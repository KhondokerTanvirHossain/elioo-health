package com.elioo.baymax.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every FAILED a document can reach is written by DocumentExtractionService (whose result DocumentProcessingChain.run
 * explains) or by DocumentProcessingChain.fail. Both lead to ExplanationService.explain, which answers it with the
 * failure notice (PR 9). A new writer anywhere else would end a document in silence: route it through the chain
 * instead. Derived from the source tree, not from a list of the writers someone remembered.
 */
class FailedHasOneWriterTest {

    private static final Pattern WRITES_FAILED = Pattern.compile(
            "withStatus\\(\\s*(Document\\.)?Status\\.FAILED|finish\\([^;]*?(Document\\.)?Status\\.FAILED|new Document\\([^;]*?Status\\.FAILED");

    @Test
    void documentFailedIsWrittenOnlyByTheExtractionAndTheProcessingChain() throws IOException {
        List<String> writers;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            writers = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            return WRITES_FAILED.matcher(Files.readString(p)).find();
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .map(p -> p.getFileName().toString()).sorted().toList();
        }
        assertThat(writers).as("the scan found the two known writers (vacuity guard) and nothing else")
                .containsExactly("DocumentExtractionService.java", "DocumentProcessingChain.java");
    }
}
