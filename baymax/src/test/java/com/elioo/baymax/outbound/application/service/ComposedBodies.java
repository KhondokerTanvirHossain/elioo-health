package com.elioo.baymax.outbound.application.service;

import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.outbound.application.port.in.DocumentUrgencyUseCase;
import com.elioo.baymax.outbound.domain.StoredUrgency;
import com.elioo.baymax.outbound.domain.UrgencyAssessment;
import org.springframework.context.ApplicationContext;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Message bodies as the composers write them, for tests that seed a delivered {@code outbound_message} (review of #135,
 * finding 7: a fixture's bubble is production text, never a hand-written one-liner). Every body comes out of the real
 * {@code messages_bn.properties} through {@link Copy}, assembled as the composer assembles it:
 * <ul>
 *   <li>an explanation or a detail: {@link ExplanationService#skeleton} over the paper's stored facts and its STORED
 *       urgency (the level and the value verdicts the writer stored), then the doctor's diagnosis block and the verbatim
 *       medicine block, exactly as {@code ExplanationService.compose} appends them after the model. The skeleton stands in
 *       for the model's phrasing: it already holds every fixed line (the NOW instruction, the red-flag sentence, the link);</li>
 *   <li>a retake and a failure notice: {@code retake} and {@code failure.document}, as {@code ExplanationService} sends
 *       them (no model);</li>
 *   <li>a nudge: {@code nudge.<rule>} and, after a newline, the {@code nudge.optout} line, as {@code NudgeComposer} joins
 *       them.</li>
 * </ul>
 * Each answer carries the template it came from, so a test can render it again and compare by field.
 */
public final class ComposedBodies {

    private ComposedBodies() {
    }

    /** One composed body and the template key it was rendered from. */
    public record Composed(String template, String body) {
    }

    public static Composed explanation(ApplicationContext context, UUID documentId) {
        return composed(context, documentId, false);
    }

    public static Composed detail(ApplicationContext context, UUID documentId) {
        return composed(context, documentId, true);
    }

    public static Composed retake(ApplicationContext context) {
        return new Composed("retake", context.getBean(Copy.class).bn("retake", Map.of()));
    }

    public static Composed failure(ApplicationContext context) {
        return new Composed("failure.document", context.getBean(Copy.class).bn("failure.document", Map.of()));
    }

    /** {@code nudge.<rule>} with these vars, then the opt-out line carrying {@code optoutLink}, as NudgeComposer joins them. */
    public static Composed nudge(ApplicationContext context, String rule, Map<String, String> vars, String optoutLink) {
        Copy copy = context.getBean(Copy.class);
        Map<String, String> all = new LinkedHashMap<>(vars);
        all.put("optout_link", optoutLink);
        return new Composed("nudge." + rule, copy.bn("nudge." + rule, all) + "\n" + copy.bn("nudge.optout", all));
    }

    private static Composed composed(ApplicationContext context, UUID documentId, boolean detail) {
        Duration wait = Duration.ofSeconds(30);
        Document document = context.getBean(DocumentRecordPort.class).find(documentId).block(wait);
        if (document == null) {
            throw new IllegalArgumentException("no document " + documentId);
        }
        StoredUrgencyService urgencies = context.getBean(StoredUrgencyService.class);
        DocumentFacts facts = urgencies.facts(document).block(wait);
        StoredUrgency stored = context.getBean(DocumentUrgencyUseCase.class).currentOrAssess(documentId).block(wait);
        if (stored == null) {
            throw new IllegalStateException("no stored urgency for " + documentId + ": a DONE paper is composed at its stored level");
        }
        UrgencyAssessment assessed = stored.asAssessment();
        Copy copy = context.getBean(Copy.class);
        String text = context.getBean(ExplanationService.class).skeleton(facts, assessed, detail);
        String diagnosis = DiagnosisTranscription.block(copy.bn("diagnosis.header", Map.of()), facts);
        String head = diagnosis.isEmpty() ? text : text + "\n\n" + diagnosis;
        List<Map<String, Object>> medicines = context.getBean(DocumentRecordPort.class).medicinesOf(documentId).collectList().block(wait);
        String block = MedicineTranscription.block(copy.bn("medicines.header", Map.of()), medicines);
        String key = detail ? "detail.header" : "explanation." + assessed.level().dbValue();
        return new Composed(key, block.isEmpty() ? head : head + "\n\n" + block);
    }
}
