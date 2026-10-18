package com.elioo.baymax.wa.application.service;

import com.elioo.baymax.common.error.FreeTierExceededException;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.application.port.in.DocumentIntakeUseCase;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.Upload;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.outbound.application.service.Copy;
import com.elioo.baymax.outbound.domain.OutboundMessage;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.wa.application.port.out.WaMessagingPort;
import com.elioo.baymax.wa.domain.WaInbound;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BMX-10 phase 1 acceptance for the resolution rules. The theme of every case: the family always hears
 * something. A photo that vanishes in silence is the worst outcome, worse than a refusal that says why.
 */
class WaIntakeServiceTest {

    private static final String FROM = "8801700000000";
    private static final String E164 = "+8801700000000";
    private static final UUID FAMILY = UUID.randomUUID();

    private final HealthRecordPort records = mock(HealthRecordPort.class);
    private final com.elioo.baymax.extraction.application.port.out.DocumentRecordPort documents =
            mock(com.elioo.baymax.extraction.application.port.out.DocumentRecordPort.class);
    private final com.elioo.baymax.outbound.application.port.in.ExplainDocumentUseCase explainer =
            mock(com.elioo.baymax.outbound.application.port.in.ExplainDocumentUseCase.class);
    private final DocumentIntakeUseCase intake = mock(DocumentIntakeUseCase.class);
    private final WaMessagingPort graph = mock(WaMessagingPort.class);
    private final BaymaxProperties props = new BaymaxProperties();
    /**
     * What actually went out, recorded when the send is SUBSCRIBED, not when sendText is called. WaGraphClient.sendText
     * is a lazy WebClient Mono that sends nothing until subscribed; a mock that recorded the call counted a reply built
     * and never sent as a reply, so silence passed (CLAUDE.md: a mock that cannot fail the way production fails).
     */
    private final List<Map.Entry<String, String>> delivered = new java.util.concurrent.CopyOnWriteArrayList<>();
    private WaIntakeService service;

    @BeforeEach
    void wire() {
        props.getWa().setEnabled(true);
        props.getWa().setAllowlist(List.of(E164));
        when(graph.sendText(anyString(), anyString())).thenAnswer(inv -> Mono.fromCallable(() -> {
            delivered.add(Map.entry(inv.<String>getArgument(0), inv.<String>getArgument(1)));
            return com.elioo.baymax.wa.WaFixtures.wamid(FROM, 9);
        }));
        when(graph.downloadMedia(anyString())).thenReturn(Mono.just(new byte[]{1, 2, 3}));
        service = new WaIntakeService(records, documents, explainer, intake, graph, new Copy(), props);
    }

    private PatientProfile patient(String name) {
        return new PatientProfile(UUID.randomUUID(), FAMILY, name, 70, PatientProfile.Sex.FEMALE,
                List.of(), Instant.EPOCH, Instant.EPOCH);
    }

    private void familyWith(PatientProfile... patients) {
        when(records.findFamilyByWhatsapp(E164)).thenReturn(Mono.just(
                new FamilyAccount(FAMILY, E164, "Rahim", FamilyAccount.Plan.FREE, Instant.EPOCH, Instant.EPOCH)));
        when(records.patientsOf(FAMILY)).thenReturn(Flux.just(patients));
    }

    @Test
    void aPhotoFromAKnownFamilyWithOnePatientBecomesADocument() {
        PatientProfile ma = patient("মা");
        familyWith(ma);
        when(intake.accept(eq(ma.id()), any())).thenReturn(Mono.just(mock(Document.class)));

        assertThat(service.onImage(FROM, "MEDIA-1").block()).isEqualTo(WaIntakeService.Outcome.ACCEPTED);

        ArgumentCaptor<List<Upload>> uploads = ArgumentCaptor.forClass(List.class);
        verify(intake).accept(eq(ma.id()), uploads.capture());
        assertThat(uploads.getValue()).singleElement()
                .satisfies(u -> assertThat(u.bytes()).containsExactly(1, 2, 3));
        // and the family was told the photo arrived, before the slow part
        assertThat(sentBodies()).containsExactly(bn("wa.received"));
    }

