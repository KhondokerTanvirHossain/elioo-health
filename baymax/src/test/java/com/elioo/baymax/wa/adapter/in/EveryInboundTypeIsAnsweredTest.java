package com.elioo.baymax.wa.adapter.in;

import com.elioo.baymax.common.error.ErrorResponseFilter;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.application.port.in.DocumentIntakeUseCase;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.outbound.application.port.in.ExplainDocumentUseCase;
import com.elioo.baymax.outbound.application.service.Copy;
import com.elioo.baymax.wa.WaFixtures;
import com.elioo.baymax.wa.application.port.out.WaMessagingPort;
import com.elioo.baymax.wa.application.service.WaIntakeService;
import com.elioo.baymax.wa.domain.WaInbound;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Spec §7.8: every Meta inbound type has a non-silent handler, through the real webhook and the real intake.
 * Derived from WaInbound.Type via WaWebhookDispatchTest.ONE_OF_EACH, which itself must cover every Type, so a type
 * added to the enum, or a later PR that reroutes dispatch, cannot leave this green over a silent type.
 */
class EveryInboundTypeIsAnsweredTest {

    private static final String APP_SECRET = "app-secret-under-test";
    private static final String FROM = "8801700000000";
    private static final String E164 = "+8801700000000";
    private final HealthRecordPort records = mock(HealthRecordPort.class);
    private final DocumentRecordPort documents = mock(DocumentRecordPort.class);
    private final ExplainDocumentUseCase explainer = mock(ExplainDocumentUseCase.class);
    private final DocumentIntakeUseCase intake = mock(DocumentIntakeUseCase.class);
    private final List<String> sent = new CopyOnWriteArrayList<>();
    private final WaMessagingPort graph = new WaMessagingPort() {
        @Override
        public Mono<byte[]> downloadMedia(String mediaId) {
            return Mono.just(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0});
        }

        @Override
        public Mono<String> sendText(String to, String body) {
            sent.add(body);
            return Mono.just(WaFixtures.wamid(FROM, 1000 + sent.size()));
        }
    };
    private final BaymaxProperties props = new BaymaxProperties();
    private WebTestClient client;

    @BeforeEach
    void wire() {
        props.getWa().setEnabled(true);
        props.getWa().setVerifyToken("v");
        props.getWa().setAppSecret(APP_SECRET);
        props.getWa().setAllowlist(List.of(E164));
        FamilyAccount family = new FamilyAccount(UUID.randomUUID(), E164, "Rahim", FamilyAccount.Plan.FREE, Instant.EPOCH, Instant.EPOCH);
        when(records.findFamilyByWhatsapp(E164)).thenReturn(Mono.just(family));
        when(records.patientsOf(family.id())).thenReturn(Flux.just(new PatientProfile(UUID.randomUUID(), family.id(), "মা", 70,
                PatientProfile.Sex.FEMALE, List.of(), Instant.EPOCH, Instant.EPOCH)));
        when(documents.latestDoneDocument(any())).thenReturn(Mono.empty());
        when(intake.accept(any(), any())).thenReturn(Mono.just(mock(Document.class)));
        // the constructor as this PR leaves it (plan Global Constraints table); later PRs update this line with the others
        WaIntakeService service = new WaIntakeService(records, documents, explainer, intake, graph, new Copy(), props);
        client = WebTestClient.bindToRouterFunction(new WaWebhookRouter()
                .baymaxWaRoutes(new WaWebhookHandler(props, new ObjectMapper(), service), new ErrorResponseFilter())).build();
    }

    private byte[] callback(String wamid, String fields) {
        return ("{\"object\":\"whatsapp_business_account\",\"entry\":[{\"changes\":[{\"value\":{\"messages\":[{"
                + "\"id\":\"" + wamid + "\",\"from\":\"" + FROM + "\"," + fields + "}]}}]}]}").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void everyMessageTypeAFamilyCanSendIsAnsweredExactlyOnceAndEveryReplyFitsTheCap() throws Exception {
        assertThat(WaWebhookDispatchTest.ONE_OF_EACH.keySet()).as("one fixture per Type")
                .containsExactlyInAnyOrder(WaInbound.Type.values());
        Map<WaInbound.Type, Integer> replies = new EnumMap<>(WaInbound.Type.class);
        Map<WaInbound.Type, Integer> expected = new EnumMap<>(WaInbound.Type.class);
        int n = 0;
        for (var e : WaWebhookDispatchTest.ONE_OF_EACH.entrySet()) {
            sent.clear();
            byte[] body = callback(WaFixtures.wamid(FROM, ++n), e.getValue());
            client.post().uri(WaWebhookRouter.WEBHOOK_PATH).header("X-Hub-Signature-256", WaSignature.sign(body, APP_SECRET))
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isOk();
            int want = e.getKey() == WaInbound.Type.SYSTEM ? 0 : 1;   // Meta's own notice is the only silence
            expected.put(e.getKey(), want);
            if (want == 1) {
                // a silent type times out here quietly; the map assertion below then names every silent type at once
                try {
                    await().atMost(Duration.ofSeconds(3)).until(() -> !sent.isEmpty());
                } catch (org.awaitility.core.ConditionTimeoutException silent) {
                    // recorded as 0 below
                }
            }
            await().pollDelay(Duration.ofMillis(400)).atMost(Duration.ofSeconds(2)).until(() -> true);   // room for a second
            List<String> got = List.copyOf(sent);
            replies.put(e.getKey(), got.size());
            for (String reply : got) {
                assertThat(reply.length()).as(e.getKey() + " reply: " + reply)
                        .isLessThanOrEqualTo(props.getOutbound().getMaxChars());
            }
        }
        assertThat(replies).as("replies per inbound type: every message a family sends gets exactly one reply")
                .isEqualTo(expected);
    }
}
