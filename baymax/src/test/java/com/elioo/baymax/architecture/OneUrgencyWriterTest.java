package com.elioo.baymax.architecture;

import com.elioo.baymax.outbound.application.port.out.DocumentUrgencyPort;
import com.elioo.baymax.outbound.application.service.DocumentFacts;
import com.elioo.baymax.outbound.application.service.UrgencyService;
import com.elioo.baymax.outbound.domain.UrgencyAssessment;
import com.elioo.baymax.outbound.domain.UrgencyContext;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * F1/F2: one writer. Only StoredUrgencyService assesses and raises; only PostgresDocumentUrgencyAdapter (and the
 * migrations that created the columns) writes the urgency and verdict columns. The trigger stops a lowering; this stops
 * a second writer raising from the wrong source.
 */
class OneUrgencyWriterTest {

    private static final JavaClasses MAIN = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.elioo.baymax");
    private static final Pattern LITERAL = Pattern.compile("\"\"\"(.*?)\"\"\"|\"((?:[^\"\\\\\\n]|\\\\.)*)\"", Pattern.DOTALL);
    private static final Pattern WRITES = Pattern.compile(
            "(?is)\\bdocument\\b(?:\\s+\\w+)?\\s+SET\\b[^;]*?\\b(urgency|urgency_reasons|urgency_assessed_at)\\s*="
                    + "|\\bobservation\\b(?:\\s+\\w+)?\\s+SET\\b[^;]*?\\bverdict[a-z_]*\\s*="
                    + "|\\bdocument\\s*\\([^)]*\\burgency|\\bobservation\\s*\\([^)]*\\bverdict");
    private static final Set<String> ALLOWED = Set.of("PostgresDocumentUrgencyAdapter.java",
            "V18__document_stored_urgency.sql", "V19__observation_value_verdict.sql");

    @Test
    void onlyTheWriterAssesses() {
        assertThat(MAIN.contain(UrgencyService.class)).as("the importer sees the main classes").isTrue();
        noClasses().that().doNotHaveSimpleName("StoredUrgencyService").and().doNotHaveSimpleName("UrgencyService")
                .should().callMethod(UrgencyService.class, "assess", DocumentFacts.class)
                .orShould().callMethod(UrgencyService.class, "assess", DocumentFacts.class, UrgencyContext.class)
                .because("F1: every assessment goes through the one writer that stores max(old, new)").check(MAIN);
    }

    @Test
    void onlyTheWriterRaises() {
        noClasses().that().doNotHaveSimpleName("StoredUrgencyService")
                .should().callMethod(DocumentUrgencyPort.class, "raise", UUID.class, UrgencyAssessment.class, Instant.class)
                .because("F1: one writer, so the stored level always comes from UrgencyService.assess").check(MAIN);
    }

    @Test
    void onlyTheAdapterAndItsMigrationsWriteTheColumns() throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Stream.concat(Files.walk(Path.of("src/main/java")), Files.walk(Path.of("src/main/resources")))) {
            files = walk.filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".sql")).toList();
        }
        assertThat(files).hasSizeGreaterThan(100);
        List<String> found = new ArrayList<>();
        int allowedWrites = 0;
        for (Path p : files) {
            String text = Files.readString(p);
            List<String> sql = new ArrayList<>();
            if (p.toString().endsWith(".sql")) {
                sql.add(text);
            } else {
                for (Matcher l = LITERAL.matcher(text); l.find(); ) {
                    sql.add(l.group(1) != null ? l.group(1) : l.group(2));
                }
            }
            for (String s : sql) {
                Matcher w = WRITES.matcher(s);
                while (w.find()) {
                    if (ALLOWED.contains(p.getFileName().toString())) {
                        allowedWrites++;
                    } else {
                        found.add(p.getFileName() + ": " + w.group().replaceAll("\\s+", " "));
                    }
                }
            }
        }
        assertThat(allowedWrites).as("the sweep sees the writer's own writes (a pattern that sees nothing proves nothing)").isPositive();
        assertThat(found).isEmpty();
    }
}
