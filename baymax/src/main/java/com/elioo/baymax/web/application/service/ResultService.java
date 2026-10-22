package com.elioo.baymax.web.application.service;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.extraction.application.port.in.DocumentIntakeUseCase;
import com.elioo.baymax.extraction.application.port.out.DocumentReadPort;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.FiledDay;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.outbound.application.port.in.DocumentUrgencyUseCase;
import com.elioo.baymax.outbound.application.port.out.OutboundMessagePort;
import com.elioo.baymax.outbound.domain.MarkerThreshold;
import com.elioo.baymax.outbound.domain.OutboundMessage;
import com.elioo.baymax.outbound.domain.StoredUrgency;
import com.elioo.baymax.web.application.port.in.ResultUseCase;
import com.elioo.baymax.web.application.port.out.AuditPort;
import com.elioo.baymax.web.domain.AuditEvent;
import com.elioo.baymax.web.domain.EarlierHistory;
import com.elioo.baymax.web.domain.EarlierReading;
import com.elioo.baymax.web.domain.GateState;
import com.elioo.baymax.web.domain.ReadingView;
import com.elioo.baymax.web.domain.ResultView;
import com.elioo.baymax.web.domain.ValueSheetView;
import com.elioo.baymax.web.domain.ViewerLocale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The document page's read side (A9: the status and the reading view; A12: the result). Every read starts from the
 * session's family and reaches the document only through {@code findVisible}, which decides access in SQL.
 */
@Service
@RequiredArgsConstructor
public class ResultService implements ResultUseCase {

    private final HealthRecordPort records;
    private final DocumentRecordPort documents;
    private final DocumentIntakeUseCase intake;
    private final AuditPort audit;
    private final Clock clock;
    private final OutboundMessagePort messages;
    private final DocumentUrgencyUseCase urgencies;
    private final BaymaxProperties properties;
    private final DocumentReadPort reads;

    @Override
    public Mono<Document.Status> status(UUID familyId, UUID documentId) {
        return visibleDocument(familyId, documentId).map(Document::status);
    }

    @Override
    public Mono<ReadingView> reading(UUID familyId, UUID documentId) {
        return visibleDocument(familyId, documentId).flatMap(d -> readingOf(familyId, d,
                audit.record(AuditEvent.family(AuditEvent.Kind.DOCUMENT_VIEW, familyId, d.patientId(), documentId, clock.instant()))));
    }

    /** The reading view of a visible paper; {@code before} runs ahead of the view's read (the open's audit row, or nothing). */
    private Mono<ReadingView> readingOf(UUID familyId, Document d, Mono<Void> before) {
        return Mono.zip(
                        records.visiblePatient(familyId, d.patientId())
                                .switchIfEmpty(Mono.error(BaymaxException.notFound("patient_not_found", "no patient with id " + d.patientId()))),
                        viewer(familyId),
                        before.then(intake.view(d.id())))
                .map(t -> new ReadingView(t.getT3(), t.getT1().patient(), t.getT1().owner(), t.getT2(), d.createdAt(), clock.instant()));
    }

    /**
     * Plan W5-api: the thread's card, read exactly as {@link #reading} and {@link #result} read the paper, with no audit row
     * at all. A paper that finished between the visibility read and the reading view is answered finished, as
     * {@code PaperApiHandler} does, never as a reading that would wait for a change that already happened.
     */
    @Override
    public Mono<Object> card(UUID familyId, UUID documentId) {
        return visibleDocument(familyId, documentId).flatMap(d -> !inFlight(d.status())
                ? resultOf(familyId, d).cast(Object.class)
                : readingOf(familyId, d, Mono.empty()).flatMap(r -> inFlight(Document.Status.valueOf(r.document().status()))
                        ? Mono.<Object>just(r)
                        : visibleDocument(familyId, documentId).flatMap(now -> resultOf(familyId, now)).cast(Object.class)));
    }

    private static boolean inFlight(Document.Status status) {
        return status == Document.Status.RECEIVED || status == Document.Status.PROCESSING;
    }

    @Override
    public Mono<ResultView> result(UUID familyId, UUID documentId) {
        // the stored level is read (or, for an unassessed DONE document, assessed once) only after visibility is decided,
        // and the values only after the level: the level's read may assess the paper there and then (the page opened at
        // DONE while the chain is still composing), and its verdicts commit with the level in one transaction. A view read
        // alongside it could carry no verdict under a value_critical level, and the NOW block then drew the whole page
        // as its proof above the page drawn below (WebOnlyJourneyAcceptanceTest's held journey). The view is recorded
        // first, before an assessment that may fail, so a failed read is still an audited one.
        return visibleDocument(familyId, documentId).flatMap(d -> audit.record(
                        AuditEvent.family(AuditEvent.Kind.DOCUMENT_VIEW, familyId, d.patientId(), documentId, clock.instant()))
                .then(Mono.defer(() -> resultOf(familyId, d))));
    }

