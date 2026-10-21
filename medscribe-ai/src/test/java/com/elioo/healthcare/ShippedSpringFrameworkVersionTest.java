package com.elioo.healthcare;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.springframework.core.SpringVersion;
import org.springframework.http.codec.multipart.DefaultPartHttpMessageReader;

/**
 * The Spring Framework this deployable carries is one version, at least 6.2.19.
 *
 * <p>Framework 6.2.2 (Boot 3.4.2's) has spring-framework#34178, fixed by #34388 in 6.2.4: {@code MultipartParser}
 * in spring-web loses demand that arrives after a part was written to disk, so a press of several photos over 256 KB
 * each can hang forever with no error. build.gradle overrides the version to 6.2.19.
 *
 * <p>The first check reads the bootJar itself, every {@code BOOT-INF/lib} Framework jar's manifest: that lib is built
 * from the production runtime classpath, which Gradle resolves apart from the test runtime classpath this class runs
 * on, so a test-classpath check alone can pass while the jar ships 6.2.2. The image is then built again by the
 * Dockerfile (its own Gradle, tests skipped), and {@code scripts/check-framework-jars.sh} checks that lib there. The
 * test-runtime-classpath checks below are a second line, not the shipped artefact.
 */
class ShippedSpringFrameworkVersionTest {

    private static final int[] FIXED = {6, 2, 19};

    /** The Boot line the build.gradle override was decided for. Moving Boot while the override is set must fail. */
    private static final String BOOT_THE_OVERRIDE_IS_FOR = "3.4.2";

    /** Boot 3.4.2's BOM keeps reactor-core here, though Framework 6.2.19 declares 3.7.19 (linkage-checked). */
    private static final String REACTOR_CORE_UNDER_THE_BOOT_BOM = "3.7.2";

    private static final Set<String> REQUIRED =
            Set.of("spring-core", "spring-web", "spring-webflux", "spring-r2dbc", "spring-jdbc");

    /** Any nested jar: its manifest is read whatever its file name, so a renamed or shaded copy is still seen. */
    private static final Pattern LIB_ENTRY = Pattern.compile("BOOT-INF/lib/[^/]+\\.jar");

    /** A nested jar's artifact name and version, from its file name ("reactor-core-3.7.2.jar"). */
    private static final Pattern LIB_JAR = Pattern.compile("BOOT-INF/lib/([A-Za-z0-9._-]+?)-(\\d[^/]*)\\.jar");

    record FrameworkJar(String title, String version, String where) {}

    // ---- the shipped artefact: the bootJar

    @Test
    void everyFrameworkJarInTheBootJarIsOneFixedVersion() throws IOException {
        List<FrameworkJar> jars = new ArrayList<>();
        for (Map.Entry<String, Manifest> lib : bootJarLibManifests().entrySet()) {
            FrameworkJar jar = frameworkJar(lib.getValue().getMainAttributes(), lib.getKey());
            if (jar != null) jars.add(jar);
        }
        assertOneFixedVersion(byTitle(jars), "the bootJar's BOOT-INF/lib");
    }

    @Test
    void theOverrideIsOnlyForTheBootItWasDecidedFor() throws IOException {
        String override = System.getProperty("spring-framework.override", "");
        String boot = bootJarLibVersions().get("spring-boot");
        assertThat(boot).as("spring-boot jar in the bootJar's BOOT-INF/lib").isNotNull();
        if (!override.isEmpty()) {
            assertThat(boot)
                    .as("Boot is %s but build.gradle still sets ext['spring-framework.version'] = '%s', decided for"
                            + " Boot %s. Remove the override when this Boot's BOM brings Framework >= 6.2.19;"
                            + " otherwise re-decide it and update this constant.", boot, override,
                            BOOT_THE_OVERRIDE_IS_FOR)
                    .isEqualTo(BOOT_THE_OVERRIDE_IS_FOR);
        }
    }

