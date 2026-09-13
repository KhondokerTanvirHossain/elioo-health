package com.elioo.baymax.family.adapter.in.handler;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.family.application.port.in.FamilyAccountUseCase;
import com.elioo.baymax.family.application.port.in.FamilyAccountUseCase.AddPatientCommand;
import com.elioo.baymax.family.application.port.in.FamilyAccountUseCase.CreateFamilyCommand;
import com.elioo.baymax.healthrecord.domain.DeletionReceipt;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** JSON in, JSON out for the family endpoints. Bodies use snake_case as in the ticket. */
@Component
@RequiredArgsConstructor
public class FamilyHandler {

    record CreateFamilyRequest(@JsonProperty("whatsapp_number") String whatsappNumber,
                               @JsonProperty("owner_name") String ownerName,
                               @JsonProperty("terms_accepted") Boolean termsAccepted) {
    }

    record AddPatientRequest(String name, Integer age, String sex,
                             @JsonProperty("chronic_flags") List<String> chronicFlags,
                             @JsonProperty("proxy_consent") Boolean proxyConsent) {
    }

    record ShareRequest(@JsonProperty("whatsapp_number") String whatsappNumber) {
    }

    private final FamilyAccountUseCase families;

    public Mono<ServerResponse> createFamily(ServerRequest request) {
        return request.bodyToMono(CreateFamilyRequest.class)
                .switchIfEmpty(Mono.error(BaymaxException.badRequest("invalid_request", "a JSON body is required")))
                .flatMap(body -> families.createFamily(new CreateFamilyCommand(
                        body.whatsappNumber(), body.ownerName(), Boolean.TRUE.equals(body.termsAccepted()))))
                .flatMap(family -> ServerResponse.status(HttpStatus.CREATED).bodyValue(Map.of("id", family.id())));
    }

    public Mono<ServerResponse> addPatient(ServerRequest request) {
        UUID familyId = uuid(request.pathVariable("id"), "family id");
        return request.bodyToMono(AddPatientRequest.class)
                .switchIfEmpty(Mono.error(BaymaxException.badRequest("invalid_request", "a JSON body is required")))
                .flatMap(body -> families.addPatient(familyId, new AddPatientCommand(
                        body.name(), body.age(), body.sex(), body.chronicFlags(), Boolean.TRUE.equals(body.proxyConsent()))))
                .flatMap(patient -> ServerResponse.status(HttpStatus.CREATED).bodyValue(Map.of("id", patient.id())));
    }

    public Mono<ServerResponse> addShareMember(ServerRequest request) {
        UUID patientId = uuid(request.pathVariable("id"), "patient id");
        return request.bodyToMono(ShareRequest.class)
                .switchIfEmpty(Mono.error(BaymaxException.badRequest("invalid_request", "a JSON body is required")))
                .flatMap(body -> families.addShareMember(patientId, body.whatsappNumber()))
                .flatMap(member -> ServerResponse.status(HttpStatus.CREATED).bodyValue(Map.of("id", member.id())));
    }

    public Mono<ServerResponse> deletePatient(ServerRequest request) {
        return families.deletePatient(uuid(request.pathVariable("id"), "patient id"))
                .flatMap(receipt -> ServerResponse.ok().bodyValue(receipt(receipt)));
    }

    public Mono<ServerResponse> deleteFamily(ServerRequest request) {
        return families.deleteFamily(uuid(request.pathVariable("id"), "family id"))
                .flatMap(receipt -> ServerResponse.ok().bodyValue(receipt(receipt)));
    }

    private static Map<String, Object> receipt(DeletionReceipt r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deleted_at", r.deletedAt().toString());
        body.put("families", r.families());
        body.put("patients", r.patients());
        body.put("documents", r.documents());
        body.put("objects", r.objects());
        return body;
    }

    private static UUID uuid(String raw, String what) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw BaymaxException.badRequest("invalid_request", what + " must be a UUID");
        }
    }
}
