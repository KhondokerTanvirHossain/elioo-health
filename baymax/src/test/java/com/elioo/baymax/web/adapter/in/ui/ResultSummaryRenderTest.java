package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.web.domain.GateState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/** s10 / F1 / F3: the stored urgency first, in every gate state, at both levels; model wording only when released. */
class ResultSummaryRenderTest {

    private final WebUiTestKit kit = new WebUiTestKit();
    private final UiCopy copy = kit.copy;

    /** One value on the paper, so the items list is on the page and the order checks have it to order. */
    private static final List<Map<String, Object>> VALUES =
            List.of(ResultFixtures.value("Potassium", "6.8", "mmol/L", "3.5", "5.1", null));

    private String body(Lang lang, Urgency level, GateState gate) {
        return kit.documents.summaryBody(lang, ResultFixtures.result(ResultFixtures.lab(VALUES), level,
                level == Urgency.NOW ? List.of("value_critical:potassium|stopgap") : List.of("diagnosis_present"),
                gate, gate == GateState.RELEASED ? "রিপোর্টটি পেয়েছি।" : null, ResultFixtures.BD));
    }

    static Stream<Arguments> everyLevelInEveryGateState() {
        return Stream.of(Urgency.NOW, Urgency.THIS_WEEK)
                .flatMap(l -> Arrays.stream(GateState.values()).map(g -> arguments(l, g)));
    }

    /** D5: whatever the review state, the stored line is on the page, verbatim, and is the first section of main. */
    @ParameterizedTest(name = "{0} in {1}")
    @MethodSource("everyLevelInEveryGateState")
    void theStoredUrgencyLeadsThePageInEveryGateState(Urgency level, GateState gate) {
        String html = body(Lang.BN, level, gate);
        String banner = ResultFixtures.section(html, "urgency");
        assertThat(banner).contains(copy.urgency(Lang.BN, level, false)).doesNotContain("<button").doesNotContain("dismiss");
        assertThat(banner).contains(level == Urgency.NOW ? "class=\"banner now\"" : "class=\"banner soon\"");
        int at = html.indexOf("id=\"urgency\"");
        Matcher other = Pattern.compile("<section(?![^>]*id=\"urgency\")").matcher(html);
        int others = 0;
        while (other.find()) {
            others++;
            assertThat(other.start()).as("a section before the banner").isGreaterThan(at);
        }
        if (gate != GateState.NONE) {
            assertThat(others).as("the gate line is a section after the banner, so the order check has something to order").isPositive();
        }
        // the value groups are sections too (B2), so the loop above already ordered them; the row itself is on the page
        int items = html.indexOf("<a class=\"vrow ");
        assertThat(items).as("the value row").isNotNegative();
        assertThat(items).as("the value row before the banner").isGreaterThan(at);
        assertThat(html.indexOf("6.8")).as("the value before the banner").isGreaterThan(at);
    }

    /** s10/s11 and kit §5: THIS_WEEK carries the calendar, NOW the octagon, both aria-hidden beside the verbatim line. */
    @ParameterizedTest
    @EnumSource(value = Urgency.class, names = {"NOW", "THIS_WEEK"})
    void theBannerCarriesItsLevelsIconHiddenFromScreenReaders(Urgency level) {
        String banner = ResultFixtures.section(body(Lang.BN, level, GateState.RELEASED), "urgency");
        String mine = level == Urgency.NOW ? "octagon" : "calendar";
        String theirs = level == Urgency.NOW ? "calendar" : "octagon";
        assertThat(banner).contains(Icons.svg(mine, level == Urgency.NOW ? 28 : 26)).contains("aria-hidden=\"true\"");
        assertThat(banner).doesNotContain(Icons.svg(theirs, 26)).doesNotContain(Icons.svg(theirs, 28));
    }

    @ParameterizedTest
    @EnumSource(Lang.class)
    void theBannerIsTheBundleLineInEitherLanguage(Lang lang) {
        assertThat(ResultFixtures.section(body(lang, Urgency.THIS_WEEK, GateState.RELEASED), "urgency"))
                .contains(copy.urgency(lang, Urgency.THIS_WEEK, false));
    }

