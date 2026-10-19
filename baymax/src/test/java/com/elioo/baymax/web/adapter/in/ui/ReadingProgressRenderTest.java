package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.web.domain.ReadingView;
import com.elioo.baymax.web.domain.ViewerLocale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** s09 / ProgressSteps: only the real states, a reload that needs no script, and a poll that needs no inline script. */
class ReadingProgressRenderTest {

    private static final Instant NOW = Instant.parse("2026-10-20T04:00:00Z");
    private static final PatientProfile MA = new PatientProfile(WebUiTestKit.MA_ID, WebUiTestKit.FAMILY, "Ma", 64,
            PatientProfile.Sex.FEMALE, List.of(), NOW, NOW);
    private final WebUiTestKit kit = new WebUiTestKit();

    private static ReadingView reading(Document.Status status, Duration elapsed, boolean bangladesh) {
        Document d = Document.received(MA.id(), MA.familyId(), 2, NOW.minus(elapsed)).withId(WebUiTestKit.DOC_ID);
        Document at = status == Document.Status.RECEIVED ? d : d.withStatus(status, null, NOW);
        return new ReadingView(DocumentView.pending(at), MA, true, new ViewerLocale(bangladesh), NOW.minus(elapsed), NOW);
    }

    @Test
    void withoutScriptThePageRefreshesItselfAndWithScriptItPolls() {
        String page = kit.documents.progressPage(Lang.BN, reading(Document.Status.PROCESSING, Duration.ofSeconds(30), true));
        assertThat(page).contains("<noscript><meta http-equiv=\"refresh\" content=\"5\"></noscript>")
                .containsPattern("<script src=\"/assets/progress\\.js\\?v=[0-9a-f]+\" defer></script>");
        // an unconditional refresh would reload over the poll: the only refresh on the page is the one inside <noscript>
        assertThat(page.split("http-equiv=\"refresh\"", -1).length - 1).as("refresh metas").isEqualTo(1);
        assertThat(Pattern.compile("<script(?![^>]*\\bsrc=)").matcher(page).find()).as("an inline script").isFalse();
        assertThat(page).contains("data-status-url=\"/app/documents/" + WebUiTestKit.DOC_ID + "/status\"").contains("data-status=\"PROCESSING\"");
    }

    /** Through the router: an in-flight document's page is the reading page, and it is under কাগজপত্র. */
    @Test
    void anInFlightDocumentsPageIsTheReadingPage() {
        PageFixtures.Stubs.processing(kit);
        String page = kit.page("/app/documents/" + WebUiTestKit.DOC_ID, "bn");
        assertThat(page).contains("<section id=\"progress\"").contains("data-status=\"PROCESSING\"")
                .contains(kit.copy.t(Lang.BN, "progress.title")).contains("<noscript><meta http-equiv=\"refresh\" content=\"5\"></noscript>");
    }

    /**
     * A paper finished between the status read and the view read is drawn finished: a reading page drawn for it would
     * carry data-status="DONE", and the poll, seeing no change, would wait on it for ever.
     */
    @Test
    void aPaperThatFinishedBetweenTheTwoReadsIsDrawnFinished() {
        PageFixtures.Stubs.doneLab(kit);
        Document done = Document.received(MA.id(), MA.familyId(), 1, NOW).withId(WebUiTestKit.DOC_ID).withStatus(Document.Status.DONE, null, NOW);
        org.mockito.Mockito.when(kit.results.status(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID)).thenReturn(reactor.core.publisher.Mono.just(Document.Status.PROCESSING));
        org.mockito.Mockito.when(kit.results.reading(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID)).thenReturn(reactor.core.publisher.Mono.just(
                new ReadingView(DocumentView.pending(done), MA, true, new ViewerLocale(true), NOW, NOW)));
        String page = kit.page("/app/documents/" + WebUiTestKit.DOC_ID, "bn");
        assertThat(page).contains(kit.copy.t(Lang.BN, "doc.values")).doesNotContain("data-status=").doesNotContain("<script");
    }

