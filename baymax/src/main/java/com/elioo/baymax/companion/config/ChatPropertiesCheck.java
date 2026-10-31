package com.elioo.baymax.companion.config;

import com.elioo.baymax.aicall.application.service.AiCostCalculator;
import com.elioo.baymax.companion.application.port.out.ChatCopyPort;
import com.elioo.baymax.companion.application.service.ChatAnswerService;
import com.elioo.baymax.companion.application.service.ChatAppendix;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.config.ExtractionModelConfiguration;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.healthcare.llm.config.LlmProperties;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Refuses to start on a চ্যাট configuration that would break DR-50's envelope at run time rather than at boot:
 * <ul>
 *   <li>{@code baymax.chat.daily-cap} below one (every question would be refused);</li>
 *   <li>{@code baymax.chat.max-answer-chars} below the longest set of fixed lines code appends, in either language, plus
 *       {@value #MIN_BODY} characters of body: the length cap runs on the whole assembled text, and a cap the fixed lines
 *       cannot fit would turn every answer, the fixed fallback included, into a refused one;</li>
 *   <li>{@code baymax.chat.model} with no price for its provider: every chat call would log a NULL cost, and the daily cost
 *       bound DR-50 reconciles against the console would be unknown;</li>
 *   <li>{@code baymax.chat.answer-deadline} past what the web composer waits ({@link #WEB_ASK_TIMEOUT}) less the store's
 *       grace and the network's margin: the web would show its fallback while Java went on to store an answer the family
 *       never saw (review of #166, finding 4).</li>
 * </ul>
 */
@Component
public class ChatPropertiesCheck implements InitializingBean {

    static final int MIN_BODY = 200;

    /**
     * How long the web composer waits for an answer (medioo-web Composer.tsx {@code ASK_TIMEOUT_MS}, held equal to this by
     * ChatModelConfigurationTest). Review of #166, finding 4.
     */
    public static final Duration WEB_ASK_TIMEOUT = Duration.ofSeconds(50);

    /** What the network may add between Java's answer and the browser's clock. */
    static final Duration NETWORK_MARGIN = Duration.ofSeconds(2);

    private final BaymaxProperties properties;
    private final LlmProperties llm;
    private final AiCostCalculator costs;
    private final ChatCopyPort copy;

    public ChatPropertiesCheck(BaymaxProperties properties, LlmProperties llm, AiCostCalculator costs, ChatCopyPort copy) {
        this.properties = properties;
        this.llm = llm;
        this.costs = costs;
        this.copy = copy;
    }

    @Override
    public void afterPropertiesSet() {
        BaymaxProperties.Chat chat = properties.getChat();
        if (chat.getDailyCap() < 1) {
            throw new IllegalStateException("baymax.chat.daily-cap must be at least 1, was " + chat.getDailyCap());
        }
        int longest = longestAppendix();
        if (chat.getMaxAnswerChars() < longest + MIN_BODY) {
            throw new IllegalStateException("baymax.chat.max-answer-chars is " + chat.getMaxAnswerChars()
                    + " but the fixed lines alone run to " + longest + " characters; it must be at least " + (longest + MIN_BODY));
        }
        Duration most = WEB_ASK_TIMEOUT.minus(ChatAnswerService.STORE_GRACE).minus(NETWORK_MARGIN);
        if (chat.getAnswerDeadline() == null || chat.getAnswerDeadline().compareTo(most) > 0) {
            throw new IllegalStateException("baymax.chat.answer-deadline is " + chat.getAnswerDeadline() + " but the web composer waits "
                    + WEB_ASK_TIMEOUT + "; with the store's grace and the network's margin it must be at most " + most);
        }
        String model = chat.getModel();
        if (model != null && !model.isBlank()) {
            String provider = ExtractionModelConfiguration.isAnthropicModel(model) ? "anthropic"
                    : "openai".equalsIgnoreCase(llm.getProvider()) ? "openai" : "groq";
            if (costs.llmCost(provider, model, 0, 0).isEmpty()) {
                throw new IllegalStateException("baymax.chat.model is " + model + " and baymax.llm.prices." + provider + ".[" + model
                        + "] has no price; add it (USD per 1M tokens, with its source and date)");
            }
        }
    }

    /** The longest appendix any turn can carry, and the emergency reply, in either language. */
    int longestAppendix() {
        int longest = 0;
        for (String lang : List.of("bn", "en")) {
            longest = Math.max(longest, ChatAppendix.tail(true, Optional.of(Urgency.THIS_WEEK), k -> copy.line(lang, k)).length());
            longest = Math.max(longest, ChatAppendix.tail(true, Optional.of(Urgency.NOW), k -> copy.line(lang, k)).length());
            longest = Math.max(longest, ChatAppendix.emergency(k -> copy.line(lang, k)).length());
        }
        return longest;
    }
}