    /** D27, the other direction: a routine paper carries none of the urgency lines anywhere and no status word; it is "filed". */
    static Stream<Arguments> everyLanguageInEveryGateState() {
        return Arrays.stream(Lang.values()).flatMap(l -> Arrays.stream(GateState.values()).map(g -> arguments(l, g)));
    }

    /** Every urgency.* line of this language's message bundle, derived from the file and guarded against emptiness. */
    private static List<Map.Entry<String, String>> urgencyLines(Lang lang) throws IOException {
        List<Map.Entry<String, String>> lines = BundleFiles.messages(lang).entrySet().stream()
                .filter(e -> e.getKey().startsWith("urgency.")).toList();
        assertThat(lines).as("urgency lines in messages_" + lang).hasSizeGreaterThanOrEqualTo(2);
        return lines;
    }

    @ParameterizedTest(name = "{0} in {1}")
    @MethodSource("everyLanguageInEveryGateState")
    void aRoutinePaperCarriesNoUrgencyLineAndSaysOnlyThatItWasFiled(Lang lang, GateState gate) throws IOException {
        String html = body(lang, Urgency.ROUTINE, gate);
        urgencyLines(lang).forEach(e -> assertThat(html).as(e.getKey()).doesNotContain(Html.esc(e.getValue())));
        assertThat(html).doesNotContain("id=\"urgency\"").doesNotContain("class=\"banner");
        assertThat(html).contains(copy.t(lang, "result.filed"));
        if (lang == Lang.BN) {
            for (String word : List.of("স্বাভাবিক", "চিন্তার কিছু নেই", "সব ঠিক")) {
                assertThat(html).as(word).doesNotContain(word);
            }
        }
    }

    /** A paper whose level is not known (DONE but never assessed) says neither an urgency nor that it was filed. */
    @ParameterizedTest
    @EnumSource(Lang.class)
    void anUnassessedPaperClaimsNeitherAnUrgencyNorThatItWasFiled(Lang lang) throws IOException {
        String html = body(lang, null, GateState.NONE);
        urgencyLines(lang).forEach(e -> assertThat(html).as(e.getKey()).doesNotContain(Html.esc(e.getValue())));
        assertThat(html).doesNotContain("id=\"urgency\"").doesNotContain("class=\"banner")
                .doesNotContain(copy.t(lang, "result.filed"));
    }

    /** F3: pending says a person is checking (with the configured hours); the model's text is not on the page. */
    @Test
    void pendingSaysAPersonIsCheckingAndShowsNoModelText() {
        String html = body(Lang.BN, Urgency.THIS_WEEK, GateState.PENDING);
        assertThat(ResultFixtures.section(html, "gate-pending")).contains(copy.t(Lang.BN, "result.gate.pending", 2))
                .contains("২ ঘণ্টার মধ্যে").contains(copy.t(Lang.BN, "result.review.chip"));
        // no model text here is the ResultView constructor's rule (ResultServiceTest): the view cannot carry it, so no
        // assertion on this page could fail over it; what the renderer decides is which block it draws
        assertThat(html).doesNotContain("id=\"says\"").doesNotContain("id=\"gate-withheld\"");
    }

    /** F3: a withheld explanation is never described as being checked — that would be a promise nobody keeps. */
    @ParameterizedTest
    @EnumSource(value = GateState.class, names = {"REJECTED", "FAILED_SAFETY"})
    void aWithheldExplanationSaysSoAndNeverThatItIsBeingChecked(GateState gate) {
        String html = body(Lang.BN, Urgency.NOW, gate);
        assertThat(ResultFixtures.section(html, "gate-withheld")).contains(copy.t(Lang.BN, "result.gate.withheld"));
        assertThat(html).doesNotContain(copy.t(Lang.BN, "result.review.chip")).doesNotContain("id=\"says\"")
                .doesNotContain("id=\"gate-pending\"");
    }