    /** The result of a visible paper, with no audit row: {@link #result} records the open first, {@link #card} records none. */
    private Mono<ResultView> resultOf(UUID familyId, Document d) {
        UUID documentId = d.id();
        return Mono.defer(() -> stored(d)).flatMap(level -> Mono.zip(
                        records.visiblePatient(familyId, d.patientId())
                                .switchIfEmpty(Mono.error(BaymaxException.notFound("patient_not_found", "no patient with id " + d.patientId()))),
                        viewer(familyId),
                        intake.read(documentId),
                        gate(d), retakeOrdinal(d), replacedRetake(d), retakeReplaced(d))
                // D2: the filed day comes from the row the view was built from, never from the earlier visibility read:
                // a paper that finished in between would otherwise be dated as it stood before it was read
                .map(t -> new ResultView(t.getT3().view(), t.getT1().patient(), t.getT1().owner(), t.getT2(),
                        level.map(StoredUrgency::level).orElse(null), level.map(StoredUrgency::reasons).orElse(List.of()),
                        t.getT4().state(), t.getT4().wording(), t.getT5(), properties.getOutbound().getReviewTypicalHours(),
                        d.createdAt(), clock.instant(), filedDayOf(t.getT3().document()), t.getT6().orElse(null),
                        t.getT7() && "NEEDS_RETAKE".equals(t.getT3().view().status()))));
    }

    /** The row's generated filed day; a row read back always has one, so its absence is a mapping defect, said loudly. */
    private static FiledDay filedDayOf(Document read) {
        if (read.filed() == null) {
            throw new IllegalStateException("D2: document " + read.id() + " was read without its filed day (timeline_date)");
        }
        return read.filed();
    }

    /**
     * D1: for a FAILED paper, the retake it was itself sent to replace (still waiting for one), so its page's resend names
     * that retake and the photo that finally reads clears its card. Every other status: none, without a query.
     */
    private Mono<Optional<UUID>> replacedRetake(Document d) {
        return d.status() == Document.Status.FAILED
                ? documents.replacedRetakeOf(d.id()).map(Optional::of).defaultIfEmpty(Optional.empty())
                : Mono.just(Optional.empty());
    }

    /**
     * Review of #135, finding 10: whether a NEEDS_RETAKE paper was already sent again, so the thread's card (and the paper
     * page's body) stops asking for a photo the family sent. Every other status: false, without a query.
     */
    private Mono<Boolean> retakeReplaced(Document d) {
        return d.status() != Document.Status.NEEDS_RETAKE ? Mono.just(false) : reads.retakeReplaced(d.id()).defaultIfEmpty(false);
    }

    /** How many earlier readings the value sheet lists (s13 draws two; ten is a page, not a trend). */
    public static final int EARLIER_READINGS = 10;

    /**
     * Plan W6-fix: the sheet's one read (see {@link ResultUseCase#valueSheet}). The status is the visibility read's own, so
     * a paper that is not DONE is refused before its values, its history or its result are read: nothing is recorded for
     * it. Today the intake's view of such a paper carries no rows either (D2, {@code DocumentIntakeService.read}), so the
     * observation would not be found; this gate does not lean on that, it states the rule where both routes meet it.
     * The result's own status is checked again, for a paper that changed between the two reads.
     */
    @Override
    public Mono<ValueSheetView> valueSheet(UUID familyId, UUID documentId, UUID observationId) {
        return visibleDocument(familyId, documentId)
                .flatMap(d -> d.status() != Document.Status.DONE ? Mono.<EarlierHistory>error(noSheet())
                        : historyOf(d, documentId, observationId))
                .flatMap(history -> result(familyId, documentId)
                        .flatMap(r -> !r.done() ? Mono.<ValueSheetView>error(noSheet()) : Mono.just(new ValueSheetView(r, history))));
    }

    private static BaymaxException noSheet() {
        return BaymaxException.notFound("observation_not_found", "no sheet for a paper that is not read");
    }

    /**
     * The value's history, NOT on the port (a history read without {@link #valueSheet}'s status gate is how the HTML sheet
     * came to differ from the JSON): the document through {@link #visibleDocument} (404 for another family), then the
     * observation among THAT document's values (404 for an id from another paper), then the patient's history of the
     * marker, kept only where the unit folds to this one's ({@link MarkerThreshold#sameUnit}: MICRO SIGN, GREEK MU and "u"
     * are one; mg/dL and µmol/L are two), plus whether any earlier reading is in a unit that does not fold to this one.
     * One scan of the history ({@code DocumentReadPort.readingsOf}) answers both, so the two halves cannot disagree about
     * which rows they saw. A value with no canonical name has no history: the marker is not known, so nothing is "the same".
     * Records nothing. Public for {@code EarlierReadingsTest}, which holds the scan on Postgres.
     */
    public Mono<EarlierHistory> earlierHistory(UUID familyId, UUID documentId, UUID observationId) {
        return visibleDocument(familyId, documentId).flatMap(d -> historyOf(d, documentId, observationId));
    }

