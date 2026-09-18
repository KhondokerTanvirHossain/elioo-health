package com.elioo.baymax.web.application.port.out;

import com.elioo.baymax.web.domain.AuditEvent;
import com.elioo.baymax.web.domain.FamilyViewCount;
import com.elioo.baymax.web.domain.OtpDailyCount;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

public interface AuditPort {

    Mono<Void> record(AuditEvent event);

    Flux<OtpDailyCount> otpPerNumberPerDay(Instant from, Instant to);

    Flux<FamilyViewCount> viewsPerFamily(Instant from, Instant to);
}
