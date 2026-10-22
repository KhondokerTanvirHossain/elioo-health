package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.web.domain.EarlierHistory;
import com.elioo.baymax.web.domain.EarlierReading;
import com.elioo.baymax.web.domain.GateState;
import com.elioo.baymax.web.domain.ResultView;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** s17 / s13: everything on the paper, each fact beside its stored crop; the sheet is a plain URL. */
class DocumentDetailRenderTest {

    private final WebUiTestKit kit = new WebUiTestKit();
    private final UiCopy copy = kit.copy;
    /** A fact's block: a value row, a medicine (both links), or a quote from the paper; each holds its own crop. */
    private static final Pattern FACT_BLOCK = Pattern.compile(
            "<a class=\"(?:vrow|med)[^\"]*\"[^>]*>.*?</a>|<figure class=\"paper\">.*?</figure>", Pattern.DOTALL);

    /** One fact of every kind the view carries: values of three verdicts, a medicine, a follow-up, two context lines. */
    private static ResultView full() {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("diagnosis", List.of(Map.of("text", "Kindly correlate clinically.", "crop_key", "f/p/d/crop-c1.jpg")));
        ctx.put("advice", List.of(Map.of("text", "Low salt diet", "crop_key", "f/p/d/crop-c2.jpg")));
        DocumentView v = ResultFixtures.paper(List.of(
                        ResultFixtures.value("HbA1c", "8.4", "%", "4.0", "6.0", "outside", "high"),
                        ResultFixtures.value("Urine Albumin", "Trace", null, "Nil", null, "unknown"),
                        ResultFixtures.value("TSH", "2.1", "µIU/mL", "0.4", "4.0", "inside")),
                List.of(Map.of("name", "Tab. Demoprol", "dose_text", "5 mg", "frequency_text", "1+0+1", "crop_key", "f/p/d/crop-m1.jpg")),
                List.of(Map.of("instruction", "Next visit: 22/10/26", "due_date", "2026-10-22", "crop_key", "f/p/d/crop-f1.jpg")),
                ctx);
        return ResultFixtures.result(v, Urgency.THIS_WEEK, List.of("value_outside_range:hba1c|stopgap"), GateState.NONE, null, ResultFixtures.BD);
    }

    /** Derived over every fact list the view carries: each item is in a block that also carries THAT item's crop. */
    @Test
    @SuppressWarnings("unchecked")
    void everyFactOnThePaperSitsBesideItsOwnStoredCrop() {
        ResultView r = full();
        String html = kit.documents.detail(Lang.BN, r);
        List<String> blocks = FACT_BLOCK.matcher(html).results().map(MatchResult::group).toList();
        List<Map<String, Object>> facts = new ArrayList<>();
        facts.addAll(r.document().values());
        facts.addAll(r.document().medicines());
        facts.addAll(r.document().followUp());
        r.document().clinicalContext().values().forEach(section -> {
            if (section instanceof List<?> items) {
                items.forEach(i -> facts.add((Map<String, Object>) i));
            }
        });
        assertThat(facts).as("facts in the view").hasSizeGreaterThanOrEqualTo(7);
        for (Map<String, Object> fact : facts) {
            String text = Html.esc(String.valueOf(fact.getOrDefault("name", fact.getOrDefault("instruction", fact.get("text")))));
            String key = "key=" + URLEncoder.encode((String) fact.get("crop_key"), StandardCharsets.UTF_8);
            assertThat(blocks).as(text + " beside " + key).anyMatch(b -> b.contains(text) && b.contains(key));
        }
        assertThat(html.split("class=\"vrow ").length - 1).as("one row per value").isEqualTo(r.document().values().size());
    }

    /** Every value row on the detail opens its own sheet, which closes back to the detail (from=all). */
    @Test
    void everyValueRowOnTheDetailOpensItsSheet() {
        ResultView r = full();
        String html = kit.documents.detail(Lang.BN, r);
        for (Map<String, Object> v : r.document().values()) {
            assertThat(html).contains("id=\"v-" + v.get("id") + "\" href=\"/app/documents/" + ResultFixtures.DOC + "/values/" + v.get("id") + "?from=all\"");
        }
    }

