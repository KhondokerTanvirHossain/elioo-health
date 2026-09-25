package com.elioo.baymax.extraction.application.service;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.domain.ExtractionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A date the page cannot settle is marked, not silently resolved.
 *
 * <p>Batch 2's dates were right on every checkable case — and the convention was entirely the model's,
 * because the schema said only "ISO date as written" with no day-first instruction. Bangladeshi reports are
 * day-first: lab3's {@code 04/07/2024} is 4 July, and the model read it that way with nothing telling it to.
 *
 * <p><b>lab8 is the finding.</b> Every date on that page is {@code 07/11/2021} — 7 November or 11 July, and
 * nothing on the page decides. It was resolved to 7 November anyway, silently. A wrongly-placed report
 * corrupts timeline order and every trend that joins it, and nothing downstream could tell that the date was
 * a convention rather than a reading.
 *
 * <p>So the model now states day-first explicitly AND flags the cases it cannot prove. The date is still
 * filled in — a null date loses the report from the timeline entirely, which is worse — but it carries a
 * marker saying it rests on a convention.
 */
class AmbiguousDateIsMarkedTest {

    private final ExtractionJsonReader reader = new ExtractionJsonReader(new ObjectMapper());

    private static String document(String date, String uncertain) {
        return """
                {"document_type":"lab_report","document_date":%s,%s
                 "values":[],"medicines":[],"follow_up":[],
                 "clinical_context":{"chief_complaint":[],"history":[],"examination":[],"diagnosis":[],
                   "investigations_advised":[],"advice":[],"referral":null},
                 "confidence":{"overall":0.9}}
                """.formatted(date == null ? "null" : "\"" + date + "\"",
                uncertain == null ? "" : " \"document_date_uncertain\":" + uncertain + ",");
    }

    /** lab8: 07/11/2021, read day-first, and flagged because the page cannot prove the order. */
    @Test
    void anAmbiguousDateIsFilledInAndFlagged() {
        ExtractionResult result = reader.read(document("2021-11-07", "true"));

        assertThat(result.documentDate())
                .as("the day-first reading is still used — a null date loses the report from the timeline")
                .isEqualTo("2021-11-07");
        assertThat(result.documentDateUncertain())
                .as("and it is marked, so nothing downstream mistakes a convention for a reading")
                .isTrue();
    }

    /** lab3: 04/07/2024 day-first, unambiguous because a companion print date settles it. */
    @Test
    void anUnambiguousDateIsNotFlagged() {
        ExtractionResult result = reader.read(document("2024-07-04", "false"));

        assertThat(result.documentDate()).isEqualTo("2024-07-04");
        assertThat(result.documentDateUncertain()).isFalse();
    }

    /**
     * The field is new, so every document extracted before it exists has no value for it. An absent flag must
     * read as "not marked uncertain" rather than throwing or defaulting to uncertain — otherwise adding the
     * field would retroactively cast doubt on every date already stored.
     */
    @Test
    void aDocumentWithoutTheFieldIsNotUncertain() {
        ExtractionResult result = reader.read(document("2024-07-04", null));

        assertThat(result.documentDateUncertain())
                .as("absent means not flagged; the field predates nothing")
                .isNotEqualTo(Boolean.TRUE);
    }

    /** A page with no date at all stays null, and is not "uncertain" — there is nothing to be uncertain about. */
    @Test
    void aMissingDateIsNullNotUncertain() {
        ExtractionResult result = reader.read(document(null, null));

        assertThat(result.documentDate()).isNull();
        assertThat(result.documentDateUncertain()).isNotEqualTo(Boolean.TRUE);
    }

    /** The instructions must carry the rule, since that is where it is enforced at run time. */
    @Test
    void theInstructionsStateDayFirstAndAskForTheFlag() {
        String prompt = new ExtractionPromptBuilder(new BaymaxProperties())
                .userPrompt(List.of(new com.elioo.baymax.extraction.domain.PageOcr(
                        1, "text", List.of(), "aGk=", 0.9)), false);

        assertThat(prompt).as("day-first is stated, not left to the model").containsIgnoringCase("day-first");
        assertThat(prompt).as("with the worked example from the corpus").contains("04/07/2024");
        assertThat(prompt).contains("document_date_uncertain");
        assertThat(prompt)
                .as("and the instruction must say to still fill the date, or reports fall out of the timeline")
                .containsIgnoringCase("still");
    }
}
