package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.nudge.application.port.out.NudgeDataPort;
import com.elioo.baymax.web.domain.PatientSummary;
import com.elioo.baymax.web.domain.TimelinePage;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** s21 basics: what the owner may change, what a share member may only see, and the account. */
class FamilyViewTest {

    private static final Pattern INPUT = Pattern.compile("<input[^>]*>");
    private static final Pattern SAVED = Pattern.compile("<p class=\"saved\" role=\"status\">");

    private final WebUiTestKit kit = new WebUiTestKit();

    private String family(PatientSummary p, boolean optedOut) {
        kit.familyWith(p);
        when(kit.nudgeData.patient(p.patient().id())).thenReturn(Mono.just(new NudgeDataPort.PatientRef(p.patient().id(),
                WebUiTestKit.FAMILY, List.of("diabetes"), optedOut ? WebUiTestKit.NOW : null, null)));
        return kit.page("/app/family", "bn");
    }

    /** The same patient with other chronic flags and another consent instant. */
    private static PatientSummary with(PatientSummary p, List<String> flags, Instant consentAt) {
        PatientProfile x = p.patient();
        return new PatientSummary(new PatientProfile(x.id(), x.familyId(), x.name(), x.age(), x.sex(), flags, consentAt, x.createdAt()),
                p.owner(), p.documents(), p.lastDocumentAt());
    }

    /** Every {@code <input>} of the flags form (the one that posts to the profile URL), read element by element. */
    private static List<String> flagInputs(String html, UUID patientId) {
        String open = "action=\"/app/patients/" + patientId + "/profile\">";
        int from = html.indexOf(open);
        assertThat(from).as("the flags form is on the page").isPositive();
        String form = html.substring(from, html.indexOf("</form>", from));
        List<String> inputs = new ArrayList<>();
        Matcher m = INPUT.matcher(form);
        while (m.find()) {
            inputs.add(m.group());
        }
        assertThat(inputs).as("the flags form's inputs: five boxes and the 'other' field").hasSize(ProfilePages.FLAGS.size() + 1);
        return inputs;
    }

    private static long count(Pattern p, String html) {
        return p.matcher(html).results().count();
    }

    @Test
    void theOwnerCanSaveTheFlagsAndTurnRemindersOff() {
        PatientSummary ma = kit.patient("আম্মা", true, 12);
        String html = family(ma, false);
        assertThat(html).contains("action=\"/app/patients/" + ma.patient().id() + "/profile\"")
                .contains("<button type=\"submit\">সেভ করুন</button>")
                .contains("action=\"/app/patients/" + ma.patient().id() + "/nudges/opt-out\"")
                .contains("আম্মার রিমাইন্ডার চাই না").contains("আম্মার রিমাইন্ডার চালু আছে");
        assertThat(html).contains("৬৪ বছর · ১২টি কাগজ");   // Bangla digits for UI counts (rule 13)
        // the owner's inputs are live: the other direction of the share member's disabled form
        assertThat(flagInputs(html, ma.patient().id())).allSatisfy(i -> assertThat(i).doesNotContain("disabled"));
    }

    /** The other direction: a share member sees the same papers and changes nothing (spec §3, D30). */
    @Test
    void aShareMemberSeesButHasNoSaveAndNoReminderSwitch() {
        PatientSummary shared = kit.patient("আম্মা", false, 12);
        String html = family(shared, false);
        assertThat(html).contains("শেয়ার করা").doesNotContain("<button type=\"submit\">সেভ করুন</button>")
                .doesNotContain("/nudges/opt-out");
        assertThat(flagInputs(html, shared.patient().id())).allSatisfy(i -> assertThat(i).endsWith(" disabled>"));
    }

    /** Reminders already stopped: the page says so, and offers no switch (there is nothing left to stop). */
    @Test
    void remindersOffSaysSoAndOffersNoSwitch() {
        PatientSummary ma = kit.patient("আম্মা", true, 0);
        String html = family(ma, true);
        assertThat(html).contains("আম্মার রিমাইন্ডার বন্ধ আছে।").doesNotContain("রিমাইন্ডার চালু আছে")
                .doesNotContain("/nudges/opt-out").doesNotContain("রিমাইন্ডার চাই না");
    }

