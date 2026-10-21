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
import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
import com.elioo.baymax.extraction.application.port.out.DocumentReadPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.outbound.application.port.out.OutboundMessagePort;
import com.elioo.baymax.web.adapter.in.api.AppApiRouter;
import com.elioo.baymax.web.adapter.in.api.PaperApiHandler;
import com.elioo.baymax.extraction.domain.RetakeReason;
import com.elioo.baymax.testsupport.Json;
import com.elioo.baymax.web.adapter.in.ui.Lang;
import com.elioo.baymax.web.adapter.in.ui.UiCopy;
import java.util.Arrays;
import com.elioo.baymax.web.application.service.ResultService;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.application.port.in.UploadUseCase;
import com.elioo.baymax.web.application.port.out.AuditPort;
import com.elioo.baymax.web.application.port.out.TimelineReadPort;
import com.elioo.baymax.web.application.service.TimelineService;
import com.elioo.baymax.web.domain.TimelineEntry;
import com.elioo.baymax.web.domain.WebSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;

/**
 * F5: the family's JSON never carries {@code status_reason}. Each stored document — one per prose a retake path
 * writes, each with its code, and a FAILED row carrying a provider error — goes through the real intake view, the real
 * timeline service and the real handlers; the response is parsed and EVERY string value and EVERY field name in the
 * tree is walked — a field check, never a substring of the document. The document JSON names the reason by its code,
 * {@code retake_reason_code}; so does the timeline entry, from its read model (C2: the row carries the stored code of a
 * NEEDS_RETAKE paper, as {@code PostgresTimelineReadAdapter} reads it, and the handler must pass the code and nothing
 * else).
 */
class NoProseInTheJsonApiTest {

    private static final Instant T = Instant.parse("2026-10-19T08:00:00Z");
    private static final UUID FAMILY = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID DOC = UUID.randomUUID();
    /** The fixed lead of every prose a retake path writes; none may appear in any string of any response. */
    private static final List<String> EVERY_PROSE = List.of(DocumentExtractionService.REASON_NO_TEXT,
            DocumentExtractionService.REASON_LOW_OVERALL,
            DocumentExtractionService.REASON_SECTION_EMPTY.substring(0, DocumentExtractionService.REASON_SECTION_EMPTY.indexOf('%')),
            DocumentExtractionService.REASON_LOW_SECTION);

    /** The date the model read off a page it could not read, kept in doc_date on a retake (and on a FAILED row here). */
    private static final String MISREAD = "2021-03-04";

    private final ObjectMapper json = new ObjectMapper();
    private WebTestClient client;

    /**
     * D2 (review finding 15): a paper that is not DONE keeps the date read off it in doc_date, and only intake's
     * status-only view for anything not DONE keeps it out of every body: the legacy {@code /documents/{id}} has no
     * {@code document_date} (admin and family alike), and the paper JSON's {@code printed_date} is null beside its
     * upload day. Asserted by field over the whole tree, never a substring.
     */
    @ParameterizedTest
    @MethodSource("storedDocuments")
    void aNotDoneDocumentsMisreadDateReachesNoJson(String status, String prose, String code) throws Exception {
        wire(status, prose, code);
        for (String uri : List.of("/documents/" + DOC, "/documents/" + DOC + "?as=family")) {
            JsonNode body = get(uri);
            assertThat(body.has("document_date")).as(uri + " $.document_date").isFalse();
            List<String> carrying = new java.util.ArrayList<>();
            com.elioo.baymax.testsupport.Json.walk(body, (path, node) -> {
                if (node.isTextual() && node.asText().equals(MISREAD)) {
                    carrying.add(path);
                }
            });
            assertThat(carrying).as(uri + " fields carrying the misread date").isEmpty();
        }
        JsonNode paper = get(AppApiRouter.BASE + "/documents/" + DOC);
        assertThat(paper.has("printed_date")).as("$.printed_date is present").isTrue();
        assertThat(paper.path("printed_date").isNull()).as("$.printed_date of a " + status + " paper").isTrue();
        assertThat(paper.path("date").asText()).as("$.date: the filed upload day").isEqualTo("2026-10-19");
    }

