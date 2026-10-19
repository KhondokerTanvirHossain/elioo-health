package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.web.domain.PatientSummary;
import com.elioo.baymax.web.domain.TimelinePage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Spec §3: three always-labelled tabs as links, a header naming whose papers are in view, and no guessing. */
class AppShellRoutesTest {

    private final WebUiTestKit kit = new WebUiTestKit();
    private final PatientSummary ma = kit.patient("আম্মা", true, 0);
    private final PatientSummary baba = kit.patient("আব্বু", true, 0);

    private void noPapers(PatientSummary p) {
        when(kit.timeline.timeline(eq(WebUiTestKit.FAMILY), eq(p.patient().id()), any())).thenReturn(Mono.just(new TimelinePage(List.of(), null)));
    }

    static String between(String html, String start, String end) {
        int i = html.indexOf(start);
        assertThat(i).as("page contains " + start).isNotNegative();
        int j = html.indexOf(end, i);
        assertThat(j).as(start + " is closed by " + end).isNotNegative();
        return html.substring(i, j + end.length());
    }

    /** The one element of a page that carries {@code cls}, refused when there are none or several. */
    static Dom.Element only(Dom dom, String tag, String cls) {
        List<Dom.Element> found = dom.all.stream().filter(e -> e.tag().equals(tag) && e.classes().contains(cls)).toList();
        assertThat(found).as(tag + "." + cls + " on the page").hasSize(1);
        return found.get(0);
    }

    /** {@code e} and everything under it. */
    static List<Dom.Element> subtree(Dom.Element e) {
        List<Dom.Element> out = new java.util.ArrayList<>(List.of(e));
        for (Dom.Element c : e.children()) {
            out.addAll(subtree(c));
        }
        return out;
    }

    /** All text under {@code e}, its own and its descendants'. */
    static String text(Dom.Element e) {
        return subtree(e).stream().map(Dom.Element::ownText).collect(java.util.stream.Collectors.joining(" "));
    }

    @ParameterizedTest
    @EnumSource(Tab.class)
    void eachTabAnswersBehindASessionAndMarksOnlyItselfCurrent(Tab tab) {
        kit.familyWith(ma);
        noPapers(ma);
        String nav = between(kit.page(tab.path, "bn"), "<nav class=\"tabs\"", "</nav>");
        assertThat(Pattern.compile("aria-current=\"page\"").matcher(nav).results().count()).isEqualTo(1);
        assertThat(nav).contains("<a href=\"" + tab.path + "\" aria-current=\"page\">");
        assertThat(nav).contains(">চ্যাট<").contains(">কাগজপত্র<").contains(">পরিবার<").doesNotContain("টাইমলাইন");
    }

    @ParameterizedTest
    @EnumSource(Tab.class)
    void withoutASessionEveryTabGoesToSignIn(Tab tab) {
        kit.client.get().uri(tab.path).exchange().expectStatus().isSeeOther()
                .expectHeader().valueEquals(HttpHeaders.LOCATION, "/app/");
    }

    /** PatientSwitcher README, on every tab: never preselect between two; the chooser, and no one's papers are read. */
    @ParameterizedTest
    @EnumSource(value = Tab.class, names = {"CHAT", "PAPERS"})
    void twoPatientsAndNoChoiceShowTheChooserAndReadNoOnesPapers(Tab tab) {
        kit.familyWith(ma, baba);
        // both stubbed, so a page that guessed renders (200) and fails on the guess, not on an unstubbed mock
        noPapers(ma);
        noPapers(baba);
        String html = kit.page(tab.path, "bn");
        // PAPERS would read the guessed patient's timeline; CHAT reads nobody's papers even when it guesses, so on
        // CHAT these two can never fail — the chooser below and the header test after this one are its guards
        verify(kit.timeline, never()).timeline(any(), any(), any());
        verify(kit.timeline, never()).document(any(), any());
        Dom dom = Dom.parse(html);
        assertThat(dom.unclosed).isEmpty();
        assertThat(dom.all.stream().filter(e -> e.tag().equals("h1")).map(Dom.Element::ownText)).containsExactly("কার কাগজ দেখবেন?");
        Dom.Element choices = only(dom, "ul", "choices");
        assertThat(subtree(choices).stream().filter(e -> e.tag().equals("a") && e.classes().contains("pchoice"))
                .map(e -> e.attrs().get("href")))
                .containsExactlyInAnyOrder(tab.path + "?patient=" + ma.patient().id(), tab.path + "?patient=" + baba.patient().id());
        assertThat(subtree(choices).stream().filter(e -> e.attrs().containsKey("aria-current")).map(Dom.Element::describe))
                .as("choices marked chosen").isEmpty();
    }