    /**
     * The §1.4 case. Phase 1 cannot ask "Ma or Baba?", so it refuses and says so — picking the first patient
     * would misfile one parent's report under the other and every artefact built on it would be wrong and
     * internally consistent.
     */
    @Test
    void aFamilyWithTwoPatientsIsRefusedAndToldWhyAndNothingIsFiled() {
        familyWith(patient("মা"), patient("বাবা"));

        assertThat(service.onImage(FROM, "MEDIA-2").block())
                .isEqualTo(WaIntakeService.Outcome.AMBIGUOUS_PATIENT);

        verify(intake, never()).accept(any(), any());
        verify(graph, never()).downloadMedia(anyString());
        assertThat(sentBodies()).containsExactly(new Copy().bn("wa.ambiguous",
                Map.of("link", props.getOutbound().getPublicBaseUrl() + "/app")));
    }

    @Test
    void anUnknownSenderGetsTheStubReplyAndNoDocumentRow() {
        when(records.findFamilyByWhatsapp(E164)).thenReturn(Mono.empty());

        assertThat(service.onImage(FROM, "MEDIA-3").block())
                .isEqualTo(WaIntakeService.Outcome.UNKNOWN_SENDER);

        verify(intake, never()).accept(any(), any());
        assertThat(sentBodies()).containsExactly(bn("wa.unknown"));
    }

    @Test
    void aFamilyWithNoPatientYetIsTreatedAsUnknownRatherThanCrashing() {
        familyWith();

        assertThat(service.onImage(FROM, "MEDIA-4").block())
                .isEqualTo(WaIntakeService.Outcome.UNKNOWN_SENDER);
        verify(intake, never()).accept(any(), any());
    }

    @Test
    void anExhaustedFreeTierIsExplainedRatherThanFailingSilently() {
        PatientProfile ma = patient("মা");
        familyWith(ma);
        when(intake.accept(any(), any())).thenReturn(Mono.error(
                new FreeTierExceededException(FreeTierExceededException.DOCUMENTS, "free tier")));

        assertThat(service.onImage(FROM, "MEDIA-5").block()).isEqualTo(WaIntakeService.Outcome.FREE_TIER);
        assertThat(sentBodies()).endsWith(bn("wa.free_tier"));
    }

    /** The allowlist is enforced here, not left to the Meta test number's own restriction. */
    @Test
    void aRecipientNotOnTheAllowlistIsRefusedAndNothingIsSent() {
        props.getWa().setAllowlist(List.of("+8800000000000"));
        when(records.findFamilyByWhatsapp(E164)).thenReturn(Mono.empty());

        assertThat(service.onImage(FROM, "MEDIA-6").block())
                .isEqualTo(WaIntakeService.Outcome.UNKNOWN_SENDER);
        verify(graph, never()).sendText(anyString(), anyString());
    }

    /** A failed reply must not lose a document that was already accepted. */
    @Test
    void aFailedReplyDoesNotUndoAnAcceptedDocument() {
        PatientProfile ma = patient("মা");
        familyWith(ma);
        when(graph.sendText(anyString(), anyString())).thenReturn(Mono.error(new RuntimeException("window expired")));
        when(intake.accept(eq(ma.id()), any())).thenReturn(Mono.just(mock(Document.class)));

        assertThat(service.onImage(FROM, "MEDIA-7").block()).isEqualTo(WaIntakeService.Outcome.ACCEPTED);
        verify(intake).accept(eq(ma.id()), any());
    }

    /**
     * Guard for a real bug: switchIfEmpty evaluates its argument at assembly time, so an eager
     * reply(...) there sent the unknown-sender message on EVERY inbound image — a known family would have
     * received "this number is not linked to a Medioo account" alongside their real acknowledgement.
     */
    @Test
    void aKnownFamilyNeverReceivesTheUnknownSenderReply() {
        PatientProfile ma = patient("মা");
        familyWith(ma);
        when(intake.accept(eq(ma.id()), any())).thenReturn(Mono.just(mock(Document.class)));

        service.onImage(FROM, "MEDIA-8").block();

        assertThat(sentBodies())
                .as("the unknown-sender text must never go to a family that exists")
                .doesNotContain(bn("wa.unknown"));
        assertThat(sentBodies()).containsExactly(bn("wa.received"));
    }

