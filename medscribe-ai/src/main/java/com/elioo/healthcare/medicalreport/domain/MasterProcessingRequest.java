package com.elioo.healthcare.medicalreport.domain;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Request DTO for master orchestration API.
 * Contains image data, patient context, and workflow configuration.
 *
 * <p>This is the entry point for the complete medical report processing workflow.</p>
 * <p>The orchestration service will process this request through 10 stages:
 * validation, OCR, entity detection, medical codes inference, and clinical insights generation.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MasterProcessingRequest {

    @NotBlank(message = "Image data is required")
    private String imageBase64;

    @NotNull(message = "Patient context is required")
    @Valid
    private PatientContext patientContext;

    @Builder.Default
    private WorkflowOptions workflowOptions = new WorkflowOptions();

    /**
     * Patient demographic and medical history.
     * Used to provide personalized clinical insights and risk assessments.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PatientContext {

        @NotBlank(message = "Patient ID is required")
        private String patientId;

        @NotNull(message = "Age is required")
        @Min(value = 0, message = "Age must be positive")
        @Max(value = 150, message = "Age must be realistic")
        private Integer age;

        @NotBlank(message = "Gender is required")
        @Pattern(regexp = "MALE|FEMALE|OTHER", message = "Gender must be MALE, FEMALE, or OTHER")
        private String gender;

        private List<String> medicalHistory;
        private List<String> currentMedications;
        private List<String> allergies;
        private Map<String, Object> vitalSigns;
        private Map<String, Object> lifestyle;
    }
}
