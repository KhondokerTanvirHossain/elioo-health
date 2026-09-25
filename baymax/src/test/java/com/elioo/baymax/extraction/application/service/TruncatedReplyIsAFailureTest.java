package com.elioo.baymax.extraction.application.service;

import com.elioo.healthcare.llm.model.LlmResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A reply cut off at the output ceiling is a failure, never a partial success.
 *
 * <p>Nothing in the pipeline reads {@code stop_reason} today. If a long report ever hit the ceiling, the JSON
 * would be truncated mid-array, the repair pass would salvage whatever parsed, and the document would be
 * stored as a normal success holding a fraction of the page — with a confidence score describing the part
 * that survived. The family would be shown a short report and told nothing was missing.</p>
 *
 * <p><b>This was investigated as the cause of batch 2's run-to-run variance and ruled out.</b> lab2 read 29
 * values in one run (4816 output tokens) and 14 in the next (2524), both far below the 8192 ceiling, and the
 * 14-value reply carried a complete {@code confidence} and {@code clinical_context} — fields that come last
 * and could not survive truncation. The variance is the model reading differently, not the ceiling.</p>
 *
 * <p>The check is here anyway, because the failure mode is silent and the reason it has never fired is that
 * no page has been long enough yet. A guard that only exists once the incident happens is a guard written
 * too late.</p>
 */
class TruncatedReplyIsAFailureTest {

    /** A reply that parses perfectly well but was cut off: the dangerous case, because nothing looks wrong. */
    private static LlmResponse reply(String content, String stopReason) {
        return new LlmResponse(content, stopReason, null, "claude-sonnet-5", null);
    }

    private static final String VALID_JSON = """
            {"document_type":"lab_report","confidence":{"overall":0.9},
             "values":[{"name":"Haemoglobin","value":"12.9","source_span":{"page":1,"start":0,"end":11}}],
             "medicines":[],"follow_up":[],
             "clinical_context":{"chief_complaint":[],"history":[],"examination":[],"diagnosis":[],
               "investigations_advised":[],"advice":[],"referral":null}}
            """;

    @Test
    void aReplyStoppedAtTheTokenCeilingIsRefusedEvenWhenItParses() {
        assertThatThrownBy(() -> DocumentExtractionService.refuseTruncated(reply(VALID_JSON, "max_tokens")))
                .as("a truncated reply holds part of the page; storing it loses the rest silently")
                .isInstanceOf(ExtractionJsonReader.InvalidExtractionException.class)
                .hasMessageContaining("max_tokens");
    }

    /** The provider-neutral spellings. An OpenAI-compatible API says "length". */
    @Test
    void everySpellingOfTheCeilingIsRefused() {
        for (String reason : new String[]{"max_tokens", "MAX_TOKENS", "length", "max_output_tokens"}) {
            assertThatThrownBy(() -> DocumentExtractionService.refuseTruncated(reply(VALID_JSON, reason)))
                    .as("stop_reason=%s means the reply was cut off", reason)
                    .isInstanceOf(ExtractionJsonReader.InvalidExtractionException.class);
        }
    }

    /** The other direction: a normal reply must pass untouched, or every document fails. */
    @Test
    void aCompleteReplyIsNotRefused() {
        for (String reason : new String[]{"end_turn", "stop", "stop_sequence", null}) {
            assertThat(DocumentExtractionService.refuseTruncated(reply(VALID_JSON, reason)))
                    .as("stop_reason=%s is a complete reply", reason)
                    .isSameAs(VALID_JSON);
        }
    }

    /**
     * A refusal must be distinguishable from a malformed reply in the log, because the fixes differ: a
     * truncated reply means raise the ceiling or shorten the prompt, a malformed one means the model is
     * wrong.
     */
    @Test
    void theRefusalNamesTheCeilingRatherThanBlamingTheJson() {
        assertThatThrownBy(() -> DocumentExtractionService.refuseTruncated(reply(VALID_JSON, "max_tokens")))
                .hasMessageContaining("output limit")
                .hasMessageNotContainingAny("did not match the required shape");
    }
}
