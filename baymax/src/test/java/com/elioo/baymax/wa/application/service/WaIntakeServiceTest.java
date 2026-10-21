package com.elioo.baymax.wa.application.service;

import com.elioo.baymax.common.error.FreeTierExceededException;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.application.port.in.DocumentIntakeUseCase;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.Upload;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.outbound.application.service.BanglaDate;
import com.elioo.baymax.outbound.application.service.Copy;
import com.elioo.baymax.outbound.domain.OutboundMessage;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.wa.WaFixtures;
import com.elioo.baymax.wa.application.port.out.WaMessagingPort;
import com.elioo.baymax.wa.domain.WaInbound;
import com.elioo.baymax.web.domain.AttentionItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
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
    private final com.elioo.baymax.web.application.port.in.WebAuthUseCase auth =
            mock(com.elioo.baymax.web.application.port.in.WebAuthUseCase.class);
    private final java.time.Clock clock = java.time.Clock.fixed(Instant.parse("2026-10-17T06:00:00Z"), java.time.ZoneOffset.UTC);
    private final com.elioo.baymax.family.application.port.in.FreeTierUseCase freeTier =
            mock(com.elioo.baymax.family.application.port.in.FreeTierUseCase.class);
    private final com.elioo.baymax.web.application.port.in.AttentionUseCase attention =
            mock(com.elioo.baymax.web.application.port.in.AttentionUseCase.class);
    private final com.elioo.baymax.outbound.application.port.in.DocumentUrgencyUseCase urgency =
            mock(com.elioo.baymax.outbound.application.port.in.DocumentUrgencyUseCase.class);
    /**
     * What actually went out, recorded when the send is SUBSCRIBED, not when sendText is called. WaGraphClient.sendText
     * is a lazy WebClient Mono that sends nothing until subscribed; a mock that recorded the call counted a reply built
     * and never sent as a reply, so silence passed (CLAUDE.md: a mock that cannot fail the way production fails).
     */
    private final List<Map.Entry<String, String>> delivered = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final com.elioo.baymax.wa.application.port.out.InboundMessagePort inbound =
            mock(com.elioo.baymax.wa.application.port.out.InboundMessagePort.class);
    private final com.elioo.baymax.web.application.service.PhoneHasher hasher =
            new com.elioo.baymax.web.application.service.PhoneHasher(props);
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
        when(auth.noteInbound(anyString(), any())).thenReturn(Mono.empty());
        when(graph.markRead(anyString(), anyBoolean())).thenReturn(Mono.empty());
        // a sender is unknown unless a test says otherwise (familyWith), so no test dies on an unstubbed lookup
        when(records.findFamilyByWhatsapp(anyString())).thenReturn(Mono.empty());
        // no paper read yet, unless a test says otherwise; the cap is not reached
        when(documents.latestDoneDocument(any())).thenReturn(Mono.empty());
        when(freeTier.checkCanUploadDocument(any(FamilyAccount.class))).thenReturn(Mono.empty());
        // nothing stored NOW / THIS_WEEK and no stored level, unless a test says otherwise (stored)
        when(attention.urgent(any(), any())).thenReturn(Mono.just(List.of()));
        when(urgency.current(any())).thenReturn(Mono.empty());
        when(urgency.currentOrAssess(any())).thenReturn(Mono.empty());
        // F9a: the inbound key is HMAC(wamid); the record accepts writes unless a test says otherwise
        props.getAuth().setHmacSecret("s".repeat(32));
        when(inbound.markHandled(anyString(), anyString())).thenReturn(Mono.empty());
        when(inbound.linkDocument(anyString(), any(), any())).thenReturn(Mono.empty());
        service = new WaIntakeService(records, documents, explainer, intake, graph, new Copy(), props, auth,
                clock, freeTier, attention, urgency, inbound, hasher, new WaInstance(clock));
    }

    // ---- F9a: claim before the webhook answers, then handle once and record the outcome ----

    private static final String WAMID = WaFixtures.wamid(FROM, 21);

    @Test
    void aKnownFamilysTextIsClaimedUnderTheHmacWithItsFamilyAndBodyAndNeverTheRawId() {
        familyWith(patient("মা"));
        when(inbound.claim(any(), any())).thenAnswer(i -> Mono.just(i.getArgument(0)));
        WaInbound in = new WaInbound(WAMID, FROM, WaInbound.Type.TEXT, "বিস্তারিত", "", "", Instant.parse("2026-10-17T05:59:00Z"));

        assertThat(service.claim(in).block()).isSameAs(in);

        ArgumentCaptor<com.elioo.baymax.wa.domain.InboundMessage> row = ArgumentCaptor.forClass(com.elioo.baymax.wa.domain.InboundMessage.class);
        verify(inbound).claim(row.capture(), eq(clock.instant()));
        assertThat(row.getValue().providerKey()).isEqualTo(hasher.providerId(WAMID)).doesNotContain(WAMID).matches("[0-9a-f]{64}");
        assertThat(row.getValue().familyId()).isEqualTo(FAMILY);
        assertThat(row.getValue().type()).isEqualTo("text");
        assertThat(row.getValue().body()).isEqualTo("বিস্তারিত");
        assertThat(row.getValue().providerTimestamp()).isEqualTo(Instant.parse("2026-10-17T05:59:00Z"));
        assertThat(delivered).as("the claim answers nobody; handle does").isEmpty();
    }

    @Test
    void anUnknownSendersTextIsClaimedWithoutItsText() {
        when(records.findFamilyByWhatsapp(E164)).thenReturn(Mono.empty());
        when(inbound.claim(any(), any())).thenAnswer(i -> Mono.just(i.getArgument(0)));
        service.claim(new WaInbound(WAMID, FROM, WaInbound.Type.TEXT, "আম্মার রিপোর্ট", "", "")).block();
        ArgumentCaptor<com.elioo.baymax.wa.domain.InboundMessage> row = ArgumentCaptor.forClass(com.elioo.baymax.wa.domain.InboundMessage.class);
        verify(inbound).claim(row.capture(), any());
        assertThat(row.getValue().familyId()).isNull();
        assertThat(row.getValue().body()).isNull();
    }

    @Test
    void aMessageAlreadyClaimedIsEmptyNotAnError() {
        when(inbound.claim(any(), any())).thenReturn(Mono.empty());
        StepVerifier.create(service.claim(new WaInbound(WAMID, FROM, WaInbound.Type.TEXT, "hi", "", ""))).verifyComplete();
    }

    /** No secret, no key: an error signal (the webhook answers 503, Meta retries), never a throw at assembly time. */
    @Test
    void withoutTheHmacSecretTheClaimFailsAsASignalAndNothingIsRecorded() {
        props.getAuth().setHmacSecret("");
        Mono<WaInbound> claim = service.claim(new WaInbound(WAMID, FROM, WaInbound.Type.TEXT, "hi", "", ""));
        StepVerifier.create(claim).expectError(com.elioo.baymax.common.error.BaymaxException.class).verify();
        verify(inbound, never()).claim(any(), any());
    }

    @Test
    void aMessageWithNoProviderIdIsPassedThroughWithoutAClaim() {
        WaInbound in = new WaInbound("", FROM, WaInbound.Type.TEXT, "hi", "", "");
        assertThat(service.claim(in).block()).isSameAs(in);
        verify(inbound, never()).claim(any(), any());
    }

    @Test
    void aHandledPhotoRecordsItsOutcomeAndLinksItsDocument() {
        PatientProfile ma = patient("মা");
        familyWith(ma);
        Document doc = mock(Document.class);
        UUID docId = UUID.randomUUID();
        when(doc.id()).thenReturn(docId);
        when(intake.accept(eq(ma.id()), any())).thenReturn(Mono.just(doc));

        assertThat(service.handle(new WaInbound(WAMID, FROM, WaInbound.Type.IMAGE, "", "MEDIA-1", "image/jpeg")).block())
                .isEqualTo(WaIntakeService.Outcome.ACCEPTED);
        verify(inbound).linkDocument(hasher.providerId(WAMID), ma.id(), docId);
        verify(inbound).markHandled(hasher.providerId(WAMID), "ACCEPTED");
    }

    @Test
    void aFailedIntakeIsStillMarkedHandledWithFailed() {
        familyWith(patient("মা"));
        when(graph.downloadMedia("MEDIA-2")).thenReturn(Mono.error(new WaMessagingPort.MediaUnavailableException(404)));
        assertThat(service.handle(new WaInbound(WAMID, FROM, WaInbound.Type.IMAGE, "", "MEDIA-2", "image/jpeg")).block())
                .isEqualTo(WaIntakeService.Outcome.FAILED);
        verify(inbound).markHandled(hasher.providerId(WAMID), "FAILED");
        verify(inbound, never()).linkDocument(anyString(), any(), any());
    }

    /**
     * handle()'s OWN error branch: onInbound itself errors (the family lookup fails, and withFamily has no fallback), so
     * the FAILED stamp can only come from handle. The download-404 test above reaches FAILED through onImage's mapping
     * instead, and would stay green with handle's onErrorResume deleted: same verdict, different rule.
     */
    @Test
    void anErrorOutOfOnInboundItselfIsTheFailedOutcomeAndIsStamped() {
        when(records.findFamilyByWhatsapp(E164)).thenReturn(Mono.error(new IllegalStateException("lookup failed")));
        WaInbound in = new WaInbound(WAMID, FROM, WaInbound.Type.TEXT, "ধন্যবাদ", "", "");
        StepVerifier.create(service.onInbound(in)).as("the precondition: onInbound itself errors")
                .expectError(IllegalStateException.class).verify();

        // the stamp first, the error swallowed: a replay without handle's onErrorResume fails ON the missing stamp
        WaIntakeService.Outcome outcome = service.handle(in).onErrorResume(e -> Mono.empty()).block();
        verify(inbound).markHandled(hasher.providerId(WAMID), "FAILED");
        assertThat(outcome).isEqualTo(WaIntakeService.Outcome.FAILED);
    }

    /**
     * A row the database refuses as data (SQLSTATE 22xxx / 23xxx) would be refused on every retry for a week: the claim
     * passes the message through unclaimed, so it is answered. Anything else stays an error, the 503 that lets Meta retry.
     */
    @Test
    void aClaimRefusedAsDataIsPassedThroughUnclaimedAndAnythingElseIsStillAnError() {
        when(inbound.claim(any(), any())).thenReturn(Mono.error(new org.springframework.dao.DataIntegrityViolationException("x",
                new io.r2dbc.spi.R2dbcDataIntegrityViolationException("invalid byte sequence", "22021"))));
        WaInbound in = new WaInbound(WAMID, FROM, WaInbound.Type.TEXT, "hi", "", "");
        assertThat(service.claim(in).block()).as("22021: answered unkeyed").isSameAs(in);

        when(inbound.claim(any(), any())).thenReturn(Mono.error(new org.springframework.dao.DataAccessResourceFailureException("x",
                new io.r2dbc.spi.R2dbcNonTransientResourceException("connection refused", "08001"))));
        StepVerifier.create(service.claim(in)).as("08001: a retry can help").expectError().verify();

        when(inbound.claim(any(), any())).thenReturn(Mono.error(new org.springframework.r2dbc.BadSqlGrammarException("x", "sql",
                new io.r2dbc.spi.R2dbcBadGrammarException("relation does not exist", "42P01"))));
        StepVerifier.create(service.claim(in)).as("42P01: the table is missing for every message; Meta holds them").expectError().verify();
    }

    /** The answer already went; a stamp that cannot be written shows as unhandled at the next restart, not as an error. */
    @Test
    void aLostOutcomeStampDoesNotTurnAnAnsweredMessageIntoAnError() {
        familyWith(patient("মা"));
        when(inbound.markHandled(anyString(), anyString())).thenReturn(Mono.error(new IllegalStateException("db down")));
        assertThat(service.handle(new WaInbound(WAMID, FROM, WaInbound.Type.TEXT, "ধন্যবাদ", "", "")).block())
                .isEqualTo(WaIntakeService.Outcome.REPLIED);
        assertThat(delivered).hasSize(1);
    }

    /** A link that cannot be written never costs the family the document or the reply that follows it. */
    @Test
    void aLostDocumentLinkDoesNotFailAnAcceptedPhoto() {
        PatientProfile ma = patient("মা");
        familyWith(ma);
        Document doc = mock(Document.class);
        when(doc.id()).thenReturn(UUID.randomUUID());
        when(intake.accept(eq(ma.id()), any())).thenReturn(Mono.just(doc));
        when(inbound.linkDocument(anyString(), any(), any())).thenReturn(Mono.error(new IllegalStateException("db down")));

        assertThat(service.handle(new WaInbound(WAMID, FROM, WaInbound.Type.IMAGE, "", "MEDIA-3", "image/jpeg")).block())
                .isEqualTo(WaIntakeService.Outcome.ACCEPTED);
        verify(inbound).markHandled(hasher.providerId(WAMID), "ACCEPTED");
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

    /**
     * W15: a family at the cap heard "got it, reading now" and then "free reports used up". The check runs before the ack:
     * one message, the true one, and nothing is downloaded. Replaces anExhaustedFreeTierIsExplainedRatherThanFailingSilently,
     * whose endsWith accepted the ack in front of the refusal.
     */
    @Test
    void aFamilyAtTheFreeCapHearsOneMessageAndItIsTheTrueOneAndNothingIsDownloaded() {
        familyWith(patient("মা"));
        FreeTierExceededException capped = new FreeTierExceededException(FreeTierExceededException.DOCUMENTS, "free tier");
        when(freeTier.checkCanUploadDocument(any(FamilyAccount.class))).thenReturn(Mono.error(capped));
        when(intake.accept(any(), any())).thenReturn(Mono.error(capped));   // the pipeline's own check, as before the fix

        assertThat(service.onImage(FROM, "MEDIA-9").block()).isEqualTo(WaIntakeService.Outcome.FREE_TIER);

        assertThat(sentBodies()).as("the ack must not precede the refusal")
                .containsExactly(new Copy().bn("wa.free_tier", java.util.Map.of()));
        verify(graph, never()).downloadMedia(anyString());
    }

    /** The race past the first check: intake.accept keeps its own, and the refusal after the ack is still truthful. */
    @Test
    void aCapReachedAfterTheAckIsStillToldAsTheFreeTier() {
        familyWith(patient("মা"));
        when(intake.accept(any(), any())).thenReturn(Mono.error(
                new FreeTierExceededException(FreeTierExceededException.DOCUMENTS, "free tier")));

        assertThat(service.onImage(FROM, "MEDIA-5").block()).isEqualTo(WaIntakeService.Outcome.FREE_TIER);
        assertThat(sentBodies()).containsExactly(bn("wa.received"), bn("wa.free_tier"));
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

    // ---- D2 / DR-39: "কোড" asks for a sign-in code; every message opens the free window ------------------------

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"কোড", "  কোড ", "কোড।", "code", "Code", "CODE!", "kod", "otp",
            "OTP", "লগইন", "\u0995\u09C7\u09BE\u09A1"})   // the last: কোড typed as e-kar + aa-kar, which NFKC composes
    void everyWayOfAskingForTheLoginCodeIssuesOneAndIntakeItselfSendsNothing(String text) {
        when(auth.issueCodeFor(E164)).thenReturn(Mono.just(com.elioo.baymax.web.domain.CodeIssue.SENT));
        assertThat(service.onText(FROM, text).block()).isEqualTo(WaIntakeService.Outcome.LOGIN_CODE);
        verify(auth).issueCodeFor(E164);
        assertThat(delivered).as("the code goes out through OtpDeliveryPort only").isEmpty();
        verify(explainer, never()).detail(any());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"আমার কোড আসেনি", "code ta pai nai", "photo", "বিস্তারিত",
            "otp ki?", "কোডটা", "promo code", "hi", ""})
    void aMessageThatOnlyMentionsACodeIsNotALoginRequest(String text) {
        familyWith(patient("মা"));
        when(documents.latestDoneDocument(any())).thenReturn(Mono.empty());
        // stubbed, so a router that wrongly routes here fails on "never wanted", not on an unstubbed mock
        when(auth.issueCodeFor(anyString())).thenReturn(Mono.just(com.elioo.baymax.web.domain.CodeIssue.SENT));
        service.onText(FROM, text).block();
        verify(auth, never()).issueCodeFor(anyString());
    }

    @Test
    void aRateLimitedSenderIsToldOnWhatsappBecauseTheyHaveProvedTheNumber() {
        when(auth.issueCodeFor(E164)).thenReturn(Mono.just(com.elioo.baymax.web.domain.CodeIssue.RATE_LIMITED));
        service.onText(FROM, "কোড").block();
        assertThat(sentBodies()).containsExactly(copy.bn("wa.login_rate_limited", Map.of("minutes",
                com.elioo.baymax.outbound.application.service.BanglaDate.digits(String.valueOf(props.getAuth().getOtpTtl().toMinutes())))));
    }

    @Test
    void aSenderWithNoAccountHearsThatOnceAndNotTheGenericStub() {
        when(auth.issueCodeFor(E164)).thenReturn(Mono.just(com.elioo.baymax.web.domain.CodeIssue.NO_ACCOUNT));
        assertThat(service.onText(FROM, "কোড").block()).isEqualTo(WaIntakeService.Outcome.UNKNOWN_SENDER);
        assertThat(sentBodies()).containsExactly(bn("wa.login_no_account"));
        verify(records, never()).findFamilyByWhatsapp(anyString());   // the rule runs before the family lookup
    }

    @Test
    void aLoginThatCannotBeIssuedIsAnsweredNeverSilent() {
        when(auth.issueCodeFor(E164)).thenReturn(Mono.error(new com.elioo.baymax.common.error.BaymaxException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "auth_not_configured", "baymax.auth.hmac-secret is not configured")));
        assertThat(service.onText(FROM, "কোড").block()).isEqualTo(WaIntakeService.Outcome.FAILED);
        assertThat(sentBodies()).containsExactly(bn("wa.login_off"));
    }

    /** WhatsApp refused the message carrying the code: the family is told to try again, not left waiting. */
    @Test
    void aCodeWhatsappRefusedIsAnsweredNeverSilent() {
        when(auth.issueCodeFor(E164)).thenReturn(Mono.just(com.elioo.baymax.web.domain.CodeIssue.NOT_DELIVERED));
        assertThat(service.onText(FROM, "কোড").block()).isEqualTo(WaIntakeService.Outcome.FAILED);
        assertThat(sentBodies()).containsExactly(bn("wa.login_off"));
    }

    @Test
    void noLoginReplyGoesToASenderOffTheAllowlist() {
        props.getWa().setAllowlist(List.of("+8801700000999"));
        when(auth.issueCodeFor(E164)).thenReturn(Mono.just(com.elioo.baymax.web.domain.CodeIssue.NOT_PERMITTED));
        service.onText(FROM, "কোড").block();
        verify(graph, never()).sendText(anyString(), anyString());
    }

    /** PO 2026-10-18: every message the family sends opens the window in which a web sign-in code may be sent free. */
    @ParameterizedTest
    @EnumSource(value = WaInbound.Type.class, names = {"SYSTEM"}, mode = EnumSource.Mode.EXCLUDE)
    void everyMessageTheFamilySendsIsRecordedAsTheirLastInboundBeforeTheReply(WaInbound.Type type) {
        familyWith(patient("মা"));
        when(documents.latestDoneDocument(any())).thenReturn(Mono.empty());
        when(intake.accept(any(), any())).thenReturn(Mono.just(mock(Document.class)));
        service.onInbound(new WaInbound("w", FROM, type, type == WaInbound.Type.REACTION ? "👍" : "hi", "MEDIA-W", "image/jpeg")).block();
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(auth, graph);
        order.verify(auth).noteInbound(eq(E164), any());
        order.verify(graph).sendText(eq(E164), anyString());
    }

    /** The other direction: what the family did not send opens nothing — Meta's own notice, an emoji taken back. */
    @Test
    void aSystemNoticeOrAWithdrawnReactionOpensNoWindow() {
        service.onInbound(new WaInbound("w", FROM, WaInbound.Type.SYSTEM, "", "", "")).block();
        service.onInbound(new WaInbound("w", FROM, WaInbound.Type.REACTION, "", "", "")).block();
        verify(auth, never()).noteInbound(anyString(), any());
    }

    /** Recording the window is never worth a silence: if it fails, the family still gets the reply. */
    @Test
    void aWindowThatCannotBeRecordedStillGetsTheReply() {
        familyWith(patient("মা"));
        when(auth.noteInbound(anyString(), any())).thenReturn(Mono.error(new IllegalStateException("db down")));
        assertThat(service.onInbound(new WaInbound("w", FROM, WaInbound.Type.TEXT, "hi", "", "")).block())
                .isEqualTo(WaIntakeService.Outcome.REPLIED);
        assertThat(sentBodies()).hasSize(1);
    }

    // ---- read receipts and typing, behind BAYMAX_WA_READ_RECEIPTS (plan PR 10, Task 10.3) -------------------------

    @Test
    void withTypingOnAPhotoIsMarkedReadWithTypingBeforeTheWork() {
        props.getWa().setReadReceipts("typing");
        PatientProfile ma = patient("মা");
        familyWith(ma);
        when(intake.accept(eq(ma.id()), any())).thenReturn(Mono.just(mock(Document.class)));
        service.onInbound(new WaInbound(WaFixtures.wamid(FROM, 61), FROM, WaInbound.Type.IMAGE, "", "MEDIA-1", "image/jpeg")).block();
        InOrder order = inOrder(graph, intake);
        order.verify(graph).markRead(WaFixtures.wamid(FROM, 61), true);
        order.verify(graph).sendText(eq(E164), anyString());
        order.verify(intake).accept(eq(ma.id()), any());
    }

    @Test
    void withTypingAPlainTextGetsTheTickButNoDots() {          // a greeting is answered at once from a template: the tick, no dots
        props.getWa().setReadReceipts("typing");
        familyWith(patient("মা"));
        service.onInbound(new WaInbound(WaFixtures.wamid(FROM, 62), FROM, WaInbound.Type.TEXT, "hello", "", "")).block();
        verify(graph).markRead(WaFixtures.wamid(FROM, 62), false);
        assertThat(sentBodies()).hasSize(1);
    }

    /** The detail is a model call or a stored body to fetch: it takes time, so it gets the dots. */
    @Test
    void withTypingTheDetailWordGetsTheDots() {
        props.getWa().setReadReceipts("typing");
        familyWith(patient("মা"));
        latestDone();
        when(explainer.detail(DOC)).thenReturn(Mono.just(
                detailRow(OutboundMessage.GateStatus.RELEASED, Urgency.ROUTINE, "বিস্তারিত: সব স্বাভাবিক", Instant.EPOCH)));
        service.onInbound(new WaInbound(WaFixtures.wamid(FROM, 65), FROM, WaInbound.Type.TEXT, "বিস্তারিত", "", "")).block();
        verify(graph).markRead(WaFixtures.wamid(FROM, 65), true);
    }

    /** read: the blue ticks only, never the dots, even for a photo. */
    @Test
    void readModeNeverShowsTheDots() {
        props.getWa().setReadReceipts("read");
        PatientProfile ma = patient("মা");
        familyWith(ma);
        when(intake.accept(eq(ma.id()), any())).thenReturn(Mono.just(mock(Document.class)));
        service.onInbound(new WaInbound(WaFixtures.wamid(FROM, 66), FROM, WaInbound.Type.IMAGE, "", "MEDIA-1", "image/jpeg")).block();
        verify(graph).markRead(WaFixtures.wamid(FROM, 66), false);
    }

    /** The default. The detail word is the strongest case (it would get the dots), and it is still answered. */
    @Test
    void offMeansNoReadReceiptAtAll() {
        familyWith(patient("মা"));
        latestDone();
        when(explainer.detail(DOC)).thenReturn(Mono.just(
                detailRow(OutboundMessage.GateStatus.RELEASED, Urgency.ROUTINE, "বিস্তারিত: সব স্বাভাবিক", Instant.EPOCH)));
        service.onInbound(new WaInbound(WaFixtures.wamid(FROM, 63), FROM, WaInbound.Type.TEXT, "বিস্তারিত", "", "")).block();
        verify(graph, never()).markRead(anyString(), anyBoolean());
        assertThat(sentBodies()).as("the message was still answered, so the silence is the mode's").hasSize(1);
    }

    /** The one allowlist rule covers the third outbound path too: a read receipt is a message to that number. */
    @Test
    void aSenderOffTheAllowlistGetsNoReadReceipt() {
        props.getWa().setReadReceipts("typing");
        props.getWa().setAllowlist(List.of("+8801700000999"));
        familyWith(patient("মা"));
        latestDone();
        when(explainer.detail(DOC)).thenReturn(Mono.just(
                detailRow(OutboundMessage.GateStatus.RELEASED, Urgency.ROUTINE, "বিস্তারিত: সব স্বাভাবিক", Instant.EPOCH)));
        service.onInbound(new WaInbound(WaFixtures.wamid(FROM, 64), FROM, WaInbound.Type.TEXT, "বিস্তারিত", "", "")).block();
        verify(explainer).detail(DOC);   // the message was handled: the receipt is absent because of the rule, not a crash
        verify(graph, never()).markRead(anyString(), anyBoolean());
    }

    /** Meta: show the indicator only when we will respond. What needs no reply gets no tick either. */
    @Test
    void whatNeedsNoReplyGetsNoReadReceipt() {
        props.getWa().setReadReceipts("typing");
        service.onInbound(new WaInbound(WaFixtures.wamid(FROM, 67), FROM, WaInbound.Type.SYSTEM, "", "", "")).block();
        service.onInbound(new WaInbound(WaFixtures.wamid(FROM, 68), FROM, WaInbound.Type.REACTION, "", "", "")).block();
        verify(graph, never()).markRead(anyString(), anyBoolean());
    }

    /** A courtesy that fails must not cost the family the reply. */
    @Test
    void aReadReceiptThatFailsStillGetsTheReply() {
        props.getWa().setReadReceipts("typing");
        familyWith(patient("মা"));
        when(graph.markRead(anyString(), anyBoolean())).thenReturn(Mono.error(new IllegalStateException("graph down")));
        assertThat(service.onInbound(new WaInbound(WaFixtures.wamid(FROM, 69), FROM, WaInbound.Type.TEXT, "hi", "", "")).block())
                .isEqualTo(WaIntakeService.Outcome.REPLIED);
        assertThat(sentBodies()).hasSize(1);
    }

    /**
     * The claim is "a courtesy never costs the answer", and the way a courtesy costs it is not an error but a call that
     * never returns: onErrorResume cannot see a stall, and the receipt runs in front of the 24 h window record and the
     * reply. Mono.never() is that stall; virtual time lets the bound pass without the test waiting for it.
     */
    @Test
    void aReadReceiptThatHangsStillGetsTheReply() {
        props.getWa().setReadReceipts("typing");
        familyWith(patient("মা"));
        when(graph.markRead(anyString(), anyBoolean())).thenReturn(Mono.never());
        StepVerifier.withVirtualTime(() -> service.onInbound(
                        new WaInbound(WaFixtures.wamid(FROM, 70), FROM, WaInbound.Type.TEXT, "hi", "", "")))
                .thenAwait(Duration.ofSeconds(5))
                .expectNext(WaIntakeService.Outcome.REPLIED)
                .expectComplete()
                .verify(Duration.ofSeconds(5));
        assertThat(sentBodies()).hasSize(1);
        verify(auth).noteInbound(eq(E164), any());
    }

    /** The photo is the case the class exists for: a hung receipt must not hold back the ack or the intake. */
    @Test
    void aReadReceiptThatHangsStillAcknowledgesAndFilesThePhoto() {
        props.getWa().setReadReceipts("typing");
        PatientProfile ma = patient("মা");
        familyWith(ma);
        when(intake.accept(eq(ma.id()), any())).thenReturn(Mono.just(mock(Document.class)));
        when(graph.markRead(anyString(), anyBoolean())).thenReturn(Mono.never());
        StepVerifier.withVirtualTime(() -> service.onInbound(
                        new WaInbound(WaFixtures.wamid(FROM, 71), FROM, WaInbound.Type.IMAGE, "", "MEDIA-1", "image/jpeg")))
                .thenAwait(Duration.ofSeconds(5))
                .expectNext(WaIntakeService.Outcome.ACCEPTED)
                .expectComplete()
                .verify(Duration.ofSeconds(5));
        assertThat(sentBodies()).containsExactly(bn("wa.received"));
        verify(intake).accept(eq(ma.id()), any());
    }

    /**
     * Both directions of takesTime, through onInbound with mode=typing: what the dots are for (a photo, a PDF, the detail
     * word typed or tapped) and what is answered at once (a DOCX refused by template, a reaction, a greeting tapped).
     */
    @ParameterizedTest(name = "{0} {1} \"{2}\" -> typing={3}")
    @CsvSource(delimiter = '|', value = {
            "IMAGE       | image/jpeg                                                              |           | true",
            "DOCUMENT    | application/pdf                                                         |           | true",
            "DOCUMENT    | application/vnd.openxmlformats-officedocument.wordprocessingml.document |           | false",
            "BUTTON      |                                                                         | বিস্তারিত | true",
            "INTERACTIVE |                                                                         | বিস্তারিত | true",
            "BUTTON      |                                                                         | hello     | false",
            "REACTION    |                                                                         | 👍        | false",
    })
    void typingFollowsWhetherTheAnswerTakesTime(WaInbound.Type type, String mime, String text, boolean typing) {
        props.getWa().setReadReceipts("typing");
        PatientProfile ma = patient("মা");
        familyWith(ma);
        latestDone();
        when(explainer.detail(DOC)).thenReturn(Mono.just(
                detailRow(OutboundMessage.GateStatus.RELEASED, Urgency.ROUTINE, "বিস্তারিত: সব স্বাভাবিক", Instant.EPOCH)));
        when(intake.accept(eq(ma.id()), any())).thenReturn(Mono.just(mock(Document.class)));
        String id = WaFixtures.wamid(FROM, 72);
        WaInbound in = new WaInbound(id, FROM, type, text == null ? "" : text, mime == null ? "" : "MEDIA-9",
                mime == null ? "" : mime);
        assertThat(WaIntakeService.takesTime(in)).isEqualTo(typing);
        service.onInbound(in).block();
        verify(graph).markRead(id, typing);
        assertThat(sentBodies()).as("every one of them is answered").isNotEmpty();
    }

    // ---- B1b (plan PR 7, Task 7.1): a greeting and a thanks restate a standing NOW / THIS_WEEK --------------------------
    // The standing level is F1's stored one (V18 document.urgency), read the way the চ্যাট attention stack reads it, never
    // from a message row: a NOW whose explanation threw, failed safety or waits for review stands exactly as it is stored.
    // Every assertion in this section is on the WHOLE message: both branches name the paper, so a check on the paper's
    // name alone passes for a "reminder" about nothing (review finding 1).

    /** The fixed clock's day in Dhaka, which BanglaDate judges "today" by. */
    private static final LocalDate TODAY = LocalDate.parse("2026-10-17");
    private static final java.time.ZoneId DHAKA = java.time.ZoneId.of("Asia/Dhaka");
    private final java.util.Map<UUID, java.util.List<AttentionItem.Urgent>> urgentRows = new java.util.HashMap<>();

    /** As the row reads back: a DONE paper with a printed date is filed under it (D2, document.timeline_date). */
    private Document doneDocument(PatientProfile p, LocalDate docDate, Instant uploadedAt) {
        return new Document(UUID.randomUUID(), p.id(), FAMILY, "lab_report", docDate, "Shapla Diagnostics", "{}", 0.93,
                Document.Status.DONE, null, "anthropic/claude-sonnet-5", null, 1, uploadedAt, uploadedAt,
                com.elioo.baymax.extraction.domain.VerifiedItems.Unverified.none(), null, null, false, docDate, false);
    }

    /** A paper uploaded on the morning of its own date. */
    private Document doneDocument(PatientProfile p, String docDate) {
        LocalDate d = LocalDate.parse(docDate);
        return doneDocument(p, d, d.atTime(8, 0).toInstant(java.time.ZoneOffset.UTC));
    }

    private void latestIs(Document d) {
        when(documents.latestDoneDocument(FAMILY)).thenReturn(Mono.just(d));
    }

    /**
     * What F1 holds for a paper, as both reads see it: the document's own stored level, and (NOW or THIS_WEEK only) its
     * row in the attention read, in the read model's order. The fake returns every row it is given, older than 30 days
     * included, so the window these tests pin is this service's own and not the fake's.
     */
    private void stored(Document d, Urgency level) {
        when(urgency.currentOrAssess(d.id())).thenReturn(Mono.just(
                new com.elioo.baymax.outbound.domain.StoredUrgency(d.id(), level, List.of(), d.createdAt())));
        if (level.isAtLeast(Urgency.THIS_WEEK)) {
            java.util.List<AttentionItem.Urgent> rows = urgentRows.computeIfAbsent(d.patientId(), k -> new java.util.ArrayList<>());
            rows.add(new AttentionItem.Urgent(d.id(), level, d.documentType(), d.timelineDate(), d.timelineDateIsUploadDay(), d.createdAt()));
            rows.sort(AttentionItem.ORDER);
            when(attention.urgent(FAMILY, d.patientId())).thenReturn(Mono.just(List.copyOf(rows)));
        }
    }

    private Map<String, String> paper(Document d) {
        return paper(d, d.timelineDate());
    }

    /** The paper's name for the family, dated {@code date}. */
    private Map<String, String> paper(Document d, LocalDate date) {
        return Map.of("document_type", copy.documentType(d.documentType()), "date", BanglaDate.format(date, TODAY),
                "link", props.getOutbound().getPublicBaseUrl() + "/app/documents/" + d.id());
    }

    /** Nothing stands: the opener, the intro and the latest paper with its link. No "মনে করিয়ে দিই", no urgency line. */
    private String greetingNaming(Document d) {
        return greetingNaming(d, d.timelineDate());
    }

    private String greetingNaming(Document d, LocalDate date) {
        return String.join("\n", bn("wa.greeting.hello"), bn("wa.greeting.intro"), copy.bn("wa.greeting.latest", paper(d, date)));
    }

    /** A standing level: the opener, the urgent paper's name, its fixed line and the red-flag line, then the intro. */
    private String greetingLedBy(Urgency level, Document d) {
        return String.join("\n", bn("wa.greeting.hello"), copy.bn("wa.greeting.urgent", paper(d)),
                bn(level == Urgency.NOW ? "urgency.now" : "urgency.this_week"), bn("safety.red_flag_line"), bn("wa.greeting.intro"));
    }

    private String thanksLedBy(Urgency level) {
        return String.join("\n", bn("wa.thanks.urgent"), bn(level == Urgency.NOW ? "urgency.now" : "urgency.this_week"),
                bn("safety.red_flag_line"));
    }

    private String plainThanks() {
        return bn("wa.thanks") + "\n" + bn("safety.red_flag_line");
    }

    /**
     * D2: a paper is named by the day it is filed under, as Postgres generated it (document.timeline_date), never by a
     * date read off the page. A retake read back from its row carries its Dhaka upload day there; the misread printed
     * date stays in doc_date and must never reach the family. Asserted on the whole message the paper's name is built
     * into, against the one built from each date, not on a substring.
     */
    @Test
    void aRetakeIsNeverDescribedByItsMisreadDate() {
        PatientProfile ma = patient("মা");
        familyWith(ma);
        Instant uploaded = Instant.parse("2026-10-20T04:00:00Z");
        LocalDate misread = LocalDate.parse("2021-03-04");
        LocalDate uploadDay = LocalDate.parse("2026-10-20");
        // as the row reads back: the printed date kept, the filing day the upload day, and the fallback flag set
        Document retake = new Document(UUID.randomUUID(), ma.id(), FAMILY, "lab_report", misread, null, "{}", 0.4,
                Document.Status.NEEDS_RETAKE, "developer prose", "anthropic/claude-sonnet-5", null, 1, uploaded, uploaded,
                com.elioo.baymax.extraction.domain.VerifiedItems.Unverified.none(), null, "low_overall", false, uploadDay, true);
        latestIs(retake);

        service.onText(FROM, "Hi").block();

        assertThat(sentBodies()).as("the paper is named by the day it was uploaded")
                .containsExactly(greetingNaming(retake, uploadDay))
                .doesNotContain(greetingNaming(retake, misread));
    }

    /**
     * Review blocker: a DONE NOW paper whose explain() threw has a stored NOW and no outbound row at all (ExplanationService
     * stores before it composes). Read from message rows, the greeting named the paper and said nothing of the NOW.
     */
    @Test
    void aStoredNowWithNoMessageRowAtAllLeadsTheGreeting() {
        PatientProfile ma = patient("মা");
        familyWith(ma);
        Document doc = doneDocument(ma, "2026-10-16");
        latestIs(doc);
        stored(doc, Urgency.NOW);

        service.onText(FROM, "Hi").block();

        assertThat(sentBodies()).singleElement().asString()
                .as("a stored NOW hidden because no message row was ever written is the defect").contains(bn("urgency.now"));
        assertThat(sentBodies()).containsExactly(greetingLedBy(Urgency.NOW, doc));
    }

    @Test
    void aStoredNowWithNoMessageRowAtAllLeadsTheThanks() {
        PatientProfile ma = patient("মা");
        familyWith(ma);
        Document doc = doneDocument(ma, "2026-10-16");
        latestIs(doc);
        stored(doc, Urgency.NOW);

        service.onText(FROM, "ok thanks").block();

        assertThat(sentBodies()).singleElement().asString().as("a thanks closing over a stored NOW").contains(bn("urgency.now"));
        assertThat(sentBodies()).containsExactly(thanksLedBy(Urgency.NOW));
    }

    /** Review blocker (b): an older urgent paper behind a newer routine one still leads, and it is the one named. */
    @Test
    void anOlderStoredThisWeekBehindANewerRoutinePaperStillLeads() {
        PatientProfile ma = patient("মা");
        familyWith(ma);
        Document older = doneDocument(ma, "2026-10-10");
        Document newer = doneDocument(ma, "2026-10-16");
        stored(older, Urgency.THIS_WEEK);
        stored(newer, Urgency.ROUTINE);
        latestIs(newer);

        service.onText(FROM, "Hi").block();

        assertThat(sentBodies()).singleElement().asString()
                .as("a newer routine paper hiding a standing THIS_WEEK is the defect").contains(bn("urgency.this_week"));
        assertThat(sentBodies()).containsExactly(greetingLedBy(Urgency.THIS_WEEK, older));
    }

    /**
     * Review finding 2, the twin the old test could not tell apart: the NOW is OLDER than a lower paper. "Newest wins"
     * downgrades a standing NOW to THIS_WEEK; only "highest wins" keeps it.
     */
    @Test
    void aStandingNowIsNotDowngradedByANewerLowerPaper() {
        PatientProfile ma = patient("মা");
        familyWith(ma);
        Document now = doneDocument(ma, "2026-10-12");
        Document thisWeek = doneDocument(ma, "2026-10-15");
        stored(now, Urgency.NOW);
        stored(thisWeek, Urgency.THIS_WEEK);
        latestIs(thisWeek);

        service.onText(FROM, "Hi").block();
        service.onText(FROM, "ok thanks").block();

        assertThat(sentBodies().get(0)).as("a newer THIS_WEEK downgrading a standing NOW is the defect")
                .contains(bn("urgency.now")).doesNotContain(bn("urgency.this_week"));
        assertThat(sentBodies()).containsExactly(greetingLedBy(Urgency.NOW, now), thanksLedBy(Urgency.NOW));
    }

    /** One number, two patients: Baba's stored NOW leads even though Ma's routine paper is the family's latest. */
    @Test
    void aStandingUrgencyOfAnyPatientOfTheFamilyLeads() {
        PatientProfile ma = patient("মা");
        PatientProfile baba = patient("বাবা");
        familyWith(ma, baba);
        Document babas = doneDocument(baba, "2026-10-14");
        Document mas = doneDocument(ma, "2026-10-16");
        stored(babas, Urgency.NOW);
        stored(mas, Urgency.ROUTINE);
        latestIs(mas);

        service.onText(FROM, "Hi").block();

        assertThat(sentBodies()).containsExactly(greetingLedBy(Urgency.NOW, babas));
    }

    /**
     * The latest paper never assessed (a pre-V18 row, or one whose assessment threw before the sweep reached it) has no
     * attention row; the document page's read, currentOrAssess, assesses it once through the writer and its level stands.
     */
    @Test
    void aNeverAssessedLatestPaperIsAssessedAndItsNowLeads() {
        PatientProfile ma = patient("মা");
        familyWith(ma);
        Document doc = doneDocument(ma, "2026-10-16");
        latestIs(doc);
        when(urgency.currentOrAssess(doc.id())).thenReturn(Mono.just(
                new com.elioo.baymax.outbound.domain.StoredUrgency(doc.id(), Urgency.NOW, List.of(), clock.instant())));

        service.onText(FROM, "Hi").block();

        assertThat(sentBodies()).containsExactly(greetingLedBy(Urgency.NOW, doc));
    }

    /** W1: an unacknowledged THIS_WEEK in the last 30 days leads the greeting, in its fixed words. */
    @Test
    void aGreetingLeadsWithAStandingUrgencyVerbatim() {
        PatientProfile ma = patient("মা");
        familyWith(ma);
        Document doc = doneDocument(ma, "2026-10-10");
        latestIs(doc);
        stored(doc, Urgency.THIS_WEEK);

        service.onText(FROM, "Hi").block();

        assertThat(sentBodies()).singleElement().asString()
                .as("a greeting that hides a standing THIS_WEEK is the defect").contains(bn("urgency.this_week"));
        // UrgencyLines: only the one-line address (the opener, the paper's name) precedes the block; the intro follows it
        assertThat(sentBodies()).containsExactly(greetingLedBy(Urgency.THIS_WEEK, doc));
    }

    /** W12, and NOW carries the red-flag line exactly once. */
    @Test
    void okThanksAfterANowRepeatsTheNowLine() {
        PatientProfile ma = patient("মা");
        familyWith(ma);
        Document doc = doneDocument(ma, "2026-10-16");
        latestIs(doc);
        stored(doc, Urgency.NOW);

        service.onText(FROM, "ok thanks").block();

        String sent = sentBodies().get(0);
        assertThat(sent).as("a thanks after NOW must repeat it").contains(bn("urgency.now"));
        assertThat(sent.split(java.util.regex.Pattern.quote(bn("safety.red_flag_line")), -1)).as("red-flag line once").hasSize(2);
        assertThat(sentBodies()).containsExactly(thanksLedBy(Urgency.NOW));
    }

    // -- the safe direction, each case on its own and on the whole message (review finding 1) --------------------------

    /** A RECENT routine paper: named with its link, and no reminder about nothing. */
    @Test
    void aRecentRoutinePaperIsNamedWithoutAnUrgencyLine() {
        PatientProfile ma = patient("মা");
        familyWith(ma);
        Document doc = doneDocument(ma, "2026-10-16");
        latestIs(doc);
        stored(doc, Urgency.ROUTINE);

        service.onText(FROM, "Hi").block();

        assertThat(sentBodies()).singleElement().asString().doesNotContain("মনে করিয়ে দিই")
                .contains("/app/documents/" + doc.id());
        assertThat(sentBodies()).containsExactly(greetingNaming(doc));
    }

    /** A THIS_WEEK 46 days old no longer stands: named with its link, no urgency line, no reminder. */
    @Test
    void aStaleThisWeekPaperIsNamedWithoutAnUrgencyLine() {
        PatientProfile ma = patient("মা");
        familyWith(ma);
        Document doc = doneDocument(ma, "2026-09-01");
        latestIs(doc);
        stored(doc, Urgency.THIS_WEEK);

        service.onText(FROM, "Hi").block();

        assertThat(sentBodies()).singleElement().asString().doesNotContain("মনে করিয়ে দিই")
                .contains("/app/documents/" + doc.id());
        assertThat(sentBodies()).containsExactly(greetingNaming(doc));
    }

    /** The thanks twins: a paper present and nothing standing is the plain close, not "ঠিক আছে। মনে করিয়ে দিই:". */
    @Test
    void aThanksAfterARecentRoutinePaperIsThePlainClose() {
        PatientProfile ma = patient("মা");
        familyWith(ma);
        Document doc = doneDocument(ma, "2026-10-16");
        latestIs(doc);
        stored(doc, Urgency.ROUTINE);

        service.onText(FROM, "ok thanks").block();

        assertThat(sentBodies()).containsExactly(plainThanks());
    }

    @Test
    void aThanksAfterAStaleThisWeekPaperIsThePlainClose() {
        PatientProfile ma = patient("মা");
        familyWith(ma);
        Document doc = doneDocument(ma, "2026-09-01");
        latestIs(doc);
        stored(doc, Urgency.THIS_WEEK);

        service.onText(FROM, "ok thanks").block();

        assertThat(sentBodies()).containsExactly(plainThanks());
    }

    // -- the 30-day window, pinned at both sides from the clock (review finding 6) ---------------------------------------

    @Test
    void aThisWeekUploaded29DaysAgoStillLeadsTheGreeting() {
        PatientProfile ma = patient("মা");
        familyWith(ma);
        Instant at = clock.instant().minus(Duration.ofDays(29));
        Document doc = doneDocument(ma, LocalDate.ofInstant(at, DHAKA), at);
        latestIs(doc);
        stored(doc, Urgency.THIS_WEEK);

        service.onText(FROM, "Hi").block();

        assertThat(sentBodies()).containsExactly(greetingLedBy(Urgency.THIS_WEEK, doc));
    }

    @Test
    void aThisWeekUploaded31DaysAgoNoLongerLeadsTheGreeting() {
        PatientProfile ma = patient("মা");
        familyWith(ma);
        Instant at = clock.instant().minus(Duration.ofDays(31));
        Document doc = doneDocument(ma, LocalDate.ofInstant(at, DHAKA), at);
        latestIs(doc);
        stored(doc, Urgency.THIS_WEEK);

        service.onText(FROM, "Hi").block();

        assertThat(sentBodies()).containsExactly(greetingNaming(doc));
    }

    // -- a read that fails still gets exactly one reply, and it carries the red-flag line (review findings 5 and 9) ------

    /** Which of the standing-urgency reads fails. */
    enum UnreadableRead { LATEST_PAPER, ATTENTION, STORED_LEVEL }

    private void failing(UnreadableRead which, PatientProfile p, Document doc) {
        IllegalStateException e = new IllegalStateException("connection to the pooler timed out");
        switch (which) {
            case LATEST_PAPER -> when(documents.latestDoneDocument(FAMILY)).thenReturn(Mono.error(e));
            case ATTENTION -> when(attention.urgent(FAMILY, p.id())).thenReturn(Mono.error(e));
            case STORED_LEVEL -> when(urgency.currentOrAssess(doc.id())).thenReturn(Mono.error(e));
        }
    }

    /**
     * Before PR 7 a greeting read nothing beyond the family; now a pooler timeout in any of its reads reached the webhook,
     * which logs it and sends nothing (PR 6: every message gets exactly one reply). The level could not be read, so the
     * plain greeting carries the red-flag line: a NOW must not be silently replaced by a bare hello.
     */
    @ParameterizedTest
    @EnumSource(UnreadableRead.class)
    void aGreetingWhoseStandingUrgencyCannotBeReadIsStillAnsweredOnceWithTheRedFlagLine(UnreadableRead which) {
        PatientProfile ma = patient("মা");
        familyWith(ma);
        Document doc = doneDocument(ma, "2026-10-16");
        latestIs(doc);
        stored(doc, Urgency.NOW);
        failing(which, ma, doc);

        // as WaWebhookHandler does with an error: log it, and nothing more
        WaIntakeService.Outcome outcome = service.onText(FROM, "Hi").onErrorResume(e -> Mono.empty()).block();

        assertThat(sentBodies()).as("silence on a failed read is the defect").hasSize(1);
        assertThat(outcome).isEqualTo(WaIntakeService.Outcome.REPLIED);
        assertThat(sentBodies()).containsExactly(String.join("\n", bn("wa.greeting.hello"), bn("wa.greeting.intro"),
                bn("safety.red_flag_line")));
    }

    @ParameterizedTest
    @EnumSource(UnreadableRead.class)
    void aThanksWhoseStandingUrgencyCannotBeReadIsStillAnsweredOnceWithTheRedFlagLine(UnreadableRead which) {
        PatientProfile ma = patient("মা");
        familyWith(ma);
        Document doc = doneDocument(ma, "2026-10-16");
        latestIs(doc);
        stored(doc, Urgency.NOW);
        failing(which, ma, doc);

        WaIntakeService.Outcome outcome = service.onText(FROM, "ok thanks").onErrorResume(e -> Mono.empty()).block();

        assertThat(sentBodies()).as("silence on a failed read is the defect").hasSize(1);
        assertThat(outcome).isEqualTo(WaIntakeService.Outcome.REPLIED);
        assertThat(sentBodies()).containsExactly(plainThanks());
    }

    // ---- W13 (plan PR 7, Task 7.3): a failure after the ack is told, never left on "পড়ে দেখছি" -------------------------

    /** Stock-take wa-failure-reply (2): after the ack, the download failed and the family was left on "পড়ে দেখছি". */
    @Test
    void aDownloadThatFailsAfterTheAckIsToldNotLeftWaiting() {
        familyWith(patient("মা"));
        when(graph.downloadMedia("MEDIA-10")).thenReturn(Mono.error(new RuntimeException("boom")));
        service.onImage(FROM, "MEDIA-10").block();
        assertThat(sentBodies()).as("after the ack the family must hear what happened — silence was the defect").hasSize(2);
        assertThat(sentBodies().get(1)).isEqualTo(bn("wa.failed.generic"));
    }

    /** downloadMedia's one error (WaGraphClient maps every failure to it) gets the "send it again" reply. */
    @Test
    void aMediaUnavailableErrorGetsTheDownloadReply() {
        familyWith(patient("মা"));
        when(graph.downloadMedia("MEDIA-14")).thenReturn(Mono.error(new WaMessagingPort.MediaUnavailableException(404)));
        assertThat(service.onImage(FROM, "MEDIA-14").block()).isEqualTo(WaIntakeService.Outcome.FAILED);
        assertThat(sentBodies()).containsExactly(bn("wa.received"), bn("wa.failed.download"));
        verify(intake, never()).accept(any(), any());
    }

    /**
     * A download that completes EMPTY skipped intake.accept and still reported ACCEPTED: the family heard "পড়ে দেখছি" and
     * nothing after it. The port promises bytes or its one error; this service does not take that on trust.
     */
    @Test
    void aDownloadThatCompletesEmptyIsToldAsADownloadFailure() {
        familyWith(patient("মা"));
        when(graph.downloadMedia("MEDIA-15")).thenReturn(Mono.empty());
        assertThat(service.onImage(FROM, "MEDIA-15").block()).as("an empty download reported as accepted is the defect")
                .isEqualTo(WaIntakeService.Outcome.FAILED);
        assertThat(sentBodies()).containsExactly(bn("wa.received"), bn("wa.failed.download"));
        verify(intake, never()).accept(any(), any());
    }

    /**
     * Reasons that get the generic "send it again in a little while" ON PURPOSE: none of them is about the file, so resending
     * the same file is the right advice. no_file: the upload carried nothing (cannot come from WhatsApp, which always sends
     * one). patient_not_found and document_not_found: a race with a deletion. upload_key_reused: a retried upload whose key
     * belongs elsewhere.
     */
    private static final java.util.Set<String> GENERIC_ON_PURPOSE =
            java.util.Set.of("no_file", "patient_not_found", "upload_key_reused", "document_not_found");

    /**
     * Every reason the intake path can refuse a file with, DERIVED from the sources that raise them (CLAUDE.md: never a
     * hand-written list of what the author remembered). A new reason PageRenderer or the intake adds, say image_too_large,
     * either gets its own reply or is added to GENERIC_ON_PURPOSE with its why; otherwise this goes red. A reason that
     * means "this file cannot be read" falling into the generic reply has the family resend the same file forever.
     */
    @Test
    void everyRefusalReasonTheIntakePathRaisesHasAReplyChosenForIt() throws java.io.IOException {
        java.util.regex.Pattern raised = java.util.regex.Pattern.compile("BaymaxException\\.\\w+\\(\\s*\"([a-z_]+)\"");
        java.util.Set<String> reasons = new java.util.TreeSet<>();
        for (String source : List.of("PageRenderer.java", "DocumentIntakeService.java")) {
            java.util.regex.Matcher m = raised.matcher(java.nio.file.Files.readString(
                    java.nio.file.Path.of("src/main/java/com/elioo/baymax/extraction/application/service", source)));
            while (m.find()) {
                reasons.add(m.group(1));
            }
        }
        // the sweep's own guard: an empty or shrunken read would pass vacuously
        assertThat(reasons).as("the reasons were actually read from the sources")
                .contains("too_many_pages", "unsupported_image", "empty_file", "no_file").hasSizeGreaterThanOrEqualTo(8);
        assertThat(GENERIC_ON_PURPOSE).as("every generic-on-purpose reason still exists").isSubsetOf(reasons);

        for (String reason : reasons) {
            String key = WaIntakeService.failureKey(com.elioo.baymax.common.error.BaymaxException.badRequest(reason, "x"));
            if (GENERIC_ON_PURPOSE.contains(reason)) {
                assertThat(key).as(reason + " is generic on purpose").isEqualTo("wa.failed.generic");
            } else {
                assertThat(key).as(reason + " needs a reply chosen for it, not the generic one").isNotEqualTo("wa.failed.generic");
                assertThat(bn(key)).as(reason + " maps to a key the copy has (Copy.bn throws on a missing one)").isNotBlank();
            }
        }
        assertThat(WaIntakeService.failureKey(new IllegalStateException("x"))).isEqualTo("wa.failed.generic");
    }

    /** The reply reads baymax.extract.max-pages: at 7, a hardcoded "১০" (the default) would be wrong. */
    @Test
    void tooManyPagesSaysHowManyAreAllowed() {
        props.getExtract().setMaxPages(7);
        familyWith(patient("মা"));
        when(intake.accept(any(), any())).thenReturn(Mono.error(
                com.elioo.baymax.common.error.BaymaxException.badRequest("too_many_pages", "at most 7")));
        service.onImage(FROM, "MEDIA-11").block();
        assertThat(sentBodies()).as("silence after the ack — the defect").hasSize(2);
        assertThat(sentBodies().get(1)).isEqualTo(new Copy().bn("wa.failed.too_many_pages", java.util.Map.of("max", "৭")));
    }

    @Test
    void aFileThatCannotBeOpenedSaysSo() {
        familyWith(patient("মা"));
        when(intake.accept(any(), any())).thenReturn(Mono.error(
                com.elioo.baymax.common.error.BaymaxException.badRequest("unsupported_image", "could not read MEDIA-12.jpg")));
        service.onImage(FROM, "MEDIA-12").block();
        assertThat(sentBodies()).as("silence after the ack — the defect").hasSize(2);
        assertThat(sentBodies().get(1)).isEqualTo(bn("wa.failed.unreadable_file"));
    }
}
