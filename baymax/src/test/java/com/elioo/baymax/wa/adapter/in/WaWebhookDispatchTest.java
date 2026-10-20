package com.elioo.baymax.wa.adapter.in;

import com.elioo.baymax.common.error.ErrorResponseFilter;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.wa.WaFixtures;
import com.elioo.baymax.wa.application.service.WaIntakeService;
import com.elioo.baymax.wa.domain.WaInbound;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Spec §0: voice, stickers and button taps were dropped at WaWebhookHandler.dispatch with a log line, so the family
 * heard nothing. Every message type Meta sends now reaches the intake, which decides the reply. One fixture per
 * WaInbound.Type, so a type added to the enum without a fixture fails here.
 */
class WaWebhookDispatchTest {

    private static final String APP_SECRET = "app-secret-under-test";
    private static final String FROM = "8801700000000";
    private static final String WAMID = WaFixtures.wamid(FROM);
    private final WaIntakeService intake = mock(WaIntakeService.class);

    /** Package-private: EveryInboundTypeIsAnsweredTest drives the real webhook with the same fixtures. */
    static final Map<WaInbound.Type, String> ONE_OF_EACH = Map.ofEntries(
            entry(WaInbound.Type.TEXT, "\"type\":\"text\",\"text\":{\"body\":\"Hi\"}"),
            entry(WaInbound.Type.IMAGE, "\"type\":\"image\",\"image\":{\"id\":\"M-IMG\",\"mime_type\":\"image/jpeg\"}"),
            entry(WaInbound.Type.DOCUMENT, "\"type\":\"document\",\"document\":{\"id\":\"M-DOC\",\"mime_type\":"
                    + "\"application/vnd.openxmlformats-officedocument.wordprocessingml.document\",\"filename\":\"r.docx\"}"),
            entry(WaInbound.Type.AUDIO, "\"type\":\"audio\",\"audio\":{\"id\":\"M-AUD\",\"mime_type\":\"audio/ogg; codecs=opus\",\"voice\":true}"),
            entry(WaInbound.Type.VIDEO, "\"type\":\"video\",\"video\":{\"id\":\"M-VID\",\"mime_type\":\"video/mp4\"}"),
            entry(WaInbound.Type.STICKER, "\"type\":\"sticker\",\"sticker\":{\"id\":\"M-STK\",\"mime_type\":\"image/webp\",\"animated\":false}"),
            entry(WaInbound.Type.REACTION, "\"type\":\"reaction\",\"reaction\":{\"message_id\":\"wamid.OUT\",\"emoji\":\"👍\"}"),
            entry(WaInbound.Type.INTERACTIVE, "\"type\":\"interactive\",\"interactive\":{\"type\":\"button_reply\","
                    + "\"button_reply\":{\"id\":\"detail\",\"title\":\"বিস্তারিত\"}}"),
            entry(WaInbound.Type.BUTTON, "\"type\":\"button\",\"button\":{\"payload\":\"DETAIL\",\"text\":\"বিস্তারিত\"}"),
            entry(WaInbound.Type.LOCATION, "\"type\":\"location\",\"location\":{\"latitude\":23.8,\"longitude\":90.4}"),
            entry(WaInbound.Type.CONTACTS, "\"type\":\"contacts\",\"contacts\":[{\"name\":{\"formatted_name\":\"X\"}}]"),
            entry(WaInbound.Type.SYSTEM, "\"type\":\"system\",\"system\":{\"type\":\"customer_changed_number\"}"),
            entry(WaInbound.Type.OTHER, "\"type\":\"unsupported\",\"errors\":[{\"code\":131051}]"));

    private WaInbound deliver(String fields) throws Exception {
        reset(intake);
        // every message is fresh here; what is claimed is what is handled (F9a)
        when(intake.claim(any())).thenAnswer(i -> Mono.just(i.getArgument(0)));
        when(intake.handle(any())).thenReturn(Mono.just(WaIntakeService.Outcome.ACCEPTED));
        BaymaxProperties props = new BaymaxProperties();
        props.getWa().setEnabled(true);
        props.getWa().setVerifyToken("v");
        props.getWa().setAppSecret(APP_SECRET);
        WebTestClient client = WebTestClient.bindToRouterFunction(new WaWebhookRouter()
                .baymaxWaRoutes(new WaWebhookHandler(props, new ObjectMapper(), intake), new ErrorResponseFilter())).build();
        byte[] body = ("{\"object\":\"whatsapp_business_account\",\"entry\":[{\"changes\":[{\"value\":{\"messages\":[{"
                + "\"id\":\"" + WAMID + "\",\"from\":\"" + FROM + "\",\"timestamp\":\"1760700000\"," + fields
                + "}]}}]}]}").getBytes(StandardCharsets.UTF_8);
        client.post().uri(WaWebhookRouter.WEBHOOK_PATH).header("X-Hub-Signature-256", WaSignature.sign(body, APP_SECRET))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isOk();
        ArgumentCaptor<WaInbound> in = ArgumentCaptor.forClass(WaInbound.class);
        // dispatch is detached on boundedElastic; wait for it rather than racing it
        verify(intake, timeout(2000)).handle(in.capture());
        return in.getValue();
    }

    @Test
    void everyMessageTypeMetaSendsReachesTheIntakeInsteadOfBeingDropped() throws Exception {
        assertThat(ONE_OF_EACH.keySet()).as("one fixture per Type").containsExactlyInAnyOrder(WaInbound.Type.values());
        for (var e : ONE_OF_EACH.entrySet()) {
            WaInbound in = deliver(e.getValue());
            assertThat(in.type()).as(e.getKey().name()).isEqualTo(e.getKey());
            assertThat(in.from()).isEqualTo(FROM);
            assertThat(in.providerMessageId()).as("F9a needs the provider id").isEqualTo(WAMID);
            assertThat(in.timestamp()).as("PR 8 stores the provider time").isEqualTo(Instant.ofEpochSecond(1760700000L));
        }
    }

    @Test
    void theWordsOfAButtonTapAndTheMimeOfADocumentAreCarried() throws Exception {
        assertThat(deliver(ONE_OF_EACH.get(WaInbound.Type.INTERACTIVE)).text()).isEqualTo("বিস্তারিত");
        assertThat(deliver(ONE_OF_EACH.get(WaInbound.Type.BUTTON)).text()).isEqualTo("বিস্তারিত");
        assertThat(deliver(ONE_OF_EACH.get(WaInbound.Type.REACTION)).text()).isEqualTo("👍");
        WaInbound docx = deliver(ONE_OF_EACH.get(WaInbound.Type.DOCUMENT));
        assertThat(docx.mediaId()).isEqualTo("M-DOC");
        assertThat(docx.mimeType()).startsWith("application/vnd.openxmlformats");
    }

    @Test
    void anyTypeMetaAddsLaterIsOtherAndStillReachesTheIntake() {
        assertThat(WaInbound.Type.of("order")).isEqualTo(WaInbound.Type.OTHER);
        assertThat(WaInbound.Type.of("something_new_from_meta")).isEqualTo(WaInbound.Type.OTHER);
        assertThat(WaInbound.Type.of("unknown")).isEqualTo(WaInbound.Type.OTHER);
        assertThat(WaInbound.Type.of(null)).isEqualTo(WaInbound.Type.OTHER);
    }
}
