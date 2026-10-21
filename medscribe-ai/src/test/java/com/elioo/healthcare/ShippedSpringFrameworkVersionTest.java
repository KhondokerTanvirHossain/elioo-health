package com.elioo.healthcare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
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
 * <p>Where each check runs, and what it covers. The jar that ships is the one the Dockerfile builds (its own Gradle,
 * tests skipped, only the files it copies), and {@code scripts/check-framework-jars.sh} refuses that build's lib; CI's
 * build job runs the Dockerfile's builder stage on every PR, so that refusal fails the PR. This class is the early
 * line: it reads CI's own bootJar, built from the same inputs by the wrapper's Gradle, which is a sibling of the image's
 * jar, not its bytes. It still catches what a classpath check cannot: that lib is built from the production runtime
 * classpath, which Gradle resolves apart from the test runtime classpath this class runs on. The test-classpath checks
 * at the end are a third line.
 */
class ShippedSpringFrameworkVersionTest {

    private static final int[] FIXED = {6, 2, 19};

    /** The Boot line the build.gradle override was decided for. Moving Boot while the override is set must fail. */
    private static final String BOOT_THE_OVERRIDE_IS_FOR = "3.4.2";

    /**
     * The mixed BOM the override creates: Boot 3.4.2's own versions of what Framework 6.2.19 was linkage-checked
     * against on review of #111 (6.2.19's POMs declare reactor-core 3.7.19 and micrometer-observation 1.15.12). Any
     * of these moving invalidates that check, so each is held while the override is set. build.gradle's comment
     * cites this table; it is the one list of what was checked.
     */
    static final Map<String, String> LINKAGE_CHECKED = new TreeMap<>(Map.of(
            "reactor-core", "3.7.2",
            "reactor-netty-core", "1.2.2",
            "reactor-netty-http", "1.2.2",
            "netty-common", "4.1.117.Final",
            "netty-handler", "4.1.117.Final",
            "netty-codec-http", "4.1.117.Final",
            "micrometer-observation", "1.14.3",
            "spring-data-commons", "3.4.2",
            "spring-data-relational", "3.4.2",
            "spring-data-r2dbc", "3.4.2"));

    private static final Set<String> REQUIRED =
            Set.of("spring-core", "spring-web", "spring-webflux", "spring-r2dbc", "spring-jdbc");

    /**
     * Title prefixes of other Spring projects whose manifests have the Framework shape (title "spring-security-core",
     * module "spring.security.core") but carry their own version line. One missing here counts as Framework and fails
     * as a mix, loudly. The same list as OTHER_PROJECTS in scripts/check-framework-jars.sh.
     */
    private static final List<String> OTHER_PROJECTS = List.of("spring-security-", "spring-amqp");

    /** Any nested jar: its manifest is read whatever its file name, so a renamed copy is still seen. */
    private static final Pattern LIB_ENTRY = Pattern.compile("BOOT-INF/lib/[^/]+\\.jar");

    /** A nested jar's artifact name and version, from its file name ("reactor-core-3.7.2.jar"). */
    private static final Pattern LIB_JAR = Pattern.compile("BOOT-INF/lib/([A-Za-z0-9._-]+?)-(\\d[^/]*)\\.jar");

    record FrameworkJar(String title, String version, String where) {}

