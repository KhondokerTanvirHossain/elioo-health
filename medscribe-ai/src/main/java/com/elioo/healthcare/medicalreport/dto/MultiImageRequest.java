package com.elioo.healthcare.medicalreport.dto;

import com.elioo.healthcare.medicalreport.domain.MasterProcessingRequest;
import com.elioo.healthcare.medicalreport.domain.WorkflowOptions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request DTO for multi-image medical report processing.
 *
 * <p>Supports processing multiple medical report images (e.g., multi-page reports,
 * multiple test reports from same visit) as a single cohesive analysis.</p>
 *
 * <p>Workflow:</p>
 * <ol>
 *   <li>Validate all images in parallel</li>
 *   <li>OCR all images in parallel</li>
 *   <li>Concatenate raw text from all OCR results</li>
 *   <li>Validate total text length does not exceed AWS Comprehend Medical limit (20,000 chars)</li>
 *   <li>Merge test results using confidence-based deduplication</li>
 *   <li>Run classification on concatenated text</li>
 *   <li>Generate clinical insights on merged results</li>
 * </ol>
 *
 * @see MasterProcessingRequest for single-image processing
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MultiImageRequest {

    /**
     * List of base64-encoded medical report images.
     *
     * <p>Each image should be a valid base64-encoded string (JPEG, PNG, or PDF).</p>
     * <p>Images are processed in parallel - order is not guaranteed in processing,
     * but duplicate test results are merged by highest confidence score.</p>
     *
     * <p>Practical limit: 1-10 images. More images increase risk of exceeding
     * AWS Comprehend Medical's 20,000 character limit for concatenated text.</p>
     */
    @NotEmpty(message = "At least one image is required")
    @Size(min = 1, max = 10, message = "Between 1 and 10 images allowed")
    private List<String> images;

    /**
     * Patient demographic and medical history.
     *
     * <p>Same structure as single-image processing. Used to provide personalized
     * clinical insights and risk assessments based on patient demographics and history.</p>
     */
    @NotNull(message = "Patient context is required")
    @Valid
    private MasterProcessingRequest.PatientContext patientContext;

    /**
     * Workflow configuration options.
     *
     * <p>Optional. If not provided, uses default settings.</p>
     * <p>Same options as single-image processing apply to the aggregated results.</p>
     */
    @Builder.Default
    private WorkflowOptions workflowOptions = new WorkflowOptions();
}
