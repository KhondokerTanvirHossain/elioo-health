package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.extraction.domain.Upload;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.application.port.in.UploadUseCase;
import com.elioo.baymax.web.domain.WebSession;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.HandlerFilterFunction;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

import static com.elioo.baymax.web.adapter.in.ui.PageChrome.BASE;

/** The timeline's upload form posts here; the patient's timeline is where the family lands after. */
@Component
@RequiredArgsConstructor
public class UploadUiHandler implements WebPages {

    private final UploadUseCase uploads;
    private final PageChrome chrome;

    @Override
    public int order() {
        return 30;   // signed in
    }

    @Override
    public RouterFunction<ServerResponse> routes(HandlerFilterFunction<ServerResponse, ServerResponse> requireSession) {
        return RouterFunctions.route()
                .POST(BASE + "/patients/{id}/upload", this::upload)
                .filter(requireSession)
                .build();
    }

    public Mono<ServerResponse> upload(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        UUID patientId = PageChrome.uuid(request.pathVariable("id"));
        return request.multipartData().flatMap(parts -> {
            List<Part> files = parts.get("file");
            if (files == null || files.isEmpty()) {
                return Mono.error(BaymaxException.badRequest("no_file", chrome.text(request, "timeline.nofile")));
            }
            return Flux.fromIterable(files).filter(FilePart.class::isInstance).cast(FilePart.class)
                    .concatMap(UploadUiHandler::readBytes).collectList()
                    .flatMap(pages -> uploads.upload(session.familyId(), patientId, pages));
        }).flatMap(d -> Html.redirect(Tab.PAPERS.path + "?patient=" + patientId));
    }

    private static Mono<Upload> readBytes(FilePart part) {
        return DataBufferUtils.join(part.content()).map(buffer -> {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            DataBufferUtils.release(buffer);
            return new Upload(part.filename(), bytes);
        });
    }
}
