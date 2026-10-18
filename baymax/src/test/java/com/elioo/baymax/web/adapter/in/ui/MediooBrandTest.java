package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/** DR-15 / BMX-6c: the public name is Medioo. "Baymax" is a codename and never reaches a family's screen. */
class MediooBrandTest {

    private final UiCopy copy = new UiCopy();

    @Test
    void noBundleStringCarriesTheCodename() {
        for (Lang lang : Lang.values()) {
            for (String key : copy.keys(lang)) {
                assertThat(copy.t(lang, key)).as(lang + " " + key).doesNotContainIgnoringCase("baymax").doesNotContainPattern("(?i)\\bmedio\\b");
            }
            assertThat(copy.t(lang, "app.title")).isEqualTo("Medioo");
        }
    }

    @Test
    void noRenderedPageCarriesTheCodename() {
        WebTestClient client = new WebUiTestKit().client;
        for (String path : new String[]{"/", "/app/"}) {
            for (String lang : new String[]{"bn", "en"}) {
                String html = client.get().uri(path).cookie("baymax_lang", lang).exchange().expectStatus().isOk()
                        .expectBody(String.class).returnResult().getResponseBody();
                // cookie names, asset paths and CSS class names are code, not copy: strip them before looking
                String visible = html.replaceAll("<[^>]+>", " ");
                assertThat(visible).as(path + " " + lang).doesNotContainIgnoringCase("baymax").doesNotContainPattern("(?i)\\bmedio\\b");
                // the name is on every page: as text, or as the wordmark image's alt in the header
                assertThat(html).as(path + " " + lang).containsAnyOf("Medioo", "alt=\"Medioo\"");
            }
        }
    }
}
