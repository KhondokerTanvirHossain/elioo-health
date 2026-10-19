package com.elioo.baymax.extraction.domain;

import com.elioo.baymax.extraction.application.service.DocumentExtractionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F5: for every prose the retake paths write (the code's own constants), no component of the family's view carries
 * it. The components are read from the record itself, so a field added later is under the check without anyone
 * remembering to add it.
 */
class DocumentViewNeverCarriesProseTest {

    static Stream<Arguments> everyRetakeProse() {
        return Stream.of(
                Arguments.of(DocumentExtractionService.REASON_NO_TEXT, "no_text"),
                Arguments.of(DocumentExtractionService.REASON_LOW_OVERALL + " (0.40)", "low_overall"),
                Arguments.of(DocumentExtractionService.REASON_SECTION_EMPTY.formatted("values", "lab_report"), "expected_section_empty:values"),
                Arguments.of(DocumentExtractionService.REASON_LOW_SECTION + " (0.55)", "low_section:values"));
    }

    @ParameterizedTest
    @MethodSource("everyRetakeProse")
    void noComponentOfARetakeViewCarriesTheProse(String prose, String code) throws Exception {
        Document d = new Document(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, null, null, null, 0.4,
                Document.Status.NEEDS_RETAKE, prose, null, null, 1, Instant.EPOCH, Instant.EPOCH, null, null, code);
        DocumentView view = DocumentView.pending(d);
        RecordComponent[] components = DocumentView.class.getRecordComponents();
        assertThat(components).as("record components").hasSizeGreaterThanOrEqualTo(14);
        for (RecordComponent c : components) {
            assertThat(String.valueOf(c.getAccessor().invoke(view))).as(c.getName()).doesNotContain(d.statusReason());
        }
        assertThat(view.reasonCode()).isEqualTo(code);
    }

    /** FAILED is not a retake: its developer prose (an exception's first line) has no code and reaches no view either. */
    @Test
    void aFailedViewCarriesNeitherProseNorACode() throws Exception {
        Document d = new Document(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, null, null, null, null,
                Document.Status.FAILED, "groq HTTP 503", null, null, 1, Instant.EPOCH, Instant.EPOCH, null, null, null);
        DocumentView view = DocumentView.pending(d);
        for (RecordComponent c : DocumentView.class.getRecordComponents()) {
            assertThat(String.valueOf(c.getAccessor().invoke(view))).as(c.getName()).doesNotContain("groq HTTP 503");
        }
        assertThat(view.reasonCode()).isNull();
    }
}
