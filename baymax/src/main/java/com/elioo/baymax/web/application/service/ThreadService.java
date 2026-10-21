package com.elioo.baymax.web.application.service;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.web.application.port.in.ResultUseCase;
import com.elioo.baymax.web.application.port.in.ThreadUseCase;
import com.elioo.baymax.web.application.port.out.ThreadReadPort;
import com.elioo.baymax.web.domain.ThreadItem;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * The চ্যাট thread (plan W5-api). The cursor is parsed first, so a bad one is a 400 and nothing is read for it; then the
 * patient's visibility, {@link HealthRecordPort#visiblePatient}, so another family's patient is a 404 before any thread
 * SQL runs; then one page of the read model for that patient, as this viewer sees it ({@link ThreadReadPort}), with one
 * row more than a page to know whether there is a next one. Each paper's card is read through {@link ResultUseCase#card},
 * which records no DOCUMENT_VIEW: reading the thread is not opening a paper. A paper deleted between the page and its
 * card is left out of the page, never a 404 for the whole thread.
 */
@Service
@RequiredArgsConstructor
public class ThreadService implements ThreadUseCase {

    /** Cards read at once: each is a handful of indexed reads, and a page is twenty entries. */
    static final int CARD_CONCURRENCY = 4;

    private final HealthRecordPort records;
    private final ThreadReadPort reads;
    private final ResultUseCase results;

    @Override
    public Mono<Page> thread(UUID familyId, UUID patientId, String cursor) {
        return Mono.fromCallable(() -> ThreadItem.Cursor.parse(cursor))
                .flatMap(after -> records.visiblePatient(familyId, patientId)
                        .switchIfEmpty(Mono.error(BaymaxException.notFound("patient_not_found", "no patient with id " + patientId)))
                        .flatMap(access -> reads.page(familyId, patientId, after, PAGE_SIZE + 1).collectList()))
                .flatMap(rows -> {
                    boolean more = rows.size() > PAGE_SIZE;
                    List<ThreadItem> shown = more ? rows.subList(0, PAGE_SIZE) : rows;
                    // the whole (at, order, id) of the last row read, so a card left out never moves the next page
                    String next = more ? ThreadItem.Cursor.after(shown.get(shown.size() - 1)).encode() : null;
                    return Flux.fromIterable(shown)
                            .flatMapSequential(item -> entry(familyId, item), CARD_CONCURRENCY)
                            .collectList()
                            .map(entries -> new Page(entries, next));
                });
    }

    private Mono<Entry> entry(UUID familyId, ThreadItem item) {
        return switch (item) {
            case ThreadItem.Paper paper -> results.card(familyId, paper.documentId())
                    .map(card -> new Entry(item, card))
                    .onErrorResume(ThreadService::gone, e -> Mono.empty());
            case ThreadItem.Message message -> Mono.just(new Entry(message, null));
        };
    }

    private static boolean gone(Throwable e) {
        return e instanceof BaymaxException be && be.status() == HttpStatus.NOT_FOUND;
    }
}
