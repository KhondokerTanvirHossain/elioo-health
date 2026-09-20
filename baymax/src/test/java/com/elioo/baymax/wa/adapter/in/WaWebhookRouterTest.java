package com.elioo.baymax.wa.adapter.in;

import com.elioo.baymax.common.error.ErrorResponseFilter;
import com.elioo.baymax.config.BaymaxProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BMX-10 acceptance for the webhook itself: the handshake succeeds only with the right verify token, and a
 * callback that is unsigned or wrongly signed is refused with nothing processed.
 */
class WaWebhookRouterTest {

    private static final String VERIFY_TOKEN = "verify-token-under-test";
    private static final String APP_SECRET = "app-secret-under-test";
    private static final String BODY = """
            {"object":"whatsapp_business_account","entry":[{"changes":[{"value":{"messages":[
              {"id":"wamid.TEST","type":"image","from":"8801793399171"}]}}]}]}""";

    private WebTestClient client() {
        BaymaxProperties props = new BaymaxProperties();
        props.getWa().setEnabled(true);
        props.getWa().setVerifyToken(VERIFY_TOKEN);
        props.getWa().setAppSecret(APP_SECRET);
        WaWebhookHandler handler = new WaWebhookHandler(props, new ObjectMapper());
        return WebTestClient.bindToRouterFunction(
                new WaWebhookRouter().baymaxWaRoutes(handler, new ErrorResponseFilter())).build();
    }

    @Test
    void theHandshakeEchoesTheChallengeWithTheRightVerifyToken() {
        client().get().uri(b -> b.path(WaWebhookRouter.WEBHOOK_PATH)
                        .queryParam("hub.mode", "subscribe")
                        .queryParam("hub.verify_token", VERIFY_TOKEN)
                        .queryParam("hub.challenge", "1158201444").build())
                .exchange().expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_PLAIN)
                .expectBody(String.class).isEqualTo("1158201444");
    }

    @Test
    void theHandshakeIsRefusedWithTheWrongVerifyTokenAndNeverEchoesTheChallenge() {
        client().get().uri(b -> b.path(WaWebhookRouter.WEBHOOK_PATH)
                        .queryParam("hub.mode", "subscribe")
                        .queryParam("hub.verify_token", "not-the-token")
                        .queryParam("hub.challenge", "1158201444").build())
                .exchange().expectStatus().isForbidden()
                .expectBody(String.class).value(b -> assertThat(b).doesNotContain("1158201444"));
    }

    @Test
    void aSignedCallbackIsAccepted() throws Exception {
        byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
        client().post().uri(WaWebhookRouter.WEBHOOK_PATH)
                .header("X-Hub-Signature-256", WaSignature.sign(body, APP_SECRET))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isOk();
    }

    @Test
    void anUnsignedCallbackIsRefused() {
        client().post().uri(WaWebhookRouter.WEBHOOK_PATH)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(BODY.getBytes(StandardCharsets.UTF_8))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void aCallbackSignedWithTheWrongSecretIsRefused() throws Exception {
        byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
        client().post().uri(WaWebhookRouter.WEBHOOK_PATH)
                .header("X-Hub-Signature-256", WaSignature.sign(body, "someone-elses-secret"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isForbidden();
    }

    /** A valid signature over a different body must not carry a swapped payload through. */
    @Test
    void aValidSignatureOverADifferentBodyIsRefused() throws Exception {
        String header = WaSignature.sign("{\"object\":\"other\"}".getBytes(StandardCharsets.UTF_8), APP_SECRET);
        client().post().uri(WaWebhookRouter.WEBHOOK_PATH)
                .header("X-Hub-Signature-256", header)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(BODY.getBytes(StandardCharsets.UTF_8))
                .exchange().expectStatus().isForbidden();
    }

    /** A phone number never reaches a log line in clear — BMX-5 keeps it in family_account alone. */
    @Test
    void numbersAreMaskedToTheLastFourDigits() {
        assertThat(WaWebhookHandler.maskedNumber("8801793399171")).isEqualTo("****9171");
        assertThat(WaWebhookHandler.maskedNumber("123")).isEqualTo("****");
        assertThat(WaWebhookHandler.maskedNumber(null)).isEqualTo("****");
    }
}
