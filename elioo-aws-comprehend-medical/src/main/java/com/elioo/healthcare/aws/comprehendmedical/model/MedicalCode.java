package com.elioo.healthcare.aws.comprehendmedical.model;

/**
 * Medical code from a standardized coding system.
 *
 * @param code        Code value (e.g., "E11.9" for ICD-10-CM)
 * @param description Description of the code
 * @param codeSystem  Coding system (ICD-10-CM, RxNorm, SNOMED-CT, LOINC, etc.)
 * @param score       Confidence score (0.0 - 1.0)
 */
public record MedicalCode(
        String code,
        String description,
        String codeSystem,
        Double score
) {
    public boolean isICD10() {
        return "ICD-10-CM".equalsIgnoreCase(codeSystem);
    }

    public boolean isRxNorm() {
        return "RxNorm".equalsIgnoreCase(codeSystem);
    }

    public boolean isSNOMEDCT() {
        return "SNOMED-CT".equalsIgnoreCase(codeSystem);
    }

    public boolean hasHighConfidence(double threshold) {
        return score != null && score >= threshold;
    }
}
