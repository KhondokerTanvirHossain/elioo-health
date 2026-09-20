package com.elioo.baymax.family.application.service;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.common.error.FreeTierExceededException;
import com.elioo.baymax.family.application.port.in.FamilyAccountUseCase.AddPatientCommand;
import com.elioo.baymax.family.application.port.in.FamilyAccountUseCase.CreateFamilyCommand;
import com.elioo.baymax.family.application.port.in.FreeTierUseCase;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.DeletionCounts;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.healthrecord.domain.ShareMember;
import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
import com.elioo.baymax.storage.domain.DeletionReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FamilyAccountServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");

    private final HealthRecordPort records = mock(HealthRecordPort.class);
    private final FreeTierUseCase freeTier = mock(FreeTierUseCase.class);
    private final DocumentStorageUseCase storage = mock(DocumentStorageUseCase.class);
    private final FamilyAccountService service = new FamilyAccountService(records, freeTier, storage,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private final UUID familyId = UUID.randomUUID();
    private final FamilyAccount family = new FamilyAccount(familyId, "+8801700000000", "Rahim", FamilyAccount.Plan.FREE, NOW, NOW);

    @BeforeEach
    void stubs() {
        when(records.createFamily(any())).thenAnswer(inv -> inv.getArgument(0) == null ? Mono.empty()
                : Mono.just(withId((FamilyAccount) inv.getArgument(0))));
        when(records.createPatient(any())).thenAnswer(inv -> inv.getArgument(0) == null ? Mono.empty()
                : Mono.just(withId((PatientProfile) inv.getArgument(0))));
        when(records.addShareMember(any())).thenAnswer(inv -> inv.getArgument(0) == null ? Mono.empty()
                : Mono.just(withId((ShareMember) inv.getArgument(0))));
        when(freeTier.checkCanAddPatient(any())).thenReturn(Mono.empty());
    }

    private static FamilyAccount withId(FamilyAccount f) {
        return new FamilyAccount(UUID.randomUUID(), f.whatsappNumber(), f.ownerName(), f.plan(), f.termsAcceptedAt(), f.createdAt());
    }

    private static PatientProfile withId(PatientProfile p) {
        return new PatientProfile(UUID.randomUUID(), p.familyId(), p.name(), p.age(), p.sex(), p.chronicFlags(), p.proxyConsentAt(), p.createdAt());
    }

    private static ShareMember withId(ShareMember m) {
        return new ShareMember(UUID.randomUUID(), m.patientId(), m.whatsappNumber(), m.addedAt(), m.notifiedAt());
    }

    @Test
    void termsNotAcceptedIs400AndNothingIsWritten() {
        StepVerifier.create(service.createFamily(new CreateFamilyCommand("+8801700000000", "Rahim", false)))
                .expectErrorMatches(e -> e instanceof BaymaxException b && b.status().value() == 400 && b.reason().equals("terms_not_accepted"))
                .verify();
        verify(records, never()).createFamily(any());
    }

    @Test
    void createFamilyNormalisesTheNumberAndStampsConsentServerSide() {
        when(records.findFamilyByWhatsapp("+8801700000000")).thenReturn(Mono.empty());

        StepVerifier.create(service.createFamily(new CreateFamilyCommand("+880 170-000 0000", "  Rahim ", true)))
                .assertNext(f -> {
                    assertThat(f.id()).isNotNull();
                    assertThat(f.whatsappNumber()).isEqualTo("+8801700000000");
                    assertThat(f.ownerName()).isEqualTo("Rahim");
                    assertThat(f.plan()).isEqualTo(FamilyAccount.Plan.FREE);
                    assertThat(f.termsAcceptedAt()).isEqualTo(NOW);
                })
                .verifyComplete();
    }

    @Test
    void badNumberIs400() {
        StepVerifier.create(service.createFamily(new CreateFamilyCommand("1700000000", "Rahim", true)))   // ten digits, no country
                .expectErrorMatches(e -> e instanceof BaymaxException b && b.status().value() == 400)
                .verify();
        verify(records, never()).createFamily(any());
    }

    @Test
    void existingNumberIs409() {
        when(records.findFamilyByWhatsapp("+8801700000000")).thenReturn(Mono.just(family));

        StepVerifier.create(service.createFamily(new CreateFamilyCommand("+8801700000000", "Rahim", true)))
                .expectErrorMatches(e -> e instanceof BaymaxException b && b.status().value() == 409 && b.reason().equals("family_exists"))
                .verify();
        verify(records, never()).createFamily(any());
    }

    @Test
    void addPatientRequiresProxyConsentAndValidFields() {
        StepVerifier.create(service.addPatient(familyId, new AddPatientCommand("Ma", 74, "female", null, false)))
                .expectErrorMatches(e -> e instanceof BaymaxException b && b.reason().equals("proxy_consent_required"))
                .verify();
        StepVerifier.create(service.addPatient(familyId, new AddPatientCommand("Ma", 200, "female", null, true)))
                .expectErrorMatches(e -> e instanceof BaymaxException b && b.status().value() == 400)
                .verify();
        StepVerifier.create(service.addPatient(familyId, new AddPatientCommand("Ma", 74, "f", null, true)))
                .expectErrorMatches(e -> e instanceof BaymaxException b && b.status().value() == 400)
                .verify();
        verify(records, never()).createPatient(any());
    }

    @Test
    void addPatientChecksTheFreeTierThenInserts() {
        when(records.findFamily(familyId)).thenReturn(Mono.just(family));

        StepVerifier.create(service.addPatient(familyId, new AddPatientCommand(" Ma ", 74, "Female",
                        List.of(" Diabetes", "hypertension", "diabetes", ""), true)))
                .assertNext(p -> {
                    assertThat(p.familyId()).isEqualTo(familyId);
                    assertThat(p.name()).isEqualTo("Ma");
                    assertThat(p.sex()).isEqualTo(PatientProfile.Sex.FEMALE);
                    assertThat(p.chronicFlags()).containsExactly("diabetes", "hypertension");
                    assertThat(p.proxyConsentAt()).isEqualTo(NOW);
                })
                .verifyComplete();

        InOrder order = inOrder(freeTier, records);
        order.verify(freeTier).checkCanAddPatient(family);
        order.verify(records).createPatient(any());
    }

    @Test
    void freeTierBlockSurfacesAs402AndNothingIsInserted() {
        when(records.findFamily(familyId)).thenReturn(Mono.just(family));
        when(freeTier.checkCanAddPatient(family)).thenReturn(Mono.error(
                new FreeTierExceededException(FreeTierExceededException.PATIENTS, "no")));

        StepVerifier.create(service.addPatient(familyId, new AddPatientCommand("Baba", 80, "male", null, true)))
                .expectErrorMatches(e -> e instanceof FreeTierExceededException f && f.reason().equals("free_tier_patients"))
                .verify();
        verify(records, never()).createPatient(any());
    }

    @Test
    void unknownFamilyIs404() {
        when(records.findFamily(familyId)).thenReturn(Mono.empty());

        StepVerifier.create(service.addPatient(familyId, new AddPatientCommand("Ma", 74, "female", null, true)))
                .expectErrorMatches(e -> e instanceof BaymaxException b && b.status().value() == 404)
                .verify();
    }

    @Test
    void secondShareMemberIs409() {
        UUID patientId = UUID.randomUUID();
        PatientProfile patient = new PatientProfile(patientId, familyId, "Ma", 74, PatientProfile.Sex.FEMALE, List.of(), NOW, NOW);
        when(records.findPatient(patientId)).thenReturn(Mono.just(patient));
        when(records.countShareMembers(patientId)).thenReturn(Mono.just(0L), Mono.just(1L));

        StepVerifier.create(service.addShareMember(patientId, "+8801811111111"))
                .assertNext(m -> assertThat(m.whatsappNumber()).isEqualTo("+8801811111111"))
                .verifyComplete();
        StepVerifier.create(service.addShareMember(patientId, "+8801822222222"))
                .expectErrorMatches(e -> e instanceof BaymaxException b && b.status().value() == 409 && b.reason().equals("share_member_exists"))
                .verify();
        verify(records).addShareMember(any());
    }

    @Test
    void deleteFamilyCommitsRowsBeforeTouchingImagesAndReportsCounts() {
        UUID docA = UUID.randomUUID();
        UUID docB = UUID.randomUUID();
        when(records.findFamily(familyId)).thenReturn(Mono.just(family));
        when(records.documentIdsOf(familyId)).thenReturn(Flux.just(docA, docB));
        when(storage.deleteFamily(familyId)).thenReturn(Mono.just(new DeletionReport(familyId + "/", 5, 5)));
        when(records.deleteFamily(eq(familyId), any())).thenReturn(Mono.just(
                DeletionCounts.empty().with("patient_profile", 2L).with("document", 2L)));

        StepVerifier.create(service.deleteFamily(familyId))
                .assertNext(r -> {
                    assertThat(r.deletedAt()).isEqualTo(NOW);
                    assertThat(r.families()).isEqualTo(1);
                    assertThat(r.patients()).isEqualTo(2);
                    assertThat(r.documents()).isEqualTo(2);
                    assertThat(r.objects()).isEqualTo(5);
                })
                .verifyComplete();

        // Rows FIRST, images after. The reverse order destroyed images and then rolled the SQL back on failure,
        // leaving records that claimed images existed which were already gone — silent and unrecoverable. This
        // test previously asserted that dangerous order and so locked the bug in.
        InOrder order = inOrder(storage, records);
        ArgumentCaptor<java.util.Collection<UUID>> ids = ArgumentCaptor.forClass(java.util.Collection.class);
        order.verify(records).deleteFamily(eq(familyId), ids.capture());
        order.verify(storage).deleteFamily(familyId);
        assertThat(ids.getValue()).containsExactlyInAnyOrder(docA, docB);
    }

    @Test
    void deletePatientUsesThePatientPrefix() {
        UUID patientId = UUID.randomUUID();
        PatientProfile patient = new PatientProfile(patientId, familyId, "Ma", 74, PatientProfile.Sex.FEMALE, List.of(), NOW, NOW);
        when(records.findPatient(patientId)).thenReturn(Mono.just(patient));
        when(records.documentIdsOfPatient(patientId)).thenReturn(Flux.empty());
        when(storage.deletePatient(familyId, patientId)).thenReturn(Mono.just(new DeletionReport("p/", 0, 0)));
        when(records.deletePatient(eq(patientId), any())).thenReturn(Mono.just(
                DeletionCounts.empty().with("patient_profile", 1L).with("document", 0L)));

        StepVerifier.create(service.deletePatient(patientId))
                .assertNext(r -> {
                    assertThat(r.families()).isZero();
                    assertThat(r.patients()).isEqualTo(1);
                    assertThat(r.documents()).isZero();
                })
                .verifyComplete();
        verify(storage).deletePatient(familyId, patientId);
    }

    @Test
    void deleteUnknownIs404AndTouchesNothing() {
        when(records.findFamily(familyId)).thenReturn(Mono.empty());

        StepVerifier.create(service.deleteFamily(familyId))
                .expectErrorMatches(e -> e instanceof BaymaxException b && b.status().value() == 404)
                .verify();
        verify(storage, never()).deleteFamily(any());
        verify(records, never()).deleteFamily(any(), any());
    }

    @Test
    void numberNormalisation() {
        assertThat(FamilyAccountService.normalizeNumber("+880 (17) 00-000000")).isEqualTo("+8801700000000");
        assertThat(FamilyAccountService.normalizeNumber("8801700000000")).isEqualTo("+8801700000000");   // accepted since the walkthrough fix
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> FamilyAccountService.normalizeNumber("017"))   // too short to be anything
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> FamilyAccountService.normalizeNumber("+1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Found on the first real walkthrough: a Bangladeshi types 017…, not +8801…; both must mean the same account. */
    @org.junit.jupiter.api.Test
    void localBangladeshiFormatsNormaliseToE164() {
        org.assertj.core.api.Assertions.assertThat(FamilyAccountService.normalizeNumber("01793399171")).isEqualTo("+8801793399171");
        org.assertj.core.api.Assertions.assertThat(FamilyAccountService.normalizeNumber("8801793399171")).isEqualTo("+8801793399171");
        org.assertj.core.api.Assertions.assertThat(FamilyAccountService.normalizeNumber("+880 1793-399171")).isEqualTo("+8801793399171");
        org.assertj.core.api.Assertions.assertThat(FamilyAccountService.normalizeNumber("০১৭৯৩৩৯৯১৭১")).isEqualTo("+8801793399171");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> FamilyAccountService.normalizeNumber("1793399171"))
                .isInstanceOf(IllegalArgumentException.class);   // ten digits, no country: still refused
    }
}
