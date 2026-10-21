package com.elioo.baymax.web.adapter.in;

import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.adapter.in.ui.LocalPath;
import org.springframework.http.HttpCookie;
import org.springframework.http.ResponseCookie;
import org.springframework.web.reactive.function.server.ServerRequest;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The two short-lived cookies between the number step and the code step, shared by the HTML sign-in pages and the
 * {@code /app/api/sign-in} JSON so the two can never disagree on a name, a lifetime, a scope or what a value means.
 * The number is never in either: {@code baymax_login} holds its HMAC, its last three digits and, when WhatsApp accepted
 * the code, the {@code .s} mark; {@code baymax_next} holds the page to open after sign-in.
 */
public final class LoginCookie {

    public static final String NAME = "baymax_login";
    /** The login cookie's mark for "WhatsApp accepted the code" (DR-39 amended); absent for every other outcome. */
    public static final String SENT_MARK = "s";
    /**
     * How long the code step remembers which number it is for. 30 minutes, up from BMX-5's 10, for the button path
     * (PO 2026-10-18): the family leaves the browser, opens WhatsApp, sends কোড, waits for the reply and comes back,
     * and only then does the 10-minute code TTL start. The cookie holds the HMAC and the last three digits, nothing a
     * longer life exposes; the code itself still expires on its own TTL.
     */
    public static final Duration TTL = Duration.ofMinutes(30);
    /** The page to open after sign-in ({@code ?next=}), held for the same 30 minutes as the login cookie. */
    public static final String NEXT_NAME = "baymax_next";
    /** Where sign-in lands when no held place passes {@link #nextPage}. */
    public static final String HOME = SessionAuthFilter.COOKIE_PATH + "/home";

    private static final String BASE = SessionAuthFilter.COOKIE_PATH;

    /**
     * {@code <hmac>[.<last three digits>][.s]}. The bare-hash form is still accepted: a cookie from before the tail was
     * added may be in flight for its lifetime after a deploy.
     */
    private static final Pattern VALUE = Pattern.compile("^([0-9a-f]{64})(?:\\.([0-9]{3}))?(?:\\.(" + SENT_MARK + "))?$");

    private LoginCookie() {
    }

    /**
     * A login cookie read back: the number's HMAC, its last three digits (null for the bare-hash form), and whether
     * the code request behind it came back SENT, i.e. WhatsApp accepted the message.
     */
    public record Login(String hash, String tail, boolean sent) {
    }

    /** The request's login cookie, when it has one of the right shape; empty for a missing or tampered one. */
    public static Optional<Login> read(ServerRequest request) {
        HttpCookie c = request.cookies().getFirst(NAME);
        return Optional.ofNullable(c).map(HttpCookie::getValue).map(VALUE::matcher).filter(Matcher::matches)
                .map(m -> new Login(m.group(1), m.group(2), m.group(3) != null));
    }

    /** The login cookie's value for a code request: {@code <hmac>.<last3>}, plus {@code .s} only when {@code sent}. */
    public static String value(String hash, String last3, boolean sent) {
        return hash + "." + last3 + (sent ? "." + SENT_MARK : "");
    }

    /**
     * A page under {@code /app} to open after sign-in: {@link LocalPath}'s rule (no other host, no backslash, no dot
     * segment, raw or decoded), minus {@code /} and the JSON namespace {@code /app/api}, raw or decoded. Empty for
     * anything else, which opens {@link #HOME}.
     */
    public static Optional<String> nextPage(String raw) {
        String safe = LocalPath.or(raw, null);
        if (safe == null || !safe.startsWith(BASE)) {
            return Optional.empty();
        }
        String decoded = URLDecoder.decode(safe, StandardCharsets.UTF_8);
        for (String p : new String[]{safe, decoded}) {
            String path = p.contains("?") ? p.substring(0, p.indexOf('?')) : p;
            if (path.equals(BASE + "/api") || path.startsWith(BASE + "/api/")) {
                return Optional.empty();
            }
        }
        return Optional.of(safe);
    }

    /**
     * One of the sign-in cookies: {@code Path=/app} (the session cookie's own path, so it never reaches MedScribe),
     * HttpOnly, SameSite=Lax. A clearing cookie is the same call with {@link Duration#ZERO}, so it always has the Path
     * of the cookie it clears.
     */
    public static ResponseCookie cookie(String name, String value, Duration maxAge, boolean secure) {
        return ResponseCookie.from(name, value).httpOnly(true).secure(secure).sameSite("Lax").path(BASE).maxAge(maxAge).build();
    }
}
