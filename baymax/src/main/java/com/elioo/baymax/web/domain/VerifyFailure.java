package com.elioo.baymax.web.domain;

/**
 * Why a code was refused. The web page says which (so the family knows what to do next); the API answers every one
 * with the same 401, because there a difference would be an oracle.
 */
public enum VerifyFailure {
    /** Not six digits after folding. */
    FORMAT,
    /** A live code exists and this is not it (or the right code for a number with no account). */
    WRONG,
    /** No code for this number in the last hour, or the newest one was already used. */
    NO_CODE,
    /** The newest code is past its time. */
    EXPIRED,
    /** Too many wrong guesses killed the newest code. */
    BURNED
}
