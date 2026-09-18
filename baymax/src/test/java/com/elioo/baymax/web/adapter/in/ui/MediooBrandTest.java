package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;
import com.elioo.baymax.web.application.port.in.WebAuthUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
        WebAuthUseCase auth = mock(WebAuthUseCase.class);
        when(auth.authenticate(anyString())).thenReturn(Mono.empty());
        SessionAuthFilter sessions = new SessionAuthFilter(auth, new BaymaxProperties());
        WebTestClient client = WebTestClient.bindToRouterFunction(new BaymaxWebUiRouter().baymaxWebUiRoutes(
                new WebUiHandler(auth, mock(TimelineUseCase.class), sessions, copy), sessions)).build();
        for (String path : new String[]{"/", "/app/"}) {
            for (String lang : new String[]{"bn", "en"}) {
                String html = client.get().uri(path).cookie("baymax_lang", lang).exchange().expectStatus().isOk()
                        .expectBody(String.class).returnResult().getResponseBody();
                // cookie names, asset paths and CSS class names are code, not copy: strip them before looking
                String visible = html.replaceAll("<[^>]+>", " ");
                assertThat(visible).as(path + " " + lang).doesNotContainIgnoringCase("baymax").doesNotContainPattern("(?i)\\bmedio\\b").contains("Medioo");
            }
        }
    }
}
