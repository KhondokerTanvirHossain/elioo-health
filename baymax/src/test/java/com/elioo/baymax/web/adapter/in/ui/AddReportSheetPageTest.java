package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.healthrecord.domain.DocumentAllowance;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.web.domain.PatientSummary;
import com.elioo.baymax.web.domain.TimelinePage;
import com.elioo.baymax.web.domain.UploadOptions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** s07 as the server renders it: every state, and a form that posts with no script at all. */
class AddReportSheetPageTest {

    private static final Instant NOW = WebUiTestKit.NOW;
    private final WebUiTestKit kit = new WebUiTestKit();

    static PatientSummary patient(String name, int age, boolean owner) {
        return new PatientSummary(new PatientProfile(UUID.nameUUIDFromBytes(name.getBytes()), WebUiTestKit.FAMILY, name, age,
                PatientProfile.Sex.FEMALE, List.of(), NOW, NOW), owner, 0, null);
    }

    static DocumentAllowance used(long n) {
        return new DocumentAllowance(true, 3, n, YearMonth.of(2026, 10));
    }

    private String sheet() {
        return kit.page("/app/add", "bn");
    }

    /** As the browser sends it: the query already encoded, so the test client must not encode it again. */
    private String signedIn(String uri) {
        return kit.client.get().uri(java.net.URI.create(uri)).cookie("baymax_session", "tok").exchange()
                .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
    }

    /**
     * The form is complete without a script: real file inputs, a submit, nothing disabled. That it posts somewhere that
     * answers is A8's half ({@code POST /app/add} has no route until A8 deploys; the papers tab's inline form is gone).
     */
    @Test
    void withOnePatientItIsChosenAndTheFormNeedsNoScript() {
        PatientSummary ma = patient("আম্মা", 64, true);
        when(kit.uploads.uploadOptions(WebUiTestKit.FAMILY)).thenReturn(Mono.just(new UploadOptions(List.of(ma), List.of(), used(0))));
        String html = sheet();
        assertThat(html).contains("<form id=\"add\" class=\"add\" method=\"post\" enctype=\"multipart/form-data\" action=\"/app/add\"");
        assertThat(html).containsPattern("<input type=\"radio\" name=\"patient\" value=\"" + ma.patient().id() + "\" required checked>");
        assertThat(html).contains("আম্মা · ৬৪");   // the age in Bangla digits (rule 13); the relation joins when the profile has one
        // three ways in, each a real input the browser posts on its own
        assertThat(html).contains("name=\"file\" accept=\"image/*\" capture=\"environment\"")
                .contains("name=\"file\" accept=\"image/*\" multiple")
                .contains("name=\"file\" accept=\"application/pdf,image/*\"");
        assertThat(html).contains("<button type=\"submit\" class=\"btn primary nojs\">পাঠান</button>");
        assertThat(html).doesNotContain(" disabled");   // nothing the server renders waits for a script to enable it
        assertThat(classed(Dom.parse(html), "quota")).singleElement().extracting(Dom.Element::ownText)
                .isEqualTo("এ মাসে বিনা খরচে আরও ৩টি কাগজ পড়াতে পারবেন। কোনো ছবি পড়তে না পারলে সেটা গোনা হবে না।");
        assertThat(html).doesNotContain("<script");   // A7 ships no script; D4 adds exactly one
    }

    static List<Dom.Element> classed(Dom dom, String cls) {
        return dom.all.stream().filter(e -> e.classes().contains(cls)).toList();
    }

