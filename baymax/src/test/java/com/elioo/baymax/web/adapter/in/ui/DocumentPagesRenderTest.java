package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.outbound.domain.UrgencyReason;
import com.elioo.baymax.web.domain.GateState;
import com.elioo.baymax.web.domain.ResultView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the document page shows and, more importantly, what it never shows. The stored urgency on a DONE paper is
 * ResultSummaryRenderTest's (A12 replaced A10's interim banner and its two tests here); a NEEDS_RETAKE or FAILED paper's
 * page is NeedsRetakeRenderTest's (B5 replaced B4's interim retake line and the legacy page's tests here).
 */
class DocumentPagesRenderTest {

    private final WebUiTestKit kit = new WebUiTestKit();
    private final DocumentPages ui = kit.documents;
    private final UUID id = UUID.randomUUID();

    /**
     * Acceptance: given unverified items, each section says so where its items would be (B2: the values line, the
     * medicines count) and the items themselves never appear.
     */
    @Test
    void unverifiedCountIsShownAndNothingElseAboutThem() {
        DocumentView view = new DocumentView(id.toString(), "DONE", null, "prescription", "2026-09-01", null, 0.9, "m", 1,
                List.of(Map.of("name", "BP", "value", "130", "unit", "mmHg", "crop_key", "f/p/d/crop-v1.jpg")),
                List.of(), List.of(), null, Map.of("values", 1, "medicines", 2, "follow_up", 0, "total", 3));
        String html = ui.render(view, id);
        assertThat(html).contains(kit.copy.t(Lang.BN, "value.unverified"));
        assertThat(ResultFixtures.section(html, "medicines")).contains("২টি লাইন পড়া যায়নি");   // a count on a Bangla page is in Bangla digits (rule 13)
        assertThat(html).contains("130").contains("mmHg");
        assertThat(html).doesNotContain("unverified_values").doesNotContain("medicines\":");
    }

    /** Acceptance: images are served through an endpoint that signs on load, never as a signed URL in the page. */
    @Test
    void cropsPointAtTheReSigningEndpointNotAtStorage() {
        DocumentView view = new DocumentView(id.toString(), "DONE", null, "prescription", "2026-09-01", "Clinic", 0.9, "m", 1,
                List.of(), List.of(Map.of("name", "Tab. Napa", "dose_text", "500 mg", "frequency_text", "১+০+১", "crop_key", "f/p/d/crop-m1.jpg")),
                List.of(), null, null);
        String html = ui.render(view, id);
        assertThat(html).contains("/app/documents/" + id + "/image?key=f%2Fp%2Fd%2Fcrop-m1.jpg");
        assertThat(html).doesNotContain("X-Amz-").doesNotContain("amazonaws").doesNotContain("localhost:9000");
        assertThat(html).contains("১+০+১");   // verbatim, Bangla numerals untouched
    }

