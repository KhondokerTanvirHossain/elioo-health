package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.Upload;
import com.elioo.baymax.web.application.port.in.UploadUseCase;
import com.elioo.baymax.web.application.port.in.WebAuthUseCase;
import com.elioo.baymax.web.domain.UploadOptions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ByteArrayResource;
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
     */
    @Test
    void unusedInputsAreNotPagesButAChosenEmptyFileIsStillPassedOn() throws IOException {
        UUID ma = UUID.randomUUID();
        Document doc = Document.received(ma, family, 1, NOW).withId(UUID.randomUUID());
        @SuppressWarnings("unchecked") ArgumentCaptor<List<Upload>> sent = ArgumentCaptor.forClass(List.class);
        when(uploads.upload(eq(family), eq(ma), sent.capture())).thenReturn(Mono.just(doc));

        MultipartBodyBuilder unused = new MultipartBodyBuilder();
        unused.part("patient", ma.toString());
        unused.part("file", new ByteArrayResource(new byte[0]) { @Override public String getFilename() { return ""; } });
        unused.part("file", jpeg()).filename("p1.jpg");
        unused.part("file", new ByteArrayResource(new byte[0]) { @Override public String getFilename() { return ""; } });
        post(unused).expectStatus().isSeeOther();
        assertThat(sent.getValue()).extracting(Upload::filename).containsExactly("p1.jpg");

        MultipartBodyBuilder chosenEmpty = new MultipartBodyBuilder();
        chosenEmpty.part("patient", ma.toString());
        chosenEmpty.part("file", new byte[0]).filename("a.jpg");
        post(chosenEmpty).expectStatus().isSeeOther();
        assertThat(sent.getValue()).singleElement().satisfies(u -> {
            assertThat(u.filename()).isEqualTo("a.jpg");
            assertThat(u.bytes()).isEmpty();
        });
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
        verify(uploads, never()).upload(any(), any(), any());
    }

    /** The script asks for JSON and gets the same refusal as data, unescaped, in the page's language. */
    @Test
    void theScriptGetsTheRefusalAsJsonAndTheFormGetsThePage() throws IOException {
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
}
