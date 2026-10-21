package com.elioo.baymax.web.adapter.in;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.domain.Upload;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.server.ServerRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * How a multipart press of "পাঠান" is read, shared by the add-report sheet ({@code POST /app/add}, the legacy form) and
 * the JSON upload ({@code POST /app/api/uploads}), so the two can never drift on the caps or on what counts as a file:
 * <ul>
 *   <li>a declared Content-Length over {@code baymax.upload.max-request-bytes} is refused before a byte is read;</li>
 *   <li>each file is joined in memory up to {@code max-file-bytes}, and the press as a whole up to
 *       {@code max-request-bytes} — counted while reading, so a chunked body with no Content-Length is held to the same
 *       cap and never read past it into memory;</li>
 *   <li>an input the family did not use (no filename, no bytes) is not a page; a chosen empty file is passed on, so
 *       intake refuses it by name;</li>
 *   <li>no file at all is {@code no_file}.</li>
 * </ul>
 * A file's own name may name the patient: nothing here logs it.
 */
public final class MultipartUploads {

    /** What the sheet writes (a UUID) and a little room; intake appends the files' digest and ":i", within V21's 80-character CHECK. */
    public static final Pattern UPLOAD_KEY = Pattern.compile("[A-Za-z0-9-]{8,64}");

    private MultipartUploads() {
    }

    /** {@code request_too_large} when the request declares more bytes than one press may hold; empty otherwise (or undeclared). */
    public static Optional<BaymaxException> declaredTooLarge(ServerRequest request, BaymaxProperties.Upload caps) {
        return request.headers().contentLength().orElse(-1L) > caps.getMaxRequestBytes()
                ? Optional.of(tooLarge("request_too_large")) : Optional.empty();
    }

    /**
     * The {@code file} parts as uploads, in the order sent. Refuses with {@code file_too_large} for a file over the file
     * cap, {@code request_too_large} once the files together pass the request cap, and {@code no_file} when nothing was
     * attached.
     */
    public static Mono<List<Upload>> files(MultiValueMap<String, Part> parts, BaymaxProperties.Upload caps) {
        List<FilePart> files = parts.getOrDefault("file", List.of()).stream()
                .filter(FilePart.class::isInstance).map(FilePart.class::cast).toList();
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
}
