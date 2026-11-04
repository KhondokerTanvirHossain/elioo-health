package com.elioo.baymax.companion.config;

import com.elioo.baymax.aicall.application.service.AiCostCalculator;
import com.elioo.baymax.companion.application.port.out.ChatCopyPort;
import com.elioo.baymax.companion.application.service.ChatAnswerService;
import com.elioo.baymax.companion.application.service.ChatAnswerCheck;
import com.elioo.baymax.companion.application.service.ChatLabels;
import com.elioo.baymax.companion.domain.ChatContext;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.config.BaymaxModelClient;
import com.elioo.baymax.outbound.application.service.Copy;
import com.elioo.baymax.web.adapter.in.ui.UiChatCopyAdapter;
import com.elioo.baymax.web.adapter.in.ui.UiCopy;
import com.elioo.healthcare.llm.config.LlmProperties;
import com.elioo.healthcare.llm.model.LlmRequest;
import com.elioo.healthcare.llm.provider.anthropic.AnthropicLlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan CHAT-api Task 5 (DR-50 MODEL): the chat model is a property, never code; its client follows the id; no key means
 * no client and a context that still starts; the shipped model is priced from the shipped table (derived from the
 * property, not a hand list); and a cap the fixed lines cannot fit stops startup.
 */
class ChatModelConfigurationTest {

    static final Path SHIPPED = Path.of("../medscribe-ai/src/main/resources/application.properties");

    @Configuration
    @EnableConfigurationProperties({BaymaxProperties.class, LlmProperties.class})
    static class Props {
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(Props.class, ChatModelConfiguration.class, ChatCheckModelConfiguration.class, ChatPropertiesCheck.class,
                        AiCostCalculator.class, UiCopy.class, Copy.class, UiChatCopyAdapter.class)
                .withPropertyValues("baymax.llm.prices.anthropic.[claude-haiku-4-5].input=1.00",
                        "baymax.llm.prices.anthropic.[claude-haiku-5-5].input=0.10",
                        "baymax.llm.prices.anthropic.[claude-haiku-5-5].output=0.50",
                        "baymax.llm.prices.anthropic.[claude-haiku-4-5].output=5.00",
                        "baymax.llm.prices.anthropic.[claude-sonnet-5].input=2.00",
                        "baymax.llm.prices.anthropic.[claude-sonnet-5].output=10.00");
    }

    private static String configuredModel(BaymaxModelClient bean) {
        assertThat(bean.delegate()).isInstanceOf(AnthropicLlmClient.class);
        return ((LlmProperties.Anthropic) ReflectionTestUtils.getField(bean.delegate(), "cfg")).getModel();
    }

    @Test
    void theChatClientFollowsTheModelProperty() {
        runner().withPropertyValues("baymax.chat.model=claude-haiku-4-5", "llm.anthropic.api-key=test-key").run(context -> {
            assertThat(context).hasNotFailed();
            BaymaxModelClient bean = context.getBean(ChatModelConfiguration.CHAT_CLIENT, BaymaxModelClient.class);
            assertThat(configuredModel(bean)).isEqualTo("claude-haiku-4-5");
        });
        runner().withPropertyValues("baymax.chat.model=claude-sonnet-5", "baymax.chat.effort=low", "llm.anthropic.api-key=test-key")
                .run(context -> {
                    BaymaxModelClient bean = context.getBean(ChatModelConfiguration.CHAT_CLIENT, BaymaxModelClient.class);
                    assertThat(configuredModel(bean)).isEqualTo("claude-sonnet-5");
                    assertThat(((LlmProperties.Anthropic) ReflectionTestUtils.getField(bean.delegate(), "cfg")).getEffort()).isEqualTo("low");
                });
    }

