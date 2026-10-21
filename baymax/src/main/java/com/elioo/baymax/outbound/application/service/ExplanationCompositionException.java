package com.elioo.baymax.outbound.application.service;

/**
 * An explanation that could not be composed safely, with a machine reason an operator can read above DEBUG. The
 * message can name a medicine or a diagnosis (it lists what was elided or missing), so it is logged at DEBUG only; the
 * {@link #reason()} code is the part that goes to ERROR. Without it a DR-16 transcription defect and a model outage both
 * looked like {@code error=IllegalStateException} (PR 9 review).
 *
 * <p>An {@link IllegalStateException}, as these errors always were, so nothing that caught them changes.
 */
public final class ExplanationCompositionException extends IllegalStateException {

    /** The model's text failed the checklist after it had passed (the template itself is wrong). */
    public static final String TEMPLATE_CHECKLIST = "template_checklist";
    /** A stored medicine line carries an ellipsis: abbreviated, not transcribed (DR-16). */
    public static final String MEDICINE_ELIDED = "medicine_elided";
    /** The medicine block in the body does not match the store character for character (DR-16). */
    public static final String MEDICINE_NOT_VERBATIM = "medicine_not_verbatim";
    /** The doctor's diagnosis lines in the body do not match the store (DR-26). */
    public static final String DIAGNOSIS_NOT_VERBATIM = "diagnosis_not_verbatim";
    /** The composed row carries a different urgency from the one assessed (F1). */
    public static final String URGENCY_CHANGED = "urgency_changed";
    /** The code-only fallback failed its own checklist: a template bug. */
    public static final String FALLBACK_CHECKLIST = "fallback_checklist";

    private final String reason;

    public ExplanationCompositionException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }

    /** The reason code of any composition error: the code for this type, {@code other} for anything else (a model outage). */
    static String reasonOf(Throwable e) {
        return e instanceof ExplanationCompositionException c ? c.reason() : "other";
    }
}
