package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.extraction.application.service.MarkerMatcher;
import com.elioo.baymax.extraction.application.service.MarkerMatchingIsWholeWordTest;
import com.elioo.baymax.outbound.application.port.in.DocumentUrgencyUseCase;
import com.elioo.baymax.outbound.application.service.ComposedBodies;
import com.elioo.baymax.web.adapter.in.ActivePatient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan desktop-parity Task 3: the prototype's pretend family, as {@code docs/design/2026-10-21-approved/desktop.html}
 * defines it (its {@code PATIENTS}, {@code P}, {@code TIMES} and {@code fresh()}), seeded once in Postgres under the
 * prototype's own today (20 October, 13:00 in Dhaka) and read back through the real router, guard, services and
 * handlers, so every {@code proto*} fixture is Java's own output and every id agrees across areas (the thread's cards,
 * the papers, the stack and the banner name the same documents). The levels are not typed into rows: every lab paper's
 * values go through the real urgency writer ({@code DocumentUrgencyUseCase.assessAndStore}), and
 * {@link #theFamilyIsDesktopHtmls} checks it reached the prototype's own levels.
 *
 * <ul>
 *   <li>আম্মা (68, F; diabetes and high blood pressure ticked): lipid 13 Oct (one-sided ranges), creat 14 Oct (THIS_WEEK),
 *       rx 15 Oct (two medicines, back on 22 Oct), cbc 20 Oct (all inside), blur 20 Oct (NEEDS_RETAKE).</li>
 *   <li>আব্বু (74, M): sugar 10 Oct (all inside), potas 16 Oct (NOW: Potassium 11.0 against a printed 3.5–5.1).</li>
 * </ul>
 *
 * <p>The family's account is a Bangladeshi number in the fictional range (the prototype's "+880 17•• ••••71"), so the
 * banner and the NOW card offer 999. Explanations are the composer's own renderings ({@link ComposedBodies}), never the
 * prototype's sample sentences: the fixtures carry what Java sends. The file names start with {@link #PREFIX}; each
 * area's own contract test leaves those files to this one, as {@code MeApiContractTest} leaves {@code me/family-*}.</p>
 */
