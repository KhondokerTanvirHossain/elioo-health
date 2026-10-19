package com.elioo.baymax.web.domain;

import java.util.Objects;

/**
 * F7 for Phase 2: whether the reader's own phone is Bangladeshi. That alone decides whether a tel:999 action may
 * appear. Every time on these pages is the patient's calendar ({@code PatientCalendar.ZONE}, Dhaka); a reader abroad sees "(ঢাকার সময়)".
 * Zones per country are D29 (Phase 5).
 */
public record ViewerLocale(boolean bangladesh) {

    public static ViewerLocale of(String e164) {
        Objects.requireNonNull(e164, "family_account.whatsapp_number is NOT NULL");
        return new ViewerLocale(e164.startsWith("+880"));
    }
}
