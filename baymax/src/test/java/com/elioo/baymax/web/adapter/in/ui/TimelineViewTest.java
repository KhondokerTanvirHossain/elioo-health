package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.web.domain.TimelineEntry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** s16 / spec §4.15: what one paper's row shows, counted per row — never a substring of the whole page. */
class TimelineViewTest {

    private static final LocalDate OCT_12 = LocalDate.parse("2026-10-12");
    private static final Map<String, String> MESSAGES_BN = messages();
    private final UiCopy copy = new UiCopy();
    private final TimelineView view = new TimelineView(copy);

    private static Map<String, String> messages() {
        return messages(Lang.BN);
    }

    private static Map<String, String> messages(Lang lang) {
        try {
            return BundleFiles.messages(lang);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static TimelineEntry entry(String status, Urgency level, boolean fallback) {
        return entry(status, level, fallback, "lab_report", null, null);
    }

    private static TimelineEntry entry(String status, Urgency level, boolean fallback, String type, String review, String retakeCode) {
        return new TimelineEntry(UUID.randomUUID(), OCT_12, fallback, type, "Diagnostic Centre, Mirpur", status,
                14, 3, 0, 0, "k", Instant.parse("2026-10-12T08:00:00Z"), level, 0, review, retakeCode);
    }

    private static List<String> chips(String html) {
        return Pattern.compile("class=\"(chip [a-z]+)\"").matcher(html).results().map(r -> r.group(1)).toList();
    }

    /** D27 + principle 3, both directions: NOW and THIS_WEEK carry their chip; ROUTINE and unassessed carry nothing at all. */
    @Test
    void aTimelineEntryShowsTheStoredUrgencyChipAndRoutineShowsNone() {
        String now = view.item(Lang.BN, entry("DONE", Urgency.NOW, false), "আম্মা");
        String week = view.item(Lang.BN, entry("DONE", Urgency.THIS_WEEK, false), "আম্মা");
        assertThat(chips(now)).containsExactly("chip now");
        assertThat(chips(week)).containsExactly("chip soon");
        for (Urgency quiet : new Urgency[] {Urgency.ROUTINE, null}) {
            String row = view.item(Lang.BN, entry("DONE", quiet, false), "আম্মা");
            assertThat(chips(row)).as(quiet + ": no chip, no colour, no word").isEmpty();
            assertThat(row).as(String.valueOf(quiet)).doesNotContain("class=\"chips\"")
                    .doesNotContain(MESSAGES_BN.get("urgency.now.short"))
                    .doesNotContain(MESSAGES_BN.get("urgency.this_week.short"));
        }
    }

    /** The text of the row's one chip of this level, exactly — a substring would pass for a longer line around it. */
    private static String chipText(String row, String level) {
        List<Dom.Element> found = Dom.parse(row).all.stream().filter(e -> e.classes().equals(java.util.Set.of("chip", level))).toList();
        assertThat(found).as("one chip " + level).hasSize(1);
        return found.get(0).ownText();
    }

    /** The chip text is the bundle's short form verbatim — read from the file WhatsApp uses, not retyped here. */
    @Test
    void theChipSaysTheBundlesShortFormVerbatim() {
        for (Lang lang : Lang.values()) {
            Map<String, String> bundle = lang == Lang.BN ? MESSAGES_BN : messages(lang);
            assertThat(bundle.get("urgency.now.short")).as(lang + " now").isNotBlank();
            assertThat(chipText(view.item(lang, entry("DONE", Urgency.NOW, false), "Ma"), "now")).as(lang + " now")
                    .isEqualTo(Html.esc(bundle.get("urgency.now.short")));
            assertThat(chipText(view.item(lang, entry("DONE", Urgency.THIS_WEEK, false), "Ma"), "soon")).as(lang + " this week")
                    .isEqualTo(Html.esc(bundle.get("urgency.this_week.short")));
        }
    }

    /** D5: the review state sits beside the level, never instead of it; a released paper has no review chip. */
    @Test
    void aPendingReviewChipSitsBesideTheLevelAndAReleasedPaperHasNone() {
        assertThat(chips(view.item(Lang.BN, entry("DONE", Urgency.NOW, false, "lab_report", "pending", null), "আম্মা")))
                .containsExactly("chip now", "chip review");
        assertThat(chips(view.item(Lang.BN, entry("DONE", Urgency.NOW, false, "lab_report", "released", null), "আম্মা")))
                .containsExactly("chip now");
        assertThat(chips(view.item(Lang.BN, entry("DONE", Urgency.ROUTINE, false, "lab_report", "pending", null), "আম্মা")))
                .as("pending on a routine paper: the review chip alone, no level").containsExactly("chip review");
    }

    /** The row's title element, its own text exactly: "কাগজ · আম্মা" is a substring of "নতুন কাগজ · আম্মা". */
    private static String title(String row) {
        List<Dom.Element> found = Dom.parse(row).all.stream().filter(e -> e.classes().contains("ttitle")).toList();
        assertThat(found).as("one title in the row").hasSize(1);
        return found.get(0).ownText();
    }

    @Test
    void aPaperThatIsNotDoneSaysWhereItIsAndCarriesNoUrgency() {
        String reading = copy.t(Lang.BN, "timeline.reading.title") + " · আম্মা";
        String paper = copy.t(Lang.BN, "timeline.paper") + " · আম্মা";
        assertThat(reading).isEqualTo("নতুন কাগজ · আম্মা");
        assertThat(paper).isEqualTo("কাগজ · আম্মা");
        for (String s : List.of("RECEIVED", "PROCESSING")) {
            String row = view.item(Lang.BN, entry(s, null, true), "আম্মা");
            assertThat(title(row)).as(s).isEqualTo(reading);
            assertThat(row).as(s).contains("পড়ছি…");
        }
        // a retake or a failure is not "new": it was read and could not be used, and its title must not say otherwise
        for (String s : List.of("NEEDS_RETAKE", "FAILED")) {
            String row = view.item(Lang.BN, entry(s, null, true), "আম্মা");
            assertThat(title(row)).as(s).isEqualTo(paper);
            assertThat(row).as(s).doesNotContain("নতুন কাগজ").doesNotContain("পড়ছি…");
        }
        assertThat(view.item(Lang.BN, entry("NEEDS_RETAKE", null, true), "আম্মা")).contains("ঠিকমতো পড়তে পারিনি — আবার তুলতে হবে");
        assertThat(view.item(Lang.BN, entry("FAILED", null, true), "আম্মা")).contains("পড়া যায়নি — আবার পাঠান");
        for (String s : List.of("RECEIVED", "PROCESSING", "NEEDS_RETAKE", "FAILED")) {
            // a stored level on a paper that is not DONE (it cannot have one, but the row must not trust that) shows nothing
            String row = view.item(Lang.BN, entry(s, Urgency.NOW, true, "lab_report", "pending", null), "আম্মা");
            assertThat(chips(row)).as(s).isEmpty();
            assertThat(row).as(s).doesNotContain(MESSAGES_BN.get("urgency.now.short")).doesNotContain("১৪টি মান");
        }
    }

    /** F5: a retake says why in Bangla, from the stored code — the section's own line where one exists. */
    @Test
    void aRetakeRowSaysWhyFromTheStoredCode() {
        assertThat(view.item(Lang.BN, entry("NEEDS_RETAKE", null, true, null, null, "expected_section_empty:values"), "আম্মা"))
                .contains(copy.t(Lang.BN, "retake.reason.expected_section_empty.values"));
        assertThat(view.item(Lang.BN, entry("NEEDS_RETAKE", null, true, null, null, "low_overall"), "আম্মা"))
                .contains(copy.t(Lang.BN, "retake.reason.low_overall"))
                .doesNotContain(copy.t(Lang.BN, "retake.reason.expected_section_empty.values"));
    }

    /** Rule 13: UI dates and counts in Bangla digits with full month names; the facility as printed; English unchanged. */
    @Test
    void monthsDatesAndCountsAreBanglaAndTheUploadDateIsMarked() {
        String page = view.items(Lang.BN, List.of(entry("DONE", Urgency.ROUTINE, false), entry("DONE", Urgency.ROUTINE, true)), "আম্মা");
        assertThat(Pattern.compile("<h2>অক্টোবর ২০২৬</h2>").matcher(page).results().count()).as("one header per month").isEqualTo(1);
        assertThat(page).contains("১২ অক্টোবর · Diagnostic Centre, Mirpur").contains("১৪টি মান").contains("১২ অক্টোবর (আপলোডের তারিখ) · ");
        String en = view.items(Lang.EN, List.of(entry("DONE", Urgency.ROUTINE, false)), "Ma");
        assertThat(en).contains("<h2>October 2026</h2>").contains("2026-10-12 · Diagnostic Centre, Mirpur").contains("14 values");
    }

    /** A month header opens each new month, in the order given, and every section it opens is closed. */
    @Test
    void eachMonthHasOneHeaderInOrderAndTheListIsWellFormed() {
        TimelineEntry oct = entry("DONE", Urgency.ROUTINE, false);
        TimelineEntry sep = new TimelineEntry(UUID.randomUUID(), LocalDate.parse("2026-09-21"), true, "prescription", null, "DONE",
                0, 2, 0, 0, "k", Instant.parse("2026-09-21T08:00:00Z"), null, 0, null, null);
        String html = view.items(Lang.BN, List.of(oct, oct, sep), "আম্মা");
        Dom dom = Dom.parse(html);
        assertThat(dom.unclosed).isEmpty();
        assertThat(dom.all.stream().filter(e -> e.tag().equals("h2")).map(Dom.Element::ownText)).containsExactly("অক্টোবর ২০২৬", "সেপ্টেম্বর ২০২৬");
        assertThat(dom.all.stream().filter(e -> e.classes().contains("titem")).count()).isEqualTo(3);
        assertThat(view.items(Lang.BN, List.of(), "আম্মা")).isEmpty();
    }

    /**
     * The month header is the paper's own date, never the day it was uploaded: an old paper sent today sits under its
     * own month and year — on a Bangla page the row shows only "১২ মার্চ", so the header is the only place the year is.
     * The other direction: a paper with no readable date sits under its upload month (its date is the upload day, marked).
     */
    @Test
    void theMonthHeaderIsThePapersOwnDateNotItsUploadDay() {
        Instant uploadedToday = Instant.parse("2026-10-12T08:00:00Z");
        TimelineEntry old = new TimelineEntry(UUID.randomUUID(), LocalDate.parse("2019-03-12"), false, "lab_report", null, "DONE",
                3, 0, 0, 0, "k", uploadedToday, null, 0, null, null);
        Dom dom = Dom.parse(view.items(Lang.BN, List.of(old), "আম্মা"));
        assertThat(dom.all.stream().filter(e -> e.tag().equals("h2")).map(Dom.Element::ownText)).containsExactly("মার্চ ২০১৯");
        assertThat(dom.all.stream().map(Dom.Element::ownText)).noneMatch(t -> t.contains("অক্টোবর"));
        assertThat(dom.all.stream().filter(e -> e.classes().contains("meta")).map(Dom.Element::ownText)).contains("১২ মার্চ");

        TimelineEntry undated = new TimelineEntry(UUID.randomUUID(), LocalDate.parse("2026-10-12"), true, "lab_report", null, "DONE",
                3, 0, 0, 0, "k", uploadedToday, null, 0, null, null);
        assertThat(Dom.parse(view.items(Lang.BN, List.of(undated, old), "আম্মা")).all.stream().filter(e -> e.tag().equals("h2"))
                .map(Dom.Element::ownText)).containsExactly("অক্টোবর ২০২৬", "মার্চ ২০১৯");
        assertThat(Dom.parse(view.items(Lang.EN, List.of(old), "Ma")).all.stream().filter(e -> e.tag().equals("h2"))
                .map(Dom.Element::ownText)).containsExactly("March 2019");
    }

    @Test
    void theWholeRowIsOneLinkToThePaper() {
        TimelineEntry e = entry("DONE", Urgency.NOW, false);
        String row = view.item(Lang.BN, e, "আম্মা");
        assertThat(row).startsWith("<a class=\"titem\" href=\"/app/documents/" + e.documentId() + "\">").endsWith("</a>");
        assertThat(Pattern.compile("<a\\b").matcher(row).results().count()).as("no link inside the row's link").isEqualTo(1);
    }

    /** Kit §14: a drawn paper with its LAB/Rx badge (Latin pictogram text), and no picture per row. */
    @Test
    void theThumbIsDrawnWithItsBadgeAndNoImage() {
        assertThat(view.item(Lang.BN, entry("DONE", null, false, "lab_report", null, null), "আম্মা")).contains(">LAB</span>").doesNotContain("<img");
        assertThat(view.item(Lang.BN, entry("DONE", null, false, "imaging_report", null, null), "আম্মা")).contains(">LAB</span>");
        assertThat(view.item(Lang.BN, entry("DONE", null, false, "prescription", null, null), "আম্মা")).contains(">Rx</span>");
        String other = view.item(Lang.BN, entry("DONE", null, false, "discharge_summary", null, null), "আম্মা");
        assertThat(other).doesNotContain(">LAB<").doesNotContain(">Rx<").contains("class=\"thumb\"");
    }
}