    /** Principle 6: the released wording is labelled as Medioo's, and carries no crop (a crop means "the paper says"). */
    @Test
    void theReleasedWordingIsLabelledAsMediooSWordsAndCarriesNoCrop() {
        String html = body(Lang.BN, Urgency.THIS_WEEK, GateState.RELEASED);
        String says = ResultFixtures.section(html, "says");
        assertThat(says).contains(copy.t(Lang.BN, "result.says.label")).contains("রিপোর্টটি পেয়েছি।").doesNotContain("<img");
        assertThat(html).doesNotContain("id=\"gate-pending\"").doesNotContain("id=\"gate-withheld\"");
    }

    /** Everything that came from the paper, the model or the family is escaped on the served summary. */
    @Test
    void everythingOnTheSummaryIsEscaped() {
        PatientProfile named = new PatientProfile(ResultFixtures.MA.id(), ResultFixtures.MA.familyId(), "<b>Ma</b>", 64,
                PatientProfile.Sex.FEMALE, List.of(), ResultFixtures.NOW, ResultFixtures.NOW);
        String html = kit.documents.summaryBody(Lang.BN, ResultFixtures.result(ResultFixtures.lab("<img src=x onerror=alert(1)>", VALUES),
                named, Urgency.THIS_WEEK, List.of(), GateState.RELEASED, "<script>alert(1)</script>", ResultFixtures.BD));
        assertThat(html).doesNotContain("<script>").doesNotContain("<img src=x").doesNotContain("<b>Ma</b>");
        assertThat(ResultFixtures.section(html, "says")).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(html).contains("&lt;img src=x onerror=alert(1)&gt;").contains("&lt;b&gt;Ma&lt;/b&gt;");
    }

    /** The patient is named on every result, before F8 grammar exists. */
    @Test
    void thePatientIsNamedInTheHeader() {
        assertThat(body(Lang.BN, Urgency.ROUTINE, GateState.NONE)).contains("<span class=\"pchip\">Ma · ৬৪ বছর</span>");
    }

    // ---- B2: the summary sections (s10) — grouped value rows, crop-first medicines, the doctor's lines, the next step ----

    /** Spec §4.10 grouping: outside first (critical before outside), then unknown, then inside (B6 collapses them). */
    @Test
    void rowsAreGroupedByVerdictAndInsideRowsComeLast() {
        var view = ResultFixtures.lab(List.of(
                ResultFixtures.value("Haemoglobin", "12.6", "g/dL", "12.0", "15.5", "inside"),
                ResultFixtures.value("WBC", "7.2", "10^3/µL", "4.0", "11.0", "inside"),
                ResultFixtures.value("Urine Albumin", "Trace", null, "Nil", null, "unknown"),
                ResultFixtures.value("HbA1c", "8.4", "%", "4.0", "6.0", "outside", "high"),
                ResultFixtures.value("Potassium", "11.0", "mmol/L", "3.5", "5.1", "critical", "high")));
        String html = kit.documents.summaryBody(Lang.BN, ResultFixtures.result(view, Urgency.NOW, List.of(), GateState.NONE, null, ResultFixtures.BD));
        assertThat(html.indexOf("id=\"v-" + id(view, "Potassium"))).isNotNegative()
                .isLessThan(html.indexOf("id=\"v-" + id(view, "HbA1c")));
        assertThat(html.indexOf("id=\"v-" + id(view, "HbA1c"))).isLessThan(html.indexOf("id=\"v-" + id(view, "Urine Albumin")));
        assertThat(html.indexOf("id=\"v-" + id(view, "Urine Albumin"))).isLessThan(html.indexOf("id=\"v-" + id(view, "Haemoglobin")));
        assertThat(ResultFixtures.section(html, "outside")).contains(copy.t(Lang.BN, "value.group.outside"))
                .contains("id=\"v-" + id(view, "Potassium")).contains("id=\"v-" + id(view, "HbA1c")).doesNotContain("vrow inside");
        assertThat(ResultFixtures.section(html, "unknown")).contains(copy.t(Lang.BN, "value.group.unknown.why"))
                .contains("id=\"v-" + id(view, "Urine Albumin"));
        assertThat(ResultFixtures.section(html, "inside")).contains(copy.t(Lang.BN, "value.group.inside"))
                .contains("id=\"v-" + id(view, "Haemoglobin")).contains("id=\"v-" + id(view, "WBC"));
    }

