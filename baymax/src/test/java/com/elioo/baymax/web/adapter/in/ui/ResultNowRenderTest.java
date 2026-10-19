package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.extraction.domain.VerifiedItems;
import com.elioo.baymax.outbound.application.service.DocumentFacts;
import com.elioo.baymax.outbound.application.service.UrgencyService;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.outbound.domain.UrgencyAssessment;
import com.elioo.baymax.outbound.domain.UrgencyReason;
import com.elioo.baymax.outbound.domain.ValueVerdict;
import com.elioo.baymax.web.domain.GateState;
import com.elioo.baymax.web.domain.ViewerLocale;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** s11 / spec §4.11: one block per reason, derived from the rule's own enum, and an action that depends on the reader. */
class ResultNowRenderTest {

    private final WebUiTestKit kit = new WebUiTestKit();
    private final UiCopy copy = kit.copy;
    /** The rule itself, so a reason list a test feeds the page is one the rule can actually store. */
    private final UrgencyService rule = new UrgencyService(new BaymaxProperties(), Clock.fixed(ResultFixtures.NOW, ZoneOffset.UTC));

    private String now(List<String> reasons, ViewerLocale viewer) {
        var view = ResultFixtures.lab(List.of(ResultFixtures.value("Potassium", "11.0", "mmol/L", "3.5", "5.1", "critical")));
        return kit.documents.summaryBody(Lang.BN, ResultFixtures.result(view, Urgency.NOW, reasons, GateState.PENDING, null, viewer));
    }

    private String now(DocumentView view, List<String> reasons, ViewerLocale viewer) {
        return kit.documents.summaryBody(Lang.BN, ResultFixtures.result(view, Urgency.NOW, reasons, GateState.NONE, null, viewer));
    }

    /** Derived: every reason the rule can raise has its block — the enum is the rule's own list (UrgencyAssessment.raise refuses others). */
    @Test
    void everyReasonTheRuleCanRaiseHasItsOwnBlock() {
        assertThat(EnumSet.allOf(UrgencyReason.class)).as("the rule's reasons").hasSizeGreaterThanOrEqualTo(7);
        assertThat(DocumentPages.REASON_BLOCKS.keySet()).isEqualTo(EnumSet.allOf(UrgencyReason.class));
    }

    /**
     * Derived from the rule, not written down: the reasons the page draws first (spec §4.11, "THIS_WEEK reasons shown below
     * if present") are exactly the ones UrgencyService raises a paper to NOW with. One paper per reason, each raising
     * that reason alone; a reason with no paper here fails, so a reason added later must say which group it is in.
     */
    @Test
    void theReasonsDrawnFirstAreExactlyTheOnesTheRuleRaisesToNow() {
        Map<UrgencyReason, DocumentFacts> papers = onePaperPerReason();
        assertThat(papers.keySet()).as("a paper for every reason").isEqualTo(EnumSet.allOf(UrgencyReason.class));
        EnumSet<UrgencyReason> raisedToNow = EnumSet.noneOf(UrgencyReason.class);
        papers.forEach((reason, paper) -> {
            UrgencyAssessment a = rule.assess(paper);
            assertThat(a.reasons()).as(reason + "'s paper raises that reason alone")
                    .isNotEmpty().allSatisfy(r -> assertThat(UrgencyReason.ofReason(r)).contains(reason));
            if (a.level() == Urgency.NOW) {
                raisedToNow.add(reason);
            }
        });
        assertThat(raisedToNow).as("the rule's NOW reasons").isNotEmpty().isNotEqualTo(EnumSet.allOf(UrgencyReason.class));
        assertThat(DocumentPages.NOW_REASONS).isEqualTo(raisedToNow);
    }

