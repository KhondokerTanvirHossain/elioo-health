package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.healthrecord.domain.DocumentAllowance;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.web.domain.PatientSummary;
import com.elioo.baymax.web.domain.UploadOptions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The page-level "no phone number" guard ({@link BaymaxWebUiRouterTest#BD_MOBILE}) in both directions, on ids and on
 * rendered pages. It flaked once in CI (FamilyViewTest.noOtherSignedInPageCarriesTheNumber, line 174, green on a rerun
 * of the same SHA): the add sheet draws a fresh {@code UUID.randomUUID()} upload key on every render, and roughly one
 * random UUID in 1,600 holds eleven decimal digits shaped like {@code 01[3-9]…} with hyphens between them, which the
 * unbounded pattern read as a number. A number is a field: the guard matches it only where it stands on its own.
 */
class PhoneNumberGuardTest {

    /** The shape the guard looked for before it was bounded: what the ids below must still contain, or they prove nothing. */
    private static final Pattern NUMBER_SHAPE = Pattern.compile(
            "(?:\\+?[8৮][8৮][0০][\\s-]?|[0০])[1১][3-9৩-৯](?:[\\s-]?[0-9০-৯]){8}");

    /**
     * UUIDs that hold a number-shaped digit run: at the id's start, after a hex letter, across two hyphens, at the
     * id's end. The four random ones were drawn from {@code UUID.randomUUID()} by a sampler; the last is built to.
     */
    private static final List<String> NUMBER_SHAPED_IDS = List.of(
            "01497947-9397-4d03-9d03-9fad3074522c",
            "19492940-0135-4005-952b-d3d76ee5b1d2",
            "bb7d4019-0909-4420-a6d7-c5a551d2d342",
            "6cb463f6-66e2-4ec5-acd2-f01820437017",
            "01700000-0000-4000-8000-000000000000");

    /** Every way a page could carry an id: an attribute, a path, a query, a JSON field, text. */
    private static final List<String> ID_CONTEXTS = List.of(
            "<input type=\"hidden\" name=\"upload_key\" value=\"%s\">",
            "<a href=\"/app/documents/%s\">",
            "/app/timeline?patient=%s&sheet=patient",
            "{\"id\":\"%s\"}",
            "<p>%s</p>");

    @Test
    void anIdThatHoldsANumberShapedRunIsNotANumber() {
        for (String id : NUMBER_SHAPED_IDS) {
            // the id is a real trap: without this, a list of harmless ids would pass the guard for the wrong reason
            assertThat(NUMBER_SHAPE.matcher(id).find()).as("number-shaped run in " + id).isTrue();
            for (String context : ID_CONTEXTS) {
                String html = context.formatted(id);
                assertThat(html).as(html).doesNotContainPattern(BaymaxWebUiRouterTest.BD_MOBILE);
            }
        }
    }

    /** The other direction, in the same contexts: a number that stands on its own is caught however it is written. */
    @Test
    void theOwnersNumberInAnyContextIsStillANumber() {
        List<String> numbers = List.of(WebUiTestKit.OWNER_NUMBER, WebUiTestKit.OWNER_NUMBER.substring(1), "01700000000",
                PhoneDisplay.full(WebUiTestKit.OWNER_NUMBER), "+880 1700 000 000", "০১৭০০০০০০০০", "+৮৮০১৭০০০০০০০০", "০১৭০০-০০০০০০");
        List<String> contexts = List.of("<a href=\"tel:%s\">", "https://wa.me/%s?text=hi", "<input name=\"phone\" value=\"%s\">",
                "{\"phone\":\"%s\"}", "?to=%s&lang=bn", "<span class=\"num\">%s</span>", "নম্বর: %s।", "নম্বর%sএ কোড গেছে",
                "%s-এ কোড পাঠানো হয়েছে", "(%s)");
        for (String number : numbers) {
            for (String context : contexts) {
                String html = context.formatted(number);
                assertThat(html).as(html).containsPattern(BaymaxWebUiRouterTest.BD_MOBILE);
            }
        }
    }

    /** The CI flake, made deterministic: a rendered sheet whose patient id holds a number-shaped run carries no number. */
    @Test
    void aRenderedPageWhoseIdsLookLikeNumbersCarriesNoNumber() {
        for (String id : NUMBER_SHAPED_IDS) {
            String html = sheetFor(patient(UUID.fromString(id), "আম্মা"));
            assertThat(html).as("the id is on the page").contains("value=\"" + id + "\"");
            assertThat(html).as(id).doesNotContainPattern(BaymaxWebUiRouterTest.BD_MOBILE);
        }
    }

    /** The other direction on the same page: the owner's number, written where a name goes, is caught in every form. */
    @Test
    void aRenderedPageThatReallyCarriesTheNumberIsCaught() {
        for (String leak : List.of(WebUiTestKit.OWNER_NUMBER, PhoneDisplay.full(WebUiTestKit.OWNER_NUMBER), "০১৭০০০০০০০০")) {
            String html = sheetFor(patient(UUID.fromString(NUMBER_SHAPED_IDS.get(0)), leak));
            assertThat(html).as(leak).containsPattern(BaymaxWebUiRouterTest.BD_MOBILE);
        }
    }

    private String sheetFor(PatientSummary p) {
        WebUiTestKit k = new WebUiTestKit();
        when(k.uploads.uploadOptions(WebUiTestKit.FAMILY)).thenReturn(Mono.just(new UploadOptions(List.of(p), List.of(),
                new DocumentAllowance(true, 3, 1, YearMonth.of(2026, 10)))));
        return k.page("/app/add", "bn");
    }

    private static PatientSummary patient(UUID id, String name) {
        return new PatientSummary(new PatientProfile(id, WebUiTestKit.FAMILY, name, 64, PatientProfile.Sex.FEMALE, List.of(),
                WebUiTestKit.NOW, WebUiTestKit.NOW), true, 3, WebUiTestKit.NOW);
    }
}
