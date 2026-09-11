package com.elioo.healthcare.llm.provider.openai;

import com.elioo.healthcare.llm.config.LlmProperties;
import com.elioo.healthcare.llm.exception.LlmException;
import com.elioo.healthcare.llm.model.LlmRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleLlmClientTest {

    private static final String OK_BODY = """
            {"id":"x","model":"openai/gpt-oss-120b",
             "choices":[{"message":{"role":"assistant","content":"{\\"summary\\":\\"fine\\"}"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":12,"completion_tokens":7}}
            """;

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<ClientRequest> sent = new ArrayList<>();

    private OpenAiCompatibleLlmClient client(IntFunction<ClientResponse> responder) {
        AtomicInteger calls = new AtomicInteger();
        WebClient web = WebClient.builder()
                .exchangeFunction(req -> {
                    sent.add(req);
                    return Mono.just(responder.apply(calls.incrementAndGet()));
                })
                .build();
        LlmProperties props = new LlmProperties();
        props.getGroq().setApiKey("gsk_test");
        // fast retry backoff for tests; production default is 1 s
        return new OpenAiCompatibleLlmClient("groq", props.getGroq(), props, web, mapper,
                Retry.backoff(2, Duration.ofMillis(1)));
    }

    private static ClientResponse json(HttpStatus status, String body) {
        return ClientResponse.create(status)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    @Test
    void sendsChatCompletionWithJsonModeAndMapsResponse() throws Exception {
        OpenAiCompatibleLlmClient c = client(n -> json(HttpStatus.OK, OK_BODY));

        StepVerifier.create(c.invoke(LlmRequest.forJson("Summarise HbA1c 7.8%", "You are a clinician.")))
                .assertNext(r -> {
                    assertThat(r.content()).isEqualTo("{\"summary\":\"fine\"}");
                    assertThat(r.stopReason()).isEqualTo("stop");
                    assertThat(r.usage().inputTokens()).isEqualTo(12);
                    assertThat(r.usage().outputTokens()).isEqualTo(7);
                    assertThat(r.modelId()).isEqualTo("openai/gpt-oss-120b");
                    assertThat(r.metadata()).containsEntry("provider", "groq");
                })
                .verifyComplete();

        ClientRequest req = sent.get(0);
        assertThat(req.url().toString()).isEqualTo("https://api.groq.com/openai/v1/chat/completions");
        assertThat(req.headers().getFirst("Authorization")).isEqualTo("Bearer gsk_test");
        JsonNode body = mapper.readTree(c.lastRequestBody());
        assertThat(body.get("model").asText()).isEqualTo("openai/gpt-oss-120b");
        assertThat(body.get("messages").get(0).get("role").asText()).isEqualTo("system");
        assertThat(body.get("messages").get(1).get("content").asText()).contains("HbA1c");
        assertThat(body.get("response_format").get("type").asText()).isEqualTo("json_object");
        assertThat(body.get("max_tokens").asInt()).isEqualTo(8192);
    }

    @Test
    void omitsJsonModeAndSystemWhenNotRequested() throws Exception {
        OpenAiCompatibleLlmClient c = client(n -> json(HttpStatus.OK, OK_BODY));
        StepVerifier.create(c.invoke(LlmRequest.custom("hi", null, "custom-model", 50, 0.9)))
                .expectNextCount(1)
                .verifyComplete();
        JsonNode body = mapper.readTree(c.lastRequestBody());
        assertThat(body.has("response_format")).isFalse();
        assertThat(body.get("messages").size()).isEqualTo(1);
        assertThat(body.get("model").asText()).isEqualTo("custom-model");
        assertThat(body.get("max_tokens").asInt()).isEqualTo(50);
        assertThat(body.get("temperature").asDouble()).isEqualTo(0.9);
    }

    @Test
    void unauthorizedBecomesLlmExceptionWithStatus() {
        OpenAiCompatibleLlmClient c = client(n -> json(HttpStatus.UNAUTHORIZED, "{\"error\":{\"message\":\"Invalid API Key\"}}"));
        StepVerifier.create(c.invoke(LlmRequest.standard("hi")))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(LlmException.class).hasMessageContaining("Invalid API Key");
                    assertThat(((LlmException) e).httpStatus()).isEqualTo(401);
                    assertThat(((LlmException) e).provider()).isEqualTo("groq");
                })
                .verify();
        assertThat(sent).hasSize(1);
    }

    @Test
    void rateLimitIsRetriedThenSucceeds() {
        OpenAiCompatibleLlmClient c = client(n -> n < 3
                ? json(HttpStatus.TOO_MANY_REQUESTS, "{\"error\":{\"message\":\"slow down\"}}")
                : json(HttpStatus.OK, OK_BODY));
        StepVerifier.create(c.invoke(LlmRequest.standard("hi")))
                .expectNextCount(1)
                .verifyComplete();
        assertThat(sent).hasSize(3);
    }

    @Test
    void rateLimitExhaustedFailsWithLastError() {
        OpenAiCompatibleLlmClient c = client(n -> json(HttpStatus.TOO_MANY_REQUESTS, "{\"error\":{\"message\":\"slow down\"}}"));
        StepVerifier.create(c.invoke(LlmRequest.standard("hi")))
                .expectErrorSatisfies(e -> assertThat(((LlmException) e).httpStatus()).isEqualTo(429))
                .verify();
        assertThat(sent).hasSize(3);
    }
}
