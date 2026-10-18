# Medioo roadmap — Phase 0 to Phase 7

**Date:** 2026-10-17. **Read at:** `main` @ `e4494c1`, read-only. Paths are repo-relative.

**Goal.** Medioo becomes the family's health companion: one brain behind the web app and WhatsApp that always answers,
files every paper, says how urgent it is in fixed words, and keeps watch. Code decides urgency, numbers, medicines,
patient identity and refusals. The model only words over facts the code has already checked. **Every phase ends with
something Tanvir does on his own phone in production**; a phase is not done until he has.

**Approved by the PO (Tanvir), 2026-10-17 — "I agree all":**

| # | Decision | What it means for the build |
|---|---|---|
| D1 | The companion (chat) is core MVP scope | DR-37. Phases 3 and 6 are in scope; push (nudges) stays the moat. |
| D2 | Inbound-first login code | The family texts "কোড" on WhatsApp; `issueCodeFor(sender)` mints a code for the number WhatsApp proved. The web mints nothing. Phase 1a. |
| D4 | "What is this medicine for?" stays refused | DR-16 unchanged. Nothing in any phase answers a medicine-purpose question. |
| D5 | Urgency shows at once, before any review | The code-computed urgency line goes out immediately on every surface, in every gate state. A PENDING **body** is still never delivered. Amends DR-13's wording. |
| D8 | Code answers unlimited; 6 model-worded answers per family-month | Phase 6 meters model wording on what was **sent**, not on a counter. |
| D13 | Voice notes: fixed reply plus the red-flag line now, speech-to-text later | Phase 0 (B1a); STT in Phase 7. |

**Standing PO direction (2026-10-16): fully automated first.** The pipeline must work well with no human in the loop.
Production stays `BAYMAX_GATE_MODE=off`. The reviewer workflow and portal come later. Every gate-sensitive path is
still tested in both gate modes, and the urgency-mode behaviour is proven by tests only; it is never tried by flipping
the gate in production. The gate decision before real families is open (below).

**Read with:**
- Spec: [`docs/superpowers/specs/2026-10-17-medioo-companion-experience-design.md`](../specs/2026-10-17-medioo-companion-experience-design.md)
  (sections that drive the build: §2 Foundations F1–F10, §6 WhatsApp journey W1–W23, §7 companion, §11 decisions, §12 phasing).
- Design artifacts: the **Medioo Experience** canvas, https://claude.ai/artifact/X9xDXZEL2FgU5hv5bRQT2i, and the
  **Medioo Design System**, https://claude.ai/artifact/F7Kj5uHkfv4nmPWVrDhimZ (tokens v3 are also in the repo beside the
  spec: `docs/superpowers/specs/2026-10-17-medioo-tokens-v3.json`).
- The bite-sized plan for Phase 0 and Phase 1a: [`2026-10-17-medioo-phase0-1a.md`](2026-10-17-medioo-phase0-1a.md).

---

## Rules that apply to every phase

- **One PR per work item**, on `feat/...` or `fix/...`, merged green; **every merge deploys**. Stage files by explicit
  path, never `git add -A` or `git add .`. Before pushing a PR that adds directories,
  `git status --ignored --short -- baymax/src medscribe-ai/src | grep '^!!'` must print nothing.
- **Branch protection is not enforced.** The repo is private and `gh api repos/{owner}/{repo}/branches/main/protection`
  answers 403 "Upgrade to GitHub Pro or make this repository public" (checked 2026-10-17). CLAUDE.md's "main is
  protected; Build and test must pass" is therefore a convention, not a gate: two separately green PRs that clash
  (migration numbers, constructors) can both merge, and main goes red. Every PR runs the pre-merge step:
  `env -u GITHUB_TOKEN gh pr view <n> --json mergeStateStatus` must be `CLEAN` (rebased on current main) and
  `env -u GITHUB_TOKEN gh pr checks <n> --watch` green. Deploy `needs: build`, so a red main does not reach production,
  but it blocks every later deploy. PR 0 proposes the CLAUDE.md correction; Tanvir decides.
- **TDD.** When a rule has two directions, write both tests first. Each regression guard is replayed against the pre-fix
  source and must go red **on the defect**: read the message (`expected: DONE but was: NEEDS_RETAKE`, not an NPE or a
  compile error). A replay that needs files the fix does not touch is run in a scratch worktree at `origin/main` with
  only the guard file copied in, and the output is pasted into the PR body.
- **Anything with a DB constraint or trigger gets a Testcontainers test** (copy `runner()` from
  `baymax/src/test/java/com/elioo/baymax/nudge/adapter/out/persistence/PostgresNudgeAdapterTest.java:46-60`).
- **Limits are asserted on the final text actually sent**, after every insertion: capture the `sendText` argument, never
  rebuild the string in the test.
- **Logs carry ids, sizes, digests and machine codes; never patient text, phone numbers or wamids.** A WhatsApp message
  id (wamid) is base64 of the phone number: Meta's sample `wamid.HBgLMTY1MDM4Nzk0MzkV…` decodes to `16503879439`. Log
  `Redact.id(wamid)` (a short SHA-256 digest, PR 1) or the row UUID. Store a wamid only on rows deletable with the
  family. Test fixtures use a **realistic** wamid built from base64 (`WaFixtures.wamid(...)`, PR 1), so a leak is
  visible to the log-capture tests.
- **Test numbers.** `+8801700000000` (and `+88017000007xx` / `+88017000008xx` for extra families) only. The
  maintainer's personal number was purged from history (`docs/HISTORY_REWRITE.md:28,83-85`) and must not return; PR 1
  adds a guard test over `baymax/src/test` and `medscribe-ai/src/test`.
- **Reactive end to end**, `.block()` only in tests. Constructor injection through `@RequiredArgsConstructor`; a PR that
  adds a field appends it last and updates every `new X(` call site in the same commit.
- **Migrations.** Never edit an applied one. Numbers are allocated here, once; each PR's preflight
  `git ls-tree --name-only origin/main baymax/src/main/resources/db/baymax/migration | sort -V | tail -1` is a check that
  the predecessor in this table is the latest on main, not the allocator. If it is not, stop and re-plan; do not
  renumber silently.

  | Version | PR | Content |
  |---|---|---|
  | V15 | (applied) | `crop_verify_call_purpose` |
  | **V16** | Phase 0, PR 4 (C2) | `document_recovery_attempts` (F9b) |
  | **V17** | Phase 1a, PR 12 (1a-ii) | `wa_last_inbound` (PO 2026-10-18, DR-39 amended: the free sign-in window); PR 12 merged ahead of PRs 7–10 |
  | **V18** | Phase 0, PR 8 (C1) | `inbound_message` (F9a) — was V17 until 2026-10-18 |
  | **V19** | Phase 0, PR 9 (B2) | `outbound_failure_kind` — was V18 until 2026-10-18 |
  | **V20** | Phase 1b.1–1b.2 | `document_urgency_and_value_verdict` (F1 + F2) |
  | **V21** | Phase 1b.3 | `retake_reason_code_and_timeline_date` (F5) |
  | V22+ | Phase 2 onward | the next free number when written; the phase sections say "next free `V<n>`" |

  The spec's §2 and §12 still say "V16" for F1/F5; that label predates this allocation and is superseded by it.
- **A table added in any phase goes into both delete paths** (`PostgresHealthRecordAdapter.deleteFamily/deletePatient`).
  From PR 8 on, `FamilyDeletionAcceptanceTest` derives its targets from the schema (FKs to `family_account`,
  `patient_profile` and `share_member`, plus the column-name sweep) and refuses any target column the fixture left
  empty. The PR that adds a table adds its fixture row; the test fails until it does.
- **Synthetic documents only.** Every try uses fictional documents (DR-36) generated outside the repo (plan Task P.2).
  Until Phase 5's entry criteria are met, every try uses Tanvir's number alone (`BAYMAX_WA_ALLOWLIST`).
- **Done = Tanvir has run the phase's Try it line** and the build report says so (`BMX-<n> report: Done / Deviations /
  Open questions / Cost & metrics / Next`). Every `~/medscribe.env` change is backed up first and listed in the report.

---

## Phases at a glance

| Phase | Goal | Try it (Tanvir's phone, production) | PRs | Size | Entry criteria | Depends on |
|---|---|---|---|---|---|---|
| **0** Medioo never ignores me | Every WhatsApp message gets exactly one reply; every document ends with a reply; the detail arrives once; live defects fixed (D35, F4, prescriptions retaken, credential and number leaks, lost documents on deploy, re-delivered messages) | "Hi", "kemon acho", "details", a voice note, a sticker, a DOCX, a PDF, a photo, "আজ বৃষ্টি হবে?", the same message twice (replay script), a photo across a restart: each answered once | 11 (PR 0–10) | M–L | Preflight done (free-tier headroom, one patient, WA env, try documents) | — |
| **1a** I can sign in | Inbound-first login (D2): type the number any way, text "কোড", type the code in Latin or Bangla digits | Open medioo.eliooo.org/app, type `০১৭…`, tap "WhatsApp খুলে কোড আনুন", Send, type the code in Bangla digits, see Ma's page | 2 (PR 11–12) | S–M | Phase 0 merged and tried; `BAYMAX_WA_PUBLIC_NUMBER` set **before** PR 12 merges | Phase 0 (router, `Wa.permits`, log hygiene) |
| **1b** Foundations and the look | Stored urgency that only rises (F1), per-value verdicts (F2), retake reason codes (F5), tokens v3, dark mode, Baymax CSP, bottom tabs, কাগজপত্র with urgency chips, Meta templates submitted | কাগজপত্র shows red/amber chips and nothing on routine papers; dark mode; English toggle; templates "In review"; page weight < 200 KB in the report | 9 | L | D12, D22, D27, D28 short forms, D15 confirmed; DR-39 sign-in recorded | Phase 0 (F4 split, `DocumentProcessingChain`), 1a |
| **2** The result is clear and checkable | Result screens in every gate state (F3), country-aware NOW action (F7), retake button, crop-first detail, value sheet, honest reading progress, DR-25 purge | Upload a synthetic lab report, read the banner, tap a value's crop, retake a blurred photo, open a synthetic NOW as a +44 test viewer: no 999 button | 5–6 | L | 1b merged; D25, D31 confirmed | 1b |
| **3** It answers my questions (code only) | Companion v1 on both channels, zero model calls: values, prescription blocks, dose/purpose refusals, red flags, symptoms, help, off-topic | "আম্মার ক্রিয়েটিনিন কত?", "ওষুধটা অর্ধেক করব?", "Napa কীসের?", "আম্মার বুকে ব্যথা", "ok thanks" after a NOW: same answers on web and WhatsApp, emergency ≤ 2 s, no `chat` rows in `ai_call_log` | 6–8 | L | **D11 signed** (clinician + PO); D24; D40; D18 if help promises a person | 0, 1b, 2 |
| **4** Uploading is easy | Several pages make one document on both channels; honest progress; small uploads | A 2-page report as two WhatsApp photos and three web photos on mobile data: one document each, a progress bar, one ack | 3–4 | M | Downscale size chosen by a measured eval (paid run Tanvir approves) | 1b (CSP allows JS) |
| **5** My family can join | Self-onboarding, siblings with their own accounts and roles, per-recipient delivery, recipient time zones | Add a second test number, onboard it with "Hi", add Baba, share Ma as "কাগজ পাঠাতে পারবেন", send a photo from it, see "…একটা কাগজ পাঠিয়েছেন" on the owner's home | 6–8 | L | **Gate decision taken and recorded**; DR-23 verify token rotated; D5 pending UI shipped (Phase 2); D9, D17, D19, D29, D30, D33, D37 | 2, 3 |
| **6** It talks like a companion | Model wording over code-built references for `Kind.CHAT`, 6 per family-month (D8), every step-7 check live | Free-form Bangla and Banglish; every number checks against its crop; `# chat` in the weekly export; a timeout falls back to code | 4–5 | L | Every §7.1 step-7 check live and replayed red; D3 if glossary in scope; gate decision revisited | 3, 5 |
| **7** It keeps watch | Trends, reminders in the recipient's day, approved templates outside 24 h, help routing, STT, signed links, demo | A follow-up reminder template in his own daytime, tap it, ask about the HbA1c trend, open the demo on a friend's phone | 5–6 | M–L | Templates approved (submitted in 1b); D13 STT provider under HC6; D18, D31, D38; **nudge-gate decision** | 1b, 5, 6 |

