package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.common.time.PatientCalendar;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.nudge.application.port.out.NudgeDataPort;
import com.elioo.baymax.web.adapter.in.PhoneDisplay;
import com.elioo.baymax.web.adapter.in.ui.Lang;
import com.elioo.baymax.web.adapter.in.ui.ProfilePages;
import com.elioo.baymax.web.adapter.in.ui.UiCopy;
import com.elioo.baymax.web.domain.FamilyOverview;
import com.elioo.baymax.web.domain.PatientSummary;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The পরিবার tab as JSON ({@code GET /app/api/family}), the facts the HTML tab ({@code FamilyView}) shows, in
 * {@code PaperJson}'s conventions: snake_case keys, Dhaka days as ISO dates, copy as {@code *_key}, the name forms in the
 * viewer's language ({@link UiCopy#poss}). The offered flags are {@link ProfilePages#FLAGS}, the one list the HTML form
 * ticks, each as {@code {id, label_key}}. A stored flag is {@code {value, label_key}}: {@code label_key} names its copy
 * when the flag is an offered one, and is null for a flag the family typed in the form's "other" field, which React
 * shows as written. Which is which is decided here, by membership in {@code FLAGS}, never by React.
 *
 * <p>The account's number is here, as {@link PhoneDisplay#full}, and nowhere else in {@code /app/api}: the পরিবার tab
 * is the one page that shows it ({@code FamilyView}). It is never logged.
 */
final class FamilyJson {

    /** A patient's reminders: on, stopped for this patient, or stopped for the whole family (which covers every patient). */
    static final String ON = "on";
    static final String STOPPED_FOR_PATIENT = "stopped_for_patient";
    static final String STOPPED_FOR_FAMILY = "stopped_for_family";

    private FamilyJson() {
    }

    /**
     * The whole tab: the viewer's own account and every patient they may see, in {@code me}'s order.
     *
     * @param reminders      one {@link #reminders} answer per patient of {@code me}, in the same order
     * @param remindersStopped the viewer's own family stop ({@code family_account.nudges_opted_out_at} is set)
     */
    static Map<String, Object> family(FamilyOverview me, List<String> reminders, boolean remindersStopped, Lang lang, UiCopy copy) {
        FamilyAccount account = me.family();
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("owner_name", account.ownerName());
        a.put("number", PhoneDisplay.full(account.whatsappNumber()));
        a.put("terms_date", day(account.termsAcceptedAt()));
        a.put("language", lang.code);
        a.put("reminders_stopped", remindersStopped);
        List<Map<String, Object>> patients = new ArrayList<>();
        for (int i = 0; i < me.patients().size(); i++) {
            patients.add(patient(me.patients().get(i), reminders.get(i), lang, copy));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("account", a);
        body.put("patients", patients);
        body.put("flags_offered", ProfilePages.FLAGS.stream().map(FamilyJson::offered).toList());
        return body;
    }

    /** One patient as the tab shows them; {@code owner} false for a patient shared with the viewer, whose writes are refused. */
    static Map<String, Object> patient(PatientSummary p, String reminders, Lang lang, UiCopy copy) {
        PatientProfile profile = p.patient();
        PatientProfile.Sex sex = profile.sex() == null ? PatientProfile.Sex.UNKNOWN : profile.sex();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", profile.id().toString());
        m.put("name", profile.name());
        m.put("name_possessive", copy.poss(lang, profile.name()));
        m.put("age", profile.age());
        m.put("sex_key", "sex." + sex.name());
        m.put("owner", p.owner());
        m.put("chronic_flags", (profile.chronicFlags() == null ? List.<String>of() : profile.chronicFlags()).stream()
                .map(FamilyJson::stored).toList());
        m.put("consent_date", day(profile.proxyConsentAt()));
        m.put("reminders", reminders);
        return m;
    }

    /** An offered flag: its id (what the form posts) and its copy. */
    private static Map<String, Object> offered(String id) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("id", id);
        f.put("label_key", labelKey(id));
        return f;
    }

    /** A stored flag as written, with its copy when it is an offered one; null (sent, not left out) when typed. */
    private static Map<String, Object> stored(String value) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("value", value);
        f.put("label_key", ProfilePages.FLAGS.contains(value) ? labelKey(value) : null);
        return f;
    }

    private static String labelKey(String id) {
        return "flag." + id;
    }

    /**
     * Why a patient's reminders are off, said for what it is: the family's stop covers every patient (the nudge engine
     * reads either column, {@link NudgeDataPort.PatientRef#optedOut}), so it wins over the patient's own. For a shared
     * patient the family is the patient's own family, the owner's, whose stop is the one that silences them.
     */
    static String reminders(NudgeDataPort.PatientRef ref) {
        if (ref.familyOptedOutAt() != null) {
            return STOPPED_FOR_FAMILY;
        }
        return ref.patientOptedOutAt() != null ? STOPPED_FOR_PATIENT : ON;
    }

    /** The Dhaka day of an instant, ISO, as every date of {@code /app/api}; null when unrecorded. */
    private static String day(Instant at) {
        return at == null ? null : PatientCalendar.dayOf(at).toString();
    }
}
