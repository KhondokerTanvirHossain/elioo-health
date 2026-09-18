package com.elioo.baymax.extraction.application.service;

import com.elioo.baymax.aicall.application.service.MeteredLlmClient;
import com.elioo.baymax.aicall.application.service.MeteredVisionOcr;
import com.elioo.baymax.aicall.domain.AiCallPurpose;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.config.ExtractionModelConfiguration.ExtractionClients;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.ExtractionResult;
import com.elioo.baymax.extraction.domain.PageOcr;
import com.elioo.baymax.extraction.domain.VerifiedItems;
import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
import com.elioo.healthcare.gcp.vision.model.VisionOcrRequest;
import com.elioo.healthcare.llm.api.LlmClient;
import com.elioo.healthcare.llm.model.LlmImage;
import com.elioo.healthcare.llm.model.LlmRequest;
import com.elioo.healthcare.llm.model.LlmResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The core loop: OCR each page once, ask one model for structured content, verify every item against the
 * page it came from, and persist only what a family could check for themselves.
 *
 * <p>The four stages in order:</p>
 * <ol>
 *   <li><b>OCR</b> — Vision exactly once per page, keeping word geometry.</li>
 *   <li><b>Extract</b> — one structured call, OCR text plus the page images when the model takes them.</li>
 *   <li><b>Gate</b> — below the confidence thresholds the document needs a retake and nothing is written
 *       beyond the document row itself.</li>
 *   <li><b>Escalate</b> — low confidence or a critical flag asks the strong model too, and the more
 *       confident of the two answers wins.</li>
 * </ol>
 *
 * <p>No explanation, urgency or advice is produced here; that is BMX-6. An item whose source span cannot be
 * turned into a crop is dropped, counted, and never surfaced.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentExtractionService {

    private final MeteredVisionOcr ocr;
    private final MeteredLlmClient metered;
    private final ExtractionClients clients;
    private final ExtractionPromptBuilder prompts;
    private final ExtractionJsonReader reader;
    private final CropCutter cropCutter;
    private final MarkerMatcher markers;
    private final DocumentStorageUseCase storage;
    private final DocumentRecordPort records;
    private final BaymaxProperties properties;
    private final ObjectMapper mapper;
    private final Clock clock;

    /** Runs the whole pipeline for an already-stored document. Never signals an error: it records one. */
    public Mono<Document> process(Document document, List<byte[]> pages) {
        return records.update(document.withStatus(Document.Status.PROCESSING, null, clock.instant()))
                .then(runPipeline(document, pages))
                .onErrorResume(error -> {
                    log.error("[baymax] extraction failed documentId={}: {}", document.id(), error.getMessage(), error);
                    return finish(document, Document.Status.FAILED, shortReason(error), null, null, null);
                })
                .flatMap(done -> records.refreshCost(done.id()).thenReturn(done));
    }

    private Mono<Document> runPipeline(Document document, List<byte[]> pages) {
        return ocrPages(document, pages)
                .flatMap(ocrPages -> {
                    if (ocrPages.stream().noneMatch(PageOcr::hasGeometry)) {
                        return finish(document, Document.Status.NEEDS_RETAKE,
                                "no readable text found on the page", null, null, null);
                    }
                    return extractWithEscalation(document, ocrPages)
                            .flatMap(attempt -> gateAndPersist(document, ocrPages, attempt));
                });
    }

    // --- stage 1: OCR, exactly once per page -----------------------------------------------------

    private Mono<List<PageOcr>> ocrPages(Document document, List<byte[]> pages) {
        return Flux.range(0, pages.size())
                .concatMap(index -> {
                    String base64 = Base64.getEncoder().encodeToString(pages.get(index));
                    return ocr.detectDocumentText(document.id(),
                                    VisionOcrRequest.withLanguages(base64, List.of("bn", "en")))
                            .map(response -> PageOcr.from(index + 1, base64, response));
                })
                .collectList();
    }

    // --- stage 2 and 4: one call, then escalate when the answer is weak or alarming ---------------

    /** One extraction attempt: which client answered, and what it said. */
    private record Attempt(String model, String provider, ExtractionResult result) {
        double confidence() {
            return result.confidence() == null ? 0d : result.confidence().overallOrZero();
        }
    }

    private Mono<Attempt> extractWithEscalation(Document document, List<PageOcr> pages) {
        return callModel(document, pages, clients.primary(), null)
                .flatMap(cheap -> {
                    if (!shouldEscalate(cheap)) {
                        return Mono.just(cheap);
                    }
                    LlmClient strong = strongClient();
                    if (strong == null) {
                        log.info("[baymax] escalation wanted but no strong provider configured documentId={}", document.id());
                        return Mono.just(cheap);
                    }
                    String strongModel = properties.getExtract().getStrongModel();
                    log.info("[baymax] escalating documentId={} confidence={} critical={} to {}",
                            document.id(), cheap.confidence(), cheap.result().hasCriticalValue(), strongModel);
                    return callModel(document, pages, strong, strongModel)
                            .map(escalated -> escalated.confidence() >= cheap.confidence() ? escalated : cheap)
                            .onErrorResume(e -> {
                                log.warn("[baymax] escalation failed documentId={}, keeping the cheap result: {}",
                                        document.id(), e.getMessage());
                                return Mono.just(cheap);
                            });
                });
    }

    private boolean shouldEscalate(Attempt attempt) {
        return attempt.confidence() < properties.getExtract().getStrongModelThreshold()
                || attempt.result().hasCriticalValue();
    }

    private LlmClient strongClient() {
        // DR-9: the strong tier is its own Anthropic client built from baymax.extract.strong-model, not
        // the application default. It used to be "the default client, if llm.provider=anthropic", which
        // meant escalation silently never fired whenever MedScribe's default was Groq.
        return clients.strong();
    }

    /** One structured call, with a single repair retry when the reply does not match the schema. */
    private Mono<Attempt> callModel(Document document, List<PageOcr> pages, LlmClient client, String modelOverride) {
        boolean withImages = client.supportsImages() && properties.getExtract().isSendImages();
        String prompt = prompts.userPrompt(pages, withImages);
        LlmRequest request = buildRequest(prompt, pages, client, modelOverride, withImages);

        return metered.using(client)
                .invoke(AiCallPurpose.EXTRACT, document.id(), request, this::confidenceOf)
                .flatMap(response -> parse(response, client, modelOverride)
                        .onErrorResume(ExtractionJsonReader.InvalidExtractionException.class, invalid -> {
                            log.info("[baymax] repairing extraction reply documentId={}: {}",
                                    document.id(), invalid.getMessage());
                            LlmRequest repair = buildRequest(
                                    prompts.repairPrompt(prompt, invalid.getMessage()),
                                    pages, client, modelOverride, withImages);
                            return metered.using(client)
                                    .invoke(AiCallPurpose.EXTRACT, document.id(), repair, this::confidenceOf)
                                    .flatMap(second -> parse(second, client, modelOverride));
                        }));
    }

    private LlmRequest buildRequest(String prompt, List<PageOcr> pages, LlmClient client,
                                    String modelOverride, boolean withImages) {
        LlmRequest request = new LlmRequest(prompt, prompts.systemPrompt(), modelOverride,
                properties.getExtract().getMaxOutputTokens(), null, null, null, null, true);
        if (!withImages) {
            return request;
        }
        return request.withImages(pages.stream()
                .map(page -> LlmImage.jpeg(page.imageBase64()))
                .toList());
    }

    private Mono<Attempt> parse(LlmResponse response, LlmClient client, String modelOverride) {
        return Mono.fromCallable(() -> new Attempt(
                response.modelId() != null ? response.modelId() : String.valueOf(modelOverride),
                client.providerName(),
                reader.read(response.content())));
    }

    /** Read back out of the reply for the cost log; a malformed reply simply has no confidence. */
    private Double confidenceOf(LlmResponse response) {
        try {
            ExtractionResult result = reader.read(response.content());
            return result.confidence() == null ? null : result.confidence().overall();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // --- stage 3: the gate, then crops, then persistence ------------------------------------------

    private Mono<Document> gateAndPersist(Document document, List<PageOcr> pages, Attempt attempt) {
        ExtractionResult result = attempt.result();
        double overall = attempt.confidence();
        double section = result.confidence() == null ? 1d : result.confidence().lowestSection();
        var thresholds = properties.getExtract();

        if (overall < thresholds.getMinConfidenceOverall()) {
            return finish(document, Document.Status.NEEDS_RETAKE,
                    "the document was not read confidently enough (%.2f)".formatted(overall),
                    attempt, result, null);
        }
        if (section < thresholds.getMinConfidenceSection()) {
            return finish(document, Document.Status.NEEDS_RETAKE,
                    "part of the document was not read confidently enough (%.2f)".formatted(section),
                    attempt, result, null);
        }
        return verify(document, pages, result)
                .flatMap(items -> finish(document, Document.Status.DONE, null, attempt, result, items));
    }

    /**
     * Cuts a crop for every item and stores it; an item whose span cannot be located is dropped. This is
     * the rule that makes "never a number without its source" true in the data, not just in the prompt.
     */
    private Mono<VerifiedItems> verify(Document document, List<PageOcr> pages, ExtractionResult result) {
        Map<Integer, PageOcr> byPage = CropCutter.byPageNumber(pages);
        Instant observedAt = observedAt(result, document);
        List<VerifiedItems.Observation> observations = new ArrayList<>();
        List<VerifiedItems.Medication> medications = new ArrayList<>();
        List<VerifiedItems.FollowUpItem> followUps = new ArrayList<>();
        int[] droppedValues = {0};
        int[] droppedMedicines = {0};
        int[] droppedFollowUp = {0};

        Flux<Void> values = Flux.fromIterable(result.valuesOrEmpty())
                .concatMap(value -> store(document, cropCutter.cutValue(value.sourceSpan(), value.name(), value.value(), byPage), itemId("v", observations.size()))
                        .doOnNext(key -> observations.add(new VerifiedItems.Observation(
                                document.patientId(), value.name(),
                                markers.canonicalFor(value.name(), value.canonicalName()).orElse(null),
                                value.value(), value.unit(), value.refLow(), value.refHigh(), value.flag(),
                                key, observedAt)))
                        .switchIfEmpty(Mono.fromRunnable(() -> droppedValues[0]++))
                        .then());

        Flux<Void> medicines = Flux.fromIterable(result.medicinesOrEmpty())
                .concatMap(medicine -> crop(document, medicine.sourceSpan(), medicine.name(), byPage, itemId("m", medications.size()))
                        .doOnNext(key -> medications.add(new VerifiedItems.Medication(
                                document.patientId(), medicine.name(), medicine.doseText(), medicine.route(),
                                medicine.frequencyText(), medicine.timingText(), medicine.durationText(),
                                key, observedAt)))
                        .switchIfEmpty(Mono.fromRunnable(() -> droppedMedicines[0]++))
                        .then());

        Flux<Void> follow = Flux.fromIterable(result.followUpOrEmpty())
                .concatMap(item -> crop(document, item.sourceSpan(), item.instruction(), byPage, itemId("f", followUps.size()))
                        .doOnNext(key -> followUps.add(new VerifiedItems.FollowUpItem(
                                document.patientId(), item.instruction(), parseDate(item.dueDate()), key)))
                        .switchIfEmpty(Mono.fromRunnable(() -> droppedFollowUp[0]++))
                        .then());

        List<VerifiedItems.ContextLine> context = new ArrayList<>();
        int[] droppedContext = {0};
        Flux<Void> clinical = Flux.fromIterable(contextItems(result))
                .concatMap(item -> crop(document, item.span(), item.text(), byPage, itemId("c", context.size()))
                        .doOnNext(key -> context.add(new VerifiedItems.ContextLine(
                                item.section(), item.text(), item.duration(), key)))
                        .switchIfEmpty(Mono.fromRunnable(() -> droppedContext[0]++))
                        .then());

        return Flux.concat(values, medicines, follow, clinical)
                .then(Mono.fromSupplier(() -> new VerifiedItems(List.copyOf(observations),
                        List.copyOf(medications), List.copyOf(followUps), List.copyOf(context),
                        new VerifiedItems.Unverified(droppedValues[0], droppedMedicines[0],
                                droppedFollowUp[0], droppedContext[0]))));
    }

    /** One clinical line, flattened out of its section so every line is cropped the same way. */
    /** A clinical-context line flattened with its section; public for the BMX-5b replay harness. */
    public record ContextItem(String section, String text, String duration,
                               ExtractionResult.SourceSpan span) {
    }

    public static List<ContextItem> contextItems(ExtractionResult result) {
        ExtractionResult.ClinicalContext c = result.clinicalContextOrEmpty();
        List<ContextItem> items = new ArrayList<>();
        c.chiefComplaintOrEmpty().forEach(x ->
                items.add(new ContextItem("chief_complaint", x.text(), x.duration(), x.sourceSpan())));
        c.or(c.history()).forEach(x -> items.add(new ContextItem("history", x.text(), null, x.sourceSpan())));
        c.or(c.examination()).forEach(x -> items.add(new ContextItem("examination", x.text(), null, x.sourceSpan())));
        c.or(c.diagnosis()).forEach(x -> items.add(new ContextItem("diagnosis", x.text(), null, x.sourceSpan())));
        c.or(c.investigationsAdvised()).forEach(x ->
                items.add(new ContextItem("investigations_advised", x.text(), null, x.sourceSpan())));
        c.or(c.advice()).forEach(x -> items.add(new ContextItem("advice", x.text(), null, x.sourceSpan())));
        if (c.referral() != null && c.referral().text() != null && !c.referral().text().isBlank()) {
            items.add(new ContextItem("referral", c.referral().text(), null, c.referral().sourceSpan()));
        }
        return items;
    }

    private Mono<String> crop(Document document, ExtractionResult.SourceSpan span, String anchor,
                              Map<Integer, PageOcr> byPage, String itemId) {
        return store(document, cropCutter.cut(span, anchor, byPage), itemId);
    }

    private Mono<String> store(Document document, CropCutter.Cut cut, String itemId) {
        if (cut.bytes().isEmpty()) {
            // the outcome names why, never the text (BMX-5b)
            log.info("[baymax] item dropped documentId={} item={} outcome={}", document.id(), itemId, cut.outcome());
            return Mono.empty();
        }
        if (cut.outcome() == CropCutter.Outcome.RELOCATED) {
            log.debug("[baymax] item crop relocated by text documentId={} item={}", document.id(), itemId);
        }
        return storage.storeCrop(document.familyId(), document.patientId(), document.id(), itemId, cut.bytes().get())
                .map(stored -> stored.storageKey());
    }


    private static String itemId(String prefix, int index) {
        return prefix + (index + 1);
    }

    // --- finishing -------------------------------------------------------------------------------

    private Mono<Document> finish(Document document, Document.Status status, String reason,
                                  Attempt attempt, ExtractionResult result, VerifiedItems items) {
        Instant now = clock.instant();
        Document updated = new Document(
                document.id(), document.patientId(), document.familyId(),
                result == null ? null : result.documentType(),
                result == null ? null : parseDate(result.documentDate()),
                result == null ? null : result.facility(),
                result == null ? null : toJson(result),
                attempt == null ? null : attempt.confidence(),
                status, reason,
                attempt == null ? null : attempt.provider() + "/" + attempt.model(),
                document.costUsd(), document.pageCount(), document.createdAt(), now,
                items == null ? VerifiedItems.Unverified.none() : items.unverified(),
                items == null ? null : contextJson(items));

        if (items != null && items.unverified().any()) {
            log.info("[baymax] {} item(s) had no resolvable crop documentId={} values={} medicines={} followUp={}",
                    items.unverified().total(), document.id(), items.unverified().values(),
                    items.unverified().medicines(), items.unverified().followUp());
        }
        if (items == null) {
            return records.update(updated)
                    .doOnSuccess(d -> log.info("[baymax] document {} documentId={} reason={}",
                            status, d.id(), reason));
        }
        return records.saveExtraction(updated, items);
    }

    /** The verified clinical lines, grouped by section, as the JSONB the document column holds. */
    private String contextJson(VerifiedItems items) {
        if (items.clinicalContext().isEmpty()) {
            return null;
        }
        Map<String, List<Map<String, String>>> bySection = new java.util.LinkedHashMap<>();
        for (VerifiedItems.ContextLine line : items.clinicalContext()) {
            Map<String, String> entry = new java.util.LinkedHashMap<>();
            entry.put("text", line.text());
            if (line.duration() != null && !line.duration().isBlank()) {
                entry.put("duration", line.duration());
            }
            entry.put("crop_key", line.cropKey());
            bySection.computeIfAbsent(line.section(), k -> new ArrayList<>()).add(entry);
        }
        try {
            return mapper.writeValueAsString(bySection);
        } catch (Exception e) {
            log.warn("[baymax] could not serialise the clinical context: {}", e.getMessage());
            return null;
        }
    }

    private String toJson(ExtractionResult result) {
        try {
            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            log.warn("[baymax] could not serialise the extraction result: {}", e.getMessage());
            return null;
        }
    }

    /** The document's own date when it has one, otherwise now: a trend needs a point in time. */
    private Instant observedAt(ExtractionResult result, Document document) {
        LocalDate date = parseDate(result.documentDate());
        return date == null ? document.createdAt() : date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Error text for the status column: short, developer-facing, never patient content. */
    private static String shortReason(Throwable error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        String firstLine = message.lines().findFirst().orElse(message);
        return firstLine.length() > 180 ? firstLine.substring(0, 180) : firstLine;
    }
}
