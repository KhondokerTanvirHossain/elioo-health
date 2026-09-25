package com.elioo.baymax.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** The price table keys carry '/' and '.', so they must be written in brackets; this pins that syntax. */
class BaymaxPropertiesBindingTest {

    @Configuration
    @EnableConfigurationProperties(BaymaxProperties.class)
    static class Config {
    }

    /**
     * The before/after crop measurement runs both arms on ONE build and switches this flag between them, so
     * a flag that silently failed to bind would make the "before" arm secretly identical to the "after" arm
     * and the recovery count meaningless. Asserted on the exact environment-variable spelling used to run it.
     */
    @Test
    void theImageRecoveryFlagBindsFromItsEnvironmentVariableName() {
        // A real environment source: only this one applies Spring's UNDERSCORE -> dot relaxation, which is
        // exactly what the measurement run relies on. A system property with the same spelling does NOT
        // bind, and asserting on one would have passed the test while the flag stayed on.
        new ApplicationContextRunner()
                .withUserConfiguration(Config.class)
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new org.springframework.core.env.SystemEnvironmentPropertySource(
                                org.springframework.core.env.StandardEnvironment
                                        .SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                java.util.Map.of("BAYMAX_EXTRACT_RECOVER_CROPS_FROM_IMAGE", "false"))))
                .run(context -> assertThat(context.getBean(BaymaxProperties.class)
                        .getExtract().isRecoverCropsFromImage())
                        .as("BAYMAX_EXTRACT_RECOVER_CROPS_FROM_IMAGE=false must turn image recovery OFF")
                        .isFalse());

        new ApplicationContextRunner()
                .withUserConfiguration(Config.class)
                .run(context -> assertThat(context.getBean(BaymaxProperties.class)
                        .getExtract().isRecoverCropsFromImage())
                        .as("on by default")
                        .isTrue());
    }

    @Test
    void bracketedModelKeysBind() {
        new ApplicationContextRunner()
                .withUserConfiguration(Config.class)
                .withPropertyValues(
                        "baymax.llm.prices.groq.[openai/gpt-oss-120b].input=0.15",
                        "baymax.llm.prices.groq.[openai/gpt-oss-120b].output=0.60",
                        "baymax.llm.prices.anthropic.[claude-opus-5].input=5.00",
                        "baymax.llm.prices.anthropic.[claude-opus-5].output=25.00",
                        "baymax.vision.cost-per-image=0.0015",
                        "baymax.admin.token=s3cret")
                .run(context -> {
                    BaymaxProperties p = context.getBean(BaymaxProperties.class);
                    assertThat(p.getLlm().getPrices().get("groq").get("openai/gpt-oss-120b").getInput())
                            .isEqualByComparingTo(new BigDecimal("0.15"));
                    assertThat(p.getLlm().getPrices().get("groq").get("openai/gpt-oss-120b").getOutput())
                            .isEqualByComparingTo(new BigDecimal("0.60"));
                    assertThat(p.getLlm().getPrices().get("anthropic").get("claude-opus-5").getOutput())
                            .isEqualByComparingTo(new BigDecimal("25"));
                    assertThat(p.getVision().getCostPerImage()).isEqualByComparingTo(new BigDecimal("0.0015"));
                    assertThat(p.getAdmin().getToken()).isEqualTo("s3cret");
                });
    }
}
