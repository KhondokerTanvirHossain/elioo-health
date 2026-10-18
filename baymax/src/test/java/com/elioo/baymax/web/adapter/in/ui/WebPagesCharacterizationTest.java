package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R1 and R2 are move-only. This test proves it: every route the router publishes (derived, never listed), called with
 * its fixture, answers the same status, Location and body bytes as the snapshot recorded before the move. Lives for R1
 * and R2 only; L1 (A3) deletes it, because L1 is the first PR that changes pages on purpose.
 *
 * Record (on the pre-move commit only):
 * BAYMAX_CHARACTERIZE_RECORD=true ./gradlew :baymax:test --tests '*WebPagesCharacterizationTest' --rerun
 * (an environment variable, because Gradle does not pass its own -D system properties to the test JVM).
 */
class WebPagesCharacterizationTest {

    private static final Path SNAPSHOTS = Path.of("src/test/resources/baymax/characterization");
    private static final boolean RECORD = "true".equals(System.getenv("BAYMAX_CHARACTERIZE_RECORD"));

    @Test
    void everyRouteAnswersExactlyWhatItAnsweredBeforeTheMove() throws IOException {
        List<RouteTable.Route> routes = RouteTable.of(new WebUiTestKit().router);
        assertThat(routes).as("routes read from the router; an empty or short read would pass vacuously").hasSizeGreaterThanOrEqualTo(20);
        List<String> diffs = new ArrayList<>();
        for (RouteTable.Route route : routes) {
            WebUiTestKit kit = new WebUiTestKit();
            PageFixtures.Fixture f = PageFixtures.of(route);
            f.stubs().accept(kit);
            String actual = render(kit, route, f);
            Path file = SNAPSHOTS.resolve(route.key().replaceAll("[^A-Za-z0-9]+", "_") + ".txt");
            if (RECORD) {
                Files.createDirectories(SNAPSHOTS);
                Files.writeString(file, actual, StandardCharsets.UTF_8);
                continue;
            }
            assertThat(file).as("snapshot for " + route.key()).exists();
            String expected = Files.readString(file, StandardCharsets.UTF_8);
            if (!expected.equals(actual)) {
                diffs.add(route.key() + " changed at char " + firstDifference(expected, actual) + ":\n  was: "
                        + around(expected, firstDifference(expected, actual)) + "\n  now: " + around(actual, firstDifference(expected, actual)));
            }
        }
        assertThat(RECORD).as("recording mode never passes as a check").isFalse();
        assertThat(diffs).isEmpty();
    }

    static String render(WebUiTestKit kit, RouteTable.Route route, PageFixtures.Fixture f) {
        WebTestClient.RequestBodySpec spec = kit.client.method(route.method()).uri(f.uri());
        if (f.signedIn()) {
            spec = spec.cookie("baymax_session", "tok");
        }
        WebTestClient.RequestHeadersSpec<?> ready = switch (f.body()) {
            case NONE -> spec;
            case FORM -> spec.contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(new LinkedMultiValueMap<>(f.form().entrySet().stream()
                            .collect(java.util.stream.Collectors.toMap(e -> e.getKey(), e -> List.of(e.getValue()))))));
            case MULTIPART -> {
                MultipartBodyBuilder body = new MultipartBodyBuilder();
                body.part("file", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}).filename("page-1.jpg");
                yield spec.body(BodyInserters.fromMultipartData(body.build()));
            }
        };
        EntityExchangeResult<byte[]> r = ready.exchange().expectBody(byte[].class).returnResult();
        String location = r.getResponseHeaders().getFirst(HttpHeaders.LOCATION);
        return r.getStatus().value() + "\nLocation: " + (location == null ? "-" : location) + "\n\n"
                + (r.getResponseBody() == null ? "" : new String(r.getResponseBody(), StandardCharsets.UTF_8));
    }

    private static int firstDifference(String a, String b) {
        int i = 0;
        while (i < a.length() && i < b.length() && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return i;
    }

    private static String around(String s, int at) {
        return s.substring(Math.max(0, at - 40), Math.min(s.length(), at + 40)).replace("\n", "⏎");
    }
}
