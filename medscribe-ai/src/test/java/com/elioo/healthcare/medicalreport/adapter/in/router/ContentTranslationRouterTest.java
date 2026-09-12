package com.elioo.healthcare.medicalreport.adapter.in.router;

import com.elioo.healthcare.medicalreport.adapter.in.handler.ContentTranslationHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContentTranslationRouterTest {

    private final ContentTranslationHandler handler = mock(ContentTranslationHandler.class);
    private final WebTestClient client = WebTestClient.bindToRouterFunction(
            new ContentTranslationRouter().translationRoutes(handler)).build();

    ContentTranslationRouterTest() {
        when(handler.translateContent(any())).thenReturn(ServerResponse.ok().bodyValue("translate"));
        when(handler.checkTranslationCache(any())).thenReturn(ServerResponse.ok().bodyValue("check"));
        when(handler.getSupportedLanguages(any())).thenReturn(ServerResponse.ok().bodyValue("languages"));
    }

    @Test
    void checkRouteIsNotShadowedByTheResultTypeRoute() {
        client.get().uri("/api/v1/medical-report/RPT-1/translate/check?resultType=CLINICAL_INSIGHTS&lang=bn")
                .exchange().expectStatus().isOk()
                .expectBody(String.class).isEqualTo("check");
    }

    @Test
    void resultTypeRouteStillWorks() {
        client.get().uri("/api/v1/medical-report/RPT-1/translate/CLINICAL_INSIGHTS?lang=bn")
                .exchange().expectStatus().isOk()
                .expectBody(String.class).isEqualTo("translate");
    }

    @Test
    void languagesRouteStillWorks() {
        client.get().uri("/api/v1/medical-report/languages")
                .exchange().expectStatus().isOk()
                .expectBody(String.class).isEqualTo("languages");
    }
}
