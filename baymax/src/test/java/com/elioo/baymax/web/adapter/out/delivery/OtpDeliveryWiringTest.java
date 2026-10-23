package com.elioo.baymax.web.adapter.out.delivery;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.outbound.application.service.Copy;
import com.elioo.baymax.testsupport.LogCapture;
import com.elioo.baymax.wa.WaFixtures;
import com.elioo.baymax.wa.application.port.out.WaMessagingPort;
import com.elioo.baymax.web.application.port.out.OtpDeliveryPort;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression guard (replayed on the pre-fix code), against whatever OtpDeliveryPort the delivery package wires: given a
 * reachable WhatsApp, the code goes there and to no log line at any level.
 */
class OtpDeliveryWiringTest {

    static final String NUMBER = "+8801700000000";

    /**
     * Deliberately NOT {@code @Configuration}: BaymaxAutoConfiguration component-scans com.elioo.baymax, test classes
     * included, and a stereotype here would put a second BaymaxProperties into every other context. @Bean methods
     * work in lite mode; @ComponentScan is honoured on a registered user configuration.
     */
    @ComponentScan("com.elioo.baymax.web.adapter.out.delivery")
    static class Slice {
        @Bean
        BaymaxProperties baymaxProperties() {
            BaymaxProperties p = new BaymaxProperties();
            p.getWa().setAllowlist(List.of(NUMBER));
            return p;
        }

        @Bean
        Copy copy() {
            return new Copy();
        }

        @Bean
        WaMessagingPort wa() {
            WaMessagingPort wa = mock(WaMessagingPort.class);
            when(wa.sendText(anyString(), anyString())).thenReturn(Mono.just(WaFixtures.wamid("8801700000000")));
            return wa;
        }
    }

    @Test
    void theWiredDeliverySendsOverWhatsappAndLogsTheCodeNowhere() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Level before = root.getLevel();
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        root.addAppender(logged);
        root.setLevel(Level.TRACE);
        try {
            new ApplicationContextRunner().withUserConfiguration(Slice.class).run(ctx -> {
                assertThat(ctx).hasSingleBean(OtpDeliveryPort.class);
                ctx.getBean(OtpDeliveryPort.class).deliver(NUMBER, "a".repeat(64), "483920").block();
                // the log assertion comes FIRST, so a replay on the pre-fix code fails on the leak, not on "wanted but not invoked"
                for (ILoggingEvent e : LogCapture.events(logged)) {
                    assertThat(e.getFormattedMessage().split("[^0-9A-Za-z]+"))
                            .as("%s %s", e.getLevel(), e.getFormattedMessage()).doesNotContain("483920");
                }
                verify(ctx.getBean(WaMessagingPort.class)).sendText(eq(NUMBER), contains("483920"));
            });
        } finally {
            root.detachAppender(logged);
            root.setLevel(before);
        }
    }
}
