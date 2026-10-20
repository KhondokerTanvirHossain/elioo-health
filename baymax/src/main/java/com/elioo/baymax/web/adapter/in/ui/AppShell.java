package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.web.adapter.in.ActivePatient;
import com.elioo.baymax.web.domain.FamilyOverview;
import com.elioo.baymax.web.domain.PatientSummary;

import static com.elioo.baymax.web.adapter.in.ui.Html.esc;

/**
 * The signed-in frame (spec §3; kit §2, §3): a 56 px header with the text wordmark (never a link inside the app) and
 * whose papers are in view, the page, the disclaimer, the 64 px tab bar of three always-labelled links. No script.
 * A patient is never guessed (PatientSwitcher README): two or more and none chosen, the page body is {@link #chooser}.
 * With two or more and one in view, the header's name opens {@link #patientSheet} — a plain URL
 * ({@code ?patient=<id>&sheet=patient}, the patient in it so the link does not depend on the remembered choice),
 * drawn by the server over the tab it was opened from (C1).
 */
final class AppShell {

    /** U+09CD BENGALI SIGN VIRAMA (hasanta): joins the consonant before it to the one after. */
    private static final int HASANTA = 0x09CD;

    private final UiCopy copy;

    AppShell(UiCopy copy) {
        this.copy = copy;
    }

    String page(Lang lang, Tab tab, FamilyOverview me, ActivePatient active, String body, Html.Head head, String bodyClass) {
        return page(lang, tab, me, active, body, head, bodyClass, "");
    }

    /** As above with {@code sheet} drawn over the page, which is then {@code inert}; empty, there is no sheet. */
    String page(Lang lang, Tab tab, FamilyOverview me, ActivePatient active, String body, Html.Head head, String bodyClass, String sheet) {
        String classes = sheet.isEmpty() ? bodyClass : (bodyClass == null || bodyClass.isEmpty() ? "" : bodyClass + " ") + "has-sheet";
        return Html.appPage(lang, head, header(lang, tab, me, active), body, copy.t(lang, "disclaimer"), tabs(lang, tab), sheet, classes);
    }

    /**
     * The patient switcher (spec §3 sheets; kit §17; PatientSwitcher README) over {@code tab}: the same choices as the
     * chooser, the one in view marked. Its close is a link back to the tab with the patient in view asked for by id, so it
     * needs no cookie to land on the same patient; it takes the focus ({@code autofocus}), so no script is needed to land inside the dialog.
     */
    String patientSheet(Lang lang, Tab tab, FamilyOverview me, ActivePatient active) {
        return dialog(lang, copy.t(lang, "switch.title"), "<p class=\"small\">" + copy.t(lang, "switch.hint") + "</p>"
                + choices(lang, tab, me, active), inView(tab, active));
    }

    /**
     * A sheet's markup (kit §17), as the add-report and value sheets draw it. {@code title} is already escaped (it comes
     * from {@link UiCopy}); {@code closeHref} is the page beneath, without the sheet.
     */
    String dialog(Lang lang, String title, String content, String closeHref) {
        return "<section class=\"sheet\" role=\"dialog\" aria-modal=\"true\" aria-labelledby=\"sheet-title\">"
                + "<div class=\"sheet-h\"><h2 id=\"sheet-title\">" + title + "</h2>"
                + "<a class=\"icon-btn\" href=\"" + closeHref + "\" autofocus aria-label=\"" + copy.t(lang, "sheet.close") + "\">"
                + Icons.svg("x", 24) + "</a></div>" + content + "</section>";
    }

    /**
     * A signed-in page that is not a tab itself (a document, its deletion, a profile): the wordmark-only header, the
     * page, and the tab bar with {@code under} current, the tab the page belongs to.
     */
    String inside(Lang lang, Tab under, String body) {
        return inside(lang, under, Html.Head.of(copy.t(lang, under.labelKey) + " — " + copy.t(lang, "app.title")), body);
    }

    /** As {@link #inside(Lang, Tab, String)} with a head of the page's own (the reading page: its title, its refresh, its script). */
    String inside(Lang lang, Tab under, Html.Head head, String body) {
        return Html.appPage(lang, head,
                "<header class=\"appbar\">" + Html.brand(copy.t(lang, "app.title")) + "</header>", body,
                copy.t(lang, "disclaimer"), tabs(lang, under), "", "");
    }

    /**
     * A sheet's no-JS URL (spec §3): the wordmark-only header and the tab bar of {@code under}, the tab the sheet was
     * opened from, inert behind the sheet; nothing of anyone's papers is read for the page under it.
     */
    String sheet(Lang lang, Tab under, String titleKey, String sheet) {
        return sheet(lang, under, head(lang, titleKey), sheet);
    }

    /** The head a sheet's page carries by default: its title only. */
    Html.Head head(Lang lang, String titleKey) {
        return Html.Head.of(copy.t(lang, titleKey) + " — " + copy.t(lang, "app.title"));
    }

