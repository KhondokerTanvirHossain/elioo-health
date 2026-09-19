package com.elioo.baymax.nudge.application.service;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.nudge.application.port.in.NudgeOptOutUseCase;
import com.elioo.baymax.nudge.application.port.in.NudgeUseCase;
import com.elioo.baymax.nudge.application.port.out.NudgeDataPort;
import com.elioo.baymax.nudge.application.port.out.NudgeDataPort.PatientRef;
import com.elioo.baymax.nudge.application.port.out.NudgePort;
import com.elioo.baymax.nudge.domain.Nudge;
import com.elioo.baymax.nudge.domain.NudgeCandidate;
import com.elioo.baymax.nudge.domain.NudgeStatus;
import com.elioo.baymax.outbound.application.port.in.ComposeNudgeUseCase;
import com.elioo.baymax.outbound.domain.OutboundMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The proactive engine (BMX-8). One evaluation: release held nudges now inside the window, then for every patient
 * who has not opted out, run the rules on stored data, apply the policy centrally, record every outcome, and hand
 * the one winner to the BMX-6 composition path. No model call happens here.
 */
@Slf4j
@Service
public class NudgeEvaluationService implements NudgeUseCase {

    private final NudgeDataPort data;
    private final NudgePort nudges;
    private final NudgeRules rules;
    private final NudgePolicy policy;
    private final ComposeNudgeUseCase composer;
    private final NudgeOptOutUseCase optOut;
    private final DocumentRecordPort documents;
    private final com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort records;
    private final BaymaxProperties properties;
    private final Clock clock;
    /** Patients skipped after an error in the last {@link #evaluateAll()} (DR-22); surfaced in the weekly export. */
    private final java.util.concurrent.atomic.AtomicInteger failedPatients = new java.util.concurrent.atomic.AtomicInteger();

    /** How many patients the last evaluation skipped after an error. Zero on a clean run. */
    public int lastRunFailedPatients() {
        return failedPatients.get();
    }

