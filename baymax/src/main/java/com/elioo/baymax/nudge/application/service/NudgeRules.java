package com.elioo.baymax.nudge.application.service;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.nudge.application.port.out.NudgeDataPort;
import com.elioo.baymax.nudge.application.port.out.NudgeDataPort.MedicationRow;
import com.elioo.baymax.nudge.application.port.out.NudgeDataPort.ObservationRow;
import com.elioo.baymax.nudge.application.port.out.NudgeDataPort.PatientRef;
import com.elioo.baymax.nudge.application.port.out.NudgePort;
import com.elioo.baymax.nudge.domain.NudgeCandidate;
import com.elioo.baymax.nudge.domain.NudgeRule;
import com.elioo.baymax.nudge.domain.NudgeUrgency;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The five rules (BMX-8), each a pure function of stored rows. No model call anywhere in this class. Every
 * candidate carries the extraction's own strings verbatim in {@code vars}, the digit runs those strings contain as
 * the closed set of numbers the message may use, and the crops that ground it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NudgeRules {

    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern NUMERIC = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private final NudgeDataPort data;
    private final NudgePort nudges;
    private final BaymaxProperties properties;
    private final Clock clock;

    ZoneId zone() {
        return ZoneId.of(properties.getNudge().getZone());
    }

    LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), zone());
    }

    // ---- follow_up_due: an open follow_up whose due_date is T-2 days; once per follow_up ---------------------

    Flux<NudgeCandidate> followUpDue() {
        LocalDate due = today().plusDays(properties.getNudge().getFollowUpLeadDays());
        return data.openFollowUpsDueOn(due).map(f -> {
            Map<String, String> vars = new LinkedHashMap<>();
            vars.put("instruction", nz(f.instruction()));
            vars.put("due_date", f.dueDate().toString());
            return new NudgeCandidate(NudgeRule.FOLLOW_UP_DUE, f.familyId(), f.patientId(), "follow_up:" + f.id(), NudgeUrgency.ROUTINE,
                    vars, numbersOf(vars.values()), List.of(f.cropKey()));
        });
    }

    // ---- course_ending: a medication whose duration yields an end date at T-1; once per medicine per prescription -

    Flux<NudgeCandidate> courseEnding() {
        LocalDate target = today().plusDays(properties.getNudge().getCourseLeadDays());
        Instant since = clock.instant().minus(Duration.ofDays(400));
        return data.medicationsWithDurationSince(since).flatMap(m -> {
            Optional<Integer> days = DurationParser.days(m.durationText());
            if (days.isEmpty()) {
                log.info("[baymax] course_ending: unparseable duration medicationId={} documentId={}", m.id(), m.documentId());
                return Mono.empty();
            }
            LocalDate start = m.docDate() != null ? m.docDate() : LocalDate.ofInstant(m.at(), zone());
            LocalDate end = start.plusDays(days.get() - 1L);
            if (!end.equals(target)) {
                return Mono.empty();
            }
            Map<String, String> vars = new LinkedHashMap<>();
            vars.put("medicine", nz(m.name()));
            vars.put("dose_text", nz(m.doseText()));
            vars.put("frequency_text", nz(m.frequencyText()));
            vars.put("timing_text", nz(m.timingText()));
            vars.put("duration_text", nz(m.durationText()));
            vars.put("end_date", end.toString());
            return Mono.just(new NudgeCandidate(NudgeRule.COURSE_ENDING, m.familyId(), m.patientId(), "medication:" + m.id(),
                    NudgeUrgency.ROUTINE, vars, numbersOf(vars.values()), List.of(m.cropKey())));
        });
    }

    // ---- medicine_changed: on a new prescription with a prior one; names what changed, verbatim both sides ------

    Mono<NudgeCandidate> medicineChanged(UUID patientId, UUID documentId) {
        return data.medicationsOfPatient(patientId).collectList().flatMap(rows -> {
            // rows are newest document first; group by document keeping order
            Map<UUID, List<MedicationRow>> byDoc = new LinkedHashMap<>();
            for (MedicationRow r : rows) {
                byDoc.computeIfAbsent(r.documentId(), k -> new ArrayList<>()).add(r);
            }
            List<UUID> order = new ArrayList<>(byDoc.keySet());
            int idx = order.indexOf(documentId);
            if (idx < 0 || idx + 1 >= order.size()) {
                return Mono.empty();   // no prior prescription: nothing changed
            }
            List<MedicationRow> now = byDoc.get(documentId);
            List<MedicationRow> before = byDoc.get(order.get(idx + 1));
            List<String> lines = new ArrayList<>();
            List<String> crops = new ArrayList<>();
            Map<String, MedicationRow> nowByName = new LinkedHashMap<>();
            for (MedicationRow r : now) {
                nowByName.putIfAbsent(key(r.name()), r);
            }
            for (MedicationRow b : before) {
                MedicationRow n = nowByName.get(key(b.name()));
                if (n == null) {
                    lines.add(nz(b.name()) + " — " + joined(b) + " → (নতুন প্রেসক্রিপশনে নেই)");
                    crops.add(b.cropKey());
                } else if (!nz(b.doseText()).equals(nz(n.doseText())) || !nz(b.frequencyText()).equals(nz(n.frequencyText()))) {
                    lines.add(nz(b.name()) + " — " + joined(b) + " → " + joined(n));
                    crops.add(b.cropKey());
                    crops.add(n.cropKey());
                }
            }
            if (lines.isEmpty()) {
                return Mono.empty();
            }
            MedicationRow any = now.isEmpty() ? before.get(0) : now.get(0);
            Map<String, String> vars = new LinkedHashMap<>();
            vars.put("changes", String.join("\n", lines));
            vars.put("date", any.docDate() == null ? "" : any.docDate().toString());
            return Mono.just(new NudgeCandidate(NudgeRule.MEDICINE_CHANGED, any.familyId(), patientId, "document:" + documentId,
                    NudgeUrgency.THIS_WEEK, vars, numbersOf(vars.values()), crops));
        });
    }

    private static String joined(MedicationRow r) {
        List<String> parts = new ArrayList<>();
        for (String s : List.of(nz(r.doseText()), nz(r.frequencyText()), nz(r.timingText()))) {
            if (!s.isEmpty()) {
                parts.add(s);
            }
        }
        return parts.isEmpty() ? "(মাত্রা লেখা নেই)" : String.join(" · ", parts);
    }

    private static String key(String name) {
        return nz(name).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    // ---- trend: N consecutive readings of one canonical marker moving the wrong way (DR-17) ---------------------

    Flux<NudgeCandidate> trend(PatientRef patient) {
        Set<String> rising = new HashSet<>(properties.getNudge().getTrendRising());
        Set<String> either = new HashSet<>(properties.getNudge().getTrendEitherWay());
        return data.markersOf(patient.patientId())
                .filter(m -> rising.contains(m) || either.contains(m))
                .flatMap(marker -> data.observationsOf(patient.patientId(), marker).collectList()
                        .flatMap(rows -> Mono.justOrEmpty(trendOf(patient, marker, rows, rising.contains(marker)))));
    }

    /** The current monotonic run at the end of the series; a candidate when it is long enough and in the wrong direction. */
    Optional<NudgeCandidate> trendOf(PatientRef patient, String marker, List<ObservationRow> rows, boolean risingIsWrong) {
        List<ObservationRow> numeric = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        for (ObservationRow r : rows) {
            Optional<Double> v = numeric(r.value());
            if (v.isPresent()) {
                numeric.add(r);
                values.add(v.get());
            }
        }
        int n = properties.getNudge().getTrendReadings();
        if (values.size() < n) {
            return Optional.empty();
        }
        int last = values.size() - 1;
        int direction = Double.compare(values.get(last), values.get(last - 1));   // +1 rising, -1 falling
        if (direction == 0) {
            return Optional.empty();
        }
        if (risingIsWrong && direction < 0) {
            return Optional.empty();
        }
        int start = last;
        while (start > 0 && Double.compare(values.get(start), values.get(start - 1)) == direction) {
            start--;
        }
        int run = last - start + 1;
        if (run < n) {
            return Optional.empty();
        }
        // the key is the run's first reading: a fourth reading in the same run dedupes, a reversal starts a new run
        List<ObservationRow> window = numeric.subList(start, last + 1);
        List<String> shown = new ArrayList<>();
        List<String> crops = new ArrayList<>();
        for (ObservationRow r : window) {
            shown.add(nz(r.value()) + (nz(r.unit()).isEmpty() ? "" : " " + r.unit()));
            crops.add(r.cropKey());
        }
        ObservationRow latest = numeric.get(last);
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("marker", nz(latest.name()));
        vars.put("values", String.join(" → ", shown));
        vars.put("count", String.valueOf(window.size()));
        vars.put("first_date", LocalDate.ofInstant(window.get(0).observedAt(), zone()).toString());
        vars.put("last_date", LocalDate.ofInstant(latest.observedAt(), zone()).toString());
        vars.put("direction", direction > 0 ? "up" : "down");
        return Optional.of(new NudgeCandidate(NudgeRule.TREND, patient.familyId(), patient.patientId(),
                "trend:" + marker + ":" + numeric.get(start).id(), NudgeUrgency.THIS_WEEK, vars, numbersOf(vars.values()), crops));
    }

    static Optional<Double> numeric(String value) {
        if (value == null) {
            return Optional.empty();
        }
        Matcher m = NUMERIC.matcher(DurationParser.fold(value).replace(',', '.'));
        if (!m.find()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Double.parseDouble(m.group()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    // ---- silence: chronic flags, no document in N days; once, then suppressed ------------------------------------

    Mono<NudgeCandidate> silence(PatientRef patient) {
        if (patient.chronicFlags() == null || patient.chronicFlags().isEmpty()) {
            return Mono.empty();   // asked, never inferred: no flags, no silence nudge, whatever the quiet period
        }
        Instant now = clock.instant();
        Instant cutoff = now.minus(Duration.ofDays(properties.getNudge().getSilenceDays()));
        return data.lastDocumentAt(patient.patientId())
                .filter(last -> !last.isAfter(cutoff))
                .flatMap(last -> nudges.latest(patient.patientId(), NudgeRule.SILENCE)
                        .filter(prev -> prev.createdAt().isAfter(now.minus(Duration.ofDays(properties.getNudge().getSilenceSuppressDays()))))
                        .map(prev -> Optional.<NudgeCandidate>empty())
                        .defaultIfEmpty(Optional.of(silenceCandidate(patient, last)))
                        .flatMap(Mono::justOrEmpty));
    }

    private NudgeCandidate silenceCandidate(PatientRef patient, Instant lastDocumentAt) {
        Map<String, String> vars = new LinkedHashMap<>();
        long days = Duration.between(lastDocumentAt, clock.instant()).toDays();
        vars.put("days", String.valueOf(days));
        vars.put("last_date", LocalDate.ofInstant(lastDocumentAt, zone()).toString());
        return new NudgeCandidate(NudgeRule.SILENCE, patient.familyId(), patient.patientId(), "silence:" + today(), NudgeUrgency.ROUTINE,
                vars, numbersOf(vars.values()), List.of());
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    static Set<String> numbersOf(Iterable<String> strings) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : strings) {
            if (s == null) {
                continue;
            }
            Matcher m = DIGITS.matcher(DurationParser.fold(s));
            while (m.find()) {
                out.add(m.group());
            }
        }
        return out;
    }

    static String nz(String s) {
        return s == null ? "" : s;
    }
}
