package com.elioo.healthcare.medicalreport.dto;

import com.elioo.healthcare.medicalreport.domain.TestResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClassificationRequest {

    @NotBlank(message = "Report ID is required")
    private String reportId;

    @NotEmpty(message = "Extracted data is required")
    private List<TestResult> extractedData;
}