    /**
     * The header is where a guess would show: the chooser prints both names, so a whole-page check cannot see it. With
     * two and none chosen, on every tab, the header names no one and has no switcher.
     */
    @ParameterizedTest
    @EnumSource(Tab.class)
    void twoPatientsAndNoChoiceTheHeaderNamesNoOne(Tab tab) {
        kit.familyWith(ma, baba);
        noPapers(ma);
        noPapers(baba);
        Dom.Element header = only(Dom.parse(kit.page(tab.path, "bn")), "header", "appbar");
        assertThat(text(header)).as("names in the header").doesNotContain("আম্মা").doesNotContain("আব্বু");
        assertThat(subtree(header).stream().filter(e -> e.classes().contains("switcher")).map(Dom.Element::describe))
                .as("a switcher in the header").isEmpty();
    }

    /** The other direction: one patient is simply in view — no chooser, and the header names her. */
    @ParameterizedTest
    @EnumSource(value = Tab.class, names = {"CHAT", "PAPERS"})
    void onePatientIsInViewWithoutAsking(Tab tab) {
        kit.familyWith(ma);
        noPapers(ma);
        String html = kit.page(tab.path, "bn");
        assertThat(between(html, "<header class=\"appbar\"", "</header>")).contains("আম্মা");
        assertThat(html).doesNotContain("কার কাগজ দেখবেন?");
    }

    @Test
    void choosingByUrlShowsThatPatientAndAnUnseenPatientIs404() {
        kit.familyWith(ma, baba);
        noPapers(baba);
        assertThat(between(kit.page("/app/timeline?patient=" + baba.patient().id(), "bn"), "<header class=\"appbar\"", "</header>"))
                .contains("আব্বু").doesNotContain("আম্মা");
        kit.client.get().uri("/app/timeline?patient=" + UUID.randomUUID()).cookie("baymax_session", "tok").exchange()
                .expectStatus().isNotFound();
    }

    /**
     * A patient this family cannot see is the same 404 on every tab, পরিবার included (one rule in ActivePatient), and so
     * is an id that is not a UUID at all — never a fallback to the first patient, never anyone's papers.
     */
    @ParameterizedTest
    @EnumSource(Tab.class)
    void anUnseenOrMalformedPatientIs404OnEveryTab(Tab tab) {
        kit.familyWith(ma, baba);
        noPapers(ma);
        noPapers(baba);
        for (String asked : List.of(UUID.randomUUID().toString(), "not-a-uuid")) {
            kit.client.get().uri(tab.path + "?patient=" + asked).cookie("baymax_session", "tok").exchange()
                    .expectStatus().isNotFound();
        }
        verify(kit.timeline, never()).timeline(any(), any(), any());
    }

    /** WhatsApp messages and bookmarks carry the old URL; it opens কাগজপত্র with the patient chosen (profile: L5). */
    @Test
    void theOldPatientUrlOpensThePapersTab() {
        UUID id = ma.patient().id();
        kit.client.get().uri("/app/patients/" + id).cookie("baymax_session", "tok").exchange().expectStatus().isFound()
                .expectHeader().valueEquals(HttpHeaders.LOCATION, "/app/timeline?patient=" + id);
        kit.client.get().uri("/app/patients/not-a-uuid").cookie("baymax_session", "tok").exchange().expectStatus().isNotFound();
        kit.client.get().uri("/baymax/patients/" + id).exchange().expectStatus().isEqualTo(301);   // the 301 chain still starts
    }

    /**
     * The dangerous direction of the old link: it redirects without reading anything, and the tab it lands on checks the
     * patient against this family as any {@code ?patient=} is — an unseen patient is a 404 there, and no one's papers are read.
     */
    @Test
    void theOldPatientUrlForAPatientThisFamilyCannotSeeEndsIn404() {
        kit.familyWith(ma, baba);
        noPapers(ma);
        noPapers(baba);
        String unseen = UUID.randomUUID().toString();
        String location = kit.client.get().uri("/app/patients/" + unseen).cookie("baymax_session", "tok").exchange()
                .expectStatus().isFound().returnResult(Void.class).getResponseHeaders().getFirst(HttpHeaders.LOCATION);
        assertThat(location).isEqualTo("/app/timeline?patient=" + unseen);
        kit.client.get().uri(location).cookie("baymax_session", "tok").exchange().expectStatus().isNotFound();
        verify(kit.timeline, never()).timeline(any(), any(), any());
    }

