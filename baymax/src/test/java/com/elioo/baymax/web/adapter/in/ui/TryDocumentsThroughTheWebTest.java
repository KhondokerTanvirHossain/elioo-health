package com.elioo.baymax.web.adapter.in.ui;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.application.service.DocumentExtractionService;
import com.elioo.baymax.extraction.application.service.DocumentIntakeService;
import com.elioo.baymax.extraction.application.service.DocumentProcessingChain;
import com.elioo.baymax.extraction.application.service.PageRenderer;
import com.elioo.baymax.extraction.domain.CreatedDocument;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.family.application.service.FreeTierService;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientAccess;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.nudge.application.port.in.NudgeUseCase;
import com.elioo.baymax.outbound.application.port.in.ExplainDocumentUseCase;
import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
import com.elioo.baymax.storage.domain.ObjectKind;
import com.elioo.baymax.storage.domain.StoredObject;
import com.elioo.baymax.testsupport.LoggedText;
import com.elioo.baymax.testsupport.TryDocuments;
import com.elioo.baymax.testsupport.UploadRefusalSweep;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;
import com.elioo.baymax.web.application.port.out.AuditPort;
import com.elioo.baymax.web.application.service.UploadService;
import com.elioo.baymax.web.domain.FamilyOverview;
import com.elioo.baymax.web.domain.PatientSummary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.elioo.baymax.testsupport.TryDocuments.docx;
import static com.elioo.baymax.testsupport.TryDocuments.jpeg;
import static com.elioo.baymax.testsupport.TryDocuments.twoPagePdf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The six try documents (~/medioo-try-docs, Phase 0 P.2) through the web upload, with the real intake. */
class TryDocumentsThroughTheWebTest {

    private static final Instant NOW = Instant.parse("2026-10-19T08:00:00Z");
    private final HealthRecordPort records = mock(HealthRecordPort.class);
    private final DocumentRecordPort documents = mock(DocumentRecordPort.class);
    private final DocumentStorageUseCase storage = mock(DocumentStorageUseCase.class);
    private final DocumentExtractionService extraction = mock(DocumentExtractionService.class);
    private final TimelineUseCase timeline = mock(TimelineUseCase.class);
    private final UUID family = WebUiTestKit.FAMILY;
    private final PatientProfile ma = new PatientProfile(WebUiTestKit.MA_ID, family, "আম্মা", 64, PatientProfile.Sex.FEMALE, List.of(), NOW, NOW);
    private WebTestClient client;
    private ListAppender<ILoggingEvent> logged;
    private Level levelBefore;

    @BeforeEach
    void wire() {
        BaymaxProperties props = new BaymaxProperties();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        FreeTierService freeTier = new FreeTierService(records, documents, props, clock);
        ExplainDocumentUseCase explainer = mock(ExplainDocumentUseCase.class);
        com.elioo.baymax.outbound.application.port.in.DocumentUrgencyUseCase urgency =
                mock(com.elioo.baymax.outbound.application.port.in.DocumentUrgencyUseCase.class);
        when(urgency.assessAndStore(any())).thenReturn(Mono.empty());
        NudgeUseCase nudges = mock(NudgeUseCase.class);
        when(explainer.explain(any())).thenReturn(Mono.empty());
        when(nudges.onDocumentDone(any())).thenReturn(Mono.empty());
        when(extraction.process(any(), any())).thenReturn(Mono.empty());
        DocumentIntakeService intake = new DocumentIntakeService(records, documents, freeTier, storage, new PageRenderer(), props, clock,
                new DocumentProcessingChain(extraction, explainer, nudges, documents, clock, urgency));
        AuditPort audit = mock(AuditPort.class);
        when(audit.record(any())).thenReturn(Mono.empty());
        FamilyAccount account = new FamilyAccount(family, "+8801700000000", "Tanvir", FamilyAccount.Plan.FREE, NOW, NOW);
        when(timeline.me(family)).thenReturn(Mono.just(new FamilyOverview(account, List.of(new PatientSummary(ma, true, 0, null)))));
        when(records.findFamily(family)).thenReturn(Mono.just(account));
        when(records.findPatient(ma.id())).thenReturn(Mono.just(ma));
        when(records.visiblePatient(family, ma.id())).thenReturn(Mono.just(new PatientAccess(ma, true)));
        when(documents.countInMonth(eq(family), any())).thenReturn(Mono.just(0L));
        when(documents.createOnce(any(), any())).thenAnswer(i -> Mono.just(new CreatedDocument(((Document) i.getArgument(0)).withId(UUID.randomUUID()), true)));
        when(documents.findByUploadKey(any(), any())).thenReturn(Mono.empty());
        when(storage.storePage(any(), any(), any(), anyInt(), any())).thenAnswer(i -> Mono.just(new StoredObject(UUID.randomUUID(),
                family, ma.id(), i.getArgument(2), ObjectKind.PAGE, i.getArgument(3), null, "page", "image/jpeg", 10, NOW)));
        client = new WebUiTestKit(new UploadService(timeline, records, freeTier, intake, audit, clock)).client;

        Logger baymax = (Logger) LoggerFactory.getLogger("com.elioo.baymax");
        levelBefore = baymax.getLevel();
        baymax.setLevel(Level.INFO);   // INFO events must reach the appender, or the absence below proves nothing
        logged = new ListAppender<>();
        logged.start();
        baymax.addAppender(logged);
    }

