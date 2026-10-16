package com.elioo.baymax.extraction.application.service;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.domain.ExtractionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule that protects a family from a machine's guess: we transcribe what the page says and nothing else.
 *
 * <p>The case that motivates it is real and in the corpus: a prescription carrying a Parkinson's diagnosis,
 * paracetamol and insulin among the medicines, and a CBC among the tests. Three facts on one page. A model
 * that reasons will happily tie them together — diabetes from the insulin, pain from the paracetamol, an
 * infection from the blood count — and every one of those links would be invented. None of them is written
 * down, so none of them may appear.</p>
 *
 * <p>These tests pin the reading of a reply, and the instructions that produce it. What the model actually
 * does with a real page is measured by the evaluation run, not here.</p>
 */
class TranscriptionNotInferenceTest {

    private final ExtractionJsonReader reader = new ExtractionJsonReader(new ObjectMapper());

    /** A page with a diagnosis, two unrelated medicines and a test: all transcribed, nothing joined up. */
    private static final String PARKINSONS_PAGE = """
            {
              "document_type": "prescription",
              "document_date": "2026-08-06",
              "values": [],
              "medicines": [
                {"name": "Tab. Napa", "dose_text": "500 mg", "frequency_text": "1+0+1",
                 "timing_text": "খাওয়ার পর", "duration_text": "৭ দিন",
                 "source_span": {"page": 1, "start": 40, "end": 60}},
                {"name": "Inj. Insulin", "dose_text": "10 unit", "route": "S/C",
                 "frequency_text": "0+0+1", "timing_text": "রাত", "duration_text": "চলবে",
                 "source_span": {"page": 1, "start": 61, "end": 85}}
              ],
              "follow_up": [
                {"instruction": "৩ মাস পর আসবেন", "due_date": "2026-11-06",
                 "source_span": {"page": 1, "start": 120, "end": 140}}
              ],
              "clinical_context": {
                "chief_complaint": [
                  {"text": "Tremor of both hands", "duration": "2 years",
                   "source_span": {"page": 1, "start": 1, "end": 20}}
                ],
                "history": [],
                "examination": [],
                "diagnosis": [
                  {"text": "Parkinson's disease", "source_span": {"page": 1, "start": 21, "end": 39}}
                ],
                "investigations_advised": [
                  {"text": "CBC", "source_span": {"page": 1, "start": 100, "end": 103}}
                ],
                "advice": [
                  {"text": "নিয়মিত ঔষধ খাবেন", "source_span": {"page": 1, "start": 104, "end": 119}}
                ],
                "referral": null
              },
              "confidence": {"overall": 0.9, "values": 1.0, "medicines": 0.9,
                             "follow_up": 0.9, "clinical_context": 0.88}
            }
            """;

    /** The same page as above, from a doctor who wrote no diagnosis line. Everything else is unchanged. */
    private static final String NO_DIAGNOSIS_PAGE = """
            {
              "document_type": "prescription",
              "document_date": "2026-08-06",
              "values": [],
              "medicines": [
                {"name": "Tab. Napa", "dose_text": "500 mg", "frequency_text": "1+0+1",
                 "timing_text": "খাওয়ার পর", "duration_text": "৭ দিন",
                 "source_span": {"page": 1, "start": 40, "end": 60}},
                {"name": "Inj. Insulin", "dose_text": "10 unit", "route": "S/C",
                 "frequency_text": "0+0+1", "timing_text": "রাত", "duration_text": "চলবে",
                 "source_span": {"page": 1, "start": 61, "end": 85}}
              ],
              "follow_up": [],
              "clinical_context": {
                "chief_complaint": [
                  {"text": "Tremor of both hands", "duration": "2 years",
                   "source_span": {"page": 1, "start": 1, "end": 20}}
                ],
                "history": [],
                "examination": [],
                "diagnosis": [],
                "investigations_advised": [
                  {"text": "CBC", "source_span": {"page": 1, "start": 100, "end": 103}}
                ],
                "advice": [],
                "referral": null
              },
              "confidence": {"overall": 0.9, "values": 1.0, "medicines": 0.9,
                             "follow_up": 1.0, "clinical_context": 0.85}
            }
            """;

    @Test
    void allThreeFactsAreTranscribedAndNoneIsLinkedToAnother() {
        ExtractionResult result = reader.read(PARKINSONS_PAGE);
        var context = result.clinicalContextOrEmpty();

        // 1. the diagnosis that IS written is kept, exactly once and exactly as written
        assertThat(context.or(context.diagnosis())).hasSize(1);
        assertThat(context.or(context.diagnosis()).get(0).text()).isEqualTo("Parkinson's disease");

        // 2. both medicines survive, with nothing added about why they were given
        assertThat(result.medicinesOrEmpty()).extracting(ExtractionResult.Medicine::name)
                .containsExactly("Tab. Napa", "Inj. Insulin");

        // 3. the test is recorded as an instruction, not as evidence of anything
        assertThat(context.or(context.investigationsAdvised())).hasSize(1);
        assertThat(context.or(context.investigationsAdvised()).get(0).text()).isEqualTo("CBC");

        // and nothing invented a fourth fact joining them: no diabetes from the insulin, no fever from the
        // paracetamol, no infection from the blood count
        String everything = (context.or(context.diagnosis()) + context.or(context.history()).toString()
                + context.or(context.examination())).toLowerCase();
        assertThat(everything).doesNotContain("diabetes").doesNotContain("infection").doesNotContain("fever");
        assertThat(context.or(context.history())).isEmpty();
        assertThat(context.or(context.examination())).isEmpty();
    }

