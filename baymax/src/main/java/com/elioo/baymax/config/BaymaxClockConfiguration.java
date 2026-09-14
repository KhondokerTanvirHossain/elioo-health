package com.elioo.baymax.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The two plain collaborators every Baymax service needs: one UTC clock, so tests can pin time and the
 * calendar-month rule is unambiguous, and a JSON mapper. Both back off when the host application already
 * defines one, which it does in the full app; they exist so the module also starts in a sliced context
 * that has not imported Jackson's auto-configuration.
 */
@Configuration
public class BaymaxClockConfiguration {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock baymaxClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper baymaxObjectMapper() {
        return new ObjectMapper();
    }
}
