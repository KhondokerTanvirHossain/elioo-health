package com.elioo.healthcare.llm.api;

import com.elioo.healthcare.llm.model.LlmRequest;
import com.elioo.healthcare.llm.model.LlmResponse;
import reactor.core.publisher.Mono;

/** One call to a text-generation model, independent of the vendor behind it. */
public interface LlmClient {

    /** Sends the request and returns the model's reply. Errors surface as {@code LlmException}. */
    Mono<LlmResponse> invoke(LlmRequest request);

    /** Short lower-case name used in logs and metadata, e.g. "groq", "anthropic", "bedrock". */
    String providerName();
}