    /**
     * The order a real NOW paper is stored in, from the rule: values in printed order (Sodium, outside, printed before
     * Potassium, critical), then the diagnosis, and the phrase always last. The page draws the NOW reasons first in
     * stored order (critical, phrase), then the THIS_WEEK ones (outside, diagnosis), then the red-flag questions. And
     * Sodium stays under "যেগুলো সীমার বাইরে" below, as s11 draws it: only the critical row moves up.
     */
    @Test
    void aRealNowPaperDrawsItsNowReasonsFirstAndKeepsItsOutsideRowsInTheirGroup() {
        Map<String, Object> ctx = Map.of("diagnosis", List.of(Map.of("text", "CKD", "crop_key", "f/p/d/crop-dx.jpg")));
        List<Map<String, Object>> rows = List.of(ResultFixtures.value("Sodium", "131", "mmol/L", "135", "145", null),
                ResultFixtures.value("Potassium", "11.0", "mmol/L", "3.5", "5.1", null));
        UrgencyAssessment a = rule.assess(facts("lab_report", rows, List.of(), ctx, "{\"free_text_summary\":\"URGENT please contact the physician\"}"));
        List<String> stored = a.reasons();
        assertThat(a.level()).isEqualTo(Urgency.NOW);
        // the guard against emptiness: the stored order really is not the drawn order, so the page has to reorder it
        assertThat(stored.indexOf("text_emergency_phrase")).isGreaterThan(stored.indexOf("diagnosis_present"))
                .isGreaterThan(stored.indexOf("value_outside_range:sodium|stopgap"));
        assertThat(stored.indexOf("value_outside_range:sodium|stopgap")).isNotNegative().isLessThan(stored.indexOf("value_critical:potassium|stopgap"));

        DocumentView view = ResultFixtures.lab("Diagnostic Centre, Mirpur", withVerdicts(rows, a), List.of(), ctx);
        String html = now(view, stored, ResultFixtures.BD);
        List<String> drawn = blocksInOrder(html);
        assertThat(drawn).containsExactly("why-value-critical", "why-emergency-phrase", "why-value-outside", "why-diagnosis", "red-flags");
        assertThat(ResultFixtures.section(html, "why-value-critical")).contains("id=\"v-" + id(view, "Potassium") + "\"").doesNotContain("Sodium");
        assertThat(html).containsOnlyOnce("id=\"v-" + id(view, "Potassium") + "\"").containsOnlyOnce("id=\"v-" + id(view, "Sodium") + "\"");
        assertThat(ResultFixtures.section(html, "outside")).contains("id=\"v-" + id(view, "Sodium") + "\"").doesNotContain("Potassium");
        String outside = ResultFixtures.section(html, "why-value-outside");
        assertThat(outside).contains("href=\"#outside\"").doesNotContain("class=\"vrow").doesNotContain("/page/");
    }

    /** Inside each group the stored order holds, and two reasons with one prefix are one block (an id has one target). */
    @Test
    void insideEachGroupTheStoredOrderHoldsAndOnePrefixIsOneBlock() {
        var view = ResultFixtures.lab("Diagnostic Centre, Mirpur",
                List.of(ResultFixtures.value("Potassium", "11.0", "mmol/L", "3.5", "5.1", "critical", "high"),
                        ResultFixtures.value("Sodium", "131", "mmol/L", "135", "145", "outside", "low")),
                List.of(Map.of("instruction", "আবার দেখাবেন", "due_date", "2026-10-01", "crop_key", "f/p/d/crop-fu.jpg")),
                Map.of("diagnosis", List.of(Map.of("text", "CKD", "crop_key", "f/p/d/crop-dx.jpg"))));
        // the shape the rule stores: value rows, unverified values, follow-up, diagnosis, phrase
        String html = now(view, List.of("value_outside_range:sodium|stopgap", "value_critical:potassium|stopgap", "value_critical:calcium|stopgap",
                "unverified_value_critical:hb", "unverified_value_outside_range:wbc", "follow_up_past_due", "diagnosis_present",
                "text_emergency_phrase"), ResultFixtures.BD);
        assertThat(html).containsOnlyOnce("id=\"why-value-critical\"");
        assertThat(blocksInOrder(html)).containsExactly("why-value-critical", "why-unverified-critical", "why-emergency-phrase",
                "why-value-outside", "why-unverified-outside", "why-follow-up", "why-diagnosis", "red-flags");
        assertThat(html.indexOf("id=\"urgency\"")).isLessThan(html.indexOf("id=\"why-value-critical\""));
    }