    private static final UUID DOC = UUID.randomUUID();

    private void latestDone() {
        Document doc = mock(Document.class);
        when(doc.id()).thenReturn(DOC);
        when(documents.latestDoneDocument(FAMILY)).thenReturn(Mono.just(doc));
    }

    private static OutboundMessage detailRow(OutboundMessage.GateStatus status, Urgency urgency, String body, Instant sentAt) {
        return new OutboundMessage(UUID.randomUUID(), FAMILY, null, DOC, OutboundMessage.Kind.DETAIL, urgency, List.of(),
                body, status, null, null, null, sentAt, Instant.EPOCH);
    }

    private String link() {
        return props.getOutbound().getPublicBaseUrl() + "/app/documents/" + DOC;
    }

    /**
     * Every explanation ends with বিস্তারিত শুনতে চাইলে লিখুন "বিস্তারিত", so the word must be answered. The body
     * reaches the family through deliver() — the delivery port records the outcome — and never from onText (F4).
     * This replaces theWordTheExplanationInvitesIsAnswered, which asserted that onText sent the body itself: it mocked
     * the delivering detail() and so could not see the second send. It encoded the defect.
     */
    @Test
    void theInvitedWordIsAnsweredByDeliveringTheStoredDetailNotBySendingItHere() {
        familyWith(patient("মা"));
        latestDone();
        OutboundMessage stored = detailRow(OutboundMessage.GateStatus.RELEASED, Urgency.ROUTINE, "বিস্তারিত: সব স্বাভাবিক", null);
        when(explainer.detail(DOC)).thenReturn(Mono.just(stored));
        when(explainer.deliver(stored)).thenReturn(Mono.just(detailRow(OutboundMessage.GateStatus.RELEASED, Urgency.ROUTINE,
                stored.body(), Instant.EPOCH)));

        assertThat(service.onText(FROM, "বিস্তারিত").block()).isEqualTo(WaIntakeService.Outcome.ACCEPTED);
        verify(explainer).deliver(stored);
        verify(graph, never()).sendText(anyString(), anyString());
    }

    @Test
    void aDetailAlreadyDeliveredIsNotSentAgain() {
        familyWith(patient("মা"));
        latestDone();
        when(explainer.detail(DOC)).thenReturn(Mono.just(
                detailRow(OutboundMessage.GateStatus.RELEASED, Urgency.ROUTINE, "বিস্তারিত: সব স্বাভাবিক", Instant.EPOCH)));

        service.onText(FROM, "বিস্তারিত").block();

        verify(explainer, never()).deliver(any());
        assertThat(sentBodies()).containsExactly(new Copy().bn("wa.detail.already_sent", Map.of("link", link())));
    }

    /** A concurrent ask holds the claim on this very row: the second ask gets the pointer, never a second body. */
    @Test
    void aDetailWhoseDeliveryClaimIsHeldGetsThePointerNotTheBody() {
        familyWith(patient("মা"));
        latestDone();
        OutboundMessage stored = detailRow(OutboundMessage.GateStatus.RELEASED, Urgency.ROUTINE, "বিস্তারিত: সব স্বাভাবিক", null);
        when(explainer.detail(DOC)).thenReturn(Mono.just(stored));
        when(explainer.deliver(stored)).thenReturn(Mono.empty());

        service.onText(FROM, "বিস্তারিত").block();

        assertThat(sentBodies()).containsExactly(new Copy().bn("wa.detail.already_sent", Map.of("link", link())));
        verify(graph, never()).sendText(E164, stored.body());
    }

    /** D5 on WhatsApp: a pending NOW gets its urgency line verbatim at once, the red-flag line, and never the body. */
    @Test
    void aPendingNowDetailGetsTheUrgencyAndTheRedFlagLineNeverTheBody() {
        familyWith(patient("মা"));
        latestDone();
        when(explainer.detail(DOC)).thenReturn(Mono.just(
                detailRow(OutboundMessage.GateStatus.PENDING, Urgency.NOW, "অপরীক্ষিত লেখা", null)));
        Copy copy = new Copy();

        service.onText(FROM, "বিস্তারিত").block();

        verify(explainer, never()).deliver(any());
        assertThat(sentBodies()).containsExactly(String.join("\n", copy.bn("urgency.now", Map.of()),
                copy.bn("safety.red_flag_line", Map.of()), copy.bn("wa.detail.pending", Map.of())));
        assertThat(sentBodies().get(0)).isNotEqualTo("অপরীক্ষিত লেখা");
        assertThat(sentBodies().get(0).length()).isLessThanOrEqualTo(props.getOutbound().getMaxChars());
    }

