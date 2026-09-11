package com.elioo.healthcare.aws.bedrock.health.config;

import com.elioo.healthcare.aws.bedrock.api.BedrockService;
import com.elioo.healthcare.aws.bedrock.health.api.BedrockHealthService;
import com.elioo.healthcare.aws.bedrock.health.prompt.DefaultPromptTemplateEngine;
import com.elioo.healthcare.aws.bedrock.health.prompt.PromptTemplateEngine;
import com.elioo.healthcare.aws.bedrock.health.service.BedrockHealthServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.concurrent.TimeUnit;

/**
 * Auto-configuration for Bedrock Health API.
 *
 * <p>This auto-configuration activates when:</p>
 * <ul>
 *   <li>{@code aws.bedrock.health.enabled=true} (default)</li>
 *   <li>BedrockService bean is available</li>
 *   <li>Spring WebFlux is on the classpath</li>
 * </ul>
 *
 * <p>Provides beans:</p>
 * <ul>
 *   <li>{@link PromptTemplateEngine} - Prompt template builder</li>
 *   <li>{@link BedrockHealthService} - High-level health API service</li>
 *   <li>{@link CacheManager} - Cache manager for response caching (if caching enabled)</li>
 * </ul>
 *
 * @since 0.2.0
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass({BedrockService.class, BedrockHealthService.class})
@ConditionalOnProperty(prefix = "aws.bedrock.health", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(BedrockHealthProperties.class)
public class BedrockHealthAutoConfiguration {

    public BedrockHealthAutoConfiguration() {
        log.info("🏥 Bedrock Health API auto-configuration activated");
    }

    /**
     * Create PromptTemplateEngine bean.
     *
     * <p>Provides default implementation that can be overridden by applications.</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public PromptTemplateEngine promptTemplateEngine(ObjectMapper objectMapper) {
        log.info("Creating default PromptTemplateEngine");
        return new DefaultPromptTemplateEngine(objectMapper);
    }

    /**
     * Create BedrockHealthService bean.
     *
     * <p>Wires together BedrockService, PromptTemplateEngine, and ObjectMapper
     * to provide the high-level health API.</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public BedrockHealthService bedrockHealthService(
            BedrockService bedrockService,
            PromptTemplateEngine promptTemplateEngine,
            ObjectMapper objectMapper
    ) {
        log.info("Creating BedrockHealthService");
        return new BedrockHealthServiceImpl(bedrockService, promptTemplateEngine, objectMapper);
    }

    /**
     * Cache configuration for health API responses.
     *
     * <p>Only activated when caching is enabled via properties.</p>
     */
    @AutoConfiguration
    @ConditionalOnProperty(prefix = "aws.bedrock.health.cache", name = "enabled", havingValue = "true", matchIfMissing = true)
    @EnableCaching
    public static class BedrockHealthCacheConfiguration {

        /**
         * Create CacheManager for health API caching.
         *
         * <p>Uses Caffeine cache with configurable TTL and max entries.</p>
         */
        @Bean("bedrockHealthCacheManager")
        @ConditionalOnMissingBean(name = "bedrockHealthCacheManager")
        @ConditionalOnClass(name = "com.github.benmanes.caffeine.cache.Caffeine")
        public CacheManager bedrockHealthCacheManager(BedrockHealthProperties properties) {
            log.info("Creating Bedrock Health cache manager (TTL: {}s, Max entries: {})",
                    properties.getCache().getTtlSeconds(),
                    properties.getCache().getMaxEntries());

            CaffeineCacheManager cacheManager = new CaffeineCacheManager(
                    "clinicalInsights",
                    "summaries",
                    "riskAssessments",
                    "recommendations",
                    "trendAnalysis",
                    "educationalContent"
            );

            cacheManager.setCaffeine(Caffeine.newBuilder()
                    .expireAfterWrite(properties.getCache().getTtlSeconds(), TimeUnit.SECONDS)
                    .maximumSize(properties.getCache().getMaxEntries())
                    .recordStats()
            );
            cacheManager.setAsyncCacheMode(true);

            return cacheManager;
        }
    }
}
