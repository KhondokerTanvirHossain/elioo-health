# Provider-Neutral LLM Layer — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Clinical insights and chat run on Groq, Anthropic, OpenAI, or Bedrock, chosen by `LLM_PROVIDER`, with Groq as the default and no AWS dependency in the LLM code path.

**Architecture:** A new Gradle module `elioo-llm` owns a one-method `LlmClient` interface, the neutral request/response records, the clinical prompt layer (moved from `elioo-aws-bedrock`), and two clients: Anthropic (official Java SDK) and OpenAI-compatible (WebClient; serves Groq and OpenAI). `elioo-aws-bedrock` shrinks to a Bedrock `LlmClient`. The app's `ClinicalInsightPort` adapter is retargeted to the new types; nothing above the port changes.

**Tech Stack:** Java 21, Spring Boot 3.4.2, Reactor, Spring WebFlux `WebClient`, `com.anthropic:anthropic-java:2.62.0`, Jackson, JUnit 5, Mockito, `ApplicationContextRunner`.

**Spec:** `docs/superpowers/specs/2026-09-12-provider-neutral-llm-design.md`

## Global Constraints

- Public repo: no keys in code or commits. Keys come only from `.env.local` (laptop) and `/home/ec2-user/medscribe.env` (server); both already contain `LLM_PROVIDER=groq`, `GROQ_API_KEY`, `ANTHROPIC_API_KEY`.
- Anthropic SDK version `2.62.0`; Anthropic default model `claude-opus-5`; `temperature` must not be sent to Anthropic; thinking adaptive; effort default `medium`.
- Groq base URL `https://api.groq.com/openai/v1`, default model `openai/gpt-oss-120b`; OpenAI base URL `https://api.openai.com/v1`, default model `gpt-4o`.
- Package root for the new module: `com.elioo.healthcare.llm`. No AWS types anywhere in `elioo-llm`.
- After every task `./gradlew build -q` must pass (root build, all modules). Commit after every task with trailer `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- Prompt or response bodies are logged at DEBUG only; INFO logs carry provider, model, prompt length, tokens, duration.
- Work on `main` (user's standing choice); pushes trigger CI/CD to EC2, so push only at the end (Task 7).

---

## File map

| Path | Responsibility |
|---|---|
| `settings.gradle` | add `include 'elioo-llm'` |
| `elioo-llm/build.gradle` | module deps (reactor, jackson, anthropic SDK, spring-webflux, reactor-netty) |
| `elioo-llm/src/main/java/com/elioo/healthcare/llm/api/LlmClient.java` | the provider interface |
| `elioo-llm/.../llm/model/{LlmRequest,LlmResponse,TokenUsage}.java` | moved from bedrock; `LlmRequest` gains `jsonOutput` |
| `elioo-llm/.../llm/exception/LlmException.java` | provider errors |
| `elioo-llm/.../llm/json/LlmJsonExtractor.java` | fence stripping + first balanced JSON block |
| `elioo-llm/.../llm/health/**` | moved prompt layer, renamed `HealthInsightService*`, `HealthInsightException` |
| `elioo-llm/.../llm/provider/openai/OpenAiCompatibleLlmClient.java` | Groq + OpenAI |
| `elioo-llm/.../llm/provider/anthropic/AnthropicLlmClient.java` | Anthropic |
| `elioo-llm/.../llm/config/{LlmProperties,LlmAutoConfiguration}.java` | selection by `llm.provider` |
| `elioo-llm/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | registers `LlmAutoConfiguration` |
| `elioo-aws-bedrock/**` | depends on `elioo-llm`; `BedrockServiceImpl implements LlmClient`; health package and `BedrockClientConfig` deleted |
| `medscribe-ai/.../medicalreport/adapter/out/llm/LlmInsightAdapter.java` | renamed from `BedrockAdapter` |
| `medscribe-ai/.../llm/{handler/LlmApiHandler,router/LlmApiRouter,dto/InvokeRequest}.java` | `/api/llm/*` |
| `medscribe-ai/.../aws/textract/{handler,router}` | conditional on `aws.textract.enabled` |
| `medscribe-ai/src/main/resources/application.properties`, `application-aws.properties` | `llm.*` block; Textract and Bedrock off |
| `medscribe.env.example` | `LLM_PROVIDER` and key names |
| Deleted | `medscribe-ai/.../aws/bedrock/**`, `medscribe-ai/.../core/config/AwsConfig.java`, `BedrockAdapterIntegrationTest.java` |

---

### Task 1: `elioo-llm` module skeleton, neutral types, JSON extractor

**Files:**
- Modify: `settings.gradle`
- Create: `elioo-llm/build.gradle`
- Move: `elioo-aws-bedrock/src/main/java/com/elioo/healthcare/aws/bedrock/model/{LlmRequest,LlmResponse,TokenUsage}.java` → `elioo-llm/src/main/java/com/elioo/healthcare/llm/model/`
- Move: `elioo-aws-bedrock/src/test/java/com/elioo/healthcare/aws/bedrock/model/*Test.java` → `elioo-llm/src/test/java/com/elioo/healthcare/llm/model/`
- Create: `elioo-llm/src/main/java/com/elioo/healthcare/llm/api/LlmClient.java`, `.../llm/exception/LlmException.java`, `.../llm/json/LlmJsonExtractor.java`
- Test: `elioo-llm/src/test/java/com/elioo/healthcare/llm/json/LlmJsonExtractorTest.java`
- Modify: `elioo-aws-bedrock/build.gradle`, every file importing `com.elioo.healthcare.aws.bedrock.model.*`

**Interfaces:**
- Produces: `LlmClient { Mono<LlmResponse> invoke(LlmRequest); String providerName(); }`; `LlmRequest(userPrompt, systemPrompt, modelId, maxTokens, temperature, topP, stopSequences, metadata, jsonOutput)` with factories `standard`, `withSystemPrompt`, `custom`, `forJson(userPrompt, systemPrompt)`; `LlmJsonExtractor.extract(String)`; `LlmException(String message)`, `LlmException(String message, Throwable cause)`, `LlmException(String provider, int httpStatus, String message)` with `provider()` and `httpStatus()` accessors.

- [ ] **Step 1: Register the module and write its build file**

`settings.gradle`, after the `include 'elioo-aws-common'` block header comment add:

```groovy
// Provider-neutral LLM library (Groq / OpenAI / Anthropic / Bedrock adapters)
include 'elioo-llm'
```

`elioo-llm/build.gradle`:

```groovy
/*
 * Elioo LLM Module
 *
 * Provider-neutral LLM layer: one LlmClient interface, request/response records,
 * the clinical prompt layer, and clients for Anthropic and any OpenAI-compatible
 * API (OpenAI, Groq, ...). No cloud-vendor SDK other than the Anthropic client.
 */
plugins {
    id 'java-library'
    id 'maven-publish'
}

version = '0.1.0'
description = 'Provider-neutral LLM library for Elioo Healthcare'

dependencies {
    api 'io.projectreactor:reactor-core'
    api 'com.fasterxml.jackson.core:jackson-databind'
    api 'com.anthropic:anthropic-java:2.62.0'
    api 'org.springframework:spring-webflux'
    implementation 'io.projectreactor.netty:reactor-netty-http'
    api 'org.slf4j:slf4j-api'

    compileOnly 'org.springframework.boot:spring-boot-autoconfigure'
    annotationProcessor 'org.springframework.boot:spring-boot-autoconfigure-processor'
    annotationProcessor 'org.springframework.boot:spring-boot-configuration-processor'

    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'io.projectreactor:reactor-test'
}

java {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        maven(MavenPublication) {
            from components.java
            groupId = 'com.elioo.healthcare'
            artifactId = 'elioo-llm'
        }
    }
}
```

`elioo-aws-bedrock/build.gradle` dependencies: add `api project(':elioo-llm')` as the first line.

- [ ] **Step 2: Move the three records and their tests, then repoint packages**

```bash
mkdir -p elioo-llm/src/main/java/com/elioo/healthcare/llm/model elioo-llm/src/test/java/com/elioo/healthcare/llm/model
git mv elioo-aws-bedrock/src/main/java/com/elioo/healthcare/aws/bedrock/model/LlmRequest.java  elioo-llm/src/main/java/com/elioo/healthcare/llm/model/
git mv elioo-aws-bedrock/src/main/java/com/elioo/healthcare/aws/bedrock/model/LlmResponse.java elioo-llm/src/main/java/com/elioo/healthcare/llm/model/
git mv elioo-aws-bedrock/src/main/java/com/elioo/healthcare/aws/bedrock/model/TokenUsage.java  elioo-llm/src/main/java/com/elioo/healthcare/llm/model/
git mv elioo-aws-bedrock/src/test/java/com/elioo/healthcare/aws/bedrock/model/LlmRequestTest.java  elioo-llm/src/test/java/com/elioo/healthcare/llm/model/
git mv elioo-aws-bedrock/src/test/java/com/elioo/healthcare/aws/bedrock/model/LlmResponseTest.java elioo-llm/src/test/java/com/elioo/healthcare/llm/model/
git mv elioo-aws-bedrock/src/test/java/com/elioo/healthcare/aws/bedrock/model/TokenUsageTest.java  elioo-llm/src/test/java/com/elioo/healthcare/llm/model/
grep -rl 'com.elioo.healthcare.aws.bedrock.model' --include='*.java' elioo-llm elioo-aws-bedrock medscribe-ai \
  | xargs sed -i '' 's/com\.elioo\.healthcare\.aws\.bedrock\.model/com.elioo.healthcare.llm.model/g'
