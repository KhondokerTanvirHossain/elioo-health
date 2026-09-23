package com.elioo.baymax.config;

import com.elioo.baymax.adapter.out.persistence.BaymaxSchemaMigrator;
import org.junit.jupiter.api.Test;
import com.elioo.healthcare.llm.config.LlmAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.r2dbc.R2dbcDataAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance for BMX-0: the module is loaded only when {@code baymax.enabled=true}, and when it is,
 * its Flyway history lives in schema {@code baymax}, not in the medscribe schema.
 */
@Testcontainers
class BaymaxAutoConfigurationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private ReactiveWebApplicationContextRunner runner() {
        return new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        R2dbcAutoConfiguration.class,
                        R2dbcDataAutoConfiguration.class,
                        LlmAutoConfiguration.class,
                        BaymaxAutoConfiguration.class))
                .withPropertyValues(
                        "llm.provider=groq",
                        "llm.groq.api-key=test-key",
                        "spring.r2dbc.url=r2dbc:postgresql://" + POSTGRES.getHost() + ":"
                                + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName(),
                        "spring.r2dbc.username=" + POSTGRES.getUsername(),
                        "spring.r2dbc.password=" + POSTGRES.getPassword(),
                        "baymax.flyway.url=" + jdbcUrl(),
                        "baymax.flyway.user=" + POSTGRES.getUsername(),
                        "baymax.flyway.password=" + POSTGRES.getPassword());
    }

    /**
     * BMX-10 RUNBOOK contract: with the WhatsApp flag unset the channel is entirely absent — not a disabled
     * endpoint that might still answer — so every existing behaviour is unchanged. The route appears only when
     * the flag is explicitly true.
     */
    @Test
    void theWhatsappWebhookExistsOnlyWhenTheChannelIsEnabled() {
        runner().withPropertyValues("baymax.enabled=true").run(context ->
                assertThat(context).doesNotHaveBean("baymaxWaRoutes"));
        runner().withPropertyValues("baymax.enabled=true", "baymax.wa.enabled=false").run(context ->
                assertThat(context).doesNotHaveBean("baymaxWaRoutes"));
        // with the channel on, the config must also be well formed — WaConfigValidator refuses to start
        // otherwise, which is the point of it, so the enabled case supplies credentials of the right shape
        runner().withPropertyValues("baymax.enabled=true", "baymax.wa.enabled=true",
                        "baymax.wa.token=EAA" + "x".repeat(200),
                        "baymax.wa.app-secret=88e3540dd539aabbccddeeff00112233",
                        "baymax.wa.verify-token=78d41af3449a9bfbd560140166c6f2c2",
                        "baymax.wa.phone-number-id=1412074765313019",
                        "baymax.wa.waba-id=4407553579467225")
                .run(context -> assertThat(context).hasBean("baymaxWaRoutes"));
    }

    /**
     * BMX-10: a malformed WhatsApp value stops the application rather than surfacing later as a rejected
     * handshake or a 403 on every callback. The inline-comment case is the one that actually happened.
     */
    @Test
    void aMalformedWhatsappValueStopsStartupWithANamedReason() {
        runner().withPropertyValues("baymax.enabled=true", "baymax.wa.enabled=true",
                        "baymax.wa.token=EAA" + "x".repeat(200),
                        "baymax.wa.app-secret=88e3540dd539aabbccddeeff00112233",
                        "baymax.wa.verify-token=78d41af3449a9bfbd560140166c6f2c2  # must match Meta exactly",
                        "baymax.wa.phone-number-id=1412074765313019",
                        "baymax.wa.waba-id=4407553579467225")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause()
                        .hasMessageContaining("BAYMAX_WA_VERIFY_TOKEN")
                        .hasMessageContaining("inline comment"));
    }

    /**
     * The crop verifier must exist whenever the module does. Its readers are what stand between a region
     * the model pointed at and a crop stored as a value's source (DR-12), so a context that silently lacks
     * the bean would fail at boot — and the application context test does not enable baymax, so nothing
     * else in the build constructs it.
     */
    @Test
    void theCropVerifierIsBuiltWheneverTheModuleIsEnabled() {
        runner().withPropertyValues("baymax.enabled=true").run(context ->
                assertThat(context).hasSingleBean(
                        com.elioo.baymax.extraction.application.service.CropVerifier.class));
    }

    @Test
    void disabledByDefault() {
        runner().run(context -> {
            assertThat(context).doesNotHaveBean(BaymaxAutoConfiguration.class);
            assertThat(context).doesNotHaveBean("baymaxHealthRoutes");
            assertThat(context).doesNotHaveBean(BaymaxSchemaMigrator.class);
        });
    }

    @Test
    void explicitlyDisabledLoadsNoRoutesAndRunsNoMigrations() {
        runner().withPropertyValues("baymax.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(BaymaxAutoConfiguration.class);
            assertThat(context).doesNotHaveBean("baymaxHealthRoutes");
            assertThat(context).doesNotHaveBean(BaymaxSchemaMigrator.class);
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void enabledServesHealthAndMigratesItsOwnSchema() {
        runner().withPropertyValues("baymax.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(BaymaxAutoConfiguration.class);
            assertThat(context).hasBean("baymaxHealthRoutes");

            WebTestClient client = WebTestClient.bindToRouterFunction(
                    (RouterFunction<?>) context.getBean("baymaxHealthRoutes")).build();
            client.get().uri("/api/v1/baymax/health").exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.status").isEqualTo("UP");

            assertThat(schemaExists("baymax")).isTrue();
            assertThat(appliedVersions("baymax")).contains("1", "2");
            assertThat(schemaExists("medscribe")).as("must never create the medscribe schema").isFalse();
        });
    }

    private static boolean schemaExists(String schema) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "select 1 from information_schema.schemata where schema_name = '" + schema + "'")) {
            return rs.next();
        }
    }

    private static java.util.List<String> appliedVersions(String schema) throws Exception {
        java.util.List<String> versions = new java.util.ArrayList<>();
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select version from " + schema + ".flyway_schema_history")) {
            while (rs.next()) {
                versions.add(rs.getString(1));
            }
        }
        return versions;
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(jdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** The container speaks no TLS; skip the driver's SSL attempt, which occasionally fails mid-handshake. */
    private static String jdbcUrl() {
        return POSTGRES.getJdbcUrl() + "&sslmode=disable";
    }
}
