package com.elioo.healthcare.core.config;

import io.r2dbc.spi.ConnectionFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.config.AbstractR2dbcConfiguration;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;

/**
 * R2DBC configuration for PostgreSQL.
 *
 * <p>This configuration enables R2DBC repositories across the application.</p>
 *
 * <p>JSONB Handling:</p>
 * <ul>
 *   <li>Spring Data R2DBC automatically handles JSONB columns as String</li>
 *   <li>Entity fields with @Column annotation will be mapped correctly</li>
 *   <li>JSON serialization is handled in the persistence adapter layer</li>
 * </ul>
 */
@Configuration
@EnableR2dbcRepositories(basePackages = "com.elioo.healthcare")
public class R2dbcConfiguration extends AbstractR2dbcConfiguration {

    private final ConnectionFactory connectionFactory;

    public R2dbcConfiguration(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public ConnectionFactory connectionFactory() {
        return connectionFactory;
    }
}
