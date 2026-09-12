package com.elioo.healthcare.medicalreport.application.service;

import com.elioo.healthcare.medicalreport.application.port.out.ClinicalInsightPort;
import com.elioo.healthcare.medicalreport.application.port.out.MedicalClassificationPort;
import com.elioo.healthcare.medicalreport.application.port.out.MedicalReportPersistencePort;
import com.elioo.healthcare.medicalreport.application.port.out.MedicalReportPersistencePort.ErrorRecord;
import com.elioo.healthcare.medicalreport.application.port.out.MedicalReportPersistencePort.ProcessRecord;
import com.elioo.healthcare.medicalreport.application.port.out.MedicalReportPersistencePort.ResultRecord;
import com.elioo.healthcare.medicalreport.application.port.out.MedicalReportPersistencePort.StageRecord;
import com.elioo.healthcare.medicalreport.application.port.out.OcrPort;
import com.elioo.healthcare.medicalreport.application.port.out.TranslationPort;
import com.elioo.healthcare.medicalreport.domain.MasterProcessingRequest;
import com.elioo.healthcare.medicalreport.domain.MasterProcessingResponse;
import com.elioo.healthcare.medicalreport.domain.ProcessingStage;
import com.elioo.healthcare.medicalreport.domain.ProcessingStatus;
import com.elioo.healthcare.medicalreport.domain.TestResult;
import com.elioo.healthcare.medicalreport.domain.TestStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Single-image pipeline with every port mocked; persistence records are minimal stand-ins. */
class MedicalReportOrchestrationServiceTest {

    private static final String BANGLA_TEXT = "হিমোগ্লোবিন ১২.৫ g/dL";
    private static final String ENGLISH_TEXT = "Hemoglobin 12.5 g/dL";

    private final OcrPort ocrPort = mock(OcrPort.class);
    private final TranslationPort translationPort = mock(TranslationPort.class);
    private final MedicalClassificationPort classificationPort = mock(MedicalClassificationPort.class);
    private final ClinicalInsightPort clinicalInsightPort = mock(ClinicalInsightPort.class);
    private final MedicalReportPersistencePort persistencePort = mock(MedicalReportPersistencePort.class);

    private MedicalReportOrchestrationService service;