    /** The grouping reads the row's rendered kind: a stored INSIDE the code cannot prove sits under "check the paper". */
    @Test
    void aDemotedInsideIsGroupedWithTheUnknownsNeverTheInsides() {
        var view = ResultFixtures.lab(List.of(ResultFixtures.value("K", "6.4", "mmol/L", "3.5", "5.1", "inside")));
        String html = kit.documents.summaryBody(Lang.BN, ResultFixtures.result(view, Urgency.ROUTINE, List.of(), GateState.NONE, null, ResultFixtures.BD));
        assertThat(ResultFixtures.section(html, "unknown")).contains("id=\"v-" + id(view, "K"));
        assertThat(html).doesNotContain("id=\"inside\"");
    }

    /**
     * MedicineRow: crop first and large, caption verbatim and quiet, no colour, no status; the pharmacy notice once for
     * the section (two medicines, so "once" can fail), drawn as the warn notice (s17).
     */
    @Test
    void aMedicineIsItsCropFirstWithTheLineAsWrittenAndNoStatus() {
        var view = ResultFixtures.prescription(List.of(
                Map.of("name", "Tab. Demoprol", "dose_text", "5 mg", "frequency_text", "1+0+1", "timing_text", "খাবারের পরে",
                        "duration_text", "৩০ দিন", "crop_key", "f/p/d/crop-m1.jpg"),
                Map.of("name", "Cap. Fictomeg", "dose_text", "20 mg", "frequency_text", "1+0+0", "crop_key", "f/p/d/crop-m2.jpg")),
                Map.of(), null);
        String html = kit.documents.summaryBody(Lang.BN, ResultFixtures.result(view, Urgency.ROUTINE, List.of(), GateState.NONE, null, ResultFixtures.BD));
        String med = ResultFixtures.section(html, "medicines");
        assertThat(med).containsOnlyOnce(copy.t(Lang.BN, "med.pharmacy"))
                .contains("<p class=\"warn\">" + copy.t(Lang.BN, "med.pharmacy") + "</p>");
        // each medicine link, opened to its close: the image is its first child, the caption after it
        Matcher row = Pattern.compile("<a class=\"med\" href=\"([^\"]+)\">(.*?)</a>", Pattern.DOTALL).matcher(med);
        int rows = 0;
        while (row.find()) {
            rows++;
            String inner = row.group(2);
            assertThat(inner).as("medicine " + rows + " opens with its crop").startsWith("<img class=\"crop big\"")
                    .contains("src=\"" + row.group(1) + "\"");
            assertThat(inner.indexOf("<img class=\"crop big\"")).as("medicine " + rows + ": crop before caption")
                    .isLessThan(inner.indexOf("<span class=\"cap\">"));
        }
        assertThat(rows).as("medicine rows").isEqualTo(2);
        assertThat(med).contains("<span class=\"cap\">Tab. Demoprol · 5 mg · 1+0+1 · খাবারের পরে · ৩০ দিন</span>")
                .contains("<span class=\"cap\">Cap. Fictomeg · 20 mg · 1+0+0</span>").doesNotContain("class=\"chip");
    }

    /** A blank field leaves no gap and no stray separator: the caption joins only what was written. */
    @Test
    void aMedicineCaptionJoinsOnlyTheFieldsTheyWrote() {
        var view = ResultFixtures.prescription(List.of(Map.of("name", "Tab. Demoprol", "dose_text", " ",
                "frequency_text", "1+0+1", "crop_key", "f/p/d/crop-m1.jpg")), Map.of(), null);
        String med = ResultFixtures.section(kit.documents.summaryBody(Lang.BN, ResultFixtures.result(view, Urgency.ROUTINE, List.of(),
                GateState.NONE, null, ResultFixtures.BD)), "medicines");
        assertThat(med).contains("<span class=\"cap\">Tab. Demoprol · 1+0+1</span>");
    }

