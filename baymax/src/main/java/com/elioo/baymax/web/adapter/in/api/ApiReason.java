package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.common.error.FreeTierExceededException;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;

/**
 * Why an {@code /app/api} request was refused. A refusal with a name has the body {@code {"reason":"<NAME>"}} and nothing
 * else: no developer message (it can carry a filename or a medicine), no status echo. React maps the name to copy; Java
 * never sends prose. A status with no name yet (a 403 without a {@link #BY_REASON} entry, the patients free tier, any
 * 5xx) is answered bodiless, with the bare status; the PR that first raises one on this namespace adds its name here.
 * Additive only (rebuild plan A2): a name, once shipped, keeps its meaning and its status.
 *
 * <p>A refusal the application throws by its own reason ({@link BaymaxException#reason()}) is named by that reason first
 * ({@link #BY_REASON}): each of the upload path's refusals has its own name, where the status alone would call most of
 * them {@code INVALID}. Numbers a refusal's copy needs (the page cap, the allowance) come from {@code GET
 * /app/api/upload-options}, never from the error body.
 */
public enum ApiReason {

    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    MISSING_HEADER(HttpStatus.FORBIDDEN),
    FORBIDDEN_ORIGIN(HttpStatus.FORBIDDEN),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    INVALID(HttpStatus.BAD_REQUEST),
    /** Sign-in request: the typed number is not one {@code PhoneDisplay.normalise} accepts. */
    INVALID_NUMBER(HttpStatus.BAD_REQUEST),
    /** Sign-in verify with no valid {@code baymax_login} cookie: start again from the number. */
    SIGN_IN_RESTART(HttpStatus.BAD_REQUEST),
    /**
     * The code refusals, 400 and never 401: 401 means "no session" and sends the client to sign-in, which must never
     * fire on a code typed on the sign-in page itself. Exactly as fine as the HTML page's copy: WRONG and NO_CODE are
     * both {@code CODE_WRONG}, as both are {@code login.bad.wrong} there.
     */
    CODE_WRONG(HttpStatus.BAD_REQUEST),
    CODE_EXPIRED(HttpStatus.BAD_REQUEST),
    CODE_BURNED(HttpStatus.BAD_REQUEST),
    CODE_FORMAT(HttpStatus.BAD_REQUEST),
    /** Upload: no patient was chosen (the sheet never guesses one). */
    CHOOSE_PATIENT(HttpStatus.BAD_REQUEST),
    /** Upload: no file was attached. */
    NO_FILE(HttpStatus.BAD_REQUEST),
    /** Upload: a chosen file had no bytes. */
    EMPTY_FILE(HttpStatus.BAD_REQUEST),
    /** Upload: a file that is neither an image Medioo reads nor a PDF, or an image that could not be re-encoded. */
    UNSUPPORTED_FILE(HttpStatus.BAD_REQUEST),
    /** Upload: a PDF with no pages, or one that could not be read. */
    UNREADABLE_PDF(HttpStatus.BAD_REQUEST),
    /** Upload: more pages than one paper may have ({@code limits.max_pages} of the upload options). */
    TOO_MANY_PAGES(HttpStatus.BAD_REQUEST),
    /**
     * Upload: more chosen files in one press than {@code limits.max_files} of the upload options ("আলাদা আলাদা" makes a
     * document, and a model call, per file).
     */
    TOO_MANY_FILES(HttpStatus.BAD_REQUEST),
    /**
     * Upload: a file over {@code max_file_bytes}, a press over {@code max_request_bytes} (counted on the bytes as they
     * arrive, so a chunked press is refused at the cap, not after it was received), or a form field or part headers over
     * the reader's memory limit.
     */
    TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE),
    /** A patient this viewer may see but not change (a share member, D30): upload and delete ({@code shared_patient_read_only}). */
    VIEW_ONLY(HttpStatus.FORBIDDEN),
    /** Upload: the press's key already made another patient's paper (D3); the client draws a new key. */
    UPLOAD_KEY_REUSED(HttpStatus.CONFLICT),
    /** Upload: this month's free papers are used up ({@code allowance} of the upload options says until when). */
    FREE_TIER_DOCUMENTS(HttpStatus.PAYMENT_REQUIRED),
    /** A paper delete without a valid confirm token: missing, another paper's, another session's, tampered or expired. */
    CONFIRM_REQUIRED(HttpStatus.BAD_REQUEST),
    /** Upload (D1): {@code replaces} names a paper of this family that is not this patient's paper waiting for a retake. */
    NOT_A_RETAKE(HttpStatus.BAD_REQUEST);

    /**
     * The application's own reasons ({@link BaymaxException#reason()}) that have a name. Each maps to a name whose status
     * is the one the reason is thrown with ({@code UploadApiReasonsTest} sweeps the upload path's source for both).
     */
    static final Map<String, ApiReason> BY_REASON = Map.ofEntries(
            Map.entry("choose_patient", CHOOSE_PATIENT),
            Map.entry("no_file", NO_FILE),
            Map.entry("empty_file", EMPTY_FILE),
            Map.entry("unsupported_image", UNSUPPORTED_FILE),
            Map.entry("encode_failed", UNSUPPORTED_FILE),
            Map.entry("empty_pdf", UNREADABLE_PDF),
            Map.entry("unreadable_pdf", UNREADABLE_PDF),
            Map.entry("too_many_pages", TOO_MANY_PAGES),
            Map.entry("too_many_files", TOO_MANY_FILES),
            Map.entry("file_too_large", TOO_LARGE),
            Map.entry("request_too_large", TOO_LARGE),
            Map.entry("shared_patient_read_only", VIEW_ONLY),
            Map.entry("upload_key_reused", UPLOAD_KEY_REUSED),
            Map.entry(FreeTierExceededException.DOCUMENTS, FREE_TIER_DOCUMENTS),
            Map.entry("confirm_required", CONFIRM_REQUIRED),
            Map.entry("not_a_retake", NOT_A_RETAKE));

    private final HttpStatus status;

    ApiReason(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public Mono<ServerResponse> respond() {
        return ServerResponse.status(status).contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("reason", name()));
    }

    /**
     * The reason for an error a handler raised: a {@link BaymaxException}'s own reason when it has a name
     * ({@link #BY_REASON}), else by its status, a 401 / 404 / 400 / 415; or
     * input the request itself got wrong (a body the decoder could not read, a binding failure). Empty for anything
     * else (a 403 or a 402 without a {@link #BY_REASON} entry, a 5xx), which the guard answers with the bare status
     * and no body.
     *
     * <p>A bare {@link IllegalArgumentException} is deliberately NOT {@code INVALID}: thrown from deep in a service (an
     * unknown stored enum value, a precondition, {@code UUID.fromString} on stored data) it is a server fault, and
     * calling it the client's would hide a 500 behind "your request was invalid" with no ERROR line. It falls through
     * to the guard's 500. A handler that means a 400 says so with {@link BaymaxException#badRequest}.
     */
    static Optional<ApiReason> of(Throwable e) {
        if (e instanceof BaymaxException be) {
            // Map.of(...).get(null) throws: a BaymaxException without a reason is looked up by its status alone
            ApiReason named = be.reason() == null ? null : BY_REASON.get(be.reason());
            return named != null ? Optional.of(named) : byStatus(be.status().value());
        }
        if (e instanceof ServerWebInputException || e instanceof DecodingException) {
            return Optional.of(INVALID);
        }
        if (e instanceof ResponseStatusException rse) {
            // e.g. a body the decoder cannot read as the route's type: Spring's 415 or 400
            return byStatus(rse.getStatusCode().value());
        }
        return Optional.empty();
    }

    private static Optional<ApiReason> byStatus(int status) {
        return switch (status) {
            case 401 -> Optional.of(UNAUTHENTICATED);
            case 404 -> Optional.of(NOT_FOUND);
            case 400 -> Optional.of(INVALID);
            case 415 -> Optional.of(UNSUPPORTED_MEDIA_TYPE);
            default -> Optional.empty();
        };
    }
}
