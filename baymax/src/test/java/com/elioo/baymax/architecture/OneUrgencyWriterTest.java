package com.elioo.baymax.architecture;

import com.elioo.baymax.outbound.application.port.out.DocumentUrgencyPort;
import com.elioo.baymax.outbound.application.service.UrgencyService;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
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
            "(?is)\\bdocument\\b(?:\\s+(?:AS\\s+)?\\w+)?\\s+SET\\b[^;]*?\\b(urgency|urgency_reasons|urgency_assessed_at)\\s*="
                    + "|\\bobservation\\b(?:\\s+(?:AS\\s+)?\\w+)?\\s+SET\\b[^;]*?\\bverdict[a-z_]*\\s*="
                    + "|\\bdocument\\s*\\([^)]*\\burgency|\\bobservation\\s*\\([^)]*\\bverdict");
    private static final Set<String> ALLOWED = Set.of("PostgresDocumentUrgencyAdapter.java",
            "V18__document_stored_urgency.sql", "V19__observation_value_verdict.sql");

    @Test
    void onlyTheWriterAssesses() {
        assertThat(MAIN.contain(UrgencyService.class)).as("the importer sees the main classes").isTrue();
        classes().that().doNotHaveSimpleName("StoredUrgencyService").and().doNotHaveSimpleName("UrgencyService")
                .should(notAccess(UrgencyService.class, "assess"))
                .because("F1: every assessment goes through the one writer that stores max(old, new)").check(MAIN);
    }

    @Test
    void onlyTheWriterRaises() {
        classes().that().doNotHaveSimpleName("StoredUrgencyService")
                .should(notAccess(DocumentUrgencyPort.class, "raise"))
                .because("F1: one writer, so the stored level always comes from UrgencyService.assess").check(MAIN);
    }

    /**
     * Every overload of {@code owner.name}, called OR referenced. ArchUnit's {@code callMethod} sees only direct calls,
     * so {@code .map(urgency::assess)} — the ordinary Reactor spelling — slipped past it; a code-unit access covers both.
     */
    private static ArchCondition<JavaClass> notAccess(Class<?> owner, String name) {
        return new ArchCondition<>("not call or reference " + owner.getSimpleName() + "." + name) {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (JavaCodeUnitAccess<?> access : item.getCodeUnitAccessesFromSelf()) {
                    if (access.getTarget().getOwner().isEquivalentTo(owner) && access.getTarget().getName().equals(name)) {
                        events.add(SimpleConditionEvent.violated(access, access.getDescription()));
                    }
                }
            }
        };
    }

    /** The pattern itself, both directions: every spelling of a write is seen; a read, or the attempt stamp, is not. */
    @Test
    void thePatternSeesEveryWayToSpellAWriteAndNoRead() {
        assertThat(List.of(
                "UPDATE baymax.document SET urgency = 'now' WHERE id = :id",
                "UPDATE %1$s.document d SET urgency_reasons = '[]'::jsonb",
                "UPDATE %1$s.document AS d SET\n    urgency_assessed_at = :at",
                "update baymax.document set status = 'DONE', urgency = :u",
                "INSERT INTO baymax.document (id, patient_id, urgency) VALUES (:id, :p, :u)",
                "UPDATE baymax.observation o SET verdict_status = :v"))
                .allSatisfy(write -> assertThat(WRITES.matcher(write).find()).as(write).isTrue());
        assertThat(List.of(
                "SELECT id, urgency, urgency_reasons::text AS reasons FROM baymax.document WHERE id = :id",
                "UPDATE baymax.document SET urgency_attempted_at = :at WHERE id = :id",
                "UPDATE baymax.document SET status = :s, updated_at = :at WHERE id = :id AND urgency IS NULL"))
                .allSatisfy(read -> assertThat(WRITES.matcher(read).find()).as(read).isFalse());
    }

    @Test
    void onlyTheAdapterAndItsMigrationsWriteTheColumns() throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Stream.concat(Files.walk(Path.of("src/main/java")), Files.walk(Path.of("src/main/resources")))) {
            files = walk.filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".sql")).toList();
        }
        assertThat(files).hasSizeGreaterThan(100);
        List<String> found = new ArrayList<>();
        Map<String, Integer> allowedWrites = new HashMap<>();
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
                        allowedWrites.merge(p.getFileName().toString(), 1, Integer::sum);
                    } else {
                        found.add(p.getFileName() + ": " + w.group().replaceAll("\\s+", " "));
                    }
                }
            }
        }
        // the writer's OWN writes, not just V18's: a migration's UPDATE alone would keep this green after the adapter's
        // SQL had been reworded past the pattern, and then a second writer spelled the same way would be invisible too
        assertThat(allowedWrites).as("the sweep sees the writer's own writes (a pattern that sees nothing proves nothing)")
                .containsKey("PostgresDocumentUrgencyAdapter.java");
        assertThat(found).isEmpty();
    }
}
