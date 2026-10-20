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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Spec §7.8: every Meta inbound type has a non-silent handler, through the real webhook and the real intake.
 * Derived from WaInbound.Type via WaWebhookDispatchTest.ONE_OF_EACH, which itself must cover every Type, so a type
 * added to the enum, or a later PR that reroutes dispatch, cannot leave this green over a silent type.
 *
 * <p>Three things make the count honest. A reply is recorded when its send is SUBSCRIBED (WaGraphClient.sendText is
 * a lazy Mono: a reply built and never subscribed is silence, and a fake that recorded the call counted it). The
 * count is read only after the intake's own Mono has completed for that message, not after a fixed wait. And the
 * assertion names WHICH reply each type got, so a photo swallowed by the text router (one reply, the fallback) or a
 * PDF refused as a Word file is not "one reply, fine".
 */
class EveryInboundTypeIsAnsweredTest {

    private static final String APP_SECRET = "app-secret-under-test";
    private static final String FROM = "8801700000000";
    private static final String E164 = "+8801700000000";
    /** The readable direction of WaIntakeService.readable, end to end: ONE_OF_EACH's DOCUMENT is the unreadable .docx. */
    private static final String PDF = "\"type\":\"document\",\"document\":{\"id\":\"M-PDF\",\"mime_type\":\"application/pdf\","
            + "\"filename\":\"lab.pdf\"}";
    private final HealthRecordPort records = mock(HealthRecordPort.class);
    private final DocumentRecordPort documents = mock(DocumentRecordPort.class);
    private final ExplainDocumentUseCase explainer = mock(ExplainDocumentUseCase.class);
    private final DocumentIntakeUseCase intake = mock(DocumentIntakeUseCase.class);
    private final List<String> sent = new CopyOnWriteArrayList<>();
    private final AtomicInteger finished = new AtomicInteger();
    private final WaMessagingPort graph = new WaMessagingPort() {
        @Override
        public Mono<byte[]> downloadMedia(String mediaId) {
            return Mono.just(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0});
        }

