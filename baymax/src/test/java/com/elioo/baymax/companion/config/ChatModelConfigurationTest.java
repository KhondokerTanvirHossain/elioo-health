package com.elioo.baymax.companion.config;

import com.elioo.baymax.aicall.application.service.AiCostCalculator;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.config.BaymaxModelClient;
import com.elioo.baymax.outbound.application.service.Copy;
import com.elioo.baymax.web.adapter.in.ui.UiChatCopyAdapter;
import com.elioo.baymax.web.adapter.in.ui.UiCopy;
import com.elioo.healthcare.llm.config.LlmProperties;
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
                .withUserConfiguration(Props.class, ChatModelConfiguration.class, ChatPropertiesCheck.class, AiCostCalculator.class,
                        UiCopy.class, Copy.class, UiChatCopyAdapter.class)
                .withPropertyValues("baymax.llm.prices.anthropic.[claude-haiku-4-5].input=1.00",
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
