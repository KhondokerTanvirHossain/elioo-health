package com.elioo.healthcare.core.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Configuration for reactive schedulers used in background processing.
 *
 * <p>This configuration provides a custom bounded elastic scheduler specifically
 * for medical report processing workflows that may run for extended periods (up to 10 minutes).
 *
 * <p>The scheduler is designed to handle CPU-intensive and I/O-intensive operations
 * without blocking the main Netty event loop threads used by Spring WebFlux.
 *
 * @author MedScribe AI Team
 * @since 1.0.0
 */
@Configuration
@Slf4j
public class SchedulerConfig {

    /**
     * Creates a custom bounded elastic scheduler for medical report background processing.
     *
     * <p>This scheduler is used to move long-running medical report processing workflows
     * off the main HTTP request thread, allowing the API to return immediately while
     * processing continues in the background.
     *
     * <p><b>Configuration Parameters:</b>
     * <ul>
     *   <li><b>poolSize</b>: Number of worker threads (default: 20)
     *       <ul>
     *         <li>Each thread can handle one report at a time</li>
     *         <li>Sized to handle concurrent report processing</li>
     *         <li>Lower than default boundedElastic to prevent resource exhaustion</li>
     *       </ul>
     *   </li>
     *   <li><b>queueCapacity</b>: Maximum queued tasks (default: 100)
     *       <ul>
     *         <li>Prevents memory exhaustion during traffic spikes</li>
     *         <li>Tasks beyond capacity will be rejected</li>
     *       </ul>
     *   </li>
     *   <li><b>ttlSeconds</b>: Thread time-to-live (default: 600 = 10 minutes)
     *       <ul>
     *         <li>Matches the workflow timeout duration</li>
     *         <li>Prevents thread leaks from long-running operations</li>
     *       </ul>
     *   </li>
     * </ul>
     *
     * <p><b>Thread Naming:</b> All threads created by this scheduler will be named
     * with the prefix "medical-report-worker-" for easy identification in logs and monitoring.
     *
     * <p><b>Daemon Threads:</b> All threads are daemon threads, meaning they will not
     * prevent JVM shutdown if the application is terminated.
     *
     * @param poolSize the number of worker threads in the pool
     * @param queueCapacity the maximum number of tasks that can be queued
     * @param ttlSeconds the time-to-live for idle threads in seconds
     * @return a configured {@link Scheduler} for medical report processing
     */
    @Bean(name = "medicalReportScheduler", destroyMethod = "dispose")
    public Scheduler medicalReportScheduler(
            @Value("${medical-report.processing.thread-pool-size:20}") int poolSize,
            @Value("${medical-report.processing.queue-capacity:100}") int queueCapacity,
            @Value("${medical-report.processing.thread-ttl-seconds:600}") int ttlSeconds) {

        log.info("Initializing medical report scheduler with poolSize={}, queueCapacity={}, ttlSeconds={}",
                poolSize, queueCapacity, ttlSeconds);

        Scheduler scheduler = Schedulers.newBoundedElastic(
                poolSize,                      // Thread pool size
                queueCapacity,                 // Max queued tasks
                "medical-report-worker",       // Thread name prefix
                ttlSeconds,                    // Thread TTL in seconds
                true                           // Daemon threads
        );

        log.info("Medical report scheduler initialized successfully");

        return scheduler;
    }
}
