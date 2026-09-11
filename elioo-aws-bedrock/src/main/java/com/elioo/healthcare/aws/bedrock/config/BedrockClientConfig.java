package com.elioo.healthcare.aws.bedrock.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;

import java.time.Duration;

/**
 * Bedrock client configuration with explicit HTTP and API timeouts to avoid
 * client-side cancellations on long-running model invocations.
 */
@Slf4j
@Configuration
public class BedrockClientConfig {

    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    /**
     * Configure Bedrock runtime async client with 2 minute read/attempt timeouts.
     */
    @Bean
    public BedrockRuntimeAsyncClient bedrockRuntimeAsyncClient(AwsCredentialsProvider credentialsProvider) {
        log.info("Initializing Amazon Bedrock Runtime client in region: {}", awsRegion);

        return BedrockRuntimeAsyncClient.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(credentialsProvider)
                .httpClientBuilder(NettyNioAsyncHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5))
                        .readTimeout(Duration.ofMinutes(2))
                        .writeTimeout(Duration.ofMinutes(2)))
                .overrideConfiguration(cfg -> cfg
                        .apiCallTimeout(Duration.ofMinutes(2))
                        .apiCallAttemptTimeout(Duration.ofMinutes(2)))
                .build();
    }
}