    /**
     * F8, the other ending and the other language: a name ending in a consonant takes ের (রহিমের), never "রহিম:" or a
     * glued "-এর"; the English page writes the apostrophe ("Ma's reminders are on", escaped as {@code &#39;}).
     */
    @Test
    void theNameTakesItsPossessiveOnTheFamilyTabForEitherEndingAndInEnglish() {
        String bn = family(kit.patient("রহিম", true, 3), false);
        assertThat(bn).contains("<h2>রহিমের তথ্য</h2>").contains("রহিমের রিমাইন্ডার চালু আছে").contains("রহিমের রিমাইন্ডার চাই না")
                .doesNotContain("রহিম:").doesNotContain("রহিম-এর").doesNotContain("— রহিম");

        PatientSummary ma = kit.patient("Ma", true, 3);
        family(ma, false);
        String en = kit.page("/app/family", "en");
        assertThat(en).contains("<h2>Ma&#39;s details</h2>").contains("Ma&#39;s reminders are on").contains("Turn off Ma&#39;s reminders")
                .doesNotContain("Ma:");
    }

    /** What the family ticked comes back ticked, and what they wrote comes back written: the next save keeps both. */
    @Test
    void savedFlagsComeBackTickedAndTheOtherTextFilledIn() {
        PatientSummary ma = with(kit.patient("আম্মা", true, 0), List.of("diabetes", "asthma"), WebUiTestKit.NOW);
        List<String> inputs = flagInputs(family(ma, false), ma.patient().id());
        assertThat(inputs).filteredOn(i -> i.contains("value=\"diabetes\"")).singleElement().asString().contains(" checked");
        assertThat(inputs).filteredOn(i -> i.contains("value=\"hypertension\"")).singleElement().asString().doesNotContain("checked");
        assertThat(inputs).filteredOn(i -> i.contains("name=\"other\"")).singleElement().asString().contains("value=\"asthma\"");
    }

    /** The consent day is the patient's (Dhaka), not UTC's: 20:00 UTC on the 17th is already the 18th in Dhaka. */
    @Test
    void theConsentDateIsThePatientsDayNotUtcs() {
        PatientSummary ma = with(kit.patient("আম্মা", true, 0), List.of(), Instant.parse("2026-10-17T20:00:00Z"));
        assertThat(family(ma, false)).contains("আপনি নিশ্চিত করেছেন ১৮ অক্টোবর ২০২৬").doesNotContain("১৭ অক্টোবর");
    }

    @Test
    void theAccountShowsTheOwnersNumberOnceAndOnlyHere() {
        PatientSummary ma = kit.patient("আম্মা", true, 0);
        String html = family(ma, false);
        assertThat(count(BaymaxWebUiRouterTest.BD_MOBILE, html)).as("a phone number in any form on পরিবার").isEqualTo(1);
        assertThat(html).contains(PhoneDisplay.full(WebUiTestKit.OWNER_NUMBER));
        assertThat(html).contains("<form method=\"post\" action=\"/app/logout\">")
                .contains("href=\"/lang/en?back=%2Fapp%2Ffamily\"");
        // the other direction: no other tab carries a phone number in any form
        when(kit.timeline.timeline(eq(WebUiTestKit.FAMILY), eq(ma.patient().id()), any(), any())).thenReturn(Mono.just(new TimelinePage(List.of(), null)));
        for (Tab t : List.of(Tab.CHAT, Tab.PAPERS)) {
            assertThat(kit.page(t.path, "bn")).as(t.path).doesNotContainPattern(BaymaxWebUiRouterTest.BD_MOBILE);
        }
    }

    /**
     * "Only here", derived: every signed-in page the router renders (each route, each fixture, both languages), other
     * than পরিবার itself, carries no phone number in any form. A page added later is swept without being listed here.
     */
    @Test
    void noOtherSignedInPageCarriesTheNumber() {
        List<String> swept = new ArrayList<>();
        for (RouteTable.Route route : RouteTable.of(kit.router)) {
            if (route.key().equals("GET " + Tab.FAMILY.path)) {
                continue;
            }
            for (PageFixtures.Fixture f : PageFixtures.allOf(route)) {
                if (!f.signedIn()) {
                    continue;
                }
                for (String lang : RenderedPages.LANGS) {
                    WebUiTestKit k = new WebUiTestKit();
                    f.stubs().accept(k);
                    EntityExchangeResult<byte[]> r = RenderedPages.exchange(k, route, f, lang);
                    MediaType type = r.getResponseHeaders().getContentType();
                    if (type == null || !type.isCompatibleWith(MediaType.TEXT_HTML) || r.getResponseBody() == null) {
                        continue;
                    }
                    String key = route.key() + " " + f.uri() + " #" + f.name() + " " + lang;
                    assertThat(new String(r.getResponseBody(), java.nio.charset.StandardCharsets.UTF_8)).as(key)
                            .doesNotContainPattern(BaymaxWebUiRouterTest.BD_MOBILE);
                    swept.add(key);
                }
            }
        }
        // a sweep over nothing passes vacuously: it must have read the other two tabs and the document page
        assertThat(swept).as("signed-in pages swept").hasSizeGreaterThanOrEqualTo(10)
                .anyMatch(k -> k.startsWith("GET " + Tab.CHAT.path + " "))
                .anyMatch(k -> k.startsWith("GET " + Tab.PAPERS.path + " "))
                .anyMatch(k -> k.startsWith("GET /app/documents/{id} "));
    }

