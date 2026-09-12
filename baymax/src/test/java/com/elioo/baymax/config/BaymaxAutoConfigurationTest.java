package com.elioo.baymax.config;

import com.elioo.baymax.adapter.out.persistence.BaymaxSchemaMigrator;
import org.junit.jupiter.api.Test;
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
                        BaymaxAutoConfiguration.class))
                .withPropertyValues(
                        "spring.r2dbc.url=r2dbc:postgresql://" + POSTGRES.getHost() + ":"
                                + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName(),
                        "spring.r2dbc.username=" + POSTGRES.getUsername(),
                        "spring.r2dbc.password=" + POSTGRES.getPassword(),
                        "baymax.flyway.url=" + POSTGRES.getJdbcUrl(),
                        "baymax.flyway.user=" + POSTGRES.getUsername(),
                        "baymax.flyway.password=" + POSTGRES.getPassword());
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
        // Own schema name so the assertion holds regardless of which test touched the shared container first
        runner().withPropertyValues("baymax.enabled=false", "baymax.schema=baymax_disabled").run(context -> {
            assertThat(context).doesNotHaveBean(BaymaxAutoConfiguration.class);
            assertThat(context).doesNotHaveBean("baymaxHealthRoutes");
            assertThat(schemaExists("baymax_disabled")).isFalse();
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
            assertThat(appliedVersions("baymax")).contains("1");
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
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
