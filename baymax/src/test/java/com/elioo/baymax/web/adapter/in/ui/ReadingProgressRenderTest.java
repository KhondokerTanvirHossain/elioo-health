package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.web.domain.ReadingView;
import com.elioo.baymax.web.domain.ViewerLocale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** s09 / ProgressSteps: only the real states, a reload that needs no script, and a poll that needs no inline script. */
class ReadingProgressRenderTest {

    /** 10:00 in Dhaka; a paper sent 30 s before arrived at 09:59:30, "সকাল ৯:৫৯". */
    private static final Instant NOW = Instant.parse("2026-10-20T04:00:00Z");
    private static final PatientProfile MA = new PatientProfile(WebUiTestKit.MA_ID, WebUiTestKit.FAMILY, "Ma", 64,
            PatientProfile.Sex.FEMALE, List.of(), NOW, NOW);
    private final WebUiTestKit kit = new WebUiTestKit();

    private static Document document(Document.Status status, Duration elapsed) {
        Document d = Document.received(MA.id(), MA.familyId(), 2, NOW.minus(elapsed)).withId(WebUiTestKit.DOC_ID);
        return status == Document.Status.RECEIVED ? d : d.withStatus(status, null, NOW);
    }

    private static ReadingView reading(Document.Status status, Duration elapsed, boolean bangladesh) {
        return new ReadingView(DocumentView.pending(document(status, elapsed)), MA, true, new ViewerLocale(bangladesh), NOW.minus(elapsed), NOW);
    }

    private Dom body(Document.Status status, Duration elapsed, boolean bangladesh) {
        Dom dom = Dom.parse(kit.documents.progressBody(Lang.BN, reading(status, elapsed, bangladesh)));
        assertThat(dom.unclosed).as("the reading page's markup").isEmpty();
        return dom;
    }

    private static Dom.Element only(Dom dom, String what, Predicate<Dom.Element> p) {
        List<Dom.Element> found = dom.all.stream().filter(p).toList();
        assertThat(found).as(what).hasSize(1);
        return found.get(0);
    }

    private static Dom.Element byId(Dom dom, String id) {
        return only(dom, "#" + id, e -> id.equals(e.attrs().get("id")));
    }

    /** The {@code <li>} of the steps whose own text starts with a step's label. */
    private Dom.Element step(Dom dom, String key) {
        String label = kit.copy.t(Lang.BN, key);
        return only(dom, "step " + key, e -> e.tag().equals("li") && e.ownText().startsWith(label));
    }

    private static String metaOf(Dom.Element li) {
        List<Dom.Element> metas = li.children().stream().filter(c -> c.tag().equals("span") && c.classes().contains("meta")).toList();
        assertThat(metas).as("the meta of " + li.describe()).hasSize(1);
        return metas.get(0).ownText();
    }

    @Test
    void withoutScriptThePageRefreshesItselfAndWithScriptItPolls() {
        String page = kit.documents.progressPage(Lang.BN, reading(Document.Status.PROCESSING, Duration.ofSeconds(30), true));
        assertThat(page).contains("<noscript><meta http-equiv=\"refresh\" content=\"5\"></noscript>")
                .containsPattern("<script src=\"/assets/progress\\.js\\?v=[0-9a-f]+\" defer></script>");
        // an unconditional refresh would reload over the poll: the only refresh on the page is the one inside <noscript>
        assertThat(page.split("http-equiv=\"refresh\"", -1).length - 1).as("refresh metas").isEqualTo(1);
        assertThat(Pattern.compile("<script(?![^>]*\\bsrc=)").matcher(page).find()).as("an inline script").isFalse();
        Dom.Element progress = byId(Dom.parse(page), "progress");
        assertThat(progress.attrs()).containsEntry("data-status-url", "/app/documents/" + WebUiTestKit.DOC_ID + "/status")
                .containsEntry("data-status", "PROCESSING");
    }

