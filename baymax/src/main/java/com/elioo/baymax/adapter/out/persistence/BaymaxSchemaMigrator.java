package com.elioo.baymax.adapter.out.persistence;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.config.BaymaxSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Runs the Baymax Flyway migrations at startup against schema {@link BaymaxSchema#NAME}.
 *
 * <p>Deliberately builds and runs its own {@link Flyway} instance <em>without</em> exposing it as a
 * bean: Spring Boot's Flyway auto-configuration is {@code @ConditionalOnMissingBean(Flyway.class)},
 * so a second {@code Flyway} bean would silently switch off the MedScribe migrations.</p>
 *
 * <p>Migrations live under {@code classpath:db/baymax/migration}, outside {@code db/migration},
 * because Flyway scans locations recursively and MedScribe's instance would otherwise pick them up
 * and collide on version numbers.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "baymax.flyway", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BaymaxSchemaMigrator implements InitializingBean {

    static final String LOCATION = "classpath:db/baymax/migration";

    private final BaymaxProperties properties;

    @Override
    public void afterPropertiesSet() {
        BaymaxProperties.Flyway settings = properties.getFlyway();
        if (!StringUtils.hasText(settings.getUrl())) {
            throw new IllegalStateException(
                    "baymax.flyway.url is required when baymax.enabled=true (it defaults to spring.flyway.url)");
        }
        String schema = BaymaxSchema.NAME;
        Flyway flyway = Flyway.configure()
                .dataSource(settings.getUrl(), settings.getUser(), settings.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(settings.isCreateSchemas())
                .locations(LOCATION)
                .baselineOnMigrate(false)
                .load();
        MigrateResult result = flyway.migrate();
        log.info("Baymax schema '{}' migrated: {} migration(s) applied, now at version {}",
                schema, result.migrationsExecuted, result.targetSchemaVersion);
    }
}
