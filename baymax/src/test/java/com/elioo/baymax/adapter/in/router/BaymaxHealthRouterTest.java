package com.elioo.baymax.adapter.in.router;

import com.elioo.baymax.adapter.in.handler.BaymaxHealthHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

class BaymaxHealthRouterTest {

    private final WebTestClient client = WebTestClient.bindToRouterFunction(
            new BaymaxHealthRouter().baymaxHealthRoutes(new BaymaxHealthHandler())).build();

    @Test
    void healthReturnsUpUnderTheBaymaxPrefix() {
        client.get().uri("/api/v1/baymax/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.module").isEqualTo("baymax");
    }

    @Test
    void nothingIsServedOutsideTheBaymaxPrefix() {
        client.get().uri("/api/v1/health").exchange().expectStatus().isNotFound();
    }
}
