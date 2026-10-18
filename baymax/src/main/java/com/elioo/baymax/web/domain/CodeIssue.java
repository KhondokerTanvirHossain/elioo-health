package com.elioo.baymax.web.domain;

/**
 * What one request for a sign-in code came to (DR-39, amended 2026-10-18). Only {@link #SENT} means a code reached
 * WhatsApp: minted, and accepted by the provider. A page may say "sent" for {@code SENT} and for nothing else.
 */
public enum CodeIssue {
    /** Minted and accepted by WhatsApp. */
    SENT,
    /** Minted, but WhatsApp did not accept the message (closed window, token, allowlist at the adapter, channel off). */
    NOT_DELIVERED,
    /** The number already had the hour's maximum codes made, whatever became of them; nothing minted, nothing sent. */
    RATE_LIMITED,
    /** No account has this number (or it is not a number at all); nothing minted, never an open sender. */
    NO_ACCOUNT,
    /** The phase-1 allowlist does not permit this number; nothing minted. */
    NOT_PERMITTED,
    /** Web only: the number has not written to Medioo inside the service window, so a free send is impossible. */
    OUTSIDE_WINDOW
}
