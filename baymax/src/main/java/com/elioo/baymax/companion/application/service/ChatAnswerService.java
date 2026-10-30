package com.elioo.baymax.companion.application.service;

import com.elioo.baymax.aicall.application.service.AiCostCalculator;
import com.elioo.baymax.aicall.application.service.MeteredLlmClient;
import com.elioo.baymax.aicall.domain.AiCallPurpose;
import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.companion.application.port.in.AskUseCase;
import com.elioo.baymax.companion.application.port.out.ChatCopyPort;
import com.elioo.baymax.companion.application.port.out.ChatTurnPort;
import com.elioo.baymax.companion.config.ChatModelConfiguration;
import com.elioo.baymax.companion.domain.AnswerKind;
import com.elioo.baymax.companion.domain.ChatContext;
import com.elioo.baymax.companion.domain.ChatOutcome;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.config.BaymaxModelClient;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.web.application.port.in.AttentionUseCase;
import com.elioo.baymax.web.domain.AttentionItem;
import com.elioo.baymax.web.domain.ViewerLocale;
import com.elioo.healthcare.llm.api.LlmClient;
import com.elioo.healthcare.llm.model.LlmResponse;
import com.elioo.healthcare.llm.model.TokenUsage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * The চ্যাট pipeline (DR-50, PO ruling P7), in this order, which is part of the safety design:
 * <ol>
 *   <li>the question is checked (blank or over {@code question-max-chars}: 400) and the patient must be visible to this
 *       account (else 404), before any {@code chat_turn} SQL;</li>
 *   <li><b>the emergency pre-check</b> ({@link RedFlagMatcher}): zero model calls, before the switch, the cap and the
 *       context; never counted; the existing emergency lines ({@link ChatAppendix#emergency});</li>
 *   <li>answers switched off, or no chat client: {@code chat.ask.reply} (DECLINED);</li>
 *   <li>the daily cap, reserved atomically ({@link ChatTurnPort#reserveCounted}): {@code chat.ask.limit} (LIMIT);</li>
 *   <li>the context of stored facts ({@link ChatContextBuilder}) and one model call through {@link MeteredLlmClient}
 *       (one {@code ai_call_log} row each, {@code document_id} NULL);</li>
 *   <li>the code checks on the model's text ({@link AnswerCheck}) and the length of the whole assembled text; on a failure
 *       one repair call naming the violations, then {@code chat.ask.failed} (FALLBACK). A model error, a timeout, a
 *       {@code max_tokens} stop or the answer deadline goes straight to FALLBACK. The raw text of a refused answer is never
 *       returned and never stored;</li>
 *   <li>code appends, after the body: {@code chat.dose.ask_doctor} for a dose question, the patient's open urgency line,
 *       {@code disclaimer}, {@code safety.red_flag_line} ({@link ChatAppendix});</li>
 *   <li>the turn is completed with exactly what is returned.</li>
 * </ol>
 * One INFO line per turn: ids, sizes, counts, tokens, USD and violation codes. Question, answer and details at DEBUG only.
 */
@Slf4j
@Service
public class ChatAnswerService implements AskUseCase {

    private final BaymaxProperties properties;
    private final HealthRecordPort records;
    private final ChatTurnPort turns;
    private final RedFlagMatcher redFlags;
    private final ChatContextBuilder contexts;
    private final ChatCopyPort copy;
    private final AttentionUseCase attention;
    private final MeteredLlmClient metered;
    private final AiCostCalculator costs;
    private final ObjectProvider<BaymaxModelClient> chatClient;
    private final Clock clock;

    public ChatAnswerService(BaymaxProperties properties, HealthRecordPort records, ChatTurnPort turns, RedFlagMatcher redFlags,
                             ChatContextBuilder contexts, ChatCopyPort copy, AttentionUseCase attention, MeteredLlmClient metered,
                             AiCostCalculator costs, @Qualifier(ChatModelConfiguration.CHAT_CLIENT) ObjectProvider<BaymaxModelClient> chatClient,
                             Clock clock) {
        this.properties = properties;
        this.records = records;
        this.turns = turns;
        this.redFlags = redFlags;
        this.contexts = contexts;
        this.copy = copy;
        this.attention = attention;
        this.metered = metered;
        this.costs = costs;
        this.chatClient = chatClient;
        this.clock = clock;
    }

    /** The calls, tokens, cost and violations of one turn's model work; confined to the turn's own chain. */
    private static final class Tally {
        int calls;
        int in;
        int out;
        BigDecimal usd;
        final List<String> violations = new ArrayList<>();
    }

    /** Where a turn's body came from, before the appendix. */
    private record Body(String text, AnswerKind kind, String handler, List<UUID> documents) {
    }

    @Override
    public Mono<AskAnswer> ask(UUID familyId, UUID patientId, String question, String lang) {
        BaymaxProperties.Chat chat = properties.getChat();
        String q = question == null ? "" : question.strip();
        String l = "en".equals(lang) ? "en" : "bn";
        if (q.isEmpty() || q.length() > chat.getQuestionMaxChars()) {
            return Mono.error(BaymaxException.badRequest("invalid_question",
                    "a question is between one and " + chat.getQuestionMaxChars() + " characters"));
        }
        long started = System.nanoTime();
        return records.visiblePatient(familyId, patientId)
                .switchIfEmpty(Mono.error(BaymaxException.notFound("patient_not_found", "no patient with id " + patientId)))
                .flatMap(access -> records.findFamily(familyId)
                        .switchIfEmpty(Mono.error(BaymaxException.unauthorized("session_family_gone", "the family no longer exists")))
                        .map(f -> ViewerLocale.of(f.whatsappNumber()))
                        .flatMap(viewer -> route(familyId, access.patient(), q, l, viewer, started)));
    }

    private Mono<AskAnswer> route(UUID familyId, PatientProfile patient, String q, String l, ViewerLocale viewer, long started) {
        Function<String, String> line = key -> copy.line(l, key);
        // 2. the emergency pre-check: before the switch, the cap, the context and any model call
        if (redFlags.match(q).isPresent()) {
            String answer = ChatAppendix.emergency(line);
            return turns.reserveUncounted(familyId, patient.id(), l, q)
                    .flatMap(id -> finish(id, patient, q, l, viewer, new Body(answer, AnswerKind.EMERGENCY, "red_flag", List.of()), "",
                            new Tally(), started));
        }
        return openUrgency(familyId, patient.id()).flatMap(open -> {
            String tail = ChatAppendix.tail(DoseChangeGuard.asksForChange(q), open, line);
            // 3. switched off, or no model
            if (!properties.getChat().isEnabled()) {
                return fixed(familyId, patient, q, l, viewer, AnswerKind.DECLINED, "off", "chat.ask.reply", tail, started);
            }
            LlmClient client = BaymaxModelClient.unwrap(chatClient.getIfAvailable());
            if (client == null) {
                return fixed(familyId, patient, q, l, viewer, AnswerKind.DECLINED, "no_model", "chat.ask.reply", tail, started);
            }
            // 4. the daily cap, reserved atomically
            return turns.reserveCounted(familyId, patient.id(), l, q, dayStart(), properties.getChat().getDailyCap())
                    .flatMap(id -> modelled(id, client, familyId, patient, q, l, viewer, tail, started))
                    .switchIfEmpty(Mono.defer(() -> fixed(familyId, patient, q, l, viewer, AnswerKind.LIMIT, "cap", "chat.ask.limit", tail,
                            started)));
        });
    }

    /** 5–6: the context, one call, the checks, one repair, else the fixed reply. Never empty. */
    private Mono<AskAnswer> modelled(UUID turnId, LlmClient client, UUID familyId, PatientProfile patient, String q, String l,
                                     ViewerLocale viewer, String tail, long started) {
        BaymaxProperties.Chat chat = properties.getChat();
        Tally tally = new Tally();
        Function<String, String> line = key -> copy.line(l, key);
        Body failed = new Body(line.apply("chat.ask.failed"), AnswerKind.FALLBACK, "fixed_after_repair", List.of());
        Mono<Body> work = contexts.build(familyId, patient.id(), patient.name()).flatMap(ctx -> {
            int bodyChars = chat.getMaxAnswerChars() - tail.length();
            return call(client, ctx, patient.name(), q, l, bodyChars, List.of(), tally).flatMap(first -> {
                List<String> v1 = check(first, ctx, tail);
                if (v1.isEmpty()) {
                    return Mono.just(new Body(AnswerCheck.resolve(first.strip(), ctx), AnswerKind.ANSWER, "model", ctx.documentIds()));
                }
                tally.violations.addAll(v1);
                log.debug("[baymax] chat turn={} refused first answer: {}", turnId, v1);
                return call(client, ctx, patient.name(), q, l, bodyChars, v1, tally).map(second -> {
                    List<String> v2 = check(second, ctx, tail);
                    if (v2.isEmpty()) {
                        return new Body(AnswerCheck.resolve(second.strip(), ctx), AnswerKind.ANSWER, "repair", ctx.documentIds());
                    }
                    tally.violations.addAll(v2);
                    log.debug("[baymax] chat turn={} refused the repair: {}", turnId, v2);
                    return failed;
                });
            });
        });
        return work.timeout(chat.getAnswerDeadline())
                .onErrorResume(e -> {
                    log.warn("[baymax] chat turn={} model failed after {} call(s): {}", turnId, tally.calls, e.getClass().getSimpleName());
                    return Mono.just(new Body(failed.text(), AnswerKind.FALLBACK, "model_error", List.of()));
                })
                .flatMap(body -> finish(turnId, patient, q, l, viewer, body, tail, tally, started));
    }

    /** One metered call under the per-call timeout; a {@code max_tokens} stop is a failure, never a truncated answer. */
    private Mono<String> call(LlmClient client, ChatContext ctx, String name, String q, String l, int bodyChars, List<String> prior,
                              Tally tally) {
        BaymaxProperties.Chat chat = properties.getChat();
        return Mono.defer(() -> {
            tally.calls++;
            return metered.using(client)
                    .invoke(AiCallPurpose.CHAT, null, ChatPrompt.of(ctx, name, q, l, bodyChars, prior, chat.getMaxOutputTokens()))
                    .timeout(chat.getModelTimeout())
                    .map(response -> {
                        count(tally, response);
                        if (response.isMaxTokensReached()) {
                            throw new IllegalStateException("the chat model stopped at max_tokens");
                        }
                        return response.content() == null ? "" : response.content();
                    });
        });
    }

    private void count(Tally tally, LlmResponse response) {
        TokenUsage usage = response.hasUsage() ? response.usage() : TokenUsage.empty();
        tally.in += usage.inputTokens();
        tally.out += usage.outputTokens();
        String provider = response.provider() != null ? response.provider() : "unknown";
        String model = response.modelId() != null ? response.modelId() : "unknown";
        costs.llmCost(provider, model, usage.inputTokens(), usage.outputTokens())
                .ifPresent(c -> tally.usd = tally.usd == null ? c : tally.usd.add(c));
    }

    /** The content checks on the model's words, and the length of the whole text the family would be shown. */
    private List<String> check(String modelText, ChatContext ctx, String tail) {
        List<String> v = new ArrayList<>(AnswerCheck.violations(modelText, ctx));
        if (v.stream().noneMatch(c -> c.startsWith("unresolved_reference"))) {
            v.addAll(AnswerCheck.length(AnswerCheck.resolve(modelText.strip(), ctx) + tail, properties.getChat().getMaxAnswerChars()));
        }
        return v;
    }

    /** A fixed body on an uncounted turn: DECLINED or LIMIT. */
    private Mono<AskAnswer> fixed(UUID familyId, PatientProfile patient, String q, String l, ViewerLocale viewer, AnswerKind kind,
                                  String handler, String key, String tail, long started) {
        return turns.reserveUncounted(familyId, patient.id(), l, q)
                .flatMap(id -> finish(id, patient, q, l, viewer, new Body(copy.line(l, key), kind, handler, List.of()), tail, new Tally(),
                        started));
    }

    /** 7–8: the appendix, the length asserted on the whole text, the turn stored with exactly what is returned. */
    private Mono<AskAnswer> finish(UUID turnId, PatientProfile patient, String q, String l, ViewerLocale viewer, Body body, String tail,
                                   Tally tally, long started) {
        String answer = body.text() + tail;
        List<String> length = AnswerCheck.length(answer, properties.getChat().getMaxAnswerChars());
        if (!length.isEmpty()) {
            // a fixed body over the cap is a configuration defect ChatPropertiesCheck refuses at startup; an emergency is
            // still shown whole
            log.error("[baymax] chat turn={} fixed {} reply is {}", turnId, body.kind().dbValue(), length);
        }
        long ms = (System.nanoTime() - started) / 1_000_000;
        List<String> codes = AnswerCheck.forLog(tally.violations);
        ChatOutcome outcome = new ChatOutcome(answer, body.kind(), body.handler(), codes, tally.calls, tally.in, tally.out, tally.usd, ms,
                body.documents());
        boolean call999 = body.kind() == AnswerKind.EMERGENCY && viewer.bangladesh();
        return turns.complete(turnId, outcome)
                .then(Mono.fromSupplier(() -> {
                    log.info("[baymax] chat turn={} patient={} kind={} handler={} q_chars={} a_chars={} calls={} in={} out={} usd={} ms={}"
                                    + " violations={}", turnId, patient.id(), body.kind().dbValue(), body.handler(), q.length(), answer.length(),
                            tally.calls, tally.in, tally.out, tally.usd, ms, String.join(",", codes));
                    log.debug("[baymax] chat turn={} question={} answer={}", turnId, q, answer);
                    return new AskAnswer(turnId, body.kind(), q, answer, call999, clock.instant());
                }));
    }

    /** The patient's highest open stored urgency (NOW above THIS_WEEK), from the attention read; zero model calls. */
    private Mono<Optional<Urgency>> openUrgency(UUID familyId, UUID patientId) {
        return attention.urgent(familyId, patientId)
                .map(list -> list.stream().map(AttentionItem.Urgent::level)
                        .min((a, b) -> Integer.compare(a == Urgency.NOW ? 0 : 1, b == Urgency.NOW ? 0 : 1)));
    }

    private Instant dayStart() {
        ZoneId zone = ZoneId.of(properties.getChat().getZone());
        return LocalDate.now(clock.withZone(zone)).atStartOfDay(zone).toInstant();
    }
}