    /** s07: each of the three tips is a pictogram over its words, and the three pictograms are three different icons. */
    @Test
    void eachTipHasItsOwnPictogram() {
        when(kit.uploads.uploadOptions(WebUiTestKit.FAMILY)).thenReturn(Mono.just(new UploadOptions(List.of(patient("আম্মা", 64, true)), List.of(), used(0))));
        Dom dom = Dom.parse(sheet());
        List<Dom.Element> tips = dom.all.stream().filter(e -> e.tag().equals("li") && e.parent().parent().classes().contains("tips")).toList();
        assertThat(tips).hasSize(3);
        java.util.Set<String> drawings = new java.util.HashSet<>();
        for (Dom.Element tip : tips) {
            Dom.Element tile = tip.children().stream().filter(c -> c.classes().contains("tip-i")).findFirst().orElseThrow(() ->
                    new AssertionError("no pictogram tile in " + tip.describe()));
            assertThat(tile.attrs()).containsEntry("aria-hidden", "true");
            Dom.Element svg = tile.children().stream().filter(c -> c.tag().equals("svg")).findFirst().orElseThrow();
            drawings.add(svg.children().stream().map(c -> c.tag() + c.attrs()).toList().toString());
            assertThat(tip.children().stream().filter(c -> c.tag().equals("span") && !c.classes().contains("tip-i"))
                    .map(Dom.Element::ownText)).singleElement().asString().isNotBlank();
        }
        assertThat(drawings).hasSize(3);
    }

    /**
     * s07 fills the chosen chip (primary container, primary border) and leaves the others plain. Read from the rules that
     * match each chip in the served stylesheet, in both directions: the chosen one filled, an unchosen one not.
     */
    @Test
    void theChosenChipIsFilledAndAnUnchosenOneIsNot() {
        List<Css.Rule> rules = Css.rules(kit.servedText("/assets/baymax.css"));
        PatientSummary ma = patient("আম্মা", 64, true);
        PatientSummary baba = patient("আব্বু", 72, true);
        when(kit.uploads.uploadOptions(WebUiTestKit.FAMILY)).thenReturn(Mono.just(new UploadOptions(List.of(ma, baba), List.of(), used(0))));
        Dom dom = Dom.parse(kit.page("/app/add?patient=" + baba.patient().id(), "bn"));
        List<Dom.Element> chips = classed(dom, "chip").stream().filter(c -> c.parent().classes().contains("chips")).toList();
        assertThat(chips).hasSize(2);
        for (Dom.Element chip : chips) {
            boolean chosen = chip.children().get(0).attrs().containsKey("checked");
            String background = Css.valueOf(Css.winner(rules, chip, "background").orElseThrow(), "background");
            assertThat(background).as(chip.describe()).isEqualTo(chosen ? "var(--c-primary-container)" : "var(--c-surface)");
        }
        assertThat(chips.stream().filter(c -> c.children().get(0).attrs().containsKey("checked"))).hasSize(1);
    }

    /** The h1 already says "কার কাগজ?": the legend says it to a screen reader only, as drawn, not a second time on screen. */
    @Test
    void theLegendIsForScreenReadersOnly() {
        List<Css.Rule> rules = Css.rules(kit.servedText("/assets/baymax.css"));
        when(kit.uploads.uploadOptions(WebUiTestKit.FAMILY)).thenReturn(Mono.just(new UploadOptions(List.of(patient("আম্মা", 64, true)), List.of(), used(0))));
        Dom.Element legend = Dom.parse(sheet()).all.stream().filter(e -> e.tag().equals("legend")).findFirst().orElseThrow();
        assertThat(legend.ownText()).isEqualTo("কার কাগজ?");
        assertThat(Css.valueOf(Css.winner(rules, legend, "clip-path").orElseThrow(() -> new AssertionError("legend is visible")), "clip-path"))
                .isEqualTo("inset(50%)");
        assertThat(Css.valueOf(Css.winner(rules, legend, "position").orElseThrow(), "position")).isEqualTo("absolute");
    }

    @Test
    void withTwoPatientsNeitherIsChosenAndTheChoiceIsRequired() {
        PatientSummary ma = patient("আম্মা", 64, true);
        PatientSummary baba = patient("আব্বু", 72, true);
        when(kit.uploads.uploadOptions(WebUiTestKit.FAMILY)).thenReturn(Mono.just(new UploadOptions(List.of(ma, baba), List.of(), used(1))));
        String html = sheet();
        // match the patient field, not the page: D3 adds a checked "একটা রিপোর্ট" radio to the same form
        assertThat(html).doesNotContainPattern("name=\"patient\"[^>]*checked");
        assertThat(html).containsPattern("name=\"patient\" value=\"" + ma.patient().id() + "\" required>")
                .containsPattern("name=\"patient\" value=\"" + baba.patient().id() + "\" required>");
        assertThat(html).contains("কার কাগজ বেছে নিন — Medioo নিজে আন্দাজ করবে না");
    }

