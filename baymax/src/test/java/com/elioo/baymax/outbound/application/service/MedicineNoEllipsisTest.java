package com.elioo.baymax.outbound.application.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DR-16: a medicine reaches a family as verbatim transcription. An ellipsis is not a transcription.
 *
 * <p>On 2026-09-21 prescription 08c02570 sent "Cap. DDR · 30mg · ১+০+০ ... ১+০+১ · খাবারের আগে". The page says
 * "১+০+০ - খাবারের আগে - ৩০ দিন। তারপর ১+০+১ - খাবারের আগে - ২ সপ্তাহ". The ellipsis swallowed "৩০ দিন। তারপর"
 * — the words that make it a sequence rather than two simultaneous doses — and the durations were stranded in
 * a separate field the family never saw.
 */
class MedicineNoEllipsisTest {

    private static Map<String, Object> medicine(String... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    /** Exactly as stored on production. */
    private static Map<String, Object> theDdrLineAsItWasStored() {
        return medicine("name", "Cap. DDR", "dose_text", "30mg",
                "frequency_text", "১+০+০ ... ১+০+১", "timing_text", "খাবারের আগে",
                "duration_text", "৩০ দিন। তারপর - ২ সপ্তাহ");
    }

    @Test
    void theProductionDdrLineIsRefusedBecauseItsInstructionIsAbbreviated() {
        assertThat(MedicineTranscription.elided(List.of(theDdrLineAsItWasStored())))
                .as("an ellipsis in a dosing instruction hides the word that makes it a sequence")
                .isNotEmpty()
                .anySatisfy(found -> assertThat(found).contains("frequency_text").contains("..."));
    }

    @Test
    void everyShapeOfEllipsisIsCaught() {
        assertThat(MedicineTranscription.elided(List.of(medicine("frequency_text", "১+০+০ ... ১+০+১")))).isNotEmpty();
        assertThat(MedicineTranscription.elided(List.of(medicine("frequency_text", "১+০+০ … ১+০+১")))).isNotEmpty();
        assertThat(MedicineTranscription.elided(List.of(medicine("duration_text", "৩০ দিন...")))).isNotEmpty();
        assertThat(MedicineTranscription.elided(List.of(medicine("instruction_text", "take 1 ... then 2")))).isNotEmpty();
    }

    @Test
    void anOrdinaryMedicineLineIsUntouched() {
        assertThat(MedicineTranscription.elided(List.of(medicine(
                "name", "Tab. Rosuva", "dose_text", "5 mg",
                "frequency_text", "০+০+১", "timing_text", "খাবারের পরে")))).isEmpty();
    }

    /**
     * The remedy: a regimen that does not fit the fields carries its whole original line, and that is what the
     * family is shown — sequence, durations and all.
     */
    @Test
    void aMultiPhaseRegimenIsSentAsItsWholeOriginalLine() {
        String whole = "Cap. DDR · 30mg · ১+০+০ - খাবারের আগে - ৩০ দিন। তারপর ১+০+১ - খাবারের আগে - ২ সপ্তাহ";
        Map<String, Object> ddr = medicine("name", "Cap. DDR", "instruction_text", whole);

        String block = MedicineTranscription.block("যা লেখা আছে:", List.of(ddr));

        assertThat(block).contains(whole);
        assertThat(block).doesNotContain("...").doesNotContain("…");
        assertThat(block).as("the sequence word survives").contains("তারপর");
        assertThat(MedicineTranscription.elided(List.of(ddr))).isEmpty();
        assertThat(MedicineTranscription.verify(block, List.of(ddr)))
                .as("verified against the whole line, not the fields it could not be split into").isEmpty();
    }

    /** duration_text used to be dropped entirely: a course length the family never saw. */
    @Test
    void durationReachesTheFamily() {
        String block = MedicineTranscription.block("যা লেখা আছে:", List.of(medicine(
                "name", "Tab. Terbicon", "dose_text", "250 mg", "frequency_text", "১+০+০",
                "timing_text", "সকাল", "duration_text", "৭ দিন")));
        assertThat(block).contains("৭ দিন");
    }
}
