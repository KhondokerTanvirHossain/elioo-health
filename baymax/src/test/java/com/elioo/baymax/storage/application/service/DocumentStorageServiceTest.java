package com.elioo.baymax.storage.application.service;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.storage.application.port.out.StoragePort;
import com.elioo.baymax.storage.application.port.out.StoredObjectLedgerPort;
import com.elioo.baymax.storage.domain.ObjectKind;
import com.elioo.baymax.storage.domain.StoredObject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentStorageServiceTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 1, 2, 3};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0, 1, 2, 3};

    private final StoragePort storage = mock(StoragePort.class);
    private final StoredObjectLedgerPort ledger = mock(StoredObjectLedgerPort.class);
    private final UUID family = UUID.randomUUID();
    private final UUID patient = UUID.randomUUID();
    private final UUID document = UUID.randomUUID();

    @SuppressWarnings("unchecked")
    private DocumentStorageService service(StoragePort port) {
        ObjectProvider<StoragePort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(port);
        BaymaxProperties props = new BaymaxProperties();
        props.getStorage().setSignedUrlTtl(Duration.ofMinutes(15));
        when(ledger.save(any())).thenAnswer(inv -> inv.getArgument(0) == null
                ? Mono.empty() // re-stubbing invokes the previous answer with a null argument
                : Mono.just(((StoredObject) inv.getArgument(0)).withId(UUID.randomUUID())));
        return new DocumentStorageService(provider, ledger, props);
    }

    @Test
    void storePagePutsTheObjectThenWritesALedgerRowWithKeyAndSizeOnly() {
        when(storage.put(anyString(), any(), eq("image/jpeg"))).thenReturn(Mono.just((long) JPEG.length));

        StepVerifier.create(service(storage).storePage(family, patient, document, 1, JPEG))
                .assertNext(saved -> {
                    assertThat(saved.id()).isNotNull();
                    assertThat(saved.kind()).isEqualTo(ObjectKind.PAGE);
                    assertThat(saved.pageNo()).isEqualTo(1);
                    assertThat(saved.itemId()).isNull();
                    assertThat(saved.storageKey()).isEqualTo(family + "/" + patient + "/" + document + "/page-1.jpg");
                    assertThat(saved.sizeBytes()).isEqualTo(JPEG.length);
                    assertThat(saved.contentType()).isEqualTo("image/jpeg");
                })
                .verifyComplete();

        InOrder order = inOrder(storage, ledger);
        order.verify(storage).put(eq(family + "/" + patient + "/" + document + "/page-1.jpg"), eq(JPEG), eq("image/jpeg"));
        order.verify(ledger).save(any());
    }

    @Test
    void storeCropUsesTheItemId() {
        when(storage.put(anyString(), any(), anyString())).thenReturn(Mono.just(8L));

        StepVerifier.create(service(storage).storeCrop(family, patient, document, "hba1c", JPEG))
                .assertNext(saved -> {
                    assertThat(saved.kind()).isEqualTo(ObjectKind.CROP);
                    assertThat(saved.itemId()).isEqualTo("hba1c");
                    assertThat(saved.storageKey()).endsWith("/crop-hba1c.jpg");
                })
                .verifyComplete();
    }

    @Test
    void nonJpegIsRejectedBeforeAnythingIsStored() {
        StepVerifier.create(service(storage).storePage(family, patient, document, 1, PNG))
                .expectError(IllegalArgumentException.class)
                .verify();
        StepVerifier.create(service(storage).storePage(family, patient, document, 1, new byte[0]))
                .expectError(IllegalArgumentException.class)
                .verify();
        verify(storage, never()).put(any(), any(), any());
        verify(ledger, never()).save(any());
    }

    @Test
    void missingStorageFailsAtCallTimeWithAClearMessage() {
        StepVerifier.create(service(null).storePage(family, patient, document, 1, JPEG))
                .expectErrorMatches(e -> e instanceof IllegalStateException && e.getMessage().contains("baymax.storage.bucket"))
                .verify();
        verify(ledger, never()).save(any());
    }

    @Test
    void signedUrlUsesTheConfiguredTtl() {
        when(storage.signedGetUrl(eq("k"), any())).thenReturn(Mono.just(URI.create("https://bucket/k?sig")));

        StepVerifier.create(service(storage).signedUrl("k"))
                .expectNext(URI.create("https://bucket/k?sig"))
                .verifyComplete();

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(storage).signedGetUrl(eq("k"), ttl.capture());
        assertThat(ttl.getValue()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void deleteDocumentRemovesObjectsFirstThenLedgerRowsUnderThePrefix() {
        String prefix = family + "/" + patient + "/" + document + "/";
        when(storage.deleteByPrefix(prefix)).thenReturn(Mono.just(3L));
        when(ledger.deleteByKeyPrefix(prefix)).thenReturn(Mono.just(3L));

        StepVerifier.create(service(storage).deleteDocument(family, patient, document))
                .assertNext(report -> {
                    assertThat(report.prefix()).isEqualTo(prefix);
                    assertThat(report.objectsDeleted()).isEqualTo(3);
                    assertThat(report.ledgerRowsDeleted()).isEqualTo(3);
                })
                .verifyComplete();

        InOrder order = inOrder(storage, ledger);
        order.verify(storage).deleteByPrefix(prefix);
        order.verify(ledger).deleteByKeyPrefix(prefix);
    }

    @Test
    void deletePatientAndFamilyUseTheWiderPrefixes() {
        when(storage.deleteByPrefix(anyString())).thenReturn(Mono.just(0L));
        when(ledger.deleteByKeyPrefix(anyString())).thenReturn(Mono.just(0L));
        DocumentStorageService service = service(storage);

        StepVerifier.create(service.deletePatient(family, patient)).expectNextCount(1).verifyComplete();
        StepVerifier.create(service.deleteFamily(family)).expectNextCount(1).verifyComplete();

        verify(storage).deleteByPrefix(family + "/" + patient + "/");
        verify(storage).deleteByPrefix(family + "/");
    }
}
