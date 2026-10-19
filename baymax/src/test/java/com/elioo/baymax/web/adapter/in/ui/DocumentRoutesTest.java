package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.extraction.domain.Document;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** The document page's routes besides the page itself (A9: the reading page's status poll). */
class DocumentRoutesTest {

    private final WebUiTestKit kit = new WebUiTestKit();

    /** The poll's answer: the status alone (never an urgency), never cached, and nothing without a session. */
    @Test
    void theStatusEndpointAnswersTheStatusAloneAndIsNeverCached() throws Exception {
        when(kit.results.status(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID)).thenReturn(Mono.just(Document.Status.PROCESSING));
        String json = kit.client.get().uri("/app/documents/" + WebUiTestKit.DOC_ID + "/status").cookie("baymax_session", "tok").exchange()
                .expectStatus().isOk().expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(new ObjectMapper().readTree(json).fieldNames()).toIterable().containsExactly("status");
        assertThat(new ObjectMapper().readTree(json).get("status").asText()).isEqualTo("PROCESSING");
        kit.client.get().uri("/app/documents/" + WebUiTestKit.DOC_ID + "/status").exchange().expectStatus().isSeeOther();
    }
}
