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
import com.elioo.baymax.wa.application.port.out.WaMessagingPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
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

    private static final String FROM = "8801793399171";
    private static final String E164 = "+8801793399171";
    private static final UUID FAMILY = UUID.randomUUID();

    private final HealthRecordPort records = mock(HealthRecordPort.class);
    private final DocumentIntakeUseCase intake = mock(DocumentIntakeUseCase.class);
    private final WaMessagingPort graph = mock(WaMessagingPort.class);
    private final BaymaxProperties props = new BaymaxProperties();
    private WaIntakeService service;

    @BeforeEach
    void wire() {
        props.getWa().setEnabled(true);
        props.getWa().setAllowlist(List.of(E164));
        when(graph.sendText(anyString(), anyString())).thenReturn(Mono.just("wamid.REPLY"));
        when(graph.downloadMedia(anyString())).thenReturn(Mono.just(new byte[]{1, 2, 3}));
        service = new WaIntakeService(records, intake, graph, new Copy(), props);
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

    @Test
    void metaNumbersAreNormalisedToE164() {
        assertThat(WaIntakeService.e164("8801793399171")).isEqualTo("+8801793399171");
        assertThat(WaIntakeService.e164("+8801793399171")).isEqualTo("+8801793399171");
        assertThat(WaIntakeService.e164("")).isEmpty();
        assertThat(WaIntakeService.e164(null)).isEmpty();
    }
}
