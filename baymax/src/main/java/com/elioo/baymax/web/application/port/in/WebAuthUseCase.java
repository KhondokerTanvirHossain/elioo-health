package com.elioo.baymax.web.application.port.in;

import com.elioo.baymax.web.domain.CodeIssue;
import com.elioo.baymax.web.domain.CodeRequest;
import com.elioo.baymax.web.domain.IssuedSession;
import com.elioo.baymax.web.domain.WebSession;
import reactor.core.publisher.Mono;

import java.time.Instant;

public interface WebAuthUseCase {

    /**
     * The web form's request (DR-39, amended 2026-10-18: sign-in is free). A code is minted and sent to WhatsApp only
     * for a number that has an account, is permitted by the allowlist, and wrote to Medioo inside the service window
     * ({@code baymax.wa.service-window}); for every other number nothing is minted and nothing is sent. The result
     * says {@link CodeIssue#SENT} only when WhatsApp accepted the message, and the page may say "sent" for nothing
     * else. The API answers the same 200 whatever the result.
     */
    Mono<CodeRequest> requestCode(String rawWhatsappNumber);

    /**
     * A code for a number the channel itself has verified — the WhatsApp sender id of a message that asked for one.
     * Never for a typed number: that is {@link #requestCode}, which checks the window first.
     */
    Mono<CodeIssue> issueCodeFor(String verifiedSenderNumber);

    /**
     * The verified sender wrote to Medioo on WhatsApp, which opens the window in which a code may be sent free.
     * Recorded under the number's HMAC, and only for a number that has an account.
     *
     * @param sentAt when the family sent it (Meta's {@code timestamp}), because WhatsApp's window runs from then and
     *               a retried callback can arrive hours later; null (no timestamp) or later than now counts as now
     */
    Mono<Void> noteInbound(String verifiedSenderNumber, Instant sentAt);

    /**
     * Verifies a code against the number's HMAC and opens a session. Every failure is a
     * {@link CodeRejectedException}: the same 401 for the API, with the reason for the web page.
     */
    Mono<IssuedSession> verify(String phoneHash, String code);

    /** Convenience for the API, which is given the number rather than the hash. */
    Mono<IssuedSession> verifyNumber(String rawWhatsappNumber, String code);

    /** The live session behind a cookie token, touched; empty when there is none. */
    Mono<WebSession> authenticate(String token);

    Mono<Void> logout(String token);

    /** HMAC of a normalised number; the one place the mapping lives. */
    String hash(String rawWhatsappNumber);
}