    @Test
    void reactorCoreIsTheBootBomsOwnByDecision() throws IOException {
        assertThat(bootJarLibVersions().get("reactor-core"))
                .as("reactor-core in the bootJar. Framework 6.2.19 declares 3.7.19; Boot 3.4.2's BOM keeps %s and the"
                        + " pairing was linkage-checked. A change here is a decision: update build.gradle's comment"
                        + " and this constant.", REACTOR_CORE_UNDER_THE_BOOT_BOM)
                .isEqualTo(REACTOR_CORE_UNDER_THE_BOOT_BOM);
    }

    // ---- second line: the test runtime classpath this class runs on

    @Test
    void theJarCarryingTheMultipartParserIsFixed() {
        String version = DefaultPartHttpMessageReader.class.getPackage().getImplementationVersion();
        assertThat(atLeastFixed(version))
                .as("spring-web (DefaultPartHttpMessageReader, from %s) is %s, below 6.2.19",
                        DefaultPartHttpMessageReader.class.getProtectionDomain().getCodeSource().getLocation(),
                        version)
                .isTrue();
    }

    @Test
    void everyFrameworkJarOnTheTestRuntimeClasspathIsOneFixedVersion() throws IOException {
        Map<String, Set<String>> framework = byTitle(testClasspathFrameworkJars());
        assertOneFixedVersion(framework, "the test runtime classpath");
        assertThat(distinctVersions(framework)).as("Framework versions on the test runtime classpath")
                .containsExactly(SpringVersion.getVersion());
    }

    // ---- the helpers themselves, in the direction that matters

    @Test
    void aSecondOlderCopyOfAModuleIsReportedWhicheverComesLast() {
        List<FrameworkJar> jars = List.of(
                new FrameworkJar("spring-web", "6.2.2", "libs/old-spring-web.jar"),
                new FrameworkJar("spring-web", "6.2.19", "BOOT-INF/lib/spring-web-6.2.19.jar"));
        Map<String, Set<String>> framework = byTitle(jars);

        assertThat(below(framework)).containsEntry("spring-web", Set.of("6.2.2"));
        assertThat(duplicated(framework)).containsEntry("spring-web", Set.of("6.2.2", "6.2.19"));
        assertThat(byTitle(List.of(jars.get(1), jars.get(0)))).isEqualTo(framework);
    }

    @Test
    void twoVersionsAboveTheFloorAreStillAMix() {
        Map<String, Set<String>> framework = byTitle(List.of(
                new FrameworkJar("spring-web", "6.2.19", "a"),
                new FrameworkJar("spring-webflux", "6.2.21", "b")));

        assertThat(below(framework)).isEmpty();
        assertThat(distinctVersions(framework)).containsExactly("6.2.19", "6.2.21");
    }

    @Test
    void theFloorComparesNumbersNotStrings() {
        assertThat(atLeastFixed("6.2.19")).isTrue();
        assertThat(atLeastFixed("6.2.21")).isTrue();
        assertThat(atLeastFixed("7.0.0")).isTrue();
        assertThat(atLeastFixed("6.2.2")).isFalse();
        assertThat(atLeastFixed("6.2.9")).isFalse();
        assertThat(atLeastFixed("6.1.30")).isFalse();
        assertThat(atLeastFixed(null)).isFalse();
    }

    // ----

    private static void assertOneFixedVersion(Map<String, Set<String>> framework, String where) {
        // derived from the artefact, so guard against an empty sweep passing vacuously
        assertThat(framework).as("Framework jars found in %s", where).containsKeys(REQUIRED.toArray(String[]::new));
        assertThat(below(framework)).as("Spring Framework jars below 6.2.19 in %s", where).isEmpty();
        assertThat(duplicated(framework)).as("Spring Framework modules present at two versions in %s", where).isEmpty();
        assertThat(distinctVersions(framework)).as("Spring Framework versions in %s (must be one)", where).hasSize(1);
    }

    static Map<String, Set<String>> byTitle(List<FrameworkJar> jars) {
        Map<String, Set<String>> found = new TreeMap<>();
        for (FrameworkJar jar : jars) found.computeIfAbsent(jar.title(), t -> new TreeSet<>()).add(jar.version());
        return found;
    }

