package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DR-14 / BMX-6b acceptance: no hardcoded colour or radius outside the token file. The page sources are the
 * Java that renders HTML; the only place a colour, a radius or an rgb() may be written is baymax.css.
 */
class DesignTokensTest {

    private static final Path UI_SOURCES = Path.of("src/main/java/com/elioo/baymax/web/adapter/in/ui");
    private static final WebUiTestKit KIT = new WebUiTestKit();
    /** What the browser receives (CLAUDE.md: assert on the thing sent), never a file on disk. */
    private static final String CSS = KIT.servedText("/assets/baymax.css");
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

    /** R2: the stylesheet is the parts in order, byte for byte; a part added on disk but not listed fails here. */
    @Test
    void theServedStylesheetIsEveryPartInOrder() throws IOException {
        List<Path> onDisk;
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/baymax/ui/css"))) {
            onDisk = files.filter(p -> p.toString().endsWith(".css")).sorted().toList();
        }
        assertThat(onDisk).as("css parts on disk").hasSizeGreaterThanOrEqualTo(9);
        assertThat(onDisk.stream().map(p -> p.getFileName().toString()).toList()).isEqualTo(AssetHandler.CSS_PARTS);
        StringBuilder joined = new StringBuilder();
        for (Path p : onDisk) {
            joined.append(Files.readString(p, StandardCharsets.UTF_8));
        }
        assertThat(CSS).isEqualTo(joined.toString());
    }

    @Test
    void theTokenFileDefinesEveryTokenThePagesUse() throws IOException {
        String css = CSS;
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

    /** Spec §8: red means NOW. The status-now tokens and the wordmark dot appear only under now/critical/emergency selectors. */
    @Test
    void redAppearsOnlyInNowCriticalAndEmergencyRules() {
        Matcher rule = Pattern.compile("([^{}]+)\\{([^{}]*)}").matcher(Tokens.rules(CSS));
        Pattern red = Pattern.compile("var\\(--c-(status-now[a-z-]*|on-status-now|brand-dot)\\)");
        Pattern allowed = Pattern.compile("\\.(now|critical|emergency)\\b|\\.brand \\.dot");
        int redRules = 0;
        List<String> offenders = new ArrayList<>();
        while (rule.find()) {
            Matcher uses = red.matcher(rule.group(2));
            if (!uses.find()) {
                continue;
            }
            redRules++;
            for (String selector : rule.group(1).split(",")) {
                if (!allowed.matcher(selector).find()) {
                    offenders.add(selector.trim() + " uses " + uses.group());
                }
            }
        }
        assertThat(redRules).as("rules using the red tokens (an empty parse would pass vacuously)").isPositive();
        assertThat(offenders).as("red is reserved for NOW").isEmpty();
    }

    /** DR-14 inside the stylesheet: a colour literal lives in a token block, never in a rule. */
    @Test
    void colourLiteralsLiveOnlyInTheTokenBlocks() {
        String rules = Tokens.rules(CSS);
        assertThat(rules.length()).as("rules remain after removing the token blocks").isGreaterThan(5_000);
        assertThat(Pattern.compile("#[0-9a-fA-F]{3,8}\\b|rgba?\\(|hsla?\\(").matcher(rules).results().map(MatchResult::group).toList()).isEmpty();
    }
}
