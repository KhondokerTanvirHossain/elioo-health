package com.elioo.baymax.family.adapter.in.router;

import com.elioo.baymax.aicall.adapter.in.router.AdminAuthFilter;
import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.common.error.ErrorResponseFilter;
import com.elioo.baymax.common.error.FreeTierExceededException;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.family.adapter.in.handler.FamilyHandler;
import com.elioo.baymax.family.application.port.in.FamilyAccountUseCase;
import com.elioo.baymax.healthrecord.domain.DeletionReceipt;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BaymaxFamilyRouterTest {

    private static final String TOKEN = "s3cret";

    private final FamilyAccountUseCase families = mock(FamilyAccountUseCase.class);
    private final WebTestClient client;

    BaymaxFamilyRouterTest() {
        BaymaxProperties props = new BaymaxProperties();
        props.getAdmin().setToken(TOKEN);
        client = WebTestClient.bindToRouterFunction(new BaymaxFamilyRouter()
                        .baymaxFamilyRoutes(new FamilyHandler(families), new AdminAuthFilter(props), new ErrorResponseFilter()))
                .build();
    }

    @Test
    void withoutTheAdminTokenEverythingIs401() {
        client.post().uri("/api/v1/baymax/families").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}").exchange().expectStatus().isUnauthorized();
        verify(families, never()).createFamily(any());
    }

    @Test
    void createFamilyReturns201WithTheId() {
        UUID id = UUID.randomUUID();
        when(families.createFamily(any())).thenReturn(Mono.just(
                new FamilyAccount(id, "+8801700000000", "Rahim", FamilyAccount.Plan.FREE, Instant.now(), Instant.now())));

        client.post().uri("/api/v1/baymax/families").header(AdminAuthFilter.HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"whatsapp_number\":\"+8801700000000\",\"owner_name\":\"Rahim\",\"terms_accepted\":true}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.id").isEqualTo(id.toString());

        ArgumentCaptor<FamilyAccountUseCase.CreateFamilyCommand> cmd = ArgumentCaptor.forClass(FamilyAccountUseCase.CreateFamilyCommand.class);
        verify(families).createFamily(cmd.capture());
        assertThat(cmd.getValue().termsAccepted()).isTrue();
        assertThat(cmd.getValue().whatsappNumber()).isEqualTo("+8801700000000");
    }

    @Test
    void missingTermsAcceptedIsPassedAsFalseAndRenderedAs400() {
        when(families.createFamily(any())).thenReturn(Mono.error(BaymaxException.badRequest("terms_not_accepted", "terms_accepted must be true")));

        client.post().uri("/api/v1/baymax/families").header(AdminAuthFilter.HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"whatsapp_number\":\"+8801700000000\",\"owner_name\":\"Rahim\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.reason").isEqualTo("terms_not_accepted").jsonPath("$.status").isEqualTo(400);

        ArgumentCaptor<FamilyAccountUseCase.CreateFamilyCommand> cmd = ArgumentCaptor.forClass(FamilyAccountUseCase.CreateFamilyCommand.class);
        verify(families).createFamily(cmd.capture());
        assertThat(cmd.getValue().termsAccepted()).isFalse();
    }

    @Test
    void malformedJsonIs400() {
        client.post().uri("/api/v1/baymax/families").header(AdminAuthFilter.HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{not json")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.reason").isEqualTo("invalid_request");
    }

    @Test
    void freeTierIs402WithReason() {
        UUID familyId = UUID.randomUUID();
        when(families.addPatient(eq(familyId), any())).thenReturn(Mono.error(
                new FreeTierExceededException(FreeTierExceededException.PATIENTS, "free plan allows 1 patient profile(s)")));

        client.post().uri("/api/v1/baymax/families/" + familyId + "/patients").header(AdminAuthFilter.HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"Baba\",\"age\":80,\"sex\":\"male\",\"proxy_consent\":true}")
                .exchange()
                .expectStatus().isEqualTo(402)
                .expectBody().jsonPath("$.reason").isEqualTo("free_tier_patients");
    }

    @Test
    void addPatientReturns201() {
        UUID familyId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        when(families.addPatient(eq(familyId), any())).thenReturn(Mono.just(new PatientProfile(patientId, familyId, "Ma", 74,
                PatientProfile.Sex.FEMALE, List.of("diabetes"), Instant.now(), Instant.now())));

        client.post().uri("/api/v1/baymax/families/" + familyId + "/patients").header(AdminAuthFilter.HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"Ma\",\"age\":74,\"sex\":\"female\",\"chronic_flags\":[\"diabetes\"],\"proxy_consent\":true}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.id").isEqualTo(patientId.toString());
    }

    @Test
    void secondShareIs409AndBadUuidIs400() {
        UUID patientId = UUID.randomUUID();
        when(families.addShareMember(eq(patientId), any())).thenReturn(Mono.error(
                BaymaxException.conflict("share_member_exists", "one per patient")));

        client.post().uri("/api/v1/baymax/patients/" + patientId + "/share").header(AdminAuthFilter.HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"whatsapp_number\":\"+8801811111111\"}")
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.reason").isEqualTo("share_member_exists");

        client.post().uri("/api/v1/baymax/patients/not-a-uuid/share").header(AdminAuthFilter.HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"whatsapp_number\":\"+8801811111111\"}")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void deleteFamilyReturnsTheReceipt() {
        UUID familyId = UUID.randomUUID();
        when(families.deleteFamily(familyId)).thenReturn(Mono.just(
                new DeletionReceipt(Instant.parse("2026-09-13T10:00:00Z"), 1, 2, 3, 7)));

        client.delete().uri("/api/v1/baymax/families/" + familyId).header(AdminAuthFilter.HEADER, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.deleted_at").isEqualTo("2026-09-13T10:00:00Z")
                .jsonPath("$.families").isEqualTo(1)
                .jsonPath("$.patients").isEqualTo(2)
                .jsonPath("$.documents").isEqualTo(3)
                .jsonPath("$.objects").isEqualTo(7);
    }

    @Test
    void unknownIs404() {
        UUID patientId = UUID.randomUUID();
        when(families.deletePatient(patientId)).thenReturn(Mono.error(BaymaxException.notFound("patient_not_found", "no")));

        client.delete().uri("/api/v1/baymax/patients/" + patientId).header(AdminAuthFilter.HEADER, TOKEN)
                .exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.reason").isEqualTo("patient_not_found");
    }
}
