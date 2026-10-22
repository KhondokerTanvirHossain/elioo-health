package com.elioo.baymax.web.application.service;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.PatientAccess;
import com.elioo.baymax.outbound.domain.MarkerThreshold;
import com.elioo.baymax.outbound.domain.ValueVerdict;
import com.elioo.baymax.web.application.port.in.HealthUseCase;
import com.elioo.baymax.web.application.port.out.HealthReadPort;
import com.elioo.baymax.web.domain.LabSeries;
import com.elioo.baymax.web.domain.LatestPrescription;
import com.elioo.baymax.web.domain.PaperDisplay;
import com.elioo.baymax.web.domain.PrintedBand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The health screen's series, blood-pressure pairs and latest prescription (plan B6), over stored rows read after
 * visibility. The joins are decided here, once:
 * <ul>
 *   <li><b>A series</b> is {@code (patient, canonical_name, MarkerThreshold.foldUnit(unit))}. Two rows are one series only
 *       when all three are equal; nothing is paired by name alone and nothing consumes a first match, so an absolute
 *       count and a percentage of one cell type stay two series, and MICRO SIGN, GREEK MU and ASCII u are one unit. A row
 *       with no printed unit (null or blank once folded) is a series of its own: it never joins another unitless row nor
 *       a series with a unit, as no threshold ever matches it either.</li>
 *   <li><b>A blood-pressure pair</b> is a {@code bp_systolic} and a {@code bp_diastolic} on the same document and the same
 *       non-null OCR line ({@code source_line}), exactly one of each. Never by order, by document alone or by crop key:
 *       production gives every value its own crop, even two cut from one line. Anything else stays in its own series.</li>
 * </ul>
 * The kind each point is drawn as is {@link PaperDisplay#kindOf}, so a one-sided INSIDE is UNKNOWN here as on the paper.
 * Each point's band is {@link PrintedBand#of} its own row's printed range (PO ruling R3): a series joins readings, never
 * their ranges, so two labs that printed two ranges keep two, and a paper that printed none carries none.
 */
@Service
@RequiredArgsConstructor
public class HealthService implements HealthUseCase {

    static final String SYSTOLIC = "bp_systolic";
    static final String DIASTOLIC = "bp_diastolic";

    private final HealthRecordPort records;
    private final HealthReadPort health;

    @Override
    public Mono<HealthLabs> labs(UUID familyId, UUID patientId) {
        return visible(familyId, patientId)
                .flatMap(access -> health.labPoints(patientId).collectList())
                .map(HealthService::labsOf);
    }

    @Override
    public Mono<LatestPrescription> medicines(UUID familyId, UUID patientId) {
        return visible(familyId, patientId)
                .flatMap(access -> health.latestPrescription(patientId))
                .map(latest -> new LatestPrescription(latest.documentId(), latest.date(), latest.dateIsUploadDay(), latest.dateUncertain(),
                        latest.medicines().stream().map(HealthService::asWritten).toList(), latest.newerNotRead()));
    }

    private Mono<PatientAccess> visible(UUID familyId, UUID patientId) {
        return records.visiblePatient(familyId, patientId)
                .switchIfEmpty(Mono.error(BaymaxException.notFound("patient_not_found", "no patient with id " + patientId)));
    }

    /** The rows (newest first) as series and pairs. */
    static HealthLabs labsOf(List<Map<String, Object>> rows) {
        // 1. pairs: one systolic and one diastolic on one line of one document
        Map<String, List<Map<String, Object>>> byLine = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object line = row.get("source_line");
            if (line != null && isBp(row)) {
                byLine.computeIfAbsent(row.get("document_id") + "|" + line, k -> new ArrayList<>()).add(row);
            }
        }
        Set<Object> paired = new HashSet<>();
        List<LabSeries.BpPoint> pairs = new ArrayList<>();
        for (List<Map<String, Object>> onLine : byLine.values()) {
            List<Map<String, Object>> systolic = onLine.stream().filter(r -> SYSTOLIC.equals(r.get("canonical_name"))).toList();
            List<Map<String, Object>> diastolic = onLine.stream().filter(r -> DIASTOLIC.equals(r.get("canonical_name"))).toList();
            if (systolic.size() != 1 || diastolic.size() != 1) {
                continue;
            }
            LabSeries.Point s = point(systolic.get(0));
            LabSeries.Point d = point(diastolic.get(0));
            pairs.add(new LabSeries.BpPoint(s.documentId(), s.date(), s.dateIsUploadDay(), s.dateUncertain(), s, d,
                    String.valueOf(systolic.get(0).get("source_line"))));
            paired.add(systolic.get(0).get("id"));
            paired.add(diastolic.get(0).get("id"));
        }

        // 2. series: every other row, keyed by canonical name and folded unit; a row with no printed unit is its own series,
        //    keyed by its observation id, because "no unit" is not a unit: creatinine read without one on two papers may be
        //    1.2 mg/dL and 106 µmol/L, and joined by name alone they would draw a 90x rise that never happened
        Map<List<String>, List<Map<String, Object>>> byKey = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            if (paired.contains(row.get("id"))) {
                continue;
            }
            String canonical = String.valueOf(row.get("canonical_name"));
            String fold = MarkerThreshold.foldUnit((String) row.get("unit"));
            List<String> key = fold.isEmpty() ? List.of(canonical, fold, "observation:" + row.get("id")) : List.of(canonical, fold);
            byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        List<LabSeries> series = new ArrayList<>();
        byKey.forEach((key, members) -> {
            Map<String, Object> newest = members.get(0);
            series.add(new LabSeries(key.get(0), key.get(1), (String) newest.get("name"), (String) newest.get("unit"),
                    members.stream().map(HealthService::point).toList()));
        });
        // by the newest point's date, newest first; a stable sort keeps the read's own order (upload, then id) within a day
        series.sort(Comparator.comparing((LabSeries s) -> s.points().get(0).date(), Comparator.nullsLast(Comparator.<LocalDate>reverseOrder())));
        return new HealthLabs(List.copyOf(series), new LabSeries.BpSeries(List.copyOf(pairs)));
    }

    private static boolean isBp(Map<String, Object> row) {
        Object canonical = row.get("canonical_name");
        return SYSTOLIC.equals(canonical) || DIASTOLIC.equals(canonical);
    }

    private static LabSeries.Point point(Map<String, Object> row) {
        ValueVerdict.Kind kind = PaperDisplay.kindOf(row);
        return new LabSeries.Point((UUID) row.get("document_id"), (UUID) row.get("id"), (String) row.get("value"),
                (LocalDate) row.get("date"), Boolean.TRUE.equals(row.get("date_is_fallback")), Boolean.TRUE.equals(row.get("date_uncertain")),
                kind, PaperDisplay.drawnSide(kind, row), (String) row.get("crop_key"),
                PrintedBand.of(row.get("ref_low"), row.get("ref_high")));
    }

    /** Only the fields a medicine line was written with, and its crop: never action, purpose, status or anything derived. */
    private static Map<String, Object> asWritten(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String field : PaperDisplay.MEDICINE_FIELDS) {
            Object written = row.get(field);
            if (written != null && !String.valueOf(written).isBlank()) {
                m.put(field, String.valueOf(written));
            }
        }
        Object crop = row.get("crop_key");
        if (crop != null) {
            m.put("crop_key", String.valueOf(crop));
        }
        return m;
    }
}