    /**
     * The family chose on the page it came from (a patient's কাগজপত্র tab): that patient is checked among several. An id
     * the viewer may not send for is ignored, never trusted, so a guessed or shared id never preselects anyone.
     */
    @Test
    void aPatientNamedByTheLinkIsChosenAmongSeveralAndAnIdNotOfferedIsIgnored() {
        PatientSummary ma = patient("আম্মা", 64, true);
        PatientSummary baba = patient("আব্বু", 72, true);
        PatientSummary shared = patient("খালা", 60, false);
        when(kit.uploads.uploadOptions(WebUiTestKit.FAMILY)).thenReturn(Mono.just(new UploadOptions(List.of(ma, baba), List.of(shared), used(0))));

        String chosen = kit.page("/app/add?patient=" + baba.patient().id(), "bn");
        assertThat(chosen).containsPattern("name=\"patient\" value=\"" + baba.patient().id() + "\" required checked>")
                .containsPattern("name=\"patient\" value=\"" + ma.patient().id() + "\" required>");

        for (String id : List.of(shared.patient().id().toString(), UUID.randomUUID().toString(), "not-a-uuid")) {
            String html = kit.page("/app/add?patient=" + id, "bn");
            assertThat(html).as(id).doesNotContainPattern("name=\"patient\"[^>]*checked").doesNotContain(shared.patient().id().toString());
        }
    }

    @Test
    void atTheCapTheSheetSaysSoBeforeAnyUploadAndOffersNoFileInput() {
        when(kit.uploads.uploadOptions(WebUiTestKit.FAMILY)).thenReturn(Mono.just(new UploadOptions(List.of(patient("আম্মা", 64, true)), List.of(), used(3))));
        String html = sheet();
        assertThat(html).doesNotContain("type=\"file\"");
        assertThat(html).contains("এ মাসের ৩টি বিনা খরচের কাগজ পড়া হয়ে গেছে").contains("১ নভেম্বর ২০২৬");
    }

    /** The other direction of the cap: one slot left still offers the inputs, and says one remains. */
    @Test
    void oneSlotLeftStillOffersTheInputs() {
        when(kit.uploads.uploadOptions(WebUiTestKit.FAMILY)).thenReturn(Mono.just(new UploadOptions(List.of(patient("আম্মা", 64, true)), List.of(), used(2))));
        String html = sheet();
        assertThat(html).contains("type=\"file\"").contains("আরও ১টি কাগজ").doesNotContain("পড়া হয়ে গেছে");
    }

    @Test
    void aViewOnlyMemberIsToldWhyAndOfferedNoFileInput() {
        when(kit.uploads.uploadOptions(WebUiTestKit.FAMILY)).thenReturn(Mono.just(new UploadOptions(List.of(), List.of(patient("আম্মা", 64, false)), used(0))));
        String html = sheet();
        assertThat(html).doesNotContain("type=\"file\"").contains("এই ফাইলে আপনি শুধু দেখতে পারেন — নতুন কাগজ পাঠানো যাবে না।");
    }

    @Test
    void aPayingFamilySeesNoQuotaLineAndTheSheetFollowsTheLanguageCookie() {
        when(kit.uploads.uploadOptions(WebUiTestKit.FAMILY)).thenReturn(Mono.just(new UploadOptions(List.of(patient("Ma", 64, true)), List.of(),
                DocumentAllowance.unlimited(YearMonth.of(2026, 10)))));
        assertThat(classed(Dom.parse(sheet()), "quota")).isEmpty();
        String en = kit.client.get().uri("/app/add").cookie("baymax_session", "tok").cookie("baymax_lang", "en").exchange()
                .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        assertThat(en).startsWith("<!doctype html><html lang=\"en\">").contains("Whose paper?").doesNotContain("কার কাগজ");
    }

