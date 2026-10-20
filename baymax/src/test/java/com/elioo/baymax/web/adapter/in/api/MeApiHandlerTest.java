package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.common.text.NameGrammar;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;
import com.elioo.baymax.web.domain.PatientSummary;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan B1 step 7: {@code GET /app/api/me} and {@code PUT /app/api/active-patient} through the real module on Postgres —
 * the context's routers, guard, services and adapters; families, patients, sessions and papers are real rows. Each test
 * makes its own family, so they run in any order against the one container.
 */
@Testcontainers
class MeApiHandlerTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final AtomicInteger NUMBERS = new AtomicInteger();
    /** Keys the family's JSON must never carry, at any depth: developer and model fields, and the account's number. */
    private static final Set<String> NEVER = Set.of("confidence", "model", "whatsapp_number", "phone", "status_reason", "plan");

    private final ObjectMapper json = new ObjectMapper();

    /** A fictional number in the fixture range, unique per call. */
    private static String number() {
        return "+88017000003" + String.format("%02d", NUMBERS.incrementAndGet());
    }

    @Test
    void theNameFormsAreNameGrammarsAndOnlyThePatientWithAStoredNowPaperIsUrgent() {
        AppApiContext.runner(POSTGRES).run(context -> {
            FamilyAccount family = AppApiContext.family(context, number(), "Tanvir");
            PatientProfile rahim = AppApiContext.patient(context, family.id(), "রহিম", 41, PatientProfile.Sex.MALE);
            PatientProfile amma = AppApiContext.patient(context, family.id(), "আম্মা", 64, PatientProfile.Sex.FEMALE);
            // a sibling's patient shared with this family's number: visible, not owned, nothing on file
            FamilyAccount sibling = AppApiContext.family(context, number(), "Karim");
            PatientProfile nana = AppApiContext.patient(context, sibling.id(), "নানা", 83, PatientProfile.Sex.OTHER);
            AppApiContext.share(context, nana, family.whatsappNumber());
            // the dangerous direction: THIS_WEEK is a stored urgent level too, and must not light the red mark
            UUID rahimsPaper = AppApiContext.paper(context, POSTGRES, rahim, "this_week");
            AppApiContext.storedPage(POSTGRES, rahim, rahimsPaper);
            AppApiContext.paper(context, POSTGRES, amma, "now");
            String token = AppApiContext.session(context, family.id());

            JsonNode me = me(client(context), token);
            Map<String, JsonNode> byId = patients(me);
            assertThat(byId).containsOnlyKeys(rahim.id().toString(), amma.id().toString(), nana.id().toString());

            // every summary field, against TimelineUseCase.me for that patient id: a swap between rows or a constant fails
            Map<UUID, PatientSummary> expected = new HashMap<>();
            context.getBean(TimelineUseCase.class).me(family.id()).block().patients().forEach(p -> expected.put(p.patient().id(), p));
            for (PatientProfile p : List.of(rahim, amma, nana)) {
                JsonNode row = byId.get(p.id().toString());
                PatientSummary want = expected.get(p.id());
                assertThat(row.get("age").asInt()).as("age of " + p.name()).isEqualTo(want.patient().age());
                assertThat(row.get("sex").asText()).as("sex of " + p.name()).isEqualTo(want.patient().sex().dbValue());
                assertThat(row.get("owner").asBoolean()).as("owner of " + p.name()).isEqualTo(want.owner());
                assertThat(row.get("documents").asLong()).as("documents of " + p.name()).isEqualTo(want.documents());
                assertThat(row.get("last_document_at").isNull() ? null : row.get("last_document_at").asText())
                        .as("last_document_at of " + p.name())
                        .isEqualTo(want.lastDocumentAt() == null ? null : want.lastDocumentAt().toString());
            }
            // and the fixture differs where it must, so the comparisons above could tell two patients apart
            assertThat(expected.get(rahim.id()).documents()).isEqualTo(1);
            assertThat(expected.get(amma.id()).documents()).isZero();
            assertThat(expected.get(nana.id()).owner()).isFalse();
            assertThat(expected.get(rahim.id()).owner()).isTrue();
            assertThat(expected.get(nana.id()).lastDocumentAt()).isNull();
            assertThat(expected.get(amma.id()).lastDocumentAt()).isNotNull();
            assertThat(List.of(rahim.age(), amma.age(), nana.age())).doesNotHaveDuplicates();

            for (PatientProfile p : List.of(rahim, amma)) {
                JsonNode row = byId.get(p.id().toString());
                assertThat(row.get("name").asText()).isEqualTo(p.name());
                assertThat(row.get("name_possessive").asText()).isEqualTo(NameGrammar.possessive(p.name()));
                assertThat(row.get("name_objective").asText()).isEqualTo(NameGrammar.objective(p.name()));
            }
            // the two names take different endings, so a handler that used one rule for both fails above
            assertThat(NameGrammar.possessive(rahim.name()).substring(rahim.name().length()))
                    .isNotEqualTo(NameGrammar.possessive(amma.name()).substring(amma.name().length()));
            assertThat(byId.get(amma.id().toString()).get("urgent").asBoolean()).as("Amma has a stored NOW paper").isTrue();
            assertThat(byId.get(rahim.id().toString()).get("urgent").isBoolean()).isTrue();
            assertThat(byId.get(rahim.id().toString()).get("urgent").asBoolean()).as("Rahim's paper is THIS_WEEK only").isFalse();
            assertThat(me.get("family").get("id").asText()).isEqualTo(family.id().toString());
            assertThat(me.get("family").get("owner_name").asText()).isEqualTo("Tanvir");
            assertThat(keys(me)).as("field names anywhere in the /me tree").doesNotContainAnyElementsOf(NEVER);
            // the dangerous direction is the value, not the key: no field of any name carries the family's number
            assertThat(carryingTheNumber(me, family.whatsappNumber())).as("values in the /me tree carrying the number").isEmpty();
            assertThat(carryingTheNumber(json.readTree("{\"contact\":\"" + phoneDisplay(family.whatsappNumber()) + "\"}"),
                    family.whatsappNumber())).as("the value check can fail").isNotEmpty();
        });
    }

    /** A read is a read: GET /me never writes the active-patient cookie (only PUT does, plan B1), even with one patient in view. */
    @Test
    void readingMeSetsNoActivePatientCookie() {
        AppApiContext.runner(POSTGRES).run(context -> {
            FamilyAccount family = AppApiContext.family(context, number(), "Tanvir");
            PatientProfile only = AppApiContext.patient(context, family.id(), "আম্মা");
            String token = AppApiContext.session(context, family.id());
            WebTestClient client = client(context);
            for (boolean remembered : List.of(false, true)) {
                var spec = client.get().uri("/app/api/me").cookie("baymax_session", token);
                if (remembered) {
                    spec = spec.cookie("baymax_patient", only.id().toString());
                }
                var result = spec.exchange().expectStatus().isOk().expectBody(String.class).returnResult();
                assertThat(json.readTree(result.getResponseBody()).get("active_patient_id").asText())
                        .as("the only patient is in view").isEqualTo(only.id().toString());
                assertThat(result.getResponseCookies().getFirst("baymax_patient")).as("Set-Cookie on GET /me").isNull();
            }
        });
    }

    /** Every textual value (and its digits alone) that carries the number in any rendering a page or a log would use. */
    private static List<String> carryingTheNumber(JsonNode tree, String e164) throws Exception {
        String digits = e164.substring(1);
        String local = "0" + e164.substring(4);
        List<String> forms = List.of(e164, digits, local, phoneDisplay(e164));
        List<String> hits = new ArrayList<>();
        values(tree, (path, value) -> {
            String valueDigits = value.replaceAll("[^0-9]", "");
            if (forms.stream().anyMatch(value::contains) || valueDigits.contains(local.substring(1))) {
                hits.add(path);
            }
        }, "$");
        return hits;
    }

    /** {@code PhoneDisplay.full}, the rendering the sign-in page shows back, read from the class itself. */
    private static String phoneDisplay(String e164) throws Exception {
        var full = Class.forName("com.elioo.baymax.web.adapter.in.ui.PhoneDisplay").getDeclaredMethod("full", String.class);
        full.setAccessible(true);
        return (String) full.invoke(null, e164);
    }

    private static void values(JsonNode node, java.util.function.BiConsumer<String, String> visit, String path) {
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> values(e.getValue(), visit, path + "." + e.getKey()));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                values(node.get(i), visit, path + "[" + i + "]");
            }
        } else if (node.isValueNode() && !node.isNull()) {
            visit.accept(path, node.asText());
        }
    }

    @Test
    void theActivePatientIsNullWithoutTheCookieAndThePatientAfterPut() {
        AppApiContext.runner(POSTGRES).run(context -> {
            FamilyAccount family = AppApiContext.family(context, number(), "Tanvir");
            AppApiContext.patient(context, family.id(), "রহিম");
            PatientProfile amma = AppApiContext.patient(context, family.id(), "আম্মা");
            String token = AppApiContext.session(context, family.id());
            WebTestClient client = client(context);

            assertThat(me(client, token).get("active_patient_id").isNull()).as("two patients and no choice: Medioo never guesses").isTrue();

            ResponseCookie chosen = client.put().uri("/app/api/active-patient").cookie("baymax_session", token)
                    .header("X-Medioo", "1").header("Origin", AppApiContext.ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("patient_id", amma.id().toString()))
                    .exchange().expectStatus().isNoContent()
                    .returnResult(Void.class).getResponseCookies().getFirst("baymax_patient");
            assertThat(chosen).isNotNull();
            assertThat(chosen.getValue()).isEqualTo(amma.id().toString());
            assertThat(chosen.getPath()).isEqualTo("/app");
            assertThat(chosen.isHttpOnly()).isTrue();

            JsonNode after = json.readTree(client.get().uri("/app/api/me").cookie("baymax_session", token)
                    .cookie("baymax_patient", chosen.getValue()).exchange().expectStatus().isOk()
                    .expectBody(String.class).returnResult().getResponseBody());
            assertThat(after.get("active_patient_id").asText()).isEqualTo(amma.id().toString());
        });
    }

    /** A patient of another family is the same 404 as one that does not exist, and sets no cookie. */
    @Test
    void choosingAnotherFamilysPatientIsNotFoundAndSetsNoCookie() {
        AppApiContext.runner(POSTGRES).run(context -> {
            FamilyAccount mine = AppApiContext.family(context, number(), "Tanvir");
            AppApiContext.patient(context, mine.id(), "রহিম");
            FamilyAccount other = AppApiContext.family(context, number(), "Karim");
            PatientProfile theirs = AppApiContext.patient(context, other.id(), "আম্মা");
            String token = AppApiContext.session(context, mine.id());
            WebTestClient client = client(context);
            for (String id : List.of(theirs.id().toString(), UUID.randomUUID().toString(), "not-a-uuid")) {
                var result = client.put().uri("/app/api/active-patient").cookie("baymax_session", token)
                        .header("X-Medioo", "1").header("Origin", AppApiContext.ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("patient_id", id))
                        .exchange().expectStatus().isNotFound()
                        .expectBody().json("{\"reason\":\"NOT_FOUND\"}", true).returnResult();
                assertThat(result.getResponseCookies().getFirst("baymax_patient")).as("no cookie for " + id).isNull();
            }
            client.put().uri("/app/api/active-patient").cookie("baymax_session", token)
                    .header("X-Medioo", "1").header("Origin", AppApiContext.ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("other", "x"))
                    .exchange().expectStatus().isBadRequest()
                    .expectBody().json("{\"reason\":\"INVALID\"}", true);
        });
    }

    /**
     * Preview in both directions: the allowlisted family sees true; a family not on it sends a forged
     * {@code medioo_preview=1} cookie (and the same as a header and a query) and still sees false.
     */
    @Test
    void previewIsTrueOnlyForAnAllowlistedFamilyAndNoCookieCanTurnItOn() {
        AppApiContext.runner(POSTGRES).run(context -> {
            FamilyAccount listed = AppApiContext.family(context, number(), "Tanvir");
            FamilyAccount notListed = AppApiContext.family(context, number(), "Karim");
            // bound from BAYMAX_WEB_PREVIEW_FAMILIES in production (AppApiSettingsTest, and medscribe-ai's
            // AppApiPropertiesWiringTest for the env line); set on the live bean here because the family id exists only
            // after the context has started
            context.getBean(BaymaxProperties.class).getWeb().getPreviewFamilyIds().add(listed.id().toString());
            WebTestClient client = client(context);

            assertThat(me(client, AppApiContext.session(context, listed.id())).get("preview").asBoolean()).isTrue();

            JsonNode forged = json.readTree(client.get().uri("/app/api/me?preview=1&medioo_preview=1")
                    .cookie("baymax_session", AppApiContext.session(context, notListed.id()))
                    .cookie("medioo_preview", "1").header("X-Medioo-Preview", "1")
                    .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody());
            assertThat(forged.get("preview").isBoolean()).isTrue();
            assertThat(forged.get("preview").asBoolean()).as("a family not on the allowlist, with a forged cookie").isFalse();
        });
    }

    private static WebTestClient client(ApplicationContext context) {
        return WebTestClient.bindToApplicationContext(context).build();
    }

    private JsonNode me(WebTestClient client, String token) throws Exception {
        return json.readTree(client.get().uri("/app/api/me").cookie("baymax_session", token).exchange()
                .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody());
    }

    private static Map<String, JsonNode> patients(JsonNode me) {
        Map<String, JsonNode> byId = new HashMap<>();
        me.get("patients").forEach(p -> byId.put(p.get("id").asText(), p));
        return byId;
    }

    /** Every field name in the tree, at any depth. */
    private static List<String> keys(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                out.add(e.getKey());
                out.addAll(keys(e.getValue()));
            }
        } else if (node.isArray()) {
            node.forEach(n -> out.addAll(keys(n)));
        }
        return out;
    }
}
