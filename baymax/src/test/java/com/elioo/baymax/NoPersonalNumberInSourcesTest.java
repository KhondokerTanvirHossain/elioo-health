package com.elioo.baymax;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** docs/HISTORY_REWRITE.md: a personal number was purged from history. Every test source is checked, not a list. */
class NoPersonalNumberInSourcesTest {

    /** Spelled in pieces so this file does not itself contain what it forbids. */
    private static final List<String> FORBIDDEN = List.of("17933" + "99171", "৭৯৩৩" + "৯৯১৭১", "****" + "9171");

    @Test
    void noSourceOrTestFileCarriesThePurgedNumber() throws IOException {
        List<Path> roots = Stream.of("src/test", "src/main", "../medscribe-ai/src/test", "../medscribe-ai/src/main", "../scripts")
                .map(Path::of).filter(Files::isDirectory).toList();
        List<Path> files;
        try (Stream<Path> walk = roots.stream().flatMap(r -> {
            try { return Files.walk(r); } catch (IOException e) { throw new IllegalStateException(e); }
        })) {
            files = walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().matches(".*\\.(java|properties|sql|py|sh|json|html|css)$")).toList();
        }
        assertThat(files).as("vacuous if the walk found nothing").hasSizeGreaterThan(100);
        List<String> hits = files.stream().filter(p -> {
            try {
                String s = Files.readString(p, StandardCharsets.UTF_8);
                return FORBIDDEN.stream().anyMatch(s::contains);
            } catch (IOException e) {
                return false;
            }
        }).map(Path::toString).toList();
        assertThat(hits).isEmpty();
    }
}
