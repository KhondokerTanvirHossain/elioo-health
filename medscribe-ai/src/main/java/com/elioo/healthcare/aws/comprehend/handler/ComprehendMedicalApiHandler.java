package com.elioo.healthcare.aws.comprehend.handler;

import com.elioo.healthcare.aws.comprehend.dto.DetectEntitiesApiRequest;
import com.elioo.healthcare.aws.comprehend.dto.InferCodesRequest;
import com.elioo.healthcare.aws.comprehendmedical.api.ComprehendMedicalService;
import com.elioo.healthcare.aws.comprehendmedical.model.EntityDetectionRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Web handler for AWS Comprehend Medical API endpoints.
 *
 * <p>Exposes direct access to AWS Comprehend Medical NLP services via REST endpoints:
 * <ul>
 *   <li>POST /api/aws/comprehend-medical/detect-entities - Detect medical entities</li>
 *   <li>POST /api/aws/comprehend-medical/infer-icd10 - Map conditions to ICD-10 codes</li>
 *   <li>POST /api/aws/comprehend-medical/infer-rxnorm - Map medications to RxNorm codes</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ComprehendMedicalApiHandler {

    private final ComprehendMedicalService comprehendMedicalService;

    /**
     * Detect medical entities from text.
     *
     * @param request ServerRequest containing DetectEntitiesApiRequest
     * @return ServerResponse with detected entities
     */
    public Mono<ServerResponse> detectEntities(ServerRequest request) {
        return request.bodyToMono(DetectEntitiesApiRequest.class)
                .doOnNext(req -> log.info("Detecting entities from text. Length: {} chars, Detect PHI: {}",
                        req.text().length(), req.detectPhi() != null && req.detectPhi()))
                .flatMap(req -> {
                    // Build entity detection request
                    EntityDetectionRequest detectionRequest = new EntityDetectionRequest(
                            req.text(),
                            req.detectPhi() != null ? req.detectPhi() : false,
                            true
                    );

                    return comprehendMedicalService.detectEntities(detectionRequest);
                })
                .flatMap(response -> {
                    log.info("Entity detection completed. Entities found: {}", response.getEntityCount());
                    return ServerResponse.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(response);
                })
                .onErrorResume(this::handleError);
    }

    /**
     * Infer ICD-10 codes from medical text.
     *
     * @param request ServerRequest containing InferCodesRequest
     * @return ServerResponse with ICD-10 codes
     */
    public Mono<ServerResponse> inferICD10Codes(ServerRequest request) {
        return request.bodyToMono(InferCodesRequest.class)
                .doOnNext(req -> log.info("Inferring ICD-10 codes from text. Length: {} chars", req.text().length()))
                .flatMap(req -> comprehendMedicalService.inferICD10Codes(req.text()))
                .flatMap(codes -> {
                    log.info("ICD-10 inference completed. Codes found: {}", codes.size());
                    return ServerResponse.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(Map.of("icd10Codes", codes));
                })
                .onErrorResume(this::handleError);
    }

    /**
     * Infer RxNorm codes from medical text.
     *
     * @param request ServerRequest containing InferCodesRequest
     * @return ServerResponse with RxNorm codes
     */
    public Mono<ServerResponse> inferRxNormCodes(ServerRequest request) {
        return request.bodyToMono(InferCodesRequest.class)
                .doOnNext(req -> log.info("Inferring RxNorm codes from text. Length: {} chars", req.text().length()))
                .flatMap(req -> comprehendMedicalService.inferRxNormCodes(req.text()))
                .flatMap(codes -> {
                    log.info("RxNorm inference completed. Codes found: {}", codes.size());
                    return ServerResponse.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(Map.of("rxNormCodes", codes));
                })
                .onErrorResume(this::handleError);
    }

    /**
     * Handle errors and return appropriate error response.
     *
     * @param error The exception that occurred
     * @return ServerResponse with error details
     */
    private Mono<ServerResponse> handleError(Throwable error) {
        log.error("Comprehend Medical API error: {}", error.getMessage(), error);

        return ServerResponse
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "error", error.getClass().getSimpleName(),
                        "message", error.getMessage() != null ? error.getMessage() : "Unknown error",
                        "service", "comprehend-medical"
                ));
    }
}