    public NudgeEvaluationService(NudgeDataPort data, NudgePort nudges, NudgeRules rules, ComposeNudgeUseCase composer,
                                  NudgeOptOutUseCase optOut, DocumentRecordPort documents,
                                  com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort records,
                                  BaymaxProperties properties, Clock clock) {
        this.data = data;
        this.nudges = nudges;
        this.rules = rules;
        this.policy = new NudgePolicy(properties.getNudge());
        this.composer = composer;
        this.optOut = optOut;
        this.documents = documents;
        this.records = records;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Mono<Long> evaluateAll() {
        Instant now = clock.instant();
        // the two global rules are one query each; group their candidates by patient
        Mono<Map<UUID, List<NudgeCandidate>>> global = Flux.concat(rules.followUpDue(), rules.courseEnding())
                .collect(LinkedHashMap::new, (Map<UUID, List<NudgeCandidate>> m, NudgeCandidate c) ->
                        m.computeIfAbsent(c.patientId(), k -> new ArrayList<>()).add(c));
        failedPatients.set(0);
        return releaseHeld(now)
                .then(global)
                .flatMap(byPatient -> data.patients()
                        .filter(p -> !p.optedOut())
                        // One patient's failure must never cost every other family their nudges for this run
                        // (DR-22): isolate it, count it, carry on. Before this, a single duplicate-key error
                        // aborted the whole evaluation and nobody would have noticed until a pilot family asked
                        // why they had heard nothing.
                        .concatMap(p -> candidatesFor(p, byPatient.getOrDefault(p.patientId(), List.of()))
                                .flatMap(cs -> decide(p, cs, now))
                                .onErrorResume(e -> {
                                    failedPatients.incrementAndGet();
                                    log.error("[baymax] nudge evaluation failed for patientId={} familyId={} — skipped, run continues: {}",
                                            p.patientId(), p.familyId(), e.toString());
                                    return Mono.just(0L);
                                }))
                        .reduce(0L, Long::sum))
                .doOnSuccess(n -> {
                    int failed = failedPatients.get();
                    if (failed > 0) {
                        log.warn("[baymax] nudge evaluation completed with {} patient(s) skipped after errors", failed);
                    }
                });
    }

    private Mono<List<NudgeCandidate>> candidatesFor(PatientRef p, List<NudgeCandidate> global) {
        return Flux.concat(Flux.fromIterable(global), rules.trend(p), rules.silence(p).flux())
                .collectList()
                .flatMap(this::named);
    }

    /**
     * Every nudge names the patient as the family entered it (PO ruling 2026-09-19): the reader is the eldest
     * child, the patient is their parent, so "আপনার" would address the wrong person.
     */
    private Mono<List<NudgeCandidate>> named(List<NudgeCandidate> candidates) {
        if (candidates.isEmpty()) {
            return Mono.just(candidates);
        }
        return records.findPatient(candidates.get(0).patientId())
                .map(profile -> profile.name())
                .defaultIfEmpty("")
                .map(name -> candidates.stream().map(c -> c.withPatientName(name)).toList());
    }

    @Override
    public Mono<Void> onDocumentDone(UUID documentId) {
        return documents.find(documentId)
                .filter(d -> d.status() == Document.Status.DONE && "prescription".equalsIgnoreCase(d.documentType()))
                .flatMap(d -> data.patient(d.patientId())
                        .filter(p -> !p.optedOut())
                        .flatMap(p -> rules.medicineChanged(p.patientId(), documentId)
                                .flatMap(c -> decide(p, List.of(c), clock.instant()))))
                .then();
    }

    /** Dedupe, select, cap, window — every outcome a row. Returns how many were handed to composition. */
    Mono<Long> decide(PatientRef p, List<NudgeCandidate> candidates, Instant now) {
        return Flux.fromIterable(candidates)
                // a deferred row is a retry, not a duplicate: consuming it lets the trigger be attempted again (DR-19)
                .filterWhen(c -> nudges.consumeDeferred(c.patientId(), c.rule(), c.triggerKey(), now)
                        .flatMap(retry -> retry ? Mono.just(true)
                                : nudges.exists(c.patientId(), c.rule(), c.triggerKey()).map(exists -> !exists)))
                .collectList()
                .flatMap(fresh -> {
                    NudgePolicy.Selection sel = policy.select(fresh);
                    if (sel.winner() == null) {
                        return Mono.just(0L);
                    }
                    // DR-20 widens DR-19: a date-bound nudge is never discarded, whether a cap refused it or a tie
                    // outranked it — the family missing the appointment is the same failure either way. It defers
                    // and is retried until its date passes; only trend/medicine_changed/silence may be dropped.
                    Mono<Void> losers = Flux.fromIterable(sel.dropped())
                            .concatMap(c -> record(c, deferOrDrop(c), "superseded_by_" + sel.winner().rule().dbValue(), null, null, now))
                            .then();
                    return losers.then(place(sel.winner(), now));
                });
    }

    /**
     * DR-20: the single definition of "may this candidate be discarded?". A date-bound trigger (follow_up_due,
     * course_ending) is never dropped before its date passes — it sends, defers, or expires — however it lost:
     * refused by a cap or beaten in a tie. Non-date-bound rules can be said on any day, so they drop.
     */
    private NudgeStatus deferOrDrop(NudgeCandidate c) {
        return c.rule().isDateBound() && !c.expired(rules.today()) ? NudgeStatus.DEFERRED : NudgeStatus.DROPPED;
    }

    /** Caps, then window, then composition. */
    private Mono<Long> place(NudgeCandidate c, Instant now) {
        return Mono.zip(nudges.countedSince(c.patientId(), policy.sevenDaysAgo(now)), nudges.countedSince(c.patientId(), policy.startOfToday(now)))
                .flatMap(t -> {
                    String cap = policy.capReason(t.getT1(), t.getT2());
                    if (cap != null) {
                        // DR-19: a date-bound nudge refused by a cap is deferred to the next day, never discarded —
                        // follow_up_due fires once per follow-up, so a drop meant the appointment was never mentioned
                        return record(c, deferOrDrop(c), cap, null, null, now).thenReturn(0L);
                    }
                    if (!policy.inWindow(now)) {
                        return record(c, NudgeStatus.HELD, null, null, policy.nextWindowStart(now), now).thenReturn(0L);
                    }
                    // inside the window: the row exists before composition (dedupe) but carries no hold_until, so a
                    // concurrent evaluation's releaseHeld can never compose the same trigger a second time
                    return record(c, NudgeStatus.HELD, null, null, null, now).flatMap(row -> send(row, c)).thenReturn(1L);
                });
    }

    /** Held rows whose window has come: caps are re-checked at release, since the day may have moved on. */
    private Mono<Void> releaseHeld(Instant now) {
        if (!policy.inWindow(now)) {
            return Mono.empty();
        }
        return nudges.heldDueBy(now)
                // claim the row first (resolve clears hold_until), so two evaluations never compose the same hold
                .concatMap(claimed -> nudges.resolve(claimed.id(), NudgeStatus.HELD, null, null, now))
                .concatMap(row -> Mono.zip(nudges.countedSince(row.patientId(), policy.sevenDaysAgo(now)), nudges.countedSince(row.patientId(), policy.startOfToday(now)))
                        .flatMap(t -> {
                            String cap = policy.capReason(t.getT1(), t.getT2());
                            if (cap != null) {
                                return nudges.resolve(row.id(), NudgeStatus.DROPPED, cap, null, now).then();
                            }
                            return data.patient(row.patientId()).flatMap(p -> {
                                if (p.optedOut()) {
                                    return nudges.resolve(row.id(), NudgeStatus.DROPPED, "opted_out", null, now).then();
                                }
                                NudgeCandidate c = new NudgeCandidate(row.rule(), row.familyId(), row.patientId(),
                                        row.vars().get("patient"), row.triggerKey(), row.urgency(), row.vars(),
                                        NudgeRules.numbersOf(row.vars().values()), List.of(), null);
                                return send(row, c).then();
                            });
                        }))
                .then();
    }

    private Mono<Nudge> send(Nudge row, NudgeCandidate c) {
        String link = properties.getOutbound().getPublicBaseUrl() + "/app/nudges/opt-out?p=" + c.patientId() + "&t=" + optOut.token(c.patientId());
        return composer.compose(c, link)
                .flatMap(m -> {
                    NudgeStatus st = m.gateStatus() == OutboundMessage.GateStatus.FAILED_SAFETY ? NudgeStatus.FAILED
                            : m.gateStatus() == OutboundMessage.GateStatus.PENDING ? NudgeStatus.GATED : NudgeStatus.SENT;
                    log.info("[baymax] nudge rule={} patientId={} status={} messageId={}", c.rule(), c.patientId(), st, m.id());
                    return nudges.resolve(row.id(), st, st == NudgeStatus.FAILED ? "failed_safety" : null, m.id(), clock.instant());
                })
                .onErrorResume(e -> {
                    log.error("[baymax] nudge composition failed rule={} patientId={}: {}", c.rule(), c.patientId(), e.getMessage(), e);
                    return nudges.resolve(row.id(), NudgeStatus.FAILED, "error:" + e.getClass().getSimpleName(), null, clock.instant());
                });
    }

    private Mono<Nudge> record(NudgeCandidate c, NudgeStatus status, String reason, UUID messageId, Instant holdUntil, Instant now) {
        Map<String, String> vars = new LinkedHashMap<>(c.vars());
        if (c.patientName() != null) {
            vars.put("patient", c.patientName());   // a held row is composed later and still needs the name
        }
        return nudges.save(new Nudge(null, c.familyId(), c.patientId(), c.rule(), c.triggerKey(), c.urgency(), status, reason, vars,
                messageId, holdUntil, now, status == NudgeStatus.HELD ? null : now));
    }
}
