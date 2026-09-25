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
        return call(documentId, request, AiCallPurpose.OCR);
    }

    /**
     * OCR on a single crop, to confirm it shows the value before the crop may be stored (DR-12).
     *
     * <p>Logged under its own purpose rather than as {@code ocr}. Page OCR happens once per page; this
     * happens once per value whose crop could not be located in the OCR text, so blending the two would
     * leave "what does verification cost per document" unanswerable in the data.</p>
     */
    public Mono<VisionOcrResponse> readCrop(UUID documentId, VisionOcrRequest request) {
        return call(documentId, request, AiCallPurpose.CROP_VERIFY);
    }

    private Mono<VisionOcrResponse> call(UUID documentId, VisionOcrRequest request, AiCallPurpose purpose) {
        VisionService service = vision.getIfAvailable();
        if (service == null) {
            return Mono.error(new IllegalStateException("Vision OCR is not configured (gcp.enabled=false)"));
        }
        long started = System.currentTimeMillis();
        return service.detectDocumentText(request)
                .flatMap(response -> record(documentId, response, System.currentTimeMillis() - started, purpose)
                        .thenReturn(response))
                .onErrorResume(error -> recordFailure(documentId, started, error, purpose).then(Mono.error(error)));
    }

    /**
     * A failed OCR call is logged like a failed model call (BMX-2): zero tokens, no cost, real latency.
     * An unhealthy provider then shows up in the cost log instead of only in the application log.
     */
    private Mono<AiCallRecord> recordFailure(UUID documentId, long started, Throwable error, AiCallPurpose purpose) {
        long latency = System.currentTimeMillis() - started;
        log.warn("[baymax] ocr call failed documentId={} after {}ms: {}", documentId, latency, error.getMessage());
        return callLog.save(AiCallRecord.failed(documentId, purpose, PROVIDER, MODEL, latency, Instant.now()))
                .onErrorResume(writeError -> {
                    log.error("[baymax] could not write failed ocr ai_call_log row documentId={}: {}",
                            documentId, writeError.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<AiCallRecord> record(UUID documentId, VisionOcrResponse response, long latency, AiCallPurpose purpose) {
        BigDecimal cost = costs.visionCost().orElse(null);
        Double confidence = response.averageConfidence() > 0 ? response.averageConfidence() : null;
        AiCallRecord record = new AiCallRecord(null, documentId, purpose, PROVIDER, MODEL,
                0, 0, cost, latency, confidence, Instant.now());
        return callLog.save(record)
                .doOnNext(saved -> log.info("[baymax] ai_call purpose={} provider={} cost={} {}ms confidence={} documentId={}",
                        purpose.dbValue(), PROVIDER, cost, latency, confidence, documentId))
                .onErrorResume(e -> {
                    log.error("[baymax] could not write ai_call_log row purpose=ocr documentId={}: {}",
                            documentId, e.getMessage(), e);
                    return Mono.just(record);
                });
    }
}