    @BeforeEach
    void setUp() {
        service = new MedicalReportOrchestrationService(ocrPort, translationPort, classificationPort,
                clinicalInsightPort, persistencePort, Schedulers.immediate(), new ObjectMapper());

        ProcessRecord process = new ProcessRecord("RPT-T", "P1", ProcessingStatus.IN_PROGRESS, null, null, null,
                null, 0, 0, 10, null, null, null, null);
        StageRecord stage = new StageRecord("S1", "RPT-T", ProcessingStage.OCR_PROCESSING, "PENDING", null, null, null, null, null);
        ResultRecord result = new ResultRecord("R1", "RPT-T", "OCR", "{}", null, null, null, null, null, null, null, null, null, null);
        ErrorRecord errorRecord = new ErrorRecord("E1", "RPT-T", null, ProcessingStage.OCR_PROCESSING, "X", "x", false, null, "ERROR");

        when(persistencePort.createProcess(any())).thenReturn(Mono.just(process));
        when(persistencePort.updateProcessStatus(anyString(), any())).thenReturn(Mono.just(process));
        when(persistencePort.createStage(anyString(), any())).thenReturn(Mono.just(stage));
        when(persistencePort.startStage(anyString())).thenReturn(Mono.just(stage));
        when(persistencePort.completeStage(anyString(), any(), any())).thenReturn(Mono.just(stage));
        when(persistencePort.failStage(anyString(), any(), anyBoolean())).thenReturn(Mono.just(stage));
        when(persistencePort.saveResult(anyString(), anyString(), any(), any())).thenReturn(Mono.just(result));
        when(persistencePort.saveResult(anyString(), anyString(), any(), any(), anyMap())).thenReturn(Mono.just(result));
        when(persistencePort.recordError(anyString(), any(ProcessingStage.class), any(Throwable.class))).thenReturn(Mono.just(errorRecord));
        when(persistencePort.completeProcess(anyString(), any())).thenReturn(Mono.just(process));
        when(persistencePort.failProcess(anyString(), any())).thenReturn(Mono.just(process));

        TestResult banglaRow = TestResult.builder().testName("হিমোগ্লোবিন").testValue("12.5").unit("g/dL").status(TestStatus.NORMAL).confidence(0.9).build();
        TestResult englishRow = TestResult.builder().testName("Hemoglobin").testValue("12.5").unit("g/dL").status(TestStatus.NORMAL).confidence(0.9).build();
        when(ocrPort.validateImageQuality(anyString())).thenReturn(Mono.just(new OcrPort.ImageQualityResult(true, 0.9, "ok", Map.of())));
        when(ocrPort.extractMedicalData(anyString(), anyString(), anyMap())).thenReturn(Flux.just(banglaRow));
        when(ocrPort.extractRawText(anyString(), any())).thenReturn(Mono.just(BANGLA_TEXT));

        when(translationPort.containsNonEnglish(anyString())).thenReturn(Mono.just(true));
        when(translationPort.translateMixedText(anyString())).thenReturn(Mono.just(ENGLISH_TEXT));
        when(translationPort.translateTestResults(any())).thenReturn(Flux.just(englishRow));

        MedicalClassificationPort.MedicalEntity entity = new MedicalClassificationPort.MedicalEntity(
                1, "Hemoglobin", "TEST_TREATMENT_PROCEDURE", "TEST_NAME", 0.95, List.of());
        when(classificationPort.classifyMedicalEntities(any())).thenReturn(Mono.just(
                new MedicalClassificationPort.ClassificationResult(List.of(entity), Map.of(), 0.95)));
        when(classificationPort.mapToMedicalCodes(anyString(), any())).thenReturn(Mono.just(List.of()));
        when(classificationPort.extractRelationships(any())).thenReturn(Mono.just(List.of()));
        when(classificationPort.validateClassification(any())).thenReturn(Mono.just(
                new MedicalClassificationPort.ValidationResult(true, 1.0, List.of(), List.of())));

        // insights are non-critical; keep the test focused on the OCR/translation/coding path
        when(clinicalInsightPort.generateClinicalInsights(any())).thenReturn(Mono.error(new RuntimeException("insights skipped in test")));
    }

    private MasterProcessingRequest request() {
        MasterProcessingRequest r = new MasterProcessingRequest();
        r.setImageBase64("aGVsbG8gd29ybGQ=");
        MasterProcessingRequest.PatientContext p = new MasterProcessingRequest.PatientContext();
        p.setPatientId("P1");
        p.setAge(40);
        p.setGender("FEMALE");
        r.setPatientContext(p);
        return r;
    }

    @Test
    void medicalCodesAreInferredFromTheTranslatedText() {
        service.processCompleteMedicalReport(request()).block(Duration.ofSeconds(10));

        verify(classificationPort, times(3)).mapToMedicalCodes(eq(ENGLISH_TEXT), any());
        verify(classificationPort, never()).mapToMedicalCodes(eq(BANGLA_TEXT), any());
    }

    @Test
    void singleImageResponseReturnsTranslatedTestNames() {
        MasterProcessingResponse response = service.processCompleteMedicalReport(request()).block(Duration.ofSeconds(10));

        assertThat(response).isNotNull();
        assertThat(response.getOcrResults().getExtractedData()).extracting(TestResult::getTestName)
                .containsExactly("Hemoglobin");
        assertThat(response.getOcrResults().getRawText()).isEqualTo(ENGLISH_TEXT);
    }

    @Test
    void criticalFailureIsRecordedOnceAgainstItsOwnStage() {
        when(classificationPort.classifyMedicalEntities(any())).thenReturn(Mono.error(new RuntimeException("comprehend down")));

        MasterProcessingResponse response = service.processCompleteMedicalReport(request()).block(Duration.ofSeconds(10));

        assertThat(response).isNotNull();
        assertThat(response.getProcessingStatus()).isEqualTo(ProcessingStatus.FAILED);
        verify(persistencePort, times(1)).recordError(eq("RPT-T"), eq(ProcessingStage.ENTITY_DETECTION), any(Throwable.class));
        verify(persistencePort, never()).recordError(eq("RPT-T"), eq(ProcessingStage.OCR_PROCESSING), any(Throwable.class));
    }
}
