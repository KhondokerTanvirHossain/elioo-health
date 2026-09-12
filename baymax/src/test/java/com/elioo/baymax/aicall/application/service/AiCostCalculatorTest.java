package com.elioo.baymax.aicall.application.service;

import com.elioo.baymax.config.BaymaxProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AiCostCalculatorTest {

    private static BaymaxProperties props(String provider, String model, String in, String out, String vision) {
        BaymaxProperties p = new BaymaxProperties();
        if (provider != null) {
            BaymaxProperties.Price price = new BaymaxProperties.Price();
            price.setInput(new BigDecimal(in));
            price.setOutput(new BigDecimal(out));
            p.getLlm().getPrices().put(provider, Map.of(model, price));
        }
        if (vision != null) {
            p.getVision().setCostPerImage(new BigDecimal(vision));
        }
        return p;
    }

    @Test
    void costIsTokensTimesPricePerMillion() {
        AiCostCalculator calc = new AiCostCalculator(props("groq", "openai/gpt-oss-120b", "0.15", "0.60", null));

        assertThat(calc.llmCost("groq", "openai/gpt-oss-120b", 1_000_000, 1_000_000))
                .contains(new BigDecimal("0.75000000"));
        assertThat(calc.llmCost("groq", "openai/gpt-oss-120b", 1000, 200))
                .contains(new BigDecimal("0.00027000"));
    }

    @Test
    void providerLookupIsCaseInsensitive() {
        AiCostCalculator calc = new AiCostCalculator(props("anthropic", "claude-opus-5", "5", "25", null));

        assertThat(calc.llmCost("Anthropic", "Claude-Opus-5", 100, 10)).contains(new BigDecimal("0.00075000"));
    }

    @Test
    void unknownModelYieldsNoCostRatherThanAGuess() {
        AiCostCalculator calc = new AiCostCalculator(props("groq", "openai/gpt-oss-120b", "0.15", "0.60", null));

        assertThat(calc.llmCost("groq", "llama-3.3-70b-versatile", 1000, 10)).isEmpty();
        assertThat(calc.llmCost("openai", "openai/gpt-oss-120b", 1000, 10)).isEmpty();
        assertThat(calc.llmCost(null, null, 1, 1)).isEmpty();
    }

    @Test
    void incompletePriceCountsAsUnknown() {
        BaymaxProperties p = new BaymaxProperties();
        BaymaxProperties.Price half = new BaymaxProperties.Price();
        half.setInput(BigDecimal.ONE);
        p.getLlm().getPrices().put("groq", Map.of("m", half));

        assertThat(new AiCostCalculator(p).llmCost("groq", "m", 1, 1)).isEmpty();
    }

    @Test
    void visionCostComesFromConfigOrIsAbsent() {
        assertThat(new AiCostCalculator(props(null, null, null, null, "0.0015")).visionCost())
                .contains(new BigDecimal("0.00150000"));
        assertThat(new AiCostCalculator(props(null, null, null, null, null)).visionCost()).isEmpty();
    }
}
