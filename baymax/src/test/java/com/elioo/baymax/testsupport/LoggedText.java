package com.elioo.baymax.testsupport;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Everything an appender would write for an event: the formatted message AND every message in the attached throwable's
 * cause and suppressed chain. A leak check that reads only {@code getFormattedMessage()} passes while
 * {@code log.error("...", e)} prints the exception's message at ERROR.
 */
public final class LoggedText {

    private LoggedText() {
    }

    /** The text of every event at INFO or above, message and throwable chain together. */
    public static List<String> atInfoOrAbove(List<ILoggingEvent> events) {
        return events.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.INFO)).map(LoggedText::of).toList();
    }

    public static String of(ILoggingEvent event) {
        List<String> out = new ArrayList<>();
        out.add(event.getFormattedMessage());
        collect(event.getThrowableProxy(), out, 0);
        return out.stream().collect(Collectors.joining(" | "));
    }

    private static void collect(IThrowableProxy t, List<String> out, int depth) {
        if (t == null || depth > 16) {
            return;
        }
        out.add(t.getClassName() + ": " + t.getMessage());
        collect(t.getCause(), out, depth + 1);
        for (IThrowableProxy s : t.getSuppressed()) {
            collect(s, out, depth + 1);
        }
    }
}