    @Test
    void choosingSetsAThirtyMinuteCookieThatTheNextPageHonours() {
        kit.familyWith(ma, baba);
        noPapers(baba);
        kit.client.get().uri("/app/timeline?patient=" + baba.patient().id()).cookie("baymax_session", "tok").exchange()
                .expectStatus().isOk()
                .expectHeader().value(HttpHeaders.SET_COOKIE, c -> assertThat(c)
                        .startsWith("baymax_patient=" + baba.patient().id()).contains("Path=/app").contains("Max-Age=1800")
                        .contains("HttpOnly").contains("SameSite=Lax"));
        String next = kit.client.get().uri("/app/timeline").cookie("baymax_session", "tok")
                .cookie("baymax_patient", baba.patient().id().toString()).exchange()
                .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        assertThat(between(next, "<header class=\"appbar\"", "</header>")).contains("আব্বু").doesNotContain("আম্মা");
        // both pages read Baba's papers, the chosen one and the remembered one, and never Ma's
        verify(kit.timeline, times(2)).timeline(WebUiTestKit.FAMILY, baba.patient().id(), null);
        verify(kit.timeline, never()).timeline(any(), eq(ma.patient().id()), any());
    }

    /** Sliding, on every tab: a page with a patient in view re-sets the cookie; the chooser (no one in view) sets none. */
    @ParameterizedTest
    @EnumSource(Tab.class)
    void everyTabWithAPatientInViewSlidesTheCookieAndTheChooserSetsNone(Tab tab) {
        kit.familyWith(ma, baba);
        noPapers(ma);
        noPapers(baba);
        String remembered = kit.client.get().uri(tab.path).cookie("baymax_session", "tok")
                .cookie("baymax_patient", ma.patient().id().toString()).exchange().expectStatus().isOk()
                .returnResult(String.class).getResponseHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(remembered).startsWith("baymax_patient=" + ma.patient().id() + ";").contains("Max-Age=1800");
        if (tab != Tab.FAMILY) {
            assertThat(kit.client.get().uri(tab.path).cookie("baymax_session", "tok").exchange().expectStatus().isOk()
                    .returnResult(String.class).getResponseHeaders().get(HttpHeaders.SET_COOKIE))
                    .as("a cookie set while nobody is in view").isNull();
        }
    }

    /** A remembered patient this family can no longer see is forgotten (not a 404); asking for one by URL is a 404. */
    @Test
    void aStaleCookieIsIgnoredButAnUnseenPatientInTheUrlIs404() {
        kit.familyWith(ma);
        noPapers(ma);
        String html = kit.client.get().uri("/app/timeline").cookie("baymax_session", "tok")
                .cookie("baymax_patient", UUID.randomUUID().toString()).exchange()
                .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        assertThat(between(html, "<header class=\"appbar\"", "</header>")).contains("আম্মা");
        kit.client.get().uri("/app/timeline?patient=" + UUID.randomUUID()).cookie("baymax_session", "tok").exchange()
                .expectStatus().isNotFound();
    }

    /** Spec §3 sheets: the switcher is a URL; the page beneath is inert; the close link returns to the bare tab. */
    @Test
    void thePatientSheetIsAPlainUrlThatInertsThePageBeneathAndClosesBackToTheTab() {
        kit.familyWith(ma, baba);
        noPapers(ma);
        String open = kit.page("/app/timeline?patient=" + ma.patient().id() + "&sheet=patient", "bn");
        assertThat(open).contains("<div class=\"shell\" inert>");
        String dialog = between(open, "role=\"dialog\"", "</section>");
        assertThat(dialog).contains("href=\"/app/timeline\" autofocus")
                .contains("href=\"/app/timeline?patient=" + baba.patient().id() + "\"")
                .contains("aria-current=\"true\"");   // Ma is the chosen one
        String closed = kit.page("/app/timeline?patient=" + ma.patient().id(), "bn");
        assertThat(closed).contains("<div class=\"shell\">").doesNotContain("role=\"dialog\"");
        // the header's switcher is the sheet's URL on the tab it is on
        assertThat(between(closed, "<header class=\"appbar\"", "</header>")).contains("href=\"/app/timeline?sheet=patient\"");
    }

