package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.testsupport.Snapshot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fictional names medioo-web's component catalog renders ({@code /app/catalog-test}, a catalog build only), written
 * here so that no Bangla is typed in TypeScript (plan W2c; master plan Global Constraints). Byte-compared with the
 * committed {@code medioo-web/fixtures/catalog/names.json} through {@link Snapshot#matches}, as
 * {@link WebCopyExportTest} does for the copy.
 *
 * <p>The long names exist for the overflow check ({@code catalog.spec.ts › noElementOverflowsItsBox}): {@code long} is a
 * spaced name, {@code long_unbroken} the same name with its spaces removed and repeated past 40 code points, which can
 * wrap only inside a word. It is computed here, so nobody types it. Every name is fictional.
 */
class CatalogFixturesExportTest {

    static final Path COMMITTED = Path.of("../medioo-web/fixtures/catalog");
    static final Path WRITTEN = Path.of("build/catalog-fixtures");

    static final String SHORT = "মা";
    static final String LONG = "মোছাম্মৎ রোকেয়া বেগম চৌধুরী";
    static final String ENGLISH = "Ma";
    static final int UNBROKEN_MIN_CODE_POINTS = 40;

    /** {@link #LONG} without its spaces, repeated whole until it is at least {@link #UNBROKEN_MIN_CODE_POINTS} long. */
    static String longUnbroken() {
        String word = LONG.replaceAll("\\s+", "");
        StringBuilder out = new StringBuilder(word);
        while (out.codePointCount(0, out.length()) < UNBROKEN_MIN_CODE_POINTS) {
            out.append(word);
        }
        return out.toString();
    }

    static Map<String, String> files() {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("short", SHORT);
        names.put("long", LONG);
        names.put("long_unbroken", longUnbroken());
        names.put("english", ENGLISH);
        Map<String, Object> file = new TreeMap<>();
        file.put("_meta", Snapshot.meta(CatalogFixturesExportTest.class,
                "fictional catalog names; long_unbroken is long without spaces, repeated to at least "
                        + UNBROKEN_MIN_CODE_POINTS + " code points"));
        file.put("names", names);
        return Map.of("names.json", Snapshot.json(file));
    }

    @Test
    void theCatalogNamesAreTheCommittedFixture() {
        files().forEach((name, content) -> Snapshot.matches(COMMITTED.resolve(name), WRITTEN.resolve(name), content,
                "cp baymax/" + WRITTEN.resolve(name) + " medioo-web/fixtures/catalog/" + name));
    }

    @Test
    void theUnbrokenNameHasNoSpaceAndIsLong() {
        String unbroken = longUnbroken();
        assertThat(unbroken).as("no whitespace at all: it can wrap only inside a word").doesNotContainPattern("\\s");
        assertThat(unbroken.codePointCount(0, unbroken.length())).isGreaterThanOrEqualTo(UNBROKEN_MIN_CODE_POINTS);
        // It is the long name's letters, not some other string: the spaced name is a part of what the test wrote.
        assertThat(unbroken).startsWith(LONG.replace(" ", ""));
        assertThat(LONG).as("the spaced long name has spaces to wrap at").contains(" ");
    }

    @Test
    void everyCommittedCatalogFixtureIsOneJavaWrites() throws IOException {
        Set<String> committed;
        try (Stream<Path> listing = Files.list(COMMITTED)) {
            committed = listing.map(p -> p.getFileName().toString()).collect(Collectors.toCollection(TreeSet::new));
        }
        assertThat(committed).as("the listing of " + COMMITTED + " (an empty listing would pass vacuously)").isNotEmpty();
        assertThat(committed).as("files in " + COMMITTED + " that no Java generator writes; delete them")
                .isEqualTo(new TreeSet<>(files().keySet()));
    }
}
