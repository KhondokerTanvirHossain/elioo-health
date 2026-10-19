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

    /**
     * A stored OUTSIDE or CRITICAL with its side, as V19 requires (observation_verdict_side_matches_kind): the shape the
     * producer writes. The six-argument form carries no side: right for INSIDE, UNKNOWN and NULL, and for OUTSIDE or
     * CRITICAL only to test a row the database cannot hold.
     */
    static Map<String, Object> value(String name, String value, String unit, String low, String high, String verdict, String side) {
        Map<String, Object> m = value(name, value, unit, low, high, verdict);
        m.put("verdict_side", side);
        return m;
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
        return lab("Diagnostic Centre, Mirpur", values);
    }

    static DocumentView lab(String facility, List<Map<String, Object>> values) {
        return lab(facility, values, List.of(), Map.of());
    }

    /** A two-page lab report that also carries the doctor's follow-up and clinical-context lines as stored. */
    static DocumentView lab(String facility, List<Map<String, Object>> values, List<Map<String, Object>> followUp,
                            Map<String, Object> clinicalContext) {
        return new DocumentView(DOC.toString(), "DONE", null, "lab_report", "2026-10-12", facility, 0.92,
                "m", 2, values, List.of(), followUp, clinicalContext, null);
    }

    /** B6: a two-page DONE paper carrying every kind of fact: values, medicines, follow-ups and the doctor's lines. */
    static DocumentView paper(List<Map<String, Object>> values, List<Map<String, Object>> medicines, List<Map<String, Object>> followUp,
                              Map<String, Object> clinicalContext) {
        return new DocumentView(DOC.toString(), "DONE", null, "lab_report", "2026-10-12", "Shapla Diagnostics", 0.9,
                "m", 2, values, medicines, followUp, clinicalContext, null);
    }

    /** B6: a one-page DONE lab report that printed no date (t9-style), with or without a facility. */
    static DocumentView undated(String facility, List<Map<String, Object>> values) {
        return new DocumentView(DOC.toString(), "DONE", null, "lab_report", null, facility, 0.9,
                "m", 1, values, List.of(), List.of(), Map.of(), null);
    }

    static DocumentView prescription(List<Map<String, Object>> medicines, Map<String, Object> clinicalContext, Map<String, Object> unverified) {
        return new DocumentView(DOC.toString(), "DONE", null, "prescription", "2026-10-12", "Demo Chamber", 0.9,
                "m", 1, List.of(), medicines, List.of(), clinicalContext, unverified);
    }

    static DocumentView pending(String status) {
        return new DocumentView(DOC.toString(), status, null, null, null, null, null, null, 1, null, null, null, null, null);
    }

    static ResultView result(DocumentView view, Urgency urgency, List<String> reasons, GateState gate, String wording, ViewerLocale viewer) {
        return result(view, MA, urgency, reasons, gate, wording, viewer);
    }

    static ResultView result(DocumentView view, PatientProfile patient, Urgency urgency, List<String> reasons, GateState gate,
                             String wording, ViewerLocale viewer) {
        return new ResultView(view, patient, true, viewer, "DONE".equals(view.status()) ? urgency : null, reasons, gate, wording,
                0, 2, NOW.minusSeconds(60), NOW);
    }

    /**
     * B5: a one-page NEEDS_RETAKE paper of Ma's with its reason code, as intake views it (no prose: a view has none),
     * the {@code ordinal}-th retake in a row; {@code owner} false is a view-only share member.
     */
    static ResultView retake(String code, int ordinal, boolean owner) {
        DocumentView view = new DocumentView(DOC.toString(), "NEEDS_RETAKE", code, null, null, null, null, null, 1,
                null, null, null, null, null);
        return new ResultView(view, MA, owner, BD, null, List.of(), GateState.NONE, null, ordinal, 2, NOW.minusSeconds(60), NOW);
    }

    /** B5: a one-page FAILED paper of Ma's, seen by its owner. */
    static ResultView failed() {
        DocumentView view = new DocumentView(DOC.toString(), "FAILED", null, null, null, null, null, null, 1,
                null, null, null, null, null);
        return new ResultView(view, MA, true, BD, null, List.of(), GateState.NONE, null, 0, 2, NOW.minusSeconds(60), NOW);
    }

    /**
     * Each text node of the markup once the paper's own blocks are gone: its quotes ({@code <figure class="paper">}) and
     * its value rows ({@code <a class="vrow …">}), whose words are the paper's, drawn by B2's audited row code.
     */
    static List<String> textOutsidePaper(String html) {
        String noPaper = html.replaceAll("(?s)<figure class=\"paper\">.*?</figure>", "")
                .replaceAll("(?s)<a class=\"vrow[^\"]*\"[^>]*>.*?</a>", "");
        return java.util.Arrays.stream(noPaper.split("<[^>]*>")).map(String::strip).filter(t -> !t.isEmpty()).toList();
    }

    /**
     * Every bundle string of {@code lang} as a page shows it (escaped), each {@code {n}} free to hold any argument; a
     * template with no letter of its own outside its placeholders ("{0} · {1}") is left out, since it would match any
     * sentence. A text node is bundle copy only when it is the whole of one of these.
     */
    static List<java.util.regex.Pattern> bundleTemplates(UiCopy copy, Lang lang) {
        List<java.util.regex.Pattern> out = new java.util.ArrayList<>();
        for (String key : copy.keys(lang)) {
            String template = copy.t(lang, key);
            if (!template.replaceAll("\\{\\d+}", "").matches("(?s).*\\p{L}.*")) {
                continue;
            }
            StringBuilder regex = new StringBuilder();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{\\d+}").matcher(template);
            int at = 0;
            while (m.find()) {
                regex.append(java.util.regex.Pattern.quote(template.substring(at, m.start()))).append(".+?");
                at = m.end();
            }
            regex.append(java.util.regex.Pattern.quote(template.substring(at)));
            out.add(java.util.regex.Pattern.compile(regex.toString(), java.util.regex.Pattern.DOTALL));
        }
        return out;
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
