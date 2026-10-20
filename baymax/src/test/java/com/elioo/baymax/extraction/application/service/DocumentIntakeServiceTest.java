package com.elioo.baymax.extraction.application.service;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.common.error.FreeTierExceededException;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.CreatedDocument;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.Upload;
import com.elioo.baymax.family.application.port.in.FreeTierUseCase;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
import com.elioo.baymax.storage.domain.DeletionReport;
import com.elioo.baymax.storage.domain.ObjectKind;
import com.elioo.baymax.testsupport.TryDocuments;
import com.elioo.baymax.storage.domain.StoredObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentIntakeServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-14T09:00:00Z");
    private static final UUID FAMILY = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();

    private final HealthRecordPort records = mock(HealthRecordPort.class);
    private final DocumentRecordPort documents = mock(DocumentRecordPort.class);
    private final FreeTierUseCase freeTier = mock(FreeTierUseCase.class);
    private final DocumentStorageUseCase storage = mock(DocumentStorageUseCase.class);
    private final DocumentExtractionService extraction = mock(DocumentExtractionService.class);
    private final BaymaxProperties properties = new BaymaxProperties();
    /** F1: the chain's urgency writer; these tests are about intake, so it stores nothing. */
    private final com.elioo.baymax.outbound.application.port.in.DocumentUrgencyUseCase urgency =
            mock(com.elioo.baymax.outbound.application.port.in.DocumentUrgencyUseCase.class);
    private final com.elioo.baymax.outbound.application.port.in.ExplainDocumentUseCase explainer =
            mock(com.elioo.baymax.outbound.application.port.in.ExplainDocumentUseCase.class);
    private final com.elioo.baymax.nudge.application.port.in.NudgeUseCase nudges =
            mock(com.elioo.baymax.nudge.application.port.in.NudgeUseCase.class);

    private DocumentIntakeService service;
    /** The keyed rows createOnce made, by family and stored key (V21's partial unique index, in memory). */
    private final Map<String, Document> keyed = new java.util.concurrent.ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        service = new DocumentIntakeService(records, documents, freeTier, storage, new PageRenderer(),
                properties, Clock.fixed(NOW, ZoneOffset.UTC),
                new DocumentProcessingChain(extraction, explainer, nudges, documents, Clock.fixed(NOW, ZoneOffset.UTC), urgency));
        when(explainer.explain(any())).thenReturn(Mono.empty());
        when(urgency.assessAndStore(any())).thenReturn(Mono.empty());
        when(nudges.onDocumentDone(any())).thenReturn(Mono.empty());

        when(records.findPatient(PATIENT)).thenReturn(Mono.just(new PatientProfile(
                PATIENT, FAMILY, "Ma", 74, PatientProfile.Sex.FEMALE, List.of(), NOW, NOW)));
        when(freeTier.checkCanUploadDocument(FAMILY)).thenReturn(Mono.empty());
        when(freeTier.checkCanUploadDocuments(any(UUID.class), anyInt())).thenReturn(Mono.empty());
        // the keyed rows as DocumentUploadKeyTest proves Postgres keeps them: one per family and key, first insert wins
        when(documents.createOnce(any(), any())).thenAnswer(i -> {
            Document made = ((Document) i.getArgument(0)).withId(UUID.randomUUID());
            String key = i.getArgument(1);
            Document had = key == null ? null : keyed.putIfAbsent(made.familyId() + "|" + key, made);
            return Mono.just(had == null ? new CreatedDocument(made, true) : new CreatedDocument(had, false));
        });
        when(documents.findByUploadKey(any(), any())).thenAnswer(i -> Mono.justOrEmpty(keyed.get(i.getArgument(0) + "|" + i.getArgument(1))));
        when(documents.deleteDocument(any())).thenAnswer(i -> {
            keyed.values().removeIf(d -> d.id().equals(i.getArgument(0)));
            return Mono.just(1L);
        });
        when(storage.storePage(any(), any(), any(), anyInt(), any())).thenAnswer(i -> Mono.just(
                new StoredObject(UUID.randomUUID(), FAMILY, PATIENT, i.getArgument(2), ObjectKind.PAGE,
                        i.getArgument(3), null, "page", "image/jpeg", 10, NOW)));
        when(extraction.process(any(), any())).thenReturn(Mono.empty());
    }

    private static Upload jpeg(String name) throws Exception {
        return jpeg(name, 0x000000);
    }

    /** A page painted one colour: another colour is another paper, byte for byte. */
    private static Upload jpeg(String name, int rgb) throws Exception {
        BufferedImage image = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = image.createGraphics();
        g.setColor(new java.awt.Color(rgb));
        g.fillRect(0, 0, 200, 100);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return new Upload(name, out.toByteArray());
    }

    /** Forgets what the calls so far did, keeping the stubs: the next press is measured on its own. */
    private void nextPress() {
        org.mockito.Mockito.clearInvocations(documents, freeTier, storage, extraction);
    }

    private static org.mockito.ArgumentMatcher<String> keyOf(String press) {
        return k -> k != null && k.startsWith(press + ":");
    }

    @Test
    void anAcceptedUploadStoresEveryPageAndReturnsImmediately() throws Exception {
        StepVerifier.create(service.accept(PATIENT, List.of(jpeg("a.jpg"), jpeg("b.jpg"))))
                .assertNext(document -> {
                    assertThat(document.id()).isNotNull();
                    assertThat(document.status()).isEqualTo(Document.Status.RECEIVED);
                    assertThat(document.pageCount()).isEqualTo(2);
                    assertThat(document.familyId()).isEqualTo(FAMILY);
                })
                .verifyComplete();

        verify(storage, times(2)).storePage(any(), any(), any(), anyInt(), any());
        verify(extraction).process(any(), any());
        // BMX-8: extraction of any document leaves chronic_flags exactly as the family set them
        verify(records, never()).updateChronicFlags(any(), any());
    }

    @Test
    void theFreeTierIsCheckedBeforeAnythingIsStored() throws Exception {
        when(freeTier.checkCanUploadDocument(FAMILY)).thenReturn(Mono.error(
                new FreeTierExceededException(FreeTierExceededException.DOCUMENTS, "3 a month")));

        StepVerifier.create(service.accept(PATIENT, List.of(jpeg("a.jpg"))))
                .expectErrorMatches(e -> e instanceof FreeTierExceededException f
                        && f.reason().equals("free_tier_documents"))
                .verify();

        verify(documents, never()).createOnce(any(), any());
        verify(storage, never()).storePage(any(), any(), any(), anyInt(), any());
        verify(extraction, never()).process(any(), any());
    }

    @Test
    void anUnknownPatientIs404() throws Exception {
        UUID unknown = UUID.randomUUID();
        when(records.findPatient(unknown)).thenReturn(Mono.empty());

        StepVerifier.create(service.accept(unknown, List.of(jpeg("a.jpg"))))
                .expectErrorMatches(e -> e instanceof BaymaxException b && b.status().value() == 404)
                .verify();
    }

    @Test
    void anEmptyUploadListIsRejected() {
        StepVerifier.create(service.accept(PATIENT, List.of()))
                .expectErrorMatches(e -> e instanceof BaymaxException b && b.reason().equals("no_file"))
                .verify();
    }

    @Test
    void tooManyPagesAreRefused() throws Exception {
        properties.getExtract().setMaxPages(2);

        StepVerifier.create(service.accept(PATIENT, List.of(jpeg("a.jpg"), jpeg("b.jpg"), jpeg("c.jpg"))))
                .expectErrorMatches(e -> e instanceof BaymaxException b && b.reason().equals("too_many_pages"))
                .verify();

        verify(documents, never()).createOnce(any(), any());
    }

    // --- D3: one press of "পাঠান" is one document; "আলাদা আলাদা" is all-or-nothing --------------------------------

    /** WhatsApp and the JSON API send no key: the two-argument form is the keyed one with no key, never a replay. */
    @Test
    void withoutAKeyNothingIsLookedUpAndTheDocumentIsCreatedWithNoKey() throws Exception {
        StepVerifier.create(service.accept(PATIENT, List.of(jpeg("a.jpg")))).assertNext(d -> assertThat(d.id()).isNotNull()).verifyComplete();
        verify(documents, never()).findByUploadKey(any(), any());
        verify(documents).createOnce(any(), isNull());
    }

    /** A retry of the same press returns the document it already made: nothing rendered, stored, counted or processed. */
    @Test
    void aRetryWithTheSameKeyAndTheSamePagesReturnsTheFirstDocumentAndCostsNothing() throws Exception {
        CreatedDocument first = service.accept(PATIENT, List.of(jpeg("a.jpg"), jpeg("b.jpg")), "k-7a3e9b10").block();
        assertThat(first.fresh()).isTrue();
        nextPress();
        StepVerifier.create(service.accept(PATIENT, List.of(jpeg("a.jpg"), jpeg("b.jpg")), "k-7a3e9b10"))
                .assertNext(d -> {
                    assertThat(d.document().id()).isEqualTo(first.document().id());
                    assertThat(d.fresh()).as("a replay made nothing").isFalse();
                }).verifyComplete();
        verify(freeTier, never()).checkCanUploadDocument(any(UUID.class));
        verify(documents, never()).createOnce(any(), any());
        verify(storage, never()).storePage(any(), any(), any(), anyInt(), any());
        verify(extraction, never()).process(any(), any());
    }

    /**
     * The dangerous half of "one press is one paper": a sheet restored by Back keeps its key, and the family chooses
     * ANOTHER paper and presses পাঠান. That is a new paper — read, stored, counted — never the old one handed back.
     */
    @Test
    void anotherPaperUnderTheSameKeyIsANewDocumentNotTheOldOne() throws Exception {
        CreatedDocument first = service.accept(PATIENT, List.of(jpeg("a.jpg", 0xFF0000)), "k-back0001").block();
        nextPress();
        StepVerifier.create(service.accept(PATIENT, List.of(jpeg("a.jpg", 0x0000FF)), "k-back0001"))
                .assertNext(d -> {
                    assertThat(d.document().id()).as("the new paper's own document").isNotEqualTo(first.document().id());
                    assertThat(d.fresh()).isTrue();
                }).verifyComplete();
        verify(freeTier).checkCanUploadDocument(FAMILY);
        verify(storage).storePage(any(), any(), any(), eq(1), any());
        verify(extraction).process(any(), any());
    }

    /** One more page added to the restored sheet is another press too: the two pages are a new paper. */
    @Test
    void theSamePagesPlusOneUnderTheSameKeyIsANewDocument() throws Exception {
        CreatedDocument first = service.accept(PATIENT, List.of(jpeg("a.jpg", 0xFF0000)), "k-back0002").block();
        StepVerifier.create(service.accept(PATIENT, List.of(jpeg("a.jpg", 0xFF0000), jpeg("b.jpg", 0x00FF00)), "k-back0002"))
                .assertNext(d -> assertThat(d.document().id()).isNotEqualTo(first.document().id())).verifyComplete();
    }

    /** The other direction: a new press is a new document, and it is counted. */
    @Test
    void aNewKeyIsANewDocumentAndIsCounted() throws Exception {
        StepVerifier.create(service.accept(PATIENT, List.of(jpeg("a.jpg")), "k-new00001"))
                .assertNext(d -> assertThat(d.fresh()).isTrue()).verifyComplete();
        verify(freeTier).checkCanUploadDocument(FAMILY);
        verify(documents).createOnce(any(), org.mockito.ArgumentMatchers.argThat(keyOf("k-new00001")));
        verify(storage).storePage(any(), any(), any(), eq(1), any());
    }

    /** Whatever the press's key and pages, what is stored stays inside V21's CHECK: the longest key the sheet accepts too. */
    @Test
    void theStoredKeyFitsTheDatabasesRuleForTheLongestKeyTheSheetAccepts() throws Exception {
        String longest = "k".repeat(64);
        properties.getExtract().setMaxPages(200);
        List<Upload> many = new java.util.ArrayList<>();
        for (int i = 0; i < 120; i++) {
            many.add(jpeg("p" + i + ".jpg"));
        }
        service.accept(PATIENT, many, longest).block();
        service.acceptEach(PATIENT, many, longest).collectList().block();
        org.mockito.ArgumentCaptor<String> stored = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(documents, times(121)).createOnce(any(), stored.capture());
        assertThat(stored.getAllValues()).allSatisfy(k -> assertThat(k).matches("^[A-Za-z0-9:-]{8,80}$"));
    }

    @Test
    void aKeyThatBelongsToAnotherPatientsDocumentIsAConflictNotAReplay() throws Exception {
        UUID abbu = UUID.randomUUID();
        when(records.findPatient(abbu)).thenReturn(Mono.just(new PatientProfile(abbu, FAMILY, "Abbu", 72, PatientProfile.Sex.MALE, List.of(), NOW, NOW)));
        service.accept(abbu, List.of(jpeg("a.jpg")), "k-other001").block();
        nextPress();
        StepVerifier.create(service.accept(PATIENT, List.of(jpeg("a.jpg")), "k-other001"))
                .expectErrorMatches(e -> e instanceof BaymaxException b && b.reason().equals("upload_key_reused")
                        && b.status().value() == 409)
                .verify();
        verify(documents, never()).createOnce(any(), any());
    }

    /** Two presses racing: createOnce answers fresh=false exactly as DocumentUploadKeyTest proved on Postgres; the loser stores nothing. */
    @Test
    void theLoserOfARaceOnTheSameKeyStoresNoPagesAndStartsNoProcessing() throws Exception {
        Document winner = Document.received(PATIENT, FAMILY, 1, NOW).withId(UUID.randomUUID());
        // doReturn: when(...) would run setUp's answer with null arguments while stubbing
        org.mockito.Mockito.doReturn(Mono.just(new CreatedDocument(winner, false))).when(documents).createOnce(any(), any());
        StepVerifier.create(service.accept(PATIENT, List.of(jpeg("a.jpg")), "k-race0001"))
                .assertNext(d -> {
                    assertThat(d.document().id()).isEqualTo(winner.id());
                    assertThat(d.fresh()).isFalse();
                }).verifyComplete();
        verify(storage, never()).storePage(any(), any(), any(), anyInt(), any());
        verify(extraction, never()).process(any(), any());
    }

    /**
     * The patient rule on the race's path too, not only on the replay's: two presses of one sheet for different patients
     * overlap, and the loser's insert finds the winner's row. That row is another patient's — a conflict, never handed back.
     */
    @Test
    void theLoserOfARaceWhoseRowIsAnotherPatientsGetsAConflictNotTheirDocument() throws Exception {
        Document abbus = Document.received(UUID.randomUUID(), FAMILY, 1, NOW).withId(UUID.randomUUID());
        org.mockito.Mockito.doReturn(Mono.just(new CreatedDocument(abbus, false))).when(documents).createOnce(any(), any());
        StepVerifier.create(service.accept(PATIENT, List.of(jpeg("a.jpg")), "k-race0002"))
                .expectErrorMatches(e -> e instanceof BaymaxException b && b.reason().equals("upload_key_reused")
                        && b.status().value() == 409)
                .verify();
        verify(storage, never()).storePage(any(), any(), any(), anyInt(), any());
    }

    /**
     * The winner's row can vanish between the loser's insert and its read (the winner's store failed and removed it):
     * createOnce then answers nothing. The press is refused, so the sheet is drawn again with a new key — never an empty
     * answer that leaves the family's request without a response.
     */
    @Test
    void aRowThatVanishedMidRaceIsRefusedNotAnEmptyAnswer() throws Exception {
        org.mockito.Mockito.doReturn(Mono.empty()).when(documents).createOnce(any(), any());
        StepVerifier.create(service.accept(PATIENT, List.of(jpeg("a.jpg")), "k-race0003"))
                .expectErrorMatches(e -> e instanceof BaymaxException b && b.reason().equals("upload_key_reused"))
                .verify();
        StepVerifier.create(service.acceptEach(PATIENT, List.of(jpeg("a.jpg")), "k-race0004"))
                .expectErrorMatches(e -> e instanceof BaymaxException b && b.reason().equals("upload_key_reused"))
                .verify();
    }

    /** No row without its pages: storage failing on page 2 removes the row, so the retry with the same key starts clean. */
    @Test
    void aStorageFailureLeavesNoDocumentBehindAndTheRetryStartsClean() throws Exception {
        when(storage.storePage(any(), any(), any(), eq(2), any())).thenReturn(Mono.error(new IllegalStateException("bucket down")));
        when(storage.deleteDocument(any(), any(), any())).thenReturn(Mono.just(new DeletionReport("p/", 1, 1)));
        StepVerifier.create(service.accept(PATIENT, List.of(jpeg("a.jpg"), jpeg("b.jpg")), "k-store001"))
                .expectErrorMessage("bucket down").verify();
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(storage, documents);
        order.verify(storage).deleteDocument(eq(FAMILY), eq(PATIENT), any());
        order.verify(documents).deleteDocument(any());
        verify(extraction, never()).process(any(), any());
        assertThat(keyed).as("no keyed row is left").isEmpty();

        when(storage.storePage(any(), any(), any(), eq(2), any())).thenAnswer(i -> Mono.just(new StoredObject(UUID.randomUUID(),
                FAMILY, PATIENT, i.getArgument(2), ObjectKind.PAGE, 2, null, "page", "image/jpeg", 10, NOW)));
        StepVerifier.create(service.accept(PATIENT, List.of(jpeg("a.jpg"), jpeg("b.jpg")), "k-store001"))
                .assertNext(d -> assertThat(d.fresh()).as("the retry makes the document").isTrue()).verifyComplete();
    }

    /** The row still goes when removing the stored page fails too: an orphan object is harmless, a pageless row is not. */
    @Test
    void theRowGoesEvenWhenRemovingItsStoredPagesFails() throws Exception {
        when(storage.storePage(any(), any(), any(), eq(1), any())).thenReturn(Mono.error(new IllegalStateException("bucket down")));
        when(storage.deleteDocument(any(), any(), any())).thenReturn(Mono.error(new IllegalStateException("still down")));
        StepVerifier.create(service.accept(PATIENT, List.of(jpeg("a.jpg")), "k-store002"))
                .expectErrorMessage("bucket down").verify();
        verify(documents).deleteDocument(any());
        assertThat(keyed).isEmpty();
        verify(extraction, never()).process(any(), any());
    }

    /** "আলাদা আলাদা": one document per file, each under its own key of the press, each counted. */
    @Test
    void separatePagesAreOneDocumentEach() throws Exception {
        when(freeTier.checkCanUploadDocuments(FAMILY, 3)).thenReturn(Mono.empty());
        StepVerifier.create(service.acceptEach(PATIENT, List.of(jpeg("a.jpg"), jpeg("b.jpg"), jpeg("c.jpg")), "k-sep00001").collectList())
                .assertNext(docs -> assertThat(docs).hasSize(3).allSatisfy(d -> {
                            assertThat(d.document().pageCount()).isEqualTo(1);
                            assertThat(d.fresh()).isTrue();
                        }).extracting(d -> d.document().id()).doesNotHaveDuplicates())
                .verifyComplete();
        org.mockito.ArgumentCaptor<String> stored = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(documents, times(3)).createOnce(any(), stored.capture());
        assertThat(stored.getAllValues()).doesNotHaveDuplicates().allSatisfy(k -> assertThat(k).startsWith("k-sep00001:"));
        for (int i = 0; i < 3; i++) {
            assertThat(stored.getAllValues().get(i)).endsWith(":" + i);
        }
        verify(extraction, times(3)).process(any(), any());
    }

    /** A retry of a separate press that half-landed: the stored one comes back, only the missing one is made and counted. */
    @Test
    void aRetryOfSeparatePagesMakesOnlyTheMissingOnesAndCountsOnlyThem() throws Exception {
        java.util.concurrent.atomic.AtomicInteger stores = new java.util.concurrent.atomic.AtomicInteger();
        when(storage.storePage(any(), any(), any(), anyInt(), any())).thenAnswer(i -> stores.incrementAndGet() == 2
                ? Mono.error(new IllegalStateException("bucket down"))
                : Mono.just(new StoredObject(UUID.randomUUID(), FAMILY, PATIENT, i.getArgument(2), ObjectKind.PAGE, 1, null, "page", "image/jpeg", 10, NOW)));
        when(storage.deleteDocument(any(), any(), any())).thenReturn(Mono.just(new DeletionReport("p/", 1, 1)));
        List<Upload> two = List.of(jpeg("a.jpg", 0xFF0000), jpeg("b.jpg", 0x00FF00));
        StepVerifier.create(service.acceptEach(PATIENT, two, "k-sep00004")).expectNextCount(1).expectErrorMessage("bucket down").verify();
        Document zero = keyed.values().iterator().next();
        assertThat(keyed).as("the first paper landed, the second did not").hasSize(1);
        nextPress();
        StepVerifier.create(service.acceptEach(PATIENT, two, "k-sep00004").collectList())
                .assertNext(docs -> {
                    assertThat(docs).hasSize(2);
                    assertThat(docs.get(0).document().id()).isEqualTo(zero.id());
                    assertThat(docs.get(0).fresh()).isFalse();
                    assertThat(docs.get(1).document().id()).isNotEqualTo(zero.id());
                    assertThat(docs.get(1).fresh()).isTrue();
                })
                .verifyComplete();
        verify(freeTier).checkCanUploadDocuments(FAMILY, 1);
        verify(documents, times(1)).createOnce(any(), any());
        verify(extraction, times(1)).process(any(), any());
    }

    /** "আলাদা আলাদা" from a restored sheet with other pages: new papers, none of the old ones handed back. */
    @Test
    void otherPagesAsSeparatePapersUnderTheSameKeyAreNewDocuments() throws Exception {
        List<CreatedDocument> first = service.acceptEach(PATIENT, List.of(jpeg("a.jpg", 0xFF0000), jpeg("b.jpg", 0x00FF00)), "k-sep00006")
                .collectList().block();
        nextPress();
        StepVerifier.create(service.acceptEach(PATIENT, List.of(jpeg("c.jpg", 0x0000FF), jpeg("d.jpg", 0xFFFF00)), "k-sep00006").collectList())
                .assertNext(docs -> assertThat(docs).allSatisfy(d -> assertThat(d.fresh()).isTrue())
                        .extracting(d -> d.document().id()).doesNotContainAnyElementsOf(first.stream().map(d -> d.document().id()).toList()))
                .verifyComplete();
        verify(freeTier).checkCanUploadDocuments(FAMILY, 2);
    }

    /** The dangerous direction: one unreadable file among separate pages refuses them all before any document exists. */
    @Test
    void oneUnreadableFileAmongSeparatePagesRefusesAllBeforeAnyDocumentExists() throws Exception {
        when(freeTier.checkCanUploadDocuments(FAMILY, 3)).thenReturn(Mono.empty());
        StepVerifier.create(service.acceptEach(PATIENT, List.of(jpeg("a.jpg"), new Upload("x.docx", TryDocuments.docx()), jpeg("c.jpg")), "k-sep00002"))
                .expectErrorMatches(e -> e instanceof BaymaxException b && b.reason().equals("unsupported_image"))
                .verify();
        verify(documents, never()).createOnce(any(), any());
        verify(storage, never()).storePage(any(), any(), any(), anyInt(), any());
    }

    @Test
    void separatePagesAskTheAllowanceForAllOfThemAtOnce() throws Exception {
        when(freeTier.checkCanUploadDocuments(FAMILY, 3)).thenReturn(Mono.error(
                new FreeTierExceededException(FreeTierExceededException.DOCUMENTS, "cap")));
        StepVerifier.create(service.acceptEach(PATIENT, List.of(jpeg("a.jpg"), jpeg("b.jpg"), jpeg("c.jpg")), "k-sep00003"))
                .expectError(FreeTierExceededException.class).verify();
        verify(documents, never()).createOnce(any(), any());
        verify(freeTier, never()).checkCanUploadDocument(any(UUID.class));
    }

    @Test
    void separatePagesWithNoFileAreRefused() {
        StepVerifier.create(service.acceptEach(PATIENT, List.of(), "k-sep00005"))
                .expectErrorMatches(e -> e instanceof BaymaxException b && b.reason().equals("no_file"))
                .verify();
    }

    @Test
    void aPendingDocumentShowsStatusOnly() {
        UUID id = UUID.randomUUID();
        when(documents.find(id)).thenReturn(Mono.just(new Document(id, PATIENT, FAMILY, null, null, null,
                null, null, Document.Status.PROCESSING, null, null, null, 1, NOW, NOW)));

        StepVerifier.create(service.view(id))
                .assertNext(view -> {
                    assertThat(view.status()).isEqualTo("PROCESSING");
                    assertThat(view.values()).isNull();
                })
                .verifyComplete();

        verify(documents, never()).observationsOf(any());
    }

    /** F5: a retake's view carries its reason CODE, the thing a page maps to copy; the developer prose stays behind. */
    @Test
    void aRetakeShowsItsReasonCodeNeverItsProse() {
        UUID id = UUID.randomUUID();
        when(documents.find(id)).thenReturn(Mono.just(new Document(id, PATIENT, FAMILY, null, null, null,
                null, null, Document.Status.NEEDS_RETAKE, "too blurry", null, null, 1, NOW, NOW, null, null, "low_overall")));

        StepVerifier.create(service.view(id))
                .assertNext(view -> {
                    assertThat(view.status()).isEqualTo("NEEDS_RETAKE");
                    assertThat(view.reasonCode()).isEqualTo("low_overall");
                })
                .verifyComplete();
    }

    @Test
    void aDoneDocumentShowsItsItemsWithCropKeys() {
        UUID id = UUID.randomUUID();
        when(documents.find(id)).thenReturn(Mono.just(new Document(id, PATIENT, FAMILY, "lab_report",
                java.time.LocalDate.parse("2026-03-14"), "Popular", "{}", 0.93, Document.Status.DONE,
                null, "groq/gpt-oss", null, 1, NOW, NOW)));
        when(documents.observationsOf(id)).thenReturn(Flux.just(
                Map.of("name", "HbA1c", "value", "8.2", "crop_key", "f/p/d/crop-v1.jpg")));
        when(documents.medicinesOf(id)).thenReturn(Flux.empty());
        when(documents.followUpsOf(id)).thenReturn(Flux.empty());
        when(documents.clinicalContextOf(id)).thenReturn(Mono.just(
                Map.of("diagnosis", List.of(Map.of("text", "Type 2 Diabetes Mellitus")))));

        StepVerifier.create(service.view(id))
                .assertNext(view -> {
                    assertThat(view.status()).isEqualTo("DONE");
                    assertThat(view.documentType()).isEqualTo("lab_report");
                    assertThat(view.documentDate()).isEqualTo("2026-03-14");
                    assertThat(view.model()).isEqualTo("groq/gpt-oss");
                    assertThat(view.values()).singleElement()
                            .satisfies(v -> assertThat(v).containsEntry("crop_key", "f/p/d/crop-v1.jpg"));
                    assertThat(view.clinicalContext()).containsKey("diagnosis");
                })
                .verifyComplete();
    }

    /**
     * A document whose clinical_context is absent must still return its values: the context is an extra
     * section, not a precondition. This is a {@code Mono.zip}, which completes empty if any source does,
     * so an empty context here would silently turn a good document into a 404-shaped empty response.
     */
    @Test
    void aDocumentWithoutClinicalContextStillShowsItsValues() {
        UUID id = UUID.randomUUID();
        when(documents.find(id)).thenReturn(Mono.just(new Document(id, PATIENT, FAMILY, "lab_report",
                java.time.LocalDate.parse("2026-03-14"), "Popular", "{}", 0.93, Document.Status.DONE,
                null, "groq/gpt-oss", null, 1, NOW, NOW)));
        when(documents.observationsOf(id)).thenReturn(Flux.just(
                Map.of("name", "HbA1c", "value", "8.2", "crop_key", "f/p/d/crop-v1.jpg")));
        when(documents.medicinesOf(id)).thenReturn(Flux.empty());
        when(documents.followUpsOf(id)).thenReturn(Flux.empty());
        when(documents.clinicalContextOf(id)).thenReturn(Mono.empty());

        StepVerifier.create(service.view(id))
                .assertNext(view -> {
                    assertThat(view.values()).hasSize(1);
                    assertThat(view.clinicalContext()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void anUnknownDocumentIs404() {
        UUID id = UUID.randomUUID();
        when(documents.find(id)).thenReturn(Mono.empty());

        StepVerifier.create(service.view(id))
                .expectErrorMatches(e -> e instanceof BaymaxException b
                        && b.reason().equals("document_not_found"))
                .verify();
    }

    /** F9b moved the explain and nudge lines into DocumentProcessingChain; the intake still logs the finish. */
    private static final List<Class<?>> LOGGERS = List.of(DocumentIntakeService.class, DocumentProcessingChain.class);

    private static ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> captureAtDebug() {
        var logged = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logged.start();
        for (Class<?> owner : LOGGERS) {
            ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(owner);
            logger.addAppender(logged);
            // DEBUG on, so the test sees the detail line exists and still asserts nothing above DEBUG carries it;
            // at INFO the detail line would simply not be emitted and the check would pass for the wrong reason
            logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        }
        return logged;
    }

    private static void release(ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logged) {
        for (Class<?> owner : LOGGERS) {
            ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(owner);
            logger.detachAppender(logged);
            logger.setLevel(null);
        }
    }

    private static String at(ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logged,
                             java.util.function.Predicate<ch.qos.logback.classic.Level> level) {
        return String.join("\n", logged.list.stream().filter(e -> level.test(e.getLevel()))
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList());
    }

    /** Finding 2: the explanation error's message carries medicine text (MedicineTranscription.elided), logged at ERROR. */
    @Test
    void anExplanationErrorIsLoggedWithoutThePatientTextItCarries() throws Exception {
        var logged = captureAtDebug();
        try {
            Document done = new Document(UUID.randomUUID(), PATIENT, FAMILY, "prescription", null, null, "{}", 0.9,
                    Document.Status.DONE, null, "m", null, 1, NOW, NOW);
            when(extraction.process(any(), any())).thenReturn(Mono.just(done));
            when(explainer.explain(done.id())).thenReturn(Mono.error(new IllegalStateException(
                    "medicine instruction is abbreviated, not transcribed: [frequency_text:Cap. DDR ১+০+০ ... ১+০+১]")));

            service.accept(PATIENT, List.of(jpeg("a.jpg"))).block();
            verify(nudges, org.mockito.Mockito.timeout(2000)).onDocumentDone(done.id());

            String aboveDebug = at(logged, l -> l.isGreaterOrEqual(ch.qos.logback.classic.Level.INFO));
            assertThat(aboveDebug).contains("explanation failed")
                    .as("patient medicine text must not reach the log").doesNotContain("Cap. DDR").doesNotContain("১+০+০");
            assertThat(at(logged, l -> l == ch.qos.logback.classic.Level.DEBUG))
                    .as("the detail is kept, at DEBUG only").contains("explanation failure detail").contains("Cap. DDR");
        } finally {
            release(logged);
        }
    }

    /** The nudge-check line printed e.getMessage(); a nudge failure can carry the same stored strings. */
    @Test
    void aNudgeCheckErrorIsLoggedWithoutItsText() throws Exception {
        var logged = captureAtDebug();
        try {
            Document done = new Document(UUID.randomUUID(), PATIENT, FAMILY, "prescription", null, null, "{}", 0.9,
                    Document.Status.DONE, null, "m", null, 1, NOW, NOW);
            when(extraction.process(any(), any())).thenReturn(Mono.just(done));
            when(nudges.onDocumentDone(done.id())).thenReturn(Mono.error(new IllegalStateException(
                    "nudge body over the cap: Tab. Napa Extend 665 mg ১+০+১")));

            service.accept(PATIENT, List.of(jpeg("a.jpg"))).block();
            verify(nudges, org.mockito.Mockito.timeout(2000)).onDocumentDone(done.id());
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(2))
                    .until(() -> at(logged, l -> true).contains("document finished"));

            String aboveDebug = at(logged, l -> l.isGreaterOrEqual(ch.qos.logback.classic.Level.INFO));
            assertThat(aboveDebug).as("the line exists").contains("nudge check failed");
            assertThat(aboveDebug).as("exception text is never logged above DEBUG").doesNotContain("Napa");
        } finally {
            release(logged);
        }
    }
}