    /** DR-26 / principle 6: the doctor's words are verbatim under "কাগজে লেখা", each with its crop; never Medioo's label. */
    @Test
    void theDoctorsLinesAreQuotedFromThePaperBesideTheirCrops() {
        var ctx = new LinkedHashMap<String, Object>();
        ctx.put("diagnosis", List.of(Map.of("text", "Kindly correlate clinically.", "crop_key", "f/p/d/crop-c1.jpg")));
        // a referral written as lines with crops, so the referral skip itself is what keeps it off the page
        ctx.put("referral", List.of(Map.of("text", "Dr X, Fictional Hospital", "crop_key", "f/p/d/crop-r1.jpg")));
        var view = ResultFixtures.prescription(List.of(), ctx, null);
        String doctor = ResultFixtures.section(kit.documents.summaryBody(Lang.BN, ResultFixtures.result(view, Urgency.THIS_WEEK, List.of(),
                GateState.RELEASED, "w", ResultFixtures.BD)), "doctor");
        assertThat(doctor).contains(copy.t(Lang.BN, "doctor.title")).contains("<figcaption class=\"label\">" + copy.t(Lang.BN, "paper.label"))
                .contains("<blockquote>Kindly correlate clinically.</blockquote>").contains("crop-c1.jpg")
                .doesNotContain(copy.t(Lang.BN, "result.says.label")).doesNotContain("Dr X").doesNotContain("crop-r1.jpg");   // referral stays hidden (contextSection skips it)
    }

    /**
     * Each line from the paper sits beside ITS crop, not a neighbour's (DR-12): two diagnosis lines and two follow-ups, each
     * quote's img src is the image href of that line's own crop_key.
     */
    @Test
    void everyPaperQuoteCarriesItsOwnLinesCrop() {
        var ctx = new LinkedHashMap<String, Object>();
        ctx.put("diagnosis", List.of(Map.of("text", "Fictional dx one", "crop_key", "f/p/d/crop-c1.jpg"),
                Map.of("text", "Fictional dx two", "crop_key", "f/p/d/crop-c2.jpg")));
        var view = new DocumentView(ResultFixtures.DOC.toString(), "DONE", null, "prescription", "2026-10-12", "Demo Chamber", 0.9,
                "m", 1, List.of(), List.of(), List.of(
                Map.of("instruction", "Fictional follow-up one", "crop_key", "f/p/d/crop-f1.jpg"),
                Map.of("instruction", "Fictional follow-up two", "crop_key", "f/p/d/crop-f2.jpg")), ctx, null);
        String html = kit.documents.summaryBody(Lang.BN, ResultFixtures.result(view, Urgency.ROUTINE, List.of(), GateState.NONE, null, ResultFixtures.BD));
        Map<String, String> expected = Map.of("Fictional dx one", "f/p/d/crop-c1.jpg", "Fictional dx two", "f/p/d/crop-c2.jpg",
                "Fictional follow-up one", "f/p/d/crop-f1.jpg", "Fictional follow-up two", "f/p/d/crop-f2.jpg");
        Matcher quote = Pattern.compile("<figure class=\"paper\">.*?<img class=\"crop\"[^>]* src=\"([^\"]+)\"><blockquote>([^<]*)</blockquote>",
                Pattern.DOTALL).matcher(html);
        Map<String, String> found = new LinkedHashMap<>();
        while (quote.find()) {
            found.put(quote.group(2), quote.group(1));
        }
        assertThat(found).as("paper quotes, text to img src").hasSize(4);
        expected.forEach((text, crop) -> assertThat(found.get(text)).as(text).isEqualTo(PageChrome.imageHref(ResultFixtures.DOC, crop)));
    }

