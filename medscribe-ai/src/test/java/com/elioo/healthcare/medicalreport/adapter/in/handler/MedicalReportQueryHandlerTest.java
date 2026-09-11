package com.elioo.healthcare.medicalreport.adapter.in.handler;

import com.elioo.healthcare.medicalreport.adapter.in.router.MedicalReportQueryRouter;
import com.elioo.healthcare.medicalreport.application.port.in.MedicalReportQueryUseCase;
import com.elioo.healthcare.medicalreport.application.port.out.MedicalReportPersistencePort.ResultRecord;
import com.elioo.healthcare.medicalreport.domain.Priority;
import com.elioo.healthcare.medicalreport.domain.Severity;
import com.elioo.healthcare.medicalreport.domain.SuggestionCategory;
import com.elioo.healthcare.medicalreport.domain.TestResult;
import com.elioo.healthcare.medicalreport.dto.ClassificationResponse;
import com.elioo.healthcare.medicalreport.dto.OcrResponse;
import com.elioo.healthcare.medicalreport.dto.SuggestionsResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MedicalReportQueryHandlerTest {

    private MedicalReportQueryUseCase queryUseCase;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        queryUseCase = mock(MedicalReportQueryUseCase.class);
        MedicalReportQueryHandler handler = new MedicalReportQueryHandler(queryUseCase);
        var router = new MedicalReportQueryRouter().queryRoutes(handler);
        client = WebTestClient.bindToRouterFunction(router).build();
    }

    @Test
    void getResultsShouldReturnTypedPayloads() {
        String reportId = "RPT-123";
        LocalDateTime now = LocalDateTime.now();

        OcrResponse ocr = OcrResponse.builder()
                .reportId(reportId)
                .patientId("PAT-1")
                .extractedData(List.of(TestResult.builder().testName("Sodium").unit("mmol/L").build()))
                .rawText("raw text")
                .confidence(0.9)
                .processedAt(now)
                .build();

        ClassificationResponse classification = ClassificationResponse.builder()
                .reportId(reportId)
                .classificationResult(ClassificationResponse.ComprehendMedicalResult.builder()
                        .Entities(List.of(ClassificationResponse.MedicalEntity.builder()
                                .Id(1)
                                .Text("Blood")
                                .Score(0.95)
                                .build()))
                        .build())
                .medicalCodes(ClassificationResponse.MedicalCodes.builder()
                        .ICD10(List.of("A00"))
                        .build())
                .processedAt(now)
                .build();

        SuggestionsResponse suggestions = SuggestionsResponse.builder()
                .reportId(reportId)
                .summary("summary")
                .aiSuggestions(List.of(SuggestionsResponse.AiSuggestion.builder()
                        .category(SuggestionCategory.MEDICATION)
                        .priority(Priority.HIGH)
                        .recommendation("recommend")
                        .build()))
                .riskLevel(Severity.HIGH)
                .generatedAt(now)
                .confidenceScore(0.8)
                .build();

        ResultRecord ocrRecord = new ResultRecord(
                "OCR-ID", reportId, "OCR", "{\"foo\":\"bar\"}", ocr, null, ocr, null,
                0.9, now, 1, null, null, null
        );
        ResultRecord classificationRecord = new ResultRecord(
                "CLS-ID", reportId, "CLASSIFICATION", "{\"foo\":\"bar\"}", classification, classification, null, null,
                0.85, now, null, 1, null, null
        );
        ResultRecord suggestionsRecord = new ResultRecord(
                "SGN-ID", reportId, "CLINICAL_INSIGHTS", "{\"foo\":\"bar\"}", suggestions, null, null, suggestions,
                0.8, now, null, null, null, "HIGH"
        );

        when(queryUseCase.getOcrResults(reportId)).thenReturn(Mono.just(ocrRecord));
        when(queryUseCase.getAllResults(reportId)).thenReturn(Flux.just(ocrRecord, classificationRecord, suggestionsRecord));

        client.get()
                .uri("/api/v1/medical-report/query/results/{reportId}/ocr", reportId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.ocrResult.reportId").isEqualTo(reportId)
                .jsonPath("$.resultData.extractedData[0].testName").isEqualTo("Sodium");

        client.get()
                .uri("/api/v1/medical-report/query/results/{reportId}/all", reportId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].ocrResult.patientId").isEqualTo("PAT-1")
                .jsonPath("$[1].classificationResult.classificationResult.Entities[0].Text").isEqualTo("Blood")
                .jsonPath("$[2].suggestionsResult.riskLevel").isEqualTo("HIGH");
    }
}