    /** status | stored prose | stored code: every prose a retake path writes, and FAILED's developer error. */
    static Stream<Arguments> storedDocuments() {
        return Stream.of(
                Arguments.of("NEEDS_RETAKE", DocumentExtractionService.REASON_NO_TEXT, "no_text"),
                Arguments.of("NEEDS_RETAKE", DocumentExtractionService.REASON_LOW_OVERALL + " (0.40)", "low_overall"),
                Arguments.of("NEEDS_RETAKE", DocumentExtractionService.REASON_SECTION_EMPTY.formatted("medicines", "prescription"),
                        "expected_section_empty:medicines"),
                Arguments.of("NEEDS_RETAKE", DocumentExtractionService.REASON_LOW_SECTION + " (0.55)", "low_section:values"),
                Arguments.of("FAILED", "groq HTTP 503", null));
    }

    private void wire(String status, String prose, String code) {
        // D2: finish keeps the date it read even off a page it could not read; no JSON may show it (MISREAD)
        Document retake = new Document(DOC, PATIENT, FAMILY, null, LocalDate.parse(MISREAD), null, null, 0.4, Document.Status.valueOf(status),
                prose, null, null, 1, T, T, null, null, code, false,
                // as the row reads back: a paper that is not DONE (or printed nothing) is filed under its upload day (V25)
                new com.elioo.baymax.extraction.domain.FiledDay(LocalDate.parse("2026-10-19"), true, false));
        DocumentRecordPort documents = mock(DocumentRecordPort.class);
        when(documents.find(DOC)).thenReturn(Mono.just(retake));
        when(documents.findVisible(FAMILY, DOC)).thenReturn(Mono.just(retake));
        // the read model's row for this paper: the code on a retake (the adapter reads it only then), never the prose
        TimelineReadPort timelineRead = mock(TimelineReadPort.class);
        when(timelineRead.page(eq(PATIENT), any(), any(), anyInt())).thenReturn(Flux.just(new TimelineEntry(DOC,
                LocalDate.parse("2026-10-19"), true, null, null, status, 0, 0, 0, 0, "k/" + DOC + "/page-1.jpg", T,
                null, 0, null, "NEEDS_RETAKE".equals(status) ? code : null, false)));
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
        TimelineService timeline = new TimelineService(records, documents, intake, storage, audit, clock, urgency, timelineRead);
        DocumentHandler documentApi = new DocumentHandler(intake, timeline, mock(ExplainDocumentUseCase.class), mock(UploadUseCase.class));
        TimelineApiHandler timelineApi = new TimelineApiHandler(timeline);
        // the paper's display model (rebuild plan B2), over the real result service and the same intake view
        when(records.findFamily(FAMILY)).thenReturn(Mono.just(new FamilyAccount(FAMILY, "+8801700000401", "Tanvir",
                FamilyAccount.Plan.FREE, T, T)));
        DocumentReadPort reads = mock(DocumentReadPort.class);
        when(reads.retakeOrdinal(DOC)).thenReturn(Mono.just(1));
        ResultService result = new ResultService(records, documents, intake, audit, clock, mock(OutboundMessagePort.class), urgency,
                new BaymaxProperties(), reads);
        when(storage.measuredObjectsOf(any(), any())).thenReturn(Flux.empty());
        PaperApiHandler paperApi = new PaperApiHandler(result, new UiCopy(), storage);

        RouterFunction<ServerResponse> routes = RouterFunctions.route(GET("/documents/{id}"), documentApi::status)
                .andRoute(GET("/patients/{id}/timeline"), timelineApi::timeline)
                .andRoute(GET(AppApiRouter.BASE + "/documents/{id}"), paperApi::paper)
                .filter((request, next) -> {
                    // a family session for "/patients/…", "/app/api/…" and "?as=family"; the admin path (no session) otherwise
                    if (request.path().startsWith("/patients/") || request.path().startsWith(AppApiRouter.BASE + "/")
                            || request.queryParam("as").isPresent()) {
                        request.attributes().put(SessionAuthFilter.SESSION_ATTRIBUTE,
                                new WebSession(UUID.randomUUID(), FAMILY, T, T, T.plusSeconds(3600)));
                    }
                    return next.handle(request);
                });
        client = WebTestClient.bindToRouterFunction(routes).build();
    }