    /** No fact without its proof: a doctor's line stored without a crop is not quoted (and no empty section is drawn). */
    @Test
    void aDoctorsLineWithoutItsCropIsNotQuoted() {
        var view = ResultFixtures.prescription(List.of(), Map.of("advice", List.of(Map.of("text", "Walk daily"))), null);
        String html = kit.documents.summaryBody(Lang.BN, ResultFixtures.result(view, Urgency.ROUTINE, List.of(), GateState.NONE, null, ResultFixtures.BD));
        assertThat(html).doesNotContain("Walk daily").doesNotContain("id=\"doctor\"");
    }

    @Test
    void anUnverifiedCountSendsTheFamilyToThePaperAndShowsNoneOfThoseItems() {
        var view = ResultFixtures.prescription(List.of(), Map.of(), Map.of("values", 0, "medicines", 1, "follow_up", 0, "total", 1));
        String html = kit.documents.summaryBody(Lang.BN, ResultFixtures.result(view, Urgency.ROUTINE, List.of(), GateState.NONE, null, ResultFixtures.BD));
        assertThat(ResultFixtures.section(html, "medicines")).contains(copy.t(Lang.BN, "med.unverified", 1)).contains("১টি লাইন")
                .doesNotContain(copy.t(Lang.BN, "med.pharmacy"));
        assertThat(html).doesNotContain(copy.t(Lang.BN, "value.unverified"));
    }

    @Test
    void anUnverifiedValueSendsTheFamilyToThePaper() {
        var view = ResultFixtures.lab(List.of(ResultFixtures.value("Creatinine", "1.0", "mg/dL", "0.7", "1.3", "inside")));
        view = new DocumentView(view.documentId(), view.status(), null, view.documentType(), view.documentDate(), view.facility(),
                view.confidence(), view.model(), view.pageCount(), view.values(), List.of(), List.of(), Map.of(),
                Map.of("values", 2, "medicines", 0, "follow_up", 0, "total", 2));
        String html = kit.documents.summaryBody(Lang.BN, ResultFixtures.result(view, Urgency.ROUTINE, List.of(), GateState.NONE, null, ResultFixtures.BD));
        assertThat(html).contains(copy.t(Lang.BN, "value.unverified")).doesNotContain("id=\"medicines\"");
    }

    /** A follow-up that could not be checked sends the family to the paper in the result's voice, under পরের ধাপ. */
    @Test
    void anUnverifiedFollowUpSendsTheFamilyToThePaperUnderTheNextStep() {
        var view = ResultFixtures.prescription(List.of(), Map.of(), Map.of("values", 0, "medicines", 0, "follow_up", 1, "total", 1));
        String html = kit.documents.summaryBody(Lang.BN, ResultFixtures.result(view, Urgency.ROUTINE, List.of(), GateState.NONE, null, ResultFixtures.BD));
        assertThat(ResultFixtures.section(html, "next")).contains("<p class=\"warn\">" + copy.t(Lang.BN, "next.unverified", 1) + "</p>")
                .contains("১টি লাইন");
    }

    /** পরের ধাপ: what to bring only when there is a reason to see the doctor; each follow-up quoted beside its crop. */
    @ParameterizedTest
    @EnumSource(value = Urgency.class, names = {"NOW", "THIS_WEEK", "ROUTINE"})
    void theNextStepBringsThePaperOnlyWhenThereIsAReasonAndQuotesEachFollowUp(Urgency level) {
        var view = new DocumentView(ResultFixtures.DOC.toString(), "DONE", null, "prescription", "2026-10-12", "Demo Chamber", 0.9,
                "m", 1, List.of(), List.of(), List.of(Map.of("instruction", "Come back after 1 month", "due_date", "2026-11-15",
                "crop_key", "f/p/d/crop-f1.jpg")), Map.of(), null);
        String next = ResultFixtures.section(kit.documents.summaryBody(Lang.BN, ResultFixtures.result(view, level, List.of(),
                GateState.NONE, null, ResultFixtures.BD)), "next");
        assertThat(next).contains(copy.t(Lang.BN, "next.title")).contains("<blockquote>Come back after 1 month</blockquote>")
                .contains("crop-f1.jpg").contains(copy.t(Lang.BN, "next.followup", copy.dateText(Lang.BN, java.time.LocalDate.parse("2026-11-15"))));
        if (level == Urgency.ROUTINE) {
            assertThat(next).doesNotContain(copy.t(Lang.BN, "next.bring"));
        } else {
            assertThat(next).contains(copy.t(Lang.BN, "next.bring"));
        }
    }