Sizes: S ≈ a day, M ≈ 2–4 days, L ≈ a week or more of focused work, each including review and the phone try.

---

## Phase 0 and Phase 1a: the PR sequence

The full tasks, tests and try checklists are in the plan file. The order is fixed: **each PR is rebased on the merged
one before it, and its snippets are rewritten against merged main**, not against `e4494c1`. No PR carries a "skip
this step if X merged first" conditional. Every one of PRs 1–10 touches `WaIntakeService`, `ExplanationService` or
`WaWebhookHandler`, which is why they are serial.

Two changes from the spec's §12 text: the "কোড" rule and `issueCodeFor` move from Phase 0 to Phase 1a (PR 12), because
the web must stop minting codes in the same deploy that the WhatsApp path opens, so "কোড" is not part of the Phase 0
try; and the Graph v21 check becomes the v25.0 bump in PR 10. Until PR 12, "কোড" gets the honest fallback (a reply).

| # | PR | Branch | What it ships | Migration | Base | Try (short) |
|---|---|---|---|---|---|---|
| — | **Preflight** (no PR) | — | Tanvir: free-tier headroom, one patient, WA env checks, gate off, try documents made | — | — | — |
| 0 | Docs | `docs/medioo-companion-design` | Spec, roadmap, plan into `docs/superpowers`; DR-37 (D1 + D4/D8/D13), DR-39 (D2/D36/D37), DR-13 amendment (D5); proposed CLAUDE.md correction | — | main | Open the docs on GitHub mobile |
| 1 | **L** log hygiene | `fix/no-secrets-in-logs` | Header redaction (`IWebFilter`), no medicine text or signed URL in error logs, wamid digests, fixture-number scrub + guard, RUNBOOK WA block | — | 0 | Sign in as today; `grep -c 'baymax_session='` stays flat |
| 2 | **A1** D35 | `fix/baymax-normal-range-claim` | "Within the normal range" only when every value is INSIDE by code | — | 1 | One-sided lipid report: filed, not "সব কিছু স্বাভাবিক" |
| 3 | **RX** prescriptions | `fix/baymax-prescription-follow-up-gate` | A prescription with no follow-up is DONE, not a retake; replay harness models the deployed gate | — | 1 (parallel with 2: no shared files) | A prescription with no follow-up is filed |
| 4 | **C2** F9b recovery | `feat/baymax-document-recovery` | `DocumentProcessingChain`; interrupted documents re-run after restart, bounded; unexplained recent documents explained | **V16** | 2 + 3 | Photo, ack, restart: the result arrives once |
| 5 | **A2** F4 | `fix/baymax-detail-compose-release` | compose/release/deliver split, DETAIL cache, atomic delivery claim, central gate guard, fixed copy for every gate state, `UrgencyLines`, bundle parity | — | 4 | "বিস্তারিত" once; again: "আগেই পাঠানো হয়েছে" + link |
| 6 | **B1a** never silent | `feat/baymax-wa-every-message-answered` | Text router, `WaInbound` with every Meta type, greeting/thanks/fallback with red-flag line, voice/video/sticker/reaction/DOCX/PDF replies | — | 5 | The whole try table except failures |
| 7 | **B1b** urgency and refusals | `feat/baymax-wa-greeting-urgency` | Greeting/thanks restate a standing NOW/THIS_WEEK in any gate state; free tier checked before the ack; failures after the ack are told | — | 6 | "Hi" after a THIS_WEEK paper leads with it |
| 8 | **C1** F9a idempotency | `fix/baymax-wa-inbound-idempotency` | `inbound_message` keyed by HMAC(wamid), claim before 200, 503 on failure, both delete paths, schema-derived deletion sweep, replay script, unanswered-count WARN | **V18** | 7 | `scripts/wa-replay-callback.sh`: one reply, one `wa inbound duplicate` |
| 9 | **B2** failure notices | `fix/baymax-every-document-ends-with-a-reply` | `Kind.FAILURE`; FAILED, fail-closed and throwing explanations reply, from one central point every FAILED writer meets | **V19** | 8 | Nothing regressed (re-run PR 6/7 tries); failure paths proven locally |
| 10 | **C3** Graph v25 + read receipts | `feat/baymax-wa-graph-v25-read-receipts` | Graph v25.0 (v21.0 expires 2027-01-21), `Wa.permits` (one allowlist rule), read ticks and typing behind `BAYMAX_WA_READ_RECEIPTS` | — | 9 | With `typing`: blue ticks + dots on a photo |
| 11 | **1a-i** numbers and digits | `fix/baymax-number-and-bangla-code` | `normalizeNumber` (00cc, trunk zero, NBSP, en dash); a code typed in Bangla digits is accepted | — | 10 | Sign in as today, type the code in Bangla digits |
| 12 | **1a-ii** sign-in by WhatsApp code, free (DR-39 amended 2026-10-18) | `feat/baymax-whatsapp-sign-in` | `Wa.permits` (Task 10.1 first), `issueCodeFor`, the automatic in-window send from the web, composite OTP delivery (no code above DEBUG, never a template), "কোড" route, sign-in pages ("sent" only when Graph accepted it), validator | **V17** | 11; **env set first** | The full Phase 1a try |

---

## Phase 1b: Foundations and the look (L)

**Goal:** urgency is stored once and only rises; every value row has a code verdict; retakes carry a reason code; the
web gets tokens v3, dark mode, a Baymax CSP, bottom tabs and a কাগজপত্র timeline with urgency chips; the Meta templates
are submitted.

**Try it:** sign in at medioo.eliooo.org/app on his phone. Open কাগজপত্র and see Ma's papers newest-document-date
first: a red chip on a NOW paper, an amber chip on a THIS_WEEK paper, nothing on routine ones. Switch the phone to dark
mode and toggle English. Open WhatsApp Manager and see the five templates "In review" or "Approved". The build report
states first-load page weight under 200 KB.

**Entry:** Phase 0 and Phase 1a merged and tried. DR-39 recorded. Confirm at phase start: D12 (Baymax-path CSP), D22
(tokens v3), D27 (no ROUTINE chip), D28 short forms `urgency.now.short` / `urgency.this_week.short` (copy approval),
D15 (template set). No doctor sign-off needed.

**Depends on:** Phase 0 (F4 `composeChecked`/`release`/`deliver`; `DocumentProcessingChain` from PR 4, which is where
the F1 writer hooks in; the JSON encoding of `urgency_reasons` from PR 5), Phase 1a (sign-in).

### File map

| Area | Create | Modify |
|---|---|---|
| migration | `V20__document_urgency_and_value_verdict.sql`, `V21__retake_reason_code_and_timeline_date.sql` | — |
| domain | `outbound/domain/ValueVerdict.java`, `outbound/domain/StoredUrgency.java`, `extraction/domain/RetakeReason.java` | `outbound/domain/UrgencyAssessment.java` (add `values`), `extraction/domain/Document.java` (add `statusReasonCode`) |
| port/service | `outbound/application/port/out/DocumentUrgencyPort.java`, `outbound/application/service/StoredUrgencyService.java` | `UrgencyService.java:58-124` (emit verdicts), `ExplanationService` (compose at the stored level), `extraction/application/service/DocumentProcessingChain.java` (assess and store at extraction finish, before and independent of explain), `DocumentExtractionService.java:80,89,242,251,257,520-547`, `web/application/service/TimelineService.java:65-84` |
| adapter | `outbound/adapter/out/persistence/PostgresDocumentUrgencyAdapter.java`, `web/adapter/in/BaymaxSecurityHeadersFilter.java` | `PostgresDocumentRecordAdapter.java:74-95,282-286`, `ObservationEntity.java:45-56` (add `id`) |
| web UI | `baymax/ui/noto-sans-bengali-subset.woff2`, `baymax/ui/noto-sans-latin.woff2` (OFL) | `baymax.css` (v3), `Html.java:74` (viewport, tabs), `WebUiHandler.java:63,191-212,287-369`, `BaymaxWebUiRouter.java:60-74`, `web/domain/TimelineEntry.java` |
| copy | — | `ui_bn.properties`, `ui_en.properties`, `messages_bn.properties` (`urgency.*.short`, glossary sweep) |
| WhatsApp | `baymax/src/main/resources/baymax/wa-templates/*.json` | — |
| tests | see each task | `WebUiHandlerRenderTest.java:31-38,52-62`, `HexagonalArchitectureTest.java`, `DesignTokensTest.java` |

(Java paths are under `baymax/src/main/java/com/elioo/baymax/` unless they start with a module name. Line numbers are
from `e4494c1`; re-read them after Phase 0 has merged.)

### 1b.1 F1: stored urgency, a no-lowering trigger, one central writer (M)

- [ ] **Files.** Create `V20__document_urgency_and_value_verdict.sql`, `DocumentUrgencyPort`, `StoredUrgency`,
  `StoredUrgencyService`, `PostgresDocumentUrgencyAdapter`, and the test
  `baymax/src/test/java/com/elioo/baymax/outbound/adapter/out/persistence/DocumentUrgencyNeverLowersTest.java`.
  Modify `ExplanationService` (compose at the stored max), `DocumentProcessingChain.run` (assess and store at extraction
  finish, so a failing explanation no longer leaves the document unassessed), the nudge job's assessment call, and
  `HexagonalArchitectureTest`.
