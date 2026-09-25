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
import com.elioo.baymax.outbound.application.service.BanglaDate;
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
            vars.put("due_date", BanglaDate.format(f.dueDate(), today()));
            return new NudgeCandidate(NudgeRule.FOLLOW_UP_DUE, f.familyId(), f.patientId(), null, "follow_up:" + f.id(),
                    NudgeUrgency.ROUTINE, vars, numbersOf(vars.values()), List.of(f.cropKey()), f.dueDate());
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
            vars.put("end_date", BanglaDate.format(end, today()));
            return Mono.just(new NudgeCandidate(NudgeRule.COURSE_ENDING, m.familyId(), m.patientId(), null, "medication:" + m.id(),
                    NudgeUrgency.ROUTINE, vars, numbersOf(vars.values()), List.of(m.cropKey()), end));
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
            for (MedicationRow b : before) {
                MedicationRow n = sameMedicine(b, now);
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
            vars.put("date", any.docDate() == null ? "" : BanglaDate.format(any.docDate(), today()));
            return Mono.just(new NudgeCandidate(NudgeRule.MEDICINE_CHANGED, any.familyId(), patientId, null, "document:" + documentId,
                    NudgeUrgency.THIS_WEEK, vars, numbersOf(vars.values()), crops, null));
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

    /** Dosage-form words the model may or may not copy from the page ("Tab. Amlodipine" one run, "Amlodipine" the next). */
    private static final Pattern FORM_WORDS = Pattern.compile("(?<![\\p{L}\\p{N}])(tab|tabs|tablet|tablets|cap|caps|capsule|capsules|syp|syrup|inj|injection|susp|suspension|oint|ointment|drops?|ট্যাব|ক্যাপ|সিরাপ|ইনজেকশন)(?![\\p{L}\\p{M}\\p{N}])\\.?");

    static String key(String name) {
        String k = nz(name).toLowerCase(Locale.ROOT);
        k = FORM_WORDS.matcher(k).replaceAll(" ");
        return k.replaceAll("[^\\p{L}\\p{M}\\p{N}]+", " ").trim();
    }

    /** The same medicine on the newer prescription: equal after form words are dropped, or one name contains the other. */
    static MedicationRow sameMedicine(MedicationRow before, List<MedicationRow> now) {
        String b = key(before.name());
        if (b.isEmpty()) {
            return null;
        }
        for (MedicationRow n : now) {
            String k = key(n.name());
            if (k.equals(b) || (k.length() >= 4 && b.length() >= 4 && (k.contains(b) || b.contains(k)))) {
                return n;
            }
        }
        return null;
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

    /**
     * The readings that share the MOST RECENT reading's unit, in order.
     *
     * <p>The latest unit wins because the trend is about where the patient is now: a series that moved from
     * mg/dL to µmol/L is a patient whose current lab reports in µmol/L, and the older numbers are not
     * comparable to it. Units are folded with NFKC and whitespace removed, so "umol/L", "µmol/L" and
     * "\u03bcmol/L" are one unit — MICRO SIGN normalises onto GREEK SMALL LETTER MU, and both are then
     * mapped onto ASCII "u" because a properties file or a label is typed by hand.</p>
     */
    private static List<ObservationRow> inLatestUnit(List<ObservationRow> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        String latest = unitKey(rows.get(rows.size() - 1).unit());
        List<ObservationRow> kept = new ArrayList<>();
        for (ObservationRow r : rows) {
            if (unitKey(r.unit()).equals(latest)) {
                kept.add(r);
            }
        }
        return kept;
    }

    /** Units compare folded, never converted: "mg/dL" and "µmol/L" are two units and stay two. */
    private static String unitKey(String unit) {
        if (unit == null) {
            return "";
        }
        return java.text.Normalizer.normalize(unit, java.text.Normalizer.Form.NFKC)
                .replace('\u03bc', 'u')
                .replaceAll("\\s+", "")
                .toLowerCase(java.util.Locale.ROOT);
    }

    /** The current monotonic run at the end of the series; a candidate when it is long enough and in the wrong direction. */
    Optional<NudgeCandidate> trendOf(PatientRef patient, String marker, List<ObservationRow> rows, boolean risingIsWrong) {
        // distinct document dates only (PO ruling 2026-09-19): several readings on one day are one report, and the
        // first reading of a day stands for it — the rule exists for slow decline nobody noticed, not for a day's repeats
        // A TREND ACROSS A UNIT CHANGE IS NOT A TREND. Bangladeshi labs report creatinine in mg/dL and some
        // regional labs in µmol/L, numbers about 88x apart, so a patient who changes lab produces
        // "1.1 mg/dL -> 1.3 mg/dL -> 65 µmol/L": three rising readings to a comparison that ignores units,
        // and a nudge telling a family to see a doctor about a perfectly normal result. Only readings in the
        // SAME unit as the most recent one are compared; the rest are set aside, not converted. Converting
        // would be worse — a wrong factor turns a normal result into an emergency silently.
        List<ObservationRow> sameUnit = inLatestUnit(rows);

        List<ObservationRow> numeric = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        LocalDate lastDate = null;
        for (ObservationRow r : sameUnit) {
            Optional<Double> v = numeric(r.value());
            LocalDate day = LocalDate.ofInstant(r.observedAt(), zone());
            if (v.isEmpty() || day.equals(lastDate)) {
                continue;
            }
            numeric.add(r);
            values.add(v.get());
            lastDate = day;
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
        // three readings inside a week is an acute situation a doctor is already handling (PO ruling 2026-09-19)
        long spanDays = Duration.between(numeric.get(start).observedAt(), numeric.get(last).observedAt()).toDays();
        if (spanDays < properties.getNudge().getTrendMinSpanDays()) {
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
        vars.put("count", BanglaDate.digits(String.valueOf(window.size())));
        vars.put("first_date", BanglaDate.format(LocalDate.ofInstant(window.get(0).observedAt(), zone()), today()));
        vars.put("last_date", BanglaDate.format(LocalDate.ofInstant(latest.observedAt(), zone()), today()));
        // the direction in words, not "changing in one direction" (PO ruling 2026-09-19)
        vars.put("direction", direction > 0 ? "একটু একটু করে বাড়ছে" : "একটু একটু করে কমছে");
        vars.put("direction_en", direction > 0 ? "creeping up" : "drifting down");
        return Optional.of(new NudgeCandidate(NudgeRule.TREND, patient.familyId(), patient.patientId(), null,
                "trend:" + marker + ":" + numeric.get(start).id(), NudgeUrgency.THIS_WEEK, vars, numbersOf(vars.values()), crops, null));
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
        vars.put("days", BanglaDate.digits(String.valueOf(days)));
        vars.put("last_date", BanglaDate.format(LocalDate.ofInstant(lastDocumentAt, zone()), today()));
        return new NudgeCandidate(NudgeRule.SILENCE, patient.familyId(), patient.patientId(), null, "silence:" + today(),
                NudgeUrgency.ROUTINE, vars, numbersOf(vars.values()), List.of(), null);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    public static Set<String> numbersOf(Iterable<String> strings) {
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
