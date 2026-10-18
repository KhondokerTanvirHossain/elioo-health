package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R2: the Head form writes the bytes the seven-argument page() wrote before R2, so no page changes. The old form now
 * delegates to the Head form, so comparing the two would be green by construction; the oracle is the pre-R2 body of
 * page(), copied verbatim from cf61009 below.
 */
class HtmlHeadTest {

    @ParameterizedTest
    @CsvSource({"0,false,false", "5,false,false", "0,true,true", "5,true,false"})
    void theHeadFormIsByteForByteTheOldForm(int refresh, boolean wide, boolean index) {
        String old = beforeR2(Lang.BN, "t &amp; u", "<p>x</p>", refresh, wide, "d", index);
        Html.Head head = Html.Head.of("t &amp; u").refresh(refresh);
        head = index ? head.indexed() : head;
        assertThat(Html.page(Lang.BN, head, "<p>x</p>", wide, "d")).isEqualTo(old);
        assertThat(Html.page(Lang.BN, "t &amp; u", "<p>x</p>", refresh, wide, "d", index)).isEqualTo(old);
    }

    /** Html.page(Lang, String, String, int, boolean, String, boolean) at cf61009, verbatim. */
    private static String beforeR2(Lang lang, String title, String body, int refreshSeconds, boolean wide, String disclaimer, boolean index) {
        return "<!doctype html><html lang=\"" + lang.code + "\"><head><meta charset=\"utf-8\">"
                + (refreshSeconds > 0 ? "<meta http-equiv=\"refresh\" content=\"" + refreshSeconds + "\">" : "")
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + (index ? "" : "<meta name=\"robots\" content=\"noindex\">")
                + "<title>" + title + "</title><link rel=\"stylesheet\" href=\"" + Html.CSS_HREF + "\"><link rel=\"icon\" type=\"image/svg+xml\" href=\"" + Html.ICON_HREF + "\"></head><body><main class=\""
                + (wide ? "page" : "app") + "\">" + body
                + "<footer class=\"foot\">" + disclaimer + "</footer></main></body></html>";
    }
}