    /** "প্রশ্ন? চ্যাট" is a quiet link to the chat tab, on every DONE result. */
    @Test
    void theAskLinkGoesToTheChatTab() {
        assertThat(body(Lang.BN, Urgency.ROUTINE, GateState.NONE)).contains("<a class=\"btn quiet\" href=\"" + Tab.CHAT.path + "\">"
                + copy.t(Lang.BN, "result.ask") + "</a>");
    }

    private static Object id(DocumentView view, String name) {
        return view.values().stream().filter(v -> name.equals(v.get("name"))).findFirst().orElseThrow().get("id");
    }

    /** Nothing outside says nothing about the range: no "০টি সীমার বাইরে", and never a reassurance (principle 10). */
    @Test
    void theCountsLineNamesOutsideOnlyWhenSomethingIs() {
        String none = kit.documents.summaryBody(Lang.BN, ResultFixtures.result(ResultFixtures.lab(List.of(
                ResultFixtures.value("Creatinine", "1.0", "mg/dL", "0.7", "1.3", "inside"))), Urgency.ROUTINE, List.of(), GateState.NONE, null, ResultFixtures.BD));
        assertThat(counts(none)).isEqualTo(copy.t(Lang.BN, "result.counts.none", 1));
        assertThat(none).doesNotContain("সীমার বাইরে");
        String two = kit.documents.summaryBody(Lang.BN, ResultFixtures.result(ResultFixtures.lab(List.of(
                ResultFixtures.value("HbA1c", "8.4", "%", "4.0", "6.0", "outside", "high"),
                ResultFixtures.value("Potassium", "11.0", "mmol/L", "3.5", "5.1", "critical", "high"))), Urgency.NOW, List.of(), GateState.NONE, null, ResultFixtures.BD));
        assertThat(counts(two)).isEqualTo(copy.t(Lang.BN, "result.counts", 2, 2)).isEqualTo("২টি মান · ২টি সীমার বাইরে");
    }

    /**
     * The direction that matters: a value Medioo could not place, and a stored INSIDE it could not prove, are not
     * "outside" — counted as such the line would alarm over values nobody judged. They are not "inside" either, and the
     * line claims neither: it names only how many values there are.
     */
    @Test
    void anUnknownOrDemotedValueIsNeverCountedAsOutside() {
        String html = kit.documents.summaryBody(Lang.BN, ResultFixtures.result(ResultFixtures.lab(List.of(
                ResultFixtures.value("Urine Albumin", "Trace", null, "Nil", null, "unknown"),
                ResultFixtures.value("K", "6.4", "mmol/L", "3.5", "5.1", "inside"))), Urgency.ROUTINE, List.of(), GateState.NONE, null, ResultFixtures.BD));
        assertThat(counts(html)).isEqualTo(copy.t(Lang.BN, "result.counts.none", 2));
    }

    /**
     * B1's rule on B2's rows: a stored OUTSIDE or CRITICAL with no side (a row V19 cannot hold) names no direction. It is
     * UNKNOWN — grouped under "check the paper", no bar, never counted as outside — and never a "▲" read off the pin.
     * Both rows have a two-sided range the pin could place, so a page that guessed the side from the pin fails here.
     */
    @Test
    void aStoredOutsideOrCriticalWithNoSideReadsCheckThePaper() {
        Map<String, Object> outside = ResultFixtures.value("HbA1c", "6.5", "%", "4.0", "5.6", "outside");
        Map<String, Object> critical = ResultFixtures.value("Hb", "5.0", "g/dL", "12.0", "16.0", "critical");
        var view = ResultFixtures.lab(List.of(outside, critical));
        String html = kit.documents.summaryBody(Lang.BN, ResultFixtures.result(view,
                Urgency.NOW, List.of("value_critical:hb|stopgap"), GateState.NONE, null, ResultFixtures.BD));
        for (String name : List.of("HbA1c", "Hb")) {
            assertThat(vrow(html, view, name)).as(name).startsWith("<a class=\"vrow unknown\"")
                    .contains("<span class=\"chip unknown\">" + copy.t(Lang.BN, "value.chip.unknown") + "</span>")
                    .doesNotContain("<svg").doesNotContain("▲").doesNotContain("▼");
            assertThat(ResultFixtures.section(html, "unknown")).as(name).contains("id=\"v-" + id(view, name));
        }
        assertThat(html).doesNotContain("id=\"outside\"");
        assertThat(counts(html)).isEqualTo(copy.t(Lang.BN, "result.counts.none", 2));
    }

