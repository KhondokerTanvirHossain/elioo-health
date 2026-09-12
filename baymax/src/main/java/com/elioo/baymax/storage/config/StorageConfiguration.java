package com.elioo.baymax.storage.config;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.storage.adapter.out.s3.S3StorageAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The storage adapter exists only when a bucket is configured; the rest of Baymax loads either way. */
@Configuration
public class StorageConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${baymax.storage.bucket:}')")
    public S3StorageAdapter storagePort(BaymaxProperties properties) {
        return S3StorageAdapter.create(properties.getStorage());
    }
}
