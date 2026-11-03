package com.elioo.baymax.companion.config;

import com.elioo.baymax.aicall.application.service.AiCostCalculator;
import com.elioo.baymax.companion.application.port.out.ChatCopyPort;
import com.elioo.baymax.companion.application.service.ChatAnswerService;
import com.elioo.baymax.companion.application.service.ChatAppendix;
import com.elioo.baymax.companion.application.service.ChatLabels;
import com.elioo.baymax.companion.application.service.MedicinePurposeReply;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.config.BaymaxModelClient;
import com.elioo.baymax.extraction.config.ExtractionModelConfiguration;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.healthcare.llm.config.LlmProperties;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Refuses to start on a চ্যাট configuration that would break DR-50's envelope at run time rather than at boot:
 * <ul>
 *   <li>{@code baymax.chat.daily-cap} below one (every question would be refused);</li>
 *   <li>a bundle key of the context's labels ({@link ChatLabels#KEYS}), of the purpose reply
 *       ({@link MedicinePurposeReply#KEYS}, PO ruling P14) or of the appended lines ({@link ChatAppendix#KEYS}) missing in
 *       either language;</li>
 *   <li>{@code baymax.chat.max-answer-chars} below the longest set of fixed lines code appends, in either language, plus
 *       {@value #MIN_BODY} characters of body: the length cap runs on the whole assembled text, and a cap the fixed lines
 *       cannot fit would turn every answer, the fixed fallback included, into a refused one;</li>
 *   <li>{@code baymax.chat.model} with no price for its provider: every chat call would log a NULL cost, and the daily cost
 *       bound DR-50 reconciles against the console would be unknown;</li>
 *   <li>{@code baymax.chat.answer-deadline} past what the web composer waits ({@link #WEB_ASK_TIMEOUT}) less the store's
 *       grace and the network's margin: the web would show its fallback while Java went on to store an answer the family
 *       never saw (review of #166, finding 4);</li>
 *   <li>{@code baymax.chat.check.model} with no price while the check is on (PO ruling P15), for the same reason: the
 *       check's calls are logged as {@code chat_check} and added to the turn's cost;</li>
 *   <li>answers on and the check on with no check client (a blank {@code BAYMAX_CHAT_CHECK_MODEL}, or no key for the
 *       provider its id selects): every answer that mentions a medicine would be refused as {@code check_no_client}, and
 *       nothing on the box would say why (review of #173, findings 13 and 18);</li>
 *   <li>the check's timeout plus one model call not inside the answer deadline: an answer with no repair always leaves the
 *       check its whole timeout (review of #173, finding 5); after a repair the service gives it what is left.</li>
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
    private final ObjectProvider<BaymaxModelClient> checkClient;

    public ChatPropertiesCheck(BaymaxProperties properties, LlmProperties llm, AiCostCalculator costs, ChatCopyPort copy,
                               @Qualifier(ChatCheckModelConfiguration.CHECK_CLIENT) ObjectProvider<BaymaxModelClient> checkClient) {
        this.properties = properties;
        this.llm = llm;
        this.costs = costs;
        this.copy = copy;
        this.checkClient = checkClient;
    }

    @Override
    public void afterPropertiesSet() {
        BaymaxProperties.Chat chat = properties.getChat();
        if (chat.getDailyCap() < 1) {
            throw new IllegalStateException("baymax.chat.daily-cap must be at least 1, was " + chat.getDailyCap());
        }
        // the context's labels (plan CHATCOPY) are read per answer; a missing one refuses the boot, not a family's question.
        // Every key in both languages, whether answers are on or off: a Bangla ask reads the English ones too (review of #170)
        List<String> keys = new java.util.ArrayList<>(ChatLabels.KEYS);
        // PO ruling P14: the purpose reply and its appended line are read per answer too
        keys.addAll(MedicinePurposeReply.KEYS);
        keys.addAll(ChatAppendix.KEYS);
        for (String lang : List.of("bn", "en")) {
            for (String key : keys) {
                try {
                    copy.line(lang, key);
                } catch (RuntimeException e) {
                    throw new IllegalStateException("the chat label " + lang + ":" + key + " cannot be read ("
                            + e.getClass().getSimpleName() + "); every ChatLabels key must be in both ui bundles", e);
                }
            }
        }
        for (String lang : List.of("bn", "en")) {
            ChatLabels.of(lang, copy);
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
        priced("baymax.chat.model", chat.getModel());
        BaymaxProperties.Chat.Check check = chat.getCheck();
        if (check.isEnabled()) {
            priced("baymax.chat.check.model", check.getModel());
            if (chat.getModelTimeout().plus(check.getTimeout()).plus(ChatAnswerService.CHECK_MARGIN).compareTo(chat.getAnswerDeadline()) > 0) {
                throw new IllegalStateException("baymax.chat.model-timeout (" + chat.getModelTimeout() + ") plus baymax.chat.check.timeout ("
                        + check.getTimeout() + ") plus " + ChatAnswerService.CHECK_MARGIN + " must fit inside baymax.chat.answer-deadline ("
                        + chat.getAnswerDeadline() + ")");
            }
            if (chat.isEnabled() && checkClient.getIfAvailable() == null) {
                throw new IllegalStateException("baymax.chat.enabled and baymax.chat.check.enabled are both true but there is no chat"
                        + " check client: baymax.chat.check.model is '" + check.getModel() + "' and needs a non-blank id and the key of"
                        + " the provider it selects (BAYMAX_CHAT_CHECK_MODEL absent from the env, never blank; docs/RUNBOOK.md)");
            }
        }
    }

    /** A model named by {@code property}, when one is named, must have a price for the provider its id selects. */
    private void priced(String property, String model) {
        if (model == null || model.isBlank()) {
            return;
        }
        String provider = ExtractionModelConfiguration.isAnthropicModel(model) ? "anthropic"
                : "openai".equalsIgnoreCase(llm.getProvider()) ? "openai" : "groq";
        if (costs.llmCost(provider, model, 0, 0).isEmpty()) {
            throw new IllegalStateException(property + " is " + model + " and baymax.llm.prices." + provider + ".[" + model
                    + "] has no price; add it (USD per 1M tokens, with its source and date)");
        }
    }

    /** The longest appendix any turn can carry, and the emergency reply, in either language. */
    int longestAppendix() {
        int longest = 0;
        for (String lang : List.of("bn", "en")) {
            longest = Math.max(longest, ChatAppendix.tail(true, true, Optional.of(Urgency.THIS_WEEK), k -> copy.line(lang, k)).length());
            longest = Math.max(longest, ChatAppendix.tail(true, true, Optional.of(Urgency.NOW), k -> copy.line(lang, k)).length());
            longest = Math.max(longest, ChatAppendix.emergency(k -> copy.line(lang, k)).length());
        }
        return longest;
    }
}
