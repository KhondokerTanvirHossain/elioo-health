package com.elioo.baymax.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** One UTC clock for every "now" in Baymax, so tests can pin time and the calendar-month rule is unambiguous. */
@Configuration
public class BaymaxClockConfiguration {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock baymaxClock() {
        return Clock.systemUTC();
    }
}
