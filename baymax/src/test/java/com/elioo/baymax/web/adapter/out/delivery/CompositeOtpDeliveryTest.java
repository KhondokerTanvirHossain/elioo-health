package com.elioo.baymax.web.adapter.out.delivery;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.outbound.application.service.Copy;
import com.elioo.baymax.wa.WaFixtures;
import com.elioo.baymax.wa.application.port.out.WaMessagingPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The composite (new code, not a replay): WhatsApp first, free-form, never a template; a fallback that logs no code
 * above DEBUG; and "delivered" emitted only when the provider accepted the message, because the sign-in page says
 * "sent" on that and nothing else (PO 2026-10-18).
 */
class CompositeOtpDeliveryTest {

    private static final String NUMBER = "+8801700000000";
    private static final String HASH = "a".repeat(64);
    private static final String CODE = "483920";
    private static final String WAMID = WaFixtures.wamid("8801700000000", 71);
    private final WaMessagingPort wa = mock(WaMessagingPort.class);
    private final BaymaxProperties props = new BaymaxProperties();
    private ListAppender<ILoggingEvent> logged;
    private Logger root;
    private Level before;

    @BeforeEach
    void capture() {
        props.getWa().setAllowlist(List.of(NUMBER));
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        before = root.getLevel();
        logged = new ListAppender<>();
        logged.start();
        root.addAppender(logged);
        root.setLevel(Level.TRACE);
    }

    @AfterEach
    void release() {
        root.detachAppender(logged);
        root.setLevel(before);
    }

    private CompositeOtpDelivery delivery(WaMessagingPort port) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        if (port != null) {
            beans.registerSingleton("wa", port);
        }
        return new CompositeOtpDelivery(beans.getBeanProvider(WaMessagingPort.class), new Copy(), props);
    }

    private void noLineAtOrAbove(Level floor, String token) {
        for (ILoggingEvent e : logged.list) {
            if (e.getLevel().isGreaterOrEqual(floor)) {
                assertThat(e.getFormattedMessage().split("[^0-9A-Za-z+]+")).as("%s %s", e.getLevel(), e.getFormattedMessage())
                        .doesNotContain(token);
            }
        }
    }

    @Test
    void aSuccessfulWhatsappDeliveryIsReportedDeliveredAndWritesTheCodeAndTheNumberToNoLogAtAnyLevel() {
        when(wa.sendText(eq(NUMBER), anyString())).thenReturn(Mono.just(WAMID));
        StepVerifier.create(delivery(wa).deliver(NUMBER, HASH, CODE)).expectNext(true).verifyComplete();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(wa).sendText(eq(NUMBER), body.capture());
        verifyNoMoreInteractions(wa);   // a free-form session message and nothing else: never a (paid) template
        assertThat(body.getValue()).contains(CODE).contains("এই কোড কাউকে দেবেন না").contains("Medioo কখনো ফোন করে কোড চায় না");
        assertThat(body.getValue().length()).as("the cap, on the text actually sent").isLessThanOrEqualTo(props.getOutbound().getMaxChars());
        assertThat(logged.list).as("the success line exists, or 'nothing leaked' passes on an empty log")
                .anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("otp delivered channel=whatsapp"));
        noLineAtOrAbove(Level.TRACE, CODE);
        noLineAtOrAbove(Level.TRACE, NUMBER);
        noLineAtOrAbove(Level.TRACE, "8801700000000");
        // a wamid is the number in base64: matched as a substring, because the token split above would cut it apart
        assertThat(logged.list.stream().map(ILoggingEvent::getFormattedMessage))
                .noneMatch(m -> m.contains(WAMID.substring("wamid.".length(), "wamid.".length() + 16)));
    }

    @Test
    void aFailedSendIsReportedNotDeliveredFallsBackWithoutTheCodeAtInfoAndKeepsItAtDebugForBreakGlass() {
        when(wa.sendText(anyString(), anyString())).thenReturn(Mono.error(new RuntimeException("(#131047) Re-engagement message")));
        StepVerifier.create(delivery(wa).deliver(NUMBER, HASH, CODE)).expectNext(false).verifyComplete();   // never an error upstream

        noLineAtOrAbove(Level.INFO, CODE);
        assertThat(logged.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("reason=window_expired").contains(HASH);
        });
        assertThat(logged.list).as("the other direction: the operator can still recover it at DEBUG")
                .anySatisfy(e -> {
                    assertThat(e.getLevel()).isEqualTo(Level.DEBUG);
                    assertThat(e.getFormattedMessage().split("[^0-9A-Za-z]+")).contains(CODE);
                });
    }

    /**
     * Graph answering without a message id is not an acceptance. The port can say so two ways — no element, or a
     * blank one — and only the blank one is what the real client emitted (WaGraphClient mapped a missing id to ""):
     * the empty-only version of this test was green over a page that said "sent" for a send WhatsApp never accepted.
     */
    @Test
    void aSendThatAnswersWithABlankMessageIdIsNotDelivered() {
        for (String blank : new String[] {"", "   "}) {
            when(wa.sendText(anyString(), anyString())).thenReturn(Mono.just(blank));
            StepVerifier.create(delivery(wa).deliver(NUMBER, HASH, CODE)).as("id '" + blank + "'").expectNext(false).verifyComplete();
        }
        assertThat(logged.list).anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("reason=no_message_id"));
        assertThat(logged.list).noneSatisfy(e -> assertThat(e.getFormattedMessage()).contains("otp delivered"));
        noLineAtOrAbove(Level.INFO, CODE);
    }

    @Test
    void aSendThatCompletesWithoutAMessageIdIsNotDelivered() {
        when(wa.sendText(anyString(), anyString())).thenReturn(Mono.empty());
        StepVerifier.create(delivery(wa).deliver(NUMBER, HASH, CODE)).expectNext(false).verifyComplete();
        assertThat(logged.list).anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("reason=no_message_id"));
        noLineAtOrAbove(Level.INFO, CODE);
    }

    @Test
    void aSenderOffTheAllowlistIsNeverMessaged() {
        props.getWa().setAllowlist(List.of("+8801700000999"));
        StepVerifier.create(delivery(wa).deliver(NUMBER, HASH, CODE)).expectNext(false).verifyComplete();
        verify(wa, never()).sendText(anyString(), anyString());
        assertThat(logged.list).anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("reason=not_allowlisted"));
        noLineAtOrAbove(Level.INFO, CODE);
    }

    @Test
    void withTheChannelOffTheCodeGoesNowhereAboveDebug() {
        StepVerifier.create(delivery(null).deliver(NUMBER, HASH, CODE)).expectNext(false).verifyComplete();
        assertThat(logged.list).anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("reason=channel_off"));
        noLineAtOrAbove(Level.INFO, CODE);
    }
}