    // ---- CI's bootJar: the same inputs as the image, not the image's bytes (check-framework-jars.sh checks those)

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
        Set<String> boot = bootJarLibVersions().get("spring-boot");
        assertThat(boot).as("spring-boot jars in the bootJar's BOOT-INF/lib (must be exactly one)").hasSize(1);
        if (!override.isEmpty()) {
            assertThat(boot)
                    .as("Boot is %s but build.gradle still sets ext['spring-framework.version'] = '%s', decided for"
                            + " Boot %s. Remove the override when this Boot's BOM brings Framework >= 6.2.19;"
                            + " otherwise re-decide it and update this constant.", boot, override,
                            BOOT_THE_OVERRIDE_IS_FOR)
                    .containsExactly(BOOT_THE_OVERRIDE_IS_FOR);
        }
    }

    @Test
    void theMixedBomStaysTheOneThatWasLinkageChecked() throws IOException {
        String override = System.getProperty("spring-framework.override", "");
        assumeFalse(override.isEmpty(), "no spring-framework.version override, so no mixed BOM to hold");
        Map<String, Set<String>> versions = bootJarLibVersions();
        LINKAGE_CHECKED.forEach((artifact, checked) -> assertThat(versions.get(artifact))
                .as("%s in the bootJar. Framework %s under Boot %s was linkage-checked against %s %s (#111);"
                        + " moving it is a decision: re-run the linkage check, then update LINKAGE_CHECKED and"
                        + " build.gradle's comment.", artifact, override, BOOT_THE_OVERRIDE_IS_FOR, artifact, checked)
                .containsExactly(checked));
    }

    // ---- third line: the test runtime classpath this class runs on

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
    void aSecondCopyOfAPinnedArtifactIsSeenWhicheverComesLast() {
        List<String> entries = List.of("BOOT-INF/lib/reactor-core-3.7.19.jar", "BOOT-INF/lib/reactor-core-3.7.2.jar",
                "BOOT-INF/lib/spring-boot-3.4.2.jar", "BOOT-INF/lib/netty-codec-http2-4.1.117.Final.jar");

        assertThat(libVersions(entries)).containsEntry("reactor-core", Set.of("3.7.19", "3.7.2"))
                .containsEntry("netty-codec-http2", Set.of("4.1.117.Final"));
        assertThat(libVersions(entries.reversed())).isEqualTo(libVersions(entries));
    }

    @Test
    void aManifestStoredAfterOtherEntriesIsStillRead() throws IOException {
        // JarInputStream finds a manifest only among the first entries; tomcat-embed-el-10.1.34.jar is such a jar
        byte[] jar = jarOf(List.of(
                Map.entry("org/example/A.class", new byte[] {0}),
                Map.entry("META-INF/MANIFEST.MF", manifestBytes("spring-web", "spring.web", "6.2.2"))));

        Manifest manifest = manifestOf(new ByteArrayInputStream(jar));

        assertThat(manifest).as("manifest of a jar whose MANIFEST.MF is not among its first entries").isNotNull();
        assertThat(frameworkJar(manifest.getMainAttributes(), "x"))
                .isEqualTo(new FrameworkJar("spring-web", "6.2.2", "x"));
        assertThat(manifestOf(new ByteArrayInputStream(jarOf(List.of(Map.entry("A.class", new byte[] {0}))))))
                .as("a jar with no manifest at all").isNull();
    }

    @Test
    void aFrameworkJarIsKnownByItsManifestNotByAListOfModules() throws IOException {
        // a module no list named, Boot and Data (titled "Spring ..."), and Security, whose manifest has the
        // Framework shape but its own version line
        assertThat(frameworkJar(attributes("spring-core-test", "spring.core.test", "6.2.2"), "a"))
                .isEqualTo(new FrameworkJar("spring-core-test", "6.2.2", "a"));
        assertThat(frameworkJar(attributes("spring-context-support", "spring.context.support", "6.2.2"), "b"))
                .isNotNull();
        assertThat(frameworkJar(attributes("Spring Boot", "spring.boot", "3.4.2"), "c")).isNull();
        assertThat(frameworkJar(attributes("Spring Data R2DBC", "spring.data.r2dbc", "3.4.2"), "d")).isNull();
        assertThat(frameworkJar(attributes("spring-security-core", "spring.security.core", "6.4.2"), "e")).isNull();
        assertThat(frameworkJar(attributes("spring-amqp", "spring.amqp", "3.2.2"), "f")).isNull();
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

    /**
     * A Framework jar is titled by artifact ("spring-webflux") with module "spring.webflux"; Boot and Data title
     * themselves "Spring Boot ...", and Security and AMQP, which share the shape, are set aside by OTHER_PROJECTS.
     */
    static FrameworkJar frameworkJar(Attributes attributes, String where) {
        String title = attributes.getValue("Implementation-Title");
        String module = attributes.getValue("Automatic-Module-Name");
        String version = attributes.getValue("Implementation-Version");
        if (title != null && version != null && module != null
                && title.startsWith("spring-") && module.equals(title.replace('-', '.'))
                && OTHER_PROJECTS.stream().noneMatch(title::startsWith)) {
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

    /**
     * Every nested jar in BOOT-INF/lib, by entry name, with its own manifest. A spring-* jar whose manifest cannot be
     * read fails the sweep rather than dropping out of it.
     */
    private static Map<String, Manifest> bootJarLibManifests() throws IOException {
        Map<String, Manifest> manifests = new TreeMap<>();
        List<String> unreadableSpringJars = new ArrayList<>();
        try (ZipFile zip = new ZipFile(bootJar())) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                if (!LIB_ENTRY.matcher(entry.getName()).matches()) continue;
                try (InputStream in = zip.getInputStream(entry)) {
                    Manifest manifest = manifestOf(in);
                    if (manifest != null) manifests.put(entry.getName(), manifest);
                    else if (entry.getName().startsWith("BOOT-INF/lib/spring-")) unreadableSpringJars.add(entry.getName());
                }
            }
        }
        assertThat(unreadableSpringJars).as("nested Spring jars without a readable manifest (the sweep cannot"
                + " classify them)").isEmpty();
        assertThat(manifests).as("nested jars with a manifest in the bootJar's BOOT-INF/lib").isNotEmpty();
        return manifests;
    }

    /** A jar's META-INF/MANIFEST.MF, wherever it is stored among the entries; null when it has none. */
    static Manifest manifestOf(InputStream jar) throws IOException {
        // a ZipInputStream scan, not JarInputStream.getManifest(): that finds the manifest only among the first entries
        ZipInputStream zip = new ZipInputStream(jar);
        for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
            if (entry.getName().equalsIgnoreCase("META-INF/MANIFEST.MF")) return new Manifest(zip);
        }
        return null;
    }

    /** artifact name to every version present, from the BOOT-INF/lib entry names. */
    private static Map<String, Set<String>> bootJarLibVersions() throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipFile zip = new ZipFile(bootJar())) {
            for (ZipEntry entry : Collections.list(zip.entries())) names.add(entry.getName());
        }
        return libVersions(names);
    }

    /** artifact name to every version present ("reactor-core-3.7.2.jar"); a second copy is kept, never overwritten. */
    static Map<String, Set<String>> libVersions(Collection<String> entryNames) {
        Map<String, Set<String>> versions = new TreeMap<>();
        for (String name : entryNames) {
            Matcher m = LIB_JAR.matcher(name);
            if (m.matches()) versions.computeIfAbsent(m.group(1), a -> new TreeSet<>()).add(m.group(2));
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

    // ---- fixtures for the helper tests

    private static byte[] manifestBytes(String title, String module, String version) {
        return ("Manifest-Version: 1.0\r\nImplementation-Title: " + title + "\r\nImplementation-Version: " + version
                + "\r\nAutomatic-Module-Name: " + module + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
    }

    private static Attributes attributes(String title, String module, String version) throws IOException {
        return new Manifest(new ByteArrayInputStream(manifestBytes(title, module, version))).getMainAttributes();
    }

    private static byte[] jarOf(List<Map.Entry<String, byte[]>> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> e : entries) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
