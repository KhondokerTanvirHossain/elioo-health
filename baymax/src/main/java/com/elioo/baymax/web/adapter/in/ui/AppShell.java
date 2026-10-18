package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.web.domain.FamilyOverview;
import com.elioo.baymax.web.domain.PatientSummary;

import static com.elioo.baymax.web.adapter.in.ui.Html.esc;

/**
 * The signed-in frame (spec §3; kit §2, §3): a 56 px header with the text wordmark (never a link inside the app) and
 * whose papers are in view, the page, the disclaimer, the 64 px tab bar of three always-labelled links. No script.
 * A patient is never guessed (PatientSwitcher README): two or more and none chosen, the page body is {@link #chooser}.
 * The remembered choice and the switcher sheet are C1.
 */
final class AppShell {

    /** U+09CD BENGALI SIGN VIRAMA (hasanta): joins the consonant before it to the one after. */
    private static final int HASANTA = 0x09CD;

    private final UiCopy copy;

    AppShell(UiCopy copy) {
        this.copy = copy;
    }

    String page(Lang lang, Tab tab, FamilyOverview me, ActivePatient active, String body, Html.Head head, String bodyClass) {
        return Html.appPage(lang, head, header(lang, tab, me, active), body, copy.t(lang, "disclaimer"), tabs(lang, tab), "", bodyClass);
    }

    /**
     * A signed-in page that is not a tab itself (a document, its deletion, a profile): the wordmark-only header, the
     * page, and the tab bar with {@code under} current, the tab the page belongs to.
     */
    String inside(Lang lang, Tab under, String body) {
        return Html.appPage(lang, Html.Head.of(copy.t(lang, under.labelKey) + " — " + copy.t(lang, "app.title")),
                "<header class=\"appbar\">" + Html.brand(copy.t(lang, "app.title")) + "</header>", body,
                copy.t(lang, "disclaimer"), tabs(lang, under), "", "");
    }

    /** The back link at the top of a page inside a tab: {@code ‹ কাগজপত্র}, {@code ‹ পরিবার}. */
    String back(Lang lang, String href, String key) {
        return "<a class=\"back\" href=\"" + href + "\">" + copy.t(lang, key) + "</a>";
    }

    String header(Lang lang, Tab tab, FamilyOverview me, ActivePatient active) {
        StringBuilder b = new StringBuilder("<header class=\"appbar\">").append(Html.brand(copy.t(lang, "app.title")));
        boolean many = me.patients().size() > 1;
        if (active.patient().isPresent()) {
            String name = active.patient().get().patient().name();
            String inner = "<span class=\"pav\" aria-hidden=\"true\">" + esc(initial(name)) + "</span><span>" + esc(name) + "</span>";
            // with two or more, the name is the way back to the chooser (C1 turns it into the switcher sheet)
            b.append(many
                    ? "<a class=\"switcher\" href=\"" + tab.path + "\" aria-label=\"" + copy.t(lang, "switch.label", name) + "\">"
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

    String noPatients(Lang lang) {
        return "<h1>" + copy.t(lang, "tab.family") + "</h1><div class=\"card\">" + copy.t(lang, "patients.none") + "</div>";
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