    /** Three honest steps from Document.Status — no percentage, no crop-verification step (spec §4.9). */
    @ParameterizedTest
    @CsvSource({"RECEIVED,1", "PROCESSING,2"})
    void theStepsAreTheStoredStatusAndNothingInvented(Document.Status status, int current) {
        String body = kit.documents.progressBody(Lang.BN, reading(status, Duration.ofSeconds(30), true));
        String steps = body.substring(body.indexOf("<ol id=\"steps\""), body.indexOf("</ol>") + 5);
        assertThat(steps.split("<li").length - 1).isEqualTo(3);
        assertThat(steps).doesNotContain("%").doesNotContain("যাচাই");
        assertThat(steps.split("aria-current=", -1).length - 1).as("exactly one current step").isEqualTo(1);
        if (current == 2) {
            assertThat(steps).containsPattern("<li class=\"done\"[^>]*>[^<]*" + kit.copy.t(Lang.BN, "progress.step.received"))
                    .containsPattern("<li aria-current=\"step\"[^>]*>[^<]*" + kit.copy.t(Lang.BN, "progress.step.processing"));
        } else {
            assertThat(steps).containsPattern("<li aria-current=\"step\"[^>]*>[^<]*" + kit.copy.t(Lang.BN, "progress.step.received"))
                    .containsPattern("<li class=\"next\"[^>]*>[^<]*" + kit.copy.t(Lang.BN, "progress.step.processing"));
        }
        assertThat(steps).containsPattern("<li class=\"next\"[^>]*>[^<]*" + kit.copy.t(Lang.BN, "progress.step.done"));
    }

    /** Both directions of the five-minute line. */
    @Test
    void itSaysItIsTakingLongerOnlyFromFiveMinutes() {
        String slow = kit.copy.t(Lang.BN, "progress.slow");
        assertThat(kit.documents.progressBody(Lang.BN, reading(Document.Status.PROCESSING, Duration.ofMinutes(5).minusSeconds(1), true))).doesNotContain(slow);
        assertThat(kit.documents.progressBody(Lang.BN, reading(Document.Status.PROCESSING, Duration.ofMinutes(5), true))).contains(slow);
    }

    /** No result text, no urgency and no reassurance before DONE. */
    @Test
    void nothingAboutTheResultAppearsWhileReading() {
        String html = kit.documents.progressBody(Lang.BN, reading(Document.Status.PROCESSING, Duration.ofSeconds(30), true));
        assertThat(html).doesNotContain("id=\"urgency\"").doesNotContain("class=\"vrow").doesNotContain("চিন্তা")
                .contains(kit.copy.t(Lang.BN, "progress.leave"));
    }

    /** A reader abroad is told the time is the patient's calendar; a reader at home is not. */
    @Test
    void aReaderAbroadSeesDhakaTimeMarked() {
        assertThat(kit.documents.progressBody(Lang.BN, reading(Document.Status.RECEIVED, Duration.ofSeconds(30), false))).contains("(ঢাকার সময়)");
        assertThat(kit.documents.progressBody(Lang.BN, reading(Document.Status.RECEIVED, Duration.ofSeconds(30), true))).doesNotContain("(ঢাকার সময়)");
    }

    /** JS only where it is needed (spec §10): the script is small, holds no copy, and a DONE page loads no script and never refreshes. */
    @Test
    void theScriptIsSmallCopyFreeAndOnlyOnTheReadingPage() {
        byte[] js = kit.served("/assets/progress.js");
        assertThat(js.length).isPositive().isLessThanOrEqualTo(40 * 1024);
        String text = new String(js, StandardCharsets.UTF_8);
        assertThat(text).contains("data-status-url").doesNotContain("eval(").doesNotContain("innerHTML");
        assertThat(text.codePoints().noneMatch(c -> c >= 0x0980 && c <= 0x09FF)).as("no Bangla copy in the script").isTrue();
        PageFixtures.Stubs.doneLab(kit);
        String done = kit.page("/app/documents/" + WebUiTestKit.DOC_ID, "bn");
        assertThat(done).contains(kit.copy.t(Lang.BN, "doc.values")).doesNotContain("<script").doesNotContain("http-equiv=\"refresh\"");
    }
}
