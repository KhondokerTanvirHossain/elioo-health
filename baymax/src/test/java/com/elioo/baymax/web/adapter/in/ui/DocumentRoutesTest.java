package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.extraction.domain.Document;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** The document page's routes besides the page itself (A9: the reading page's status poll; B6: the detail and the value sheet). */
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

    /** B6: an observation id from another paper is the 404 page, and nothing of the result is read for it. */
    @Test
    void aValueSheetForAnObservationOfAnotherPaperIsTheNotFoundPage() {
        UUID other = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100099");
        PageFixtures.Stubs.doneLab(kit);
        when(kit.results.earlierReadings(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID, other))
                .thenReturn(Flux.error(BaymaxException.notFound("observation_not_found", "no such value on document")));
        String html = kit.client.get().uri("/app/documents/" + WebUiTestKit.DOC_ID + "/values/" + other).cookie("baymax_session", "tok")
                .exchange().expectStatus().isNotFound().expectBody(String.class).returnResult().getResponseBody();
        assertThat(html).contains(kit.copy.t(Lang.BN, "app.err.notfound"));
        org.mockito.Mockito.verify(kit.results, org.mockito.Mockito.never()).result(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID);
    }

    /** B6: the sheet of one of this paper's values is a page; the detail of a read paper is a page; both need a session. */
    @Test
    void theValueSheetAndTheDetailOfAReadPaperArePages() {
        PageFixtures.Stubs.doneLab(kit);
        UUID hba1c = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100021");
        when(kit.results.earlierReadings(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID, hba1c)).thenReturn(Flux.fromIterable(List.of()));
        assertThat(kit.page("/app/documents/" + WebUiTestKit.DOC_ID + "/values/" + hba1c + "?from=all", "bn"))
                .contains("role=\"dialog\"").contains("href=\"/app/documents/" + WebUiTestKit.DOC_ID + "/all#v-" + hba1c + "\"");
        assertThat(kit.page("/app/documents/" + WebUiTestKit.DOC_ID + "/all", "bn")).contains("id=\"seg\"");
        kit.client.get().uri("/app/documents/" + WebUiTestKit.DOC_ID + "/all").exchange().expectStatus().isSeeOther();
        kit.client.get().uri("/app/documents/" + WebUiTestKit.DOC_ID + "/values/" + hba1c).exchange().expectStatus().isSeeOther();
    }

    /** B6: a paper that is not read yet (or needs a retake) has no detail: its own page says what is happening. */
    @Test
    void theDetailOfAPaperNotYetReadGoesToItsPage() {
        PageFixtures.Stubs.processing(kit);
        kit.client.get().uri("/app/documents/" + WebUiTestKit.DOC_ID + "/all").cookie("baymax_session", "tok").exchange()
                .expectStatus().isSeeOther().expectHeader().valueEquals(HttpHeaders.LOCATION, "/app/documents/" + WebUiTestKit.DOC_ID);
        org.mockito.Mockito.verify(kit.results, org.mockito.Mockito.never()).result(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID);
    }
}