    /** Assert the block, not "a red banner": each reason renders its own id, and only its own. */
    @ParameterizedTest
    @EnumSource(UrgencyReason.class)
    void eachReasonRendersItsOwnBlockAndNoOther(UrgencyReason reason) {
        String html = now(List.of(reason.of(reason.name().startsWith("VALUE") || reason.name().startsWith("UNVERIFIED") ? "potassium" : null,
                reason.name().startsWith("VALUE") ? "|stopgap" : null)), ResultFixtures.BD);
        String mine = DocumentPages.REASON_BLOCKS.get(reason);
        assertThat(html).contains("id=\"" + mine + "\"");
        DocumentPages.REASON_BLOCKS.values().stream().filter(b -> !b.equals(mine))
                .forEach(other -> assertThat(html).as(other).doesNotContain("id=\"" + other + "\""));
    }

    /**
     * A stored reason whose prefix the enum does not know (a legacy row) gets the generic block — never nothing — alone
     * and beside a known one (where the no-reason fallback cannot stand in for it), among the NOW reasons: its level is
     * unknown, so it is not demoted below one.
     */
    @Test
    void anUnknownStoredReasonGetsTheGenericBlock() {
        String block = ResultFixtures.section(now(List.of("retired_rule_2025:potassium"), ResultFixtures.BD), "why-other");
        assertThat(block).contains(copy.t(Lang.BN, "now.why.other")).contains("/page/1");
        String mixed = now(List.of("diagnosis_present", "text_emergency_phrase", "retired_rule_2025:potassium"), ResultFixtures.BD);
        assertThat(ResultFixtures.section(mixed, "why-other")).contains(copy.t(Lang.BN, "now.why.other"));
        assertThat(blocksInOrder(mixed)).containsExactly("why-emergency-phrase", "why-other", "why-diagnosis", "red-flags");
    }

    /** A NOW stored with no reason at all (a row from before reasons were kept) is not a NOW page with nothing to show. */
    @Test
    void aNowWithNoStoredReasonGetsTheGenericBlock() {
        String block = ResultFixtures.section(now(List.of(), ResultFixtures.BD), "why-other");
        assertThat(block).contains(copy.t(Lang.BN, "now.why.other")).contains("/page/1").contains("/page/2");
    }

    /**
     * A NOW stored with only THIS_WEEK reasons (a legacy row: the rule never stores one) would explain nothing about why
     * it is NOW: the generic block comes first. The other direction: a stored NOW reason needs no generic block.
     */
    @Test
    void aNowWithNoNowReasonStoredGetsTheGenericBlockFirst() {
        assertThat(blocksInOrder(now(List.of("value_outside_range:sodium|stopgap", "diagnosis_present"), ResultFixtures.BD)))
                .containsExactly("why-other", "why-value-outside", "why-diagnosis", "red-flags");
        for (UrgencyReason nowReason : DocumentPages.NOW_REASONS) {
            assertThat(now(List.of(nowReason.of("potassium", null), "diagnosis_present"), ResultFixtures.BD)).as(nowReason.name())
                    .doesNotContain("id=\"why-other\"");
        }
    }

    /**
     * The critical row itself sits in its block, from the stored verdict, beside its crop — and only there (s11): the
     * outside group below keeps every OUTSIDE row, value_outside_range stored or not, so no row is drawn twice and none is
     * dropped.
     */
    @Test
    void aCriticalValueIsShownOnceInItsBlockAndTheOutsideRowsStayInTheirGroup() {
        var view = ResultFixtures.lab(List.of(ResultFixtures.value("Potassium", "11.0", "mmol/L", "3.5", "5.1", "critical", "high"),
                ResultFixtures.value("Sodium", "131", "mmol/L", "135", "145", "outside", "low")));
        String html = now(view, List.of("value_outside_range:sodium|stopgap", "value_critical:potassium|stopgap"), ResultFixtures.BD);
        String block = ResultFixtures.section(html, "why-value-critical");
        assertThat(block).contains(copy.t(Lang.BN, "now.why.critical")).contains("<a class=\"vrow critical\" id=\"v-" + id(view, "Potassium"))
                .contains("crop-Potassium.jpg").contains(copy.t(Lang.BN, "value.chip.critical.high")).doesNotContain("Sodium");
        assertThat(html).containsOnlyOnce("id=\"v-" + id(view, "Potassium") + "\"").containsOnlyOnce("id=\"v-" + id(view, "Sodium") + "\"");
        assertThat(ResultFixtures.section(html, "outside")).contains("id=\"v-" + id(view, "Sodium") + "\"").doesNotContain("Potassium");
        assertThat(block).doesNotContain("/page/");   // the row is the proof: no page images stand in for it
        assertThat(ResultFixtures.section(html, "why-value-outside")).contains(copy.t(Lang.BN, "now.why.outside"))
                .contains("href=\"#outside\"").doesNotContain("Sodium").doesNotContain("/page/");
    }

