package com.elioo.baymax.wa.application.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * When this instance started, as F9a's inbound ledger needs it. Built during context refresh, before Netty serves the
 * webhook, so every message this instance receives is received AFTER {@link #startedAt}; a row received before it was
 * claimed by an earlier instance (the box runs one at a time), and nothing here is handling it. That is what lets the
 * startup close such rows as cut off and a Meta re-delivery reclaim one, without ever taking a message that is in
 * flight here.
 */
@Component
@ConditionalOnProperty(prefix = "baymax.wa", name = "enabled", havingValue = "true")
public class WaInstance {

    private final Instant startedAt;

    public WaInstance(Clock clock) {
        this.startedAt = clock.instant();
    }

    public Instant startedAt() {
        return startedAt;
    }
}
