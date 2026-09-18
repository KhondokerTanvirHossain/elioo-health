package com.elioo.baymax.web.adapter.in.ui;

import org.springframework.http.HttpCookie;
import org.springframework.http.ResponseCookie;
import org.springframework.web.reactive.function.server.ServerRequest;

import java.time.Duration;

/**
 * The UI language: Bangla by default, English by choice. The choice is a cookie, never a guess from the
 * browser (DR-14: a real toggle, persisted), set by {@code GET /lang/{code}} and read on every page.
 */
public enum Lang {
    BN("bn"), EN("en");

    static final String COOKIE = "baymax_lang";

    public final String code;

    Lang(String code) {
        this.code = code;
    }

    public static Lang of(ServerRequest request) {
        HttpCookie c = request.cookies().getFirst(COOKIE);
        return c != null && EN.code.equals(c.getValue()) ? EN : BN;
    }

    /** The code as typed in a URL; anything unknown is Bangla, so a bad link never breaks a page. */
    public static Lang parse(String code) {
        return EN.code.equalsIgnoreCase(code) ? EN : BN;
    }

    public ResponseCookie cookie(boolean secure) {
        return ResponseCookie.from(COOKIE, code).path("/").maxAge(Duration.ofDays(365)).sameSite("Lax").secure(secure).build();
    }
}
