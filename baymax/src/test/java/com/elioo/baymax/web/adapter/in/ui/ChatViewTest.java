package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.web.domain.AttentionItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** s06 / spec §4.6 and principles 3, 5, 6: what the চ্যাট tab shows, and what it must never show. */
class ChatViewTest {

    private static final Instant T = Instant.parse("2026-10-18T08:00:00Z");
    private static final UUID MA = WebUiTestKit.MA_ID;
    private final UiCopy copy = new UiCopy();
    private final ChatView view = new ChatView(copy);

    private static AttentionItem.Urgent urgent(Urgency level) {
        return new AttentionItem.Urgent(UUID.randomUUID(), level, "lab_report", LocalDate.parse("2026-10-12"), false, T);
    }

    private static long count(String html, String needle) {
        return Pattern.compile(Pattern.quote(needle)).matcher(html).results().count();
    }

    /** Principle 3: the NOW card says urgency.now verbatim, has one action, and cannot be dismissed (D14 not built). */
    @Test
    void aNowCardCarriesTheFixedLineAndOneActionAndNoWayToDismissIt() {
        AttentionItem.Urgent now = urgent(Urgency.NOW);
        String card = view.card(Lang.BN, now, "আম্মা");
        assertThat(card).contains("class=\"attn now\"").contains("<strong>" + copy.urgency(Lang.BN, Urgency.NOW, false) + "</strong>")
                .contains("১২ অক্টোবর").contains(copy.t(Lang.BN, "type.lab_report"));
        assertThat(count(card, "class=\"btn")).as("exactly one action").isEqualTo(1);
        assertThat(card).contains("<a class=\"btn primary\" href=\"/app/documents/" + now.documentId() + "\">" + copy.t(Lang.BN, "attn.open") + "</a>");
        assertThat(card).doesNotContain("দেখেছি").doesNotContain(copy.t(Lang.BN, "sheet.close")).doesNotContain("<form").doesNotContain("<button");
        String week = view.card(Lang.BN, urgent(Urgency.THIS_WEEK), "আম্মা");
        assertThat(week).contains("class=\"attn soon\"").contains("<strong>" + copy.urgency(Lang.BN, Urgency.THIS_WEEK, false) + "</strong>");
        assertThat(count(week, "class=\"btn")).isEqualTo(1);
    }

    /** Each urgent card is a region named by its level's short form, so a screen reader can jump to it by name. */
    @Test
    void anUrgentCardIsARegionNamedByItsLevel() {
        for (Urgency level : List.of(Urgency.NOW, Urgency.THIS_WEEK)) {
            Dom.Element section = Dom.parse(view.card(Lang.BN, urgent(level), "আম্মা")).root.children().get(0);
            assertThat(section.tag()).isEqualTo("section");
            assertThat(section.attrs().get("aria-label")).as(level.name()).isEqualTo(copy.urgency(Lang.BN, level, true));
        }
    }

    /** The header is the full line, never the chip's short form: the short form is a different, shorter sentence. */
    @Test
    void theNowHeaderIsTheFullLineNotTheShortForm() {
        String head = AppShellRoutesTest.between(view.card(Lang.BN, urgent(Urgency.NOW), "আম্মা"), "<div class=\"head\">", "</div>");
        assertThat(head).contains(copy.urgency(Lang.BN, Urgency.NOW, false));
        assertThat(copy.urgency(Lang.BN, Urgency.NOW, false)).as("the two forms differ, so this test can tell them apart")
                .isNotEqualTo(copy.urgency(Lang.BN, Urgency.NOW, true));
    }

    /** A paper with no printed date says its date is the upload day, never presents it as the visit's. */
    @Test
    void anUrgentPaperWithoutAPrintedDateMarksTheUploadDay() {
        String card = view.card(Lang.BN, new AttentionItem.Urgent(UUID.randomUUID(), Urgency.NOW, null, LocalDate.parse("2026-10-12"), true, T), "আম্মা");
        assertThat(card).contains(copy.t(Lang.BN, "timeline.uploaddate")).contains(copy.t(Lang.BN, "timeline.paper"));
        assertThat(view.card(Lang.BN, urgent(Urgency.NOW), "আম্মা")).doesNotContain(copy.t(Lang.BN, "timeline.uploaddate"));
    }

