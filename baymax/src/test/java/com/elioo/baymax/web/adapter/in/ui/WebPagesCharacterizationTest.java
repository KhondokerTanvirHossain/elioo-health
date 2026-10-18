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
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R1 and R2 are move-only. This test proves it: every route the router publishes (derived, never listed), called with
 * each of its fixtures in Bangla (no cookie, the default) and in English, answers the same status, response headers
 * and body bytes as the snapshot recorded before the move. A second test holds the set of snapshots on disk equal to
 * the set the router's routes produce, so a route lost in a move fails here instead of leaving its snapshot unread.
 * Lives for R1 and R2 only; L1 (A3) deletes it, because L1 is the first PR that changes pages on purpose.
 *
 * Record (on the pre-move commit only), from the repo root:
 * BAYMAX_CHARACTERIZE_RECORD=true ./gradlew :baymax:test --tests '*WebPagesCharacterizationTest' --rerun
 * (an environment variable, because Gradle does not pass its own -D system properties to the test JVM). The snapshot
 * directory is found from the baymax module (Gradle's working directory) or from the repo root (an IDE's).
 */
class WebPagesCharacterizationTest {

    private static final Path IN_MODULE = Path.of("src/test/resources/baymax/characterization");
    private static final Path IN_REPO = Path.of("baymax").resolve(IN_MODULE);
    private static final boolean RECORD = "true".equals(System.getenv("BAYMAX_CHARACTERIZE_RECORD"));
    /** bn is sent as no cookie at all: what a first visit gets. */
    static final List<String> LANGS = List.of("bn", "en");

    @Test
    void everyRouteAnswersExactlyWhatItAnsweredBeforeTheMove() throws IOException {
        Path snapshots = snapshots();
        List<RouteTable.Route> routes = routes();
        List<String> diffs = new ArrayList<>();
        for (RouteTable.Route route : routes) {
            for (PageFixtures.Fixture f : PageFixtures.allOf(route)) {
                for (String lang : LANGS) {
                    WebUiTestKit kit = new WebUiTestKit();
                    f.stubs().accept(kit);
                    String actual = render(kit, route, f, lang);
                    Path file = snapshots.resolve(snapshotName(route, f, lang));
                    if (RECORD) {
                        Files.createDirectories(snapshots);
                        Files.writeString(file, actual, StandardCharsets.UTF_8);
                        continue;
                    }
                    assertThat(file).as("snapshot for " + file.getFileName()).exists();
                    String expected = Files.readString(file, StandardCharsets.UTF_8);
                    if (!expected.equals(actual)) {
                        int at = firstDifference(expected, actual);
                        diffs.add(file.getFileName() + " (" + route.key() + ") changed at char " + at + ":\n  was: "
                                + around(expected, at) + "\n  now: " + around(actual, at));
                    }
                }
            }
        }
        assertThat(RECORD).as("recording mode never passes as a check").isFalse();
        assertThat(diffs).isEmpty();
    }

