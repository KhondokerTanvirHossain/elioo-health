package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.testsupport.PaperFixtures;
import com.elioo.baymax.web.domain.GateState;
import com.elioo.baymax.web.domain.ResultView;
import com.elioo.baymax.web.domain.ViewerLocale;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Result-page fixtures for the HTML render tests. The rows and views are {@link PaperFixtures}'s, the one source the
 * {@code /app/api} contract tests read too, so the page and the JSON are drawn from the same paper; this class keeps the
 * short names the render tests use and the page-reading helpers below.
 */
final class ResultFixtures {

    static final Instant NOW = PaperFixtures.NOW;
    static final UUID DOC = PaperFixtures.DOC;
    static final PatientProfile MA = PaperFixtures.MA;
    static final ViewerLocale BD = PaperFixtures.BD;
    static final ViewerLocale ABROAD = PaperFixtures.ABROAD;

    private ResultFixtures() {
    }

    static Map<String, Object> value(String name, String value, String unit, String low, String high, String verdict, String side) {
        return PaperFixtures.value(name, value, unit, low, high, verdict, side);
    }

    static Map<String, Object> value(String name, String value, String unit, String low, String high, String verdict) {
        return PaperFixtures.value(name, value, unit, low, high, verdict);
    }

    static DocumentView lab(List<Map<String, Object>> values) {
        return PaperFixtures.lab(values);
    }

    static DocumentView lab(String facility, List<Map<String, Object>> values) {
        return PaperFixtures.lab(facility, values);
    }

    static DocumentView lab(String facility, List<Map<String, Object>> values, List<Map<String, Object>> followUp,
                            Map<String, Object> clinicalContext) {
        return PaperFixtures.lab(facility, values, followUp, clinicalContext);
    }

    static DocumentView paper(List<Map<String, Object>> values, List<Map<String, Object>> medicines, List<Map<String, Object>> followUp,
                              Map<String, Object> clinicalContext) {
        return PaperFixtures.paper(values, medicines, followUp, clinicalContext);
    }

    static DocumentView undated(String facility, List<Map<String, Object>> values) {
        return PaperFixtures.undated(facility, values);
    }

    static DocumentView prescription(List<Map<String, Object>> medicines, Map<String, Object> clinicalContext, Map<String, Object> unverified) {
        return PaperFixtures.prescription(medicines, clinicalContext, unverified);
    }

    static DocumentView pending(String status) {
        return PaperFixtures.pending(status);
    }

    static ResultView result(DocumentView view, Urgency urgency, List<String> reasons, GateState gate, String wording, ViewerLocale viewer) {
        return PaperFixtures.result(view, urgency, reasons, gate, wording, viewer);
    }

    static ResultView result(DocumentView view, PatientProfile patient, Urgency urgency, List<String> reasons, GateState gate,
                             String wording, ViewerLocale viewer) {
        return PaperFixtures.result(view, patient, urgency, reasons, gate, wording, viewer);
    }

    static ResultView retake(String code, int ordinal, boolean owner) {
        return PaperFixtures.retake(code, ordinal, owner);
    }

    static ResultView failed() {
        return PaperFixtures.failed();
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