    @Test
    void aPendingRoutineDetailGetsThePendingLineAlone() {
        familyWith(patient("মা"));
        latestDone();
        when(explainer.detail(DOC)).thenReturn(Mono.just(detailRow(OutboundMessage.GateStatus.PENDING, Urgency.ROUTINE, "x", null)));
        service.onText(FROM, "বিস্তারিত").block();
        assertThat(sentBodies()).containsExactly(new Copy().bn("wa.detail.pending", Map.of()));
    }

    /** F3: REJECTED and FAILED_SAFETY get the urgency (if any) and the paper link — never "দেখা হচ্ছে", never silence. */
    @ParameterizedTest
    @EnumSource(value = OutboundMessage.GateStatus.class, names = {"REJECTED", "FAILED_SAFETY"})
    void aWithheldDetailIsAnsweredWithTheUrgencyAndTheLink(OutboundMessage.GateStatus status) {
        familyWith(patient("মা"));
        latestDone();
        when(explainer.detail(DOC)).thenReturn(Mono.just(detailRow(status, Urgency.THIS_WEEK, null, null)));
        Copy copy = new Copy();

        service.onText(FROM, "বিস্তারিত").block();

        String expected = String.join("\n", copy.bn("urgency.this_week", Map.of()), copy.bn("safety.red_flag_line", Map.of()),
                copy.bn("wa.detail.unavailable", Map.of("link", link())));
        assertThat(sentBodies()).containsExactly(expected);
        assertThat(expected).doesNotContain("দেখা হচ্ছে");
    }

    /** CLAUDE.md: the cap is asserted on what was SENT — every gate state × urgency × delivered-or-not, through onText. */
    @Test
    void everyFixedDetailReplyActuallySentFitsTheCap() {
        familyWith(patient("মা"));
        latestDone();
        List<String> sent = new java.util.ArrayList<>();
        for (OutboundMessage.GateStatus status : OutboundMessage.GateStatus.values()) {
            for (Urgency urgency : Urgency.values()) {
                for (Instant sentAt : java.util.Arrays.asList(null, Instant.EPOCH)) {
                    delivered.clear();
                    OutboundMessage row = detailRow(status, urgency,
                            status == OutboundMessage.GateStatus.FAILED_SAFETY ? null : "লেখা", sentAt);
                    when(explainer.detail(DOC)).thenReturn(Mono.just(row));
                    when(explainer.deliver(any())).thenReturn(Mono.just(row));
                    service.onText(FROM, "বিস্তারিত").block();
                    for (String b : sentBodies()) {
                        assertThat(b.length()).as(status + "/" + urgency + "/" + sentAt + ": " + b)
                                .isLessThanOrEqualTo(props.getOutbound().getMaxChars());
                        sent.add(b);
                    }
                }
            }
        }
        assertThat(sent).as("vacuous if no fixed reply was sent").hasSizeGreaterThanOrEqualTo(9);
    }

    private final Copy copy = new Copy();

    private String bn(String key) {
        return copy.bn(key, Map.of());
    }

    /** The bodies whose send was subscribed, to this family's number, in order. */
    private List<String> sentBodies() {
        return delivered.stream().filter(d -> d.getKey().equals(E164)).map(Map.Entry::getValue).toList();
    }

    /** CLAUDE.md: a limit is asserted on the thing actually sent, after every insertion. Every reply in every test is checked. */
    @AfterEach
    void everyReplyActuallySentFitsTheCap() {
        for (var d : delivered) {
            assertThat(d.getValue().length()).as("sent: " + d.getValue()).isLessThanOrEqualTo(props.getOutbound().getMaxChars());
        }
    }

