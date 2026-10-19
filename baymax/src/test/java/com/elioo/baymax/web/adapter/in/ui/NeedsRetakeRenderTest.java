package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.extraction.application.service.DocumentExtractionService;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.web.domain.GateState;
import com.elioo.baymax.web.domain.ResultView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * s12 / RetakeCard / F5 (B5): the Bangla reason from the code, the photo, a retake for the same patient through the one
 * upload entry — never English, never an urgency. And FAILED's own honest page. Replaces B4's interim retake line and
 * the legacy page's tests in DocumentPagesRenderTest.
 */
class NeedsRetakeRenderTest {

    private final WebUiTestKit kit = new WebUiTestKit();
    private final UiCopy copy = kit.copy;

    private String retake(String code, int ordinal, boolean owner) {
        return kit.documents.retakeBody(Lang.BN, ResultFixtures.retake(code, ordinal, owner));
    }

    @ParameterizedTest
    @CsvSource({"no_text,retake.reason.no_text", "low_overall,retake.reason.low_overall",
            "expected_section_empty:values,retake.reason.expected_section_empty.values", "low_section:values,retake.reason.low_section",
            "legacy,retake.reason.legacy"})
    void aRetakeShowsTheBanglaReasonForItsCode(String code, String key) {
        assertThat(ResultFixtures.section(retake(code, 1, true), "retake")).contains(copy.t(Lang.BN, "retake.head")).contains(copy.t(Lang.BN, key));
    }

    /** A section's own line replaces the reason's general line, never joins it; an English page reads the English line. */
    @Test
    void aSectionedRetakeReadsItsSectionLineInsteadOfTheGeneralOne() {
        assertThat(retake("expected_section_empty:values", 1, true))
                .contains(copy.t(Lang.BN, "retake.reason.expected_section_empty.values"))
                .doesNotContain(copy.t(Lang.BN, "retake.reason.expected_section_empty"))
                .doesNotContain("expected_section_empty");
        assertThat(kit.documents.retakeBody(Lang.EN, ResultFixtures.retake("expected_section_empty:values", 1, true)))
                .contains(copy.t(Lang.EN, "retake.reason.expected_section_empty.values")).doesNotContain("expected_section_empty");
    }

    /** Replaces the pre-F5 render test that asserted the developer English reached the family (spec §0 row 4). */
    @Test
    void aRetakeShowsTheBanglaReasonAndNeverTheDeveloperReasonOrAnUrgency() {
        String html = retake("low_overall", 1, true);
        assertThat(html).doesNotContain("not read confidently").doesNotContain("low_overall").contains("/documents/" + ResultFixtures.DOC + "/page/1");
        assertThat(html).doesNotContain("class=\"vrow").doesNotContain("id=\"urgency\"").doesNotContain("status-soon").doesNotContain("class=\"banner");
        // a retake is not urgency: the card is the warn tint, never the NOW or THIS_WEEK classes (RetakeCard README)
        assertThat(ResultFixtures.section(html, "retake")).startsWith("<section id=\"retake\" class=\"retake\">")
                .doesNotContain(" now").doesNotContain("soon");
    }

    /** "আবার তুলুন" opens the one upload entry for THIS patient, camera first, without script. */
    @Test
    void theRetakeButtonUploadsForTheSamePatientThroughTheOneEntry() {
        String form = ResultFixtures.section(retake("no_text", 1, true), "retake-form");
        assertThat(form).contains("method=\"post\"").contains("enctype=\"multipart/form-data\"").contains("action=\"/app/add\"")
                .contains("<input type=\"hidden\" name=\"patient\" value=\"" + ResultFixtures.MA.id() + "\">")
                .contains("capture=\"environment\"").contains("name=\"file\"").contains(copy.t(Lang.BN, "retake.again"))
                .contains(copy.t(Lang.BN, "retake.same_patient", ResultFixtures.MA.name()));
        assertThat(retake("no_text", 1, true)).contains(copy.t(Lang.BN, "retake.free")).contains(copy.t(Lang.BN, "retake.nothing_kept"));
    }

