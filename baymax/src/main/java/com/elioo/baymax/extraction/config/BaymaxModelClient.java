package com.elioo.baymax.extraction.config;

import com.elioo.healthcare.llm.api.LlmClient;

/**
 * A Baymax-owned model client, deliberately NOT an {@link LlmClient} bean.
 *
 * <p>MedScribe's default client is registered {@code @ConditionalOnMissingBean(LlmClient.class)}. Any
 * {@code LlmClient} bean Baymax contributes therefore suppresses it, and MedScribe loses the LLM it needs —
 * which is exactly what DR-2 ("MedScribe stays as-is") forbids. Two attempts failed before this one:
 * registering them as plain {@code LlmClient} beans stopped the application starting at all
 * ("expected single matching bean but found 2"), and {@code autowireCandidate = false} excluded them from
 * {@code @Qualifier} lookup as well, so the extraction pipeline silently fell back to MedScribe's Groq
 * client and produced a table labelled haiku that was really gpt-oss-120b.
 *
 * <p>Wrapping in a distinct type solves both: these beans are invisible to {@code LlmClient} injection,
 * while the pipeline still reaches them by qualifier and unwraps.
 *
 * <p>Spring 6.2's {@code @Bean(defaultCandidate = false)} would express this directly; this project is on
 * Spring 6.1, where only {@code autowireCandidate} exists.
 */
public record BaymaxModelClient(LlmClient delegate) {

    /** @return the wrapped client, or null when the provider was absent */
    public static LlmClient unwrap(BaymaxModelClient wrapper) {
        return wrapper == null ? null : wrapper.delegate();
    }
}
