package com.elioo.baymax.wa.adapter.in;

import com.elioo.baymax.common.error.ErrorResponseFilter;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.wa.application.service.WaIntakeService;
import com.elioo.baymax.wa.domain.WaInbound;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BMX-10 acceptance for the webhook itself: the handshake succeeds only with the right verify token, and a
 * callback that is unsigned or wrongly signed is refused with nothing processed.
 */
class WaWebhookRouterTest {

    private static final String VERIFY_TOKEN = "verify-token-under-test";
    private static final String APP_SECRET = "app-secret-under-test";
    private static final String BODY = """
            {"object":"whatsapp_business_account","entry":[{"changes":[{"value":{"messages":[
              {"id":"%s","type":"image","from":"8801700000000"}]}}]}]}""".formatted(
            com.elioo.baymax.wa.WaFixtures.wamid("8801700000000"));

    private static final String WAMID = com.elioo.baymax.wa.WaFixtures.wamid("8801700000000");

    /** A fresh message by default: claimed (the store did not have it) and handled. */
    private WebTestClient client() {
        WaIntakeService intake = mock(WaIntakeService.class);
        // an unstubbed Mono-returning mock answers null, which would fail the claim into a 503 for a reason that is
        // not the test's and make "accepted" meaningless
        when(intake.claim(any())).thenAnswer(i -> Mono.just(i.getArgument(0)));
        when(intake.handle(any())).thenReturn(Mono.just(WaIntakeService.Outcome.ACCEPTED));
        return client(intake);
    }

    private WebTestClient client(WaIntakeService intake) {
        BaymaxProperties props = new BaymaxProperties();
        props.getWa().setEnabled(true);
        props.getWa().setVerifyToken(VERIFY_TOKEN);
        props.getWa().setAppSecret(APP_SECRET);
        WaWebhookHandler handler = new WaWebhookHandler(props, new ObjectMapper(), intake);
        return WebTestClient.bindToRouterFunction(
                new WaWebhookRouter().baymaxWaRoutes(handler, new ErrorResponseFilter())).build();
    }

