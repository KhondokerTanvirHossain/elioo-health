package com.elioo.baymax.testsupport;

import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.extraction.domain.FiledDay;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.web.domain.GateState;
import com.elioo.baymax.web.domain.ReadingView;
import com.elioo.baymax.web.domain.ResultView;
import com.elioo.baymax.web.domain.ViewerLocale;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * One paper's stored rows and views, for every test that draws a paper: the HTML render tests (through
 * {@code ResultFixtures}, which delegates here) and the {@code /app/api} contract tests. The only test code that calls
 * {@link DocumentView}'s constructor, so a shape change lands here once. The ids and the clock are fixed, so a JSON
 * response written from these fixtures is the same bytes on every run (the committed MSW fixtures depend on it).
 * Names and numbers are fictional.
 */
public final class PaperFixtures {

    public static final Instant NOW = Instant.parse("2026-10-20T04:00:00Z");
    public static final UUID FAMILY = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100001");
    public static final UUID MA_ID = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100002");
    public static final UUID DOC = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100003");
    public static final PatientProfile MA = new PatientProfile(MA_ID, FAMILY, "Ma", 64, PatientProfile.Sex.FEMALE, List.of(), NOW, NOW);
    public static final ViewerLocale BD = new ViewerLocale(true);
    public static final ViewerLocale ABROAD = new ViewerLocale(false);
    /** The typical review time a PENDING result carries (spec §4.10 "[N]"), as the default configuration sets it. */
    public static final int REVIEW_HOURS = 2;

    private PaperFixtures() {
    }

    // ---- stored rows ------------------------------------------------------------------------------------

    /**
     * A stored OUTSIDE or CRITICAL with its side, as V19 requires (observation_verdict_side_matches_kind): the shape the
     * producer writes. The six-argument form carries no side: right for INSIDE, UNKNOWN and NULL, and for OUTSIDE or
     * CRITICAL only to test a row the database cannot hold.
     */
    public static Map<String, Object> value(String name, String value, String unit, String low, String high, String verdict, String side) {
        Map<String, Object> m = value(name, value, unit, low, high, verdict);
        m.put("verdict_side", side);
        return m;
    }

