# Medioo Web Rebuild — Wave 2b (tokens, components) and Wave 3 back end Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. In this repo each PR below runs as its own Workflow pipeline (master plan Part E): one worktree, one branch, one PR, no merge inside the pipeline.

**Goal:** Give the Wave 3 screens their visual vocabulary (one token file, the NOW red in one token, twelve components with catalog states) and every `/app/api` read and write they still lack (attention stack, timeline by month, calendar, follow-ups, the দেখেছি acknowledgement, the family settings, lab series, medicines as written), and fix the two data defects the চ্যাট and কাগজপত্র screens would otherwise show (a retake that never leaves the stack, a retake filed under a misread date).

**Architecture:**
- **Web (W2a, W2c–W2e).** `medioo-web/src/styles/medioo.tokens.json` is the one source; a TypeScript renderer turns it into the committed `tokens.css`, byte-compared on every test run. Components are React server components with one global stylesheet each, every class prefixed by the component's kebab name, so a built-CSS test can say which component uses `--now*`. The catalog route exists only in a build made with `MEDIOO_CATALOG=1`.
- **API (B3, B5, B6).** Every route joins `AppApiRouter.mediooAppApiRoutes` behind `AppApiGuard`. Each reads through an existing use case where one exists (`AttentionUseCase`, `TimelineUseCase`, `NudgeOptOutUseCase`, `FamilyAccountService`) and through one new read port per PR otherwise. Display decisions stay in Java (`PaperDisplay.kindOf`, `MarkerThreshold`'s fold, `PatientCalendar`).
- **Data (D1, D2).** Two migrations, each asserting its rule where every writer meets it: a foreign key and a trigger for the retake link (D1), a generated column for the day a paper is filed under (D2).

**Tech Stack:**
- Spring Boot 3.4 WebFlux and R2DBC, Flyway, Testcontainers (Postgres 17, MinIO), JUnit 5, AssertJ.
- Next.js 15.5 / React 19 / TypeScript strict, Vitest, Testing Library, Playwright with `@axe-core/playwright`, stylelint, postcss.

**Spec:**
- [`2026-10-21-medioo-nextjs-rebuild.md`](2026-10-21-medioo-nextjs-rebuild.md), the master plan: Global Constraints, Part A (A3, A5, A7, A8), Part B decisions 7, 8 and 9, Part D (W2a, W2c–W2e, B3, B5, B6, D1, D2), Part E.
- [`2026-10-21-medioo-wave2.md`](2026-10-21-medioo-wave2.md): its Global Constraints, Shared interfaces (`ApiReason` mechanism, `Snapshot`, `DeletionSweep`, the generated-file envelope, PR-order hotspots) are binding here and not restated.
- [`docs/design/2026-10-21-approved/`](../../design/2026-10-21-approved/README.md): `phone.html`, `desktop.html`, `direction-contract.md`, the wordmark SVGs, `mio.webp`, `mio-head.webp`.
- `docs/DECISIONS.md`: DR-12, DR-13, DR-22, DR-40, DR-41 (item 5 is the দেখেছি rule this plan's B3 implements).
- `CLAUDE.md`: its flattering-failure rules govern every test below.

**In scope:** W2a, W2c, W2d, W2e, B3, B5 (E1/E2 excluded), B6, D1, D2. **Out of scope:** the screens (W3–W10), W2b, B2, B4a–c, D4 (all merged: #106, #108, #107, #109, #110, #105), the value-sheet API (W6's plan), the chat-thread API for W5's message history (W5's plan), E1/E2.

**Order and lanes (master plan Part E):**

| PR | Branch | Lane | Starts after | Migration |
|---|---|---|---|---|
| W2a | `feat/medioo-web-tokens` | C | now (decision 7 has a recommendation; see the PO table) | none |
| W2c | `feat/medioo-web-components-c` | C | W2a merged | none |
| W2d | `feat/medioo-web-components-d` | C | W2c merged | none |
| W2e | `feat/medioo-web-components-e` | C | W2c merged (parallel with W2d) | none |
| B3 | `feat/baymax-web-api-attention-timeline-ack` | B | now; **merge only after V23 (PR 9) is on main** | `now_ack` |
| B5 | `feat/baymax-web-api-family` | B | now (parallel with B3, B6) | none |
| B6 | `feat/baymax-web-api-health` | B | now (parallel with B3, B5) | none |
| D1 | `fix/baymax-chat-cards-retake-link` | D | Phase 0 PR 9 (V23) merged, B3 merged | `retake link` |
| D2 | `fix/baymax-retake-upload-day-uncertain-dates` | D | Phase 0 PR 9 (V23) merged, B3 merged | `upload-day filing, uncertain dates` |

## Global Constraints

The master plan's [Global Constraints](2026-10-21-medioo-nextjs-rebuild.md#global-constraints) and the Wave 2 plan's [Global Constraints](2026-10-21-medioo-wave2.md#global-constraints) apply **verbatim** to every task here (the `gl` alias, web commands, additive API, regenerated `app-routes.txt`, `AppApiLogSafetyTest`'s derived route list, `{"reason"}` errors, no Bangla literal outside generated files, logs, commits and staging, red replays, read-only box). `CLAUDE.md` applies too. New here, one line each:

- **No migration above V23 merges before V23 is on `origin/main`.** `BaymaxSchemaMigrator` runs Flyway with the defaults (`outOfOrder` false, `ignoreMigrationPatterns` `*:future`). A V24 applied in production before V23 makes V23 an *ignored* migration, and the next startup fails `validate`. B3, D1 and D2 each check `git ls-tree origin/main baymax/src/main/resources/db/baymax/migration/ | grep -c V23__` prints `1` right before merging; if it prints `0`, stop and list it under `box_steps_pending`.
- **Migration numbers are taken at merge time** (master plan): the next free `V<n>` above V23 on `origin/main` after the final rebase. Below, a migration is named `V<n>__<name>.sql`; the PR renames the file on rebase and nothing else references the number.
- **The account holder is a `family_account`.** There is no member table. Each WhatsApp number is one `family_account`, and a share member signs in as their own `family_account`, which sees the shared patient through `share_member` (`PostgresHealthRecordAdapter.VISIBLE_SQL`). So DR-41's "member id" is `WebSession.familyId()`, the viewer's own account, never the patient's family. Decision 8's recommendation (per account holder) is therefore "per `family_account`", and a share member is a different account holder from the owner.
- **Every new table with family or patient data** has `family_id` NOT NULL (and `patient_id` where relevant) with a foreign key, is deleted explicitly in `PostgresHealthRecordAdapter.deleteFamily` and `deletePatient` and, where it has `document_id`, in `PostgresDocumentRecordAdapter.deleteDocument`. `FamilyDeletionAcceptanceTest.targets()` and `DeletionSweep.documentIdTables` find it from the schema; each of those tests' fixtures must hold a row in it before the delete (their emptiness guards fail until it does, which is intended).
- **Component styles.** One plain stylesheet per component, `src/components/<Name>/<Name>.css` (or `src/components/safety/<Name>/<Name>.css`), imported by the component. Every class selector in it starts with `.<kebab-name>` (`NowBanner` → `.now-banner`, `.now-banner__call`, `.now-banner--abroad`). No CSS Modules: the class names reaching the built CSS are the source names, so the built-CSS test reads them without guessing Next's hashing.
- **Colour literals live only in `src/styles/tokens.css`**, which is generated. Everywhere else stylelint refuses hex colours, named colours and the colour functions (W2a).
- **New copy keys are drafts.** A key added to `baymax/src/main/resources/baymax/ui-*_{bn,en}.properties` is listed in the PR body under "Pending (PO / copy owner)", with both strings. The copy JSON is regenerated by `WebCopyExportTest` (its failure message prints the `cp` command). No component names a string that is not a key.
- **Catalog states** (`/app/catalog-test`) use only fictional data. A Bangla name a state needs (a long one, for overflow) comes from `medioo-web/fixtures/catalog/names.json`, written by a Java test, never typed in TypeScript.
- **Boards (A7).** Each W PR publishes a private screenshot board (phone 390×844, desktop 1280×800, light and dark) of its catalog states beside the matching prototype scene, listed under "Pending (PO / box)". No screenshot goes into git.

## PO decisions this plan needs

Each blocks only the step named; every other step goes ahead. Each PR that meets one lists it under "Pending (PO / box)" in its body, with the default below.

| # | Decision | Blocks | Default until ruled |
|---|---|---|---|
| Q1 | **Part B decision 7, the NOW red.** | W2a merge | The recommendation, `#d12c24` light and `#d93229` dark, in the one token `color.now`. A different choice is a one-line change to `medioo.tokens.json` plus the regenerated `tokens.css`; the contrast test says at once whether it still clears 4.5:1 with `on-now`. |
| Q2 | **Part B decision 8, দেখেছি scope.** | B3 merge | The recommendation: per account holder (`family_account`, see Global Constraints). If the PO picks per family, DR-41 records the trade-off before B3 merges, and B3's member-B test is inverted, not deleted. |
| Q3 | **Part B decision 9, "current medicines".** | B6 merge | The recommendation: the latest prescription's medicines as written, with its date, under the heading key `health.medicines.as_written` (draft bn "প্রেসক্রিপশনে যেমন লেখা — সর্বশেষ, তারিখসহ", en "As written on the prescription — the latest, with its date"). |
| Q4 | **New copy keys.** দেখেছি's label, the calendar legend and day status, the medicines heading. | the PR that adds each key, at merge | Drafts as listed in each task. |
| Q5 | **Opting back in to reminders from the web** (B5). Today a stop is one-way (`NudgeOptOutPort` has no opt-in). | B5's two opt-in routes, at merge | Owner only, explicit tap, clears only what it names (the patient's stop or the family's stop); the master plan lists "opt-back-in" as B5's proof, so the routes are built and tested. |
| Q6 | **A paper whose printed date the model marked uncertain** (D2). | D2 merge | Filed under its printed date, as today, and carried as `date_uncertain: true` so W8 marks it and a trend drops the direction (spec §4.18). Only a retake is moved to its upload day. |
| Q7 | **WhatsApp retakes are not linked** (D1). A photo sent on WhatsApp after a "send it again" message carries no signal of which paper it replaces. | nothing | Unlinked. Only the web's retake form and the JSON upload name the paper they replace. The stale WhatsApp case stays as today and the PR body says so. |

## Review Focus

Inputs no task's main tests exercise, most likely to reach a family first. Each has a test added to the task that owns the code.

1. **The banner after the reviewer approves a held NOW paper, seen by someone who already tapped দেখেছি** (urgency gate mode, weeks 0–2). The paper was `pending` at the tap and becomes `approved`. The banner must come back, because the family has not yet been told anything. Owner: B3, test `aLaterGateReleaseShowsTheBannerAgain`.
2. **A share member who is also an owner** (a sibling with their own parent plus a shared one). Their দেখেছি on the shared parent's NOW paper must not hide the banner for the owner of that parent, and the owner's tap must not hide it for them. Owner: B3, tests `afterTheOwnerAcksTheShareMemberStillSeesTheBanner` and `afterTheShareMemberAcksTheOwnerStillSeesTheBanner`.
3. **A long Bangla patient name in every component that names a patient** (NowBanner title, VerdictCard, StatusChip's row, MonthCalendar's day status). It must wrap, never clip or overflow. Owner: W2c/W2d/W2e catalog states `long-name`, asserted by `catalog.spec.ts › no element overflows its box` (`scrollWidth <= clientWidth` on every element under `[data-catalog-state]`).
4. **Two readings of one marker that differ only by MICRO SIGN vs GREEK MU vs ASCII u**, and two genuinely different rows sharing a name (an absolute count and a percentage). The first must be one series, the second two. Owner: B6, tests `microSignGreekMuAndAsciiUAreOneSeries` and `aCountAndAPercentageOfOneCellTypeAreTwoSeries`.
5. **A retake uploaded just after midnight in Dhaka on the first of a month** (a family that photographs at night). It is filed under the new month, by the Dhaka day of its upload, never under a date misread off the blurred page. Owner: D2, test `aRetakeUploadedAfterDhakaMidnightIsInTheNewMonth`.

---

## Shared interfaces (read before any task)

**`ApiReason` additions** (file `baymax/src/main/java/com/elioo/baymax/web/adapter/in/api/ApiReason.java`; the Wave 2 plan's mechanism, `BY_REASON`). The first PR to need a name adds it; a later PR rebases and reuses it.

| Name | Status | Raised by | Added by |
|---|---|---|---|
| `VIEW_ONLY` (exists) | 403 | add `Map.entry("not_owner", VIEW_ONLY)`: `TimelineService.updateChronicFlags` throws `not_owner` for a share member | B5 |
| `INVALID` (exists) | 400 | `month=` not `YYYY-MM`, an ack body that is not a list of `{document_id, urgency_assessed_at, release_state}` | B3 |
| `NOT_A_RETAKE` | 400 | an upload's `replaces` names a paper of this patient that is not NEEDS_RETAKE (`not_a_retake`) | D1 |

**Route list after this plan** (each PR adds only its own; `docs/infra/app-routes.txt` is regenerated):

| Route | PR |
|---|---|
| `GET /app/api/now-banner`, `PUT /app/api/now-banner/ack` | B3 |
| `GET /app/api/patients/{id}/attention` | B3 |
| `GET /app/api/patients/{id}/timeline` (`?cursor=&type=lab\|rx`) | B3 |
| `GET /app/api/patients/{id}/calendar` (`?month=YYYY-MM`) | B3 |
| `GET /app/api/patients/{id}/follow-ups` | B3 |
| `GET /app/api/family`, `PUT /app/api/patients/{id}/flags`, `PUT /app/api/patients/{id}/reminders`, `PUT /app/api/family/reminders`, `PUT /app/api/language`, `GET /app/api/family/delete-token`, `DELETE /app/api/family` | B5 |
| `GET /app/api/patients/{id}/labs`, `GET /app/api/patients/{id}/medicines` | B6 |

`AppApiRouter.mediooAppApiRoutes` gains one handler parameter per new handler class. `AppApiGuardTest`'s constructor call follows. Every new route is registered before the `/app/api/**` catch-all, as today's are; none of them shares a prefix with a `{variable}` route of the same method, so their order among themselves does not matter.

**JSON conventions** (copied from `PaperJson`, so React reads one shape): snake_case keys; dates ISO `YYYY-MM-DD` with a sibling `date_is_upload_day`; instants ISO-8601 UTC; images as `{"src": ImageRefs.crop(...)}` or `ImageRefs.page(id, 1)`; copy as `*_key` naming a key that resolves in both languages; no `confidence`, `model`, `status_reason`, phone number (except `GET /app/api/family`'s `account.number`, see B5) or patient text in any log.

**MSW fixtures.** Each new GET writes its fixtures from its contract test, exactly as `PaperApiContractTest` does: `medioo-web/fixtures/api/<area>/<case>.json`, envelope `{"_meta":{"generated_by":"baymax <TestClass>","source":"<route> over <case>"},"status":<int>,"body":…}`, compared with `Snapshot.matches(committed, written, Snapshot.json(envelope), "cp baymax/<written> medioo-web/fixtures/api/<area>/<file>")`, plus an `everyCommittedFixtureIsACurrentCase` test per area. `medioo-web/src/lint/bangla-literal.test.ts`'s real-tree test already exempts `fixtures/api/` (line 156); W2c adds `fixtures/catalog/`.

**Shared test support** (`baymax/src/test/java/com/elioo/baymax/testsupport/`), each added by the first task that needs it:
- `FamilySweep.java` (B3, or B5 if first): `targets(Connection)`, `emptyBefore(...)`, `stillHolding(...)` and `FK_LESS`, lifted out of `FamilyDeletionAcceptanceTest` (where they are private), which then delegates. One schema derivation for the admin delete and the API delete.
- `NowPapers.java` (B3): `nowPaper(ApplicationContext, PostgreSQLContainer<?>, PatientProfile)` → `UUID`, a DONE lab report with stored `now` (as `AppApiContext.paper(…, "now")`), and `criticalValue(…)`, an observation `"12.0"` against a printed `3.5`–`5.1` (more than twice the high: the stopgap's NOW). Package-public so `web.adapter.in.api` and `web.adapter.out.persistence` tests share it.

---

## Task W2a: Tokens, fonts, the colour-literal ban, the NOW-token and contrast tests

Branch `feat/medioo-web-tokens`. Lane C. Decision 7 is the only PO input (Q1); everything else goes ahead.

**Files:**
- Create: `medioo-web/src/styles/medioo.tokens.json` (the source), `medioo-web/src/styles/build-tokens.ts` (the renderer), `medioo-web/src/styles/tokens.css` (generated, committed), `medioo-web/src/styles/contrast.ts` (WCAG maths and compositing), `medioo-web/.stylelintrc.json`.
- Create tests: `medioo-web/src/styles/tokens.test.ts`, `medioo-web/src/styles/contrast.test.ts`, `medioo-web/src/styles/stylelint-bans.test.ts`, `medioo-web/src/styles/now-usage.ts` (the checker) with `medioo-web/src/styles/now-usage.test.ts`, and `medioo-web/src/styles/built/now-usage.built.test.ts` (runs on the build).
- Create: `medioo-web/vitest.built.config.mts` (`include: ["src/styles/built/**/*.built.test.ts"]`).
- Modify: `medioo-web/vitest.config.mts` (exclude `src/**/*.built.test.ts`), `medioo-web/package.json` (scripts `lint:css`: `stylelint "src/**/*.css"`, `test:built`: `vitest run -c vitest.built.config.mts`; devDependencies `stylelint`, `postcss`, pinned exact, see Step 1), `medioo-web/src/app/layout.tsx` (`import "@/styles/tokens.css"` before `./globals.css`), `medioo-web/src/app/globals.css` (the `@font-face` blocks stay; the `:root` colour block is deleted; every `var(--c-*)` becomes the new token name), `medioo-web/e2e/reading.spec.ts` (`var(--c-bg)` → `var(--bg)`), `.github/workflows/ci-cd.yml` (in "medioo-web lint, typecheck, unit tests, build": `pnpm run lint:css` after `lint`, `pnpm run test:built` after `build`).

**Interfaces:**
- Produces, for every later W task:
  - CSS custom properties on `:root`: the colour tokens below, `--font-bn`, `--font-sys`, `--font-mono`, `--text-xs` (12px) … `--text-xl`, `--radius-*`, `--space-*`, `--target-min` (44px), `--target-primary` (48px), `--sidebar-w` (256px), `--conversation-w` (400px).
  - `renderTokensCss(src: TokenFile): string` and `resolveTokens(src: TokenFile): Record<Theme, Record<Layout, Record<string, string>>>` (`Theme = "light" | "dark"`, `Layout = "phone" | "desktop"`), exported from `build-tokens.ts`.
  - `contrastRatio(fg: string, bg: string): number`, `composite(over: string, under: string): string` from `contrast.ts`.
  - `nowUsageViolations(css: string, allow: RegExp): string[]` from `now-usage.ts`; `NOW_ALLOWED = /^\.(now-banner|verdict-card--now|status-chip--crit)/`.
- The token file shape:
  ```json
  {
    "_meta": {"source": "docs/design/2026-10-21-approved/{phone,desktop}.html, reconciled (plan W2a)"},
    "color": {
      "<name>": {"light": <value>, "dark": <value>, "desktop"?: {"light"?: <value>, "dark"?: <value>},
                 "role": "surface" | "text" | "non-text" | "fill", "on"?: ["<surface token>", ...], "note"?: "..."}
    },
    "_absorbed": {"<literal from a prototype>": "<token name> | dropped: <why>"}
  }
  ```
  A `<value>` is a hex (`#rrggbb`), `rgba(r, g, b, a)`, `{"mix": ["<token>", "#rrggbb", <0..1>]}` (sRGB interpolation toward the hex) or `{"alpha": ["<token>", <0..1>]}`. Derived values are resolved by `resolveTokens` and written to `tokens.css` as literals, so the CSS never needs `color-mix()`.

**The reconciled colour tokens.** Phone values apply below 900 px; a `desktop` override applies at `@media (min-width: 900px)`. Values from the prototypes' `:root` blocks:

| Token | light (phone / desktop) | dark (phone / desktop) | role, on |
|---|---|---|---|
| `bg` | `#ffffff` / same | `#000000` / `#1c1c1e` | surface |
| `bg2` | `#f2f2f7` / `#f5f5f7` | `#000000` / `#141416` | surface |
| `bg3` | `#ffffff` / same | `#1c1c1e` / `#2a2a2d` | surface |
| `side`, `canvas` | desktop only: `#f3f4f6`, `#e8e9ed` | `#232326`, `#101012` | surface |
| `label` | `#000000` / `#1d1d1f` | `#ffffff` / `#f5f5f7` | text, on every surface |
| `label2` | `rgba(60, 60, 67, 0.82)` | `rgba(235, 235, 245, 0.78)` | text, on every surface |
| `label3` | `rgba(60, 60, 67, 0.36)` / `0.38` | `rgba(235, 235, 245, 0.32)` | **non-text** (placeholder hint, disabled glyph); never a `color:` for text |
| `sep`, `fill`, `hover`, `selbg` | from the prototypes | from the prototypes | non-text |
| `recv`, `recv-ink` | `#e9e9eb`, `#000000` | `#26252a` / `#2c2c2f`, `#ffffff` | surface; text on `recv` |
| `sent`, `sent-ink` | `#06788a`, `#ffffff` | `#06788a`, `#ffffff` | fill; text on `sent` |
| `tint`, `tint-ink`, `on-tint`, `tint-soft` | `#06788a`, `#045f6d`, `#ffffff`, `rgba(6, 120, 138, 0.12)` | `#5cc8d6`, `#5cc8d6`, `#062a30`, `rgba(92, 200, 214, 0.16)` | fill / text / text on `tint` / surface |
| `mio-bg` | `#dff0f2` | `#1d3b41` | surface |
| **`now`** | **`#d12c24`** | **`#d93229`** | **fill; note "Part B decision 7: the one NOW red"; no desktop override** |
| `on-now` | `#ffffff` | `#ffffff` | text, on `now` |
| `now-ink` | `{"mix": ["now", "#000000", 0.22]}` (→ `#a3221c`) | `{"mix": ["now", "#ffffff", 0.45]}` (→ `#ea8e89`) | text, on every surface and `now-soft` |
| `now-soft` | `{"alpha": ["now", 0.12]}` | `{"alpha": ["now", 0.18]}` | surface |
| `week`, `week-soft` | `#92400e`, `rgba(255, 149, 0, 0.16)` | `#ffb340`, `rgba(255, 159, 10, 0.2)` | text on every surface and `week-soft` / surface |
| `unk-soft` | `rgba(120, 120, 128, 0.14)` | `rgba(120, 120, 128, 0.26)` | surface |
| `green` | `#34c759` | `#30d158` | non-text (the follow-up circle) |
| `paper`, `paper-ink` | `#fffdf8` / `#fffefa`, `#2b2b2b` | `#f4efe4` / `#f4f0e6`, `#2b2b2b` | surface; text on `paper` |
| `bar`, `scrim` | from the prototypes | from the prototypes | surface (translucent) / non-text |

`stage` (the phone frame's backdrop) is a prototype device and is not a token. Every other literal the prototypes use outside their `:root` blocks is mapped in `_absorbed`; for example `"#e0352b": "now (decision 7)"`, `"#c4271e": "now-ink"`, `"#25d366": "dropped: WhatsApp brand green on the prototype's share button, a prototype device"`, `"#0a84ff": "tint"`.

- [ ] **Step 1: Add the dependencies.** `pnpm -C medioo-web add -D --save-exact stylelint@<v> postcss@<v>`. Use the newest release that is at least two weeks old on the day (at writing: `stylelint` 17.16.0, `postcss` 8.5.31) and record both in the PR body. Expected: `pnpm-lock.yaml` and `package.json` change, nothing else.
- [ ] **Step 2: Write the failing token tests** in `src/styles/tokens.test.ts`:
  - `theCommittedTokensCssIsTheRendererOutput`: `readFileSync("src/styles/tokens.css", "utf8")` equals `renderTokensCss(json)`; on mismatch the test writes `build/tokens.css` and its message is `regenerate: cp medioo-web/build/tokens.css medioo-web/src/styles/tokens.css`.
  - `theNowRedIsOneTokenAndEveryNowTokenDerivesFromIt`: the only colour tokens whose name starts `now` and whose value is a literal are `now` and `on-now`; every other `now-*` value is `{"mix"|"alpha": ["now", …]}`. Then the derived direction: in a deep copy with `color.now.light = "#0000ff"`, every resolved `now-*` light value differs from the original's, and `now-ink` light resolves to `#0000c7` (`mix(#0000ff, #000000, 0.22)`).
  - `decisionSevenIsTheNowRed`: resolved `now` is `#d12c24` light and `#d93229` dark in both layouts.
  - `everyLiteralInThePrototypesIsAbsorbed`: read both prototype files from `../docs/design/2026-10-21-approved/`, strip their `:root` blocks, collect every `#[0-9a-fA-F]{3,8}\b` and `rgba?\([^)]*\)`; each one is either a resolved token value or a key of `_absorbed`. Guard: the collected list is non-empty and contains `#25d366` (the reader really read the prototypes).
  - `everyFontSizeTokenIsAtLeastTwelvePixels`: every `--text-*` in the rendered CSS is `>= 12px`; the list holds at least four sizes.
  - `bothThemesAndBothLayoutsAreEmitted`: the CSS has a `:root` block, `@media (prefers-color-scheme: dark) { :root:not([data-theme="light"]) {…} }`, `:root[data-theme="dark"] {…}`, and `@media (min-width: 900px)` blocks for each theme; each dark block defines every colour token.
- [ ] **Step 3:** Run `pnpm -C medioo-web exec vitest run src/styles/tokens.test.ts`. Expected: FAIL, `Failed to resolve import "./build-tokens"`.
- [ ] **Step 4: Implement** `medioo.tokens.json` (the table above, every value copied from the prototypes' `:root` blocks) and `build-tokens.ts`: `resolveTokens` resolves `mix` and `alpha` against the same theme and layout (`alpha` emits `rgba(r, g, b, a)` of the resolved hex), and `renderTokensCss` writes tokens in file order, two-space indent, trailing newline. Font tokens: `--font-bn: "Noto Sans Bengali", "Noto Sans", system-ui, sans-serif`; `--font-sys: "Noto Sans", "Noto Sans Bengali", system-ui, sans-serif`; `--font-mono: ui-monospace, Menlo, monospace` (Noto Sans Mono is not self-hosted, so it is not named). Run Step 3's command. Expected: PASS.
- [ ] **Step 5: Write the failing contrast tests** in `src/styles/contrast.test.ts`, over `resolveTokens(json)`:
  - `everyTextTokenClearsFourAndAHalfOnEverySurfaceItIsDeclaredOn`: for each theme × layout, each token with `role: "text"` × each name in its `on`, `contrastRatio(composite(fg, bgOpaque), bgOpaque) >= 4.5`, where a translucent surface is first composited over `bg` of the same theme and layout. The failure message lists `theme/layout fg on bg = ratio`. Guard: at least 40 pairs were computed, and they include `on-now on now` in both themes.
  - `everyNonTextTokenClearsThreeWhereItIsDeclared`: the same with 3.0, for `role: "non-text"` tokens that declare `on`.
  - `contrastRatioMatchesWcagReferenceValues`: `contrastRatio("#d12c24", "#ffffff")` is 5.13 ± 0.01, `contrastRatio("#d93229", "#ffffff")` is 4.73 ± 0.01, `contrastRatio("#000000", "#ffffff")` is 21.
  - `aColourThatFailsIsReported` (the dangerous direction): a copy with `now.light = "#ff6f61"` makes the first test's checker return a failure naming `light/phone on-now on now`.
- [ ] **Step 6:** Run `pnpm -C medioo-web exec vitest run src/styles/contrast.test.ts`. Expected: FAIL (`Failed to resolve import "./contrast"`), then implement `contrast.ts` (WCAG 2.x relative luminance; `composite` is source-over in sRGB) and run again. If a prototype pair fails (expected candidate: none; `label2` on `bg2` is the closest), change the token in `medioo.tokens.json`, never the threshold, and name the change in the PR body. Expected: PASS.
- [ ] **Step 7: Write the failing stylelint tests** in `src/styles/stylelint-bans.test.ts` (both directions, through `stylelint.lint({ code, codeFilename, config })` with the repo config):
  - `aHexColourOutsideTokensIsRefused`, `aNamedColourIsRefused`, `rgbRgbaHslAndColorMixAreRefused`: each reports its rule (`color-no-hex`, `color-named`, `function-disallowed-list`) for `src/components/X/X.css`.
  - `aVarColourIsAccepted`: `.x { color: var(--label); background: var(--bg); }` reports nothing.
  - `theGeneratedTokensFileIsTheOnlyException`: the committed `src/styles/tokens.css` reports nothing, and the same text linted as `src/components/X/X.css` reports `color-no-hex`.
  - `theRealTreeIsClean`: `stylelint.lint({ files: "src/**/*.css" })` reports no errors and linted at least two files (`globals.css`, `tokens.css`).
- [ ] **Step 8:** Run it. Expected: FAIL (`No configuration provided`). Write `.stylelintrc.json`: `color-no-hex: true`, `color-named: "never"`, `function-disallowed-list: ["rgb", "rgba", "hsl", "hsla", "hwb", "lab", "lch", "oklab", "oklch", "color", "color-mix"]`, with `overrides: [{ "files": ["src/styles/tokens.css"], "rules": { "color-no-hex": null, "function-disallowed-list": null } }]`. Rewrite `globals.css` on the new tokens (`--c-bg` → `--bg`, `--c-surface` → `--bg3`, `--c-text` → `--label`, `--c-text-muted` → `--label2`, `--c-line` → `--sep`, `--c-primary` → `--tint-ink` for text and `--tint` for fills). Run again. Expected: PASS.
- [ ] **Step 9: Write the failing NOW-usage checker tests** in `src/styles/now-usage.test.ts`:
  - `aNowVarOutsideTheAllowedComponentsIsReported`: `nowUsageViolations(".day-cell { color: var(--now-ink) }", NOW_ALLOWED)` is `[".day-cell uses --now-ink"]`.
  - `aNowVarInsideTheAllowedComponentsIsAccepted`: `.now-banner`, `.now-banner__call`, `.verdict-card--now .verdict-card__band` and `.status-chip--crit-high` using `var(--now)` or `var(--on-now)` report nothing.
  - `theTokenDefinitionsThemselvesAreNotUses`: a `:root { --now: #d12c24 }` block and `:root[data-theme="dark"] { --now-soft: … }` report nothing.
  - `aSelectorListIsJudgedPerSelector`: `.now-banner, .tab-bar { background: var(--now) }` reports `.tab-bar uses --now`.
  - `aCustomPropertyThatForwardsNowIsAUse`: `.tab-bar { --accent: var(--now) }` reports `.tab-bar uses --now`.
- [ ] **Step 10:** Run it. Expected: FAIL (module missing). Implement `now-usage.ts` with postcss: walk every declaration whose value matches `var\(\s*--(on-)?now`, skip rules whose selector is `:root` or `:root[...]` or `:root:not(...)` (inside any at-rule), split the selector list, and report each selector that `NOW_ALLOWED` does not match. Run again. Expected: PASS.
- [ ] **Step 11: Write the built-CSS test** `src/styles/built/now-usage.built.test.ts`:
  - `theBuiltCssUsesNowOnlyInNowCritAndEmergencyComponents`: read every `*.css` under `.next/static/css/` (fail with `run pnpm build first: no CSS under .next/static/css` if none), concatenate, `expect(nowUsageViolations(css, NOW_ALLOWED)).toEqual([])`.
  - The guard against emptiness at W2a: the built CSS defines `--now:` inside a `:root` block (so the test read the right files). W2c adds the second guard, at least one allowed use (`StatusChip`'s crit states).
- [ ] **Step 12:** Run `pnpm -C medioo-web build && pnpm -C medioo-web run test:built`. Expected: PASS. Then run `pnpm -C medioo-web lint`, `lint:css`, `typecheck`, `test` and `pnpm -C medioo-web e2e`. Expected: all pass, including `reading.spec.ts`'s axe run in both themes on the probe.
- [ ] **Step 13: Red replays.**
  - (a) In `tokens.css`, hand-edit `--now: #d12c24` to `#d93229` in the light block. Expected: `theCommittedTokensCssIsTheRendererOutput` fails with the `cp` line.
  - (b) Set `now-ink.light` to the literal `"#a3201a"`. Expected: `theNowRedIsOneTokenAndEveryNowTokenDerivesFromIt` fails naming `now-ink`.
  - (c) Add `.probe-x { color: var(--now-ink) }` to `globals.css`, build, run `test:built`. Expected: `.probe-x uses --now-ink`. Restore.
  - (d) Give `label3` `role: "text"`. Expected: the contrast test fails naming `light/phone label3 on bg`.
  - (e) Add `color: #000` to `globals.css`. Expected: `theRealTreeIsClean` fails with `color-no-hex`.
- [ ] **Step 14: Commit** (ignore check first: `git status --ignored --short -- medioo-web/src | grep '^!!'` prints nothing):
  ```bash
  git add medioo-web/src/styles/medioo.tokens.json medioo-web/src/styles/build-tokens.ts medioo-web/src/styles/tokens.css \
    medioo-web/src/styles/contrast.ts medioo-web/src/styles/now-usage.ts medioo-web/src/styles/tokens.test.ts \
    medioo-web/src/styles/contrast.test.ts medioo-web/src/styles/stylelint-bans.test.ts medioo-web/src/styles/now-usage.test.ts \
    medioo-web/src/styles/built/now-usage.built.test.ts medioo-web/vitest.built.config.mts medioo-web/vitest.config.mts \
    medioo-web/.stylelintrc.json medioo-web/package.json medioo-web/pnpm-lock.yaml medioo-web/src/app/layout.tsx \
    medioo-web/src/app/globals.css medioo-web/e2e/reading.spec.ts .github/workflows/ci-cd.yml
  git status --short -- medioo-web docs .github   # nothing outside the list
  git commit -m "feat(medioo-web): one token file for phone and desktop, the NOW red in one token, colour literals banned outside it (plan W2a)" \
    -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
  ```
  PR body: the reconciliation table (each divergent token and the value chosen), the contrast table both themes × both layouts, Q1 under "Pending (PO / box)", replays (a)–(e).

## Task W2c: Wordmark, MioAvatar, MessageBubble, StatusChip, and the catalog

Branch `feat/medioo-web-components-c`. Lane C. After W2a.

**Files:**
- Create: `medioo-web/src/components/Wordmark/{Wordmark.tsx,Wordmark.css}`, `medioo-web/src/components/MioAvatar/{MioAvatar.tsx,MioAvatar.css}`, `medioo-web/src/components/MessageBubble/{MessageBubble.tsx,MessageBubble.css}`, `medioo-web/src/components/StatusChip/{StatusChip.tsx,StatusChip.css}`, each with a `*.test.tsx` beside it.
- Create: `medioo-web/public/brand/{medioo-light.svg,medioo-dark.svg,mio.webp,mio-head.webp}`, copied byte for byte from `docs/design/2026-10-21-approved/`.
- Create: `medioo-web/src/app/app/catalog-test/page.tsx` (the catalog; states are registered per component in `medioo-web/src/catalog/states.tsx`), `medioo-web/src/catalog/states.tsx`, `medioo-web/src/catalog/flag.ts` (`export const CATALOG = process.env.MEDIOO_CATALOG === "1"`), `medioo-web/e2e/catalog.spec.ts`, `medioo-web/e2e/catalog-off.spec.ts`, `medioo-web/src/components/brand-assets.test.ts`.
- Create: `baymax/src/test/java/com/elioo/baymax/web/adapter/in/ui/CatalogFixturesExportTest.java`, writing `medioo-web/fixtures/catalog/names.json` (`{"_meta":…, "names": {"short": "মা", "long": "মোছাম্মৎ রোকেয়া বেগম চৌধুরী", "english": "Ma"}}`, fictional) through `Snapshot.matches`.
- Modify: `medioo-web/next.config.ts` (`env: { MEDIOO_CATALOG: process.env.MEDIOO_CATALOG === "1" ? "1" : "0" }`, inlined at build; `distDir: process.env.MEDIOO_CATALOG === "1" ? ".next-catalog" : ".next"`), `medioo-web/e2e/playwright.config.ts` (a third project `catalog`, `testMatch: /catalog\.spec\.ts/`, served from a second `webServer` entry `MEDIOO_CATALOG=1 pnpm exec next start -p <PORT+1>`; the existing two projects `testIgnore: /catalog\.spec\.ts/`), `medioo-web/package.json` (devDependencies `@testing-library/react`, `@testing-library/dom`, `jsdom`, pinned exact as W2a Step 1; script `build:catalog`: `MEDIOO_CATALOG=1 next build`), `medioo-web/vitest.config.mts` (`environmentMatchGlobs: [["src/**/*.test.tsx", "jsdom"]]`), `medioo-web/.gitignore` (`/.next-catalog/`), `medioo-web/src/lint/bangla-literal.test.ts` (line 156: add `"fixtures/catalog/"`), `medioo-web/src/styles/built/now-usage.built.test.ts` (the second guard), `scripts/smoke-https.sh` (row `/app/catalog-test|404|-|-`), `scripts/test_smoke_https.sh` (its fake answers 404 for that path; a break `catalog-200` makes the row FAIL), `.github/workflows/ci-cd.yml` (after the browser tests: `pnpm run build:catalog` then `pnpm exec playwright test -c e2e/playwright.config.ts --project catalog`), `docs/MEDIOO.md` (clear the wordmark asset TODO, DR-41 "Why").

**Interfaces:**
- Consumes: W2a's tokens; `t`, `Lang` from `@/copy`.
- Produces:
  - `Wordmark({ lang }: { lang: Lang }): JSX.Element`: an `<img>` pair inside `<picture>`: `<source srcset="/brand/medioo-dark.svg" media="(prefers-color-scheme: dark)">` and `<img src="/brand/medioo-light.svg" alt="Medioo" width height>`; `[data-theme]` overrides are handled by two `<img>` with `.wordmark--light` / `.wordmark--dark` toggled in CSS, not by script.
  - `MioAvatar({ size, still }: { size: 32 | 48 | 96; still?: boolean })`: `mio-head.webp` for 32 and 48, `mio.webp` for 96; `alt=""` (decorative; the name next to it carries meaning). Motion only as a whole figure; `still` (set by every NOW surface) renders no animation class. Any animation is `animation-iteration-count: 2` at most, and none under `prefers-reduced-motion: reduce`.
  - `MessageBubble({ surface, children, tail? }: { surface: "recv" | "sent"; children: ReactNode; tail?: boolean })`: `.message-bubble--recv` paints `--recv`/`--recv-ink`, `--sent` paints `--sent`/`--sent-ink`.
  - `StatusChip({ kind, lang }: { kind: "in" | "out-high" | "out-low" | "crit-high" | "crit-low" | "unk"; lang: Lang })`: colour + shape + word. Words are the existing keys `PaperDisplay.chipKey` emits, mapped one to one: `in` → `value.chip.inside`, `unk` → `value.chip.unknown`, and the four sided kinds to the keys `chipKey(OUTSIDE|CRITICAL, "high"|"low")` returns (read them from `PaperDisplay.java` and `copy.bn.json` in Step 1; never invent one). Shapes: `in` a circle, `out-*` an arrow up/down, `crit-*` a double arrow, `unk` a question mark, as inline `<svg aria-hidden="true">`. Only `crit-*` use `--now*` (`.status-chip--crit-high`, `.status-chip--crit-low`). `out-*` paint `--label` on `--unk-soft` with the arrow, never amber: out of range is not THIS_WEEK, and amber is reserved for THIS_WEEK (master Global Constraints).
  - The catalog: `src/catalog/states.tsx` exports `STATES: Array<{ component: string; state: string; render: (lang: Lang) => JSX.Element }>`; the page renders each inside `<section data-catalog-state="<component>/<state>">`, in both languages one under the other, and calls `notFound()` first when `!CATALOG`.

- [ ] **Step 1: Write the failing component tests** (Testing Library, `jsdom`):
  - `Wordmark.test.tsx › theWordmarkIsTheVectorMasterForEachTheme`: the light `<img>` src is `/brand/medioo-light.svg`, the dark source is `/brand/medioo-dark.svg`, `alt` is `Medioo`.
  - `brand-assets.test.ts › theServedBrandFilesAreTheApprovedOnesByteForByte`: SHA-256 of each `public/brand/*` equals the file of the same name in `docs/design/2026-10-21-approved/`; the list has exactly four files.
  - `MioAvatar.test.tsx › stillRendersNoAnimation` and `› theFigureIsTheApprovedRaster` (src `/brand/mio-head.webp` at 48, `/brand/mio.webp` at 96).
  - `MessageBubble.test.tsx › theSurfacePicksTheClass`: `recv` → `.message-bubble--recv`, `sent` → `.message-bubble--sent`; children rendered verbatim.
  - `StatusChip.test.tsx › everyKindIsColourShapeAndWord`: for each of the six kinds, the chip has a class `status-chip--<kind>`, an `svg[aria-hidden="true"]` whose `data-shape` is the kind's shape, and text equal to `t(lang, <its key>)` in `bn` and `en`. `› theSixKindsAreTheKindsTheApiSends`: the set of kinds equals the chip keys found in `medioo-web/fixtures/api/documents/*.json` mapped back through the same table, plus any `chipKey` result absent from the fixtures read from `copy.bn.json` keys starting `value.chip.` (derived from the generated copy, not a list typed here).
- [ ] **Step 2:** Run `pnpm -C medioo-web exec vitest run src/components`. Expected: FAIL (modules missing). Implement the four components and their CSS (every selector `.wordmark…`, `.mio-avatar…`, `.message-bubble…`, `.status-chip…`). Run again. Expected: PASS.
- [ ] **Step 3: Write the Java fixture export** `CatalogFixturesExportTest.theCatalogNamesAreTheCommittedFixture` (as `WebCopyExportTest.matches`). Run `gl :baymax:test --tests '*CatalogFixturesExportTest'`. Expected: FAIL with the `cp baymax/build/... medioo-web/fixtures/catalog/names.json` line; run the `cp`, run again, PASS.
- [ ] **Step 4: Write the failing catalog e2e tests.**
  - `catalog-off.spec.ts › theCatalogIs404InAProductionBuild` (runs in the `phone` project against the normal build): `GET /app/catalog-test` is 404 and the body contains no `data-catalog-state`.
  - `catalog.spec.ts` (project `catalog`, both colour schemes, phone and desktop viewports via `test.use`):
    - `everyRegisteredStateRenders`: the count of `[data-catalog-state]` equals `STATES.length × 2` (both languages) and is at least 12 for W2c (Wordmark 1, MioAvatar 3, MessageBubble 2, StatusChip 6).
    - `axeReportsNoViolation` with the probe's guard (passes non-empty; the painted `--bg` is the active scheme's).
    - `noElementOverflowsItsBox`: for every element under `[data-catalog-state]`, `scrollWidth <= clientWidth + 1`; the `long-name` states exist (guard).
    - `noTextUnderTwelvePixels`: every text node's computed `font-size` ≥ 12px.
    - `theWordmarkPaintsTheThemesFile`: in dark, the visible wordmark image's `currentSrc` ends `medioo-dark.svg`; `naturalWidth > 0` in both themes.
    - `noCspViolation`: the page reports no `securitypolicyviolation` event (listener added by `page.addInitScript`).
- [ ] **Step 5:** Run `pnpm -C medioo-web build && pnpm -C medioo-web run build:catalog && pnpm -C medioo-web e2e`. Expected: FAIL (route missing). Implement the page, `states.tsx`, `flag.ts`, `next.config.ts` and the Playwright config. Run again. Expected: PASS.
- [ ] **Step 6:** Add the second guard in `now-usage.built.test.ts`: the built CSS contains at least one allowed use (`.status-chip--crit-high` with `var(--now`). Run `pnpm -C medioo-web run test:built`. Expected: PASS. Add the smoke row and its test; run `bash scripts/test_smoke_https.sh`. Expected: `PASS` for the new row, and the `catalog-200` break FAILs it.
- [ ] **Step 7: Red replays.**
  - (a) Make `flag.ts` return `true`. Expected: `theCatalogIs404InAProductionBuild` fails with `expected 404, received 200`.
  - (b) Paint `.status-chip--out-high` with `var(--now-ink)`. Expected: `test:built` reports `.status-chip--out-high uses --now-ink`.
  - (c) Give StatusChip `unk` no `svg`. Expected: `everyKindIsColourShapeAndWord` fails naming `unk`.
  - (d) Replace `public/brand/medioo-dark.svg` with the light file. Expected: `theServedBrandFilesAreTheApprovedOnesByteForByte` fails naming `medioo-dark.svg`.
- [ ] **Step 8:** Local gates (`lint`, `lint:css`, `typecheck`, `test`, `build`, `test:built`, `e2e`, `gl :baymax:test --tests '*CatalogFixturesExportTest' --tests '*WebCopyExportTest'`), ignore check over `medioo-web/src medioo-web/e2e medioo-web/public medioo-web/fixtures`, publish the board, commit by explicit paths (every file in Files), message `feat(medioo-web): Wordmark, MioAvatar, MessageBubble and StatusChip, with catalog states that exist only in a catalog build (plan W2c)`.

## Task W2d: VerdictCard, NowBanner, Toast, Sheet

Branch `feat/medioo-web-components-d`. Lane C. After W2c.

**Files:**
- Create: `medioo-web/src/components/safety/VerdictCard/{VerdictCard.tsx,VerdictCard.css,VerdictCard.test.tsx}`, `medioo-web/src/components/safety/NowBanner/{NowBanner.tsx,NowBanner.css,NowBanner.test.tsx}`, `medioo-web/src/components/Toast/{Toast.tsx,Toast.css,Toast.test.tsx}`, `medioo-web/src/components/Sheet/{Sheet.tsx,Sheet.css,Sheet.test.tsx}`.
- Create: `medioo-web/src/api/types.ts`: TypeScript types for the paper display model (from `fixtures/api/documents/*.json`) and the B3 banner (`NowBannerJson`, see B3 Interfaces). Until B3 merges, the banner states use a hand fixture under `medioo-web/src/catalog/fixtures/now-banner.*.json` with `_meta.generated_by: "pending B3 NowBannerApiContractTest"`; W2d's PR body names it, and B3 replaces it with its generated fixture.
- Modify: `medioo-web/src/catalog/states.tsx`.

**Interfaces:**
- Consumes: W2a tokens; W2c `MioAvatar`, `StatusChip`; `t`; the paper JSON fields `banner.level`, `banner.line_key`, `now.*_key`, `now.call_999`, `gate`, `wording`, `counts`.
- Produces:
  - `VerdictCard({ paper, lang }: { paper: PaperJson; lang: Lang })`: the band from `paper.banner` (`.verdict-card--now` only when `banner.level === "now"`, `.verdict-card--week` for `this_week`, no band for anything else), the counts line from `counts.line_key`, the wording only when `paper.gate === "RELEASED"` and `paper.wording` is present, and `now.notdoctor_key` on a NOW card. The `tel:999` link (`.verdict-card__call`) only when `paper.now.call_999 === true`.
  - `NowBanner({ banner, patients, lang, onSeen? }: { banner: NowBannerJson; patients: MePatient[]; lang: Lang; onSeen?: () => void })`: renders nothing when `banner.show` is false. Otherwise `role="alert"`, the octagon shape, the patient's name in the title (decision 3: never inside the safety sentence), the `urgency.now` line read verbatim from `banner.line_key`, a link to the first unacked paper, the 999 link only when the viewer is in Bangladesh (`banner.call_999`), and a দেখেছি button. The button is a client island (`NowBannerSeen`, `"use client"`) that sends `PUT /app/api/now-banner/ack` with `banner.seen` exactly as received; W5 wires it. In the catalog it is inert.
  - `Toast({ messageKey, args, lang })`: `role="status"`, never red, auto-dismiss is the caller's.
  - `Sheet({ titleKey, open, children, lang })`: a `<dialog>`-based sheet; focus trapped by the platform; `Escape` closes; no inline style.
- [ ] **Step 1: Write the failing tests:**
  - `VerdictCard.test.tsx`, each over a committed fixture from `fixtures/api/documents/`:
    - `wordingOnlyWhenReleased`: `routine-released.json` shows its wording; `this-week-pending.json` shows none, and no element has the class `verdict-card__wording`.
    - `noNineNineNineAbroad`: `now-abroad.json` has no `a[href^="tel:"]`; `now-bd.json` has exactly one, `tel:999`.
    - `routineHasNoBand`: `routine-released.json` has no `.verdict-card--now` and no `.verdict-card--week`.
    - `aNowCardCarriesNotDoctor`: `now-bd.json` and `now-abroad.json` contain `t(lang, "now.notdoctor")`.
    - `theCardNeverDerivesUrgency` (the dangerous direction): a copy of `routine-released.json` with every value's chip set to a crit kind still renders no `.verdict-card--now`; the band reads `banner.level` only.
  - `NowBanner.test.tsx`:
    - `hiddenWhenShowIsFalse`: renders `null`.
    - `theSafetyLineIsReadVerbatimAndTheNameIsInTheTitle`: the alert's sentence equals `t(lang, "urgency.now")` exactly, and the patient's name appears only in the title element.
    - `the999LinkFollowsTheViewer`: `call_999: false` → no `tel:` link.
    - `theSeenButtonSendsWhatWasReceived`: clicking দেখেছি in the client island calls `fetch` once with method `PUT`, path `/app/api/now-banner/ack`, headers `Content-Type: application/json` and `X-Medioo: 1`, and a body deep-equal to `{"seen": banner.seen}`.
    - `mioIsStillOnNow`: any `MioAvatar` inside has `still`.
  - `Toast.test.tsx › aToastIsNeverRed`: no class of `Toast` matches `NOW_ALLOWED`; `role="status"`.
  - `Sheet.test.tsx › escapeCloses` and `› theTitleIsTheDialogsName` (`aria-labelledby`).
- [ ] **Step 2:** Run `pnpm -C medioo-web exec vitest run src/components`. Expected: FAIL (modules missing). Implement. Run again. Expected: PASS.
- [ ] **Step 3:** Register catalog states: VerdictCard over every `fixtures/api/documents/*.json` (derived: one state per file, so a new fixture gets a state), NowBanner `one-patient`, `two-patients`, `long-name`, `abroad`, `hidden`; Toast `default`; Sheet `open`. Run `pnpm -C medioo-web run build:catalog && pnpm -C medioo-web e2e --project catalog`. Expected: PASS, with W2c's catalog assertions now covering these states; `everyRegisteredStateRenders` minimum raised to the derived count.
- [ ] **Step 4:** `pnpm -C medioo-web build && pnpm -C medioo-web run test:built`. Expected: PASS: `.now-banner` and `.verdict-card--now` are allowed uses, nothing else uses `--now*`.
- [ ] **Step 5: Red replays.** (a) Render wording when `gate === "PENDING"`: `wordingOnlyWhenReleased` fails naming `this-week-pending`. (b) Render the 999 link unconditionally: `noNineNineNineAbroad` fails, `expected 0 tel: links, found 1`. (c) Put the name inside the sentence: `theSafetyLineIsReadVerbatim…` fails with the two strings. (d) Give `Toast` the class `now-banner`: `aToastIsNeverRed` fails.
- [ ] **Step 6:** Local gates, ignore check, board, commit `feat(medioo-web): VerdictCard and NowBanner render only what Java sent; Toast and Sheet (plan W2d)`.

## Task W2e: OtpInput, PhoneField, MonthCalendar, TrendChart

Branch `feat/medioo-web-components-e`. Lane C. After W2c, parallel with W2d.

**Files:**
- Create: `medioo-web/src/components/OtpInput/*`, `medioo-web/src/components/PhoneField/*`, `medioo-web/src/components/MonthCalendar/*`, `medioo-web/src/components/TrendChart/*` (each `.tsx`, `.css`, `.test.tsx`).
- Modify: `medioo-web/src/catalog/states.tsx`; `baymax/src/main/resources/baymax/ui-papers_{bn,en}.properties` (draft keys below); `medioo-web/src/copy/generated/copy.{bn,en}.json` (regenerated by `WebCopyExportTest`).

**Draft copy keys (Q4)**, in `ui-papers_*`:
- `calendar.legend.paper` = bn "কাগজ" / en "Paper"; `calendar.legend.follow` = bn "ডাক্তারের দেওয়া তারিখ" / en "Doctor's date"; `calendar.legend.now` = bn "এখনই দেখার" / en "Needs attention now";
- `calendar.day.status` = bn "{0}: {1}টি কাগজ, {2}টি ডাক্তারের তারিখ" / en "{0}: {1} papers, {2} doctor's dates".

**Interfaces:**
- Consumes: `formatDate`, `formatMonthYear`, `banglaDigits`, `t` from `@/copy`; W2c `StatusChip`.
- Produces:
  - `OtpInput({ length, lang, name }: { length: 6; lang: Lang; name: string })`: one `<input inputmode="numeric" autocomplete="one-time-code" maxlength="6" pattern="[0-9০-৯]{6}">` (no six boxes: one field reads correctly to a screen reader and pastes in one go). The label key is the existing sign-in key the HTML page uses (read `SignInPages.java` in Step 1).
  - `PhoneField({ lang, name })`: `<input type="tel" inputmode="tel" autocomplete="tel">` with the existing sign-in label and hint keys. Normalisation stays in Java (`PhoneDisplay.normalise`); nothing is parsed here.
  - `MonthCalendar({ month, days, lang }: { month: string /* YYYY-MM */; days: CalendarDayJson[]; lang: Lang })`: a `<table role="grid">` with weekday headers, each day cell carrying `aria-label = t(lang, "calendar.day.status", formatDate(lang, date), papers, followUps)`; a NOW paper's day carries the `crit` shape plus the word from `calendar.legend.now`; the legend lists the three keys with their shapes. The data shape is B3's `GET /app/api/patients/{id}/calendar` body (`CalendarDayJson` in `src/api/types.ts`); until B3 merges, the catalog uses a hand fixture marked as W2d's is.
  - `TrendChart({ points, lang }: { points: TrendPointJson[]; lang: Lang })`: an SVG line, no inline style, coloured by tokens via classes. Fewer than two points renders `null` (one reading is a sentence on the স্বাস্থ্য screen, W9, not a chart). Each point's marker is the point's `kind` shape (B6's `kind`); no band is drawn for a point whose `kind` is `unknown`.
- [ ] **Step 1: Write the failing tests:** `OtpInput.test.tsx › oneFieldForSixDigitsWithOneTimeCode`; `PhoneField.test.tsx › aTelFieldAndNoParsing` (typing `+880 1700-000001` leaves the value as typed); `MonthCalendar.test.tsx › everyDayHasAScreenReaderStatus` (31 cells for `2026-10`, each `aria-label` built from `calendar.day.status` with Bangla digits in `bn`), `› theFirstWeekdayCellIsTheMonthsFirstDay` (1 October 2026 is a Thursday: it sits in the Thursday column), `› aNowDayIsShapeAndWordNotOnlyColour`, `› theLegendNamesAllThreeMarks`; `TrendChart.test.tsx › oneReadingIsNoChart` (`null` for one point), `› twoReadingsDrawOneLine`, `› anUnknownPointHasNoBand`.
- [ ] **Step 2:** Add the draft keys; run `gl :baymax:test --tests '*WebCopyExportTest' --tests '*BundleFilesTest' --tests '*UiCopyTest'`, apply the printed `cp` for `copy.bn.json` and `copy.en.json`, run again. Expected: PASS. Run `pnpm -C medioo-web exec vitest run src/components`. Expected: FAIL (modules missing). Implement. Run again. Expected: PASS.
- [ ] **Step 3:** Catalog states: OtpInput `empty`, `filled`; PhoneField `empty`, `error` (the existing `INVALID_NUMBER` copy key); MonthCalendar `empty-month`, `busy-month` (a follow-up and two papers on one day, a NOW day), `long-name`; TrendChart `one-point` (renders the empty state of the section), `rising`, `with-unknown`. Run the catalog e2e. Expected: PASS (axe, overflow, 12 px, CSP).
- [ ] **Step 4: Red replays.** (a) Drop the `aria-label` on day cells: `everyDayHasAScreenReaderStatus` fails naming the first day. (b) Render a chart for one point: `oneReadingIsNoChart` fails. (c) Start the week on the wrong day: `theFirstWeekdayCellIsTheMonthsFirstDay` fails with the column found.
- [ ] **Step 5:** Local gates, ignore check, board, commit `feat(medioo-web): OtpInput, PhoneField, MonthCalendar with a screen-reader day status, TrendChart (plan W2e)`. The PR body lists the four draft keys under "Pending (PO / copy owner)".

---

## Task B3: Attention stack, timeline by month, calendar, follow-ups, and দেখেছি

Branch `feat/baymax-web-api-attention-timeline-ack`. Lane B. Starts now; merges only when V23 is on `origin/main` (Global Constraints) and Q2 is ruled or its default stands.

**Files:**
- Create migration `baymax/src/main/resources/db/baymax/migration/V<n>__now_ack.sql`:
  ```sql
  -- DR-41 item 5: দেখেছি hides the global NOW banner for the account holder who tapped, for exactly what they saw.
  -- It is a display preference, never a change to the record: nothing here touches document, observation or outbound_message.
  ALTER TABLE ${flyway:defaultSchema}.document ADD CONSTRAINT document_id_patient_key UNIQUE (id, patient_id);
  CREATE TABLE ${flyway:defaultSchema}.now_ack (
      family_id           uuid        NOT NULL REFERENCES ${flyway:defaultSchema}.family_account (id),
      patient_id          uuid        NOT NULL REFERENCES ${flyway:defaultSchema}.patient_profile (id),
      document_id         uuid        NOT NULL,
      urgency_assessed_at timestamptz NOT NULL,
      release_state       varchar(16) NOT NULL CHECK (release_state IN ('none', 'pending', 'released', 'rejected', 'failed_safety')),
      acked_at            timestamptz NOT NULL DEFAULT now(),
      PRIMARY KEY (family_id, document_id, urgency_assessed_at, release_state),
      FOREIGN KEY (document_id, patient_id) REFERENCES ${flyway:defaultSchema}.document (id, patient_id)
  );
  CREATE INDEX now_ack_patient_idx ON ${flyway:defaultSchema}.now_ack (patient_id);
  CREATE INDEX now_ack_document_idx ON ${flyway:defaultSchema}.now_ack (document_id);
  ```
  with `COMMENT ON` lines: `family_id` is "the account holder who tapped: the viewer's own family_account (a share member's, for a shared patient), never the patient's family"; the composite key "makes a row name exactly what was seen; a raise, a new assessment or a release is a different key, so the banner shows again". No cascade on any key, so a delete that forgets this table fails loudly.
- Create: `web/domain/NowSeen.java`, `web/domain/NowBanner.java`, `web/domain/CalendarMonth.java`, `web/application/port/in/NowBannerUseCase.java`, `web/application/port/out/NowAckPort.java`, `web/application/service/NowBannerService.java`, `web/adapter/out/persistence/PostgresNowAckAdapter.java`, `web/adapter/in/api/NowBannerApiHandler.java`, `web/adapter/in/api/AttentionApiHandler.java`, `web/adapter/in/api/PatientTimelineApiHandler.java`, `web/adapter/in/api/TimelineJson.java` (all under `baymax/src/main/java/com/elioo/baymax/`).
- Modify: `web/application/port/in/TimelineUseCase.java` and `web/application/service/TimelineService.java` (`calendar`), `web/application/port/in/AttentionUseCase.java` and `web/application/service/AttentionService.java` (`followUps`), `web/application/port/out/TimelineReadPort.java` and `web/adapter/out/persistence/PostgresTimelineReadAdapter.java` (`month`), `healthrecord/adapter/out/persistence/PostgresHealthRecordAdapter.java` (`deleteFamily`: `DELETE FROM now_ack WHERE family_id = :family OR patient_id IN <patientsOf>` before `document`; `deletePatient`: `WHERE patient_id = :patient` before `document`), `extraction/adapter/out/persistence/PostgresDocumentRecordAdapter.java` (`deleteDocument`: `DELETE FROM now_ack WHERE document_id = :d` before the `document` delete), `AppApiRouter.java`, `ApiReason.java` (nothing new unless needed), `docs/infra/app-routes.txt`.
- Tests (under `baymax/src/test/java/com/elioo/baymax/`): create `web/adapter/in/api/NowBannerApiTest.java`, `web/adapter/in/api/AttentionApiContractTest.java`, `web/adapter/in/api/PatientTimelineApiContractTest.java`, `web/adapter/out/persistence/NowAckAdapterTest.java`, `testsupport/NowPapers.java`, `testsupport/FamilySweep.java`; modify `family/FamilyDeletionAcceptanceTest.java` (seed one `now_ack` row by the owner and one by a share member's family; delegate to `FamilySweep`), `web/adapter/in/api/PaperDeleteApiTest.java` (seed one `now_ack` row before the delete, comment why), `web/adapter/in/api/AppApiLogSafetyTest.java` (exercise every new route), `web/adapter/in/api/AppApiGuardTest.java` (constructor), `web/adapter/out/persistence/TimelineReadModelTest.java` (`month`), `web/application/service/AttentionServiceTest.java`; fixtures under `medioo-web/fixtures/api/{now-banner,attention,timeline,calendar,follow-ups}/`.

**Interfaces:**
- Consumes: `AttentionUseCase.attention/urgent`, `TimelineUseCase.timeline/me`, `HealthRecordPort.visiblePatients/visiblePatient`, `NudgeDataPort.openFollowUpsOfPatient`, `PatientCalendar`, `ImageRefs`, `AttentionService.WINDOW` (30 days), `ViewerLocale` (for `call_999`, as `PaperJson` decides it: read `ResultService.viewer` and reuse it, never a second rule).
- Produces:
  - `record NowSeen(UUID documentId, UUID patientId, Instant urgencyAssessedAt, String releaseState)`; `releaseState` is the latest explanation's `gate_status` with `approved` collapsed to `released`, and `none` when there is no explanation.
  - `record NowBanner(List<Item> documents, boolean call999)` with `record Item(NowSeen seen, String documentType, LocalDate date, boolean dateIsUploadDay, boolean acked)` and `boolean show()` = any item not acked.
  - `NowBannerUseCase`: `Mono<NowBanner> banner(UUID viewerFamilyId)`; `Mono<NowBanner> acknowledge(UUID viewerFamilyId, List<NowSeen> seen)`. The banner's documents are the NOW papers of every visible patient inside `AttentionService.WINDOW` (the stack's own rule, so the banner and the stack never disagree). `acknowledge` records only the entries that equal a current item exactly (id, assessed instant, release state); any other entry is ignored, so a tap on a banner that changed after it was drawn acknowledges nothing new. It writes nothing but `now_ack`.
  - `NowAckPort`: `Flux<NowSeen> nowOf(UUID patientId, Instant since)` (DONE, `urgency = 'now'`, `created_at >= since`; the same window and filter as `PostgresTimelineReadAdapter.ATTENTION`); `Flux<NowSeen> ackedBy(UUID viewerFamilyId, Collection<UUID> documentIds)`; `Mono<Long> record(UUID viewerFamilyId, List<NowSeen> seen, Instant at)` (`INSERT … ON CONFLICT DO NOTHING`).
  - `TimelineReadPort.month(UUID patientId, LocalDate first, LocalDate last)` → `Flux<TimelineEntry>`, `timeline_date BETWEEN :first AND :last`, the page's order.
  - `TimelineUseCase.calendar(UUID familyId, UUID patientId, YearMonth month)` → `Mono<CalendarMonth>`, `record CalendarMonth(YearMonth month, List<TimelineEntry> papers, List<AttentionItem.FollowUp> followUps)`; follow-ups are `openFollowUpsOfPatient(patient, first, last)`. Records a `TIMELINE_VIEW` audit row, as `timeline` does.
  - `AttentionUseCase.followUps(UUID familyId, UUID patientId)` → `Mono<List<AttentionItem.FollowUp>>`, open, due in [today − 30, today + 365] on the patient's calendar, soonest first.
  - JSON:
    - `GET /app/api/now-banner` and the `PUT …/ack` answer: `{"show": bool, "line_key": "urgency.now", "call_999": bool, "documents": [{"document_id", "patient_id", "document_type", "date", "date_is_upload_day", "acked": bool}], "seen": [{"document_id", "urgency_assessed_at", "release_state"}]}`. `seen` lists every item, acked or not; the client sends it back unchanged.
    - `PUT /app/api/now-banner/ack` body `{"seen": [...]}`.
    - `GET /app/api/patients/{id}/attention`: `{"items": [...]}` in `AttentionItem.ORDER`, each `{"kind": "urgent", "document_id", "level": "now"|"this_week", "document_type", "date", "date_is_upload_day", "received_at"}`, `{"kind": "retake", "document_id", "received_at", "title_key": "attn.retake.title"}`, `{"kind": "follow_up", "document_id", "instruction", "due_date", "crop": {"src"}, "title_key": "attn.follow.title"}` or `{"kind": "reading", "document_id", "received_at", "title_key": "attn.reading.title"}`; every card has `"action_key": "attn.open"`.
    - `GET /app/api/patients/{id}/timeline`: `{"entries": [{"document_id", "month": "YYYY-MM", "date", "date_is_upload_day", "document_type", "facility", "status", "chip": "now"|"this_week"|null, "counts": {"values", "outside", "medicines", "follow_ups", "unverified"}, "review_pending": bool, "retake_reason_key": string|null, "page_one": {"src"}}], "next_cursor": string|null}`. `month` is `YearMonth.from(entry.date())`, Java's, so React groups by equal keys and never computes a month.
    - `GET /app/api/patients/{id}/calendar?month=YYYY-MM`: `{"month", "days": [{"date", "papers": [{"document_id", "document_type", "chip"}], "follow_ups": [{"document_id", "instruction", "crop": {"src"}}]}]}`, only days with something, ascending.
    - `GET /app/api/patients/{id}/follow-ups`: `{"items": [{"document_id", "instruction", "due_date", "overdue": bool, "crop": {"src"}}]}`; `overdue` is `due_date < PatientCalendar.today(clock)`.

- [ ] **Step 1: Write the failing persistence tests** in `NowAckAdapterTest` (Testcontainers, `PostgresNudgeAdapterTest.runnerFor`):
  - `theNowSetIsTheAttentionStacksNowRows`: a DONE `now` paper inside the window, a DONE `now` paper uploaded 31 days ago, a DONE `this_week` paper and a PROCESSING paper: `nowOf` returns exactly the first.
  - `theReleaseStateIsTheLatestExplanationCollapsed`: explanations `pending` then `approved` (later `created_at`) → `released`; none → `none`.
  - `aRowMustNameThePatientOfItsDocument`: inserting a `now_ack` row whose `patient_id` is another patient's fails with `violates foreign key constraint "now_ack_document_id_patient_id_fkey"` (read the generated name in Step 2 and assert it exactly).
  - `recordingTwiceIsOneRow`: the same `NowSeen` recorded twice leaves one row and returns 1 then 0.
- [ ] **Step 2:** Run `gl :baymax:test --tests '*NowAckAdapterTest'`. Expected: FAIL (`cannot find symbol NowAckPort`). Write the migration (number per Global Constraints) and `PostgresNowAckAdapter`. Run again. Expected: PASS.
- [ ] **Step 3: Write the two-direction ack tests first**, in `NowBannerApiTest` (Testcontainers Postgres through `AppApiContext.runner(postgres)` plus a `@Primary` recording `MessageDeliveryPort`, as `PaperDeleteApiTest` builds it; family A `+8801700000501` owns patient Ma; family B `+8801700000502` is a share member of Ma through `AppApiContext.share`). Each test GETs the banner, PUTs `seen` exactly as received, then asserts:
  - **Hides, exactly:**
    - `anAckHidesTheBannerForTheDocumentsAcked`: one NOW paper; after the ack, `show == false` and the item is `acked: true`.
    - `anAckHidesNothingItDidNotSee`: two NOW papers; the PUT sends only the first's `seen` entry; `show == true`, the second is `acked: false`.
    - `anAckHoldsAcrossThatAccountsDevices`: A's session 1 acks; A's session 2 (a second `AppApiContext.session`) gets `show == false`.
  - **Changes nothing else** (`anAckNeverChangesTheRecordTheNowBlockOrWhatsApp`): before and after the ack, `select urgency, urgency_reasons::text, urgency_assessed_at from baymax.document where id = :id` is equal; `select id, gate_status, sent_at from baymax.outbound_message where document_id = :id order by id` is equal; `GET /app/api/documents/{id}` has `$.banner.level == "now"` and `$.now.notdoctor_key == "now.notdoctor"`; `GET /app/api/me` has `$.patients[0].urgent == true` (the red mark stays); a `pending` explanation seeded before the ack and approved after it with `ReviewGateUseCase.approve(id, "tanvir")` is delivered once by the recording port.
  - **Shows again** (each acks first, then changes the paper, then GETs):
    - `aNewNowUploadShowsTheBannerAgain`: a second NOW paper → `show == true`, the new one `acked: false`, the old one `acked: true`.
    - `aThisWeekToNowRaiseShowsTheBannerAgain`: a `this_week` paper (no banner, `show == false`), raised by SQL to `now` with a reason `value_critical:k|stopgap` and a new `urgency_assessed_at` (the V18 trigger allows a raise) → `show == true`.
    - `aFirstAssessmentBySweepShowsTheBannerAgain`: a DONE paper with `urgency` NULL and `NowPapers.criticalValue`, acked while the banner shows only an older NOW paper; `DocumentUrgencyUseCase.sweep()` assesses it → it is `now` and `show == true`.
    - `aReassessmentOfANowPaperShowsTheBannerAgain`: ack a NOW paper; `DocumentUrgencyUseCase.assessAndStore(id)` stamps a new `urgency_assessed_at` at the same level → `show == true`.
    - `aLaterGateReleaseShowsTheBannerAgain` (Review Focus 1): a NOW paper whose explanation is `pending`; ack; `ReviewGateUseCase.approve` → `release_state` is `released` and `show == true`.
  - **Per account holder** (Q2, Review Focus 2):
    - `afterTheOwnerAcksTheShareMemberStillSeesTheBanner`: A acks; B's session gets `show == true`.
    - `afterTheShareMemberAcksTheOwnerStillSeesTheBanner`: B acks; A's session gets `show == true`.
  - **Refusals:** `aStaleSeenSetAcknowledgesNothingNew`: GET, then raise or re-assess the paper, then PUT the old `seen` → `show == true` and `now_ack` holds no row with the new instant. `aMalformedAckIs400Invalid`: `{"seen": "x"}` → 400 `{"reason":"INVALID"}`. `anotherFamilysDocumentInSeenIsIgnored`: a `seen` entry naming a paper of a third family adds no row.
  - **The banner reaches abroad viewers without 999:** `aViewerAbroadGetsNoCall999`: a family `+447700900001` sharing Ma gets `call_999 == false`; A gets `true`.
- [ ] **Step 4:** Run `gl :baymax:test --tests '*NowBannerApiTest'`. Expected: FAIL, 404 `NOT_FOUND` from the catch-all on every test (the routes are missing). Implement `NowBannerService`, `NowBannerApiHandler` and the two routes. Run again. Expected: PASS.
- [ ] **Step 5: Write the failing contract tests** with their MSW fixtures:
  - `AttentionApiContractTest`: `theStackIsAttentionUseCasesOrder` (a NOW, a THIS_WEEK, a retake, a follow-up due in 3 days and a paper being read → kinds in that order); `routineHasNoCard`; `anotherFamilysPatientIsNotFound` (404 `NOT_FOUND`, no audit row); `everyEmittedKeyResolvesInBothLanguages` (every `*_key` in every fixture is in `UiCopy.keys(Lang.BN)` and `UiCopy.keys(Lang.EN)` or the `urgency.*` keys of `messages_*`); `eachCaseIsItsCommittedMswFixture` and `everyCommittedFixtureIsACurrentCase` (cases `empty`, `full-stack`, `now-only`).
  - `PatientTimelineApiContractTest`:
    - `theMonthKeyIsTheTimelineDatesMonth`: entries dated 2026-10-01, 2026-09-30 and an undated one uploaded `2026-09-30T18:30:00Z` (Dhaka 1 October) → months `2026-10`, `2026-10`, `2026-09` in timeline order.
    - `theTimelinePagesWithTheReadModelsCursor`: 25 papers → 20 entries and a `next_cursor`; the second page has 5 and `next_cursor` null; no `document_id` repeats.
    - `theCalendarHoldsTheMonthsPapersAndFollowUps`: papers on 2026-10-01 and 2026-10-31, one on 2026-11-01, a follow-up due 2026-10-15 → `days` are exactly 01, 15 and 31; `month=2026-13` → 400 `INVALID`.
    - `followUpsAreSoonestFirstWithJavasOverdue`: the clock fixed at `2026-10-22T04:00:00Z`; due 2026-10-21 → `overdue: true`, 2026-10-22 → `false`.
    - `aChipIsOnlyNowOrThisWeek`: a routine and an unassessed paper have `chip: null`.
    - `noConfidenceModelOrDeveloperProseAnywhere`: walking every fixture with `Json.walk`, no field is `confidence`, `model`, `status_reason`, and no value equals the seeded developer prose `"developer prose"`.
    - fixtures `timeline/{first-page,last-page,empty}.json`, `calendar/{busy,empty}.json`, `follow-ups/{some,none}.json`, `now-banner/{one,two-patients,acked,abroad}.json` (from `NowBannerApiTest`'s cases, written by a `NowBannerApiContractTest` nested class or method in the same file).
- [ ] **Step 6:** Run `gl :baymax:test --tests '*AttentionApiContractTest' --tests '*PatientTimelineApiContractTest'`. Expected: FAIL (404 from the catch-all), then implement the handlers, `TimelineJson`, `TimelineReadPort.month`, `TimelineUseCase.calendar` and `AttentionUseCase.followUps`; apply each printed fixture `cp`; run again. Expected: PASS.
- [ ] **Step 7: Deletion.** Seed `now_ack` in `FamilyDeletionAcceptanceTest.seed` (one row by the owner family for docA, one by a share member's family: create the family `+8801811111111` with `HealthRecordPort.createFamily`, so the share member is a real account holder) and in `PaperDeleteApiTest.deletingAPaperLeavesNoRowInAnyTableThatNamesIt` (one row by the owner). Extract `FamilySweep` from `FamilyDeletionAcceptanceTest` first (no behaviour change; run it green). Then run `gl :baymax:test --tests '*FamilyDeletionAcceptanceTest' --tests '*PaperDeleteApiTest'` **before** adding the deletes. Expected: FAIL, the delete answers 500 with `violates foreign key constraint "now_ack_family_id_fkey"` (family), `"now_ack_document_id_patient_id_fkey"` (patient and paper). Add the three deletes. Run again. Expected: PASS, and `emptyBefore` lists no `now_ack` column.
- [ ] **Step 8:** Add every new route to `AppApiLogSafetyTest`'s exercise map (the ack with a real `seen` set, asserting 200; `calendar` with a fictional follow-up instruction `Return-for-review-6613` as a needle that must appear in no log line at INFO or above). Fix `AppApiGuardTest`. Regenerate `docs/infra/app-routes.txt` from `AppApiRouteContractTest`'s printed `cp`; `git diff origin/main -- docs/infra/app-routes.txt | grep '^-[A-Z*]'` prints nothing. Run `gl :baymax:test --tests '*AppApi*'`. Expected: PASS.
- [ ] **Step 9: Red replays.**
  - (a) Key `acknowledge` on `document_id` only (drop the instant and state from the match). Expected: `aThisWeekToNowRaiseShowsTheBannerAgain`, `aReassessmentOfANowPaperShowsTheBannerAgain` and `aLaterGateReleaseShowsTheBannerAgain` fail with `expected: true but was: false` on `$.show`.
  - (b) Store and look up acks by the patient's `family_id` instead of the viewer's. Expected: `afterTheOwnerAcksTheShareMemberStillSeesTheBanner` fails with `expected: true but was: false`.
  - (c) Make `acknowledge` also run `UPDATE baymax.document SET urgency_assessed_at = now() WHERE id = :id` (a write the V18 trigger allows). Expected: `anAckNeverChangesTheRecordTheNowBlockOrWhatsApp` fails on the document row, naming `urgency_assessed_at`.
  - (d) Drop the `now_ack` delete from `deleteDocument`. Expected: `PaperDeleteApiTest` fails on `now_ack_document_id_patient_id_fkey`.
  - (e) Use `timeline_date >= :since` in `nowOf`. Expected: a `NowAckAdapterTest` case added for it, `aNowPaperUploadedTodayWithAnOldPrintedDateIsInTheBanner` (printed 2026-01-05, uploaded inside the window), fails with `expected [<id>] but was []`. Write that test in Step 1; it passes on the correct query and is the guard for this replay.
- [ ] **Step 10:** `gl build`, ignore check over `baymax/src medioo-web/fixtures`, confirm V23 is on `origin/main`, commit by explicit paths (every file in Files and each fixture by name), message `feat(baymax-web): attention, timeline by month, calendar and follow-ups JSON; দেখেছি per account holder, keyed to what was seen (plan B3)`. The PR body: Q2 and the DR-41 reference, the replays, and that the দেখেছি label key is W5's (Q4).

## Task B5: Family JSON — flags, consent, reminders, language, delete

Branch `feat/baymax-web-api-family`. Lane B. Parallel with B3 and B6. No migration. E1/E2 are out.

**Files:**
- Create: `web/adapter/in/api/FamilyApiHandler.java`, `web/adapter/in/api/FamilyJson.java`.
- Modify: `web/adapter/in/api/DeleteConfirmation.java`: add `enum Purpose { PAPER("paper-delete:v1|"), FAMILY("family-delete:v1|") }`, `Token mint(UUID sessionId, UUID subjectId, Purpose purpose)` and `boolean valid(String token, UUID sessionId, UUID subjectId, Purpose purpose)`; the existing two-argument methods delegate with `PAPER`, so every caller and `DeleteConfirmationTest` are unchanged.
- Modify: `nudge/application/port/in/NudgeOptOutUseCase.java`, `nudge/application/service/NudgeOptOutService.java`, `nudge/application/port/out/NudgeOptOutPort.java`, `nudge/adapter/out/persistence/PostgresNudgeDataAdapter.java` (add `optInPatient(UUID)` and `optInFamily(UUID)`: `SET nudges_opted_out_at = NULL`).
- Modify: `ApiReason.java` (`Map.entry("not_owner", VIEW_ONLY)`), `AppApiRouter.java`, `docs/infra/app-routes.txt`.
- Tests: create `web/adapter/in/api/FamilyApiContractTest.java`, `web/adapter/in/api/FamilyDeleteApiTest.java`, `nudge/adapter/out/persistence/OptInTest.java`; modify `DeleteConfirmationTest.java` (two purposes), `AppApiLogSafetyTest.java`, `AppApiGuardTest.java`; fixtures `medioo-web/fixtures/api/family/{owner,share-member,reminders-off}.json`.

**Interfaces:**
- Consumes: `TimelineUseCase.me` (the visible patients and the owner flag), `TimelineUseCase.updateChronicFlags` (owner only, `not_owner`), `NudgeDataPort.patient(UUID)` (`PatientRef.patientOptedOutAt/familyOptedOutAt`), `NudgeOptOutUseCase`, `FamilyAccountService.deleteFamily` (rows first, then objects, the orphan logged), `ProfilePages.FLAGS` (move it to `web/domain/ChronicFlags.java` as `public static final List<String> OFFERED`, and make `ProfilePages` and `FamilyView` read it: one list), `PhoneDisplay` (the number as displayed today on the পরিবার page), `Lang.cookie(boolean)`, `FamilyDeletionAcceptanceTest`'s derivation through `FamilySweep` (created here if B3 has not landed).
- Produces:
  - `GET /app/api/family` → `{"account": {"owner_name", "number": <PhoneDisplay of the family's own number>, "terms_date", "language": "bn"|"en", "reminders_stopped": bool}, "patients": [{"id", "name", "name_possessive", "age", "sex_key", "owner": bool, "chronic_flags": [...], "consent_date", "reminders": "on"|"stopped_for_patient"|"stopped_for_family"}], "flags_offered": ChronicFlags.OFFERED}`. The number appears here and nowhere else in `/app/api` (as `FamilyView` today); it never reaches a log. A share member sees their own account and the shared patient with `owner: false`.
  - `PUT /app/api/patients/{id}/flags` `{"chronic_flags": [...]}` → 200 the patient object; 403 `VIEW_ONLY` for a share member; 404 for an unseen patient.
  - `PUT /app/api/patients/{id}/reminders` `{"on": bool}` → `optOutPatient` or `optInPatient`; owner only (403 `VIEW_ONLY`). Opting the patient back in never clears a family stop; the answer then says `stopped_for_family`.
  - `PUT /app/api/family/reminders` `{"on": bool}` → `optOutFamily` or `optInFamily` on the session's own family.
  - `PUT /app/api/language` `{"lang": "bn"|"en"}` → 204 with `Set-Cookie` from `Lang.parse(lang).cookie(secure)`; anything else is `bn` (as `Lang.parse`).
  - `GET /app/api/family/delete-token` → `{"confirm_token", "expires_at"}` (purpose `FAMILY`, subject the session's family id); `DELETE /app/api/family` `{"confirm_token"}` → 200 `{"patients", "documents", "objects"}` from the receipt, with the session cookie cleared (`Max-Age=0`, `Path=/app`); 400 `CONFIRM_REQUIRED` for a missing, foreign, tampered, expired or `PAPER`-purpose token.
- [ ] **Step 1: Write the failing tests.**
  - `OptInTest` (Testcontainers): `optInClearsOnlyThePatientsStop` (patient and family both stopped; `optInPatient` → patient NULL, family still set); `optInFamilyClearsOnlyTheFamilysStop`; `anOptOutAfterAnOptInStampsANewInstant`.
  - `DeleteConfirmationTest`: `aTokenOfOnePurposeNeverOpensTheOther` (a `FAMILY` token for subject S is invalid as `PAPER` for S, and the reverse); the existing tests unchanged and green.
  - `FamilyApiContractTest`: `theOwnerSeesFlagsConsentRemindersAndLanguage` (field by field, against the seeded profile); `aShareMemberSeesTheSharedPatientReadOnly` (`owner: false`; their PUT flags and PUT reminders → 403 `VIEW_ONLY`, and the stored flags and `nudges_opted_out_at` are unchanged); `optingBackInIsHonestAboutAFamilyStop` (family stopped; PUT patient reminders `on: true` → `reminders: "stopped_for_family"`); `languageSetsOnlyTheCookie` (`baymax_lang=en`, `Path=/`, no other `Set-Cookie`; an unknown value sets `bn`); `theNumberIsHereAndInNoOtherApiBody` (derived: walk every committed fixture under `medioo-web/fixtures/api/`, the family's fictional number appears only in `family/*.json`, guarded non-empty); fixtures and `everyCommittedFixtureIsACurrentCase`.
  - `FamilyDeleteApiTest` (Testcontainers Postgres and MinIO, `AppApiContext.runner(postgres, minio)`):
    - `deletingTheFamilyThroughTheApiLeavesNothingInAnyTableTheSchemaNames`: seed the family's whole graph as `FamilyDeletionAcceptanceTest.seed` does (a paper through `POST /app/add`, a share member, an `outbound_message`, a `nudge`, a second session, and a `now_ack` row once B3 has landed); `FamilySweep.emptyBefore(targets, fixture)` is empty (the guard); mint a token, DELETE → 200 and the cookie cleared; `FamilySweep.stillHolding` is empty; the bucket under `<family>/` is empty; the next `GET /app/api/me` with the same cookie is 401 `UNAUTHENTICATED`.
    - `aDeleteWithoutAValidTokenTouchesNothing`: no token, a `PAPER` token, another session's token → 400 `CONFIRM_REQUIRED`, row counts in every swept column unchanged.
    - `aShareMemberDeletesOnlyTheirOwnAccount`: B (a share member of A's patient) deletes their account → A's patient, documents and bucket are intact (row counts for A's ids unchanged), B's `family_account` is gone.
- [ ] **Step 2:** Run `gl :baymax:test --tests '*OptInTest' --tests '*DeleteConfirmationTest' --tests '*FamilyApiContractTest' --tests '*FamilyDeleteApiTest'`. Expected: FAIL (compile errors on `optInPatient`, `Purpose`; then 404 from the catch-all). Implement; apply fixture `cp`s; run again. Expected: PASS.
- [ ] **Step 3:** Exercise every new route in `AppApiLogSafetyTest` (the family's number `+8801700000533` and the owner's name are needles; the family DELETE runs last, on a family made for it). Fix `AppApiGuardTest`; regenerate `app-routes.txt`. Run `gl :baymax:test --tests '*AppApi*' --tests '*FamilyViewTest' --tests '*FamilyDeletionAcceptanceTest'`. Expected: PASS.
- [ ] **Step 4: Red replays.** (a) Remove the owner check from the reminders route: `aShareMemberSeesTheSharedPatientReadOnly` fails, `expected: 403 but was: 200`. (b) Make `optInPatient` also clear the family: `optInClearsOnlyThePatientsStop` fails naming `family_account.nudges_opted_out_at`. (c) Accept a `PAPER` token on `DELETE /app/api/family`: `aDeleteWithoutAValidTokenTouchesNothing` fails, `expected: 400 but was: 200`. (d) Leave `share_member`'s delete out of `deleteFamily`: `deletingTheFamilyThroughTheApi…` fails with the FK message (the sweep's guard caught it through the API path too).
- [ ] **Step 5:** `gl build`, ignore check, commit `feat(baymax-web): family settings JSON — flags, consent, reminders with opt-back-in, language, and delete-family with a confirm token (plan B5)`. PR body: Q5 under "Pending (PO / box)", replays.

## Task B6: Lab series, blood-pressure pairs, medicines as written

Branch `feat/baymax-web-api-health`. Lane B. Parallel with B3 and B5. No migration.

**Files:**
- Create: `web/domain/LabSeries.java`, `web/domain/LatestPrescription.java`, `web/application/port/in/HealthUseCase.java`, `web/application/port/out/HealthReadPort.java`, `web/application/service/HealthService.java`, `web/adapter/out/persistence/PostgresHealthReadAdapter.java`, `web/adapter/in/api/HealthApiHandler.java`, `web/adapter/in/api/HealthJson.java`.
- Modify: `outbound/domain/MarkerThreshold.java`: make the unit fold public as `public static String foldUnit(String unit)` (the body of today's private `fold`, unchanged), and `sameUnit` calls it. One fold for the module, as `DocumentReadPort.readingsOf`'s javadoc already requires.
- Modify: `AppApiRouter.java`, `docs/infra/app-routes.txt`; `baymax/src/main/resources/baymax/ui-result_{bn,en}.properties` (draft `health.medicines.as_written`, Q3); regenerated `medioo-web/src/copy/generated/copy.{bn,en}.json`.
- Tests: create `web/application/service/HealthServiceTest.java` (pure, mocked port), `web/adapter/out/persistence/HealthReadModelTest.java` (Testcontainers), `web/adapter/in/api/HealthApiContractTest.java`; modify `outbound/domain/MarkerThresholdTest.java` (or create it if absent: `foldUnitIsTheSameUnitsFold`), `AppApiLogSafetyTest.java`, `AppApiGuardTest.java`; fixtures `medioo-web/fixtures/api/health/{labs-some,labs-none,bp,medicines-latest,medicines-none}.json`.

**Interfaces:**
- Consumes: `PaperDisplay.kindOf`, `PaperDisplay.storedSide`, `PaperDisplay.chipKey`, `PaperDisplay.MEDICINE_FIELDS`, `MarkerThreshold.foldUnit`, `HealthRecordPort.visiblePatient`, `ImageRefs.crop`.
- Produces:
  - `HealthReadPort`: `Flux<Map<String, Object>> labPoints(UUID patientId)`: every observation with a `canonical_name` on a DONE paper of the patient, newest `timeline_date` then `created_at` then id; each map holds the keys `PaperDisplay.kindOf` and `PrintedRange` read (`id`, `name`, `canonical_name`, `value`, `unit`, `ref_low`, `ref_high`, `verdict`, `verdict_side`) plus `document_id`, `crop_key`, `date` (`timeline_date`), `date_is_fallback`. `Mono<LatestPrescription> latestPrescription(UUID patientId)`: the newest DONE `prescription` (by `timeline_date`, then `created_at`, then id) that has at least one `medication_event`, with those rows in stored order.
  - `record LabSeries(String canonicalName, String unitFold, String name, String unit, List<Point> points)` with `record Point(UUID documentId, UUID observationId, String value, LocalDate date, boolean dateIsUploadDay, ValueVerdict.Kind kind, String side, String cropKey)`; and `record BpSeries(List<BpPoint> points)` with `record BpPoint(UUID documentId, LocalDate date, boolean dateIsUploadDay, Point systolic, Point diastolic, String cropKey)`.
  - `HealthUseCase`: `Mono<HealthLabs> labs(UUID familyId, UUID patientId)` (`record HealthLabs(List<LabSeries> series, BpSeries bp)`), `Mono<Optional<LatestPrescription>> medicines(UUID familyId, UUID patientId)`. 404 for an unseen patient.
  - **The join.** A series key is `(patient_id, canonical_name, MarkerThreshold.foldUnit(unit))`. Two rows are one series only when all three are equal; nothing is paired by name alone and nothing consumes the first match. The series' `name` and `unit` are the newest point's, as printed.
  - **Blood pressure.** A `bp_systolic` and a `bp_diastolic` row on the same document with the same `crop_key` are one `BpPoint` (the one line-wide crop `CropCutter` cuts for both). A systolic or diastolic with no partner on that `(document_id, crop_key)` stays in its own `LabSeries`, never paired by order or by document alone.
  - `GET /app/api/patients/{id}/labs` → `{"series": [{"canonical_name", "name", "unit", "points": [{"document_id", "observation_id", "value", "date", "date_is_upload_day", "kind": "inside"|"unknown"|"outside"|"critical", "side": "high"|"low"|null, "chip_key", "crop": {"src"}}]}], "bp": {"points": [{"document_id", "date", "date_is_upload_day", "systolic": {...point}, "diastolic": {...point}, "crop": {"src"}}]}}`. Series ordered by the newest point's date, newest first. `kind` is `PaperDisplay.kindOf` over the point's map, so a one-sided printed range with a stored INSIDE is `unknown`. `unit_fold` is not sent (it is a join key, not a display value).
  - `GET /app/api/patients/{id}/medicines` → `{"heading_key": "health.medicines.as_written", "document_id", "date", "date_is_upload_day", "medicines": [{<MEDICINE_FIELDS present>, "crop": {"src"}}]}`, or `{"heading_key": …, "document_id": null, "medicines": []}`. Never `action`, purpose, status or anything not in `MEDICINE_FIELDS`.
- [ ] **Step 1: Write the failing pure tests** in `HealthServiceTest` (the port mocked with maps built in the test):
  - **Both directions of the join, written together:**
    - `microSignGreekMuAndAsciiUAreOneSeries` (Review Focus 4): creatinine `µmol/L` (U+00B5), `μmol/L` (U+03BC) and `umol/L` → one series of three points. Assert the code points in the test with `"µmol/L"`, `"μmol/L"`, so an editor cannot fold them.
    - `aCountAndAPercentageOfOneCellTypeAreTwoSeries` (the dangerous direction): neutrophil `2.25 10^3/µL` and `41.9 %` on one paper, both `canonical_name = "neutrophil"` → two series, each with its own point; `value` of each series' point is its own row's.
    - `theSameMarkerInTwoUnitsIsTwoSeries`: glucose `mg/dL` and `mmol/L` → two series.
    - `rowsAreNeverPairedByNameAlone`: two papers, each with `hba1c %`; three rows total, one of them `canonical_name = "hba1c"` with unit `mmol/mol` → series sizes 2 and 1; every observation id appears in exactly one point (derived: the union of point ids equals the input ids, and no id repeats).
  - **Blood pressure, both directions:**
    - `aSystolicAndDiastolicSharingACropAreOnePair`.
    - `twoPairsOnOnePaperPairByCropNotByOrder`: crops `k1` (130/85) and `k2` (150/95), the rows listed systolic-k1, systolic-k2, diastolic-k2, diastolic-k1 → pairs (130, 85) and (150, 95).
    - `aSystolicWithoutAPartnerStaysAlone`: a systolic on `k1` and a diastolic on `k2` → no pair; two single series.
  - **Display:** `aOneSidedInsideIsNeverInside`: stored `inside`, `ref_low` null, `ref_high` `"200"` → `kind == UNKNOWN`; and the other direction, `aTwoSidedInsideProvedByCodeStaysInside`: `ref_low` `"70"`, `ref_high` `"110"`, value `"95"` → `INSIDE`. `aSidelessOutsideIsUnknown`.
  - **Medicines:** `onlyTheLatestPrescriptionsMedicines` (port returns the latest; the service passes it through untouched); `noFieldOutsideMedicineFields` (an input row with `action: "stopped"` and `purpose: "BP"` → neither key in the output).
- [ ] **Step 2:** Run `gl :baymax:test --tests '*HealthServiceTest'`. Expected: FAIL (`cannot find symbol HealthService`). Make `MarkerThreshold.foldUnit` public (run `--tests '*MarkerThreshold*' --tests '*UrgencyService*'` green before and after: the fold is unchanged), implement the domain records and `HealthService`. Run again. Expected: PASS.
- [ ] **Step 3: Write the failing persistence tests** in `HealthReadModelTest` (`TimelineReadModelTest`'s helpers): `labPointsAreThisPatientsDonePapersNewestFirst` (a PROCESSING paper's rows and another patient's rows are absent); `aRowWithoutACanonicalNameIsNoPoint`; `theLatestPrescriptionIsByTimelineDateThenUpload` (an older-dated prescription uploaded later loses to a newer-dated one uploaded earlier; a newest prescription with zero medicines is skipped); `aLabReportsMedicinesAreNotAPrescription`. Run. Expected: FAIL (missing adapter). Implement `PostgresHealthReadAdapter`. Run again. Expected: PASS.
- [ ] **Step 4: Write the failing contract tests** in `HealthApiContractTest` with fixtures: `labsAreTheServicesSeriesAsJson` (one creatinine series across `µmol/L` and `umol/L`, one BP pair, fields by name); `aOneSidedPointIsSentUnknown` (the JSON `kind` is `unknown` and `chip_key` is `value.chip.unknown`); `medicinesCarryOnlyVerbatimFieldsAndACrop` (walk every medicine object: keys ⊆ `MEDICINE_FIELDS ∪ {"crop"}`); `everyEmittedKeyResolvesInBothLanguages` (includes `health.medicines.as_written`); `anotherFamilysPatientIsNotFound`; `everyCommittedFixtureIsACurrentCase`. Add the draft key, regenerate copy JSON. Run, implement `HealthApiHandler`, `HealthJson` and the routes, apply `cp`s, run again. Expected: PASS.
- [ ] **Step 5:** `AppApiLogSafetyTest` exercise (a medicine named `Zorvalex-7731` and a value `Kalium-8843` are needles), `AppApiGuardTest`, route snapshot. Run `gl :baymax:test --tests '*AppApi*' --tests '*Health*'`. Expected: PASS.
- [ ] **Step 6: Red replays.** (a) Key series by `canonical_name` alone: `aCountAndAPercentageOfOneCellTypeAreTwoSeries` fails, `expected size 2 but was 1`. (b) Fold units without the `μ` → `u` step (call `Normalizer` only): `microSignGreekMuAndAsciiUAreOneSeries` fails with sizes `[2, 1]`. (c) Pair BP by document only: `twoPairsOnOnePaperPairByCropNotByOrder` fails showing `(130, 95)`. (d) Use the stored verdict as `kind` without `PaperDisplay.kindOf`: `aOneSidedInsideIsNeverInside` fails, `expected: UNKNOWN but was: INSIDE`. (e) Add `action` to the medicine map: `noFieldOutsideMedicineFields` fails naming `action`.
- [ ] **Step 7:** `gl build`, ignore check, commit `feat(baymax-web): lab series keyed by name, folded unit and patient; blood pressure paired by its crop; the latest prescription's medicines as written (plan B6)`. PR body: Q3 and the draft key under "Pending (PO / copy owner)", replays.

---

## Task D1: Chat cards dated by the paper's date; a retake linked to the paper it replaces

Branch `fix/baymax-chat-cards-retake-link`. Lane D. After Phase 0 PR 9 (V23) and B3 are on `origin/main`.

**The defect, from the code.** `PostgresTimelineReadAdapter.IN_FLIGHT` returns every RECEIVED, PROCESSING and NEEDS_RETAKE paper uploaded in the last 30 days, and `AttentionService.attention` makes each NEEDS_RETAKE one a `Retake` card. When the family sends the new photo from the retake page (`DocumentPages.sendForm`, which posts `patient` and `back` and nothing naming the old paper), the new paper is read and filed, and the old paper's retake card stays on the চ্যাট stack for up to 30 days: a stale duplicate asking for a photo the family already sent. Nothing records which paper a retake replaces. The other direction must hold while this is fixed: a NOW paper uploaded today whose printed date is months old is on the stack, because the window is the upload (`created_at >= :since`), never the printed date. Retake and reading cards carry `received_at` only; the paper's filed date (`timeline_date`) is not on them.

**Files:**
- Create migration `V<n>__retake_replaces.sql` (next free number above V23 at merge):
  ```sql
  ALTER TABLE ${flyway:defaultSchema}.document
      ADD COLUMN replaces_document_id uuid NULL REFERENCES ${flyway:defaultSchema}.document (id) ON DELETE SET NULL;
  CREATE INDEX document_replaces_idx ON ${flyway:defaultSchema}.document (replaces_document_id) WHERE replaces_document_id IS NOT NULL;
  ```
  plus a `BEFORE INSERT OR UPDATE OF replaces_document_id` trigger `document_replaces_same_patient` that raises `check_violation` ("a retake replaces a paper of the same patient") unless the replaced row has the same `patient_id` and is not the row itself. The rule lives where every writer meets it (V14's lesson). `ON DELETE SET NULL` keeps a retake when the paper it replaced is deleted; `PostgresDocumentRecordAdapter.deleteDocument` needs no new delete (the column is on `document`, which it already deletes), and `DeletionSweep.documentIdTables` (which keys on `document_id` columns) is unaffected; `FamilyDeletionAcceptanceTest` deletes `document` by family.
- Modify: `web/adapter/out/persistence/PostgresTimelineReadAdapter.java` (`IN_FLIGHT` excludes a NEEDS_RETAKE paper `d` when `EXISTS (SELECT 1 FROM document r WHERE r.replaces_document_id = d.id AND r.status <> 'FAILED')`), `web/domain/AttentionItem.java` (`Retake` and `Reading` gain `LocalDate date, boolean dateIsFallback` from the entry's `timeline_date`), `web/application/service/AttentionService.java`, `web/adapter/in/api/AttentionApiHandler.java` (B3's; retake and reading cards gain `date` and `date_is_upload_day`, additive), `web/adapter/in/ui/ChatView.java` (unchanged output; compile only), `web/application/port/in/UploadUseCase.java` and `web/application/service/UploadService.java` (`upload(..., String uploadKey, UUID replaces)`: before any file is read, a non-null `replaces` must be a visible paper of the same patient with status NEEDS_RETAKE — 404 `document_not_found` when not visible, 400 `not_a_retake` otherwise; every document the press makes is linked), `extraction/application/port/out/DocumentRecordPort.java` and `extraction/adapter/out/persistence/PostgresDocumentRecordAdapter.java` (`Mono<Void> linkRetake(UUID documentId, UUID replaces)`: `UPDATE document SET replaces_document_id = :replaces WHERE id = :id`, the only writer of the column; `UploadService` calls it for each document the press returned, a replayed press included. A link that fails is logged at WARN with the two ids and does not fail the upload: the photo is stored and read either way, and the worst case is today's stale card), `web/adapter/in/MultipartUploads.java` (`replacesOrNull(parts)`: field `replaces`, a UUID or null), `web/adapter/in/ui/UploadUiHandler.java` and `web/adapter/in/api/UploadApiHandler.java` (pass it), `web/adapter/in/ui/DocumentPages.java` (`sendForm` adds `<input type="hidden" name="replaces" value="<documentId>">`), `ApiReason.java` (`NOT_A_RETAKE`, `Map.entry("not_a_retake", NOT_A_RETAKE)`), `UploadApiReasonsTest` (its sweep of the upload path's reasons picks up `not_a_retake`).
- Tests: modify `web/adapter/out/persistence/TimelineReadModelTest.java`, `web/application/service/AttentionServiceTest.java`, `web/application/service/UploadServiceTest.java`, `web/adapter/in/api/AttentionApiContractTest.java` (fixtures regenerated), `web/adapter/in/api/UploadApiHandlerTest.java`, `web/adapter/in/ui/NeedsRetakeRenderTest.java`; create `web/adapter/in/api/RetakeLinkAcceptanceTest.java` (Testcontainers Postgres and MinIO).

**Interfaces:**
- Consumes: B3's attention JSON; `UploadUseCase`'s existing five-argument press (D3 `upload_key` replay rules unchanged).
- Produces: the upload field `replaces` (multipart, both the HTML form and `POST /app/api/uploads`); `ApiReason.NOT_A_RETAKE`; every attention card carries `date` and `date_is_upload_day`.

- [ ] **Step 1: Write both directions first**, in `TimelineReadModelTest`, before the migration exists (they fail to compile on the column until Step 3, which is the expected first red; the second test also runs against today's query by reverting only the column use, see Step 6):
  - `aRetakeReplacedByANewPhotoLeavesTheStack` (the stale duplicate): paper A NEEDS_RETAKE uploaded `2026-10-19T04:00:00Z`; paper B DONE uploaded `2026-10-20T03:00:00Z` with `replaces_document_id = A` → `inFlightAndRetakes(p, since)` holds neither A nor B.
  - `aRetakeWhoseReplacementFailedStaysOnTheStack`: as above with B FAILED → A is there (the family still needs to send a readable photo).
  - `aRetakeWhoseReplacementIsBeingReadIsOneCard`: B PROCESSING → the list is `[B]`, never `[B, A]`.
  - `aNowPaperUploadedTodayWithAnOldPrintedDateIsOnTheStack` (the other direction): a DONE `now` paper uploaded `2026-10-20T03:00:00Z` with `doc_date = '2026-03-02'` → `attention(p, since, 10)` holds it, and its `date` is `2026-03-02`.
  - In `AttentionServiceTest`: `retakeAndReadingCardsCarryTheFiledDate` (the entry's `date` and `dateIsFallback` reach the card).
- [ ] **Step 2:** In `UploadServiceTest`: `aRetakeNamesOnlyAPaperOfThisPatientThatNeedsARetake` (a DONE paper → `not_a_retake` 400, nothing created; another patient's NEEDS_RETAKE paper the family can see → `not_a_retake`; an unseen id → 404; `verify(documents, never()).create(any())` for each) and `aPressThatReplacesLinksEveryDocumentItMade` (`SEPARATE` with two files → both linked). In `RetakeLinkAcceptanceTest`: `theRetakePageSendsTheLinkAndTheOldCardLeaves` (render the retake page through `GET /app/documents/{A}`, parse the form with the `Dom` helper, post it with a fresh photo, wait DONE, then `GET /app/api/patients/{p}/attention` has no `retake` card for A) and `theDatabaseRefusesALinkAcrossPatients` (SQL `UPDATE … SET replaces_document_id = <another patient's paper>` fails with `a retake replaces a paper of the same patient`).
- [ ] **Step 3:** Run `gl :baymax:test --tests '*TimelineReadModelTest' --tests '*AttentionServiceTest' --tests '*UploadServiceTest' --tests '*RetakeLinkAcceptanceTest'`. Expected: FAIL (compile on the new arguments, then `column "replaces_document_id" does not exist`). Write the migration and the code. Run again. Expected: PASS.
- [ ] **Step 4:** Regenerate the attention fixtures (`cp` from the contract test), run `gl :baymax:test --tests '*AttentionApiContractTest' --tests '*UploadApi*' --tests '*NeedsRetakeRenderTest' --tests '*ChatViewTest'`. Expected: PASS; the HTML chat page's output is byte-identical (ChatView still renders `sent(receivedAt)`).
- [ ] **Step 5:** `gl build`.
- [ ] **Step 6: Red replays against the pre-fix query.**
  - (a) Restore `IN_FLIGHT` exactly as on `origin/main` (`git show origin/main:baymax/src/main/java/com/elioo/baymax/web/adapter/out/persistence/PostgresTimelineReadAdapter.java`). Expected: `aRetakeReplacedByANewPhotoLeavesTheStack` fails with `Expecting empty but was: [TimelineEntry[documentId=<A>…status=NEEDS_RETAKE…]]`, and `aRetakeWhoseReplacementIsBeingReadIsOneCard` with `[<B>, <A>]`. This is the guard replayed on the defect.
  - (b) Drop `AND r.status <> 'FAILED'`. Expected: `aRetakeWhoseReplacementFailedStaysOnTheStack` fails, `Expecting … to contain <A>`.
  - (c) Change `ATTENTION`'s window to `d.timeline_date >= :since::date`. Expected: `aNowPaperUploadedTodayWithAnOldPrintedDateIsOnTheStack` fails, `Expecting … to contain <id>`. It passes on both the pre-fix and the fixed code by design: it is the direction the fix must not break, and this replay shows it can fail.
  - (d) Drop the trigger: `theDatabaseRefusesALinkAcrossPatients` fails, `Expecting code to raise a throwable`.
- [ ] **Step 7:** Confirm V23 is on `origin/main`, ignore check, commit `fix(baymax): a retake names the paper it replaces, and the old card leaves the chat stack; every card carries the filed date (plan D1)`. PR body: the defect as stated above, Q7, the replay lines (a)–(d).

## Task D2: Retakes filed under the upload day; uncertain dates stored

Branch `fix/baymax-retake-upload-day-uncertain-dates`. Lane D. After Phase 0 PR 9 (V23) and B3; rebased onto D1 if D1 merged first (both touch `PostgresTimelineReadAdapter`).

**The defect, from the code.** `DocumentExtractionService.finish` stores `doc_date = parseDate(result.documentDate())` on every status, NEEDS_RETAKE included. A retake is a page the model could not read confidently, so its date is the least trustworthy read there is, and V20's generated `timeline_date = coalesce(doc_date, Dhaka upload day)` files the retake under it: a blurred photo sent today can sit in March 2021 on the কাগজপত্র calendar, where the family will never look. Separately, the model's `document_date_uncertain` (`ExtractionResult.documentDateUncertain`) is kept only inside `extraction_json`; no column carries it, so the timeline cannot mark the date and a trend cannot drop its direction (spec §4.18).

**Files:**
- Create migration `V<n>__filed_day_and_uncertain_date.sql`:
  - `ALTER TABLE document ADD COLUMN doc_date_uncertain boolean NOT NULL DEFAULT false;` then the backfill `UPDATE document SET doc_date_uncertain = coalesce((extraction_json ->> 'document_date_uncertain')::boolean, false) WHERE extraction_json IS NOT NULL;` between `-- backfill:begin` and `-- backfill:end`.
  - Redefine the filing day where every writer meets it: `DROP INDEX document_patient_timeline_idx; ALTER TABLE document DROP COLUMN timeline_date;` then `ADD COLUMN timeline_date date GENERATED ALWAYS AS (CASE WHEN status = 'NEEDS_RETAKE' OR doc_date IS NULL THEN (created_at AT TIME ZONE 'Asia/Dhaka')::date ELSE doc_date END) STORED`, `ADD COLUMN timeline_date_is_upload_day boolean GENERATED ALWAYS AS (status = 'NEEDS_RETAKE' OR doc_date IS NULL) STORED`, and re-create `document_patient_timeline_idx` exactly as V20 made it. (Drop and re-add, not `ALTER COLUMN … SET EXPRESSION`, which needs Postgres 17 and the production Supabase version is not asserted here.) `COMMENT ON` both columns.
  - The printed `doc_date` is kept on a retake (nothing is lost; a later reading of the same paper may confirm it), and never shown as the paper's date.
- Modify: `extraction/domain/Document.java` and `extraction/adapter/out/persistence/{DocumentEntity.java,PostgresDocumentRecordAdapter.java}` (write `doc_date_uncertain` from `result.documentDateUncertain()` in `finish`; read the existing constructors first and add the field the way V20 added `statusReasonCode`), `extraction/application/service/DocumentExtractionService.java` (`finish` passes it), `web/adapter/out/persistence/PostgresTimelineReadAdapter.java` (`d.doc_date IS NULL AS date_is_fallback` → `d.timeline_date_is_upload_day AS date_is_fallback`; add `d.doc_date_uncertain`), `extraction/adapter/out/persistence/PostgresDocumentReadAdapter.java:49` (the same replacement), `web/domain/TimelineEntry.java` (`boolean dateUncertain`), `web/adapter/in/api/PaperJson.java` (`dates(...)`: a NEEDS_RETAKE paper is dated by its upload day with `date_is_upload_day: true` and no `printed_date`; every paper gains `date_uncertain`), `web/adapter/in/ui/DocumentPages.java` (`paperDateText`, the same rule for the HTML retake page), `web/adapter/in/api/TimelineJson.java` and `AttentionApiHandler.java` (B3's; add `date_uncertain`), `web/adapter/in/api/HealthJson.java` (B6's points; add `date_uncertain` if B6 merged).
- Tests: modify `web/adapter/out/persistence/TimelineReadModelTest.java`, `extraction/application/service/DocumentExtractionServiceTest.java`, `web/adapter/in/api/PaperApiContractTest.java` (fixtures regenerated), `web/adapter/in/api/PatientTimelineApiContractTest.java`, `web/adapter/in/ui/NeedsRetakeRenderTest.java`; create `extraction/adapter/out/persistence/FiledDayMigrationTest.java` (Testcontainers: migrate to the version before this one with Flyway `target`, insert legacy rows, migrate, assert).

**Interfaces:**
- Produces: `document.timeline_date` (redefined), `document.timeline_date_is_upload_day`, `document.doc_date_uncertain`; `TimelineEntry.dateUncertain()`; JSON `date_uncertain: bool` on papers, timeline entries, attention cards and lab points.

- [ ] **Step 1: Write the failing tests:**
  - `TimelineReadModelTest`:
    - `aRetakeIsFiledUnderItsUploadDayNotTheDateReadOffIt`: NEEDS_RETAKE uploaded `2026-10-20T04:00:00Z`, `doc_date = '2021-03-04'` → `date == 2026-10-20`, `dateIsFallback == true`.
    - `aRetakeUploadedAfterDhakaMidnightIsInTheNewMonth` (Review Focus 5): NEEDS_RETAKE uploaded `2026-10-31T18:30:00Z` (00:30 on 1 November in Dhaka), `doc_date = '2026-10-12'` → `date == 2026-11-01`; its B3 JSON `month` is `2026-11`.
    - `aRetakeUploadedJustBeforeDhakaMidnightStaysInTheOldMonth`: uploaded `2026-10-31T17:59:59Z` → `2026-10-31`, month `2026-10`.
    - `aFinishedPaperKeepsItsPrintedDateAcrossMidnight` (the other direction): DONE uploaded `2026-10-31T18:30:00Z`, `doc_date = '2026-09-30'` → `date == 2026-09-30`, `dateIsFallback == false`, month `2026-09`.
    - `anUncertainPrintedDateIsFiledAsPrintedAndMarked` (Q6): DONE, `doc_date = '2026-07-11'`, `doc_date_uncertain = true` → `date == 2026-07-11`, `dateUncertain == true`.
  - `DocumentExtractionServiceTest`: `theUncertainFlagIsStored` (a result with `document_date_uncertain: true` → the saved `Document` has it, on DONE and on NEEDS_RETAKE).
  - `FiledDayMigrationTest`: `theBackfillReadsTheFlagFromTheStoredExtraction` (a pre-migration row whose `extraction_json` has `"document_date_uncertain": true` → `true`; one without the key → `false`; one with `extraction_json` NULL → `false`) and `existingRetakesMoveToTheirUploadDay` (a pre-migration NEEDS_RETAKE row with `doc_date = '2021-03-04'` → `timeline_date` is its Dhaka upload day after the migration; a DONE row's is unchanged), and `theTimelineIndexIsBack` (`pg_indexes` has `document_patient_timeline_idx` with the V20 definition).
  - `PaperApiContractTest`: `aRetakeIsDatedByItsUploadDayNeverByWhatWasMisread` (a retake case with a printed `2021-03-04` → `date` is the upload day, `date_is_upload_day: true`, `printed_date` absent).
  - `PatientTimelineApiContractTest`: `monthGroupingAcrossADhakaMidnight`: the four papers of the `TimelineReadModelTest` cases through `GET /app/api/patients/{id}/timeline` → `month` values `2026-11`, `2026-10`, `2026-09` and the uncertain one's `date_uncertain: true`, in timeline order.
- [ ] **Step 2:** Run `gl :baymax:test --tests '*TimelineReadModelTest' --tests '*DocumentExtractionServiceTest' --tests '*FiledDayMigrationTest' --tests '*PaperApiContractTest' --tests '*PatientTimelineApiContractTest'`. Expected: FAIL; the first message is `expected: 2026-10-20 but was: 2021-03-04` from `aRetakeIsFiledUnderItsUploadDayNotTheDateReadOffIt` (the defect, not a compile error: write the timeline tests against existing columns first, then add `dateUncertain` and the migration test). Implement the migration and the code. Run again. Expected: PASS. Regenerate the affected MSW fixtures from the printed `cp` lines.
- [ ] **Step 3:** Run `gl :baymax:test --tests '*TimelineView*' --tests '*ChatView*' --tests '*NeedsRetakeRenderTest' --tests '*DocumentPages*' --tests '*BanglaGoldenVectorsTest'`. Expected: PASS. Any HTML render test whose expected text was a retake's printed date is the finding: change it to the upload day and name it in the PR body.
- [ ] **Step 4: Red replays.** (a) Redefine `timeline_date` as V20's expression: `aRetakeIsFiledUnderItsUploadDay…` fails with `expected: 2026-10-20 but was: 2021-03-04`, and `aRetakeUploadedAfterDhakaMidnightIsInTheNewMonth` with `expected: 2026-11-01 but was: 2026-10-12`. (b) Use `(created_at AT TIME ZONE 'UTC')::date`: `aRetakeUploadedAfterDhakaMidnightIsInTheNewMonth` fails with `expected: 2026-11-01 but was: 2026-10-31`. (c) Leave `date_is_fallback` as `doc_date IS NULL`: the first test fails on `dateIsFallback`, `expected: true but was: false`. (d) Drop the backfill: `theBackfillReadsTheFlagFromTheStoredExtraction` fails, `expected: true but was: false`.
- [ ] **Step 5:** `gl build`, confirm V23 on `origin/main`, ignore check, commit `fix(baymax): a retake is filed under its Dhaka upload day, never a date misread off it; the model's uncertain date is stored and sent (plan D2)`. PR body: the defect, Q6, the replays, and the migration's drop-and-re-add with its index.

---

## Self-review notes (writing-plans)

- **Spec coverage.** Part D's W2a (tokens, reconciliation, fonts, stylelint, built-CSS `--now*`, contrast both themes, decision 7 in one token) → W2a Steps 2–12. W2c–W2e (the twelve components in the master's groups, catalog behind a build flag, 404 in production) → W2c Steps 4–6 (`catalog-off.spec.ts`, smoke row), W2d, W2e. B3 (stack, timeline by month, follow-ups, ack migration with FK and delete order, two-direction ack tests first, Testcontainers deletion with a real ack row) → B3 Steps 1–9. B5 (flags, consent, reminders, opt-out, language, schema-derived deletion, opt-back-in) → B5. B6 (series key, BP via `crop_key`, decision 9, dangerous-direction join, MICRO SIGN, one-sided never INSIDE) → B6 Steps 1–6. D1 (dated by the paper's date, retake link, both directions first, pre-fix replay, Testcontainers) → D1. D2 (retakes under the upload day, uncertain dates stored, Dhaka-midnight grouping) → D2.
- **Grounding.** Paths, classes and methods were read on `origin/main` at `1884f90`: `AppApiRouter.mediooAppApiRoutes`'s seven handlers, `ApiReason.BY_REASON`, `DeleteConfirmation.PURPOSE`, `AttentionService.WINDOW`, `PostgresTimelineReadAdapter.ATTENTION`/`IN_FLIGHT`, V18's `urgency_assessed_at` stamped on every assessment by `PostgresDocumentUrgencyAdapter.RAISE`, V20's generated `timeline_date`, `DocumentExtractionService.finish` writing `doc_date` on every status, `MarkerThreshold.fold`, `PaperDisplay.kindOf`, `FamilyDeletionAcceptanceTest.targets()`, `DeletionSweep.rowsByTable`, `BaymaxSchemaMigrator`'s Flyway defaults. The latest applied migration is V22; V23 is PR 9's.
- **Type consistency.** `NowSeen` and `NowBanner` (B3) are the types W2d's `NowBannerJson` mirrors; `CalendarDayJson` (W2e) is B3's calendar `days[]`; `TrendPointJson` (W2e) is B6's point; `AttentionItem.Retake/Reading` gain `date`/`dateIsFallback` only in D1; `TimelineEntry.dateUncertain` only in D2.
- **Review Focus.** Each line has its test in the owning task: B3 `aLaterGateReleaseShowsTheBannerAgain`, the two share-member tests; W2c's `noElementOverflowsItsBox` over `long-name` states; B6 `microSignGreekMuAndAsciiUAreOneSeries` and `aCountAndAPercentageOfOneCellTypeAreTwoSeries`; D2 `aRetakeUploadedAfterDhakaMidnightIsInTheNewMonth`.
- **Proportion.** Bodies are given only where a test or signature does not decide them: the migrations, the token file's derivation grammar, the NOW-usage scan, the series join and the BP pairing rule.
