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
import com.elioo.baymax.companion.domain.CitedMedicine;
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
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * The চ্যাট pipeline (DR-50, PO ruling P7), in this order, which is part of the safety design:
 * <ol>
 *   <li><b>the emergency pre-check</b> ({@link RedFlagMatcher}) on the whole question, before its length is checked; then
 *       the question is checked (blank, or over {@code question-max-chars} and no emergency: 400) and the patient must be
 *       visible to this account (else 404), before any {@code chat_turn} SQL;</li>
 *   <li>an emergency gets the existing emergency lines ({@link ChatAppendix#emergency}): zero model calls, before the
 *       switch, the cap and the context; never counted; stored cut to {@code question-max-chars}; and returned even when
 *       storing it fails;</li>
 *   <li>answers switched off: {@code chat.ask.reply} (DECLINED), as before PO ruling P14;</li>
 *   <li>a question that asks only what a medicine is for, does or treats (P14, {@link MedicinePurposeGuard#route}): the
 *       stored line(s), every line when no stored one is named (never that the medicine is missing), built by code
 *       ({@link MedicinePurposeReply}), then
 *       {@code chat.purpose.ask}; zero model calls, before the client and the cap, never counted (DECLINED, handler
 *       {@code medicine_purpose}, V30). A question that also asks something else goes on to the model, and code still
 *       appends {@code chat.purpose.ask};</li>
 *   <li>no chat client: {@code chat.ask.reply} (DECLINED);</li>
 *   <li>the daily cap, reserved atomically ({@link ChatTurnPort#reserveCounted}): {@code chat.ask.limit} (LIMIT);</li>
 *   <li>the context of stored facts ({@link ChatContextBuilder}) and one model call through {@link MeteredLlmClient}
 *       (one {@code ai_call_log} row each, {@code document_id} NULL);</li>
 *   <li>the code checks on the model's text ({@link AnswerCheck}) and the length of the whole assembled text; on a failure
 *       one repair call naming the violations, then {@code chat.ask.failed} (FALLBACK). A model error, a timeout, a
 *       {@code max_tokens} stop or the answer deadline goes straight to FALLBACK. The raw text of a refused answer is never
 *       returned and never stored;</li>
 *   <li>code appends, after the body: {@code chat.dose.ask_doctor} for a dose question, {@code chat.purpose.ask} for a
 *       purpose question, the patient's open urgency line,
 *       {@code disclaimer}, {@code safety.red_flag_line} ({@link ChatAppendix});</li>
 *   <li>the turn is completed with exactly what is returned.</li>
 * </ol>
 * One INFO line per turn: ids, sizes, counts, tokens, USD and violation codes. Question, answer and details at DEBUG only.
 */
@Slf4j
@Service
public class ChatAnswerService implements AskUseCase {

    /**
     * What storing the turn may add after the answer deadline: the whole request ends by {@code answer-deadline} plus this,
     * inside the web composer's wait (ChatPropertiesCheck), so the web never shows its fallback for a turn Java goes on to
     * answer (review of #166, finding 4).
     */
    public static final Duration STORE_GRACE = Duration.ofSeconds(3);

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
    private record Body(String text, AnswerKind kind, String handler, List<UUID> documents, List<CitedMedicine> medicines) {

        /** A body that quotes no prescription line. */
        Body(String text, AnswerKind kind, String handler, List<UUID> documents) {
            this(text, kind, handler, documents, List.of());
        }
    }

    @Override
    public Mono<AskAnswer> ask(UUID familyId, UUID patientId, String question, String lang) {
        BaymaxProperties.Chat chat = properties.getChat();
        String asked = question == null ? "" : question.strip();
        String l = "en".equals(lang) ? "en" : "bn";
        // the emergency pre-check reads the whole question, before its length is held against it: a long, frantic message
        // about chest pain gets the emergency lines, never a 400 (review of #164, findings 7, 14)
        boolean emergency = !asked.isEmpty() && redFlags.match(asked).isPresent();
        if (asked.isEmpty() || (!emergency && asked.length() > chat.getQuestionMaxChars())) {
            return Mono.error(BaymaxException.badRequest("invalid_question",
                    "a question is between one and " + chat.getQuestionMaxChars() + " characters"));
        }
        // what is stored and returned: an over-long emergency is cut to the column's limit (V29's CHECK), on a code point
        String q = cut(asked, chat.getQuestionMaxChars());
        long started = System.nanoTime();
        // the answer deadline runs from here, on the scheduler's clock the timeouts use (review of #166, finding 4)
        long startMs = Schedulers.parallel().now(TimeUnit.MILLISECONDS);
        Duration deadline = chat.getAnswerDeadline();
        Mono<AskAnswer> answered = records.visiblePatient(familyId, patientId)
                .switchIfEmpty(Mono.error(BaymaxException.notFound("patient_not_found", "no patient with id " + patientId)))
                .flatMap(access -> records.findFamily(familyId)
                        .switchIfEmpty(Mono.error(BaymaxException.unauthorized("session_family_gone", "the family no longer exists")))
                        .map(f -> ViewerLocale.of(f.whatsappNumber()))
                        .flatMap(viewer -> emergency ? emergency(familyId, access.patient(), q, l, viewer, started)
                                : route(familyId, access.patient(), q, l, viewer, started, startMs)));
        // the whole request, reads and store included, ends inside the web's wait (ChatPropertiesCheck): past it the web has
        // shown its fallback, so no answer may be stored after it that the family would only meet on a reload. An emergency
        // is pure copy and is returned at its own bound below.
        return emergency ? answered : answered.timeout(deadline.plus(STORE_GRACE));
    }

    /**
     * 2. the emergency reply: before the switch, the cap, the context and any model call. It is pure copy, so a store that
     * fails or answers nothing never costs the family the emergency lines (review of #164, finding 14): the reply is still
     * returned, under an id no row carries, and the failure is logged by class and patient id only.
     */
    private Mono<AskAnswer> emergency(UUID familyId, PatientProfile patient, String q, String l, ViewerLocale viewer, long started) {
        String answer = ChatAppendix.emergency(key -> copy.line(l, key));
        Body body = new Body(answer, AnswerKind.EMERGENCY, "red_flag", List.of());
        Mono<AskAnswer> unstored = Mono.fromSupplier(() -> new AskAnswer(UUID.randomUUID(), AnswerKind.EMERGENCY, q, answer,
                viewer.bangladesh(), clock.instant()));
        return turns.reserveUncounted(familyId, patient.id(), l, q)
                .flatMap(id -> finish(id, patient, q, l, viewer, body, "", new Tally(), started))
                // a store that hangs never holds the emergency lines back past the deadline
                .timeout(properties.getChat().getAnswerDeadline())
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("[baymax] chat emergency for patient={} not stored: no turn reserved", patient.id());
                    return unstored;
                }))
                .onErrorResume(e -> {
                    log.warn("[baymax] chat emergency for patient={} not stored: {}", patient.id(), e.getClass().getSimpleName());
                    return unstored;
                });
    }

    /** What code decided about the question before any context or model: a dose question, and where a purpose one goes. */
    private record Asked(boolean dose, MedicinePurposeGuard.Route purpose) {
    }

    private Mono<AskAnswer> route(UUID familyId, PatientProfile patient, String q, String l, ViewerLocale viewer, long started,
                                  long startMs) {
        Function<String, String> line = key -> copy.line(l, key);
        // whether code appends the ask-the-doctor line: the stored medicines' names count as medicine words, since a family
        // names the drug (review of #164, finding 3); a names read that fails appends the line rather than drop it. The same
        // names route a question about what a medicine is for (PO ruling P14); routed again once the context is read
        Mono<Asked> asked = contexts.medicineNames(familyId, patient.id())
                .map(names -> new Asked(DoseChangeGuard.asksForChange(q, names),
                        MedicinePurposeGuard.route(q, names, Set.of(), patient.name())))
                .defaultIfEmpty(new Asked(DoseChangeGuard.asksForChange(q), MedicinePurposeGuard.route(q, Set.of(), Set.of(), patient.name())))
                .onErrorReturn(new Asked(true, MedicinePurposeGuard.route(q, Set.of(), Set.of(), patient.name())));
        return Mono.zip(openUrgency(familyId, patient.id()), asked).flatMap(t -> {
            Optional<Urgency> open = t.getT1();
            boolean dose = t.getT2().dose();
            MedicinePurposeGuard.Route purpose = t.getT2().purpose();
            // 3. switched off: the reply as before P14, so nothing changes while answers are off
            if (!properties.getChat().isEnabled()) {
                return fixed(familyId, patient, q, l, viewer, AnswerKind.DECLINED, "off", "chat.ask.reply",
                        ChatAppendix.tail(dose, open, line), started);
            }
            // 4. what a medicine is for, and nothing else (P14): code answers from the stored lines, before the model, the
            // client and the cap, since it costs nothing
            if (purpose == MedicinePurposeGuard.Route.CODE && !dose) {
                return purposeReply(familyId, patient, q, l, viewer, open, started, startMs);
            }
            boolean purposeLine = purpose != MedicinePurposeGuard.Route.NONE;
            return byModel(familyId, patient, q, dose, purposeLine, l, viewer, ChatAppendix.tail(dose, purposeLine, open, line), started,
                    startMs);
        });
    }

    /** 5. no model, the cap, then the model: every question code does not answer itself. */
    private Mono<AskAnswer> byModel(UUID familyId, PatientProfile patient, String q, boolean dose, boolean purpose, String l,
                                    ViewerLocale viewer, String tail, long started, long startMs) {
        LlmClient client = BaymaxModelClient.unwrap(chatClient.getIfAvailable());
        if (client == null) {
            return fixed(familyId, patient, q, l, viewer, AnswerKind.DECLINED, "no_model", "chat.ask.reply", tail, started);
        }
        // the daily cap, reserved atomically; only the reservation's own emptiness is the cap (review of #164, finding 6)
        return turns.reserveCounted(familyId, patient.id(), l, q, dayStart(), properties.getChat().getDailyCap())
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(reserved -> reserved.isPresent()
                        ? modelled(reserved.get(), client, familyId, patient, q, dose, purpose, l, viewer, tail, started, startMs)
                        : fixed(familyId, patient, q, l, viewer, AnswerKind.LIMIT, "cap", "chat.ask.limit", tail, started));
    }

    /**
     * PO ruling P14, "Only what the papers say": a question that asks only what a medicine is for is answered by code from
     * the stored lines ({@link MedicinePurposeReply}), then {@code chat.purpose.ask} and the usual lines; zero model calls, an
     * uncounted DECLINED turn under handler {@value MedicinePurposeGuard#HANDLER} (V30 holds it so). Routed again once the
     * context is read: a question that names a stored lab ("what is creatinine for?") is not a medicine's and goes to the
     * model. A context that cannot be read gives {@code chat.ask.failed}, still with no model call.
     */
    private Mono<AskAnswer> purposeReply(UUID familyId, PatientProfile patient, String q, String l, ViewerLocale viewer,
                                         Optional<Urgency> open, long started, long startMs) {
        Function<String, String> line = key -> copy.line(l, key);
        String tail = ChatAppendix.tail(false, true, open, line);
        return Mono.fromCallable(() -> ChatLabels.of(l, copy))
                .flatMap(labels -> contexts.build(familyId, patient.id(), patient.name(), labels))
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .onErrorResume(e -> {
                    log.warn("[baymax] chat purpose reply for patient={} read no context: {}", patient.id(), e.getClass().getSimpleName());
                    return Mono.just(Optional.empty());
                })
                .flatMap(read -> {
                    if (read.isEmpty()) {
                        return stored(familyId, patient, q, l, viewer, new Body(line.apply("chat.ask.failed"), AnswerKind.DECLINED,
                                MedicinePurposeGuard.HANDLER, List.of()), tail, started);
                    }
                    ChatContext ctx = read.get();
                    MedicinePurposeGuard.Route again = MedicinePurposeGuard.route(q, ctx.medicineNames(), MedicinePurposeGuard.labNames(ctx),
                            patient.name());
                    if (again != MedicinePurposeGuard.Route.CODE) {
                        boolean purposeLine = again != MedicinePurposeGuard.Route.NONE;
                        return byModel(familyId, patient, q, false, purposeLine, l, viewer, ChatAppendix.tail(false, purposeLine, open, line),
                                started, startMs);
                    }
                    MedicinePurposeReply.Reply reply = MedicinePurposeReply.of(q, ctx, patient.name(), l, copy,
                            properties.getChat().getMaxAnswerChars() - tail.length());
                    // the lines shown carry their crops, as a model answer's quoted lines do (DR-12; review of #171 on #166)
                    return stored(familyId, patient, q, l, viewer, new Body(reply.text(), AnswerKind.DECLINED, MedicinePurposeGuard.HANDLER,
                            reply.documents(), AnswerCheck.citedLines(reply.quoted(), ctx)), tail, started);
                });
    }

    /** A code-built body on an uncounted turn. */
    private Mono<AskAnswer> stored(UUID familyId, PatientProfile patient, String q, String l, ViewerLocale viewer, Body body,
                                   String tail, long started) {
        return turns.reserveUncounted(familyId, patient.id(), l, q)
                .flatMap(id -> finish(id, patient, q, l, viewer, body, tail, new Tally(), started));
    }

    /** The deadline less what has passed since {@code startMs}, on the timeouts' clock; never negative. */
    private static Duration remaining(Duration deadline, long startMs) {
        long left = deadline.toMillis() - (Schedulers.parallel().now(TimeUnit.MILLISECONDS) - startMs);
        return Duration.ofMillis(Math.max(0, left));
    }

    /** The first {@code max} characters of a text, never splitting a code point. */
    static String cut(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int end = max;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }

    /**
     * 5–6: the context, one call, the checks, one repair, else the fixed reply. Never empty. {@code dose} is the decision that
     * also appends the ask-the-doctor line, so the prompt's dose rule and the appended line never disagree (review of #170);
     * {@code purpose} is likewise the decision that appends {@code chat.purpose.ask} (review of #171, finding 3).
     */
    private Mono<AskAnswer> modelled(UUID turnId, LlmClient client, UUID familyId, PatientProfile patient, String q, boolean dose,
                                     boolean purpose, String l, ViewerLocale viewer, String tail, long started,
                                     long startMs) {
        BaymaxProperties.Chat chat = properties.getChat();
        Tally tally = new Tally();
        Function<String, String> line = key -> copy.line(l, key);
        Body failed = new Body(line.apply("chat.ask.failed"), AnswerKind.FALLBACK, "fixed_after_repair", List.of());
        // the labels are read inside the chain, so a missing bundle key is a model error and the fixed reply, never a 500
        Mono<Body> work = Mono.fromCallable(() -> ChatLabels.of(l, copy)).flatMap(labels ->
                contexts.build(familyId, patient.id(), patient.name(), labels).flatMap(ctx -> {
                    int bodyChars = chat.getMaxAnswerChars() - tail.length();
                    // the name the family typed in a purpose question is a medicine to the purpose rule (finding 1 b)
                    Set<String> asked = MedicinePurposeGuard.askedNames(q, ctx.medicineNames(), MedicinePurposeGuard.labNames(ctx),
                            patient.name());
                    return call(client, ctx, labels, patient.name(), q, dose, purpose, bodyChars, List.of(), tally).flatMap(first -> {
                        List<String> v1 = check(first, ctx, tail, asked);
                        if (v1.isEmpty()) {
                            return Mono.just(new Body(AnswerCheck.resolve(first.strip(), ctx), AnswerKind.ANSWER, "model", ctx.documentIds(),
                                    AnswerCheck.cited(first.strip(), ctx)));
                        }
                        tally.violations.addAll(v1);
                        log.debug("[baymax] chat turn={} refused first answer: {}", turnId, v1);
                        return call(client, ctx, labels, patient.name(), q, dose, purpose, bodyChars, v1, tally).map(second -> {
                            List<String> v2 = check(second, ctx, tail, asked);
                            if (v2.isEmpty()) {
                                return new Body(AnswerCheck.resolve(second.strip(), ctx), AnswerKind.ANSWER, "repair", ctx.documentIds(),
                                        AnswerCheck.cited(second.strip(), ctx));
                            }
                            tally.violations.addAll(v2);
                            log.debug("[baymax] chat turn={} refused the repair: {}", turnId, v2);
                            return failed;
                        });
                    });
                }));
        // a context read or a call that completes empty is a model error, never an empty turn: a reserved, counted turn is
        // always completed (review of #164, finding 6)
        return work.switchIfEmpty(Mono.fromSupplier(() -> new Body(failed.text(), AnswerKind.FALLBACK, "model_error", List.of())))
                // what is left of the deadline, counted from the request: the reads before the model count against it
                .timeout(remaining(chat.getAnswerDeadline(), startMs))
                .onErrorResume(e -> {
                    log.warn("[baymax] chat turn={} model failed after {} call(s): {}", turnId, tally.calls, e.getClass().getSimpleName());
                    return Mono.just(new Body(failed.text(), AnswerKind.FALLBACK, "model_error", List.of()));
                })
                .flatMap(body -> finish(turnId, patient, q, l, viewer, body, tail, tally, started));
    }

    /** One metered call under the per-call timeout; a {@code max_tokens} stop is a failure, never a truncated answer. */
    private Mono<String> call(LlmClient client, ChatContext ctx, ChatLabels labels, String name, String q, boolean dose,
                              boolean purpose, int bodyChars, List<String> prior, Tally tally) {
        BaymaxProperties.Chat chat = properties.getChat();
        return Mono.defer(() -> {
            tally.calls++;
            return metered.using(client)
                    .invoke(AiCallPurpose.CHAT, null,
                            ChatPrompt.of(ctx, labels, name, q, dose, purpose, bodyChars, prior, chat.getMaxOutputTokens()))
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
    private List<String> check(String modelText, ChatContext ctx, String tail, Set<String> asked) {
        List<String> v = new ArrayList<>(AnswerCheck.violations(modelText, ctx, asked));
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
                body.documents(), body.medicines());
        boolean call999 = body.kind() == AnswerKind.EMERGENCY && viewer.bangladesh();
        return turns.complete(turnId, outcome)
                .then(Mono.fromSupplier(() -> {
                    log.info("[baymax] chat turn={} patient={} kind={} handler={} q_chars={} a_chars={} calls={} in={} out={} usd={} ms={}"
                                    + " violations={}", turnId, patient.id(), body.kind().dbValue(), body.handler(), q.length(), answer.length(),
                            tally.calls, tally.in, tally.out, tally.usd, ms, String.join(",", codes));
                    log.debug("[baymax] chat turn={} question={} answer={}", turnId, q, answer);
                    return new AskAnswer(turnId, body.kind(), q, answer, call999, clock.instant(), body.medicines());
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
