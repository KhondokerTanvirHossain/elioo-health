package com.elioo.baymax.web.adapter.out.delivery;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.elioo.baymax.testsupport.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard (replayed on the pre-fix code): the fallback may say a code was not delivered; at INFO or above it
 * may never say what the code was. BMX-5's log adapter wrote "OTP for phoneHash=… code=483920" at INFO.
 */
class LogOtpDeliveryTest {

    private static final String CODE = "483920";
    private ListAppender<ILoggingEvent> logged;
    private Logger logger;

    @BeforeEach
    void capture() {
        logger = (Logger) LoggerFactory.getLogger(LogOtpDelivery.class);
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
    void theFallbackLineAtInfoOrAboveNeverCarriesTheCode() {
        new LogOtpDelivery().deliver("+8801700000000", "b".repeat(64), CODE).block();

        assertThat(LogCapture.events(logged)).as("silence is not 'no leak': the fallback must say something at INFO or above")
                .anySatisfy(e -> assertThat(e.getLevel().isGreaterOrEqual(Level.INFO)).isTrue());
        for (ILoggingEvent e : LogCapture.events(logged)) {
            if (e.getLevel().isGreaterOrEqual(Level.INFO)) {
                // tokens, not a substring: the code must not BE a field, wherever it sits in the line
                assertThat(e.getFormattedMessage().split("[^0-9A-Za-z]+"))
                        .as("%s %s", e.getLevel(), e.getFormattedMessage()).doesNotContain(CODE);
            }
        }
    }
}
