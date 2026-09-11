# Provider-Neutral LLM Layer (Groq / Anthropic / OpenAI / Bedrock) — Design

**Date:** 2026-09-12
**Status:** Approved (first item of phase 3: make every part work)

## Goal

Replace the hard dependency on AWS Bedrock for clinical insights and chat with a
provider-neutral LLM layer. The active provider is chosen by one environment
variable (`LLM_PROVIDER`) and switched by a restart, no redeploy. Groq is the
default for development and tests; Anthropic (Claude Opus 5) is used for demos;
OpenAI works through the same code path as Groq; Bedrock remains available as a
fallback implementation.

Out of scope: prompt re-tuning per provider (prompts stay as written for Claude,
with tolerant JSON parsing), streaming responses, cost tracking dashboards.

## Fixed facts

| Item | Value |
|---|---|
| Anthropic Java SDK | `com.anthropic:anthropic-java:2.62.0` (latest on Maven Central, 2026-09-12) |
| Anthropic model default | `claude-opus-5` (verified available on the user's key); adaptive thinking on, `effort` configurable (default `medium`), `max_tokens` 8192 |
| Groq endpoint | `https://api.groq.com/openai/v1` (OpenAI chat-completions format) |
| Groq model default | `openai/gpt-oss-120b` (131k context; verified live on the user's key; honors `response_format: json_object`) |
| OpenAI endpoint | `https://api.openai.com/v1`, model default `gpt-4o` |
| Keys | `GROQ_API_KEY`, `ANTHROPIC_API_KEY`, `OPENAI_API_KEY` env vars, present in `.env.local` (laptop) and `/home/ec2-user/medscribe.env` (server). Never in the repo. |
| `LLM_PROVIDER` | `groq` on both laptop and server today |
| Existing neutral types | `LlmRequest`, `LlmResponse`, `TokenUsage` records already exist in `elioo-aws-bedrock` and are provider-agnostic; they move rather than get rewritten |

## Module layout

### New module `elioo-llm` (package root `com.elioo.healthcare.llm`)

| Package | Contents |
|---|---|
| `llm.api` | `LlmClient` interface: `Mono<LlmResponse> invoke(LlmRequest request)`; `String providerName()` |
| `llm.model` | `LlmRequest` (moved; gains a `boolean jsonOutput` component and factory `LlmRequest.forJson(userPrompt, systemPrompt)`), `LlmResponse` (moved, unchanged), `TokenUsage` (moved, unchanged) |
| `llm.exception` | `LlmException` (runtime; carries provider name and optional HTTP status) |
| `llm.json` | `LlmJsonExtractor.extract(String content)`: strips markdown fences, then returns the first balanced `{...}` or `[...]` block; throws `LlmException` when none |
| `llm.provider.anthropic` | `AnthropicLlmClient` (Anthropic Java SDK, sync call wrapped in `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`) |
| `llm.provider.openai` | `OpenAiCompatibleLlmClient` (Spring `WebClient` to `{baseUrl}/chat/completions`; serves Groq and OpenAI; sets `response_format: {"type":"json_object"}` when `jsonOutput` is true) |
| `llm.health.api` | `HealthInsightService` (renamed from `BedrockHealthService`, same seven methods) |
| `llm.health.dto` | the 34 DTOs, moved unchanged |
| `llm.health.prompt` | `PromptTemplateEngine`, `DefaultPromptTemplateEngine`, moved unchanged |
| `llm.health.service` | `HealthInsightServiceImpl` (renamed from `BedrockHealthServiceImpl`; depends on `LlmClient`; **no `@Cacheable`** — see decisions) |
| `llm.health.exception` | `HealthInsightException` (renamed from `BedrockHealthServiceException`) |
| `llm.config` | `LlmProperties` (prefix `llm`), `LlmAutoConfiguration` |

Dependencies: `api io.projectreactor:reactor-core`, `api com.fasterxml.jackson.core:jackson-databind`, `api com.anthropic:anthropic-java:2.62.0`, `api org.springframework:spring-webflux`, `implementation io.projectreactor.netty:reactor-netty-http`, `compileOnly spring-boot-autoconfigure` + annotation processors, Lombok, tests `spring-boot-starter-test` + `reactor-test`. No AWS dependency.

### Changed module `elioo-aws-bedrock`

- Depends on `project(':elioo-llm')`.
- Deletes its own `model/` and `health/` packages (moved).
- `BedrockService` interface stays for the raw handler; `BedrockServiceImpl` additionally implements `LlmClient` (`invoke` delegates to `invokeModel`, `providerName()` returns `"bedrock"`).
- `BedrockAutoConfiguration` registers the `LlmClient` bean named `bedrockLlmClient` only when `llm.provider=bedrock`.
- `BedrockHealthAutoConfiguration`, `BedrockHealthProperties`, `BedrockClientConfig` (the unconditional duplicate client bean) are deleted. The `AutoConfiguration.imports` file lists only `BedrockAutoConfiguration`.

### Application module `medscribe-ai`

- `adapter/out/aws/BedrockAdapter` → `adapter/out/llm/LlmInsightAdapter` (same `ClinicalInsightPort` implementation, injects `HealthInsightService` + `LlmClient` + `ObjectMapper`; imports updated to `com.elioo.healthcare.llm.*`). `chatAboutReport` and `generateFreeTextInsights` use `LlmClient.invoke` / `HealthInsightService.executeCustomPrompt` exactly as today.
- Raw REST surface: `aws/bedrock/handler/BedrockApiHandler` + router and `aws/bedrock/health/*` are replaced by `llm/handler/LlmApiHandler` + `llm/router/LlmApiRouter` exposing `POST /api/llm/invoke` (body: `userPrompt`, `systemPrompt`, `maxTokens`, `temperature`, `jsonOutput`) and `POST /api/llm/health/{clinical-insights|summary|risk-assessment|recommendations|trend-analysis|educational-content}` (same request bodies as before). The `/api/aws/bedrock/*` paths are removed.
- Tests: `BedrockAdapterTest` → `LlmInsightAdapterTest`; `BedrockAdapterIntegrationTest` (a duplicate mock test) is deleted.
- `TextractApiHandler` and `TextractApiRouter` gain `@ConditionalOnProperty(name="aws.textract.enabled", havingValue="true", matchIfMissing=true)` so the app boots with Textract disabled.
- `application-aws.properties`: `aws.textract.enabled=false`, `aws.bedrock.enabled=false`; the `aws.bedrock.*` model/health keys are removed.
- `application.properties` gains the `llm.*` block (below). `medscribe.env.example` documents `LLM_PROVIDER`, the three API keys, `ANTHROPIC_MODEL`, `GROQ_MODEL`, `OPENAI_MODEL`.
- `AwsConfig.java` (fully commented-out) is deleted.

## Configuration

```properties
llm.provider=${LLM_PROVIDER:groq}              # groq | anthropic | openai | bedrock
llm.default-max-tokens=8192
llm.default-temperature=0.3
llm.timeout-seconds=120

llm.anthropic.api-key=${ANTHROPIC_API_KEY:}
llm.anthropic.model=${ANTHROPIC_MODEL:claude-opus-5}
llm.anthropic.effort=${ANTHROPIC_EFFORT:medium}    # low|medium|high|xhigh|max

llm.groq.api-key=${GROQ_API_KEY:}
llm.groq.base-url=https://api.groq.com/openai/v1
llm.groq.model=${GROQ_MODEL:openai/gpt-oss-120b}

llm.openai.api-key=${OPENAI_API_KEY:}
llm.openai.base-url=https://api.openai.com/v1
llm.openai.model=${OPENAI_MODEL:gpt-4o}
```

`LlmAutoConfiguration` creates exactly one `LlmClient` bean:

| `llm.provider` | Bean | Fails fast when |
|---|---|---|
| `anthropic` | `AnthropicLlmClient(properties.anthropic)` | api-key blank |
| `groq` | `OpenAiCompatibleLlmClient(properties.groq, "groq")` | api-key blank |
| `openai` | `OpenAiCompatibleLlmClient(properties.openai, "openai")` | api-key blank |
| `bedrock` | provided by `elioo-aws-bedrock` (requires `aws.bedrock.enabled=true`) | no bean → context fails with a clear message |

A blank key fails startup with `LlmException("llm.provider=groq but GROQ_API_KEY is not set")`. This is deliberate: unlike GCP OCR, the insight stage is central and a silent degrade would hide misconfiguration.

`HealthInsightService` and `PromptTemplateEngine` beans are always created (they only need an `LlmClient`).

## Request mapping

| `LlmRequest` field | Anthropic (`MessageCreateParams`) | OpenAI-compatible (`/chat/completions` body) |
|---|---|---|
| `systemPrompt` | `.system(...)` | first message `{role:"system"}` |
| `userPrompt` | `.addUserMessage(...)` | message `{role:"user"}` |
| `modelId` or provider default | `.model(...)` | `model` |
| `maxTokens` or default | `.maxTokens(...)` | `max_tokens` |
| `temperature` | not sent (removed on Opus 5; would return 400) | `temperature` |
| `jsonOutput` | prompt already demands JSON; no API flag | `response_format: {"type":"json_object"}` |
| thinking / effort | `.thinking(ThinkingConfigAdaptive)` + `.outputConfig(effort)` | not applicable |

Response mapping to `LlmResponse`: `content` = concatenated text blocks (Anthropic) or `choices[0].message.content` (OpenAI); `stopReason` = `stop_reason` / `finish_reason`; `usage` = input/output tokens; `modelId` = model echoed by the provider; `metadata` = `{"provider": name}`.

Anthropic `stop_reason == "refusal"` maps to `LlmException("Anthropic refused the request: <category>")`. Refusal fallbacks are not configured in this iteration; the caller sees the error and the stage is marked failed like any other error.

## Error handling

- HTTP 401/403 → `LlmException` with status; 429 and 5xx → retried twice with 1 s and 3 s backoff (`Retry.backoff(2, 1s)`), then `LlmException`.
- JSON parse failure in `HealthInsightServiceImpl.parseResponse` → `HealthInsightException` whose message includes provider, model, and the first 200 characters of the content, logged at WARN. This is where Groq-vs-Claude output differences will surface.
- Timeouts: `llm.timeout-seconds` applied via `Mono.timeout`.
- Provider clients log only model, prompt length, token usage, and duration at INFO. Prompt and response bodies are logged at DEBUG only (the current Bedrock implementation logs full payloads at INFO; that stops).

## Decisions

1. **Caching removed.** The current `@Cacheable` on `Mono`-returning methods caches the unsubscribed publisher and keys on a single `int` hash, so it never saves a call and can return another patient's insights. Deleted rather than fixed; the pipeline calls each method once per report anyway.
2. **Groq and OpenAI share one client.** They speak the same wire format; only base URL, key, model, and the name in logs differ.
3. **Bedrock kept, not deleted.** It becomes an optional `LlmClient` behind `llm.provider=bedrock` so the AWS path can be revived without a rewrite.
4. **Anthropic via the official SDK, sync-wrapped.** The Java SDK's synchronous client wrapped on the bounded-elastic scheduler is simpler and documented; the pipeline is already off the event loop for this stage.
5. **No per-provider prompts.** Prompts stay Claude-oriented. If Groq output proves unreliable for a given prompt, that becomes a follow-up ticket with evidence from the WARN logs, not a preemptive fork.

## Testing

Unit (no network):
- `LlmJsonExtractorTest`: fenced JSON, prose before/after JSON, nested braces, arrays, no JSON.
- `OpenAiCompatibleLlmClientTest`: `WebClient` with a stubbed `ExchangeFunction`; asserts request body (model, messages, `response_format` only when `jsonOutput`), response mapping, 401 → `LlmException`, 429 retried then failed.
- `AnthropicLlmClientTest`: builds params from an `LlmRequest` and asserts model, system, max tokens, thinking, effort; maps a constructed `Message` to `LlmResponse`; refusal → `LlmException`. (SDK client itself is not exercised.)
- `LlmAutoConfigurationTest`: `ApplicationContextRunner` for each provider value, the blank-key failure, and "exactly one `LlmClient`".
- `HealthInsightServiceImplTest`: mocked `LlmClient`; one happy path per method, plus a parse-failure path.
- `LlmInsightAdapterTest`: the existing `BedrockAdapterTest` cases, retargeted.
- Existing `BedrockServiceImplTest` keeps passing (types move, behaviour unchanged).

Integration (network, gated by `RUN_LLM_INTEGRATION_TESTS=true`):
- `LlmClientIntegrationTest`: for the configured provider, runs `HealthInsightService.generateSummary` on a fixed sample and asserts non-blank summary. Run locally against Groq before merging; run once against Anthropic to confirm the demo path.

Acceptance:
1. `./gradlew build` green.
2. Local run with `LLM_PROVIDER=groq`: `POST /api/v1/medical-report/process` with `blood-test-report.png` reaches `COMPLETED`; `GET /query/results/{id}/insights` returns a summary; stage table shows `CLINICAL_INSIGHTS` completed.
3. Same run with `LLM_PROVIDER=anthropic` completes.
4. `POST /api/v1/medical-report/chat/{id}/send` answers on Groq.
5. Deploy to EC2 with `LLM_PROVIDER=groq` in the server env; the pipeline completes on the EC2 URL.
6. `/api/aws/bedrock/*` returns 404; `/api/llm/health/summary` works.

## Rollout

1. Land the module and app changes on `main` (CI builds, tests without network).
2. Server env already has `LLM_PROVIDER=groq` and both keys; the deploy picks them up.
3. For a demo: `ssh`, set `LLM_PROVIDER=anthropic` in `~/medscribe.env`, rerun `IMAGE_TAG=<current sha> bash ~/deploy.sh`. Switch back afterwards.

## Data-handling note

With `groq`, report text and patient context are sent to Groq (US). With `anthropic`, to Anthropic (US). Comprehend Medical already sends the same text to AWS us-east-1. Use the sample images, not real patient reports, during testing.
