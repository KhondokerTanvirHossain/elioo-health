package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.family.application.service.FamilyAccountService;

import java.util.Optional;

/** The sign-in page's view of a typed number: the one normalisation, and how it is shown back. Never logged. */
final class PhoneDisplay {

    private PhoneDisplay() {
    }

    /** E.164 for anything {@link FamilyAccountService#normalizeNumber} accepts; empty for anything it refuses. */
    static Optional<String> normalise(String raw) {
        try {
            return Optional.of(FamilyAccountService.normalizeNumber(raw));
        } catch (IllegalArgumentException notANumber) {
            return Optional.empty();
        }
    }

    /** {@code +880 1700-000000} for a Bangladeshi mobile; any other E.164 number as it is. */
    static String full(String e164) {
        if (e164.matches("\\+8801\\d{9}")) {
            return "+880 " + e164.substring(4, 8) + "-" + e164.substring(8);
        }
        return e164;
    }

    /** The last three digits, the only part of the number the login cookie keeps (for the masked display). */
    static String last3(String e164) {
        return e164.substring(e164.length() - 3);
    }
}
