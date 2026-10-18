package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.nudge.application.port.out.NudgeDataPort;
import com.elioo.baymax.web.domain.PatientSummary;
import com.elioo.baymax.web.domain.TimelinePage;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** s21 basics: what the owner may change, what a share member may only see, and the account. */
class FamilyViewTest {

    private final WebUiTestKit kit = new WebUiTestKit();

    private String family(PatientSummary p, boolean optedOut) {
        kit.familyWith(p);
        when(kit.nudgeData.patient(p.patient().id())).thenReturn(Mono.just(new NudgeDataPort.PatientRef(p.patient().id(),
                WebUiTestKit.FAMILY, List.of("diabetes"), optedOut ? WebUiTestKit.NOW : null, null)));
        return kit.page("/app/family", "bn");
    }

    @Test
    void theOwnerCanSaveTheFlagsAndTurnRemindersOff() {
        PatientSummary ma = kit.patient("আম্মা", true, 12);
        String html = family(ma, false);
        assertThat(html).contains("action=\"/app/patients/" + ma.patient().id() + "/profile\"")
                .contains("<button type=\"submit\">সেভ করুন</button>")
                .contains("action=\"/app/patients/" + ma.patient().id() + "/nudges/opt-out\"")
                .contains("রিমাইন্ডার চাই না — আম্মা").contains("আম্মা: রিমাইন্ডার চালু আছে");
        assertThat(html).contains("৬৪ বছর · ১২টি কাগজ");   // Bangla digits for UI counts (rule 13)
    }

    /** The other direction: a share member sees the same papers and changes nothing (spec §3, D30). */
    @Test
    void aShareMemberSeesButHasNoSaveAndNoReminderSwitch() {
        PatientSummary shared = kit.patient("আম্মা", false, 12);
        String html = family(shared, false);
        assertThat(html).contains("শেয়ার করা").doesNotContain("<button type=\"submit\">সেভ করুন</button>")
                .doesNotContain("/nudges/opt-out");
    }

    @Test
    void theAccountShowsTheOwnersNumberOnceAndOnlyHere() {
        PatientSummary ma = kit.patient("আম্মা", true, 0);
        String html = family(ma, false);
        assertThat(java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(PhoneDisplay.full(WebUiTestKit.OWNER_NUMBER)))
                .matcher(html).results().count()).isEqualTo(1);
        assertThat(html).contains("<form method=\"post\" action=\"/app/logout\">")
                .contains("href=\"/lang/en?back=%2Fapp%2Ffamily\"");
        // the other direction: no other tab carries a phone number in any form
        when(kit.timeline.timeline(eq(WebUiTestKit.FAMILY), eq(ma.patient().id()), any())).thenReturn(Mono.just(new TimelinePage(List.of(), null)));
        for (Tab t : List.of(Tab.CHAT, Tab.PAPERS)) {
            assertThat(kit.page(t.path, "bn")).as(t.path).doesNotContainPattern(BaymaxWebUiRouterTest.BD_MOBILE);
        }
    }

    @Test
    void savingComesBackToTheTabAndSaysSo() {
        PatientSummary ma = kit.patient("আম্মা", true, 0);
        family(ma, false);
        when(kit.timeline.updateChronicFlags(eq(WebUiTestKit.FAMILY), eq(ma.patient().id()), any())).thenReturn(Mono.just(ma.patient()));
        kit.client.post().uri("/app/patients/" + ma.patient().id() + "/profile").cookie("baymax_session", "tok")
                .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED).bodyValue("flag=diabetes")
                .exchange().expectStatus().isSeeOther()
                .expectHeader().valueEquals(org.springframework.http.HttpHeaders.LOCATION, "/app/family?patient=" + ma.patient().id() + "&saved=1");
        assertThat(kit.page("/app/family?patient=" + ma.patient().id() + "&saved=1", "bn")).contains("সেভ হয়েছে");
        assertThat(kit.page("/app/family?patient=" + ma.patient().id(), "bn")).doesNotContain("সেভ হয়েছে");
    }

    /** The old profile URL (WhatsApp opt-out pages, bookmarks) opens the পরিবার tab with the patient chosen. */
    @Test
    void theOldProfileUrlOpensTheFamilyTab() {
        UUID id = UUID.randomUUID();
        kit.client.get().uri("/app/patients/" + id + "/profile").cookie("baymax_session", "tok").exchange().expectStatus().isFound()
                .expectHeader().valueEquals(org.springframework.http.HttpHeaders.LOCATION, "/app/family?patient=" + id);
    }
}
