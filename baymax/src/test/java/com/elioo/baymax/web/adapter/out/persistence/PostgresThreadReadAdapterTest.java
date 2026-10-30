package com.elioo.baymax.web.adapter.out.persistence;

import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.healthrecord.domain.ShareMember;
import com.elioo.baymax.nudge.adapter.out.persistence.PostgresNudgeAdapterTest;
import com.elioo.baymax.outbound.application.port.out.OutboundMessagePort;
import com.elioo.baymax.outbound.domain.DeliveryOutcome;
import com.elioo.baymax.outbound.domain.OutboundMessage;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.web.application.port.out.ThreadReadPort;
import com.elioo.baymax.web.domain.ThreadItem;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan W5-api: the chat thread's read model on real Postgres. A message is listed only when the provider accepted it
 * ({@code delivery_status = 'sent'}, which V14 ties to a wamid), to this viewer's own account (S4), about a paper that
 * still exists or about none (S10); a logged outcome (sent_at with no delivery status, S11) is not a delivery. Papers
 * and messages interleave newest first, and the cursor pages without a repeat or a gap. Every row is matched by id,
 * never by a substring, and every kind the database allows is read from the database, never from a list written here.
 */
@Testcontainers
class PostgresThreadReadAdapterTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final Instant T0 = Instant.parse("2026-10-01T04:00:00Z");
    private static final AtomicInteger NUMBERS = new AtomicInteger(300);

    private ReactiveWebApplicationContextRunner runner() {
        return PostgresNudgeAdapterTest.runnerFor(POSTGRES).withPropertyValues("baymax.urgency.sweep-cron=-");
    }

    record Holder(FamilyAccount family, PatientProfile ma) {
    }

    private static String nextNumber() {
        return "+8801700000" + NUMBERS.incrementAndGet();
    }

    private static Holder owner(ApplicationContext context) {
        HealthRecordPort records = context.getBean(HealthRecordPort.class);
        FamilyAccount f = records.createFamily(new FamilyAccount(null, nextNumber(), "Rahim", FamilyAccount.Plan.FAMILY, T0, T0)).block();
        PatientProfile ma = records.createPatient(new PatientProfile(null, f.id(), "Ma", 64, PatientProfile.Sex.FEMALE, List.of(), T0, T0)).block();
        return new Holder(f, ma);
    }

    /** A DONE paper uploaded at exactly {@code at}. */
    private static UUID paper(ApplicationContext context, PatientProfile p, Instant at) throws Exception {
        UUID id = context.getBean(DocumentRecordPort.class).create(Document.received(p.id(), p.familyId(), 1, at)).block().id();
        sql("UPDATE baymax.document SET status = 'DONE', document_type = 'lab_report', created_at = '" + at + "' WHERE id = '" + id + "'");
        return id;
    }

    /** A message composed for {@code family} about {@code p}, stored through the port, not yet handed to any channel. */
    private static OutboundMessage composed(ApplicationContext context, UUID family, PatientProfile p, UUID document, String kind,
                                            OutboundMessage.GateStatus gate) {
        return composed(context, family, p, document, kind, gate, T0);
    }

    private static OutboundMessage composed(ApplicationContext context, UUID family, PatientProfile p, UUID document, String kind,
                                            OutboundMessage.GateStatus gate, Instant createdAt) {
        return context.getBean(OutboundMessagePort.class).save(new OutboundMessage(null, family, p.id(), document,
                OutboundMessage.Kind.fromDbValue(kind), Urgency.ROUTINE, List.of(), "Body of a " + kind + " message.", gate,
                null, null, null, null, createdAt)).block();
    }

    /**
     * The provider accepted it at {@code at}: delivery_status 'sent', the wamid, sent_at (V13, V14). Composed a minute
     * before, so the newest of a kind by {@code created_at} (latestOfKind's order) is the newest delivered.
     */
    private static UUID delivered(ApplicationContext context, UUID family, PatientProfile p, UUID document, String kind, Instant at) {
        OutboundMessage m = composed(context, family, p, document, kind, OutboundMessage.GateStatus.RELEASED, at.minusSeconds(60));
        context.getBean(OutboundMessagePort.class).recordDelivery(m.id(), DeliveryOutcome.sent("wamid." + m.id()), at).block();
        return m.id();
    }

    private static List<ThreadItem> read(ApplicationContext context, UUID viewer, PatientProfile p) {
        return context.getBean(ThreadReadPort.class).page(viewer, p.id(), ThreadItem.Cursor.FIRST, 500).collectList().block();
    }

    private static List<UUID> ids(List<ThreadItem> items) {
        return items.stream().map(ThreadItem::id).toList();
    }

    @Test
    void onlyDeliveredMessagesAreListed() {
        runner().run(context -> {
            Holder a = owner(context);
            UUID doc = paper(context, a.ma(), T0);
            OutboundMessagePort port = context.getBean(OutboundMessagePort.class);
            UUID pending = composed(context, a.family().id(), a.ma(), doc, "detail", OutboundMessage.GateStatus.PENDING).id();
            UUID rejected = composed(context, a.family().id(), a.ma(), doc, "detail", OutboundMessage.GateStatus.REJECTED).id();
            UUID failedSafety = context.getBean(OutboundMessagePort.class).save(new OutboundMessage(null, a.family().id(), a.ma().id(), doc,
                    OutboundMessage.Kind.EXPLANATION, Urgency.ROUTINE, List.of(), null, OutboundMessage.GateStatus.FAILED_SAFETY,
                    null, null, null, null, T0)).block().id();
            UUID failedDelivery = composed(context, a.family().id(), a.ma(), doc, "detail", OutboundMessage.GateStatus.RELEASED).id();
            port.recordDelivery(failedDelivery, DeliveryOutcome.failed("provider 131047"), T0.plusSeconds(60)).block();
            // the log adapter's outcome: sent_at stamped, no delivery status, nothing reached the family (S11)
            UUID logged = composed(context, a.family().id(), a.ma(), doc, "detail", OutboundMessage.GateStatus.RELEASED).id();
            port.markSent(logged, T0.plusSeconds(90)).block();
            UUID sent = delivered(context, a.family().id(), a.ma(), doc, "detail", T0.plusSeconds(120));
            assertThat(scalar("SELECT count(*) FROM baymax.outbound_message WHERE id = '" + logged + "' AND sent_at IS NOT NULL"
                    + " AND delivery_status IS NULL")).as("the logged row is stored as the log adapter writes it").isEqualTo("1");

            List<UUID> listed = ids(read(context, a.family().id(), a.ma()));
            assertThat(listed).as("the delivered message").contains(sent);
            assertThat(listed).as("pending").doesNotContain(pending);
            assertThat(listed).as("rejected").doesNotContain(rejected);
            assertThat(listed).as("failed_safety").doesNotContain(failedSafety);
            assertThat(listed).as("a failed delivery").doesNotContain(failedDelivery);
            assertThat(listed).as("a logged outcome " + logged).doesNotContain(logged);
            assertThat(listed).as("the paper and the one delivered message, nothing else").containsExactlyInAnyOrder(doc, sent);
        });
    }

    /** The kinds {@code outbound_message_kind_check} allows, parsed from the constraint as Postgres reports it. */
    static Set<String> allowedKinds() throws Exception {
        String def = scalar("SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace"
                + " WHERE n.nspname = 'baymax' AND c.conname = 'outbound_message_kind_check'");
        Set<String> kinds = new LinkedHashSet<>();
        Matcher m = Pattern.compile("'([a-z_]+)'").matcher(def);
        while (m.find()) {
            kinds.add(m.group(1));
        }
        assertThat(kinds).as("the kinds parsed from " + def).isNotEmpty();
        return kinds;
    }

    @Test
    void everyOutboundKindIsListedWithItsKind() {
        runner().run(context -> {
            Holder a = owner(context);
            UUID doc = paper(context, a.ma(), T0);
            Set<String> kinds = allowedKinds();
            assertThat(kinds).as("V23's kinds at least").contains("explanation", "failure");
            List<UUID> inserted = new ArrayList<>();
            List<String> insertedKinds = new ArrayList<>();
            int i = 0;
            for (String kind : kinds) {
                inserted.add(delivered(context, a.family().id(), a.ma(), doc, kind, T0.plusSeconds(60L * ++i)));
                insertedKinds.add(kind);
            }
            // the paper's newest explanation is its card's wording and is not listed (review of #135, finding 6): one newer
            // than all of the above, so the explanation inserted in the loop is an older one, and listed
            UUID cards = delivered(context, a.family().id(), a.ma(), doc, "explanation", T0.plusSeconds(60L * ++i));
            List<ThreadItem> items = read(context, a.family().id(), a.ma());
            assertThat(ids(items)).as("the explanation the card carries").doesNotContain(cards);
            for (int k = 0; k < inserted.size(); k++) {
                UUID id = inserted.get(k);
                ThreadItem.Message listed = items.stream().filter(it -> it.id().equals(id)).map(ThreadItem.Message.class::cast)
                        .findFirst().orElseThrow(() -> new AssertionError("no thread item for the delivered " + kindOf(insertedKinds, inserted, id)
                                + " message " + id));
                assertThat(listed.kind().dbValue()).as("message_kind of the " + id + " row").isEqualTo(insertedKinds.get(k));
            }
            assertThat(items.stream().filter(it -> it instanceof ThreadItem.Message).count()).as("one listed per kind").isEqualTo(kinds.size());
        });
    }

    private static String kindOf(List<String> kinds, List<UUID> ids, UUID id) {
        return "'" + kinds.get(ids.indexOf(id)) + "'";
    }

    /**
     * Review of #135, finding 6, in both directions: the explanation a DONE paper's card carries as its wording (the row
     * {@code latestOfKind} picks) is not listed a second time; an older delivered explanation of the same paper is. When
     * the newest explanation is still with the reviewer (the card says PENDING and carries no wording), the older
     * delivered one is the only place its text appears, so it is listed. A paper that is not DONE carries no wording:
     * its explanation is listed.
     */
    @Test
    void onlyTheExplanationTheCardCarriesIsLeftOut() {
        runner().run(context -> {
            Holder a = owner(context);
            UUID doc = paper(context, a.ma(), T0);
            UUID older = delivered(context, a.family().id(), a.ma(), doc, "explanation", T0.plusSeconds(120));
            UUID carried = delivered(context, a.family().id(), a.ma(), doc, "explanation", T0.plusSeconds(240));
            List<UUID> listed = ids(read(context, a.family().id(), a.ma()));
            assertThat(listed).as("the explanation " + carried + " the card carries").doesNotContain(carried);
            assertThat(listed).as("the older explanation " + older).contains(older);
            assertThat(context.getBean(OutboundMessagePort.class).latestOfKind(doc, OutboundMessage.Kind.EXPLANATION).block().id())
                    .as("the one left out is the one the card reads").isEqualTo(carried);

            // the newest explanation still with the reviewer: no wording on the card, so every delivered one is listed
            UUID reviewing = composed(context, a.family().id(), a.ma(), doc, "explanation", OutboundMessage.GateStatus.PENDING,
                    T0.plusSeconds(600)).id();
            assertThat(ids(read(context, a.family().id(), a.ma()))).as("with a newer explanation " + reviewing + " pending")
                    .contains(older, carried).doesNotContain(reviewing);

            // a paper that is not DONE carries no wording: its only explanation is listed
            UUID again = paper(context, a.ma(), T0.plusSeconds(900));
            UUID reprocessed = delivered(context, a.family().id(), a.ma(), again, "explanation", T0.plusSeconds(960));
            sql("UPDATE baymax.document SET status = 'PROCESSING' WHERE id = '" + again + "'");
            assertThat(ids(read(context, a.family().id(), a.ma()))).as("the explanation of a paper being read again")
                    .contains(reprocessed);
        });
    }

    @Test
    void aDeletedPapersMessagesAreNotListed() {
        runner().run(context -> {
            Holder a = owner(context);
            UUID doc = paper(context, a.ma(), T0);
            UUID detail = delivered(context, a.family().id(), a.ma(), doc, "detail", T0.plusSeconds(60));
            assertThat(ids(read(context, a.family().id(), a.ma()))).as("before the delete").contains(detail);

            context.getBean(DocumentRecordPort.class).deleteDocument(doc).block();
            assertThat(scalar("SELECT count(*) FROM baymax.outbound_message WHERE id = '" + detail + "'"))
                    .as("the delivered row is kept (P1 is the PO's)").isEqualTo("1");
            assertThat(ids(read(context, a.family().id(), a.ma()))).as("the message " + detail + " after its paper was deleted")
                    .doesNotContain(detail).doesNotContain(doc);
        });
    }

    @Test
    void aNudgeWithNoDocumentIsStillListed() {
        runner().run(context -> {
            Holder a = owner(context);
            UUID nudge = delivered(context, a.family().id(), a.ma(), null, "nudge", T0.plusSeconds(60));
            List<ThreadItem> items = read(context, a.family().id(), a.ma());
            assertThat(ids(items)).as("a nudge about no paper").containsExactly(nudge);
            assertThat(((ThreadItem.Message) items.get(0)).documentId()).isNull();
        });
    }

    @Test
    void anotherAccountsMessagesAreNotListed() {
        runner().run(context -> {
            Holder a = owner(context);
            UUID doc = paper(context, a.ma(), T0);
            UUID ownersMessage = delivered(context, a.family().id(), a.ma(), doc, "detail", T0.plusSeconds(60));
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            String memberNumber = nextNumber();
            FamilyAccount member = records.createFamily(new FamilyAccount(null, memberNumber, "Sister", FamilyAccount.Plan.FAMILY, T0, T0)).block();
            records.addShareMember(new ShareMember(null, a.ma().id(), memberNumber, T0, null)).block();

            List<UUID> theirs = ids(read(context, member.id(), a.ma()));
            assertThat(theirs).as("the share member's read of the owner's patient").containsExactly(doc);
            assertThat(theirs).doesNotContain(ownersMessage);
            assertThat(ids(read(context, a.family().id(), a.ma()))).as("the owner's own read").containsExactlyInAnyOrder(doc, ownersMessage);
        });
    }

    @Test
    void papersAndMessagesInterleaveByTime() {
        runner().run(context -> {
            Holder a = owner(context);
            List<UUID> expectedNewestFirst = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                UUID doc = paper(context, a.ma(), T0.plus(Duration.ofHours(2L * i)));
                UUID message = delivered(context, a.family().id(), a.ma(), doc, "detail", T0.plus(Duration.ofHours(2L * i + 1)));
                expectedNewestFirst.add(0, doc);
                expectedNewestFirst.add(0, message);
            }
            List<ThreadItem> items = read(context, a.family().id(), a.ma());
            assertThat(ids(items)).as("descending at, by id").containsExactlyElementsOf(expectedNewestFirst);
            for (int i = 1; i < items.size(); i++) {
                assertThat(items.get(i).at()).as("item " + i + " is not newer than the one before").isBefore(items.get(i - 1).at());
            }
        });
    }

    @Test
    void theCursorPagesWithoutRepeatsOrGaps() {
        runner().run(context -> {
            Holder a = owner(context);
            Set<UUID> all = new LinkedHashSet<>();
            // 45 items: 15 papers, each with two delivered messages, several sharing one instant
            for (int i = 0; i < 15; i++) {
                Instant at = T0.plus(Duration.ofHours(i));
                UUID doc = paper(context, a.ma(), at);
                all.add(doc);
                all.add(delivered(context, a.family().id(), a.ma(), doc, "retake", at));
                all.add(delivered(context, a.family().id(), a.ma(), doc, "detail", at.plusSeconds(1)));
            }
            ThreadReadPort port = context.getBean(ThreadReadPort.class);
            List<Integer> sizes = new ArrayList<>();
            List<UUID> seen = new ArrayList<>();
            ThreadItem.Cursor after = ThreadItem.Cursor.FIRST;
            for (int guard = 0; guard < 10; guard++) {
                List<ThreadItem> page = port.page(a.family().id(), a.ma().id(), after, 20).collectList().block();
                if (page.isEmpty()) {
                    break;
                }
                sizes.add(page.size());
                seen.addAll(ids(page));
                ThreadItem last = page.get(page.size() - 1);
                after = ThreadItem.Cursor.after(last);
                after = ThreadItem.Cursor.parse(after.encode());
            }
            assertThat(all).hasSize(45);
            assertThat(sizes).as("page sizes").containsExactly(20, 20, 5);
            assertThat(seen).as("every id once").hasSize(45).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(all);
        });
    }

    /** An answered চ্যাট turn (V29) asked by {@code viewer} at {@code at}, citing {@code documents}. */
    private static UUID turn(UUID viewer, PatientProfile p, Instant at, List<UUID> documents) throws Exception {
        UUID id = UUID.randomUUID();
        String ids = documents.stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(",", "{", "}"));
        sql("INSERT INTO baymax.chat_turn (id, family_id, patient_id, lang, question, answer, kind, counted, handler, model_calls,"
                + " document_ids, created_at, answered_at) VALUES ('" + id + "','" + viewer + "','" + p.id() + "','bn','প্রশ্ন','উত্তর',"
                + "'answer',true,'model',1,'" + ids + "','" + at + "','" + at.plusSeconds(5) + "')");
        return id;
    }

    @Test
    void withoutAsksNoTurnIsListed() {
        runner().run(context -> {
            Holder a = owner(context);
            UUID doc = paper(context, a.ma(), T0);
            UUID asked = turn(a.family().id(), a.ma(), T0.plusSeconds(60), List.of(doc));
            assertThat(ids(read(context, a.family().id(), a.ma()))).containsExactly(doc);
            List<ThreadItem> with = context.getBean(ThreadReadPort.class)
                    .page(a.family().id(), a.ma().id(), ThreadItem.Cursor.FIRST, 500, true).collectList().block();
            assertThat(ids(with)).containsExactly(asked, doc);
            assertThat(with.get(0)).isInstanceOfSatisfying(ThreadItem.Ask.class, ask -> {
                assertThat(ask.question()).isEqualTo("প্রশ্ন");
                assertThat(ask.answer()).isEqualTo("উত্তর");
                assertThat(ask.order()).isEqualTo("ask");
            });
        });
    }

    @Test
    void anAskIsListedOnlyForItsOwnAccountAndWhileItsPapersExist() {
        runner().run(context -> {
            Holder a = owner(context);
            Holder b = owner(context);
            UUID doc = paper(context, a.ma(), T0);
            UUID mine = turn(a.family().id(), a.ma(), T0.plusSeconds(60), List.of());
            UUID citing = turn(a.family().id(), a.ma(), T0.plusSeconds(120), List.of(doc));
            UUID theirs = turn(b.family().id(), a.ma(), T0.plusSeconds(180), List.of());
            ThreadReadPort port = context.getBean(ThreadReadPort.class);
            assertThat(ids(port.page(a.family().id(), a.ma().id(), ThreadItem.Cursor.FIRST, 500, true).collectList().block()))
                    .contains(mine, citing).doesNotContain(theirs);
            context.getBean(com.elioo.baymax.extraction.application.port.out.DocumentRecordPort.class).deleteDocument(doc).block();
            assertThat(ids(port.page(a.family().id(), a.ma().id(), ThreadItem.Cursor.FIRST, 500, true).collectList().block()))
                    .containsExactly(mine);
        });
    }

    @Test
    void theCursorPagesAcrossAsksWithoutRepeatsOrGaps() {
        runner().run(context -> {
            Holder a = owner(context);
            Set<UUID> all = new LinkedHashSet<>();
            // 45 items: 15 papers, 15 delivered messages and 15 answered turns, a paper and a turn sharing each instant
            for (int i = 0; i < 15; i++) {
                Instant at = T0.plus(Duration.ofHours(i));
                UUID doc = paper(context, a.ma(), at);
                all.add(doc);
                all.add(delivered(context, a.family().id(), a.ma(), doc, "detail", at.plusSeconds(1)));
                all.add(turn(a.family().id(), a.ma(), at, List.of(doc)));
            }
            ThreadReadPort port = context.getBean(ThreadReadPort.class);
            List<Integer> sizes = new ArrayList<>();
            List<UUID> seen = new ArrayList<>();
            ThreadItem.Cursor after = ThreadItem.Cursor.FIRST;
            for (int guard = 0; guard < 10; guard++) {
                List<ThreadItem> page = port.page(a.family().id(), a.ma().id(), after, 20, true).collectList().block();
                if (page.isEmpty()) {
                    break;
                }
                sizes.add(page.size());
                seen.addAll(ids(page));
                after = ThreadItem.Cursor.parse(ThreadItem.Cursor.after(page.get(page.size() - 1)).encode());
            }
            assertThat(all).hasSize(45);
            assertThat(sizes).as("page sizes").containsExactly(20, 20, 5);
            assertThat(seen).as("every id once").hasSize(45).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(all);
        });
    }

    static void sql(String statement) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute(statement);
        }
    }

    static String scalar(String query) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(query)) {
            assertThat(rs.next()).as("a row for " + query).isTrue();
            return rs.getString(1);
        }
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl() + "&sslmode=disable", POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
