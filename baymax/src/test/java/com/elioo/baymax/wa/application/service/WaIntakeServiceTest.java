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
    private WaIntakeService service;

    @BeforeEach
    void wire() {
        props.getWa().setEnabled(true);
        props.getWa().setAllowlist(List.of(E164));
        when(graph.sendText(anyString(), anyString())).thenReturn(Mono.just(com.elioo.baymax.wa.WaFixtures.wamid(FROM, 9)));
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
        verify(graph).sendText(eq(E164), anyString());
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
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(graph).sendText(eq(E164), sent.capture());
        assertThat(sent.getValue()).isNotBlank();
    }

    @Test
    void anUnknownSenderGetsTheStubReplyAndNoDocumentRow() {
        when(records.findFamilyByWhatsapp(E164)).thenReturn(Mono.empty());

        assertThat(service.onImage(FROM, "MEDIA-3").block())
                .isEqualTo(WaIntakeService.Outcome.UNKNOWN_SENDER);

        verify(intake, never()).accept(any(), any());
        verify(graph).sendText(eq(E164), anyString());
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
        verify(graph, org.mockito.Mockito.atLeastOnce()).sendText(eq(E164), anyString());
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

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(graph, org.mockito.Mockito.atLeastOnce()).sendText(eq(E164), sent.capture());
        Copy copy = new Copy();
        assertThat(sent.getAllValues())
                .as("the unknown-sender text must never go to a family that exists")
                .doesNotContain(copy.bn("wa.unknown", java.util.Map.of()));
        assertThat(sent.getAllValues()).containsExactly(copy.bn("wa.received", java.util.Map.of()));
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
        verify(graph).sendText(E164, new Copy().bn("wa.detail.already_sent", Map.of("link", link())));
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

        verify(graph).sendText(E164, new Copy().bn("wa.detail.already_sent", Map.of("link", link())));
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
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(graph).sendText(eq(E164), sent.capture());
        assertThat(sent.getValue()).isEqualTo(String.join("\n", copy.bn("urgency.now", Map.of()),
                copy.bn("safety.red_flag_line", Map.of()), copy.bn("wa.detail.pending", Map.of())));
        assertThat(sent.getValue()).isNotEqualTo("অপরীক্ষিত লেখা");
        assertThat(sent.getValue().length()).isLessThanOrEqualTo(props.getOutbound().getMaxChars());
    }

    @Test
    void aPendingRoutineDetailGetsThePendingLineAlone() {
        familyWith(patient("মা"));
        latestDone();
        when(explainer.detail(DOC)).thenReturn(Mono.just(detailRow(OutboundMessage.GateStatus.PENDING, Urgency.ROUTINE, "x", null)));
        service.onText(FROM, "বিস্তারিত").block();
        verify(graph).sendText(E164, new Copy().bn("wa.detail.pending", Map.of()));
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
        verify(graph).sendText(E164, expected);
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
                    org.mockito.Mockito.clearInvocations(graph);
                    OutboundMessage row = detailRow(status, urgency,
                            status == OutboundMessage.GateStatus.FAILED_SAFETY ? null : "লেখা", sentAt);
                    when(explainer.detail(DOC)).thenReturn(Mono.just(row));
                    when(explainer.deliver(any())).thenReturn(Mono.just(row));
                    service.onText(FROM, "বিস্তারিত").block();
                    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
                    verify(graph, org.mockito.Mockito.atLeast(0)).sendText(eq(E164), body.capture());
                    for (String b : body.getAllValues()) {
                        assertThat(b.length()).as(status + "/" + urgency + "/" + sentAt + ": " + b)
                                .isLessThanOrEqualTo(props.getOutbound().getMaxChars());
                        sent.add(b);
                    }
                }
            }
        }
        assertThat(sent).as("vacuous if no fixed reply was sent").hasSizeGreaterThanOrEqualTo(9);
    }

    @Test
    void anOrdinaryTextIsAcknowledgedWithoutAReply() {
        familyWith(patient("মা"));
        assertThat(service.onText(FROM, "ধন্যবাদ").block()).isEqualTo(WaIntakeService.Outcome.ACCEPTED);
        verify(graph, never()).sendText(anyString(), anyString());
    }

    @Test
    void askingForDetailBeforeAnyReadableDocumentSaysSoRatherThanNothing() {
        familyWith(patient("মা"));
        when(documents.latestDoneDocument(FAMILY)).thenReturn(Mono.empty());
        assertThat(service.onText(FROM, "বিস্তারিত").block()).isEqualTo(WaIntakeService.Outcome.ACCEPTED);
        verify(graph).sendText(eq(E164), anyString());
    }

    @Test
    void theInvitedWordIsRecognisedWithPunctuationOrInEnglish() {
        assertThat(WaIntakeService.isDetailRequest("বিস্তারিত")).isTrue();
        assertThat(WaIntakeService.isDetailRequest("  বিস্তারিত।  ")).isTrue();
        assertThat(WaIntakeService.isDetailRequest("detail")).isTrue();
        assertThat(WaIntakeService.isDetailRequest("ধন্যবাদ")).isFalse();
        assertThat(WaIntakeService.isDetailRequest(null)).isFalse();
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
