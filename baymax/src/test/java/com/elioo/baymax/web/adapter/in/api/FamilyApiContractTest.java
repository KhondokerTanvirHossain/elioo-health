package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.testsupport.Json;
import com.elioo.baymax.testsupport.Snapshot;
import com.elioo.baymax.web.adapter.in.PhoneDisplay;
import com.elioo.baymax.web.adapter.in.ui.Lang;
import com.elioo.baymax.web.adapter.in.ui.ProfilePages;
import com.elioo.baymax.web.adapter.in.ui.UiCopy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan B5: the পরিবার tab's JSON through the real module on Postgres — {@code GET /app/api/family}, the chronic flags,
 * the reminder stops (stop only: opting back in is the E1/E2-API task, behind the PO's yes, Q5/Q9) and the language
 * cookie. Families, patients, shares and sessions are real rows; every refusal is read back from the database, so a
 * refusal that still wrote cannot pass. Each test makes its own family with its own number; the three MSW cases use
 * fixed ids and fixed instants, so their bodies are byte-stable.
 *
 * <p>Every assertion reads the parsed tree by field path; none matches a substring of the document (the ids are UUIDs).
 */
@Testcontainers
class FamilyApiContractTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final Path FIXTURES = Path.of("../medioo-web/fixtures/api");
    private static final Path COMMITTED = FIXTURES.resolve("family");
    private static final Path WRITTEN = Path.of("build/web-contract/family");
    private static final AtomicInteger NUMBERS = new AtomicInteger();

    private final ObjectMapper json = new ObjectMapper();
    private final UiCopy copy = new UiCopy();

    /** A fictional number in the fixture range, unique per call (the MSW cases own 533 to 536). */
    private static String number() {
        return "+88017000006" + String.format("%02d", NUMBERS.incrementAndGet());
    }

    // ---- the read -------------------------------------------------------------------------------------------

    /**
     * Field by field against what was seeded. The instants sit near Dhaka midnight on purpose, in both directions: terms
     * at 18:30Z and Baba's consent at 18:30Z are the next Dhaka day (a UTC derivation says the day before), Ma's consent
     * at 17:59Z is the same day (a derivation that always added a day says the day after). Each row's {@code created_at}
     * is on another Dhaka day again, so a date read from the wrong column fails too; the guard below says so from the
     * rows themselves.
     */
    @Test
    void theOwnerSeesFlagsConsentRemindersAndLanguage() {
        AppApiContext.runner(POSTGRES).run(context -> {
            String number = number();
            UUID family = family(UUID.randomUUID(), number, "Tanvir", "2026-03-01T18:30:00Z", "2026-02-20T04:00:00Z");
            UUID ma = patient(UUID.randomUUID(), family, "রোকেয়া পরীক্ষা", 64, "female", List.of("diabetes", "hypertension", "asthma"),
                    "2026-04-10T17:59:00Z", "2026-04-20T04:00:00Z");
            UUID baba = patient(UUID.randomUUID(), family, "Karim", 71, "male", List.of(), "2026-04-11T18:30:00Z", "2026-04-21T04:00:00Z");
            String token = AppApiContext.session(context, family);
            WebTestClient client = client(context);
            // the guard: on every seeded row the column the date is read from and created_at are different Dhaka days
            assertThat(text("select count(*) from baymax.family_account where id = '" + family + "' and "
                    + DHAKA_DAY.formatted("terms_accepted_at") + " <> " + DHAKA_DAY.formatted("created_at"))).isEqualTo("1");
            assertThat(text("select count(*) from baymax.patient_profile where family_id = '" + family + "' and "
                    + DHAKA_DAY.formatted("proxy_consent_at") + " <> " + DHAKA_DAY.formatted("created_at"))).isEqualTo("2");

            JsonNode body = family(client, token, Lang.BN);
            assertThat(names(body)).containsExactlyInAnyOrder("account", "patients", "flags_offered");
            JsonNode account = body.get("account");
            assertThat(names(account)).containsExactlyInAnyOrder("owner_name", "number", "terms_date", "language", "reminders_stopped");
            assertThat(account.get("owner_name").asText()).isEqualTo("Tanvir");
            assertThat(account.get("number").asText()).isEqualTo(PhoneDisplay.full(number));
            assertThat(account.get("number").asText()).as("the displayed form, not E.164").isNotEqualTo(number);
            assertThat(account.get("terms_date").asText()).as("the Dhaka day of 18:30Z").isEqualTo("2026-03-02");
            assertThat(account.get("language").asText()).isEqualTo("bn");
            assertThat(account.get("reminders_stopped").isBoolean()).isTrue();
            assertThat(account.get("reminders_stopped").asBoolean()).isFalse();
            assertThat(ids(body.get("flags_offered"))).containsExactlyElementsOf(ProfilePages.FLAGS);
            body.get("flags_offered").forEach(f -> {
                assertThat(names(f)).containsExactlyInAnyOrder("id", "label_key");
                assertThat(f.get("label_key").asText()).isEqualTo("flag." + f.get("id").asText());
            });

            Map<String, JsonNode> byId = patients(body);
            assertThat(byId).containsOnlyKeys(ma.toString(), baba.toString());
            JsonNode m = byId.get(ma.toString());
            assertThat(names(m)).containsExactlyInAnyOrder("id", "name", "name_possessive", "age", "sex_key", "owner",
                    "chronic_flags", "consent_date", "reminders");
            assertThat(m.get("name").asText()).isEqualTo("রোকেয়া পরীক্ষা");
            assertThat(m.get("name_possessive").asText()).isEqualTo(copy.poss(Lang.BN, "রোকেয়া পরীক্ষা"));
            assertThat(m.get("age").asInt()).isEqualTo(64);
            assertThat(m.get("sex_key").asText()).isEqualTo("sex.FEMALE");
            assertThat(m.get("owner").asBoolean()).isTrue();
            assertThat(values(m.get("chronic_flags"))).containsExactly("diabetes", "hypertension", "asthma");
            // an offered flag names its copy; a flag the family typed ("other") has none and is shown as written
            JsonNode flags = m.get("chronic_flags");
            assertThat(names(flags.get(0))).containsExactlyInAnyOrder("value", "label_key");
            assertThat(flags.get(0).get("label_key").asText()).isEqualTo("flag.diabetes");
            assertThat(flags.get(1).get("label_key").asText()).isEqualTo("flag.hypertension");
            assertThat(flags.get(2).has("label_key")).as("the key is present for a typed flag too").isTrue();
            assertThat(flags.get(2).get("label_key").isNull()).as("a typed flag has no copy key").isTrue();
            assertThat(m.get("consent_date").asText()).as("the Dhaka day of 17:59Z, not created_at's").isEqualTo("2026-04-10");
            assertThat(m.get("reminders").asText()).isEqualTo("on");
            JsonNode b = byId.get(baba.toString());
            assertThat(b.get("age").asInt()).isEqualTo(71);
            assertThat(b.get("sex_key").asText()).isEqualTo("sex.MALE");
            assertThat(values(b.get("chronic_flags"))).isEmpty();
            assertThat(b.get("consent_date").asText()).as("the Dhaka day of 18:30Z, not the UTC one").isEqualTo("2026-04-12");

            // the viewer's language: the cookie, and the possessive in English
            JsonNode en = family(client, token, Lang.EN);
            assertThat(en.get("account").get("language").asText()).isEqualTo("en");
            assertThat(patients(en).get(baba.toString()).get("name_possessive").asText()).isEqualTo("Karim's");
            assertThat(patients(en).get(baba.toString()).get("name_possessive").asText())
                    .as("the two languages differ, so a handler that ignored the cookie fails")
                    .isNotEqualTo(patients(body).get(baba.toString()).get("name_possessive").asText());
        });
    }

    /**
     * Every {@code *_key} the JSON names resolves in both languages (Wave 2 Review Focus 5): the sexes, every offered
     * flag and every stored offered flag. A null {@code label_key} (a flag the family typed) names no key and is not
     * collected; that it is null is asserted on its own, so the walk cannot pass by skipping a key that should be there.
     */
    @Test
    void everyEmittedKeyResolvesInBothLanguages() {
        AppApiContext.runner(POSTGRES).run(context -> {
            UUID family = family(number(), "Tanvir", "2026-03-01T04:00:00Z");
            for (String sex : List.of("male", "female", "other", "unknown")) {
                List<String> flags = new ArrayList<>(ProfilePages.FLAGS);
                flags.add("asthma");
                patient(family, "P " + sex, 40, sex, sex.equals("male") ? flags : List.of(), "2026-04-01T04:00:00Z");
            }
            JsonNode body = family(client(context), AppApiContext.session(context, family), Lang.BN);
            List<String> keys = new ArrayList<>();
            List<String> nullKeys = new ArrayList<>();
            Json.walk(body, (path, node) -> {
                String field = Json.fieldName(path);
                if (field != null && field.endsWith("_key")) {
                    if (node.isNull()) {
                        nullKeys.add(path);
                    } else {
                        keys.add(node.asText());
                    }
                }
            });
            assertThat(keys).as("a sex_key per patient, a label_key per offered flag and per stored offered flag")
                    .hasSizeGreaterThanOrEqualTo(4 + 2 * ProfilePages.FLAGS.size());
            assertThat(nullKeys).as("only the typed flag has no key").hasSize(1);
            // derived from the answer, not from a list written here: every offered flag has its copy in both languages
            List<String> offered = ids(body.get("flags_offered"));
            assertThat(offered).isNotEmpty();
            for (Lang lang : Lang.values()) {
                assertThat(copy.keys(lang)).as("keys of " + lang).containsAll(keys);
                for (String f : offered) {
                    assertThat(copy.keys(lang)).as("the copy of the offered flag " + f + " in " + lang).contains("flag." + f);
                }
            }
        });
    }

    /**
     * The sibling's view (D30): their own account, the shared patient with {@code owner: false}, and every write on that
     * patient refused 403 {@code VIEW_ONLY} with nothing stored. Their own family stop stops their own family only.
     */
    @Test
    void aShareMemberSeesTheSharedPatientReadOnly() {
        AppApiContext.runner(POSTGRES).run(context -> {
            String ownerNumber = number();
            UUID owner = family(ownerNumber, "Tanvir", "2026-03-01T04:00:00Z");
            UUID ma = patient(owner, "রোকেয়া পরীক্ষা", 64, "female", List.of("diabetes"), "2026-04-01T04:00:00Z");
            String memberNumber = number();
            UUID member = family(memberNumber, "Karim", "2026-05-01T04:00:00Z");
            share(ma, memberNumber);
            String token = AppApiContext.session(context, member);
            WebTestClient client = client(context);

            JsonNode body = family(client, token, Lang.BN);
            assertThat(body.get("account").get("owner_name").asText()).as("the member's own account").isEqualTo("Karim");
            assertThat(body.get("account").get("number").asText()).isEqualTo(PhoneDisplay.full(memberNumber));
            assertThat(body.get("account").get("terms_date").asText()).isEqualTo("2026-05-01");
            assertThat(patients(body)).containsOnlyKeys(ma.toString());
            assertThat(patients(body).get(ma.toString()).get("owner").asBoolean()).isFalse();
            assertThat(values(patients(body).get(ma.toString()).get("chronic_flags"))).containsExactly("diabetes");

            String flagsBefore = text("select array_to_string(chronic_flags, ',') from baymax.patient_profile where id = '" + ma + "'");
            put(client, token, "/app/api/patients/" + ma + "/flags", Map.of("chronic_flags", List.of("heart")))
                    .expectStatus().isForbidden().expectBody().json("{\"reason\":\"VIEW_ONLY\"}", true);
            put(client, token, "/app/api/patients/" + ma + "/reminders", Map.of("on", false))
                    .expectStatus().isForbidden().expectBody().json("{\"reason\":\"VIEW_ONLY\"}", true);
            assertThat(text("select array_to_string(chronic_flags, ',') from baymax.patient_profile where id = '" + ma + "'"))
                    .isEqualTo(flagsBefore).isEqualTo("diabetes");
            assertThat(text("select nudges_opted_out_at::text from baymax.patient_profile where id = '" + ma + "'")).isNull();
            assertThat(text("select nudges_opted_out_at::text from baymax.family_account where id = '" + owner + "'")).isNull();

            // the member's family stop is theirs: the owner's family, and so the shared patient, keep their reminders
            // the owner's family first: a stop keyed by a patient the member sees would land there, silencing the shared patient
            JsonNode stopped = json.readTree(put(client, token, "/app/api/family/reminders", Map.of("on", false))
                    .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody());
            assertThat(text("select nudges_opted_out_at::text from baymax.family_account where id = '" + owner + "'"))
                    .as("the owner's family stop after the member's own stop").isNull();
            assertThat(text("select nudges_opted_out_at::text from baymax.family_account where id = '" + member + "'")).isNotNull();
            assertThat(stopped.get("account").get("reminders_stopped").asBoolean()).isTrue();
            assertThat(patients(stopped).get(ma.toString()).get("reminders").asText()).isEqualTo("on");
        });
    }

    /**
     * The other direction of the shared patient's {@code reminders}: the stops that count are the owner's (the patient's
     * own, then the owner's family's, {@code PatientRef.familyOptedOutAt}), so after the owner stops them the member is
     * told so, while the member's own account still says their family is not stopped. An answer that ignored stops on a
     * patient the viewer does not own ("on" always) fails here.
     */
    @Test
    void aShareMemberIsToldTheOwnersStopsOnTheSharedPatient() {
        AppApiContext.runner(POSTGRES).run(context -> {
            UUID owner = family(number(), "Tanvir", "2026-03-01T04:00:00Z");
            UUID ma = patient(owner, "রোকেয়া পরীক্ষা", 64, "female", List.of(), "2026-04-01T04:00:00Z");
            String memberNumber = number();
            UUID member = family(memberNumber, "Karim", "2026-05-01T04:00:00Z");
            share(ma, memberNumber);
            String ownerToken = AppApiContext.session(context, owner);
            String memberToken = AppApiContext.session(context, member);
            WebTestClient client = client(context);
            assertThat(patients(family(client, memberToken, Lang.BN)).get(ma.toString()).get("reminders").asText()).isEqualTo("on");

            put(client, ownerToken, "/app/api/patients/" + ma + "/reminders", Map.of("on", false)).expectStatus().isOk();
            JsonNode afterPatientStop = family(client, memberToken, Lang.BN);
            assertThat(patients(afterPatientStop).get(ma.toString()).get("reminders").asText()).isEqualTo("stopped_for_patient");
            assertThat(afterPatientStop.get("account").get("reminders_stopped").asBoolean()).as("the member's own family").isFalse();

            put(client, ownerToken, "/app/api/family/reminders", Map.of("on", false)).expectStatus().isOk();
            JsonNode afterFamilyStop = family(client, memberToken, Lang.BN);
            assertThat(patients(afterFamilyStop).get(ma.toString()).get("reminders").asText()).isEqualTo("stopped_for_family");
            assertThat(afterFamilyStop.get("account").get("reminders_stopped").asBoolean()).as("the member's own family").isFalse();
            assertThat(text("select nudges_opted_out_at::text from baymax.family_account where id = '" + member + "'")).isNull();
        });
    }

    /** A patient this family cannot see is the same 404 as one that does not exist, on every write, and nothing is stored. */
    @Test
    void anUnseenPatientIsNotFoundOnEveryWrite() {
        AppApiContext.runner(POSTGRES).run(context -> {
            UUID mine = family(number(), "Tanvir", "2026-03-01T04:00:00Z");
            patient(mine, "Ma", 64, "female", List.of(), "2026-04-01T04:00:00Z");
            UUID other = family(number(), "Karim", "2026-03-01T04:00:00Z");
            UUID theirs = patient(other, "Baba", 70, "male", List.of("kidney"), "2026-04-01T04:00:00Z");
            String token = AppApiContext.session(context, mine);
            WebTestClient client = client(context);
            for (String id : List.of(theirs.toString(), UUID.randomUUID().toString(), "not-a-uuid")) {
                put(client, token, "/app/api/patients/" + id + "/flags", Map.of("chronic_flags", List.of("heart")))
                        .expectStatus().isNotFound().expectBody().json("{\"reason\":\"NOT_FOUND\"}", true);
                put(client, token, "/app/api/patients/" + id + "/reminders", Map.of("on", false))
                        .expectStatus().isNotFound().expectBody().json("{\"reason\":\"NOT_FOUND\"}", true);
            }
            assertThat(text("select array_to_string(chronic_flags, ',') from baymax.patient_profile where id = '" + theirs + "'")).isEqualTo("kidney");
            assertThat(text("select nudges_opted_out_at::text from baymax.patient_profile where id = '" + theirs + "'")).isNull();
        });
    }

    // ---- the flags ------------------------------------------------------------------------------------------

    /**
     * One case per refusal {@code FamilyAccountService.normalizeFlags} has (more than {@code MAX_CHRONIC_FLAGS}, 20; an
     * entry over {@code MAX_FLAG_LENGTH}, 40 characters), and a body without the list: each is 400 {@code INVALID} and
     * the stored flags are unchanged. Before the fix the first two were a 500 (the refusal was thrown inside a
     * {@code flatMap}, an {@code IllegalArgumentException} the guard rightly calls a server fault).
     */
    @Test
    void tooManyOrTooLongFlagsIs400Invalid() {
        AppApiContext.runner(POSTGRES).run(context -> {
            UUID family = family(number(), "Tanvir", "2026-03-01T04:00:00Z");
            UUID ma = patient(family, "Ma", 64, "female", List.of("thyroid"), "2026-04-01T04:00:00Z");
            String token = AppApiContext.session(context, family);
            WebTestClient client = client(context);
            String query = "select array_to_string(chronic_flags, ',') from baymax.patient_profile where id = '" + ma + "'";

            List<String> tooMany = IntStream.rangeClosed(1, 21).mapToObj(i -> "flag" + i).toList();
            List<String> tooLong = List.of("diabetes", "x".repeat(41));
            Map<String, Object> noList = Map.of("flags", List.of("diabetes"));
            for (Object refused : List.of(Map.of("chronic_flags", tooMany), Map.of("chronic_flags", tooLong), noList)) {
                put(client, token, "/app/api/patients/" + ma + "/flags", refused)
                        .expectStatus().isBadRequest().expectBody().json("{\"reason\":\"INVALID\"}", true);
                assertThat(text(query)).as("stored flags after a refused " + refused.getClass().getSimpleName()).isEqualTo("thyroid");
            }

            // the edges are accepted: exactly 20, exactly 40 characters, so the refusals above are the limits themselves
            List<String> twenty = IntStream.rangeClosed(1, 20).mapToObj(i -> "flag" + i).toList();
            put(client, token, "/app/api/patients/" + ma + "/flags", Map.of("chronic_flags", twenty)).expectStatus().isOk();
            assertThat(text(query).split(",")).hasSize(20);
            put(client, token, "/app/api/patients/" + ma + "/flags", Map.of("chronic_flags", List.of("y".repeat(40)))).expectStatus().isOk();
            assertThat(text(query)).isEqualTo("y".repeat(40));
        });
    }

    /**
     * What is accepted is stored as the HTML form's save stores it ({@code normalizeFlags}: trimmed, lower-cased, each
     * once), and a flag the HTML's "other" field could have written is kept, not refused: the answer is the patient.
     */
    @Test
    void acceptedFlagsAreStoredAsTheHtmlFormStoresThemAndAnsweredAsThePatient() {
        AppApiContext.runner(POSTGRES).run(context -> {
            UUID family = family(number(), "Tanvir", "2026-03-01T04:00:00Z");
            UUID ma = patient(family, "Ma", 64, "female", List.of(), "2026-04-01T04:00:00Z");
            String token = AppApiContext.session(context, family);
            JsonNode answer = json.readTree(put(client(context), token, "/app/api/patients/" + ma + "/flags",
                    Map.of("chronic_flags", List.of("diabetes", " Diabetes ", "Asthma", "heart")))
                    .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody());
            assertThat(answer.get("id").asText()).isEqualTo(ma.toString());
            assertThat(values(answer.get("chronic_flags"))).containsExactly("diabetes", "asthma", "heart");
            assertThat(answer.get("chronic_flags").get(1).get("label_key").isNull()).as("the typed flag, shown as written").isTrue();
            // the typed flag round-trips through the read the পরিবার tab draws from, not only through the write's answer
            JsonNode read = patients(family(client(context), token, Lang.BN)).get(ma.toString());
            assertThat(values(read.get("chronic_flags"))).containsExactly("diabetes", "asthma", "heart");
            assertThat(answer.get("owner").asBoolean()).isTrue();
            assertThat(answer.get("reminders").asText()).isEqualTo("on");
            assertThat(text("select array_to_string(chronic_flags, ',') from baymax.patient_profile where id = '" + ma + "'"))
                    .isEqualTo("diabetes,asthma,heart");
        });
    }

    // ---- the reminders --------------------------------------------------------------------------------------

    /** A stop is stored and said for what it is: the patient's own stop, and then the family's, which covers every patient. */
    @Test
    void aStopIsStoredAndSaidHonestly() {
        AppApiContext.runner(POSTGRES).run(context -> {
            UUID family = family(number(), "Tanvir", "2026-03-01T04:00:00Z");
            UUID ma = patient(family, "Ma", 64, "female", List.of(), "2026-04-01T04:00:00Z");
            UUID baba = patient(family, "Baba", 70, "male", List.of(), "2026-04-02T04:00:00Z");
            String token = AppApiContext.session(context, family);
            WebTestClient client = client(context);

            JsonNode patient = json.readTree(put(client, token, "/app/api/patients/" + ma + "/reminders", Map.of("on", false))
                    .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody());
            assertThat(patient.get("id").asText()).isEqualTo(ma.toString());
            assertThat(patient.get("reminders").asText()).isEqualTo("stopped_for_patient");
            assertThat(text("select nudges_opted_out_at::text from baymax.patient_profile where id = '" + ma + "'")).isNotNull();
            assertThat(text("select nudges_opted_out_at::text from baymax.patient_profile where id = '" + baba + "'")).isNull();
            assertThat(text("select nudges_opted_out_at::text from baymax.family_account where id = '" + family + "'")).isNull();
            JsonNode read = family(client, token, Lang.BN);
            assertThat(patients(read).get(ma.toString()).get("reminders").asText()).isEqualTo("stopped_for_patient");
            assertThat(patients(read).get(baba.toString()).get("reminders").asText()).isEqualTo("on");
            assertThat(read.get("account").get("reminders_stopped").asBoolean()).isFalse();

            JsonNode stopped = json.readTree(put(client, token, "/app/api/family/reminders", Map.of("on", false))
                    .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody());
            assertThat(text("select nudges_opted_out_at::text from baymax.family_account where id = '" + family + "'")).isNotNull();
            assertThat(stopped.get("account").get("reminders_stopped").asBoolean()).isTrue();
            assertThat(patients(stopped).get(ma.toString()).get("reminders").asText()).as("the family's stop wins").isEqualTo("stopped_for_family");
            assertThat(patients(stopped).get(baba.toString()).get("reminders").asText()).isEqualTo("stopped_for_family");
            JsonNode again = json.readTree(put(client, token, "/app/api/patients/" + ma + "/reminders", Map.of("on", false))
                    .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody());
            assertThat(again.get("reminders").asText()).isEqualTo("stopped_for_family");
        });
    }

    /** Before E1/E2-API (Q5, Q9), {@code on: true} is refused on both routes, and neither stop is cleared or restamped. */
    @Test
    void optingBackInIsRefusedUntilE2() {
        AppApiContext.runner(POSTGRES).run(context -> {
            UUID family = family(number(), "Tanvir", "2026-03-01T04:00:00Z");
            UUID ma = patient(family, "Ma", 64, "female", List.of(), "2026-04-01T04:00:00Z");
            AppApiContext.sql(POSTGRES, "UPDATE baymax.patient_profile SET nudges_opted_out_at = '2026-10-01T05:00:00Z' WHERE id = '" + ma + "'");
            AppApiContext.sql(POSTGRES, "UPDATE baymax.family_account SET nudges_opted_out_at = '2026-10-02T05:00:00Z' WHERE id = '" + family + "'");
            String token = AppApiContext.session(context, family);
            WebTestClient client = client(context);
            String patientStop = text("select nudges_opted_out_at::text from baymax.patient_profile where id = '" + ma + "'");
            String familyStop = text("select nudges_opted_out_at::text from baymax.family_account where id = '" + family + "'");
            assertThat(patientStop).isNotNull();
            assertThat(familyStop).isNotNull();

            for (Object body : List.of(Map.of("on", true), Map.of("other", false))) {
                put(client, token, "/app/api/patients/" + ma + "/reminders", body)
                        .expectStatus().isBadRequest().expectBody().json("{\"reason\":\"INVALID\"}", true);
                put(client, token, "/app/api/family/reminders", body)
                        .expectStatus().isBadRequest().expectBody().json("{\"reason\":\"INVALID\"}", true);
            }
            assertThat(text("select nudges_opted_out_at::text from baymax.patient_profile where id = '" + ma + "'")).isEqualTo(patientStop);
            assertThat(text("select nudges_opted_out_at::text from baymax.family_account where id = '" + family + "'")).isEqualTo(familyStop);
        });
    }

    // ---- the language ---------------------------------------------------------------------------------------

    /**
     * The language is the cookie and nothing else: one {@code Set-Cookie}, {@code baymax_lang}, {@code Path=/}; unknown is
     * Bangla. The read that follows each PUT sends back the cookie that PUT set (not one this test builds), so the round
     * trip is what is asserted; the account row's whole text is the same before and after the four PUTs.
     */
    @Test
    void languageSetsOnlyTheCookie() {
        AppApiContext.runner(POSTGRES).run(context -> {
            UUID family = family(number(), "Tanvir", "2026-03-01T04:00:00Z");
            String token = AppApiContext.session(context, family);
            WebTestClient client = client(context);
            String row = "select t::text from baymax.family_account t where id = '" + family + "'";
            String rowBefore = text(row);
            Map<String, String> expected = new LinkedHashMap<>();
            expected.put("en", "en");
            expected.put("EN", "en");
            expected.put("bn", "bn");
            expected.put("fr", "bn");
            for (Map.Entry<String, String> e : expected.entrySet()) {
                EntityExchangeResult<byte[]> r = put(client, token, "/app/api/language", Map.of("lang", e.getKey()))
                        .expectStatus().isNoContent().expectBody().returnResult();
                List<String> setCookies = r.getResponseHeaders().getOrEmpty(HttpHeaders.SET_COOKIE);
                assertThat(setCookies).as("Set-Cookie lines for " + e.getKey()).hasSize(1);
                assertThat(r.getResponseCookies().keySet()).containsExactly("baymax_lang");
                ResponseCookie cookie = r.getResponseCookies().getFirst("baymax_lang");
                assertThat(cookie.getValue()).as("the cookie for " + e.getKey()).isEqualTo(e.getValue());
                assertThat(cookie.getPath()).isEqualTo("/");
                JsonNode next = json.readTree(client.get().uri("/app/api/family").cookie("baymax_session", token)
                        .cookie("baymax_lang", cookie.getValue()).exchange().expectStatus().isOk()
                        .expectBody(String.class).returnResult().getResponseBody());
                assertThat(next.get("account").get("language").asText()).as("the read with the cookie " + e.getKey() + " set")
                        .isEqualTo(e.getValue());
            }
            assertThat(text(row)).as("the account row, whole, after four language PUTs").isEqualTo(rowBefore);
        });
    }

    // ---- the number -----------------------------------------------------------------------------------------

    private static final List<Pattern> NUMBER_FORMS = List.of(
            Pattern.compile("\\+?880\\s?1\\d{3}-?\\d{6}"),
            Pattern.compile("(?<![0-9a-f])01\\d{9}(?![0-9a-f])"),
            Pattern.compile("\\+?৮৮০\\s?১[০-৯]{3}-?[০-৯]{6}"),
            Pattern.compile("(?<![0-9a-f০-৯])০১[০-৯]{9}(?![0-9a-f০-৯])"));
    private static final String ACCOUNT_NUMBER = "$.body.account.number";

    /**
     * Derived from every committed MSW fixture, by field: the family's number appears as {@code body.account.number} of a
     * {@code family/*.json} and nowhere else under {@code fixtures/api/} — no other string value, no number value
     * ({@code 8801700000533} as a JSON number) and no field name — in any form a page shows (E.164,
     * {@code PhoneDisplay.full}, the 11-digit national form) or with Bangla digits. Guards: the patterns see each form of
     * every {@code account.number} they exempt, and the walk read a file in every fixture area that exists.
     */
    @Test
    void theNumberAppearsOnlyAsTheAccountNumber() throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(FIXTURES)) {
            files = walk.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        List<String> hits = new ArrayList<>();
        Set<String> areasRead = new TreeSet<>();
        List<String> accountNumbers = new ArrayList<>();
        // plan W3-api: the sign-in fixtures carry two numbers that are not a family's account number, each exempted
        // at one path and only when it is exactly the value SignInApiContractTest serves — the visitor's own typed
        // number shown back for a yes, and Medioo's public WhatsApp number in the link. Any other value there is a hit.
        String shownBack = PhoneDisplay.full(SignInApiContractTest.NUMBER);
        String waPrefix = "https://wa.me/" + SignInApiContractTest.PUBLIC_NUMBER + "?text=";
        List<String> exempted = new ArrayList<>();
        for (Path file : files) {
            String area = FIXTURES.relativize(file).getName(0).toString();
            areasRead.add(area);
            boolean family = area.equals("family");
            boolean signIn = area.equals("sign-in");
            JsonNode tree = json.readTree(Files.readString(file, StandardCharsets.UTF_8));
            Json.walk(tree, (path, node) -> {
                List<String> seen = new ArrayList<>();
                String field = Json.fieldName(path);
                if (field != null) {
                    seen.add(field);
                }
                if (node.isTextual() || node.isNumber()) {
                    if (family && path.equals(ACCOUNT_NUMBER) && node.isTextual()) {
                        accountNumbers.add(node.asText());
                    } else if (signIn && node.isTextual() && (path.equals("$.body.confirm.number_display") && node.asText().equals(shownBack)
                            || path.equals("$.body.wa_link") && node.asText().startsWith(waPrefix)
                            && !NUMBER_FORMS.stream().anyMatch(p -> p.matcher(node.asText().substring(waPrefix.length())).find()))) {
                        exempted.add(FIXTURES.relativize(file) + " " + path);
                    } else {
                        seen.add(node.asText());
                    }
                }
                for (String text : seen) {
                    for (Pattern form : NUMBER_FORMS) {
                        if (form.matcher(text).find()) {
                            hits.add(FIXTURES.relativize(file) + " " + path);
                        }
                    }
                }
            });
        }
        assertThat(hits).as("string values carrying a phone number outside account.number").isEmpty();
        // the exemption is not vacuous, and what it lets through is a number the patterns do see
        assertThat(exempted).as("the sign-in numbers exempted by value").contains("sign-in/confirm.json $.body.confirm.number_display")
                .anyMatch(e -> e.endsWith("$.body.wa_link"));
        assertThat(NUMBER_FORMS.stream().anyMatch(p -> p.matcher(shownBack).find())).as("the patterns see " + shownBack).isTrue();
        assertThat(NUMBER_FORMS.stream().anyMatch(p -> p.matcher(waPrefix).find())).as("the patterns see " + waPrefix).isTrue();

        Set<String> areas;
        try (Stream<Path> dirs = Files.list(FIXTURES)) {
            areas = dirs.filter(Files::isDirectory).map(p -> p.getFileName().toString()).collect(Collectors.toCollection(TreeSet::new));
        }
        assertThat(areas).as("fixture areas").contains("documents", "family");
        assertThat(areasRead).as("every fixture area had a file read").containsExactlyElementsOf(areas);
        long familyFiles = files.stream().filter(f -> FIXTURES.relativize(f).getName(0).toString().equals("family")).count();
        assertThat(accountNumbers).as("one account.number per family fixture").hasSize((int) familyFiles).isNotEmpty();
        for (String shown : accountNumbers) {
            String e164 = shown.replaceAll("[\\s-]", "");
            String national = "0" + e164.substring(4);
            for (String form : List.of(shown, e164, national, bangla(shown), bangla(e164), bangla(national))) {
                assertThat(NUMBER_FORMS.stream().anyMatch(p -> p.matcher(form).find())).as("the patterns see " + form).isTrue();
            }
        }
    }

    private static String bangla(String ascii) {
        StringBuilder b = new StringBuilder();
        ascii.chars().forEach(c -> b.append(c >= '0' && c <= '9' ? (char) ('০' + (c - '0')) : (char) c));
        return b.toString();
    }

    // ---- the MSW fixtures -----------------------------------------------------------------------------------

    /** case name → the family it is read as. Fixed ids and instants, so each body is byte-stable. */
    static Map<String, CaseSeed> cases() {
        Map<String, CaseSeed> cases = new LinkedHashMap<>();
        cases.put("owner", FamilyApiContractTest::ownerCase);
        cases.put("share-member", FamilyApiContractTest::shareMemberCase);
        cases.put("reminders-off", FamilyApiContractTest::remindersOffCase);
        return cases;
    }

    static Stream<String> caseNames() {
        return cases().keySet().stream();
    }

    @FunctionalInterface
    interface CaseSeed {
        /** Seeds the case's rows and answers the family whose session reads it. */
        UUID seed() throws Exception;
    }

    private static UUID id(String name) {
        return UUID.nameUUIDFromBytes(("family-fixture-" + name).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Two patients: one with an offered and a typed flag and her reminders on; one whose own reminders the family
     * stopped. Every {@code created_at} is on another Dhaka day than the terms or the consent, so the fixture shows
     * which column a date is read from; Ma's consent at 18:30Z is the next Dhaka day.
     */
    private static UUID ownerCase() throws Exception {
        UUID family = family(id("owner"), "+8801700000533", "Tanvir", "2026-09-14T18:30:00Z", "2026-09-01T04:00:00Z");
        patient(id("owner-ma"), family, "রোকেয়া পরীক্ষা", 64, "female", List.of("diabetes", "hypertension", "asthma"),
                "2026-09-14T18:30:00Z", "2026-09-05T04:00:00Z");
        UUID baba = patient(id("owner-baba"), family, "Baba", 71, "male", List.of(), "2026-09-15T04:00:00Z", "2026-09-06T04:00:00Z");
        AppApiContext.sql(POSTGRES, "UPDATE baymax.patient_profile SET nudges_opted_out_at = '2026-10-01T05:00:00Z' WHERE id = '" + baba + "'");
        return family;
    }

    /** The sibling's own account, seeing the owner's patient read-only. */
    private static UUID shareMemberCase() throws Exception {
        UUID owner = family(id("share-owner"), "+8801700000535", "Tanvir", "2026-09-14T03:00:00Z", "2026-09-01T04:00:00Z");
        UUID ma = patient(id("share-ma"), owner, "রোকেয়া পরীক্ষা", 64, "female", List.of("kidney"), "2026-09-14T04:00:00Z", "2026-09-05T04:00:00Z");
        UUID member = family(id("share-member"), "+8801700000534", "Karim", "2026-09-20T03:00:00Z", "2026-09-10T04:00:00Z");
        share(ma, "+8801700000534");
        return member;
    }

    /** The family stopped every reminder: the account says so and every patient says why. */
    private static UUID remindersOffCase() throws Exception {
        UUID family = family(id("off"), "+8801700000536", "Tanvir", "2026-09-14T03:00:00Z", "2026-09-01T04:00:00Z");
        patient(id("off-ma"), family, "রোকেয়া পরীক্ষা", 64, "female", List.of(), "2026-09-14T04:00:00Z", "2026-09-05T04:00:00Z");
        AppApiContext.sql(POSTGRES, "UPDATE baymax.family_account SET nudges_opted_out_at = '2026-10-01T05:00:00Z' WHERE id = '" + family + "'");
        return family;
    }

    @ParameterizedTest
    @MethodSource("caseNames")
    void eachCaseIsItsCommittedMswFixture(String name) {
        AppApiContext.runner(POSTGRES).run(context -> {
            UUID family = cases().get(name).seed();
            EntityExchangeResult<byte[]> r = client(context).get().uri("/app/api/family")
                    .cookie("baymax_session", AppApiContext.session(context, family)).exchange().expectBody().returnResult();
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("_meta", Snapshot.meta(FamilyApiContractTest.class, "GET /app/api/family over the case " + name));
            envelope.put("status", r.getStatus().value());
            envelope.put("body", json.readValue(r.getResponseBody(), Object.class));
            String file = name + ".json";
            Snapshot.matches(COMMITTED.resolve(file), WRITTEN.resolve(file), Snapshot.json(envelope),
                    "cp baymax/" + WRITTEN.resolve(file) + " medioo-web/fixtures/api/family/" + file);
        });
    }

    /** Both ways: every committed fixture is a current case, so a renamed or removed case leaves no stale body for MSW. */
    @Test
    void everyCommittedFixtureIsACurrentCase() throws IOException {
        List<String> committed;
        try (Stream<Path> files = Files.list(COMMITTED)) {
            committed = files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json")).sorted().toList();
        }
        assertThat(committed).as("the committed fixtures in " + COMMITTED.toAbsolutePath()).isNotEmpty();
        assertThat(committed).containsExactlyInAnyOrderElementsOf(caseNames().map(n -> n + ".json").toList());
    }

    // ---- seeding and reading --------------------------------------------------------------------------------

    /** The Dhaka day of a column, in SQL: the guard that a seeded row's columns fall on different days. */
    private static final String DHAKA_DAY = "(%s AT TIME ZONE 'Asia/Dhaka')::date";

    /**
     * A row's {@code created_at} is never on the day of the instant a date is read from: the family is created nine days
     * before its terms, a patient ten days before the consent (the order of patients, by {@code created_at}, is kept), so
     * a date read from {@code created_at} is a different answer, never the same one by chance.
     */
    private static String daysBefore(String at, int days) {
        return Instant.parse(at).minus(Duration.ofDays(days)).toString();
    }

    private static UUID family(String number, String owner, String termsAt) throws Exception {
        return family(UUID.randomUUID(), number, owner, termsAt, daysBefore(termsAt, 9));
    }

    private static UUID family(UUID id, String number, String owner, String termsAt, String createdAt) throws Exception {
        AppApiContext.sql(POSTGRES, "INSERT INTO baymax.family_account (id, whatsapp_number, owner_name, plan, terms_accepted_at, created_at)"
                + " VALUES ('" + id + "','" + number + "','" + owner + "','family','" + termsAt + "','" + createdAt + "')");
        return id;
    }

    private static UUID patient(UUID family, String name, int age, String sex, List<String> flags, String consentAt) throws Exception {
        return patient(UUID.randomUUID(), family, name, age, sex, flags, consentAt, daysBefore(consentAt, 10));
    }

    private static UUID patient(UUID id, UUID family, String name, int age, String sex, List<String> flags, String consentAt,
                                String createdAt) throws Exception {
        String array = flags.isEmpty() ? "'{}'::text[]"
                : "ARRAY[" + flags.stream().map(f -> "'" + f + "'").collect(Collectors.joining(",")) + "]::text[]";
        AppApiContext.sql(POSTGRES, "INSERT INTO baymax.patient_profile (id, family_id, name, age, sex, chronic_flags, proxy_consent_at, created_at)"
                + " VALUES ('" + id + "','" + family + "','" + name.replace("'", "''") + "'," + age + ",'" + sex + "'," + array + ",'"
                + consentAt + "','" + createdAt + "')");
        return id;
    }

    private static void share(UUID patient, String number) throws Exception {
        AppApiContext.sql(POSTGRES, "INSERT INTO baymax.share_member (patient_id, whatsapp_number) VALUES ('" + patient + "','" + number + "')");
    }

    /** The one text value a query answers (null for SQL NULL). */
    private static String text(String query) throws Exception {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl() + "&sslmode=disable", POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement(); ResultSet r = s.executeQuery(query)) {
            assertThat(r.next()).as("a row for " + query).isTrue();
            return r.getString(1);
        }
    }

    private static WebTestClient client(ApplicationContext context) {
        return WebTestClient.bindToApplicationContext(context).build();
    }

    private JsonNode family(WebTestClient client, String token, Lang lang) throws IOException {
        return json.readTree(client.get().uri("/app/api/family").cookie("baymax_session", token).cookie("baymax_lang", lang.code)
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody());
    }

    private static WebTestClient.ResponseSpec put(WebTestClient client, String token, String uri, Object body) {
        return client.put().uri(uri).cookie("baymax_session", token).header("X-Medioo", "1").header("Origin", AppApiContext.ORIGIN)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    private static Map<String, JsonNode> patients(JsonNode body) {
        Map<String, JsonNode> byId = new HashMap<>();
        body.get("patients").forEach(p -> byId.put(p.get("id").asText(), p));
        return byId;
    }

    private static List<String> names(JsonNode node) {
        List<String> out = new ArrayList<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            out.add(it.next());
        }
        return Collections.unmodifiableList(out);
    }

    /** The stored values of a {@code chronic_flags} array ({@code [{value, label_key}]}), in order. */
    private static List<String> values(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.get("value").asText()));
        return out;
    }

    /** The ids of a {@code flags_offered} array ({@code [{id, label_key}]}), in order. */
    private static List<String> ids(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.get("id").asText()));
        return out;
    }
}
