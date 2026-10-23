package com.elioo.baymax.storage.application.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.storage.application.port.out.ObjectMissingException;
import com.elioo.baymax.storage.application.port.out.StoragePort;
import com.elioo.baymax.storage.application.port.out.StoredObjectLedgerPort;
import com.elioo.baymax.storage.domain.ObjectKind;
import com.elioo.baymax.storage.domain.StoredObject;
import com.elioo.baymax.testsupport.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Wave 2 plan D4b, the read-path backfill without a database: which rows a paper's read fetches, how many objects are in
 * memory at once across every read in the process, and that the answer never waits on a store that does not answer. The
 * ledger is an in-memory fake and the store a fake that counts its GETs; the SQL that records a size is exercised on
 * real Postgres in {@code StoredDimensionsTest}.
 */
class MeasuredObjectsTest {

    private static final long CAP = 25L * 1024 * 1024;
    private static final Instant THEN = Instant.parse("2026-09-01T00:00:00Z");

    private final FakeLedger ledger = new FakeLedger();
    private final FakeStore store = new FakeStore();
    private DocumentStorageService service;
    private ListAppender<ILoggingEvent> logged;
    private Logger logger;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void service() {
        ObjectProvider<StoragePort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(store);
        BaymaxProperties props = new BaymaxProperties();
        props.getUpload().setMaxFileBytes(CAP);
        service = new DocumentStorageService(provider, ledger, props);
        logger = (Logger) LoggerFactory.getLogger(DocumentStorageService.class);
        logged = new ListAppender<>();
        logged.start();
        logger.addAppender(logged);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(logged);
    }

    /**
     * The process-wide bound: two papers read at the same moment, each with three crops never measured, never hold more
     * than two objects at once between them. A per-read limit alone lets each read hold two, four in all.
     */
    @Test
    void atMostTwoObjectsAreInMemoryAtOnceAcrossEveryRead() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        for (UUID doc : List.of(a, b)) {
            for (int i = 0; i < 3; i++) {
                ledger.add(untried(doc, ObjectKind.CROP, "c" + i));
                store.objects.put(key(doc, "c" + i), jpeg(30 + i, 10));
            }
        }
        store.delay = Duration.ofMillis(150);

        List<StoredObject> read = Flux.merge(service.measuredObjectsOf(a, ObjectKind.CROP), service.measuredObjectsOf(b, ObjectKind.CROP))
                .collectList().block(Duration.ofSeconds(10));

