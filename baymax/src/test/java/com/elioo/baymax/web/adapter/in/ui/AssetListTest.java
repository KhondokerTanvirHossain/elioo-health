package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every file under baymax/ui is served or deliberately private; every served name exists or is a declared slot; and
 * what is served is the file itself, compared by content (an image is checked against its bytes, never its presence).
 */
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
                byte[] expected = "baymax.css".equals(listed) ? stylesheetOnDisk() : Files.readAllBytes(UI.resolve(listed));
                assertThat(expected).as(listed + " on disk").isNotEmpty();
                assertThat(kit.served("/assets/" + listed)).as(listed + " is served as itself").isEqualTo(expected);
            } else {
                assertThat(AssetHandler.OPTIONAL_SLOTS).as(listed + " is listed but missing and not a declared slot").contains(listed);
            }
        }
    }

    /** A slot that is not listed could never be served; one left behind when its name leaves the list is a stale slot. */
    @Test
    void everyOptionalSlotIsAListedAsset() {
        assertThat(AssetHandler.OPTIONAL_SLOTS).isNotEmpty();
        assertThat(AssetHandler.ASSETS).containsAll(AssetHandler.OPTIONAL_SLOTS);
    }

    /** The stylesheet parts as they are on disk, joined in the served order. */
    private static byte[] stylesheetOnDisk() throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (String part : AssetHandler.CSS_PARTS) {
            out.writeBytes(Files.readAllBytes(UI.resolve("css").resolve(part)));
        }
        return out.toByteArray();
    }
}
