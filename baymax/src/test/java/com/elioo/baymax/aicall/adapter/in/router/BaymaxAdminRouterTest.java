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
    private final com.elioo.baymax.outbound.application.port.in.ReviewGateUseCase review =
            mock(com.elioo.baymax.outbound.application.port.in.ReviewGateUseCase.class);
    private final com.elioo.baymax.extraction.application.port.in.RecropUseCase recrop =
            mock(com.elioo.baymax.extraction.application.port.in.RecropUseCase.class);
    private final com.elioo.baymax.outbound.application.port.in.DocumentUrgencyUseCase urgency =
            mock(com.elioo.baymax.outbound.application.port.in.DocumentUrgencyUseCase.class);

    private WebTestClient client(String configuredToken) {
        BaymaxProperties props = new BaymaxProperties();
        props.getAdmin().setToken(configuredToken);
        when(metrics.weeklyCsv(any(), any())).thenReturn(Mono.just("# documents\n"));
        return WebTestClient.bindToRouterFunction(new BaymaxAdminRouter()
                .baymaxAdminRoutes(new AdminMetricsHandler(metrics), mock(StorageSelfTestHandler.class),
                        new com.elioo.baymax.extraction.adapter.in.handler.RecropHandler(recrop, urgency),
                        new com.elioo.baymax.outbound.adapter.in.handler.ReviewGateHandler(review),
                        new AdminAuthFilter(props), new ErrorResponseFilter(), mock(com.elioo.baymax.nudge.application.port.in.NudgeUseCase.class))).build();
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
        when(urgency.assessAndStore(any())).thenReturn(Mono.empty());
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
    /**
     * F1: a re-crop rewrites which values a DONE document shows, so it is an assessment site: the stored urgency is
     * assessed through the one writer AFTER the items are rewritten, on both the one-document and the all-documents route.
     * The order is the order the work RAN in (each Mono records when it is subscribed), not the order the mocks were
     * asked for their Monos: a route that assembles both and subscribes the assessment first would pass an InOrder check.
     */
    @Test
    void aRecropAssessesTheStoredUrgencyAfterTheItemsAreRewritten() {
        java.util.UUID one = java.util.UUID.randomUUID(), other = java.util.UUID.randomUUID();
        java.util.List<String> ran = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        when(urgency.assessAndStore(any())).thenAnswer(i -> Mono.fromRunnable(() -> ran.add("assess:" + i.getArgument(0))));
        when(recrop.recrop(one)).thenReturn(recropped(one, ran));
        when(recrop.recropAll()).thenReturn(reactor.core.publisher.Flux.concat(recropped(one, ran), recropped(other, ran)));

        client("t").post().uri("/api/v1/baymax/admin/documents/" + one + "/recrop").header(AdminAuthFilter.HEADER, "t")
                .exchange().expectStatus().isOk();
        assertThat(ran).as("one document").containsExactly("recrop:" + one, "assess:" + one);

        ran.clear();
        client("t").post().uri("/api/v1/baymax/admin/documents/recrop").header(AdminAuthFilter.HEADER, "t")
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.documents").isEqualTo(2);
        assertThat(ran).as("all documents").hasSize(4);
        for (java.util.UUID id : java.util.List.of(one, other)) {
            assertThat(ran.indexOf("recrop:" + id)).as("re-cropped before assessed: " + id)
                    .isNotNegative().isLessThan(ran.indexOf("assess:" + id));
        }
    }

    /**
     * An assessment that throws after a bulk re-crop is logged by class name — never the message, which can carry a value,
     * and with no throwable attached, whose stack trace would print it — and the rest of the corpus is still re-cropped.
     */
    @Test
    void aThrowingAssessmentDoesNotStopABulkRecrop() {
        java.util.UUID bad = java.util.UUID.randomUUID(), good = java.util.UUID.randomUUID();
        when(urgency.assessAndStore(bad)).thenReturn(Mono.error(new IllegalStateException("Ma Potassium 11.0")));
        when(urgency.assessAndStore(good)).thenReturn(Mono.empty());
        when(recrop.recropAll()).thenReturn(reactor.core.publisher.Flux.just(
                new com.elioo.baymax.extraction.application.port.in.RecropUseCase.RecropReport(bad, "recropped", 1, 0, 0, 0, 0),
                new com.elioo.baymax.extraction.application.port.in.RecropUseCase.RecropReport(good, "recropped", 1, 0, 0, 0, 0)));
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                com.elioo.baymax.extraction.adapter.in.handler.RecropHandler.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logged = new ch.qos.logback.core.read.ListAppender<>();
        logged.start();
        logger.addAppender(logged);
        try {
            client("t").post().uri("/api/v1/baymax/admin/documents/recrop").header(AdminAuthFilter.HEADER, "t")
                    .exchange().expectStatus().isOk().expectBody().jsonPath("$.documents").isEqualTo(2);
        } finally {
            logger.detachAppender(logged);
        }
        verify(urgency).assessAndStore(good);
        assertThat(logged.list.stream().filter(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR)
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage))
                .containsExactly("[baymax] urgency store after recrop failed documentId=" + bad + " error=IllegalStateException");
        assertThat(logged.list).allSatisfy(e -> {
            assertThat(e.getFormattedMessage()).doesNotContain("Potassium").doesNotContain("11.0");
            assertThat(e.getThrowableProxy()).as("no throwable on " + e.getFormattedMessage()).isNull();
        });
    }

    /** A re-crop of {@code id} that records, when it actually runs, that it ran. */
    private static Mono<com.elioo.baymax.extraction.application.port.in.RecropUseCase.RecropReport> recropped(
            java.util.UUID id, java.util.List<String> ran) {
        return Mono.fromCallable(() -> {
            ran.add("recrop:" + id);
            return new com.elioo.baymax.extraction.application.port.in.RecropUseCase.RecropReport(id, "recropped", 2, 3, 1, 4, 0);
        });
    }
}
