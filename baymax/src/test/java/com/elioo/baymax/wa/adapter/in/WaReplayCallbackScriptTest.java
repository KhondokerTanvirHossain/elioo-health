package com.elioo.baymax.wa.adapter.in;

import com.elioo.baymax.wa.WaFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * scripts/wa-replay-callback.py is how Tanvir tries F9a from the server: a phone cannot force a Meta re-delivery. The
 * request it builds must be one the real webhook accepts — the right path, Meta's signature over the exact bytes, and a
 * message id of Meta's shape that carries the allowlisted number — or a green "two 200s" would prove nothing. Runs the
 * real script with python3; if python3 is missing this fails rather than skips.
 */
class WaReplayCallbackScriptTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String NUMBER = "+8801700000000";

    @Test
    void theDryRunBuildsTheRequestTheWebhookAcceptsAndPrintsNoSecretOrNumberOutsideTheBody(@TempDir Path dir) throws Exception {
        Path env = dir.resolve("replay.env");
        Files.writeString(env, String.join("\n",
                "# a comment line, skipped",
                "BAYMAX_WA_APP_SECRET_OLD=not-this-one",
                "BAYMAX_WA_APP_SECRET=" + SECRET,
                "BAYMAX_WA_ALLOWLIST=" + NUMBER + ",+8801700000001",
                ""));
        Path script = Path.of("..", "scripts", "wa-replay-callback.py").toAbsolutePath().normalize();
        assertThat(script).as("the script exists").exists();

        Process p = new ProcessBuilder("python3", "-I", script.toString(), "--env", env.toString(), "--dry-run")
                .directory(dir.toFile()).start();
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).as("the script finished").isTrue();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.exitValue()).as("exit code; stderr: %s", err).isZero();

        JsonNode printed = new ObjectMapper().readTree(out);
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
        assertThat(WaFixtures.decodedDigits(id)).as("a wamid of Meta's shape, carrying the first allowlisted number")
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
        assertThat(err).as("stderr").isEmpty();
    }

    @Test
    void anEnvFileWithoutTheSecretStopsTheScriptAndNamesTheVariableNotItsValue(@TempDir Path dir) throws Exception {
        Path env = dir.resolve("replay.env");
        Files.writeString(env, "BAYMAX_WA_ALLOWLIST=" + NUMBER + "\nBAYMAX_WA_APP_SECRET=\n");
        Path script = Path.of("..", "scripts", "wa-replay-callback.py").toAbsolutePath().normalize();

        Process p = new ProcessBuilder("python3", "-I", script.toString(), "--env", env.toString(), "--dry-run")
                .directory(dir.toFile()).start();
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(p.exitValue()).isNotZero();
        assertThat(err).contains("BAYMAX_WA_APP_SECRET");
        assertThat(out + err).doesNotContain("8801700000000");
    }
}
