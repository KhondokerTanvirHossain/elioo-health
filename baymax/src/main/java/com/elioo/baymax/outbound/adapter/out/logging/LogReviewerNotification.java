package com.elioo.baymax.outbound.adapter.out.logging;

import com.elioo.baymax.outbound.application.port.out.ReviewerNotificationPort;
import com.elioo.baymax.outbound.domain.OutboundMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** v1: the reviewer (DR-13: Tanvir) is told in the server log. Ids and urgency only — never the body. WhatsApp is BMX-10. */
@Slf4j
@Component
public class LogReviewerNotification implements ReviewerNotificationPort {

    @Override
    public Mono<Void> notifyPending(OutboundMessage m) {
        return Mono.fromRunnable(() -> log.info("[baymax] REVIEW PENDING messageId={} documentId={} urgency={} kind={} — approve or reject via /admin/messages",
                m.id(), m.documentId(), m.urgency(), m.kind()));
    }
}
