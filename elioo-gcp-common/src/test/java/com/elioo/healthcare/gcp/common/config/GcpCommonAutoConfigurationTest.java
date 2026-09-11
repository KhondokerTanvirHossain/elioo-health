package com.elioo.healthcare.gcp.common.config;

import com.google.auth.Credentials;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GcpCommonAutoConfiguration")
class GcpCommonAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(GcpCommonAutoConfiguration.class));

    @Test
    @DisplayName("does nothing when gcp.enabled is not set")
    void disabledByDefault() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(Credentials.class));
    }

    @Test
    @DisplayName("starts with placeholder credentials when the credentials file is missing")
    void missingCredentialsFileDoesNotFailStartup() {
        runner.withPropertyValues(
                        "gcp.enabled=true",
                        "gcp.project-id=test-project",
                        "gcp.credentials-path=/definitely/not/here/gcp-credentials.json")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(Credentials.class);
                });
    }

    @Test
    @DisplayName("starts with placeholder credentials when the classpath resource is missing")
    void missingClasspathResourceDoesNotFailStartup() {
        runner.withPropertyValues(
                        "gcp.enabled=true",
                        "gcp.project-id=test-project",
                        "gcp.credentials-path=classpath:gcp/does-not-exist.json")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(Credentials.class);
                });
    }

    @Test
    @DisplayName("starts with placeholder credentials when inline JSON is malformed")
    void malformedInlineJsonDoesNotFailStartup() {
        runner.withPropertyValues(
                        "gcp.enabled=true",
                        "gcp.project-id=test-project",
                        "gcp.credentials-path=",
                        "gcp.credentials-json={not json")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(Credentials.class);
                });
    }
}
