package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.PatientAccess;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
import com.elioo.baymax.testsupport.Json;
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
import com.elioo.baymax.web.application.port.out.HealthReadPort;
import com.elioo.baymax.web.application.service.HealthService;
import com.elioo.baymax.web.domain.LatestPrescription;
import com.elioo.baymax.web.domain.PaperDisplay;
import com.elioo.baymax.web.domain.WebSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Plan B6 step 5: {@code GET /app/api/patients/{id}/labs} and {@code /medicines} through the real {@link AppApiRouter}
 * and {@link AppApiGuard}, the real {@link HealthService} (the joins are its own, not a mock's) over a mocked read port,
 * and the real {@link UiCopy}. Every assertion reads the parsed tree by field; none matches a substring of the document.
 *
 * <p>The cases React's MSW handlers serve are written here too, in the generated-file envelope, and compared byte for byte
 * with the committed {@code medioo-web/fixtures/api/health/*.json}. The blood-pressure rows carry the line exactly as
 * extraction writes it for {@code BP : 130 / 80} ({@code BloodPressurePairAcceptanceTest}: {@code "1:0-13"}) and one crop
 * per value, never a shared key; that the pair survives the real extraction path is that test's, end to end.
 */
class HealthApiContractTest {

    private static final Path COMMITTED = Path.of("../medioo-web/fixtures/api/health");
    private static final Path WRITTEN = Path.of("build/web-contract/health");
    private static final Instant NOW = Instant.parse("2026-10-21T09:00:00Z");
    static final UUID FAMILY = UUID.fromString("b6b6b6b6-0000-4000-8000-000000000001");
    static final UUID MA = UUID.fromString("b6b6b6b6-0000-4000-8000-000000000002");
    private static final UUID LAB_OCT = UUID.fromString("b6b6b6b6-0000-4000-8000-0000000000d1");
    private static final UUID LAB_AUG = UUID.fromString("b6b6b6b6-0000-4000-8000-0000000000d2");
    private static final UUID LAB_UNDATED = UUID.fromString("b6b6b6b6-0000-4000-8000-0000000000d3");
    private static final UUID RX = UUID.fromString("b6b6b6b6-0000-4000-8000-0000000000d4");
    private static final UUID RX_READING = UUID.fromString("b6b6b6b6-0000-4000-8000-0000000000d5");

    private final ObjectMapper json = new ObjectMapper();
    private final WebAuthUseCase auth = mock(WebAuthUseCase.class);
    private final HealthRecordPort records = mock(HealthRecordPort.class);
    private final HealthReadPort reads = mock(HealthReadPort.class);
    private final HealthUseCase health = new HealthService(records, reads);
    private final UiCopy copy = new UiCopy();
    private final WebTestClient client;

    HealthApiContractTest() {
        BaymaxProperties properties = new BaymaxProperties();
        properties.getWeb().setPublicOrigin(AppApiGuardTest.ORIGIN);
        when(auth.authenticate(anyString())).thenReturn(Mono.empty());
        when(auth.authenticate("tok")).thenReturn(Mono.just(new WebSession(UUID.randomUUID(), FAMILY, NOW, NOW, NOW.plusSeconds(600))));
        when(records.visiblePatient(any(), any())).thenReturn(Mono.empty());
        when(records.visiblePatient(FAMILY, MA)).thenReturn(Mono.just(new PatientAccess(
                new PatientProfile(MA, FAMILY, "Ma", 64, PatientProfile.Sex.FEMALE, List.of(), NOW, NOW), true)));
        SessionAuthFilter sessions = new SessionAuthFilter(auth, properties);
        AppApiGuard guard = new AppApiGuard(sessions, properties);
        TimelineUseCase timeline = mock(TimelineUseCase.class);
        client = WebTestClient.bindToRouterFunction(new AppApiRouter().mediooAppApiRoutes(
                new MeApiHandler(timeline, mock(AttentionUseCase.class), properties), new SignInApiHandler(auth, sessions, properties),
                new PaperApiHandler(mock(ResultUseCase.class), copy, mock(DocumentStorageUseCase.class)),
                new UploadApiHandler(mock(UploadUseCase.class), properties, copy),
                new PaperStatusApiHandler(mock(ResultUseCase.class)),
                new PaperDeleteApiHandler(timeline, new DeleteConfirmation(properties, java.time.Clock.systemUTC())),
                new HealthApiHandler(health),
                guard)).build();
    }

    // ---- the cases ------------------------------------------------------------------------------------------

    /** A case: the route, and what the read port serves for it. */
    record Case(String route, List<Map<String, Object>> points, LatestPrescription latest) {
        static Case labs(List<Map<String, Object>> points) {
            return new Case("labs", points, null);
        }

        static Case medicines(LatestPrescription latest) {
            return new Case("medicines", List.of(), latest);
        }
    }

    static Map<String, Supplier<Case>> cases() {
        Map<String, Supplier<Case>> cases = new LinkedHashMap<>();
        cases.put("labs-some", () -> Case.labs(labsSome()));
        cases.put("labs-none", () -> Case.labs(List.of()));
        cases.put("bp", () -> Case.labs(bpRows()));
        cases.put("medicines-latest", () -> Case.medicines(new LatestPrescription(RX, LocalDate.parse("2026-10-12"), false, true,
                List.of(medicine("Tab. Demoprol", "50 mg", "1+0+1", "after food", "30 days", 1),
                        medicine("Tab. Fictazide", "25 mg", null, null, null, 2)), Optional.empty())));
        cases.put("medicines-empty-latest", () -> Case.medicines(new LatestPrescription(RX, LocalDate.parse("2026-10-12"), false, false,
                List.of(), Optional.empty())));
        cases.put("medicines-newer-unread", () -> Case.medicines(new LatestPrescription(RX, LocalDate.parse("2026-10-12"), false, false,
                List.of(medicine("Tab. Demoprol", "50 mg", "1+0+1", null, null, 1)), Optional.of(RX_READING))));
        cases.put("medicines-none", () -> Case.medicines(LatestPrescription.none(Optional.empty())));
        return cases;
    }

    static Stream<String> caseNames() {
        return cases().keySet().stream();
    }

    /** Creatinine in µmol/L (MICRO SIGN) and umol/L on two papers (one series), a one-sided cholesterol, a potassium high. */
    static List<Map<String, Object>> labsSome() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> creatinineOct = row(LAB_OCT, 11, "2026-10-12", "S. Creatinine", "creatinine", "132", "µmol/L", "59", "104");
        creatinineOct.put("verdict", "outside");
        creatinineOct.put("verdict_side", "high");
        creatinineOct.put("date_uncertain", true);          // a printed date the model marked uncertain (D2, Q6)
        rows.add(creatinineOct);
        Map<String, Object> potassium = row(LAB_OCT, 12, "2026-10-12", "Potassium", "potassium", "5.9", "mmol/L", "3.5", "5.1");
        potassium.put("verdict", "outside");
        potassium.put("verdict_side", "high");
        potassium.put("date_uncertain", true);
        rows.add(potassium);
        Map<String, Object> cholesterol = row(LAB_AUG, 21, "2026-08-03", "Total Cholesterol", "total_cholesterol", "180", "mg/dL", null, "200");
        cholesterol.put("verdict", "inside");                // one-sided: never sent inside
        rows.add(cholesterol);
        Map<String, Object> creatinineAug = row(LAB_AUG, 22, "2026-08-03", "Creatinine", "creatinine", "98", "umol/L", "59", "104");
        creatinineAug.put("verdict", "inside");
        rows.add(creatinineAug);
        Map<String, Object> undated = row(LAB_UNDATED, 31, "2026-06-20", "Creatinine", "creatinine", "90", "umol/L", "59", "104");
        undated.put("verdict", "inside");
        undated.put("date_is_fallback", true);               // nothing printed: the Dhaka upload day, never uncertain
        rows.add(undated);
        return rows;
    }

    /** One BP : 130 / 80 line read through extraction: two values, two crops, one line. */
    static List<Map<String, Object>> bpRows() {
        Map<String, Object> systolic = row(LAB_OCT, 41, "2026-10-12", "BP", "bp_systolic", "130", "mmHg", null, null);
        systolic.put("source_line", "1:0-13");
        Map<String, Object> diastolic = row(LAB_OCT, 42, "2026-10-12", "BP", "bp_diastolic", "80", "mmHg", null, null);
        diastolic.put("source_line", "1:0-13");
        return List.of(systolic, diastolic);
    }

    private static Map<String, Object> row(UUID document, int n, String date, String name, String canonical, String value, String unit,
                                           String low, String high) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", UUID.fromString(String.format("b6b6b6b6-0000-4000-8000-0000000001%02d", n)));
        m.put("document_id", document);
        m.put("name", name);
        m.put("canonical_name", canonical);
        m.put("value", value);
        m.put("unit", unit);
        if (low != null) {
            m.put("ref_low", low);
        }
        if (high != null) {
            m.put("ref_high", high);
        }
        m.put("crop_key", "f/p/" + document + "/crop-v" + n + ".jpg");
        m.put("date", LocalDate.parse(date));
        m.put("date_is_fallback", false);
        m.put("date_uncertain", false);
        return m;
    }

    private static Map<String, Object> medicine(String name, String dose, String frequency, String timing, String duration, int n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("dose_text", dose);
        if (frequency != null) {
            m.put("frequency_text", frequency);
        }
        if (timing != null) {
            m.put("timing_text", timing);
        }
        if (duration != null) {
            m.put("duration_text", duration);
        }
        m.put("action", "stopped");          // stored on the row; never sent
        m.put("crop_key", "f/p/" + RX + "/crop-m" + n + ".jpg");
        return m;
    }

    private EntityExchangeResult<byte[]> serve(Case c) {
        when(reads.labPoints(MA)).thenReturn(Flux.fromIterable(c.points()));
        if (c.latest() != null) {
            when(reads.latestPrescription(MA)).thenReturn(Mono.just(c.latest()));
        }
        return exchange("/app/api/patients/" + MA + "/" + c.route(), "tok");
    }

    private JsonNode get(Case c) throws IOException {
        EntityExchangeResult<byte[]> r = serve(c);
        assertThat(r.getStatus().value()).as("GET " + c.route()).isEqualTo(200);
        return json.readTree(r.getResponseBody());
    }

    private EntityExchangeResult<byte[]> exchange(String uri, String token) {
        WebTestClient.RequestHeadersSpec<?> spec = client.get().uri(uri);
        if (token != null) {
            spec = spec.cookie("baymax_session", token);
        }
        return spec.exchange().expectBody().returnResult();
    }

    @ParameterizedTest
    @MethodSource("caseNames")
    void eachCaseIsItsCommittedMswFixture(String name) throws IOException {
        Case c = cases().get(name).get();
        EntityExchangeResult<byte[]> r = serve(c);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("_meta", Snapshot.meta(HealthApiContractTest.class, "GET /app/api/patients/{id}/" + c.route() + " over the case " + name));
        envelope.put("status", r.getStatus().value());
        envelope.put("body", json.readValue(r.getResponseBody(), Object.class));
        String file = name + ".json";
        Snapshot.matches(COMMITTED.resolve(file), WRITTEN.resolve(file), Snapshot.json(envelope),
                "cp baymax/" + WRITTEN.resolve(file) + " medioo-web/fixtures/api/health/" + file);
    }

    /** Both ways: every committed fixture is a current case, so a renamed or removed case leaves no stale body for MSW. */
    @Test
    void everyCommittedFixtureIsACurrentCase() throws IOException {
        List<String> committed;
        try (Stream<Path> files = java.nio.file.Files.list(COMMITTED)) {
            committed = files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json")).sorted().toList();
        }
        assertThat(committed).as("the committed fixtures in " + COMMITTED.toAbsolutePath()).isNotEmpty();
        assertThat(committed).containsExactlyInAnyOrderElementsOf(caseNames().map(n -> n + ".json").toList());
    }

    // ---- the contract ----------------------------------------------------------------------------------------

    /** The JSON is the service's series and pair, field by field: nothing re-joined, re-ordered or re-judged on the way out. */
    @Test
    void labsAreTheServicesSeriesAsJson() throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>(labsSome());
        rows.addAll(bpRows());
        JsonNode body = get(Case.labs(rows));
        HealthUseCase.HealthLabs labs = health.labs(FAMILY, MA).block();

        assertThat(fieldNames(body)).containsExactly("series", "bp");
        assertThat(body.path("series")).hasSize(labs.series().size());
        JsonNode creatinine = body.path("series").get(0);
        assertThat(creatinine.path("canonical_name").asText()).isEqualTo("creatinine");
        assertThat(creatinine.path("unit").asText()).as("the newest point's unit, as printed").isEqualTo("µmol/L");
        assertThat(creatinine.path("points")).as("µmol/L and umol/L are one series").hasSize(3);
        for (int i = 0; i < labs.series().size(); i++) {
            var s = labs.series().get(i);
            JsonNode js = body.path("series").get(i);
            assertThat(fieldNames(js)).containsExactly("canonical_name", "name", "unit", "points");
            assertThat(js.path("canonical_name").asText()).isEqualTo(s.canonicalName());
            assertThat(js.path("name").asText()).isEqualTo(s.name());
            for (int j = 0; j < s.points().size(); j++) {
                assertPoint(js.path("points").get(j), s.points().get(j));
            }
        }
        assertThat(body.path("bp").path("points")).hasSize(1);
        JsonNode pair = body.path("bp").path("points").get(0);
        assertThat(fieldNames(pair)).containsExactly("document_id", "date", "date_is_upload_day", "date_uncertain", "systolic", "diastolic");
        assertPoint(pair.path("systolic"), labs.bp().points().get(0).systolic());
        assertPoint(pair.path("diastolic"), labs.bp().points().get(0).diastolic());
        assertThat(pair.path("systolic").path("value").asText()).isEqualTo("130");
        assertThat(pair.path("diastolic").path("value").asText()).isEqualTo("80");
        assertThat(pair.path("systolic").path("crop").path("src").asText()).as("each half keeps its own crop")
                .isNotEqualTo(pair.path("diastolic").path("crop").path("src").asText());

        List<String> joinKeys = new ArrayList<>();
        Json.walk(body, (path, node) -> {
            if (path.endsWith("unit_fold") || path.endsWith("source_line")) {
                joinKeys.add(path);
            }
        });
        assertThat(joinKeys).as("join keys are not display values").isEmpty();
    }

    private static void assertPoint(JsonNode j, com.elioo.baymax.web.domain.LabSeries.Point p) {
        assertThat(fieldNames(j)).containsExactly("document_id", "observation_id", "value", "date", "date_is_upload_day", "date_uncertain",
                "kind", "side", "chip_key", "crop");
        assertThat(j.path("document_id").asText()).isEqualTo(p.documentId().toString());
        assertThat(j.path("observation_id").asText()).isEqualTo(p.observationId().toString());
        assertThat(j.path("value").asText()).isEqualTo(p.value());
        assertThat(j.path("date").asText()).isEqualTo(p.date().toString());
        assertThat(j.path("date_is_upload_day").asBoolean()).isEqualTo(p.dateIsUploadDay());
        assertThat(j.path("date_uncertain").asBoolean()).isEqualTo(p.dateUncertain());
        assertThat(j.path("kind").asText()).isEqualTo(p.kind().name().toLowerCase(java.util.Locale.ROOT));
        assertThat(j.path("side").isNull() ? null : j.path("side").asText()).isEqualTo(p.side());
        assertThat(j.path("chip_key").asText()).isEqualTo(PaperDisplay.chipKey(p.kind(), p.side()));
        assertThat(fieldNames(j.path("crop"))).containsExactly("src");
        assertThat(j.path("crop").path("src").asText()).isEqualTo(ImageRefs.crop(p.documentId(), p.cropKey()));
    }

    @Test
    void aOneSidedPointIsSentUnknown() throws IOException {
        JsonNode body = get(Case.labs(labsSome()));
        JsonNode cholesterol = null;
        for (JsonNode s : body.path("series")) {
            if (s.path("canonical_name").asText().equals("total_cholesterol")) {
                cholesterol = s.path("points").get(0);
            }
        }
        assertThat(cholesterol).as("the one-sided cholesterol point").isNotNull();
        assertThat(cholesterol.path("kind").asText()).isEqualTo("unknown");
        assertThat(cholesterol.path("chip_key").asText()).isEqualTo("value.chip.unknown");
        assertThat(cholesterol.path("side").isNull()).isTrue();
    }

    /** D2 on the health screen: the mark is the paper's, each way — a marked printed date is true, an upload day false. */
    @Test
    void theUncertainMarkIsSentEachWay() throws IOException {
        JsonNode points = get(Case.labs(labsSome())).path("series").get(0).path("points");
        Map<String, JsonNode> byDocument = new HashMap<>();
        points.forEach(p -> byDocument.put(p.path("document_id").asText(), p));
        assertThat(byDocument.get(LAB_OCT.toString()).path("date_uncertain").asBoolean()).as("a DONE paper whose printed date is marked").isTrue();
        assertThat(byDocument.get(LAB_OCT.toString()).path("date_is_upload_day").asBoolean()).isFalse();
        assertThat(byDocument.get(LAB_UNDATED.toString()).path("date_uncertain").asBoolean()).as("a point on an undated paper").isFalse();
        assertThat(byDocument.get(LAB_UNDATED.toString()).path("date_is_upload_day").asBoolean()).isTrue();
        assertThat(byDocument.get(LAB_UNDATED.toString()).path("date_uncertain").isBoolean()).isTrue();

        JsonNode latest = get(cases().get("medicines-latest").get());
        assertThat(latest.path("date_uncertain").asBoolean()).isTrue();
        JsonNode empty = get(cases().get("medicines-empty-latest").get());
        assertThat(empty.path("date_uncertain").isBoolean()).isTrue();
        assertThat(empty.path("date_uncertain").asBoolean()).isFalse();
    }

    @Test
    void medicinesCarryOnlyVerbatimFieldsAndACrop() throws IOException {
        JsonNode body = get(cases().get("medicines-latest").get());
        assertThat(body.path("medicines")).hasSize(2);
        Set<String> allowed = new HashSet<>(PaperDisplay.MEDICINE_FIELDS);
        allowed.add("crop");
        for (JsonNode medicine : body.path("medicines")) {
            assertThat(allowed).as("keys of " + medicine).containsAll(fieldNames(medicine));
            assertThat(fieldNames(medicine.path("crop"))).containsExactly("src");
            assertThat(medicine.path("crop").path("src").asText()).startsWith(ImageRefs.crop(RX, "x").substring(0, ImageRefs.crop(RX, "x").indexOf("?")));
        }
        assertThat(body.path("medicines").get(0).path("name").asText()).isEqualTo("Tab. Demoprol");
        assertThat(body.path("medicines").get(0).path("timing_text").asText()).isEqualTo("after food");
        assertThat(body.path("medicines").get(1).has("frequency_text")).as("a field the line was not written with is absent").isFalse();
        List<String> derived = new ArrayList<>();
        Json.walk(body, (path, node) -> {
            String key = path.substring(path.lastIndexOf('.') + 1);
            if (Set.of("action", "purpose", "status", "current").contains(key)) {
                derived.add(path);
            }
        });
        assertThat(derived).as("nothing derived anywhere in the tree").isEmpty();
        assertThat(body.path("heading_key").asText()).isEqualTo(HealthJson.MEDICINES_HEADING_KEY);
        assertThat(body.path("document_id").asText()).isEqualTo(RX.toString());
        assertThat(body.path("date").asText()).isEqualTo("2026-10-12");
        assertThat(body.path("newer_prescription_not_read").asBoolean()).isFalse();
        assertThat(body.path("newer_document_id").isNull()).isTrue();
    }

    /** Decision 9: the newest prescription with no medicines is sent as itself, dated and empty. */
    @Test
    void anEmptyLatestIsSentEmptyWithItsDate() throws IOException {
        JsonNode body = get(cases().get("medicines-empty-latest").get());
        assertThat(body.path("document_id").asText()).isEqualTo(RX.toString());
        assertThat(body.path("date").asText()).isEqualTo("2026-10-12");
        assertThat(body.path("medicines").isArray()).isTrue();
        assertThat(body.path("medicines")).isEmpty();
    }

    @Test
    void aNewerUnreadPrescriptionIsSaid() throws IOException {
        JsonNode body = get(cases().get("medicines-newer-unread").get());
        assertThat(body.path("newer_prescription_not_read").asBoolean()).isTrue();
        assertThat(body.path("newer_document_id").asText()).isEqualTo(RX_READING.toString());
        assertThat(body.path("document_id").asText()).as("the read one is still sent, as what it is").isEqualTo(RX.toString());
    }

    @Test
    void noPrescriptionIsSentWithoutADocument() throws IOException {
        JsonNode body = get(cases().get("medicines-none").get());
        assertThat(fieldNames(body)).containsExactly("heading_key", "document_id", "medicines", "newer_prescription_not_read", "newer_document_id");
        assertThat(body.path("document_id").isNull()).isTrue();
        assertThat(body.path("medicines")).isEmpty();
        assertThat(body.path("newer_prescription_not_read").asBoolean()).isFalse();
    }

    /** Every key the two routes can emit (the heading, every chip a point can carry) resolves in both languages. */
    @Test
    void everyEmittedKeyResolvesInBothLanguages() throws IOException {
        Set<String> emitted = new TreeSet<>();
        for (String name : cases().keySet()) {
            Json.walk(get(cases().get(name).get()), (path, node) -> {
                if (path.endsWith("_key") && node.isTextual()) {
                    emitted.add(node.asText());
                }
            });
        }
        for (com.elioo.baymax.outbound.domain.ValueVerdict.Kind kind : com.elioo.baymax.outbound.domain.ValueVerdict.Kind.values()) {
            for (String side : java.util.Arrays.asList("high", "low", null)) {
                try {
                    emitted.add(PaperDisplay.chipKey(kind, side));
                } catch (IllegalStateException sidelessIsNeverDrawn) {
                    // OUTSIDE or CRITICAL without a side: kindOf has already made it UNKNOWN
                }
            }
        }
        assertThat(emitted).contains(HealthJson.MEDICINES_HEADING_KEY, "value.chip.unknown", "value.chip.outside.high", "value.chip.inside");
        for (Lang lang : Lang.values()) {
            Set<String> keys = copy.keys(lang);
            assertThat(emitted.stream().filter(k -> !keys.contains(k)).toList()).as("emitted keys missing in " + lang).isEmpty();
        }
    }

    @Test
    void anotherFamilysPatientIsNotFound() throws IOException {
        when(reads.labPoints(any())).thenReturn(Flux.fromIterable(labsSome()));
        when(reads.latestPrescription(any())).thenReturn(Mono.just(LatestPrescription.none(Optional.empty())));
        UUID stranger = UUID.fromString("b6b6b6b6-0000-4000-8000-0000000000ff");
        for (String route : List.of("labs", "medicines")) {
            for (String id : List.of(stranger.toString(), "not-a-uuid")) {
                EntityExchangeResult<byte[]> r = exchange("/app/api/patients/" + id + "/" + route, "tok");
                assertThat(r.getStatus().value()).as(route + " of " + id).isEqualTo(404);
                assertThat(json.readTree(r.getResponseBody())).isEqualTo(json.readTree("{\"reason\":\"NOT_FOUND\"}"));
            }
            EntityExchangeResult<byte[]> anonymous = exchange("/app/api/patients/" + MA + "/" + route, null);
            assertThat(anonymous.getStatus().value()).isEqualTo(401);
        }
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
