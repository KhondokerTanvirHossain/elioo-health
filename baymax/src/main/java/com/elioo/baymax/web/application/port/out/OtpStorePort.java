package com.elioo.baymax.web.application.port.out;

import com.elioo.baymax.web.domain.OtpCode;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

public interface OtpStorePort {

    Mono<OtpCode> create(OtpCode code);

    /**
     * Every code made for this number at or after {@code since}, whatever became of it (live, used, burned or
     * expired). This is the hourly cap's count: it bounds codes made and WhatsApp messages sent. Counting only live
     * codes let a burned or expired code drop out, so five wrong guesses bought another send, without limit.
     */
    Mono<Long> countCreatedSince(String phoneHash, Instant since);

    /** The newest live code for this number, if any. */
    Mono<OtpCode> latestLive(String phoneHash, Instant now);

    /**
     * The newest code for this number created at or after {@code since}, whatever its state (live, used, burned or
     * expired), so a refusal can say which.
     */
    Mono<OtpCode> latestSince(String phoneHash, Instant since);

    /** Records a wrong guess; {@code burnedAt} non-null kills the code. */
    Mono<Void> recordAttempt(UUID codeId, int attempts, Instant burnedAt);

    Mono<Void> consume(UUID codeId, Instant at);
}
