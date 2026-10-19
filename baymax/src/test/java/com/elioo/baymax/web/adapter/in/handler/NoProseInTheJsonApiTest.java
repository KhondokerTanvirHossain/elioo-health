package com.elioo.baymax.web.adapter.in.handler;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.adapter.in.handler.DocumentHandler;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.application.service.DocumentExtractionService;
import com.elioo.baymax.extraction.application.service.DocumentIntakeService;
import com.elioo.baymax.extraction.application.service.DocumentProcessingChain;
import com.elioo.baymax.extraction.application.service.PageRenderer;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.family.application.port.in.FreeTierUseCase;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.PatientAccess;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.outbound.application.port.in.DocumentUrgencyUseCase;
import com.elioo.baymax.outbound.application.port.in.ExplainDocumentUseCase;
import com.elioo.baymax.outbound.application.port.out.OutboundMessagePort;
import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.application.port.in.UploadUseCase;
import com.elioo.baymax.web.application.port.out.AuditPort;
import com.elioo.baymax.web.application.service.TimelineService;
import com.elioo.baymax.web.domain.WebSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;

/**
 * F5: the family's JSON never carries {@code status_reason}. A NEEDS_RETAKE document whose stored prose is the code's
 * own low-overall text goes through the real intake view, the real timeline service and the real handlers; the
 * response is parsed and EVERY string value and EVERY field name in the tree is walked — a field check, never a
 * substring of the document. The document JSON names the reason by its code, {@code retake_reason_code}; the timeline
 * entry gains that field with its read model (C2), and until then carries no reason of any kind.
 */
class NoProseInTheJsonApiTest {

    private static final Instant T = Instant.parse("2026-10-19T08:00:00Z");
    private static final UUID FAMILY = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID DOC = UUID.randomUUID();
    private static final String PROSE = DocumentExtractionService.REASON_LOW_OVERALL + " (0.40)";
    /** The fixed lead of every prose a retake path writes; none may appear in any string of any response. */
    private static final List<String> EVERY_PROSE = List.of(DocumentExtractionService.REASON_NO_TEXT,
            DocumentExtractionService.REASON_LOW_OVERALL,
            DocumentExtractionService.REASON_SECTION_EMPTY.substring(0, DocumentExtractionService.REASON_SECTION_EMPTY.indexOf('%')),
            DocumentExtractionService.REASON_LOW_SECTION);

    private final ObjectMapper json = new ObjectMapper();
    private WebTestClient client;

