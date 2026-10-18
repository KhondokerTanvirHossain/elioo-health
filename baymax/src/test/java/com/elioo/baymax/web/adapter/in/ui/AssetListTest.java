package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Every file under baymax/ui is served or deliberately private; every served name exists or is a declared slot. */
class AssetListTest {

    private static final Path UI = Path.of("src/main/resources/baymax/ui");

    @Test
    void everyFileIsListedOrPrivateAndEveryListedFileIsServed() throws IOException {
        List<String> files;
        try (Stream<Path> walk = Files.walk(UI)) {
            files = walk.filter(Files::isRegularFile).map(p -> UI.relativize(p).toString().replace('\\', '/')).sorted().toList();
        }
        assertThat(files).as("files under baymax/ui").hasSizeGreaterThan(10);
        assertThat(files.stream().filter(f -> !AssetHandler.ASSETS.contains(f) && AssetHandler.PRIVATE_DIRS.stream().noneMatch(f::startsWith)).toList())
                .as("on disk but neither served nor private").isEmpty();
        WebUiTestKit kit = new WebUiTestKit();
        for (String listed : AssetHandler.ASSETS) {
            if (files.contains(listed) || "baymax.css".equals(listed)) {
                assertThat(kit.served("/assets/" + listed)).as(listed).isNotEmpty();
            } else {
                assertThat(AssetHandler.OPTIONAL_SLOTS).as(listed + " is listed but missing and not a declared slot").contains(listed);
            }
        }
    }
}
