package com.elioo.baymax.aicall.domain;

import java.util.Locale;

/** Why an AI call was made. Stored lower-case in {@code ai_call_log.purpose}. */
public enum AiCallPurpose {
    OCR, EXTRACT, EXPLAIN, CHAT, NUDGE, SUMMARY,
    /** Re-reading a cut crop to confirm it shows the value, before it may be stored (DR-12). */
    CROP_VERIFY;

    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static AiCallPurpose fromDbValue(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
