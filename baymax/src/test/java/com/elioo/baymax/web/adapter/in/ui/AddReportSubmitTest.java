package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.common.error.FreeTierExceededException;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.Upload;
import com.elioo.baymax.web.application.port.in.UploadUseCase;
import com.elioo.baymax.web.application.port.in.WebAuthUseCase;
import com.elioo.baymax.web.domain.UploadOptions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Mono;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** POST /app/add: what reaches intake, in what order, and what the family reads when it is refused. */
class AddReportSubmitTest {

    private static final Instant NOW = WebUiTestKit.NOW;
    private final UUID family = WebUiTestKit.FAMILY;
    private WebUiTestKit kit = new WebUiTestKit();
    private UploadUseCase uploads = kit.uploads;
    private WebTestClient client = kit.client;

    private void rebuild(BaymaxProperties properties) {
        kit = new WebUiTestKit(mock(WebAuthUseCase.class), properties, true);
        uploads = kit.uploads;
        client = kit.client;
    }

    private static byte[] jpeg() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(120, 160, BufferedImage.TYPE_INT_RGB), "jpg", out);
        return out.toByteArray();
    }

    private WebTestClient.ResponseSpec post(MultipartBodyBuilder body, MediaType... accept) {
        return client.post().uri("/app/add").cookie("baymax_session", "tok").accept(accept.length == 0 ? new MediaType[]{MediaType.TEXT_HTML} : accept)
                .body(BodyInserters.fromMultipartData(body.build())).exchange();
    }

    @Test
    void everyChosenFileReachesIntakeInTheOrderSentAndTheFamilyLandsOnTheDocument() throws IOException {
        UUID ma = UUID.randomUUID();
        Document doc = Document.received(ma, family, 3, NOW).withId(UUID.randomUUID());
        @SuppressWarnings("unchecked") ArgumentCaptor<List<Upload>> sent = ArgumentCaptor.forClass(List.class);
        when(uploads.upload(eq(family), eq(ma), sent.capture())).thenReturn(Mono.just(doc));
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("patient", ma.toString());
        for (String name : List.of("p1.jpg", "p2.jpg", "p3.jpg")) {
            body.part("file", jpeg()).filename(name);
        }
        post(body).expectStatus().isSeeOther().expectHeader().valueEquals(HttpHeaders.LOCATION, "/app/documents/" + doc.id());
        assertThat(sent.getValue()).extracting(Upload::filename).containsExactly("p1.jpg", "p2.jpg", "p3.jpg");
    }

    /**
     * The no-JS sheet has three file inputs; the two the family did not use arrive as empty parts with filename="".
     * Those are not pages. A file the family DID choose that is empty is still passed on, so intake refuses it by name
     * (PageRendererTest.anEmptyFileIsRejected) — it is never silently dropped.
     *
     * <p>The body is written as a browser writes it ({@code filename=""}, {@code application/octet-stream}): Spring's
     * MultipartBodyBuilder leaves an empty filename out, so the server reads a form field, and a test built with it
     * passes with or without the filter.</p>
     */
    @Test
    void unusedInputsAreNotPagesButAChosenEmptyFileIsStillPassedOn() throws IOException {
        UUID ma = UUID.randomUUID();
        Document doc = Document.received(ma, family, 1, NOW).withId(UUID.randomUUID());
        @SuppressWarnings("unchecked") ArgumentCaptor<List<Upload>> sent = ArgumentCaptor.forClass(List.class);
        when(uploads.upload(eq(family), eq(ma), sent.capture())).thenReturn(Mono.just(doc));

        browserPost(ma, unusedInput(), filePart("p1.jpg", jpeg()), unusedInput()).expectStatus().isSeeOther();
        assertThat(sent.getValue()).extracting(Upload::filename).containsExactly("p1.jpg");

        browserPost(ma, filePart("a.jpg", new byte[0])).expectStatus().isSeeOther();
        assertThat(sent.getValue()).singleElement().satisfies(u -> {
            assertThat(u.filename()).isEqualTo("a.jpg");
            assertThat(u.bytes()).isEmpty();
        });
    }

    private static final String BOUNDARY = "----mediooTestBoundary7MA4YWxkTrZu0gW";

    private static byte[] unusedInput() {
        return ("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n\r\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] filePart(String name, byte[] bytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name + "\"\r\n"
                + "Content-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private WebTestClient.ResponseSpec browserPost(UUID patient, byte[]... files) throws IOException {
        return browserPost(MediaType.TEXT_HTML, patient, files);
    }

    private WebTestClient.ResponseSpec browserPost(MediaType accept, UUID patient, byte[]... files) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"patient\"\r\n\r\n" + patient + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
        for (byte[] f : files) {
            body.write(f);
        }
        body.write(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return client.post().uri("/app/add").cookie("baymax_session", "tok").accept(accept)
                .contentType(MediaType.parseMediaType("multipart/form-data; boundary=" + BOUNDARY))
                .bodyValue(body.toByteArray()).exchange();
    }

    @Test
    void noPatientChosenIsRefusedOnTheSheetAndNothingIsSent() throws IOException {
        when(uploads.uploadOptions(family)).thenReturn(Mono.just(new UploadOptions(
                List.of(AddReportSheetPageTest.patient("আম্মা", 64, true), AddReportSheetPageTest.patient("আব্বু", 72, true)),
                List.of(), AddReportSheetPageTest.used(0))));
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", jpeg()).filename("p1.jpg");
        String html = post(body).expectStatus().isBadRequest().expectBody(String.class).returnResult().getResponseBody();
        assertThat(html).contains("<p id=\"add-alert\" class=\"warn\" role=\"alert\">কার কাগজ বেছে নিন — Medioo নিজে আন্দাজ করবে না</p>");
        // s07--choose-patient draws the sentence once: the alert says it, so the fieldset's hint does not repeat it
        assertThat(html.split("কার কাগজ বেছে নিন", -1)).as("times the sentence is on the page, plus one").hasSize(2);
        verify(uploads, never()).upload(any(), any(), any());
    }

    /** The script asks for JSON and gets the same refusal as data, unescaped, in the page's language (the form's half: aBrowsersFormPost…). */
    @Test
    void theScriptGetsTheRefusalAsJson() throws IOException {
        when(uploads.uploadOptions(family)).thenReturn(Mono.just(new UploadOptions(
                List.of(AddReportSheetPageTest.patient("আম্মা", 64, true), AddReportSheetPageTest.patient("আব্বু", 72, true)),
                List.of(), AddReportSheetPageTest.used(0))));
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", jpeg()).filename("p1.jpg");
        post(body, MediaType.APPLICATION_JSON).expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody().jsonPath("$.reason").isEqualTo("choose_patient")
                .jsonPath("$.text").isEqualTo("কার কাগজ বেছে নিন — Medioo নিজে আন্দাজ করবে না");
    }

    @Test
    void aFileOverTheLimitIsRefusedWithoutReachingIntake() {
        BaymaxProperties small = new BaymaxProperties();
        small.getUpload().setMaxFileBytes(1024);
        rebuild(small);
        when(uploads.uploadOptions(family)).thenReturn(Mono.just(new UploadOptions(
                List.of(AddReportSheetPageTest.patient("আম্মা", 64, true)), List.of(), AddReportSheetPageTest.used(0))));
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("patient", UUID.randomUUID().toString());
        body.part("file", new byte[4096]).filename("big.jpg");
        String html = post(body).expectStatus().isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE).expectBody(String.class).returnResult().getResponseBody();
        assertThat(html).contains("ছবিগুলো অনেক বড়");
        verify(uploads, never()).upload(any(), any(), any());
    }

    /**
     * The other direction of the JSON rule: a browser's form post sends {@code Accept: text/html,…,*}{@code /*;q=0.8}, and
     * {@code *}{@code /*} is "compatible with" JSON. A family pressing পাঠান without script must get the page, never JSON.
     */
    @Test
    void aBrowsersFormPostGetsThePageEvenThoughItAcceptsAnything() throws IOException {
        when(uploads.uploadOptions(family)).thenReturn(Mono.just(new UploadOptions(
                List.of(AddReportSheetPageTest.patient("আম্মা", 64, true), AddReportSheetPageTest.patient("আব্বু", 72, true)),
                List.of(), AddReportSheetPageTest.used(0))));
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", jpeg()).filename("p1.jpg");
        client.post().uri("/app/add").cookie("baymax_session", "tok")
                .header(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .body(BodyInserters.fromMultipartData(body.build())).exchange()
                .expectStatus().isBadRequest().expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML);
    }

    /** Refused after choosing: the sheet comes back with the same patient chosen and the same way back, never a guess. */
    @Test
    void aRefusalKeepsThePatientTheFamilyChoseAndTheWayBack() throws IOException {
        var ma = AddReportSheetPageTest.patient("আম্মা", 64, true);
        var abbu = AddReportSheetPageTest.patient("আব্বু", 72, true);
        when(uploads.uploadOptions(family)).thenReturn(Mono.just(new UploadOptions(List.of(ma, abbu), List.of(), AddReportSheetPageTest.used(0))));
        when(uploads.upload(eq(family), eq(abbu.patient().id()), any())).thenReturn(Mono.error(
                com.elioo.baymax.common.error.BaymaxException.badRequest("unsupported_image", "could not read t6-letter.docx; send a JPEG, PNG or PDF")));
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("patient", abbu.patient().id().toString());
        body.part("back", "/app/timeline?patient=" + abbu.patient().id());
        body.part("file", jpeg()).filename("t6-letter.docx");
        String html = post(body).expectStatus().isBadRequest().expectBody(String.class).returnResult().getResponseBody();
        assertThat(html).contains("value=\"" + abbu.patient().id() + "\" required checked>")
                .contains("value=\"" + ma.patient().id() + "\" required>")
                .contains("href=\"/app/timeline?patient=" + abbu.patient().id() + "\" aria-label=")
                .contains("এই ধরনের ফাইল এখনো খুলতে পারি না").doesNotContain("t6-letter").doesNotContain("could not read");
    }

    /**
     * A parser fact the unused-input guard above rests on, pinned so a Spring upgrade cannot quietly change what that
     * guard covers. Spring 6.2's DefaultPartHttpMessageReader cuts parts at each part's LAST body token; a zero-byte
     * part has no body token, so when another part follows, that part's headers replace it and it never arrives. Only
     * a zero-byte part that is the final part reaches a handler. So (unused, p1, unused) arrives as [p1.jpg, ""] — the
     * guard's filter is exercised by the trailing part — and a CHOSEN empty file is dropped by the parser, not by us,
     * whenever another part follows it (an empty file is not a page, so nothing is lost to the family).
     * If this test fails, the parser changed: re-read the guard above against the new part list.
     */
    @Test
    void theParserHandsOnOnlyAnEmptyPartThatComesLast() throws IOException {
        WebTestClient probe = WebTestClient.bindToRouterFunction(org.springframework.web.reactive.function.server.RouterFunctions.route()
                .POST("/probe", r -> r.multipartData().flatMap(m -> org.springframework.web.reactive.function.server.ServerResponse.ok()
                        .bodyValue(String.join("|", m.getOrDefault("file", List.of()).stream()
                                .map(p -> p instanceof org.springframework.http.codec.multipart.FilePart f ? "[" + f.filename() + "]" : "field")
                                .toList()))))
                .build()).build();
        java.util.function.Function<byte[][], String> parts = files -> {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            for (byte[] f : files) {
                body.writeBytes(f);
            }
            body.writeBytes(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return probe.post().uri("/probe").contentType(MediaType.parseMediaType("multipart/form-data; boundary=" + BOUNDARY))
                    .bodyValue(body.toByteArray()).exchange().expectBody(String.class).returnResult().getResponseBody();
        };
        assertThat(parts.apply(new byte[][]{unusedInput(), filePart("p1.jpg", jpeg()), unusedInput()})).isEqualTo("[p1.jpg]|[]");
        assertThat(parts.apply(new byte[][]{filePart("a.jpg", new byte[0]), filePart("p1.jpg", jpeg())})).isEqualTo("[p1.jpg]");
        assertThat(parts.apply(new byte[][]{filePart("p1.jpg", jpeg()), filePart("a.jpg", new byte[0])})).isEqualTo("[p1.jpg]|[a.jpg]");
    }

    /** The script's success: the document's address as data, exactly — D4 navigates to it. */
    @Test
    void theScriptGetsTheDocumentsAddressAsJson() throws IOException {
        UUID ma = UUID.randomUUID();
        Document doc = Document.received(ma, family, 1, NOW).withId(UUID.randomUUID());
        when(uploads.upload(eq(family), eq(ma), any())).thenReturn(Mono.just(doc));
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("patient", ma.toString());
        body.part("file", jpeg()).filename("p1.jpg");
        post(body, MediaType.APPLICATION_JSON).expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody().jsonPath("$.next").isEqualTo("/app/documents/" + doc.id());
    }

    /** At the cap the script reads the cap's own text — the count and the reset date, unescaped — never the developer message. */
    @Test
    void theScriptGetsTheCapAsJsonWithTheResetDate() throws IOException {
        var ma = AddReportSheetPageTest.patient("আম্মা", 64, true);
        when(uploads.uploadOptions(family)).thenReturn(Mono.just(new UploadOptions(List.of(ma), List.of(), AddReportSheetPageTest.used(3))));
        when(uploads.upload(eq(family), eq(ma.patient().id()), any())).thenReturn(Mono.error(new FreeTierExceededException(
                FreeTierExceededException.DOCUMENTS, "free plan allows 3 document(s) per calendar month")));
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("patient", ma.patient().id().toString());
        body.part("file", jpeg()).filename("p1.jpg");
        post(body, MediaType.APPLICATION_JSON).expectStatus().isEqualTo(HttpStatus.PAYMENT_REQUIRED)
                .expectBody().jsonPath("$.reason").isEqualTo(FreeTierExceededException.DOCUMENTS)
                .jsonPath("$.text").isEqualTo("এ মাসের ৩টি বিনা খরচের কাগজ পড়া হয়ে গেছে। ১ নভেম্বর ২০২৬ থেকে আবার পাঠাতে পারবেন। যেগুলো পড়তে পারিনি সেগুলো গোনা হয়নি।");
    }

    /**
     * The request cap read from Content-Length, before a byte of the body is read. The one file is also over the
     * per-file cap, so only this rule can answer request_too_large: without it, reading the file says file_too_large.
     * The copy is shared, so the path is asserted by reason.
     */
    @Test
    void aRequestDeclaredOverTheLimitIsRefusedBeforeItsBodyIsRead() throws IOException {
        BaymaxProperties small = new BaymaxProperties();
        small.getUpload().setMaxFileBytes(1024);
        small.getUpload().setMaxRequestBytes(2048);
        rebuild(small);
        when(uploads.uploadOptions(family)).thenReturn(Mono.just(new UploadOptions(
                List.of(AddReportSheetPageTest.patient("আম্মা", 64, true)), List.of(), AddReportSheetPageTest.used(0))));
        browserPost(MediaType.APPLICATION_JSON, UUID.randomUUID(), filePart("big.jpg", new byte[4096]))
                .expectStatus().isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE)
                .expectBody().jsonPath("$.reason").isEqualTo("request_too_large");
        verify(uploads, never()).upload(any(), any(), any());
    }

    /**
     * The request cap summed over what was read, for a body that declares no length (chunked): two files each under
     * the per-file cap, together over the request cap. The request is shown to carry no Content-Length, so the
     * pre-check above could not have answered.
     */
    @Test
    void filesEachUnderTheLimitButTogetherOverItAreRefusedWithoutReachingIntake() throws IOException {
        BaymaxProperties small = new BaymaxProperties();
        small.getUpload().setMaxFileBytes(4096);
        small.getUpload().setMaxRequestBytes(6000);
        rebuild(small);
        when(uploads.uploadOptions(family)).thenReturn(Mono.just(new UploadOptions(
                List.of(AddReportSheetPageTest.patient("আম্মা", 64, true)), List.of(), AddReportSheetPageTest.used(0))));
        // intake would accept: without the summed cap the request goes through, and the status says so
        Document doc = Document.received(UUID.randomUUID(), family, 2, NOW).withId(UUID.randomUUID());
        when(uploads.upload(any(), any(), any())).thenReturn(Mono.just(doc));
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("patient", UUID.randomUUID().toString());
        body.part("file", new byte[4000]).filename("p1.jpg");
        body.part("file", new byte[4000]).filename("p2.jpg");
        var result = post(body, MediaType.APPLICATION_JSON).expectStatus().isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE)
                .expectBody().jsonPath("$.reason").isEqualTo("request_too_large").returnResult();
        assertThat(result.getRequestHeaders().getContentLength()).as("the request declared no length").isEqualTo(-1L);
        verify(uploads, never()).upload(any(), any(), any());
    }
}
