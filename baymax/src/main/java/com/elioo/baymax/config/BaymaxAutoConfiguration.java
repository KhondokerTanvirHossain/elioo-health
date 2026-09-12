package com.elioo.baymax.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;

/**
 * Entry point of the Baymax module into the shared Spring Boot deployable.
 *
 * <p>Registered through {@code META-INF/spring/...AutoConfiguration.imports}, so the host
 * application does not need to scan {@code com.elioo.baymax}. Everything in the module hangs
 * off this class: when {@code baymax.enabled} is not {@code true} the condition fails, nothing
 * below is scanned, no route is registered and no migration runs (DR-2).</p>
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "baymax", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(BaymaxProperties.class)
@ComponentScan(
        basePackages = "com.elioo.baymax",
        excludeFilters = @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = AutoConfiguration.class))
@EnableR2dbcRepositories(basePackages = "com.elioo.baymax")
public class BaymaxAutoConfiguration {
}
