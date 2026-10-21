package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.healthrecord.domain.DocumentAllowance;
import com.elioo.baymax.web.adapter.in.MultipartUploads;
import com.elioo.baymax.web.adapter.in.PatientJson;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.adapter.in.ui.Lang;
import com.elioo.baymax.web.adapter.in.ui.UiCopy;
import com.elioo.baymax.web.application.port.in.UploadUseCase;
import com.elioo.baymax.web.domain.Grouping;
import com.elioo.baymax.web.domain.PatientSummary;
import com.elioo.baymax.web.domain.UploadOptions;
import com.elioo.baymax.web.domain.WebSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code GET /app/api/upload-options} and {@code POST /app/api/uploads}: the add-report sheet as JSON (rebuild plan B4b).
 *
 * <p>The options are {@link UploadUseCase#uploadOptions}, the sheet's own: who the viewer may send for, who they may only
 * see, the one patient to preselect when there is nobody else it could be (never a guess), this month's allowance, and
 * every limit the server refuses by. A refusal's copy reads its numbers from here; the error body is only a name.
 *
 * <p>The upload is one press of "পাঠান": {@code patient}, a required {@code upload_key} (a retry with the same key and
 * the same files is the same press and returns the same ids, V21), {@code group} ({@code one}, the default, or
 * {@code separate}; anything else is refused, never guessed) and the {@code file} parts. It is read by
 * {@link MultipartUploads}, the sheet's own reader, so the two paths hold the same caps, counted on the bytes as they
 * arrive. Whether the viewer may send for the patient is asked before a file is joined into memory. One document opens
 * its page; several land on the patient's papers.
 *
 * <p>The name forms ({@code name_possessive}, {@code name_objective}) are the viewer's language, as the sheet writes
 * them ({@link UiCopy#poss}, {@link UiCopy#obj}).
 *
 * <p>Logs carry ids, counts and bytes only. A file's own name may name the patient and is never logged or returned.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UploadApiHandler {

    private final UploadUseCase uploads;
    private final BaymaxProperties properties;
    private final UiCopy copy;

    public Mono<ServerResponse> options(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        Lang lang = Lang.of(request);
        return uploads.uploadOptions(session.familyId())
                .flatMap(o -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(options(o, lang)));
    }

    public Mono<ServerResponse> upload(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        BaymaxProperties.Upload caps = properties.getUpload();
        Optional<BaymaxException> declared = MultipartUploads.declaredTooLarge(request, caps);
        if (declared.isPresent()) {
            return Mono.error(declared.get());
        }
        return MultipartUploads.withParts(request, properties, parts -> {
            UUID patientId = MultipartUploads.uuidOrNull(MultipartUploads.field(parts, "patient"));
            if (patientId == null) {
                return Mono.error(BaymaxException.badRequest("choose_patient", "no patient chosen"));
            }
            String key = MultipartUploads.uploadKey(parts);
            Optional<Grouping> grouping = grouping(MultipartUploads.field(parts, "group"));
            if (key == null || grouping.isEmpty() || MultipartUploads.replacesMalformed(parts)) {
                // the client's own mistake, not a refusal intake can make: no reason string for the sheet to word
                return ApiReason.INVALID.respond();
            }
            UUID replaces = MultipartUploads.replacesOrNull(parts);
            // who may send for this patient, and the retake it replaces, are asked before a file is joined into memory: a
            // view-only press, or one naming a paper that is not this patient's retake, reads nothing
            return uploads.admit(session.familyId(), patientId, replaces).then(MultipartUploads.files(parts, properties, grouping.get())).flatMap(files -> {
                long bytes = files.stream().mapToLong(u -> u.bytes().length).sum();
                return uploads.upload(session.familyId(), patientId, files, grouping.get(), key, replaces)
                        .doOnNext(docs -> docs.forEach(d -> log.info("[baymax-api] upload documentId={} files={} bytes={} documents={}",
                                d.id(), files.size(), bytes, docs.size())))
                        .flatMap(docs -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(pressed(docs, patientId)));
            });
        });
    }

    /**
     * {@code one} or nothing is one report; {@code separate} is one per file; anything else is not guessed. A misspelt
     * "seperate" read as one report would merge papers without telling anyone, and React posts only these two values.
     * The HTML sheet stays lenient (UploadUiHandler.grouping): its values are its own radio buttons, and the only other
     * value it can receive is none, from a page cached before D3.
     */
    private static Optional<Grouping> grouping(String raw) {
        if (raw == null || raw.equals("one")) {
            return Optional.of(Grouping.ONE);
        }
        return raw.equals("separate") ? Optional.of(Grouping.SEPARATE) : Optional.empty();
    }

    private static Map<String, Object> pressed(List<Document> docs, UUID patientId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("documents", docs.stream().map(d -> Map.of("id", d.id().toString())).toList());
        // one document opens; several land on the patient's papers, where each one is (the sheet's own rule)
        body.put("next", docs.size() == 1 ? "/app/documents/" + docs.get(0).id() : "/app/timeline?patient=" + patientId);
        return body;
    }

    private Map<String, Object> options(UploadOptions o, Lang lang) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("can_send", o.canSend().stream().map(p -> patient(p, lang)).toList());
        body.put("view_only", o.viewOnly().stream().map(p -> patient(p, lang)).toList());
        body.put("preselected_patient_id", o.preselected().map(p -> p.patient().id().toString()).orElse(null));
        body.put("allowance", allowance(o.allowance()));
        body.put("limits", limits());
        return body;
    }

    /** A paying family has no cap: {@code limited} false and every number null, never {@code Long.MAX_VALUE}. */
    private static Map<String, Object> allowance(DocumentAllowance a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("limited", a.limited());
        m.put("max", a.limited() ? a.max() : null);
        m.put("remaining", a.limited() ? a.remaining() : null);
        m.put("resets_on", a.limited() ? a.resetsOn().toString() : null);
        return m;
    }

    private Map<String, Object> limits() {
        BaymaxProperties.Upload up = properties.getUpload();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("max_pages", properties.getExtract().getMaxPages());
        m.put("max_files", MultipartUploads.maxFiles(properties));
        m.put("max_file_bytes", up.getMaxFileBytes());
        m.put("max_request_bytes", up.getMaxRequestBytes());
        m.put("downscale_max_edge", up.getDownscaleMaxEdge());
        m.put("downscale_quality", up.getDownscaleQuality());
        m.put("pass_through_bytes", up.getPassThroughBytes());
        return m;
    }

    /** {@link PatientJson}'s fields, then the name forms React must not build ("{name}-এর"), in the viewer's language. */
    private Map<String, Object> patient(PatientSummary p, Lang lang) {
        Map<String, Object> m = PatientJson.fields(p);
        String name = p.patient().name();
        m.put("name_possessive", copy.poss(lang, name));
        m.put("name_objective", copy.obj(lang, name));
        return m;
    }
}