- [ ] **Step 1: failing tests.** Testcontainers, `runner()` copied from `PostgresNudgeAdapterTest`:

```java
/**
 * F1 on real Postgres: a document's urgency only rises. The rule lives in a trigger so that no writer, present or
 * future, can store a lower level — the V14 lesson. The port additionally merges as max(old, new).
 */
@Testcontainers
class DocumentUrgencyNeverLowersTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final Instant T = Instant.parse("2026-10-20T08:00:00Z");

    @Test
    void aDirectUpdateThatLowersUrgencyIsRejectedByTheDatabase() {
        runner().run(context -> {
            UUID documentId = newDocument(context);
            context.getBean(DocumentUrgencyPort.class).raise(documentId,
                    new UrgencyAssessment(Urgency.THIS_WEEK, List.of("value_outside_range:ldl|stopgap")), T).block();

            assertThatThrownBy(() -> execute("UPDATE baymax.document SET urgency = 'routine' WHERE id = '" + documentId + "'"))
                    .isInstanceOf(java.sql.SQLException.class)
                    .hasMessageContaining("document urgency may not be lowered (this_week -> routine)");
            assertThatThrownBy(() -> execute("UPDATE baymax.document SET urgency = NULL WHERE id = '" + documentId + "'"))
                    .hasMessageContaining("document urgency may not be lowered");
            assertThat(scalar("SELECT urgency FROM baymax.document WHERE id = '" + documentId + "'"))
                    .isEqualTo("this_week");
        });
    }

    @Test
    void thePortKeepsTheHigherLevelAndTheUnionOfReasonsWhenALaterAssessmentIsLower() {
        runner().run(context -> {
            UUID documentId = newDocument(context);
            DocumentUrgencyPort port = context.getBean(DocumentUrgencyPort.class);
            port.raise(documentId, new UrgencyAssessment(Urgency.NOW, List.of("value_critical:potassium|stopgap")), T).block();

            StoredUrgency after = port.raise(documentId,
                    new UrgencyAssessment(Urgency.ROUTINE, List.of()), T.plusSeconds(60)).block();

            assertThat(after.level()).isEqualTo(Urgency.NOW);
            assertThat(after.reasons()).containsExactly("value_critical:potassium|stopgap");
            assertThat(after.assessedAt()).isEqualTo(T.plusSeconds(60));
        });
    }

    /** The other direction: raising, and a first assessment at any level, are allowed. */
    @Test
    void anUnassessedDocumentTakesAnyLevelAndALevelCanRise() {
        runner().run(context -> {
            UUID documentId = newDocument(context);
            DocumentUrgencyPort port = context.getBean(DocumentUrgencyPort.class);
            assertThat(port.raise(documentId, UrgencyAssessment.routine(), T).block().level()).isEqualTo(Urgency.ROUTINE);
            assertThat(port.raise(documentId, new UrgencyAssessment(Urgency.THIS_WEEK,
                    List.of("follow_up_past_due")), T).block().level()).isEqualTo(Urgency.THIS_WEEK);
            execute("UPDATE baymax.document SET urgency = 'now' WHERE id = '" + documentId + "'");   // raising is fine
        });
    }

    /**
     * The backfill, with the trigger present: urgency lived only on outbound rows until now. One document has a ROUTINE
     * and a NOW explanation (it must come out NOW, reasons from both encodings of urgency_reasons); one has none (NULL).
     */
    @Test
    void theBackfillTakesTheHighestMessageLevelAndLeavesAnUnmessagedDocumentNull() {
        runner().run(context -> {
            UUID withMessages = newDocument(context);
            UUID without = newDocument(context);
            UUID family = familyOf(withMessages);
            execute("INSERT INTO baymax.outbound_message (family_id, document_id, kind, urgency, urgency_reasons, gate_status) VALUES "
                    + "('" + family + "','" + withMessages + "','explanation','routine','','released'),"
                    + "('" + family + "','" + withMessages + "','detail','now','[\"value_critical:cholesterol, total|stopgap\"]','released')");

            execute(backfillSqlFromTheMigration());   // read V20's UPDATE block from the classpath, run it again

            assertThat(scalar("SELECT urgency FROM baymax.document WHERE id = '" + withMessages + "'")).isEqualTo("now");
            assertThat(scalar("SELECT urgency_reasons::text FROM baymax.document WHERE id = '" + withMessages + "'"))
                    .isEqualTo("[\"value_critical:cholesterol, total|stopgap\"]");
            assertThat(scalar("SELECT coalesce(urgency, '<null>') FROM baymax.document WHERE id = '" + without + "'"))
                    .isEqualTo("<null>");
        });
    }

    private UUID newDocument(org.springframework.context.ApplicationContext context) {
        HealthRecordPort records = context.getBean(HealthRecordPort.class);
        FamilyAccount family = records.createFamily(
                new FamilyAccount(null, "+88017000006" + String.format("%02d", COUNTER.incrementAndGet()), "Tanvir",
                        FamilyAccount.Plan.FREE, T, T)).block();
        PatientProfile patient = records.createPatient(
                new PatientProfile(null, family.id(), "Ma", 68, PatientProfile.Sex.FEMALE, List.of(), T, T)).block();
        return context.getBean(DocumentRecordPort.class)
                .create(Document.received(patient.id(), family.id(), 1, T)).block().id();
    }

    private static final java.util.concurrent.atomic.AtomicInteger COUNTER = new java.util.concurrent.atomic.AtomicInteger();
    // execute(sql), scalar(sql), familyOf(documentId), backfillSqlFromTheMigration(): JDBC helpers as in PostgresNudgeAdapterTest
}
```

  The central-writer guard, added to `HexagonalArchitectureTest`:

```java
@ArchTest
static final ArchRule urgencyIsAssessedOnlyWhereItIsStored = noClasses()
        .that().doNotHaveSimpleName("StoredUrgencyService").and().doNotHaveSimpleName("UrgencyService")
        .should().callMethod(UrgencyService.class, "assess", DocumentFacts.class)
        .orShould().callMethod(UrgencyService.class, "assess", DocumentFacts.class, UrgencyContext.class)
        .because("F1: every assessment goes through the one writer that stores max(old, new)");
```

- [ ] **Step 2: expected red, on the defect.** The ArchUnit rule fails on the pre-fix source with
  `Method <...ExplanationService.compose(...)> calls method <...UrgencyService.assess(DocumentFacts)>`: a path
  computes urgency and stores it nowhere. For the trigger test, replay with the `CREATE TRIGGER` statement removed
  locally: it must fail with `Expecting code to raise a throwable` at the `'routine'` UPDATE (the lowering went
  through). For the port test, replay with a naive `SET urgency = :level`: it must fail with
  `R2dbcDataIntegrityViolationException ... document urgency may not be lowered (now -> routine)`. A "column does not
  exist" failure is **not** a valid replay. The backfill test, replayed with the comma-split read of
  `urgency_reasons`, must fail with `expected: "[\"value_critical:cholesterol, total|stopgap\"]" but was: "[\"value_critical:cholesterol\", \" total|stopgap\"]"`.
- [ ] **Step 3: implementation.** The V20 urgency part:

```sql
-- F1: a document's urgency, written by one writer, only ever raised. Every surface reads this column.
ALTER TABLE ${flyway:defaultSchema}.document
    ADD COLUMN urgency             varchar(16) NULL CHECK (urgency IN ('routine', 'this_week', 'now')),
    ADD COLUMN urgency_reasons     jsonb       NULL,   -- array of machine codes; marker names may contain commas
    ADD COLUMN urgency_assessed_at timestamptz NULL;

CREATE FUNCTION ${flyway:defaultSchema}.urgency_rank(u varchar) RETURNS int LANGUAGE sql IMMUTABLE
    AS $$ SELECT array_position(ARRAY['routine', 'this_week', 'now']::varchar[], u) $$;

CREATE FUNCTION ${flyway:defaultSchema}.document_urgency_never_lowers() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.urgency IS NOT NULL AND (NEW.urgency IS NULL
            OR ${flyway:defaultSchema}.urgency_rank(NEW.urgency) < ${flyway:defaultSchema}.urgency_rank(OLD.urgency)) THEN
        RAISE EXCEPTION 'document urgency may not be lowered (% -> %)', OLD.urgency, coalesce(NEW.urgency, 'NULL')
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;

-- BEFORE UPDATE with no column list: a writer cannot avoid it by choosing which columns to SET
CREATE TRIGGER document_urgency_never_lowers BEFORE UPDATE ON ${flyway:defaultSchema}.document
    FOR EACH ROW EXECUTE FUNCTION ${flyway:defaultSchema}.document_urgency_never_lowers();

-- backfill: the highest level any explanation/detail ever carried. outbound_message.urgency_reasons is text holding
-- either a JSON array (written since Phase 0 PR 5) or the older comma-joined form; both are read here.
UPDATE ${flyway:defaultSchema}.document d SET urgency = m.top, urgency_reasons = m.reasons, urgency_assessed_at = now()
FROM (SELECT o.document_id,
             (ARRAY['routine', 'this_week', 'now'])[max(${flyway:defaultSchema}.urgency_rank(o.urgency))] AS top,
             coalesce(jsonb_agg(DISTINCT r.reason) FILTER (WHERE r.reason IS NOT NULL AND r.reason <> ''), '[]'::jsonb) AS reasons
      FROM ${flyway:defaultSchema}.outbound_message o
      LEFT JOIN LATERAL jsonb_array_elements_text(CASE
              WHEN o.urgency_reasons LIKE '[%' THEN o.urgency_reasons::jsonb
              WHEN coalesce(o.urgency_reasons, '') = '' THEN '[]'::jsonb
              ELSE to_jsonb(string_to_array(o.urgency_reasons, ',')) END) AS r(reason) ON true
      WHERE o.document_id IS NOT NULL AND o.kind IN ('explanation', 'detail') GROUP BY o.document_id) m
WHERE d.id = m.document_id;
```

  `PostgresDocumentUrgencyAdapter.raise` is one atomic `UPDATE ... SET urgency = CASE WHEN urgency IS NULL OR
  urgency_rank(:l) > urgency_rank(urgency) THEN :l ELSE urgency END, urgency_reasons = <jsonb set union>,
  urgency_assessed_at = :at ... RETURNING ...`, so a concurrent explain and detail cannot race.
  `StoredUrgencyService.assessAndStore(DocumentFacts)` calls `urgency.assess(facts)`, then `port.raise`, and returns
  the stored max. `ExplanationService` composes at the **stored** level; its `urgency changed after assessment`
  assertion stays, comparing against the stored level. DONE documents left with NULL urgency after the backfill are
  assessed lazily by `TimelineService` through the same writer.