        @Override
        public Mono<String> sendText(String to, String body) {
            // lazy, like WaGraphClient: nothing is sent, and nothing recorded, until someone subscribes
            return Mono.fromCallable(() -> {
                sent.add(body);
                return WaFixtures.wamid(FROM, 1000 + sent.size());
            });
        }
    };
    private final BaymaxProperties props = new BaymaxProperties();
    private final Copy copy = new Copy();
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
        // the constructor as this PR leaves it (plan Global Constraints table); later PRs update this line with the others.
        // The real service, observed: finished counts intake Monos that have completed, so the test waits on the work itself.
        com.elioo.baymax.web.application.port.in.WebAuthUseCase auth = mock(com.elioo.baymax.web.application.port.in.WebAuthUseCase.class);
        when(auth.noteInbound(anyString(), any())).thenReturn(Mono.empty());
        com.elioo.baymax.outbound.application.port.out.OutboundMessagePort messages =
                mock(com.elioo.baymax.outbound.application.port.out.OutboundMessagePort.class);
        when(messages.latestOfKind(any(), any())).thenReturn(Mono.empty());
        com.elioo.baymax.family.application.port.in.FreeTierUseCase freeTier =
                mock(com.elioo.baymax.family.application.port.in.FreeTierUseCase.class);
        when(freeTier.checkCanUploadDocument(any(FamilyAccount.class))).thenReturn(Mono.empty());
        WaIntakeService service = new WaIntakeService(records, documents, explainer, intake, graph, copy, props, auth,
                messages, java.time.Clock.systemUTC(), freeTier) {
            @Override
            public Mono<Outcome> onInbound(WaInbound in) {
                return super.onInbound(in).doFinally(signal -> finished.incrementAndGet());
            }
        };
        client = WebTestClient.bindToRouterFunction(new WaWebhookRouter()
                .baymaxWaRoutes(new WaWebhookHandler(props, new ObjectMapper(), service), new ErrorResponseFilter())).build();
    }

    private byte[] callback(String wamid, String fields) {
        return ("{\"object\":\"whatsapp_business_account\",\"entry\":[{\"changes\":[{\"value\":{\"messages\":[{"
                + "\"id\":\"" + wamid + "\",\"from\":\"" + FROM + "\"," + fields + "}]}}]}]}").getBytes(StandardCharsets.UTF_8);
    }

    private String bn(String key) {
        return copy.bn(key, Map.of());
    }

    private String withRedFlag(String key) {
        return bn(key) + "\n" + bn("safety.red_flag_line");
    }

    /** The reply each fixture must get, by name; SYSTEM (Meta's own notice) is the only silence. */
    private Map<String, List<String>> expectedReplies() {
        Map<String, String> byName = new LinkedHashMap<>();
        byName.put("TEXT", "wa.greeting");
        byName.put("IMAGE", "wa.received");
        byName.put("DOCUMENT", "wa.unsupported_file");
        byName.put("DOCUMENT_PDF", "wa.received.file");
        byName.put("AUDIO", "wa.voice+red_flag");
        byName.put("VIDEO", "wa.video+red_flag");
        byName.put("STICKER", "wa.thanks+red_flag");
        byName.put("REACTION", "wa.thanks+red_flag");
        byName.put("INTERACTIVE", "wa.no_document");
        byName.put("BUTTON", "wa.no_document");
        byName.put("LOCATION", "wa.fallback+red_flag");
        byName.put("CONTACTS", "wa.fallback+red_flag");
        byName.put("OTHER", "wa.fallback+red_flag");
        Map<String, List<String>> expected = new LinkedHashMap<>();
        byName.forEach((k, v) -> expected.put(k, List.of(v)));
        expected.put("SYSTEM", List.of());
        return expected;
    }

    /** A sent body back to the copy key it was built from, so a red names the reply, not 170 characters of Bangla. */
    private String keyOf(String body) {
        Map<String, String> known = new LinkedHashMap<>();
        known.put(String.join("\n", bn("wa.greeting.hello"), bn("wa.greeting.intro"), bn("wa.greeting.none")), "wa.greeting");
        for (String k : List.of("wa.received", "wa.received.file", "wa.unsupported_file", "wa.no_document", "wa.unknown")) {
            known.put(bn(k), k);
        }
        for (String k : List.of("wa.voice", "wa.video", "wa.thanks", "wa.fallback")) {
            known.put(withRedFlag(k), k + "+red_flag");
        }
        return known.getOrDefault(body, "<unexpected: " + body.length() + " chars>");
    }

    @Test
    void everyMessageTypeAFamilyCanSendGetsItsOneReplyAndEveryReplyFitsTheCap() throws Exception {
        assertThat(WaWebhookDispatchTest.ONE_OF_EACH.keySet()).as("one fixture per Type")
                .containsExactlyInAnyOrder(WaInbound.Type.values());
        Map<String, String> fixtures = new LinkedHashMap<>();
        WaWebhookDispatchTest.ONE_OF_EACH.forEach((type, fields) -> fixtures.put(type.name(), fields));
        fixtures.put("DOCUMENT_PDF", PDF);

        Map<String, List<String>> replies = new LinkedHashMap<>();
        Map<String, Integer> filed = new LinkedHashMap<>();
        int n = 0;
        for (var e : fixtures.entrySet()) {
            sent.clear();
            clearInvocations(intake);
            int before = finished.get();
            byte[] body = callback(WaFixtures.wamid(FROM, ++n), e.getValue());
            client.post().uri(WaWebhookRouter.WEBHOOK_PATH).header("X-Hub-Signature-256", WaSignature.sign(body, APP_SECRET))
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isOk();
            // the intake runs detached on boundedElastic: wait until its Mono for THIS message has completed
            await().atMost(Duration.ofSeconds(5)).until(() -> finished.get() > before);
            List<String> got = List.copyOf(sent);
            replies.put(e.getKey(), got.stream().map(this::keyOf).toList());
            for (String reply : got) {
                assertThat(reply.length()).as(e.getKey() + " reply: " + reply)
                        .isLessThanOrEqualTo(props.getOutbound().getMaxChars());
            }
            filed.put(e.getKey(), org.mockito.Mockito.mockingDetails(intake).getInvocations().size());
        }
        assertThat(replies).as("the reply each inbound type got: exactly one, and the right one ([] = silence, two = a double send)")
                .isEqualTo(expectedReplies());
        assertThat(filed).as("a photo and a PDF are filed; nothing else reaches the document intake")
                .allSatisfy((name, accepted) -> assertThat(accepted).as(name)
                        .isEqualTo(name.equals("IMAGE") || name.equals("DOCUMENT_PDF") ? 1 : 0));
    }
}
