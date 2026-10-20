package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.web.domain.PatientSummary;
import com.elioo.baymax.web.domain.TimelineEntry;
import com.elioo.baymax.web.domain.TimelineFilter;
import com.elioo.baymax.web.domain.TimelinePage;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C2 on today's কাগজপত্র list (C3 restyles it): each finished paper's row carries the stored chip (NOW, THIS_WEEK; nothing
 * on ROUTINE or unassessed), the pending-review chip beside it, and the code-built summary; the filter links and
 * "আরও দেখুন" carry {@code type} and the opaque cursor. Every row is read by its own card, never from the page as a whole.
 */
class PapersTabTimelineTest {

    private static final Instant T = Instant.parse("2026-10-18T08:00:00Z");
    private static final UUID NOW_DOC = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4200001");
    private static final UUID WEEK_DOC = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4200002");
    private static final UUID ROUTINE_DOC = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4200003");
    private static final UUID UNASSESSED_DOC = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4200004");

    private final WebUiTestKit kit = new WebUiTestKit();
    private final PatientSummary ma = kit.patient("আম্মা", true, 4);
    private final String papers = Tab.PAPERS.path + "?patient=" + ma.patient().id();

    private static TimelineEntry done(UUID id, Urgency level, int values, int outside, String review) {
        return new TimelineEntry(id, LocalDate.parse("2026-10-12"), false, "lab_report", null, "DONE", values, 0, 0, 0,
                "f/p/" + id + "/page-1.jpg", T, level, outside, review, null);
    }

    private void papers(String nextCursor) {
        kit.familyWith(ma);
        when(kit.timeline.timeline(eq(WebUiTestKit.FAMILY), eq(ma.patient().id()), any(), any())).thenReturn(Mono.just(new TimelinePage(List.of(
                done(NOW_DOC, Urgency.NOW, 14, 1, "pending"),
                done(WEEK_DOC, Urgency.THIS_WEEK, 9, 2, "released"),
                done(ROUTINE_DOC, Urgency.ROUTINE, 9, 0, "released"),
                done(UNASSESSED_DOC, null, 9, 0, null)), nextCursor)));
    }

    /** The card of one paper: the element with class "card" that links to that document. */
    private static String card(String html, UUID doc) {
        Dom dom = Dom.parse(html);
        assertThat(dom.unclosed).isEmpty();
        List<Dom.Element> cards = dom.all.stream().filter(e -> e.classes().contains("card"))
                .filter(e -> AppShellRoutesTest.subtree(e).stream().anyMatch(x -> (PageChrome.BASE + "/documents/" + doc).equals(x.attrs().get("href"))))
                .toList();
        assertThat(cards).as("one card for " + doc).hasSize(1);
        return AppShellRoutesTest.subtree(cards.get(0)).stream().map(e -> "<" + e.tag() + " class=\"" + String.join(" ", e.classes()) + "\">" + e.ownText())
                .reduce("", String::concat);
    }

    private static List<String> chips(String cardHtml) {
        return Pattern.compile("class=\"(chip [a-z]+)\"").matcher(cardHtml).results().map(r -> r.group(1)).toList();
    }

    /** D27 + principle 3, both directions, by row: NOW and THIS_WEEK carry their chip; ROUTINE and unassessed none at all. */
    @Test
    void eachFinishedRowCarriesItsStoredChipAndRoutineAndUnassessedNone() {
        papers(null);
        String html = kit.page(papers, "bn");
        String now = card(html, NOW_DOC);
        String week = card(html, WEEK_DOC);
        assertThat(chips(now)).contains("chip now").doesNotContain("chip soon");
        assertThat(now).contains(kit.copy.urgency(Lang.BN, Urgency.NOW, true));
        assertThat(chips(week)).contains("chip soon").doesNotContain("chip now");
        assertThat(week).contains(kit.copy.urgency(Lang.BN, Urgency.THIS_WEEK, true));
        for (UUID quiet : List.of(ROUTINE_DOC, UNASSESSED_DOC)) {
            String row = card(html, quiet);
            assertThat(chips(row)).as(quiet + ": no level chip").doesNotContain("chip now", "chip soon");
            for (Urgency level : List.of(Urgency.NOW, Urgency.THIS_WEEK)) {
                assertThat(row).as(quiet + ": no urgency words").doesNotContain(kit.copy.urgency(Lang.BN, level, false))
                        .doesNotContain(kit.copy.urgency(Lang.BN, level, true));
            }
        }
    }