```

- [ ] **Step 3: Add `jsonOutput` to `LlmRequest`** — replace the record header and factories in `elioo-llm/.../llm/model/LlmRequest.java`:

```java
package com.elioo.healthcare.llm.model;

import java.util.List;
import java.util.Map;

/**
 * Provider-neutral LLM invocation request.
 *
 * @param userPrompt    The user's prompt
 * @param systemPrompt  System instructions (optional)
 * @param modelId       Provider model identifier (optional; provider default when null)
 * @param maxTokens     Maximum tokens to generate (optional)
 * @param temperature   Sampling temperature (optional; ignored by providers that reject it)
 * @param topP          Nucleus sampling (optional)
 * @param stopSequences Stop sequences (optional)
 * @param metadata      Free-form metadata (optional)
 * @param jsonOutput    True when the caller will parse the reply as JSON; providers with a
 *                      JSON mode enable it, others rely on the prompt
 */
public record LlmRequest(
        String userPrompt,
        String systemPrompt,
        String modelId,
        Integer maxTokens,
        Double temperature,
        Double topP,
        List<String> stopSequences,
        Map<String, Object> metadata,
        boolean jsonOutput
) {
    public static LlmRequest standard(String userPrompt) {
        return new LlmRequest(userPrompt, null, null, null, null, null, null, null, false);
    }

    public static LlmRequest withSystemPrompt(String userPrompt, String systemPrompt) {
        return new LlmRequest(userPrompt, systemPrompt, null, null, null, null, null, null, false);
    }

    /** A request whose reply must be JSON (the prompt already says so). */
    public static LlmRequest forJson(String userPrompt, String systemPrompt) {
        return new LlmRequest(userPrompt, systemPrompt, null, null, null, null, null, null, true);
    }

    public static LlmRequest custom(String userPrompt, String systemPrompt, String modelId,
                                    Integer maxTokens, Double temperature) {
        return new LlmRequest(userPrompt, systemPrompt, modelId, maxTokens, temperature, null, null, null, false);
    }

    public boolean isValid() { return userPrompt != null && !userPrompt.isBlank(); }
    public boolean hasSystemPrompt() { return systemPrompt != null && !systemPrompt.isBlank(); }
    public boolean hasCustomModelId() { return modelId != null && !modelId.isBlank(); }
    public boolean hasCustomParameters() { return maxTokens != null || temperature != null || topP != null; }
}
```

Then fix every `new LlmRequest(` call with 8 arguments to pass a trailing `false` (grep: `grep -rn 'new LlmRequest(' --include='*.java' elioo-llm elioo-aws-bedrock medscribe-ai`).

- [ ] **Step 4: Interface and exception**

`elioo-llm/.../llm/api/LlmClient.java`:

```java
package com.elioo.healthcare.llm.api;

import com.elioo.healthcare.llm.model.LlmRequest;
import com.elioo.healthcare.llm.model.LlmResponse;
import reactor.core.publisher.Mono;

/** One call to a text-generation model, independent of the vendor behind it. */
public interface LlmClient {

    /** Sends the request and returns the model's reply. Errors surface as {@code LlmException}. */
    Mono<LlmResponse> invoke(LlmRequest request);

    /** Short lower-case name used in logs and metadata, e.g. "groq", "anthropic", "bedrock". */
    String providerName();
}
```

`elioo-llm/.../llm/exception/LlmException.java`:

```java
package com.elioo.healthcare.llm.exception;

public class LlmException extends RuntimeException {
    private final String provider;
    private final Integer httpStatus;

    public LlmException(String message) { this(null, null, message, null); }
    public LlmException(String message, Throwable cause) { this(null, null, message, cause); }
    public LlmException(String provider, int httpStatus, String message) { this(provider, httpStatus, message, null); }

    private LlmException(String provider, Integer httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.provider = provider;
        this.httpStatus = httpStatus;
    }

    public String provider() { return provider; }
    public Integer httpStatus() { return httpStatus; }
    public boolean isRetryable() { return httpStatus != null && (httpStatus == 429 || httpStatus >= 500); }
}
```

- [ ] **Step 5: Write the failing extractor test** `elioo-llm/src/test/java/com/elioo/healthcare/llm/json/LlmJsonExtractorTest.java`:

```java
package com.elioo.healthcare.llm.json;

import com.elioo.healthcare.llm.exception.LlmException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmJsonExtractorTest {

    @Test
    void returnsPlainJsonUnchanged() {
        assertThat(LlmJsonExtractor.extract("{\"a\":1}")).isEqualTo("{\"a\":1}");
    }

    @Test
    void stripsMarkdownFences() {
        assertThat(LlmJsonExtractor.extract("```json\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}");
        assertThat(LlmJsonExtractor.extract("```\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}");
    }

    @Test
    void ignoresProseAroundTheObject() {
        String content = "Here is the analysis:\n{\"summary\":\"ok\",\"n\":{\"x\":[1,2]}}\nLet me know if you need more.";
        assertThat(LlmJsonExtractor.extract(content)).isEqualTo("{\"summary\":\"ok\",\"n\":{\"x\":[1,2]}}");
    }

    @Test
    void handlesBracesInsideStrings() {
        String content = "{\"text\":\"a } b { c\",\"ok\":true}";
        assertThat(LlmJsonExtractor.extract(content)).isEqualTo(content);
    }

    @Test
    void supportsTopLevelArrays() {
        assertThat(LlmJsonExtractor.extract("Result: [{\"a\":1},{\"b\":2}] done")).isEqualTo("[{\"a\":1},{\"b\":2}]");
    }

    @Test
    void throwsWhenNoJsonPresent() {
        assertThatThrownBy(() -> LlmJsonExtractor.extract("I cannot help with that."))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("No JSON");
    }

    @Test
    void throwsOnNull() {
        assertThatThrownBy(() -> LlmJsonExtractor.extract(null)).isInstanceOf(LlmException.class);
    }
}
```

- [ ] **Step 6: Run it, expect compile failure** — `./gradlew :elioo-llm:test --tests '*LlmJsonExtractorTest' -q` → fails with `cannot find symbol LlmJsonExtractor`.

- [ ] **Step 7: Implement** `elioo-llm/.../llm/json/LlmJsonExtractor.java`:

```java
package com.elioo.healthcare.llm.json;

import com.elioo.healthcare.llm.exception.LlmException;

/** Pulls the JSON document out of a model reply that may contain fences or prose. */
public final class LlmJsonExtractor {

    private LlmJsonExtractor() {}

    public static String extract(String content) {
        if (content == null || content.isBlank()) {
            throw new LlmException("No JSON in empty model response");
        }
        String text = content.strip();
        // 1. Markdown fences: ```json ... ``` or ``` ... ```
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int closing = text.lastIndexOf("```");
            if (firstNewline > 0 && closing > firstNewline) {
                text = text.substring(firstNewline + 1, closing).strip();
            }
        }
        // 2. First balanced { } or [ ] block, respecting string literals
        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{' || c == '[') { start = i; break; }
        }
        if (start < 0) {
            throw new LlmException("No JSON object or array found in model response");
        }
        char open = text.charAt(start);
        char close = open == '{' ? '}' : ']';
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) { escaped = false; }
                else if (c == '\\') { escaped = true; }
                else if (c == '"') { inString = false; }
                continue;
            }
            if (c == '"') { inString = true; continue; }
            if (c == open) depth++;
            else if (c == close) {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        throw new LlmException("No JSON object or array found in model response (unbalanced)");
    }
}
```

- [ ] **Step 8: Run tests** — `./gradlew :elioo-llm:test -q && ./gradlew build -q && echo OK` → `OK` (the moved record tests pass, bedrock and app still compile with repointed imports).

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat(llm): add elioo-llm module with LlmClient, neutral records, JSON extractor

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: Move the clinical prompt layer into `elioo-llm`

**Files:**
- Move: `elioo-aws-bedrock/src/main/java/com/elioo/healthcare/aws/bedrock/health/{api,dto,prompt,service,exception}` → `elioo-llm/src/main/java/com/elioo/healthcare/llm/health/`
- Delete: `elioo-aws-bedrock/.../health/config/{BedrockHealthAutoConfiguration,BedrockHealthProperties}.java`, `elioo-aws-bedrock/.../config/BedrockClientConfig.java`
- Modify: `elioo-aws-bedrock/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Rename classes: `BedrockHealthService`→`HealthInsightService`, `BedrockHealthServiceImpl`→`HealthInsightServiceImpl`, `BedrockHealthServiceException`→`HealthInsightException`
- Test: `elioo-llm/src/test/java/com/elioo/healthcare/llm/health/service/HealthInsightServiceImplTest.java`

**Interfaces:**
- Produces: `HealthInsightService` (same 7 methods as before), `HealthInsightServiceImpl(LlmClient, PromptTemplateEngine, ObjectMapper)`, `HealthInsightException`. `PromptTemplateEngine`/`DefaultPromptTemplateEngine` unchanged apart from package.

- [ ] **Step 1: Move and rename**

```bash
mkdir -p elioo-llm/src/main/java/com/elioo/healthcare/llm/health
for d in api dto prompt service exception; do
  git mv elioo-aws-bedrock/src/main/java/com/elioo/healthcare/aws/bedrock/health/$d elioo-llm/src/main/java/com/elioo/healthcare/llm/health/$d
done
git rm -rq elioo-aws-bedrock/src/main/java/com/elioo/healthcare/aws/bedrock/health
git rm -q elioo-aws-bedrock/src/main/java/com/elioo/healthcare/aws/bedrock/config/BedrockClientConfig.java
git mv elioo-llm/src/main/java/com/elioo/healthcare/llm/health/api/BedrockHealthService.java elioo-llm/src/main/java/com/elioo/healthcare/llm/health/api/HealthInsightService.java
git mv elioo-llm/src/main/java/com/elioo/healthcare/llm/health/service/BedrockHealthServiceImpl.java elioo-llm/src/main/java/com/elioo/healthcare/llm/health/service/HealthInsightServiceImpl.java
git mv elioo-llm/src/main/java/com/elioo/healthcare/llm/health/exception/BedrockHealthServiceException.java elioo-llm/src/main/java/com/elioo/healthcare/llm/health/exception/HealthInsightException.java
grep -rl 'aws\.bedrock\.health\|BedrockHealthService\|BedrockHealthServiceImpl\|BedrockHealthServiceException' --include='*.java' elioo-llm elioo-aws-bedrock medscribe-ai \
  | xargs sed -i '' \
      -e 's/com\.elioo\.healthcare\.aws\.bedrock\.health/com.elioo.healthcare.llm.health/g' \
      -e 's/BedrockHealthServiceException/HealthInsightException/g' \
      -e 's/BedrockHealthServiceImpl/HealthInsightServiceImpl/g' \
      -e 's/BedrockHealthService/HealthInsightService/g'
printf 'com.elioo.healthcare.aws.bedrock.config.BedrockAutoConfiguration\n' > elioo-aws-bedrock/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

- [ ] **Step 2: Rewrite `HealthInsightServiceImpl`** (`elioo-llm/.../llm/health/service/HealthInsightServiceImpl.java`) — same six prompt methods, now on `LlmClient`, no caching, `jsonOutput=true`, tolerant parsing:

```java
package com.elioo.healthcare.llm.health.service;

import com.elioo.healthcare.llm.api.LlmClient;
import com.elioo.healthcare.llm.health.api.HealthInsightService;
import com.elioo.healthcare.llm.health.dto.*;
import com.elioo.healthcare.llm.health.exception.HealthInsightException;
import com.elioo.healthcare.llm.health.prompt.PromptTemplateEngine;
import com.elioo.healthcare.llm.json.LlmJsonExtractor;
import com.elioo.healthcare.llm.model.LlmRequest;
import com.elioo.healthcare.llm.model.LlmResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.function.Supplier;

/**
 * Clinical prompt layer on top of any {@link LlmClient}: builds a prompt, sends it,
 * parses the JSON reply into a DTO. Stateless; no caching (the pipeline calls each
 * method once per report).
 */
@Slf4j
@RequiredArgsConstructor
public class HealthInsightServiceImpl implements HealthInsightService {

    private final LlmClient llmClient;
    private final PromptTemplateEngine promptEngine;
    private final ObjectMapper objectMapper;

    @Override
    public Mono<ClinicalInsightResponse> generateClinicalInsights(ClinicalInsightRequest request) {
        if (request == null || !request.isValid()) {
            return Mono.error(new HealthInsightException("Invalid clinical insight request: medical data is required"));
        }
        InsightOptions options = request.options() != null ? request.options() : InsightOptions.defaultPatient();
        String system = options.targetAudience() != null
                ? promptEngine.getSystemPrompt(options.targetAudience()) : promptEngine.getSystemPrompt();
        return run("clinicalInsights",
                () -> promptEngine.buildClinicalInsightPrompt(request.medicalData(), request.patientContext(), options),
                system, ClinicalInsightResponse.class);
    }

    @Override
    public Mono<SummaryResponse> generateSummary(SummaryRequest request) {
        if (request == null || !request.isValid()) {
            return Mono.error(new HealthInsightException("Invalid summary request: medical data is required"));
        }
        SummaryOptions options = request.options() != null && request.options().targetAudience() != null
                ? request.options() : SummaryOptions.defaultPatient();
        return run("summary",
                () -> promptEngine.buildSummaryPrompt(request.medicalData(), request.patientContext(), options),
                promptEngine.getSystemPrompt(options.targetAudience()), SummaryResponse.class);
    }

    @Override
    public Mono<RiskAssessmentResponse> assessRisk(RiskAssessmentRequest request) {
        if (request == null || !request.isValid()) {
            return Mono.error(new HealthInsightException("Invalid risk assessment request: medical data is required"));
        }
        RiskAssessmentOptions options = request.options() != null ? request.options() : RiskAssessmentOptions.defaultOptions();
        return run("riskAssessment",
                () -> promptEngine.buildRiskAssessmentPrompt(request.medicalData(), request.patientContext(), options),
                promptEngine.getSystemPrompt(TargetAudience.PROVIDER), RiskAssessmentResponse.class);
    }

    @Override
    public Mono<RecommendationResponse> generateRecommendations(RecommendationRequest request) {
        if (request == null || !request.isValid()) {
            return Mono.error(new HealthInsightException("Invalid recommendation request: medical findings are required"));
        }
        RecommendationOptions options = request.options() != null ? request.options() : RecommendationOptions.defaultOptions();
        return run("recommendations",
                () -> promptEngine.buildRecommendationPrompt(request.medicalFindings(), request.patientContext(), options),
                promptEngine.getSystemPrompt(TargetAudience.PROVIDER), RecommendationResponse.class);
    }

    @Override
    public Mono<TrendAnalysisResponse> analyzeTrends(TrendAnalysisRequest request) {
        if (request == null || !request.isValid()) {
            return Mono.error(new HealthInsightException("Invalid trend analysis request: valid historical data is required"));
        }
        TrendAnalysisOptions options = request.options() != null ? request.options() : TrendAnalysisOptions.defaultOptions();
        return run("trendAnalysis",
                () -> promptEngine.buildTrendAnalysisPrompt(request.historicalData(), request.patientContext(), options),
                promptEngine.getSystemPrompt(TargetAudience.PROVIDER), TrendAnalysisResponse.class);
    }

    @Override
    public Mono<EducationalContentResponse> generateEducationalContent(EducationalContentRequest request) {
        if (request == null || !request.isValid()) {
            return Mono.error(new HealthInsightException("Invalid educational content request: topic is required"));
        }
        EducationalContentOptions options = request.options() != null ? request.options() : EducationalContentOptions.defaultPatient();
        return run("educationalContent",
                () -> promptEngine.buildEducationalContentPrompt(request.topic(), request.patientContext(), options),
                promptEngine.getSystemPrompt(TargetAudience.PATIENT), EducationalContentResponse.class);
    }

    @Override
    public <T> Mono<T> executeCustomPrompt(String prompt, Class<T> responseClass) {
        if (prompt == null || prompt.isBlank()) {
            return Mono.error(new HealthInsightException("Prompt cannot be null or empty"));
        }
        return run("custom:" + responseClass.getSimpleName(), () -> prompt, null, responseClass);
    }

    private <T> Mono<T> run(String operation, Supplier<String> userPrompt, String systemPrompt, Class<T> type) {
        return Mono.fromCallable(() -> LlmRequest.forJson(userPrompt.get(), systemPrompt))
                .doOnNext(req -> log.info("[{}] sending {} chars to {}", operation, req.userPrompt().length(), llmClient.providerName()))
                .flatMap(llmClient::invoke)
                .map(response -> parse(operation, response, type))
                .onErrorMap(e -> e instanceof HealthInsightException ? e
                        : new HealthInsightException("Health insight operation '" + operation + "' failed: " + e.getMessage(), e));
    }

    private <T> T parse(String operation, LlmResponse response, Class<T> type) {
        if (response == null || !response.hasContent()) {
            throw new HealthInsightException("[" + operation + "] empty response from " + llmClient.providerName());
        }
        String json;
        try {
            json = LlmJsonExtractor.extract(response.content());
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            String head = response.content().substring(0, Math.min(200, response.content().length()));
            log.warn("[{}] could not parse {} reply from {} ({}) as {}: {}", operation, response.modelId(),
                    llmClient.providerName(), e.getClass().getSimpleName(), type.getSimpleName(), head);
            log.debug("[{}] full unparseable content: {}", operation, response.content());
            throw new HealthInsightException("Failed to parse " + llmClient.providerName() + " reply into "
                    + type.getSimpleName() + ": " + head, e);
        }
    }
}
```

Also remove the `@Service` and `@Cacheable` imports left in the file header by the move (the rewrite above has none), and delete the `@Component` annotation from `DefaultPromptTemplateEngine` (beans are created by auto-configuration in Task 5).

- [ ] **Step 3: Write the failing service test** `elioo-llm/src/test/java/com/elioo/healthcare/llm/health/service/HealthInsightServiceImplTest.java`:

```java
package com.elioo.healthcare.llm.health.service;

import com.elioo.healthcare.llm.api.LlmClient;
import com.elioo.healthcare.llm.health.dto.*;
import com.elioo.healthcare.llm.health.exception.HealthInsightException;
import com.elioo.healthcare.llm.health.prompt.DefaultPromptTemplateEngine;
import com.elioo.healthcare.llm.model.LlmRequest;
import com.elioo.healthcare.llm.model.LlmResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HealthInsightServiceImplTest {

    private LlmClient llm;
    private HealthInsightServiceImpl service;

    @BeforeEach
    void setUp() {
        llm = mock(LlmClient.class);
        when(llm.providerName()).thenReturn("test");
        ObjectMapper mapper = new ObjectMapper();
        service = new HealthInsightServiceImpl(llm, new DefaultPromptTemplateEngine(mapper), mapper);
    }

    @Test
    void summaryParsesFencedJsonAndRequestsJsonOutput() {
        when(llm.invoke(any())).thenReturn(Mono.just(LlmResponse.simple(
                "```json\n{\"summary\":\"Mostly normal results.\",\"keyPoints\":[\"HbA1c high\"]}\n```")));

        SummaryRequest req = new SummaryRequest(Map.of("HbA1c", "7.8%"), null, SummaryOptions.defaultPatient());

        StepVerifier.create(service.generateSummary(req))
                .assertNext(r -> assertThat(r.summary()).isEqualTo("Mostly normal results."))
                .verifyComplete();

        ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llm).invoke(captor.capture());
        assertThat(captor.getValue().jsonOutput()).isTrue();
        assertThat(captor.getValue().systemPrompt()).isNotBlank();
        assertThat(captor.getValue().userPrompt()).contains("7.8%");
    }

    @Test
    void unparseableReplyBecomesHealthInsightException() {
        when(llm.invoke(any())).thenReturn(Mono.just(LlmResponse.simple("Sorry, I cannot produce that.")));

        SummaryRequest req = new SummaryRequest(Map.of("HbA1c", "7.8%"), null, SummaryOptions.defaultPatient());

        StepVerifier.create(service.generateSummary(req))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(HealthInsightException.class)
                        .hasMessageContaining("Failed to parse test reply"))
                .verify();
    }

    @Test
    void invalidRequestFailsBeforeCallingTheModel() {
        StepVerifier.create(service.generateRecommendations(new RecommendationRequest(List.of(), null, null)))
                .expectError(HealthInsightException.class)
                .verify();
        verify(llm, org.mockito.Mockito.never()).invoke(any());
    }

    @Test
    void customPromptUsesNoSystemPrompt() {
        when(llm.invoke(any())).thenReturn(Mono.just(LlmResponse.simple("{\"answer\":42}")));
        record Answer(int answer) {}

        StepVerifier.create(service.executeCustomPrompt("Give me 42", Answer.class))
                .assertNext(a -> assertThat(a.answer()).isEqualTo(42))
                .verifyComplete();

        ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llm).invoke(captor.capture());
        assertThat(captor.getValue().systemPrompt()).isNull();
    }
}
```

If `RecommendationRequest.isValid()` treats an empty list as valid, use `null` for the findings argument instead; check the DTO before running.

- [ ] **Step 4: Run** — `./gradlew :elioo-llm:test -q` → the four tests pass. Then `./gradlew build -q` → expect the **app module** to fail on `BedrockAdapter`/handlers still using `BedrockService` for chat (Task 6 fixes); confirm the only failures are in `medscribe-ai` and that `elioo-aws-bedrock` builds: `./gradlew :elioo-aws-bedrock:build -q`.

  If `medscribe-ai` fails only because `BedrockHealthAutoConfiguration` no longer creates `HealthInsightService`, that is expected until Task 5.

- [ ] **Step 5: Commit** (the app is temporarily red; Tasks 5 and 6 restore it — commit anyway so the move is one atomic history entry)

```bash
git add -A
git commit -m "refactor(llm): move clinical prompt layer into elioo-llm as HealthInsightService

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: `OpenAiCompatibleLlmClient` (Groq and OpenAI)