    @ParameterizedTest
    @MethodSource("storedDocuments")
    void theDocumentJsonNamesTheRetakeByItsCodeAndCarriesNoProse(String status, String prose, String code) throws Exception {
        wire(status, prose, code);
        for (String uri : List.of("/documents/" + DOC, "/documents/" + DOC + "?as=family")) {
            JsonNode body = get(uri);
            assertNoProseAndNoReasonField(uri, body, prose);
            JsonNode reasonCode = body.path("retake_reason_code");
            assertThat(reasonCode.isNull() || reasonCode.isMissingNode() ? null : reasonCode.asText())
                    .as(uri + " $.retake_reason_code").isEqualTo(code);
            assertThat(body.path("status").asText()).as(uri + " $.status").isEqualTo(status);
        }
    }

    @ParameterizedTest
    @MethodSource("storedDocuments")
    void theTimelineJsonCarriesNoProseAndNoReasonField(String status, String prose, String code) throws Exception {
        wire(status, prose, code);
        String uri = "/patients/" + PATIENT + "/timeline";
        JsonNode body = get(uri);
        assertThat(body.path("documents")).as("the entry is there to be checked").hasSize(1);
        assertThat(body.path("documents").get(0).path("status").asText()).isEqualTo(status);
        assertNoProseAndNoReasonField(uri, body, prose);
        JsonNode reasonCode = body.path("documents").get(0).path("retake_reason_code");
        assertThat(reasonCode.isMissingNode()).as(uri + " $.documents[0].retake_reason_code is a field").isFalse();
        assertThat(reasonCode.isNull() ? null : reasonCode.asText()).as(uri + " $.documents[0].retake_reason_code").isEqualTo(code);
    }

    /** The paper's display model names a retake by its copy key (the code's own mapping) and carries no prose either. */
    @ParameterizedTest
    @MethodSource("storedDocuments")
    void thePaperJsonCarriesNoProseAndNoReasonField(String status, String prose, String code) throws Exception {
        wire(status, prose, code);
        String uri = AppApiRouter.BASE + "/documents/" + DOC;
        JsonNode body = get(uri);
        assertThat(body.path("status").asText()).as(uri + " $.status").isEqualTo(status);
        assertNoProseAndNoReasonField(uri, body, prose);
        if ("NEEDS_RETAKE".equals(status)) {
            // the key the bundles say this code reads as: the section's own line where both languages hold one
            UiCopy copy = new UiCopy();
            RetakeReason reason = RetakeReason.ofCode(code).orElseThrow();
            String section = RetakeReason.sectionOf(code);
            String specific = section == null ? null : "retake.reason." + reason.prefix() + "." + section;
            boolean bothHold = specific != null && Arrays.stream(Lang.values()).allMatch(l -> copy.keys(l).contains(specific));
            assertThat(body.path("retake").path("reason_key").asText()).as(uri + " $.retake.reason_key")
                    .isEqualTo(bothHold ? specific : "retake.reason." + reason.prefix());
            List<String> carryingTheCode = new ArrayList<>();
            Json.walk(body, (path, node) -> {
                if (node.isTextual() && node.asText().equals(code)) {
                    carryingTheCode.add(path);
                }
            });
            assertThat(carryingTheCode).as(uri + " scalars that are the stored code " + code).isEmpty();
        } else {
            assertThat(body.has("retake")).as(uri + " $.retake on " + status).isFalse();
        }
    }

    private JsonNode get(String uri) throws Exception {
        byte[] bytes = client.get().uri(uri).exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();
        return json.readTree(bytes);
    }

    /** Every string value and every field name, by JSON path, asserting the walk found something first. */
    private static void assertNoProseAndNoReasonField(String uri, JsonNode body, String storedProse) {
        List<Map.Entry<String, String>> strings = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        walk("$", body, strings, fields);
        assertThat(strings).as(uri + ": the walk reached string values").isNotEmpty();
        assertThat(fields).as(uri + ": the walk reached field names").isNotEmpty();
        for (Map.Entry<String, String> s : strings) {
            assertThat(s.getValue()).as(uri + " " + s.getKey() + ": the stored prose").doesNotContain(storedProse);
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
