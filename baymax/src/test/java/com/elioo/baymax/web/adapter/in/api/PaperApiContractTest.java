package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.web.application.port.in.UploadUseCase;
import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.common.text.NameGrammar;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.extraction.domain.RetakeReason;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.outbound.domain.ValueVerdict;
import com.elioo.baymax.testsupport.Json;
import com.elioo.baymax.testsupport.PaperFixtures;
import com.elioo.baymax.testsupport.Snapshot;
import com.elioo.baymax.web.adapter.in.ImageRefs;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.adapter.in.ui.Dom;
import com.elioo.baymax.web.adapter.in.ui.DocumentPages;
import com.elioo.baymax.web.adapter.in.ui.Lang;
import com.elioo.baymax.web.adapter.in.ui.PageChrome;
import com.elioo.baymax.web.adapter.in.ui.UiCopy;
import com.elioo.baymax.web.application.port.in.AttentionUseCase;
import com.elioo.baymax.web.application.port.in.ResultUseCase;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;
import com.elioo.baymax.web.application.port.in.WebAuthUseCase;
import com.elioo.baymax.web.domain.GateState;
import com.elioo.baymax.web.domain.PaperDisplay;
import com.elioo.baymax.web.domain.ReadingView;
import com.elioo.baymax.web.domain.ResultView;
import com.elioo.baymax.web.domain.ViewerLocale;
import com.elioo.baymax.web.domain.WebSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.elioo.baymax.testsupport.PaperFixtures.DOC;
import static com.elioo.baymax.testsupport.PaperFixtures.FAMILY;
import static com.elioo.baymax.testsupport.PaperFixtures.value;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Rebuild plan B2 step 5: {@code GET /app/api/documents/{id}}, the paper's display model, through the real
 * {@link AppApiRouter} and {@link AppApiGuard}, with a stub session, a mocked {@link ResultUseCase} serving
 * {@link PaperFixtures}, and the real {@link UiCopy}. Every assertion reads the parsed tree by field path; none matches a
 * substring of the document (the ids are UUIDs). Where the HTML page draws the same rule (the counts line, the doctor's
 * lines), the page is rendered from the same mocked read and the two are compared, derived, not written down.
 *
 * <p>The nine cases React's MSW handlers serve are written here too, in the generated-file envelope, and compared byte
 * for byte with the committed {@code medioo-web/fixtures/api/documents/*.json}.
 */
class PaperApiContractTest {

    private static final String URI = "/app/api/documents/" + DOC;
    private static final Path COMMITTED = Path.of("../medioo-web/fixtures/api/documents");
    private static final Path WRITTEN = Path.of("build/web-contract/documents");

    private final ObjectMapper json = new ObjectMapper();
    private final WebAuthUseCase auth = mock(WebAuthUseCase.class);
    private final ResultUseCase results = mock(ResultUseCase.class);
    private final UiCopy copy = new UiCopy();
    private final BaymaxProperties properties = new BaymaxProperties();
    private final SessionAuthFilter sessions = new SessionAuthFilter(auth, properties);
    private final WebTestClient client;
    private final WebTestClient pages;

    PaperApiContractTest() {
        Instant now = PaperFixtures.NOW;
        when(auth.authenticate(anyString())).thenReturn(Mono.empty());
        when(auth.authenticate("tok")).thenReturn(Mono.just(new WebSession(UUID.randomUUID(), FAMILY, now, now, now.plusSeconds(600))));
        properties.getWeb().setPublicOrigin(AppApiGuardTest.ORIGIN);
        AppApiGuard guard = new AppApiGuard(sessions, properties);
        AttentionUseCase attention = mock(AttentionUseCase.class);
        TimelineUseCase timeline = mock(TimelineUseCase.class);
        client = WebTestClient.bindToRouterFunction(new AppApiRouter().mediooAppApiRoutes(
                new MeApiHandler(timeline, attention, properties), new SignInApiHandler(auth, sessions, properties),
                new PaperApiHandler(results, copy),
                new UploadApiHandler(mock(UploadUseCase.class), properties), guard)).build();
        // the HTML page of the same paper, from the same mocked read: what the JSON must agree with
        DocumentPages html = new DocumentPages(timeline, copy, new PageChrome(copy, sessions), results);
        pages = WebTestClient.bindToRouterFunction(html.routes(sessions)).build();
    }

    // ---- the nine cases -----------------------------------------------------------------------------------

    /** case name → how it is served. Each is also the MSW fixture of the same name. */
    static Map<String, Supplier<Served>> cases() {
        Map<String, Supplier<Served>> cases = new LinkedHashMap<>();
        cases.put("now-bd", () -> Served.result(PaperFixtures.nowPaper(PaperFixtures.BD)));
        cases.put("now-abroad", () -> Served.result(PaperFixtures.nowPaper(PaperFixtures.ABROAD)));
        cases.put("this-week-pending", () -> Served.result(PaperFixtures.thisWeekPending()));
        cases.put("routine-released", () -> Served.result(PaperFixtures.routineReleased()));
        cases.put("unassessed", () -> Served.result(PaperFixtures.unassessed()));
        cases.put("retake-third", () -> Served.result(PaperFixtures.retake("low_overall", 3, true)));
        cases.put("failed", () -> Served.result(PaperFixtures.failed()));
        cases.put("processing", () -> Served.reading(PaperFixtures.reading("PROCESSING")));
        cases.put("not-found", Served::notFound);
        return cases;
    }

    static Stream<String> caseNames() {
        return cases().keySet().stream();
    }

    /** What the mocked read serves for one case: a result, a paper still being read, or a paper this family cannot see. */
    record Served(ResultView result, ReadingView reading) {
        static Served result(ResultView r) {
            return new Served(r, null);
        }

        static Served reading(ReadingView r) {
            return new Served(null, r);
        }

        static Served notFound() {
            return new Served(null, null);
        }
    }

    private void serve(Served s) {
        if (s.result() != null) {
            serve(s.result());
        } else if (s.reading() != null) {
            serve(s.reading());
        } else {
            when(results.status(FAMILY, DOC)).thenReturn(Mono.error(BaymaxException.notFound("document_not_found", "no such document")));
            when(results.result(FAMILY, DOC)).thenReturn(Mono.error(BaymaxException.notFound("document_not_found", "no such document")));
            when(results.reading(FAMILY, DOC)).thenReturn(Mono.error(BaymaxException.notFound("document_not_found", "no such document")));
        }
    }

    private void serve(ResultView r) {
        when(results.status(FAMILY, DOC)).thenReturn(Mono.just(Document.Status.valueOf(r.document().status())));
        when(results.result(FAMILY, DOC)).thenReturn(Mono.just(r));
    }

    private void serve(ReadingView r) {
        when(results.status(FAMILY, DOC)).thenReturn(Mono.just(Document.Status.valueOf(r.document().status())));
        when(results.reading(FAMILY, DOC)).thenReturn(Mono.just(r));
    }

    @ParameterizedTest
    @MethodSource("caseNames")
    void eachCaseIsItsCommittedMswFixture(String name) throws IOException {
        serve(cases().get(name).get());
        EntityExchangeResult<byte[]> r = exchange(Lang.BN);
        Map<String, Object> envelope = new LinkedHashMap<>();
        // the one envelope and pretty-printer every Java-generated file for medioo-web uses (W2b's Snapshot)
        envelope.put("_meta", Snapshot.meta(PaperApiContractTest.class, "GET /app/api/documents/{id} over the PaperFixtures case " + name));
        envelope.put("status", r.getStatus().value());
        envelope.put("body", json.readValue(r.getResponseBody(), Object.class));
        String file = name + ".json";
        Snapshot.matches(COMMITTED.resolve(file), WRITTEN.resolve(file), Snapshot.json(envelope),
                "cp baymax/" + WRITTEN.resolve(file) + " medioo-web/fixtures/api/documents/" + file);
    }

    // ---- the contract ---------------------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(GateState.class)
    void wordingIsPresentOnlyWhenTheGateIsReleased(GateState gate) throws IOException {
        String wording = gate == GateState.RELEASED ? "Everything printed here is inside its range." : null;
        // a review time no default carries, so the JSON is shown to echo the served view's value and nothing else
        int reviewHours = 7;
        serve(PaperFixtures.withReviewHours(PaperFixtures.result(PaperFixtures.routineReleased().document(), Urgency.ROUTINE, List.of(), gate,
                wording, PaperFixtures.BD), reviewHours));
        JsonNode body = get(Lang.BN);
        assertThat(body.path("gate").asText()).isEqualTo(gate.name());
        if (gate == GateState.RELEASED) {
            assertThat(body.path("wording").asText()).isEqualTo(wording);
        } else {
            assertThat(body.has("wording")).as("$.wording for " + gate).isFalse();
        }
        if (gate == GateState.PENDING) {
            assertThat(body.path("review_hours").isInt()).isTrue();
            assertThat(body.path("review_hours").asInt()).as("$.review_hours").isEqualTo(reviewHours);
        } else {
            assertThat(body.has("review_hours")).as("$.review_hours for " + gate).isFalse();
        }
    }

    @Test
    void aPhoneAbroadIsNeverOffered999() throws IOException {
        serve(PaperFixtures.nowPaper(PaperFixtures.ABROAD));
        JsonNode abroad = get(Lang.BN);
        assertThat(abroad.path("viewer").path("bangladesh").asBoolean(true)).isFalse();
        assertThat(abroad.path("now").path("call_999").isBoolean()).isTrue();
        assertThat(abroad.path("now").path("call_999").asBoolean()).as("$.now.call_999 abroad").isFalse();
        assertThat(abroad.path("now").path("flags_go_key").asText()).isEqualTo("now.remote.flags.go");
        assertThat(abroad.path("now").path("bring_key").asText()).isEqualTo("now.bring.remote");
        assertThat(abroad.path("now").path("remote_share").asBoolean()).isTrue();
        List<String> tel = new ArrayList<>();
        Json.walk(abroad, (path, node) -> {
            if (node.isTextual() && node.asText().startsWith("tel:")) {
                tel.add(path);
            }
        });
        assertThat(tel).as("scalars that are a call link").isEmpty();

        serve(PaperFixtures.nowPaper(PaperFixtures.BD));
        JsonNode home = get(Lang.BN);
        assertThat(home.path("now").path("call_999").asBoolean()).as("$.now.call_999 in Bangladesh").isTrue();
        assertThat(home.path("now").path("flags_go_key").asText()).isEqualTo("now.flags.go");
        assertThat(home.path("now").path("bring_key").asText()).isEqualTo("now.bring");
        assertThat(home.path("now").path("remote_share").asBoolean(true)).isFalse();
    }

    @Test
    void aRoutinePaperHasNoBannerAndSaysOnlyThatItWasFiled() throws IOException {
        serve(PaperFixtures.routineReleased());
        JsonNode routine = get(Lang.BN);
        assertThat(routine.has("banner")).isTrue();
        assertThat(routine.path("banner").isNull()).as("$.banner on ROUTINE").isTrue();
        assertThat(routine.path("filed").asBoolean()).isTrue();
        assertThat(routine.path("urgency").asText()).isEqualTo("routine");

        serve(PaperFixtures.thisWeekPending());
        JsonNode week = get(Lang.BN);
        assertThat(week.path("banner").path("level").asText()).isEqualTo("this_week");
        assertThat(week.path("banner").path("line_key").asText()).isEqualTo("urgency.this_week");
        assertThat(fieldNames(week.path("banner"))).containsExactlyInAnyOrder("level", "line_key");
        assertThat(week.path("filed").asBoolean(true)).isFalse();

        serve(PaperFixtures.unassessed());
        JsonNode unknown = get(Lang.BN);
        assertThat(unknown.path("urgency").isNull()).isTrue();
        assertThat(unknown.path("banner").isNull()).as("$.banner with no stored level").isTrue();
        assertThat(unknown.path("filed").asBoolean(true)).isFalse();
    }

    @Test
    void aSidelessOutsideIsShownAsUnknownAndNeverCountedOutside() throws IOException {
        Map<String, Object> sideless = value("K", "6.1", "mmol/L", "3.5", "5.1", "outside", null);
        serve(PaperFixtures.result(PaperFixtures.lab(List.of(sideless)), Urgency.THIS_WEEK, List.of(), GateState.NONE, null, PaperFixtures.BD));
        JsonNode body = get(Lang.BN);
        JsonNode row = row(body, sideless.get("id"));
        assertThat(row.path("display_kind").asText()).isEqualTo("unknown");
        assertThat(row.path("side").isNull()).as("side").isTrue();
        assertThat(row.path("chip_key").asText()).isEqualTo("value.chip.unknown");
        assertThat(row.path("pin").isNull()).as("pin").isTrue();
        assertThat(body.path("counts").path("values").asInt()).isEqualTo(1);
        assertThat(body.path("counts").path("outside").asInt(-1)).isZero();
        assertThat(body.path("counts").path("line_key").asText()).isEqualTo("result.counts.none");
    }

    @Test
    void aOneSidedInsideIsShownAsUnknown() throws IOException {
        serve(PaperFixtures.nowPaper(PaperFixtures.BD));
        JsonNode body = get(Lang.BN);
        JsonNode ldl = row(body, PaperFixtures.ldlOneSided().get("id"));
        assertThat(ldl.path("display_kind").asText()).isEqualTo("unknown");
        assertThat(ldl.path("chip_key").asText()).isEqualTo("value.chip.unknown");
        assertThat(ldl.path("pin").isNull()).isTrue();
        // the other direction: two printed bounds and a value between them stay inside, with the bar's pin
        JsonNode hb = row(body, PaperFixtures.haemoglobinInside().get("id"));
        assertThat(hb.path("display_kind").asText()).isEqualTo("inside");
        assertThat(hb.path("chip_key").asText()).isEqualTo("value.chip.inside");
        assertThat(hb.path("pin").isNumber()).as("the inside row's pin").isTrue();
        assertThat(hb.path("pin").asDouble()).isBetween(25.0, 75.0);
    }

    @Test
    void aMedicineCarriesOnlyItsVerbatimFieldsAndItsCrop() throws IOException {
        // every field a prescription writes, beside the extraction's purpose, status, confidence and crop_key
        Map<String, Object> stored = PaperFixtures.medicine("Napa 500", "1+0+1", "3 times a day", "after meals", "7 days");
        assertThat(stored.keySet()).as("the stored row carries every written field").containsAll(PaperDisplay.MEDICINE_FIELDS)
                .contains("purpose", "status", "confidence", "crop_key");
        serve(PaperFixtures.result(PaperFixtures.prescription(List.of(stored), Map.of(), null), Urgency.ROUTINE, List.of(), GateState.NONE,
                null, PaperFixtures.BD));
        JsonNode body = get(Lang.BN);
        assertThat(body.path("medicines")).hasSize(1);
        JsonNode m = body.path("medicines").get(0);
        Set<String> expected = new LinkedHashSet<>(PaperDisplay.MEDICINE_FIELDS);
        expected.add("crop");
        assertThat(fieldNames(m)).as("the medicine's fields: the written ones (PaperDisplay.MEDICINE_FIELDS) and its crop")
                .containsExactlyInAnyOrderElementsOf(expected);
        for (String field : PaperDisplay.MEDICINE_FIELDS) {
            assertThat(m.path(field).asText()).as(field).isEqualTo(stored.get(field));
        }
        assertThat(m.path("crop").path("src").asText()).isEqualTo(ImageRefs.crop(DOC, (String) stored.get("crop_key")));
    }

    @Test
    void aNowPaperCarriesNotDoctorInEitherViewer() throws IOException {
        for (ViewerLocale viewer : List.of(PaperFixtures.BD, PaperFixtures.ABROAD)) {
            serve(PaperFixtures.nowPaper(viewer));
            assertThat(get(Lang.BN).path("now").path("notdoctor_key").asText()).as("bangladesh=" + viewer.bangladesh())
                    .isEqualTo("now.notdoctor");
        }
    }

    /**
     * Every key the JSON can emit resolves in both languages — the universe derived from the code, not sampled from the
     * fixtures: the chip of every kind on every side {@link PaperDisplay#chipKey} accepts, the retake line of every
     * {@link RetakeReason} on every section the gate can name (served through the route), the NOW keys of both viewers,
     * the banner of every level and both counts lines.
     */
    @Test
    void everyEmittedKeyResolvesInBothLanguages() throws IOException {
        Set<String> emitted = new TreeSet<>();
        Set<String> levels = new TreeSet<>();

        for (ValueVerdict.Kind kind : ValueVerdict.Kind.values()) {
            for (String side : java.util.Arrays.asList("high", "low", null)) {
                try {
                    emitted.add(PaperDisplay.chipKey(kind, side));
                } catch (IllegalStateException sidelessIsNeverDrawn) {
                    // OUTSIDE or CRITICAL without a side: kindOf has already made it UNKNOWN
                }
            }
        }

        for (String code : retakeCodes()) {
            serve(PaperFixtures.retake(code, 1, true));
            String key = get(Lang.BN).path("retake").path("reason_key").asText();
            assertThat(key).as("reason_key of " + code).isEqualTo(DocumentPages.retakeReasonKey(copy, code));
            RetakeReason reason = RetakeReason.ofCode(code).orElseThrow();
            String section = RetakeReason.sectionOf(code);
            String specific = section == null ? null : "retake.reason." + reason.prefix() + "." + section;
            boolean bothHold = specific != null && copy.keys(Lang.BN).contains(specific) && copy.keys(Lang.EN).contains(specific);
            assertThat(key).as("reason_key of " + code + ": the section's own line where both languages hold one")
                    .isEqualTo(bothHold ? specific : "retake.reason." + reason.prefix());
            emitted.add(key);
        }
        assertThat(emitted).as("a sectioned code reached its section's own line").anyMatch(k -> k.matches("retake\\.reason\\.[a-z_]+\\.[a-z_]+"));

        for (ViewerLocale viewer : List.of(PaperFixtures.BD, PaperFixtures.ABROAD)) {
            serve(PaperFixtures.nowPaper(viewer));
            get(Lang.BN).path("now").fields().forEachRemaining(f -> {
                if (f.getKey().endsWith("_key")) {
                    emitted.add(f.getValue().asText());
                }
            });
        }

        for (Urgency level : Urgency.values()) {
            serve(PaperFixtures.result(PaperFixtures.lab(List.of(PaperFixtures.haemoglobinInside(), PaperFixtures.sodiumLow())), level, List.of(),
                    GateState.NONE, null, PaperFixtures.BD));
            JsonNode body = get(Lang.BN);
            if (body.path("banner").isObject()) {
                emitted.add(body.path("banner").path("line_key").asText());
                levels.add(body.path("banner").path("level").asText());
            }
            emitted.add(body.path("counts").path("line_key").asText());
        }
        serve(PaperFixtures.routineReleased());
        emitted.add(get(Lang.BN).path("counts").path("line_key").asText());

        assertThat(emitted).as("keys the code can emit").isNotEmpty()
                .contains("now.notdoctor", "now.flags.go", "now.remote.flags.go", "now.bring", "now.bring.remote", "urgency.now",
                        "urgency.this_week", "value.chip.critical.low", "value.chip.outside.high", "result.counts", "result.counts.none");
        for (Lang lang : Lang.values()) {
            Set<String> readable = keysReactReads(lang);
            assertThat(readable).as("keys React reads in " + lang).isNotEmpty();
            assertThat(emitted.stream().filter(k -> !readable.contains(k)).toList()).as("emitted keys React cannot read in " + lang).isEmpty();
            for (String level : levels) {
                assertThatCode(() -> copy.urgency(lang, Urgency.fromDbValue(level), false)).as(level + " in " + lang).doesNotThrowAnyException();
            }
        }
    }

    /** Every code a retake can store: each reason, and each sectioned one on every section the gate is configured to name. */
    static List<String> retakeCodes() {
        BaymaxProperties.Extract extract = new BaymaxProperties().getExtract();
        Set<String> sections = new TreeSet<>();
        extract.getExpectedSections().values().forEach(sections::addAll);
        extract.getGatingSections().values().forEach(sections::addAll);
        assertThat(sections).as("the sections the gate names").contains("values", "medicines");
        List<String> codes = new ArrayList<>();
        for (RetakeReason reason : RetakeReason.values()) {
            if (reason.hasSection()) {
                sections.forEach(section -> codes.add(reason.code(section)));
            } else {
                codes.add(reason.code(null));
            }
        }
        return codes;
    }

    /** UiCopy's keys, and the urgency lines of messages_{lang}.properties, loaded as UiCopy loads them. */
    private Set<String> keysReactReads(Lang lang) throws IOException {
        Set<String> keys = new HashSet<>(copy.keys(lang));
        Properties messages = new Properties();
        try (Reader r = new InputStreamReader(UiCopy.class.getResourceAsStream("/baymax/messages_" + lang.code + ".properties"), StandardCharsets.UTF_8)) {
            messages.load(r);
        }
        messages.stringPropertyNames().stream().filter(k -> k.startsWith("urgency.")).forEach(keys::add);
        return keys;
    }

    @Test
    void theNameIsDeclinedInTheViewersLanguage() throws IOException {
        serve(PaperFixtures.nowPaper(PaperFixtures.BD));
        JsonNode en = get(Lang.EN);
        assertThat(en.path("patient").path("name").asText()).isEqualTo("Ma");
        assertThat(en.path("patient").path("name_possessive").asText()).isEqualTo("Ma's");
        assertThat(en.path("patient").path("name_objective").asText()).isEqualTo("Ma");

        String amma = "আম্মা";
        PatientProfile ma = new PatientProfile(PaperFixtures.MA_ID, FAMILY, amma, 64, PatientProfile.Sex.FEMALE, List.of(),
                PaperFixtures.NOW, PaperFixtures.NOW);
        serve(PaperFixtures.result(PaperFixtures.routineReleased().document(), ma, Urgency.ROUTINE, List.of(), GateState.NONE, null,
                PaperFixtures.BD));
        JsonNode bn = get(null);
        assertThat(bn.path("patient").path("name_possessive").asText()).isEqualTo(NameGrammar.possessive(amma));
        assertThat(bn.path("patient").path("name_objective").asText()).isEqualTo(NameGrammar.objective(amma));
    }

    @Test
    void onlyANowPaperWithAValueCriticalReasonDrawsRowsInTheNowBlock() throws IOException {
        Map<String, Object> critical = PaperFixtures.potassiumCritical();
        Map<String, Object> outside = PaperFixtures.sodiumLow();
        DocumentView paper = PaperFixtures.lab(List.of(critical, outside));
        List<String> reasons = List.of("value_critical|threshold:x");
        Set<ValueVerdict.Kind> above = PaperDisplay.shownAbove(Urgency.NOW, reasons);
        assertThat(above).as("what the rule draws above for value_critical").isNotEmpty();

        serve(PaperFixtures.result(paper, Urgency.NOW, reasons, GateState.NONE, null, PaperFixtures.BD));
        JsonNode now = get(Lang.BN);
        for (Map<String, Object> stored : List.of(critical, outside)) {
            boolean expected = above.contains(PaperDisplay.kindOf(stored));
            assertThat(row(now, stored.get("id")).path("in_now_block").asBoolean(!expected)).as(stored.get("name") + " in the NOW block")
                    .isEqualTo(expected);
        }
        assertThat(row(now, critical.get("id")).path("in_now_block").asBoolean()).isTrue();

        serve(PaperFixtures.result(paper, Urgency.NOW, List.of("text_emergency_phrase"), GateState.NONE, null, PaperFixtures.BD));
        assertThat(inNowBlock(get(Lang.BN))).as("a NOW paper for a phrase draws no value row above").isEmpty();

        for (Urgency below : List.of(Urgency.THIS_WEEK, Urgency.ROUTINE)) {
            serve(PaperFixtures.result(paper, below, reasons, GateState.NONE, null, PaperFixtures.BD));
            JsonNode body = get(Lang.BN);
            assertThat(inNowBlock(body)).as(below + ": rows in the NOW block").isEmpty();
            assertThat(body.has("now")).as(below + ": $.now").isFalse();
        }
    }

    private static List<String> inNowBlock(JsonNode body) {
        List<String> ids = new ArrayList<>();
        body.path("values").forEach(v -> {
            assertThat(v.path("in_now_block").isBoolean()).as("in_now_block is a field of every row").isTrue();
            if (v.path("in_now_block").asBoolean()) {
                ids.add(v.path("id").asText());
            }
        });
        return ids;
    }

    @Test
    void noConfidenceModelOrDeveloperProseAnywhere() throws IOException {
        Map<String, Object> hb = PaperFixtures.haemoglobinInside();
        hb.put("confidence", 0.81);
        // the model as production stores it (DocumentExtractionService.finish: provider + "/" + model)
        DocumentView view = new DocumentView(DOC.toString(), "DONE", null, "lab_report", "2026-10-12", "Shapla Diagnostics", 0.93,
                "anthropic/claude-opus-5", 1, List.of(hb), List.of(PaperFixtures.medicine("Napa 500", "1+0+1", "7 days")), List.of(), Map.of(), null);
        serve(PaperFixtures.result(view, Urgency.ROUTINE, List.of(), GateState.NONE, null, PaperFixtures.BD));
        JsonNode body = get(Lang.BN);
        List<String> fields = new ArrayList<>();
        List<String> model = new ArrayList<>();
        Json.walk(body, (path, node) -> {
            String field = Json.fieldName(path);
            if (field != null) {
                fields.add(path);
            }
            if (Set.of("confidence", "model", "status_reason", "reason_code").contains(field)) {
                model.add(path);
            }
            // a fixed fictional string, not a random id, so a substring is sound: it catches the model under any field name
            if (node.isTextual() && node.asText().contains("claude-opus-5")) {
                model.add(path);
            }
        });
        assertThat(fields).as("the walk reached the fields").isNotEmpty();
        assertThat(model).as("confidence, model or developer prose in the tree").isEmpty();
    }

    @Test
    void reasonsAreInDrawnOrder() throws IOException {
        DocumentView paper = PaperFixtures.lab(List.of(PaperFixtures.potassiumCritical()));
        serve(PaperFixtures.result(paper, Urgency.NOW, List.of("value_outside_range|stopgap", "value_critical|threshold:x", "legacy_thing"),
                GateState.NONE, null, PaperFixtures.BD));
        assertThat(texts(get(Lang.BN).path("reasons"))).containsExactly("value_critical", "other", "value_outside_range");

        serve(PaperFixtures.result(paper, Urgency.NOW, List.of(), GateState.NONE, null, PaperFixtures.BD));
        assertThat(texts(get(Lang.BN).path("reasons"))).containsExactly("other");

        serve(PaperFixtures.thisWeekPending());
        JsonNode week = get(Lang.BN);
        assertThat(week.path("reasons").isArray()).isTrue();
        assertThat(texts(week.path("reasons"))).as("below NOW").isEmpty();
    }

    @Test
    void everyValueCarriesItsOwnRowsCrop() throws IOException {
        Map<String, Object> first = PaperFixtures.potassiumCritical();
        Map<String, Object> second = PaperFixtures.sodiumLow();
        assertThat(first.get("id")).isNotEqualTo(second.get("id"));
        assertThat(first.get("crop_key")).isNotEqualTo(second.get("crop_key"));
        serve(PaperFixtures.result(PaperFixtures.lab(List.of(first, second)), Urgency.THIS_WEEK, List.of(), GateState.NONE, null, PaperFixtures.BD));
        JsonNode body = get(Lang.BN);
        List<String> srcs = new ArrayList<>();
        for (Map<String, Object> stored : List.of(first, second)) {
            String src = row(body, stored.get("id")).path("crop").path("src").asText();
            assertThat(src).as("the crop of row " + stored.get("id")).isEqualTo(ImageRefs.crop(DOC, (String) stored.get("crop_key")));
            srcs.add(src);
        }
        assertThat(srcs).doesNotHaveDuplicates();
    }

    @Test
    void aContextLineWithoutACropIsLeftOutAsTheHtmlLeavesItOut() throws IOException {
        ResultView paper = PaperFixtures.nowPaper(PaperFixtures.BD);
        serve(paper);
        JsonNode body = get(Lang.BN);
        assertThat(fieldNames(body.path("doctor"))).as("sections shown: never the referral").containsExactly("diagnosis");
        JsonNode lines = body.path("doctor").path("diagnosis");
        assertThat(texts(lines, "text")).containsExactly("Hyperkalaemia");
        assertThat(lines.get(0).path("crop").path("src").asText()).isEqualTo(ImageRefs.crop(DOC, "f/p/d/ctx-dx-1.jpg"));

        Dom page = Dom.parse(page(Lang.BN));
        Dom.Element doctor = page.all.stream().filter(e -> "doctor".equals(e.attrs().get("id"))).findFirst().orElseThrow();
        List<String> drawn = new ArrayList<>();
        page.all.stream().filter(e -> e.tag().equals("blockquote") && within(e, doctor)).forEach(e -> drawn.add(e.ownText()));
        assertThat(drawn).as("the lines the HTML page quotes").isNotEmpty();
        assertThat(texts(lines, "text")).isEqualTo(drawn);

        assertThat(json.convertValue(body.path("unverified"), Map.class)).isEqualTo(paper.document().unverified());
    }

    @Test
    void theJsonCountsAreTheHtmlCountsLine() throws IOException {
        Map<String, Object> sideless = value("K", "6.1", "mmol/L", "3.5", "5.1", "outside", null);
        List<ResultView> papers = List.of(PaperFixtures.nowPaper(PaperFixtures.BD), PaperFixtures.thisWeekPending(),
                PaperFixtures.routineReleased(), PaperFixtures.unassessed(),
                PaperFixtures.result(PaperFixtures.lab(List.of(sideless)), Urgency.THIS_WEEK, List.of(), GateState.NONE, null, PaperFixtures.BD));
        Set<String> lineKeys = new LinkedHashSet<>();
        for (ResultView paper : papers) {
            for (Lang lang : Lang.values()) {
                serve(paper);
                JsonNode counts = get(lang).path("counts");
                assertThat(counts.isObject()).as("counts of a paper with values").isTrue();
                String fromJson = copy.t(lang, counts.path("line_key").asText(), counts.path("values").asInt(), counts.path("outside").asInt())
                        .replaceAll("<[^>]*>", "");
                List<String> html = Dom.parse(page(lang)).all.stream()
                        .filter(e -> e.tag().equals("p") && e.classes().contains("counts")).map(Dom.Element::ownText).toList();
                assertThat(html).as("the HTML counts line").hasSize(1);
                assertThat(fromJson.strip()).as(lang + " " + paper.urgency()).isEqualTo(html.get(0));
                lineKeys.add(counts.path("line_key").asText());
            }
        }
        assertThat(lineKeys).as("both forms of the line were compared").containsExactlyInAnyOrder("result.counts", "result.counts.none");
    }

    @Test
    void anUndatedPaperSentAfterDhakaMidnightIsDatedByTheDhakaDay() throws IOException {
        DocumentView undated = PaperFixtures.paper(null, List.of(PaperFixtures.haemoglobinInside()), List.of(), List.of(), Map.of(), null);
        serve(PaperFixtures.result(undated, PaperFixtures.MA, Urgency.ROUTINE, List.of(), GateState.NONE, null, PaperFixtures.BD,
                Instant.parse("2026-10-20T18:30:00Z")));
        JsonNode body = get(Lang.BN);
        assertThat(body.path("printed_date").isNull()).as("$.printed_date").isTrue();
        assertThat(body.path("date").asText()).isEqualTo("2026-10-21");
        assertThat(body.path("date_is_upload_day").asBoolean()).isTrue();

        DocumentView printed = PaperFixtures.paper("2026-10-12", List.of(PaperFixtures.haemoglobinInside()), List.of(), List.of(), Map.of(), null);
        serve(PaperFixtures.result(printed, PaperFixtures.MA, Urgency.ROUTINE, List.of(), GateState.NONE, null, PaperFixtures.BD,
                Instant.parse("2026-10-20T18:30:00Z")));
        JsonNode dated = get(Lang.BN);
        assertThat(dated.path("printed_date").asText()).isEqualTo("2026-10-12");
        assertThat(dated.path("date").asText()).isEqualTo("2026-10-12");
        assertThat(dated.path("date_is_upload_day").asBoolean(true)).isFalse();
    }

    @Test
    void aPrintedDateThatIsNotIsoIsShownAsPrintedNeverAsTheUploadDay() throws IOException {
        DocumentView printed = PaperFixtures.paper("12/10/2026", List.of(PaperFixtures.haemoglobinInside()), List.of(), List.of(), Map.of(), null);
        serve(PaperFixtures.result(printed, Urgency.ROUTINE, List.of(), GateState.NONE, null, PaperFixtures.BD));
        JsonNode body = get(Lang.BN);
        // the dangerous direction first: a printed paper is never dated by the day it was sent
        assertThat(body.path("date_is_upload_day").isBoolean()).isTrue();
        assertThat(body.path("date_is_upload_day").asBoolean()).as("$.date_is_upload_day").isFalse();
        assertThat(body.path("printed_date").asText()).isEqualTo("12/10/2026");
        assertThat(body.has("date")).isTrue();
        assertThat(body.path("date").isNull()).as("$.date of a printed date that is not ISO").isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = Document.Status.class, names = {"RECEIVED", "PROCESSING"})
    void aPaperStillBeingReadCarriesNoResult(Document.Status status) throws IOException {
        serve(PaperFixtures.reading(status.name()));
        // were the paper sent through the result read, this is what it would give: a result-shaped body for an unread paper
        when(results.result(FAMILY, DOC)).thenReturn(Mono.just(new ResultView(PaperFixtures.pending(status.name()), PaperFixtures.MA, true,
                PaperFixtures.BD, null, List.of(), GateState.NONE, null, 0, PaperFixtures.REVIEW_HOURS, PaperFixtures.NOW, PaperFixtures.NOW)));
        JsonNode body = get(Lang.BN);
        assertThat(fieldNames(body)).as("the fields of a " + status + " paper")
                .containsExactlyInAnyOrder("id", "status", "patient", "owner", "viewer", "received_at", "page_count", "pages");
        assertThat(body.path("status").asText()).isEqualTo(status.name());
        assertThat(body.path("pages").get(0).path("src").asText()).isEqualTo(ImageRefs.page(DOC, 1));
        verify(results, never()).result(any(), any());
    }

    /**
     * The race the handler shares with the HTML page: the status says PROCESSING, then the reading read finds it DONE. It
     * is sent finished (React would otherwise poll for a change that already happened), from the result read — and that
     * opening records two DOCUMENT_VIEW rows, the reading view's and the result's, as the HTML page does.
     */
    @Test
    void aPaperThatFinishedBetweenTheTwoReadsIsSentFinished() throws IOException {
        ResultView done = PaperFixtures.nowPaper(PaperFixtures.BD);
        when(results.status(FAMILY, DOC)).thenReturn(Mono.just(Document.Status.PROCESSING));
        when(results.reading(FAMILY, DOC)).thenReturn(Mono.just(new ReadingView(done.document(), PaperFixtures.MA, true, PaperFixtures.BD,
                done.receivedAt(), done.now())));
        when(results.result(FAMILY, DOC)).thenReturn(Mono.just(done));
        JsonNode body = get(Lang.BN);
        assertThat(fieldNames(body)).as("$.values of a paper that finished between the two reads").contains("values", "urgency", "gate");
        assertThat(body.path("status").asText()).isEqualTo("DONE");
        assertThat(body.path("values")).as("$.values").hasSize(done.document().values().size());
        verify(results).reading(FAMILY, DOC);
        verify(results).result(FAMILY, DOC);
    }

    /**
     * A read the system rejected (FAILED, NEEDS_RETAKE) shows none of its stored rows, whatever was stored: the HTML page
     * draws them only for DONE, so the JSON sends empty lists. The stored V6 counts stay as stored.
     */
    @ParameterizedTest
    @EnumSource(value = Document.Status.class, names = {"FAILED", "NEEDS_RETAKE"})
    void aRejectedPaperSendsNoneOfItsStoredRows(Document.Status status) throws IOException {
        ResultView rejected = PaperFixtures.rejectedWithRows(status.name(), status == Document.Status.NEEDS_RETAKE ? "low_overall" : null);
        assertThat(rejected.document().values()).as("the stored rows the paper holds").isNotEmpty();
        serve(rejected);
        JsonNode body = get(Lang.BN);
        assertThat(body.path("status").asText()).isEqualTo(status.name());
        for (String list : List.of("values", "medicines", "follow_up")) {
            assertThat(body.path(list).isArray()).as("$." + list + " on " + status + " is a list").isTrue();
            assertThat(body.path(list)).as("$." + list + " on " + status).isEmpty();
        }
        assertThat(body.path("doctor").isObject()).as("$.doctor on " + status + " is an object").isTrue();
        assertThat(fieldNames(body.path("doctor"))).as("$.doctor on " + status).isEmpty();
        assertThat(body.path("counts").isNull()).as("$.counts on " + status).isTrue();
        assertThat(json.convertValue(body.path("unverified"), Map.class)).isEqualTo(rejected.document().unverified());

        // the page drawn from the same read shows no crop either: only the whole photos
        List<String> crops = Dom.parse(page(Lang.BN)).all.stream().filter(e -> e.tag().equals("img") && e.classes().contains("crop"))
                .map(e -> e.attrs().get("src")).toList();
        assertThat(crops).as("crops on the HTML page of a " + status + " paper").isEmpty();
    }

    /**
     * {@code in_now_block} and {@code pin} against the page drawn from the same read, not against the rule PaperJson calls:
     * the rows drawn inside the page's NOW reason blocks are exactly the rows the JSON marks, and each row's pin is the
     * page's own circle. Over a NOW paper for a value, a NOW paper for a phrase only, and a THIS_WEEK paper.
     */
    @Test
    void theNowBlockAndThePinAreThePagesOwn() throws IOException {
        DocumentView paper = PaperFixtures.paper("2026-10-12",
                List.of(PaperFixtures.sodiumLow(), PaperFixtures.potassiumCritical(), PaperFixtures.haemoglobinInside(), PaperFixtures.ldlOneSided()),
                List.of(), List.of(), Map.of(), null);
        Map<String, ResultView> papers = new LinkedHashMap<>();
        papers.put("NOW for a value", PaperFixtures.nowPaper(PaperFixtures.BD));
        papers.put("NOW for a phrase", PaperFixtures.result(paper, Urgency.NOW, List.of("text_emergency_phrase"), GateState.NONE, null, PaperFixtures.BD));
        papers.put("THIS_WEEK", PaperFixtures.result(paper, Urgency.THIS_WEEK, List.of("value_critical:potassium|threshold:demo"), GateState.NONE,
                null, PaperFixtures.BD));
        Set<Boolean> marked = new HashSet<>();
        int pins = 0;
        for (Map.Entry<String, ResultView> c : papers.entrySet()) {
            serve(c.getValue());
            JsonNode body = get(Lang.BN);
            Dom page = Dom.parse(page(Lang.BN));
            List<Dom.Element> nowBlocks = page.all.stream()
                    .filter(e -> e.tag().equals("section") && e.classes().contains("reason") && e.classes().contains("now")).toList();
            Set<String> drawnAbove = new TreeSet<>();
            page.all.stream().filter(e -> e.tag().equals("img") && e.classes().contains("crop") && e.parent() != null
                            && e.parent().classes().contains("vrow") && nowBlocks.stream().anyMatch(s -> within(e, s)))
                    .forEach(e -> drawnAbove.add(e.attrs().get("src")));
            Set<String> markedAbove = new TreeSet<>();
            body.path("values").forEach(v -> {
                if (v.path("in_now_block").asBoolean()) {
                    markedAbove.add(v.path("crop").path("src").asText());
                }
            });
            assertThat(markedAbove).as(c.getKey() + ": the JSON's in_now_block rows are the page's NOW-block rows").isEqualTo(drawnAbove);
            marked.add(!markedAbove.isEmpty());

            for (JsonNode v : body.path("values")) {
                String id = v.path("id").asText();
                List<String> cx = page.all.stream().filter(e -> e.tag().equals("circle") && e.classes().contains("pin")
                        && rowOf(e) != null && ("v-" + id).equals(rowOf(e).attrs().get("id"))).map(e -> e.attrs().get("cx")).toList();
                if (v.path("pin").isNull()) {
                    assertThat(cx).as(c.getKey() + ": the page draws no pin for row " + id).isEmpty();
                } else {
                    assertThat(cx).as(c.getKey() + ": the page's pin of row " + id).isNotEmpty()
                            .containsOnly(String.format(java.util.Locale.ROOT, "%.1f", v.path("pin").asDouble()));
                    pins++;
                }
            }
        }
        assertThat(marked).as("both a paper with rows above and one without were compared").containsExactlyInAnyOrder(true, false);
        assertThat(pins).as("pins compared").isPositive();
    }

    /** The value row ({@code a.vrow}) an element is drawn inside, or null. */
    private static Dom.Element rowOf(Dom.Element e) {
        for (Dom.Element at = e.parent(); at != null; at = at.parent()) {
            if (at.tag().equals("a") && at.classes().contains("vrow")) {
                return at;
            }
        }
        return null;
    }

    /** Both ways: every committed fixture is a current case, so a renamed or removed case leaves no stale body for MSW. */
    @Test
    void everyCommittedFixtureIsACurrentCase() throws IOException {
        List<String> committed;
        try (Stream<Path> files = java.nio.file.Files.list(COMMITTED)) {
            committed = files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json")).sorted().toList();
        }
        assertThat(committed).as("the committed fixtures in " + COMMITTED.toAbsolutePath()).isNotEmpty();
        assertThat(committed).as("committed fixtures vs the cases").containsExactlyInAnyOrderElementsOf(caseNames().map(n -> n + ".json").toList());
    }

    @Test
    void aRetakeCarriesItsReasonKeyNeverTheCode() throws IOException {
        serve(PaperFixtures.retake("low_overall", 3, true));
        JsonNode body = get(Lang.BN);
        assertThat(fieldNames(body.path("retake"))).containsExactlyInAnyOrder("reason_key", "ordinal");
        assertThat(body.path("retake").path("reason_key").asText()).isEqualTo(DocumentPages.retakeReasonKey(copy, "low_overall"));
        assertThat(body.path("retake").path("ordinal").asInt()).isEqualTo(3);
        List<String> code = new ArrayList<>();
        Json.walk(body, (path, node) -> {
            if (node.isTextual() && node.asText().equals("low_overall")) {
                code.add(path);
            }
        });
        assertThat(code).as("scalars that are the stored code").isEmpty();
        assertThat(body.path("values").isArray()).isTrue();
        assertThat(body.path("values")).isEmpty();
    }

    @Test
    void aPaperThisFamilyCannotSeeIsNotFoundByName() throws IOException {
        serve(Served.notFound());
        EntityExchangeResult<byte[]> r = exchange(Lang.BN);
        assertThat(r.getStatus().value()).isEqualTo(404);
        assertThat(json.readTree(r.getResponseBody())).isEqualTo(json.readTree("{\"reason\":\"NOT_FOUND\"}"));
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private EntityExchangeResult<byte[]> exchange(Lang lang) {
        WebTestClient.RequestHeadersSpec<?> spec = client.get().uri(URI).cookie("baymax_session", "tok");
        if (lang != null) {
            spec = spec.cookie("baymax_lang", lang.code);
        }
        return spec.exchange().expectBody().returnResult();
    }

    private JsonNode get(Lang lang) throws IOException {
        EntityExchangeResult<byte[]> r = exchange(lang);
        assertThat(r.getStatus().value()).as("GET " + URI).isEqualTo(200);
        return json.readTree(r.getResponseBody());
    }

    /** The HTML page of the same paper, from the same mocked read. */
    private String page(Lang lang) {
        return pages.get().uri("/app/documents/" + DOC).cookie("baymax_session", "tok").cookie("baymax_lang", lang.code)
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
    }

    /** The JSON row of a stored row, paired by id: never by position or name. */
    private static JsonNode row(JsonNode body, Object id) {
        List<JsonNode> rows = new ArrayList<>();
        body.path("values").forEach(v -> {
            if (v.path("id").asText().equals(String.valueOf(id))) {
                rows.add(v);
            }
        });
        assertThat(rows).as("the row with id " + id).hasSize(1);
        return rows.get(0);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static List<String> texts(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.path(field).asText()));
        return out;
    }

    private static boolean within(Dom.Element e, Dom.Element ancestor) {
        for (Dom.Element at = e.parent(); at != null; at = at.parent()) {
            if (at == ancestor) {
                return true;
            }
        }
        return false;
    }
}
