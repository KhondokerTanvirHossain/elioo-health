package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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

    /** The tokens that paint the NOW states and the wordmark dot: red by name. */
    private static final Set<String> NAMED_RED = Set.of("--c-status-now", "--c-status-now-container", "--c-status-now-solid", "--c-brand-dot");

    /**
     * Red is decided by value, not by name: every colour token whose value, in light or in dark, is one of the named
     * red values or has a red hue (within 15° of 0°, HSL saturation ≥ 0.4). A token added under another name with a red
     * value is still red.
     */
    static Set<String> redTokens(String css) {
        Map<String, String> light = Tokens.light(css), dark = Tokens.dark(css);
        Set<String> redValues = new HashSet<>();
        for (String t : NAMED_RED) {
            redValues.add(light.get(t).toLowerCase());
            redValues.add(dark.get(t).toLowerCase());
        }
        Set<String> red = new TreeSet<>();
        for (Map<String, String> theme : List.of(light, dark)) {
            theme.forEach((token, value) -> {
                if (token.startsWith("--c-") && value.startsWith("#") && (redValues.contains(value.toLowerCase()) || redHue(value))) {
                    red.add(token);
                }
            });
        }
        return red;
    }

    static boolean redHue(String hex) {
        double r = Integer.parseInt(hex.substring(1, 3), 16) / 255.0, g = Integer.parseInt(hex.substring(3, 5), 16) / 255.0,
                b = Integer.parseInt(hex.substring(5, 7), 16) / 255.0;
        double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min, l = (max + min) / 2;
        if (d == 0 || max != r) {
            return false;
        }
        double sat = d / (1 - Math.abs(2 * l - 1));
        double hue = 60 * (((g - b) / d) % 6);
        hue = hue < 0 ? hue + 360 : hue;
        return sat >= 0.4 && (hue <= 15 || hue >= 345);
    }

    /** Spec §8: red means NOW. Every red token (by value) appears only under now/critical/emergency selectors. */
    @Test
    void redAppearsOnlyInNowCriticalAndEmergencyRules() {
        Set<String> red = redTokens(CSS);
        assertThat(red).as("red tokens derived from the values").containsAll(NAMED_RED);
        Matcher rule = Pattern.compile("([^{}]+)\\{([^{}]*)}").matcher(Tokens.rules(CSS));
        Pattern uses = Pattern.compile("var\\((--c-[a-z0-9-]+)\\)");
        Pattern allowed = Pattern.compile("\\.(now|critical|emergency)\\b");
        int redRules = 0;
        List<String> offenders = new ArrayList<>();
        while (rule.find()) {
            List<String> used = uses.matcher(rule.group(2)).results().map(m -> m.group(1)).filter(red::contains).toList();
            if (used.isEmpty()) {
                continue;
            }
            redRules++;
            for (String selector : rule.group(1).split(",")) {
                if (!allowed.matcher(selector).find()) {
                    offenders.add(selector.trim() + " uses " + used);
                }
            }
        }
        assertThat(redRules).as("rules using the red tokens (an empty parse would pass vacuously)").isPositive();
        assertThat(offenders).as("red is reserved for NOW").isEmpty();
    }

    /** CSS named and system colours: a colour written as a word is still a literal. */
    private static final Pattern NAMED_COLOUR = Pattern.compile("(?i)(?<![-\\w])(aliceblue|antiquewhite|aqua|aquamarine|azure|beige|bisque|black|blanchedalmond|blue|blueviolet|brown|burlywood|cadetblue|chartreuse|chocolate|coral|cornflowerblue|cornsilk|crimson|cyan|darkblue|darkcyan|darkgoldenrod|darkgray|darkgreen|darkgrey|darkkhaki|darkmagenta|darkolivegreen|darkorange|darkorchid|darkred|darksalmon|darkseagreen|darkslateblue|darkslategray|darkslategrey|darkturquoise|darkviolet|deeppink|deepskyblue|dimgray|dimgrey|dodgerblue|firebrick|floralwhite|forestgreen|fuchsia|gainsboro|ghostwhite|gold|goldenrod|gray|green|greenyellow|grey|honeydew|hotpink|indianred|indigo|ivory|khaki|lavender|lavenderblush|lawngreen|lemonchiffon|lightblue|lightcoral|lightcyan|lightgoldenrodyellow|lightgray|lightgreen|lightgrey|lightpink|lightsalmon|lightseagreen|lightskyblue|lightslategray|lightslategrey|lightsteelblue|lightyellow|lime|limegreen|linen|magenta|maroon|mediumaquamarine|mediumblue|mediumorchid|mediumpurple|mediumseagreen|mediumslateblue|mediumspringgreen|mediumturquoise|mediumvioletred|midnightblue|mintcream|mistyrose|moccasin|navajowhite|navy|oldlace|olive|olivedrab|orange|orangered|orchid|palegoldenrod|palegreen|paleturquoise|palevioletred|papayawhip|peachpuff|peru|pink|plum|powderblue|purple|rebeccapurple|red|rosybrown|royalblue|saddlebrown|salmon|sandybrown|seagreen|seashell|sienna|silver|skyblue|slateblue|slategray|slategrey|snow|springgreen|steelblue|tan|teal|thistle|tomato|turquoise|violet|wheat|white|whitesmoke|yellow|yellowgreen|accentcolor|accentcolortext|activetext|buttonborder|buttonface|buttontext|canvas|canvastext|field|fieldtext|graytext|highlight|highlighttext|linktext|mark|marktext|selecteditem|selecteditemtext|visitedtext)(?![-\\w])");

    /**
     * DR-14 inside the stylesheet: a colour literal lives in a token block, never in a rule — hex, rgb(), hsl() and a
     * colour written as a CSS colour word ({@code color:white}) alike. Values are read with var(), url() and quoted
     * strings removed, so a token or a font name is never mistaken for one.
     */
    @Test
    void colourLiteralsLiveOnlyInTheTokenBlocks() {
        String rules = Tokens.rules(CSS);
        assertThat(rules.length()).as("rules remain after removing the token blocks").isGreaterThan(5_000);
        assertThat(Pattern.compile("#[0-9a-fA-F]{3,8}\\b|rgba?\\(|hsla?\\(").matcher(rules).results().map(MatchResult::group).toList()).isEmpty();
        List<String> words = new ArrayList<>();
        int values = 0;
        for (Css.Rule r : Css.rules(CSS)) {
            for (Map.Entry<String, String> d : r.decls().entrySet()) {
                values++;
                String v = d.getValue().replaceAll("var\\([^)]*\\)|url\\([^)]*\\)|\"[^\"]*\"|'[^']*'", " ");
                NAMED_COLOUR.matcher(v).results().forEach(m -> words.add(r.selector() + " " + d.getKey() + ":" + m.group()));
            }
        }
        assertThat(values).as("declarations read").isGreaterThan(300);
        assertThat(words).as("colours written as words").isEmpty();
    }
}