    static Map<String, Set<String>> below(Map<String, Set<String>> framework) {
        Map<String, Set<String>> below = new TreeMap<>();
        framework.forEach((title, versions) -> versions.stream().filter(v -> !atLeastFixed(v))
                .forEach(v -> below.computeIfAbsent(title, t -> new TreeSet<>()).add(v)));
        return below;
    }

    static Map<String, Set<String>> duplicated(Map<String, Set<String>> framework) {
        Map<String, Set<String>> duplicated = new TreeMap<>();
        framework.forEach((title, versions) -> {
            if (versions.size() > 1) duplicated.put(title, versions);
        });
        return duplicated;
    }

    static Set<String> distinctVersions(Map<String, Set<String>> framework) {
        Set<String> versions = new TreeSet<>();
        framework.values().forEach(versions::addAll);
        return versions;
    }

    /** A Framework jar is titled by artifact ("spring-webflux") with module "spring.webflux"; Boot and Data are not. */
    static FrameworkJar frameworkJar(Attributes attributes, String where) {
        String title = attributes.getValue("Implementation-Title");
        String module = attributes.getValue("Automatic-Module-Name");
        String version = attributes.getValue("Implementation-Version");
        if (title != null && version != null && module != null
                && title.startsWith("spring-") && module.equals(title.replace('-', '.'))) {
            return new FrameworkJar(title, version, where);
        }
        return null;
    }

    private static File bootJar() {
        String path = System.getProperty("shipped.bootJar");
        assertThat(path).as("system property shipped.bootJar (set by medscribe-ai/build.gradle)").isNotBlank();
        File jar = new File(path);
        assertThat(jar).as("the bootJar").isFile();
        return jar;
    }

    /** Every nested jar in BOOT-INF/lib, by entry name, with its own manifest. */
    private static Map<String, Manifest> bootJarLibManifests() throws IOException {
        Map<String, Manifest> manifests = new TreeMap<>();
        try (ZipFile zip = new ZipFile(bootJar())) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                if (!LIB_ENTRY.matcher(entry.getName()).matches()) continue;
                try (JarInputStream nested = new JarInputStream(zip.getInputStream(entry))) {
                    Manifest manifest = nested.getManifest();
                    if (manifest != null) manifests.put(entry.getName(), manifest);
                }
            }
        }
        assertThat(manifests).as("nested jars with a manifest in the bootJar's BOOT-INF/lib").isNotEmpty();
        return manifests;
    }

    /** artifact name to version, from the BOOT-INF/lib entry names ("reactor-core-3.7.2.jar"). */
    private static Map<String, String> bootJarLibVersions() throws IOException {
        Map<String, String> versions = new TreeMap<>();
        try (ZipFile zip = new ZipFile(bootJar())) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                Matcher m = LIB_JAR.matcher(entry.getName());
                if (m.matches()) versions.put(m.group(1), m.group(2));
            }
        }
        return versions;
    }

    private static List<FrameworkJar> testClasspathFrameworkJars() throws IOException {
        List<FrameworkJar> jars = new ArrayList<>();
        for (URL url : Collections.list(
                ShippedSpringFrameworkVersionTest.class.getClassLoader().getResources("META-INF/MANIFEST.MF"))) {
            try (InputStream in = url.openStream()) {
                FrameworkJar jar = frameworkJar(new Manifest(in).getMainAttributes(), url.toString());
                if (jar != null) jars.add(jar);
            }
        }
        return jars;
    }

    static boolean atLeastFixed(String version) {
        if (version == null) return false;
        String[] parts = version.split("[.-]");
        for (int i = 0; i < FIXED.length; i++) {
            int n = i < parts.length && parts[i].matches("\\d+") ? Integer.parseInt(parts[i]) : 0;
            if (n != FIXED[i]) return n > FIXED[i];
        }
        return true;
    }
}
