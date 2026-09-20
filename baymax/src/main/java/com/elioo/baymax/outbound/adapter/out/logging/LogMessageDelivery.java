package com.elioo.baymax.outbound.adapter.out.logging;

import com.elioo.baymax.outbound.application.port.out.MessageDeliveryPort;
import com.elioo.baymax.outbound.domain.DeliveryOutcome;
import com.elioo.baymax.outbound.domain.OutboundMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** v1: "delivered" means logged (ids, urgency, length — never the body). The family sees it on the timeline; WhatsApp is BMX-10. */
@Slf4j
@Component
public class LogMessageDelivery implements MessageDeliveryPort {

    @Override
    public Mono<DeliveryOutcome> deliver(OutboundMessage m) {
        return Mono.fromCallable(() -> {
            log.info("[baymax] message delivered (log) messageId={} documentId={} kind={} urgency={} chars={}",
                    m.id(), m.documentId(), m.kind(), m.urgency(), m.body() == null ? 0 : m.body().length());
            // nothing was delivered anywhere, so there is no outcome to record and sent_at keeps its v1 meaning
            return DeliveryOutcome.logged();
        });
    }
}