    private Mono<EarlierHistory> historyOf(Document document, UUID documentId, UUID observationId) {
        return intake.view(documentId).map(v -> observationOf(v.values(), observationId)).map(o -> Map.entry(document, o))
                .flatMap(found -> {
                    Object canonical = found.getValue().get("canonical_name");
                    if (canonical == null) {
                        return Mono.just(EarlierHistory.NONE);
                    }
                    String unit = found.getValue().get("unit") == null ? null : String.valueOf(found.getValue().get("unit"));
                    return reads.readingsOf(found.getKey().patientId(), String.valueOf(canonical), documentId).collectList()
                            .map(rows -> new EarlierHistory(
                                    rows.stream().filter(row -> MarkerThreshold.sameUnit(unit, (String) row.get("unit")))
                                            .limit(EARLIER_READINGS).map(ResultService::earlierReading).toList(),
                                    rows.stream().anyMatch(row -> !MarkerThreshold.sameUnit(unit, (String) row.get("unit")))));
                });
    }

    private static Map<String, Object> observationOf(List<Map<String, Object>> values, UUID observationId) {
        return (values == null ? List.<Map<String, Object>>of() : values).stream()
                .filter(v -> observationId.toString().equals(String.valueOf(v.get("id")))).findFirst()
                .orElseThrow(() -> BaymaxException.notFound("observation_not_found", "no such value on document"));
    }

    private static EarlierReading earlierReading(Map<String, Object> row) {
        return new EarlierReading((UUID) row.get("document_id"), (UUID) row.get("id"), (String) row.get("value"),
                (String) row.get("unit"), (String) row.get("crop_key"), (LocalDate) row.get("date"),
                Boolean.TRUE.equals(row.get("date_is_fallback")));
    }

    /**
     * F1: the stored level, read through the sole writer (DocumentUrgencyUseCase.currentOrAssess), which assesses an
     * unassessed DONE document once; never computed here (OneUrgencyWriterTest). Nothing before DONE.
     */
    private Mono<Optional<StoredUrgency>> stored(Document d) {
        return d.status() != Document.Status.DONE ? Mono.just(Optional.empty())
                : urgencies.currentOrAssess(d.id()).map(Optional::of).defaultIfEmpty(Optional.empty());
    }

    /** B5: this patient's retakes in a row up to this one (RetakeCard's escalation); 0 for every other status. */
    private Mono<Integer> retakeOrdinal(Document d) {
        return d.status() != Document.Status.NEEDS_RETAKE ? Mono.just(0) : reads.retakeOrdinal(d.id()).defaultIfEmpty(0);
    }

    /** What the newest explanation's review state lets the page say. Wording only when deliverable (F3). */
    private record Gate(GateState state, String wording) {
        static final Gate NONE = new Gate(GateState.NONE, null);

        static Gate of(OutboundMessage m) {
            return switch (m.gateStatus()) {
                // a deliverable status with no body is a safety failure that closed: nothing to say, nothing promised
                case RELEASED, APPROVED -> m.body() == null ? new Gate(GateState.FAILED_SAFETY, null) : new Gate(GateState.RELEASED, m.body());
                case PENDING -> new Gate(GateState.PENDING, null);
                case REJECTED -> new Gate(GateState.REJECTED, null);
                case FAILED_SAFETY -> new Gate(GateState.FAILED_SAFETY, null);
            };
        }
    }

    /** F3 reads the newest EXPLANATION whatever its state (latestOfKind), not latestDeliverable, which hides PENDING. */
    private Mono<Gate> gate(Document d) {
        return d.status() != Document.Status.DONE ? Mono.just(Gate.NONE)
                : messages.latestOfKind(d.id(), OutboundMessage.Kind.EXPLANATION).map(Gate::of).defaultIfEmpty(Gate.NONE);
    }

    Mono<ViewerLocale> viewer(UUID familyId) {
        return records.findFamily(familyId).map(f -> ViewerLocale.of(f.whatsappNumber()))
                .switchIfEmpty(Mono.error(BaymaxException.unauthorized("session_family_gone", "the family no longer exists")));
    }

    Mono<Document> visibleDocument(UUID familyId, UUID documentId) {
        return documents.findVisible(familyId, documentId)
                .switchIfEmpty(Mono.error(BaymaxException.notFound("document_not_found", "no document with id " + documentId)));
    }
}
