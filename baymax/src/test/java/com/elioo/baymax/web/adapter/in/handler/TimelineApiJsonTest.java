package com.elioo.baymax.web.adapter.in.handler;

import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;
import com.elioo.baymax.web.domain.TimelineEntry;
import com.elioo.baymax.web.domain.TimelineFilter;
import com.elioo.baymax.web.domain.TimelinePage;
import com.elioo.baymax.web.domain.WebSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunctions;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;

/**
 * C2's JSON: {@code GET /patients/{id}/timeline} passes {@code cursor} and {@code type} to the read model as given, and
 * each entry carries the stored level, the outside count, the review state and the retake code by field.
 */
class TimelineApiJsonTest {

    private static final Instant T = Instant.parse("2026-10-19T08:00:00Z");
    private static final UUID FAMILY = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID DOC = UUID.randomUUID();

    private final TimelineUseCase timeline = mock(TimelineUseCase.class);
    private final ObjectMapper json = new ObjectMapper();
    private WebTestClient client;

    @BeforeEach
    void wire() {
        TimelineApiHandler handler = new TimelineApiHandler(timeline);
        client = WebTestClient.bindToRouterFunction(RouterFunctions.route(GET("/patients/{id}/timeline"), handler::timeline)
                .filter((request, next) -> {
                    request.attributes().put(SessionAuthFilter.SESSION_ATTRIBUTE,
                            new WebSession(UUID.randomUUID(), FAMILY, T, T, T.plusSeconds(3600)));
                    return next.handle(request);
                })).build();
        when(timeline.imageUrl(eq(FAMILY), eq(DOC), any())).thenReturn(Mono.just(URI.create("https://storage.invalid/p.jpg")));
        when(timeline.timeline(eq(FAMILY), eq(PATIENT), any(), any())).thenReturn(Mono.just(new TimelinePage(List.of(
                new TimelineEntry(DOC, LocalDate.parse("2026-10-18"), false, "lab_report", null, "DONE", 14, 0, 0, 0,
                        "k/" + DOC + "/page-1.jpg", T, Urgency.NOW, 2, "pending", null, false)), "v1.next")));
    }

    /**
     * D2: the legacy JSON carries the read model's uncertain mark by value, on each entry: one marked, one not, so a key
     * dropped, a constant written, or the wrong field wired fails on one of the two.
     */
    @Test
    void eachEntryCarriesItsOwnUncertainMark() throws Exception {
        UUID marked = UUID.randomUUID();
        UUID plain = UUID.randomUUID();
        when(timeline.timeline(eq(FAMILY), eq(PATIENT), any(), any())).thenReturn(Mono.just(new TimelinePage(List.of(
                new TimelineEntry(marked, LocalDate.parse("2026-07-11"), false, "lab_report", null, "DONE", 1, 0, 0, 0,
                        "k/" + marked + "/page-1.jpg", T, null, 0, null, null, true),
                new TimelineEntry(plain, LocalDate.parse("2026-07-10"), false, "lab_report", null, "DONE", 1, 0, 0, 0,
                        "k/" + plain + "/page-1.jpg", T, null, 0, null, null, false)), null)));
        when(timeline.imageUrl(eq(FAMILY), any(), any())).thenReturn(Mono.just(URI.create("https://storage.invalid/p.jpg")));
        JsonNode documents = get("/patients/" + PATIENT + "/timeline").path("documents");
        assertThat(documents.get(0).path("id").asText()).isEqualTo(marked.toString());
        assertThat(documents.get(0).path("doc_date_uncertain").isBoolean()).as("$.documents[0].doc_date_uncertain is a boolean").isTrue();
        assertThat(documents.get(0).path("doc_date_uncertain").booleanValue()).as("$.documents[0].doc_date_uncertain").isTrue();
        assertThat(documents.get(1).path("id").asText()).isEqualTo(plain.toString());
        assertThat(documents.get(1).path("doc_date_uncertain").isBoolean()).as("$.documents[1].doc_date_uncertain is a boolean").isTrue();
        assertThat(documents.get(1).path("doc_date_uncertain").booleanValue()).as("$.documents[1].doc_date_uncertain").isFalse();
    }

    @Test
    void theCursorAndTheLabFilterReachTheReadModelAndTheEntryCarriesItsStoredFacts() throws Exception {
        JsonNode body = get("/patients/" + PATIENT + "/timeline?cursor=v1.abc&type=lab");
        verify(timeline).timeline(FAMILY, PATIENT, "v1.abc", TimelineFilter.LAB);
        JsonNode e = body.path("documents").get(0);
        assertThat(e.path("urgency").asText()).isEqualTo("now");
        assertThat(e.path("outside").asInt()).isEqualTo(2);
        assertThat(e.path("review_state").asText()).isEqualTo("pending");
        assertThat(e.has("retake_reason_code")).isTrue();
        assertThat(e.path("retake_reason_code").isNull()).isTrue();
        assertThat(body.path("next_cursor").asText()).isEqualTo("v1.next");
    }

    @Test
    void thePrescriptionFilterIsRxAndAnythingElseIsEveryPaper() throws Exception {
        get("/patients/" + PATIENT + "/timeline?type=rx");
        verify(timeline).timeline(FAMILY, PATIENT, null, TimelineFilter.PRESCRIPTION);
        get("/patients/" + PATIENT + "/timeline?type=';drop");
        verify(timeline).timeline(FAMILY, PATIENT, null, TimelineFilter.ALL);
    }

    private JsonNode get(String uri) throws Exception {
        return json.readTree(client.get().uri(uri).exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
    }
}
