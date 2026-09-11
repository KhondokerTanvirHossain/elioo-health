package com.elioo.healthcare.medicalreport.domain;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

public enum Severity {
    LOW,
    MODERATE,
    HIGH,
    CRITICAL,
    /** Value the model produced that we do not model. */
    @JsonEnumDefaultValue
    UNKNOWN
}
