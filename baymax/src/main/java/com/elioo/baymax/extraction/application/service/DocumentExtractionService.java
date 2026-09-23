package com.elioo.baymax.extraction.application.service;

import com.elioo.baymax.aicall.application.service.MeteredLlmClient;
import com.elioo.baymax.aicall.application.service.MeteredVisionOcr;
import com.elioo.baymax.aicall.domain.AiCallPurpose;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.config.ExtractionModelConfiguration.ExtractionClients;
import com.elioo.baymax.common.error.BaymaxException;
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
public class DocumentExtractionService implements com.elioo.baymax.extraction.application.port.in.RecropUseCase {

    private final MeteredVisionOcr ocr;
    private final MeteredLlmClient metered;
    private final ExtractionClients clients;
    private final ExtractionPromptBuilder prompts;
    private final ExtractionJsonReader reader;
    private final CropCutter cropCutter;
    private final CropVerifier cropVerifier;
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
        var thresholds = properties.getExtract();

        if (overall < thresholds.getMinConfidenceOverall()) {
            return finish(document, Document.Status.NEEDS_RETAKE,
                    "the document was not read confidently enough (%.2f)".formatted(overall),
                    attempt, result, null);
        }
        // A section the document type expects, read as empty, is a failed read whatever confidence it claims:
        // a lab report with no values is not an empty page. Checked before the confidence gate, because the
        // number is the thing we do not trust here.
        String missing = missingExpectedSection(result, thresholds);
        if (missing != null) {
            return finish(document, Document.Status.NEEDS_RETAKE,
                    "nothing was read from the %s of this %s".formatted(missing, typeOf(result)),
                    attempt, result, null);
        }
        double gatedSection = lowestPresentSection(result, thresholds);
        if (gatedSection < thresholds.getMinConfidenceSection()) {
            return finish(document, Document.Status.NEEDS_RETAKE,
                    "part of the document was not read confidently enough (%.2f)".formatted(gatedSection),
                    attempt, result, null);
        }
        // DR-28: the gate above judged what was EXTRACTED; this judges what will actually be SHOWN. A value
        // with no locatable crop is never persisted (crop_key is NOT NULL, "no number without its source"),
        // so a lab report can pass every confidence check and still show the family nothing. lab10 did
        // exactly that: DONE at 0.9, one value extracted, one dropped, zero shown.
        // DR-31 amends DR-28: a document that shows nothing after cropping is DONE, not NEEDS_RETAKE. The
        // confidence gate above judged the READING and passed it; the crop locator failing says nothing about
        // the photo. Retaking would tell a family with a clear page that their photo is unclear, they would
        // send the same page again, and it would fail the same way — and because a retaken document persists
        // no extraction, the finding would be discarded on every attempt. lab10 was exactly that: a clear
        // page, read at 0.9, whose one value was an out-of-range uric acid.
        //
        // The extraction is persisted, nothing is shown, and the dropped values still reach urgency
        // (UrgencyService.unverifiedValues). NEEDS_RETAKE is for weak reading, never for our own locator.
        return verify(document, pages, result)
                .flatMap(items -> {
                    if (showsNothingItShould(result, items, thresholds)) {
                        log.warn("[baymax] document shows nothing after cropping documentId={} section={} — "
                                        + "kept as DONE, values unverified (DR-31)",
                                document.id(), gatingSectionOf(result, thresholds));
                    }
                    return finish(document, Document.Status.DONE, null, attempt, result, items);
                });
    }


    /**
     * {@code document_type} as the expectation table keys it, taken from the EXTRACTION — the document row
     * does not carry it yet at gate time, it is written in {@code finish}. Reading it from the row here made
     * every document look like "other", which expects nothing, and the lab-report half of the rule never fired.
     */
    private static String typeOf(ExtractionResult result) {
        String type = result == null ? null : result.documentType();
        return type == null ? "other" : type.toLowerCase(java.util.Locale.ROOT);
    }

    private static int itemCount(ExtractionResult result, String section) {
        return switch (section) {
            case "values" -> result.valuesOrEmpty().size();
            case "medicines" -> result.medicinesOrEmpty().size();
            case "follow_up" -> result.followUpOrEmpty().size();
            default -> 1;   // unknown section names are not gated on emptiness
        };
    }

    /**
     * The first section this document type expects that came back with nothing, or null when all are present.
     * "Expects" comes from config, not from the model: a prescription never carries lab values, so an empty
     * values[] there is the page, not the read.
     */
    private static String missingExpectedSection(ExtractionResult result,
                                                 BaymaxProperties.Extract thresholds) {
        for (String section : thresholds.getGatingSections().getOrDefault(typeOf(result), List.of())) {
            if (itemCount(result, section) == 0) {
                return section;
            }
        }
        return null;
    }

    /**
     * The lowest confidence among sections that actually have content, plus any expected-and-present section.
     * A section with nothing in it is excluded — confidence in an empty section is a number about nothing, and
     * including it rejected clear prescriptions because values[] scored 0.0 (eleventh flattering failure: a
     * gate firing for a reason unrelated to what it exists for).
     */
    private static double lowestPresentSection(ExtractionResult result,
                                               BaymaxProperties.Extract thresholds) {
        ExtractionResult.Confidence c = result.confidence();
        if (c == null) {
            return 1d;
        }
        // DR-27: only the sections that matter for this document type gate a retake. clinical_context is
        // deliberately absent from every gating list — lab1 read 21 values at 0.92 and was retaken because a
        // clinical section a lab report barely has scored 0.60.
        List<String> gating = thresholds.getGatingSections().getOrDefault(typeOf(result), List.of());
        double lowest = 1d;
        for (var entry : java.util.Map.of("values", c.values(), "medicines", c.medicines(),
                "follow_up", c.followUp()).entrySet()) {
            if (gating.contains(entry.getKey()) && entry.getValue() != null
                    && itemCount(result, entry.getKey()) > 0) {
                lowest = Math.min(lowest, entry.getValue());
            }
        }
        return lowest;
    }

    /** The first gating section for this type, for the retake reason; "document" when the type gates nothing. */
    private static String gatingSectionOf(ExtractionResult result, BaymaxProperties.Extract thresholds) {
        List<String> gating = thresholds.getGatingSections().getOrDefault(typeOf(result), List.of());
        return gating.isEmpty() ? "document" : gating.get(0);
    }

    /**
     * True when a gating section was read but nothing from it survived cropping — the family would be shown
     * an empty report (DR-28). Judged on the verified items, not the extraction, because that is what they
     * see: every value here carries a crop, by construction.
     */
    private static boolean showsNothingItShould(ExtractionResult result, VerifiedItems items,
                                                BaymaxProperties.Extract thresholds) {
        if (items == null) {
            return false;
        }
        for (String section : thresholds.getGatingSections().getOrDefault(typeOf(result), List.of())) {
            if (itemCount(result, section) > 0 && shownCount(items, section) == 0) {
                return true;
            }
        }
        return false;
    }

    private static int shownCount(VerifiedItems items, String section) {
        return switch (section) {
            case "values" -> items.observations() == null ? 0 : items.observations().size();
            case "medicines" -> items.medications() == null ? 0 : items.medications().size();
            case "follow_up" -> items.followUps() == null ? 0 : items.followUps().size();
            default -> 1;
        };
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
                .concatMap(value -> cropForValue(document, value, byPage, itemId("v", observations.size()))
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

    /**
     * A value's crop key: the OCR-located crop when there is one, otherwise the model's region — but only
     * after something independent has re-read that region and found the value in it.
     *
     * <p>The OCR path is unchanged and still first: when Vision produced the value's text, its word boxes
     * are the most reliable thing we have and the crop needs no second opinion, because the text check
     * inside {@code cutValue} already is one. The region path exists for the 30-of-85 case where Vision
     * never produced the text at all, and there the model's box is the only claim available — so it is
     * verified before it is stored, never on the strength of the same model that proposed it (DR-12).</p>
     */
    private Mono<String> cropForValue(Document document, ExtractionResult.Value value,
                                      Map<Integer, PageOcr> byPage, String itemId) {
        CropCutter.Cut located = cropCutter.cutValue(value.sourceSpan(), value.name(), value.value(), byPage);
        if (located.bytes().isPresent()) {
            return store(document, located, itemId);
        }
        CropCutter.Cut region = cropCutter.cutRegion(value.sourceRegion(), byPage);
        if (region.bytes().isEmpty()) {
            return Mono.empty();
        }
        byte[] crop = region.bytes().orElseThrow();
        return cropVerifier.verify(document.id(), crop, value.name(), value.value())
                .flatMap(outcome -> {
                    if (!outcome.verified()) {
                        log.debug("[baymax] region crop not confirmed documentId={} item={}", document.id(), itemId);
                        return Mono.empty();
                    }
                    log.info("[baymax] value recovered from the page image documentId={} item={} readBy={}",
                            document.id(), itemId, outcome.readBy());
                    return store(document, region, itemId);
                });
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

    // --- re-crop (DR-12): replay stored extraction_json through the current cutter --------------------

    @Override
    public Mono<RecropReport> recrop(UUID documentId) {
        return records.find(documentId)
                .switchIfEmpty(Mono.error(BaymaxException.notFound("document_not_found", "no document with id " + documentId)))
                .flatMap(document -> {
                    if (document.status() != Document.Status.DONE || document.extractionJson() == null) {
                        return Mono.just(new RecropReport(documentId, "skipped_" + document.status().name().toLowerCase(java.util.Locale.ROOT),
                                0, 0, 0, 0, 0));
                    }
                    ExtractionResult result = reader.readStored(document.extractionJson());
                    return storedPages(document)
                            .flatMap(pages -> storage.deleteCrops(document.familyId(), document.patientId(), document.id())
                                    .then(records.deleteItems(document.id()))
                                    .then(verify(document, pages, result))
                                    .flatMap(items -> finish(document, Document.Status.DONE, null, storedAttempt(document), result, items)
                                            .thenReturn(items)))
                            .map(items -> new RecropReport(documentId, "recropped", items.observations().size(),
                                    items.medications().size(), items.followUps().size(), items.clinicalContext().size(),
                                    items.unverified().total()));
                })
                .doOnNext(r -> log.info("[baymax] recrop documentId={} outcome={} values={} medicines={} followUps={} context={} unverified={}",
                        r.documentId(), r.outcome(), r.values(), r.medicines(), r.followUps(), r.context(), r.unverified()));
    }

    @Override
    public Flux<RecropReport> recropAll() {
        return records.idsWithStatus(Document.Status.DONE).concatMap(this::recrop);
    }

    /** OCR the stored page images again: Vision only, metered like any OCR call, no model. */
    private Mono<List<PageOcr>> storedPages(Document document) {
        return Flux.range(1, Math.max(1, document.pageCount()))
                .concatMap(n -> storage.pageBytes(document.familyId(), document.patientId(), document.id(), n)
                        .flatMap(bytes -> {
                            String base64 = Base64.getEncoder().encodeToString(bytes);
                            return ocr.detectDocumentText(document.id(), VisionOcrRequest.withLanguages(base64, List.of("bn", "en")))
                                    .map(response -> PageOcr.from(n, base64, response));
                        }))
                .collectList();
    }

    /** The model and confidence the document already carries, so finish() writes them back unchanged. */
    private static Attempt storedAttempt(Document document) {
        String model = document.modelFinal() == null ? "unknown/unknown" : document.modelFinal();
        int slash = model.indexOf('/');
        String provider = slash > 0 ? model.substring(0, slash) : "unknown";
        String name = slash > 0 ? model.substring(slash + 1) : model;
        ExtractionResult.Confidence confidence = new ExtractionResult.Confidence(document.confidenceOverall(), null, null, null, null);
        return new Attempt(name, provider, new ExtractionResult(null, null, null, null, null, null, null, null, null, confidence));
    }
}
