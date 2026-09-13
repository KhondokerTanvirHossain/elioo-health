package com.elioo.baymax.common.error;

import org.springframework.http.HttpStatus;

/** HTTP 402 with reason {@code free_tier_patients} or {@code free_tier_documents}. */
public class FreeTierExceededException extends BaymaxException {

    public static final String PATIENTS = "free_tier_patients";
    public static final String DOCUMENTS = "free_tier_documents";

    public FreeTierExceededException(String reason, String message) {
        super(HttpStatus.PAYMENT_REQUIRED, reason, message);
    }
}
