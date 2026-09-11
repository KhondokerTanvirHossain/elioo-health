package com.elioo.healthcare.aws.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AwsCommonAutoConfiguration Test")
class AwsCommonAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AwsCommonAutoConfiguration.class));

    @Test
    @DisplayName("Should create credentials provider with default credentials chain")
    void testDefaultCredentialsProvider() {
        contextRunner
                .withPropertyValues("aws.region=us-west-2")
                .run(context -> {
                    assertThat(context).hasSingleBean(AwsCredentialsProvider.class);
                    assertThat(context).hasSingleBean(Region.class);
                    assertThat(context).hasSingleBean(ObjectMapper.class);

                    AwsCredentialsProvider provider = context.getBean(AwsCredentialsProvider.class);
                    // Should be DefaultCredentialsProvider when no keys configured
                    assertThat(provider).isInstanceOf(DefaultCredentialsProvider.class);

                    Region region = context.getBean(Region.class);
                    assertThat(region).isEqualTo(Region.US_WEST_2);
                });
    }

    @Test
    @DisplayName("Should create credentials provider with static credentials")
    void testStaticCredentialsProvider() {
        contextRunner
                .withPropertyValues(
                        "aws.region=us-east-1",
                        "aws.access-key-id=AKIAIOSFODNN7EXAMPLE",
                        "aws.secret-access-key=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(AwsCredentialsProvider.class);

                    AwsCredentialsProvider provider = context.getBean(AwsCredentialsProvider.class);
                    // Should be StaticCredentialsProvider when keys configured
                    assertThat(provider).isInstanceOf(StaticCredentialsProvider.class);

                    Region region = context.getBean(Region.class);
                    assertThat(region).isEqualTo(Region.US_EAST_1);
                });
    }

    @Test
    @DisplayName("Should use default region when not configured")
    void testDefaultRegion() {
        contextRunner
                .run(context -> {
                    assertThat(context).hasSingleBean(Region.class);

                    Region region = context.getBean(Region.class);
                    // Default region from properties should be us-east-1
                    assertThat(region).isEqualTo(Region.US_EAST_1);
                });
    }

    @Test
    @DisplayName("Should load AwsCommonProperties")
    void testPropertiesLoading() {
        contextRunner
                .withPropertyValues(
                        "aws.region=eu-west-1",
                        "aws.retry.max-attempts=5",
                        "aws.retry.backoff-base-delay-ms=200"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(AwsCommonProperties.class);

                    AwsCommonProperties properties = context.getBean(AwsCommonProperties.class);
                    assertThat(properties.getRegion()).isEqualTo("eu-west-1");
                    assertThat(properties.getRetry().getMaxAttempts()).isEqualTo(5);
                    assertThat(properties.getRetry().getBackoffBaseDelayMs()).isEqualTo(200);
                });
    }

    @Test
    @DisplayName("Should create ObjectMapper bean")
    void testObjectMapperBean() {
        contextRunner
                .run(context -> {
                    assertThat(context).hasSingleBean(ObjectMapper.class);
                    ObjectMapper mapper = context.getBean(ObjectMapper.class);
                    assertThat(mapper).isNotNull();
                });
    }
}
