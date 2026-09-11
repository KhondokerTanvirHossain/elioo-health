package com.elioo.healthcare.llm.health.dto;

import java.util.List;
import java.util.Map;

/**
 * Patient demographics and clinical context.
 * Generic record that can be used across multiple health applications.
 *
 * @param age                  Patient age in years
 * @param gender               Patient gender (e.g., "MALE", "FEMALE", "OTHER")
 * @param medicalHistory       List of past medical conditions/diagnoses
 * @param currentMedications   List of current medications
 * @param vitalSigns           Map of vital signs (e.g., "BP" -> "120/80", "HR" -> "72")
 * @param additionalContext    Additional context specific to the application
 */
public record PatientContext(
        Integer age,
        String gender,
        List<String> medicalHistory,
        List<String> currentMedications,
        Map<String, String> vitalSigns,
        Map<String, Object> additionalContext
) {
    /**
     * Create a minimal patient context with just age and gender.
     */
    public static PatientContext minimal(Integer age, String gender) {
        return new PatientContext(age, gender, null, null, null, null);
    }

    /**
     * Create a patient context with age, gender, and medical history.
     */
    public static PatientContext withHistory(
            Integer age,
            String gender,
            List<String> medicalHistory
    ) {
        return new PatientContext(age, gender, medicalHistory, null, null, null);
    }

    /**
     * Check if medical history is present.
     */
    public boolean hasMedicalHistory() {
        return medicalHistory != null && !medicalHistory.isEmpty();
    }

    /**
     * Check if current medications are present.
     */
    public boolean hasCurrentMedications() {
        return currentMedications != null && !currentMedications.isEmpty();
    }

    /**
     * Check if vital signs are present.
     */
    public boolean hasVitalSigns() {
        return vitalSigns != null && !vitalSigns.isEmpty();
    }
}