    /** The other direction: a NOW raised by another rule leaves a critical row in the outside group — never dropped. */
    @Test
    void aCriticalRowWithNoValueBlockAboveStaysInTheOutsideGroup() {
        var view = ResultFixtures.lab(List.of(ResultFixtures.value("Potassium", "11.0", "mmol/L", "3.5", "5.1", "critical", "high")));
        String html = now(view, List.of("text_emergency_phrase"), ResultFixtures.BD);
        assertThat(html).doesNotContain("id=\"why-value-critical\"").containsOnlyOnce("id=\"v-" + id(view, "Potassium") + "\"");
        assertThat(ResultFixtures.section(html, "outside")).contains("id=\"v-" + id(view, "Potassium") + "\"");
    }

    /** Below NOW the value blocks do not exist, so every outside row stays in its group. */
    @Test
    void belowNowEveryOutsideRowStaysInItsGroup() {
        var view = ResultFixtures.lab(List.of(ResultFixtures.value("Sodium", "131", "mmol/L", "135", "145", "outside", "low")));
        String html = kit.documents.summaryBody(Lang.BN, ResultFixtures.result(view, Urgency.THIS_WEEK,
                List.of("value_outside_range:sodium"), GateState.NONE, null, ResultFixtures.BD));
        assertThat(ResultFixtures.section(html, "outside")).contains("id=\"v-" + id(view, "Sodium") + "\"");
    }

    /** A value block whose stored row is not drawn as its kind (no such row on the page) shows the whole pages instead. */
    @ParameterizedTest
    @EnumSource(value = UrgencyReason.class, names = {"VALUE_CRITICAL", "VALUE_OUTSIDE_RANGE"})
    void aValueBlockWithNoRowOfItsKindShowsEveryPage(UrgencyReason reason) {
        var view = ResultFixtures.lab(List.of(ResultFixtures.value("Potassium", "11.0", "mmol/L", "3.5", "5.1", null)));   // UNKNOWN
        String block = ResultFixtures.section(now(view, List.of(reason.of("potassium", "|stopgap")), ResultFixtures.BD),
                DocumentPages.REASON_BLOCKS.get(reason));
        assertThat(block).as(reason.name()).contains("/page/1").contains("/page/2").doesNotContain("class=\"vrow").doesNotContain("href=\"#outside\"");
    }

    /** The phrase is somewhere on the paper, not on a line Medioo can point at: its block shows every page, whole. */
    @Test
    void thePhraseBlockShowsEveryPage() {
        String block = ResultFixtures.section(now(List.of("text_emergency_phrase"), ResultFixtures.BD), "why-emergency-phrase");
        assertThat(block).contains(copy.t(Lang.BN, "now.why.phrase")).contains("/page/1").contains("/page/2");
    }

    /** The past-due follow-up is quoted as written, beside its own crop, and that crop is the proof: no page stands in. */
    @Test
    void theFollowUpBlockQuotesTheStoredLineBesideItsCrop() {
        var view = ResultFixtures.lab("Diagnostic Centre, Mirpur", List.of(),
                List.of(Map.of("instruction", "আবার দেখাবেন", "due_date", "2026-10-01", "crop_key", "f/p/d/crop-4.jpg")), Map.of());
        String block = ResultFixtures.section(now(view, List.of("follow_up_past_due"), ResultFixtures.BD), "why-follow-up");
        assertThat(block).contains(copy.t(Lang.BN, "now.why.followup")).contains("<figure class=\"paper\">")
                .contains("<blockquote>আবার দেখাবেন</blockquote>").contains("crop-4.jpg").doesNotContain("/page/");
    }

