package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.nudge.application.port.in.NudgeOptOutUseCase;
import com.elioo.baymax.nudge.application.port.out.NudgeDataPort;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.outbound.domain.ValueVerdict;
import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
import com.elioo.baymax.storage.domain.ObjectKind;
import com.elioo.baymax.storage.domain.StoredObject;
import com.elioo.baymax.testsupport.PaperFixtures;
import com.elioo.baymax.testsupport.Snapshot;
import com.elioo.baymax.web.adapter.in.ImageRefs;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.adapter.in.ui.Lang;
import com.elioo.baymax.web.adapter.in.ui.UiCopy;
import com.elioo.baymax.web.application.port.in.AttentionUseCase;
import com.elioo.baymax.web.application.port.in.HealthUseCase;
import com.elioo.baymax.web.application.port.in.ResultUseCase;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;
import com.elioo.baymax.web.application.port.in.UploadUseCase;
import com.elioo.baymax.web.application.port.in.WebAuthUseCase;
import com.elioo.baymax.web.domain.AuditEvent;
import com.elioo.baymax.web.domain.EarlierHistory;
import com.elioo.baymax.web.domain.EarlierReading;
import com.elioo.baymax.web.domain.GateState;
import com.elioo.baymax.web.domain.PaperDisplay;
import com.elioo.baymax.web.domain.ResultView;
import com.elioo.baymax.web.domain.WebSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.elioo.baymax.testsupport.PaperFixtures.DOC;
import static com.elioo.baymax.testsupport.PaperFixtures.FAMILY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plan W6-api: {@code GET /app/api/documents/{id}/values/{obs}}, one value's sheet as JSON. Two halves.
 *
 * <p>The contract, through the real {@link AppApiRouter} and {@link AppApiGuard} with a mocked {@link ResultUseCase}
 * serving {@link PaperFixtures}: each case is written in the generated-file envelope and compared byte for byte with the
 * committed {@code medioo-web/fixtures/api/value-sheet/*.json}; the value is compared, field by field, with the row of
 * the same id in {@code GET /app/api/documents/{id}} from the same mocked read (derived, never written down).
 *
 * <p>The audit, over the real module and Testcontainers Postgres ({@link AppApiContext}): a probe records no
 * DOCUMENT_VIEW and one sheet read records exactly one, counted in {@code baymax.audit_event} itself (a mock cannot
 * fail the way the database does, CLAUDE.md). Every assertion reads the parsed tree by field path; none matches a
 * substring of the document (the ids are UUIDs). Names and numbers are fictional.
 */
@Testcontainers
class ValueSheetApiContractTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final Path COMMITTED = Path.of("../medioo-web/fixtures/api/value-sheet");
    private static final Path WRITTEN = Path.of("build/web-contract/value-sheet");

    /** The value row's fields, exactly: the PaperJson row the paper sends, nothing added and nothing left out. */
    private static final Set<String> VALUE_FIELDS = Set.of("id", "name", "value", "unit", "ref_low", "ref_high", "display_kind",
            "side", "chip_key", "pin", "in_now_block", "crop");

    // three earlier papers of Ma's, each with its own crop under its own key and its own ledger size
    static final UUID EARLIER_1 = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100071");
    static final UUID EARLIER_2 = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100072");
    static final UUID EARLIER_3 = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100073");

    private final ObjectMapper json = new ObjectMapper();
    private final WebAuthUseCase auth = mock(WebAuthUseCase.class);
    private final ResultUseCase results = mock(ResultUseCase.class);
    private final DocumentStorageUseCase storage = mock(DocumentStorageUseCase.class);
    private final UiCopy copy = new UiCopy();
    private final BaymaxProperties properties = new BaymaxProperties();
    private final SessionAuthFilter sessions = new SessionAuthFilter(auth, properties);
    private final WebTestClient client;

    ValueSheetApiContractTest() {
        Instant now = PaperFixtures.NOW;
        when(auth.authenticate(anyString())).thenReturn(Mono.empty());
        when(storage.measuredObjectsOf(any(), any())).thenReturn(Flux.empty());
        when(storage.objectsOf(any())).thenReturn(Flux.empty());
        when(auth.authenticate("tok")).thenReturn(Mono.just(new WebSession(UUID.randomUUID(), FAMILY, now, now, now.plusSeconds(600))));
        properties.getWeb().setPublicOrigin(AppApiGuardTest.ORIGIN);
        AppApiGuard guard = new AppApiGuard(sessions, properties);
        TimelineUseCase timeline = mock(TimelineUseCase.class);
        client = WebTestClient.bindToRouterFunction(new AppApiRouter().mediooAppApiRoutes(
                new MeApiHandler(timeline, mock(AttentionUseCase.class), properties), new SignInApiHandler(auth, sessions, properties),
                new PaperApiHandler(results, copy, storage),
                new UploadApiHandler(mock(UploadUseCase.class), properties, copy),
                new PaperStatusApiHandler(results),
                new PaperDeleteApiHandler(timeline, new DeleteConfirmation(properties, java.time.Clock.systemUTC())),
                new FamilyApiHandler(mock(TimelineUseCase.class), mock(NudgeDataPort.class), mock(NudgeOptOutUseCase.class),
                        new UiCopy(), properties),
                new HealthApiHandler(mock(HealthUseCase.class)),
                B3Handlers.nowBanner(), B3Handlers.attention(), B3Handlers.timeline(),
                guard)).build();
    }

    // ---- the cases ------------------------------------------------------------------------------------------

    /**
     * One sheet as the mocked read serves it: the paper, the observation asked for, its earlier readings in this unit and
     * whether the marker was also read in another unit; or a 404.
     */
    record Case(ResultView paper, UUID observation, List<EarlierReading> earlier, boolean readInAnotherUnit, boolean notFound) {
        static Case of(ResultView paper, Map<String, Object> row, List<EarlierReading> earlier) {
            return new Case(paper, (UUID) row.get("id"), earlier, false, false);
        }

        /** The same sheet, the marker also read before in a unit that does not fold to this one. */
        Case alsoReadInAnotherUnit() {
            return new Case(paper, observation, earlier, true, notFound);
        }
    }

    /** Creatinine above its printed range: a stored OUTSIDE on the high side. */
    static Map<String, Object> creatinineHigh() {
        return PaperFixtures.value("Creatinine", "1.6", "mg/dL", "0.6", "1.2", "outside", "high");
    }

    /** A value whose name did not canonicalise: Medioo has no history of it to look up. */
    static Map<String, Object> uncanonical() {
        Map<String, Object> row = PaperFixtures.value("Serum X factor", "42", "U/L", "10", "50", "inside");
        row.remove("canonical_name");
        return row;
    }

    static ResultView thisWeekCreatinine() {
        DocumentView view = PaperFixtures.paper(List.of(creatinineHigh(), PaperFixtures.haemoglobinInside()), List.of(), List.of(), Map.of());
        return PaperFixtures.result(view, Urgency.THIS_WEEK, List.of("value_outside_range:creatinine|stopgap"), GateState.RELEASED,
                "Creatinine is above the printed range.", PaperFixtures.BD);
    }

    static ResultView routineUncanonical() {
        DocumentView view = PaperFixtures.paper(List.of(uncanonical()), List.of(), List.of(), Map.of());
        return PaperFixtures.result(view, Urgency.ROUTINE, List.of(), GateState.RELEASED, "Everything printed here is inside its range.",
                PaperFixtures.BD);
    }

    static EarlierReading earlier(UUID document, int n, String value, String unit, LocalDate date, boolean uploadDay) {
        return new EarlierReading(document, UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a41000" + (80 + n)), value, unit,
                earlierCrop(document), date, uploadDay);
    }

    /** Each earlier paper's crop under its own document's prefix, so a reading drawn beside another paper's strip is caught. */
    static String earlierCrop(UUID document) {
        return "f/p/" + document + "/crop-1.jpg";
    }

    static Map<String, Supplier<Case>> cases() {
        Map<String, Supplier<Case>> cases = new LinkedHashMap<>();
        cases.put("inside", () -> Case.of(PaperFixtures.routineReleased(), PaperFixtures.haemoglobinInside(),
                List.of(earlier(EARLIER_1, 1, "12.8", "g/dL", LocalDate.of(2026, 7, 11), false))));
        cases.put("outside-high", () -> Case.of(thisWeekCreatinine(), creatinineHigh(),
                List.of(earlier(EARLIER_1, 2, "1.1", "mg/dL", LocalDate.of(2026, 7, 11), false))));
        cases.put("unknown-one-sided", () -> Case.of(PaperFixtures.nowPaper(PaperFixtures.BD), PaperFixtures.ldlOneSided(), List.of()));
        // the critical value of the NOW paper; the third paper printed no date (filed under its upload day) and its crop
        // was never measured, so both the sized and the unsized crop shape are on the wire
        cases.put("earlier-three", () -> Case.of(PaperFixtures.nowPaper(PaperFixtures.BD), PaperFixtures.potassiumCritical(), List.of(
                earlier(EARLIER_1, 3, "5.9", "mmol/L", LocalDate.of(2026, 9, 2), false),
                earlier(EARLIER_2, 4, "5.4", "mmol/L", LocalDate.of(2026, 7, 11), false),
                earlier(EARLIER_3, 5, "4.8", "mmol/L", LocalDate.of(2026, 4, 3), true))));
        cases.put("earlier-none", () -> Case.of(PaperFixtures.routineReleased(), PaperFixtures.haemoglobinInside(), List.of()));
        cases.put("no-history", () -> Case.of(routineUncanonical(), uncanonical(), List.of()));
        // creatinine in mg/dL here, read before only in µmol/L: no earlier reading in this unit, and not the first time
        cases.put("earlier-other-unit", () -> Case.of(thisWeekCreatinine(), creatinineHigh(), List.of()).alsoReadInAnotherUnit());
        cases.put("not-found", () -> new Case(PaperFixtures.routineReleased(), UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100099"),
                List.of(), false, true));
        return cases;
    }

    static Stream<String> caseNames() {
        return cases().keySet().stream();
    }

    /** The ledger of an earlier paper: its crop sized distinctly per paper, except EARLIER_3's, never measured. */
    static List<StoredObject> earlierLedger(UUID document) {
        if (document.equals(EARLIER_3)) {
            return List.of(new StoredObject(UUID.nameUUIDFromBytes(document.toString().getBytes()), FAMILY, PaperFixtures.MA_ID, document,
                    ObjectKind.CROP, null, "e", earlierCrop(document), "image/jpeg", 100, PaperFixtures.NOW, null, null));
        }
        int n = document.equals(EARLIER_1) ? 1 : 2;
        return List.of(new StoredObject(UUID.nameUUIDFromBytes(document.toString().getBytes()), FAMILY, PaperFixtures.MA_ID, document,
                ObjectKind.CROP, null, "e", earlierCrop(document), "image/jpeg", 100, PaperFixtures.NOW, 300 + 7 * n, 40 + n));
    }

    private void serve(Case c) {
        ResultView r = c.paper();
        when(results.status(FAMILY, DOC)).thenReturn(Mono.just(Document.Status.valueOf(r.document().status())));
        when(results.result(FAMILY, DOC)).thenReturn(Mono.just(r));
        when(storage.measuredObjectsOf(DOC, ObjectKind.CROP)).thenReturn(Flux.fromIterable(
                PaperApiContractTest.ledgerOf(r.document()).stream().filter(o -> o.kind() == ObjectKind.CROP).toList()));
        // an earlier paper's sizes are read from its ledger as stored (objectsOf), never measured (measuredObjectsOf)
        for (UUID d : List.of(EARLIER_1, EARLIER_2, EARLIER_3)) {
            when(storage.objectsOf(d)).thenReturn(Flux.fromIterable(earlierLedger(d)));
        }
        when(results.earlierHistory(FAMILY, DOC, c.observation())).thenReturn(c.notFound()
                ? Mono.error(BaymaxException.notFound("observation_not_found", "no such value on document"))
                : Mono.just(new EarlierHistory(c.earlier(), c.readInAnotherUnit())));
    }

    private EntityExchangeResult<byte[]> exchange(String uri) {
        return client.get().uri(uri).cookie("baymax_session", "tok").cookie("baymax_lang", Lang.BN.code).exchange().expectBody().returnResult();
    }

    private JsonNode sheet(Case c) throws IOException {
        EntityExchangeResult<byte[]> r = exchange(sheetUri(DOC, c.observation()));
        assertThat(r.getStatus().value()).as("GET the sheet").isEqualTo(200);
        return json.readTree(r.getResponseBody());
    }

    private static String sheetUri(UUID document, UUID observation) {
        return "/app/api/documents/" + document + "/values/" + observation;
    }

    @ParameterizedTest
    @MethodSource("caseNames")
    void eachCaseIsItsCommittedMswFixture(String name) throws IOException {
        Case c = cases().get(name).get();
        serve(c);
        EntityExchangeResult<byte[]> r = exchange(sheetUri(DOC, c.observation()));
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("_meta", Snapshot.meta(ValueSheetApiContractTest.class, "GET /app/api/documents/{id}/values/{obs} over the case " + name));
        envelope.put("status", r.getStatus().value());
        envelope.put("body", json.readValue(r.getResponseBody(), Object.class));
        String file = name + ".json";
        Snapshot.matches(COMMITTED.resolve(file), WRITTEN.resolve(file), Snapshot.json(envelope),
                "cp baymax/" + WRITTEN.resolve(file) + " medioo-web/fixtures/api/value-sheet/" + file);
    }

    /** Both ways: every committed fixture is a current case, so a renamed or removed case leaves no stale body for MSW. */
    @Test
    void everyCommittedFixtureIsACurrentCase() throws IOException {
        List<String> committed;
        try (Stream<Path> files = Files.list(COMMITTED)) {
            committed = files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json")).sorted().toList();
        }
        assertThat(committed).as("the committed fixtures in " + COMMITTED.toAbsolutePath()).isNotEmpty();
        assertThat(committed).containsExactlyInAnyOrderElementsOf(caseNames().map(n -> n + ".json").toList());
    }

    @Test
    void notFoundIsTheReasonAndNothingElse() throws IOException {
        Case c = cases().get("not-found").get();
        serve(c);
        EntityExchangeResult<byte[]> r = exchange(sheetUri(DOC, c.observation()));
        assertThat(r.getStatus().value()).isEqualTo(404);
        assertThat(json.readTree(r.getResponseBody())).isEqualTo(json.readTree("{\"reason\":\"NOT_FOUND\"}"));
        verify(results, never()).result(any(), any());
    }

    // ---- the contract ---------------------------------------------------------------------------------------

    /**
     * The value is the paper's own row, field by field, and the sheet's paper head is the paper's own fields: both read
     * from {@code GET /app/api/documents/{id}} over the same mocked read, for every case that opens a sheet.
     */
    @Test
    void theValueIsThePapersOwnRow() throws IOException {
        int compared = 0;
        for (Map.Entry<String, Supplier<Case>> e : cases().entrySet()) {
            Case c = e.getValue().get();
            if (c.notFound()) {
                continue;
            }
            serve(c);
            JsonNode sheet = sheet(c);
            EntityExchangeResult<byte[]> p = exchange("/app/api/documents/" + DOC);
            assertThat(p.getStatus().value()).as(e.getKey() + ": the paper").isEqualTo(200);
            JsonNode paper = json.readTree(p.getResponseBody());
            JsonNode row = null;
            for (JsonNode v : paper.path("values")) {
                if (v.path("id").asText().equals(c.observation().toString())) {
                    row = v;
                }
            }
            assertThat(row).as(e.getKey() + ": the paper sends a row with the observation's id").isNotNull();
            assertThat(fieldNames(sheet.path("value"))).as(e.getKey() + ": $.value's fields").isEqualTo(VALUE_FIELDS);
            assertThat(sheet.path("value")).as(e.getKey() + ": $.value is the paper's row").isEqualTo(row);

            JsonNode head = sheet.path("paper");
            assertThat(fieldNames(head)).as(e.getKey() + ": $.paper's fields")
                    .isEqualTo(Set.of("id", "document_type", "date", "date_is_upload_day", "date_uncertain", "patient"));
            for (String field : List.of("id", "document_type", "date", "date_is_upload_day", "date_uncertain")) {
                assertThat(head.path(field)).as(e.getKey() + ": $.paper." + field).isEqualTo(paper.path(field));
            }
            assertThat(fieldNames(head.path("patient"))).isEqualTo(Set.of("id", "name", "name_possessive"));
            for (String field : List.of("id", "name", "name_possessive")) {
                assertThat(head.path("patient").path(field)).as(e.getKey() + ": $.paper.patient." + field)
                        .isEqualTo(paper.path("patient").path(field));
            }
            compared++;
        }
        assertThat(compared).as("cases compared").isEqualTo(cases().size() - 1);
    }

    /** Each earlier reading beside its own paper's strip (DR-12), with that paper's stored size, never this paper's. */
    @Test
    void everyEarlierReadingCarriesItsOwnCrop() throws IOException {
        Case c = cases().get("earlier-three").get();
        serve(c);
        JsonNode earlier = sheet(c).path("earlier");
        assertThat(earlier.size()).as("$.earlier").isEqualTo(3);
        Set<String> documents = new HashSet<>();
        for (int i = 0; i < earlier.size(); i++) {
            JsonNode e = earlier.get(i);
            EarlierReading served = c.earlier().get(i);
            UUID document = UUID.fromString(e.path("document_id").asText());
            documents.add(document.toString());
            assertThat(document).as("$.earlier[" + i + "].document_id").isEqualTo(served.documentId());
            assertThat(e.path("observation_id").asText()).isEqualTo(served.observationId().toString());
            assertThat(e.path("value").asText()).isEqualTo(served.value());
            assertThat(e.path("unit").asText()).isEqualTo(served.unit());
            assertThat(e.path("date").asText()).isEqualTo(served.date().toString());
            assertThat(e.path("date_is_upload_day").booleanValue()).isEqualTo(served.dateIsFallback());
            String src = e.path("crop").path("src").asText();
            assertThat(src).as("$.earlier[" + i + "].crop.src").isEqualTo(ImageRefs.crop(document, served.cropKey()));
            assertThat(src).as("$.earlier[" + i + "].crop.src names its own paper").startsWith("/app/documents/" + document + "/image?");
            StoredObject ledger = earlierLedger(document).get(0);
            if (ledger.widthPx() == null) {
                assertThat(e.path("crop").path("width").isNull()).as("an unmeasured crop has no width").isTrue();
                assertThat(e.path("crop").path("height").isNull()).as("an unmeasured crop has no height").isTrue();
            } else {
                assertThat(e.path("crop").path("width").asInt()).as("$.earlier[" + i + "].crop.width").isEqualTo(ledger.widthPx());
                assertThat(e.path("crop").path("height").asInt()).as("$.earlier[" + i + "].crop.height").isEqualTo(ledger.heightPx());
            }
        }
        // the readings are the mock's own, so "none of them is this paper" could not fail here; that rule lives in SQL
        // (readingsOf's "< me" tuple) and is asserted over Postgres in oneSheetReadIsOneView
        assertThat(documents).as("three different earlier papers").hasSize(3);
    }

    /**
     * An earlier paper's crop sizes are the ones its ledger already holds: never measured from here (measuring fetches
     * every never-tried crop on that paper, under the process-wide fetch gate, for a paper the family did not open). Only
     * this paper's crops are measured, as its own JSON measures them. EARLIER_3's crop was never measured, and stays so.
     */
    @Test
    void earlierPapersAreReadFromTheLedgerAndNeverMeasured() throws IOException {
        Case c = cases().get("earlier-three").get();
        serve(c);
        JsonNode earlier = sheet(c).path("earlier");
        assertThat(earlier.size()).as("$.earlier").isEqualTo(3);
        for (UUID d : List.of(EARLIER_1, EARLIER_2, EARLIER_3)) {
            verify(storage, never()).measuredObjectsOf(d, ObjectKind.CROP);
        }
        verify(storage).measuredObjectsOf(DOC, ObjectKind.CROP);
        assertThat(earlier.get(0).path("crop").path("width").asInt()).as("EARLIER_1's stored size, from its ledger")
                .isEqualTo(earlierLedger(EARLIER_1).get(0).widthPx());
        assertThat(earlier.get(2).path("crop").path("width").isNull()).as("EARLIER_3's never-measured crop goes out unsized").isTrue();
    }

    /**
     * Sizes are best effort: once the view is recorded a failed size read never fails the answer. One earlier paper's
     * failed ledger read blanks that paper's sizes only; this paper's failed read blanks this paper's only.
     */
    @Test
    void aFailedSizeReadSendsTheSheetWithoutSizes() throws IOException {
        Case c = cases().get("earlier-three").get();
        serve(c);
        when(storage.objectsOf(EARLIER_2)).thenReturn(Flux.error(new IllegalStateException("connection refused")));
        JsonNode one = sheet(c);
        assertThat(one.path("earlier").get(1).path("document_id").asText()).isEqualTo(EARLIER_2.toString());
        assertThat(one.path("earlier").get(1).path("crop").path("width").isNull()).as("$.earlier[1].crop.width").isTrue();
        assertThat(one.path("earlier").get(1).path("crop").path("height").isNull()).as("$.earlier[1].crop.height").isTrue();
        assertThat(one.path("earlier").get(1).path("crop").path("src").asText()).as("the crop itself is still sent")
                .isEqualTo(ImageRefs.crop(EARLIER_2, earlierCrop(EARLIER_2)));
        assertThat(one.path("earlier").get(0).path("crop").path("width").asInt()).as("EARLIER_1's size survives EARLIER_2's failure")
                .isEqualTo(earlierLedger(EARLIER_1).get(0).widthPx());

        // this paper's failure, on a case whose value crop IS sized when the read works (so the null below is the failure's)
        Case sized = cases().get("outside-high").get();
        serve(sized);
        assertThat(sheet(sized).path("value").path("crop").path("width").isNumber()).as("outside-high's value crop is sized").isTrue();
        when(storage.measuredObjectsOf(DOC, ObjectKind.CROP)).thenReturn(Flux.error(new IllegalStateException("connection refused")));
        JsonNode two = sheet(sized);
        assertThat(two.path("value").path("crop").path("width").isNull()).as("$.value.crop.width").isTrue();
        assertThat(two.path("value").path("crop").path("height").isNull()).as("$.value.crop.height").isTrue();
        assertThat(two.path("earlier").get(0).path("crop").path("width").asInt()).as("the earlier sizes survive this paper's failure")
                .isEqualTo(earlierLedger(EARLIER_1).get(0).widthPx());
    }

    /** A printed range with one bound and a stored INSIDE the code cannot prove: UNKNOWN here as on the paper. */
    @Test
    void aOneSidedInsideIsUnknownOnTheSheetToo() throws IOException {
        Case c = cases().get("unknown-one-sided").get();
        serve(c);
        JsonNode value = sheet(c).path("value");
        assertThat(value.path("display_kind").asText()).isEqualTo("unknown");
        assertThat(value.path("chip_key").asText()).isEqualTo(PaperDisplay.chipKey(ValueVerdict.Kind.UNKNOWN, null))
                .isNotEqualTo("value.chip.inside");
        assertThat(value.path("pin").isNull()).as("no pin for an UNKNOWN").isTrue();
        assertThat(value.path("side").isNull()).isTrue();

        // the other direction: the same paper's two-sided INSIDE is inside
        Map<String, Object> hb = PaperFixtures.haemoglobinInside();
        Case inside = Case.of(c.paper(), hb, List.of());
        serve(inside);
        assertThat(sheet(inside).path("value").path("display_kind").asText()).isEqualTo("inside");
    }

    /**
     * The heading line is Java's, as the HTML sheet draws it: no history claimed for a value whose marker is not known
     * (never "the first time"), the first time only for a known marker with no earlier reading, and the heading with or
     * without the unit otherwise.
     */
    @Test
    void theEarlierLineSaysOnlyWhatMedioosHistoryCanSay() throws IOException {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("no-history", null);
        expected.put("earlier-none", "sheet.earlier.none");
        // the dangerous direction: no reading in this unit, but the marker WAS read before (in another unit), so "the
        // first time" would be false; no line at all, as for a marker Medioo does not know
        expected.put("earlier-other-unit", null);
        expected.put("earlier-three", "sheet.earlier");
        for (Map.Entry<String, String> e : expected.entrySet()) {
            Case c = cases().get(e.getKey()).get();
            serve(c);
            JsonNode body = sheet(c);
            assertThat(body.has("earlier_line_key")).as(e.getKey() + ": $.earlier_line_key is present").isTrue();
            JsonNode line = body.path("earlier_line_key");
            assertThat(line.isNull() || line.isTextual()).as(e.getKey() + ": $.earlier_line_key is null or a key").isTrue();
            assertThat(line.isNull() ? null : line.asText()).as(e.getKey() + ": $.earlier_line_key").isEqualTo(e.getValue());
        }
        Map<String, Object> unitless = PaperFixtures.value("ESR", "30", null, null, "20", "outside", "high");
        DocumentView view = PaperFixtures.paper(List.of(unitless), List.of(), List.of(), Map.of());
        Case c = Case.of(PaperFixtures.result(view, Urgency.THIS_WEEK, List.of("value_outside_range:esr|stopgap"), GateState.RELEASED,
                "ESR is above the printed range.", PaperFixtures.BD), unitless, List.of(earlier(EARLIER_1, 6, "25", null, LocalDate.of(2026, 7, 11), false)));
        serve(c);
        assertThat(sheet(c).path("earlier_line_key").asText()).as("a unitless value's heading").isEqualTo("sheet.earlier.nounit");

        // readings in this unit AND in another: the heading names this unit, as it does with none in another
        Case both = cases().get("earlier-three").get().alsoReadInAnotherUnit();
        serve(both);
        assertThat(sheet(both).path("earlier_line_key").asText()).as("earlier-three, also read in another unit").isEqualTo("sheet.earlier");
    }

    /**
     * A paper that is not DONE shows none of its rows (PaperJson), so none opens a sheet either and no view is recorded:
     * a read the system rejected (NEEDS_RETAKE, FAILED) and a paper still being read (PROCESSING) holding stored rows.
     */
    @ParameterizedTest
    @ValueSource(strings = {"NEEDS_RETAKE", "FAILED", "PROCESSING"})
    void aRejectedPapersRowOpensNoSheet(String status) throws IOException {
        ResultView rejected = PaperFixtures.rejectedWithRows(status, "PROCESSING".equals(status) ? null : "low_overall");
        assertThat(rejected.document().values()).as("the " + status + " paper holds a stored row").isNotEmpty();
        Case c = Case.of(rejected, rejected.document().values().get(0), List.of());
        serve(c);
        EntityExchangeResult<byte[]> r = exchange(sheetUri(DOC, c.observation()));
        assertThat(r.getStatus().value()).as("a " + status + " paper's stored row").isEqualTo(404);
        assertThat(json.readTree(r.getResponseBody())).isEqualTo(json.readTree("{\"reason\":\"NOT_FOUND\"}"));
        verify(results, never()).result(any(), any());
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            names.add(it.next());
        }
        return names;
    }

    // ---- the audit, over Postgres ---------------------------------------------------------------------------

    /** Ma with two DONE lab reports, each with one potassium reading (canonical, so it has a history) and its own crop. */
    record Papers(String owner, String stranger, UUID older, UUID newer, UUID olderObservation, UUID newerObservation) {
    }

    private static Papers papers(org.springframework.context.ApplicationContext context, String ownerNumber, String strangerNumber) throws Exception {
        FamilyAccount owner = AppApiContext.family(context, ownerNumber, "Tanvir");
        PatientProfile ma = AppApiContext.patient(context, owner.id(), "Ma");
        UUID older = AppApiContext.paper(context, POSTGRES, ma, "routine");
        AppApiContext.sql(POSTGRES, "UPDATE baymax.document SET doc_date = '2026-07-11' WHERE id = '" + older + "'");
        UUID newer = AppApiContext.paper(context, POSTGRES, ma, "routine");
        AppApiContext.sql(POSTGRES, "UPDATE baymax.document SET doc_date = '2026-10-12' WHERE id = '" + newer + "'");
        UUID olderObservation = potassium(ma, older, "5.4", 1);
        UUID newerObservation = potassium(ma, newer, "6.9", 1);
        // a second potassium on the newer paper itself: same patient, marker and unit, so only "earlier" keeps it out
        potassium(ma, newer, "6.7", 2);
        FamilyAccount stranger = AppApiContext.family(context, strangerNumber, "Karim");
        return new Papers(AppApiContext.session(context, owner.id()), AppApiContext.session(context, stranger.id()),
                older, newer, olderObservation, newerObservation);
    }

    /** One potassium row on the paper, under its own crop ({@code crop-<n>}); the id the database gave it. */
    private static UUID potassium(PatientProfile patient, UUID document, String value, int n) throws Exception {
        return UUID.fromString(scalar("INSERT INTO baymax.observation (patient_id, document_id, name, canonical_name, value, unit, ref_low, ref_high,"
                + " crop_key, observed_at) VALUES ('" + patient.id() + "','" + document + "','Potassium','potassium','" + value
                + "','mmol/L','3.5','5.1','" + patient.familyId() + "/" + patient.id() + "/" + document + "/crop-" + n + ".jpg',now()) RETURNING id"));
    }

    @Test
    void anObservationOfAnotherPaperIsNotFoundAndRecordsNoView() {
        AppApiContext.runner(POSTGRES).run(context -> {
            Papers p = papers(context, "+8801700000711", "+8801700000712");
            WebTestClient c = WebTestClient.bindToApplicationContext(context).build();
            long before = views();

            // the older paper's observation asked for on the newer paper: both are the owner's, so only the pairing is wrong
            EntityExchangeResult<byte[]> r = c.get().uri(sheetUri(p.newer(), p.olderObservation())).cookie("baymax_session", p.owner())
                    .exchange().expectBody().returnResult();
            assertThat(r.getStatus().value()).as("another paper's observation").isEqualTo(404);
            assertThat(json.readTree(r.getResponseBody())).isEqualTo(json.readTree("{\"reason\":\"NOT_FOUND\"}"));
            assertThat(views() - before).as("DOCUMENT_VIEW rows recorded by the probe").isEqualTo(0);

            // another family's probe of a real pair
            EntityExchangeResult<byte[]> s = c.get().uri(sheetUri(p.newer(), p.newerObservation())).cookie("baymax_session", p.stranger())
                    .exchange().expectBody().returnResult();
            assertThat(s.getStatus().value()).as("another family's sheet").isEqualTo(404);
            assertThat(views() - before).as("DOCUMENT_VIEW rows recorded by both probes").isEqualTo(0);
        });
    }

    @Test
    void oneSheetReadIsOneView() {
        AppApiContext.runner(POSTGRES).run(context -> {
            Papers p = papers(context, "+8801700000721", "+8801700000722");
            WebTestClient c = WebTestClient.bindToApplicationContext(context).build();
            long before = views();
            long beforeOnPaper = views(p.newer());

            EntityExchangeResult<byte[]> r = c.get().uri(sheetUri(p.newer(), p.newerObservation())).cookie("baymax_session", p.owner())
                    .exchange().expectBody().returnResult();
            assertThat(r.getStatus().value()).as("the owner's sheet").isEqualTo(200);
            JsonNode body = json.readTree(r.getResponseBody());
            assertThat(body.path("value").path("id").asText()).isEqualTo(p.newerObservation().toString());
            assertThat(body.path("value").path("value").asText()).isEqualTo("6.9");
            assertThat(views() - before).as("DOCUMENT_VIEW rows after one sheet read").isEqualTo(1);
            assertThat(views(p.newer()) - beforeOnPaper).as("the view is the sheet's own paper's").isEqualTo(1);

            // the earlier reading is the older paper's, beside the older paper's own crop; never a row of the viewed paper
            // (its second potassium is the same patient, marker and unit, so only readingsOf's "< me" keeps it out)
            JsonNode earlier = body.path("earlier");
            for (JsonNode e : earlier) {
                assertThat(e.path("document_id").asText()).as("an earlier reading on the viewed paper itself").isNotEqualTo(p.newer().toString());
            }
            assertThat(earlier.size()).as("$.earlier over real rows").isEqualTo(1);
            assertThat(earlier.get(0).path("document_id").asText()).isEqualTo(p.older().toString());
            assertThat(earlier.get(0).path("observation_id").asText()).isEqualTo(p.olderObservation().toString());
            assertThat(earlier.get(0).path("crop").path("src").asText()).startsWith("/app/documents/" + p.older() + "/image?");
            assertThat(body.path("earlier_line_key").asText()).isEqualTo("sheet.earlier");
        });
    }

    /** Every DOCUMENT_VIEW row, by the kind as the adapter stores it. */
    private static long views() throws Exception {
        return AppApiContext.count(POSTGRES, "select count(*) from baymax.audit_event where kind = '" + AuditEvent.Kind.DOCUMENT_VIEW.dbValue() + "'");
    }

    private static long views(UUID paper) throws Exception {
        return AppApiContext.count(POSTGRES, "select count(*) from baymax.audit_event where kind = '" + AuditEvent.Kind.DOCUMENT_VIEW.dbValue()
                + "' and document_id = '" + paper + "'");
    }

    private static String scalar(String query) throws Exception {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl() + "&sslmode=disable", POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement(); ResultSet r = s.executeQuery(query)) {
            r.next();
            return r.getString(1);
        }
    }
}
