package com.elioo.healthcare.medicalreport.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for free-text clinical insight generation.
 *
 * <p>This DTO accepts any free-form medical text (conversations, notes, symptoms, clinical observations)
 * and generates structured clinical insights without requiring structured test data or patient context.</p>
 *
 * <p><b>Use Cases:</b></p>
 * <ul>
 *   <li>Analyzing patient symptom descriptions</li>
 *   <li>Summarizing doctor's clinical notes</li>
 *   <li>Extracting insights from medical conversations</li>
 *   <li>Processing unstructured medical reports</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FreeTextInsightRequest {

    /**
     * Free-form medical text to analyze.
     * Can be any medical-related content: symptoms, notes, conversations, observations.
     * User is responsible for ensuring the content is medical-related.
     */
    @NotBlank(message = "Free text content is required")
    @Size(min = 10, max = 50000, message = "Text must be between 10 and 50,000 characters")
    private String text;

    /**
     * Language of the text (e.g., "en", "bn", "es").
     * Currently only used for metadata; analysis is performed in English.
     */
    @Builder.Default
    private String language = "en";

    /**
     * Target audience for the generated insights.
     *
     * <ul>
     *   <li><b>PATIENT</b>: Use clear, simple language appropriate for patients</li>
     *   <li><b>PROVIDER</b>: Use medical terminology appropriate for healthcare providers</li>
     * </ul>
     */
    @Builder.Default
    private String targetAudience = "PATIENT";

    /**
     * Whether to include risk assessment in the response.
     * If true, generates risk scores across organ systems (cardiovascular, metabolic, renal, etc.).
     */
    @Builder.Default
    private Boolean includeRiskAssessment = true;

    /**
     * Whether to include clinical recommendations in the response.
     * If true, generates evidence-based recommendations with priority levels.
     */
    @Builder.Default
    private Boolean includeRecommendations = true;
}