    /** No-JS segments: anchors only for the sections that exist; empty sections hidden. */
    @Test
    void theSegmentsAreAnchorsToTheSectionsThatExist() {
        String html = kit.documents.detail(Lang.BN, ResultFixtures.result(ResultFixtures.lab(List.of(
                ResultFixtures.value("TSH", "2.1", "µIU/mL", "0.4", "4.0", "inside"))), Urgency.ROUTINE, List.of(), GateState.NONE, null, ResultFixtures.BD));
        assertThat(ResultFixtures.section(html, "seg")).contains("href=\"#values\"").contains("href=\"#pages\"")
                .doesNotContain("href=\"#medicines\"").doesNotContain("href=\"#doctor\"");
        assertThat(html).contains("id=\"values\"").contains("id=\"pages\"").doesNotContain("id=\"medicines\"").doesNotContain("id=\"doctor\"");
        // and the other direction: a paper that has them gets their anchors and sections
        String all = kit.documents.detail(Lang.BN, full());
        assertThat(ResultFixtures.section(all, "seg")).contains("href=\"#values\"").contains("href=\"#medicines\"")
                .contains("href=\"#doctor\"").contains("href=\"#pages\"");
    }

    /** "বড় করে দেখুন" opens the page image itself, one figure per page (where the browser pinch-zooms). */
    @Test
    void everyPageIsShownWholeAndOpensItself() {
        String pages = ResultFixtures.section(kit.documents.detail(Lang.BN, full()), "pages");
        for (int n = 1; n <= 2; n++) {
            assertThat(pages).contains("id=\"page-" + n + "\"").contains("src=\"/app/documents/" + ResultFixtures.DOC + "/page/" + n + "\"")
                    .contains("href=\"/app/documents/" + ResultFixtures.DOC + "/page/" + n + "\">" + copy.t(Lang.BN, "crop.zoom") + "</a>");
        }
        assertThat(pages).doesNotContain("id=\"page-3\"");
    }

    /** The stored urgency stays on the detail page too (UrgencyBanner: "the top of every … document detail"). */
    @Test
    void theDetailPageCarriesTheStoredBanner() {
        String html = kit.documents.detail(Lang.BN, ResultFixtures.result(ResultFixtures.lab(List.of()), Urgency.NOW, List.of(), GateState.REJECTED, null, ResultFixtures.BD));
        assertThat(ResultFixtures.section(html, "urgency")).contains(copy.urgency(Lang.BN, Urgency.NOW, false));
        assertThat(html.indexOf("id=\"urgency\"")).as("the banner before the segments").isLessThan(html.indexOf("id=\"seg\""));
    }

    /** A share member never sees delete (spec §4.16). */
    @Test
    void onlyTheOwnerIsOfferedDelete() {
        ResultView owner = ResultFixtures.result(ResultFixtures.lab(List.of()), Urgency.ROUTINE, List.of(), GateState.NONE, null, ResultFixtures.BD);
        ResultView member = new ResultView(owner.document(), owner.patient(), false, owner.viewer(), owner.urgency(), List.of(),
                GateState.NONE, null, 0, 2, owner.receivedAt(), owner.now(), owner.date());
        assertThat(kit.documents.detail(Lang.BN, owner)).contains("/documents/" + ResultFixtures.DOC + "/delete")
                .contains(copy.t(Lang.BN, "detail.delete"));
        assertThat(kit.documents.detail(Lang.BN, member)).doesNotContain("/delete").doesNotContain(copy.t(Lang.BN, "detail.delete"));
    }

    /**
     * s13--unknown: no bar, the "could not match" sentence; the glossary stays hidden until D3 is signed. Over every shape a
     * drawn UNKNOWN takes, including two whose number the bar COULD place (a stored INSIDE the code cannot prove, and a
     * stored OUTSIDE with no side): the bar is withheld because the value is UNKNOWN, not because nothing parsed.
     */
    @Test
    void anUnknownValueSheetSaysItCouldNotMatchAndShowsNoGlossary() {
        List<Map<String, Object>> unknowns = List.of(
                ResultFixtures.value("Urine Albumin", "Trace", null, "Nil", null, "unknown"),
                ResultFixtures.value("Creatinine", "130", "µmol/L", "59", "104", "inside"),
                ResultFixtures.value("LDL", "190", "mg/dL", "50", "130", "outside"));
        for (Map<String, Object> v : unknowns) {
            assertThat(ValueRows.kindOf(v)).as(v.get("name") + " is drawn UNKNOWN").isEqualTo(com.elioo.baymax.outbound.domain.ValueVerdict.Kind.UNKNOWN);
            String html = kit.documents.valueSheet(Lang.BN, ResultFixtures.result(ResultFixtures.lab(List.of(v)), Urgency.ROUTINE,
                    List.of(), GateState.NONE, null, ResultFixtures.BD), (UUID) v.get("id"), EarlierHistory.NONE, "summary");
            assertThat(html).as(String.valueOf(v.get("name"))).contains(copy.t(Lang.BN, "sheet.unknown.why")).contains(copy.t(Lang.BN, "sheet.earlier.none"))
                    .contains("<span class=\"chip unknown\">")
                    .doesNotContain("<svg class=\"bar\"").doesNotContain("এই পরীক্ষা কী মাপে").doesNotContain("সাধারণ তথ্য");
            assertThat(html).contains("href=\"/app/documents/" + ResultFixtures.DOC + "#v-" + v.get("id") + "\"");   // close returns to the row
        }
        assertThat(unknowns.stream().filter(v -> com.elioo.baymax.outbound.domain.PrintedRange.pinPercent(v).isPresent()))
                .as("UNKNOWN values a bar could place: without them the no-bar check passes for the wrong reason").hasSize(2);
    }