    /**
     * Inverted from anOrdinaryTextIsAcknowledgedWithoutAReply, which asserted the silence Phase 0 removes. A test
     * whose name encodes the defect is evidence of it (CLAUDE.md). "ধন্যবাদ" is a thanks and is answered once.
     */
    @Test
    void aThankYouIsAnsweredOnce() {
        familyWith(patient("মা"));
        when(documents.latestDoneDocument(FAMILY)).thenReturn(Mono.empty());
        service.onText(FROM, "ধন্যবাদ").block();
        assertThat(sentBodies()).as("a thank-you gets exactly one reply (0 = silence, the defect; 2 or more = a double send)").hasSize(1);
        assertThat(sentBodies().get(0)).startsWith(bn("wa.thanks")).endsWith(bn("safety.red_flag_line"));
    }

    @Test
    void hiIsAnsweredWithAGreetingThatSaysWhatToDoNext() {
        familyWith(patient("মা"));
        when(documents.latestDoneDocument(FAMILY)).thenReturn(Mono.empty());
        service.onText(FROM, "Hi").block();
        assertThat(sentBodies()).as("\"Hi\" gets exactly one reply (0 = silence, the defect; 2 or more = a double send) (spec §0)").hasSize(1);
        assertThat(sentBodies().get(0)).startsWith(bn("wa.greeting.hello"))
                .contains(bn("wa.greeting.intro")).contains(bn("wa.greeting.none"));
    }

    /** Mirror the greeting; never assume religion (spec §7.4). */
    @Test
    void salamIsAnsweredWithSalamAndKemonAchoIsNot() {
        familyWith(patient("মা"));
        when(documents.latestDoneDocument(FAMILY)).thenReturn(Mono.empty());
        service.onText(FROM, "আসসালামু আলাইকুম").block();
        service.onText(FROM, "kemon acho").block();
        assertThat(sentBodies()).as("two greetings get one reply each (fewer = silence, the defect; more = a double send)").hasSize(2);
        assertThat(sentBodies().get(0)).startsWith(bn("wa.greeting.salam"));
        assertThat(sentBodies().get(1)).startsWith(bn("wa.greeting.hello")).doesNotContain("আসসালাম");
    }

    /** আদাব is mirrored with আদাব, never with সালাম and never with the fallback (spec §7.4). */
    @Test
    void adabIsAnsweredWithAdab() {
        familyWith(patient("মা"));
        service.onText(FROM, "আদাব").block();
        assertThat(sentBodies()).as("\"আদাব\" gets " + "exactly one reply (0 = silence, the defect; 2 or more = a double send)")
                .hasSize(1);
        assertThat(sentBodies().get(0)).as("mirrored, not the fallback").startsWith("আদাব।")
                .contains(bn("wa.greeting.intro")).doesNotContain("আসসালাম").doesNotContain(bn("safety.red_flag_line"));
    }

    @Test
    void anythingElseGetsTheHonestFallbackWithTheRedFlagLine() {
        familyWith(patient("মা"));
        service.onText(FROM, "আম্মা কাল থেকে খুব দুর্বল").block();
        assertThat(sentBodies()).as("free text gets exactly one reply (0 = silence, the defect; 2 or more = a double send)").hasSize(1);
        assertThat(sentBodies()).containsExactly(bn("wa.fallback") + "\n" + bn("safety.red_flag_line"));
    }

    /** Whole-message rule, dangerous direction: the detail word inside a symptom message must not trigger detail. */
    @Test
    void aRuleWordInsideALongerMessageGetsTheFallbackNotTheRule() {
        familyWith(patient("মা"));
        service.onText(FROM, "বিস্তারিত — আম্মার খুব জ্বর").block();
        assertThat(sentBodies()).as("a rule word inside a longer message gets exactly one reply (0 = silence, the defect; 2 or more = a double send)").hasSize(1);
        verify(explainer, never()).detail(any());
        assertThat(sentBodies().get(0)).contains(bn("safety.red_flag_line"));
    }

