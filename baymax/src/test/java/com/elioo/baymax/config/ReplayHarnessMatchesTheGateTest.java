package com.elioo.baymax.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code scripts/replay-extraction-gate.py} replays every stored extraction through a MODEL of the retake gate. A
 * model that is not the deployed gate answers a question nobody asked, and every number it prints looks like
 * evidence. After DR-27 (#52) the harness still modelled the pre-DR-27 gate: emptiness on
 * {@code prescription -> [medicines]} and confidence over every non-empty section, {@code clinical_context}
 * included — so it could not see a prescription with no follow-up being retaken, the defect it existed to catch.
 *
 * <p>The harness's tables and thresholds are read out of the script and compared with the
 * {@link BaymaxProperties.Extract} defaults. Not only the tables by name: the table the harness's {@code current_gate}
 * actually iterates for its emptiness check, and the table its confidence check actually reads, because a harness
 * that defines the right dict and never uses it would pass a name check (CLAUDE.md: a check that cannot fail for the
 * reason it exists). Every extraction is asserted non-empty first, so a renamed variable cannot pass vacuously.</p>
 *
 * <p>This reads the source, so it cannot say what the harness's functions DO with the tables: a {@code lowest_gated}
 * that ignored its table kept every check here green. What the harness decides is compared with what the gate
 * decides, case by case, in {@code DocumentExtractionServiceTest.theReplayHarnessDecidesEveryCaseAsTheGateDoes}.</p>
 *
 * <p>Limit: "deployed" here means the {@code new BaymaxProperties()} defaults, not the bound production config. It is
 * honest only while no {@code BAYMAX_EXTRACT_GATING_SECTIONS*} / {@code BAYMAX_EXTRACT_EXPECTED_SECTIONS*} (or
 * {@code baymax.extract.*-sections}) override exists anywhere — none does in the repo; the server env is checked by
 * hand before a gate change merges.</p>
 */
class ReplayHarnessMatchesTheGateTest {

    private static final Path HARNESS = Path.of("../scripts/replay-extraction-gate.py");

    private static String harness() throws IOException {
        assertThat(HARNESS).as("the replay harness is where this test expects it").isRegularFile();
        return Files.readString(HARNESS, StandardCharsets.UTF_8);
    }

    /** A top-level {@code NAME = { "type": ["a", "b"], ... }} dict, or an empty map when there is none. */
    private static Map<String, List<String>> dict(String source, String name) {
        Matcher block = Pattern.compile("(?ms)^" + Pattern.quote(name) + "\\s*=\\s*\\{(.*?)^}").matcher(source);
        Map<String, List<String>> parsed = new LinkedHashMap<>();
        if (!block.find()) {
            return parsed;
        }
        Matcher entry = Pattern.compile("\"(\\w+)\"\\s*:\\s*\\[(.*?)]").matcher(block.group(1));
        while (entry.find()) {
            List<String> sections = new ArrayList<>();
            Matcher item = Pattern.compile("\"(\\w+)\"").matcher(entry.group(2));
            while (item.find()) {
                sections.add(item.group(1));
            }
            parsed.put(entry.group(1), sections);
        }
        return parsed;
    }

    /** The body of a top-level {@code def name(...)}: everything up to the next line that is not indented. */
    private static String function(String source, String name) {
        Matcher body = Pattern.compile("(?ms)^def " + Pattern.quote(name) + "\\(.*?(?=^\\S|\\z)").matcher(source);
        String found = body.find() ? body.group() : "";
        assertThat(found).as("the harness defines %s()", name).contains("return");
        return found;
    }

    /** The first capture of {@code pattern} in {@code text}, asserted present. */
    private static String captured(String text, String pattern, String description) {
        Matcher m = Pattern.compile(pattern).matcher(text);
        assertThat(m.find()).as(description).isTrue();
        return m.group(1);
    }

    private static double constant(String source, String name) {
        return Double.parseDouble(captured(source, "(?m)^" + Pattern.quote(name) + "\\s*=\\s*([0-9.]+)",
                "the harness defines " + name));
    }

    private static void sameTable(Map<String, List<String>> harness, Map<String, List<String>> deployed,
                                  String what) {
        assertThat(deployed).as("the deployed %s table", what).isNotEmpty();
        assertThat(harness).as("the harness's %s table (read out of the script)", what).isNotEmpty();
        assertThat(harness.keySet()).as("document types in the harness's %s table", what)
                .isEqualTo(deployed.keySet());
        for (var type : deployed.entrySet()) {
            assertThat(harness.get(type.getKey())).as("%s for %s", what, type.getKey())
                    .isEqualTo(type.getValue());
        }
    }

    private static BaymaxProperties.Extract deployed() {
        return new BaymaxProperties().getExtract();
    }

    @Test
    void theThresholdsAreTheDeployedOnes() throws IOException {
        String source = harness();
        assertThat(constant(source, "MIN_CONFIDENCE_OVERALL")).isEqualTo(deployed().getMinConfidenceOverall());
        assertThat(constant(source, "MIN_CONFIDENCE_SECTION")).isEqualTo(deployed().getMinConfidenceSection());
    }

    @Test
    void gatingSectionsAreTheDeployedOnes() throws IOException {
        sameTable(dict(harness(), "GATING_SECTIONS"), deployed().getGatingSections(), "gating sections");
    }

    @Test
    void expectedSectionsAreTheDeployedOnes() throws IOException {
        sameTable(dict(harness(), "EXPECTED_SECTIONS"), deployed().getExpectedSections(), "expected sections");
    }

    /**
     * The table the deployed gate checks for emptiness: {@code missingExpectedSection} reads expectedSections (RX).
     * Before RX it read gatingSections, and this test, then comparing against that table, was what caught the
     * harness still modelling the pre-DR-27 gate.
     */
    @Test
    void theCurrentModelChecksEmptinessOnTheTableTheDeployedGateDoes() throws IOException {
        String source = harness();
        String table = captured(function(source, "current_gate"), "for section in (\\w+)\\.get\\(",
                "current_gate checks emptiness by iterating a sections table");
        sameTable(dict(source, table), deployed().getExpectedSections(), "emptiness");
    }

    /** The table the deployed gate reads for confidence: {@code lowestPresentSection} reads gatingSections. */
    @Test
    void theCurrentModelGatesConfidenceOnlyOnTheGatingSections() throws IOException {
        String source = harness();
        String table = captured(function(source, "current_gate"), "lowest_gated\\(sections,\\s*(\\w+)\\.get\\(",
                "current_gate takes the lowest confidence over the gating sections only, not every section "
                        + "(clinical_context gates nothing since DR-27)");
        sameTable(dict(source, table), deployed().getGatingSections(), "confidence");
    }
}
