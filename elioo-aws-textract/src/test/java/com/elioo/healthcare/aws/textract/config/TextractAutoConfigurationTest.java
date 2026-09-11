package com.elioo.healthcare.aws.textract.config;

import com.elioo.healthcare.aws.textract.api.TextractService;
import com.elioo.healthcare.aws.textract.service.TextractServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.textract.TextractAsyncClient;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TextractAutoConfiguration Test")
class TextractAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TextractAutoConfiguration.class))
            // Provide required beans from common module
            .withBean("awsRegion", Region.class, () -> Region.US_EAST_1)
            .withBean("awsCredentialsProvider", StaticCredentialsProvider.class, () ->
                    StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")));

    @Test
    @DisplayName("Should auto-configure Textract client and service when enabled")
    void testAutoConfiguration_Enabled() {
        contextRunner
                .withPropertyValues("aws.textract.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(TextractAsyncClient.class);
                    assertThat(context).hasSingleBean(TextractService.class);
                    assertThat(context).hasSingleBean(TextractProperties.class);

                    // Verify bean is AWS implementation
                    TextractService service = context.getBean(TextractService.class);
                    assertThat(service).isInstanceOf(TextractServiceImpl.class);
                });
    }

    @Test
    @DisplayName("Should not auto-configure when disabled")
    void testAutoConfiguration_Disabled() {
        contextRunner
                .withPropertyValues("aws.textract.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(TextractAsyncClient.class);
                    assertThat(context).doesNotHaveBean(TextractService.class);
                });
    }

    @Test
    @DisplayName("Should load custom properties")
    void testPropertiesLoading() {
        contextRunner
                .withPropertyValues(
                        "aws.textract.enabled=true",
                        "aws.textract.min-confidence-threshold=0.90",
                        "aws.textract.max-image-size-mb=15",
                        "aws.textract.min-image-size-mb=0.2"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(TextractProperties.class);

                    TextractProperties properties = context.getBean(TextractProperties.class);
                    assertThat(properties.getMinConfidenceThreshold()).isEqualTo(0.90);
                    assertThat(properties.getMaxImageSizeMb()).isEqualTo(15);
                    assertThat(properties.getMinImageSizeMb()).isEqualTo(0.2);
                });
    }

    @Test
    @DisplayName("Should use default properties when not specified")
    void testDefaultProperties() {
        contextRunner
                .withPropertyValues("aws.textract.enabled=true")
                .run(context -> {
                    TextractProperties properties = context.getBean(TextractProperties.class);

                    assertThat(properties.getMinConfidenceThreshold()).isEqualTo(0.80);
                    assertThat(properties.getMaxImageSizeMb()).isEqualTo(10);
                    assertThat(properties.getMinImageSizeMb()).isEqualTo(0.1);
                    assertThat(properties.getDefaultFeatureTypes()).isEqualTo("TABLES,FORMS,LAYOUT");
                });
    }
}
