package com.elioo.baymax.nudge.application.port.in;

import reactor.core.publisher.Mono;

import java.util.UUID;

/** The engine's two entry points: the hourly evaluation, and the medicine_changed check when a document is DONE. */
public interface NudgeUseCase {

    /** One evaluation of every rule for every patient, plus release of held nudges now inside the send window. */
    Mono<Long> evaluateAll();

    /** medicine_changed fires on extraction completion; the other rules wait for the hour. */
    Mono<Void> onDocumentDone(UUID documentId);
}