    /**
     * The other direction: the stored reason outlives the line it was raised on (the follow-up row is gone from the view;
     * follow_up.crop_key is NOT NULL, so a crop-less row is not a state to test), and the whole pages stand in for it.
     */
    @Test
    void aFollowUpReasonWithNoFollowUpLineFallsBackToEveryPage() {
        var view = ResultFixtures.lab("Diagnostic Centre, Mirpur", List.of(), List.of(), Map.of());
        String block = ResultFixtures.section(now(view, List.of("follow_up_past_due"), ResultFixtures.BD), "why-follow-up");
        assertThat(block).contains(copy.t(Lang.BN, "now.why.followup")).contains("/page/1").contains("/page/2").doesNotContain("<figure");
    }

    /** The diagnosis lines are quoted as written, under their section's label, beside their crops. */
    @Test
    void theDiagnosisBlockQuotesTheStoredLinesBesideTheirCrops() {
        var view = ResultFixtures.lab("Diagnostic Centre, Mirpur", List.of(), List.of(),
                Map.of("diagnosis", List.of(Map.of("text", "CKD stage 3", "crop_key", "f/p/d/crop-5.jpg"))));
        String block = ResultFixtures.section(now(view, List.of("diagnosis_present"), ResultFixtures.BD), "why-diagnosis");
        assertThat(block).contains(copy.t(Lang.BN, "ctx.diagnosis")).contains("<figure class=\"paper\">")
                .contains("<blockquote>CKD stage 3</blockquote>").contains("crop-5.jpg").doesNotContain("/page/");
    }

    /** The other direction: a diagnosis line stored without a crop is never quoted, and the whole pages stand in. */
    @Test
    void aDiagnosisWithNoCropFallsBackToEveryPage() {
        var view = ResultFixtures.lab("Diagnostic Centre, Mirpur", List.of(), List.of(),
                Map.of("diagnosis", List.of(Map.of("text", "CKD stage 3"))));
        String block = ResultFixtures.section(now(view, List.of("diagnosis_present"), ResultFixtures.BD), "why-diagnosis");
        assertThat(block).contains("/page/1").contains("/page/2").doesNotContain("<figure").doesNotContain("CKD stage 3");
    }

    /** An unverified value is not an observation: its block shows the whole pages and no value row at all. */
    @Test
    void anUnverifiedCriticalValueShowsEveryPageAndNoValueRow() {
        String block = ResultFixtures.section(now(List.of("unverified_value_critical:potassium"), ResultFixtures.BD), "why-unverified-critical");
        assertThat(block).contains(copy.t(Lang.BN, "now.why.unverified")).contains("/page/1").contains("/page/2")
                .doesNotContain("class=\"vrow");
    }

    /**
     * Safety constraint, the NOW body's half of it (DocumentPagesRenderTest has the page's): every word in every block and
     * in both actions is a bundle string with its arguments filled, or the paper's own (its quotes and its value rows).
     * Every reason, both readers, both languages, with the paper's lines present and with nothing croppable at all.
     */
    @ParameterizedTest
    @MethodSource("everyNowBody")
    void everyWordOfTheNowBodyIsBundleCopyOrThePapersOwn(Lang lang, ViewerLocale viewer, boolean croppable) {
        var view = croppable ? everyLine() : ResultFixtures.lab(List.of());
        List<String> reasons = Stream.concat(EnumSet.allOf(UrgencyReason.class).stream().map(r -> r.of("potassium", null)),
                Stream.of("retired_rule_2025:potassium")).toList();
        String body = kit.documents.nowBody(lang, ResultFixtures.result(view, Urgency.NOW, reasons, GateState.NONE, null, viewer));
        for (String block : DocumentPages.REASON_BLOCKS.values()) {
            assertThat(body).as(block).contains("id=\"" + block + "\"");
        }
        assertThat(body).contains("id=\"" + DocumentPages.OTHER_BLOCK + "\"").contains("id=\"red-flags\"").contains("id=\"now-bring\"");
        if (!viewer.bangladesh()) {
            assertThat(body).contains("id=\"remote-action\"");
        }
        List<Pattern> templates = ResultFixtures.bundleTemplates(copy, lang);
        assertThat(ResultFixtures.textOutsidePaper(body)).as("the NOW body's text").isNotEmpty()
                .allSatisfy(text -> assertThat(templates).as(text).anySatisfy(p -> assertThat(p.matcher(text).matches()).isTrue()));
    }

