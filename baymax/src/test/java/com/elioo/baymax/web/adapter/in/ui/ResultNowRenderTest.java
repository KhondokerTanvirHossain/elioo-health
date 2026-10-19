package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.outbound.domain.UrgencyReason;
import com.elioo.baymax.web.domain.GateState;
import com.elioo.baymax.web.domain.ViewerLocale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** s11 / spec §4.11: one block per reason, derived from the rule's own enum, and an action that depends on the reader. */
class ResultNowRenderTest {

    private final WebUiTestKit kit = new WebUiTestKit();
    private final UiCopy copy = kit.copy;

    private String now(List<String> reasons, ViewerLocale viewer) {
        var view = ResultFixtures.lab(List.of(ResultFixtures.value("Potassium", "11.0", "mmol/L", "3.5", "5.1", "critical")));
        return kit.documents.summaryBody(Lang.BN, ResultFixtures.result(view, Urgency.NOW, reasons, GateState.PENDING, null, viewer));
    }

    /** Derived: every reason the rule can raise has its block — the enum is the rule's own list (UrgencyAssessment.raise refuses others). */
    @Test
    void everyReasonTheRuleCanRaiseHasItsOwnBlock() {
        assertThat(EnumSet.allOf(UrgencyReason.class)).as("the rule's reasons").hasSizeGreaterThanOrEqualTo(7);
        assertThat(DocumentPages.REASON_BLOCKS.keySet()).isEqualTo(EnumSet.allOf(UrgencyReason.class));
    }

    /** Assert the block, not "a red banner": each reason renders its own id, and only its own. */
    @ParameterizedTest
    @EnumSource(UrgencyReason.class)
    void eachReasonRendersItsOwnBlockAndNoOther(UrgencyReason reason) {
        String html = now(List.of(reason.of(reason.name().startsWith("VALUE") || reason.name().startsWith("UNVERIFIED") ? "potassium" : null,
                reason.name().startsWith("VALUE") ? "|stopgap" : null)), ResultFixtures.BD);
        String mine = DocumentPages.REASON_BLOCKS.get(reason);
        assertThat(html).contains("id=\"" + mine + "\"");
        DocumentPages.REASON_BLOCKS.values().stream().filter(b -> !b.equals(mine) && b.startsWith("why-"))
                .forEach(other -> assertThat(html).as(other).doesNotContain("id=\"" + other + "\""));
        assertThat(html).doesNotContain("id=\"why-other\"");
    }

    /**
     * A stored reason whose prefix the enum does not know (a legacy row) gets the generic block — never nothing — alone
     * and beside a known one (where the no-reason fallback cannot stand in for it), in the stored order.
     */
    @Test
    void anUnknownStoredReasonGetsTheGenericBlock() {
        String block = ResultFixtures.section(now(List.of("retired_rule_2025:potassium"), ResultFixtures.BD), "why-other");
        assertThat(block).contains(copy.t(Lang.BN, "now.why.other")).contains("/page/1");
        String mixed = now(List.of("text_emergency_phrase", "retired_rule_2025:potassium"), ResultFixtures.BD);
        assertThat(ResultFixtures.section(mixed, "why-other")).contains(copy.t(Lang.BN, "now.why.other"));
        assertThat(mixed.indexOf("id=\"why-emergency-phrase\"")).isNotNegative().isLessThan(mixed.indexOf("id=\"why-other\""));
    }

    /** A NOW stored with no reason at all (a row from before reasons were kept) is not a NOW page with nothing to show. */
    @Test
    void aNowWithNoStoredReasonGetsTheGenericBlock() {
        String block = ResultFixtures.section(now(List.of(), ResultFixtures.BD), "why-other");
        assertThat(block).contains(copy.t(Lang.BN, "now.why.other")).contains("/page/1").contains("/page/2");
    }

    /** Two stored reasons with one prefix are one block: an id is on the page once, so a link to it has one target. */
    @Test
    void twoReasonsOfOneKindAreOneBlockAndBlocksKeepTheStoredOrder() {
        String html = now(List.of("text_emergency_phrase", "value_critical:potassium|stopgap", "value_critical:sodium|stopgap"),
                ResultFixtures.BD);
        assertThat(html).containsOnlyOnce("id=\"why-value-critical\"").containsOnlyOnce("id=\"why-emergency-phrase\"");
        assertThat(html.indexOf("id=\"why-emergency-phrase\"")).isLessThan(html.indexOf("id=\"why-value-critical\""));
        assertThat(html.indexOf("id=\"urgency\"")).isLessThan(html.indexOf("id=\"why-emergency-phrase\""));
    }

    /**
     * The critical row itself sits in its block, from the stored verdict, beside its crop — and only there (s11): the
     * outside group below keeps the rows no block above has shown, so no row is drawn twice and none is dropped.
     */
    @Test
    void aCriticalValueIsShownOnceInItsBlockAndTheOtherOutsideRowsStayInTheirGroup() {
        var view = ResultFixtures.lab(List.of(ResultFixtures.value("Potassium", "11.0", "mmol/L", "3.5", "5.1", "critical", "high"),
                ResultFixtures.value("Sodium", "131", "mmol/L", "135", "145", "outside", "low")));
        String html = kit.documents.summaryBody(Lang.BN, ResultFixtures.result(view, Urgency.NOW,
                List.of("value_critical:potassium|stopgap"), GateState.NONE, null, ResultFixtures.BD));
        String block = ResultFixtures.section(html, "why-value-critical");
        assertThat(block).contains(copy.t(Lang.BN, "now.why.critical")).contains("<a class=\"vrow critical\" id=\"v-" + id(view, "Potassium"))
                .contains("crop-Potassium.jpg").contains(copy.t(Lang.BN, "value.chip.critical.high")).doesNotContain("Sodium");
        assertThat(html).containsOnlyOnce("id=\"v-" + id(view, "Potassium") + "\"");
        assertThat(ResultFixtures.section(html, "outside")).contains("id=\"v-" + id(view, "Sodium") + "\"").doesNotContain("Potassium");
        assertThat(block).doesNotContain("/page/");   // the row is the proof: no page images stand in for it
    }

