package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.web.domain.GateState;
import com.elioo.baymax.web.domain.ResultView;
import com.elioo.baymax.web.domain.ViewerLocale;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Result-page fixtures. The only test code that calls DocumentView's constructor, so B1/B4 shape changes land here once. */
final class ResultFixtures {

    static final Instant NOW = Instant.parse("2026-10-20T04:00:00Z");
    static final UUID DOC = WebUiTestKit.DOC_ID;
    static final PatientProfile MA = new PatientProfile(WebUiTestKit.MA_ID, WebUiTestKit.FAMILY, "Ma", 64,
            PatientProfile.Sex.FEMALE, List.of(), NOW, NOW);
    static final ViewerLocale BD = new ViewerLocale(true);
    static final ViewerLocale ABROAD = new ViewerLocale(false);

    private ResultFixtures() {
    }

    static Map<String, Object> value(String name, String value, String unit, String low, String high, String verdict) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", UUID.nameUUIDFromBytes((name + value).getBytes(StandardCharsets.UTF_8)));
        m.put("name", name);
        m.put("canonical_name", name.toLowerCase(Locale.ROOT));
        m.put("value", value);
        if (unit != null) { m.put("unit", unit); }
        if (low != null) { m.put("ref_low", low); }
        if (high != null) { m.put("ref_high", high); }
        if (verdict != null) { m.put("verdict", verdict); }
        m.put("crop_key", "f/p/d/crop-" + name.replaceAll("[^A-Za-z0-9]", "") + ".jpg");
        return m;
    }

    static DocumentView lab(List<Map<String, Object>> values) {
        return new DocumentView(DOC.toString(), "DONE", null, "lab_report", "2026-10-12", "Diagnostic Centre, Mirpur", 0.92,
                "m", 2, values, List.of(), List.of(), Map.of(), null);
    }

    static DocumentView prescription(List<Map<String, Object>> medicines, Map<String, Object> clinicalContext, Map<String, Object> unverified) {
        return new DocumentView(DOC.toString(), "DONE", null, "prescription", "2026-10-12", "Demo Chamber", 0.9,
                "m", 1, List.of(), medicines, List.of(), clinicalContext, unverified);
    }

    static DocumentView pending(String status) {
        return new DocumentView(DOC.toString(), status, null, null, null, null, null, null, 1, null, null, null, null, null);
    }

    static ResultView result(DocumentView view, Urgency urgency, List<String> reasons, GateState gate, String wording, ViewerLocale viewer) {
        return new ResultView(view, MA, true, viewer, "DONE".equals(view.status()) ? urgency : null, reasons, gate, wording,
                0, 2, NOW.minusSeconds(60), NOW);
    }

    /** The element with this id, through its closing tag: assertions read one block, never the whole page (UUIDs). */
    static String section(String html, String id) {
        int at = html.indexOf("id=\"" + id + "\"");
        assertThat(at).as("block #" + id).isNotNegative();
        int open = html.lastIndexOf('<', at);
        String tag = html.substring(open + 1, html.indexOf(' ', open));
        return html.substring(open, html.indexOf("</" + tag + ">", at) + tag.length() + 3);
    }
}
