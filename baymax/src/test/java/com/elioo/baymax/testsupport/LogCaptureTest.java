package com.elioo.baymax.testsupport;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

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
        int overlapped = 0;
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
                // bounded: a writer that dies before line 100 fails here, naming its error, instead of hanging CI
                assertThat(writing.await(10, SECONDS)).as("writer started; its failure: %s", writerFailed.get()).isTrue();
                while (writer.isAlive()) {
                    List<ILoggingEvent> seen = LogCapture.events(appender);
                    seen.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
                    assertThat(seen).as("a snapshot never holds a hole").doesNotContainNull();
                    if (seen.size() > 100 && seen.size() < 20_000) {
                        overlapped++;   // taken mid-run: the writer had logged some lines and not yet all
                    }
                }
            } finally {
                // a failed read must not leave the writer logging into the next test's appender
                stop.set(true);
                writer.join();
            }
            assertThat(writerFailed.get()).as("the writer").isNull();
        }
        // the emptiness guard: a writer that finishes before the first read makes every round vacuous
        assertThat(overlapped).as("reads taken while the writer was still logging, across 50 rounds").isPositive();
    }

    /**
     * The lock itself, deterministically. Another thread holds the appender's monitor, the one
     * {@code AppenderBase.doAppend} takes for every line; {@code events} must wait for it. Reading the bare list, or
     * copying it under the list's own monitor (which no writer takes), returns at once: both are the CME race, and the
     * writer-thread test above passed 3 of 3 runs over the second form.
     */
    @Test
    void theCopyWaitsForTheMonitorEveryAppendHolds() throws Exception {
        assertThat(Modifier.isSynchronized(AppenderBase.class.getMethod("doAppend", Object.class).getModifiers()))
                .as("logback's AppenderBase.doAppend is synchronized on the appender: the monitor LogCapture.events takes")
                .isTrue();
        logger.info("one");
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            synchronized (appender) {
                held.countDown();
                try {
                    release.await(30, SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "appender-monitor-holder");
        CountDownLatch returned = new CountDownLatch(1);
        AtomicInteger size = new AtomicInteger(-1);
        Thread reader = new Thread(() -> {
            size.set(LogCapture.events(appender).size());
            returned.countDown();
        }, "log-capture-reader");
        holder.start();
        try {
            assertThat(held.await(10, SECONDS)).as("the holder took the appender's monitor").isTrue();
            reader.start();
            assertThat(returned.await(300, MILLISECONDS))
                    .as("LogCapture.events returned while another thread held the appender's monitor (reader %s): "
                            + "it read without the lock doAppend holds", reader.getState())
                    .isFalse();
            assertThat(reader.getState()).as("the reader waits on the appender's monitor").isEqualTo(Thread.State.BLOCKED);
        } finally {
            release.countDown();
            holder.join();
        }
        assertThat(returned.await(10, SECONDS)).as("the read returns once the monitor is free").isTrue();
        assertThat(size.get()).isEqualTo(1);
        reader.join();
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
