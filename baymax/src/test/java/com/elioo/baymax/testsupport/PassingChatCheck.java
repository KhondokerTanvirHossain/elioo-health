package com.elioo.baymax.testsupport;

import com.elioo.baymax.extraction.config.BaymaxModelClient;
import com.elioo.healthcare.llm.api.LlmClient;
import com.elioo.healthcare.llm.model.LlmRequest;
import com.elioo.healthcare.llm.model.LlmResponse;
import com.elioo.healthcare.llm.model.TokenUsage;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stand-in for the চ্যাট's refuse-only model check (PO ruling P15) that lets every answer stand: what a context test that
 * wires a stub chat model registers as {@code ChatCheckModelConfiguration.CHECK_CLIENT}, so an answer about a medicine is
 * shown as it would be in production with a check that passes it. Without it the check, on by default, has no client and
 * refuses such an answer (fail closed). Counts the calls it was asked.
 */
public final class PassingChatCheck implements LlmClient {

    public static final String MODEL = "claude-haiku-5-5";

    public final AtomicInteger calls = new AtomicInteger();

    @Override
    public Mono<LlmResponse> invoke(LlmRequest request) {
        calls.incrementAndGet();
        return Mono.just(new LlmResponse("{\"verdict\":\"pass\"}", "end_turn", new TokenUsage(400, 8), MODEL, null, "anthropic", 2L));
    }

    @Override
    public String providerName() {
        return "anthropic";
    }

    /** This stand-in as the bean the service takes. */
    public BaymaxModelClient client() {
        return new BaymaxModelClient(this);
    }
}
