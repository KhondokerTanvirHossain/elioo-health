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
        return html.substring(i, html.indexOf(end, i) + end.length());
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
        verify(kit.timeline, never()).timeline(any(), any(), any());
        verify(kit.timeline, never()).document(any(), any());
        assertThat(html).contains("কার কাগজ দেখবেন?")
                .contains("href=\"" + tab.path + "?patient=" + ma.patient().id() + "\"")
                .contains("href=\"" + tab.path + "?patient=" + baba.patient().id() + "\"");
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

    /** The চ্যাট placeholder: the composer says chat is coming and can send nothing (C4 keeps this test). */
    @Test
    void theChatComposerSaysChatIsComingAndCannotSend() {
        kit.familyWith(ma);
        String composer = between(kit.page(Tab.CHAT.path, "bn"), "<div class=\"composer\"", "</div>");
        assertThat(composer).contains(kit.copy.t(Lang.BN, "chat.soon"))
                .doesNotContain("<input").doesNotContain("<textarea").doesNotContain("<form").doesNotContain("type=\"submit\"");
    }

    @Test
    void initialsKeepTheirVowelSignsAndConjuncts() {
        assertThat(AppShell.initial("আম্মা")).isEqualTo("আ");
        assertThat(AppShell.initial("রুমানা")).isEqualTo("রু");
        assertThat(AppShell.initial("স্বপ্না")).isEqualTo("স্ব");
        assertThat(AppShell.initial("Ma")).isEqualTo("M");
    }
}