    static Stream<Arguments> everyNowBody() {
        List<Arguments> out = new ArrayList<>();
        for (Lang lang : Lang.values()) {
            for (ViewerLocale viewer : List.of(ResultFixtures.BD, ResultFixtures.ABROAD)) {
                out.add(Arguments.of(lang, viewer, true));
                out.add(Arguments.of(lang, viewer, false));
            }
        }
        return out.stream();
    }

    /** A paper with a line for every block that quotes one: a critical and an outside row, a follow-up, a diagnosis. */
    private static DocumentView everyLine() {
        return ResultFixtures.lab("Diagnostic Centre, Mirpur",
                List.of(ResultFixtures.value("Potassium", "11.0", "mmol/L", "3.5", "5.1", "critical", "high"),
                        ResultFixtures.value("Sodium", "131", "mmol/L", "135", "145", "outside", "low")),
                List.of(Map.of("instruction", "আবার দেখাবেন", "due_date", "2026-10-01", "crop_key", "f/p/d/crop-fu.jpg")),
                Map.of("diagnosis", List.of(Map.of("text", "CKD", "crop_key", "f/p/d/crop-dx.jpg"))));
    }

    /** F7, the dangerous direction: a phone outside Bangladesh is never handed tel:999, and the share text carries no value. */
    @ParameterizedTest
    @ValueSource(strings = {"+447700900123", "+971500000000", "+15555550123"})
    void aReaderAbroadIsNeverOfferedTheBangladeshEmergencyNumber(String e164) {
        String html = now(List.of("value_critical:potassium|stopgap"), ViewerLocale.of(e164));
        assertThat(html).doesNotContain("tel:");
        assertThat(ResultFixtures.section(html, "remote-action")).contains(copy.t(Lang.BN, "now.remote.999"))
                .contains(copy.t(Lang.BN, "now.remote.lead", "Ma")).contains(copy.t(Lang.BN, "now.remote.share"));
        // the red-flag questions are their own section after the action, as s11--abroad draws them, with no call link
        assertThat(ResultFixtures.section(html, "red-flags")).contains(copy.t(Lang.BN, "now.flags.q"))
                .contains(copy.t(Lang.BN, "now.remote.flags.go")).doesNotContain(copy.t(Lang.BN, "now.flags.go"));
        assertThat(html.indexOf("id=\"remote-action\"")).isLessThan(html.indexOf("id=\"red-flags\""));
        // the field, not a substring: the share text is the bundle's, with only the name filled in
        String raw = shareText(html);
        assertThat(URLDecoder.decode(raw, StandardCharsets.UTF_8)).isEqualTo(copy.raw(Lang.BN, "now.remote.share.text", "Ma"));
        assertThat(raw).as("spaces as %20, never '+', which some WhatsApp clients show as a plus").doesNotContain("+").contains("%20");
    }

    /** F7, the other direction: a Bangladeshi phone gets tel:999 once, and only under the red-flag questions. */
    @Test
    void aReaderInBangladeshGetsTheCallButtonOnlyUnderTheRedFlagQuestions() {
        String html = now(List.of("value_critical:potassium|stopgap"), ResultFixtures.BD);
        assertThat(html).containsOnlyOnce("href=\"tel:999\"");
        assertThat(ResultFixtures.section(html, "red-flags")).contains("href=\"tel:999\"").contains(copy.t(Lang.BN, "now.flags.q"))
                .contains(copy.t(Lang.BN, "now.flags.go")).contains(copy.t(Lang.BN, "now.call999"));
        assertThat(ResultFixtures.section(html, "urgency")).doesNotContain("tel:");
        assertThat(html).doesNotContain("id=\"remote-action\"").doesNotContain("wa.me");
    }