**Files:**
- Create: `elioo-llm/src/main/java/com/elioo/healthcare/llm/provider/openai/OpenAiCompatibleLlmClient.java`
- Create: `elioo-llm/src/main/java/com/elioo/healthcare/llm/config/LlmProperties.java`
- Test: `elioo-llm/src/test/java/com/elioo/healthcare/llm/provider/openai/OpenAiCompatibleLlmClientTest.java`

**Interfaces:**
- Produces: `OpenAiCompatibleLlmClient(String providerName, LlmProperties.OpenAiCompatible cfg, LlmProperties defaults, WebClient webClient, ObjectMapper mapper)`; `LlmProperties` with nested `Anthropic{apiKey, model, effort}` and `OpenAiCompatible{apiKey, baseUrl, model}` plus `provider`, `defaultMaxTokens`, `defaultTemperature`, `timeoutSeconds`.

- [ ] **Step 1: Properties class** `elioo-llm/.../llm/config/LlmProperties.java`:

```java
package com.elioo.healthcare.llm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {
    /** groq | anthropic | openai | bedrock */
    private String provider = "groq";
    private int defaultMaxTokens = 8192;
    private double defaultTemperature = 0.3;
    private int timeoutSeconds = 120;

    private Anthropic anthropic = new Anthropic();
    private OpenAiCompatible groq = new OpenAiCompatible("https://api.groq.com/openai/v1", "openai/gpt-oss-120b");
    private OpenAiCompatible openai = new OpenAiCompatible("https://api.openai.com/v1", "gpt-4o");

    @Data
    public static class Anthropic {
        private String apiKey = "";
        private String model = "claude-opus-5";
        /** low | medium | high | xhigh | max */
        private String effort = "medium";
    }

    @Data
    public static class OpenAiCompatible {
        private String apiKey = "";
        private String baseUrl;
        private String model;

        public OpenAiCompatible() {}
        public OpenAiCompatible(String baseUrl, String model) { this.baseUrl = baseUrl; this.model = model; }
    }
}
```

