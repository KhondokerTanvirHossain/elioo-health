package com.elioo.healthcare.aws.starter;

import com.elioo.healthcare.aws.bedrock.api.BedrockService;
import com.elioo.healthcare.aws.comprehendmedical.api.ComprehendMedicalService;
import com.elioo.healthcare.aws.textract.api.TextractService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for AWS Spring Boot Starter auto-configuration.
 *
 * Verifies that all AWS service auto-configurations are properly loaded
 * and beans are created when using the starter.
 */
@DisplayName("AWS Starter Auto-Configuration Test")
class AwsStarterAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    com.elioo.healthcare.aws.textract.config.TextractAutoConfiguration.class,
                    com.elioo.healthcare.aws.comprehendmedical.config.ComprehendMedicalAutoConfiguration.class,
                    com.elioo.healthcare.aws.bedrock.config.BedrockAutoConfiguration.class
            ))
            .withBean("awsRegion", Region.class, () -> Region.US_EAST_1)
            .withBean("awsCredentialsProvider", StaticCredentialsProvider.class, () ->
                    StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")));

    @Test
    @DisplayName("Should auto-configure all AWS services when enabled")
    void testAllServicesAutoConfigured() {
        contextRunner
                .withPropertyValues(
                        "aws.textract.enabled=true",
                        "aws.comprehend-medical.enabled=true",
                        "aws.bedrock.enabled=true"
                )
                .run(context -> {
                    // Verify all service beans are created
                    assertThat(context).hasSingleBean(TextractService.class);
                    assertThat(context).hasSingleBean(ComprehendMedicalService.class);
                    assertThat(context).hasSingleBean(BedrockService.class);

                    // Verify beans are not null
                    assertThat(context.getBean(TextractService.class)).isNotNull();
                    assertThat(context.getBean(ComprehendMedicalService.class)).isNotNull();
                    assertThat(context.getBean(BedrockService.class)).isNotNull();
                });
    }

    @Test
    @DisplayName("Should allow selective service enablement")
    void testSelectiveServiceEnablement() {
        contextRunner
                .withPropertyValues(
                        "aws.textract.enabled=true",
                        "aws.comprehend-medical.enabled=false",
                        "aws.bedrock.enabled=true"
                )
                .run(context -> {
                    // Textract should be enabled
                    assertThat(context).hasSingleBean(TextractService.class);

                    // Comprehend Medical should be disabled
                    assertThat(context).doesNotHaveBean(ComprehendMedicalService.class);

                    // Bedrock should be enabled
                    assertThat(context).hasSingleBean(BedrockService.class);
                });
    }

    @Test
    @DisplayName("Should enable all services by default")
    void testDefaultConfiguration() {
        contextRunner
                .run(context -> {
                    // All services should be enabled by default
                    assertThat(context).hasSingleBean(TextractService.class);
                    assertThat(context).hasSingleBean(ComprehendMedicalService.class);
                    assertThat(context).hasSingleBean(BedrockService.class);
                });
    }

    @Test
    @DisplayName("Should disable all services when explicitly disabled")
    void testAllServicesDisabled() {
        contextRunner
                .withPropertyValues(
                        "aws.textract.enabled=false",
                        "aws.comprehend-medical.enabled=false",
                        "aws.bedrock.enabled=false"
                )
                .run(context -> {
                    assertThat(context).doesNotHaveBean(TextractService.class);
                    assertThat(context).doesNotHaveBean(ComprehendMedicalService.class);
                    assertThat(context).doesNotHaveBean(BedrockService.class);
                });
    }
}