    @ParameterizedTest
    @EnumSource(value = Urgency.class, names = {"ROUTINE", "THIS_WEEK"})
    void belowNowThereIsNoEmergencyActionForAnyReader(Urgency level) {
        for (ViewerLocale viewer : List.of(ResultFixtures.BD, ResultFixtures.ABROAD)) {
            String html = kit.documents.summaryBody(Lang.BN, ResultFixtures.result(ResultFixtures.lab(List.of()), level,
                    level == Urgency.ROUTINE ? List.of() : List.of("follow_up_past_due"), GateState.NONE, null, viewer));
            assertThat(html).doesNotContain("tel:").doesNotContain("id=\"red-flags\"").doesNotContain("id=\"remote-action\"");
            assertThat(html).doesNotContain("id=\"why-");
        }
    }

    /** Every string of the NOW body is a key, so the English toggle reaches it: no Bangla left on the English page. */
    @Test
    void theEnglishNowPageCarriesNoBangla() {
        for (ViewerLocale viewer : List.of(ResultFixtures.BD, ResultFixtures.ABROAD)) {
            var view = ResultFixtures.lab(List.of(ResultFixtures.value("Potassium", "11.0", "mmol/L", "3.5", "5.1", "critical", "high")));
            String html = kit.documents.summaryBody(Lang.EN, ResultFixtures.result(view, Urgency.NOW,
                    List.of("value_critical:potassium|stopgap", "value_outside_range:sodium|stopgap", "unverified_value_critical:x", "retired:x"),
                    GateState.NONE, null, viewer));
            assertThat(html).contains(copy.t(Lang.EN, "now.flags.q"));
            assertThat(html).doesNotContainPattern("[\\u0980-\\u09FF]");
        }
    }

    /**
     * The served page, through the router, from the fixtures every derived page test reads (PageFixtures "now" and
     * "nowabroad"): contrast, 48 px targets, inline code and the paper label are checked on these blocks only if the
     * fixtures really render them — a fixture that answered an error page would let those checks pass over nothing. And
     * F7 on the whole served page, shell and nav included, in both languages: tel:999 once for +880, never abroad.
     */
    @Test
    void theDerivedPageTestsReadEveryNowBlockAsServed() {
        RouteTable.Route route = RouteTable.of(kit.router).stream().filter(r -> r.key().equals("GET /app/documents/{id}")).findFirst().orElseThrow();
        for (String variant : List.of("now", "nowabroad")) {
            for (String lang : RenderedPages.LANGS) {
                PageFixtures.Fixture f = PageFixtures.allOf(route).stream().filter(x -> x.name().equals(variant)).findFirst().orElseThrow();
                WebUiTestKit fresh = new WebUiTestKit();
                f.stubs().accept(fresh);
                var served = RenderedPages.exchange(fresh, route, f, lang);
                String as = variant + " " + lang;
                assertThat(served.getStatus().value()).as(as).isEqualTo(200);
                String html = new String(served.getResponseBody(), StandardCharsets.UTF_8);
                for (String block : DocumentPages.REASON_BLOCKS.values()) {
                    assertThat(html).as(as + " " + block).contains("id=\"" + block + "\"");
                }
                assertThat(html).as(as).contains("id=\"" + DocumentPages.OTHER_BLOCK + "\"").contains("id=\"red-flags\"");
                if (variant.equals("now")) {
                    assertThat(html).as(as).containsOnlyOnce("href=\"tel:999\"").doesNotContain("id=\"remote-action\"");
                } else {
                    assertThat(html).as(as).doesNotContain("tel:").contains("id=\"remote-action\"");
                }
            }
        }
    }

    // ---- the rule's papers -------------------------------------------------------------------------------------------

    private static final ObjectMapper JSON = new ObjectMapper();