    public static Map<String, Object> value(String name, String value, String unit, String low, String high, String verdict) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", UUID.nameUUIDFromBytes((name + value).getBytes(StandardCharsets.UTF_8)));
        m.put("name", name);
        m.put("canonical_name", name.toLowerCase(Locale.ROOT));
        m.put("value", value);
        if (unit != null) { m.put("unit", unit); }
        if (low != null) { m.put("ref_low", low); }
        if (high != null) { m.put("ref_high", high); }
        if (verdict != null) { m.put("verdict", verdict); }
        m.put("crop_key", "f/p/d/crop-" + name.replaceAll("[^A-Za-z0-9]", "") + ".jpg");
        return m;
    }

    /** A medicine line as stored: the fields as written, the extraction's own guesses beside them, and its crop. */
    public static Map<String, Object> medicine(String name, String dose, String duration) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("dose_text", dose);
        m.put("duration_text", duration);
        m.put("purpose", "fever");
        m.put("status", "current");
        m.put("confidence", 0.4);
        m.put("crop_key", "f/p/d/med-" + name.replaceAll("[^A-Za-z0-9]", "") + ".jpg");
        return m;
    }

    /** A medicine line with every field a prescription writes, beside the extraction's own guesses and its crop. */
    public static Map<String, Object> medicine(String name, String dose, String frequency, String timing, String duration) {
        Map<String, Object> m = medicine(name, dose, duration);
        m.put("frequency_text", frequency);
        m.put("timing_text", timing);
        return m;
    }

    /** A follow-up as stored, with its crop. */
    public static Map<String, Object> followUp(String instruction, String due) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instruction", instruction);
        if (due != null) { m.put("due_date", due); }
        m.put("crop_key", "f/p/d/fu-" + instruction.replaceAll("[^A-Za-z0-9]", "") + ".jpg");
        return m;
    }

    /** One clinical-context line; {@code crop} null is a legacy line stored without one. */
    public static Map<String, Object> line(String text, String crop) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", text);
        if (crop != null) { m.put("crop_key", crop); }
        return m;
    }

    // ---- document views ---------------------------------------------------------------------------------

    public static DocumentView lab(List<Map<String, Object>> values) {
        return lab("Diagnostic Centre, Mirpur", values);
    }

    public static DocumentView lab(String facility, List<Map<String, Object>> values) {
        return lab(facility, values, List.of(), Map.of());
    }

    /** A two-page lab report that also carries the doctor's follow-up and clinical-context lines as stored. */
    public static DocumentView lab(String facility, List<Map<String, Object>> values, List<Map<String, Object>> followUp,
                                   Map<String, Object> clinicalContext) {
        return new DocumentView(DOC.toString(), "DONE", null, "lab_report", "2026-10-12", facility, 0.92,
                "m", 2, values, List.of(), followUp, clinicalContext, null);
    }

    /** B6: a two-page DONE paper carrying every kind of fact: values, medicines, follow-ups and the doctor's lines. */
    public static DocumentView paper(List<Map<String, Object>> values, List<Map<String, Object>> medicines, List<Map<String, Object>> followUp,
                                     Map<String, Object> clinicalContext) {
        return paper("2026-10-12", values, medicines, followUp, clinicalContext, null);
    }

    /** As above, with its printed date ({@code null}: nothing printed) and its stored V6 counts. */
    public static DocumentView paper(String printedDate, List<Map<String, Object>> values, List<Map<String, Object>> medicines,
                                     List<Map<String, Object>> followUp, Map<String, Object> clinicalContext, Map<String, Object> unverified) {
        return new DocumentView(DOC.toString(), "DONE", null, "lab_report", printedDate, "Shapla Diagnostics", 0.9,
                "m", 2, values, medicines, followUp, clinicalContext, unverified);
    }

    /** B6: a one-page DONE lab report that printed no date (t9-style), with or without a facility. */
    public static DocumentView undated(String facility, List<Map<String, Object>> values) {
        return new DocumentView(DOC.toString(), "DONE", null, "lab_report", null, facility, 0.9,
                "m", 1, values, List.of(), List.of(), Map.of(), null);
    }

    public static DocumentView prescription(List<Map<String, Object>> medicines, Map<String, Object> clinicalContext, Map<String, Object> unverified) {
        return new DocumentView(DOC.toString(), "DONE", null, "prescription", "2026-10-12", "Demo Chamber", 0.9,
                "m", 1, List.of(), medicines, List.of(), clinicalContext, unverified);
    }

    public static DocumentView pending(String status) {
        return new DocumentView(DOC.toString(), status, null, null, null, null, null, null, 1, null, null, null, null, null);
    }

    // ---- result and reading views -----------------------------------------------------------------------

    /**
     * D2: the filed day the document row would read back for this view, as V25's generated columns define it: a retake
     * or a paper with no printed date under its upload day in Dhaka, every other paper under its printed date, never
     * marked uncertain. Test support only — it stands in for the row a mocked read never touches; main code reads the
     * columns and never this. A copy of V25's rule, so {@code FilingDayOnPostgresTest.theFixturesFileAsPostgresDoes}
     * holds it equal to the generated columns on real rows: if V25 changes, that test fails before any fixture lies.
     */
    public static FiledDay filedAsStored(DocumentView view, Instant receivedAt) {
        String printed = view.documentDate();
        if ("NEEDS_RETAKE".equals(view.status()) || printed == null || printed.isBlank()) {
            return new FiledDay(com.elioo.baymax.common.time.PatientCalendar.dayOf(receivedAt), true, false);
        }
        // doc_date is a DATE column and the view prints it with LocalDate.toString(), so a stored printed date is ISO
        return new FiledDay(java.time.LocalDate.parse(printed), false, false);
    }

    /** The same view, its printed date marked uncertain by the model (Q6); a view filed under its upload day cannot be. */
    public static ResultView uncertain(ResultView r) {
        return new ResultView(r.document(), r.patient(), r.owner(), r.viewer(), r.urgency(), r.urgencyReasons(), r.gate(), r.wording(),
                r.retakeOrdinal(), r.reviewHours(), r.receivedAt(), r.now(), new FiledDay(r.date().date(), r.date().uploadDay(), true));
    }

    public static ResultView result(DocumentView view, Urgency urgency, List<String> reasons, GateState gate, String wording, ViewerLocale viewer) {
        return result(view, MA, urgency, reasons, gate, wording, viewer);
    }

    public static ResultView result(DocumentView view, PatientProfile patient, Urgency urgency, List<String> reasons, GateState gate,
                                    String wording, ViewerLocale viewer) {
        return result(view, patient, urgency, reasons, gate, wording, viewer, NOW.minusSeconds(60));
    }

    /** As above, sent at {@code receivedAt}. */
    public static ResultView result(DocumentView view, PatientProfile patient, Urgency urgency, List<String> reasons, GateState gate,
                                    String wording, ViewerLocale viewer, Instant receivedAt) {
        return new ResultView(view, patient, true, viewer, "DONE".equals(view.status()) ? urgency : null, reasons, gate, wording,
                0, REVIEW_HOURS, receivedAt, NOW, filedAsStored(view, receivedAt));
    }

    /**
     * B5: a one-page NEEDS_RETAKE paper of Ma's with its reason code, as intake views it (no prose: a view has none),
     * the {@code ordinal}-th retake in a row; {@code owner} false is a view-only share member.
     */
    public static ResultView retake(String code, int ordinal, boolean owner) {
        DocumentView view = new DocumentView(DOC.toString(), "NEEDS_RETAKE", code, null, null, null, null, null, 1,
                null, null, null, null, null);
        return new ResultView(view, MA, owner, BD, null, List.of(), GateState.NONE, null, ordinal, REVIEW_HOURS, NOW.minusSeconds(60), NOW,
                filedAsStored(view, NOW.minusSeconds(60)));
    }

    /** B5: a one-page FAILED paper of Ma's, seen by its owner. */
    public static ResultView failed() {
        DocumentView view = new DocumentView(DOC.toString(), "FAILED", null, null, null, null, null, null, 1,
                null, null, null, null, null);
        return new ResultView(view, MA, true, BD, null, List.of(), GateState.NONE, null, 0, REVIEW_HOURS, NOW.minusSeconds(60), NOW,
                filedAsStored(view, NOW.minusSeconds(60)));
    }

    /**
     * A FAILED or NEEDS_RETAKE paper that nonetheless holds stored rows — a value, a medicine, a follow-up and a doctor's
     * line, each with its crop. The pipeline persists none today; a partial persist, a recrop or a later reprocess could.
     * The page draws none of them for a read the system rejected, and neither may the JSON.
     */
    public static ResultView rejectedWithRows(String status, String code) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("diagnosis", List.of(line("Hyperkalaemia", "f/p/d/ctx-dx-1.jpg")));
        DocumentView view = new DocumentView(DOC.toString(), status, code, "lab_report", "2026-10-12", "Shapla Diagnostics", 0.3,
                "m", 1, List.of(potassiumCritical()), List.of(medicine("Napa 500", "1+0+1", "7 days")),
                List.of(followUp("Repeat the test after 7 days", "2026-10-19")), context,
                Map.of("values", 1, "medicines", 0, "follow_up", 0, "total", 1));
        return new ResultView(view, MA, true, BD, null, List.of(), GateState.NONE, null, "NEEDS_RETAKE".equals(status) ? 1 : 0,
                REVIEW_HOURS, NOW.minusSeconds(60), NOW, filedAsStored(view, NOW.minusSeconds(60)));
    }

    /** The same view with another typical review time, so a test can tell the served value from a default. */
    public static ResultView withReviewHours(ResultView r, int hours) {
        return new ResultView(r.document(), r.patient(), r.owner(), r.viewer(), r.urgency(), r.urgencyReasons(), r.gate(), r.wording(),
                r.retakeOrdinal(), hours, r.receivedAt(), r.now(), r.date());
    }

    /** A paper still being read ({@code RECEIVED} or {@code PROCESSING}), sent a minute ago, seen by its owner in Bangladesh. */
    public static ReadingView reading(String status) {
        return new ReadingView(pending(status), MA, true, BD, NOW.minusSeconds(60), NOW);
    }

    // ---- the papers the /app/api contract and the MSW fixtures are drawn from ---------------------------

    /** Potassium far above its printed range: a stored CRITICAL with its side. */
    public static Map<String, Object> potassiumCritical() {
        return value("Potassium", "6.9", "mmol/L", "3.5", "5.1", "critical", "high");
    }

    /** Sodium below its printed range: a stored OUTSIDE with its side. */
    public static Map<String, Object> sodiumLow() {
        return value("Sodium", "128", "mmol/L", "135", "145", "outside", "low");
    }

    /** Haemoglobin inside both printed bounds: an INSIDE the code proves. */
    public static Map<String, Object> haemoglobinInside() {
        return value("Haemoglobin", "13.1", "g/dL", "12.0", "15.5", "inside");
    }

    /** LDL with only an upper bound printed: a stored INSIDE the code cannot prove, so UNKNOWN. */
    public static Map<String, Object> ldlOneSided() {
        return value("LDL", "90", "mg/dL", null, "100", "inside");
    }

    /**
     * The NOW paper: a critical and an outside value, an inside one and a one-sided one; a medicine; a follow-up; the
     * doctor's diagnosis with one legacy line stored without a crop, and the referral (never shown); the stored V6 counts.
     * Stored reasons in the rule's order (values in printed order, so the outside one first).
     */
    public static ResultView nowPaper(ViewerLocale viewer) {
        DocumentView view = paper("2026-10-12",
                List.of(sodiumLow(), potassiumCritical(), haemoglobinInside(), ldlOneSided()),
                List.of(medicine("Napa 500", "1+0+1", "7 days")),
                List.of(followUp("Repeat the test after 7 days", "2026-10-19")),
                nowContext(),
                Map.of("values", 1, "medicines", 0, "follow_up", 0, "total", 1));
        return result(view, MA, Urgency.NOW, List.of("value_outside_range:sodium|stopgap", "value_critical:potassium|threshold:demo"),
                GateState.RELEASED, "Potassium is far above the printed range.", viewer);
    }

    private static Map<String, Object> nowContext() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("diagnosis", List.of(line("Hyperkalaemia", "f/p/d/ctx-dx-1.jpg"), line("CKD stage 3", null)));
        context.put("referral", "Nephrology OPD");
        return context;
    }

    /** A THIS_WEEK paper whose explanation is still with the reviewer. */
    public static ResultView thisWeekPending() {
        DocumentView view = paper("2026-10-12", List.of(sodiumLow(), haemoglobinInside()), List.of(), List.of(), Map.of(), null);
        return result(view, Urgency.THIS_WEEK, List.of("value_outside_range:sodium|stopgap"), GateState.PENDING, null, BD);
    }

    /** A ROUTINE paper with its released explanation: every value inside. */
    public static ResultView routineReleased() {
        DocumentView view = paper("2026-10-12", List.of(haemoglobinInside()), List.of(), List.of(), Map.of(), null);
        return result(view, Urgency.ROUTINE, List.of(), GateState.RELEASED, "Everything printed here is inside its range.", BD);
    }

    /** A DONE paper that was never assessed (no stored level) and whose explanation was never stored. */
    public static ResultView unassessed() {
        DocumentView view = paper("2026-10-12", List.of(haemoglobinInside()), List.of(), List.of(), Map.of(), null);
        return result(view, null, List.of(), GateState.NONE, null, BD);
    }
}
