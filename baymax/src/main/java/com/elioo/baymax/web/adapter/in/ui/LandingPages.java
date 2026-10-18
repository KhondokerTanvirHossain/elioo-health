package com.elioo.baymax.web.adapter.in.ui;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.HandlerFilterFunction;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import static com.elioo.baymax.web.adapter.in.ui.PageChrome.BASE;
import static com.elioo.baymax.web.adapter.in.ui.PageChrome.CONTACT_EMAIL;
import static com.elioo.baymax.web.adapter.in.ui.PageChrome.ELIOO;

/** The storytelling landing page at {@code /}, the language switch, and the 301 from every old {@code /baymax} URL. */
@Component
@RequiredArgsConstructor
public class LandingPages implements WebPages {

    private final UiCopy copy;
    private final PageChrome chrome;

    @Override
    public int order() {
        return 10;   // open
    }

    @Override
    public RouterFunction<ServerResponse> routes(HandlerFilterFunction<ServerResponse, ServerResponse> requireSession) {
        return RouterFunctions.route()
                .GET("/", this::landing)
                .GET("/lang/{code}", chrome::switchLang)
                .GET(BaymaxWebUiRouter.OLD_BASE, BaymaxWebUiRouter::movedToApp)
                .GET(BaymaxWebUiRouter.OLD_BASE + "/{*rest}", BaymaxWebUiRouter::movedToApp)
                .build();
    }