    /** One paper per reason, each of which the rule raises with that reason alone (asserted by the test that reads it). */
    private static Map<UrgencyReason, DocumentFacts> onePaperPerReason() {
        Map<String, Object> potassium = ResultFixtures.value("Potassium", "11.0", "mmol/L", "3.5", "5.1", null);
        Map<String, Object> sodium = ResultFixtures.value("Sodium", "131", "mmol/L", "135", "145", null);
        Map<UrgencyReason, DocumentFacts> papers = new EnumMap<>(UrgencyReason.class);
        papers.put(UrgencyReason.VALUE_CRITICAL, facts("lab_report", List.of(potassium), List.of(), Map.of(), "{}"));
        papers.put(UrgencyReason.VALUE_OUTSIDE_RANGE, facts("lab_report", List.of(sodium), List.of(), Map.of(), "{}"));
        papers.put(UrgencyReason.UNVERIFIED_VALUE_CRITICAL, unverified(potassium));
        papers.put(UrgencyReason.UNVERIFIED_VALUE_OUTSIDE_RANGE, unverified(sodium));
        papers.put(UrgencyReason.FOLLOW_UP_PAST_DUE, facts("lab_report", List.of(),
                List.of(Map.of("instruction", "আবার দেখাবেন", "due_date", "2026-01-01")), Map.of(), "{}"));
        papers.put(UrgencyReason.DIAGNOSIS_PRESENT, facts("lab_report", List.of(), List.of(),
                Map.of("diagnosis", List.of(Map.of("text", "CKD"))), "{}"));
        papers.put(UrgencyReason.TEXT_EMERGENCY_PHRASE, facts("lab_report", List.of(), List.of(), Map.of(),
                "{\"free_text_summary\":\"URGENT please contact the physician\"}"));
        return papers;
    }

    private static DocumentFacts facts(String type, List<Map<String, Object>> values, List<Map<String, Object>> followUps,
                                       Map<String, Object> ctx, String json) {
        Document d = new Document(UUID.randomUUID(), WebUiTestKit.MA_ID, WebUiTestKit.FAMILY, type, null, null, json, 0.9,
                Document.Status.DONE, null, "m", null, 2, ResultFixtures.NOW, ResultFixtures.NOW);
        return new DocumentFacts(d, "Ma", values, followUps, ctx, "https://x/app/documents/1");
    }

    /** A value the model read that never became an observation (no crop): only in the extraction and the unverified count. */
    private static DocumentFacts unverified(Map<String, Object> value) {
        String json;
        try {
            json = JSON.writeValueAsString(Map.of("document_type", "lab_report", "values", List.of(Map.of("name", value.get("name"),
                    "value", value.get("value"), "ref_low", value.get("ref_low"), "ref_high", value.get("ref_high")))));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        Document d = new Document(UUID.randomUUID(), WebUiTestKit.MA_ID, WebUiTestKit.FAMILY, "lab_report", null, null, json, 0.9,
                Document.Status.DONE, null, "m", null, 2, ResultFixtures.NOW, ResultFixtures.NOW,
                new VerifiedItems.Unverified(1, 0, 0, 0), null);
        return new DocumentFacts(d, "Ma", List.of(), List.of(), Map.of(), "https://x/app/documents/1");
    }

    /** The rows as V19 stores them after this assessment: each row's verdict and side from the rule's own verdict for it. */
    private static List<Map<String, Object>> withVerdicts(List<Map<String, Object>> rows, UrgencyAssessment a) {
        Map<UUID, ValueVerdict> byId = a.values().stream().collect(Collectors.toMap(ValueVerdict::observationId, Function.identity()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> stored = new LinkedHashMap<>(row);
            ValueVerdict v = byId.get((UUID) row.get("id"));
            assertThat(v).as("the rule's verdict for " + row.get("name")).isNotNull();
            stored.put("verdict", v.kind().dbValue());
            if (v.side() != null) {
                stored.put("verdict_side", v.side().dbValue());
            }
            out.add(stored);
        }
        return out;
    }

    // ---- reading the page --------------------------------------------------------------------------------------------

    /** The NOW body's sections in page order: the reason blocks and the red-flag questions. */
    private static List<String> blocksInOrder(String html) {
        Matcher m = Pattern.compile("<section id=\"((?:why-[a-z-]+)|red-flags)\"").matcher(html);
        List<String> out = new ArrayList<>();
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static Object id(DocumentView view, String name) {
        return view.values().stream().filter(v -> name.equals(v.get("name"))).findFirst().orElseThrow().get("id");
    }

    private static String shareText(String html) {
        Matcher m = Pattern.compile("href=\"https://wa\\.me/\\?text=([^\"]+)\"").matcher(html);
        assertThat(m.find()).as("share link").isTrue();
        return m.group(1);
    }
}