    /** The review state is shown beside the level, never instead of it (D5: urgency at once, in every gate state). */
    @Test
    void aPendingReviewChipSitsBesideTheLevelChip() {
        papers(null);
        String html = kit.page(papers, "bn");
        assertThat(chips(card(html, NOW_DOC))).containsExactly("chip now", "chip review");
        assertThat(card(html, NOW_DOC)).contains(kit.copy.t(Lang.BN, "timeline.review.pending"));
        assertThat(chips(card(html, WEEK_DOC))).as("released: no review chip").containsExactly("chip soon");
    }

    /**
     * Kit §6, asked of the served stylesheet's cascade on the rendered row, never of a class name: the pending chip sits
     * beside the level chip at the level chip's own size, weight and padding, in the unknown-status colour. The result
     * page's .chip.review has none of these, so reused as-is it renders at body size beside a 14 px chip.
     */
    @Test
    void thePendingChipIsDrawnAtTheLevelChipsSizeBesideIt() {
        papers(null);
        Dom dom = Dom.parse(kit.page(papers, "bn"));
        List<Css.Rule> rules = Css.rules(kit.servedText("/assets/baymax.css"));
        Dom.Element row = dom.all.stream().filter(e -> e.classes().contains("chips")
                && AppShellRoutesTest.subtree(e).stream().anyMatch(x -> x.classes().contains("now"))).findFirst().orElseThrow();
        Dom.Element level = AppShellRoutesTest.subtree(row).stream().filter(e -> e.classes().contains("now")).findFirst().orElseThrow();
        Dom.Element review = AppShellRoutesTest.subtree(row).stream().filter(e -> e.classes().contains("review")).findFirst().orElseThrow();
        for (String property : List.of("font-size", "line-height", "font-weight")) {
            assertThat(declared(rules, review, property)).as("the pending chip's " + property + " is the level chip's")
                    .isPresent().isEqualTo(declared(rules, level, property));
        }
        // the vertical padding (the first value); the level chip's left side is narrower for its icon
        assertThat(declared(rules, review, "padding").map(v -> v.split("\\s+")[0])).as("the pending chip's vertical padding")
                .isPresent().isEqualTo(declared(rules, level, "padding").map(v -> v.split("\\s+")[0]));
        assertThat(Css.winner(rules, review, "color").map(r -> Css.valueOf(r, "color"))).contains("var(--c-status-unknown)");
    }

    /** The value the winning rule of the served cascade gives {@code property} on the element itself, if any rule does. */
    private static java.util.Optional<String> declared(List<Css.Rule> rules, Dom.Element e, String property) {
        return Css.winner(rules, e, property).map(r -> Css.valueOf(r, property));
    }

    @Test
    void theRowSummaryIsTheCodeBuiltLine() {
        papers(null);
        String html = kit.page(papers, "bn");
        assertThat(card(html, NOW_DOC)).contains("১৪টি মান, ১টি সীমার বাইরে").doesNotContain("মান ১৪ ·");
        assertThat(card(html, ROUTINE_DOC)).contains("৯টি মান").doesNotContain("সীমা");
    }

    /** The filter links name their type; the one in view is marked; the request's type reaches the read model. */
    @Test
    void theFilterLinksCarryTheTypeAndTheChosenOneReachesTheReadModel() {
        papers(null);
        String html = kit.page(papers + "&type=rx", "bn");
        verify(kit.timeline).timeline(WebUiTestKit.FAMILY, ma.patient().id(), null, TimelineFilter.PRESCRIPTION);
        Dom dom = Dom.parse(html);
        Dom.Element nav = AppShellRoutesTest.only(dom, "nav", "filters");
        List<Dom.Element> links = AppShellRoutesTest.subtree(nav).stream().filter(e -> e.tag().equals("a")).toList();
        assertThat(links).extracting(e -> e.attrs().get("href")).containsExactly(
                papers, papers + "&amp;type=lab", papers + "&amp;type=rx");   // as written in the HTML
        assertThat(links).extracting(e -> e.attrs().get("aria-current")).containsExactly(null, null, "page");
        assertThat(links).extracting(Dom.Element::ownText).containsExactly(
                kit.copy.t(Lang.BN, "timeline.filter.all"), kit.copy.t(Lang.BN, "timeline.filter.lab"), kit.copy.t(Lang.BN, "timeline.filter.rx"));
    }

    /** "আরও দেখুন" continues the same filtered list from the opaque cursor. */
    @Test
    void theMoreLinkCarriesTheCursorAndTheType() {
        papers("v1.next-page");
        String html = kit.page(papers + "&type=lab", "bn");
        Dom dom = Dom.parse(html);
        List<String> more = dom.all.stream().filter(e -> e.tag().equals("a") && kit.copy.t(Lang.BN, "timeline.more").equals(e.ownText()))
                .map(e -> e.attrs().get("href")).toList();
        assertThat(more).containsExactly(papers + "&amp;type=lab&amp;cursor=v1.next-page");   // as written in the HTML
    }
}