- [ ] **Step 4.** `./gradlew :baymax:test`, then `./gradlew build` (Docker Postgres). Before merge, Tanvir runs one
  read-only check that the Flyway role owns `baymax.document` (trigger creation needs ownership):
  `SELECT tableowner FROM pg_tables WHERE schemaname='baymax' AND tablename='document'`.
- [ ] **Step 5.** `feat(baymax): F1 stored urgency that only rises, asserted by the database`.

### 1b.2 F2: per-value verdict from `assess()` (M)

- [ ] **Files.** Create `outbound/domain/ValueVerdict.java`
  (`record ValueVerdict(UUID observationId, Kind verdict, Urgency level, String reason)`, with
  `enum Kind { INSIDE, UNKNOWN, OUTSIDE, CRITICAL }` in severity order) and
  `baymax/src/test/java/com/elioo/baymax/outbound/application/service/ValueVerdictTest.java` plus
  `baymax/src/test/java/com/elioo/baymax/outbound/adapter/out/persistence/ValueVerdictNeverSoftensTest.java`
  (Testcontainers). Modify `UrgencyAssessment` (add `List<ValueVerdict> values`, keeping a `(level, reasons)`
  constructor), `UrgencyService.java:62-76` (one verdict per shown value, from the same branch that raises),
  `ObservationEntity.toView` (add `id`), V20 (`observation.verdict varchar(8) CHECK (verdict IN
  ('inside','unknown','outside','critical'))`, `observation.verdict_reason varchar(160)`, written by `raise` in the same
  transaction), `ExplanationService.standout` (read verdicts, not `criticalKind`), and `canSayWithinNormalRange`
  (every verdict INSIDE, replacing Phase 0's `insideByCode`, which F2 reuses).
- [ ] **The verdict merge rule (defined here, asserted on Postgres).** F1 keeps `max(old, new)` for the banner, so the
  rows must not soften under it: a row's stored verdict only moves **up** the order INSIDE < UNKNOWN < OUTSIDE <
  CRITICAL, and keeps the reason of the verdict it holds. UNKNOWN outranks INSIDE because it withholds reassurance. A
  lower re-assessment changes neither row nor banner; a higher one raises both. V20 adds an
  `observation_verdict_never_softens` trigger of the same shape as F1's, so no writer can store a softer verdict.
- [ ] **Step 1: failing tests.**

```java
/** F2: a row's verdict comes from the same rule that set the banner, and "inside" must be proven. */
class ValueVerdictTest {

    private static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");
    private final BaymaxProperties properties = new BaymaxProperties();
    private final UrgencyService service = new UrgencyService(properties, Clock.fixed(NOW, ZoneOffset.UTC));

    static Map<String, Object> obs(String name, String value, String low, String high, String unit) {
        Map<String, Object> m = UrgencyServiceTest.value(name, value, low, high);
        if (low == null && high != null) { m.put("ref_high", high); }      // one-sided range
        m.put("id", UUID.randomUUID());
        m.put("canonical_name", name);
        m.put("unit", unit);
        return m;
    }

    @ParameterizedTest(name = "value ''{0}'' against ''{1}''–''{2}'' is UNKNOWN")
    @CsvSource(delimiter = '|', nullValues = "null", value = {
            "> 89     | 60   | 120",
            "130 / 80 | 90   | 140",
            "1,23     | 0.5  | 1.5",
            "Positive | null | null",
            "250      | null | 100",
            "250      | null | <100",
            "250      | null | null"})
    void aValueCodeCannotPlaceIsUnknownNeverInside(String value, String low, String high) {
        var verdict = service.assess(UrgencyServiceTest.facts(List.of(obs("ldl", value, low, high, "mg/dL")),
                List.of(), Map.of(), "{}")).values().get(0);
        assertThat(verdict.verdict()).isEqualTo(ValueVerdict.Kind.UNKNOWN);
        assertThat(verdict.level()).isEqualTo(Urgency.ROUTINE);
    }

    @Test
    void aParsedValueBetweenParsedBoundsIsInside() {
        var verdict = service.assess(UrgencyServiceTest.facts(List.of(obs("creatinine", "1.0", "0.7", "1.3", "mg/dL")),
                List.of(), Map.of(), "{}")).values().get(0);
        assertThat(verdict.verdict()).isEqualTo(ValueVerdict.Kind.INSIDE);
    }

    /** Assert the path, not the outcome: the row names the rule that produced it, and that rule set the banner. */
    @Test
    void everyRowReasonIsADocumentReasonAndAThresholdRowSaysSo() {
        properties.getOutbound().setMarkerThresholds(new java.util.ArrayList<>(List.of(new MarkerThreshold(
                "potassium", "mmol/L", 2.5, 6.0, 3.5, 5.1, false, MarkerThreshold.Direction.NORMAL,
                MarkerThreshold.Status.ACTIVE, false, "test-signed"))));
        var potassium = obs("potassium", "6.4", "3.5", "5.1", "mmol/L");   // stopgap alone: 1.25x -> OUTSIDE
        var hba1c = obs("hba1c", "6.5", "4.0", "5.6", "%");

        var a = service.assess(UrgencyServiceTest.facts(List.of(potassium, hba1c), List.of(), Map.of(), "{}"));

        assertThat(a.values()).filteredOn(v -> v.level() != Urgency.ROUTINE)
                .isNotEmpty()
                .allSatisfy(v -> assertThat(a.reasons()).contains(v.reason()));
        assertThat(a.values()).filteredOn(v -> v.observationId().equals(potassium.get("id"))).singleElement()
                .satisfies(v -> {
                    assertThat(v.verdict()).isEqualTo(ValueVerdict.Kind.CRITICAL);
                    assertThat(v.reason()).endsWith("|threshold:test-signed");
                });
        assertThat(a.values()).filteredOn(v -> v.observationId().equals(hba1c.get("id"))).singleElement()
                .satisfies(v -> assertThat(v.reason()).endsWith("|stopgap"));
    }
}
```

  `ValueVerdictNeverSoftensTest` (Testcontainers), both directions: store CRITICAL for a row, then raise with an
  assessment whose verdict for that row is INSIDE: the row stays CRITICAL with its original reason and the banner stays
  NOW. Store INSIDE, then raise with UNKNOWN: the row becomes UNKNOWN. A direct
  `UPDATE baymax.observation SET verdict = 'inside'` on a CRITICAL row raises `observation verdict may not soften`; a
  direct `SET verdict = 'bogus'` raises `observation_verdict_check`; `SET verdict = 'critical'` on an INSIDE row succeeds.
- [ ] **Step 2: expected red.** First implement `values()` the way rows are judged today: delegate to `criticalKind`
  and map NONE to INSIDE, as `canSayWithinNormalRange` did before Phase 0. That run must fail with
  `expected: UNKNOWN but was: INSIDE` for `'<100'` and `expected: CRITICAL but was: OUTSIDE` for potassium under the
  live threshold: the row and the banner disagree (`UrgencyService.java:64-71` vs `:345-362`). The Postgres test,
  replayed with the trigger removed, must fail with `expected: "critical" but was: "inside"`.
- [ ] **Step 3.** In `UrgencyService.assess`, the threshold branch and the stopgap branch each emit a verdict with the
  same reason string they raise with. NONE splits into INSIDE (`insideByCode`) and UNKNOWN (anything else). Unverified
  values (DR-28) keep raising but emit no row, because they have no observation.
- [ ] **Step 4.** `./gradlew :baymax:test` (`UrgencyServiceTest`, `ProposedThresholdsAreInertTest`,
  `WithinNormalRangeClaimTest` stay green).
- [ ] **Step 5.** `feat(baymax): F2 per-value verdicts from the rule that set the urgency, never softened`.

### 1b.3 F5: retake reason codes and the document-date timeline key (S–M)

- [ ] **Files.** Create `V21__retake_reason_code_and_timeline_date.sql`, `extraction/domain/RetakeReason.java`
  (`NO_TEXT, LOW_OVERALL, EXPECTED_SECTION_EMPTY, LOW_SECTION`, with `code(String section)`), and the tests
  `baymax/src/test/java/com/elioo/baymax/extraction/adapter/out/persistence/DocumentReasonCodeConstraintTest.java`
  (Testcontainers) and `baymax/src/test/java/com/elioo/baymax/web/adapter/in/ui/RetakeReasonsHaveCopyTest.java`.
  Modify `Document.java` (add `statusReasonCode`), the `finish(...)` calls in `DocumentExtractionService` (FAILED: code
  NULL; each retake path its code), `PostgresDocumentRecordAdapter.writeDocument`, and `DocumentView`.
- [ ] **V21:**
  `ALTER TABLE document ADD COLUMN status_reason_code varchar(48) NULL CHECK (status_reason_code ~ '^(no_text|low_overall|expected_section_empty:[a-z_]+|low_section:[a-z_]+)$')`;
  `ADD COLUMN timeline_date date GENERATED ALWAYS AS (coalesce(doc_date, (created_at AT TIME ZONE 'UTC')::date)) STORED`;
  `CREATE INDEX document_patient_timeline_idx ON document (patient_id, timeline_date DESC, created_at DESC, id DESC)`.
  The `AT TIME ZONE 'UTC'` cast keeps the expression immutable.
- [ ] **Step 1: tests.** In `DocumentExtractionServiceTest`, each of the four retake paths asserts its code, e.g.
  `assertThat(d.statusReasonCode()).isEqualTo("expected_section_empty:medicines")`, and a DONE document asserts NULL.
  `DocumentReasonCodeConstraintTest` writes `'blurry'` and expects a `check_violation`; the direction that must pass is
  `low_section:values`. `RetakeReasonsHaveCopyTest` is derived from the `RetakeReason` enum: every value has
  `retake.reason.<prefix>` in `ui_bn` and `ui_en`, and at least one value exists.
- [ ] **Step 2: expected red.** `expected: "expected_section_empty:medicines" but was: null`. The DB test, with the CHECK
  removed, fails with `Expecting code to raise a throwable`.
- [ ] **Step 3.** Set the code beside the prose. `statusReason` stays developer-facing and is never rendered. **Rewrite
  `WebUiHandlerRenderTest.aRetakeRendersTheReasonAndThePageImageOnly` (`:31-38`)**: it asserts that the developer
  English "not read confidently" reaches the family, which is a test whose name encodes the defect (CLAUDE.md). It
  becomes: the Bangla `retake.reason.low_overall` copy is shown and the page `doesNotContain("not read confidently")`.
  The full retake screen is Phase 2; here the page only swaps the prose for the code's copy.
- [ ] **Step 4.** `feat(baymax): F5 retake reason codes, and a document-date timeline key`.

### 1b.4 Tokens v3 into `baymax.css`, dark mode, contrast as a build check (M)

- [ ] **Files.** Modify `baymax/src/main/resources/baymax/ui/baymax.css` (there is no dark mode today) with the v3
  values from `docs/superpowers/specs/2026-10-17-medioo-tokens-v3.json`. Renames: `--c-accent` → `--c-primary`,
  `--c-muted` (`#566663`) → `--c-text-muted` (`#4f5f5c`), `--c-week` → `--c-status-soon`, `--c-urgent` →
  `--c-status-now`; add `--c-outline`, `--c-status-inside`, `--c-status-unknown`, `--t-*` and `--target-min`. The blue
  `status-watch` is gone. Add the dark block under
  `@media (prefers-color-scheme:dark){:root:not([data-theme=light]){...}}` plus `:root[data-theme=dark]`. Modify
  `Html.java:74` to `width=device-width, initial-scale=1, viewport-fit=cover`, never `maximum-scale`. Create
  `baymax/src/test/java/com/elioo/baymax/web/adapter/in/ui/TokenContrastTest.java`.
- [ ] **Tests.** `DesignTokensTest` stays green: no colour, radius or font-size literal in UI Java, and every
  `var(--x)` is defined. `TokenContrastTest` ports the 28 pairs × 2 themes from the design system's `check_tokens.py`.
  It parses both `:root` blocks **from the shipped `baymax.css`**, not from the JSON, so it measures what ships, and it
  asserts 56 pairs were checked so it cannot pass on an empty parse. Its failure message names the pair, the theme,
  the measured ratio and the threshold (`dark: --c-text-muted on --c-surface is 4.12:1, needs 4.5:1`). Add one rule to
  `DesignTokensTest`: `--c-status-now` and `--c-brand-dot` appear only in selectors matching
  `.now|.critical|.emergency|.brand .dot` (red is reserved, spec §8), derived from the CSS.
- [ ] **Replay (on the rule, not on a missing token).** On the finished stylesheet, change one pair below its threshold
  (for example dark `--c-text-muted` to `#6b7a77`). The test must fail with a message naming that pair and its
  measured ratio. A failure saying "token not defined" is not this replay.
- [ ] Commit: `feat(baymax-ui): tokens v3 and dark mode, contrast checked on the shipped stylesheet`.

### 1b.5 Baymax-path CSP, self-hosted fonts and JS assets, page-weight budget (M)

- [ ] **Files.** Create `baymax/src/main/java/com/elioo/baymax/web/adapter/in/BaymaxSecurityHeadersFilter.java`, a
  `WebFilter` that sets the header in `beforeCommit`, for `/`, `/app`, `/app/**`, `/assets/**`, `/lang/**` and
  `/demo`, with `default-src 'none'; style-src 'self'; font-src 'self'; script-src 'self'; connect-src 'self';
  img-src 'self' https: http:; form-action 'self'; base-uri 'none'; frame-ancestors 'none'`. Per DR-2,
  `SecurityHeadersFilter` in medscribe-ai is **not edited**. Modify `WebUiHandler.java:63` (`ASSETS` += the two woff2
  files plus later js) and `:207-209` (`font/woff2`, `text/javascript`). In `baymax.css`:
  `@font-face{src:local("Noto Sans Bengali"),local("NotoSansBengali-Regular"),url(/assets/noto-sans-bengali-subset.woff2) format("woff2");font-display:swap}`.
- [ ] **Tests.** `medscribe-ai/src/test/java/com/elioo/healthcare/core/filters/BaymaxPathCspTest.java`, in the package of
  `SecurityHeadersFilterTest` (`STRICT_CSP`/`MEDSCRIBE_CSP` are package-private). "Wins regardless of order" is the
  claim, so **both orders** are composed, and one more test goes through the real filter chain:

```java
class BaymaxPathCspTest {

    private static String csp(String path, boolean medscribeFirst) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
        WebFilter medscribe = new SecurityHeadersFilter();
        WebFilter baymax = new BaymaxSecurityHeadersFilter();
        WebFilter first = medscribeFirst ? medscribe : baymax;
        WebFilter second = medscribeFirst ? baymax : medscribe;
        first.filter(exchange, ex -> second.filter(ex, e -> e.getResponse().setComplete())).block();
        return exchange.getResponse().getHeaders().getFirst("Content-Security-Policy");
    }

    @ParameterizedTest
    @CsvSource({"/,true", "/,false", "/app,true", "/app,false", "/app/home,true", "/app/home,false",
            "/assets/baymax.css,true", "/assets/baymax.css,false", "/lang/en,true", "/lang/en,false"})
    void baymaxPathsGetSelfHostedFontsAndScriptsAndNothingInlineInEitherOrder(String path, boolean medscribeFirst) {
        assertThat(csp(path, medscribeFirst))
                .contains("font-src 'self'").contains("script-src 'self'").contains("connect-src 'self'")
                .contains("frame-ancestors 'none'").doesNotContain("unsafe-inline").doesNotContain("unsafe-eval");
    }

    /** The other direction: MedScribe's demo keeps exactly its own policy (DR-2), in either order. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void medscribePathsKeepTheirPolicy(boolean medscribeFirst) {
        assertThat(csp("/medscribeai/", medscribeFirst)).isEqualTo(SecurityHeadersFilter.MEDSCRIBE_CSP);
    }
}
```

  Plus `BaymaxCspThroughTheAppTest` in `medscribe-ai`: a `@SpringBootTest(webEnvironment = RANDOM_PORT)` slice with
  `baymax.enabled=true` and a `WebTestClient` GET of `/app` and `/medscribeai/`, asserting the same two policies through
  whatever filter order Spring actually builds. Expected red without the new filter:
  `expected ... to contain "font-src 'self'"` (today `default-src 'none'` blocks every font).
  `FirstLoadBudgetTest` renders `/` and the timeline through the router test setup, collects every `/assets/...`
  reference from the HTML and every `url(/assets/...)` inside the referenced CSS, sums the bytes of those classpath
  files plus the HTML (crops excluded), asserts ≤ 204,800 bytes counting the fallback woff2 as the worst case, and
  asserts at least one asset was found. `BaymaxWebUiRouterTest.java:43` (`doesNotContain("<script")`) becomes "no
  inline script": no `<script>` without `src`, and no `style="`. Replay: add an inline `<script>` and expect a red
  naming it.
- [ ] Commit: `feat(baymax-ui): a Baymax CSP with self-hosted fonts, and a first-load budget test`.

### 1b.6 Bottom tabs and routes (M)

- [ ] **Files.** `BaymaxWebUiRouter.java:60-74` gets `GET /app/home` (চ্যাট), `GET /app/timeline` (কাগজপত্র) and
  `GET /app/family` (পরিবার, today's patient list from `WebUiHandler.home` plus profile links). `/app/patients/{id}`
  answers 302 to `/app/timeline?patient={id}`. Literal routes go before `{id}` routes (CLAUDE.md). `Html.page` gets a
  `<nav class="tabs">` with three always-labelled links and `aria-current`.
- [ ] **চ্যাট before Phase 3** is honest about what exists: attention cards from stored F1 (NOW/THIS_WEEK papers from the
  last 30 days, most urgent first), the latest paper, and "প্রশ্ন করতে WhatsApp-এ লিখুন". After Phase 0 that line works,
  because WhatsApp replies. **No composer until Phase 3.**
- [ ] **Active patient:** with `max-patients=1` it is the only patient; `?patient=` is accepted and checked through
  `records.visiblePatient`. The switcher arrives in Phase 5.
- [ ] Tests (`BaymaxWebUiRouterTest`): each tab returns 200 behind the session and redirects without one; the old URL
  redirects; the tab marked `aria-current` matches the route.
- [ ] Commit: `feat(baymax-ui): three bottom tabs`.

### 1b.7 কাগজপত্র timeline with urgency chips from F1 (M)

- [ ] **Files.** `TimelineEntry` gets `Urgency urgency`, nullable only while a document is not DONE.
  `TimelineService.entry` zips `sectionCounts` with `DocumentUrgencyPort.find`; a DONE document with no stored urgency
  goes through `StoredUrgencyService` (lazy, idempotent). `DocumentRecordPort.timelineOf` and its adapter page by
  `(timeline_date, created_at, id)`, and the cursor becomes an opaque `timeline_date|created_at|id` string;
  `parseCursor` also accepts the old instant form for one release. The chip renders in a method extracted from
  `WebUiHandler.timelinePage` as package-private `renderEntry(Lang, TimelineEntry)`.
- [ ] **Tests.**
  - `WebUiHandlerRenderTest.aTimelineEntryShowsTheStoredUrgencyChipAndRoutineShowsNone`: NOW renders
    `class="chip now"` with `urgency.now.short` read from the bundle; THIS_WEEK renders `chip soon`; a ROUTINE entry
    renders no `chip` at all (D27). Count `class="chip` occurrences per entry, never a substring of the whole page.
  - `TimelineServiceTest.theChipComesFromTheDocumentNotFromAMessage`: the mocked `OutboundMessagePort` holds a ROUTINE
    message, the stored urgency is NOW, and the entry must be NOW. Replay with the entry reading the latest message:
    `expected: NOW but was: ROUTINE`.
  - `TimelineServiceTest.ordersByDocumentDateThenUploadDate`: an older upload with a newer printed date sorts first,
    and a fallback date is marked.
  - Rewrite `WebUiHandlerRenderTest.nothingInterpretiveIsRenderedForAFullDocument` (`:52-62`, which forbids "জরুরি" and
    "urgent" on every page) per DR-37. Two directions: the only urgency text on a page is the bundle's fixed
    `urgency.*`/`.short` string for the **stored** level, and "normal range", "advice", "should" and "ঝুঁকি" still never
    appear.
- [ ] Commit: `feat(baymax-ui): কাগজপত্র shows the stored urgency, nothing on routine`.

### 1b.8 Copy glossary sweep (S)

- [ ] `ui_bn.properties` ("রোগী" becomes the patient's name, "নথি" becomes কাগজ) and `messages_bn.properties`
  ("স্বাভাবিক সীমা" becomes "রিপোর্টে লেখা সীমা"). **Not** `nudge.silence` ("সব ঠিক আছে তো?"), which belongs to Phase 7's
  template rework, and not `explanation.routine`, which Phase 0 (D35) touched. Add `urgency.now.short` and
  `urgency.this_week.short` (D28) to `bn` and `en`.
- [ ] Test `GlossaryTest`: no bundle value contains `রোগী`, `নথি`, `ছাপা সীমা` or `তুলনা নেই`, derived over every key,
  with a non-empty key count asserted. `MediooBrandTest` stays green.
- [ ] Commit: `fix(baymax-copy): one term each — কাগজ, the patient's name, রিপোর্টে লেখা সীমা`.

### 1b.9 Meta templates: definitions in the repo, submitted now (S, plus weeks of Meta review)

- [ ] **Files.** `baymax/src/main/resources/baymax/wa-templates/{report_ready,report_ready_this_week,report_ready_now,follow_up_reminder,login_code}.json`,
  each in Graph `POST /{waba-id}/message_templates` payload shape: `name`, `language: "bn"`, `category` (`UTILITY`;
  `AUTHENTICATION` for `login_code`), components, and a URL button to `/app/documents/{{1}}`. Bodies carry no values,
  medicines or diagnoses (spec §10, shared phones). Variables are the patient's possessive form (F8, `{{1}}`) and the
  link only.
- [ ] **Test** `WaTemplatesCarryUrgencyTest` (derived): it loads every file in the directory and asserts at least the five
  names exist. The `_now` body contains the bundle's `urgency.now` verbatim (read from `messages_bn`, not retyped), and
  `_this_week` contains `urgency.this_week`. No body contains a digit outside the allowlist, a `{{n}}` beyond the
  declared variables, or a stored-medicine-like token. Replay: delete the urgency line from `_now` and expect
  `report_ready_now does not contain "এখনই ডাক্তারের কাছে যান। দেরি করবেন না।"`.
- [ ] **Submission is Tanvir's hand** in WhatsApp Manager, or a curl with the token he holds; Claude does not touch the
  production token. Record template names, ids and status in `docs/RUNBOOK.md`. Nothing sends a template until Phase 7
  (Phase 2 for `login_code`). Risk: Meta may re-categorise a Bangla health utility template as marketing (about 20× the
  cost) or reject it; resubmit with plainer wording, and keep the urgency line.
- [ ] Commit: `feat(baymax-wa): template definitions with their urgency lines, for Meta submission`.

---

## Phase 2: The result is clear and checkable (L)

**Goal:** result-summary, result-now, needs-retake, document-detail, value-detail and reading-progress, all reading F1,
F2 and F3, with a retake button and a country-aware NOW action.

**Try it:** upload a synthetic lab report on the web and read the banner. Tap a value and see its crop at full size.
Retake a blurred photo with the button. Open a synthetic NOW as a +44 test viewer and see no 999 button, then as his
+880 number and see it. (Restart-mid-read is already covered by Phase 0 PR 4.)

**Entry:** 1b merged. D5 decided (done). Confirm at phase start: D25 (reviewer SLA, which only matters if the gate is
ever on), D31 (signed WhatsApp links, needed here only for "কাগজে দেখুন" from WhatsApp; otherwise Phase 7). No doctor
sign-off: the NOW copy reuses already-approved `messages_bn.properties:32-33`. If the D28 patient-named `urgency.now`
is wanted here, PO copy approval is needed.

| Layer | Work | Files | Size |
|---|---|---|---|
| migration | none required. The DR-25 purge reads `stored_object.created_at`; an index if the query plan needs one | next free `V<n>` | S |
| domain+service | gate-state view model per F3 (none / released / pending / rejected / failed_safety), from `OutboundMessagePort.latestDeliverable` + `latestOfKind`; viewer locale F7 (`+880` → `tel:999`, else the remote variant) | new `web/domain/ResultView.java`, `web/application/service/ResultService.java`; `TimelineService` | M |
| domain+service | DR-25 7-day retake image purge | new `storage/application/service/RetakePurgeJob.java`, `DocumentStorageUseCase` | M |
| web UI | `result-summary`, `result-now` (solid NOW header, never dismissible), `needs-retake` (F5 copy + retake upload targeting the same patient, "এটা গোনা হবে না" per DR-24), `document-detail` (crop-first medicines, "কাগজে লেখা" blocks, DR-26 diagnosis verbatim), `value-detail-sheet` (no-JS URL, glossary hidden until D3), `reading-progress` (three steps, "taking longer" after 90 s, meta refresh with no JS; it reads the recovery state Phase 0 PR 4 writes) | `WebUiHandler` result pages split into `ResultPages.java`; routes `/app/documents/{id}`, `/app/documents/{id}/all`, `/app/documents/{id}/values/{obs}`; back-link fix | L |
| copy | F3 pending, rejected and failed lines; retake reason copy per code; "রিপোর্টে সীমা লেখা নেই"; "কাগজে মিলিয়ে দেখুন" | `ui_bn/en.properties` | S |

The startup sweep (F9b) is **not** here: Phase 0 PR 4 builds it (`DocumentRecoveryService` + `DocumentRecoveryStartup`).
A second sweep would re-run paid extraction twice and race on crop keys.

**Tests (rules):**
- F3 table as a parameterized render test, one case per gate state, plus "REJECTED/FAILED_SAFETY never contains
  'দেখা হচ্ছে'".
- "Pending NOW shows `urgency.now` verbatim and no model text" and its other direction "released NOW carries it too"
  (two directions, written first).
- "+44 viewer gets no `tel:999`" / "+880 viewer does" (two directions).
- A row's colour class comes only from `ValueVerdict.Kind` (assert the class per kind, not that "a colour exists").
- Purge against MinIO and Postgres (`DocumentStorageAcceptanceTest` pattern): an 8-day-old retake page is gone and a
  6-day-old one stays, both directions; the test inserts both.
- Every crop shown is the stored `crop_key` (DR-12 content rule unchanged; no new crop source).

**Depends on:** 1b (F1, F2, F5, tokens, tabs). **Risks:** the retake button doubles free-tier counting unless DR-24 is
respected (test it through `FreeTierUseCase`, not `countInMonth`).

## Phase 3: It answers my questions (companion v1, code only, both channels, L)

**Goal:** steps 0–5 and 7–9 of spec §7.1 with zero model calls, behind one `CompanionUseCase` that the web thread and
`WaIntakeService.onText` both call (Phase 0's `WaTextRouter` becomes the first stage of it).

**Try it:** on the web and on WhatsApp, send "আম্মার ক্রিয়েটিনিন কত?", "ওষুধটা অর্ধেক করব?", "Napa কীসের?",
"আম্মার বুকে ব্যথা", and "ok thanks" after a NOW. The answers are the same on both channels, sources are tappable,
and the emergency reply arrives within 2 s. `ai_call_log` shows no `chat` rows.

**Entry (all required):** **D11 signed by a clinician and the PO** (package below); D24 decided (`sendImage` vs a
ruling that a link satisfies HC3 on WhatsApp); D40 (the HC4 reading for CHAT, URGENCY_NOTICE and EMERGENCY kinds);
D18 only if the "সাহায্য" reply promises a person. Otherwise the help reply states no time and no person (principle 16).

| Layer | Work | Files | Size |
|---|---|---|---|
| migration | `chat_message` and `conversation_state` (`family_id`, `patient_id`, `viewer_id`; in Phase 3 the viewer is the owner account); `outbound_message.kind` CHECK += `chat`, `urgency_notice`, `emergency` | next free `V<n>` | M |
| domain+service | new package `com.elioo.baymax.companion`: `TextNormaliser` (moved from `WaTextRouter.normalise`, plus `DocumentFacts.fold` and a Banglish table), `RedFlagMatcher` (stem/substring, phrases from the **signed** resource), `SymptomRouter`, `MedicineRouter` (the active patient's stored medicine names + medicine words, before any rule), `IntentRules` (whole message, the Phase 0 router's tables), `PatientResolver` (F6, through `records.visiblePatient`), handlers per §7.3 rows marked T/C, `AnswerSafetyCheck` (step 7, wrapping `MessageSafetyCheck`), `NameGrammar` (F8) | `companion/{domain,application/port/in,application/port/out,application/service}/...` | L |
| domain+service | `OutboundMessageGate.decide(Kind, Urgency)`: EMERGENCY and URGENCY_NOTICE always release, asserted at startup so config cannot park them | `OutboundMessageGate.java`, `BaymaxProperties` validation | S |
| adapter | `PostgresChatAdapter`; web `ChatHandler` under `/app/home` (POST `/app/chat`, works without JS via form post + redirect; JS ≤ 40 KB adds send-without-reload); WhatsApp `onText` → `CompanionUseCase`; `WaMessagingPort.sendImage` if D24 chooses it; reply buttons (≤ 3, ≤ 20 chars) | `WaIntakeService`, `WaGraphClient`, `WaMessagingPort` | L |
| web UI | `companion-home` (composer; tab bar hides while composing), `companion-thread`, `emergency-card` (country-aware action) | `CompanionPages.java`, `baymax.css` | M |
| copy | greeting, thanks with urgency repeat, help, off-topic, dose and purpose refusal (D4, DR-16), prescription block headers, emergency and symptom templates **exactly as signed** | `messages_bn/en.properties` | M |

**Tests (rules):**
- Red-flag set, **derived**: phrase × inflection × Banglish from the signed file. Each fires with `handler_path=red_flag`
  and zero metered calls, with the allowance at 0 and the model client mocked to time out. The negative set ("বুকের
  এক্স-রে রিপোর্ট") asserts its decision explicitly. Two directions, both written first. The Phase 0 fallback's red-flag
  line stays until this set ships, and the PR that ships it replaces it in one place (`UrgencyLines`).
- A stored medicine name in the user's text means zero model calls (assert on the `MeteredLlmClient` mock); an unrelated
  word does not route (two directions).
- A share-member-style viewer asking about another patient by name gets the "no permission" line (F6). The test inserts
  both patients.
- The final text contains the urgency string for the highest stored level among cited documents; a body that only says
  "ডাক্তার" fails. This is the proxy `doctor_first` replacement (`MessageSafetyCheck.java:86-92`); replay red against
  today's check.
- The 600-character cap and every check run on the **captured sent text** after every insertion (medicine blocks, links,
  urgency lines).
- Prescription answers: one block per prescription, never merged; the unverified-line notice is shown.
- Every companion template passes `AnswerSafetyCheck` against an empty fact set (derived over bundle keys, shape of
  `CopyInstructionsHaveHandlersTest`).
- Every Meta inbound `type` still has a non-silent handler: Phase 0's `EveryInboundTypeIsAnsweredTest` (driven through
  `WaWebhookHandler`) is extended, not replaced.
- Testcontainers for `chat_message`/`conversation_state` constraints, and the deletion sweep fixture extended with a row
  in each new table (the schema-derived sweep fails until it is).
- No message text, phone number or wamid in INFO logs: a log-capture test on one full turn, with a realistic wamid.

**Depends on:** Phase 0 (`inbound_message`, text router, F4), 1b (F1, F2, tabs), Phase 2 (record cards reuse value rows).
**Risks:** D11 is the critical path and needs a person outside the team; over-firing red flags annoys families (the
conditional phrasing limits it to one message); interactive features on Graph v25.0 need checking against Meta's docs
when written.

## Phase 4: Uploading is easy (M)

**Goal:** several pages make one document on both channels, with honest progress and small uploads.

**Try it:** send a 2-page report as two WhatsApp photos, and three web photos on mobile data. Each becomes one document,
with a progress bar and one acknowledgement.

**Entry:** 1b (JS allowed by CSP). The downscale size is chosen by measurement first: run the extraction eval at the
candidate sizes on the corpus (`scripts/evaluate-extraction.sh`, a paid run Tanvir approves) and record accuracy per
size in `docs/testset/`. No other sign-off.

| Layer | Work | Files | Size |
|---|---|---|---|
| migration | `document.notify_whatsapp boolean` (D32); `conversation_state.pending_media` if Phase 3 did not add it | next free `V<n>` | S |
| service | WhatsApp debounce (~30 s, DB-backed, survives deploys, flushed by a scheduled job); "same report / separate" question when unclear; F9c re-dispatch of claimed-but-unanswered inbound rows (the gap Phase 0 PR 8 only counts) | `WaIntakeService`, new `wa/application/service/MediaGrouping.java` | M |
| web UI | `add-report-sheet`, `page-tray` (reorder, delete; no-JS fallback = `<input multiple>`), client downscale and byte progress (JS ≤ 40 KB) | `WebUiHandler.upload` accepts several `file` parts (`DocumentIntakeService.accept` already takes a list); new `/assets/upload.js` | M |
| copy | PDF ack "ফাইলটা" (Phase 0 has it), multi-page acks | bundles | S |

**Tests:** grouping on real Postgres (two media ids within 30 s make one document; 40 s apart make two, both
directions); restart in mid-window still flushes; `FirstLoadBudgetTest` still passes with `upload.js` counted on the
upload page; free tier counts one document per group (DR-24 unaffected, asserted through `FreeTierUseCase`).
**Risks:** a downscale that costs accuracy silently. Measure it, never assume.

## Phase 5: My family can join (L)

**Goal:** a new number onboards itself on WhatsApp or the web; siblings get their own account, role and messages.

**Try it:** add a second test number to the allowlist, onboard it by saying "Hi", add Baba, share Ma with the second
phone as "কাগজ পাঠাতে পারবেন", send a photo from it, and see "...একটা কাগজ পাঠিয়েছেন" on the owner's home.

**Entry (spec §12, plus the decision below):** DR-23 verify token rotated; the D5 pending-state UI shipped (Phase 2);
the multi-message contract settled (`docs/MEDIOO.md:118`); D9 (invite list), D17 (foreign numbers), D19, D29, D30, D33
and D37 confirmed; **the gate decision taken and recorded**; `BAYMAX_FREE_MAX_DOCS_PER_MONTH` reverted to the pilot
value if Preflight raised it.

| Layer | Work | Files | Size |
|---|---|---|---|
| migration | `signup_intent`; `share_member.role` CHECK (`view`, `upload`); `family_account.zone`; per-recipient `message_delivery` (D19: one row per recipient, its own wamid, its own V14-style CHECKs); sibling accounts (share member → own `family_account` link) | next free `V<n>` (+1) | L |
| service | WhatsApp onboarding W2 with the red-flag check on every turn; `welcome-consent` and `add-parent` (relation chips, self mode F8); which-patient chips W6 with media held in `conversation_state`; **fix `WaIntakeService` `latestDoneDocument(family)` → by patient**; `removeShareMember`; roles enforced in `TimelineService.upload`; `max-patients` raised by config; recipient-zone nudge windows (DR-18 amendment) in `NudgePolicy.zone()` and `NudgeRules.zone()` | `family/`, `healthrecord/`, `wa/`, `nudge/` | L |
| web UI | `welcome-consent`, `add-parent`, `share-family`, patient switcher in the header | new pages | M |

**Tests:** both delete paths sweep every new table; the schema-derived sweep in `FamilyDeletionAcceptanceTest` (Phase 0
PR 8) picks up `viewer_id` and sibling-account links through their FKs, and its fixture inserts a row in each
(`signup_intent`, `message_delivery`, sibling links) or it fails. `message_delivery` uniqueness and CHECKs on real
Postgres. A view-only member cannot upload; an upload member can, and neither can delete (two directions). One
sibling's NOW acknowledgement does not shrink another's NOW card (D14). A +44 owner's nudge is held until 09:00 London;
the patient's due date still reads in Dhaka time (two directions). Red flags fire inside onboarding and inside "কার?".
**Risks:** wrong-patient filing is the most harmful proxy error, so the which-patient flow must never guess; consent
copy needs PO sign-off; a dedicated business number (D37) before strangers see Tanvir's own.

## Phase 6: It talks like a companion (companion v2, model wording, L)

**Goal:** model wording over code-built references for `Kind.CHAT`, within 6 model-worded answers per family-month
(D8), with every step-7 check live.

**Try it:** free-form Bangla and Banglish ("bujhlam na", "kal doctor er kache jabo"). Check every number against its
crop. See `# chat` and `# classify` in the weekly export, and a reply that falls back to code when the model times out.

**Entry:** every §7.1 step-7 check live and **replayed red on the defect** against today's `MessageSafetyCheck` (e.g.
`number_word_not_allowed:দেড়`, not an NPE); D3 glossary signed by a clinician if "what does HTN mean" is in scope (it
amends DR-26); the Phase 5 gate decision revisited (option A becomes possible here).

| Layer | Work | Files | Size |
|---|---|---|---|
| migration | `ai_call_log` purpose CHECK += `classify` (V15 already has `chat`) | next free `V<n>` | S |
| service | Haiku classifier (may only raise toward symptom/emergency; `patient_hint` becomes a confirm chip); reference grounding `{{obs\|dx\|doc\|rx:id}}` resolved for the active patient only; clarify-last; trend wording; the D8 allowance; 20 s timeout to the code fallback; its own chat LLM client config (effort is per client, `AnthropicLlmClient.java:94-99`) | `companion/application/service/*`, `extraction/config/ExtractionModelConfiguration.java` pattern | L |
| service | after-the-fact review queue and the "ভুল" marker (changes no value or urgency) | `outbound/` | M |

**The D8 allowance is asserted on what was sent.** The allowance check counts the month's `outbound_message` rows of
kind `chat` that carry model wording (a `worded_by_model` marker in `urgency_reasons`-style machine codes, or the
matching `ai_call_log` `chat` rows joined to a delivered message), never a separate counter that can drift from what
reached the family. The reservation is one atomic statement (`INSERT ... SELECT ... WHERE (SELECT count(*) ...) < :cap`),
so it holds under concurrency.

**Tests:** number words, condition words, timing words outside appended strings, stored medicine names, and references
to another patient each rejected, with the passing direction for each ("একজন ডাক্তার"; a condition inside a
code-inserted `{{dx}}`). Allowance: 6 model-worded answers sent means the 7th is code-only, and safety intents still run
at 0, counted from the sent rows. **A Testcontainers race:** a family with 5 model-worded answers this month sends two
questions at the same instant; exactly one gets a model-worded answer and the other a code-only one. Fallback on
timeout is never silence. Metering rows carry the right purpose. All conversations are synthetic. Usability sessions
with 5–8 caregivers before the PO trusts it (spec §7.8).
**Risks:** cost, which is uncached ≈ $0.021/answer (§7.6); caching is its own measured change.

## Phase 7: It keeps watch (M–L)

**Goal:** trends, reminders in the recipient's day, approved templates outside the 24 h window, help routing, voice,
signed links, demo.

**Try it:** receive a follow-up reminder template in his own daytime, tap it, ask about the HbA1c trend, and open the
demo on a friend's phone.

**Entry:** Meta templates approved (submitted in 1b); D13 STT provider chosen under HC6 (zero retention); D18, D31 and
D38 confirmed; **a nudge-gate decision**: `BAYMAX_GATE_NUDGES` defaults to `true` and parks every nudge for a reviewer
(DR-18), so with "reviewer later" no reminder ever reaches a family. Either set it to `false` with the DR-18 checks as
the guard (a DR-18 amendment), or ship the reviewer path first.

| Layer | Work | Files | Size |
|---|---|---|---|
| service | daily re-assessment of open follow-ups through `StoredUrgencyService` (past-due raises THIS_WEEK on the timeline without a view); template sends outside the window (`WaMessagingPort.sendTemplate`); help routing (D18); STT behind a port (replacing D13's fixed voice reply); visit one-pager; signed one-document links (D31, HMAC pattern of the opt-out link); demo family seeded from fictional data only (DR-36), read-only, zero model calls | `nudge/`, `wa/`, `outbound/` | L |
| web UI | `trends` (SVG with class-based fills; `style-src 'self'` forbids inline styles), `reminders`, `demo-family` | new pages | M |
| copy | `nudge.silence` reworded ("সব ঠিক আছে তো?" is reassurance-adjacent); template bodies | bundles | S |

**Tests:** no NOW/THIS_WEEK result goes out through a template lacking its urgency line (extends 1b.9 to the send path,
asserted on the captured send); a reminder is held outside 09:00–20:00 in the recipient's zone; the demo page makes zero
metered calls and carries the "নমুনা পরিবার" banner; a signed link opens one document for 24 h and nothing else (two
directions: the other document 404s, and an expired link 404s).

---

## Doctor sign-off package (D11, the Phase 3 entry criterion)

One session, one signed artifact, in the proposed-then-active pattern the threshold table already uses
(`baymax/src/main/resources/marker-thresholds.properties`, guarded by `ShippedThresholdsAreAllProposedTest`).

1. **Red-flag categories and phrases** (spec §7.2): chest pain, breathlessness, unresponsive/fainting, stroke signs,
   seizure, major bleeding / vomiting blood / black stool, sudden severe headache, fall with head injury, fever with
   confusion or stiff neck, anaphylaxis, hypoglycaemia in an insulin user, overdose or wrong/double dose, self-harm.
   Each in Bangla, Banglish and English with inflections, shipped as `baymax/src/main/resources/red-flags.properties`,
   each row `status=proposed` with a `source`.
2. **A dry-run table generated from the code, not prose.** For every phrase and for the negative set it shows which
   template fires. The doctor signs what the code does.
3. **Template texts:** emergency (conditional phrasing "এখন যদি এমন হয়..."), the remote variant for non-+880 viewers,
   the 999 wording, the symptom template including its timing ("আজই..."), the self-harm template, and the wrong-dose
   template. English versions too (D39).
4. **D28:** `urgency.now` naming the patient, plus `urgency.now.remote`.
5. **D34:** rules for one-sided ranges ("<100", "> 500") and qualitative positives (HBsAg, Dengue NS1), which today can
   never raise urgency (`UrgencyService.java:349`). Until a rule exists they show UNKNOWN (F2) and, since Phase 0 PR 2,
   never support "সব কিছু স্বাভাবিক".
6. **The pending per-marker thresholds** already in `marker-thresholds.properties`, reviewed in the same session.
7. **The Phase 0 red-flag line on every fallback and on THIS_WEEK notices** (an interim rule the PO approved under D13 and
   D5): the clinician confirms or replaces it.
8. **D3 glossary** (Phase 6): a separate sheet, one meaning per entry, ambiguous abbreviations excluded.

Deliverable: the signed sheet (name, registration, date) is kept outside the repo if it names the clinician privately.
In the repo, the rows flip to `status=active` with `source=<signer initials, date>` in one PR. Guard
`ShippedRedFlagsAreSignedTest`: the table loads and is non-empty (no vacuous pass), every category in item 1 has at
least one active row, and no proposed row can fire in production. The PO signs the template texts alongside.
**Start drafting during Phase 1b**; it needs a person outside the team and is the critical path to Phase 3.

---

## Decisions still open

| Decision | Needed before | Options | Recommendation | Owner |
|---|---|---|---|---|
| **Gate mode before real families** | Phase 5 entry | **A.** Gate off; after-the-fact review: model wording on NOW/THIS_WEEK goes out live, step-7 checks are the only guard, Tanvir reads 100% of messages weekly from the export. **B.** `gate=urgency` as the spec says: urgency notice at once (D5), wording after a human approves; needs reviewer notification (D18), a phone approve path, the D25 SLA job; contradicts "reviewer later". **C.** Gate off, but **code-only wording on urgent documents** until the reviewer exists: `baymax.outbound.model-wording=routine-only\|all`; NOW/THIS_WEEK send the code skeleton (`ExplanationService.skeleton`, already sendable — ROUTINE ships it today) with no model call. | **C**, recorded as a second DR-13 amendment. Fully automated, nothing waits for a reviewer, no unreviewed model text where it matters most, and cheaper. Move to B when the reviewer portal exists, or to A once Phase 6's step-7 checks are live and replayed red. Tests for C: "NOW under routine-only makes zero `explain` calls and contains `urgency.now` verbatim"; the other direction "under `all` it still calls the model"; both under `gate=urgency` too. Size S. | PO (it amends DR-13) |
| **D11 clinician sign-off** | Phase 3 entry | The package above | Start during 1b; one session; rows flip from proposed to active in one PR | PO + a clinician |
| **Meta templates** | Phase 1b (submit), Phase 7 (use) | Submit the five in 1b; Meta may re-categorise to marketing (≈20× cost) or reject | Submit early with plain wording and the urgency line kept; record ids and status in RUNBOOK | Tanvir submits |
| Nudge gate (`BAYMAX_GATE_NUDGES=true` parks every nudge) | Phase 7 entry | `false` with DR-18 checks as the guard, or reviewer path first | Decide together with the gate mode above | PO |
| Repeat "বিস্তারিত": pointer or resend | Phase 0 PR 5 (built as pointer) | Pointer ("আগেই পাঠানো হয়েছে" + link) or resend the body (needs a second row: V14 allows one `sent_at` and one wamid per row) | Pointer. The greeting copy, the DETAIL synonym list and the try table are written to agree with it | PO |
| The red-flag line on THIS_WEEK notices as well as NOW | Phase 0 PR 5 | NOW only, or NOW and THIS_WEEK | NOW and THIS_WEEK (spec W16; HC2 "when uncertain, escalate"); confirmed by the D11 clinician | PO, then clinician |
| Phase 0/1a draft copy (all new `wa.*`, `login.*`, `failure.*` keys) | Each PR's merge | — | Drafts; the copy owner (Tanvir's wife) approves; tests read the bundle, so wording can change without code | Copy owner |
| `IWebFilter` header redaction is a MedScribe file | PR 1 | Edit it (a credential leak), or leave MedScribe untouched per DR-2 | Edit it; a credential leak outranks DR-2's "untouched"; recorded in the build report | PO |
| Free-tier headroom for the tries | Preflight | Raise `BAYMAX_FREE_MAX_DOCS_PER_MONTH` for the duration, or set his family to `plan='family'` | Raise the env value (reversible, no data change); revert at Phase 5 entry | Tanvir |
| DR-38 open-source pause (recorded in PR 0) | 0 | done | Recorded in PR 0 per the Sprint 1 brief ("open source is paused (DR-38)"); `gh repo view` says PRIVATE; DR-14 still says "open-source" | PO |
| DR-21, DR-33, DR-34, DR-35: do they exist? | 1b | — | Only the spec cites them; ask the PO project; do not invent or reuse the numbers | PO |
| Dedicated business number (D37) | Phase 5 | Keep Tanvir's own number on `/app` only, or a new number | New number before strangers see it | PO |
| CLAUDE.md "main is protected" | PR 0 | Correct the line, or upgrade the GitHub plan | Correct the line and keep the manual pre-merge step | Tanvir |

## DECISIONS.md entries to write

| Entry | When | Status | Content |
|---|---|---|---|
| **DR-37** The companion is core MVP scope | PR 0 | Approved (D1) | One brain behind web and WhatsApp that always replies; code produces urgency, numbers, medicines, identity, refusals and emergency replies; the model only phrases over code-built references; push stays the moat. Records D4 (DR-16 unchanged), D8 (6 model-worded answers per family-month, code answers unlimited), D13 (voice: fixed reply + red-flag line now, STT later), and "fully automated first". Amends PRODUCT §4 "MVP in". Retires the UI rule "no urgency anywhere in the UI" (`WebUiHandlerRenderTest.nothingInterpretiveIsRenderedForAFullDocument`), replaced by spec principle 3. |
| **DR-39** Sign-in is inbound-first — amended 2026-10-18: sign-in is free | PR 0; amended in PR 12 | Approved (D2; D36, D37 as recommended); amendment by the PO 2026-10-18 (inside the window the web sends the code automatically; "sent" only when Graph accepted it) | The family texts "কোড"; `issueCodeFor(sender)` mints for the number WhatsApp proved; the web mints nothing and promises no send; the rate-limit message goes only to WhatsApp (D36); the wa.me link appears on `/app` only (D37); no code in any log above DEBUG. Effective from Phase 1a PR 12. |
| **DR-13 amendment** (D5) | PR 0 | Approved | "*Amended 2026-10-17 (PO, D5):* the stored, code-computed urgency shows on every surface whatever the gate state. On WhatsApp a PENDING NOW/THIS_WEEK document gets its fixed urgency line at once. A PENDING **body** is still never delivered: 'Nothing PENDING is ever delivered' now reads 'No PENDING body is ever delivered'." |
| DR-27 recorded, with its correction | Phase 0 PR 3 | Correction proposed in the build report | DR-27 is cited in code (`BaymaxProperties.java:260`, `DocumentExtractionService.java:332`) but has no entry. Record it with the correction: emptiness gates on `expectedSections` (a prescription needs medicines, a lab report values); `gatingSections` governs confidence only. |
| DR-13 second amendment (gate option) | Before Phase 5 | Open | Whichever of A/B/C the PO chooses. |
| DR-18 amendment (D29) | Phase 5 (may be written in 1b, "effective from Phase 5") | Recommended | The 09:00–20:00 window is in the **recipient's** zone; the patient's calendar stays Asia/Dhaka. |
| Open-source publication paused | 1b | Needs the PO's wording | Next free number when confirmed. |

## Risks

| Risk | Where | Mitigation |
|---|---|---|
| A clash between two green PRs breaks main (no branch protection) | Phase 0 | Strict serial order, rebase before merge, `mergeStateStatus` CLEAN, the migration allocation table |
| Each deploy SIGKILLs in-flight documents until PR 4 lands | PRs 0–3 | PR 4 (recovery) is the fourth PR; PRs 1–3 are small; merge when no try is in flight |
| The first deploys sweep old production documents (recovery, unexplained explain, later FAILED notices) and message Tanvir about old papers | PRs 4, 9 | Bounded by `max-age` (7 days re-run, 6 h re-explain); Tanvir counts stuck rows first (read-only, his choice) |
| Free tier stops the tries partway | Phase 0 | Preflight raises the cap; revert at Phase 5 |
| A prescription try fails for a reason unrelated to its PR | Phase 0 | PR 3 lands early; until it merges every try uses a lab report |
| A try passes through a different rule from the one fixed | PR 2 and others | Each try first confirms the path on the web page (stored range, urgency), the same as tests assert the path |
| Credentials already in the production log (session cookies, admin token) | PR 1 | Count, rotate `BAYMAX_ADMIN_TOKEN`, optionally revoke sessions, rotate `BAYMAX_WA_VERIFY_TOKEN` (DR-23, still outstanding) — Tanvir's production actions |
| `BAYMAX_WA_PUBLIC_NUMBER` blank or malformed when PR 12 deploys: nobody can sign in, or the validator refuses startup and takes MedScribe down with it (`deploy.sh` has already removed the old container) | PR 12 | Set and shape-check the variable before merging; old code ignores it |
| The urgency-mode paths cannot be tried on a phone | Phase 0 onward | Stated once: proven by tests in both gate modes; production stays `gate=off` |
| Wamids in storage | PR 8 and later | `inbound_message` keys on HMAC(wamid) and stores no raw wamid; outbound `provider_message_id` stays on rows deleted with the family |
| Graph v21.0 expires 2027-01-21 | PR 10 | v25.0 with an env rollback (`BAYMAX_WA_GRAPH_VERSION`); the validator warns 90 days before any version's expiry |
| D11 needs a person outside the team | Phase 3 | Start drafting in 1b |
| Meta re-categorises templates as marketing | Phase 1b/7 | Plain utility wording, urgency line kept, resubmit |
| Wrong-patient filing when families have several patients | Phase 5 | The which-patient flow never guesses; until then `max-patients=1` and AMBIGUOUS_PATIENT replies |
| Cost of model wording | Phase 6 | D8 allowance on sent rows; uncached ≈ $0.021/answer; caching measured separately |

## Order and critical path

```
Preflight → PR 0 docs → PR 1 L → PR 2 A1 ┐
                              → PR 3 RX ─┴→ PR 4 C2 (V16) → PR 5 A2 → PR 6 B1a → PR 7 B1b
          → PR 8 C1 (V18) → PR 9 B2 (V19) → PR 10 C3 → PR 11 1a-i → [env: BAYMAX_WA_PUBLIC_NUMBER] → PR 12 1a-ii (V17)
          (as merged: PR 11 and PR 12 went ahead of PRs 7–10; PR 12 carries Task 10.1)
Phase 1b (V20 F1+F2, V21 F5, look) → 2 → 3 → 4 → 5 → 6 → 7
            └ 1b.9 Meta submission ──────────────────────────── (approval) → 7
D11 doctor package: start drafting during 1b ─────────→ signed before 3
Gate decision (C recommended): take during 3 ─────────→ before 5
Nudge-gate decision: before 7
```

Phase 4 can run in parallel with Phase 3, since it shares only `WaIntakeService`; sequence those PRs.
