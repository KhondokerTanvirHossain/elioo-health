package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.common.text.NameGrammar;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.nudge.application.port.in.NudgeOptOutUseCase;
import com.elioo.baymax.nudge.application.port.out.NudgeDataPort;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
import com.elioo.baymax.testsupport.PaperFixtures;
import com.elioo.baymax.testsupport.Snapshot;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.adapter.in.ui.UiCopy;
import com.elioo.baymax.web.application.port.in.AttentionUseCase;
import com.elioo.baymax.web.application.port.in.HealthUseCase;
import com.elioo.baymax.web.application.port.in.ResultUseCase;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;
import com.elioo.baymax.web.application.port.in.UploadUseCase;
import com.elioo.baymax.web.application.port.in.WebAuthUseCase;
import com.elioo.baymax.web.domain.AttentionItem;
import com.elioo.baymax.web.domain.FamilyOverview;
import com.elioo.baymax.web.domain.PatientSummary;
import com.elioo.baymax.web.domain.WebSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Plan W3-api: {@code GET /app/api/me} as the Next screens' stub Java serves it, one MSW fixture per case, written in
 * the generated-file envelope and compared byte for byte with the committed {@code medioo-web/fixtures/api/me/*.json}.
 * Over the real {@link AppApiRouter}, {@link AppApiGuard} and {@link MeApiHandler}, with a stub session and a mocked
 * {@link TimelineUseCase} and {@link AttentionUseCase} serving fixed ids and instants, so the bodies are byte-stable.
 *
 * <p>Every signed-in case but {@code preview-off} is a family on {@code baymax.web.preview-family-ids}: until each
 * screen's flip, its pages are reached on the preview path, which serves nothing to a family that is not.
 * {@code preview-on} and {@code preview-off} are the pair that differ in {@code preview} alone.
 */
class MeApiContractTest {

    private static final Path COMMITTED = Path.of("../medioo-web/fixtures/api/me");
    private static final Path WRITTEN = Path.of("build/web-contract/me");
    /** {@code CatalogFixturesExportTest}'s long name, read from the file it writes (and byte-checks) for medioo-web. */
    private static final Path NAMES = Path.of("../medioo-web/fixtures/catalog/names.json");
    private static final Instant NOW = PaperFixtures.NOW;
    private static final UUID FAMILY = PaperFixtures.FAMILY;
    private static final UUID SHARED_ID = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100012");
    /** A second patient the family owns: the add sheet's two-patient case names the same two (UploadApiContractTest). */
    static final UUID BABA_ID = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100801");
    private static final String NUMBER = "+8801700000121";

    private final ObjectMapper json = new ObjectMapper();
    private final WebAuthUseCase auth = mock(WebAuthUseCase.class);
    private final TimelineUseCase timeline = mock(TimelineUseCase.class);
    private final AttentionUseCase attention = mock(AttentionUseCase.class);
    private final BaymaxProperties properties = new BaymaxProperties();

    MeApiContractTest() {
        when(auth.authenticate(anyString())).thenReturn(Mono.empty());
        when(auth.authenticate("tok")).thenReturn(Mono.just(new WebSession(UUID.randomUUID(), FAMILY, NOW, NOW, NOW.plusSeconds(600))));
        when(attention.urgent(any(), any())).thenReturn(Mono.just(List.of()));
    }

    private WebTestClient client() {
        SessionAuthFilter sessions = new SessionAuthFilter(auth, properties);
        properties.getWeb().setPublicOrigin(AppApiGuardTest.ORIGIN);
        AppApiGuard guard = new AppApiGuard(sessions, properties);
        return WebTestClient.bindToRouterFunction(new AppApiRouter().mediooAppApiRoutes(
                new MeApiHandler(timeline, attention, properties),
                new SignInApiHandler(auth, sessions, properties),
                new PaperApiHandler(mock(ResultUseCase.class), new UiCopy(), mock(DocumentStorageUseCase.class)),
                new UploadApiHandler(mock(UploadUseCase.class), properties, new UiCopy()),
                new PaperStatusApiHandler(mock(ResultUseCase.class)),
                new PaperDeleteApiHandler(timeline, new DeleteConfirmation(properties, Clock.systemUTC())),
                new FamilyApiHandler(mock(TimelineUseCase.class), mock(NudgeDataPort.class), mock(NudgeOptOutUseCase.class),
                        new UiCopy(), properties),
                new HealthApiHandler(mock(HealthUseCase.class)),
                B3Handlers.nowBanner(), B3Handlers.attention(), B3Handlers.timeline(),
                new PreferencesApiHandler(properties), B3Handlers.thread(),
                guard)).build();
    }

    // ---- the cases ------------------------------------------------------------------------------------------

    static String longName() {
        try {
            String name = new ObjectMapper().readTree(Files.readString(NAMES, StandardCharsets.UTF_8)).path("names").path("long").asText();
            assertThat(name).as("the long name in " + NAMES).isNotBlank().contains(" ");
            return name;
        } catch (IOException e) {
            throw new AssertionError("could not read " + NAMES, e);
        }
    }

    /** One case: who is signed in (null: nobody), whom they see, which of them has a NOW paper, and the allowlist. */
    record Case(List<PatientSummary> patients, List<UUID> urgent, boolean preview, boolean session) {
        static Case of(boolean preview, PatientSummary... patients) {
            return new Case(List.of(patients), List.of(), preview, true);
        }

        Case urgent(UUID... ids) {
            return new Case(patients, List.of(ids), preview, session);
        }
    }

    static PatientSummary ma() {
        return new PatientSummary(PaperFixtures.MA, true, 1, NOW);
    }

    /** Ma's husband, owned by the same family, nothing on file (plan W7: the add sheet's two-patient case). */
    static PatientSummary baba() {
        return new PatientSummary(new PatientProfile(BABA_ID, FAMILY, "Karim", 71, PatientProfile.Sex.MALE, List.of(), NOW, NOW), true, 0, null);
    }

    /** Ma's row under the long name (fixtures/catalog/names.json), as {@code long-name} serves it. */
    static PatientSummary maLong() {
        return new PatientSummary(new PatientProfile(PaperFixtures.MA_ID, FAMILY, longName(), 64, PatientProfile.Sex.FEMALE, List.of(), NOW, NOW),
                true, 1, NOW);
    }

    static PatientSummary shared() {
        // a sibling's patient shared with this number: visible, not owned, nothing on file; a Bangla name, so its
        // possessive takes another ending than Ma's
        return new PatientSummary(new PatientProfile(SHARED_ID, UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100013"),
                "রোকেয়া পরীক্ষা", 71, PatientProfile.Sex.FEMALE, List.of(), NOW, NOW), false, 0, null);
    }

    static Map<String, Case> cases() {
        Map<String, Case> cases = new LinkedHashMap<>();
        cases.put("one-patient", Case.of(true, ma()));
        cases.put("two-patients", Case.of(true, ma(), shared()).urgent(PaperFixtures.MA_ID));
        cases.put("no-patients", Case.of(true));
        cases.put("preview-on", Case.of(true, ma()));
        cases.put("preview-off", Case.of(false, ma()));
        cases.put("long-name", Case.of(true, maLong()));
        // two patients the family owns and no remembered choice: the add sheet (W7) asks whose paper it is
        cases.put("two-owned", Case.of(true, ma(), baba()));
        cases.put("unauthenticated", new Case(List.of(), List.of(), false, false));
        return cases;
    }

    static Stream<String> caseNames() {
        return cases().keySet().stream();
    }

    private EntityExchangeResult<byte[]> serve(Case c) {
        when(timeline.me(FAMILY)).thenReturn(Mono.just(new FamilyOverview(
                new FamilyAccount(FAMILY, NUMBER, "Tanvir", FamilyAccount.Plan.FAMILY, NOW, NOW), c.patients())));
        for (UUID id : c.urgent()) {
            when(attention.urgent(FAMILY, id)).thenReturn(Mono.just(List.of(new AttentionItem.Urgent(PaperFixtures.DOC, Urgency.NOW,
                    "lab_report", LocalDate.parse("2026-10-19"), false, NOW, false))));
        }
        properties.getWeb().setPreviewFamilyIds(c.preview() ? new ArrayList<>(List.of(FAMILY.toString())) : new ArrayList<>());
        WebTestClient.RequestHeadersSpec<?> spec = client().get().uri("/app/api/me");
        if (c.session()) {
            spec = spec.cookie("baymax_session", "tok");
        }
        return spec.exchange().expectBody().returnResult();
    }

    private JsonNode body(Case c) throws IOException {
        return json.readTree(serve(c).getResponseBody());
    }

    @ParameterizedTest
    @MethodSource("caseNames")
    void eachCaseIsItsCommittedMswFixture(String name) throws IOException {
        EntityExchangeResult<byte[]> r = serve(cases().get(name));
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("_meta", Snapshot.meta(MeApiContractTest.class, "GET /app/api/me over the MeApiContractTest case " + name));
        envelope.put("status", r.getStatus().value());
        envelope.put("body", json.readValue(r.getResponseBody(), Object.class));
        String file = name + ".json";
        Snapshot.matches(COMMITTED.resolve(file), WRITTEN.resolve(file), Snapshot.json(envelope),
                "cp baymax/" + WRITTEN.resolve(file) + " medioo-web/fixtures/api/me/" + file);
    }

    @Test
    void everyCommittedFixtureIsACurrentCase() throws IOException {
        List<String> committed;
        try (Stream<Path> files = Files.list(COMMITTED)) {
            committed = files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json")).sorted().toList();
        }
        assertThat(committed).as("the committed fixtures in " + COMMITTED.toAbsolutePath()).isNotEmpty();
        assertThat(committed).as("committed fixtures vs the cases").containsExactlyInAnyOrderElementsOf(caseNames().map(n -> n + ".json").toList());
    }

    // ---- what each case is for, by field -----------------------------------------------------------------------

    @Test
    void theCasesShowWhatTheirNamesSay() throws IOException {
        Map<String, Case> cases = cases();
        JsonNode one = body(cases.get("one-patient"));
        assertThat(one.at("/patients").size()).isEqualTo(1);
        assertThat(one.at("/active_patient_id").asText()).as("the only patient is in view").isEqualTo(PaperFixtures.MA_ID.toString());
        assertThat(one.at("/preview").asBoolean()).isTrue();

        JsonNode two = body(cases.get("two-patients"));
        assertThat(two.at("/patients").size()).isEqualTo(2);
        assertThat(two.at("/active_patient_id").isNull()).as("two patients and no remembered choice: the page asks").isTrue();
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        two.at("/patients").forEach(p -> byId.put(p.get("id").asText(), p));
        assertThat(byId.get(PaperFixtures.MA_ID.toString()).get("urgent").asBoolean()).as("Ma has a NOW paper").isTrue();
        assertThat(byId.get(SHARED_ID.toString()).get("urgent").asBoolean()).isFalse();
        assertThat(byId.get(SHARED_ID.toString()).get("owner").asBoolean()).as("the shared patient is not owned").isFalse();
        assertThat(byId.get(SHARED_ID.toString()).get("name_possessive").asText()).isEqualTo(NameGrammar.possessive("রোকেয়া পরীক্ষা"));

        JsonNode none = body(cases.get("no-patients"));
        assertThat(none.at("/patients").isArray()).isTrue();
        assertThat(none.at("/patients").size()).isZero();
        assertThat(none.at("/active_patient_id").isNull()).isTrue();

        JsonNode longest = body(cases.get("long-name"));
        assertThat(longest.at("/patients/0/name").asText()).isEqualTo(longName());

        EntityExchangeResult<byte[]> unauthenticated = serve(cases.get("unauthenticated"));
        assertThat(unauthenticated.getStatus().value() + " " + new String(unauthenticated.getResponseBody(), StandardCharsets.UTF_8))
                .isEqualTo("401 {\"reason\":\"UNAUTHENTICATED\"}");
    }

    /** Derived, not written down: remove {@code preview} from both and the two bodies are the same tree. */
    @Test
    void previewOnAndOffDifferInPreviewAlone() throws IOException {
        Map<String, Case> cases = cases();
        JsonNode on = body(cases.get("preview-on"));
        JsonNode off = body(cases.get("preview-off"));
        assertThat(on.get("preview").asBoolean()).isTrue();
        assertThat(off.get("preview").isBoolean()).isTrue();
        assertThat(off.get("preview").asBoolean()).isFalse();
        ObjectNode a = on.deepCopy();
        ObjectNode b = off.deepCopy();
        a.remove("preview");
        b.remove("preview");
        assertThat(a).as("every other field").isEqualTo(b);
        assertThat(a.size()).as("fields left to compare").isGreaterThanOrEqualTo(3);
    }
}
