package com.elioo.baymax.wa.adapter.in;

import com.elioo.baymax.adapter.in.router.BaymaxHealthRouter;
import com.elioo.baymax.common.error.ErrorResponseFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * {@code /api/v1/baymax/wa/webhook} — Meta's callback (BMX-10 phase 1).
 *
 * <p>Under the API prefix rather than the human UI paths ({@code /app}, {@code /baymax/}) because it is a
 * machine endpoint. It carries no admin filter and cannot: Meta decides what to send and has no token of ours.
 * The {@code X-Hub-Signature-256} check inside the handler is the whole of its authentication.
 *
 * <p>The route exists only when {@code baymax.wa.enabled=true}. With the flag unset there is no endpoint at
 * all — not a disabled one that might answer — so every existing behaviour is unchanged, which is the RUNBOOK
 * contract for this channel.
 */
@Configuration
@ConditionalOnProperty(prefix = "baymax.wa", name = "enabled", havingValue = "true")
public class WaWebhookRouter {

    public static final String WEBHOOK_PATH = BaymaxHealthRouter.BASE_PATH + "/wa/webhook";

    @Bean
    public RouterFunction<ServerResponse> baymaxWaRoutes(WaWebhookHandler handler, ErrorResponseFilter errors) {
        return RouterFunctions.route()
                .GET(WEBHOOK_PATH, handler::verify)
                .POST(WEBHOOK_PATH, handler::receive)
                .filter(errors)
                .build();
    }
}
