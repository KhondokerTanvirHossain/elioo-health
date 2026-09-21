package com.elioo.baymax.outbound.application.service;

import com.elioo.baymax.extraction.domain.Document;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DR-26, with the production document's real clinical context. Prescription 08c02570 recorded "HTN (known)",
 * "HTN + DM (known)" and "Parkinson Disease", and the family received a medicine list and nothing else.
 */
class DiagnosisTranscriptionTest {

    private static DocumentFacts facts(Map<String, Object> clinicalContext) {
        Document document = Document.received(UUID.randomUUID(), UUID.randomUUID(), 1,
                Instant.parse("2026-09-21T09:00:00Z"));
        return new DocumentFacts(document, "Test Patient", List.of(), List.of(), clinicalContext, "https://x/y");
    }

    private static Map<String, Object> theProductionContext() {
        return Map.of("diagnosis", List.of(
                Map.of("text", "HTN (known)"),
                Map.of("text", "HTN + DM (known)"),
                Map.of("text", "Parkinson Disease")));
    }

    @Test
    void theDoctorsOwnDiagnosisLinesAppearVerbatim() {
        String block = DiagnosisTranscription.block("ডাক্তার লিখেছেন:", facts(theProductionContext()));

        assertThat(block).contains("HTN (known)").contains("HTN + DM (known)").contains("Parkinson Disease");
        assertThat(DiagnosisTranscription.verify(block, facts(theProductionContext()))).isEmpty();
    }

    /** The message that omits them fails the check, which is what makes the omission impossible to ship. */
    @Test
    void aBodyThatOmitsADiagnosisLineIsCaught() {
        String withoutParkinsons = "ডাক্তার লিখেছেন:\nHTN (known)\nHTN + DM (known)";

        assertThat(DiagnosisTranscription.verify(withoutParkinsons, facts(theProductionContext())))
                .as("a diagnosis the doctor wrote must not vanish from the message")
                .containsExactly("Parkinson Disease");
    }

    @Test
    void aDocumentWithNoDiagnosisAddsNothing() {
        assertThat(DiagnosisTranscription.block("ডাক্তার লিখেছেন:", facts(Map.of()))).isEmpty();
        assertThat(DiagnosisTranscription.block("ডাক্তার লিখেছেন:", facts(Map.of("diagnosis", List.of())))).isEmpty();
    }

    /** Only diagnosis: complaints, examination and history stay on the timeline and in the detail reply. */
    @Test
    void chiefComplaintsAndExaminationAreNotIncluded() {
        Map<String, Object> context = Map.of(
                "diagnosis", List.of(Map.of("text", "Parkinson Disease")),
                "chief_complaint", List.of(Map.of("text", "Hallucinations")),
                "examination", List.of(Map.of("text", "RR 66/min")));

        String block = DiagnosisTranscription.block("ডাক্তার লিখেছেন:", facts(context));

        assertThat(block).contains("Parkinson Disease");
        assertThat(block).doesNotContain("Hallucinations").doesNotContain("RR 66/min");
    }
}
