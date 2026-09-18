package com.elioo.baymax.web.application.port.out;

import com.elioo.baymax.web.domain.OtpCode;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

public interface OtpStorePort {

    Mono<OtpCode> create(OtpCode code);

    /** Codes for this number that are still live and were created at or after {@code since}. */
    Mono<Long> countLive(String phoneHash, Instant since, Instant now);

    /** The newest live code for this number, if any. */
    Mono<OtpCode> latestLive(String phoneHash, Instant now);

    /** Records a wrong guess; {@code burnedAt} non-null kills the code. */
    Mono<Void> recordAttempt(UUID codeId, int attempts, Instant burnedAt);

    Mono<Void> consume(UUID codeId, Instant at);
}