    /** "অন্য কাগজ পাঠান" is the add sheet for this patient, which comes back to this paper: every source, still chosen by the family. */
    @Test
    void anotherPaperOpensTheAddSheetForThisPatientAndComesBackHere() {
        String html = retake("no_text", 1, true);
        String back = java.net.URLEncoder.encode("/app/documents/" + ResultFixtures.DOC, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(html).contains("href=\"/app/add?patient=" + ResultFixtures.MA.id() + "&amp;back=" + back + "\"");
    }

    /** A view-only share member is told why, and gets no form that would 403 and no link to a sheet that would refuse. */
    @Test
    void aViewOnlyMemberGetsNoUploadForm() {
        String html = retake("no_text", 1, false);
        assertThat(html).doesNotContain("id=\"retake-form\"").doesNotContain("<form").doesNotContain("/app/add")
                .contains(copy.t(Lang.BN, "retake.viewonly"));
        // the reason and the photo still show: the member may see the paper, only not send for it
        assertThat(html).contains(copy.t(Lang.BN, "retake.reason.no_text")).contains("/documents/" + ResultFixtures.DOC + "/page/1");
    }

    /** RetakeCard third state: "অন্য কাগজ পাঠান" becomes primary, "এই কাগজটাই আবার তুলুন" goes quiet; before that, the reverse. */
    @Test
    void theThirdRetakeLeadsWithAnotherPaper() {
        String first = retake("low_overall", 1, true);
        String third = retake("low_overall", 3, true);
        assertThat(first.indexOf(copy.t(Lang.BN, "retake.again"))).isNotNegative()
                .isLessThan(first.indexOf(copy.t(Lang.BN, "retake.other")));
        assertThat(third.indexOf(copy.t(Lang.BN, "retake.other"))).isNotNegative()
                .isLessThan(third.indexOf(copy.t(Lang.BN, "retake.again.same")));
        assertThat(third).contains(copy.t(Lang.BN, "retake.title.third")).contains(copy.t(Lang.BN, "retake.third.p"))
                .contains(copy.t(Lang.BN, "retake.later"));
        assertThat(first).contains(copy.t(Lang.BN, "retake.title")).doesNotContain(copy.t(Lang.BN, "retake.title.third"))
                .doesNotContain(copy.t(Lang.BN, "retake.again.same"));
        // the primary is the one with the primary class, in each state
        assertThat(ResultFixtures.section(first, "retake-form")).contains("class=\"btn primary\"");
        assertThat(ResultFixtures.section(third, "retake-form")).doesNotContain("class=\"btn primary\"").contains("class=\"btn quiet\"");
    }

    /** Tips escalate (spec §4.12): the first retake gets one, the second and later all three. */
    @Test
    void theTipsEscalateFromTheSecondRetake() {
        String first = retake("low_overall", 1, true);
        String second = retake("low_overall", 2, true);
        assertThat(first).contains(copy.t(Lang.BN, "retake.tip.1")).doesNotContain(copy.t(Lang.BN, "retake.tip.2"))
                .doesNotContain(copy.t(Lang.BN, "retake.tip.3"));
        assertThat(second).contains(copy.t(Lang.BN, "retake.tip.1")).contains(copy.t(Lang.BN, "retake.tip.2"))
                .contains(copy.t(Lang.BN, "retake.tip.3"));
    }

    @Test
    void aFailedPaperSaysItWasOurSideAndOffersToSendAgainForTheSamePatient() {
        String html = kit.documents.failedBody(Lang.BN, ResultFixtures.failed());
        assertThat(html).contains(copy.t(Lang.BN, "failed.p")).contains(copy.t(Lang.BN, "failed.again")).contains("action=\"/app/add\"")
                .contains("<input type=\"hidden\" name=\"patient\" value=\"" + ResultFixtures.MA.id() + "\">")
                .doesNotContain("Exception").doesNotContain("error=");
        assertThat(html).as("FAILED is not a retake").doesNotContain(copy.t(Lang.BN, "retake.head")).doesNotContain("id=\"retake\"");
    }

    /**
     * Through the router, from the stored row as intake views it: a NEEDS_RETAKE or FAILED paper is read through
     * ResultUseCase.result (which carries the streak and whether the viewer owns the patient) and drawn by its own page;
     * the developer prose the row stores never reaches it, nor the summary's urgency.
     */
    @ParameterizedTest
    @ValueSource(strings = {"NEEDS_RETAKE", "FAILED"})
    void aRetakeOrFailedPaperIsServedItsOwnPageFromTheResultRead(String status) {
        boolean retake = status.equals("NEEDS_RETAKE");
        DocumentView view = DocumentView.pending(stored(status, retake ? LOW_OVERALL_PROSE : "groq HTTP 503", retake ? "low_overall" : null));
        when(kit.results.status(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID)).thenReturn(Mono.just(Document.Status.valueOf(status)));
        when(kit.results.result(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID)).thenReturn(Mono.just(new ResultView(view, ResultFixtures.MA,
                true, ResultFixtures.BD, null, List.of(), GateState.NONE, null, retake ? 1 : 0, 2, ResultFixtures.NOW, ResultFixtures.NOW)));
        // were the legacy read made, it would answer: the page must not ask (it knows neither the streak nor the owner)
        when(kit.timeline.document(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID)).thenReturn(Mono.just(view));
        when(kit.timeline.explanationOf(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID)).thenReturn(Mono.empty());
        String html = kit.page("/app/documents/" + WebUiTestKit.DOC_ID, "bn");
        verify(kit.timeline, never()).document(any(), any());
        assertThat(html).contains("id=\"" + (retake ? "retake" : "failed") + "\"").contains("action=\"/app/add\"")
                .contains("/documents/" + WebUiTestKit.DOC_ID + "/delete");
        assertThat(html).doesNotContain("not read confidently").doesNotContain("groq HTTP 503").doesNotContain("low_overall")
                .doesNotContain("id=\"urgency\"");
        if (retake) {
            assertThat(html).contains(copy.t(Lang.BN, "retake.reason.low_overall"));
        } else {
            assertThat(html).contains(copy.t(Lang.BN, "failed.p")).doesNotContain(copy.t(Lang.BN, "retake.head"));
        }
    }

    private static final String LOW_OVERALL_PROSE = DocumentExtractionService.REASON_LOW_OVERALL + " (0.40)";

    /** A stored document as the database holds it: the developer prose in status_reason, and the code beside it. */
    private static Document stored(String status, String prose, String code) {
        Instant t = Instant.parse("2026-10-19T08:00:00Z");
        return new Document(WebUiTestKit.DOC_ID, UUID.randomUUID(), UUID.randomUUID(), null, null, null, null, 0.4,
                Document.Status.valueOf(status), prose, null, null, 1, t, t, null, null, code);
    }
}
