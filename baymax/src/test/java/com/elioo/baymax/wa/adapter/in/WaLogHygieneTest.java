package com.elioo.baymax.wa.adapter.in;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.elioo.baymax.common.error.ErrorResponseFilter;
import com.elioo.baymax.common.redact.Redact;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.outbound.domain.OutboundMessage;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.wa.WaFixtures;
import com.elioo.baymax.wa.adapter.out.WhatsAppMessageDelivery;
import com.elioo.baymax.wa.application.port.out.WaMessagingPort;
import com.elioo.baymax.wa.application.service.WaIntakeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A wamid is base64 of the phone number, so a log line that prints one prints the number. Every WhatsApp log site
 * that handles a provider id is driven here with a realistic id, and no line at any level may carry it or its digits.
 */
class WaLogHygieneTest {

    private static final String FROM = "8801700000000";
    private static final String E164 = "+8801700000000";
    private static final String IN = WaFixtures.wamid(FROM, 1);
    private static final String OUT = WaFixtures.wamid(FROM, 2);
    private static final String APP_SECRET = "app-secret-under-test";
    private ListAppender<ILoggingEvent> logged;
    private Logger root;
    private Level before;

    @BeforeEach
    void capture() {
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

    private String everything() {
        return logged.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
    }

    private void assertNoWamidAndNoNumber(String log) {
        for (String id : List.of(IN, OUT)) {
            assertThat(log).as("a wamid in the log is the phone number in the log").doesNotContain(id)
                    .doesNotContain(id.substring("wamid.".length(), "wamid.".length() + 16));
        }
        assertThat(log).doesNotContain(FROM).doesNotContain(WaFixtures.decodedDigits(IN));
    }

    @Test
    void theWebhookLogsInboundAndStatusCallbacksByDigestOnly() throws Exception {
        WaIntakeService intake = mock(WaIntakeService.class);
        when(intake.claim(any())).thenAnswer(i -> Mono.just(i.getArgument(0)));
        when(intake.handle(any())).thenReturn(Mono.just(WaIntakeService.Outcome.ACCEPTED));
        BaymaxProperties props = new BaymaxProperties();
        props.getWa().setEnabled(true);
        props.getWa().setVerifyToken("v");
        props.getWa().setAppSecret(APP_SECRET);
        WebTestClient client = WebTestClient.bindToRouterFunction(new WaWebhookRouter()
                .baymaxWaRoutes(new WaWebhookHandler(props, new ObjectMapper(), intake), new ErrorResponseFilter())).build();
        byte[] body = ("{\"object\":\"whatsapp_business_account\",\"entry\":[{\"changes\":[{\"value\":{"
                + "\"messages\":[{\"id\":\"" + IN + "\",\"from\":\"" + FROM + "\",\"type\":\"text\",\"text\":{\"body\":\"Hi\"}}],"
                + "\"statuses\":[{\"id\":\"" + OUT + "\",\"status\":\"delivered\",\"recipient_id\":\"" + FROM + "\"}]"
                + "}}]}]}").getBytes(StandardCharsets.UTF_8);

        client.post().uri(WaWebhookRouter.WEBHOOK_PATH).header("X-Hub-Signature-256", WaSignature.sign(body, APP_SECRET))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isOk();

        String log = everything();
        assertNoWamidAndNoNumber(log);
        assertThat(log).as("the lines exist, carrying the digest").contains("ref=" + Redact.id(IN)).contains("ref=" + Redact.id(OUT));
    }

    @Test
    void aDeliveredMessageIsLoggedByRowIdAndDigestOnly() {
        WaMessagingPort graph = mock(WaMessagingPort.class);
        when(graph.sendText(anyString(), anyString())).thenReturn(Mono.just(OUT));
        HealthRecordPort records = mock(HealthRecordPort.class);
        UUID family = UUID.randomUUID();
        when(records.findFamily(any())).thenReturn(Mono.just(
                new FamilyAccount(family, E164, "Rahim", FamilyAccount.Plan.FREE, Instant.EPOCH, Instant.EPOCH)));
        BaymaxProperties props = new BaymaxProperties();
        props.getWa().setAllowlist(List.of(E164));
        OutboundMessage row = new OutboundMessage(UUID.randomUUID(), family, null, UUID.randomUUID(),
                OutboundMessage.Kind.EXPLANATION, Urgency.ROUTINE, List.of(), "লেখা", OutboundMessage.GateStatus.RELEASED,
                null, null, null, null, Instant.EPOCH);

        new WhatsAppMessageDelivery(graph, records, props).deliver(row).block();

        String log = everything();
        assertNoWamidAndNoNumber(log);
        assertThat(log).contains("wa delivered id=" + row.id()).contains("ref=" + Redact.id(OUT));
    }

    /** F9a's two new webhook lines: a re-delivery ignored, and a claim that failed into a 503. Digests only. */
    @Test
    void aDuplicateAndAFailedClaimAreLoggedByDigestOnly() throws Exception {
        byte[] body = ("{\"entry\":[{\"changes\":[{\"value\":{\"messages\":[{\"id\":\"" + IN + "\",\"from\":\"" + FROM
                + "\",\"type\":\"text\",\"text\":{\"body\":\"Hi\"}}]}}]}]}").getBytes(StandardCharsets.UTF_8);
        WaIntakeService duplicate = mock(WaIntakeService.class);
        when(duplicate.claim(any())).thenReturn(Mono.empty());
        webhook(duplicate).post().uri(WaWebhookRouter.WEBHOOK_PATH).header("X-Hub-Signature-256", WaSignature.sign(body, APP_SECRET))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isOk();
        WaIntakeService down = mock(WaIntakeService.class);
        when(down.claim(any())).thenReturn(Mono.error(new IllegalStateException("insert failed for " + IN + " from " + FROM)));
        webhook(down).post().uri(WaWebhookRouter.WEBHOOK_PATH).header("X-Hub-Signature-256", WaSignature.sign(body, APP_SECRET))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isEqualTo(503);

        String log = everything();
        assertThat(log).as("the lines exist").contains("wa inbound duplicate ref=" + Redact.id(IN)).contains("asking Meta to retry");
        assertThat(log).as("an exception message is never logged").doesNotContain("insert failed");
        assertNoWamidAndNoNumber(log);
    }

    private WebTestClient webhook(WaIntakeService intake) {
        BaymaxProperties props = new BaymaxProperties();
        props.getWa().setEnabled(true);
        props.getWa().setVerifyToken("v");
        props.getWa().setAppSecret(APP_SECRET);
        return WebTestClient.bindToRouterFunction(new WaWebhookRouter()
                .baymaxWaRoutes(new WaWebhookHandler(props, new ObjectMapper(), intake), new ErrorResponseFilter())).build();
    }

    /**
     * A validly signed body that is not JSON. The parse-error line printed e.toString(), and Jackson's message
     * quotes the token it choked on: whatever the family wrote, in the log at ERROR.
     */
    @Test
    void aSignedCallbackThatIsNotJsonIsLoggedWithoutItsText() throws Exception {
        byte[] body = ("{\"entry\":[{\"changes\":[{\"value\":{\"messages\":[{\"from\":\"" + FROM
                + "\",\"text\":{\"body\": NapaExtend665mg}}]}}]}]}").getBytes(StandardCharsets.UTF_8);

        webhook(mock(WaIntakeService.class)).post().uri(WaWebhookRouter.WEBHOOK_PATH)
                .header("X-Hub-Signature-256", WaSignature.sign(body, APP_SECRET))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();

        String log = everything();
        assertThat(log).as("the line exists").contains("did not parse as JSON");
        assertThat(log).as("the body is the family's text: never in the log").doesNotContain("NapaExtend665mg");
        assertNoWamidAndNoNumber(log);
    }

    /** The delivery-failed line printed e.toString(); a provider error can name the recipient. Class name only. */
    @Test
    void aFailedDeliveryIsLoggedByCodeAndClassOnly() {
        WaMessagingPort graph = mock(WaMessagingPort.class);
        when(graph.sendText(anyString(), anyString())).thenReturn(Mono.error(new IllegalStateException(
                "(#131026) Message undeliverable to " + E164 + " for " + IN)));
        HealthRecordPort records = mock(HealthRecordPort.class);
        UUID family = UUID.randomUUID();
        when(records.findFamily(any())).thenReturn(Mono.just(
                new FamilyAccount(family, E164, "Rahim", FamilyAccount.Plan.FREE, Instant.EPOCH, Instant.EPOCH)));
        BaymaxProperties props = new BaymaxProperties();
        props.getWa().setAllowlist(List.of(E164));
        OutboundMessage row = new OutboundMessage(UUID.randomUUID(), family, null, UUID.randomUUID(),
                OutboundMessage.Kind.EXPLANATION, Urgency.ROUTINE, List.of(), "লেখা", OutboundMessage.GateStatus.RELEASED,
                null, null, null, null, Instant.EPOCH);

        new WhatsAppMessageDelivery(graph, records, props).deliver(row).block();

        String log = everything();
        assertThat(log).as("the line exists").contains("wa delivery failed id=" + row.id());
        assertThat(log).as("provider prose is never logged").doesNotContain("undeliverable");
        assertNoWamidAndNoNumber(log);
    }
}