    @Test
    void theHandshakeEchoesTheChallengeWithTheRightVerifyToken() {
        client().get().uri(b -> b.path(WaWebhookRouter.WEBHOOK_PATH)
                        .queryParam("hub.mode", "subscribe")
                        .queryParam("hub.verify_token", VERIFY_TOKEN)
                        .queryParam("hub.challenge", "1158201444").build())
                .exchange().expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_PLAIN)
                .expectBody(String.class).isEqualTo("1158201444");
    }

    @Test
    void theHandshakeIsRefusedWithTheWrongVerifyTokenAndNeverEchoesTheChallenge() {
        client().get().uri(b -> b.path(WaWebhookRouter.WEBHOOK_PATH)
                        .queryParam("hub.mode", "subscribe")
                        .queryParam("hub.verify_token", "not-the-token")
                        .queryParam("hub.challenge", "1158201444").build())
                .exchange().expectStatus().isForbidden()
                .expectBody(String.class).value(b -> assertThat(b).doesNotContain("1158201444"));
    }

    @Test
    void aSignedCallbackIsAccepted() throws Exception {
        byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
        client().post().uri(WaWebhookRouter.WEBHOOK_PATH)
                .header("X-Hub-Signature-256", WaSignature.sign(body, APP_SECRET))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isOk();
    }

    @Test
    void anUnsignedCallbackIsRefused() {
        client().post().uri(WaWebhookRouter.WEBHOOK_PATH)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(BODY.getBytes(StandardCharsets.UTF_8))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void aCallbackSignedWithTheWrongSecretIsRefused() throws Exception {
        byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
        client().post().uri(WaWebhookRouter.WEBHOOK_PATH)
                .header("X-Hub-Signature-256", WaSignature.sign(body, "someone-elses-secret"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isForbidden();
    }

    /** A valid signature over a different body must not carry a swapped payload through. */
    @Test
    void aValidSignatureOverADifferentBodyIsRefused() throws Exception {
        String header = WaSignature.sign("{\"object\":\"other\"}".getBytes(StandardCharsets.UTF_8), APP_SECRET);
        client().post().uri(WaWebhookRouter.WEBHOOK_PATH)
                .header("X-Hub-Signature-256", header)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(BODY.getBytes(StandardCharsets.UTF_8))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void aMessageAlreadyClaimedIsAcknowledgedButNeverHandledAgain() throws Exception {
        WaIntakeService intake = mock(WaIntakeService.class);
        when(intake.claim(any())).thenReturn(Mono.empty());           // the store already has this message
        byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
        client(intake).post().uri(WaWebhookRouter.WEBHOOK_PATH)
                .header("X-Hub-Signature-256", WaSignature.sign(body, APP_SECRET))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isOk();
        verify(intake).claim(argThat(in -> WAMID.equals(in.providerMessageId()) && in.type() == WaInbound.Type.IMAGE));
        // handling is detached: give it the time a real one would take before asserting it never came
        verify(intake, after(500).never()).handle(any());
    }

    @Test
    void aFreshMessageIsHandledOnce() throws Exception {
        WaIntakeService intake = mock(WaIntakeService.class);
        when(intake.claim(any())).thenAnswer(i -> Mono.just(i.getArgument(0)));
        when(intake.handle(any())).thenReturn(Mono.just(WaIntakeService.Outcome.ACCEPTED));
        byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
        client(intake).post().uri(WaWebhookRouter.WEBHOOK_PATH)
                .header("X-Hub-Signature-256", WaSignature.sign(body, APP_SECRET))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isOk();
        verify(intake, timeout(2000).times(1)).handle(argThat(in -> WAMID.equals(in.providerMessageId())));
    }

    /** A message we could not record is not acknowledged: Meta retries, and the claim makes the retry safe. */
    @Test
    void whenTheMessageCannotBeRecordedMetaIsAskedToRetry() throws Exception {
        WaIntakeService intake = mock(WaIntakeService.class);
        when(intake.claim(any())).thenReturn(Mono.error(new org.springframework.dao.DataAccessResourceFailureException("db down")));
        byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
        client(intake).post().uri(WaWebhookRouter.WEBHOOK_PATH)
                .header("X-Hub-Signature-256", WaSignature.sign(body, APP_SECRET))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isEqualTo(503);
        verify(intake, after(500).never()).handle(any());
    }

    /** A retry will never fix bad JSON, so it is still a 200, and nothing is claimed. */
    @Test
    void aSignedBodyThatIsNotJsonIsStillA200() throws Exception {
        WaIntakeService intake = mock(WaIntakeService.class);
        byte[] body = "not json".getBytes(StandardCharsets.UTF_8);
        client(intake).post().uri(WaWebhookRouter.WEBHOOK_PATH)
                .header("X-Hub-Signature-256", WaSignature.sign(body, APP_SECRET))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isOk();
        verify(intake, never()).claim(any());
    }

    /** One callback can carry two messages; the second is claimed and handled even when the first was a duplicate. */
    @Test
    void eachMessageInACallbackIsClaimedOnItsOwn() throws Exception {
        String first = com.elioo.baymax.wa.WaFixtures.wamid("8801700000000", 31);
        String second = com.elioo.baymax.wa.WaFixtures.wamid("8801700000000", 32);
        WaIntakeService intake = mock(WaIntakeService.class);
        when(intake.claim(any())).thenAnswer(i -> first.equals(i.<WaInbound>getArgument(0).providerMessageId())
                ? Mono.empty() : Mono.just(i.getArgument(0)));
        when(intake.handle(any())).thenReturn(Mono.just(WaIntakeService.Outcome.REPLIED));
        byte[] body = ("{\"entry\":[{\"changes\":[{\"value\":{\"messages\":["
                + "{\"id\":\"" + first + "\",\"type\":\"text\",\"from\":\"8801700000000\",\"text\":{\"body\":\"Hi\"}},"
                + "{\"id\":\"" + second + "\",\"type\":\"text\",\"from\":\"8801700000000\",\"text\":{\"body\":\"Hi\"}}"
                + "]}}]}]}").getBytes(StandardCharsets.UTF_8);
        client(intake).post().uri(WaWebhookRouter.WEBHOOK_PATH)
                .header("X-Hub-Signature-256", WaSignature.sign(body, APP_SECRET))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isOk();
        verify(intake, timeout(2000).times(1)).handle(argThat(in -> second.equals(in.providerMessageId())));
        verify(intake, after(300).never()).handle(argThat(in -> first.equals(in.providerMessageId())));
    }

    /** A phone number never reaches a log line in clear — BMX-5 keeps it in family_account alone. */
    @Test
    void numbersAreMaskedToTheLastFourDigits() {
        assertThat(WaWebhookHandler.maskedNumber("8801700000000")).isEqualTo("****0000");
        assertThat(WaWebhookHandler.maskedNumber("123")).isEqualTo("****");
        assertThat(WaWebhookHandler.maskedNumber(null)).isEqualTo("****");
    }
}