    /**
     * The page reads what the producer writes, not a hand-built map: an ObservationEntity with stored verdict columns,
     * through the real toView(), onto the page. A key renamed on either side turns every row into "check the paper"
     * and every critical row silent, which no hand-built fixture can see. The range is one-sided, so the side on the
     * chip can only have come from the stored verdict_side.
     */
    @Test
    void aStoredVerdictReachesThePageThroughTheRealObservationView() {
        com.elioo.baymax.extraction.adapter.out.persistence.ObservationEntity hb = new com.elioo.baymax.extraction.adapter.out.persistence.ObservationEntity();
        hb.setId(java.util.UUID.randomUUID());
        hb.setName("Hb");
        hb.setCanonicalName("hb");
        hb.setValue("5.0");
        hb.setUnit("g/dL");
        hb.setRefLow("12.0");
        hb.setCropKey("f/p/d/crop-Hb.jpg");
        hb.setVerdict("critical");
        hb.setVerdictLevel("now");
        hb.setVerdictSide("low");
        hb.setVerdictReason("value_critical:hb|stopgap");
        var view = ResultFixtures.lab(List.of(hb.toView()));
        String html = kit.documents.summaryBody(Lang.BN, ResultFixtures.result(view,
                Urgency.NOW, List.of("value_critical:hb|stopgap"), GateState.NONE, null, ResultFixtures.BD));
        assertThat(vrow(html, view, "Hb")).startsWith("<a class=\"vrow critical\"")
                .contains("<span class=\"chip critical\">" + copy.t(Lang.BN, "value.chip.critical.low") + "</span>")
                .doesNotContain(copy.t(Lang.BN, "value.chip.unknown"));
        assertThat(ResultFixtures.section(html, "outside")).contains("id=\"v-" + hb.getId());
    }

    /** The one {@code <a class="vrow …">} for this value, found by its stored id: assertions read one row, never the page. */
    private static String vrow(String html, DocumentView view, String name) {
        Matcher m = Pattern.compile("<a class=\"vrow [a-z]+\" id=\"v-" + Pattern.quote(String.valueOf(id(view, name))) + "\".*?</a>").matcher(html);
        assertThat(m.find()).as("the row for " + name).isTrue();
        return m.group();
    }

    /** The counts line's own text: the one {@code <p class="counts">} element, never a search of the whole page. */
    private static String counts(String html) {
        Matcher m = Pattern.compile("<p class=\"counts\">([^<]*)</p>").matcher(html);
        assertThat(m.find()).as("the counts line").isTrue();
        String text = m.group(1);
        assertThat(m.find()).as("one counts line").isFalse();
        return text;
    }

    /** A paper with no values (a prescription) has no counts line at all: "০টি মান" says nothing a family needs. */
    @Test
    void aPaperWithNoValuesHasNoCountsLine() {
        String html = kit.documents.summaryBody(Lang.BN, ResultFixtures.result(ResultFixtures.prescription(List.of(), Map.of(), null),
                Urgency.ROUTINE, List.of(), GateState.NONE, null, ResultFixtures.BD));
        assertThat(html).doesNotContain(copy.t(Lang.BN, "result.counts.none", 0)).doesNotContain("class=\"counts\"");
    }
}
