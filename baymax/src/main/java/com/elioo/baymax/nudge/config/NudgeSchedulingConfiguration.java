package com.elioo.baymax.nudge.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on Spring scheduling for the module; the one job is {@code NudgeScheduler}. */
@Configuration
@EnableScheduling
public class NudgeSchedulingConfiguration {
}
