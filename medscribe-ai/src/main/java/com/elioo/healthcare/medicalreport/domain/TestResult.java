package com.elioo.healthcare.medicalreport.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestResult {
    private String testName;
    private String testValue;
    private String unit;
    private String referenceRange;
    private TestStatus status;
    private Double confidence; // OCR confidence score (0.0 to 1.0)
}
