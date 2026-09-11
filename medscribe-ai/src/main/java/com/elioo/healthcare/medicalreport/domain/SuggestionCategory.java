package com.elioo.healthcare.medicalreport.domain;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

public enum SuggestionCategory {
    IMMEDIATE_ACTION,
    DIAGNOSTIC_TESTS,
    MEDICATION,
    MONITORING,
    LIFESTYLE,
    /** Any category the model produced that we do not model (e.g. PHYSIOTHERAPY, REFERRAL). */
    @JsonEnumDefaultValue
    OTHER
}
