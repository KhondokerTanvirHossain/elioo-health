package com.elioo.baymax.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code baymax.*} settings. Defaults are chosen so that the module is invisible unless
 * explicitly enabled, and so that its Flyway connection follows MedScribe's
 * ({@code spring.flyway.*}) unless overridden.
 */
@Data
@ConfigurationProperties(prefix = "baymax")
public class BaymaxProperties {

    /** Master switch for the whole module. */
    private boolean enabled = false;

    /** Postgres schema owned by Baymax. Never {@code medscribe}. */
    private String schema = "baymax";

    private Flyway flyway = new Flyway();

    @Data
    public static class Flyway {
        /** Run the Baymax migrations at startup. */
        private boolean enabled = true;
        /** Let Flyway create the schema if it is missing (false where the DB role cannot CREATE SCHEMA). */
        private boolean createSchemas = true;
        /** JDBC URL; defaults to {@code spring.flyway.url} via application.properties. */
        private String url;
        private String user;
        private String password;
    }
}