    /** The other direction of the sheet: with one patient there is nothing to switch to, so no sheet and no inert page. */
    @Test
    void withOnePatientThereIsNoSheetToOpen() {
        kit.familyWith(ma);
        noPapers(ma);
        String html = kit.page("/app/timeline?sheet=patient", "bn");
        assertThat(html).contains("<div class=\"shell\">").doesNotContain("role=\"dialog\"").doesNotContain(" inert");
    }

    /**
     * Never preselect, now with a cookie in play: two patients, no ?patient, and no cookie — or one naming a patient this
     * family cannot see, or not an id at all — still read nobody's papers on any tab.
     */
    @ParameterizedTest
    @EnumSource(value = Tab.class, names = {"CHAT", "PAPERS"})
    void aRememberedChoiceIsTheOnlyThingThatSkipsTheChooser(Tab tab) {
        kit.familyWith(ma, baba);
        // both stubbed, so a page that guessed renders (200) and fails on the guess, not on an unstubbed mock
        noPapers(ma);
        noPapers(baba);
        assertThat(kit.page(tab.path, "bn")).contains("কার কাগজ দেখবেন?");
        for (String stale : List.of(UUID.randomUUID().toString(), "not-a-uuid")) {
            String html = kit.client.get().uri(tab.path).cookie("baymax_session", "tok").cookie("baymax_lang", "bn")
                    .cookie("baymax_patient", stale).exchange().expectStatus().isOk()
                    .expectBody(String.class).returnResult().getResponseBody();
            assertThat(html).as("cookie " + stale).contains("কার কাগজ দেখবেন?");
        }
        verify(kit.timeline, never()).timeline(any(), any(), any());
    }

    /** The legacy upload form (a page cached before A7) lands where the sheet's does: on the paper it sent (A8). */
    @Test
    void aLegacyUploadLandsOnTheDocumentItSent() {
        String key = "POST /app/patients/{id}/upload";
        PageFixtures.Fixture f = PageFixtures.ALL.get(key);
        f.stubs().accept(kit);
        var r = RenderedPages.exchange(kit, new RouteTable.Route(PageFixtures.method(key), "/app/patients/{id}/upload"), f, "bn");
        assertThat(r.getStatus().value()).isEqualTo(303);
        assertThat(r.getResponseHeaders().getFirst(HttpHeaders.LOCATION)).isEqualTo("/app/documents/" + WebUiTestKit.DOC_ID);
    }

    /** No patient yet: the tab says which tab it is, not পরিবার (C4 and A6 draw the add-parent screens). */
    @ParameterizedTest
    @EnumSource(value = Tab.class, names = {"CHAT", "PAPERS"})
    void aFamilyWithNoPatientSeesTheTabItIsOn(Tab tab) {
        kit.familyWith();
        Dom dom = Dom.parse(kit.page(tab.path, "bn"));
        assertThat(dom.all.stream().filter(e -> e.tag().equals("h1")).map(Dom.Element::ownText))
                .containsExactly(kit.copy.t(Lang.BN, tab.labelKey));
        verify(kit.timeline, never()).timeline(any(), any(), any());
    }

    /** The চ্যাট placeholder: the composer says chat is coming and can send nothing (C4 keeps this test). */
    @Test
    void theChatComposerSaysChatIsComingAndCannotSend() {
        kit.familyWith(ma);
        Dom dom = Dom.parse(kit.page(Tab.CHAT.path, "bn"));
        assertThat(dom.unclosed).isEmpty();
        Dom.Element composer = only(dom, "div", "composer");
        // the whole subtree, so a control nested inside a div of the composer (C4) is still seen
        assertThat(text(composer)).contains(kit.copy.t(Lang.BN, "chat.soon"));
        assertThat(subtree(composer).stream().filter(e -> java.util.Set.of("input", "textarea", "form", "button", "select").contains(e.tag())
                        || "submit".equals(e.attrs().get("type")) || e.attrs().containsKey("contenteditable")).map(Dom.Element::describe))
                .as("controls in the composer").isEmpty();
        // and none anywhere on the page: the placeholder can send nothing at all
        assertThat(dom.all.stream().filter(e -> java.util.Set.of("form", "textarea", "input").contains(e.tag())).map(Dom.Element::describe))
                .as("a form or a field on the chat placeholder").isEmpty();
    }

    @Test
    void initialsKeepTheirVowelSignsAndConjuncts() {
        assertThat(AppShell.initial("আম্মা")).isEqualTo("আ");
        assertThat(AppShell.initial("রুমানা")).isEqualTo("রু");
        assertThat(AppShell.initial("স্বপ্না")).isEqualTo("স্ব");
        assertThat(AppShell.initial("Ma")).isEqualTo("M");
    }
}
