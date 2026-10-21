package com.elioo.baymax.web.adapter.in;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rebuild plan B2, the source half of the {@link ImageRefs} guard: no Java string literal outside {@code ImageRefs.java}
 * builds a page or crop href — one ending in {@code /page/} or carrying {@code /image?key=}. A route template such as
 * {@code "/documents/{id}/page/{n}"} is not an href and is not matched. Comment lines (javadoc mentions the route) are
 * skipped. The scan asserts it read files and literals, so it cannot pass on an empty tree.
 */
class ImageRefsAreTheOnlyImageHrefBuilderTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");

    @Test
    void noImageHrefIsBuiltOutsideImageRefs() throws IOException {
        List<Path> sources;
        try (Stream<Path> walk = Files.walk(MAIN)) {
            sources = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        assertThat(sources).as("Java sources under " + MAIN.toAbsolutePath()).hasSizeGreaterThan(50);
        assertThat(sources).as("ImageRefs itself is among them").anyMatch(p -> p.endsWith(Path.of("web/adapter/in/ImageRefs.java")));

        List<String> offending = new ArrayList<>();
        int literals = 0;
        for (Path source : sources) {
            if (source.endsWith(Path.of("web/adapter/in/ImageRefs.java"))) {
                continue;
            }
            List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).strip();
                if (line.startsWith("*") || line.startsWith("//") || line.startsWith("/*")) {
                    continue;
                }
                Matcher m = LITERAL.matcher(line);
                while (m.find()) {
                    literals++;
                    String text = m.group(1);
                    if (text.endsWith("/page/") || text.contains("/image?key=")) {
                        offending.add(MAIN.relativize(source) + ":" + (i + 1) + " " + m.group());
                    }
                }
            }
        }
        assertThat(literals).as("string literals scanned").isGreaterThan(1000);
        assertThat(offending).as("image hrefs built outside ImageRefs").isEmpty();
    }
}