    /**
     * Safety constraint, on the page a DONE paper is served since A12 (the summary): the only interpretive text is the
     * stored urgency's fixed line (#urgency), on a NOW paper the s11 body that follows it (B3: the reason blocks and the
     * emergency action, every word of it fixed bundle copy with its arguments filled, or the paper's own quotes and value
     * rows), and Medioo's released words, labelled as such (#says). Take those blocks away and nothing interpretive is
     * left — no urgency, risk or advice in the header, the items or the chrome. The NOW body carries a reason of every
     * kind the rule raises plus a retired one, for a reader in Bangladesh and one abroad, so every block and both actions
     * are under the check (ResultNowRenderTest repeats the body's half in English and with nothing croppable).
     */
    @ParameterizedTest
    @EnumSource(value = Urgency.class, names = {"NOW", "THIS_WEEK"})
    void nothingInterpretiveIsRenderedForAFullDocumentButTheStoredLevelAndTheLabelledWording(Urgency level) {
        DocumentView view = new DocumentView(id.toString(), "DONE", null, "lab_report", "2026-09-01", null, 0.9, "m", 1,
                List.of(Map.of("name", "HbA1c", "value", "9.8", "unit", "%", "flag", "high", "crop_key", "k")),
                List.of(), List.of(Map.of("instruction", "আবার দেখাবেন", "due_date", "2026-08-01", "crop_key", "k3")),
                Map.of("diagnosis", List.of(Map.of("text", "T2DM", "crop_key", "k2"))), null);
        String wording = "রিপোর্টটি পেয়েছি।";
        List<String> reasons = new java.util.ArrayList<>();
        for (UrgencyReason r : UrgencyReason.values()) {
            reasons.add(r.of("hba1c", null));
        }
        reasons.add("retired_rule_2025:hba1c");
        List<java.util.regex.Pattern> templates = ResultFixtures.bundleTemplates(kit.copy, Lang.BN);
        for (var viewer : List.of(ResultFixtures.BD, ResultFixtures.ABROAD)) {
            ResultView r = new ResultView(view, ResultFixtures.MA, true, viewer, level, level == Urgency.NOW ? reasons : List.of("diagnosis_present"),
                    GateState.RELEASED, wording, 0, 2, ResultFixtures.NOW, ResultFixtures.NOW);
            String body = ui.summaryBody(Lang.BN, r);
            String banner = ResultFixtures.section(body, "urgency");
            String says = ResultFixtures.section(body, "says");
            assertThat(banner).contains(kit.copy.urgency(Lang.BN, level, false));
            assertThat(says).contains(kit.copy.t(Lang.BN, "result.says.label")).contains(wording);
            String now = ui.nowBody(Lang.BN, r);
            if (level == Urgency.NOW) {
                // the block removed below is on the page, and every word in it is fixed copy or the paper's own
                assertThat(now).isNotEmpty();
                assertThat(body).contains(now);
                assertThat(now).contains(viewer.bangladesh() ? "id=\"red-flags\"" : "id=\"remote-action\"");
                assertThat(ResultFixtures.textOutsidePaper(now)).as("the NOW body's text").isNotEmpty()
                        .allSatisfy(text -> assertThat(templates).as(text).anySatisfy(p -> assertThat(p.matcher(text).matches()).isTrue()));
            } else {
                assertThat(now).isEmpty();
            }
            String rest = Html.page(Lang.BN, "t", body.replace(banner, "").replace(now, "").replace(says, ""), 0, false, "", false).toLowerCase();
            for (String forbidden : List.of("urgent", "জরুরি", "advice", "পরামর্শ", "high risk", "ঝুঁকি", "normal range", "should",
                    kit.copy.urgency(Lang.BN, Urgency.NOW, false), kit.copy.urgency(Lang.BN, Urgency.THIS_WEEK, false), wording)) {
                assertThat(rest).as(forbidden).doesNotContain(forbidden);
            }
            assertThat(rest).contains("9.8");   // the value itself, as extracted, is shown
        }
    }

    @Test
    void everythingFromADocumentIsEscaped() {
        DocumentView view = new DocumentView(id.toString(), "DONE", null, "<script>", "2026-09-01", "<img src=x onerror=alert(1)>", 0.9, "m", 1,
                List.of(Map.of("name", "<b>x</b>", "value", "1", "unit", "\"", "crop_key", "k")), List.of(), List.of(), null, null);
        String html = ui.render(view, id);
        assertThat(html).doesNotContain("<script>").doesNotContain("<img src=x").contains("&lt;img src=x onerror=alert(1)&gt;").contains("&lt;b&gt;x&lt;/b&gt;");
    }

    /**
     * The doctor's own advice lines are shown as written, in the page's order, each beside its crop, under
     * "as written on the prescription" — never composed, never reordered (PO ruling 2026-09-18).
     */
    @Test
    void thePagesAdviceIsShownAsWrittenInOrderAndSectionKeysAreLabelledInBangla() {
        java.util.LinkedHashMap<String, Object> ctx = new java.util.LinkedHashMap<>();
        ctx.put("advice", List.of(Map.of("text", "Salt restriction", "crop_key", "k1"),
                Map.of("text", "Daily walk 30 min", "crop_key", "k2")));
        ctx.put("diagnosis", List.of(Map.of("text", "Hypertension", "crop_key", "k3")));
        DocumentView view = new DocumentView(id.toString(), "DONE", null, "prescription", "2026-09-01", null, 0.9, "m", 1,
                List.of(), List.of(), List.of(), ctx, null);
        String html = ui.render(view, id);
        assertThat(html).contains("প্রেসক্রিপশনে যা লেখা আছে").contains("Salt restriction").contains("Daily walk 30 min");
        assertThat(html.indexOf("Salt restriction")).isLessThan(html.indexOf("Daily walk 30 min"));
        assertThat(html).contains("image?key=k1").contains("image?key=k2");
        assertThat(html).doesNotContain(">advice<").doesNotContain(">diagnosis<").contains("রোগ নির্ণয়");
    }
}
