package com.elioo.baymax.web.application.service;

import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.web.application.port.in.AttentionUseCase;
import com.elioo.baymax.web.application.port.in.NowBannerUseCase;
import com.elioo.baymax.web.application.port.out.NowAckPort;
import com.elioo.baymax.web.domain.AttentionItem;
import com.elioo.baymax.web.domain.NowBanner;
import com.elioo.baymax.web.domain.NowSeen;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * DR-41 item 5. <b>The banner's papers are the attention stack's NOW cards, read, not restated</b>: for each patient the
 * viewer may see, {@link AttentionUseCase#urgent} (stored F1 levels, the 30-day window, DONE papers, in the stack's
 * order), kept where the level is NOW. There is no second query defining "the visible NOW set", so a later change to the
 * stack changes the banner with it. {@link NowAckPort} only enriches those ids with what was shown and records the taps;
 * {@code call_999} is {@link ResultService#viewer}'s rule, the paper page's, never a second one.
 */
@Service
@RequiredArgsConstructor
public class NowBannerService implements NowBannerUseCase {

    private final HealthRecordPort records;
    private final AttentionUseCase attention;
    private final NowAckPort acks;
    private final ResultService results;
    private final Clock clock;

    @Override
    public Mono<NowBanner> banner(UUID viewerFamilyId) {
        return records.visiblePatients(viewerFamilyId)
                .concatMap(access -> attention.urgent(viewerFamilyId, access.patient().id()).flatMapIterable(Function.identity()))
                .filter(card -> card.level() == Urgency.NOW)
                .collectList()
                .flatMap(cards -> {
                    List<UUID> ids = cards.stream().map(AttentionItem::documentId).toList();
                    return Mono.zip(
                            acks.seenOf(ids).collectMap(NowSeen::documentId),
                            acks.ackedBy(viewerFamilyId, ids).collectList(),
                            results.viewer(viewerFamilyId))
                            .map(t -> new NowBanner(items(cards, t.getT1(), t.getT2()), t.getT3().bangladesh()));
                });
    }

    @Override
    public Mono<NowBanner> acknowledge(UUID viewerFamilyId, List<NowSeen> seen) {
        return banner(viewerFamilyId)
                .flatMap(current -> {
                    // only what the banner shows right now, exactly; the current item (with its patient) is what is stored
                    List<NowSeen> matched = current.documents().stream().map(NowBanner.Item::seen)
                            .filter(shown -> seen.stream().anyMatch(shown::sameShownAs))
                            .toList();
                    return acks.record(viewerFamilyId, matched, clock.instant());
                })
                .then(Mono.defer(() -> banner(viewerFamilyId)));
    }

    private static List<NowBanner.Item> items(List<AttentionItem.Urgent> cards, Map<UUID, NowSeen> seen, List<NowSeen> acked) {
        List<NowBanner.Item> items = new ArrayList<>();
        for (AttentionItem.Urgent card : cards) {
            NowSeen shown = seen.get(card.documentId());
            if (shown == null) {
                continue;   // deleted between the two reads: nothing to show
            }
            boolean done = acked.stream().anyMatch(shown::sameShownAs);
            items.add(new NowBanner.Item(shown, card.documentType(), card.date(), card.dateIsFallback(), card.dateUncertain(), done));
        }
        return List.copyOf(items);
    }
}
