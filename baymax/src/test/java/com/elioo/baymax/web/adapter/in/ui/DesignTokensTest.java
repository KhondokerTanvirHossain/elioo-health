package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DR-14 / BMX-6b acceptance: no hardcoded colour or radius outside the token file. The page sources are the
 * Java that renders HTML; the only place a colour, a radius or an rgb() may be written is baymax.css.
 */
class DesignTokensTest {

    private static final Path UI_SOURCES = Path.of("src/main/java/com/elioo/baymax/web/adapter/in/ui");
    private static final Path TOKENS = Path.of("src/main/resources/baymax/ui/baymax.css");
    private static final Pattern LITERAL = Pattern.compile(
            "#[0-9a-fA-F]{3,8}\\b|\\brgba?\\(|\\bhsla?\\(|border-radius\\s*:|\\bcolor\\s*:\\s*[a-z#]|background\\s*:\\s*[a-z#]|font-size\\s*:\\s*[0-9.]+(px|rem|em)");

    @Test
    void noColourOrRadiusLiteralOutsideTheTokenFile() throws IOException {
        try (Stream<Path> files = Files.walk(UI_SOURCES)) {
            List<String> offenders = files.filter(p -> p.toString().endsWith(".java")).flatMap(p -> {
                try {
                    List<String> lines = Files.readAllLines(p);
                    return java.util.stream.IntStream.range(0, lines.size())
                            .filter(i -> LITERAL.matcher(lines.get(i)).find())
                            .mapToObj(i -> p.getFileName() + ":" + (i + 1) + " " + lines.get(i).trim());
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).toList();
            assertThat(offenders).as("style literals belong in baymax.css").isEmpty();
        }
    }

    @Test
    void theTokenFileDefinesEveryTokenThePagesUse() throws IOException {
        String css = Files.readString(TOKENS);
        assertThat(css).contains(":root{");
        Pattern use = Pattern.compile("var\\((--[a-z0-9-]+)\\)");
        Pattern def = Pattern.compile("(--[a-z0-9-]+)\\s*:");
        java.util.Set<String> defined = new java.util.HashSet<>();
        java.util.regex.Matcher d = def.matcher(css);
        while (d.find()) {
            defined.add(d.group(1));
        }
        java.util.regex.Matcher u = use.matcher(css);
        while (u.find()) {
            assertThat(defined).as(u.group(1)).contains(u.group(1));
        }
        // the pages may reference a token inline (spacing only); each must exist
        try (Stream<Path> files = Files.walk(UI_SOURCES)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                java.util.regex.Matcher m = use.matcher(Files.readString(p));
                while (m.find()) {
                    assertThat(defined).as(p.getFileName() + " " + m.group(1)).contains(m.group(1));
                }
            }
        }
    }
}
