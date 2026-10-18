package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.web.domain.FamilyOverview;
import com.elioo.baymax.web.domain.PatientSummary;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static com.elioo.baymax.web.adapter.in.ui.Html.esc;
import static com.elioo.baymax.web.adapter.in.ui.PageChrome.BASE;

/**
 * The পরিবার tab (s21 basics, A6): the patient cards; for the patient in view their age and sex, the chronic flags
 * (asked, never inferred), the consent date and the reminder switch; the account (the owner's name, the WhatsApp
 * number — shown here and on no other page — and the language); sign out. Only the owner may change anything: a share
 * member sees the same facts with no save and no reminder switch (spec §3, D30), and the use cases refuse them anyway.
 */
final class FamilyView {

    /** The patient's calendar for the consent date. A10 replaces this with {@code PatientCalendar.ZONE}. */
    static final ZoneId PATIENT_ZONE = ZoneId.of("Asia/Dhaka");

    private final UiCopy copy;
    private final PageChrome chrome;

    FamilyView(UiCopy copy, PageChrome chrome) {
        this.copy = copy;
        this.chrome = chrome;
    }

    /**
     * @param optedOut whether reminders are stopped for the patient in view (ignored when none is)
     * @param saved    the page is the answer to a save: say so once, at the top
     */
    String render(Lang lang, FamilyOverview me, ActivePatient active, boolean optedOut, boolean saved) {
        StringBuilder b = new StringBuilder("<h1>").append(copy.t(lang, "tab.family")).append("</h1>");
        if (saved) {
            b.append("<p class=\"saved\" role=\"status\">").append(copy.t(lang, "profile.saved")).append("</p>");
        }
        b.append("<h2>").append(copy.t(lang, "family.patients.title")).append("</h2>");
        if (me.patients().isEmpty()) {
            b.append("<div class=\"card\">").append(copy.t(lang, "patients.none")).append("</div>");
        } else {
            b.append(new AppShell(copy).choices(lang, Tab.FAMILY, me, active));
        }
        active.patient().ifPresent(p -> patient(b, lang, p, optedOut));
        account(b, lang, me);
        b.append(chrome.logout(lang, null));
        return b.toString();
    }

    private void patient(StringBuilder b, Lang lang, PatientSummary p, boolean optedOut) {
        PatientProfile profile = p.patient();
        String name = profile.name();
        PatientProfile.Sex sex = profile.sex() == null ? PatientProfile.Sex.UNKNOWN : profile.sex();
        b.append("<h2>").append(copy.t(lang, "family.info.title", name)).append("</h2>");
        if (!p.owner()) {
            b.append("<p><span class=\"tag\">").append(copy.t(lang, "patients.shared")).append("</span></p>");
        }
        b.append("<dl class=\"facts\"><dt>").append(copy.t(lang, "family.info.age")).append("</dt><dd>")
                .append(copy.t(lang, "patients.years", profile.age())).append("</dd><dt>").append(copy.t(lang, "family.info.sex"))
                .append("</dt><dd>").append(copy.t(lang, "sex." + sex.name())).append("</dd></dl>");
        flags(b, lang, p);

        if (profile.proxyConsentAt() != null) {
            b.append("<h2>").append(copy.t(lang, "family.consent.title")).append("</h2><p class=\"small\">")
                    .append(copy.t(lang, "family.consent.at", copy.dateText(lang, LocalDate.ofInstant(profile.proxyConsentAt(), PATIENT_ZONE))))
                    .append("</p>");
        }

        b.append("<h2>").append(copy.t(lang, "profile.nudges")).append("</h2><div class=\"card\"><p>")
                .append(copy.t(lang, optedOut ? "profile.nudges.off" : "profile.nudges.on", name)).append("</p>");
        if (!optedOut && p.owner()) {
            b.append("<form method=\"post\" action=\"").append(BASE).append("/patients/").append(profile.id()).append("/nudges/opt-out\">")
                    .append("<button type=\"submit\" class=\"quiet\">").append(copy.t(lang, "profile.nudges.stop", name)).append("</button></form>");
        }
        b.append("</div>");
    }

    /** The chronic flags, as the family ticked them; the submit only for the owner, whose save is the only one accepted. */
    private void flags(StringBuilder b, Lang lang, PatientSummary p) {
        PatientProfile profile = p.patient();
        List<String> flags = profile.chronicFlags() == null ? List.of() : profile.chronicFlags();
        String disabled = p.owner() ? "" : " disabled";
        b.append("<form class=\"card\" method=\"post\" action=\"").append(BASE).append("/patients/").append(profile.id()).append("/profile\">")
                .append("<strong>").append(copy.t(lang, "profile.flags")).append("</strong><p class=\"small\">")
                .append(copy.t(lang, "family.flags.hint")).append("</p>");
        String other = "";
        for (String f : flags) {
            if (!ProfilePages.FLAGS.contains(f)) {
                other = other.isEmpty() ? f : other + ", " + f;
            }
        }
        for (String f : ProfilePages.FLAGS) {
            b.append("<label class=\"check\"><input type=\"checkbox\" name=\"flag\" value=\"").append(f).append("\"")
                    .append(flags.contains(f) ? " checked" : "").append(disabled).append("> ").append(copy.t(lang, "flag." + f)).append("</label>");
        }
        b.append("<label for=\"o\">").append(copy.t(lang, "flag.other")).append("</label><input id=\"o\" type=\"text\" name=\"other\" maxlength=\"40\" value=\"")
                .append(esc(other)).append("\"").append(disabled).append(">")
                .append(p.owner() ? "<button type=\"submit\">" + copy.t(lang, "profile.save") + "</button>" : "").append("</form>");
    }

    private void account(StringBuilder b, Lang lang, FamilyOverview me) {
        String owner = me.family().ownerName();
        b.append("<h2>").append(copy.t(lang, "family.account.title")).append("</h2><dl class=\"facts\">");
        if (owner != null && !owner.isBlank()) {
            b.append("<dt>").append(copy.t(lang, "family.account.name")).append("</dt><dd>").append(esc(owner)).append("</dd>");
        }
        b.append("<dt>").append(copy.t(lang, "family.account.number")).append("</dt><dd>")
                .append(esc(PhoneDisplay.full(me.family().whatsappNumber()))).append("</dd>")
                .append("<dt>").append(copy.t(lang, "family.account.lang")).append("</dt><dd>")
                .append(chrome.langToggle(lang, Tab.FAMILY.path)).append("</dd></dl>");
    }
}
