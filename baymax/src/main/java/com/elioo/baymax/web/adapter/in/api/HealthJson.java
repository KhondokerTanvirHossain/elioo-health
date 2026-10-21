package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.outbound.domain.ValueVerdict;
import com.elioo.baymax.web.adapter.in.ImageRefs;
import com.elioo.baymax.web.application.port.in.HealthUseCase;
import com.elioo.baymax.web.domain.LabSeries;
import com.elioo.baymax.web.domain.LatestPrescription;
import com.elioo.baymax.web.domain.PaperDisplay;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The health screen's JSON (plan B6), in {@link PaperJson}'s conventions: snake case, ISO dates with
 * {@code date_is_upload_day} beside them, {@code date_uncertain} (D2's {@code timeline_date_uncertain}, never true on an
 * upload day), a chip as its copy key, a crop as {@code {"src"}} through {@link ImageRefs}. The join keys
 * ({@code unit_fold}, {@code source_line}) are not sent: they decide what is one series or one pair, and are not display
 * values. A medicine carries only the fields it was written with ({@link PaperDisplay#MEDICINE_FIELDS}) and its crop:
 * never an action, a purpose, a status or anything derived, and never the word "current" (decision 9).
 */
public final class HealthJson {

    /** The heading the medicines list is shown under: as written on the latest prescription, with its date (Q3). */
    public static final String MEDICINES_HEADING_KEY = "health.medicines.as_written";

    private HealthJson() {
    }

    /** {@code GET /app/api/patients/{id}/labs}. */
    public static Map<String, Object> labs(HealthUseCase.HealthLabs labs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("series", labs.series().stream().map(HealthJson::series).toList());
        m.put("bp", ordered("points", labs.bp().points().stream().map(HealthJson::pair).toList()));
        return m;
    }

    /** {@code GET /app/api/patients/{id}/medicines}: the latest prescription's medicines as written, or none yet. */
    public static Map<String, Object> medicines(LatestPrescription latest) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("heading_key", MEDICINES_HEADING_KEY);
        m.put("document_id", latest.found() ? latest.documentId().toString() : null);
        if (latest.found()) {
            m.put("date", latest.date().toString());
            m.put("date_is_upload_day", latest.dateIsUploadDay());
            m.put("date_uncertain", latest.dateUncertain());
        }
        m.put("medicines", !latest.found() ? List.of()
                : latest.medicines().stream().map(row -> medicine(latest.documentId(), row)).toList());
        m.put("newer_prescription_not_read", latest.newerNotRead().isPresent());
        m.put("newer_document_id", latest.newerNotRead().map(UUID::toString).orElse(null));
        return m;
    }

    private static Map<String, Object> series(LabSeries s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("canonical_name", s.canonicalName());
        m.put("name", s.name());
        m.put("unit", s.unit());
        m.put("points", s.points().stream().map(HealthJson::point).toList());
        return m;
    }

    private static Map<String, Object> pair(LabSeries.BpPoint p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("document_id", p.documentId().toString());
        m.put("date", p.date().toString());
        m.put("date_is_upload_day", p.dateIsUploadDay());
        m.put("date_uncertain", p.dateUncertain());
        m.put("systolic", point(p.systolic()));
        m.put("diastolic", point(p.diastolic()));
        return m;
    }

    private static Map<String, Object> point(LabSeries.Point p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("document_id", p.documentId().toString());
        m.put("observation_id", p.observationId().toString());
        m.put("value", p.value());
        m.put("date", p.date().toString());
        m.put("date_is_upload_day", p.dateIsUploadDay());
        m.put("date_uncertain", p.dateUncertain());
        ValueVerdict.Kind kind = p.kind();
        m.put("kind", kind.name().toLowerCase(Locale.ROOT));
        m.put("side", p.side());
        m.put("chip_key", PaperDisplay.chipKey(kind, p.side()));
        m.put("crop", ordered("src", ImageRefs.crop(p.documentId(), p.cropKey())));
        return m;
    }

    /**
     * A row {@code HealthService} has already narrowed to what the line was written with plus {@code crop_key}: the one
     * copy of the as-written rule is there. Here the crop key becomes its crop. A row without one cannot exist
     * ({@code medication_event.crop_key} is NOT NULL, DR-12) and is refused loudly, never silently dropped.
     */
    private static Map<String, Object> medicine(UUID documentId, Map<String, Object> row) {
        Object cropKey = row.get("crop_key");
        if (cropKey == null || String.valueOf(cropKey).isBlank()) {
            throw new IllegalStateException("a medicine line without its crop on document " + documentId);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        row.forEach((key, written) -> {
            if (!key.equals("crop_key")) {
                m.put(key, written);
            }
        });
        m.put("crop", ordered("src", ImageRefs.crop(documentId, String.valueOf(cropKey))));
        return m;
    }

    private static Map<String, Object> ordered(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((String) pairs[i], pairs[i + 1]);
        }
        return m;
    }
}