    /**
     * The other half of the proof: the first test walks the routes the router publishes today, so a route a move lost
     * would be skipped and its snapshot left unread. Every snapshot recorded before the move must still be produced by
     * a route, and every fixture must still have its route.
     */
    @Test
    void everySnapshotRecordedBeforeTheMoveIsStillARoute() throws IOException {
        List<RouteTable.Route> routes = routes();
        Set<String> derived = new TreeSet<>();
        for (RouteTable.Route route : routes) {
            for (PageFixtures.Fixture f : PageFixtures.allOf(route)) {
                LANGS.forEach(lang -> derived.add(snapshotName(route, f, lang)));
            }
        }
        Set<String> onDisk;
        try (Stream<Path> files = Files.list(snapshots())) {
            onDisk = files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".txt")).collect(Collectors.toCollection(TreeSet::new));
        }
        assertThat(onDisk).as("snapshots on disk; none would make this check vacuous").hasSizeGreaterThanOrEqualTo(routes.size() * LANGS.size());
        Set<String> lost = new TreeSet<>(onDisk);
        lost.removeAll(derived);
        Set<String> unrecorded = new TreeSet<>(derived);
        unrecorded.removeAll(onDisk);
        assertThat(lost).as("snapshots recorded before the move that no route produces any more").isEmpty();
        assertThat(unrecorded).as("routes or fixtures with no snapshot recorded before the move").isEmpty();
        Set<String> fixtureKeys = new TreeSet<>(PageFixtures.ALL.keySet());
        fixtureKeys.addAll(PageFixtures.VARIANTS.keySet());
        Set<String> routeKeys = routes.stream().map(RouteTable.Route::key).collect(Collectors.toCollection(TreeSet::new));
        assertThat(routeKeys).as("every fixture is for a route the router still publishes").isEqualTo(fixtureKeys);
        assertThat(routes).as("one main fixture per route").hasSize(PageFixtures.ALL.size());
    }

    private static List<RouteTable.Route> routes() {
        List<RouteTable.Route> routes = RouteTable.of(new WebUiTestKit().router);
        assertThat(routes).as("routes read from the router; an empty or short read would pass vacuously").hasSizeGreaterThanOrEqualTo(20);
        return routes;
    }

    static String snapshotName(RouteTable.Route route, PageFixtures.Fixture f, String lang) {
        return route.key().replaceAll("[^A-Za-z0-9]+", "_") + (f.name().isEmpty() ? "" : "__" + f.name()) + "." + lang + ".txt";
    }

    private static Path snapshots() {
        if (RECORD || Files.isDirectory(IN_MODULE)) {
            return IN_MODULE;
        }
        if (Files.isDirectory(IN_REPO)) {
            return IN_REPO;
        }
        throw new AssertionError("no snapshot directory at " + IN_MODULE + " or " + IN_REPO + " from "
                + Path.of("").toAbsolutePath() + ": run from the baymax module or the repo root");
    }

    /** Status, every response header (sorted, the cookie clock normalised), a blank line, the body. */
    static String render(WebUiTestKit kit, RouteTable.Route route, PageFixtures.Fixture f, String lang) {
        WebTestClient.RequestBodySpec spec = kit.client.method(route.method()).uri(f.uri());
        if (f.signedIn()) {
            spec = spec.cookie("baymax_session", "tok");
        }
        if (!"bn".equals(lang)) {
            spec = spec.cookie("baymax_lang", lang);
        }
        for (var c : f.cookies().entrySet()) {
            spec = spec.cookie(c.getKey(), c.getValue());
        }
        WebTestClient.RequestHeadersSpec<?> ready = switch (f.body()) {
            case NONE -> spec;
            case FORM -> spec.contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(new LinkedMultiValueMap<>(f.form().entrySet().stream()
                            .collect(Collectors.toMap(e -> e.getKey(), e -> List.of(e.getValue()))))));
            case MULTIPART -> {
                MultipartBodyBuilder body = new MultipartBodyBuilder();
                body.part("file", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}).filename("page-1.jpg");
                yield spec.body(BodyInserters.fromMultipartData(body.build()));
            }
        };
        EntityExchangeResult<byte[]> r = ready.exchange().expectBody(byte[].class).returnResult();
        StringBuilder out = new StringBuilder().append(r.getStatus().value()).append('\n');
        new TreeMap<>(r.getResponseHeaders()).forEach((name, values) -> values.forEach(v -> out.append(name).append(": ")
                .append(name.equalsIgnoreCase(HttpHeaders.SET_COOKIE) ? v.replaceAll("Expires=[^;]*GMT", "Expires=X") : v)
                .append('\n')));
        return out.append('\n').append(r.getResponseBody() == null ? "" : new String(r.getResponseBody(), StandardCharsets.UTF_8)).toString();
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