    @AfterEach
    void release() {
        Logger baymax = (Logger) LoggerFactory.getLogger("com.elioo.baymax");
        baymax.detachAppender(logged);
        baymax.setLevel(levelBefore);
    }

    private WebTestClient.ResponseSpec send(String filename, byte[] bytes) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("patient", ma.id().toString());
        body.part("file", bytes).filename(filename);
        return client.post().uri("/app/add").cookie("baymax_session", "tok").body(BodyInserters.fromMultipartData(body.build())).exchange();
    }

    /** t1: one document, bytes untouched; the filename (it may name the patient) reaches no INFO line — and the INFO line exists. */
    @Test
    void aPhotographedLabReportBecomesOneDocumentWithItsBytesUntouchedAndItsNameIsNeverLogged() throws IOException {
        byte[] t1 = jpeg(1240, 1754);
        send("Rokeya-Begum-CBC.jpg", t1).expectStatus().isSeeOther()
                .expectHeader().value(HttpHeaders.LOCATION, l -> assertThat(l).matches("/app/documents/[0-9a-f-]{36}"));
        ArgumentCaptor<byte[]> page = ArgumentCaptor.forClass(byte[].class);
        verify(storage).storePage(eq(family), eq(ma.id()), any(), eq(1), page.capture());
        assertThat(page.getValue()).isEqualTo(t1);   // a JPEG is passed through, never re-compressed (PageRenderer)
        List<String> info = LoggedText.atInfoOrAbove(logged.list);   // message and attached throwable chain
        assertThat(info).as("the upload's own INFO line was captured").anyMatch(m -> m.startsWith("[baymax] web upload documentId="));
        assertThat(info).noneMatch(m -> m.contains("Rokeya"));
    }

    @Test
    void aTwoPagePdfIsOneDocumentOfTwoPages() throws IOException {
        send("t5.pdf", twoPagePdf()).expectStatus().isSeeOther();
        ArgumentCaptor<Document> created = ArgumentCaptor.forClass(Document.class);
        verify(documents).createOnce(created.capture(), any());
        assertThat(created.getValue().pageCount()).isEqualTo(2);
        verify(storage).storePage(eq(family), eq(ma.id()), any(), eq(1), any());
        verify(storage).storePage(eq(family), eq(ma.id()), any(), eq(2), any());
    }

    /** t6: refused in Bangla, with no developer text and no filename, and no row — so nothing can count against the month. */
    @Test
    void aWordDocumentIsRefusedInBanglaAndCostsNothing() throws IOException {
        String html = send("t6-letter.docx", docx()).expectStatus().isBadRequest().expectBody(String.class).returnResult().getResponseBody();
        assertThat(html).contains("এই ধরনের ফাইল এখনো খুলতে পারি না। কাগজের ছবি বা PDF পাঠান।")
                .doesNotContain("could not read").doesNotContain("letter").doesNotContain("JPEG, PNG or PDF");
        // the refusal's own message names the file ("could not read t6-letter.docx; …"): the likeliest leak is here
        List<String> info = LoggedText.atInfoOrAbove(logged.list);
        assertThat(info).as("the refusal's own INFO line was captured")
                .anyMatch(m -> m.startsWith("[baymax] web upload refused reason=unsupported_image status=400"));
        assertThat(info).noneMatch(m -> m.contains("t6-letter") || m.contains("could not read"));
        verify(documents, never()).createOnce(any(), any());
        verify(storage, never()).storePage(any(), any(), any(), anyInt(), any());
    }

    @Test
    void elevenPagesAreRefusedWithTheLimitAndNothingIsCreated() throws IOException {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("patient", ma.id().toString());
        for (int i = 1; i <= 11; i++) {
            body.part("file", jpeg(100, 140)).filename("p" + i + ".jpg");
        }
        String html = client.post().uri("/app/add").cookie("baymax_session", "tok").body(BodyInserters.fromMultipartData(body.build()))
                .exchange().expectStatus().isBadRequest().expectBody(String.class).returnResult().getResponseBody();
        assertThat(html).contains("একটা কাগজে সবচেয়ে বেশি ১০টি পাতা");
        verify(documents, never()).createOnce(any(), any());
    }

    @Test
    void atTheCapTheRefusalNamesTheResetDateNotTheDeveloperMessage() throws IOException {
        when(documents.countInMonth(eq(family), any())).thenReturn(Mono.just(3L));
        String html = send("t1.jpg", jpeg(1240, 1754)).expectStatus().isEqualTo(HttpStatus.PAYMENT_REQUIRED)
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(html).contains("১ নভেম্বর ২০২৬").doesNotContain("free plan allows");
        verify(documents, never()).createOnce(any(), any());
    }

    /**
     * Every refusal intake or the handler can throw has its own copy: derived from the sources, not a list from memory.
     * The files are {@link UploadRefusalSweep#sources}: the whole extraction/application tree (intake, the renderer,
     * anything added there later), the web files that refuse an upload by name, and every web file that calls or
     * implements UploadUseCase. The size refusals live in MultipartUploads, which mentions no UploadUseCase: they must be
     * found by name, or this guard would pass with both missing.
     */
    @Test
    void everyReasonAnUploadCanBeRefusedForHasItsOwnCopy() throws IOException {
        assertThat(UploadRefusalSweep.sources()).extracting(p -> p.getFileName().toString())
                .as("the upload path's files were found").contains("PageRenderer.java", "DocumentIntakeService.java", "UploadUiHandler.java", "UploadService.java",
                        "MultipartUploads.java", "UploadApiHandler.java");
        Set<String> reasons = UploadRefusalSweep.reasonsWithStatus().keySet();
        // shared_patient_read_only is thrown by UploadService; file_too_large and request_too_large by the handler's tooLarge
        assertThat(reasons).as("the sweep found the refusals; an empty set would pass vacuously").hasSizeGreaterThanOrEqualTo(10);
        assertThat(reasons).as("the size refusals were swept").contains("file_too_large", "request_too_large");
        assertThat(reasons).allSatisfy(r -> assertThat(UploadUiHandler.REFUSALS).as(r).containsKey(r));
    }

    /**
     * The other half: every key REFUSALS maps to is real copy in both languages, read the way the sheet reads it. A typo
     * in a value would make UiCopy.t throw, and the family's refusal would become a 500.
     */
    @Test
    void everyRefusalNamesCopyThatExistsInBothLanguages() {
        UiCopy copy = new UiCopy();
        assertThat(UploadUiHandler.REFUSALS).as("refusals read; none would pass vacuously").hasSizeGreaterThanOrEqualTo(10);
        for (Lang lang : Lang.values()) {
            UploadUiHandler.REFUSALS.forEach((reason, key) -> assertThat(copy.maybe(lang, key)).as(lang + " " + reason + " → " + key).isNotBlank());
            UploadUiHandler.REFUSALS.forEach((reason, key) -> assertThat(copy.t(lang, key, 10)).as(lang + " " + reason).doesNotContain("{"));
        }
    }
}
