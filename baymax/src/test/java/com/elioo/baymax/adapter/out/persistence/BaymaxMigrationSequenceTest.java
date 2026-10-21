package com.elioo.baymax.adapter.out.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Baymax migrations are numbered 1..n with no gap and no number twice, read from the location
 * {@link BaymaxSchemaMigrator} migrates from.
 *
 * <p>Why a gap is a production outage, not a style point: the migrator runs Flyway with its defaults
 * ({@code outOfOrder=false}). If V25 is applied in production while V24 is still on a branch, the later merge of V24
 * makes it a resolved migration below the applied version, Flyway's {@code validate} refuses it, and the next startup
 * of the deployable fails. Every merge to main deploys, so a gapped tree must not go green. Two files with one number
 * fail Flyway at startup too ("more than one migration with version"); here they fail the build first, with both
 * names. The check that a branch's new migration is above everything on {@code origin/main} needs git and lives in
 * {@code scripts/check-migration-order.sh} (CI, before the build).
 */
class BaymaxMigrationSequenceTest {

    private static final Pattern VERSIONED = Pattern.compile("V(\\d+)__.+\\.sql");

    @Test
    void theMigrationsAreNumberedOneToNWithNoGapAndNoNumberTwice() throws Exception {
        Resource[] found = new PathMatchingResourcePatternResolver()
                .getResources(BaymaxSchemaMigrator.LOCATION.replace("classpath:", "classpath*:") + "/*.sql");
        Map<Integer, List<String>> byVersion = new TreeMap<>();
        for (Resource r : found) {
            Matcher m = VERSIONED.matcher(r.getFilename());
            assertThat(m.matches()).as("every file in the location is a versioned migration: " + r.getFilename()).isTrue();
            byVersion.computeIfAbsent(Integer.parseInt(m.group(1)), v -> new ArrayList<>()).add(r.getFilename());
        }
        // a location that resolved nothing would pass both checks below vacuously
        assertThat(byVersion).as("migrations found at " + BaymaxSchemaMigrator.LOCATION).containsKey(1).hasSizeGreaterThanOrEqualTo(23);

        assertThat(byVersion.entrySet().stream().filter(e -> e.getValue().size() > 1)
                .map(e -> "V" + e.getKey() + " " + e.getValue()).toList())
                .as("versions taken by more than one file (renumber the later one to max+1)").isEmpty();

        int highest = ((TreeMap<Integer, List<String>>) byVersion).lastKey();
        List<Integer> missing = IntStream.rangeClosed(1, highest).filter(v -> !byVersion.containsKey(v)).boxed().toList();
        assertThat(missing).as("versions missing below V" + highest + " ("
                        + byVersion.keySet().stream().map(String::valueOf).collect(Collectors.joining(",")) + "): "
                        + "renumber to the next free version on origin/main")
                .isEmpty();
    }
}