    @Test
    void theCloseLinkGoesBackOnlyToALocalPath() {
        when(kit.uploads.uploadOptions(WebUiTestKit.FAMILY)).thenReturn(Mono.just(new UploadOptions(List.of(patient("আম্মা", 64, true)), List.of(), used(0))));
        String html = kit.client.get().uri("/app/add?back=//evil.example/x").cookie("baymax_session", "tok").exchange()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(html).contains("href=\"/app/home\" aria-label=\"বন্ধ\"").doesNotContain("evil.example");
    }

    /** No-JS URL of a sheet (spec §3): drawn over the tab it was opened from, which is inert behind it. */
    @Test
    void theSheetIsDrawnOverTheTabItWasOpenedFrom() {
        when(kit.uploads.uploadOptions(WebUiTestKit.FAMILY)).thenReturn(Mono.just(new UploadOptions(List.of(patient("আম্মা", 64, true)), List.of(), used(0))));
        String back = "/app/timeline?patient=" + WebUiTestKit.MA_ID;
        Dom dom = Dom.parse(signedIn("/app/add?back=" + URLEncoder.encode(back, StandardCharsets.UTF_8)));
        assertThat(dom.unclosed).isEmpty();
        List<Dom.Element> current = dom.all.stream().filter(e -> "page".equals(e.attrs().get("aria-current"))).toList();
        assertThat(current).hasSize(1);
        assertThat(current.get(0).attrs().get("href")).isEqualTo(Tab.PAPERS.path);
        assertThat(dom.all.stream().filter(e -> e.classes().contains("shell")).findFirst().orElseThrow().attrs()).containsKey("inert");
        assertThat(dom.all.stream().filter(e -> e.classes().contains("icon-btn")).findFirst().orElseThrow().attrs().get("href"))
                .isEqualTo(back);
    }

    /**
     * The timeline's inline form is gone: the owner gets a link to the sheet for that patient, which comes back there —
     * "কাগজ পাঠান" over a list, and s16's "ছবি তুলুন" on the empty panel (C3).
     */
    @Test
    void thePapersTabLinksTheOwnerToTheSheetAndPostsNoFormOfItsOwn() {
        kit.familyWith(kit.ma(1));
        com.elioo.baymax.web.domain.TimelineEntry paper = new com.elioo.baymax.web.domain.TimelineEntry(UUID.randomUUID(),
                java.time.LocalDate.parse("2026-10-12"), false, "prescription", null, "DONE", 0, 2, 0, 0, "k", Instant.parse("2026-10-12T08:00:00Z"),
                null, 0, null, null);
        when(kit.timeline.timeline(eq(WebUiTestKit.FAMILY), eq(WebUiTestKit.MA_ID), any(), any()))
                .thenReturn(Mono.just(new TimelinePage(List.of(paper), null)), Mono.just(new TimelinePage(List.of(), null)));
        String back = URLEncoder.encode("/app/timeline?patient=" + WebUiTestKit.MA_ID, StandardCharsets.UTF_8);
        for (String words : List.of(kit.copy.t(Lang.BN, "timeline.add"), kit.copy.t(Lang.BN, "chat.empty.action"))) {
            String html = kit.page("/app/timeline", "bn");
            assertThat(html).as(words).contains("href=\"/app/add?patient=" + WebUiTestKit.MA_ID + "&amp;back=" + back + "\"")
                    .contains("<span>" + words + "</span></a>")
                    .doesNotContain("enctype=\"multipart/form-data\"").doesNotContain("type=\"file\"");
        }
    }

    /** The other direction: a shared patient's papers offer no way to add one. */
    @Test
    void aSharedPatientsPapersOfferNoAddLink() {
        PatientSummary shared = kit.patient("খালা", false, 0);
        kit.familyWith(shared);
        when(kit.timeline.timeline(eq(WebUiTestKit.FAMILY), eq(shared.patient().id()), any(), any())).thenReturn(Mono.just(new TimelinePage(List.of(), null)));
        assertThat(kit.page("/app/timeline", "bn")).doesNotContain("/app/add").doesNotContain("কাগজ পাঠান");
    }
}
