package com.elioo.baymax.aicall.application.service;

import com.elioo.baymax.config.BaymaxProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Turns token counts into USD using the configured price table ({@code baymax.llm.prices}) and the
 * per-image Vision price ({@code baymax.vision.cost-per-image}). An unknown provider/model yields
 * {@link Optional#empty()} plus a WARN, so the call is logged with a NULL cost rather than a guess.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiCostCalculator {

    private static final BigDecimal PER_MILLION = BigDecimal.valueOf(1_000_000);
    static final int SCALE = 8;

    private final BaymaxProperties properties;

    public Optional<BigDecimal> llmCost(String provider, String model, int inputTokens, int outputTokens) {
        BaymaxProperties.Price price = lookup(provider, model);
        if (price == null || price.getInput() == null || price.getOutput() == null) {
            log.warn("No price configured for LLM {}/{}; cost_usd will be NULL. "
                    + "Set baymax.llm.prices.{}.[{}].input and .output (USD per 1M tokens).",
                    provider, model, provider, model);
            return Optional.empty();
        }
        BigDecimal cost = price.getInput().multiply(BigDecimal.valueOf(inputTokens))
                .add(price.getOutput().multiply(BigDecimal.valueOf(outputTokens)))
                .divide(PER_MILLION, SCALE, RoundingMode.HALF_UP);
        return Optional.of(cost);
    }

    public Optional<BigDecimal> visionCost() {
        BigDecimal perImage = properties.getVision().getCostPerImage();
        if (perImage == null) {
            log.warn("No price configured for Vision OCR; cost_usd will be NULL. Set baymax.vision.cost-per-image (USD).");
            return Optional.empty();
        }
        return Optional.of(perImage.setScale(SCALE, RoundingMode.HALF_UP));
    }

    private BaymaxProperties.Price lookup(String provider, String model) {
        if (provider == null || model == null) {
            return null;
        }
        Map<String, BaymaxProperties.Price> byModel = properties.getLlm().getPrices()
                .get(provider.toLowerCase(Locale.ROOT));
        if (byModel == null) {
            return null;
        }
        BaymaxProperties.Price exact = byModel.get(model);
        if (exact != null) {
            return exact;
        }
        return byModel.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(model))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }
}
