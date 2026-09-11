package com.elioo.healthcare.aws.bedrock.config;

import com.elioo.healthcare.aws.bedrock.api.BedrockService;
import com.elioo.healthcare.aws.bedrock.service.BedrockServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;

import static org.assertj.core.api.Assertions.assertThat;

class BedrockAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BedrockAutoConfiguration.class))
            .withBean(AwsCredentialsProvider.class, () ->
                    StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
            .withBean(Region.class, () -> Region.US_EAST_1);

    @Test
    void testAutoConfiguration_BeansCreated() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(BedrockRuntimeAsyncClient.class);
            assertThat(context).hasSingleBean(BedrockService.class);
            assertThat(context).hasSingleBean(BedrockProperties.class);
            assertThat(context).hasSingleBean(ObjectMapper.class);

            // Verify bean is AWS implementation
            BedrockService service = context.getBean(BedrockService.class);
            assertThat(service).isInstanceOf(BedrockServiceImpl.class);
        });
    }

    @Test
    void testAutoConfiguration_DefaultProperties() {
        contextRunner.run(context -> {
            BedrockProperties properties = context.getBean(BedrockProperties.class);

            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getModelId()).isEqualTo("anthropic.claude-3-5-sonnet-20241022-v2:0");
            assertThat(properties.getMaxTokens()).isEqualTo(4096);
            assertThat(properties.getTemperature()).isEqualTo(0.7);
            assertThat(properties.getTimeoutSeconds()).isEqualTo(120);
        });
    }

    @Test
    void testAutoConfiguration_CustomProperties() {
        contextRunner
                .withPropertyValues(
                        "aws.bedrock.model-id=anthropic.claude-3-haiku-20240307-v1:0",
                        "aws.bedrock.max-tokens=2000",
                        "aws.bedrock.temperature=0.5",
                        "aws.bedrock.top-p=0.9",
                        "aws.bedrock.timeout-seconds=60",
                        "aws.bedrock.log-requests=true"
                )
                .run(context -> {
                    BedrockProperties properties = context.getBean(BedrockProperties.class);

                    assertThat(properties.getModelId()).isEqualTo("anthropic.claude-3-haiku-20240307-v1:0");
                    assertThat(properties.getMaxTokens()).isEqualTo(2000);
                    assertThat(properties.getTemperature()).isEqualTo(0.5);
                    assertThat(properties.getTopP()).isEqualTo(0.9);
                    assertThat(properties.getTimeoutSeconds()).isEqualTo(60);
                    assertThat(properties.isLogRequests()).isTrue();
                });
    }

    @Test
    void testAutoConfiguration_Disabled() {
        contextRunner
                .withPropertyValues("aws.bedrock.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(BedrockRuntimeAsyncClient.class);
                    assertThat(context).doesNotHaveBean(BedrockService.class);
                });
    }

    @Test
    void testAutoConfiguration_BeansConditionalOnMissing() {
        // Custom ObjectMapper should not be overridden
        ObjectMapper customMapper = new ObjectMapper();

        contextRunner
                .withBean(ObjectMapper.class, () -> customMapper)
                .run(context -> {
                    assertThat(context).hasSingleBean(ObjectMapper.class);
                    assertThat(context.getBean(ObjectMapper.class)).isSameAs(customMapper);
                });
    }

    @Test
    void testAutoConfiguration_ServiceInitialization() {
        contextRunner.run(context -> {
            BedrockService service = context.getBean(BedrockService.class);
            assertThat(service).isNotNull();
        });
    }

    @Test
    void testAutoConfiguration_ClientConfiguration() {
        contextRunner
                .withPropertyValues("aws.bedrock.timeout-seconds=30")
                .run(context -> {
                    BedrockRuntimeAsyncClient client = context.getBean(BedrockRuntimeAsyncClient.class);
                    assertThat(client).isNotNull();

                    // Verify client is properly configured (basic check)
                    assertThat(client.serviceName()).isEqualTo("bedrock");
                });
    }
}