    /** Principles 5 and 6: the doctor's words on a paper slip with their crop; Medioo's own words never on one. */
    @Test
    void aFollowUpIsTheDoctorsWordsVerbatimBesideItsCropOnPaper() {
        UUID rx = UUID.randomUUID();
        String card = view.card(Lang.BN, new AttentionItem.FollowUp(rx, "Next visit: 20/10/26", LocalDate.parse("2026-10-20"), "f/p/" + rx + "/crop-f.jpg"), "আম্মা");
        String paper = AppShellRoutesTest.between(card, "<figure class=\"paper\">", "</figure>");
        assertThat(paper).contains(copy.t(Lang.BN, "paper.label")).contains("<blockquote>Next visit: 20/10/26</blockquote>")
                .contains("<img class=\"crop\" loading=\"lazy\" alt=\"\" src=\"/app/documents/" + rx + "/image?key=f%2Fp%2F" + rx + "%2Fcrop-f.jpg\">");
        assertThat(card).contains("class=\"attn follow\"").contains(copy.t(Lang.BN, "attn.follow.title", "২০ অক্টোবর ২০২৬"));
        assertThat(card.substring(0, card.indexOf("<figure"))).as("the doctor's words only inside the paper block")
                .doesNotContain("Next visit");
        assertThat(count(card, "class=\"btn")).isEqualTo(1);
        assertThat(card).contains("<a class=\"btn quiet\" href=\"/app/documents/" + rx + "\">");
        String greeting = view.greeting(Lang.BN, "Tanvir");
        assertThat(greeting).contains("class=\"bubble\"").contains(copy.t(Lang.BN, "chat.hello", "Tanvir"))
                .doesNotContain("class=\"paper\"").doesNotContain(copy.t(Lang.BN, "paper.label"));
        assertThat(view.greeting(Lang.BN, " ")).contains(copy.t(Lang.BN, "chat.hello.plain"));
    }

