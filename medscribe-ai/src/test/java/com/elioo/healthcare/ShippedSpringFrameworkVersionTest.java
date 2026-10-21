package com.elioo.healthcare;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.springframework.core.SpringVersion;

/**
 * The Spring Framework this deployable carries is at least 6.2.19.
 *
 * <p>Framework 6.2.2 (Boot 3.4.2's) has spring-framework#34178: {@code MultipartParser} loses demand that arrives
 * after a part was written to disk, so a press of several photos over 256 KB each can hang forever with no error.
 * The override once lived only in the root {@code subprojects} BOM import; the Boot plugin applied here imports
 * spring-boot-dependencies a second time without it, so the bootJar kept 6.2.2 while baymax's own test classpath saw
 * 6.2.19 and its real-server guard stayed green. This test runs on medscribe-ai's classpath, which is the one the
 * bootJar is built from, and reads every Framework jar on it rather than a list of the ones remembered.
 */
class ShippedSpringFrameworkVersionTest {

    private static final int[] FIXED = {6, 2, 19};

    @Test
    void theCoreReportsTheFixedFramework() {
        assertThat(atLeastFixed(SpringVersion.getVersion()))
                .as("SpringVersion.getVersion() is %s, below 6.2.19", SpringVersion.getVersion())
                .isTrue();
    }

    @Test
    void everyFrameworkJarOnTheShippedClasspathIsTheFixedVersion() throws IOException {
        Map<String, String> framework = frameworkJars();

        // derived from the classpath, so guard against an empty sweep passing vacuously
        assertThat(framework).as("framework jars found on the classpath")
                .containsKeys("spring-core", "spring-web", "spring-webflux", "spring-r2dbc", "spring-jdbc");

        Map<String, String> below = new TreeMap<>();
        framework.forEach((jar, version) -> {
            if (!atLeastFixed(version)) below.put(jar, version);
        });
        assertThat(below).as("Spring Framework jars below 6.2.19").isEmpty();
    }

    /** Every manifest on the classpath whose title is a Framework module name, with its version. */
    private static Map<String, String> frameworkJars() throws IOException {
        Map<String, String> found = new TreeMap<>();
        for (URL url : Collections.list(
                ShippedSpringFrameworkVersionTest.class.getClassLoader().getResources("META-INF/MANIFEST.MF"))) {
            try (InputStream in = url.openStream()) {
                var attributes = new Manifest(in).getMainAttributes();
                String title = attributes.getValue("Implementation-Title");
                String module = attributes.getValue("Automatic-Module-Name");
                String version = attributes.getValue("Implementation-Version");
                // Framework jars are titled by artifact ("spring-webflux") with module "spring.webflux";
                // Boot and Data jars carry other titles and module names, and their own version lines
                if (title != null && version != null && module != null
                        && title.startsWith("spring-") && module.equals(title.replace('-', '.'))) {
                    found.put(title, version);
                }
            }
        }
        return found;
    }

    private static boolean atLeastFixed(String version) {
        if (version == null) return false;
        String[] parts = version.split("[.-]");
        for (int i = 0; i < FIXED.length; i++) {
            int n = i < parts.length && parts[i].matches("\\d+") ? Integer.parseInt(parts[i]) : 0;
            if (n != FIXED[i]) return n > FIXED[i];
        }
        return true;
    }
}