    /** As above with a head of the sheet's own (the add sheet: its script, D4, when it draws the form). */
    String sheet(Lang lang, Tab under, Html.Head head, String sheet) {
        return Html.appPage(lang, head,
                "<header class=\"appbar\">" + Html.brand(copy.t(lang, "app.title")) + "</header>", "",
                copy.t(lang, "disclaimer"), tabs(lang, under), sheet, "has-sheet");
    }

    /** The back link at the top of a page inside a tab: {@code ‹ কাগজপত্র}, {@code ‹ পরিবার}. */
    String back(Lang lang, String href, String key) {
        return "<a class=\"back\" href=\"" + href + "\">" + copy.t(lang, key) + "</a>";
    }

    /** {@code tab} with the patient in view asked for by id: checked again by the tab like any {@code ?patient=}. */
    private static String inView(Tab tab, ActivePatient active) {
        return tab.path + "?patient=" + active.patient().orElseThrow().patient().id();
    }

    String header(Lang lang, Tab tab, FamilyOverview me, ActivePatient active) {
        StringBuilder b = new StringBuilder("<header class=\"appbar\">").append(Html.brand(copy.t(lang, "app.title")));
        boolean many = me.patients().size() > 1;
        if (active.patient().isPresent()) {
            String name = active.patient().get().patient().name();
            String inner = "<span class=\"pav\" aria-hidden=\"true\">" + esc(initial(name)) + "</span><span>" + esc(name) + "</span>";
            // with two or more, the name opens the switcher sheet over this tab (C1); the patient is in the URL, so a
            // browser that keeps no cookie still opens the sheet, and never lands on the chooser instead
            b.append(many
                    ? "<a class=\"switcher\" href=\"" + inView(tab, active) + "&amp;sheet=patient\" aria-haspopup=\"dialog\" aria-label=\"" + copy.t(lang, "switch.label", name) + "\">"
                            + inner + Icons.svg("chevron-down", 20) + "</a>"
                    : "<span class=\"switcher\">" + inner + "</span>");
        }
        return b.append("</header>").toString();
    }

    String tabs(Lang lang, Tab current) {
        StringBuilder b = new StringBuilder("<nav class=\"tabs\" aria-label=\"").append(copy.t(lang, "tabs.label")).append("\">");
        for (Tab t : Tab.values()) {
            b.append("<a href=\"").append(t.path).append("\"").append(t == current ? " aria-current=\"page\"" : "").append(">")
                    .append("<span class=\"pill\">").append(Icons.svg(t.icon, 24)).append("</span><span>")
                    .append(copy.t(lang, t.labelKey)).append("</span></a>");
        }
        return b.append("</nav>").toString();
    }

    /** Two or more patients and none chosen: the page body is this, and nothing about anyone's papers. */
    String chooser(Lang lang, Tab tab, FamilyOverview me, ActivePatient active) {
        return "<h1>" + copy.t(lang, "switch.title") + "</h1><p class=\"small\">" + copy.t(lang, "switch.hint") + "</p>"
                + choices(lang, tab, me, active);
    }

    /** No patient yet: the tab names itself (C4 and A6 draw the add-parent screens, spec §4.6). */
    String noPatients(Lang lang, Tab tab) {
        return "<h1>" + copy.t(lang, tab.labelKey) + "</h1><div class=\"card\">" + copy.t(lang, "patients.none") + "</div>";
    }

    String choices(Lang lang, Tab tab, FamilyOverview me, ActivePatient active) {
        StringBuilder b = new StringBuilder("<ul class=\"choices\">");
        for (PatientSummary p : me.patients()) {
            boolean chosen = active.is(p);
            b.append("<li><a class=\"pchoice\" href=\"").append(tab.path).append("?patient=").append(p.patient().id()).append("\"")
                    .append(chosen ? " aria-current=\"true\">" + Icons.svg("check", 18) : ">")
                    .append("<span class=\"pav\" aria-hidden=\"true\">").append(esc(initial(p.patient().name()))).append("</span>")
                    .append("<span><strong>").append(esc(p.patient().name())).append("</strong><span class=\"meta\">")
                    .append(copy.t(lang, "family.patient.meta", p.patient().age(), p.documents())).append("</span></span></a></li>");
        }
        return b.append("</ul>").toString();
    }

    /** The first letter as a reader sees it: with its vowel signs, and the whole conjunct when a hasanta joins one. */
    static String initial(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String n = name.strip();
        int end = n.offsetByCodePoints(0, 1);
        while (end < n.length()) {
            int cp = n.codePointAt(end);
            int type = Character.getType(cp);
            if (type != Character.NON_SPACING_MARK && type != Character.COMBINING_SPACING_MARK) {
                break;
            }
            end = n.offsetByCodePoints(end, 1);
            if (cp == HASANTA && end < n.length()) {   // take the consonant it joins
                end = n.offsetByCodePoints(end, 1);
            }
        }
        return n.substring(0, end);
    }
}