    /**
     * The storytelling landing page (BMX-6d, PO-approved design): a chat thread in which Medioo speaks first, the
     * polythene bag, a year of unprompted messages, one photo, check-it-yourself, promises, what it does not do,
     * Mio. No script; the one motion is CSS. Every CTA goes to the app login until WhatsApp (BMX-10) exists —
     * never a dead button. The voice chip stays while BMX-7 is on the roadmap and comes out if it slips.
     */
    public Mono<ServerResponse> landing(ServerRequest request) {
        Lang lang = Lang.of(request);
        String t = copy.t(lang, "app.title");
        String cta = "<a class=\"cta\" href=\"" + BASE + "\">" + copy.t(lang, "landing.cta") + "</a>";
        StringBuilder b = new StringBuilder("<div class=\"land\">");
        b.append("<header class=\"top wrap\"><div><a class=\"wm\" href=\"/\">").append(Html.brand(t)).append("</a>")
                .append("<span class=\"tagline\">").append(copy.t(lang, "app.tagline")).append("</span></div>")
                .append("<div class=\"actions\">").append(chrome.langToggle(lang, "/"))
                .append("<a class=\"login\" href=\"").append(BASE).append("\">").append(copy.t(lang, "landing.login")).append("</a></div></header>");

        // hero: the message no one asked for
        b.append("<section class=\"hero\"><div class=\"wrap\"><div><h1>").append(copy.t(lang, "landing.h1")).append("</h1>")
                .append("<p class=\"sub\">").append(copy.t(lang, "landing.sub")).append("</p>").append(cta)
                .append("<p class=\"fine\">").append(copy.t(lang, "landing.fine")).append("</p></div>")
                .append("<div><div class=\"thread\">")
                .append(day(lang, "landing.h.d1"))
                .append(me(paper("paper-lab", copy.t(lang, "landing.paper.lab")) + copy.t(lang, "landing.h.me")))
                .append(mio(copy.t(lang, "landing.h.reply")))
                .append("<span class=\"gap\">").append(copy.t(lang, "landing.h.gap")).append("</span>")
                .append(day(lang, "landing.h.d2"))
                .append("<div class=\"late\"><span class=\"dots\" aria-hidden=\"true\"><i></i><i></i><i></i></span>")
                .append("<div class=\"msg arrive\"><span class=\"av\"></span><div class=\"b\">")
                .append(copy.t(lang, "landing.h.msg", "\u2060TREND\u2060").replace("\u2060TREND\u2060", "<span class=\"nw\">" + copy.t(lang, "landing.h.trend") + "</span>"))
                .append("<br>").append(tag(lang, "week")).append("</div></div></div></div>")
                .append("<p class=\"cap\">").append(copy.t(lang, "landing.h.cap")).append("</p>");
        String note = copy.t(lang, "landing.h.note");
        if (!note.isBlank()) {
            b.append("<p class=\"note\">").append(note).append("</p>");
        }
        b.append("</div></div></section>");

        // the polythene bag
        b.append("<section class=\"sec alt\"><div class=\"wrap\"><h2>").append(copy.t(lang, "landing.bag.h")).append("</h2>")
                .append("<p class=\"body\">").append(copy.t(lang, "landing.bag.p")).append("</p>")
                .append("<p class=\"quote\">").append(copy.t(lang, "landing.bag.q")).append("</p></div></section>");

        // a year of unprompted messages
        b.append("<section class=\"sec\"><div class=\"wrap\"><h2>").append(copy.t(lang, "landing.yr.h")).append("</h2>")
                .append("<p class=\"lede\">").append(copy.t(lang, "landing.yr.lede")).append("</p><div class=\"thread year\">");
        for (int i = 1; i <= 4; i++) {
            b.append(day(lang, "landing.yr." + i + ".day"))
                    .append("<p class=\"why\">").append(copy.t(lang, "landing.yr." + i + ".why")).append("</p>")
                    .append(mio(copy.t(lang, "landing.yr." + i + ".msg")));
        }
        b.append("</div>").append(cta).append("</div></section>");

        // it starts with one photo (voice chip: BMX-7, flagged — comes out if BMX-7 slips)
        b.append("<section class=\"sec alt\"><div class=\"wrap\"><h2>").append(copy.t(lang, "landing.f.h")).append("</h2>")
                .append("<p class=\"body\">").append(copy.t(lang, "landing.f.p")).append("</p><div class=\"thread\">")
                .append(me(paper("paper-rx", copy.t(lang, "landing.paper.rx")) + copy.t(lang, "landing.f.me")))
                .append(mio(copy.t(lang, "landing.f.msg") + "<br><span class=\"voice\">" + copy.t(lang, "landing.f.voice") + "</span><br>" + tag(lang, "routine")))
                .append("</div><p class=\"after\">").append(copy.t(lang, "landing.f.voice_p")).append("</p>")
                .append("<p class=\"after\">").append(copy.t(lang, "landing.f.levels")).append("</p>")
                .append("<div class=\"levels\">").append(tag(lang, "routine")).append(tag(lang, "week")).append(tag(lang, "now")).append("</div></div></section>");

        // check it yourself
        b.append("<section class=\"sec\"><div class=\"wrap\"><h2>").append(copy.t(lang, "landing.c.h")).append("</h2>")
                .append("<p class=\"body\">").append(copy.t(lang, "landing.c.p")).append("</p><div class=\"cmp\">")
                .append("<figure><figcaption>").append(copy.t(lang, "landing.c.l1")).append("</figcaption><div class=\"scan\" lang=\"en\"><div>S. Creatinine &nbsp;&nbsp; <b>1.5</b> mg/dL<br>Ref. range &nbsp;&nbsp;&nbsp;&nbsp; 0.6 - 1.2</div></div></figure>")
                .append("<div><p class=\"lbl\">").append(copy.t(lang, "landing.c.l2")).append("</p><div class=\"read\"><strong>")
                .append(copy.t(lang, "landing.c.val")).append("</strong><span>").append(copy.t(lang, "landing.c.ref")).append("</span></div></div></div>")
                .append("<ul class=\"promises\">");
        for (int i = 1; i <= 3; i++) {
            b.append("<li>").append(copy.t(lang, "landing.promise." + i)).append("</li>");
        }
        b.append("</ul><div class=\"not\"><h3>").append(copy.t(lang, "landing.not.h")).append("</h3><p>").append(copy.t(lang, "landing.not.p")).append("</p></div></div></section>");

        // close: Mio
        b.append("<section class=\"sec alt\"><div class=\"wrap\">")
                .append(Html.present(Html.MASCOT)
                        ? "<img class=\"mio\" src=\"" + Html.MASCOT + "?v=" + Html.version("/baymax/ui/mio.webp") + "\" width=\"160\" height=\"197\" alt=\"" + copy.t(lang, "landing.mio.alt") + "\">"
                        : "<span class=\"mio-slot\"></span>")
                .append("<h2>").append(copy.t(lang, "landing.z.h")).append("</h2>")
                .append("<p class=\"body\">").append(copy.t(lang, "landing.z.p")).append("</p>").append(cta)
                .append("<p class=\"collab\">").append(copy.t(lang, "landing.collab")).append(" <a href=\"mailto:").append(CONTACT_EMAIL)
                .append("\">").append(CONTACT_EMAIL).append("</a></p>")
                .append("</div></section></div>");

        String foot = "<div class=\"wrap\"><p>" + copy.t(lang, "landing.foot") + "</p><p class=\"about\"><a href=\"" + ELIOO + "\" rel=\"noopener\">"
                + copy.t(lang, "landing.about") + "</a> · &copy; 2026 Medioo</p></div>";
        return Html.html(HttpStatus.OK, Html.page(lang, t + " — " + copy.t(lang, "app.tagline"), b.toString(), 0, true, foot, true));
    }

    private String day(Lang lang, String key) {
        return "<span class=\"day\">" + copy.t(lang, key) + "</span>";
    }

    private static String me(String inner) {
        return "<div class=\"msg me\"><div class=\"b\">" + inner + "</div></div>";
    }

    private static String mio(String inner) {
        return "<div class=\"msg\"><span class=\"av\"></span><div class=\"b\">" + inner + "</div></div>";
    }

    private static String paper(String file, String alt) {
        return "<img class=\"paper\" src=\"/assets/" + file + ".svg\" width=\"132\" height=\"96\" alt=\"" + alt + "\">";
    }

    private String tag(Lang lang, String level) {
        return "<span class=\"tag " + level + "\">" + copy.t(lang, "landing.tag." + level) + "</span>";
    }
}
