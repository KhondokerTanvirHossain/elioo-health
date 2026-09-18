package com.elioo.baymax.web.domain;

/** A freshly created session and the one-time clear token that goes into the cookie. */
public record IssuedSession(String token, WebSession session) {
}
