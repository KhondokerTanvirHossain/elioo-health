package com.elioo.baymax.aicall.domain;

import java.util.Locale;

/** Why an AI call was made. Stored lower-case in {@code ai_call_log.purpose}. */
public enum AiCallPurpose {
    OCR, EXTRACT, EXPLAIN, CHAT, NUDGE, SUMMARY;

    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static AiCallPurpose fromDbValue(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
