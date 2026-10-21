package com.elioo.baymax.web.application.service;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.common.time.PatientCalendar;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.nudge.application.port.out.NudgeDataPort;
import com.elioo.baymax.web.application.port.in.AttentionUseCase;
import com.elioo.baymax.web.application.port.out.TimelineReadPort;
import com.elioo.baymax.web.domain.AttentionItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The চ্যাট attention stack (spec §4.6), after visibility: the urgent papers are exactly what the read model calls
 * urgent (C2's stored F1 levels, filtered in SQL, so no number of newer routine papers can hide a NOW one); the papers
 * not finished come from the same read model; the doctor's dates from the stored follow-ups. "Today" is the patient's
 * calendar. Nothing here assesses, reads a message row or calls a model.
 */
@Service
@RequiredArgsConstructor
public class AttentionService implements AttentionUseCase {

    /** Spec §4.6: what the last 30 days left needing attention. */
    static final Duration WINDOW = Duration.ofDays(30);
    /** A follow-up the doctor wrote due within seven days of the patient's today (both days included). */
    static final int FOLLOW_UP_DAYS = 7;
    public static final int URGENT_LIMIT = 10;
    /** Plan B3's follow-ups list: from thirty days before the patient's today, so a missed visit stays in sight… */
    static final int FOLLOW_UPS_BACK_DAYS = 30;
    /** …to a year after it. */
    static final int FOLLOW_UPS_AHEAD_DAYS = 365;

    private final HealthRecordPort records;
    private final TimelineReadPort timeline;
    private final NudgeDataPort nudgeData;
    private final Clock clock;

    @Override
    public Mono<List<AttentionItem>> attention(UUID familyId, UUID patientId) {
        return visible(familyId, patientId)
                .flatMap(access -> {
                    Instant since = clock.instant().minus(WINDOW);
                    LocalDate today = PatientCalendar.today(clock);
                    return Mono.zip(
                            urgentRows(patientId, since).map(u -> (AttentionItem) u).collectList(),
                            timeline.inFlightAndRetakes(patientId, since)
                                    .map(e -> "NEEDS_RETAKE".equals(e.status())
                                            ? (AttentionItem) new AttentionItem.Retake(e.documentId(), e.date(), e.dateIsFallback(), e.createdAt())
                                            : new AttentionItem.Reading(e.documentId(), e.date(), e.dateIsFallback(), e.createdAt()))
                                    .collectList(),
                            nudgeData.openFollowUpsOfPatient(patientId, today, today.plusDays(FOLLOW_UP_DAYS))
                                    .map(f -> (AttentionItem) new AttentionItem.FollowUp(f.documentId(), f.instruction(), f.dueDate(), f.cropKey()))
                                    .collectList());
                })
                .map(t -> {
                    List<AttentionItem> all = new ArrayList<>(t.getT1());
                    all.addAll(t.getT2());
                    all.addAll(t.getT3());
                    all.sort(AttentionItem.ORDER);
                    return List.copyOf(all);
                });
    }

    @Override
    public Mono<List<AttentionItem.Urgent>> urgent(UUID familyId, UUID patientId) {
        return visible(familyId, patientId)
                .flatMap(access -> urgentRows(patientId, clock.instant().minus(WINDOW)).sort(AttentionItem.ORDER).collectList())
                .map(List::copyOf);
    }

    @Override
    public Mono<List<AttentionItem.FollowUp>> followUps(UUID familyId, UUID patientId) {
        return visible(familyId, patientId)
                .flatMap(access -> {
                    LocalDate today = PatientCalendar.today(clock);
                    return nudgeData.openFollowUpsOfPatient(patientId, today.minusDays(FOLLOW_UPS_BACK_DAYS), today.plusDays(FOLLOW_UPS_AHEAD_DAYS))
                            .map(f -> new AttentionItem.FollowUp(f.documentId(), f.instruction(), f.dueDate(), f.cropKey()))
                            .sort(AttentionItem.ORDER)
                            .collectList();
                })
                .map(List::copyOf);
    }

    private Mono<?> visible(UUID familyId, UUID patientId) {
        return records.visiblePatient(familyId, patientId)
                .switchIfEmpty(Mono.error(BaymaxException.notFound("patient_not_found", "no patient with id " + patientId)));
    }

    /** The read model's urgent rows (stored NOW / THIS_WEEK only, filtered in SQL) as cards. */
    private Flux<AttentionItem.Urgent> urgentRows(UUID patientId, Instant since) {
        return timeline.attention(patientId, since, URGENT_LIMIT)
                .map(e -> new AttentionItem.Urgent(e.documentId(), e.urgency(), e.documentType(), e.date(), e.dateIsFallback(), e.createdAt(),
                        e.dateUncertain()));
    }
}