    /**
     * A value whose name did not canonicalise has no history Medioo could look up, so the sheet says nothing about earlier
     * reports — never "this is the first time", which Medioo has not established. A named one with none does say it.
     */
    @Test
    void aValueWithNoCanonicalNameClaimsNoHistoryEitherWay() {
        Map<String, Object> unnamed = ResultFixtures.value("Serum Zeta", "4.2", "mg/dL", "3.0", "5.0", "inside");
        unnamed.remove("canonical_name");
        String html = kit.documents.valueSheet(Lang.BN, ResultFixtures.result(ResultFixtures.lab(List.of(unnamed)), Urgency.ROUTINE,
                List.of(), GateState.NONE, null, ResultFixtures.BD), (UUID) unnamed.get("id"), EarlierHistory.NONE, "summary");
        assertThat(html).doesNotContain(copy.t(Lang.BN, "sheet.earlier.none")).doesNotContain("id=\"earlier\"")
                .contains("<span class=\"val big\">4.2</span>");

        Map<String, Object> named = ResultFixtures.value("Creatinine", "1.0", "mg/dL", "0.7", "1.3", "inside");
        String first = kit.documents.valueSheet(Lang.BN, ResultFixtures.result(ResultFixtures.lab(List.of(named)), Urgency.ROUTINE,
                List.of(), GateState.NONE, null, ResultFixtures.BD), (UUID) named.get("id"), EarlierHistory.NONE, "summary");
        assertThat(ResultFixtures.section(first, "earlier")).contains(copy.t(Lang.BN, "sheet.earlier.none"));
    }

    /**
     * W6-fix (ruling R1): a known marker with no reading in this unit but one in another (µmol/L before, mg/dL now) is
     * NOT the first time, so the sheet draws no history line at all, as for a marker Medioo does not know. The other
     * direction is {@link #aValueWithNoCanonicalNameClaimsNoHistoryEitherWay}'s named value: no reading in any unit, the
     * first time.
     */
    @Test
    void aMarkerReadBeforeOnlyInAnotherUnitIsNotTheFirstTime() {
        Map<String, Object> named = ResultFixtures.value("Creatinine", "1.0", "mg/dL", "0.7", "1.3", "inside");
        String html = kit.documents.valueSheet(Lang.BN, ResultFixtures.result(ResultFixtures.lab(List.of(named)), Urgency.ROUTINE,
                List.of(), GateState.NONE, null, ResultFixtures.BD), (UUID) named.get("id"), new EarlierHistory(List.of(), true), "summary");
        assertThat(html).contains("<span class=\"val big\">1.0</span>");
        assertThat(html.contains("id=\"earlier\"")).as("an #earlier block for a marker read before only in another unit").isFalse();
        assertThat(html).doesNotContain(copy.t(Lang.BN, "sheet.earlier.none"));
    }

