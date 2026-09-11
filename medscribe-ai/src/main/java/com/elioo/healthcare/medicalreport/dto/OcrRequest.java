package com.elioo.healthcare.medicalreport.dto;

import com.elioo.healthcare.medicalreport.domain.ReportType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OcrRequest {

    @NotBlank(message = "Image base64 is required")
    private String imageBase64;

    @NotBlank(message = "Patient ID is required")
    private String patientId;

    private ReportType reportType;
}
