package com.elioo.baymax.web.adapter.in;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.domain.Upload;
import com.elioo.baymax.web.domain.Grouping;
import org.springframework.core.ResolvableType;
import org.springframework.core.codec.DecodingException;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.DefaultPartHttpMessageReader;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * How a multipart press of "পাঠান" is read, shared by the add-report sheet ({@code POST /app/add}, the legacy form) and
 * the JSON upload ({@code POST /app/api/uploads}), so the two can never drift on the caps or on what counts as a file.
 *
 * <p>The press is parsed here ({@link #withParts}), never by the exchange's own {@code multipartData()}, whose reader has
 * no disk or part limit: a chunked body of any size was received whole and spooled to {@code java.io.tmpdir} (the EC2
 * root disk, shared with n8n and bddrug) before any cap below was checked. Here every limit holds on the bytes as they
 * arrive:
 * <ul>
 *   <li>a declared Content-Length over {@code baymax.upload.max-request-bytes} is refused before a byte is read;</li>
 *   <li>the body itself is counted as it arrives and refused ({@code request_too_large}) at the first byte past
 *       {@code max-request-bytes}, so a chunked body with no Content-Length is read no further, into memory or onto
 *       disk;</li>
 *   <li>a part is kept in memory up to {@value #IN_MEMORY_BYTES} bytes and on disk up to {@code max-file-bytes}
 *       ({@code file_too_large}); a form field or a part's headers over the in-memory limit is {@code request_too_large};</li>
 *   <li>more chosen files than {@code baymax.extract.max-pages} is refused before any is joined: for "আলাদা আলাদা",
 *       which makes one document and one model call per file, {@code too_many_files}; for one report,
 *       {@code too_many_pages}, as intake would say. More parts than those and the form's fields is refused the same
 *       way as the parts arrive, so a press of thousands of tiny files is not even parsed to its end;</li>
 *   <li>an input the family did not use (no filename, no bytes) is not a page; a chosen empty file is passed on, so
 *       intake refuses it by name;</li>
 *   <li>no file at all is {@code no_file}.</li>
 * </ul>
 * Every part spooled to disk is deleted when the press is answered, refused or cancelled. A file's own name may name
 * the patient: nothing here logs it.
 */
public final class MultipartUploads {

    /** What the sheet writes (a UUID) and a little room; intake appends the files' digest and ":i", within V21's 80-character CHECK. */
    public static final Pattern UPLOAD_KEY = Pattern.compile("[A-Za-z0-9-]{8,64}");

    /** A part larger than this goes to disk while it is read (Spring's own default); a form field may not be larger. */
    static final int IN_MEMORY_BYTES = 256 * 1024;

    /**
     * Parts a press may carry besides its chosen files: {@code patient}, {@code upload_key}, {@code group}, {@code back}
     * and the sheet's three file inputs, an unused one of which posts an empty part with no name. Room to spare, never
     * room for a document.
     */
    static final int OTHER_PARTS = 8;

    private static final ResolvableType PART = ResolvableType.forClass(Part.class);
    private static final Map<List<Long>, DefaultPartHttpMessageReader> READERS = new ConcurrentHashMap<>();

    private MultipartUploads() {
    }

    /** The most chosen files one press may carry: one paper's pages, and "আলাদা আলাদা" makes no more documents than that. */
    public static int maxFiles(BaymaxProperties properties) {
        return properties.getExtract().getMaxPages();
    }

    /** {@code request_too_large} when the request declares more bytes than one press may hold; empty otherwise (or undeclared). */
    public static Optional<BaymaxException> declaredTooLarge(ServerRequest request, BaymaxProperties.Upload caps) {
        return request.headers().contentLength().orElse(-1L) > caps.getMaxRequestBytes()
                ? Optional.of(tooLarge("request_too_large")) : Optional.empty();
    }

    /**
     * The press's parts, read under every limit above, handed to {@code use}; every part spooled to disk is deleted when
     * {@code use} completes, fails or is cancelled, and when the read itself is refused part way.
     */
    public static <T> Mono<T> withParts(ServerRequest request, BaymaxProperties properties,
                                        Function<MultiValueMap<String, Part>, Mono<T>> use) {
        List<Part> read = new CopyOnWriteArrayList<>();
        return Mono.usingWhen(parts(request, properties, read), use,
                parts -> delete(read), (parts, error) -> delete(read), parts -> delete(read))
                // refused part way: the parts read before the refusal are on disk too
                .onErrorResume(e -> delete(read).then(Mono.error(e)));
    }

    private static Mono<MultiValueMap<String, Part>> parts(ServerRequest request, BaymaxProperties properties, List<Part> read) {
        BaymaxProperties.Upload caps = properties.getUpload();
        int maxFiles = maxFiles(properties);
        ServerHttpRequest raw = request.exchange().getRequest();
        Flux<DataBuffer> counted = counted(raw.getBody(), caps.getMaxRequestBytes());
        ServerHttpRequest bounded = new ServerHttpRequestDecorator(raw) {
            @Override
            public Flux<DataBuffer> getBody() {
                return counted;
            }
        };
        AtomicInteger parts = new AtomicInteger();
        // both clients send the group before the files; until it is seen, a press is one report (the sheet's default)
        AtomicReference<Grouping> grouping = new AtomicReference<>(Grouping.ONE);
        return reader(caps.getMaxFileBytes(), maxFiles + OTHER_PARTS).read(PART, bounded, Map.of())
                .doOnNext(read::add)
                .handle((Part part, reactor.core.publisher.SynchronousSink<Part> sink) -> {
                    if (part instanceof FormFieldPart f && f.name().equals("group") && f.value().trim().equals("separate")) {
                        grouping.set(Grouping.SEPARATE);
                    }
                    if (parts.incrementAndGet() > maxFiles + OTHER_PARTS) {
                        sink.error(tooMany(grouping.get(), maxFiles));
                    } else {
                        sink.next(part);
                    }
                })
                .collect(LinkedMultiValueMap<String, Part>::new, (map, part) -> map.add(part.name(), part))
                .map(map -> (MultiValueMap<String, Part>) map)
                .onErrorMap(DataBufferLimitException.class, MultipartUploads::overLimit)
                // the exchange's own reader answered a malformed body this way; a 400, never a server fault
                .onErrorMap(DecodingException.class, e -> new ServerWebInputException("Failed to parse multipart data", null, e));
    }

    /** The body, refused at the first buffer that takes it past {@code max} bytes; nothing after it is read. */
    static Flux<DataBuffer> counted(Flux<DataBuffer> body, long max) {
        AtomicLong seen = new AtomicLong();
        return body.handle((DataBuffer buffer, reactor.core.publisher.SynchronousSink<DataBuffer> sink) -> {
            if (seen.addAndGet(buffer.readableByteCount()) > max) {
                DataBufferUtils.release(buffer);
                sink.error(tooLarge("request_too_large"));
            } else {
                sink.next(buffer);
            }
        });
    }

    /** One reader per set of limits: its blocking scheduler and spool directory are made once, not per press. */
    private static DefaultPartHttpMessageReader reader(long maxFileBytes, int maxParts) {
        return READERS.computeIfAbsent(List.of(maxFileBytes, (long) maxParts), k -> {
            DefaultPartHttpMessageReader reader = new DefaultPartHttpMessageReader();
            reader.setMaxInMemorySize(IN_MEMORY_BYTES);
            reader.setMaxDiskUsagePerPart(maxFileBytes);
            // a backstop only: the count above refuses the part after the last allowed, by name, before this does
            reader.setMaxParts(maxParts + 1);
            return reader;
        });
    }

    /**
     * Spring's reader refuses a file over its disk limit, and a form field or a part's headers over its memory limit, with
     * one exception type: the first is {@code file_too_large}, the others {@code request_too_large}. Both are TOO_LARGE.
     */
    private static BaymaxException overLimit(DataBufferLimitException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        return tooLarge(message.startsWith("Part exceeded the disk usage limit") ? "file_too_large" : "request_too_large");
    }

    /** A part the family chose as a file: a file part with a name. An unused input posts one with none. */
    private static boolean chosen(Part part) {
        return part instanceof FilePart f && f.filename() != null && !f.filename().isBlank();
    }

    private static Mono<Void> delete(List<Part> parts) {
        return Flux.fromIterable(parts).concatMap(p -> p.delete().onErrorResume(e -> Mono.empty())).then()
                .doOnTerminate(parts::clear);
    }

    /**
     * The {@code file} parts as uploads, in the order sent. Refuses, before any file is joined, more chosen files than one
     * press may carry ({@code too_many_files} for "আলাদা আলাদা", {@code too_many_pages} for one report); then
     * {@code file_too_large} for a file over the file cap, {@code request_too_large} once the files together pass the
     * request cap, and {@code no_file} when nothing was attached.
     */
    public static Mono<List<Upload>> files(MultiValueMap<String, Part> parts, BaymaxProperties properties, Grouping grouping) {
        BaymaxProperties.Upload caps = properties.getUpload();
        List<FilePart> files = parts.getOrDefault("file", List.of()).stream()
                .filter(FilePart.class::isInstance).map(FilePart.class::cast).toList();
        int maxFiles = maxFiles(properties);
        if (files.stream().filter(MultipartUploads::chosen).count() > maxFiles) {
            return Mono.error(tooMany(grouping, maxFiles));
        }
        AtomicLong total = new AtomicLong();
        return Flux.fromIterable(files).concatMap(part -> read(part, caps, total))
                // an input the family did not use posts filename="" and nothing; a chosen empty file keeps its name
                .filter(u -> !(u.bytes().length == 0 && (u.filename() == null || u.filename().isBlank())))
                .collectList()
                .flatMap(pages -> pages.isEmpty()
                        ? Mono.error(BaymaxException.badRequest("no_file", "attach at least one file"))
                        : Mono.just(pages));
    }

    /**
     * One file, joined up to whichever is smaller: the file cap, or what the request cap has left after the files before
     * it. Over the first is {@code file_too_large}; over the second is the total check, {@code request_too_large}.
     */
    private static Mono<Upload> read(FilePart part, BaymaxProperties.Upload caps, AtomicLong total) {
        long left = Math.max(0, caps.getMaxRequestBytes() - total.get());
        boolean requestBound = left < caps.getMaxFileBytes();
        int limit = Math.toIntExact(Math.min(Integer.MAX_VALUE, Math.min(caps.getMaxFileBytes(), left)));
        return DataBufferUtils.join(part.content(), limit)
                .map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    total.addAndGet(bytes.length);
                    return new Upload(part.filename(), bytes);
                })
                .defaultIfEmpty(new Upload(part.filename(), new byte[0]))
                .onErrorMap(DataBufferLimitException.class, e -> requestBound ? tooLarge("request_too_large") : tooLarge("file_too_large"));
    }

    /** The press's key, or null for a value that is not our shape (the legacy form sends none). */
    public static String uploadKey(MultiValueMap<String, Part> parts) {
        return Optional.ofNullable(field(parts, "upload_key")).filter(k -> UPLOAD_KEY.matcher(k).matches()).orElse(null);
    }

    /** A form field's trimmed value; null when absent, blank or a file. */
    public static String field(MultiValueMap<String, Part> parts, String name) {
        Part part = parts.getFirst(name);
        return part instanceof FormFieldPart f && !f.value().isBlank() ? f.value().trim() : null;
    }

    /** The id, or null for anything that is not one: a patient that is not an id is nobody chosen. */
    public static UUID uuidOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static BaymaxException tooLarge(String reason) {
        return new BaymaxException(HttpStatus.PAYLOAD_TOO_LARGE, reason, "upload over the size limit");
    }

    /** More files than one press may carry: for one report that is more pages than a paper has, as intake says. */
    private static BaymaxException tooMany(Grouping grouping, int maxFiles) {
        return grouping == Grouping.SEPARATE
                ? BaymaxException.badRequest("too_many_files", "at most " + maxFiles + " files in one press")
                : BaymaxException.badRequest("too_many_pages", "at most " + maxFiles + " pages in one paper");
    }
}
