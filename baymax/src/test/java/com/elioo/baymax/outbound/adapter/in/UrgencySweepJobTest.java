package com.elioo.baymax.outbound.adapter.in;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.elioo.baymax.outbound.application.port.in.DocumentUrgencyUseCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The sweep's one log line, which the morning check greps for: a count on success, the class name on failure. */
class UrgencySweepJobTest {

    private final DocumentUrgencyUseCase urgency = mock(DocumentUrgencyUseCase.class);
    private final UrgencySweepJob job = new UrgencySweepJob(urgency);
    private ListAppender<ILoggingEvent> logged;
    private Logger logger;

    @BeforeEach
    void capture() {
        logger = (Logger) LoggerFactory.getLogger(UrgencySweepJob.class);
        logged = new ListAppender<>();
        logged.start();
        logger.addAppender(logged);
        logger.setLevel(Level.TRACE);
    }

    @AfterEach
    void release() {
        logger.detachAppender(logged);
        logger.setLevel(null);
    }

    @Test
    void aSweepLogsItsTriggerAndHowManyDocumentsItAssessed() {
        when(urgency.sweep()).thenReturn(Mono.just(3L));

        StepVerifier.create(job.sweep("startup")).expectNext(3L).verifyComplete();

        assertThat(lines(Level.INFO)).containsExactly("[baymax] urgency sweep done trigger=startup documents=3");
    }

    /** A failed sweep never escapes into the scheduler, and its line carries the class name, never the message. */
    @Test
    void aFailedSweepIsLoggedByClassNameAndSwallowed() {
        when(urgency.sweep()).thenReturn(Mono.error(new IllegalStateException("Ma Potassium 11.0")));

        StepVerifier.create(job.sweep("hourly")).verifyComplete();

        assertThat(lines(Level.ERROR)).containsExactly("[baymax] urgency sweep failed trigger=hourly error=IllegalStateException");
        assertThat(logged.list).allSatisfy(e -> assertThat(e.getFormattedMessage()).doesNotContain("Potassium").doesNotContain("11.0"));
        assertThat(logged.list).allSatisfy(e -> assertThat(e.getThrowableProxy()).isNull());
    }

    private List<String> lines(Level level) {
        return logged.list.stream().filter(e -> e.getLevel() == level).map(ILoggingEvent::getFormattedMessage).toList();
    }
}
