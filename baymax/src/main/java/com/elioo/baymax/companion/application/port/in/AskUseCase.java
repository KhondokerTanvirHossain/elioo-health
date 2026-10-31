package com.elioo.baymax.companion.application.port.in;

import com.elioo.baymax.companion.domain.AnswerKind;
import com.elioo.baymax.companion.domain.CitedMedicine;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A question typed in the web চ্যাট about one patient, answered under DR-50 (PO ruling P7): an emergency in code before any
 * model call; otherwise one Claude Sonnet answer from the patient's own stored papers, checked in code, repaired once,
 * else a fixed reply; the doctor, urgency, disclaimer and red-flag lines appended by code. A blank question, or one over
 * {@code baymax.chat.question-max-chars}, is 400; a patient this account cannot see is 404 before any turn is stored.
 *
 * @param lang {@code "bn"} or {@code "en"}; anything else is Bangla
 */
public interface AskUseCase {

    Mono<AskAnswer> ask(UUID familyId, UUID patientId, String question, String lang);

    /**
     * What the family is shown: exactly the stored turn's answer. {@code call999} is true only for an emergency read on a
     * Bangladeshi number ({@code ViewerLocale}). {@code medicines}: the prescription lines the answer quotes, each with its
     * crop, in the order quoted (review of #166, finding 5).
     */
    record AskAnswer(UUID turnId, AnswerKind kind, String question, String answer, boolean call999, Instant at,
                     List<CitedMedicine> medicines) {

        public AskAnswer {
            medicines = medicines == null ? List.of() : List.copyOf(medicines);
        }

        /** An answer quoting no prescription line. */
        public AskAnswer(UUID turnId, AnswerKind kind, String question, String answer, boolean call999, Instant at) {
            this(turnId, kind, question, answer, call999, at, List.of());
        }
    }
}
