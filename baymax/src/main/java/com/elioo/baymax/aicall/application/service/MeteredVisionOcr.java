package com.elioo.baymax.aicall.application.service;

import com.elioo.baymax.aicall.application.port.out.AiCallLogPort;
import com.elioo.baymax.aicall.domain.AiCallPurpose;
import com.elioo.baymax.aicall.domain.AiCallRecord;
import com.elioo.healthcare.gcp.vision.api.VisionService;
import com.elioo.healthcare.gcp.vision.model.VisionOcrRequest;
import com.elioo.healthcare.gcp.vision.model.VisionOcrResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The only way Baymax code runs OCR. One Vision call per image, one {@code ai_call_log} row per call
 * (purpose {@code ocr}, provider {@code gcp}, cost from {@code baymax.vision.cost-per-image}, confidence =
 * Vision's average word confidence). OCR text never reaches the log.
 *
 * <p>The Vision bean exists only when {@code gcp.enabled=true}; without it every call fails with a clear
 * error at call time rather than preventing the module from loading.</p>
 */
@Slf4j
@Component
public class MeteredVisionOcr {

    static final String PROVIDER = "gcp";
    static final String MODEL = "vision-document-text-detection";

    private final ObjectProvider<VisionService> vision;
    private final AiCallLogPort callLog;
    private final AiCostCalculator costs;

    public MeteredVisionOcr(ObjectProvider<VisionService> vision, AiCallLogPort callLog, AiCostCalculator costs) {
        this.vision = vision;
        this.callLog = callLog;
        this.costs = costs;
    }

    public Mono<VisionOcrResponse> detectDocumentText(UUID documentId, VisionOcrRequest request) {
        VisionService service = vision.getIfAvailable();
        if (service == null) {
            return Mono.error(new IllegalStateException("Vision OCR is not configured (gcp.enabled=false)"));
        }
        long started = System.currentTimeMillis();
        return service.detectDocumentText(request)
                .flatMap(response -> record(documentId, response, System.currentTimeMillis() - started)
                        .thenReturn(response))
                .doOnError(e -> log.warn("[baymax] ocr call failed documentId={} after {}ms: {}",
                        documentId, System.currentTimeMillis() - started, e.getMessage()));
    }

    private Mono<AiCallRecord> record(UUID documentId, VisionOcrResponse response, long latency) {
        BigDecimal cost = costs.visionCost().orElse(null);
        Double confidence = response.averageConfidence() > 0 ? response.averageConfidence() : null;
        AiCallRecord record = new AiCallRecord(null, documentId, AiCallPurpose.OCR, PROVIDER, MODEL,
                0, 0, cost, latency, confidence, Instant.now());
        return callLog.save(record)
                .doOnNext(saved -> log.info("[baymax] ai_call purpose=ocr provider={} cost={} {}ms confidence={} documentId={}",
                        PROVIDER, cost, latency, confidence, documentId))
                .onErrorResume(e -> {
                    log.error("[baymax] could not write ai_call_log row purpose=ocr documentId={}: {}",
                            documentId, e.getMessage(), e);
                    return Mono.just(record);
                });
    }
}
