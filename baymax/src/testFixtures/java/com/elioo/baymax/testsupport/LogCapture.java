package com.elioo.baymax.testsupport;

import ch.qos.logback.core.read.ListAppender;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The one way a test reads what a {@link ListAppender} captured. {@code appender.list} is a plain ArrayList that
 * {@code AppenderBase.doAppend} fills while holding the APPENDER's monitor, and a reactor, bounded-elastic or netty
 * thread can still be inside {@code doAppend} when the body a test waited for returns: the webhook logs "wa intake
 * done" after its 200 goes out. Streaming the bare list then threw ConcurrentModificationException (main run 39834549827
 * in WaLogHygieneTest, #142's push run 39808633148 in RetakeLinkAcceptanceTest). Locking the list itself, as one test
 * did, takes a monitor no writer holds and protects nothing.
 *
 * <p>Every test that reads an appender's list goes through here; {@code ListAppenderReadsGoThroughLogCaptureTest}
 * fails on any other read in any test source. It lives in baymax's test fixtures so medscribe-ai's tests use the same
 * class rather than a copy.
 */
public final class LogCapture {

    private static final Duration POLL = Duration.ofMillis(20);

    private LogCapture() {
    }

    /**
     * A copy of every event captured so far, taken under the appender's own monitor: no {@code doAppend} is mid-way
     * while it is taken, and a line logged afterwards does not change it. The appender keeps capturing.
     */
    public static <E> List<E> events(ListAppender<E> appender) {
        synchronized (appender) {
            return new ArrayList<>(appender.list);
        }
    }

    /**
     * Waits until a captured event matches {@code line}, then returns {@link #events} as of that moment. For a line
     * logged on another thread after the body returned: a sweep that reads before it lands passes without having seen
     * it. Fails with an AssertionError naming {@code what} after {@code timeout}.
     */
    public static <E> List<E> await(ListAppender<E> appender, String what, Predicate<? super E> line, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            List<E> seen = events(appender);
            if (seen.stream().anyMatch(line)) {
                return seen;
            }
            if (System.nanoTime() - deadline > 0) {
                throw new AssertionError("timed out after " + timeout + " waiting for " + what + " (" + seen.size()
                        + " events captured)");
            }
            Thread.sleep(POLL.toMillis());
        }
    }
}