    @Test
    void savingComesBackToTheTabAndSaysSo() {
        PatientSummary ma = kit.patient("আম্মা", true, 0);
        family(ma, false);
        when(kit.timeline.updateChronicFlags(eq(WebUiTestKit.FAMILY), eq(ma.patient().id()), any())).thenReturn(Mono.just(ma.patient()));
        kit.client.post().uri("/app/patients/" + ma.patient().id() + "/profile").cookie("baymax_session", "tok")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).bodyValue("flag=diabetes&flag=kidney&other=+asthma+")
                .exchange().expectStatus().isSeeOther()
                .expectHeader().valueEquals(HttpHeaders.LOCATION, "/app/family?patient=" + ma.patient().id() + "&saved=1");
        // what was ticked and written is what is saved: the boxes, then the trimmed 'other' text
        verify(kit.timeline).updateChronicFlags(WebUiTestKit.FAMILY, ma.patient().id(), List.of("diabetes", "kidney", "asthma"));
        assertThat(count(SAVED, kit.page("/app/family?patient=" + ma.patient().id() + "&saved=1", "bn"))).isEqualTo(1);
        assertThat(count(SAVED, kit.page("/app/family?patient=" + ma.patient().id(), "bn"))).isZero();
    }

    /** The old profile URL (WhatsApp opt-out pages, bookmarks) opens the পরিবার tab with the patient chosen. */
    @Test
    void theOldProfileUrlOpensTheFamilyTab() {
        PatientSummary ma = kit.patient("আম্মা", true, 0);
        family(ma, false);
        kit.client.get().uri("/app/patients/" + ma.patient().id() + "/profile").cookie("baymax_session", "tok").exchange()
                .expectStatus().isFound()
                .expectHeader().valueEquals(HttpHeaders.LOCATION, "/app/family?patient=" + ma.patient().id());
    }

    /** A patient this family cannot see: the old URL still redirects, and the tab it lands on answers 404. */
    @Test
    void theOldProfileUrlOfAnUnknownPatientLandsOnA404() {
        family(kit.patient("আম্মা", true, 0), false);
        UUID id = UUID.randomUUID();
        kit.client.get().uri("/app/patients/" + id + "/profile").cookie("baymax_session", "tok").exchange()
                .expectStatus().isFound()
                .expectHeader().valueEquals(HttpHeaders.LOCATION, "/app/family?patient=" + id);
        kit.client.get().uri("/app/family?patient=" + id).cookie("baymax_session", "tok").exchange().expectStatus().isNotFound();
    }

    /** The server, not only the missing button, refuses a share member's reminder switch: 404 and nothing stopped. */
    @Test
    void aShareMemberCannotStopRemindersByPosting() {
        PatientSummary shared = kit.patient("আম্মা", false, 0);
        family(shared, false);
        // the use case would succeed if reached: the refusal must come from the owner check, not a failing mock
        when(kit.optOut.optOutPatient(any())).thenReturn(Mono.empty());
        kit.client.post().uri("/app/patients/" + shared.patient().id() + "/nudges/opt-out").cookie("baymax_session", "tok")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).bodyValue("scope=patient")
                .exchange().expectStatus().isNotFound();
        verify(kit.optOut, never()).optOutPatient(any());
    }

    /** The other direction: the owner's post stops them, and comes back to the tab. */
    @Test
    void theOwnerStopsRemindersAndComesBackToTheTab() {
        PatientSummary ma = kit.patient("আম্মা", true, 0);
        family(ma, false);
        when(kit.optOut.optOutPatient(ma.patient().id())).thenReturn(Mono.empty());
        kit.client.post().uri("/app/patients/" + ma.patient().id() + "/nudges/opt-out").cookie("baymax_session", "tok")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).bodyValue("scope=patient")
                .exchange().expectStatus().isSeeOther()
                .expectHeader().valueEquals(HttpHeaders.LOCATION, "/app/family?patient=" + ma.patient().id());
        verify(kit.optOut).optOutPatient(ma.patient().id());
    }
}
