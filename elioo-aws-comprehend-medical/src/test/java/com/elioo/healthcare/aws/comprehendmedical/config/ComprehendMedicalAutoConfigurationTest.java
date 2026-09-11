package com.elioo.healthcare.aws.comprehendmedical.config;

import com.elioo.healthcare.aws.comprehendmedical.api.ComprehendMedicalService;
import com.elioo.healthcare.aws.comprehendmedical.service.ComprehendMedicalServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.comprehendmedical.ComprehendMedicalAsyncClient;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ComprehendMedicalAutoConfiguration Test")
class ComprehendMedicalAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ComprehendMedicalAutoConfiguration.class))
            .withBean("awsRegion", Region.class, () -> Region.US_EAST_1)
            .withBean("awsCredentialsProvider", StaticCredentialsProvider.class, () ->
                    StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")));

    @Test
    @DisplayName("Should auto-configure when enabled")
    void testAutoConfiguration_Enabled() {
        contextRunner
                .withPropertyValues("aws.comprehend-medical.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(ComprehendMedicalAsyncClient.class);
                    assertThat(context).hasSingleBean(ComprehendMedicalService.class);
                    assertThat(context).hasSingleBean(ComprehendMedicalProperties.class);

                    // Verify bean is AWS implementation
                    ComprehendMedicalService service = context.getBean(ComprehendMedicalService.class);
                    assertThat(service).isInstanceOf(ComprehendMedicalServiceImpl.class);
                });
    }

    @Test
    @DisplayName("Should not auto-configure when disabled")
    void testAutoConfiguration_Disabled() {
        contextRunner
                .withPropertyValues("aws.comprehend-medical.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ComprehendMedicalAsyncClient.class);
                    assertThat(context).doesNotHaveBean(ComprehendMedicalService.class);
                });
    }

    @Test
    @DisplayName("Should load custom properties")
    void testPropertiesLoading() {
        contextRunner
                .withPropertyValues(
                        "aws.comprehend-medical.enabled=true",
                        "aws.comprehend-medical.min-confidence-threshold=0.85",
                        "aws.comprehend-medical.max-text-length=15000"
                )
                .run(context -> {
                    ComprehendMedicalProperties properties = context.getBean(ComprehendMedicalProperties.class);
                    assertThat(properties.getMinConfidenceThreshold()).isEqualTo(0.85);
                    assertThat(properties.getMaxTextLength()).isEqualTo(15000);
                });
    }
}
