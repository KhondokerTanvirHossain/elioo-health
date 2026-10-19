package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R2: the Head form writes the bytes the seven-argument page() wrote before R2, so no page changes. The old form now
 * delegates to the Head form, so comparing the two would be green by construction; the oracle is the pre-R2 body of
 * page(), copied verbatim from cf61009 below, with the one change L1 (A3) made on purpose: the viewport line became
 * the zoomable viewport, color-scheme and the two theme-color metas.
 */
class HtmlHeadTest {

    @ParameterizedTest
    @CsvSource({"0,false,false", "5,false,false", "0,true,true", "5,true,false", "5,false,true", "5,true,true"})
    void theHeadFormIsByteForByteTheOldForm(int refresh, boolean wide, boolean index) {
        String old = beforeR2(Lang.BN, "t &amp; u", "<p>x</p>", refresh, wide, "d", index);
        Html.Head head = Html.Head.of("t &amp; u").refresh(refresh);
        head = index ? head.indexed() : head;
        assertThat(Html.page(Lang.BN, head, "<p>x</p>", wide, "d")).isEqualTo(old);
        assertThat(Html.page(Lang.BN, "t &amp; u", "<p>x</p>", refresh, wide, "d", index)).isEqualTo(old);
    }

    /**
     * A9 renders the two fields R2 refused until a page used them: the refresh only without script, inside
     * {@code <noscript>} and nowhere else (an unconditional refresh would reload over the poll), and a script file from
     * {@code /assets/}, versioned and deferred. A script that is not a served asset is refused rather than linked to a 404.
     */
    @Test
    void theRefreshWithoutScriptIsInsideNoscriptAndAScriptIsAServedVersionedFile() {
        String page = Html.page(Lang.BN, Html.Head.of("t").refreshWithoutScript(5).script("progress.js"), "<p>x</p>", false, "d");
        assertThat(page).contains("<noscript><meta http-equiv=\"refresh\" content=\"5\"></noscript>")
                .contains("<script src=\"/assets/progress.js?v=" + AssetHandler.version("progress.js") + "\" defer></script></head>");
        assertThat(page.split("http-equiv=\"refresh\"", -1).length - 1).as("refresh metas").isEqualTo(1);
        assertThat(Html.page(Lang.BN, Html.Head.of("t").refresh(5), "<p>x</p>", false, "d"))
                .contains("content=\"5\"").doesNotContain("<noscript>").doesNotContain("<script");
        assertThatThrownBy(() -> Html.page(Lang.BN, new Html.Head("t", 0, false, List.of("nope.js"), false), "<p>x</p>", false, "d"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("nope.js");
    }

    /** Html.page(Lang, String, String, int, boolean, String, boolean) at cf61009, verbatim but for L1's head metas. */
    private static String beforeR2(Lang lang, String title, String body, int refreshSeconds, boolean wide, String disclaimer, boolean index) {
        return "<!doctype html><html lang=\"" + lang.code + "\"><head><meta charset=\"utf-8\">"
                + (refreshSeconds > 0 ? "<meta http-equiv=\"refresh\" content=\"" + refreshSeconds + "\">" : "")
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
                + "<meta name=\"color-scheme\" content=\"light dark\">"
                + "<meta name=\"theme-color\" media=\"(prefers-color-scheme: light)\" content=\"" + Tokens.LIGHT_BG + "\">"
                + "<meta name=\"theme-color\" media=\"(prefers-color-scheme: dark)\" content=\"" + Tokens.DARK_BG + "\">"
                + (index ? "" : "<meta name=\"robots\" content=\"noindex\">")
                + "<title>" + title + "</title><link rel=\"stylesheet\" href=\"" + Html.CSS_HREF + "\"><link rel=\"icon\" type=\"image/svg+xml\" href=\"" + Html.ICON_HREF + "\"></head><body><main class=\""
                + (wide ? "page" : "app") + "\">" + body
                + "<footer class=\"foot\">" + disclaimer + "</footer></main></body></html>";
    }
}