    /** The other direction: a NOW raised by another rule leaves a critical row in the outside group — never dropped. */
    @Test
    void aCriticalRowWithNoValueBlockAboveStaysInTheOutsideGroup() {
        var view = ResultFixtures.lab(List.of(ResultFixtures.value("Potassium", "11.0", "mmol/L", "3.5", "5.1", "critical", "high")));
        String html = kit.documents.summaryBody(Lang.BN, ResultFixtures.result(view, Urgency.NOW,
                List.of("text_emergency_phrase"), GateState.NONE, null, ResultFixtures.BD));
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

    private static Object id(com.elioo.baymax.extraction.domain.DocumentView view, String name) {
        return view.values().stream().filter(v -> name.equals(v.get("name"))).findFirst().orElseThrow().get("id");
    }

    /** An unverified value is not an observation: its block shows the whole pages and no value row at all. */
    @Test
    void anUnverifiedCriticalValueShowsEveryPageAndNoValueRow() {
        String block = ResultFixtures.section(now(List.of("unverified_value_critical:potassium"), ResultFixtures.BD), "why-unverified-critical");
        assertThat(block).contains(copy.t(Lang.BN, "now.why.unverified")).contains("/page/1").contains("/page/2")
                .doesNotContain("class=\"vrow");
    }

    /** F7, the dangerous direction: a phone outside Bangladesh is never handed tel:999, and the share text carries no value. */
    @ParameterizedTest
    @ValueSource(strings = {"+447700900123", "+971500000000", "+15555550123"})
    void aReaderAbroadIsNeverOfferedTheBangladeshEmergencyNumber(String e164) {
        String html = now(List.of("value_critical:potassium|stopgap"), ViewerLocale.of(e164));
        assertThat(html).doesNotContain("tel:");
        assertThat(ResultFixtures.section(html, "remote-action")).contains(copy.t(Lang.BN, "now.remote.999"))
                .contains(copy.t(Lang.BN, "now.remote.lead", "Ma")).contains(copy.t(Lang.BN, "now.remote.share"));
        String share = URLDecoder.decode(shareText(html), StandardCharsets.UTF_8);
        assertThat(share).contains("Ma").doesNotContain("999").doesNotContain("11.0").doesNotContain("mmol");
    }

    /** F7, the other direction: a Bangladeshi phone gets tel:999 once, and only under the red-flag questions. */
    @Test
    void aReaderInBangladeshGetsTheCallButtonOnlyUnderTheRedFlagQuestions() {
        String html = now(List.of("value_critical:potassium|stopgap"), ResultFixtures.BD);
        assertThat(html).containsOnlyOnce("href=\"tel:999\"");
        assertThat(ResultFixtures.section(html, "red-flags")).contains("href=\"tel:999\"").contains(copy.t(Lang.BN, "now.flags.q"))
                .contains(copy.t(Lang.BN, "now.call999"));
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
                    List.of("value_critical:potassium|stopgap", "unverified_value_critical:x", "retired:x"), GateState.NONE, null, viewer));
            assertThat(html).contains(copy.t(Lang.EN, "now.flags.q"));
            assertThat(html).doesNotContainPattern("[\\u0980-\\u09FF]");
        }
    }

    /**
     * The served page, through the router, from the fixtures every derived page test reads (PageFixtures "now" and
     * "nowabroad"): contrast, 48 px targets, inline code and the paper label are checked on these blocks only if the
     * fixtures really render them — a fixture that answered an error page would let those checks pass over nothing.
     */
    @Test
    void theDerivedPageTestsReadEveryNowBlockAsServed() {
        RouteTable.Route route = RouteTable.of(kit.router).stream().filter(r -> r.key().equals("GET /app/documents/{id}")).findFirst().orElseThrow();
        for (String variant : List.of("now", "nowabroad")) {
            PageFixtures.Fixture f = PageFixtures.allOf(route).stream().filter(x -> x.name().equals(variant)).findFirst().orElseThrow();
            WebUiTestKit fresh = new WebUiTestKit();
            f.stubs().accept(fresh);
            var served = RenderedPages.exchange(fresh, route, f, "bn");
            assertThat(served.getStatus().value()).as(variant).isEqualTo(200);
            String html = new String(served.getResponseBody(), StandardCharsets.UTF_8);
            for (String block : DocumentPages.REASON_BLOCKS.values()) {
                assertThat(html).as(variant + " " + block).contains("id=\"" + block + "\"");
            }
            assertThat(html).as(variant).contains("id=\"" + DocumentPages.OTHER_BLOCK + "\"")
                    .contains(variant.equals("now") ? "id=\"red-flags\"" : "id=\"remote-action\"");
        }
    }

    private static String shareText(String html) {
        Matcher m = Pattern.compile("href=\"https://wa\\.me/\\?text=([^\"]+)\"").matcher(html);
        assertThat(m.find()).as("share link").isTrue();
        return m.group(1);
    }
}
