package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.healthrecord.domain.DocumentAllowance;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.web.domain.PatientSummary;
import com.elioo.baymax.web.domain.UploadOptions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The page-level "no phone number" guard ({@link PhoneGuard}) in both directions, on ids and on rendered pages. It
 * flaked once in CI (FamilyViewTest.noOtherSignedInPageCarriesTheNumber, line 174, green on a rerun of the same SHA):
 * the add sheet draws a fresh {@code UUID.randomUUID()} upload key on every render, and roughly one random UUID in
 * 1,600 holds eleven decimal digits shaped like {@code 01[3-9]…} with hyphens between them, which the unbounded
 * pattern read as a number ({@link #aSeededSweepOfRandomUuidsHoldsNumberShapedRunsAndNoneIsANumber} measures it).
 * The guard masks canonical UUIDs and nothing else, so every number the old pattern caught outside a UUID is still
 * caught, and it reads the page percent-decoded too, because that is how this app writes URLs.
 */
class PhoneNumberGuardTest {

    /** The shape the guard looks for, from its own constant: the ids below must contain it, or they prove nothing. */
    private static final Pattern NUMBER_SHAPE = Pattern.compile(PhoneGuard.BD_MOBILE_CORE);

    /**
     * UUIDs that hold a number-shaped digit run: at the id's start, after a hex letter, across two hyphens, at the
     * id's end. The first is the shape that flaked (an upload key); the four random ones were drawn from
     * {@code UUID.randomUUID()} by a sampler; the last is built to.
     */
    private static final List<String> NUMBER_SHAPED_IDS = List.of(
            "01497947-9397-4d03-9d03-9fad3074522c",
            "19492940-0135-4005-952b-d3d76ee5b1d2",
            "bb7d4019-0909-4420-a6d7-c5a551d2d342",
            "6cb463f6-66e2-4ec5-acd2-f01820437017",
            "01700000-0000-4000-8000-000000000000");

    /** Every way a page carries an id: an attribute, a path, a query, a JSON field, text, an encoded image key. */
    private static final List<String> ID_CONTEXTS = List.of(
            "<input type=\"hidden\" name=\"upload_key\" value=\"%s\">",
            "<a href=\"/app/documents/%s\">",
            "/app/timeline?patient=%s&sheet=patient",
            "{\"id\":\"%s\"}",
            "<p>%s</p>",
            "<img src=\"/app/documents/x/image?key=f%%2Fp%%2F%s%%2Fpage-1.jpg\">");

    @Test
    void anIdThatHoldsANumberShapedRunIsNotANumber() {
        for (String id : NUMBER_SHAPED_IDS) {
            // the id is a real trap: without this, a list of harmless ids would pass the guard for the wrong reason
            assertThat(NUMBER_SHAPE.matcher(id).find()).as("number-shaped run in " + id).isTrue();
            for (String context : ID_CONTEXTS) {
                String html = context.formatted(id);
                assertThat(PhoneGuard.find(html)).as(html).isEmpty();
            }
        }
    }

    /**
     * The flake's odds, as an assertion rather than a sentence: 200,000 seeded version-4 UUIDs, built bit for bit as
     * {@code randomUUID} builds them. Enough of them hold the number shape that the sweep is not vacuous, and none of
     * them is read as a number in any context.
     */
    @Test
    void aSeededSweepOfRandomUuidsHoldsNumberShapedRunsAndNoneIsANumber() {
        Random random = new Random(42);
        List<String> traps = new ArrayList<>();
        for (int i = 0; i < 200_000; i++) {
            long msb = (random.nextLong() & ~0xF000L) | 0x4000L;                                // version 4
            long lsb = (random.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;         // IETF variant
            String id = new UUID(msb, lsb).toString();
            if (NUMBER_SHAPE.matcher(id).find()) {
                traps.add(id);
            }
        }
        // about one in 1,600 (~125 here): fewer than 50 means the sweep stopped producing the case it exists for
        assertThat(traps).as("number-shaped UUIDs among 200,000 seeded draws").hasSizeGreaterThanOrEqualTo(50);
        for (String id : traps) {
            for (String context : ID_CONTEXTS) {
                String html = context.formatted(id);
                assertThat(PhoneGuard.find(html)).as(html).isEmpty();
            }
        }
    }

    /** The owner's number in every spelling: E.164, without +, national, as displayed, spaced, Bangla digits. */
    private static final List<String> SPELLINGS = List.of(WebUiTestKit.OWNER_NUMBER, WebUiTestKit.OWNER_NUMBER.substring(1),
            "01700000000", PhoneDisplay.full(WebUiTestKit.OWNER_NUMBER), "+880 1700 000 000", "০১৭০০০০০০০০", "+৮৮০১৭০০০০০০০০",
            "০১৭০০-০০০০০০");

    /** Where a number stands on its own: a link, an attribute, JSON, a query, text, Bangla text, parentheses. */
    private static final List<String> PLAIN_CONTEXTS = List.of("<a href=\"tel:%s\">", "https://wa.me/%s?text=hi",
            "<input name=\"phone\" value=\"%s\">", "{\"phone\":\"%s\"}", "?to=%s&lang=bn", "<span class=\"num\">%s</span>",
            "নম্বর: %s।", "নম্বর%sএ কোড গেছে", "%s-এ কোড পাঠানো হয়েছে", "(%s)");

    /** Where a number is glued into a longer token: a slug, a filename, a data attribute, straight onto a letter. */
    private static final List<String> GLUED_CONTEXTS = List.of("<div id=\"member-%s\">", "<a download=\"medioo-%s.pdf\">",
            "report_%s.pdf", "owner-%s", "<p data-phone-%s>", "phone:%sx", "id=x%s");

    /**
     * Where the app writes a number URL-encoded: the NOW page's wa.me share text (DocumentPages.remoteAction, {@code %20}
     * for spaces), the language toggle's and the tabs' {@code ?back=} (PageChrome, AppTabs, ChatView), a query value, a
     * {@code tel:} written encoded.
     */
    private static final List<UnaryOperator<String>> ENCODED_CONTEXTS = List.of(
            n -> "<a href=\"https://wa.me/?text=" + enc20("জরুরি: আম্মা " + n + " — এখনই নিকটস্থ হাসপাতালে") + "\">",
            n -> "<a href=\"https://wa.me/?text=" + enc20("Urgent: " + n) + "\">",
            n -> "<a href=\"https://wa.me/?text=" + enc20("কল " + n) + "\">",
            n -> "<a href=\"/lang/en?back=" + enc("/app/family?phone=" + n) + "\">",
            n -> "<a href=\"/lang/en?back=" + enc("/app/x?to=" + n) + "\">",
            n -> "?to=" + enc(n),
            n -> "<a href=\"tel%3A" + enc(n) + "\">");

    /** The other direction: a number is caught however it is written and wherever it sits, encoded included. */
    @Test
    void theOwnersNumberInAnyContextIsStillANumber() {
        List<String> missed = new ArrayList<>();
        for (String number : SPELLINGS) {
            List<String> pages = new ArrayList<>();
            PLAIN_CONTEXTS.forEach(c -> pages.add(c.formatted(number)));
            GLUED_CONTEXTS.forEach(c -> pages.add(c.formatted(number)));
            ENCODED_CONTEXTS.forEach(c -> pages.add(c.apply(number)));
            pages.stream().filter(html -> PhoneGuard.find(html).isEmpty()).forEach(missed::add);
        }
        assertThat(missed).as("a page carrying the owner's number that the guard read as clean").isEmpty();
    }

    /**
     * The CI flake, made deterministic on the field that failed: the sheet's upload key is drawn from a known
     * number-shaped id (and the patient is that id too), the page carries exactly that key, and no number.
     */
    @Test
    void theSheetWhoseUploadKeyLooksLikeANumberCarriesNoNumber() {
        for (String id : NUMBER_SHAPED_IDS) {
            String html = sheetFor(patient(UUID.fromString(id), "আম্মা"), UUID.fromString(id));
            assertThat(html).as("the upload key is on the page").contains("name=\"upload_key\" value=\"" + id + "\"");
            assertThat(html).as("the patient id is on the page").contains("name=\"patient\" value=\"" + id + "\"");
            assertThat(PhoneGuard.find(html)).as(id).isEmpty();
        }
    }

    /** The other direction on the same page: the owner's number, written where a name goes, is caught in every form. */
    @Test
    void aRenderedPageThatReallyCarriesTheNumberIsCaught() {
        UUID trap = UUID.fromString(NUMBER_SHAPED_IDS.get(0));
        for (String leak : SPELLINGS) {
            String html = sheetFor(patient(trap, leak), trap);
            assertThat(PhoneGuard.find(html)).as(leak).isPresent();
        }
    }

    /** A clean page names nothing; a dirty one names the match and its neighbourhood, so a red sweep in CI is readable. */
    @Test
    void aMatchIsReportedWithWhatItMatchedAndWhereItSits() {
        assertThat(PhoneGuard.find("<p>নম্বর নেই</p>")).isEmpty();
        assertThat(PhoneGuard.find("<a href=\"/lang/en?back=" + enc("/app/x?to=+8801700000000") + "\">en</a>"))
                .hasValueSatisfying(d -> assertThat(d).contains("'+8801700000000'").contains("/app/x?to=").contains("decoded"));
        assertThat(PhoneGuard.find("<span>" + "x".repeat(80) + " 01700000000 " + "y".repeat(80) + "</span>"))
                .hasValueSatisfying(d -> assertThat(d).contains("'01700000000'").contains("xxxx 01700000000 yyyy")
                        .doesNotContain("<span>").doesNotContain("</span>"));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** As DocumentPages.remoteAction writes the share text: URL-encoded, a space as %20. */
    private static String enc20(String s) {
        return enc(s).replace("+", "%20");
    }

    private String sheetFor(PatientSummary p, UUID uploadKey) {
        WebUiTestKit k = new WebUiTestKit();
        k.upload.uploadKeys = () -> uploadKey;
        when(k.uploads.uploadOptions(WebUiTestKit.FAMILY)).thenReturn(Mono.just(new UploadOptions(List.of(p), List.of(),
                new DocumentAllowance(true, 3, 1, YearMonth.of(2026, 10)))));
        return k.page("/app/add", "bn");
    }

    private static PatientSummary patient(UUID id, String name) {
        return new PatientSummary(new PatientProfile(id, WebUiTestKit.FAMILY, name, 64, PatientProfile.Sex.FEMALE, List.of(),
                WebUiTestKit.NOW, WebUiTestKit.NOW), true, 3, WebUiTestKit.NOW);
    }
}
