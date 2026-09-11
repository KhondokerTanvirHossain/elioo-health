package com.elioo.healthcare.medicalreport.domain;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

public enum Priority {
    LOW,
    MEDIUM,
    HIGH,
    URGENT,  // Added for backward compatibility with existing database records
    /** Value the model produced that we do not model. */
    @JsonEnumDefaultValue
    UNKNOWN
}
