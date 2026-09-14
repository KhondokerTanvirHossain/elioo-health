package com.elioo.baymax.extraction.adapter.in.handler;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.extraction.application.port.in.DocumentIntakeUseCase;
import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.extraction.domain.Upload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code POST /documents} takes the upload and answers 202 straight away; the pipeline runs behind it.
 * {@code GET /documents/{id}} reports status, and the result once it is DONE.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentHandler {

    static final int MAX_UPLOAD_BYTES = 25 * 1024 * 1024;

    private final DocumentIntakeUseCase intake;

    public Mono<ServerResponse> upload(ServerRequest request) {
        return request.multipartData().flatMap(parts -> {
            UUID patientId = patientId(parts.getFirst("patient_id"));
            List<Part> files = parts.get("file");
            if (files == null || files.isEmpty()) {
                return Mono.error(BaymaxException.badRequest("no_file",
                        "attach the document as one or more `file` parts"));
            }
            return Flux.fromIterable(files)
                    .filter(FilePart.class::isInstance)
                    .cast(FilePart.class)
                    .concatMap(DocumentHandler::readBytes)
                    .collectList()
                    .flatMap(uploads -> intake.accept(patientId, uploads))
                    .flatMap(document -> ServerResponse.status(HttpStatus.ACCEPTED)
                            .bodyValue(Map.of("document_id", document.id().toString(),
                                    "status", document.status().name())));
        });
    }

    public Mono<ServerResponse> status(ServerRequest request) {
        UUID documentId;
        try {
            documentId = UUID.fromString(request.pathVariable("id"));
        } catch (IllegalArgumentException e) {
            return Mono.error(BaymaxException.badRequest("invalid_request", "document id must be a UUID"));
        }
        return intake.view(documentId).flatMap(view -> ServerResponse.ok().bodyValue(body(view)));
    }

    private static Mono<Upload> readBytes(FilePart part) {
        return part.content()
                .map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    org.springframework.core.io.buffer.DataBufferUtils.release(buffer);
                    return bytes;
                })
                .collectList()
                .map(chunks -> {
                    int total = chunks.stream().mapToInt(c -> c.length).sum();
                    if (total > MAX_UPLOAD_BYTES) {
                        throw BaymaxException.badRequest("file_too_large",
                                "each file must be under " + (MAX_UPLOAD_BYTES / (1024 * 1024)) + " MB");
                    }
                    byte[] joined = new byte[total];
                    int offset = 0;
                    for (byte[] chunk : chunks) {
                        System.arraycopy(chunk, 0, joined, offset, chunk.length);
                        offset += chunk.length;
                    }
                    return new Upload(part.filename(), joined);
                });
    }

    private static UUID patientId(Part part) {
        if (!(part instanceof org.springframework.http.codec.multipart.FormFieldPart field)) {
            throw BaymaxException.badRequest("invalid_request", "patient_id is required");
        }
        try {
            return UUID.fromString(field.value().trim());
        } catch (IllegalArgumentException e) {
            throw BaymaxException.badRequest("invalid_request", "patient_id must be a UUID");
        }
    }

    /** Nulls are dropped rather than sent, so a pending document is a small object, not a field of nulls. */
    private static Map<String, Object> body(DocumentView view) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("document_id", view.documentId());
        body.put("status", view.status());
        putIfPresent(body, "reason", view.reason());
        putIfPresent(body, "document_type", view.documentType());
        putIfPresent(body, "document_date", view.documentDate());
        putIfPresent(body, "facility", view.facility());
        putIfPresent(body, "confidence", view.confidence());
        putIfPresent(body, "model", view.model());
        putIfPresent(body, "page_count", view.pageCount());
        putIfPresent(body, "values", view.values());
        putIfPresent(body, "medicines", view.medicines());
        putIfPresent(body, "follow_up", view.followUp());
        // only present when something was dropped; a family that sent a clean page sees nothing here
        putIfPresent(body, "unverified", view.unverified());
        return body;
    }

    private static void putIfPresent(Map<String, Object> body, String key, Object value) {
        if (value != null) {
            body.put(key, value);
        }
    }
}
