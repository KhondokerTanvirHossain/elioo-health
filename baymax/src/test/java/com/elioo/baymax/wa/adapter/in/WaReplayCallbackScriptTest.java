package com.elioo.baymax.wa.adapter.in;

import com.elioo.baymax.wa.WaFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * scripts/wa-replay-callback.py is how Tanvir tries F9a from the server: a phone cannot force a Meta re-delivery. The
 * request it builds must be one the real webhook accepts — the right path, Meta's signature over the exact bytes, and a
 * message id of Meta's shape that carries the test number — or a green "two 200s" would prove nothing. And it forges a
 * message from a real number, so it must refuse every number but the dedicated test one: never the first allowlist
 * entry by default, which becomes a pilot family's number the day one is added first. Runs the real script with
 * python3, against a local HTTP server that records what it receives; if python3 is missing this fails rather than skips.
 */
class WaReplayCallbackScriptTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String TEST_NUMBER = "+8801700000000";
    private static final String FAMILY = "+8801700000001";

    /** A recorded POST: path, body bytes, signature header. */
    private record Received(String path, byte[] body, String signature) {
    }

    private HttpServer server;
    private final List<Received> received = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void listen() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            received.add(new Received(exchange.getRequestURI().getPath(), exchange.getRequestBody().readAllBytes(),
                    exchange.getRequestHeaders().getFirst("X-Hub-Signature-256")));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private record Run(int exit, String out, String err) {
    }

    private static Path envFile(Path dir, String allowlist, String testNumberLine) throws Exception {
        Path env = dir.resolve("replay.env");
        List<String> lines = new ArrayList<>(List.of(
                "# a comment line, skipped",
                "BAYMAX_WA_APP_SECRET_OLD=not-this-one",
                "BAYMAX_WA_APP_SECRET=" + SECRET,
                "BAYMAX_WA_ALLOWLIST=" + allowlist));
        if (testNumberLine != null) {
            lines.add(testNumberLine);
        }
        lines.add("");
        Files.writeString(env, String.join("\n", lines));
        return env;
    }

    /** The real script; BAYMAX_WA_TEST_NUMBER only from what the test passes, never from the machine running it. */
    private static Run run(Path dir, Map<String, String> environment, String... args) throws Exception {
        Path script = Path.of("..", "scripts", "wa-replay-callback.py").toAbsolutePath().normalize();
        assertThat(script).as("the script exists").exists();
        List<String> command = new ArrayList<>(List.of("python3", "-I", script.toString()));
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile());
        pb.environment().remove("BAYMAX_WA_TEST_NUMBER");
        pb.environment().putAll(environment);
        Process p = pb.start();
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).as("the script finished").isTrue();
        return new Run(p.exitValue(), new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void theDryRunBuildsTheRequestTheWebhookAcceptsAndPrintsNoSecretOrNumberOutsideTheBody(@TempDir Path dir) throws Exception {
        Path env = envFile(dir, TEST_NUMBER + "," + FAMILY, "BAYMAX_WA_TEST_NUMBER=" + TEST_NUMBER);
        Run r = run(dir, Map.of(), "--env", env.toString(), "--number", TEST_NUMBER, "--dry-run", "--show-signature");
        assertThat(r.exit()).as("exit code; stderr: %s", r.err()).isZero();

        JsonNode printed = new ObjectMapper().readTree(r.out());
        String body = printed.path("body").asText();
        assertThat(printed.path("path").asText()).isEqualTo(WaWebhookRouter.WEBHOOK_PATH);
        assertThat(printed.path("signature").asText())
                .as("Meta's signature over the exact bytes the webhook will read")
                .isEqualTo(WaSignature.sign(body.getBytes(StandardCharsets.UTF_8), SECRET));
        assertThat(WaSignature.valid(printed.path("signature").asText(), body.getBytes(StandardCharsets.UTF_8), SECRET)).isTrue();

        JsonNode message = new ObjectMapper().readTree(body).path("entry").path(0).path("changes").path(0)
                .path("value").path("messages").path(0);
        assertThat(message.path("from").asText()).isEqualTo("8801700000000");
        assertThat(message.path("type").asText()).isEqualTo("text");
        assertThat(message.path("text").path("body").asText()).isEqualTo("বিস্তারিত");
        String id = message.path("id").asText();
        assertThat(id).startsWith("wamid.");
        assertThat(WaFixtures.decodedDigits(id)).as("a wamid of Meta's shape, carrying the test number")
                .isEqualTo("8801700000000");
        // what the webhook's parser reads from it
        assertThat(WaWebhookHandler.parse(message).providerMessageId()).isEqualTo(id);
        assertThat(WaWebhookHandler.parse(message).timestamp()).isNotNull();

        // the body legitimately carries the number and the id; nothing else printed may: stdout is exactly the three
        // fields, matched as fields (a substring check over a document full of ids is not an assertion)
        assertThat(printed.fieldNames()).toIterable().containsExactlyInAnyOrder("path", "body", "signature");
        for (String field : new String[]{"path", "signature"}) {
            assertThat(printed.path(field).asText()).as(field)
                    .doesNotContain(SECRET).doesNotContain("8801700000000").doesNotContain(id);
        }
        assertThat(body).as("the secret signs the body; it is never in it").doesNotContain(SECRET);
        assertThat(r.err()).as("stderr").isEmpty();
        assertThat(received).as("a dry run sends nothing").isEmpty();
    }

    /** A printed signature is a replayable request to the public webhook: only on an explicit flag. */
    @Test
    void aDryRunPrintsNoSignatureUnlessAskedTo(@TempDir Path dir) throws Exception {
        Path env = envFile(dir, TEST_NUMBER, "BAYMAX_WA_TEST_NUMBER=" + TEST_NUMBER);
        Run r = run(dir, Map.of(), "--env", env.toString(), "--number", TEST_NUMBER, "--dry-run");
        assertThat(r.exit()).as("stderr: %s", r.err()).isZero();
        assertThat(new ObjectMapper().readTree(r.out()).fieldNames()).toIterable().containsExactlyInAnyOrder("path", "body");
        assertThat(r.out()).doesNotContain("sha256=");
    }

    /**
     * The mode Tanvir runs on the box: the identical signed bytes twice to the webhook path, and only the masked target
     * and the two statuses printed.
     */
    @Test
    void theRealRunPostsTheSameSignedBytesTwiceAndPrintsOnlyTheTargetAndTheStatuses(@TempDir Path dir) throws Exception {
        Path env = envFile(dir, FAMILY + "," + TEST_NUMBER, null);
        Run r = run(dir, Map.of("BAYMAX_WA_TEST_NUMBER", TEST_NUMBER),
                "--env", env.toString(), "--url", url(), "--number", TEST_NUMBER, "--yes");

        assertThat(r.exit()).as("stderr: %s", r.err()).isZero();
        assertThat(r.out()).isEqualTo("target ****0000\n200\n200\n");
        assertThat(r.err()).isEmpty();
        assertThat(received).hasSize(2);
        assertThat(received).allSatisfy(req -> {
            assertThat(req.path()).isEqualTo(WaWebhookRouter.WEBHOOK_PATH);
            assertThat(WaSignature.valid(req.signature(), req.body(), SECRET)).as("Meta's signature over those bytes").isTrue();
        });
        assertThat(received.get(1).body()).as("byte-identical, as Meta re-delivers").isEqualTo(received.get(0).body());
        JsonNode message = new ObjectMapper().readTree(received.get(0).body()).path("entry").path(0).path("changes").path(0)
                .path("value").path("messages").path(0);
        assertThat(message.path("from").asText()).as("sent as the test number, not the first allowlist entry")
                .isEqualTo("8801700000000");
    }

    /**
     * The dangerous direction. The allowlist's first entry is a family's number, not the test number, and no --number
     * is given: the script must exit non-zero and send nothing (it once took allowlist[0] silently).
     */
    @Test
    void withoutAnExplicitNumberNothingIsSentEvenWhenTheFirstAllowlistEntryIsAFamily(@TempDir Path dir) throws Exception {
        Path env = envFile(dir, FAMILY + "," + TEST_NUMBER, "BAYMAX_WA_TEST_NUMBER=" + TEST_NUMBER);
        // the plain invocation the RUNBOOK once gave, and the same with --yes: neither may send as anyone
        for (String[] args : List.of(new String[]{"--env", env.toString(), "--url", url()},
                new String[]{"--env", env.toString(), "--url", url(), "--yes"})) {
            Run r = run(dir, Map.of(), args);
            assertThat(received).as("requests the script sent as the first allowlisted number, args %s", List.of(args)).isEmpty();
            assertThat(r.exit()).as("exit code; out: %s", r.out()).isNotZero();
            assertThat(r.out() + r.err()).doesNotContain("8801700000001");
        }
    }

    @Test
    void anAllowlistedNumberThatIsNotTheTestNumberIsRefused(@TempDir Path dir) throws Exception {
        Path env = envFile(dir, FAMILY + "," + TEST_NUMBER, "BAYMAX_WA_TEST_NUMBER=" + TEST_NUMBER);
        Run r = run(dir, Map.of(), "--env", env.toString(), "--url", url(), "--number", FAMILY, "--yes");

        assertThat(r.exit()).isNotZero();
        assertThat(r.err()).contains("BAYMAX_WA_TEST_NUMBER").contains("****0001").doesNotContain("8801700000001");
        assertThat(received).isEmpty();
    }

    @Test
    void theTestNumberMustBeOnTheAllowlistAndMustBeSet(@TempDir Path dir) throws Exception {
        Path notListed = envFile(dir, FAMILY, "BAYMAX_WA_TEST_NUMBER=" + TEST_NUMBER);
        Run r = run(dir, Map.of(), "--env", notListed.toString(), "--url", url(), "--number", TEST_NUMBER, "--yes");
        assertThat(r.exit()).isNotZero();
        assertThat(r.err()).contains("BAYMAX_WA_ALLOWLIST");

        Path unset = envFile(dir, TEST_NUMBER, null);
        Run r2 = run(dir, Map.of(), "--env", unset.toString(), "--url", url(), "--number", TEST_NUMBER, "--yes");
        assertThat(r2.exit()).isNotZero();
        assertThat(r2.err()).contains("BAYMAX_WA_TEST_NUMBER is not set");
        assertThat(received).isEmpty();
    }

    /** Without --yes it says whom it would send as, masked, and sends nothing. */
    @Test
    void withoutYesTheMaskedTargetIsPrintedAndNothingIsSent(@TempDir Path dir) throws Exception {
        Path env = envFile(dir, TEST_NUMBER, "BAYMAX_WA_TEST_NUMBER=" + TEST_NUMBER);
        Run r = run(dir, Map.of(), "--env", env.toString(), "--url", url(), "--number", TEST_NUMBER);

        assertThat(r.exit()).isNotZero();
        assertThat(r.out()).isEqualTo("target ****0000\n");
        assertThat(r.err()).contains("--yes");
        assertThat(received).isEmpty();
    }

    @Test
    void anEnvFileWithoutTheSecretStopsTheScriptAndNamesTheVariableNotItsValue(@TempDir Path dir) throws Exception {
        Path env = dir.resolve("replay.env");
        Files.writeString(env, "BAYMAX_WA_ALLOWLIST=" + TEST_NUMBER + "\nBAYMAX_WA_APP_SECRET=\n");
        Run r = run(dir, Map.of("BAYMAX_WA_TEST_NUMBER", TEST_NUMBER), "--env", env.toString(), "--number", TEST_NUMBER,
                "--dry-run");

        assertThat(r.exit()).isNotZero();
        assertThat(r.err()).contains("BAYMAX_WA_APP_SECRET");
        assertThat(r.out() + r.err()).doesNotContain("8801700000000");
    }
}