    /**
     * Through the router: an in-flight document's page is the reading page, under কাগজপত্র, and it reads the reading
     * view exactly once (one DOCUMENT_VIEW). A finished paper's page never reads it: the status alone decides, so a DONE
     * page records the one DOCUMENT_VIEW of the document page and not a second one from the reading view.
     */
    @Test
    void anInFlightDocumentsPageIsTheReadingPageAndOnlyItReadsTheReadingView() {
        PageFixtures.Stubs.processing(kit);
        Dom dom = Dom.parse(kit.page("/app/documents/" + WebUiTestKit.DOC_ID, "bn"));
        assertThat(byId(dom, "progress").attrs()).containsEntry("data-status", "PROCESSING");
        assertThat(only(dom, "h1", e -> e.tag().equals("h1")).ownText()).isEqualTo(kit.copy.t(Lang.BN, "progress.title"));
        verify(kit.results, times(1)).reading(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID);
        verify(kit.timeline, never()).document(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID);

        WebUiTestKit done = new WebUiTestKit();
        PageFixtures.Stubs.doneLab(done);
        // were the reading view read, it would answer: the page must not ask (a DONE paper's page records one view)
        Document finished = Document.received(MA.id(), MA.familyId(), 1, NOW).withId(WebUiTestKit.DOC_ID).withStatus(Document.Status.DONE, null, NOW);
        when(done.results.reading(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID)).thenReturn(Mono.just(
                new ReadingView(DocumentView.pending(finished), MA, true, new ViewerLocale(true), NOW, NOW)));
        done.page("/app/documents/" + WebUiTestKit.DOC_ID, "bn");
        verify(done.results, never()).reading(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID);
        // A12: a DONE paper's page is the result, one read that records the one view; the legacy read is not made
        verify(done.results, times(1)).result(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID);
        verify(done.timeline, never()).document(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID);
    }

