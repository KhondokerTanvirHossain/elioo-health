package com.elioo.baymax.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on Spring scheduling for the whole module, so no one feature owns it: the jobs are {@code NudgeScheduler} and
 * {@code UrgencySweepJob} (F1's hourly past-due sweep). Kept unconditional and outside {@code nudge}: if it lived in a
 * feature's configuration and that feature were switched off, every other {@code @Scheduled} job would stop silently.
 */
@Configuration
@EnableScheduling
public class BaymaxSchedulingConfiguration {
}