    /** The synonyms reach the same detail path as the literal word. */
    @Test
    void detailSynonymsAreAnsweredLikeTheInvitedWord() {
        familyWith(patient("মা"));
        when(documents.latestDoneDocument(FAMILY)).thenReturn(Mono.empty());
        for (String s : List.of("details", "আরো বলুন", "bistarito bolen", "explain")) {
            service.onText(FROM, s).block();
        }
        assertThat(sentBodies()).as("four synonyms get one reply each (fewer = silence, the defect; more = a double send)").hasSize(4)
                .allMatch(b -> b.equals(bn("wa.no_document")));
    }

    private static WaInbound docx() {
        return new WaInbound("w", FROM, WaInbound.Type.DOCUMENT, "", "MEDIA-D",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    @Test
    void anUnReactionIsTheOnlyReactionNotAnswered() {
        familyWith(patient("মা"));
        service.onInbound(new WaInbound("w0", FROM, WaInbound.Type.REACTION, "", "", "")).block();
        verify(graph, never()).sendText(anyString(), anyString());
        service.onInbound(new WaInbound("w1", FROM, WaInbound.Type.REACTION, "👍", "", "")).block();
        assertThat(sentBodies()).as("a 👍 on a Medioo message gets exactly one reply (0 = silence, the defect; 2 or more = a double send)")
                .containsExactly(bn("wa.thanks") + "\n" + bn("safety.red_flag_line"));
    }

    /** Two directions: 👍 is thanks, 😢 is not, and must not be answered "ঠিক আছে" — it gets the fallback. */
    @Test
    void anUnhappyReactionGetsTheFallbackNotTheThanks() {
        familyWith(patient("মা"));
        service.onInbound(new WaInbound("w2", FROM, WaInbound.Type.REACTION, "😢", "", "")).block();
        assertThat(sentBodies()).as("a 😢 reaction gets exactly one reply (0 = silence, the defect; 2 or more = a double send)")
                .containsExactly(bn("wa.fallback") + "\n" + bn("safety.red_flag_line"));
    }

    @Test
    void aStickerIsAnsweredAsAThanks() {
        familyWith(patient("মা"));
        service.onInbound(new WaInbound("w", FROM, WaInbound.Type.STICKER, "", "MEDIA-S", "image/webp")).block();
        assertThat(sentBodies()).as("a sticker gets exactly one reply (0 = silence, the defect; 2 or more = a double send)")
                .containsExactly(bn("wa.thanks") + "\n" + bn("safety.red_flag_line"));
        verify(graph, never()).downloadMedia(anyString());
    }

    /** D13: fixed reply plus the red-flag line; nothing here can listen, so nothing is downloaded. */
    @Test
    void aVoiceNoteGetsTheFixedReplyAndTheRedFlagLine() {
        familyWith(patient("মা"));
        service.onInbound(new WaInbound("w", FROM, WaInbound.Type.AUDIO, "", "MEDIA-V", "audio/ogg; codecs=opus")).block();
        assertThat(sentBodies()).containsExactly(bn("wa.voice") + "\n" + bn("safety.red_flag_line"));
        verify(graph, never()).downloadMedia(anyString());
    }

    @Test
    void aVideoGetsTheFixedReplyAndTheRedFlagLine() {
        familyWith(patient("মা"));
        service.onInbound(new WaInbound("w", FROM, WaInbound.Type.VIDEO, "", "MEDIA-X", "video/mp4")).block();
        assertThat(sentBodies()).containsExactly(bn("wa.video") + "\n" + bn("safety.red_flag_line"));
        verify(graph, never()).downloadMedia(anyString());
    }

    @Test
    void aLocationGetsTheFallbackWithTheRedFlagLine() {
        familyWith(patient("মা"));
        service.onInbound(new WaInbound("w", FROM, WaInbound.Type.LOCATION, "", "", "")).block();
        assertThat(sentBodies()).containsExactly(bn("wa.fallback") + "\n" + bn("safety.red_flag_line"));
    }

    /** A button tap carries its title as words; "বিস্তারিত" reaches the detail path like the typed word. */
    @Test
    void aButtonTapIsAnsweredLikeTheWordsOnIt() {
        familyWith(patient("মা"));
        when(documents.latestDoneDocument(FAMILY)).thenReturn(Mono.empty());
        service.onInbound(new WaInbound("w", FROM, WaInbound.Type.INTERACTIVE, "বিস্তারিত", "", "")).block();
        assertThat(sentBodies()).containsExactly(bn("wa.no_document"));
    }

    /** Meta's own notice (a changed number) carries nothing the family sent: no reply, and nothing is read. */
    @Test
    void aSystemNoticeIsNotAnswered() {
        assertThat(service.onInbound(new WaInbound("w", FROM, WaInbound.Type.SYSTEM, "", "", "")).block())
                .isEqualTo(WaIntakeService.Outcome.IGNORED);
        verify(graph, never()).sendText(anyString(), anyString());
        verify(records, never()).findFamilyByWhatsapp(anyString());
    }

    @Test
    void aWordFileIsRefusedWithAReasonBeforeAnythingIsDownloadedOrAcknowledged() {
        familyWith(patient("মা"));
        service.onInbound(docx()).block();
        assertThat(sentBodies()).containsExactly(bn("wa.unsupported_file"));
        verify(graph, never()).downloadMedia(anyString());
        verify(intake, never()).accept(any(), any());
    }

    @Test
    void aPdfIsAcknowledgedAsAFileAndFiled() {
        PatientProfile ma = patient("মা");
        familyWith(ma);
        when(intake.accept(eq(ma.id()), any())).thenReturn(Mono.just(mock(Document.class)));
        service.onInbound(new WaInbound("w", FROM, WaInbound.Type.DOCUMENT, "", "MEDIA-P", "application/pdf")).block();
        assertThat(sentBodies()).containsExactly(bn("wa.received.file"));
        verify(intake).accept(eq(ma.id()), any());
    }

    /** The photo path is unchanged: "ছবিটা পেয়েছি", then the document is filed. */
    @Test
    void aPhotoThroughTheEntryPointIsAcknowledgedAsAPhotoAndFiled() {
        PatientProfile ma = patient("মা");
        familyWith(ma);
        when(intake.accept(eq(ma.id()), any())).thenReturn(Mono.just(mock(Document.class)));
        service.onInbound(new WaInbound("w", FROM, WaInbound.Type.IMAGE, "", "MEDIA-I", "image/jpeg")).block();
        assertThat(sentBodies()).containsExactly(bn("wa.received"));
        verify(intake).accept(eq(ma.id()), any());
    }

    @Test
    void aVoiceNoteFromAnUnknownNumberGetsTheUnknownSenderReply() {
        when(records.findFamilyByWhatsapp(E164)).thenReturn(Mono.empty());
        service.onInbound(new WaInbound("w", FROM, WaInbound.Type.AUDIO, "", "M", "audio/ogg")).block();
        assertThat(sentBodies()).containsExactly(bn("wa.unknown"));
    }

    @Test
    void aWordFileFromAnUnknownNumberGetsTheUnknownSenderReply() {
        when(records.findFamilyByWhatsapp(E164)).thenReturn(Mono.empty());
        service.onInbound(docx()).block();
        assertThat(sentBodies()).containsExactly(bn("wa.unknown"));
    }

    /** Blank or missing mime: PageRenderer's magic bytes decide. Parameters after ";" do not defeat the match. */
    @Test
    void readableFilesAreJudgedByTheirMimeTypeOnly() {
        assertThat(WaIntakeService.readable("application/pdf")).isTrue();
        assertThat(WaIntakeService.readable("Image/JPEG; charset=binary")).isTrue();
        assertThat(WaIntakeService.readable("")).isTrue();
        assertThat(WaIntakeService.readable(null)).isTrue();
        assertThat(WaIntakeService.readable("application/vnd.openxmlformats-officedocument.wordprocessingml.document")).isFalse();
        assertThat(WaIntakeService.readable("application/msword")).isFalse();
        assertThat(WaIntakeService.readable("text/plain")).isFalse();
    }

    /** Logs carry route names and sizes, never the text or the number (CLAUDE.md; WaWebhookHandler.maskedNumber). */
    @Test
    void theIntakeLogNeverCarriesWhatTheFamilyWroteOrTheirNumber() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(WaIntakeService.class);
        var logged = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logged.start();
        logger.addAppender(logged);
        try {
            familyWith(patient("মা"));
            service.onText(FROM, "আম্মার বুকে ব্যথা").block();
            String wamid = com.elioo.baymax.wa.WaFixtures.wamid(FROM, 3);
            service.onInbound(new WaInbound(wamid, FROM, WaInbound.Type.AUDIO, "", "MEDIA-V", "audio/ogg")).block();
            String all = String.join("\n", logged.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList());
            String base64 = wamid.substring("wamid.".length());
            assertThat(all).contains("route=FALLBACK").doesNotContain("বুকে").doesNotContain("1700000000")
                    .doesNotContain("wamid.");
            // the prefix alone is not the wamid: a line printing the base64 body, or the digits decoded from it, leaks too
            assertThat(all).as("a wamid is base64 of the number").doesNotContain(wamid).doesNotContain(base64.substring(0, 16))
                    .doesNotContain(com.elioo.baymax.wa.WaFixtures.decodedDigits(wamid));
        } finally {
            logger.detachAppender(logged);
        }
    }

    @Test
    void askingForDetailBeforeAnyReadableDocumentSaysSoRatherThanNothing() {
        familyWith(patient("মা"));
        when(documents.latestDoneDocument(FAMILY)).thenReturn(Mono.empty());
        assertThat(service.onText(FROM, "বিস্তারিত").block()).isEqualTo(WaIntakeService.Outcome.REPLIED);
        assertThat(sentBodies()).containsExactly(bn("wa.no_document"));
    }

    @Test
    void metaNumbersAreNormalisedToE164() {
        assertThat(WaIntakeService.e164("8801700000000")).isEqualTo("+8801700000000");
        assertThat(WaIntakeService.e164("+8801700000000")).isEqualTo("+8801700000000");
        assertThat(WaIntakeService.e164("")).isEmpty();
        assertThat(WaIntakeService.e164(null)).isEmpty();
    }

    /** onImage logged e.toString(), which for a WebClientResponseException is "404 … from GET <signed URL>". */
    @Test
    void theIntakeErrorLogCarriesNoExceptionText() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(WaIntakeService.class);
        var logged = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logged.start();
        logger.addAppender(logged);
        try {
            familyWith(patient("মা"));
            // what WaGraphClient.download actually throws: its message is "404 Not Found from GET <signed URL>"
            when(graph.downloadMedia("MEDIA-13")).thenReturn(Mono.error(
                    org.springframework.web.reactive.function.client.WebClientResponseException.create(404, "Not Found",
                            org.springframework.http.HttpHeaders.EMPTY, new byte[0], java.nio.charset.StandardCharsets.UTF_8,
                            new org.springframework.mock.http.client.MockClientHttpRequest(org.springframework.http.HttpMethod.GET,
                                    java.net.URI.create("https://lookaside.fbsbx.com/whatsapp_business/attachments/?mid=1&hash=SECRET")))));
            service.onImage(FROM, "MEDIA-13").block();
            String all = String.join("\n", logged.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList());
            assertThat(all).as("the line exists").contains("wa intake failed");
            assertThat(all).as("the media URL and its token must never reach the log").doesNotContain("SECRET").doesNotContain("lookaside");
        } finally {
            logger.detachAppender(logged);
        }
    }

    /** The reply-failed line printed e.toString(); a provider error can name the recipient. Class name only. */
    @Test
    void aFailedReplyIsLoggedWithoutTheExceptionText() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(WaIntakeService.class);
        var logged = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logged.start();
        logger.addAppender(logged);
        try {
            PatientProfile ma = patient("মা");
            familyWith(ma);
            when(graph.sendText(anyString(), anyString())).thenReturn(Mono.error(new RuntimeException(
                    "(#131026) Message undeliverable to " + E164)));
            when(intake.accept(eq(ma.id()), any())).thenReturn(Mono.just(mock(Document.class)));

            service.onImage(FROM, "MEDIA-7").block();

            String all = String.join("\n", logged.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList());
            assertThat(all).as("the line exists").contains("wa outbound failed");
            assertThat(all).as("provider prose and the number never reach the log").doesNotContain("undeliverable")
                    .doesNotContain(FROM);
        } finally {
            logger.detachAppender(logged);
        }
    }
}
