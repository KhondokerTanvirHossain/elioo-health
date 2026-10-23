package com.elioo.baymax.testsupport;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The one way a test reads a {@link ListAppender}. A reactor or bounded-elastic thread can still be inside
 * {@code doAppend} when the body a test waited for returns (the webhook's "wa intake done" line is logged after the 200
 * goes out), and streaming the bare {@code list} then threw ConcurrentModificationException: main run 39834549827 in
 * WaLogHygieneTest, #142's push run 39808633148 in RetakeLinkAcceptanceTest.
 */
class LogCaptureTest {

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attach() {
        logger = (Logger) LoggerFactory.getLogger(LogCaptureTest.class.getName() + ".writer");
        logger.setAdditive(false);   // twenty thousand lines a round stay out of the console
        logger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
        logger.setAdditive(true);
        logger.setLevel(null);
    }

    /**
     * Read, the way the log tests read it (stream the events, join their messages), while another thread is still
     * logging into the same appender through a real logger. The bare-list read threw on most rounds of this loop; the
     * copy under the appender's lock cannot throw, and never holds a hole (an unlocked copy of a growing ArrayList can).
     */
    @Test
    void readingWhileAnotherThreadStillLogsNeverThrows() throws Exception {
        for (int round = 0; round < 50; round++) {
            logger.detachAppender(appender);
            appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            CountDownLatch writing = new CountDownLatch(1);
            AtomicBoolean stop = new AtomicBoolean();
            AtomicReference<Throwable> writerFailed = new AtomicReference<>();
            Thread writer = new Thread(() -> {
                try {
                    for (int i = 0; i < 20_000 && !stop.get(); i++) {
                        logger.info("line {}", i);
                        if (i == 100) {
                            writing.countDown();
                        }
                    }
                } catch (Throwable t) {
                    writerFailed.set(t);
                }
            }, "log-capture-writer");
            writer.start();
            try {
                writing.await();
                while (writer.isAlive()) {
                    List<ILoggingEvent> seen = LogCapture.events(appender);
                    seen.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
                    assertThat(seen).as("a snapshot never holds a hole").doesNotContainNull();
                }
            } finally {
                // a failed read must not leave the writer logging into the next test's appender
                stop.set(true);
                writer.join();
            }
            assertThat(writerFailed.get()).as("the writer").isNull();
        }
    }

    /** The other direction: the copy is what was logged, in order, and a line logged after it does not move it. */
    @Test
    void theCopyHoldsWhatWasLoggedAndStaysPut() {
        logger.info("one");
        logger.info("two");

        List<ILoggingEvent> first = LogCapture.events(appender);
        logger.info("three");

        assertThat(first).extracting(ILoggingEvent::getFormattedMessage).containsExactly("one", "two");
        assertThat(LogCapture.events(appender)).extracting(ILoggingEvent::getFormattedMessage).containsExactly("one", "two", "three");
    }

    /** A line logged on another thread after the body returned is waited for, and then read with everything before it. */
    @Test
    void awaitReturnsOnceTheLateLineHasLanded() throws Exception {
        logger.info("early");
        Thread late = new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            logger.info("intake done ref={}", "abc");
        });
        late.start();

        List<ILoggingEvent> seen = LogCapture.await(appender, "the intake-done line",
                e -> e.getFormattedMessage().startsWith("intake done"), Duration.ofSeconds(10));

        assertThat(seen).extracting(ILoggingEvent::getFormattedMessage).containsExactly("early", "intake done ref=abc");
        late.join();
    }

    /** A line that never comes fails, and the failure names what was awaited. */
    @Test
    void awaitFailsNamingTheLineThatNeverCame() {
        logger.info("early");

        assertThatThrownBy(() -> LogCapture.await(appender, "the intake-done line",
                e -> e.getFormattedMessage().startsWith("intake done"), Duration.ofMillis(300)))
                .isInstanceOf(AssertionError.class).hasMessageContaining("the intake-done line");
    }
}
