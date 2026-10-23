package com.elioo.baymax.wa.application.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.application.port.in.DocumentIntakeUseCase;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.outbound.application.port.in.ExplainDocumentUseCase;
import com.elioo.baymax.outbound.application.service.Copy;
import com.elioo.baymax.testsupport.LogCapture;
import com.elioo.baymax.wa.WaFixtures;
import com.elioo.baymax.wa.application.port.out.WaMessagingPort;
import com.elioo.baymax.web.adapter.out.delivery.CompositeOtpDelivery;
import com.elioo.baymax.web.application.port.out.AuditPort;
import com.elioo.baymax.web.application.port.out.InboundWindowPort;
import com.elioo.baymax.web.application.port.out.OtpStorePort;
import com.elioo.baymax.web.application.port.out.SessionStorePort;
import com.elioo.baymax.web.application.service.PhoneHasher;
import com.elioo.baymax.web.application.service.WebAuthService;
import com.elioo.baymax.web.domain.OtpCode;
import com.elioo.baymax.web.domain.WebSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The phone try, end to end with no network (new feature, not a replay): the family texts "কোড", a real
 * WebAuthService mints for the sender, a real CompositeOtpDelivery sends it back over WhatsApp, the code opens the web
 * session, and no log line at any level carries it.
 */
class WaLoginCodeFlowTest {

    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");
    private static final String NUMBER = "+8801700000000";
    private static final UUID FAMILY = UUID.randomUUID();

    private final OtpStorePort otps = mock(OtpStorePort.class);
    private final SessionStorePort sessions = mock(SessionStorePort.class);
    private final AuditPort audit = mock(AuditPort.class);
    private final HealthRecordPort records = mock(HealthRecordPort.class);
    private final InboundWindowPort window = mock(InboundWindowPort.class);
    private final WaMessagingPort graph = mock(WaMessagingPort.class);
    private final BaymaxProperties props = new BaymaxProperties();
    private final AtomicReference<OtpCode> row = new AtomicReference<>();
    private WebAuthService auth;
    private WaIntakeService intake;
    private ListAppender<ILoggingEvent> logged;
    private Logger root;
    private Level before;

    @BeforeEach
    void wire() {
        props.getAuth().setHmacSecret("test-secret");
        props.getWa().setEnabled(true);
        props.getWa().setAllowlist(List.of(NUMBER));
        when(audit.record(any())).thenReturn(Mono.empty());
        when(otps.countCreatedSince(anyString(), any())).thenReturn(Mono.just(0L));
        when(otps.create(any())).thenAnswer(i -> {
            OtpCode c = i.getArgument(0);
            row.set(new OtpCode(UUID.randomUUID(), c.phoneHash(), c.codeHash(), c.familyId(), c.expiresAt(), 0, null, null, c.createdAt()));
            return Mono.just(row.get());
        });
        when(otps.latestLive(anyString(), any())).thenAnswer(i -> Mono.justOrEmpty(row.get())
                .filter(c -> c.phoneHash().equals(i.getArgument(0)) && c.isLive(i.getArgument(1))));
        when(otps.consume(any(), any())).thenReturn(Mono.empty());
        when(otps.recordAttempt(any(), anyInt(), any())).thenReturn(Mono.empty());
        when(sessions.create(anyString(), any(), any(), any())).thenAnswer(i ->
                Mono.just(new WebSession(UUID.randomUUID(), i.getArgument(1), NOW, NOW, i.getArgument(3))));
        when(records.findFamilyByWhatsapp(NUMBER)).thenReturn(Mono.just(
                new FamilyAccount(FAMILY, NUMBER, "Rahim", FamilyAccount.Plan.FREE, NOW, NOW)));
        when(window.recordInbound(anyString(), any(), any())).thenReturn(Mono.empty());
        when(graph.sendText(anyString(), anyString())).thenReturn(Mono.just(WaFixtures.wamid("8801700000000", 41)));

        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("wa", graph);
        CompositeOtpDelivery delivery = new CompositeOtpDelivery(beans.getBeanProvider(WaMessagingPort.class), new Copy(), props);
        auth = new WebAuthService(otps, sessions, delivery, audit, records, new PhoneHasher(props), props,
                Clock.fixed(NOW, ZoneOffset.UTC), window);
        DocumentRecordPort documents = mock(DocumentRecordPort.class);
        when(documents.latestDoneDocument(any())).thenReturn(Mono.empty());   // the greeting reads the latest paper (PR 7)
        intake = new WaIntakeService(records, documents, mock(ExplainDocumentUseCase.class),
                mock(DocumentIntakeUseCase.class), graph, new Copy(), props, auth,
                Clock.fixed(NOW, ZoneOffset.UTC),
                mock(com.elioo.baymax.family.application.port.in.FreeTierUseCase.class),
                com.elioo.baymax.wa.WaFixtures.noUrgentPapers(), com.elioo.baymax.wa.WaFixtures.nothingStored(),
                mock(com.elioo.baymax.wa.application.port.out.InboundMessagePort.class), new PhoneHasher(props),
                new WaInstance(Clock.fixed(NOW, ZoneOffset.UTC)));

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

    @Test
    void kodOnWhatsappSendsACodeBackThatOpensTheWebSessionAndNoLogLineCarriesIt() {
        assertThat(intake.onText("8801700000000", "কোড").block()).isEqualTo(WaIntakeService.Outcome.LOGIN_CODE);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(graph).sendText(eq(NUMBER), body.capture());
        Matcher m = Pattern.compile("(?<![0-9])([0-9]{6})(?![0-9])").matcher(body.getValue());
        assertThat(m.find()).as("the reply carries a 6-digit code").isTrue();
        String code = m.group(1);

        for (ILoggingEvent e : LogCapture.events(logged)) {
            assertThat(e.getFormattedMessage().split("[^0-9A-Za-z]+")).as("%s %s", e.getLevel(), e.getFormattedMessage())
                    .doesNotContain(code);
        }
        StepVerifier.create(auth.verify(auth.hash("01700000000"), code))
                .assertNext(issued -> assertThat(issued.session().familyId()).isEqualTo(FAMILY)).verifyComplete();
    }
}