- [ ] **Step 2: Write the failing client test** `elioo-llm/src/test/java/com/elioo/healthcare/llm/provider/openai/OpenAiCompatibleLlmClientTest.java`:

```java
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleLlmClientTest {

    private static final String OK_BODY = """
            {"id":"x","model":"openai/gpt-oss-120b",
             "choices":[{"message":{"role":"assistant","content":"{\\"summary\\":\\"fine\\"}"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":12,"completion_tokens":7}}
            """;

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<ClientRequest> sent = new ArrayList<>();

    private OpenAiCompatibleLlmClient client(java.util.function.Function<Integer, ClientResponse> responder) {
        AtomicInteger calls = new AtomicInteger();
        WebClient web = WebClient.builder()
                .exchangeFunction(req -> { sent.add(req); return Mono.just(responder.apply(calls.incrementAndGet())); })
                .build();
        LlmProperties props = new LlmProperties();
        props.getGroq().setApiKey("gsk_test");
        return new OpenAiCompatibleLlmClient("groq", props.getGroq(), props, web, mapper);
    }

    private static ClientResponse json(HttpStatus status, String body) {
        return ClientResponse.create(status).header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(body).build();
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
        JsonNode body = mapper.readTree(OpenAiCompatibleLlmClient.lastBodyForTest.get());
        assertThat(body.get("model").asText()).isEqualTo("openai/gpt-oss-120b");
        assertThat(body.get("messages").get(0).get("role").asText()).isEqualTo("system");
        assertThat(body.get("messages").get(1).get("content").asText()).contains("HbA1c");
        assertThat(body.get("response_format").get("type").asText()).isEqualTo("json_object");
        assertThat(body.get("max_tokens").asInt()).isEqualTo(8192);
    }

    @Test
    void omitsJsonModeAndSystemWhenNotRequested() throws Exception {
        OpenAiCompatibleLlmClient c = client(n -> json(HttpStatus.OK, OK_BODY));
        StepVerifier.create(c.invoke(LlmRequest.custom("hi", null, "custom-model", 50, 0.9))).expectNextCount(1).verifyComplete();
        JsonNode body = mapper.readTree(OpenAiCompatibleLlmClient.lastBodyForTest.get());
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
    }

    @Test
    void rateLimitIsRetriedThenSucceeds() {
        OpenAiCompatibleLlmClient c = client(n -> n < 3
                ? json(HttpStatus.TOO_MANY_REQUESTS, "{\"error\":{\"message\":\"slow down\"}}")
                : json(HttpStatus.OK, OK_BODY));
        StepVerifier.withVirtualTime(() -> c.invoke(LlmRequest.standard("hi")))
                .thenAwait(java.time.Duration.ofSeconds(10))
                .expectNextCount(1)
                .verifyComplete();
        assertThat(sent).hasSize(3);
    }
}
```

- [ ] **Step 3: Run** — `./gradlew :elioo-llm:test --tests '*OpenAiCompatibleLlmClientTest' -q` → compile failure (class missing).

- [ ] **Step 4: Implement** `elioo-llm/.../llm/provider/openai/OpenAiCompatibleLlmClient.java`:

