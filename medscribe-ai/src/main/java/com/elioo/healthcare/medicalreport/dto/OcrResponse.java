package com.elioo.healthcare.medicalreport.dto;

import com.elioo.healthcare.medicalreport.domain.TestResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OcrResponse {
    private String reportId;
    private String patientId;
    private List<TestResult> extractedData;
    private String rawText;
    private Double confidence;
    private LocalDateTime processedAt;
}
