package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;
import com.elioo.baymax.web.domain.FamilyOverview;
import com.elioo.baymax.web.domain.PatientSummary;
import com.elioo.baymax.web.domain.TimelineEntry;
import com.elioo.baymax.web.domain.TimelinePage;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Spec rule 13: on a Bangla page, counts and dates use Bangla digits and full month names; values keep the digits
 * printed on the paper; the English page is unchanged. Found on the PO's first look at the portal after sign-in:
 * "Test Patient 60 বছর · নথি: 10 · শেষ নথি: 2026-10-18".
 *
 * <p>The assertions read the {@code meta} lines, never the whole page: hrefs carry UUIDs full of ASCII digits, so
 * "the page has no ASCII digit" could not be true and "the page contains 60" could be true by accident.
 */
class BanglaNumbersOnPagesTest {

    private static final Instant NOW = WebUiTestKit.NOW;
    private static final UUID FAMILY = WebUiTestKit.FAMILY;
    private static final UUID PATIENT = UUID.randomUUID();
    private static final Pattern META = Pattern.compile("class=\"(?:meta|small)\">([^<]*)<");
    private static final Pattern ASCII_DIGIT = Pattern.compile("[0-9]");

    private final WebUiTestKit kit = new WebUiTestKit();
    private final TimelineUseCase timeline = kit.timeline;
    private final DocumentPages ui = kit.documents;
    private final WebTestClient client = kit.client;

    BanglaNumbersOnPagesTest() {
        PatientProfile ma = new PatientProfile(PATIENT, FAMILY, "Test Patient", 60, PatientProfile.Sex.FEMALE, List.of("diabetes"), NOW, NOW);
        when(timeline.me(FAMILY)).thenReturn(Mono.just(new FamilyOverview(
                new FamilyAccount(FAMILY, "+8801700000000", "Tanvir", FamilyAccount.Plan.FREE, NOW, NOW),
                List.of(new PatientSummary(ma, true, 10, NOW)))));
        when(timeline.timeline(eq(FAMILY), eq(PATIENT), any())).thenReturn(Mono.just(new TimelinePage(List.of(
                new TimelineEntry(UUID.randomUUID(), LocalDate.parse("2026-10-12"), false, "lab_report", null, "DONE",
                        14, 2, 1, 3, "f/p/d/page-1.jpg", NOW)), null)));
    }

    private String page(String path, String lang) {
        return client.get().uri(path).cookie("baymax_session", "tok").cookie("baymax_lang", lang)
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
    }

    private static List<String> metaLines(String html) {
        List<String> lines = new ArrayList<>();
        Matcher m = META.matcher(html);
        while (m.find()) {
            if (!m.group(1).isBlank()) {
                lines.add(m.group(1));
            }
        }
        return lines;
    }

    /** A6: the পরিবার card is s21's "age · N papers", and the consent date is the patient's day in Bangla. */
    @Test
    void theFamilyCardShowsAgeAndCountAndTheConsentDateInBanglaDigits() {
        List<String> meta = metaLines(page("/app/family", "bn"));
        assertThat(meta).contains("৬০ বছর · ১০টি কাগজ", "আপনি নিশ্চিত করেছেন ১৮ অক্টোবর ২০২৬");
        assertThat(meta).as("no meta line on the Bangla family tab carries an ASCII digit")
                .isNotEmpty().noneMatch(line -> ASCII_DIGIT.matcher(line).find());
    }

    @Test
    void theEnglishFamilyCardKeepsAsciiDigits() {
        List<String> meta = metaLines(page("/app/family", "en"));
        assertThat(meta).contains("60 years · 10 papers", "You confirmed on 2026-10-18");
    }

    @Test
    void theTimelineDateAndCountsAreInBanglaDigitsAndEnglishIsUnchanged() {
        List<String> bn = metaLines(page("/app/timeline?patient=" + PATIENT, "bn"));
        assertThat(bn).contains("১২ অক্টোবর ২০২৬", "মান ১৪ · ওষুধ ২ · ফলো-আপ ১", "৩ টি অংশ মূল ছবিতে খুঁজে পাওয়া যায়নি");

        List<String> en = metaLines(page("/app/timeline?patient=" + PATIENT, "en"));
        assertThat(en).contains("2026-10-12", "Values 14 · Medicines 2 · Follow-ups 1", "3 items could not be found in the original image");
    }

    /** The other direction: a value keeps the digits printed on the paper, on a Bangla page too. */
    @Test
    void theDocumentPageDatesAreBanglaButEveryValueKeepsItsPrintedDigits() {
        UUID id = UUID.randomUUID();
        DocumentView view = new DocumentView(id.toString(), "DONE", null, "lab_report", "2026-09-01", null, 0.9, "m", 1,
                List.of(Map.of("name", "HbA1c", "value", "8.2", "unit", "%", "crop_key", "f/p/d/crop-v1.jpg"),
                        Map.of("name", "Creatinine", "value", "130", "unit", "µmol/L", "crop_key", "f/p/d/crop-v2.jpg")),
                List.of(), List.of(Map.of("instruction", "আবার দেখাবেন", "due_date", "2026-11-15", "crop_key", "f/p/d/crop-f1.jpg")), null, null);
        UiCopy copy = new UiCopy();

        // B2: the follow-up's date is the line under its paper quote (next.followup), the value its own span
        String bn = ui.render(Lang.BN, view, id, Optional.empty());
        assertThat(metaLines(bn)).contains("১ সেপ্টেম্বর ২০২৬");
        assertThat(bn).contains("<p class=\"due\">" + copy.t(Lang.BN, "next.followup", "১৫ নভেম্বর ২০২৬") + "</p>");
        assertThat(bn).contains("<span class=\"val\">8.2</span>").contains("<span class=\"val\">130</span>")
                .doesNotContain("৮.২").doesNotContain("১৩০");

        String en = ui.render(Lang.EN, view, id, Optional.empty());
        assertThat(metaLines(en)).contains("2026-09-01");
        assertThat(en).contains("<p class=\"due\">" + copy.t(Lang.EN, "next.followup", "2026-11-15") + "</p>");
        assertThat(en).contains("<span class=\"val\">8.2</span>").contains("<span class=\"val\">130</span>");
    }

    /** A date the extractor did not normalise is shown as written, never invented. */
    @Test
    void aDocumentDateThatIsNotIsoIsShownAsWritten() {
        UUID id = UUID.randomUUID();
        DocumentView view = new DocumentView(id.toString(), "DONE", null, "prescription", "12/10/26", null, 0.9, "m", 1,
                List.of(), List.of(), List.of(), null, null);
        assertThat(metaLines(ui.render(Lang.BN, view, id, Optional.empty()))).contains("12/10/26");
    }
}
