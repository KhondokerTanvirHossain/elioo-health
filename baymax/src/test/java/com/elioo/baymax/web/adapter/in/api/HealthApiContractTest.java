package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.BloodPressurePairAcceptanceTest;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.PatientAccess;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.nudge.application.port.in.NudgeOptOutUseCase;
import com.elioo.baymax.nudge.application.port.out.NudgeDataPort;
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
 * with the committed {@code medioo-web/fixtures/api/health/*.json}. The blood-pressure rows are built here (a departure
 * from plan Step 5's "seeded through the extraction path", listed in the PR) but carry the line as
 * {@link BloodPressurePairAcceptanceTest#EXTRACTED_BP_LINE}, the constant that test asserts equal to the {@code source_line}
 * extraction stores for {@code BP : 130 / 80}, so the fixture cannot drift from extraction; and one crop per value, never a
 * shared key. That the pair survives the real extraction path is that test's, end to end.
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
    private static final UUID LAB_MAR = UUID.fromString("b6b6b6b6-0000-4000-8000-0000000000d6");
    private static final UUID LAB_DEC = UUID.fromString("b6b6b6b6-0000-4000-8000-0000000000d7");

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
                new FamilyApiHandler(mock(TimelineUseCase.class), mock(NudgeDataPort.class), mock(NudgeOptOutUseCase.class),
                        copy, properties),
                new HealthApiHandler(health),
                B3Handlers.nowBanner(), B3Handlers.attention(), B3Handlers.timeline(),
                new PreferencesApiHandler(properties), B3Handlers.thread(),
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
        cases.put("labs-with-unknown", () -> Case.labs(labsWithUnknown()));
        cases.put("labs-non-numeric", () -> Case.labs(labsNonNumeric()));
        cases.put("labs-printed-range", () -> Case.labs(labsPrintedRange()));
        cases.put("labs-two-units", () -> Case.labs(labsTwoUnits()));
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

    /**
     * Every chip kind a trend can draw, over two series (plan W2e-b, TrendChart's {@code with-unknown} and
     * {@code critical-low} states): HbA1c over five papers from December 2025 to October 2026 (critical high, outside
     * low, unknown because the June paper printed no range, outside high, inside) and potassium over two (critical low,
     * inside). Newest first, as the read port serves them. The HbA1c series spans two calendar years, so the chart dates
     * every point with its year; and the critical points are where the chart must still not paint the NOW red.
     */
    static List<Map<String, Object>> labsWithUnknown() {
        Map<String, Object> oct = row(LAB_OCT, 51, "2026-10-12", "HbA1c", "hba1c", "5.4", "%", "4.0", "5.6");
        oct.put("verdict", "inside");
        Map<String, Object> potassiumOct = row(LAB_OCT, 56, "2026-10-12", "Potassium", "potassium", "4.1", "mmol/L", "3.5", "5.1");
        potassiumOct.put("verdict", "inside");
        Map<String, Object> aug = row(LAB_AUG, 52, "2026-08-03", "HbA1c", "hba1c", "5.8", "%", "4.0", "5.6");
        aug.put("verdict", "outside");
        aug.put("verdict_side", "high");
        Map<String, Object> potassiumAug = row(LAB_AUG, 57, "2026-08-03", "Potassium", "potassium", "2.4", "mmol/L", "3.5", "5.1");
        potassiumAug.put("verdict", "critical");
        potassiumAug.put("verdict_side", "low");
        Map<String, Object> jun = row(LAB_UNDATED, 53, "2026-06-20", "HbA1c", "hba1c", "6.1", "%", null, null);
        Map<String, Object> mar = row(LAB_MAR, 54, "2026-03-02", "HbA1c", "hba1c", "3.6", "%", "4.0", "5.6");
        mar.put("verdict", "outside");
        mar.put("verdict_side", "low");
        Map<String, Object> dec = row(LAB_DEC, 55, "2025-12-01", "HbA1c", "hba1c", "9.8", "%", "4.0", "5.6");
        dec.put("verdict", "critical");
        dec.put("verdict_side", "high");
        return List.of(oct, potassiumOct, aug, potassiumAug, jun, mar, dec); // jun: no printed range, no verdict: unknown
    }

    /**
     * Two series whose newest reading is printed as a bound, not a number (plan W2e-b): creatinine over three papers whose
     * newest is {@code >500}, critical high (two numbers would be left to draw a line that ends on the older reading), and
     * CRP over two whose newest is {@code <0.5} (one number left, which a count of points still calls a trend). The chart
     * cannot place either bound on an axis, so it draws neither series ({@code chartable} is false) and the health screen
     * lists the readings as written.
     */
    static List<Map<String, Object>> labsNonNumeric() {
        Map<String, Object> creatinineOct = row(LAB_OCT, 61, "2026-10-12", "S. Creatinine", "creatinine", ">500", "µmol/L", "59", "104");
        creatinineOct.put("verdict", "critical");
        creatinineOct.put("verdict_side", "high");
        Map<String, Object> crpOct = row(LAB_OCT, 62, "2026-10-12", "CRP", "crp", "<0.5", "mg/L", null, "5");
        Map<String, Object> creatinineAug = row(LAB_AUG, 63, "2026-08-03", "S. Creatinine", "creatinine", "132", "µmol/L", "59", "104");
        creatinineAug.put("verdict", "outside");
        creatinineAug.put("verdict_side", "high");
        Map<String, Object> crpAug = row(LAB_AUG, 64, "2026-08-03", "CRP", "crp", "12", "mg/L", null, "5");
        crpAug.put("verdict", "outside");
        crpAug.put("verdict_side", "high");
        Map<String, Object> creatinineJun = row(LAB_UNDATED, 65, "2026-06-20", "Creatinine", "creatinine", "98", "µmol/L", "59", "104");
        creatinineJun.put("verdict", "inside");
        return List.of(creatinineOct, crpOct, creatinineAug, crpAug, creatinineJun);
    }

    /**
     * PO ruling R3 (2026-10-22, DR-48): every shape a printed range is stored in, over five series, each verdict one the
     * production rule can store. Creatinine on three papers: two labs printed two different two-sided ranges (59–104,
     * 62–106), and the third printed none. Total cholesterol printed one-sided high ({@code <200}) and HDL one-sided low
     * ({@code >40}): the stopgap stores {@code unknown} for a value beside a one-sided range whichever side of it the value
     * sits ({@code UrgencyService.criticalKind} needs both bounds), so the October and August points are UNKNOWN above,
     * below and inside their bound. Fasting glucose's newer paper put the whole range in one column ({@code 3.9-6.1}),
     * which the band cannot place; its older paper printed it in two. Potassium is the band that contradicts its chip.
     *
     * <p><b>The threshold path</b>, named because only a signed-off per-marker threshold ({@code perMarkerVerdict}, which
     * never reads the printed range) can store these: June's creatinine 150 OUTSIDE high with no printed range, June's
     * cholesterol 240 OUTSIDE high above {@code <200}, June's HDL 35 OUTSIDE low below {@code >40}, and October's
     * potassium 5.0 CRITICAL high inside its printed 3.5–5.1. August's potassium is a legacy row with a NULL verdict
     * inside its band.</p>
     */
    static List<Map<String, Object>> labsPrintedRange() {
        Map<String, Object> creatinineOct = verdict(row(LAB_OCT, 71, "2026-10-12", "S. Creatinine", "creatinine", "132", "µmol/L", "59", "104"), "outside", "high");
        Map<String, Object> cholesterolOct = verdict(row(LAB_OCT, 72, "2026-10-12", "Total Cholesterol", "total_cholesterol", "210", "mg/dL", null, "<200"), "unknown", null);
        Map<String, Object> hdlOct = verdict(row(LAB_OCT, 73, "2026-10-12", "HDL Cholesterol", "hdl", "38", "mg/dL", ">40", null), "unknown", null);
        Map<String, Object> glucoseOct = verdict(row(LAB_OCT, 74, "2026-10-12", "Fasting Glucose", "fasting_glucose", "6.4", "mmol/L", "3.9-6.1", null), "unknown", null);
        Map<String, Object> potassiumOct = verdict(row(LAB_OCT, 82, "2026-10-12", "Potassium", "potassium", "5.0", "mmol/L", "3.5", "5.1"), "critical", "high");
        Map<String, Object> creatinineAug = verdict(row(LAB_AUG, 75, "2026-08-03", "Creatinine", "creatinine", "98", "umol/L", "62", "106"), "inside", null);
        Map<String, Object> cholesterolAug = verdict(row(LAB_AUG, 76, "2026-08-03", "Total Cholesterol", "total_cholesterol", "180", "mg/dL", null, "<200"), "unknown", null);
        Map<String, Object> hdlAug = verdict(row(LAB_AUG, 77, "2026-08-03", "HDL Cholesterol", "hdl", "45", "mg/dL", ">40", null), "unknown", null);
        Map<String, Object> glucoseAug = verdict(row(LAB_AUG, 78, "2026-08-03", "Fasting Glucose", "fasting_glucose", "5.2", "mmol/L", "3.9", "6.1"), "inside", null);
        Map<String, Object> potassiumAug = row(LAB_AUG, 83, "2026-08-03", "Potassium", "potassium", "4.2", "mmol/L", "3.5", "5.1");
        Map<String, Object> creatinineJun = verdict(row(LAB_UNDATED, 79, "2026-06-20", "Creatinine", "creatinine", "150", "umol/L", null, null), "outside", "high");
        Map<String, Object> cholesterolJun = verdict(row(LAB_UNDATED, 80, "2026-06-20", "Total Cholesterol", "total_cholesterol", "240", "mg/dL", null, "<200"), "outside", "high");
        Map<String, Object> hdlJun = verdict(row(LAB_UNDATED, 81, "2026-06-20", "HDL Cholesterol", "hdl", "35", "mg/dL", ">40", null), "outside", "low");
        return List.of(creatinineOct, cholesterolOct, hdlOct, glucoseOct, potassiumOct, creatinineAug, cholesterolAug, hdlAug, glucoseAug,
                potassiumAug, creatinineJun, cholesterolJun, hdlJun);
    }

    /**
     * Plan W9: one name printed in two units on four papers. Fasting glucose in mmol/L (October, August) and in mg/dL
     * (June, March) is two series, as {@link HealthService} keys a series by canonical name AND folded unit: joined by name
     * alone, 6.4 and 126 would draw a twentyfold rise that never happened. The health screen shows them as Java sent them,
     * two series under one printed name, and never joins them itself.
     */
    static List<Map<String, Object>> labsTwoUnits() {
        Map<String, Object> oct = verdict(row(LAB_OCT, 91, "2026-10-12", "Fasting Glucose", "fasting_glucose", "6.4", "mmol/L", "3.9", "6.1"), "outside", "high");
        Map<String, Object> aug = verdict(row(LAB_AUG, 92, "2026-08-03", "Fasting Glucose", "fasting_glucose", "5.2", "mmol/L", "3.9", "6.1"), "inside", null);
        Map<String, Object> jun = verdict(row(LAB_UNDATED, 93, "2026-06-20", "Fasting Glucose", "fasting_glucose", "126", "mg/dL", "70", "110"), "outside", "high");
        Map<String, Object> mar = verdict(row(LAB_MAR, 94, "2026-03-02", "Fasting Glucose", "fasting_glucose", "98", "mg/dL", "70", "110"), "inside", null);
        return List.of(oct, aug, jun, mar);
    }

    private static Map<String, Object> verdict(Map<String, Object> row, String verdict, String side) {
        row.put("verdict", verdict);
        if (side != null) {
            row.put("verdict_side", side);
        }
        return row;
    }

    /** One BP : 130 / 80 line read through extraction: two values, two crops, one line. */
    static List<Map<String, Object>> bpRows() {
        Map<String, Object> systolic = row(LAB_OCT, 41, "2026-10-12", "BP", "bp_systolic", "130", "mmHg", null, null);
        systolic.put("source_line", BloodPressurePairAcceptanceTest.EXTRACTED_BP_LINE);
        Map<String, Object> diastolic = row(LAB_OCT, 42, "2026-10-12", "BP", "bp_diastolic", "80", "mmHg", null, null);
        diastolic.put("source_line", BloodPressurePairAcceptanceTest.EXTRACTED_BP_LINE);
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
                "kind", "side", "chip_key", "crop", "ref_low", "ref_high", "ref_text");
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
        assertThat(j.path("ref_low").isNull() ? null : j.path("ref_low").asDouble()).isEqualTo(p.range().low());
        assertThat(j.path("ref_high").isNull() ? null : j.path("ref_high").asDouble()).isEqualTo(p.range().high());
        assertThat(j.path("ref_text").isNull() ? null : j.path("ref_text").asText()).isEqualTo(p.range().text());
    }

    /** Plan W9: one printed name in two units is two series, each its own unit and its own papers, newest first. */
    @Test
    void oneNameInTwoUnitsIsTwoSeries() throws IOException {
        JsonNode body = get(cases().get("labs-two-units").get());
        JsonNode series = body.path("series");
        assertThat(series).as("two units of one name").hasSize(2);
        List<String> units = new ArrayList<>();
        for (JsonNode s : series) {
            assertThat(s.path("canonical_name").asText()).isEqualTo("fasting_glucose");
            assertThat(s.path("name").asText()).as("both under the one printed name").isEqualTo("Fasting Glucose");
            assertThat(s.path("points")).as(s.path("unit").asText() + "'s papers").hasSize(2);
            units.add(s.path("unit").asText());
        }
        assertThat(units).containsExactly("mmol/L", "mg/dL");
        assertThat(series.get(0).path("points").get(0).path("observation_id").asText()).isEqualTo(observation(91));
        assertThat(series.get(0).path("points").get(1).path("observation_id").asText()).isEqualTo(observation(92));
        assertThat(series.get(1).path("points").get(0).path("observation_id").asText()).isEqualTo(observation(93));
        assertThat(series.get(1).path("points").get(1).path("observation_id").asText()).isEqualTo(observation(94));
    }

    // ---- R3: the printed range, per point ------------------------------------------------------------------------

    /** The points of {@code labs-printed-range}, by observation id. */
    private Map<String, JsonNode> printedRangePoints() throws IOException {
        JsonNode body = get(cases().get("labs-printed-range").get());
        Map<String, JsonNode> byId = new HashMap<>();
        for (JsonNode s : body.path("series")) {
            for (JsonNode p : s.path("points")) {
                byId.put(p.path("observation_id").asText(), p);
            }
        }
        assertThat(byId).as("the points of labs-printed-range").hasSize(labsPrintedRange().size());
        return byId;
    }

    private static String observation(int n) {
        return String.format("b6b6b6b6-0000-4000-8000-0000000001%02d", n);
    }

    /** {@code ref_low} and {@code ref_high} are JSON numbers or JSON null and {@code ref_text} text or null: never absent. */
    private static void assertRange(JsonNode point, Double low, Double high, String text) {
        String id = point.path("observation_id").asText();
        for (String field : List.of("ref_low", "ref_high", "ref_text")) {
            assertThat(point.has(field)).as(field + " of " + id + " is sent, null when not printed").isTrue();
        }
        Double[] expected = {low, high};
        String[] fields = {"ref_low", "ref_high"};
        for (int i = 0; i < 2; i++) {
            JsonNode n = point.path(fields[i]);
            if (expected[i] == null) {
                assertThat(n.isNull()).as(fields[i] + " of " + id + " is null, was " + n).isTrue();
            } else {
                assertThat(n.isNumber()).as(fields[i] + " of " + id + " is a number, was " + n).isTrue();
                assertThat(n.asDouble()).as(fields[i] + " of " + id).isEqualTo(expected[i]);
            }
        }
        if (text == null) {
            assertThat(point.path("ref_text").isNull()).as("ref_text of " + id + " is null, was " + point.path("ref_text")).isTrue();
        } else {
            assertThat(point.path("ref_text").asText()).as("ref_text of " + id).isEqualTo(text);
        }
    }

    @Test
    void aTwoSidedPrintedRangeIsSentWithBothNumbersAndItsText() throws IOException {
        Map<String, JsonNode> points = printedRangePoints();
        assertRange(points.get(observation(71)), 59.0, 104.0, "59–104");
        assertRange(points.get(observation(78)), 3.9, 6.1, "3.9–6.1");
    }

    @Test
    void eachPointCarriesItsOwnPapersRange() throws IOException {
        Map<String, JsonNode> points = printedRangePoints();
        assertRange(points.get(observation(71)), 59.0, 104.0, "59–104");
        assertRange(points.get(observation(75)), 62.0, 106.0, "62–106");
        JsonNode creatinine = null;
        for (JsonNode series : get(cases().get("labs-printed-range").get()).path("series")) {
            if (series.path("canonical_name").asText().equals("creatinine")) {
                creatinine = series;
            }
        }
        assertThat(creatinine).as("the creatinine series").isNotNull();
        assertThat(creatinine.path("points")).as("two printed ranges, still one series").hasSize(3);
    }

    /** A one-sided range carries only that side, beside a point the chip places beyond it (the threshold path). */
    @Test
    void aOneSidedPrintedRangeCarriesOnlyThatSide() throws IOException {
        Map<String, JsonNode> points = printedRangePoints();
        assertRange(points.get(observation(80)), null, 200.0, "<200");
        assertRange(points.get(observation(81)), 40.0, null, ">40");
    }

    /**
     * What the stopgap stores beside a one-sided range is UNKNOWN, above (210 by {@code <200}), below (38 by {@code >40})
     * or within the bound (180, 45). A one-sided range is never INSIDE (D35), so a region from the axis to 200 behind 180
     * would say "normal" where the chip says unknown: those points carry the words and no number at all.
     */
    @Test
    void aOneSidedBoundBesideAnUnknownPointIsTextOnly() throws IOException {
        Map<String, JsonNode> points = printedRangePoints();
        for (int n : new int[] {72, 76}) {
            assertThat(points.get(observation(n)).path("kind").asText()).isEqualTo("unknown");
            assertRange(points.get(observation(n)), null, null, "<200");
        }
        for (int n : new int[] {73, 77}) {
            assertThat(points.get(observation(n)).path("kind").asText()).isEqualTo("unknown");
            assertRange(points.get(observation(n)), null, null, ">40");
        }
    }

    /**
     * The band agrees with the point's chip or carries no number, by the paper page's pin rule (PaperDisplay.drawnBand),
     * so React never judges it. Potassium 5.0 is CRITICAL by a threshold inside its printed 3.5–5.1: a red point inside
     * the pale "normal" band. Potassium 4.2 has a NULL verdict inside it: an UNKNOWN chip the band would contradict.
     */
    @Test
    void aBandThatContradictsThePointsChipIsSentAsTextOnly() throws IOException {
        Map<String, JsonNode> points = printedRangePoints();
        assertThat(points.get(observation(82)).path("kind").asText()).isEqualTo("critical");
        assertRange(points.get(observation(82)), null, null, "3.5–5.1");
        assertThat(points.get(observation(83)).path("kind").asText()).isEqualTo("unknown");
        assertRange(points.get(observation(83)), null, null, "3.5–5.1");
    }

    /**
     * The other direction: a paper that printed no range sends all three null, never a default or a table's range, nor
     * its neighbour's. June's creatinine is OUTSIDE high (150), so a leaked 59–104 or August's 62–106 would agree with its
     * chip and be sent: this guard can fail on that leak.
     */
    @Test
    void aPointWithNoPrintedRangeSendsAllThreeNull() throws IOException {
        Map<String, JsonNode> points = printedRangePoints();
        assertRange(points.get(observation(79)), null, null, null);
        JsonNode bp = get(Case.labs(bpRows())).path("bp").path("points").get(0);
        assertRange(bp.path("systolic"), null, null, null);
        assertRange(bp.path("diastolic"), null, null, null);
    }

    @Test
    void printedTextTheBandCannotPlaceIsSentAsTextWithNoNumber() throws IOException {
        assertRange(printedRangePoints().get(observation(74)), null, null, "3.9-6.1");
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
        List<Map<String, Object>> served = cases().get("medicines-latest").get().latest().medicines();
        for (int i = 0; i < body.path("medicines").size(); i++) {
            JsonNode medicine = body.path("medicines").get(i);
            assertThat(allowed).as("keys of " + medicine).containsAll(fieldNames(medicine));
            assertThat(fieldNames(medicine.path("crop"))).containsExactly("src");
            assertThat(medicine.path("crop").path("src").asText()).as("medicine " + i + "'s own crop")
                    .isEqualTo(ImageRefs.crop(RX, (String) served.get(i).get("crop_key")));
        }
        assertThat(body.path("medicines").get(0).path("crop").path("src").asText()).as("each line its own crop")
                .isNotEqualTo(body.path("medicines").get(1).path("crop").path("src").asText());
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