    @Test
    void noKeyMeansNoChatClientAndTheContextStarts() {
        runner().withPropertyValues("baymax.chat.model=claude-sonnet-5").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ChatModelConfiguration.CHAT_CLIENT);
        });
        runner().withPropertyValues("baymax.chat.model=", "llm.anthropic.api-key=test-key").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ChatModelConfiguration.CHAT_CLIENT);
        });
    }

    /** The shipped application.properties, bound as the application binds it: whatever model it names must be priced. */
    @Test
    void everyConfiguredChatModelIsPriced() throws Exception {
        assertThat(SHIPPED).exists();
        Properties shipped = new Properties();
        try (Reader r = new InputStreamReader(Files.newInputStream(SHIPPED), StandardCharsets.UTF_8)) {
            shipped.load(r);
        }
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addFirst(new PropertiesPropertySource("shipped", shipped));
        BaymaxProperties bound = Binder.get(env).bind("baymax", BaymaxProperties.class).get();
        String model = bound.getChat().getModel();
        assertThat(model).as("baymax.chat.model in the shipped properties").isNotBlank().startsWith("claude-");

        Optional<BigDecimal> cost = new AiCostCalculator(bound).llmCost("anthropic", model, 1_000_000, 1_000_000);
        assertThat(cost).as("the price of " + model + " for a million in and a million out").isPresent();
        assertThat(cost.get()).isEqualByComparingTo("12.00000000");
        assertThat(bound.getChat().getEffort()).isEqualTo("low");
        assertThat(bound.getChat().isEnabled()).as("answers ship off until the live measurement and CHAT-web (review of #164)")
                .isFalse();
        assertThat(bound.getChat().getDailyCap()).isEqualTo(50);

        // PO ruling P15: the refuse-only model check ships on, on a priced Claude model
        BaymaxProperties.Chat.Check check = bound.getChat().getCheck();
        assertThat(check.isEnabled()).as("the model check is on whenever answers are").isTrue();
        assertThat(check.getModel()).as("baymax.chat.check.model in the shipped properties").isNotBlank().startsWith("claude-");
        assertThat(new AiCostCalculator(bound).llmCost("anthropic", check.getModel(), 1_000_000, 1_000_000))
                .as("the price of " + check.getModel()).isPresent();
        // the end-to-end budget, not the check alone (review of #173, finding 5): an answer with no repair leaves the check its
        // whole timeout inside the deadline; after a repair the service gives it what is left (ChatAnswerServiceTest)
        assertThat(bound.getChat().getModelTimeout().plus(check.getTimeout()).plus(ChatAnswerService.CHECK_MARGIN))
                .isLessThanOrEqualTo(bound.getChat().getAnswerDeadline());
        // a one-line verdict at effort low, with room for its thinking, and its worst case still inside the ruling's +$0.003:
        // priced on the largest request the code can build from the shipped configuration, never on a typed-in size
        assertThat(check.getEffort()).isEqualTo("low");
        assertThat(check.getMaxOutputTokens()).isEqualTo(4096);
        int worstIn = worstCaseCheckInputTokens(bound.getChat().getContextMaxTokens(), bound.getChat().getMaxAnswerChars(),
                check.getMaxOutputTokens());
        assertThat(new AiCostCalculator(bound).llmCost("anthropic", check.getModel(), worstIn, check.getMaxOutputTokens()).orElseThrow())
                .as("the check's worst case: " + worstIn + " in, " + check.getMaxOutputTokens() + " out")
                .isLessThan(PER_ANSWER_CEILING);
    }

    /** PO ruling P15: the model check adds at most about +$0.003 to an answer. */
    static final BigDecimal PER_ANSWER_CEILING = new BigDecimal("0.003");

    /**
     * Tokens per character assumed for the check's input: one, the figure {@link ChatContext#estimateTokens} gives every
     * non-ASCII character, and so an over-estimate for English (about a quarter) and, by that method's own reading, for
     * Bangla. Stated, not measured: the one live run (P16(a)) logs the provider's real count.
     */
    static final int TOKENS_PER_CHAR = 1;

    /**
     * Review of #173 (third review), findings 1 and 12: the largest input the check can be sent, built by the code that
     * builds it ({@link ChatAnswerCheck#request}) from what is configured, not from a typed-in size: the fixed RULES, the
     * prescription's written lines at the context's own cap ({@code context-max-tokens}, in {@link ChatContext#estimateTokens}
     * units, where one Bangla character is one token), and an answer at {@code max-answer-chars}, all in Bangla, at
     * {@link #TOKENS_PER_CHAR}. The context cap bounds the written lines only while lab readings are what it drops: a
     * prescription block over the cap on its own is kept whole (ChatContextBuilder), and is not bounded here.
     */
    static int worstCaseCheckInputTokens(int contextMaxTokens, int maxAnswerChars, int maxOut) {
        String lines = "ক".repeat(contextMaxTokens);
        assertThat(ChatContext.estimateTokens(lines)).as("the written lines fill the context cap exactly").isEqualTo(contextMaxTokens);
        String answer = "ক".repeat(maxAnswerChars);
        LlmRequest largest = ChatAnswerCheck.request(List.of(lines), answer, maxOut);
        assertThat(largest.userPrompt()).as("the largest request carries the lines and the answer").contains(lines).contains(answer);
        assertThat(largest.systemPrompt()).as("and the fixed rules").isNotBlank();
        return (largest.systemPrompt().length() + largest.userPrompt().length()) * TOKENS_PER_CHAR;
    }

    /**
     * And the derivation can fail: the same check priced on a context cap of 8,000 is over the ruling's ceiling, so the
     * shipped figure is green because of the shipped configuration, not whatever the input is.
     */
    @Test
    void theCheckWorstCaseFollowsTheConfiguredSizes() {
        BaymaxProperties props = new BaymaxProperties();
        BaymaxProperties.Price haiku = new BaymaxProperties.Price();
        haiku.setInput(new BigDecimal("0.10"));
        haiku.setOutput(new BigDecimal("0.50"));
        props.getLlm().getPrices().put("anthropic", new java.util.LinkedHashMap<>(java.util.Map.of("claude-haiku-5-5", haiku)));
        AiCostCalculator calc = new AiCostCalculator(props);
        int shipped = worstCaseCheckInputTokens(6000, 1200, 4096);
        int wider = worstCaseCheckInputTokens(8000, 1200, 4096);
        assertThat(wider - shipped).isEqualTo(2000);
        assertThat(calc.llmCost("anthropic", "claude-haiku-5-5", shipped, 4096).orElseThrow()).isLessThan(PER_ANSWER_CEILING);
        assertThat(calc.llmCost("anthropic", "claude-haiku-5-5", wider, 4096).orElseThrow()).isGreaterThan(PER_ANSWER_CEILING);
    }

    /**
     * Review of #173, findings 13 and 18: with answers on and the check on, no check client (no key for its provider, or a
     * blank model, which a present-but-blank BAYMAX_CHAT_CHECK_MODEL gives) stops startup and says why, rather than refusing
     * every model answer (P16(c)) as check_no_client with nothing on the box to say so.
     */
    @Test
    void answersOnWithNoCheckClientStopsStartup() {
        runner().withPropertyValues("baymax.chat.enabled=true", "baymax.chat.check.model=claude-haiku-5-5").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("no chat check client");
        });
        runner().withPropertyValues("baymax.chat.enabled=true", "baymax.chat.check.model=", "llm.anthropic.api-key=test-key").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("no chat check client");
        });
        runner().withPropertyValues("baymax.chat.enabled=true", "baymax.chat.check.model=claude-haiku-5-5", "llm.anthropic.api-key=test-key")
                .run(context -> assertThat(context).hasNotFailed());
        runner().withPropertyValues("baymax.chat.enabled=true", "baymax.chat.check.enabled=false", "baymax.chat.check.model=")
                .run(context -> assertThat(context).hasNotFailed());
        runner().withPropertyValues("baymax.chat.enabled=false", "baymax.chat.check.model=claude-haiku-5-5")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /** Review of #173, finding 5: a check timeout that one model call leaves no room for inside the deadline stops startup. */
    @Test
    void aCheckTimeoutPastTheDeadlineStopsStartup() {
        runner().withPropertyValues("baymax.chat.check.model=claude-haiku-5-5", "baymax.chat.model-timeout=PT20S",
                "baymax.chat.check.timeout=PT25S", "baymax.chat.answer-deadline=PT45S").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("baymax.chat.answer-deadline");
        });
        runner().withPropertyValues("baymax.chat.check.model=claude-haiku-5-5", "baymax.chat.model-timeout=PT20S",
                "baymax.chat.check.timeout=PT8S", "baymax.chat.answer-deadline=PT45S").run(context -> assertThat(context).hasNotFailed());
    }

    /** Review of #173, findings 7 and 10: the check's client carries the check's own effort, never the chat's. */
    @Test
    void theCheckClientCarriesItsOwnEffort() {
        runner().withPropertyValues("baymax.chat.check.model=claude-haiku-5-5", "baymax.chat.effort=high", "llm.anthropic.api-key=test-key")
                .run(context -> {
                    BaymaxModelClient bean = context.getBean(ChatCheckModelConfiguration.CHECK_CLIENT, BaymaxModelClient.class);
                    assertThat(((LlmProperties.Anthropic) ReflectionTestUtils.getField(bean.delegate(), "cfg")).getEffort()).isEqualTo("low");
                });
        runner().withPropertyValues("baymax.chat.check.model=claude-haiku-5-5", "baymax.chat.check.effort=medium",
                "llm.anthropic.api-key=test-key").run(context -> {
                    BaymaxModelClient bean = context.getBean(ChatCheckModelConfiguration.CHECK_CLIENT, BaymaxModelClient.class);
                    assertThat(((LlmProperties.Anthropic) ReflectionTestUtils.getField(bean.delegate(), "cfg")).getEffort()).isEqualTo("medium");
                });
    }

    /** PO ruling P15: the check's client follows its own property and key, as the chat's does; no key, no client. */
    @Test
    void theCheckClientFollowsItsModelProperty() {
        runner().withPropertyValues("baymax.chat.check.model=claude-haiku-5-5", "llm.anthropic.api-key=test-key").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(configuredModel(context.getBean(ChatCheckModelConfiguration.CHECK_CLIENT, BaymaxModelClient.class)))
                    .isEqualTo("claude-haiku-5-5");
        });
        runner().withPropertyValues("baymax.chat.check.model=claude-haiku-5-5").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ChatCheckModelConfiguration.CHECK_CLIENT);
        });
        runner().withPropertyValues("baymax.chat.check.model=", "llm.anthropic.api-key=test-key").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ChatCheckModelConfiguration.CHECK_CLIENT);
        });
    }

    /** An unpriced check model stops startup while the check is on, and not once it is switched off. */
    @Test
    void anUnpricedCheckModelStopsStartup() {
        runner().withPropertyValues("baymax.chat.check.model=claude-unpriced-9").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("baymax.chat.check.model");
        });
        runner().withPropertyValues("baymax.chat.check.model=claude-unpriced-9", "baymax.chat.check.enabled=false")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void aCapBelowTheAppendixStopsStartup() {
        runner().withPropertyValues("baymax.chat.max-answer-chars=300").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("baymax.chat.max-answer-chars");
        });
        runner().withPropertyValues("baymax.chat.daily-cap=0").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("baymax.chat.daily-cap");
        });
        runner().withPropertyValues("baymax.chat.model=claude-unpriced-9", "llm.anthropic.api-key=test-key").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("baymax.chat.model");
        });
        runner().run(context -> assertThat(context).hasNotFailed());
    }

    /**
     * Review of #170, findings 5 and 16: every label key ({@link ChatLabels#KEYS}) is read in both languages at boot, whether
     * answers are on or off, so a renamed key stops startup and CI before a family asks. The key is missing in English only,
     * so a Bangla-only check, or one that read only the ask's own language, would start; the failure names the key.
     */
    @Test
    void aLabelKeyMissingInEnglishStopsStartupAndNamesTheKey() {
        UiChatCopyAdapter real = new UiChatCopyAdapter(new UiCopy(), new Copy());
        ChatCopyPort missing = (lang, key, args) -> {
            if ("en".equals(lang) && "value.chip.unknown".equals(key)) {
                throw new IllegalArgumentException("not in the bundle");
            }
            return real.line(lang, key, args);
        };
        assertThat(ChatLabels.KEYS).contains("value.chip.unknown");
        new ApplicationContextRunner()
                .withUserConfiguration(Props.class, ChatModelConfiguration.class, ChatPropertiesCheck.class, AiCostCalculator.class)
                .withBean(ChatCopyPort.class, () -> missing)
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable refused = context.getStartupFailure();
                    while (refused != null && !(refused instanceof IllegalStateException)) {
                        refused = refused.getCause();
                    }
                    assertThat(refused).as("the check's own refusal in " + context.getStartupFailure()).isNotNull()
                            .hasMessageContaining("en:value.chip.unknown");
                });
    }

    /**
     * PO ruling P16(d): a model-check refusal reads chat.check.refused per refusal, so a bundle without it in either language
     * stops startup and names the key, rather than turning the first refusal into a model error. Both languages, each on
     * its own (review of #173, third review, finding 2).
     */
    @Test
    void theCheckRefusalLineMissingInEitherLanguageStopsStartupAndNamesTheKey() {
        for (String lang : List.of("bn", "en")) {
            assertStartupRefused(stub(lang, "chat.check.refused", null), lang + ":chat.check.refused");
        }
    }

    /**
     * Review of #173 (third review), finding 2: a fixed body is sent with the appendix after it, so the length cap is
     * asserted at startup on the body plus the longest appendix, in each language, for the check's refusal and the code
     * checks' fixed reply alike. A 1,100-character line stops startup and names the key; the shipped lines start.
     */
    @Test
    void aFixedBodyThatCannotFitWithItsAppendixStopsStartupAndNamesTheKey() {
        String longLine = "ক".repeat(1100);
        for (String lang : List.of("bn", "en")) {
            for (String key : List.of("chat.check.refused", "chat.ask.failed")) {
                assertStartupRefused(stub(lang, key, longLine), lang + ":" + key);
            }
        }
        runner().run(context -> assertThat(context).hasNotFailed());
    }

    /** The real bundles, with one key in one language missing ({@code line == null}) or replaced by {@code line}. */
    private static ChatCopyPort stub(String lang, String key, String line) {
        UiChatCopyAdapter real = new UiChatCopyAdapter(new UiCopy(), new Copy());
        return (l, k, args) -> {
            if (lang.equals(l) && key.equals(k)) {
                if (line == null) {
                    throw new IllegalArgumentException("not in the bundle");
                }
                return line;
            }
            return real.line(l, k, args);
        };
    }

    private static void assertStartupRefused(ChatCopyPort copy, String naming) {
        new ApplicationContextRunner()
                .withUserConfiguration(Props.class, ChatModelConfiguration.class, ChatPropertiesCheck.class, AiCostCalculator.class)
                .withBean(ChatCopyPort.class, () -> copy)
                .run(context -> {
                    assertThat(context).as(naming).hasFailed();
                    Throwable refused = context.getStartupFailure();
                    while (refused != null && !(refused instanceof IllegalStateException)) {
                        refused = refused.getCause();
                    }
                    assertThat(refused).as("the check's own refusal in " + context.getStartupFailure()).isNotNull()
                            .hasMessageContaining(naming);
                });
    }

    /** What the web composer waits for Java's answer (medioo-web ask-limits.ts), read from the shipped source. */
    static final Path COMPOSER = Path.of("../medioo-web/src/components/Composer/ask-limits.ts");

    /**
     * Review of #166, finding 4: the web gives up on an answer at its own timeout, so an answer deadline the web would cut
     * off (raised on the box past the web's timeout less the store's grace and the network's margin) stops startup, and the
     * web's timeout this check holds the deadline to is the one the composer ships.
     */
    @Test
    void anAnswerDeadlineTheWebWouldCutOffStopsStartup() throws Exception {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("export const ASK_TIMEOUT_MS = ([0-9_]+);")
                .matcher(Files.readString(COMPOSER));
        assertThat(m.find()).as("ASK_TIMEOUT_MS in " + COMPOSER).isTrue();
        assertThat(java.time.Duration.ofMillis(Long.parseLong(m.group(1).replace("_", ""))))
                .as("the composer's timeout is the one this check holds the deadline to").isEqualTo(ChatPropertiesCheck.WEB_ASK_TIMEOUT);

        runner().withPropertyValues("baymax.chat.answer-deadline=PT46S").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("baymax.chat.answer-deadline");
        });
        runner().withPropertyValues("baymax.chat.answer-deadline=PT45S").run(context -> assertThat(context).hasNotFailed());
    }
}
