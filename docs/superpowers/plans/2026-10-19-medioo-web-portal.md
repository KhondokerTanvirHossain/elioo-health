# Medioo web portal — Phase 1b, Phase 2 and the web half of Phase 4 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Work one PR at a time, in the merge order below; each PR starts from merged `origin/main`.

**Goal:** A family does everything in the Medioo web app on a phone, and WhatsApp is used for one thing only: fetching
the sign-in code (PR 12, merged as `e6264a4`). They sign in, land on three always-labelled tabs (চ্যাট · কাগজপত্র ·
পরিবার), add a paper for a patient they choose (never guessed), watch an honest reading page move to the result by
itself, and read a result whose **stored, code-computed urgency** leads the page in every review state (F1, F3). Every
value row comes from a stored verdict that never softens (F2), every fact sits beside its crop, medicines come crop
first and verbatim, a retake says why in Bangla (F5) and offers a retake for the same patient, the কাগজপত্র tab shows
red and amber chips and nothing on routine papers, and the চ্যাট tab shows what needs attention with a composer that
honestly says chat is coming. The app looks like the PO-approved drawn screens (tokens v3, light and dark, Noto local
first). **Done for the night** is Milestone A: sign in → tabs → add a paper → reading page → result with the stored
banner, web only, proven end to end by a test that runs with WhatsApp switched off (PR A13). Done for the plan is
Tanvir running the morning checklist (last section) on his phone.

**Architecture:** Hexagonal Baymax module (`com.elioo.baymax`) inside the medscribe-ai deployable, server-rendered HTML
that works with no script. Two move-only refactors come first so that four streams of work stop editing the same
lines: `WebUiHandler` (876 lines) becomes one handler per screen, each publishing its own routes, and the stylesheet,
the copy bundles and the page head become ordered parts. F1 adds three columns to `document`, a trigger that rejects
any lowering, one writer (`StoredUrgencyService`, the only caller of `UrgencyService.assess` and of
`DocumentUrgencyPort.raise`, asserted by ArchUnit and a source sweep) hooked into `DocumentProcessingChain.explainOnly`
so fresh uploads and recovered documents both meet it, and an hourly sweep. F2 stores one verdict per observation from
the same rule branch, under a never-softens trigger, in the same transaction. F5 stores a retake reason code; the
English `status_reason` never reaches a page. One read model (`TimelineReadPort`) owns the timeline order, cursor,
chip rule, outside count and attention query. Every page that shows urgency reads `DocumentUrgencyUseCase`; nothing in
`web` assesses.

**Tech Stack:** Java 21, Spring Boot 3.4.2 WebFlux functional routes, Reactor, R2DBC + Flyway (schema `baymax`),
Postgres 17, JUnit 5, Mockito, AssertJ, `StepVerifier`, Testcontainers (`postgres:17-alpine`, MinIO), ArchUnit, Logback
`ListAppender`, `node --test` (Node 20) for `upload.js`, fontTools 4.53.1 for the font subsets.

**Spec:** `docs/superpowers/specs/2026-10-17-medioo-companion-experience-design.md` (§1 principles; §2 F1, F2, F3, F5,
F7, F8; §3 IA; §4.6–§4.17, §4.21; §8 tokens; §9 components; §10 accessibility). **Roadmap:**
`docs/superpowers/plans/2026-10-17-medioo-roadmap.md` (Phase 1b, Phase 2, Phase 4). **Tokens:**
`docs/superpowers/specs/2026-10-17-medioo-tokens-v3.json`. **Drawn screens (PO-approved):**
`/Users/tanvirhossain/medioo-design/canvas/project/s06…s21*.dc.html`, kit `/Users/tanvirhossain/medioo-design/kit.md`,
component READMEs `/Users/tanvirhossain/medioo-design/design-system/project/components/*/README.md`.

**PO direction this plan builds (Tanvir, 2026-10-19):** "the web portal needs to be up to date and I need to run and test
the whole thing without WhatsApp in the web app, in full." Everything a family does works on the web; WhatsApp only
delivers the login code. Standing direction: fully automated first, production stays `BAYMAX_GATE_MODE=off`.
Approved decisions built here: D5 (urgency at once, every gate state), D12 (Baymax-path CSP), D22 (tokens v3), D27 (no
ROUTINE chip), D35 (normal-range claim needs INSIDE by code). **Pending PO confirmation, shipped as drafts:** D28 short
forms (PR C2), every new Bangla string (the copy owner is Tanvir's wife; each PR lists its keys), the two scope
proposals E1 and E2.

**Out of scope tonight:** chat answers (needs D11 doctor sign-off; the চ্যাট composer says chat is coming), the NOW
acknowledgement "দেখেছি" (D14, needs a per-viewer table), add-patient s05 and share s20 (Phase 5; admin API only), trends
s18, F8 self mode, the per-page upload retry, and anything that calls a paid model outside the normal pipeline (W4.4
waits for Tanvir's approval).

## Global Constraints

- **Base.** `origin/main` @ `e6264a4` (#63, PR 12 merged, `V17__wa_last_inbound.sql` applied). Every line number below
  is from `e6264a4`; `WebUiHandler.java` is 876 lines there. After R1 the old line numbers no longer exist: anchor on the
  method names R1 moves, never on a line.
- **Production gate stays off.** PENDING, REJECTED and FAILED_SAFETY are tested in both gate modes and never tried on
  the phone. Nobody flips `BAYMAX_GATE_MODE` to try a page.
- **TDD; both directions first.** A rule with two directions gets both tests before the implementation. A **regression
  guard** is replayed against the broken behaviour and must fail **on the defect**: read the message and paste it into
  the PR body. A compile error, an NPE, a Mockito matcher error or `no UI copy for key …` is not a replay. Replay recipe
  when the guard sits beside new code: `git worktree add /tmp/replay-<pr> origin/main && cp <guard> /tmp/replay-<pr>/<same path> && (cd /tmp/replay-<pr> && ./gradlew :baymax:test --tests '<guard>')`, then
  `git worktree remove /tmp/replay-<pr>`. A **mutant replay** (the plan names the mutant) is made in the working tree,
  run, read, and reverted before the commit; `git diff --stat` after the revert shows only the PR's files.
- **Derived, never remembered.** A check over pages, routes, reasons, keys, tables or assets takes its list from the
  system (the router, the enum, the bundle files, `information_schema`, the served bytes) and asserts the list is not
  empty. The router list comes from `RouteTable` (R1); the page fixtures from `PageFixtures` (R1); a route with no
  fixture fails every derived page test.
- **Assert on what is sent.** CSS checks read the bytes served at `/assets/baymax.css`; the page-weight test fetches each
  asset through the test client; log checks read the captured events after asserting the expected line is there.
- **A DB constraint or trigger gets a Testcontainers test in both directions** (`runner()` from
  `baymax/src/test/java/com/elioo/baymax/nudge/adapter/out/persistence/PostgresNudgeAdapterTest.java:46-60`). Never only a
  mocked port. Every constraint name added by V18–V21 has a bad-write case that raises that name and a good-write case.
- **Logs:** ids, sizes, counts, levels and class names only. Never a patient name, a filename, a value, an instruction or
  a number. A log-capture guard first asserts the line it expects exists, then the absence.
- **One patient calendar.** Every "today" on a page, in the urgency rule and in the sweep is
  `PatientCalendar.today(clock)` (Asia/Dhaka, A10). A date the page shows is the patient's day.
- **Urgency on the web.** One method states a level: `UiCopy.urgency(Lang, Urgency, boolean shortForm)` (A10), which
  reads `messages_bn/en.properties` and never a copy. ROUTINE has no line, no chip, no banner: asking for it throws.
  Every page that shows urgency reads `DocumentUrgencyUseCase`; nothing under `web` calls `UrgencyService`.
- **Upload entry.** Every camera, "ছবি তুলুন", "কাগজ পাঠান", "আবার তুলুন" and "অন্য কাগজ পাঠান" control goes to
  `/app/add?patient=<id>&back=<local path>`; every multipart form posts to `/app/add` (the legacy
  `POST /app/patients/{id}/upload` stays as a route for cached pages). `?sheet=` is used only for the patient switcher.
  `UploadEntryPointsTest` (A8) holds this over every page.
- **Copy.** Bundle parts per area (R2): `ui_<lang>` (legacy, shrinks), `ui-shell_`, `ui-family_`, `ui-papers_`,
  `ui-chat_`, `ui-result_`, `ui-upload_`. A key lives in exactly one part (`UiCopyTest` reads raw lines and fails on a
  key defined twice anywhere) and in both languages. Shared keys (`sheet.close=বন্ধ`, `paper.label=কাগজে লেখা`,
  `app.back.papers`) live in `ui-shell`. `GlossaryTest` (R2, a ratchet) runs in every PR. No Bangla in Java string
  literals outside the ratchet's allow-list (R2). Values keep the printed digits; UI counts and dates go through
  `UiCopy.t`/`UiCopy.date` (Bangla digits on a Bangla page). New wording is a draft for the copy owner.
- **CSS.** Tokens only (`DesignTokensTest`); each area appends only to its own part file; every interactive selector has
  `min-height`/`height` ≥ `var(--target-min)` (L1); red only on `.now`, `.critical`, `.emergency` and the wordmark dot.
- **Constructors.** `@RequiredArgsConstructor` uses field order; a new field goes **last**. After R1 only
  `WebUiTestKit` builds web handlers in tests, so a constructor change touches the kit and the class. New reads get new
  services (`UploadService`, `ResultService`, `AttentionService`); `TimelineService` changes only in A10 and C2.
  Read-side queries go on `TimelineReadPort` (C2) or `DocumentReadPort` (B5); only D3 adds methods to `DocumentRecordPort`
  (B4 only binds its new column in `writeDocument`).
- **Interim markup.** A10, B1 and B4 each show their stored fact with at most 10 lines in `DocumentPages` and no new
  CSS; the next PR (A12, B2, B5) replaces it and rewrites the named interim test.
- **No conditionals.** The merge order is fixed; no step says "if X has merged".
- **Migrations.** Never edit an applied one. Allocation below. Preflight in each migration PR, right before merging:
  `git ls-tree --name-only origin/main baymax/src/main/resources/db/baymax/migration/ | sed 's#.*/##' | sort -V | tail -1`
  prints **exactly** its predecessor's file name. If not, stop: fix the allocation table in the roadmap first.
- **Staging and commits:** explicit paths only, never `git add -A`/`git add .`; conventional commits ending with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`; PR bodies end with
  `🤖 Generated with [Claude Code](https://claude.com/claude-code)`. `.gitignore` has `files/` and `log/`: never name a new
  directory either.
- **Secrets and production:** never source `.env.supabase` by default and never `bootRun` against it. Every production
  step (env edit, SSH, SQL) is **Tanvir's**, in the build report, with `cp ~/medscribe.env ~/medscribe.env.bak-$(date +%Y%m%d-%H%M)` first.
- **Fixtures:** families `+8801700000000`–`+8801700000999` only; documents are synthetic (DR-36).

### Every PR: pre-merge and post-deploy ("the standard pre-merge", "the standard post-deploy")

**Pre-merge** (the agent, overnight):
1. `git fetch origin && git rebase origin/main`; rewrite any snippet that no longer matches merged main.
2. Migration preflight (A10, B1, B4, D3 only), as above.
3. `docker compose up -d && ./gradlew build` green (all 10 modules); `./gradlew :baymax:test --tests '*GlossaryTest' --tests '*UiCopyTest' --tests '*DesignTokensTest'` named in the PR body as green.
4. `git status --ignored --short -- baymax/src medscribe-ai/src scripts | grep '^!!'` prints nothing.
5. `git diff --stat origin/main` lists only this PR's file map.
6. Push; `env -u GITHUB_TOKEN gh pr create` (body: what, why, every replayed red verbatim, new-feature tests named as
   such, copy keys for the copy owner, PO notes, env changes: none unless stated).
7. `env -u GITHUB_TOKEN gh pr view <n> --json mergeStateStatus` is `CLEAN`; `env -u GITHUB_TOKEN gh pr checks <n> --watch` green.
8. Squash-merge. **Verify:** `git fetch origin && git log origin/main --oneline | grep '#<n>'` prints the commit. Only then
   delete the remote and local branch. Wait for the deploy job to finish before merging the next PR.

**Merging overnight is not gated on a phone try.** A PR merges on green build, `CLEAN`, and the replay output in its
body. Every "Try it" goes into the morning checklist (last section), tagged **prod-web** (Tanvir's phone,
medioo.eliooo.org, WhatsApp only for the code), **local** (laptop, `BAYMAX_WA_ENABLED=false`,
`BAYMAX_AUTH_WEB_MINTS_CODE=true`, code read with `LOGGING_LEVEL_COM_ELIOO_BAYMAX_WEB_ADAPTER_OUT_DELIVERY=DEBUG`),
**admin** (needs the admin token) or **not web**. A failed try opens a fix PR; nothing is reverted by hand. Exception:
A10 needs P.3 (below) in its PR body before it merges, because a failed migration blocks every later deploy.

**Post-deploy** (the agent watches CI; Tanvir checks in the morning): `curl -fsS https://medioo.eliooo.org/actuator/health`
reports `UP`; the CI deploy job is green. Tanvir, morning: `docker logs medscribe-ai --since 12h 2>&1 | grep -cE ' ERROR |refusing to start'`
is 0 or each line is explained; the leak counter `grep -cE 'baymax_session=|wamid\.'` stays 0.

## Merge order

Milestone A is the night's target: alone it is "sign in → tabs → add a paper → reading → result with the stored urgency,
web only". About 1 h per PR; 28 PRs is more than one night, so B–D continue afterwards in this order. E1 and E2 are
proposals and wait for the PO.

| # | ID | PR | Branch | Migration | Tasks | Size |
|---|---|---|---|---|---|---|
| — | P | Preflight (Tanvir before he sleeps; Claude makes the try documents) | — | — | P.1–P.4 | S |
| 1 | **A1** | R1 — one web handler per screen (move only) | `refactor/baymax-web-handlers` | — | R1.1–R1.3 | M |
| 2 | **A2** | R2 — stylesheet, copy and head in parts; glossary ratchet | `refactor/baymax-web-parts` | — | R2.1–R2.5 | M |
| 3 | **A3** | L1 — tokens v3, dark mode, type scale, 48 px targets | `feat/baymax-ui-tokens-v3` | — | L1.1–L1.5 | M |
| 4 | **A4** | L2a — Baymax-path CSP, no inline code | `feat/baymax-ui-csp` | — | L2a.1–L2a.3 | S–M |
| 5 | **A5** | L4a — app shell and three tabs | `feat/baymax-ui-shell-tabs` | — | L4a.1–L4a.3 | M |
| 6 | **A6** | L5 — পরিবার tab (s21 basics) | `feat/baymax-ui-family-tab` | — | L5.1–L5.2 | S–M |
| 7 | **A7** | W4.1a — one allowance rule, `UploadService`, `GET /app/add` | `feat/baymax-web-add-sheet` | — | W1a.1–W1a.3 | M |
| 8 | **A8** | W4.1b — `POST /app/add`, refusals in Bangla, the try documents | `feat/baymax-web-add-submit` | — | W1b.1–W1b.4 | M |
| 9 | **A9** | 2.4 — reading progress (s09) | `feat/baymax-reading-progress` | — | 24.1–24.2 | M |
| 10 | **A10** | F1a — stored urgency that only rises | `feat/baymax-stored-urgency` | **V18** | 13a.1–13a.4 | M |
| 11 | **A11** | F1b — the sole writer, the sweep, the backfill | `feat/baymax-urgency-sole-writer` | — | 13b.1–13b.3 | M |
| 12 | **A12** | 2.1 — result read model, stored banner, F3 states (s10) | `feat/baymax-result-read-model` | — | 21.1–21.3 | M |
| 13 | **A13** | J1 — the web-only journey, end to end | `test/baymax-web-only-journey` | — | J1.1–J1.2 | S–M |
| 14 | **B1** | F2 — value verdicts that never soften | `feat/baymax-value-verdicts` | **V19** | 14.1–14.3 | M |
| 15 | **B2** | 2.2 — value rows, crop-first medicines, "ডাক্তার লিখেছেন" | `feat/baymax-result-value-rows` | — | 22.1–22.3 | M |
| 16 | **B3** | 2.3 — result-now (s11) | `feat/baymax-result-now` | — | 23.1–23.2 | M |
| 17 | **B4** | F5 — retake reason codes, timeline date | `feat/baymax-retake-reason-codes` | **V20** | 15.1–15.2 | S–M |
| 18 | **B5** | 2.5 — needs-retake (s12) | `feat/baymax-needs-retake` | — | 25.1–25.2 | S–M |
| 19 | **B6** | 2.6 — document detail and the value sheet (s17, s13) | `feat/baymax-document-detail` | — | 26.1–26.2 | M |
| 20 | **C1** | L4b — active patient, switcher sheet, legacy redirects | `feat/baymax-ui-active-patient` | — | L4b.1–L4b.2 | S–M |
| 21 | **C2** | T16 — the timeline read model | `feat/baymax-timeline-read-model` | — | 16.1–16.4 | M |
| 22 | **C3** | L6 — কাগজপত্র (s16), UI only | `feat/baymax-ui-papers` | — | L6.1 | M |
| 23 | **C4** | L7 — চ্যাট (s06): attention stack, honest composer | `feat/baymax-ui-chat-home` | — | L7.1–L7.3 | M |
| 24 | **D1** | L2b — self-hosted fonts, first-load budget | `feat/baymax-ui-fonts-budget` | — | L2b.1–L2b.3 | M |
| 25 | **D2** | L3 — glossary sweep and the F8 possessive | `fix/baymax-copy-glossary` | — | L3.1–L3.2 | S |
| 26 | **D3** | W4.2 — "আলাদা আলাদা" and a retry that never duplicates | `feat/baymax-upload-key` | **V21** | W2.1–W2.3 | S–M |
| 27 | **D4** | W4.3 — page tray, byte progress, retry (`upload.js`) | `feat/baymax-upload-tray` | — | W3.1–W3.4 | M |
| 28 | **D5** | W4.4 — downscale size by measurement (**paid run: Tanvir approves first**) | `feat/baymax-downscale-measured` | — | W44.1–W44.2 | S |
| — | **E1** | Proposal: delete a patient or everything from পরিবার (PO confirms scope) | `feat/baymax-web-delete-family` | — | E1.1–E1.2 | M |
| — | **E2** | Proposal: s19 "what's due" and reminders back on (PO confirms scope) | `feat/baymax-web-reminders` | — | E2.1–E2.2 | M |

Why this order. R1 and R2 first, because all four streams edited `WebUiHandler`, `baymax.css` and `ui_bn.properties` at
the same lines; after them each PR appends to its own handler, CSS part and bundle part. The look (A3–A6) and upload
(A7–A9) need no migration and make the web usable before F1. A9 reads only `document.status`, so it is safe before F1.
F1 (A10–A11) lands before the first page that states urgency (A12). A13 proves Milestone A in code. B pairs each
foundation with its screen right after it (F2 → rows, F5 → retake) so interim markup lives one PR. C needs F1, F2 and F5
for the read model. D is polish: fonts and budget, the glossary empties its ratchet, the upload key and tray, and the
measured downscale.

## Migration allocation

| Version | PR | Content |
|---|---|---|
| V17 | PR 12 (merged, `e6264a4`) | `wa_last_inbound` |
| **V18** | A10 (F1a) | `document_stored_urgency` — `urgency`, `urgency_reasons`, `urgency_assessed_at`, `urgency_attempted_at`, never-lowers trigger, backfill |
| **V19** | B1 (F2) | `observation_value_verdict` — verdict columns, never-softens trigger, four CHECKs |
| **V20** | B4 (F5) | `retake_reason_code_and_timeline_date` — `status_reason_code` (+ backfill, both CHECKs valid), generated `timeline_date`, index |
| **V21** | D3 (W4.2) | `document_upload_key` — `upload_key` + partial unique index |
| V22 | Phase 0 PR 8 (C1) | `inbound_message` (was V18) |
| V23 | Phase 0 PR 9 (B2) | `outbound_failure_kind` (was V19) |

`BaymaxSchemaMigrator` has no `outOfOrder`, so a lower number merged after a higher one fails Flyway validate at startup
and blocks every deploy. A10 therefore edits the roadmap's allocation table (lines 72–80) and the Phase 0 plan's merge
table (rows 8 and 9) in the same commit as V18, citing the PO direction of 2026-10-19 (web first) as the reason, and
records there that Phase 0 PRs 7–10 rebase after this web chain: PR 7 then reads `DocumentUrgencyUseCase.current` for
its standing urgency instead of recomputing it, and PR 9 adds its FAILED replies against the F5 writers. No new table is
added by V18–V21: every column sits on `document` or `observation`, which both delete paths remove, and each migration
PR runs `FamilyDeletionAcceptanceTest` (its targets come from `information_schema`).

## File map (by PR)

Java under `baymax/src/main/java/com/elioo/baymax/`, tests under `baymax/src/test/java/com/elioo/baymax/`, resources
under `baymax/src/main/resources/`. `ui/` means `web/adapter/in/ui/`.

| PR | Create | Modify |
|---|---|---|
| A1 | `ui/{WebPages,PageChrome,LandingPages,SignInPages,AppTabs,DocumentPages,ProfilePages,OptOutPages,UploadUiHandler,AssetHandler}.java`; tests `ui/{WebUiTestKit,RouteTable,PageFixtures,WebPagesCharacterizationTest}.java`, `baymax/characterization/*.txt` (test resources) | `ui/BaymaxWebUiRouter.java` (compose `List<WebPages>`); delete `ui/WebUiHandler.java`; the six `new WebUiHandler(` test sites; rename `WebUiHandlerRenderTest` → `DocumentPagesRenderTest` |
| A2 | `baymax/ui/css/{00-tokens,10-base,20-shell,30-family,40-papers,50-chat,60-result,70-upload,90-landing}.css`; `baymax/ui-{shell,family,papers,chat,result,upload}_{bn,en}.properties`; tests `ui/{GlossaryTest,AssetListTest,NoBanglaInJavaLiteralsTest}.java` | `ui/AssetHandler.java` (concatenated stylesheet, one asset per line), `ui/UiCopy.java` (parts), `ui/Html.java` (`Head`), `UiCopyTest`, `DesignTokensTest`; delete `baymax/ui/baymax.css` |
| A3 | `ui/Tokens.java`; tests `ui/{TokenContrastTest,TypeScaleTest,TouchTargetTest}.java` | `css/00-tokens.css`, `css/10-base.css`, `css/90-landing.css`, `Html.java` (head), `DesignTokensTest`; delete `WebPagesCharacterizationTest` and its snapshots |
| A4 | `web/adapter/in/BaymaxSecurityHeadersFilter.java`; tests `medscribe-ai/src/test/java/com/elioo/healthcare/core/filters/BaymaxPathCspTest.java`, `ui/NoInlineCodeTest.java` | the medscribe-ai context test (+1 case) |
| A5 | `ui/{Tab,Icons,AppShell,ActivePatient}.java`; test `ui/AppShellRoutesTest.java` | `ui/AppTabs.java`, `ui/DocumentPages.java`, `ui/ProfilePages.java`, `Html.java` (`appPage`), `css/20-shell.css`, `ui-shell_*`, `PageFixtures`, `BanglaNumbersOnPagesTest` |
| A6 | `ui/FamilyView.java`; test `ui/FamilyViewTest.java` | `ui/AppTabs.java`, `ui/ProfilePages.java`, `css/30-family.css`, `ui-family_*`, `WebUiTestKit` |
| A7 | `healthrecord/domain/DocumentAllowance.java`, `web/domain/UploadOptions.java`, `web/application/port/in/UploadUseCase.java`, `web/application/service/UploadService.java`, `ui/LocalPath.java`; tests `web/application/service/UploadServiceTest.java`, `ui/{AddReportSheetPageTest,LocalPathTest}.java` | `FreeTierUseCase`, `FreeTierService`, `FreeTierServiceTest`, `ui/UploadUiHandler.java`, `ui/AppTabs.java` (form → link), `config/BaymaxProperties.java` (+`Upload`), `application.properties`, `css/70-upload.css`, `ui-upload_*` |
| A8 | tests `ui/{AddReportSubmitTest,TryDocumentsThroughTheWebTest,UploadEntryPointsTest,NoDeveloperTextOnPagesTest}.java`, `testsupport/TryDocuments.java` | `ui/UploadUiHandler.java` (submit, legacy, refusals), `ui/UiCopy.java` (+`plain`), `BaymaxWebUiRouter.htmlErrors`, `TimelineService` (upload delegates → removed), `TimelineUseCase`, `ui-upload_*`, `ui_*` (`app.err.freetier`) |
| A9 | `web/domain/{ViewerLocale,ReadingView}.java`, `web/application/port/in/ResultUseCase.java`, `web/application/service/ResultService.java`, `baymax/ui/progress.js`; tests `ui/{ReadingProgressRenderTest,DocumentRoutesTest}.java`, `web/application/service/ResultServiceTest.java`, `ui/UiCopyTimeTest.java` | `ui/DocumentPages.java` (progress, status), `ui/AssetHandler.java`, `ui/UiCopy.java` (+`time`), `css/60-result.css`, `ui-result_*` |
| A10 | `V18__document_stored_urgency.sql`; `common/time/PatientCalendar.java`; `outbound/domain/StoredUrgency.java`; `outbound/application/port/in/DocumentUrgencyUseCase.java`; `outbound/application/port/out/DocumentUrgencyPort.java`; `outbound/application/service/StoredUrgencyService.java`; `outbound/adapter/out/persistence/{PostgresDocumentUrgencyAdapter,UrgencyReasonsJson}.java`; tests `outbound/adapter/out/persistence/DocumentUrgencyNeverLowersTest.java`, `outbound/application/service/{StoredUrgencyServiceTest,InMemoryDocumentUrgency,PatientCalendarRulesAgreeTest}.java`, `ui/UrgencyCopyTest.java`, `architecture/NoUrgencyColumnInEntitiesTest.java` | `UrgencyService.java:96` (Dhaka), `DocumentProcessingChain.java` (`explainOnly`), `PostgresOutboundMessageAdapter` (reasons codec moves), `ui/UiCopy.java` (+`urgency`), `ui/DocumentPages.java` (interim banner), `TimelineService.document`, `extraction/domain/DocumentView.java` (+`urgency`), `DocumentProcessingChainTest`, `DocumentPagesRenderTest`, roadmap and Phase 0 plan tables |
| A11 | `outbound/adapter/in/UrgencySweepJob.java`; test `architecture/OneUrgencyWriterTest.java` | `ExplanationService.java:63-70,94,104-112,148-156`, `RecropHandler.java`, `StoredUrgencyService` (+`sweep`), `PostgresDocumentUrgencyAdapter` (+`needingAssessment`, `markAttempt`), `ExplanationServiceTest`, `DetailDeliveryGuardsTest`, `HexagonalArchitectureTest`, `application.properties` |
| A12 | `web/domain/{GateState,ResultView}.java`; tests `ui/{ResultFixtures,ResultSummaryRenderTest}.java` | `ResultUseCase`, `ResultService`, `ResultServiceTest`, `ui/DocumentPages.java` (summary, banner, gate line), `config/BaymaxProperties.java` (`reviewTypicalHours`), `css/60-result.css`, `ui-result_*`, `DocumentPagesRenderTest` (interim banner test removed) |
| A13 | test `WebOnlyJourneyAcceptanceTest.java` (package `com.elioo.baymax`) | — |
| B1 | `V19__observation_value_verdict.sql`; `outbound/domain/{ValueVerdict,UrgencyReason}.java`; tests `outbound/application/service/ValueVerdictTest.java`, `outbound/adapter/out/persistence/ValueVerdictNeverSoftensTest.java` | `UrgencyAssessment`, `UrgencyService.java:58-124`, `MarkerThreshold` (+`side`), `StoredUrgency` (+`values`), `PostgresDocumentUrgencyAdapter` (transaction), `ExplanationService` (`standout`/`kindOf`), `ObservationEntity` (read-only verdict fields), `ui/DocumentPages.java` (interim chip), `ui-result_*` |
| B2 | `ui/ValueRows.java`; tests `outbound/application/service/PinPercentTest.java`, `ui/{ValueRowRenderTest,PaperIsNeverMediooTest}.java` | `UrgencyService` (+`pinPercent`), `ui/DocumentPages.java` (rows, medicines, doctor, next), `css/60-result.css`, `ui-result_*`, `ResultSummaryRenderTest` |
| B3 | test `ui/ResultNowRenderTest.java` | `ui/DocumentPages.java` (`REASON_BLOCKS`, now body), `ui/UiCopy.java` (+`raw`), `css/60-result.css`, `ui-result_*` |
| B4 | `V20__retake_reason_code_and_timeline_date.sql`; `extraction/domain/RetakeReason.java`; tests `extraction/adapter/out/persistence/DocumentReasonCodeConstraintTest.java`, `ui/RetakeReasonsHaveCopyTest.java`, `extraction/domain/DocumentViewNeverCarriesProseTest.java`, `web/adapter/in/handler/NoProseInTheJsonApiTest.java` | `Document` (+`statusReasonCode`), `DocumentEntity`, `PostgresDocumentRecordAdapter.writeDocument`, `DocumentExtractionService` (four reason constants + codes), `DocumentView` (`reason` → `reasonCode`), `ui/DocumentPages.java` (interim line), `ui-result_*`, `DocumentExtractionServiceTest`, `DocumentPagesRenderTest` |
| B5 | `extraction/application/port/out/DocumentReadPort.java`, `extraction/adapter/out/persistence/PostgresDocumentReadAdapter.java`; tests `extraction/adapter/out/persistence/RetakeOrdinalAndFreeTierTest.java`, `ui/NeedsRetakeRenderTest.java` | `ResultService` (ordinal), `ui/DocumentPages.java` (retake, failed), `css/60-result.css`, `ui-result_*` |
| B6 | `web/domain/EarlierReading.java`; tests `extraction/adapter/out/persistence/EarlierReadingsTest.java`, `ui/DocumentDetailRenderTest.java` | `DocumentReadPort` (+`readingsOf`), `ResultUseCase`/`ResultService` (+`earlierReadings`), `ui/DocumentPages.java` (`/all`, `/values/{obs}`), `css/60-result.css`, `ui-result_*` |
| C1 | — | `ui/ActivePatient.java` (cookie), `ui/AppShell.java` (switcher sheet), `ui/AppTabs.java`, `ui/DocumentPages.java` (legacy 302), `AppShellRoutesTest`, `css/20-shell.css`, `ui-shell_*` |
| C2 | `web/domain/{TimelineCursor,TimelineFilter}.java`, `web/application/port/out/TimelineReadPort.java`, `web/adapter/out/persistence/PostgresTimelineReadAdapter.java`, `ui/TimelineSummary.java`; tests `web/adapter/out/persistence/TimelineReadModelTest.java`, `web/domain/TimelineCursorTest.java`, `ui/TimelineSummaryTest.java` | `web/domain/TimelineEntry.java` (+urgency, outside, reviewState, retakeReasonCode, `chip()`), `TimelineService.timeline`, `TimelineUseCase`, `TimelineApiHandler`, `messages_bn/en` (`urgency.*.short`), `TimelineServiceTest`, `ui-papers_*` |
| C3 | `ui/{TimelineView,UrgencyChip}.java`; test `ui/TimelineViewTest.java` | `ui/AppTabs.java` (papers body), `ui/UiCopy.java` (+`monthYear`, `dayMonth`), `outbound/application/service/BanglaDate.java` (+`monthYear`), `css/40-papers.css`, `ui-papers_*`, `BanglaNumbersOnPagesTest` |
| C4 | `web/domain/AttentionItem.java`, `web/application/port/in/AttentionUseCase.java`, `web/application/service/AttentionService.java`, `ui/ChatView.java`; tests `web/application/service/AttentionServiceTest.java`, `nudge/adapter/out/persistence/FollowUpsOfPatientOnPostgresTest.java`, `ui/ChatViewTest.java` | `NudgeDataPort` (+`openFollowUpsOfPatient`), `PostgresNudgeDataAdapter`, `TimelineReadPort`/adapter (+`inFlightAndRetakes`), `ui/AppTabs.java`, `WebUiTestKit`, `css/50-chat.css`, `ui-chat_*`, `AppShellRoutesTest` |
| D1 | `scripts/fonts/build-ui-fonts.sh`, `baymax/ui/{noto-sans-bengali,noto-sans-latin}.woff2`, `baymax/ui/OFL-Noto.txt`; tests `ui/{FirstLoadBudgetTest,FontCoverageTest}.java` | `ui/AssetHandler.java` (+woff2), `css/00-tokens.css` (`@font-face`) |
| D2 | `common/text/NameGrammar.java`; test `common/text/NameGrammarTest.java` | `ui/UiCopy.java` (+`poss`), every bundle part, `messages_bn.properties:47`, `GlossaryTest` (ratchet emptied), `UiCopyTest`, `BanglaNumbersOnPagesTest` |
| D3 | `V21__document_upload_key.sql`, `extraction/domain/CreatedDocument.java`, `web/domain/Grouping.java`; test `extraction/adapter/out/persistence/DocumentUploadKeyTest.java` | `DocumentRecordPort` (+`createOnce`, `findByUploadKey`), `PostgresDocumentRecordAdapter`, `DocumentEntity` (`uploadKey`), `DocumentRepository`, `DocumentIntakeUseCase`/`DocumentIntakeService`, `UploadUseCase`/`UploadService`, `ui/UploadUiHandler.java`, tests |
| D4 | `baymax/ui/upload.js`, `baymax/src/test/js/upload.test.mjs`; test `ui/UploadScriptTest.java` | `ui/AssetHandler.java`, `ui/UploadUiHandler.java`, `css/70-upload.css`, `ui-upload_*`, `.github/workflows/ci-cd.yml` (`node --test`) |
| D5 | `scripts/make-downscaled-corpus.py`, `scripts/test_downscaled_corpus_guard.py` | `docs/DECISIONS.md`, `application.properties` (after the measurement) |
| E1 | `ui/DeleteFamilyPages.java`; test `family/WebDeletionAcceptanceTest.java` | `ui/FamilyView.java`, `ui-family_*` |
| E2 | `ui/RemindersPage.java`; tests `ui/RemindersPageTest.java`, `nudge/adapter/out/persistence/OptBackInTest.java` | `NudgeOptOutUseCase`/port (+`optIn`), `ui/FamilyView.java`, `ui-family_*` |

## Preflight — Tanvir before he sleeps, Claude for the documents (no PR)

### Task P.1: Free-tier and patient headroom

`baymax.free.max-patients=1` and `baymax.free.max-docs-per-month=3` (`application.properties:159-160`) count DONE
documents (`FreeTierService.java:50`). The morning checklist uploads about 25 synthetic papers, so the 4th DONE paper
would stop it.

- [ ] **Step 1 (Tanvir, prod):** `cp ~/medscribe.env ~/medscribe.env.bak-$(date +%Y%m%d-%H%M)`; set
  `BAYMAX_FREE_MAX_DOCS_PER_MONTH=60`; redeploy the running SHA (`IMAGE_TAG=<sha on main> bash ~/deploy.sh`); list the
  change in the build report; revert it at the Phase 5 entry. The cap screens are then tried **local** only.
- [ ] **Step 2 (decision, Tanvir):** keep **one** patient in production. A second patient makes every WhatsApp photo
  `AMBIGUOUS_PATIENT` until Phase 5 (Phase 0 plan, P.1 step 2). Every two-patient try (switcher, "কার কাগজ?", never
  preselect) is therefore **local**, with the second patient made through the admin API:
  `curl -fsS -X POST -H "X-Baymax-Admin-Token: $TOKEN" -H 'Content-Type: application/json' -d '{"name":"আব্বু","age":72,"sex":"male"}' http://localhost:8086/api/v1/baymax/families/<id>/patients`
  (with `BAYMAX_FREE_MAX_PATIENTS=3` in `.env.local`). If Tanvir prefers the production switcher, he adds the patient
  the same way on the box with `BAYMAX_FREE_MAX_PATIENTS=2` and accepts the WhatsApp ambiguity; the build report says
  which.

### Task P.2: The local run recipe (used by every local try)

- [ ] `docker compose up -d`; `set -a; source .env.local; set +a`; export `BAYMAX_WA_ENABLED=false`,
  `BAYMAX_AUTH_WEB_MINTS_CODE=true`, `LOGGING_LEVEL_COM_ELIOO_BAYMAX_WEB_ADAPTER_OUT_DELIVERY=DEBUG`,
  `BAYMAX_FREE_MAX_PATIENTS=3`; `./gradlew :medscribe-ai:bootRun`; sign in at http://localhost:8086/app with the local
  family's number and read the code from the log line `otp fallback … code=`. That validator combination (web mints
  only while WhatsApp is off, `WaConfigValidator.java:75`) is the supported laptop path.

### Task P.3: The V18 table-owner check (Tanvir, read-only, 2 minutes)

- [ ] `SELECT tableowner FROM pg_tables WHERE schemaname='baymax' AND tablename IN ('document','observation');` against
  Supabase (his explicit, read-only use of `.env.supabase`). Both must print the Flyway role. Paste the output into the
  build report. Supporting evidence the agent cites in A10's PR body either way: `V4__family_account_patient_profile_share_member.sql:38-62`
  already created two functions and two triggers in production through the same Flyway role, and `document` was
  created by `V5`. If P.3 is not in the report when A10 is ready, the agent stops before merging A10 and continues
  nothing else (every later PR depends on it).

### Task P.4: Synthetic try documents (Claude, outside the repo)

DR-36: fictional only, in `~/medioo-try-docs/` (t1–t6 exist from Phase 0 P.2; their HTML sources are in `src/`).

- [ ] Write the new HTML sources in `~/medioo-try-docs/src/` (patient "Rokeya Begum", 68, "Shapla Diagnostics",
  October 2026) and render each with `--headless --screenshot --window-size=1240,1754`:

  | File | Content | Used by |
  |---|---|---|
  | `t7-now-lab.jpg` | Potassium 11.0 mmol/L against 3.5–5.1 (≥ 2× high: stopgap NOW), others inside | A10, A12, B2, B3, C3, C4 |
  | `t8-blurred.jpg` | t1 with a 12 px Gaussian blur (ImageMagick `-blur 0x12`) | B4, B5 |
  | `t9-rx-follow-up-soon.jpg` | Prescription, no printed date, "Next visit: <today + 3 days, dd/mm/yy>" | C3 (undated), C4 (follow-up card) |
  | `t10-urgent-phrase.jpg` | A lab report with "URGENT — please contact the physician" printed on it | B3 |
  | `t11-qualitative.jpg` | Urine: Albumin "Trace" against "Nil", Glucose "Positive", one one-sided range "<100" | B1, B2, B6 |

- [ ] Tanvir copies them to his phone with the t1–t6 set.

---

## PR A1 — R1: one web handler per screen (move only)

Branch `refactor/baymax-web-handlers`. **Nothing a family sees changes.** Every area of this plan edits
`WebUiHandler.java` (876 lines at `e6264a4`): the result screens remove `render`, the shell moves `home` and
`timelinePage`, upload replaces `upload`/`readBytes`, the CSP and fonts touch `ASSETS` and `asset`. R1 moves each method,
unchanged, into the handler for its screen. Each handler publishes its own routes, so the literal-before-`{id}`
ordering lives inside `DocumentPages` and nowhere else. R1 also builds the three test helpers every later derived check
uses: `RouteTable` (the router's own route list), `PageFixtures` (one fixture per route; a route without one fails)
and `WebUiTestKit` (the only place a test builds web handlers).

| New class | Moved from `WebUiHandler` (method names at `e6264a4`) |
|---|---|
| `PageChrome` | `BASE`, `ELIOO`, `CONTACT_EMAIL`, `app(...)`, `errorPage`, `text`, `top`, `langToggle`, `documentType`, `contextSection`, `status`, `uuid`, `imageHref`; `switchLang` |
| `LandingPages` | `landing`, `day`, `me`, `mio`, `paper`, `tag` |
| `SignInPages` | `LOGIN_COOKIE`, `SENT_MARK`, `LOGIN_COOKIE_TTL`, `loginPage`, `numberPage`, `login`, `verifyPage` (both), `waLink`, `verify`, `refusal`, `logout`, `loginValue`, `loginHash`, `loginTail`, `loginSent`, `loginCookie` |
| `AppTabs` | `home`, `timelinePage` |
| `DocumentPages` | `documentPage`, `render` (both), `section`, `pageImages`, `deleteLink`, `confirmDelete`, `delete`, `image`, `pageImage` |
| `ProfilePages` | `FLAGS`, `profilePage` (both), `saveProfile`, `optOutFromProfile` |
| `OptOutPages` | `optOutPage`, `optOutApply`, `badLink` |
| `UploadUiHandler` | `upload`, `readBytes` |
| `AssetHandler` | `ASSETS`, `asset` |

### Task R1.1: `RouteTable`, `PageFixtures`, `WebUiTestKit` and the characterization snapshot, on the code before the move

**Files:** create tests `ui/RouteTable.java`, `ui/PageFixtures.java`, `ui/WebUiTestKit.java`,
`ui/WebPagesCharacterizationTest.java`. Modify the six `new WebUiHandler(` sites to use the kit:
`BanglaNumbersOnPagesTest`, `BaymaxWebUiRouterTest`, `MediooBrandTest`, `SignInIsFreeAcceptanceTest`, `SignInPagesTest`,
`WebUiHandlerRenderTest` (`git grep -n 'new WebUiHandler(' -- baymax/src/test` lists exactly these six).

- [ ] **Step 1: `RouteTable`** — the route list read from the router, never typed:

```java
package com.elioo.baymax.web.adapter.in.ui;

import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.RequestPredicate;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Every (method, pattern) the web UI router publishes, read from the router with its own visitor API. */
final class RouteTable {

    record Route(HttpMethod method, String pattern) {
        String key() {
            return method.name() + " " + pattern;
        }
    }

    private RouteTable() {
    }

    static List<Route> of(RouterFunction<?> router) {
        List<Route> routes = new ArrayList<>();
        router.accept(new RouterFunctions.Visitor() {
            @Override public void startNested(RequestPredicate predicate) { }
            @Override public void endNested(RequestPredicate predicate) { }
            @Override public void route(RequestPredicate predicate, HandlerFunction<?> handler) {
                Collector c = new Collector();
                predicate.accept(c);
                if (c.path == null || c.methods.isEmpty()) {
                    throw new IllegalStateException("a route without a method and a path: " + predicate);
                }
                c.methods.forEach(m -> routes.add(new Route(m, c.path)));
            }
            @Override public void resources(Function<ServerRequest, Mono<Resource>> lookup) {
                throw new IllegalStateException("resource routes are not used by the web UI");
            }
            @Override public void attributes(Map<String, Object> attributes) { }
            @Override public void unknown(RouterFunction<?> routerFunction) {
                throw new IllegalStateException("a router the table cannot read: " + routerFunction);
            }
        });
        return List.copyOf(routes);
    }

    private static final class Collector implements RequestPredicates.Visitor {
        final Set<HttpMethod> methods = new LinkedHashSet<>();
        String path;

        @Override public void method(Set<HttpMethod> m) { methods.addAll(m); }
        @Override public void path(String pattern) { path = pattern; }
        @Override public void pathExtension(String extension) { }
        @Override public void header(String name, String value) { }
        @Override public void queryParam(String name, String value) { }
        @Override public void startAnd() { }
        @Override public void and() { }
        @Override public void endAnd() { }
        @Override public void startOr() { }
        @Override public void or() { }
        @Override public void endOr() { }
        @Override public void startNegate() { }
        @Override public void endNegate() { }
        @Override public void unknown(RequestPredicate predicate) {
            throw new IllegalStateException("a predicate the table cannot read: " + predicate);
        }
    }
}
```

- [ ] **Step 2: `WebUiTestKit`** (today it wraps `new WebUiHandler(auth, timeline, sessions, copy, optOut, nudgeData, properties)`;
  Task R1.2 changes only its body). Fixed ids and a fixed instant, so every page renders the same bytes every run:

```java
package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.nudge.application.port.in.NudgeOptOutUseCase;
import com.elioo.baymax.nudge.application.port.out.NudgeDataPort;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;
import com.elioo.baymax.web.application.port.in.WebAuthUseCase;
import com.elioo.baymax.web.domain.FamilyOverview;
import com.elioo.baymax.web.domain.PatientSummary;
import com.elioo.baymax.web.domain.WebSession;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Builds the web UI under test once. A constructor change touches this file and the class, never every test. */
final class WebUiTestKit {

    static final Instant NOW = Instant.parse("2026-10-18T09:00:00Z");
    static final String OWNER_NUMBER = "+8801700000000";
    static final UUID FAMILY = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100001");
    static final UUID MA_ID = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100002");
    static final UUID DOC_ID = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100003");

    final WebAuthUseCase auth;
    final TimelineUseCase timeline = mock(TimelineUseCase.class);
    final NudgeOptOutUseCase optOut = mock(NudgeOptOutUseCase.class);
    final NudgeDataPort nudgeData = mock(NudgeDataPort.class);
    final BaymaxProperties properties;
    final UiCopy copy = new UiCopy();
    final SessionAuthFilter sessions;
    final RouterFunction<ServerResponse> router;
    final WebTestClient client;

    WebUiTestKit() {
        this(mock(WebAuthUseCase.class), new BaymaxProperties(), true);
    }

    /** A real auth service (SignInIsFreeAcceptanceTest) and its properties; {@code stubSession=false} leaves auth alone. */
    WebUiTestKit(WebAuthUseCase auth, BaymaxProperties properties, boolean stubSession) {
        this.auth = auth;
        this.properties = properties;
        if (stubSession) {
            when(auth.authenticate(anyString())).thenReturn(Mono.empty());
            when(auth.authenticate("tok")).thenReturn(Mono.just(new WebSession(
                    UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4100009"), FAMILY, NOW, NOW, NOW.plusSeconds(600))));
        }
        this.sessions = new SessionAuthFilter(auth, properties);
        this.router = buildRouter();
        this.client = WebTestClient.bindToRouterFunction(router).build();
    }

    /** R1.1: today's single handler. R1.2 replaces this body with the per-screen handlers; nothing else changes. */
    private RouterFunction<ServerResponse> buildRouter() {
        WebUiHandler ui = new WebUiHandler(auth, timeline, sessions, copy, optOut, nudgeData, properties);
        return new BaymaxWebUiRouter().baymaxWebUiRoutes(ui, sessions);
    }

    PatientSummary ma(long documents) {
        return new PatientSummary(new PatientProfile(MA_ID, FAMILY, "আম্মা", 64, PatientProfile.Sex.FEMALE,
                List.of(), NOW, NOW), true, documents, documents == 0 ? null : NOW);
    }

    PatientSummary patient(String name, boolean owner, long documents) {
        UUID id = UUID.nameUUIDFromBytes(("patient:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new PatientSummary(new PatientProfile(id, FAMILY, name, 64, PatientProfile.Sex.FEMALE, List.of(), NOW, NOW),
                owner, documents, documents == 0 ? null : NOW);
    }

    void familyWith(PatientSummary... patients) {
        when(timeline.me(FAMILY)).thenReturn(Mono.just(new FamilyOverview(
                new FamilyAccount(FAMILY, OWNER_NUMBER, "Tanvir", FamilyAccount.Plan.FREE, NOW, NOW), List.of(patients))));
    }

    /** A signed-in GET, in a language; 200 expected. */
    String page(String path, String lang) {
        return client.get().uri(path).cookie("baymax_session", "tok").cookie("baymax_lang", lang)
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
    }

    /** A GET with no session (landing, sign-in); 200 expected. */
    String open(String path, String lang) {
        return client.get().uri(path).cookie("baymax_lang", lang)
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
    }

    /** The bytes the browser receives for an asset path, after asserting 200: what every CSS and weight check reads. */
    byte[] served(String path) {
        return client.get().uri(path).exchange().expectStatus().isOk().expectBody(byte[].class).returnResult().getResponseBody();
    }

    String servedText(String path) {
        return new String(served(path), java.nio.charset.StandardCharsets.UTF_8);
    }
}
```

  Move each of the six test files onto the kit (their constructor lines only; no assertion changes).
  `SignInIsFreeAcceptanceTest` keeps its real `WebAuthService` through `new WebUiTestKit(auth, props, false)`.

- [ ] **Step 3: `PageFixtures`** — one entry per route the router publishes; Task R1.1 Step 4's test fails on a route
  that has none, so every later PR that adds a route adds its fixture in the same commit:

```java
package com.elioo.baymax.web.adapter.in.ui;

import org.springframework.http.HttpMethod;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * How to call each route with fixed data. Keyed by RouteTable.Route.key() ("GET /app/documents/{id}"). A fixture names
 * the concrete URI, whether it needs a session, a form or multipart body, and the stubs it needs on the kit.
 */
final class PageFixtures {

    enum Body { NONE, FORM, MULTIPART }

    record Fixture(String uri, boolean signedIn, Body body, Map<String, String> form, Consumer<WebUiTestKit> stubs) {
        static Fixture get(String uri, boolean signedIn, Consumer<WebUiTestKit> stubs) {
            return new Fixture(uri, signedIn, Body.NONE, Map.of(), stubs);
        }
    }

    static final Map<String, Fixture> ALL = new LinkedHashMap<>();

    static {
        String doc = WebUiTestKit.DOC_ID.toString();
        String ma = WebUiTestKit.MA_ID.toString();
        put("GET /", Fixture.get("/", false, k -> { }));
        put("GET /lang/{code}", Fixture.get("/lang/en?back=%2Fapp%2Fhome", false, k -> { }));
        put("GET /assets/{file}", Fixture.get("/assets/baymax.css", false, k -> { }));
        put("GET /baymax", Fixture.get("/baymax", false, k -> { }));
        put("GET /baymax/{*rest}", Fixture.get("/baymax/patients/" + ma + "?x=1", false, k -> { }));
        put("GET /app/", Fixture.get("/app/", false, k -> { }));
        put("GET /app", Fixture.get("/app", false, k -> { }));
        put("GET /app/verify", Fixture.get("/app/verify", false, k -> { }));
        put("GET /app/nudges/opt-out", Fixture.get("/app/nudges/opt-out?t=not-a-token", false, k -> { }));
        put("GET /app/home", Fixture.get("/app/home", true, Stubs::familyOfMa));
        put("GET /app/patients/{id}", Fixture.get("/app/patients/" + ma, true, Stubs::maTimeline));
        put("GET /app/patients/{id}/profile", Fixture.get("/app/patients/" + ma + "/profile", true, Stubs::maProfile));
        put("GET /app/documents/{id}/delete", Fixture.get("/app/documents/" + doc + "/delete", true, Stubs::doneLab));
        put("GET /app/documents/{id}/image", Fixture.get("/app/documents/" + doc + "/image?key=f%2Fp%2Fd%2Fcrop-1.jpg", true, Stubs::signedUrls));
        put("GET /app/documents/{id}/page/{n}", Fixture.get("/app/documents/" + doc + "/page/1", true, Stubs::signedUrls));
        put("GET /app/documents/{id}", Fixture.get("/app/documents/" + doc, true, Stubs::doneLab));
        put("POST /app/login", new Fixture("/app/login", false, Body.FORM, Map.of("whatsapp_number", "01700000000", "confirmed", "1"), Stubs::noCode));
        put("POST /app/verify", new Fixture("/app/verify", false, Body.FORM, Map.of("code", "123456"), Stubs::noCode));
        put("POST /app/logout", new Fixture("/app/logout", true, Body.FORM, Map.of(), k -> { }));
        put("POST /app/nudges/opt-out", new Fixture("/app/nudges/opt-out", false, Body.FORM, Map.of("t", "not-a-token", "scope", "patient"), k -> { }));
        put("POST /app/patients/{id}/upload", new Fixture("/app/patients/" + ma + "/upload", true, Body.MULTIPART, Map.of(), Stubs::acceptsUpload));
        put("POST /app/patients/{id}/profile", new Fixture("/app/patients/" + ma + "/profile", true, Body.FORM, Map.of("flag", "diabetes"), Stubs::maProfile));
        put("POST /app/patients/{id}/nudges/opt-out", new Fixture("/app/patients/" + ma + "/nudges/opt-out", true, Body.FORM, Map.of("scope", "patient"), Stubs::maProfile));
        put("POST /app/documents/{id}/delete", new Fixture("/app/documents/" + doc + "/delete", true, Body.FORM, Map.of(), Stubs::deletes));
    }

    private PageFixtures() {
    }

    static void put(String key, Fixture f) {
        if (ALL.put(key, f) != null) {
            throw new IllegalStateException("two fixtures for " + key);
        }
    }

    static Fixture of(RouteTable.Route route) {
        Fixture f = ALL.get(route.key());
        if (f == null) {
            throw new AssertionError("route " + route.key() + " has no fixture in PageFixtures: add one in the PR that adds the route");
        }
        return f;
    }

    static HttpMethod method(String key) {
        return HttpMethod.valueOf(key.substring(0, key.indexOf(' ')));
    }
}
```

  `Stubs` (a nested final class in the same file) holds the named stub methods. Each stubs exactly what its page calls on
  the kit's mocks with fixed values: `familyOfMa` (`kit.familyWith(kit.ma(3))`), `maTimeline` (plus a
  `TimelinePage` of two fixed `TimelineEntry` rows, one DONE and one PROCESSING), `maProfile` (plus
  `nudgeData.patient(MA_ID)` and `timeline.updateChronicFlags`), `doneLab` (a DONE `DocumentView` with two values, one
  medicine, one follow-up, every `crop_key` fixed, and `explanationOf` → a fixed Bangla sentence), `signedUrls`
  (`imageUrl`/`pageUrl` → `URI.create("https://storage.example/signed")`), `noCode` (`auth.requestCode`/`verify` →
  their "not sent" and "wrong code" answers), `acceptsUpload` (`timeline.upload` → a received `Document` with `DOC_ID`),
  `deletes` (`timeline.deleteDocument` → a fixed `DeletionReport`). Copy the stubbing lines from the six existing tests
  that already render these pages (`BaymaxWebUiRouterTest`, `WebUiHandlerRenderTest`, `BanglaNumbersOnPagesTest`).

- [ ] **Step 4: `WebPagesCharacterizationTest`** — every route, rendered from its fixture, compared byte for byte with a
  snapshot recorded on the code **before** the move:

```java
package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R1 and R2 are move-only. This test proves it: every route the router publishes (derived, never listed), called with
 * its fixture, answers the same status, Location and body bytes as the snapshot recorded before the move. Lives for R1
 * and R2 only; L1 (A3) deletes it, because L1 is the first PR that changes pages on purpose.
 *
 * Record (on the pre-move commit only): ./gradlew :baymax:test --tests '*WebPagesCharacterizationTest' -Dbaymax.characterize.record=true
 */
class WebPagesCharacterizationTest {

    private static final Path SNAPSHOTS = Path.of("src/test/resources/baymax/characterization");
    private static final boolean RECORD = Boolean.getBoolean("baymax.characterize.record");

    @Test
    void everyRouteAnswersExactlyWhatItAnsweredBeforeTheMove() throws IOException {
        List<RouteTable.Route> routes = RouteTable.of(new WebUiTestKit().router);
        assertThat(routes).as("routes read from the router; an empty or short read would pass vacuously").hasSizeGreaterThanOrEqualTo(20);
        List<String> diffs = new ArrayList<>();
        for (RouteTable.Route route : routes) {
            WebUiTestKit kit = new WebUiTestKit();
            PageFixtures.Fixture f = PageFixtures.of(route);
            f.stubs().accept(kit);
            String actual = render(kit, route, f);
            Path file = SNAPSHOTS.resolve(route.key().replaceAll("[^A-Za-z0-9]+", "_") + ".txt");
            if (RECORD) {
                Files.createDirectories(SNAPSHOTS);
                Files.writeString(file, actual, StandardCharsets.UTF_8);
                continue;
            }
            assertThat(file).as("snapshot for " + route.key()).exists();
            String expected = Files.readString(file, StandardCharsets.UTF_8);
            if (!expected.equals(actual)) {
                diffs.add(route.key() + " changed at char " + firstDifference(expected, actual) + ":\n  was: "
                        + around(expected, firstDifference(expected, actual)) + "\n  now: " + around(actual, firstDifference(expected, actual)));
            }
        }
        assertThat(RECORD).as("recording mode never passes as a check").isFalse();
        assertThat(diffs).isEmpty();
    }

    static String render(WebUiTestKit kit, RouteTable.Route route, PageFixtures.Fixture f) {
        WebTestClient.RequestBodySpec spec = kit.client.method(route.method()).uri(f.uri());
        if (f.signedIn()) {
            spec = spec.cookie("baymax_session", "tok");
        }
        WebTestClient.RequestHeadersSpec<?> ready = switch (f.body()) {
            case NONE -> spec;
            case FORM -> spec.contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(new LinkedMultiValueMap<>(f.form().entrySet().stream()
                            .collect(java.util.stream.Collectors.toMap(e -> e.getKey(), e -> List.of(e.getValue()))))));
            case MULTIPART -> {
                MultipartBodyBuilder body = new MultipartBodyBuilder();
                body.part("file", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}).filename("page-1.jpg");
                yield spec.body(BodyInserters.fromMultipartData(body.build()));
            }
        };
        EntityExchangeResult<byte[]> r = ready.exchange().expectBody(byte[].class).returnResult();
        String location = r.getResponseHeaders().getFirst(HttpHeaders.LOCATION);
        return r.getStatus().value() + "\nLocation: " + (location == null ? "-" : location) + "\n\n"
                + (r.getResponseBody() == null ? "" : new String(r.getResponseBody(), StandardCharsets.UTF_8));
    }

    private static int firstDifference(String a, String b) {
        int i = 0;
        while (i < a.length() && i < b.length() && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return i;
    }

    private static String around(String s, int at) {
        return s.substring(Math.max(0, at - 40), Math.min(s.length(), at + 40)).replace("\n", "⏎");
    }
}
```

- [ ] **Step 5: record twice, on the code before the move.** Run the record command twice; `git diff --stat --
  baymax/src/test/resources/baymax/characterization` after the second run prints nothing (the fixtures are
  deterministic; if it prints anything, fix the fixture — never normalise the body, or the check could not fail). Then
  run the test without `-D…record`: green. Commit the kit, helpers, the six test moves and the snapshots:
  `test(baymax-web): route table, page fixtures, a test kit and a snapshot of every route before the split`.

### Task R1.2: the move

**Files:** create the nine handlers and `ui/WebPages.java`; modify `ui/BaymaxWebUiRouter.java`; delete
`ui/WebUiHandler.java`; rename `WebUiHandlerRenderTest` → `DocumentPagesRenderTest` (`git mv`, then point its calls at
`kit`'s `DocumentPages`).

- [ ] **Step 1: the seam.**

```java
package com.elioo.baymax.web.adapter.in.ui;

import org.springframework.web.reactive.function.server.HandlerFilterFunction;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

/** One screen's routes. BaymaxWebUiRouter only composes these, in {@link #order()}; it routes nothing itself. */
public interface WebPages {

    /** Lower first. Open pages 10–19, signed-in pages 20–89, assets 90. */
    int order();

    /** {@code requireSession} is the router's session filter; a handler applies it to its signed-in routes only. */
    RouterFunction<ServerResponse> routes(HandlerFilterFunction<ServerResponse, ServerResponse> requireSession);
}
```

- [ ] **Step 2: move.** For each class in the table above: `@Component @RequiredArgsConstructor`, implements
  `WebPages`, holds exactly the fields its moved methods read, and its `routes(...)` contains exactly the lines of
  today's router (`BaymaxWebUiRouter.java:46-76`) that point at its methods, in today's order. `DocumentPages.routes`
  keeps `/documents/{id}/delete`, `/image`, `/page/{n}` before `/documents/{id}` with the existing comment. Method bodies
  are cut and pasted; the only edits allowed are `this.` → the field of the new class and calls into `PageChrome`
  (`chrome.top(...)`, `chrome.documentType(...)`). `BaymaxWebUiRouter`:

```java
    @Bean
    public RouterFunction<ServerResponse> baymaxWebUiRoutes(List<WebPages> pages, SessionAuthFilter sessions, PageChrome chrome) {
        HandlerFilterFunction<ServerResponse, ServerResponse> requireSession = (request, next) ->
                sessions.resolve(request)
                        .flatMap(session -> {
                            request.attributes().put(SessionAuthFilter.SESSION_ATTRIBUTE, session);
                            return next.handle(request);
                        })
                        .switchIfEmpty(Mono.defer(() -> Html.redirect(PageChrome.BASE + "/")));
        RouterFunction<ServerResponse> all = pages.stream().sorted(Comparator.comparingInt(WebPages::order))
                .map(p -> p.routes(requireSession)).reduce(RouterFunction::and)
                .orElseThrow(() -> new IllegalStateException("no web pages registered"));
        return all.filter((request, next) -> htmlErrors(chrome, request, next));
    }
```

  `htmlErrors` and `movedToApp` stay in the router, now calling `chrome.text` / `chrome.errorPage`. `OLD_BASE` routes
  (`/baymax`, `/baymax/{*rest}`) move into `LandingPages.routes` unchanged.
- [ ] **Step 3: the kit builds the handlers.** Replace `WebUiTestKit.buildRouter()`'s body:

```java
    private RouterFunction<ServerResponse> buildRouter() {
        PageChrome chrome = new PageChrome(copy, properties);
        documents = new DocumentPages(timeline, copy, chrome);
        List<WebPages> pages = List.of(new LandingPages(copy, chrome), new SignInPages(auth, sessions, copy, chrome, properties),
                new OptOutPages(optOut, copy, chrome), new AppTabs(timeline, copy, chrome), documents,
                new ProfilePages(timeline, optOut, nudgeData, copy, chrome), new UploadUiHandler(timeline, chrome),
                new AssetHandler());
        return new BaymaxWebUiRouter().baymaxWebUiRoutes(pages, sessions, chrome);
    }
```

  (`DocumentPages documents;` becomes a kit field so `DocumentPagesRenderTest` can call `kit.documents.render(...)`.)
  The constructor argument lists are whatever fields each moved method set needs; this snippet is the one place they
  are written down in tests.
- [ ] **Step 4: run.** `./gradlew :baymax:test` green, including `WebPagesCharacterizationTest` without re-recording.
  `HexagonalArchitectureTest` and `DesignTokensTest` stay green (`DesignTokensTest` walks the whole `ui` package).
- [ ] **Step 5: replay (mutant).** In `DocumentPages.pageImages`, change one character of the markup (for example add a
  space before `<img`). Run `WebPagesCharacterizationTest`; the red must name the route:
  `GET /app/documents/{id} changed at char <n>: was: …<img… now: … <img…`. Revert; green. Paste the red into the PR body.

### Task R1.3: ship

- [ ] `git grep -n 'WebUiHandler' -- baymax/src` prints nothing (no stale import, comment or test name).
- [ ] Standard pre-merge. Commit `refactor(baymax-web): one handler per screen, each publishing its own routes`.

### Try it (A1) — prod-web

- [ ] Sign in; open home, Ma's papers, a document, its delete confirmation (do not delete), the profile, and toggle
  English. Everything looks exactly as yesterday.

---

## PR A2 — R2: the stylesheet, the copy and the page head in parts; the glossary ratchet

Branch `refactor/baymax-web-parts`. Still move-only: `WebPagesCharacterizationTest` (A1) stays green without
re-recording, which proves the served stylesheet, every page and every string are byte-identical. After R2 each area
appends to its own CSS part and bundle part, so two PRs never conflict at the end of one shared file.

### Task R2.1: `baymax.css` is served as the ordered concatenation of its parts

**Files:** create `baymax/ui/css/{00-tokens,10-base,20-shell,30-family,40-papers,50-chat,60-result,70-upload,90-landing}.css`;
modify `ui/AssetHandler.java`, `ui/Html.java` (`CSS_HREF`), `DesignTokensTest`; delete `baymax/ui/baymax.css`.

- [ ] **Step 1: failing test** (add to `DesignTokensTest`; `TOKENS` becomes the served bytes):

```java
    private static final WebUiTestKit KIT = new WebUiTestKit();
    /** What the browser receives (CLAUDE.md: assert on the thing sent), never a file on disk. */
    private static final String CSS = KIT.servedText("/assets/baymax.css");

    /** R2: the stylesheet is the parts in order, byte for byte; a part added on disk but not listed fails here. */
    @Test
    void theServedStylesheetIsEveryPartInOrder() throws IOException {
        List<Path> onDisk;
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/baymax/ui/css"))) {
            onDisk = files.filter(p -> p.toString().endsWith(".css")).sorted().toList();
        }
        assertThat(onDisk).as("css parts on disk").hasSizeGreaterThanOrEqualTo(9);
        assertThat(onDisk.stream().map(p -> p.getFileName().toString()).toList()).isEqualTo(AssetHandler.CSS_PARTS);
        StringBuilder joined = new StringBuilder();
        for (Path p : onDisk) {
            joined.append(Files.readString(p, StandardCharsets.UTF_8));
        }
        assertThat(CSS).isEqualTo(joined.toString());
    }
```

  `theTokenFileDefinesEveryTokenThePagesUse` reads `CSS` instead of `Files.readString(TOKENS)`.
- [ ] **Step 2: red.** `cannot find symbol AssetHandler.CSS_PARTS` → add the constant with the nine names and run:
  `expected: ["00-tokens.css", …] but was: []` (the directory does not exist yet).
- [ ] **Step 3: split, at line boundaries, so the concatenation is byte-identical.** From today's `baymax.css`
  (200 lines): lines 1–36 (the header comment and `:root{…}`) → `00-tokens.css`; lines 37–109 (base, top bar, toggle,
  surfaces, forms, NOW, footer) → `10-base.css`; lines 110–200 (landing) → `90-landing.css`. The six others hold one
  comment line each, e.g. `/* 20-shell: app shell, tabs, sheets (A5, C1). Tokens only. */` — and because a comment adds
  bytes, the characterization snapshot of `GET /assets/{file}` would change: put those placeholder comments in **after**
  Step 5's characterization run, in a second commit (`style(baymax-ui): name the empty css parts`), and re-record only
  that one snapshot, stating it in the PR body.

```bash
src=baymax/src/main/resources/baymax/ui/baymax.css; d=baymax/src/main/resources/baymax/ui/css; mkdir -p "$d"
sed -n '1,36p' "$src" > "$d/00-tokens.css"; sed -n '37,109p' "$src" > "$d/10-base.css"; sed -n '110,200p' "$src" > "$d/90-landing.css"
for p in 20-shell 30-family 40-papers 50-chat 60-result 70-upload; do : > "$d/$p.css"; done
cat "$d"/*.css | cmp - "$src" && git rm -q "$src"
```

  (Check the line numbers with `grep -n '^/\*' "$src"` first: at `e6264a4` the landing block starts at line 110 and
  `:root{…}` ends at line 36.) `AssetHandler`:

```java
    /** The stylesheet's parts, in the order they are served as /assets/baymax.css. Each area appends only to its own. */
    static final List<String> CSS_PARTS = List.of("00-tokens.css", "10-base.css", "20-shell.css", "30-family.css",
            "40-papers.css", "50-chat.css", "60-result.css", "70-upload.css", "90-landing.css");

    private static final byte[] STYLESHEET = concat(CSS_PARTS);

    static byte[] stylesheet() {
        return STYLESHEET.clone();
    }

    /** The cache-busting version of a served asset, from the bytes served (the stylesheet: the concatenation). */
    static String version(String file) {
        byte[] bytes = "baymax.css".equals(file) ? STYLESHEET : read(file);
        return Integer.toHexString(java.util.Arrays.hashCode(bytes));
    }
```

  `asset(...)` answers `baymax.css` from `STYLESHEET`; `Html.CSS_HREF` becomes `CSS_PATH + "?v=" + AssetHandler.version("baymax.css")`.
  The hash of identical bytes is identical, so every page's `<link>` is unchanged.
- [ ] **Step 4: run** `./gradlew :baymax:test --tests '*DesignTokensTest' --tests '*WebPagesCharacterizationTest'` → green.
- [ ] **Step 5: replay (mutant).** Swap `10-base.css` and `20-shell.css` in `CSS_PARTS` → red
  `expected: ["00-tokens.css", "10-base.css", "20-shell.css", …] but was: ["00-tokens.css", "20-shell.css", "10-base.css", …]`.
  Revert.

### Task R2.2: the copy in parts, one owner per key

**Files:** create `baymax/ui-{shell,family,papers,chat,result,upload}_{bn,en}.properties` (each a single comment line);
create test `ui/BundleFiles.java`; modify `ui/UiCopy.java`, `UiCopyTest`.

- [ ] **Step 1: failing tests** (`UiCopyTest`; `BundleFiles` reads raw lines so a key Properties silently overwrote is
  still seen):

```java
    /** A key defined twice — in one file or across parts — is a defect Properties hides by keeping the last one. */
    @Test
    void noKeyIsDefinedTwiceInAnyPartOrAcrossParts() throws IOException {
        Map<String, List<String>> where = new TreeMap<>();
        int files = 0;
        for (Lang lang : Lang.values()) {
            for (String part : UiCopy.PARTS) {
                files++;
                BundleFiles.keys(part, lang).forEach((key, line) ->
                        where.computeIfAbsent(lang.code + ":" + key, k -> new ArrayList<>()).add(part + "_" + lang.code + ":" + line));
                BundleFiles.duplicatesWithin(part, lang).forEach(d -> where.computeIfAbsent(lang.code + ":" + d, k -> new ArrayList<>()).add(part + " (twice)"));
            }
        }
        assertThat(files).isEqualTo(UiCopy.PARTS.size() * Lang.values().length);
        assertThat(where).as("keys read").hasSizeGreaterThan(300);   // bn + en
        assertThat(where.entrySet().stream().filter(e -> e.getValue().size() > 1).map(e -> e.getKey() + " at " + e.getValue()).toList())
                .isEmpty();
    }

    /** Every key exists in both languages, across all parts, with the same placeholders. */
    @Test
    void everyKeyExistsInBothLanguagesWithTheSamePlaceholders() {
        UiCopy copy = new UiCopy();
        assertThat(copy.keys(Lang.BN)).hasSizeGreaterThan(150).isEqualTo(copy.keys(Lang.EN));
        Pattern slot = Pattern.compile("\\{\\d+}");
        for (String key : copy.keys(Lang.BN)) {
            assertThat(slot.matcher(copy.raw(Lang.EN, key)).results().map(MatchResult::group).sorted().toList()).as(key)
                    .isEqualTo(slot.matcher(copy.raw(Lang.BN, key)).results().map(MatchResult::group).sorted().toList());
        }
    }

    /** A bundle file on the classpath that UiCopy does not load would be copy that silently never reaches a page. */
    @Test
    void everyBundlePartOnDiskIsLoaded() throws IOException {
        Set<String> onDisk;
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/baymax"))) {
            onDisk = files.map(p -> p.getFileName().toString()).filter(n -> n.matches("ui(-[a-z]+)?_(bn|en)\\.properties"))
                    .map(n -> n.substring(0, n.lastIndexOf('_'))).collect(Collectors.toCollection(TreeSet::new));
        }
        assertThat(onDisk).hasSizeGreaterThanOrEqualTo(7).isEqualTo(new TreeSet<>(UiCopy.PARTS));
    }
```

```java
package com.elioo.baymax.web.adapter.in.ui;

/** The bundle files as written, line by line: what a family's string actually is before Properties merges it. */
final class BundleFiles {

    private static final Pattern KEY = Pattern.compile("^\\s*([^#!\\s][^=:\\s]*)\\s*[=:]");

    private BundleFiles() {
    }

    static List<String> lines(String part, Lang lang) throws IOException {
        return Files.readAllLines(Path.of("src/main/resources/baymax/" + part + "_" + lang.code + ".properties"), StandardCharsets.UTF_8);
    }

    /** key → 1-based line of its first definition; continuation lines (after a trailing backslash) are skipped. */
    static Map<String, Integer> keys(String part, Lang lang) throws IOException {
        Map<String, Integer> out = new LinkedHashMap<>();
        List<String> lines = lines(part, lang);
        boolean continued = false;
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (!continued) {
                Matcher m = KEY.matcher(l);
                if (m.find()) {
                    out.putIfAbsent(m.group(1), i + 1);
                }
            }
            continued = l.endsWith("\\") && !l.endsWith("\\\\");
        }
        return out;
    }

    static List<String> duplicatesWithin(String part, Lang lang) throws IOException {
        Map<String, Integer> seen = new HashMap<>();
        List<String> twice = new ArrayList<>();
        boolean continued = false;
        for (String l : lines(part, lang)) {
            if (!continued) {
                Matcher m = KEY.matcher(l);
                if (m.find() && seen.merge(m.group(1), 1, Integer::sum) == 2) {
                    twice.add(m.group(1));
                }
            }
            continued = l.endsWith("\\") && !l.endsWith("\\\\");
        }
        return twice;
    }
}
```

- [ ] **Step 2: red.** `cannot find symbol UiCopy.PARTS` → add `PARTS` holding only `"ui"`, run:
  `everyBundlePartOnDiskIsLoaded: expected: ["ui"] but was: ["ui", "ui-chat", "ui-family", …]` once the empty part
  files exist.
- [ ] **Step 3: implement** `UiCopy`:

```java
    /** Bundle parts, loaded in this order; a key may live in one part only (startup refuses a second definition). */
    static final List<String> PARTS = List.of("ui", "ui-shell", "ui-family", "ui-papers", "ui-chat", "ui-result", "ui-upload");

    public UiCopy() {
        for (Lang lang : Lang.values()) {
            Properties all = new Properties();
            for (String part : PARTS) {
                Properties p = load("/baymax/" + part + "_" + lang.code + ".properties");
                for (String key : p.stringPropertyNames()) {
                    if (all.setProperty(key, p.getProperty(key)) != null) {
                        throw new IllegalStateException("copy key " + key + " is defined in two bundle parts");
                    }
                }
            }
            bundles.put(lang, all);
        }
    }

    /** The bundle text as written, unescaped, no arguments: for tests and for URL parameters only, never for HTML. */
    String raw(Lang lang, String key) {
        String raw = bundles.get(lang).getProperty(key);
        if (raw == null) {
            throw new IllegalArgumentException("no UI copy for key " + key);
        }
        return raw;
    }
```

  Each new part file starts with one comment line naming its owner (`# ui-shell: the app shell, tabs, sheets; owned by
  A5/C1. Shared keys (sheet.close, paper.label, app.back.papers) live here.`). No key moves in R2.
- [ ] **Step 4: run** `./gradlew :baymax:test --tests '*UiCopyTest' --tests '*WebPagesCharacterizationTest'` → green.
- [ ] **Step 5: replay (mutant).** Append `sheet.close=বন্ধ করুন` twice to `ui-shell_bn.properties` (and `sheet.close=Close`
  once to `ui-shell_en`) → red `bn:sheet.close at [ui-shell_bn:2, ui-shell (twice)]`; then put the bn key once in
  `ui-shell_bn` and once in `ui-chat_bn` → `UiCopy` refuses at construction: `copy key sheet.close is defined in two
  bundle parts`. Revert.

### Task R2.3: one `Html.Head` instead of `page(...)` overloads

**Files:** modify `ui/Html.java`; test `ui/HtmlHeadTest.java`.

- [ ] **Step 1: failing test.**

```java
package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** R2: the old seven-argument page() and the Head form write the same bytes, so no page changes. */
class HtmlHeadTest {

    @ParameterizedTest
    @CsvSource({"0,false,false", "5,false,false", "0,true,true", "5,true,false"})
    void theHeadFormIsByteForByteTheOldForm(int refresh, boolean wide, boolean index) {
        String old = Html.page(Lang.BN, "t &amp; u", "<p>x</p>", refresh, wide, "d", index);
        Html.Head head = Html.Head.of("t &amp; u").refresh(refresh);
        head = index ? head.indexed() : head;
        assertThat(Html.page(Lang.BN, head, "<p>x</p>", wide, "d")).isEqualTo(old);
    }
}
```

- [ ] **Step 2: red** `cannot find symbol Html.Head`. **Step 3: implement:**

```java
    /**
     * Everything a page's head carries (R2). A PR that needs something new in the head adds a field here (L1: theme,
     * A9: refresh without script and progress.js, D4: upload.js) instead of another page() overload.
     */
    record Head(String title, int refreshSeconds, boolean refreshOnlyWithoutScript, List<String> scripts, boolean index) {
        Head {
            scripts = scripts == null ? List.of() : List.copyOf(scripts);
        }

        static Head of(String title) {
            return new Head(title, 0, false, List.of(), false);
        }

        Head refresh(int seconds) {
            return new Head(title, seconds, false, scripts, index);
        }

        Head indexed() {
            return new Head(title, refreshSeconds, refreshOnlyWithoutScript, scripts, true);
        }
    }

    static String page(Lang lang, Head head, String body, boolean wide, String disclaimer) {
        return "<!doctype html><html lang=\"" + lang.code + "\"><head><meta charset=\"utf-8\">"
                + (head.refreshSeconds() > 0 ? "<meta http-equiv=\"refresh\" content=\"" + head.refreshSeconds() + "\">" : "")
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + (head.index() ? "" : "<meta name=\"robots\" content=\"noindex\">")
                + "<title>" + head.title() + "</title><link rel=\"stylesheet\" href=\"" + CSS_HREF + "\"><link rel=\"icon\" type=\"image/svg+xml\" href=\"" + ICON_HREF + "\"></head><body><main class=\""
                + (wide ? "page" : "app") + "\">" + body
                + "<footer class=\"foot\">" + disclaimer + "</footer></main></body></html>";
    }

    static String page(Lang lang, String title, String body, int refreshSeconds, boolean wide, String disclaimer, boolean index) {
        Head head = Head.of(title).refresh(refreshSeconds);
        return page(lang, index ? head.indexed() : head, body, wide, disclaimer);
    }
```

- [ ] **Step 4: run** → green (`HtmlHeadTest`, `WebPagesCharacterizationTest`).

### Task R2.4: the asset list, one per line, derived both ways

**Files:** modify `ui/AssetHandler.java` (`ASSETS` one entry per line, plus `PRIVATE_DIRS` and `OPTIONAL_SLOTS`);
test `ui/AssetListTest.java`.

- [ ] **Step 1: failing test.**

```java
package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Every file under baymax/ui is served or deliberately private; every served name exists or is a declared slot. */
class AssetListTest {

    private static final Path UI = Path.of("src/main/resources/baymax/ui");

    @Test
    void everyFileIsListedOrPrivateAndEveryListedFileIsServed() throws IOException {
        List<String> files;
        try (Stream<Path> walk = Files.walk(UI)) {
            files = walk.filter(Files::isRegularFile).map(p -> UI.relativize(p).toString().replace('\\', '/')).sorted().toList();
        }
        assertThat(files).as("files under baymax/ui").hasSizeGreaterThan(10);
        assertThat(files.stream().filter(f -> !AssetHandler.ASSETS.contains(f) && AssetHandler.PRIVATE_DIRS.stream().noneMatch(f::startsWith)).toList())
                .as("on disk but neither served nor private").isEmpty();
        WebUiTestKit kit = new WebUiTestKit();
        for (String listed : AssetHandler.ASSETS) {
            if (files.contains(listed) || "baymax.css".equals(listed)) {
                assertThat(kit.served("/assets/" + listed)).as(listed).isNotEmpty();
            } else {
                assertThat(AssetHandler.OPTIONAL_SLOTS).as(listed + " is listed but missing and not a declared slot").contains(listed);
            }
        }
    }
}
```

- [ ] **Step 2: red** `cannot find symbol PRIVATE_DIRS`; then with `PRIVATE_DIRS = List.of()` →
  `on disk but neither served nor private: ["css/00-tokens.css", …]`. **Step 3:** `PRIVATE_DIRS = List.of("css/")`,
  `OPTIONAL_SLOTS = Set.of("mio.png", "medioo.png", "medioo.svg")` with the comment "original assets from Tanvir; a slot
  renders only when `Html.present` finds the file (mascot slot empty by decision)". `ASSETS` keeps its ten names, one
  per line, sorted. Run → green.

### Task R2.5: two ratchets — the glossary and Bangla in Java literals

**Files:** create tests `ui/GlossaryTest.java`, `ui/NoBanglaInJavaLiteralsTest.java`.

- [ ] **Step 1: the glossary ratchet** (spec §7.4: one term each). The known violations are an explicit list that can
  only shrink: a new violation fails, and a listed entry that no longer violates fails too.

```java
package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §7.4 glossary, over every bundle part. The patient by name, never "রোগী"; কাগজ, never "নথি"; "রিপোর্টে লেখা
 * সীমা", never "স্বাভাবিক সীমা"; the tab is "কাগজপত্র"; never "বন্ধ করুন" (a forbidden phrase, BaymaxProperties.java:186).
 * A ratchet: KNOWN lists what main had on 2026-10-19 and may only shrink (D2 empties it).
 */
class GlossaryTest {

    static final List<String> WEB_RETIRED = List.of("রোগী", "নথি", "ছাপা সীমা", "স্বাভাবিক সীমা", "তুলনা নেই",
            "টাইমলাইন", "বন্ধ করুন", "চিন্তার কিছু নেই", "যা চোখে পড়ার মতো");
    /** A placeholder glued to a case ending means the bundle does name grammar; F8 does it in code (D2). */
    static final Pattern GLUED_CASE = Pattern.compile("\\{\\d+}-?(এর|র|কে)(?![\\u0980-\\u09FF])");
    static final List<String> MESSAGE_RANGE_WORDS = List.of("স্বাভাবিক সীমা", "ছাপা সীমা", "তুলনা নেই");
    /** messages_bn exemptions, each with its owner: the D35 claim wording is the PO's (Phase 0 PR 2). */
    static final Map<String, String> MESSAGE_EXEMPT = Map.of("explanation.routine", "স্বাভাবিক সীমা");

    /** "<lang-part>:<key> <word>" for every violation on main when this test was written. Paste the first red here. */
    static final Set<String> KNOWN = Set.of(
            /* filled from the Step 2 red, one string per line, sorted */);

    @Test
    void noBundleStringUsesARetiredTermExceptTheKnownOnesAndTheKnownOnesStillDo() throws Exception {
        Set<String> found = new TreeSet<>();
        int keys = 0;
        for (String part : UiCopy.PARTS) {
            for (Map.Entry<String, String> e : BundleFiles.values(part, Lang.BN).entrySet()) {
                keys++;
                for (String w : WEB_RETIRED) {
                    if (e.getValue().contains(w)) {
                        found.add(part + ":" + e.getKey() + " " + w);
                    }
                }
                if (GLUED_CASE.matcher(e.getValue()).find()) {
                    found.add(part + ":" + e.getKey() + " glued-case");
                }
            }
        }
        for (Map.Entry<String, String> e : BundleFiles.messages(Lang.BN).entrySet()) {
            keys++;
            for (String w : MESSAGE_RANGE_WORDS) {
                if (e.getValue().contains(w) && !w.equals(MESSAGE_EXEMPT.get(e.getKey()))) {
                    found.add("messages:" + e.getKey() + " " + w);
                }
            }
        }
        assertThat(keys).as("keys read; an empty read would pass vacuously").isGreaterThan(200);
        Set<String> fresh = new TreeSet<>(found);
        fresh.removeAll(KNOWN);
        Set<String> healed = new TreeSet<>(KNOWN);
        healed.removeAll(found);
        assertThat(fresh).as("new glossary violations").isEmpty();
        assertThat(healed).as("fixed, so remove from KNOWN (the ratchet only shrinks)").isEmpty();
        MESSAGE_EXEMPT.forEach((k, w) -> assertThat(BundleFiles.messages(Lang.BN).get(k)).as("exemption " + k + " still needed").contains(w));
    }
}
```

  (`BundleFiles.values(part, lang)` and `BundleFiles.messages(lang)` load one file with `Properties` in UTF-8 and return
  `key → value`.)
- [ ] **Step 2: red, then record.** Run; the red lists every violation on main (`ui:app.home.title নথি`,
  `ui:patients রোগী`, `ui:optout.title বন্ধ করুন`, `ui:landing.c.ref স্বাভাবিক সীমা`,
  `messages:line.value_outside_range স্বাভাবিক সীমা`, …). Paste exactly that list into `KNOWN`. Green.
- [ ] **Step 3: replay (both directions).** Add `ui-shell_bn` `x.test=নথি দেখুন` → red `new glossary violations: [ui-shell:x.test নথি]`.
  Remove one `KNOWN` entry's word from its bundle line → red `fixed, so remove from KNOWN`. Revert both.
- [ ] **Step 4: no Bangla in Java string literals** (copy belongs in the bundles, where the English toggle reaches it):

```java
package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** A Bengali code point inside a Java string literal is copy the English page and the copy owner never see. */
class NoBanglaInJavaLiteralsTest {

    private static final Path WEB = Path.of("src/main/java/com/elioo/baymax/web/adapter/in");
    private static final Pattern LITERAL = Pattern.compile("\"(?:[^\"\\\\\\n]|\\\\.)*\"");
    /** file → literal fragment, each with why it is not copy. A ratchet: an unused allowance fails. */
    private static final Map<String, String> ALLOWED = Map.of(
            "SignInPages.java#[0-9০-৯+", "the number field's pattern accepts Bangla digits (input, not copy)",
            "SignInPages.java#[0-9০-৯]{6}", "the code field's pattern accepts Bangla digits (input, not copy)",
            "SignInPages.java#\"কোড\"", "the WhatsApp keyword WaTextRouter matches; the link pre-fills it",
            "AuthHandler.java#কোড", "the JSON API's machine message names the WhatsApp keyword");

    @Test
    void noBanglaInAnyStringLiteralOfTheWebAdapters() throws IOException {
        List<String> found = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(WEB)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        assertThat(files).as("sources read").hasSizeGreaterThan(8);
        for (Path p : files) {
            String src = Files.readString(p).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
            Matcher m = LITERAL.matcher(src);
            while (m.find()) {
                if (m.group().codePoints().anyMatch(c -> c >= 0x0980 && c <= 0x09FF)) {
                    String hit = p.getFileName() + "#" + m.group();
                    if (ALLOWED.keySet().stream().noneMatch(a -> hit.startsWith(a.substring(0, a.indexOf('#') + 1)) && hit.contains(a.substring(a.indexOf('#') + 1)))) {
                        found.add(hit);
                    }
                }
            }
        }
        assertThat(found).isEmpty();
        for (String a : ALLOWED.keySet()) {
            Path f = files.stream().filter(p -> p.getFileName().toString().equals(a.substring(0, a.indexOf('#')))).findFirst().orElseThrow();
            assertThat(Files.readString(f)).as("allowance " + a + " still needed").contains(a.substring(a.indexOf('#') + 1));
        }
    }
}
```

  Replay: add `return "<p>বড় করে দেখুন</p>";` to a scratch method in `DocumentPages` → red naming
  `DocumentPages.java#"<p>বড় করে দেখুন</p>"`. Revert.
- [ ] **Step 5: ship.** Standard pre-merge. Commit
  `refactor(baymax-web): stylesheet, copy and head in parts; a glossary ratchet`.

### Try it (A2) — prod-web

- [ ] As A1: every page looks exactly as before; `curl -s https://medioo.eliooo.org/assets/baymax.css | wc -c` equals
  yesterday's size plus the placeholder comment lines.

---

## PR A3 — L1: tokens v3, dark mode, the type scale and 48 px targets

Branch `feat/baymax-ui-tokens-v3`. The first PR that changes pages on purpose, so it deletes
`WebPagesCharacterizationTest` and `src/test/resources/baymax/characterization/` (A1, A2 needed them; nothing after
does). Every check below reads the stylesheet **as served** (`kit.servedText("/assets/baymax.css")`) and takes its
rules, pairs and targets from that stylesheet or from the rendered pages, never from a hand list (a hand list remains
only for the two pairs the stylesheet cannot express: the focus ring and control outlines).

### Task L1.1: contrast, derived from the served rules, in both themes

**Files:** create `ui/Tokens.java`, test `ui/TokenContrastTest.java`.

- [ ] **Step 1: `Tokens`** (one parser for the tests and for the `theme-color` meta, so the browser bar and the page
  cannot disagree):

```java
package com.elioo.baymax.web.adapter.in.ui;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The colour tokens of the served stylesheet, per theme. No hex is written in Java (DesignTokensTest). */
final class Tokens {

    private static final Pattern LIGHT = Pattern.compile(":root\\s*\\{([^}]*)}");
    private static final Pattern DARK_MEDIA = Pattern.compile(
            "@media\\s*\\(prefers-color-scheme:\\s*dark\\)\\s*\\{\\s*:root:not\\(\\[data-theme=light]\\)\\s*\\{([^}]*)}\\s*}");
    private static final Pattern DARK_ATTR = Pattern.compile(":root\\[data-theme=dark]\\s*\\{([^}]*)}");
    private static final Pattern DECL = Pattern.compile("(--[a-z0-9-]+)\\s*:\\s*([^;]+);");

    static final String CSS = new String(AssetHandler.stylesheet(), StandardCharsets.UTF_8);
    static final String LIGHT_BG = light(CSS).get("--c-bg");
    static final String DARK_BG = dark(CSS).get("--c-bg");

    private Tokens() {
    }

    static String withoutComments(String css) {
        return css.replaceAll("(?s)/\\*.*?\\*/", "");
    }

    static Map<String, String> light(String css) {
        return block(LIGHT, css);
    }

    static Map<String, String> dark(String css) {
        return block(DARK_MEDIA, css);
    }

    static Map<String, String> darkAttribute(String css) {
        return block(DARK_ATTR, css);
    }

    /** The stylesheet with the three token blocks and every @font-face removed: the rules that style pages. */
    static String rules(String css) {
        return withoutComments(css).replaceAll(":root\\s*\\{[^}]*}", "")
                .replaceAll("@media\\s*\\(prefers-color-scheme:\\s*dark\\)\\s*\\{\\s*:root:not\\(\\[data-theme=light]\\)\\s*\\{[^}]*}\\s*}", "")
                .replaceAll(":root\\[data-theme=dark]\\s*\\{[^}]*}", "")
                .replaceAll("@font-face\\s*\\{[^}]*}", "");
    }

    private static Map<String, String> block(Pattern p, String css) {
        Matcher m = p.matcher(withoutComments(css));
        Map<String, String> out = new LinkedHashMap<>();
        if (m.find()) {
            Matcher d = DECL.matcher(m.group(1));
            while (d.find()) {
                out.put(d.group(1), d.group(2).trim());
            }
        }
        return out;
    }
}
```

- [ ] **Step 2: the failing test.**

```java
package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §8 / §10: every text colour clears 4.5:1 on the background it is painted on, in light and in dark. The pairs
 * are derived from the served stylesheet: a rule declaring both color and background is measured as written; a rule
 * declaring only color is measured on --c-surface and --c-bg. Only the two pairs a rule cannot express are listed.
 */
class TokenContrastTest {

    private static final String CSS = new WebUiTestKit().servedText("/assets/baymax.css");
    private static final Pattern RULE = Pattern.compile("([^{}]+)\\{([^{}]*)}");
    private static final Pattern COLOR = Pattern.compile("(?<![-a-z])color\\s*:\\s*var\\((--c-[a-z0-9-]+)\\)");
    private static final Pattern BACKGROUND = Pattern.compile("background(?:-color)?\\s*:\\s*var\\((--c-[a-z0-9-]+)\\)");

    record Pair(String where, String fg, String bg, double min) {
    }

    /** WCAG 1.4.11: the focus ring and control outlines against what they sit on (3:1). Not expressible as a rule pair. */
    static final List<Pair> NON_TEXT = List.of(new Pair("focus ring", "--c-focus", "--c-bg", 3), new Pair("focus ring", "--c-focus", "--c-surface", 3),
            new Pair("control outline", "--c-outline", "--c-surface", 3), new Pair("control outline", "--c-outline", "--c-bg", 3));
    /** Logotypes are exempt from 1.4.3; each exemption names the selector and why. */
    static final Map<String, String> EXEMPT = Map.of(".wm-text .oo", "the wordmark's green 'oo' is a logotype (WCAG 1.4.3 exception)");

    static List<Pair> derivedPairs(String css) {
        List<Pair> pairs = new ArrayList<>();
        Matcher r = RULE.matcher(Tokens.rules(css));
        while (r.find()) {
            String selector = r.group(1).trim();
            if (selector.startsWith("@") || EXEMPT.containsKey(selector)) {
                continue;
            }
            Matcher c = COLOR.matcher(r.group(2));
            if (!c.find()) {
                continue;
            }
            Matcher b = BACKGROUND.matcher(r.group(2));
            if (b.find()) {
                pairs.add(new Pair(selector, c.group(1), b.group(1), 4.5));
            } else {
                pairs.add(new Pair(selector, c.group(1), "--c-surface", 4.5));
                pairs.add(new Pair(selector, c.group(1), "--c-bg", 4.5));
            }
        }
        return pairs;
    }

    @Test
    void everyTextColourClearsItsBackgroundInLightAndDark() {
        List<Pair> pairs = new ArrayList<>(derivedPairs(CSS));
        assertThat(pairs).as("pairs derived from the served rules; a broken parse would pass vacuously").hasSizeGreaterThan(30);
        pairs.addAll(NON_TEXT);
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> theme : Map.of("light", Tokens.light(CSS), "dark", Tokens.dark(CSS)).entrySet()) {
            for (Pair p : pairs) {
                String fg = theme.getValue().get(p.fg());
                String bg = theme.getValue().get(p.bg());
                assertThat(fg).as("%s %s (%s) is defined", theme.getKey(), p.fg(), p.where()).isNotNull();
                assertThat(bg).as("%s %s (%s) is defined", theme.getKey(), p.bg(), p.where()).isNotNull();
                double ratio = ratio(fg, bg);
                if (ratio < p.min()) {
                    failures.add("%s: %s — %s on %s is %.2f:1, needs %.1f:1".formatted(theme.getKey(), p.where(), p.fg(), p.bg(), ratio, p.min()));
                }
            }
        }
        assertThat(failures).isEmpty();
    }

    /** The dark palette is written twice (media query and data-theme); the copies may not drift. */
    @Test
    void theTwoDarkBlocksAreTheSameAndRedefineEveryColour() {
        assertThat(Tokens.dark(CSS)).hasSizeGreaterThan(30).isEqualTo(Tokens.darkAttribute(CSS));
        assertThat(Tokens.dark(CSS).keySet()).containsAll(Tokens.light(CSS).keySet().stream().filter(k -> k.startsWith("--c-")).toList());
    }

    /** An exemption that no rule needs any more is removed (the list only shrinks). */
    @Test
    void everyExemptionStillNamesARealRule() {
        Set<String> selectors = new LinkedHashSet<>();
        Matcher r = RULE.matcher(Tokens.rules(CSS));
        while (r.find()) {
            selectors.add(r.group(1).trim());
        }
        assertThat(selectors).containsAll(EXEMPT.keySet());
    }

    static double ratio(String a, String b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(String hex) {
        String h = hex.substring(1, 7);
        double[] c = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16) / 255.0;
            c[i] = v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }
}
```

  `everyExemptionStillNamesARealRule` is red until A5 adds `.wm-text .oo`; in this PR `EXEMPT` is `Map.of()` and A5 adds
  the entry with its rule (the test then holds the exemption to a real selector).
- [ ] **Step 3: red.** `[light --c-text-muted (…) is defined] Expecting actual not to be null` (today's tokens are v2
  names) and `[dark …] … null` (no dark block).

### Task L1.2: red is reserved for NOW, critical and emergency; literals only in token blocks

**Files:** modify `DesignTokensTest` (add two tests, reading `CSS`, the served bytes).

- [ ] **Step 1: failing tests.**

```java
    /** Spec §8: red means NOW. The status-now tokens and the wordmark dot appear only under now/critical/emergency selectors. */
    @Test
    void redAppearsOnlyInNowCriticalAndEmergencyRules() {
        Matcher rule = Pattern.compile("([^{}]+)\\{([^{}]*)}").matcher(Tokens.rules(CSS));
        Pattern red = Pattern.compile("var\\(--c-(status-now[a-z-]*|on-status-now|brand-dot)\\)");
        Pattern allowed = Pattern.compile("\\.(now|critical|emergency)\\b|\\.brand \\.dot");
        int redRules = 0;
        List<String> offenders = new ArrayList<>();
        while (rule.find()) {
            Matcher uses = red.matcher(rule.group(2));
            if (!uses.find()) {
                continue;
            }
            redRules++;
            for (String selector : rule.group(1).split(",")) {
                if (!allowed.matcher(selector).find()) {
                    offenders.add(selector.trim() + " uses " + uses.group());
                }
            }
        }
        assertThat(redRules).as("rules using the red tokens (an empty parse would pass vacuously)").isPositive();
        assertThat(offenders).as("red is reserved for NOW").isEmpty();
    }

    /** DR-14 inside the stylesheet: a colour literal lives in a token block, never in a rule. */
    @Test
    void colourLiteralsLiveOnlyInTheTokenBlocks() {
        String rules = Tokens.rules(CSS);
        assertThat(rules.length()).as("rules remain after removing the token blocks").isGreaterThan(5_000);
        assertThat(Pattern.compile("#[0-9a-fA-F]{3,8}\\b|rgba?\\(|hsla?\\(").matcher(rules).results().map(MatchResult::group).toList()).isEmpty();
    }
```

- [ ] **Step 2: red** `rules using the red tokens … Expecting actual: 0 to be greater than 0` (the v3 red tokens do not
  exist yet). The honest replays come in L1.5.

### Task L1.3: the type scale — on the tokens and on every rule that uses them

**Files:** create test `ui/TypeScaleTest.java`.

- [ ] **Step 1: failing test.**

```java
package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §1 rule 12, §8: body 17 px, nothing Bangla below 14 px, line-height ≥ 1.5. Checked on the tokens AND on every
 * rule: a rule may only size text through the tokens, and the Latin-only sizes only on Latin-only selectors.
 */
class TypeScaleTest {

    private static final String CSS = new WebUiTestKit().servedText("/assets/baymax.css");
    private static final Set<String> LATIN_ONLY = Set.of("caption-latin", "badge-latin", "value-large", "value", "unit", "range");
    /** Selectors that only ever hold printed Latin (values, units, ranges, badges, bar ends). */
    private static final Pattern LATIN_SELECTOR = Pattern.compile("\\.(val|unit|range|badge|ends)\\b");
    private static final Pattern RULE = Pattern.compile("([^{}]+)\\{([^{}]*)}");

    @Test
    void everyBanglaStyleTokenIsAtLeastFourteenPixelsWithLineHeightAtLeastOneAndAHalf() {
        Map<String, String> t = Tokens.light(CSS);
        List<String> styles = t.keySet().stream().filter(k -> k.startsWith("--fs-")).map(k -> k.substring(5)).toList();
        assertThat(styles).as("type styles defined").hasSizeGreaterThanOrEqualTo(18);
        assertThat(t.get("--fs-body")).isEqualTo("1.0625rem");   // 17 px
        List<String> failures = new ArrayList<>();
        for (String s : styles) {
            double px = Double.parseDouble(t.get("--fs-" + s).replace("rem", "")) * 16;
            String lh = t.get("--lh-" + s);
            assertThat(lh).as("--lh-" + s + " is defined").isNotNull();
            if (LATIN_ONLY.contains(s)) {
                continue;
            }
            if (px < 14) {
                failures.add(s + " is " + px + " px");
            }
            if (Double.parseDouble(lh) < 1.5) {
                failures.add(s + " line-height " + lh);
            }
        }
        assertThat(failures).isEmpty();
    }

    @Test
    void everyRuleSizesTextThroughTheTokensAndLatinSizesStayOnLatinSelectors() {
        Matcher r = RULE.matcher(Tokens.rules(CSS));
        List<String> failures = new ArrayList<>();
        int sized = 0;
        boolean body = false;
        while (r.find()) {
            String selector = r.group(1).trim();
            String decl = r.group(2);
            for (Matcher m = Pattern.compile("font-size\\s*:\\s*([^;]+)").matcher(decl); m.find(); ) {
                sized++;
                String v = m.group(1).trim();
                Matcher tok = Pattern.compile("var\\(--fs-([a-z0-9-]+)\\)").matcher(v);
                if (!tok.matches()) {
                    failures.add(selector + " font-size " + v);
                } else if (LATIN_ONLY.contains(tok.group(1)) && !LATIN_SELECTOR.matcher(selector).find()) {
                    failures.add(selector + " uses the Latin-only size --fs-" + tok.group(1));
                }
            }
            for (Matcher m = Pattern.compile("line-height\\s*:\\s*([^;]+)").matcher(decl); m.find(); ) {
                String v = m.group(1).trim();
                if (!v.matches("var\\(--lh-[a-z0-9-]+\\)") && !(v.matches("[0-9.]+") && Double.parseDouble(v) >= 1.5)) {
                    failures.add(selector + " line-height " + v);
                }
            }
            if (selector.equals("body") && decl.contains("var(--fs-body)") && decl.contains("var(--lh-body)")) {
                body = true;
            }
        }
        assertThat(sized).as("font-size declarations read").isGreaterThan(20);
        assertThat(body).as("the body rule uses --fs-body/--lh-body").isTrue();
        assertThat(failures).isEmpty();
    }
}
```

- [ ] **Step 2: red** `type styles defined … Expecting size of: [s, 0, 1, 2, 3, 4] to be greater than or equal to 18`.

### Task L1.4: every control is at least 48 px — derived from the pages and the rules

**Files:** create test `ui/TouchTargetTest.java`.

- [ ] **Step 1: failing test.**

```java
package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §1 rule 12 / WCAG 2.5.8 at Medioo's 48 px: every styled control a page renders has a rule giving it
 * min-height or height ≥ 48 px. Controls are derived from every fixture page (a classed a/button/label is a control;
 * an unclassed link inside prose is the WCAG inline exception), and from the stylesheet (a link or button a rule turns
 * into a block or flex box).
 */
class TouchTargetTest {

    private static final String CSS = new WebUiTestKit().servedText("/assets/baymax.css");
    private static final Pattern RULE = Pattern.compile("([^{}]+)\\{([^{}]*)}");
    private static final Pattern CONTROL = Pattern.compile("<(a|button|label)\\b[^>]*\\bclass=\"([^\"]+)\"");
    private static final Pattern SIZE = Pattern.compile("(?:min-)?height\\s*:\\s*([^;]+)");
    private static final Set<String> TALL_TOKENS = Set.of("--target-min", "--sp-7", "--sp-8", "--header-h", "--tabbar-h", "--composer-h", "--now-strip-h");
    /** Classes on a/button/label that are not targets, each with why. */
    private static final Map<String, String> NOT_TARGETS = Map.of("scrim", "aria-hidden, tabindex=-1; the close control is the target");

    static boolean tall(String value) {
        Matcher var = Pattern.compile("var\\((--[a-z0-9-]+)\\)").matcher(value);
        if (var.find() && TALL_TOKENS.contains(var.group(1))) {
            return true;
        }
        Matcher px = Pattern.compile("^([0-9.]+)px$").matcher(value.trim());
        return px.matches() && Double.parseDouble(px.group(1)) >= 48;
    }

    /** class → true when some rule whose last compound names exactly that class (no pseudo-class) is ≥ 48 px tall. */
    static Map<String, Boolean> sizedClasses(String css) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        Matcher r = RULE.matcher(Tokens.rules(css));
        while (r.find()) {
            for (String selector : r.group(1).split(",")) {
                String last = selector.trim().replaceAll(".*\\s", "");
                if (last.contains(":")) {
                    continue;
                }
                Matcher size = SIZE.matcher(r.group(2));
                boolean isTall = false;
                while (size.find()) {
                    isTall |= tall(size.group(1));
                }
                for (Matcher c = Pattern.compile("\\.([a-z0-9-]+)").matcher(last); c.find(); ) {
                    out.merge(c.group(1), isTall, Boolean::logicalOr);
                }
            }
        }
        return out;
    }

    @Test
    void everyControlAPageRendersHasAFortyEightPixelRule() {
        Map<String, Boolean> sized = sizedClasses(CSS);
        Set<String> failures = new TreeSet<>();
        int controls = 0;
        for (Map.Entry<String, String> page : RenderedPages.all().entrySet()) {
            Matcher m = CONTROL.matcher(page.getValue());
            while (m.find()) {
                List<String> classes = List.of(m.group(2).trim().split("\\s+"));
                if (classes.stream().anyMatch(NOT_TARGETS::containsKey)) {
                    continue;
                }
                controls++;
                if (classes.stream().noneMatch(c -> sized.getOrDefault(c, false)) && !(m.group(1).equals("button") && sized.getOrDefault("@button", false))) {
                    failures.add(page.getKey() + ": <" + m.group(1) + " class=\"" + m.group(2) + "\">");
                }
            }
        }
        assertThat(controls).as("controls found on the fixture pages").isGreaterThan(10);
        assertThat(failures).isEmpty();
    }

    /** A link or button a rule makes into a box (inline-flex, flex, block, grid) is a control: it must be ≥ 48 px. */
    @Test
    void everyLinkOrButtonARuleTurnsIntoABoxIsFortyEightPixelsTall() {
        Matcher r = RULE.matcher(Tokens.rules(CSS));
        List<String> failures = new ArrayList<>();
        while (r.find()) {
            for (String selector : r.group(1).split(",")) {
                String last = selector.trim().replaceAll(".*\\s", "");
                if (!last.matches("(a|button)(\\[[^]]*])?") || !r.group(2).matches("(?s).*display\\s*:\\s*(inline-)?(flex|block|grid).*")) {
                    continue;
                }
                Matcher size = SIZE.matcher(r.group(2));
                boolean isTall = false;
                while (size.find()) {
                    isTall |= tall(size.group(1));
                }
                if (!isTall) {
                    failures.add(selector.trim());
                }
            }
        }
        assertThat(failures).isEmpty();
    }
}
```

  `RenderedPages.all()` (a test helper in the same package, added here) renders every `GET` route of
  `RouteTable.of(kit.router)` with its `PageFixtures` fixture in Bangla and English and returns `"<METHOD> <uri> <lang>" → html`
  for the `text/html` answers; it asserts at least 10 pages. `NoInlineCodeTest`, `UploadEntryPointsTest`,
  `FirstLoadBudgetTest` and `PaperIsNeverMediooTest` reuse it, so every page added later is checked by all of them.
  `sizedClasses` maps an element rule like `button{…}` under the key `@button`.
- [ ] **Step 2: red.** On today's stylesheet no `--target-min` exists, so `everyControlAPageRendersHasAFortyEightPixelRule`
  lists the landing and app controls (`<a class="cta">`, `<a class="brand">`, `<a class="btn">`, …). The second test is
  green today (`.lang a` has no `display`); L1.5 makes `.lang a` an inline-flex pill, and its replay sets that rule's
  `min-height:42px` (the kit §23 drawing's value) to see the second test go red naming `.lang a`.

### Task L1.5: write tokens v3, rename, fix the two contrast defects, the head

**Files:** modify `css/00-tokens.css`, `css/10-base.css`, `css/90-landing.css`, `ui/Html.java`; delete
`WebPagesCharacterizationTest` and the snapshots.

- [ ] **Step 1: replace `:root{…}` in `00-tokens.css` with the v3 block** (values from
  `docs/superpowers/specs/2026-10-17-medioo-tokens-v3.json`; `--c-paper`, `--c-paper-line` (kit §0 slip), `--c-dash`,
  `--c-dot`, `--c-scan-ink` (DR-14 landing) are added with dark values; `--fs-badge-latin` is the 11 px LAB/Rx
  pictogram of kit §14):

```css
/* Medioo design tokens v3 (2026-10-17, evolving DR-14: teal #06788a, ink #0a2e4d, paper #f8f6f1, amber THIS_WEEK,
   red NOW, destructive dark). The ONLY place a colour is written; DesignTokensTest fails a literal anywhere else.
   RED IS RESERVED for NOW urgency, critical values and the emergency card; the wordmark dot is its only decoration. */
:root{
  color-scheme:light dark;
  --c-bg:#f8f6f1;--c-surface:#ffffff;--c-surface-2:#efece4;--c-surface-3:#e6e1d6;
  --c-text:#0a2e4d;--c-text-muted:#4f5f5c;--c-line:#e9e4da;--c-outline:#7d8a88;
  --c-primary:#06788a;--c-on-primary:#ffffff;--c-primary-pressed:#05606e;
  --c-primary-container:#ddf1f4;--c-on-primary-container:#0a2e4d;
  --c-bubble-me:#e0f3f1;--c-brand-green:#2ee0a5;--c-brand-dot:#b91c1c;--c-focus:#0a2e4d;--c-scrim:#0a2e4d66;
  --c-status-inside:#06697a;--c-status-inside-container:#e3f2f4;
  --c-status-soon:#92400e;--c-status-soon-container:#fdf0c4;
  --c-status-now:#b91c1c;--c-status-now-container:#fdeae6;--c-status-now-solid:#b91c1c;--c-on-status-now:#ffffff;
  --c-status-unknown:#4f5f5c;--c-status-unknown-container:#efece4;
  --c-danger:#0a2e4d;--c-on-danger:#ffffff;--c-warn-container:#fff6e5;--c-warn-line:#f0dcae;
  --c-paper:#fffdf7;--c-paper-line:#e6e1d6;--c-dash:#c9c0b0;--c-dot:#a9b6b3;--c-scan-ink:#2a2a2a;
  --shadow-1:0 1px 2px #2b25230a,0 6px 20px #2b25230f;--shadow-2:0 -1px 0 #e9e4da,0 -4px 16px #2b252314;
  --shadow-3:0 12px 40px #0a2e4d29;--shadow-toast:0 8px 24px #0a2e4d33;
  --r-xs:6px;--r-s:10px;--r-m:16px;--r-l:24px;--r-xl:28px;--r-tail:4px;--r-pill:999px;
  --sp-0:2px;--sp-1:4px;--sp-2:8px;--sp-3:12px;--sp-4:16px;--sp-5:24px;--sp-6:32px;--sp-7:48px;--sp-8:72px;
  --target-min:48px;--header-h:56px;--tabbar-h:64px;--composer-h:56px;--now-strip-h:48px;--chip-pad-y:6px;
  --w-app:560px;--w-page:920px;
  --font-sans:"Noto Sans","Noto Sans Bengali",system-ui,sans-serif;--font-latin:"Noto Sans",system-ui,sans-serif;
  --font-mono:ui-monospace,"SFMono-Regular",Menlo,"Courier New","Noto Sans Bengali",monospace;
  --fs-display:2.125rem;--lh-display:1.53;--fs-title-1:1.75rem;--lh-title-1:1.5;--fs-title-2:1.375rem;--lh-title-2:1.55;
  --fs-title-3:1.25rem;--lh-title-3:1.6;--fs-headline:1.0625rem;--lh-headline:1.59;--fs-body:1.0625rem;--lh-body:1.65;
  --fs-callout:1rem;--lh-callout:1.63;--fs-subhead:.9375rem;--lh-subhead:1.6;--fs-footnote:.875rem;--lh-footnote:1.57;
  --fs-label-large:1.0625rem;--lh-label-large:1.53;--fs-label:.9375rem;--lh-label:1.6;--fs-label-small:.875rem;--lh-label-small:1.57;
  --fs-caption-latin:.8125rem;--lh-caption-latin:1.38;--fs-value-large:1.75rem;--lh-value-large:1.21;
  --fs-value:1.25rem;--lh-value:1.3;--fs-unit:.875rem;--lh-unit:1.43;--fs-range:.875rem;--lh-range:1.43;
  --t:160ms ease;
}
@media (prefers-color-scheme:dark){:root:not([data-theme=light]){
  --c-bg:#0e1a22;--c-surface:#15232d;--c-surface-2:#1c2c37;--c-surface-3:#243642;
  --c-text:#e9eef0;--c-text-muted:#a3b2b5;--c-line:#2b3b46;--c-outline:#71838b;
  --c-primary:#5fc4d3;--c-on-primary:#00313a;--c-primary-pressed:#84d5e0;
  --c-primary-container:#0e3d47;--c-on-primary-container:#cdeef3;
  --c-bubble-me:#16393f;--c-brand-green:#2ee0a5;--c-brand-dot:#ff9b92;--c-focus:#9fdbe6;--c-scrim:#00000099;
  --c-status-inside:#7fd0dc;--c-status-inside-container:#103840;
  --c-status-soon:#f4c06a;--c-status-soon-container:#3a2b12;
  --c-status-now:#ff9b92;--c-status-now-container:#421717;--c-status-now-solid:#c62828;--c-on-status-now:#ffffff;
  --c-status-unknown:#a3b2b5;--c-status-unknown-container:#1c2c37;
  --c-danger:#e9eef0;--c-on-danger:#0e1a22;--c-warn-container:#33280f;--c-warn-line:#5a4719;
  --c-paper:#1c2c37;--c-paper-line:#2b3b46;--c-dash:#3a4b56;--c-dot:#71838b;--c-scan-ink:#e9eef0;
  --shadow-1:0 0 0 1px #2b3b46;--shadow-2:0 -1px 0 #2b3b46;--shadow-3:0 0 0 1px #2b3b46,0 12px 40px #00000080;
  --shadow-toast:0 8px 24px #00000099;
}}
:root[data-theme=dark]{ /* the same block again, byte for byte (TokenContrastTest.theTwoDarkBlocksAreTheSame) */ }
```

  Paste the dark declarations into `:root[data-theme=dark]{…}` byte for byte. Nothing sets `data-theme` yet; the hook is
  for a future in-app switch. Add `--fs-badge-latin:.6875rem;--lh-badge-latin:1.45;` to the light block's type line.
- [ ] **Step 2: renames in `10-base.css` and `90-landing.css`**, longest names first:

```bash
for f in baymax/src/main/resources/baymax/ui/css/10-base.css baymax/src/main/resources/baymax/ui/css/90-landing.css; do
sed -i '' -e 's/--c-accent-soft/--c-primary-container/g' -e 's/--c-on-accent/--c-on-primary/g' \
  -e 's/--c-muted/--c-text-muted/g' -e 's/--c-me)/--c-bubble-me)/g' -e 's/--c-brand-2/--c-brand-green/g' \
  -e 's/--c-week-soft/--c-status-soon-container/g' -e 's/--c-week)/--c-status-soon)/g' \
  -e 's/--c-urgent-soft/--c-status-now-container/g' -e 's/--c-info-soft/--c-surface-2/g' \
  -e 's/--c-warn-soft/--c-warn-container/g' -e 's/--c-danger-soft/--c-warn-container/g' \
  -e 's/--c-danger-line/--c-warn-line/g' -e 's/var(--shadow)/var(--shadow-1)/g' \
  -e 's/var(--fs-s)/var(--fs-footnote)/g' -e 's/var(--fs-0)/var(--fs-body)/g' -e 's/var(--fs-4)/var(--fs-display)/g' \
  -e 's/var(--fs-3)/var(--fs-title-1)/g' -e 's/var(--fs-2)/var(--fs-title-2)/g' -e 's/var(--lh)/var(--lh-body)/g' "$f"
done
```

  (the `f=…` assignment line of the draft is replaced by the loop; the `sed -i ''` invocation applies to `"$f"`). Then by
  hand: `body{…font:var(--fs-body)/var(--lh-body) var(--font-sans);…}`; `a{color:var(--c-primary)}`,
  `a:hover{color:var(--c-primary-pressed)}`; `:focus-visible{outline:3px solid var(--c-focus);outline-offset:2px}` covering
  `a`, `button`, `input`, `select`, `label`; `h2{font-size:var(--fs-title-3);line-height:var(--lh-title-3)}`; inputs
  `font-size:var(--fs-body);border:1px solid var(--c-outline)` (3:1 control boundary, was `--c-line` 1.27:1);
  `.brand .dot{background:var(--c-brand-dot)}`; `.top .btn`, `.top button.quiet`, `button,.btn`, `.cta`:
  `min-height:var(--target-min)`; `button:hover,.btn:hover{background:var(--c-primary-pressed);color:var(--c-on-primary)}`;
  the language toggle (kit §23) `.lang{padding:3px;border:1px solid var(--c-outline);border-radius:var(--r-pill)}`,
  `.lang a{display:inline-flex;align-items:center;justify-content:center;min-height:var(--target-min);min-width:64px;padding:0 14px;border-radius:var(--r-pill);font-size:var(--fs-label);line-height:var(--lh-label)}`,
  active `background:var(--c-primary-container);color:var(--c-on-primary-container);font-weight:700`; replace `.urgent`
  with `.banner.now{border-radius:var(--r-m);overflow:hidden;background:var(--c-status-now-container)}` and
  `.banner.now .head{background:var(--c-status-now-solid);color:var(--c-on-status-now);padding:var(--sp-4);font-size:var(--fs-title-3);line-height:var(--lh-title-3);font-weight:700}`
  (`git grep -n 'urgent' -- baymax/src/main/java` prints nothing); `.tag.routine{background:var(--c-primary-container);color:var(--c-on-primary-container)}`,
  `.tag.now{background:var(--c-status-now-solid);color:var(--c-on-status-now)}`, `.voice{color:var(--c-on-primary-container)}`
  (the two 4.42:1 pairs); `.brand{min-height:var(--target-min)}`. Remaining `var(--c-accent)` → `var(--c-primary)`;
  `var(--fs-1)` → `var(--fs-headline)`. Then run `TypeScaleTest` and `TouchTargetTest` and fix every rule they list
  (literal sizes and line-heights in `90-landing.css`, `h1{line-height:1.25}` → `var(--lh-title-1)`): each literal maps
  to the token of the nearest size in the v3 scale; nothing is exempted to make the test pass.
- [ ] **Step 3: the head** (`Html.page(Lang, Head, …)`; `Head` gains nothing, the metas are always on):

```java
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
                + "<meta name=\"color-scheme\" content=\"light dark\">"
                + "<meta name=\"theme-color\" media=\"(prefers-color-scheme: light)\" content=\"" + Tokens.LIGHT_BG + "\">"
                + "<meta name=\"theme-color\" media=\"(prefers-color-scheme: dark)\" content=\"" + Tokens.DARK_BG + "\">"
```

  replacing the old viewport line. Test (add to `BaymaxWebUiRouterTest`), over every page, not two:

```java
    /** Spec §10: never maximum-scale or user-scalable=no (WCAG 1.4.4); colour-scheme and theme-color follow the tokens. */
    @Test
    void everyPageCanBeZoomedAndFollowsTheSystemTheme() {
        Map<String, String> pages = RenderedPages.all();
        assertThat(pages).hasSizeGreaterThan(10);
        pages.forEach((where, html) -> assertThat(html).as(where)
                .contains("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">")
                .doesNotContain("maximum-scale").doesNotContain("user-scalable")
                .contains("<meta name=\"color-scheme\" content=\"light dark\">")
                .contains("content=\"" + Tokens.LIGHT_BG + "\">").contains("content=\"" + Tokens.DARK_BG + "\">"));
        assertThat(Tokens.LIGHT_BG).startsWith("#").isNotEqualTo(Tokens.DARK_BG);
    }
```

- [ ] **Step 4: run** `./gradlew :baymax:test` → green (`TokenContrastTest`, `DesignTokensTest`, `TypeScaleTest`,
  `TouchTargetTest`, `BaymaxWebUiRouterTest`). Delete `WebPagesCharacterizationTest` and its snapshot directory.
- [ ] **Step 5: replays (mutants, each reverted; paste each red).**
  - Dark `--c-text-muted:#6b7a77` in both dark blocks → `dark: body … --c-text-muted on --c-surface is 3.57:1, needs 4.5:1`.
  - Change only the media-query block → `theTwoDarkBlocksAreTheSame…` names `--c-text-muted`.
  - Add `.x{color:var(--c-primary);background:var(--c-primary-container)}` to `10-base.css` →
    `light: .x — --c-primary on --c-primary-container is 4.42:1, needs 4.5:1`.
  - Add `.tag.week{border-color:var(--c-status-now)}` → `.tag.week uses var(--c-status-now)`.
  - `.lang a{…min-height:42px}` → `everyLinkOrButtonARuleTurnsIntoABox…: [".lang a"]`.
  - Add `.chip{font-size:var(--fs-caption-latin)}` → `.chip uses the Latin-only size --fs-caption-latin`.
  - `--lh-label-large:1.4` → `label-large line-height 1.4`.
- [ ] **Step 6:** standard pre-merge. Commit `feat(baymax-ui): tokens v3 and dark mode, contrast, type and targets checked on the served stylesheet`.

### Try it (A3) — prod-web

- [ ] Phone in light mode, then dark mode: landing, `/app`, the code page, home, papers and a document all switch;
  nothing is invisible; the browser bar matches the page background.
- [ ] Pinch-zoom works on every page. The language pills are tinted (no longer white on teal) and as tall as a button.
- [ ] Laptop: Tab through the code page; every link, button and field shows the 3 px focus ring.

---

## PR A4 — L2a: the Baymax-path CSP and no inline code on any page

Branch `feat/baymax-ui-csp`. D12. The policy ships once, final: it already allows self-hosted fonts (D1), `progress.js`
(A9) and `upload.js` (D4) with `connect-src 'self'` for their requests, and `img-src … blob:` for the tray thumbnails
(D4), so no later PR edits the filter. Per DR-2, MedScribe's `SecurityHeadersFilter` is **not edited**.

### Task L2a.1: the filter, in both registration orders, on every Baymax route

**Files:** create `web/adapter/in/BaymaxSecurityHeadersFilter.java`; tests
`medscribe-ai/src/test/java/com/elioo/healthcare/core/filters/BaymaxPathCspTest.java` (that package: `STRICT_CSP` and
`MEDSCRIBE_CSP` are package-private, `SecurityHeadersFilter.java:29-33`) and `web/adapter/in/BaymaxCspRegisteredTest.java`.

- [ ] **Step 1: the stub** (so the red is about the header, not a compile error):

```java
package com.elioo.baymax.web.adapter.in;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * D12: a Content-Security-Policy for Medioo's own pages that allows self-hosted fonts and scripts and nothing inline.
 * MedScribe's filter (medscribe-ai, HIGHEST_PRECEDENCE) sets its header before the chain runs; this one sets the Baymax
 * header at commit time, so it wins whatever order Spring registers the two in, and MedScribe's paths are untouched.
 */
@Component
public class BaymaxSecurityHeadersFilter implements WebFilter {

    public static final String CSP = "default-src 'none'; style-src 'self'; font-src 'self'; script-src 'self'; "
            + "connect-src 'self'; img-src 'self' https: http: blob:; form-action 'self'; base-uri 'none'; frame-ancestors 'none'";

    /** The web UI's paths (BaymaxWebUiRouter): the landing, the app, its assets, the language switch, the old /baymax. */
    public static boolean isBaymaxPage(String path) {
        return path.equals("/") || path.equals("/app") || path.startsWith("/app/") || path.startsWith("/assets/")
                || path.startsWith("/lang/") || path.equals("/baymax") || path.startsWith("/baymax/");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return chain.filter(exchange);   // Step 3 adds the header
    }
}
```

- [ ] **Step 2: failing tests.**

```java
package com.elioo.healthcare.core.filters;

import com.elioo.baymax.web.adapter.in.BaymaxSecurityHeadersFilter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.WebFilter;

import static org.assertj.core.api.Assertions.assertThat;

/** D12 / DR-2: Medioo's pages get the Baymax policy and MedScribe keeps its own, whichever filter runs first. */
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
        assertThat(csp(path, medscribeFirst)).isEqualTo(BaymaxSecurityHeadersFilter.CSP)
                .contains("font-src 'self'").contains("script-src 'self'").contains("frame-ancestors 'none'")
                .doesNotContain("unsafe-inline").doesNotContain("unsafe-eval");
    }

    /** The other direction: MedScribe's demo and every other path keep exactly what they had. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void medscribeAndOtherPathsKeepTheirPolicy(boolean medscribeFirst) {
        assertThat(csp("/medscribeai/", medscribeFirst)).isEqualTo(SecurityHeadersFilter.MEDSCRIBE_CSP);
        assertThat(csp("/api/v1/baymax/me", medscribeFirst)).isEqualTo(SecurityHeadersFilter.STRICT_CSP);
        assertThat(csp("/appendix", medscribeFirst)).as("a prefix that only looks like /app").isEqualTo(SecurityHeadersFilter.STRICT_CSP);
    }

    /** Through a real WebHttpHandler: the header the browser receives, with the other security headers intact. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void theBrowserReceivesEachPolicyThroughTheFilterChain(boolean medscribeFirst) {
        RouterFunction<ServerResponse> pages = RouterFunctions.route()
                .GET("/app/home", r -> ServerResponse.ok().bodyValue("home"))
                .GET("/medscribeai/", r -> ServerResponse.ok().bodyValue("demo")).build();
        WebFilter medscribe = new SecurityHeadersFilter();
        WebFilter baymax = new BaymaxSecurityHeadersFilter();
        WebTestClient client = WebTestClient.bindToRouterFunction(pages)
                .webFilter(medscribeFirst ? medscribe : baymax, medscribeFirst ? baymax : medscribe).build();
        client.get().uri("/app/home").exchange()
                .expectHeader().valueEquals("Content-Security-Policy", BaymaxSecurityHeadersFilter.CSP)
                .expectHeader().valueEquals("X-Frame-Options", "DENY")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff");
        client.get().uri("/medscribeai/").exchange()
                .expectHeader().valueEquals("Content-Security-Policy", SecurityHeadersFilter.MEDSCRIBE_CSP);
    }
}
```

  Change the draft's assertion set to the final policy: in `baymaxPathsGetSelfHostedFontsAndScriptsAndNothingInlineInEitherOrder`
  add `.contains("img-src 'self' https: http: blob:").contains("connect-src 'self'")`, and add the row
  `"/app/add,true", "/app/add,false"` (the tray thumbnails are `blob:` URLs). In `BaymaxCspRegisteredTest` (baymax
  module) the paths are derived from the router, and the bean is proven registered by Baymax's own configuration (the
  configuration the deployable loads when `baymax.enabled=true`):

```java
package com.elioo.baymax.web.adapter.in;

import com.elioo.baymax.web.adapter.in.ui.WebUiTestKitAccess;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Every route the web UI publishes is a Baymax page for the CSP; the JSON API and MedScribe are not. Derived, not listed. */
class BaymaxCspRegisteredTest {

    @Test
    void everyWebUiRouteIsCoveredAndNothingElseIs() {
        List<String> uris = WebUiTestKitAccess.fixtureUris();
        assertThat(uris).as("fixture URIs of every route").hasSizeGreaterThanOrEqualTo(20);
        assertThat(uris).allSatisfy(u -> assertThat(BaymaxSecurityHeadersFilter.isBaymaxPage(u.replaceAll("\\?.*$", ""))).as(u).isTrue());
        for (String other : List.of("/api/v1/baymax/me", "/medscribeai/", "/actuator/health", "/appendix", "/assetsx")) {
            assertThat(BaymaxSecurityHeadersFilter.isBaymaxPage(other)).as(other).isFalse();
        }
    }
}
```

  (`WebUiTestKitAccess` is a public test helper in the `ui` package that returns `PageFixtures.ALL` values' URIs, so a
  route added later is covered by this test the moment its fixture exists.) Add to an existing Baymax context-runner
  test that loads `BaymaxAutoConfiguration` against Testcontainers (`PostgresNudgeAdapterTest`'s `runner()`):
  `assertThat(context.getBeansOfType(org.springframework.web.server.WebFilter.class).values()).hasAtLeastOneElementOfType(BaymaxSecurityHeadersFilter.class);`
  and, through `WebTestClient.bindToApplicationContext(context).build()`, `GET /app/` answers
  `Content-Security-Policy: <CSP>`.
- [ ] **Step 3: red, on the defect.** `./gradlew :medscribe-ai:test --tests '*BaymaxPathCspTest'` →
  `expected: "default-src 'none'; style-src 'self'; font-src 'self'; …" but was: "default-src 'none'; style-src 'self'; img-src 'self' https: http:; …"`
  — today's `STRICT_CSP`, which has no `font-src`/`script-src` and would block every self-hosted font and script.
- [ ] **Step 4: implement** the `filter` body:

```java
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (isBaymaxPage(exchange.getRequest().getPath().value())) {
            exchange.getResponse().beforeCommit(() -> {
                exchange.getResponse().getHeaders().set("Content-Security-Policy", CSP);
                return Mono.empty();
            });
        }
        return chain.filter(exchange);
    }
```

- [ ] **Step 5: run** → green. `SecurityHeadersFilterTest` (unchanged) stays green.

### Task L2a.2: no inline script, style attribute or handler on any page — derived from the router

**Files:** create test `ui/NoInlineCodeTest.java`.

- [ ] **Step 1: test** (green on today's pages; its value is the replay and every page added later):

```java
package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D12: script-src 'self' and style-src 'self' block inline script, style="" and on* handlers. A page that relied on one
 * would break silently in the browser and pass every server test, so every page the router serves is read here.
 */
class NoInlineCodeTest {

    private static final Pattern INLINE_SCRIPT = Pattern.compile("<script(?![^>]*\\bsrc=)[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_ATTR = Pattern.compile("<[^>]*\\sstyle\\s*=", Pattern.CASE_INSENSITIVE);
    private static final Pattern HANDLER = Pattern.compile("<[^>]*\\son[a-z]+\\s*=", Pattern.CASE_INSENSITIVE);

    @Test
    void noPageCarriesInlineScriptStyleOrHandlers() {
        Map<String, String> pages = RenderedPages.all();
        assertThat(pages).as("pages rendered from the router's routes").hasSizeGreaterThan(10);
        List<String> found = new ArrayList<>();
        pages.forEach((where, html) -> {
            INLINE_SCRIPT.matcher(html).results().forEach(r -> found.add(where + " inline script " + r.group()));
            STYLE_ATTR.matcher(html).results().forEach(r -> found.add(where + " style attribute " + r.group()));
            HANDLER.matcher(html).results().forEach(r -> found.add(where + " handler " + r.group()));
        });
        assertThat(found).isEmpty();
    }
}
```

- [ ] **Step 2: replay (mutants).** Add `<span style="color:red">` to `LandingPages.landing` → red
  `GET / bn style attribute <span style=`. Add `<script>x()</script>` → `GET / bn inline script <script>`. Revert.

### Task L2a.3: ship

- [ ] `./gradlew :baymax:test :medscribe-ai:test`, then the standard pre-merge. Commit
  `feat(baymax-ui): a Baymax-path CSP for self-hosted fonts and scripts, and no inline code on any page`.

### Try it (A4) — prod-web

- [ ] `curl -sI https://medioo.eliooo.org/app | grep -i content-security` shows `font-src 'self'; script-src 'self';
  connect-src 'self'; img-src 'self' https: http: blob:`. `curl -sI https://medioo.eliooo.org/medscribeai/ | grep -i
  content-security` still shows the Tailwind policy and the MedScribe demo loads.
- [ ] Laptop DevTools console on `/app/home`: no "Refused to load" line.

---

## PR A5 — L4a: the app shell and three tabs

Branch `feat/baymax-ui-shell-tabs`. Spec §3: three always-labelled tabs as plain links (চ্যাট `/app/home` ·
কাগজপত্র `/app/timeline` · পরিবার `/app/family`), a 56 px header with the text wordmark (kit §2: never a link inside
the app) and whose papers are in view. **A patient is never guessed** (PatientSwitcher README): one visible patient is
simply in view; with two or more and no `?patient=`, every tab shows the chooser and reads no one's papers. The
remembered choice (cookie), the switcher sheet and the legacy redirects are C1. The চ্যাট tab is a placeholder until C4:
the greeting, a link to the papers, and a composer that says chat is coming and cannot send.

### Task L4a.1: the failing route tests

**Files:** create test `ui/AppShellRoutesTest.java`; modify `PageFixtures` (+`GET /app/timeline`, `GET /app/family`).

- [ ] **Step 1:**

```java
package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.web.domain.PatientSummary;
import com.elioo.baymax.web.domain.TimelinePage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Spec §3: three always-labelled tabs as links, a header naming whose papers are in view, and no guessing. */
class AppShellRoutesTest {

    private final WebUiTestKit kit = new WebUiTestKit();
    private final PatientSummary ma = kit.patient("আম্মা", true, 0);
    private final PatientSummary baba = kit.patient("আব্বু", true, 0);

    private void noPapers(PatientSummary p) {
        when(kit.timeline.timeline(eq(WebUiTestKit.FAMILY), eq(p.patient().id()), any())).thenReturn(Mono.just(new TimelinePage(List.of(), null)));
    }

    static String between(String html, String start, String end) {
        int i = html.indexOf(start);
        assertThat(i).as("page contains " + start).isNotNegative();
        return html.substring(i, html.indexOf(end, i) + end.length());
    }

    @ParameterizedTest
    @EnumSource(Tab.class)
    void eachTabAnswersBehindASessionAndMarksOnlyItselfCurrent(Tab tab) {
        kit.familyWith(ma);
        noPapers(ma);
        String nav = between(kit.page(tab.path, "bn"), "<nav class=\"tabs\"", "</nav>");
        assertThat(Pattern.compile("aria-current=\"page\"").matcher(nav).results().count()).isEqualTo(1);
        assertThat(nav).contains("<a href=\"" + tab.path + "\" aria-current=\"page\">");
        assertThat(nav).contains(">চ্যাট<").contains(">কাগজপত্র<").contains(">পরিবার<").doesNotContain("টাইমলাইন");
    }

    @ParameterizedTest
    @EnumSource(Tab.class)
    void withoutASessionEveryTabGoesToSignIn(Tab tab) {
        kit.client.get().uri(tab.path).exchange().expectStatus().isSeeOther()
                .expectHeader().valueEquals(HttpHeaders.LOCATION, "/app/");
    }

    /** PatientSwitcher README, on every tab: never preselect between two; the chooser, and no one's papers are read. */
    @ParameterizedTest
    @EnumSource(value = Tab.class, names = {"CHAT", "PAPERS"})
    void twoPatientsAndNoChoiceShowTheChooserAndReadNoOnesPapers(Tab tab) {
        kit.familyWith(ma, baba);
        String html = kit.page(tab.path, "bn");
        assertThat(html).contains("কার কাগজ দেখবেন?")
                .contains("href=\"" + tab.path + "?patient=" + ma.patient().id() + "\"")
                .contains("href=\"" + tab.path + "?patient=" + baba.patient().id() + "\"");
        verify(kit.timeline, never()).timeline(any(), any(), any());
        verify(kit.timeline, never()).document(any(), any());
    }

    /** The other direction: one patient is simply in view — no chooser, and the header names her. */
    @ParameterizedTest
    @EnumSource(value = Tab.class, names = {"CHAT", "PAPERS"})
    void onePatientIsInViewWithoutAsking(Tab tab) {
        kit.familyWith(ma);
        noPapers(ma);
        String html = kit.page(tab.path, "bn");
        assertThat(between(html, "<header class=\"appbar\"", "</header>")).contains("আম্মা");
        assertThat(html).doesNotContain("কার কাগজ দেখবেন?");
    }

    @Test
    void choosingByUrlShowsThatPatientAndAnUnseenPatientIs404() {
        kit.familyWith(ma, baba);
        noPapers(baba);
        assertThat(between(kit.page("/app/timeline?patient=" + baba.patient().id(), "bn"), "<header class=\"appbar\"", "</header>"))
                .contains("আব্বু").doesNotContain("আম্মা");
        kit.client.get().uri("/app/timeline?patient=" + UUID.randomUUID()).cookie("baymax_session", "tok").exchange()
                .expectStatus().isNotFound();
    }

    /** The চ্যাট placeholder: the composer says chat is coming and can send nothing (C4 keeps this test). */
    @Test
    void theChatComposerSaysChatIsComingAndCannotSend() {
        kit.familyWith(ma);
        String composer = between(kit.page(Tab.CHAT.path, "bn"), "<div class=\"composer\"", "</div>");
        assertThat(composer).contains(kit.copy.t(Lang.BN, "chat.soon"))
                .doesNotContain("<input").doesNotContain("<textarea").doesNotContain("<form").doesNotContain("type=\"submit\"");
    }

    @Test
    void initialsKeepTheirVowelSignsAndConjuncts() {
        assertThat(AppShell.initial("আম্মা")).isEqualTo("আ");
        assertThat(AppShell.initial("রুমানা")).isEqualTo("রু");
        assertThat(AppShell.initial("স্বপ্না")).isEqualTo("স্ব");
        assertThat(AppShell.initial("Ma")).isEqualTo("M");
    }
}
```

- [ ] **Step 2: stubs, then the red on each rule.** Add `enum Tab` with the three paths, `AppTabs.chat/papers/family`
  returning `ServerResponse.ok().build()`, their routes, `AppShell.initial` returning `""`. Run:
  `page contains <nav class="tabs" … Expecting actual: -1 to be greater than or equal to 0`, `expected: "আ" but was: ""`,
  and the chooser test `Expecting actual "" to contain "কার কাগজ দেখবেন?"`.

### Task L4a.2: `Tab`, `Icons`, `ActivePatient`, `AppShell`, the three tab bodies

**Files:** create `ui/{Tab,Icons,ActivePatient,AppShell}.java`; modify `ui/AppTabs.java`, `ui/DocumentPages.java`,
`ui/ProfilePages.java`, `ui/Html.java` (+`appPage`), `css/20-shell.css`, `css/50-chat.css`, `ui-shell_*`, `ui-chat_*`,
`TokenContrastTest.EXEMPT`, `BanglaNumbersOnPagesTest`.

- [ ] **Step 1: `Tab` and `Icons`** (kit §1 paths; `currentColor` only, so `DesignTokensTest` stays green):

```java
package com.elioo.baymax.web.adapter.in.ui;

/** Spec §3: the three always-labelled tabs. চ্যাট keeps /app/home, where sign-in already lands. */
enum Tab {
    CHAT(PageChrome.BASE + "/home", "tab.chat", "chat"),
    PAPERS(PageChrome.BASE + "/timeline", "tab.papers", "papers"),
    FAMILY(PageChrome.BASE + "/family", "tab.family", "family");

    final String path;
    final String labelKey;
    final String icon;

    Tab(String path, String labelKey, String icon) {
        this.path = path;
        this.labelKey = labelKey;
        this.icon = icon;
    }
}
```

```java
package com.elioo.baymax.web.adapter.in.ui;

import java.util.Map;

/** The kit's 24 × 24 stroke icons (kit §1), always aria-hidden, coloured by the text around them. */
final class Icons {

    private static final Map<String, String> PATHS = Map.ofEntries(
            Map.entry("chat", "<path d=\"M20 11.5a7.5 7.5 0 0 1-10.9 6.7L4 19.5l1.3-4.6A7.5 7.5 0 1 1 20 11.5z\"/>"),
            Map.entry("papers", "<path d=\"M8 3.5h6.5L19 8v11a1.5 1.5 0 0 1-1.5 1.5h-9.5A1.5 1.5 0 0 1 6.5 19V5A1.5 1.5 0 0 1 8 3.5z\"/><path d=\"M14 3.5V8h5\"/><path d=\"M9.5 12.5h6M9.5 16h4\"/>"),
            Map.entry("family", "<circle cx=\"9\" cy=\"8\" r=\"3.25\"/><path d=\"M3 19.5a6 6 0 0 1 12 0\"/><circle cx=\"17\" cy=\"9.5\" r=\"2.5\"/><path d=\"M16.5 14.1a4.6 4.6 0 0 1 4.5 4.9\"/>"),
            Map.entry("calendar", "<rect x=\"3.5\" y=\"5\" width=\"17\" height=\"15.5\" rx=\"2\"/><path d=\"M3.5 10h17M8 3v4M16 3v4\"/>"),
            Map.entry("octagon", "<path d=\"M8 2.5h8l5.5 5.5v8L16 21.5H8L2.5 16V8z\"/><path d=\"M12 7.5v5.5M12 16.5h.01\"/>"),
            Map.entry("camera", "<path d=\"M4.5 8h2.8l1.8-2.5h5.8L16.7 8h2.8A1.5 1.5 0 0 1 21 9.5v8.5a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 18V9.5A1.5 1.5 0 0 1 4.5 8z\"/><circle cx=\"12\" cy=\"13.5\" r=\"3.5\"/>"),
            Map.entry("chevron-down", "<path d=\"M6 9l6 6 6-6\"/>"),
            Map.entry("chevron-right", "<path d=\"M9 6l6 6-6 6\"/>"),
            Map.entry("check", "<path d=\"M5 12.5l4.5 4.5L19 7.5\"/>"),
            Map.entry("page", "<path d=\"M7 3.5h7l4.5 4.5v12.5H7z\"/><path d=\"M14 3.5V8h4.5\"/>"),
            Map.entry("x", "<path d=\"M6 6l12 12M18 6L6 18\"/>"));

    private Icons() {
    }

    static String svg(String name, int size) {
        String paths = PATHS.get(name);
        if (paths == null) {
            throw new IllegalArgumentException("no icon " + name);
        }
        return "<svg aria-hidden=\"true\" width=\"" + size + "\" height=\"" + size + "\" viewBox=\"0 0 24 24\" fill=\"none\""
                + " stroke=\"currentColor\" stroke-width=\"1.75\" stroke-linecap=\"round\" stroke-linejoin=\"round\">" + paths + "</svg>";
    }
}
```

- [ ] **Step 2: `ActivePatient`** — A5's rule; C1 adds the remembered choice:

```java
package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.web.domain.FamilyOverview;
import com.elioo.baymax.web.domain.PatientSummary;
import org.springframework.web.reactive.function.server.ServerRequest;

import java.util.Optional;
import java.util.UUID;

/**
 * Whose papers are in view (spec §3): ?patient= if this family may see that patient (else 404, the same answer as a
 * patient that does not exist), else the only patient. Two or more and no choice: empty, and the tab asks. The visible
 * set is timeline.me(), i.e. records.visiblePatients in SQL; nothing the browser sends is trusted.
 */
record ActivePatient(Optional<PatientSummary> patient) {

    static ActivePatient resolve(ServerRequest request, FamilyOverview me) {
        Optional<String> asked = request.queryParam("patient");
        if (asked.isPresent()) {
            return new ActivePatient(Optional.of(find(me, asked.get())
                    .orElseThrow(() -> BaymaxException.notFound("patient_not_found", "no such patient"))));
        }
        return new ActivePatient(me.patients().size() == 1 ? Optional.of(me.patients().get(0)) : Optional.empty());
    }

    boolean is(PatientSummary p) {
        return patient.map(a -> a.patient().id().equals(p.patient().id())).orElse(false);
    }

    static Optional<PatientSummary> find(FamilyOverview me, String raw) {
        UUID id;
        try {
            id = UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return me.patients().stream().filter(p -> p.patient().id().equals(id)).findFirst();
    }
}
```

- [ ] **Step 3: `AppShell`** (header, tabs, chooser; the sheet arrives in C1):

```java
package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.web.domain.FamilyOverview;
import com.elioo.baymax.web.domain.PatientSummary;

import static com.elioo.baymax.web.adapter.in.ui.Html.esc;

/** The signed-in frame (spec §3; kit §2, §3): a 56 px header, the page, the disclaimer, the 64 px tab bar. No script. */
final class AppShell {

    private final UiCopy copy;

    AppShell(UiCopy copy) {
        this.copy = copy;
    }

    String page(Lang lang, Tab tab, FamilyOverview me, ActivePatient active, String body, Html.Head head, String bodyClass) {
        return Html.appPage(lang, head, header(lang, tab, me, active), body, copy.t(lang, "disclaimer"), tabs(lang, tab), "", bodyClass);
    }

    String header(Lang lang, Tab tab, FamilyOverview me, ActivePatient active) {
        StringBuilder b = new StringBuilder("<header class=\"appbar\"><span class=\"wm-text\" role=\"img\" aria-label=\"")
                .append(copy.t(lang, "app.title"))
                .append("\"><span aria-hidden=\"true\">Medi</span><span class=\"oo\" aria-hidden=\"true\">oo</span></span>");
        boolean many = me.patients().size() > 1;
        if (active.patient().isPresent()) {
            String name = active.patient().get().patient().name();
            String inner = "<span class=\"pav\" aria-hidden=\"true\">" + esc(initial(name)) + "</span><span>" + esc(name) + "</span>";
            b.append(many
                    ? "<a class=\"switcher\" href=\"" + tab.path + "\" aria-label=\"" + copy.t(lang, "switch.label", name) + "\">"
                            + inner + Icons.svg("chevron-down", 20) + "</a>"
                    : "<span class=\"switcher\">" + inner + "</span>");
        }
        return b.append("</header>").toString();
    }

    String tabs(Lang lang, Tab current) {
        StringBuilder b = new StringBuilder("<nav class=\"tabs\" aria-label=\"").append(copy.t(lang, "tabs.label")).append("\">");
        for (Tab t : Tab.values()) {
            b.append("<a href=\"").append(t.path).append("\"").append(t == current ? " aria-current=\"page\"" : "").append(">")
                    .append("<span class=\"pill\">").append(Icons.svg(t.icon, 24)).append("</span><span>")
                    .append(copy.t(lang, t.labelKey)).append("</span></a>");
        }
        return b.append("</nav>").toString();
    }

    /** Two or more patients and none chosen: the page body is this, and nothing about anyone's papers. */
    String chooser(Lang lang, Tab tab, FamilyOverview me, ActivePatient active) {
        return "<h1>" + copy.t(lang, "switch.title") + "</h1><p class=\"small\">" + copy.t(lang, "switch.hint") + "</p>"
                + choices(lang, tab, me, active);
    }

    String noPatients(Lang lang) {
        return "<h1>" + copy.t(lang, "tab.family") + "</h1><div class=\"card\">" + copy.t(lang, "patients.none") + "</div>";
    }

    String choices(Lang lang, Tab tab, FamilyOverview me, ActivePatient active) {
        StringBuilder b = new StringBuilder("<ul class=\"choices\">");
        for (PatientSummary p : me.patients()) {
            boolean chosen = active.is(p);
            b.append("<li><a class=\"pchoice\" href=\"").append(tab.path).append("?patient=").append(p.patient().id()).append("\"")
                    .append(chosen ? " aria-current=\"true\">" + Icons.svg("check", 18) : ">")
                    .append("<span class=\"pav\" aria-hidden=\"true\">").append(esc(initial(p.patient().name()))).append("</span>")
                    .append("<span><strong>").append(esc(p.patient().name())).append("</strong><span class=\"meta\">")
                    .append(copy.t(lang, "family.patient.meta", p.patient().age(), p.documents())).append("</span></span></a></li>");
        }
        return b.append("</ul>").toString();
    }

    /** The first letter as a reader sees it: with its vowel signs, and the whole conjunct when a hasanta joins one. */
    static String initial(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String n = name.strip();
        int end = n.offsetByCodePoints(0, 1);
        while (end < n.length()) {
            int cp = n.codePointAt(end);
            int type = Character.getType(cp);
            if (type != Character.NON_SPACING_MARK && type != Character.COMBINING_SPACING_MARK) {
                break;
            }
            end = n.offsetByCodePoints(end, 1);
            if (cp == '্' && end < n.length()) {   // hasanta: take the consonant it joins
                end = n.offsetByCodePoints(end, 1);
            }
        }
        return n.substring(0, end);
    }
}
```

  `Html.appPage(Lang, Head, String header, String body, String disclaimer, String tabs, String sheet, String bodyClass)`
  writes the same head as `page` and `<body class="in-app[ bodyClass]"><div class="shell"[ inert]>header<main class="app">body<footer
  class="foot">disclaimer</footer></main>tabs</div>[sheet]</body>`; `inert` is set only when `sheet` is not empty (C1).
- [ ] **Step 4: `AppTabs`** — the three tabs through one `tab(...)`; the bodies are today's markup, moved:

```java
    record Body(String html, Html.Head head, String bodyClass) {
        static Body of(String html, Html.Head head) {
            return new Body(html, head, "");
        }
    }

    @FunctionalInterface
    interface Renderer {
        Mono<Body> render(ServerRequest request, Lang lang, FamilyOverview me, ActivePatient active);
    }

    public Mono<ServerResponse> chat(ServerRequest request) {
        return tab(request, Tab.CHAT, this::chatBody);
    }

    public Mono<ServerResponse> papers(ServerRequest request) {
        return tab(request, Tab.PAPERS, this::papersBody);
    }

    public Mono<ServerResponse> family(ServerRequest request) {
        return tab(request, Tab.FAMILY, this::familyBody);
    }

    private Mono<ServerResponse> tab(ServerRequest request, Tab tab, Renderer renderer) {
        WebSession session = SessionAuthFilter.session(request);
        Lang lang = Lang.of(request);
        AppShell shell = new AppShell(copy);
        return timeline.me(session.familyId()).flatMap(me -> {
            ActivePatient active = ActivePatient.resolve(request, me);
            Html.Head head = Html.Head.of(copy.t(lang, tab.labelKey) + " — " + copy.t(lang, "app.title"));
            Mono<Body> body = active.patient().isEmpty() && tab != Tab.FAMILY
                    ? Mono.just(Body.of(me.patients().isEmpty() ? shell.noPatients(lang) : shell.chooser(lang, tab, me, active), head))
                    : renderer.render(request, lang, me, active);
            return body.flatMap(b -> Html.html(HttpStatus.OK, shell.page(lang, tab, me, active, b.html(), b.head(), b.bodyClass())));
        });
    }

    /** C4 replaces this with the attention stack (s06). Until then: the greeting, the papers, the honest composer. */
    Mono<Body> chatBody(ServerRequest request, Lang lang, FamilyOverview me, ActivePatient active) {
        PatientSummary p = active.patient().orElseThrow();
        return Mono.just(new Body("<p class=\"bubble\">" + copy.t(lang, "chat.hello", me.family().ownerName()) + "</p>"
                + "<p><a class=\"btn quiet\" href=\"" + Tab.PAPERS.path + "?patient=" + p.patient().id() + "\">"
                + copy.t(lang, "chat.papers", p.patient().name()) + "</a></p>"
                + "<div class=\"composer\"><p class=\"soon\">" + copy.t(lang, "chat.soon") + "</p></div>",
                Html.Head.of(copy.t(lang, "tab.chat") + " — " + copy.t(lang, "app.title")), "has-composer"));
    }
```

  - **`papersBody`** is today's `timelinePage` body minus the back link and the `top(...)` call, for
    `active.patient()`; it returns `Body.of(html, head.refresh(inFlight ? 5 : 0))` and points "আরও দেখুন" at
    `Tab.PAPERS.path + "?patient=<id>&cursor=…"`. The upload form stays until A7 replaces it with the link.
  - **`familyBody`** is today's `home` loop (patient cards linking `/app/patients/{id}/profile`), plus the language
    toggle (`chrome.langToggle(lang, Tab.FAMILY.path)`) and the logout form from `top(...)`. A6 replaces it.
  - `home` and `timelinePage` stay as methods only for the old `GET /app/patients/{id}` route (until C1's redirect);
    `GET /app/home` now routes to `chat`.
  - `DocumentPages` (document, delete confirm, deleted) and `ProfilePages` render through
    `Html.appPage(lang, head, <wordmark-only header>, body, disclaimer, new AppShell(copy).tabs(lang, Tab.PAPERS|Tab.FAMILY), "", "")`;
    their back links become `<a class="back" href="/app/timeline?patient=<id>">` with `app.back.papers` (and
    `/app/family` with `app.back.family` for the profile).
- [ ] **Step 5: copy** (`ui-shell_*` unless marked; English in the same table):

  | key | bn | en |
  |---|---|---|
  | `tab.chat` / `tab.papers` / `tab.family` | `চ্যাট` / `কাগজপত্র` / `পরিবার` | `Chat` / `Papers` / `Family` |
  | `tabs.label` | `প্রধান` | `Main` |
  | `switch.label` | `কার কাগজ দেখছেন — এখন {0}` | `Whose papers — now {0}` |
  | `switch.title` | `কার কাগজ দেখবেন?` | `Whose papers do you want to see?` |
  | `switch.hint` | `কার কাগজ বেছে নিন — Medioo নিজে আন্দাজ করবে না` | `Choose whose papers — Medioo will not guess` |
  | `sheet.close` | `বন্ধ` | `Close` |
  | `paper.label` | `কাগজে লেখা` | `Written on the paper` |
  | `family.patient.meta` | `{0} বছর · {1}টি কাগজ` | `{0} years · {1} papers` |
  | `app.back.papers` / `app.back.family` | `‹ কাগজপত্র` / `‹ পরিবার` | `‹ Papers` / `‹ Family` |
  | `chat.hello` (ui-chat) | `হ্যালো, {0}।` | `Hello, {0}.` |
  | `chat.papers` (ui-chat) | `কাগজপত্র দেখুন — {0}` | `See the papers — {0}` |
  | `chat.soon` (ui-chat) | `জিজ্ঞেস করার সুবিধা শিগগিরই আসছে। ততক্ষণ কাগজপত্রে সব কাগজ আর মান দেখুন।` | `Asking questions is coming soon. Until then, see every paper and value under Papers.` |

  (`chat.papers` keeps the name standalone: no `{0}-এর` until D2's possessive.)
- [ ] **Step 6: CSS.** Into `20-shell.css` (tokens only):

```css
/* app shell (spec §3; kit §2, §3, §17) */
body.in-app{padding-bottom:calc(var(--tabbar-h) + env(safe-area-inset-bottom))}
.appbar{position:sticky;top:0;z-index:2;display:flex;align-items:center;justify-content:space-between;gap:var(--sp-3);min-height:var(--header-h);padding:0 var(--sp-4);background:var(--c-bg);border-bottom:1px solid var(--c-line)}
.wm-text{font-family:var(--font-latin);font-size:var(--fs-title-2);line-height:1.3;font-weight:700;letter-spacing:-.2px;color:var(--c-text)}
.wm-text .oo{color:var(--c-brand-green)} /* logotype: exempt from contrast (WCAG 1.4.3); never used for text */
.switcher{display:inline-flex;align-items:center;gap:var(--sp-2);min-height:var(--target-min);padding:0 var(--sp-3) 0 6px;border:1px solid var(--c-outline);border-radius:var(--r-pill);background:var(--c-surface);color:var(--c-text);text-decoration:none;font-size:var(--fs-label-large);line-height:var(--lh-label-large);font-weight:600}
.pav{display:inline-flex;align-items:center;justify-content:center;flex:none;width:34px;height:34px;border-radius:var(--r-pill);background:var(--c-primary-container);color:var(--c-on-primary-container);font-size:var(--fs-label);font-weight:700}
.tabs{position:fixed;left:0;right:0;bottom:0;z-index:2;display:flex;height:calc(var(--tabbar-h) + env(safe-area-inset-bottom));padding-bottom:env(safe-area-inset-bottom);background:var(--c-surface);box-shadow:var(--shadow-2)}
.tabs a{flex:1;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:var(--sp-0);min-height:var(--target-min);text-decoration:none;color:var(--c-text-muted);font-size:var(--fs-label-small);line-height:var(--lh-label-small);font-weight:600}
.tabs .pill{display:flex;align-items:center;justify-content:center;width:64px;height:32px;border-radius:var(--r-pill)}
.tabs a[aria-current=page]{color:var(--c-text);font-weight:700}
.tabs a[aria-current=page] .pill{background:var(--c-primary-container)}
.back{display:inline-flex;align-items:center;min-height:var(--target-min);color:var(--c-primary);font-weight:600;text-decoration:none}
.sheet-wrap{position:fixed;inset:0;z-index:3}
.scrim{position:absolute;inset:0;background:var(--c-scrim)}
.sheet{position:absolute;left:0;right:0;bottom:0;max-width:var(--w-app);max-height:92%;margin:0 auto;overflow:auto;display:flex;flex-direction:column;gap:var(--sp-4);padding:var(--sp-2) var(--sp-4) calc(var(--sp-5) + env(safe-area-inset-bottom));background:var(--c-surface);border-radius:var(--r-xl) var(--r-xl) 0 0;box-shadow:var(--shadow-3)}
.grab{align-self:center;width:36px;height:4px;border-radius:var(--r-pill);background:var(--c-outline)}
.sheet-head{display:flex;justify-content:space-between;align-items:center;gap:var(--sp-3)}
.sheet-head h2{margin:0;font-size:var(--fs-title-2);line-height:var(--lh-title-2)}
.icon-btn{display:inline-flex;align-items:center;justify-content:center;flex:none;width:var(--target-min);height:var(--target-min);border-radius:var(--r-pill);background:var(--c-surface-2);color:var(--c-text)}
.choices{list-style:none;margin:0;padding:0;display:flex;flex-direction:column;gap:var(--sp-2)}
.pchoice{display:flex;align-items:center;gap:var(--sp-3);min-height:56px;padding:var(--sp-2) var(--sp-4);border:1px solid var(--c-outline);border-radius:var(--r-pill);background:var(--c-surface);color:var(--c-text);text-decoration:none}
.pchoice[aria-current=true]{background:var(--c-primary-container);border:2px solid var(--c-primary)}
.pchoice .meta{display:block}
```

  and into `50-chat.css` the two chat rules of the same draft block (`.bubble`, `.composer`, `.composer .soon`) plus
  `body.in-app.has-composer{padding-bottom:calc(var(--tabbar-h) + var(--composer-h) + env(safe-area-inset-bottom))}`.
  In `20-shell.css` the line `.wm-text{…line-height:1.3…}` becomes `line-height:var(--lh-title-2)` (TypeScaleTest), and
  `TokenContrastTest.EXEMPT` gains `".wm-text .oo"` (logotype).
- [ ] **Step 7: fixtures and moved paths.** `PageFixtures`: `GET /app/timeline` → `/app/timeline` with `Stubs::maTimeline`,
  `GET /app/family` → `/app/family` with `Stubs::familyOfMa`. `BanglaNumbersOnPagesTest`: `/app/home` → `/app/family`
  (the patient cards moved) and `/app/patients/<id>` → `/app/timeline?patient=<id>`.

### Task L4a.3: run, replay, ship

- [ ] `./gradlew :baymax:test` → green (`AppShellRoutesTest`, every derived test over the two new pages:
  `NoInlineCodeTest`, `TouchTargetTest`, `TokenContrastTest`, `BaymaxCspRegisteredTest`).
- [ ] **Replay (mutant):** make `ActivePatient.resolve` fall back to `me.patients().get(0)` → red
  `twoPatientsAndNoChoice…[CHAT] Expecting actual … to contain "কার কাগজ দেখবেন?"` and `never()` fails on
  `timeline.timeline(…)` for PAPERS. Revert. Paste both.
- [ ] `git status --ignored --short -- baymax/src | grep '^!!'` prints nothing; standard pre-merge. Commit
  `feat(baymax-ui): three bottom tabs and a shell for every signed-in page; never guess the patient`.

### Try it (A5)

- [ ] **prod-web:** sign in (code from WhatsApp). You land on **চ্যাট**: the greeting, "কাগজপত্র দেখুন — আম্মা", and a
  bar at the bottom saying chat is coming, where nothing can be typed. Three labelled tabs; the tapped one is highlighted.
- [ ] **prod-web:** কাগজপত্র lists Ma's papers as yesterday; a document opens with the tabs still at the bottom and
  "‹ কাগজপত্র" returns. পরিবার lists Ma; her profile link still saves flags.
- [ ] **local** (two patients, P.1 step 2): চ্যাট and কাগজপত্র ask "কার কাগজ দেখবেন?" and show nobody's papers until one
  is tapped.
- [ ] **prod-web:** Chrome → Site settings → JavaScript blocked: every tab still works.

---

## PR A6 — L5: the পরিবার tab (s21 basics)

Branch `feat/baymax-ui-family-tab`. What a family can do here tonight, all existing use cases: see the patient cards;
for the patient in view see name, age and sex, tick the chronic flags (asked, never inferred) and save, see the consent
date, see and stop reminders; and for the account see the owner's name, the WhatsApp number (shown once, only here),
switch language, and sign out. Deleting a patient or everything, turning reminders back on, sharing and the time zone
are not built here (E1 and E2 propose the first two; sharing is Phase 5). The language toggle lives on this tab (s21),
not in the app header.

### Task L5.1: the failing tests

**Files:** create test `ui/FamilyViewTest.java`; modify `WebUiTestKit` (default stub
`when(nudgeData.patient(any())).thenReturn(Mono.empty())`).

- [ ] **Step 1:**

```java
package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.nudge.application.port.out.NudgeDataPort;
import com.elioo.baymax.web.domain.PatientSummary;
import com.elioo.baymax.web.domain.TimelinePage;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** s21 basics: what the owner may change, what a share member may only see, and the account. */
class FamilyViewTest {

    private final WebUiTestKit kit = new WebUiTestKit();

    private String family(PatientSummary p, boolean optedOut) {
        WebUiTestKit.FAMILYWith(p);
        when(kit.nudgeData.patient(p.patient().id())).thenReturn(Mono.just(new NudgeDataPort.PatientRef(p.patient().id(),
                WebUiTestKit.FAMILY, List.of("diabetes"), optedOut ? WebUiTestKit.NOW : null, null)));
        return kit.page("/app/family", "bn");
    }

    @Test
    void theOwnerCanSaveTheFlagsAndTurnRemindersOff() {
        PatientSummary ma = kit.patient("আম্মা", true, 12);
        String html = family(ma, false);
        assertThat(html).contains("action=\"/app/patients/" + ma.patient().id() + "/profile\"")
                .contains("<button type=\"submit\">সেভ করুন</button>")
                .contains("action=\"/app/patients/" + ma.patient().id() + "/nudges/opt-out\"")
                .contains("রিমাইন্ডার চাই না — আম্মা").contains("আম্মা: রিমাইন্ডার চালু আছে");
        assertThat(html).contains("৬৪ বছর · ১২টি কাগজ");   // Bangla digits for UI counts (rule 13)
    }

    /** The other direction: a share member sees the same papers and changes nothing (spec §3, D30). */
    @Test
    void aShareMemberSeesButHasNoSaveAndNoReminderSwitch() {
        PatientSummary shared = kit.patient("আম্মা", false, 12);
        String html = family(shared, false);
        assertThat(html).contains("শেয়ার করা").doesNotContain("<button type=\"submit\">সেভ করুন</button>")
                .doesNotContain("/nudges/opt-out");
    }

    @Test
    void theAccountShowsTheOwnersNumberOnceAndOnlyHere() {
        PatientSummary ma = kit.patient("আম্মা", true, 0);
        String html = family(ma, false);
        assertThat(java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(PhoneDisplay.full(WebUiTestKit.OWNER_NUMBER)))
                .matcher(html).results().count()).isEqualTo(1);
        assertThat(html).contains("<form method=\"post\" action=\"/app/logout\">")
                .contains("href=\"/lang/en?back=%2Fapp%2Ffamily\"");
        // the other direction: no other tab carries a phone number in any form
        when(kit.timeline.timeline(eq(WebUiTestKit.FAMILY), eq(ma.patient().id()), any())).thenReturn(Mono.just(new TimelinePage(List.of(), null)));
        for (Tab t : List.of(Tab.CHAT, Tab.PAPERS)) {
            assertThat(kit.page(t.path, "bn")).as(t.path).doesNotContainPattern(BaymaxWebUiRouterTest.BD_MOBILE);
        }
    }

    @Test
    void savingComesBackToTheTabAndSaysSo() {
        PatientSummary ma = kit.patient("আম্মা", true, 0);
        family(ma, false);
        when(kit.timeline.updateChronicFlags(eq(WebUiTestKit.FAMILY), eq(ma.patient().id()), any())).thenReturn(Mono.just(ma.patient()));
        kit.client.post().uri("/app/patients/" + ma.patient().id() + "/profile").cookie("baymax_session", "tok")
                .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED).bodyValue("flag=diabetes")
                .exchange().expectStatus().isSeeOther()
                .expectHeader().valueEquals(org.springframework.http.HttpHeaders.LOCATION, "/app/family?patient=" + ma.patient().id() + "&saved=1");
        assertThat(kit.page("/app/family?patient=" + ma.patient().id() + "&saved=1", "bn")).contains("সেভ হয়েছে");
        assertThat(kit.page("/app/family?patient=" + ma.patient().id(), "bn")).doesNotContain("সেভ হয়েছে");
    }

    /** The old profile URL (WhatsApp opt-out pages, bookmarks) opens the পরিবার tab with the patient chosen. */
    @Test
    void theOldProfileUrlOpensTheFamilyTab() {
        UUID id = UUID.randomUUID();
        kit.client.get().uri("/app/patients/" + id + "/profile").cookie("baymax_session", "tok").exchange().expectStatus().isFound()
                .expectHeader().valueEquals(org.springframework.http.HttpHeaders.LOCATION, "/app/family?patient=" + id);
    }
}
```

  (The name stays standalone until D2's possessive: D2 rewrites these two strings to "আম্মার রিমাইন্ডার …" and this
  test with them.)

- [ ] **Step 2: red.** `Expecting actual … to contain "<button type="submit">সেভ করুন</button>"`: the A5 body still says
  `সংরক্ষণ করুন` and has no consent or account section; `theOldProfileUrlOpensTheFamilyTab` gets `200` instead of `302`.

### Task L5.2: `FamilyView`, the redirect-after-post, ship

**Files:** create `ui/FamilyView.java`; modify `ui/AppTabs.java` (`familyBody`; constructor gains `NudgeDataPort nudgeData`
last; `WebUiTestKit` passes it), `ui/ProfilePages.java` (`GET /app/patients/{id}/profile` → 302
`/app/family?patient=<id>`; `saveProfile` → 303 `/app/family?patient=<id>&saved=1`; `optOutFromProfile` → 303
`/app/family?patient=<id>`; both `profilePage` methods deleted), `css/30-family.css`, `ui-family_*`.

- [ ] **Step 1: implement** `FamilyView` (package-private, built from `UiCopy`), called by `familyBody`. Sections in order:
  1. `<h1>` পরিবার; `<h2>` `patients`; the cards as `AppShell.choices(lang, Tab.FAMILY, me, active)`.
  2. With a patient in view: `<h2>` `family.info.title` (`{0}` = name), a `<dl class="facts">` with `family.info.age`
     and `family.info.sex` (`sex.<ENUM>`); the flags form (moved from `profilePage`, labels `flag.*`, hint
     `family.flags.hint`), with its submit rendered only when `p.owner()`.
  3. `<h2>` `family.consent.title`; `family.consent.at` with `copy.dateText(lang, LocalDate.ofInstant(p.patient().proxyConsentAt(), PatientCalendar.ZONE))`
     (`PatientCalendar` arrives in A10; until then `ZoneId.of("Asia/Dhaka")` in one constant on `FamilyView`, which
     A10 replaces).
  4. `<h2>` `profile.nudges`: `profile.nudges.on` / `.off` from `nudgeData.patient(id).map(PatientRef::optedOut)`, and
     the stop form for the owner when not opted out.
  5. `<h2>` `family.account.title`, a `<dl class="facts">`: `family.account.name` (`esc(ownerName)`),
     `family.account.number` (`esc(PhoneDisplay.full(me.family().whatsappNumber()))`), `family.account.lang`
     (`chrome.langToggle(lang, Tab.FAMILY.path)`).
  6. `<form method="post" action="/app/logout"><button type="submit" class="quiet">` + `app.logout` + `</button></form>`.
  7. With `?saved=1`: `<p class="saved" role="status">` + `profile.saved` at the top.
- [ ] **Step 2: copy** (`ui-family_*`): `profile.save=সেভ করুন` / `Save`; `profile.saved=সেভ হয়েছে` / `Saved`;
  `family.info.title=তথ্য — {0}` / `Details — {0}`; `family.info.age=বয়স` / `Age`; `family.info.sex=লিঙ্গ` / `Sex`;
  `sex.FEMALE=মহিলা`, `sex.MALE=পুরুষ`, `sex.OTHER=অন্য`, `sex.UNKNOWN=বলা হয়নি` (and English);
  `family.flags.hint=ডাক্তার যা বলেছেন, শুধু তা-ই টিক দিন। Medioo নিজে থেকে কিছু অনুমান করে না।`;
  `family.consent.title=সম্মতি` / `Consent`; `family.consent.at=আপনি নিশ্চিত করেছেন {0}` / `You confirmed on {0}`;
  `family.account.title=আপনার অ্যাকাউন্ট` / `Your account`; `family.account.name=নাম` / `Name`;
  `family.account.number=WhatsApp নম্বর` / `WhatsApp number`; `family.account.lang=ভাষা` / `Language`;
  `app.logout=সাইন আউট` / `Sign out`; `profile.nudges.on={0}: রিমাইন্ডার চালু আছে — Medioo নিজে থেকে মনে করিয়ে দেয়
  (ফলো-আপ, ওষুধ বদল, মান বদলানো, অনেকদিন খবর না থাকলে)।` / `{0}: reminders are on — …`;
  `profile.nudges.off={0}: রিমাইন্ডার বন্ধ আছে।` / `{0}: reminders are off.`; `profile.nudges.stop=রিমাইন্ডার চাই না — {0}` /
  `No reminders — {0}` (no "রোগী", no "বন্ধ করুন"). Remove the entries this heals from `GlossaryTest.KNOWN` (the ratchet
  fails until they are gone). Keys that existed in `ui_*` for the profile page move here (one owner per key:
  `UiCopyTest` fails on a copy left behind in `ui_*`). The profile-page key `profile.saved` stays one key.
- [ ] **Step 3: CSS** (`30-family.css`): `.facts{display:grid;grid-template-columns:auto 1fr;gap:var(--sp-2) var(--sp-4)}`,
  `.facts dt{color:var(--c-text-muted)}`, `.facts dd{margin:0}`, `.saved{padding:var(--sp-3) var(--sp-4);border-radius:var(--r-m);background:var(--c-primary-container);color:var(--c-on-primary-container)}`,
  and the flag checkboxes as `label.chip` rows with `min-height:var(--target-min)` (TouchTargetTest).
- [ ] **Step 4: fixtures.** `PageFixtures`: `GET /app/patients/{id}/profile` now expects the 302 (the fixture is unchanged;
  the derived page tests skip non-HTML answers), `GET /app/family` gets `Stubs::maProfile`.
- [ ] **Step 5: run** → green. **Replay (mutant):** render the submit regardless of `owner` → red
  `aShareMemberSees… Expecting actual … not to contain "<button type="submit">সেভ করুন</button>"`. Revert.
- [ ] **Step 6:** standard pre-merge. Commit `feat(baymax-ui): পরিবার — patients, chronic flags, consent date, reminders and the account`.

### Try it (A6) — prod-web

- [ ] পরিবার: Ma's card reads "৬৪ বছর · Nটি কাগজ" (Bangla digits). Tick ডায়াবেটিস, tap সেভ করুন: the page returns on
  পরিবার with "সেভ হয়েছে" and the tick kept; reload: the message is gone, the tick stays.
- [ ] Your number shows once, under আপনার অ্যাকাউন্ট, and on no other tab. Toggle English and back.
- [ ] **Not tried in production:** "রিমাইন্ডার চাই না" — it really stops BMX-8 reminders for Ma and there is no web way
  back on until E2. Try it **local**; in production only check that the line "আম্মা: রিমাইন্ডার চালু আছে" shows.
- [ ] Tap সাইন আউট: you are on the number page, and `/app/home` sends you back there.

---

## PR A7 — W4.1a: one allowance rule, `UploadService`, and the add-report sheet (`GET /app/add`, no script)

Branch `feat/baymax-web-add-sheet`. Spec §4.7 (s07): "কার কাগজ?", the patient chosen only when there is exactly one the
viewer may send for, three ways in (camera, gallery with several pages, PDF or screenshot) as real file inputs that post
with no script, and the free allowance stated **before** any upload. `/app/add?patient=<id>&back=<local path>` is the
one upload entry for the whole app (Global Constraints); this PR also turns the timeline's inline form into a link to
it. The POST, the refusals and the try documents are A8.

### Task W1a.1: one allowance rule for the sheet's line and intake's refusal

**Files:** create `healthrecord/domain/DocumentAllowance.java`; modify `FreeTierUseCase`, `FreeTierService.java:42-62`,
`FreeTierServiceTest` (its `CLOCK` is 2026-09-13T23:30Z).

- [ ] **Step 1: failing tests** (append to `FreeTierServiceTest`):

```java
    /**
     * The sheet's "আরও ৩টি" and intake's refusal come from one rule. Swept over every count from 0 to past the cap,
     * not two hand-picked cases: a sheet that promised one more while intake refused it would be the page lying.
     */
    @Test
    void theAllowanceTheSheetShowsAgreesWithTheRefusalAtEveryCount() {
        FamilyAccount family = family(FamilyAccount.Plan.FREE);
        int max = new BaymaxProperties().getFree().getMaxDocsPerMonth();
        int checked = 0;
        for (long used = 0; used <= max + 1; used++) {
            when(documents.countInMonth(eq(family.id()), eq(YearMonth.of(2026, 9)))).thenReturn(Mono.just(used));
            DocumentAllowance allowance = service.documentAllowance(family).block();
            Boolean refused = service.checkCanUploadDocument(family).thenReturn(false)
                    .onErrorResume(FreeTierExceededException.class, e -> Mono.just(true)).block();
            assertThat(allowance.allows(1)).as("used=%d", used).isEqualTo(!refused);
            assertThat(allowance.remaining()).as("used=%d", used).isEqualTo(Math.max(0, max - used));
            checked++;
        }
        assertThat(checked).isEqualTo(max + 2);   // the sweep ran; an empty loop agrees with anything
    }

    /** "আলাদা আলাদা" with three pages is three documents: refused when two remain, allowed when three do. */
    @Test
    void separatePagesCountAsSeparateDocumentsInBothDirections() {
        FamilyAccount family = family(FamilyAccount.Plan.FREE);
        when(documents.countInMonth(eq(family.id()), eq(YearMonth.of(2026, 9)))).thenReturn(Mono.just(1L));
        StepVerifier.create(service.checkCanUploadDocuments(family, 3))
                .expectErrorMatches(e -> e instanceof FreeTierExceededException f && f.reason().equals("free_tier_documents"))
                .verify();
        StepVerifier.create(service.checkCanUploadDocuments(family, 2)).verifyComplete();
    }

    @Test
    void theCapLiftsOnTheFirstOfTheNextUtcMonthAndAPayingFamilyHasNoCap() {
        FamilyAccount free = family(FamilyAccount.Plan.FREE);
        when(documents.countInMonth(eq(free.id()), eq(YearMonth.of(2026, 9)))).thenReturn(Mono.just(3L));
        assertThat(service.documentAllowance(free).block().resetsOn()).isEqualTo(LocalDate.of(2026, 10, 1));

        FamilyAccount paying = family(FamilyAccount.Plan.FAMILY);
        DocumentAllowance paid = service.documentAllowance(paying).block();
        assertThat(paid.limited()).isFalse();
        assertThat(paid.allows(10)).isTrue();
        verify(documents, never()).countInMonth(eq(paying.id()), any());   // a paying family is never counted
    }
```

- [ ] **Step 2: red.** Compile error first; then, with a stub `documentAllowance` returning
  `new DocumentAllowance(true, max, 0, month)`, `[used=3] expected: false but was: true` — the stub promising a slot
  intake refuses, the disagreement the sweep exists for.
- [ ] **Step 3: implement.**

```java
package com.elioo.baymax.healthrecord.domain;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * How many more documents a family may have read this UTC month (DR-24: only DONE consumes a slot). One value feeds
 * both the add-report sheet's line and intake's refusal, so the page can never promise what intake refuses.
 *
 * @param limited false for a paying plan: no line, no cap
 * @param month   the UTC calendar month counted; the cap lifts on the first day of the next one
 */
public record DocumentAllowance(boolean limited, int max, long used, YearMonth month) {

    public static DocumentAllowance unlimited(YearMonth month) {
        return new DocumentAllowance(false, 0, 0, month);
    }

    public long remaining() {
        return limited ? Math.max(0, max - used) : Long.MAX_VALUE;
    }

    public boolean allows(int documents) {
        return !limited || documents <= remaining();
    }

    public LocalDate resetsOn() {
        return month.plusMonths(1).atDay(1);
    }
}
```

  `FreeTierUseCase` gains `Mono<DocumentAllowance> documentAllowance(FamilyAccount family)`,
  `Mono<DocumentAllowance> documentAllowance(UUID familyId)`, `Mono<Void> checkCanUploadDocuments(FamilyAccount family, int documents)`
  and the `UUID` overload. `FreeTierService` writes the count once:

```java
    @Override
    public Mono<DocumentAllowance> documentAllowance(FamilyAccount family) {
        YearMonth month = YearMonth.now(clock.withZone(ZoneOffset.UTC));
        if (!family.isFree()) {
            return Mono.just(DocumentAllowance.unlimited(month));
        }
        int max = properties.getFree().getMaxDocsPerMonth();
        // Counted from the document table (BMX-2), DONE only (DR-24, PostgresDocumentRecordAdapter.countInMonth).
        return documents.countInMonth(family.id(), month).map(used -> new DocumentAllowance(true, max, used, month));
    }

    @Override
    public Mono<Void> checkCanUploadDocuments(FamilyAccount family, int count) {
        return documentAllowance(family).flatMap(a -> a.allows(count) ? Mono.<Void>empty()
                : Mono.error(new FreeTierExceededException(FreeTierExceededException.DOCUMENTS,
                        "free plan allows " + a.max() + " document(s) per calendar month")));
    }

    @Override
    public Mono<Void> checkCanUploadDocument(FamilyAccount family) {
        return checkCanUploadDocuments(family, 1);
    }
```

  The `UUID` overloads load the family as `checkCanUploadDocument(UUID)` does (`:57-62`). The existing free-tier tests
  stay green unchanged.
- [ ] **Step 4: run** `./gradlew :baymax:test --tests '*FreeTierServiceTest'` → green.

### Task W1a.2: `UploadService` — whom this viewer may send for, the allowance, and the upload itself

**Files:** create `web/domain/UploadOptions.java`, `web/application/port/in/UploadUseCase.java`,
`web/application/service/UploadService.java`, test `web/application/service/UploadServiceTest.java`; modify
`TimelineService.upload` (delegates to `UploadService.upload`; A8 removes it), `TimelineServiceTest` (the upload tests
move to `UploadServiceTest` verbatim, re-pointed).

- [ ] **Step 1: failing tests.**

```java
package com.elioo.baymax.web.application.service;

import com.elioo.baymax.extraction.application.port.in.DocumentIntakeUseCase;
import com.elioo.baymax.family.application.port.in.FreeTierUseCase;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.DocumentAllowance;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientAccess;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;
import com.elioo.baymax.web.application.port.out.AuditPort;
import com.elioo.baymax.web.domain.FamilyOverview;
import com.elioo.baymax.web.domain.PatientSummary;
import com.elioo.baymax.web.domain.UploadOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The add-report sheet's options and the upload behind it: owners only, never a guessed patient, the allowance first. */
class UploadServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-19T08:00:00Z");
    private static final UUID FAMILY = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4200001");
    private final TimelineUseCase timeline = mock(TimelineUseCase.class);
    private final HealthRecordPort records = mock(HealthRecordPort.class);
    private final FreeTierUseCase freeTier = mock(FreeTierUseCase.class);
    private final DocumentIntakeUseCase intake = mock(DocumentIntakeUseCase.class);
    private final AuditPort audit = mock(AuditPort.class);
    private UploadService service;
    private final FamilyAccount family = new FamilyAccount(FAMILY, "+8801700000000", "Tanvir", FamilyAccount.Plan.FREE, NOW, NOW);

    @BeforeEach
    void wire() {
        service = new UploadService(timeline, records, freeTier, intake, audit, Clock.fixed(NOW, ZoneOffset.UTC));
        when(audit.record(any())).thenReturn(Mono.empty());
    }

    private static PatientSummary summary(String name, boolean owner) {
        return new PatientSummary(new PatientProfile(UUID.nameUUIDFromBytes(name.getBytes()), FAMILY, name, 64,
                PatientProfile.Sex.FEMALE, List.of(), NOW, NOW), owner, 0, null);
    }

    @Test
    void theSheetOffersOnlyPatientsTheViewerMaySendForAndPreselectsOnlyWhenThereIsOne() {
        PatientSummary ma = summary("আম্মা", true);
        PatientSummary shared = summary("খালা", false);
        when(timeline.me(FAMILY)).thenReturn(Mono.just(new FamilyOverview(family, List.of(ma, shared))));
        when(freeTier.documentAllowance(family)).thenReturn(Mono.just(new DocumentAllowance(true, 3, 1, YearMonth.of(2026, 10))));

        StepVerifier.create(service.uploadOptions(FAMILY)).assertNext(o -> {
            assertThat(o.canSend()).containsExactly(ma);
            assertThat(o.viewOnly()).containsExactly(shared);
            assertThat(o.preselected()).contains(ma);
            assertThat(o.allowance().remaining()).isEqualTo(2);
        }).verifyComplete();
    }

    /** The other direction: two patients the viewer may send for, and neither is chosen for them (spec §4.7). */
    @Test
    void withTwoPatientsNothingIsPreselected() {
        assertThat(new UploadOptions(List.of(summary("আম্মা", true), summary("আব্বু", true)), List.of(),
                DocumentAllowance.unlimited(YearMonth.of(2026, 10))).preselected()).isEmpty();
    }

    /** Moved from TimelineServiceTest: a shared patient can be seen but not added to; nothing reaches intake. */
    @Test
    void aShareMemberCannotUploadAndIntakeIsNeverCalled() {
        PatientProfile ma = summary("আম্মা", false).patient();
        when(records.visiblePatient(FAMILY, ma.id())).thenReturn(Mono.just(new PatientAccess(ma, false)));
        StepVerifier.create(service.upload(FAMILY, ma.id(), List.of()))
                .expectErrorMatches(e -> e.getMessage().contains("can be seen but not added to")).verify();
        verify(intake, never()).accept(any(), any());
    }
}
```

- [ ] **Step 2: red.** Compile error; then, with `uploadOptions` putting every patient in `canSend`,
  `Expecting actual: [<আম্মা>, <খালা>] to contain exactly: [<আম্মা>]`.
- [ ] **Step 3: implement.**

```java
package com.elioo.baymax.web.domain;

/**
 * What the add-report sheet may offer this viewer: the patients they may send papers for (the owner today; D30's
 * "কাগজ পাঠাতে পারবেন" role joins here later), those they may only see, and this month's allowance.
 */
public record UploadOptions(List<PatientSummary> canSend, List<PatientSummary> viewOnly, DocumentAllowance allowance) {

    /** Preselect only when there is nobody else it could be (spec 4.7; never guess the patient, messages_bn:79). */
    public Optional<PatientSummary> preselected() {
        return canSend.size() == 1 ? Optional.of(canSend.get(0)) : Optional.empty();
    }
}
```

```java
package com.elioo.baymax.web.application.port.in;

/** Adding papers on the web: what the sheet may offer, and the upload itself (A7); "আলাদা আলাদা" and the key (D3). */
public interface UploadUseCase {
    Mono<UploadOptions> uploadOptions(UUID familyId);

    /** 404 for a patient this family cannot see, 403 for one it may only see (share member, D30). */
    Mono<Document> upload(UUID familyId, UUID patientId, List<Upload> uploads);
}
```

  `UploadService` (`@Service @RequiredArgsConstructor`, fields `timeline, records, freeTier, intake, audit, clock`):
  `uploadOptions` is

```java
    @Override
    public Mono<UploadOptions> uploadOptions(UUID familyId) {
        return timeline.me(familyId).flatMap(me -> freeTier.documentAllowance(me.family()).map(allowance -> new UploadOptions(
                me.patients().stream().filter(PatientSummary::owner).toList(),
                me.patients().stream().filter(p -> !p.owner()).toList(),
                allowance)));
    }
```

  and `upload` is today's `TimelineService.upload` body (`TimelineService.java:107-115`), moved verbatim; the
  `TimelineService` method now calls `uploads.upload(...)` (field appended last; `TimelineServiceTest`'s wire gets a mock)
  so nothing else changes in this PR.
- [ ] **Step 4: run** `./gradlew :baymax:test --tests '*UploadServiceTest' --tests '*TimelineServiceTest'` → green.

### Task W1a.3: `GET /app/add` — the sheet as a page that works without script, and one safe `back`

**Files:** create `ui/LocalPath.java`, tests `ui/LocalPathTest.java`, `ui/AddReportSheetPageTest.java`; modify
`ui/UploadUiHandler.java` (R1's class: + `sheet`, `sheetBody`, `row`, `capText`; `routes()` adds `GET /app/add` and is
given `UploadUseCase uploads` and `UiCopy copy`), `ui/PageChrome.switchLang` (uses `LocalPath`), `ui/AppTabs.papersBody`
(form → link), `WebUiTestKit` (+`final UploadUseCase uploads = mock(UploadUseCase.class)`), `PageFixtures`
(+`GET /app/add`), `config/BaymaxProperties.java` (+`Upload`), `application.properties`, `css/70-upload.css`,
`ui-upload_*`.

- [ ] **Step 1: failing tests.** `LocalPathTest` (both directions; `switchLang` had the same hole):

```java
package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** "back" is a page of this app or the landing, never another origin: browsers read /\host and /%5Chost as //host. */
class LocalPathTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
            "/app/timeline?patient=5e9edf6d-0a2a-4913-8675-9d07a4100002|/app/timeline?patient=5e9edf6d-0a2a-4913-8675-9d07a4100002",
            "/app/home|/app/home",
            "/|/",
            "//evil.example/x|/app/home",
            "/\\evil.example|/app/home",
            "/\\\\evil.example|/app/home",
            "/%5Cevil.example|/app/home",
            "/%5cevil.example|/app/home",
            "/%2F%2Fevil.example|/app/home",
            "https://evil.example|/app/home",
            "/api/v1/baymax/me|/app/home",
            "app/home|/app/home"})
    void onlyALocalAppPathIsKept(String raw, String expected) {
        assertThat(LocalPath.or(raw, "/app/home")).isEqualTo(expected);
    }
}
```

  `AddReportSheetPageTest` (on the kit; the methods are the draft's, re-pointed):

```java
package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.healthrecord.domain.DocumentAllowance;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.web.domain.PatientSummary;
import com.elioo.baymax.web.domain.UploadOptions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** s07 as the server renders it: every state, and a form that posts with no script at all. */
class AddReportSheetPageTest {

    private static final Instant NOW = WebUiTestKit.NOW;
    private final WebUiTestKit kit = new WebUiTestKit();

    static PatientSummary patient(String name, int age, boolean owner) {
        return new PatientSummary(new PatientProfile(UUID.nameUUIDFromBytes(name.getBytes()), WebUiTestKit.FAMILY, name, age,
                PatientProfile.Sex.FEMALE, List.of(), NOW, NOW), owner, 0, null);
    }

    static DocumentAllowance used(long n) {
        return new DocumentAllowance(true, 3, n, YearMonth.of(2026, 10));
    }

    private String sheet() {
        return kit.page("/app/add", "bn");
    }

    @Test
    void withOnePatientItIsChosenAndTheFormPostsWithoutAnyScript() {
        PatientSummary ma = patient("আম্মা", 64, true);
        when(kit.uploads.uploadOptions(WebUiTestKit.FAMILY)).thenReturn(Mono.just(new UploadOptions(List.of(ma), List.of(), used(0))));
        String html = sheet();
        assertThat(html).contains("<form id=\"add\" class=\"add\" method=\"post\" enctype=\"multipart/form-data\" action=\"/app/add\"");
        assertThat(html).containsPattern("<input type=\"radio\" name=\"patient\" value=\"" + ma.patient().id() + "\" required checked>");
        assertThat(html).contains("আম্মা · ৬৪");   // the age in Bangla digits (rule 13); the relation joins when the profile has one
        // three ways in, each a real input the browser posts on its own
        assertThat(html).contains("name=\"file\" accept=\"image/*\" capture=\"environment\"")
                .contains("name=\"file\" accept=\"image/*\" multiple")
                .contains("name=\"file\" accept=\"application/pdf,image/*\"");
        assertThat(html).contains("<button type=\"submit\" class=\"btn primary nojs\">পাঠান</button>");
        assertThat(html).doesNotContain(" disabled");   // nothing the server renders waits for a script to enable it
        assertThat(html).contains("এ মাসে বিনা খরচে আরও ৩টি কাগজ পড়াতে পারবেন। কোনো ছবি পড়তে না পারলে সেটা গোনা হবে না।");
        assertThat(html).doesNotContain("<script");   // A7 ships no script; D4 adds exactly one
    }

    @Test
    void withTwoPatientsNeitherIsChosenAndTheChoiceIsRequired() {
        PatientSummary ma = patient("আম্মা", 64, true);
        PatientSummary baba = patient("আব্বু", 72, true);
        when(kit.uploads.uploadOptions(WebUiTestKit.FAMILY)).thenReturn(Mono.just(new UploadOptions(List.of(ma, baba), List.of(), used(1))));
        String html = sheet();
        // match the patient field, not the page: D3 adds a checked "একটা রিপোর্ট" radio to the same form
        assertThat(html).doesNotContainPattern("name=\"patient\"[^>]*checked");
        assertThat(html).containsPattern("name=\"patient\" value=\"" + ma.patient().id() + "\" required>")
                .containsPattern("name=\"patient\" value=\"" + baba.patient().id() + "\" required>");
        assertThat(html).contains("কার কাগজ বেছে নিন — Medioo নিজে আন্দাজ করবে না");
    }

    @Test
    void atTheCapTheSheetSaysSoBeforeAnyUploadAndOffersNoFileInput() {
        when(kit.uploads.uploadOptions(WebUiTestKit.FAMILY)).thenReturn(Mono.just(new UploadOptions(List.of(patient("আম্মা", 64, true)), List.of(), used(3))));
        String html = sheet();
        assertThat(html).doesNotContain("type=\"file\"");
        assertThat(html).contains("এ মাসের ৩টি বিনা খরচের কাগজ পড়া হয়ে গেছে").contains("১ নভেম্বর ২০২৬");
    }

    @Test
    void aViewOnlyMemberIsToldWhyAndOfferedNoFileInput() {
        when(kit.uploads.uploadOptions(WebUiTestKit.FAMILY)).thenReturn(Mono.just(new UploadOptions(List.of(), List.of(patient("আম্মা", 64, false)), used(0))));
        String html = sheet();
        assertThat(html).doesNotContain("type=\"file\"").contains("এই ফাইলে আপনি শুধু দেখতে পারেন — নতুন কাগজ পাঠানো যাবে না।");
    }

    @Test
    void aPayingFamilySeesNoQuotaLineAndTheSheetFollowsTheLanguageCookie() {
        when(kit.uploads.uploadOptions(WebUiTestKit.FAMILY)).thenReturn(Mono.just(new UploadOptions(List.of(patient("Ma", 64, true)), List.of(),
                DocumentAllowance.unlimited(YearMonth.of(2026, 10)))));
        assertThat(sheet()).doesNotContain("বিনা খরচে");
        String en = kit.client.get().uri("/app/add").cookie("baymax_session", "tok").cookie("baymax_lang", "en").exchange()
                .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        assertThat(en).startsWith("<!doctype html><html lang=\"en\">").contains("Whose paper?").doesNotContain("কার কাগজ");
    }

    @Test
    void theCloseLinkGoesBackOnlyToALocalPath() {
        when(kit.uploads.uploadOptions(WebUiTestKit.FAMILY)).thenReturn(Mono.just(new UploadOptions(List.of(patient("আম্মা", 64, true)), List.of(), used(0))));
        String html = kit.client.get().uri("/app/add?back=//evil.example/x").cookie("baymax_session", "tok").exchange()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(html).contains("href=\"/app/home\" aria-label=\"বন্ধ\"").doesNotContain("evil.example");
    }
}
```

- [ ] **Step 2: red.** `LocalPathTest`: `cannot find symbol LocalPath` → with a stub copying today's rule
  (`p.startsWith("/") && !p.startsWith("//")`), red `/\evil.example → expected: "/app/home" but was: "/\evil.example"` —
  today's `switchLang` rule (`WebUiHandler.java:196` at `e6264a4`) on the defect. The sheet test: `expected status 200
  but was 404`.
- [ ] **Step 3: implement.**

```java
package com.elioo.baymax.web.adapter.in.ui;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** A return path a page may link to: "/" or a page under /app, checked raw and decoded; anything else is the fallback. */
final class LocalPath {

    private static final Pattern SAFE = Pattern.compile("/|/app(/[A-Za-z0-9._~%/-]*)?(\\?[A-Za-z0-9._~%=&/-]*)?");

    private LocalPath() {
    }

    static String or(String raw, String fallback) {
        if (raw == null || raw.indexOf('\\') >= 0 || !SAFE.matcher(raw).matches()) {
            return fallback;
        }
        String decoded;
        try {
            decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
        return decoded.indexOf('\\') >= 0 || decoded.startsWith("//") || !SAFE.matcher(decoded).matches() ? fallback : raw;
    }
}
```

  `PageChrome.switchLang` uses `LocalPath.or(request.queryParam("back").orElse(null), "/")`. The sheet (the draft's
  handler, merged into R1's class):

```java
package com.elioo.baymax.web.adapter.in.ui;

/**
 * The add-report sheet (spec 4.7, s07) and its submit, for every way a family adds a paper on the web. The form posts
 * as it is without script; {@code /assets/upload.js} (D4) only enhances it. Never guesses the patient: preselected
 * only when the viewer may send for exactly one, and the server refuses a submit that names none.
 *
 * <p>Logs carry counts, bytes and ids only. A file's own name may name the patient and is never logged or shown.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UploadUiHandler implements WebPages {

    static final String SHEET = PageChrome.BASE + "/add";

    private final UploadUseCase uploads;
    private final UiCopy copy;
    private final BaymaxProperties properties;

    public Mono<ServerResponse> sheet(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        Lang lang = Lang.of(request);
        return uploads.uploadOptions(session.familyId())
                .flatMap(options -> page(lang, HttpStatus.OK, sheetBody(lang, options, back(request), null)));
    }

    String sheetBody(Lang lang, UploadOptions o, String back, String alert) {
        StringBuilder b = new StringBuilder("<section class=\"sheet\" aria-labelledby=\"sheet-title\">");
        b.append("<div class=\"sheet-h\"><h1 id=\"sheet-title\">").append(copy.t(lang, "add.title")).append("</h1>")
                .append("<a class=\"icon-btn\" href=\"").append(esc(back)).append("\" aria-label=\"").append(copy.t(lang, "sheet.close"))
                .append("\">").append(Icons.svg("x", 24)).append("</a></div>");
        b.append("<p id=\"add-alert\" class=\"warn\" role=\"alert\"").append(alert == null ? " hidden>" : ">" + alert).append("</p>");
        DocumentAllowance a = o.allowance();
        if (o.canSend().isEmpty() || !a.allows(1)) {
            String why = !o.canSend().isEmpty() ? capText(lang, a)
                    : copy.t(lang, o.viewOnly().isEmpty() ? "add.nopatient" : "add.viewonly");
            return b.append("<p class=\"card\">").append(why).append("</p><a class=\"btn quiet\" href=\"").append(esc(back))
                    .append("\">").append(copy.t(lang, "app.err.return")).append("</a></section>").toString();
        }
        boolean only = o.preselected().isPresent();
        b.append("<form id=\"add\" class=\"add\" method=\"post\" enctype=\"multipart/form-data\" action=\"").append(SHEET).append("\">");
        b.append("<fieldset class=\"pick\"><legend>").append(copy.t(lang, "add.who")).append("</legend>");
        for (PatientSummary p : o.canSend()) {
            b.append("<label class=\"chip\"><input type=\"radio\" name=\"patient\" value=\"").append(p.patient().id())
                    .append("\" required").append(only ? " checked" : "").append(">")
                    .append(copy.t(lang, "add.patient", p.patient().name(), p.patient().age())).append("</label>");
        }
        b.append("<p class=\"hint\">").append(copy.t(lang, only ? "add.onlyone" : "add.choose")).append("</p></fieldset>");
        b.append("<div class=\"src\">")
                .append(row(lang, "camera", "accept=\"image/*\" capture=\"environment\"", "camera"))
                .append(row(lang, "gallery", "accept=\"image/*\" multiple", "papers"))
                .append(row(lang, "pdf", "accept=\"application/pdf,image/*\"", "page"))
                .append("</div>");
        b.append("<button type=\"submit\" class=\"btn primary nojs\">").append(copy.t(lang, "add.send")).append("</button>");
        b.append("<section class=\"tips\" aria-labelledby=\"tips-title\"><h2 id=\"tips-title\">").append(copy.t(lang, "add.tips"))
                .append("</h2><ul><li>").append(copy.t(lang, "add.tip.flat")).append("</li><li>").append(copy.t(lang, "add.tip.light"))
                .append("</li><li>").append(copy.t(lang, "add.tip.frame")).append("</li></ul></section>");
        if (a.limited()) {
            b.append("<p class=\"quota\">").append(copy.t(lang, "add.quota", a.remaining())).append("</p>");
        }
        return b.append("</form></section>").toString();
    }

    private String row(Lang lang, String src, String attrs, String icon) {
        return "<label class=\"src-row\"><span class=\"src-i\" aria-hidden=\"true\">" + Icons.svg(icon, 24) + "</span>"
                + "<span class=\"src-t\"><strong>" + copy.t(lang, "add." + src) + "</strong><span>" + copy.t(lang, "add." + src + ".sub")
                + "</span></span><input type=\"file\" name=\"file\" " + attrs + " data-src=\"" + src + "\"></label>";
    }

    String capText(Lang lang, DocumentAllowance a) {
        return copy.t(lang, "add.cap", a.max(), copy.dateText(lang, a.resetsOn()));
    }

    static String back(ServerRequest request) {
        return LocalPath.or(request.queryParam("back").orElse(null), PageChrome.BASE + "/home");
    }

    private Mono<ServerResponse> page(Lang lang, HttpStatus status, String body) {
        return Html.html(status, Html.page(lang, copy.t(lang, "app.home.title"), body, 0, false, copy.t(lang, "disclaimer"), false));
    }
}
```

  `routes()` adds `.GET(BASE + "/add", this::sheet)` to R1's legacy upload route (both behind `requireSession`). When the
  request carries `?patient=<id>` of a patient in `canSend`, that radio is `checked` even with several patients (the
  family chose on the page they came from); an id not in `canSend` is ignored, never trusted. In
  `AppTabs.papersBody` the inline form becomes
  `<a class="btn primary" href="/app/add?patient=<id>&back=` + `URLEncoder.encode("/app/timeline?patient=<id>")` +
  `">` + `Icons.svg("camera", 24)` + `copy.t(lang, "timeline.add")` + `</a>`. `BaymaxProperties` gains:

```java
    @Data
    public static class Upload {
        /** Per file; matches the JSON API's DocumentHandler.MAX_UPLOAD_BYTES (25 MB). */
        private long maxFileBytes = 25L * 1024 * 1024;
        /** One press of "পাঠান", every page together: bounds what a request may hold in memory. */
        private long maxRequestBytes = 60L * 1024 * 1024;
        /** Long edge the browser downscales to before upload; 0 = off until measured (D5, spec 4.7). */
        private int downscaleMaxEdge = 0;
        private double downscaleQuality = 0.92;
        /** A JPEG within the edge and under this many bytes is sent byte for byte, never re-compressed. */
        private long passThroughBytes = 1_500_000;
    }
```

  with `private Upload upload = new Upload();` beside `free`; `application.properties` after `:160`:

```properties
# Web upload (Phase 4, area 4). Per-file cap = the JSON API's; the request cap bounds one press of "পাঠান" (10 pages).
# Downscale is OFF (0) until the eval at candidate sizes has run (spec 4.7, roadmap Phase 4 entry; plan D5).
baymax.upload.max-file-bytes=${BAYMAX_UPLOAD_MAX_FILE_BYTES:26214400}
baymax.upload.max-request-bytes=${BAYMAX_UPLOAD_MAX_REQUEST_BYTES:62914560}
baymax.upload.downscale-max-edge=${BAYMAX_UPLOAD_DOWNSCALE_MAX_EDGE:0}
baymax.upload.downscale-quality=${BAYMAX_UPLOAD_DOWNSCALE_QUALITY:0.92}
```

- [ ] **Step 4: copy** (`ui-upload_*`; both bundles; drafts for the copy owner, the drawn s07 strings are approved):

| key | bn | en |
|---|---|---|
| `add.title` | কার কাগজ? | Whose paper? |
| `add.who` | কার কাগজ? | Whose paper? |
| `add.patient` | {0} · {1} | {0} · {1} |
| `add.onlyone` | একজনেরই ফাইল আছে, তাই তাঁকেই বেছে রাখা হয়েছে **(F8 fallback; drawn: "শুধু আম্মার ফাইল আছে, তাই আম্মাকেই…")** | Only one person's file is here, so they are chosen |
| `add.choose` | কার কাগজ বেছে নিন — Medioo নিজে আন্দাজ করবে না | Choose whose paper this is — Medioo will not guess |
| `add.camera` / `.sub` | ছবি তুলুন / ক্যামেরা খুলবে — পাতা একটা একটা করে | Take a photo / Opens the camera — one page at a time |
| `add.gallery` / `.sub` | গ্যালারি থেকে (একাধিক পাতা) / একই রিপোর্টের কয়েকটা পাতা একসাথে | From the gallery (several pages) / Several pages of one report together |
| `add.pdf` / `.sub` | PDF বা স্ক্রিনশট / ল্যাবের পাঠানো PDF-ও চলবে | PDF or screenshot / A PDF from the lab works too |
| `add.tips`, `add.tip.flat`, `.light`, `.frame` | পরিষ্কার ছবির জন্য · সমান করে রাখুন · আলোতে · পুরো পাতা ফ্রেমে | For a clear photo · Lay it flat · In good light · The whole page in the frame |
| `add.send` | পাঠান | Send |
| `add.quota` | এ মাসে বিনা খরচে আরও {0}টি কাগজ পড়াতে পারবেন। কোনো ছবি পড়তে না পারলে সেটা গোনা হবে না। (drawn, s07) | You can have {0} more papers read free this month. A photo we cannot read is not counted. |
| `add.cap` | এ মাসের {0}টি বিনা খরচের কাগজ পড়া হয়ে গেছে। {1} থেকে আবার পাঠাতে পারবেন। যেগুলো পড়তে পারিনি সেগুলো গোনা হয়নি। **(new — PO)** | This month's {0} free papers have been read. You can send again from {1}. Papers we could not read were not counted. |
| `add.viewonly` | এই ফাইলে আপনি শুধু দেখতে পারেন — নতুন কাগজ পাঠানো যাবে না। **(new — PO)** | You can view this file but not add papers to it. |
| `add.nopatient` | এখনো কারো ফাইল খোলা হয়নি। | No one's file has been opened yet. |
| `upload.err.no_file` | কোনো ছবি বা PDF বেছে নেওয়া হয়নি। | No photo or PDF was chosen. |
| `upload.err.empty_file` | ফাইলটা খালি — আবার বেছে নিন। | That file is empty — please choose it again. |
| `upload.err.unsupported` | এই ধরনের ফাইল এখনো খুলতে পারি না। কাগজের ছবি বা PDF পাঠান। (= approved `wa.unsupported_file`, `messages_bn.properties:108`) | I can't open this kind of file yet. Send a photo of the paper or a PDF. |
| `upload.err.pdf` | PDF-টা খুলতে পারিনি — ল্যাবের পাঠানো PDF বা পাতার ছবি পাঠান। | I couldn't open that PDF — send the lab's PDF or a photo of the page. |
| `upload.err.too_many_pages` | একটা কাগজে সবচেয়ে বেশি {0}টি পাতা। কম পাতা দিয়ে আবার পাঠান। | One paper can have at most {0} pages. Send fewer pages. |
| `upload.err.too_large` | ছবিগুলো অনেক বড় — একবারে কম পাতা দিন, বা ক্যামেরা দিয়ে আবার তুলুন। **(PO: spec 4.7 says "ছবি WhatsApp-এ পাঠান"; tonight's direction is web-only)** | These photos are too large — send fewer pages at once, or take them again with the camera. |

  and in `ui-upload_*` `timeline.add=কাগজ পাঠান` / `Send a paper` (moved from `ui_*`). `add.close` does not exist: the
  close control uses the shared `sheet.close`.
- [ ] **Step 5: CSS** (`70-upload.css`): the sheet subset of the D4 block (`.sheet`, `.sheet-h`, `.icon-btn`, `.pick`,
  `.chip`, `.chip input`, `.chip:focus-within`, `.hint`, `.quota`, `.src`, `.src-row`, `.src-row:focus-within`, `.src-t`,
  `.src-t span`) as written in D4 Task W3.3; `.icon-btn` and `label.chip` and `.src-row` all carry
  `min-height`/`height` `var(--target-min)` or more (TouchTargetTest derives them from this page).
- [ ] **Step 6: run** `./gradlew :baymax:test` (the new page is in every derived test through its fixture). **Replay:**
  remove `required` from the patient radios → red `withTwoPatients… Expecting actual … to contain pattern
  "name="patient" value="…" required>"`. Revert.
- [ ] **Step 7:** standard pre-merge. Commit `feat(baymax-ui): the add-report sheet — whose paper, three ways in, the allowance before upload`.

### Try it (A7)

- [ ] **prod-web:** কাগজপত্র → "কাগজ পাঠান" opens `/app/add`: "কার কাগজ?" with "আম্মা · ৬৪" chosen, three rows, three
  tips, and "এ মাসে বিনা খরচে আরও …টি কাগজ পড়াতে পারবেন…" (with P.1's cap it says a large number). The close (×) returns
  to কাগজপত্র. Do not press পাঠান until A8 has deployed.
- [ ] **local:** with `BAYMAX_FREE_MAX_DOCS_PER_MONTH=3` after three DONE papers the sheet says the month's papers are
  used, names "১ নভেম্বর ২০২৬", and shows **no** file rows. With two patients nobody is preselected.
- [ ] **prod-web:** `https://medioo.eliooo.org/lang/en?back=/%5Cevil.example` lands on the landing page, not elsewhere.

---

## PR A8 — W4.1b: `POST /app/add`, refusals in Bangla, the try documents through the real intake

Branch `feat/baymax-web-add-submit`. This PR alone owns the upload POST and where it lands (`/app/documents/{id}`, the
reading page from A9). It also owns the legacy `POST /app/patients/{id}/upload` (cached pages), the per-file and
per-request byte caps (today `readBytes` joins every part with no cap, `WebUiHandler.java:868` at `e6264a4`), and the
family-facing refusals, which today reach the page as developer English with the filename
(`BaymaxWebUiRouter.htmlErrors` default branch prints `be.getMessage()`, e.g. "could not read t6-letter.docx; send a JPEG,
PNG or PDF"; `app.err.freetier=ফ্রি সীমা শেষ। {0}` appends "free plan allows 3 document(s) per calendar month").

### Task W1b.1: the failing tests — every chosen file in order, unused inputs dropped, refusals on the sheet

**Files:** create tests `ui/AddReportSubmitTest.java`; modify `UiCopyTest`.

- [ ] **Step 1:**

```java
package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.Upload;
import com.elioo.baymax.web.application.port.in.UploadUseCase;
import com.elioo.baymax.web.application.port.in.WebAuthUseCase;
import com.elioo.baymax.web.domain.UploadOptions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Mono;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** POST /app/add: what reaches intake, in what order, and what the family reads when it is refused. */
class AddReportSubmitTest {

    private static final Instant NOW = WebUiTestKit.NOW;
    private final UUID family = WebUiTestKit.FAMILY;
    private WebUiTestKit kit = new WebUiTestKit();
    private UploadUseCase uploads = kit.uploads;
    private WebTestClient client = kit.client;

    private void rebuild(BaymaxProperties properties) {
        kit = new WebUiTestKit(mock(WebAuthUseCase.class), properties, true);
        uploads = kit.uploads;
        client = kit.client;
    }

    private static byte[] jpeg() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(120, 160, BufferedImage.TYPE_INT_RGB), "jpg", out);
        return out.toByteArray();
    }

    private WebTestClient.ResponseSpec post(MultipartBodyBuilder body, MediaType... accept) {
        return client.post().uri("/app/add").cookie("baymax_session", "tok").accept(accept.length == 0 ? new MediaType[]{MediaType.TEXT_HTML} : accept)
                .body(BodyInserters.fromMultipartData(body.build())).exchange();
    }

    @Test
    void everyChosenFileReachesIntakeInTheOrderSentAndTheFamilyLandsOnTheDocument() throws IOException {
        UUID ma = UUID.randomUUID();
        Document doc = Document.received(ma, family, 3, NOW).withId(UUID.randomUUID());
        @SuppressWarnings("unchecked") ArgumentCaptor<List<Upload>> sent = ArgumentCaptor.forClass(List.class);
        when(uploads.upload(eq(family), eq(ma), sent.capture())).thenReturn(Mono.just(doc));
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("patient", ma.toString());
        for (String name : List.of("p1.jpg", "p2.jpg", "p3.jpg")) {
            body.part("file", jpeg()).filename(name);
        }
        post(body).expectStatus().isSeeOther().expectHeader().valueEquals(HttpHeaders.LOCATION, "/app/documents/" + doc.id());
        assertThat(sent.getValue()).extracting(Upload::filename).containsExactly("p1.jpg", "p2.jpg", "p3.jpg");
    }

    /**
     * The no-JS sheet has three file inputs; the two the family did not use arrive as empty parts with filename="".
     * Those are not pages. A file the family DID choose that is empty is still passed on, so intake refuses it by name
     * (PageRendererTest.anEmptyFileIsRejected) — it is never silently dropped.
     */
    @Test
    void unusedInputsAreNotPagesButAChosenEmptyFileIsStillPassedOn() throws IOException {
        UUID ma = UUID.randomUUID();
        Document doc = Document.received(ma, family, 1, NOW).withId(UUID.randomUUID());
        @SuppressWarnings("unchecked") ArgumentCaptor<List<Upload>> sent = ArgumentCaptor.forClass(List.class);
        when(uploads.upload(eq(family), eq(ma), sent.capture())).thenReturn(Mono.just(doc));

        MultipartBodyBuilder unused = new MultipartBodyBuilder();
        unused.part("patient", ma.toString());
        unused.part("file", new ByteArrayResource(new byte[0]) { @Override public String getFilename() { return ""; } });
        unused.part("file", jpeg()).filename("p1.jpg");
        unused.part("file", new ByteArrayResource(new byte[0]) { @Override public String getFilename() { return ""; } });
        post(unused).expectStatus().isSeeOther();
        assertThat(sent.getValue()).extracting(Upload::filename).containsExactly("p1.jpg");

        MultipartBodyBuilder chosenEmpty = new MultipartBodyBuilder();
        chosenEmpty.part("patient", ma.toString());
        chosenEmpty.part("file", new byte[0]).filename("a.jpg");
        post(chosenEmpty).expectStatus().isSeeOther();
        assertThat(sent.getValue()).singleElement().satisfies(u -> {
            assertThat(u.filename()).isEqualTo("a.jpg");
            assertThat(u.bytes()).isEmpty();
        });
    }

    @Test
    void noPatientChosenIsRefusedOnTheSheetAndNothingIsSent() throws IOException {
        when(uploads.uploadOptions(family)).thenReturn(Mono.just(new UploadOptions(
                List.of(AddReportSheetPageTest.patient("আম্মা", 64, true), AddReportSheetPageTest.patient("আব্বু", 72, true)),
                List.of(), AddReportSheetPageTest.used(0))));
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", jpeg()).filename("p1.jpg");
        String html = post(body).expectStatus().isBadRequest().expectBody(String.class).returnResult().getResponseBody();
        assertThat(html).contains("<p id=\"add-alert\" class=\"warn\" role=\"alert\">কার কাগজ বেছে নিন — Medioo নিজে আন্দাজ করবে না</p>");
        verify(uploads, never()).upload(any(), any(), any());
    }

    /** The script asks for JSON and gets the same refusal as data, unescaped, in the page's language. */
    @Test
    void theScriptGetsTheRefusalAsJsonAndTheFormGetsThePage() throws IOException {
        when(uploads.uploadOptions(family)).thenReturn(Mono.just(new UploadOptions(
                List.of(AddReportSheetPageTest.patient("আম্মা", 64, true), AddReportSheetPageTest.patient("আব্বু", 72, true)),
                List.of(), AddReportSheetPageTest.used(0))));
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", jpeg()).filename("p1.jpg");
        post(body, MediaType.APPLICATION_JSON).expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody().jsonPath("$.reason").isEqualTo("choose_patient")
                .jsonPath("$.text").isEqualTo("কার কাগজ বেছে নিন — Medioo নিজে আন্দাজ করবে না");
    }

    @Test
    void aFileOverTheLimitIsRefusedWithoutReachingIntake() {
        BaymaxProperties small = new BaymaxProperties();
        small.getUpload().setMaxFileBytes(1024);
        rebuild(small);
        when(uploads.uploadOptions(family)).thenReturn(Mono.just(new UploadOptions(
                List.of(AddReportSheetPageTest.patient("আম্মা", 64, true)), List.of(), AddReportSheetPageTest.used(0))));
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("patient", UUID.randomUUID().toString());
        body.part("file", new byte[4096]).filename("big.jpg");
        String html = post(body).expectStatus().isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE).expectBody(String.class).returnResult().getResponseBody();
        assertThat(html).contains("ছবিগুলো অনেক বড়");
        verify(uploads, never()).upload(any(), any(), any());
    }
}
```

  In `UiCopyTest`, the copy half of the developer-text guard (it compiles against `main`, so it replays there):

```java
    /**
     * Regression guard: app.err.freetier was "ফ্রি সীমা শেষ। {0}", and {0} was the exception's developer message, so a
     * Bangla family read "free plan allows 3 document(s) per calendar month". No error copy may carry a slot for a
     * developer message.
     */
    @Test
    void noErrorCopyHasASlotForADeveloperMessage() {
        UiCopy copy = new UiCopy();
        for (Lang lang : Lang.values()) {
            for (String key : copy.keys(lang)) {
                if (key.startsWith("app.err.")) {
                    assertThat(copy.t(lang, key)).as(lang + " " + key).doesNotContain("{0}");
                }
            }
        }
    }
```

- [ ] **Step 2: red.** The `UiCopyTest` guard replayed on `main` (scratch worktree, only `UiCopyTest.java` copied):
  `[BN app.err.freetier] Expecting actual: "ফ্রি সীমা শেষ। {0}" not to contain: "{0}"` — the defect. The handler tests
  get `404` until `POST /app/add` exists; then `unusedInputsAreNotPages…` reads
  `Expecting actual: ["", "p1.jpg", ""] to contain exactly ["p1.jpg"]` until the filter is in. Record in the PR body what
  `main`'s `upload` does with the same three parts (it keeps the empty ones and intake refuses `empty_file`): this test is
  a guard over that defect, replayed.

### Task W1b.2: implement — the submit, the caps, the refusals, the router's error page

**Files:** modify `ui/UploadUiHandler.java` (`submit`, `legacy`, `receive`, `readBytes`, `refuse`, `REFUSALS`;
`routes()` gains `POST /app/add`; the legacy route now calls `legacy`), `ui/UiCopy.java` (+`plain`),
`ui/BaymaxWebUiRouter.java` (`htmlErrors`), `web/application/service/TimelineService.java` and `TimelineUseCase`
(`upload` removed), `extraction/adapter/in/handler/DocumentHandler.java:58` (`uploads.upload(...)`), `ui_*`
(`app.err.freetier`).

- [ ] **Step 1: the handler.**

```java
    /** reason → copy key. Completeness against every reason intake can throw is swept by TryDocumentsThroughTheWebTest. */
    static final Map<String, String> REFUSALS = Map.ofEntries(
            Map.entry("choose_patient", "add.choose"),
            Map.entry("no_file", "upload.err.no_file"),
            Map.entry("empty_file", "upload.err.empty_file"),
            Map.entry("unsupported_image", "upload.err.unsupported"),
            Map.entry("encode_failed", "upload.err.unsupported"),
            Map.entry("empty_pdf", "upload.err.pdf"),
            Map.entry("unreadable_pdf", "upload.err.pdf"),
            Map.entry("too_many_pages", "upload.err.too_many_pages"),
            Map.entry("file_too_large", "upload.err.too_large"),
            Map.entry("request_too_large", "upload.err.too_large"),
            Map.entry("shared_patient_read_only", "add.viewonly"),
            Map.entry("upload_key_reused", "app.err.generic"));

    public Mono<ServerResponse> submit(ServerRequest request) {
        return receive(request, null);
    }

    /** Today's timeline form (and any page still cached with it): the patient is in the path. */
    public Mono<ServerResponse> legacy(ServerRequest request) {
        return receive(request, request.pathVariable("id"));
    }

    private Mono<ServerResponse> receive(ServerRequest request, String pathPatient) {
        WebSession session = SessionAuthFilter.session(request);
        if (request.headers().contentLength().orElse(-1L) > properties.getUpload().getMaxRequestBytes()) {
            return refuse(request, new BaymaxException(HttpStatus.PAYLOAD_TOO_LARGE, "request_too_large", "request over the limit"));
        }
        return request.multipartData().flatMap(parts -> {
                    UUID patientId = uuidOrNull(pathPatient != null ? pathPatient : field(parts, "patient"));
                    if (patientId == null) {
                        return Mono.error(BaymaxException.badRequest("choose_patient", "no patient chosen"));
                    }
                    List<FilePart> files = parts.getOrDefault("file", List.of()).stream()
                            .filter(FilePart.class::isInstance).map(FilePart.class::cast).toList();
                    return Flux.fromIterable(files).concatMap(this::readBytes)
                            // an input the family did not use posts filename="" and nothing; a chosen empty file keeps its name
                            .filter(u -> !(u.bytes().length == 0 && (u.filename() == null || u.filename().isBlank())))
                            .collectList()
                            .flatMap(uploads -> {
                                long total = uploads.stream().mapToLong(u -> u.bytes().length).sum();
                                if (uploads.isEmpty()) {
                                    return Mono.error(BaymaxException.badRequest("no_file", "attach at least one file"));
                                }
                                if (total > properties.getUpload().getMaxRequestBytes()) {
                                    return Mono.error(new BaymaxException(HttpStatus.PAYLOAD_TOO_LARGE, "request_too_large", "request over the limit"));
                                }
                                return this.uploads.upload(session.familyId(), patientId, uploads)
                                        .doOnNext(d -> log.info("[baymax] web upload documentId={} files={} bytes={}", d.id(), uploads.size(), total))
                                        .map(d -> PageChrome.BASE + "/documents/" + d.id());
                            });
                })
                .flatMap(next -> wantsJson(request)
                        ? ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("next", next))
                        : Html.redirect(next))
                .onErrorResume(BaymaxException.class, e -> refuse(request, e));
    }

    private Mono<Upload> readBytes(FilePart part) {
        int max = Math.toIntExact(properties.getUpload().getMaxFileBytes());
        return DataBufferUtils.join(part.content(), max)
                .map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    return new Upload(part.filename(), bytes);
                })
                .defaultIfEmpty(new Upload(part.filename(), new byte[0]))
                .onErrorMap(DataBufferLimitException.class,
                        e -> new BaymaxException(HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large", "a file is over the per-file limit"));
    }

    private Mono<ServerResponse> refuse(ServerRequest request, BaymaxException e) {
        if (e.status() == HttpStatus.NOT_FOUND || e.status() == HttpStatus.UNAUTHORIZED) {
            return Mono.error(e);   // the router's own 404 page / login redirect (BaymaxWebUiRouter.htmlErrors)
        }
        Lang lang = Lang.of(request);
        UUID familyId = SessionAuthFilter.session(request).familyId();
        return this.uploads.uploadOptions(familyId).flatMap(o -> {
            boolean cap = e instanceof FreeTierExceededException;
            String key = REFUSALS.getOrDefault(e.reason(), "app.err.generic");
            if (wantsJson(request)) {
                String text = cap ? copy.plain(lang, "add.cap", o.allowance().max(), copy.dateText(lang, o.allowance().resetsOn()))
                        : copy.plain(lang, key, properties.getExtract().getMaxPages());
                return ServerResponse.status(e.status()).contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("reason", e.reason(), "text", text));
            }
            String alert = cap ? capText(lang, o.allowance()) : copy.t(lang, key, properties.getExtract().getMaxPages());
            return page(lang, e.status(), sheetBody(lang, o, back(request), alert));
        });
    }

    static boolean wantsJson(ServerRequest request) {
        return request.headers().accept().stream().anyMatch(MediaType.APPLICATION_JSON::isCompatibleWith);
    }
```

  `field(parts, name)` returns the trimmed `FormFieldPart.value()` or null; `uuidOrNull` swallows
  `IllegalArgumentException`. `UiCopy.plain(lang, key, args)` is `t` without HTML escaping, for JSON only (Javadoc: "never
  for HTML"); it writes Number arguments in Bangla digits like `t`.
- [ ] **Step 2: the router's error page carries no developer text.** In `BaymaxWebUiRouter.htmlErrors`:
  `case PAYMENT_REQUIRED -> chrome.text(request, "app.err.freetier");` and the default branch becomes
  `default -> chrome.text(request, chrome.hasCopy("app.err." + be.reason()) ? "app.err." + be.reason() : "app.err.generic");`.
  The unhandled-error log line becomes
  `log.error("[baymax] unhandled error on {} {} error={}", request.method(), request.path(), e.getClass().getSimpleName());`
  with `log.debug("[baymax] unhandled error detail", e);` — an exception message can carry medicine text
  (`MedicineTranscription.elided`). `ui_*`: `app.err.freetier=এ মাসের বিনা খরচের কাগজ পড়া হয়ে গেছে। নতুন মাসে আবার পাঠাতে পারবেন।`
  / `This month's free papers have been read. You can send again next month.`
- [ ] **Step 3: the code half of the guard** (create `ui/NoDeveloperTextOnPagesTest.java`):

```java
package com.elioo.baymax.web.adapter.in.ui;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** No exception message reaches a page or an ERROR log: the copy has no slot for one (UiCopyTest), and no call site passes one. */
class NoDeveloperTextOnPagesTest {

    private static final Path WEB = Path.of("src/main/java/com/elioo/baymax/web/adapter/in");
    private static final Pattern PASSES_A_MESSAGE = Pattern.compile("\\b(t|plain|text|esc|errorPage)\\([^;]*\\bget(Localized)?Message\\(\\)");

    @Test
    void noCallSiteHandsAnExceptionMessageToTheCopyOrThePage() throws IOException {
        List<String> found = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(WEB)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        assertThat(files).hasSizeGreaterThan(8);
        for (Path p : files) {
            List<String> lines = Files.readAllLines(p);
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = PASSES_A_MESSAGE.matcher(lines.get(i));
                if (m.find()) {
                    found.add(p.getFileName() + ":" + (i + 1) + " " + lines.get(i).trim());
                }
            }
        }
        assertThat(found).isEmpty();
    }

    /** The router's last-resort error line: the class at ERROR, never the message, and the line itself exists. */
    @Test
    void anUnhandledErrorIsLoggedByClassWithoutItsMessage() {
        Logger logger = (Logger) LoggerFactory.getLogger(BaymaxWebUiRouter.class);
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        logger.addAppender(logged);
        logger.setLevel(Level.INFO);
        try {
            WebUiTestKit kit = new WebUiTestKit();
            org.mockito.Mockito.when(kit.timeline.me(WebUiTestKit.FAMILY))
                    .thenReturn(reactor.core.publisher.Mono.error(new IllegalStateException("Tab. Demoprol 5 mg 1+0+1")));
            String html = kit.client.get().uri("/app/home").cookie("baymax_session", "tok").exchange()
                    .expectStatus().is5xxServerError().expectBody(String.class).returnResult().getResponseBody();
            assertThat(html).doesNotContain("Demoprol");
            List<String> lines = logged.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.INFO))
                    .map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(lines).anyMatch(l -> l.contains("unhandled error on GET /app/home error=IllegalStateException"));
            assertThat(lines).noneMatch(l -> l.contains("Demoprol"));
        } finally {
            logger.detachAppender(logged);
        }
    }
}
```

- [ ] **Step 4: replays.** Restore `ui.text(request, "app.err.freetier", be.getMessage())` in `htmlErrors` → red
  `BaymaxWebUiRouter.java:<n> case PAYMENT_REQUIRED -> chrome.text(request, "app.err.freetier", be.getMessage());`.
  Restore the old `log.error(… e.getMessage(), e)` → red `… noneMatch … "unhandled error on GET /app/home: Tab. Demoprol…"`.
  Revert both.
- [ ] **Step 5: remove the old path.** `TimelineUseCase.upload` and `TimelineService.upload` are deleted; the two callers
  (`UploadUiHandler.legacy` through `receive`, and `DocumentHandler`) call `UploadUseCase.upload`. The `uploads` field
  A7 appended to `TimelineService` is removed with it (and from `TimelineServiceTest`'s wire).

### Task W1b.3: the try documents through the real intake — t1–t5 accepted, t6 refused, nothing counted, nothing logged

**Files:** create `ui/TryDocumentsThroughTheWebTest.java`, `testsupport/TryDocuments.java` (the JPEG, two-page PDF and
DOCX builders, shared with D3); `WebUiTestKit` gains a constructor `WebUiTestKit(UploadUseCase uploads)` that uses the
given use case instead of the mock.

- [ ] **Step 1: the test.** The real `UploadService` → `DocumentIntakeService` → `PageRenderer` → `FreeTierService`;
  only persistence and storage are mocked. Files are built in the test, never read from `~/medioo-try-docs`.

```java
package com.elioo.baymax.web.adapter.in.ui;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.application.service.DocumentExtractionService;
import com.elioo.baymax.extraction.application.service.DocumentIntakeService;
import com.elioo.baymax.extraction.application.service.DocumentProcessingChain;
import com.elioo.baymax.extraction.application.service.PageRenderer;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.family.application.service.FreeTierService;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientAccess;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.nudge.application.port.in.NudgeUseCase;
import com.elioo.baymax.outbound.application.port.in.ExplainDocumentUseCase;
import com.elioo.baymax.storage.application.port.in.DocumentStorageUseCase;
import com.elioo.baymax.storage.domain.ObjectKind;
import com.elioo.baymax.storage.domain.StoredObject;
import com.elioo.baymax.testsupport.TryDocuments;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;
import com.elioo.baymax.web.application.port.out.AuditPort;
import com.elioo.baymax.web.application.service.UploadService;
import com.elioo.baymax.web.domain.FamilyOverview;
import com.elioo.baymax.web.domain.PatientSummary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.elioo.baymax.testsupport.TryDocuments.docx;
import static com.elioo.baymax.testsupport.TryDocuments.jpeg;
import static com.elioo.baymax.testsupport.TryDocuments.twoPagePdf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The six try documents (~/medioo-try-docs, Phase 0 P.2) through the web upload, with the real intake. */
class TryDocumentsThroughTheWebTest {

    private static final Instant NOW = Instant.parse("2026-10-19T08:00:00Z");
    private final HealthRecordPort records = mock(HealthRecordPort.class);
    private final DocumentRecordPort documents = mock(DocumentRecordPort.class);
    private final DocumentStorageUseCase storage = mock(DocumentStorageUseCase.class);
    private final DocumentExtractionService extraction = mock(DocumentExtractionService.class);
    private final TimelineUseCase timeline = mock(TimelineUseCase.class);
    private final UUID family = WebUiTestKit.FAMILY;
    private final PatientProfile ma = new PatientProfile(WebUiTestKit.MA_ID, family, "আম্মা", 64, PatientProfile.Sex.FEMALE, List.of(), NOW, NOW);
    private WebTestClient client;
    private ListAppender<ILoggingEvent> logged;

    @BeforeEach
    void wire() {
        BaymaxProperties props = new BaymaxProperties();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        FreeTierService freeTier = new FreeTierService(records, documents, props, clock);
        ExplainDocumentUseCase explainer = mock(ExplainDocumentUseCase.class);
        NudgeUseCase nudges = mock(NudgeUseCase.class);
        when(explainer.explain(any())).thenReturn(Mono.empty());
        when(nudges.onDocumentDone(any())).thenReturn(Mono.empty());
        when(extraction.process(any(), any())).thenReturn(Mono.empty());
        DocumentIntakeService intake = new DocumentIntakeService(records, documents, freeTier, storage, new PageRenderer(), props, clock,
                new DocumentProcessingChain(extraction, explainer, nudges, documents, clock));
        AuditPort audit = mock(AuditPort.class);
        when(audit.record(any())).thenReturn(Mono.empty());
        FamilyAccount account = new FamilyAccount(family, "+8801700000000", "Tanvir", FamilyAccount.Plan.FREE, NOW, NOW);
        when(timeline.me(family)).thenReturn(Mono.just(new FamilyOverview(account, List.of(new PatientSummary(ma, true, 0, null)))));
        when(records.findFamily(family)).thenReturn(Mono.just(account));
        when(records.findPatient(ma.id())).thenReturn(Mono.just(ma));
        when(records.visiblePatient(family, ma.id())).thenReturn(Mono.just(new PatientAccess(ma, true)));
        when(documents.countInMonth(eq(family), any())).thenReturn(Mono.just(0L));
        when(documents.create(any())).thenAnswer(i -> Mono.just(((Document) i.getArgument(0)).withId(UUID.randomUUID())));
        when(storage.storePage(any(), any(), any(), anyInt(), any())).thenAnswer(i -> Mono.just(new StoredObject(UUID.randomUUID(),
                family, ma.id(), i.getArgument(2), ObjectKind.PAGE, i.getArgument(3), null, "page", "image/jpeg", 10, NOW)));
        client = new WebUiTestKit(new UploadService(timeline, records, freeTier, intake, audit, clock)).client;

        Logger baymax = (Logger) LoggerFactory.getLogger("com.elioo.baymax");
        baymax.setLevel(Level.INFO);   // INFO events must reach the appender, or the absence below proves nothing
        logged = new ListAppender<>();
        logged.start();
        baymax.addAppender(logged);
    }

    @AfterEach
    void release() {
        ((Logger) LoggerFactory.getLogger("com.elioo.baymax")).detachAppender(logged);
    }

    private WebTestClient.ResponseSpec send(String filename, byte[] bytes) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("patient", ma.id().toString());
        body.part("file", bytes).filename(filename);
        return client.post().uri("/app/add").cookie("baymax_session", "tok").body(BodyInserters.fromMultipartData(body.build())).exchange();
    }

    /** t1: one document, bytes untouched; the filename (it may name the patient) reaches no INFO line — and the INFO line exists. */
    @Test
    void aPhotographedLabReportBecomesOneDocumentWithItsBytesUntouchedAndItsNameIsNeverLogged() throws IOException {
        byte[] t1 = jpeg(1240, 1754);
        send("Rokeya-Begum-CBC.jpg", t1).expectStatus().isSeeOther()
                .expectHeader().value(HttpHeaders.LOCATION, l -> assertThat(l).matches("/app/documents/[0-9a-f-]{36}"));
        ArgumentCaptor<byte[]> page = ArgumentCaptor.forClass(byte[].class);
        verify(storage).storePage(eq(family), eq(ma.id()), any(), eq(1), page.capture());
        assertThat(page.getValue()).isEqualTo(t1);   // a JPEG is passed through, never re-compressed (PageRenderer)
        List<String> info = logged.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.INFO))
                .map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(info).as("the upload's own INFO line was captured").anyMatch(m -> m.startsWith("[baymax] web upload documentId="));
        assertThat(info).noneMatch(m -> m.contains("Rokeya"));
    }

    @Test
    void aTwoPagePdfIsOneDocumentOfTwoPages() throws IOException {
        send("t5.pdf", twoPagePdf()).expectStatus().isSeeOther();
        ArgumentCaptor<Document> created = ArgumentCaptor.forClass(Document.class);
        verify(documents).create(created.capture());
        assertThat(created.getValue().pageCount()).isEqualTo(2);
        verify(storage).storePage(eq(family), eq(ma.id()), any(), eq(1), any());
        verify(storage).storePage(eq(family), eq(ma.id()), any(), eq(2), any());
    }

    /** t6: refused in Bangla, with no developer text and no filename, and no row — so nothing can count against the month. */
    @Test
    void aWordDocumentIsRefusedInBanglaAndCostsNothing() throws IOException {
        String html = send("t6-letter.docx", docx()).expectStatus().isBadRequest().expectBody(String.class).returnResult().getResponseBody();
        assertThat(html).contains("এই ধরনের ফাইল এখনো খুলতে পারি না। কাগজের ছবি বা PDF পাঠান।")
                .doesNotContain("could not read").doesNotContain("letter").doesNotContain("JPEG, PNG or PDF");
        verify(documents, never()).create(any());
        verify(storage, never()).storePage(any(), any(), any(), anyInt(), any());
    }

    @Test
    void elevenPagesAreRefusedWithTheLimitAndNothingIsCreated() throws IOException {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("patient", ma.id().toString());
        for (int i = 1; i <= 11; i++) {
            body.part("file", jpeg(100, 140)).filename("p" + i + ".jpg");
        }
        String html = client.post().uri("/app/add").cookie("baymax_session", "tok").body(BodyInserters.fromMultipartData(body.build()))
                .exchange().expectStatus().isBadRequest().expectBody(String.class).returnResult().getResponseBody();
        assertThat(html).contains("একটা কাগজে সবচেয়ে বেশি ১০টি পাতা");
        verify(documents, never()).create(any());
    }

    @Test
    void atTheCapTheRefusalNamesTheResetDateNotTheDeveloperMessage() throws IOException {
        when(documents.countInMonth(eq(family), any())).thenReturn(Mono.just(3L));
        String html = send("t1.jpg", jpeg(1240, 1754)).expectStatus().isEqualTo(HttpStatus.PAYMENT_REQUIRED)
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(html).contains("১ নভেম্বর ২০২৬").doesNotContain("free plan allows");
        verify(documents, never()).create(any());
    }

    /** Every refusal intake or the handler can throw has its own copy: derived from the sources, not a list from memory. */
    @Test
    void everyReasonAnUploadCanBeRefusedForHasItsOwnCopy() throws IOException {
        Pattern thrown = Pattern.compile("(?:badRequest|forbidden|conflict)\\(\\s*\"([a-z_]+)\"|PAYLOAD_TOO_LARGE,\\s*\"([a-z_]+)\"");
        Set<String> reasons = new TreeSet<>();
        for (String f : List.of("src/main/java/com/elioo/baymax/extraction/application/service/PageRenderer.java",
                "src/main/java/com/elioo/baymax/extraction/application/service/DocumentIntakeService.java",
                "src/main/java/com/elioo/baymax/web/adapter/in/ui/UploadUiHandler.java", "src/main/java/com/elioo/baymax/web/application/service/UploadService.java")) {
            Matcher m = thrown.matcher(Files.readString(Path.of(f)));
            while (m.find()) {
                reasons.add(m.group(1) != null ? m.group(1) : m.group(2));
            }
        }
        // shared_patient_read_only is thrown by UploadService, read above
        assertThat(reasons).as("the sweep found the refusals; an empty set would pass vacuously").hasSizeGreaterThanOrEqualTo(10);
        assertThat(reasons).allSatisfy(r -> assertThat(UploadUiHandler.REFUSALS).as(r).containsKey(r));
    }

    static byte[] jpeg(int w, int h) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "jpg", out);
        return out.toByteArray();
    }

    static byte[] twoPagePdf() throws IOException {
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            pdf.addPage(new PDPage(PDRectangle.A4));
            pdf.addPage(new PDPage(PDRectangle.A4));
            pdf.save(out);
            return out.toByteArray();
        }
    }

    /** The shape of t6-letter.docx: a ZIP (PK\3\4) with a content-types part. Nothing in it is an image. */
    static byte[] docx() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write("<w:document/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }
}
```

  Delete the three builder methods at the end of the included block from this file: they live in `TryDocuments`
  (identical bodies, `public static`).
- [ ] **Step 2: red.** Before W1b.2's mapping exists the DOCX test fails with `Expecting actual … to contain
  "এই ধরনের ফাইল এখনো খুলতে পারি না…"` and the page holds `could not read t6-letter.docx` (today's defect). Derived
  sweep: remove one `REFUSALS` entry → red naming that reason. Log guard replay: add
  `log.info("[baymax] web upload file={}", part.filename());` in `readBytes` → red
  `Expecting … noneMatch … "[baymax] web upload file=Rokeya-Begum-CBC.jpg"`. Revert each.

### Task W1b.4: one upload entry, held over every page; ship

**Files:** create test `ui/UploadEntryPointsTest.java`.

- [ ] **Step 1:**

```java
package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One way to add a paper (Global Constraints): every multipart form posts to /app/add (or the legacy route), and every
 * camera or "send a paper" link opens /app/add. Derived over every page the router serves; camera links are found by
 * the camera icon's own path data, so a new camera button anywhere is held to this.
 */
class UploadEntryPointsTest {

    private static final Pattern MULTIPART = Pattern.compile("<form[^>]*enctype=\"multipart/form-data\"[^>]*>");
    private static final Pattern ACTION = Pattern.compile("action=\"([^\"]+)\"");
    private static final Pattern LINK = Pattern.compile("<a\\b([^>]*)>(.*?)</a>", Pattern.DOTALL);
    private static final String CAMERA = Icons.svg("camera", 24).replaceAll(".*?(<path d=\"[^\"]+\"/>).*", "$1");

    @Test
    void everyUploadFormAndEveryCameraLinkGoesToTheOneEntry() {
        Map<String, String> pages = RenderedPages.all();
        List<String> wrong = new ArrayList<>();
        int forms = 0, cameras = 0;
        for (Map.Entry<String, String> page : pages.entrySet()) {
            for (Matcher f = MULTIPART.matcher(page.getValue()); f.find(); ) {
                forms++;
                Matcher a = ACTION.matcher(f.group());
                String action = a.find() ? a.group(1) : "";
                if (!action.equals("/app/add") && !action.matches("/app/patients/[0-9a-f-]{36}/upload")) {
                    wrong.add(page.getKey() + " form → " + action);
                }
            }
            for (Matcher l = LINK.matcher(page.getValue()); l.find(); ) {
                if (l.group(2).contains(CAMERA)) {
                    cameras++;
                    Matcher href = Pattern.compile("href=\"([^\"]+)\"").matcher(l.group(1));
                    if (!href.find() || !href.group(1).startsWith("/app/add")) {
                        wrong.add(page.getKey() + " camera link → " + (href.find(0) ? href.group(1) : "(none)"));
                    }
                }
            }
        }
        assertThat(forms + cameras).as("upload controls found; zero would pass vacuously").isPositive();
        assertThat(wrong).isEmpty();
    }
}
```

- [ ] **Step 2: replay (mutant):** point `AppTabs`' "কাগজ পাঠান" link at `/app/timeline?sheet=add` → red
  `GET /app/timeline bn camera link → /app/timeline?sheet=add`. Revert.
- [ ] **Step 3:** `./gradlew :baymax:test`; standard pre-merge. Commit
  `feat(baymax-ui): send every chosen page in order; refusals on the sheet in the family's language`.

### Try it (A8)

- [ ] **prod-web:** "কাগজ পাঠান" → **t1** from "গ্যালারি থেকে" → পাঠান. You land on the document's page (A9 makes it
  move by itself). **t2, t3, t4a, t4b** the same way: one paper each on কাগজপত্র.
- [ ] **prod-web:** **t5** (PDF) via "PDF বা স্ক্রিনশট" → one paper with **two** pages.
- [ ] **prod-web:** **t6-letter.docx** (picker → Files) → the sheet comes back with "এই ধরনের ফাইল এখনো খুলতে পারি না।
  কাগজের ছবি বা PDF পাঠান।", no English, no filename, and the allowance line unchanged.
- [ ] **prod-web:** t4a and t4b chosen together in the gallery → **one** paper of two pages.
- [ ] **local:** with `BAYMAX_FREE_MAX_DOCS_PER_MONTH=3` after three DONE papers, a fourth is refused on the sheet with the
  reset date and no developer text.

---

## PR A9 — 2.4: the reading page (s09) — honest steps, and it moves to the result by itself

Branch `feat/baymax-reading-progress`. After A8 a family lands on `/app/documents/{id}` while the paper is RECEIVED or
PROCESSING. That page now shows three real steps from `document.status` (পেয়েছি · পড়ছি · তৈরি; no percentage, no
step the code does not have), says when it is taking longer than five minutes, and reloads itself: a
`<noscript><meta refresh 5></noscript>` without JavaScript, a 5 s poll of a status endpoint with it. **Safe before F1:**
the status endpoint answers `{"status": …}` and nothing else, never an urgency, so an unassessed level cannot leak into
the poll (asserted on the JSON's keys).

### Task 24.1: the status read, the reading view, the endpoint

**Files:** create `web/domain/ViewerLocale.java`, `web/domain/ReadingView.java`, `web/application/port/in/ResultUseCase.java`,
`web/application/service/ResultService.java`; tests `web/application/service/ResultServiceTest.java`,
`ui/DocumentRoutesTest.java`; modify `ui/DocumentPages.java` (+`documentStatus`, route `GET /documents/{id}/status`
before `/documents/{id}`; constructor gains `ResultUseCase results` last), `WebUiTestKit` (+`results` mock),
`PageFixtures` (+`GET /app/documents/{id}/status`).

- [ ] **Step 1: failing tests.**

```java
package com.elioo.baymax.web.application.service;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.extraction.application.port.in.DocumentIntakeUseCase;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientAccess;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.web.application.port.out.AuditPort;
import com.elioo.baymax.web.domain.ViewerLocale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The document page's read side. A9: the status and the reading view. A12 adds the result. */
class ResultServiceTest {

    static final Instant NOW = Instant.parse("2026-10-20T04:00:00Z");
    static final UUID FAMILY = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4000201");
    static final UUID PATIENT = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4000202");
    static final UUID DOC = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4000203");
    static final PatientProfile MA = new PatientProfile(PATIENT, FAMILY, "Ma", 64, PatientProfile.Sex.FEMALE, List.of(), NOW, NOW);

    final HealthRecordPort records = mock(HealthRecordPort.class);
    final DocumentRecordPort documents = mock(DocumentRecordPort.class);
    final DocumentIntakeUseCase intake = mock(DocumentIntakeUseCase.class);
    final AuditPort audit = mock(AuditPort.class);
    ResultService service;

    @BeforeEach
    void wire() {
        service = new ResultService(records, documents, intake, audit, Clock.fixed(NOW, ZoneOffset.UTC));
        Document received = Document.received(PATIENT, FAMILY, 2, NOW.minusSeconds(30)).withId(DOC);
        when(documents.findVisible(FAMILY, DOC)).thenReturn(Mono.just(received));
        when(records.visiblePatient(FAMILY, PATIENT)).thenReturn(Mono.just(new PatientAccess(MA, true)));
        when(records.findFamily(FAMILY)).thenReturn(Mono.just(new FamilyAccount(FAMILY, "+8801700000000", "Tanvir", FamilyAccount.Plan.FREE, NOW, NOW)));
        when(audit.record(any())).thenReturn(Mono.empty());
        when(intake.view(DOC)).thenReturn(Mono.just(DocumentView.pending(received)));
    }

    @ParameterizedTest(name = "{0} is in Bangladesh: {1}")
    @CsvSource({"+8801700000000,true", "+8801700000799,true", "+447700900123,false", "+971500000000,false", "+15555550123,false"})
    void theViewersCountryComesFromTheirNumber(String e164, boolean bangladesh) {
        assertThat(ViewerLocale.of(e164).bangladesh()).isEqualTo(bangladesh);
    }

    /** The poll reads the status and nothing else: no audit row every 5 s, no view, no patient. */
    @Test
    void theStatusIsTheStoredStatusAloneAndRecordsNothing() {
        StepVerifier.create(service.status(FAMILY, DOC)).expectNext(Document.Status.RECEIVED).verifyComplete();
        verify(audit, never()).record(any());
        verify(intake, never()).view(any());
    }

    @Test
    void theReadingViewNamesThePatientAndKnowsWhenItArrivedAndWhereTheReaderIs() {
        StepVerifier.create(service.reading(FAMILY, DOC)).assertNext(r -> {
            assertThat(r.patient().name()).isEqualTo("Ma");
            assertThat(r.receivedAt()).isEqualTo(NOW.minusSeconds(30));
            assertThat(r.now()).isEqualTo(NOW);
            assertThat(r.viewer().bangladesh()).isTrue();
        }).verifyComplete();
    }

    @Test
    void aDocumentTheFamilyCannotSeeIsNotFoundForEitherRead() {
        when(documents.findVisible(FAMILY, DOC)).thenReturn(Mono.empty());
        StepVerifier.create(service.status(FAMILY, DOC))
                .expectErrorSatisfies(e -> assertThat(((BaymaxException) e).status()).isEqualTo(HttpStatus.NOT_FOUND)).verify();
        StepVerifier.create(service.reading(FAMILY, DOC))
                .expectErrorSatisfies(e -> assertThat(((BaymaxException) e).status()).isEqualTo(HttpStatus.NOT_FOUND)).verify();
    }
}
```

```java
    // DocumentRoutesTest (WebUiTestKit; kit.results is the ResultUseCase mock)
    /** The poll's answer: the status alone (never an urgency), never cached, and nothing without a session. */
    @Test
    void theStatusEndpointAnswersTheStatusAloneAndIsNeverCached() {
        when(kit.results.status(WebUiTestKit.FAMILY, WebUiTestKit.DOC_ID)).thenReturn(Mono.just(Document.Status.PROCESSING));
        String json = kit.client.get().uri("/app/documents/" + WebUiTestKit.DOC_ID + "/status").cookie("baymax_session", "tok").exchange()
                .expectStatus().isOk().expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(new ObjectMapper().readTree(json).fieldNames()).toIterable().containsExactly("status");
        assertThat(new ObjectMapper().readTree(json).get("status").asText()).isEqualTo("PROCESSING");
        kit.client.get().uri("/app/documents/" + WebUiTestKit.DOC_ID + "/status").exchange().expectStatus().isSeeOther();
    }
```

- [ ] **Step 2: red.** Compile errors; then `GET …/status` → `404` (no route), and the reading test fails until
  `reading` exists.
- [ ] **Step 3: implement.**

```java
package com.elioo.baymax.web.domain;

import java.time.ZoneId;
import java.util.Objects;

/**
 * F7 for Phase 2: whether the reader's own phone is Bangladeshi. That alone decides whether a tel:999 action may
 * appear. Every time on these pages is the patient's calendar (Dhaka); a reader abroad sees "(ঢাকার সময়)".
 * Zones per country are D29 (Phase 5).
 */
public record ViewerLocale(boolean bangladesh) {

    public static final ZoneId PATIENT_ZONE = ZoneId.of("Asia/Dhaka");

    public static ViewerLocale of(String e164) {
        Objects.requireNonNull(e164, "family_account.whatsapp_number is NOT NULL");
        return new ViewerLocale(e164.startsWith("+880"));
    }
}
```

```java
package com.elioo.baymax.web.domain;

import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.healthrecord.domain.PatientProfile;

import java.time.Instant;

/** What the reading page (s09) shows: the document as stored, whose it is, when it arrived, and where the reader is. */
public record ReadingView(DocumentView document, PatientProfile patient, boolean owner, ViewerLocale viewer,
                          Instant receivedAt, Instant now) {
}
```

```java
package com.elioo.baymax.web.application.port.in;

/** One document as its page reads it; 404 when this family cannot see it (never 403: ids cannot be probed). */
public interface ResultUseCase {

    /** The status alone, for the reading page's poll. Records no audit event (it is called every 5 s). */
    Mono<Document.Status> status(UUID familyId, UUID documentId);

    /** The reading view (records DOCUMENT_VIEW, as the document page always has). A12 adds result(). */
    Mono<ReadingView> reading(UUID familyId, UUID documentId);
}
```

```java
@Service
@RequiredArgsConstructor
public class ResultService implements ResultUseCase {

    private final HealthRecordPort records;
    private final DocumentRecordPort documents;
    private final DocumentIntakeUseCase intake;
    private final AuditPort audit;
    private final Clock clock;

    @Override
    public Mono<Document.Status> status(UUID familyId, UUID documentId) {
        return visibleDocument(familyId, documentId).map(Document::status);
    }

    @Override
    public Mono<ReadingView> reading(UUID familyId, UUID documentId) {
        return visibleDocument(familyId, documentId).flatMap(d -> Mono.zip(
                        records.visiblePatient(familyId, d.patientId())
                                .switchIfEmpty(Mono.error(BaymaxException.notFound("patient_not_found", "no patient with id " + d.patientId()))),
                        viewer(familyId),
                        audit.record(AuditEvent.family(AuditEvent.Kind.DOCUMENT_VIEW, familyId, d.patientId(), documentId, clock.instant()))
                                .then(intake.view(documentId)))
                .map(t -> new ReadingView(t.getT3(), t.getT1().patient(), t.getT1().owner(), t.getT2(), d.createdAt(), clock.instant())));
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
```

  `DocumentPages.documentStatus`:

```java
    /** GET /app/documents/{id}/status: what the poll needs and nothing a log or a cache could leak. */
    public Mono<ServerResponse> documentStatus(ServerRequest request) {
        WebSession session = SessionAuthFilter.session(request);
        return results.status(session.familyId(), PageChrome.uuid(request.pathVariable("id")))
                .flatMap(s -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.CACHE_CONTROL, "private, no-store").bodyValue(Map.of("status", s.name())));
    }
```

  The status endpoint returns JSON, so `RenderedPages` (HTML only) skips it; its `PageFixtures` entry uses
  `Stubs::processingStatus`.
- [ ] **Step 4: run** → green.

### Task 24.2: the reading page — three real steps, "taking longer", refresh without script, poll with it

**Files:** create `baymax/ui/progress.js`, tests `ui/ReadingProgressRenderTest.java`, `ui/UiCopyTimeTest.java`; modify
`ui/DocumentPages.java` (`documentPage` branches on status; `progressPage`, `progressBody`), `ui/Html.java`
(`Head.refreshWithoutScript`, `Head.script`; the head writes `<noscript><meta http-equiv="refresh" …></noscript>` and
`<script src="/assets/<file>?v=<version>" defer></script>`), `ui/AssetHandler.java` (+`progress.js`, served as
`text/javascript;charset=UTF-8`), `ui/UiCopy.java` (+`time`), `css/60-result.css`, `ui-result_*`.

- [ ] **Step 1: failing tests.**

```java
package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.web.domain.ReadingView;
import com.elioo.baymax.web.domain.ViewerLocale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** s09 / ProgressSteps: only the real states, a reload that needs no script, and a poll that needs no inline script. */
class ReadingProgressRenderTest {

    private static final Instant NOW = Instant.parse("2026-10-20T04:00:00Z");
    private static final PatientProfile MA = new PatientProfile(WebUiTestKit.MA_ID, WebUiTestKit.FAMILY, "Ma", 64,
            PatientProfile.Sex.FEMALE, List.of(), NOW, NOW);
    private final WebUiTestKit kit = new WebUiTestKit();

    private static ReadingView reading(Document.Status status, Duration elapsed, boolean bangladesh) {
        Document d = Document.received(MA.id(), MA.familyId(), 2, NOW.minus(elapsed)).withId(WebUiTestKit.DOC_ID);
        Document at = status == Document.Status.RECEIVED ? d : d.withStatus(status, null, NOW);
        return new ReadingView(DocumentView.pending(at), MA, true, new ViewerLocale(bangladesh), NOW.minus(elapsed), NOW);
    }

    @Test
    void withoutScriptThePageRefreshesItselfAndWithScriptItPolls() {
        String page = kit.documents.progressPage(Lang.BN, reading(Document.Status.PROCESSING, Duration.ofSeconds(30), true));
        assertThat(page).contains("<noscript><meta http-equiv=\"refresh\" content=\"5\"></noscript>")
                .containsPattern("<script src=\"/assets/progress\\.js\\?v=[0-9a-f]+\" defer></script>");
        assertThat(Pattern.compile("<script(?![^>]*\\bsrc=)").matcher(page).find()).as("an inline script").isFalse();
        assertThat(page).contains("data-status-url=\"/app/documents/" + WebUiTestKit.DOC_ID + "/status\"").contains("data-status=\"PROCESSING\"");
    }

    /** Three honest steps from Document.Status — no percentage, no crop-verification step (spec §4.9). */
    @ParameterizedTest
    @CsvSource({"RECEIVED,1", "PROCESSING,2"})
    void theStepsAreTheStoredStatusAndNothingInvented(Document.Status status, int current) {
        String body = kit.documents.progressBody(Lang.BN, reading(status, Duration.ofSeconds(30), true));
        String steps = body.substring(body.indexOf("<ol id=\"steps\""), body.indexOf("</ol>") + 5);
        assertThat(steps.split("<li").length - 1).isEqualTo(3);
        assertThat(steps).doesNotContain("%").doesNotContain("যাচাই");
        if (current == 2) {
            assertThat(steps).containsPattern("<li class=\"done\"[^>]*>[^<]*" + kit.copy.t(Lang.BN, "progress.step.received"))
                    .containsPattern("<li aria-current=\"step\"[^>]*>[^<]*" + kit.copy.t(Lang.BN, "progress.step.processing"));
        } else {
            assertThat(steps).containsPattern("<li aria-current=\"step\"[^>]*>[^<]*" + kit.copy.t(Lang.BN, "progress.step.received"));
        }
    }

    /** Both directions of the five-minute line. */
    @Test
    void itSaysItIsTakingLongerOnlyFromFiveMinutes() {
        String slow = kit.copy.t(Lang.BN, "progress.slow");
        assertThat(kit.documents.progressBody(Lang.BN, reading(Document.Status.PROCESSING, Duration.ofMinutes(5).minusSeconds(1), true))).doesNotContain(slow);
        assertThat(kit.documents.progressBody(Lang.BN, reading(Document.Status.PROCESSING, Duration.ofMinutes(5), true))).contains(slow);
    }

    /** No result text, no urgency and no reassurance before DONE. */
    @Test
    void nothingAboutTheResultAppearsWhileReading() {
        String html = kit.documents.progressBody(Lang.BN, reading(Document.Status.PROCESSING, Duration.ofSeconds(30), true));
        assertThat(html).doesNotContain("id=\"urgency\"").doesNotContain("class=\"vrow").doesNotContain("চিন্তা")
                .contains(kit.copy.t(Lang.BN, "progress.leave"));
    }

    /** A reader abroad is told the time is the patient's calendar; a reader at home is not. */
    @Test
    void aReaderAbroadSeesDhakaTimeMarked() {
        assertThat(kit.documents.progressBody(Lang.BN, reading(Document.Status.RECEIVED, Duration.ofSeconds(30), false))).contains("(ঢাকার সময়)");
        assertThat(kit.documents.progressBody(Lang.BN, reading(Document.Status.RECEIVED, Duration.ofSeconds(30), true))).doesNotContain("(ঢাকার সময়)");
    }

    /** JS only where it is needed (spec §10): the script is small, holds no copy, and a DONE page loads no script and never refreshes. */
    @Test
    void theScriptIsSmallCopyFreeAndOnlyOnTheReadingPage() throws IOException {
        byte[] js = kit.served("/assets/progress.js");
        assertThat(js.length).isPositive().isLessThanOrEqualTo(40 * 1024);
        String text = new String(js, StandardCharsets.UTF_8);
        assertThat(text).doesNotContain("eval(").doesNotContain("innerHTML");
        assertThat(text.codePoints().noneMatch(c -> c >= 0x0980 && c <= 0x09FF)).as("no Bangla copy in the script").isTrue();
        PageFixtures.Stubs.doneLab(kit);
        String done = kit.page("/app/documents/" + WebUiTestKit.DOC_ID, "bn");
        assertThat(done).doesNotContain("<script").doesNotContain("http-equiv=\"refresh\"");
    }
}
```

```java
package com.elioo.baymax.web.adapter.in.ui;

/** A time of day on the patient's calendar (Dhaka): "সকাল ১০:১২" on a Bangla page, "10:12" on an English one. */
class UiCopyTimeTest {

    private final UiCopy copy = new UiCopy();

    @ParameterizedTest(name = "{0} UTC → {1}")
    @CsvSource({"2026-10-20T04:12:00Z,সকাল ১০:১২,10:12", "2026-10-19T23:00:00Z,ভোর ৫:০০,05:00",
            "2026-10-20T06:00:00Z,দুপুর ১২:০০,12:00", "2026-10-20T09:30:00Z,বিকাল ৩:৩০,15:30",
            "2026-10-20T11:15:00Z,সন্ধ্যা ৫:১৫,17:15", "2026-10-20T13:00:00Z,রাত ৭:০০,19:00",
            "2026-10-19T18:00:00Z,রাত ১২:০০,00:00"})
    void theTimeIsDhakasWithThePartOfDayInBangla(String utc, String bn, String en) {
        assertThat(copy.time(Lang.BN, Instant.parse(utc))).isEqualTo(bn);
        assertThat(copy.time(Lang.EN, Instant.parse(utc))).isEqualTo(en);
    }
}
```

- [ ] **Step 2: red.** Compile errors; then, rendering the in-flight page with `Head.of(…).refresh(5)` (an unconditional
  refresh, the timeline's way today), `Expecting actual … to contain "<noscript><meta http-equiv=\"refresh\"…"` — an
  unconditional refresh reloads over the poll. Replay the five-minute test with `elapsed.toMinutes() > 5`: it fails at
  exactly 5:00 with `Expecting actual … to contain "স্বাভাবিকের চেয়ে বেশি সময় লাগছে…"`.
- [ ] **Step 3: implement.**
  - `documentPage`: `results.reading(familyId, id)`; RECEIVED/PROCESSING → `progressPage`; any other status → the page as
    before (A12 replaces DONE, B5 replaces NEEDS_RETAKE/FAILED).
  - `progressPage(lang, r)` = `Html.appPage(lang, Html.Head.of(copy.t(lang, "progress.title")).refreshWithoutScript(5).script("progress.js"), header, progressBody(lang, r), disclaimer, tabs(PAPERS), "", "")`.
  - `progressBody`: `<section id="progress" data-status-url="/app/documents/{id}/status" data-status="{status}">`, the
    patient chip `<span class="pchip">` + `copy.t(lang, "result.patient", name, age)` + `</span>`, `<h1>` `progress.title`,
    `progress.sent` (`{0}` = page count), `<ol id="steps" class="steps" aria-live="polite">` with three `<li>`: received
    (`class="done"` once PROCESSING, `aria-current="step"` while RECEIVED; time `copy.time(lang, r.receivedAt())` plus
    `time.dhaka` when `!viewer.bangladesh()`), processing (`aria-current="step"` when PROCESSING, else `class="next"`),
    done (`class="next"`, `progress.step.done.note`). Then `progress.slow` when `Duration.between(receivedAt, now) >= 5 min`,
    `progress.leave`, and `<a class="btn quiet" href="/app/timeline?patient=<id>">` + `progress.timeline`.
  - `UiCopy.time`: `LocalTime t = LocalTime.ofInstant(at, ZoneId.of("Asia/Dhaka"))` (A10 swaps in `PatientCalendar.ZONE`);
    Bangla: part of day `time.dawn` 04:00–05:59 (ভোর), `time.morning` 06:00–11:59 (সকাল), `time.noon` 12:00–14:59 (দুপুর),
    `time.afternoon` 15:00–16:59 (বিকাল), `time.evening` 17:00–18:59 (সন্ধ্যা), `time.night` otherwise (রাত); the hour on a
    12-hour clock (0 → 12) and two-digit minutes, through `BanglaDate.digits`. English: `HH:mm`.
  - `progress.js`:

```js
// Reading page only (spec §10). Polls the stored status every 5 s; when it changes, reloads so the server renders the
// result, retake or failure screen. No inline script, no HTML from the network: CSP script-src/connect-src 'self'.
(function () {
  var el = document.querySelector('[data-status-url]');
  if (!el) { return; }
  var url = el.getAttribute('data-status-url'), current = el.getAttribute('data-status');
  function tick() {
    fetch(url, { credentials: 'same-origin', headers: { 'Accept': 'application/json' }, cache: 'no-store' })
      .then(function (r) {
        var json = (r.headers.get('content-type') || '').indexOf('application/json') === 0;
        if (!r.ok || !json) { location.reload(); return null; }   // signed out or an error page: let the server answer
        return r.json();
      })
      .then(function (j) { if (j && j.status !== current) { location.reload(); } else { setTimeout(tick, 5000); } })
      .catch(function () { setTimeout(tick, 5000); });                // offline: keep the page, try again
  }
  setTimeout(tick, 5000);
})();
```

  - CSS (`60-result.css`): `.pchip{display:inline-flex;align-items:center;min-height:var(--target-min);padding:0 var(--sp-3);border-radius:var(--r-pill);background:var(--c-surface-2);color:var(--c-text)}`,
    `.steps{list-style:none;margin:var(--sp-4) 0;padding:0;display:flex;flex-direction:column;gap:var(--sp-2)}`,
    `.steps li{min-height:var(--target-min);display:flex;align-items:center;gap:var(--sp-2)}`,
    `.steps li.done{color:var(--c-text)}`, `.steps li.next{color:var(--c-text-muted)}`,
    `.steps li[aria-current]{font-weight:700;outline:3px solid var(--c-primary);outline-offset:2px;border-radius:var(--r-s)}`.
    No spinner is the only signal (reduced motion).
- [ ] **Step 4: copy** (`ui-result_*`; s09 strings, approved):

```properties
progress.title=কাগজ পড়ছি
progress.sent=আপনি পাঠিয়েছেন · {0}টি পাতা
progress.step.received=পেয়েছি
progress.step.processing=পড়ছি
progress.step.processing.note=কাগজের প্রতিটা লাইন পড়া হচ্ছে
progress.step.done=তৈরি
progress.step.done.note=তৈরি হয়ে গেলে ফলাফল নিজেই খুলবে
progress.leave=চলে গেলেও সমস্যা নেই — কাগজপত্রে থাকবে
progress.slow=স্বাভাবিকের চেয়ে বেশি সময় লাগছে — হয়ে গেলে কাগজপত্রে দেখাবে
progress.timeline=কাগজপত্রে যান
time.dhaka=(ঢাকার সময়)
```

  plus `result.patient={0} · {1} বছর` / `{0} · {1} years`, `time.dawn=ভোর`, `time.morning=সকাল`, `time.noon=দুপুর`,
  `time.afternoon=বিকাল`, `time.evening=সন্ধ্যা`, `time.night=রাত` (English keys present with empty-free values
  `dawn`…`night`, unused by the English format).
- [ ] **Step 5: fixtures, then run.** `PageFixtures` gains `VARIANTS` (route key → further fixtures of the same route),
  which `RenderedPages.all()` renders as well; A9 adds the variant `GET /app/documents/{id}` with `Stubs::processing` (a
  PROCESSING document), so `NoInlineCodeTest`, `TouchTargetTest` and the contrast checks read the reading page too.
  `./gradlew :baymax:test` → green.
- [ ] **Step 6:** standard pre-merge. Commit `feat(baymax-web): an honest reading page that opens the result by itself, with or without JS`.

### Try it (A9)

- [ ] **prod-web:** send t1. The page reads "কাগজ পড়ছি" with "পেয়েছি ✓ · পড়ছি · তৈরি"; when it is read, the result
  (today's document page until A12) opens by itself without a tap.
- [ ] **prod-web:** JavaScript off for the site, send t3: the page still moves on within about 5 s of DONE.
- [ ] **local:** stop the app mid-read and leave the page open; after five minutes it says
  "স্বাভাবিকের চেয়ে বেশি সময় লাগছে"; start the app: F9b recovery finishes the paper and the page moves on.

---

## PR A10 — F1a: a stored urgency that only rises, asserted by the database (V18)

Branch `feat/baymax-stored-urgency`. Spec §2 F1, principle 3, D5. Until now urgency lived only on `outbound_message`
rows: if `explain()` threw nothing was stored, and a later `detail()` could assess higher than the family had been told.
This PR stores it on `document` (level, reasons as a JSON array, assessed-at; plus `urgency_attempted_at` for A11's
sweep), rejects any lowering in a trigger every writer meets (the V14 lesson), adds the one writer
(`StoredUrgencyService`) and hooks it into `DocumentProcessingChain.explainOnly` — the half of the chain both fresh
uploads and recovered documents run — so an explanation that throws can no longer leave a DONE paper unassessed. The
page shows the stored line at once, in every review state (interim markup, ≤ 10 lines; A12 replaces it). A11 makes it
the **only** writer and adds the sweep.

**Pre-merge, in addition to the standard one:** the migration preflight prints `V17__wa_last_inbound.sql`; P.3's output
(or, failing it, the agent stops) and the V4 precedent are in the PR body; this PR's commit also edits the roadmap's
allocation table (`docs/superpowers/plans/2026-10-17-medioo-roadmap.md:72-80`) and the Phase 0 plan's merge table
(rows 8 and 9, and the Migrations bullet) to the allocation of this plan, with the reason "PO direction 2026-10-19: the
web portal first; Phase 0 PRs 7–10 rebase after the web chain".

### Task 13a.1: V18 and the no-lowering trigger, red first on Testcontainers

**Files:** create `V18__document_stored_urgency.sql`, `outbound/domain/StoredUrgency.java`,
`outbound/application/port/out/DocumentUrgencyPort.java`, `outbound/adapter/out/persistence/PostgresDocumentUrgencyAdapter.java`,
`outbound/adapter/out/persistence/UrgencyReasonsJson.java` (the `encodeReasons`/`decodeReasons` PR 5 wrote in
`PostgresOutboundMessageAdapter.java`, moved unchanged and made `public static`, so both adapters share one encoding);
test `outbound/adapter/out/persistence/DocumentUrgencyNeverLowersTest.java`.

- [ ] **Step 1: the failing test.** `runner()` from `PostgresNudgeAdapterTest.java:46-60`; the JDBC helpers `sql` /
  `scalar` / `connect` from `FamilyDeletionAcceptanceTest`.

```java
package com.elioo.baymax.outbound.adapter.out.persistence;

/**
 * F1 on real Postgres. A document's urgency only rises. The rule lives in a trigger, so no writer, present or future,
 * can store a lower level or drop a reason (the V14 lesson: assert the invariant once, where every writer meets it).
 * The port additionally merges as max(old, new), so a correct writer never trips the trigger.
 */
@Testcontainers
class DocumentUrgencyNeverLowersTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final Instant T = Instant.parse("2026-10-20T08:00:00Z");
    private static final AtomicInteger FAMILIES = new AtomicInteger();

    @Test
    void aDirectUpdateThatLowersOrClearsUrgencyIsRejectedByTheDatabase() {
        runner().run(context -> {
            UUID doc = doneDocument(context);
            context.getBean(DocumentUrgencyPort.class).raise(doc,
                    new UrgencyAssessment(Urgency.THIS_WEEK, List.of("value_outside_range:ldl|stopgap")), T).block();

            assertThatThrownBy(() -> sql("UPDATE baymax.document SET urgency = 'routine' WHERE id = '" + doc + "'"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("document urgency may not be lowered (this_week -> routine)");
            assertThatThrownBy(() -> sql("UPDATE baymax.document SET urgency = NULL, urgency_reasons = NULL, "
                    + "urgency_assessed_at = NULL WHERE id = '" + doc + "'"))
                    .hasMessageContaining("document urgency may not be lowered (this_week -> NULL)");
            assertThatThrownBy(() -> sql("UPDATE baymax.document SET urgency_reasons = '[]' WHERE id = '" + doc + "'"))
                    .hasMessageContaining("document urgency reasons may not be removed");
            assertThat(scalar("SELECT urgency FROM baymax.document WHERE id = '" + doc + "'")).isEqualTo("this_week");
        });
    }

    /** The direction that must still work: a first assessment at any level, and raising, are allowed. */
    @Test
    void anUnassessedDocumentTakesAnyLevelAndALevelCanRise() {
        runner().run(context -> {
            UUID doc = doneDocument(context);
            DocumentUrgencyPort port = context.getBean(DocumentUrgencyPort.class);
            assertThat(port.raise(doc, UrgencyAssessment.routine(), T).block().level()).isEqualTo(Urgency.ROUTINE);
            assertThat(port.raise(doc, new UrgencyAssessment(Urgency.THIS_WEEK, List.of("follow_up_past_due")), T)
                    .block().level()).isEqualTo(Urgency.THIS_WEEK);
            sql("UPDATE baymax.document SET urgency = 'now' WHERE id = '" + doc + "'");       // raising directly is fine
            assertThat(scalar("SELECT urgency FROM baymax.document WHERE id = '" + doc + "'")).isEqualTo("now");
        });
    }

    @Test
    void thePortKeepsTheHigherLevelAndTheUnionOfReasonsWhenALaterAssessmentIsLower() throws Exception {
        runner().run(context -> {
            UUID doc = doneDocument(context);
            DocumentUrgencyPort port = context.getBean(DocumentUrgencyPort.class);
            port.raise(doc, new UrgencyAssessment(Urgency.NOW, List.of("value_critical:potassium|stopgap")), T).block();

            StoredUrgency after = port.raise(doc, new UrgencyAssessment(Urgency.ROUTINE, List.of()), T.plusSeconds(60)).block();

            assertThat(after.level()).isEqualTo(Urgency.NOW);
            assertThat(after.reasons()).containsExactly("value_critical:potassium|stopgap");
            assertThat(after.assessedAt()).isEqualTo(T.plusSeconds(60));
            // the writer must not stamp updated_at: ExplanationService.composedAfterLastChange keys the DETAIL cache on it
            assertThat(scalar("SELECT updated_at = created_at FROM baymax.document WHERE id = '" + doc + "'")).isEqualTo("t");
        });
    }

    /** A marker name carries a comma ("cholesterol, total"): reasons are a JSON array, never a comma-joined string. */
    @Test
    void aReasonWithACommaRoundTripsAsOneReason() {
        runner().run(context -> {
            UUID doc = doneDocument(context);
            StoredUrgency s = context.getBean(DocumentUrgencyPort.class).raise(doc,
                    new UrgencyAssessment(Urgency.THIS_WEEK, List.of("value_outside_range:cholesterol, total|stopgap")), T).block();
            assertThat(s.reasons()).containsExactly("value_outside_range:cholesterol, total|stopgap");
        });
    }

    /**
     * The backfill. Until now urgency lived only on outbound rows. A document with a ROUTINE explanation and a NOW
     * detail comes out NOW, with reasons from both encodings of outbound_message.urgency_reasons (JSON array since
     * PR 5, comma-joined before). A DONE document with no message stays NULL, for the sweep to assess.
     */
    @Test
    void theBackfillTakesTheHighestLevelAFamilyWasToldAndLeavesAnUnmessagedDocumentNull() throws Exception {
        runner().run(context -> {
            UUID told = doneDocument(context);
            UUID untold = doneDocument(context);
            String family = scalar("SELECT family_id FROM baymax.document WHERE id = '" + told + "'");
            sql("UPDATE baymax.document SET urgency = NULL, urgency_reasons = NULL, urgency_assessed_at = NULL "
                    + "WHERE id IN ('" + told + "','" + untold + "')");   // both start unassessed (NULL -> NULL is not a lowering)
            sql("INSERT INTO baymax.outbound_message (family_id, document_id, kind, urgency, urgency_reasons, gate_status, created_at) VALUES "
                    + "('" + family + "','" + told + "','explanation','routine','diagnosis_present','released','2026-10-01T08:00:00Z'),"
                    + "('" + family + "','" + told + "','detail','now','[\"value_critical:cholesterol, total|stopgap\"]','released','2026-10-02T08:00:00Z')");

            sql(backfillStatementFromV18());

            assertThat(scalar("SELECT urgency FROM baymax.document WHERE id = '" + told + "'")).isEqualTo("now");
            assertThat(scalar("SELECT urgency_reasons::text FROM baymax.document WHERE id = '" + told + "'"))
                    .isEqualTo("[\"diagnosis_present\", \"value_critical:cholesterol, total|stopgap\"]");
            assertThat(scalar("SELECT urgency_assessed_at = '2026-10-02T08:00:00Z' FROM baymax.document WHERE id = '" + told + "'"))
                    .isEqualTo("t");
            assertThat(scalar("SELECT coalesce(urgency, '<null>') FROM baymax.document WHERE id = '" + untold + "'"))
                    .isEqualTo("<null>");
        });
    }


    /** V18's CHECKs, both directions: level, reasons and time together or not at all; a known level; a JSON array. */
    @Test
    void levelReasonsAndTimeAreSetTogetherAsAKnownLevelAndAnArray() throws Exception {
        runner().run(context -> {
            UUID doc = doneDocument(context);
            assertThatThrownBy(() -> sql("UPDATE baymax.document SET urgency = 'now' WHERE id = '" + doc + "'"))
                    .hasMessageContaining("document_urgency_complete");
            assertThatThrownBy(() -> sql("UPDATE baymax.document SET urgency = 'urgent', urgency_reasons = '[]', "
                    + "urgency_assessed_at = now() WHERE id = '" + doc + "'")).hasMessageContaining("document_urgency_check");
            assertThatThrownBy(() -> sql("UPDATE baymax.document SET urgency = 'now', urgency_reasons = '{\"a\":1}', "
                    + "urgency_assessed_at = now() WHERE id = '" + doc + "'")).hasMessageContaining("document_urgency_reasons_check");
            sql("UPDATE baymax.document SET urgency = 'now', urgency_reasons = '[\"text_emergency_phrase\"]', "
                    + "urgency_assessed_at = now() WHERE id = '" + doc + "'");                       // the good write
            assertThat(scalar("SELECT urgency FROM baymax.document WHERE id = '" + doc + "'")).isEqualTo("now");
        });
    }

    /** The V14 lesson: every other writer of the row (writeDocument's explicit UPDATE) meets the trigger and keeps the level. */
    @Test
    void theDocumentsOtherWritersKeepTheStoredLevelAndReasons() {
        runner().run(context -> {
            UUID doc = doneDocument(context);
            DocumentUrgencyPort port = context.getBean(DocumentUrgencyPort.class);
            port.raise(doc, new UrgencyAssessment(Urgency.NOW, List.of("text_emergency_phrase")), T).block();
            DocumentRecordPort documents = context.getBean(DocumentRecordPort.class);
            Document d = documents.find(doc).block();
            documents.update(d.withStatus(Document.Status.DONE, null, T.plusSeconds(5))).block();
            StoredUrgency after = port.find(doc).block();
            assertThat(after.level()).isEqualTo(Urgency.NOW);
            assertThat(after.reasons()).containsExactly("text_emergency_phrase");
        });
    }

    /** An unassessed document has nothing stored: find() is empty, never a ROUTINE it was not given. */
    @Test
    void anUnassessedDocumentHasNoStoredUrgency() {
        runner().run(context -> {
            UUID doc = doneDocument(context);
            assertThat(context.getBean(DocumentUrgencyPort.class).find(doc).blockOptional()).isEmpty();
        });
    }

    private UUID doneDocument(ApplicationContext context) {
        HealthRecordPort records = context.getBean(HealthRecordPort.class);
        FamilyAccount family = records.createFamily(new FamilyAccount(null,
                "+88017000007" + String.format("%02d", FAMILIES.incrementAndGet()), "Tanvir", FamilyAccount.Plan.FREE, T, T)).block();
        PatientProfile patient = records.createPatient(
                new PatientProfile(null, family.id(), "Ma", 68, PatientProfile.Sex.FEMALE, List.of(), T, T)).block();
        DocumentRecordPort documents = context.getBean(DocumentRecordPort.class);
        Document received = documents.create(Document.received(patient.id(), family.id(), 1, T)).block();
        return documents.update(received.withStatus(Document.Status.DONE, null, T)).block().id();
    }

    private static void followUp(UUID doc, String due) throws Exception {
        sql("INSERT INTO baymax.follow_up (patient_id, document_id, instruction, due_date, crop_key) "
                + "SELECT patient_id, id, 'আবার আসবেন', DATE '" + due + "', 'k/crop-f1.jpg' FROM baymax.document WHERE id = '" + doc + "'");
    }

    /** The UPDATE block of V18 itself, read from the classpath: the test runs the shipped SQL, not a copy of it. */
    private static String backfillStatementFromV18() throws Exception {
        String v18 = new String(DocumentUrgencyNeverLowersTest.class
                .getResourceAsStream("/db/baymax/migration/V18__document_stored_urgency.sql").readAllBytes(), StandardCharsets.UTF_8);
        int start = v18.indexOf("-- backfill:begin");
        int end = v18.indexOf("-- backfill:end");
        assertThat(start).as("V18 must mark its backfill block").isNotNegative();
        return v18.substring(start, end).replace("${flyway:defaultSchema}", "baymax");
    }
    // runner(), sql(), scalar(), connect(): as in PostgresNudgeAdapterTest / FamilyDeletionAcceptanceTest
}
```

- [ ] **Step 2: red on compile, then on the defect.** `./gradlew :baymax:test --tests '*DocumentUrgencyNeverLowersTest'`
  fails to compile (`DocumentUrgencyPort` missing). Add the port, `StoredUrgency` and an adapter whose `raise` is a
  naive `UPDATE document SET urgency = :level, urgency_reasons = :reasons, urgency_assessed_at = :at`, with V18 **without**
  the `CREATE TRIGGER` statement. The reds must be:
  - `aDirectUpdateThatLowers…`: `Expecting code to raise a throwable` at the `'routine'` UPDATE (the lowering went through);
  - `thePortKeepsTheHigherLevel…`: `expected: NOW but was: ROUTINE`;
  - the backfill test, with the comma-split read (`string_to_array` on every row): `expected: "[\"diagnosis_present\",
    \"value_critical:cholesterol, total|stopgap\"]" but was: "[\"diagnosis_present\", \" total|stopgap\"\", …]"`.
  A `column "urgency" does not exist` is not a replay. Paste the three messages.
- [ ] **Step 3: V18.**

```sql
-- F1 (Phase 1b, A10): a document's urgency, stored on the document, written by one writer
-- (StoredUrgencyService), and only ever raised. Every surface (timeline, result, chat, WhatsApp) reads these columns,
-- never a message row. Until now urgency lived only on outbound_message: if explain() threw, nothing was stored, and
-- a later detail() could assess higher than the family had been told.
--
-- The invariant is asserted HERE, where every writer meets it, not in the writer (CLAUDE.md, the V14 lesson):
-- a BEFORE UPDATE trigger with no column list rejects a lower level, a NULL after a value, and a dropped reason.
ALTER TABLE ${flyway:defaultSchema}.document
    ADD COLUMN urgency             varchar(16) NULL CHECK (urgency IN ('routine', 'this_week', 'now')),
    ADD COLUMN urgency_reasons     jsonb       NULL CHECK (urgency_reasons IS NULL OR jsonb_typeof(urgency_reasons) = 'array'),
    ADD COLUMN urgency_assessed_at timestamptz NULL;

ALTER TABLE ${flyway:defaultSchema}.document ADD CONSTRAINT document_urgency_complete
    CHECK ((urgency IS NULL) = (urgency_reasons IS NULL) AND (urgency IS NULL) = (urgency_assessed_at IS NULL));

CREATE FUNCTION ${flyway:defaultSchema}.urgency_rank(u varchar) RETURNS int LANGUAGE sql IMMUTABLE
    AS $$ SELECT array_position(ARRAY['routine', 'this_week', 'now']::varchar[], u) $$;

CREATE FUNCTION ${flyway:defaultSchema}.document_urgency_never_lowers() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.urgency IS NULL THEN
        RETURN NEW;                                   -- a first assessment may be any level
    END IF;
    IF NEW.urgency IS NULL
            OR ${flyway:defaultSchema}.urgency_rank(NEW.urgency) < ${flyway:defaultSchema}.urgency_rank(OLD.urgency) THEN
        RAISE EXCEPTION 'document urgency may not be lowered (% -> %)', OLD.urgency, coalesce(NEW.urgency, 'NULL')
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.urgency_reasons IS NULL OR NOT (NEW.urgency_reasons @> OLD.urgency_reasons) THEN
        RAISE EXCEPTION 'document urgency reasons may not be removed' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER document_urgency_never_lowers BEFORE UPDATE ON ${flyway:defaultSchema}.document
    FOR EACH ROW EXECUTE FUNCTION ${flyway:defaultSchema}.document_urgency_never_lowers();

-- A11's sweep stamps an attempt before assessing, so a document whose assessment throws goes to the back of the queue
-- instead of being picked first every hour (it is not urgency: the trigger ignores it).
ALTER TABLE ${flyway:defaultSchema}.document ADD COLUMN urgency_attempted_at timestamptz NULL;

-- backfill:begin
-- The highest level any explanation or detail ever carried: never store less than a family was told.
-- outbound_message.urgency_reasons is text holding a JSON array (since PR 5) or the older comma-joined form.
-- The legacy form cannot be split correctly when a marker name has a comma; those rows keep a fragment.
UPDATE ${flyway:defaultSchema}.document d
SET urgency = m.top, urgency_reasons = m.reasons, urgency_assessed_at = m.at
FROM (SELECT o.document_id,
             (ARRAY['routine', 'this_week', 'now'])[max(${flyway:defaultSchema}.urgency_rank(o.urgency))] AS top,
             coalesce(jsonb_agg(DISTINCT trim(r.reason) ORDER BY trim(r.reason))
                      FILTER (WHERE coalesce(trim(r.reason), '') <> ''), '[]'::jsonb) AS reasons,
             max(o.created_at) AS at
      FROM ${flyway:defaultSchema}.outbound_message o
      LEFT JOIN LATERAL jsonb_array_elements_text(CASE
              WHEN o.urgency_reasons LIKE '[%' THEN o.urgency_reasons::jsonb
              WHEN coalesce(o.urgency_reasons, '') = '' THEN '[]'::jsonb
              ELSE to_jsonb(string_to_array(o.urgency_reasons, ',')) END) AS r(reason) ON true
      WHERE o.document_id IS NOT NULL AND o.kind IN ('explanation', 'detail')
      GROUP BY o.document_id) m
WHERE d.id = m.document_id;
-- backfill:end

COMMENT ON COLUMN ${flyway:defaultSchema}.document.urgency IS
    'F1: code-computed urgency, max over every assessment; a trigger rejects lowering. NULL = never assessed.';
COMMENT ON COLUMN ${flyway:defaultSchema}.document.urgency_reasons IS
    'F1: JSON array of machine codes (value_critical:<marker>|stopgap, ...); only grows. Marker names, never document text.';
```

  `StoredUrgency`:

```java
package com.elioo.baymax.outbound.domain;

/** F1: what the document row holds. Only {@code DocumentUrgencyPort.raise} writes it, and it only rises. */
public record StoredUrgency(UUID documentId, Urgency level, List<String> reasons, Instant assessedAt) {
    public StoredUrgency {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    /** The stored level as the composition pipeline consumes it: compose at what is stored, never at a fresh lower read. */
    public UrgencyAssessment asAssessment() {
        return new UrgencyAssessment(level, reasons);
    }
}
```

  `DocumentUrgencyPort` (A11 adds `needingAssessment` and `markAttempt`):

```java
package com.elioo.baymax.outbound.application.port.out;

public interface DocumentUrgencyPort {

    /**
     * One statement: level = max(stored, assessed), reasons = stored ∪ assessed, assessed_at = {@code at}. Never stamps
     * updated_at. Returns the row as stored afterwards; empty when there is no such document. Only StoredUrgencyService
     * calls this (A11, ArchUnit).
     */
    Mono<StoredUrgency> raise(UUID documentId, UrgencyAssessment assessed, Instant at);

    /** What is stored; empty when the document was never assessed. */
    Mono<StoredUrgency> find(UUID documentId);
}
```

  `PostgresDocumentUrgencyAdapter.raise` (`@>` instead of `?`, which Spring's named-parameter parser would read as a bind
  marker):

```java
private static final String RAISE = """
        UPDATE %1$s.document SET
            urgency = CASE WHEN urgency IS NULL
                                OR %1$s.urgency_rank(CAST(:level AS varchar)) > %1$s.urgency_rank(urgency)
                           THEN CAST(:level AS varchar) ELSE urgency END,
            urgency_reasons = (SELECT coalesce(jsonb_agg(DISTINCT r ORDER BY r), '[]'::jsonb)
                               FROM jsonb_array_elements_text(coalesce(urgency_reasons, '[]'::jsonb)
                                                              || CAST(:reasons AS jsonb)) AS t(r)),
            urgency_assessed_at = :at
        WHERE id = :id
        RETURNING id, urgency, urgency_reasons::text AS reasons, urgency_assessed_at
        """.formatted(S);

@Override
public Mono<StoredUrgency> raise(UUID documentId, UrgencyAssessment assessed, Instant at) {
    return db.sql(RAISE)
            .bind("id", documentId)
            .bind("level", assessed.level().dbValue())
            .bind("reasons", UrgencyReasonsJson.encode(assessed.reasons()))   // the JSON encoder PR 5 shipped
            .bind("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
            .map((row, meta) -> stored(row))
            .one();
}
```

  `find` is `SELECT id, urgency, urgency_reasons::text AS reasons, urgency_assessed_at FROM document WHERE id = :id AND urgency IS NOT NULL`.
- [ ] **Step 4: run green, then the two replays.** `./gradlew :baymax:test --tests '*DocumentUrgencyNeverLowersTest'` →
  green. Replay A: delete the `CREATE TRIGGER` line locally → `aDirectUpdateThatLowers…` red
  `Expecting code to raise a throwable`. Replay B: restore the trigger and make `RAISE` a naive `SET urgency = :level` →
  `thePortKeepsTheHigherLevel…` red `R2dbcDataIntegrityViolationException … document urgency may not be lowered (now -> routine)`.
  Both are required; paste both.

### Task 13a.2: one patient calendar for every "today"

**Files:** create `common/time/PatientCalendar.java`, test `outbound/application/service/PatientCalendarRulesAgreeTest.java`,
`architecture/NoUrgencyColumnInEntitiesTest.java`; modify `UrgencyService.java:96`, `ui/UiCopy.time` and `ui/FamilyView`
(their Dhaka constant → `PatientCalendar.ZONE`).

- [ ] **Step 1: failing tests.** The follow-up rule judged today by UTC; the attention stack (C4), the timeline (C2) and
  the patient judge by Dhaka. Between 00:00 and 06:00 Dhaka the two disagree: a follow-up due "yesterday" in Dhaka is
  not yet past due by UTC. Both directions at Dhaka midnight:

```java
package com.elioo.baymax.outbound.application.service;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.outbound.domain.Urgency;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F7: the patient's calendar is Dhaka's. Every rule that asks "is this date before today" asks PatientCalendar. A11 adds
 * the sweep's predicate and C4 the attention stack's window to this class, at the same instants.
 */
class PatientCalendarRulesAgreeTest {

    @ParameterizedTest(name = "at {0} a follow-up due 2026-10-19 is past due: {1}")
    @CsvSource({"2026-10-19T17:59:00Z,false",     // 23:59 on the 19th in Dhaka: due today, not past
                "2026-10-19T18:00:00Z,true",      // 00:00 on the 20th in Dhaka
                "2026-10-19T23:30:00Z,true"})     // 05:30 on the 20th in Dhaka — still the 19th in UTC
    void theFollowUpRuleJudgesPastDueOnTheDhakaDay(String at, boolean pastDue) {
        UrgencyService rule = new UrgencyService(new BaymaxProperties(), Clock.fixed(Instant.parse(at), ZoneOffset.UTC));
        var facts = UrgencyServiceTest.facts(List.of(), List.of(Map.of("instruction", "আবার আসবেন", "due_date", "2026-10-19")), Map.of(), "{}");
        assertThat(rule.assess(facts).level()).isEqualTo(pastDue ? Urgency.THIS_WEEK : Urgency.ROUTINE);
    }
}
```

  `NoUrgencyColumnInEntitiesTest` — no Spring Data entity can write an urgency or verdict column, so a `save()` can never
  overwrite the stored level (derived over every `@Table` class):

```java
package com.elioo.baymax.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** F1/F2: only PostgresDocumentUrgencyAdapter writes urgency and verdict columns; an entity may at most read one. */
class NoUrgencyColumnInEntitiesTest {

    @Test
    void noEntityHasAWritableUrgencyOrVerdictProperty() {
        JavaClasses classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.elioo.baymax");
        List<String> entities = new ArrayList<>();
        List<String> writable = new ArrayList<>();
        classes.stream().filter(c -> c.isAnnotatedWith(Table.class)).forEach(c -> {
            entities.add(c.getSimpleName());
            for (JavaField f : c.getFields()) {
                String column = f.tryGetAnnotationOfType(Column.class).map(Column::value).orElse(f.getName());
                if (column.matches("(?i)(urgency|verdict)[a-z_]*") && !f.isAnnotatedWith(ReadOnlyProperty.class)) {
                    writable.add(c.getSimpleName() + "." + f.getName());
                }
            }
        });
        assertThat(entities).as("entity classes found").contains("DocumentEntity", "ObservationEntity");
        assertThat(writable).isEmpty();
    }
}
```

- [ ] **Step 2: red.** `PatientCalendarRulesAgreeTest[3]` fails: `expected: THIS_WEEK but was: ROUTINE` — at 05:30 Dhaka
  on the 20th, UTC still says the 19th. (`NoUrgencyColumnInEntitiesTest` is green today; its replay is adding
  `@Column("urgency") private String urgency;` to `DocumentEntity` → red `[DocumentEntity.urgency]`.)
- [ ] **Step 3: implement.**

```java
package com.elioo.baymax.common.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** F7: the patient's calendar. Every "today" and every day a page shows is Dhaka's (D29 adds per-recipient zones later). */
public final class PatientCalendar {

    public static final ZoneId ZONE = ZoneId.of("Asia/Dhaka");

    private PatientCalendar() {
    }

    public static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(ZONE));
    }

    public static LocalDate dayOf(Instant at) {
        return LocalDate.ofInstant(at, ZONE);
    }
}
```

  `UrgencyService.java:96`: `LocalDate today = PatientCalendar.today(clock);`. **PO note in the PR body:**
  "follow_up_past_due is now judged on the patient's Dhaka day: it can raise up to six hours earlier than before, never
  later." Re-run `UrgencyServiceTest`, `WithinNormalRangeClaimTest`, `ProposedThresholdsAreInertTest`,
  `UrgencyHooksAreInertTest`: green.

### Task 13a.3: the writer, and the chain hook both paths meet

**Files:** create `outbound/application/port/in/DocumentUrgencyUseCase.java`,
`outbound/application/service/StoredUrgencyService.java`; tests `StoredUrgencyServiceTest.java`,
`InMemoryDocumentUrgency.java` (a test fake with the same max/union semantics; the database behaviour is 13a.1's);
modify `DocumentProcessingChain.java` (field `DocumentUrgencyUseCase urgency` appended last), `DocumentProcessingChainTest`,
`DocumentIntakeServiceTest`, `TryDocumentsThroughTheWebTest` (the two other `new DocumentProcessingChain(` sites).

- [ ] **Step 1: failing tests.** In `DocumentProcessingChainTest`:

```java
/** F1: a DONE document is assessed and stored before explain runs, so an explanation that throws leaves it assessed. */
@Test
void urgencyIsStoredBeforeExplainEvenWhenTheExplanationThrows() {
    when(extraction.process(any(), any())).thenReturn(Mono.just(DONE_DOC));
    when(urgency.assessAndStore(DONE_DOC.id())).thenReturn(Mono.just(
            new StoredUrgency(DONE_DOC.id(), Urgency.THIS_WEEK, List.of("diagnosis_present"), NOW)));
    when(explainer.explain(DONE_DOC.id())).thenReturn(Mono.error(new IllegalStateException("medicine transcription is not verbatim")));
    when(nudges.onDocumentDone(any())).thenReturn(Mono.empty());

    StepVerifier.create(chain.run(DONE_DOC, List.of(new byte[]{1}))).expectNext(DONE_DOC).verifyComplete();

    InOrder order = inOrder(urgency, explainer);
    order.verify(urgency).assessAndStore(DONE_DOC.id());
    order.verify(explainer).explain(DONE_DOC.id());
}

/** The other path into the chain: a recovered document that finished but was never explained (F9b) is assessed too. */
@Test
void aRecoveredFinishedDocumentIsAssessedOnTheSamePath() {
    when(urgency.assessAndStore(DONE_DOC.id())).thenReturn(Mono.empty());
    when(explainer.explain(DONE_DOC.id())).thenReturn(Mono.empty());
    when(nudges.onDocumentDone(any())).thenReturn(Mono.empty());
    StepVerifier.create(chain.explainOnly(DONE_DOC)).expectNext(DONE_DOC).verifyComplete();
    verify(urgency).assessAndStore(DONE_DOC.id());
}

/** A failing store is logged by class and never undoes the document; explain still runs. */
@Test
void aFailingStoreNeverUndoesTheDocumentAndExplainStillRuns() {
    when(urgency.assessAndStore(DONE_DOC.id())).thenReturn(Mono.error(new IllegalStateException("db down")));
    when(explainer.explain(DONE_DOC.id())).thenReturn(Mono.empty());
    when(nudges.onDocumentDone(any())).thenReturn(Mono.empty());
    StepVerifier.create(chain.explainOnly(DONE_DOC)).expectNext(DONE_DOC).verifyComplete();
    verify(explainer).explain(DONE_DOC.id());
}

/** A NEEDS_RETAKE persisted nothing to assess: no urgency is stored for it, and none is invented. */
@Test
void aRetakeIsNotAssessed() {
    when(extraction.process(any(), any())).thenReturn(Mono.just(RETAKE_DOC));
    when(explainer.explain(any())).thenReturn(Mono.empty());
    when(nudges.onDocumentDone(any())).thenReturn(Mono.empty());
    StepVerifier.create(chain.run(RETAKE_DOC, List.of(new byte[]{1}))).expectNext(RETAKE_DOC).verifyComplete();
    verify(urgency, never()).assessAndStore(any());
}
```

  `StoredUrgencyServiceTest` (with `InMemoryDocumentUrgency` and mocked `DocumentRecordPort`/`HealthRecordPort`):
  (a) `assessAndStore` of a DONE document with Potassium 11.0 against 3.5–5.1 stores NOW with
  `value_critical:potassium|stopgap`; (b) of a PROCESSING document stores nothing and completes empty; (c)
  `currentOrAssess` of an unassessed DONE document assesses once, and a **second** call assesses nothing
  (`verify(documents, times(1)).observationsOf(id)`) — a page read twice writes once; (d) the INFO line carries
  `documentId=`, `assessed=`, `stored=`, `reasons=<count>` and `doesNotContain("potassium")` (reasons hold marker names;
  the log holds their count), asserted after `anyMatch(l -> l.startsWith("[baymax] urgency stored documentId="))`.
- [ ] **Step 2: red.** The chain tests: `Wanted but not invoked: urgency.assessAndStore(…)`. (c):
  `Wanted 1 time … But was 2 times` with a `currentOrAssess` that always re-assesses. Each names the defect.
- [ ] **Step 3: implement.**

```java
package com.elioo.baymax.outbound.application.port.in;

public interface DocumentUrgencyUseCase {
    /** Assess a DONE document from its persisted facts and store max(old, new). Empty for any other status. */
    Mono<StoredUrgency> assessAndStore(UUID documentId);

    /** What is stored; empty when never assessed. Every surface that only lists reads this. */
    Mono<StoredUrgency> current(UUID documentId);

    /**
     * The document page's read: what is stored, or — for a DONE document still unassessed (a pre-V18 row the backfill
     * left NULL) — one assessment through the writer now. A second read finds it stored and assesses nothing.
     */
    Mono<StoredUrgency> currentOrAssess(UUID documentId);
}
```

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class StoredUrgencyService implements DocumentUrgencyUseCase {

    private final UrgencyService urgency;
    private final DocumentUrgencyPort stored;
    private final DocumentRecordPort documents;
    private final HealthRecordPort records;
    private final BaymaxProperties properties;
    private final Clock clock;

    /** The writer. A11 makes this the only caller of UrgencyService.assess and of DocumentUrgencyPort.raise. */
    Mono<StoredUrgency> assessAndStore(DocumentFacts facts) {
        UrgencyAssessment assessed = urgency.assess(facts);
        return stored.raise(facts.document().id(), assessed, clock.instant())
                .switchIfEmpty(Mono.error(new IllegalStateException("no document row for urgency id=" + facts.document().id())))
                .doOnNext(s -> log.info("[baymax] urgency stored documentId={} assessed={} stored={} reasons={}",
                        s.documentId(), assessed.level().dbValue(), s.level().dbValue(), s.reasons().size()));
    }

    @Override
    public Mono<StoredUrgency> assessAndStore(UUID documentId) {
        return documents.find(documentId).filter(d -> d.status() == Document.Status.DONE)
                .flatMap(this::facts).flatMap(this::assessAndStore);
    }

    @Override
    public Mono<StoredUrgency> current(UUID documentId) {
        return stored.find(documentId);
    }

    @Override
    public Mono<StoredUrgency> currentOrAssess(UUID documentId) {
        return stored.find(documentId).switchIfEmpty(Mono.defer(() -> assessAndStore(documentId)));
    }

    /** ExplanationService.java:148-156, copied verbatim; A11 points ExplanationService here and deletes its copy. */
    Mono<DocumentFacts> facts(Document document) {
        String link = properties.getOutbound().getPublicBaseUrl() + "/app/documents/" + document.id();
        return Mono.zip(
                documents.observationsOf(document.id()).collectList(),
                documents.followUpsOf(document.id()).collectList(),
                documents.clinicalContextOf(document.id()).defaultIfEmpty(Map.of()),
                records.findPatient(document.patientId()).map(p -> p.name()).defaultIfEmpty("")
        ).map(t -> new DocumentFacts(document, t.getT4(), t.getT1(), t.getT2(), t.getT3(), link));
    }
}
```

  `DocumentProcessingChain`:

```diff
     public Mono<Document> explainOnly(Document done) {
-        return explain(done).flatMap(this::nudge);
+        return storeUrgency(done).flatMap(this::explain).flatMap(this::nudge);
     }
+
+    // F1: assessed and stored before explain, on the path both fresh uploads (run) and recovery (explainOnly) take, so a
+    // throwing explanation cannot leave a DONE document unassessed. A failure is logged by class name and never undoes
+    // the extraction (the same contract as explain and nudge).
+    private Mono<Document> storeUrgency(Document done) {
+        if (done.status() != Document.Status.DONE) {
+            return Mono.just(done);
+        }
+        return urgency.assessAndStore(done.id())
+                .onErrorResume(e -> {
+                    log.error("[baymax] urgency store failed documentId={} error={}", done.id(), e.getClass().getSimpleName());
+                    return Mono.empty();
+                })
+                .thenReturn(done);
+    }
```

- [ ] **Step 4: run** `./gradlew :baymax:test` → green.

### Task 13a.4: the stored line on the document page, from the message bundle (interim)

**Files:** create test `ui/UrgencyCopyTest.java`; modify `ui/UiCopy.java` (+`urgency`, loads `messages_<lang>.properties`
read-only), `extraction/domain/DocumentView.java` (last component `String urgency`, the 14-argument constructor delegating
with `null`, `withUrgency(String)`), `TimelineService.document` (zips `urgency.currentOrAssess(documentId)`; field
`DocumentUrgencyUseCase urgency` appended last; `TimelineServiceTest` wire), `ui/DocumentPages.render` (≤ 10 lines),
`DocumentPagesRenderTest`.

- [ ] **Step 1: failing tests.**

```java
package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.outbound.application.service.Copy;
import com.elioo.baymax.outbound.domain.Urgency;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Principle 3: urgency appears only as the fixed strings, and the web says exactly what WhatsApp says. */
class UrgencyCopyTest {

    private final UiCopy ui = new UiCopy();
    private final Copy messages = new Copy();

    @Test
    void theUrgencyLineIsTheMessagesBundleLineVerbatimInBothLanguages() {
        for (Urgency level : List.of(Urgency.THIS_WEEK, Urgency.NOW)) {
            assertThat(ui.urgency(Lang.BN, level, false)).isEqualTo(Html.esc(messages.bn("urgency." + level.dbValue(), Map.of())));
            assertThat(ui.urgency(Lang.EN, level, false)).isEqualTo(Html.esc(messages.en("urgency." + level.dbValue(), Map.of())));
        }
    }

    /** D27: ROUTINE has no line; asking for one is a programming error, so no renderer can draw a ROUTINE chip or banner. */
    @Test
    void routineAndNullHaveNoLine() {
        assertThatThrownBy(() -> ui.urgency(Lang.BN, Urgency.ROUTINE, false)).hasMessageContaining("ROUTINE has no urgency line");
        assertThatThrownBy(() -> ui.urgency(Lang.BN, null, false)).hasMessageContaining("ROUTINE has no urgency line");
    }

    /** One source: no ui bundle part defines an urgency.* key a page could read instead. */
    @Test
    void noUiBundlePartDefinesAnUrgencyKey() {
        assertThat(ui.keys(Lang.BN)).hasSizeGreaterThan(150).noneMatch(k -> k.startsWith("urgency."));
    }
}
```

  In `DocumentPagesRenderTest` (both levels, both directions; matched on the bundle lines, read from the file, not on
  markup):

```java
    /** Every urgency line in messages_bn (all forms, derived from the file), for the absence checks. */
    private static List<String> urgencyLines() {
        List<String> lines = BundleFiles.messages(Lang.BN).entrySet().stream().filter(e -> e.getKey().startsWith("urgency."))
                .map(e -> Html.esc(e.getValue())).toList();
        assertThat(lines).as("urgency lines in messages_bn").hasSizeGreaterThanOrEqualTo(2);
        return lines;
    }

    /** F1 on the page: a NOW or THIS_WEEK document shows its fixed line even with no released explanation. */
    @ParameterizedTest
    @EnumSource(value = Urgency.class, names = {"NOW", "THIS_WEEK"})
    void aStoredLevelIsShownWithoutAnyExplanation(Urgency level) {
        String html = kit.documents.render(Lang.BN, doneView().withUrgency(level.dbValue()), id, Optional.empty());
        assertThat(html).contains(kit.copy.urgency(Lang.BN, level, false));
    }

    /** D27, the other direction: ROUTINE and unassessed carry none of the urgency lines, in any form, anywhere. */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "routine")
    void routineAndUnassessedShowNoUrgencyLine(String stored) {
        String html = kit.documents.render(Lang.BN, doneView().withUrgency(stored), id, Optional.empty());
        assertThat(urgencyLines()).allSatisfy(line -> assertThat(html).doesNotContain(line));
    }
```

- [ ] **Step 2: red.** `cannot find symbol urgency` / `withUrgency` (compile) → with both added and no rendering:
  `aStoredLevelIsShownWithoutAnyExplanation[NOW] Expecting actual … to contain "এখনই ডাক্তারের কাছে যান। দেরি করবেন না।"`.
- [ ] **Step 3: implement.**

```java
    private final Map<Lang, Properties> messages = new EnumMap<>(Lang.class);
    // in the constructor, per language: messages.put(lang, load("/baymax/messages_" + lang.code + ".properties"));

    /**
     * The fixed urgency line for a stored level, from messages_{bn,en}.properties: the same bytes WhatsApp sends, so the
     * two channels never word a level differently (principle 3). HTML-escaped. {@code shortForm} reads the chip form
     * (urgency.*.short, C2). ROUTINE has no line, no chip and no banner (D27): asking for one throws.
     */
    public String urgency(Lang lang, Urgency level, boolean shortForm) {
        if (level == null || level == Urgency.ROUTINE) {
            throw new IllegalArgumentException("ROUTINE has no urgency line (D27)");
        }
        String key = "urgency." + level.dbValue() + (shortForm ? ".short" : "");
        String raw = messages.get(lang).getProperty(key);
        if (raw == null) {
            throw new IllegalArgumentException("no urgency copy for key " + key);
        }
        return Html.esc(raw);
    }
```

  In `DocumentPages.render`, right after the meta line and before the explanation (the whole interim change):

```java
        if (view.urgency() != null && !"routine".equals(view.urgency())) {
            Urgency level = Urgency.fromDbValue(view.urgency());
            b.append("<section class=\"banner ").append(level == Urgency.NOW ? "now" : "soon")
                    .append("\" id=\"urgency\" role=\"status\"><p class=\"line\">")
                    .append(copy.urgency(lang, level, false)).append("</p></section>");
        }
```

  `TimelineService.document`:

```java
        return visibleDocument(familyId, documentId)
                .flatMap(d -> audit.record(AuditEvent.family(AuditEvent.Kind.DOCUMENT_VIEW, familyId, d.patientId(), documentId, clock.instant()))
                        .then(intake.view(documentId)))
                .zipWith(urgency.currentOrAssess(documentId).map(Optional::of).defaultIfEmpty(Optional.empty()),
                        (view, stored) -> view.withUrgency(stored.map(s -> s.level().dbValue()).orElse(null)));
```

- [ ] **Step 4: run, then the mutant replay.** Green. Mutant: render for any non-null level
  (`if (view.urgency() != null) { … copy.urgency(lang, Urgency.fromDbValue(view.urgency()), false) … }`) → red in
  `routineAndUnassessedShowNoUrgencyLine[2] routine`: `IllegalArgumentException: ROUTINE has no urgency line (D27)` — the
  failure names the ROUTINE case. Revert. Paste.
- [ ] **Step 5:** `git status --ignored --short -- baymax/src | grep '^!!'` prints nothing (new `common/time`); standard
  pre-merge with the migration preflight and P.3. Commit (explicit paths, the two docs tables included):
  `feat(baymax): F1 stored urgency that only rises, asserted by the database (V18)`.

### Try it (A10)

- [ ] **prod-web:** send **t3** (THIS_WEEK). When it opens, the first block under the date reads
  "এ সপ্তাহের মধ্যে একজন ডাক্তার দেখান।" — even before any explanation.
- [ ] **prod-web:** send **t7** (NOW): "এখনই ডাক্তারের কাছে যান। দেরি করবেন না।" on the red header. Send **t1**: no
  urgency line at all, and no status word.
- [ ] **prod-web:** open a paper from before this deploy that had a NOW or THIS_WEEK message: its line shows (the
  backfill). Reload twice: nothing changes. Toggle English: "See a doctor within this week." / "Go to a doctor now. Do
  not delay."

---

## PR A11 — F1b: the sole writer, the sweep, the backfill of everything V18 left NULL

Branch `feat/baymax-urgency-sole-writer`. After A10, `ExplanationService` still calls `UrgencyService.assess` itself
(`ExplanationService.java:94` and `:105`) and composes at a fresh, possibly lower level. This PR composes at the
**stored** level, makes `StoredUrgencyService` the only class that assesses and the only class that raises, proves no
other code writes the urgency columns (ArchUnit plus a source sweep over every SQL string and migration), and adds the
startup and hourly sweep: DONE documents V18's backfill left NULL (never messaged), and documents whose follow-up date
has passed on the patient's calendar, are assessed with nobody uploading.

### Task 13b.1: ExplanationService composes at the stored level; a re-crop is an assessment site

**Files:** modify `ExplanationService.java` (field `StoredUrgencyService storedUrgency` appended **last**, after `clock`;
`:94`, `:104-112`, `:148-156`), `extraction/adapter/in/handler/RecropHandler.java` (+`DocumentUrgencyUseCase`),
`ExplanationServiceTest` (wire, `:71-104`), `DetailDeliveryGuardsTest` (its `new ExplanationService(`), `StoredUrgencyService`
(`facts` becomes the only copy).

- [ ] **Step 1: failing tests** (in `ExplanationServiceTest`: `stored = new InMemoryDocumentUrgency()` and
  `new StoredUrgencyService(new UrgencyService(properties, clock), stored, documents, records, properties, clock)` as the
  last constructor argument):

```java
/** F1: a later, lower reading never lowers what is composed: the message is composed at the STORED level. */
@Test
void anExplanationIsComposedAtTheStoredLevelWhenAFreshAssessmentIsLower() {
    done(NORMAL);                                                   // assess() alone says ROUTINE
    stored.preset(DOC, Urgency.NOW, List.of("value_critical:hba1c|stopgap"));
    replies.push("HbA1c 5.0। এখনই ডাক্তারের কাছে যান। দেরি করবেন না।");

    StepVerifier.create(service.composeChecked(DOC, OutboundMessage.Kind.EXPLANATION))
            .assertNext(m -> {
                assertThat(m.urgency()).isEqualTo(Urgency.NOW);
                assertThat(m.urgencyReasons()).containsExactly("value_critical:hba1c|stopgap");
            })
            .verifyComplete();
}

/** The other direction: a higher fresh reading raises what is stored, and the message carries it. */
@Test
void aHigherFreshAssessmentRaisesTheStoredLevelAndTheMessage() {
    done(CRITICAL);
    stored.preset(DOC, Urgency.ROUTINE, List.of());
    replies.push("HbA1c 11.2। এখনই ডাক্তারের কাছে যান। দেরি করবেন না।");

    StepVerifier.create(service.composeChecked(DOC, OutboundMessage.Kind.EXPLANATION))
            .assertNext(m -> assertThat(m.urgency()).isEqualTo(Urgency.NOW))
            .verifyComplete();
    assertThat(stored.find(DOC).block().level()).isEqualTo(Urgency.NOW);
}
```

- [ ] **Step 2: red.** `anExplanationIsComposedAtTheStoredLevel…` → `expected: NOW but was: ROUTINE` (today's fresh read).
- [ ] **Step 3: implement.**

```diff
     private final Clock clock;
+    private final StoredUrgencyService storedUrgency;
@@ :94
-                    case DONE -> facts(document).flatMap(facts -> compose(facts, kind, urgency.assess(facts)));
+                    case DONE -> facts(document).flatMap(facts -> storedUrgency.assessAndStore(facts)
+                            .flatMap(stored -> compose(facts, kind, stored.asAssessment())));
@@ :104-112
-                .flatMap(document -> facts(document).flatMap(facts -> {
-                    UrgencyAssessment assessed = urgency.assess(facts);
-                    return messages.latestOfKind(documentId, OutboundMessage.Kind.DETAIL)
+                .flatMap(document -> facts(document).flatMap(facts -> storedUrgency.assessAndStore(facts).flatMap(stored -> {
+                    UrgencyAssessment assessed = stored.asAssessment();
+                    return messages.latestOfKind(documentId, OutboundMessage.Kind.DETAIL)
@@ :148-156
-    private Mono<DocumentFacts> facts(Document document) { … }
+    private Mono<DocumentFacts> facts(Document document) {
+        return storedUrgency.facts(document);
+    }
```

  `StoredUrgencyService.facts` is now the only copy (A10's comment removed). The `urgency changed after assessment` check
  (`:248-251`) stays and now compares the message with the stored level it was composed at. `stillCurrent` is unchanged:
  stored reasons only grow, so a cached DETAIL composed before a new reason arrived is recomposed. The `UrgencyService
  urgency` field stays for `criticalKind` until B1 removes its last use. `RecropHandler.java:30`:
  `recrop.recrop(id).flatMap(r -> urgency.assessAndStore(id).thenReturn(r))` — a re-crop changes which values are shown.
- [ ] **Step 4: run** `./gradlew :baymax:test` → green (`DetailCacheAcceptanceTest`, `WithinNormalRangeClaimTest`,
  `UrgencyHooksAreInertTest` unchanged).

### Task 13b.2: one writer — asserted on calls and on every SQL string

**Files:** create test `architecture/OneUrgencyWriterTest.java`.

- [ ] **Step 1: failing test.**

```java
package com.elioo.baymax.architecture;

import com.elioo.baymax.outbound.application.port.out.DocumentUrgencyPort;
import com.elioo.baymax.outbound.application.service.UrgencyService;
import com.elioo.baymax.outbound.domain.DocumentFacts;
import com.elioo.baymax.outbound.domain.UrgencyAssessment;
import com.elioo.baymax.outbound.domain.UrgencyContext;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * F1/F2: one writer. Only StoredUrgencyService assesses and raises; only PostgresDocumentUrgencyAdapter (and the
 * migrations that created the columns) writes the urgency and verdict columns. The trigger stops a lowering; this stops
 * a second writer raising from the wrong source.
 */
class OneUrgencyWriterTest {

    private static final JavaClasses MAIN = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.elioo.baymax");
    private static final Pattern LITERAL = Pattern.compile("\"\"\"(.*?)\"\"\"|\"((?:[^\"\\\\\\n]|\\\\.)*)\"", Pattern.DOTALL);
    private static final Pattern WRITES = Pattern.compile(
            "(?is)\\bdocument\\b(?:\\s+\\w+)?\\s+SET\\b[^;]*?\\b(urgency|urgency_reasons|urgency_assessed_at)\\s*="
                    + "|\\bobservation\\b(?:\\s+\\w+)?\\s+SET\\b[^;]*?\\bverdict[a-z_]*\\s*="
                    + "|\\bdocument\\s*\\([^)]*\\burgency|\\bobservation\\s*\\([^)]*\\bverdict");
    private static final Set<String> ALLOWED = Set.of("PostgresDocumentUrgencyAdapter.java",
            "V18__document_stored_urgency.sql", "V19__observation_value_verdict.sql");

    @Test
    void onlyTheWriterAssesses() {
        noClasses().that().doNotHaveSimpleName("StoredUrgencyService").and().doNotHaveSimpleName("UrgencyService")
                .should().callMethod(UrgencyService.class, "assess", DocumentFacts.class)
                .orShould().callMethod(UrgencyService.class, "assess", DocumentFacts.class, UrgencyContext.class)
                .because("F1: every assessment goes through the one writer that stores max(old, new)").check(MAIN);
    }

    @Test
    void onlyTheWriterRaises() {
        noClasses().that().doNotHaveSimpleName("StoredUrgencyService")
                .should().callMethod(DocumentUrgencyPort.class, "raise", UUID.class, UrgencyAssessment.class, Instant.class)
                .because("F1: one writer, so the stored level always comes from UrgencyService.assess").check(MAIN);
    }

    @Test
    void onlyTheAdapterAndItsMigrationsWriteTheColumns() throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Stream.concat(Files.walk(Path.of("src/main/java")), Files.walk(Path.of("src/main/resources")))) {
            files = walk.filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".sql")).toList();
        }
        assertThat(files).hasSizeGreaterThan(100);
        List<String> found = new ArrayList<>();
        int allowedWrites = 0;
        for (Path p : files) {
            String text = Files.readString(p);
            List<String> sql = new ArrayList<>();
            if (p.toString().endsWith(".sql")) {
                sql.add(text);
            } else {
                for (Matcher l = LITERAL.matcher(text); l.find(); ) {
                    sql.add(l.group(1) != null ? l.group(1) : l.group(2));
                }
            }
            for (String s : sql) {
                Matcher w = WRITES.matcher(s);
                while (w.find()) {
                    if (ALLOWED.contains(p.getFileName().toString())) {
                        allowedWrites++;
                    } else {
                        found.add(p.getFileName() + ": " + w.group().replaceAll("\\s+", " "));
                    }
                }
            }
        }
        assertThat(allowedWrites).as("the sweep sees the writer's own writes (a pattern that sees nothing proves nothing)").isPositive();
        assertThat(found).isEmpty();
    }
}
```

- [ ] **Step 2: red on today's source (after 13b.1 reverted in a scratch copy, or replayed at A10's merge commit):**
  `Method <…ExplanationService.lambda$composeChecked$…> calls method <…UrgencyService.assess(DocumentFacts)> in (ExplanationService.java:94)`
  and `:105`. With 13b.1 in, green.
- [ ] **Step 3: replays (mutants; revert each).** Add `stored.raise(id, UrgencyAssessment.routine(), clock.instant())` to
  `ExplanationService` (with a `DocumentUrgencyPort stored` field) → `onlyTheWriterRaises` red naming
  `ExplanationService`. Add the literal `"UPDATE " + S + ".document SET urgency = 'now' WHERE id = :id"` to
  `PostgresDocumentRecordAdapter` → `PostgresDocumentRecordAdapter.java: document SET urgency =`.

### Task 13b.3: the sweep — the backfill of NULLs, past-due follow-ups, and a queue that cannot starve

**Files:** create `outbound/adapter/in/UrgencySweepJob.java`; modify `DocumentUrgencyPort` (+`needingAssessment`,
`markAttempt`), `DocumentUrgencyUseCase` (+`sweep`), `PostgresDocumentUrgencyAdapter`, `StoredUrgencyService`,
`DocumentUrgencyNeverLowersTest`, `StoredUrgencyServiceTest`, `PatientCalendarRulesAgreeTest`, `application.properties`.

- [ ] **Step 1: failing tests** (in `DocumentUrgencyNeverLowersTest`; `followUp(doc, date)` is its helper):

```java
    /** The sweep's predicate is the rule's (UrgencyService, PatientCalendar): a follow-up due BEFORE today; today is not past. */
    @Test
    void theSweepPicksUnassessedAndPastDueAndNeitherDueTodayNorAlreadyRaised() throws Exception {
        runner().run(context -> {
            DocumentUrgencyPort port = context.getBean(DocumentUrgencyPort.class);
            UUID unassessed = doneDocument(context);
            UUID pastDue = doneDocument(context);
            UUID dueToday = doneDocument(context);
            UUID dueTomorrow = doneDocument(context);
            UUID alreadyRaised = doneDocument(context);
            for (UUID d : List.of(pastDue, dueToday, dueTomorrow, alreadyRaised)) {
                port.raise(d, UrgencyAssessment.routine(), T).block();
            }
            followUp(pastDue, "2026-10-19");
            followUp(dueToday, "2026-10-20");
            followUp(dueTomorrow, "2026-10-21");
            followUp(alreadyRaised, "2026-10-19");
            port.raise(alreadyRaised, new UrgencyAssessment(Urgency.THIS_WEEK, List.of("follow_up_past_due")), T).block();

            assertThat(port.needingAssessment(LocalDate.parse("2026-10-20"), 500).collectList().block())
                    .containsExactlyInAnyOrder(unassessed, pastDue);
        });
    }

    /** A document picked and attempted goes behind every document not yet attempted: a throwing one cannot starve the rest. */
    @Test
    void anAttemptedDocumentGoesToTheBackOfTheQueue() {
        runner().run(context -> {
            DocumentUrgencyPort port = context.getBean(DocumentUrgencyPort.class);
            UUID older = doneDocument(context);
            UUID newer = doneDocument(context);
            assertThat(port.needingAssessment(LocalDate.parse("2026-10-20"), 1).collectList().block()).containsExactly(older);
            port.markAttempt(older, T).block();
            assertThat(port.needingAssessment(LocalDate.parse("2026-10-20"), 1).collectList().block()).containsExactly(newer);
        });
    }

    /** After one sweep there is nothing left to assess: the predicate and the rule agree, so the queue converges. */
    @Test
    void aSweepConverges() {
        runner().withBean(Clock.class, () -> Clock.fixed(Instant.parse("2026-10-20T08:00:00Z"), ZoneOffset.UTC)).run(context -> {
            UUID unassessed = doneDocument(context);
            UUID pastDue = doneDocument(context);
            DocumentUrgencyPort port = context.getBean(DocumentUrgencyPort.class);
            port.raise(pastDue, UrgencyAssessment.routine(), T).block();
            followUp(pastDue, "2026-10-19");

            Long assessed = context.getBean(DocumentUrgencyUseCase.class).sweep().block();

            assertThat(assessed).isGreaterThanOrEqualTo(2L);
            assertThat(port.needingAssessment(LocalDate.parse("2026-10-20"), 500).collectList().block())
                    .doesNotContain(unassessed, pastDue);
            assertThat(port.find(pastDue).block().reasons()).contains("follow_up_past_due");
        });
    }
```

  `StoredUrgencyServiceTest`: three ids, the second's assessment throws → `sweep()` emits `2`, the WARN carries the class
  name only, and `InOrder` shows `markAttempt(id)` before each assessment. `PatientCalendarRulesAgreeTest` gains, at the
  same three instants as A10's case, the sweep's predicate on Testcontainers: due `2026-10-19` is picked exactly when
  the rule says past due (one more `@Testcontainers` method in that class, reusing `runner()`).
- [ ] **Step 2: red.** `cannot find symbol needingAssessment` → with a stub returning every DONE id ordered by
  `created_at` and no attempt stamp: `theSweepPicks… … but some elements were not expected: [<dueToday>, <dueTomorrow>, <alreadyRaised>]`
  and `anAttemptedDocument… expected: [<newer>] but was: [<older>]`.
- [ ] **Step 3: implement.**

```sql
SELECT d.id FROM %1$s.document d
WHERE d.status = 'DONE'
  AND (d.urgency IS NULL
       OR (NOT d.urgency_reasons @> '["follow_up_past_due"]'::jsonb
           AND EXISTS (SELECT 1 FROM %1$s.follow_up f WHERE f.document_id = d.id AND f.due_date < :today)))
ORDER BY d.urgency_attempted_at NULLS FIRST, d.created_at, d.id
LIMIT :limit
```

  `markAttempt(id, at)` is `UPDATE %1$s.document SET urgency_attempted_at = :at WHERE id = :id` (no `updated_at`).

```java
    static final int SWEEP_LIMIT = 500;

    @Override
    public Mono<Long> sweep() {
        LocalDate today = PatientCalendar.today(clock);   // the day UrgencyService judges follow_up_past_due by
        return stored.needingAssessment(today, SWEEP_LIMIT)
                .concatMap(id -> stored.markAttempt(id, clock.instant())
                        .then(assessAndStore(id))
                        .onErrorResume(e -> {
                            log.warn("[baymax] urgency sweep skipped documentId={} error={}", id, e.getClass().getSimpleName());
                            return Mono.empty();
                        }))
                .count();
    }
```

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class UrgencySweepJob {

    private final DocumentUrgencyUseCase urgency;

    /** Backfill: DONE documents V18 left NULL (never messaged) are assessed by code once the app is ready. */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        run("startup");
    }

    /** follow_up_past_due is date-driven (UrgencyService.java:97-108): the level can rise with nobody uploading. */
    @Scheduled(cron = "${baymax.urgency.sweep-cron:0 20 * * * *}")
    public void hourly() {
        run("hourly");
    }

    private void run(String why) {
        urgency.sweep().subscribeOn(Schedulers.boundedElastic())
                .subscribe(n -> log.info("[baymax] urgency sweep done trigger={} documents={}", why, n),
                        e -> log.error("[baymax] urgency sweep failed trigger={} error={}", why, e.getClass().getSimpleName()));
    }
}
```

  `application.properties`: `baymax.urgency.sweep-cron=${BAYMAX_URGENCY_SWEEP_CRON:0 20 * * * *}` beside the nudge cron.
- [ ] **Step 4: run** `./gradlew :baymax:test`, then `./gradlew build`; `git status --ignored --short -- baymax/src | grep '^!!'`
  prints nothing (new `outbound/adapter/in`). Standard pre-merge. Commit
  `feat(baymax): one urgency writer, composed at the stored level, and an hourly sweep that cannot starve`.

### Try it (A11)

- [ ] **prod-web:** a paper from before V18 that was never messaged (or never explained) now shows its line within a
  minute of the deploy (the startup sweep), and stays the same after reloads.
- [ ] **prod-web:** "বিস্তারিত" on WhatsApp is not part of this try; the WhatsApp detail is unchanged except that it now
  states the stored level (proven by `ExplanationServiceTest`).
- [ ] **admin:** `docker logs medscribe-ai --since 2h 2>&1 | grep -c 'urgency sweep done trigger=startup'` is 1, and no
  `urgency sweep failed` line.

---

## PR A12 — 2.1: the result read model, the stored banner on first paint, the F3 review states (s10)

Branch `feat/baymax-result-read-model`. Spec §4.10, F1, F3, D5. A DONE document's page is now the result: the patient
named in a chip ("Ma · ৬৪ বছর"; F8's possessive headline waits for D2), the document type and date, then **the stored
urgency line as the first section of the page, in every review state**, then what the review state allows: the
released explanation labelled "Medioo-র কথা", or "একজন মিলিয়ে দেখছেন" for PENDING, or the withheld line for REJECTED and
FAILED_SAFETY — never "being checked" for something no one will release. A routine paper has no banner and says only
"তুলে রাখা হয়েছে". The value rows, medicines and doctor's lines are B2; NOW's reason blocks and emergency action are B3.
This replaces A10's interim banner and its interim tests.

### Task 21.1: `GateState`, `ResultView` — wording travels only with RELEASED, by construction

**Files:** create `web/domain/GateState.java`, `web/domain/ResultView.java`; test (append) `ResultServiceTest`.

- [ ] **Step 1: failing test** (append to A9's `ResultServiceTest`):

```java
    /** F3 as a constructor rule: wording exists only for RELEASED, so no renderer can show a parked or rejected body. */
    @ParameterizedTest
    @EnumSource(value = GateState.class, names = {"NONE", "PENDING", "REJECTED", "FAILED_SAFETY"})
    void modelWordingCannotTravelWithAnyGateStateButReleased(GateState gate) {
        assertThatThrownBy(() -> new ResultView(DocumentView.pending(Document.received(PATIENT, FAMILY, 1, NOW).withId(DOC)
                .withStatus(Document.Status.DONE, null, NOW)), MA, true, new ViewerLocale(true), Urgency.NOW, List.of(), gate,
                "body", 0, 2, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("F3: model wording only when released");
    }
```

- [ ] **Step 2: red** (compile, then `Expecting code to raise a throwable` with a constructor that does not check).
  **Step 3: implement.**

```java
package com.elioo.baymax.web.domain;

/**
 * F3, collapsed to what a page may do. RELEASED covers APPROVED: both are deliverable. NONE means a DONE document
 * whose explanation was never stored (explain threw); the page shows urgency and values with no wording line.
 */
public enum GateState { NONE, RELEASED, PENDING, REJECTED, FAILED_SAFETY }
```

```java
package com.elioo.baymax.web.domain;

import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.outbound.domain.Urgency;

import java.time.Instant;
import java.util.List;

/**
 * Everything a document page renders, read from stored rows only. {@code urgency} is the document's stored F1 level
 * and is null only while the document is not DONE. {@code wording} is the released explanation's body and is non-null
 * only when {@code gate == RELEASED} (F3, asserted here so no renderer can show a parked or rejected body).
 *
 * @param retakeOrdinal 1, 2, 3… for a NEEDS_RETAKE document (this patient's streak up to and including it), else 0
 * @param reviewHours   the typical review time in the PENDING copy (spec §4.10 "[N]"), from configuration
 */
public record ResultView(DocumentView document, PatientProfile patient, boolean owner, ViewerLocale viewer,
                         Urgency urgency, List<String> urgencyReasons, GateState gate, String wording,
                         int retakeOrdinal, int reviewHours, Instant receivedAt, Instant now) {
    public ResultView {
        if (wording != null && gate != GateState.RELEASED) {
            throw new IllegalArgumentException("F3: model wording only when released or approved, not " + gate);
        }
        if (urgency != null && !"DONE".equals(document.status())) {
            throw new IllegalArgumentException("urgency is shown from DONE onwards, not " + document.status());
        }
        urgencyReasons = urgencyReasons == null ? List.of() : List.copyOf(urgencyReasons);
    }

    public boolean done() {
        return "DONE".equals(document.status());
    }
}
```

### Task 21.2: `ResultService.result` — the stored level, the newest explanation in any state, the viewer

**Files:** modify `ResultUseCase` (+`result`), `ResultService` (fields `OutboundMessagePort messages`,
`DocumentUrgencyUseCase urgencies`, `BaymaxProperties properties` appended last), `ResultServiceTest`,
`config/BaymaxProperties.java` (`Outbound.reviewTypicalHours = 2`, Javadoc "the PENDING copy's [N]; measure in week 0"),
`application.properties` (`baymax.outbound.review-typical-hours=${BAYMAX_REVIEW_TYPICAL_HOURS:2}` beside `gate-mode`).

- [ ] **Step 1: failing tests** (append; the wire gains `messages`, `urgencies` mocks and `new BaymaxProperties()`):

```java
    private final OutboundMessagePort messages = mock(OutboundMessagePort.class);
    private final DocumentUrgencyUseCase urgencies = mock(DocumentUrgencyUseCase.class);

    private void done() {
        Document done = Document.received(PATIENT, FAMILY, 1, NOW).withId(DOC).withStatus(Document.Status.DONE, null, NOW);
        when(documents.findVisible(FAMILY, DOC)).thenReturn(Mono.just(done));
        when(intake.view(DOC)).thenReturn(Mono.just(DocumentView.pending(done)));
        when(urgencies.currentOrAssess(DOC)).thenReturn(Mono.just(new StoredUrgency(DOC, Urgency.NOW, List.of("value_critical:potassium|stopgap"), NOW)));
        when(messages.latestOfKind(DOC, OutboundMessage.Kind.EXPLANATION)).thenReturn(Mono.empty());
    }

    /** F3 table, one row per stored gate status: the urgency is the stored NOW in every one; wording only when deliverable. */
    @ParameterizedTest(name = "{0} shows as {1}, wording {2}")
    @CsvSource({"RELEASED,RELEASED,true", "APPROVED,RELEASED,true", "PENDING,PENDING,false",
            "REJECTED,REJECTED,false", "FAILED_SAFETY,FAILED_SAFETY,false"})
    void theExplanationsGateStateDecidesTheWordingAndNeverTheUrgency(OutboundMessage.GateStatus stored, GateState shown, boolean wording) {
        done();
        String body = stored == OutboundMessage.GateStatus.FAILED_SAFETY ? null : "রিপোর্টটি পেয়েছি।";
        when(messages.latestOfKind(DOC, OutboundMessage.Kind.EXPLANATION)).thenReturn(Mono.just(message(stored, body, Urgency.NOW)));
        ResultView r = service.result(FAMILY, DOC).block();
        assertThat(r.urgency()).isEqualTo(Urgency.NOW);
        assertThat(r.gate()).isEqualTo(shown);
        assertThat(r.wording() != null).isEqualTo(wording);
    }

    /** F1: the page reads the document's stored level. A message row's urgency is history, not the answer. */
    @Test
    void theUrgencyComesFromTheDocumentNeverFromAMessage() {
        done();
        when(messages.latestOfKind(DOC, OutboundMessage.Kind.EXPLANATION))
                .thenReturn(Mono.just(message(OutboundMessage.GateStatus.RELEASED, "x", Urgency.ROUTINE)));
        assertThat(service.result(FAMILY, DOC).block().urgency()).isEqualTo(Urgency.NOW);
        verify(urgencies).currentOrAssess(DOC);
    }

    /** Before DONE there is no urgency and no message is read: "no result text before DONE" (§4.9). */
    @Test
    void aDocumentStillBeingReadCarriesNoUrgencyAndReadsNoMessage() {
        ResultView r = service.result(FAMILY, DOC).block();   // A9's wire: a RECEIVED document
        assertThat(r.urgency()).isNull();
        assertThat(r.gate()).isEqualTo(GateState.NONE);
        verify(messages, never()).latestOfKind(any(), any());
        verify(urgencies, never()).currentOrAssess(any());
    }

    private static OutboundMessage message(OutboundMessage.GateStatus gate, String body, Urgency level) {
        return new OutboundMessage(UUID.randomUUID(), FAMILY, PATIENT, DOC, OutboundMessage.Kind.EXPLANATION, level,
                List.of(), body, gate, null, null, null, null, NOW);
    }
```

- [ ] **Step 2: red, and the replays that must fail on the defect.**
  - Implement `result` as `TimelineService.explanationOf` reads today (`latestDeliverable`, urgency from the message's
    `urgency()`): `theUrgencyComesFromTheDocument…` fails `expected: NOW but was: ROUTINE`, and the PENDING row fails
    `expected: PENDING but was: NONE` (a pending row is invisible to `latestDeliverable`, so the page cannot tell "being
    checked" from "never written").
  - **The F3 guard is the constructor.** Replay at `Gate.of`: map `PENDING` to `new Gate(GateState.PENDING, m.body())`.
    The PENDING row must fail with `IllegalArgumentException: F3: model wording only when released or approved, not PENDING`.
    The renderer assertion in 21.3 (`doesNotContain("id=\"says\"")`) is a second line of defence; it cannot be the guard,
    because the constructor already throws before any renderer runs.
- [ ] **Step 3: implement.**

```java
    /** One DONE document as its result page shows it. 404 when this family cannot see it. */
    Mono<ResultView> result(UUID familyId, UUID documentId);
```

```java
    @Override
    public Mono<ResultView> result(UUID familyId, UUID documentId) {
        return visibleDocument(familyId, documentId).flatMap(d -> Mono.zip(
                        records.visiblePatient(familyId, d.patientId())
                                .switchIfEmpty(Mono.error(BaymaxException.notFound("patient_not_found", "no patient with id " + d.patientId()))),
                        viewer(familyId),
                        audit.record(AuditEvent.family(AuditEvent.Kind.DOCUMENT_VIEW, familyId, d.patientId(), documentId, clock.instant()))
                                .then(intake.view(documentId)),
                        stored(d), gate(d))
                .map(t -> new ResultView(t.getT3(), t.getT1().patient(), t.getT1().owner(), t.getT2(),
                        t.getT4().map(StoredUrgency::level).orElse(null), t.getT4().map(StoredUrgency::reasons).orElse(List.of()),
                        t.getT5().state(), t.getT5().wording(), 0, properties.getOutbound().getReviewTypicalHours(),
                        d.createdAt(), clock.instant())));
    }

    /** F1: the stored level, through the use case; never assessed here (OneUrgencyWriterTest). */
    private Mono<Optional<StoredUrgency>> stored(Document d) {
        return d.status() != Document.Status.DONE ? Mono.just(Optional.empty())
                : urgencies.currentOrAssess(d.id()).map(Optional::of).defaultIfEmpty(Optional.empty());
    }

    private record Gate(GateState state, String wording) {
        static final Gate NONE = new Gate(GateState.NONE, null);

        static Gate of(OutboundMessage m) {
            return switch (m.gateStatus()) {
                case RELEASED, APPROVED -> m.body() == null ? new Gate(GateState.FAILED_SAFETY, null) : new Gate(GateState.RELEASED, m.body());
                case PENDING -> new Gate(GateState.PENDING, null);
                case REJECTED -> new Gate(GateState.REJECTED, null);
                case FAILED_SAFETY -> new Gate(GateState.FAILED_SAFETY, null);
            };
        }
    }

    /** F3 reads the newest EXPLANATION whatever its state (latestOfKind, PR 5), not latestDeliverable, which hides PENDING. */
    private Mono<Gate> gate(Document d) {
        return d.status() != Document.Status.DONE ? Mono.just(Gate.NONE)
                : messages.latestOfKind(d.id(), OutboundMessage.Kind.EXPLANATION).map(Gate::of).defaultIfEmpty(Gate.NONE);
    }
```

  (`ordinal` is `0` here; B5 reads it from `DocumentReadPort`.) Run `ResultServiceTest`, `HexagonalArchitectureTest`,
  `OneUrgencyWriterTest` → green.

### Task 21.3: `DocumentPages.summary` — the banner on first paint, the F3 line, the patient named

**Files:** create tests `ui/ResultFixtures.java`, `ui/ResultSummaryRenderTest.java`; modify `ui/DocumentPages.java`
(`documentPage` sends DONE to `summaryPage`; A10's interim block removed), `DocumentPagesRenderTest` (A10's two interim
tests deleted: `ResultSummaryRenderTest` replaces them on the real page), `css/60-result.css`, `ui-result_*`.

- [ ] **Step 1: failing tests.** `ResultFixtures` is the only place a test calls `DocumentView`'s canonical constructor
  (B1 and B4 change its shape; this helper absorbs it):

```java
package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.extraction.domain.DocumentView;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.web.domain.GateState;
import com.elioo.baymax.web.domain.ResultView;
import com.elioo.baymax.web.domain.ViewerLocale;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Result-page fixtures. The only test code that calls DocumentView's constructor, so B1/B4 shape changes land here once. */
final class ResultFixtures {

    static final Instant NOW = Instant.parse("2026-10-20T04:00:00Z");
    static final UUID DOC = WebUiTestKit.DOC_ID;
    static final PatientProfile MA = new PatientProfile(WebUiTestKit.MA_ID, WebUiTestKit.FAMILY, "Ma", 64,
            PatientProfile.Sex.FEMALE, List.of(), NOW, NOW);
    static final ViewerLocale BD = new ViewerLocale(true);
    static final ViewerLocale ABROAD = new ViewerLocale(false);

    private ResultFixtures() {
    }

    static Map<String, Object> value(String name, String value, String unit, String low, String high, String verdict) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", UUID.nameUUIDFromBytes((name + value).getBytes(StandardCharsets.UTF_8)));
        m.put("name", name);
        m.put("canonical_name", name.toLowerCase(Locale.ROOT));
        m.put("value", value);
        if (unit != null) { m.put("unit", unit); }
        if (low != null) { m.put("ref_low", low); }
        if (high != null) { m.put("ref_high", high); }
        if (verdict != null) { m.put("verdict", verdict); }
        m.put("crop_key", "f/p/d/crop-" + name.replaceAll("[^A-Za-z0-9]", "") + ".jpg");
        return m;
    }

    static DocumentView lab(List<Map<String, Object>> values) {
        return new DocumentView(DOC.toString(), "DONE", null, "lab_report", "2026-10-12", "Diagnostic Centre, Mirpur", 0.92,
                "m", 2, values, List.of(), List.of(), Map.of(), null);
    }

    static DocumentView prescription(List<Map<String, Object>> medicines, Map<String, Object> clinicalContext, Map<String, Object> unverified) {
        return new DocumentView(DOC.toString(), "DONE", null, "prescription", "2026-10-12", "Demo Chamber", 0.9,
                "m", 1, List.of(), medicines, List.of(), clinicalContext, unverified);
    }

    static DocumentView pending(String status) {
        return new DocumentView(DOC.toString(), status, null, null, null, null, null, null, 1, null, null, null, null, null);
    }

    static ResultView result(DocumentView view, Urgency urgency, List<String> reasons, GateState gate, String wording, ViewerLocale viewer) {
        return new ResultView(view, MA, true, viewer, "DONE".equals(view.status()) ? urgency : null, reasons, gate, wording,
                0, 2, NOW.minusSeconds(60), NOW);
    }

    /** The element with this id, through its closing tag: assertions read one block, never the whole page (UUIDs). */
    static String section(String html, String id) {
        int at = html.indexOf("id=\"" + id + "\"");
        assertThat(at).as("block #" + id).isNotNegative();
        int open = html.lastIndexOf('<', at);
        String tag = html.substring(open + 1, html.indexOf(' ', open));
        return html.substring(open, html.indexOf("</" + tag + ">", at) + tag.length() + 3);
    }
}
```

```java
package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.web.domain.GateState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/** s10 / F1 / F3: the stored urgency first, in every gate state, at both levels; model wording only when released. */
class ResultSummaryRenderTest {

    private final WebUiTestKit kit = new WebUiTestKit();
    private final UiCopy copy = kit.copy;

    private String body(Lang lang, Urgency level, GateState gate) {
        return kit.documents.summaryBody(lang, ResultFixtures.result(ResultFixtures.lab(List.of()), level,
                level == Urgency.NOW ? List.of("value_critical:potassium|stopgap") : List.of("diagnosis_present"),
                gate, gate == GateState.RELEASED ? "রিপোর্টটি পেয়েছি।" : null, ResultFixtures.BD));
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> everyLevelInEveryGateState() {
        return Stream.of(Urgency.NOW, Urgency.THIS_WEEK)
                .flatMap(l -> Arrays.stream(GateState.values()).map(g -> arguments(l, g)));
    }

    /** D5: whatever the review state, the stored line is on the page, verbatim, and is the first section of main. */
    @ParameterizedTest(name = "{0} in {1}")
    @MethodSource("everyLevelInEveryGateState")
    void theStoredUrgencyLeadsThePageInEveryGateState(Urgency level, GateState gate) {
        String html = body(Lang.BN, level, gate);
        String banner = ResultFixtures.section(html, "urgency");
        assertThat(banner).contains(copy.urgency(Lang.BN, level, false)).doesNotContain("<button").doesNotContain("dismiss");
        int at = html.indexOf("id=\"urgency\"");
        Matcher other = Pattern.compile("<section(?![^>]*id=\"urgency\")").matcher(html);
        while (other.find()) {
            assertThat(other.start()).as("a section before the banner").isGreaterThan(at);
        }
    }

    @ParameterizedTest
    @EnumSource(Lang.class)
    void theBannerIsTheBundleLineInEitherLanguage(Lang lang) {
        assertThat(ResultFixtures.section(body(lang, Urgency.THIS_WEEK, GateState.RELEASED), "urgency"))
                .contains(copy.urgency(lang, Urgency.THIS_WEEK, false));
    }

    /** D27, the other direction: a routine paper carries none of the urgency lines anywhere and no status word; it is "filed". */
    @ParameterizedTest
    @EnumSource(GateState.class)
    void aRoutinePaperCarriesNoUrgencyLineAndSaysOnlyThatItWasFiled(GateState gate) {
        String html = body(Lang.BN, Urgency.ROUTINE, gate);
        BundleFiles.messages(Lang.BN).entrySet().stream().filter(e -> e.getKey().startsWith("urgency."))
                .forEach(e -> assertThat(html).as(e.getKey()).doesNotContain(Html.esc(e.getValue())));
        assertThat(html).contains(copy.t(Lang.BN, "result.filed"));
        for (String word : List.of("স্বাভাবিক", "চিন্তার কিছু নেই", "সব ঠিক")) {
            assertThat(html).as(word).doesNotContain(word);
        }
    }

    /** F3: pending says a person is checking (with the configured hours); the model's text is not on the page. */
    @Test
    void pendingSaysAPersonIsCheckingAndShowsNoModelText() {
        String html = body(Lang.BN, Urgency.THIS_WEEK, GateState.PENDING);
        assertThat(ResultFixtures.section(html, "gate-pending")).contains(copy.t(Lang.BN, "result.gate.pending", 2))
                .contains("২ ঘণ্টার মধ্যে").contains(copy.t(Lang.BN, "result.review.chip"));
        assertThat(html).doesNotContain("id=\"says\"").doesNotContain("রিপোর্টটি পেয়েছি।");
    }

    /** F3: a withheld explanation is never described as being checked — that would be a promise nobody keeps. */
    @ParameterizedTest
    @EnumSource(value = GateState.class, names = {"REJECTED", "FAILED_SAFETY"})
    void aWithheldExplanationSaysSoAndNeverThatItIsBeingChecked(GateState gate) {
        String html = body(Lang.BN, Urgency.NOW, gate);
        assertThat(ResultFixtures.section(html, "gate-withheld")).contains(copy.t(Lang.BN, "result.gate.withheld"));
        assertThat(html).doesNotContain(copy.t(Lang.BN, "result.review.chip")).doesNotContain("id=\"says\"");
    }

    /** Principle 6: the released wording is labelled as Medioo's, and carries no crop (a crop means "the paper says"). */
    @Test
    void theReleasedWordingIsLabelledAsMediooSWordsAndCarriesNoCrop() {
        String says = ResultFixtures.section(body(Lang.BN, Urgency.THIS_WEEK, GateState.RELEASED), "says");
        assertThat(says).contains(copy.t(Lang.BN, "result.says.label")).contains("রিপোর্টটি পেয়েছি।").doesNotContain("<img");
    }

    /** The patient is named on every result, before F8 grammar exists. */
    @Test
    void thePatientIsNamedInTheHeader() {
        assertThat(body(Lang.BN, Urgency.ROUTINE, GateState.NONE)).contains("<span class=\"pchip\">Ma · ৬৪ বছর</span>");
    }
}
```

- [ ] **Step 2: red, and the replays.** `summaryBody` missing (compile). Then:
  - Mutant: append `gateLine` before `banner` in `summaryBody` → `theStoredUrgencyLeadsThePage…[NOW in RELEASED]` red
    `[a section before the banner] Expecting actual: <n> to be greater than: <m>` (the model's words above the urgency).
  - Mutant: render the banner for any non-null level → `aRoutinePaper…[*]` red with
    `IllegalArgumentException: ROUTINE has no urgency line (D27)`, naming the ROUTINE case.
  - Mutant: render THIS_WEEK only when `gate == RELEASED` → `theStoredUrgencyLeadsThePage…[THIS_WEEK in PENDING]` red
    `block #urgency Expecting actual: -1 to be greater than or equal to 0`.
  Paste the three reds; revert the mutants.
- [ ] **Step 3: implement** (`DocumentPages`; `summaryPage` wraps `summaryBody` in `Html.appPage` with the tabs, PAPERS
  current):

```java
    String summaryBody(Lang lang, ResultView r) {
        DocumentView v = r.document();
        StringBuilder b = new StringBuilder();
        b.append("<p><a class=\"back\" href=\"").append(PageChrome.BASE).append("/timeline?patient=").append(r.patient().id()).append("\">")
                .append(copy.t(lang, "app.back.papers")).append("</a></p>")
                .append("<span class=\"pchip\">").append(copy.t(lang, "result.patient", r.patient().name(), r.patient().age())).append("</span>")
                .append("<h1>").append(chrome.documentType(lang, v.documentType())).append("</h1>")
                .append("<p class=\"meta\">").append(copy.date(lang, v.documentDate()))
                .append(v.facility() == null ? "" : " · " + esc(v.facility())).append("</p>");
        if (r.urgency() == Urgency.ROUTINE) {
            b.append("<p class=\"meta\">").append(copy.t(lang, "result.filed")).append("</p>");
        }
        b.append(banner(lang, r));       // first section in main, on first paint: no script, no animation, no close
        b.append(gateLine(lang, r));
        b.append(legacyItems(lang, r));  // today's value/medicine/advice list, moved from render; B2 replaces it
        return b.toString();
    }

    String banner(Lang lang, ResultView r) {
        if (r.urgency() == null || r.urgency() == Urgency.ROUTINE) {
            return "";
        }
        boolean now = r.urgency() == Urgency.NOW;
        return "<section class=\"banner " + (now ? "now" : "soon") + "\" id=\"urgency\" aria-label=\"" + copy.t(lang, "result.banner.label")
                + "\"><p class=\"line\">" + copy.urgency(lang, r.urgency(), false) + "</p></section>";
    }

    String gateLine(Lang lang, ResultView r) {
        return switch (r.gate()) {
            case RELEASED -> "<section class=\"says\" id=\"says\" aria-label=\"" + copy.t(lang, "result.says.label") + "\"><p class=\"label\">"
                    + copy.t(lang, "result.says.label") + "</p><div class=\"explain\">" + esc(r.wording()) + "</div><p class=\"small\">"
                    + copy.t(lang, "result.says.note") + "</p></section>";
            case PENDING -> "<section class=\"gate\" id=\"gate-pending\"><span class=\"chip review\">" + copy.t(lang, "result.review.chip")
                    + "</span><p>" + copy.t(lang, "result.gate.pending", r.reviewHours()) + "</p></section>";
            case REJECTED, FAILED_SAFETY -> "<section class=\"gate\" id=\"gate-withheld\"><p>" + copy.t(lang, "result.gate.withheld") + "</p></section>";
            case NONE -> "";
        };
    }
```

- [ ] **Step 4: copy** (`ui-result_*`, from s10/s11, approved): `result.filed=তুলে রাখা হয়েছে`,
  `result.banner.label=জরুরি মাত্রা`, `result.says.label=Medioo-র কথা`, `result.says.note=এটা Medioo-র ভাষায় লেখা,
  কাগজের লেখা নয়।`, `result.review.chip=একজন মিলিয়ে দেখছেন`, `result.gate.pending=Medioo-র ব্যাখ্যাটা একজন মিলিয়ে দেখছেন —
  সাধারণত {0} ঘণ্টার মধ্যে; রাত হলে সকালে।`, `result.gate.withheld=এই কাগজের লেখা ব্যাখ্যা দেওয়া হবে না। কাগজে যা লেখা আছে
  নিচে দিলাম — প্রশ্ন থাকলে ডাক্তারকে দেখান।`; English: `Filed`, `Urgency`, `Medioo's words`, `This is in Medioo's
  words, not the paper's.`, `A person is checking it`, `Medioo's explanation is being checked by a person — usually within
  {0} hours; overnight, by morning.`, `No written explanation will be given for this paper. What the paper says is below
  — show it to a doctor if you have questions.`
- [ ] **Step 5: CSS** (`60-result.css`): `.banner{border-radius:var(--r-m);padding:var(--sp-4);margin:var(--sp-4) 0}`,
  `.banner.soon{background:var(--c-status-soon-container)}`, `.banner.soon .line{font-weight:700;color:var(--c-text)}`,
  `.banner.now{padding:0;overflow:hidden}`, `.banner.now .line{background:var(--c-status-now-solid);color:var(--c-on-status-now);padding:var(--sp-4);margin:0;font-weight:700}`,
  `.says{padding:var(--sp-4);border-radius:var(--r-l);background:var(--c-bubble-me)}`,
  `.chip.review{background:var(--c-status-unknown-container);color:var(--c-text-muted)}`. No `animation` or
  `transition` on `.banner` (s11: never animated). Red only under `.banner.now` (DesignTokensTest).
- [ ] **Step 6: run** `./gradlew :baymax:test` → green. Standard pre-merge. Commit
  `feat(baymax-web): result summary — the stored urgency first, in every review state`.

### Try it (A12)

- [ ] **prod-web:** open the t3 paper from কাগজপত্র: the amber banner "এ সপ্তাহের মধ্যে একজন ডাক্তার দেখান।" comes
  first, then "Medioo-র কথা" with the explanation. Open t7: the red header line first. Open t1: no banner, "তুলে রাখা
  হয়েছে" under the date.
- [ ] **prod-web:** the header names the patient ("Ma · ৬৪ বছর" with the real name). Toggle English: "See a doctor within this week."
- [ ] **not tried:** PENDING / REJECTED / FAILED_SAFETY (the gate stays off; proven by `ResultSummaryRenderTest` and
  `ResultServiceTest` in both gate modes).

---

## PR A13 — J1: the web-only journey, end to end, in code

Branch `test/baymax-web-only-journey`. The PO asked to "run and test the whole thing without WhatsApp in the web app, in
full". Every PR above tests its screen alone, with mocked ports. This one walks the whole journey through the **real**
Baymax context — real Postgres and MinIO, the real router, filters, services and adapters; only OCR and the model are
stubbed at their ports — with WhatsApp **switched off**, and fails if any family-facing route is neither visited nor
listed with a reason. A second test runs the same journey with WhatsApp on and a mocked messaging port, and asserts the
only messages sent are the sign-in code and the explanation of the paper uploaded on the web (today's behaviour: a web
upload is also told on WhatsApp; D32's "WhatsApp-এও জানাবেন" opt-in is Phase 4 — a PO note in the PR body). Each later
PR that adds a family-facing route adds its step here (or its reason), because the derived coverage check fails until it
does: B5 the retake, B6 the value sheet, C3 the chip, C4 the attention stack.

### Task J1.1: the journey with WhatsApp off

**Files:** create test `ui/WebOnlyJourneyAcceptanceTest.java` (in the `ui` package, for `RouteTable` and the copy).

- [ ] **Step 1: the test.** `runner()` is `ExtractionPipelineAcceptanceTest`'s (Postgres, MinIO, the bucket, its
  `StubbedProviders` OCR and `LlmClient` stubs) with `REPLY` replaced by the synthetic NOW report below.

```java
package com.elioo.baymax.web.adapter.in.ui;

/**
 * The PO's request, as a test: a family uses the whole web app with WhatsApp switched off. Sign in with a code from the
 * log fallback (the supported laptop path), see the tabs, add a paper, watch it read, read the stored NOW banner, use
 * পরিবার, delete the paper. Each step asserts its element. The route sweep at the end is derived from the router.
 */
@Testcontainers
class WebOnlyJourneyAcceptanceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    @Container
    static final MinIOContainer MINIO = com.elioo.baymax.storage.TestImages.minioContainer();

    /** Synthetic (DR-36): Potassium 11.0 against 3.5–5.1 is ≥ 2× high, the stopgap's NOW. */
    static final String NOW_REPLY = """
            {"document_type":"lab_report","document_date":"2026-10-12","facility":"Shapla Diagnostics",
             "values":[{"name":"Potassium","value":"11.0","unit":"mmol/L","ref_low":"3.5","ref_high":"5.1",
                        "source_span":{"page":1,"start":0,"end":9}}],
             "medicines":[],"follow_up":[],
             "clinical_context":{"chief_complaint":[],"history":[],"examination":[],"diagnosis":[],
                                 "investigations_advised":[],"advice":[],"referral":null},
             "confidence":{"overall":0.95,"values":0.95,"medicines":1.0,"follow_up":1.0,"clinical_context":1.0}}
            """;

    private static final String NUMBER = "+8801700000731";
    private final Set<String> visited = new TreeSet<>();
    private ListAppender<ILoggingEvent> codes;

    /** Routes a family never reaches by browsing the app, each with why it is not a step of this journey. */
    static final Map<String, String> NOT_IN_JOURNEY = Map.ofEntries(
            Map.entry("GET /assets/{file}", "fetched by the browser; FirstLoadBudgetTest (D1) and AssetListTest cover every asset"),
            Map.entry("GET /lang/{code}", "the language toggle; BaymaxWebUiRouterTest"),
            Map.entry("GET /baymax", "a permanent redirect for old links; BaymaxWebUiRouterTest"),
            Map.entry("GET /baymax/{*rest}", "a permanent redirect for old links; BaymaxWebUiRouterTest"),
            Map.entry("GET /app", "the same page as GET /app/"),
            Map.entry("GET /app/nudges/opt-out", "reached only from a link inside a WhatsApp reminder (HMAC token)"),
            Map.entry("POST /app/nudges/opt-out", "reached only from a link inside a WhatsApp reminder (HMAC token)"),
            Map.entry("POST /app/patients/{id}/nudges/opt-out", "stops real reminders; FamilyViewTest; tried locally"),
            Map.entry("POST /app/patients/{id}/upload", "the legacy upload for cached pages; AddReportSubmitTest"),
            Map.entry("GET /app/patients/{id}", "the pre-tab timeline URL; C1 turns it into a redirect"),
            Map.entry("GET /app/patients/{id}/profile", "the pre-tab profile URL; redirects to /app/family (A6)"),
            Map.entry("GET /app/documents/{id}/image", "a crop, fetched by the browser from a page"),
            Map.entry("GET /app/documents/{id}/page/{n}", "a page image, fetched by the browser from a page"));

    private ReactiveWebApplicationContextRunner runner() {
        return ExtractionPipelineAcceptanceTest.runnerFor(POSTGRES, MINIO, NOW_REPLY)
                .withPropertyValues("baymax.wa.enabled=false", "baymax.auth.web-mints-code=true",
                        "baymax.auth.hmac-secret=journey-secret", "baymax.auth.cookie-secure=false",
                        "baymax.free.max-docs-per-month=50");
    }

    @BeforeEach
    void listen() {
        Logger delivery = (Logger) LoggerFactory.getLogger("com.elioo.baymax.web.adapter.out.delivery");
        delivery.setLevel(Level.DEBUG);   // the log fallback writes the code at DEBUG only (DR-39)
        codes = new ListAppender<>();
        codes.start();
        delivery.addAppender(codes);
    }

    @AfterEach
    void stop() {
        ((Logger) LoggerFactory.getLogger("com.elioo.baymax.web.adapter.out.delivery")).detachAppender(codes);
    }

    @Test
    void aFamilyDoesEverythingOnTheWebWithWhatsAppSwitchedOff() {
        runner().run(context -> {
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            Instant t = Instant.now();
            FamilyAccount family = records.createFamily(new FamilyAccount(null, NUMBER, "Rahim", FamilyAccount.Plan.FREE, t, t)).block();
            PatientProfile ma = records.createPatient(new PatientProfile(null, family.id(), "Rokeya", 68, PatientProfile.Sex.FEMALE, List.of(), t, t)).block();
            WebTestClient web = WebTestClient.bindToApplicationContext(context).configureClient().responseTimeout(Duration.ofSeconds(30)).build();
            UiCopy copy = context.getBean(UiCopy.class);
            assertThat(context.getBeanNamesForType(WaMessagingPort.class)).as("WhatsApp is off: no messaging bean exists").isEmpty();

            // 1. sign in: the number page, then the code from the log fallback, then the session
            String login = cookie(step(web.post().uri("/app/login").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData("whatsapp_number", "01700000731").with("confirmed", "1")), "POST /app/login")
                    .expectStatus().isSeeOther().expectHeader().valueEquals(HttpHeaders.LOCATION, "/app/verify"), "baymax_login");
            String code = codes.list.stream().map(ILoggingEvent::getFormattedMessage).filter(m -> m.contains("otp fallback"))
                    .map(m -> m.replaceAll(".*code=([0-9]{6}).*", "$1")).findFirst().orElseThrow();
            assertThat(step(web.get().uri("/app/verify").cookie("baymax_login", login), "GET /app/verify").expectStatus().isOk()
                    .expectBody(String.class).returnResult().getResponseBody()).contains("name=\"code\"");
            String session = cookie(step(web.post().uri("/app/verify").cookie("baymax_login", login)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(BodyInserters.fromFormData("code", code)), "POST /app/verify")
                    .expectStatus().isSeeOther().expectHeader().valueEquals(HttpHeaders.LOCATION, "/app/home"), "baymax_session");

            // 2. the three tabs; চ্যাট's composer says chat is coming and cannot send
            String chat = get(web, session, "/app/home", "GET /app/home");
            assertThat(AppShellRoutesTest.between(chat, "<div class=\"composer\"", "</div>")).contains(copy.t(Lang.BN, "chat.soon")).doesNotContain("<form");
            assertThat(AppShellRoutesTest.between(chat, "<nav class=\"tabs\"", "</nav>")).contains(">কাগজপত্র<").contains(">পরিবার<");
            assertThat(get(web, session, "/app/timeline", "GET /app/timeline")).contains("href=\"/app/add?patient=" + ma.id());

            // 3. add a paper: the sheet, then the post, then the reading page
            assertThat(get(web, session, "/app/add?patient=" + ma.id(), "GET /app/add"))
                    .containsPattern("name=\"patient\" value=\"" + ma.id() + "\" required checked>");
            MultipartBodyBuilder body = new MultipartBodyBuilder();
            body.part("patient", ma.id().toString());
            body.part("file", TryDocuments.jpeg(400, 200)).filename("page-1.jpg");
            String documentPath = step(web.post().uri("/app/add").cookie("baymax_session", session)
                    .body(BodyInserters.fromMultipartData(body.build())), "POST /app/add")
                    .expectStatus().isSeeOther().returnResult(Void.class).getResponseHeaders().getFirst(HttpHeaders.LOCATION);
            assertThat(documentPath).matches("/app/documents/[0-9a-f-]{36}");
            String first = get(web, session, documentPath, "GET /app/documents/{id}");
            assertThat(first).containsAnyOf("id=\"progress\"", "id=\"urgency\"");   // still reading, or already read

            // 4. the poll, until DONE (status only, never an urgency)
            String status = "";
            for (int i = 0; i < 60 && !status.equals("DONE"); i++) {
                String json = step(web.get().uri(documentPath + "/status").cookie("baymax_session", session), "GET /app/documents/{id}/status")
                        .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
                assertThat(json).doesNotContain("urgency");
                status = json.replaceAll(".*\"status\":\"([A-Z_]+)\".*", "$1");
                if (!status.equals("DONE")) {
                    Thread.sleep(500);
                }
            }
            assertThat(status).as("the paper was read").isEqualTo("DONE");

            // 5. the result: the stored NOW line first, the patient named
            String result = get(web, session, documentPath, "GET /app/documents/{id}");
            assertThat(ResultFixtures.section(result, "urgency")).contains(copy.urgency(Lang.BN, Urgency.NOW, false));
            assertThat(result).contains("<span class=\"pchip\">Rokeya · ৬৮ বছর</span>");

            // 6. পরিবার: the account, the flags saved
            String familyTab = get(web, session, "/app/family", "GET /app/family");
            assertThat(familyTab).contains(PhoneDisplay.full(NUMBER)).contains("action=\"/app/logout\"");
            step(web.post().uri("/app/patients/" + ma.id() + "/profile").cookie("baymax_session", session)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(BodyInserters.fromFormData("flag", "diabetes")),
                    "POST /app/patients/{id}/profile").expectStatus().isSeeOther();
            assertThat(get(web, session, "/app/family?patient=" + ma.id() + "&saved=1", "GET /app/family")).contains(copy.t(Lang.BN, "profile.saved"));

            // 7. delete the paper: the confirmation, then gone
            assertThat(get(web, session, documentPath + "/delete", "GET /app/documents/{id}/delete")).contains("method=\"post\"");
            step(web.post().uri(documentPath + "/delete").cookie("baymax_session", session), "POST /app/documents/{id}/delete")
                    .expectStatus().is2xxSuccessful();
            web.get().uri(documentPath).cookie("baymax_session", session).exchange().expectStatus().isNotFound();

            // 8. sign out, and the landing
            step(web.post().uri("/app/logout").cookie("baymax_session", session), "POST /app/logout").expectStatus().isSeeOther();
            assertThat(step(web.get().uri("/"), "GET /").expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody()).contains("href=\"/app\"");
            assertThat(step(web.get().uri("/app/"), "GET /app/").expectStatus().isOk().expectBody(String.class).returnResult()
                    .getResponseBody()).contains("name=\"whatsapp_number\"");

            // 9. every family-facing route is visited or has a written reason (derived from the router)
            List<RouteTable.Route> routes = RouteTable.of(context.getBean("baymaxWebUiRoutes", RouterFunction.class));
            assertThat(routes).hasSizeGreaterThanOrEqualTo(25);
            List<String> missed = routes.stream().map(RouteTable.Route::key)
                    .filter(k -> !visited.contains(k) && !NOT_IN_JOURNEY.containsKey(k)).toList();
            assertThat(missed).as("routes neither walked by the journey nor listed with a reason").isEmpty();
            assertThat(NOT_IN_JOURNEY.keySet()).as("a reason for a route that no longer exists").allSatisfy(k ->
                    assertThat(routes.stream().map(RouteTable.Route::key)).contains(k));
        });
    }

    private WebTestClient.ResponseSpec step(WebTestClient.RequestHeadersSpec<?> request, String routeKey) {
        visited.add(routeKey);
        return request.exchange();
    }

    private String get(WebTestClient web, String session, String uri, String routeKey) {
        return step(web.get().uri(uri).cookie("baymax_session", session), routeKey).expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
    }

    private static String cookie(WebTestClient.ResponseSpec response, String name) {
        return response.returnResult(Void.class).getResponseCookies().getFirst(name).getValue();
    }
}
```

  `ExtractionPipelineAcceptanceTest.runnerFor(postgres, minio, reply)` is a new static factory extracted from that test's
  `runner()` (same properties; `StubbedProviders` takes the reply); its own tests call it with their `REPLY`, so they
  are unchanged. The step keys are route patterns, so the sweep compares like with like; a step that reaches a route
  under another key would leave that route "missed" and fail.
- [ ] **Step 2: run, and the two replays.** `./gradlew :baymax:test --tests '*WebOnlyJourneyAcceptanceTest'` → green.
  Replay 1 (coverage): add `.GET(BASE + "/x", r -> ServerResponse.ok().build())` to `AppTabs.routes` → red
  `routes neither walked by the journey nor listed with a reason: ["GET /app/x"]`. Replay 2 (the journey is real):
  make `UploadUiHandler.receive` redirect to `/app/timeline` → red at step 3,
  `Expecting actual: "/app/timeline" to match pattern "/app/documents/[0-9a-f-]{36}"`. Revert both.

### Task J1.2: the same journey with WhatsApp on — only the code and the paper's own explanation are sent

- [ ] **Step 1: the test** (same class; `runner()` with `baymax.wa.enabled=true`, the WhatsApp properties
  `OtpHourlyCapAgainstPostgresTest` sets, `BAYMAX_WA_ALLOWLIST` = `NUMBER`, and
  `.withBean(WaMessagingPort.class, () -> wa)` where `wa = mock(WaMessagingPort.class)` answers every `sendText` with
  `WaFixtures.wamid("8801700000731", n)`):

```java
    @Test
    void withWhatsAppOnTheOnlyMessagesAreTheCodeAndTheUploadedPapersOwnExplanation() {
        WaMessagingPort wa = mock(WaMessagingPort.class);
        AtomicInteger n = new AtomicInteger();
        when(wa.sendText(anyString(), anyString())).thenAnswer(i -> Mono.just(WaFixtures.wamid("8801700000731", n.incrementAndGet())));
        whatsappRunner(wa).run(context -> {
            // the family wrote to Medioo on WhatsApp within the window (PR 12's free path), then signs in on the web
            Family f = familyWithMa(context);
            context.getBean(WebAuthUseCase.class).noteInbound("8801700000731", Instant.now()).block();
            WebTestClient web = WebTestClient.bindToApplicationContext(context).build();
            String session = signInWithTheCodeWhatsAppGot(web, wa);           // asserts the first sendText is the code
            String documentPath = uploadAndWaitUntilDone(web, session, f.ma());  // the steps of J1.1, 3–4
            awaitExplanationRow(context, documentPath);

            // derived from the port: every call of any WaMessagingPort method, not a list of the ones we expect
            List<Invocation> calls = List.copyOf(Mockito.mockingDetails(wa).getInvocations());
            assertThat(calls).as("WhatsApp calls made").isNotEmpty();
            assertThat(calls).allSatisfy(c -> assertThat(c.getMethod().getName()).as("no other WhatsApp operation").isEqualTo("sendText"));
            List<String> bodies = calls.stream().map(c -> (String) c.getArgument(1)).toList();
            assertThat(bodies.get(0)).contains("Medioo-তে ঢোকার কোড");
            Set<String> delivered = deliveredBodiesOf(context, documentPath);   // outbound_message rows of THIS document
            assertThat(bodies.subList(1, bodies.size())).as("after the code, only this paper's own delivered messages")
                    .allSatisfy(b -> assertThat(delivered).contains(b));
        });
    }
```

  `signInWithTheCodeWhatsAppGot`, `uploadAndWaitUntilDone`, `awaitExplanationRow` (polls `outbound_message` for the
  document, 30 s max) and `deliveredBodiesOf` (`SELECT body FROM baymax.outbound_message WHERE document_id = ? AND
  delivery_status = 'sent'`) are private helpers built from J1.1's steps.
- [ ] **Step 2: replay (mutant):** in `CompositeOtpDelivery.deliver`, send the code twice → red
  `after the code, only this paper's own delivered messages … Expecting … to contain "…Medioo-তে ঢোকার কোড…"` (a second
  message that is not one of this paper's delivered rows). Revert.
- [ ] **Step 3:** standard pre-merge. Commit `test(baymax): the whole family journey on the web, with WhatsApp off and on`.

### Try it (A13) — prod-web (the Milestone A try)

- [ ] On the phone, with WhatsApp used only to fetch the code: sign in → চ্যাট → কাগজপত্র → "কাগজ পাঠান" → t7 → the
  reading page → the result opens by itself with the red "এখনই ডাক্তারের কাছে যান। দেরি করবেন না।" first → পরিবার →
  tick a flag and save → back to কাগজপত্র → open the paper → "কাগজটা মুছুন" → confirm → it is gone → সাইন আউট.
- [ ] Note in the build report whether the explanation also arrived on WhatsApp (today's behaviour; the opt-in is
  Phase 4, D32).

---

## PR B1 — F2: one verdict per shown value, from the rule that set the urgency, never softened (V19)

Branch `feat/baymax-value-verdicts`. Spec §2 F2, D35. Today the banner comes from `UrgencyService.assess` and the rows
from `criticalKind` read again elsewhere (`ExplanationService.standout`/`kindOf`), so a row could read "inside" under a
NOW banner. Here each shown observation gets exactly one `ValueVerdict(observationId, kind, level, side, reason)`,
produced **in the same branch** that raised (or did not raise) the document; a raising row's reason is the very string
it raised with. INSIDE needs `insideByCode` (PR 2); every shape the code cannot place is UNKNOWN; OUTSIDE under a
never-escalating signed-off threshold (DR-17) stays OUTSIDE at ROUTINE. The verdicts are written by the F1 writer **in
the same transaction** as the document's urgency, joined on observation id (never on name), and a trigger stops any
softening. Reason prefixes become an enum (`UrgencyReason`) so the result-now blocks (B3) can be derived from it.

**The rule.** Severity INSIDE < UNKNOWN < OUTSIDE < CRITICAL (UNKNOWN outranks INSIDE: it withholds reassurance). A raised
branch gives CRITICAL at NOW and OUTSIDE at THIS_WEEK. An unraised branch gives INSIDE only if `insideByCode`, OUTSIDE if
`criticalKind != NONE` under a never-escalating threshold, else UNKNOWN; reasons `value_inside:<m>|<rule>`,
`value_outside_no_escalation:<m>|threshold:<src>`, `value_unknown:<m>|<rule>` (verdict reasons, not urgency reasons).
Unverified values (DR-28) still raise and emit no row. A row with no stored verdict yet renders UNKNOWN, never INSIDE.

### Task 14.1: verdicts in `assess()`, and the reason prefixes as one enum

**Files:** create `outbound/domain/ValueVerdict.java`, `outbound/domain/UrgencyReason.java`, test
`outbound/application/service/ValueVerdictTest.java`; modify `UrgencyAssessment` (+`List<ValueVerdict> values`; the
`(level, reasons)` constructor delegates with `List.of()`; `raise` keeps `values`; `withValues`), `UrgencyService.java:58-124`
(the loop; every `raise` site uses the enum; `insideByCode` and `number` become `public static`), `MarkerThreshold`
(+`side(double)`), `UrgencyAssessmentTest`.

- [ ] **Step 1: failing tests.**

```java
/** F2: a row's verdict comes from the branch that set the banner, and "inside" must be proven by code. */
class ValueVerdictTest {

    private static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");
    private final BaymaxProperties properties = new BaymaxProperties();
    private final UrgencyService service = new UrgencyService(properties, Clock.fixed(NOW, ZoneOffset.UTC));

    static Map<String, Object> obs(String name, String value, String low, String high, String unit) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", UUID.randomUUID());
        m.put("name", name);
        m.put("canonical_name", name);
        m.put("value", value);
        m.put("unit", unit);
        if (low != null) { m.put("ref_low", low); }
        if (high != null) { m.put("ref_high", high); }
        return m;
    }

    private ValueVerdict only(Map<String, Object> o) {
        return service.assess(UrgencyServiceTest.facts(List.of(o), List.of(), Map.of(), "{}")).values().get(0);
    }

    /** Spec F2 (b): every one of these is UNKNOWN — never INSIDE, never teal. */
    @ParameterizedTest(name = "''{0}'' against ''{1}''–''{2}'' is UNKNOWN")
    @CsvSource(delimiter = '|', nullValues = "null", value = {
            "> 89     | 60   | 120",
            "130 / 80 | 90   | 140",
            "1,23     | 0.5  | 1.5",
            "Positive | null | null",
            "250      | null | 100",
            "250      | null | <100",
            "250      | null | null",
            "5.0      | 6.0  | 4.0"})     // inverted printed range
    void aValueCodeCannotPlaceIsUnknownNeverInside(String value, String low, String high) {
        ValueVerdict v = only(obs("ldl", value, low, high, "mg/dL"));
        assertThat(v.kind()).isEqualTo(ValueVerdict.Kind.UNKNOWN);
        assertThat(v.level()).isEqualTo(Urgency.ROUTINE);
        assertThat(v.reason()).startsWith("value_unknown:ldl|");
    }

    /** The other direction: a parsed value between two parsed bounds IS inside. */
    @Test
    void aParsedValueBetweenParsedBoundsIsInside() {
        ValueVerdict v = only(obs("creatinine", "1.0", "0.7", "1.3", "mg/dL"));
        assertThat(v.kind()).isEqualTo(ValueVerdict.Kind.INSIDE);
        assertThat(v.reason()).isEqualTo("value_inside:creatinine|stopgap");
    }

    /**
     * Assert the path, not the outcome (CLAUDE.md "when two rules can produce the same verdict"): every raising row's
     * reason is one of the document's reasons, and a threshold row names its source.
     */
    @Test
    void everyRaisingRowsReasonIsADocumentReasonAndAThresholdRowSaysWhichRule() {
        properties.getOutbound().getMarkerThresholds().add(new MarkerThreshold("potassium", "mmol/L", 2.5, 6.0, 3.5, 5.1,
                false, MarkerThreshold.Direction.NORMAL, MarkerThreshold.Status.ACTIVE, false, "test-signed"));
        Map<String, Object> potassium = obs("potassium", "6.4", "3.5", "5.1", "mmol/L");   // stopgap alone: 1.25x → OUTSIDE
        Map<String, Object> hba1c = obs("hba1c", "6.5", "4.0", "5.6", "%");

        UrgencyAssessment a = service.assess(UrgencyServiceTest.facts(List.of(potassium, hba1c), List.of(), Map.of(), "{}"));

        assertThat(a.values()).filteredOn(v -> v.level() != Urgency.ROUTINE).hasSize(2)
                .allSatisfy(v -> assertThat(a.reasons()).contains(v.reason()));
        assertThat(verdictOf(a, potassium)).satisfies(v -> {
            assertThat(v.kind()).isEqualTo(ValueVerdict.Kind.CRITICAL);
            assertThat(v.side()).isEqualTo(ValueVerdict.Side.HIGH);
            assertThat(v.reason()).isEqualTo("value_critical:potassium|threshold:test-signed");
        });
        assertThat(verdictOf(a, hba1c)).satisfies(v -> {
            assertThat(v.kind()).isEqualTo(ValueVerdict.Kind.OUTSIDE);
            assertThat(v.reason()).isEqualTo("value_outside_range:hba1c|stopgap");
        });
    }

    /** DR-17: a never-escalating marker outside its printed range is OUTSIDE at ROUTINE, never INSIDE. */
    @Test
    void aNeverEscalatingMarkerOutsideItsPrintedRangeIsOutsideNotInside() {
        properties.getOutbound().getMarkerThresholds().add(new MarkerThreshold("ldl", "mg/dL", null, null, null, null,
                true, MarkerThreshold.Direction.NORMAL, MarkerThreshold.Status.ACTIVE, false, "DR-17"));
        ValueVerdict v = only(obs("ldl", "250", "0", "100", "mg/dL"));
        assertThat(v.kind()).isEqualTo(ValueVerdict.Kind.OUTSIDE);
        assertThat(v.level()).isEqualTo(Urgency.ROUTINE);
        assertThat(v.reason()).isEqualTo("value_outside_no_escalation:ldl|threshold:DR-17");
    }


    /** DR-28: an unverified value raises the document and emits no row — there is no observation to hang it on. */
    @Test
    void anUnverifiedValueRaisesButEmitsNoRow() {
        Map<String, Object> unverified = obs("potassium", "6.1", "3.5", "5.1", "mmol/L");
        unverified.remove("id");
        var facts = UrgencyServiceTest.factsWithUnverified(List.of(), List.of(unverified));   // UnverifiedValuesTest's fixture shape
        UrgencyAssessment a = service.assess(facts);
        assertThat(a.level()).isEqualTo(Urgency.THIS_WEEK);
        assertThat(a.reasons()).containsExactly("unverified_value_outside_range:potassium");
        assertThat(a.values()).isEmpty();
    }

    /** F2 (c): "within the normal range" holds exactly when every verdict is INSIDE — over every WithinNormalRangeClaimTest fixture. */
    @Test
    void theNormalRangeClaimHoldsOnlyWhenEveryVerdictIsInside() {
        List<WithinNormalRangeClaimTest.Fixture> fixtures = WithinNormalRangeClaimTest.fixtures();
        assertThat(fixtures).as("the claim's own fixtures").hasSizeGreaterThanOrEqualTo(8);
        for (WithinNormalRangeClaimTest.Fixture f : fixtures) {
            UrgencyAssessment a = service.assess(f.facts());
            boolean allInside = !a.values().isEmpty() && f.facts().document().unverified().total() == 0
                    && a.values().stream().allMatch(v -> v.kind() == ValueVerdict.Kind.INSIDE) && !f.modelFlagged();
            assertThat(ExplanationService.canSayWithinNormalRange(f.facts(), a)).as(f.name()).isEqualTo(allInside);
        }
    }

    /** UrgencyReason: a raise with a prefix the enum does not name is refused, so B3's derived blocks cannot miss one. */
    @Test
    void aReasonMustCarryAKnownPrefix() {
        assertThatThrownBy(() -> UrgencyAssessment.routine().raise(Urgency.NOW, "made_up_reason:x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("made_up_reason");
        assertThat(UrgencyAssessment.routine().raise(Urgency.NOW, "value_critical:k|stopgap").reasons()).containsExactly("value_critical:k|stopgap");
    }

    private static ValueVerdict verdictOf(UrgencyAssessment a, Map<String, Object> o) {
        return a.values().stream().filter(v -> v.observationId().equals(o.get("id"))).findFirst().orElseThrow();
    }
}
```

  (`WithinNormalRangeClaimTest.fixtures()` exposes the fixtures that test already builds as a static list of
  `Fixture(name, facts, modelFlagged)`; `ExplanationService.canSayWithinNormalRange(facts, assessment)` is the PR 2 rule,
  now package-visible for this test. `UrgencyServiceTest.factsWithUnverified` builds facts with the unverified values
  the way `UnverifiedValuesTest` does.)
- [ ] **Step 2: red, on the defect.** First implement `values()` the way rows are judged today — `criticalKind` mapped
  straight across, NONE becoming INSIDE (what `canSayWithinNormalRange` did before PR 2). The reds must be
  `expected: UNKNOWN but was: INSIDE` for `'250' | null | 100`, and `expected: CRITICAL but was: OUTSIDE` for potassium
  under the live threshold: the disagreement F2 exists to remove. `aReasonMustCarryAKnownPrefix` fails
  `Expecting code to raise a throwable`. Paste the three.
- [ ] **Step 3: implement.**

```java
package com.elioo.baymax.outbound.domain;

/**
 * F2: one shown value's verdict, from the branch of UrgencyService.assess that raised (or did not raise) the document.
 * Kinds are in severity order; a stored verdict only moves up that order (V19 trigger).
 *
 * @param reason for a raising row, the exact urgency reason it raised; otherwise value_inside / value_unknown /
 *               value_outside_no_escalation. Always ends in |stopgap or |threshold:<source>.
 */
public record ValueVerdict(UUID observationId, Kind kind, Urgency level, Side side, String reason) {
    public enum Kind { INSIDE, UNKNOWN, OUTSIDE, CRITICAL;
        public String dbValue() { return name().toLowerCase(Locale.ROOT); } }
    public enum Side { HIGH, LOW;
        public String dbValue() { return name().toLowerCase(Locale.ROOT); } }
}
```

```java
package com.elioo.baymax.outbound.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * Every reason prefix the urgency rule can raise with (`<prefix>[:<marker>][|stopgap|threshold:<src>]`). Pages derive
 * their reason blocks from this enum (B3), and UrgencyAssessment.raise refuses a prefix that is not here.
 */
public enum UrgencyReason {
    VALUE_CRITICAL("value_critical"), VALUE_OUTSIDE_RANGE("value_outside_range"),
    UNVERIFIED_VALUE_CRITICAL("unverified_value_critical"), UNVERIFIED_VALUE_OUTSIDE_RANGE("unverified_value_outside_range"),
    FOLLOW_UP_PAST_DUE("follow_up_past_due"), DIAGNOSIS_PRESENT("diagnosis_present"), TEXT_EMERGENCY_PHRASE("text_emergency_phrase");

    private final String prefix;

    UrgencyReason(String prefix) {
        this.prefix = prefix;
    }

    public String prefix() {
        return prefix;
    }

    /** "value_critical:potassium|stopgap" for {@code of("potassium", "|stopgap")}. */
    public String of(String marker, String rule) {
        return prefix + (marker == null ? "" : ":" + marker) + (rule == null ? "" : rule);
    }

    public static Optional<UrgencyReason> ofReason(String reason) {
        if (reason == null) {
            return Optional.empty();
        }
        String p = reason.split("[:|]", 2)[0];
        return Arrays.stream(values()).filter(r -> r.prefix.equals(p)).findFirst();
    }
}
```

  `UrgencyAssessment.raise(to, reason)` begins with
  `UrgencyReason.ofReason(reason).orElseThrow(() -> new IllegalArgumentException("unknown urgency reason prefix: " + reason));`
  (the constructor does not check: stored legacy reasons are read back through `StoredUrgency.asAssessment`). The loop
  of `UrgencyService.assess` (`:62-77`) becomes:

```java
List<ValueVerdict> verdicts = new ArrayList<>();
for (Map<String, Object> v : facts.values()) {
    String name = String.valueOf(v.getOrDefault("canonical_name", v.get("name"))).toLowerCase(Locale.ROOT);
    UUID id = v.get("id") instanceof UUID u ? u : null;
    Optional<Verdict> perMarker = perMarkerVerdict(v);
    if (perMarker.isPresent()) {
        Verdict verdict = perMarker.get();
        String rule = "|threshold:" + verdict.threshold().source();
        if (verdict.level() != Urgency.ROUTINE) {
            String reason = verdict.reasonFor(name);         // raised once, by the loop below, from the row itself
            verdicts.add(new ValueVerdict(id, verdict.level() == Urgency.NOW ? Kind.CRITICAL : Kind.OUTSIDE,
                    verdict.level(), verdict.side(), reason));
        } else {
            verdicts.add(unraised(id, v, name, rule, text));
        }
        continue;                                    // a signed-off threshold replaces the stopgap
    }
    switch (criticalKind(v, text)) {
        case CRITICAL_HIGH -> verdicts.add(raised(id, Urgency.NOW, Side.HIGH, UrgencyReason.VALUE_CRITICAL.of(name, STOPGAP)));
        case CRITICAL_LOW -> verdicts.add(raised(id, Urgency.NOW, Side.LOW, UrgencyReason.VALUE_CRITICAL.of(name, STOPGAP)));
        case OUTSIDE -> verdicts.add(raised(id, Urgency.THIS_WEEK, printedSide(v), UrgencyReason.VALUE_OUTSIDE_RANGE.of(name, STOPGAP)));
        case NONE -> verdicts.add(unraised(id, v, name, STOPGAP, text));
    }
}
for (ValueVerdict r : verdicts) {
    if (r.level() != Urgency.ROUTINE) {
        a = a.raise(r.level(), r.reason());          // the row and the banner share one string, by construction
    }
}
// … unverified values, follow-ups, diagnosis, emergency phrase exactly as today (:79-122) …
return a.withValues(verdicts);
```

  The other raise sites use the enum the same way (`UrgencyReason.UNVERIFIED_VALUE_CRITICAL.of(name, null)`,
  `UrgencyReason.FOLLOW_UP_PAST_DUE.of(null, null)`, …) and `Verdict.reasonFor` builds `VALUE_CRITICAL`/`VALUE_OUTSIDE_RANGE`
  with `"|threshold:" + source`. `unraised` returns INSIDE if `insideByCode(v)`, OUTSIDE (side from the printed range) if
  `criticalKind != NONE`, else UNKNOWN. `MarkerThreshold.side(double value)` returns HIGH when `nowHigh` or `weekHigh` is
  reached and LOW otherwise, honouring `direction`. `ExplanationService.standout`/`kindOf` (`:411-433`) read
  `assessed.values()` by observation id (CRITICAL+HIGH → `line.value_critical_high`, CRITICAL+LOW → `…_low`, OUTSIDE →
  `line.value_outside_range`, INSIDE/UNKNOWN → none): the last use of `criticalKind` outside `UrgencyService`.
- [ ] **Step 4: run** `./gradlew :baymax:test --tests '*ValueVerdictTest' --tests '*UrgencyServiceTest' --tests '*WithinNormalRangeClaimTest' --tests '*ProposedThresholdsAreInertTest' --tests '*ExplanationServiceTest' --tests '*PatientCalendarRulesAgreeTest'` → green.

### Task 14.2: V19, the never-softens trigger, one transaction — on Testcontainers

**Files:** create `V19__observation_value_verdict.sql`, test `outbound/adapter/out/persistence/ValueVerdictNeverSoftensTest.java`;
modify `PostgresDocumentUrgencyAdapter` (the document UPDATE and the verdict UPDATEs inside one `TransactionalOperator`),
`StoredUrgency` (+`values`, last; the 4-argument constructor delegates with `List.of()`), `ObservationEntity`
(+`verdict`, `verdictLevel`, `verdictSide`, `verdictReason`, each `@ReadOnlyProperty`; `toView` adds `id`, `verdict`,
`verdict_level`, `verdict_side`, `verdict_reason`), `needingAssessment` (+verdict NULL), `DocumentUrgencyNeverLowersTest`
(`aSweepConverges` gains a document with observations).

- [ ] **Step 1: failing tests.**

```java
package com.elioo.baymax.outbound.adapter.out.persistence;

/** F2 on real Postgres: verdicts never soften, are joined on observation id, and are written with the urgency or not at all. */
@Testcontainers
class ValueVerdictNeverSoftensTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final Instant T = Instant.parse("2026-10-20T08:00:00Z");

    record Fixture(UUID doc, List<UUID> observations) {
        UUID obs(int i) {
            return observations.get(i);
        }
    }

    /** A DONE document (DocumentUrgencyNeverLowersTest.doneDocument) with one observation per "name|value|unit" row, in order. */
    private Fixture documentWithObservations(ApplicationContext context, String... rows) throws Exception {
        UUID doc = DocumentUrgencyNeverLowersTest.doneDocument(context);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < rows.length; i++) {
            String[] r = rows[i].split("\\|");
            UUID id = UUID.randomUUID();
            sql("INSERT INTO baymax.observation (id, patient_id, document_id, name, canonical_name, value, unit, crop_key, observed_at) "
                    + "SELECT '" + id + "', patient_id, id, '" + r[0] + "', '" + r[0].toLowerCase(Locale.ROOT) + "', '" + r[1] + "', '"
                    + r[2] + "', 'k/crop-" + i + ".jpg', now() FROM baymax.document WHERE id = '" + doc + "'");
            ids.add(id);
        }
        return new Fixture(doc, List.copyOf(ids));
    }

    private static UrgencyAssessment assessment(Urgency level, ValueVerdict... verdicts) {
        UrgencyAssessment a = UrgencyAssessment.routine();
        for (ValueVerdict v : verdicts) {
            if (v.level() != Urgency.ROUTINE) {
                a = a.raise(v.level(), v.reason());
            }
        }
        return a.withValues(List.of(verdicts));
    }

    private static ValueVerdict verdict(UUID obs, ValueVerdict.Kind kind, Urgency level, String reason) {
        return new ValueVerdict(obs, kind, level, kind == ValueVerdict.Kind.INSIDE || kind == ValueVerdict.Kind.UNKNOWN ? null : ValueVerdict.Side.HIGH, reason);
    }

/** CRITICAL stays CRITICAL when a later assessment says INSIDE; the banner stays NOW too. */
@Test
void aLowerReassessmentChangesNeitherTheRowNorTheBanner() {
    runner().run(context -> {
        Fixture f = documentWithObservations(context, "Potassium|6.4|mmol/L");
        DocumentUrgencyPort port = context.getBean(DocumentUrgencyPort.class);
        port.raise(f.doc(), assessment(Urgency.NOW, verdict(f.obs(0), Kind.CRITICAL, Urgency.NOW, "value_critical:potassium|stopgap")), T).block();

        StoredUrgency after = port.raise(f.doc(), assessment(Urgency.ROUTINE,
                verdict(f.obs(0), Kind.INSIDE, Urgency.ROUTINE, "value_inside:potassium|threshold:x")), T.plusSeconds(5)).block();

        assertThat(after.level()).isEqualTo(Urgency.NOW);
        assertThat(after.values()).singleElement().satisfies(v -> {
            assertThat(v.kind()).isEqualTo(Kind.CRITICAL);
            assertThat(v.reason()).isEqualTo("value_critical:potassium|stopgap");
        });
    });
}

/** The other direction: INSIDE then UNKNOWN raises the row (UNKNOWN withholds reassurance), and the banner is untouched. */
@Test
void insideThenUnknownBecomesUnknown() {
    runner().run(context -> {
        Fixture f = documentWithObservations(context, "LDL|90|mg/dL");
        DocumentUrgencyPort port = context.getBean(DocumentUrgencyPort.class);
        port.raise(f.doc(), assessment(Urgency.ROUTINE, verdict(f.obs(0), Kind.INSIDE, Urgency.ROUTINE, "value_inside:ldl|stopgap")), T).block();
        StoredUrgency after = port.raise(f.doc(), assessment(Urgency.ROUTINE,
                verdict(f.obs(0), Kind.UNKNOWN, Urgency.ROUTINE, "value_unknown:ldl|stopgap")), T.plusSeconds(5)).block();
        assertThat(after.values()).singleElement().satisfies(v -> {
            assertThat(v.kind()).isEqualTo(Kind.UNKNOWN);
            assertThat(v.reason()).isEqualTo("value_unknown:ldl|stopgap");
        });
        assertThat(after.level()).isEqualTo(Urgency.ROUTINE);
    });
}

/** The pairing lesson (CLAUDE.md): two rows with the same name are two rows. Joined on observation id, never name. */
@Test
void twoObservationsWithTheSameNameKeepTheirOwnVerdicts() {
    runner().run(context -> {
        Fixture f = documentWithObservations(context, "Neutrophil|2.25|10^3/µL", "Neutrophil|41.9|%");
        context.getBean(DocumentUrgencyPort.class).raise(f.doc(), assessment(Urgency.THIS_WEEK,
                verdict(f.obs(0), Kind.INSIDE, Urgency.ROUTINE, "value_inside:neutrophil|stopgap"),
                verdict(f.obs(1), Kind.OUTSIDE, Urgency.THIS_WEEK, "value_outside_range:neutrophil|stopgap")), T).block();
        assertThat(scalar("SELECT verdict FROM baymax.observation WHERE id = '" + f.obs(0) + "'")).isEqualTo("inside");
        assertThat(scalar("SELECT verdict FROM baymax.observation WHERE id = '" + f.obs(1) + "'")).isEqualTo("outside");
    });
}

@Test
void theDatabaseRefusesASoftenedOrMalformedVerdict() {
    runner().run(context -> {
        Fixture f = documentWithObservations(context, "Potassium|6.4|mmol/L", "Hb|13|g/dL");
        sql("UPDATE baymax.observation SET verdict='critical', verdict_level='now', verdict_side='high', "
                + "verdict_reason='value_critical:potassium|stopgap' WHERE id = '" + f.obs(0) + "'");
        assertThatThrownBy(() -> sql("UPDATE baymax.observation SET verdict='inside', verdict_level='routine', "
                + "verdict_reason='value_inside:potassium|stopgap' WHERE id = '" + f.obs(0) + "'"))
                .hasMessageContaining("observation verdict may not soften (critical -> inside)");
        assertThatThrownBy(() -> sql("UPDATE baymax.observation SET verdict='bogus' WHERE id = '" + f.obs(1) + "'"))
                .hasMessageContaining("observation_verdict_check");
        assertThatThrownBy(() -> sql("UPDATE baymax.observation SET verdict='inside', verdict_level='routine', "
                + "verdict_reason='value_inside:hb' WHERE id = '" + f.obs(1) + "'"))
                .hasMessageContaining("observation_verdict_reason_names_its_rule");
        // the direction that must pass: raising a row directly
        sql("UPDATE baymax.observation SET verdict='critical', verdict_level='now', verdict_side='low', "
                + "verdict_reason='value_critical:hb|stopgap' WHERE id = '" + f.obs(1) + "'");
    });
}

/** V19's CHECKs, each by name, both directions. */
@Test
void everyVerdictConstraintRefusesItsBadWriteAndAllowsItsGoodOne() {
    runner().run(context -> {
        Fixture f = documentWithObservations(context, "Hb|13|g/dL", "K|6.4|mmol/L", "Na|140|mmol/L");
        assertThatThrownBy(() -> sql("UPDATE baymax.observation SET verdict='inside' WHERE id = '" + f.obs(0) + "'"))
                .hasMessageContaining("observation_verdict_complete");
        assertThatThrownBy(() -> sql("UPDATE baymax.observation SET verdict='inside', verdict_level='now', verdict_reason='value_inside:hb|stopgap' WHERE id = '" + f.obs(0) + "'"))
                .hasMessageContaining("observation_inside_is_routine");
        assertThatThrownBy(() -> sql("UPDATE baymax.observation SET verdict='critical', verdict_level='this_week', verdict_side='high', verdict_reason='value_critical:k|stopgap' WHERE id = '" + f.obs(1) + "'"))
                .hasMessageContaining("observation_critical_is_now");
        // the good writes
        sql("UPDATE baymax.observation SET verdict='inside', verdict_level='routine', verdict_reason='value_inside:hb|stopgap' WHERE id = '" + f.obs(0) + "'");
        sql("UPDATE baymax.observation SET verdict='critical', verdict_level='now', verdict_side='high', verdict_reason='value_critical:k|stopgap' WHERE id = '" + f.obs(1) + "'");
        sql("UPDATE baymax.observation SET verdict='unknown', verdict_level='routine', verdict_reason='value_unknown:na|stopgap' WHERE id = '" + f.obs(2) + "'");
    });
}

/** One transaction: a verdict that cannot be written (no observation id) rolls back the document's urgency too. */
@Test
void aVerdictThatCannotBeWrittenRollsBackTheDocumentsUrgency() {
    runner().run(context -> {
        Fixture f = documentWithObservations(context, "K|6.4|mmol/L");
        DocumentUrgencyPort port = context.getBean(DocumentUrgencyPort.class);
        port.raise(f.doc(), assessment(Urgency.ROUTINE, verdict(f.obs(0), Kind.INSIDE, Urgency.ROUTINE, "value_inside:k|stopgap")), T).block();
        UrgencyAssessment broken = assessment(Urgency.NOW,
                new ValueVerdict(null, Kind.CRITICAL, Urgency.NOW, ValueVerdict.Side.HIGH, "value_critical:k|stopgap"));
        assertThatThrownBy(() -> port.raise(f.doc(), broken, T.plusSeconds(5)).block())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("verdict without observation id");
        assertThat(scalar("SELECT urgency FROM baymax.document WHERE id = '" + f.doc() + "'")).isEqualTo("routine");
    });
}
// runner(), sql(), scalar(): as in DocumentUrgencyNeverLowersTest
}
```

  (`DocumentUrgencyNeverLowersTest.doneDocument` becomes `static` and package-visible for this; rows are inserted in SQL so
  the ids and names are exact.)
- [ ] **Step 2: red, each replay on its own defect (all required).**
  - V19 without the `CREATE TRIGGER` and the adapter **with** its rank guard: green (the guard alone holds) — so replay
    (a) adapter **without** its rank guard and with the trigger → `aLowerReassessment…` red
    `R2dbcDataIntegrityViolationException … observation verdict may not soften (critical -> inside)`;
    (b) trigger removed **and** no rank guard → `expected: CRITICAL but was: INSIDE`;
    (c) the adapter without the `TransactionalOperator` → `aVerdictThatCannotBeWritten…` red `expected: "routine" but was: "now"`;
    (d) the pairing join `WHERE document_id = :doc AND name = :name` → `twoObservationsWithTheSameName…` red
    `expected: "inside" but was: "outside"`.
  A missing-column error is not a replay. Paste all four.
- [ ] **Step 3: V19.**

```sql
-- F2 (Phase 1b, B1): each shown value's verdict, written by the F1 writer in the same transaction as the document's
-- urgency, from the same rule branch. Value rows, result-now's "what caused it" and chat cards render ONLY from this.
ALTER TABLE ${flyway:defaultSchema}.observation
    ADD COLUMN verdict        varchar(8)  NULL CHECK (verdict IN ('inside', 'unknown', 'outside', 'critical')),
    ADD COLUMN verdict_level  varchar(16) NULL CHECK (verdict_level IN ('routine', 'this_week', 'now')),
    ADD COLUMN verdict_side   varchar(4)  NULL CHECK (verdict_side IN ('high', 'low')),
    ADD COLUMN verdict_reason text        NULL;

ALTER TABLE ${flyway:defaultSchema}.observation
    ADD CONSTRAINT observation_verdict_complete
        CHECK ((verdict IS NULL) = (verdict_level IS NULL) AND (verdict IS NULL) = (verdict_reason IS NULL)),
    ADD CONSTRAINT observation_verdict_reason_names_its_rule
        CHECK (verdict_reason IS NULL OR verdict_reason ~ '\|(stopgap|threshold:.+)$'),
    ADD CONSTRAINT observation_inside_is_routine CHECK (verdict IS DISTINCT FROM 'inside' OR verdict_level = 'routine'),
    ADD CONSTRAINT observation_critical_is_now CHECK (verdict IS DISTINCT FROM 'critical' OR verdict_level = 'now');

CREATE FUNCTION ${flyway:defaultSchema}.verdict_rank(v varchar) RETURNS int LANGUAGE sql IMMUTABLE
    AS $$ SELECT array_position(ARRAY['inside', 'unknown', 'outside', 'critical']::varchar[], v) $$;

CREATE FUNCTION ${flyway:defaultSchema}.observation_verdict_never_softens() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.verdict IS NOT NULL AND (NEW.verdict IS NULL
            OR ${flyway:defaultSchema}.verdict_rank(NEW.verdict) < ${flyway:defaultSchema}.verdict_rank(OLD.verdict)
            OR (NEW.verdict = OLD.verdict AND ${flyway:defaultSchema}.urgency_rank(NEW.verdict_level)
                                              < ${flyway:defaultSchema}.urgency_rank(OLD.verdict_level))) THEN
        RAISE EXCEPTION 'observation verdict may not soften (% -> %)', OLD.verdict, coalesce(NEW.verdict, 'NULL')
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER observation_verdict_never_softens BEFORE UPDATE ON ${flyway:defaultSchema}.observation
    FOR EACH ROW EXECUTE FUNCTION ${flyway:defaultSchema}.observation_verdict_never_softens();
-- No backfill here: UrgencySweepJob assesses every DONE document whose observations have no verdict (see below).
```

  The adapter's `raise` runs inside `transactional.transactional(...)` (`TransactionalOperator` bean, from
  `R2dbcTransactionManager`): the document UPDATE from A10, then per verdict (refusing a null `observationId` with
  `IllegalStateException("verdict without observation id")`) one guarded UPDATE that touches the row only when the new
  verdict is higher:

```sql
UPDATE %1$s.observation SET verdict = :kind, verdict_level = :level, verdict_side = :side, verdict_reason = :reason
WHERE id = :obs AND document_id = :doc
  AND (verdict IS NULL
       OR %1$s.verdict_rank(CAST(:kind AS varchar)) > %1$s.verdict_rank(verdict)
       OR (verdict = :kind AND %1$s.urgency_rank(CAST(:level AS varchar)) > %1$s.urgency_rank(verdict_level)))
```

  then `SELECT id, verdict, verdict_level, verdict_side, verdict_reason FROM observation WHERE document_id = :doc AND
  verdict IS NOT NULL` into `StoredUrgency.values`; `asAssessment()` carries them, so ExplanationService's standout lines
  are stored verdicts too. `needingAssessment` gains
  `OR EXISTS (SELECT 1 FROM %1$s.observation o WHERE o.document_id = d.id AND o.verdict IS NULL)` — the F2 backfill and
  every re-crop; `aSweepConverges` adds a document with two observations and asserts both carry a verdict and the queue
  is empty afterwards (a verdict-less observation would be picked every hour).
- [ ] **Step 4: run** `./gradlew :baymax:test --tests '*ValueVerdictNeverSoftensTest' --tests '*DocumentUrgencyNeverLowersTest' --tests '*NoUrgencyColumnInEntitiesTest' --tests '*OneUrgencyWriterTest' --tests '*FamilyDeletionAcceptanceTest'` → green.

### Task 14.3: the stored verdict on the value line (interim), demoted unless code proves INSIDE

**Files:** modify `ui/DocumentPages.legacyItems` (≤ 10 lines), `ResultSummaryRenderTest` (+2 tests; B2 replaces them),
`ui-result_*` (the chip words).

- [ ] **Step 1: failing tests** (both directions, matched on the row):

```java
    /** Interim F2 line: a stored INSIDE the code cannot prove reads "কাগজে মিলিয়ে দেখুন", never "সীমার মধ্যে". */
    @Test
    void aStoredInsideCodeCannotProveReadsCheckThePaper() {
        String row = rowOf(kit.documents.summaryBody(Lang.BN, ResultFixtures.result(ResultFixtures.lab(List.of(
                ResultFixtures.value("K", "6.4", "mmol/L", "3.5", "5.1", "inside"))), Urgency.ROUTINE, List.of(), GateState.NONE, null, ResultFixtures.BD)), "K");
        assertThat(row).contains(copy.t(Lang.BN, "value.chip.unknown")).doesNotContain(copy.t(Lang.BN, "value.chip.inside"));
    }

    @Test
    void aProvenInsideReadsInside() {
        String row = rowOf(kit.documents.summaryBody(Lang.BN, ResultFixtures.result(ResultFixtures.lab(List.of(
                ResultFixtures.value("Creatinine", "1.0", "mg/dL", "0.7", "1.3", "inside"))), Urgency.ROUTINE, List.of(), GateState.NONE, null, ResultFixtures.BD)), "Creatinine");
        assertThat(row).contains(copy.t(Lang.BN, "value.chip.inside"));
    }
```

  (`rowOf(html, name)` returns the `<div class="item">` holding that value's name.)
- [ ] **Step 2: red** (`no UI copy for key value.chip.unknown`, then `Expecting … to contain "কাগজে মিলিয়ে দেখুন"` while the
  line shows nothing). **Step 3:** append to the value line `<span class="tag">` + `copy.t(lang, chipKey(kindOf(v)))` +
  `</span>` where `kindOf(v)` returns UNKNOWN for a NULL or unrecognised verdict and for a stored INSIDE unless
  `UrgencyService.insideByCode(v)`; copy `value.chip.inside=সীমার মধ্যে`, `value.chip.unknown=কাগজে মিলিয়ে দেখুন`,
  `value.chip.outside.high=▲ সীমার বেশি`, `value.chip.outside.low=▼ সীমার কম`, `value.chip.critical.high=সীমার অনেক বেশি`,
  `value.chip.critical.low=সীমার অনেক কম` (kit §6, approved), English `Inside the range`, `Check the paper`, `▲ Above the
  range`, `▼ Below the range`, `Far above the range`, `Far below the range`.
- [ ] **Step 4: replay** — remove the demotion → `aStoredInsideCodeCannotProve…` red
  `Expecting … not to contain "সীমার মধ্যে"`. Standard pre-merge with the migration preflight (`V18__document_stored_urgency.sql`).
  Commit `feat(baymax): F2 per-value verdicts from the rule that set the urgency, never softened (V19)`.

### Try it (B1)

- [ ] **prod-web:** t3: Creatinine shows "▲ সীমার বেশি", the inside values "সীমার মধ্যে".
- [ ] **prod-web:** **t11** (qualitative, one-sided): "Trace", "Positive" and "<100" all read "কাগজে মিলিয়ে দেখুন"; none
  says "সীমার মধ্যে".
- [ ] **prod-web:** a paper from before this deploy shows verdicts within a minute (the startup sweep); its banner is
  unchanged.

---

## PR B2 — 2.2: value rows from the stored verdict, crop-first medicines, "ডাক্তার লিখেছেন" (s10 complete)

Branch `feat/baymax-result-value-rows`. Spec §4.10, §9 ValueRow / MedicineRow, principles 5–7. Each value row reads
only its stored verdict (B1): its class, its chip word, and a range bar drawn **only** where the code placed the value
(the bar's pin uses the verdict's own parser). A stored INSIDE the code cannot prove is shown UNKNOWN. Every row sits
beside its stored crop. Medicines come crop first and large, the line verbatim and quiet, with no colour and no status.
The doctor's lines are quoted under "কাগজে লেখা", each with its crop; Medioo's words are never on a paper block and a
paper block is never in Medioo's bubble. This replaces B1's interim chip and A12's `legacyItems`.

### Task 22.1: `UrgencyService.pinPercent` — the bar uses the verdict's parser

**Files:** modify `UrgencyService` (after `insideByCode`); create test `outbound/application/service/PinPercentTest.java`.

- [ ] **Step 1: failing tests (both directions):**

```java
/** The range bar may only tell the story the verdict tells: a pin in the band exactly when the code proved INSIDE. */
class PinPercentTest {

    @ParameterizedTest(name = "{0} in {1}–{2}")
    @CsvSource(delimiter = '|', nullValues = "null", value = {
            "5.1|4.0|6.0", "4.0|4.0|6.0", "6.0|4.0|6.0", "8.4|4.0|6.0", "3.1|4.0|6.0", "13.0|4.0|6.0",
            "> 89|60|120", "130 / 80|90|140", "1,23|0.5|1.5", "Positive|null|null", "250|null|100", "250|null|null", "1,5|1|2"})
    void thePinIsInTheBandExactlyWhenTheValueIsInsideByCode(String value, String low, String high) {
        Map<String, Object> v = UrgencyServiceTest.value("x", value, low, high);
        OptionalDouble pin = UrgencyService.pinPercent(v);
        boolean inBand = pin.isPresent() && pin.getAsDouble() >= 25 && pin.getAsDouble() <= 75;
        assertThat(inBand).isEqualTo(UrgencyService.insideByCode(v));
    }

    @Test
    void anUnparseableReadingHasNoPinAtAll() {
        for (String value : List.of("> 89", "130 / 80", "1,23", "Positive", "Trace")) {
            assertThat(UrgencyService.pinPercent(UrgencyServiceTest.value("x", value, "1", "2"))).as(value).isEmpty();
        }
    }

    @Test
    void aFarValueIsClampedToTheBarNotOffIt() {
        assertThat(UrgencyService.pinPercent(UrgencyServiceTest.value("k", "99", "3.5", "5.1")).getAsDouble()).isEqualTo(98.0);
        assertThat(UrgencyService.pinPercent(UrgencyServiceTest.value("k", "0.1", "3.5", "5.1")).getAsDouble()).isEqualTo(2.0);
    }
}
```

- [ ] **Step 2: red** (`cannot find symbol pinPercent`). Replay with a pin computed by
  `Double.parseDouble(value.replaceAll("[^0-9.]", ""))`, the way a renderer naturally writes it: `"1,23"` fails with
  `expected: false but was: true` — the stripped "123" placed as a reading the verdict refused.
- [ ] **Step 3: implement:**

```java
    /**
     * Where a value's pin sits on the range bar, in percent: the printed range is the middle half (25–75), clamped to
     * 2–98. Empty unless the value and both bounds parse with {@link #number}, the parser the verdict uses, and
     * low < high. A bar drawn by any other parser could show a reading the verdict refused to read.
     */
    public static OptionalDouble pinPercent(Map<String, Object> v) {
        Optional<Double> value = number(v.get("value"));
        Optional<Double> low = number(v.get("ref_low"));
        Optional<Double> high = number(v.get("ref_high"));
        if (value.isEmpty() || low.isEmpty() || high.isEmpty() || !(high.get() > low.get())) {
            return OptionalDouble.empty();
        }
        double p = 25 + (value.get() - low.get()) / (high.get() - low.get()) * 50;
        return OptionalDouble.of(Math.max(2, Math.min(98, p)));
    }
```

### Task 22.2: `ValueRows.row` — class, chip, bar and crop from the stored verdict, INSIDE only when proven

**Files:** create `ui/ValueRows.java`, test `ui/ValueRowRenderTest.java`; `ui-result_*` (the B1 chip keys stay; add
the group keys below).

- [ ] **Step 1: copy** (s10/s17/s13; `value.chip.*` exist from B1):

```properties
value.group.outside=যেগুলো সীমার বাইরে
value.group.unknown=কাগজে মিলিয়ে দেখুন
value.group.unknown.why=এই মানটা রিপোর্টে লেখা সীমার সাথে Medioo মেলাতে পারেনি। কাগজে কী লেখা, নিজে দেখে নিন।
value.group.inside=রিপোর্টে লেখা সীমার মধ্যে
value.range=রিপোর্টে লেখা সীমা {0}–{1}
value.range.one=রিপোর্টে লেখা সীমা: {0}
value.range.none=রিপোর্টে সীমা লেখা নেই
value.chip.outside=সীমার বাইরে
value.chip.critical=সীমার অনেক বাইরে
value.unverified=কিছু মান মিলিয়ে দেখতে পারিনি — মূল কাগজ দেখুন
crop.zoom=বড় করে দেখুন
```

  `value.chip.outside`, `value.chip.critical` and `value.unverified` are new wording (no drawn board): listed for the PO.
- [ ] **Step 2: failing tests.**

```java
/** ValueRow (spec §9, F2): a row's look comes only from its stored verdict; "inside" is never shown unproven. */
class ValueRowRenderTest {

    private final UiCopy copy = new UiCopy();

    /** Assert the class AND the chip per kind, not that "a colour exists". Derived over the enum. */
    @ParameterizedTest(name = "{0}")
    @CsvSource({"INSIDE,5.1,value.chip.inside", "UNKNOWN,Trace,value.chip.unknown",
            "OUTSIDE,8.4,value.chip.outside.high", "CRITICAL,13.0,value.chip.critical.high"})
    void aRowsClassAndChipComeFromItsStoredVerdict(ValueVerdict.Kind kind, String value, String chipKey) {
        String row = ValueRows.row(copy, Lang.BN, ResultFixtures.DOC,
                ResultFixtures.value("HbA1c", value, "%", "4.0", "6.0", kind.name().toLowerCase(Locale.ROOT)));
        assertThat(row).startsWith("<a class=\"vrow " + kind.name().toLowerCase(Locale.ROOT) + "\"");
        assertThat(row).contains("<span class=\"chip " + kind.name().toLowerCase(Locale.ROOT) + "\">" + copy.t(Lang.BN, chipKey) + "</span>");
    }

    @Test
    void everyVerdictKindHasARendering() {
        assertThat(ValueVerdict.Kind.values()).isNotEmpty().allSatisfy(k -> assertThat(ValueRows.chipKey(k, OptionalDouble.empty())).isNotBlank());
    }

    /** F2(b) on the page: every shape the code cannot place is UNKNOWN — no bar, no pin, no teal, never "normal". */
    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "null", value = {"> 89|60|120", "130 / 80|90|140", "1,23|0.5|1.5",
            "Positive|null|null", "250|null|<100", "250|null|null"})
    void aValueTheCodeCouldNotPlaceHasNoBarAndNoTeal(String value, String low, String high) {
        String row = ValueRows.row(copy, Lang.BN, ResultFixtures.DOC, ResultFixtures.value("LDL", value, "mg/dL", low, high, "unknown"));
        assertThat(row).doesNotContain("<svg").doesNotContain("inside").doesNotContain("স্বাভাবিক")
                .contains(copy.t(Lang.BN, "value.chip.unknown"));
    }

    /** A row stored before F2 (verdict NULL) is UNKNOWN — the safe direction — never INSIDE. */
    @Test
    void aRowWithNoStoredVerdictIsUnknown() {
        String row = ValueRows.row(copy, Lang.BN, ResultFixtures.DOC, ResultFixtures.value("Creatinine", "1.0", "mg/dL", "0.7", "1.3", null));
        assertThat(row).startsWith("<a class=\"vrow unknown\"").doesNotContain("<svg");
    }

    /** The hard rule, both directions: a stored INSIDE the code cannot prove renders UNKNOWN — no bar, no teal, no "inside". */
    @Test
    void aStoredInsideCodeCannotProveRendersUnknown() {
        String row = ValueRows.row(copy, Lang.BN, ResultFixtures.DOC, ResultFixtures.value("K", "6.4", "mmol/L", "3.5", "5.1", "inside"));
        assertThat(row).startsWith("<a class=\"vrow unknown\"").contains("<span class=\"chip unknown\">")
                .doesNotContain("<svg").doesNotContain(copy.t(Lang.BN, "value.chip.inside"));
    }

    @Test
    void aProvenInsideRendersInside() {
        String row = ValueRows.row(copy, Lang.BN, ResultFixtures.DOC, ResultFixtures.value("Creatinine", "1.0", "mg/dL", "0.7", "1.3", "inside"));
        assertThat(row).startsWith("<a class=\"vrow inside\"").contains("<span class=\"chip inside\">" + copy.t(Lang.BN, "value.chip.inside") + "</span>")
                .contains("<svg class=\"bar\"");
    }

    /** Values keep the printed digits; the range text is the paper's text; the crop is the stored crop_key (DR-12). */
    @Test
    void theRowShowsThePaperAsPrintedBesideItsStoredCrop() {
        Map<String, Object> v = ResultFixtures.value("Creatinine", "1.8", "mg/dL", "0.7", "1.3", "outside");
        String row = ValueRows.row(copy, Lang.BN, ResultFixtures.DOC, v);
        assertThat(row).contains(">1.8<").contains("রিপোর্টে লেখা সীমা 0.7–1.3")
                .contains("src=\"" + DocumentPages.imageHref(ResultFixtures.DOC, (String) v.get("crop_key")) + "\"");
        assertThat(row).doesNotContain("১.৮");
    }
}
```

- [ ] **Step 3: red, and the replays.** Compile errors; then, with `kindOf` reading the model's `flag` (as today's page
  does implicitly), `aRowWithNoStoredVerdictIsUnknown` fails `startsWith("<a class=\"vrow unknown\"") … but was "<a class=\"vrow inside\""`.
  **The demotion's replay:** remove the `insideByCode` check from `kindOf` → `aStoredInsideCodeCannotProveRendersUnknown`
  red `Expecting actual: "<a class="vrow inside" …" to start with "<a class="vrow unknown"`. Paste both.
- [ ] **Step 4: implement** (the excerpt below plus the draft's `pin`, `chipKey`, `row`, `bar`, `range`):

```java
final class ValueRows {

    private ValueRows() {
    }


    /**
     * F2 on the page: the stored verdict; NULL or anything unrecognised is UNKNOWN, and a stored INSIDE is shown INSIDE
     * only when the code proves it on the row as rendered (insideByCode). The direction that withholds reassurance wins.
     */
    static ValueVerdict.Kind kindOf(Map<String, Object> v) {
        Object raw = v.get("verdict");
        if (raw == null) {
            return ValueVerdict.Kind.UNKNOWN;
        }
        ValueVerdict.Kind kind;
        try {
            kind = ValueVerdict.Kind.valueOf(String.valueOf(raw).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ValueVerdict.Kind.UNKNOWN;
        }
        return kind == ValueVerdict.Kind.INSIDE && !UrgencyService.insideByCode(v) ? ValueVerdict.Kind.UNKNOWN : kind;
    }


    /** The pin only when it agrees with the verdict: INSIDE in the band, OUTSIDE/CRITICAL outside it, UNKNOWN never. */
    static OptionalDouble pin(ValueVerdict.Kind kind, Map<String, Object> v) {
        if (kind == ValueVerdict.Kind.UNKNOWN) {
            return OptionalDouble.empty();
        }
        OptionalDouble p = UrgencyService.pinPercent(v);
        if (p.isEmpty()) {
            return p;
        }
        boolean inBand = p.getAsDouble() >= 25 && p.getAsDouble() <= 75;
        return inBand == (kind == ValueVerdict.Kind.INSIDE) ? p : OptionalDouble.empty();
    }

    static String chipKey(ValueVerdict.Kind kind, OptionalDouble pin) {
        String side = pin.isEmpty() ? "" : pin.getAsDouble() > 75 ? ".high" : pin.getAsDouble() < 25 ? ".low" : "";
        return switch (kind) {
            case INSIDE -> "value.chip.inside";
            case UNKNOWN -> "value.chip.unknown";
            case OUTSIDE -> "value.chip.outside" + side;
            case CRITICAL -> "value.chip.critical" + side;
        };
    }

    static String row(UiCopy copy, Lang lang, UUID documentId, Map<String, Object> v) {
        ValueVerdict.Kind kind = kindOf(v);
        String cls = kind.name().toLowerCase(Locale.ROOT);
        OptionalDouble pin = pin(kind, v);
        String chip = copy.t(lang, chipKey(kind, pin));
        String range = range(copy, lang, v);
        String href = BASE + "/documents/" + documentId + "/values/" + v.get("id");   // B2: the crop; B6 points it at the value sheet
        StringBuilder b = new StringBuilder("<a class=\"vrow ").append(cls).append("\" id=\"v-").append(v.get("id"))
                .append("\" href=\"").append(href).append("\" aria-label=\"")
                .append(esc(v.get("name"))).append(' ').append(esc(v.get("value"))).append(' ').append(esc(v.get("unit")))
                .append(", ").append(range).append(", ").append(chip).append("\">")
                .append("<span class=\"top\"><span class=\"name\">").append(esc(v.get("name"))).append("</span>")
                .append("<span class=\"val\">").append(esc(v.get("value"))).append("</span> <span class=\"unit\">")
                .append(esc(v.get("unit"))).append("</span></span>")
                .append("<img class=\"crop\" loading=\"lazy\" alt=\"\" src=\"").append(imageHref(documentId, String.valueOf(v.get("crop_key")))).append("\">");
        pin.ifPresent(p -> b.append(bar(v, kind, p)));
        return b.append("<span class=\"foot\"><span class=\"range\">").append(range).append("</span><span class=\"chip ")
                .append(cls).append("\">").append(chip).append("</span></span></a>").toString();
    }

    /** Server SVG: geometry as attributes, colour from classes (CSP style-src 'self'; DesignTokensTest forbids literals). */
    static String bar(Map<String, Object> v, ValueVerdict.Kind kind, double pin) {
        return "<svg class=\"bar\" viewBox=\"0 0 100 10\" aria-hidden=\"true\" focusable=\"false\">"
                + "<rect class=\"track\" x=\"0\" y=\"4\" width=\"100\" height=\"2\"/>"
                + "<rect class=\"band\" x=\"25\" y=\"2\" width=\"50\" height=\"6\"/>"
                + "<circle class=\"pin " + kind.name().toLowerCase(Locale.ROOT) + "\" cx=\"" + String.format(Locale.ROOT, "%.1f", pin) + "\" cy=\"5\" r=\"4\"/></svg>"
                + "<span class=\"ends\"><span>" + esc(v.get("ref_low")) + "</span><span>" + esc(v.get("ref_high")) + "</span></span>";
    }

    static String range(UiCopy copy, Lang lang, Map<String, Object> v) {
        Object low = v.get("ref_low");
        Object high = v.get("ref_high");
        if (low != null && high != null) {
            return copy.t(lang, "value.range", String.valueOf(low), String.valueOf(high));   // strings: printed digits kept
        }
        if (low != null || high != null) {
            return copy.t(lang, "value.range.one", String.valueOf(low != null ? low : high));
        }
        return copy.t(lang, "value.range.none");
    }
}
```

  Every value row is a link whose `href` is the crop (`DocumentPages.imageHref`) until B6 points it at the value sheet.
  A value map without a `crop_key` is never rendered (V5: `crop_key` NOT NULL; `row` throws
  `IllegalStateException("a value without its crop is never shown")` so the page fails loudly rather than show a number
  without its proof). CSS (`60-result.css`, tokens only):
  `.vrow{display:grid;gap:var(--sp-2);min-height:var(--target-min);padding:var(--sp-3);border-radius:var(--r-m);text-decoration:none;color:var(--c-text)}`,
  `.vrow.inside,.vrow.unknown{background:var(--c-surface);border:1px solid var(--c-line)}`,
  `.vrow.outside{background:var(--c-status-soon-container)}`, `.vrow.critical{background:var(--c-status-now-container)}`,
  `.chip.inside{background:var(--c-status-inside-container);color:var(--c-status-inside)}`,
  `.chip.unknown{background:var(--c-status-unknown-container);color:var(--c-status-unknown);border:1px dashed var(--c-outline)}`,
  `.chip.outside{background:var(--c-surface);color:var(--c-status-soon)}`,
  `.chip.critical{background:var(--c-status-now-solid);color:var(--c-on-status-now)}`, `.bar{width:100%;height:auto}`,
  `.bar .track{fill:var(--c-surface-3)}`, `.bar .band{fill:var(--c-surface);stroke:var(--c-outline);stroke-width:.5}`,
  `.pin.inside{fill:var(--c-status-inside)}`, `.pin.outside{fill:var(--c-status-soon)}`, `.pin.critical{fill:var(--c-status-now)}`,
  `.crop{border:1px solid var(--c-surface-3);border-radius:var(--r-xs)}`, `.val,.unit,.range,.ends{font-family:var(--font-latin)}`,
  `.val{font-size:var(--fs-value);line-height:var(--lh-value)}`, `.unit{font-size:var(--fs-unit);line-height:var(--lh-unit)}`,
  `.range{font-size:var(--fs-range);line-height:var(--lh-range)}`. Every verdict colour is paired with its word (and
  ▲/▼ for OUTSIDE). `.chip.critical` is red: `DesignTokensTest.redAppearsOnly…` allows `.critical`.

### Task 22.3: the summary sections — grouped rows, crop-first medicines, the doctor's lines, পরের ধাপ

**Files:** modify `ui/DocumentPages.java` (`valuesSummary`, `medicinesSection`, `doctorSection`, `nextSection` replace
`legacyItems` and B1's interim chip; the header gains the counts line; "প্রশ্ন? চ্যাট" links `/app/home`),
`ValueRows` (+`medicine`, `paperQuote`), `ResultSummaryRenderTest` (B1's two interim tests deleted — `ValueRowRenderTest`
holds the rule now), create test `ui/PaperIsNeverMediooTest.java`, `ui-result_*`.

- [ ] **Step 1: copy:** `med.title=ওষুধ`, `med.pharmacy=ফার্মেসিতে মূল প্রেসক্রিপশন বা এই ছবিটা দেখান — টাইপ করা লেখা নয়`,
  `med.unverified={0}টি লাইন পড়া যায়নি — মূল কাগজ দেখুন`, `doctor.title=ডাক্তার লিখেছেন`, `next.title=পরের ধাপ`,
  `next.bring=ডাক্তার দেখানোর সময় সাথে নিন: এই রিপোর্টের মূল কাগজ, আর আগের প্রেসক্রিপশনগুলো`,
  `next.followup=কাগজে লেখা পরের দেখা: {0}`, `result.counts={0}টি মান · {1}টি সীমার বাইরে`, `result.counts.none={0}টি মান`,
  `result.ask=প্রশ্ন? চ্যাট` (and English).
- [ ] **Step 2: failing tests** (append to `ResultSummaryRenderTest`; `id(view, name)` returns that value map's `id`):

```java
    /** Spec §4.10 grouping: outside first (critical before outside), then unknown, then inside collapsed behind a count. */
    @Test
    void rowsAreGroupedByVerdictAndInsideRowsCollapse() {
        var view = ResultFixtures.lab(List.of(
                ResultFixtures.value("Haemoglobin", "12.6", "g/dL", "12.0", "15.5", "inside"),
                ResultFixtures.value("WBC", "7.2", "10^3/µL", "4.0", "11.0", "inside"),
                ResultFixtures.value("Urine Albumin", "Trace", null, "Nil", null, "unknown"),
                ResultFixtures.value("HbA1c", "8.4", "%", "4.0", "6.0", "outside"),
                ResultFixtures.value("Potassium", "11.0", "mmol/L", "3.5", "5.1", "critical")));
        String html = kit.documents.summaryBody(Lang.BN, ResultFixtures.result(view, Urgency.NOW, List.of(), GateState.NONE, null, ResultFixtures.BD));
        assertThat(html.indexOf("id=\"v-" + id(view, "Potassium"))).isLessThan(html.indexOf("id=\"v-" + id(view, "HbA1c")));
        assertThat(html.indexOf("id=\"v-" + id(view, "HbA1c"))).isLessThan(html.indexOf("id=\"v-" + id(view, "Urine Albumin")));
        assertThat(html).contains("id=\"v-" + id(view, "Haemoglobin")).contains("id=\"v-" + id(view, "WBC"));
                // B6 collapses the inside rows behind a count once /all exists
    }

    /** MedicineRow: crop first and large, caption verbatim and quiet, no colour, no status, the pharmacy notice once. */
    @Test
    void aMedicineIsItsCropFirstWithTheLineAsWrittenAndNoStatus() {
        var view = ResultFixtures.prescription(List.of(Map.of("name", "Tab. Demoprol", "dose_text", "5 mg",
                "frequency_text", "1+0+1", "timing_text", "খাবারের পরে", "duration_text", "৩০ দিন", "crop_key", "f/p/d/crop-m1.jpg")), Map.of(), null);
        String html = kit.documents.summaryBody(Lang.BN, ResultFixtures.result(view, Urgency.ROUTINE, List.of(), GateState.NONE, null, ResultFixtures.BD));
        String med = ResultFixtures.section(html, "medicines");
        assertThat(med).containsOnlyOnce(copy.t(Lang.BN, "med.pharmacy"));
        assertThat(med.indexOf("crop-m1.jpg")).isLessThan(med.indexOf("Tab. Demoprol"));
        assertThat(med).contains("Tab. Demoprol · 5 mg · 1+0+1 · খাবারের পরে · ৩০ দিন").doesNotContain("class=\"chip");
    }

    /** DR-26 / principle 6: the doctor's words are verbatim under "কাগজে লেখা", each with its crop; never Medioo's label. */
    @Test
    void theDoctorsLinesAreQuotedFromThePaperBesideTheirCrops() {
        var ctx = new LinkedHashMap<String, Object>();
        ctx.put("diagnosis", List.of(Map.of("text", "Kindly correlate clinically.", "crop_key", "f/p/d/crop-c1.jpg")));
        ctx.put("referral", "Dr X");
        var view = ResultFixtures.prescription(List.of(), ctx, null);
        String doctor = ResultFixtures.section(kit.documents.summaryBody(Lang.BN, ResultFixtures.result(view, Urgency.THIS_WEEK, List.of(), GateState.RELEASED, "w", ResultFixtures.BD)), "doctor");
        assertThat(doctor).contains("কাগজে লেখা").contains("Kindly correlate clinically.").contains("crop-c1.jpg")
                .doesNotContain("Medioo-র কথা").doesNotContain("Dr X");   // referral stays hidden (contextSection skips it)
    }

    @Test
    void anUnverifiedCountSendsTheFamilyToThePaperAndShowsNoneOfThoseItems() {
        var view = ResultFixtures.prescription(List.of(), Map.of(), Map.of("values", 0, "medicines", 1, "follow_up", 0, "total", 1));
        String html = kit.documents.summaryBody(Lang.BN, ResultFixtures.result(view, Urgency.ROUTINE, List.of(), GateState.NONE, null, ResultFixtures.BD));
        assertThat(html).contains(copy.t(Lang.BN, "med.unverified", 1)).contains("১টি লাইন");
    }
```

```java
    private static Object id(DocumentView view, String name) {
        return view.values().stream().filter(v -> name.equals(v.get("name"))).findFirst().orElseThrow().get("id");
    }

    /** Nothing outside says nothing about the range: no "০টি সীমার বাইরে", and never a reassurance (principle 10). */
    @Test
    void theCountsLineNamesOutsideOnlyWhenSomethingIs() {
        String none = kit.documents.summaryBody(Lang.BN, ResultFixtures.result(ResultFixtures.lab(List.of(
                ResultFixtures.value("Creatinine", "1.0", "mg/dL", "0.7", "1.3", "inside"))), Urgency.ROUTINE, List.of(), GateState.NONE, null, ResultFixtures.BD));
        assertThat(none).contains(copy.t(Lang.BN, "result.counts.none", 1)).doesNotContain("সীমার বাইরে");
        String two = kit.documents.summaryBody(Lang.BN, ResultFixtures.result(ResultFixtures.lab(List.of(
                ResultFixtures.value("HbA1c", "8.4", "%", "4.0", "6.0", "outside"),
                ResultFixtures.value("Potassium", "11.0", "mmol/L", "3.5", "5.1", "critical"))), Urgency.NOW, List.of(), GateState.NONE, null, ResultFixtures.BD));
        assertThat(two).contains("২টি মান · ২টি সীমার বাইরে");
    }
```

  `PaperIsNeverMediooTest` — derived over every page, and over the stylesheet:

```java
package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Principle 6: "কাগজে লেখা" and "Medioo-র কথা" never look alike — on every page, and in the CSS that draws them. */
class PaperIsNeverMediooTest {

    private static final Pattern PAPER = Pattern.compile("<(figure|div) class=\"paper\"[^>]*>(.*?)</\\1>", Pattern.DOTALL);
    private static final Pattern MEDIOO = Pattern.compile("<(section|p|div) [^>]*(?:class=\"bubble\"|id=\"says\")[^>]*>(.*?)</\\1>", Pattern.DOTALL);

    @Test
    void everyPaperBlockCarriesItsLabelAndItsCropAndNoMediooBlockCarriesEither() {
        UiCopy copy = new UiCopy();
        String label = copy.t(Lang.BN, "paper.label");
        List<String> wrong = new ArrayList<>();
        int papers = 0;
        for (Map.Entry<String, String> page : RenderedPages.allBangla().entrySet()) {
            for (Matcher m = PAPER.matcher(page.getValue()); m.find(); ) {
                papers++;
                if (!m.group(2).contains(label) || !m.group(2).contains("<img class=\"crop")) {
                    wrong.add(page.getKey() + " a paper block without its label or crop");
                }
            }
            for (Matcher m = MEDIOO.matcher(page.getValue()); m.find(); ) {
                if (m.group(2).contains("<img class=\"crop") || m.group(2).contains(label)) {
                    wrong.add(page.getKey() + " Medioo's words carry a crop or the paper label");
                }
            }
        }
        assertThat(papers).as("paper blocks found on the fixture pages").isPositive();
        assertThat(wrong).isEmpty();
    }

    @Test
    void thePaperAndMediooStylesDifferInBackgroundAndTypeface() {
        String css = Tokens.rules(new WebUiTestKit().servedText("/assets/baymax.css"));
        String paper = rule(css, ".paper"), says = rule(css, ".says"), bubble = rule(css, ".bubble");
        for (String medioo : List.of(says, bubble)) {
            assertThat(prop(medioo, "background")).isNotEqualTo(prop(paper, "background"));
            assertThat(prop(medioo, "font-family")).isNotEqualTo(prop(paper, "font-family"));
        }
    }

    private static String rule(String css, String selector) {
        Matcher m = Pattern.compile("(?m)^" + Pattern.quote(selector) + "\\{([^}]*)}").matcher(css);
        assertThat(m.find()).as("a rule for " + selector).isTrue();
        return m.group(1);
    }

    private static String prop(String rule, String name) {
        Matcher m = Pattern.compile("(?<![-a-z])" + name + "\\s*:\\s*([^;]+)").matcher(rule);
        return m.find() ? m.group(1).trim() : "(inherited)";
    }
}
```

  (`RenderedPages.allBangla()` is the Bangla half of `all()`. The B2 fixture `Stubs::doneLab` gains a diagnosis line with
  a crop, so the derived test sees a paper block.)
- [ ] **Step 3: red, and the replay.** `block #medicines Expecting actual: -1 …` until the section exists. Replay the
  order assertion with today's text-then-crop markup (`<div class="text">` before `<img>`, the old `section()`): it fails
  `Expecting actual: <n> to be less than: <m>` — the typed line before the crop, the order DR-12 and principle 7
  reverse. Replay `PaperIsNeverMediooTest` by putting the released wording inside a `<figure class="paper">` → red
  `GET /app/documents/{id} bn a paper block without its label or crop`.
- [ ] **Step 4: implement.** `valuesSummary` sorts by `kindOf` (CRITICAL, OUTSIDE | UNKNOWN | INSIDE) and renders
  `<section id="outside">` (`value.group.outside`), `<section id="unknown">` with `value.group.unknown.why`, and
  `<section id="inside">` (`value.group.inside`, every row; B6 collapses them). Then `<section id="doctor">`
  (`doctor.title`) with every clinical-context section except `referral`, each item as `ValueRows.paperQuote`
  (`<figure class="paper"><figcaption class="label">` + `paper.label` + `</figcaption><img class="crop" …><blockquote>`
  verbatim `</blockquote></figure>`). Then `<section id="medicines">` (`med.pharmacy`, then one `ValueRows.medicine` per
  line: `<a class="med" href="{crop}">` wrapping `<img class="crop big">` then `<span class="cap">` joining the non-blank
  verbatim fields with " · "). Last `<section id="next">`: `next.bring` for THIS_WEEK/NOW only, then one paper-quoted
  follow-up per stored follow-up (`instruction` verbatim, `due_date` through `copy.date`). `value.unverified` /
  `med.unverified` come from `DocumentView.unverified()`. The header's counts line: `result.counts.none` when no row is
  OUTSIDE or CRITICAL, else `result.counts`. "প্রশ্ন? চ্যাট" (`result.ask`) is a quiet link to `/app/home`. CSS:
  `.paper{padding:var(--sp-3);background:var(--c-paper);border:1px solid var(--c-paper-line);border-radius:var(--r-xs);font-family:var(--font-mono)}`
  in `60-result.css` (C4 reuses it), `.paper .label{display:flex;align-items:center;gap:6px;font-family:var(--font-sans);font-size:var(--fs-label-small);line-height:var(--lh-label-small);font-weight:600;color:var(--c-text-muted)}`,
  `.med{display:flex;flex-direction:column;gap:var(--sp-2);min-height:var(--target-min);text-decoration:none;color:var(--c-text)}`,
  `.crop.big{width:100%}`, `.cap{color:var(--c-text-muted);font-size:var(--fs-callout);line-height:var(--lh-callout)}`.
- [ ] **Step 5: run** `./gradlew :baymax:test`; standard pre-merge. Commit
  `feat(baymax-web): value rows from the stored verdict, crop-first medicines, the doctor's lines as written`.

### Try it (B2) — prod-web

- [ ] t3: Creatinine is amber with "▲ সীমার বেশি" and a bar; the header reads "Nটি মান · ১টি সীমার বাইরে".
- [ ] t11: "Trace" and "Positive" sit under "কাগজে মিলিয়ে দেখুন" with no bar.
- [ ] t4a: every medicine shows its photo strip first, then the line as written; the pharmacy notice is on top; the
  follow-up is quoted under "কাগজে লেখা" beside its crop.
- [ ] Zoom to 200 % at 360 px: nothing scrolls sideways.

---

## PR B3 — 2.3: result-now (s11) — one block per reason, and an emergency action that knows where the reader is

Branch `feat/baymax-result-now`. Spec §4.11, F7. On a NOW paper the red header is followed by **one block per stored
reason**, derived from `UrgencyReason` (B1) so a reason added later cannot reach a NOW page with nothing to show for it;
a stored reason with a prefix the enum does not know (a legacy row) gets a generic block, never nothing. The emergency
action depends on the reader's own phone: a Bangladeshi number gets "৯৯৯-এ ফোন করুন" once, under the red-flag
questions; a phone abroad is **never** handed `tel:999` (it does not connect from abroad) and gets "call the person
with them" plus a WhatsApp share whose text carries no value.

### Task 23.1: the failing tests

**Files:** create test `ui/ResultNowRenderTest.java`; `ui-result_*` (copy below).

- [ ] **Step 1: copy** (s11, approved; every string the page shows is a key, including the button labels, so the
  English toggle reaches them and `NoBanglaInJavaLiteralsTest` stays green):

```properties
now.why.unverified=এই কাগজের একটা মান আমরা মিলিয়ে দেখতে পারিনি, কিন্তু সেটা বিপদসীমায় হতে পারে — মূল কাগজটা এখনই ডাক্তারকে দেখান।
now.why.phrase=কাগজে 'জরুরি' বা 'ভর্তি' লেখা আছে।
now.why.unverified_outside=কিছু মান মিলিয়ে দেখতে পারিনি — মূল কাগজ দেখুন
now.why.followup=ডাক্তারের দেওয়া ফলো-আপের তারিখ পেরিয়ে গেছে:
now.page=পাতা {0} · পুরো পাতা
now.flags.q=অজ্ঞান? শ্বাস নিতে কষ্ট? বুকে ব্যথা?
now.flags.go=হ্যাঁ হলে এখনই নিকটস্থ হাসপাতালের জরুরি বিভাগে যান।
now.call999=৯৯৯-এ ফোন করুন
now.remote.lead={0} — তাঁর কাছে যিনি আছেন, তাঁকে এখনই ফোন করুন। তাঁকে ৯৯৯-এ ফোন করতে বা হাসপাতালে নিয়ে যেতে বলুন।
now.remote.999=বাংলাদেশে জরুরি নম্বর ৯৯৯ — বিদেশের ফোন থেকে এই নম্বরে কল যায় না
now.remote.flags.go=হ্যাঁ হলে তাঁর কাছে যিনি আছেন, তাঁকে বলুন এখনই নিকটস্থ হাসপাতালের জরুরি বিভাগে নিয়ে যেতে।
now.remote.share=বাড়ির কাউকে পাঠান
now.remote.share.note=WhatsApp-এ যাবে: এখনই জরুরি বিভাগে নিতে হবে, আর সাথে কী নিতে হবে। রিপোর্টের কোনো মান যাবে না।
now.remote.share.text=জরুরি: {0} — এখনই নিকটস্থ হাসপাতালের জরুরি বিভাগে নিয়ে যান। সাথে নিন: এই রিপোর্টের মূল কাগজ আর শেষ প্রেসক্রিপশন।
now.bring=সাথে নিয়ে যান: এই রিপোর্টের মূল কাগজ, আর শেষ প্রেসক্রিপশন
now.bring.remote=সাথে নিয়ে যেতে বলুন: এই রিপোর্টের মূল কাগজ, আর শেষ প্রেসক্রিপশন
now.notdoctor=আমি ডাক্তার নই — এই মুহূর্তে আমার উত্তরের জন্য অপেক্ষা করবেন না।
```

  plus `now.why.other=এই কাগজে জরুরি কিছু আছে — মূল কাগজটা এখনই ডাক্তারকে দেখান।` (the generic block; new wording for
  the PO) and `now.why.critical=এই মানটা রিপোর্টে লেখা সীমার অনেক বাইরে:` (s11). The names stay standalone arguments
  (`now.remote.lead`, `now.remote.share.text`): no bundle writes `{0}-এর` or `{0}কে`.
- [ ] **Step 2: the tests.**

```java
package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.outbound.domain.UrgencyReason;
import com.elioo.baymax.web.domain.GateState;
import com.elioo.baymax.web.domain.ViewerLocale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** s11 / spec §4.11: one block per reason, derived from the rule's own enum, and an action that depends on the reader. */
class ResultNowRenderTest {

    private final WebUiTestKit kit = new WebUiTestKit();
    private final UiCopy copy = kit.copy;

    private String now(List<String> reasons, ViewerLocale viewer) {
        var view = ResultFixtures.lab(List.of(ResultFixtures.value("Potassium", "11.0", "mmol/L", "3.5", "5.1", "critical")));
        return kit.documents.summaryBody(Lang.BN, ResultFixtures.result(view, Urgency.NOW, reasons, GateState.PENDING, null, viewer));
    }

    /** Derived: every reason the rule can raise has its block — the enum is the rule's own list (UrgencyAssessment.raise refuses others). */
    @Test
    void everyReasonTheRuleCanRaiseHasItsOwnBlock() {
        assertThat(EnumSet.allOf(UrgencyReason.class)).as("the rule's reasons").hasSizeGreaterThanOrEqualTo(7);
        assertThat(DocumentPages.REASON_BLOCKS.keySet()).isEqualTo(EnumSet.allOf(UrgencyReason.class));
    }

    /** Assert the block, not "a red banner": each reason renders its own id, and only its own. */
    @ParameterizedTest
    @EnumSource(UrgencyReason.class)
    void eachReasonRendersItsOwnBlockAndNoOther(UrgencyReason reason) {
        String html = now(List.of(reason.of(reason.name().startsWith("VALUE") || reason.name().startsWith("UNVERIFIED") ? "potassium" : null,
                reason.name().startsWith("VALUE") ? "|stopgap" : null)), ResultFixtures.BD);
        String mine = DocumentPages.REASON_BLOCKS.get(reason);
        assertThat(html).contains("id=\"" + mine + "\"");
        DocumentPages.REASON_BLOCKS.values().stream().filter(b -> !b.equals(mine) && b.startsWith("why-"))
                .forEach(other -> assertThat(html).as(other).doesNotContain("id=\"" + other + "\""));
    }

    /** A stored reason whose prefix the enum does not know (a legacy row) gets the generic block — never nothing. */
    @Test
    void anUnknownStoredReasonGetsTheGenericBlock() {
        String block = ResultFixtures.section(now(List.of("retired_rule_2025:potassium"), ResultFixtures.BD), "why-other");
        assertThat(block).contains(copy.t(Lang.BN, "now.why.other")).contains("/page/1");
    }

    /** An unverified value is not an observation: its block shows the whole pages and no value row at all. */
    @Test
    void anUnverifiedCriticalValueShowsEveryPageAndNoValueRow() {
        String block = ResultFixtures.section(now(List.of("unverified_value_critical:potassium"), ResultFixtures.BD), "why-unverified-critical");
        assertThat(block).contains(copy.t(Lang.BN, "now.why.unverified")).contains("/page/1").contains("/page/2")
                .doesNotContain("class=\"vrow");
    }

    /** F7, the dangerous direction: a phone outside Bangladesh is never handed tel:999, and the share text carries no value. */
    @ParameterizedTest
    @ValueSource(strings = {"+447700900123", "+971500000000", "+15555550123"})
    void aReaderAbroadIsNeverOfferedTheBangladeshEmergencyNumber(String e164) {
        String html = now(List.of("value_critical:potassium|stopgap"), ViewerLocale.of(e164));
        assertThat(html).doesNotContain("tel:");
        assertThat(ResultFixtures.section(html, "remote-action")).contains(copy.t(Lang.BN, "now.remote.999"))
                .contains(copy.t(Lang.BN, "now.remote.lead", "Ma")).contains(copy.t(Lang.BN, "now.remote.share"));
        String share = URLDecoder.decode(shareText(html), StandardCharsets.UTF_8);
        assertThat(share).contains("Ma").doesNotContain("999").doesNotContain("11.0").doesNotContain("mmol");
    }

    /** F7, the other direction: a Bangladeshi phone gets tel:999 once, and only under the red-flag questions. */
    @Test
    void aReaderInBangladeshGetsTheCallButtonOnlyUnderTheRedFlagQuestions() {
        String html = now(List.of("value_critical:potassium|stopgap"), ResultFixtures.BD);
        assertThat(html).containsOnlyOnce("href=\"tel:999\"");
        assertThat(ResultFixtures.section(html, "red-flags")).contains("href=\"tel:999\"").contains(copy.t(Lang.BN, "now.flags.q"))
                .contains(copy.t(Lang.BN, "now.call999"));
        assertThat(ResultFixtures.section(html, "urgency")).doesNotContain("tel:");
        assertThat(html).doesNotContain("id=\"remote-action\"");
    }

    @ParameterizedTest
    @EnumSource(value = Urgency.class, names = {"ROUTINE", "THIS_WEEK"})
    void belowNowThereIsNoEmergencyActionForAnyReader(Urgency level) {
        for (ViewerLocale viewer : List.of(ResultFixtures.BD, ResultFixtures.ABROAD)) {
            String html = kit.documents.summaryBody(Lang.BN, ResultFixtures.result(ResultFixtures.lab(List.of()), level,
                    level == Urgency.ROUTINE ? List.of() : List.of("follow_up_past_due"), GateState.NONE, null, viewer));
            assertThat(html).doesNotContain("tel:").doesNotContain("id=\"red-flags\"").doesNotContain("id=\"remote-action\"");
        }
    }

    private static String shareText(String html) {
        Matcher m = Pattern.compile("href=\"https://wa\\.me/\\?text=([^\"]+)\"").matcher(html);
        assertThat(m.find()).as("share link").isTrue();
        return m.group(1);
    }
}
```

- [ ] **Step 3: red, and the replays on the defect.** Compile errors (no `REASON_BLOCKS`). Then: remove
  `UNVERIFIED_VALUE_CRITICAL` from `REASON_BLOCKS` → `everyReasonTheRule…` red `expected: [VALUE_CRITICAL, …,
  UNVERIFIED_VALUE_CRITICAL, …] but was: [VALUE_CRITICAL, …]`; render the +880 action for every reader →
  `aReaderAbroad…[+447700900123] Expecting actual … not to contain "tel:"` (the defect F7 exists for); skip unknown
  prefixes → `block #why-other Expecting actual: -1 …`. A compile error is not a replay.

### Task 23.2: implement

**Files:** modify `ui/DocumentPages.java` (`REASON_BLOCKS`, `nowBody`), `ui/UiCopy.java` (+`raw(lang, key, args)` made
public for URL parameters: "for a URL parameter, never for HTML"), `css/60-result.css`.

- [ ] **Step 1:**

```java
    /** Reason → the block that renders it (spec §4.11). Every block renders even when no stored row matches its reason. */
    static final Map<UrgencyReason, String> REASON_BLOCKS = new EnumMap<>(Map.of(
            UrgencyReason.VALUE_CRITICAL, "why-value-critical",
            UrgencyReason.VALUE_OUTSIDE_RANGE, "why-value-outside",
            UrgencyReason.UNVERIFIED_VALUE_CRITICAL, "why-unverified-critical",
            UrgencyReason.UNVERIFIED_VALUE_OUTSIDE_RANGE, "why-unverified-outside",
            UrgencyReason.TEXT_EMERGENCY_PHRASE, "why-emergency-phrase",
            UrgencyReason.FOLLOW_UP_PAST_DUE, "why-follow-up",
            UrgencyReason.DIAGNOSIS_PRESENT, "why-diagnosis"));
```

  `summaryBody` puts `nowBody` directly after the banner when `r.urgency() == NOW`: for each distinct stored reason, in
  stored order, `UrgencyReason.ofReason(reason)` → its block, or `why-other` (`now.why.other` and every page image) when
  empty. `why-value-critical` (`now.why.critical`, the CRITICAL rows via `ValueRows.row`), `why-value-outside`
  (`value.group.outside`, the OUTSIDE rows; the page images when no stored row matches), `why-unverified-critical`
  (`now.why.unverified` and every page image `/app/documents/{id}/page/{n}` linking to itself, `crop.zoom`),
  `why-emergency-phrase` (`now.why.phrase` and the page images), `why-follow-up` (`now.why.followup` and the follow-up
  rows as paper quotes), `why-diagnosis` (the diagnosis quotes), `why-unverified-outside` (`now.why.unverified_outside`).
  Then the action: for `viewer.bangladesh()`, `<section id="red-flags" class="flags now">` with `now.flags.q`,
  `now.flags.go` and `<a class="btn now-outline" href="tel:999">` + `copy.t(lang, "now.call999")` + `</a>`; otherwise
  `<section id="remote-action" class="emergency">` with `now.remote.lead` (`{0}` = name), `now.remote.999`,
  `<a class="btn" href="https://wa.me/?text=` + `URLEncoder.encode(copy.raw(lang, "now.remote.share.text", name), UTF_8)`
  + `" rel="noopener">` + `copy.t(lang, "now.remote.share")` + `</a>`, `now.remote.share.note`, the red-flag questions
  with `now.remote.flags.go` and **no** tel link. Then `now.bring` / `now.bring.remote` and `now.notdoctor`. The normal
  summary continues after it (gate line, rows, doctor, medicines). No "কাছের মানুষ" call button exists until Phase 5
  stores that person.
- [ ] **Step 2: CSS:** `.emergency,.flags.now{background:var(--c-status-now-container);border-radius:var(--r-m);padding:var(--sp-4)}`,
  `.btn.now-outline{border:2px solid var(--c-status-now);color:var(--c-status-now);background:var(--c-surface);min-height:56px}`.
  Every red selector carries `.now` or `.emergency` (`DesignTokensTest`); `TokenContrastTest` derives the new pairs.
- [ ] **Step 3: run** `./gradlew :baymax:test`; standard pre-merge. Commit
  `feat(baymax-web): result-now — one block per reason, and no 999 for a phone abroad`.

### Try it (B3)

- [ ] **prod-web:** open t7 on Tanvir's +880 phone: the red header, the Potassium row in red under "এই মানটা রিপোর্টে লেখা
  সীমার অনেক বাইরে:", and "৯৯৯-এ ফোন করুন" only under "অজ্ঞান? শ্বাস নিতে কষ্ট? বুকে ব্যথা?".
- [ ] **prod-web:** t10 (printed "URGENT") shows the "কাগজে 'জরুরি' বা 'ভর্তি' লেখা আছে।" block and the page image.
- [ ] **local:** a second local family with `+447700900123` (admin API `POST /api/v1/baymax/families`), the same t7 for it,
  signed in as that number (P.2): no 999 button, "বিদেশের ফোন থেকে এই নম্বরে কল যায় না", and "বাড়ির কাউকে পাঠান" opens a
  WhatsApp share whose text has no value. (Not prod: signing in needs a foreign WhatsApp number.)

---

## PR B4 — F5: retake reason codes in Bangla, never the developer prose; the document-date timeline key (V20)

Branch `feat/baymax-retake-reason-codes`. Spec §2 F5, §4.12, §4.15. A retake stores **why** as a machine code at each of
the four retake paths (`DocumentExtractionService.java:92`, `:248`, `:257`, `:263` at `e6264a4`); the page maps the code
to Bangla copy; the English `status_reason` stays developer-facing and never reaches a page or the JSON API. V20 also
adds the generated `timeline_date` (the printed date, else the upload's Dhaka day) that C2's read model orders by.
A legacy retake whose prose matches none of the four becomes `legacy` in the backfill, so both CHECKs are **valid**
(no `NOT VALID` constraint that a later update of an old row could trip). Before merging, the retake gate's replay
harness runs (CLAUDE.md: rerun whenever the gate code changes): `scripts/replay-extraction-gate.py` against every
production document must print **zero decision changes** — only the reason gains a code — and the summary goes into the
PR body (Tanvir runs it read-only if it needs `.env.supabase`).

### Task 15.1: a code at each of the four retake paths, and V20

**Files:** create `extraction/domain/RetakeReason.java`, `V20__retake_reason_code_and_timeline_date.sql`, test
`extraction/adapter/out/persistence/DocumentReasonCodeConstraintTest.java`; modify `Document` (last component
`statusReasonCode`; the 17-argument constructor delegates with `null`; `withStatus(Status, String reason, String code, Instant)`),
`DocumentEntity` (`@Column("status_reason_code")`), `PostgresDocumentRecordAdapter.writeDocument` (binds it),
`DocumentExtractionService` (the four prose texts become `static final String` constants — `REASON_NO_TEXT`,
`REASON_LOW_OVERALL` (the prefix before `" (%.2f)"`), `REASON_SECTION_EMPTY` (`"nothing was read from the %s of this %s"`),
`REASON_LOW_SECTION` (the prefix) — and each `finish(...)` passes its code; `lowestPresentSection` returns
`SectionScore(String section, double confidence)` so the low-section path names the section), `DocumentExtractionServiceTest`.

- [ ] **Step 1: failing tests.** In `DocumentExtractionServiceTest`, each retake test asserts the field, not the prose:

```java
// lowOverallConfidenceNeedsARetakeAndWritesNoItems (:220-232), added line:
assertThat(document.statusReasonCode()).isEqualTo("low_overall");
// aWeakSectionAloneAlsoNeedsARetake (:235-243), now asserting the section the gate named:
.assertNext(d -> {
    assertThat(d.status()).isEqualTo(Document.Status.NEEDS_RETAKE);
    assertThat(d.statusReasonCode()).isEqualTo("low_section:values");
})
// aPrescriptionWithNoMedicinesIsStillRetaken (:360-378), added:
assertThat(d.statusReasonCode()).isEqualTo("expected_section_empty:medicines");
// the no-text test (:812), added:
assertThat(document.statusReasonCode()).isEqualTo("no_text");
// the other direction, in the DONE tests and the FAILED test: a code is never set off a retake
assertThat(d.statusReasonCode()).isNull();
```

  `DocumentReasonCodeConstraintTest` (Testcontainers; `runner()`, `sql`, `scalar` as before):

```java
    /** Each V20 constraint by name, both directions. */
    @Test
    void everyReasonCodeConstraintRefusesItsBadWriteAndAllowsItsGoodOne() throws Exception {
        runner().run(context -> {
            UUID doc = DocumentUrgencyNeverLowersTest.receivedDocument(context);
            assertThatThrownBy(() -> sql("UPDATE baymax.document SET status='NEEDS_RETAKE', status_reason_code='blurry' WHERE id='" + doc + "'"))
                    .hasMessageContaining("document_status_reason_code_check");
            assertThatThrownBy(() -> sql("UPDATE baymax.document SET status='DONE', status_reason_code='no_text' WHERE id='" + doc + "'"))
                    .hasMessageContaining("document_reason_code_only_on_retake");
            assertThatThrownBy(() -> sql("UPDATE baymax.document SET status='NEEDS_RETAKE', status_reason_code=NULL WHERE id='" + doc + "'"))
                    .hasMessageContaining("document_retake_has_reason_code");
            sql("UPDATE baymax.document SET status='NEEDS_RETAKE', status_reason_code='low_section:values' WHERE id='" + doc + "'");
            assertThat(scalar("SELECT status_reason_code FROM baymax.document WHERE id='" + doc + "'")).isEqualTo("low_section:values");
        });
    }

    /** The backfill, fed the code's own constants (so the SQL and the Java cannot drift), plus an unmatched legacy prose. */
    @Test
    void theBackfillMapsTheFourProseConstantsAndMarksAnythingElseLegacy() throws Exception {
        runner().run(context -> {
            Map<String, String> expected = new LinkedHashMap<>();
            expected.put(DocumentExtractionService.REASON_NO_TEXT, "no_text");
            expected.put(DocumentExtractionService.REASON_LOW_OVERALL + " (0.40)", "low_overall");
            expected.put(DocumentExtractionService.REASON_SECTION_EMPTY.formatted("medicines", "prescription"), "expected_section_empty:medicines");
            expected.put(DocumentExtractionService.REASON_LOW_SECTION + " (0.55)", "low_section:unknown");
            expected.put("an old reason from 2025", "legacy");
            // the table as it was before V20's constraints: drop both, write pre-V20 rows, run V20's own blocks again
            sql("ALTER TABLE baymax.document DROP CONSTRAINT document_reason_code_only_on_retake, DROP CONSTRAINT document_retake_has_reason_code");
            Map<String, UUID> rows = new LinkedHashMap<>();
            for (String prose : expected.keySet()) {
                UUID doc = DocumentUrgencyNeverLowersTest.receivedDocument(context);
                sql("UPDATE baymax.document SET status='NEEDS_RETAKE', status_reason='" + prose.replace("'", "''")
                        + "', status_reason_code=NULL WHERE id='" + doc + "'");
                rows.put(prose, doc);
            }
            sql(block("backfill"));
            sql(block("constraint"));   // ADD CONSTRAINT validates every row: a NULL code left by the backfill fails here
            for (Map.Entry<String, String> e : expected.entrySet()) {
                assertThat(scalar("SELECT status_reason_code FROM baymax.document WHERE id='" + rows.get(e.getKey()) + "'")).as(e.getKey()).isEqualTo(e.getValue());
            }
        });
    }

    /** The app's own writes to a backfilled legacy retake succeed: the constraint is valid and the row satisfies it. */
    @Test
    void anAppWriteToALegacyRetakeRowSucceeds() throws Exception {
        runner().run(context -> {
            UUID doc = DocumentUrgencyNeverLowersTest.receivedDocument(context);
            sql("UPDATE baymax.document SET status='NEEDS_RETAKE', status_reason='an old reason', status_reason_code='legacy' WHERE id='" + doc + "'");
            DocumentRecordPort documents = context.getBean(DocumentRecordPort.class);
            Document d = documents.find(doc).block();
            documents.update(d.withStatus(Document.Status.NEEDS_RETAKE, d.statusReason(), d.statusReasonCode(), Instant.now())).block();
            assertThat(scalar("SELECT status_reason_code FROM baymax.document WHERE id='" + doc + "'")).isEqualTo("legacy");
        });
    }

    /** The timeline key: the printed date, else the Dhaka day of the upload (F7) — generated, so sort and label agree. */
    @Test
    void theTimelineDateIsThePrintedDateElseTheDhakaUploadDay() throws Exception {
        runner().run(context -> {
            UUID dated = DocumentUrgencyNeverLowersTest.receivedDocument(context);
            UUID undated = DocumentUrgencyNeverLowersTest.receivedDocument(context);
            sql("UPDATE baymax.document SET doc_date='2026-07-12' WHERE id='" + dated + "'");
            sql("UPDATE baymax.document SET doc_date=NULL, created_at='2026-10-18T19:30:00Z' WHERE id='" + undated + "'");   // 01:30 on the 19th in Dhaka
            assertThat(scalar("SELECT timeline_date FROM baymax.document WHERE id='" + dated + "'")).isEqualTo("2026-07-12");
            assertThat(scalar("SELECT timeline_date FROM baymax.document WHERE id='" + undated + "'")).isEqualTo("2026-10-19");
        });
    }
```

  (`block(name)` reads the text between `-- <name>:begin` and `-- <name>:end` of V20 from the classpath, as A10's test
  reads V18's backfill; `receivedDocument` is a static helper beside `doneDocument` that leaves the document RECEIVED.)
- [ ] **Step 2: red.** `expected: "expected_section_empty:medicines" but was: null`; with the CHECKs removed from V20,
  `Expecting code to raise a throwable` for each constraint; with the UTC expression
  `(created_at AT TIME ZONE 'UTC')::date`, `expected: "2026-10-19" but was: "2026-10-18"`.
- [ ] **Step 3: V20.**

```sql
-- F5 (Phase 1b, B4): why a document needs a retake, as a machine code mapped to Bangla copy. status_reason stays
-- developer-facing English and is never rendered. 'legacy' marks a pre-V20 retake whose prose matched no known form.
ALTER TABLE ${flyway:defaultSchema}.document ADD COLUMN status_reason_code varchar(48) NULL
    CONSTRAINT document_status_reason_code_check
    CHECK (status_reason_code ~ '^(no_text|low_overall|legacy|expected_section_empty:[a-z_]+|low_section:[a-z_]+)$');

-- backfill:begin
UPDATE ${flyway:defaultSchema}.document SET status_reason_code = CASE
    WHEN status_reason = 'no readable text found on the page' THEN 'no_text'
    WHEN status_reason LIKE 'the document was not read confidently enough%' THEN 'low_overall'
    WHEN status_reason ~ '^nothing was read from the [a-z_]+ of this '
        THEN 'expected_section_empty:' || substring(status_reason FROM '^nothing was read from the ([a-z_]+) of this ')
    WHEN status_reason LIKE 'part of the document was not read confidently enough%' THEN 'low_section:unknown'
    ELSE 'legacy'
END
WHERE status = 'NEEDS_RETAKE'
-- backfill:end
;

-- constraint:begin
ALTER TABLE ${flyway:defaultSchema}.document
    ADD CONSTRAINT document_reason_code_only_on_retake CHECK (status_reason_code IS NULL OR status = 'NEEDS_RETAKE'),
    ADD CONSTRAINT document_retake_has_reason_code CHECK (status <> 'NEEDS_RETAKE' OR status_reason_code IS NOT NULL)
-- constraint:end
;

-- The timeline key (§4.15: newest DOCUMENT date first). An upload with no printed date uses its upload day in Dhaka,
-- the patient's calendar (F7). Generated, so the date a page shows and the order it sorts by can never disagree.
ALTER TABLE ${flyway:defaultSchema}.document ADD COLUMN timeline_date date
    GENERATED ALWAYS AS (coalesce(doc_date, (created_at AT TIME ZONE 'Asia/Dhaka')::date)) STORED;
CREATE INDEX document_patient_timeline_idx
    ON ${flyway:defaultSchema}.document (patient_id, timeline_date DESC, created_at DESC, id DESC);
```

  `RetakeReason` (with `LEGACY("legacy", false)`):

```java
package com.elioo.baymax.extraction.domain;

/** F5: why a document needs a retake, as the code stored in status_reason_code. Copy is keyed by {@link #prefix}. */
public enum RetakeReason {
    NO_TEXT("no_text", false), LOW_OVERALL("low_overall", false), LEGACY("legacy", false),
    EXPECTED_SECTION_EMPTY("expected_section_empty", true), LOW_SECTION("low_section", true);

    private final String prefix;
    private final boolean hasSection;

    RetakeReason(String prefix, boolean hasSection) { this.prefix = prefix; this.hasSection = hasSection; }

    public String prefix() { return prefix; }

    public String code(String section) {
        if (hasSection != (section != null)) {
            throw new IllegalArgumentException(prefix + (hasSection ? " needs a section" : " takes no section"));
        }
        return hasSection ? prefix + ":" + section : prefix;
    }

    public static Optional<RetakeReason> ofCode(String code) {
        if (code == null) { return Optional.empty(); }
        String p = code.contains(":") ? code.substring(0, code.indexOf(':')) : code;
        return Arrays.stream(values()).filter(r -> r.prefix.equals(p)).findFirst();
    }
}
```

- [ ] **Step 4: run** → green; then the gate replay harness (above) with zero decision changes.

### Task 15.2: the family never sees `status_reason` — not on a page, not in the JSON

**Files:** create tests `extraction/domain/DocumentViewNeverCarriesProseTest.java`, `ui/RetakeReasonsHaveCopyTest.java`,
`web/adapter/in/handler/NoProseInTheJsonApiTest.java`; modify `DocumentView` (`reason` → `reasonCode`; `pending()` passes
`statusReasonCode()`), `TimelineApiHandler.entry` and the document JSON (both write `retake_reason_code`, never
`status_reason`), `ui/DocumentPages.render` (the interim retake line), `DocumentPagesRenderTest` (`:36-43`, rewritten),
`DocumentIntakeServiceTest.java:164-168`, `ui-result_*`.

- [ ] **Step 1: failing tests.**

```java
package com.elioo.baymax.extraction.domain;

/** F5: for every prose the retake paths write (the code's own constants), no component of the family's view carries it. */
class DocumentViewNeverCarriesProseTest {

    static Stream<Arguments> everyRetakeProse() {
        return Stream.of(
                Arguments.of(DocumentExtractionService.REASON_NO_TEXT, "no_text"),
                Arguments.of(DocumentExtractionService.REASON_LOW_OVERALL + " (0.40)", "low_overall"),
                Arguments.of(DocumentExtractionService.REASON_SECTION_EMPTY.formatted("values", "lab_report"), "expected_section_empty:values"),
                Arguments.of(DocumentExtractionService.REASON_LOW_SECTION + " (0.55)", "low_section:values"));
    }

    @ParameterizedTest
    @MethodSource("everyRetakeProse")
    void noComponentOfARetakeViewCarriesTheProse(String prose, String code) throws Exception {
        Document d = new Document(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, null, null, null, 0.4,
                Document.Status.NEEDS_RETAKE, prose, null, null, 1, Instant.EPOCH, Instant.EPOCH, null, null, code);
        DocumentView view = DocumentView.pending(d);
        RecordComponent[] components = DocumentView.class.getRecordComponents();
        assertThat(components).as("record components").hasSizeGreaterThanOrEqualTo(14);
        for (RecordComponent c : components) {
            assertThat(String.valueOf(c.getAccessor().invoke(view))).as(c.getName()).doesNotContain(d.statusReason());
        }
        assertThat(view.reasonCode()).isEqualTo(code);
    }
}
```

  `NoProseInTheJsonApiTest`: the timeline JSON (`TimelineApiHandler`) and the document JSON (`DocumentHandler`'s GET),
  each bound with `WebTestClient.bindToRouterFunction(...)` and mocked use cases returning a NEEDS_RETAKE document whose
  `statusReason` is `REASON_LOW_OVERALL + " (0.40)"`; the response is parsed with Jackson and **every string value** in
  the tree is walked (a recursive visit over `JsonNode.elements()`/`fields()`), asserting none contains the prose and
  that a field named `retake_reason_code` equals `low_overall` — a field check, not a substring of the document.
  `RetakeReasonsHaveCopyTest`: for every `RetakeReason` (at least 5) a `retake.reason.<prefix>` key exists in both
  languages. The rewritten `DocumentPagesRenderTest` test:

```java
/** F5: a retake shows the Bangla line for its code and the page image; the developer prose never reaches the page. */
@Test
void aRetakeRendersItsReasonCodeInBanglaAndThePageImageOnly() {
    DocumentView view = new DocumentView(id.toString(), "NEEDS_RETAKE", "low_overall",
            null, null, null, null, null, 1, null, null, null, null, null);
    String html = kit.documents.render(Lang.BN, view, id, java.util.Optional.empty());
    assertThat(html).contains("এই ছবিটা থেকে ঠিকমতো পড়তে পারিনি — আন্দাজে কিছু বলব না।")
            .contains("পুরো ছবিটা ঝাপসা").contains("/documents/" + id + "/page/1");
    assertThat(html).doesNotContain("low_overall").doesNotContain("not read confidently");
    assertThat(html).doesNotContain("<h2>মান</h2>").doesNotContain("<h2>ওষুধ</h2>").doesNotContain("crop-");
}
```

- [ ] **Step 2: red.** The rewritten render test: `Expecting actual … to contain "পুরো ছবিটা ঝাপসা"`; once the copy exists
  but the old `warn` line is still in place, `… not to contain "low_overall"` (the raw code on the page). The prose test,
  replayed with `pending()` passing `statusReason()`: `[reasonCode] Expecting actual "the document was not read
  confidently enough (0.40)" not to contain …`. The JSON test, replayed with the API still writing `status_reason`:
  red naming the JSON path `$.entries[0].status_reason`.
- [ ] **Step 3: implement.** The interim retake line in `DocumentPages.render` (B5 replaces it):

```java
if ("NEEDS_RETAKE".equals(view.status())) {
    b.append("<div class=\"card retake\"><p><strong>").append(copy.t(lang, "retake.head")).append("</strong></p>");
    RetakeReason.ofCode(view.reasonCode()).ifPresent(r -> {
        String section = view.reasonCode().contains(":") ? view.reasonCode().substring(view.reasonCode().indexOf(':') + 1) : null;
        String specific = section == null ? null : copy.maybe(lang, "retake.reason." + r.prefix() + "." + section);
        b.append("<p>").append(specific != null ? specific : copy.t(lang, "retake.reason." + r.prefix())).append("</p>");
    });
    b.append("<p>").append(copy.t(lang, "doc.retake")).append("</p></div>");
}
```

  Copy (`ui-result_*`; the spec §4.12 strings as written; **(proposed)** ones go to the PO and ship as drafts):
  `retake.head=এই ছবিটা থেকে ঠিকমতো পড়তে পারিনি — আন্দাজে কিছু বলব না।`, `retake.reason.no_text=ছবিতে লেখা খুঁজে পাইনি`,
  `retake.reason.low_overall=পুরো ছবিটা ঝাপসা`, `retake.reason.expected_section_empty=কাগজের একটা দরকারি অংশ পড়া যায়নি` **(proposed)**,
  `retake.reason.expected_section_empty.values=মানগুলোর অংশটা পড়া যায়নি`, `retake.reason.expected_section_empty.medicines=ওষুধের অংশটা পড়া যায়নি` **(proposed)**,
  `retake.reason.low_section=ছবির একটা অংশ ঝাপসা` **(proposed)**, `retake.reason.legacy=ছবিটা পড়া যায়নি` **(proposed)**,
  `doc.retake=আবার ছবি তুলুন` (exists), and English equivalents.
- [ ] **Step 4: run** `./gradlew :baymax:test`, `./gradlew build`; standard pre-merge with the migration preflight
  (`V19__observation_value_verdict.sql`). Commit `feat(baymax): F5 retake reason codes in Bangla, never the developer prose; the document-date key (V20)`.

### Try it (B4) — prod-web

- [ ] A photo of a blank wall: "এই ছবিটা থেকে ঠিকমতো পড়তে পারিনি — আন্দাজে কিছু বলব না।" and "ছবিতে লেখা খুঁজে পাইনি";
  no English, no number.
- [ ] **t8** (blurred): "পুরো ছবিটা ঝাপসা" (or the section line), the page image and "আবার ছবি তুলুন".
- [ ] Toggle English: the same reasons in English; "not read confidently" appears nowhere.

---

## PR B5 — 2.5: needs-retake (s12) — the reason in Bangla, a retake for the same patient, and it never counts

Branch `feat/baymax-needs-retake`. Spec §4.12, RetakeCard README, DR-24. A NEEDS_RETAKE page is a warm warning card
(never amber or red: a retake is not urgency) with B4's Bangla reason, the photo that was sent, the tips, and "আবার
তুলুন" — a form that posts to **`/app/add`** with the same patient preselected (hidden `patient` field), camera first,
working without script. From the third retake in a row for this patient, "অন্য কাগজ পাঠান" leads. A retake never uses
the month's free allowance (DR-24: only DONE counts), proven through `FreeTierUseCase` on a fixed clock. FAILED gets its
own honest page ("আমাদের দিকে একটা সমস্যা"). A view-only share member is told why and gets no form.

### Task 25.1: the streak, on real Postgres, and the allowance a retake does not use

**Files:** create `extraction/application/port/out/DocumentReadPort.java`,
`extraction/adapter/out/persistence/PostgresDocumentReadAdapter.java`, test
`extraction/adapter/out/persistence/RetakeOrdinalAndFreeTierTest.java`; modify `ResultService` (`DocumentReadPort reads`
appended last; ordinal = `reads.retakeOrdinal(d.id())` for NEEDS_RETAKE, else 0), `ResultServiceTest`.

- [ ] **Step 1: failing tests (both directions).**

```java
package com.elioo.baymax.extraction.adapter.out.persistence;

/** The retake streak is per patient and resets on a read paper; a retake never uses the family's free allowance (DR-24). */
@Testcontainers
class RetakeOrdinalAndFreeTierTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    /** In October 2026, and the clock the context runs on: the month counted is this one, not the month the test runs. */
    private static final Instant T = Instant.parse("2026-10-20T04:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T.plusSeconds(3600), ZoneOffset.UTC);
    private static final AtomicInteger FAMILIES = new AtomicInteger();

    record Fixture(ApplicationContext context, UUID familyId, UUID ma, UUID baba) {
        static Fixture create(ApplicationContext context) {
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            FamilyAccount f = records.createFamily(new FamilyAccount(null, "+88017000008" + String.format("%02d", FAMILIES.incrementAndGet()),
                    "Rahim", FamilyAccount.Plan.FREE, T, T)).block();
            UUID ma = records.createPatient(new PatientProfile(null, f.id(), "Ma", 68, PatientProfile.Sex.FEMALE, List.of(), T, T)).block().id();
            UUID baba = records.createPatient(new PatientProfile(null, f.id(), "Baba", 72, PatientProfile.Sex.MALE, List.of(), T, T)).block().id();
            return new Fixture(context, f.id(), ma, baba);
        }

        UUID doc(UUID patient, Document.Status status, Instant at) {
            DocumentRecordPort docs = context.getBean(DocumentRecordPort.class);
            Document d = docs.create(Document.received(patient, familyId, 1, at)).block();
            String code = status == Document.Status.NEEDS_RETAKE ? "low_overall" : null;
            return docs.update(d.withStatus(status, status == Document.Status.DONE ? null : "test", code, at)).block().id();
        }
    }

    private ReactiveWebApplicationContextRunner runner() {
        return PostgresNudgeAdapterTest.runnerFor(POSTGRES).withBean(Clock.class, () -> CLOCK)
                .withPropertyValues("baymax.free.max-docs-per-month=2", "baymax.free.max-patients=2");
    }

    @Test
    void theStreakCountsThisPatientsRetakesSinceTheirLastReadPaper() {
        runner().run(context -> {
            DocumentReadPort reads = context.getBean(DocumentReadPort.class);
            Fixture f = Fixture.create(context);
            UUID r1 = f.doc(f.ma(), Document.Status.NEEDS_RETAKE, T);
            UUID done = f.doc(f.ma(), Document.Status.DONE, T.plusSeconds(60));
            UUID r2 = f.doc(f.ma(), Document.Status.NEEDS_RETAKE, T.plusSeconds(120));
            UUID babasRetake = f.doc(f.baba(), Document.Status.NEEDS_RETAKE, T.plusSeconds(150));
            UUID r3 = f.doc(f.ma(), Document.Status.NEEDS_RETAKE, T.plusSeconds(180));

            assertThat(reads.retakeOrdinal(r1).block()).isEqualTo(1);
            assertThat(reads.retakeOrdinal(r2).block()).isEqualTo(1);   // the DONE paper between ends the streak
            assertThat(reads.retakeOrdinal(r3).block()).isEqualTo(2);   // Baba's retake does not join Ma's streak
            assertThat(reads.retakeOrdinal(done).block()).isEqualTo(0);
            assertThat(reads.retakeOrdinal(babasRetake).block()).isEqualTo(1);
        });
    }

    /** Through FreeTierUseCase on the fixed clock: retakes are free, read papers count — and the month really holds them. */
    @Test
    void retakesNeverUseTheAllowanceAndReadPapersDo() {
        runner().run(context -> {
            FreeTierUseCase freeTier = context.getBean(FreeTierUseCase.class);
            DocumentRecordPort docs = context.getBean(DocumentRecordPort.class);
            Fixture f = Fixture.create(context);
            f.doc(f.ma(), Document.Status.DONE, T);
            for (int i = 1; i <= 3; i++) {
                f.doc(f.ma(), Document.Status.NEEDS_RETAKE, T.plusSeconds(i * 60L));
            }
            assertThat(docs.countInMonth(f.familyId(), YearMonth.of(2026, 10)).block()).as("the fixture is in the counted month").isEqualTo(1L);
            StepVerifier.create(freeTier.checkCanUploadDocument(f.familyId())).verifyComplete();

            f.doc(f.ma(), Document.Status.DONE, T.plusSeconds(600));
            StepVerifier.create(freeTier.checkCanUploadDocument(f.familyId())).expectError(FreeTierExceededException.class).verify();
        });
    }

    /** One concept, two counters: for a one-patient family (WhatsApp's case) the web's streak equals WhatsApp's count. */
    @Test
    void forOnePatientTheWebStreakAgreesWithWhatsAppsConsecutiveRetakes() {
        runner().run(context -> {
            Fixture f = Fixture.create(context);
            UUID last = null;
            for (int i = 0; i < 3; i++) {
                last = f.doc(f.ma(), Document.Status.NEEDS_RETAKE, T.plusSeconds(i * 60L));
                context.getBean(ExplainDocumentUseCase.class).explain(last).onErrorResume(e -> Mono.empty()).block();
            }
            long whatsapp = context.getBean(OutboundMessagePort.class).consecutiveRetakes(f.familyId()).block();
            assertThat(context.getBean(DocumentReadPort.class).retakeOrdinal(last).block()).isEqualTo((int) whatsapp);
        });
    }
}
```

  (`PostgresNudgeAdapterTest.runnerFor(container)` is that test's `runner()` extracted as a static factory, unchanged.)
  The third test documents a known difference in the PR body: for a family with **two** patients WhatsApp's count is
  family-wide (`PostgresOutboundMessageAdapter.consecutiveRetakes`) and the web's is per patient; the PO decides whether
  WhatsApp follows (proposal: per patient, the case the retake card is about).
- [ ] **Step 2: red.** Stub `retakeOrdinal` first (an `AbstractMethodError` is not a replay). Replay with a family-wide
  count (the semantics of `consecutiveRetakes`): `expected: 2 but was: 3` at `r3`, Baba's retake joining Ma's streak.
  The free-tier test guards existing behaviour: replay it with `countInWindow` (every status) in
  `FreeTierService.java:50` → `expected: complete but got FreeTierExceededException`.
- [ ] **Step 3: implement.**

```java
package com.elioo.baymax.extraction.application.port.out;

/** Read-side questions about documents that the write port does not answer (B5, B6). */
public interface DocumentReadPort {

    /** 1, 2, 3… for a NEEDS_RETAKE document (this patient's streak up to and including it); 0 for any other status. */
    Mono<Integer> retakeOrdinal(UUID documentId);
}
```

```java
    @Override
    public Mono<Integer> retakeOrdinal(UUID documentId) {
        return db.sql("SELECT CASE WHEN me.status <> 'NEEDS_RETAKE' THEN 0 ELSE (SELECT count(*) FROM " + S + ".document d "
                        + "WHERE d.patient_id = me.patient_id AND d.status = 'NEEDS_RETAKE' AND d.created_at <= me.created_at "
                        + "AND d.created_at > coalesce((SELECT max(x.created_at) FROM " + S + ".document x WHERE x.patient_id = me.patient_id "
                        + "AND x.status IN ('DONE', 'FAILED') AND x.created_at < me.created_at), '-infinity'::timestamptz)) END AS n "
                        + "FROM " + S + ".document me WHERE me.id = :id")
                .bind("id", documentId)
                .map((row, meta) -> row.get("n", Long.class).intValue()).one().defaultIfEmpty(0);
    }
```

### Task 25.2: the retake and failed screens

**Files:** modify `ui/DocumentPages.java` (`retakeBody`, `failedBody`; `documentPage` sends NEEDS_RETAKE and FAILED to
them; B4's interim line removed), `DocumentPagesRenderTest` (B4's rewritten retake test moves to `NeedsRetakeRenderTest`),
`ResultFixtures` (+`retake(code, ordinal)`, `failed()`), create test `ui/NeedsRetakeRenderTest.java`,
`WebOnlyJourneyAcceptanceTest` (+ the retake step), `css/60-result.css`, `ui-result_*`.

- [ ] **Step 1: copy** (s12; the escalation reuses `messages_bn` `retake.second`/`retake.third` wording):

```properties
retake.title=কাগজটা আবার তুলতে হবে
retake.title.third=এই কাগজটা পড়া কঠিন হচ্ছে
retake.lead=এই ছবিটা থেকে ঠিকমতো পড়তে পারিনি — আন্দাজে কিছু বলব না।
retake.photo=আপনার পাঠানো ছবি · পাতা {0}
retake.tip.1=দিনের আলোয় তুলুন
retake.tip.2=কাগজটা সমান করে রাখুন
retake.tip.3=ফোনটা ঠিক উপর থেকে ধরুন, যেন পুরো পাতা ফ্রেমে থাকে
retake.third.p=অন্য কোনো রিপোর্ট বা প্রেসক্রিপশন থাকলে সেটা পাঠান — সেটা দিয়েই শুরু করি। এই কাগজটা রেখে দিন; পরে দিনের আলোয় আরেকবার চেষ্টা করলে হয়ে যেতে পারে।
retake.again=আবার তুলুন
retake.again.same=এই কাগজটাই আবার তুলুন
retake.same_patient=যাবে: {0}
retake.other=অন্য কাগজ পাঠান
retake.later=এখন থাক — কাগজপত্রে ফিরুন
retake.free=এটা আপনার দোষ নয় — আর এটা গোনা হবে না
retake.nothing_kept=এই ছবি থেকে কোনো মান রাখা হয়নি, তাই এটা নিয়ে কোনো প্রশ্নের উত্তরও দেওয়া হবে না।
retake.viewonly=শুধু যিনি এই প্রোফাইল খুলেছেন তিনি নতুন ছবি পাঠাতে পারেন।
failed.title=কাগজটা পড়া যায়নি
failed.p=পড়তে গিয়ে আমাদের দিকে একটা সমস্যা হয়েছে — আপনার ছবির দোষ নয়। আবার পাঠালে আবার চেষ্টা করব।
failed.again=আবার পাঠান
```

  The drawing's "আম্মার নামেই যাবে" needs D2's possessive; `retake.same_patient=যাবে: {0}` keeps the name standalone until
  then. `retake.lead` duplicates B4's `retake.head` — keep one: `retake.head` (B4) is the key; drop `retake.lead`.
- [ ] **Step 2: failing tests.**

```java
package com.elioo.baymax.web.adapter.in.ui;

/** s12 / RetakeCard / F5: the Bangla reason from the code, the photo, a retake for the same patient — never English. */
class NeedsRetakeRenderTest {

    private final WebUiTestKit kit = new WebUiTestKit();
    private final UiCopy copy = kit.copy;

    private String retake(String code, int ordinal, boolean owner) {
        return kit.documents.retakeBody(Lang.BN, ResultFixtures.retake(code, ordinal, owner));
    }

    @ParameterizedTest
    @CsvSource({"no_text,retake.reason.no_text", "low_overall,retake.reason.low_overall",
            "expected_section_empty:values,retake.reason.expected_section_empty.values", "low_section:values,retake.reason.low_section",
            "legacy,retake.reason.legacy"})
    void aRetakeShowsTheBanglaReasonForItsCode(String code, String key) {
        assertThat(ResultFixtures.section(retake(code, 1, true), "retake")).contains(copy.t(Lang.BN, "retake.head")).contains(copy.t(Lang.BN, key));
    }

    /** Replaces the pre-F5 render test that asserted the developer English reached the family (spec §0 row 4). */
    @Test
    void aRetakeShowsTheBanglaReasonAndNeverTheDeveloperReasonOrAnUrgency() {
        String html = retake("low_overall", 1, true);
        assertThat(html).doesNotContain("not read confidently").doesNotContain("low_overall").contains("/documents/" + ResultFixtures.DOC + "/page/1");
        assertThat(html).doesNotContain("class=\"vrow").doesNotContain("id=\"urgency\"").doesNotContain("status-soon").doesNotContain("class=\"banner");
    }

    /** "আবার তুলুন" opens the one upload entry for THIS patient, camera first, without script. */
    @Test
    void theRetakeButtonUploadsForTheSamePatientThroughTheOneEntry() {
        String form = ResultFixtures.section(retake("no_text", 1, true), "retake-form");
        assertThat(form).contains("method=\"post\"").contains("enctype=\"multipart/form-data\"").contains("action=\"/app/add\"")
                .contains("<input type=\"hidden\" name=\"patient\" value=\"" + ResultFixtures.MA.id() + "\">")
                .contains("capture=\"environment\"").contains(copy.t(Lang.BN, "retake.again"));
        assertThat(retake("no_text", 1, true)).contains(copy.t(Lang.BN, "retake.free"));
    }

    /** A view-only share member is told why, and gets no form that would 403. */
    @Test
    void aViewOnlyMemberGetsNoUploadForm() {
        assertThat(retake("no_text", 1, false)).doesNotContain("id=\"retake-form\"").contains(copy.t(Lang.BN, "retake.viewonly"));
    }

    /** RetakeCard third state: "অন্য কাগজ পাঠান" becomes primary, "এই কাগজটাই আবার তুলুন" goes quiet; before that, the reverse. */
    @Test
    void theThirdRetakeLeadsWithAnotherPaper() {
        String first = retake("low_overall", 1, true);
        String third = retake("low_overall", 3, true);
        assertThat(first.indexOf(copy.t(Lang.BN, "retake.again"))).isLessThan(first.indexOf(copy.t(Lang.BN, "retake.other")));
        assertThat(third.indexOf(copy.t(Lang.BN, "retake.other"))).isLessThan(third.indexOf(copy.t(Lang.BN, "retake.again.same")));
        assertThat(third).contains(copy.t(Lang.BN, "retake.title.third"));
    }

    @Test
    void aFailedPaperSaysItWasOurSideAndOffersToSendAgainForTheSamePatient() {
        String html = kit.documents.failedBody(Lang.BN, ResultFixtures.failed());
        assertThat(html).contains(copy.t(Lang.BN, "failed.p")).contains(copy.t(Lang.BN, "failed.again")).contains("action=\"/app/add\"")
                .doesNotContain("Exception").doesNotContain("error=");
    }
}
```

  In `WebOnlyJourneyAcceptanceTest` the stub answers the **second** extraction with `confidence.overall` 0.40 (the
  `StubbedProviders` reply queue: extraction calls take the next reply, every other model call gets a short Bangla
  sentence), and the journey gains: upload again → the reading page → DONE becomes NEEDS_RETAKE → `GET /app/documents/{id}`
  shows `#retake` with `retake.reason.low_overall` → the `#retake-form` posts to `/app/add` with the patient → a new
  document. Its `NOT_IN_JOURNEY` list does not change (no new route).
- [ ] **Step 3: red.** Against B4's interim page: `block #retake Expecting actual: -1 …`; the form test against a form
  posting to `/app/patients/{pid}/upload`: `Expecting … to contain "action=\"/app/add\""`; `UploadEntryPointsTest` also
  goes red on that mutant (`… form → /app/patients/…/upload` is allowed, so make the mutant post to `/app/timeline` to
  see it: `GET /app/documents/{id} bn form → /app/timeline`).
- [ ] **Step 4: implement.** `<section id="retake" class="retake">` (a warn-container card: `--c-warn-container`,
  `--c-warn-line`) with the camera icon, `retake.head`, the reason (`copy.t(lang, "retake.reason." + prefix + "." + section)`
  if that key exists, else `retake.reason.<prefix>`), the page images (`retake.photo`), the tips (tip 1 for the first
  retake, 1–3 from the second). Then, for `r.owner()`:
  `<form id="retake-form" method="post" enctype="multipart/form-data" action="/app/add">` with
  `<input type="hidden" name="patient" value="{pid}">`, `<input type="file" name="file" accept="image/*,application/pdf" capture="environment" required>`
  and the submit `retake.again`; ordinal ≥ 3 reorders: `retake.other` first (a second form to `/app/add` without
  `capture`, so the gallery opens), then `retake.again.same` (quiet), then `retake.later` (link to `/app/timeline?patient=…`).
  `retake.free` and `retake.nothing_kept` close the card. `failedBody` renders `failed.title`, `failed.p` and the same
  form with `failed.again`. Both land on A9's reading page through `/app/add`. CSS:
  `.retake{background:var(--c-warn-container);border:1px solid var(--c-warn-line);border-radius:var(--r-m);padding:var(--sp-4)}`.
- [ ] **Step 5: run** `./gradlew :baymax:test` (including `WebOnlyJourneyAcceptanceTest`); standard pre-merge. Commit
  `feat(baymax-web): needs-retake — the reason in Bangla, a retake for the same patient, never counted`.

### Try it (B5) — prod-web

- [ ] Send **t8** (blurred): the card says "এই ছবিটা থেকে ঠিকমতো পড়তে পারিনি…" with a Bangla reason, no English.
- [ ] Tap "আবার তুলুন": the camera opens; a clear photo goes to the same patient; the reading page, then the result.
- [ ] t8 three times in a row: the third card leads with "অন্য কাগজ পাঠান".
- [ ] The add sheet's allowance line is the same before and after the three retakes.

---

## PR B6 — 2.6: document detail (s17) and the value sheet (s13) — every fact beside its crop

Branch `feat/baymax-document-detail`. Spec §4.16, §4.17. `/app/documents/{id}/all` shows everything on the paper:
the stored banner, segment anchors (no script), every value grouped by verdict, every medicine crop first, every doctor
line quoted, and the full pages ("বড় করে দেখুন" opens the page image itself, where the browser pinch-zooms).
`/app/documents/{id}/values/{obs}` is the value sheet as a plain URL: the crop large, the value as printed, the range,
the chip, the bar only through `ValueRows.pin`, the "could not match" sentence for UNKNOWN, and earlier readings of the
**same marker and the same unit** for **this patient only**. The summary now collapses inside rows behind
"আরও {0}টি মান সীমার মধ্যে — সব দেখুন" linking `/all#values`, and every value row links to its sheet.

### Task 26.1: earlier readings — same marker AND folded unit, this patient only, on real Postgres

**Files:** create `web/domain/EarlierReading.java`, test `extraction/adapter/out/persistence/EarlierReadingsTest.java`;
modify `DocumentReadPort` (+`readingsOf`), `PostgresDocumentReadAdapter`, `ResultUseCase`/`ResultService`
(+`earlierReadings`).

- [ ] **Step 1: failing tests (both directions written first).**

```java
package com.elioo.baymax.extraction.adapter.out.persistence;

/**
 * The value sheet's history. Same marker AND same unit (a mg/dL reading beside a µmol/L one is a different number),
 * where "same unit" survives MICRO SIGN vs GREEK MU (CLAUDE.md: 8 of 9 "misses" in batch 2 were that fold), and never
 * crosses patients.
 */
@Testcontainers
class EarlierReadingsTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final Instant T = Instant.parse("2026-10-20T04:00:00Z");

    /** A family with two patients; doneWith() writes a DONE document holding one observation row and returns its id. */
    record Fixture(ApplicationContext context, UUID familyId, UUID ma, UUID baba) {
        UUID doneWith(UUID patient, Instant at, String canonical, String name, String value, String unit) throws Exception {
            DocumentRecordPort docs = context.getBean(DocumentRecordPort.class);
            UUID doc = docs.update(docs.create(Document.received(patient, familyId, 1, at)).block()
                    .withStatus(Document.Status.DONE, null, at)).block().id();
            sql("INSERT INTO baymax.observation (patient_id, document_id, name, canonical_name, value, unit, crop_key, observed_at) VALUES ('"
                    + patient + "','" + doc + "','" + name + "','" + canonical + "','" + value + "','" + unit + "','k/c.jpg','" + at + "')");
            return doc;
        }

        UUID observationIn(UUID doc) throws Exception {
            return UUID.fromString(scalar("SELECT id FROM baymax.observation WHERE document_id = '" + doc + "'"));
        }
    }

    @Test
    void earlierReadingsMatchMarkerAndFoldedUnitForThisPatientOnly() {
        runner().run(context -> {
            Fixture f = fixture(context, "+8801700000703");
            UUID now = f.doneWith(f.ma(), T.plusSeconds(300), "creatinine", "Creatinine", "160", "µmol/L");       // MICRO SIGN
            UUID earlier = f.doneWith(f.ma(), T.plusSeconds(200), "creatinine", "S. Creatinine", "140", "μmol/L"); // GREEK MU
            f.doneWith(f.ma(), T.plusSeconds(100), "creatinine", "Creatinine", "1.6", "mg/dL");                     // another unit
            f.doneWith(f.baba(), T.plusSeconds(150), "creatinine", "Creatinine", "150", "µmol/L");                  // another patient

            List<EarlierReading> rows = context.getBean(ResultUseCase.class)
                    .earlierReadings(f.familyId(), now, f.observationIn(now)).collectList().block();

            assertThat(rows).extracting(EarlierReading::documentId).containsExactly(earlier);
            assertThat(rows).extracting(EarlierReading::value).containsExactly("140");
        });
    }

    /** The dangerous direction: an observation id from another document cannot open a sheet on this one. */
    @Test
    void anObservationFromAnotherDocumentIsNotFound() {
        runner().run(context -> {
            Fixture f = fixture(context, "+8801700000704");
            UUID a = f.doneWith(f.ma(), T, "hba1c", "HbA1c", "8.4", "%");
            UUID b = f.doneWith(f.ma(), T.plusSeconds(60), "hba1c", "HbA1c", "7.9", "%");
            StepVerifier.create(context.getBean(ResultUseCase.class).earlierReadings(f.familyId(), a, f.observationIn(b)).collectList())
                    .expectErrorSatisfies(e -> assertThat(((BaymaxException) e).status()).isEqualTo(HttpStatus.NOT_FOUND)).verify();
        });
    }

    /** And a family that cannot see the document gets 404, whatever observation it names. */
    @Test
    void anotherFamilyGetsNotFound() {
        runner().run(context -> {
            Fixture mine = fixture(context, "+8801700000705");
            Fixture theirs = fixture(context, "+8801700000706");
            UUID doc = mine.doneWith(mine.ma(), T, "hba1c", "HbA1c", "8.4", "%");
            StepVerifier.create(context.getBean(ResultUseCase.class).earlierReadings(theirs.familyId(), doc, mine.observationIn(doc)).collectList())
                    .expectErrorSatisfies(e -> assertThat(((BaymaxException) e).status()).isEqualTo(HttpStatus.NOT_FOUND)).verify();
        });
    }
    // runner(): PostgresNudgeAdapterTest.runnerFor(POSTGRES); fixture(context, number): a family with Ma and Baba; sql/scalar as before
}
```

- [ ] **Step 2: red, three replays (all required).** Units compared with `String.equals` → `expected: [<earlier>] but
  was: []` (the MU spelling dropped: the scorer bug one layer up). Filter by canonical name alone → `but was:
  [<earlier>, <mg/dL doc>]`. Drop the `patient_id` predicate → Baba's document in the list. Paste all three.
- [ ] **Step 3: implement.** `readingsOf(UUID patientId, String canonicalName, UUID excludingDocumentId)` returns DONE
  documents' observations of that patient and marker, newest first, at most 10, each map = the observation view +
  `document_id` + `date` (the document's `timeline_date`) + `date_is_fallback` (`doc_date IS NULL`); the unit is compared
  in Java after `DocumentFacts.fold` and lower-casing on both sides. `EarlierReading(UUID documentId, String value,
  String unit, LocalDate date, boolean dateIsFallback)`. `ResultService.earlierReadings` first resolves the document
  through `visibleDocument` (404 for another family) and the observation within **that** document's values (404 for an
  id from another document).

### Task 26.2: `/all`, `/values/{obs}`, the collapse, the routes

**Files:** modify `ui/DocumentPages.java` (`detail`, `valueSheet`; routes `GET /documents/{id}/all` and
`GET /documents/{id}/values/{obs}` before `/documents/{id}`; the summary's inside rows collapse; `ValueRows.row` links the
sheet with `?from=summary|all`), `PageFixtures` (+2 routes), `WebOnlyJourneyAcceptanceTest` (+2 steps),
`ResultSummaryRenderTest` (+the collapse), create test `ui/DocumentDetailRenderTest.java`, `css/60-result.css`,
`ui-result_*`.

- [ ] **Step 1: copy** (s17/s13, approved; the close control uses the shared `sheet.close`):

```properties
detail.title={0} — সবকিছু
detail.back=ফলাফল
detail.seg.label=এই কাগজের অংশ
detail.seg.values=মান
detail.seg.medicines=ওষুধ
detail.seg.doctor=ডাক্তার লিখেছেন
detail.seg.pages=মূল কাগজ
detail.values.note={0}টি মান, কাগজে যেভাবে লেখা। যেকোনোটায় চাপ দিলে কাগজের ছবি বড় হবে।
detail.group.count={0} · {1}টি
detail.pages.note=যে ছবি পাঠিয়েছিলেন, ঠিক সেটাই। দুই আঙুলে বড় করা যায়।
detail.pages.rx=ফার্মেসিতে এই ছবিটাই দেখান। দুই আঙুলে বড় করা যায়।
detail.page=পাতা {0}
detail.delete=কাগজটা মুছুন
detail.delete.note=মুছে ফেলার আগে আরেকবার জিজ্ঞেস করা হবে
sheet.of={0} · {1} · {2}
sheet.unknown.why=এই মানটা রিপোর্টে লেখা সীমার সাথে Medioo মেলাতে পারেনি। কাগজে কী লেখা, ওপরে নিজে দেখে নিন, আর ডাক্তারকে দেখান।
sheet.earlier=আগের রিপোর্টে, একই একক {0}
sheet.earlier.none=আগের কোনো রিপোর্টে এই মান পাওয়া যায়নি। এটাই প্রথমবার।
value.more.inside=আরও {0}টি মান সীমার মধ্যে — সব দেখুন
result.all=সব দেখুন — সব মান ও মূল কাগজ
```

  `sheet.of` is "Ma · ১২ অক্টোবর ২০২৬ · ল্যাব রিপোর্ট": three standalone arguments (patient, date, type), so no
  placeholder carries a case ending (`GlossaryTest.GLUED_CASE`).
- [ ] **Step 2: failing tests.**

```java
package com.elioo.baymax.web.adapter.in.ui;

/** s17 / s13: everything on the paper, each fact beside its stored crop; the sheet is a plain URL. */
class DocumentDetailRenderTest {

    private final WebUiTestKit kit = new WebUiTestKit();
    private final UiCopy copy = kit.copy;
    private static final Pattern FACT_BLOCK = Pattern.compile("<a class=\"(?:vrow|med)[^\"]*\"[^>]*>.*?</a>|<figure class=\"paper\">.*?</figure>", Pattern.DOTALL);

    private static ResultView full() {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("diagnosis", List.of(Map.of("text", "Kindly correlate clinically.", "crop_key", "f/p/d/crop-c1.jpg")));
        ctx.put("advice", List.of(Map.of("text", "Low salt diet", "crop_key", "f/p/d/crop-c2.jpg")));
        DocumentView v = new DocumentView(ResultFixtures.DOC.toString(), "DONE", null, "lab_report", "2026-10-12", "Shapla Diagnostics", 0.9, "m", 2,
                List.of(ResultFixtures.value("HbA1c", "8.4", "%", "4.0", "6.0", "outside"),
                        ResultFixtures.value("Urine Albumin", "Trace", null, "Nil", null, "unknown"),
                        ResultFixtures.value("TSH", "2.1", "µIU/mL", "0.4", "4.0", "inside")),
                List.of(Map.of("name", "Tab. Demoprol", "dose_text", "5 mg", "frequency_text", "1+0+1", "crop_key", "f/p/d/crop-m1.jpg")),
                List.of(Map.of("instruction", "Next visit: 22/10/26", "due_date", "2026-10-22", "crop_key", "f/p/d/crop-f1.jpg")),
                ctx, null);
        return ResultFixtures.result(v, Urgency.THIS_WEEK, List.of("value_outside_range:hba1c|stopgap"), GateState.NONE, null, ResultFixtures.BD);
    }

    /** Derived over every fact list the view carries: each item is in a block that also carries THAT item's crop. */
    @Test
    void everyFactOnThePaperSitsBesideItsOwnStoredCrop() {
        ResultView r = full();
        String html = kit.documents.detail(Lang.BN, r);
        List<String> blocks = FACT_BLOCK.matcher(html).results().map(MatchResult::group).toList();
        List<Map<String, Object>> facts = new ArrayList<>();
        facts.addAll(r.document().values());
        facts.addAll(r.document().medicines());
        facts.addAll(r.document().followUp());
        r.document().clinicalContext().values().forEach(section -> {
            if (section instanceof List<?> items) {
                items.forEach(i -> facts.add((Map<String, Object>) i));
            }
        });
        assertThat(facts).as("facts in the view").hasSizeGreaterThanOrEqualTo(7);
        for (Map<String, Object> fact : facts) {
            String text = Html.esc(String.valueOf(fact.getOrDefault("name", fact.getOrDefault("instruction", fact.get("text")))));
            String key = "key=" + URLEncoder.encode((String) fact.get("crop_key"), StandardCharsets.UTF_8);
            assertThat(blocks).as(text + " beside " + key).anyMatch(b -> b.contains(text) && b.contains(key));
        }
        assertThat(html.split("class=\"vrow ").length - 1).as("one row per value").isEqualTo(r.document().values().size());
    }

    /** No-JS segments: anchors only for the sections that exist; empty sections hidden. */
    @Test
    void theSegmentsAreAnchorsToTheSectionsThatExist() {
        String html = kit.documents.detail(Lang.BN, ResultFixtures.result(ResultFixtures.lab(List.of(
                ResultFixtures.value("TSH", "2.1", "µIU/mL", "0.4", "4.0", "inside"))), Urgency.ROUTINE, List.of(), GateState.NONE, null, ResultFixtures.BD));
        assertThat(ResultFixtures.section(html, "seg")).contains("href=\"#values\"").contains("href=\"#pages\"").doesNotContain("href=\"#medicines\"");
        assertThat(html).contains("id=\"values\"").contains("id=\"pages\"").doesNotContain("id=\"medicines\"");
    }

    /** The stored urgency stays on the detail page too (UrgencyBanner: "the top of every … document detail"). */
    @Test
    void theDetailPageCarriesTheStoredBanner() {
        String html = kit.documents.detail(Lang.BN, ResultFixtures.result(ResultFixtures.lab(List.of()), Urgency.NOW, List.of(), GateState.REJECTED, null, ResultFixtures.BD));
        assertThat(ResultFixtures.section(html, "urgency")).contains(copy.urgency(Lang.BN, Urgency.NOW, false));
    }

    /** A share member never sees delete (spec §4.16). */
    @Test
    void onlyTheOwnerIsOfferedDelete() {
        ResultView owner = ResultFixtures.result(ResultFixtures.lab(List.of()), Urgency.ROUTINE, List.of(), GateState.NONE, null, ResultFixtures.BD);
        ResultView member = new ResultView(owner.document(), owner.patient(), false, owner.viewer(), owner.urgency(), List.of(),
                GateState.NONE, null, 0, 2, owner.receivedAt(), owner.now());
        assertThat(kit.documents.detail(Lang.BN, owner)).contains("/documents/" + ResultFixtures.DOC + "/delete");
        assertThat(kit.documents.detail(Lang.BN, member)).doesNotContain("/delete");
    }

    /** s13--unknown: no bar, the "could not match" sentence; the glossary stays hidden until D3 is signed. */
    @Test
    void anUnknownValueSheetSaysItCouldNotMatchAndShowsNoGlossary() {
        Map<String, Object> v = ResultFixtures.value("Urine Albumin", "Trace", null, "Nil", null, "unknown");
        String html = kit.documents.valueSheet(Lang.BN, ResultFixtures.result(ResultFixtures.lab(List.of(v)), Urgency.ROUTINE,
                List.of(), GateState.NONE, null, ResultFixtures.BD), (UUID) v.get("id"), List.of(), "summary");
        assertThat(html).contains(copy.t(Lang.BN, "sheet.unknown.why")).contains(copy.t(Lang.BN, "sheet.earlier.none"))
                .doesNotContain("<svg").doesNotContain("এই পরীক্ষা কী মাপে").doesNotContain("সাধারণ তথ্য");
        assertThat(html).contains("href=\"/app/documents/" + ResultFixtures.DOC + "#v-" + v.get("id") + "\"");   // close returns to the row
    }
}
```

  In `ResultSummaryRenderTest`: two inside rows render one and `copy.t(Lang.BN, "value.more.inside", 1)` linking
  `/app/documents/{id}/all#values`. In `DocumentRoutesTest`: `GET /app/documents/{doc}/values/{obsOfAnotherDoc}` → the
  404 page (`app.err.notfound`), and `GET /app/documents/{doc}/all` → 200. In `WebOnlyJourneyAcceptanceTest` after step
  5: `GET {documentPath}/all` (the `#pages` section holds `/page/1`) and `GET {documentPath}/values/{the Potassium row's
  id, read from the page}` (the sheet shows "11.0" and `sheet.earlier.none`).
- [ ] **Step 3: red, and the replay on the crop.** `404` for `/all` and compile errors for `detail`. **Replay:** add a
  fourth value with `crop_key` = `""` to `full()` and make `ValueRows.row` skip the `<img class="crop">` when the key is
  blank (instead of throwing) → red `[Potassium beside key=] Expecting any elements of … to match predicate` — the row
  rendered without its proof, inside its own block. Revert both.
- [ ] **Step 4: implement.** `detail` = back link to the result (`detail.back`), the patient chip, `<h1>` `detail.title`,
  the meta line (date · facility · `{n}টি মান · {p}টি পাতা`), `banner(lang, r)` (the summary's method), `<nav id="seg"
  class="seg" aria-label="…">` with anchors for the non-empty sections, `<section id="values">` (three groups with
  `detail.group.count`; every INSIDE row shown), `<section id="medicines">`, `<section id="doctor">` (every context
  section but `referral`), `<section id="pages">` with `<figure id="page-{n}">` (`<img class="page-img">`) and
  `<a class="btn quiet" href="/app/documents/{id}/page/{n}">` + `crop.zoom` + `</a>`, then the owner-only delete link to
  the existing confirm route. `valueSheet` = a page styled as a sheet (the `.sheet` rule from A7) with the close link
  back to `/app/documents/{id}[/all]#v-{obs}` (`aria-label` = `sheet.close`), the large crop + `crop.zoom`, the value
  and unit as printed (`--fs-value-large`), the range line, the chip, the bar only through `ValueRows.pin`,
  `sheet.unknown.why` for UNKNOWN, and earlier readings as compact rows (`sheet.earlier` with the unit as printed; dates
  through `copy.date`, `timeline.uploaddate` when `date_is_fallback`). `DocumentPages.valueSheet(request)` resolves
  `obs` through `results.earlierReadings` (the 404s above) and renders. CSS: `.seg{display:flex;flex-wrap:wrap;gap:var(--sp-2)}`,
  `.seg a{display:inline-flex;align-items:center;min-height:var(--target-min);padding:0 var(--sp-4);border:1px solid var(--c-outline);border-radius:var(--r-pill);color:var(--c-text);text-decoration:none}`,
  `.page-img{width:100%;border:1px solid var(--c-surface-3);border-radius:var(--r-xs)}`.
- [ ] **Step 5: run** `./gradlew :baymax:test`, `./gradlew build`; standard pre-merge. Commit
  `feat(baymax-web): document detail and the value sheet, every fact beside its crop`.

### Try it (B6) — prod-web

- [ ] From a result, "সব দেখুন — সব মান ও মূল কাগজ": the segments মান · ওষুধ · ডাক্তার লিখেছেন · মূল কাগজ jump without JS;
  "বড় করে দেখুন" opens the page image, where pinch zoom works.
- [ ] Tap a value: its crop large, the value as printed, "রিপোর্টে লেখা সীমা …", and the earlier t3/t7 readings in the
  same unit. Close returns to the same row.
- [ ] t11's "Trace": no bar, "…মেলাতে পারেনি", no glossary line.

---

## PR C1 — L4b: the active patient remembered, the switcher sheet, the legacy redirects

Branch `feat/baymax-ui-active-patient`. Spec §3, F6: the patient in view is remembered per browser for 30 minutes of
silence (a sliding `baymax_patient` cookie, `HttpOnly`, `SameSite=Lax`, path `/app`), always re-checked against what this
family may see, never trusted. The header's switcher opens a **sheet** that is a plain URL (`?sheet=patient`): the server
draws it over the page, makes the page beneath `inert`, and its close control is a link back with `autofocus` — no
script. `/app/patients/{id}` (WhatsApp links, bookmarks) becomes a 302 to কাগজপত্র with the patient chosen.

### Task L4b.1: the failing tests

**Files:** modify `AppShellRoutesTest` (+4 tests), `WebOnlyJourneyAcceptanceTest` (`NOT_IN_JOURNEY`: `GET /app/patients/{id}`
now reads "a 302 for old links; AppShellRoutesTest").

- [ ] **Step 1:**

```java
    /** WhatsApp messages and bookmarks carry the old URL; it opens কাগজপত্র with the patient chosen (profile: L5). */
    @Test
    void theOldPatientUrlOpensThePapersTab() {
        UUID id = ma.patient().id();
        kit.client.get().uri("/app/patients/" + id).cookie("baymax_session", "tok").exchange().expectStatus().isFound()
                .expectHeader().valueEquals(HttpHeaders.LOCATION, "/app/timeline?patient=" + id);
        kit.client.get().uri("/app/patients/not-a-uuid").cookie("baymax_session", "tok").exchange().expectStatus().isNotFound();
        kit.client.get().uri("/baymax/patients/" + id).exchange().expectStatus().isEqualTo(301);   // the 301 chain still starts
    }

    @Test
    void choosingSetsAThirtyMinuteCookieThatTheNextPageHonours() {
        kit.familyWith(ma, baba);
        noPapers(baba);
        kit.client.get().uri("/app/timeline?patient=" + baba.patient().id()).cookie("baymax_session", "tok").exchange()
                .expectStatus().isOk()
                .expectHeader().value(HttpHeaders.SET_COOKIE, c -> assertThat(c)
                        .startsWith("baymax_patient=" + baba.patient().id()).contains("Path=/app").contains("Max-Age=1800")
                        .contains("HttpOnly").contains("SameSite=Lax"));
        String next = kit.client.get().uri("/app/timeline").cookie("baymax_session", "tok")
                .cookie("baymax_patient", baba.patient().id().toString()).exchange()
                .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        assertThat(between(next, "<header class=\"appbar\"", "</header>")).contains("আব্বু").doesNotContain("আম্মা");
    }

    /** A remembered patient this family can no longer see is forgotten (not a 404); asking for one by URL is a 404. */
    @Test
    void aStaleCookieIsIgnoredButAnUnseenPatientInTheUrlIs404() {
        kit.familyWith(ma);
        noPapers(ma);
        String html = kit.client.get().uri("/app/timeline").cookie("baymax_session", "tok")
                .cookie("baymax_patient", UUID.randomUUID().toString()).exchange()
                .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        assertThat(between(html, "<header class=\"appbar\"", "</header>")).contains("আম্মা");
        kit.client.get().uri("/app/timeline?patient=" + UUID.randomUUID()).cookie("baymax_session", "tok").exchange()
                .expectStatus().isNotFound();
    }

    /** Spec §3 sheets: the switcher is a URL; the page beneath is inert; the close link returns to the bare tab. */
    @Test
    void thePatientSheetIsAPlainUrlThatInertsThePageBeneathAndClosesBackToTheTab() {
        kit.familyWith(ma, baba);
        noPapers(ma);
        String open = kit.page("/app/timeline?patient=" + ma.patient().id() + "&sheet=patient", "bn");
        assertThat(open).contains("<div class=\"shell\" inert>");
        String dialog = between(open, "role=\"dialog\"", "</section>");
        assertThat(dialog).contains("href=\"/app/timeline\" autofocus")
                .contains("href=\"/app/timeline?patient=" + baba.patient().id() + "\"")
                .contains("aria-current=\"true\"");   // Ma is the chosen one
        String closed = kit.page("/app/timeline?patient=" + ma.patient().id(), "bn");
        assertThat(closed).contains("<div class=\"shell\">").doesNotContain("role=\"dialog\"");
    }

    /** Never preselect, now with a cookie in play: two patients, no ?patient and no cookie still read nobody's papers on any tab. */
    @ParameterizedTest
    @EnumSource(value = Tab.class, names = {"CHAT", "PAPERS"})
    void aRememberedChoiceIsTheOnlyThingThatSkipsTheChooser(Tab tab) {
        kit.familyWith(ma, baba);
        assertThat(kit.page(tab.path, "bn")).contains("কার কাগজ দেখবেন?");
        verify(kit.timeline, never()).timeline(any(), any(), any());
    }
```

- [ ] **Step 2: red.** `choosingSets… Expecting actual "" to start with "baymax_patient="` (no cookie yet);
  `thePatientSheet… Expecting … to contain "<div class="shell" inert>"`; `theOldPatientUrl… Status expected:<302 FOUND>
  but was:<200 OK>` (today `/app/patients/{id}` renders the old timeline).

### Task L4b.2: implement, replay, ship

**Files:** modify `ui/ActivePatient.java` (the cookie), `ui/AppShell.java` (`sheet`, `patientSheet`; the switcher links
`tab.path + "?sheet=patient"`), `ui/AppTabs.java` (sets the cookie on every tab with a patient in view; renders the sheet
when `?sheet=patient` and two or more patients; `GET /app/patients/{id}` → `movedToTab`; `home` and `timelinePage`
deleted), `css/20-shell.css`, `ui-shell_*` (+`switch.none=কার কাগজ?` / `Whose papers?`).

- [ ] **Step 1: `ActivePatient`** (C1's full rule):

```java
package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.web.domain.FamilyOverview;
import com.elioo.baymax.web.domain.PatientSummary;
import org.springframework.http.HttpCookie;
import org.springframework.http.ResponseCookie;
import org.springframework.web.reactive.function.server.ServerRequest;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Whose papers are in view (spec §3), for this browser: {@code ?patient=} if this family may see that patient (else a
 * 404, the same answer as a patient that does not exist), else the remembered choice if still visible, else the only
 * patient. Two or more and no choice: empty, and the tab asks — Medioo never guesses (PatientSwitcher README). The
 * visible set is {@code timeline.me()}, i.e. {@code records.visiblePatients} in SQL; the cookie is never trusted.
 */
record ActivePatient(Optional<PatientSummary> patient) {

    static final String COOKIE = "baymax_patient";
    static final Duration TTL = Duration.ofMinutes(30);

    static ActivePatient resolve(ServerRequest request, FamilyOverview me) {
        Optional<String> asked = request.queryParam("patient");
        if (asked.isPresent()) {
            return new ActivePatient(Optional.of(find(me, asked.get())
                    .orElseThrow(() -> BaymaxException.notFound("patient_not_found", "no such patient"))));
        }
        Optional<PatientSummary> remembered = Optional.ofNullable(request.cookies().getFirst(COOKIE))
                .map(HttpCookie::getValue).flatMap(v -> find(me, v));
        if (remembered.isPresent()) {
            return new ActivePatient(remembered);
        }
        return new ActivePatient(me.patients().size() == 1 ? Optional.of(me.patients().get(0)) : Optional.empty());
    }

    /** Sliding: re-set on every page that has a patient in view, so thirty minutes of silence forgets it. */
    Optional<ResponseCookie> cookie(boolean secure) {
        return patient.map(p -> ResponseCookie.from(COOKIE, p.patient().id().toString()).httpOnly(true).secure(secure)
                .sameSite("Lax").path(PageChrome.BASE).maxAge(TTL).build());
    }

    boolean is(PatientSummary p) {
        return patient.map(a -> a.patient().id().equals(p.patient().id())).orElse(false);
    }

    private static Optional<PatientSummary> find(FamilyOverview me, String raw) {
        UUID id;
        try {
            id = UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return me.patients().stream().filter(p -> p.patient().id().equals(id)).findFirst();
    }
}
```

- [ ] **Step 2: the sheet** (`AppShell`, kit §17; the scrim is `aria-hidden` and `tabindex="-1"`, the close link is the
  target):

```java
    /** Kit §17. {@code title} is already escaped (from UiCopy); {@code closeHref} is the page beneath, without the sheet. */
    String sheet(Lang lang, String title, String content, String closeHref) {
        return "<div class=\"sheet-wrap\"><a class=\"scrim\" href=\"" + closeHref + "\" tabindex=\"-1\" aria-hidden=\"true\"></a>"
                + "<section class=\"sheet\" role=\"dialog\" aria-modal=\"true\" aria-labelledby=\"sheet-title\">"
                + "<span class=\"grab\" aria-hidden=\"true\"></span><div class=\"sheet-head\"><h2 id=\"sheet-title\">" + title + "</h2>"
                + "<a class=\"icon-btn\" href=\"" + closeHref + "\" autofocus aria-label=\"" + copy.t(lang, "sheet.close") + "\">"
                + Icons.svg("x", 24) + "</a></div>" + content + "</section></div>";
    }

    String patientSheet(Lang lang, Tab tab, FamilyOverview me, ActivePatient active) {
        return sheet(lang, copy.t(lang, "switch.title"), choices(lang, tab, me, active), tab.path);
    }
```

  and the redirect (in `AppTabs`):

```java
    /** A patient URL from before the tabs (WhatsApp links, bookmarks): 302 to its tab with the patient chosen. */
    static Mono<ServerResponse> movedToTab(ServerRequest request, Tab tab) {
        UUID id;
        try {
            id = UUID.fromString(request.pathVariable("id"));
        } catch (IllegalArgumentException e) {
            return Mono.error(BaymaxException.notFound("not_found", "no such page"));
        }
        return ServerResponse.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, tab.path + "?patient=" + id).build();
    }
```

  `AppTabs.tab(...)` adds `active.cookie(properties.getAuth().isCookieSecure()).ifPresent(builder::cookie)` to the
  response and passes `patientSheet(...)` as the `sheet` argument of `Html.appPage` when
  `"patient".equals(request.queryParam("sheet").orElse(""))` and `me.patients().size() > 1`. The CSS for `.sheet-wrap`,
  `.scrim`, `.sheet`, `.grab`, `.sheet-head` is already in `20-shell.css` (A5); `.sheet-head` gains nothing.
- [ ] **Step 3: run** `./gradlew :baymax:test` → green. **Replays (mutants):** fall back to `me.patients().get(0)` when the
  cookie names a patient this family no longer sees → `aStaleCookie…` still green (it forgets) but
  `aRememberedChoice…` red `Expecting … to contain "কার কাগজ দেখবেন?"`; drop `inert` → `Expecting … to contain "<div class="shell" inert>"`.
  Revert; paste both.
- [ ] **Step 4:** standard pre-merge. Commit `feat(baymax-ui): remember the patient for thirty minutes, a switcher sheet, old links redirect`.

### Try it (C1)

- [ ] **prod-web:** open an old link `https://medioo.eliooo.org/app/patients/<Ma's id>`: কাগজপত্র with Ma in view.
- [ ] **local** (two patients): choose Baba on কাগজপত্র, go to চ্যাট: Baba is still in view. Tap the name in the header: the
  sheet opens over the page; choose Ma: the sheet closes on Ma. With JavaScript blocked, the same.
- [ ] **local:** leave the browser 31 minutes: কাগজপত্র asks "কার কাগজ দেখবেন?" again.

---

## PR C2 — T16: the timeline read model — document-date order, an opaque cursor, the stored chip, the outside count

Branch `feat/baymax-timeline-read-model`. This PR is the **one owner** of the timeline's data: the order (V20's
`timeline_date`, then upload, then id — §4.15 newest document date first), a composite cursor that cannot skip or
repeat a row, the stored F1 urgency and the chip rule, the F2 outside count, the review state, the retake code, the
code-built one-line summary, and the attention query the চ্যাট tab (C4) and the "এখন যা দেখার" strip read. Nothing in
the web assesses: rows come from SQL over stored columns. What it replaces: `TimelineService.timeline` pages on
`created_at` alone (`created_at < :before`), runs one `sectionCounts` per row and emits `next = createdAt`, so rows
sharing an instant are skipped at a page boundary; the date is UTC. C3 restyles the list (months, filters, empty state);
this PR renders the chip and the summary in today's list.

### Task 16.1: the cursor (unit)

**Files:** create `web/domain/TimelineCursor.java`, test `web/domain/TimelineCursorTest.java`.

- [ ] **Step 1: failing tests.**

```java
package com.elioo.baymax.web.domain;

import com.elioo.baymax.common.error.BaymaxException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** §3: (timeline date, arrival, id), newest first, as an opaque token a client cannot edit into a date. */
class TimelineCursorTest {

    private static final TimelineCursor C = new TimelineCursor(LocalDate.parse("2026-10-12"),
            Instant.parse("2026-10-14T08:00:00.123456Z"), UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4000001"));

    @Test
    void aCursorRoundTripsExactlyAndIsOpaque() {
        assertThat(TimelineCursor.parse(C.encode())).isEqualTo(C);
        assertThat(C.encode()).startsWith("v1.").doesNotContainPattern("\\d{4}-\\d{2}-\\d{2}").doesNotContain("|");
    }

    @Test
    void noCursorIsTheFirstPage() {
        assertThat(TimelineCursor.parse(null)).isEqualTo(TimelineCursor.FIRST);
        assertThat(TimelineCursor.parse(" ")).isEqualTo(TimelineCursor.FIRST);
    }

    /** A "আরও দেখুন" link saved before this deploy carries a bare instant: it opens the first page, not an error. */
    @Test
    void aPreDeployInstantCursorOpensTheFirstPage() {
        assertThat(TimelineCursor.parse("2026-10-14T08:00:00Z")).isEqualTo(TimelineCursor.FIRST);
    }

    /** The other direction: a payload without the v1 prefix, or a v1 that does not decode, is a 400 (as before). */
    @Test
    void anythingElseIsABadRequest() {
        String unprefixed = Base64.getUrlEncoder().withoutPadding().encodeToString("2026-10-12|2026-10-14T08:00:00Z|5e9edf6d-0a2a-4913-8675-9d07a4000001".getBytes());
        for (String bad : new String[]{unprefixed, "v1.!!!", "abc", "v2." + unprefixed}) {
            assertThatThrownBy(() -> TimelineCursor.parse(bad)).as(bad).isInstanceOf(BaymaxException.class)
                    .satisfies(e -> assertThat(((BaymaxException) e).status().value()).isEqualTo(400));
        }
    }
}
```

- [ ] **Step 2: red** on compile, then (a cursor that encodes `date|instant|uuid` in clear, the draft's first idea)
  `Expecting actual "2026-10-12|…" to start with "v1."`.
- [ ] **Step 3: implement** `record TimelineCursor(LocalDate date, Instant createdAt, UUID id)`;
  `FIRST = (9999-12-31, 9999-12-31T00:00:00Z, ffffffff-ffff-ffff-ffff-ffffffffffff)`; `encode()` =
  `"v1." + base64url-without-padding(date + "|" + createdAt + "|" + id)`; `parse`: null/blank → FIRST; a string that
  `Instant.parse`s → FIRST; `v1.` + a payload that decodes to exactly three parsable parts → the cursor; anything else →
  `BaymaxException.badRequest("invalid_cursor", "cursor must be the next_cursor of a previous page")`.

### Task 16.2: the read port and its SQL, on Testcontainers — every test written out, each with its mutant

**Files:** create `web/application/port/out/TimelineReadPort.java`, `web/domain/TimelineFilter.java`,
`web/adapter/out/persistence/PostgresTimelineReadAdapter.java`, test `web/adapter/out/persistence/TimelineReadModelTest.java`;
modify `web/domain/TimelineEntry.java` (+`Urgency urgency`, `int outside`, `String reviewState`, `String retakeReasonCode`,
last; the 12-argument constructor delegates with `null, 0, null, null`; `chip()`).

- [ ] **Step 1: failing tests.**

```java
package com.elioo.baymax.web.adapter.out.persistence;

/** The timeline read model on real Postgres: order, cursor, filter, stored urgency, verdict counts, review state, attention. */
@Testcontainers
class TimelineReadModelTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final Instant NOW = Instant.parse("2026-10-20T04:00:00Z");
    private static final AtomicInteger FAMILIES = new AtomicInteger();

    private ReactiveWebApplicationContextRunner runner() {
        return PostgresNudgeAdapterTest.runnerFor(POSTGRES);
    }

    private static UUID patient(ApplicationContext context) {
        HealthRecordPort records = context.getBean(HealthRecordPort.class);
        FamilyAccount f = records.createFamily(new FamilyAccount(null, "+88017000009" + String.format("%02d", FAMILIES.incrementAndGet()),
                "Rahim", FamilyAccount.Plan.FREE, NOW, NOW)).block();
        return records.createPatient(new PatientProfile(null, f.id(), "Ma", 68, PatientProfile.Sex.FEMALE, List.of(), NOW, NOW)).block().id();
    }

    /** A document with exactly these columns (created through the port, then set in SQL so created_at and doc_date are exact). */
    private static UUID doc(ApplicationContext context, UUID patient, String createdAt, String docDate, String type, String status) throws Exception {
        UUID family = UUID.fromString(scalar("SELECT family_id FROM baymax.patient_profile WHERE id = '" + patient + "'"));
        UUID id = context.getBean(DocumentRecordPort.class).create(Document.received(patient, family, 1, NOW)).block().id();
        sql("UPDATE baymax.document SET status = '" + status + "', document_type = " + (type == null ? "NULL" : "'" + type + "'")
                + ", created_at = '" + createdAt + "', doc_date = " + (docDate == null ? "NULL" : "'" + docDate + "'")
                + (status.equals("NEEDS_RETAKE") ? ", status_reason_code = 'low_overall'" : "") + " WHERE id = '" + id + "'");
        return id;
    }

    private static void urgency(UUID doc, String level) throws Exception {
        sql("UPDATE baymax.document SET urgency = '" + level + "', urgency_reasons = '[]', urgency_assessed_at = now() WHERE id = '" + doc + "'");
    }

    private static void observation(UUID doc, String verdict) throws Exception {
        String v = verdict == null ? "NULL, NULL, NULL" : "'" + verdict + "', '" + (verdict.equals("critical") ? "now" : verdict.equals("outside") ? "this_week" : "routine")
                + "', 'value_" + (verdict.equals("critical") ? "critical" : verdict.equals("outside") ? "outside_range" : verdict) + ":x|stopgap'";
        sql("INSERT INTO baymax.observation (patient_id, document_id, name, value, crop_key, observed_at, verdict, verdict_level, verdict_reason) "
                + "SELECT patient_id, id, 'X', '1', 'k/c.jpg', now(), " + v + " FROM baymax.document WHERE id = '" + doc + "'");
    }

    private static void explanation(UUID doc, String gate) throws Exception {
        sql("INSERT INTO baymax.outbound_message (family_id, document_id, kind, urgency, urgency_reasons, gate_status, created_at) "
                + "SELECT family_id, id, 'explanation', 'routine', '[]', '" + gate + "', now() FROM baymax.document WHERE id = '" + doc + "'");
    }

    /** The defect a created_at-only cursor has: 21 rows sharing one instant and one date come back exactly once each. */
    @Test
    void rowsSharingADateAndAnInstantAreNeitherSkippedNorRepeated() {
        runner().run(context -> {
            UUID p = patient(context);
            for (int i = 0; i < 21; i++) {
                doc(context, p, "2026-10-14T08:00:00Z", "2026-10-12", "lab_report", "DONE");
            }
            TimelineReadPort port = context.getBean(TimelineReadPort.class);
            List<TimelineEntry> first = port.page(p, TimelineCursor.FIRST, TimelineFilter.ALL, 21).collectList().block();
            TimelineEntry last = first.get(19);
            List<TimelineEntry> second = port.page(p, new TimelineCursor(last.date(), last.createdAt(), last.documentId()),
                    TimelineFilter.ALL, 21).collectList().block();
            assertThat(first).hasSize(21);
            assertThat(second).hasSize(1);
            assertThat(Stream.concat(first.subList(0, 20).stream(), second.stream()).map(TimelineEntry::documentId).distinct()).hasSize(21);
        });
    }

    /** §4.15: newest DOCUMENT date first. A 12 July report uploaded today sits below a 12 October one uploaded last week. */
    @Test
    void theTimelineIsOrderedByDocumentDateNotByUpload() {
        runner().run(context -> {
            UUID p = patient(context);
            UUID october = doc(context, p, "2026-10-13T08:00:00Z", "2026-10-12", "lab_report", "DONE");
            UUID july = doc(context, p, "2026-10-20T03:00:00Z", "2026-07-12", "lab_report", "DONE");
            assertThat(context.getBean(TimelineReadPort.class).page(p, TimelineCursor.FIRST, TimelineFilter.ALL, 10)
                    .map(TimelineEntry::documentId).collectList().block()).containsExactly(october, july);
        });
    }

    /** An undated upload falls back to its upload day in Dhaka (F7) and says so. */
    @Test
    void anUndatedUploadUsesItsDhakaUploadDay() {
        runner().run(context -> {
            UUID p = patient(context);
            doc(context, p, "2026-10-18T19:30:00Z", null, "prescription", "DONE");   // 01:30 on 19 October in Dhaka
            TimelineEntry e = context.getBean(TimelineReadPort.class).page(p, TimelineCursor.FIRST, TimelineFilter.ALL, 10).blockFirst();
            assertThat(e.date()).isEqualTo(LocalDate.parse("2026-10-19"));
            assertThat(e.dateIsFallback()).isTrue();
        });
    }

    @Test
    void anEntryCarriesItsStoredUrgencyOutsideCountAndReviewState() {
        runner().run(context -> {
            UUID p = patient(context);
            UUID d = doc(context, p, "2026-10-19T08:00:00Z", "2026-10-18", "lab_report", "DONE");
            urgency(d, "this_week");
            observation(d, "outside");
            observation(d, "critical");
            observation(d, "inside");
            observation(d, null);   // not yet assessed: counted as a value, as neither inside nor outside
            explanation(d, "pending");
            TimelineEntry e = context.getBean(TimelineReadPort.class).page(p, TimelineCursor.FIRST, TimelineFilter.ALL, 10).blockFirst();
            assertThat(e.urgency()).isEqualTo(Urgency.THIS_WEEK);
            assertThat(e.values()).isEqualTo(4);
            assertThat(e.outside()).isEqualTo(2);
            assertThat(e.reviewState()).isEqualTo("pending");
            assertThat(e.chip()).contains(Urgency.THIS_WEEK);
        });
    }

    /** The other direction of D27: a ROUTINE paper and an unassessed one have no chip. */
    @Test
    void routineAndUnassessedPapersHaveNoChip() {
        runner().run(context -> {
            UUID p = patient(context);
            UUID routine = doc(context, p, "2026-10-19T08:00:00Z", "2026-10-18", "lab_report", "DONE");
            urgency(routine, "routine");
            doc(context, p, "2026-10-19T09:00:00Z", "2026-10-17", "lab_report", "DONE");   // never assessed
            List<TimelineEntry> rows = context.getBean(TimelineReadPort.class).page(p, TimelineCursor.FIRST, TimelineFilter.ALL, 10).collectList().block();
            assertThat(rows).hasSize(2).allSatisfy(e -> assertThat(e.chip()).isEmpty());
            assertThat(rows.get(0).urgency()).isEqualTo(Urgency.ROUTINE);
            assertThat(rows.get(1).urgency()).isNull();
        });
    }

    @Test
    void theLabFilterExcludesPrescriptionsAndThePrescriptionFilterTheLabs() {
        runner().run(context -> {
            UUID p = patient(context);
            UUID lab = doc(context, p, "2026-10-19T08:00:00Z", "2026-10-18", "lab_report", "DONE");
            UUID imaging = doc(context, p, "2026-10-19T08:00:01Z", "2026-10-17", "imaging_report", "DONE");
            UUID rx = doc(context, p, "2026-10-19T08:00:02Z", "2026-10-16", "prescription", "DONE");
            TimelineReadPort port = context.getBean(TimelineReadPort.class);
            assertThat(port.page(p, TimelineCursor.FIRST, TimelineFilter.LAB, 10).map(TimelineEntry::documentId).collectList().block())
                    .containsExactly(lab, imaging).doesNotContain(rx);
            assertThat(port.page(p, TimelineCursor.FIRST, TimelineFilter.PRESCRIPTION, 10).map(TimelineEntry::documentId).collectList().block())
                    .containsExactly(rx);
        });
    }

    /** s06 and the s16 strip: NOW and THIS_WEEK only, in ANY review state, NOW first, newest within a level. */
    @Test
    void attentionHoldsNowAndThisWeekWhateverTheReviewState() {
        runner().run(context -> {
            UUID p = patient(context);
            UUID nowPending = doc(context, p, "2026-10-19T08:00:00Z", "2026-10-18", "lab_report", "DONE");
            urgency(nowPending, "now");
            explanation(nowPending, "pending");
            UUID week = doc(context, p, "2026-10-19T09:00:00Z", "2026-10-18", "lab_report", "DONE");
            urgency(week, "this_week");
            explanation(week, "released");
            UUID routine = doc(context, p, "2026-10-19T10:00:00Z", "2026-10-18", "lab_report", "DONE");
            urgency(routine, "routine");
            explanation(routine, "released");
            doc(context, p, "2026-10-19T11:00:00Z", "2026-10-18", "lab_report", "DONE");   // unassessed
            assertThat(context.getBean(TimelineReadPort.class).attention(p, NOW.minus(Duration.ofDays(30)), 5)
                    .map(TimelineEntry::documentId).collectList().block()).containsExactly(nowPending, week);
        });
    }

    /** RECENT_LIMIT's defect: 51 newer routine papers cannot push an older NOW paper off the stack (filtered in SQL). */
    @Test
    void manyNewerRoutinePapersDoNotHideANowPaper() {
        runner().run(context -> {
            UUID p = patient(context);
            UUID now = doc(context, p, "2026-10-01T08:00:00Z", "2026-10-01", "lab_report", "DONE");
            urgency(now, "now");
            for (int i = 0; i < 51; i++) {
                UUID r = doc(context, p, "2026-10-19T08:00:" + String.format("%02d", i) + "Z", "2026-10-19", "lab_report", "DONE");
                urgency(r, "routine");
            }
            assertThat(context.getBean(TimelineReadPort.class).attention(p, NOW.minus(Duration.ofDays(30)), 5)
                    .map(TimelineEntry::documentId).collectList().block()).containsExactly(now);
        });
    }

    /** The 30-day window (spec §4.6), both edges: uploaded exactly 30 days ago is in; one second earlier is out. */
    @Test
    void theAttentionWindowIncludesItsStartAndNothingBefore() {
        runner().run(context -> {
            UUID p = patient(context);
            UUID edge = doc(context, p, NOW.minus(Duration.ofDays(30)).toString(), "2026-09-20", "lab_report", "DONE");
            urgency(edge, "this_week");
            UUID before = doc(context, p, NOW.minus(Duration.ofDays(30)).minusSeconds(1).toString(), "2026-09-20", "lab_report", "DONE");
            urgency(before, "now");
            assertThat(context.getBean(TimelineReadPort.class).attention(p, NOW.minus(Duration.ofDays(30)), 5)
                    .map(TimelineEntry::documentId).collectList().block()).containsExactly(edge);
        });
    }
    // sql(), scalar(): as in FamilyDeletionAcceptanceTest
}
```

- [ ] **Step 2: red — each test against its named mutant (paste every red).**

  | Test | Mutant | Expected red |
  |---|---|---|
  | `rowsSharing…` | `page` with today's predicate `created_at < :created ORDER BY created_at DESC` | `Expected size: 1 but was: 0` (the 21st row skipped) |
  | `theTimelineIsOrdered…` | `ORDER BY d.created_at DESC` | `Expecting actual: [<july>, <october>] to contain exactly … [<october>, <july>]` |
  | `anUndatedUpload…` | read the date as `coalesce(doc_date, (created_at AT TIME ZONE 'UTC')::date)` | `expected: 2026-10-19 but was: 2026-10-18` |
  | `anEntryCarries…` | outside counted as `verdict IS DISTINCT FROM 'inside'` | `expected: 2 but was: 3` (the NULL row counted outside) |
  | `routineAndUnassessed…` | `chip()` returning `Optional.of(urgency)` for any non-null level | `expected: empty but was: Optional[ROUTINE]` |
  | `theLabFilter…` | `TimelineFilter.LAB.sql()` returning `""` | `… but some elements were not expected: [<rx>]` |
  | `attentionHolds…` | attention SQL joined to the latest explanation with `AND m.gate_status = 'released'` | `Expecting actual: [<week>] to contain exactly … [<nowPending>, <week>]` (the PENDING NOW missing) |
  | `manyNewerRoutine…` | attention as "the newest 50 papers, then filter in Java" (the draft's `RECENT_LIMIT`) | `Expecting actual: [] to contain exactly … [<now>]` |
  | `theAttentionWindow…` | `created_at > :since` | `Expecting actual: [] to contain exactly … [<edge>]` |

- [ ] **Step 3: implement.**

```java
package com.elioo.baymax.web.application.port.out;

public interface TimelineReadPort {
    /** One patient's papers after {@code after}: newest timeline_date, then upload, then id; at most {@code limit}. */
    Flux<TimelineEntry> page(UUID patientId, TimelineCursor after, TimelineFilter filter, int limit);

    /** DONE papers uploaded at or after {@code since} with a stored NOW or THIS_WEEK, in any review state; NOW first, then newest. */
    Flux<TimelineEntry> attention(UUID patientId, Instant since, int limit);
}
```

```sql
SELECT d.id, d.family_id, d.patient_id, d.document_type, d.facility, d.status, d.status_reason_code,
       d.doc_date, d.timeline_date, d.created_at, d.urgency,
       d.unverified_values + d.unverified_medicines + d.unverified_follow_up + d.unverified_clinical_context AS unverified, d.doc_date IS NULL AS date_is_fallback,
       (SELECT count(*) FROM %1$s.observation o WHERE o.document_id = d.id) AS values_n,
       (SELECT count(*) FROM %1$s.observation o WHERE o.document_id = d.id AND o.verdict IN ('outside', 'critical')) AS outside_n,
       (SELECT count(*) FROM %1$s.medication_event m WHERE m.document_id = d.id) AS medicines_n,
       (SELECT count(*) FROM %1$s.follow_up f WHERE f.document_id = d.id) AS follow_ups_n,
       (SELECT m.gate_status FROM %1$s.outbound_message m
         WHERE m.document_id = d.id AND m.kind = 'explanation' ORDER BY m.created_at DESC LIMIT 1) AS review_state
FROM %1$s.document d
WHERE d.patient_id = :patient
  AND (d.timeline_date, d.created_at, d.id) < (:date, :created, :id)
  %2$s
ORDER BY d.timeline_date DESC, d.created_at DESC, d.id DESC
LIMIT :limit
```

  `attention` uses the same column list with
  `WHERE d.patient_id = :patient AND d.status = 'DONE' AND d.urgency IN ('now', 'this_week') AND d.created_at >= :since
  ORDER BY CASE d.urgency WHEN 'now' THEN 0 ELSE 1 END, d.timeline_date DESC, d.created_at DESC, d.id DESC LIMIT :limit`.
  `%2$s` is `TimelineFilter.sql()`, a fixed fragment per value (`""`, `"AND d.document_type IN ('lab_report','imaging_report')"`,
  `"AND d.document_type = 'prescription'"`); request text never reaches the SQL. `TimelineFilter.fromParam("lab"|"rx"|other)`
  maps to LAB, PRESCRIPTION, ALL and `param()` back. `pageOneKey` from `StorageKeys.page(familyId, patientId, id, 1)`; the
  unverified sum is `VerifiedItems.Unverified.total()`'s, pinned by one assertion. `TimelineEntry.chip()`:

```java
    /** D27: NOW and THIS_WEEK get a chip; ROUTINE and unassessed get none. The rule lives here, once; UrgencyChip calls it. */
    public Optional<Urgency> chip() {
        return urgency == null || urgency == Urgency.ROUTINE ? Optional.empty() : Optional.of(urgency);
    }
```

### Task 16.3: `TimelineService` on the read model; the one-line summary; the JSON

**Files:** create `ui/TimelineSummary.java`, test `ui/TimelineSummaryTest.java`; modify `TimelineService.timeline`
(`TimelineReadPort timelineRead` appended last; `parseCursor` and `entry` deleted), `TimelineUseCase` (+4-argument
`timeline(..., TimelineFilter)`; the 3-argument delegates with `ALL`), `TimelineApiHandler.entry` (adds `urgency`,
`outside`, `review_state`, `retake_reason_code`; `next_cursor` is now the opaque form — noted in the PR), `TimelineServiceTest`,
`ui-papers_*`.

- [ ] **Step 1: failing tests.** `TimelineServiceTest`: mock `TimelineReadPort`; a page of 21 yields `nextCursor` equal
  to `new TimelineCursor(e20.date(), e20.createdAt(), e20.documentId()).encode()`; the share-member test stays green;
  the 404 test asserts `verify(timelineRead, never()).page(any(), any(), any(), anyInt())`; a garbage cursor is 400.
  `TimelineSummaryTest` (code-built; §4.15 forbids a model summary on the timeline):

```java
@Test
void aLabLineCountsValuesAndOutsideInBanglaDigits() {
    assertThat(TimelineSummary.line(copy, Lang.BN, lab(14, 2))).isEqualTo("১৪টি মান, ২টি সীমার বাইরে");
}

/** Nothing outside says nothing about the range: no "০টি সীমার বাইরে", no reassurance (principle 10). */
@Test
void nothingOutsideMentionsNoRange() {
    assertThat(TimelineSummary.line(copy, Lang.BN, lab(9, 0))).isEqualTo("৯টি মান").doesNotContain("সীমা");
}

@Test
void aPrescriptionCountsMedicinesAndAnUndatedOneSaysSo() {
    assertThat(TimelineSummary.line(copy, Lang.BN, rx(3, false))).isEqualTo("৩টি ওষুধ");
    assertThat(TimelineSummary.line(copy, Lang.BN, rx(2, true))).isEqualTo("২টি ওষুধ · কাগজে তারিখ পড়া যায়নি");
}
```

  Keys (`ui-papers_*`): `timeline.sum.values={0}টি মান` · `timeline.sum.outside={0}টি সীমার বাইরে` ·
  `timeline.sum.medicines={0}টি ওষুধ` · `timeline.sum.nodate=কাগজে তারিখ পড়া যায়নি` · `timeline.review.pending=একজন মিলিয়ে দেখছেন`
  (and English). `UiCopy.t` writes every Number argument in Bangla digits.
- [ ] **Step 2: red** — wired to the old `timeline.counts` key: `expected: "১৪টি মান, ২টি সীমার বাইরে" but was:
  "মান 14 · ওষুধ 0 · ফলো-আপ 0"`.
- [ ] **Step 3: implement** `TimelineSummary.line(UiCopy, Lang, TimelineEntry)`: lab/imaging → values (+ ", " outside when
  > 0); prescription → medicines; other → values and medicines when both > 0; `· nodate` when `dateIsFallback`.

### Task 16.4: the chip on today's list, from the bundle's short form

**Files:** create `ui/UrgencyChip.java`, tests `ui/UrgencyChipTest.java`; modify `messages_bn.properties`/`messages_en.properties`
(after `urgency.this_week`: `urgency.now.short`, `urgency.this_week.short`), `ui/AppTabs.papersBody` (each DONE item:
`UrgencyChip.html`, the pending chip **beside** it, `TimelineSummary.line` instead of `timeline.counts`; filter links
`সব · ল্যাব · প্রেসক্রিপশন` to `?type=`; "আরও দেখুন" carries `cursor` and `type`), `css/40-papers.css` (`.chip`, `.chip.now`,
`.chip.soon`), `WebOnlyJourneyAcceptanceTest` (after step 5: `GET /app/timeline?patient=…` shows the NOW short form in
that paper's row).

- [ ] **Step 1: copy (D28 short forms — drafts; the PO confirms or replaces them in the bundle, the tests read it):**

```properties
urgency.now.short=এখনই ডাক্তারের কাছে
urgency.this_week.short=এ সপ্তাহের মধ্যে ডাক্তার দেখান
```

  English: `urgency.now.short=See a doctor now`, `urgency.this_week.short=See a doctor this week`. `MessagesBundleTest`
  (PR 5) keeps bn/en keys identical.
- [ ] **Step 2: failing tests.**

```java
package com.elioo.baymax.web.adapter.in.ui;

/** Kit §6: NOW and THIS_WEEK only, the bundle's short form verbatim; ROUTINE and unassessed carry none of the lines. */
class UrgencyChipTest {

    private final UiCopy copy = new UiCopy();

    private static TimelineEntry entry(Urgency level) {
        return new TimelineEntry(UUID.randomUUID(), LocalDate.parse("2026-10-12"), false, "lab_report", "Shapla Diagnostics", "DONE",
                14, 0, 0, 0, "k", Instant.parse("2026-10-12T08:00:00Z"), level, 0, null, null);
    }

    @ParameterizedTest
    @EnumSource(value = Urgency.class, names = {"NOW", "THIS_WEEK"})
    void aChipSaysTheBundlesShortFormVerbatim(Urgency level) {
        assertThat(UrgencyChip.html(copy, Lang.BN, entry(level))).contains(copy.urgency(Lang.BN, level, true))
                .contains("class=\"chip " + (level == Urgency.NOW ? "now" : "soon") + "\"");
    }

    @ParameterizedTest
    @NullSource
    @EnumSource(value = Urgency.class, names = "ROUTINE")
    void routineAndUnassessedCarryNoneOfTheUrgencyLines(Urgency level) {
        String chip = UrgencyChip.html(copy, Lang.BN, entry(level));
        List<String> lines = BundleFiles.messages(Lang.BN).entrySet().stream().filter(e -> e.getKey().startsWith("urgency."))
                .map(e -> Html.esc(e.getValue())).toList();
        assertThat(lines).as("every urgency line, long and short").hasSizeGreaterThanOrEqualTo(4);
        assertThat(lines).allSatisfy(l -> assertThat(chip).doesNotContain(l));
        assertThat(chip).isEmpty();
    }
}
```

- [ ] **Step 3: red, and the mutant.** `cannot find symbol UrgencyChip`; then mutant `UrgencyChip.html` rendering for
  `e.urgency() != null` instead of `e.chip()` → `routineAndUnassessed…[ROUTINE]` red
  `IllegalArgumentException: ROUTINE has no urgency line (D27)`. Revert.
- [ ] **Step 4: implement.**

```java
package com.elioo.baymax.web.adapter.in.ui;

/** Kit §6: an icon and the bundle's short form for NOW and THIS_WEEK; nothing at all otherwise. Uses TimelineEntry.chip(). */
final class UrgencyChip {

    private UrgencyChip() {
    }

    static String html(UiCopy copy, Lang lang, TimelineEntry e) {
        return e.chip().map(level -> {
            boolean now = level == Urgency.NOW;
            return "<span class=\"chip " + (now ? "now" : "soon") + "\">" + Icons.svg(now ? "octagon" : "calendar", 18)
                    + copy.urgency(lang, level, true) + "</span>";
        }).orElse("");
    }
}
```

  CSS (`40-papers.css`): `.chips{display:flex;flex-wrap:wrap;gap:6px}`,
  `.chip{display:inline-flex;align-items:center;gap:6px;padding:var(--chip-pad-y) var(--sp-3) var(--chip-pad-y) var(--sp-2);border-radius:var(--r-pill);font-size:var(--fs-label-small);line-height:var(--lh-label-small);font-weight:600}`,
  `.chip.now{background:var(--c-status-now-solid);color:var(--c-on-status-now)}`,
  `.chip.soon{background:var(--c-status-soon-container);color:var(--c-status-soon)}`. (A `.chip` in the timeline is a
  `<span>`, not a control; A7's `label.chip` is the 48 px control.)
- [ ] **Step 5: run** `./gradlew :baymax:test` (+ `WebOnlyJourneyAcceptanceTest`), `./gradlew build`;
  `git status --ignored --short -- baymax/src | grep '^!!'` prints nothing (new `web/adapter/out/persistence` file).
  Standard pre-merge. Commit `feat(baymax): the timeline read model — document-date order, an opaque cursor, the stored chip`.

### Try it (C2) — prod-web

- [ ] Ma's papers are ordered by the date printed on each; a July report uploaded today sits under the October ones.
- [ ] t7 shows the red "এখনই ডাক্তারের কাছে" chip, t3 the amber "এ সপ্তাহের মধ্যে ডাক্তার দেখান", t1 nothing.
- [ ] A lab row reads like "১৪টি মান, ১টি সীমার বাইরে"; a prescription "৩টি ওষুধ".
- [ ] (With more than 20 papers) "আরও দেখুন" continues the list; no paper appears twice or goes missing at the join.

---

## PR C3 — L6: the কাগজপত্র tab (s16) — months, rows, filters, the empty state (UI only)

Branch `feat/baymax-ui-papers`. Spec §4.15, s16, kit §14. UI on C2's read model: month headers ("অক্টোবর ২০২৬"), each
paper one ≥ 88 px link with a drawn LAB/Rx thumb (no image per row, so a page of twenty costs no crop bytes), the date
and facility as printed, C2's summary line and chip (nothing on ROUTINE), the filter chips সব · ল্যাব · প্রেসক্রিপশন, a
tonal "কাগজ পাঠান" to `/app/add`, and the s16 empty state. No data or SQL changes here.

### Task L6.1: `TimelineView` and the s16 page

**Files:** create `ui/TimelineView.java`, test `ui/TimelineViewTest.java`; modify `ui/AppTabs.papersBody`,
`ui/UiCopy.java` (+`monthYear`, `dayMonth`), `outbound/application/service/BanglaDate.java` (+`monthYear`),
`css/40-papers.css`, `ui-papers_*`, `BanglaNumbersOnPagesTest` (the row strings), `AppShellRoutesTest` (+ the filter
and the empty state, both directions).

- [ ] **Step 1: failing tests.**

```java
package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.web.domain.TimelineEntry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** s16 / spec §4.15: what one paper's row shows, counted per row — never a substring of the whole page. */
class TimelineViewTest {

    private static final LocalDate OCT_12 = LocalDate.parse("2026-10-12");
    private static final Properties MESSAGES_BN = GlossaryTest.load("/baymax/messages_bn.properties");
    private final TimelineView view = new TimelineView(new UiCopy());

    private static TimelineEntry entry(String status, Urgency level, boolean fallback) {
        return new TimelineEntry(UUID.randomUUID(), OCT_12, fallback, "lab_report", "Diagnostic Centre, Mirpur", status,
                14, 0, 0, 0, "k", Instant.parse("2026-10-12T08:00:00Z"), level, 0, null, null);
    }

    private static List<String> chips(String html) {
        return Pattern.compile("class=\"(chip [a-z]+)\"").matcher(html).results().map(r -> r.group(1)).toList();
    }

    /** D27 + principle 3, both directions: NOW and THIS_WEEK carry their chip; ROUTINE carries nothing at all. */
    @Test
    void aTimelineEntryShowsTheStoredUrgencyChipAndRoutineShowsNone() {
        String now = view.item(Lang.BN, entry("DONE", Urgency.NOW, false), "আম্মা");
        String week = view.item(Lang.BN, entry("DONE", Urgency.THIS_WEEK, false), "আম্মা");
        String routine = view.item(Lang.BN, entry("DONE", Urgency.ROUTINE, false), "আম্মা");
        assertThat(chips(now)).containsExactly("chip now");
        assertThat(chips(week)).containsExactly("chip soon");
        assertThat(chips(routine)).as("ROUTINE: no chip, no colour, no word").isEmpty();
        assertThat(routine).doesNotContain("class=\"chips\"")
                .doesNotContain(MESSAGES_BN.getProperty("urgency.now.short"))
                .doesNotContain(MESSAGES_BN.getProperty("urgency.this_week.short"));
    }

    /** The chip text is the bundle's short form verbatim — read from the file WhatsApp uses, not retyped here. */
    @Test
    void theChipSaysTheBundlesShortFormVerbatim() {
        assertThat(MESSAGES_BN.getProperty("urgency.now.short")).isNotBlank();
        assertThat(view.item(Lang.BN, entry("DONE", Urgency.NOW, false), "আম্মা"))
                .contains(Html.esc(MESSAGES_BN.getProperty("urgency.now.short")));
        assertThat(view.item(Lang.BN, entry("DONE", Urgency.THIS_WEEK, false), "আম্মা"))
                .contains(Html.esc(MESSAGES_BN.getProperty("urgency.this_week.short")));
    }

    @Test
    void aPaperThatIsNotDoneSaysWhereItIsAndCarriesNoUrgency() {
        assertThat(view.item(Lang.BN, entry("PROCESSING", null, true), "আম্মা")).contains("পড়ছি…").contains("নতুন কাগজ · আম্মা");
        assertThat(view.item(Lang.BN, entry("NEEDS_RETAKE", null, true), "আম্মা")).contains("ঠিকমতো পড়তে পারিনি — আবার তুলতে হবে");
        for (String s : List.of("RECEIVED", "PROCESSING", "NEEDS_RETAKE", "FAILED")) {
            assertThat(chips(view.item(Lang.BN, entry(s, null, true), "আম্মা"))).as(s).isEmpty();
        }
    }

    /** Rule 13: UI dates and counts in Bangla digits with full month names; the facility as printed; English unchanged. */
    @Test
    void monthsDatesAndCountsAreBanglaAndTheUploadDateIsMarked() {
        String page = view.items(Lang.BN, List.of(entry("DONE", Urgency.ROUTINE, false), entry("DONE", Urgency.ROUTINE, true)), "আম্মা");
        assertThat(Pattern.compile("<h2>অক্টোবর ২০২৬</h2>").matcher(page).results().count()).as("one header per month").isEqualTo(1);
        assertThat(page).contains("১২ অক্টোবর · Diagnostic Centre, Mirpur").contains("১৪টি মান").contains("(আপলোডের তারিখ)");
        String en = view.items(Lang.EN, List.of(entry("DONE", Urgency.ROUTINE, false)), "Ma");
        assertThat(en).contains("<h2>October 2026</h2>").contains("2026-10-12 · Diagnostic Centre, Mirpur").contains("14 values");
    }

    @Test
    void theWholeRowIsOneLinkToThePaper() {
        TimelineEntry e = entry("DONE", Urgency.NOW, false);
        assertThat(view.item(Lang.BN, e, "আম্মা")).startsWith("<a class=\"titem\" href=\"/app/documents/" + e.documentId() + "\">").endsWith("</a>");
    }
}
```

  In `AppShellRoutesTest`: `?type=rx` reaches `timeline(…, TimelineFilter.PRESCRIPTION)` (Mockito `verify`), an unknown
  `?type=x` reaches `ALL`; an empty page shows `timeline.empty.title` with the name and, for the owner only, the
  "ছবি তুলুন" link to `/app/add?patient=<id>&back=…` (a share member's empty page has no link).
- [ ] **Step 2: red** (stubs returning `""`): `Expecting actual: [] to contain exactly: ["chip now"]` and the month
  header test `Expecting … to contain "<h2>অক্টোবর ২০২৬</h2>"`.
- [ ] **Step 3: implement.** `UiCopy`:

```java
    /** A month header: "অক্টোবর ২০২৬" / "October 2026". */
    public String monthYear(Lang lang, LocalDate date) {
        return Html.esc(lang == Lang.BN ? BanglaDate.monthYear(date)
                : date.getMonth().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH) + " " + date.getYear());
    }

    /** A date under its month header: "১২ অক্টোবর" (the header carries the year) / ISO on an English page, as before. */
    public String dayMonth(Lang lang, LocalDate date) {
        return Html.esc(lang == Lang.BN ? BanglaDate.format(date, date) : date.toString());
    }
```

  `BanglaDate.monthYear(LocalDate d)` = `MONTHS[d.getMonthValue() - 1] + " " + digits(String.valueOf(d.getYear()))`.
  `TimelineView` (the draft's, on C2's types):

```java
package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.web.domain.TimelineEntry;

import java.time.YearMonth;
import java.util.List;

import static com.elioo.baymax.web.adapter.in.ui.Html.esc;

/** The কাগজপত্র list (spec §4.15, s16, kit §14): month headers; each paper one ≥ 88 px link; F1 chips for NOW/THIS_WEEK. */
final class TimelineView {

    private final UiCopy copy;

    TimelineView(UiCopy copy) {
        this.copy = copy;
    }

    String items(Lang lang, List<TimelineEntry> entries, String patientName) {
        StringBuilder b = new StringBuilder();
        YearMonth month = null;
        for (TimelineEntry e : entries) {
            YearMonth m = YearMonth.from(e.date());
            if (!m.equals(month)) {
                b.append(month == null ? "" : "</ol></section>").append("<section class=\"month\"><h2>")
                        .append(copy.monthYear(lang, e.date())).append("</h2><ol class=\"titems\">");
                month = m;
            }
            b.append("<li>").append(item(lang, e, patientName)).append("</li>");
        }
        return b.append(month == null ? "" : "</ol></section>").toString();
    }

    String item(Lang lang, TimelineEntry e, String patientName) {
        StringBuilder b = new StringBuilder("<a class=\"titem\" href=\"").append(PageChrome.BASE).append("/documents/")
                .append(e.documentId()).append("\">").append(thumb(e)).append("<span class=\"tbody\">");
        String who = esc(patientName);
        switch (e.status()) {
            case "DONE" -> {
                b.append("<span class=\"ttitle\">").append(type(lang, e.documentType())).append(" · ").append(who).append("</span>")
                        .append("<span class=\"meta\">").append(dateLine(lang, e)).append("</span>")
                        .append("<span class=\"meta\">").append(TimelineSummary.line(copy, lang, e)).append("</span>");
                if (e.unverified() > 0) {
                    b.append("<span class=\"meta\">").append(copy.t(lang, "timeline.unverified", e.unverified())).append("</span>");
                }
                String chip = UrgencyChip.html(copy, lang, e);
                if (!chip.isEmpty()) {
                    b.append("<span class=\"chips\">").append(chip).append("</span>");
                }
            }
            case "NEEDS_RETAKE" -> b.append(state(lang, "type.default", who, e, "timeline.retake"));
            case "FAILED" -> b.append(state(lang, "type.default", who, e, "timeline.failed"));
            default -> b.append(state(lang, "timeline.reading.title", who, e, "timeline.reading"));
        }
        return b.append("</span>").append(Icons.svg("chevron-right", 24)).append("</a>").toString();
    }

    private String state(Lang lang, String titleKey, String who, TimelineEntry e, String stateKey) {
        return "<span class=\"ttitle\">" + copy.t(lang, titleKey) + " · " + who + "</span>"
                + "<span class=\"meta\">" + copy.t(lang, "timeline.sent", copy.dateText(lang, java.time.LocalDate.ofInstant(e.createdAt(),
                com.elioo.baymax.common.time.PatientCalendar.ZONE))) + "</span><span class=\"state\">" + copy.t(lang, stateKey) + "</span>";
    }

    private String dateLine(Lang lang, TimelineEntry e) {
        return copy.dayMonth(lang, e.date()) + (e.dateIsFallback() ? " " + copy.t(lang, "timeline.uploaddate") : "")
                + (e.facility() == null || e.facility().isBlank() ? "" : " · " + esc(e.facility()));
    }

    private String counts(Lang lang, TimelineEntry e) {
        String values = copy.t(lang, "timeline.count.values", e.values());
        String medicines = copy.t(lang, "timeline.count.medicines", e.medicines());
        return switch (e.documentType() == null ? "" : e.documentType()) {
            case "lab_report", "imaging_report" -> values;
            case "prescription" -> medicines;
            default -> e.values() > 0 && e.medicines() > 0 ? values + " · " + medicines : e.medicines() > 0 ? medicines : values;
        };
    }

    private String type(Lang lang, String type) {
        String label = type == null ? null : copy.maybe(lang, "type." + type);
        return label != null ? label : copy.t(lang, "type.default");
    }

    /** Kit §14: a drawn paper with a LAB/Rx badge — no image per row, so a page of twenty costs no crop bytes. */
    private static String thumb(TimelineEntry e) {
        String badge = "prescription".equals(e.documentType()) ? "Rx"
                : "lab_report".equals(e.documentType()) || "imaging_report".equals(e.documentType()) ? "LAB" : null;
        return "<span class=\"thumb\" aria-hidden=\"true\"><i></i><i></i><i></i>"
                + (badge == null ? "" : "<span class=\"badge\" lang=\"en\">" + badge + "</span>") + "</span>";
    }
}
```

  (delete the draft's private `counts` method: `TimelineSummary.line` replaces it; the NEEDS_RETAKE row adds the retake
  reason line from `e.retakeReasonCode()` via `retake.reason.<prefix>`.) `papersBody` puts together: `<h1>` `timeline.docs`;
  the sub-line `timeline.sub` (`{0}` = name, standalone until D2); for the owner
  `<a class="btn tonal" href="/app/add?patient=<id>&back=…">` + `Icons.svg("camera", 24)` + `timeline.send` + `</a>`;
  `<nav class="filters" aria-label="…">` with `?type=all|lab|rx` links (current one `aria-current="true"` + the check
  icon, kit §26b); `view.items(...)`, or the s16 empty panel (`timeline.empty.title`/`.body`, the owner-only
  `chat.empty.action` link to `/app/add?patient=…`, `timeline.empty.foot`); "আরও দেখুন" `?type=…&cursor=…`; the BMX-8
  flags-ask card (moved under the list). In-flight papers keep the 5 s refresh (`Head.refresh`, no script on this page).
- [ ] **Step 4: copy** (`ui-papers_*`): `timeline.docs=কাগজপত্র` / `Papers`; `timeline.sub=সব কাগজ, তারিখ ধরে — {0}` /
  `All papers, by date — {0}`; `timeline.send=কাগজ পাঠান` / `Send a paper`; `timeline.filter.label=কাগজের ধরন`;
  `timeline.filter.all=সব`, `.lab=ল্যাব`, `.rx=প্রেসক্রিপশন`; `timeline.reading.title=নতুন কাগজ`; `timeline.sent={0} পাঠানো` /
  `Sent {0}`; `timeline.reading=পড়ছি…`; `timeline.retake=ঠিকমতো পড়তে পারিনি — আবার তুলতে হবে`;
  `timeline.failed=পড়া যায়নি — আবার পাঠান`; `timeline.uploaddate=(আপলোডের তারিখ)`; `timeline.empty.title=কোনো কাগজ এখনো নেই — {0}` /
  `No papers yet — {0}`; `timeline.empty.body=যেকোনো রিপোর্ট বা প্রেসক্রিপশনের ছবি পাঠান — পুরনো হলেও চলবে`;
  `chat.empty.action` (in `ui-chat`, used here too) `=ছবি তুলুন` / `Take a photo`; `timeline.empty.foot=প্রতিটা কাগজ এখানে
  তারিখ অনুযায়ী সাজানো থাকবে`. The old `timeline.counts` goes once `git grep -n timeline.counts -- baymax/src` is empty.
- [ ] **Step 5: CSS** (`40-papers.css`):

```css
/* কাগজপত্র (s16; kit §6, §14, §19, §26b) */
.btn.tonal{background:var(--c-primary-container);color:var(--c-on-primary-container)}
.btn.tonal:hover{background:var(--c-surface-3);color:var(--c-text)}
.filters{display:flex;flex-wrap:wrap;gap:var(--sp-2);margin:var(--sp-4) 0}
.filters a{display:inline-flex;align-items:center;gap:6px;min-height:var(--target-min);padding:0 var(--sp-4);border:1px solid var(--c-outline);border-radius:var(--r-pill);background:var(--c-surface);color:var(--c-text);text-decoration:none;font-size:var(--fs-label);line-height:var(--lh-label);font-weight:600}
.filters a[aria-current=true]{padding-left:var(--sp-3);border:2px solid var(--c-primary);background:var(--c-primary-container);color:var(--c-on-primary-container);font-weight:700}
.month h2{margin:var(--sp-5) 0 var(--sp-3);font-size:var(--fs-title-3);line-height:var(--lh-title-3);font-weight:600}
.titems{list-style:none;margin:0;padding:0;display:flex;flex-direction:column;gap:var(--sp-2)}
.titem{display:flex;align-items:center;gap:var(--sp-3);min-height:88px;padding:var(--sp-3);background:var(--c-surface);border:1px solid var(--c-line);border-radius:var(--r-m);color:var(--c-text);text-decoration:none}
.thumb{position:relative;flex:none;display:flex;flex-direction:column;gap:5px;width:48px;height:64px;padding:9px 7px;background:var(--c-paper);border:1px solid var(--c-paper-line);border-radius:4px}
.thumb i{display:block;height:3px;background:var(--c-paper-line);border-radius:2px}
.thumb i:nth-child(2){width:70%}
.badge{position:absolute;left:4px;bottom:4px;padding:0 4px;border-radius:3px;background:var(--c-text);color:var(--c-bg);font-family:var(--font-latin);font-size:var(--fs-badge-latin);line-height:var(--lh-badge-latin);font-weight:700}
.tbody{flex:1;min-width:0;display:flex;flex-direction:column;gap:var(--sp-1)}
.ttitle{font-size:var(--fs-headline);line-height:var(--lh-headline);font-weight:600}
.titem .meta,.state{font-size:var(--fs-subhead);line-height:var(--lh-subhead);color:var(--c-text-muted)}
.chips{display:flex;flex-wrap:wrap;gap:6px}
.chip{display:inline-flex;align-items:center;gap:6px;padding:var(--chip-pad-y) var(--sp-3) var(--chip-pad-y) var(--sp-2);border-radius:var(--r-pill);font-size:var(--fs-label-small);line-height:var(--lh-label-small);font-weight:600}
.chip.now{background:var(--c-status-now-solid);color:var(--c-on-status-now)}
.chip.soon{background:var(--c-status-soon-container);color:var(--c-status-soon)}
.empty{display:flex;flex-direction:column;align-items:center;gap:var(--sp-4);padding:var(--sp-6) var(--sp-5);text-align:center;background:var(--c-surface);border-radius:var(--r-l)}
.empty .disc{display:flex;align-items:center;justify-content:center;width:72px;height:72px;border-radius:var(--r-pill);background:var(--c-primary-container);color:var(--c-primary)}
```

  (`.chip`, `.chip.now`, `.chip.soon` are already in this part from C2: delete the draft's duplicates.)
- [ ] **Step 5b: replays (mutants; revert each).** Render a ROUTINE chip as `chip routine` → `ROUTINE: no chip … Expecting
  empty but was: ["chip routine"]`; hard-code the chip text `"এখনই ডাক্তারের কাছে যান"` → `theChipSaysTheBundlesShortFormVerbatim`
  red naming the bundle string; point the tonal button at `/app/timeline?sheet=add` → `UploadEntryPointsTest` red.
- [ ] **Step 6: run** `./gradlew :baymax:test`, `./gradlew build`; standard pre-merge. Commit
  `feat(baymax-ui): কাগজপত্র — months, filters and the stored chip, nothing on routine`.

### Try it (C3) — prod-web

- [ ] কাগজপত্র: papers grouped under "অক্টোবর ২০২৬" by printed date; t9 (undated) says "(আপলোডের তারিখ)"; ল্যাব /
  প্রেসক্রিপশন filter the list and "সব" brings everything back.
- [ ] The red chip on t7, the amber on t3, nothing on t1; dark mode keeps them legible; English: "See a doctor now".
- [ ] t8 (blurred) reads "ঠিকমতো পড়তে পারিনি — আবার তুলতে হবে" with its reason, and no chip.
- [ ] Build report: the DevTools first-load total for `/app/timeline`.

---

## PR C4 — L7: the চ্যাট tab (s06) — what needs attention, in code-urgency order, and an honest composer

Branch `feat/baymax-ui-chat-home`. Spec §4.6. The চ্যাট tab shows the **attention stack**, highest first, each card with
one action: NOW (solid red header, the full `urgency.now` line, never dismissible — D14's "দেখেছি" is not built) ›
THIS_WEEK (amber) › waiting for a retake › a follow-up the doctor wrote due within seven days (the doctor's words on a
paper slip beside its crop) › being read. Levels are C2's stored ones (`TimelineReadPort.attention`, filtered in SQL, so
no number of newer routine papers can hide a NOW one). "Today" is the patient's calendar. An empty stack is silence —
no heading, no "সব ঠিক আছে". The composer says chat is coming (D11 not signed) and cannot send; its camera opens
`/app/add`. কাগজপত্র gets the "এখন যা দেখার" strip of the same urgent items. Zero model calls.

### Task L7.1: follow-ups due within seven days, and in-flight papers, on Postgres (both window edges)

**Files:** modify `NudgeDataPort` (+`openFollowUpsOfPatient`), `PostgresNudgeDataAdapter` (row mapping extracted into
`followUp(Row)`), `TimelineReadPort` (+`inFlightAndRetakes(UUID patientId, Instant since)`), `PostgresTimelineReadAdapter`;
create test `nudge/adapter/out/persistence/FollowUpsOfPatientOnPostgresTest.java`; add a case to `TimelineReadModelTest`.

- [ ] **Step 1: failing tests.** (`runner()`, `doc()` and `sql()` copied from `TimelineReadModelTest`; here
  `patient(context, number)` returns the `PatientProfile`, and the draft's `execute(...)` is `sql(...)`.)

```java
    /** s06: an open follow-up the doctor wrote for today .. today+7, soonest first; nothing outside, closed or another patient's. */
    @Test
    void openFollowUpsOfOnePatientInsideTheWindowSoonestFirst() {
        runner().run(context -> {
            NudgeDataPort data = context.getBean(NudgeDataPort.class);
            PatientProfile ma = patient(context, "+8801700000711");
            PatientProfile other = patient(context, "+8801700000712");
            UUID rx = doc(context, ma.id(), "2026-10-03T08:00:00Z", "2026-10-03", "prescription", "DONE");
            UUID otherRx = doc(context, other.id(), "2026-10-03T08:00:00Z", "2026-10-03", "prescription", "DONE");
            LocalDate today = LocalDate.parse("2026-10-19");
            followUp(ma, rx, "Next visit: 20/10/26", today.plusDays(1), "open");     // in
            followUp(ma, rx, "CBC after 7 days", today.plusDays(7), "open");         // in: the edge
            followUp(ma, rx, "Echo after 8 days", today.plusDays(8), "open");        // out: one past
            followUp(ma, rx, "Visit yesterday", today.minusDays(1), "open");         // out: before today
            followUp(ma, rx, "Done visit", today.plusDays(2), "done");               // out: closed
            followUp(other, otherRx, "Other patient", today.plusDays(2), "open");    // out: not Ma
            assertThat(data.openFollowUpsOfPatient(ma.id(), today, today.plusDays(7)).map(NudgeDataPort.FollowUpRow::instruction)
                    .collectList().block()).containsExactly("Next visit: 20/10/26", "CBC after 7 days");
        });
    }

    private static void followUp(PatientProfile p, UUID document, String instruction, LocalDate due, String status) throws Exception {
        execute("INSERT INTO baymax.follow_up (patient_id, document_id, instruction, due_date, status, crop_key) VALUES ('"
                + p.id() + "','" + document + "','" + instruction + "','" + due + "','" + status + "','f/p/" + document + "/crop-f.jpg')");
    }
```

  In `TimelineReadModelTest`: `inFlightAndRetakes(p, since)` returns the RECEIVED, PROCESSING and NEEDS_RETAKE papers
  uploaded at or after `since`, newest first, and neither a DONE nor a FAILED one nor one uploaded a second before
  `since` (the mutant `status <> 'DONE'` lets the FAILED one in: red `… but some elements were not expected: [<failed>]`).
- [ ] **Step 2: red:** `Expecting actual: [] to contain exactly (and in same order): ["Next visit: 20/10/26", "CBC after 7 days"]`
  with a default method returning `Flux.empty()`. **Replay:** `BETWEEN :from AND :to` → `>= :from` → red, the list now
  includes "Echo after 8 days".
- [ ] **Step 3: implement.**

```java
    /** Open follow-ups of one patient whose written date is in [from, to], soonest first (the চ্যাট attention stack). */
    Flux<FollowUpRow> openFollowUpsOfPatient(UUID patientId, LocalDate from, LocalDate to);
```

```java
    @Override
    public Flux<FollowUpRow> openFollowUpsOfPatient(UUID patientId, LocalDate from, LocalDate to) {
        return db.sql("SELECT u.id, u.patient_id, d.family_id, u.document_id, u.instruction, u.due_date, u.crop_key FROM " + S + ".follow_up u "
                        + "JOIN " + S + ".document d ON d.id = u.document_id WHERE u.patient_id = :patient AND u.status = 'open' "
                        + "AND u.due_date BETWEEN :from AND :to ORDER BY u.due_date, u.id")
                .bind("patient", patientId).bind("from", from).bind("to", to)
                .map((row, meta) -> followUp(row)).all();
    }
```

### Task L7.2: `AttentionService` — the stack in code-urgency order, on the patient's calendar

**Files:** create `web/domain/AttentionItem.java`, `web/application/port/in/AttentionUseCase.java`,
`web/application/service/AttentionService.java`, test `web/application/service/AttentionServiceTest.java`; modify
`PatientCalendarRulesAgreeTest` (+ the attention rule at the same instants).

- [ ] **Step 1: the domain** (pure):

```java
package com.elioo.baymax.web.domain;

import com.elioo.baymax.outbound.domain.Urgency;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.UUID;

/**
 * One card on the চ্যাট attention stack (spec §4.6, AttentionCard README), each with exactly one action. The order is
 * code urgency: NOW › THIS_WEEK › retake › the doctor's follow-up within seven days › being read. Within a rank the
 * newest paper first; follow-ups the soonest date first.
 */
public sealed interface AttentionItem {

    UUID documentId();

    int rank();

    long sortKey();

    Comparator<AttentionItem> ORDER = Comparator.comparingInt(AttentionItem::rank).thenComparingLong(AttentionItem::sortKey);

    record Urgent(UUID documentId, Urgency level, String documentType, LocalDate date, boolean dateIsFallback, Instant receivedAt)
            implements AttentionItem {
        public int rank() {
            return level == Urgency.NOW ? 0 : 1;
        }

        public long sortKey() {
            return -receivedAt.toEpochMilli();
        }
    }

    record Retake(UUID documentId, Instant receivedAt) implements AttentionItem {
        public int rank() {
            return 2;
        }

        public long sortKey() {
            return -receivedAt.toEpochMilli();
        }
    }

    record FollowUp(UUID documentId, String instruction, LocalDate dueDate, String cropKey) implements AttentionItem {
        public int rank() {
            return 3;
        }

        public long sortKey() {
            return dueDate.toEpochDay();
        }
    }

    record Reading(UUID documentId, Instant receivedAt) implements AttentionItem {
        public int rank() {
            return 4;
        }

        public long sortKey() {
            return -receivedAt.toEpochMilli();
        }
    }
}
```

- [ ] **Step 2: failing tests.**

```java
package com.elioo.baymax.web.application.service;

/** Spec §4.6: what needs attention, in code-urgency order, from stored F1 levels — never from a message row. */
class AttentionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-19T04:00:00Z");   // 10:00 in Dhaka
    private static final LocalDate TODAY = LocalDate.parse("2026-10-19");
    private static final UUID FAMILY = UUID.randomUUID();
    private static final UUID MA = UUID.randomUUID();

    private final HealthRecordPort records = mock(HealthRecordPort.class);
    private final TimelineReadPort timeline = mock(TimelineReadPort.class);
    private final NudgeDataPort nudgeData = mock(NudgeDataPort.class);
    private AttentionService service;

    @BeforeEach
    void wire() {
        service = new AttentionService(records, timeline, nudgeData, Clock.fixed(NOW, ZoneOffset.UTC));
        when(records.visiblePatient(FAMILY, MA)).thenReturn(Mono.just(new PatientAccess(
                new PatientProfile(MA, FAMILY, "আম্মা", 64, PatientProfile.Sex.FEMALE, List.of(), NOW, NOW), true)));
        when(timeline.attention(eq(MA), any(), anyInt())).thenReturn(Flux.empty());
        when(timeline.inFlightAndRetakes(eq(MA), any())).thenReturn(Flux.empty());
        when(nudgeData.openFollowUpsOfPatient(MA, TODAY, TODAY.plusDays(7))).thenReturn(Flux.empty());
    }

    private static TimelineEntry entry(String status, Urgency level, Duration ago) {
        return new TimelineEntry(UUID.randomUUID(), TODAY.minusDays(3), false, "lab_report", "Shapla", status, 14, 0, 0, 0, "k",
                NOW.minus(ago), level, 0, null, null);
    }

    @Test
    void theStackIsNowThenThisWeekThenRetakeThenFollowUpThenReading() {
        TimelineEntry nowNew = entry("DONE", Urgency.NOW, Duration.ofDays(1));
        TimelineEntry nowOld = entry("DONE", Urgency.NOW, Duration.ofDays(9));
        TimelineEntry week = entry("DONE", Urgency.THIS_WEEK, Duration.ofDays(5));
        TimelineEntry retake = entry("NEEDS_RETAKE", null, Duration.ofDays(2));
        TimelineEntry reading = entry("PROCESSING", null, Duration.ofMinutes(3));
        when(timeline.attention(eq(MA), eq(NOW.minus(Duration.ofDays(30))), anyInt())).thenReturn(Flux.just(nowNew, nowOld, week));
        when(timeline.inFlightAndRetakes(eq(MA), eq(NOW.minus(Duration.ofDays(30))))).thenReturn(Flux.just(reading, retake));
        UUID rx = UUID.randomUUID();
        when(nudgeData.openFollowUpsOfPatient(MA, TODAY, TODAY.plusDays(7))).thenReturn(Flux.just(
                new NudgeDataPort.FollowUpRow(UUID.randomUUID(), MA, FAMILY, rx, "CBC after 7 days", TODAY.plusDays(3), "k3"),
                new NudgeDataPort.FollowUpRow(UUID.randomUUID(), MA, FAMILY, rx, "Next visit: 20/10/26", TODAY.plusDays(1), "k1")));

        StepVerifier.create(service.attention(FAMILY, MA)).assertNext(stack -> {
            assertThat(stack).extracting(AttentionItem::documentId)
                    .containsExactly(nowNew.documentId(), nowOld.documentId(), week.documentId(), retake.documentId(), rx, rx, reading.documentId());
            assertThat(((AttentionItem.FollowUp) stack.get(4)).instruction()).isEqualTo("Next visit: 20/10/26");
        }).verifyComplete();
    }

    /** The service never re-filters by level or reads a message: what the read model calls urgent is the stack. */
    @Test
    void theUrgentCardsAreExactlyTheReadModelsAttentionRows() {
        TimelineEntry week = entry("DONE", Urgency.THIS_WEEK, Duration.ofDays(5));
        when(timeline.attention(eq(MA), any(), anyInt())).thenReturn(Flux.just(week));
        StepVerifier.create(service.attention(FAMILY, MA)).assertNext(stack -> assertThat(stack).singleElement()
                .isInstanceOfSatisfying(AttentionItem.Urgent.class, u -> assertThat(u.level()).isEqualTo(Urgency.THIS_WEEK))).verifyComplete();
    }

    @Test
    void aPatientThisFamilyCannotSeeIs404AndNothingIsRead() {
        UUID stranger = UUID.randomUUID();
        when(records.visiblePatient(FAMILY, stranger)).thenReturn(Mono.empty());
        StepVerifier.create(service.attention(FAMILY, stranger))
                .expectErrorMatches(e -> ((BaymaxException) e).status().value() == 404).verify();
        verifyNoInteractions(timeline, nudgeData);
    }
}
```

  `PatientCalendarRulesAgreeTest` gains, at A10's three instants: a follow-up due `2026-10-19` on a THIS_WEEK paper is on
  the stack **either** as the follow-up card **or** as the urgent card at every instant — never neither. (At 05:30 Dhaka
  on the 20th the follow-up window starts on the 20th, and UrgencyService has already raised `follow_up_past_due`: the
  paper is the THIS_WEEK card.) With A10's UTC rule restored as the mutant, the 05:30 case is red:
  `the paper vanished from the stack between the follow-up window and the urgency rule`.
- [ ] **Step 3: red:** stub `attention` returning `Mono.just(List.of())` → `Expecting actual: [] to contain exactly …`.
- [ ] **Step 4: implement.**

```java
@Service
@RequiredArgsConstructor
public class AttentionService implements AttentionUseCase {

    static final Duration WINDOW = Duration.ofDays(30);
    static final int FOLLOW_UP_DAYS = 7;
    static final int URGENT_LIMIT = 10;

    private final HealthRecordPort records;
    private final TimelineReadPort timeline;
    private final NudgeDataPort nudgeData;
    private final Clock clock;

    @Override
    public Mono<List<AttentionItem>> attention(UUID familyId, UUID patientId) {
        Instant now = clock.instant();
        LocalDate today = PatientCalendar.today(clock);
        Instant since = now.minus(WINDOW);
        return records.visiblePatient(familyId, patientId)
                .switchIfEmpty(Mono.error(BaymaxException.notFound("patient_not_found", "no patient with id " + patientId)))
                .flatMap(access -> Mono.zip(
                        timeline.attention(patientId, since, URGENT_LIMIT)
                                .map(e -> (AttentionItem) new AttentionItem.Urgent(e.documentId(), e.urgency(), e.documentType(), e.date(), e.dateIsFallback(), e.createdAt()))
                                .collectList(),
                        timeline.inFlightAndRetakes(patientId, since)
                                .map(e -> "NEEDS_RETAKE".equals(e.status()) ? (AttentionItem) new AttentionItem.Retake(e.documentId(), e.createdAt())
                                        : new AttentionItem.Reading(e.documentId(), e.createdAt()))
                                .collectList(),
                        nudgeData.openFollowUpsOfPatient(patientId, today, today.plusDays(FOLLOW_UP_DAYS))
                                .map(f -> (AttentionItem) new AttentionItem.FollowUp(f.documentId(), f.instruction(), f.dueDate(), f.cropKey()))
                                .collectList()))
                .map(t -> {
                    List<AttentionItem> all = new ArrayList<>(t.getT1());
                    all.addAll(t.getT2());
                    all.addAll(t.getT3());
                    all.sort(AttentionItem.ORDER);
                    return List.copyOf(all);
                });
    }
}
```

  `AttentionUseCase.attention(UUID familyId, UUID patientId)` — Javadoc: "a patient this family cannot see is a 404".
- [ ] **Step 5: run** → green. **Replay:** sort by `receivedAt` only → red `… [<nowNew>, …] but was [<reading>, <nowNew>, …]`.

### Task L7.3: `ChatView`, the s06 page, the strip on কাগজপত্র

**Files:** create `ui/ChatView.java`, test `ui/ChatViewTest.java`; modify `ui/AppTabs.java` (`chatBody` on
`AttentionUseCase` — field appended last; the strip in `papersBody`), `WebUiTestKit` (+`attention` mock, stubbed to
`Mono.just(List.of())`), `AppShellRoutesTest` (the never-preselect test now also verifies `attention` is never called; the
strip both directions), `WebOnlyJourneyAcceptanceTest` (+ step: after the NOW paper is DONE, `GET /app/home` shows the
`attn now` card linking that paper), `css/50-chat.css`, `ui-chat_*`.

- [ ] **Step 1: failing tests.**

```java
package com.elioo.baymax.web.adapter.in.ui;

/** s06 / spec §4.6 and principles 3, 5, 6: what the চ্যাট tab shows, and what it must never show. */
class ChatViewTest {

    private static final Instant T = Instant.parse("2026-10-18T08:00:00Z");
    private static final UUID MA = WebUiTestKit.MA_ID;
    private final UiCopy copy = new UiCopy();
    private final ChatView view = new ChatView(copy);

    private static AttentionItem urgent(Urgency level) {
        return new AttentionItem.Urgent(UUID.randomUUID(), level, "lab_report", LocalDate.parse("2026-10-12"), false, T);
    }

    private static long count(String html, String needle) {
        return Pattern.compile(Pattern.quote(needle)).matcher(html).results().count();
    }

    /** Principle 3: the NOW card says urgency.now verbatim, has one action, and cannot be dismissed (D14 not built). */
    @Test
    void aNowCardCarriesTheFixedLineAndOneActionAndNoWayToDismissIt() {
        String card = view.card(Lang.BN, urgent(Urgency.NOW), "আম্মা");
        assertThat(card).contains("class=\"attn now\"").contains(copy.urgency(Lang.BN, Urgency.NOW, false)).contains("১২ অক্টোবর");
        assertThat(count(card, "class=\"btn primary")).as("exactly one action").isEqualTo(1);
        assertThat(card).doesNotContain("দেখেছি").doesNotContain("aria-label=\"" + copy.t(Lang.BN, "sheet.close") + "\"").doesNotContain("<form");
        assertThat(view.card(Lang.BN, urgent(Urgency.THIS_WEEK), "আম্মা")).contains("class=\"attn soon\"")
                .contains(copy.urgency(Lang.BN, Urgency.THIS_WEEK, false));
    }

    /** Principles 5 and 6: the doctor's words on a paper slip with their crop; Medioo's own words never on one. */
    @Test
    void aFollowUpIsTheDoctorsWordsVerbatimBesideItsCropOnPaper() {
        UUID rx = UUID.randomUUID();
        String card = view.card(Lang.BN, new AttentionItem.FollowUp(rx, "Next visit: 20/10/26", LocalDate.parse("2026-10-20"), "f/p/" + rx + "/crop-f.jpg"), "আম্মা");
        String paper = card.substring(card.indexOf("<div class=\"paper\">"), card.indexOf("</div>", card.indexOf("<div class=\"paper\">")));
        assertThat(paper).contains(copy.t(Lang.BN, "paper.label")).contains("Next visit: 20/10/26")
                .contains("src=\"/app/documents/" + rx + "/image?key=f%2Fp%2F" + rx + "%2Fcrop-f.jpg\"");
        assertThat(card).contains("২০ অক্টোবর");
        assertThat(view.greeting(Lang.BN, "Tanvir")).contains("class=\"bubble\"").doesNotContain("class=\"paper\"");
    }

    /** The composer says chat is coming and cannot send; its camera opens the one upload entry, for the owner only. */
    @Test
    void thePlaceholderComposerSaysChatIsComingCannotSendAndItsCameraOpensTheAddSheet() {
        String composer = view.composer(Lang.BN, true, MA);
        assertThat(composer).contains(copy.t(Lang.BN, "chat.soon"))
                .doesNotContain("<input").doesNotContain("<textarea").doesNotContain("<form").doesNotContain("type=\"submit\"");
        assertThat(composer).contains("href=\"/app/add?patient=" + MA + "&amp;back=%2Fapp%2Fhome\"");
        assertThat(view.composer(Lang.BN, false, MA)).doesNotContain("/app/add");
    }

    /** An empty stack is silence, not reassurance: no heading, no "সব ঠিক". */
    @Test
    void nothingToAttendToShowsNoHeadingAndNoReassurance() {
        assertThat(view.stack(Lang.BN, List.of(), "আম্মা")).isEmpty();
        assertThat(view.page(Lang.BN, "Tanvir", "আম্মা", MA, List.of(), 3, true)).doesNotContain(copy.t(Lang.BN, "attn.heading", "আম্মা"))
                .doesNotContain("সব ঠিক").doesNotContain("চিন্তার কিছু নেই");
    }

    @Test
    void aPatientWithNoPapersGetsTheIntroAndOneAction() {
        String html = view.page(Lang.BN, "Tanvir", "আম্মা", MA, List.of(), 0, true);
        assertThat(html).contains(copy.t(Lang.BN, "chat.intro")).contains(copy.t(Lang.BN, "timeline.empty.title", "আম্মা"));
        assertThat(count(html, "class=\"btn primary")).isEqualTo(1);
    }
}
```

- [ ] **Step 2: red** (stubs returning `""`): `Expecting actual: "" to contain: "class="attn now""`.
- [ ] **Step 3: implement `ChatView`.** `card(lang, item, name)` switches on the sealed type: `Urgent` →
  `<section class="attn now|soon" aria-label="…"><div class="head">{octagon|calendar}<strong>{copy.urgency(lang, level, false)}</strong></div><div class="body"><span>{type} · {dayMonth}{uploaddate?}</span><a class="btn primary" href="/app/documents/{id}">{attn.open}</a></div></section>`;
  `Retake` → `class="attn retake"`, the camera disc, `attn.retake.title`, `timeline.sent`, one `btn outlined` (`attn.open`);
  `FollowUp` → `class="attn follow"`, `attn.follow.title` (`{0}` = `copy.dateText(lang, due)`), then
  `<div class="paper"><span class="label">{page icon}{paper.label}</span><p lang="en">{esc(instruction)}</p><img class="crop" loading="lazy" alt="" src="{DocumentPages.imageHref(doc, cropKey)}"></div>`,
  then one `btn quiet` (`attn.open`); `Reading` → `class="attn reading"`, `attn.reading.title`, `timeline.sent`, one
  `btn quiet` (`attn.reading.action`). `stack` is `""` for an empty list, else `<h2>{attn.heading}</h2><div class="attn-stack">…</div>`;
  `greeting` is `<p class="bubble">{chat.hello}</p>`; `composer(lang, owner, patientId)` is `<div class="composer">`, for
  the owner `<a class="icon-btn tonal" href="/app/add?patient={id}&amp;back=%2Fapp%2Fhome" aria-label="{chat.camera}">{camera}</a>`,
  then `<p class="soon">{chat.soon}</p></div>`; `page(...)` = greeting + stack + composer with documents, or greeting +
  `<p class="bubble">{chat.intro}</p>` + the empty panel (one `btn primary` `chat.empty.action` to `/app/add?patient=…`
  for the owner) + composer without. `AppTabs.chatBody` returns the page with `Head.refresh(10)` when the stack holds a
  `Reading` item. `papersBody` puts the strip "এখন যা দেখার" above the filters: the `Urgent` items only, each a compact
  link `{type} · {date}` + `UrgencyChip`; a ROUTINE-only patient has no strip and no heading (asserted both ways).
  The "never preselect" test in `AppShellRoutesTest` adds `verify(kit.attention, never()).attention(any(), any())`.
- [ ] **Step 4: copy** (`ui-chat_*`): `attn.heading=এখন যা দেখার — {0}` / `Needs attention now — {0}`;
  `attn.open=কাগজটা দেখুন` / `Open the paper`; `attn.retake.title=এই কাগজটা ঠিকমতো পড়তে পারিনি — আন্দাজে কিছু বলব না।`;
  `attn.follow.title=ডাক্তারের লেখা তারিখ: {0}` / `Date the doctor wrote: {0}`; `attn.reading.title=একটা কাগজ পড়ছি` /
  `Reading a paper`; `attn.reading.action=কতদূর হলো দেখুন` / `See how far it is`;
  `chat.intro=আমি Medioo — ডাক্তার নই। ডাক্তারি কাগজ গুছিয়ে রাখি, আর কাগজে যা লেখা তা সহজ বাংলায় দেখাই।`;
  `chat.camera=কাগজের ছবি পাঠান` / `Send a photo of a paper`. (`paper.label` is the shared `ui-shell` key.)
- [ ] **Step 5: CSS** (`50-chat.css`; `.paper` and `.paper .label` already live in `60-result.css`, so they are not
  repeated):

```css
.attn-stack{display:flex;flex-direction:column;gap:var(--sp-3);margin-bottom:var(--sp-5)}
.attn{overflow:hidden;background:var(--c-surface);border:1px solid var(--c-line);border-radius:var(--r-m)}
.attn .head{display:flex;gap:var(--sp-3);align-items:flex-start;padding:var(--sp-4) var(--sp-4) 0;font-weight:700}
.attn .body{display:flex;flex-direction:column;gap:var(--sp-3);padding:var(--sp-3) var(--sp-4) var(--sp-4)}
.attn.now{background:var(--c-status-now-container);border:0}
.attn.now .head{position:sticky;top:var(--header-h);min-height:var(--now-strip-h);padding:var(--sp-4);background:var(--c-status-now-solid);color:var(--c-on-status-now);font-size:var(--fs-title-3);line-height:var(--lh-title-3)}
.attn.soon{background:var(--c-status-soon-container);border:0}
.attn.soon .head svg{color:var(--c-status-soon)}
.attn.retake{background:var(--c-warn-container);border-color:var(--c-warn-line)}
.btn.outlined{background:var(--c-surface);color:var(--c-text);border:1px solid var(--c-outline)}
.icon-btn.tonal{background:var(--c-primary-container);color:var(--c-on-primary-container)}
```

- [ ] **Step 6: run** `./gradlew :baymax:test`, `./gradlew build`. **Replays:** render the follow-up instruction inside
  the bubble → `aFollowUpIsTheDoctorsWords…` red and `PaperIsNeverMediooTest` red; give the composer `<input name="q">` →
  `… not to contain "<input"`; write the NOW header with the short form → red naming the full `urgency.now` line.
  Standard pre-merge. Commit `feat(baymax-ui): চ্যাট shows what needs attention from the stored urgency, and says chat is coming`.

### Try it (C4)

- [ ] **prod-web:** with t7, t3, t8 and t9 on file, open **চ্যাট**: "এখন যা দেখার — <name>"; the NOW card first (red header
  "এখনই ডাক্তারের কাছে যান। দেরি করবেন না।"), then THIS_WEEK (amber), then the retake, then t9's follow-up (the doctor's
  words on a paper slip with its crop), then anything still being read. One button each; each opens its paper.
- [ ] **prod-web:** no close and no "দেখেছি" on the NOW card; scrolling keeps the red header pinned under the app header.
- [ ] **prod-web:** the composer says chat is coming; nothing can be typed; its camera opens the add sheet for the patient.
- [ ] **prod-web:** কাগজপত্র shows the same NOW and THIS_WEEK papers in its "এখন যা দেখার" strip.
- [ ] **prod-web:** the whole session (sign in, add, read, results, flags, sign out) without opening WhatsApp except for the code.

---

## PR D1 — L2b: self-hosted fonts that cover every Bangla string, and a first-load budget measured on what is served

Branch `feat/baymax-ui-fonts-budget`. Spec §8, §1 rule 14 (PO limit: first load ≤ 200 KB excluding crops; JS ≤ 40 KB and
only on enhanced pages). Noto Sans Bengali and Noto Sans (Latin) are subset, built reproducibly, served from the jar,
and declared `local()` first so most Android phones never download them. A test proves the subset covers every code
point the Bangla bundles and `messages_bn` can put on a page. The budget test takes its pages from the router (every
GET HTML route, each at its heaviest fixture) and weighs each asset **as served** through the test client, the fonts at
their self-hosted size (the worst case: `local()` missed).

### Task L2b.1: the fonts, built reproducibly, with a coverage check in the build script

**Files:** create `scripts/fonts/build-ui-fonts.sh`, `baymax/ui/noto-sans-bengali.woff2`, `baymax/ui/noto-sans-latin.woff2`,
`baymax/ui/OFL-Noto.txt` (OFL-1.1 from `ofl/notosansbengali/OFL.txt` at the same commit; private, not served); modify
`ui/AssetHandler.java` (`ASSETS` + the two woff2, `PRIVATE` + `OFL-Noto.txt`; `.woff2` → `font/woff2`), `css/00-tokens.css`
(`@font-face` after the token blocks).

- [ ] **Step 1: the script** (run by hand; outputs committed; values measured 2026-10-19), with the coverage assertion
  appended before its last line:

```bash
#!/usr/bin/env bash
# Builds Medioo's two self-hosted fallback fonts (SIL OFL 1.1). Run by hand from the repo root; outputs are committed.
# local() names come first in baymax.css, so most Android phones never download these (spec §8).
set -euo pipefail
OUT="baymax/src/main/resources/baymax/ui"
GF="47d54ca1ebb14fd864695f1d39dc734f39017c33"   # github.com/google/fonts main, 2026-10-19
BN_URL="https://raw.githubusercontent.com/google/fonts/$GF/ofl/notosansbengali/NotoSansBengali%5Bwdth%2Cwght%5D.ttf"
LA_URL="https://raw.githubusercontent.com/google/fonts/$GF/ofl/notosans/NotoSans%5Bwdth%2Cwght%5D.ttf"
BN_SHA="dcd42978094e584a849c84a51450eeac40c8826057d566ea6d4b9627a403a05a"
LA_SHA="bfb7bb691513f12e734dc346c03a03f784912432d7e3fa8e56efcf906fe86b3d"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
python3 -m venv "$WORK/venv"
"$WORK/venv/bin/pip" install -q fonttools==4.53.1 brotli==1.1.0
curl -fsSL "$BN_URL" -o "$WORK/bn.ttf"; echo "$BN_SHA  $WORK/bn.ttf" | shasum -a 256 -c -
curl -fsSL "$LA_URL" -o "$WORK/la.ttf"; echo "$LA_SHA  $WORK/la.ttf" | shasum -a 256 -c -
"$WORK/venv/bin/fonttools" varLib.instancer "$WORK/bn.ttf" wdth=100 wght=400:700 -q -o "$WORK/bn-i.ttf"
"$WORK/venv/bin/fonttools" varLib.instancer "$WORK/la.ttf" wdth=100 wght=400:700 -q -o "$WORK/la-i.ttf"
# Bengali block + danda + ZWNJ/ZWJ + dotted circle; every layout feature, because Bangla shaping needs them all
"$WORK/venv/bin/pyftsubset" "$WORK/bn-i.ttf" --unicodes="U+0964-0965,U+0980-09FE,U+200C-200D,U+25CC" \
  --layout-features='*' --flavor=woff2 --no-hinting --desubroutinize --output-file="$OUT/noto-sans-bengali.woff2"
"$WORK/venv/bin/pyftsubset" "$WORK/la-i.ttf" --unicodes="U+0020-007E,U+00A0-00FF,U+00B5,U+2013-2014,U+2018-201D,U+2022,U+2026,U+2192,U+2212" \
  --layout-features='*' --flavor=woff2 --no-hinting --desubroutinize --output-file="$OUT/noto-sans-latin.woff2"
ls -l "$OUT"/noto-sans-*.woff2   # expect 69,732 and 27,796 bytes
```

```bash
# Every code point a Bangla page can show must be in the Bengali subset's cmap (FontCoverageTest checks the CSS side).
"$WORK/venv/bin/python" - "$OUT/noto-sans-bengali.woff2" baymax/src/main/resources/baymax <<'PY'
import sys, glob
from fontTools.ttLib import TTFont
cmap = TTFont(sys.argv[1]).getBestCmap()
need = set()
for f in glob.glob(sys.argv[2] + "/ui*_bn.properties") + [sys.argv[2] + "/messages_bn.properties"]:
    for ch in open(f, encoding="utf-8").read():
        if 0x0980 <= ord(ch) <= 0x09FF or ch in "।॥‌‍":
            need.add(ord(ch))
missing = sorted(c for c in need if c not in cmap)
assert not missing, "missing from the Bengali subset: " + " ".join("U+%04X" % c for c in missing)
print("bengali subset covers", len(need), "code points")
PY
```

  Expected: `noto-sans-bengali.woff2` 69,732 bytes (wght 400–700 variable, 383 glyphs, every Bangla layout feature) and
  `noto-sans-latin.woff2` 27,796 bytes; record the actual numbers in the PR if a re-run differs.
- [ ] **Step 2: failing tests** (`BaymaxWebUiRouterTest`; a woff2's magic bytes are presence, so the content check is
  L2b.2):

```java
    @Test
    void theFontsAreServedAsWoff2FromTheJar() {
        for (String f : new String[]{"noto-sans-bengali.woff2", "noto-sans-latin.woff2"}) {
            client.get().uri("/assets/" + f).exchange().expectStatus().isOk()
                    .expectHeader().contentType("font/woff2")
                    .expectBody(byte[].class).value(b -> assertThat(new String(b, 0, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("wOF2"));
        }
        client.get().uri("/assets/OFL-Noto.txt").exchange().expectStatus().isNotFound();
    }
```

- [ ] **Step 3: implement** (`ASSETS`, the media type, and in `00-tokens.css`):

```css
/* Fonts (spec §8): the phone's own Noto first; the self-hosted subset only when it is missing. unicode-range keeps the
   Bengali file off English pages. One variable file per script serves 400, 600 and 700. */
@font-face{font-family:"Noto Sans Bengali";font-style:normal;font-weight:400;font-display:swap;src:local("Noto Sans Bengali"),local("NotoSansBengali-Regular"),local("Noto Sans Bengali UI"),url(/assets/noto-sans-bengali.woff2) format("woff2");unicode-range:U+0964-0965,U+0980-09FE,U+200C-200D,U+25CC}
@font-face{font-family:"Noto Sans Bengali";font-style:normal;font-weight:600;font-display:swap;src:local("Noto Sans Bengali SemiBold"),local("NotoSansBengali-SemiBold"),url(/assets/noto-sans-bengali.woff2) format("woff2");unicode-range:U+0964-0965,U+0980-09FE,U+200C-200D,U+25CC}
@font-face{font-family:"Noto Sans Bengali";font-style:normal;font-weight:700;font-display:swap;src:local("Noto Sans Bengali Bold"),local("NotoSansBengali-Bold"),local("Noto Sans Bengali UI Bold"),url(/assets/noto-sans-bengali.woff2) format("woff2");unicode-range:U+0964-0965,U+0980-09FE,U+200C-200D,U+25CC}
@font-face{font-family:"Noto Sans";font-style:normal;font-weight:400;font-display:swap;src:local("Noto Sans"),local("NotoSans-Regular"),url(/assets/noto-sans-latin.woff2) format("woff2");unicode-range:U+0020-007E,U+00A0-00FF,U+00B5,U+2013-2014,U+2018-201D,U+2022,U+2026,U+2192,U+2212}
@font-face{font-family:"Noto Sans";font-style:normal;font-weight:600;font-display:swap;src:local("Noto Sans SemiBold"),local("NotoSans-SemiBold"),url(/assets/noto-sans-latin.woff2) format("woff2");unicode-range:U+0020-007E,U+00A0-00FF,U+00B5,U+2013-2014,U+2018-201D,U+2022,U+2026,U+2192,U+2212}
@font-face{font-family:"Noto Sans";font-style:normal;font-weight:700;font-display:swap;src:local("Noto Sans Bold"),local("NotoSans-Bold"),url(/assets/noto-sans-latin.woff2) format("woff2");unicode-range:U+0020-007E,U+00A0-00FF,U+00B5,U+2013-2014,U+2018-201D,U+2022,U+2026,U+2192,U+2212}
```

  Each weight is its own face: a single `font-weight:400 700` face whose `local()` resolved to the static Regular would
  draw bold Bangla at regular weight.

### Task L2b.2: the subset covers the copy — on the CSS's unicode-range too

**Files:** create test `ui/FontCoverageTest.java`.

- [ ] **Step 1:**

```java
package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Every code point a Bangla page can show falls in the unicode-range of a self-hosted Bengali face. Derived from the bundles. */
class FontCoverageTest {

    private static final Pattern FACE = Pattern.compile("@font-face\\s*\\{([^}]*)}");
    private static final Pattern RANGE = Pattern.compile("U\\+([0-9A-Fa-f]{4})(?:-([0-9A-Fa-f]{4}))?");

    @Test
    void everyBanglaCodePointInTheCopyIsInABengaliFacesRange() throws Exception {
        String css = new WebUiTestKit().servedText("/assets/baymax.css");
        List<int[]> ranges = new ArrayList<>();
        for (Matcher f = FACE.matcher(css); f.find(); ) {
            if (f.group(1).contains("\"Noto Sans Bengali\"")) {
                for (Matcher r = RANGE.matcher(f.group(1)); r.find(); ) {
                    int lo = Integer.parseInt(r.group(1), 16);
                    ranges.add(new int[]{lo, r.group(2) == null ? lo : Integer.parseInt(r.group(2), 16)});
                }
            }
        }
        assertThat(ranges).as("Bengali unicode-ranges in the served CSS").isNotEmpty();
        TreeSet<Integer> need = new TreeSet<>();
        for (String part : UiCopy.PARTS) {
            BundleFiles.values(part, Lang.BN).values().forEach(v -> v.codePoints().filter(c -> c >= 0x0964 && c <= 0x09FF).forEach(need::add));
        }
        BundleFiles.messages(Lang.BN).values().forEach(v -> v.codePoints().filter(c -> c >= 0x0964 && c <= 0x09FF).forEach(need::add));
        assertThat(need).as("Bangla code points in the copy").hasSizeGreaterThan(50);
        List<String> outside = need.stream().filter(c -> ranges.stream().noneMatch(r -> c >= r[0] && c <= r[1]))
                .map(c -> String.format("U+%04X", c)).toList();
        assertThat(outside).isEmpty();
    }
}
```

- [ ] **Step 2: replay:** narrow the faces' `unicode-range` to `U+0980-09BF` → red listing the vowel signs and `U+09CD`
  (hasanta) that the copy uses. Revert.

### Task L2b.3: the first-load budget — every page the router serves, every asset as served

**Files:** create test `ui/FirstLoadBudgetTest.java`; modify `PageFixtures` (`VARIANTS` gain the heaviest fixtures:
`/app/timeline` with twenty entries, a third NOW and a third THIS_WEEK; `/app/home` with a full stack of two Urgent, one
Retake, one FollowUp, one Reading).

- [ ] **Step 1: test.**

```java
package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §1 rule 14: a page's first load is at most 200 KB excluding crops — the HTML, the stylesheet, and every asset
 * either names, each fetched through the test client as the browser would (bytes actually served), the fonts at their
 * self-hosted size. Pages come from the router; a route without a fixture fails in RenderedPages.
 */
class FirstLoadBudgetTest {

    static final int BUDGET = 200 * 1024;
    private static final Pattern HTML_ASSET = Pattern.compile("(?:href|src)=\"(/assets/[^\"?#]+)");
    private static final Pattern CSS_URL = Pattern.compile("url\\(");
    private static final Pattern CSS_ASSET = Pattern.compile("url\\(\\s*[\"']?(/assets/[^)\"'?#]+)[^)]*\\)");
    private static final Pattern CROP = Pattern.compile("/app/documents/[^/]+/(?:image\\?|page/)");

    @Test
    void everyPagesFirstLoadIsAtMostTwoHundredKilobytesExcludingCrops() {
        WebUiTestKit kit = new WebUiTestKit();
        String css = kit.servedText("/assets/baymax.css");
        int urls = (int) CSS_URL.matcher(css).results().count();
        List<String> cssAssets = CSS_ASSET.matcher(css).results().map(r -> r.group(1)).toList();
        assertThat(cssAssets).as("every url( in the stylesheet is an /assets/ file the budget can weigh").hasSize(urls);
        Map<String, String> pages = RenderedPages.allBangla();
        assertThat(pages).as("pages from the router").hasSizeGreaterThan(15);
        List<String> over = new ArrayList<>();
        for (Map.Entry<String, String> page : pages.entrySet()) {
            Map<String, Integer> parts = new LinkedHashMap<>();
            parts.put("html", page.getValue().getBytes(StandardCharsets.UTF_8).length);
            Set<String> assets = new LinkedHashSet<>(HTML_ASSET.matcher(page.getValue()).results().map(r -> r.group(1)).toList());
            if (assets.contains("/assets/baymax.css")) {
                assets.addAll(cssAssets);
            }
            for (String a : assets) {
                parts.put(a, kit.served(a).length);
            }
            if (!page.getKey().startsWith("GET /assets")) {
                assertThat(assets).as(page.getKey() + " counts the stylesheet and both fonts")
                        .contains("/assets/baymax.css", "/assets/noto-sans-bengali.woff2", "/assets/noto-sans-latin.woff2");
            }
            int total = parts.values().stream().mapToInt(Integer::intValue).sum();
            if (total > BUDGET) {
                over.add("%s is %,d bytes: %s".formatted(page.getKey(), total, parts));
            }
            assertThat(page.getValue()).as(page.getKey() + ": crops are lazy, never part of the first load")
                    .doesNotContainPattern("<img(?![^>]*loading=\"lazy\")[^>]*src=\"" + CROP.pattern());
        }
        assertThat(over).isEmpty();
    }
}
```

- [ ] **Step 2: run** → green; record each page's total in the PR body (the build report needs them). Estimate: landing
  ≈ 180 KB (fonts 97.5 KB, images 41 KB, CSS ≈ 26 KB, HTML ≈ 17 KB); `/app/add` with `upload.js` (D4) is weighed by
  this same test once D4 lands.
- [ ] **Step 3: replays.** Pad `noto-sans-bengali.woff2` to 190,000 bytes → red `GET / is 300,… bytes: {html=…, …}`.
  Add `.x{background:url(data:image/png;base64,AAAA)}` to a CSS part → red
  `every url( in the stylesheet is an /assets/ file …` (bytes the budget could not weigh). Remove the `@font-face` block →
  red naming the missing woff2. Revert each.
- [ ] **Step 4:** `./gradlew :baymax:test`, `./gradlew build`; standard pre-merge. Commit
  `feat(baymax-ui): self-hosted fonts that cover the copy, and a first-load budget on the served bytes`.

### Try it (D1) — prod-web

- [ ] Laptop DevTools → Network, cache disabled, "Fast 4G", `/app/home` signed in: total transferred (crops excluded)
  under 200 KB — write the number in the build report; no "Refused to load" in the console.
- [ ] Phone: Bangla renders with joined conjuncts (ক্ষ, ন্ত, র্ক); bold headings are bold.

---

## PR D2 — L3: the glossary ratchet emptied, and the F8 possessive in code

Branch `fix/baymax-copy-glossary`. Spec §7.4, §2 F8. R2's `GlossaryTest.KNOWN` listed every violation main had on
2026-10-19 (রোগী, নথি, স্বাভাবিক সীমা, বন্ধ করুন, টাইমলাইন, …) and has only shrunk since. This PR empties it, and builds the
possessive of a family-entered name in code (`NameGrammar`, which Phase 3's companion reuses — named in the PR body), so
the strings that waited with a standalone name (`A6`'s reminders, `chat.papers`, `timeline.sub`, `timeline.empty.title`,
`retake.same_patient`, `add.onlyone`) read naturally: "আম্মার রিমাইন্ডার চালু আছে", "রহিমের কাগজপত্র". No bundle writes
`{0}-এর`; `GLUED_CASE` holds that.

### Task L3.1: `NameGrammar.possessive`, both kinds of ending

**Files:** create `common/text/NameGrammar.java`, test `common/text/NameGrammarTest.java`.

- [ ] **Step 1: failing test.**

```java
package com.elioo.baymax.common.text;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec §2 F8: a vowel or vowel sign takes র, a consonant takes এর (as ের), Latin keeps a hyphen. */
class NameGrammarTest {

    @ParameterizedTest
    @CsvSource({
            "আম্মা,আম্মার", "আব্বু,আব্বুর", "নানু,নানুর", "মাতৃ,মাতৃর",    // vowel signs
            "রহিম,রহিমের", "শরৎ,শরতের", "জন্,জনের",                  // consonant, khanda ta, hasanta
            "Rina,Rina-র", "John,John-এর",                            // Latin keeps a hyphen
            "রং,রং-এর", "'  আম্মা  ',আম্মার"})                         // anything unusual: the plain hyphen form
    void thePossessiveFollowsTheLastLetter(String name, String expected) {
        assertThat(NameGrammar.possessive(name)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(value = {"NULL", "''"}, nullValues = "NULL")
    void nothingInNothingOut(String name) {
        assertThat(NameGrammar.possessive(name)).isEmpty();
    }
}
```

- [ ] **Step 2:** a stub returning `name + "-এর"`; red on the rule: `expected: "আম্মার" but was: "আম্মা-এর"`.
- [ ] **Step 3: implement.**

```java
package com.elioo.baymax.common.text;

/**
 * Spec §2 F8: the possessive of the name a family entered, built in code so no bundle writes "{patient}-এর". A vowel or
 * vowel sign takes র (আম্মার, আব্বুর); a consonant takes ের (রহিমের); khanda ta becomes ত before it (শরতের); a trailing
 * hasanta is dropped first (জনের); a Latin name keeps a hyphen (Rina-র, John-এর). Anything else — ং ঃ ঁ, a digit,
 * punctuation — takes "-এর", which is never wrong, only plainer. Self mode ("আপনার") arrives with the relation field.
 */
public final class NameGrammar {

    private static final String VOWEL_SIGNS = "ািীুূৃেৈোৌ";
    private static final String VOWELS = "অআইঈউঊঋএঐওঔ";
    private static final char HASANTA = '্';
    private static final char KHANDA_TA = 'ৎ';
    private static final char NUKTA = '়';

    private NameGrammar() {
    }

    public static String possessive(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String n = name.strip();
        char last = n.charAt(n.length() - 1);
        if (VOWEL_SIGNS.indexOf(last) >= 0 || VOWELS.indexOf(last) >= 0) {
            return n + "র";
        }
        if (last == KHANDA_TA) {
            return n.substring(0, n.length() - 1) + "তের";
        }
        if (last == HASANTA) {
            return n.substring(0, n.length() - 1) + "ের";
        }
        if (isBanglaConsonant(last) || last == NUKTA) {
            return n + "ের";
        }
        if ((last >= 'a' && last <= 'z') || (last >= 'A' && last <= 'Z')) {
            return n + ("aeiouyAEIOUY".indexOf(last) >= 0 ? "-র" : "-এর");
        }
        return n + "-এর";
    }

    private static boolean isBanglaConsonant(char c) {
        return (c >= 'ক' && c <= 'হ') || c == 'ড়' || c == 'ঢ়' || c == 'য়';
    }
}
```

### Task L3.2: `UiCopy.poss`, the rewrite, the ratchet emptied

**Files:** modify `ui/UiCopy.java` (+`poss`), every bundle part the rewrite touches, `messages_bn.properties:47`,
`GlossaryTest` (`KNOWN = Set.of()`; the `MESSAGE_EXEMPT` entry for `explanation.routine` stays — it is the PO's D35
wording), `UiCopyTest`, `BanglaNumbersOnPagesTest`, `FamilyViewTest` (back to "আম্মার রিমাইন্ডার …"), `ChatViewTest`,
`TimelineViewTest`, `NeedsRetakeRenderTest`, `AddReportSheetPageTest`.

- [ ] **Step 1: `UiCopy.poss`:**

```java
    /**
     * The possessive of a name as this page writes it (spec §2 F8): "আম্মার" on a Bangla page, "Ma's" on an English one.
     * Not escaped — pass it to {@link #t}, which escapes every argument exactly once.
     */
    public String poss(Lang lang, String name) {
        if (lang == Lang.BN) {
            return com.elioo.baymax.common.text.NameGrammar.possessive(name);
        }
        String n = name == null ? "" : name.strip();
        return n.isEmpty() ? "" : n + (n.endsWith("s") ? "'" : "'s");
    }
```

- [ ] **Step 2: the rewrite** (copy direction for the copy owner; she may reword inside the glossary). The draft table,
  with each key in the part that owns it after A2–C4:

  | key | new bn | new en |
  |---|---|---|
  | `landing.c.ref` | `রিপোর্টে লেখা সীমা 0.6–1.2` | (unchanged) |
  | `app.home.title` | `Medioo` | `Medioo` |
  | `app.back.patients` | `← পরিবার` | `← Family` |
  | `patients` | `যাঁদের কাগজ রাখছেন` | `Whose papers you keep` |
  | `patients.none` | `এখনো কারো ফাইল খোলা হয়নি।` | `No one's file is open yet.` |
  | `patients.docs` | `{0}টি কাগজ` | `{0} papers` |
  | `patients.last` | `শেষ কাগজ: {0}` | `Last paper: {0}` |
  | `timeline.add` | `নতুন কাগজ যোগ করুন` | `Add a new paper` |
  | `timeline.docs` | `কাগজপত্র` | `Papers` |
  | `timeline.none` | `এখনো কোনো কাগজ নেই।` | `No papers yet.` |
  | `doc.delete` / `.q` | `কাগজটা মুছুন` / `কাগজটা মুছবেন?` | `Delete this paper` / `Delete this paper?` |
  | `doc.delete.warn` | `মুছে ফেললে কাগজের ছবি আর পড়া তথ্য চিরতরে মুছে যাবে। ফেরানো যাবে না।` | (keep) |
  | `doc.deleted` / `.back` | `কাগজটা মুছে ফেলা হয়েছে` / `কাগজপত্রে ফিরুন` | `The paper was deleted` / `Back to papers` |
  | `type.default` | `কাগজ` | `Paper` |
  | `profile.nudges.on` | `{0} রিমাইন্ডার চালু আছে — Medioo নিজে থেকে মনে করিয়ে দেয় (ফলো-আপ, ওষুধ বদল, মান বদলানো, অনেকদিন খবর না থাকলে)।` | `{0} reminders are on — Medioo reminds you on its own (follow-ups, medicine changes, values drifting, long silences).` |
  | `profile.nudges.off` | `{0} রিমাইন্ডার বন্ধ আছে।` | `{0} reminders are off.` |
  | `profile.nudges.stop` | `{0} রিমাইন্ডার চাই না` | `No reminders for {0}` (en `{0}` gets the plain name — see below) |
  | `optout.title` | `Medioo-র নিজে থেকে পাঠানো বার্তা` | `Messages Medioo sends on its own` |
  | `optout.patient` | `শুধু এঁর রিমাইন্ডার চাই না` | `No reminders for this person` |
  | `optout.family` | `পরিবারের কারো রিমাইন্ডার চাই না` | `No reminders for anyone in the family` |
  | `optout.done.patient` | `এঁর জন্য আর কোনো রিমাইন্ডার যাবে না।` | `No more reminders will go for this person.` |
  | `optout.done.family` | `পরিবারের কারো জন্য আর কোনো রিমাইন্ডার যাবে না।` | `No more reminders will go for anyone in the family.` |
  | `flag.kidney` / `flag.heart` | `কিডনির রোগ` / `হার্টের রোগ` (s21) | (keep) |

  plus the possessive forms of the strings that waited: `chat.papers={0} কাগজপত্র দেখুন` (`{0}` = `copy.poss(...)`),
  `timeline.sub={0} সব কাগজ, তারিখ ধরে`, `timeline.empty.title={0} কোনো কাগজ এখনো নেই`, `retake.same_patient={0} নামেই যাবে`
  (the drawn s12 wording), `add.onlyone=শুধু {0} ফাইল আছে, তাই {1} বেছে রাখা হয়েছে` (`{0}` possessive, `{1}` the plain name;
  the drawn s07 wording "শুধু আম্মার ফাইল আছে, তাই আম্মাকেই…" needs the objective case `-কে`, which F8 also defines; until it
  is in `NameGrammar` the shipped form says "বেছে রাখা হয়েছে"), `profile.nudges.on={0} রিমাইন্ডার চালু আছে — …`,
  `.off={0} রিমাইন্ডার বন্ধ আছে।`, `.stop={0} রিমাইন্ডার চাই না`. Each call site passes `copy.poss(lang, name)` on a Bangla
  page; on an English page the plain name where the English reads better (`No reminders for {0}`). `messages_bn.properties:47`:
  `line.value_outside_range={value_name} এসেছে {value} {unit}, রিপোর্টে লেখা সীমা {ref_low}–{ref_high}।`.
  `git grep -n "নথি\|রোগী" -- baymax/src/test` finds the assertions to update (`UiCopyTest`'s `"&lt;b&gt;টি কাগজ"`,
  `"১০টি কাগজ"`, `"8.2টি কাগজ"`; `BanglaNumbersOnPagesTest`'s `"১০টি কাগজ · শেষ কাগজ: ১৮ অক্টোবর ২০২৬"`).
- [ ] **Step 3: run** `./gradlew :baymax:test` → green; `GlossaryTest` with `KNOWN` empty.
- [ ] **Step 4: replays.** Put back `patients.docs=নথি: {0}` → red `new glossary violations: [ui-family:patients.docs নথি]`;
  `profile.nudges.on={0}-এর জন্য …` → red `… glued-case`. Revert.
- [ ] **Step 5:** standard pre-merge. Commit `fix(baymax-copy): one term each — কাগজ, the patient's name, রিপোর্টে লেখা সীমা`.

### Try it (D2) — prod-web

- [ ] Home, papers, family and a result in Bangla: no "রোগী", no "নথি"; পরিবার says "<name>র রিমাইন্ডার চালু আছে" with the
  right ending for the name (আম্মার, রহিমের); English: "Ma's reminders are on".

---

## PR D3 — W4.2: "আলাদা আলাদা", and a retry that can never make a second document (V21)

Branch `feat/baymax-upload-key`. Spec §4.8 (s08's "একটা রিপোর্ট / আলাদা আলাদা"), and the precondition for D4's retry:
a key per press of "পাঠান", stored on `document` with a partial unique index, so a double tap, a browser resubmit or
the script's retry returns the document it already made — never a second document, a second free slot or a second
explanation. "আলাদা আলাদা" is all-or-nothing: every file is read and the allowance checked for all of them before any
document exists. This is the only PR that touches the write side of `DocumentRecordPort`. **Pre-merge:** the migration
preflight prints `V20__retake_reason_code_and_timeline_date.sql`.

### Task W2.1: the key in the database — one row per family and key, enforced there; the race on real Postgres

**Files:** create `V21__document_upload_key.sql`, `extraction/domain/CreatedDocument.java`, test
`extraction/adapter/out/persistence/DocumentUploadKeyTest.java`; modify `DocumentRecordPort` (+`createOnce`,
`findByUploadKey`), `PostgresDocumentRecordAdapter`, `DocumentEntity` (`@Column("upload_key") @ReadOnlyProperty private String uploadKey;`
— written only by `createOnce`'s SQL), `DocumentRepository` (+`findByFamilyIdAndUploadKey`).

```sql
-- D3 (web upload): the key a browser sends with one press of "পাঠান". A retry after a dropped connection sends the
-- same key and gets the same document back: never a second document, a second free slot or a second explanation.
-- Scoped per family. NULL for WhatsApp and the JSON API, which have their own idempotency (inbound message ids).
-- A column, not a table: it goes when the document goes, so both delete paths already cover it.
ALTER TABLE ${flyway:defaultSchema}.document ADD COLUMN upload_key varchar(80) NULL
    CHECK (upload_key IS NULL OR upload_key ~ '^[A-Za-z0-9:-]{8,80}$');
CREATE UNIQUE INDEX document_upload_key_uq ON ${flyway:defaultSchema}.document (family_id, upload_key)
    WHERE upload_key IS NOT NULL;
```

- [ ] **Step 1: failing tests.**

```java
package com.elioo.baymax.extraction.adapter.out.persistence;

/**
 * The retry rule on real Postgres. A mock ledger cannot fail the way the database does (CLAUDE.md, DR-22): a second
 * insert with the same key comes back as "already there", the database refuses a duplicate from any writer, and two
 * presses racing on separate connections make one row.
 */
@Testcontainers
class DocumentUploadKeyTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final Instant T = Instant.parse("2026-10-19T08:00:00Z");

    private ReactiveWebApplicationContextRunner runner() {
        return PostgresNudgeAdapterTest.runnerFor(POSTGRES);
    }

    private static PatientProfile patient(ApplicationContext context, String number) {
        HealthRecordPort records = context.getBean(HealthRecordPort.class);
        FamilyAccount f = records.createFamily(new FamilyAccount(null, number, "A", FamilyAccount.Plan.FREE, T, T)).block();
        return records.createPatient(new PatientProfile(null, f.id(), "Ma", 64, PatientProfile.Sex.FEMALE, List.of(), T, T)).block();
    }

    @Test
    void theSameKeyTwiceIsOneDocumentAndAnotherFamilysSameKeyIsItsOwn() {
        runner().run(context -> {
            DocumentRecordPort documents = context.getBean(DocumentRecordPort.class);
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            DatabaseClient db = context.getBean(DatabaseClient.class);
            Instant t = Instant.parse("2026-10-19T08:00:00Z");
            FamilyAccount a = records.createFamily(new FamilyAccount(null, "+8801700000721", "A", FamilyAccount.Plan.FREE, t, t)).block();
            FamilyAccount b = records.createFamily(new FamilyAccount(null, "+8801700000722", "B", FamilyAccount.Plan.FREE, t, t)).block();
            PatientProfile pa = records.createPatient(new PatientProfile(null, a.id(), "Ma", 64, PatientProfile.Sex.FEMALE, List.of(), t, t)).block();
            PatientProfile pb = records.createPatient(new PatientProfile(null, b.id(), "Ma", 70, PatientProfile.Sex.FEMALE, List.of(), t, t)).block();

            CreatedDocument first = documents.createOnce(Document.received(pa.id(), a.id(), 2, t), "k-0f8e2c1a").block();
            CreatedDocument retry = documents.createOnce(Document.received(pa.id(), a.id(), 2, t), "k-0f8e2c1a").block();
            CreatedDocument other = documents.createOnce(Document.received(pb.id(), b.id(), 1, t), "k-0f8e2c1a").block();

            assertThat(first.fresh()).isTrue();
            assertThat(retry.fresh()).isFalse();
            assertThat(retry.document().id()).isEqualTo(first.document().id());
            assertThat(other.fresh()).isTrue();
            assertThat(other.document().id()).isNotEqualTo(first.document().id());
            assertThat(documents.findByUploadKey(a.id(), "k-0f8e2c1a").block().id()).isEqualTo(first.document().id());
            // asserted on the table, not on the port's own answer
            Long rows = db.sql("SELECT COUNT(*) FROM " + BaymaxSchema.NAME + ".document WHERE upload_key = 'k-0f8e2c1a'")
                    .map((row, meta) -> row.get(0, Long.class)).one().block();
            assertThat(rows).isEqualTo(2L);
        });
    }

    @Test
    void theDatabaseRefusesASecondRowWithTheKeyFromAnyWriterButNotTwoRowsWithoutOne() {
        runner().run(context -> {
            PatientProfile p = patient(context, "+8801700000723");
            DatabaseClient db = context.getBean(DatabaseClient.class);
            String insert = "INSERT INTO " + BaymaxSchema.NAME + ".document (patient_id, family_id, upload_key) VALUES (:p, :f, :k)";
            db.sql(insert).bind("p", p.id()).bind("f", p.familyId()).bind("k", "k-dup-0001").then().block();
            assertThatThrownBy(() -> db.sql(insert).bind("p", p.id()).bind("f", p.familyId()).bind("k", "k-dup-0001").then().block())
                    .hasMessageContaining("document_upload_key_uq");
            assertThatThrownBy(() -> db.sql(insert).bind("p", p.id()).bind("f", p.familyId()).bind("k", "bad key!").then().block())
                    .hasMessageContaining("document_upload_key_check");
            String plain = "INSERT INTO " + BaymaxSchema.NAME + ".document (patient_id, family_id) VALUES (:p, :f)";
            db.sql(plain).bind("p", p.id()).bind("f", p.familyId()).then().block();
            db.sql(plain).bind("p", p.id()).bind("f", p.familyId()).then().block();   // WhatsApp/API rows: no key, no conflict
        });
    }

    /** Two presses racing on separate connections: exactly one is fresh, both answer the same id, one row exists. */
    @Test
    void twoConcurrentPressesWithOneKeyMakeOneDocument() {
        runner().run(context -> {
            PatientProfile p = patient(context, "+8801700000724");
            DocumentRecordPort documents = context.getBean(DocumentRecordPort.class);
            Mono<CreatedDocument> press = Mono.defer(() -> documents.createOnce(Document.received(p.id(), p.familyId(), 1, T), "k-race-0001"))
                    .subscribeOn(Schedulers.boundedElastic());
            var both = Mono.zip(press, press).block();
            assertThat(List.of(both.getT1().fresh(), both.getT2().fresh())).containsExactlyInAnyOrder(true, false);
            assertThat(both.getT1().document().id()).isEqualTo(both.getT2().document().id());
            assertThat(scalar("SELECT count(*) FROM baymax.document WHERE upload_key = 'k-race-0001'")).isEqualTo("1");
        });
    }
    // sql(), scalar(): as in FamilyDeletionAcceptanceTest
}
```

  (Two subscriptions on `boundedElastic` take two pool connections; `ON CONFLICT … DO NOTHING` makes the loser's
  `RETURNING` empty, and it reads the winner's row.)
- [ ] **Step 2: red, on the defect.** Compile errors; then (migration present, `createOnce` delegating to `create`)
  `expected: false but was: true` on `retry.fresh()`, and the race test `Expecting actual: [true, true] to contain
  exactly in any order: [true, false]` with `count = 2`. Replay the second test with the `CREATE UNIQUE INDEX` removed →
  `Expecting code to raise a throwable`: the index is what refuses the duplicate.
- [ ] **Step 3: implement** (`PostgresDocumentRecordAdapter`):

```java
    @Override
    public Mono<CreatedDocument> createOnce(Document d, String uploadKey) {
        if (uploadKey == null) {
            return create(d).map(doc -> new CreatedDocument(doc, true));
        }
        return db.sql("INSERT INTO " + S + ".document (patient_id, family_id, status, page_count, created_at, updated_at, upload_key) "
                        + "VALUES (:patient, :family, :status, :pages, :at, :at, :key) "
                        + "ON CONFLICT (family_id, upload_key) WHERE upload_key IS NOT NULL DO NOTHING RETURNING id")
                .bind("patient", d.patientId()).bind("family", d.familyId()).bind("status", d.status().name())
                .bind("pages", d.pageCount()).bind("at", OffsetDateTime.ofInstant(d.createdAt(), ZoneOffset.UTC)).bind("key", uploadKey)
                .map((row, meta) -> row.get("id", UUID.class)).one()
                .flatMap(id -> find(id).map(doc -> new CreatedDocument(doc, true)))
                .switchIfEmpty(Mono.defer(() -> findByUploadKey(d.familyId(), uploadKey).map(doc -> new CreatedDocument(doc, false))));
    }
```

  `writeDocument` never names `upload_key` (`DocumentUpdateBindingTest` stays green); both delete paths remove the row and
  its key; no new table, so `FamilyDeletionAcceptanceTest` needs no fixture row — run it anyway. `OneUrgencyWriterTest`
  sees an `INSERT INTO … document (…)` without urgency columns: green.
- [ ] **Step 4: run** `./gradlew :baymax:test --tests '*DocumentUploadKeyTest' --tests '*FamilyDeletionAcceptanceTest' --tests '*DocumentUpdateBindingTest'`.

### Task W2.2: intake — a replay costs nothing, a race stores once, a failed store leaves nothing, "আলাদা আলাদা" all-or-nothing

**Files:** modify `DocumentIntakeUseCase` (+`accept(UUID, List<Upload>, String)`, `acceptEach`), `DocumentIntakeService`,
`DocumentIntakeServiceTest` (`setUp` stubs `createOnce` and `findByUploadKey → Mono.empty()`; every existing
`create(...)` stub or verify becomes `createOnce`, so no test stubs a path that is no longer taken),
`TryDocumentsThroughTheWebTest` (the same).

- [ ] **Step 1: failing tests.**

```java
    /** A retry of the same press returns the document it already made: nothing rendered, stored, counted or processed. */
    @Test
    void aRetryWithTheSameKeyReturnsTheFirstDocumentAndCostsNothing() throws Exception {
        Document first = Document.received(PATIENT, FAMILY, 2, NOW).withId(UUID.randomUUID());
        when(documents.findByUploadKey(FAMILY, "k-7a3e9b10")).thenReturn(Mono.just(first));
        StepVerifier.create(service.accept(PATIENT, List.of(jpeg("a.jpg"), jpeg("b.jpg")), "k-7a3e9b10"))
                .assertNext(d -> assertThat(d.id()).isEqualTo(first.id())).verifyComplete();
        verify(freeTier, never()).checkCanUploadDocument(any(UUID.class));
        verify(documents, never()).createOnce(any(), any());
        verify(storage, never()).storePage(any(), any(), any(), anyInt(), any());
        verify(extraction, never()).process(any(), any());
    }

    /** The other direction: a new press is a new document, and it is counted. */
    @Test
    void aNewKeyIsANewDocumentAndIsCounted() throws Exception {
        StepVerifier.create(service.accept(PATIENT, List.of(jpeg("a.jpg")), "k-new00001")).assertNext(d -> assertThat(d.id()).isNotNull()).verifyComplete();
        verify(freeTier).checkCanUploadDocument(FAMILY);
        verify(documents).createOnce(any(), eq("k-new00001"));
    }

    @Test
    void aKeyThatBelongsToAnotherPatientsDocumentIsAConflictNotAReplay() throws Exception {
        when(documents.findByUploadKey(FAMILY, "k-other001")).thenReturn(Mono.just(Document.received(UUID.randomUUID(), FAMILY, 1, NOW).withId(UUID.randomUUID())));
        StepVerifier.create(service.accept(PATIENT, List.of(jpeg("a.jpg")), "k-other001"))
                .expectErrorMatches(e -> e instanceof BaymaxException b && b.reason().equals("upload_key_reused") && b.status().value() == 409).verify();
    }

    /** Two presses racing: createOnce answers fresh=false exactly as DocumentUploadKeyTest proved on Postgres; the loser stores nothing. */
    @Test
    void theLoserOfARaceOnTheSameKeyStoresNoPagesAndStartsNoProcessing() throws Exception {
        Document winner = Document.received(PATIENT, FAMILY, 1, NOW).withId(UUID.randomUUID());
        when(documents.createOnce(any(), eq("k-race0001"))).thenReturn(Mono.just(new CreatedDocument(winner, false)));
        StepVerifier.create(service.accept(PATIENT, List.of(jpeg("a.jpg")), "k-race0001"))
                .assertNext(d -> assertThat(d.id()).isEqualTo(winner.id())).verifyComplete();
        verify(storage, never()).storePage(any(), any(), any(), anyInt(), any());
        verify(extraction, never()).process(any(), any());
    }

    /** No row without its pages: storage failing on page 2 removes the row, so the retry with the same key starts clean. */
    @Test
    void aStorageFailureLeavesNoDocumentBehind() throws Exception {
        when(storage.storePage(any(), any(), any(), eq(2), any())).thenReturn(Mono.error(new IllegalStateException("bucket down")));
        when(storage.deleteDocument(any(), any(), any())).thenReturn(Mono.just(new DeletionReport(1, 1)));
        when(documents.deleteDocument(any())).thenReturn(Mono.just(1L));
        StepVerifier.create(service.accept(PATIENT, List.of(jpeg("a.jpg"), jpeg("b.jpg")), "k-store001"))
                .expectErrorMessage("bucket down").verify();
        verify(documents).deleteDocument(any());
        verify(extraction, never()).process(any(), any());
    }

    /** "আলাদা আলাদা": one document per file, keys k:0…k:n-1, each counted. */
    @Test
    void separatePagesAreOneDocumentEach() throws Exception {
        when(freeTier.checkCanUploadDocuments(FAMILY, 3)).thenReturn(Mono.empty());
        StepVerifier.create(service.acceptEach(PATIENT, List.of(jpeg("a.jpg"), jpeg("b.jpg"), jpeg("c.jpg")), "k-sep00001").collectList())
                .assertNext(docs -> assertThat(docs).hasSize(3).allSatisfy(d -> assertThat(d.pageCount()).isEqualTo(1))).verifyComplete();
        for (int i = 0; i < 3; i++) {
            verify(documents).createOnce(any(), eq("k-sep00001:" + i));
        }
    }

    /** The dangerous direction: one unreadable file among separate pages refuses them all before any document exists. */
    @Test
    void oneUnreadableFileAmongSeparatePagesRefusesAllBeforeAnyDocumentExists() throws Exception {
        when(freeTier.checkCanUploadDocuments(FAMILY, 3)).thenReturn(Mono.empty());
        byte[] docx = TryDocuments.docx();
        StepVerifier.create(service.acceptEach(PATIENT, List.of(jpeg("a.jpg"), new Upload("x.docx", docx), jpeg("c.jpg")), "k-sep00002"))
                .expectErrorMatches(e -> e instanceof BaymaxException b && b.reason().equals("unsupported_image")).verify();
        verify(documents, never()).createOnce(any(), any());
    }

    @Test
    void separatePagesAskTheAllowanceForAllOfThemAtOnce() throws Exception {
        when(freeTier.checkCanUploadDocuments(FAMILY, 3)).thenReturn(Mono.error(new FreeTierExceededException(FreeTierExceededException.DOCUMENTS, "cap")));
        StepVerifier.create(service.acceptEach(PATIENT, List.of(jpeg("a.jpg"), jpeg("b.jpg"), jpeg("c.jpg")), "k-sep00003"))
                .expectError(FreeTierExceededException.class).verify();
        verify(documents, never()).createOnce(any(), any());
    }
```

- [ ] **Step 2: red.** `aRetryWithTheSameKey…` `Never wanted here … createOnce` (today every call creates);
  `oneUnreadableFile…` `createOnce(…, "k-sep00002:0")` invoked once when `acceptEach` creates as it renders — the ordering
  the test guards.
- [ ] **Step 3: implement.**

```java
    @Override
    public Mono<Document> accept(UUID patientId, List<Upload> uploads) {
        return accept(patientId, uploads, null);
    }

    @Override
    public Mono<Document> accept(UUID patientId, List<Upload> uploads, String uploadKey) {
        if (uploads == null || uploads.isEmpty()) {
            return Mono.error(BaymaxException.badRequest("no_file", "attach at least one file"));
        }
        return patient(patientId).flatMap(patient -> replay(patient, uploadKey)
                .switchIfEmpty(Mono.defer(() -> freeTier.checkCanUploadDocument(patient.familyId())
                        .then(Mono.fromCallable(() -> render(uploads)))
                        .flatMap(pages -> store(patient, pages, uploadKey)))));
    }

    /** "আলাদা আলাদা": one document per file. Every missing file is read before any document exists; one bad file refuses all. */
    @Override
    public Flux<Document> acceptEach(UUID patientId, List<Upload> uploads, String uploadKey) {
        if (uploads == null || uploads.isEmpty()) {
            return Flux.error(BaymaxException.badRequest("no_file", "attach at least one file"));
        }
        return patient(patientId).flatMapMany(patient -> Flux.range(0, uploads.size())
                .concatMap(i -> replay(patient, key(uploadKey, i)).map(Optional::of).defaultIfEmpty(Optional.empty()))
                .collectList()
                .flatMapMany(found -> {
                    List<Integer> missing = IntStream.range(0, found.size()).filter(i -> found.get(i).isEmpty()).boxed().toList();
                    Mono<Void> allowed = missing.isEmpty() ? Mono.empty() : freeTier.checkCanUploadDocuments(patient.familyId(), missing.size());
                    return allowed.then(Mono.fromCallable(() -> {
                                Map<Integer, List<byte[]>> pages = new HashMap<>();
                                for (int i : missing) {
                                    pages.put(i, render(List.of(uploads.get(i))));
                                }
                                return pages;
                            }))
                            .flatMapMany(pages -> Flux.range(0, uploads.size()).concatMap(i -> found.get(i).map(Mono::just)
                                    .orElseGet(() -> store(patient, pages.get(i), key(uploadKey, i)))));
                }));
    }

    private Mono<Document> store(PatientProfile patient, List<byte[]> pages, String uploadKey) {
        return documents.createOnce(Document.received(patient.id(), patient.familyId(), pages.size(), clock.instant()), uploadKey)
                .flatMap(created -> !created.fresh() ? Mono.just(created.document())   // the same press won the race; it stores
                        : storePages(created.document(), pages)
                                .onErrorResume(e -> forget(created.document()).then(Mono.error(e)))
                                .thenReturn(created.document())
                                .doOnNext(document -> startProcessing(document, pages)));
    }

    private Mono<Document> replay(PatientProfile patient, String uploadKey) {
        if (uploadKey == null) {
            return Mono.empty();
        }
        return documents.findByUploadKey(patient.familyId(), uploadKey).flatMap(d -> d.patientId().equals(patient.id())
                ? Mono.just(d)
                : Mono.error(BaymaxException.conflict("upload_key_reused", "the upload key belongs to another patient's document")));
    }

    /** Objects first, then the row (TimelineService.deleteDocument's order): an orphan object is harmless, a pageless row is not. */
    private Mono<Void> forget(Document d) {
        return storage.deleteDocument(d.familyId(), d.patientId(), d.id()).then(documents.deleteDocument(d.id())).then();
    }

    private static String key(String uploadKey, int index) {
        return uploadKey == null ? null : uploadKey + ":" + index;
    }
```

### Task W2.3: the web side — the hidden key, the grouping, the hand-off for several

**Files:** create `web/domain/Grouping.java` (`ONE`, `SEPARATE`); modify `UploadUseCase` (+`Mono<List<Document>> upload(UUID,
UUID, List<Upload>, Grouping, String)`; the 3-argument form delegates with `ONE, null` and takes `.get(0)`), `UploadService`,
`ui/UploadUiHandler` (`sheetBody`: the hidden key and the group fieldset; `receive`: reads both; several documents land
on `/app/timeline?patient=<id>`), `UploadServiceTest`, `AddReportSheetPageTest`, `AddReportSubmitTest`, `ui-upload_*`.

- [ ] **Step 1: failing tests.**

```java
    // AddReportSheetPageTest
    @Test
    void everySheetCarriesAFreshKeySoADoubleTapOrAResubmitIsOneDocument() {
        when(kit.uploads.uploadOptions(WebUiTestKit.FAMILY)).thenReturn(Mono.just(new UploadOptions(List.of(patient("আম্মা", 64, true)), List.of(), used(0))));
        Pattern key = Pattern.compile("<input type=\"hidden\" name=\"upload_key\" value=\"([0-9a-f-]{36})\">");
        Matcher first = key.matcher(sheet()), second = key.matcher(sheet());
        assertThat(first.find()).isTrue();
        assertThat(second.find()).isTrue();
        assertThat(first.group(1)).isNotEqualTo(second.group(1));   // a new sheet is a new press; the same sheet resubmitted is not
        assertThat(sheet()).containsPattern("name=\"group\" value=\"one\" checked>").containsPattern("name=\"group\" value=\"separate\">");
    }

    // AddReportSubmitTest
    @Test
    void theKeyAndTheGroupingReachIntakeAndSeveralDocumentsLandOnThePatientsPapers() throws IOException {
        UUID ma = UUID.randomUUID();
        List<Document> two = List.of(Document.received(ma, family, 1, NOW).withId(UUID.randomUUID()), Document.received(ma, family, 1, NOW).withId(UUID.randomUUID()));
        when(uploads.upload(eq(family), eq(ma), anyList(), eq(Grouping.SEPARATE), eq("1b4e28ba-2fa1-11d2-883f-0016d3cca427"))).thenReturn(Mono.just(two));
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("patient", ma.toString());
        body.part("group", "separate");
        body.part("upload_key", "1b4e28ba-2fa1-11d2-883f-0016d3cca427");
        body.part("file", jpeg()).filename("a.jpg");
        body.part("file", jpeg()).filename("b.jpg");
        post(body).expectStatus().isSeeOther().expectHeader().valueEquals(HttpHeaders.LOCATION, "/app/timeline?patient=" + ma);
    }

    @Test
    void aKeyThatIsNotOurShapeIsIgnoredNotStored() throws IOException {
        UUID ma = UUID.randomUUID();
        when(uploads.upload(eq(family), eq(ma), anyList(), eq(Grouping.ONE), isNull()))
                .thenReturn(Mono.just(List.of(Document.received(ma, family, 1, NOW).withId(UUID.randomUUID()))));
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("patient", ma.toString());
        body.part("upload_key", "<script>x</script>");
        body.part("file", jpeg()).filename("a.jpg");
        post(body).expectStatus().isSeeOther();
        verify(uploads).upload(eq(family), eq(ma), anyList(), eq(Grouping.ONE), isNull());
    }

    // TimelineServiceTest
    @Test
    void separatePagesGoToIntakeAsSeparateDocumentsAndAShareMemberStillMayNot() {
        when(records.visiblePatient(FAMILY, PATIENT_B)).thenReturn(Mono.just(new PatientAccess(SHARED, false)));
        StepVerifier.create(service.upload(FAMILY, PATIENT_B, List.of(new Upload("a.jpg", new byte[]{1})), Grouping.SEPARATE, "k-share001"))
                .expectErrorMatches(e -> ((BaymaxException) e).status().value() == 403).verify();
        verify(intake, never()).acceptEach(any(), any(), any());
    }
```

  (In `UploadServiceTest`, `PATIENT_B`/`SHARED` are a patient id and its profile of another family that this family may
  only see.)
- [ ] **Step 2: red.** No hidden field (`first.find()` false), then `expected Location /app/timeline?patient=…`.
- [ ] **Step 3: implement.** In `sheetBody`, `<input type="hidden" name="upload_key" value="` + `UUID.randomUUID()` +
  `">` right after `<form …>`; after the source rows, `<fieldset id="group" class="group"><legend>` + `add.group.q` +
  two `label.chip` radios (`value="one" checked`, `value="separate"`) + `add.group.hint`. In `receive`:
  `Grouping grouping = "separate".equals(field(parts, "group")) ? Grouping.SEPARATE : Grouping.ONE;` and
  `String key = Optional.ofNullable(field(parts, "upload_key")).filter(k -> k.matches("[A-Za-z0-9-]{8,64}")).orElse(null);`
  then `uploads.upload(familyId, patientId, files, grouping, key)`; the next page is `/app/documents/<id>` for one
  document, else `/app/timeline?patient=<id>`. `UploadService.upload` (5-argument):

```java
    @Override
    public Mono<List<Document>> upload(UUID familyId, UUID patientId, List<Upload> uploads, Grouping grouping, String uploadKey) {   // UploadService
        return visiblePatient(familyId, patientId)
                .flatMap(access -> access.owner() ? Mono.just(access) : Mono.error(BaymaxException.forbidden("shared_patient_read_only",
                        "a shared patient's records can be seen but not added to")))
                .flatMap(access -> grouping == Grouping.SEPARATE
                        ? intake.acceptEach(patientId, uploads, uploadKey).collectList()
                        : intake.accept(patientId, uploads, uploadKey).map(List::of))
                .flatMap(docs -> Flux.fromIterable(docs)
                        .concatMap(d -> audit.record(AuditEvent.family(AuditEvent.Kind.DOCUMENT_UPLOAD, familyId, patientId, d.id(), clock.instant())))
                        .then(Mono.just(docs)));
    }
```

  Copy (`ui-upload_*`, drawn s08): `add.group.q=একাধিক পাতা দিলে, সেগুলো কি একই রিপোর্টের?`,
  `add.group.one=একটা রিপোর্ট — সব পাতা মিলে একটা কাগজ`, `add.group.separate=আলাদা আলাদা — প্রতিটি পাতা আলাদা কাগজ`,
  `add.group.hint=আপনি বেছে দিন — Medioo নিজে পাতা মেলাবে না`; English "If you add several pages, are they one report?",
  "One report — all pages make one paper", "Separate — each page is its own paper", "You choose — Medioo will not match
  pages itself".
- [ ] **Step 4: run** `./gradlew :baymax:test --tests '*AddReport*' --tests '*UploadServiceTest' --tests '*TryDocumentsThroughTheWebTest' --tests '*WebOnlyJourneyAcceptanceTest'`;
  `./gradlew build`; standard pre-merge. Commit `feat(baymax): one press of "পাঠান" is one document, enforced by the database (V21)`.

### Try it (D3) — prod-web

- [ ] t4a + t4b from the gallery, "আলাদা আলাদা", পাঠান → কাগজপত্র shows **two** new papers.
- [ ] The same two with "একটা রিপোর্ট" → **one** paper of two pages.
- [ ] Tap পাঠান twice fast with t1 (no JS yet) → **one** paper. After landing, Back and accept "Confirm form resubmission"
  → still one; the same document opens.
- [ ] **local:** with one free slot left, "আলাদা আলাদা" with two pages → refused on the sheet, and **no** new paper.

---

## PR D4 — W4.3: the page tray (s08), byte progress (s09 upload), a retry that resends the same press (`upload.js` ≤ 40 KB)

Branch `feat/baymax-upload-tray`. Spec §4.8, §4.9 upload step. `/assets/upload.js` is the app's only script besides
`progress.js`, loaded only on the add sheet when the form is rendered. It enhances the A7 form: pages go into a tray
(order, remove, add more, one report or separate), are downscaled on the phone only when the server turns that on
(`baymax.upload.downscale-max-edge`, `0` until D5), are sent with a real byte progress bar, and a stalled send can be
retried with the **same** `upload_key` (D3), so a retry can never make a second paper. A file the server would refuse
(a DOCX) or a press the allowance would refuse never leaves the phone. No copy lives in the script: every string comes
from `data-*` on `#up-copy`. The CSP already allows it (`script-src 'self'`, `connect-src 'self'`, `img-src … blob:` for
the thumbnails, A4).

### Task W3.1: serve the script on the sheet only, never inline

**Files:** modify `ui/AssetHandler.java` (+`upload.js`, `text/javascript`), `ui/UploadUiHandler.page` (`Head.script("upload.js")`
only when the form is rendered); tests `AddReportSheetPageTest`, `BaymaxWebUiRouterTest`.

- [ ] **Step 1: failing tests.**

```java
    // AddReportSheetPageTest
    @Test
    void theSheetLoadsExactlyOneScriptFromAssetsAndNoOtherPageLoadsAny() {
        when(kit.uploads.uploadOptions(WebUiTestKit.FAMILY)).thenReturn(Mono.just(new UploadOptions(List.of(patient("আম্মা", 64, true)), List.of(), used(0))));
        String html = sheet();
        assertThat(html).containsOnlyOnce("<script").containsPattern("<script src=\"/assets/upload\\.js\\?v=[0-9a-f]+\" defer></script>");
        assertThat(html).doesNotContainPattern("<script(?![^>]*\\bsrc=)");   // nothing inline (CSP script-src 'self')
        when(kit.uploads.uploadOptions(WebUiTestKit.FAMILY)).thenReturn(Mono.just(new UploadOptions(List.of(patient("আম্মা", 64, true)), List.of(), used(3))));
        assertThat(sheet()).doesNotContain("<script");   // no form, nothing to enhance: no script (spec §10)
    }

    // BaymaxWebUiRouterTest
    @Test
    void theUploadScriptIsServedAsJavascript() {
        kit.client.get().uri("/assets/upload.js").exchange().expectStatus().isOk().expectHeader().contentTypeCompatibleWith("text/javascript");
    }
```

- [ ] **Step 2: red** `Expecting actual … to contain only once "<script"` (none), and `404` for the asset.

### Task W3.2: `upload.js`'s pure functions, under node, in both directions — including what it sends

**Files:** create `baymax/src/main/resources/baymax/ui/upload.js` (the pure head), `baymax/src/test/js/upload.test.mjs`;
modify `.github/workflows/ci-cd.yml` (after "Build": `- uses: actions/setup-node@v4` with `node-version: "20"`, then
`- name: Upload script tests` / `run: node --test baymax/src/test/js/`). `git check-ignore baymax/src/test/js/upload.test.mjs`
prints nothing (`.gitignore` has no `js/` rule — check, do not assume).

- [ ] **Step 1: failing tests** (`upload.test.mjs`; the draft's five plus the two that pin what leaves the phone):

```js
// Pure parts of the add-report sheet's script. Run: node --test baymax/src/test/js/
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const u = require('../../main/resources/baymax/ui/upload.js');
const BN = '০১২৩৪৫৬৭৮৯';

test('counts on a Bangla page are in Bangla digits, and a slot with no value is left visible, not blanked', () => {
  assert.equal(u.fill('পাঠানো হচ্ছে — {total}টির {n}টি পাতা', { n: 2, total: 3 }, BN), 'পাঠানো হচ্ছে — ৩টির ২টি পাতা');
  assert.equal(u.fill('{n} of {total} pages', { n: 10, total: 10 }, '0123456789'), '10 of 10 pages');
  assert.equal(u.fill('পাতা {n}', {}, BN), 'পাতা {n}');
});

test('a move past either end changes nothing; a move inside swaps exactly two', () => {
  assert.deepEqual(u.move(['a', 'b', 'c'], 0, -1), ['a', 'b', 'c']);
  assert.deepEqual(u.move(['a', 'b', 'c'], 2, 1), ['a', 'b', 'c']);
  assert.deepEqual(u.move(['a', 'b', 'c'], 1, -1), ['b', 'a', 'c']);
  const list = ['a', 'b'];
  u.move(list, 0, 1);
  assert.deepEqual(list, ['a', 'b']);   // never mutates the tray it was given
});

test('one report is one document whatever the pages; separate pages are one each; the cap is checked before upload', () => {
  assert.equal(u.documentsNeeded(3, 'one'), 1);
  assert.equal(u.documentsNeeded(3, 'separate'), 3);
  assert.equal(u.allowed(3, 'separate', 2), false);
  assert.equal(u.allowed(2, 'separate', 2), true);
  assert.equal(u.allowed(9, 'one', 1), true);
  assert.equal(u.allowed(9, 'separate', -1), true);   // a paying family: no cap
});

test('downscale is off unless the server turns it on; a small JPEG is never re-compressed; a PDF is never touched', () => {
  const on = { edge: 2200, quality: 0.92, passBytes: 1500000 };
  assert.equal(u.plan(4000, 3000, 5000000, 'image/jpeg', { ...on, edge: 0 }), null);
  assert.equal(u.plan(1240, 1754, 230947, 'image/jpeg', on), null);   // t1 goes byte for byte
  assert.deepEqual(u.plan(4000, 3000, 5000000, 'image/jpeg', on), { width: 2200, height: 1650, quality: 0.92 });
  assert.deepEqual(u.plan(1080, 2400, 900000, 'image/png', on), { width: 990, height: 2200, quality: 0.92 });
  assert.equal(u.plan(1000, 1000, 10, 'application/pdf', on), null);
});

test('a page counts as sent only when its bytes have left, and the last one only when the server has them all', () => {
  const sizes = [100, 100, 100];
  assert.equal(u.pagesSent(0, 330, sizes, false), 0);
  assert.equal(u.pagesSent(115, 330, sizes, false), 1);
  assert.equal(u.pagesSent(330, 330, sizes, false), 2);   // every byte written, not yet answered
  assert.equal(u.pagesSent(330, 330, sizes, true), 3);
});

test('a page is sent as page-N, never under its own name (a filename may name the patient)', () => {
  assert.equal(u.pageName(0, 'jpg'), 'page-1.jpg');
  assert.equal(u.pageName(9, 'pdf'), 'page-10.pdf');
  const req = u.buildRequest([{ blob: { size: 3, name: 'Rokeya-Begum-CBC.jpg' }, name: u.pageName(0, 'jpg') }], 'separate', 'k-0f8e2c1a', 'p-1');
  assert.deepEqual(req.files.map((f) => f.name), ['page-1.jpg']);
  assert.ok(!JSON.stringify(req.fields).includes('Rokeya'));
  assert.ok(!req.files.some((f) => f.name.includes('Rokeya')));
});

test('a retry is the same press: the same key, the same patient; one page is always one report', () => {
  const pages = [{ blob: { size: 3 }, name: 'page-1.jpg' }];
  const a = u.buildRequest(pages, 'separate', 'k-same-0001', 'p-1');
  const b = u.buildRequest(pages, 'separate', 'k-same-0001', 'p-1');
  assert.deepEqual(a.fields, b.fields);
  assert.deepEqual(a.fields, [['patient', 'p-1'], ['group', 'one'], ['upload_key', 'k-same-0001']]);
  const two = u.buildRequest(pages.concat([{ blob: { size: 4 }, name: 'page-2.jpg' }]), 'separate', 'k-same-0001', 'p-1');
  assert.deepEqual(two.fields[1], ['group', 'separate']);
});
```

- [ ] **Step 2: red.** `Cannot find module '…/upload.js'`; then with stub exports
  `AssertionError: Expected values to be strictly equal: 'পাঠানো হচ্ছে — 3টির 2টি পাতা' !== '…৩টির ২টি…'` and
  `u.pageName is not a function`.
- [ ] **Step 3: implement** the pure head (ES5, so a 2019 Android WebView parses it), the draft's plus:

```js
  /** The only name a page is sent under: never the file's own name, which may name the patient. */
  function pageName(i, ext) { return 'page-' + (i + 1) + '.' + ext; }
  /** One press of "পাঠান" as fields and files: the same pages and key always make the same request (a retry). */
  function buildRequest(pages, group, key, patient) {
    return {
      fields: [['patient', patient], ['group', pages.length < 2 ? 'one' : group], ['upload_key', key]],
      files: pages.map(function (p) { return { blob: p.blob, name: p.name }; })
    };
  }
```

```js
/*
 * Medioo add-report sheet (spec 4.7-4.9, plan area 4). Enhances the server-rendered form; without this file the form
 * posts as it is. Holds no copy: every visible string comes from data-* on #up-copy, in the page's language.
 * Never shows or sends a file's own name (it may name the patient): pages go as page-1.jpg, page-2.pdf, ...
 */
(function (root) {
  'use strict';

  function digits(n, set) {
    return String(n).replace(/[0-9]/g, function (d) { return set.charAt(+d); });
  }
  function fill(template, values, set) {
    return String(template || '').replace(/\{([a-z]+)\}/g, function (all, k) {
      return Object.prototype.hasOwnProperty.call(values, k) ? digits(values[k], set) : all;
    });
  }
  function move(list, i, delta) {
    var out = list.slice(), j = i + delta;
    if (i < 0 || i >= out.length || j < 0 || j >= out.length) { return out; }
    var t = out[i]; out[i] = out[j]; out[j] = t;
    return out;
  }
  function documentsNeeded(count, group) { return group === 'separate' ? count : (count > 0 ? 1 : 0); }
  function allowed(count, group, remaining) { return remaining < 0 || documentsNeeded(count, group) <= remaining; }
  /** null = send the file as it is. Only what is bigger than the target is scaled; a small JPEG is never re-compressed. */
  function plan(width, height, bytes, type, cfg) {
    if (!cfg.edge || !/^image\/(jpeg|png|webp)$/.test(type)) { return null; }
    var longest = Math.max(width, height);
    if (type === 'image/jpeg' && longest <= cfg.edge && bytes <= cfg.passBytes) { return null; }
    var scale = Math.min(1, cfg.edge / longest);
    return { width: Math.max(1, Math.round(width * scale)), height: Math.max(1, Math.round(height * scale)), quality: cfg.quality };
  }
  /** Pages whose bytes have all left the phone; the last only once the server has answered. */
  function pagesSent(loaded, total, sizes, done) {
    if (done) { return sizes.length; }
    var all = 0, sum = 0, n = 0, i;
    for (i = 0; i < sizes.length; i++) { all += sizes[i]; }
    var reached = total > 0 ? (loaded / total) * all : 0;
    for (i = 0; i < sizes.length - 1; i++) { sum += sizes[i]; if (reached >= sum) { n++; } else { break; } }
    return n;
  }

  var api = { digits: digits, fill: fill, move: move, documentsNeeded: documentsNeeded, allowed: allowed, plan: plan, pagesSent: pagesSent, pageName: pageName, buildRequest: buildRequest };
  if (typeof module === 'object' && module.exports) { module.exports = api; return; }
  root.MediooUpload = api;
  /* DOM wiring: Task W3.3 */
})(typeof window !== 'undefined' ? window : this);
```

  (`pageName` and `buildRequest` sit with the other pure functions, before `var api`.)

### Task W3.3: the tray, the upload step, the retry

**Files:** `upload.js` (DOM wiring), `ui/UploadUiHandler.sheetBody` (the W3 markup: on the form `data-max-pages`,
`data-remaining` (`-1` for paying), `data-max-request-bytes`, `data-downscale-edge`, `data-downscale-quality`,
`data-pass-bytes`, `data-digits` (`BanglaDate.digits("0123456789")` on a Bangla page); `<div id="up-copy" hidden data-…>`
with every string the script shows; `<section id="tray" hidden>` with `#tray-title`, `#tray-list`, `#tray-more`,
`#tray-limit`, `#tray-send`; `<template id="t-item">` with the thumbnail, `.t-page`, `.t-src` and three 48 px
`icon-btn`s `.t-up` `.t-down` `.t-remove`; `<section id="up" hidden aria-live="polite">` with `#up-title`, `#up-count`,
`<progress id="up-bar">`, `#up-pages`, `#up-slow`, `#up-retry`), `css/70-upload.css`, `ui-upload_*`.

- [ ] **Step 1: the wiring** (inside the IIFE after `root.MediooUpload = api;`; names go through `pageName`, the request
  through `buildRequest`):

```js
  var form = document.getElementById('add');
  var copyEl = document.getElementById('up-copy');
  if (!form || !copyEl || !root.FormData || !root.XMLHttpRequest || !root.URL || !URL.createObjectURL) { return; }
  document.documentElement.classList.add('js');

  var copy = copyEl.dataset;
  var SRC = { camera: copy.traySrcCamera, gallery: copy.traySrcGallery, pdf: copy.traySrcPdf };
  var set = form.dataset.digits || '0123456789';
  var cfg = { edge: +form.dataset.downscaleEdge || 0, quality: +form.dataset.downscaleQuality || 0.92, passBytes: +form.dataset.passBytes || 1500000 };
  var maxPages = +form.dataset.maxPages || 10, maxBytes = +form.dataset.maxRequestBytes || 0;
  var remaining = form.dataset.remaining === undefined ? -1 : +form.dataset.remaining;
  var known = /^(image\/(jpeg|png|webp|heic|heif)|application\/pdf)$/;
  var tray = [], busy = false, lastSent = 0, again = null;
  var $ = function (id) { return document.getElementById(id); };
  var trayEl = $('tray'), listEl = $('tray-list'), upEl = $('up'), alertEl = $('add-alert'), itemTpl = $('t-item');
  trayEl.insertBefore($('group'), listEl);   // one set of group radios: the no-JS fieldset moves into the tray

  function say(text) { alertEl.textContent = text || ''; alertEl.hidden = !text; }
  function chosen(name) { var el = form.querySelector('input[name="' + name + '"]:checked'); return el ? el.value : null; }
  function rowsFollowPatient() {
    var on = !!chosen('patient');
    Array.prototype.forEach.call(form.querySelectorAll('.src-row'), function (row) {
      row.classList.toggle('off', !on);
      row.querySelector('input').disabled = !on;
    });
  }

  function add(files, src) {
    say('');
    for (var i = 0; i < files.length; i++) {
      var f = files[i];
      if (f.type && !known.test(f.type)) { say(copy.unsupported); continue; }   // a DOCX never leaves the phone
      if (tray.length >= maxPages) { say(fill(copy.trayLimit, { max: maxPages, n: tray.length }, set)); break; }
      tray.push({ file: f, src: src, url: /^image\//.test(f.type) ? URL.createObjectURL(f) : null });
    }
    render();
    if (src === 'pdf' && tray.length === 1) { send(); }   // spec 4.8: a single PDF or screenshot skips the tray
  }

  function render() {
    trayEl.hidden = tray.length === 0;
    $('tray-title').textContent = fill(copy.trayTitle, { n: tray.length }, set);
    $('group').hidden = tray.length < 2;
    $('tray-limit').textContent = fill(copy.trayLimit, { max: maxPages, n: tray.length }, set);
    $('tray-more').disabled = tray.length >= maxPages;
    while (listEl.firstChild) { listEl.removeChild(listEl.firstChild); }
    tray.forEach(function (item, i) {
      var li = itemTpl.content.firstElementChild.cloneNode(true), n = { n: i + 1 };
      li.querySelector('.t-page').textContent = fill(copy.trayPage, n, set);
      li.querySelector('.t-src').textContent = SRC[item.src] || '';
      if (item.url) { var img = document.createElement('img'); img.alt = ''; img.src = item.url; li.querySelector('.t-thumb').appendChild(img); }
      else { li.querySelector('.t-thumb').classList.add('pdf'); }
      wire(li, '.t-up', copy.trayUp, n, i === 0, function () { tray = move(tray, i, -1); render(); focus(i - 1, '.t-up'); });
      wire(li, '.t-down', copy.trayDown, n, i === tray.length - 1, function () { tray = move(tray, i, 1); render(); focus(i + 1, '.t-down'); });
      wire(li, '.t-remove', copy.trayRemove, n, false, function () {
        if (item.url) { URL.revokeObjectURL(item.url); }
        tray.splice(i, 1); render(); focus(Math.min(i, tray.length - 1), '.t-remove');
      });
      listEl.appendChild(li);
    });
  }
  function wire(li, sel, label, n, disabled, onClick) {
    var b = li.querySelector(sel);
    b.setAttribute('aria-label', fill(label, n, set)); b.disabled = disabled; b.addEventListener('click', onClick);
  }
  function focus(i, sel) {
    var li = listEl.children[i], b = li && li.querySelector(sel);
    (b && !b.disabled ? b : (li ? li.querySelector('.t-remove') : $('tray-more'))).focus();
  }

  function prepare(item, i) {
    var f = item.file, ext = f.type === 'application/pdf' ? 'pdf' : (f.type === 'image/png' ? 'png' : 'jpg');
    function named(blob, e) { return { blob: blob, name: pageName(i, e) }; }
    if (!cfg.edge || !root.createImageBitmap || !/^image\/(jpeg|png|webp)$/.test(f.type)) { return Promise.resolve(named(f, ext)); }
    return createImageBitmap(f, { imageOrientation: 'from-image' }).then(function (bmp) {
      var p = plan(bmp.width, bmp.height, f.size, f.type, cfg);
      if (!p) { bmp.close(); return named(f, ext); }
      var canvas = document.createElement('canvas'), g = canvas.getContext('2d');
      canvas.width = p.width; canvas.height = p.height;
      g.fillStyle = 'white'; g.fillRect(0, 0, p.width, p.height);   // JPEG has no alpha: a transparent PNG must not turn black
      g.imageSmoothingQuality = 'high'; g.drawImage(bmp, 0, 0, p.width, p.height); bmp.close();
      return new Promise(function (ok) {
        canvas.toBlob(function (blob) { ok(blob && blob.size < f.size ? named(blob, 'jpg') : named(f, ext)); }, 'image/jpeg', p.quality);
      });
    }).catch(function () { return named(f, ext); });   // a format this browser cannot decode (HEIC on Chrome) goes as is; the server decides
  }
  function prepareAll(i, out) {
    return i >= tray.length ? Promise.resolve(out) : prepare(tray[i], i).then(function (p) { out.push(p); return prepareAll(i + 1, out); });
  }

  function send() {
    if (busy) { return; }
    var group = tray.length < 2 ? 'one' : (chosen('group') || 'one');
    if (!chosen('patient')) { say(copy.choosePatient); return; }
    if (!tray.length) { say(copy.noFile); return; }
    if (!allowed(tray.length, group, remaining)) { say(copy.overAllowance); return; }   // before a byte leaves the phone
    busy = true; say(''); lastSent = 0; progress(0, tray.length, false);
    prepareAll(0, []).then(function (pages) {
      var total = pages.reduce(function (s, p) { return s + p.blob.size; }, 0);
      if (maxBytes && total > maxBytes) { busy = false; back(); say(copy.tooLarge); return; }
      post(pages, group);
    });
  }

  function post(pages, group) {
    var fd = new FormData(), xhr = new XMLHttpRequest(), quiet = null;
    var sizes = pages.map(function (p) { return p.blob.size; });
    var req = buildRequest(pages, group, form.elements.upload_key.value, chosen('patient')); req.fields.forEach(function (f) { fd.append(f[0], f[1]); });
    req.files.forEach(function (f) { fd.append('file', f.blob, f.name); });
    function stall() { clearTimeout(quiet); quiet = setTimeout(function () { xhr.abort(); }, 30000); }
    xhr.open('POST', form.action);
    xhr.setRequestHeader('Accept', 'application/json');
    xhr.upload.onprogress = function (e) { stall(); if (e.lengthComputable) { progress(pagesSent(e.loaded, e.total, sizes, false), sizes.length, false); } };
    xhr.onload = function () {
      clearTimeout(quiet);
      var body = null;
      if (/json/.test(xhr.getResponseHeader('Content-Type') || '')) { try { body = JSON.parse(xhr.responseText); } catch (ignored) { body = null; } }
      busy = false;
      if (body && xhr.status < 300 && body.next) { progress(sizes.length, sizes.length, false); root.location.assign(body.next); return; }
      back();
      // no JSON: the session lapsed and the filter answered the sign-in page, or the server failed outright
      say(body ? (body.text || copy.generic) : (/\/app\/?$/.test(xhr.responseURL || '') ? copy.signedOut : copy.generic));
    };
    xhr.onerror = xhr.onabort = function () { clearTimeout(quiet); stuck(pages, group); };
    stall();
    xhr.send(fd);
  }

  function progress(n, total, isStuck) {
    lastSent = n; trayEl.hidden = true; upEl.hidden = false;
    $('up-count').textContent = fill(copy.upCount, { n: n, total: total }, set);
    $('up-bar').max = total; $('up-bar').value = n;
    $('up-bar').setAttribute('aria-label', fill(copy.upCount, { n: n, total: total }, set));
    $('up-slow').hidden = !isStuck;
    var ol = $('up-pages');
    while (ol.firstChild) { ol.removeChild(ol.firstChild); }
    for (var i = 0; i < total; i++) {
      var li = document.createElement('li'), a = document.createElement('span'), b = document.createElement('span');
      a.textContent = fill(copy.trayPage, { n: i + 1 }, set);
      b.textContent = i < n ? copy.upSent : (isStuck ? copy.upStuck : '');
      li.appendChild(a); li.appendChild(b); ol.appendChild(li);
    }
  }
  function stuck(pages, group) {
    progress(lastSent, pages.length, true);
    again = $('up-retry'); again.hidden = false;
    again.onclick = function () { again.hidden = true; post(pages, group); };   // the same upload_key: a retry, never a second paper
    root.addEventListener('online', function once() { root.removeEventListener('online', once); if (!again.hidden) { again.click(); } });
  }
  function back() { upEl.hidden = true; trayEl.hidden = tray.length === 0; }

  Array.prototype.forEach.call(form.querySelectorAll('input[type=file]'), function (input) {
    input.addEventListener('change', function () {
      $('tray-more').dataset.src = input.dataset.src === 'pdf' ? 'gallery' : input.dataset.src;
      add(input.files, input.dataset.src);
      input.value = '';   // the tray holds the files now; the same input can be used again
    });
  });
  $('tray-more').addEventListener('click', function () { form.querySelector('input[data-src="' + ($('tray-more').dataset.src || 'gallery') + '"]').click(); });
  $('tray-send').addEventListener('click', send);
  form.addEventListener('submit', function (e) { e.preventDefault(); send(); });
  Array.prototype.forEach.call(form.querySelectorAll('input[name=patient]'), function (r) { r.addEventListener('change', rowsFollowPatient); });
  root.addEventListener('beforeunload', function (e) { if (busy || (again && !again.hidden)) { e.preventDefault(); e.returnValue = ''; } });
  rowsFollowPatient();
```

- [ ] **Step 2: copy** (`#up-copy` attributes; bn shown, en in both bundles):

  | dataset key | ui key | bn |
  |---|---|---|
  | `trayTitle` | `tray.title` | {n}টি পাতা — একই রিপোর্টের? (s08) |
  | `trayPage` | `tray.page` | পাতা {n} |
  | `traySrcCamera` / `traySrcGallery` / `traySrcPdf` | `tray.src.*` | ক্যামেরা থেকে / গ্যালারি থেকে / PDF |
  | `trayUp` / `trayDown` / `trayRemove` | `tray.up` … | পাতা {n} উপরে নিন / পাতা {n} নিচে নিন / পাতা {n} বাদ দিন (s08) |
  | `trayLimit` | `tray.limit` | একটা কাগজে সবচেয়ে বেশি {max}টি পাতা · এখন {n}টি (s08) |
  | `upCount` | `up.count` | পাঠানো হচ্ছে — {total}টির {n}টি পাতা (s09) |
  | `upSent` / `upStuck` | `up.sent` / `up.stuck` | পাঠানো হয়েছে / আটকে গেছে — ফোনেই রাখা আছে (s09) |
  | `choosePatient` | `add.choose` | কার কাগজ বেছে নিন — Medioo নিজে আন্দাজ করবে না |
  | `noFile` / `unsupported` / `tooLarge` | `upload.err.*` | as W4.1 |
  | `overAllowance` | `upload.err.over_allowance` | এ মাসে বিনা খরচে আর এতগুলো কাগজ পড়ানো যাবে না — "একটা রিপোর্ট" বেছে নিন বা কম পাতা দিন। **(new — PO)** |
  | `signedOut` | `upload.err.signed_out` | আবার ঢুকতে হবে — পাতাগুলো আবার বেছে নিন। **(new)** |
  | `generic` | `app.err.generic` | (existing) |

  Server-rendered (not read by the script): `tray.order` "পাতার ক্রম", `tray.more` "আরও পাতা", `up.title` "কাগজ পাঠানো
  হচ্ছে" **(F8 fallback; drawn "আম্মার কাগজ পাঠানো হচ্ছে")**, `up.slow` "নেটওয়ার্ক ধীর — পাতা হারাবে না। নেট ফিরলে
  নিজেই আবার চেষ্টা করবে।" (s09), `up.retry` "সব পাতা আবার পাঠান", `up.after` "সব পাতা পৌঁছালে পড়া শুরু হবে।", `up.keep`
  "পাঠানো শেষ না হওয়া পর্যন্ত এই পাতাটা খোলা রাখুন" **(new — makes "পাতা হারাবে না" true; PO)**.

- [ ] **Step 3: CSS** (`70-upload.css`, after A7's sheet subset):

```css
.js .src-row input[type=file]{position:absolute;inset:0;width:100%;height:100%;opacity:0;cursor:pointer}
.src-row.off{opacity:.5}
.js .nojs{display:none}
.tray,.up{display:flex;flex-direction:column;gap:var(--sp-3)}
.tray[hidden],.up[hidden]{display:none}
.tray-list,.up-pages{list-style:none;margin:0;padding:0;display:flex;flex-direction:column;gap:var(--sp-2)}
.t{display:flex;align-items:center;gap:var(--sp-2);padding:var(--sp-2);border:1px solid var(--c-line);border-radius:var(--r-m);background:var(--c-surface)}
.t-thumb{flex:none;width:var(--sp-7);aspect-ratio:3/4;border-radius:var(--r-xs);background:var(--c-surface-2);overflow:hidden;display:flex;align-items:center;justify-content:center}
.t-thumb img{width:100%;height:100%;object-fit:cover}
.t-text{flex:1;min-width:0;display:flex;flex-direction:column}
.up progress{width:100%;height:var(--sp-2);accent-color:var(--c-primary)}
.up-pages li{display:flex;justify-content:space-between;align-items:center;min-height:var(--target-min);border-bottom:1px solid var(--c-line)}
```

  Under `.js` the file input covers its row and stays focusable, so the keyboard reaches it and the ring sits on the row
  (`:focus-within`). Without JS the native input shows inside the row with what was chosen.

### Task W3.4: the script holds no copy, and every string it reads is on the sheet in both languages (derived)

**Files:** create test `ui/UploadScriptTest.java`. (The weight of `/app/add` with `upload.js` is D1's `FirstLoadBudgetTest`,
which already renders every route; there is no second budget test.)

- [ ] **Step 1:**

```java
/** upload.js: within budget, carries no copy, and every string it reads is on the sheet in both languages. */
class UploadScriptTest {

    private static final Path SCRIPT = Path.of("src/main/resources/baymax/ui/upload.js");

    @Test
    void theScriptFitsTheJsBudget() throws IOException {
        assertThat(Files.size(SCRIPT)).as("spec §1 rule 14: JS ≤ 40 KB, only on enhanced pages").isLessThanOrEqualTo(40 * 1024);
    }

    @Test
    void theScriptCarriesNoBanglaCopy() throws IOException {
        List<String> lines = Files.readAllLines(SCRIPT);
        List<String> bangla = IntStream.range(0, lines.size())
                .filter(i -> lines.get(i).codePoints().anyMatch(c -> c >= 0x0980 && c <= 0x09FF))
                .mapToObj(i -> "upload.js:" + (i + 1) + " " + lines.get(i).trim()).toList();
        assertThat(bangla).as("copy lives in ui_*.properties and reaches the script through #up-copy").isEmpty();
    }

    /** Derived from the script itself: a key added to upload.js without its data-* attribute shows "undefined" to a family. */
    @Test
    void everyStringTheScriptReadsIsOnTheSheetInBothLanguages() throws IOException {
        Matcher m = Pattern.compile("\\bcopy\\.([a-z][A-Za-z]+)").matcher(Files.readString(SCRIPT));
        Set<String> keys = new TreeSet<>();
        while (m.find()) {
            keys.add(m.group(1));
        }
        assertThat(keys).as("the sweep found the script's keys; an empty set would pass vacuously").hasSizeGreaterThanOrEqualTo(15);
        UploadUiHandler handler = new WebUiTestKit().upload;
        UploadOptions one = new UploadOptions(List.of(AddReportSheetPageTest.patient("আম্মা", 64, true)), List.of(), AddReportSheetPageTest.used(0));
        for (Lang lang : Lang.values()) {
            String html = handler.sheetBody(lang, one, "/app/home", null);
            String block = html.substring(html.indexOf("<div id=\"up-copy\""), html.indexOf('>', html.indexOf("<div id=\"up-copy\"")));
            for (String k : keys) {
                String attr = "data-" + k.replaceAll("([A-Z])", "-$1").toLowerCase(Locale.ROOT) + "=\"";
                assertThat(block).as(lang + " " + attr).contains(attr);
                assertThat(block).as(lang + " " + attr + " is not empty").doesNotContain(attr + "\"");
            }
        }
    }
}
```

  (`WebUiTestKit.upload` exposes the kit's `UploadUiHandler`.) The size check reads the served bytes:
  `assertThat(new WebUiTestKit().served("/assets/upload.js").length).isLessThanOrEqualTo(40 * 1024)` replaces the
  draft's `Files.size`.
- [ ] **Step 2: red** `[BN data-over-allowance="] Expecting … to contain` until `#up-copy` renders. **Replay the no-copy
  rule:** paste `say('আবার চেষ্টা করুন');` into `upload.js` → red naming `upload.js:<line>`. Revert.
- [ ] **Step 3:** `./gradlew :baymax:test`, `node --test baymax/src/test/js/`, `./gradlew build`; `git status --ignored
  --short -- baymax/src | grep '^!!'` prints nothing (new `src/test/js`). Standard pre-merge. Commit
  `feat(baymax-ui): the page tray, byte progress and a retry that resends the same press`.

### Try it (D4)

- [ ] **prod-web** (Android Chrome on mobile data, or DevTools "Slow 3G" at 360×640): "ছবি তুলুন" → a page → the tray
  shows "১টি পাতা — একই রিপোর্টের?" with a thumbnail; "+ আরও পাতা" → "২টি পাতা" and the one/separate choice appears.
- [ ] Reorder with ▲▼ (focus stays on the moved page's button); × removes; ▲ disabled on পাতা ১, ▼ on the last.
- [ ] পাঠান → "পাঠানো হচ্ছে — ২টির ১টি পাতা" with a moving bar → the reading page.
- [ ] Airplane mode mid-upload → after ~30 s "আটকে গেছে — ফোনেই রাখা আছে" and "সব পাতা আবার পাঠান"; data back on → it
  retries by itself → **one** paper on কাগজপত্র.
- [ ] t5 via "PDF বা স্ক্রিনশট" → straight to the upload step → one paper of two pages. t6 from Files → refused at once,
  **no POST** in DevTools.
- [ ] JavaScript off for the site → A7's form still works.
- [ ] **local:** `BAYMAX_UPLOAD_DOWNSCALE_MAX_EDGE=2200`: a 12 MP photo goes as about 1 MB, upright (EXIF honoured); t1 goes
  byte for byte. Production stays `0` until D5.

---

## PR D5 — W4.4: choose the downscale size by measurement, then turn it on (paid run: Tanvir approves first)

Branch `feat/baymax-downscale-measured`. Nothing here runs a paid call without Tanvir's explicit go-ahead, so it does
**not** run overnight. The code ships with downscale off (`0`, A7); this PR is how it gets turned on. The script that
makes the corpus variants is a writer pointed beside human work (the verified labels), so it links `expected/`, never
copies or regenerates it, and refuses to write beside a real labels directory (CLAUDE.md, the `draft-labels.sh` lesson).
The script is `scripts/make_downscaled_corpus.py` (an importable name: the guard test imports it).

### Task W44.1 — Downscaled copies of the corpus, beside the human labels and never on top of them

**Files.** Create `scripts/make_downscaled_corpus.py`. Test `scripts/test_downscaled_corpus_guard.py`, in the style of
`scripts/test_draft_label_guard.py`.

- [ ] **Failing test first** (`python3 -I -m unittest scripts/test_downscaled_corpus_guard.py`):

```python
class DownscaledCorpusGuardTest(unittest.TestCase):
    """The variant directory links to expected/, never copies it, and refuses to write beside a real labels directory."""

    def test_refuses_when_expected_is_a_real_directory(self):
        with tempfile.TemporaryDirectory() as root:
            os.makedirs(os.path.join(root, "expected"))
            Image.new("RGB", (3000, 4000), "white").save(os.path.join(root, "a.png"))
            os.makedirs(os.path.join(root, "downscaled-2000", "expected"))   # human work, not our link
            with self.assertRaises(SystemExit) as stop:
                make_downscaled_corpus.main(root, [2000])
            self.assertIn("refusing", str(stop.exception))

    def test_links_labels_and_scales_only_what_is_larger(self):
        with tempfile.TemporaryDirectory() as root:
            os.makedirs(os.path.join(root, "expected"))
            Image.new("RGB", (3000, 4000), "white").save(os.path.join(root, "big.png"))
            Image.new("RGB", (1240, 1754), "white").save(os.path.join(root, "small.png"))
            make_downscaled_corpus.main(root, [2000])
            out = os.path.join(root, "downscaled-2000")
            self.assertTrue(os.path.islink(os.path.join(out, "expected")))
            self.assertEqual(max(Image.open(os.path.join(out, "big.jpg")).size), 2000)
            self.assertEqual(Image.open(os.path.join(out, "small.jpg")).size, (1240, 1754))
```

- [ ] **Implementation** (prints counts and bytes only, never a name; `ImageOps.exif_transpose` matches the browser's
  `imageOrientation: 'from-image'`; bilinear approximates canvas `imageSmoothingQuality: 'high'`, recorded as a caveat):

```python
#!/usr/bin/env python3
"""Downscaled copies of the evaluation corpus, so the extraction eval runs at each candidate size before the web sheet
downscales anything (spec 4.7; roadmap Phase 4 entry).   python3 -I scripts/make_downscaled_corpus.py docs/testset 1600 2000 2400

Writes <testset>/downscaled-<edge>/ (git-ignored with the corpus) and LINKS its expected/ to the original labels, so the
human-verified layer is never copied or regenerated (CLAUDE.md). Refuses to write beside a real expected/ directory.
"""
import os
import sys
from PIL import Image, ImageOps

QUALITY = 92


def main(testset, edges):
    names = sorted(f for f in os.listdir(testset) if f.lower().endswith((".jpg", ".jpeg", ".png", ".webp")))
    if not names:
        sys.exit(f"no images in the corpus directory; refusing to report an empty run")
    for edge in edges:
        out = os.path.join(testset, f"downscaled-{edge}")
        link = os.path.join(out, "expected")
        if os.path.exists(link) and not os.path.islink(link):
            sys.exit(f"downscaled-{edge}/expected is a real directory, not this script's link; refusing to write beside human labels")
        os.makedirs(out, exist_ok=True)
        if not os.path.islink(link):
            os.symlink(os.path.abspath(os.path.join(testset, "expected")), link)
        before = after = 0
        for name in names:
            src = os.path.join(testset, name)
            img = ImageOps.exif_transpose(Image.open(src)).convert("RGB")
            longest = max(img.size)
            if longest > edge:
                s = edge / longest
                img = img.resize((max(1, round(img.width * s)), max(1, round(img.height * s))), Image.Resampling.BILINEAR)
            dst = os.path.join(out, os.path.splitext(name)[0] + ".jpg")
            img.save(dst, "JPEG", quality=QUALITY)
            before += os.path.getsize(src)
            after += os.path.getsize(dst)
        print(f"edge={edge} files={len(names)} bytes_before={before} bytes_after={after}")


if __name__ == "__main__":
    main(sys.argv[1], [int(e) for e in sys.argv[2:]] or [1600, 2000, 2400])
```

- [ ] **Commit.** `feat(scripts): downscaled corpus variants that link, never copy, the human labels`

### Task W44.2 — The measurement (paid; **Tanvir approves first**) and the decision

- [ ] Ask Tanvir for approval, with the estimate: 4 runs (baseline + 1600/2000/2400) × the labelled corpus × ~$0.025
  per document (DR-10), on the order of $2.
- [ ] `TESTSET=docs/testset/downscaled-<edge> scripts/evaluate-extraction.sh downscale-<edge>` for each edge, plus the
  baseline run. Then `scripts/replay-extraction-gate.py` against the local DB, so a size that flips retake decisions is
  seen (CLAUDE.md: rerun whenever the inputs to the gate change). Then the DR-12 crop-content check through
  `GET /api/v1/baymax/admin/documents/{id}/verification-rate` for each run.
- [ ] Look at every mismatch **by value** before believing a drop. The harness has manufactured findings before
  (MICRO SIGN, pairing on a non-unique key).
- [ ] Caveat in the report: the corpus is PDF screenshots (`docs/testset/README.md`). PNGs are already capped at 2200 px
  by `PageRenderer.MAX_EDGE_PX`. What this measures for phone JPEGs is best-case, and it is not final until batch 3a
  (phone photos) lands.
- [ ] Write `DR-<n>` in `docs/DECISIONS.md`: the chosen edge and quality, the per-size accuracy, crop-verification rate
  and retake count, and the source runs. Then set `baymax.upload.downscale-max-edge` in `application.properties` (a
  PR). Changing `~/medscribe.env` instead requires a backup first, and the change is listed in the build report.
- [ ] **Try it (web only):** on mobile data, a 12 MP photo of a fictional report uploads as about 1 MB with a moving
  bar. The result reads the same as the full-size upload of the same page, compared field by field on the document page.


## PR E1 (proposal — the PO confirms the scope first) — delete a patient, or everything, from পরিবার

Branch `feat/baymax-web-delete-family`. **Why it is proposed, not scheduled:** the PO direction says everything a family
does works on the web; today deleting a patient or the whole family is admin API only
(`BaymaxFamilyRouter.java:29-30`: `DELETE /api/v1/baymax/patients/{id}`, `DELETE /api/v1/baymax/families/{id}`).
Spec §4.21 draws it ("আম্মার সব তথ্য মুছুন", "সবকিছু মুছে ফেলুন", two-step, dark — never red). Building it changes what a
family can destroy from a browser, so it waits for Tanvir's yes in the morning (CLAUDE.md: scope comes from the PO).

### Task E1.1: the routes and the confirmation (owner only, the session's family checked)

**Files:** create `ui/DeleteFamilyPages.java` (a `WebPages`: `GET/POST /app/patients/{id}/delete`, `GET/POST /app/account/delete`,
literal routes registered before `/app/patients/{id}`), `ui/FamilyView` (the two dark links), `ui-family_*`, `PageFixtures`.

- [ ] **Step 1: failing tests** (`DeleteFamilyPagesTest`, on the kit with a mocked `FamilyAccountUseCase`): the confirm
  page names exactly what goes (papers, values, medicines, reminders, the photos) and asks the family to type the
  patient's name (or "মুছুন" for the account); a POST with the wrong typed text is refused on the page and
  `deletePatient` is never called; a share member gets 403 and no link; a patient of another family is 404 and
  `deletePatient` is never called; the account delete revokes the session cookie and lands on `/`. Buttons are
  `class="btn danger"` (dark `--c-danger`, `DesignTokensTest`'s red rule keeps them non-red).
- [ ] **Step 2: red** (`404` for the routes); **Step 3: implement** on `FamilyAccountUseCase.deletePatient/deleteFamily`
  (existing), after `timeline.me(session.familyId())` proves the patient is this family's and `owner`.

### Task E1.2: the whole delete, end to end through the web, swept from the schema

**Files:** create test `family/WebDeletionAcceptanceTest.java` (Testcontainers Postgres + MinIO; the `Fixture` seed and
`targets()` / `emptyBefore(targets, fx, scope)` / `stillHolding(targets, fx, scope)` from `FamilyDeletionAcceptanceTest`,
made package-visible. `tablesStillHolding` no longer exists: PR 8 (F9a) replaced it with these, the sweep derived from
foreign keys AND column names, with a guard on every swept column the fixture left empty).

- [ ] **Step 1:** seed one family with a row in **every** family/patient table the fixture builder knows (documents,
  observations with verdicts, urgency, upload keys, OTPs, sessions, audit, outbound messages, nudges, window), sign in
  through the web, `POST /app/account/delete` with the typed confirmation, then assert
  `emptyBefore(targets(), fx, t -> true)` is empty before the delete and `stillHolding(targets(), fx, t -> true)` is
  empty after it — the list of columns comes from `pg_constraint` and `information_schema`, and `targets()` first
  asserts both derivations still see what they must (an empty sweep would pass vacuously) — and that MinIO holds
  no object under the family's prefix. A second family seeded the same way keeps every row (the other direction).
- [ ] **Step 2: replay** — skip `document` in the delete (the bug `FamilyDeletionAcceptanceTest` was written for) →
  red naming `document`. Standard pre-merge. Commit `feat(baymax-ui): delete a patient or everything from পরিবার, swept from the schema`.

### Try it (E1) — local only (a production delete is irreversible; try it on a local family)

## PR E2 (proposal — the PO confirms the scope first) — s19 "what's due", and reminders back on

Branch `feat/baymax-web-reminders`. Spec §4.19: "সামনে যা আছে" (follow-ups the doctor wrote: the instruction verbatim,
the date, the crop) and the reminder switch for the patient, which today can only be turned **off** on the web
(`NudgeOptOutUseCase` has `optOutPatient`/`optOutFamily` and nothing to turn back on). DR-18: every nudge stays gated
for the reviewer (`BAYMAX_GATE_NUDGES`), and the reminders themselves arrive only on WhatsApp — so on the web the PO
can check the list and the stored switch, not a delivered reminder.

### Task E2.1: turning reminders back on, on Postgres, both directions

**Files:** modify `NudgeOptOutUseCase`/`NudgeOptOutPort` (+`optInPatient(UUID patientId)`), `PostgresNudgeOptOutAdapter`;
create test `nudge/adapter/out/persistence/OptBackInTest.java`: after `optOutPatient`, `optInPatient` clears the patient's
opt-out and `NudgeDataPort.patient(id).optedOut()` is false; a **family-level** opt-out is not undone by a patient opt-in
(`optedOut()` stays true); opting in a patient of another family changes nothing.

### Task E2.2: the page

**Files:** create `ui/RemindersPage.java` (`GET /app/patients/{id}/reminders`, `POST …/reminders/on|off`), test
`ui/RemindersPageTest.java`; modify `ui/FamilyView` (a link), `ui-family_*`. The page lists open follow-ups due today
or later (paper quotes beside their crops, `PaperIsNeverMediooTest` covers it through its fixture), the BMX-8 rules in
plain words (at most two a week, 09:00–20:00 Dhaka, stop any time), and the switch for the owner. Button copy is
"ঠিক আছে" · "পরে মনে করান" · "রিমাইন্ডার চাই না" — never "বন্ধ করুন" (`GlossaryTest`). Commit
`feat(baymax-ui): what's due, and reminders back on, from পরিবার`.

### Try it (E2) — prod-web for the list and the switch; the reminder itself is WhatsApp and gated (not web)

---

## The morning checklist — every Try it, in merge order

Tanvir, on his phone at medioo.eliooo.org (**prod-web**: WhatsApp only to fetch the code), on the laptop (**local**: P.2's
recipe), with the admin token (**admin**), or **not web**. Each line is the PR's own "Try it", shortened; a failure opens
a fix PR against that PR's area. Write each result in the build report (`BMX report: Done / Deviations / Open questions /
Cost & metrics / Next`).

| PR | Where | Check |
|---|---|---|
| P.1–P.3 | admin | free-tier env changed (backup first); one patient kept in production (or the second added, with the WhatsApp consequence accepted); P.3 output pasted |
| A1, A2 | prod-web | every page looks exactly as before |
| A3 | prod-web | light → dark mode on every page; pinch-zoom; tinted 48 px language pills; focus ring on the code page (laptop) |
| A4 | prod-web | `curl -sI …/app` shows the Baymax CSP; MedScribe keeps Tailwind; no "Refused to load" in the console |
| A5 | prod-web + local | three tabs; চ্যাট composer says chat is coming; JavaScript off still works; (local) two patients → the chooser, nobody's papers |
| A6 | prod-web + local | flags saved with "সেভ হয়েছে"; the number once, only on পরিবার; sign out; (local) "রিমাইন্ডার চাই না" |
| A7 | prod-web + local | the add sheet, "আম্মা · ৬৪" chosen, the allowance line; `/lang/en?back=/%5Cevil.example` stays on the site; (local) the cap screen |
| A8 | prod-web + local | t1–t5 accepted, t5 two pages, t4a+t4b one paper; t6 refused in Bangla; (local) the 4th paper at the cap refused |
| A9 | prod-web + local | the reading page moves to the result by itself, with and without JS; (local) "taking longer" after a restart |
| A10 | prod-web | t3's line, t7's red line, t1 nothing; an old NOW paper shows its line; English |
| A11 | prod-web + admin | an old never-messaged paper shows its line after the deploy; `urgency sweep done trigger=startup` once in the log |
| A12 | prod-web | the banner first, "Medioo-র কথা" after; t1 "তুলে রাখা হয়েছে"; the patient named |
| **A13** | **prod-web** | **Milestone A: sign in → চ্যাট → কাগজপত্র → কাগজ পাঠান → t7 → reading → the red line first → পরিবার → delete the paper → sign out, WhatsApp only for the code**; note whether the explanation also arrived on WhatsApp |
| B1 | prod-web | t3 "▲ সীমার বেশি"; t11 all "কাগজে মিলিয়ে দেখুন"; old papers gain verdicts |
| B2 | prod-web | rows with bars only where placed; medicines crop first; the doctor's lines under "কাগজে লেখা"; 200 % zoom at 360 px |
| B3 | prod-web + local | t7: 999 only under the red-flag questions; t10: the phrase block; (local) a +44 family: no 999, the share text has no value |
| B4 | prod-web | a blank wall and t8: Bangla reasons, no English |
| B5 | prod-web | t8 → "আবার তুলুন" → the same patient; three in a row → "অন্য কাগজ পাঠান" leads; the allowance unchanged |
| B6 | prod-web | `/all` segments without JS; "বড় করে দেখুন"; the value sheet with earlier readings in the same unit; t11 "Trace" |
| C1 | prod-web + local | an old `/app/patients/<id>` link opens কাগজপত্র; (local) the switcher sheet, the 30-minute memory |
| C2 | prod-web | document-date order; red/amber chips, nothing on t1; "১৪টি মান, ১টি সীমার বাইরে"; "আরও দেখুন" joins cleanly |
| C3 | prod-web | months, filters, "(আপলোডের তারিখ)" on t9, the retake row; dark mode; the `/app/timeline` weight in the report |
| C4 | prod-web | the attention stack in order with t9's follow-up on paper; no dismiss; the strip on কাগজপত্র; the whole session web only |
| D1 | prod-web | first-load total < 200 KB (DevTools, in the report); Bangla conjuncts and bold |
| D2 | prod-web | no রোগী/নথি anywhere; possessives read naturally; English "Ma's" |
| D3 | prod-web + local | separate → two papers; one report → one; a double tap → one; (local) separate at the cap → nothing created |
| D4 | prod-web + local | the tray, reorder, the progress bar, airplane-mode retry → one paper, DOCX never posted; (local) downscale on |
| D5 | **not tonight** | the paid measurement only after Tanvir's approval; then DR-<n> and the switch |
| E1, E2 | after the PO's yes | E1 local only; E2 the list and the switch on the web, the reminder itself on WhatsApp (gated) |

## What still cannot be done on the web (for the build report)

- **Add a patient** (s05) and **share a patient** (s20): admin API only (`BaymaxFamilyRouter.java:26-28`); Phase 5 (sibling
  accounts, roles D30, per-recipient delivery).
- **Delete a patient or everything** (s21 delete): admin API only until **E1** (proposed).
- **Reminders back on**, and the "what's due" list (s19): until **E2** (proposed). Reminders themselves arrive only on
  WhatsApp and are gated for the reviewer (DR-18).
- **The nudge opt-out page** (`/app/nudges/opt-out?t=…`): its token arrives only inside a WhatsApp reminder.
- **Chat answers** (s14, s15): the composer says chat is coming; Phase 3 needs D11's clinician sign-off.
- **Trends** (s18), **the demo family** (s22), **the NOW acknowledgement** "দেখেছি" (D14), **time zones per recipient** (D29).
- **Sign-in itself** needs WhatsApp for the code (PO-approved: the one WhatsApp step). A foreign number can sign in only
  locally (P.2), so every "reader abroad" check is local.

## Open questions for the PO (proposed in the build report; none blocks a merge)

1. **D28 short forms** — "এখনই ডাক্তারের কাছে" / "এ সপ্তাহের মধ্যে ডাক্তার দেখান" (C2): confirm or replace in `messages_bn`.
2. **New wording** listed per PR (retake reasons marked *proposed*, `now.why.other`, `add.cap`, `add.viewonly`,
   `upload.err.over_allowance`, `upload.err.signed_out`, `up.keep`, `value.chip.outside`, `value.chip.critical`,
   `value.unverified`): the copy owner edits the bundles; the tests read them.
3. **follow_up_past_due on the Dhaka day** (A10): it can raise up to six hours earlier than before, never later.
4. **The home stack's 30-day window** (C4): spec §4.6's window for NOW/THIS_WEEK; confirm a NOW paper uploaded 31 days ago
   leaving the stack (it stays on কাগজপত্র with its chip).
5. **A web upload still notifies WhatsApp** when WhatsApp is on (A13 pins today's behaviour); D32's opt-in checkbox is Phase 4.
6. **The retake streak**: per patient on the web, family-wide on WhatsApp (B5); proposal: per patient everywhere.
7. **E1 and E2**: build them next?
8. **WhatsApp copy the glossary does not own**: "টাইমলাইন" in `explanation.*`/`detail.footer`/`nudge.trend`/`wa.free_tier`
   now that the tab is "কাগজপত্র"; "রোগী" in `explanation.now` and `safety.red_flag_line` (D11 wording);
   "স্বাভাবিকের চেয়ে অনেক বেশি/কম" in `line.value_critical_high/low` vs kit §0's "সীমার অনেক বেশি/কম"; `explanation.routine` (D35).
9. **The landing page's illustration** still draws a ROUTINE tag (`LandingPages.tag("routine")`) against D27; the landing
   rework (§4.1) owns it.
10. **"হাঁপানি" (asthma)** in s21's flags is not a flag the nudge rules know; not added.

## How each review finding was resolved

| Finding | Where it is fixed |
|---|---|
| Nine empty-bodied tests | Every body written: `ValueVerdictTest` (B1 14.1), `ValueVerdictNeverSoftensTest.insideThenUnknownBecomesUnknown` (B1 14.2), `TimelineReadModelTest` ×6 (C2 16.2) — each with its named mutant and expected red (C2's table) |
| One central writer only guarded on `assess` | `OneUrgencyWriterTest` (A11): `assess` and `DocumentUrgencyPort.raise` callers, plus a SQL-string and migration sweep for writes of the urgency/verdict columns with a non-vacuity count; `NoUrgencyColumnInEntitiesTest` (A10) for entity mappings; replays name `ExplanationService` and `PostgresDocumentRecordAdapter` |
| Two timeline read models | One owner, C2 (`web/domain/TimelineCursor`, `TimelineReadPort`, `TimelineEntry.chip()`, attention, outside count); `UrgencyChip` calls `chip()`; C3 is UI only; one `TimelineCursorTest` |
| Copy keys defined twice; retired "বন্ধ করুন"; `sheet.of` case ending | Bundle parts with one owner per key; `UiCopyTest` reads raw lines (R2); `sheet.close=বন্ধ` shared in `ui-shell`; `sheet.of={0} · {1} · {2}`; `GlossaryTest` in every pre-merge |
| Three upload URLs | One entry `/app/add?patient=&back=`; the retake form posts there; `UploadEntryPointsTest` (A8) derives forms and camera links from every page |
| Stored INSIDE shown when code cannot prove it | `ValueRows.kindOf` demotes unless `insideByCode` (B2); the interim line does the same (B1 14.3); both directions plus the replay |
| ROUTINE-has-no-chip tests could not fail | Absence asserted on every urgency bundle line (derived from `messages_bn`, long and short); `UiCopy.urgency(ROUTINE)` throws, so the "any non-null level" mutant fails naming ROUTINE (A10, A12, C2); THIS_WEEK across every gate state (A12) |
| PENDING replay impossible | The guard is `ResultView`'s constructor; replayed at `Gate.of` → `F3: model wording only when released` (A12 21.2); the renderer check is the second line |
| Crop replay failed for the wrong reason | B6: the blank-crop fixture plus the "skip the image" mutant fails inside the row's block; the sweep covers values, medicines, follow-ups and clinical context |
| Budget and inline-code checks on a hand list | `RenderedPages` from `RouteTable` with a fixture per route (A1, A3); assets fetched through the client; quoted `url(` and a `url(` count guard (D1); one budget test |
| Nothing tested the whole web journey | `WebOnlyJourneyAcceptanceTest` (A13), WhatsApp off and on, with a derived route-coverage check; B5, B6, C2, C4 extend it |
| Vacuous log guard | The INFO line is asserted first, the logger level set, then the absence (A8); replay logs the filename |
| A sweep that may never finish | `urgency_attempted_at` ordering, `aSweepConverges`, the due-today edge (A11), verdict-NULL convergence (B1) |
| Stale base; broken migration preflight | Base `e6264a4`; the preflight command fixed everywhere |
| One migration order | V18–V21 in merge order; Phase 0 PRs 8/9 move to V22/V23 in A10's commit, with the reason |
| Single merge order; R1; R2 | The merge table; A1 and A2 move-only, proven by a byte-for-byte snapshot of every route |
| One owner for urgency copy; consumed F1 names | `UiCopy.urgency` (A10) reads `messages_*`; `DocumentUrgencyUseCase.currentOrAssess` (A10) is what pages call |
| Conditionals | None: the order is fixed; the CSP ships final in A4 |
| PR size | 13→A10/A11, W4.1→A7/A8, L4→A5/C1, L2→A4/D1, L6 UI only |
| Free-tier preflight; flags for non-web tries | P.1–P.3; every Try it tagged; the "cannot be done on the web" list |
| Should-fixes taken | constraint cases both directions for every V18–V21 constraint; the V14 writer test; the transaction test with four separate replays; backfill fed the code's constants and `legacy` instead of `NOT VALID`; prose checks per constant and over the JSON; one Dhaka calendar; derived contrast pairs; rule-level type scale; 48 px targets derived; CSP registration test; font coverage; no Bangla in Java literals; never-preselect over every tab; no write on a list GET (`currentOrAssess` only on the document page, second read writes nothing); SQL-filtered attention; reason enum with a generic block; fixed clock for the free tier; the race on Postgres; `back` allow-list; developer-text path sweep; `upload.js` request functions under node; paper-vs-Medioo derived check; `UploadService`/`DocumentReadPort` instead of growing `TimelineService`/`DocumentRecordPort`; the overnight merge policy; interim markup ≤ 10 lines replaced next; Phase 0 PRs 7–10 rebase note; E1/E2 proposals |
| Declined | none of the findings; one deviation: `UiCopy.urgency(ROUTINE)` **throws** rather than returning empty, so a ROUTINE chip is impossible by construction and the "any non-null level" mutant fails loudly |

## Self-review against the spec

- **§1 principles.** Urgency code-computed, stored, shown first in every gate state, only rises (A10–A12); ROUTINE gets
  nothing (A10, A12, C2); tri-state verdicts with INSIDE only by code (B1, B2); কাগজে লেখা and Medioo-র কথা never alike
  (B2's derived check); every fact beside its crop (B6's sweep); medicines verbatim and crop first (B2); the patient
  named (A12 chip; D2 possessive); 48 px targets (A3); Bangla body 17 px, line-height ≥ 1.5 (A3); printed digits for
  values, Bangla digits for UI counts and dates (`UiCopy.t`, `BanglaNumbersOnPagesTest`).
- **§2.** F1 (A10, A11), F2 (B1), F3 (A12), F5 (B4), F7 (A10's calendar, A9's Dhaka time, B3's action), F8 (D2).
- **§3 IA.** `/app/home`, `/app/timeline`, `/app/family`; `/app/documents/{id}`, `/all`, `/values/{obs}`; `/app/add`;
  sheets as URLs (`?sheet=patient`); literal routes before `{id}` inside `DocumentPages`.
- **§4.** s06 (C4), s07 (A7, A8), s08 (D4), s09 (A9, D4), s10 (A12, B2), s11 (B3), s12 (B5), s13 (B6), s16 (C2, C3),
  s17 (B6), s21 basics (A6). Not built: s14/s15 (D11), s18, s19/s20/s21-delete (E1/E2/Phase 5), s22.
- **§8–§10.** Tokens v3 light and dark, contrast, red reserved, type scale, targets, zoom (A3); CSP (A4); fonts local
  first and the 200 KB budget (D1); JS only on the reading page and the add sheet, each ≤ 40 KB (A9, D4).
- **CLAUDE.md.** TDD with both directions; every guard replayed on its defect with the red named; Testcontainers for
  every constraint and trigger; derived lists with non-empty guards; logs without patient text (A8, A10, A11);
  explicit staging; the ignored-directory check in every PR that adds one; the gate replay harness in B4; no tool
  overwrites human labels (D5).
