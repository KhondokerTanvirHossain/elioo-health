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
