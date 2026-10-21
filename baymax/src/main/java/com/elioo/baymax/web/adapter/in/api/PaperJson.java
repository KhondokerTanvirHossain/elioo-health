package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.common.time.PatientCalendar;
import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.outbound.domain.UrgencyReason;
import com.elioo.baymax.outbound.domain.ValueVerdict;
import com.elioo.baymax.storage.domain.ImageDimensions;
import com.elioo.baymax.web.adapter.in.ImageRefs;
import com.elioo.baymax.web.domain.GateState;
import com.elioo.baymax.web.domain.PaperDisplay;
import com.elioo.baymax.web.domain.ReadingView;
import com.elioo.baymax.web.domain.ResultView;
import com.elioo.baymax.web.domain.ViewerLocale;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * The paper's display model as {@code /app/api/documents/{id}} sends it (rebuild plan A5, B2): every safety rule decided
 * here, in Java, from the same {@link PaperDisplay} the HTML pages read, and sent as a field React renders and never
 * derives. Snake case. A key that does not apply is absent, never null: {@code now} (only on a NOW paper),
 * {@code wording} (only when the gate is RELEASED), {@code review_hours} (only PENDING), {@code retake} (only
 * NEEDS_RETAKE). The paper's items ({@code values}, {@code medicines}, {@code follow_up}, {@code doctor}) are sent only
 * for DONE, as the page draws them; a rejected read sends them empty. Medicines carry only the fields as written
 * ({@link PaperDisplay#MEDICINE_FIELDS}). There is no confidence, no model, no developer reason
 * prose, no reason code and no {@code tel:} anywhere in the tree: a call is a boolean, a line is a copy key.
 *
 * <p>Every {@code crop} is {@code {src, width, height}} (D4b): the size its ledger row stores, so React can lay the crop
 * out before it loads; {@code width} and {@code height} are null together when the row has none, never zero.</p>
 */
public final class PaperJson {

    private PaperJson() {
    }

    /**
     * A finished paper: DONE (the result), NEEDS_RETAKE (the retake) or FAILED. {@code retakeKey} maps a stored code to its
     * copy key; {@code sizes} is the paper's stored image sizes by storage key (a crop whose key is absent has no size).
     */
    public static Map<String, Object> of(ResultView r, Function<String, String> retakeKey, Map<String, ImageDimensions> sizes) {
        DocumentView v = r.document();
        UUID documentId = UUID.fromString(v.documentId());
        Function<Map<String, Object>, Map<String, Object>> crop = row -> crop(documentId, String.valueOf(row.get("crop_key")), sizes);
        Map<String, Object> m = head(v, r.patient(), r.owner(), r.viewer(), r.receivedAt());
        m.put("document_type", v.documentType());
        dates(m, v.documentDate(), r.receivedAt());
        m.put("facility", blank(v.facility()) ? null : v.facility());
        Urgency level = r.urgency();
        m.put("urgency", level == null ? null : level.dbValue());
        m.put("banner", level == null || level == Urgency.ROUTINE ? null
                : ordered("level", level.dbValue(), "line_key", "urgency." + level.dbValue()));
        m.put("filed", level == Urgency.ROUTINE);
        m.put("reasons", level != Urgency.NOW ? List.of()
                : PaperDisplay.drawnOrder(r.urgencyReasons()).stream().map(o -> o.map(UrgencyReason::prefix).orElse("other")).toList());
        if (level == Urgency.NOW) {
            boolean bangladesh = r.viewer().bangladesh();
            Map<String, Object> now = new LinkedHashMap<>();
            now.put("notdoctor_key", "now.notdoctor");
            now.put("flags_go_key", bangladesh ? "now.flags.go" : "now.remote.flags.go");
            now.put("bring_key", bangladesh ? "now.bring" : "now.bring.remote");
            now.put("call_999", bangladesh);          // F7: tel:999 only for a Bangladeshi phone
            now.put("remote_share", !bangladesh);
            m.put("now", now);
        }
        m.put("gate", r.gate().name());
        if (r.gate() == GateState.RELEASED) {
            m.put("wording", r.wording());
        }
        if (r.gate() == GateState.PENDING) {
            m.put("review_hours", r.reviewHours());
        }
        List<Map<String, Object>> values = v.values() == null ? List.of() : v.values();
        // the HTML counts line's own numbers, over the same list it counts (shown from DONE, on a paper with values)
        long outside = PaperDisplay.outsideCount(values);
        m.put("counts", !r.done() || values.isEmpty() ? null
                : ordered("values", values.size(), "outside", outside, "line_key", outside == 0 ? "result.counts.none" : "result.counts"));
        // the paper's items only for DONE, as summaryBody draws them: a read the system rejected (NEEDS_RETAKE, FAILED)
        // shows none of whatever rows it holds, so the JSON never sends a chip or a pin the page withholds
        boolean shown = r.done();
        Set<ValueVerdict.Kind> above = PaperDisplay.shownAbove(level, r.urgencyReasons());
        m.put("values", !shown ? List.of() : values.stream().filter(PaperJson::hasCrop).map(row -> value(row, above, crop)).toList());
        m.put("medicines", !shown ? List.of() : rows(v.medicines()).stream().filter(PaperJson::hasCrop).map(row -> medicine(row, crop)).toList());
        m.put("follow_up", !shown ? List.of() : rows(v.followUp()).stream().filter(PaperJson::hasCrop).map(row -> followUp(row, crop)).toList());
        m.put("doctor", !shown ? Map.of() : doctor(v.clinicalContext(), crop));
        m.put("unverified", v.unverified());     // the stored V6 counts, verbatim
        if ("NEEDS_RETAKE".equals(v.status())) {
            m.put("retake", ordered("reason_key", retakeKey.apply(v.reasonCode()), "ordinal", r.retakeOrdinal()));
        }
        return m;
    }

    /** A paper still being read (RECEIVED, PROCESSING): whose it is, when it came, its pages. Nothing about a result. */
    public static Map<String, Object> reading(ReadingView r) {
        return head(r.document(), r.patient(), r.owner(), r.viewer(), r.receivedAt());
    }

    /** Adds the patient's name forms, in the viewer's language (UiCopy.poss and obj), to a body built here. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> withNames(Map<String, Object> body, String possessive, String objective) {
        Map<String, Object> patient = (Map<String, Object>) body.get("patient");
        patient.put("name_possessive", possessive);
        patient.put("name_objective", objective);
        return body;
    }

    private static Map<String, Object> head(DocumentView v, PatientProfile patient, boolean owner, ViewerLocale viewer, Instant receivedAt) {
        UUID documentId = UUID.fromString(v.documentId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.documentId());
        m.put("status", v.status());
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", patient.id().toString());
        p.put("name", patient.name());
        p.put("age", patient.age());
        m.put("patient", p);
        m.put("owner", owner);
        m.put("viewer", ordered("bangladesh", viewer.bangladesh()));
        m.put("received_at", receivedAt == null ? null : receivedAt.toString());
        int pages = v.pageCount() == null ? 1 : Math.max(1, v.pageCount());
        m.put("page_count", pages);
        List<Map<String, Object>> list = new ArrayList<>();
        for (int n = 1; n <= pages; n++) {
            list.add(ordered("n", n, "src", ImageRefs.page(documentId, n)));
        }
        m.put("pages", list);
        return m;
    }

    /**
     * DocumentPages.paperDateText's rule: a printed date is shown as printed (ISO or not); only when nothing was printed is
     * the paper dated by its upload day, on the patient's calendar (Dhaka), and marked so.
     */
    private static void dates(Map<String, Object> m, String printed, Instant receivedAt) {
        if (!blank(printed)) {
            m.put("printed_date", printed);
            m.put("date", iso(printed) ? printed : null);
            m.put("date_is_upload_day", false);
        } else {
            m.put("printed_date", null);
            m.put("date", receivedAt == null ? null : PatientCalendar.dayOf(receivedAt).toString());
            m.put("date_is_upload_day", true);
        }
    }

    private static boolean iso(String date) {
        try {
            LocalDate.parse(date);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static Map<String, Object> value(Map<String, Object> row, Set<ValueVerdict.Kind> above,
                                             Function<Map<String, Object>, Map<String, Object>> crop) {
        ValueVerdict.Kind kind = PaperDisplay.kindOf(row);
        String side = PaperDisplay.drawnSide(kind, row);
        OptionalDouble pin = PaperDisplay.pin(kind, row, side);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.get("id") == null ? null : String.valueOf(row.get("id")));
        m.put("name", text(row.get("name")));
        m.put("value", text(row.get("value")));
        m.put("unit", text(row.get("unit")));
        m.put("ref_low", text(row.get("ref_low")));
        m.put("ref_high", text(row.get("ref_high")));
        m.put("display_kind", kind.name().toLowerCase(Locale.ROOT));
        m.put("side", side);
        m.put("chip_key", PaperDisplay.chipKey(kind, side));
        // as the bar draws it (one decimal), so the page and the JSON place the pin at the same point
        m.put("pin", pin.isPresent() ? Math.round(pin.getAsDouble() * 10) / 10.0 : null);
        m.put("in_now_block", above.contains(kind));
        m.put("crop", crop.apply(row));
        return m;
    }

    private static Map<String, Object> medicine(Map<String, Object> row, Function<Map<String, Object>, Map<String, Object>> crop) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String field : PaperDisplay.MEDICINE_FIELDS) {
            Object written = row.get(field);
            if (written != null && !String.valueOf(written).isBlank()) {
                m.put(field, String.valueOf(written));
            }
        }
        m.put("crop", crop.apply(row));
        return m;
    }

    private static Map<String, Object> followUp(Map<String, Object> row, Function<Map<String, Object>, Map<String, Object>> crop) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instruction", text(row.get("instruction")));
        Object due = row.get("due_date");
        m.put("due_date", due == null || String.valueOf(due).isBlank() ? null : String.valueOf(due));
        m.put("crop", crop.apply(row));
        return m;
    }

    /** Every clinical-context section but the referral, each line as written beside its crop; a line with no crop is left out. */
    private static Map<String, Object> doctor(Map<String, Object> context, Function<Map<String, Object>, Map<String, Object>> crop) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (context == null) {
            return out;
        }
        for (Map.Entry<String, Object> section : context.entrySet()) {
            if (section.getKey() == null || "referral".equals(section.getKey()) || !(section.getValue() instanceof List<?> lines)) {
                continue;
            }
            List<Map<String, Object>> quoted = new ArrayList<>();
            for (Object line : lines) {
                if (line instanceof Map<?, ?> l && l.get("text") != null && l.get("crop_key") != null && !String.valueOf(l.get("crop_key")).isBlank()) {
                    quoted.add(ordered("text", String.valueOf(l.get("text")), "crop", crop.apply(Map.of("crop_key", l.get("crop_key")))));
                }
            }
            if (!quoted.isEmpty()) {
                out.put(section.getKey(), quoted);
            }
        }
        return out;
    }

    /** The crop's href and its stored size, both null when its ledger row has none (D4b). */
    private static Map<String, Object> crop(UUID documentId, String cropKey, Map<String, ImageDimensions> sizes) {
        ImageDimensions size = sizes.get(cropKey);
        return ordered("src", ImageRefs.crop(documentId, cropKey),
                "width", size == null ? null : size.width(), "height", size == null ? null : size.height());
    }

    /** No row without its crop (DR-12); Postgres cannot hold one, so this skip is defensive and changes no count. */
    private static boolean hasCrop(Map<String, Object> row) {
        Object key = row.get("crop_key");
        return key != null && !String.valueOf(key).isBlank();
    }

    private static List<Map<String, Object>> rows(List<Map<String, Object>> rows) {
        return rows == null ? List.of() : rows;
    }

    private static String text(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static Map<String, Object> ordered(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((String) pairs[i], pairs[i + 1]);
        }
        return m;
    }
}
