package com.elioo.baymax.aicall.adapter.in.router;

import com.elioo.baymax.aicall.adapter.in.handler.AdminMetricsHandler;
import com.elioo.baymax.common.error.ErrorResponseFilter;
import com.elioo.baymax.storage.adapter.in.handler.StorageSelfTestHandler;
import com.elioo.baymax.aicall.application.port.in.WeeklyMetricsUseCase;
import com.elioo.baymax.config.BaymaxProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BaymaxAdminRouterTest {

    private static final String PATH = "/api/v1/baymax/admin/metrics/weekly";

    private final WeeklyMetricsUseCase metrics = mock(WeeklyMetricsUseCase.class);
    private final com.elioo.baymax.extraction.application.port.in.RecropUseCase recrop =
            mock(com.elioo.baymax.extraction.application.port.in.RecropUseCase.class);

    private WebTestClient client(String configuredToken) {
        BaymaxProperties props = new BaymaxProperties();
        props.getAdmin().setToken(configuredToken);
        when(metrics.weeklyCsv(any(), any())).thenReturn(Mono.just("# documents\n"));
        return WebTestClient.bindToRouterFunction(new BaymaxAdminRouter()
                .baymaxAdminRoutes(new AdminMetricsHandler(metrics), mock(StorageSelfTestHandler.class),
                        new com.elioo.baymax.extraction.adapter.in.handler.RecropHandler(recrop),
                        new AdminAuthFilter(props), new ErrorResponseFilter())).build();
    }

    @Test
    void unconfiguredTokenFailsClosed() {
        client(null).get().uri(PATH).header(AdminAuthFilter.HEADER, "anything")
                .exchange().expectStatus().isEqualTo(503);
        verify(metrics, never()).weeklyCsv(any(), any());
    }

    @Test
    void missingOrWrongTokenIsUnauthorized() {
        WebTestClient client = client("s3cret");
        client.get().uri(PATH).exchange().expectStatus().isUnauthorized();
        client.get().uri(PATH).header(AdminAuthFilter.HEADER, "wrong").exchange().expectStatus().isUnauthorized();
        verify(metrics, never()).weeklyCsv(any(), any());
    }

    @Test
    void rightTokenReturnsCsvForTheRequestedWindow() {
        client("s3cret").get().uri(PATH + "?from=2026-09-01&to=2026-09-08")
                .header(AdminAuthFilter.HEADER, "s3cret")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith("text/csv")
                .expectHeader().valueMatches("Content-Disposition", ".*baymax-metrics-2026-09-01_2026-09-08\\.csv.*")
                .expectBody(String.class).isEqualTo("# documents\n");

        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        verify(metrics).weeklyCsv(from.capture(), to.capture());
        assertThat(from.getValue()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(to.getValue()).isEqualTo(Instant.parse("2026-09-08T00:00:00Z"));
    }

    @Test
    void defaultWindowIsSevenDays() {
        client("s3cret").get().uri(PATH).header(AdminAuthFilter.HEADER, "s3cret")
                .exchange().expectStatus().isOk();

        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        verify(metrics).weeklyCsv(from.capture(), to.capture());
        assertThat(java.time.Duration.between(from.getValue(), to.getValue())).isEqualTo(java.time.Duration.ofDays(7));
    }

    @Test
    void badDatesAreRejected() {
        WebTestClient client = client("s3cret");
        client.get().uri(PATH + "?from=yesterday").header(AdminAuthFilter.HEADER, "s3cret")
                .exchange().expectStatus().isBadRequest();
        client.get().uri(PATH + "?from=2026-09-08&to=2026-09-01").header(AdminAuthFilter.HEADER, "s3cret")
                .exchange().expectStatus().isBadRequest();
    }

    /** DR-12: re-crop is an admin route, token-gated, and "recrop" is a literal segment before {id}. */
    @Test
    void recropRoutesAreTokenGatedAndTheLiteralWinsOverTheId() {
        java.util.UUID id = java.util.UUID.randomUUID();
        when(recrop.recrop(id)).thenReturn(Mono.just(new com.elioo.baymax.extraction.application.port.in.RecropUseCase.RecropReport(
                id, "recropped", 2, 3, 1, 4, 0)));
        when(recrop.recropAll()).thenReturn(reactor.core.publisher.Flux.just(
                new com.elioo.baymax.extraction.application.port.in.RecropUseCase.RecropReport(id, "recropped", 2, 3, 1, 4, 0)));

        client("t").post().uri("/api/v1/baymax/admin/documents/" + id + "/recrop").header(AdminAuthFilter.HEADER, "t")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.outcome").isEqualTo("recropped").jsonPath("$.medicines").isEqualTo(3);
        client("t").post().uri("/api/v1/baymax/admin/documents/recrop").header(AdminAuthFilter.HEADER, "t")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.documents").isEqualTo(1).jsonPath("$.recropped").isEqualTo(1);
        client("t").post().uri("/api/v1/baymax/admin/documents/recrop")
                .exchange().expectStatus().isUnauthorized();
    }
}
