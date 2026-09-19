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
    private final BaymaxProperties properties;
    private final Clock clock;

    public NudgeEvaluationService(NudgeDataPort data, NudgePort nudges, NudgeRules rules, ComposeNudgeUseCase composer,
                                  NudgeOptOutUseCase optOut, DocumentRecordPort documents, BaymaxProperties properties, Clock clock) {
        this.data = data;
        this.nudges = nudges;
        this.rules = rules;
        this.policy = new NudgePolicy(properties.getNudge());
        this.composer = composer;
        this.optOut = optOut;
        this.documents = documents;
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
        return releaseHeld(now)
                .then(global)
                .flatMap(byPatient -> data.patients()
                        .filter(p -> !p.optedOut())
                        .concatMap(p -> candidatesFor(p, byPatient.getOrDefault(p.patientId(), List.of()))
                                .flatMap(cs -> decide(p, cs, now)))
                        .reduce(0L, Long::sum));
    }

    private Mono<List<NudgeCandidate>> candidatesFor(PatientRef p, List<NudgeCandidate> global) {
        return Flux.concat(Flux.fromIterable(global), rules.trend(p), rules.silence(p).flux()).collectList();
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
                .filterWhen(c -> nudges.exists(c.patientId(), c.rule(), c.triggerKey()).map(exists -> !exists))
                .collectList()
                .flatMap(fresh -> {
                    NudgePolicy.Selection sel = policy.select(fresh);
                    if (sel.winner() == null) {
                        return Mono.just(0L);
                    }
                    Mono<Void> losers = Flux.fromIterable(sel.dropped())
                            .concatMap(c -> record(c, NudgeStatus.DROPPED, "superseded_by_" + sel.winner().rule().dbValue(), null, null, now))
                            .then();
                    return losers.then(place(sel.winner(), now));
                });
    }

    /** Caps, then window, then composition. */
    private Mono<Long> place(NudgeCandidate c, Instant now) {
        return Mono.zip(nudges.countedSince(c.patientId(), policy.sevenDaysAgo(now)), nudges.countedSince(c.patientId(), policy.startOfToday(now)))
                .flatMap(t -> {
                    String cap = policy.capReason(t.getT1(), t.getT2());
                    if (cap != null) {
                        return record(c, NudgeStatus.DROPPED, cap, null, null, now).thenReturn(0L);
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
                                NudgeCandidate c = new NudgeCandidate(row.rule(), row.familyId(), row.patientId(), row.triggerKey(), row.urgency(),
                                        row.vars(), NudgeRules.numbersOf(row.vars().values()), List.of());
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
        return nudges.save(new Nudge(null, c.familyId(), c.patientId(), c.rule(), c.triggerKey(), c.urgency(), status, reason, c.vars(),
                messageId, holdUntil, now, status == NudgeStatus.HELD ? null : now));
    }
}