        assertThat(store.maxInFlight.get()).as("objects fetched and not yet measured at once, across both reads").isLessThanOrEqualTo(2);
        assertThat(store.maxInFlight.get()).as("both permits used (the bound is not vacuously met by running one at a time)").isEqualTo(2);
        assertThat(read).as("every crop of both papers").hasSize(6).allMatch(o -> o.dimensions().isPresent());
    }

    /** Only crops are fetched for the paper JSON; a page (up to the upload cap, several MB) is never pulled to be measured. */
    @Test
    void aCropReadNeverFetchesAPage() {
        UUID doc = UUID.randomUUID();
        ledger.add(untried(doc, ObjectKind.PAGE, null));
        ledger.add(untried(doc, ObjectKind.CROP, "v1"));
        store.objects.put(pageKey(doc), jpeg(400, 200));
        store.objects.put(key(doc, "v1"), jpeg(60, 20));

        List<StoredObject> read = service.measuredObjectsOf(doc, ObjectKind.CROP).collectList().block(Duration.ofSeconds(10));

        assertThat(read).as("the crop rows only").extracting(StoredObject::kind).containsExactly(ObjectKind.CROP);
        assertThat(store.gets(pageKey(doc))).as("GETs of the page").isZero();
        assertThat(store.gets(key(doc, "v1"))).as("GETs of the crop").isEqualTo(1);
    }

    /** The ledger's size is checked before anything is fetched: an object over the cap is never downloaded to be refused. */
    @Test
    void aCropOverTheUploadCapIsNeverFetchedAndIsRecordedTried() {
        UUID doc = UUID.randomUUID();
        StoredObject big = new StoredObject(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), doc, ObjectKind.CROP, null, "big",
                key(doc, "big"), "image/jpeg", CAP + 1, THEN, null, null, null);
        ledger.add(big);
        store.objects.put(key(doc, "big"), jpeg(60, 20));

        List<StoredObject> read = service.measuredObjectsOf(doc, ObjectKind.CROP).collectList().block(Duration.ofSeconds(10));

        assertThat(store.gets(key(doc, "big"))).as("GETs of a crop over the cap").isZero();
        assertThat(ledger.unmeasurable).as("rows recorded as tried without a size").containsExactly(key(doc, "big"));
        assertThat(read).singleElement().satisfies(o -> assertThat(o.dimensions()).as("its size").isEmpty());
    }

    /** A missing object is a permanent answer: recorded as tried, so the next read does not fetch it again. */
    @Test
    void aMissingObjectIsFetchedOnceEver() {
        UUID doc = UUID.randomUUID();
        ledger.add(untried(doc, ObjectKind.CROP, "gone"));

        service.measuredObjectsOf(doc, ObjectKind.CROP).collectList().block(Duration.ofSeconds(10));
        List<StoredObject> again = service.measuredObjectsOf(doc, ObjectKind.CROP).collectList().block(Duration.ofSeconds(10));

        assertThat(store.gets(key(doc, "gone"))).as("GETs of a missing object over two reads").isEqualTo(1);
        assertThat(ledger.unmeasurable).containsExactly(key(doc, "gone"));
        assertThat(again).singleElement().satisfies(o -> assertThat(o.dimensions()).isEmpty());
        assertThat(lines(Level.INFO)).as("INFO lines naming the storage key").noneMatch(l -> l.contains(key(doc, "gone")));
    }

    /**
     * One fetch that never answers is given up at its own timeout ({@link DocumentStorageService#MEASURE_TIMEOUT}, before
     * the read's budget), the crop beside it is measured, and the stuck row is left untried (a later read tries it again):
     * an outage is not recorded as a permanent answer.
     */
    @Test
    void aFetchThatNeverAnswersIsLeftBehindAtItsOwnTimeout() {
        UUID doc = UUID.randomUUID();
        ledger.add(untried(doc, ObjectKind.CROP, "stuck"));
        ledger.add(untried(doc, ObjectKind.CROP, "fine"));
        store.hang.add(key(doc, "stuck"));
        store.objects.put(key(doc, "fine"), jpeg(60, 20));

        long start = System.nanoTime();
        List<StoredObject> read = service.measuredObjectsOf(doc, ObjectKind.CROP).collectList().block(Duration.ofSeconds(20));
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(DocumentStorageService.MEASURE_TIMEOUT).as("the fetch timeout is shorter than the read budget")
                .isLessThan(DocumentStorageService.READ_BUDGET.minusMillis(500));
        assertThat(took).as("time to answer with one fetch never answering")
                .isLessThan(DocumentStorageService.MEASURE_TIMEOUT.plusMillis(500));
        StoredObject stuck = read.stream().filter(o -> o.storageKey().equals(key(doc, "stuck"))).findFirst().orElseThrow();
        assertThat(stuck.dimensions()).as("the stuck crop's size").isEmpty();
        assertThat(stuck.neverMeasured()).as("the stuck crop, still untried for a later read").isTrue();
        assertThat(read.stream().filter(o -> o.storageKey().equals(key(doc, "fine"))).findFirst().orElseThrow().dimensions())
                .as("the crop beside it").isPresent();
        assertThat(ledger.unmeasurable).as("an outage recorded as a permanent answer").isEmpty();
        assertThat(ledger.sized).containsExactly(key(doc, "fine"));
    }

    /**
     * A store that answers, but slowly, is left behind at the read's budget: each fetch is inside its own timeout, but six of
     * them two at a time take longer than the budget, so the paper is answered at the budget with the sizes it has and
     * the rest left untried for a later read.
     */
    @Test
    void aSlowStoreIsLeftBehindAtTheReadBudget() {
        UUID doc = UUID.randomUUID();
        for (int i = 0; i < 6; i++) {
            ledger.add(untried(doc, ObjectKind.CROP, "s" + i));
            store.objects.put(key(doc, "s" + i), jpeg(40 + i, 20));
        }
        store.delay = Duration.ofMillis(1400);

        long start = System.nanoTime();
        List<StoredObject> read = service.measuredObjectsOf(doc, ObjectKind.CROP).collectList().block(Duration.ofSeconds(20));
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(took).as("time to answer with a slow store (three rounds of 1.4 s without a budget)")
                .isLessThan(DocumentStorageService.READ_BUDGET.plusMillis(600));
        assertThat(read).as("every crop row, answered").hasSize(6);
        assertThat(read).as("crops left untried for a later read").anyMatch(StoredObject::neverMeasured);
        assertThat(read).as("crops measured inside the budget").anyMatch(o -> o.dimensions().isPresent());
    }

    /** A failed ledger write is an operator's problem (WARN), never the family's: the size read is still sent. */
    @Test
    void aFailedLedgerWriteStillAnswersAndWarns() {
        UUID doc = UUID.randomUUID();
        ledger.add(untried(doc, ObjectKind.CROP, "v1"));
        store.objects.put(key(doc, "v1"), jpeg(60, 20));
        ledger.failWrites = true;

        List<StoredObject> read = service.measuredObjectsOf(doc, ObjectKind.CROP).collectList().block(Duration.ofSeconds(10));

        assertThat(read).singleElement().satisfies(o -> assertThat(o.dimensions()).as("the size read").isPresent()
                .hasValueSatisfying(d -> assertThat(new int[]{d.width(), d.height()}).containsExactly(60, 20)));
        assertThat(lines(Level.WARN)).as("WARN lines").anyMatch(l -> l.contains("could not record") && l.contains(doc.toString()));
        assertThat(lines(Level.WARN)).as("WARN lines naming the storage key").noneMatch(l -> l.contains(key(doc, "v1")));
    }

    // ---- fakes --------------------------------------------------------------------------------------------------

    private List<String> lines(Level level) {
        return LogCapture.events(logged).stream().filter(e -> e.getLevel() == level).map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static StoredObject untried(UUID doc, ObjectKind kind, String item) {
        return new StoredObject(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), doc, kind, kind == ObjectKind.PAGE ? 1 : null,
                item, kind == ObjectKind.PAGE ? pageKey(doc) : key(doc, item), "image/jpeg", 100, THEN, null, null, null);
    }

    private static String key(UUID doc, String item) {
        return "f/p/" + doc + "/crop-" + item + ".jpg";
    }

    private static String pageKey(UUID doc) {
        return "f/p/" + doc + "/page-1.jpg";
    }

    private static byte[] jpeg(int w, int h) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR), "jpg", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The ledger's rows by key; records what the service writes, as the SQL would (only a row never tried takes it). */
    static final class FakeLedger implements StoredObjectLedgerPort {
        final Map<String, StoredObject> rows = new ConcurrentHashMap<>();
        final List<String> sized = new CopyOnWriteArrayList<>();
        final List<String> unmeasurable = new CopyOnWriteArrayList<>();
        volatile boolean failWrites;

        void add(StoredObject o) {
            rows.put(o.storageKey(), o);
        }

        @Override
        public Mono<StoredObject> save(StoredObject object) {
            return Mono.just(object);
        }

        @Override
        public Flux<StoredObject> findByDocument(UUID documentId) {
            return Flux.defer(() -> Flux.fromIterable(new ArrayList<>(rows.values().stream()
                    .filter(o -> o.documentId().equals(documentId)).sorted(java.util.Comparator.comparing(StoredObject::storageKey)).toList())));
        }

        @Override
        public Mono<Long> deleteByKeyPrefix(String prefix) {
            return Mono.just(0L);
        }

        @Override
        public Mono<Long> bytesStored(UUID documentId) {
            return Mono.just(0L);
        }

        @Override
        public Mono<Long> recordDimensions(String storageKey, int width, int height) {
            if (failWrites) {
                return Mono.error(new IllegalStateException("the database is read-only"));
            }
            return Mono.fromSupplier(() -> {
                StoredObject o = rows.get(storageKey);
                if (o == null || !o.neverMeasured()) {
                    return 0L;
                }
                rows.put(storageKey, o.withDimensions(new com.elioo.baymax.storage.domain.ImageDimensions(width, height), Instant.now()));
                sized.add(storageKey);
                return 1L;
            });
        }

        @Override
        public Mono<Long> recordUnmeasurable(String storageKey) {
            if (failWrites) {
                return Mono.error(new IllegalStateException("the database is read-only"));
            }
            return Mono.fromSupplier(() -> {
                StoredObject o = rows.get(storageKey);
                if (o == null || !o.neverMeasured()) {
                    return 0L;
                }
                rows.put(storageKey, o.withoutDimensions(Instant.now()));
                unmeasurable.add(storageKey);
                return 1L;
            });
        }
    }

    /** Objects by key; a key with none is missing; counts every GET and the most GETs not yet answered at once. */
    static final class FakeStore implements StoragePort {
        final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        final Set<String> hang = ConcurrentHashMap.newKeySet();
        final Map<String, AtomicInteger> gets = new ConcurrentHashMap<>();
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        volatile Duration delay = Duration.ZERO;

        int gets(String key) {
            AtomicInteger n = gets.get(key);
            return n == null ? 0 : n.get();
        }

        @Override
        public Mono<byte[]> get(String key) {
            return Mono.defer(() -> {
                gets.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
                if (hang.contains(key)) {
                    return Mono.never();
                }
                maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                byte[] bytes = objects.get(key);
                Mono<byte[]> answer = bytes == null ? Mono.error(new ObjectMissingException(null)) : Mono.just(bytes);
                return Mono.delay(delay).then(answer).doFinally(s -> inFlight.decrementAndGet());
            });
        }

        @Override
        public Mono<Long> put(String key, byte[] bytes, String contentType) {
            objects.put(key, bytes);
            return Mono.just((long) bytes.length);
        }

        @Override
        public Flux<String> listKeys(String prefix) {
            return Flux.empty();
        }

        @Override
        public Mono<URI> signedGetUrl(String key, Duration ttl) {
            return Mono.empty();
        }

        @Override
        public Mono<Long> deleteByPrefix(String prefix) {
            return Mono.just(0L);
        }
    }
}