    @BeforeEach
    void wire() {
        Document retake = new Document(DOC, PATIENT, FAMILY, null, null, null, null, 0.4, Document.Status.NEEDS_RETAKE,
                PROSE, null, null, 1, T, T, null, null, "low_overall");
        DocumentRecordPort documents = mock(DocumentRecordPort.class);
        when(documents.find(DOC)).thenReturn(Mono.just(retake));
        when(documents.findVisible(FAMILY, DOC)).thenReturn(Mono.just(retake));
        when(documents.timelineOf(eq(PATIENT), any(), anyInt())).thenReturn(Flux.just(retake));
        when(documents.sectionCounts(DOC)).thenReturn(Mono.just(new int[]{0, 0, 0}));
        HealthRecordPort records = mock(HealthRecordPort.class);
        when(records.visiblePatient(FAMILY, PATIENT)).thenReturn(Mono.just(new PatientAccess(
                new PatientProfile(PATIENT, FAMILY, "Ma", 68, PatientProfile.Sex.FEMALE, List.of(), T, T), true)));
        AuditPort audit = mock(AuditPort.class);
        when(audit.record(any())).thenReturn(Mono.empty());
        DocumentStorageUseCase storage = mock(DocumentStorageUseCase.class);
        when(storage.signedUrl(any())).thenReturn(Mono.just(URI.create("https://storage.invalid/p.jpg")));
        DocumentUrgencyUseCase urgency = mock(DocumentUrgencyUseCase.class);
        when(urgency.currentOrAssess(any())).thenReturn(Mono.empty());
        Clock clock = Clock.fixed(T, ZoneOffset.UTC);

        DocumentIntakeService intake = new DocumentIntakeService(records, documents, mock(FreeTierUseCase.class), storage,
                new PageRenderer(), new BaymaxProperties(), clock, mock(DocumentProcessingChain.class));
        TimelineService timeline = new TimelineService(records, documents, intake, storage, audit,
                mock(OutboundMessagePort.class), clock, urgency);
        DocumentHandler documentApi = new DocumentHandler(intake, timeline, mock(ExplainDocumentUseCase.class), mock(UploadUseCase.class));
        TimelineApiHandler timelineApi = new TimelineApiHandler(timeline);

        RouterFunction<ServerResponse> routes = RouterFunctions.route(GET("/documents/{id}"), documentApi::status)
                .andRoute(GET("/patients/{id}/timeline"), timelineApi::timeline)
                .filter((request, next) -> {
                    // a family session for "/patients/…" and "?as=family"; the admin path (no session) otherwise
                    if (request.path().startsWith("/patients/") || request.queryParam("as").isPresent()) {
                        request.attributes().put(SessionAuthFilter.SESSION_ATTRIBUTE,
                                new WebSession(UUID.randomUUID(), FAMILY, T, T, T.plusSeconds(3600)));
                    }
                    return next.handle(request);
                });
        client = WebTestClient.bindToRouterFunction(routes).build();
    }

    @Test
    void theDocumentJsonNamesTheRetakeByItsCodeAndCarriesNoProse() throws Exception {
        for (String uri : List.of("/documents/" + DOC, "/documents/" + DOC + "?as=family")) {
            JsonNode body = get(uri);
            assertNoProseAndNoReasonField(uri, body);
            assertThat(body.path("retake_reason_code").asText(null)).as(uri + " $.retake_reason_code").isEqualTo("low_overall");
            assertThat(body.path("status").asText()).as(uri + " $.status").isEqualTo("NEEDS_RETAKE");
        }
    }

    @Test
    void theTimelineJsonCarriesNoProseAndNoReasonField() throws Exception {
        String uri = "/patients/" + PATIENT + "/timeline";
        JsonNode body = get(uri);
        assertThat(body.path("documents")).as("the entry is there to be checked").hasSize(1);
        assertThat(body.path("documents").get(0).path("status").asText()).isEqualTo("NEEDS_RETAKE");
        assertNoProseAndNoReasonField(uri, body);
    }

    private JsonNode get(String uri) throws Exception {
        byte[] bytes = client.get().uri(uri).exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();
        return json.readTree(bytes);
    }

    /** Every string value and every field name, by JSON path, asserting the walk found something first. */
    private static void assertNoProseAndNoReasonField(String uri, JsonNode body) {
        List<Map.Entry<String, String>> strings = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        walk("$", body, strings, fields);
        assertThat(strings).as(uri + ": the walk reached string values").isNotEmpty();
        assertThat(fields).as(uri + ": the walk reached field names").isNotEmpty();
        for (Map.Entry<String, String> s : strings) {
            for (String prose : EVERY_PROSE) {
                assertThat(s.getValue()).as(uri + " " + s.getKey()).doesNotContain(prose);
            }
        }
        assertThat(fields).as(uri + ": no developer reason field").noneMatch(f -> f.endsWith(".status_reason") || f.endsWith(".reason"));
    }

    private static void walk(String path, JsonNode node, List<Map.Entry<String, String>> strings, List<String> fields) {
        if (node.isTextual()) {
            strings.add(Map.entry(path, node.asText()));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                walk(path + "[" + i + "]", node.get(i), strings, fields);
            }
        } else if (node.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> f = it.next();
                fields.add(path + "." + f.getKey());
                walk(path + "." + f.getKey(), f.getValue(), strings, fields);
            }
        }
    }
}