    @Test
    void medicineTextStaysVerbatimIncludingBanglaAndBrandNames() {
        ExtractionResult result = reader.read(PARKINSONS_PAGE);
        ExtractionResult.Medicine napa = result.medicinesOrEmpty().get(0);
        ExtractionResult.Medicine insulin = result.medicinesOrEmpty().get(1);

        assertThat(napa.name()).isEqualTo("Tab. Napa");          // not "Paracetamol"
        assertThat(napa.frequencyText()).isEqualTo("1+0+1");     // not "twice daily"
        assertThat(napa.timingText()).isEqualTo("খাওয়ার পর");      // Bangla stays Bangla
        assertThat(napa.durationText()).isEqualTo("৭ দিন");
        assertThat(insulin.route()).isEqualTo("S/C");
    }

    @Test
    void aRelativeFollowUpResolvesToAnAbsoluteDateWhileTheInstructionStaysVerbatim() {
        ExtractionResult result = reader.read(PARKINSONS_PAGE);
        ExtractionResult.FollowUp followUp = result.followUpOrEmpty().get(0);

        assertThat(followUp.instruction()).isEqualTo("৩ মাস পর আসবেন");
        assertThat(followUp.dueDate()).isEqualTo("2026-11-06");  // three months after document_date
    }

    @Test
    void aPageWithNoDiagnosisLineYieldsAnEmptyDiagnosis() {
        ExtractionResult result = reader.read(NO_DIAGNOSIS_PAGE);
        var context = result.clinicalContextOrEmpty();

        // no diagnosis is written, so none is reported — not one worked out from insulin or from the CBC
        assertThat(context.or(context.diagnosis())).isEmpty();
        assertThat(result.medicinesOrEmpty()).hasSize(2);
        assertThat(context.or(context.investigationsAdvised())).hasSize(1);
        assertThat(context.chiefComplaintOrEmpty()).hasSize(1);
    }

    @Test
    void aTruncatedLineIsKeptTruncated() {
        String truncated = PARKINSONS_PAGE.replace("Parkinson's disease", "Amenorrhoea due to");

        ExtractionResult result = reader.read(truncated);
        var context = result.clinicalContextOrEmpty();

        assertThat(context.or(context.diagnosis()).get(0).text()).isEqualTo("Amenorrhoea due to");
    }

    @Test
    void theInstructionsThemselvesForbidInference() {
        // the prompt is where this rule is actually enforced at run time, so it must say so plainly
        String prompt = new ExtractionPromptBuilder(new BaymaxProperties())
                .userPrompt(List.of(new com.elioo.baymax.extraction.domain.PageOcr(
                        1, "text", List.of(), "aGk=", 0.9)), false);

        assertThat(prompt).contains("TRANSCRIBE, DO NOT INFER");
        assertThat(prompt).contains("no diagnosis line, diagnosis is an empty list");
        assertThat(prompt).contains("Do not connect items");
        assertThat(prompt).contains("Amenorrhoea due to");
        assertThat(prompt).contains("Bangla stays Bangla");
        assertThat(prompt).contains("no normalisation");
    }

    /**
     * MEASURED DEFECT, not a hypothetical. lab2 is a urine report with 28 findings; one run returned 14, and
     * every one of the 14 it dropped read "Nil" — Amorphous Phosphate, Calcium-Oxalate, Candida, Granular
     * Cast, RBC Cast, Spermatozoa, Trichomonas, Triple-phosphate, Urates, Uric acid, WBC Cast and the rest.
     * The model was deciding that negatives were not worth listing.
     *
     * <p>A negative result is a result. "Nil" means the lab looked for this and did not find it, which is
     * clinically different from the test not having been done — and a family comparing our summary against
     * their own page would find half the rows missing with nothing to say why.</p>
     */
    @Test
    void theInstructionsRequireNegativeAndNormalRowsToBeListed() {
        String prompt = new ExtractionPromptBuilder(new BaymaxProperties())
                .userPrompt(List.of(new com.elioo.baymax.extraction.domain.PageOcr(
                        1, "text", List.of(), "aGk=", 0.9)), false);

        assertThat(prompt).contains("EVERY ROW THAT HAS A PRINTED RESULT IS LISTED");
        assertThat(prompt).contains("Nil");
        assertThat(prompt).contains("Negative");
        assertThat(prompt)
                .as("the instruction must say what a blank row is, or it reads as 'invent a row for everything'")
                .contains("result column BLANK is skipped");
    }

    @Test
    void clinicalContextCountsTowardsTheItemTotalSoDropsAreVisible() {
        ExtractionResult result = reader.read(PARKINSONS_PAGE);

        // 2 medicines + 1 follow-up + 4 context lines (complaint, diagnosis, investigation, advice)
        assertThat(result.clinicalContextOrEmpty().itemCount()).isEqualTo(4);
        assertThat(result.itemCount()).isEqualTo(7);
    }
}
