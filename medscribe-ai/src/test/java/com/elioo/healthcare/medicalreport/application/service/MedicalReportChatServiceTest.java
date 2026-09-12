package com.elioo.healthcare.medicalreport.application.service;

import com.elioo.healthcare.medicalreport.application.port.in.MedicalReportChatUseCase.ChatRequest;
import com.elioo.healthcare.medicalreport.application.port.out.ClinicalInsightPort;
import com.elioo.healthcare.medicalreport.application.port.out.MedicalReportPersistencePort;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.NoSuchElementException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MedicalReportChatServiceTest {

    private final ClinicalInsightPort insightPort = mock(ClinicalInsightPort.class);
    private final MedicalReportPersistencePort persistencePort = mock(MedicalReportPersistencePort.class);
    private final MedicalReportChatService service = new MedicalReportChatService(insightPort, persistencePort);

    @Test
    void chatOnUnknownReportFailsWithNotFoundInsteadOfEmpty() {
        when(persistencePort.findProcessByReportId(anyString())).thenReturn(Mono.empty());
        when(persistencePort.findResultByType(anyString(), anyString())).thenReturn(Mono.empty());

        StepVerifier.create(service.sendMessage(new ChatRequest("RPT-NOPE", "hello?")))
                .expectErrorMatches(e -> e instanceof NoSuchElementException && e.getMessage().contains("RPT-NOPE"))
                .verify();

        verify(insightPort, never()).chatAboutReport(any(), any(), any(), any(), any());
    }
}
