package com.elioo.baymax.extraction.application.service;

import com.elioo.baymax.aicall.application.service.MeteredLlmClient;
import com.elioo.baymax.aicall.application.service.MeteredVisionOcr;
import com.elioo.baymax.aicall.domain.AiCallPurpose;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.config.ExtractionModelConfiguration.ExtractionClients;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.VerifiedItems;
import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
import com.elioo.baymax.storage.domain.ObjectKind;
import com.elioo.baymax.storage.domain.StoredObject;
import com.elioo.healthcare.gcp.vision.model.TextBlock;
import com.elioo.healthcare.gcp.vision.model.TextGeometry;
import com.elioo.healthcare.gcp.vision.model.TextParagraph;
import com.elioo.healthcare.gcp.vision.model.TextWord;
import com.elioo.healthcare.gcp.vision.model.VisionOcrRequest;
import com.elioo.healthcare.gcp.vision.model.VisionOcrResponse;
import com.elioo.healthcare.llm.api.LlmClient;
import com.elioo.healthcare.llm.model.LlmRequest;
import com.elioo.healthcare.llm.model.LlmResponse;
import com.elioo.healthcare.llm.model.TokenUsage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The four stages with every port mocked: OCR once per page, the gate, escalation, and crop-or-drop. */
class DocumentExtractionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-14T09:00:00Z");
    private static final UUID FAMILY = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID DOC = UUID.randomUUID();

    private final MeteredVisionOcr ocr = mock(MeteredVisionOcr.class);
    private final MeteredLlmClient metered = mock(MeteredLlmClient.class);
    private final LlmClient cheap = mock(LlmClient.class);
    /** DR-9: the strong tier is its own client, no longer the cheap one wearing a different provider name. */
    private final LlmClient strong = mock(LlmClient.class);
    private final DocumentStorageUseCase storage = mock(DocumentStorageUseCase.class);
    private final DocumentRecordPort records = mock(DocumentRecordPort.class);
    private final BaymaxProperties properties = new BaymaxProperties();

    private DocumentExtractionService service;

    private static final String GOOD_REPLY = """
            {"document_type":"lab_report","document_date":"2026-03-14","facility":"Popular",
             "values":[{"name":"HbA1c","canonical_name":"hba1c","value":"8.2","unit":"%",
                        "flag":"high","source_span":{"page":1,"start":0,"end":5}}],
             "medicines":[],"follow_up":[],
             "clinical_context":{"chief_complaint":[],"history":[],"examination":[],
                                 "diagnosis":[],"investigations_advised":[],"advice":[],"referral":null},
             "confidence":{"overall":0.93,"values":0.93,"medicines":1.0,"follow_up":1.0,
                           "clinical_context":1.0}}
            """;

    @BeforeEach
    void setUp() throws Exception {
        when(cheap.providerName()).thenReturn("groq");
        when(cheap.supportsImages()).thenReturn(false);
        when(metered.using(any())).thenReturn(metered);
        when(ocr.detectDocumentText(any(), any())).thenReturn(Mono.just(visionPage()));
        when(records.update(any())).thenAnswer(i -> Mono.just(i.getArgument(0)));
        when(records.saveExtraction(any(), any())).thenAnswer(i -> Mono.just(i.getArgument(0)));
        when(records.refreshCost(any())).thenReturn(Mono.just(BigDecimal.ONE));
        when(storage.storeCrop(any(), any(), any(), anyString(), any())).thenAnswer(i -> Mono.just(
                new StoredObject(UUID.randomUUID(), FAMILY, PATIENT, DOC, ObjectKind.CROP, null,
                        i.getArgument(3), "key/" + i.getArgument(3, String.class), "image/jpeg", 10, NOW)));

        BaymaxProperties.Marker marker = new BaymaxProperties.Marker();
        marker.setCanonical("hba1c");
        marker.setAliases(List.of("hba1c"));
        properties.setMarkers(List.of(marker));
        // DR-10 turned confidence escalation off by default (threshold 0.0). These tests exercise the
        // escalation path itself, so they pin the threshold the path was designed around.
        properties.getExtract().setStrongModelThreshold(0.85);

        service = new DocumentExtractionService(ocr, metered,
                new ExtractionClients(cheap, null, null, false),
                new ExtractionPromptBuilder(properties),
                new ExtractionJsonReader(new ObjectMapper()),
                new CropCutter(properties), new MarkerMatcher(properties),
                storage, records, properties, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /**
     * Rebuild the service with an escalation client present. Before DR-9 a test could fake this by making
     * the cheap client claim {@code providerName() == "anthropic"}, because the strong tier WAS the cheap
     * client whenever the default provider happened to be Anthropic. That is exactly the coupling that let
     * escalation silently never fire in production, so the test now wires a separate client like the
     * configuration does.
     */
    private void withStrongClient() {
        service = new DocumentExtractionService(ocr, metered,
                new ExtractionClients(cheap, null, strong, false),
                new ExtractionPromptBuilder(properties),
                new ExtractionJsonReader(new ObjectMapper()),
                new CropCutter(properties), new MarkerMatcher(properties),
                storage, records, properties, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** A page whose OCR says "HbA1c 8.2" with real boxes, so spans can resolve to crops. */
    private static VisionOcrResponse visionPage() {
        TextWord hba1c = word("HbA1c", 10, 10, 90, 40);
        TextWord value = word("8.2", 100, 10, 140, 40);
        return new VisionOcrResponse("HbA1c 8.2",
                List.of(new TextBlock("", "en", 0.9f, null,
                        List.of(new TextParagraph("", 0.9f, null, List.of(hba1c, value))), 0, "TEXT")),
                List.of(), 0.92, null);
    }

    private static TextWord word(String text, int l, int t, int r, int b) {
        return new TextWord(text, 0.9f, new TextGeometry(new TextGeometry.BoundingBox(List.of(
                new TextGeometry.Point(l, t), new TextGeometry.Point(r, t),
                new TextGeometry.Point(r, b), new TextGeometry.Point(l, b))), List.of()));
    }

    private static byte[] pageJpeg() throws Exception {
        BufferedImage img = new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    private static Document received(int pages) {
        return new Document(DOC, PATIENT, FAMILY, null, null, null, null, null,
                Document.Status.RECEIVED, null, null, null, pages, NOW, NOW);
    }

    @SuppressWarnings("unchecked")
    private void replyWith(String... replies) {
        var stub = when(metered.invoke(eq(AiCallPurpose.EXTRACT), any(), any(LlmRequest.class),
                any(Function.class)));
        Mono<LlmResponse>[] monos = new Mono[replies.length];
        for (int i = 0; i < replies.length; i++) {
            monos[i] = Mono.just(new LlmResponse(replies[i], "stop", new TokenUsage(100, 50),
                    "openai/gpt-oss-120b", null, "groq", 500L));
        }
        if (monos.length == 1) {
            stub.thenReturn(monos[0]);
        } else {
            stub.thenReturn(monos[0], java.util.Arrays.copyOfRange(monos, 1, monos.length));
        }
    }

    @Test
    void visionIsCalledExactlyOncePerPage() throws Exception {
        replyWith(GOOD_REPLY);

        StepVerifier.create(service.process(received(3), List.of(pageJpeg(), pageJpeg(), pageJpeg())))
                .expectNextCount(1)
                .verifyComplete();

        verify(ocr, times(3)).detectDocumentText(eq(DOC), any(VisionOcrRequest.class));
    }

    @Test
    void aConfidentDocumentIsDoneAndItsValueKeepsItsCropAndCanonicalName() throws Exception {
        replyWith(GOOD_REPLY);

        StepVerifier.create(service.process(received(1), List.of(pageJpeg())))
                .assertNext(document -> {
                    assertThat(document.status()).isEqualTo(Document.Status.DONE);
                    assertThat(document.documentType()).isEqualTo("lab_report");
                    assertThat(document.confidenceOverall()).isEqualTo(0.93);
                    assertThat(document.modelFinal()).isEqualTo("groq/openai/gpt-oss-120b");
                })
                .verifyComplete();

        ArgumentCaptor<VerifiedItems> items = ArgumentCaptor.forClass(VerifiedItems.class);
        verify(records).saveExtraction(any(), items.capture());
        assertThat(items.getValue().observations()).singleElement().satisfies(o -> {
            assertThat(o.name()).isEqualTo("HbA1c");
            assertThat(o.canonicalName()).isEqualTo("hba1c");
            assertThat(o.cropKey()).isEqualTo("key/v1");
            assertThat(o.observedAt()).isEqualTo(Instant.parse("2026-03-14T00:00:00Z"));
        });
        assertThat(items.getValue().unverified().any()).isFalse();
    }

    @Test
    void lowOverallConfidenceNeedsARetakeAndWritesNoItems() throws Exception {
        replyWith(GOOD_REPLY.replace("\"overall\":0.93", "\"overall\":0.55"));

        StepVerifier.create(service.process(received(1), List.of(pageJpeg())))
                .assertNext(document -> {
                    assertThat(document.status()).isEqualTo(Document.Status.NEEDS_RETAKE);
                    assertThat(document.statusReason()).contains("not read confidently");
                })
                .verifyComplete();

        verify(records, never()).saveExtraction(any(), any());
        verify(storage, never()).storeCrop(any(), any(), any(), anyString(), any());
    }

    @Test
    void aWeakSectionAloneAlsoNeedsARetake() throws Exception {
        replyWith(GOOD_REPLY.replace("\"values\":0.93", "\"values\":0.4"));

        StepVerifier.create(service.process(received(1), List.of(pageJpeg())))
                .assertNext(d -> assertThat(d.status()).isEqualTo(Document.Status.NEEDS_RETAKE))
                .verifyComplete();
        verify(records, never()).saveExtraction(any(), any());
    }

    /**
     * BMX-5b: offsets past the end of the text used to drop the item. The item's text is on the page, so
     * it is now cropped from where the text actually is — and stored with that crop.
     */
    @Test
    void aValueWhoseOffsetsAreWrongIsCroppedFromItsOwnTextNotDropped() throws Exception {
        replyWith(GOOD_REPLY.replace("\"start\":0,\"end\":5", "\"start\":900,\"end\":950"));

        StepVerifier.create(service.process(received(1), List.of(pageJpeg())))
                .assertNext(d -> assertThat(d.status()).isEqualTo(Document.Status.DONE))
                .verifyComplete();

        ArgumentCaptor<VerifiedItems> items = ArgumentCaptor.forClass(VerifiedItems.class);
        verify(records).saveExtraction(any(), items.capture());
        assertThat(items.getValue().observations()).hasSize(1);
        assertThat(items.getValue().unverified().total()).isZero();
        verify(storage).storeCrop(any(), any(), any(), eq("v1"), any());
    }

    /** The invariant: text that is not on the page gets no crop, is not stored, and is counted. */
    @Test
    void anItemWhoseSpanResolvesToNothingIsDroppedNotStored() throws Exception {
        replyWith(GOOD_REPLY.replace("HbA1c", "Ferritin"));

        StepVerifier.create(service.process(received(1), List.of(pageJpeg())))
                .assertNext(d -> assertThat(d.status()).isEqualTo(Document.Status.DONE))
                .verifyComplete();

        ArgumentCaptor<VerifiedItems> items = ArgumentCaptor.forClass(VerifiedItems.class);
        verify(records).saveExtraction(any(), items.capture());
        assertThat(items.getValue().observations()).isEmpty();
        // the value whose span pointed nowhere is counted against its own section, not a single total
        assertThat(items.getValue().unverified().values()).isEqualTo(1);
        assertThat(items.getValue().unverified().medicines()).isZero();
        assertThat(items.getValue().unverified().total()).isEqualTo(1);
        verify(storage, never()).storeCrop(any(), any(), any(), anyString(), any());
    }

    @Test
    void aCriticalFlagAsksTheStrongModelEvenWhenConfidenceIsHigh() throws Exception {
        withStrongClient();
        replyWith(GOOD_REPLY.replace("\"flag\":\"high\"", "\"flag\":\"critical\""),
                GOOD_REPLY.replace("\"flag\":\"high\"", "\"flag\":\"critical\"").replace("0.93", "0.97"));

        StepVerifier.create(service.process(received(1), List.of(pageJpeg())))
                .assertNext(d -> assertThat(d.confidenceOverall()).isEqualTo(0.97))
                .verifyComplete();

        verify(metered, times(2)).invoke(eq(AiCallPurpose.EXTRACT), any(), any(LlmRequest.class), any());
    }

    @Test
    void theMoreConfidentAnswerWinsTheEscalation() throws Exception {
        withStrongClient();
        replyWith(GOOD_REPLY.replace("0.93", "0.81"), GOOD_REPLY.replace("0.93", "0.70"));

        StepVerifier.create(service.process(received(1), List.of(pageJpeg())))
                .assertNext(d -> assertThat(d.confidenceOverall()).isEqualTo(0.81))
                .verifyComplete();
    }

    @Test
    void withoutAStrongProviderTheCheapAnswerStands() throws Exception {
        replyWith(GOOD_REPLY.replace("0.93", "0.82"));

        StepVerifier.create(service.process(received(1), List.of(pageJpeg())))
                .assertNext(d -> assertThat(d.status()).isEqualTo(Document.Status.DONE))
                .verifyComplete();

        verify(metered, times(1)).invoke(eq(AiCallPurpose.EXTRACT), any(), any(LlmRequest.class), any());
    }

    @Test
    void aMalformedReplyIsRepairedOnceThenAccepted() throws Exception {
        replyWith("{\"document_type\":\"xray\"}", GOOD_REPLY);

        StepVerifier.create(service.process(received(1), List.of(pageJpeg())))
                .assertNext(d -> assertThat(d.status()).isEqualTo(Document.Status.DONE))
                .verifyComplete();

        verify(metered, times(2)).invoke(eq(AiCallPurpose.EXTRACT), any(), any(LlmRequest.class), any());
    }

    @Test
    void aSecondMalformedReplyFailsTheDocumentWithoutThrowing() throws Exception {
        replyWith("{\"document_type\":\"xray\"}", "{\"still\":\"wrong\"}");

        StepVerifier.create(service.process(received(1), List.of(pageJpeg())))
                .assertNext(document -> {
                    assertThat(document.status()).isEqualTo(Document.Status.FAILED);
                    assertThat(document.statusReason()).isNotBlank();
                })
                .verifyComplete();
        verify(records, atLeastOnce()).update(any());
    }

    @Test
    void aPageWithNoReadableTextNeedsARetakeWithoutCallingAModel() throws Exception {
        when(ocr.detectDocumentText(any(), any())).thenReturn(Mono.just(
                new VisionOcrResponse("", List.of(), List.of(), 0.0, null)));

        StepVerifier.create(service.process(received(1), List.of(pageJpeg())))
                .assertNext(document -> {
                    assertThat(document.status()).isEqualTo(Document.Status.NEEDS_RETAKE);
                    assertThat(document.statusReason()).contains("no readable text");
                })
                .verifyComplete();

        verify(metered, never()).invoke(any(), any(), any(LlmRequest.class), any());
    }

    @Test
    void aFailedProviderCallFailsTheDocumentAndTheCostIsStillRefreshed() throws Exception {
        when(metered.invoke(eq(AiCallPurpose.EXTRACT), any(), any(LlmRequest.class), any()))
                .thenReturn(Mono.error(new IllegalStateException("groq HTTP 503")));

        StepVerifier.create(service.process(received(1), List.of(pageJpeg())))
                .assertNext(document -> {
                    assertThat(document.status()).isEqualTo(Document.Status.FAILED);
                    assertThat(document.statusReason()).contains("503");
                })
                .verifyComplete();

        verify(records).refreshCost(DOC);
    }

    // --- DR-12 re-crop -------------------------------------------------------------------------------

    /** The stored JSON as the pipeline writes it: validated result re-serialised, with the derived usable flag. */
    private static final String STORED_JSON = GOOD_REPLY.replace("\"source_span\":{\"page\":1,\"start\":0,\"end\":5}",
            "\"source_span\":{\"page\":1,\"start\":900,\"end\":950,\"usable\":true}");

    private Document doneDocument() {
        return new Document(DOC, PATIENT, FAMILY, "lab_report", java.time.LocalDate.parse("2026-03-14"), "Popular",
                STORED_JSON, 0.93, Document.Status.DONE, null, "anthropic/claude-sonnet-5", null, 1, NOW, NOW);
    }

    @Test
    void recropWipesOldCropsAndItemsThenReVerifiesFromStoredJsonWithoutAModelCall() throws Exception {
        when(records.find(DOC)).thenReturn(Mono.just(doneDocument()));
        when(storage.pageBytes(FAMILY, PATIENT, DOC, 1)).thenReturn(Mono.just(pageJpeg()));
        when(storage.deleteCrops(FAMILY, PATIENT, DOC)).thenReturn(Mono.just(new com.elioo.baymax.storage.domain.DeletionReport("p/", 1, 1)));
        when(records.deleteItems(DOC)).thenReturn(Mono.just(1L));

        StepVerifier.create(service.recrop(DOC))
                .assertNext(r -> {
                    assertThat(r.outcome()).isEqualTo("recropped");
                    // the offsets pointed past the text; the value is on the page, so it is cropped from its own text
                    assertThat(r.values()).isEqualTo(1);
                    assertThat(r.unverified()).isZero();
                })
                .verifyComplete();

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(storage, records);
        order.verify(storage).deleteCrops(FAMILY, PATIENT, DOC);
        order.verify(records).deleteItems(DOC);
        order.verify(records).saveExtraction(any(), any());
        verify(metered, never()).invoke(any(), any(), any(LlmRequest.class), any());   // no model call
        verify(ocr).detectDocumentText(eq(DOC), any());                                // Vision, once per page
        ArgumentCaptor<Document> saved = ArgumentCaptor.forClass(Document.class);
        verify(records).saveExtraction(saved.capture(), any());
        assertThat(saved.getValue().modelFinal()).isEqualTo("anthropic/claude-sonnet-5");   // written back unchanged
        assertThat(saved.getValue().confidenceOverall()).isEqualTo(0.93);
        assertThat(saved.getValue().status()).isEqualTo(Document.Status.DONE);
    }

    @Test
    void recropSkipsADocumentThatIsNotDoneAndTouchesNothing() {
        when(records.find(DOC)).thenReturn(Mono.just(received(1)));
        StepVerifier.create(service.recrop(DOC))
                .assertNext(r -> assertThat(r.outcome()).isEqualTo("skipped_received"))
                .verifyComplete();
        verify(storage, never()).deleteCrops(any(), any(), any());
        verify(records, never()).deleteItems(any());
        verify(ocr, never()).detectDocumentText(any(), any());
    }
}
