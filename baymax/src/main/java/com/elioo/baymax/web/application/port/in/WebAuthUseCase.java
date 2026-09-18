package com.elioo.baymax.web.application.port.in;

import com.elioo.baymax.web.domain.IssuedSession;
import com.elioo.baymax.web.domain.WebSession;
import reactor.core.publisher.Mono;

public interface WebAuthUseCase {

    /**
     * Issues a code for the number and returns its HMAC. Completes the same way whether or not the number
     * has an account, whether or not it is rate-limited, whether or not it even parses: the caller can never
     * learn anything from this call except the hash it may hold on to for the verify step.
     */
    Mono<String> requestCode(String rawWhatsappNumber);

    /** Verifies a code against the number's HMAC and opens a session. Any failure is the same 401. */
    Mono<IssuedSession> verify(String phoneHash, String code);

    /** Convenience for the API, which is given the number rather than the hash. */
    Mono<IssuedSession> verifyNumber(String rawWhatsappNumber, String code);

    /** The live session behind a cookie token, touched; empty when there is none. */
    Mono<WebSession> authenticate(String token);

    Mono<Void> logout(String token);

    /** HMAC of a normalised number; the one place the mapping lives. */
    String hash(String rawWhatsappNumber);
}
