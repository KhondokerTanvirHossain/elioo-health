package com.elioo.baymax.extraction.application.service;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Decides whether a cut crop may be stored, by reading it again with something other than the extraction.
 *
 * <p>DR-12 in one sentence: a crop is stored only when something independent of the model's claim confirms
 * what it shows. That rule exists because 137 crops were once stored on the strength of the model's own
 * offsets and 116 of them showed the neighbouring words — and the test that was meant to catch it checked
 * only that a file existed.</p>
 *
 * <p>The image-side route makes that failure easy to repeat. The model now points at a region of the page
 * and we cut it, but a box is a claim about where the value is, not evidence that it is there. A model that
 * misreads a table by one row produces a confident box over the wrong number, and the crop is then a
 * picture of a different value sitting under this value's name. So the box proposes and this class
 * disposes, and the two must not share a source.</p>
 *
 * <p>The crop is re-read <em>alone</em>: no page around it, no name, no expected value, nothing that would
 * let the reader agree out of context. OCR goes first because it is cheap and a tight crop is usually easy
 * to read; only when OCR returns nothing does a model see the crop by itself. Whatever comes back must
 * contain the value under {@link SpanLocator#valueAppearsOn}'s digit-boundary rule, so a crop showing
 * {@code 12.1} can never verify a value of {@code 2.1}.</p>
 *
 * <p>Anything short of confirmation — no reading, a failed reader, a reading that disagrees — leaves the
 * value unverified, and an unverified value is not shown. That is the same outcome as today's locator
 * failure, which is the point: the change adds a way to recover values, never a way to show an unchecked
 * one.</p>
 */
@Slf4j
public class CropVerifier {

    /** Reads a crop with no context. Empty when nothing could be read; an error is not a disagreement. */
    @FunctionalInterface
    public interface CropReader {
        Mono<String> read(UUID documentId, byte[] crop);
    }

    /** Which reader confirmed the crop — for the cost report, since one is free and one is not. */
    public enum ReadBy {
        OCR, MODEL, NONE
    }

    public record Outcome(boolean verified, ReadBy readBy) {
        static final Outcome UNVERIFIED = new Outcome(false, ReadBy.NONE);
    }

    private final CropReader ocr;
    private final CropReader model;

    public CropVerifier(CropReader ocr, CropReader model) {
        this.ocr = ocr;
        this.model = model;
    }

    /**
     * @param name  the item's name, for the log only — a tight crop of a table cell often holds just the
     *              number, and requiring the name back would reject the best crops we cut
     * @param value the reading that must appear in the crop
     */
    public Mono<Outcome> verify(UUID documentId, byte[] crop, String name, String value) {
        if (crop == null || crop.length == 0 || value == null || value.isBlank()) {
            return Mono.just(Outcome.UNVERIFIED);
        }
        // OCR is read ONCE. A second subscription would bill a second call, and for a non-idempotent reader
        // could return something different from what the decision below was made on.
        return read(ocr, documentId, crop)
                .flatMap(text -> {
                    if (SpanLocator.valueAppearsOn(text, value)) {
                        return Mono.just(new Outcome(true, ReadBy.OCR));
                    }
                    // OCR read this crop and it does not show the value: a wrong crop, settled. Paying a
                    // model to look again would only invite a second opinion on evidence we already have.
                    log.debug("[baymax] crop rejected by OCR documentId={} item={}", documentId, name);
                    return Mono.just(Outcome.UNVERIFIED);
                })
                // Empty means OCR read NOTHING — the case the model exists for, and the case that loses
                // values today.
                .switchIfEmpty(Mono.defer(() -> read(model, documentId, crop)
                        .map(text -> new Outcome(SpanLocator.valueAppearsOn(text, value), ReadBy.MODEL))
                        .defaultIfEmpty(Outcome.UNVERIFIED)));
    }

    /** A reader that fails has not confirmed anything; it must never take the document down with it. */
    private static Mono<String> read(CropReader reader, UUID documentId, byte[] crop) {
        return reader.read(documentId, crop)
                .filter(text -> text != null && !text.isBlank())
                .onErrorResume(error -> {
                    log.warn("[baymax] crop reader failed documentId={}: {}", documentId, error.getMessage());
                    return Mono.empty();
                });
    }
}