@Testcontainers
class ProtoFamilyContractTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    /** desktop.html's today: {@code dayLabel} calls 20 October আজ; 13:00 in Dhaka, after the blurred photo of 12:20. */
    static final Instant NOW = Instant.parse("2026-10-20T07:00:00Z");
    /** Every file this test writes starts with this; the areas' own tests leave such files to it. */
    static final String PREFIX = "proto";
    static final Path FIXTURES = Path.of("../medioo-web/fixtures/api");
    static final Path WRITTEN = Path.of("build/web-contract");
    private static final String K = "proto";
    private static final ObjectMapper JSON = new ObjectMapper();

    static final UUID FAMILY = FixedPapers.id(K, "family");
    static final UUID AMMA = FixedPapers.id(K, "amma");
    static final UUID ABBU = FixedPapers.id(K, "abbu");

    /** One paper of the prototype: its key in {@code P}, whose it is, and its type, as desktop.html names them. */
    record Paper(String key, UUID patient, String type) {
        UUID id() {
            return FixedPapers.id(K, key);
        }
    }

    /** {@code fresh()}: আম্মা's five, then আব্বু's two, each in the prototype's order. */
    static final List<Paper> PAPERS = List.of(
            new Paper("lipid", AMMA, "lab_report"), new Paper("creat", AMMA, "lab_report"), new Paper("rx", AMMA, "prescription"),
            new Paper("cbc", AMMA, "lab_report"), new Paper("blur", AMMA, "lab_report"),
            new Paper("sugar", ABBU, "lab_report"), new Paper("potas", ABBU, "lab_report"));

    /** area → file name (without .json) → the route it answers, in the order the bodies are read. */
    static Map<String, Map<String, String>> cases() {
        Map<String, Map<String, String>> cases = new LinkedHashMap<>();
        cases.put("now-banner", Map.of(PREFIX, "/app/api/now-banner"));
        Map<String, String> attention = new LinkedHashMap<>();
        Map<String, String> follows = new LinkedHashMap<>();
        Map<String, String> thread = new LinkedHashMap<>();
        Map<String, String> calendar = new LinkedHashMap<>();
        Map<String, String> timeline = new LinkedHashMap<>();
        Map<String, String> health = new LinkedHashMap<>();
        for (Map.Entry<String, UUID> p : patients().entrySet()) {
            // the desktop's lab values (plan desktop-parity L1) and the phone health tab beside them on the same page
            health.put(PREFIX + "-labs-" + p.getKey(), "/app/api/patients/" + p.getValue() + "/labs");
            health.put(PREFIX + "-medicines-" + p.getKey(), "/app/api/patients/" + p.getValue() + "/medicines");
            // desktop.html's month (calView(): October 2026), and the papers list the calendar's list view shows
            calendar.put(PREFIX + "-" + p.getKey(), "/app/api/patients/" + p.getValue() + "/calendar?month=2026-10");
            timeline.put(PREFIX + "-" + p.getKey(), "/app/api/patients/" + p.getValue() + "/timeline");
            attention.put(PREFIX + "-" + p.getKey(), "/app/api/patients/" + p.getValue() + "/attention");
            follows.put(PREFIX + "-" + p.getKey(), "/app/api/patients/" + p.getValue() + "/follow-ups");
            // read as the web's home page reads it (plan CHAT-web)
            thread.put(PREFIX + "-" + p.getKey(), "/app/api/patients/" + p.getValue() + "/thread?asks=1");
        }
        cases.put("attention", attention);
        cases.put("calendar", calendar);
        cases.put("timeline", timeline);
        cases.put("health", health);
        cases.put("follow-ups", follows);
        cases.put("me", Map.of(PREFIX, "/app/api/me"));
        // the desktop's family and account page (plan desktop-parity F1)
        cases.put("family", Map.of(PREFIX, "/app/api/family"));
        cases.put("thread", thread);
        Map<String, String> documents = new LinkedHashMap<>();
        PAPERS.forEach(p -> documents.put(PREFIX + "-" + p.key(), "/app/api/documents/" + p.id()));
        cases.put("documents", documents);
        return cases;
    }

    static Map<String, UUID> patients() {
        Map<String, UUID> m = new LinkedHashMap<>();
        m.put("amma", AMMA);
        m.put("abbu", ABBU);
        return m;
    }

    /** {@code area/name} for every case, the parameterized test's names. */
    static Stream<String> caseNames() {
        return cases().entrySet().stream().flatMap(a -> a.getValue().keySet().stream().map(n -> a.getKey() + "/" + n));
    }

    /** The route templates the web's MSW handlers key on, as each area's own contract test writes them in {@code _meta}. */
    static final Map<String, String> SOURCES = Map.of(
            "now-banner", "GET /app/api/now-banner",
            "attention", "GET /app/api/patients/{id}/attention",
            "calendar", "GET /app/api/patients/{id}/calendar",
            "timeline", "GET /app/api/patients/{id}/timeline",
            "follow-ups", "GET /app/api/patients/{id}/follow-ups",
            "me", "GET /app/api/me",
            "family", "GET /app/api/family",
            "thread", "GET /app/api/patients/{id}/thread?asks=1",
            "documents", "GET /app/api/documents/{id}");

    /**
     * The route a case answers, as its area's own contract test writes it: {@link #SOURCES}, except the health area,
     * which holds two routes (HealthApiContractTest's labs-* and medicines-*), told apart by the case's name.
     */
    static String sourceOf(String area, String name) {
        if ("health".equals(area)) {
            return name.startsWith(PREFIX + "-medicines-") ? "GET /app/api/patients/{id}/medicines" : "GET /app/api/patients/{id}/labs";
        }
        return SOURCES.get(area);
    }

    // ---- the family, seeded once ---------------------------------------------------------------------------------

    /** What the family's session read, case by case: computed once, in {@link #cases()}'s order, then only compared. */
    private static Map<String, EntityExchangeResult<byte[]>> served;

    static ReactiveWebApplicationContextRunner runner() {
        return FixedPapers.runnerAt(POSTGRES, NOW).withPropertyValues("baymax.web.preview-family-ids=" + FAMILY);
    }

    static synchronized Map<String, EntityExchangeResult<byte[]>> served() {
        if (served == null) {
            Map<String, EntityExchangeResult<byte[]>> out = new LinkedHashMap<>();
            runner().run(context -> {
                String session = seed(context);
                WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
                for (Map.Entry<String, Map<String, String>> area : cases().entrySet()) {
                    for (Map.Entry<String, String> c : area.getValue().entrySet()) {
                        out.put(area.getKey() + "/" + c.getKey(), client.get().uri(c.getValue())
                                .cookie("baymax_session", session)
                                // the prototype opens on আম্মা (st.patient): her choice is remembered, as the switcher's PUT leaves it
                                .cookie(ActivePatient.COOKIE, AMMA.toString())
                                .exchange().expectBody().returnResult());
                    }
                }
            });
            assertThat(out).as("bodies read").hasSize((int) caseNames().count());
            served = out;
        }
        return served;
    }

    /** Seeds the family under {@link #NOW} and returns its session token. */
    static String seed(ApplicationContext context) throws Exception {
        FixedPapers.family(POSTGRES, FAMILY, "+8801700000171", "Tanvir");
        patient(AMMA, "আম্মা", 68, "female", "{diabetes,hypertension}", Instant.parse("2026-09-01T00:00:00Z"));
        patient(ABBU, "আব্বু", 74, "male", "{}", Instant.parse("2026-09-01T00:01:00Z"));

        // desktop.html's P and TIMES: the printed date, the upload time in Dhaka, the facility, and each row
        // [test, result, unit, printed low, printed high, flag printed on the paper]
        lab(context, "lipid", AMMA, "2026-10-13", "2026-10-13T05:02:00Z", List.of(
                row("LDL Cholesterol", "90", "mg/dL", null, "100", null),
                row("HDL Cholesterol", "52", "mg/dL", "40", null, null),
                row("Triglycerides", "120", "mg/dL", null, "150", null)));
        lab(context, "creat", AMMA, "2026-10-14", "2026-10-14T03:12:00Z", List.of(
                row("Serum Creatinine", "1.6", "mg/dL", "0.6", "1.3", "high"),
                row("Blood Urea", "32", "mg/dL", "15", "40", null),
                row("Serum Potassium", "4.3", "mmol/L", "3.5", "5.1", null)));
        UUID rx = FixedPapers.paper(POSTGRES, FixedPapers.id(K, "rx"), profile(AMMA), "prescription", "DONE", "2026-10-15",
                Instant.parse("2026-10-15T11:40:00Z"), null);
        FixedPapers.exec(POSTGRES, "UPDATE baymax.document SET facility = 'Shapla Nagar Clinic' WHERE id = ?", rx);
        medicine(rx, AMMA, "demoprol", "Tab. Demoprol", "5 mg", "১+০+১", "খাবার পরে", "১ মাস", Instant.parse("2026-10-15T11:40:00Z"));
        medicine(rx, AMMA, "exampin", "Tab. Exampin", "500", "০+০+১", "রাতে খাবার পরে", "১৪ দিন", Instant.parse("2026-10-15T11:40:00Z"));
        // "ডাক্তার ২২ অক্টোবর আবার যেতে বলেছেন": the doctor's words as a prescription writes them, seven days after the 15th
        FixedPapers.followUp(POSTGRES, FixedPapers.id(K, "rx-follow"), profile(AMMA), rx, "৭ দিন পর আবার আসবেন", "2026-10-22");
        assess(context, rx);
        lab(context, "cbc", AMMA, "2026-10-20", "2026-10-20T02:30:00Z", List.of(
                row("HbA1c", "5.4", "%", "4.0", "5.6", null),
                row("Haemoglobin", "12.6", "g/dL", "11.5", "15.5", null),
                row("Platelet Count", "245", "10^3/µL", "150", "450", null)));
        // blur: the photo was too blurred to read (retake.reason.low_overall, "পুরো ছবিটা ঝাপসা"), so nothing was read or kept
        FixedPapers.paper(POSTGRES, FixedPapers.id(K, "blur"), profile(AMMA), "lab_report", "NEEDS_RETAKE", null,
                Instant.parse("2026-10-20T06:20:00Z"), null);
        lab(context, "sugar", ABBU, "2026-10-10", "2026-10-10T13:15:00Z", List.of(
                row("Fasting Blood Sugar", "5.2", "mmol/L", "3.9", "6.1", null),
                row("Serum Creatinine", "1.1", "mg/dL", "0.7", "1.3", null)));
        lab(context, "potas", ABBU, "2026-10-16", "2026-10-16T04:05:00Z", List.of(
                row("Serum Potassium", "11.0", "mmol/L", "3.5", "5.1", "high"),
                row("Serum Sodium", "139", "mmol/L", "135", "145", null)));

        // each paper's one photographed page, in the storage ledger (what /me counts as a patient's documents)
        for (Paper p : PAPERS) {
            FixedPapers.exec(POSTGRES, "INSERT INTO baymax.stored_object (family_id, patient_id, document_id, kind, page_no, storage_key,"
                    + " content_type, size_bytes, created_at) SELECT family_id, patient_id, id, 'page', 1, ?, 'image/jpeg', 182000, created_at"
                    + " FROM baymax.document WHERE id = ?", FAMILY + "/" + p.patient() + "/" + p.id() + "/page-1.jpg", p.id());
        }

        // what WhatsApp delivered: each finished paper's explanation (its card carries it as wording), the retake notice
        for (Paper p : PAPERS) {
            if ("blur".equals(p.key())) {
                delivered(p, "retake", "released", ComposedBodies.retake(context).body(), Instant.parse("2026-10-20T06:21:00Z"));
                continue;
            }
            String level = FixedPapers.rows(POSTGRES, "SELECT urgency FROM baymax.document WHERE id = '" + p.id() + "'").get(0).get(0);
            Instant received = Instant.parse(FixedPapers.rows(POSTGRES, "SELECT to_char(created_at AT TIME ZONE 'UTC',"
                    + " 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') FROM baymax.document WHERE id = '" + p.id() + "'").get(0).get(0));
            // above routine the reviewer approves it first (DR-13's urgency gate); routine is released as composed
            delivered(p, "explanation", "routine".equals(level) ? "released" : "approved",
                    ComposedBodies.explanation(context, p.id()).body(), received.plus(Duration.ofMinutes(2)));
        }
        return FixedPapers.session(context, FAMILY, NOW);
    }

    static com.elioo.baymax.healthrecord.domain.PatientProfile profile(UUID patient) {
        return new com.elioo.baymax.healthrecord.domain.PatientProfile(patient, FAMILY, null, 0, null, List.of(), null, null);
    }

    static void patient(UUID id, String name, int age, String sex, String flags, Instant at) throws Exception {
        FixedPapers.exec(POSTGRES, "INSERT INTO baymax.patient_profile (id, family_id, name, age, sex, chronic_flags, proxy_consent_at,"
                + " created_at) VALUES (?, ?, ?, ?, ?, CAST(? AS text[]), ?, ?)", id, FAMILY, name, age, sex, flags,
                Timestamp.from(at), Timestamp.from(at));
    }

    record Row(String name, String value, String unit, String low, String high, String flag) {
    }

    static Row row(String name, String value, String unit, String low, String high, String flag) {
        return new Row(name, value, unit, low, high, flag);
    }

    /**
     * A DONE lab report with its values (each with its crop), assessed by the real urgency writer. Each value's canonical
     * name is the one extraction would store, from the real {@link MarkerMatcher} over the shipped markers.properties (never typed
     * here): what puts a value in the patient's lab series (plan desktop-parity L1), and a value no marker matches stays
     * out of them, as in production.
     */
    static void lab(ApplicationContext context, String key, UUID patient, String printed, String uploaded, List<Row> rows) throws Exception {
        UUID doc = FixedPapers.paper(POSTGRES, FixedPapers.id(K, key), profile(patient), "lab_report", "DONE", printed,
                Instant.parse(uploaded), null);
        MarkerMatcher markers = MarkerMatchingIsWholeWordTest.shippedMatcher();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            UUID obs = FixedPapers.id(K, key + "-value-" + (i + 1));
            FixedPapers.exec(POSTGRES, "INSERT INTO baymax.observation (id, patient_id, document_id, name, canonical_name, value, unit,"
                            + " ref_low, ref_high, flag, crop_key, observed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", obs, patient, doc,
                    r.name(), markers.canonicalFor(r.name(), null).orElse(null), r.value(), r.unit(), r.low(), r.high(), r.flag(),
                    FAMILY + "/" + patient + "/" + doc + "/crop-" + obs.toString().substring(0, 8) + ".jpg",
                    Timestamp.from(Instant.parse(uploaded)));
        }
        assess(context, doc);
    }

    static void assess(ApplicationContext context, UUID document) {
        context.getBean(DocumentUrgencyUseCase.class).assessAndStore(document).block(Duration.ofSeconds(30));
    }

    static void medicine(UUID document, UUID patient, String role, String name, String dose, String frequency, String timing,
                         String duration, Instant at) throws Exception {
        UUID id = FixedPapers.id(K, "medicine-" + role);
        FixedPapers.exec(POSTGRES, "INSERT INTO baymax.medication_event (id, patient_id, document_id, name, dose_text, frequency_text,"
                        + " timing_text, duration_text, crop_key, at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", id, patient, document, name, dose,
                frequency, timing, duration, FAMILY + "/" + patient + "/" + document + "/med-" + id.toString().substring(0, 8) + ".jpg",
                Timestamp.from(at));
    }

    /** A message the provider accepted (V14: sent, with its wamid), composed a minute before. */
    static void delivered(Paper p, String kind, String gate, String body, Instant sentAt) throws Exception {
        FixedPapers.exec(POSTGRES, "INSERT INTO baymax.outbound_message (id, family_id, patient_id, document_id, kind, urgency, urgency_reasons,"
                        + " body, gate_status, reviewer, decided_at, sent_at, delivery_status, provider_message_id, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, 'routine', '[]', ?, ?, ?, ?, ?, 'sent', ?, ?)",
                FixedPapers.id(K, p.key() + "-" + kind), FAMILY, p.patient(), p.id(), kind, body, gate,
                "approved".equals(gate) ? "tanvir" : null, "approved".equals(gate) ? Timestamp.from(sentAt.minusSeconds(30)) : null,
                Timestamp.from(sentAt), "wamid.proto-" + p.key(), Timestamp.from(sentAt.minusSeconds(60)));
    }

    // ---- the fixtures ---------------------------------------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("caseNames")
    void eachProtoCaseIsItsCommittedMswFixture(String areaAndName) throws IOException {
        String area = areaAndName.substring(0, areaAndName.indexOf('/'));
        String name = areaAndName.substring(area.length() + 1);
        EntityExchangeResult<byte[]> r = served().get(areaAndName);
        assertThat(r.getStatus().value()).as(areaAndName + " answered").isEqualTo(200);
        AttentionApiContractTest.write(ProtoFamilyContractTest.class, FIXTURES.resolve(area), WRITTEN.resolve(area), area, name,
                sourceOf(area, name) + " over the prototype family (desktop.html), case " + name, r);
    }

    /** Both ways: every committed {@code proto*} file in any area is a current case, and every case is committed. */
    @Test
    void everyCommittedProtoFixtureIsACurrentCase() throws IOException {
        List<String> committed = new ArrayList<>();
        for (String area : AttentionApiContractTest.areas()) {
            for (String file : AttentionApiContractTest.committed(FIXTURES.resolve(area))) {
                if (file.startsWith(PREFIX)) {
                    committed.add(area + "/" + file.substring(0, file.length() - ".json".length()));
                }
            }
        }
        assertThat(committed).as("committed proto fixtures").isNotEmpty();
        assertThat(committed).containsExactlyInAnyOrderElementsOf(caseNames().toList());
    }

    // ---- the family is the prototype's ------------------------------------------------------------------------------

    private static JsonNode body(String areaAndName) throws IOException {
        return JSON.readTree(served().get(areaAndName).getResponseBody());
    }

    /**
     * Read by field from what Java served: the names and ages, each paper's type, printed date and Java's own level
     * against the prototype's {@code urgency} (creat THIS_WEEK, potas NOW, the rest filed, blur a retake), the
     * sidebar's level per patient, the NOW banner naming potas with 999, and the doctor's date of 22 October.
     */
    @Test
    void theFamilyIsDesktopHtmls() throws Exception {
        JsonNode me = body("me/" + PREFIX);
        Map<String, JsonNode> byName = new LinkedHashMap<>();
        me.get("patients").forEach(p -> byName.put(p.get("name").asText(), p));
        assertThat(byName.keySet()).containsExactly("আম্মা", "আব্বু");
        assertThat(byName.get("আম্মা").get("age").asInt()).isEqualTo(68);
        assertThat(byName.get("আব্বু").get("age").asInt()).isEqualTo(74);
        assertThat(byName.get("আম্মা").get("name_possessive").asText()).isEqualTo("আম্মার");
        assertThat(byName.get("আম্মা").get("urgency").asText()).as("আম্মা: creat").isEqualTo("this_week");
        assertThat(byName.get("আব্বু").get("urgency").asText()).as("আব্বু: potas").isEqualTo("now");
        assertThat(byName.get("আম্মা").get("urgent").asBoolean()).isFalse();
        assertThat(byName.get("আব্বু").get("urgent").asBoolean()).isTrue();
        assertThat(byName.get("আম্মা").get("documents").asInt()).isEqualTo(5);
        assertThat(byName.get("আব্বু").get("documents").asInt()).isEqualTo(2);
        assertThat(me.get("active_patient_id").asText()).isEqualTo(AMMA.toString());
        assertThat(me.get("preview").asBoolean()).isTrue();

        Map<String, String> levels = new LinkedHashMap<>();
        Map<String, String> statuses = new LinkedHashMap<>();
        for (Paper p : PAPERS) {
            JsonNode paper = body("documents/" + PREFIX + "-" + p.key());
            assertThat(paper.get("id").asText()).isEqualTo(p.id().toString());
            assertThat(paper.get("patient").get("id").asText()).isEqualTo(p.patient().toString());
            levels.put(p.key(), paper.path("urgency").isNull() ? null : paper.path("urgency").asText());
            statuses.put(p.key(), paper.get("status").asText());
        }
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("lipid", "routine");
        expected.put("creat", "this_week");
        expected.put("rx", "routine");
        expected.put("cbc", "routine");
        expected.put("blur", null);
        expected.put("sugar", "routine");
        expected.put("potas", "now");
        assertThat(levels).as("each paper's level, as Java's urgency writer assessed it").containsExactlyEntriesOf(expected);
        assertThat(statuses.get("blur")).isEqualTo("NEEDS_RETAKE");

        JsonNode lipid = body("documents/" + PREFIX + "-lipid");
        lipid.get("values").forEach(v -> assertThat(v.get("display_kind").asText()).as("lipid " + v.get("name").asText()
                + ": a one-sided range Medioo cannot place").isEqualTo("unknown"));
        JsonNode cbc = body("documents/" + PREFIX + "-cbc");
        assertThat(cbc.get("values")).hasSize(3);
        cbc.get("values").forEach(v -> assertThat(v.get("display_kind").asText()).as("cbc " + v.get("name").asText()).isEqualTo("inside"));
        JsonNode rxPaper = body("documents/" + PREFIX + "-rx");
        assertThat(rxPaper.get("medicines")).hasSize(2);
        assertThat(rxPaper.get("facility").asText()).isEqualTo("Shapla Nagar Clinic");
        assertThat(rxPaper.get("follow_up").get(0).get("due_date").asText()).isEqualTo("2026-10-22");
        JsonNode potas = body("documents/" + PREFIX + "-potas");
        assertThat(potas.get("now").get("call_999").asBoolean()).as("a Bangladeshi viewer").isTrue();

        JsonNode banner = body("now-banner/" + PREFIX);
        assertThat(banner.get("show").asBoolean()).isTrue();
        assertThat(banner.get("call_999").asBoolean()).isTrue();
        assertThat(banner.get("documents")).hasSize(1);
        assertThat(banner.get("documents").get(0).get("document_id").asText()).isEqualTo(FixedPapers.id(K, "potas").toString());
        assertThat(banner.get("documents").get(0).get("patient_id").asText()).isEqualTo(ABBU.toString());

        List<String> ammaStack = new ArrayList<>();
        body("attention/" + PREFIX + "-amma").get("items")
                .forEach(i -> ammaStack.add(i.get("kind").asText() + (i.has("level") ? ":" + i.get("level").asText() : "")));
        assertThat(ammaStack).as("আম্মা's stack").containsExactly("urgent:this_week", "retake", "follow_up");
        List<String> abbuStack = new ArrayList<>();
        body("attention/" + PREFIX + "-abbu").get("items")
                .forEach(i -> abbuStack.add(i.get("kind").asText() + (i.has("level") ? ":" + i.get("level").asText() : "")));
        assertThat(abbuStack).as("আব্বু's stack").containsExactly("urgent:now");

        JsonNode follows = body("follow-ups/" + PREFIX + "-amma").get("items");
        assertThat(follows).hasSize(1);
        assertThat(follows.get(0).get("due_date").asText()).isEqualTo("2026-10-22");
        assertThat(body("follow-ups/" + PREFIX + "-abbu").get("items")).isEmpty();

        // desktop.html's calendar (calView(), October 2026): every paper of each patient on its October day, and আম্মা's
        // doctor's date on the 22nd, ringed (plan desktop-parity C1).
        for (Map.Entry<String, UUID> p : patients().entrySet()) {
            JsonNode month = body("calendar/" + PREFIX + "-" + p.getKey());
            assertThat(month.get("month").asText()).isEqualTo("2026-10");
            List<String> onCalendar = new ArrayList<>();
            List<String> visits = new ArrayList<>();
            month.get("days").forEach(d -> {
                d.get("papers").forEach(x -> onCalendar.add(x.get("document_id").asText()));
                d.get("follow_ups").forEach(x -> visits.add(d.get("date").asText()));
            });
            assertThat(onCalendar).as(p.getKey() + "'s October").containsExactlyInAnyOrderElementsOf(PAPERS.stream()
                    .filter(x -> x.patient().equals(p.getValue())).map(x -> x.id().toString()).toList());
            assertThat(visits).as(p.getKey() + "'s doctor's dates").isEqualTo("amma".equals(p.getKey()) ? List.of("2026-10-22") : List.of());
        }

        // the desktop's lab values (plan desktop-parity L1): every value a marker tracks is in its patient's series, asked of
        // the database rather than listed here, and the prototype's two levels are the series' own (creat, potas)
        for (Map.Entry<String, UUID> p : patients().entrySet()) {
            List<String> inSeries = new ArrayList<>();
            body("health/" + PREFIX + "-labs-" + p.getKey()).get("series")
                    .forEach(x -> x.get("points").forEach(pt -> inSeries.add(pt.get("observation_id").asText())));
            List<String> tracked = FixedPapers.rows(POSTGRES, "SELECT id FROM baymax.observation WHERE patient_id = '" + p.getValue()
                    + "' AND canonical_name IS NOT NULL").stream().map(r -> r.get(0)).toList();
            assertThat(tracked).as(p.getKey() + "'s tracked values (none would pass the sweep vacuously)").isNotEmpty();
            assertThat(inSeries).as(p.getKey() + "'s lab series").containsExactlyInAnyOrderElementsOf(tracked);
        }
        Map<String, String> levelOf = new LinkedHashMap<>();
        for (Map.Entry<String, UUID> p : patients().entrySet()) {
            body("health/" + PREFIX + "-labs-" + p.getKey()).get("series").forEach(x -> levelOf.put(p.getKey() + ":"
                    + x.get("canonical_name").asText(), x.get("points").get(0).get("kind").asText()));
        }
        assertThat(levelOf).containsEntry("amma:creatinine", "outside").containsEntry("abbu:potassium", "critical");

        // every paper is on its patient's thread, as a card
        for (Map.Entry<String, UUID> p : patients().entrySet()) {
            List<String> cards = new ArrayList<>();
            body("thread/" + PREFIX + "-" + p.getKey()).get("items").forEach(i -> {
                if ("paper".equals(i.get("kind").asText())) {
                    cards.add(i.get("card").get("id").asText());
                }
            });
            assertThat(cards).as(p.getKey() + "'s thread cards").containsExactlyInAnyOrderElementsOf(PAPERS.stream()
                    .filter(x -> x.patient().equals(p.getValue())).map(x -> x.id().toString()).toList());
        }
    }
}