```java
package com.elioo.healthcare.llm.provider.openai;

import com.elioo.healthcare.llm.api.LlmClient;
import com.elioo.healthcare.llm.config.LlmProperties;
import com.elioo.healthcare.llm.exception.LlmException;
import com.elioo.healthcare.llm.model.LlmRequest;
import com.elioo.healthcare.llm.model.LlmResponse;
import com.elioo.healthcare.llm.model.TokenUsage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link LlmClient} for any server that implements the OpenAI chat-completions API:
 * OpenAI itself, Groq, and compatible gateways. Only the base URL, key, model and
 * log name differ per provider.
 */
@Slf4j
public class OpenAiCompatibleLlmClient implements LlmClient {

    /** Test hook: the last JSON body sent. Not used in production code paths. */
    static final AtomicReference<String> lastBodyForTest = new AtomicReference<>();

    private final String providerName;
    private final LlmProperties.OpenAiCompatible cfg;
    private final LlmProperties defaults;
    private final WebClient webClient;
    private final ObjectMapper mapper;

    public OpenAiCompatibleLlmClient(String providerName, LlmProperties.OpenAiCompatible cfg,
                                     LlmProperties defaults, WebClient webClient, ObjectMapper mapper) {
        this.providerName = providerName;
        this.cfg = cfg;
        this.defaults = defaults;
        this.webClient = webClient;
        this.mapper = mapper;
    }

    @Override
    public String providerName() { return providerName; }

    @Override
    public Mono<LlmResponse> invoke(LlmRequest request) {
        if (request == null || !request.isValid()) {
            return Mono.error(new LlmException(providerName + ": userPrompt is required"));
        }
        String model = request.hasCustomModelId() ? request.modelId() : cfg.getModel();
        String body = buildBody(request, model);
        lastBodyForTest.set(body);
        long started = System.currentTimeMillis();
        log.info("[{}] chat/completions model={} promptChars={} json={}", providerName, model,
                request.userPrompt().length(), request.jsonOutput());
        log.debug("[{}] request body: {}", providerName, body);

        return webClient.post()
                .uri(cfg.getBaseUrl() + "/chat/completions")
                .header("Authorization", "Bearer " + cfg.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToMono(resp -> resp.bodyToMono(String.class).defaultIfEmpty("")
                        .flatMap(text -> resp.statusCode().is2xxSuccessful()
                                ? Mono.just(text)
                                : Mono.error(toException(resp.statusCode(), text))))
                .timeout(Duration.ofSeconds(defaults.getTimeoutSeconds()))
                .retryWhen(Retry.backoff(2, Duration.ofSeconds(1))
                        .filter(e -> e instanceof LlmException le && le.isRetryable())
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                .map(text -> parse(text, model))
                .doOnNext(r -> log.info("[{}] done model={} in={} out={} stop={} {}ms", providerName, r.modelId(),
                        r.usage().inputTokens(), r.usage().outputTokens(), r.stopReason(), System.currentTimeMillis() - started))
                .onErrorMap(e -> !(e instanceof LlmException),
                        e -> new LlmException(providerName + " call failed: " + e.getMessage(), e));
    }

    private String buildBody(LlmRequest request, String model) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", request.maxTokens() != null ? request.maxTokens() : defaults.getDefaultMaxTokens());
        root.put("temperature", request.temperature() != null ? request.temperature() : defaults.getDefaultTemperature());
        if (request.topP() != null) root.put("top_p", request.topP());
        ArrayNode messages = root.putArray("messages");
        if (request.hasSystemPrompt()) {
            messages.addObject().put("role", "system").put("content", request.systemPrompt());
        }
        messages.addObject().put("role", "user").put("content", request.userPrompt());
        if (request.jsonOutput()) {
            root.putObject("response_format").put("type", "json_object");
        }
        if (request.stopSequences() != null && !request.stopSequences().isEmpty()) {
            ArrayNode stop = root.putArray("stop");
            request.stopSequences().forEach(stop::add);
        }
        try {
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new LlmException(providerName + ": could not serialise request", e);
        }
    }

    private LlmResponse parse(String text, String requestedModel) {
        try {
            JsonNode root = mapper.readTree(text);
            JsonNode choice = root.path("choices").path(0);
            String content = choice.path("message").path("content").asText(null);
            if (content == null) {
                throw new LlmException(providerName + ": response has no choices[0].message.content");
            }
            JsonNode usage = root.path("usage");
            TokenUsage tokens = new TokenUsage(usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0));
            String model = root.path("model").asText(requestedModel);
            return new LlmResponse(content, choice.path("finish_reason").asText(null), tokens, model,
                    Map.of("provider", providerName));
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException(providerName + ": unreadable response: " + e.getMessage(), e);
        }
    }

    private LlmException toException(HttpStatusCode status, String body) {
        String message = body;
        try {
            JsonNode err = mapper.readTree(body).path("error").path("message");
            if (!err.isMissingNode()) message = err.asText();
        } catch (Exception ignored) { /* keep raw body */ }
        return new LlmException(providerName, status.value(), providerName + " HTTP " + status.value() + ": " + message);
    }
}
```

- [ ] **Step 5: Run** — `./gradlew :elioo-llm:test -q` → all pass. If `StepVerifier.withVirtualTime` does not advance the retry backoff (Retry uses a scheduler created at assembly), replace the retry test with `Retry.backoff(2, Duration.ofMillis(1))` injected via a package-private constructor overload that accepts a `Retry`; keep the production default at 1 s.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(llm): OpenAI-compatible client for Groq/OpenAI with JSON mode and retry

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: `AnthropicLlmClient`

**Files:**
- Create: `elioo-llm/src/main/java/com/elioo/healthcare/llm/provider/anthropic/AnthropicLlmClient.java`
- Test: `elioo-llm/src/test/java/com/elioo/healthcare/llm/provider/anthropic/AnthropicLlmClientTest.java`

**Interfaces:**
- Produces: `AnthropicLlmClient(LlmProperties.Anthropic cfg, LlmProperties defaults)` (builds its own `AnthropicClient`) and a package-private constructor `AnthropicLlmClient(AnthropicClient client, LlmProperties.Anthropic cfg, LlmProperties defaults)` for tests; package-private `MessageCreateParams buildParams(LlmRequest)` and `static LlmResponse toResponse(String text, String stopReason, long in, long out, String model)`.

- [ ] **Step 1: Write the failing test** `elioo-llm/src/test/java/com/elioo/healthcare/llm/provider/anthropic/AnthropicLlmClientTest.java`:

```java
package com.elioo.healthcare.llm.provider.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.elioo.healthcare.llm.config.LlmProperties;
import com.elioo.healthcare.llm.exception.LlmException;
import com.elioo.healthcare.llm.model.LlmRequest;
import com.elioo.healthcare.llm.model.LlmResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AnthropicLlmClientTest {

    private AnthropicLlmClient client() {
        LlmProperties props = new LlmProperties();
        props.getAnthropic().setApiKey("sk-test");
        props.getAnthropic().setEffort("high");
        return new AnthropicLlmClient(mock(AnthropicClient.class), props.getAnthropic(), props);
    }

    @Test
    void buildsParamsWithDefaultsThinkingAndEffort() {
        MessageCreateParams p = client().buildParams(LlmRequest.forJson("Summarise HbA1c 7.8%", "You are a clinician."));
        assertThat(p.model().toString()).isEqualTo("claude-opus-5");
        assertThat(p.maxTokens()).isEqualTo(8192L);
        assertThat(p.system()).isPresent();
        assertThat(p.thinking()).isPresent();
        assertThat(p.outputConfig()).isPresent();
        assertThat(p.temperature()).isEmpty();   // never sent to Opus 5
    }

    @Test
    void honoursPerRequestModelAndMaxTokens() {
        MessageCreateParams p = client().buildParams(LlmRequest.custom("hi", null, "claude-sonnet-5", 300, 0.9));
        assertThat(p.model().toString()).isEqualTo("claude-sonnet-5");
        assertThat(p.maxTokens()).isEqualTo(300L);
        assertThat(p.system()).isEmpty();
        assertThat(p.temperature()).isEmpty();
    }

    @Test
    void mapsResponse() {
        LlmResponse r = AnthropicLlmClient.toResponse("{\"a\":1}", "end_turn", 10, 5, "claude-opus-5");
        assertThat(r.content()).isEqualTo("{\"a\":1}");
        assertThat(r.stopReason()).isEqualTo("end_turn");
        assertThat(r.usage().totalTokens()).isEqualTo(15);
        assertThat(r.metadata()).containsEntry("provider", "anthropic");
    }

    @Test
    void refusalIsAnError() {
        assertThatThrownBy(() -> AnthropicLlmClient.toResponse("", "refusal", 10, 0, "claude-opus-5"))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("refused");
    }
}
```

- [ ] **Step 2: Run** — `./gradlew :elioo-llm:test --tests '*AnthropicLlmClientTest' -q` → compile failure.

- [ ] **Step 3: Implement** `elioo-llm/.../llm/provider/anthropic/AnthropicLlmClient.java`:

