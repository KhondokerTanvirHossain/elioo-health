package com.elioo.healthcare.medicalreport.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessingStageTest {

    @Test
    void persistedStagesAreTheOnesThatGetAStageRow() {
        List<ProcessingStage> persisted = ProcessingStage.persistedStages();

        assertThat(persisted).containsExactly(
                ProcessingStage.IMAGE_VALIDATION,
                ProcessingStage.OCR_PROCESSING,
                ProcessingStage.TRANSLATION,
                ProcessingStage.ENTITY_DETECTION,
                ProcessingStage.ICD10_INFERENCE,
                ProcessingStage.RXNORM_INFERENCE,
                ProcessingStage.SNOMEDCT_INFERENCE,
                ProcessingStage.CLINICAL_INSIGHTS);
        // the four insight sub-steps are tracked in memory only
        assertThat(persisted).doesNotContain(ProcessingStage.PATIENT_SUMMARY, ProcessingStage.RISK_ASSESSMENT,
                ProcessingStage.RECOMMENDATIONS, ProcessingStage.EDUCATIONAL_CONTENT);
    }

    @Test
    void countPersistedIgnoresInMemorySubSteps() {
        List<ProcessingStage> completed = List.of(ProcessingStage.OCR_PROCESSING, ProcessingStage.CLINICAL_INSIGHTS,
                ProcessingStage.PATIENT_SUMMARY, ProcessingStage.RISK_ASSESSMENT, ProcessingStage.RECOMMENDATIONS);
        assertThat(ProcessingStage.countPersisted(completed)).isEqualTo(2);
        assertThat(ProcessingStage.countPersisted(null)).isZero();
    }
}
