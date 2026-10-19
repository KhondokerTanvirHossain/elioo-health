package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.web.domain.GateState;
import com.elioo.baymax.web.domain.ResultView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the document page shows and, more importantly, what it never shows. The stored urgency on a DONE paper is
 * ResultSummaryRenderTest's (A12 replaced A10's interim banner and its two tests here).
 */
class DocumentPagesRenderTest {

    private final WebUiTestKit kit = new WebUiTestKit();
    private final DocumentPages ui = kit.documents;
    private final UUID id = UUID.randomUUID();

    /** Acceptance: given unverified items, the count is shown and the items themselves never appear. */
    @Test
    void unverifiedCountIsShownAndNothingElseAboutThem() {
        DocumentView view = new DocumentView(id.toString(), "DONE", null, "prescription", "2026-09-01", null, 0.9, "m", 1,
                List.of(Map.of("name", "BP", "value", "130", "unit", "mmHg", "crop_key", "f/p/d/crop-v1.jpg")),
                List.of(), List.of(), null, Map.of("values", 1, "medicines", 2, "follow_up", 0, "total", 3));
        String html = ui.render(view, id);
        assertThat(html).contains("৩ টি অংশ মূল ছবিতে খুঁজে পাওয়া যায়নি");   // a count on a Bangla page is in Bangla digits (rule 13)
        assertThat(html).contains("130").contains("mmHg");
        assertThat(html).doesNotContain("unverified_values").doesNotContain("medicines\":");
    }

    /** Acceptance: given a NEEDS_RETAKE document, no extracted values are rendered anywhere. */
    @Test
    void aRetakeRendersTheReasonAndThePageImageOnly() {
        DocumentView view = new DocumentView(id.toString(), "NEEDS_RETAKE", "the document was not read confidently enough (0.75)",
                null, null, null, null, null, 1, null, null, null, null, null);
        String html = ui.render(view, id);
        assertThat(html).contains("আবার ছবি তুলুন").contains("not read confidently").contains("/documents/" + id + "/page/1");
        assertThat(html).doesNotContain("<h2>মান</h2>").doesNotContain("<h2>ওষুধ</h2>").doesNotContain("crop-");
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
     * stored urgency's fixed line (#urgency) and Medioo's released words, labelled as such (#says). Take those two
     * blocks away and nothing interpretive is left — no urgency, risk or advice in the header, the items or the chrome.
     */
    @ParameterizedTest
    @EnumSource(value = Urgency.class, names = {"NOW", "THIS_WEEK"})
    void nothingInterpretiveIsRenderedForAFullDocumentButTheStoredLevelAndTheLabelledWording(Urgency level) {
        DocumentView view = new DocumentView(id.toString(), "DONE", null, "lab_report", "2026-09-01", null, 0.9, "m", 1,
                List.of(Map.of("name", "HbA1c", "value", "9.8", "unit", "%", "flag", "high", "crop_key", "k")),
                List.of(), List.of(), Map.of("diagnosis", List.of(Map.of("text", "T2DM", "crop_key", "k2"))), null);
        String wording = "রিপোর্টটি পেয়েছি।";
        String body = ui.summaryBody(Lang.BN, new ResultView(view, ResultFixtures.MA, true, ResultFixtures.BD, level,
                List.of("diagnosis_present"), GateState.RELEASED, wording, 0, 2, ResultFixtures.NOW, ResultFixtures.NOW));
        String banner = ResultFixtures.section(body, "urgency");
        String says = ResultFixtures.section(body, "says");
        assertThat(banner).contains(kit.copy.urgency(Lang.BN, level, false));
        assertThat(says).contains(kit.copy.t(Lang.BN, "result.says.label")).contains(wording);
        String rest = Html.page(Lang.BN, "t", body.replace(banner, "").replace(says, ""), 0, false, "", false).toLowerCase();
        for (String forbidden : List.of("urgent", "জরুরি", "advice", "পরামর্শ", "high risk", "ঝুঁকি", "normal range", "should",
                kit.copy.urgency(Lang.BN, Urgency.NOW, false), kit.copy.urgency(Lang.BN, Urgency.THIS_WEEK, false), wording)) {
            assertThat(rest).as(forbidden).doesNotContain(forbidden);
        }
        assertThat(rest).contains("9.8");   // the value itself, as extracted, is shown
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

    /**
     * The legacy page serves only NEEDS_RETAKE and FAILED since A12; on it the released message (a RETAKE reply, say) is
     * the only interpretive text, shown verbatim, and only when there is one.
     */
    @ParameterizedTest
    @ValueSource(strings = {"NEEDS_RETAKE", "FAILED"})
    void theReleasedMessageIsShownVerbatimAndOnlyWhenPresent(String status) {
        DocumentView view = new DocumentView(id.toString(), status, "the document was not read confidently enough (0.75)",
                null, null, null, null, null, 1, null, null, null, null, null);
        String with = ui.render(Lang.BN, view, id, java.util.Optional.of("ছবিটি স্পষ্ট নয়।\nআবার ছবি তুলে পাঠান।"));
        assertThat(with).contains("সংক্ষেপে").contains("ছবিটি স্পষ্ট নয়।\nআবার ছবি তুলে পাঠান।");
        assertThat(ui.render(Lang.BN, view, id, java.util.Optional.empty())).doesNotContain("সংক্ষেপে");
    }

    /**
     * Through the router, the other direction of A12's split: a NEEDS_RETAKE or FAILED paper is still the legacy page —
     * its reason and its page, read through the timeline — never the result summary, which for a paper that is not
     * DONE would draw a header with no reason, no page and no items.
     */
    @ParameterizedTest
    @ValueSource(strings = {"NEEDS_RETAKE", "FAILED"})
    void aRetakeOrFailedPaperIsServedTheLegacyPageNeverTheSummary(String status) {
        DocumentView view = new DocumentView(WebUiTestKit.DOC_ID.toString(), status, "the document was not read confidently enough (0.75)",
                null, null, null, null, null, 1, null, null, null, null, null);
        when(kit.results.status(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID)).thenReturn(Mono.just(Document.Status.valueOf(status)));
        when(kit.timeline.document(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID)).thenReturn(Mono.just(view));
        when(kit.timeline.explanationOf(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID)).thenReturn(Mono.empty());
        // were the summary read, it would answer: the page must not ask
        when(kit.results.result(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID)).thenReturn(Mono.just(new ResultView(view, ResultFixtures.MA,
                true, ResultFixtures.BD, null, List.of(), GateState.NONE, null, 0, 2, ResultFixtures.NOW, ResultFixtures.NOW)));
        String html = kit.page("/app/documents/" + WebUiTestKit.DOC_ID, "bn");
        verify(kit.results, never()).result(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID);
        verify(kit.timeline, times(1)).document(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID);
        assertThat(html).contains("not read confidently enough").contains("/documents/" + WebUiTestKit.DOC_ID + "/page/1");
        if (status.equals("NEEDS_RETAKE")) {
            assertThat(html).contains(kit.copy.t(Lang.BN, "doc.retake"));
        }
    }
}