```java
package com.elioo.healthcare.llm.provider.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.elioo.healthcare.llm.api.LlmClient;
import com.elioo.healthcare.llm.config.LlmProperties;
import com.elioo.healthcare.llm.exception.LlmException;
import com.elioo.healthcare.llm.model.LlmRequest;
import com.elioo.healthcare.llm.model.LlmResponse;
import com.elioo.healthcare.llm.model.TokenUsage;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** {@link LlmClient} on the official Anthropic Java SDK. */
@Slf4j
public class AnthropicLlmClient implements LlmClient {

    private final AnthropicClient client;
    private final LlmProperties.Anthropic cfg;
    private final LlmProperties defaults;

    public AnthropicLlmClient(LlmProperties.Anthropic cfg, LlmProperties defaults) {
        this(AnthropicOkHttpClient.builder()
                        .apiKey(cfg.getApiKey())
                        .timeout(Duration.ofSeconds(defaults.getTimeoutSeconds()))
                        .maxRetries(2)
                        .build(),
                cfg, defaults);
    }

    AnthropicLlmClient(AnthropicClient client, LlmProperties.Anthropic cfg, LlmProperties defaults) {
        this.client = client;
        this.cfg = cfg;
        this.defaults = defaults;
    }

    @Override
    public String providerName() { return "anthropic"; }

    @Override
    public Mono<LlmResponse> invoke(LlmRequest request) {
        if (request == null || !request.isValid()) {
            return Mono.error(new LlmException("anthropic: userPrompt is required"));
        }
        MessageCreateParams params = buildParams(request);
        long started = System.currentTimeMillis();
        log.info("[anthropic] messages.create model={} promptChars={} effort={}", params.model(),
                request.userPrompt().length(), cfg.getEffort());
        log.debug("[anthropic] user prompt: {}", request.userPrompt());

        return Mono.fromCallable(() -> client.messages().create(params))
                .subscribeOn(Schedulers.boundedElastic())
                .map(AnthropicLlmClient::toResponse)
                .doOnNext(r -> log.info("[anthropic] done model={} in={} out={} stop={} {}ms", r.modelId(),
                        r.usage().inputTokens(), r.usage().outputTokens(), r.stopReason(), System.currentTimeMillis() - started))
                .onErrorMap(AnthropicServiceException.class,
                        e -> new LlmException("anthropic", e.statusCode(), "anthropic HTTP " + e.statusCode() + ": " + e.getMessage()))
                .onErrorMap(e -> !(e instanceof LlmException), e -> new LlmException("anthropic call failed: " + e.getMessage(), e));
    }

    MessageCreateParams buildParams(LlmRequest request) {
        MessageCreateParams.Builder b = MessageCreateParams.builder()
                .model(request.hasCustomModelId() ? request.modelId() : cfg.getModel())
                .maxTokens(request.maxTokens() != null ? request.maxTokens().longValue() : defaults.getDefaultMaxTokens())
                .addUserMessage(request.userPrompt())
                .thinking(ThinkingConfigAdaptive.builder().build())
                .outputConfig(OutputConfig.builder().effort(effort(cfg.getEffort())).build());
        if (request.hasSystemPrompt()) {
            b.system(request.systemPrompt());
        }
        if (request.stopSequences() != null && !request.stopSequences().isEmpty()) {
            b.stopSequences(request.stopSequences());
        }
        // temperature/top_p deliberately not sent: rejected by Claude Opus 5
        return b.build();
    }

    private static OutputConfig.Effort effort(String value) {
        return switch (value == null ? "medium" : value.toLowerCase(Locale.ROOT)) {
            case "low" -> OutputConfig.Effort.LOW;
            case "high" -> OutputConfig.Effort.HIGH;
            case "xhigh" -> OutputConfig.Effort.XHIGH;
            case "max" -> OutputConfig.Effort.MAX;
            default -> OutputConfig.Effort.MEDIUM;
        };
    }

    private static LlmResponse toResponse(Message message) {
        String text = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(t -> t.text())
                .collect(Collectors.joining());
        String stop = message.stopReason().map(Object::toString).orElse(null);
        return toResponse(text, stop, message.usage().inputTokens(), message.usage().outputTokens(), message.model().toString());
    }

    static LlmResponse toResponse(String text, String stopReason, long inputTokens, long outputTokens, String model) {
        if ("refusal".equalsIgnoreCase(stopReason)) {
            throw new LlmException("anthropic refused the request (stop_reason=refusal)");
        }
        return new LlmResponse(text, stopReason, new TokenUsage((int) inputTokens, (int) outputTokens), model,
                Map.of("provider", "anthropic"));
    }
}
```

- [ ] **Step 4: Run** — `./gradlew :elioo-llm:test -q`. Use compiler errors to correct SDK member names (candidates if a name is wrong: `e.statusCode()` may be `e.statusCode()` on `AnthropicServiceException`; `p.temperature()` returns `Optional<Double>`; `message.model()` may need `.asString()`; check with `jar tf` on the SDK jar in `~/.gradle/caches` for `com/anthropic/models/messages/OutputConfig$Effort.class`). Do not change the tested behaviour.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(llm): Anthropic client on the official Java SDK (adaptive thinking, effort)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: Auto-configuration and the Bedrock fallback

**Files:**
- Create: `elioo-llm/src/main/java/com/elioo/healthcare/llm/config/LlmAutoConfiguration.java`
- Create: `elioo-llm/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Modify: `elioo-aws-bedrock/.../service/BedrockServiceImpl.java` (implements `LlmClient`), `elioo-aws-bedrock/.../config/BedrockAutoConfiguration.java` (conditional `LlmClient` bean)
- Test: `elioo-llm/src/test/java/com/elioo/healthcare/llm/config/LlmAutoConfigurationTest.java`

**Interfaces:**
- Produces beans: exactly one `LlmClient`; `PromptTemplateEngine`; `HealthInsightService`; `WebClient` named `llmWebClient`.

- [ ] **Step 1: Write the failing auto-config test** `elioo-llm/src/test/java/com/elioo/healthcare/llm/config/LlmAutoConfigurationTest.java`:

```java
package com.elioo.healthcare.llm.config;

import com.elioo.healthcare.llm.api.LlmClient;
import com.elioo.healthcare.llm.health.api.HealthInsightService;
import com.elioo.healthcare.llm.provider.anthropic.AnthropicLlmClient;
import com.elioo.healthcare.llm.provider.openai.OpenAiCompatibleLlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class LlmAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LlmAutoConfiguration.class));

    @Test
    void groqIsDefaultAndNeedsKey() {
        runner.withPropertyValues("llm.groq.api-key=gsk_x").run(ctx -> {
            assertThat(ctx).hasSingleBean(LlmClient.class);
            assertThat(ctx.getBean(LlmClient.class)).isInstanceOf(OpenAiCompatibleLlmClient.class);
            assertThat(ctx.getBean(LlmClient.class).providerName()).isEqualTo("groq");
            assertThat(ctx).hasSingleBean(HealthInsightService.class);
        });
    }

    @Test
    void openaiProvider() {
        runner.withPropertyValues("llm.provider=openai", "llm.openai.api-key=sk-x").run(ctx ->
                assertThat(ctx.getBean(LlmClient.class).providerName()).isEqualTo("openai"));
    }

    @Test
    void anthropicProvider() {
        runner.withPropertyValues("llm.provider=anthropic", "llm.anthropic.api-key=sk-ant-x").run(ctx ->
                assertThat(ctx.getBean(LlmClient.class)).isInstanceOf(AnthropicLlmClient.class));
    }

    @Test
    void missingKeyFailsStartupWithClearMessage() {
        runner.withPropertyValues("llm.provider=groq").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasStackTraceContaining("GROQ_API_KEY");
        });
    }

    @Test
    void unknownProviderFails() {
        runner.withPropertyValues("llm.provider=nope").run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void bedrockProviderCreatesNoClientHere() {
        runner.withPropertyValues("llm.provider=bedrock").run(ctx -> {
            // the bedrock module supplies the client; without it the health service cannot be built
            assertThat(ctx).hasFailed();
        });
    }
}
```

- [ ] **Step 2: Run** → compile failure.

- [ ] **Step 3: Implement** `elioo-llm/.../llm/config/LlmAutoConfiguration.java`:

```java
package com.elioo.healthcare.llm.config;

