package com.elioo.baymax.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Every reason the upload path can be refused for, read from the source with the status of the call that throws it. Used
 * by the HTML sheet's copy sweep ({@code TryDocumentsThroughTheWebTest}) and the JSON names' sweep
 * ({@code UploadApiReasonsTest}), so the two read one list.
 *
 * <p>The files are explicit: all of {@code extraction/application}, the three web files that hold upload refusals by
 * name ({@link #NAMED}), and any other web file that mentions {@code UploadUseCase}. The named list is what keeps
 * {@code MultipartUploads} in: it throws {@code file_too_large} and {@code request_too_large} and does not mention
 * {@code UploadUseCase}, so a sweep by mention alone would drop both while a size guard still passed. Callers assert
 * the named files were found.
 */
public final class UploadRefusalSweep {

    /** The web files that refuse an upload by name; each must exist ({@link #sources} callers assert it). */
    public static final Set<String> NAMED = Set.of("UploadUiHandler.java", "MultipartUploads.java", "UploadApiHandler.java");

    private static final Path MAIN = Path.of("src/main/java/com/elioo/baymax");
    /** {@code badRequest("x"}, {@code forbidden("x"}, {@code conflict("x"}, {@code tooLarge("x"}, {@code PAYLOAD_TOO_LARGE, "x"}. */
    private static final Pattern THROWN = Pattern.compile(
            "(badRequest|forbidden|conflict|tooLarge)\\(\\s*\"([a-z_]+)\"|(PAYLOAD_TOO_LARGE),\\s*\"([a-z_]+)\"");
    private static final Map<String, Integer> STATUS = Map.of(
            "badRequest", 400, "forbidden", 403, "conflict", 409, "tooLarge", 413, "PAYLOAD_TOO_LARGE", 413);

    private UploadRefusalSweep() {
    }

    /** The files swept, in walk order. */
    public static List<Path> sources() throws IOException {
        List<Path> sources = new ArrayList<>(javaFiles(MAIN.resolve("extraction/application")));
        for (Path p : javaFiles(MAIN.resolve("web"))) {
            if (NAMED.contains(p.getFileName().toString()) || Files.readString(p).contains("UploadUseCase")) {
                sources.add(p);
            }
        }
        return sources;
    }

    /**
     * Each reason and the HTTP status of the call that throws it, sorted by reason. One reason thrown with two statuses is
     * an error here, not a silent pick: a name can keep only one status (rebuild plan A2).
     */
    public static Map<String, Integer> reasonsWithStatus() throws IOException {
        Map<String, Integer> reasons = new TreeMap<>();
        for (Path f : sources()) {
            Matcher m = THROWN.matcher(Files.readString(f));
            while (m.find()) {
                String call = m.group(1) != null ? m.group(1) : m.group(3);
                String reason = m.group(2) != null ? m.group(2) : m.group(4);
                Integer status = STATUS.get(call);
                Integer before = reasons.putIfAbsent(reason, status);
                if (before != null && !before.equals(status)) {
                    throw new IllegalStateException(reason + " is thrown as both " + before + " and " + status + " (" + f.getFileName() + ")");
                }
            }
        }
        return reasons;
    }

    private static List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }
}
