package com.elioo.baymax.web.application.service;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.PatientAccess;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.outbound.domain.OutboundMessage;
import com.elioo.baymax.web.application.port.in.ResultUseCase;
import com.elioo.baymax.web.application.port.in.ThreadUseCase;
import com.elioo.baymax.web.application.port.out.ThreadReadPort;
import com.elioo.baymax.web.domain.ThreadItem;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Plan W5-api, review of #135 finding 5: a paper deleted between the thread's page and its card is left out of the page,
 * never a 404 for the whole thread, and the next page still starts after the last row the page READ (so the left-out
 * paper moves nothing). Only {@code document_not_found} is that case: any other failure of a card, a 404 for another
 * reason included, fails the request rather than silently dropping a paper from the thread.
 */
class ThreadServiceTest {

    static final UUID FAMILY = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    static final UUID PATIENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final Instant T0 = Instant.parse("2026-10-01T04:00:00Z");

    final HealthRecordPort records = mock(HealthRecordPort.class);
    final ThreadReadPort reads = mock(ThreadReadPort.class);
    final ResultUseCase results = mock(ResultUseCase.class);
    final ThreadService service = new ThreadService(records, reads, results);

    /** Three papers and two messages, newest first, then one row more than a page (so a next page exists). */
    private List<ThreadItem> rows(List<UUID> papers) {
        List<ThreadItem> rows = new ArrayList<>();
        for (int i = 0; i < ThreadUseCase.PAGE_SIZE + 1; i++) {
            Instant at = T0.minus(Duration.ofHours(i));
            if (i < 3) {
                UUID paper = UUID.nameUUIDFromBytes(("paper-" + i).getBytes());
                papers.add(paper);
                rows.add(new ThreadItem.Paper(paper, at));
            } else {
                rows.add(new ThreadItem.Message(UUID.nameUUIDFromBytes(("message-" + i).getBytes()), at, OutboundMessage.Kind.DETAIL,
                        null, "body " + i));
            }
        }
        return rows;
    }

    private List<ThreadItem> given(BaymaxException middleCard) {
        PatientProfile ma = new PatientProfile(PATIENT, FAMILY, "Ma", 64, PatientProfile.Sex.FEMALE, List.of(), T0, T0);
        when(records.visiblePatient(FAMILY, PATIENT)).thenReturn(Mono.just(new PatientAccess(ma, true)));
        List<UUID> papers = new ArrayList<>();
        List<ThreadItem> rows = rows(papers);
        when(reads.page(eq(FAMILY), eq(PATIENT), any(), anyInt())).thenReturn(Flux.fromIterable(rows));
        when(results.card(FAMILY, papers.get(0))).thenReturn(Mono.just("card-0"));
        when(results.card(FAMILY, papers.get(1))).thenReturn(Mono.error(middleCard));
        when(results.card(FAMILY, papers.get(2))).thenReturn(Mono.just("card-2"));
        return rows;
    }

    @Test
    void aPaperDeletedBeforeItsCardIsLeftOutAndTheCursorStillFollowsTheLastRowRead() {
        List<ThreadItem> rows = given(BaymaxException.notFound("document_not_found", "no document"));

        StepVerifier.create(service.thread(FAMILY, PATIENT, null)).assertNext(page -> {
            List<UUID> ids = page.items().stream().map(e -> e.item().id()).toList();
            List<UUID> expected = new ArrayList<>(rows.subList(0, ThreadUseCase.PAGE_SIZE).stream().map(ThreadItem::id).toList());
            expected.remove(rows.get(1).id());
            assertThat(ids).as("the page: the two other papers and every message, in order, the deleted paper left out")
                    .containsExactlyElementsOf(expected);
            assertThat(page.items().stream().filter(e -> e.item() instanceof ThreadItem.Paper).map(ThreadUseCase.Entry::card).toList())
                    .as("the cards of the two papers still there").containsExactly("card-0", "card-2");
            assertThat(page.nextCursor()).as("next_cursor: after the last row read, whatever the page left out")
                    .isEqualTo(ThreadItem.Cursor.after(rows.get(ThreadUseCase.PAGE_SIZE - 1)).encode());
        }).verifyComplete();
    }

    @Test
    void aCardThatFailsForAnotherReasonFailsTheRequest() {
        given(BaymaxException.notFound("patient_not_found", "no patient"));

        StepVerifier.create(service.thread(FAMILY, PATIENT, null))
                .expectErrorSatisfies(e -> assertThat(e).as("a 404 that is not the paper's own: not swallowed")
                        .isInstanceOf(BaymaxException.class).hasFieldOrPropertyWithValue("reason", "patient_not_found"))
                .verify();
    }

    @Test
    void aCardThatFailsWithAnythingElseFailsTheRequest() {
        PatientProfile ma = new PatientProfile(PATIENT, FAMILY, "Ma", 64, PatientProfile.Sex.FEMALE, List.of(), T0, T0);
        when(records.visiblePatient(FAMILY, PATIENT)).thenReturn(Mono.just(new PatientAccess(ma, true)));
        UUID paper = UUID.randomUUID();
        when(reads.page(eq(FAMILY), eq(PATIENT), any(), anyInt())).thenReturn(Flux.just(new ThreadItem.Paper(paper, T0)));
        when(results.card(FAMILY, paper)).thenReturn(Mono.error(new IllegalStateException("the store is down")));

        StepVerifier.create(service.thread(FAMILY, PATIENT, null))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalStateException.class).hasMessage("the store is down"))
                .verify();
    }
}