import com.elioo.healthcare.llm.api.LlmClient;
import com.elioo.healthcare.llm.exception.LlmException;
import com.elioo.healthcare.llm.health.api.HealthInsightService;
import com.elioo.healthcare.llm.health.prompt.DefaultPromptTemplateEngine;
import com.elioo.healthcare.llm.health.prompt.PromptTemplateEngine;
import com.elioo.healthcare.llm.health.service.HealthInsightServiceImpl;
import com.elioo.healthcare.llm.provider.anthropic.AnthropicLlmClient;
import com.elioo.healthcare.llm.provider.openai.OpenAiCompatibleLlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Creates exactly one {@link LlmClient} according to {@code llm.provider}
 * (groq | openai | anthropic; bedrock is supplied by elioo-aws-bedrock), plus the
 * clinical prompt layer on top of it.
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "llmObjectMapper")
    public ObjectMapper llmObjectMapper(org.springframework.beans.factory.ObjectProvider<ObjectMapper> existing) {
        return existing.getIfAvailable(ObjectMapper::new);
    }

    @Bean
    @ConditionalOnMissingBean(name = "llmWebClient")
    public WebClient llmWebClient() {
        return WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(LlmClient.class)
    @ConditionalOnProperty(name = "llm.provider", havingValue = "groq", matchIfMissing = true)
    public LlmClient groqLlmClient(LlmProperties p, WebClient llmWebClient, ObjectMapper llmObjectMapper) {
        requireKey(p.getGroq().getApiKey(), "groq", "GROQ_API_KEY");
        log.info("LLM provider: groq (model {})", p.getGroq().getModel());
        return new OpenAiCompatibleLlmClient("groq", p.getGroq(), p, llmWebClient, llmObjectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(LlmClient.class)
    @ConditionalOnProperty(name = "llm.provider", havingValue = "openai")
    public LlmClient openaiLlmClient(LlmProperties p, WebClient llmWebClient, ObjectMapper llmObjectMapper) {
        requireKey(p.getOpenai().getApiKey(), "openai", "OPENAI_API_KEY");
        log.info("LLM provider: openai (model {})", p.getOpenai().getModel());
        return new OpenAiCompatibleLlmClient("openai", p.getOpenai(), p, llmWebClient, llmObjectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(LlmClient.class)
    @ConditionalOnProperty(name = "llm.provider", havingValue = "anthropic")
    public LlmClient anthropicLlmClient(LlmProperties p) {
        requireKey(p.getAnthropic().getApiKey(), "anthropic", "ANTHROPIC_API_KEY");
        log.info("LLM provider: anthropic (model {}, effort {})", p.getAnthropic().getModel(), p.getAnthropic().getEffort());
        return new AnthropicLlmClient(p.getAnthropic(), p);
    }

    @Bean
    @ConditionalOnMissingBean
    public PromptTemplateEngine promptTemplateEngine(ObjectMapper llmObjectMapper) {
        return new DefaultPromptTemplateEngine(llmObjectMapper);
    }

    /** Requires an LlmClient; with llm.provider=bedrock that bean comes from elioo-aws-bedrock. */
    @Bean
    @ConditionalOnMissingBean
    public HealthInsightService healthInsightService(LlmClient llmClient, PromptTemplateEngine engine,
                                                     ObjectMapper llmObjectMapper, LlmProperties p) {
        if (!java.util.Set.of("groq", "openai", "anthropic", "bedrock").contains(p.getProvider())) {
            throw new LlmException("Unknown llm.provider '" + p.getProvider() + "'; expected groq, openai, anthropic or bedrock");
        }
        return new HealthInsightServiceImpl(llmClient, engine, llmObjectMapper);
    }

    private static void requireKey(String key, String provider, String envVar) {
        if (key == null || key.isBlank()) {
            throw new LlmException("llm.provider=" + provider + " but " + envVar + " is not set");
        }
    }
}
```

`elioo-llm/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```
com.elioo.healthcare.llm.config.LlmAutoConfiguration
```

- [ ] **Step 4: Bedrock as an `LlmClient`.** In `BedrockServiceImpl`, change the class declaration to `implements BedrockService, LlmClient` and add:

```java
    @Override
    public Mono<LlmResponse> invoke(LlmRequest request) { return invokeModel(request); }

    @Override
    public String providerName() { return "bedrock"; }
```

(with imports `com.elioo.healthcare.llm.api.LlmClient`). In `BedrockAutoConfiguration` add:

```java
    /** Exposes Bedrock as the application's LlmClient only when llm.provider=bedrock. */
    @Bean
    @ConditionalOnProperty(name = "llm.provider", havingValue = "bedrock")
    @ConditionalOnMissingBean(com.elioo.healthcare.llm.api.LlmClient.class)
    public com.elioo.healthcare.llm.api.LlmClient bedrockLlmClient(BedrockService bedrockService) {
        log.info("LLM provider: bedrock");
        return (com.elioo.healthcare.llm.api.LlmClient) bedrockService;
    }
```

- [ ] **Step 5: Run** — `./gradlew :elioo-llm:test :elioo-aws-bedrock:test :elioo-aws-spring-boot-starter:test -q` → pass. (`AwsStarterAutoConfigurationTest` only asserts `BedrockService`, unaffected.)

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(llm): auto-configuration selecting the provider by llm.provider; Bedrock as fallback LlmClient

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 6: Application wiring, endpoints, properties

**Files:**
- Move: `medscribe-ai/.../medicalreport/adapter/out/aws/BedrockAdapter.java` → `medscribe-ai/.../medicalreport/adapter/out/llm/LlmInsightAdapter.java`
- Move test: `medscribe-ai/src/test/.../adapter/out/aws/BedrockAdapterTest.java` → `.../adapter/out/llm/LlmInsightAdapterTest.java`; delete `BedrockAdapterIntegrationTest.java`
- Delete: `medscribe-ai/src/main/java/com/elioo/healthcare/aws/bedrock/**`, `medscribe-ai/.../core/config/AwsConfig.java`
- Create: `medscribe-ai/src/main/java/com/elioo/healthcare/llm/{dto/InvokeRequest.java,handler/LlmApiHandler.java,router/LlmApiRouter.java}`
- Modify: `medscribe-ai/.../aws/textract/handler/TextractApiHandler.java`, `.../aws/textract/router/TextractApiRouter.java`, `medscribe-ai/build.gradle`, `medscribe-ai/src/main/resources/application.properties`, `application-aws.properties`, `medscribe.env.example`

- [ ] **Step 1: Rename the adapter and its test**

```bash
mkdir -p medscribe-ai/src/main/java/com/elioo/healthcare/medicalreport/adapter/out/llm medscribe-ai/src/test/java/com/elioo/healthcare/medicalreport/adapter/out/llm
git mv medscribe-ai/src/main/java/com/elioo/healthcare/medicalreport/adapter/out/aws/BedrockAdapter.java medscribe-ai/src/main/java/com/elioo/healthcare/medicalreport/adapter/out/llm/LlmInsightAdapter.java
git mv medscribe-ai/src/test/java/com/elioo/healthcare/medicalreport/adapter/out/aws/BedrockAdapterTest.java medscribe-ai/src/test/java/com/elioo/healthcare/medicalreport/adapter/out/llm/LlmInsightAdapterTest.java
git rm -q medscribe-ai/src/test/java/com/elioo/healthcare/medicalreport/adapter/out/aws/BedrockAdapterIntegrationTest.java
git rm -rq medscribe-ai/src/main/java/com/elioo/healthcare/aws/bedrock
git rm -q medscribe-ai/src/main/java/com/elioo/healthcare/core/config/AwsConfig.java
sed -i '' \
  -e 's/^package com\.elioo\.healthcare\.medicalreport\.adapter\.out\.aws;/package com.elioo.healthcare.medicalreport.adapter.out.llm;/' \
  -e 's/import com\.elioo\.healthcare\.aws\.bedrock\.api\.BedrockService;/import com.elioo.healthcare.llm.api.LlmClient;/' \
  -e 's/\bBedrockAdapter\b/LlmInsightAdapter/g' \
  -e 's/private final BedrockService bedrockService;/private final LlmClient llmClient;/' \
  -e 's/bedrockService\.invokeModel(/llmClient.invoke(/g' \
  -e 's/\bBedrockService\b/LlmClient/g' \
  -e 's/\bbedrockService\b/llmClient/g' \
  medscribe-ai/src/main/java/com/elioo/healthcare/medicalreport/adapter/out/llm/LlmInsightAdapter.java \
  medscribe-ai/src/test/java/com/elioo/healthcare/medicalreport/adapter/out/llm/LlmInsightAdapterTest.java
```

Then open `LlmInsightAdapter.java` and: change the class Javadoc first line to "Implements ClinicalInsightPort on top of the provider-neutral LLM layer."; in `chatAboutReport` the existing `LlmRequest.custom(prompt, null, null, 2048, 0.7)` stays valid. In `mapRiskAssessment` replace `libraryRiskAssessment.toString()` with a readable string: `"Overall risk: " + libraryRiskAssessment.overallRiskLevel()` (fixes the record-toString leak noted in the review).

- [ ] **Step 2: New raw endpoints.** `medscribe-ai/src/main/java/com/elioo/healthcare/llm/dto/InvokeRequest.java`:

```java
package com.elioo.healthcare.llm.dto;

public record InvokeRequest(String userPrompt, String systemPrompt, String modelId,
                            Integer maxTokens, Double temperature, Boolean jsonOutput) {}
```

`medscribe-ai/src/main/java/com/elioo/healthcare/llm/handler/LlmApiHandler.java` — take the six health methods from the deleted `BedrockHealthApiHandler` verbatim (renaming the field to `healthInsightService` of type `HealthInsightService`, package imports `com.elioo.healthcare.llm.health.*`) and add:

```java
    private final LlmClient llmClient;

    public Mono<ServerResponse> invoke(ServerRequest request) {
        return request.bodyToMono(InvokeRequest.class)
                .map(r -> new LlmRequest(r.userPrompt(), r.systemPrompt(), r.modelId(), r.maxTokens(),
                        r.temperature(), null, null, null, Boolean.TRUE.equals(r.jsonOutput())))
                .flatMap(llmClient::invoke)
                .flatMap(response -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(response))
                .onErrorResume(this::handleError);
    }
```

with `handleError` returning `{"error": simpleName, "message": ..., "service": "llm"}` and status 500 (copy from the deleted handler, change `"bedrock"` to `"llm"`).

`medscribe-ai/src/main/java/com/elioo/healthcare/llm/router/LlmApiRouter.java`:

```java
package com.elioo.healthcare.llm.router;

import com.elioo.healthcare.llm.handler.LlmApiHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

@Configuration
public class LlmApiRouter {

    @Bean
    public RouterFunction<ServerResponse> llmRoutes(LlmApiHandler handler) {
        return RouterFunctions.route()
                .path("/api/llm", b -> b
                        .POST("/invoke", handler::invoke)
                        .POST("/health/clinical-insights", handler::generateClinicalInsights)
                        .POST("/health/summary", handler::generateSummary)
                        .POST("/health/risk-assessment", handler::assessRisk)
                        .POST("/health/recommendations", handler::generateRecommendations)
                        .POST("/health/trend-analysis", handler::analyzeTrends)
                        .POST("/health/educational-content", handler::generateEducationalContent))
                .build();
    }
}
```

- [ ] **Step 3: Textract handler/router conditional.** Add to both `TextractApiHandler` (above `@Component`) and `TextractApiRouter` (above `@Configuration`):

```java
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "aws.textract.enabled", havingValue = "true", matchIfMissing = true)
```

- [ ] **Step 4: Build file and properties.** `medscribe-ai/build.gradle`: add `implementation project(':elioo-llm')` next to the two starters. Leave `spring-boot-starter-cache`/`caffeine` (unused now) for removal in a later cleanup.

`application.properties` — append:

```properties
# ---------------------------------------------------------------------------
# LLM provider for clinical insights and chat: groq | anthropic | openai | bedrock
# ---------------------------------------------------------------------------
llm.provider=${LLM_PROVIDER:groq}
llm.default-max-tokens=8192
llm.default-temperature=0.3
llm.timeout-seconds=120
llm.anthropic.api-key=${ANTHROPIC_API_KEY:}
llm.anthropic.model=${ANTHROPIC_MODEL:claude-opus-5}
llm.anthropic.effort=${ANTHROPIC_EFFORT:medium}
llm.groq.api-key=${GROQ_API_KEY:}
llm.groq.base-url=https://api.groq.com/openai/v1
llm.groq.model=${GROQ_MODEL:openai/gpt-oss-120b}
llm.openai.api-key=${OPENAI_API_KEY:}
llm.openai.base-url=https://api.openai.com/v1
llm.openai.model=${OPENAI_MODEL:gpt-4o}
```

`application-aws.properties` — replace lines 13-28 (the Bedrock block) with:

```properties
# Bedrock is optional now (llm.provider=bedrock); off by default
aws.bedrock.enabled=false
aws.bedrock.model-id=us.anthropic.claude-3-5-sonnet-20241022-v2:0
aws.bedrock.timeout-seconds=300
```

and replace lines 35-37 (Textract block) with:

```properties
# Textract is dormant: GCP Vision is the OCR provider (ocr.provider=gcp)
aws.textract.enabled=false
```

`medscribe.env.example` — add after the GCP block:

```bash
# --- LLM for clinical insights and chat ---
# groq (default, cheap, for tests) | anthropic (demos) | openai | bedrock
LLM_PROVIDER=groq
GROQ_API_KEY=
ANTHROPIC_API_KEY=
OPENAI_API_KEY=
# Optional overrides
# GROQ_MODEL=openai/gpt-oss-120b
# ANTHROPIC_MODEL=claude-opus-5
# ANTHROPIC_EFFORT=medium
```

- [ ] **Step 5: Build and run the whole suite with Docker Postgres up**

```bash
docker compose up -d && ./gradlew build -q && echo BUILD OK
```

Expected: `BUILD OK`. The context-load test now needs `llm.groq.api-key` non-blank: add to `medscribe-ai/src/test/resources/application-test.properties` (create) the line `llm.groq.api-key=test-key` and annotate `MedscribeAiApplicationTests` with `@TestPropertySource("classpath:application-test.properties")`. Also add `llm.groq.api-key=ci-placeholder` to the `build` job env in `.github/workflows/ci-cd.yml` as `LLM_PROVIDER: groq` / `GROQ_API_KEY: ci-placeholder` (the key is never used; only presence is checked).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(app): clinical insights and chat on the provider-neutral LLM layer; /api/llm endpoints; Textract and Bedrock off by default

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 7: Live verification on Groq and Anthropic, then deploy

**Files:**
- Create: `elioo-llm/src/test/java/com/elioo/healthcare/llm/LlmClientIntegrationTest.java` (gated)
- Modify: spec status line; `README.md` (one paragraph on `LLM_PROVIDER`)

- [ ] **Step 1: Gated integration test**

```java
package com.elioo.healthcare.llm;

import com.elioo.healthcare.llm.api.LlmClient;
import com.elioo.healthcare.llm.config.LlmProperties;
import com.elioo.healthcare.llm.health.api.HealthInsightService;
import com.elioo.healthcare.llm.health.dto.SummaryOptions;
import com.elioo.healthcare.llm.health.dto.SummaryRequest;
import com.elioo.healthcare.llm.health.prompt.DefaultPromptTemplateEngine;
import com.elioo.healthcare.llm.health.service.HealthInsightServiceImpl;
import com.elioo.healthcare.llm.provider.anthropic.AnthropicLlmClient;
import com.elioo.healthcare.llm.provider.openai.OpenAiCompatibleLlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "RUN_LLM_INTEGRATION_TESTS", matches = "true",
        disabledReason = "Calls the real LLM provider selected by LLM_PROVIDER")
class LlmClientIntegrationTest {

    @Test
    void summaryFromConfiguredProvider() {
        String provider = System.getenv().getOrDefault("LLM_PROVIDER", "groq");
        LlmProperties p = new LlmProperties();
        ObjectMapper mapper = new ObjectMapper();
        LlmClient client;
        if ("anthropic".equals(provider)) {
            p.getAnthropic().setApiKey(System.getenv("ANTHROPIC_API_KEY"));
            client = new AnthropicLlmClient(p.getAnthropic(), p);
        } else {
            p.getGroq().setApiKey(System.getenv("GROQ_API_KEY"));
            client = new OpenAiCompatibleLlmClient("groq", p.getGroq(), p, WebClient.create(), mapper);
        }
        HealthInsightService service = new HealthInsightServiceImpl(client, new DefaultPromptTemplateEngine(mapper), mapper);

        SummaryRequest req = new SummaryRequest(
                Map.of("HbA1c", "7.8 %", "Creatinine", "1.2 mg/dL", "LDL", "160 mg/dL"),
                null, SummaryOptions.defaultPatient());

        StepVerifier.create(service.generateSummary(req))
                .assertNext(s -> {
                    System.out.println("[" + provider + "] " + s.summary());
                    assertThat(s.summary()).isNotBlank();
                })
                .expectComplete()
                .verify(Duration.ofSeconds(120));
    }
}
```

- [ ] **Step 2: Run it on both providers**

```bash
set -a; source .env.local; set +a
RUN_LLM_INTEGRATION_TESTS=true LLM_PROVIDER=groq      ./gradlew :elioo-llm:test --tests '*LlmClientIntegrationTest' -q -i 2>&1 | grep -E '^\[groq\]|FAILED|BUILD'
RUN_LLM_INTEGRATION_TESTS=true LLM_PROVIDER=anthropic ./gradlew :elioo-llm:test --tests '*LlmClientIntegrationTest' -q -i 2>&1 | grep -E '^\[anthropic\]|FAILED|BUILD'
```
Expected: one summary line per provider, no FAILED.

- [ ] **Step 3: End-to-end pipeline locally on Groq** (Docker Postgres or Supabase; GCP OCR is degraded on the laptop, so use the multi-step path that starts from text: the free-text insights endpoint, plus the chat endpoint on an existing report if one exists; the full image pipeline is verified on EC2 in step 6)

```bash
set -a; source .env.local; set +a
./gradlew :medscribe-ai:bootRun -q > /tmp/medscribe-llm.log 2>&1 &
until curl -fsS localhost:8086/actuator/health >/dev/null 2>&1; do sleep 2; done
grep -E 'LLM provider' /tmp/medscribe-llm.log | sed 's/\x1b\[[0-9;]*m//g' | cut -c1-160
curl -s -X POST localhost:8086/api/llm/health/summary -H 'Content-Type: application/json' \
  -d '{"medicalData":{"HbA1c":"7.8 %","Creatinine":"1.2 mg/dL"},"options":{"targetAudience":"PATIENT"}}' | head -c 400; echo
curl -s -X POST localhost:8086/api/v1/medical-report/free-text-insights -H 'Content-Type: application/json' \
  -d '{"text":"HbA1c 7.8%, fasting glucose 140 mg/dL, LDL 160 mg/dL","targetAudience":"PATIENT","includeRiskAssessment":true,"includeRecommendations":true}' | head -c 600; echo
curl -s -o /dev/null -w 'old bedrock route: %{http_code}\n' -X POST localhost:8086/api/aws/bedrock/invoke-model -H 'Content-Type: application/json' -d '{}'
pkill -f MedscribeAiApplication
```
Expected: `LLM provider: groq`, a JSON summary, a free-text insight JSON, `old bedrock route: 404`. Check the exact field names of `FreeTextInsightRequest` before sending (`grep -n 'private\|record' medscribe-ai/src/main/java/com/elioo/healthcare/medicalreport/dto/FreeTextInsightRequest.java`).

- [ ] **Step 4: Repeat step 3's summary call with `LLM_PROVIDER=anthropic`** (start the app with that env var) and confirm the log says `LLM provider: anthropic (model claude-opus-5, effort medium)` and the summary returns.

- [ ] **Step 5: Docs and push**

`README.md`, in the "Run" section, add:

```markdown
Clinical insights and chat use the provider named by `LLM_PROVIDER` (`groq` default, `anthropic`,
`openai`, or `bedrock`) with the matching `*_API_KEY`. Switch providers by changing the variable and
restarting; no rebuild needed.
```

Spec status line → `**Status:** Implemented 2026-09-12`. Then:

```bash
git add -A
git commit -m "test(llm): gated provider integration test; docs for LLM_PROVIDER

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
git push origin main
env -u GITHUB_TOKEN gh run watch -R KhondokerTanvirHossain/elioo-health --exit-status
```

- [ ] **Step 6: EC2 acceptance (Groq, with real GCP OCR on the server)**

```bash
H=http://13.205.14.249:8086
IMG=$(base64 -i medscribe-ai/src/test/resources/test-images/blood-test-report.png | tr -d '\n')
RID=$(curl -s -X POST $H/api/v1/medical-report/process -H 'Content-Type: application/json' \
  -d "{\"imageBase64\":\"$IMG\",\"patientContext\":{\"patientId\":\"DEMO-1\",\"age\":45,\"gender\":\"MALE\"}}" | python3 -c 'import json,sys; print(json.load(sys.stdin)["reportId"])')
echo $RID
for i in $(seq 1 60); do S=$(curl -s $H/api/v1/medical-report/query/status/$RID | python3 -c 'import json,sys; print(json.load(sys.stdin).get("status"))'); echo "$S"; case $S in COMPLETED|FAILED|PARTIAL_SUCCESS) break;; esac; sleep 5; done
curl -s $H/api/v1/medical-report/query/results/$RID/insights | head -c 600; echo
curl -s -X POST $H/api/v1/medical-report/chat/$RID/send -H 'Content-Type: application/json' -d '{"message":"Which value is most concerning?","language":"en"}' | head -c 400; echo
```
Expected: status reaches `COMPLETED` (or `PARTIAL_SUCCESS` only if a non-LLM stage failed; inspect `/query/errors/$RID`), insights non-empty, chat answers. Check the request field names against `MasterProcessingRequest` and `ProcessingStatusSummary` before running and adjust the `python3` extraction if the field is named differently.

- [ ] **Step 7: Update memory and report.** Record in the project memory that `LLM_PROVIDER` exists and Groq is default.
