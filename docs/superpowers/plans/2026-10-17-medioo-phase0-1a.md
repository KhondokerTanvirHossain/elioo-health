# Medioo Phase 0 + Phase 1a — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Work one PR at a time, in the merge order below; each PR starts from merged `origin/main`.

**Goal:** Every WhatsApp message a family sends gets exactly one reply, every document ends with a reply, the detail
arrives once, the defects live in production today are fixed, and the family signs in on the web with a code they
fetch by texting "কোড" on WhatsApp (D2). Done means Tanvir has tried each PR's checklist on his own phone in production.

**Architecture:** Hexagonal Baymax module (`com.elioo.baymax`) inside the medscribe-ai deployable. The work lands as
thirteen serial PRs. A WhatsApp text router (`WaTextRouter`, whole-message rules, zero model calls) and a typed
`WaInbound` give every Meta message type a reply. `ExplainDocumentUseCase` is split into
`composeChecked` / `release` / `deliver`, so a composed body reaches a family only through `deliver`, at most once per
row (an atomic claim on `outbound_message`), and every other gate state gets fixed copy that leads with the
code-computed urgency (D5). Inbound messages are claimed under HMAC(wamid) before the webhook answers 200 (F9a).
Documents interrupted by a deploy are re-run from their stored pages at startup through one `DocumentProcessingChain`
(F9b), and every FAILED writer meets one notice. Sign-in mints codes only for the WhatsApp sender (`issueCodeFor`), and
codes never reach a log above DEBUG.

**Tech Stack:** Java 21, Spring Boot 3.4.2 WebFlux, Reactor, R2DBC + Flyway (schema `baymax`), Postgres 17, JUnit 5,
Mockito, AssertJ, Reactor `StepVerifier`, Testcontainers (`postgres:17-alpine`, MinIO), ArchUnit, Logback
`ListAppender`, WhatsApp Cloud API (Graph v25.0 from PR 10).

**Spec:** `docs/superpowers/specs/2026-10-17-medioo-companion-experience-design.md` (§2 F4, F9; §6 W1–W23; §7.1;
§11 D2, D4, D5, D8, D13, D35, D36, D37; §12 Phase 0 and 1a). **Roadmap:** `docs/superpowers/plans/2026-10-17-medioo-roadmap.md`.
**Design artifacts:** Medioo Experience canvas https://claude.ai/artifact/X9xDXZEL2FgU5hv5bRQT2i (screens
`sign-in-number`, `sign-in-code`, the WhatsApp journey) and Medioo Design System
https://claude.ai/artifact/F7Kj5uHkfv4nmPWVrDhimZ (tokens, components; tokens v3 land in Phase 1b, so Phase 1a restyles
on today's `baymax.css` with existing tokens only).

**Approved decisions this plan builds:** D2 (inbound-first login code), D4 (keep refusing "what is this medicine for",
DR-16: nothing here answers it; such text gets the fallback), D5 (urgency shows at once, before review), D13 (voice: a
fixed reply plus the red-flag line), D35 (the normal-range claim needs every value INSIDE by code), and the standing
direction **fully automated first**: production stays `BAYMAX_GATE_MODE=off`.

## Global Constraints

- **Production gate stays off.** Every gate-sensitive path (PENDING, REJECTED, FAILED_SAFETY, `gate-mode=urgency`) is
  tested in both gate modes and is **not** tried on the phone; nobody flips `BAYMAX_GATE_MODE` to try it. The FAILED and
  FAILED_SAFETY replies (PR 9) cannot be triggered safely in production either: their done is the local run plus
  Testcontainers, and their production check is "nothing regressed". This is stated once here, not per PR.
- **TDD; both directions first.** Every rule with two directions has both tests written before the implementation.
  A **regression guard** is replayed against the pre-fix source and must fail **on the defect**: read the message and
  paste it into the PR body. A red that is a compile error, an NPE, a Mockito matcher error or
  `messages: missing key …` is not a replay. When the guard needs nothing new to compile, the replay is the Step 2 run.
  When the guard sits beside other new tests, replay in a scratch worktree:
  `git worktree add /tmp/replay-<pr> origin/main && cp <guard file> /tmp/replay-<pr>/<same path> && (cd /tmp/replay-<pr> && ./gradlew :baymax:test --tests '<guard>')`,
  then `git worktree remove /tmp/replay-<pr>`. New-feature tests are labelled as such in the PR, not counted as replays.
- **A DB constraint path gets a Testcontainers test**; never only a mocked port. The in-memory fake
  `InMemoryOutboundMessages` (PR 5) cannot fail like Postgres and is never used to test a constraint.
- **Limits on what is sent.** The 600-character cap is asserted on the captured `sendText` argument, after every
  insertion; a test never rebuilds the string itself.
- **Logs:** ids, sizes, digests, routes and machine codes only. Never patient text, a phone number or a wamid (a
  wamid is base64 of the phone number). Use `Redact.id(wamid)` (PR 1) or the row UUID. Patient text is allowed at
  DEBUG only (CLAUDE.md); numbers and wamids not at all. Exception messages are never logged above DEBUG
  (`e.getMessage()`/`e.toString()` can carry a signed media URL or medicine text): log the class name and a machine
  reason.
- **Fixtures:** families use the fictional block `+8801700000000`–`+8801700000999` only (`FROM = "8801700000000"`,
  `E164 = "+8801700000000"` unless a test needs a second family). Wamids are built by `WaFixtures.wamid(digits[, n])`
  (PR 1), which produces Meta's real shape, so a leak is visible. The maintainer's personal number must not appear in
  any source or doc; PR 1 adds the guard.
- **Reactive, no `.block()` outside tests.** Every deferred send is wrapped in `Mono.defer` (`.then(send(...))` calls
  `graph.sendText` at assembly time, the bug noted at `WaIntakeService.java:61-62`).
- **Constructors.** `@RequiredArgsConstructor` uses field order. A PR that adds a field appends it **last** and updates
  every `new X(` (grep the test tree) in the same commit. `WaIntakeService` field order, as each PR leaves it:

  | After PR | `WaIntakeService(...)` |
  |---|---|
  | main, 1–6 | `records, documents, explainer, intake, graph, copy, properties` |
  | 12 (1a-ii) | `…, properties, auth` |
  | 7 (B1b) | `…, auth, clock, freeTier, attention, urgency` |
  | 8 (C1) | `…, urgency, inbound, hasher` |

  *2026-10-21:* PRs 10, 11 and 12 merged ahead of PR 7, so `auth` comes right after `properties` and PR 7 appends after
  it. PR 7 reads the standing urgency from F1's stored level (`AttentionUseCase` for `attention`, the চ্যাট stack's read;
  `DocumentUrgencyUseCase` for `urgency`, the latest paper's `currentOrAssess`), never from `OutboundMessagePort`, so
  `messages` is not a field. Full order after PR 7:
  `records, documents, explainer, intake, graph, copy, properties, auth, clock, freeTier, attention, urgency`.

- **Copy keys (one set; tests read the bundle, never retype a string):** `safety.red_flag_line` (the approved
  sentence inside `explanation.now`), `wa.detail.pending`, `wa.detail.unavailable`, `wa.detail.already_sent` (PR 5);
  `wa.greeting.*`, `wa.thanks`, `wa.fallback`, `wa.voice`, `wa.video`, `wa.unsupported_file`, `wa.received.file`
  (PR 6); `wa.greeting.latest`, `wa.greeting.urgent`, `wa.thanks.urgent`, `wa.failed.*` (PR 7); `failure.document`,
  `explanation.fallback` (PR 9); `wa.login_*`, `login.*` (PR 12). All new wording is a **draft** for the copy owner
  (Tanvir's wife); `MessagesBundleTest` (PR 5) keeps bn/en keys and placeholders identical.
- **One urgency rule (`UrgencyLines`, PR 5).** Wherever code states a level: NOW → `urgency.now` then
  `safety.red_flag_line`; THIS_WEEK → `urgency.this_week` then `safety.red_flag_line`; ROUTINE → nothing. The block
  comes before any body it qualifies. Only a one-line address (the greeting opener "হ্যালো!", "ঠিক আছে। মনে করিয়ে দিই:",
  or the name of the paper it refers to) may precede it. Every assembler calls `UrgencyLines.block`; nothing else
  writes an urgency line.
- **Repeat detail.** A second "বিস্তারিত" for an already-delivered detail gets a pointer
  (`wa.detail.already_sent` + link), never the body again. The greeting copy, the DETAIL synonym list and the try
  tables agree with this. (Open PO ruling, roadmap; the pointer is the default.)
- **Migrations:** V16 (PR 4), V17 (PR 12, `wa_last_inbound` — added by the PO decision of 2026-10-18 and merged ahead
  of PRs 7–10, see PR 12), V22 (PR 8), V23 (PR 9). Each of those PRs checks, right before merging, that
  `git ls-tree --name-only origin/main baymax/src/main/resources/db/baymax/migration | sort -V | tail -1` prints its
  predecessor (V15 for PR 4, V16 for PR 12, V21 for PR 8, V22 for PR 9). If not, stop: the allocation table in the
  roadmap is wrong, fix it there first. (Renumbered 2026-10-18: PR 8 was V17 and PR 9 was V18 before PR 12 took V17. Renumbered again 2026-10-19 — PO
  direction 2026-10-19: the web portal first; Phase 0 PRs 7–10 rebase after the web chain, which takes V18–V21
  (`2026-10-19-medioo-web-portal.md`); PR 8 moves V18 → V22 and PR 9 V19 → V23. The file names, preflights and
  migration checks in the PR 8 and PR 9 sections below were rewritten to V22/V23 at PR 8's rebase, 2026-10-21.)
- **Staging and commits:** explicit paths only, never `git add -A`/`git add .`; conventional commits ending with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`; PR bodies end with
  `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
- **Secrets and production:** never source `.env.supabase`; never `bootRun` against it. Every production step (env
  edits, SSH, restarts, SQL) is **Tanvir's** action, listed in the build report, with `~/medscribe.env` backed up first
  (`cp ~/medscribe.env ~/medscribe.env.bak-$(date +%Y%m%d-%H%M)`, exact filename, never a glob).

### Every PR: pre-merge and post-deploy (referenced as "the standard pre-merge" and "the standard post-deploy")

**Pre-merge** (branch protection is not enforced on this private repo; `gh api …/branches/main/protection` answers 403):
1. `git fetch origin && git rebase origin/main`; rewrite any snippet that no longer matches merged main.
2. Migration check (PRs 4, 8, 9 only), as above.
3. `docker compose up -d && ./gradlew build` green (all 10 modules; Testcontainers needs Docker).
4. `git status --ignored --short -- baymax/src medscribe-ai/src | grep '^!!'` prints nothing.
5. `git diff --stat origin/main` lists only this PR's file map.
6. Push, `env -u GITHUB_TOKEN gh pr create` (body: what, why, replayed reds verbatim, new-feature tests, PO notes, env
   changes), then `env -u GITHUB_TOKEN gh pr view <n> --json mergeStateStatus` is `CLEAN` and
   `env -u GITHUB_TOKEN gh pr checks <n> --watch` is green.
7. Squash-merge. **Verify before deleting the branch:** `git fetch origin && git log origin/main --oneline | grep '#<n>'`
   prints the commit. Only then delete the remote and local branch.

**Post-deploy** (Tanvir, after CI's deploy job):
1. `curl -fsS https://medioo.eliooo.org/actuator/health` reports `UP`; the RUNBOOK self-test passes.
2. `docker logs medscribe-ai --since 15m 2>&1 | grep -cE ' ERROR |refusing to start'` is 0, or each line is explained.
3. The leak counters stay flat (they are 0 from PR 1 on):
   `docker logs medscribe-ai --since 15m 2>&1 | grep -cE 'baymax_session=|X-Baymax-Admin-Token: [^R]|wamid\.'`.
4. Then the PR's own "Try it on your phone" checklist.

## Merge order

| # | PR | Branch | Base | Migration | Tasks | Size |
|---|---|---|---|---|---|---|
| — | Preflight (Tanvir; no PR) | — | — | — | P.1–P.2 | S |
| 0 | Docs: spec, roadmap, plan, DR-37/DR-39, DR-13 amendment | `docs/medioo-companion-design` | main | — | 0.1 | S |
| 1 | **L** — no credentials, patient text, numbers or wamids in logs | `fix/no-secrets-in-logs` | PR 0 | — | 1.1–1.5 | S–M |
| 2 | **A1** — D35 normal-range claim | `fix/baymax-normal-range-claim` | PR 1 | — | 2.1 | S |
| 3 | **RX** — a prescription without a follow-up is not a retake | `fix/baymax-prescription-follow-up-gate` | PR 1 (parallel with PR 2; merge after it, rebased) | — | 3.1–3.3 | S |
| 4 | **C2** — F9b interrupted documents finish after a restart | `feat/baymax-document-recovery` | PR 3 | V16 | 4.1–4.4 | M |
| 5 | **A2** — F4 compose / release / deliver | `fix/baymax-detail-compose-release` | PR 4 | — | 5.1–5.8 | M–L |
| 6 | **B1a** — every message gets one reply | `feat/baymax-wa-every-message-answered` | PR 5 | — | 6.1–6.5 | M |
| 7 | **B1b** — standing urgency, free tier before the ack, failures after the ack | `feat/baymax-wa-greeting-urgency` | PR 6 | — | 7.1–7.4 | M |
| 8 | **C1** — F9a inbound idempotency | `fix/baymax-wa-inbound-idempotency` | PR 7 | V22 (was V18; renumbered 2026-10-19) | 8.1–8.6 | M–L |
| 9 | **B2** — every document ends with a reply | `fix/baymax-every-document-ends-with-a-reply` | PR 8 | V23 (was V19; renumbered 2026-10-19) | 9.1–9.4 | M |
| 10 | **C3** — Graph v25.0, one allowlist rule, read receipts | `feat/baymax-graph-v25` (planned as `feat/baymax-wa-graph-v25-read-receipts`) | ~~PR 9~~ `origin/main` after PR 12; **merges ahead of PRs 7–9** (note under PR 10, 2026-10-21) | — | 10.1–10.3 | S–M |
| 11 | **1a-i** — every way of writing a number; Bangla-digit codes | `fix/baymax-number-and-bangla-code` | PR 10 | — | 11.1–11.2 | S |
| 12 | **1a-ii** — sign-in by WhatsApp code, free (D2, DR-39 amended 2026-10-18) | `feat/baymax-whatsapp-sign-in` | PR 11 (+ Task 10.1 as its first commit), **after the env step**; merges ahead of PRs 7–10 | V17 | 12.0–12.8 | M |

Why this order: PR 1 stops live credential and number leaks before anything else ships on top of them. PRs 2 and 3
are tiny live-defect fixes with no shared files. PR 4 comes next because every later deploy (`docker rm -f`, a SIGKILL)
would otherwise strand an in-flight document. PR 5 owns the whole detail path (F4); PRs 6–7 build on its API; PR 8
claims inbound messages in front of PR 6's single entry point; PR 9 writes its failure notices against PR 5's split
and PR 4's chain; PR 10 bumps the Graph version once everything that calls Graph has landed (*2026-10-21: it merges
ahead of PRs 7–9 instead, because v21.0 expires 2027-01-21; see the note under PR 10*); PRs 11–12 are Phase 1a.

## File map (by PR)

| PR | Create | Modify |
|---|---|---|
| 0 | `docs/superpowers/plans/2026-10-17-medioo-roadmap.md`, `docs/superpowers/plans/2026-10-17-medioo-phase0-1a.md` | `docs/superpowers/specs/2026-10-17-medioo-companion-experience-design.md` (stage it; one number replaced by a placeholder), `docs/superpowers/specs/2026-10-17-medioo-tokens-v3.json` (stage), `docs/DECISIONS.md`, `CLAUDE.md` (only if Tanvir agrees) |
| 1 | `baymax/.../common/redact/Redact.java`; tests `medscribe-ai/.../core/filters/HeaderRedactionTest.java`, `baymax/.../wa/WaFixtures.java`, `baymax/.../wa/WaFixturesTest.java`, `baymax/.../wa/adapter/in/WaLogHygieneTest.java`, `baymax/.../NoPersonalNumberInSourcesTest.java` | `medscribe-ai/.../core/filters/IWebFilter.java`, `wa/adapter/in/WaWebhookHandler.java`, `wa/adapter/out/WaGraphClient.java`, `wa/adapter/out/WhatsAppMessageDelivery.java`, `wa/application/service/WaIntakeService.java`, `extraction/application/service/DocumentIntakeService.java`; six test files (number scrub); `DocumentIntakeServiceTest.java`, `WaIntakeServiceTest.java`; `docs/RUNBOOK.md` |
| 2 | — | `outbound/application/service/ExplanationService.java`, `UrgencyService.java`, `messages_bn.properties` (comment); tests `WithinNormalRangeClaimTest.java`, `ExplanationServiceTest.java` |
| 3 | `baymax/.../config/ReplayHarnessMatchesTheGateTest.java` | `scripts/replay-extraction-gate.py`, `config/BaymaxProperties.java` (`Extract.expectedSections`), `extraction/application/service/DocumentExtractionService.java` (`missingExpectedSection`); `DocumentExtractionServiceTest.java` |
| 4 | `V16__document_recovery_attempts.sql`; `extraction/application/service/{DocumentProcessingChain,DocumentRecoveryService}.java`, `extraction/application/port/in/DocumentRecoveryUseCase.java`, `extraction/domain/RecoveryReport.java`, `extraction/adapter/in/DocumentRecoveryStartup.java`; tests `DocumentRecoveryServiceTest`, `DocumentRecoveryStartupTest`, `DocumentProcessingChainTest`, `extraction/DocumentRecoveryAcceptanceTest` | `DocumentIntakeService.java`, `DocumentRecordPort.java`, `PostgresDocumentRecordAdapter.java`, `BaymaxProperties.java` (`Recovery`), `medscribe-ai/src/main/resources/application.properties`; `DocumentIntakeServiceTest.java`; `docs/RUNBOOK.md` |
| 5 | `outbound/application/service/UrgencyLines.java`; tests `outbound/InMemoryOutboundMessages.java`, `outbound/application/service/MessagesBundleTest.java`, `wa/application/service/DetailDeliveryGuardsTest.java`, `architecture/WaNeverSendsAComposedBodyTest.java`, `architecture/DeliveryCallersClaimFirstTest.java`, `wa/adapter/out/WhatsAppMessageDeliveryTest.java`, `outbound/application/service/DetailCacheAcceptanceTest.java` | `ExplainDocumentUseCase.java`, `ExplanationService.java`, `ReviewGateService.java`, `OutboundMessagePort.java`, `PostgresOutboundMessageAdapter.java`, `OutboundMessage.java`, `DeliveryOutcome.java`, `WhatsAppMessageDelivery.java`, `WaIntakeService.java`, `messages_bn/en.properties`; tests `ExplanationServiceTest.java`, `WaIntakeServiceTest.java`, `ReviewGateServiceTest.java` |
| 6 | `wa/application/service/WaTextRouter.java`, `wa/domain/WaInbound.java`; tests `WaTextRouterTest`, `wa/adapter/in/WaWebhookDispatchTest`, `wa/adapter/in/EveryInboundTypeIsAnsweredTest` | `WaWebhookHandler.java`, `WaIntakeService.java`, `messages_bn/en.properties`; tests `WaIntakeServiceTest`, `CopyInstructionsHaveHandlersTest` |
| 7 | — | `WaIntakeService.java`, `WaMessagingPort.java`, `WaGraphClient.java`, `messages_bn/en.properties`; tests `WaIntakeServiceTest`, `WaGraphClientTest`, `BaymaxAutoConfigurationTest` (unchanged, run) |
| 8 | `V22__inbound_message.sql`; `wa/domain/InboundMessage.java`, `wa/application/port/out/InboundMessagePort.java`, `wa/adapter/out/persistence/PostgresInboundMessageAdapter.java`, `wa/adapter/in/WaInboundStartupReport.java`, `scripts/wa-replay-callback.sh`; tests `PostgresInboundMessageAdapterTest`, `wa/adapter/in/WaInboundIdempotencyAcceptanceTest`, `WaInboundStartupReportTest`, `WaReplayCallbackScriptTest` | `PhoneHasher.java` (`providerId`), `WaWebhookHandler.java`, `WaIntakeService.java`, `WaConfigValidator.java`, `PostgresHealthRecordAdapter.java`; tests `FamilyDeletionAcceptanceTest`, `WaWebhookRouterTest`, `WaIntakeServiceTest`, `WaConfigValidatorTest`; `docs/RUNBOOK.md` |
| 9 | `V23__outbound_failure_kind.sql`; tests `outbound/FailureKindAcceptanceTest`, `extraction/RecoveredFailureIsToldOnceTest`, `architecture/FailedHasOneWriterTest` | `OutboundMessage.java`, `PostgresOutboundMessageAdapter.java`, `ExplanationService.java`, `ExplainDocumentUseCase.java`, `DocumentRecordPort.java` + adapter (unexplained query), `DocumentRecoveryService.java`, `messages_bn/en.properties`; `ExplanationServiceTest` |
| 10 | — | `BaymaxProperties.java` (`Wa.permits`, `readReceipts`, `graphVersion`), `application.properties`, `WaConfigValidator.java`, `WaMessagingPort.java`, `WaGraphClient.java`, `WaIntakeService.java`, `WhatsAppMessageDelivery.java`; tests `config/WaAllowlistTest` (new), `WaConfigValidatorTest`, `WaGraphClientTest`, `WaIntakeServiceTest`; `docs/RUNBOOK.md` |
| 11 | — | `FamilyAccountService.java` (`normalizeNumber`), `WebAuthService.java` (`verify` fold); tests `FamilyAccountServiceTest`, `WebAuthServiceTest` |
| 12 | `V17__wa_last_inbound.sql`, `web/application/port/out/InboundWindowPort.java`, `web/adapter/out/persistence/PostgresInboundWindowAdapter.java`, `web/domain/CodeRequest.java`, `config/WaAllowlistTest` (Task 10.1), `SignInIsFreeAcceptanceTest`; `web/domain/{CodeIssue,VerifyFailure}.java`, `web/application/port/in/CodeRejectedException.java`, `web/adapter/out/delivery/CompositeOtpDelivery.java`, `web/adapter/in/ui/PhoneDisplay.java`; tests `LogOtpDeliveryTest`, `CompositeOtpDeliveryTest`, `OtpDeliveryWiringTest`, `WaLoginCodeFlowTest`, `SignInPagesTest` | `PostgresHealthRecordAdapter.java` (`deleteFamily`), `WhatsAppMessageDelivery.java` (Task 10.1), `WebAuthUseCase.java`, `WebAuthService.java`, `OtpStorePort.java` + adapter, `LogOtpDelivery.java`, `BaymaxProperties.Auth`/`Wa`, `WaTextRouter.java`, `WaIntakeService.java`, `WaConfigValidator.java`, `WebUiHandler.java`, `AuthHandler.java`, bundles, `baymax.css`, `application.properties`; tests `WebAuthServiceTest`, `WebPersistenceAdaptersTest`, `WaIntakeServiceTest`, `WaConfigValidatorTest`, `BaymaxWebUiRouterTest`, `WebUiHandlerRenderTest`, `MediooBrandTest`, `BaymaxAuthRouterTest`; `docs/MEDIOO.md`, `docs/RUNBOOK.md`, `medscribe.env.example` |

(`baymax/...` is `baymax/src/main/java/com/elioo/baymax/` for main code and `baymax/src/test/java/com/elioo/baymax/` for
tests. Line numbers cited below are from `e4494c1`; after the first PRs merge, re-read the code and anchor on the method,
not the number.)

---

## Preflight — Tanvir, before PR 1 merges (no code)

### Task P.1: Production readiness for the tries

The Phase 0 tries need about eight DONE documents in October, WhatsApp working end to end, and one patient. None of
this can be changed from the app (there is no admin route for a family's plan).

- [ ] **Step 1: Free-tier headroom.** `baymax.free.max-docs-per-month=3` counts DONE documents per UTC month for
  `plan='free'` (`FreeTierService`, `PostgresDocumentRecordAdapter.countInMonth`). Recommended: back up
  `~/medscribe.env`, set `BAYMAX_FREE_MAX_DOCS_PER_MONTH=30`, redeploy the running SHA
  (`IMAGE_TAG=<sha on main> bash ~/deploy.sh`), list the change in the build report, and revert it at the Phase 5 entry
  (roadmap). The alternative, `plan='family'` on his own family, is a data change he makes himself if he prefers it.
- [ ] **Step 2: Exactly one patient.** His family must have one patient (Ma). Two patients make every WhatsApp photo
  `AMBIGUOUS_PATIENT` until Phase 5. Check on the web home page.
- [ ] **Step 3: WhatsApp is live and narrow.** On the box: `grep -cE '^BAYMAX_WA_ENABLED=true$' ~/medscribe.env` is 1;
  `BAYMAX_WA_ALLOWLIST` holds only his number (MEDIOO.md: it must while the gate is off);
  `BAYMAX_GATE_MODE` is blank or `off`; `BAYMAX_AUTH_HMAC_SECRET` is set (PR 8 needs it on the WhatsApp path too).
  `docker logs medscribe-ai --since 72h 2>&1 | grep -c 'error=token_invalid'` is 0, which means `BAYMAX_WA_TOKEN` is a
  permanent system-user token, not a 24-hour one. In the Meta app dashboard the webhook callback URL is on
  `medioo.eliooo.org` and is subscribed to the `messages` field (it already carries audio, sticker, reaction and
  interactive, so no new subscription is needed for PR 6).
- [ ] **Step 4: Branch protection.** Read the pre-merge note above; decide whether PR 0 corrects CLAUDE.md's "main is
  protected" line.
- [ ] **Step 5: Stuck and unexplained documents (optional, read-only, his choice to source `.env.supabase`).**
  `select status, count(*), min(created_at) from baymax.document where status in ('RECEIVED','PROCESSING') group by 1;`
  The first PR 4 deploy re-runs those younger than 7 days. Put the numbers in the build report.

### Task P.2: Synthetic try documents (Claude makes them, outside the repo)

DR-36: no real patient document in the repository. These files are fictional and live outside it, in
`~/medioo-try-docs/` on the dev machine (never under the repo, never committed; `scripts/check-test-fixtures.sh` is not
involved because nothing enters the tree).

- [ ] **Step 1:** Write one HTML file per document (fictional patient "Rokeya Begum", 68, fictional lab "Shapla
  Diagnostics", dates in October 2026), and render each to a 1240 px wide JPEG with headless Chrome
  (`--headless --screenshot --window-size=1240,1754`):

  | File | Content | Used by |
  |---|---|---|
  | `t1-routine-lab.jpg` | CBC + HbA1c, every value inside a two-sided printed range | PR 2 (protected direction), PR 6, PR 8 |
  | `t2-lipid-one-sided.jpg` | Lipid profile: LDL 90 mg/dL against "<100", HDL 52 against ">40", TG 120 against "<150", total cholesterol 180 against "<200" — every value meets its one-sided range | PR 2 (the D35 path) |
  | `t3-this-week-lab.jpg` | Creatinine 1.6 mg/dL against 0.6–1.3 (outside, below the 2× stopgap, so THIS_WEEK), others inside | PR 7 |
  | `t4a-rx-follow-up.jpg` | Prescription, two medicines, "৭ দিন পর আসবেন" | PR 3 (protected direction) |
  | `t4b-rx-no-follow-up.jpg` | Prescription, two medicines, no follow-up line | PR 3 (the defect) |
  | `t5-routine-lab.pdf` | t1 printed to PDF | PR 6 |
  | `t6-letter.docx` | Any short text document | PR 6 |

- [ ] **Step 2:** Tanvir copies them to his phone (AirDrop or a cable). Every try sends a file from the phone's gallery
  or files app, never a photo of a real paper.

---

## PR 0 — Docs: the plan into the repo, and the approved decisions recorded

Branch `docs/medioo-companion-design` from `origin/main`. Docs only; CI still runs the build.

### Task 0.1: Commit the spec, roadmap and plan; write DR-37, DR-39 and the DR-13 amendment

**Files:** stage `docs/superpowers/specs/2026-10-17-medioo-companion-experience-design.md` and
`docs/superpowers/specs/2026-10-17-medioo-tokens-v3.json` (already in the working tree, untracked); create
`docs/superpowers/plans/2026-10-17-medioo-roadmap.md` and `docs/superpowers/plans/2026-10-17-medioo-phase0-1a.md`
(this file); modify `docs/DECISIONS.md`; `CLAUDE.md` only if Tanvir agreed in P.1 Step 4.

- [ ] **Step 1: The number in the spec.** The spec's `sign-in-code` section (around line 246) carries a `wa.me` link with
  the maintainer's own number. Replace the digits with `<BAYMAX_WA_PUBLIC_NUMBER>`. Then
  `grep -rnE '17933(99171)' docs/superpowers` prints nothing.
- [ ] **Step 2: DECISIONS.md** (after DR-36, `docs/DECISIONS.md:517`), in the house format
  (`## DR-<n> | <date> | <title>`, then *Decision*, *Why*, *Supersedes/Amends*):
  - **DR-37 | 2026-10-17 | The companion (chat) is core MVP scope** (D1), recording D4 (DR-16 unchanged), D8 (code
    answers unlimited; 6 model-worded answers per family-month, counted on what was sent), D13 (voice: fixed reply plus
    the red-flag line now, STT later) and "fully automated first" (production `gate=off`; reviewer portal later). It
    amends PRODUCT §4 "MVP in" (`docs/PRODUCT.md:216-229`) and retires the UI rule "no urgency anywhere in the UI"
    (asserted by `WebUiHandlerRenderTest.nothingInterpretiveIsRenderedForAFullDocument`), replaced by spec principle 3.
  - **DR-39 | 2026-10-17 | Sign-in is inbound-first** (D2, with D36 and D37 as recommended): the family texts "কোড";
    `issueCodeFor(sender)` mints for the number WhatsApp proved; the web mints nothing and promises no send (*amended
    2026-10-18 by the PO: the web sends inside the window, see PR 12*); the
    rate-limit message goes only to WhatsApp; the `wa.me` link appears on `/app` only; no code in any log above DEBUG.
    Effective from PR 12.
  - **Under DR-13**, append: "*Amended 2026-10-17 (PO, D5):* the stored, code-computed urgency shows on every surface
    whatever the gate state. On WhatsApp a PENDING NOW/THIS_WEEK document gets its fixed urgency line at once. A PENDING
    **body** is still never delivered: 'Nothing PENDING is ever delivered' now reads 'No PENDING body is ever delivered'."
- [ ] **Step 3: CLAUDE.md (only with Tanvir's yes).** Replace "main is protected; the 'Build and test' check must pass
  and direct pushes are rejected, admins included" with "main is not protected by GitHub on this private repo (the
  API answers 403); every PR runs the manual pre-merge in the current plan: rebased, `mergeStateStatus` CLEAN, checks
  green".
- [ ] **Step 4: Commit and merge.** Do **not** stage `docs/MEDIOO.md` unless Tanvir confirms that its local edit (the
  "Experience design" pointer) belongs here; if he does, stage it explicitly.
  `git add docs/superpowers/specs/2026-10-17-medioo-companion-experience-design.md docs/superpowers/specs/2026-10-17-medioo-tokens-v3.json docs/superpowers/plans/2026-10-17-medioo-roadmap.md docs/superpowers/plans/2026-10-17-medioo-phase0-1a.md docs/DECISIONS.md`
  then `git commit -m "docs: Medioo companion spec, roadmap and Phase 0/1a plan; DR-37, DR-39, DR-13 amendment (D5)"`.
  Run the standard pre-merge.

**Try it on your phone:** open the repo on GitHub mobile and read `docs/superpowers/plans/2026-10-17-medioo-roadmap.md`;
both artifact links open the canvas and the design system. **Post-deploy:** the standard check (a docs PR still
redeploys).

---

## PR 1 — L: no credentials, patient text, numbers or wamids in logs

Branch `fix/no-secrets-in-logs`. Every finding below is live in production today. `IWebFilter` is MedScribe code; DR-2
says MedScribe stays untouched, but a credential leak outranks that, and the PR body says so for the PO.

### Task 1.1: No credential header reaches the log

**Files:** modify `medscribe-ai/src/main/java/com/elioo/healthcare/core/filters/IWebFilter.java` (`logRequest`
`:120-142`, `logResponse` `:144-169`); create `medscribe-ai/src/test/java/com/elioo/healthcare/core/filters/HeaderRedactionTest.java`.

`IWebFilter` logs raw request and response headers at INFO (`:137`, `:161`) and the root level is `info`
(`logback-spring.xml:109`), so every `Cookie: baymax_session=…`, every `Set-Cookie` that opens a session and every
`X-Baymax-Admin-Token` is in the production log. DR-23 redacted query strings, not headers.

- [ ] **Step 1: Write the failing test** (both directions: a filter that hides every header is useless):

```java
package com.elioo.healthcare.core.filters;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * DR-23 covered query strings; headers were still logged raw at INFO on receive and respond. A session cookie,
 * the Set-Cookie that opens a session and the admin token are credentials: any one of them in the log is a
 * working login for whoever can read it. Both directions: a filter that hides every header is useless.
 */
class HeaderRedactionTest {

    private ListAppender<ILoggingEvent> logged;
    private Logger logger;

    @BeforeEach
    void capture() {
        logger = (Logger) LoggerFactory.getLogger(IWebFilter.class);
        logged = new ListAppender<>();
        logged.start();
        logger.addAppender(logged);
    }

    @AfterEach
    void release() {
        logger.detachAppender(logged);
    }

    private String run(MockServerHttpRequest request, String setCookie) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        new IWebFilter(mock(Tracer.class)).filter(exchange, ex -> {
            ex.getResponse().getHeaders().add(HttpHeaders.SET_COOKIE, setCookie);
            return ex.getResponse().setComplete();
        }).block();
        return logged.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
    }

    @Test
    void sessionCookiesAdminTokenAndSetCookieNeverReachTheLog() {
        String log = run(MockServerHttpRequest.get("/app/home")
                        .header(HttpHeaders.COOKIE, "baymax_session=SESSIONTOKEN7f3a; baymax_lang=bn")
                        .header("X-Baymax-Admin-Token", "ADMINTOKEN9c1d")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer BEARER5e2b")
                        .header(HttpHeaders.USER_AGENT, "Mozilla/5.0 test-agent")
                        .build(),
                "baymax_session=NEWSESSION4b8e; Path=/app; HttpOnly");

        assertThat(log).doesNotContain("SESSIONTOKEN7f3a").doesNotContain("ADMINTOKEN9c1d")
                .doesNotContain("BEARER5e2b").doesNotContain("NEWSESSION4b8e");
        // guards against a vacuous pass: the filter did log both lines, with the redaction marker in them
        assertThat(log).contains("Request Received From").contains("Response Sending To").contains("REDACTED");
        // THE OTHER DIRECTION: the headers that make an incident debuggable survive verbatim
        assertThat(log).contains("Mozilla/5.0 test-agent").contains("/app/home");
    }
}
```

- [ ] **Step 2: Run it; it must fail on the token in the log.**
  `./gradlew :medscribe-ai:test --tests '*HeaderRedactionTest'` →
  `Expecting actual: "Request Received From ... Headers : [Cookie:"baymax_session=SESSIONTOKEN7f3a; baymax_lang=bn", ...] not to contain: "SESSIONTOKEN7f3a"`.
  A `NullPointerException` inside the filter (for example the `requireNonNull` at `:166` on the processing-time header)
  has not reached the defect: fix the harness until the failure is the token. Paste the red into the PR body.
- [ ] **Step 3: Implement.** Next to `SECRET_PARAMS` (`:59`) add the header names `cookie, set-cookie, authorization,
  proxy-authorization, x-baymax-admin-token, x-hub-signature-256`, matched on the whole name, case-insensitive. Add
  `static Map<String, List<String>> redactHeaders(HttpHeaders h)`, returning a `LinkedHashMap` copy whose secret header
  values are each replaced by `"REDACTED"`. Pass it instead of `request.getHeaders()` (`:137`) and
  `serverWebExchange.getResponse().getHeaders()` (`:161`).
- [ ] **Step 4:** `./gradlew :medscribe-ai:test --tests '*HeaderRedactionTest' --tests '*QueryStringRedactionTest'` green.
- [ ] **Step 5:** `git add medscribe-ai/src/main/java/com/elioo/healthcare/core/filters/IWebFilter.java medscribe-ai/src/test/java/com/elioo/healthcare/core/filters/HeaderRedactionTest.java`
  then `git commit -m "fix(core): redact cookie, set-cookie and admin-token headers from the request log (DR-23 follow-up)"`.

### Task 1.2: Wamids never reach a log; realistic wamid fixtures

**Files:** create `baymax/src/main/java/com/elioo/baymax/common/redact/Redact.java` (the package is `redact`, not `log`:
CLAUDE.md records `log/` in `.gitignore` swallowing a source directory once), test helpers
`baymax/src/test/java/com/elioo/baymax/wa/WaFixtures.java` and `WaFixturesTest.java`, and
`baymax/src/test/java/com/elioo/baymax/wa/adapter/in/WaLogHygieneTest.java` (in the webhook's package: `WaSignature.sign` is package-private); modify `WaWebhookHandler.java:120-140`,
`WaGraphClient.java:104`, `WhatsAppMessageDelivery.java:66,72`.

A wamid is base64 of the phone number: Meta's sample `wamid.HBgLMTY1MDM4Nzk0MzkV…` decodes to
`\x1c\x18\x0b16503879439\x15…`. Four lines log one: `wa inbound id=` and `wa status id=` (`WaWebhookHandler.java:132,137`),
`wa outbound sent wamid=` (`WaGraphClient.java:104`) and `wa delivered … wamid=` (`WhatsAppMessageDelivery.java:66`).
Today's fixtures (`wamid.OK`, `wamid.TEST`) carry no number, so no test could see this.

- [ ] **Step 1: Write the fixture and prove its shape against Meta's own sample.**

```java
package com.elioo.baymax.wa;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * WhatsApp ids shaped like Meta's: base64 of 0x1c 0x18 len, the sender's digits, then an opaque tail. A wamid CARRIES
 * the phone number, which is why no log may print one; a fixture like "wamid.OK" carries nothing and hides the leak.
 */
public final class WaFixtures {

    private WaFixtures() {
    }

    public static String wamid(String digits) {
        return wamid(digits, 1);
    }

    public static String wamid(String digits, int n) {
        byte[] number = digits.getBytes(StandardCharsets.US_ASCII);
        byte[] tail = String.format("%020X", n).getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x1c);
        b.write(0x18);
        b.write(number.length);
        b.writeBytes(number);
        b.write(0x15);
        b.write(0x02);
        b.write(0x00);
        b.write(0x12);
        b.write(0x18);
        b.write(tail.length);
        b.writeBytes(tail);
        return "wamid." + Base64.getEncoder().encodeToString(b.toByteArray());
    }

    /** What anyone reading a leaked wamid recovers: the digits inside it. */
    public static String decodedDigits(String wamid) {
        byte[] raw = Base64.getDecoder().decode(wamid.substring("wamid.".length()));
        return new String(raw, 3, raw[2], StandardCharsets.US_ASCII);
    }
}
```

```java
package com.elioo.baymax.wa;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WaFixturesTest {

    /** The fixture is only worth having if it is Meta's shape: Meta's documented sample starts exactly like this. */
    @Test
    void theFixtureHasMetasShapeAndCarriesTheNumber() {
        assertThat(WaFixtures.wamid("16503879439")).startsWith("wamid.HBgLMTY1MDM4Nzk0MzkV");
        assertThat(WaFixtures.decodedDigits(WaFixtures.wamid("8801700000000"))).isEqualTo("8801700000000");
        assertThat(WaFixtures.wamid("8801700000000", 1)).isNotEqualTo(WaFixtures.wamid("8801700000000", 2));
    }
}
```

- [ ] **Step 2: Write the failing log test** over all four sites:

```java
package com.elioo.baymax.wa.adapter.in;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.elioo.baymax.common.error.ErrorResponseFilter;
import com.elioo.baymax.common.redact.Redact;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.outbound.domain.OutboundMessage;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.wa.WaFixtures;
import com.elioo.baymax.wa.adapter.out.WhatsAppMessageDelivery;
import com.elioo.baymax.wa.application.port.out.WaMessagingPort;
import com.elioo.baymax.wa.application.service.WaIntakeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A wamid is base64 of the phone number, so a log line that prints one prints the number. Every WhatsApp log site
 * that handles a provider id is driven here with a realistic id, and no line at any level may carry it or its digits.
 */
class WaLogHygieneTest {

    private static final String FROM = "8801700000000";
    private static final String E164 = "+8801700000000";
    private static final String IN = WaFixtures.wamid(FROM, 1);
    private static final String OUT = WaFixtures.wamid(FROM, 2);
    private static final String APP_SECRET = "app-secret-under-test";
    private ListAppender<ILoggingEvent> logged;
    private Logger root;
    private Level before;

    @BeforeEach
    void capture() {
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        before = root.getLevel();
        logged = new ListAppender<>();
        logged.start();
        root.addAppender(logged);
        root.setLevel(Level.TRACE);
    }

    @AfterEach
    void release() {
        root.detachAppender(logged);
        root.setLevel(before);
    }

    private String everything() {
        return logged.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
    }

    private void assertNoWamidAndNoNumber(String log) {
        for (String id : List.of(IN, OUT)) {
            assertThat(log).as("a wamid in the log is the phone number in the log").doesNotContain(id)
                    .doesNotContain(id.substring("wamid.".length(), "wamid.".length() + 16));
        }
        assertThat(log).doesNotContain(FROM).doesNotContain(WaFixtures.decodedDigits(IN));
    }

    @Test
    void theWebhookLogsInboundAndStatusCallbacksByDigestOnly() throws Exception {
        WaIntakeService intake = mock(WaIntakeService.class);
        when(intake.onText(anyString(), anyString())).thenReturn(Mono.just(WaIntakeService.Outcome.ACCEPTED));
        BaymaxProperties props = new BaymaxProperties();
        props.getWa().setEnabled(true);
        props.getWa().setVerifyToken("v");
        props.getWa().setAppSecret(APP_SECRET);
        WebTestClient client = WebTestClient.bindToRouterFunction(new WaWebhookRouter()
                .baymaxWaRoutes(new WaWebhookHandler(props, new ObjectMapper(), intake), new ErrorResponseFilter())).build();
        byte[] body = ("{\"object\":\"whatsapp_business_account\",\"entry\":[{\"changes\":[{\"value\":{"
                + "\"messages\":[{\"id\":\"" + IN + "\",\"from\":\"" + FROM + "\",\"type\":\"text\",\"text\":{\"body\":\"Hi\"}}],"
                + "\"statuses\":[{\"id\":\"" + OUT + "\",\"status\":\"delivered\",\"recipient_id\":\"" + FROM + "\"}]"
                + "}}]}]}").getBytes(StandardCharsets.UTF_8);

        client.post().uri(WaWebhookRouter.WEBHOOK_PATH).header("X-Hub-Signature-256", WaSignature.sign(body, APP_SECRET))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isOk();

        String log = everything();
        assertNoWamidAndNoNumber(log);
        assertThat(log).as("the lines exist, carrying the digest").contains("ref=" + Redact.id(IN)).contains("ref=" + Redact.id(OUT));
    }

    @Test
    void aDeliveredMessageIsLoggedByRowIdAndDigestOnly() {
        WaMessagingPort graph = mock(WaMessagingPort.class);
        when(graph.sendText(anyString(), anyString())).thenReturn(Mono.just(OUT));
        HealthRecordPort records = mock(HealthRecordPort.class);
        UUID family = UUID.randomUUID();
        when(records.findFamily(any())).thenReturn(Mono.just(
                new FamilyAccount(family, E164, "Rahim", FamilyAccount.Plan.FREE, Instant.EPOCH, Instant.EPOCH)));
        BaymaxProperties props = new BaymaxProperties();
        props.getWa().setAllowlist(List.of(E164));
        OutboundMessage row = new OutboundMessage(UUID.randomUUID(), family, null, UUID.randomUUID(),
                OutboundMessage.Kind.EXPLANATION, Urgency.ROUTINE, List.of(), "লেখা", OutboundMessage.GateStatus.RELEASED,
                null, null, null, null, Instant.EPOCH);

        new WhatsAppMessageDelivery(graph, records, props).deliver(row).block();

        String log = everything();
        assertNoWamidAndNoNumber(log);
        assertThat(log).contains("wa delivered id=" + row.id()).contains("ref=" + Redact.id(OUT));
    }
}
```

  Add one more test of the same shape to `WaGraphClientTest`: `aSentMessageIsLoggedByDigest`, using its existing
  `clientReturning("{\"messages\":[{\"id\":\"" + WaFixtures.wamid("8801700000000", 3) + "\"}]}")`, asserting the log
  contains `ref=<digest>` and neither the wamid nor `8801700000000`.
- [ ] **Step 3: Run; it must fail on the wamid.** `./gradlew :baymax:test --tests '*WaLogHygieneTest' --tests '*WaGraphClientTest'`
  → `[a wamid in the log is the phone number in the log] Expecting actual: "...[baymax] wa inbound id=wamid.HBgNODgwMTcwMDAwMDAwMBUC... type=text from=****0000..." not to contain: "wamid.HBgN..."`.
  (Before Step 4 the `Redact` class does not exist: add it first with a body that returns `"-"`, so the red is the
  wamid assertion, not a compile error.)
- [ ] **Step 4: Implement.**

```java
package com.elioo.baymax.common.redact;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Log-safe references. A WhatsApp message id (wamid) is base64 of the phone number, so it never goes in a log; its
 * SHA-256 prefix correlates log lines without revealing it (the opaque tail makes the digest unguessable from a number).
 */
public final class Redact {

    private Redact() {
    }

    public static String id(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return "-";
        }
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(providerId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

  Replace each site: `wa inbound ref={} type={} from={}` with `Redact.id(message.path("id").asText(""))`;
  `wa status ref={} status={} recipient={}`; `wa outbound sent ref={} chars={}`; `wa delivered id={} ref={} urgency={}
  chars={}`. In the same files, the two error lines that print `e.toString()`/`error.toString()`
  (`WaWebhookHandler.subscribe`, `WhatsAppMessageDelivery.java:72`) print `e.getClass().getSimpleName()` instead.
- [ ] **Step 5: Run** `./gradlew :baymax:test --tests 'com.elioo.baymax.wa.*'` green. Commit
  `git add baymax/src/main/java/com/elioo/baymax/common/redact/Redact.java baymax/src/main/java/com/elioo/baymax/wa/adapter/in/WaWebhookHandler.java baymax/src/main/java/com/elioo/baymax/wa/adapter/out/WaGraphClient.java baymax/src/main/java/com/elioo/baymax/wa/adapter/out/WhatsAppMessageDelivery.java baymax/src/test/java/com/elioo/baymax/wa/WaFixtures.java baymax/src/test/java/com/elioo/baymax/wa/WaFixturesTest.java baymax/src/test/java/com/elioo/baymax/wa/adapter/in/WaLogHygieneTest.java baymax/src/test/java/com/elioo/baymax/wa/adapter/out/WaGraphClientTest.java`
  — `git status --ignored --short -- baymax/src | grep '^!!'` must print nothing first (new `common/redact`) — then
  `git commit -m "fix(baymax): log WhatsApp ids by digest — a wamid carries the phone number"`.

### Task 1.3: No medicine text or signed media URL in error logs

**Files:** modify `DocumentIntakeService.java:97-100`, `WaIntakeService.java:65-70`; tests `DocumentIntakeServiceTest`,
`WaIntakeServiceTest`.

`DocumentIntakeService.java:98` logs `e.getMessage()` of explanation failures, and `ExplanationService` throws
`IllegalStateException("medicine instruction is abbreviated, not transcribed: " + elided)`, whose `elided` strings are
`field:value` medicine text (`MedicineTranscription.java:87`). `WaIntakeService.java:68` logs `e.toString()`, which for a
`WebClientResponseException` from `WaGraphClient.download` is `"404 Not Found from GET <signed lookaside URL>"`, the URL
`WaGraphClient`'s class comment promises never to log (DR-23).

- [ ] **Step 1: Write the failing tests.** In `DocumentIntakeServiceTest`:

```java
/** Finding 2: the explanation error's message carries medicine text (MedicineTranscription.elided), logged at ERROR. */
@Test
void anExplanationErrorIsLoggedWithoutThePatientTextItCarries() throws Exception {
    ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(DocumentIntakeService.class);
    var logged = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    logged.start();
    logger.addAppender(logged);
    try {
        Document done = new Document(UUID.randomUUID(), PATIENT, FAMILY, "prescription", null, null, "{}", 0.9,
                Document.Status.DONE, null, "m", null, 1, NOW, NOW);
        when(extraction.process(any(), any())).thenReturn(Mono.just(done));
        when(explainer.explain(done.id())).thenReturn(Mono.error(new IllegalStateException(
                "medicine instruction is abbreviated, not transcribed: [frequency_text:Cap. DDR ১+০+০ ... ১+০+১]")));

        service.accept(PATIENT, List.of(jpeg("a.jpg"))).block();
        verify(nudges, org.mockito.Mockito.timeout(2000)).onDocumentDone(done.id());

        String all = String.join("\n", logged.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList());
        assertThat(all).contains("explanation failed")
                .as("patient medicine text must not reach the log").doesNotContain("Cap. DDR").doesNotContain("১+০+০");
    } finally {
        logger.detachAppender(logged);
    }
}
```

  In `WaIntakeServiceTest` (constructor as on main):

```java
/** onImage logged e.toString(), which for a WebClientResponseException is "404 … from GET <signed URL>". */
@Test
void theIntakeErrorLogCarriesNoExceptionText() {
    ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(WaIntakeService.class);
    var logged = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    logged.start();
    logger.addAppender(logged);
    try {
        familyWith(patient("মা"));
        when(graph.downloadMedia("MEDIA-13")).thenReturn(Mono.error(new RuntimeException(
                "404 Not Found from GET https://lookaside.fbsbx.com/whatsapp_business/attachments/?mid=1&hash=SECRET")));
        service.onImage(FROM, "MEDIA-13").block();
        String all = String.join("\n", logged.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList());
        assertThat(all).as("the line exists").contains("wa intake failed");
        assertThat(all).as("the media URL and its token must never reach the log").doesNotContain("SECRET").doesNotContain("lookaside");
    } finally {
        logger.detachAppender(logged);
    }
}
```

- [ ] **Step 2: Run; each fails on the leaked text.** `./gradlew :baymax:test --tests '*DocumentIntakeServiceTest' --tests '*WaIntakeServiceTest'` →
  `[patient medicine text must not reach the log] Expecting actual: "…explanation failed documentId=…: medicine instruction is abbreviated, not transcribed: [frequency_text:Cap. DDR …]" not to contain: "Cap. DDR"`
  and `[the media URL and its token must never reach the log] Expecting actual: "...wa intake failed mediaId=MEDIA-13 : java.lang.RuntimeException: 404 Not Found from GET https://lookaside…SECRET" not to contain: "SECRET"`.
- [ ] **Step 3: Implement.** `DocumentIntakeService.java:98`:
  `log.error("[baymax] explanation failed documentId={} error={}", done.id(), e.getClass().getSimpleName());` and on the
  next line `log.debug("[baymax] explanation failure detail documentId={}: {}", done.id(), e.getMessage());`.
  `WaIntakeService.java:68`: `log.error("[baymax] wa intake failed mediaId={} error={}", mediaId, e instanceof BaymaxException b ? "BaymaxException:" + b.reason() : e.getClass().getSimpleName());`.
  In the same file of `DocumentIntakeService`, the two neighbouring lines that print `e.getMessage()` — `nudge check failed`
  (`:105`) and `document processing ended in error` (`:113`) — print the class name the same way.
- [ ] **Step 4:** both test classes green.
- [ ] **Step 5:** `git add baymax/src/main/java/com/elioo/baymax/extraction/application/service/DocumentIntakeService.java baymax/src/main/java/com/elioo/baymax/wa/application/service/WaIntakeService.java baymax/src/test/java/com/elioo/baymax/extraction/application/service/DocumentIntakeServiceTest.java baymax/src/test/java/com/elioo/baymax/wa/application/service/WaIntakeServiceTest.java`
  then `git commit -m "fix(baymax): no medicine text or signed media URL in error logs"`.

### Task 1.4: The purged personal number stays purged

**Files:** create `baymax/src/test/java/com/elioo/baymax/NoPersonalNumberInSourcesTest.java`; modify the six test files
that carry it on main: `web/adapter/in/ui/BaymaxWebUiRouterTest.java`, `family/application/service/FamilyAccountServiceTest.java`,
`wa/config/WaConfigValidatorTest.java`, `wa/adapter/out/WaGraphClientTest.java`, `wa/adapter/in/WaWebhookRouterTest.java`,
`wa/application/service/WaIntakeServiceTest.java`.

`docs/HISTORY_REWRITE.md:28,83-85` records the maintainer's number being removed from the tests; it is back in six files.

- [ ] **Step 1: Write the guard** (derived from the tree, with a non-empty guard of its own):

```java
package com.elioo.baymax;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** docs/HISTORY_REWRITE.md: a personal number was purged from history. Every test source is checked, not a list. */
class NoPersonalNumberInSourcesTest {

    /** Spelled in pieces so this file does not itself contain what it forbids. */
    private static final List<String> FORBIDDEN = List.of("17933" + "99171", "৭৯৩৩" + "৯৯১৭১", "****" + "9171");

    @Test
    void noSourceOrTestFileCarriesThePurgedNumber() throws IOException {
        List<Path> roots = Stream.of("src/test", "src/main", "../medscribe-ai/src/test", "../medscribe-ai/src/main", "../scripts")
                .map(Path::of).filter(Files::isDirectory).toList();
        List<Path> files;
        try (Stream<Path> walk = roots.stream().flatMap(r -> {
            try { return Files.walk(r); } catch (IOException e) { throw new IllegalStateException(e); }
        })) {
            files = walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().matches(".*\\.(java|properties|sql|py|sh|json|html|css)$")).toList();
        }
        assertThat(files).as("vacuous if the walk found nothing").hasSizeGreaterThan(100);
        List<String> hits = files.stream().filter(p -> {
            try {
                String s = Files.readString(p, StandardCharsets.UTF_8);
                return FORBIDDEN.stream().anyMatch(s::contains);
            } catch (IOException e) {
                return false;
            }
        }).map(Path::toString).toList();
        assertThat(hits).isEmpty();
    }
}
```

- [ ] **Step 2: Run; it must list the six files.** `./gradlew :baymax:test --tests '*NoPersonalNumberInSourcesTest'` →
  `Expecting empty but was: ["src/test/java/com/elioo/baymax/web/adapter/in/ui/BaymaxWebUiRouterTest.java", …]` (six entries).
- [ ] **Step 3: Replace** in those six files, by hand and file by file (no repo-wide `sed`): the E.164 form → `+8801700000000`,
  digits-only → `8801700000000`, local → `01700000000`, the Bangla-digit form → `০১৭০০০০০০০০`, the masked form → `****0000`,
  the ten-digit form → `1700000000`. In `FamilyAccountServiceTest` lines 261-265 the replacement equals the family
  constant at `:50`; that is fine because those lines test `normalizeNumber` only. Where a test needs two different
  families after the change, give the second `+8801700000009`. In `WaWebhookRouterTest` and `WaGraphClientTest` also
  replace the fixture wamids (`wamid.TEST`, `wamid.OK`, …) with `WaFixtures.wamid(...)`.
- [ ] **Step 4:** `./gradlew :baymax:test` green (the scrubbed tests still pass; the guard is green).
- [ ] **Step 5:** stage the guard and the six files by path; `git commit -m "test(baymax): the purged personal number stays out of every source (guard + scrub)"`.

### Task 1.5: RUNBOOK lists the WhatsApp variables

**Files:** modify `docs/RUNBOOK.md` ("Must be set / Must be blank" tables, which list no `BAYMAX_WA_*` variable today).

- [ ] **Step 1:** Add the existing WhatsApp block: `BAYMAX_WA_ENABLED`, `BAYMAX_WA_TOKEN` (permanent system-user
  token), `BAYMAX_WA_APP_SECRET`, `BAYMAX_WA_VERIFY_TOKEN` (rotation outstanding, DR-23), `BAYMAX_WA_PHONE_NUMBER_ID`,
  `BAYMAX_WA_WABA_ID`, `BAYMAX_WA_ALLOWLIST` (Tanvir's number only while the gate is off), `BAYMAX_GATE_MODE` (blank =
  off). Add a "Leak counters" section with the three `grep -c` lines from the standard post-deploy.
- [ ] **Step 2:** `git add docs/RUNBOOK.md`; `git commit -m "docs(runbook): the WhatsApp variables and the log leak counters"`.
  Run the standard pre-merge.

**Try it on your phone (PR 1):**
- [ ] Before the deploy, Tanvir records the counts: `docker logs medscribe-ai 2>&1 | grep -c 'baymax_session='` and
  `… | grep -c 'wamid\.'` (counts only; never print the lines).
- [ ] After the deploy: sign in at medioo.eliooo.org/app the way it works today (request a code on the web, read it from
  the log as now), browse two pages, then send "বিস্তারিত" on WhatsApp.
- [ ] `docker logs medscribe-ai --since 10m 2>&1 | grep -cE 'baymax_session=|wamid\.'` is 0, and
  `docker logs medscribe-ai --since 10m 2>&1 | grep 'wa inbound ref=' | tail -1` shows a 12-character digest.
- [ ] **Production follow-ups (Tanvir decides, listed in the build report):** if the old counts were non-zero, rotate
  `BAYMAX_ADMIN_TOKEN`; optionally revoke live sessions
  (`UPDATE baymax.web_session SET revoked_at = now() WHERE revoked_at IS NULL`); and do the outstanding DR-23
  `BAYMAX_WA_VERIFY_TOKEN` rotation (Meta dashboard plus env). The login code itself still appears at INFO until PR 12
  (it is how he signs in today); PR 12 removes it.

**Post-deploy:** the standard check.

---

## PR 2 — A1: "within the normal range" only when every value is INSIDE by code (D35)

Branch `fix/baymax-normal-range-claim`. `canSayWithinNormalRange` (`ExplanationService.java:235-258`) only checks that
range strings exist and reads the model's `flag`, so "LDL 90 against `<100`" with a blank or `normal` flag is sent
"সব কিছু স্বাভাবিক সীমার মধ্যে আছে।". The claim needs every value INSIDE by code: value, `ref_low` and `ref_high` each
parse as one reading through `UrgencyService.number` (`UrgencyService.java:258`), with low ≤ value ≤ high. The model's
flag can withdraw the claim but never grant it.

### Task 2.1: The claim needs INSIDE by code, both directions, on the static rule and on the sent body

**Files:** modify `outbound/application/service/ExplanationService.java` (`:235-258`), `UrgencyService.java` (insert
after `:362`), `messages_bn.properties:20-24` (comment only); tests `WithinNormalRangeClaimTest.java`,
`ExplanationServiceTest.java`.

This is a two-direction rule (protect true reassurance, still withhold false reassurance), so both directions are
written before the implementation. The dangerous-direction cases use a **blank** flag, so the claim must be withdrawn
by the code comparison, not by the flag (when two rules can give the same verdict, assert the path). One case has the
model say `normal` over a value that is outside by code, proving code beats the flag.

- [ ] **Step 1: Write the failing tests.** Add to `WithinNormalRangeClaimTest` (keep the five existing tests):

```java
    private static DocumentFacts factsWithUnverified(List<Map<String, Object>> values, int unverifiedValues) {
        java.time.Instant t = java.time.Instant.parse("2026-10-20T09:00:00Z");
        Document document = new Document(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "lab_report", null, null, "{}", 0.9, Document.Status.DONE, null, null, null, 1, t, t,
                new com.elioo.baymax.extraction.domain.VerifiedItems.Unverified(unverifiedValues, 0, 0, 0), null);
        return new DocumentFacts(document, "Test Patient", values, List.of(), Map.of(), "https://x/y");
    }

    // ---- the dangerous direction: each of these was CLAIMED "all normal" before D35 ----

    /** The live gap: a one-sided printed range and a blank flag. 250 is not inside "<100"; nothing compared them. */
    @Test
    void anLdlOf250AgainstALessThan100RangeWithABlankFlagCannotMakeTheClaim() {
        assertThat(ExplanationService.canSayWithinNormalRange(facts(List.of(
                value("LDL", "250", null, "<100", "")))))
                .as("LDL 250 against '<100' was told 'everything is within the normal range' (D35)")
                .isFalse();
    }

    @Test
    void aOneSidedNumericRangeIsNotARangeAValueCanBeInside() {
        assertThat(ExplanationService.canSayWithinNormalRange(facts(List.of(
                value("LDL", "250", null, "100", null))))).isFalse();
        assertThat(ExplanationService.canSayWithinNormalRange(facts(List.of(
                value("LDL", "250", "", "100", null))))).isFalse();
    }

    /** Code beats the model: the flag may withdraw the claim, never grant it. */
    @Test
    void aValueOutsideItsRangeByCodeCannotBeRescuedByTheModelsNormalFlag() {
        assertThat(ExplanationService.canSayWithinNormalRange(facts(List.of(
                value("Creatinine", "2.4", "0.6", "1.1", "normal")))))
                .as("the model's flag said normal; 2.4 is outside 0.6–1.1")
                .isFalse();
    }

    /** No reading means no comparison, and no comparison means no claim (UrgencyService.number refuses these). */
    @Test
    void valuesThatDoNotParseAsOneReadingCannotMakeTheClaim() {
        assertThat(ExplanationService.canSayWithinNormalRange(facts(List.of(value("eGFR", "> 89", "60", "120", ""))))).isFalse();
        assertThat(ExplanationService.canSayWithinNormalRange(facts(List.of(value("HBsAg", "Positive", "Negative", "Negative", ""))))).isFalse();
        assertThat(ExplanationService.canSayWithinNormalRange(facts(List.of(value("TSH", "1,23", "0.5", "1.5", ""))))).isFalse();
        assertThat(ExplanationService.canSayWithinNormalRange(facts(List.of(value("BP", "130 / 80", "90", "140", ""))))).isFalse();
    }

    @Test
    void anInvertedRangeHasNoInside() {
        assertThat(ExplanationService.canSayWithinNormalRange(facts(List.of(value("X", "7", "10", "5", ""))))).isFalse();
    }

    /** DR-28: a value read but dropped for want of a crop is still on the page; the claim would cover it unseen. */
    @Test
    void aDocumentWithAnUnverifiedValueCannotMakeTheClaim() {
        assertThat(ExplanationService.canSayWithinNormalRange(factsWithUnverified(List.of(
                value("HbA1c", "5.2", "4.0", "5.6", "")), 1))).isFalse();
    }

    // ---- the protected direction: a truly all-inside report still says so ----

    @Test
    void aReportWhoseEveryValueIsInsideByCodeMayMakeTheClaimEvenWithBlankFlags() {
        assertThat(ExplanationService.canSayWithinNormalRange(facts(List.of(
                value("HbA1c", "5.2", "4.0", "5.6", ""),
                value("WBC", "6,100", "4,000", "11,000", null),
                value("Creatinine", "1.1", "0.6", "1.1", null)))))       // the boundary is inside
                .isTrue();
        assertThat(ExplanationService.canSayWithinNormalRange(factsWithUnverified(List.of(
                value("HbA1c", "৫.২", "৪.০", "৫.৬", "normal")), 0)))    // Bangla digits fold
                .isTrue();
    }
```

  Add to `ExplanationServiceTest`. Both directions on **what is handed to delivery**, with the lines read from the
  bundle:

```java
    /** D35 on the sent body: a one-sided range is not "inside", so the ROUTINE message is filed, not reassuring. */
    @Test
    void aRoutineReportWithAOneSidedRangeIsSentAsFiledNotAsAllNormal() {
        Map<String, Object> ldl = new java.util.HashMap<>(Map.of("name", "LDL", "value", "250", "ref_high", "<100"));
        done(ldl);
        service.explain(DOC).block();

        ArgumentCaptor<OutboundMessage> sent = ArgumentCaptor.forClass(OutboundMessage.class);
        verify(delivery).deliver(sent.capture());
        Copy copy = new Copy();
        String reassurance = copy.bn("explanation.routine", Map.of()).split("\n")[1];       // সব কিছু স্বাভাবিক সীমার মধ্যে আছে।
        String filedLine = copy.bn("explanation.routine.filed", Map.of()).split("\n")[2];   // কাগজে যা লেখা আছে নিচে তুলে দিলাম।
        assertThat(sent.getValue().urgency()).as("one-sided ranges cannot raise urgency yet (D34)").isEqualTo(Urgency.ROUTINE);
        assertThat(sent.getValue().body())
                .as("LDL 250 against '<100' must not be told everything is normal (D35)")
                .doesNotContain(reassurance)
                .contains(filedLine);
    }

    /** The protected direction, on the sent body: a report whose every value is inside by code still reassures. */
    @Test
    void aRoutineReportWhoseEveryValueIsInsideIsSentWithTheReassuranceLine() {
        done(new java.util.HashMap<>(Map.of("name", "HbA1c", "value", "5.2", "ref_low", "4.0", "ref_high", "5.6")));
        service.explain(DOC).block();

        ArgumentCaptor<OutboundMessage> sent = ArgumentCaptor.forClass(OutboundMessage.class);
        verify(delivery).deliver(sent.capture());
        String reassurance = new Copy().bn("explanation.routine", Map.of()).split("\n")[1];   // সব কিছু স্বাভাবিক সীমার মধ্যে আছে।
        assertThat(sent.getValue().body()).as("a truly all-inside report must keep its reassurance").contains(reassurance);
    }
```

- [ ] **Step 2: Run on the pre-fix source and confirm each red names the defect.**
  `./gradlew :baymax:test --tests '*WithinNormalRangeClaimTest' --tests '*ExplanationServiceTest.aRoutineReport*'`
  - `anLdlOf250AgainstALessThan100RangeWithABlankFlagCannotMakeTheClaim` →
    `[LDL 250 against '<100' was told 'everything is within the normal range' (D35)] Expecting value to be false but was true`
  - `aOneSidedNumericRange…`, `valuesThatDoNotParse…` (first assertion, `"> 89"`), `anInvertedRange…`,
    `aDocumentWithAnUnverifiedValue…` → `Expecting value to be false but was true`
  - `aValueOutsideItsRangeByCode…` → `[the model's flag said normal; 2.4 is outside 0.6–1.1] Expecting value to be false but was true`
  - `aRoutineReportWithAOneSidedRangeIsSentAsFiledNotAsAllNormal` →
    `[LDL 250 against '<100' must not be told everything is normal (D35)] Expecting actual: "রিপোর্টটি পেয়েছি …" not to contain: "সব কিছু স্বাভাবিক সীমার মধ্যে আছে।"`
  - **Green on the pre-fix source, and must stay green:** `aReportWhoseEveryValueIsInsideByCode…`,
    `aRoutineReportWhoseEveryValueIsInsideIsSentWithTheReassuranceLine`, and the five existing tests. They guard the
    protected direction.
  - Any other failure (an NPE, a missing key) has not reached the defect; fix the test first.
- [ ] **Step 3: Implement.** In `UrgencyService.java`, after `criticalKind`:

```java
    /**
     * INSIDE by code (D35, and F2's INSIDE verdict when it lands): the value and BOTH printed bounds parse as one
     * reading each ({@link #number}) and low ≤ value ≤ high. A one-sided range ("<100"), a bound, a pair, an
     * ambiguous comma, a qualitative result or an inverted range is not inside — it is unknown, and unknown never
     * supports a reassurance.
     */
    static boolean insideByCode(Map<String, Object> v) {
        Optional<Double> value = number(v.get("value"));
        Optional<Double> low = number(v.get("ref_low"));
        Optional<Double> high = number(v.get("ref_high"));
        return value.isPresent() && low.isPresent() && high.isPresent()
                && low.get() <= high.get()
                && value.get() >= low.get() && value.get() <= high.get();
    }
```

  In `ExplanationService.java`, replace the body of `canSayWithinNormalRange` and add to its javadoc: "2026-10: the
  claim also needs every value INSIDE by code. Range strings existing and a blank model flag are not evidence; LDL 250
  against '<100' was claimed normal (D35)."

```java
    static boolean canSayWithinNormalRange(DocumentFacts facts) {
        List<Map<String, Object>> values = facts.values();
        if (values == null || values.isEmpty()) {
            return false;
        }
        // DR-28: a value read but not shown (no crop) is still on the page; the claim would cover it unseen
        if (facts.document().unverified().values() > 0) {
            return false;
        }
        for (Map<String, Object> value : values) {
            if (!UrgencyService.insideByCode(value)) {
                return false;
            }
            // the model's flag and critical mark may only WITHDRAW the claim, never grant it
            String flag = value.get("flag") == null ? "" : String.valueOf(value.get("flag"));
            if (!flag.isBlank() && !"normal".equalsIgnoreCase(flag)) {
                return false;
            }
            if (Boolean.TRUE.equals(value.get("critical"))) {
                return false;
            }
        }
        return true;
    }
```

- [ ] **Step 4: Run** `./gradlew :baymax:test --tests '*WithinNormalRangeClaimTest' --tests '*ExplanationServiceTest' --tests '*UrgencyServiceTest'`
  green. Update the comment block at `messages_bn.properties:20-24`: add "and every value INSIDE by code (D35)" (a
  comment only; no copy changes).
- [ ] **Step 5: Commit.** `git add baymax/src/main/java/com/elioo/baymax/outbound/application/service/ExplanationService.java baymax/src/main/java/com/elioo/baymax/outbound/application/service/UrgencyService.java baymax/src/main/resources/baymax/messages_bn.properties baymax/src/test/java/com/elioo/baymax/outbound/application/service/WithinNormalRangeClaimTest.java baymax/src/test/java/com/elioo/baymax/outbound/application/service/ExplanationServiceTest.java`
  then `git commit -m "fix(baymax): \"within the normal range\" only when every value is inside by code (D35)"`. The
  standard pre-merge.

**Try it on your phone (PR 2)** — the try must reach the D35 rule, not another one:
- [ ] Send `t2-lipid-one-sided.jpg` on WhatsApp. When the explanation arrives, **first** open the document on the web
  (medioo.eliooo.org/app, Ma, the newest paper) and confirm two things: the LDL row's range reads `<100` (one-sided,
  stored as the paper prints it), and no urgency line was sent (the explanation is ROUTINE). If the range was stored
  two-sided or the urgency is not ROUTINE, the try went through a different rule: note it in the build report and
  re-try with `t2` once more; do not count it.
- [ ] Then read the WhatsApp reply: it must **not** contain "সব কিছু স্বাভাবিক সীমার মধ্যে আছে।" and must contain
  "কাগজে যা লেখা আছে নিচে তুলে দিলাম।".
- [ ] The other direction: send `t1-routine-lab.jpg`. Its reply still contains "সব কিছু স্বাভাবিক সীমার মধ্যে আছে।".

**Post-deploy:** the standard check. PO note for the build report: "সব কিছু স্বাভাবিক" becomes rarer (one-sided
ranges, qualitative results and dropped values now get the "filed" wording). That is the intended, safe direction.

---

## PR 3 — RX: a prescription with no follow-up is filed, not sent back for a retake

Branch `fix/baymax-prescription-follow-up-gate`. Independent of PR 2 (no shared files); open it in parallel, merge it
after PR 2, rebased.

`missingExpectedSection` (`DocumentExtractionService.java:310-318`) reads `gatingSections`, and DR-27 (#52, "expectedSections
is replaced by gatingSections") set `prescription -> [medicines, follow_up]` (`BaymaxProperties.java:275-280`). So a
clearly read prescription with **no follow-up instruction** gets `NEEDS_RETAKE "nothing was read from the follow_up of
this prescription"`. Batch 1 had a follow-up on 1 of 10 prescriptions. No test covers it: the only prescription with
`follow_up: []` (`DocumentExtractionServiceTest.java:305-323`) sits at overall 0.55, so the overall gate fires first.
And the replay harness cannot see it: `scripts/replay-extraction-gate.py:30-37` still models the pre-DR-27
`EXPECTED_SECTIONS = {"prescription": ["medicines"]}` and takes the minimum confidence over every non-empty section,
`clinical_context` included — a check that cannot fail for the reason it exists. **Until this PR merges, every Phase 0
try uses a lab report.**

### Task 3.1: The harness models the gate that is deployed

**Files:** modify `scripts/replay-extraction-gate.py`; create `baymax/src/test/java/com/elioo/baymax/config/ReplayHarnessMatchesTheGateTest.java`.

- [ ] **Step 1: Write the guard.** `ReplayHarnessMatchesTheGateTest` reads `../scripts/replay-extraction-gate.py`,
  regex-extracts its `GATING_SECTIONS` and `EXPECTED_SECTIONS` dicts and its two thresholds, and asserts they equal
  `new BaymaxProperties().getExtract()` defaults (`gatingSections`, and from Task 3.3 `expectedSections`). It also asserts
  each extracted map is non-empty, so a renamed variable cannot pass vacuously.
- [ ] **Step 2: Run; it fails on the stale model.** → `expected: ["medicines", "follow_up"] but was: ["medicines"]`
  (`GATING_SECTIONS` does not exist yet: the regex finds `EXPECTED_SECTIONS` only and the assertion compares the
  deployed gating list against it).
- [ ] **Step 3: Implement.** Add `GATING_SECTIONS` mirroring `BaymaxProperties.Extract.gatingSections`; `current_gate`
  checks emptiness and confidence on gating sections only, which reproduces today's behaviour, defect included. Add
  `--model fixed`, the Task 3.3 behaviour (emptiness on `EXPECTED_SECTIONS`, confidence on `GATING_SECTIONS`).
- [ ] **Step 4:** the guard is green; `python3 -I scripts/replay-extraction-gate.py --help` works.
- [ ] **Step 5:** `git add scripts/replay-extraction-gate.py baymax/src/test/java/com/elioo/baymax/config/ReplayHarnessMatchesTheGateTest.java`;
  `git commit -m "fix(scripts): the replay harness models the gate that is deployed (DR-27)"`.

### Task 3.2: Tanvir replays production through both models (read-only, his machine)

- [ ] **Step 1:** Tanvir runs, on his machine (Claude never sources `.env.supabase`):
  `set -a; source .env.supabase; set +a; python3 -I scripts/replay-extraction-gate.py --json > /tmp/replay-deployed.json`,
  then the same with `--model fixed > /tmp/replay-fixed.json`.
- [ ] **Step 2:** The build report lists every document whose decision differs between the two, and each one is
  checked against its image **by value** before it counts (CLAUDE.md: a harness result is evidence about the harness
  until a mismatch has been looked at). Expected: prescriptions with medicines and no follow-up move from retake to done;
  nothing else changes.

### Task 3.3: Two-direction tests, then the fix

**Files:** modify `config/BaymaxProperties.java` (`Extract`), `extraction/application/service/DocumentExtractionService.java`
(`missingExpectedSection`); test `DocumentExtractionServiceTest.java`.

- [ ] **Step 1: Write the tests** (the protected direction is the new one; the dangerous directions are green today and
  must stay green):

```java
/**
 * RX: a prescription with medicines and no follow-up instruction is a complete prescription. DR-27 made follow_up
 * a gating section and the emptiness check reads the gating list, so this page was retaken as unclear.
 */
@Test
void aPrescriptionWithNoFollowUpIsDoneNotRetaken() throws Exception {
    replyWith("""
            {"document_type":"prescription","document_date":"2026-09-19","facility":"Shapla Diagnostics",
             "values":[],
             "medicines":[{"name":"Tab. Demoprol","dose_text":"30 mg","frequency_text":"1+0+0",
                           "timing_text":"before meal","source_span":{"page":1,"start":0,"end":5}}],
             "follow_up":[],
             "clinical_context":{"chief_complaint":[],"history":[],"examination":[],
                                 "diagnosis":[],"investigations_advised":[],"advice":[],"referral":null},
             "confidence":{"overall":0.9,"values":0.0,"medicines":0.9,"follow_up":0.0,
                           "clinical_context":0.85}}
            """);

    StepVerifier.create(service.process(received(1), List.of(pageJpeg())))
            .assertNext(d -> {
                // the reason first: emptiness and confidence both yield NEEDS_RETAKE, so the reason names the rule that ran
                assertThat(d.statusReason()).as("no follow-up written is the page, not a failed read; the rule that ran")
                        .isNull();
                assertThat(d.status()).isEqualTo(Document.Status.DONE);
            })
            .verifyComplete();
}

/** The dangerous direction: a prescription with NO medicines read is still a failed read. */
@Test
void aPrescriptionWithNoMedicinesIsStillRetaken() throws Exception {
    replyWith("""
            {"document_type":"prescription","document_date":"2026-09-19","facility":"Shapla Diagnostics",
             "values":[],"medicines":[],
             "follow_up":[{"instruction":"৫ দিন পর আসবেন","due_date":"2026-09-24",
                           "source_span":{"page":1,"start":0,"end":5}}],
             "clinical_context":{"chief_complaint":[],"history":[],"examination":[],
                                 "diagnosis":[],"investigations_advised":[],"advice":[],"referral":null},
             "confidence":{"overall":0.95,"values":0.0,"medicines":0.95,"follow_up":0.9,
                           "clinical_context":0.9}}
            """);

    StepVerifier.create(service.process(received(1), List.of(pageJpeg())))
            .assertNext(d -> {
                assertThat(d.status()).isEqualTo(Document.Status.NEEDS_RETAKE);
                assertThat(d.statusReason()).contains("medicines");
            })
            .verifyComplete();
}

/** Confidence still gates a follow-up that WAS read: a weakly read due date is a weak read. */
@Test
void aPrescriptionWhoseFollowUpWasReadWeaklyIsStillRetaken() throws Exception {
    replyWith("""
            {"document_type":"prescription","document_date":"2026-09-19","facility":"Shapla Diagnostics",
             "values":[],
             "medicines":[{"name":"Tab. Demoprol","dose_text":"30 mg","frequency_text":"1+0+0",
                           "timing_text":"before meal","source_span":{"page":1,"start":0,"end":5}}],
             "follow_up":[{"instruction":"৭ দিন পর আসবেন","due_date":"2026-09-26",
                           "source_span":{"page":1,"start":10,"end":20}}],
             "clinical_context":{"chief_complaint":[],"history":[],"examination":[],
                                 "diagnosis":[],"investigations_advised":[],"advice":[],"referral":null},
             "confidence":{"overall":0.9,"values":0.0,"medicines":0.9,"follow_up":0.5,
                           "clinical_context":0.85}}
            """);

    StepVerifier.create(service.process(received(1), List.of(pageJpeg())))
            .assertNext(d -> {
                assertThat(d.status()).isEqualTo(Document.Status.NEEDS_RETAKE);
                assertThat(d.statusReason()).as("the confidence rule ran, on the section that was read").contains("follow_up");
            })
            .verifyComplete();
}
```

- [ ] **Step 2: Run on the pre-fix source.** `./gradlew :baymax:test --tests '*DocumentExtractionServiceTest'`:
  `aPrescriptionWithNoFollowUpIsDoneNotRetaken` must fail on its **first** assertion, the status reason:
  `[no follow-up written is the page, not a failed read; the rule that ran] Expecting actual: "nothing was read from the follow_up of this prescription" to be null`.
  Both the emptiness rule and the confidence rule produce NEEDS_RETAKE (the fixture's follow_up confidence is 0.0), so a
  red on the verdict alone would not say which rule ran; the reason does. A red naming confidence (`… was read with
  confidence …`) means the fixture reached the other rule: fix the fixture. The other two tests are green and stay green.
- [ ] **Step 3: Implement (S).** Add `Map<String, List<String>> expectedSections` to `BaymaxProperties.Extract`, defaulting
  to `lab_report -> [values], prescription -> [medicines]`, others empty (the pre-#52 table from #47).
  `missingExpectedSection` reads `expectedSections`; `gatingSections` keeps governing confidence only, and a gating
  section that is **empty** is skipped by the confidence check (confidence of nothing is not a weak read). Update the
  javadoc at `BaymaxProperties.java:259-274`. Make `ReplayHarnessMatchesTheGateTest` also compare `EXPECTED_SECTIONS`.
- [ ] **Step 4:** `./gradlew :baymax:test` green; Tanvir reruns Task 3.2 on the branch and attaches both outputs.
- [ ] **Step 5:** `git add baymax/src/main/java/com/elioo/baymax/config/BaymaxProperties.java baymax/src/main/java/com/elioo/baymax/extraction/application/service/DocumentExtractionService.java baymax/src/test/java/com/elioo/baymax/extraction/application/service/DocumentExtractionServiceTest.java baymax/src/test/java/com/elioo/baymax/config/ReplayHarnessMatchesTheGateTest.java scripts/replay-extraction-gate.py`;
  `git commit -m "fix(baymax): a prescription with no follow-up is not a retake (DR-27 correction)"`. The build report
  proposes recording DR-27 with this correction (roadmap). This is a gate change: CLAUDE.md requires the Task 3.2 replay
  output in the PR before merge. The standard pre-merge.

**Try it on your phone (PR 3):**
- [ ] Send `t4b-rx-no-follow-up.jpg`: it is filed and explained, not answered with a retake request.
- [ ] Send `t4a-rx-follow-up.jpg`: filed and explained, with the follow-up date in the reply.
- [ ] Send a deliberately blurred photo of `t4b` (shake the phone): it still gets the retake request (the
  dangerous direction holds).

**Post-deploy:** the standard check.

---

## PR 4 — C2: documents a deploy interrupted finish after the restart (F9b)

Branch `feat/baymax-document-recovery`, from merged PR 3. Migration **V16**.

Processing a document is a detached `subscribe()` (extract → explain → nudges, `DocumentIntakeService.java:89-115`) and
a deploy is `docker rm -f` then `docker run --restart unless-stopped --memory=1200m` (`deploy.sh:43,49-50`): anything in
flight is killed and sits in RECEIVED/PROCESSING forever. This PR lands early because every later merge redeploys.

Facts the design rests on: `DocumentRecordPort.idsWithStatus(status)` exists, oldest first (`:91-92`); `process()` sets
PROCESSING, runs the pipeline and **records** failures as FAILED (`DocumentExtractionService.java:75-83`); crop ids are
deterministic (`v1`, `m1`, …), so storage keys are deterministic (`…/crop-v1.jpg`) and `stored_object.storage_key` is
`UNIQUE` (`V3__stored_object.sql:11`): **a naive re-run of a document killed mid-verify hits a unique violation on its own
leftover crop and ends FAILED** (`recrop` avoids this by deleting crops and items first, `:619-620`); stored pages are
re-readable through `storage.pageBytes(...)` and page completeness is read from the ledger (`objectsOf`), because the
ledger row is written only after a successful put and a missing S3 key is an AWS exception nothing above `adapter.out`
may import.

Design decisions (in the PR body for the PO):
1. **One processing chain.** `DocumentProcessingChain` owns extract → explain → nudge, and `fail(document, reason)` for
   every FAILED a non-extraction path writes. Fresh uploads, recovered documents and recovery's own failures all go
   through it, so PR 9's failure notice is added once and every FAILED writer meets it.
2. **The cutoff is the instant the recovery bean is built**, not `ApplicationReadyEvent` time: Netty serves the webhook
   before the ready event, and a photo arriving in that gap would otherwise be processed twice.
3. **Sequential, bounded, claimed.** `concatMap` (1200 MB container). Each attempt first commits
   `recovery_attempts = recovery_attempts + 1 … WHERE status IN (RECEIVED, PROCESSING) AND updated_at < :cutoff`; after
   `max-attempts` (2) the document is failed `interrupted_repeatedly`, so a document that kills the JVM cannot crash-loop
   a container that restarts unless-stopped.
4. **Too old is left alone.** Documents older than `max-age` (7 days) are reported `skipped_too_old` and not touched:
   no status change, no message to a family about a paper from weeks ago.
5. **Finished but never explained.** A document that reached DONE or NEEDS_RETAKE and died before `explain()` has no
   EXPLANATION or RETAKE row and would stay silent forever. The sweep explains those updated within `explain-max-age`
   (6 hours) and only **counts** older ones (WARN), so a first deploy cannot text a family about last week's paper.

### Task 4.1: V16, `DocumentProcessingChain`, and the intake on it

**Files:** create `baymax/src/main/resources/db/baymax/migration/V16__document_recovery_attempts.sql`,
`extraction/application/service/DocumentProcessingChain.java`,
`baymax/src/test/java/com/elioo/baymax/extraction/application/service/DocumentProcessingChainTest.java`; modify
`DocumentIntakeService.java` (constructor and `startProcessing`), `DocumentRecordPort.java` (+`markRecoveryAttempt`),
`PostgresDocumentRecordAdapter.java`; test `DocumentIntakeServiceTest.java` (constructor only).

- [ ] **Step 1: Preflight.** `git ls-tree --name-only origin/main baymax/src/main/resources/db/baymax/migration | sort -V | tail -1`
  prints `V15__crop_verify_call_purpose.sql`.
- [ ] **Step 2: Write the failing tests** (`DocumentProcessingChainTest`, mocked ports):

```java
class DocumentProcessingChainTest {

    private static final Instant T = Instant.parse("2026-10-17T06:00:00Z");
    private final DocumentExtractionService extraction = mock(DocumentExtractionService.class);
    private final ExplainDocumentUseCase explainer = mock(ExplainDocumentUseCase.class);
    private final NudgeUseCase nudges = mock(NudgeUseCase.class);
    private final DocumentRecordPort documents = mock(DocumentRecordPort.class);
    private final DocumentProcessingChain chain =
            new DocumentProcessingChain(extraction, explainer, nudges, documents, Clock.fixed(T, ZoneOffset.UTC));

    private static Document received() {
        return new Document(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, null, null, null, null,
                Document.Status.RECEIVED, null, null, null, 1, T, T);
    }

    @BeforeEach
    void wire() {
        when(explainer.explain(any())).thenReturn(Mono.empty());
        when(nudges.onDocumentDone(any())).thenReturn(Mono.empty());
        when(documents.update(any())).thenAnswer(i -> Mono.just(i.getArgument(0)));
    }

    /** Today's behaviour, now in one place: whatever extraction ends in, the explainer and the nudges see it. */
    @Test
    void anExtractionThatEndsFailedStillReachesTheExplainer() {
        Document d = received();
        when(extraction.process(eq(d), any())).thenReturn(Mono.just(d.withStatus(Document.Status.FAILED, "llm_error", T)));

        StepVerifier.create(chain.run(d, List.of(new byte[]{1}))).assertNext(r -> assertThat(r.status())
                .isEqualTo(Document.Status.FAILED)).verifyComplete();
        verify(explainer).explain(d.id());
    }

    /** The one writer of FAILED outside extraction: it records the reason, then meets the same explainer. */
    @Test
    void failRecordsTheReasonThenGoesThroughTheExplainer() {
        Document d = received();

        StepVerifier.create(chain.fail(d, "interrupted_repeatedly")).assertNext(r -> {
            assertThat(r.status()).isEqualTo(Document.Status.FAILED);
            assertThat(r.statusReason()).isEqualTo("interrupted_repeatedly");
        }).verifyComplete();
        InOrder order = inOrder(documents, explainer);
        order.verify(documents).update(argThat(u -> u.status() == Document.Status.FAILED));
        order.verify(explainer).explain(d.id());
    }

    /** An explainer error never undoes the extraction, and the log carries the class only (PR 1). */
    @Test
    void anExplainerErrorIsSwallowedAndTheDocumentStillFinishes() {
        Document d = received();
        when(extraction.process(eq(d), any())).thenReturn(Mono.just(d.withStatus(Document.Status.DONE, null, T)));
        when(explainer.explain(d.id())).thenReturn(Mono.error(new IllegalStateException("Cap. DDR ১+০+০")));

        StepVerifier.create(chain.run(d, List.of(new byte[]{1}))).expectNextCount(1).verifyComplete();
        verify(nudges).onDocumentDone(d.id());
    }
}
```

- [ ] **Step 3: Run.** `./gradlew :baymax:test --tests '*DocumentProcessingChainTest'` → compile error
  `cannot find symbol: class DocumentProcessingChain`. New behaviour (a moved chain plus `fail`), not a regression guard.
- [ ] **Step 4: Implement.**
  - V16:

```sql
-- F9b (Phase 0, PR 4): how many times a startup sweep has re-run this document. Incremented and committed BEFORE
-- the work, so a document that kills the JVM (1200 MB container, deploy.sh:50) is retried a bounded number of times
-- and then failed, instead of crash-looping a container that restarts unless-stopped.
ALTER TABLE ${flyway:defaultSchema}.document ADD COLUMN recovery_attempts integer NOT NULL DEFAULT 0;
```

  - `DocumentProcessingChain` (`@Slf4j @Component @RequiredArgsConstructor`; fields `DocumentExtractionService
    extraction`, `ExplainDocumentUseCase explainer`, `NudgeUseCase nudges`, `DocumentRecordPort documents`,
    `Clock clock`). `public Mono<Document> run(Document document, List<byte[]> pages)` is the body of
    `DocumentIntakeService.startProcessing` (extract → explain → nudges, with PR 1's class-only error logs) **without**
    `.subscribeOn` and `.subscribe`. `public Mono<Document> fail(Document document, String reason)` is
    `documents.update(document.withStatus(FAILED, reason, clock.instant()))` followed by the same explain step (errors
    swallowed and logged by class). Javadoc: "every FAILED written outside DocumentExtractionService is written here, so
    whatever answers a FAILED document (PR 9) is reached by all of them".
  - `DocumentIntakeService`: the constructor loses `extraction`, `explainer` and `nudges` and gains
    `DocumentProcessingChain chain` (appended last). `startProcessing` becomes
    `chain.run(document, pages).subscribeOn(Schedulers.boundedElastic()).subscribe(<the two log lambdas>)`.
    `DocumentIntakeServiceTest.setUp` builds `new DocumentProcessingChain(extraction, explainer, nudges, documents,
    Clock.fixed(NOW, ZoneOffset.UTC))` from its existing mocks, so every existing assertion keeps its meaning.
  - `DocumentRecordPort.markRecoveryAttempt(UUID documentId, Instant interruptedBefore)` → `Mono<Integer>`, javadoc
    "increments and commits; empty when the document is no longer RECEIVED/PROCESSING or was updated after the
    cutoff". Adapter: `UPDATE %s.document SET recovery_attempts = recovery_attempts + 1 WHERE id = :id AND status IN
    ('RECEIVED','PROCESSING') AND updated_at < :before RETURNING recovery_attempts`.
- [ ] **Step 5: Run** `./gradlew :baymax:test --tests '*DocumentProcessingChainTest' --tests '*DocumentIntakeServiceTest' --tests '*ExtractionPipelineAcceptanceTest'`
  green. Commit (stage the migration, the chain, its test, `DocumentIntakeService`, its test, the port and adapter):
  `feat(baymax): one document processing chain, and a recovery-attempt counter (V16)`.

### Task 4.2: The recovery service and its startup trigger

**Files:** create `extraction/application/port/in/DocumentRecoveryUseCase.java`, `extraction/domain/RecoveryReport.java`,
`extraction/application/service/DocumentRecoveryService.java`, `extraction/adapter/in/DocumentRecoveryStartup.java`,
tests `DocumentRecoveryServiceTest.java`, `extraction/adapter/in/DocumentRecoveryStartupTest.java`; modify
`BaymaxProperties.java` (`Recovery`), `medscribe-ai/src/main/resources/application.properties`.

- [ ] **Step 1: Write the failing tests. Both directions go in before any code.** `DocumentRecoveryServiceTest`:

```java
class DocumentRecoveryServiceTest {

    private static final Instant BOOT = Instant.parse("2026-10-17T06:00:00Z");
    private static final UUID FAMILY = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};

    private final DocumentRecordPort documents = mock(DocumentRecordPort.class);
    private final DocumentStorageUseCase storage = mock(DocumentStorageUseCase.class);
    private final DocumentProcessingChain chain = mock(DocumentProcessingChain.class);
    private final BaymaxProperties properties = new BaymaxProperties();
    private DocumentRecoveryService service;

    @BeforeEach
    void wire() {
        service = new DocumentRecoveryService(documents, storage, chain, properties,
                Clock.fixed(BOOT.plusSeconds(30), ZoneOffset.UTC));
        when(documents.idsWithStatus(any())).thenReturn(Flux.empty());
        when(documents.update(any())).thenAnswer(i -> Mono.just(i.getArgument(0)));
        when(documents.deleteItems(any())).thenReturn(Mono.just(0L));
        when(storage.deleteCrops(any(), any(), any())).thenReturn(Mono.just(new DeletionReport("p/", 0, 0)));
        when(chain.fail(any(), anyString())).thenAnswer(i -> Mono.just(((Document) i.getArgument(0))
                .withStatus(Document.Status.FAILED, i.getArgument(1), BOOT)));
    }

    private Document stuck(Document.Status status, Instant updatedAt, int pages) {
        UUID id = UUID.randomUUID();
        Document d = new Document(id, PATIENT, FAMILY, null, null, null, null, null, status, null, null, null,
                pages, updatedAt.minusSeconds(5), updatedAt);
        when(documents.find(id)).thenReturn(Mono.just(d));
        return d;
    }

    private static StoredObject page(UUID documentId, int n) {
        return new StoredObject(UUID.randomUUID(), FAMILY, PATIENT, documentId, ObjectKind.PAGE, n, null,
                "k" + n, "image/jpeg", 4, BOOT);
    }

    /** The other direction of the pages rule: an S3 outage is an error to retry, never "pages missing" for good. */
    @Test
    void aTransientStorageFailureIsAnErrorNotAPermanentFailure() {
        Document d = stuck(Document.Status.PROCESSING, BOOT.minusSeconds(40), 1);
        when(documents.idsWithStatus(Document.Status.PROCESSING)).thenReturn(Flux.just(d.id()));
        when(documents.markRecoveryAttempt(d.id(), BOOT)).thenReturn(Mono.just(1));
        when(storage.objectsOf(d.id())).thenReturn(Flux.just(page(d.id(), 1)));
        when(storage.pageBytes(FAMILY, PATIENT, d.id(), 1)).thenReturn(Mono.error(new RuntimeException("503 SlowDown")));

        StepVerifier.create(service.recoverInterrupted(BOOT).map(RecoveryReport::outcome))
                .expectNext("error").verifyComplete();
        verify(chain, never()).fail(any(), anyString());
    }

    @Test
    void aDocumentInterruptedBeforeThisBootIsReRunFromItsStoredPagesAfterItsLeftoverCropsAreCleared() {
        Document d = stuck(Document.Status.PROCESSING, BOOT.minusSeconds(40), 1);
        when(documents.idsWithStatus(Document.Status.PROCESSING)).thenReturn(Flux.just(d.id()));
        when(documents.markRecoveryAttempt(d.id(), BOOT)).thenReturn(Mono.just(1));
        when(storage.objectsOf(d.id())).thenReturn(Flux.just(page(d.id(), 1)));
        when(storage.pageBytes(FAMILY, PATIENT, d.id(), 1)).thenReturn(Mono.just(JPEG));
        when(chain.run(any(), any())).thenReturn(Mono.just(d.withStatus(Document.Status.DONE, null, BOOT.plusSeconds(60))));

        StepVerifier.create(service.recoverInterrupted(BOOT))
                .assertNext(r -> assertThat(r.outcome()).isEqualTo("reprocessed"))
                .verifyComplete();

        InOrder order = inOrder(documents, storage, chain);
        order.verify(documents).markRecoveryAttempt(d.id(), BOOT);          // committed before any work
        order.verify(storage).deleteCrops(FAMILY, PATIENT, d.id());        // or the re-run collides with its own crop-v1
        order.verify(documents).deleteItems(d.id());
        order.verify(chain).run(eq(d), argThat(pages -> pages.size() == 1 && Arrays.equals(pages.get(0), JPEG)));
    }

    @Test
    void aDocumentThisInstanceIsAlreadyProcessingIsNeverTouched() {
        Document mine = stuck(Document.Status.PROCESSING, BOOT.plusSeconds(1), 1);   // updated after this bean was built
        when(documents.idsWithStatus(Document.Status.PROCESSING)).thenReturn(Flux.just(mine.id()));

        StepVerifier.create(service.recoverInterrupted(BOOT)).verifyComplete();

        verify(documents, never()).markRecoveryAttempt(any(), any());
        verify(chain, never()).run(any(), any());
        verify(storage, never()).deleteCrops(any(), any(), any());
    }

    @Test
    void aDocumentInterruptedTooOftenIsFailedNotRetried() {
        Document d = stuck(Document.Status.RECEIVED, BOOT.minusSeconds(40), 1);
        when(documents.idsWithStatus(Document.Status.RECEIVED)).thenReturn(Flux.just(d.id()));
        when(documents.markRecoveryAttempt(d.id(), BOOT)).thenReturn(Mono.just(3));   // max-attempts is 2

        StepVerifier.create(service.recoverInterrupted(BOOT))
                .assertNext(r -> assertThat(r.outcome()).isEqualTo("failed_interrupted_repeatedly"))
                .verifyComplete();

        verify(chain).fail(d, "interrupted_repeatedly");   // the one FAILED writer outside extraction (PR 9 answers it)
        verify(chain, never()).run(any(), any());
    }

    @Test
    void aDocumentWhosePagesWereNeverAllStoredIsFailedWithAReason() {
        Document d = stuck(Document.Status.RECEIVED, BOOT.minusSeconds(40), 2);
        when(documents.idsWithStatus(Document.Status.RECEIVED)).thenReturn(Flux.just(d.id()));
        when(documents.markRecoveryAttempt(d.id(), BOOT)).thenReturn(Mono.just(1));
        when(storage.objectsOf(d.id())).thenReturn(Flux.just(page(d.id(), 1)));   // killed between page 1 and page 2

        StepVerifier.create(service.recoverInterrupted(BOOT))
                .assertNext(r -> assertThat(r.outcome()).isEqualTo("failed_pages_missing"))
                .verifyComplete();
        verify(chain).fail(d, "pages_missing_after_restart");
        verify(chain, never()).run(any(), any());
    }

    @Test
    void oneDocumentErroringDoesNotStopTheSweep() {
        Document a = stuck(Document.Status.PROCESSING, BOOT.minusSeconds(50), 1);
        Document b = stuck(Document.Status.PROCESSING, BOOT.minusSeconds(40), 1);
        when(documents.idsWithStatus(Document.Status.PROCESSING)).thenReturn(Flux.just(a.id(), b.id()));
        when(documents.markRecoveryAttempt(any(), eq(BOOT))).thenReturn(Mono.just(1));
        when(storage.objectsOf(any())).thenAnswer(i -> Flux.just(page(i.getArgument(0), 1)));
        when(storage.pageBytes(any(), any(), any(), anyInt())).thenReturn(Mono.just(JPEG));
        when(chain.run(eq(a), any())).thenReturn(Mono.error(new IllegalStateException("boom")));
        when(chain.run(eq(b), any())).thenReturn(Mono.just(b.withStatus(Document.Status.DONE, null, BOOT)));

        StepVerifier.create(service.recoverInterrupted(BOOT).map(RecoveryReport::outcome))
                .expectNext("error", "reprocessed").verifyComplete();
    }

    @Test
    void aDocumentOlderThanTheMaxAgeIsLeftAloneAndReported() {
        properties.getRecovery().setMaxAge(Duration.ofDays(7));
        Document old = stuck(Document.Status.PROCESSING, BOOT.minus(Duration.ofDays(30)), 1);
        when(documents.idsWithStatus(Document.Status.PROCESSING)).thenReturn(Flux.just(old.id()));

        StepVerifier.create(service.recoverInterrupted(BOOT))
                .assertNext(r -> assertThat(r.outcome()).isEqualTo("skipped_too_old"))
                .verifyComplete();
        verify(documents, never()).markRecoveryAttempt(any(), any());
        verify(documents, never()).update(any());
        verify(chain, never()).fail(any(), anyString());   // a paper from weeks ago never messages the family
    }
}
```

  `DocumentRecoveryStartupTest`:

```java
    @Test
    void theCutoffIsTheInstantTheBeanWasBuiltNotWhenTheAppBecameReady() {
        Instant built = Instant.parse("2026-10-17T06:00:00Z");
        DocumentRecoveryUseCase recovery = mock(DocumentRecoveryUseCase.class);
        when(recovery.recoverInterrupted(any())).thenReturn(Flux.empty());
        DocumentRecoveryStartup startup = new DocumentRecoveryStartup(recovery, new BaymaxProperties(),
                Clock.fixed(built, ZoneOffset.UTC));

        startup.onReady();   // Netty has been serving the webhook since before this event

        verify(recovery, timeout(2000)).recoverInterrupted(built);
    }

    @Test
    void disabledMeansNoSweep() {
        BaymaxProperties p = new BaymaxProperties();
        p.getRecovery().setEnabled(false);
        DocumentRecoveryUseCase recovery = mock(DocumentRecoveryUseCase.class);
        new DocumentRecoveryStartup(recovery, p, Clock.systemUTC()).onReady();
        verify(recovery, after(500).never()).recoverInterrupted(any());
    }
```

- [ ] **Step 2: Run** `./gradlew :baymax:test --tests '*DocumentRecovery*'` → compile errors for
  `DocumentRecoveryService`, `DocumentRecoveryUseCase`, `RecoveryReport`. New behaviour.
- [ ] **Step 3: Implement.**
  - `BaymaxProperties`: `private Recovery recovery = new Recovery();` with
    `@Data public static class Recovery { private boolean enabled = true; private int maxAttempts = 2; private java.time.Duration maxAge = java.time.Duration.ofDays(7); private java.time.Duration explainMaxAge = java.time.Duration.ofHours(6); }`.
    `application.properties`: `baymax.recovery.enabled=${BAYMAX_RECOVERY_ENABLED:true}`, `baymax.recovery.max-attempts=2`,
    `baymax.recovery.max-age=P7D`, `baymax.recovery.explain-max-age=PT6H`.
  - `DocumentRecoveryService implements DocumentRecoveryUseCase` (`Flux<RecoveryReport> recoverInterrupted(Instant cutoff)`);
    constructor `(DocumentRecordPort documents, DocumentStorageUseCase storage, DocumentProcessingChain chain,
    BaymaxProperties properties, Clock clock)`:

```java
    @Override
    public Flux<RecoveryReport> recoverInterrupted(Instant cutoff) {
        Instant tooOld = clock.instant().minus(properties.getRecovery().getMaxAge());
        return Flux.concat(documents.idsWithStatus(Document.Status.PROCESSING), documents.idsWithStatus(Document.Status.RECEIVED))
                .concatMap(documents::find)                                    // sequential: 1200 MB container
                .filter(d -> d.updatedAt().isBefore(cutoff))                  // this instance owns everything newer
                .concatMap(d -> d.createdAt().isBefore(tooOld)
                        ? Mono.just(new RecoveryReport(d.id(), "skipped_too_old"))
                        : recover(d, cutoff).onErrorResume(e -> {
                            log.error("[baymax] recovery errored documentId={}: {}", d.id(), e.getClass().getSimpleName());
                            return Mono.just(new RecoveryReport(d.id(), "error"));
                        }))
                .doOnNext(r -> log.info("[baymax] recovery documentId={} outcome={}", r.documentId(), r.outcome()));
    }

    private Mono<RecoveryReport> recover(Document d, Instant cutoff) {
        return documents.markRecoveryAttempt(d.id(), cutoff)               // committed before the work
                .flatMap(attempt -> attempt > properties.getRecovery().getMaxAttempts()
                        ? chain.fail(d, "interrupted_repeatedly").thenReturn(new RecoveryReport(d.id(), "failed_interrupted_repeatedly"))
                        : storedPages(d)
                                .flatMap(pages -> storage.deleteCrops(d.familyId(), d.patientId(), d.id())
                                        .then(documents.deleteItems(d.id()))
                                        .then(chain.run(d, pages))
                                        .thenReturn(new RecoveryReport(d.id(), "reprocessed")))
                                .switchIfEmpty(Mono.defer(() -> chain.fail(d, "pages_missing_after_restart")
                                        .thenReturn(new RecoveryReport(d.id(), "failed_pages_missing")))));
    }

    /**
     * Every page or nothing: a partial page list would extract half a report as if it were whole. Completeness is
     * read from the LEDGER (DocumentStorageService.store writes the row only after the put succeeded), not from a
     * failed GET: the S3 adapter reports a missing key as an AWS NoSuchKeyException, which nothing above adapter.out
     * may import — and a transient S3 outage must stay an error (retried next restart, bounded by attempts), never
     * be misread as "pages missing" and fail the document for good.
     */
    private Mono<List<byte[]>> storedPages(Document d) {
        int expected = Math.max(1, d.pageCount());
        return storage.objectsOf(d.id())
                .filter(o -> o.kind() == ObjectKind.PAGE && o.pageNo() != null)
                .map(StoredObject::pageNo)
                .collect(java.util.stream.Collectors.toSet())
                .flatMap(stored -> java.util.stream.IntStream.rangeClosed(1, expected).allMatch(stored::contains)
                        ? Flux.range(1, expected)
                                .concatMap(n -> storage.pageBytes(d.familyId(), d.patientId(), d.id(), n))
                                .collectList()
                        : Mono.empty());
    }

    // No private fail(): every FAILED written here goes through DocumentProcessingChain.fail, which PR 9's notice meets.
```

  - `DocumentRecoveryStartup` (`extraction/adapter/in`, `@Component`): the constructor takes the use case, the
    properties and `Clock`, and stores `this.cutoff = clock.instant()`.
    `@EventListener(ApplicationReadyEvent.class) public void onReady()` returns at once when
    `!properties.getRecovery().isEnabled()`; otherwise
    `recovery.recoverInterrupted(cutoff).subscribeOn(Schedulers.boundedElastic()).count().subscribe(n -> log.info("[baymax] recovery sweep done documents={}", n), e -> log.error("[baymax] recovery sweep failed: {}", e.getClass().getSimpleName()))`.
    Detached, so the health check (`deploy.sh` waits 90 s) never waits on model calls. `ApplicationContextRunner`
    publishes no `ApplicationReadyEvent`, so no existing Testcontainers test starts a sweep.
- [ ] **Step 4: Run** `./gradlew :baymax:test --tests '*DocumentRecovery*'` green.
- [ ] **Step 5: Commit** (stage the five new main files, the two tests, `BaymaxProperties`, `application.properties`;
  `git status --ignored --short -- baymax/src | grep '^!!'` prints nothing, new `extraction/adapter/in`):
  `feat(baymax): documents a redeploy interrupted are re-run from their stored pages at startup, bounded and claimed (F9b)`.

### Task 4.3: Real Postgres and MinIO — the leftover crop, and the replay

**Files:** create `baymax/src/test/java/com/elioo/baymax/extraction/DocumentRecoveryAcceptanceTest.java` (copy
`runner()`, the bucket setup, `seedFamily`, `pageJpeg` and `scalar` from `ExtractionPipelineAcceptanceTest.java:61-260`,
and reuse its stubs with `.withUserConfiguration(ExtractionPipelineAcceptanceTest.StubbedProviders.class)`; they are
package-private in the same package).

- [ ] **Step 1: Write the test** (this is the path with the `stored_object.storage_key` UNIQUE constraint):

```java
    @Test
    void aDocumentKilledMidCropIsReprocessedToDoneNotFailedOnItsOwnLeftoverCrop() {
        runner().run(context -> {
            UUID family = UUID.randomUUID();
            UUID patient = UUID.randomUUID();
            seedFamily(family, patient);
            DocumentRecordPort records = context.getBean(DocumentRecordPort.class);
            DocumentStorageUseCase storage = context.getBean(DocumentStorageUseCase.class);
            Instant beforeBoot = Instant.now().minusSeconds(600);

            // what `docker rm -f` (deploy.sh:43) leaves: PROCESSING, page stored, first crop already in bucket + ledger
            Document doc = records.create(Document.received(patient, family, 1, beforeBoot)).block();
            records.update(doc.withStatus(Document.Status.PROCESSING, null, beforeBoot)).block();
            storage.storePage(family, patient, doc.id(), 1, pageJpeg()).block();
            storage.storeCrop(family, patient, doc.id(), "v1", pageJpeg()).block();

            List<RecoveryReport> reports = context.getBean(DocumentRecoveryUseCase.class)
                    .recoverInterrupted(Instant.now()).collectList().block(Duration.ofSeconds(60));

            assertThat(scalar("select coalesce(status_reason, '') from baymax.document where id = '" + doc.id() + "'"))
                    .as("why the recovered document did not finish").isEmpty();
            assertThat(scalar("select status from baymax.document where id = '" + doc.id() + "'")).isEqualTo("DONE");
            assertThat(scalar("select count(*) from baymax.stored_object where document_id = '" + doc.id() + "' and kind = 'crop'"))
                    .as("one crop per value, the leftover replaced, not duplicated").isEqualTo("1");
            assertThat(scalar("select count(*) from baymax.observation where document_id = '" + doc.id() + "'")).isEqualTo("1");
            assertThat(scalar("select recovery_attempts from baymax.document where id = '" + doc.id() + "'")).isEqualTo("1");
            assertThat(reports).extracting(RecoveryReport::outcome).containsExactly("reprocessed");
        });
    }

    @Test
    void aDocumentReceivedAfterTheCutoffIsLeftForTheSubscriptionAlreadyRunningIt() {
        runner().run(context -> {
            UUID family = UUID.randomUUID();
            UUID patient = UUID.randomUUID();
            seedFamily(family, patient);
            Instant cutoff = Instant.now().minusSeconds(60);
            Document fresh = context.getBean(DocumentRecordPort.class)
                    .create(Document.received(patient, family, 1, Instant.now())).block();

            context.getBean(DocumentRecoveryUseCase.class).recoverInterrupted(cutoff).collectList().block();

            assertThat(scalar("select status || '/' || recovery_attempts from baymax.document where id = '" + fresh.id() + "'"))
                    .isEqualTo("RECEIVED/0");
        });
    }
```

- [ ] **Step 2: Run** green with Task 4.2's implementation (Docker running).
- [ ] **Step 3: Replay the leftover-crop guard against the defect.** Comment out the `storage.deleteCrops(...)` and
  `documents.deleteItems(...)` steps in `DocumentRecoveryService.recover` and rerun
  `aDocumentKilledMidCropIsReprocessedToDoneNotFailedOnItsOwnLeftoverCrop`. It must fail on
  `[why the recovered document did not finish] Expecting empty but was: "…duplicate key value violates unique constraint "stored_object_storage_key_key"…"`.
  `shortReason` truncates to 180 characters (`DocumentExtractionService.java:597-602`); if Spring's translated message
  pushes the constraint name past the cut, the replay is **not yet proof**: assert instead on the
  `[baymax] extraction failed documentId=…` line captured with a `ListAppender`, and confirm it names
  `stored_object_storage_key_key`. Restore the lines; paste the red into the PR body.
- [ ] **Step 4:** `./gradlew :baymax:test --tests '*DocumentRecoveryAcceptanceTest' --tests '*ExtractionPipelineAcceptanceTest'` green.
- [ ] **Step 5:** `git add baymax/src/test/java/com/elioo/baymax/extraction/DocumentRecoveryAcceptanceTest.java`;
  `test(baymax): recovery on real Postgres and MinIO replaces a leftover crop instead of failing on it`.

### Task 4.4: Finished but never explained — explain the recent ones, count the rest

**Files:** modify `DocumentRecordPort.java` (+`unexplainedSince`), `PostgresDocumentRecordAdapter.java`,
`DocumentRecoveryService.java`, `DocumentProcessingChain.java` (+`explainOnly`); tests `DocumentRecoveryServiceTest`,
`DocumentRecoveryAcceptanceTest`.

- [ ] **Step 1: Write the failing tests, both directions.** In `DocumentRecoveryServiceTest`:

```java
    /** Died after DONE, before explain(): no message row exists. Recent → explained once through the chain. */
    @Test
    void aRecentDocumentThatFinishedButWasNeverExplainedIsExplainedOnce() {
        Document d = stuck(Document.Status.DONE, BOOT.minusSeconds(600), 1);
        when(documents.unexplainedSince(BOOT.plusSeconds(30).minus(properties.getRecovery().getExplainMaxAge()), BOOT))
                .thenReturn(Flux.just(d.id()));
        when(chain.explainOnly(d)).thenReturn(Mono.just(d));

        StepVerifier.create(service.recoverInterrupted(BOOT).map(RecoveryReport::outcome))
                .expectNext("explained").verifyComplete();
        verify(chain, times(1)).explainOnly(d);
        verify(chain, never()).run(any(), any());
    }

    /** The other direction: an unexplained document older than the window is only counted, never messaged. */
    @Test
    void anOldUnexplainedDocumentIsCountedNotMessaged() {
        when(documents.unexplainedSince(any(), any())).thenReturn(Flux.empty());
        when(documents.countUnexplainedBefore(BOOT.plusSeconds(30).minus(properties.getRecovery().getExplainMaxAge())))
                .thenReturn(Mono.just(3L));

        StepVerifier.create(service.recoverInterrupted(BOOT)).verifyComplete();
        verify(chain, never()).explainOnly(any());
    }
```

  (`wire()` gains `when(documents.unexplainedSince(any(), any())).thenReturn(Flux.empty())` and
  `when(documents.countUnexplainedBefore(any())).thenReturn(Mono.just(0L))`, so the earlier tests are unaffected.)
  In `DocumentRecoveryAcceptanceTest`, `unexplainedSinceFindsADoneDocumentWithNoMessageAndNotOneWithAnExplanation`: seed
  two DONE documents inside the window and one outside it; give the first an `outbound_message` row of kind
  `explanation` by SQL; assert `unexplainedSince(...)` returns exactly the second, and `countUnexplainedBefore(...)`
  counts the third.
- [ ] **Step 2: Run** → compile errors on `unexplainedSince`, `countUnexplainedBefore`, `explainOnly`. New behaviour.
- [ ] **Step 3: Implement.** Port and adapter:
  `Flux<UUID> unexplainedSince(Instant since, Instant before)` →
  `SELECT d.id FROM %s.document d WHERE d.status IN ('DONE','NEEDS_RETAKE') AND d.updated_at >= :since AND d.updated_at < :before AND NOT EXISTS (SELECT 1 FROM %s.outbound_message m WHERE m.document_id = d.id AND m.kind IN ('explanation','retake')) ORDER BY d.updated_at`;
  `Mono<Long> countUnexplainedBefore(Instant since)` with the same `NOT EXISTS` and `d.updated_at < :since`.
  `DocumentProcessingChain.explainOnly(Document d)` is the explain → nudges half of `run`. In `DocumentRecoveryService`,
  rename Task 4.2's `recoverInterrupted` body to `private Flux<RecoveryReport> interrupted(Instant cutoff)` (without its
  final `doOnNext`) and add:

```java
    @Override
    public Flux<RecoveryReport> recoverInterrupted(Instant cutoff) {
        return Flux.concat(interrupted(cutoff), Flux.defer(() -> unexplained(cutoff)))
                .doOnNext(r -> log.info("[baymax] recovery documentId={} outcome={}", r.documentId(), r.outcome()));
    }

    /** DONE or NEEDS_RETAKE with no message: explained if recent, only counted if older (a late text about an old paper helps nobody). */
    private Flux<RecoveryReport> unexplained(Instant cutoff) {
        Instant since = clock.instant().minus(properties.getRecovery().getExplainMaxAge());
        return documents.unexplainedSince(since, cutoff)
                .concatMap(documents::find)
                .concatMap(d -> chain.explainOnly(d).thenReturn(new RecoveryReport(d.id(), "explained"))
                        .onErrorResume(e -> {
                            log.error("[baymax] recovery explain errored documentId={}: {}", d.id(), e.getClass().getSimpleName());
                            return Mono.just(new RecoveryReport(d.id(), "error"));
                        }))
                .concatWith(documents.countUnexplainedBefore(since)
                        .doOnNext(n -> {
                            if (n > 0) {
                                log.warn("[baymax] recovery: {} finished document(s) older than {} were never explained", n,
                                        properties.getRecovery().getExplainMaxAge());
                            }
                        })
                        .thenMany(Flux.<RecoveryReport>empty()));
    }
```
- [ ] **Step 4:** the recovery tests green; `./gradlew build` green.
- [ ] **Step 5:** `feat(baymax): a recent document that finished but was never explained is explained after a restart`.
  Add to `docs/RUNBOOK.md`: `BAYMAX_RECOVERY_ENABLED` (default true; set `false` only to stop a crash loop), the log
  lines `recovery documentId=… outcome=…`, `recovery sweep done`, and the WARN count. Run the standard pre-merge
  (migration check: V15 is the latest on main).

**Try it on your phone (PR 4)** — needs Tanvir on the box (SSH) as well as the phone:
- [ ] Before the first deploy, optionally note P.1 Step 5's counts; after it, the log shows `recovery sweep done
  documents=<n>` and one `recovery documentId=… outcome=…` per stuck document.
- [ ] Send `t1-routine-lab.jpg`. **Wait for the ack** "ছবিটা পেয়েছি…". Open the web timeline and confirm the new paper
  is listed as being read (status RECEIVED/PROCESSING). Only then run `docker restart medscribe-ai` on the box.
- [ ] Once `/actuator/health` is UP again, the explanation arrives on WhatsApp **once**, and
  `docker logs medscribe-ai --since 10m 2>&1 | grep 'recovery documentId='` shows `outcome=reprocessed`.
- [ ] Known gap, stated in the build report: a restart in the 1–2 s between the webhook's 200 and the document row being
  created leaves nothing to recover (no document yet); PR 8 counts such messages, F9c (Phase 4) re-dispatches them.

**Post-deploy:** the standard check.

---

## PR 5 — A2: compose, release and deliver are separate; the detail arrives once (F4)

Branch `fix/baymax-detail-compose-release`, from merged PR 4. **This PR alone owns the detail path**: later PRs call
its API and never re-implement it.

The defect: `detail()` composes, saves **and delivers** (`ExplanationService.java:68-71` → `release` `:200-221` →
`MessageDeliveryPort.deliver`), then `WaIntakeService.onText` sends `message.body()` a second time (`:102-105`). With
`gate-mode=urgency`, a PENDING body is sent by `onText` itself, bypassing the reviewer. On the same path: a web
`POST /documents/{id}/detail` (`DocumentHandler.java:158-175`) also texts the family; `detail()` on a NEEDS_RETAKE
document files a new RETAKE row (bumping `consecutiveRetakes`); a detail that failed closed gets silence; every ask
recomposes (a paid call); and `urgency_reasons` is stored comma-joined (`PostgresOutboundMessageAdapter.java:38,122`)
while reasons embed marker names that can contain commas (`value_critical:<name>|stopgap`, `UrgencyService.java:73-74`),
so a cache keyed on reasons would never hit on the real database.

The fix: `composeChecked(documentId, kind)` (safety-checked, no save, no delivery), `release(message)` (save, then park
or deliver), `deliver(stored)` (the only path from this service to `MessageDeliveryPort`, behind an **atomic claim**
so a row reaches a family at most once). DETAIL is cached per document. `onText` never sends a body; every non-deliverable
state gets fixed copy that leads with the urgency (D5). Production runs `gate=off`; the urgency-mode behaviour is
proven by the tests.

Decisions this PR fixes for Phase 0 (one rule each, used by every later PR):
- **DETAIL that failed safety** → fixed `wa.detail.unavailable` + link, and the row is final for that urgency and reason
  set (a cache hit; no regeneration until the checklist passes). An EXPLANATION that failed safety gets PR 9's
  code-only fallback.
- **Repeat ask** → pointer (`wa.detail.already_sent` + link), never the body again.
- **`UrgencyLines`** → the one urgency block (Global Constraints).

### Task 5.1: Copy foundations — bundle parity, the red-flag line as one key, `UrgencyLines`

**Files:** create `baymax/src/test/java/com/elioo/baymax/outbound/application/service/MessagesBundleTest.java`,
`outbound/application/service/UrgencyLines.java`, `baymax/src/test/java/com/elioo/baymax/outbound/application/service/UrgencyLinesTest.java`;
modify `messages_bn.properties`, `messages_en.properties`.

- [ ] **Step 1: Write the tests.** `MessagesBundleTest` (parity was checked by a key diff on 2026-10-17; these two are
  forward guards; the third fails on the missing key, which is the absence it exists to fix):

```java
package com.elioo.baymax.outbound.application.service;

import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * messages_en.properties says "same keys, same placeholders"; nothing asserted it (UiCopyTest covers ui_* only).
 * Phase 0 adds twenty-odd keys to both files, so the claim becomes a test before the keys arrive.
 */
class MessagesBundleTest {

    private static Properties load(String path) throws Exception {
        Properties p = new Properties();
        try (var in = MessagesBundleTest.class.getClassLoader().getResourceAsStream(path)) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    @Test
    void bothLanguagesCarryExactlyTheSameKeys() throws Exception {
        Properties bn = load("baymax/messages_bn.properties");
        Properties en = load("baymax/messages_en.properties");
        assertThat(bn.stringPropertyNames()).as("vacuous if the bundle did not load").hasSizeGreaterThanOrEqualTo(34);
        assertThat(en.stringPropertyNames()).containsExactlyInAnyOrderElementsOf(bn.stringPropertyNames());
    }

    @Test
    void placeholdersMatchAcrossLanguages() throws Exception {
        Properties bn = load("baymax/messages_bn.properties");
        Properties en = load("baymax/messages_en.properties");
        Pattern hole = Pattern.compile("\\{[a-z_]+}");
        for (String key : bn.stringPropertyNames()) {
            assertThat(holes(hole, en.getProperty(key))).as(key).isEqualTo(holes(hole, bn.getProperty(key)));
        }
    }

    /**
     * The approved red-flag sentence exists once and is reused by every Phase 0 fallback. explanation.now must
     * still carry it verbatim, so an edit to either copy without the other fails here instead of drifting.
     */
    @Test
    void theRedFlagLineIsTheApprovedSentenceInsideTheNowExplanation() {
        Copy copy = new Copy();
        assertThat(copy.bn("explanation.now", Map.of())).contains(copy.bn("safety.red_flag_line", Map.of()));
        assertThat(copy.en("explanation.now", Map.of())).contains(copy.en("safety.red_flag_line", Map.of()));
    }

    private static List<String> holes(Pattern p, String s) {
        List<String> out = new ArrayList<>();
        Matcher m = p.matcher(s == null ? "" : s);
        while (m.find()) {
            out.add(m.group());
        }
        Collections.sort(out);
        return out;
    }
}
```

  `UrgencyLinesTest`:

```java
class UrgencyLinesTest {

    private final Copy copy = new Copy();

    @Test
    void nowAndThisWeekCarryTheirLineThenTheRedFlagLineAndRoutineCarriesNothing() {
        assertThat(UrgencyLines.block(copy, Urgency.NOW))
                .containsExactly(copy.bn("urgency.now", Map.of()), copy.bn("safety.red_flag_line", Map.of()));
        assertThat(UrgencyLines.block(copy, Urgency.THIS_WEEK))
                .containsExactly(copy.bn("urgency.this_week", Map.of()), copy.bn("safety.red_flag_line", Map.of()));
        assertThat(UrgencyLines.block(copy, Urgency.ROUTINE)).isEmpty();
    }

    /** D5: the urgency is never behind what it qualifies. */
    @Test
    void leadPutsTheBlockFirst() {
        assertThat(UrgencyLines.lead(copy, Urgency.NOW, "body")).isEqualTo(String.join("\n",
                copy.bn("urgency.now", Map.of()), copy.bn("safety.red_flag_line", Map.of()), "body"));
        assertThat(UrgencyLines.lead(copy, Urgency.ROUTINE, "body")).isEqualTo("body");
    }
}
```

- [ ] **Step 2: Run** → `IllegalStateException: messages: missing key safety.red_flag_line` (`Copy.java:38`) and a compile
  error on `UrgencyLines`.
- [ ] **Step 3: Implement.** Append to `messages_bn.properties` after `:81`, under
  `# Phase 0. Drafts for the copy owner; safety.red_flag_line is the approved sentence from explanation.now, word for word.`:

```properties
safety.red_flag_line=রোগী যদি অজ্ঞান হন, শ্বাস নিতে কষ্ট হয়, বা বুকে ব্যথা হয় — সরাসরি নিকটস্থ হাসপাতালের জরুরি বিভাগে যান।
# F4: answers to "বিস্তারিত" that are NOT the explanation body. WaIntakeService never sends a body itself; the body
# goes through the delivery port. Code puts the UrgencyLines block first when the document is urgent (D5).
wa.detail.pending=নতুন রিপোর্টটা পড়া হয়েছে। Medioo-র ব্যাখ্যাটা একজন মিলিয়ে দেখছেন।
wa.detail.unavailable=এই কাগজের লেখা ব্যাখ্যা দেওয়া হবে না। কাগজে যা লেখা আছে, ছবিসহ এখানে: {link}\nপ্রশ্ন থাকলে ডাক্তারকে দেখান।
wa.detail.already_sent=বিস্তারিত আগেই পাঠানো হয়েছে — উপরের বার্তায় আছে। কাগজে যা লেখা আছে, ছবিসহ: {link}
```

  English twins in `messages_en.properties` after `:41`
  (`safety.red_flag_line=If the patient is unconscious, struggling to breathe, or has chest pain — go straight to the nearest hospital emergency department.`,
  and the three `wa.detail.*` with the same placeholders). No new `লিখুন` invitation, so `CopyInstructionsHaveHandlersTest`
  is unaffected.

```java
package com.elioo.baymax.outbound.application.service;

import com.elioo.baymax.outbound.domain.Urgency;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The one place an urgency level becomes words (Phase 0, D5). NOW and THIS_WEEK: their fixed line, then the approved
 * red-flag line; ROUTINE: nothing. Callers put the block before anything it qualifies. D11's signed emergency
 * template replaces the red-flag line here, once, in Phase 3.
 */
public final class UrgencyLines {

    private UrgencyLines() {
    }

    public static List<String> block(Copy copy, Urgency level) {
        return switch (level) {
            case NOW -> List.of(copy.bn("urgency.now", Map.of()), copy.bn("safety.red_flag_line", Map.of()));
            case THIS_WEEK -> List.of(copy.bn("urgency.this_week", Map.of()), copy.bn("safety.red_flag_line", Map.of()));
            case ROUTINE -> List.of();
        };
    }

    public static String lead(Copy copy, Urgency level, String... lines) {
        List<String> all = new ArrayList<>(block(copy, level));
        all.addAll(List.of(lines));
        return String.join("\n", all);
    }
}
```

- [ ] **Step 4:** `./gradlew :baymax:test --tests '*MessagesBundleTest' --tests '*UrgencyLinesTest' --tests '*CopyTest' --tests '*CopyInstructionsHaveHandlersTest'` green.
- [ ] **Step 5:** stage the two bundles, `UrgencyLines.java` and the two tests;
  `feat(baymax): one urgency block and the red-flag line as one key; bundle parity asserted`.

### Task 5.2: Red guards, end to end, both gate modes

**Files:** create `baymax/src/test/java/com/elioo/baymax/outbound/InMemoryOutboundMessages.java`,
`baymax/src/test/java/com/elioo/baymax/wa/application/service/DetailDeliveryGuardsTest.java`,
`baymax/src/test/java/com/elioo/baymax/architecture/WaNeverSendsAComposedBodyTest.java`,
`baymax/src/test/java/com/elioo/baymax/wa/adapter/out/WhatsAppMessageDeliveryTest.java`.

These tests count what reached the one mocked `WaMessagingPort`, from **every** path (release, approve, `onText`). A body
is identified by equality with a stored row's `body` field, never by a substring, and each test first checks that a body
exists to leak, so it cannot pass vacuously. They compile against the pre-fix APIs (the fake declares the new port
methods **without** `@Override`), so the Step 2 run is the replay.

- [ ] **Step 1: Write the failing tests.** `InMemoryOutboundMessages.java`:

```java
package com.elioo.baymax.outbound;

import com.elioo.baymax.outbound.application.port.out.OutboundMessagePort;
import com.elioo.baymax.outbound.domain.DeliveryOutcome;
import com.elioo.baymax.outbound.domain.MessageCount;
import com.elioo.baymax.outbound.domain.OutboundMessage;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rows in memory, for wiring tests that count what reached the delivery channel. It CANNOT fail the way Postgres
 * fails — V14's CHECKs are not here — so every constraint path these tests touch is also exercised for real in
 * {@code DetailCacheAcceptanceTest}. Do not use this fake to test a constraint.
 */
public final class InMemoryOutboundMessages implements OutboundMessagePort {

    private final Map<UUID, OutboundMessage> rows = new LinkedHashMap<>();

    public List<OutboundMessage> rows() {
        return new ArrayList<>(rows.values());
    }

    @Override
    public Mono<OutboundMessage> save(OutboundMessage m) {
        UUID id = UUID.randomUUID();
        OutboundMessage s = new OutboundMessage(id, m.familyId(), m.patientId(), m.documentId(), m.kind(), m.urgency(),
                m.urgencyReasons(), m.body(), m.gateStatus(), m.reviewer(), m.rejectReason(), m.decidedAt(), m.sentAt(), m.createdAt());
        rows.put(id, s);
        return Mono.just(s);
    }

    @Override
    public Mono<OutboundMessage> find(UUID id) {
        return Mono.justOrEmpty(rows.get(id));
    }

    @Override
    public Mono<OutboundMessage> decide(UUID id, OutboundMessage.GateStatus status, String reviewer, String reason, Instant at) {
        OutboundMessage m = rows.get(id);
        OutboundMessage d = new OutboundMessage(id, m.familyId(), m.patientId(), m.documentId(), m.kind(), m.urgency(),
                m.urgencyReasons(), m.body(), status, reviewer, reason, at, m.sentAt(), m.createdAt());
        rows.put(id, d);
        return Mono.just(d);
    }

    @Override
    public Mono<Void> markSent(UUID id, Instant at) {
        return Mono.fromRunnable(() -> rows.computeIfPresent(id, (k, m) -> withSentAt(m, at)));
    }

    @Override
    public Mono<OutboundMessage> recordDelivery(UUID id, DeliveryOutcome outcome, Instant at) {
        rows.computeIfPresent(id, (k, m) -> withSentAt(m, outcome.wasSent() ? at : null));
        if (!outcome.wasSent()) {
            claimed.remove(id);   // a failed delivery may be claimed again
        }
        return find(id);
    }

    @Override
    public Flux<OutboundMessage> pending() {
        return Flux.fromIterable(rows()).filter(m -> m.gateStatus() == OutboundMessage.GateStatus.PENDING);
    }

    @Override
    public Mono<OutboundMessage> latestDeliverable(UUID documentId) {
        return Flux.fromIterable(rows()).filter(m -> documentId.equals(m.documentId()) && m.isDeliverable()).last()
                .onErrorResume(java.util.NoSuchElementException.class, e -> Mono.empty());
    }

    @Override
    public Flux<MessageCount> counts(Instant from, Instant to) {
        return Flux.empty();
    }

    @Override
    public Mono<Long> consecutiveRetakes(UUID familyId) {
        return Mono.just(0L);
    }

    /** F4. Deliberately no @Override: on the pre-fix port this is an extra method, so the red replay compiles. */
    public Mono<OutboundMessage> latestOfKind(UUID documentId, OutboundMessage.Kind kind) {
        OutboundMessage last = null;
        for (OutboundMessage m : rows.values()) {                 // insertion order is creation order
            if (documentId.equals(m.documentId()) && m.kind() == kind) {
                last = m;
            }
        }
        return Mono.justOrEmpty(last);
    }

    private final java.util.Set<UUID> claimed = new java.util.HashSet<>();

    /**
     * F4, no @Override for the same reason. A Set here CANNOT fail like Postgres does; the real claim is one UPDATE and
     * is tested for concurrency in DetailCacheAcceptanceTest. This fake only lets wiring tests count deliveries.
     */
    public Mono<OutboundMessage> claimDelivery(UUID id) {
        OutboundMessage m = rows.get(id);
        if (m == null || m.sentAt() != null || !m.isDeliverable() || !claimed.add(id)) {
            return Mono.empty();
        }
        return Mono.just(m);
    }

    private static OutboundMessage withSentAt(OutboundMessage m, Instant at) {
        return new OutboundMessage(m.id(), m.familyId(), m.patientId(), m.documentId(), m.kind(), m.urgency(),
                m.urgencyReasons(), m.body(), m.gateStatus(), m.reviewer(), m.rejectReason(), m.decidedAt(), at, m.createdAt());
    }
}
```

  `DetailDeliveryGuardsTest.java`:

```java
package com.elioo.baymax.wa.application.service;

import com.elioo.baymax.aicall.application.service.MeteredLlmClient;
import com.elioo.baymax.aicall.domain.AiCallPurpose;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.application.port.in.DocumentIntakeUseCase;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.config.ExtractionModelConfiguration;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.outbound.InMemoryOutboundMessages;
import com.elioo.baymax.outbound.application.port.out.MessageDeliveryPort;
import com.elioo.baymax.outbound.application.port.out.ReviewerNotificationPort;
import com.elioo.baymax.outbound.application.service.Copy;
import com.elioo.baymax.outbound.application.service.ExplanationService;
import com.elioo.baymax.outbound.application.service.MessageSafetyCheck;
import com.elioo.baymax.outbound.application.service.OutboundMessageGate;
import com.elioo.baymax.outbound.application.service.ReviewGateService;
import com.elioo.baymax.outbound.application.service.UrgencyService;
import com.elioo.baymax.outbound.domain.OutboundMessage;
import com.elioo.baymax.wa.adapter.out.WhatsAppMessageDelivery;
import com.elioo.baymax.wa.application.port.out.WaMessagingPort;
import com.elioo.healthcare.llm.api.LlmClient;
import com.elioo.healthcare.llm.model.LlmRequest;
import com.elioo.healthcare.llm.model.LlmResponse;
import com.elioo.healthcare.llm.model.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F4 guards, end to end: the real ExplanationService, ReviewGateService and WaIntakeService over ONE mocked WhatsApp
 * port, so every path that can put text on the family's phone is counted — release, approve and onText alike.
 * Written against APIs that exist before the fix, so each was replayed red on the pre-fix source and failed ON the defect.
 */
class DetailDeliveryGuardsTest {

    private static final String FROM = "8801700000000", E164 = "+8801700000000";
    private static final Instant T0 = Instant.parse("2026-10-20T10:00:00Z");
    private static final UUID DOC = UUID.randomUUID(), PATIENT = UUID.randomUUID(), FAMILY = UUID.randomUUID();
    /** model replies the checklist accepts (same strings ExplanationServiceTest already uses) */
    private static final String ROUTINE_REPLY = "HbA1c 5.0, স্বাভাবিক 4.0–5.6।";
    private static final String NOW_REPLY = "HbA1c এসেছে 11.2 %, যা স্বাভাবিকের চেয়ে অনেক বেশি। এখনই ডাক্তারের কাছে যান। দেরি করবেন না।";

    private final DocumentRecordPort documents = mock(DocumentRecordPort.class);
    private final HealthRecordPort records = mock(HealthRecordPort.class);
    private final InMemoryOutboundMessages messages = new InMemoryOutboundMessages();
    private final MeteredLlmClient metered = mock(MeteredLlmClient.class);
    private final LlmClient sonnet = mock(LlmClient.class);
    private final ReviewerNotificationPort reviewer = mock(ReviewerNotificationPort.class);
    private final WaMessagingPort graph = mock(WaMessagingPort.class);
    private final BaymaxProperties props = new BaymaxProperties();
    private final Deque<String> replies = new ArrayDeque<>();
    private final AtomicInteger wamids = new AtomicInteger();
    private ExplanationService explainer;
    private ReviewGateService reviewGate;
    private WaIntakeService wa;

    @BeforeEach
    void wire() {
        props.getWa().setEnabled(true);
        props.getWa().setAllowlist(List.of(E164));
        Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        MessageDeliveryPort whatsapp = new WhatsAppMessageDelivery(graph, records, props);
        explainer = new ExplanationService(documents, records, messages, metered,
                new ExtractionModelConfiguration.ExtractionClients(sonnet, sonnet, sonnet, true),
                new UrgencyService(props, clock), new MessageSafetyCheck(props), new OutboundMessageGate(props),
                reviewer, whatsapp, new Copy(), props, clock);
        reviewGate = new ReviewGateService(messages, whatsapp, clock);
        wa = new WaIntakeService(records, documents, explainer, mock(DocumentIntakeUseCase.class), graph, new Copy(), props);

        when(graph.sendText(anyString(), anyString())).thenAnswer(i -> Mono.just("wamid." + wamids.incrementAndGet()));
        when(metered.using(any())).thenReturn(metered);
        when(metered.invoke(eq(AiCallPurpose.EXPLAIN), any(), any(LlmRequest.class))).thenAnswer(i -> Mono.just(
                new LlmResponse(replies.isEmpty() ? "" : replies.pop(), "stop", new TokenUsage(500, 120), "claude-sonnet-5", null, "anthropic", 900L)));
        when(reviewer.notifyPending(any())).thenReturn(Mono.empty());
        FamilyAccount family = new FamilyAccount(FAMILY, E164, "Rahim", FamilyAccount.Plan.FREE, Instant.EPOCH, Instant.EPOCH);
        when(records.findFamilyByWhatsapp(E164)).thenReturn(Mono.just(family));
        when(records.findFamily(FAMILY)).thenReturn(Mono.just(family));
        when(records.findPatient(PATIENT)).thenReturn(Mono.just(
                new PatientProfile(PATIENT, FAMILY, "Ma", 74, PatientProfile.Sex.FEMALE, List.of(), T0, T0)));
        Document doc = new Document(DOC, PATIENT, FAMILY, "lab_report", LocalDate.parse("2026-10-14"), "Shapla Diagnostics", "{}", 0.93,
                Document.Status.DONE, null, "anthropic/claude-sonnet-5", null, 1, T0, T0);
        when(documents.find(DOC)).thenReturn(Mono.just(doc));
        when(documents.latestDoneDocument(FAMILY)).thenReturn(Mono.just(doc));
        when(documents.followUpsOf(DOC)).thenReturn(Flux.empty());
        when(documents.clinicalContextOf(DOC)).thenReturn(Mono.empty());
        when(documents.medicinesOf(DOC)).thenReturn(Flux.empty());
    }

    private void observations(String name, String value, String low, String high) {
        Map<String, Object> v = new HashMap<>();
        v.put("name", name); v.put("value", value); v.put("ref_low", low); v.put("ref_high", high);
        when(documents.observationsOf(DOC)).thenReturn(Flux.just(v));
    }

    private List<String> allSent() {
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(graph, atLeast(0)).sendText(eq(E164), sent.capture());
        return sent.getAllValues();
    }

    /** Sends that ARE a stored message body — field equality, never a substring of the document. */
    private List<String> bodiesSent() {
        Set<String> bodies = messages.rows().stream().map(OutboundMessage::body).filter(Objects::nonNull).collect(Collectors.toSet());
        return allSent().stream().filter(bodies::contains).toList();
    }

    private OutboundMessage firstDetail() {
        return messages.rows().stream().filter(m -> m.kind() == OutboundMessage.Kind.DETAIL).findFirst().orElseThrow();
    }

    /** gate=off (production): one ask, one body on the phone. */
    @Test
    void gateOffTheDetailArrivesExactlyOnce() {
        observations("HbA1c", "5.0", "4.0", "5.6");
        replies.push(ROUTINE_REPLY);

        wa.onText(FROM, "বিস্তারিত").block();

        assertThat(firstDetail().body()).as("vacuity guard: a body exists that could be sent twice").isNotBlank();
        assertThat(bodiesSent())
                .as("the detail arrived twice: once from release(), once from onText itself (F4)")
                .hasSize(1);
    }

    /** gate=off: a second ask neither re-sends the body nor pays for a second composition. */
    @Test
    void gateOffAskingTwiceSendsTheBodyOnceAndComposesOnce() {
        observations("HbA1c", "5.0", "4.0", "5.6");
        replies.push(ROUTINE_REPLY);
        replies.push(ROUTINE_REPLY);

        wa.onText(FROM, "বিস্তারিত").block();
        wa.onText(FROM, "বিস্তারিত").block();

        assertThat(bodiesSent()).as("each ask re-composed and re-sent the detail (no DETAIL cache, F4)").hasSize(1);
        verify(metered, times(1)).invoke(eq(AiCallPurpose.EXPLAIN), any(), any(LlmRequest.class));
        assertThat(allSent()).hasSize(2);                     // the body, then the fixed already-sent reply
        assertThat(allSent().get(1)).isEqualTo(new Copy().bn("wa.detail.already_sent",
                Map.of("link", props.getOutbound().getPublicBaseUrl() + "/app/documents/" + DOC)));
    }

    /** gate=urgency, NOW: the PENDING body never reaches WhatsApp by any path; the family gets the urgency at once (D5). */
    @Test
    void underTheUrgencyGateAPendingDetailIsNeverSent() {
        props.getOutbound().setGateMode("urgency");
        observations("HbA1c", "11.2", "4.0", "5.6");
        replies.push(NOW_REPLY);

        wa.onText(FROM, "বিস্তারিত").block();

        OutboundMessage detail = firstDetail();
        assertThat(detail.gateStatus()).isEqualTo(OutboundMessage.GateStatus.PENDING);
        assertThat(detail.body()).as("vacuity guard: an unreviewed body exists that could leak").isNotBlank();
        assertThat(bodiesSent())
                .as("a PENDING body reached WhatsApp — onText sent it itself, bypassing the review gate (F4)")
                .isEmpty();
        verify(reviewer, times(1)).notifyPending(any());
        Copy copy = new Copy();
        assertThat(allSent()).singleElement().satisfies(s -> {
            assertThat(s).startsWith(copy.bn("urgency.now", Map.of()))
                    .contains(copy.bn("safety.red_flag_line", Map.of()))
                    .contains(copy.bn("wa.detail.pending", Map.of()));
            assertThat(s.length()).isLessThanOrEqualTo(props.getOutbound().getMaxChars());
        });
    }

    /** gate=urgency: ask, approve, ask again — the approved body reaches the phone once, from approve, and never again. */
    @Test
    void underTheUrgencyGateTheApprovedDetailArrivesExactlyOnce() {
        props.getOutbound().setGateMode("urgency");
        observations("HbA1c", "11.2", "4.0", "5.6");
        replies.push(NOW_REPLY);

        wa.onText(FROM, "বিস্তারিত").block();
        reviewGate.approve(firstDetail().id(), "tanvir").block();
        wa.onText(FROM, "বিস্তারিত").block();

        assertThat(firstDetail().body()).isNotBlank();
        assertThat(bodiesSent())
                .as("the body went out before approval (onText) and again after it (F4)")
                .hasSize(1);
        verify(metered, times(1)).invoke(eq(AiCallPurpose.EXPLAIN), any(), any(LlmRequest.class));
    }

    /** The web endpoint asks for the detail to SHOW it; it must not text the family as a side effect. */
    @Test
    void aWebDetailRequestStoresTheMessageAndSendsNothingOnWhatsapp() {
        observations("HbA1c", "5.0", "4.0", "5.6");
        replies.push(ROUTINE_REPLY);

        OutboundMessage shown = explainer.detail(DOC).block();

        assertThat(shown.body()).isNotBlank();
        verify(graph, never()).sendText(anyString(), anyString());
    }

    /** A family that typed the word we asked for gets an answer even when the detail failed closed. */
    @Test
    void aDetailThatFailedClosedIsAnsweredNotSilent() {
        observations("HbA1c", "5.0", "4.0", "5.6");               // no replies queued: the model returns "", twice

        wa.onText(FROM, "বিস্তারিত").block();

        verify(graph, atLeastOnce()).sendText(eq(E164), anyString());
        assertThat(firstDetail().gateStatus()).isEqualTo(OutboundMessage.GateStatus.FAILED_SAFETY);
        assertThat(allSent()).singleElement().isEqualTo(new Copy().bn("wa.detail.unavailable",
                Map.of("link", props.getOutbound().getPublicBaseUrl() + "/app/documents/" + DOC)));
    }

    /** A detail request on a NEEDS_RETAKE document must not file another retake (it escalates the next retake copy). */
    @Test
    void aDetailRequestOnARetakeDocumentFilesNoRetakeRow() {
        when(documents.find(DOC)).thenReturn(Mono.just(new Document(DOC, PATIENT, FAMILY, null, null, null, null, 0.6,
                Document.Status.NEEDS_RETAKE, "blurry", null, null, 1, T0, T0)));

        reactor.test.StepVerifier.create(explainer.detail(DOC)).verifyComplete();

        assertThat(messages.rows()).as("a detail request filed a RETAKE row").isEmpty();
        verify(graph, never()).sendText(anyString(), anyString());
    }

    /** After a fail-closed detail, asking again is final: no second model call, no body, the same fixed reply. */
    @Test
    void aSecondAskAfterAFailClosedDetailCostsNothingAndSendsNoBody() {
        observations("HbA1c", "5.0", "4.0", "5.6");               // no replies queued: the model returns "" (fail-closed)

        wa.onText(FROM, "বিস্তারিত").block();
        org.mockito.Mockito.clearInvocations(metered);
        wa.onText(FROM, "বিস্তারিত").block();

        verify(metered, never()).invoke(eq(AiCallPurpose.EXPLAIN), any(), any(LlmRequest.class));
        assertThat(messages.rows()).filteredOn(m -> m.kind() == OutboundMessage.Kind.DETAIL).hasSize(1);
        assertThat(firstDetail().gateStatus()).isEqualTo(OutboundMessage.GateStatus.FAILED_SAFETY);
        String unavailable = new Copy().bn("wa.detail.unavailable",
                Map.of("link", props.getOutbound().getPublicBaseUrl() + "/app/documents/" + DOC));
        assertThat(allSent()).containsExactly(unavailable, unavailable);
    }
}
```

  `WaNeverSendsAComposedBodyTest.java` (ArchUnit 1.x `failOnEmptyShould` is on, so a package rename fails the rule
  instead of passing it vacuously):

```java
package com.elioo.baymax.architecture;

import com.elioo.baymax.outbound.domain.OutboundMessage;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * F4: a composed body reaches a family only through MessageDeliveryPort, which records the outcome (V13/V14) and
 * refuses anything not released or approved. The inbound WhatsApp layer sends fixed copy only — it never reads a
 * body. Before F4, WaIntakeService.onText sent message.body() itself: the double-send and the gate bypass.
 */
class WaNeverSendsAComposedBodyTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.elioo.baymax");

    @Test
    void theInboundWhatsappLayerNeverReadsAComposedBody() {
        noClasses().that().resideInAPackage("..wa.application..")
                .should().callMethod(OutboundMessage.class, "body")
                .because("a body is delivered only by ExplainDocumentUseCase.deliver → MessageDeliveryPort (F4)")
                .check(CLASSES);
    }
}
```

  `WhatsAppMessageDeliveryTest.java` (the gate invariant where every writer meets it: release, approve and nudges all
  reach WhatsApp through this adapter):

```java
package com.elioo.baymax.wa.adapter.out;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.outbound.domain.DeliveryOutcome;
import com.elioo.baymax.outbound.domain.OutboundMessage;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.wa.application.port.out.WaMessagingPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WhatsAppMessageDeliveryTest {

    private static final UUID FAMILY = UUID.randomUUID();
    private static final String E164 = "+8801700000000";
    private final WaMessagingPort graph = mock(WaMessagingPort.class);
    private final HealthRecordPort records = mock(HealthRecordPort.class);
    private final BaymaxProperties props = new BaymaxProperties();
    private WhatsAppMessageDelivery delivery;

    @BeforeEach
    void wire() {
        props.getWa().setAllowlist(List.of(E164));
        when(records.findFamily(FAMILY)).thenReturn(Mono.just(
                new FamilyAccount(FAMILY, E164, "Rahim", FamilyAccount.Plan.FREE, Instant.EPOCH, Instant.EPOCH)));
        when(graph.sendText(anyString(), anyString())).thenReturn(Mono.just("wamid.X"));
        delivery = new WhatsAppMessageDelivery(graph, records, props);
    }

    private static OutboundMessage row(OutboundMessage.GateStatus status) {
        return new OutboundMessage(UUID.randomUUID(), FAMILY, null, UUID.randomUUID(), OutboundMessage.Kind.DETAIL,
                Urgency.NOW, List.of(), "এখনও কেউ দেখেননি এমন লেখা", status, null, null, null, null, Instant.EPOCH);
    }

    @ParameterizedTest
    @EnumSource(value = OutboundMessage.GateStatus.class, names = {"PENDING", "REJECTED", "FAILED_SAFETY"})
    void aMessageThatIsNotReleasedOrApprovedNeverReachesWhatsapp(OutboundMessage.GateStatus status) {
        DeliveryOutcome outcome = delivery.deliver(row(status)).block();

        verify(graph, never()).sendText(anyString(), anyString());
        assertThat(outcome.wasSent()).isFalse();
        assertThat(outcome.error()).isEqualTo("not_released");
    }

    @ParameterizedTest
    @EnumSource(value = OutboundMessage.GateStatus.class, names = {"RELEASED", "APPROVED"})
    void aReleasedOrApprovedMessageIsSent(OutboundMessage.GateStatus status) {
        assertThat(delivery.deliver(row(status)).block().wasSent()).isTrue();
        verify(graph, times(1)).sendText(E164, "এখনও কেউ দেখেননি এমন লেখা");
    }
}
```

- [ ] **Step 2 — the replay: run on the pre-fix source (`origin/main` with PR 4 merged); each must fail on its defect.**
  `./gradlew :baymax:test --tests '*DetailDeliveryGuardsTest' --tests '*WaNeverSendsAComposedBodyTest' --tests '*WhatsAppMessageDeliveryTest'`
  - `gateOffTheDetailArrivesExactlyOnce` → `[the detail arrived twice: once from release(), once from onText itself (F4)] Expected size: 1 but was: 2 in: ["HbA1c 5.0, স্বাভাবিক 4.0–5.6।", "HbA1c 5.0, স্বাভাবিক 4.0–5.6।"]`
  - `gateOffAskingTwiceSendsTheBodyOnceAndComposesOnce` → `[each ask re-composed and re-sent the detail (no DETAIL cache, F4)] Expected size: 1 but was: 4`
  - `underTheUrgencyGateAPendingDetailIsNeverSent` → `[a PENDING body reached WhatsApp — onText sent it itself, bypassing the review gate (F4)] Expecting empty but was: ["HbA1c এসেছে 11.2 % …"]`
  - `underTheUrgencyGateTheApprovedDetailArrivesExactlyOnce` → `[the body went out before approval (onText) and again after it (F4)] Expected size: 1 but was: 2`.
    Pre-fix, the second ask composes again and fails closed, because no reply is queued.
  - `aWebDetailRequestStoresTheMessageAndSendsNothingOnWhatsapp` → `NeverWantedButInvoked: waMessagingPort.sendText(<any string>, <any string>); Never wanted here … But invoked here: -> at …WhatsAppMessageDelivery.send(WhatsAppMessageDelivery.java:64)`
  - `aDetailThatFailedClosedIsAnsweredNotSilent` → `Wanted but not invoked: waMessagingPort.sendText("+8801700000000", <any string>); Actually, there were zero interactions with this mock.`
    That is the silence.
  - `aDetailRequestOnARetakeDocumentFilesNoRetakeRow` → `expectation "expectComplete" failed (expected: onComplete(); actual: onNext(OutboundMessage[id=…, kind=RETAKE, …]))`
  - `theInboundWhatsappLayerNeverReadsAComposedBody` → `Architecture Violation … was violated (3 times): Method <…WaIntakeService.lambda$onText$…> calls method <…OutboundMessage.body()> in (WaIntakeService.java:103)` (twice on :103, once on `(WaIntakeService.java:105)`)
  - `aMessageThatIsNotReleasedOrApprovedNeverReachesWhatsapp[PENDING|REJECTED|FAILED_SAFETY]` → `NeverWantedButInvoked: waMessagingPort.sendText(…)`. `aReleasedOrApprovedMessageIsSent` is green: it is the protected direction.
  - **Reject the replay** if any test above fails on `messages: missing key wa.…`, a `NullPointerException` or a Mockito
    matcher error. Each count assertion comes before any new copy key, so that should not happen. If it does, the test
    is reaching the wrong line.
  - Paste each red output into the PR body under "Replayed red on the pre-fix source".
  - `aSecondAskAfterAFailClosedDetailCostsNothingAndSendsNoBody` →
    `meteredLlmClient.invoke(EXPLAIN, …); Never wanted here: … But invoked here` — the second ask recomposed (paid)
    after the first had failed closed.
- [ ] **Step 3:** no implementation in this task; Tasks 5.3–5.6 turn these green.
- [ ] **Step 4:** `./gradlew :baymax:test` — only the tests above are red.
- [ ] **Step 5: Commit locally, do not push yet:** stage the four files;
  `test(baymax): red guards for the বিস্তারিত double-send, gate bypass, web-side send and retake row (F4)`.

### Task 5.3: The WhatsApp delivery adapter refuses anything not released or approved

**Files:** modify `wa/adapter/out/WhatsAppMessageDelivery.java` (`deliver`, `:43-48`), `outbound/domain/DeliveryOutcome.java`
(`:28-32`). "Nothing PENDING is delivered" was asserted per caller (`ExplanationService`, `NudgeComposer:113-123`,
`ReviewGateService`); `onText` sidestepped it by not being a caller at all. This asserts the rule once, at the adapter
every release path meets (CLAUDE.md, "a fix scoped to the path").

- [ ] **Step 1–2:** the tests and their red exist (Task 5.2, `WhatsAppMessageDeliveryTest`).
- [ ] **Step 3: Implement.** `DeliveryOutcome`, after `SEND_FAILED`:
  `/** The row was not released or approved; the adapter refused it. An invariant breach, never expected. */ public static final String NOT_RELEASED = "not_released";`.
  `WhatsAppMessageDelivery.deliver`, replacing the body-null check:

```java
        if (!message.isDeliverable()) {
            // DR-13 / F4: only RELEASED or APPROVED rows with a body reach a family, whichever caller handed it over
            log.error("[baymax] wa delivery refused id={} gate={} — not released or approved", message.id(),
                    message.gateStatus() == null ? null : message.gateStatus().dbValue());
            return Mono.just(DeliveryOutcome.failed(DeliveryOutcome.NOT_RELEASED));
        }
```

  `isDeliverable()` (`OutboundMessage.java:38-40`) already covers a null body; keep a blank-body check after it. Correct
  the class javadoc ("Nothing PENDING reaches here" was a claim; now it is a check).
- [ ] **Step 4:** `./gradlew :baymax:test --tests '*WhatsAppMessageDeliveryTest' --tests '*ReviewGateServiceTest' --tests '*NudgeComposerTest'`.
  `delivery_error` has no value CHECK (V13) and V14's `failed_status_has_reason` is satisfied, so `not_released` stores cleanly.
- [ ] **Step 5:** `fix(baymax): WhatsApp delivery refuses any message not released or approved (F4)`.

### Task 5.4: `latestOfKind` with a deterministic tie-break, reasons that survive the database, and an atomic delivery claim

**Files:** modify `outbound/application/port/out/OutboundMessagePort.java`, `outbound/adapter/out/persistence/PostgresOutboundMessageAdapter.java`
(`save`/`from` reasons encoding, `markSent`, new `latestOfKind` and `claimDelivery`), `InMemoryOutboundMessages.java`
(add `@Override`); create `baymax/src/test/java/com/elioo/baymax/outbound/application/service/DetailCacheAcceptanceTest.java`
(in the service package, so it can call the package-private `ExplanationService.stillCurrent`).

- [ ] **Step 1: Write the failing Testcontainers tests.** Copy `runner()` from `DeliveryOutcomeAcceptanceTest.java:47-62` and
  `column(UUID, String)` from `:210-217`; the counting delivery stub follows `WaInboundIdempotencyAcceptanceTest`'s
  `@Configuration` + `@Primary` pattern:

```java
@Testcontainers
class DetailCacheAcceptanceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    // runner(), column(...) and execute(...) exactly as in DeliveryOutcomeAcceptanceTest. Imports as there, plus
    // ExplanationService, ExplainDocumentUseCase, UrgencyService, UrgencyAssessment, DocumentFacts, Document,
    // BaymaxProperties, MessageDeliveryPort, DeliveryOutcome, Clock, ZoneOffset, Map, Mono.

    private static OutboundMessage row(UUID family, UUID document, OutboundMessage.Kind kind, String body, Instant at) {
        return new OutboundMessage(null, family, null, document, kind, Urgency.ROUTINE, List.of(), body,
                OutboundMessage.GateStatus.RELEASED, null, null, null, null, at);
    }

    @Test
    void latestOfKindIsTheNewestRowOfThatKindForThatDocumentOnly() {
        runner().run(context -> {
            OutboundMessagePort messages = context.getBean(OutboundMessagePort.class);
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            Instant t = Instant.parse("2026-10-20T09:00:00Z");
            FamilyAccount family = records.createFamily(new FamilyAccount(
                    null, "+8801700000801", "Rahim", FamilyAccount.Plan.FREE, t, t)).block();
            UUID doc = UUID.randomUUID(), other = UUID.randomUUID();

            messages.save(row(family.id(), doc, OutboundMessage.Kind.DETAIL, "পুরনো বিস্তারিত", t)).block();
            OutboundMessage newer = messages.save(row(family.id(), doc, OutboundMessage.Kind.DETAIL, "নতুন বিস্তারিত", t.plusSeconds(60))).block();
            messages.save(row(family.id(), doc, OutboundMessage.Kind.EXPLANATION, "ব্যাখ্যা", t.plusSeconds(120))).block();
            messages.save(row(family.id(), other, OutboundMessage.Kind.DETAIL, "অন্য কাগজ", t.plusSeconds(180))).block();

            assertThat(messages.latestOfKind(doc, OutboundMessage.Kind.DETAIL).block())
                    .extracting(OutboundMessage::id).isEqualTo(newer.id());
            assertThat(messages.latestOfKind(doc, OutboundMessage.Kind.RETAKE).blockOptional()).isEmpty();
            assertThat(messages.latestOfKind(UUID.randomUUID(), OutboundMessage.Kind.DETAIL).blockOptional()).isEmpty();
        });
    }

    /**
     * The web stores a DETAIL without delivering it; WhatsApp delivers that same row later. V14 must accept the
     * provider's acceptance on the stored row, and sent_at must come from that acceptance alone.
     */
    @Test
    void aDetailStoredUndeliveredAndDeliveredLaterRecordsTheOutcomeOnTheSameRow() {
        runner().run(context -> {
            OutboundMessagePort messages = context.getBean(OutboundMessagePort.class);
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            Instant t = Instant.parse("2026-10-20T09:30:00Z");
            FamilyAccount family = records.createFamily(new FamilyAccount(
                    null, "+8801700000802", "Karim", FamilyAccount.Plan.FREE, t, t)).block();
            OutboundMessage stored = messages.save(row(family.id(), UUID.randomUUID(), OutboundMessage.Kind.DETAIL, "বিস্তারিত", t)).block();
            assertThat(column(stored.id(), "sent_at")).as("stored, not delivered").isNull();
            assertThat(column(stored.id(), "delivery_status")).isNull();

            messages.recordDelivery(stored.id(), DeliveryOutcome.sent("wamid.LATER"), t.plusSeconds(30)).block();

            assertThat(column(stored.id(), "sent_at")).isNotNull();
            assertThat(column(stored.id(), "delivery_status")).isEqualTo("sent");
            assertThat(column(stored.id(), "provider_message_id")).isEqualTo("wamid.LATER");
            assertThat(messages.latestOfKind(stored.documentId(), OutboundMessage.Kind.DETAIL).block().sentAt()).isNotNull();
        });
    }

    /** Rows saved at the same instant (a FAILED_SAFETY attempt and its released successor) read back the same way every time. */
    @Test
    void rowsSavedAtTheSameInstantComeBackDeliverableFirstOnEveryRead() {
        runner().run(context -> {
            OutboundMessagePort messages = context.getBean(OutboundMessagePort.class);
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            Instant t = Instant.parse("2026-10-20T10:00:00Z");
            FamilyAccount family = records.createFamily(new FamilyAccount(
                    null, "+8801700000803", "Salma", FamilyAccount.Plan.FREE, t, t)).block();
            UUID doc = UUID.randomUUID();
            messages.save(new OutboundMessage(null, family.id(), null, doc, OutboundMessage.Kind.EXPLANATION, Urgency.NOW,
                    List.of(), null, OutboundMessage.GateStatus.FAILED_SAFETY, null, null, null, null, t)).block();
            OutboundMessage released = messages.save(row(family.id(), doc, OutboundMessage.Kind.EXPLANATION, "কোডের লেখা", t)).block();

            for (int i = 0; i < 5; i++) {
                assertThat(messages.latestOfKind(doc, OutboundMessage.Kind.EXPLANATION).block().id()).isEqualTo(released.id());
            }
        });
    }

    /** urgency_reasons carry marker names, and lab names carry commas: the stored set must equal the fresh one. */
    @Test
    void urgencyReasonsSurviveTheRoundTripEvenWhenAMarkerNameHasAComma() {
        runner().run(context -> {
            OutboundMessagePort messages = context.getBean(OutboundMessagePort.class);
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            Instant t = Instant.parse("2026-10-20T11:00:00Z");
            FamilyAccount family = records.createFamily(new FamilyAccount(
                    null, "+8801700000804", "Nasrin", FamilyAccount.Plan.FREE, t, t)).block();
            Map<String, Object> value = new java.util.HashMap<>(Map.of(
                    "name", "Cholesterol, Total", "value", "450", "ref_low", "0", "ref_high", "200"));
            Document document = new Document(UUID.randomUUID(), UUID.randomUUID(), family.id(), "lab_report", null, null,
                    "{}", 0.9, Document.Status.DONE, null, null, null, 1, t, t);
            UrgencyAssessment assessed = new UrgencyService(new BaymaxProperties(), Clock.fixed(t, ZoneOffset.UTC))
                    .assess(new DocumentFacts(document, "Ma", List.of(value), List.of(), Map.of(), "https://x/y"));
            assertThat(assessed.reasons()).as("vacuity: the fixture must produce a reason with a comma in it")
                    .anySatisfy(r -> assertThat(r).contains(","));

            messages.save(new OutboundMessage(null, family.id(), null, document.id(), OutboundMessage.Kind.DETAIL,
                    assessed.level(), assessed.reasons(), "বিস্তারিত", OutboundMessage.GateStatus.RELEASED,
                    null, null, null, null, t)).block();
            OutboundMessage readBack = messages.latestOfKind(document.id(), OutboundMessage.Kind.DETAIL).block();

            assertThat(readBack.urgencyReasons()).containsExactlyElementsOf(assessed.reasons());
            assertThat(ExplanationService.stillCurrent(readBack, assessed))
                    .as("a cache hit, so a second ask makes no model call").isTrue();
        });
    }

    /** Rows written before the JSON encoding still read as they always did. */
    @Test
    void aLegacyCommaJoinedReasonListStillReads() {
        runner().run(context -> {
            OutboundMessagePort messages = context.getBean(OutboundMessagePort.class);
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            Instant t = Instant.parse("2026-10-20T11:30:00Z");
            FamilyAccount family = records.createFamily(new FamilyAccount(
                    null, "+8801700000805", "Rokeya", FamilyAccount.Plan.FREE, t, t)).block();
            UUID doc = UUID.randomUUID();
            execute("INSERT INTO baymax.outbound_message (family_id, document_id, kind, urgency, urgency_reasons, body, gate_status) "
                    + "VALUES ('" + family.id() + "','" + doc + "','detail','this_week','follow_up_past_due,value_outside_range:ldl|stopgap','x','released')");

            assertThat(messages.latestOfKind(doc, OutboundMessage.Kind.DETAIL).block().urgencyReasons())
                    .containsExactly("follow_up_past_due", "value_outside_range:ldl|stopgap");
        });
    }

    @Test
    void sixteenConcurrentClaimsOfOneRowYieldExactlyOne() {
        runner().run(context -> {
            OutboundMessagePort messages = context.getBean(OutboundMessagePort.class);
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            Instant t = Instant.parse("2026-10-20T12:00:00Z");
            FamilyAccount family = records.createFamily(new FamilyAccount(
                    null, "+8801700000806", "Shirin", FamilyAccount.Plan.FREE, t, t)).block();
            OutboundMessage stored = messages.save(row(family.id(), UUID.randomUUID(), OutboundMessage.Kind.DETAIL, "বিস্তারিত", t)).block();

            List<OutboundMessage> won = reactor.core.publisher.Flux.range(0, 16)
                    .flatMap(i -> messages.claimDelivery(stored.id()).subscribeOn(reactor.core.scheduler.Schedulers.parallel()), 16)
                    .collectList().block(java.time.Duration.ofSeconds(30));

            assertThat(won).as("claims won on one row").hasSize(1);
            assertThat(column(stored.id(), "delivery_status")).isEqualTo("pending");
        });
    }

    /** Both directions: a failed delivery may be claimed again (the family wrote, the window is open); a sent one may not. */
    @Test
    void aFailedDeliveryMayBeClaimedAgainButASentOneMayNot() {
        runner().run(context -> {
            OutboundMessagePort messages = context.getBean(OutboundMessagePort.class);
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            Instant t = Instant.parse("2026-10-20T12:30:00Z");
            FamilyAccount family = records.createFamily(new FamilyAccount(
                    null, "+8801700000807", "Laila", FamilyAccount.Plan.FREE, t, t)).block();
            OutboundMessage stored = messages.save(row(family.id(), UUID.randomUUID(), OutboundMessage.Kind.DETAIL, "বিস্তারিত", t)).block();

            assertThat(messages.claimDelivery(stored.id()).blockOptional()).isPresent();
            assertThat(messages.claimDelivery(stored.id()).blockOptional()).as("pending: someone holds it").isEmpty();
            messages.recordDelivery(stored.id(), DeliveryOutcome.failed(DeliveryOutcome.WINDOW_EXPIRED), t.plusSeconds(5)).block();
            assertThat(messages.claimDelivery(stored.id()).blockOptional()).as("failed: may be tried again").isPresent();
            messages.recordDelivery(stored.id(), DeliveryOutcome.sent(com.elioo.baymax.wa.WaFixtures.wamid("8801700000807")), t.plusSeconds(9)).block();
            assertThat(messages.claimDelivery(stored.id()).blockOptional()).as("sent: never again").isEmpty();
        });
    }

    /** The log adapter's path stamps sent_at and clears the claim, inside V14's CHECKs. */
    @Test
    void theLogAdapterPathStampsSentAtAndClearsTheClaim() {
        runner().run(context -> {
            OutboundMessagePort messages = context.getBean(OutboundMessagePort.class);
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            Instant t = Instant.parse("2026-10-20T13:00:00Z");
            FamilyAccount family = records.createFamily(new FamilyAccount(
                    null, "+8801700000808", "Hasina", FamilyAccount.Plan.FREE, t, t)).block();
            OutboundMessage stored = messages.save(row(family.id(), UUID.randomUUID(), OutboundMessage.Kind.DETAIL, "বিস্তারিত", t)).block();

            messages.claimDelivery(stored.id()).block();
            messages.markSent(stored.id(), t.plusSeconds(1)).block();

            assertThat(column(stored.id(), "sent_at")).isNotNull();
            assertThat(column(stored.id(), "delivery_status")).isNull();
        });
    }

    /** End to end on Postgres: two asks deliver one stored row at the same instant; the channel sees it once. */
    @Test
    void twoConcurrentDeliversOfOneRowHandItToTheChannelOnce() {
        CountingDelivery.CALLS.set(0);
        runner().withUserConfiguration(CountingDelivery.class).run(context -> {
            OutboundMessagePort messages = context.getBean(OutboundMessagePort.class);
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            ExplainDocumentUseCase explainer = context.getBean(ExplainDocumentUseCase.class);
            Instant t = Instant.parse("2026-10-20T13:30:00Z");
            FamilyAccount family = records.createFamily(new FamilyAccount(
                    null, "+8801700000809", "Momena", FamilyAccount.Plan.FREE, t, t)).block();
            OutboundMessage stored = messages.save(row(family.id(), UUID.randomUUID(), OutboundMessage.Kind.DETAIL, "বিস্তারিত", t)).block();

            reactor.core.publisher.Flux.range(0, 2)
                    .flatMap(i -> explainer.deliver(stored).subscribeOn(reactor.core.scheduler.Schedulers.parallel()), 2)
                    .collectList().block(java.time.Duration.ofSeconds(30));

            assertThat(CountingDelivery.CALLS.get()).as("deliveries handed to the channel for one row").isEqualTo(1);
        });
    }

    @org.springframework.context.annotation.Configuration
    static class CountingDelivery {
        static final java.util.concurrent.atomic.AtomicInteger CALLS = new java.util.concurrent.atomic.AtomicInteger();

        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        MessageDeliveryPort countingDelivery() {
            return m -> {
                CALLS.incrementAndGet();
                return Mono.just(DeliveryOutcome.sent(com.elioo.baymax.wa.WaFixtures.wamid("8801700000809", CALLS.get())));
            };
        }
    }
}
```

- [ ] **Step 2: Run** `./gradlew :baymax:test --tests '*DetailCacheAcceptanceTest'` (Docker). Compile errors on
  `latestOfKind` and `claimDelivery` (new API). Then, after Step 3's port methods exist but **before** the reasons
  encoding changes, `urgencyReasonsSurviveTheRoundTripEvenWhenAMarkerNameHasAComma` must fail **on the defect**:
  `Expecting actual: ["value_critical:cholesterol", " total|stopgap"] to contain exactly (and in same order): ["value_critical:cholesterol, total|stopgap"]`
  (the marker name as `UrgencyService` writes it).
  That red is a regression guard for the encoding: paste it into the PR body.
- [ ] **Step 3: Implement.**
  - Port, after `latestDeliverable`:
    `/** The newest message of this kind for a document, whatever its gate state — the DETAIL cache (F4). Ties on created_at prefer a deliverable row, then the larger id, so the answer is the same on every read. */ Mono<OutboundMessage> latestOfKind(UUID documentId, OutboundMessage.Kind kind);`
    and
    `/** F4: atomically take the right to hand this row to the channel. Emits the row when THIS call took it; empty when it was sent, is being sent, or is not deliverable. A failed delivery may be claimed again. */ Mono<OutboundMessage> claimDelivery(UUID id);`
  - Adapter:

```java
    @Override
    public Mono<OutboundMessage> latestOfKind(UUID documentId, OutboundMessage.Kind kind) {
        return db.sql("SELECT " + COLS + " FROM " + T + " WHERE document_id = :d AND kind = :k "
                        + "ORDER BY created_at DESC, (gate_status IN ('released', 'approved') AND body IS NOT NULL) DESC, id DESC LIMIT 1")
                .bind("d", documentId).bind("k", kind.dbValue())
                .map((row, meta) -> from(row)).one();
    }

    @Override
    public Mono<OutboundMessage> claimDelivery(UUID id) {
        // one statement, so two concurrent asks cannot both win; V14's CHECKs hold ('pending' with sent_at NULL)
        return db.sql("UPDATE " + T + " SET delivery_status = 'pending' WHERE id = :id AND sent_at IS NULL "
                        + "AND (delivery_status IS NULL OR delivery_status = 'failed') "
                        + "AND gate_status IN ('released', 'approved') AND body IS NOT NULL RETURNING " + COLS)
                .bind("id", id).map((row, meta) -> from(row)).one();
    }
```

    `markSent` (the log adapter's path, which delivers nothing) becomes `SET sent_at = :at, delivery_status = NULL`, so
    a claimed row it stamps still satisfies `outbound_message_sent_at_requires_acceptance`.
    **Reasons encoding:** `save` binds `urgency_reasons` as a JSON array (`["a","b"]`, written with the adapter's own
    small encoder or Jackson); `from` reads a value starting with `[` as JSON and anything else with the legacy comma
    split, so rows written before this PR read as before. Phase 1b's V20 backfill reads both forms (roadmap).
  - `InMemoryOutboundMessages`: add `@Override` to `latestOfKind` and `claimDelivery`.
- [ ] **Step 4:** `./gradlew :baymax:test --tests '*DetailCacheAcceptanceTest' --tests '*DeliveryOutcomeAcceptanceTest' --tests '*PostgresOutboundMessageAdapter*'` green.
- [ ] **Step 5:** stage the port, the adapter, the fake and the test;
  `feat(baymax): latestOfKind and an atomic delivery claim on outbound messages; reasons stored as a JSON array (F4)`.

### Task 5.5: Split `ExplanationService` — `composeChecked` / `release` / `deliver`, DETAIL cached

**Files:** modify `outbound/application/port/in/ExplainDocumentUseCase.java`, `ExplanationService.java` (`:63-221`),
`OutboundMessage.java` (`:25` javadoc), `ReviewGateService.java` (claim before delivering); tests `ExplanationServiceTest.java`,
`ReviewGateServiceTest.java`. Callers keep working: `DocumentProcessingChain` (`explain`) and `DocumentHandler.java:168`
(`detail`; same response shape, no WhatsApp side effect any more).

- [ ] **Step 1: Write the failing tests** (new API, red by compile error). In `wire()` add
  `when(messages.latestOfKind(any(), any())).thenReturn(Mono.empty());` and
  `when(messages.claimDelivery(any())).thenAnswer(i -> messages.find(i.getArgument(0)));`. Then:

```java
    private static final String NOW_REPLY = "HbA1c এসেছে 11.2 %, যা স্বাভাবিকের চেয়ে অনেক বেশি। এখনই ডাক্তারের কাছে যান। দেরি করবেন না।";

    private static OutboundMessage storedDetail(Urgency urgency, List<String> reasons, OutboundMessage.GateStatus status, String body) {
        return new OutboundMessage(UUID.randomUUID(), FAMILY, PATIENT, DOC, OutboundMessage.Kind.DETAIL, urgency, reasons,
                body, status, null, null, null, null, NOW);
    }

    /** F4: composition is pure — no row, no reviewer, no delivery — in both gate modes; the gate status is decided. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"off", "urgency"})
    void composeCheckedNeitherSavesNorParksNorDelivers(String mode) {
        properties.getOutbound().setGateMode(mode);
        done(CRITICAL);
        replies.push(NOW_REPLY);

        StepVerifier.create(service.composeChecked(DOC, OutboundMessage.Kind.DETAIL)).assertNext(m -> {
            assertThat(m.id()).isNull();
            assertThat(m.kind()).isEqualTo(OutboundMessage.Kind.DETAIL);
            assertThat(m.urgency()).isEqualTo(Urgency.NOW);
            assertThat(m.gateStatus()).isEqualTo(mode.equals("off") ? OutboundMessage.GateStatus.RELEASED : OutboundMessage.GateStatus.PENDING);
            assertThat(m.body()).contains(new Copy().bn("urgency.now", Map.of()));
        }).verifyComplete();
        verify(messages, never()).save(any());
        verify(reviewer, never()).notifyPending(any());
        verify(delivery, never()).deliver(any());
    }

    @Test
    void composeCheckedForDetailOnARetakeDocumentIsEmpty() {
        when(documents.find(DOC)).thenReturn(Mono.just(new Document(DOC, PATIENT, FAMILY, null, null, null, null, 0.6,
                Document.Status.NEEDS_RETAKE, "blurry", null, null, 1, NOW, NOW)));
        StepVerifier.create(service.composeChecked(DOC, OutboundMessage.Kind.DETAIL)).verifyComplete();
        StepVerifier.create(service.composeChecked(DOC, OutboundMessage.Kind.EXPLANATION))
                .assertNext(m -> assertThat(m.kind()).isEqualTo(OutboundMessage.Kind.RETAKE)).verifyComplete();
        verify(messages, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"off", "urgency"})
    void releaseParksAPendingMessageAndDeliversOnlyAReleasedOne(String mode) {
        properties.getOutbound().setGateMode(mode);
        done(CRITICAL);
        replies.push(NOW_REPLY);
        OutboundMessage composed = service.composeChecked(DOC, OutboundMessage.Kind.EXPLANATION).block();

        OutboundMessage released = service.release(composed).block();

        verify(messages, times(1)).save(any());
        if (mode.equals("off")) {
            verify(delivery, times(1)).deliver(any());
            verify(reviewer, never()).notifyPending(any());
        } else {
            assertThat(released.gateStatus()).isEqualTo(OutboundMessage.GateStatus.PENDING);
            verify(delivery, never()).deliver(any());
            verify(reviewer, times(1)).notifyPending(any());
        }
    }

    @Test
    void releaseRefusesAMessageThatIsAlreadyStored() {
        StepVerifier.create(service.release(storedDetail(Urgency.ROUTINE, List.of(), OutboundMessage.GateStatus.RELEASED, "x")))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalStateException.class).hasMessageContaining("unsaved"))
                .verify();
        verify(messages, never()).save(any());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = OutboundMessage.GateStatus.class, names = {"PENDING", "REJECTED", "FAILED_SAFETY"})
    void deliverRefusesAnythingNotReleasedOrApproved(OutboundMessage.GateStatus status) {
        StepVerifier.create(service.deliver(storedDetail(Urgency.NOW, List.of(), status, "unreviewed")))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("gate=" + status.dbValue()))
                .verify();
        verify(delivery, never()).deliver(any());
    }

    /** The cache, protected direction: same urgency and reasons → the stored row, no model call, no new row. */
    @Test
    void detailReturnsTheCachedRowWhenTheUrgencyIsUnchanged() {
        done(NORMAL);
        OutboundMessage cached = storedDetail(Urgency.ROUTINE, List.of(), OutboundMessage.GateStatus.RELEASED, "আগের বিস্তারিত");
        when(messages.latestOfKind(DOC, OutboundMessage.Kind.DETAIL)).thenReturn(Mono.just(cached));

        StepVerifier.create(service.detail(DOC)).expectNext(cached).verifyComplete();
        verify(metered, never()).invoke(any(), any(), any(LlmRequest.class));
        verify(messages, never()).save(any());
        verify(delivery, never()).deliver(any());
    }

    /** The cache, dangerous direction: urgency rose since the row was stored → recompose; a stale row would understate it. */
    @Test
    void detailIsRecomposedWhenTheUrgencyHasRisenSinceItWasCached() {
        done(CRITICAL);
        when(messages.latestOfKind(DOC, OutboundMessage.Kind.DETAIL)).thenReturn(Mono.just(
                storedDetail(Urgency.ROUTINE, List.of(), OutboundMessage.GateStatus.RELEASED, "আগের বিস্তারিত")));
        replies.push(NOW_REPLY);

        StepVerifier.create(service.detail(DOC)).assertNext(m -> {
            assertThat(m.urgency()).isEqualTo(Urgency.NOW);
            assertThat(m.body()).isNotEqualTo("আগের বিস্তারিত");
        }).verifyComplete();
        verify(metered, times(1)).invoke(eq(AiCallPurpose.EXPLAIN), any(), any(LlmRequest.class));
        verify(delivery, never()).deliver(any());
    }

    /** HC2 in the cache: a cached row at a HIGHER urgency than a fresh assessment is kept, never replaced by a lower one. */
    @Test
    void aCachedDetailAtAHigherUrgencyIsNeverReplacedByALowerOne() {
        done(NORMAL);
        OutboundMessage cached = storedDetail(Urgency.NOW, List.of("value_critical:hba1c|stopgap"), OutboundMessage.GateStatus.RELEASED, "জরুরি");
        when(messages.latestOfKind(DOC, OutboundMessage.Kind.DETAIL)).thenReturn(Mono.just(cached));

        StepVerifier.create(service.detail(DOC)).expectNext(cached).verifyComplete();
        verify(messages, never()).save(any());
    }

    /** Fail-closed is final for that urgency: regenerating until the checklist passes would make it a filter over chance. */
    @Test
    void aCachedDetailThatFailedSafetyIsNotRegeneratedOnEveryAsk() {
        done(NORMAL);
        OutboundMessage cached = storedDetail(Urgency.ROUTINE, List.of(), OutboundMessage.GateStatus.FAILED_SAFETY, null);
        when(messages.latestOfKind(DOC, OutboundMessage.Kind.DETAIL)).thenReturn(Mono.just(cached));

        StepVerifier.create(service.detail(DOC)).expectNext(cached).verifyComplete();
        verify(metered, never()).invoke(any(), any(), any(LlmRequest.class));
    }

    /** A miss stores the composed DETAIL (parked if PENDING) and never delivers it — delivery is the caller's call. */
    @ParameterizedTest
    @ValueSource(strings = {"off", "urgency"})
    void aDetailCacheMissIsStoredButNotDelivered(String mode) {
        properties.getOutbound().setGateMode(mode);
        done(CRITICAL);
        replies.push(NOW_REPLY);

        OutboundMessage m = service.detail(DOC).block();

        assertThat(m.id()).isNotNull();
        assertThat(m.sentAt()).isNull();
        verify(messages, times(1)).save(any());
        verify(delivery, never()).deliver(any());
        verify(reviewer, times(mode.equals("urgency") ? 1 : 0)).notifyPending(any());
    }

    /** Same level, different reasons: the new reason must reach the family, so the cache misses and recomposes. */
    @Test
    void detailIsRecomposedWhenTheLevelIsTheSameButTheReasonsChanged() {
        done(CRITICAL);
        when(messages.latestOfKind(DOC, OutboundMessage.Kind.DETAIL)).thenReturn(Mono.just(storedDetail(Urgency.NOW,
                List.of("value_critical:another_marker|stopgap"), OutboundMessage.GateStatus.RELEASED, "পুরনো বিস্তারিত")));
        replies.push(NOW_REPLY);

        StepVerifier.create(service.detail(DOC)).assertNext(m -> assertThat(m.body()).isNotEqualTo("পুরনো বিস্তারিত"))
                .verifyComplete();
        verify(metered, times(1)).invoke(eq(AiCallPurpose.EXPLAIN), any(), any(LlmRequest.class));
    }

    /** A REJECTED detail is a hit: recomposing after a reject would route around the reviewer. */
    @Test
    void aCachedRejectedDetailIsReturnedNotRecomposed() {
        done(NORMAL);
        OutboundMessage cached = storedDetail(Urgency.ROUTINE, List.of(), OutboundMessage.GateStatus.REJECTED, "x");
        when(messages.latestOfKind(DOC, OutboundMessage.Kind.DETAIL)).thenReturn(Mono.just(cached));

        StepVerifier.create(service.detail(DOC)).expectNext(cached).verifyComplete();
        verify(metered, never()).invoke(any(), any(), any(LlmRequest.class));
        verify(messages, never()).save(any());
    }

    /** deliver hands a row to the channel only after winning the claim; a lost claim is empty, not a second send. */
    @Test
    void deliverOnALostClaimSendsNothing() {
        OutboundMessage stored = storedDetail(Urgency.ROUTINE, List.of(), OutboundMessage.GateStatus.RELEASED, "বিস্তারিত");
        when(messages.claimDelivery(stored.id())).thenReturn(Mono.empty());

        StepVerifier.create(service.deliver(stored)).verifyComplete();
        verify(delivery, never()).deliver(any());
    }
```

  In `ReviewGateServiceTest`, stub `claimDelivery` in its wiring the same way, and add
  `aSecondApproveOfTheSameRowDoesNotDeliverAgain`: `claimDelivery` returns empty → `approve` completes with the decided
  row and `delivery.deliver` is never called.
- [ ] **Step 2: Run** `./gradlew :baymax:test --tests '*ExplanationServiceTest' --tests '*ReviewGateServiceTest'` →
  compile errors `cannot find symbol: method composeChecked(UUID, Kind)` / `release(OutboundMessage)` /
  `deliver(OutboundMessage)`. New-API tests; the regression guards are Task 5.2's.
- [ ] **Step 3: Implement.** `ExplainDocumentUseCase` becomes:

```java
public interface ExplainDocumentUseCase {

    /**
     * Composes and safety-checks the family's message for a document, and decides its gate status. NEVER saves and
     * NEVER delivers (F4). EXPLANATION on NEEDS_RETAKE is the retake prompt; DETAIL exists only for DONE. Empty for
     * any other status. The message may be FAILED_SAFETY with no body.
     */
    Mono<OutboundMessage> composeChecked(UUID documentId, OutboundMessage.Kind kind);

    /** Persists a composed, unsaved message: PENDING → reviewer, FAILED_SAFETY → stored only, RELEASED → delivered. */
    Mono<OutboundMessage> release(OutboundMessage composed);

    /**
     * Hands a STORED, released-or-approved row to delivery, at most once (an atomic claim on the row), and records the
     * outcome (V13/V14). Empty when the claim is lost (the row is being or was delivered). Errors for anything else.
     */
    Mono<OutboundMessage> deliver(OutboundMessage stored);

    /** The post-extraction message: {@code composeChecked(EXPLANATION)} then {@code release}. */
    Mono<OutboundMessage> explain(UUID documentId);

    /**
     * The document's DETAIL row: the cached one while its urgency is still current, otherwise composed and STORED
     * (parked if PENDING). Never delivered here — the WhatsApp answer calls {@link #deliver}; the web only shows it.
     */
    Mono<OutboundMessage> detail(UUID documentId);
}
```

  `ExplanationService`, by location: `explain` (`:63-66`) →
  `return composeChecked(documentId, OutboundMessage.Kind.EXPLANATION).flatMap(this::release);`. Delete `detail`'s
  one-liner (`:68-71`) and `compose(UUID, boolean)` (`:73-82`). Add:

```java
    @Override
    public Mono<OutboundMessage> composeChecked(UUID documentId, OutboundMessage.Kind kind) {
        if (kind != OutboundMessage.Kind.EXPLANATION && kind != OutboundMessage.Kind.DETAIL) {
            return Mono.error(new IllegalArgumentException("composeChecked composes EXPLANATION or DETAIL, not " + kind));
        }
        return documents.find(documentId)
                .switchIfEmpty(Mono.error(BaymaxException.notFound("document_not_found", "no document with id " + documentId)))
                .flatMap(document -> switch (document.status()) {
                    // a detail request on a retake used to file ANOTHER retake row and escalate the next retake copy
                    case NEEDS_RETAKE -> kind == OutboundMessage.Kind.EXPLANATION ? retake(document) : Mono.<OutboundMessage>empty();
                    case DONE -> facts(document).flatMap(facts -> compose(facts, kind, urgency.assess(facts)));
                    default -> Mono.<OutboundMessage>empty();
                });
    }

    @Override
    public Mono<OutboundMessage> detail(UUID documentId) {
        return documents.find(documentId)
                .switchIfEmpty(Mono.error(BaymaxException.notFound("document_not_found", "no document with id " + documentId)))
                .filter(document -> document.status() == Document.Status.DONE)
                .flatMap(document -> facts(document).flatMap(facts -> {
                    UrgencyAssessment assessed = urgency.assess(facts);
                    return messages.latestOfKind(documentId, OutboundMessage.Kind.DETAIL)
                            .filter(cached -> stillCurrent(cached, assessed))
                            .doOnNext(cached -> log.info("[baymax] detail cache hit documentId={} messageId={} gate={}",
                                    documentId, cached.id(), cached.gateStatus().dbValue()))
                            .switchIfEmpty(Mono.defer(() -> compose(facts, OutboundMessage.Kind.DETAIL, assessed)
                                    .flatMap(this::store)));
                }));
    }

    /**
     * A cached DETAIL answers only for the urgency it was composed at. Urgency can rise after composition
     * (follow_up_past_due is date-driven, UrgencyService:96-108), and a stale row would understate it — so a row at a
     * LOWER level, or the same level for different reasons, is recomposed. A row at a HIGHER level is kept: nothing
     * lowers urgency (HC2; F1 makes this central in phase 1b). FAILED_SAFETY and REJECTED rows are hits too:
     * regenerating until the checklist passes would make it a filter over chance, and recomposing after a reject
     * would route around the reviewer.
     */
    static boolean stillCurrent(OutboundMessage cached, UrgencyAssessment now) {
        if (cached.urgency() != now.level()) {
            return cached.urgency().isAtLeast(now.level());
        }
        return new java.util.HashSet<>(cached.urgencyReasons() == null ? List.of() : cached.urgencyReasons())
                .equals(new java.util.HashSet<>(now.reasons()));
    }
```

  `retake(Document)` (`:90-98`) builds the same `OutboundMessage` but **returns** it instead of `release(...)`.
  `explanation(facts, medicines, detail)` (`:115-198`) becomes
  `private Mono<OutboundMessage> compose(DocumentFacts facts, OutboundMessage.Kind kind, UrgencyAssessment assessed)`:
  it loads `documents.medicinesOf(...)`, runs the existing body pipeline with `boolean detail = kind == DETAIL`, drops
  `:116` (the assessment is a parameter now), and ends with
  `.flatMap(message -> message.urgency() != assessed.level() ? Mono.error(new IllegalStateException("urgency changed after assessment")) : Mono.just(message))`.
  The checklist, verbatim blocks and cap (`:117-190`) do not change. Replace `release` (`:200-221`) with:

```java
    @Override
    public Mono<OutboundMessage> release(OutboundMessage composed) {
        if (composed.id() != null) {
            return Mono.error(new IllegalStateException("release takes a composed, unsaved message; got id=" + composed.id()));
        }
        return store(composed).flatMap(saved -> saved.gateStatus() == OutboundMessage.GateStatus.RELEASED
                ? deliver(saved) : Mono.just(saved));
    }

    /** Persist only: PENDING is parked with the reviewer, FAILED_SAFETY is recorded, RELEASED waits for deliver(). */
    private Mono<OutboundMessage> store(OutboundMessage m) {
        return switch (m.gateStatus()) {
            case PENDING -> messages.save(m).flatMap(saved -> reviewer.notifyPending(saved).thenReturn(saved));
            case FAILED_SAFETY -> messages.save(m);
            // sentAt stays NULL (V13/V14): only a provider acceptance sets it, and nothing has been attempted yet
            case RELEASED -> messages.save(new OutboundMessage(null, m.familyId(), m.patientId(), m.documentId(), m.kind(),
                    m.urgency(), m.urgencyReasons(), m.body(), OutboundMessage.GateStatus.RELEASED, null, null, null, null, m.createdAt()));
            case APPROVED, REJECTED -> Mono.error(new IllegalStateException("only the reviewer decides " + m.gateStatus().dbValue()));
        };
    }

    @Override
    public Mono<OutboundMessage> deliver(OutboundMessage stored) {
        if (stored.id() == null || !stored.isDeliverable()) {
            return Mono.error(new IllegalStateException("not deliverable: id=" + stored.id()
                    + " gate=" + stored.gateStatus().dbValue()));
        }
        Instant now = clock.instant();
        // F4: a row reaches the channel at most once. The claim is one UPDATE … WHERE sent_at IS NULL AND
        // (delivery_status IS NULL OR delivery_status = 'failed') RETURNING; empty means another caller holds the row.
        return messages.claimDelivery(stored.id())
                .flatMap(claimed -> delivery.deliver(claimed)
                        .onErrorResume(e -> {
                            log.error("[baymax] released message delivery threw id={} error={}", stored.id(), e.getClass().getSimpleName());
                            return Mono.just(DeliveryOutcome.failed(DeliveryOutcome.SEND_FAILED));
                        })
                        .flatMap(outcome -> outcome.status() == null
                                // the log adapter delivered nothing; stamp sent_at (markSent also clears the claim)
                                ? messages.markSent(stored.id(), now).then(messages.find(stored.id()))
                                : messages.recordDelivery(stored.id(), outcome, now)));
    }
```

  `ReviewGateService.approve`: after `decide(...APPROVED...)`, `messages.claimDelivery(id).flatMap(delivery::deliver …)`
  with the existing outcome recording; an empty claim returns the decided row. Update the class javadoc of
  `ExplanationService` (composition, persistence and delivery are three steps; `deliver` is the only path to
  `MessageDeliveryPort`, behind the claim) and `OutboundMessage.java:25` ("RELEASED = not gated; deliverable. Whether it
  was delivered is `sent_at` / `delivery_status` (V13), not this status — a DETAIL stored for the web is RELEASED and
  undelivered.").
- [ ] **Step 4: Run** `./gradlew :baymax:test --tests '*ExplanationServiceTest' --tests '*ReviewGateServiceTest' --tests '*DocumentIntakeServiceTest' --tests '*BaymaxDocumentRouterTest' --tests '*DetailDeliveryGuardsTest'`.
  Expected half-way state: green — `aWebDetailRequest…`, `aDetailRequestOnARetakeDocument…`, and
  `gateOffTheDetailArrivesExactlyOnce` (only because `onText` is now the sole sender); still red until Task 5.6 —
  `underTheUrgencyGate…NeverSent`, `…ApprovedDetailArrivesExactlyOnce`, `gateOffAskingTwice…`,
  `aDetailThatFailedClosed…`, `aSecondAskAfterAFailClosedDetail…` and the ArchUnit rule. Do not push here.
- [ ] **Step 5:** stage the use case, the service, `OutboundMessage`, `ReviewGateService` and both tests;
  `refactor(baymax): compose, release and deliver are separate steps; DETAIL cached per document (F4)`.

### Task 5.6: `onText` sends fixed copy only; the body goes through `deliver`

**Files:** modify `wa/application/service/WaIntakeService.java` (`onText` `:92-111`); test `WaIntakeServiceTest.java`.

`theWordTheExplanationInvitesIsAnswered` (`WaIntakeServiceTest.java:187-201`) asserts that `onText` sends the body
itself; it mocks the delivering `detail()`, so it cannot see the second send. It encodes the defect and is rewritten,
not kept (CLAUDE.md: a test whose name encodes surprising behaviour is evidence of a defect).

- [ ] **Step 1: Write the failing tests.** Replace `:187-201` and add per-state tests (records, not mocks, so every field
  is explicit):

```java
    private static final UUID DOC = UUID.randomUUID();

    private void latestDone() {
        Document doc = mock(Document.class);
        when(doc.id()).thenReturn(DOC);
        when(documents.latestDoneDocument(FAMILY)).thenReturn(Mono.just(doc));
    }

    private static OutboundMessage detailRow(OutboundMessage.GateStatus status, Urgency urgency, String body, Instant sentAt) {
        return new OutboundMessage(UUID.randomUUID(), FAMILY, null, DOC, OutboundMessage.Kind.DETAIL, urgency, List.of(),
                body, status, null, null, null, sentAt, Instant.EPOCH);
    }

    private String link() {
        return props.getOutbound().getPublicBaseUrl() + "/app/documents/" + DOC;
    }

    /**
     * Every explanation ends with বিস্তারিত শুনতে চাইলে লিখুন "বিস্তারিত", so the word must be answered. The body
     * reaches the family through deliver() — the delivery port records the outcome — and never from onText (F4).
     */
    @Test
    void theInvitedWordIsAnsweredByDeliveringTheStoredDetailNotBySendingItHere() {
        familyWith(patient("মা"));
        latestDone();
        OutboundMessage stored = detailRow(OutboundMessage.GateStatus.RELEASED, Urgency.ROUTINE, "বিস্তারিত: সব স্বাভাবিক", null);
        when(explainer.detail(DOC)).thenReturn(Mono.just(stored));
        when(explainer.deliver(stored)).thenReturn(Mono.just(detailRow(OutboundMessage.GateStatus.RELEASED, Urgency.ROUTINE,
                stored.body(), Instant.EPOCH)));

        assertThat(service.onText(FROM, "বিস্তারিত").block()).isEqualTo(WaIntakeService.Outcome.ACCEPTED);
        verify(explainer).deliver(stored);
        verify(graph, never()).sendText(anyString(), anyString());
    }

    @Test
    void aDetailAlreadyDeliveredIsNotSentAgain() {
        familyWith(patient("মা"));
        latestDone();
        when(explainer.detail(DOC)).thenReturn(Mono.just(
                detailRow(OutboundMessage.GateStatus.RELEASED, Urgency.ROUTINE, "বিস্তারিত: সব স্বাভাবিক", Instant.EPOCH)));

        service.onText(FROM, "বিস্তারিত").block();

        verify(explainer, never()).deliver(any());
        verify(graph).sendText(E164, new Copy().bn("wa.detail.already_sent", Map.of("link", link())));
    }

    /** D5 on WhatsApp: a pending NOW gets its urgency line verbatim at once, the red-flag line, and never the body. */
    @Test
    void aPendingNowDetailGetsTheUrgencyAndTheRedFlagLineNeverTheBody() {
        familyWith(patient("মা"));
        latestDone();
        when(explainer.detail(DOC)).thenReturn(Mono.just(
                detailRow(OutboundMessage.GateStatus.PENDING, Urgency.NOW, "অপরীক্ষিত লেখা", null)));
        Copy copy = new Copy();

        service.onText(FROM, "বিস্তারিত").block();

        verify(explainer, never()).deliver(any());
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(graph).sendText(eq(E164), sent.capture());
        assertThat(sent.getValue()).isEqualTo(String.join("\n", copy.bn("urgency.now", Map.of()),
                copy.bn("safety.red_flag_line", Map.of()), copy.bn("wa.detail.pending", Map.of())));
        assertThat(sent.getValue()).isNotEqualTo("অপরীক্ষিত লেখা");
        assertThat(sent.getValue().length()).isLessThanOrEqualTo(props.getOutbound().getMaxChars());
    }

    @Test
    void aPendingRoutineDetailGetsThePendingLineAlone() {
        familyWith(patient("মা"));
        latestDone();
        when(explainer.detail(DOC)).thenReturn(Mono.just(detailRow(OutboundMessage.GateStatus.PENDING, Urgency.ROUTINE, "x", null)));
        service.onText(FROM, "বিস্তারিত").block();
        verify(graph).sendText(E164, new Copy().bn("wa.detail.pending", Map.of()));
    }

    /** F3: REJECTED and FAILED_SAFETY get the urgency (if any) and the paper link — never "দেখা হচ্ছে", never silence. */
    @ParameterizedTest
    @EnumSource(value = OutboundMessage.GateStatus.class, names = {"REJECTED", "FAILED_SAFETY"})
    void aWithheldDetailIsAnsweredWithTheUrgencyAndTheLink(OutboundMessage.GateStatus status) {
        familyWith(patient("মা"));
        latestDone();
        when(explainer.detail(DOC)).thenReturn(Mono.just(detailRow(status, Urgency.THIS_WEEK, null, null)));
        Copy copy = new Copy();

        service.onText(FROM, "বিস্তারিত").block();

        String expected = String.join("\n", copy.bn("urgency.this_week", Map.of()), copy.bn("safety.red_flag_line", Map.of()),
                copy.bn("wa.detail.unavailable", Map.of("link", link())));
        verify(graph).sendText(E164, expected);
        assertThat(expected).doesNotContain("দেখা হচ্ছে");
    }
    /** CLAUDE.md: the cap is asserted on what was SENT — every gate state × urgency × delivered-or-not, through onText. */
    @Test
    void everyFixedDetailReplyActuallySentFitsTheCap() {
        familyWith(patient("মা"));
        latestDone();
        List<String> sent = new java.util.ArrayList<>();
        for (OutboundMessage.GateStatus status : OutboundMessage.GateStatus.values()) {
            for (Urgency urgency : Urgency.values()) {
                for (Instant sentAt : java.util.Arrays.asList(null, Instant.EPOCH)) {
                    org.mockito.Mockito.clearInvocations(graph);
                    OutboundMessage row = detailRow(status, urgency,
                            status == OutboundMessage.GateStatus.FAILED_SAFETY ? null : "লেখা", sentAt);
                    when(explainer.detail(DOC)).thenReturn(Mono.just(row));
                    when(explainer.deliver(any())).thenReturn(Mono.just(row));
                    service.onText(FROM, "বিস্তারিত").block();
                    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
                    verify(graph, org.mockito.Mockito.atLeast(0)).sendText(eq(E164), body.capture());
                    for (String b : body.getAllValues()) {
                        assertThat(b.length()).as(status + "/" + urgency + "/" + sentAt + ": " + b)
                                .isLessThanOrEqualTo(props.getOutbound().getMaxChars());
                        sent.add(b);
                    }
                }
            }
        }
        assertThat(sent).as("vacuous if no fixed reply was sent").hasSizeGreaterThanOrEqualTo(9);
    }
```

- [ ] **Step 2: Run** `./gradlew :baymax:test --tests '*WaIntakeServiceTest'` → `Wanted but not invoked:
  explainDocumentUseCase.deliver(…)` on the rewritten test and the fixed-copy tests failing on the current body send.
  The guards that count are Task 5.2's replays.
- [ ] **Step 3: Implement.** In `onText`, replace the detail branch (`:100-108`):

```java
                .flatMap(family -> isDetailRequest(text)
                        ? documents.latestDoneDocument(family.id())
                                .flatMap(document -> explainer.detail(document.id()))
                                .flatMap(message -> answerDetail(number, message))
                                .switchIfEmpty(Mono.defer(() -> reply(number, "wa.no_document", Outcome.ACCEPTED,
                                        "detail asked for but no readable document yet")))
                        : Mono.just(Outcome.ACCEPTED))
```

  Below it, the only two methods that answer a detail ask:

```java
    /**
     * F4: this method never sends a composed body. A released or approved row not yet delivered goes through
     * explainer.deliver, which claims the row (at most once), records the provider's outcome (V13/V14) and is refused by
     * the delivery adapter for anything unreviewed. Every other state gets fixed copy: PENDING the "being checked" line,
     * REJECTED and FAILED_SAFETY the paper link, an already-delivered detail a pointer to it. Urgent documents lead with
     * the UrgencyLines block (D5): the urgency is never held back for review.
     */
    private Mono<Outcome> answerDetail(String number, OutboundMessage m) {
        return switch (m.gateStatus()) {
            case RELEASED, APPROVED -> m.sentAt() == null
                    ? explainer.deliver(m)
                            .map(d -> d.sentAt() != null ? Outcome.ACCEPTED : Outcome.FAILED)
                            // the claim was lost: a concurrent ask is delivering this very row right now
                            .switchIfEmpty(Mono.defer(() -> fixed(number, m, "wa.detail.already_sent", "detail being delivered")))
                    : fixed(number, m, "wa.detail.already_sent", "detail already delivered");
            case PENDING -> fixed(number, m, "wa.detail.pending", "detail pending review");
            case REJECTED, FAILED_SAFETY -> fixed(number, m, "wa.detail.unavailable", "detail withheld");
        };
    }

    private Mono<Outcome> fixed(String number, OutboundMessage m, String key, String why) {
        String text = UrgencyLines.lead(copy, m.urgency(), copy.bn(key,
                Map.of("link", properties.getOutbound().getPublicBaseUrl() + "/app/documents/" + m.documentId())));
        log.info("[baymax] wa detail answered documentId={} gate={} urgency={} reason={} chars={}",
                m.documentId(), m.gateStatus().dbValue(), m.urgency().dbValue(), why, text.length());
        return send(number, text).thenReturn(Outcome.ACCEPTED);
    }
```

  Imports: `OutboundMessage`, `UrgencyLines`. Update the `onText` javadoc: "the body is delivered by the delivery port,
  never sent from here (F4)". The log lines carry ids, gate, urgency, a fixed reason and a size: no number, no text.
- [ ] **Step 4: Run** `./gradlew :baymax:test --tests '*WaIntakeServiceTest' --tests '*DetailDeliveryGuardsTest' --tests '*WaNeverSendsAComposedBodyTest' --tests '*CopyInstructionsHaveHandlersTest'`:
  every Task 5.2 guard is green, in both gate modes.
- [ ] **Step 5:** stage `WaIntakeService.java` and `WaIntakeServiceTest.java`;
  `fix(baymax): বিস্তারিত is delivered once through the delivery port; pending and withheld details get fixed copy (F4)`.

### Task 5.7: Only claiming callers may deliver

**Files:** create `baymax/src/test/java/com/elioo/baymax/architecture/DeliveryCallersClaimFirstTest.java`.

- [ ] **Step 1: Write the rule** (derived from the bytecode; a new caller fails it until it claims):

```java
class DeliveryCallersClaimFirstTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.elioo.baymax");

    /** F4: a stored row reaches a family at most once. Whoever hands a row to the channel claims it first. */
    @Test
    void onlyKnownCallersDeliverAndTheOnesDeliveringStoredRowsClaimFirst() {
        noClasses().that().doNotHaveSimpleName("ExplanationService").and().doNotHaveSimpleName("ReviewGateService")
                .and().doNotHaveSimpleName("NudgeComposer")
                .should().callMethod(MessageDeliveryPort.class, "deliver", OutboundMessage.class)
                .because("a new deliverer must claim the row first (OutboundMessagePort.claimDelivery)")
                .check(CLASSES);
        classes().that().haveSimpleName("ExplanationService").or().haveSimpleName("ReviewGateService")
                .should().callMethod(OutboundMessagePort.class, "claimDelivery", java.util.UUID.class)
                .check(CLASSES);
    }
}
```

  `NudgeComposer` delivers only the row it has just created in the same flow, so no second asker exists; the PR body
  says so.
- [ ] **Step 2: Replay.** Temporarily remove the `claimDelivery` call from `ReviewGateService.approve`: the rule must fail
  with `Architecture Violation … ReviewGateService … should call method OutboundMessagePort.claimDelivery`. Restore.
- [ ] **Step 3–5:** green; `test(baymax): only claiming callers hand a stored row to the channel (F4)`.

### Task 5.8: Verify, replay on record, ship

- [ ] **Step 1: Replay evidence.** In a scratch worktree at `origin/main` (PR 4 merged) with **only** the Task 5.2 test
  files copied in, rerun Task 5.2's command and diff the failure messages against the recorded ones; each guard must
  fail on its defect, not elsewhere. Paste the output into the PR body.
- [ ] **Step 2:** the standard pre-merge. The PR body lists: the defects fixed (double-send, gate bypass, web-side
  WhatsApp send, retake row from a detail request, silence on fail-closed, a paid recompose on every ask, reasons that
  could not survive the database, two concurrent asks both delivering); the behaviour changes for the PO (the web detail
  no longer texts the family; a second বিস্তারিত gets "আগেই পাঠানো হয়েছে" plus the link; a failed-safety detail is final;
  the red-flag line now accompanies THIS_WEEK lines too; new draft copy keys); the red replays; "no migration".
- [ ] **Known gap (build report):** a crash between the claim and the recorded outcome leaves the row `pending`; the next
  ask then gets the pointer and the link rather than the body. PR 8's unanswered-message count surfaces such restarts.

**Try it on your phone (PR 5):**
- [ ] Send `t1-routine-lab.jpg`; wait for the explanation; type "বিস্তারিত". The detail arrives **once**.
- [ ] Type "বিস্তারিত" again: one reply, "বিস্তারিত আগেই পাঠানো হয়েছে …" with the link, and no second copy of the detail.
- [ ] Open the link: the paper and its crops open on the web.
- [ ] Do **not** flip `BAYMAX_GATE_MODE` to try the pending path; `DetailDeliveryGuardsTest` proves it.

**Post-deploy:** the standard check, plus `docker logs medscribe-ai --since 15m 2>&1 | grep -c 'wa delivery refused'` is
0 (the new central guard never fires in normal operation).

---

## PR 6 — B1a: every message a family sends gets exactly one reply

Branch `feat/baymax-wa-every-message-answered`, from merged PR 5. Today, text other than the literal
বিস্তারিত/detail/bistarito gets silence (`WaIntakeService.java:97-111`, locked in by
`WaIntakeServiceTest.anOrdinaryTextIsAcknowledgedWithoutAReply`, `:203-208`); voice, stickers, reactions and button taps
are dropped at the webhook (`WaWebhookHandler.java:112-113`); a DOCX is acknowledged and then dropped. This PR covers
spec §6 W1 (greeting, without the standing urgency, which is PR 7), W8 (detail synonyms only), W11 (voice, D13), W12
(thanks, without urgency), W23 (off-topic → the honest fallback) and the Phase 0 rule that **every fallback, voice and
sticker reply carries the approved red-flag line** until D11's emergency template ships. D4 holds: nothing here answers
a medicine-purpose question; such text gets the fallback.

**This PR owns `WaInbound` and `WaWebhookHandler.parse`.** One record, a typed `Type` enum covering every Meta message
type, and a provider timestamp (used by PR 8). PR 8 adds the claim in front of the single entry point `onInbound`; it
does not define a second record or a second type switch.

### Task 6.1: The copy this PR sends

**Files:** modify `messages_bn.properties`, `messages_en.properties` (after PR 5's keys).

- [ ] **Step 1:** `MessagesBundleTest` (PR 5) is the test: adding a key to one bundle only fails it.
- [ ] **Step 2: Add** (bn | en), under `# Phase 0 PR 6 — drafts for the copy owner`:

  | key | bn | en |
  |---|---|---|
  | `wa.greeting.hello` | হ্যালো! | Hello! |
  | `wa.greeting.salam` | ওয়ালাইকুম আসসালাম। | Wa alaikum assalam. |
  | `wa.greeting.namaskar` | নমস্কার। | Namaskar. |
  | `wa.greeting.intro` | আমি Medioo — পরিবারের ডাক্তারি কাগজ গুছিয়ে রাখি আর সহজ বাংলায় বুঝিয়ে বলি। আমি ডাক্তার নই। | I am Medioo — I keep the family's medical papers in order and explain them in plain Bangla. I am not a doctor. |
  | `wa.greeting.none` | রিপোর্ট বা প্রেসক্রিপশনের ছবি পাঠান — পড়ে সহজ বাংলায় বুঝিয়ে বলব। | Send a photo of a report or prescription — I will read it and explain it simply. |
  | `wa.thanks` | ঠিক আছে। নতুন কোনো রিপোর্ট বা প্রেসক্রিপশন এলে ছবি পাঠাবেন। | All right. When a new report or prescription comes, send a photo. |
  | `wa.fallback` | এটা এখনো বুঝতে পারছি না — রিপোর্টের ছবি পাঠান, বা লিখুন "বিস্তারিত"। | I cannot understand this yet — send a photo of the report, or type "detail". |
  | `wa.voice` | ভয়েস মেসেজ এখনো শুনতে পারি না — লিখে পাঠাবেন, বা কাগজের ছবি দিন। | I cannot listen to voice messages yet — please type it, or send a photo of the paper. |
  | `wa.video` | ভিডিও এখনো দেখতে পারি না — কাগজের একটা ছবি তুলে পাঠান। | I cannot watch videos yet — please take a photo of the paper and send it. |
  | `wa.unsupported_file` | এই ধরনের ফাইল এখনো খুলতে পারি না। কাগজের ছবি, বা PDF পাঠান। | I cannot open this kind of file yet. Send a photo of the paper, or a PDF. |
  | `wa.received.file` | ফাইলটা পেয়েছি। পড়ে দেখছি — একটু সময় লাগবে। | Got the file. Reading it now — this takes a moment. |

  `wa.fallback` quotes "বিস্তারিত" (the spec text does not), so `CopyInstructionsHaveHandlersTest` sees the invitation.
- [ ] **Step 3:** `./gradlew :baymax:test --tests '*MessagesBundleTest' --tests '*CopyInstructionsHaveHandlersTest'` green.
- [ ] **Step 4–5:** stage both bundles; `feat(baymax): copy for answering every WhatsApp message (drafts)`.

### Task 6.2: `WaTextRouter` — whole-message rules in Bangla, English and Banglish (both directions first)

**Files:** create `wa/application/service/WaTextRouter.java`, `baymax/src/test/java/com/elioo/baymax/wa/application/service/WaTextRouterTest.java`.

- [ ] **Step 1: Write the failing test.** First create a skeleton, `WaTextRouter` with `enum Route { GREETING_HELLO,
  GREETING_SALAM, GREETING_NAMASKAR, DETAIL, THANKS, FALLBACK }` and `static Route route(String t) { return Route.FALLBACK; }`,
  so the red is an assertion and not a compile error.

```java
package com.elioo.baymax.wa.application.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.elioo.baymax.wa.application.service.WaTextRouter.Route.DETAIL;
import static com.elioo.baymax.wa.application.service.WaTextRouter.Route.FALLBACK;
import static com.elioo.baymax.wa.application.service.WaTextRouter.Route.GREETING_HELLO;
import static com.elioo.baymax.wa.application.service.WaTextRouter.Route.GREETING_NAMASKAR;
import static com.elioo.baymax.wa.application.service.WaTextRouter.Route.GREETING_SALAM;
import static com.elioo.baymax.wa.application.service.WaTextRouter.Route.THANKS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §7.1 step 2: rules match the WHOLE normalised message or nothing. Both directions are written before the
 * router. Every listed phrasing routes, and a longer message that merely contains one does not:
 * "বিস্তারিত — আম্মার খুব জ্বর" must reach the fallback, which carries the red-flag line, never the detail path,
 * which would answer only its first word.
 */
class WaTextRouterTest {

    @Test
    void greetingsInBanglaEnglishAndBanglishAreGreetings() {
        for (String hi : List.of("Hi", "hi!", "Hello", "hey", "hiii", "হাই", "হ্যালো", "kemon acho", "Kemon achen?",
                "কেমন আছেন", "good morning")) {
            assertThat(WaTextRouter.route(hi)).as(hi).isEqualTo(GREETING_HELLO);
        }
        for (String salam : List.of("সালাম", "আসসালামু আলাইকুম", "Assalamu Alaikum", "assalamualaikum", "Salam", "slm")) {
            assertThat(WaTextRouter.route(salam)).as(salam).isEqualTo(GREETING_SALAM);
        }
        assertThat(WaTextRouter.route("নমস্কার")).isEqualTo(GREETING_NAMASKAR);
    }

    @Test
    void everyDetailPhrasingIsADetailRequest() {
        for (String d : List.of("বিস্তারিত", "  বিস্তারিত।  ", "বিস্তারিত বলুন", "আরো বলুন", "আরও বলো", "বুঝিয়ে বলুন",
                "detail", "Details", "DETAILS.", "explain", "tell me more", "more", "bistarito", "Bistarito bolen",
                "aro bolo", "bujhiye bolun")) {
            assertThat(WaTextRouter.route(d)).as(d).isEqualTo(DETAIL);
        }
    }

    @Test
    void thanksAndAffirmativeEmojiAreThanks() {
        for (String t : List.of("ধন্যবাদ", "ঠিক আছে", "ok", "OK!", "okk", "thanks", "Thank you", "dhonnobad",
                "thik ache", "ok thanks", "👍", "👍🏽", "🙏", "❤️", "👍 👍")) {
            assertThat(WaTextRouter.route(t)).as(t).isEqualTo(THANKS);
        }
    }

    /** The dangerous direction. A rule word inside a longer message, an unhappy emoji, and noise all fall through. */
    @Test
    void aLongerMessageThatOnlyContainsARuleWordFallsThrough() {
        for (String s : List.of("বিস্তারিত — আম্মার খুব জ্বর", "hi, ammu oggan hoye gese", "ধন্যবাদ, কিন্তু বুকে ব্যথা করছে",
                "details please, ma breathless", "ok but she fainted", "আজ বৃষ্টি হবে?", "😢", "?", "", "   ")) {
            assertThat(WaTextRouter.route(s)).as(s).isEqualTo(FALLBACK);
        }
        assertThat(WaTextRouter.route(null)).isEqualTo(FALLBACK);
    }

    /** য় has two encodings (U+09DF, and য + nukta U+09AF U+09BC); a phone keyboard may send either. */
    @Test
    void bothEncodingsOfABanglaLetterMatchTheSameRule() {
        String precomposed = "বুঝিয়ে বলুন";
        String decomposed = "বুঝিয়ে বলুন";
        assertThat(precomposed).as("the test is about two different code-point sequences").isNotEqualTo(decomposed);
        assertThat(WaTextRouter.route(precomposed)).isEqualTo(DETAIL);
        assertThat(WaTextRouter.route(decomposed)).isEqualTo(DETAIL);
    }

    /**
     * After F4 (PR 5) a repeat detail ask gets a pointer, not the body. "I did not understand" deserves a real answer
     * (Phase 6), not a pointer, so in Phase 0 it gets the honest fallback with the red-flag line.
     */
    @Test
    void notUnderstoodIsNotADetailRequestInPhaseZero() {
        assertThat(WaTextRouter.route("বুঝলাম না")).isEqualTo(FALLBACK);
        assertThat(WaTextRouter.route("bujhlam na")).isEqualTo(FALLBACK);
    }
}
```

- [ ] **Step 2: Run** `./gradlew :baymax:test --tests '*WaTextRouterTest'`. Four tests fail, each like
  `[Hi] expected: GREETING_HELLO but was: FALLBACK`. `aLongerMessageThatOnlyContainsARuleWordFallsThrough` and
  `notUnderstoodIsNotADetailRequestInPhaseZero` are **green on the skeleton**: they are the dangerous direction, written
  now so they must stay green through Step 3.
- [ ] **Step 3: Implement.**

```java
package com.elioo.baymax.wa.application.service;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Whole-message rules for inbound WhatsApp text (spec §7.1 step 2). Zero model calls. Logs never see the text.
 * A rule matches the entire normalised message or nothing, so a longer message that happens to start with a rule
 * word falls through to the fallback, which carries the red-flag line.
 */
public final class WaTextRouter {

    public enum Route { GREETING_HELLO, GREETING_SALAM, GREETING_NAMASKAR, DETAIL, THANKS, FALLBACK }

    private static final Set<String> SALAM = words("সালাম", "আসসালামু আলাইকুম", "আস্সালামু আলাইকুম", "আসসালামুয়ালাইকুম",
            "salam", "slm", "assalamu alaikum", "assalamualaikum", "asalamualaikum", "assalamu alaykum", "as salamu alaikum");
    private static final Set<String> NAMASKAR = words("নমস্কার", "namaskar", "nomoskar", "nomoshkar");
    private static final Set<String> HELLO = words("hi", "hii", "hello", "helo", "hey", "hlw", "hlo", "হাই", "হ্যালো",
            "হেলো", "kemon acho", "kemon achen", "kemon aso", "kemon asen", "kmn acho", "কেমন আছো", "কেমন আছেন", "কেমন আছ",
            "good morning", "good evening", "শুভ সকাল");
    private static final Set<String> DETAIL = words("বিস্তারিত", "বিস্তারিত বলুন", "বিস্তারিত বলো", "বিস্তারিত বলেন",
            "বিস্তারিত জানতে চাই", "আরো বলুন", "আরও বলুন", "আরো বলো", "আরও বলো", "আরো বলেন", "আরও বলেন", "আরো জানতে চাই",
            "আরও জানতে চাই", "বুঝিয়ে বলুন", "বুঝিয়ে বলো", "বুঝিয়ে বলেন", "ব্যাখ্যা", "ব্যাখ্যা করুন",
            "detail", "details", "more", "more detail", "more details", "tell me more", "explain", "explain please",
            "please explain", "bistarito", "bistarito bolun", "bistarito bolo", "bistarito bolen", "bistaritto",
            "aro bolun", "aro bolo", "aro bolen", "bujhiye bolun", "bujhiye bolo", "bujhiye bolen");
    private static final Set<String> THANKS = words("ধন্যবাদ", "অনেক ধন্যবাদ", "থ্যাংক ইউ", "থ্যাঙ্ক ইউ", "থ্যাংকস",
            "ঠিক আছে", "ঠিক আছে ধন্যবাদ", "আচ্ছা", "ওকে", "ok", "okk", "okay", "oky", "k", "thanks", "thank you",
            "thanks a lot", "thx", "tnx", "ty", "ok thanks", "ok thank you", "got it", "dhonnobad", "donnobad",
            "thik ache", "thik ase", "accha", "acha");
    /** Affirmative only: 😢 or 😡 is not "thanks" and must reach the fallback with its red-flag line. */
    private static final Set<Integer> AFFIRMATIVE = Set.of(0x1F44D, 0x1F64F, 0x2764, 0x1F44C, 0x2705, 0x1F60A,
            0x1F642, 0x263A, 0x1F970, 0x1F60D, 0x1F490, 0x1F91D, 0x1F44F);

    private WaTextRouter() {
    }

    public static Route route(String text) {
        if (affirmativeEmojiOnly(text)) {
            return Route.THANKS;
        }
        String n = normalise(text);
        if (n.isEmpty()) {
            return Route.FALLBACK;
        }
        if (DETAIL.contains(n)) {
            return Route.DETAIL;
        }
        if (SALAM.contains(n)) {
            return Route.GREETING_SALAM;
        }
        if (NAMASKAR.contains(n)) {
            return Route.GREETING_NAMASKAR;
        }
        if (HELLO.contains(n)) {
            return Route.GREETING_HELLO;
        }
        return THANKS.contains(n) ? Route.THANKS : Route.FALLBACK;
    }

    /**
     * NFKC (folds য় U+09DF onto য + nukta), drop ZWJ/ZWNJ, lower-case Latin, punctuation to spaces (danda included,
     * it is \p{IsPunctuation}), collapse whitespace, and squeeze a Latin letter repeated 3+ times to two ("hiii" → "hii").
     */
    static String normalise(String text) {
        if (text == null) {
            return "";
        }
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replace("‌", "").replace("‍", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{IsPunctuation}\\s]+", " ")
                .replaceAll("([a-z])\\1{2,}", "$1$1")
                .trim();
    }

    static boolean affirmativeEmojiOnly(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        boolean any = false;
        for (int cp : text.codePoints().toArray()) {
            if (Character.isWhitespace(cp) || cp == 0xFE0F || cp == 0x200D || (cp >= 0x1F3FB && cp <= 0x1F3FF)) {
                continue;   // variation selector, joiner, skin tone
            }
            if (!AFFIRMATIVE.contains(cp)) {
                return false;
            }
            any = true;
        }
        return any;
    }

    /** Table entries go through the same normalisation as input, so the two can never disagree on encoding. */
    private static Set<String> words(String... entries) {
        return Arrays.stream(entries).map(WaTextRouter::normalise).collect(Collectors.toUnmodifiableSet());
    }
}
```

- [ ] **Step 4:** all green, including both fall-through tests.
- [ ] **Step 5:** stage both files; `feat(baymax): whole-message WhatsApp text router (greeting, detail synonyms, thanks, fallback)`.

### Task 6.3: Every Meta message type reaches the intake; one `WaInbound`

**Files:** create `wa/domain/WaInbound.java`, `baymax/src/test/java/com/elioo/baymax/wa/adapter/in/WaWebhookDispatchTest.java`;
modify `WaWebhookHandler.java` (`dispatch`, plus `parse`), `WaIntakeService.java` (an `onInbound` stub here, filled in
Task 6.5).

- [ ] **Step 1: Write the failing test.** First add `WaInbound` exactly as in Step 3 and a stub
  `public Mono<Outcome> onInbound(WaInbound in) { return Mono.just(Outcome.ACCEPTED); }`; leave `dispatch` unchanged, so
  the red is "dropped", not a compile error.

```java
package com.elioo.baymax.wa.adapter.in;

import com.elioo.baymax.common.error.ErrorResponseFilter;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.wa.application.service.WaIntakeService;
import com.elioo.baymax.wa.domain.WaInbound;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Spec §0: voice, stickers and button taps were dropped at WaWebhookHandler.java:112-113 with a log line, so the
 * family heard nothing. Every message type Meta sends now reaches the intake, which decides the reply.
 * One fixture per WaInbound.Type, so a type added to the enum without a fixture fails here.
 */
class WaWebhookDispatchTest {

    private static final String APP_SECRET = "app-secret-under-test";
    private static final String FROM = "8801700000000";
    private static final String WAMID = com.elioo.baymax.wa.WaFixtures.wamid(FROM);
    private final WaIntakeService intake = mock(WaIntakeService.class);

    /** Package-private: EveryInboundTypeIsAnsweredTest drives the real webhook with the same fixtures. */
    static final Map<WaInbound.Type, String> ONE_OF_EACH = Map.ofEntries(
            entry(WaInbound.Type.TEXT, "\"type\":\"text\",\"text\":{\"body\":\"Hi\"}"),
            entry(WaInbound.Type.IMAGE, "\"type\":\"image\",\"image\":{\"id\":\"M-IMG\",\"mime_type\":\"image/jpeg\"}"),
            entry(WaInbound.Type.DOCUMENT, "\"type\":\"document\",\"document\":{\"id\":\"M-DOC\",\"mime_type\":"
                    + "\"application/vnd.openxmlformats-officedocument.wordprocessingml.document\",\"filename\":\"r.docx\"}"),
            entry(WaInbound.Type.AUDIO, "\"type\":\"audio\",\"audio\":{\"id\":\"M-AUD\",\"mime_type\":\"audio/ogg; codecs=opus\",\"voice\":true}"),
            entry(WaInbound.Type.VIDEO, "\"type\":\"video\",\"video\":{\"id\":\"M-VID\",\"mime_type\":\"video/mp4\"}"),
            entry(WaInbound.Type.STICKER, "\"type\":\"sticker\",\"sticker\":{\"id\":\"M-STK\",\"mime_type\":\"image/webp\",\"animated\":false}"),
            entry(WaInbound.Type.REACTION, "\"type\":\"reaction\",\"reaction\":{\"message_id\":\"wamid.OUT\",\"emoji\":\"👍\"}"),
            entry(WaInbound.Type.INTERACTIVE, "\"type\":\"interactive\",\"interactive\":{\"type\":\"button_reply\","
                    + "\"button_reply\":{\"id\":\"detail\",\"title\":\"বিস্তারিত\"}}"),
            entry(WaInbound.Type.BUTTON, "\"type\":\"button\",\"button\":{\"payload\":\"DETAIL\",\"text\":\"বিস্তারিত\"}"),
            entry(WaInbound.Type.LOCATION, "\"type\":\"location\",\"location\":{\"latitude\":23.8,\"longitude\":90.4}"),
            entry(WaInbound.Type.CONTACTS, "\"type\":\"contacts\",\"contacts\":[{\"name\":{\"formatted_name\":\"X\"}}]"),
            entry(WaInbound.Type.SYSTEM, "\"type\":\"system\",\"system\":{\"type\":\"customer_changed_number\"}"),
            entry(WaInbound.Type.OTHER, "\"type\":\"unsupported\",\"errors\":[{\"code\":131051}]"));

    private WaInbound deliver(String fields) throws Exception {
        reset(intake);
        when(intake.onInbound(any())).thenReturn(Mono.just(WaIntakeService.Outcome.ACCEPTED));
        BaymaxProperties props = new BaymaxProperties();
        props.getWa().setEnabled(true);
        props.getWa().setVerifyToken("v");
        props.getWa().setAppSecret(APP_SECRET);
        WebTestClient client = WebTestClient.bindToRouterFunction(new WaWebhookRouter()
                .baymaxWaRoutes(new WaWebhookHandler(props, new ObjectMapper(), intake), new ErrorResponseFilter())).build();
        byte[] body = ("{\"object\":\"whatsapp_business_account\",\"entry\":[{\"changes\":[{\"value\":{\"messages\":[{"
                + "\"id\":\"" + WAMID + "\",\"from\":\"" + FROM + "\"," + fields + "}]}}]}]}").getBytes(StandardCharsets.UTF_8);
        client.post().uri(WaWebhookRouter.WEBHOOK_PATH).header("X-Hub-Signature-256", WaSignature.sign(body, APP_SECRET))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isOk();
        ArgumentCaptor<WaInbound> in = ArgumentCaptor.forClass(WaInbound.class);
        // dispatch is detached on boundedElastic; wait for it rather than racing it
        verify(intake, timeout(2000)).onInbound(in.capture());
        return in.getValue();
    }

    @Test
    void everyMessageTypeMetaSendsReachesTheIntakeInsteadOfBeingDropped() throws Exception {
        assertThat(ONE_OF_EACH.keySet()).as("one fixture per Type").containsExactlyInAnyOrder(WaInbound.Type.values());
        for (var e : ONE_OF_EACH.entrySet()) {
            WaInbound in = deliver(e.getValue());
            assertThat(in.type()).as(e.getKey().name()).isEqualTo(e.getKey());
            assertThat(in.from()).isEqualTo(FROM);
            assertThat(in.providerMessageId()).as("F9a needs the provider id").isEqualTo(WAMID);
        }
    }

    @Test
    void theWordsOfAButtonTapAndTheMimeOfADocumentAreCarried() throws Exception {
        assertThat(deliver(ONE_OF_EACH.get(WaInbound.Type.INTERACTIVE)).text()).isEqualTo("বিস্তারিত");
        assertThat(deliver(ONE_OF_EACH.get(WaInbound.Type.BUTTON)).text()).isEqualTo("বিস্তারিত");
        assertThat(deliver(ONE_OF_EACH.get(WaInbound.Type.REACTION)).text()).isEqualTo("👍");
        WaInbound docx = deliver(ONE_OF_EACH.get(WaInbound.Type.DOCUMENT));
        assertThat(docx.mediaId()).isEqualTo("M-DOC");
        assertThat(docx.mimeType()).startsWith("application/vnd.openxmlformats");
    }

    @Test
    void anyTypeMetaAddsLaterIsOtherAndStillReachesTheIntake() {
        assertThat(WaInbound.Type.of("order")).isEqualTo(WaInbound.Type.OTHER);
        assertThat(WaInbound.Type.of("something_new_from_meta")).isEqualTo(WaInbound.Type.OTHER);
        assertThat(WaInbound.Type.of("unknown")).isEqualTo(WaInbound.Type.OTHER);
    }
}
```

- [ ] **Step 2: Run** `./gradlew :baymax:test --tests '*WaWebhookDispatchTest'` →
  `Wanted but not invoked: waIntakeService.onInbound(<Capturing argument>)`: today's dispatch routes text to `onText`,
  image to `onImage`, and logs `wa inbound ignored` for the rest. That is the defect.
- [ ] **Step 3: Implement.** `WaInbound`:

```java
package com.elioo.baymax.wa.domain;

import java.util.Locale;

/**
 * One inbound WhatsApp message, mapped from Meta's JSON at the adapter boundary. {@code text} is the typed body,
 * a button's title, or a reaction's emoji. It is patient text and is never logged.
 */
public record WaInbound(String providerMessageId, String from, Type type, String text, String mediaId, String mimeType,
                        java.time.Instant timestamp) {

    /** Callers and tests with no provider timestamp. */
    public WaInbound(String providerMessageId, String from, Type type, String text, String mediaId, String mimeType) {
        this(providerMessageId, from, type, text, mediaId, mimeType, null);
    }

    public enum Type {
        TEXT, IMAGE, DOCUMENT, AUDIO, VIDEO, STICKER, REACTION, INTERACTIVE, BUTTON, LOCATION, CONTACTS, SYSTEM, OTHER;

        /** Anything Meta adds later, plus "unsupported", "unknown" and "order", is OTHER, which gets the fallback reply. */
        public static Type of(String meta) {
            return switch (meta == null ? "" : meta.toLowerCase(Locale.ROOT)) {
                case "text" -> TEXT;
                case "image" -> IMAGE;
                case "document" -> DOCUMENT;
                case "audio" -> AUDIO;
                case "video" -> VIDEO;
                case "sticker" -> STICKER;
                case "reaction" -> REACTION;
                case "interactive" -> INTERACTIVE;
                case "button" -> BUTTON;
                case "location" -> LOCATION;
                case "contacts" -> CONTACTS;
                case "system" -> SYSTEM;
                default -> OTHER;
            };
        }
    }

    /**
     * Exactly two inbound events carry nothing the family sent, so they get no reply: Meta's own system notices
     * (e.g. a changed number) and an un-reaction (a reaction with its emoji removed). Everything else is answered once.
     */
    public boolean expectsReply() {
        return switch (type) {
            case SYSTEM -> false;
            case REACTION -> text != null && !text.isBlank();
            default -> true;
        };
    }
}
```

  In `WaWebhookHandler.dispatch`, the inner loop body becomes `WaInbound in = parse(message); subscribe(intake.onInbound(in), in.from());`. Add:

```java
/** Meta's message JSON → WaInbound. Every level is optional, so every read has a default. */
static WaInbound parse(JsonNode m) {
    String meta = m.path("type").asText("");
    WaInbound.Type type = WaInbound.Type.of(meta);
    String text = switch (type) {
        case TEXT -> m.path("text").path("body").asText("");
        case REACTION -> m.path("reaction").path("emoji").asText("");
        case INTERACTIVE -> {
            JsonNode i = m.path("interactive");
            JsonNode reply = i.has("button_reply") ? i.path("button_reply") : i.path("list_reply");
            yield reply.path("title").asText(reply.path("id").asText(""));
        }
        case BUTTON -> m.path("button").path("text").asText(m.path("button").path("payload").asText(""));
        default -> "";
    };
    JsonNode media = m.path(meta);   // image | document | audio | video | sticker carry {id, mime_type}
    long ts = m.path("timestamp").asLong(0);   // Meta sends epoch seconds as a string; PR 8 stores it
    return new WaInbound(m.path("id").asText(""), m.path("from").asText(""), type, text,
            media.path("id").asText(""), media.path("mime_type").asText(""), ts == 0 ? null : java.time.Instant.ofEpochSecond(ts));
}
```

  The javadoc of `dispatch` gains: "No type is dropped here; the intake answers every one (Phase 0)."
- [ ] **Step 4:** `./gradlew :baymax:test --tests 'com.elioo.baymax.wa.*'` green. The tests that mock `WaIntakeService` and
  post a callback (`WaWebhookRouterTest`, PR 1's `WaLogHygieneTest`) stub `onInbound` to `Mono.just(Outcome.ACCEPTED)`:
  an unstubbed Mono-returning mock answers `null`, which would fail the request for a reason that is not the test's.
- [ ] **Step 5:** `git status --ignored --short -- baymax/src | grep '^!!'` prints nothing (new `wa/domain`); stage the
  record, the handler, the service and the test; `feat(baymax): every WhatsApp message type reaches the intake; nothing is dropped at the webhook`.

### Task 6.4: Text gets an answer — greeting, thanks, fallback with the red-flag line (invert the silence test)

**Files:** modify `WaIntakeService.java` (`onText`; `isDetailRequest` is deleted), `WaIntakeServiceTest.java` (`:203-208`
inverted, `:218-225` moved to `WaTextRouterTest`, and an `@AfterEach` cap check), `CopyInstructionsHaveHandlersTest.java` (`:59`).

- [ ] **Step 1: Write the failing tests** in `WaIntakeServiceTest` (constructor unchanged). Helpers:
  `private final Copy copy = new Copy(); private String bn(String k) { return copy.bn(k, java.util.Map.of()); }` and

```java
private List<String> sentBodies() {
    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
    verify(graph, org.mockito.Mockito.atLeast(0)).sendText(eq(E164), body.capture());
    return body.getAllValues();
}
```

  The cap on what is actually sent, checked after every test in the class (CLAUDE.md):

```java
/** CLAUDE.md: a limit is asserted on the thing actually sent, after every insertion. Every reply in every test is checked. */
@org.junit.jupiter.api.AfterEach
void everyReplyActuallySentFitsTheCap() {
    for (var call : org.mockito.Mockito.mockingDetails(graph).getInvocations()) {
        if (call.getMethod().getName().equals("sendText")) {
            String body = call.getArgument(1);
            assertThat(body.length()).as("sent: " + body).isLessThanOrEqualTo(props.getOutbound().getMaxChars());
        }
    }
}
```

  Then:

```java
/**
 * Inverted from anOrdinaryTextIsAcknowledgedWithoutAReply, which asserted the silence Phase 0 removes. A test
 * whose name encodes the defect is evidence of it (CLAUDE.md). "ধন্যবাদ" is a thanks and is answered once.
 */
@Test
void aThankYouIsAnsweredOnce() {
    familyWith(patient("মা"));
    when(documents.latestDoneDocument(FAMILY)).thenReturn(Mono.empty());
    service.onText(FROM, "ধন্যবাদ").block();
    assertThat(sentBodies()).as("a family's thank-you got silence — the defect").hasSize(1);
    assertThat(sentBodies().get(0)).startsWith(bn("wa.thanks")).endsWith(bn("safety.red_flag_line"));
}

@Test
void hiIsAnsweredWithAGreetingThatSaysWhatToDoNext() {
    familyWith(patient("মা"));
    when(documents.latestDoneDocument(FAMILY)).thenReturn(Mono.empty());
    service.onText(FROM, "Hi").block();
    assertThat(sentBodies()).as("\"Hi\" got silence (spec §0) — the defect").hasSize(1);
    assertThat(sentBodies().get(0)).startsWith(bn("wa.greeting.hello"))
            .contains(bn("wa.greeting.intro")).contains(bn("wa.greeting.none"));
}

/** Mirror the greeting; never assume religion (spec §7.4). */
@Test
void salamIsAnsweredWithSalamAndKemonAchoIsNot() {
    familyWith(patient("মা"));
    when(documents.latestDoneDocument(FAMILY)).thenReturn(Mono.empty());
    service.onText(FROM, "আসসালামু আলাইকুম").block();
    service.onText(FROM, "kemon acho").block();
    assertThat(sentBodies()).as("both greetings got silence — the defect").hasSize(2);
    assertThat(sentBodies().get(0)).startsWith(bn("wa.greeting.salam"));
    assertThat(sentBodies().get(1)).startsWith(bn("wa.greeting.hello")).doesNotContain("আসসালাম");
}

@Test
void anythingElseGetsTheHonestFallbackWithTheRedFlagLine() {
    familyWith(patient("মা"));
    service.onText(FROM, "আম্মা কাল থেকে খুব দুর্বল").block();
    assertThat(sentBodies()).as("free text got silence — the defect").hasSize(1);
    assertThat(sentBodies()).containsExactly(bn("wa.fallback") + "\n" + bn("safety.red_flag_line"));
}

/** Whole-message rule, dangerous direction: the detail word inside a symptom message must not trigger detail. */
@Test
void aRuleWordInsideALongerMessageGetsTheFallbackNotTheRule() {
    familyWith(patient("মা"));
    service.onText(FROM, "বিস্তারিত — আম্মার খুব জ্বর").block();
    assertThat(sentBodies()).as("silence — the defect").hasSize(1);
    verify(explainer, never()).detail(any());
    assertThat(sentBodies().get(0)).contains(bn("safety.red_flag_line"));
}

/** The synonyms reach the same detail path as the literal word. */
@Test
void detailSynonymsAreAnsweredLikeTheInvitedWord() {
    familyWith(patient("মা"));
    when(documents.latestDoneDocument(FAMILY)).thenReturn(Mono.empty());
    for (String s : List.of("details", "আরো বলুন", "bistarito bolen", "explain")) {
        service.onText(FROM, s).block();
    }
    assertThat(sentBodies()).as("synonyms got silence — the defect").hasSize(4)
            .allMatch(b -> b.equals(bn("wa.no_document")));
}
```

  Delete `anOrdinaryTextIsAcknowledgedWithoutAReply` (`:203-208`) and
  `theInvitedWordIsRecognisedWithPunctuationOrInEnglish` (`:218-225`); `WaTextRouterTest` covers the latter. In
  `CopyInstructionsHaveHandlersTest`, change `:59` and add the English bundle:

```java
List<String> unhandled = invited.stream()
        .filter(word -> WaTextRouter.route(word) == WaTextRouter.Route.FALLBACK).toList();
```
```java
/** The English mirror invites typed replies too ('reply "detail"', 'type "detail"'); each must route. */
@Test
void everyWordTheEnglishCopyAsksAFamilyToTypeIsRecognisedToo() throws Exception {
    Properties en = load("baymax/messages_en.properties");
    List<String> invited = new ArrayList<>();
    for (String key : en.stringPropertyNames()) {
        String value = en.getProperty(key);
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        if (!lower.contains("reply") && !lower.contains("type")) {
            continue;
        }
        Matcher m = INVITED_WORD.matcher(value);
        while (m.find()) {
            invited.add(m.group(1).trim());
        }
    }
    assertThat(invited).as("vacuous if the English copy invites nothing").isNotEmpty();
    assertThat(invited.stream().filter(w -> WaTextRouter.route(w) == WaTextRouter.Route.FALLBACK).toList()).isEmpty();
}
```

- [ ] **Step 2: Run (the replay against the pre-fix `onText`).**
  `./gradlew :baymax:test --tests '*WaIntakeServiceTest' --tests '*CopyInstructionsHaveHandlersTest'`:
  - `[a family's thank-you got silence — the defect] Expected size: 1 but was: 0 in: []`
  - `["Hi" got silence (spec §0) — the defect] Expected size: 1 but was: 0 in: []`
  - `[both greetings got silence — the defect] Expected size: 2 but was: 0`
  - `[free text got silence — the defect] Expected size: 1 but was: 0`
  - `[silence — the defect] Expected size: 1 but was: 0`
  - `[synonyms got silence — the defect] Expected size: 4 but was: 0` (`isDetailRequest("details")` is false today).

  The router-based `CopyInstructionsHaveHandlersTest` is green: every invited word is "বিস্তারিত" or "detail". The
  `@AfterEach` cap check is green when added (a forward guard; say so in the PR).
- [ ] **Step 3: Implement.** Replace `onText` and delete `isDetailRequest`:

```java
/**
 * Text, a button tap's title, or the words of a quick reply. Every message gets exactly one reply, and none is
 * silent (Phase 0). The route is logged; the text is not.
 */
public Mono<Outcome> onText(String from, String text) {
    String number = e164(from);
    WaTextRouter.Route route = WaTextRouter.route(text);
    log.info("[baymax] wa text route={} chars={}", route, text == null ? 0 : text.length());
    return withFamily(number, family -> switch (route) {
        case DETAIL -> answerLatestDetail(number, family);
        case GREETING_HELLO, GREETING_SALAM, GREETING_NAMASKAR -> greet(number, route);
        case THANKS -> replyWithRedFlag(number, "wa.thanks", "thanks");
        case FALLBACK -> replyWithRedFlag(number, "wa.fallback", "text not understood");
    }, "text");
}

/** PR 5's detail answer, unchanged: the latest DONE document's DETAIL row, answered by answerDetail (F4). */
private Mono<Outcome> answerLatestDetail(String number, FamilyAccount family) {
    return documents.latestDoneDocument(family.id())
            .flatMap(document -> explainer.detail(document.id()))
            .flatMap(message -> answerDetail(number, message))
            .switchIfEmpty(Mono.defer(() -> reply(number, "wa.no_document", Outcome.REPLIED,
                    "detail asked for but no readable document yet")));
}

private Mono<Outcome> greet(String number, WaTextRouter.Route route) {
    String opener = copy.bn(switch (route) {
        case GREETING_SALAM -> "wa.greeting.salam";
        case GREETING_NAMASKAR -> "wa.greeting.namaskar";
        default -> "wa.greeting.hello";
    }, Map.of());
    return send(number, opener + "\n" + copy.bn("wa.greeting.intro", Map.of()) + "\n" + copy.bn("wa.greeting.none", Map.of()))
            .thenReturn(Outcome.REPLIED);
}

private Mono<Outcome> withFamily(String number, java.util.function.Function<FamilyAccount, Mono<Outcome>> known, String what) {
    return records.findFamilyByWhatsapp(number)
            .flatMap(known)
            .switchIfEmpty(Mono.defer(() -> reply(number, "wa.unknown", Outcome.UNKNOWN_SENDER, what + " from unknown sender")));
}

private Mono<Outcome> replyWithRedFlag(String number, String key, String why) {
    log.info("[baymax] wa reply key={} reason={}", key, why);
    return send(number, withRedFlag(copy.bn(key, Map.of()))).thenReturn(Outcome.REPLIED);
}

/** Phase 0: every fallback, voice and sticker reply carries the approved red-flag line, once. */
private String withRedFlag(String body) {
    String line = copy.bn("safety.red_flag_line", Map.of());
    return body.contains(line) ? body : body + "\n" + line;
}
```

  Add `REPLIED` and `IGNORED` to `Outcome`. Update the class javadoc: text is answered too.
- [ ] **Step 4:** `./gradlew :baymax:test --tests 'com.elioo.baymax.wa.*'` green. PR 5's `DetailDeliveryGuardsTest` stays
  green: the detail route reaches the same `answerDetail`.
- [ ] **Step 5:** stage `WaIntakeService.java`, `WaIntakeServiceTest.java`, `CopyInstructionsHaveHandlersTest.java`;
  `fix(baymax): answer every WhatsApp text — greeting, detail synonyms, thanks, honest fallback with the red-flag line` (body:
  "Inverts anOrdinaryTextIsAcknowledgedWithoutAReply, which asserted the silence.").

### Task 6.5: Voice, video, sticker, reaction, location, contacts, DOCX, PDF — and a derived "never silent" test through the webhook

**Files:** modify `WaIntakeService.java` (`onInbound` replaces the stub; `onImage` gains an ack-key overload),
`WaIntakeServiceTest.java`, `WaWebhookDispatchTest.java` (`ONE_OF_EACH` becomes package-private); create
`baymax/src/test/java/com/elioo/baymax/wa/adapter/in/EveryInboundTypeIsAnsweredTest.java`.

- [ ] **Step 1: Write the failing tests.** The derived test drives **the webhook the app runs**, with a signed callback per
  `WaInbound.Type` and a recording `WaMessagingPort`, so it tests whatever dispatch actually ships (a later PR that
  reroutes dispatch cannot leave it green over a silent type). The cap is asserted inside the loop, on each captured body:

```java
package com.elioo.baymax.wa.adapter.in;

import com.elioo.baymax.common.error.ErrorResponseFilter;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.application.port.in.DocumentIntakeUseCase;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.outbound.application.port.in.ExplainDocumentUseCase;
import com.elioo.baymax.outbound.application.service.Copy;
import com.elioo.baymax.wa.WaFixtures;
import com.elioo.baymax.wa.application.port.out.WaMessagingPort;
import com.elioo.baymax.wa.application.service.WaIntakeService;
import com.elioo.baymax.wa.domain.WaInbound;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Spec §7.8: every Meta inbound type has a non-silent handler, through the real webhook and the real intake.
 * Derived from WaInbound.Type via WaWebhookDispatchTest.ONE_OF_EACH, which itself must cover every Type.
 */
class EveryInboundTypeIsAnsweredTest {

    private static final String APP_SECRET = "app-secret-under-test";
    private static final String FROM = "8801700000000";
    private static final String E164 = "+8801700000000";
    private final HealthRecordPort records = mock(HealthRecordPort.class);
    private final DocumentRecordPort documents = mock(DocumentRecordPort.class);
    private final ExplainDocumentUseCase explainer = mock(ExplainDocumentUseCase.class);
    private final DocumentIntakeUseCase intake = mock(DocumentIntakeUseCase.class);
    private final List<String> sent = new CopyOnWriteArrayList<>();
    private final WaMessagingPort graph = new WaMessagingPort() {
        @Override
        public Mono<byte[]> downloadMedia(String mediaId) {
            return Mono.just(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0});
        }

        @Override
        public Mono<String> sendText(String to, String body) {
            sent.add(body);
            return Mono.just(WaFixtures.wamid(FROM, 1000 + sent.size()));
        }
    };
    private final BaymaxProperties props = new BaymaxProperties();
    private WebTestClient client;

    @BeforeEach
    void wire() {
        props.getWa().setEnabled(true);
        props.getWa().setVerifyToken("v");
        props.getWa().setAppSecret(APP_SECRET);
        props.getWa().setAllowlist(List.of(E164));
        FamilyAccount family = new FamilyAccount(UUID.randomUUID(), E164, "Rahim", FamilyAccount.Plan.FREE, Instant.EPOCH, Instant.EPOCH);
        when(records.findFamilyByWhatsapp(E164)).thenReturn(Mono.just(family));
        when(records.patientsOf(family.id())).thenReturn(Flux.just(new PatientProfile(UUID.randomUUID(), family.id(), "মা", 70,
                PatientProfile.Sex.FEMALE, List.of(), Instant.EPOCH, Instant.EPOCH)));
        when(documents.latestDoneDocument(any())).thenReturn(Mono.empty());
        when(intake.accept(any(), any())).thenReturn(Mono.just(mock(Document.class)));
        // the constructor as this PR leaves it (Global Constraints table); later PRs update this line with the others
        WaIntakeService service = new WaIntakeService(records, documents, explainer, intake, graph, new Copy(), props);
        client = WebTestClient.bindToRouterFunction(new WaWebhookRouter()
                .baymaxWaRoutes(new WaWebhookHandler(props, new ObjectMapper(), service), new ErrorResponseFilter())).build();
    }

    private byte[] callback(String wamid, String fields) {
        return ("{\"object\":\"whatsapp_business_account\",\"entry\":[{\"changes\":[{\"value\":{\"messages\":[{"
                + "\"id\":\"" + wamid + "\",\"from\":\"" + FROM + "\"," + fields + "}]}}]}]}").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void everyMessageTypeAFamilyCanSendIsAnsweredExactlyOnceAndEveryReplyFitsTheCap() throws Exception {
        assertThat(WaWebhookDispatchTest.ONE_OF_EACH.keySet()).as("one fixture per Type")
                .containsExactlyInAnyOrder(WaInbound.Type.values());
        int n = 0;
        for (var e : WaWebhookDispatchTest.ONE_OF_EACH.entrySet()) {
            sent.clear();
            byte[] body = callback(WaFixtures.wamid(FROM, ++n), e.getValue());
            client.post().uri(WaWebhookRouter.WEBHOOK_PATH).header("X-Hub-Signature-256", WaSignature.sign(body, APP_SECRET))
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isOk();
            int expected = e.getKey() == WaInbound.Type.SYSTEM ? 0 : 1;   // Meta's own notice is the only silence
            if (expected == 1) {
                await().atMost(Duration.ofSeconds(5)).until(() -> !sent.isEmpty());
            }
            await().pollDelay(Duration.ofMillis(400)).atMost(Duration.ofSeconds(2)).until(() -> true);   // room for a second
            List<String> replies = List.copyOf(sent);
            assertThat(replies).as(e.getKey() + ": every message a family sends gets exactly one reply").hasSize(expected);
            for (String reply : replies) {
                assertThat(reply.length()).as(e.getKey() + " reply: " + reply)
                        .isLessThanOrEqualTo(props.getOutbound().getMaxChars());
            }
        }
    }
}
```

  Unit tests in `WaIntakeServiceTest`:

```java
@Test
void anUnReactionIsTheOnlyReactionNotAnswered() {
    familyWith(patient("মা"));
    service.onInbound(new WaInbound("w0", FROM, WaInbound.Type.REACTION, "", "", "")).block();
    verify(graph, never()).sendText(anyString(), anyString());
    service.onInbound(new WaInbound("w1", FROM, WaInbound.Type.REACTION, "👍", "", "")).block();
    assertThat(sentBodies()).singleElement().asString().contains(bn("safety.red_flag_line"));
}

/** D13: fixed reply plus the red-flag line; nothing here can listen, so nothing is downloaded. */
@Test
void aVoiceNoteGetsTheFixedReplyAndTheRedFlagLine() {
    familyWith(patient("মা"));
    service.onInbound(new WaInbound("w", FROM, WaInbound.Type.AUDIO, "", "MEDIA-V", "audio/ogg; codecs=opus")).block();
    assertThat(sentBodies()).containsExactly(bn("wa.voice") + "\n" + bn("safety.red_flag_line"));
    verify(graph, never()).downloadMedia(anyString());
}

@Test
void aWordFileIsRefusedWithAReasonBeforeAnythingIsDownloadedOrAcknowledged() {
    familyWith(patient("মা"));
    service.onInbound(sample(WaInbound.Type.DOCUMENT)).block();
    assertThat(sentBodies()).containsExactly(bn("wa.unsupported_file"));
    verify(graph, never()).downloadMedia(anyString());
    verify(intake, never()).accept(any(), any());
}

@Test
void aPdfIsAcknowledgedAsAFileAndFiled() {
    PatientProfile ma = patient("মা");
    familyWith(ma);
    when(intake.accept(eq(ma.id()), any())).thenReturn(Mono.just(mock(Document.class)));
    service.onInbound(new WaInbound("w", FROM, WaInbound.Type.DOCUMENT, "", "MEDIA-P", "application/pdf")).block();
    assertThat(sentBodies()).containsExactly(bn("wa.received.file"));
    verify(intake).accept(eq(ma.id()), any());
}

@Test
void aVoiceNoteFromAnUnknownNumberGetsTheUnknownSenderReply() {
    when(records.findFamilyByWhatsapp(E164)).thenReturn(Mono.empty());
    service.onInbound(new WaInbound("w", FROM, WaInbound.Type.AUDIO, "", "M", "audio/ogg")).block();
    assertThat(sentBodies()).containsExactly(bn("wa.unknown"));
}

/** Logs carry route names and sizes, never the text or the number (CLAUDE.md; WaWebhookHandler.maskedNumber). */
@Test
void theIntakeLogNeverCarriesWhatTheFamilyWroteOrTheirNumber() {
    ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(WaIntakeService.class);
    var logged = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    logged.start();
    logger.addAppender(logged);
    try {
        familyWith(patient("মা"));
        service.onText(FROM, "আম্মার বুকে ব্যথা").block();
        String all = String.join("\n", logged.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList());
        assertThat(all).contains("route=FALLBACK").doesNotContain("বুকে").doesNotContain("1700000000");
    } finally {
        logger.detachAppender(logged);
    }
}
```

- [ ] **Step 2: Run.** `./gradlew :baymax:test --tests '*EveryInboundTypeIsAnsweredTest' --tests '*WaIntakeServiceTest'`.
  The derived test fails on the first type, e.g. `[AUDIO: every message a family sends gets exactly one reply] Expected size: 1 but was: 0`
  (the Task 6.3 stub answers nothing). The voice, DOCX, PDF and unknown-sender tests fail the same way, on
  `containsExactly` against `[]`. The log test is a forward guard (green after Task 6.4); say so in the PR.
- [ ] **Step 3: Implement.** Replace the stub:

```java
private static final java.util.Set<String> READABLE_FILES = java.util.Set.of("application/pdf", "image/jpeg", "image/png");

/** The one entry point from the webhook. The switch is exhaustive over Type, so a new Type will not compile without a reply. */
public Mono<Outcome> onInbound(WaInbound in) {
    if (!in.expectsReply()) {
        log.info("[baymax] wa inbound needs no reply type={}", in.type());
        return Mono.just(Outcome.IGNORED);
    }
    String number = e164(in.from());
    return switch (in.type()) {
        case IMAGE -> onImage(in.from(), in.mediaId(), "wa.received");
        case DOCUMENT -> readable(in.mimeType())
                ? onImage(in.from(), in.mediaId(), "wa.received.file")
                : withFamily(number, f -> reply(number, "wa.unsupported_file", Outcome.REPLIED, "document type not readable"), "document");
        case TEXT, INTERACTIVE, BUTTON -> onText(in.from(), in.text());
        case STICKER, REACTION -> withFamily(number, f -> thanks(number, f), "acknowledgement");
        case AUDIO -> withFamily(number, f -> replyWithRedFlag(number, "wa.voice", "voice note"), "voice note");
        case VIDEO -> withFamily(number, f -> replyWithRedFlag(number, "wa.video", "video"), "video");
        case LOCATION, CONTACTS, OTHER -> withFamily(number, f -> replyWithRedFlag(number, "wa.fallback", "type " + in.type()), "message");
        case SYSTEM -> Mono.just(Outcome.IGNORED);   // unreachable: expectsReply() is false for SYSTEM
    };
}

/** Blank or missing mime: let PageRenderer's magic bytes decide. A failure still replies (PR 7). */
static boolean readable(String mimeType) {
    if (mimeType == null || mimeType.isBlank()) {
        return true;
    }
    return READABLE_FILES.contains(mimeType.split(";")[0].trim().toLowerCase(java.util.Locale.ROOT));
}
```

  Extract a `private Mono<Outcome> thanks(String number, FamilyAccount family)` from the THANKS branch (PR 7 gives it the
  standing urgency). Rename today's `onImage(from, mediaId)` body to `Mono<Outcome> onImage(String from, String mediaId,
  String ackKey)`; keep `public Mono<Outcome> onImage(String from, String mediaId) { return onImage(from, mediaId, "wa.received"); }`;
  thread `ackKey` into `withPatients`.
- [ ] **Step 4:** `./gradlew :baymax:test --tests 'com.elioo.baymax.wa.*'` green.
- [ ] **Step 5:** stage `WaIntakeService.java`, `WaIntakeServiceTest.java`, `WaWebhookDispatchTest.java`,
  `EveryInboundTypeIsAnsweredTest.java`; `feat(baymax): answer voice notes, videos, stickers, reactions, button taps and unreadable files on WhatsApp`.
  The standard pre-merge.

**Try it on your phone (PR 6)** — each gets **exactly one** reply:

| He sends | He gets |
|---|---|
| "Hi" | "হ্যালো!" + the intro + "রিপোর্ট বা প্রেসক্রিপশনের ছবি পাঠান…" (the latest paper and any standing urgency come in PR 7) |
| "আসসালামু আলাইকুম" | "ওয়ালাইকুম আসসালাম।" + intro |
| "kemon acho" | "হ্যালো!" + intro (no সালাম) |
| "details" (after an explanation) | the detail **once** |
| "আরো বলুন" (right after) | "বিস্তারিত আগেই পাঠানো হয়েছে…" + link (the pointer, PR 5) |
| "ধন্যবাদ", 👍, a reaction on a Medioo message, a sticker | "ঠিক আছে…" + the red-flag line |
| a voice note | `wa.voice` + the red-flag line |
| a video | `wa.video` + the red-flag line |
| a location | the fallback + the red-flag line |
| "আজ বৃষ্টি হবে?" | the fallback + the red-flag line |
| `t6-letter.docx` | `wa.unsupported_file`, no ack |
| `t5-routine-lab.pdf` | "ফাইলটা পেয়েছি…", then the explanation |
| `t1-routine-lab.jpg` | "ছবিটা পেয়েছি…", then the explanation |

- [ ] Read-only log check on the box: `docker logs medscribe-ai --since 20m 2>&1 | grep 'wa text route=' | tail -5` shows
  route names and character counts, no text, and no number except the masked `****0000` form.

**Post-deploy:** the standard check.

---

## PR 7 — B1b: the standing urgency in greetings and thanks; the free tier before the ack; failures after the ack are told

Branch `feat/baymax-wa-greeting-urgency`, from merged PR 6. `WaIntakeService` gains `messages`, `clock`, `freeTier`
(appended last; update every `new WaIntakeService(` including `EveryInboundTypeIsAnsweredTest` and
`DetailDeliveryGuardsTest`).

### Task 7.1: Greeting and thanks restate a standing NOW / THIS_WEEK, in any gate state (W1, W12, D5)

**Files:** modify `WaIntakeService.java`, `messages_bn/en.properties`; test `WaIntakeServiceTest.java`.

The tempting source for the standing level, `latestDeliverable`, returns only RELEASED/APPROVED rows with a body. A NOW whose explanation **failed safety** (body null) or is **PENDING** under `gate=urgency` is not deliverable,
so the greeting would hide exactly the urgent documents. The level is read from the newest EXPLANATION and DETAIL rows
in **any** gate state (`latestOfKind`, PR 5); the urgency column is always set.

- [ ] **Step 1: Copy.** Add (bn | en):

  | key | bn | en |
  |---|---|---|
  | `wa.greeting.latest` | শেষ কাগজ: {document_type}, {date}। কাগজে যা লেখা আছে, ছবিসহ: {link} | Latest paper: {document_type}, {date}. What the paper says, with photos: {link} |
  | `wa.greeting.urgent` | {date}-এর {document_type} নিয়ে মনে করিয়ে দিই: | A reminder about the {document_type} of {date}: |
  | `wa.thanks.urgent` | ঠিক আছে। মনে করিয়ে দিই: | All right. A reminder: |

  `wa.greeting.latest` deliberately does not invite "বিস্তারিত" again: after PR 5 a repeat ask gets the pointer, so the
  greeting points at the paper instead.
- [ ] **Step 2: Write the failing tests, the dangerous direction first.** Wiring: `private final OutboundMessagePort
  messages = mock(OutboundMessagePort.class); private final Clock clock = Clock.fixed(Instant.parse("2026-10-17T06:00:00Z"), ZoneOffset.UTC);`,
  `when(messages.latestOfKind(any(), any())).thenReturn(Mono.empty());`, the constructor extended. Helpers:

```java
private Document doneDocument(LocalDate docDate) {
    return new Document(UUID.randomUUID(), UUID.randomUUID(), FAMILY, "lab_report", docDate, "Shapla Diagnostics", "{}", 0.93,
            Document.Status.DONE, null, "anthropic/claude-sonnet-5", null, 1, Instant.EPOCH, Instant.EPOCH);
}

private OutboundMessage explained(Document doc, Urgency urgency, Instant at) {
    return explained(doc, urgency, at, OutboundMessage.GateStatus.RELEASED);
}

private OutboundMessage explained(Document doc, Urgency urgency, Instant at, OutboundMessage.GateStatus gate) {
    return new OutboundMessage(UUID.randomUUID(), FAMILY, doc.patientId(), doc.id(), OutboundMessage.Kind.EXPLANATION,
            urgency, List.of(), gate == OutboundMessage.GateStatus.FAILED_SAFETY ? null : "…", gate, null, null, null, null, at);
}
```

```java
/** D5 at the greeting: a NOW whose wording failed the checklist has no deliverable row, and must still lead the greeting. */
@Test
void aNowWhoseExplanationFailedSafetyStillLeadsTheGreeting() {
    familyWith(patient("মা"));
    Document doc = doneDocument(LocalDate.parse("2026-10-16"));
    when(documents.latestDoneDocument(FAMILY)).thenReturn(Mono.just(doc));
    when(messages.latestOfKind(doc.id(), OutboundMessage.Kind.EXPLANATION)).thenReturn(Mono.just(
            explained(doc, Urgency.NOW, Instant.parse("2026-10-16T08:00:00Z"), OutboundMessage.GateStatus.FAILED_SAFETY)));

    service.onText(FROM, "Hi").block();

    assertThat(sentBodies()).singleElement().asString()
            .as("a standing NOW hidden because its wording failed safety is the defect")
            .contains(bn("urgency.now")).contains(bn("safety.red_flag_line"));
}

/** The same under gate=urgency: a PENDING NOW is not deliverable, and its urgency still shows at once (D5). */
@Test
void aPendingNowUnderTheUrgencyGateStillLeadsTheGreeting() {
    props.getOutbound().setGateMode("urgency");
    familyWith(patient("মা"));
    Document doc = doneDocument(LocalDate.parse("2026-10-16"));
    when(documents.latestDoneDocument(FAMILY)).thenReturn(Mono.just(doc));
    when(messages.latestOfKind(doc.id(), OutboundMessage.Kind.EXPLANATION)).thenReturn(Mono.just(
            explained(doc, Urgency.NOW, Instant.parse("2026-10-16T08:00:00Z"), OutboundMessage.GateStatus.PENDING)));

    service.onText(FROM, "Hi").block();

    assertThat(sentBodies()).singleElement().asString()
            .as("a PENDING NOW hidden from the greeting contradicts D5")
            .contains(bn("urgency.now")).contains(bn("safety.red_flag_line"));
}

/** A DETAIL that rose higher than its explanation (follow_up_past_due is date-driven) is the level that stands. */
@Test
void theHigherOfTheExplanationAndTheDetailStands() {
    familyWith(patient("মা"));
    Document doc = doneDocument(LocalDate.parse("2026-10-12"));
    when(documents.latestDoneDocument(FAMILY)).thenReturn(Mono.just(doc));
    when(messages.latestOfKind(doc.id(), OutboundMessage.Kind.EXPLANATION)).thenReturn(Mono.just(
            explained(doc, Urgency.ROUTINE, Instant.parse("2026-10-12T08:00:00Z"))));
    when(messages.latestOfKind(doc.id(), OutboundMessage.Kind.DETAIL)).thenReturn(Mono.just(new OutboundMessage(UUID.randomUUID(),
            FAMILY, doc.patientId(), doc.id(), OutboundMessage.Kind.DETAIL, Urgency.THIS_WEEK, List.of("follow_up_past_due"), "…",
            OutboundMessage.GateStatus.RELEASED, null, null, null, null, Instant.parse("2026-10-15T08:00:00Z"))));

    service.onText(FROM, "Hi").block();

    assertThat(sentBodies().get(0)).contains(bn("urgency.this_week"));
}
```

```java
/** W1: an unacknowledged THIS_WEEK in the last 30 days leads the greeting, in its fixed words. */
@Test
void aGreetingLeadsWithAStandingUrgencyVerbatim() {
    familyWith(patient("মা"));
    Document doc = doneDocument(LocalDate.parse("2026-10-10"));
    when(documents.latestDoneDocument(FAMILY)).thenReturn(Mono.just(doc));
    when(messages.latestOfKind(doc.id(), OutboundMessage.Kind.EXPLANATION)).thenReturn(Mono.just(explained(doc, Urgency.THIS_WEEK, Instant.parse("2026-10-10T08:00:00Z"))));

    service.onText(FROM, "Hi").block();

    assertThat(sentBodies()).singleElement().asString()
            .as("a greeting that hides a standing THIS_WEEK is the defect").contains(bn("urgency.this_week"))
            .contains(BanglaDate.format(LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-17")));
}

/** W12, and NOW carries the red-flag line exactly once. */
@Test
void okThanksAfterANowRepeatsTheNowLine() {
    familyWith(patient("মা"));
    Document doc = doneDocument(LocalDate.parse("2026-10-16"));
    when(documents.latestDoneDocument(FAMILY)).thenReturn(Mono.just(doc));
    when(messages.latestOfKind(doc.id(), OutboundMessage.Kind.EXPLANATION)).thenReturn(Mono.just(explained(doc, Urgency.NOW, Instant.parse("2026-10-16T08:00:00Z"))));

    service.onText(FROM, "ok thanks").block();

    String sent = sentBodies().get(0);
    assertThat(sent).as("a thanks after NOW must repeat it").contains(bn("urgency.now")).startsWith(bn("wa.thanks.urgent"));
    assertThat(sent.split(java.util.regex.Pattern.quote(bn("safety.red_flag_line")), -1)).as("red-flag line once").hasSize(2);
}

/** The other direction: ROUTINE, or a THIS_WEEK older than 30 days, adds no urgency line. */
@Test
void aRoutineOrStaleDocumentNamesThePaperWithoutAnUrgencyLine() {
    familyWith(patient("মা"));
    Document doc = doneDocument(LocalDate.parse("2026-09-01"));
    when(documents.latestDoneDocument(FAMILY)).thenReturn(Mono.just(doc));
    when(messages.latestOfKind(doc.id(), OutboundMessage.Kind.EXPLANATION)).thenReturn(Mono.just(explained(doc, Urgency.THIS_WEEK, Instant.parse("2026-09-01T08:00:00Z"))));

    service.onText(FROM, "Hi").block();

    assertThat(sentBodies().get(0)).doesNotContain(bn("urgency.this_week")).doesNotContain(bn("urgency.now"))
            .contains(new Copy().documentType("lab_report"));
}
```

- [ ] **Step 3: Run (the replay).** `./gradlew :baymax:test --tests '*WaIntakeServiceTest'`. On PR 6's code every test above
  fails, e.g. `[a greeting that hides a standing THIS_WEEK is the defect] Expecting actual: "হ্যালো!\nআমি Medioo …" to contain: "এ সপ্তাহের মধ্যে একজন ডাক্তার দেখান।"`.
  **Then the mutation replay for the gate-state rule:** write Step 4 first with `messages.latestDeliverable(doc.id())`
  in place of the two `latestOfKind` calls (the tempting approach). The two dangerous-direction tests must fail with
  `[a standing NOW hidden because its wording failed safety is the defect] … to contain: "এখনই ডাক্তারের কাছে যান। দেরি করবেন না।"`
  and the PENDING twin likewise, while the RELEASED tests pass. Then switch to `latestOfKind`. Paste both reds.
- [ ] **Step 4: Implement.**

```java
private static final java.time.Duration STANDING = java.time.Duration.ofDays(30);
private static final java.time.ZoneId DHAKA = java.time.ZoneId.of("Asia/Dhaka");

/**
 * INTERIM until F1 (document.urgency, Phase 1b). The standing level is the highest urgency on the latest DONE document's
 * newest EXPLANATION and DETAIL rows, in ANY gate state: the urgency column is always set, and a PENDING or
 * FAILED_SAFETY row is exactly where a NOW would hide from latestDeliverable (D5). Known gap until F1 + D14: an older
 * urgent document behind a newer routine one is not surfaced.
 */
private record Latest(Document document, Urgency standing) {
}

private Mono<Latest> latest(FamilyAccount family) {
    Instant cutoff = clock.instant().minus(STANDING);
    return documents.latestDoneDocument(family.id())
            .flatMap(doc -> Flux.concat(messages.latestOfKind(doc.id(), OutboundMessage.Kind.EXPLANATION),
                            messages.latestOfKind(doc.id(), OutboundMessage.Kind.DETAIL))
                    .filter(m -> m.createdAt() != null && m.createdAt().isAfter(cutoff))
                    .map(OutboundMessage::urgency)
                    .reduce(Urgency.ROUTINE, (a, b) -> a.isAtLeast(b) ? a : b)
                    .map(level -> new Latest(doc, level.isAtLeast(Urgency.THIS_WEEK) ? level : null)));
}

private Map<String, String> paperVars(Document d) {
    LocalDate today = LocalDate.now(clock.withZone(DHAKA));
    LocalDate date = d.docDate() != null ? d.docDate() : LocalDate.ofInstant(d.createdAt(), DHAKA);
    return Map.of("document_type", copy.documentType(d.documentType()), "date", BanglaDate.format(date, today),
            "link", properties.getOutbound().getPublicBaseUrl() + "/app/documents/" + d.id());
}

private Mono<Outcome> greet(String number, FamilyAccount family, WaTextRouter.Route route) {
    String opener = copy.bn(switch (route) {
        case GREETING_SALAM -> "wa.greeting.salam";
        case GREETING_NAMASKAR -> "wa.greeting.namaskar";
        default -> "wa.greeting.hello";
    }, Map.of());
    String intro = copy.bn("wa.greeting.intro", Map.of());
    return latest(family)
            .map(l -> l.standing() == null
                    ? String.join("\n", opener, intro, copy.bn("wa.greeting.latest", paperVars(l.document())))
                    // the opener and the paper's name may precede the block; nothing it qualifies does (UrgencyLines)
                    : String.join("\n", opener, copy.bn("wa.greeting.urgent", paperVars(l.document())),
                            String.join("\n", UrgencyLines.block(copy, l.standing())), intro))
            .defaultIfEmpty(String.join("\n", opener, intro, copy.bn("wa.greeting.none", Map.of())))
            .flatMap(text -> send(number, text))
            .thenReturn(Outcome.REPLIED);
}

private Mono<Outcome> thanks(String number, FamilyAccount family) {
    return latest(family)
            .filter(l -> l.standing() != null)
            .map(l -> String.join("\n", copy.bn("wa.thanks.urgent", Map.of()), String.join("\n", UrgencyLines.block(copy, l.standing()))))
            .defaultIfEmpty(copy.bn("wa.thanks", Map.of()))
            .flatMap(body -> send(number, withRedFlag(body)))   // idempotent: NOW/THIS_WEEK already carry the line once
            .thenReturn(Outcome.REPLIED);
}
```

  `onText`'s greeting case passes `family`; the THANKS case calls `thanks(number, family)`.
- [ ] **Step 5:** `./gradlew :baymax:test --tests 'com.elioo.baymax.wa.*' --tests '*BaymaxAutoConfigurationTest'` green (the
  context test proves the new constructor beans resolve; `Clock` comes from `BaymaxClockConfiguration`). Stage the
  service, the bundles, the tests; `feat(baymax): WhatsApp greeting and thanks restate a standing NOW/THIS_WEEK in any gate state`.

### Task 7.2: The free-tier check runs before the "got it" ack (W15)

**Files:** modify `WaIntakeService.java` (`withPatients`); test `WaIntakeServiceTest.java` (replaces
`anExhaustedFreeTierIsExplainedRatherThanFailingSilently`, `:127-136`).

- [ ] **Step 1: Write the guard in its final form.** Add the field `private final FreeTierUseCase freeTier;` (appended
  last) as a behaviour-free stub first, with `when(freeTier.checkCanUploadDocument(any(FamilyAccount.class))).thenReturn(Mono.empty());`
  in `wire()`, so the guard below compiles on the pre-fix code and is replayed exactly as it is kept:

```java
/**
 * W15: a family at the cap heard "got it, reading now" and then "free reports used up". The check runs before the ack:
 * one message, the true one, and nothing is downloaded.
 */
@Test
void aFamilyAtTheFreeCapHearsOneMessageAndItIsTheTrueOneAndNothingIsDownloaded() {
    familyWith(patient("মা"));
    FreeTierExceededException capped = new FreeTierExceededException(FreeTierExceededException.DOCUMENTS, "free tier");
    when(freeTier.checkCanUploadDocument(any(FamilyAccount.class))).thenReturn(Mono.error(capped));
    when(intake.accept(any(), any())).thenReturn(Mono.error(capped));   // the pipeline's own check, as before the fix

    service.onImage(FROM, "MEDIA-9").block();

    assertThat(sentBodies()).as("the ack must not precede the refusal")
            .containsExactly(new Copy().bn("wa.free_tier", java.util.Map.of()));
    verify(graph, never()).downloadMedia(anyString());
}
```

- [ ] **Step 2: Run (the replay).** →
  `[the ack must not precede the refusal] Expecting actual: ["ছবিটা পেয়েছি। পড়ে দেখছি — একটু সময় লাগবে।", "এই মাসে বিনামূল্যের সব কটা রিপোর্ট পাঠানো হয়ে গেছে।…"] to contain exactly …`.
- [ ] **Step 3: Implement.** `withPatients` takes the `FamilyAccount` (pass `family` from `onImage`); replace the ack and
  download with:

```java
// the cap is checked BEFORE the ack: a family at the limit hears one true message, not "got it" and then "no".
// Mono.defer around the send: .then(send(...)) would call graph.sendText at assembly time (cf. :61-62).
return freeTier.checkCanUploadDocument(family)
        .then(Mono.defer(() -> send(number, copy.bn(ackKey, Map.of()))))
        .then(Mono.defer(() -> graph.downloadMedia(mediaId)))
        .flatMap(bytes -> intake.accept(patient.id(), List.of(new Upload(mediaId + ".jpg", bytes))))
```

  `intake.accept` keeps its own check (`DocumentIntakeService.java:54`): a race past the first check still gets
  `wa.free_tier` after the ack, which stays truthful. Delete the old free-tier test.
- [ ] **Step 4–5:** `com.elioo.baymax.wa.*` green; `fix(baymax): check the free tier before acknowledging a WhatsApp photo`.

### Task 7.3: Failures after the ack reply to the family (W13)

**Files:** modify `WaMessagingPort.java` (nested `MediaUnavailableException`), `WaGraphClient.java` (`downloadMedia`),
`WaIntakeService.java` (`onErrorResume`), `messages_bn/en.properties`; tests `WaGraphClientTest.java`, `WaIntakeServiceTest.java`.

- [ ] **Step 1: Copy** (bn | en): `wa.failed.download` ছবিটা নামাতে পারিনি — আরেকবার পাঠাবেন? | I could not download the photo — would you send it again?;
  `wa.failed.unreadable_file` এই ফাইলটা খুলতে পারছি না। কাগজের ছবি (JPG/PNG) বা PDF আবার পাঠান। | I cannot open this file. Send a photo of the paper (JPG/PNG) or a PDF again.;
  `wa.failed.too_many_pages` একটা কাগজে {max} পাতার বেশি নিতে পারি না। পাতাগুলো কয়েক ভাগে পাঠান। | I can take at most {max} pages per paper. Send the pages in smaller batches.;
  `wa.failed.generic` দুঃখিত — এটা এখন রাখতে পারলাম না। একটু পরে আবার পাঠান। | Sorry — I could not keep this just now. Please send it again in a little while.
- [ ] **Step 2: Write the failing tests.** In `WaGraphClientTest`, a client whose second call answers 404:

```java
private WaGraphClient clientWhoseDownloadIs404() {
    BaymaxProperties props = new BaymaxProperties();
    props.getWa().setEnabled(true);
    props.getWa().setToken(BEARER);
    List<Integer> call = new ArrayList<>(List.of(0));
    WebClient http = WebClient.builder().exchangeFunction(request -> {
        requestedUris.add(request.url().toString());
        boolean descriptor = call.set(0, call.get(0) + 1) == 0;
        return Mono.just(descriptor
                ? ClientResponse.create(HttpStatus.OK).header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"url\":\"" + MEDIA_URL + "\",\"mime_type\":\"image/jpeg\",\"file_size\":12}").build()
                : ClientResponse.create(HttpStatus.NOT_FOUND).build());
    }).build();
    return new WaGraphClient(props, http);
}

/** DR-23: an error travels into every caller's log. It must not carry the signed lookaside URL or its token. */
@Test
void aFailedDownloadsErrorCarriesNeitherTheUrlNorItsToken() {
    StepVerifier.create(clientWhoseDownloadIs404().downloadMedia("MEDIA-ID-3"))
            .expectErrorSatisfies(e -> {
                assertThat(requestedUris).as("it really fetched the signed URL").anyMatch(u -> u.contains(TOKEN_IN_URL));
                assertThat(e.toString() + " / " + e.getCause())
                        .as("the error a caller logs must not carry the media URL").doesNotContain(TOKEN_IN_URL).doesNotContain("lookaside");
            })
            .verify();
}
```

  In `WaIntakeServiceTest` (the `{max}` value is derived from the property the code reads, so the test tracks what is sent):

```java
/** Stock-take wa-failure-reply (2): after the ack, the download failed and the family was left on "পড়ে দেখছি". */
@Test
void aDownloadThatFailsAfterTheAckIsToldNotLeftWaiting() {
    familyWith(patient("মা"));
    when(graph.downloadMedia("MEDIA-10")).thenReturn(Mono.error(new RuntimeException("boom")));
    service.onImage(FROM, "MEDIA-10").block();
    assertThat(sentBodies()).as("after the ack the family must hear what happened — silence was the defect").hasSize(2);
    assertThat(sentBodies().get(1)).isEqualTo(bn("wa.failed.generic"));
}

@Test
void tooManyPagesSaysHowManyAreAllowed() {
    familyWith(patient("মা"));
    when(intake.accept(any(), any())).thenReturn(Mono.error(
            com.elioo.baymax.common.error.BaymaxException.badRequest("too_many_pages", "at most 10")));
    service.onImage(FROM, "MEDIA-11").block();
    assertThat(sentBodies()).as("silence after the ack — the defect").hasSize(2);
    assertThat(sentBodies().get(1)).isEqualTo(new Copy().bn("wa.failed.too_many_pages", java.util.Map.of("max", BanglaDate.digits(String.valueOf(props.getExtract().getMaxPages())))));
}

@Test
void aFileThatCannotBeOpenedSaysSo() {
    familyWith(patient("মা"));
    when(intake.accept(any(), any())).thenReturn(Mono.error(
            com.elioo.baymax.common.error.BaymaxException.badRequest("unsupported_image", "could not read MEDIA-12.jpg")));
    service.onImage(FROM, "MEDIA-12").block();
    assertThat(sentBodies()).as("silence after the ack — the defect").hasSize(2);
    assertThat(sentBodies().get(1)).isEqualTo(bn("wa.failed.unreadable_file"));
}
```

- [ ] **Step 3: Run (the replay).** `./gradlew :baymax:test --tests '*WaGraphClientTest' --tests '*WaIntakeServiceTest'`:
  - `[the error a caller logs must not carry the media URL] Expecting actual: "org.springframework.web.reactive.function.client.WebClientResponseException$NotFound: 404 Not Found from GET https://lookaside.fbsbx.com/…hash=…SECRET… / null" not to contain: "…"`.
    **If this test is green on the pre-fix code, Spring is not putting the URI in the message and the guard is
    vacuous.** Stop, print `e.toString()` once, and anchor the assertion on what the error actually carries.
  - `[after the ack the family must hear what happened — silence was the defect] Expected size: 2 but was: 1`, and the same
    for the two `BaymaxException` cases.
- [ ] **Step 4: Implement.** `WaMessagingPort`:

```java
/** downloadMedia's only error. It carries the HTTP status and nothing else: no URL, no cause (DR-23). */
class MediaUnavailableException extends RuntimeException {
    public MediaUnavailableException(int status) {
        super("media unavailable status=" + status, null, false, false);
    }
}
```

  `WaGraphClient.downloadMedia`:

```java
return media(mediaId).flatMap(m -> download(m, mediaId))
        .onErrorMap(e -> !(e instanceof MediaUnavailableException), e -> {
            int status = e instanceof org.springframework.web.reactive.function.client.WebClientResponseException w
                    ? w.getStatusCode().value() : 0;
            log.warn("[baymax] wa media download failed id={} status={}", mediaId, status);
            return new MediaUnavailableException(status);   // no cause: the cause's message holds the signed URL
        });
```

  `WaIntakeService` (replacing PR 1's class-only line with the reply plus the same class-only log; add a
  `reply(String number, String key, Outcome outcome, String why, Map<String, String> vars)` overload):

```java
.onErrorResume(e -> {
    String key = failureKey(e);
    // class and machine reason only: an exception message can carry a signed URL (DR-23) or a filename
    log.error("[baymax] wa intake failed mediaId={} reply={} error={}", mediaId, key, describe(e));
    return reply(number, key, Outcome.FAILED, "intake failed",
            Map.of("max", BanglaDate.digits(String.valueOf(properties.getExtract().getMaxPages()))));
});
```
```java
static String failureKey(Throwable e) {
    if (e instanceof WaMessagingPort.MediaUnavailableException) {
        return "wa.failed.download";
    }
    if (e instanceof BaymaxException b) {
        return switch (b.reason()) {
            case "too_many_pages" -> "wa.failed.too_many_pages";
            case "empty_file", "empty_pdf", "unreadable_pdf", "unsupported_image", "encode_failed" -> "wa.failed.unreadable_file";
            default -> "wa.failed.generic";
        };
    }
    return "wa.failed.generic";
}

static String describe(Throwable e) {
    return e instanceof BaymaxException b ? e.getClass().getSimpleName() + ":" + b.reason() : e.getClass().getSimpleName();
}
```

  The reasons come from `PageRenderer.java:49,70,73,86,98,103,156` and `DocumentIntakeService.java:71`. Add
  `aMediaUnavailableErrorGetsTheDownloadReply` (stub `Mono.error(new WaMessagingPort.MediaUnavailableException(404))`,
  expect `bn("wa.failed.download")`).
- [ ] **Step 5:** `com.elioo.baymax.wa.*` green; stage the port, the client, the service, the bundles and both tests;
  `fix(baymax): tell the family when a WhatsApp photo fails after the ack; no signed URL in a download error`.

### Task 7.4: Ship

- [ ] **Step 1:** the standard pre-merge. PR body: the three behaviour changes, the replayed reds (including the
  `latestDeliverable` mutation replay), and the copy drafts.

**Try it on your phone (PR 7):**
- [ ] Send `t3-this-week-lab.jpg` and wait for its explanation (THIS_WEEK). Then send "Hi": the reply leads with
  "হ্যালো!", then the paper's date and type, then "এ সপ্তাহের মধ্যে একজন ডাক্তার দেখান।" and the red-flag line, then the intro.
- [ ] Send "ok thanks": "ঠিক আছে। মনে করিয়ে দিই:" + the THIS_WEEK line, and the red-flag line exactly once.
- [ ] Send `t1-routine-lab.jpg`, wait, then "Hi": it names the latest paper with its link, and carries no urgency line.
- [ ] (Only if Preflight left the cap at its pilot value.) The free-tier refusal arrives alone, with no "ছবিটা পেয়েছি".
  Otherwise this path is proven by Task 7.2's test; say so in the report.
- [ ] The W13 failure replies are proven by the tests; a safe production trigger does not exist, so do not invent one.

**Post-deploy:** the standard check.

---

## PR 8 — C1: the same WhatsApp message twice is answered once (F9a)

Branch `fix/baymax-wa-inbound-idempotency`, from merged PR 7. Migration **V22**.

The webhook answers 200 as soon as the signature is good, then dispatches each message to a detached subscription
(`WaWebhookHandler.java:66-78,100-125`); nothing records which message ids were handled, so a Meta re-delivery is
processed and answered again. Its javadoc promises "acknowledged as soon as it is stored-and-dispatched", but nothing is
stored. This PR claims every message, under **HMAC(wamid)**, before the 200.

Design decisions (in the PR body for the PO):
1. **One table, one key, no raw wamid.** A wamid is base64 of the sender's number, so storing it for an unknown sender
   would store an undeletable phone number. `inbound_message.provider_key` is `HMAC-SHA256(server secret, "wamid:" ||
   id)` through `PhoneHasher` (the class already used for every number outside `family_account`), `UNIQUE` under a
   named constraint, and a CHECK that it is 64 hex characters, so a raw id cannot be stored by a future writer either.
2. **The claim happens before the 200.** If it fails (database down, secret missing), the webhook answers **503** so Meta
   retries for up to 7 days; the claim makes retries harmless. An unparseable signed body is still 200 (a retry never
   fixes it).
3. **Text is stored only when the sender resolved to a family** (only then can it be deleted on request), enforced by a
   CHECK (`body IS NULL OR family_id IS NOT NULL`), and **never when it is a six-digit code** (a family pasting its live
   login code back must not leave it in a table).
4. **FKs without cascade** on `family_id` and `patient_id`, as on `document`, so a delete path that forgets this table
   fails loudly; `document_id` is `ON DELETE SET NULL`. No `viewer_id` yet (no writer until F6).
5. **Claim-then-crash is not covered yet.** A message claimed before the 200 and lost to a restart before `handle()`
   finishes is never answered. This PR only **counts** them at startup (WARN); re-dispatch is F9c (Phase 4). Stated in
   the build report.

**Builds on PR 6's API:** `WaInbound` (typed `Type`, `providerMessageId`, `timestamp`), `WaWebhookHandler.parse`, and the
single entry point `WaIntakeService.onInbound`. This PR adds `claim()` in front of it and `markHandled` after it; it adds
no second record and no type switch.

### Task 8.1: `inbound_message`, keyed by HMAC, against real Postgres

**Files:** create `V22__inbound_message.sql`, `wa/domain/InboundMessage.java`, `wa/application/port/out/InboundMessagePort.java`,
`wa/adapter/out/persistence/PostgresInboundMessageAdapter.java`; tests `baymax/src/test/java/com/elioo/baymax/wa/domain/InboundMessageTest.java`,
`baymax/src/test/java/com/elioo/baymax/wa/adapter/out/persistence/PostgresInboundMessageAdapterTest.java`; modify
`web/application/service/PhoneHasher.java` (+`providerId`).

- [ ] **Step 1: Preflight.** `git ls-tree --name-only origin/main baymax/src/main/resources/db/baymax/migration | sort -V | tail -1`
  prints `V21__document_upload_key.sql`.
- [ ] **Step 2: Write the failing tests.** The privacy rules, both directions, on the domain constructor:

```java
class InboundMessageTest {

    private static final String KEY = "a".repeat(64);
    private static final UUID FAMILY = UUID.randomUUID();

    @Test
    void aKnownFamilysTextIsKeptAndAnUnknownSendersIsNot() {
        assertThat(InboundMessage.received(KEY, FAMILY, "text", "আম্মার রিপোর্ট", null, null).body()).isEqualTo("আম্মার রিপোর্ট");
        assertThat(InboundMessage.received(KEY, null, "text", "আম্মার রিপোর্ট", null, null).body())
                .as("text from a number with no family cannot be deleted on request, so it is never stored").isNull();
    }

    /** A family that pastes its live login code back into WhatsApp must not leave it in a table (PR 12). */
    @ParameterizedTest
    @ValueSource(strings = {"483920", " 483920 ", "৪৮৩৯২০", "৪৮৩৯২০\n"})
    void aSixDigitCodeIsNeverStored(String typed) {
        assertThat(InboundMessage.received(KEY, FAMILY, "text", typed, null, null).body()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"আম্মার বয়স ৬৮", "HbA1c 6.5", "4839201", "48392", "কোড"})
    void textThatIsNotASixDigitCodeIsKept(String typed) {
        assertThat(InboundMessage.received(KEY, FAMILY, "text", typed, null, null).body()).isEqualTo(typed);
    }
}
```

  On real Postgres (the dedupe a `HashSet` mock would fake; `runner()` exactly as in `PostgresNudgeAdapterTest.java:46-60`):

```java
/**
 * F9a on real Postgres. Meta re-delivers webhooks; the only thing that makes "answered once" true is the unique key, so
 * the duplicate path is exercised where it can actually fail — a mocked port with a HashSet would turn a duplicate into
 * a silent no-op exactly where Postgres raises 23505 (DR-22).
 */
@Testcontainers
class PostgresInboundMessageAdapterTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    // runner() exactly as in PostgresNudgeAdapterTest; sql(...) and scalar(...) JDBC helpers as there

    /** The adapter stores what it is given; the service gives it an HMAC hex. */
    private static String key(int n) {
        return String.format("%064x", n);
    }

    private static FamilyAccount family(HealthRecordPort records, String number) {
        Instant t = Instant.parse("2026-10-17T08:00:00Z");
        return records.createFamily(new FamilyAccount(null, number, "Rahim", FamilyAccount.Plan.FREE, t, t)).block();
    }

    @Test
    void aRedeliveredMessageIsClaimedOnceAndTheSecondClaimIsEmptyNotAnError() {
        runner().run(context -> {
            InboundMessagePort inbound = context.getBean(InboundMessagePort.class);
            FamilyAccount f = family(context.getBean(HealthRecordPort.class), "+8801700000601");
            InboundMessage message = InboundMessage.received(key(1), f.id(), "text", "বিস্তারিত", null,
                    Instant.parse("2026-10-17T08:00:00Z"));

            StepVerifier.create(inbound.claim(message)).assertNext(row -> {
                assertThat(row.id()).isNotNull();
                assertThat(row.providerKey()).isEqualTo(key(1));
                assertThat(row.body()).isEqualTo("বিস্তারিত");
            }).verifyComplete();
            // the identical callback again: "already have it" is EMPTY — not an error (a 503 would make Meta retry for a
            // week) and not a second row (a second answer)
            StepVerifier.create(inbound.claim(message)).verifyComplete();
            assertThat(scalar("select count(*) from baymax.inbound_message where provider_key = '" + key(1) + "'")).isEqualTo("1");
        });
    }

    @Test
    void sixteenSimultaneousDeliveriesOfOneMessageYieldExactlyOneClaim() {
        runner().run(context -> {
            InboundMessagePort inbound = context.getBean(InboundMessagePort.class);
            InboundMessage message = InboundMessage.received(key(2), null, "image", null, "MEDIA-9", null);

            List<InboundMessage> claimed = Flux.range(0, 16)
                    .flatMap(i -> inbound.claim(message).subscribeOn(Schedulers.parallel()), 16)
                    .collectList().block(Duration.ofSeconds(30));

            assertThat(claimed).as("claims won by simultaneous deliveries of one message").hasSize(1);
        });
    }

    /** The dedupe and the privacy rules rest on the database, not on the adapter. */
    @Test
    void theDatabaseRefusesASecondRowARawIdAndTextWithoutAFamily() {
        runner().run(context -> {
            sql("insert into baymax.inbound_message (provider_key, type) values ('" + key(3) + "','text')");
            assertThatThrownBy(() -> sql("insert into baymax.inbound_message (provider_key, type) values ('" + key(3) + "','text')"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("inbound_message_provider_key_key")
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23505"));
            assertThatThrownBy(() -> sql("insert into baymax.inbound_message (provider_key, type) values ('"
                    + WaFixtures.wamid("8801700000602") + "','text')"))
                    .as("a raw wamid is the phone number; it may not be the key").hasMessageContaining("inbound_message_provider_key_check");
            assertThatThrownBy(() -> sql("insert into baymax.inbound_message (provider_key, type, body) values ('" + key(4) + "','text','x')"))
                    .hasMessageContaining("inbound_message_body_needs_family");
        });
    }

    @Test
    void handlingIsRecordedOnceWithItsOutcome() {
        runner().run(context -> {
            InboundMessagePort inbound = context.getBean(InboundMessagePort.class);
            inbound.claim(InboundMessage.received(key(5), null, "text", null, null, null)).block();

            inbound.markHandled(key(5), "UNKNOWN_SENDER").block();
            inbound.markHandled(key(5), "FAILED").block();   // a late second writer does not overwrite

            assertThat(scalar("select outcome from baymax.inbound_message where provider_key = '" + key(5) + "'")).isEqualTo("UNKNOWN_SENDER");
        });
    }
}
```

- [ ] **Step 3: Run** → compile errors on `InboundMessage`, `InboundMessagePort` (new behaviour; the defect guard is
  Task 8.3's acceptance test).
- [ ] **Step 4: Implement.** V22:

```sql
-- F9a (Phase 0, PR 8): every inbound WhatsApp message, keyed so that "the same message twice is answered once" is a
-- property of the database, not of the code path that happens to run. The key is HMAC(server secret, "wamid:" || the
-- provider id), never the id: a wamid is base64 of the sender's phone number, and family_account stays the only table
-- that holds a number (V8). Text is kept only for a sender who resolved to a family (deletable on request), enforced
-- below. family_id / patient_id carry NO cascade, like document: a delete path that forgets this table fails loudly.
-- Later the same rows are the WhatsApp side of chat history (spec §7.5).
CREATE TABLE ${flyway:defaultSchema}.inbound_message (
    id                  uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_key        char(64)     NOT NULL,
    family_id           uuid         NULL REFERENCES ${flyway:defaultSchema}.family_account (id),
    patient_id          uuid         NULL REFERENCES ${flyway:defaultSchema}.patient_profile (id),
    document_id         uuid         NULL REFERENCES ${flyway:defaultSchema}.document (id) ON DELETE SET NULL,
    type                varchar(32)  NOT NULL,
    body                text         NULL,
    media_id            varchar(128) NULL,
    provider_timestamp  timestamptz  NULL,
    received_at         timestamptz  NOT NULL DEFAULT now(),
    handled_at          timestamptz  NULL,
    outcome             varchar(32)  NULL,
    CONSTRAINT inbound_message_provider_key_key UNIQUE (provider_key),
    CONSTRAINT inbound_message_provider_key_check CHECK (provider_key ~ '^[0-9a-f]{64}$'),
    CONSTRAINT inbound_message_body_needs_family CHECK (body IS NULL OR family_id IS NOT NULL)
);
CREATE INDEX inbound_message_family_received_idx ON ${flyway:defaultSchema}.inbound_message (family_id, received_at);
CREATE INDEX inbound_message_patient_idx ON ${flyway:defaultSchema}.inbound_message (patient_id);
CREATE INDEX inbound_message_unhandled_idx ON ${flyway:defaultSchema}.inbound_message (received_at) WHERE handled_at IS NULL;

COMMENT ON COLUMN ${flyway:defaultSchema}.inbound_message.outcome IS
    'WaIntakeService.Outcome name, written once (handled_at IS NULL guard). NULL = received, never answered: a restart cut it off.';
```

  `InboundMessage` (`wa/domain`):

```java
/** A row of baymax.inbound_message. No raw provider id and no phone number ever reach it. */
public record InboundMessage(UUID id, String providerKey, UUID familyId, UUID patientId, UUID documentId,
                             String type, String body, String mediaId, Instant providerTimestamp,
                             Instant receivedAt, Instant handledAt, String outcome) {

    /** WhatsApp's text limit is 4096 characters; anything longer is not a WhatsApp text. */
    static final int MAX_BODY = 4096;
    private static final java.util.regex.Pattern LOGIN_CODE = java.util.regex.Pattern.compile("^\\s*[0-9০-৯]{6}\\s*$");

    /** The privacy rules in code as well as in the CHECK: no family, no text; never a six-digit code. */
    public static InboundMessage received(String providerKey, UUID familyId, String type, String body,
                                          String mediaId, Instant providerTimestamp) {
        String kept = familyId == null || body == null || LOGIN_CODE.matcher(body).matches() ? null
                : body.length() > MAX_BODY ? body.substring(0, MAX_BODY) : body;
        return new InboundMessage(null, providerKey, familyId, null, null, type, kept, mediaId,
                providerTimestamp, null, null, null);
    }
}
```

  `InboundMessagePort`: `Mono<InboundMessage> claim(InboundMessage m)` (emits the row when **this** call inserted it, empty
  when it was already there), `Mono<Void> markHandled(String providerKey, String outcome)` (once), `Mono<Void>
  linkDocument(String providerKey, UUID patientId, UUID documentId)`, `Mono<Long> countUnhandledBefore(Instant before)`.
  `PostgresInboundMessageAdapter` (`@Component @RequiredArgsConstructor`, `DatabaseClient db`, bind-or-null idiom of
  `PostgresNudgeAdapter.save`): `claim` = `INSERT … ON CONFLICT ON CONSTRAINT inbound_message_provider_key_key DO NOTHING
  RETURNING <cols>` then `.one()`; `markHandled` = `UPDATE … SET handled_at = now(), outcome = :o WHERE provider_key = :k
  AND handled_at IS NULL`; `linkDocument` and `countUnhandledBefore` likewise. `PhoneHasher` gains
  `public String providerId(String id) { return phone("wamid:" + id); }` (the same HMAC, domain-separated; it throws the
  503-shaped `auth_not_configured` when the secret is missing).
- [ ] **Step 5:** both test classes green; `HexagonalArchitectureTest` green. `git status --ignored --short -- baymax/src | grep '^!!'`
  prints nothing (new `wa/adapter/out/persistence`). Commit: `feat(baymax): inbound_message keyed by HMAC of the provider id (F9a, V22)`.

### Task 8.2: Both delete paths, and a deletion sweep derived from the schema that cannot pass vacuously

**Files:** modify `healthrecord/adapter/out/persistence/PostgresHealthRecordAdapter.java` (`deleteFamily` `:134-150`,
`deletePatient` `:153-167`), `baymax/src/test/java/com/elioo/baymax/family/FamilyDeletionAcceptanceTest.java`.

Today's sweep asks `information_schema` for `family_id`/`patient_id` columns and checks them; the fixture inserts
nothing into `outbound_message`, `nudge`, `web_session`, `otp_code` or `audit_event`, so those pass vacuously (the
`document` defect, one table at a time), the patient path has no sweep at all, and a column named anything else (Phase
3's `viewer_id`, Phase 5's sibling links) would be missed.

- [ ] **Step 1: Write the failing tests.**
  - **Targets, derived twice and united.** (a) Every FK column in schema `baymax` whose target is `family_account`,
    `patient_profile` or `share_member`:

```sql
SELECT cl.relname AS tbl, a.attname AS col, ref.relname AS target
FROM pg_constraint c
JOIN pg_class cl ON cl.oid = c.conrelid
JOIN pg_namespace n ON n.oid = cl.relnamespace
JOIN pg_class ref ON ref.oid = c.confrelid
JOIN LATERAL unnest(c.conkey) AS k(attnum) ON true
JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum
WHERE c.contype = 'f' AND n.nspname = 'baymax' AND ref.relname IN ('family_account', 'patient_profile', 'share_member')
```

    (b) the existing `information_schema` column-name lookup, and (c) the FK-less tables that hold family data
    (`audit_event`, `stored_object`, both columns). The guard on the derivation: (a) is non-empty and contains
    `document.family_id` and `inbound_message.family_id`; the union contains every entry of (c).
  - **Fixture.** After the document rows, one row in **every** target table for this family and patient, including
    `outbound_message`, `nudge`, `web_session`, `otp_code`, `audit_event`, one `share_member` for `(f, p)`, and two
    `inbound_message` rows (a family text with a `provider_key`, and an image linked to `p` and `docA`).
  - **The guard on the guard, per (table, column):** for the family path, every target `(t, c)` holds at least one row
    for this family's id (or, for a `share_member` target, for one of this family's share-member ids recorded before the
    delete); for the patient path, every `(t, c)` whose target is `patient_profile` (or whose name is `patient_id`) holds
    a row for this patient. Assert the list of empty `(t, c)` pairs is empty, with the message "the fixture left these
    empty; the sweep would pass for them without testing anything".
  - **The red names the defect.** Capture the root logger with a `ListAppender` and make the delete assertion carry the
    log: `assertThat(status).as("delete failed; the server log says: %s", logged()).isEqualTo(200)`.
  - **Patient path.** `deletePatientLeavesNothingForThatPatientAndKeepsTheFamily`: same fixture, `DELETE` the patient,
    200, the patient-restricted sweep is empty, and the family-level text row (`patient_id` NULL) survives.
- [ ] **Step 2: Run; read the message.** `./gradlew :baymax:test --tests '*FamilyDeletionAcceptanceTest'` must fail with
  `[delete failed; the server log says: … update or delete on table "family_account" violates foreign key constraint "inbound_message_family_id_fkey" …] expected: 200 but was: 500`
  (and `inbound_message_patient_id_fkey` on the patient path). Before writing the `inbound_message` fixture rows, replay
  the guard itself: with them removed it must fail with `[the fixture left these empty …] Expecting empty but was: ["inbound_message.family_id", "inbound_message.patient_id", …]`.
- [ ] **Step 3: Implement.** `deleteFamily`, after the `nudge` line:
  `.flatMap(c -> add(c, "inbound_message", "DELETE FROM " + S + ".inbound_message WHERE family_id = :family", "family", familyId))`;
  `deletePatient`, after its `nudge` line:
  `.flatMap(c -> add(c, "inbound_message", "DELETE FROM " + S + ".inbound_message WHERE patient_id = :patient", "patient", patientId))`.
  Both run before `document` and `patient_profile` (FK order). Name `inbound_message` in the javadoc at `:122-133`.
- [ ] **Step 4:** `./gradlew :baymax:test --tests '*FamilyDeletionAcceptanceTest' --tests '*PostgresHealthRecordAdapterTest' --tests '*FamilyAccountServiceTest'` green.
- [ ] **Step 5:** `fix(baymax): both delete paths remove inbound_message; the deletion sweep is derived from the schema and refuses an empty column`.

### Task 8.3: The webhook claims before it answers

**Files:** modify `wa/adapter/in/WaWebhookHandler.java` (`receive`, the dispatch loop), `wa/application/service/WaIntakeService.java`
(`claim`, `handle`; fields `inbound`, `hasher` appended last); tests `WaWebhookRouterTest.java`, `WaIntakeServiceTest.java`;
create `baymax/src/test/java/com/elioo/baymax/wa/adapter/in/WaInboundIdempotencyAcceptanceTest.java`.

- [ ] **Step 1: Write the regression guard, end to end** (real webhook, real wiring, real Postgres; only Graph is stubbed).
  It compiles on the pre-fix source: it uses `WaMessagingPort`, `WaSignature`, `WaWebhookRouter`, PR 1's `WaFixtures` and
  `Redact`, `PhoneHasher.phone` (which exists today; `providerId` is spelled out as `phone("wamid:" + id)`) and SQL.

```java
package com.elioo.baymax.wa.adapter.in;

import com.elioo.baymax.config.BaymaxAutoConfiguration;
import com.elioo.baymax.wa.application.port.out.WaMessagingPort;
import com.elioo.healthcare.llm.config.LlmAutoConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.r2dbc.R2dbcDataAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * F9a acceptance, over the real webhook, real wiring and real Postgres: Meta re-delivers a signed callback and the
 * family hears ONE reply. Only the Graph boundary is stubbed, recording what would have been sent.
 */
@Testcontainers
class WaInboundIdempotencyAcceptanceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static final String APP_SECRET = "88e3540dd539aabbccddeeff00112233";
    static final String NUMBER = "+8801700000777";
    static final List<String> SENT = new CopyOnWriteArrayList<>();

    static String callback(String wamid) {
        return """
                {"object":"whatsapp_business_account","entry":[{"changes":[{"value":{"messages":[
                  {"id":"%s","type":"text","from":"8801700000777","timestamp":"1792224000",
                   "text":{"body":"বিস্তারিত"}}]}}]}]}""".formatted(wamid);
    }

    @Configuration
    static class StubGraph {
        @Bean
        @Primary
        WaMessagingPort recordingGraph() {
            return new WaMessagingPort() {
                @Override public Mono<byte[]> downloadMedia(String mediaId) { return Mono.error(new IllegalStateException("no media in this test")); }
                @Override public Mono<String> sendText(String to, String body) { SENT.add(body); return Mono.just(WaFixtures.wamid("8801700000777", 100 + SENT.size())); }
            };
        }
    }

    @BeforeEach
    void clear() {
        SENT.clear();
    }

    private ReactiveWebApplicationContextRunner runner() {
        return new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        R2dbcAutoConfiguration.class, R2dbcDataAutoConfiguration.class,
                        R2dbcTransactionManagerAutoConfiguration.class, TransactionAutoConfiguration.class,
                        LlmAutoConfiguration.class, BaymaxAutoConfiguration.class))
                .withUserConfiguration(StubGraph.class)
                .withPropertyValues(
                        "baymax.enabled=true", "llm.provider=groq", "llm.groq.api-key=test-key",
                        "baymax.wa.enabled=true",
                        "baymax.wa.token=EAA" + "x".repeat(200),
                        "baymax.wa.app-secret=" + APP_SECRET,
                        "baymax.wa.verify-token=78d41af3449a9bfbd560140166c6f2c2",
                        "baymax.wa.phone-number-id=1412074765313019",
                        "baymax.wa.waba-id=4407553579467225",
                        "baymax.wa.allowlist=" + NUMBER,
                        "baymax.auth.hmac-secret=" + "s".repeat(32),   // the inbound key is HMAC(wamid)
                        "spring.r2dbc.url=r2dbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName(),
                        "spring.r2dbc.username=" + POSTGRES.getUsername(),
                        "spring.r2dbc.password=" + POSTGRES.getPassword(),
                        "baymax.flyway.url=" + POSTGRES.getJdbcUrl() + "&sslmode=disable",
                        "baymax.flyway.user=" + POSTGRES.getUsername(),
                        "baymax.flyway.password=" + POSTGRES.getPassword());
    }

    @SuppressWarnings("unchecked")
    private static WebTestClient webhook(org.springframework.context.ApplicationContext context) {
        return WebTestClient.bindToRouterFunction((RouterFunction<ServerResponse>) context.getBean("baymaxWaRoutes")).build();
    }

    private static void deliver(WebTestClient webhook, byte[] body) throws Exception {
        webhook.post().uri(WaWebhookRouter.WEBHOOK_PATH)
                .header("X-Hub-Signature-256", WaSignature.sign(body, APP_SECRET))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isOk();
    }

    /** Replies are sent from detached work: wait for the first, then give any second the same time before counting. */
    private static void settle() {
        await().atMost(Duration.ofSeconds(10)).until(() -> !SENT.isEmpty());
        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(3)).until(() -> true);
    }

    @Test
    void theSameMessageDeliveredTwiceIsAnsweredOnce() {
        runner().run(context -> {
            seedFamily();
            byte[] body = callback(WaFixtures.wamid("8801700000777", 1)).getBytes(StandardCharsets.UTF_8);

            deliver(webhook(context), body);
            deliver(webhook(context), body);   // Meta re-delivers the identical signed callback
            settle();

            assertThat(SENT).as("replies to one WhatsApp message id that Meta delivered twice").hasSize(1);
        });
    }

    @Test
    void twoSimultaneousDeliveriesOfOneMessageAreAnsweredOnce() {
        runner().run(context -> {
            seedFamily();
            byte[] body = callback(WaFixtures.wamid("8801700000777", 2)).getBytes(StandardCharsets.UTF_8);
            WebTestClient client = webhook(context);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch go = new CountDownLatch(1);
            try {
                List<Future<?>> both = List.of(
                        pool.submit(() -> { go.await(); deliver(client, body); return null; }),
                        pool.submit(() -> { go.await(); deliver(client, body); return null; }));
                go.countDown();
                for (Future<?> f : both) { f.get(); }
            } finally {
                pool.shutdownNow();
            }
            settle();

            assertThat(SENT).as("replies to one WhatsApp message id delivered twice at the same instant").hasSize(1);
        });
    }

    private static void seedFamily() throws Exception {
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             java.sql.Statement s = c.createStatement()) {
            s.execute("insert into baymax.family_account (whatsapp_number, owner_name, terms_accepted_at) values ('"
                    + NUMBER + "','Rahim', now()) on conflict (whatsapp_number) do nothing");
        }
    }

    // ---- these read inbound_message and are not part of the replay ----

    @Test
    void aClaimedMessageRecordsItsFamilyTextAndOutcome() {
        runner().run(context -> {
            seedFamily();
            String wamid = WaFixtures.wamid("8801700000777", 3);
            String key = context.getBean(PhoneHasher.class).phone("wamid:" + wamid);   // providerId, spelled out: this file must compile pre-fix
            deliver(webhook(context), callback(wamid).getBytes(StandardCharsets.UTF_8));
            settle();
            await().atMost(Duration.ofSeconds(5)).until(() -> "1".equals(scalar(
                    "select count(*) from baymax.inbound_message where handled_at is not null and provider_key = '" + key + "'")));

            assertThat(scalar("select (family_id is not null) || '/' || body || '/' || outcome from baymax.inbound_message "
                    + "where provider_key = '" + key + "'")).isEqualTo("true/বিস্তারিত/REPLIED");
        });
    }

    /** No phone number and no wamid in the table for an unknown sender, in any column: a wamid IS the number. */
    @Test
    void anUnknownSendersMessageLeavesNoNumberAndNoWamidInTheTable() {
        runner().run(context -> {
            String stranger = "8801700000999";
            String wamid = WaFixtures.wamid(stranger, 4);
            String key = context.getBean(PhoneHasher.class).phone("wamid:" + wamid);   // providerId, spelled out: this file must compile pre-fix
            deliver(webhook(context), callback(wamid).replace("8801700000777", stranger).getBytes(StandardCharsets.UTF_8));
            await().atMost(Duration.ofSeconds(10)).until(() -> "1".equals(scalar(
                    "select count(*) from baymax.inbound_message where provider_key = '" + key + "'")));

            String row = scalar("select row_to_json(t)::text from baymax.inbound_message t where provider_key = '" + key + "'");
            assertThat(row).contains("\"family_id\":null");
            assertThat(row).as("a wamid is the sender's number; neither may be stored")
                    .doesNotContain(wamid).doesNotContain(stranger).doesNotContain(WaFixtures.decodedDigits(wamid))
                    .doesNotContain("বিস্তারিত");
        });
    }

    /** A re-delivery's log lines carry digests: never the wamid, never the number. */
    @Test
    void noLogLineCarriesTheWamidOrTheNumber() {
        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var logged = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logged.start();
        root.addAppender(logged);
        try {
            runner().run(context -> {
                seedFamily();
                String wamid = WaFixtures.wamid("8801700000777", 5);
                byte[] body = callback(wamid).getBytes(StandardCharsets.UTF_8);
                deliver(webhook(context), body);
                deliver(webhook(context), body);
                settle();
                String all = String.join("\n", logged.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList());
                assertThat(all).contains("wa inbound duplicate ref=" + Redact.id(wamid));
                assertThat(all).doesNotContain(wamid).doesNotContain("8801700000777").doesNotContain(WaFixtures.decodedDigits(wamid));
            });
        } finally {
            root.detachAppender(logged);
        }
    }

    private static String scalar(String query) throws Exception {
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             java.sql.Statement s = c.createStatement(); java.sql.ResultSet rs = s.executeQuery(query)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
```

  **Handler unit tests** in `WaWebhookRouterTest`. Its `client()` becomes `client(WaIntakeService intake)`; the default
  stubs `when(intake.claim(any())).thenReturn(Mono.empty())` (an unstubbed mock returns `null`, which would NPE into a
  503 and make the existing "accepted" test meaningless). `BODY`'s message id is `WaFixtures.wamid(FROM)` (PR 1).

```java
    @Test
    void aMessageAlreadyClaimedIsAcknowledgedButNeverHandledAgain() throws Exception {
        WaIntakeService intake = mock(WaIntakeService.class);
        when(intake.claim(any())).thenReturn(Mono.empty());           // the store already has this message
        byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
        client(intake).post().uri(WaWebhookRouter.WEBHOOK_PATH)
                .header("X-Hub-Signature-256", WaSignature.sign(body, APP_SECRET))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isOk();
        verify(intake).claim(argThat(in -> WAMID.equals(in.providerMessageId()) && in.type() == WaInbound.Type.IMAGE));
        verify(intake, never()).handle(any());
    }

    @Test
    void aFreshMessageIsHandledOnce() throws Exception {
        WaIntakeService intake = mock(WaIntakeService.class);
        when(intake.claim(any())).thenAnswer(i -> Mono.just(i.getArgument(0)));
        when(intake.handle(any())).thenReturn(Mono.just(WaIntakeService.Outcome.ACCEPTED));
        byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
        client(intake).post().uri(WaWebhookRouter.WEBHOOK_PATH)
                .header("X-Hub-Signature-256", WaSignature.sign(body, APP_SECRET))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isOk();
        verify(intake, timeout(2000).times(1)).handle(argThat(in -> WAMID.equals(in.providerMessageId())));
    }

    /** A message we could not record is not acknowledged: Meta retries, and the claim makes the retry safe. */
    @Test
    void whenTheMessageCannotBeRecordedMetaIsAskedToRetry() throws Exception {
        WaIntakeService intake = mock(WaIntakeService.class);
        when(intake.claim(any())).thenReturn(Mono.error(new org.springframework.dao.DataAccessResourceFailureException("db down")));
        byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
        client(intake).post().uri(WaWebhookRouter.WEBHOOK_PATH)
                .header("X-Hub-Signature-256", WaSignature.sign(body, APP_SECRET))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isEqualTo(503);
        verify(intake, never()).handle(any());
    }

    /** A retry will never fix bad JSON, so it is still a 200. */
    @Test
    void aSignedBodyThatIsNotJsonIsStillA200() throws Exception {
        byte[] body = "not json".getBytes(StandardCharsets.UTF_8);
        client(mock(WaIntakeService.class)).post().uri(WaWebhookRouter.WEBHOOK_PATH)
                .header("X-Hub-Signature-256", WaSignature.sign(body, APP_SECRET))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isOk();
    }
```

  **Service unit tests** in `WaIntakeServiceTest` (add `InboundMessagePort inbound = mock(...)` and a real `PhoneHasher`
  over `props` with `props.getAuth().setHmacSecret("s".repeat(32))`; stub `inbound.markHandled(anyString(), anyString())`
  and `inbound.linkDocument(anyString(), any(), any())` to `Mono.empty()`):

```java
    private static final String WAMID = WaFixtures.wamid(FROM, 21);

    @Test
    void aKnownFamilysTextIsClaimedUnderTheHmacWithItsFamilyAndBodyAndNeverTheRawId() {
        familyWith(patient("মা"));
        when(inbound.claim(any())).thenAnswer(i -> Mono.just(i.getArgument(0)));
        service.claim(new WaInbound(WAMID, FROM, WaInbound.Type.TEXT, "বিস্তারিত", "", "")).block();
        ArgumentCaptor<InboundMessage> row = ArgumentCaptor.forClass(InboundMessage.class);
        verify(inbound).claim(row.capture());
        assertThat(row.getValue().providerKey()).isEqualTo(hasher.providerId(WAMID)).doesNotContain(WAMID).matches("[0-9a-f]{64}");
        assertThat(row.getValue().familyId()).isEqualTo(FAMILY);
        assertThat(row.getValue().body()).isEqualTo("বিস্তারিত");
    }

    @Test
    void anUnknownSendersTextIsClaimedWithoutItsText() {
        when(records.findFamilyByWhatsapp(E164)).thenReturn(Mono.empty());
        when(inbound.claim(any())).thenAnswer(i -> Mono.just(i.getArgument(0)));
        service.claim(new WaInbound(WAMID, FROM, WaInbound.Type.TEXT, "আম্মার রিপোর্ট", "", "")).block();
        ArgumentCaptor<InboundMessage> row = ArgumentCaptor.forClass(InboundMessage.class);
        verify(inbound).claim(row.capture());
        assertThat(row.getValue().familyId()).isNull();
        assertThat(row.getValue().body()).isNull();
    }

    @Test
    void aHandledPhotoRecordsItsOutcomeAndLinksItsDocument() {
        PatientProfile ma = patient("মা");
        familyWith(ma);
        Document doc = mock(Document.class);
        UUID docId = UUID.randomUUID();
        when(doc.id()).thenReturn(docId);
        when(intake.accept(eq(ma.id()), any())).thenReturn(Mono.just(doc));

        assertThat(service.handle(new WaInbound(WAMID, FROM, WaInbound.Type.IMAGE, "", "MEDIA-1", "image/jpeg")).block())
                .isEqualTo(WaIntakeService.Outcome.ACCEPTED);
        verify(inbound).linkDocument(hasher.providerId(WAMID), ma.id(), docId);
        verify(inbound).markHandled(hasher.providerId(WAMID), "ACCEPTED");
    }

    @Test
    void aFailedIntakeIsStillMarkedHandledWithFailed() {
        familyWith(patient("মা"));
        when(graph.downloadMedia("MEDIA-2")).thenReturn(Mono.error(new WaMessagingPort.MediaUnavailableException(404)));
        service.handle(new WaInbound(WAMID, FROM, WaInbound.Type.IMAGE, "", "MEDIA-2", "image/jpeg")).block();
        verify(inbound).markHandled(eq(hasher.providerId(WAMID)), anyString());
    }
```

- [ ] **Step 2: Replay the guard on the pre-fix source, in a scratch worktree.** The unit tests above call `claim`/`handle`,
  which do not exist before the fix, so the replay cannot run in the working tree:
  `git worktree add /tmp/replay-pr8 origin/main && cp baymax/src/test/java/com/elioo/baymax/wa/adapter/in/WaInboundIdempotencyAcceptanceTest.java /tmp/replay-pr8/baymax/src/test/java/com/elioo/baymax/wa/adapter/in/`
  then, in `/tmp/replay-pr8`, `./gradlew :baymax:test --tests '*WaInboundIdempotencyAcceptanceTest.theSameMessageDeliveredTwiceIsAnsweredOnce' --tests '*WaInboundIdempotencyAcceptanceTest.twoSimultaneousDeliveriesOfOneMessageAreAnsweredOnce'`.
  Both must fail with `[replies to one WhatsApp message id that Meta delivered twice] Expected size: 1 but was: 2 in: [<wa.no_document>, <wa.no_document>]`
  (the seeded family has no documents, so PR 6's detail route answers `wa.no_document`, twice). A failure on context
  startup, on the stub, or in Awaitility's timeout is **not** a replay. The tests that read `inbound_message` are not
  part of the replay. `git worktree remove /tmp/replay-pr8`; paste the output into the PR body.
- [ ] **Step 3: Implement.** `WaWebhookHandler`: `receive` (after the signature check) becomes

```java
                    return handle(body)
                            .then(ServerResponse.ok().bodyValue("ok"))
                            .onErrorResume(e -> {
                                // nothing was recorded, so nothing is acknowledged: Meta retries, and the claim makes that safe
                                log.error("[baymax] wa webhook: inbound could not be recorded, asking Meta to retry: {}",
                                        e.getClass().getSimpleName());
                                return ServerResponse.status(503).bodyValue("retry");
                            });
```

```java
    private Mono<Void> handle(byte[] body) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (java.io.IOException e) {
            log.error("[baymax] wa webhook: signed callback did not parse as JSON: {}", e.getClass().getSimpleName());
            return Mono.empty();   // a retry will never parse either
        }
        logCallback(root);
        List<WaInbound> messages = new ArrayList<>();
        for (JsonNode entry : root.path("entry")) {
            for (JsonNode change : entry.path("changes")) {
                for (JsonNode m : change.path("value").path("messages")) {
                    messages.add(parse(m));
                }
            }
        }
        // claim in the request path, one message at a time: a failure here fails the request (503)
        return Flux.fromIterable(messages)
                .concatMap(in -> intake.claim(in)
                        .doOnNext(claimed -> subscribe(intake.handle(claimed), claimed))
                        .switchIfEmpty(Mono.fromRunnable(() -> log.info(
                                "[baymax] wa inbound duplicate ref={} ignored (already claimed)", Redact.id(in.providerMessageId())))))
                .then();
    }

    private void subscribe(Mono<WaIntakeService.Outcome> work, WaInbound in) {
        work.subscribeOn(Schedulers.boundedElastic())
                .subscribe(outcome -> log.info("[baymax] wa intake done ref={} outcome={} from={}",
                                Redact.id(in.providerMessageId()), outcome, maskedNumber(in.from())),
                        error -> log.error("[baymax] wa intake errored ref={} from={}: {}",
                                Redact.id(in.providerMessageId()), maskedNumber(in.from()), error.getClass().getSimpleName()));
    }
```

  Rewrite the class javadoc: 200 once each message is **claimed**; 503 if a claim fails; duplicates are acknowledged and
  ignored. `WaIntakeService`:

```java
    /** Step 0 (spec §7.1): record the message under HMAC(provider id) before the webhook answers. Empty = already had it. */
    public Mono<WaInbound> claim(WaInbound in) {
        if (in.providerMessageId() == null || in.providerMessageId().isBlank()) {
            log.warn("[baymax] wa inbound without a provider id type={} — processed without idempotency", in.type());
            return Mono.just(in);
        }
        // defer: a missing HMAC secret must be an error signal (→ 503, Meta retries), not a throw at assembly time
        return Mono.defer(() -> {
            String key = hasher.providerId(in.providerMessageId());
            return records.findFamilyByWhatsapp(e164(in.from()))
                    .map(family -> java.util.Optional.of(family.id()))
                    .defaultIfEmpty(java.util.Optional.empty())
                    .flatMap(family -> inbound.claim(InboundMessage.received(key, family.orElse(null),
                            in.type().name().toLowerCase(java.util.Locale.ROOT), in.text(), in.mediaId(), in.timestamp())))
                    .map(row -> in);
        });
    }

    /** Everything after the claim: PR 6's single entry point, then the outcome written once to the row. */
    public Mono<Outcome> handle(WaInbound in) {
        boolean keyed = in.providerMessageId() != null && !in.providerMessageId().isBlank();
        return onInbound(in)
                .onErrorResume(e -> {
                    log.error("[baymax] wa handle failed ref={} error={}", Redact.id(in.providerMessageId()), e.getClass().getSimpleName());
                    return Mono.just(Outcome.FAILED);
                })
                .flatMap(outcome -> !keyed ? Mono.just(outcome)
                        : inbound.markHandled(hasher.providerId(in.providerMessageId()), outcome.name())
                                .onErrorResume(e -> Mono.empty())   // the answer already went; a lost stamp shows as unhandled
                                .thenReturn(outcome));
    }
```

  In the IMAGE/readable-DOCUMENT path, `onInbound` passes `in` through to `withPatients`, which after `intake.accept(...)`
  adds `.flatMap(document -> inbound.linkDocument(hasher.providerId(in.providerMessageId()), patient.id(), document.id()).thenReturn(document))`
  when the id is present; the public two-argument `onImage(from, mediaId)` passes none. `WaLogHygieneTest` (PR 1) stubs
  `claim` to `Mono.just(in)` and `handle` to `Mono.just(ACCEPTED)`, as `WaWebhookRouterTest` does. Update every `new WaIntakeService(`
  (the constructor table) and `EveryInboundTypeIsAnsweredTest`, which now also stubs the claim through a real
  `PhoneHasher` and an in-test `InboundMessagePort` that accepts every key once.
- [ ] **Step 4:** `./gradlew :baymax:test --tests '*WaWebhookRouterTest' --tests '*WaIntakeServiceTest' --tests '*WaInboundIdempotencyAcceptanceTest' --tests '*EveryInboundTypeIsAnsweredTest' --tests '*WaLogHygieneTest'`,
  then `./gradlew build`. No new log line prints a wamid, a number unmasked, a text, or an exception message.
- [ ] **Step 5:** stage the handler, the service, the four test files;
  `fix(baymax): claim each WhatsApp message before answering — a re-delivery is answered once, a failed claim is retried (F9a)`.

### Task 8.4: Count what a restart cut off

**Files:** create `wa/adapter/in/WaInboundStartupReport.java` (`@ConditionalOnProperty(baymax.wa.enabled)`) and
`baymax/src/test/java/com/elioo/baymax/wa/adapter/in/WaInboundStartupReportTest.java` (Testcontainers).

- [ ] **Step 1: Write the test, both directions.** Insert by SQL two rows with `handled_at` NULL and `received_at` one
  hour before the report's construction, one handled row, and one unhandled row with `received_at` after the cutoff.
  Build the report (it captures `clock.instant()` in its constructor), call `onReady()` directly
  (`ApplicationContextRunner` publishes no ready event), and assert the captured WARN reads
  `2 WhatsApp message(s) were received but never answered before this restart`. Second test: only handled rows → no
  WARN line at all.
- [ ] **Step 2: Run** → compile error (new class).
- [ ] **Step 3: Implement.** `onReady()` runs `inbound.countUnhandledBefore(cutoff)` on `boundedElastic` and logs the WARN
  when > 0. Re-dispatch is F9c (Phase 4); the count tells Tanvir whether that work is urgent.
- [ ] **Step 4–5:** green; `feat(baymax): count WhatsApp messages a restart left unanswered`.

### Task 8.5: WhatsApp needs the HMAC secret

**Files:** modify `wa/config/WaConfigValidator.java`; test `WaConfigValidatorTest.java`.

- [ ] **Step 1:** two tests. With WhatsApp on and `baymax.auth.hmac-secret` blank, `validate` throws a message naming
  `BAYMAX_AUTH_HMAC_SECRET` ("inbound messages are keyed by it; without it every webhook answers 503"); with it set, it
  passes. With WhatsApp off and it blank, it passes (the web login has its own fail-closed check).
- [ ] **Step 2:** red: `Expecting code to raise a throwable`.
- [ ] **Step 3:** add the `problems.add(...)` line.
- [ ] **Step 4–5:** green; `feat(baymax): WhatsApp refuses to start without the HMAC secret its inbound keys need`.

### Task 8.6: A replay script, so "the same message twice" is something Tanvir can try

**Files:** create `scripts/wa-replay-callback.py` and `baymax/src/test/java/com/elioo/baymax/wa/adapter/in/WaReplayCallbackScriptTest.java`;
modify `docs/RUNBOOK.md`.

A phone cannot force a Meta re-delivery. The script builds one signed text callback, with a fixed wamid of the right
shape, from the first number on `BAYMAX_WA_ALLOWLIST`, and POSTs it twice.

- [ ] **Step 1: Write the test.** It runs `python3 -I ../scripts/wa-replay-callback.py --env <tmp file> --dry-run` through
  `ProcessBuilder` (a hard requirement: if `python3` is missing the test fails, it does not skip). The temp env file
  holds `BAYMAX_WA_APP_SECRET=<test secret>` and `BAYMAX_WA_ALLOWLIST=+8801700000000`. Assert: the dry run prints JSON
  `{path, body, signature}`; `path` equals `WaWebhookRouter.WEBHOOK_PATH`; `signature` equals
  `WaSignature.sign(body.getBytes(UTF_8), secret)`; the body's message id satisfies
  `WaFixtures.decodedDigits(id).equals("8801700000000")`; stdout and stderr contain neither the secret nor the bare number
  outside the body.
- [ ] **Step 2:** red (no script).
- [ ] **Step 3: Implement** `scripts/wa-replay-callback.py` (standard library only: `argparse`, `base64`, `hmac`,
  `hashlib`, `json`, `urllib.request`): read the two variables from `--env` (default `~/medscribe.env`) with exact-key
  matching; build the wamid as `0x1c 0x18 len, digits, 0x15 0x02 0x00 0x12 0x18 0x14, 'REPLAY' + yyyymmddHHMM + '00'`,
  base64; body = one `messages[]` text "বিস্তারিত" from that number; signature `sha256=` + HMAC-SHA256 hex; `--url`
  default `http://localhost:8086`; POST twice with `X-Hub-Signature-256`; print the two HTTP statuses only. Never print
  the secret, the number or the body except under `--dry-run`.
- [ ] **Step 4:** green. RUNBOOK: how to run it (`scp scripts/wa-replay-callback.py` to the box, `python3 -I
  ~/wa-replay-callback.py`), what proves it (one WhatsApp reply; one `wa inbound duplicate ref=… ignored` line), and that
  it is a test aid for the allowlisted number only.
- [ ] **Step 5:** stage the script, the test, the runbook;
  `feat(scripts): replay one signed WhatsApp callback twice, to try idempotency from the server`. The standard
  pre-merge (migration check: V21 is the latest on main).

**Try it on your phone (PR 8):**
- [ ] On the box: `python3 -I ~/wa-replay-callback.py` prints two `200`s. On the phone: **one** reply arrives (the
  pointer, or `wa.no_document` if no paper is done). In the log:
  `docker logs medscribe-ai --since 5m 2>&1 | grep -c 'wa inbound duplicate'` is 1.
- [ ] Send "বিস্তারিত" normally from the phone: one reply. `docker logs … | grep 'wa intake done ref='` shows a digest,
  never a wamid.
- [ ] After the next deploy, `docker logs medscribe-ai 2>&1 | grep 'never answered before this restart'` either prints
  nothing or a count; put the count in the build report.

**Post-deploy:** the standard check.

---

## PR 9 — B2: every document ends with a reply, from one place

Branch `fix/baymax-every-document-ends-with-a-reply`, from merged PR 8. Migration **V23**.

Three paths still end in silence after "পড়ে দেখছি": a document that ends FAILED (the 2026-10-16 credits outage;
`compose`'s `default -> Mono.empty()`), an EXPLANATION that failed safety (the FAILED_SAFETY row is saved and nothing is
sent), and an explanation that throws (elided medicine, non-verbatim medicine or diagnosis, urgency mismatch,
`ExplanationService.java:142-168,193-195`). **One rule, asserted where every writer meets it:** every FAILED goes through
`DocumentExtractionService` (via `DocumentProcessingChain.run`) or `DocumentProcessingChain.fail` (PR 4), both lead to
`ExplanationService.explain`, and `explain` turns FAILED into the notice. The EXPLANATION fallback is code-only and
value-free; a DETAIL that failed safety keeps PR 5's `wa.detail.unavailable` (unchanged).

PR 5's `DetailDeliveryGuardsTest` already proves the detail arrives once, in both gate modes; no second "arrives once"
test is added here (it would duplicate those guards).

### Task 9.1: V23 — an outbound kind for failure notices (Testcontainers, both directions)

**Files:** create `V23__outbound_failure_kind.sql`, `baymax/src/test/java/com/elioo/baymax/outbound/FailureKindAcceptanceTest.java`;
modify `OutboundMessage.java` (`Kind.FAILURE`), `PostgresOutboundMessageAdapter.java` (`consecutiveRetakes`).

- [ ] **Step 1: Preflight** — the latest migration on main is `V22__inbound_message.sql`. **Write the failing test.** Add
  the enum constant `FAILURE` first, with no migration, so the red is the database refusing:

```java
package com.elioo.baymax.outbound;

// imports as DeliveryOutcomeAcceptanceTest (:3-31) plus DocumentRecordPort, Document, PatientProfile,
// FreeTierUseCase, FreeTierExceededException, StepVerifier

/**
 * A document that ends FAILED sends the family a notice (Phase 0). The notice is an outbound_message of its own kind,
 * so it passes the gate stage and records its provider outcome like every other (V13/V14). On real Postgres, because
 * the kind is a CHECK constraint (CLAUDE.md: a constrained path is never only mocked).
 */
@Testcontainers
class FailureKindAcceptanceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private ReactiveWebApplicationContextRunner runner() {
        // copied verbatim from DeliveryOutcomeAcceptanceTest.runner() (:48-63)
    }

    private static OutboundMessage row(UUID family, OutboundMessage.Kind kind, Instant at) {
        return new OutboundMessage(null, family, null, null, kind, Urgency.ROUTINE, List.of(), "x",
                OutboundMessage.GateStatus.RELEASED, null, null, null, null, at);
    }

    @Test
    void aFailureNoticeIsStorableAndReadsBackAsItsOwnKind() {
        runner().run(context -> {
            OutboundMessagePort messages = context.getBean(OutboundMessagePort.class);
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            Instant now = Instant.parse("2026-10-17T09:00:00Z");
            FamilyAccount family = records.createFamily(new FamilyAccount(null, "+8801700000801", "Rahim",
                    FamilyAccount.Plan.FREE, now, now)).block();
            OutboundMessage saved = messages.save(row(family.id(), OutboundMessage.Kind.FAILURE, now)).block();
            assertThat(column(saved.id(), "kind")).isEqualTo("failure");
            assertThat(messages.find(saved.id()).block().kind()).isEqualTo(OutboundMessage.Kind.FAILURE);
        });
    }

    /** The other direction: widening the CHECK must not have dropped it. */
    @Test
    void anUnknownKindIsStillRefusedByTheDatabase() {
        runner().run(context -> {
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            Instant now = Instant.parse("2026-10-17T09:05:00Z");
            FamilyAccount family = records.createFamily(new FamilyAccount(null, "+8801700000802", "Karim",
                    FamilyAccount.Plan.FREE, now, now)).block();
            assertThatThrownBy(() -> execute("INSERT INTO baymax.outbound_message (family_id, kind, urgency, gate_status) "
                    + "VALUES ('" + family.id() + "', 'bogus', 'routine', 'released')"))
                    .hasMessageContaining("outbound_message_kind_check");
        });
    }

    /**
     * A failure is our fault, not the family's success, so it must not reset the retake escalation
     * (ExplanationService.retake, :90-97). An explanation still does reset it.
     */
    @Test
    void aFailureNoticeDoesNotResetTheRetakeEscalationButAnExplanationDoes() {
        runner().run(context -> {
            OutboundMessagePort messages = context.getBean(OutboundMessagePort.class);
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            Instant t = Instant.parse("2026-10-17T10:00:00Z");
            FamilyAccount family = records.createFamily(new FamilyAccount(null, "+8801700000803", "Salma",
                    FamilyAccount.Plan.FREE, t, t)).block();
            messages.save(row(family.id(), OutboundMessage.Kind.RETAKE, t)).block();
            messages.save(row(family.id(), OutboundMessage.Kind.FAILURE, t.plusSeconds(60))).block();
            messages.save(row(family.id(), OutboundMessage.Kind.RETAKE, t.plusSeconds(120))).block();
            assertThat(messages.consecutiveRetakes(family.id()).block())
                    .as("a failure notice between two retakes must not reset the count").isEqualTo(2L);

            messages.save(row(family.id(), OutboundMessage.Kind.EXPLANATION, t.plusSeconds(180))).block();
            messages.save(row(family.id(), OutboundMessage.Kind.RETAKE, t.plusSeconds(240))).block();
            assertThat(messages.consecutiveRetakes(family.id()).block()).isEqualTo(1L);
        });
    }

    /** failure.document says "this will not be counted": asserted through the check an upload actually meets. */
    @Test
    void aFamilyOneBelowTheCapWithAFailedDocumentMayStillUploadAndOneMoreDoneBlocksIt() {
        runner().withPropertyValues("baymax.free.max-docs-per-month=2").run(context -> {
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            DocumentRecordPort documents = context.getBean(DocumentRecordPort.class);
            FreeTierUseCase freeTier = context.getBean(FreeTierUseCase.class);
            Instant now = Instant.now();   // the free tier counts the current UTC month
            FamilyAccount family = records.createFamily(new FamilyAccount(null, "+8801700000804", "Nasrin",
                    FamilyAccount.Plan.FREE, now, now)).block();
            PatientProfile ma = records.createPatient(new PatientProfile(null, family.id(), "মা", 70,
                    PatientProfile.Sex.FEMALE, List.of(), now, now)).block();
            finished(documents, ma, Document.Status.DONE, now);
            finished(documents, ma, Document.Status.FAILED, now);

            StepVerifier.create(freeTier.checkCanUploadDocument(family.id())).verifyComplete();   // 1 DONE + 1 FAILED, cap 2

            finished(documents, ma, Document.Status.DONE, now);
            StepVerifier.create(freeTier.checkCanUploadDocument(family.id()))
                    .expectError(FreeTierExceededException.class).verify();                    // 2 DONE: at the cap
        });
    }

    private static void finished(DocumentRecordPort documents, PatientProfile p, Document.Status status, Instant at) {
        Document d = documents.create(Document.received(p.id(), p.familyId(), 1, at)).block();
        documents.update(d.withStatus(status, status == Document.Status.FAILED ? "llm_error" : null, at)).block();
    }

    // execute(sql) and column(id, name): copied verbatim from DeliveryOutcomeAcceptanceTest (:203-217)
}
```

- [ ] **Step 2: Run (Docker).** `aFailureNoticeIsStorableAndReadsBackAsItsOwnKind` fails with
  `… new row for relation "outbound_message" violates check constraint "outbound_message_kind_check"`: the database cannot
  hold a failure notice. `anUnknownKindIsStillRefusedByTheDatabase` and the free-tier test are green: the safe direction
  and the copy claim, written first. The retake test also dies on the kind check and is replayed after Step 3.
- [ ] **Step 3: The migration, then the counter.**

```sql
-- Phase 0, PR 9: a document that ends FAILED sends the family a notice, because they were told "reading it" and
-- silence after that is the worst outcome. The notice is an outbound message of its own kind, so it passes the same
-- gate stage and records its provider outcome (V13/V14).
ALTER TABLE ${flyway:defaultSchema}.outbound_message DROP CONSTRAINT IF EXISTS outbound_message_kind_check;
ALTER TABLE ${flyway:defaultSchema}.outbound_message ADD CONSTRAINT outbound_message_kind_check
    CHECK (kind IN ('explanation', 'detail', 'retake', 'nudge', 'failure'));
```

  Rerun: the first test is green, and the retake test now fails **on the defect**:
  `[a failure notice between two retakes must not reset the count] expected: 2L but was: 1L`. Then in
  `consecutiveRetakes` change `kind <> 'retake'` to `kind NOT IN ('retake', 'failure')` ("a failure notice is our fault,
  not a readable document, and must not reset the escalation").
- [ ] **Step 4:** `./gradlew :baymax:test --tests '*FailureKindAcceptanceTest' --tests '*DeliveryOutcomeAcceptanceTest' --tests '*FamilyDeletionAcceptanceTest'` green.
- [ ] **Step 5:** `feat(baymax): V23 outbound kind 'failure'; a failure notice does not reset retake escalation`.

### Task 9.2: FAILED, fail-closed and throwing explanations reply — code-only, value-free

**Files:** modify `ExplanationService.java` (`explain`, `composeChecked`; add `failureNotice`, `fallback`, `fallbackBody`),
`ExplainDocumentUseCase.java` (javadoc), `messages_bn/en.properties`; test `ExplanationServiceTest.java` (invert
`aBodyWithAForeignNumberIsRegeneratedOnceThenFailsClosed`, `:107-124`, which asserted `delivery, never()`).

- [ ] **Step 1: Copy** (bn | en): `failure.document` = `দুঃখিত — এই কাগজটা এখন পড়া গেল না। এটা আপনার ছবির দোষ নয়।\nএটা এ মাসের হিসাবে গোনা হবে না। কিছুক্ষণ পরে ছবিটা আবার পাঠান।` |
  `Sorry — this paper could not be read just now. It is not your photo's fault.\nIt will not count towards this month. Please send the photo again in a little while.`;
  `explanation.fallback` = `রিপোর্টটি পেয়েছি — {document_type}, {date}।\nকাগজে যা লেখা আছে, ছবিসহ এখানে: {link}` |
  `Received the report — {document_type}, {date}.\nWhat the paper says, with photos: {link}`.
- [ ] **Step 2: Write the failing tests:**

```java
private void failed() {
    when(documents.find(DOC)).thenReturn(Mono.just(new Document(DOC, PATIENT, FAMILY, null, null, null, null, null,
            Document.Status.FAILED, "llm_error", null, null, 1, NOW, NOW)));
}

private String link() {
    return properties.getOutbound().getPublicBaseUrl() + "/app/documents/" + DOC;
}

/** Stock-take wa-failure-reply (1): the run that FAILED on Anthropic credits sent nothing after the ack (prod, 2026-10-16). */
@Test
void aDocumentThatEndsFailedTellsTheFamily() {
    failed();
    service.explain(DOC).block();
    ArgumentCaptor<OutboundMessage> sent = ArgumentCaptor.forClass(OutboundMessage.class);
    verify(delivery).deliver(sent.capture());
    assertThat(sent.getValue().kind().name()).isEqualTo("FAILURE");
    assertThat(sent.getValue().body()).isEqualTo(new Copy().bn("failure.document", Map.of()));
    verify(metered, never()).invoke(any(), any(), any(LlmRequest.class));
}

/** The other direction: a detail request on a failed document must not send the notice again. */
@Test
void aDetailRequestOnAFailedDocumentSendsNothingNew() {
    failed();
    StepVerifier.create(service.detail(DOC)).verifyComplete();
    verify(delivery, never()).deliver(any());
}

/**
 * Inverted from aBodyWithAForeignNumberIsRegeneratedOnceThenFailsClosed, which asserted delivery never() (stock-take
 * wa-failure-reply (3)). Fail-closed still means the MODEL's text never goes: the attempt is stored with no body,
 * as before. What changes is that the family then gets the code-only fallback. Assertions strip the link, because
 * it carries a random UUID (CLAUDE.md: never substring-match over random ids).
 */
@Test
void aBodyThatFailsClosedIsStoredWithoutTextAndTheFamilyGetsTheCodeOnlyFallback() {
    done(CRITICAL);
    replies.push("HbA1c এসেছে 11.2 mmol। এখনই ডাক্তারের কাছে যান। বাকি সব 77।");
    replies.push("HbA1c এসেছে 11.2। এখনই ডাক্তারের কাছে যান। আরও 42 দিন।");

    service.explain(DOC).block();

    verify(metered, times(2)).invoke(eq(AiCallPurpose.EXPLAIN), any(), any(LlmRequest.class));
    ArgumentCaptor<OutboundMessage> sent = ArgumentCaptor.forClass(OutboundMessage.class);
    verify(delivery).deliver(sent.capture());
    ArgumentCaptor<OutboundMessage> saved = ArgumentCaptor.forClass(OutboundMessage.class);
    verify(messages, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
    assertThat(saved.getAllValues()).anySatisfy(m -> {
        assertThat(m.gateStatus()).isEqualTo(OutboundMessage.GateStatus.FAILED_SAFETY);
        assertThat(m.body()).isNull();
    });
    String body = sent.getValue().body();
    assertThat(body).contains(link());
    assertThat(body.replace(link(), "")).as("none of the rejected model text")
            .doesNotContain("77").doesNotContain("42").doesNotContain("mmol").doesNotContain("11.2");
    assertThat(body).contains(new Copy().bn("urgency.now", Map.of())).contains(new Copy().bn("safety.red_flag_line", Map.of()));
    assertThat(sent.getValue().urgency()).isEqualTo(Urgency.NOW);
}

/** Finding 3: an explanation that throws (here an elided dose, DR-16) still reaches the family as the fallback, without the medicine. */
@Test
void anExplanationThatThrowsStillReachesTheFamilyAsTheFallbackWithoutTheMedicine() {
    done(NORMAL);
    when(documents.medicinesOf(DOC)).thenReturn(Flux.just(Map.<String, Object>of(
            "name", "Cap. DDR", "frequency_text", "১+০+০ ... ১+০+১")));

    service.explain(DOC).onErrorResume(e -> Mono.empty()).block();   // pre-fix it errors; the defect is the silence

    ArgumentCaptor<OutboundMessage> sent = ArgumentCaptor.forClass(OutboundMessage.class);
    verify(delivery).deliver(sent.capture());
    assertThat(sent.getValue().body()).doesNotContain("DDR").doesNotContain("১+০+০").contains(link());
}

/** The fallback is code; derived over every level, it passes the same checklist as any template and fits the cap. */
@Test
void theFallbackPassesTheChecklistAtEveryUrgency() {
    done(NORMAL);
    Document d = documents.find(DOC).block();
    DocumentFacts facts = new DocumentFacts(d, "Ma", List.of(NORMAL), List.of(), Map.of(), link());
    for (Urgency level : Urgency.values()) {
        String body = service.fallbackBody(facts, level);
        assertThat(new MessageSafetyCheck(properties).violations(body, facts, level)).as(level.name()).isEmpty();
        assertThat(body.length()).as(level.name()).isLessThanOrEqualTo(properties.getOutbound().getMaxChars());
        assertThat(body.contains(new Copy().bn("urgency.now", Map.of()))).as(level.name()).isEqualTo(level == Urgency.NOW);
        assertThat(body.contains(new Copy().bn("urgency.this_week", Map.of()))).as(level.name()).isEqualTo(level == Urgency.THIS_WEEK);
    }
}
```

- [ ] **Step 3: Run (the replay).** `./gradlew :baymax:test --tests '*ExplanationServiceTest'`:
  - `aDocumentThatEndsFailedTellsTheFamily` → `Wanted but not invoked: messageDeliveryPort.deliver(<Capturing argument>)`
    (`composeChecked` returns empty for FAILED).
  - `aDetailRequestOnAFailedDocumentSendsNothingNew` → **green**; the safe direction, kept green.
  - `aBodyThatFailsClosed…` → `Wanted but not invoked: messageDeliveryPort.deliver(...)` (the FAILED_SAFETY row is saved
    and nothing is sent).
  - `anExplanationThatThrows…` → `Wanted but not invoked: messageDeliveryPort.deliver(...)`.
  - `theFallbackPassesTheChecklistAtEveryUrgency`: add `String fallbackBody(DocumentFacts f, Urgency l) { return ""; }`
    first, so the red is `[ROUTINE] Expecting empty but was: ["empty_body"]`.
- [ ] **Step 4: Implement against PR 5's split.**

```java
    @Override
    public Mono<OutboundMessage> explain(UUID documentId) {
        return composeChecked(documentId, OutboundMessage.Kind.EXPLANATION)
                // fail-closed still means the MODEL's text never goes: the attempt is stored with no body, as before,
                // and the family then gets the code-only fallback instead of silence
                .flatMap(m -> m.gateStatus() == OutboundMessage.GateStatus.FAILED_SAFETY && m.kind() == OutboundMessage.Kind.EXPLANATION
                        ? store(m).then(fallbackFor(documentId))
                        : release(m))
                // an explanation that cannot be sent safely still ends in a reply; the log carries the class only
                .onErrorResume(e -> !(e instanceof BaymaxException), e -> {
                    log.error("[baymax] explanation threw documentId={} error={} — sending the code-only fallback",
                            documentId, e.getClass().getSimpleName());
                    return fallbackFor(documentId);
                });
    }

    private Mono<OutboundMessage> fallbackFor(UUID documentId) {
        return documents.find(documentId)
                .filter(d -> d.status() == Document.Status.DONE)
                .flatMap(d -> facts(d).flatMap(f -> fallback(f, urgency.assess(f))));
    }

    /** The pipeline ended FAILED: the family was told "reading it" and hears the outcome once, never values. */
    private OutboundMessage failureNotice(Document document) {
        return new OutboundMessage(null, document.familyId(), document.patientId(), document.id(),
                OutboundMessage.Kind.FAILURE, Urgency.ROUTINE, List.of("document_failed"),
                copy.bn("failure.document", Map.of()), gate.decide(Urgency.ROUTINE), null, null, null, null, clock.instant());
    }

    /**
     * Code-only and value-free: the document type, its date, the link to the paper with its crops, led by the
     * UrgencyLines block at the level the code assessed. Released through the same gate as any explanation and checked
     * by the same checklist; a template that fails its own checklist is a bug, not a fallback. No "fallback" marker is
     * added to urgency_reasons: that column carries urgency codes only (the DETAIL cache and F1 read it).
     */
    private Mono<OutboundMessage> fallback(DocumentFacts facts, UrgencyAssessment assessed) {
        String body = fallbackBody(facts, assessed.level());
        List<String> violations = safety.violations(body, facts, assessed.level());
        if (!violations.isEmpty()) {
            return Mono.error(new IllegalStateException("fallback template violated its own checklist: " + violations));
        }
        log.info("[baymax] explanation fallback documentId={} urgency={}", facts.document().id(), assessed.level().dbValue());
        Document d = facts.document();
        return release(new OutboundMessage(null, d.familyId(), d.patientId(), d.id(), OutboundMessage.Kind.EXPLANATION,
                assessed.level(), assessed.reasons(), body, gate.decide(assessed.level()), null, null, null, null, clock.instant()));
    }

    String fallbackBody(DocumentFacts facts, Urgency level) {
        Document d = facts.document();
        Map<String, String> vars = Map.of(
                "document_type", copy.documentType(d.documentType()),
                "date", d.docDate() == null ? "" : BanglaDate.format(d.docDate(), LocalDate.now(clock)),
                "link", facts.link());
        return UrgencyLines.lead(copy, level, copy.bn("explanation.fallback", vars));
    }
```

  In `composeChecked`, add `case FAILED -> kind == OutboundMessage.Kind.EXPLANATION ? Mono.just(failureNotice(document)) : Mono.<OutboundMessage>empty();`.
  `ExplainDocumentUseCase.explain` javadoc: "a FAILED document yields a FAILURE notice; a DONE document never ends in
  silence". Also add `aFailedClosedNowUnderTheUrgencyGateParksTheFallbackNotDeliversIt` (gate=urgency, NOW: the fallback
  is saved PENDING, the reviewer is notified, nothing is delivered), so the gate-on direction is asserted too.
- [ ] **Step 5:** `./gradlew :baymax:test --tests 'com.elioo.baymax.outbound.*' --tests 'com.elioo.baymax.extraction.*' --tests 'com.elioo.baymax.wa.*'`
  green; stage the service, the use case, the bundles, the test;
  `fix(baymax): failed, fail-closed and throwing explanations reply to the family, code-only` (body: "Inverts
  aBodyWithAForeignNumberIsRegeneratedOnceThenFailsClosed, which asserted the silence.").

### Task 9.3: Every FAILED writer meets the notice — derived, and through recovery end to end

**Files:** create `baymax/src/test/java/com/elioo/baymax/architecture/FailedHasOneWriterTest.java`,
`baymax/src/test/java/com/elioo/baymax/extraction/RecoveredFailureIsToldOnceTest.java`; modify
`PostgresDocumentRecordAdapter.unexplainedSince`/`countUnexplainedBefore` (FAILED without a `failure` row joins the set);
test `DocumentRecoveryAcceptanceTest`.

- [ ] **Step 1: Write the tests.**

```java
/**
 * Every FAILED a document can reach is written by DocumentExtractionService (whose result DocumentProcessingChain.run
 * explains) or by DocumentProcessingChain.fail. Both lead to ExplanationService.explain, which answers it (PR 9). A new
 * writer anywhere else would end a document in silence: route it through the chain instead.
 */
class FailedHasOneWriterTest {

    private static final java.util.regex.Pattern WRITES_FAILED = java.util.regex.Pattern.compile(
            "withStatus\\(\\s*(Document\\.)?Status\\.FAILED|finish\\([^;]*?(Document\\.)?Status\\.FAILED|new Document\\([^;]*?Status\\.FAILED");

    @Test
    void documentFailedIsWrittenOnlyByTheExtractionAndTheProcessingChain() throws java.io.IOException {
        List<String> writers;
        try (var files = java.nio.file.Files.walk(java.nio.file.Path.of("src/main/java"))) {
            writers = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            return WRITES_FAILED.matcher(java.nio.file.Files.readString(p)).find();
                        } catch (java.io.IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .map(p -> p.getFileName().toString()).sorted().toList();
        }
        assertThat(writers).as("the scan found the two known writers (vacuity guard) and nothing else")
                .containsExactly("DocumentExtractionService.java", "DocumentProcessingChain.java");
    }
}
```

  `RecoveredFailureIsToldOnceTest` wires the **real** `DocumentRecoveryService`, the **real** `DocumentProcessingChain`
  and the **real** `ExplanationService` (constructed as in `DetailDeliveryGuardsTest`), over `InMemoryOutboundMessages`,
  a mocked `MessageDeliveryPort` and a mocked `DocumentRecordPort` whose `update` stores the document that `find` then
  returns. Two tests, both directions: (a) a PROCESSING document whose `markRecoveryAttempt` returns 3 → exactly one row
  of kind FAILURE with body `failure.document`, and `delivery.deliver` called exactly once; (b) a document older than
  `max-age` → `skipped_too_old`, zero rows, `delivery.deliver` never called (a paper from weeks ago never texts the family).
  In `DocumentRecoveryAcceptanceTest`, `aFailedDocumentWithoutANoticeIsUnexplainedAndOneWithANoticeIsNot`: two FAILED
  documents inside the window, one with an `outbound_message` row of kind `failure`; `unexplainedSince` returns only the
  other.
- [ ] **Step 2: Replay.** `FailedHasOneWriterTest`: temporarily put `documents.update(d.withStatus(Document.Status.FAILED,
  "x", clock.instant()))` back into `DocumentRecoveryService`; the test must fail listing
  `DocumentRecoveryService.java`. Restore. `RecoveredFailureIsToldOnceTest` (a) on Task 9.1's code (before Task 9.2) fails
  with `Expected size: 1 but was: 0` on the FAILURE rows: the interrupted document was failed in silence.
- [ ] **Step 3: Implement** the `unexplainedSince` / `countUnexplainedBefore` change:
  `… WHERE (d.status IN ('DONE','NEEDS_RETAKE') AND NOT EXISTS (… kind IN ('explanation','retake'))) OR (d.status = 'FAILED' AND NOT EXISTS (… kind = 'failure')) …`.
  `DocumentProcessingChain.explainOnly` already calls `explain`, which now answers FAILED.
- [ ] **Step 4–5:** `./gradlew build` green; stage the three tests and the adapter;
  `test(baymax): every FAILED writer meets the failure notice, through recovery end to end`.

### Task 9.4: Ship

- [ ] **Step 1:** the standard pre-merge (migration check: V22 is the latest on main). PR body: the three silent paths
  closed, the central rule and how it is asserted, the inverted test, the copy drafts, and the plain statement that the
  FAILED and FAILED_SAFETY replies were exercised locally and by Testcontainers only.
- [ ] **Step 2: Local run (the done for this PR; PR 9 stays unmerged until it is done).** With `docker compose up -d`
  and `.env.local`, start the app with the extraction model overridden to a non-existent id in a local-only property,
  upload `t1-routine-lab.jpg` on the web, and confirm:
  - a `[baymax] message delivered (log) … kind=FAILURE … chars=<n>` line, with `n` the length of `failure.document`.
    **No body is expected in any log**: `LogMessageDelivery` logs ids, kind, urgency and `chars=` only, by design —
    never add body logging to make this check pass;
  - against the Docker Postgres: `SELECT kind, body FROM baymax.outbound_message WHERE document_id = '<id>'` returns
    `failure` with the bundle text of `failure.document`;
  - for a forced fail-closed (a DONE document whose explanation fails the checklist twice), an
    `[baymax] explanation fallback … cause=failed_safety` line and, in the same table, a `failed_safety` row with no
    body followed by an `explanation` row with the fallback body.
  Restore the property.

**Try it on your phone (PR 9)** — production check is "nothing regressed":
- [ ] Re-run PR 6's table for "Hi", "details", a voice note and `t1-routine-lab.jpg`: each still gets exactly one reply.
- [ ] `docker logs medscribe-ai 2>&1 | grep -c 'explanation fallback'` stays 0 in normal use (it would show a real
  fail-closed).

**Post-deploy:** the standard check.

---

## PR 10 — C3: Graph v25.0, one allowlist rule, read receipts and typing behind a flag

> **2026-10-21 — built and merged ahead of PRs 7–9 (PR #96, branch `feat/baymax-graph-v25`).** v21.0 expires
> 2027-01-21, so the version could not wait for the B/C chain.
> - Task 10.1 was already on `main` as PR 12's first commit (#63).
> - `readReceipt` lives at the **top of `WaIntakeService.onInbound`**, bounded (`WaGraphClient.READ_RECEIPT_TIMEOUT`
>   2 s, `WaIntakeService.READ_RECEIPT_BOUND` 3 s), not inside PR 8's `handle`, which does not exist yet. **PR 8's
>   `handle` must call `onInbound` after its claim and must not add a second receipt**: a duplicate it deduplicates
>   never reaches `onInbound`, so it gets no second tick. Task 10.3's "`handle` becomes `readReceipt(in).then(…)`" below
>   is superseded.
> - **PR 7** (which modifies `WaGraphClient` and `WaMessagingPort` per its file map) rebases onto `markRead`.
> - Step 4's local check (`BAYMAX_WA_ENABLED=false … bootRun` shows no validator warning) is vacuous: with WhatsApp off
>   the validator bean does not exist. It is replaced by `WaConfigValidatorTest`'s log-capturing cases on a fixed date.
> - The post-deploy try is RUNBOOK "Graph API version": outbound first with the dashboard on v21.0 (including the
>   photo), then the dashboard webhook version, each with its own rollback.

Branch `feat/baymax-wa-graph-v25-read-receipts`, from merged PR 9. No migration.

The Graph version is pinned to `v21.0` (`application.properties:121`, `BaymaxProperties.java:157`). Per Meta's changelog
(https://developers.facebook.com/docs/graph-api/changelog/versions, fetched 2026-10-17; **re-fetch it when this PR is
written** and update the table with the fetch date) v21.0 expires **2027-01-21** and v25.0 on 2028-07-29. Meta's
typing-indicator page (https://developers.facebook.com/docs/whatsapp/cloud-api/typing-indicators) documents one
`POST /{phone-number-id}/messages` with `{"messaging_product":"whatsapp","status":"read","message_id":"<inbound wamid>","typing_indicator":{"type":"text"}}`:
it marks the message read and shows typing until our reply or 25 s, and Meta says to show it only when we will respond.
The allowlist check is written twice today (`WaIntakeService.java:135-140`, `WhatsAppMessageDelivery.java:58-63`); this
PR adds a third outbound path (read receipts) and makes it **one** rule first.

### Task 10.1: One allowlist rule, asserted once

**Files:** modify `config/BaymaxProperties.java` (`Wa`), `WaIntakeService.java` (`send`), `WhatsAppMessageDelivery.java`;
create `baymax/src/test/java/com/elioo/baymax/config/WaAllowlistTest.java`.

- [ ] **Step 1: Write the test:**

```java
package com.elioo.baymax.config;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/** The phase-1 allowlist, once, for every outbound path: intake replies, explanations, and login codes. */
class WaAllowlistTest {

    @Test
    void aListedNumberIsPermittedAndAnUnlistedOneIsNot() {
        BaymaxProperties.Wa wa = new BaymaxProperties().getWa();
        wa.setAllowlist(List.of(" +8801700000000 "));          // env values arrive with stray spaces
        assertThat(wa.permits("+8801700000000")).isTrue();
        assertThat(wa.permits("+8801700000001")).isFalse();
        assertThat(wa.permits("8801700000000")).as("only E.164 matches; callers normalise first").isFalse();
        assertThat(wa.permits(null)).isFalse();
    }

    /** Today's documented meaning, kept: an empty list is "no phase-1 restriction" (phase 5 runs without it). */
    @Test
    void anEmptyListPermitsEveryone() {
        assertThat(new BaymaxProperties().getWa().permits("+8801700000000")).isTrue();
    }
}
```

- [ ] **Step 2:** red by compile error (`cannot find symbol: method permits(String)`). A refactor of existing behaviour, not
  a guard; `WaIntakeServiceTest.aRecipientNotOnTheAllowlistIsRefusedAndNothingIsSent` and `WhatsAppMessageDeliveryTest`
  stay green.
- [ ] **Step 3:** add `public boolean permits(String e164) { return e164 != null && (allowlist.isEmpty() ||
  allowlist.stream().anyMatch(a -> a.trim().equals(e164))); }` to `Wa`; replace both inline checks with
  `properties.getWa().permits(number)`. Make `WhatsAppMessageDelivery.classify` `public static` (PR 12 reuses it).
- [ ] **Step 4–5:** green; `refactor(baymax): one allowlist rule for every WhatsApp outbound path`.

### Task 10.2: Graph version off v21.0, with an env rollback and an expiry warning

**Files:** modify `BaymaxProperties.Wa` (`graphVersion` default, `readReceipts`), `application.properties:121`,
`wa/config/WaConfigValidator.java`; test `WaConfigValidatorTest.java`.

- [ ] **Step 1: Write the failing tests:**

```java
    @Test
    void aMalformedGraphVersionIsRefusedAndNamed() {
        BaymaxProperties p = valid();
        p.getWa().setGraphVersion("21.0");
        assertThatThrownBy(() -> validate(p)).hasMessageContaining("BAYMAX_WA_GRAPH_VERSION");
    }

    @Test
    void anUnknownReadReceiptModeIsRefused() {
        BaymaxProperties p = valid();
        p.getWa().setReadReceipts("yes");
        assertThatThrownBy(() -> validate(p)).hasMessageContaining("BAYMAX_WA_READ_RECEIPTS");
    }

    /** Derived from the version in use, not a second property that can disagree with it. */
    @Test
    void theExpiryWarningFollowsTheConfiguredVersion() {
        assertThat(WaConfigValidator.graphVersionWarnings("v21.0", LocalDate.parse("2026-10-17")))
                .as("96 days before expiry: outside the 90-day window").isEmpty();
        assertThat(WaConfigValidator.graphVersionWarnings("v21.0", LocalDate.parse("2026-11-01")))
                .singleElement().asString().contains("expires 2027-01-21");   // 81 days: warned
        assertThat(WaConfigValidator.graphVersionWarnings("v25.0", LocalDate.parse("2026-10-17"))).isEmpty();
        assertThat(WaConfigValidator.graphVersionWarnings("v21.0", LocalDate.parse("2027-02-01")))
                .singleElement().asString().contains("expired");
        assertThat(WaConfigValidator.graphVersionWarnings("v99.0", LocalDate.parse("2026-10-17")))
                .singleElement().asString().contains("not in the expiry table");
    }
```

  (2026-10-17 is 96 days before 2027-01-21 and 2026-11-01 is 81 days before it; both sides of the 90-day window are
  asserted.)
- [ ] **Step 2:** red by compile error (`setReadReceipts`, `graphVersionWarnings`).
- [ ] **Step 3: Implement.** `BaymaxProperties.Wa`: `graphVersion = "v25.0"`; `private String readReceipts = "off";`
  (`off | read | typing`). `application.properties`: a comment line with the changelog URL, the fetch date and "v21.0
  expires 2027-01-21", then `baymax.wa.graph-version=${BAYMAX_WA_GRAPH_VERSION:v25.0}` and
  `baymax.wa.read-receipts=${BAYMAX_WA_READ_RECEIPTS:off}` (comments on their own lines: the docker `--env-file` lesson
  at `WaConfigValidator.java:16-20` applies to readers of this file too). `WaConfigValidator`:
  `static final Map<String, LocalDate> GRAPH_EXPIRY` (v21.0 2027-01-21, v22.0 2027-05-20, v23.0 2027-10-08, v24.0
  2028-02-18, v25.0 2028-07-29; source URL and fetch date in its javadoc), two `problems` (shape `v\d+\.\d+` naming
  `BAYMAX_WA_GRAPH_VERSION`; read-receipt mode naming `BAYMAX_WA_READ_RECEIPTS`), and
  `static List<String> graphVersionWarnings(String version, LocalDate today)` logged at WARN and never thrown: refusing to
  start over expiry would take MedScribe down too.
- [ ] **Step 4–5:** green; `feat(baymax): Graph API v25.0 (v21.0 expires 2027-01-21), rollback by env, expiry warned`.

### Task 10.3: Read ticks and typing, only for messages we answer, never with a wamid in the log

**Files:** modify `WaMessagingPort.java` (`markRead`, default no-op), `WaGraphClient.java` (`markRead`, `readPayload`),
`WaIntakeService.java` (`readReceipt` before `onInbound` inside PR 8's `handle`); tests `WaGraphClientTest.java`,
`WaIntakeServiceTest.java`.

- [ ] **Step 1: Write the failing tests.** In `WaGraphClientTest` (its existing `clientReturning` and `requestedUris`):

```java
    @Test
    void theReadPayloadMarksReadAndOnlyAddsTypingWhenAsked() {
        assertThat(WaGraphClient.readPayload(WaFixtures.wamid("8801700000000", 51), false))
                .isEqualTo(Map.of("messaging_product", "whatsapp", "status", "read", "message_id", WaFixtures.wamid("8801700000000", 51)));
        assertThat(WaGraphClient.readPayload(WaFixtures.wamid("8801700000000", 51), true))
                .containsEntry("typing_indicator", Map.of("type", "text"))
                .containsEntry("status", "read");
    }

    @Test
    void markReadPostsToTheMessagesEndpointOfThePinnedVersion() {
        WaGraphClient client = clientReturning("{\"success\":true}");
        client.markRead(WaFixtures.wamid("8801700000000", 51), true).block();
        assertThat(requestedUris).containsExactly("https://graph.facebook.com/v25.0/1412074765313019/messages");
    }

    /** A read receipt is a courtesy: its failure never fails the answer. */
    @Test
    void aFailedReadReceiptCompletesEmpty() {
        // build a client whose ExchangeFunction answers 400 (copy clientReturning, HttpStatus.BAD_REQUEST)
        StepVerifier.create(failingClient().markRead(WaFixtures.wamid("8801700000000", 51), false)).verifyComplete();
        assertThat(everythingLogged()).contains("wa read receipt failed").doesNotContain(BEARER)
                .doesNotContain(WaFixtures.wamid("8801700000000", 51)).doesNotContain("8801700000000");
    }
```

  In `WaIntakeServiceTest`, both directions (typed `WaInbound`, through `handle`):

```java
    @Test
    void withTypingOnAPhotoIsMarkedReadWithTypingBeforeTheWork() {
        props.getWa().setReadReceipts("typing");
        PatientProfile ma = patient("মা");
        familyWith(ma);
        when(intake.accept(eq(ma.id()), any())).thenReturn(Mono.just(mock(Document.class)));
        service.handle(new WaInbound(WaFixtures.wamid(FROM, 61), FROM, WaInbound.Type.IMAGE, "", "MEDIA-1", "image/jpeg")).block();
        InOrder order = inOrder(graph, intake);
        order.verify(graph).markRead(WaFixtures.wamid(FROM, 61), true);
        order.verify(intake).accept(eq(ma.id()), any());
    }

    @Test
    void withTypingAPlainTextGetsTheTickButNoDots() {          // a greeting is answered at once from a template: the tick, no dots
        props.getWa().setReadReceipts("typing");
        familyWith(patient("মা"));
        service.handle(new WaInbound(WaFixtures.wamid(FROM, 62), FROM, WaInbound.Type.TEXT, "hello", "", "")).block();
        verify(graph).markRead(WaFixtures.wamid(FROM, 62), false);
    }

    @Test
    void offMeansNoReadReceiptAtAll() {
        familyWith(patient("মা"));
        service.handle(new WaInbound(WaFixtures.wamid(FROM, 63), FROM, WaInbound.Type.TEXT, "বিস্তারিত", "", "")).block();
        verify(graph, never()).markRead(anyString(), anyBoolean());
    }

    @Test
    void aSenderOffTheAllowlistGetsNoReadReceipt() {
        props.getWa().setReadReceipts("typing");
        props.getWa().setAllowlist(List.of("+8801700000999"));
        familyWith(patient("মা"));
        service.handle(new WaInbound(WaFixtures.wamid(FROM, 64), FROM, WaInbound.Type.TEXT, "বিস্তারিত", "", "")).block();
        verify(graph, never()).markRead(anyString(), anyBoolean());
    }
```

- [ ] **Step 2: Run** → compile failures (`markRead`, `readPayload`); once compiling,
  `markReadPostsToTheMessagesEndpointOfThePinnedVersion` fails on `…v21.0/1412074765313019/messages` vs `v25.0` until the
  default changes (Task 10.2) — the stale pin.
- [ ] **Step 3: Implement.** `WaMessagingPort`:
  `default Mono<Void> markRead(String providerMessageId, boolean typing) { return Mono.empty(); }` (javadoc: marks an inbound
  message read; with typing, shows the indicator until our reply or 25 s; never errors). `WaGraphClient`:
  `static Map<String, Object> readPayload(String id, boolean typing)` and `markRead` POSTing to `graph() + "/" +
  phoneNumberId + "/messages"` with the bearer header, `.retrieve().toBodilessEntity()`; success logs
  `[baymax] wa read receipt sent ref={} typing={}` with `Redact.id(id)`; `.onErrorResume` logs
  `[baymax] wa read receipt failed ref={} typing={} status={}` (status from `WebClientResponseException#getStatusCode`,
  otherwise the class name) and completes empty. `WaIntakeService.readReceipt(WaInbound in)`: empty when the mode is
  `off`, when `!in.expectsReply()`, or when `!properties.getWa().permits(e164(in.from()))`; otherwise
  `graph.markRead(in.providerMessageId(), "typing".equals(mode) && takesTime(in))`, where `takesTime` is `IMAGE`, a
  readable `DOCUMENT`, or a text/button whose route is `DETAIL` (a model call or a download). `handle` becomes
  `readReceipt(in).then(onInbound(in))…` (PR 8's error and `markHandled` steps unchanged).
- [ ] **Step 4:** `./gradlew build` green. Locally: `set -a; source .env.local; set +a; BAYMAX_WA_ENABLED=false ./gradlew :medscribe-ai:bootRun`
  shows no validator warning for v25.0.
- [ ] **Step 5:** stage the properties, the validator, the port, the client, the service, the three tests, the runbook
  (three env lines: `BAYMAX_WA_GRAPH_VERSION` blank = v25.0, set `v21.0` only to roll back; `BAYMAX_WA_READ_RECEIPTS`
  off | read | typing; the log line `wa read receipt sent`);
  `feat(baymax): read receipts and typing behind BAYMAX_WA_READ_RECEIPTS`. The standard pre-merge.

**Try it on your phone (PR 10):**
- [ ] After the deploy (Graph v25.0 is now live for every send): "Hi" still gets its greeting; `t1-routine-lab.jpg` still
  gets the ack and the explanation. If either fails with a Graph error, Tanvir sets `BAYMAX_WA_GRAPH_VERSION=v21.0`
  (backup first), redeploys the same SHA, and the build report records it.
- [ ] Back up `~/medscribe.env`, set `BAYMAX_WA_READ_RECEIPTS=typing`, redeploy the same SHA
  (`IMAGE_TAG=<sha> bash ~/deploy.sh`). A photo gets blue ticks plus typing dots; "hello" gets blue ticks only. Leave it
  on or set it back; either way the build report lists the env change.

**Post-deploy:** the standard check, plus `docker logs medscribe-ai --since 15m 2>&1 | grep -c 'graph version'` shows no
expiry warning.

---

## PR 11 — 1a-i: every way a family writes its number; a code typed in Bangla digits

Branch `fix/baymax-number-and-bangla-code`, from merged PR 10. Phase 1a starts here. Two live bugs, shipped before the
sign-in flow changes so they can be tried on today's flow: `FamilyAccountService.normalizeNumber` (`:173-194`) refuses
`00<cc>…`, `+880 0…`, NBSP (U+00A0) and the en dash (U+2013), which phone keyboards and copy-paste produce; and
`WebAuthService.verify` checks `code.matches("\\d{6}")` (`:106`), and Java's `\d` is ASCII-only, so **a code typed on a
Bangla keyboard (Ridmik, Gboard Bangla) is refused as wrong**.

### Task 11.1: Every way of writing a Bangladeshi or foreign number

**Files:** modify `FamilyAccountService.java` (`normalizeNumber`); test `FamilyAccountServiceTest.java` (beside `:250-265`).

- [ ] **Step 1: Write the tests** (both directions: accept every real spelling, still refuse non-numbers):

```java
@org.junit.jupiter.params.ParameterizedTest
@org.junit.jupiter.params.provider.ValueSource(strings = {
        "01700000000", "8801700000000", "+8801700000000", "008801700000000",
        "+880 01700000000",                 // the trunk 0 kept after the country code, common on cards
        "০১৭০০০০০০০০", "০১৭০০-০০০০০০",
        "01700 000000",                // NBSP from a copy-paste
        "01700–000000",                // en dash from a phone keyboard
        "(+880) 1700-000000"})
void everyWayABangladeshiNumberIsWrittenNormalisesToOneE164(String typed) {
    assertThat(FamilyAccountService.normalizeNumber(typed)).isEqualTo("+8801700000000");
}

@Test
void foreignNumbersKeepTheirCountryCodeWithPlusOrDoubleZero() {
    assertThat(FamilyAccountService.normalizeNumber("+44 7700 900123")).isEqualTo("+447700900123");
    assertThat(FamilyAccountService.normalizeNumber("0044 7700 900123")).isEqualTo("+447700900123");
}

@org.junit.jupiter.params.ParameterizedTest
@org.junit.jupiter.params.provider.ValueSource(strings = {"1700000000", "017", "+1", "00", "০১৭০০", "abc", "+880 1700"})
void somethingThatIsNotANumberIsStillRefused(String typed) {
    assertThatThrownBy(() -> FamilyAccountService.normalizeNumber(typed)).isInstanceOf(IllegalArgumentException.class);
}

/** All-zero numbers cannot show a digit-order bug; this one folds every Bangla digit it contains, in place. */
@Test
void aBanglaNumberFoldsDigitByDigit() {
    assertThat(FamilyAccountService.normalizeNumber("০১৭০০০০১২৩৪")).isEqualTo("+8801700001234");
    assertThat(FamilyAccountService.normalizeNumber("০১৭০০ ০০৫৬৭৮")).isEqualTo("+8801700005678");
}
```

- [ ] **Step 2: Run.** `008801700000000`, `+880 01700000000`, NBSP and en dash throw
  `IllegalArgumentException: whatsapp_number must be E.164`; the other cases already pass. The red names the defect (the
  spellings are refused), so the Step 2 run is the replay.
- [ ] **Step 3:** the separator class becomes `[\\s\\p{Z}\\-\\u2010-\\u2015\\u2212()]`; after it, two rewrites in this order:
  `^00([1-9]\\d{7,14})$` → `+$1`, then `^\\+8800(1\\d{9})$` → `+880$1`; then the existing `01…` and `8801…` rules, then
  the E.164 check.
- [ ] **Step 4–5:** green; `fix(baymax): accept 00-prefixed, trunk-zero and NBSP/dash-separated numbers`.

### Task 11.2: A code typed in Bangla digits opens the session

**Files:** modify `web/application/service/WebAuthService.java` (`verify`, `:103-126`); test `WebAuthServiceTest.java`.

- [ ] **Step 1: Write the tests.** The guard uses Bangla digits **with no spaces**, so its red can only be `\d`; whitespace
  folding is a separate, new-behaviour test with ASCII digits:

```java
/** Regression guard: Java's \d is ASCII-only, so a code typed on a Bangla keyboard was refused as wrong (:106). */
@Test
void aCodeTypedInBanglaDigitsOpensTheSession() {
    String hash = service.requestCode("01711111111").block();
    String bangla = com.elioo.baymax.outbound.application.service.BanglaDate.digits(deliveredCode.get());
    StepVerifier.create(service.verify(hash, bangla)).expectNextCount(1).verifyComplete();
}

/** The other direction: folding must not turn a wrong code right. */
@Test
void aBanglaCodeThatFoldsToTheWrongDigitsIsStillWrong() {
    String hash = service.requestCode("01711111111").block();
    StepVerifier.create(service.verify(hash, com.elioo.baymax.outbound.application.service.BanglaDate.digits(wrong())))
            .expectError(BaymaxException.class).verify();
}

/** New behaviour (not a replay): spaces from a paste are folded away. ASCII digits, so it says nothing about \d. */
@Test
void aCodeWithSurroundingSpacesOpensTheSession() {
    String hash = service.requestCode("01711111111").block();
    StepVerifier.create(service.verify(hash, " " + deliveredCode.get() + " ")).expectNextCount(1).verifyComplete();
}
```

- [ ] **Step 2: Run (the replay).** `aCodeTypedInBanglaDigitsOpensTheSession` →
  `expectation "expectNextCount(1)" failed (expected: count = 1; actual: counted = 0; signal: onError(com.elioo.baymax.common.error.BaymaxException: the code is wrong, expired or used))`.
  `aBanglaCodeThatFoldsToTheWrongDigitsIsStillWrong` is green and stays green. The spaces test is red too (no trim), and
  is labelled new behaviour in the PR.
- [ ] **Step 3: Implement.** `String code = fold(raw)`, where `fold` strips `\s` and `\p{Z}` and maps each
  `Character.isDigit(c)` to `(char) ('0' + Character.digit(c, 10))`; then `code.matches("[0-9]{6}")`. The rest of
  `verify` is unchanged (PR 12 types its refusals).
- [ ] **Step 4–5:** green; `fix(baymax): a login code typed in Bangla digits is accepted`. The standard pre-merge.

**Try it on your phone (PR 11):**
- [ ] Open medioo.eliooo.org/app and type the number as `+880 0 1…` or with a space copied from a contact card: it is
  accepted.
- [ ] Request a code as today; Tanvir reads it from the log on the box
  (`docker logs medscribe-ai --since 5m 2>&1 | grep 'OTP for' | tail -1`, the last time this is needed), switches the
  phone keyboard to Bangla, types the code in Bangla digits, and lands on Ma's page.

**Post-deploy:** the standard check.

---

## PR 12 — 1a-ii: inbound-first sign-in (D2)

> **Amended by the PO, 2026-10-18 (Tanvir): sign-in must be free.** This section's tasks were built as written except
> where this note says otherwise; DR-39 records the decision, spec D2 the row.
> - **The web sends the code automatically when it is free to.** If the typed number (i) has a Medioo account, (ii) is
>   permitted by the allowlist (`Wa.permits`, Task 10.1, brought forward as PR 12's first commit), and (iii) wrote to
>   Medioo on WhatsApp within `baymax.wa.service-window` (default `PT23H`, a margin inside WhatsApp's free 24 h window),
>   `requestCode` mints and sends the code to that WhatsApp as a free-form session message. The code page says
>   "পাঠানো হয়েছে" **only** when the Graph API accepted the send (`CodeIssue.SENT`, carried as the login cookie's `.s`
>   mark); on any failure and outside the window it shows the one-tap button (`wa.me/<BAYMAX_WA_PUBLIC_NUMBER>?text=কোড`).
>   The plan's "the web mints nothing" and "the page never contains পাঠিয়েছি/পাঠানো হয়েছে" are replaced by this rule;
>   `login.code.sent` is the one login key allowed to say it, and `SignInPagesTest` derives that from the bundles.
> - **Otherwise the button, and "কোড" on WhatsApp** (Tasks 12.3, 12.6 as written): the reply is inside the window the
>   family's message just opened, so it is free too.
> - **Never a template** (authentication, utility or marketing) for codes: they are paid. No code in any log above
>   DEBUG; `LogOtpDelivery`'s INFO code line is gone (Task 12.2).
> - **Never an open sender:** no account, nothing minted (the web request no longer mints for unknown numbers). The
>   hourly cap applies to both paths and counts **every code made** in the rolling hour, whatever became of it
>   (`OtpStorePort.countCreatedSince` replaces `countLive`, which the review found let five wrong guesses buy another
>   send without limit): 5 codes x 5 tries per number per hour. DR-23: no number or code in a query string.
> - **New: V17 `wa_last_inbound`** (one row per number, keyed by the PhoneHasher HMAC — a CHECK refuses anything else —
>   with a forward-only `last_inbound_at` and a `family_id` FK without cascade). `WaIntakeService.onInbound` records it,
>   through `WebAuthUseCase.noteInbound`, for every message the family sends; `deleteFamily` removes it and
>   `FamilyDeletionAcceptanceTest`'s schema-derived sweep covers it. No existing table could stand in: nothing stored
>   records an inbound text message (documents exist only for photos and files; PR 8's `inbound_message` is unmerged).
>   PRs 8 and 9 move to V18 and V19; Phase 1b to V20 and V21 (roadmap allocation table).
> - **Review fixes, same PR (2026-10-19):** the window is recorded at Meta's send `timestamp` (clamped to now) through
>   `noteInbound(number, sentAt)`, never the receipt time, so a late-retried callback cannot open a closed window;
>   `WaGraphClient.sendText` errors on a 200 with no message id (it emitted `""`, and every caller's "accepted" branch
>   ran); `deleteFamily` locks the family row first (`SELECT … FOR UPDATE`), so a message arriving mid-delete cannot
>   fail it on the `wa_last_inbound` FK; the login cookie lives 30 minutes (`WebUiHandler.LOGIN_COOKIE_TTL`) for the
>   button path's round trip.
> - Built on `feat/baymax-whatsapp-sign-in` from `origin/main` at PR 11, ahead of PRs 7–10: the `WaIntakeService`
>   constructor is `…, properties, auth` until PRs 7–8 land (they append before `auth` and update the call sites).

Branch `feat/baymax-whatsapp-sign-in` (planned as `feat/baymax-sign-in-whatsapp-code`), from merged PR 11. ~~After this PR the web mints no codes: the family texts
"কোড" on WhatsApp and the code comes back there.~~ *Superseded 2026-10-18 (PO, note above): the web mints and sends
when the number has an account, is allowlisted and wrote inside the window; otherwise the family texts "কোড".* That is why the WhatsApp half and the web half ship in one PR, and why
the public number goes into the env **before** the merge.

### Task 12.0: Tanvir sets the public number first (before the merge, no code)

- [ ] **Step 1:** back up `~/medscribe.env`; add `BAYMAX_WA_PUBLIC_NUMBER=<the display number of BAYMAX_WA_PHONE_NUMBER_ID,
  digits only, e.g. 8801…>`. The running code ignores the unknown variable.
- [ ] **Step 2: Check its shape exactly** (the validator refuses a malformed value at startup, and by then `deploy.sh` has
  already `docker rm -f`'d the old container, so a bad value takes MedScribe down too):
  `grep -n '^BAYMAX_WA_PUBLIC_NUMBER=' ~/medscribe.env | cat -A` prints one line ending `=8801…$`: digits only, no
  quotes, no spaces, no `^M`, no `#`.
- [ ] **Step 3:** list the change in the build report. Only then merge PR 12.

### Task 12.1: `issueCodeFor`, ~~a web that mints nothing~~ (superseded 2026-10-18: the web mints inside the window, note above), typed refusals

**Files:** modify `WebAuthUseCase.java`, `OtpStorePort.java`, `PostgresOtpStoreAdapter.java`, `WebAuthService.java`,
`BaymaxProperties.Auth` (+`webMintsCode`, −`otpDelivery`); create `web/domain/CodeIssue.java` (`ISSUED, RATE_LIMITED,
NO_ACCOUNT`), `web/domain/VerifyFailure.java` (`FORMAT, WRONG, NO_CODE, EXPIRED, BURNED`),
`web/application/port/in/CodeRejectedException.java`; tests `WebAuthServiceTest.java`, `WebPersistenceAdaptersTest.java`.

- [ ] **Step 1: Write the tests.** In `WebAuthServiceTest`, change the `latestLive` stub (`:68`) to use the instant it is
  passed, `.filter(c -> c.isLive(i.getArgument(1)))`; add
  `when(otps.latestSince(anyString(), any())).thenAnswer(i -> Mono.justOrEmpty(row.get()).filter(c -> !c.createdAt().isBefore(i.getArgument(1))))`
  and a `serviceAt(Instant)` helper (a second `WebAuthService` over the same mocks with `Clock.fixed(t, UTC)`). The
  minting tests move from `requestCode` to `issueCodeFor(NUMBER)`; the tests at `:128-150` keep `requestCode` and set
  `properties.getAuth().setWebMintsCode(true)` first. PR 11's Bangla tests switch to `issueCodeFor` the same way.

```java
/** D2: the code is minted for the sender WhatsApp has proved, and opens that sender's family. */
@Test
void aCodeAskedForOnWhatsappOpensASessionForTheSendersFamily() {
    StepVerifier.create(service.issueCodeFor(NUMBER)).expectNext(CodeIssue.ISSUED).verifyComplete();
    verify(delivery).deliver(eq(NUMBER), anyString(), anyString());
    StepVerifier.create(service.verify(service.hash("01711111111"), deliveredCode.get()))
            .assertNext(issued -> assertThat(issued.session().familyId()).isEqualTo(FAMILY))
            .verifyComplete();
}

/** The join between the channels: Meta's sender id and every typed spelling hash alike — and no other number does. */
@Test
void theSenderAndEveryTypedFormOfTheNumberShareOneHashAndANeighbourDoesNot() {
    String fromWhatsapp = service.hash("+8801711111111");
    for (String typed : java.util.List.of("01711111111", "+880 1711-111111", "০১৭১১১১১১১১", "008801711111111")) {
        assertThat(service.hash(typed)).as(typed).isEqualTo(fromWhatsapp);
    }
    assertThat(service.hash("01711111112")).isNotEqualTo(fromWhatsapp);
}

@Test
void onlyTheHashOfTheCodeIsStoredAndItNamesTheFamily() {
    service.issueCodeFor(NUMBER).block();
    ArgumentCaptor<OtpCode> stored = ArgumentCaptor.forClass(OtpCode.class);
    verify(otps).create(stored.capture());
    assertThat(stored.getValue().codeHash()).isEqualTo(PhoneHasher.sha256(deliveredCode.get()))
            .isNotEqualTo(deliveredCode.get());
    assertThat(stored.getValue().familyId()).isEqualTo(FAMILY);
}

@Test
void aSenderWithNoAccountMintsNothing() {
    when(records.findFamilyByWhatsapp("+8801722222222")).thenReturn(Mono.empty());
    StepVerifier.create(service.issueCodeFor("+8801722222222")).expectNext(CodeIssue.NO_ACCOUNT).verifyComplete();
    verify(otps, never()).create(any());
    verify(delivery, never()).deliver(anyString(), anyString(), anyString());
}

@Test
void theFifthLiveCodeIsTheLastAndTheSixthIsRefusedAsRateLimited() {
    when(otps.countLive(anyString(), any(), any())).thenReturn(Mono.just(4L));
    StepVerifier.create(service.issueCodeFor(NUMBER)).expectNext(CodeIssue.ISSUED).verifyComplete();
    when(otps.countLive(anyString(), any(), any())).thenReturn(Mono.just(5L));
    StepVerifier.create(service.issueCodeFor(NUMBER)).expectNext(CodeIssue.RATE_LIMITED).verifyComplete();
    verify(otps, org.mockito.Mockito.times(1)).create(any());
}

/** D2/D36: in production the web form computes the hash and sends nothing. */
@Test
void theWebFormMintsNothingUnlessADeveloperSwitchesItOn() {
    StepVerifier.create(service.requestCode("01711111111")).expectNext(service.hash(NUMBER)).verifyComplete();
    verify(otps, never()).create(any());
    verify(delivery, never()).deliver(anyString(), anyString(), anyString());

    properties.getAuth().setWebMintsCode(true);
    service.requestCode("01711111111").block();
    verify(otps).create(any());
}

@Test
void fourWrongGuessesAreWrongTheFifthBurnsAndABurnedCodeStaysBurned() {
    service.issueCodeFor(NUMBER).block();
    String hash = service.hash(NUMBER);
    for (int i = 1; i <= 4; i++) {
        StepVerifier.create(service.verify(hash, wrong())).expectErrorSatisfies(e -> rejected(e, VerifyFailure.WRONG)).verify();
    }
    StepVerifier.create(service.verify(hash, wrong())).expectErrorSatisfies(e -> rejected(e, VerifyFailure.BURNED)).verify();
    StepVerifier.create(service.verify(hash, deliveredCode.get())).expectErrorSatisfies(e -> rejected(e, VerifyFailure.BURNED)).verify();
}

@Test
void aCodeTypedAfterItsTenMinutesIsExpiredNotWrong() {
    service.issueCodeFor(NUMBER).block();
    StepVerifier.create(serviceAt(NOW.plus(java.time.Duration.ofMinutes(11))).verify(service.hash(NUMBER), deliveredCode.get()))
            .expectErrorSatisfies(e -> rejected(e, VerifyFailure.EXPIRED)).verify();
}

/** The other direction: "expired" is never said about a number that has no recent code. */
@Test
void aNumberWithNoCodeInTheLastHourIsNoCodeNeverExpired() {
    StepVerifier.create(service.verify(service.hash("+8801733333333"), "123456"))
            .expectErrorSatisfies(e -> rejected(e, VerifyFailure.NO_CODE)).verify();
    service.issueCodeFor(NUMBER).block();
    StepVerifier.create(serviceAt(NOW.plus(java.time.Duration.ofHours(2))).verify(service.hash(NUMBER), deliveredCode.get()))
            .expectErrorSatisfies(e -> rejected(e, VerifyFailure.NO_CODE)).verify();
}

/** The web may say which; the API sees one shape for every failure (WebAuthService's identical-response contract). */
private static void rejected(Throwable e, VerifyFailure expected) {
    assertThat(e).isInstanceOf(CodeRejectedException.class);
    assertThat(((CodeRejectedException) e).failure()).isEqualTo(expected);
    assertThat(((BaymaxException) e).status().value()).isEqualTo(401);
    assertThat(((BaymaxException) e).reason()).isEqualTo("invalid_code");
    assertThat(e.getMessage()).isEqualTo("the code is wrong, expired or used");
}
```

  In `WebPersistenceAdaptersTest` (the insert carries the `family_id` foreign key, V8, so a real database):

```java
@Test
void theNewestCodeIsFoundWhateverItsStateButOnlyInsideTheWindowAndTheFamilyKeyIsEnforced() {
    runner().run(context -> {
        OtpStorePort otps = context.getBean(OtpStorePort.class);
        HealthRecordPort records = context.getBean(HealthRecordPort.class);
        Instant now = Instant.parse("2026-10-17T10:00:00Z");
        FamilyAccount f = records.createFamily(new FamilyAccount(null, "+8801755555555", "F", FamilyAccount.Plan.FREE, now, now)).block();
        otps.create(new OtpCode(null, "hash-latest", "h1", f.id(), now.minusSeconds(6600), 0, null, null, now.minusSeconds(7200))).block();
        OtpCode newer = otps.create(new OtpCode(null, "hash-latest", "h2", f.id(), now.minusSeconds(60), 0, null, null, now.minusSeconds(660))).block();

        assertThat(otps.latestSince("hash-latest", now.minusSeconds(3600)).block().id()).isEqualTo(newer.id());
        StepVerifier.create(otps.latestLive("hash-latest", now)).verifyComplete();          // expired, so not live
        otps.recordAttempt(newer.id(), 5, now.minusSeconds(30)).block();
        assertThat(otps.latestSince("hash-latest", now.minusSeconds(3600)).block().burnedAt()).isNotNull();
        StepVerifier.create(otps.latestSince("hash-latest", now.minusSeconds(300))).verifyComplete();   // outside the window
        StepVerifier.create(otps.latestSince("hash-other", now.minusSeconds(3600))).verifyComplete();

        StepVerifier.create(otps.create(new OtpCode(null, "hash-x", "h3", UUID.randomUUID(), now.plusSeconds(600), 0, null, null, now)))
                .expectErrorMatches(e -> e.getMessage().contains("violates foreign key constraint"))
                .verify();
    });
}
```

- [ ] **Step 2: Run.** It does not compile until `issueCodeFor`, `latestSince`, `CodeIssue`, `VerifyFailure`,
  `CodeRejectedException` and `setWebMintsCode` exist. These are **new-feature tests, not replays** (the replayed guards
  of this PR are in Tasks 12.2 and 12.5): add the types, then each test must fail on its own rule, e.g.
  `theWebFormMintsNothing…` → `Wanted no interactions … otps.create(...)` (today's `requestCode` mints), and
  `aCodeTypedAfter…` → `expected VerifyFailure.EXPIRED` against an error that is not a `CodeRejectedException`. A test
  that dies on an `UnsupportedOperationException` stub has not reached its rule: implement the stub minimally first.
- [ ] **Step 3: Implement.**

  - `WebAuthUseCase`: add `Mono<CodeIssue> issueCodeFor(String verifiedSenderNumber)`, with javadoc: "only for a
    number the channel itself has verified (the WhatsApp sender id); never for a typed number".
  - `CodeRejectedException extends BaymaxException`: `super(UNAUTHORIZED, "invalid_code", "the code is wrong, expired
    or used")` plus a `VerifyFailure failure()` field. Delete `invalid()` (`:173-175`).
  - `OtpStorePort.latestSince(String phoneHash, Instant since)`. Postgres:
    `SELECT COLUMNS FROM T WHERE phone_hash = :phone AND created_at >= :since ORDER BY created_at DESC LIMIT 1`
    (the existing index `otp_code_phone_created_idx` covers it).
  - `WebAuthService`: extract the minting at `:77-84` into `private Mono<Void> mint(String number, String phoneHash,
    UUID familyIdOrNull, Instant now)`. `issueCodeFor` runs in `Mono.defer`, so a missing HMAC secret becomes an
    error signal and is not thrown at assembly. Its steps are normalise → hash → `audit OTP_REQUEST` →
    `findFamilyByWhatsapp`. Empty → `NO_ACCOUNT`, with nothing minted. Otherwise ~~`countLive ≥ max`~~ `countCreatedSince(hash, now − 1h) ≥ max`
    (superseded 2026-10-19: every code made counts, whatever its state) → `RATE_LIMITED`, with an INFO log carrying
    `phoneHash` and the count. Otherwise `mint(...).thenReturn(ISSUED)`.
    `requestCode` keeps its normalise/hash, and its 200-for-anything contract. When `!cfg.isWebMintsCode()` it
    returns `Mono.just(phoneHash)` with no audit, because no code was requested. Otherwise it behaves as today.
  - `verify`: keep PR 11's `fold`, then check `code.matches("[0-9]{6}")`, else `FORMAT`. An empty `latestLive`
    becomes `latestSince(hash, now − 1h)`, mapped `burnedAt != null → BURNED`, `consumedAt != null → NO_CODE`,
    `else → EXPIRED`, with `defaultIfEmpty(NO_CODE)`. A wrong guess gives `burn ? BURNED : WRONG`. A right code with
    no family gives `WRONG`. Audit kinds stay as they are (`OTP_BURNED` / `OTP_VERIFY_FAIL`).
  - `BaymaxProperties.Auth`: delete `otpDelivery` (`:114-115`) and add `private boolean webMintsCode = false;`, with
    javadoc saying it is local development only and refused at startup with WhatsApp on (Task 12.4).

- [ ] **Step 4:** `./gradlew :baymax:test --tests '*WebAuthServiceTest' --tests '*WebPersistenceAdaptersTest'` (Docker running) green.
- [ ] **Step 5:** `feat(baymax): issueCodeFor(sender), web mints nothing, typed code refusals`.

### Task 12.2: Composite OTP delivery — WhatsApp first, a log fallback with no code above DEBUG, no wamid anywhere

**Files:** modify `LogOtpDelivery.java`, `OtpDeliveryPort.java` (javadoc), `messages_bn/en.properties` (`wa.login_code`),
`application.properties` (delete `:124`, rewrite `:110-113`); create `CompositeOtpDelivery.java` and the three tests under
`baymax/src/test/java/com/elioo/baymax/web/adapter/out/delivery/`.

- [ ] **Step 1: Write the tests.** First the guard that must replay red on the pre-fix `LogOtpDelivery` (it compiles there
  unchanged, using only `new LogOtpDelivery()` and the three-argument `deliver`):

```java
package com.elioo.baymax.web.adapter.out.delivery;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/** The fallback may say a code was not delivered; at INFO or above it may never say what the code was. */
class LogOtpDeliveryTest {

    private static final String CODE = "483920";
    private ListAppender<ILoggingEvent> logged;
    private Logger logger;

    @BeforeEach
    void capture() {
        logger = (Logger) LoggerFactory.getLogger(LogOtpDelivery.class);
        logged = new ListAppender<>();
        logged.start();
        logger.addAppender(logged);
        logger.setLevel(Level.TRACE);
    }

    @AfterEach
    void release() {
        logger.detachAppender(logged);
        logger.setLevel(null);
    }

    @Test
    void theFallbackLineAtInfoOrAboveNeverCarriesTheCode() {
        new LogOtpDelivery().deliver("+8801700000000", "b".repeat(64), CODE).block();

        assertThat(logged.list).as("silence is not 'no leak': the fallback must say something at INFO or above")
                .anySatisfy(e -> assertThat(e.getLevel().isGreaterOrEqual(Level.INFO)).isTrue());
        for (ILoggingEvent e : logged.list) {
            if (e.getLevel().isGreaterOrEqual(Level.INFO)) {
                // tokens, not a substring: the code must not BE a field, wherever it sits in the line
                assertThat(e.getFormattedMessage().split("[^0-9A-Za-z]+"))
                        .as("%s %s", e.getLevel(), e.getFormattedMessage()).doesNotContain(CODE);
            }
        }
    }
}
```

  The wiring guard, against whatever bean the delivery package produces (on the pre-fix code `LogOtpDelivery`,
  `matchIfMissing=true`), failing on the log and not on a missing class:

```java
package com.elioo.baymax.web.adapter.out.delivery;

// imports as above, plus ApplicationContextRunner, @Configuration, @ComponentScan, @Bean, BaymaxProperties, Copy,
// WaMessagingPort, OtpDeliveryPort, Mono, Mockito static mock/when/verify, ArgumentMatchers anyString/eq/contains

/** Whatever OtpDeliveryPort the app wires: given a reachable WhatsApp, the code goes there and to no log line. */
class OtpDeliveryWiringTest {

    static final String NUMBER = "+8801700000000";

    @Configuration
    @ComponentScan("com.elioo.baymax.web.adapter.out.delivery")
    static class Slice {
        @Bean BaymaxProperties baymaxProperties() {
            BaymaxProperties p = new BaymaxProperties();
            p.getWa().setAllowlist(java.util.List.of(NUMBER));
            return p;
        }
        @Bean com.elioo.baymax.outbound.application.service.Copy copy() { return new com.elioo.baymax.outbound.application.service.Copy(); }
        @Bean WaMessagingPort wa() {
            WaMessagingPort wa = mock(WaMessagingPort.class);
            when(wa.sendText(anyString(), anyString())).thenReturn(Mono.just(com.elioo.baymax.wa.WaFixtures.wamid("8801700000000")));
            return wa;
        }
    }

    @Test
    void theWiredDeliverySendsOverWhatsappAndLogsTheCodeNowhere() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Level before = root.getLevel();
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        root.addAppender(logged);
        root.setLevel(Level.TRACE);
        try {
            new ApplicationContextRunner().withUserConfiguration(Slice.class).run(ctx -> {
                assertThat(ctx).hasSingleBean(OtpDeliveryPort.class);
                ctx.getBean(OtpDeliveryPort.class).deliver(NUMBER, "a".repeat(64), "483920").block();
                // the log assertion comes FIRST, so a replay on the pre-fix code fails on the leak, not on "wanted but not invoked"
                for (ILoggingEvent e : logged.list) {
                    assertThat(e.getFormattedMessage().split("[^0-9A-Za-z]+"))
                            .as("%s %s", e.getLevel(), e.getFormattedMessage()).doesNotContain("483920");
                }
                verify(ctx.getBean(WaMessagingPort.class)).sendText(eq(NUMBER), contains("483920"));
            });
        } finally {
            root.detachAppender(logged);
            root.setLevel(before);
        }
    }
}
```

  The composite's unit tests. A wamid is matched as a **substring** (the token split used for the code would cut a wamid
  at its `.`, `+` and `/` and pass vacuously):

```java
class CompositeOtpDeliveryTest {

    private static final String NUMBER = "+8801700000000";
    private static final String HASH = "a".repeat(64);
    private static final String CODE = "483920";
    private static final String WAMID = com.elioo.baymax.wa.WaFixtures.wamid("8801700000000", 71);
    private final WaMessagingPort wa = mock(WaMessagingPort.class);
    private final BaymaxProperties props = new BaymaxProperties();
    private ListAppender<ILoggingEvent> logged;
    private Logger root;
    private Level before;

    @BeforeEach
    void capture() {
        props.getWa().setAllowlist(List.of(NUMBER));
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        before = root.getLevel();
        logged = new ListAppender<>();
        logged.start();
        root.addAppender(logged);
        root.setLevel(Level.TRACE);
    }

    @AfterEach
    void release() {
        root.detachAppender(logged);
        root.setLevel(before);
    }

    private CompositeOtpDelivery delivery(WaMessagingPort port) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        if (port != null) {
            beans.registerSingleton("wa", port);
        }
        return new CompositeOtpDelivery(beans.getBeanProvider(WaMessagingPort.class), new Copy(), props);
    }

    private void noLineAtOrAbove(Level floor, String token) {
        for (ILoggingEvent e : logged.list) {
            if (e.getLevel().isGreaterOrEqual(floor)) {
                assertThat(e.getFormattedMessage().split("[^0-9A-Za-z+]+")).as("%s %s", e.getLevel(), e.getFormattedMessage())
                        .doesNotContain(token);
            }
        }
    }

    @Test
    void aSuccessfulWhatsappDeliveryWritesTheCodeAndTheNumberToNoLogAtAnyLevel() {
        when(wa.sendText(eq(NUMBER), anyString())).thenReturn(Mono.just(WAMID));
        StepVerifier.create(delivery(wa).deliver(NUMBER, HASH, CODE)).verifyComplete();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(wa).sendText(eq(NUMBER), body.capture());
        assertThat(body.getValue()).contains(CODE).contains("এই কোড কাউকে দেবেন না").contains("Medioo কখনো ফোন করে কোড চায় না");
        assertThat(body.getValue().length()).as("the cap, on the text actually sent").isLessThanOrEqualTo(props.getOutbound().getMaxChars());
        assertThat(logged.list).as("the success line exists, or 'nothing leaked' passes on an empty log")
                .anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("otp delivered channel=whatsapp"));
        noLineAtOrAbove(Level.TRACE, CODE);
        noLineAtOrAbove(Level.TRACE, NUMBER);
        noLineAtOrAbove(Level.TRACE, "8801700000000");
        // a wamid is the number in base64: matched as a substring, because the token split above would cut it apart
        assertThat(logged.list.stream().map(ILoggingEvent::getFormattedMessage))
                .noneMatch(m -> m.contains(WAMID.substring("wamid.".length(), "wamid.".length() + 16)));
    }

    @Test
    void aFailedSendFallsBackWithoutTheCodeAtInfoAndKeepsItAtDebugForBreakGlass() {
        when(wa.sendText(anyString(), anyString())).thenReturn(Mono.error(new RuntimeException("(#131047) Re-engagement message")));
        StepVerifier.create(delivery(wa).deliver(NUMBER, HASH, CODE)).verifyComplete();   // never an error upstream

        noLineAtOrAbove(Level.INFO, CODE);
        assertThat(logged.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("reason=window_expired").contains(HASH);
        });
        assertThat(logged.list).as("the other direction: the operator can still recover it at DEBUG")
                .anySatisfy(e -> {
                    assertThat(e.getLevel()).isEqualTo(Level.DEBUG);
                    assertThat(e.getFormattedMessage().split("[^0-9A-Za-z]+")).contains(CODE);
                });
    }

    @Test
    void aSenderOffTheAllowlistIsNeverMessaged() {
        props.getWa().setAllowlist(List.of("+8801700000999"));
        StepVerifier.create(delivery(wa).deliver(NUMBER, HASH, CODE)).verifyComplete();
        verify(wa, never()).sendText(anyString(), anyString());
        assertThat(logged.list).anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("reason=not_allowlisted"));
        noLineAtOrAbove(Level.INFO, CODE);
    }

    @Test
    void withTheChannelOffTheCodeGoesNowhereAboveDebug() {
        StepVerifier.create(delivery(null).deliver(NUMBER, HASH, CODE)).verifyComplete();
        assertThat(logged.list).anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("reason=channel_off"));
        noLineAtOrAbove(Level.INFO, CODE);
    }
}
```

- [ ] **Step 2: Replay red ON the defect.** On the pre-fix `LogOtpDelivery`,
  `./gradlew :baymax:test --tests '*LogOtpDeliveryTest' --tests '*OtpDeliveryWiringTest'` must fail on the INFO line
  itself: `[INFO [baymax] OTP for phoneHash=bbbb… code=483920 (log delivery, BMX-5 v1)] Expecting actual: [..., "code", "483920", ...] not to contain: ["483920"]`.
  A context that does not start, or a missing `Copy` bean, has not reached the defect: fix the slice and replay again.
  `CompositeOtpDeliveryTest` does not compile on the pre-fix code (a unit test of new code).
- [ ] **Step 3: Implement.**
  - `LogOtpDelivery`: drop `@Component` and `@ConditionalOnProperty`, keep `implements OtpDeliveryPort`. The three-argument
    `deliver(n, h, c)` delegates to `deliver(n, h, c, "channel_off")`; the four-argument one does
    `log.warn("[baymax] otp NOT delivered phoneHash={} reason={} — ask again on WhatsApp; code at DEBUG only", h, reason)`
    then `log.debug("[baymax] otp fallback phoneHash={} code={}", h, c)`. Its javadoc names the break-glass logger.
  - `CompositeOtpDelivery` (`@Slf4j @Component @RequiredArgsConstructor`; `ObjectProvider<WaMessagingPort> whatsapp`,
    `Copy copy`, `BaymaxProperties properties`, and a `new LogOtpDelivery()` fallback): no bean → fallback
    `channel_off`; `!properties.getWa().permits(number)` → fallback `not_allowlisted`; otherwise
    `body = copy.bn("wa.login_code", Map.of("code", code, "minutes", BanglaDate.digits(String.valueOf(properties.getAuth().getOtpTtl().toMinutes()))))`,
    `sendText(number, body)`, then `doOnNext(wamid -> log.info("[baymax] otp delivered channel=whatsapp phoneHash={}", hash))`
    (**no wamid**: it is the number), then `.then()`. On error: `log.debug("[baymax] otp send error: {}", e.getClass().getSimpleName())`,
    then fallback with `WhatsAppMessageDelivery.classify(e)`. **Never log `body`.**
  - `messages_bn.properties`: `wa.login_code=Medioo-তে ঢোকার কোড: {code}\nকোডটা {minutes} মিনিট চলবে।\nএই কোড কাউকে দেবেন না। Medioo কখনো ফোন করে কোড চায় না।`
    and the English twin.
  - `application.properties`: delete `baymax.auth.otp-delivery=…` (`:124`); replace the comments at `:110-113` with
    "Login codes (D2): the family texts কোড on WhatsApp; the code is minted for that sender and sent back over WhatsApp.
    If the send fails the log says so with no code; the code is at DEBUG only (break-glass:
    `LOGGING_LEVEL_COM_ELIOO_BAYMAX_WEB_ADAPTER_OUT_DELIVERY=DEBUG`)."; add
    `baymax.auth.web-mints-code=${BAYMAX_AUTH_WEB_MINTS_CODE:false}` and `baymax.wa.public-number=${BAYMAX_WA_PUBLIC_NUMBER:}`.
- [ ] **Step 4:** `./gradlew :baymax:test --tests '*delivery*' --tests '*BaymaxPropertiesBindingTest'` green.
- [ ] **Step 5:** `feat(baymax): composite OTP delivery — WhatsApp first, log fallback with no code above DEBUG`.

### Task 12.3: "কোড" on WhatsApp answers with a code — a router rule, one normalisation

**Files:** modify `WaTextRouter.java` (`Route.LOGIN_CODE`), `WaIntakeService.java` (`onText`, a `loginCode` method,
`Outcome.LOGIN_CODE`; field `auth` appended last), `messages_bn/en.properties`; tests `WaTextRouterTest.java`,
`WaIntakeServiceTest.java`; create `baymax/src/test/java/com/elioo/baymax/wa/application/service/WaLoginCodeFlowTest.java`.

The rule lives in PR 6's router, so it uses the router's one normalisation (NFKC, ZWJ/ZWNJ stripped, punctuation and
case folded) and the whole-message rule, unconditionally (there is no red-flag route before Phase 3).

- [ ] **Step 1: Write the tests, both directions, before the code.** In `WaTextRouterTest`:

```java
@Test
void theLoginWordsAloneAreALoginCodeRequest() {
    for (String s : List.of("কোড", "  কোড ", "কোড।", "code", "Code", "CODE!", "kod", "otp", "OTP", "লগইন",
            "কোড")) {   // the last is কোড typed as e-kar + aa-kar, which NFKC composes to o-kar
        assertThat(WaTextRouter.route(s)).as(s).isEqualTo(WaTextRouter.Route.LOGIN_CODE);
    }
}

@Test
void aMessageThatOnlyMentionsACodeIsNotALoginRequest() {
    for (String s : List.of("আমার কোড আসেনি", "code ta pai nai", "otp ki?", "কোডটা", "promo code")) {
        assertThat(WaTextRouter.route(s)).as(s).isNotEqualTo(WaTextRouter.Route.LOGIN_CODE);
    }
}
```

  In `WaIntakeServiceTest`:

```java
private final com.elioo.baymax.web.application.port.in.WebAuthUseCase auth =
        mock(com.elioo.baymax.web.application.port.in.WebAuthUseCase.class);
// in wire(): append auth to the constructor (…, freeTier, inbound, hasher, auth);
//            when(documents.latestDoneDocument(any())).thenReturn(Mono.empty());

@org.junit.jupiter.params.ParameterizedTest
@org.junit.jupiter.params.provider.ValueSource(strings = {"কোড", "  কোড ", "কোড।", "code", "Code", "CODE!", "kod", "otp", "OTP", "লগইন",
        "কোড"})   // কোড typed as e-kar + aa-kar, which NFC composes to o-kar
void everyWayOfAskingForTheLoginCodeIssuesOneAndIntakeItselfSendsNothing(String text) {
    when(auth.issueCodeFor(E164)).thenReturn(Mono.just(CodeIssue.ISSUED));
    assertThat(service.onText(FROM, text).block()).isEqualTo(WaIntakeService.Outcome.LOGIN_CODE);
    verify(auth).issueCodeFor(E164);
    verify(graph, never()).sendText(anyString(), anyString());   // the code goes out through OtpDeliveryPort only
    verify(explainer, never()).detail(any());
}

@org.junit.jupiter.params.ParameterizedTest
@org.junit.jupiter.params.provider.ValueSource(strings = {"আমার কোড আসেনি", "code ta pai nai", "photo", "বিস্তারিত",
        "otp ki?", "কোডটা", "promo code", "hi", ""})
void aMessageThatOnlyMentionsACodeIsNotALoginRequest(String text) {
    familyWith(patient("মা"));
    service.onText(FROM, text).block();
    verify(auth, never()).issueCodeFor(anyString());
}

@Test
void aRateLimitedSenderIsToldOnWhatsappBecauseTheyHaveProvedTheNumber() {
    when(auth.issueCodeFor(E164)).thenReturn(Mono.just(CodeIssue.RATE_LIMITED));
    service.onText(FROM, "কোড").block();
    verify(graph).sendText(E164, new Copy().bn("wa.login_rate_limited", java.util.Map.of("minutes", com.elioo.baymax.outbound.application.service.BanglaDate.digits(
            String.valueOf(props.getAuth().getOtpTtl().toMinutes())))));
}

@Test
void aSenderWithNoAccountHearsThatOnceAndNotTheGenericStub() {
    when(auth.issueCodeFor(E164)).thenReturn(Mono.just(CodeIssue.NO_ACCOUNT));
    assertThat(service.onText(FROM, "কোড").block()).isEqualTo(WaIntakeService.Outcome.UNKNOWN_SENDER);
    verify(graph, org.mockito.Mockito.times(1)).sendText(anyString(), anyString());
    verify(graph).sendText(E164, new Copy().bn("wa.login_no_account", java.util.Map.of()));
    verify(records, never()).findFamilyByWhatsapp(anyString());   // the rule runs before the family lookup
}

@Test
void aLoginThatCannotBeIssuedIsAnsweredNeverSilent() {
    when(auth.issueCodeFor(E164)).thenReturn(Mono.error(new com.elioo.baymax.common.error.BaymaxException(
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "auth_not_configured", "baymax.auth.hmac-secret is not configured")));
    assertThat(service.onText(FROM, "কোড").block()).isEqualTo(WaIntakeService.Outcome.FAILED);
    verify(graph).sendText(E164, new Copy().bn("wa.login_off", java.util.Map.of()));
}

@Test
void noLoginReplyGoesToASenderOffTheAllowlist() {
    props.getWa().setAllowlist(List.of("+8801700000999"));
    when(auth.issueCodeFor(E164)).thenReturn(Mono.just(CodeIssue.RATE_LIMITED));
    service.onText(FROM, "কোড").block();
    verify(graph, never()).sendText(anyString(), anyString());
}
```

  `WaLoginCodeFlowTest` exercises the try end to end with no network: a real `WebAuthService` (stores mocked as in
  `WebAuthServiceTest.wire()`, `NUMBER = "+8801700000000"`), a real `CompositeOtpDelivery` over a mocked
  `WaMessagingPort` (`sendText` → `Mono.just(WaFixtures.wamid("8801700000000", 41))`), and a real `WaIntakeService` over
  the same mock, with the ROOT logger captured at TRACE:

```java
@Test
void kodOnWhatsappSendsACodeBackThatOpensTheWebSessionAndNoLogLineCarriesIt() {
    assertThat(intake.onText("8801700000000", "কোড").block()).isEqualTo(WaIntakeService.Outcome.LOGIN_CODE);

    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
    verify(graph).sendText(eq("+8801700000000"), body.capture());
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?<![0-9])([0-9]{6})(?![0-9])").matcher(body.getValue());
    assertThat(m.find()).as("the reply carries a 6-digit code").isTrue();
    String code = m.group(1);

    for (ILoggingEvent e : logged.list) {
        assertThat(e.getFormattedMessage().split("[^0-9A-Za-z]+")).as("%s %s", e.getLevel(), e.getFormattedMessage())
                .doesNotContain(code);
    }
    StepVerifier.create(auth.verify(auth.hash("01700000000"), code))
            .assertNext(issued -> assertThat(issued.session().familyId()).isEqualTo(FAMILY)).verifyComplete();
}
```

- [ ] **Step 2: Run.** First a compile failure (constructor, `Route.LOGIN_CODE`, `Outcome.LOGIN_CODE`). Add them with the
  router returning `FALLBACK` for the login words: every "issues" case fails with `Wanted but not invoked:
  webAuthUseCase.issueCodeFor("+8801700000000")` and every "mentions" case passes. **Then the mutation:** make the
  router return `LOGIN_CODE` for any text containing `কোড` or `code`; the "mentions" cases must fail with `Never wanted
  here: issueCodeFor` (and the router test with `expected not: LOGIN_CODE`), proving that half can fail. Revert.
- [ ] **Step 3: Implement.** `WaTextRouter`: `LOGIN_CODE` in `Route`, `LOGIN = words("কোড", "code", "kod", "otp", "লগইন")`,
  checked first. `WaIntakeService.onText`, before the family lookup (`issueCodeFor` answers NO_ACCOUNT itself):

```java
        if (route == WaTextRouter.Route.LOGIN_CODE) {
            return loginCode(number);
        }
```

```java
    /** D2: a code for the number WhatsApp proved. ISSUED needs no reply here: OtpDeliveryPort has already sent it. */
    private Mono<Outcome> loginCode(String number) {
        String minutes = BanglaDate.digits(String.valueOf(properties.getAuth().getOtpTtl().toMinutes()));
        return auth.issueCodeFor(number)
                .flatMap(issue -> switch (issue) {
                    case ISSUED -> Mono.just(Outcome.LOGIN_CODE);
                    case RATE_LIMITED -> reply(number, "wa.login_rate_limited", Outcome.LOGIN_CODE, "login code rate-limited",
                            Map.of("minutes", minutes));
                    case NO_ACCOUNT -> reply(number, "wa.login_no_account", Outcome.UNKNOWN_SENDER, "login code, no account");
                })
                .onErrorResume(e -> {
                    log.error("[baymax] wa login code failed: {}", e.getClass().getSimpleName());
                    return reply(number, "wa.login_off", Outcome.FAILED, "login code could not be issued");
                });
    }
```

  Copy (bn, with en twins): `wa.login_rate_limited=অল্প সময়ে অনেকবার কোড চাওয়া হয়েছে। শেষ যে কোডটা এসেছে সেটা দিয়েই ঢুকুন, বা {minutes} মিনিট পরে আবার "কোড" লিখুন।`
  (accurate: `verify` reads only the newest live code); `wa.login_no_account=এই নম্বরে এখনো Medioo খোলা হয়নি।\nযিনি অ্যাকাউন্ট খুলেছেন তাঁকে বলুন এই নম্বরটা যোগ করে দিতে।`;
  `wa.login_off=এই মুহূর্তে লগইন চালু নেই। একটু পরে আবার "কোড" লিখুন।`. `CopyInstructionsHaveHandlersTest` now sees
  "কোড" invited and finds it routed.
- [ ] **Step 4:** `./gradlew :baymax:test --tests '*WaTextRouterTest' --tests '*WaIntakeServiceTest' --tests '*WaLoginCodeFlowTest' --tests '*CopyInstructionsHaveHandlersTest' --tests '*EveryInboundTypeIsAnsweredTest'` green.
- [ ] **Step 5:** `feat(baymax): "কোড" on WhatsApp mints a code for the sender (D2)`.

### Task 12.4: Startup refuses the two dangerous configurations

**Files:** modify `WaConfigValidator.java`; test `WaConfigValidatorTest.java`.

- [ ] **Step 1: Write the tests:**

```java
/** D2: with WhatsApp on, a web-minted code would make the open form a sender to any typed number. */
@Test
void theWebFormMayNotMintCodesWhileWhatsappIsOn() {
    BaymaxProperties p = valid();
    p.getAuth().setWebMintsCode(true);
    assertThatThrownBy(() -> validate(p)).hasMessageContaining("BAYMAX_AUTH_WEB_MINTS_CODE");
}

@Test
void thePublicNumberForTheDeepLinkIsDigitsOnlyAndBlankIsAllowed() {
    BaymaxProperties p = valid();
    p.getWa().setPublicNumber("+880 1700-000000");
    assertThatThrownBy(() -> validate(p)).hasMessageContaining("BAYMAX_WA_PUBLIC_NUMBER");
    p.getWa().setPublicNumber("8801700000000");
    assertThatCode(() -> validate(p)).doesNotThrowAnyException();
    p.getWa().setPublicNumber("");
    assertThatCode(() -> validate(p)).doesNotThrowAnyException();
}
```

- [ ] **Step 2:** red: `Expecting code to raise a throwable`, naming the missing rule.
- [ ] **Step 3:** `if (properties.getAuth().isWebMintsCode()) problems.add("BAYMAX_AUTH_WEB_MINTS_CODE must be false while
  BAYMAX_WA_ENABLED=true — the web form must never send codes to typed numbers (D2)")`; a non-blank `publicNumber` must
  match `[1-9]\\d{7,14}`; a blank one logs WARN "sign-in page will show no WhatsApp button".
- [ ] **Step 4–5:** green; `feat(baymax): refuse web-minted codes with WhatsApp on; validate the public number`.

### Task 12.5: `sign-in-number` — one field, the number shown back, a cookie with no number in it

**Files:** modify `web/adapter/in/ui/WebUiHandler.java` (`loginPage` `:216-229`, `login` `:231-238`,
`loginHash`/`loginCookie` `:735-743`; a `BaymaxProperties properties` field appended last); create `PhoneDisplay.java`
(package-private: `Optional<String> normalise(raw)` around `FamilyAccountService.normalizeNumber`; `full(e164)` gives
`+880 1700-000000` for `+8801…` and the raw E.164 otherwise; `last3(e164)`); `ui_bn/en.properties` (`:87-93`),
`baymax.css`; create `SignInPagesTest.java` (WebTestClient over `BaymaxWebUiRouter`, wired as in
`BaymaxWebUiRouterTest.java:22-30`, with `props.getWa().setPublicNumber("8801700000000")`); update the constructor calls in
`BaymaxWebUiRouterTest`, `WebUiHandlerRenderTest` and `MediooBrandTest`. The screen is `sign-in-number` in the Medioo
Experience canvas; restyled on today's `baymax.css` tokens only (tokens v3 are Phase 1b).

- [ ] **Step 1: Write the tests:**

```java
private String get(String path, String loginCookie) {
    var req = client.get().uri(path);
    if (loginCookie != null) req = req.cookie("baymax_login", loginCookie);
    return req.exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
}
private String post(String path, org.springframework.util.MultiValueMap<String, String> form, String loginCookie) {
    var req = client.post().uri(path);
    if (loginCookie != null) req = req.cookie("baymax_login", loginCookie);
    return req.contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
            .body(org.springframework.web.reactive.function.BodyInserters.fromFormData(form))
            .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
}
private static org.springframework.util.MultiValueMap<String, String> form(String... kv) {
    var m = new org.springframework.util.LinkedMultiValueMap<String, String>();
    for (int i = 0; i < kv.length; i += 2) m.add(kv[i], kv[i + 1]);
    return m;
}

/** Phase 1a sends nothing from the web (D2, D36): a sign-in page that says a code was or will be sent is lying. */
static void assertNoSendPromise(String html) {
    assertThat(html).doesNotContain("পাঠিয়েছি").doesNotContain("পাঠানো হবে").doesNotContain("পাঠানো হয়েছে")
            .doesNotContain("will be sent").doesNotContain("on its way").doesNotContainIgnoringCase("we sent");
}

/** Regression guard, replayed on the pre-fix code: login.hint promised "একটি ৬ সংখ্যার কোড পাঠানো হবে". */
@Test
void noSignInPagePromisesASend() {
    String number = get("/app/", null);
    assertNoSendPromise(number);                                    // first, so the replay fails ON the promise
    assertThat(number).contains("<form").contains("name=\"whatsapp_number\"");   // and not on an empty page
    String code = get("/app/verify", "c".repeat(64) + ".171");
    assertNoSendPromise(code);
    assertThat(code).contains("name=\"code\"");
}

@Test
void theGuardCanFailAndNoLoginCopyCarriesTheWordInEitherLanguage() {
    assertThatThrownBy(() -> assertNoSendPromise("<p>কোড পাঠিয়েছি</p>")).isInstanceOf(AssertionError.class);
    UiCopy copy = new UiCopy();
    for (Lang lang : Lang.values()) {
        for (String key : copy.keys(lang)) {
            if (key.startsWith("login.")) {
                assertThat(copy.t(lang, key)).as(lang + " " + key).doesNotContain("পাঠিয়েছি").doesNotContain("পাঠানো হবে")
                        .doesNotContain("will be sent");
            }
        }
    }
}

@Test
void theNumberPageIsOneFieldWithNoPrefixAndNoDeepLink() {
    String html = get("/app/", null);
    assertThat(html).contains("Medioo-তে ঢুকুন").contains("আপনার WhatsApp নম্বর").contains("যে নম্বরে WhatsApp চালান, সেটাই দিন")
            .contains("পরের ধাপ").contains("type=\"tel\"").contains("autocomplete=\"tel\"");
    assertThat(html).doesNotContain("wa.me").doesNotContain("8801700000000");   // the deep link is the code step's, D37
}

@org.junit.jupiter.params.ParameterizedTest
@org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
        "01700000000|+880 1700-000000", "+880 1700-000000|+880 1700-000000", "008801700000000|+880 1700-000000",
        "০১৭০০০০০০০০|+880 1700-000000", "0170-000 0000|+880 1700-000000", "+447700900123|+447700900123"})
void aValidNumberIsShownBackNormalisedAndNothingHappensYet(String typed, String shown) {
    String html = post("/app/login", form("whatsapp_number", typed), null);
    assertThat(html).contains(shown).contains("ঠিক আছে?")
            .contains("<input type=\"hidden\" name=\"confirmed\" value=\"1\">");
    verify(auth, never()).requestCode(anyString());
}

@Test
void anInvalidNumberIsRefusedInlineAndRevealsNothing() {
    String html = post("/app/login", form("whatsapp_number", "1700000000"), null);
    assertThat(html).contains("<div class=\"err\" role=\"alert\">").contains("নম্বরটা ঠিক মনে হচ্ছে না")
            .contains("value=\"1700000000\"");
    verify(auth, never()).requestCode(anyString());
}

/** DR-23: the next URL carries nothing; the cookie carries the HMAC and three digits, never the number. */
@Test
void confirmingSetsAShortLivedCookieWithOnlyTheHashAndTheLastThreeDigits() {
    String hash = "c".repeat(64);
    when(auth.requestCode("+8801700000171")).thenReturn(Mono.just(hash));
    client.post().uri("/app/login").contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
            .body(org.springframework.web.reactive.function.BodyInserters.fromFormData(form("whatsapp_number", "01700000171", "confirmed", "1")))
            .exchange().expectStatus().isSeeOther()
            .expectHeader().valueEquals(HttpHeaders.LOCATION, "/app/verify")
            .expectHeader().value(HttpHeaders.SET_COOKIE, c -> assertThat(c)
                    .startsWith("baymax_login=" + hash + ".171;").contains("HttpOnly").contains("Path=/app")
                    .contains("Max-Age=1800").doesNotContain("01700000171").doesNotContain("8801700000171"));
}
```

  In `BaymaxWebUiRouterTest`, set `props.getWa().setPublicNumber("8801700000000")` in the constructor and add
  `.doesNotContain("8801700000000")` to the landing assertion at `:40-42`. Without the configured number, the
  existing "no wa.me on landing" assertion passes simply because there is no number to leak.
- [ ] **Step 2 — replay red.** On the pre-fix `WebUiHandler` (constructor adapted for compilation only), run
  `noSignInPagePromisesASend`. It must fail with `Expecting actual: "...এই নম্বরে একটি ৬ সংখ্যার কোড পাঠানো
  হবে।..." not to contain: "পাঠানো হবে"`. The other tests fail on missing copy, which is expected for new behaviour.
- [ ] **Step 3 — implementation.**
  - `loginPage(lang, raw, error)`: `<h1>login.title</h1>`, then the error div if any, then
    `<form method="post" action="/app/login">` holding `<label for="n">login.number</label>` and
    `<input id="n" type="tel" name="whatsapp_number" autocomplete="tel" inputmode="tel" pattern="[0-9০-৯+ \-]{8,24}" value="<esc raw>" required>`,
    then `<p class="small">login.hint</p>` and `<button type="submit">login.next</button>`. No fixed prefix and no
    placeholder digits. The `pattern` is a courtesy; the server is the check.
  - `login`: read the form. `PhoneDisplay.normalise(raw)` empty → `loginPage(..., raw, login.number.invalid)`.
    Valid and not `confirmed=1` → a confirm view: `<p class="number-back">+880 1700-000000</p><p>login.number.confirm</p>`,
    a form posting hidden `whatsapp_number` (normalised) and `confirmed=1` with the button `login.number.yes`, and
    `<a class="btn quiet" href="/app/">login.number.change</a>`. Valid and confirmed →
    `auth.requestCode(normalised)`, which ~~mints nothing in production (Task 12.1)~~ (superseded 2026-10-18: mints and
    sends only inside the window, PR 12 note), then 303 `/app/verify` with
    `loginCookie(hash + "." + last3, Duration.ofMinutes(30))`. The code lives 10 minutes, but a family may ask again
    from the same page.
  - `loginHash` accepts `^([0-9a-f]{64})(?:\.([0-9]{3}))?$`, because a cookie from the old format may still be in
    flight for 10 minutes after the deploy. Add `loginTail(request)` → `Optional<String>`.
  - Copy (bn): `login.title=Medioo-তে ঢুকুন`, `login.number=আপনার WhatsApp নম্বর`,
    `login.hint=যে নম্বরে WhatsApp চালান, সেটাই দিন`, `login.next=পরের ধাপ`, `login.number.confirm=ঠিক আছে?`,
    `login.number.yes=হ্যাঁ, পরের ধাপ`, `login.number.change=বদলান`,
    `login.number.invalid=নম্বরটা ঠিক মনে হচ্ছে না। 01XXXXXXXXX লিখুন, বা দেশের কোডসহ (+44…)।`. Delete `login.send` and
    the old `login.hint`. Add English twins (`UiCopyTest` asserts both bundles have the same keys).
  - CSS, tokens only: `.number-back{font-size:var(--fs-2);font-weight:600;font-variant-numeric:tabular-nums}`.
- [ ] **Step 4:** `./gradlew :baymax:test --tests '*SignInPagesTest' --tests '*BaymaxWebUiRouterTest' --tests '*UiCopyTest' --tests '*DesignTokensTest' --tests '*MediooBrandTest' --tests '*WebUiHandlerRenderTest'` green.
- [ ] **Step 5:** `feat(baymax): sign-in-number shows the normalised number back; cookie holds hash + last three`.

### Task 12.6: `sign-in-code` — the deep link, the honest states

**Files:** modify `WebUiHandler.java` (`verifyPage` `:240-260`, `verify` `:262-277`), `ui_bn/en.properties`, `baymax.css`,
`AuthHandler.java:30-31`; tests `SignInPagesTest.java`, `BaymaxAuthRouterTest.java:48-56`. The screen is `sign-in-code` in
the canvas (D37: the `wa.me` link appears on `/app` only, never on the landing page).

- [ ] **Step 1: Write the tests:**

```java
private static final String LOGIN = "c".repeat(64) + ".171";

@Test
void theCodePageSendsTheFamilyToWhatsappToAskForTheCode() {
    String html = get("/app/verify", LOGIN);
    assertThat(html).contains("এখন WhatsApp-এ Medioo-কে লিখুন:").contains("<strong>কোড</strong>")
            .contains("href=\"https://wa.me/8801700000000?text=%E0%A6%95%E0%A7%8B%E0%A6%A1\"")
            .contains("WhatsApp খুলে কোড আনুন").contains("WhatsApp-এ শুধু Send চাপুন — কোড সাথে সাথে চলে আসবে")
            .contains("••••••••171")
            .contains("autocomplete=\"one-time-code\"").contains("inputmode=\"numeric\"").contains("pattern=\"[0-9০-৯]{6}\"")
            .contains("কোড ১০ মিনিট চলবে").contains("অন্য নম্বর দিন");
    assertThat(html).doesNotContain("?whatsapp").doesNotContain("?code").doesNotContain("?n=");   // DR-23
}

@Test
void inEnglishTheDeepLinkPrefillsCode() {
    String html = client.get().uri("/app/verify").cookie("baymax_login", LOGIN).cookie("baymax_lang", "en")
            .exchange().expectBody(String.class).returnResult().getResponseBody();
    assertThat(html).contains("href=\"https://wa.me/8801700000000?text=code\"").contains("Open WhatsApp to get the code");
}

@Test
void withNoPublicNumberThePageStillInstructsAndHasNoDeadLink() {
    props.getWa().setPublicNumber("");
    String html = get("/app/verify", LOGIN);
    assertThat(html).contains("এখন WhatsApp-এ Medioo-কে লিখুন:").doesNotContain("wa.me");
}

@org.junit.jupiter.params.ParameterizedTest
@org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
        "WRONG|কোডটা মেলেনি — যে নম্বর থেকে WhatsApp করেছেন, সেটাই কি এখানে দিয়েছেন?",
        "NO_CODE|কোডটা মেলেনি — যে নম্বর থেকে WhatsApp করেছেন, সেটাই কি এখানে দিয়েছেন?",
        "EXPIRED|কোডটার মেয়াদ শেষ",
        "BURNED|৫ বার ভুল হয়েছে",
        "FORMAT|৬টা সংখ্যা লিখুন"})
void eachRefusalSaysWhatHappenedAndTheWayToANewCodeIsRightThere(VerifyFailure failure, String says) {
    when(auth.verify(anyString(), anyString())).thenReturn(Mono.error(new CodeRejectedException(failure)));
    String html = post("/app/verify", form("code", "123456"), LOGIN);
    assertThat(html).contains("<div class=\"err\" role=\"alert\">" + says).contains("wa.me/8801700000000");
    assertNoSendPromise(html);
}

@Test
void theRightCodeOpensTheSessionAndClearsTheLoginCookie() {
    java.time.Instant now = java.time.Instant.now();
    when(auth.verify("c".repeat(64), "483920")).thenReturn(Mono.just(new com.elioo.baymax.web.domain.IssuedSession("tok",
            new com.elioo.baymax.web.domain.WebSession(UUID.randomUUID(), UUID.randomUUID(), now, now, now.plusSeconds(60)))));
    client.post().uri("/app/verify").cookie("baymax_login", LOGIN)
            .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
            .body(org.springframework.web.reactive.function.BodyInserters.fromFormData("code", "483920"))
            .exchange().expectStatus().isSeeOther().expectHeader().valueEquals(HttpHeaders.LOCATION, "/app/home")
            .expectHeader().values(HttpHeaders.SET_COOKIE, cs -> assertThat(cs)
                    .anySatisfy(c -> assertThat(c).startsWith("baymax_session=tok"))
                    .anySatisfy(c -> assertThat(c).startsWith("baymax_login=;").contains("Max-Age=0")));
}
```


  In `BaymaxAuthRouterTest.requestAlwaysAnswers200WithTheSameBody`, add
  `.jsonPath("$.message").value(m -> assertThat((String) m).doesNotContain("on its way").contains("WhatsApp"))`.
- [ ] **Step 2 — expected red:** `Expecting actual ... to contain "এখন WhatsApp-এ Medioo-কে লিখুন:"`. For the
  refusals, `to contain "<div class=\"err\" role=\"alert\">কোডটার মেয়াদ শেষ"` fails against today's single
  `login.bad` (`ui_bn.properties:93`). That is the defect: one message for every state.
- [ ] **Step 3 — implementation.**
  - `verifyPage(request, error)`: `<h1>login.code.title</h1>` ("Medioo-তে ঢুকুন"), then
    `<p class="lede">login.code.say <strong>login.code.word</strong></p>`. If `properties.getWa().getPublicNumber()`
    is non-blank, add `<a class="btn wa" href="https://wa.me/<n>?text=<URLEncoder(login.code.word, UTF-8)>">login.wa.open</a>`
    and `<p class="small">login.wa.hint</p>`. Then `<p class="mask">login.code.number(•••••••• + tail)</p>`, the error
    div `role="alert"`, and the form: `<input id="c" class="code-in" type="text" name="code" inputmode="numeric"
    pattern="[0-9০-৯]{6}" maxlength="6" autocomplete="one-time-code" required>`, `login.enter` ("ঢুকুন"),
    `<p class="small">login.code.ttl(minutes)</p>` (Bangla digits via `BanglaDate.digits` when `lang == BN`), and
    `<a href="/app/">login.other</a>`. No `<script>`. The 5-second poll waits for the phase-1b Baymax-path CSP
    (`STRICT_CSP` has no `script-src`, `SecurityHeadersFilter.java:29-30`).
  - `verify`: replace the single `onErrorResume(BaymaxException…)` (`:274-276`) with
    `onErrorResume(CodeRejectedException.class, e -> verifyPage(request, refusal(lang, e.failure())))`, which maps
    `WRONG, NO_CODE → login.bad.wrong`, `EXPIRED → login.bad.expired`,
    `BURNED → login.bad.burned(otpMaxAttempts in Bangla digits)`, `FORMAT → login.bad.format`. Any other 401 maps to
    `login.bad.wrong`.
  - Copy (bn): `login.code.title=Medioo-তে ঢুকুন`, `login.code.say=এখন WhatsApp-এ Medioo-কে লিখুন:`,
    `login.code.word=কোড`, `login.wa.open=WhatsApp খুলে কোড আনুন`,
    `login.wa.hint=WhatsApp-এ শুধু Send চাপুন — কোড সাথে সাথে চলে আসবে`, `login.code.number=নম্বর: {0}`,
    `login.code=৬ সংখ্যার কোড`, `login.enter=ঢুকুন`, `login.code.ttl=কোড {0} মিনিট চলবে`,
    `login.other=অন্য নম্বর দিন`,
    `login.bad.wrong=কোডটা মেলেনি — যে নম্বর থেকে WhatsApp করেছেন, সেটাই কি এখানে দিয়েছেন?`,
    `login.bad.expired=কোডটার মেয়াদ শেষ। WhatsApp-এ আবার "কোড" লিখুন — নতুন কোড আসবে।`,
    `login.bad.burned={0} বার ভুল হয়েছে, তাই এই কোডটা আর চলবে না। WhatsApp-এ আবার "কোড" লিখে নতুন কোড নিন।`,
    `login.bad.format=৬টা সংখ্যা লিখুন।`. Delete `login.bad`. English twins: `login.code.word=code`,
    `login.wa.open=Open WhatsApp to get the code`, and so on.
  - CSS: `.code-in{font-size:var(--fs-2);letter-spacing:.2em;font-variant-numeric:tabular-nums}`,
    `.mask{font-variant-numeric:tabular-nums;color:var(--c-muted)}`, `.btn.wa{display:block;text-align:center}`.
  - `AuthHandler.request` message: `"to get a code, send the word কোড to Medioo on WhatsApp"`.
- [ ] **Step 4:** Task 12.5's command plus `--tests '*BaymaxAuthRouterTest'`.
- [ ] **Step 5:** `feat(baymax): sign-in-code — wa.me deep link on /app only, honest refusal states, Bangla-digit field`.

### Task 12.7: Docs and env

- [ ] **`docs/MEDIOO.md`:** at `:60` and `:76`, "OTP stays on the log adapter" and "a code is issued and logged for unknown
  numbers" are no longer true; replace them with the D2 flow, the composite port, `web-mints-code` (dev only), the typed
  web refusals with the identical API 401, and the break-glass logger. (Stage this file only for these lines; if the
  working tree also holds the uncommitted "Experience design" pointer, Tanvir decides whether it ships here.)
- [ ] **`docs/RUNBOOK.md`:** "Must be set": `BAYMAX_WA_PUBLIC_NUMBER` (digits only; the display number of
  `BAYMAX_WA_PHONE_NUMBER_ID`). "Must be blank": `BAYMAX_AUTH_WEB_MINTS_CODE` (startup refuses `true` with WhatsApp on) and
  `BAYMAX_OTP_DELIVERY` (unused now; delete the line). A break-glass section:
  `LOGGING_LEVEL_COM_ELIOO_BAYMAX_WEB_ADAPTER_OUT_DELIVERY=DEBUG`, restart, the family sends কোড,
  `docker logs … | grep 'otp fallback'`, then set it back.
- [ ] **`medscribe.env.example`:** `# BAYMAX_WA_PUBLIC_NUMBER=` and
  `# BAYMAX_AUTH_WEB_MINTS_CODE=false   # true only on a laptop with WhatsApp off; the code is then at DEBUG`.
- [ ] Commit: `docs(baymax): inbound-first sign-in in MEDIOO and the runbook`.

### Task 12.8: Ship

- [ ] **Step 1:** confirm Task 12.0 is done (Tanvir's `cat -A` line). Then the standard pre-merge;
  `git status --ignored --short -- baymax/src medscribe-ai/src | grep '^!!'` prints nothing. The PR body lists the
  replayed reds (Tasks 12.2 and 12.5), the new-feature tests, the env prerequisite, and DR-39.
- [ ] **Step 2:** after the deploy, Tanvir deletes the now-unused `BAYMAX_OTP_DELIVERY` line (backup first; harmless if left).

**Try it on your phone (PR 12)** — this is Phase 1a's done:
- [ ] Open medioo.eliooo.org/app, type the number as `০১৭…` with spaces, and see `+880 17xx-xxxxxx — ঠিক আছে?`.
- [ ] Tap "হ্যাঁ, পরের ধাপ", then "WhatsApp খুলে কোড আনুন"; WhatsApp opens with "কোড" pre-filled; press Send.
- [ ] Within seconds "Medioo-তে ঢোকার কোড: …" arrives. Type it in Bangla digits and land on Ma's page.
- [ ] Type a wrong code five times on a second attempt: the page says "৫ বার ভুল হয়েছে…" with the way to a new code, and
  never "পাঠিয়েছি".
- [ ] On the box: `docker logs medscribe-ai --since 10m 2>&1 | grep 'otp delivered' | tail -1` shows
  `channel=whatsapp phoneHash=…` and no code, no wamid; `docker logs medscribe-ai --since 10m 2>&1 | grep -c 'OTP for'` is 0.

**Post-deploy:** the standard check.

---

## After Phase 1a

Phase 1b starts from the roadmap (`2026-10-17-medioo-roadmap.md`, "Phase 1b"), with V20 and V21. Carried into it from
here: the JSON encoding of `outbound_message.urgency_reasons` (PR 5), which V20's backfill reads in both forms; the
`DocumentProcessingChain` (PR 4), where F1's central writer hooks in; `UrgencyLines` (PR 5), where D11's signed emergency
template replaces the interim red-flag line in Phase 3; and the open decisions listed in the roadmap (gate mode before
real families, D11, Meta templates, repeat-detail pointer vs resend, the red-flag line on THIS_WEEK).
