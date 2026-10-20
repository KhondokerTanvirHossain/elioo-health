package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.web.application.port.in.WebAuthUseCase;
import com.elioo.baymax.web.domain.PatientSummary;
import com.elioo.baymax.web.domain.TimelinePage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
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
        when(kit.timeline.timeline(eq(WebUiTestKit.FAMILY), eq(p.patient().id()), any(), any())).thenReturn(Mono.just(new TimelinePage(List.of(), null)));
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
        // PAPERS would read the guessed patient's timeline and strip; CHAT the guessed patient's attention stack (C4)
        verify(kit.timeline, never()).timeline(any(), any(), any(), any());
        verify(kit.timeline, never()).document(any(), any());
        verify(kit.attention, never()).attention(any(), any());
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
        verify(kit.timeline, never()).timeline(any(), any(), any(), any());
        verify(kit.attention, never()).attention(any(), any());
    }

    /** WhatsApp messages and bookmarks carry the old URL; it opens কাগজপত্র with the patient chosen (profile: L5). */
    @Test
    void theOldPatientUrlOpensThePapersTab() {
        // the family and both papers stubbed, so the old page (which read them) renders and fails on the missing
        // redirect, not on an unstubbed mock
        kit.familyWith(ma, baba);
        noPapers(ma);
        noPapers(baba);
        UUID id = ma.patient().id();
        kit.client.get().uri("/app/patients/" + id).cookie("baymax_session", "tok").exchange().expectStatus().isFound()
                .expectHeader().valueEquals(HttpHeaders.LOCATION, "/app/timeline?patient=" + id);
        kit.client.get().uri("/app/patients/not-a-uuid").cookie("baymax_session", "tok").exchange().expectStatus().isNotFound();
        // the redirect reads nothing: the tab it lands on is where the family check happens
        verify(kit.timeline, never()).me(any());
        // the old host's link still starts the chain, and to this route
        kit.client.get().uri("/baymax/patients/" + id).exchange().expectStatus().isEqualTo(301)
                .expectHeader().valueEquals(HttpHeaders.LOCATION, "/app/patients/" + id);
    }

    /** Which wins when both are there: the URL is the family's choice just now, the cookie only the last one. */
    @ParameterizedTest
    @EnumSource(Tab.class)
    void theUrlBeatsTheRememberedChoice(Tab tab) {
        kit.familyWith(ma, baba);
        noPapers(ma);
        noPapers(baba);
        EntityExchangeResult<String> r = kit.client.get().uri(tab.path + "?patient=" + baba.patient().id())
                .cookie("baymax_session", "tok").cookie("baymax_lang", "bn").cookie("baymax_patient", ma.patient().id().toString())
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult();
        assertThat(text(only(Dom.parse(r.getResponseBody()), "header", "appbar"))).contains("আব্বু").doesNotContain("আম্মা");
        assertThat(r.getResponseHeaders().getFirst(HttpHeaders.SET_COOKIE)).startsWith("baymax_patient=" + baba.patient().id() + ";");
        verify(kit.timeline, never()).timeline(any(), eq(ma.patient().id()), any(), any());
        if (tab == Tab.PAPERS) {
            verify(kit.timeline).timeline(WebUiTestKit.FAMILY, baba.patient().id(), null, com.elioo.baymax.web.domain.TimelineFilter.ALL);
        }
    }

    /** The other direction: a remembered choice never rescues a URL naming a patient this family cannot see. */
    @ParameterizedTest
    @EnumSource(Tab.class)
    void aRememberedChoiceDoesNotRescueAnUnseenPatientInTheUrl(Tab tab) {
        kit.familyWith(ma, baba);
        noPapers(ma);
        noPapers(baba);
        kit.client.get().uri(tab.path + "?patient=" + UUID.randomUUID()).cookie("baymax_session", "tok")
                .cookie("baymax_patient", ma.patient().id().toString()).exchange().expectStatus().isNotFound();
        verify(kit.timeline, never()).timeline(any(), any(), any(), any());
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
        verify(kit.timeline, never()).timeline(any(), any(), any(), any());
    }

    @Test
    void choosingSetsAThirtyMinuteCookieThatTheNextPageHonours() {
        kit.familyWith(ma, baba);
        noPapers(baba);
        kit.client.get().uri("/app/timeline?patient=" + baba.patient().id()).cookie("baymax_session", "tok").exchange()
                .expectStatus().isOk()
                .expectHeader().value(HttpHeaders.SET_COOKIE, c -> assertThat(cookieAttributes(c))
                        .startsWith("baymax_patient=" + baba.patient().id())
                        .contains("Path=/app", "Max-Age=1800", "HttpOnly", "SameSite=Lax", "Secure"));
        String next = kit.client.get().uri("/app/timeline").cookie("baymax_session", "tok")
                .cookie("baymax_patient", baba.patient().id().toString()).exchange()
                .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        assertThat(between(next, "<header class=\"appbar\"", "</header>")).contains("আব্বু").doesNotContain("আম্মা");
        // both pages read Baba's papers, the chosen one and the remembered one, and never Ma's
        verify(kit.timeline, times(2)).timeline(WebUiTestKit.FAMILY, baba.patient().id(), null, com.elioo.baymax.web.domain.TimelineFilter.ALL);
        verify(kit.timeline, never()).timeline(any(), eq(ma.patient().id()), any(), any());
    }

    /** A Set-Cookie header split into its fields, so an attribute is matched whole, never as a substring of another. */
    static List<String> cookieAttributes(String setCookie) {
        return java.util.Arrays.stream(setCookie.split(";")).map(String::trim).toList();
    }

    /**
     * The other direction of Secure: it comes from {@code auth.cookieSecure} (off for plain-http local runs), not from a
     * constant — with the property off the cookie still works and is not marked Secure.
     */
    @Test
    void theCookieIsSecureOnlyWhenTheSessionCookieIs() {
        BaymaxProperties plainHttp = new BaymaxProperties();
        plainHttp.getAuth().setCookieSecure(false);
        WebUiTestKit local = new WebUiTestKit(org.mockito.Mockito.mock(WebAuthUseCase.class), plainHttp, true);
        local.familyWith(ma, baba);
        when(local.timeline.timeline(eq(WebUiTestKit.FAMILY), eq(baba.patient().id()), any(), any())).thenReturn(Mono.just(new TimelinePage(List.of(), null)));
        String c = local.client.get().uri("/app/timeline?patient=" + baba.patient().id()).cookie("baymax_session", "tok").exchange()
                .expectStatus().isOk().returnResult(String.class).getResponseHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(cookieAttributes(c)).startsWith("baymax_patient=" + baba.patient().id())
                .contains("HttpOnly", "Max-Age=1800").doesNotContain("Secure");
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

    /** An href as the browser follows it: the page writes {@code &} as {@code &amp;} inside an attribute. */
    static String followed(String href) {
        return href.replace("&amp;", "&");
    }

    /** The one {@code role="dialog"} sheet on a page, refused when there are none or several. */
    static Dom.Element sheet(Dom dom) {
        List<Dom.Element> found = dom.all.stream().filter(e -> e.tag().equals("section") && e.classes().contains("sheet")
                && "dialog".equals(e.attrs().get("role"))).toList();
        assertThat(found).as("section.sheet[role=dialog] on the page").hasSize(1);
        return found.get(0);
    }

    static Dom.Element tag(Dom dom, String tag) {
        List<Dom.Element> found = dom.all.stream().filter(e -> e.tag().equals(tag)).toList();
        assertThat(found).as(tag + " on the page").hasSize(1);
        return found.get(0);
    }

    /** The open sheet: exactly one dialog, the page beneath inert, the scrim on (has-sheet). */
    static void assertSheetOpen(Dom dom) {
        sheet(dom);
        assertThat(only(dom, "div", "shell").attrs()).as("the page beneath the sheet").containsKey("inert");
        assertThat(tag(dom, "body").classes()).as("body classes").contains("in-app", "has-sheet");
    }

    /** No sheet: no dialog, nothing inert, no scrim. */
    static void assertNoSheet(Dom dom) {
        assertThat(dom.all.stream().filter(e -> "dialog".equals(e.attrs().get("role"))).map(Dom.Element::describe)).as("dialogs").isEmpty();
        assertThat(dom.all.stream().filter(e -> e.attrs().containsKey("inert")).map(Dom.Element::describe)).as("inert elements").isEmpty();
        assertThat(tag(dom, "body").classes()).as("body classes").contains("in-app").doesNotContain("has-sheet");
    }

    /**
     * Spec §3 sheets, on every tab, by the link the header actually draws and with no cookie at all (a browser that
     * blocks them): tapping the name opens the sheet over the page, which is inert; the one in view is marked; the close
     * link goes back to the tab with the same patient in view and no sheet.
     */
    @ParameterizedTest
    @EnumSource(Tab.class)
    void theHeaderNameOpensTheSheetAndItsCloseLinkReturnsToTheTab(Tab tab) {
        kit.familyWith(ma, baba);
        noPapers(ma);
        noPapers(baba);
        Dom closed = Dom.parse(kit.page(tab.path + "?patient=" + ma.patient().id(), "bn"));
        assertNoSheet(closed);
        Dom.Element switcher = only(closed, "a", "switcher");
        assertThat(switcher.attrs()).containsEntry("aria-haspopup", "dialog");

        Dom open = Dom.parse(kit.page(followed(switcher.attrs().get("href")), "bn"));
        assertSheetOpen(open);
        List<Dom.Element> links = subtree(sheet(open)).stream().filter(e -> e.tag().equals("a")).toList();
        Dom.Element close = links.stream().filter(e -> e.attrs().containsKey("autofocus")).findFirst().orElseThrow();
        // the one in view is marked by its own element — Ma's choice, and Baba's is not
        List<Dom.Element> choices = links.stream().filter(e -> e.classes().contains("pchoice")).toList();
        assertThat(choices).extracting(e -> e.attrs().get("href"))
                .containsExactlyInAnyOrder(tab.path + "?patient=" + ma.patient().id(), tab.path + "?patient=" + baba.patient().id());
        for (Dom.Element c : choices) {
            boolean isMa = c.attrs().get("href").endsWith(ma.patient().id().toString());
            assertThat(c.attrs().get("aria-current")).as("aria-current on " + c.attrs().get("href")).isEqualTo(isMa ? "true" : null);
        }

        Dom back = Dom.parse(kit.page(followed(close.attrs().get("href")), "bn"));
        assertNoSheet(back);
        assertThat(text(only(back, "header", "appbar"))).contains("আম্মা").doesNotContain("আব্বু");
    }

    /** The remembered choice alone also opens the sheet: {@code ?sheet=patient} with the cookie and no {@code ?patient=}. */
    @Test
    void theSheetOpensOverTheRememberedPatient() {
        kit.familyWith(ma, baba);
        noPapers(ma);
        String html = kit.client.get().uri("/app/timeline?sheet=patient").cookie("baymax_session", "tok").cookie("baymax_lang", "bn")
                .cookie("baymax_patient", ma.patient().id().toString()).exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        Dom dom = Dom.parse(html);
        assertSheetOpen(dom);
        assertThat(text(only(dom, "header", "appbar"))).contains("আম্মা").doesNotContain("আব্বু");
    }

    /** The other direction of the sheet: with one patient there is nothing to switch to, so no sheet and no inert page. */
    @Test
    void withOnePatientThereIsNoSheetToOpen() {
        kit.familyWith(ma);
        noPapers(ma);
        assertNoSheet(Dom.parse(kit.page("/app/timeline?sheet=patient", "bn")));
    }

    /**
     * Two patients and nobody in view: {@code ?sheet=patient} is only the chooser — no sheet over a page that has no
     * patient, nothing inert, and no one's papers read.
     */
    @ParameterizedTest
    @EnumSource(value = Tab.class, names = {"CHAT", "PAPERS"})
    void withNobodyInViewTheSheetUrlIsTheChooser(Tab tab) {
        kit.familyWith(ma, baba);
        noPapers(ma);
        noPapers(baba);
        Dom dom = Dom.parse(kit.page(tab.path + "?sheet=patient", "bn"));
        assertNoSheet(dom);
        assertThat(dom.all.stream().filter(e -> e.tag().equals("h1")).map(Dom.Element::ownText)).containsExactly("কার কাগজ দেখবেন?");
        verify(kit.timeline, never()).timeline(any(), any(), any(), any());
        verify(kit.attention, never()).attention(any(), any());
    }

    /**
     * The switcher fixtures exist so that every derived page check (touch targets, glossary, no inline code, …) reads
     * the sheet. They are only worth that if they really draw it: each one has exactly one dialog and an inert page.
     */
    @Test
    void theSwitcherFixturesReallyDrawTheSheet() {
        Map<String, String> switchers = new java.util.TreeMap<>();
        RenderedPages.all().forEach((key, html) -> {
            if (key.contains(" #switcher ")) {
                switchers.put(key, html);
            }
        });
        // two tabs × two languages; fewer and the derived checks are not reading what this test says they are
        assertThat(switchers.keySet()).as("switcher pages rendered").hasSizeGreaterThanOrEqualTo(4);
        switchers.forEach((key, html) -> {
            Dom dom = Dom.parse(html);
            assertThat(dom.unclosed).as(key + " unclosed").isEmpty();
            assertSheetOpen(dom);
        });
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
        verify(kit.timeline, never()).timeline(any(), any(), any(), any());
        verify(kit.attention, never()).attention(any(), any());
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
        verify(kit.timeline, never()).timeline(any(), any(), any(), any());
        verify(kit.attention, never()).attention(any(), any());
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

    /** C3: the filter chosen reaches the read model; a type this build does not know reads every paper, never a 400. */
    @Test
    void theTypeFilterReachesTheReadModelAndAnUnknownTypeReadsEveryPaper() {
        kit.familyWith(ma);
        noPapers(ma);
        String list = Tab.PAPERS.path + "?patient=" + ma.patient().id();
        kit.page(list + "&type=rx", "bn");
        verify(kit.timeline).timeline(WebUiTestKit.FAMILY, ma.patient().id(), null, com.elioo.baymax.web.domain.TimelineFilter.PRESCRIPTION);
        kit.page(list + "&type=x", "bn");
        verify(kit.timeline).timeline(WebUiTestKit.FAMILY, ma.patient().id(), null, com.elioo.baymax.web.domain.TimelineFilter.ALL);
    }

    /** The links on a page that open the add-report sheet, as written in the HTML. */
    private static List<Dom.Element> addLinks(Dom dom) {
        return dom.all.stream().filter(e -> e.tag().equals("a") && e.attrs().getOrDefault("href", "").startsWith(UploadUiHandler.SHEET)).toList();
    }

    /**
     * s16 empty, both directions: no papers at all is the empty panel naming the patient; the owner is offered the one
     * camera link (to the sheet for this patient, coming back here), a share member is offered none anywhere on the page.
     */
    @Test
    void anEmptyListIsTheEmptyPanelAndOnlyTheOwnerIsOfferedTheCamera() {
        kit.familyWith(ma);
        noPapers(ma);
        Dom owner = Dom.parse(kit.page(Tab.PAPERS.path + "?patient=" + ma.patient().id(), "bn"));
        assertThat(owner.unclosed).isEmpty();
        Dom.Element panel = only(owner, "section", "empty");
        assertThat(subtree(panel).stream().filter(e -> e.tag().equals("h2")).map(Dom.Element::ownText))
                .containsExactly(kit.copy.t(Lang.BN, "timeline.empty.title", "আম্মা"));
        assertThat(text(panel)).contains(kit.copy.t(Lang.BN, "timeline.empty.body")).contains(kit.copy.t(Lang.BN, "timeline.empty.foot"));
        List<Dom.Element> camera = addLinks(owner);
        assertThat(camera).as("one way to add a paper on the empty page").hasSize(1);
        assertThat(subtree(panel)).as("…and it is the panel's").contains(camera.get(0));
        assertThat(text(camera.get(0))).contains(kit.copy.t(Lang.BN, "chat.empty.action"));
        String back = java.net.URLEncoder.encode(Tab.PAPERS.path + "?patient=" + ma.patient().id(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(camera.get(0).attrs().get("href")).isEqualTo(UploadUiHandler.SHEET + "?patient=" + ma.patient().id() + "&amp;back=" + back);

        PatientSummary member = kit.patient("খালা", false, 0);
        kit.familyWith(member);
        noPapers(member);
        Dom shared = Dom.parse(kit.page(Tab.PAPERS.path + "?patient=" + member.patient().id(), "bn"));
        assertThat(subtree(only(shared, "section", "empty")).stream().filter(e -> e.tag().equals("h2")).map(Dom.Element::ownText))
                .containsExactly(kit.copy.t(Lang.BN, "timeline.empty.title", "খালা"));
        assertThat(addLinks(shared).stream().map(Dom.Element::describe)).as("a share member cannot add a paper").isEmpty();
    }

    /**
     * The other direction of the empty panel: a filter that matches nothing is not "no papers yet" — the filters stay,
     * so "সব" brings everything back, and the line says only this kind is missing.
     */
    @Test
    void aFilterThatMatchesNothingKeepsTheFiltersAndIsNotTheEmptyPanel() {
        kit.familyWith(ma);
        noPapers(ma);
        Dom dom = Dom.parse(kit.page(Tab.PAPERS.path + "?patient=" + ma.patient().id() + "&type=rx", "bn"));
        assertThat(dom.all.stream().filter(e -> e.classes().contains("empty")).map(Dom.Element::describe)).as("the s16 empty panel").isEmpty();
        String words = dom.all.stream().map(Dom.Element::ownText).collect(java.util.stream.Collectors.joining(" "));
        assertThat(words).doesNotContain(kit.copy.t(Lang.BN, "timeline.empty.title", "আম্মা"))
                .contains(kit.copy.t(Lang.BN, "timeline.filter.none"));
        Dom.Element nav = only(dom, "nav", "filters");
        assertThat(subtree(nav).stream().filter(e -> e.tag().equals("a")).map(e -> e.attrs().get("href")))
                .contains(Tab.PAPERS.path + "?patient=" + ma.patient().id());
    }

    /**
     * A later page that comes back empty (a stale or hand-made "আরও দেখুন" link) is neither "no papers yet" — earlier
     * pages hold papers — nor "none of this kind" under "সব": it says only that there are no more, and keeps the filters.
     */
    @Test
    void anEmptyLaterPageIsNotTheEmptyPanelAndDoesNotBlameTheFilter() {
        kit.familyWith(ma);
        noPapers(ma);
        Dom dom = Dom.parse(kit.page(Tab.PAPERS.path + "?patient=" + ma.patient().id() + "&cursor=c2FtcGxl", "bn"));
        assertThat(dom.unclosed).isEmpty();
        assertThat(dom.all.stream().filter(e -> e.classes().contains("empty")).map(Dom.Element::describe)).as("the s16 empty panel").isEmpty();
        List<String> lines = dom.all.stream().map(Dom.Element::ownText).toList();
        assertThat(lines).as("\"none of this kind\" under \"সব\"").doesNotContain(kit.copy.t(Lang.BN, "timeline.filter.none"));
        assertThat(lines).as("the line under the filters").contains(kit.copy.t(Lang.BN, "timeline.end"))
                .doesNotContain(kit.copy.t(Lang.BN, "timeline.empty.title", "আম্মা"));
        only(dom, "nav", "filters");
    }

    /** …and a filter that matches nothing still says so, not "no more" (the two lines are not interchangeable). */
    @Test
    void aFilterThatMatchesNothingSaysSoEvenOnALaterPage() {
        kit.familyWith(ma);
        noPapers(ma);
        for (String q : List.of("&type=lab", "&type=lab&cursor=c2FtcGxl")) {
            List<String> lines = Dom.parse(kit.page(Tab.PAPERS.path + "?patient=" + ma.patient().id() + q, "bn")).all.stream()
                    .map(Dom.Element::ownText).toList();
            assertThat(lines).as(q).contains(kit.copy.t(Lang.BN, "timeline.filter.none")).doesNotContain(kit.copy.t(Lang.BN, "timeline.end"));
        }
    }

    @Test
    void initialsKeepTheirVowelSignsAndConjuncts() {
        assertThat(AppShell.initial("আম্মা")).isEqualTo("আ");
        assertThat(AppShell.initial("রুমানা")).isEqualTo("রু");
        assertThat(AppShell.initial("স্বপ্না")).isEqualTo("স্ব");
        assertThat(AppShell.initial("Ma")).isEqualTo("M");
    }

    // ---- C4: the চ্যাট attention stack (s06) and the কাগজপত্র strip -------------------------------------------------

    private static com.elioo.baymax.web.domain.AttentionItem.Urgent urgentPaper(com.elioo.baymax.outbound.domain.Urgency level, String doc) {
        return new com.elioo.baymax.web.domain.AttentionItem.Urgent(UUID.fromString(doc), level, "lab_report",
                java.time.LocalDate.parse("2026-10-12"), false, WebUiTestKit.NOW);
    }

    private void attentionOfMa(com.elioo.baymax.web.domain.AttentionItem... items) {
        when(kit.attention.attention(WebUiTestKit.FAMILY, ma.patient().id())).thenReturn(Mono.just(List.of(items)));
    }

    private static final String NOW_DOC = "5e9edf6d-0a2a-4913-8675-9d07a4100041";
    private static final String WEEK_DOC = "5e9edf6d-0a2a-4913-8675-9d07a4100042";

    /** The stack is this family's view of the patient in view: asked for with the session's family and that patient. */
    @Test
    void theChatTabShowsThePatientsAttentionStackEachCardLinkingItsPaper() {
        PatientSummary withPapers = kit.patient("আম্মা", true, 2);
        kit.familyWith(withPapers);
        attentionOfMa(urgentPaper(com.elioo.baymax.outbound.domain.Urgency.NOW, NOW_DOC),
                urgentPaper(com.elioo.baymax.outbound.domain.Urgency.THIS_WEEK, WEEK_DOC));
        Dom dom = Dom.parse(kit.page(Tab.CHAT.path, "bn"));
        assertThat(dom.unclosed).isEmpty();
        verify(kit.attention).attention(WebUiTestKit.FAMILY, ma.patient().id());
        Dom.Element now = only(dom, "section", "now");
        assertThat(now.classes()).containsExactly("attn", "now");
        assertThat(subtree(now).stream().filter(e -> e.tag().equals("a")).map(e -> e.attrs().get("href")))
                .containsExactly("/app/documents/" + NOW_DOC);
        Dom.Element soon = only(dom, "section", "soon");
        assertThat(subtree(soon).stream().filter(e -> e.tag().equals("a")).map(e -> e.attrs().get("href")))
                .containsExactly("/app/documents/" + WEEK_DOC);
        assertThat(dom.all.stream().filter(e -> e.tag().equals("h2")).map(Dom.Element::ownText))
                .contains(kit.copy.t(Lang.BN, "attn.heading", "আম্মা"));
        assertThat(dom.all.indexOf(now)).as("NOW above THIS_WEEK").isLessThan(dom.all.indexOf(soon));
    }

    /** A paper still being read reloads the চ্যাট tab without script; with nothing being read, it does not reload. */
    @Test
    void theChatTabReloadsItselfOnlyWhileAPaperIsBeingRead() {
        kit.familyWith(kit.patient("আম্মা", true, 1));
        attentionOfMa(new com.elioo.baymax.web.domain.AttentionItem.Reading(UUID.fromString(NOW_DOC), WebUiTestKit.NOW));
        assertThat(kit.page(Tab.CHAT.path, "bn")).contains("<meta http-equiv=\"refresh\" content=\"10\">");
        attentionOfMa(urgentPaper(com.elioo.baymax.outbound.domain.Urgency.NOW, NOW_DOC));
        assertThat(kit.page(Tab.CHAT.path, "bn")).doesNotContain("http-equiv=\"refresh\"");
    }

    /** A patient with no papers: the intro and the one camera, no stack read into anything and no camera twice. */
    @Test
    void theChatTabForAPatientWithNoPapersIsTheIntroAndOneCamera() {
        kit.familyWith(ma);
        Dom dom = Dom.parse(kit.page(Tab.CHAT.path, "bn"));
        assertThat(text(only(dom, "section", "empty"))).contains(kit.copy.t(Lang.BN, "timeline.empty.title", "আম্মা"));
        assertThat(dom.all.stream().filter(e -> e.tag().equals("a") && e.attrs().getOrDefault("href", "").startsWith(UploadUiHandler.SHEET)))
                .hasSize(1);
    }

    /**
     * The "এখন যা দেখার" strip on কাগজপত্র, both directions: the urgent papers only (never the retake, the follow-up or
     * the paper being read), each a link to its paper with the stored chip, above the filters; a patient with nothing
     * urgent has no strip and no heading.
     */
    @Test
    void thePapersStripShowsTheUrgentPapersOnlyAndARoutineOnlyPatientHasNone() {
        kit.familyWith(kit.patient("আম্মা", true, 2));
        String list = Tab.PAPERS.path + "?patient=" + ma.patient().id();
        when(kit.timeline.timeline(eq(WebUiTestKit.FAMILY), eq(ma.patient().id()), any(), any())).thenReturn(Mono.just(new TimelinePage(List.of(
                new com.elioo.baymax.web.domain.TimelineEntry(UUID.fromString(WEEK_DOC), java.time.LocalDate.parse("2026-10-12"), false, "lab_report",
                        null, "DONE", 3, 0, 0, 0, "k", WebUiTestKit.NOW, com.elioo.baymax.outbound.domain.Urgency.ROUTINE, 0, "released", null)), null)));
        UUID rx = UUID.randomUUID();
        attentionOfMa(urgentPaper(com.elioo.baymax.outbound.domain.Urgency.NOW, NOW_DOC),
                urgentPaper(com.elioo.baymax.outbound.domain.Urgency.THIS_WEEK, WEEK_DOC),
                new com.elioo.baymax.web.domain.AttentionItem.Retake(UUID.randomUUID(), WebUiTestKit.NOW),
                new com.elioo.baymax.web.domain.AttentionItem.FollowUp(rx, "Next visit", java.time.LocalDate.parse("2026-10-20"), "f/p/x/crop.jpg"),
                new com.elioo.baymax.web.domain.AttentionItem.Reading(UUID.randomUUID(), WebUiTestKit.NOW));
        String html = kit.page(list, "bn");
        Dom dom = Dom.parse(html);
        assertThat(dom.unclosed).isEmpty();
        Dom.Element strip = only(dom, "section", "attn-strip");
        assertThat(subtree(strip).stream().filter(e -> e.tag().equals("h2")).map(Dom.Element::ownText))
                .containsExactly(kit.copy.t(Lang.BN, "attn.strip"));
        assertThat(subtree(strip).stream().filter(e -> e.tag().equals("a")).map(e -> e.attrs().get("href")))
                .containsExactly("/app/documents/" + NOW_DOC, "/app/documents/" + WEEK_DOC);
        assertThat(subtree(strip).stream().filter(e -> e.classes().equals(java.util.Set.of("chip", "now"))).map(Dom.Element::ownText))
                .containsExactly(kit.copy.urgency(Lang.BN, com.elioo.baymax.outbound.domain.Urgency.NOW, true));
        assertThat(subtree(strip).stream().filter(e -> e.classes().equals(java.util.Set.of("chip", "soon"))).map(Dom.Element::ownText))
                .containsExactly(kit.copy.urgency(Lang.BN, com.elioo.baymax.outbound.domain.Urgency.THIS_WEEK, true));
        assertThat(html.indexOf("attn-strip")).as("the strip above the filters").isLessThan(html.indexOf("<nav class=\"filters\""));
        assertThat(text(strip)).doesNotContain("Next visit");

        // the other direction: only the routine paper and cards that are not urgent — no strip, no heading
        attentionOfMa(new com.elioo.baymax.web.domain.AttentionItem.Retake(UUID.randomUUID(), WebUiTestKit.NOW),
                new com.elioo.baymax.web.domain.AttentionItem.Reading(UUID.randomUUID(), WebUiTestKit.NOW));
        String quiet = kit.page(list, "bn");
        assertThat(quiet).contains("class=\"titem\"").doesNotContain("attn-strip").doesNotContain(kit.copy.t(Lang.BN, "attn.strip"));
    }

    /**
     * The C4 fixtures exist so that every derived page check reads every card, the empty চ্যাট and the strip. They are
     * only worth that if they really draw them, in both languages: each "stack" page has one card of every kind, each
     * "strip" page the strip, each "empty" চ্যাট page the empty panel.
     */
    @Test
    void theAttentionFixturesReallyDrawEveryCardTheEmptyChatAndTheStrip() {
        Map<String, String> pages = RenderedPages.all();
        List<String> stacks = pages.keySet().stream().filter(k -> k.startsWith("GET /app/home ") && k.contains(" #stack ")).toList();
        List<String> strips = pages.keySet().stream().filter(k -> k.startsWith("GET /app/timeline") && k.contains(" #strip ")).toList();
        List<String> empties = pages.keySet().stream().filter(k -> k.startsWith("GET /app/home ") && k.contains(" #empty ")).toList();
        assertThat(stacks).as("stack pages rendered").hasSize(2);
        assertThat(strips).as("strip pages rendered").hasSize(2);
        assertThat(empties).as("empty chat pages rendered").hasSize(2);
        for (String key : stacks) {
            Dom dom = Dom.parse(pages.get(key));
            assertThat(dom.unclosed).as(key + " unclosed").isEmpty();
            for (String kind : List.of("now", "soon", "retake", "follow", "reading")) {
                assertThat(dom.all.stream().filter(e -> e.tag().equals("section") && e.classes().equals(java.util.Set.of("attn", kind))))
                        .as(key + " card " + kind).hasSize(1);
            }
        }
        strips.forEach(key -> assertThat(Dom.parse(pages.get(key)).all.stream().filter(e -> e.classes().contains("attn-strip"))).as(key).hasSize(1));
        empties.forEach(key -> assertThat(Dom.parse(pages.get(key)).all.stream().filter(e -> e.tag().equals("section") && e.classes().contains("empty")))
                .as(key).hasSize(1));
    }
}