    /**
     * A paper that printed no date: the detail's meta line and the sheet's subtitle show the upload day marked as such (as
     * the timeline does), never an empty part; a facility only when there is one. The other direction: a printed date is
     * shown as printed and never marked as the upload day. The summary's meta line follows the same rule.
     */
    @Test
    void aPaperWithNoPrintedDateShowsItsUploadDayMarkedAsSuch() {
        Map<String, Object> v = ResultFixtures.value("TSH", "2.1", "µIU/mL", "0.4", "4.0", "inside");
        String uploadDay = copy.dateText(Lang.BN, LocalDate.of(2026, 10, 20)) + " " + copy.raw(Lang.BN, "timeline.uploaddate");
        ResultView noFacility = ResultFixtures.result(ResultFixtures.undated(null, List.of(v)), Urgency.ROUTINE, List.of(), GateState.NONE, null, ResultFixtures.BD);
        ResultView withFacility = ResultFixtures.result(ResultFixtures.undated("Shapla Diagnostics", List.of(v)), Urgency.ROUTINE, List.of(),
                GateState.NONE, null, ResultFixtures.BD);
        ResultView printed = ResultFixtures.result(ResultFixtures.lab(List.of(v)), Urgency.ROUTINE, List.of(), GateState.NONE, null, ResultFixtures.BD);

        assertThat(firstMeta(kit.documents.detail(Lang.BN, noFacility))).isEqualTo(Html.esc(uploadDay));
        assertThat(firstMeta(kit.documents.detail(Lang.BN, withFacility))).isEqualTo(Html.esc(uploadDay) + " · Shapla Diagnostics");
        assertThat(firstMeta(kit.documents.summaryBody(Lang.BN, withFacility))).isEqualTo(Html.esc(uploadDay) + " · Shapla Diagnostics");
        assertThat(firstMeta(kit.documents.detail(Lang.BN, printed))).isEqualTo(copy.date(Lang.BN, "2026-10-12") + " · Diagnostic Centre, Mirpur")
                .doesNotContain(copy.t(Lang.BN, "timeline.uploaddate"));

        String sheet = kit.documents.valueSheet(Lang.BN, noFacility, (UUID) v.get("id"), EarlierHistory.NONE, "summary");
        assertThat(sheet).contains(copy.t(Lang.BN, "sheet.of", "Ma", uploadDay, copy.raw(Lang.BN, "type.lab_report"))).doesNotContain("·  ·");
        assertThat(kit.documents.valueSheet(Lang.BN, printed, (UUID) v.get("id"), EarlierHistory.NONE, "summary"))
                .contains(copy.t(Lang.BN, "sheet.of", "Ma", copy.dateText(Lang.BN, LocalDate.of(2026, 10, 12)), copy.raw(Lang.BN, "type.lab_report")));
    }

    /** DR-28 on the detail too: follow-up lines read but not located say so under ডাক্তার লিখেছেন, even with nothing quoted. */
    @Test
    void theDetailSaysWhenFollowUpLinesCouldNotBeRead() {
        ResultView unread = ResultFixtures.result(ResultFixtures.prescription(List.of(), Map.of(), Map.of("follow_up", 2)), Urgency.ROUTINE,
                List.of(), GateState.NONE, null, ResultFixtures.BD);
        String html = kit.documents.detail(Lang.BN, unread);
        assertThat(ResultFixtures.section(html, "doctor")).contains(copy.t(Lang.BN, "next.unverified", 2));
        assertThat(ResultFixtures.section(html, "seg")).contains("href=\"#doctor\"");
        // the other direction: nothing unverified, no notice
        assertThat(ResultFixtures.section(kit.documents.detail(Lang.BN, full()), "doctor")).as("a paper with nothing unverified")
                .contains("Next visit: 22/10/26").doesNotContain("class=\"warn\"");
    }

    /** The text of the page's first meta line (the date and facility line), as rendered. */
    private static String firstMeta(String html) {
        int at = html.indexOf("<p class=\"meta\">") + "<p class=\"meta\">".length();
        return html.substring(at, html.indexOf("</p>", at));
    }

    /**
     * s13: the crop large with its zoom, the value as printed, the range, the chip and the bar from the stored verdict
     * (ValueRows.pin), and — the other direction of the unknown sheet — no "could not match" for a proven value.
     */
    @Test
    void aValueSheetShowsTheCropLargeTheValueAsPrintedTheRangeTheChipAndTheBar() {
        Map<String, Object> v = ResultFixtures.value("Creatinine", "1.0", "mg/dL", "0.7", "1.3", "inside");
        String html = kit.documents.valueSheet(Lang.BN, ResultFixtures.result(ResultFixtures.lab(List.of(v)), Urgency.ROUTINE,
                List.of(), GateState.NONE, null, ResultFixtures.BD), (UUID) v.get("id"), EarlierHistory.NONE, "all");
        String key = "key=" + URLEncoder.encode((String) v.get("crop_key"), StandardCharsets.UTF_8);
        assertThat(html).contains("<img class=\"crop big\"").contains(key).contains(copy.t(Lang.BN, "crop.zoom"))
                .contains("<span class=\"val big\">1.0</span>").contains("mg/dL")
                .contains(copy.t(Lang.BN, "value.range", "0.7", "1.3"))
                .contains("<span class=\"chip inside\">" + copy.t(Lang.BN, "value.chip.inside") + "</span>")
                .contains("<svg class=\"bar\"")
                .doesNotContain(copy.t(Lang.BN, "sheet.unknown.why"));
        assertThat(html).contains("href=\"/app/documents/" + ResultFixtures.DOC + "/all#v-" + v.get("id") + "\"");   // back to the detail's row
        assertThat(html).contains("aria-label=\"" + copy.t(Lang.BN, "sheet.close") + "\"");
    }

