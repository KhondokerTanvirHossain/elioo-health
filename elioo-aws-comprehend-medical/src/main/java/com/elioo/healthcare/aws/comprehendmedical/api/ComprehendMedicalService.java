package com.elioo.healthcare.aws.comprehendmedical.api;

import com.elioo.healthcare.aws.comprehendmedical.model.EntityDetectionRequest;
import com.elioo.healthcare.aws.comprehendmedical.model.EntityDetectionResponse;
import com.elioo.healthcare.aws.comprehendmedical.model.MedicalCode;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Medical NLP service contract for entity detection and code inference.
 *
 * <p>This interface defines the contract for medical natural language processing (NLP) services.
 * Implementations provide provider-specific medical text analysis capabilities.</p>
 *
 * <p>All operations are reactive and return {@link Mono} types for non-blocking execution.</p>
 *
 * <p><b>Implementations:</b></p>
 * <ul>
 *   <li>{@code ComprehendMedicalServiceImpl} - AWS Comprehend Medical implementation</li>
 * </ul>
 *
 * @see EntityDetectionRequest
 * @see EntityDetectionResponse
 * @see MedicalCode
 * @since 0.1.0
 */
public interface ComprehendMedicalService {

    /**
     * Detects medical entities in text.
     *
     * <p>Identifies and classifies medical entities including:</p>
     * <ul>
     *   <li>Medications and dosages</li>
     *   <li>Medical conditions and diagnoses</li>
     *   <li>Anatomy and test/treatment procedures</li>
     *   <li>Protected Health Information (PHI)</li>
     * </ul>
     *
     * @param request Entity detection request containing text and options
     * @return Mono emitting entity detection response with classified entities
     */
    Mono<EntityDetectionResponse> detectEntities(EntityDetectionRequest request);

    /**
     * Infers ICD-10-CM diagnosis codes from medical text.
     *
     * <p>Analyzes text to identify potential medical conditions and maps them to
     * standardized ICD-10-CM (International Classification of Diseases) codes.</p>
     *
     * @param text Medical text to analyze
     * @return Mono emitting list of ICD-10-CM codes with confidence scores
     */
    Mono<List<MedicalCode>> inferICD10Codes(String text);

    /**
     * Infers RxNorm medication codes from medication text.
     *
     * <p>Analyzes text to identify medications and maps them to standardized
     * RxNorm codes maintained by the National Library of Medicine.</p>
     *
     * @param text Medication text to analyze
     * @return Mono emitting list of RxNorm codes with confidence scores
     */
    Mono<List<MedicalCode>> inferRxNormCodes(String text);

    /**
     * Infers SNOMED CT codes from medical text.
     *
     * <p>Analyzes text to identify medical concepts and maps them to standardized
     * SNOMED CT (Systematized Nomenclature of Medicine - Clinical Terms) codes.
     * SNOMED CT provides comprehensive clinical terminology coverage including
     * clinical findings, procedures, body structures, organisms, and more.</p>
     *
     * @param text Medical text to analyze
     * @return Mono emitting list of SNOMED CT codes with confidence scores
     */
    Mono<List<MedicalCode>> inferSNOMEDCTCodes(String text);
}