    /** A follow-up with no crop is never drawn: no doctor's words without their source. */
    @Test
    void aFollowUpWithoutItsCropIsRefused() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> view.card(Lang.BN,
                new AttentionItem.FollowUp(UUID.randomUUID(), "Next visit", LocalDate.parse("2026-10-20"), " "), "আম্মা"))
                .isInstanceOf(IllegalStateException.class);
    }

    /** The retake and the being-read cards: their own titles, one quiet-or-outlined action each, no level colour. */
    @Test
    void theRetakeAndReadingCardsSayWhereThePaperIsWithOneActionAndNoLevel() {
        UUID bad = UUID.randomUUID();
        String retake = view.card(Lang.BN, new AttentionItem.Retake(bad, java.time.LocalDate.parse("2026-10-20"), true, T), "আম্মা");
        assertThat(retake).contains("class=\"attn retake\"").contains(copy.t(Lang.BN, "attn.retake.title"))
                .contains(copy.t(Lang.BN, "timeline.sent", "১৮ অক্টোবর"))
                .contains("<a class=\"btn outlined\" href=\"/app/documents/" + bad + "\">" + copy.t(Lang.BN, "attn.open") + "</a>");
        UUID reading = UUID.randomUUID();
        String card = view.card(Lang.BN, new AttentionItem.Reading(reading, java.time.LocalDate.parse("2026-10-20"), true, T), "আম্মা");
        assertThat(card).contains("class=\"attn reading\"").contains(copy.t(Lang.BN, "attn.reading.title"))
                .contains("<a class=\"btn quiet\" href=\"/app/documents/" + reading + "\">" + copy.t(Lang.BN, "attn.reading.action") + "</a>");
        for (String c : List.of(retake, card)) {
            assertThat(count(c, "class=\"btn")).isEqualTo(1);
            assertThat(c).doesNotContain(copy.urgency(Lang.BN, Urgency.NOW, false)).doesNotContain(copy.urgency(Lang.BN, Urgency.THIS_WEEK, false))
                    .doesNotContain("chip now").doesNotContain("chip soon");
        }
    }

    /** The stack: the heading names the patient, then the cards in the order given (the service's). */
    @Test
    void theStackNamesThePatientAndKeepsTheOrderItWasGiven() {
        AttentionItem.Urgent now = urgent(Urgency.NOW);
        AttentionItem.Urgent week = urgent(Urgency.THIS_WEEK);
        String stack = view.stack(Lang.BN, List.of(now, week, new AttentionItem.Reading(UUID.randomUUID(), java.time.LocalDate.parse("2026-10-20"), true, T)), "আম্মা");
        assertThat(stack).startsWith("<h2>" + "আম্মার জন্য এখন যা দেখার" + "</h2><div class=\"attn-stack\">");
        assertThat(stack.indexOf(now.documentId().toString())).isLessThan(stack.indexOf(week.documentId().toString()));
        assertThat(stack.indexOf("attn soon")).isLessThan(stack.indexOf("attn reading"));
    }

    /** The composer says chat is coming and cannot send; its camera opens the one upload entry, for the owner only. */
    @Test
    void thePlaceholderComposerSaysChatIsComingCannotSendAndItsCameraOpensTheAddSheet() {
        String composer = view.composer(Lang.BN, true, MA);
        assertThat(composer).contains(copy.t(Lang.BN, "chat.soon"))
                .doesNotContain("<input").doesNotContain("<textarea").doesNotContain("<form").doesNotContain("type=\"submit\"")
                .doesNotContain("<button").doesNotContain("contenteditable");
        String back = java.net.URLEncoder.encode(Tab.CHAT.path + "?patient=" + MA, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(composer).contains("<a class=\"icon-btn tonal\" href=\"/app/add?patient=" + MA + "&amp;back=" + back
                + "\" aria-label=\"" + copy.t(Lang.BN, "chat.camera") + "\">");
        assertThat(view.composer(Lang.BN, false, MA)).doesNotContain("/app/add").contains(copy.t(Lang.BN, "chat.soon"));
    }

    /**
     * An empty stack is silence, not reassurance. Asserted on the structure, not on a list of phrases someone thought of:
     * between the greeting and the composer there is the link to the papers and nothing else — no heading, no card, no
     * second bubble, whatever its words.
     */
    @Test
    void nothingToAttendToShowsNoHeadingAndNoReassurance() {
        assertThat(view.stack(Lang.BN, List.of(), "আম্মা")).isEmpty();
        String html = view.page(Lang.BN, "Tanvir", "আম্মা", MA, List.of(), 3, true);
        Dom dom = Dom.parse(html);
        assertThat(dom.unclosed).isEmpty();
        List<Dom.Element> top = dom.root.children();
        assertThat(top).extracting(e -> e.tag() + "." + String.join(".", e.classes()))
                .as("greeting, the papers link, the composer — nothing between").containsExactly("p.bubble", "p.", "div.composer");
        assertThat(top.get(0).ownText()).isEqualTo(copy.t(Lang.BN, "chat.hello", "Tanvir"));
        assertThat(top.get(1).ownText()).as("the link's paragraph carries no words of its own").isEmpty();
        assertThat(top.get(1).children()).singleElement().satisfies(a -> {
            assertThat(a.tag()).isEqualTo("a");
            assertThat(a.attrs().get("href")).isEqualTo(Tab.PAPERS.path + "?patient=" + MA);
            assertThat(a.ownText()).isEqualTo("আম্মার কাগজপত্র দেখুন");   // F8: the possessive, built in code
        });
        assertThat(html).contains(copy.t(Lang.BN, "chat.soon"));
        // the other direction: with something to attend to, the heading and the card are there
        assertThat(view.page(Lang.BN, "Tanvir", "আম্মা", MA, List.of(urgent(Urgency.NOW)), 3, true))
                .contains("আম্মার জন্য এখন যা দেখার").contains("class=\"attn now\"");
    }

    @Test
    void aPatientWithNoPapersGetsTheIntroAndOneAction() {
        String html = view.page(Lang.BN, "Tanvir", "আম্মা", MA, List.of(), 0, true);
        assertThat(html).contains(copy.t(Lang.BN, "chat.intro")).contains("আম্মার কোনো কাগজ এখনো নেই");
        assertThat(count(html, "class=\"btn primary")).isEqualTo(1);
        assertThat(count(html, "href=\"/app/add?")).as("one way to add the first paper").isEqualTo(1);
        // a share member cannot add a paper: the same page without any way to
        String member = view.page(Lang.BN, "Tanvir", "আম্মা", MA, List.of(), 0, false);
        assertThat(member).contains(copy.t(Lang.BN, "chat.intro")).doesNotContain("/app/add");
    }
}