    /**
     * Earlier readings: each in the unit as printed, beside ITS OWN crop on ITS OWN paper (the image route checks the key
     * against that document), opening that paper's sheet; an upload-day date says so; never the "first time" line.
     */
    @Test
    void earlierReadingsSitBesideTheirOwnCropsAndOpenTheirOwnPapers() {
        Map<String, Object> v = ResultFixtures.value("Creatinine", "160", "µmol/L", "59", "104", "outside", "high");
        UUID earlierDoc = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100041");
        UUID earlierObs = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100042");
        UUID printedDoc = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100043");
        UUID printedObs = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100044");
        List<EarlierReading> earlier = List.of(
                new EarlierReading(earlierDoc, earlierObs, "140", "μmol/L", "f/p/" + earlierDoc + "/crop-1.jpg", LocalDate.of(2026, 7, 12), true),
                new EarlierReading(printedDoc, printedObs, "131", "µmol/L", "f/p/" + printedDoc + "/crop-1.jpg", LocalDate.of(2026, 4, 10), false));
        String html = kit.documents.valueSheet(Lang.BN, ResultFixtures.result(ResultFixtures.lab(List.of(v)), Urgency.THIS_WEEK,
                List.of(), GateState.NONE, null, ResultFixtures.BD), (UUID) v.get("id"), new EarlierHistory(earlier, false), "summary");
        String list = ResultFixtures.section(html, "earlier");
        assertThat(list).contains(copy.t(Lang.BN, "sheet.earlier", "µmol/L")).doesNotContain(copy.t(Lang.BN, "sheet.earlier.none"));
        List<String> rows = Pattern.compile("<a class=\"erow\".*?</a>", Pattern.DOTALL).matcher(list).results().map(MatchResult::group).toList();
        assertThat(rows).hasSize(2);
        // from=all: the detail draws every row, so the earlier paper's sheet closes onto a row that is on the page
        assertThat(rows.get(0)).contains("href=\"/app/documents/" + earlierDoc + "/values/" + earlierObs + "?from=all\"")
                .contains(PageChrome.imageHref(earlierDoc, "f/p/" + earlierDoc + "/crop-1.jpg"))
                .contains(">140<").contains("μmol/L").contains(copy.date(Lang.BN, LocalDate.of(2026, 7, 12)))
                .contains(copy.t(Lang.BN, "timeline.uploaddate"));
        assertThat(rows.get(1)).contains("href=\"/app/documents/" + printedDoc + "/values/" + printedObs + "?from=all\"")
                .contains(PageChrome.imageHref(printedDoc, "f/p/" + printedDoc + "/crop-1.jpg"))
                .contains(">131<").contains(copy.date(Lang.BN, LocalDate.of(2026, 4, 10)))
                .doesNotContain(copy.t(Lang.BN, "timeline.uploaddate"));
    }

    /** A value printed without a unit: the earlier list is headed by the unitless line, never a unit of "null" or "". */
    @Test
    void earlierReadingsOfAUnitlessValueAreHeadedWithoutAUnit() {
        Map<String, Object> v = ResultFixtures.value("Urine Albumin", "Trace", null, "Nil", null, "unknown");
        UUID earlierDoc = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100045");
        List<EarlierReading> earlier = List.of(new EarlierReading(earlierDoc, UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100046"),
                "Nil", null, "f/p/" + earlierDoc + "/crop-1.jpg", LocalDate.of(2026, 7, 12), false));
        String list = ResultFixtures.section(kit.documents.valueSheet(Lang.BN, ResultFixtures.result(ResultFixtures.lab(List.of(v)),
                Urgency.ROUTINE, List.of(), GateState.NONE, null, ResultFixtures.BD), (UUID) v.get("id"), new EarlierHistory(earlier, false), "summary"), "earlier");
        assertThat(list).contains("<h2>" + copy.t(Lang.BN, "sheet.earlier.nounit") + "</h2>").doesNotContain("null")
                .doesNotContain("<span class=\"unit\">");
    }
}
