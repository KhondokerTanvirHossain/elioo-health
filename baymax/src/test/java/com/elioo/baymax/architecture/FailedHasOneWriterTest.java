package com.elioo.baymax.architecture;

import com.elioo.baymax.extraction.application.service.DocumentExtractionService;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every FAILED a document can reach is written by DocumentExtractionService.process (whose result
 * DocumentProcessingChain.run explains) or by DocumentProcessingChain.fail. Both lead to ExplanationService.explain,
 * which answers it with the failure notice (PR 9). A new writer anywhere else — or a second one inside either file, or a
 * caller of {@code process} that skips the chain — would end a document in silence: route it through the chain
 * instead. Derived from the source tree and the bytecode, not from a list of the writers someone remembered.
 */
class FailedHasOneWriterTest {

    /** A Java status write: qualified, statically imported, or through the constructor. */
    static final Pattern JAVA_WRITES_FAILED = Pattern.compile(
            "withStatus\\(\\s*(?:Document\\.)?(?:Status\\.)?FAILED\\b"
                    + "|\\bfinish\\([^;]*?\\b(?:Document\\.)?(?:Status\\.)?FAILED\\b"
                    + "|new Document\\([^;]*?\\b(?:Document\\.)?(?:Status\\.)?FAILED\\b");
    /** A SQL status write: an UPDATE's SET clause (not its WHERE), or an INSERT carrying the literal. */
    static final Pattern SQL_WRITES_FAILED = Pattern.compile(
            "(?is)\\bSET\\b(?:(?!\\bWHERE\\b)[^;])*?\\bstatus\\s*=\\s*'FAILED'|\\bINSERT\\s+INTO\\b[^;]*?'FAILED'");

    private static final JavaClasses MAIN = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.elioo.baymax");

    @Test
    void documentFailedIsWrittenExactlyOnceInTheExtractionAndOnceInTheProcessingChain() throws IOException {
        assertThat(count(Path.of("src/main/java"), ".java", JAVA_WRITES_FAILED))
                .as("one write in DocumentExtractionService.process, one in DocumentProcessingChain.fail (and the scan saw them)")
                .isEqualTo(Map.of("DocumentExtractionService.java", 1, "DocumentProcessingChain.java", 1));
    }

    @Test
    void noSqlWritesFailedBehindTheDomain() throws IOException {
        Map<String, Integer> writers = new TreeMap<>(count(Path.of("src/main/java"), ".java", SQL_WRITES_FAILED));
        writers.putAll(count(Path.of("src/main/resources"), ".sql", SQL_WRITES_FAILED));
        assertThat(writers).as("an UPDATE … SET status = 'FAILED' never meets the failure notice").isEmpty();
    }

    /** The emptiness guard for the SQL check: the pattern sees every spelling of a write, and not a read. */
    @Test
    void theSqlPatternSeesAWriteAndNotARead() {
        assertThat(SQL_WRITES_FAILED.matcher("UPDATE baymax.document SET status = 'FAILED' WHERE id = :id").find()).isTrue();
        assertThat(SQL_WRITES_FAILED.matcher("update document set updated_at = now(), status='FAILED' where id = :id").find()).isTrue();
        assertThat(SQL_WRITES_FAILED.matcher("INSERT INTO baymax.document (id, status) VALUES (:id, 'FAILED')").find()).isTrue();
        assertThat(SQL_WRITES_FAILED.matcher("OR (d.status = 'FAILED' AND NOT EXISTS (SELECT 1 FROM m))").find()).isFalse();
        assertThat(SQL_WRITES_FAILED.matcher("UPDATE x SET a = 1 WHERE status = 'FAILED'").find()).isFalse();
        assertThat(JAVA_WRITES_FAILED.matcher("d.withStatus(FAILED, \"x\", now)").find()).as("static import").isTrue();
        assertThat(JAVA_WRITES_FAILED.matcher("finish(document, Status.FAILED, r, null)").find()).isTrue();
        assertThat(JAVA_WRITES_FAILED.matcher("d.status() == Document.Status.FAILED").find()).as("a read").isFalse();
    }

    /**
     * The other half of the invariant: process() writes FAILED, so whoever calls it must explain afterwards. Only the
     * chain does; an admin re-extract or eval route calling it directly would fail a document with no reply.
     */
    @Test
    void onlyTheProcessingChainCallsTheExtraction() {
        Set<String> callers = new TreeSet<>();
        MAIN.forEach(c -> {
            for (JavaCodeUnitAccess<?> access : c.getCodeUnitAccessesFromSelf()) {
                if (access.getTarget().getOwner().isEquivalentTo(DocumentExtractionService.class)
                        && access.getTarget().getName().equals("process")
                        && !access.getOriginOwner().isEquivalentTo(DocumentExtractionService.class)) {
                    callers.add(access.getOriginOwner().getSimpleName());
                }
            }
        });
        assertThat(callers).as("exactly one caller (vacuity guard), and it is the chain").containsExactly("DocumentProcessingChain");
    }

    private static Map<String, Integer> count(Path root, String suffix, Pattern pattern) throws IOException {
        Map<String, Integer> counts = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : files.filter(f -> f.toString().endsWith(suffix)).toList()) {
                Matcher m = pattern.matcher(Files.readString(p));
                int n = 0;
                while (m.find()) {
                    n++;
                }
                if (n > 0) {
                    counts.merge(p.getFileName().toString(), n, Integer::sum);
                }
            }
        }
        return counts;
    }
}