    /**
     * A paper finished between the status read and the view read is drawn finished: a reading page drawn for it would
     * carry data-status="DONE", and the poll, seeing no change, would wait on it for ever.
     */
    @Test
    void aPaperThatFinishedBetweenTheTwoReadsIsDrawnFinished() {
        PageFixtures.Stubs.doneLab(kit);
        Document done = Document.received(MA.id(), MA.familyId(), 1, NOW).withId(WebUiTestKit.DOC_ID).withStatus(Document.Status.DONE, null, NOW);
        when(kit.results.status(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID)).thenReturn(Mono.just(Document.Status.PROCESSING));
        when(kit.results.reading(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID)).thenReturn(Mono.just(
                new ReadingView(DocumentView.pending(done), MA, true, new ViewerLocale(true), NOW, NOW)));
        Dom dom = Dom.parse(kit.page("/app/documents/" + WebUiTestKit.DOC_ID, "bn"));
        assertThat(dom.all).as("the finished page's values heading")
                .anyMatch(e -> e.tag().equals("h2") && e.ownText().equals(kit.copy.raw(Lang.BN, "doc.values")));
        assertThat(dom.all).as("no reading section, no script").noneMatch(e -> "progress".equals(e.attrs().get("id")) || e.tag().equals("script"));
        assertThat(dom.all).as("drawn as the result: the stored urgency leads").anyMatch(e -> "urgency".equals(e.attrs().get("id")));
        verify(kit.results, times(1)).result(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID);
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

    /** The received step says when it arrived on the patient's clock (its createdAt), and the meta line how many pages. */
    @Test
    void theReceivedStepCarriesTheArrivalTimeAndTheMetaLineThePageCount() {
        Dom dom = body(Document.Status.PROCESSING, Duration.ofSeconds(30), true);
        assertThat(metaOf(step(dom, "progress.step.received"))).isEqualTo("সকাল ৯:৫৯");
        Dom.Element sent = only(dom, "p.meta", e -> e.tag().equals("p") && e.classes().contains("meta"));
        assertThat(sent.ownText()).isEqualTo(kit.copy.plain(Lang.BN, "progress.sent", 2));
    }

    /**
     * The five-minute line in both directions, and for a page that stays open: it is always drawn, hidden before five
     * minutes with the seconds left on #progress for progress.js to show it then (the copy stays out of the script), and
     * shown from five minutes, when the script has nothing left to wait for.
     */
    @Test
    void theTakingLongerLineIsHiddenBeforeFiveMinutesAndShownFromThem() {
        String slowCopy = kit.copy.raw(Lang.BN, "progress.slow");
        for (Duration elapsed : List.of(Duration.ofSeconds(30), Duration.ofMinutes(5).minusSeconds(1))) {
            Dom dom = body(Document.Status.PROCESSING, elapsed, true);
            Dom.Element slow = byId(dom, "slow");
            assertThat(slow.tag()).isEqualTo("p");
            assertThat(slow.classes()).contains("warn");
            assertThat(slow.ownText()).isEqualTo(slowCopy);
            assertThat(slow.attrs()).as("hidden at " + elapsed).containsKey("hidden");
            assertThat(byId(dom, "progress").attrs()).as("seconds left at " + elapsed)
                    .containsEntry("data-slow-in", String.valueOf(Duration.ofMinutes(5).minus(elapsed).toSeconds()));
        }
        for (Duration elapsed : List.of(Duration.ofMinutes(5), Duration.ofMinutes(12))) {
            Dom dom = body(Document.Status.PROCESSING, elapsed, true);
            Dom.Element slow = byId(dom, "slow");
            assertThat(slow.ownText()).isEqualTo(slowCopy);
            assertThat(slow.attrs()).as("shown at " + elapsed).doesNotContainKey("hidden");
            assertThat(byId(dom, "progress").attrs()).as("nothing to wait for at " + elapsed).doesNotContainKey("data-slow-in");
        }
    }

    /**
     * No result text, no urgency and no reassurance before DONE — from a view that carries a result, so a page that drew
     * one would show it (DocumentView.pending carries none and could not fail this).
     */
    @Test
    void nothingAboutTheResultAppearsWhileReading() {
        Document d = document(Document.Status.PROCESSING, Duration.ofSeconds(30));
        DocumentView carrying = new DocumentView(d.id().toString(), "PROCESSING", null, "lab_report", "2026-10-12",
                "Shapla Diagnostics", 0.9, "m", 2,
                List.of(Map.of("name", "HbA1c", "value", "8.2", "unit", "%", "crop_key", "c1.jpg")),
                List.of(Map.of("name", "Metformin", "dose_text", "500 mg", "crop_key", "c2.jpg")),
                List.of(Map.of("instruction", "Come back", "crop_key", "c3.jpg")),
                Map.of("diagnosis", List.of(Map.of("text", "Type 2 diabetes"))), null);
        String html = kit.documents.progressBody(Lang.BN, new ReadingView(carrying, MA, true, new ViewerLocale(true), NOW.minusSeconds(30), NOW));
        assertThat(html).doesNotContain("HbA1c").doesNotContain("8.2").doesNotContain("Metformin").doesNotContain("Come back")
                .doesNotContain("Type 2 diabetes").doesNotContain("Shapla").doesNotContain("id=\"urgency\"")
                .doesNotContain("class=\"vrow").doesNotContain("চিন্তা").contains(kit.copy.t(Lang.BN, "progress.leave"));
    }

    /** A reader abroad is told the time is the patient's calendar, inside the received step's meta; a reader at home is not. */
    @Test
    void aReaderAbroadSeesDhakaTimeMarked() {
        assertThat(metaOf(step(body(Document.Status.RECEIVED, Duration.ofSeconds(30), false), "progress.step.received")))
                .isEqualTo("সকাল ৯:৫৯ " + kit.copy.raw(Lang.BN, "time.dhaka"));
        assertThat(metaOf(step(body(Document.Status.RECEIVED, Duration.ofSeconds(30), true), "progress.step.received")))
                .isEqualTo("সকাল ৯:৫৯");
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
