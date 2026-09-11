package com.elioo.healthcare.medicalreport.dto;

import com.elioo.healthcare.medicalreport.domain.Gender;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SuggestionsRequest {

    @NotBlank(message = "Report ID is required")
    private String reportId;

    private PatientContext patientContext;

    @Builder.Default
    private Boolean includeActionPlan = true;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PatientContext {
        private Integer age;
        private Gender gender;
        private List<String> medicalHistory;
    }
}
