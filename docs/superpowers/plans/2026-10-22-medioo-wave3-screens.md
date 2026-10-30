# Medioo Web Rebuild — Wave 3 screens Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. In this repo each PR below runs as its own Workflow pipeline (master plan Part E): one worktree, one branch, one PR, no merge inside the pipeline.

**Goal:** Put the approved screens in front of families one route at a time: sign-in, the shell, চ্যাট with the global NOW banner, the paper, upload, কাগজপত্র, স্বাস্থ্য, পরিবার and settings. Each screen first sits behind a preview path that only allowlisted families can open, and is flipped in Caddy after Tanvir has tried it on his phone.

**Architecture:**
- **Screens** are Next.js App Router pages under `medioo-web/src/app/app/`. They render what `/app/api/**` sends and derive nothing. Server components read Java with `GET` only, through one module (`src/api/java.ts`) that forwards the incoming `Cookie` header and nothing else. Every mutation is a browser `fetch` to `/app/api/**` through `src/api/browser.ts`, which Caddy sends straight to Java.
- **Client islands carry no copy.** A `"use client"` file takes its words already resolved, as props from a server parent (PR #127's rule, enforced by eslint's `CLIENT_COPY_SYNTAX` and by `src/styles/built/client-copy.built.test.ts › noClientChunkCarriesCopy`). A client-owned sheet is `SheetDialog`, a client toast is `ToastRegion`; `Sheet` and `Toast` are the server halves.
- **Preview before flip.** `src/middleware.ts` rewrites `/app/preview` and `/app/preview/<rest>` to `/app` and `/app/<rest>`, and marks the request. A marked request reads nothing from Java except `GET /app/api/me` until that answer says `preview: true` (`BAYMAX_WEB_PREVIEW_FAMILIES`, B1). The gate lives in `javaGet`, where the reads happen, not in a layout. Links inside a preview page stay inside the preview. Caddy routes `/app/preview` and `/app/preview/*`, `/_next/static/*`, `/fonts/*` and `/brand/*` once (PR F0, the first HTML route), and each screen's real paths in its own flip PR.
- **Tests run against a stub Java.** Playwright starts `next start` with `JAVA_INTERNAL_URL` pointing at `e2e/support/java-stub.mjs`, which serves the fixtures Java's contract tests write (`medioo-web/fixtures/api/**`) and refuses mutations exactly as `AppApiGuard.csrf` does. The browser's own `/app/api` calls and image requests are answered from the same scenario table. One shared check suite (`e2e/support/screen-checks.ts`) runs the content checks on every routed page.
- **Java gains five small route groups and one audit change** where a screen needs a read or a write that does not exist yet: the sign-in state, the checked held place, sign-out and the no-JS fallback (W3-api); the chat thread (W5-api); the value sheet (W6-api); the calendar's audit row (W8-api, S14); and the theme preference (W10-api, S12).

**Tech Stack:**
- Next.js 15.5.28 / React 19.2.9 / TypeScript strict, pnpm 10.11, Node 22; Vitest 5, Testing Library, Playwright 1.64.1 with `@axe-core/playwright` 4.14.0, stylelint.
- Spring Boot 3.4 WebFlux and R2DBC, Testcontainers (Postgres 17, MinIO), JUnit 5, AssertJ.
- Caddy (the box's `n8n-caddy-1`, and W1d's local replica), bash, jq, curl.

**Spec:**
- [`2026-10-21-medioo-nextjs-rebuild.md`](2026-10-21-medioo-nextjs-rebuild.md), the master plan: Global Constraints, Review Focus, Part A (A1, A3, A4, A6, A7, A8), Part B, Part D Wave 3 (W3–W10), Part E.
- [`2026-10-21-medioo-wave2.md`](2026-10-21-medioo-wave2.md) and [`2026-10-22-medioo-wave2b-3-backend.md`](2026-10-22-medioo-wave2b-3-backend.md): their Global Constraints and Shared interfaces (`gl`, `Snapshot`, the fixture envelope, `ApiReason` additions, `FamilySweep`, `SchemaSnapshot`, `NowPapers`, component styles, the colour and NOW-name bans) are binding here and not restated.
- [`docs/design/2026-10-21-approved/`](../../design/2026-10-21-approved/README.md): `phone.html` (scenes `welcome`, `chat`, `now`, `retake`, `first`, `papers`, `health`, `family`, `theme`) and `desktop.html` (scenes `signin`, `app`, `first`).
- `docs/DECISIONS.md`: DR-12, DR-13, DR-40, DR-41, DR-42 (no-JS scope, budget, the sign-in fallback), DR-44, DR-45.
- `docs/RUNBOOK.md` → "Routing a path to medioo-web" (the box procedure every flip follows) and "Rollback" (the routes floor).
- `CLAUDE.md`: its flattering-failure rules govern every test below.

**What is on `origin/main` now (6b2b3a1), and what this plan waits for:**
- On main: B1 `/app/api` with `AppApiGuard` and `/me`; B2 paper JSON; B3 now-banner, attention, timeline, calendar, follow-ups; B4a sign-in JSON; B4b upload JSON; B4c status and delete; B5 family JSON; B6 labs and medicines; D1, D2, D4b; W1a–W1c; W2a tokens with the `boundary` token (PO ruling on PR #120); W2b copy; W2c Wordmark, MioAvatar, MessageBubble, StatusChip; W2d-a VerdictCard, Toast/ToastRegion, Sheet/SheetDialog; W2d-b NowBanner, NowBannerSeen; W2e-a OtpInput, PhoneField. Migrations up to V28; `outbound_message_kind_check` allows `explanation`, `detail`, `retake`, `nudge`, `failure` (V23).
- **Not on main, and each blocks only what is named:**
  - W1d: PR #130 (`medioo-web/e2e/budget-ceilings.json`, the enforcing run, `NEXT_PAGES` in `src/routing/paths.ts` and `src/routing/built/routes.built.test.ts`): **W3 does not merge before it** (DR-42 item 4). Where this plan uses the ceilings file, its shape is "per W1d, once merged".
  - W2e-b (MonthCalendar, TrendChart): W8 and W9 start after it.
  - E1/E2-API and the PO's Q9: W10's delete-family step (S7).
  - D11: chat answers. Until then W5's composer declines the way WhatsApp does (S8).

**Order and lanes:**

| PR | Branch | Lane | Starts after | Flips |
|---|---|---|---|---|
| W3-api | `feat/baymax-web-signin-state-fallback` | B | now | nothing |
| W3 | `feat/medioo-web-signin` | C | now; **merges after W1d and W3-api** | nothing (pages unreachable until F0) |
| F0 | `feat/medioo-route-preview` | X | W3 merged and deployed | `/app/preview`, `/app/preview/*`, `/_next/static/*`, `/fonts/*`, `/brand/*` |
| F3 | `feat/medioo-flip-signin` | X | F0 merged; W3's on-device step done; S1 and S3 ruled | `GET`/`HEAD` `/app`, `/app/`, `/app/verify` |
| W4 | `feat/medioo-web-shell` | C | W3 merged | nothing (no page of its own) |
| W5-api | `feat/baymax-web-api-thread` | B | now; merges after S4 and S10 | nothing |
| W5 | `feat/medioo-web-chat` | C | W4 and W5-api merged | nothing |
| F5 | `feat/medioo-flip-chat` | X | W5 merged; on-device done | `GET`/`HEAD` `/app/home` |
| W6-api | `feat/baymax-web-api-value-sheet` | B | now | nothing |
| W6 | `feat/medioo-web-paper` | C | W4 and W6-api merged (W5 for the thread aside) | nothing |
| F6 | `feat/medioo-flip-paper` | X | W6 merged; on-device done | `GET`/`HEAD` paper, `/all`, `/values/{obs}` |
| W7 | `feat/medioo-web-upload` | C | W4 merged | nothing |
| F7 | `feat/medioo-flip-upload` | X | W7 merged; on-device done | `GET`/`HEAD` `/app/add` |
| W8-api | `fix/baymax-web-calendar-audit` | B | now; merges after S14 | nothing |
| W8 | `feat/medioo-web-papers` | C | W4, **W2e-b** and W8-api merged | nothing |
| F8 | `feat/medioo-flip-papers` | X | W8 merged; on-device done | `GET`/`HEAD` `/app/timeline` |
| W9 | `feat/medioo-web-health` | C | W4 and **W2e-b** merged | nothing |
| F9 | `feat/medioo-flip-health` | X | W9 merged; on-device done | `GET`/`HEAD` `/app/health` |
| W10-api | `feat/baymax-web-api-preferences` | B | now; merges after S12 | nothing |
| W10 | `feat/medioo-web-family` | C | W4 and W10-api merged; S5, S6, S7 ruled | nothing |
| F10 | `feat/medioo-flip-family` | X | W10 merged; on-device done | `GET`/`HEAD` `/app/family`, `/app/patients/{id}/profile` |

There is no F1, F2 or F4. W1 and W2 flip nothing, and W4 has no page of its own: its shell reaches families inside F5's chat page. The F numbers follow the W numbers so that each flip names the screen it flips.

Flip PRs serialise with every other merge (RUNBOOK step 1: hold other merges from the box edit until the flip's deploy is green). **Departure from the master plan, for PO sight:** the master plan says "one PR per screen; each flips its routes after the on-device step". Here each screen gets two PRs: the screen PR (W) and a flip PR (F). The RUNBOOK applies a Caddy edit to the box **before** its PR merges, so a flip inside the screen PR would route families to a page the box is not yet running.

## Global Constraints

The master plan's [Global Constraints](2026-10-21-medioo-nextjs-rebuild.md#global-constraints), the Wave 2 plan's and the Wave 2b/3 back end plan's [Global Constraints](2026-10-22-medioo-wave2b-3-backend.md#global-constraints) apply **verbatim** to every task here. They cover `gl`, the web commands, the additive API, the regenerated `app-routes.txt`, `AppApiLogSafetyTest`'s derived list, `{"reason"}` errors, no Bangla literal outside generated files, the colour and NOW-name bans, component styles, new copy keys as drafts, commit commands in full, the ignore check over every touched path, red replays and boards. `CLAUDE.md` applies too. New here, one line each:

- **Client islands take their words already resolved**, as props from a server parent; a client-owned sheet is `SheetDialog({title, closeLabel, open, onClose})` and a client toast is `ToastRegion({message})`, never `Sheet` or `Toast`. Each task's Interfaces names the words record its page resolves. A component that calls `t()` and is needed inside an island gets a copy-free twin (`…View`) that takes the words as props; the server component becomes a thin wrapper that resolves the words and renders the twin, so both render the same markup.
- **The DR-42 ceilings are read, never restated, and no screen PR edits the ceilings file.** Every page in `NEXT_PAGES` is measured by W1d's `medioo-web/e2e/budget.spec.ts` against the `probe` and `route` ceilings in `medioo-web/e2e/budget-ceilings.json`. W1d (PR #130) already makes `budget.spec.ts` iterate `NEXT_PAGES`; W3 maps each page to a scenario in `e2e/support/scenarios.mjs` `PAGE_SCENARIOS` (the spec fails if `NEXT_PAGES` is empty or a page has no scenario). The ceilings file therefore holds the two numbers and no page list. A page cannot be left out, and no W PR needs the DR-42 item 4 sign-off. **S9 (accepted by the PO 2026-10-22):** the ceilings file keeps the two ceilings only, as W1d (PR #130) wrote it.
- **What the route ceiling counts (S9, accepted by the PO 2026-10-22; applied by W1d):** the page's first-load wire bytes, excluding responses from Java's image routes (`/app/documents/*/image` and `/app/documents/*/page/*`). Those are crops that Java serves at their real size, and the e2e stub's synthetic PNGs say nothing about them. Without this exclusion, `/all` with 58 values would pass or fail on the stub's PNG sizes, which is a reason other than the code. DR-42 does not say, so the rule goes to the PO.
- **Reading pages work without JavaScript** (DR-42 item 1): the timeline (`/app/timeline`), a paper (`/app/documents/{id}`, `/all`, `/values/{obs}`), a NOW result. Each has a `javaScriptEnabled: false` Playwright case. Every mutation needs JavaScript and goes through `apiSend` or `apiUpload`. Without JavaScript its control is absent or says so in copy, and is never a form that posts somewhere.
- **Next reads Java with GET only, in one file.** Only `src/api/java.ts` names `JAVA_INTERNAL_URL`. That is `scripts/check-no-server-mutation.sh` rule 5, added in W3, and rule 3 checks the method inside that one file. `java.ts` forwards the incoming `cookie` header and no other header, uses `cache: "no-store"`, and is wrapped in React `cache()`, so that a layout and a page in one request read one path once. Cross-request duplicates (a prefetch, a soft navigation, `router.refresh()`, a client `apiGet`) are what `cache()` cannot stop. The lint ban on prefetch and W6's request count cover them.
- **No `next/link` prefetch.** Every `<Link>` carries `prefetch={false}`: a prefetched paper is a `GET /app/api/documents/{id}`, which records a DOCUMENT_VIEW nobody made (master W6). An eslint rule refuses a `Link` without it (W4).
- **A screen never decides a patient.** With two or more visible patients and no remembered choice, the page shows the chooser (`switch.title`, `switch.hint`). `?patient=<id>` that is not in `me.patients` is the not-found page, as Java's `ActivePatient.resolve` answers 404.
  *(Amended by W7, for PO sight:)* one exception: a page that asks whose it is in its own words, from its own read, passes `asksItself: true` to `signedInPage` and gets `main` (with no patient) in place of the chooser. Today that is only `/app/add`: the sheet asks whom to *send* for (`add.choose`, over `can_send`), which is not whom the family may *see*, and it opens on Java's `chosen_patient_id`, never on a guess. A page without the flag still gets the chooser with two patients and no choice (W4's `shell.spec.ts › twoPatientsAndNoChoiceShowTheChooser`, and W7's `twoPatientsAndNoChoiceGetTheSheetNotTheShellsChooser` for the flagged page).
  *(Amended by W10, approved by the PO 2026-10-23, ruling P2 on PR #146:)* **One exception, for a page about every patient:** a page that passes `everyone: true` to `signedInPage` (W10's পরিবার tab, and the profile page) is not replaced by the chooser. It still decides nobody: with two or more patients and no choice, `patient` is null and the toolbar names nobody, exactly as under the chooser. A page whose address names one patient passes `asked: <id>` instead (the profile page, `/app/patients/{id}/profile`), which resolves through `activePatient` like `?patient=` does: a patient this family cannot see is the not-found page, and the toolbar names the asked patient, as Java's `ProfilePages.profilePage` answers by choosing that patient.
  *(Amended by W6, for PO sight; not yet ruled:)* **One exception, for a page about one patient's paper:** a page that passes `patient: <id>` to `signedInPage` (W6's paper, `/all` and value sheet) names the patient Java sent the paper with, in place of the remembered choice, `?patient=` and the chooser, so a two-patient family opening a paper link from WhatsApp sees the paper and not the chooser. Like P2's `asked`, it resolves through `activePatient`: a patient this family's `me` does not list is the not-found page. `patient: null` (a paper that is gone) names nobody and shows no chooser. The chooser never stands in for a paper page. Held both ways by `paper.spec.ts › aTwoPatientFamilyOpeningAPaperLinkSeesThePaperNotTheChooser` and `› aPaperAboutAPatientThisFamilyDoesNotSeeIsNotFound`.
- **No screen text is logged.** `src/api/*`, pages and components call no `console.*` (eslint `no-console` over `medioo-web/src`, W3). A thrown Java error's message is `java <status> <route pattern>` (ids replaced by `{id}`, no query, no body). The Next server log of a full e2e run carries none of the fixtures' names, numbers or crop keys, happy paths and the `java-502` and `paper-500` scenarios included (`e2e/server-log.spec.ts`, W3).
- **Inputs carry the boundary token** (PO, PR #120, Q1 option A): every selector a screen adds that paints `--fill` (the composer field, a segmented control's track, a check box, the theme switch) also draws `1px solid var(--boundary)`. W2a's built-CSS `fillBoundaryViolations` and the routed-page `everyFieldAndTrackDrawsTheBoundary` check both.
- **Copy keys a screen adds are drafts** listed in its PR body under "Pending (PO / copy owner)" with both strings. This plan's drafts are named in each task; none ships before the PO's yes.
- **W gates** (named once, used by every W task). Each must PASS:
  ```bash
  pnpm -C medioo-web install --frozen-lockfile && pnpm -C medioo-web lint && pnpm -C medioo-web lint:css \
    && pnpm -C medioo-web typecheck && pnpm -C medioo-web test \
    && pnpm -C medioo-web build && pnpm -C medioo-web test:built \
    && pnpm -C medioo-web build:catalog && pnpm -C medioo-web test:built \
    && pnpm -C medioo-web build && pnpm -C medioo-web e2e
  ```
  `test:built` runs after both builds, so `noClientChunkCarriesCopy` judges the production chunks and the catalog chunks. The ignore check runs over every touched source directory, and `git status --ignored --short -- <dirs> | grep '^!!'` prints nothing. A task that touches Java also runs `gl build`.
- **Box steps belong to the operator.** The executing agent reads the box only (`ssh -i ~/Documents/000_office/umr/aws/bdemr-text-analysis.pem ec2-user@<host>`), never edits `/home/ec2-user/medscribe.env` or the box Caddyfile, and lists each box step under `box_steps_pending` with the RUNBOOK step it is.

## PO decisions this plan needs

Each blocks only the step named. When a decision blocks a merge, the PR is built and tested on the default and does not merge until the PO rules, with the ruling quoted in the PR body. **Proposed cuts** change scope from the master plan; their PR merges with the cut only after the PO's yes (CLAUDE.md: scope changes are proposed, never made).

| # | Decision | Blocks | Default until ruled |
|---|---|---|---|
| S1 | **The no-JS sign-in fallback** (DR-42 Consequences, pending). | F3 merge | **Keep it.** Java serves it at `/app/basic` (W3-api), Caddy leaves it on Java, and the Next sign-in page links to it from `<noscript>`. The signed-in redirect does **not** use it (W3-api's `GET /app/api/sign-in/next`), so the rest of W3 works either way. **If the PO accepts the gap instead**, these artifacts change: W3's `<noscript>` link becomes the `login.nojs.gap` copy (a new draft); `theNoScriptLinkCarriesTheHeldPlace` asserts that copy instead of the link; W3-api's `/app/basic*` routes, `BasicSignInPagesTest` and `fixtures/seams/noscript-href.json` are not merged; F3's `/app/basic` smoke row is dropped; W6's `anExpiredSessionOnAPaperWithoutJavaScriptReachesTheFallback` asserts the 307 and the copy. **The DR-42 no-JS proof is split** across W3 `theNoScriptLinkCarriesTheHeldPlace` (Next renders the encoded link), W3-api `aNoJsSignInThroughBasicOpensTheHeldPage` (Java, starting from the same href read from `fixtures/seams/noscript-href.json`) and W6 `anExpiredSessionOnAPaperWithoutJavaScriptReachesTheFallback` (the paper's 307 and the link). DR-42 names W3 as owner; this plan moves the paper half to W6, where the paper page exists. |
| S2 | **New HTML routes under `/app`.** W3-api adds `/app/basic`, `/app/basic/`, `POST /app/basic/login`, and `GET`/`POST /app/basic/verify`. The Wave 2 constraint lets a PR add `app-routes.txt` lines under `/app/api/` only. | W3-api merge | Added, and the PR body asks for the snapshot sign-off. |
| S3 | **Proposed cut: `/app/login` stays on Java.** DR-42 item 1 says Caddy moves `/app/`, `/app/login` and `/app/verify`. `/app/login` has no GET today; it is only the number form's POST target, and Java keeps every POST. | F3 merge | F3 routes `GET`/`HEAD` `/app`, `/app/`, `/app/verify` only; `POST /app/login` and `POST /app/verify` stay on Java (route-probe rows assert it). |
| S4 | **Whose messages a share member sees in the chat thread.** `outbound_message.family_id` is the account WhatsApp delivered to. | W5-api merge | Only messages delivered to the viewer's own `family_account` (`family_id = WebSession.familyId()`). A share member sees the papers and their cards, and no message they never received. |
| S5 | **A share member's family reminder switch** (wave 2b–3 plan Q11). | W10 merge | Hidden for a viewer who owns no patient (every `patients[].owner` false): the switch changes nothing they receive. Shown for an owner. |
| S6 | **Proposed cut: sounds in settings.** Master W10 lists sounds; the sound bank is W12. | W10 merge | W10 ships theme and language only; the sounds row lands with W12, which has something to switch. |
| S7 | **Proposed cut: delete-family in পরিবার** (Q9; master W10 lists the confirm). | W10 merge | Not built in W10. It lands in a follow-up on E1/E2-API once the PO's written yes exists. |
| S8 | **Copy drafts**, each listed in its task, including W5's composer reply: the new `chat.ask.reply` (the decline and "ask a doctor", as `wa.fallback` says them) followed in code by `safety.red_flag_line`, the line WhatsApp appends to `wa.fallback` until D11's emergency template ships. | the PR that adds each key, at merge | Drafts as written. |
| S9 | **The DR-42 ceilings file and the image class.** (a) The file holds the two ceilings only, and the page list is `NEXT_PAGES`. (b) Java-served crop and page images are left out of a page's route total. | W3 merge | (a) and (b) as stated in Global Constraints. |
| S10 | **P1: delivered messages about a deleted paper** (B4c keeps delivered history "pending PO decision P1"; `outbound_message.document_id` has no FK). In the web thread, the deleted paper's explanation would stay readable, quoting its values. | W5-api merge | The thread lists a message only when its `document_id` is NULL or still has a `document` row. The stored row is untouched, which leaves P1's storage question open. |
| S11 | **Logged history in the thread.** `sent_at` set with `delivery_status` NULL is the log adapter's outcome (`DeliveryOutcome.logged()`): nothing reached the family. | nothing | Not listed. Showing it would need its own copy, never the delivered bubble. |
| S12 | **A Java route for a browser-set cookie.** W10-api adds `PUT /app/api/preferences` (a mutation route and an `app-routes.txt` line, which needs the snapshot sign-off) only to set the non-secret `baymax_theme` cookie. | W10-api merge | Added. The alternative is a cookie the page sets with `document.cookie`, which needs no Java route but cannot be `HttpOnly`. |
| S13 | **The thread's card for a share member abroad** is checked by field (`call_999` false), as the banner and the paper are. | nothing | As stated; listed so the PO sees the rule extended to a third surface. |
| S14 | **One open of কাগজপত্র is one TIMELINE_VIEW.** The page reads the timeline and the calendar, and today each records a `timeline_view` row (`TimelineService` lines 78 and 107), which would double the weekly export's `timeline_views`. | W8-api merge | The calendar read records no audit row; the timeline read is the view (W8-api). The calendar has no other caller (`PatientTimelineApiHandler` only). |

## Review Focus

Inputs no task's main tests exercise, most likely to reach a family first. Each has a test in the task that owns the code.

1. **A Caddy matcher that captures a Java sub-path.** Flipping the paper must not route `/app/documents/{id}/image`, `/page/{n}`, `/status` or `/delete`, nor any `/app/api/**`, to Next: every crop would vanish at once. Owner: F6 (negative probe rows for each sub-path, replayed red against a `/app/documents/*` matcher) and F0 (the `/app/api/me` row).
2. **A WhatsApp paper link opened, signed out, in a browser without JavaScript** (Opera Mini's extreme mode). The paper must redirect to sign-in with its place held, and the sign-in page must offer the Java fallback. Owner: W3 `theNoScriptLinkCarriesTheHeldPlace`, W3-api `aNoJsSignInThroughBasicOpensTheHeldPage` and W6 `anExpiredSessionOnAPaperWithoutJavaScriptReachesTheFallback` (S1 names the split).
3. **দেখেছি tapped after the session expired.** The ack must not look done: at the 401 the banner is still rendered and the client goes to sign-in with the page held, and Java writes no `now_ack`. Owner: W5 `aSeenTapOnAnExpiredSessionGoesToSignInAndKeepsTheBanner` and W5-api `NowBannerApiTest › anAckWithoutALiveSessionIs401AndWritesNoAck`.
4. **One open of a paper recording two views.** A prefetch, an RSC soft navigation, `router.refresh()`, a client `apiGet` of the paper, or a non-preview family's preview-path read would each write a DOCUMENT_VIEW. (`cache()` already makes a layout and a page in one request share one read.) Owner: W6 `oneOpenIsOneDocumentRead` and `aNonPreviewFamilyOnAPreviewPaperCausesNoPaperRead` (the stub's request log, the web half), Java's `PaperApiHandlerTest › openingAPaperRecordsExactlyOneView` (one GET is one row, the other half), and the W4 lint ban on prefetching links.
5. **A share member on an owner's screen.** No delete, upload, flag or reminder control renders for them, and a forced write's 403 `VIEW_ONLY` becomes copy, never a crash. Owner: W6 `aShareMemberSeesNoDeleteAndNoRetakeUpload`, W10 `aShareMemberSeesFlagsReadOnly`.

---

## Shared interfaces (read before any task)

**Server reads** (`medioo-web/src/api/java.ts`, W3; server-only, imports `server-only`, a dependency added in W3 at an exact version, with a Vitest alias to `src/test/empty-module.ts` so unit tests can import the file):
- `type JavaAnswer<T> = { status: 200; body: T } | { status: 400 | 401 | 403 | 404 | 409 | 413 | 415; reason: string }`.
- `javaGet<T>(path: string): Promise<JavaAnswer<T>>`: `GET ${JAVA_INTERNAL_URL}${path}`, with only the header `cookie`, copied from the incoming request when present; `cache: "no-store"`; wrapped in React `cache()`. **Preview gate:** when `requestContext().preview` is true and `path` is not `/app/api/me`, it first awaits the cached `javaGet("/app/api/me")`, and calls `notFound()` before issuing the asked path unless that answer is 200 with `preview: true`. A 401 from `me` there is `redirect(signInPath(here))`. A 5xx, any status outside the refusals, or a 200 whose body is not JSON throws `new Error("java <status> <route pattern>")`, and a network failure (no status) throws `new Error("java unreachable <route pattern>")` (W3 review, PR #138), where the pattern is the path with every UUID segment replaced by `{id}` and the query dropped; it never carries the body and has no `cause` (undici's JSON `SyntaxError` quotes the body's first characters). It is never mapped to a reason.
- `signedIn<T>(answer: JavaAnswer<T>, here: string): T`: the body for a 200; `redirect(signInPath(here))` for 401 `UNAUTHENTICATED` (`here` is `requestContext().here`, so a preview page returns to its preview path); `notFound()` for 404; throws otherwise.

**Request context** (`medioo-web/src/api/request.ts`, W3): `requestContext(): Promise<{ lang: Lang; preview: boolean; here: string }>` from the request headers `cookie` (through `langOf`), `x-medioo-preview` (`"1"` only) and `x-medioo-path` (the path and query the browser asked for, preview prefix included).

**Browser writes** (`medioo-web/src/api/browser.ts`, W3; no copy import):
- `type ApiResult<T> = { kind: "ok"; status: number; body: T | null } | { kind: "refused"; status: number; reason: string } | { kind: "signed-out" } | { kind: "offline" }`.
- `apiSend<T>(method: "POST" | "PUT" | "DELETE", path: string, body?: object | FormData): Promise<ApiResult<T>>`: always `X-Medioo: 1`; `Content-Type: application/json` on every call without `FormData`, **including a call with no body** (the guard answers 415 to a mutation with no type), with `JSON.stringify(body)` when a body is given; with `FormData`, no manual `Content-Type`; `credentials: "same-origin"`. A 401 with `reason: "UNAUTHENTICATED"` calls `window.location.assign(signInPath(location.pathname + location.search))` and resolves `signed-out`. A thrown fetch resolves `offline`.
- `apiGet<T>(path: string): Promise<ApiResult<T>>`: the same 401 rule, for the client polls (paper status) and the crop retry's session check.
- `apiUpload(form: FormData, onProgress: (sent: number, total: number) => void): Promise<ApiResult<UploadAnswerJson>>` (W7): an `XMLHttpRequest` `POST /app/api/uploads` under the same rules: `X-Medioo: 1`, no manual `Content-Type`, `withCredentials` same-origin, the 401 path, `offline` on `error`/`abort`.
  *(Amended by W7, for PO sight:)* the signature is generic, `apiUpload<T = unknown>(form, onProgress): Promise<ApiResult<T>>`, and the sheet calls it with `UploadAnswerJson`. A request that only goes quiet resolves `offline` too: upload.js's watchdog is ported (`STALL_MS` 30 s with no byte moving, `SERVER_MS` 120 s once every byte has gone, then `xhr.abort()`); no `xhr.timeout` is set. `apiSend`, `apiGet` and `apiUpload` share one answer judge (`judged`).

**Paths** (`medioo-web/src/routing/paths.ts`, W3; F-PRs edit `flipped.ts` only):
- `signInPath(next: string): string` → `"/app/?next=" + encodeURIComponent(next)`. Java's `LocalPath` checks the value when the sign-in JSON receives it; Next never judges it.
- `basicSignInPath(next: string): string` → `"/app/basic?next=" + encodeURIComponent(next)` (the `<noscript>` href; S1).
- `PREVIEW_PREFIX = "/app/preview"`; `previewPath(path: string): string` → `"/app/preview" + path.slice("/app".length)`, so `previewPath("/app")` is `"/app/preview"`.
- `NEXT_PAGES: readonly string[]`: the page patterns this build serves (`"/app"`, `"/app/verify"`, then each screen's). `routes.built.test.ts` holds it equal to the page entries of the build's `.next/app-path-routes-manifest.json`, minus `/app/healthz`, `/app/probe`, every catalog-only page and `/_not-found`, and fails too if the manifest yields no page.
- `CATALOG_ONLY_PAGES`: read by `routes.built.test.ts` from the one list in `deploy.sh` (`WEB_CATALOG_PAGES`, W4), never restated.
- `JAVA_PAGES: readonly string[]`: the `GET /app/...` patterns of `docs/infra/app-routes.txt`, held equal to that file by `routes.test.ts`.
- `FLIPPED: readonly string[]` (`src/routing/flipped.ts`): the page patterns Caddy sends to Next, held equal to the `GET` paths parsed from `docs/infra/Caddyfile.medioo` by `flipped.test.ts`.
- `href(path: string, preview: boolean): string`: `previewPath(path)` when `preview` and the path matches `NEXT_PAGES`, else `path`.
- `reachable(path: string, preview: boolean): boolean`: true when the path matches `JAVA_PAGES`, or `NEXT_PAGES` under `preview`, or `FLIPPED`. A tab or link to an unreachable page is not rendered (the স্বাস্থ্য tab before F9).

**Middleware** (`medioo-web/src/middleware.ts`, W3): deletes any incoming `x-medioo-preview` and `x-medioo-path`, sets `x-medioo-path` to the request's path and query, and for `/app/preview` or `/app/preview/<rest>` rewrites to `/app` or `/app/<rest>` with `x-medioo-preview: 1`. The CSP and headers are set exactly as today on both the rewritten and the plain response.

**The shell and the banner render per page** (W4, W5): every signed-in page renders `AppShell` itself, through one server helper `src/app/app/(signed-in)/shell.tsx › signedInPage({ tab, main, aside? })`. The helper reads `me` and `now-banner` with `javaGet` and puts `NowBanner` in the shell's `banner` slot. A page is rendered on every navigation, including a soft one, but a shared `layout.tsx` is not. A banner in the layout would therefore keep its first render until a hard load, and a NOW paper that arrived while the family moved by `<Link>` would stay hidden. `(signed-in)/layout.tsx` holds no read and no gate.
*(Amended by W7, for PO sight:)* the signature is `signedInPage({ tab, main, aside?, asksItself? })`. `asksItself` (default false) is the one exception to "a screen never decides a patient" named in Global Constraints: only `/app/add` sets it.
*(Amended by W10, approved by the PO 2026-10-23, ruling P2 on PR #146:)* the signature is `signedInPage({ tab, main, aside?, asksItself?, everyone?, asked? })`. `everyone` and `asked` are the two further exceptions named in Global Constraints: `everyone` only on `/app/family`, `asked` only on `/app/patients/{id}/profile`.
*(Amended by W6, for PO sight; not yet ruled:)* the signature is `signedInPage({ tab, main, aside?, asksItself?, everyone?, asked?, patient? })`. `patient` is the further exception named in Global Constraints: only on the paper pages (`/app/documents/{id}`, `/all`, `/values/{obs}`), with the id Java sent the paper with, or `null` for a paper that is gone.

**e2e support** (W3):
- `medioo-web/e2e/support/scenarios.mjs`: `export const SCENARIOS = { "<name>": { "GET /app/api/me": "me/one-patient.json", … } }`. Keys are `METHOD route-pattern` exactly as `docs/infra/app-routes.txt` spells them. A value is a path under `medioo-web/fixtures/api/` or a literal `{ status, body }` (for example `{ status: 401, body: { reason: "UNAUTHENTICATED" } }`). Also exported:
  - `PAGE_SCENARIOS: Record<NEXT_PAGES entry, scenario>`, read by `budget.spec.ts`.
  - `LONGEST: Record<scenario, scenario>`: each routed scenario's longest twin, whose `me` is `me/long-name.json` and whose area fixture is the area's longest real-length fixture, named per task.
  - Each screen adds its scenarios.
- `medioo-web/e2e/support/java-stub.mjs`: a Node `http` server. It picks the scenario from the `e2e_scenario` cookie (`Path=/app`). `baymax_session` means only "signed in": with no session, every route that needs one answers 401 `{"reason":"UNAUTHENTICATED"}`, and the open routes (`sign-in/request`, `sign-in/verify`, `sign-in/state`) answer from the scenario. It refuses mutations exactly as `AppApiGuard.csrf` does, in the same order:
  - no `X-Medioo: 1` → 403 `MISSING_HEADER`;
  - an `Origin` present and not equal to the Playwright base URL's origin, or absent on an open route → 403 `FORBIDDEN_ORIGIN`;
  - a `Content-Type` that is not `application/json` or `multipart/form-data` (any parameters), or none at all, also on a body-less POST or DELETE → 415 `UNSUPPORTED_MEDIA_TYPE`.

  An unmapped route is 404 `{"reason":"NOT_FOUND"}`. It records every request (method, path, query, header names, the `x-medioo`, `origin` and `content-type` values) and serves the log at `GET /__requests` (`DELETE /__requests` clears it). It logs nothing.
- `medioo-web/e2e/support/png.mjs`: `pngFor(row)` and `expectedWidth(row) = row.crop.width ?? hashWidth(cropKey(row))`, exported once and used by both the stub and every crop check. `hashWidth` maps a key to 40–400 px.
- `medioo-web/e2e/support/browser-api.ts`: `useScenario(page: Page, name: string): Promise<Recorder>` sets `e2e_scenario=<name>` and `baymax_session=e2e` for path `/app`, unless the scenario is a signed-out one (named `signed-out*`, `state-*`, or with `me` 401). It routes the browser's `**/app/api/**` from the same table, with the stub's guard rules. It routes `**/app/documents/*/image?key=*` and `**/app/documents/*/page/*` to `pngFor(row)`, sized `expectedWidth(row)` × the stored height (or the width again), so each row's image is distinguishable by its pixels. A per-key answer list (`{ "<key>": [403, 200] }`) answers in order, and a `303` entry answers `Location: /app/?next=…` as Java's image route does without a session. `Recorder.requests()` returns the browser-side requests, parsed (multipart parts by field name).
- `medioo-web/e2e/support/screen-checks.ts`: `screenChecks(route: string, scenario: string, opts?: { crops?: boolean; longest?: boolean })` registers, for both colour schemes:
  - `axeReportsNoViolation`.
  - `noCspViolation` (the `securitypolicyviolation` init script).
  - `noElementOverflowsItsBox` (`scrollWidth <= clientWidth + 1` on every element and on the page).
  - `everyFieldAndTrackDrawsTheBoundary`.
  - `noTextUnderTwelvePixels`.
  - `onlyNowComponentsPaintTheNowRed` (the rendered NOW red only on elements under a `safety/` component's root).
  - `everyTargetMeetsTheFloor` (every link, button and input at least 44×44 px; the NOW banner's actions and each page's one primary action at least 48 px).
  - `everyVisibleImageIsADecodedBrandFileOrItsOwnCrop` (each visible `<img>` decoded, `naturalWidth > 0`, and either a brand file matched by content as the catalog check does or a row's crop with `naturalWidth === expectedWidth(row)`).
  - with `crops`, `everyCropIsItsRowsOwnImage`: rows located by DOM order, since `PaperValueJson.id` is nullable. The rendered row count must equal the fixture's `values.length`, so every row is checked, not one or more. The expected widths on the page must be pairwise distinct, or the test fails as "this fixture cannot detect a swap". Each row's `<img>` has `src === row.crop.src` and `naturalWidth === expectedWidth(row)`.
  - with `longest`, the route registered a second time under `LONGEST[scenario]`. Before judging overflow, it asserts that the long name's text is present in the measured DOM, matched per element (some element's own text contains it), so the check cannot pass on short strings.

  The implementations move here from `e2e/catalog.spec.ts`, which then imports them (one copy).
- `medioo-web/e2e/board.spec.ts` (runs only with `BOARD=1`): `BOARD_SCENES: { screen: string; state: string; route: string; scenario: string; proto: { file: "phone.html" | "desktop.html"; scene: string } }[]`. For each scene it takes a screenshot at phone 390×844 and desktop 1280×800, light and dark, into `test-results/board/<screen>/` (git-ignored). It also captures the prototype at `file://…/docs/design/2026-10-21-approved/<file>` after clicking `[data-scene="<scene>"]`. The executing agent publishes the folder as a private board artifact and links it under "Pending (PO / box)". No screenshot goes into git.
- **The W1d image run** (the enforcing budget run, the built image behind the Caddy replica) reaches the stub this way: the stub runs as a `node:22` container named `java-stub` on the replica's docker network, the `medioo-web` container gets `JAVA_INTERNAL_URL=http://java-stub:8086`, and the replica's Java upstream (`medscribe-ai:8086`) is aliased to the same container. W3 adds this to W1d's runner script and states it in the PR body.

**Flip mechanics** (Task F):
- `scripts/smoke-https.sh` rows gain an optional method in the path column, `METHOD /path` (default GET, passed as `curl -X`). F3 adds the method support, with its test.
- A row whose path column starts with `PROBE ` is sent only by `caddy-route-probe.sh` and skipped by the production smoke run. Every POST row is `PROBE` except the harmless sign-in POSTs (`POST /app/login`, `POST /app/verify`).
- Every new row's expected values are measured, not guessed, as W1c did.

**Copy keys this plan drafts** (bn first, en second; each in its task, with its bundle):
- `login.nojs` "JavaScript ছাড়া ঢুকতে এখানে চাপুন" / "Sign in without JavaScript" (`ui_*`).
- `tab.health` "স্বাস্থ্য" / "Health" (`ui-shell_*`; also W9's page title, one term one key).
- `chat.ask.reply` "এই প্রশ্নের উত্তর আমি এখনো দিতে পারি না। ওষুধ বা শরীরের কোনো সমস্যা নিয়ে প্রশ্ন থাকলে ডাক্তারকে জিজ্ঞেস করুন।" / "I cannot answer questions yet. If you have a question about a medicine or a health problem, ask a doctor." (`ui-chat_*`).
- `crop.retry` "ছবিটা আনা যাচ্ছে না — আবার চেষ্টা করুন" / "The picture won't load — try again" (`ui-result_*`).
- `doc.gone` "এই কাগজটা আর নেই" / "This paper is no longer here" (`ui_*`).
- `upload.nojs` "কাগজ পাঠাতে JavaScript চালু করুন" / "Turn on JavaScript to send a paper" (`ui-upload_*`).
- `due.*` (W8, `ui-papers_*`), `health.*` (W9, `ui-result_*`) and `theme.*` (W10, `ui-family_*`).

---

## Task W3-api: The sign-in state, the checked held place, sign-out, and the no-JS fallback at `/app/basic`

Branch `feat/baymax-web-signin-state-fallback`. Lane B. S1 and S2 apply.

**Files:**
- Modify: `baymax/src/main/java/com/elioo/baymax/web/adapter/in/api/SignInApiHandler.java` (`state`, `next`, `signOut`, and `requested` extracted to a package-level static), `AppApiRouter.java` (three routes; the handlers stay on `SignInApiHandler`, so `mediooAppApiRoutes`' parameter list is unchanged and no router-building test changes), `AppApiGuard.java` (`OPEN` gains `GET /app/api/sign-in/state`; `GET /app/api/sign-in/next` and `POST /app/api/sign-out` need a session), `baymax/src/main/java/com/elioo/baymax/web/adapter/in/ui/SignInPages.java` (`routesUnder(String base)`)
- Create (tests): `baymax/src/test/java/com/elioo/baymax/web/adapter/in/api/SignInStateApiTest.java`, `SignInNextApiTest.java`, `SignOutApiTest.java`, `MeApiContractTest.java`, `SignInApiContractTest.java`; `baymax/src/test/java/com/elioo/baymax/web/adapter/in/ui/BasicSignInPagesTest.java`
- Modify (tests): `AppApiLogSafetyTest.java` (`exercise` entries), `AppApiGuardTest.java` (the new open route)
- Create (committed by hand, data only): `medioo-web/fixtures/seams/noscript-href.json`: `{"held": "/app/documents/5e9edf6d-0a2a-4913-8675-9d07a4100003", "href": "/app/basic?next=%2Fapp%2Fdocuments%2F5e9edf6d-0a2a-4913-8675-9d07a4100003"}`, read by this task's Java test and by W3's `paths.test.ts`
- Generated: `docs/infra/app-routes.txt`; `medioo-web/fixtures/api/me/` `one-patient.json`, `two-patients.json`, `no-patients.json`, `preview-on.json`, `preview-off.json`, `long-name.json`, `unauthenticated.json`; `medioo-web/fixtures/api/sign-in/` `confirm.json`, `sent.json`, `not-sent.json`, `not-sent-no-link.json`, `invalid-number.json`, `code-wrong.json`, `code-expired.json`, `code-burned.json`, `code-format.json`, `restart.json`, `verified.json`, `state-pending-sent.json`, `state-pending-not-sent.json`, `state-none.json`, `next-held.json`, `next-hostile.json`

**Interfaces:**
- Consumes: `LoginCookie.read`, `LoginCookie.nextPage`, `LoginCookie.HOME`, `WaLink.of`, `WebAuthUseCase.logout`, `SessionAuthFilter.clearedCookie`, `SignInPages` handlers, `Snapshot`.
- Produces:
  - `GET /app/api/sign-in/state` (open, no session): `{"pending": false}` without a valid `baymax_login` cookie; otherwise `{"pending": true, "sent": boolean, "number_tail": "123", "code_ttl_minutes": int, "max_attempts": int, "wa_link"?: string}`, the same fields and values `SignInApiHandler.requested` sends. It reads the cookie Java wrote; Next never parses it.
  - `GET /app/api/sign-in/next?next=<encoded>` (session): `{"next": LoginCookie.nextPage(raw).orElse(HOME)}`, the place Java checked. With no session it answers 401, as every session route does. W3's sign-in page uses it to send a signed-in visitor on without the fallback and without judging `next` itself.
  - `POST /app/api/sign-out` (session, CSRF): 204 with `SessionAuthFilter.clearedCookie()`, after `WebAuthUseCase.logout(token)`. With no session, 401 as every session route.
  - `GET /app/basic` and `GET /app/basic/` (the number page), `POST /app/basic/login`, `GET /app/basic/verify`, `POST /app/basic/verify`: `SignInPages`' existing pages and handlers, served with base `/app/basic`, so every form `action`, every redirect and the "অন্য নম্বর দিন" link stay under `/app/basic`. Cookies, `next=`, `LocalPath` checks and copy are the same code. The old `/app`, `/app/login`, `/app/verify` routes are unchanged.

- [ ] **Step 1: Write the failing tests.**
  - `SignInStateApiTest`:
    - `withoutTheLoginCookieNothingIsPending`: the body is exactly `{"pending":false}`.
    - `aSentLoginIsPendingAndSent`: a cookie from `LoginCookie.value(hash, "123", true)` gives `pending` true, `sent` true, `number_tail` "123", and `code_ttl_minutes` equal to `properties.getAuth().getOtpTtl().toMinutes()`.
    - `aNotSentLoginIsPendingAndNotSent`.
    - `theStateIsTheRequestsOwnFields`: the key set of the pending body equals the key set `POST /app/api/sign-in/request` answers for the same outcome, parsed by field.
    - `aTamperedCookieIsNotPending`.
    - `theStateNeedsNoSessionButStaysUnderTheGuard`: no session gives 200, and the `AppApiGuard` access line is present.
  - `SignInNextApiTest`:
    - `aHeldPlaceIsJavasCheckedPlace`: `?next=%2Fapp%2Fdocuments%2F<uuid>` → `{"next":"/app/documents/<uuid>"}`.
    - `aHostileNextIsHome`: parameterised with `SignInApiHandlerTest.aHostileHeldPlaceOpensHomeAndIsNeverHeld`'s values, each giving `LoginCookie.HOME`.
    - `withoutASessionIs401`.
  - `SignOutApiTest`:
    - `signOutEndsTheSessionAndClearsTheCookie`: 204; `Set-Cookie` `baymax_session=` with `Max-Age=0` and `Path=/app`; the next `GET /app/api/me` with the old token is 401.
    - `signOutWithoutXMedioIsRefused`: 403 `MISSING_HEADER`.
    - `signOutWithNoContentTypeIs415`: proves `apiSend`'s rule for a body-less POST.
    - `signOutWithoutASessionIs401`.
  - `BasicSignInPagesTest` (WebTestClient over the real routers):
    - `theBasicNumberPagePostsToBasic`: the body contains `action="/app/basic/login"` and `name="whatsapp_number"`, and no `<script`.
    - `aConfirmedNumberRedirectsToTheBasicCodePage`: 303 `Location: /app/basic/verify`.
    - `theBasicCodePagePostsToBasic`: `action="/app/basic/verify"`, and the other-number link is `href="/app/basic/"`.
    - `aNoJsSignInThroughBasicOpensTheHeldPage`: the chain starts at the exact `href` read from `medioo-web/fixtures/seams/noscript-href.json`, encoded as Next renders it. It goes number → confirm → code → `POST /app/basic/verify` with the right code, and ends in 303 `Location: <held>` with a `baymax_session` cookie. No response in the chain carries `<script`. Guard: the file's `href` starts with `/app/basic?next=%2F`.
    - `aHostileNextThroughBasicOpensHome`: the same values as above.
    - `theOldPathsAreUnchanged`: `POST /app/login` confirmed still answers 303 to `/app/verify`.
  - `MeApiContractTest` and `SignInApiContractTest`: one case per fixture file above (`me/unauthenticated` is no session → 401; `me/long-name` names the patient `CatalogFixturesExportTest`'s long name; numbers are `+8801700000xxx`), each `eachCaseIsItsCommittedMswFixture`, plus `everyCommittedFixtureIsACurrentCase` per area, as `PaperApiContractTest` does.
- [ ] **Step 2:** Run `gl :baymax:test --tests '*SignInStateApiTest' --tests '*SignInNextApiTest' --tests '*SignOutApiTest' --tests '*BasicSignInPagesTest' --tests '*MeApiContractTest' --tests '*SignInApiContractTest'`. Expected: FAIL. The state, next and sign-out routes answer 404 `NOT_FOUND` (the catch-all), `/app/basic` answers Java's 404 page, and the fixture tests fail on missing committed files.
- [ ] **Step 3: Implement.** `state` builds its pending body with the extracted `requested`. `next` reads `next` from the query, `LoginCookie.nextPage(raw).orElse(HOME)`. `SignInPages` gets `routesUnder(String base)`; `routes` composes `routesUnder(BASE)` and `routesUnder(BASE + "/basic")`, with the `/basic` set ordered before any `{variable}` route.
- [ ] **Step 4:** Run Step 2's command. Expected: the fixture tests print their `cp` commands. Run them, then run again. Expected: PASS. Run `gl :baymax:test --tests '*AppApiRouteContractTest' --tests '*AppApiLogSafetyTest' --tests '*AppApiGuardTest'`. Expected: FAIL on the snapshot and on three missing `exercise` entries. Add the entries, regenerate `docs/infra/app-routes.txt` with the printed command, and run again. Expected: PASS, and `git diff origin/main -- docs/infra/app-routes.txt | grep '^-[A-Z*]'` prints nothing.
- [ ] **Step 5: Replays.**
  - (a) `SignInPages.login` redirecting to `BASE + "/verify"` regardless of base; run `aNoJsSignInThroughBasicOpensTheHeldPage`. Expected: FAIL with `expected: "/app/basic/verify" but was: "/app/verify"`.
  - (b) `next` returning the raw value unchecked; run `aHostileNextIsHome`. Expected: FAIL naming the hostile value.

  Restore each and quote both lines.
- [ ] **Step 6:** `gl build`. Expected: BUILD SUCCESSFUL. Run the ignore check over `baymax/src medioo-web/fixtures`.
- [ ] **Step 7: Commit.**
  ```bash
  M=baymax/src/main/java/com/elioo/baymax/web/adapter/in; T=baymax/src/test/java/com/elioo/baymax/web/adapter/in
  F=medioo-web/fixtures/api
  git add $M/api/SignInApiHandler.java $M/api/AppApiRouter.java $M/api/AppApiGuard.java $M/ui/SignInPages.java \
    $T/api/SignInStateApiTest.java $T/api/SignInNextApiTest.java $T/api/SignOutApiTest.java \
    $T/api/MeApiContractTest.java $T/api/SignInApiContractTest.java $T/ui/BasicSignInPagesTest.java \
    $T/api/AppApiLogSafetyTest.java $T/api/AppApiGuardTest.java docs/infra/app-routes.txt \
    medioo-web/fixtures/seams/noscript-href.json \
    $F/me/one-patient.json $F/me/two-patients.json $F/me/no-patients.json $F/me/preview-on.json \
    $F/me/preview-off.json $F/me/long-name.json $F/me/unauthenticated.json \
    $F/sign-in/confirm.json $F/sign-in/sent.json $F/sign-in/not-sent.json $F/sign-in/not-sent-no-link.json \
    $F/sign-in/invalid-number.json $F/sign-in/code-wrong.json $F/sign-in/code-expired.json \
    $F/sign-in/code-burned.json $F/sign-in/code-format.json $F/sign-in/restart.json $F/sign-in/verified.json \
    $F/sign-in/state-pending-sent.json $F/sign-in/state-pending-not-sent.json $F/sign-in/state-none.json \
    $F/sign-in/next-held.json $F/sign-in/next-hostile.json
  git status --short -- medioo-web baymax/src scripts docs .github   # nothing outside the list
  git commit -m "feat(baymax-web): the sign-in state, the checked held place and sign-out as JSON; a no-JS sign-in fallback at /app/basic (plan W3-api)" \
    -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
  ```
  PR body: S1 and S2 under "Pending (PO / box)", the snapshot diff with the sign-off request, and the replay lines.

---

## Task W3: The screen foundation and sign-in

Branch `feat/medioo-web-signin`. Lane C. Merges after W1d and W3-api; S9 applies.

**Files:**
- Create: `medioo-web/src/api/java.ts`, `src/api/request.ts`, `src/api/browser.ts`, `src/api/java.test.ts`, `src/api/browser.test.ts`, `src/test/empty-module.ts`
- Create: `medioo-web/src/routing/flipped.ts`, `src/routing/paths.test.ts`, `src/routing/routes.test.ts`, `src/routing/flipped.test.ts`
- Modify: `medioo-web/src/routing/paths.ts` (W1d created it holding `NEXT_PAGES`; W3 adds `/app`, `/app/verify` and the rest of the Paths interface), `src/routing/built/routes.built.test.ts` (W1d created it, holding `NEXT_PAGES` equal to the manifest's pages; W3's pages must appear in both)
- Create: `medioo-web/src/components/PhoneField/PhoneFieldView.tsx`, `src/components/OtpInput/OtpInputView.tsx` (the copy-free twins); modify `PhoneField.tsx`, `OtpInput.tsx` (thin server wrappers over the twins), `PhoneField.test.tsx`, `OtpInput.test.tsx` (`theWrapperAndTheViewRenderTheSameMarkup`)
- Modify: `medioo-web/src/middleware.ts`, `src/security/csp.test.ts` (rewrite cases), `vitest.config.mts` (alias `server-only` → `src/test/empty-module.ts`), `vitest.built.config.mts` (already includes `src/**/built/**/*.built.test.ts` since W1d; no change unless it moved), `eslint.config.mjs` (`no-console: error` over `src`), `src/security/lint-bans.test.ts` (the `no-console` case), `next.config.ts` (`skipTrailingSlashRedirect: true`), `package.json` and `pnpm-lock.yaml` (`server-only` at an exact version at least two weeks old)
- Modify: `scripts/check-no-server-mutation.sh` (rule 5), `scripts/test_check_no_server_mutation.sh` (both directions)
- Create: `medioo-web/src/app/app/page.tsx` (welcome and number), `src/app/app/verify/page.tsx` (code), `src/app/app/sign-in-words.ts` (server: resolves `SignInWords` and `CodeWords`), `src/app/app/SignInFlow.tsx` and `src/app/app/CodeFlow.tsx` (client islands), `src/app/app/sign-in.css`
- Create: `medioo-web/e2e/support/scenarios.mjs`, `e2e/support/java-stub.mjs`, `e2e/support/browser-api.ts`, `e2e/support/png.mjs`, `e2e/support/screen-checks.ts`, `e2e/signin.spec.ts`, `e2e/server-log.spec.ts`, `e2e/board.spec.ts`
- Modify: `medioo-web/e2e/playwright.config.ts` (the stub web server; `JAVA_INTERNAL_URL` for `next start`; the server log teed to `test-results/next-server.log`; a `logs` project depending on `phone` and `desktop`), `e2e/catalog.spec.ts` (imports the moved checks), `e2e/budget.spec.ts` (adds `PAGE_SCENARIOS` and the guard that fails on an empty `NEXT_PAGES` or a page without a scenario; W1d already iterates `NEXT_PAGES`, applies the S9 image exclusion and requires both Noto subsets on every load), W1d's image-run script (the stub container, per Shared interfaces)
- Modify: `baymax/src/main/resources/baymax/ui_bn.properties`, `ui_en.properties` (`login.nojs`); regenerated `medioo-web/src/copy/generated/copy.bn.json`, `copy.en.json`

**Interfaces:**
- Consumes:
  - `POST /app/api/sign-in/request` (`{whatsapp_number, confirmed?, next?}` → `{confirm:{number_display}}` or `{sent, number_tail, code_ttl_minutes, max_attempts, wa_link?}`; refusal `INVALID_NUMBER`).
  - `POST /app/api/sign-in/verify` (`{code}` → `{next}`; refusals `CODE_WRONG`, `CODE_EXPIRED`, `CODE_BURNED`, `CODE_FORMAT`, `SIGN_IN_RESTART`).
  - `GET /app/api/sign-in/state`, `GET /app/api/sign-in/next` and `GET /app/api/me` (W3-api).
  - Components: `Wordmark`, `MioAvatar` (96, the welcome figure), `PhoneFieldView`, `OtpInputView` (`length={6}`, `name="code"`), `MessageBubble` (Mio's lines on the welcome), `ToastRegion`. Both inputs already draw `--boundary`.
- Produces: everything under Shared interfaces; the pages `/app` (and `/app/`) and `/app/verify`.
  - `SignInFlow({ words: SignInWords, next: string | null, verifyHref: string })` (client): the number → confirm steps. `SignInWords` holds every label and hint of `PhoneFieldView`, `login.next`, `login.number.confirm`, `login.number.yes`, `login.number.change`, the `INVALID_NUMBER` text (`login.number.invalid`) and `app.err.generic`, all resolved in `page.tsx`. `confirm.number_display` is Java's string, shown verbatim. After a `sent` or `not-sent` answer it calls `location.assign(verifyHref)`.
  - `CodeFlow({ words: CodeWords, verifyHref, numberHref })` (client): the code step. `CodeWords` is resolved in `verify/page.tsx` from the `sign-in/state` answer, so every number in it is already filled server-side: `login.code.ttl` with `code_ttl_minutes`, and `login.bad.burned` with `max_attempts`. It holds one refusal text per `CODE_*` reason, `login.code.sent`, or `login.code.say` + `login.code.word` + the `wa_link`. A `SIGN_IN_RESTART` assigns `numberHref`. A verified answer assigns the body's `next`.

**Board** (master W3): welcome, number, error, code sent, code not sent (wa.me), wrong code, done, beside `phone.html` scene `welcome` and `desktop.html` scene `signin`.

- [ ] **Step 1: Write the failing unit tests.**
  - `paths.test.ts`:
    - `signInPath("/app/documents/x?all=1")` is `"/app/?next=%2Fapp%2Fdocuments%2Fx%3Fall%3D1"`.
    - `basicSignInPath(held)` equals `href` from `fixtures/seams/noscript-href.json` (the seam W3-api's chain starts from).
    - `previewPath("/app/home")` is `"/app/preview/home"` and `previewPath("/app")` is `"/app/preview"`.
    - `href("/app/home", true)` is the preview path only when `/app/home` is in `NEXT_PAGES`; asserted with a page not in it, the result is unchanged.
    - `reachable("/app/health", false)` is false while `FLIPPED` lacks it.
  - `routes.test.ts › javaPagesAreTheSnapshotsGetRoutes`: `JAVA_PAGES` equals the `GET /app…` lines of `docs/infra/app-routes.txt` (no `/app/api`), and the parsed list is non-empty.
  - `flipped.test.ts › flippedIsTheCaddyfilesNextPaths`: the `GET` paths of every `handle` block in `docs/infra/Caddyfile.medioo` whose body is `reverse_proxy medioo-web:3000`, minus `/app/healthz`, the preview matcher and the asset paths, equal `FLIPPED`. Guard: the parser found the `/app/healthz` block, so it did not read nothing.
  - `java.test.ts` (the incoming request is stubbed with `cookie`, `x-medioo-preview: 1`, `x-medioo-path`, `authorization` and `origin`, so forwarding more than `cookie` is visible):
    - `javaGet` sends `GET` with only the `cookie` header: the stubbed `fetch`'s init has `method` "GET", header names exactly `["cookie"]`, and `cache` "no-store".
    - It maps 401 `{"reason":"UNAUTHENTICATED"}` to `{status:401, reason:"UNAUTHENTICATED"}`.
    - `aJavaErrorNamesTheStatusAndPatternOnly`: a 502 on `/app/api/documents/<uuid>?x=+8801700000123` with a body throws `"java 502 /app/api/documents/{id}"`, and the message contains neither the query nor the body.
    - `signedIn` on a 401 calls `redirect` with `signInPath(here)`.
    - `aPreviewRequestReadsMeFirstAndStopsWhenNotPreview`: under `x-medioo-preview: 1` with `me` answering `preview: false`, `javaGet("/app/api/documents/<uuid>")` calls `notFound()`, and the `fetch` spy saw only `/app/api/me`.
    - `aPreviewRequestForAPreviewFamilyReads`: with `preview: true`, two fetches in order, `me` then the path.
  - `browser.test.ts`:
    - `apiSend("PUT", "/app/api/x", {a:1})` sends `X-Medioo: 1`, `Content-Type: application/json`, and body `{"a":1}`.
    - `apiSend("POST", "/app/api/sign-out")` with no body sends `X-Medioo: 1` and `Content-Type: application/json`, with no body.
    - With `FormData`, it sets no `Content-Type`.
    - A 401 `UNAUTHENTICATED` assigns `signInPath(location.pathname + location.search)` and resolves `signed-out`.
    - A 403 `{"reason":"VIEW_ONLY"}` resolves `refused` with that reason.
    - A rejected fetch resolves `offline`.
  - `csp.test.ts` gains:
    - `aPreviewPathIsRewrittenAndMarked`: `/app/preview/home` → rewrite target `/app/home`, request header `x-medioo-preview: 1`, and the CSP set on the response.
    - `theBarePreviewPathIsRewrittenToo`: `/app/preview` → `/app`.
    - `anIncomingPreviewMarkIsDropped`: `/app/home` with `x-medioo-preview: 1` from the client reaches the page without it.
  - `lint-bans.test.ts` gains `console.log in src is refused` (`no-console`).
  - `PhoneField.test.tsx` and `OtpInput.test.tsx` gain `theWrapperAndTheViewRenderTheSameMarkup`: the wrapper's HTML equals the view's when given the same words.
  - `test_check_no_server_mutation.sh` gains two cases. `onlyJavaTsNamesTheUrl`: a tree where only `api/java.ts` names `JAVA_INTERNAL_URL` passes. `aSecondFileNamingTheUrlFails`: adding `app/x.ts` that names it fails, and the message names `app/x.ts`.
- [ ] **Step 2:** Run `pnpm -C medioo-web exec vitest run src/routing src/api src/security src/components/PhoneField src/components/OtpInput` and `bash scripts/test_check_no_server_mutation.sh`. Expected: FAIL. The vitest run fails with module-not-found for `@/routing/paths`, `@/api/java` and the two views, and the middleware cases fail on `x-medioo-preview` undefined. The script test fails `aSecondFileNamingTheUrlFails`, because the second file passes.
- [ ] **Step 3: Implement** the Shared interfaces modules, the twins, rule 5 ("the files under the scanned dir that name `JAVA_INTERNAL_URL` are exactly `api/java.ts`; any other is named"), and the middleware change. Set `skipTrailingSlashRedirect: true`, add `server-only` and the alias.
- [ ] **Step 4:** Run Step 2's commands. Expected: PASS. Run `bash scripts/check-no-server-mutation.sh`. Expected: PASS. Run `pnpm -C medioo-web build && pnpm -C medioo-web test:built`. Expected: PASS, `noClientChunkCarriesCopy` included.
- [ ] **Step 5: Write the failing e2e** `e2e/signin.spec.ts`, with scenarios `signed-out` (`me` → `me/unauthenticated.json`), `signed-in`, `state-pending-sent`, `state-pending-not-sent`, `state-none`, and each sign-in answer from `fixtures/api/sign-in/*`:
  - `bothSpellingsOfTheSignInPageAnswer200`: `GET /app` and `GET /app/` are 200 with `name="whatsapp_number"`, and neither is a 308.
  - `aSignedInVisitorIsSentToJavasCheckedPlace`: with `signed-in` (`sign-in/next` → `next-held.json`), `/app/?next=%2Fapp%2Fdocuments%2Fx` answers 307 to the `next` in that fixture. The stub's log shows `GET /app/api/sign-in/next` with the query `next=%2Fapp%2Fdocuments%2Fx` (encoded with `encodeURIComponent`, never raw). With `next-hostile.json` it lands on `/app/home`. Next never judges `next`, and the signed-in hop never goes through `/app/basic`.
  - `theNumberIsShownBackBeforeAnythingIsSent`: typing and pressing `login.next` sends one `POST /app/api/sign-in/request` without `confirmed`. The page shows `confirm.number_display` verbatim and `login.number.yes`, and only that press sends `confirmed: true`.
  - `anInvalidNumberIsSaidUnderTheField`: `INVALID_NUMBER` → PhoneFieldView's alert holds `login.number.invalid`, with `aria-invalid="true"`.
  - `sentSaysSentAndNotSentNeverDoes` (after the assign to `/app/verify`):
    - `state-pending-sent` → `login.code.sent`.
    - `state-pending-not-sent` → `login.code.say` + `login.code.word` + the `wa_link` as `href`, and no `login.code.sent` anywhere in the DOM.
    - a no-link state → no WhatsApp link.
  - `eachCodeRefusalIsItsOwnLine`: one case per `CODE_*` fixture, each asserting that OtpInputView's alert text is the key's copy (`login.bad.burned` with `max_attempts` filled by the server).
  - `restartGoesBackToTheNumber`: `SIGN_IN_RESTART` → the number step.
  - `verifiedOpensTheHeldPlace`: the `next` from the verify body is the page's `location` after the press.
  - `theCodePageSurvivesALeaveToWhatsApp`: a fresh load of `/app/verify` with `state-pending-not-sent` shows the not-sent step with `number_tail`. With `state-none` it answers 307 to `/app/`, or to `/app/preview/` under the preview path.
  - `theNextIsSentToJavaUnjudged`: `/app/?next=%2F%2Fevil.example` sends `next: "//evil.example"` in the request body. Java refuses it; Next passes it on.
  - `theNoScriptLinkCarriesTheHeldPlace` (`javaScriptEnabled: false`): `/app/?next=%2Fapp%2Fdocuments%2F<the seam's uuid>` renders, inside `<noscript>`, a link whose `href` is exactly the seam file's `href`, with the text `login.nojs`.
  - `screenChecks("/app", "signed-out")` and `screenChecks("/app/verify", "state-pending-sent")`.
  - `e2e/server-log.spec.ts › theNextServerLogCarriesNoPatientText` (project `logs`, after scenarios including `java-502` (`me` answers 502) and `paper-500` (a paper GET answers 500 with a body holding a fixture name)): `test-results/next-server.log` holds none of these:
    - the fixtures' `+8801700000…` numbers and their local forms;
    - their `number_tail` values beside "••";
    - the fixtures' names, including the 500 body's name;
    - any `code` the tests typed;
    - any query string.

    Guards: the log is non-empty, it holds the line `java 502 /app/api/me`, and the stub's `/__requests` shows at least one `GET /app/api/sign-in/state`.
- [ ] **Step 6:** `pnpm -C medioo-web build && pnpm -C medioo-web e2e e2e/signin.spec.ts`. Expected: FAIL, because `/app` is Next's 404 under the policy.
- [ ] **Step 7: Implement the pages.**
  - `page.tsx` reads `requestContext()` and `javaGet("/app/api/sign-in/next?next=" + encodeURIComponent(rawNext ?? ""))`. A 200 redirects to `href(body.next, preview)`. A 401 renders the Wordmark, Mio and `SignInFlow` with words from `sign-in-words.ts`.
  - `verify/page.tsx` reads `GET /app/api/sign-in/state` and renders `CodeFlow` with server-resolved words, or redirects to `href("/app/", preview)`.
  - The `<noscript>` link (`basicSignInPath(rawNext)`) is in both pages.
- [ ] **Step 8:** Run Step 6's command, then the whole suite: `pnpm -C medioo-web e2e`. Expected: PASS, including `catalog.spec.ts` on the moved checks and `budget.spec.ts`, with `/app` and `/app/verify` under the `route` ceiling in `budget-ceilings.json`.
- [ ] **Step 9: Replays.**
  - (a) In `java.ts`, forward every incoming header; run `java.test.ts`. Expected: `expected [ 'cookie' ] … received [ 'cookie', 'x-medioo-preview', 'x-medioo-path', 'authorization', 'origin' ]`.
  - (b) In `SignInFlow`, send `confirmed: true` on the first press; run `theNumberIsShownBackBeforeAnythingIsSent`. Expected: FAIL naming the `confirmed` field.
  - (c) Remove the `x-medioo-preview` delete in the middleware; run `anIncomingPreviewMarkIsDropped`. Expected: FAIL naming the header.
  - (d) Import `t` from `@/copy` in `SignInFlow` through a helper module; run `pnpm -C medioo-web build && pnpm -C medioo-web test:built`. Expected: `noClientChunkCarriesCopy` FAIL naming the chunk.
  - (e) Drop the gate in `javaGet`; run `aPreviewRequestReadsMeFirstAndStopsWhenNotPreview`. Expected: FAIL naming the paper path in the fetch calls.

  Restore each and quote the five lines.
- [ ] **Step 10: Gates.** The W gates. Run the ignore check over `medioo-web/src medioo-web/e2e medioo-web/fixtures scripts baymax/src`, and `git check-ignore -v medioo-web/test-results/next-server.log`, which must print the rule.
- [ ] **Step 11: Board.** `BOARD=1 pnpm -C medioo-web e2e e2e/board.spec.ts`; publish `test-results/board/signin/` privately.
- [ ] **Step 12: Commit.**
  ```bash
  W=medioo-web; R=baymax/src/main/resources/baymax
  git add $W/src/api/java.ts $W/src/api/request.ts $W/src/api/browser.ts $W/src/api/java.test.ts $W/src/api/browser.test.ts \
    $W/src/test/empty-module.ts $W/src/routing/paths.ts $W/src/routing/flipped.ts $W/src/routing/paths.test.ts \
    $W/src/routing/routes.test.ts $W/src/routing/flipped.test.ts $W/src/routing/built/routes.built.test.ts \
    $W/src/components/PhoneField/PhoneFieldView.tsx $W/src/components/PhoneField/PhoneField.tsx \
    $W/src/components/PhoneField/PhoneField.test.tsx $W/src/components/OtpInput/OtpInputView.tsx \
    $W/src/components/OtpInput/OtpInput.tsx $W/src/components/OtpInput/OtpInput.test.tsx \
    $W/src/middleware.ts $W/src/security/csp.test.ts $W/src/security/lint-bans.test.ts \
    $W/vitest.config.mts $W/vitest.built.config.mts $W/eslint.config.mjs $W/next.config.ts $W/package.json $W/pnpm-lock.yaml \
    scripts/check-no-server-mutation.sh scripts/test_check_no_server_mutation.sh \
    $W/src/app/app/page.tsx $W/src/app/app/verify/page.tsx $W/src/app/app/sign-in-words.ts \
    $W/src/app/app/SignInFlow.tsx $W/src/app/app/CodeFlow.tsx $W/src/app/app/sign-in.css \
    $W/e2e/support/scenarios.mjs $W/e2e/support/java-stub.mjs $W/e2e/support/browser-api.ts $W/e2e/support/png.mjs \
    $W/e2e/support/screen-checks.ts $W/e2e/signin.spec.ts $W/e2e/server-log.spec.ts $W/e2e/board.spec.ts \
    $W/e2e/playwright.config.ts $W/e2e/catalog.spec.ts $W/e2e/budget.spec.ts <W1d's image-run script, by its path on main> \
    $R/ui_bn.properties $R/ui_en.properties $W/src/copy/generated/copy.bn.json $W/src/copy/generated/copy.en.json
  git status --short -- medioo-web baymax/src scripts docs .github   # nothing outside the list
  git commit -m "feat(medioo-web): sign-in screens over the sign-in JSON, a preview path, and the stub-Java e2e harness (plan W3)" \
    -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
  ```
  PR body: the replay lines; `login.nojs` as a draft; S1 under "Pending (PO / box)" (S9 was accepted 2026-10-22); the board link. Note that `budget-ceilings.json` is untouched (W1d gave it the two ceilings only, per S9).

---

## Task F: Flipping a route (one PR per row of the table below)

Branch per the table at the top. Lane X. Each F PR runs after its screen PR is merged and deployed. Except F0, it also waits for the PO's on-device step. *(Amended by DR-49, 2026-10-23: no F PR waits for the on-device step any more; each runs as soon as its screen PR is merged and deployed, and quotes DR-49's ruling in place of the on-device result.)*

**Files:**
- Modify:
  - `docs/infra/Caddyfile.medioo`: the row's block, before the bare `handle`; and the header comment's "Only /app/healthz is routed so far" sentence, rewritten to name what is routed (F0: `/app/preview`, `/app/preview/*`, `/_next/static/*`, `/fonts/*`, `/brand/*`).
  - `scripts/smoke-https.sh`: rows, the header comment's "Routing stops at" line, and its header-class comment (F0 adds `static`: `/_next/static/*`, `/fonts/*`, `/brand/*`, the four Caddy/Next headers once each, no CSP required).
  - `scripts/test_smoke_https.sh`: a fake-curl case per new row, both directions.
  - `medioo-web/src/routing/flipped.ts`: the row's patterns (not F0).
  - `docs/RUNBOOK.md`: "Routed today", and the environment table's Containers cell ("from W1c: `/app/healthz` alone" becomes what is routed).
- Create (F0 only):
  - `scripts/caddy-route-probe.sh <Caddyfile.medioo> <smoke-https.sh>` and `scripts/test_caddy_route_probe.sh`; `.github/workflows/ci-cd.yml` runs the test in "Deploy script tests".
  - The probe copies the site block. It rewrites the site address `medioo.eliooo.org` to `http://127.0.0.1:<port>`, so Caddy's automatic HTTPS stays off and no row gets a 308. It replaces `reverse_proxy medscribe-ai:8086` with `respond "java" 299` and `reverse_proxy medioo-web:3000` with `respond "next" 298`, and runs the result in a throwaway Caddy container of the box's version on a local port.
  - It sends every row of the smoke table, `PROBE` rows included, with its method and path (`{old}` rows skipped). A row of class `next` or `static` must get 298, and every other row 299. It fails if it read no rows.
  - The routing is thereby derived from the same table the box run checks, not from a second list.

| PR | Caddy block | Smoke rows (expected values re-measured at Step 2) |
|---|---|---|
| F0 | `handle /_next/static/* {…}`, `handle /fonts/* {…}`, `handle /brand/* {…}`, and `@medioo_preview { path /app/preview /app/preview/* }` + `handle @medioo_preview {…}` | `/_next/static/x.js\|404\|static\|-` (Next's 404, no longer Java's JSON one); `/fonts/noto-sans-latin.woff2\|200\|static\|-`; `/brand/mio-head.webp\|200\|static\|-`; `/app/preview\|200\|next\|html:name="whatsapp_number"`; `/app/preview/\|200\|next\|html:name="whatsapp_number"`; `/app/api/me\|401\|api\|json:reason=UNAUTHENTICATED` (Java still owns the API) |
| F3 | `@medioo_signin { method GET HEAD; path /app /app/ /app/verify }` + `handle @medioo_signin {…}` | `/app/` class `page` → `next`; `/app/verify\|307\|next\|location:/app/`; `/app/basic\|200\|page\|html:action="/app/basic/login"` (S1); `/lang/en?back=%2Fapp%2Fbasic%2Fverify\|303\|-\|location:/app/basic/verify` (S1: a language switch on the fallback's code step lands back on it, PR #136); `POST /app/verify\|303\|-\|location:/app/`; `POST /app/login\|200\|page\|html:name="whatsapp_number"` |
| F5 | `@medioo_chat { method GET HEAD; path /app/home }` | `/app/home\|307\|next\|location:/app/?next=%2Fapp%2Fhome` |
| F6 | `@medioo_paper { method GET HEAD; path_regexp ^/app/documents/[0-9a-f-]{36}(/all\|/values/[0-9a-f-]{36})?$ }` | the paper, `/all` and `/values/<uuid>` each `307\|next\|location:/app/?next=…`; and Java still answers, class `page` or `-` as measured: `/app/documents/<uuid>/image?key=x`, `/page/1`, `/status`, `/delete` (GET), and `PROBE POST /app/documents/<uuid>/delete` |
| F7 | `@medioo_add { method GET HEAD; path /app/add }` | `/app/add\|307\|next\|location:/app/?next=%2Fapp%2Fadd`; `PROBE POST /app/add` still Java's |
| F8 | `@medioo_papers { method GET HEAD; path /app/timeline }` | `/app/timeline\|307\|next\|…`; `/app/patients/<uuid>` still Java's 303 |
| F9 | `@medioo_health { method GET HEAD; path /app/health }` | `/app/health\|307\|next\|…` (Java's 404 before) |
| F10 | `@medioo_family { method GET HEAD; path /app/family }` and `@medioo_profile { method GET HEAD; path_regexp ^/app/patients/[0-9a-f-]{36}/profile$ }` | `/app/family\|307\|next\|…`; `/app/patients/<uuid>/profile\|307\|next\|…`; `PROBE POST /app/patients/<uuid>/profile` still Java's |

- [ ] **Step 1: Preconditions,** each pasted into the PR body:
  - The screen's squash sha is an ancestor of the running image: `curl -s https://medioo.eliooo.org/app/healthz | jq -r .sha`, then `git merge-base --is-ancestor <screen sha> <that sha> && echo deployed`. Expected: `deployed`.
  - (not F0) The PO's on-device result, quoted: Tanvir opened the screen's preview path on his phone, with his family in `BAYMAX_WEB_PREVIEW_FAMILIES` (the box step below), in both themes, and said yes. Until then the PR stays open with the step under `box_steps_pending`. *(Amended by DR-49, 2026-10-23: replaced by the PO's ruling, quoted from DR-49.)*
  - (F3 only) The S1 and S3 rulings, quoted.
- [ ] **Step 2: Measure.** Against production, run `curl -s -o /dev/null -D - <path>` (GET and HEAD only) for every non-`PROBE` row's path, before the change, and record each status and `Location`. `PROBE` rows and F0's Next rows are measured through W1d's local Caddy replica against the deployed sha's image, never by a POST to production.
- [ ] **Step 3: Write the rows first.**
  - In `smoke-https.sh`, F3 adds the method support and the `PROBE` skip. Add each row's case to `test_smoke_https.sh` in both directions: the expected answer passes, and the pre-flip Java answer fails, naming the row.
  - F3 also adds `aProductionRowNeverPostsToAMutatingPath`: the case fails if a non-`PROBE` row POSTs to a path matching `/delete|/add|/profile`, and it is shown failing on a planted row.
  - Run `bash scripts/test_smoke_https.sh`. Expected: PASS.
  - Run `bash scripts/smoke-https.sh https://medioo.eliooo.org`. Expected: FAIL on exactly the new Next rows, every other row PASS.
- [ ] **Step 4: Edit** `Caddyfile.medioo` and (not F0) `flipped.ts`.
  - Run `pnpm -C medioo-web exec vitest run src/routing/flipped.test.ts`. Expected: PASS.
  - Validate the file in a throwaway Caddy of the box's version: `docker run --rm -v "$PWD/docs/infra/Caddyfile.medioo:/etc/caddy/Caddyfile:ro" caddy:<box version> caddy validate --config /etc/caddy/Caddyfile`. Expected: `Valid configuration`.
  - Run `bash scripts/caddy-route-probe.sh docs/infra/Caddyfile.medioo scripts/smoke-https.sh`. Expected: every row PASS.
  - F0 writes the probe first, with its test in both directions:
    - a correct file passes;
    - a file routing `/app/api/*` to Next fails on the `/app/api/me` row;
    - a file whose preview block is `handle /app/preview/* {…}` only fails on the `/app/preview` row;
    - `theProbeSpeaksPlainHttp`: the probe's request to `/app/healthz` answers 298, not 308.
- *(Amended by W6-api review, corrected by W6-fix, for PO sight:)* **F6 does not change the answer for rejected papers (W6-fix measured 404 on both sides).** Java's HTML sheet `/app/documents/{id}/values/{obs}` answers 404 for a NEEDS_RETAKE, FAILED or still-being-read paper's stored row, with no view recorded: the intake's view of a paper that is not DONE carries no rows (D2), and since W6-fix the sheet's own status gate refuses it first (see W6-fix below). ~~Today Java's HTML sheet answers 200 for a NEEDS_RETAKE or FAILED paper's stored row~~: that 200 came from a mocked read, never from a real database. Next's sheet reads `GET /app/api/documents/{id}/values/{obs}`, which is 404 for the same papers, so after F6 the same WhatsApp-reachable URL is still a not-found page for them. F6's Step 2 records one such URL's 404 before the flip and its 404 after; F6's PR body expects 404 on both sides and announces no behaviour change for these papers. ~~F6's PR states the change.~~
- [ ] **Step 5 (F6 only): Replay Review Focus 1.** Replace the `path_regexp` with `path /app/documents/*` and run the route probe. Expected: `FAIL /app/documents/<uuid>/image?key=x got 298 (next), expected 299 (java)`. Restore; quote the line.
- [ ] **Step 6: Box (operator, `box_steps_pending`):** RUNBOOK "Routing a path to medioo-web" steps 2–6 with this PR's file: drift check, tenants' codes, backup, in-place write, validate, reload, tenants again, the full table PASS, drift check with the PR's file. Paste the result lines.
- [ ] **Step 7: Merge** (by the PO's merge rule; this plan's pipeline does not merge), then RUNBOOK step 7: record the routes floor with the squash sha and the row's path label, and post `cat ~/medioo-routes-floor` on the PR.
- [ ] **Step 8: Commit** (before Step 6):
  ```bash
  git add docs/infra/Caddyfile.medioo scripts/smoke-https.sh scripts/test_smoke_https.sh docs/RUNBOOK.md \
    medioo-web/src/routing/flipped.ts   # not F0
  # F0 also: scripts/caddy-route-probe.sh scripts/test_caddy_route_probe.sh .github/workflows/ci-cd.yml
  git status --short -- medioo-web baymax/src scripts docs .github   # nothing outside the list
  git commit -m "feat(infra): Caddy sends <paths> to medioo-web (plan <F n>)" \
    -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
  ```

**The preview family (once, before W3's on-device step; operator):** this is a flip-class box edit, so the RUNBOOK flip rule applies: hold every merge from the env edit until the redeploy is healthy, because a CI deploy racing a hand redeploy is why `deploy-is-tip.sh` exists. The steps:
- add Tanvir's family id to `BAYMAX_WEB_PREVIEW_FAMILIES` in `/home/ec2-user/medscribe.env` (back it up first, bare comma-separated UUIDs, `cat -A` check, RUNBOOK's env table);
- redeploy the tip sha (`deploy-is-tip.sh` first);
- confirm `GET /app/api/me` answers `preview: true` for him.

The `false` case is confirmed with a real non-allowlisted account the PO names, or with the startup WARN count plus `MeApiContractTest`'s `preview-on` and `preview-off` fixtures. A family is never created in production for this. Listed under `box_steps_pending` by W3 and F0.

---

## Task W4: The shell — tabs, three columns, the patient switcher, never guessing, and the 401 flow

Branch `feat/medioo-web-shell`. Lane C. No route of its own; its board renders inside a catalog-only page that W5 replaces.

**Files:**
- Create: `medioo-web/src/app/app/(signed-in)/layout.tsx` (no read, no gate; the group only), `src/app/app/(signed-in)/shell.tsx` (`signedInPage`), `src/app/app/(signed-in)/shell-words.ts` (server: `ShellWords`), `src/app/app/(signed-in)/shell-test/page.tsx` (exists only in the catalog build, `MEDIOO_CATALOG=1`, holding the three slots' outlines for the board and the overflow checks)
- Create: `medioo-web/src/components/AppShell/AppShell.tsx`, `AppShell.css`, `AppShell.test.tsx`; `src/components/TabBar/TabBar.tsx`, `TabBar.css`, `TabBar.test.tsx`; `src/components/PatientSwitcher/PatientSwitcher.tsx` (client), `PatientSwitcher.css`, `PatientSwitcher.test.tsx`; `src/components/PatientChooser/PatientChooser.tsx`, `PatientChooser.css`, `PatientChooser.test.tsx`; `src/components/safety/UrgentMark/UrgentMark.tsx`, `UrgentMark.css`, `UrgentMark.test.tsx`; `src/app/app/(signed-in)/active-patient.ts`, `active-patient.test.ts`; `medioo-web/e2e/shell.spec.ts`
- Modify: `medioo-web/eslint.config.mjs` and `src/security/lint-bans.test.ts` (a `Link` without `prefetch={false}` is refused), `src/api/types.ts` and `src/api/types.test.ts` (`MeJson`, pinned by `fixtures/api/me/*`), `e2e/support/scenarios.mjs` (`me-one`, `me-two`, `me-none`, `me-long`, `me-preview-off`), `e2e/playwright.config.ts` (the `catalog` project also runs `shell.spec.ts`), `e2e/catalog-off.spec.ts` (gains `/app/shell-test`)
- Modify: `deploy.sh` (`WEB_CATALOG_PAGES="/app/catalog-test /app/shell-test"`, and `WEB_CATALOG_PROBE` requires a 404 without catalog states on every page in it), `scripts/test_deploy_sh.sh` and `scripts/deploy_test_fakes.sh` (a fake that serves `/app/shell-test` fails the probe, naming the page)
- Modify: `baymax/src/main/resources/baymax/ui-shell_bn.properties`, `ui-shell_en.properties` (`tab.health`); regenerated `medioo-web/src/copy/generated/copy.bn.json`, `copy.en.json`

**Interfaces:**
- Consumes: `GET /app/api/me` (`{family:{id, owner_name}, patients:[MePatient…], active_patient_id, preview}`), `PUT /app/api/active-patient` (`{patient_id}` → 204 + `baymax_patient`), `POST /app/api/sign-out` (W3-api). Components: `Wordmark`, `MioAvatar` (32 in the toolbar), `SheetDialog` (the switcher on phone), `ToastRegion`. The switcher's red mark is `me.patients[].urgent`, drawn by `UrgentMark` as the NOW token's dot + shape + the word from `attn.strip`.
- Produces:
  - `activePatient(me: MeJson, asked: string | null): { kind: "patient"; patient: MePatient } | { kind: "choose" } | { kind: "none" } | { kind: "not-found" }`. The order is that of `ActivePatient.resolve`: `asked` (`?patient=`) if it is in `me.patients`, else `not-found`; then `active_patient_id` if it is still in the list; then the only patient; then `choose` (two or more) or `none` (zero).
  - `signedInPage({ tab, main, aside? })` (server): reads `requestContext`, `me` (through `javaGet`, so the preview gate applies) and, from W5, `now-banner`, and returns `AppShell`. *(Amended by W10, approved by the PO 2026-10-23, ruling P2:)* `signedInPage({ tab, main, aside?, asksItself?, everyone?, asked? })`: `everyone` keeps the chooser from replacing a page about every patient (and decides nobody); `asked` is a patient id the page's own address names, resolved through `activePatient` in place of `?patient=`. *(Amended by W6, for PO sight; not yet ruled:)* `signedInPage({ …, patient? })`: `patient` is the id Java sent a paper with, resolved through `activePatient` in place of `?patient=` and the remembered choice (a paper page, never the chooser); `null` names nobody.
  - `AppShell({ lang, preview, me, tab, main, aside?, banner? })` (server). Below 900 px it shows main, then the bottom `TabBar`. From 900 to 1199 px a toolbar button (`aria-expanded`, `aria-controls`) shows the sidebar. At 1200 px and wider the columns are sidebar 256 · main · aside 400. `banner` renders above everything.
  - `TabBar({ lang, preview, current })`: চ্যাট `/app/home`, কাগজপত্র `/app/timeline`, স্বাস্থ্য `/app/health`, পরিবার `/app/family`. Each is rendered only when `reachable(path, preview)`, links to `href(path, preview)`, carries `aria-current="page"` when current, and is 48 px tall.
  - `PatientSwitcher({ words: { label, title, closeLabel, refused, patients: { id, name, urgentWord? }[] }, current, preview })` (client): every word resolved by `shell-words.ts`, including `app.err.notfound` as `refused`, `switch.*`, and `attn.strip` per urgent patient.

**Board** (master W4): layouts at 390, 1024 and 1440 px wide × 0, 1 and 2 patients, light and dark, beside `phone.html` scene `chat` (the tab bar) and `desktop.html` scene `app` (the columns).

- [ ] **Step 1: Write the failing tests.**
  - `active-patient.test.ts`: each branch once with the `me/*` fixtures; and `aRememberedPatientWhoIsGoneIsForgottenNotGuessed` (`active_patient_id` not in the list, two patients → `choose`).
  - `TabBar.test.tsx`:
    - `theHealthTabIsAbsentUntilItsRouteIsReachable`: not preview, with `FLIPPED` lacking `/app/health`, gives three links; preview with `/app/health` in `NEXT_PAGES` gives four, the fourth `/app/preview/health`.
    - `everyTabSaysItsWord`: each link's name is its `tab.*` copy.
  - `PatientChooser.test.tsx`:
    - `theChooserListsEveryPatientAndPicksNone`: one link per patient, `href` `?patient=<id>`, none marked current.
    - `anUrgentPatientIsMarkedByShapeAndWord`: the NOW dot plus the `attn.strip` text, never colour alone.
  - `PatientSwitcher.test.tsx`:
    - `choosingPutsTheChoiceAndNavigates`: one `apiSend("PUT", "/app/api/active-patient", {patient_id})`, then a navigation to the same tab with `?patient=`.
    - `aRefusedChoiceSaysSo`: a 404 shows the `refused` prop's text in a status line, and nothing navigates.
  - `UrgentMark.test.tsx › anUrgentMarkIsDotShapeAndWord`.
  - `lint-bans.test.ts`: `a Link without prefetch={false} is refused` and `a Link with prefetch={false} is clean`.
  - `test_deploy_sh.sh › aCatalogShellTestPageFailsTheProbe`.
  - `e2e/shell.spec.ts`:
    - `theLayoutIsPhoneBelow900`: at 899 px the TabBar is visible and there is no sidebar.
    - `theSidebarHidesBehindTheToolbarFrom900To1199`: at 1024 px the sidebar is hidden until the toolbar button is pressed, and `aria-expanded` follows.
    - `threeColumnsFrom1200`: at 1440 px the sidebar is 256 px and the aside 400 px wide, measured by `getBoundingClientRect`.
    - `twoPatientsAndNoChoiceShowTheChooser`.
    - `anUnknownPatientParamIsNotFound`: `?patient=<other uuid>` gives the 404 page.
    - `aNonPreviewFamilyGets404OnThePreviewPath`: scenario `me-preview-off`, `/app/preview/shell-test` gives 404, and the stub's log holds only `GET /app/api/me`.
    - `noSessionRedirectsWithTheHeldPlace`: `/app/preview/shell-test?x=1` with no session gives 307 `Location: /app/?next=%2Fapp%2Fpreview%2Fshell-test%3Fx%3D1`.
    - `aMidPage401GoesToSignInWithThePlace` (master Review Focus 4, client side): the stub answers the switcher's PUT with 401, and the page lands on `signInPath` of the page it was on.
    - `signOutEndsAtTheSignInPage`: one `POST /app/api/sign-out` with `X-Medioo` and `Content-Type: application/json`, which the stub's guard accepts, then `/app/`.
    - `screenChecks("/app/shell-test", "me-one", { longest: true })` at 390, 1024 and 1440, where `LONGEST["me-one"]` is `me-long`.
- [ ] **Step 2:** `pnpm -C medioo-web exec vitest run src/components/AppShell src/components/TabBar src/components/PatientChooser src/components/PatientSwitcher src/components/safety/UrgentMark src/app src/security/lint-bans.test.ts` and `bash scripts/test_deploy_sh.sh`. Expected: FAIL on missing modules, on the `Link` lint case reporting nothing, and on the deploy probe accepting `/app/shell-test`.
- [ ] **Step 3: Implement** the shell helper, `activePatient`, the components and the deploy list. CSS uses only tokens. The switcher's urgent mark is the only NOW use, and it lives in `src/components/safety/UrgentMark`, where the NOW-name ban allows it.
- [ ] **Step 4:** Run Step 2's commands, then `pnpm -C medioo-web build:catalog && pnpm -C medioo-web test:built && pnpm -C medioo-web e2e e2e/shell.spec.ts`. Expected: PASS, `noClientChunkCarriesCopy` included.
- [ ] **Step 5: Replays.**
  - (a) `activePatient` returning the first patient instead of `choose`; run `twoPatientsAndNoChoiceShowTheChooser`. Expected: FAIL naming the chooser heading `switch.title` as absent.
  - (b) `apiSend` ignoring 401; run `aMidPage401GoesToSignInWithThePlace`. Expected: FAIL naming the URL.
  - (c) `PatientSwitcher` opening `Sheet` instead of `SheetDialog`; run `pnpm -C medioo-web lint`. Expected: the `CLIENT_COPY_MESSAGE` error naming the file.

  Restore each and quote all three.
- [ ] **Step 6: Gates.** The W gates, plus `bash scripts/test_deploy_sh.sh`. **Board**: `BOARD=1 …`, then publish `test-results/board/shell/`.
- [ ] **Step 7: Commit.**
  ```bash
  W=medioo-web; S='medioo-web/src/app/app/(signed-in)'; C=medioo-web/src/components; R=baymax/src/main/resources/baymax
  git add "$S/layout.tsx" "$S/shell.tsx" "$S/shell-words.ts" "$S/shell-test/page.tsx" \
    "$S/active-patient.ts" "$S/active-patient.test.ts" \
    $C/AppShell/AppShell.tsx $C/AppShell/AppShell.css $C/AppShell/AppShell.test.tsx \
    $C/TabBar/TabBar.tsx $C/TabBar/TabBar.css $C/TabBar/TabBar.test.tsx \
    $C/PatientSwitcher/PatientSwitcher.tsx $C/PatientSwitcher/PatientSwitcher.css $C/PatientSwitcher/PatientSwitcher.test.tsx \
    $C/PatientChooser/PatientChooser.tsx $C/PatientChooser/PatientChooser.css $C/PatientChooser/PatientChooser.test.tsx \
    $C/safety/UrgentMark/UrgentMark.tsx $C/safety/UrgentMark/UrgentMark.css $C/safety/UrgentMark/UrgentMark.test.tsx \
    $W/eslint.config.mjs $W/src/security/lint-bans.test.ts $W/src/api/types.ts $W/src/api/types.test.ts \
    $W/e2e/support/scenarios.mjs $W/e2e/playwright.config.ts $W/e2e/catalog-off.spec.ts $W/e2e/shell.spec.ts \
    deploy.sh scripts/test_deploy_sh.sh scripts/deploy_test_fakes.sh \
    $R/ui-shell_bn.properties $R/ui-shell_en.properties $W/src/copy/generated/copy.bn.json $W/src/copy/generated/copy.en.json
  git status --short -- medioo-web baymax/src scripts docs .github deploy.sh   # nothing outside the list
  git commit -m "feat(medioo-web): the shell — tabs, three columns, a switcher that never guesses, sign-in on a 401 (plan W4)" \
    -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
  ```
  PR body: the `tab.health` draft, the replays, the board.

---

## Task W5-api: The chat thread — `GET /app/api/patients/{id}/thread`

Branch `feat/baymax-web-api-thread`. Lane B. S4, S10, S11 and S13 apply.

**Files:**
- Create: `baymax/src/main/java/com/elioo/baymax/web/adapter/in/api/ThreadApiHandler.java`, `ThreadJson.java`; `baymax/src/main/java/com/elioo/baymax/web/application/port/in/ThreadUseCase.java`; `web/application/service/ThreadService.java`; `web/application/port/out/ThreadReadPort.java`; `web/adapter/out/persistence/PostgresThreadReadAdapter.java`; `web/domain/ThreadItem.java`
- Modify: `ResultUseCase.java` and `ResultService.java` (`card`), `AppApiRouter.java` (one route and one handler parameter)
- Modify (tests that build `mediooAppApiRoutes` and break on the new parameter): `PaperApiContractTest.java`, `UploadApiRealServerTest.java`, `HealthApiContractTest.java`, `SignInApiHandlerTest.java`, `UploadApiHandlerTest.java`, `AppApiGuardTest.java`, `B3Handlers.java`; and `AppApiLogSafetyTest.java`, `NowBannerApiTest.java` (one case)
- Create (tests): `baymax/src/test/java/com/elioo/baymax/web/adapter/in/api/ThreadApiContractTest.java`, `ThreadApiAuditTest.java`; `web/adapter/out/persistence/PostgresThreadReadAdapterTest.java`
- Generated: `docs/infra/app-routes.txt`; `medioo-web/fixtures/api/thread/` `default.json`, `now.json`, `now-abroad.json`, `retake.json`, `failed.json`, `first-run.json`, `share-member.json`, `second-page.json`

**Interfaces:**
- Consumes: `PaperJson.of`, `PaperJson.reading`, `PaperJson.withNames`, `HealthRecordPort.visiblePatient(familyId, patientId)` (the visibility seam: an empty answer is 404 before any thread SQL runs; the thread SQL then filters by that patient id, so `PostgresHealthRecordAdapter`'s private `VISIBLE_SQL` is never needed), `outbound_message` (V9, V13, V14, V23), `document` (B4c's delete).
- Produces:
  - `ResultUseCase.card(UUID familyId, UUID documentId): Mono<Object>`: a `ResultView` or `ReadingView`, read through the same visibility as `result`, **recording no audit row**.
  - `GET /app/api/patients/{id}/thread?cursor=` → `{"items": [...], "next_cursor": string|null}`, newest first, 20 items a page. An item is one of:
    - `{"kind": "paper", "at": <received_at>, "card": <PaperJson body with names>}`;
    - `{"kind": "message", "at": <sent_at>, "message_kind": "explanation"|"detail"|"retake"|"nudge"|"failure", "document_id": string|null, "body": string}`.

    A message is listed only when all of these hold:
    - `sent_at IS NOT NULL AND delivery_status = 'sent'`: the provider accepted it, with a wamid by V14's second CHECK. `sent_at` alone is not enough, because the log adapter sets it with `delivery_status` NULL (S11).
    - `patient_id` is this patient.
    - `family_id` is the viewer's own account (S4).
    - `document_id IS NULL OR EXISTS (SELECT 1 FROM document d WHERE d.id = document_id)` (S10).

    `body` is the stored text as sent. A patient this family cannot see is 404 `NOT_FOUND`; a malformed id or cursor is 400 `INVALID`.

- [ ] **Step 1: Write the failing tests.**
  - `PostgresThreadReadAdapterTest` (Testcontainers):
    - `onlyDeliveredMessagesAreListed`: these rows are absent, matched by id: pending, rejected, failed_safety, a failed delivery, and **a logged outcome (`sent_at` set, `delivery_status` NULL, as `PostgresOutboundMessageAdapter` writes it)**. The delivered one is present.
    - `everyOutboundKindIsListedWithItsKind`: the allowed kinds are read from `pg_constraint` (`outbound_message_kind_check`, parsed from `pg_get_constraintdef`), with a guard that the parsed set is non-empty. One delivered row of every kind is inserted, and each is listed with `message_kind` equal to its stored kind.
    - `aDeletedPapersMessagesAreNotListed` (S10): a delivered explanation for a paper, then `PostgresDocumentRecordAdapter.deleteDocument` on it; the message's id is absent.
    - `aNudgeWithNoDocumentIsStillListed` (S10, the other direction).
    - `anotherAccountsMessagesAreNotListed`: the share member's read of the owner's patient lists the papers and none of the owner's messages.
    - `papersAndMessagesInterleaveByTime`: three of each with interleaved instants come back in descending `at`, by id.
    - `theCursorPagesWithoutRepeatsOrGaps`: 45 items give pages of 20, 20 and 5, every id once.
  - `ThreadApiContractTest`:
    - one case per fixture; `eachCaseIsItsCommittedMswFixture`; `everyCommittedFixtureIsACurrentCase`.
    - `noDeliveryOrGateFieldReachesTheBrowser`: no key `wamid`, `delivery_status`, `reviewer`, `reject_reason`, `gate_status`, `confidence`, `model` or `status_reason` anywhere in the parsed tree, walked by field.
    - `aPaperCardIsThePaperJsonsOwnBody`: each `card` equals `GET /app/api/documents/{id}`'s body for the same paper, parsed, field by field.
    - `everyOutboundKindHasAMessageKind`: derived from the same `pg_constraint` read, with the same non-empty guard; every allowed kind maps to a `message_kind` string the JSON can carry.
    - `aThreadCardForAViewerAbroadOffersNo999` (`now-abroad`, a `+44` share member): `now.call_999 == false` on every card, by field.
    - `aPatientOfAnotherFamilyIsNotFound`.
  - `ThreadApiAuditTest` (Testcontainers) `readingTheThreadRecordsNoView`: the count of `audit_event` rows with `kind = 'document_view'` (V8, queried by column) is unchanged across a thread read of a patient with five papers. Guard: one `GET /app/api/documents/{id}` in the same test adds exactly one.
  - `NowBannerApiTest › anAckWithoutALiveSessionIs401AndWritesNoAck` (Testcontainers, master Review Focus 3, Java half): a `PUT /app/api/now-banner/ack` with an expired session, and one with a signed-out token, each answer 401 `UNAUTHENTICATED`, and the `now_ack` row count is unchanged. This test is written after the code, so it gets its own replay in Step 5.
- [ ] **Step 2:** `gl :baymax:test --tests '*Thread*' --tests '*NowBannerApiTest'`. Expected: FAIL. The route answers the catch-all 404 and the adapter class does not exist. The ack case passes at once, which is expected for a test of existing behaviour, so its proof is the Step 5 replay.
- [ ] **Step 3: Implement** the port, the adapter (one SQL over `document` and `outbound_message` for the patient `visiblePatient` returned), the service, `card` and the handler. Register the route before the catch-all, and add the handler to every router-building test listed.
- [ ] **Step 4:** Run Step 2's command, copy the fixtures with the printed `cp` commands, and run again. Expected: PASS. Then run `gl :baymax:test --tests '*AppApiRouteContractTest' --tests '*AppApiLogSafetyTest'`, regenerate the snapshot, and run again. Expected: PASS.
- [ ] **Step 5: Replays.**
  - (a) Filter on `sent_at IS NOT NULL` alone; run `onlyDeliveredMessagesAreListed`. Expected: FAIL naming the logged row's id.
  - (b) Make `card` call `result`; run `readingTheThreadRecordsNoView`. Expected: `expected: 0 but was: 5`.
  - (c) Drop the `document` existence clause; run `aDeletedPapersMessagesAreNotListed`. Expected: FAIL naming the message id.
  - (d) Map kinds through a hand list without `failure`; run `everyOutboundKindIsListedWithItsKind`. Expected: FAIL naming `failure`.
  - (e) Add `PUT /app/api/now-banner/ack` to `AppApiGuard.OPEN`; run `anAckWithoutALiveSessionIs401AndWritesNoAck`. Expected: FAIL on the status, `expected: 401` with the actual status named. Confirm that the message names the status, not an exception inside the test.

  Restore each and quote all five.
- [ ] **Step 6:** `gl build`. Ignore check over `baymax/src medioo-web/fixtures`. **Commit:**
  ```bash
  M=baymax/src/main/java/com/elioo/baymax/web; T=baymax/src/test/java/com/elioo/baymax/web; F=medioo-web/fixtures/api/thread
  git add $M/adapter/in/api/ThreadApiHandler.java $M/adapter/in/api/ThreadJson.java $M/application/port/in/ThreadUseCase.java \
    $M/application/service/ThreadService.java $M/application/port/out/ThreadReadPort.java \
    $M/adapter/out/persistence/PostgresThreadReadAdapter.java $M/domain/ThreadItem.java \
    $M/application/port/in/ResultUseCase.java $M/application/service/ResultService.java $M/adapter/in/api/AppApiRouter.java \
    $T/adapter/in/api/ThreadApiContractTest.java $T/adapter/in/api/ThreadApiAuditTest.java \
    $T/adapter/out/persistence/PostgresThreadReadAdapterTest.java $T/adapter/in/api/NowBannerApiTest.java \
    $T/adapter/in/api/AppApiLogSafetyTest.java $T/adapter/in/api/PaperApiContractTest.java \
    $T/adapter/in/api/UploadApiRealServerTest.java $T/adapter/in/api/HealthApiContractTest.java \
    $T/adapter/in/api/SignInApiHandlerTest.java $T/adapter/in/api/UploadApiHandlerTest.java \
    $T/adapter/in/api/AppApiGuardTest.java $T/adapter/in/api/B3Handlers.java docs/infra/app-routes.txt \
    $F/default.json $F/now.json $F/now-abroad.json $F/retake.json $F/failed.json $F/first-run.json \
    $F/share-member.json $F/second-page.json
  git status --short -- medioo-web baymax/src scripts docs .github   # nothing outside the list
  git commit -m "feat(baymax-web): the chat thread JSON — accepted messages and paper cards, no view recorded (plan W5-api)" \
    -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
  ```
  PR body: S4, S10, S11 and S13 under "Pending (PO / box)", the snapshot sign-off request, and the replays.

---

## Task W5: চ্যাট — the thread, the global NOW banner with দেখেছি, the honest composer

Branch `feat/medioo-web-chat`. Lane C. After W4 and W5-api. S8 applies.

**Files:**
- Create: `medioo-web/src/app/app/(signed-in)/home/page.tsx`, `home/chat-words.ts` (server: `ComposerWords`); `src/components/Thread/Thread.tsx`, `Thread.css`, `Thread.test.tsx`; `src/components/Composer/Composer.tsx` (client), `Composer.css`, `Composer.test.tsx`; `src/components/AttentionStack/AttentionStack.tsx`, `AttentionStack.css`, `AttentionStack.test.tsx`; `src/components/safety/NowBanner/NowBannerHold.tsx` (client: holds the post-ack state around the server-rendered banner); `medioo-web/e2e/chat.spec.ts`
- Modify:
  - `src/app/app/(signed-in)/shell.tsx`: reads `now-banner` and fills `banner` on every signed-in page.
  - `src/components/safety/NowBanner/NowBanner.tsx`: stays a server component, renders `NowBannerSeen` with a `live` flag, and drops the function prop.
  - `src/components/safety/NowBanner/NowBannerSeen.tsx`: sends through `apiSend`, so a 401 goes to sign-in, and hands the answer to `NowBannerHold`'s context.
  - `NowBanner.test.tsx`.
  - `src/components/safety/VerdictCard/VerdictCard.tsx`: the thread's additions from PR #127's amendment: `values[]` chips through `StatusChip` with Java's `chip_key`, `MioAvatar`, and the card as a link to the paper.
  - `src/api/types.ts` (`ThreadJson`, with `message_kind` including `failure`, and `AttentionJson`).
  - `e2e/support/scenarios.mjs`, `src/routing/paths.ts` (`NEXT_PAGES` gains `/app/home`).
  - `baymax/src/main/resources/baymax/ui-chat_bn.properties`, `ui-chat_en.properties` (`chat.ask.reply`).
  - `baymax/src/test/java/com/elioo/baymax/web/adapter/in/ui/WebCopyExportTest.java` and `web/adapter/in/ui/UiCopy.java`: `safety.red_flag_line` is exported from `messages_{bn,en}.properties` verbatim, as the urgency lines are, with one source per string.
  - Regenerated `medioo-web/src/copy/generated/copy.bn.json`, `copy.en.json`.

**Interfaces:**
- Consumes: `GET /app/api/me`, `GET /app/api/now-banner` (`{show, line_key, call_999, documents[], seen[]}`), `PUT /app/api/now-banner/ack` (`{seen}` → the banner re-read), `GET /app/api/patients/{id}/attention` (`{items:[{kind: urgent|retake|follow_up|reading, …, action_key}]}`), `GET /app/api/patients/{id}/thread` (W5-api). Components:
  - `NowBanner`, `NowBannerSeen` (W2d-b) and `VerdictCard` (W2d-a).
  - `MessageBubble`: `recv` for Medioo, `sent` for the family's own typed line.
  - `MioAvatar`, still on every NOW surface.
  - `StatusChip` and `ToastRegion`.

  The composer field paints `--fill` and draws `--boundary`.
- Produces:
  - the page `/app/home` (`?patient=` honoured);
  - `Thread({ items, lang, preview })` (server);
  - `Composer({ words: ComposerWords, owner, patientId, preview })` (client). `ComposerWords` is `{ field, send, standing: t(lang,'chat.soon'), reply: t(lang,'chat.ask.reply') + "\n" + t(lang,'safety.red_flag_line'), camera }`, and the reply is assembled in `chat-words.ts` on the server. `chat.soon` stays the standing line under the field, as Java's `ChatView` shows it. The reply after a send is the WhatsApp fallback's safety content: the decline, "ask a doctor", and the red-flag line appended by code, not left to the copy.
  - `NowBannerHold({ children })` (client): renders the server banner passed as `children` until an ack answers `show: false`, then renders nothing. An ack answering `show: true` calls `router.refresh()`, so the server banner is re-rendered from Java's answer. The server component `NowBanner` stays server-only and passes no function to a client component.

**Board** (master W5): default, NOW, retake, first run, beside `phone.html` scenes `chat`, `now`, `retake`, `first` and `desktop.html` scenes `app`, `first`.

- [ ] **Step 1: Write the failing tests.**
  - `Thread.test.tsx`:
    - `aMessageBodyIsShownAsSentAndNothingElse`: the `body` text verbatim in a `recv` bubble, and no other text in it.
    - `aFailureNoticeIsListedLikeAnyMessage`: the `failed` fixture's `message_kind: "failure"` renders its body.
    - `aPaperCardIsVerdictCardOverItsCard`.
    - `aRoutinePaperCarriesNoChip`: the `routine-released` card renders no `.status-chip`.
    - `everyValueChipIsTheOneJavaNamed`: for each `card.values[]`, the chip's text is `t(lang, chip_key)`, by row.
    - `thePendingWordingIsNeverShown`: a `this-week-pending` card renders no `wording` text, and does render the `result.gate.pending` line.
  - `Composer.test.tsx`:
    - `askingSendsNothingAndRepliesAsWhatsAppDoes`: type a fictional question and press send. A `sent` bubble shows the typed text, then a `recv` bubble shows the `reply` prop. The `fetch` spy was called zero times.
    - `theNotYetReplyCarriesTheRedFlagLine`: rendered with `chat-words.ts`' real words, the reply bubble's text contains `t(lang,'safety.red_flag_line')` and `t(lang,'chat.ask.reply')`, in both languages.
    - `theTypedTextIsNotStored`: `localStorage`, `sessionStorage` and `document.cookie` are unchanged after a send.
    - `onlyTheOwnerHasTheCamera`.
  - `AttentionStack.test.tsx`:
    - `cardsComeInJavasOrderEachWithOneAction`: the `full-stack` fixture's order is kept, and each card has exactly one link, named `attn.open`.
    - `anEmptyStackIsSilence`: no heading and no reassurance text.
  - `NowBanner.test.tsx` gains `theServerBannerPassesNoFunction` (a render with `live` carries no function prop to the island).
  - `e2e/chat.spec.ts`:
    - `theNowLineIsShippedVerbatimAndTheNameIsTheTitles`: the alert's sentence is exactly `t("bn","urgency.now")`, and the patient's name appears in the title element and not inside that sentence.
    - `999OnlyWhenJavaSaysBangladesh`: `now-banner/one` has a `tel:999` link, and `now-banner/abroad` has none anywhere on the page.
    - `noTelLinkInTheThreadAbroad`: with `thread/now-abroad`, the page has no `tel:` link.
    - `seenSendsTheSeenSetAsReceived`: the PUT body deep-equals the fixture's `seen`, parsed.
    - `seenHidesOnlyWhatTheServerSays`: when the stub answers `acked.json`, the banner is gone, and the patient's red mark in the switcher stays.
    - `aStaleSeenKeepsTheBanner`: when the stub answers `show: true`, the banner is still shown.
    - `aSeenTapOnAnExpiredSessionGoesToSignInAndKeepsTheBanner` (Review Focus 3): the stub answers the PUT with 401. When that response arrives, the banner is still rendered, not optimistically hidden, and the location becomes `signInPath("/app/home")`.
    - `theBannerIsOnEveryPage`: with the NOW scenario, the alert is present on every signed-in entry of `NEXT_PAGES`, iterated (guard: non-empty), and on `/app/shell-test` in the `catalog` project.
    - `aNowArrivingDuringSoftNavigationShowsTheBanner`: start on a no-NOW scenario, switch the stub's `now-banner` answer to `now-banner/one`, navigate client-side by a tab link, and the alert appears with no hard load.
    - `mioDoesNotMoveOnNow`: the computed `animation-name` of every Mio under the banner is `none`.
    - `aFirstRunPatientGetsTheIntroAndOneCamera`: the `chat.intro` bubble, and one camera link, for the owner only.
    - `aNowResultIsReadableWithoutJavaScript` (`javaScriptEnabled: false`): the banner's sentence, the NOW card and `now.notdoctor` render, and the seen button is absent.
    - `theDisclaimerIsOnThePage`: the `disclaimer` copy is present whenever a result card is.
    - `screenChecks("/app/home", "chat-default", { longest: true, crops: true })`, and the same for `chat-now`. `LONGEST` uses `me-long` and `thread/second-page`.
- [ ] **Step 2:** Run `pnpm -C medioo-web exec vitest run src/components/Thread src/components/Composer src/components/AttentionStack src/components/safety`, `gl :baymax:test --tests '*WebCopyExportTest'`, and then `pnpm -C medioo-web build && pnpm -C medioo-web e2e e2e/chat.spec.ts`. Expected: FAIL. The vitest run fails on missing modules and the copy export on the missing `safety.red_flag_line`; then `/app/home` is Next's 404.
- [ ] **Step 3: Implement.** The page reads `me`, `attention` and `thread` with `javaGet` in parallel, and the banner comes from `signedInPage`. Main holds the greeting (`chat.hello` with `owner_name`, `chat.hello.plain` without), the attention stack, the thread and the composer. At 1200 px and wider, the aside holds the attention stack and main holds the thread. The camera opens `/app/add?patient=<id>` until W7, through `href`.
- [ ] **Step 4:** Run Step 2's commands. Expected: PASS. Run `pnpm -C medioo-web test:built`. Expected: PASS. The built-CSS NOW check sees the new classes only under `safety/`, and `noClientChunkCarriesCopy` sees no copy in `Composer`'s or `NowBannerHold`'s chunk.
- [ ] **Step 5: Replays.**
  - (a) `NowBannerSeen` sending `seen` rebuilt from `documents`; run `seenSendsTheSeenSetAsReceived`. Expected: FAIL naming `reasons_digest`.
  - (b) The banner rendered from `call_999: true` regardless; run `999OnlyWhenJavaSaysBangladesh`. Expected: FAIL naming `tel:999`.
  - (c) The composer `fetch`ing `/app/api/chat`; run `askingSendsNothingAndRepliesAsWhatsAppDoes`. Expected: `expected 0 calls, received 1`.
  - (d) `chat-words.ts` building `reply` from `chat.ask.reply` alone; run `theNotYetReplyCarriesTheRedFlagLine`. Expected: FAIL naming the missing red-flag line.
  - (e) The banner moved back into `(signed-in)/layout.tsx`; run `aNowArrivingDuringSoftNavigationShowsTheBanner`. Expected: FAIL, the alert not found.
  - (f) `NowBannerHold` hiding on tap before the answer; run `aSeenTapOnAnExpiredSessionGoesToSignInAndKeepsTheBanner`. Expected: FAIL naming the alert as absent at the 401.

  Restore each and quote all six.
- [ ] **Step 6: Gates.** The W gates, plus `gl build`. **Board** `test-results/board/chat/`. **Commit:**
  ```bash
  W=medioo-web; S='medioo-web/src/app/app/(signed-in)'; C=medioo-web/src/components; R=baymax/src/main/resources/baymax
  U=baymax/src/main/java/com/elioo/baymax/web/adapter/in/ui; UT=baymax/src/test/java/com/elioo/baymax/web/adapter/in/ui
  git add "$S/home/page.tsx" "$S/home/chat-words.ts" "$S/shell.tsx" \
    $C/Thread/Thread.tsx $C/Thread/Thread.css $C/Thread/Thread.test.tsx \
    $C/Composer/Composer.tsx $C/Composer/Composer.css $C/Composer/Composer.test.tsx \
    $C/AttentionStack/AttentionStack.tsx $C/AttentionStack/AttentionStack.css $C/AttentionStack/AttentionStack.test.tsx \
    $C/safety/NowBanner/NowBannerHold.tsx $C/safety/NowBanner/NowBanner.tsx $C/safety/NowBanner/NowBannerSeen.tsx \
    $C/safety/NowBanner/NowBanner.test.tsx $C/safety/VerdictCard/VerdictCard.tsx \
    $W/src/api/types.ts $W/e2e/support/scenarios.mjs $W/src/routing/paths.ts $W/e2e/chat.spec.ts \
    $R/ui-chat_bn.properties $R/ui-chat_en.properties $U/UiCopy.java $UT/WebCopyExportTest.java \
    $W/src/copy/generated/copy.bn.json $W/src/copy/generated/copy.en.json
  git status --short -- medioo-web baymax/src scripts docs .github   # nothing outside the list
  git commit -m "feat(medioo-web): চ্যাট — the thread as delivered, the global NOW banner with দেখেছি, a composer that declines as WhatsApp does (plan W5)" \
    -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
  ```
  PR body, under "Pending (PO / copy owner)":
  - `chat.ask.reply`, with both strings, and the assembled reply as a family sees it.
  - `now.banner.seen`, already on main from W2d-b: its label is Q4's and awaits the copy ruling here.
  - Why the composer needs `chat.ask.reply` beside the existing `chat.soon`. `chat.soon` is the standing hint and carries no doctor pointer; the reply has to say what `wa.fallback` says. The choice goes to the PO under S8.

  Also: the replays, the board, and the on-device step for F5.

---

## Task W6-api: One value's sheet — `GET /app/api/documents/{id}/values/{obs}`

Branch `feat/baymax-web-api-value-sheet`. Lane B.

**Files:**
- Modify: `baymax/src/main/java/com/elioo/baymax/web/adapter/in/api/PaperApiHandler.java` (`valueSheet`, a method on the existing handler, so `mediooAppApiRoutes`' parameter list is unchanged and no router-building test changes), `PaperJson.java` (a public `valueRow` used by `of` and the sheet), `AppApiRouter.java` (the route, before `/documents/{id}`)
- Modify (tests): `AppApiLogSafetyTest.java`
- Create (tests): `baymax/src/test/java/com/elioo/baymax/web/adapter/in/api/ValueSheetApiContractTest.java`
- Generated: `docs/infra/app-routes.txt`; `medioo-web/fixtures/api/value-sheet/` `inside.json`, `outside-high.json`, `unknown-one-sided.json`, `earlier-three.json`, `earlier-none.json`, `not-found.json`
- *(Amended by W6-api, for PO sight:)* also generated `no-history.json` (a marker that did not canonicalise: no line) and `earlier-other-unit.json` (read before only in another unit: no line); also modified `ResultUseCase.java` and `ResultService.java` (`earlierHistory`), created `web/domain/EarlierHistory.java`, and modified (tests) `EarlierReadingsTest.java`; `docs/MEDIOO.md` gains the route's entry.

**Interfaces:**
- Consumes: `ResultUseCase.earlierReadings(familyId, documentId, observationId)` (404 for another family and for an observation not on this paper, before anything is read), then `ResultUseCase.result` (one DOCUMENT_VIEW, as the HTML sheet records).
  *(Amended by W6-api, for PO sight:)* `ResultUseCase.earlierHistory`, the same read and the same 404s, which also says whether the marker was read before in another unit; then `ResultUseCase.status`, which records nothing: a paper that is not DONE (NEEDS_RETAKE, FAILED, still being read) is 404 `NOT_FOUND` with no view recorded, because `PaperJson` never sends such a paper's rows.
- Produces: `{"paper": {id, document_type, date, date_is_upload_day, date_uncertain, patient:{id, name, name_possessive}}, "value": <the PaperJson value row: id, name, value, unit, ref_low, ref_high, display_kind, side, chip_key, pin, in_now_block, crop>, "earlier": [{document_id, observation_id, value, unit, crop:{src, width, height}, date, date_is_upload_day}]}`.
  *(Amended by W6-api, for PO sight:)* plus `"earlier_line_key"`: `null` | `"sheet.earlier.none"` | `"sheet.earlier"` | `"sheet.earlier.nounit"`, the heading line the HTML sheet draws. `null` when the marker did not canonicalise or was read before only in another unit, because Medioo cannot say "the first time" then; `sheet.earlier.none` only when no earlier reading of the marker exists in any unit. An earlier paper's crop size is its ledger's stored size, never measured from the sheet; null when unknown.

- [ ] **Step 1: Write the failing tests** in `ValueSheetApiContractTest`:
  - one case per fixture.
  - `theValueIsThePapersOwnRow`: equal, field by field, to the row with that id in `GET /app/api/documents/{id}`.
  - `anObservationOfAnotherPaperIsNotFoundAndRecordsNoView`: 404 `NOT_FOUND`, and the `audit_event` count with `kind = 'document_view'` unchanged.
  - `oneSheetReadIsOneView`: that count rises by exactly 1.
  - `everyEarlierReadingCarriesItsOwnCrop`: each `crop.src` names that reading's document id.
  - `aOneSidedInsideIsUnknownOnTheSheetToo`.
- [ ] **Step 2:** `gl :baymax:test --tests '*ValueSheetApiContractTest'`. Expected: FAIL. `GET /app/api/documents/{id}/values/{obs}` answers the catch-all 404.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** Run, copy the fixtures, and run again. Expected: PASS. Then run the route and log-safety tests and regenerate the snapshot.
- [ ] **Step 5: Replay** `anObservationOfAnotherPaperIsNotFoundAndRecordsNoView` with `result` read before `earlierReadings`. Expected: `expected: 0 but was: 1`.
- [ ] **Step 6:** `gl build`, then commit:
  ```bash
  M=baymax/src/main/java/com/elioo/baymax/web/adapter/in/api; T=baymax/src/test/java/com/elioo/baymax/web/adapter/in/api
  F=medioo-web/fixtures/api/value-sheet
  git add $M/PaperApiHandler.java $M/PaperJson.java $M/AppApiRouter.java $T/ValueSheetApiContractTest.java \
    $T/AppApiLogSafetyTest.java docs/infra/app-routes.txt $F/inside.json $F/outside-high.json \
    $F/unknown-one-sided.json $F/earlier-three.json $F/earlier-none.json $F/not-found.json
  # (Amended by W6-api, for PO sight:) also $F/no-history.json $F/earlier-other-unit.json,
  # baymax/src/main/java/com/elioo/baymax/web/{application/port/in/ResultUseCase.java,application/service/ResultService.java,domain/EarlierHistory.java},
  # baymax/src/test/java/com/elioo/baymax/extraction/adapter/out/persistence/EarlierReadingsTest.java, docs/MEDIOO.md and this plan
  git status --short -- medioo-web baymax/src scripts docs .github   # nothing outside the list
  git commit -m "feat(baymax-web): one value's sheet as JSON, the earlier readings each with its crop (plan W6-api)" \
    -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
  ```

### Open work found by W6-api's review: W6-fix, `fix/baymax-web-value-sheet-gates` *(Amended by W6-api review, for PO sight)*

~~Two defects are live in production~~ One defect was live in production (2) on the HTML sheet (`DocumentPages.valueSheetPage` and `valueSheet`), which families open from WhatsApp today; defect 1 was reachable only through a mocked read (W6-fix's measurement below). W6-api fixed both only on its JSON route. CLAUDE.md: a fix scoped to the path under discussion leaves the defect live on every other path.
1. **A rejected read's row opens a sheet.** ~~A NEEDS_RETAKE or FAILED paper's stored row is served with its chip and crop, and its view is recorded.~~ Not reachable on a real database: such a paper's HTML sheet was already 404 with no view (D2). W6-fix states the rule in the sheet's own gate anyway.
2. **"The first time" when it is not.** The HTML says `sheet.earlier.none` whenever no reading in this unit exists, including when the marker was read before in another unit.

The fix is one value-sheet read in `ResultUseCase` that both handlers go through. It refuses anything not DONE before the result is read, and it carries `EarlierHistory`, so the HTML draws no line when `readInAnotherUnit` is true. There are tests on the HTML route and on the JSON route, each replayed red against the current HTML. It lands before F6.

*(Amended by W6-fix, for PO sight:)* Measured on a real database before the fix, defect 2 is live and defect 1 is not reachable today. A NEEDS_RETAKE, FAILED or PROCESSING paper's stored row already answered 404 on the HTML route with no view recorded, because `DocumentIntakeService.read` gives every paper that is not DONE a status-only view with no rows (D2), so the HTML's history read found no such observation. W6-api's review saw defect 1 through a mocked `ResultUseCase` that served rows for such a paper. W6-fix still states rule 1 in `ResultUseCase.valueSheet`'s status gate, so it no longer depends on that intake detail. Rule 1's gate is held by `ResultServiceTest` (a view that does carry the row, every status but DONE derived from the enum, the gate's own reason `paper_not_done` asserted) plus a path assertion over Postgres: `ValueSheetGatesAcceptanceTest` calls `ResultUseCase.valueSheet` for every status but DONE and asserts `paper_not_done`, not `observation_not_found`, which is what the intake's D2 layer alone answers. The route-level part of that acceptance test holds rule 1's outcome on both routes (404, no view), which D2 alone would also produce; it holds rule 2 on both routes outright. F6 still sees the HTML URL answer 404 for such papers, as it already does.

---

## Task W6: The paper — summary, `/all`, one value, every gate state, delete

Branch `feat/medioo-web-paper`. Lane C. Owns master Review Focus 2 and 5, and this plan's Review Focus 4.

**Files:**
- Create: `medioo-web/src/app/app/(signed-in)/documents/[id]/page.tsx`, `documents/[id]/all/page.tsx`, `documents/[id]/values/[obs]/page.tsx`, `documents/[id]/paper-read.ts` (the one cached read), `documents/[id]/paper-words.ts` (server: `CropWords`, `DeleteWords`, `ZoomWords`)
- Create: `medioo-web/src/components/Paper/PaperSummary.tsx`, `PaperAll.tsx`, `ValueSheet.tsx`, `Paper.css`, `Paper.test.tsx`; `src/components/Crop/Crop.tsx` (client: the retry state), `Crop.css`, `Crop.test.tsx`; `src/components/PageImage/PageImage.tsx` (client: zoom in a `SheetDialog`), `PageImage.css`; `src/components/ReadingProgress/ReadingProgress.tsx` (client: polls status), `ReadingProgress.css`, `ReadingProgress.test.tsx`; `src/components/DeletePaper/DeletePaper.tsx` (client), `DeletePaper.css`, `DeletePaper.test.tsx`; `src/components/safety/NowBlock/NowBlock.tsx`, `NowBlock.css`, `NowBlock.test.tsx`; `medioo-web/e2e/paper.spec.ts`
- Modify: `baymax/src/test/java/com/elioo/baymax/web/adapter/in/api/PaperApiContractTest.java` (cases `withheld-rejected`, `failed-safety`, `fifty-eight-values-multipage`), generated `medioo-web/fixtures/api/documents/withheld-rejected.json`, `failed-safety.json`, `fifty-eight-values-multipage.json`; `medioo-web/src/catalog/states.tsx` (a VerdictCard state per new fixture, since `states.test.tsx` builds VerdictCard ids from that directory)
- Modify: `src/api/types.ts` (`ValueSheetJson`), `e2e/support/scenarios.mjs`, `src/routing/paths.ts`, `baymax/src/main/resources/baymax/ui-result_bn.properties`, `ui-result_en.properties` (`crop.retry`), `ui_bn.properties`, `ui_en.properties` (`doc.gone`), regenerated `medioo-web/src/copy/generated/copy.bn.json`, `copy.en.json`

**Interfaces:**
- Consumes:
  - `GET /app/api/documents/{id}` (PaperJson): `status`; `gate`; `wording`, only for RELEASED; `review_hours`, only for PENDING; `banner`; `now.{notdoctor_key, flags_go_key, bring_key, call_999, remote_share}`; `reasons`; `counts`; `values[]`; `medicines[]`; `follow_up[]`; `doctor`; `retake.{reason_key, ordinal}`; `owner`; `viewer.bangladesh`; `pages[]`.
  - `GET /app/api/documents/{id}/status` (polled, records nothing).
  - `GET /app/api/documents/{id}/values/{obs}` (W6-api).
    *(Amended by W6-api, for PO sight:)* `ValueSheetJson` in `src/api/types.ts` carries `earlier_line_key: null | "sheet.earlier.none" | "sheet.earlier" | "sheet.earlier.nounit"`. The earlier heading renders that key and nothing else: it is never derived from `earlier.length` (an empty `earlier` with a null key draws no line at all, never "the first time"). A 404 from this route, a rejected or unfinished paper's value included, is the not-found page.
  - `GET /app/api/documents/{id}/delete-token` → `DELETE /app/api/documents/{id}` `{confirm_token}`.
  - Images go to Java's `/app/documents/{id}/image?key=` and `/page/{n}`, which are never flipped.
  - Components:
    - `VerdictCard`.
    - `StatusChip`, with Java's `chip_key` through `chipKindOf(display_kind, side)`.
    - `MioAvatar`, still on NOW.
    - `SheetDialog`, for zoom and the delete confirm.
    - `ToastRegion`, for "deleted".
    - `MessageBubble`, for the released wording (`result.says.label` + `result.says.note`).
- Produces: `paperRead(id: string)`, the `javaGet` of the paper, cached for the request, so the layout, the page and the metadata share one read. The client islands take resolved words:
  - `Crop({ src, width, height, documentId, words: { retry, alt } })`. On a failed retry it checks the session with `apiGet("/app/api/documents/{id}/status")`; a 401 there goes to sign-in with the place held, through `apiGet`'s rule.
  - `DeletePaper({ documentId, words: { open, question, warn, yes, no, gone, refused } })`.
  - `PageImage({ src, words: { zoom, closeLabel } })`.

**Board** (master W6): RELEASED, PENDING, withheld (`withheld-rejected`, `failed-safety`), NOW, NOW abroad, retake (third), failed, reading, multi-page `/all`, a value's sheet, delete confirm, gone. Beside `phone.html` scenes `now`, `retake` and `desktop.html` scene `app` (the paper column).

- [ ] **Step 1: Write the failing tests.**
  - First run `gl :baymax:test --tests '*PaperApiContractTest'` and `pnpm -C medioo-web exec vitest run src/catalog/states.test.tsx` with the three new cases and the states added. Expected: FAIL on the missing committed fixtures, then (after the `cp`) on the missing catalog states.
  - `Paper.test.tsx`, one per fixture of `fixtures/api/documents/`:
    - `wordingOnlyWhenReleased`: the wording text is present for `routine-released` and absent in every other fixture's render.
    - `pendingSaysWhoAndHowLong`: `result.gate.pending` with `review_hours`.
    - `aWithheldPaperShowsNoWording`: `withheld-rejected` and `failed-safety`.
    - `aRoutinePaperIsFiledAndUnchipped`.
    - `theCountsLineIsJavas`: the rendered counts line equals `t(lang, counts.line_key, values, outside)` exactly, by element.
    - `aSidelessOrOneSidedValueIsUnknown`: `display_kind` unknown gives the `unk` chip and `sheet.unknown.why`, never `value.chip.inside`.
    - `medicinesAreTheirWrittenFieldsVerbatim`: each rendered medicine shows exactly the fields present in its JSON object, in `PaperDisplay.MEDICINE_FIELDS` order, and its crop. Each field element's `textContent` strictly equals the JSON string, with no trim, digit change or translation.
  - `NowBlock.test.tsx`:
    - `theNowBlockSaysNotDoctorItself`: `now.notdoctor` is inside the block, not only in the footer.
    - `abroadGetsTheRemoteLinesAndNo999`: `now-abroad` gives `now.remote.flags.go`, `now.bring.remote` and the share action, and no `tel:`.
    - `theBlockRowsAreThoseJavaFlagged`: only `in_now_block` rows, in Java's order.
    - `reasonsAreInJavasOrder`.
  - `Crop.test.tsx`:
    - `aCropThatFailsShowsRetryNeverABlank`: an `error` event shows `words.retry` with a retry button.
    - `aFailedRetryChecksTheSession`: a second `error` calls `apiGet` on the status path once.
    - `aCropIsLaidOutAtItsStoredSize`: `width`/`height` attributes come from the JSON; null gives none.
  - `DeletePaper.test.tsx`:
    - `deleteAsksThenDeletesWithTheToken`: the button opens a `SheetDialog` with `words.question` / `words.warn`, then `words.yes` sends a GET for the token, then a DELETE with `{confirm_token}`.
    - `confirmRequiredStartsAgain`: a 400 `CONFIRM_REQUIRED` gets a fresh token on the next yes.
  - `ReadingProgress.test.tsx › pollsStatusAndRefreshesWhenDone`: with fake timers, one `GET …/status` every 5 s; `DONE` calls `router.refresh()` once, and polling stops.
  - `e2e/paper.spec.ts`:
    - `everyValuesImageIsItsOwnRowsCrop`: the `crops` check of `screenChecks` (DOM order, every row, pairwise-distinct `expectedWidth`), on `now-bd`, `routine-released` and `fifty-eight-values-multipage`.
    - `anExpiredCropShowsRetry` (master Review Focus 2): the stub answers one crop key 403. That row shows `crop.retry`, and every other row's image has `naturalWidth === expectedWidth(row)`.
    - `theRetryFetchesTheImageAgain`: that key answers `[403, 200]`. After pressing `crop.retry`, the browser has made two requests for the key, and the row's `<img>` has `naturalWidth === expectedWidth(row)`.
    - `aCropAfterTheSessionEndedLeadsToSignIn`: the key answers `[303, 303]` (to `/app/?next=…`, as Java's image route does without a session), and the status path answers 401. After retry, the page is at `signInPath` of the paper.
    - `aDeletedPaperSaysItIsGone` (master Review Focus 5): the paper GET answers 404 `NOT_FOUND`, which shows `doc.gone` and a link back, not an error page.
    - `aDeleteRacedByAnotherMemberSaysGone`: the DELETE answers 404, which shows `doc.gone`.
    - `oneOpenIsOneDocumentRead` (Review Focus 4): open the paper from a paper link, then go to `/all` and back. The stub's `/__requests` holds exactly one `GET /app/api/documents/{id}` per navigation that opened the paper, and none from hovering or scrolling past paper links. This request count is the web half; Java's `PaperApiHandlerTest › openingAPaperRecordsExactlyOneView` (one GET is one `audit_event` row) is the other half, on which the master plan's audit-derived proof rests.
    - `aNonPreviewFamilyOnAPreviewPaperCausesNoPaperRead` (Review Focus 4): scenario `me-preview-off`, `/app/preview/documents/<id>`, gives a 404, and the stub's `/__requests` holds no `GET /app/api/documents/`.
    - *(Amended by W5's review, for PO sight:)* the NOW banner is on every signed-in page, and a stale দেখেছি tap (Java answers `show: true`) makes `NowBannerHold` call `router.refresh()`, which re-runs the page's server reads. On the paper page that would re-read `GET /app/api/documents/{id}` and record a second DOCUMENT_VIEW the family did not make (Review Focus 4). W5's `chat.spec.ts › aStaleSeenReReadsNoPaper` already runs over every signed-in `NEXT_PAGES` entry under its `PAGE_SCENARIOS` scenario's stale twin (`withStaleNowName`) and fails on any `GET /app/api/documents/…` the refresh makes, so this task inherits it the moment `/app/documents/{id}` joins `NEXT_PAGES` (give the pattern a fixture id in `signedInPages`). W6 makes it pass by rendering a stale answer on the paper page without re-reading the paper (for example the hold re-rendering only the banner there), never by loosening the check. A stale tap must still never ack a paper whose patient the banner did not name (DR-41 item 5), which is why W5 did not simply send Java's new `seen` on the next tap.
    - `aShareMemberSeesNoDeleteAndNoRetakeUpload`: with `owner: false` there is no `detail.delete` and no `retake.again`, and `retake.viewonly` is shown.
    - `aPaperIsReadableWithoutJavaScript` (`javaScriptEnabled: false`): values, chips, crops and the NOW block render, and the delete button is absent.
    - `anExpiredSessionOnAPaperWithoutJavaScriptReachesTheFallback` (Review Focus 2): with no JS and no session, the paper answers 307 to `/app/?next=%2Fapp%2Fdocuments%2F<id>`, whose `<noscript>` link is `basicSignInPath` of the paper.
    - `zoomOpensThePageImage`.
    - `aValueSheetListsEarlierReadingsEachWithItsCrop`.
    - `screenChecks` for the paper (`now-bd`, `routine-released`, `retake-third`), for `/all` (`fifty-eight-values-multipage`, `KNOWN_OVER` on Java; the `route` ceiling under S9's image rule, or the PR stops and says so) and for a value sheet, each with `crops: true, longest: true`. `LONGEST` uses `me-long` and `fifty-eight-values-multipage`.
- [ ] **Step 2:** Run the Vitest and Playwright commands for these files. Expected: FAIL on missing modules, then on Next's 404.
- [ ] **Step 3: Implement.** Pages read `paperRead(id)` only. Paper links everywhere use `prefetch={false}`, which the lint enforces.
- [ ] **Step 4:** Run. Expected: PASS. `pnpm -C medioo-web test:built`: PASS, with no copy in the `Crop`, `DeletePaper` or `PageImage` chunks.
- [ ] **Step 5: Replays.**
  - (a) `Crop` rendering the first crop of the paper for every row; run `everyValuesImageIsItsOwnRowsCrop`. Expected: FAIL naming the row's position and both `src` values.
  - (b) Two variants, each run against `oneOpenIsOneDocumentRead` with the expected failure `expected 1 … received 2`: a timeline paper `<Link>` with `prefetch` left on (the lint disabled for the replay), and `ReadingProgress` calling `apiGet` on `/app/api/documents/{id}` instead of `/status`.
  - (c) `wording` rendered whenever present, in a mutated fixture with gate `PENDING`; run `wordingOnlyWhenReleased`. Expected: FAIL naming the gate.
  - (d) The preview gate kept only in `(signed-in)/layout.tsx` and removed from `javaGet`; run `aNonPreviewFamilyOnAPreviewPaperCausesNoPaperRead`. Expected: FAIL naming `GET /app/api/documents/<id>` in the request log.
  - (e) A field formatter that localises digits (`1+0+1` → Bangla digits); run `medicinesAreTheirWrittenFieldsVerbatim`. Expected: FAIL naming the field.
  - (f) A retry that only resets `src`; run `theRetryFetchesTheImageAgain`. Expected: FAIL with `expected 2 … received 1` requests.

  Restore each and quote all of them.
- [ ] **Step 6: Gates.** The W gates, plus `gl build`. **Board** `test-results/board/paper/`. **Commit:**
  ```bash
  W=medioo-web; D='medioo-web/src/app/app/(signed-in)/documents/[id]'; C=medioo-web/src/components
  R=baymax/src/main/resources/baymax; T=baymax/src/test/java/com/elioo/baymax/web/adapter/in/api; F=medioo-web/fixtures/api/documents
  git add "$D/page.tsx" "$D/all/page.tsx" "$D/values/[obs]/page.tsx" "$D/paper-read.ts" "$D/paper-words.ts" \
    $C/Paper/PaperSummary.tsx $C/Paper/PaperAll.tsx $C/Paper/ValueSheet.tsx $C/Paper/Paper.css $C/Paper/Paper.test.tsx \
    $C/Crop/Crop.tsx $C/Crop/Crop.css $C/Crop/Crop.test.tsx $C/PageImage/PageImage.tsx $C/PageImage/PageImage.css \
    $C/ReadingProgress/ReadingProgress.tsx $C/ReadingProgress/ReadingProgress.css $C/ReadingProgress/ReadingProgress.test.tsx \
    $C/DeletePaper/DeletePaper.tsx $C/DeletePaper/DeletePaper.css $C/DeletePaper/DeletePaper.test.tsx \
    $C/safety/NowBlock/NowBlock.tsx $C/safety/NowBlock/NowBlock.css $C/safety/NowBlock/NowBlock.test.tsx \
    $T/PaperApiContractTest.java $F/withheld-rejected.json $F/failed-safety.json $F/fifty-eight-values-multipage.json \
    $W/src/catalog/states.tsx $W/src/api/types.ts $W/e2e/support/scenarios.mjs $W/src/routing/paths.ts $W/e2e/paper.spec.ts \
    $R/ui-result_bn.properties $R/ui-result_en.properties $R/ui_bn.properties $R/ui_en.properties \
    $W/src/copy/generated/copy.bn.json $W/src/copy/generated/copy.en.json
  git status --short -- medioo-web baymax/src scripts docs .github   # nothing outside the list
  git commit -m "feat(medioo-web): the paper — every gate state, NOW and abroad, each value beside its own crop, delete with a token (plan W6)" \
    -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
  ```
  PR body: the `crop.retry` and `doc.gone` drafts; the replays; the board; the `/all` budget number.

---

## Task W7: Upload — the sheet, the page tray, drag and drop, retry with the same key

Branch `feat/medioo-web-upload`. Lane C. Owns master Review Focus 3. Java keeps `POST /app/add` until W-del.

**Files:**
- Create: `medioo-web/src/app/app/(signed-in)/add/page.tsx` (the sheet as a page; `<noscript>` says `upload.nojs`), `add/upload-words.ts` (server: `UploadWords`, including `reasons: Record<string, string>`); `src/components/Upload/UploadSheet.tsx` (client), `PageTray.tsx`, `DropZone.tsx`, `UploadProgress.tsx`, `Upload.css`, `upload-reasons.ts` (server-side map, imported only by `upload-words.ts`), `Upload.test.tsx`, `upload-reasons.test.ts`; `medioo-web/e2e/upload.spec.ts`
- Modify: `src/components/Composer/Composer.tsx` and W8's add buttons when they exist (open the sheet in place, falling back to `/app/add`), `src/api/browser.ts` (`apiUpload`), `src/api/browser.test.ts`, `src/api/types.ts` (`UploadOptionsJson`, `UploadAnswerJson`), `e2e/support/scenarios.mjs`, `src/routing/paths.ts`, `baymax/src/main/resources/baymax/ui-upload_bn.properties`, `ui-upload_en.properties` (`upload.nojs`), regenerated `medioo-web/src/copy/generated/copy.bn.json`, `copy.en.json`
- Create (Java tests): `baymax/src/test/java/com/elioo/baymax/web/adapter/in/api/UploadApiContractTest.java` (fixtures `medioo-web/fixtures/api/upload-options/` `owner-one.json`, `owner-two.json`, `view-only.json`, `cap-reached.json`; `medioo-web/fixtures/api/uploads/` `one-document.json`, `separate.json`, `refusals.json`); `baymax/src/test/java/com/elioo/baymax/web/adapter/in/api/UploadCutOffAcceptanceTest.java` (`@Testcontainers`, Postgres and MinIO, shaped like `UploadApiReplayTest`). `UploadApiRealServerTest` is not the place: it wires a `RecordingUploads` stub with no database, so there is no `document` row to count.

**Interfaces:**
- Consumes:
  - `GET /app/api/upload-options`: who the viewer may send for, view-only patients, the one preselect, allowance, `limits.max_pages`, `limits.max_files`, `max_file_bytes`, `max_request_bytes`.
  - `POST /app/api/uploads`: multipart `patient`, `upload_key`, `group` `one`|`separate`, `replaces` (D1), and `file` parts → document ids. Refusals: `CHOOSE_PATIENT`, `NO_FILE`, `EMPTY_FILE`, `UNSUPPORTED_FILE`, `UNREADABLE_PDF`, `TOO_MANY_PAGES`, `TOO_MANY_FILES`, `TOO_LARGE`, `VIEW_ONLY`, `UPLOAD_KEY_REUSED`, `FREE_TIER_DOCUMENTS`. No `NOT_A_RETAKE` (*update 2026-10-22, [DR-47](../../DECISIONS.md)*): a `replaces` that is not this patient's retake is dropped and the photo uploads unlinked, never refused, so `refusals.json` (derived from `UploadApiReasonsTest`'s sweep) does not carry it and W7 writes no copy for it.
  - `GET /app/api/documents/{id}/status`.
  - Components: `SheetDialog`, `ToastRegion`, `MessageBubble` (progress lines `up.*`).

  The group control's track and the tray's controls paint `--fill` and draw `--boundary`.
- Produces:
  - `UPLOAD_REASON_COPY: Record<string, CopyKey>` (`upload-reasons.ts`, server side), mapping every upload refusal name to its `upload.err.*` or `app.err.*` key. `upload-words.ts` resolves it, with the numbers from the options (`max_files`, `max_pages`), into `words.reasons: Record<reason, string>`, so the browser turns no key into words.
  - `uploadKey(): string` (`crypto.randomUUID()`), drawn once per press and held in memory across that press's retries; a new press (a changed file set or patient) draws a new one.
  - `UploadSheet({ options, words: UploadWords, preview })`.

  *(Amended by W7, for PO sight:)*
  - `UploadSheet({ options, words, preview, asked, replaces })`: `asked` is the page's `?patient=` (for the view-only line and the retake) and `replaces` its `?replaces=`, sent only while the chosen patient is the one the retake link named (DR-47).
  - Whose paper the sheet opens on is Java's: `GET /app/api/upload-options?patient=<asked>` answers `chosen_patient_id` (additive) by `UploadOptions.chosen`, the rule the HTML sheet checks its chip by (`UploadUiHandler.chosen` delegates to it). The page passes `?patient=` through; the sheet renders the field and derives nothing (W7 review finding 15).
  - `UPLOAD_REASON_COPY` maps three names to the HTML sheet's own `add.*` keys (`CHOOSE_PATIENT` → `add.choose`, `VIEW_ONLY` → `add.viewonly`, `FREE_TIER_DOCUMENTS` → `add.cap`, with `add.cap.separate` when separate papers would take more than are left), so the two sheets say a refusal alike; the rest are `upload.err.*`.
  - A press's key is dropped only on a refusal Java named (a reason in `words.reasons`). A cut or quiet network, a gateway's 504, a 500, or a reason the sheet has no words for keeps the key and the prepared bytes and shows stuck with the retry: such an answer does not prove nothing was stored.
  - Each page is sent as `page-N.ext`, never under its own name (upload.js `pageName`); a type outside `image/(jpeg|png|webp|heic|heif)|application/pdf` is refused before it leaves the phone (`UNSUPPORTED_FILE`'s words); the options' downscale is applied as upload.js `prepare` does (a no-op while `downscale_max_edge` is 0).
  - The desktop drop target ships without a line of its own: `upload.drop` is a draft outside this plan and waits for the PO's yes.

**Board** (master W7): the sheet, the tray with three pages, the drop zone (desktop), sending, slow, stuck with retry, each refusal, view-only, cap reached. Beside `phone.html` scene `chat` with its sheet open, and `desktop.html` scene `app` (`#drop`).

- [ ] **Step 1: Write the failing tests.**
  - `UploadApiContractTest`: one case per fixture. `refusals.json` lists every reason name `UploadApiReasonsTest` sweeps, so the web map is derived from it.
  - `UploadCutOffAcceptanceTest › aPressCutOffMidBodyThenRetriedWithItsKeyIsOneDocument` (master Review Focus 3, Java side). It serves the real `AppApiGuard`, the real `UploadService` and the real Postgres and MinIO on a reactor-netty port. It sends half the bytes of a three-page `group=one` press and closes the socket, then sends the whole press with the same `upload_key`. It asserts that `SELECT count(*) FROM document WHERE upload_key = :key` is 1 and that the answer's ids equal that row. *(Amended by W7, for PO sight:)* V21 stores the key as `key:digest` (`DocumentIntakeService.pressKey`), so the count is `upload_key LIKE '<key>:%'` and, separately, the patient's documents; the cut-off attempt is shown to have reached the multipart reader (a test `WebFilter` records its body read and then cut), so its zero rows cannot pass for a request refused at the guard.
  - `upload-reasons.test.ts › everyUploadRefusalHasCopy`: every name in `fixtures/api/uploads/refusals.json` maps to a key that resolves in both languages. Guard: the list is non-empty.
  - `browser.test.ts` gains three cases:
    - `apiUploadSendsXMedioAndNoManualContentType`: the fake XHR saw `X-Medioo: 1`, no `setRequestHeader("Content-Type", …)`, and same-origin credentials.
    - `apiUploadSendsSignInOn401`.
    - `apiUploadIsOfflineOnError`.
  - `Upload.test.tsx`:
    - `theSheetNeverGuessesThePatient`: two patients and no preselect give `add.choose` with send disabled; `owner-one` gives `add.onlyone`, preselected.
    - `aViewOnlyPatientCannotBeSentFor`: `add.viewonly`.
    - `theTrayKeepsOrderAndCap`: `tray.up`/`tray.down` reorder, and the `max_pages`th page disables `tray.more` with `tray.limit`.
    - `groupIsAskedNotGuessed`: two or more pages give `add.group.q`, with neither option preselected.
  - `e2e/upload.spec.ts`:
    - `aRetryResendsTheSameKeyAndTheSameBytes`: the stub aborts the first `POST /app/api/uploads` with `connectionreset` and answers the second. Both requests' `upload_key` parts are equal, and their `file` parts' SHA-256 lists are equal, parsed by field. The stub's guard accepted both, which proves `X-Medioo`, Origin and the multipart type.
    - `aNewPressDrawsANewKey`.
    - `eachRefusalIsItsCopy`: parameterised over `refusals.json`.
    - `oneDocumentOpensItsPaperSeveralOpenThePapers`.
    - `dragAndDropAddsPages`: on desktop, a `DataTransfer` drop of two files fills the tray.
    - `aRetakeNamesThePaperItReplaces`: `/app/add?patient=<p>&replaces=<d>` sends `replaces=<d>`.
    - `aRetakeDropsReplacesWhenThePatientChanges` (*added 2026-10-22, DR-47*): on `/app/add?patient=<p>&replaces=<d>`, choosing another patient on the sheet sends no `replaces` part, and choosing `<p>` again sends it again. The server drops a `replaces` of another patient anyway (DR-47), so this keeps the press honest rather than saving it from a refusal; if the PO reverses DR-47's default, it is what keeps the server's 400 reachable only by a tampered client.
    - `withoutJavaScriptTheSheetSaysSo`: `upload.nojs`, and no form.
    - `screenChecks("/app/add", "upload-owner-two", { longest: true })`, where `LONGEST` uses `me-long`.
- [ ] **Step 2:** Run `gl :baymax:test --tests '*UploadApiContractTest' --tests '*UploadCutOffAcceptanceTest'` and the web commands. Expected: FAIL on the missing fixtures. The acceptance test FAILS if the cut-off press left a row. If it passes at once, it is a test written after the code, and it gets its own replay in Step 5.
- [ ] **Step 3: Implement.** The multipart is built in the browser and posted by `apiUpload` straight to Java; Next never sees the body.
- [ ] **Step 4:** Run. Expected: PASS. `test:built` PASS, with no copy in the `UploadSheet` chunk.
- [ ] **Step 5: Replays.**
  - (a) `uploadKey()` called inside the retry; run `aRetryResendsTheSameKeyAndTheSameBytes`. Expected: FAIL naming `upload_key` with both values.
  - (b) Java side: a deliberately broken `UploadService` key lookup (the key ignored for a press whose first attempt was cut off); run `UploadCutOffAcceptanceTest`. Expected: `expected: 1 but was: 2`.
  - (c) `X-Medioo` removed from `apiUpload`; run `aRetryResendsTheSameKeyAndTheSameBytes`. Expected: FAIL naming `MISSING_HEADER`.

  Restore each and quote all three.
- [ ] **Step 6: Gates.** The W gates, plus `gl build`. **Board**, then **commit:**
  ```bash
  W=medioo-web; A='medioo-web/src/app/app/(signed-in)/add'; C=medioo-web/src/components/Upload
  R=baymax/src/main/resources/baymax; T=baymax/src/test/java/com/elioo/baymax/web/adapter/in/api; F=medioo-web/fixtures/api
  git add "$A/page.tsx" "$A/upload-words.ts" $C/UploadSheet.tsx $C/PageTray.tsx $C/DropZone.tsx $C/UploadProgress.tsx \
    $C/Upload.css $C/upload-reasons.ts $C/Upload.test.tsx $C/upload-reasons.test.ts \
    $W/src/components/Composer/Composer.tsx $W/src/api/browser.ts $W/src/api/browser.test.ts $W/src/api/types.ts \
    $W/e2e/support/scenarios.mjs $W/src/routing/paths.ts $W/e2e/upload.spec.ts \
    $R/ui-upload_bn.properties $R/ui-upload_en.properties $W/src/copy/generated/copy.bn.json $W/src/copy/generated/copy.en.json \
    $T/UploadApiContractTest.java $T/UploadCutOffAcceptanceTest.java \
    $F/upload-options/owner-one.json $F/upload-options/owner-two.json $F/upload-options/view-only.json \
    $F/upload-options/cap-reached.json $F/uploads/one-document.json $F/uploads/separate.json $F/uploads/refusals.json
  git status --short -- medioo-web baymax/src scripts docs .github   # nothing outside the list
  git commit -m "feat(medioo-web): upload — the sheet, the page tray, drag and drop, a retry that is the same press (plan W7)" \
    -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
  ```
  PR body: the `upload.nojs` draft and the replays.

---

## Task W8-api: One open of কাগজপত্র is one TIMELINE_VIEW

Branch `fix/baymax-web-calendar-audit`. Lane B. S14 applies (blocks the merge).

**Files:**
- Modify: `baymax/src/main/java/com/elioo/baymax/web/application/service/TimelineService.java` (`calendar` records no audit row), `baymax/src/main/java/com/elioo/baymax/web/application/port/in/TimelineUseCase.java` (the `calendar` javadoc)
- Create (test): `baymax/src/test/java/com/elioo/baymax/web/adapter/in/api/TimelineAuditTest.java` (Testcontainers)

- [ ] **Step 1: Write the failing test.** `TimelineAuditTest`:
  - `aTimelineAndItsCalendarAreOneView`: the count of `audit_event` rows with `kind = 'timeline_view'` rises by exactly 1 across `GET /app/api/patients/{id}/timeline` plus `GET …/calendar?month=`.
  - `aMonthLinkIsNoView`: a second calendar read adds 0. *(Amended by W8-api review, for PO sight:)* named `aSecondCalendarReadIsNoView`, because that is what it checks. A month link is not a bare calendar read: the page is server-rendered and every control is a plain link, so a month, filter or cursor link is a new render whose timeline read records one TIMELINE_VIEW.
  - `theTimelineStillRecordsItsView`: the guard, the timeline alone adds 1.
- [ ] **Step 2:** `gl :baymax:test --tests '*TimelineAuditTest'`. Expected: FAIL with `expected: 1 but was: 2`.
- [ ] **Step 3:** Implement. **Step 4:** run. Expected: PASS. **Step 5: Replay:** restore the calendar's `audit.record` and run the test. Expected: `expected: 1 but was: 2`. Restore and quote the line.
- [ ] **Step 6:** `gl build`, then commit:
  ```bash
  git add baymax/src/main/java/com/elioo/baymax/web/application/service/TimelineService.java \
    baymax/src/main/java/com/elioo/baymax/web/application/port/in/TimelineUseCase.java \
    baymax/src/test/java/com/elioo/baymax/web/adapter/in/api/TimelineAuditTest.java
  git status --short -- medioo-web baymax/src scripts docs .github   # nothing outside the list
  git commit -m "fix(baymax-web): the calendar read records no timeline view, so one open of the papers page is one (plan W8-api)" \
    -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
  ```
  PR body: S14, and the effect on `WeeklyMetricsService`'s `timeline_views` column (none today, since nothing calls the calendar yet).
  *(Amended by W8-api review, for PO sight:)* from W8 on, `timeline_views` counts papers-page renders (the first open plus every month, filter and cursor link), not opens; that halves the two-per-render the calendar's row would have made. Whether S14 meant no extra row per month link is asked of the PO in the PR.

---

## Task W8: কাগজপত্র — the wall calendar, DueCard, the month list

Branch `feat/medioo-web-papers`. Lane C. After W2e-b and W8-api. Owns master Review Focus 1.

**Files:**
- Create: `medioo-web/src/app/app/(signed-in)/timeline/page.tsx`, `src/components/MonthList/MonthList.tsx`, `MonthList.css`, `MonthList.test.tsx`, `src/components/DueCard/DueCard.tsx`, `DueCard.css`, `DueCard.test.tsx`, `medioo-web/e2e/papers.spec.ts`
- Modify: `baymax/src/test/java/com/elioo/baymax/web/adapter/in/api/PatientTimelineApiContractTest.java` (cases `calendar/forty-five`, `timeline/forty-five-first-page`, `timeline/lab-only`, `timeline/rx-only`) and the generated `medioo-web/fixtures/api/calendar/forty-five.json`, `medioo-web/fixtures/api/timeline/forty-five-first-page.json`, `lab-only.json`, `rx-only.json`; `src/api/types.ts`, `e2e/support/scenarios.mjs`, `src/routing/paths.ts`
- Modify: `baymax/src/main/resources/baymax/ui-papers_bn.properties`, `ui-papers_en.properties` (drafts `due.title` "ডাক্তারের দেওয়া তারিখ" / "Dates the doctor gave"; `due.date` "{0} তারিখে" / "On {0}"; `due.overdue` "তারিখ পেরিয়ে গেছে" / "The date has passed"), regenerated `medioo-web/src/copy/generated/copy.bn.json`, `copy.en.json`

**Interfaces:**
- Consumes:
  - `GET /app/api/patients/{id}/timeline?cursor=&type=lab|rx`: `{entries:[{document_id, document_type, date, date_is_upload_day, date_uncertain, month, status, chip, counts, facility, page_one, retake_reason_key, review_pending}], next_cursor}`.
  - `GET /app/api/patients/{id}/calendar?month=YYYY-MM`: `{month, days:[{date, papers[], follow_ups[]}]}`.
  - `GET /app/api/patients/{id}/follow-ups`: `{items:[{document_id, instruction, due_date, overdue, crop}]}`.
  - `GET /app/api/patients/{id}/attention`: the strip, `attn.strip`.
  - Components: `MonthCalendar` (W2e-b; `caption` is the patient's name and the month) and `StatusChip`. No `VerdictCard`: entries are rows.
- Produces:
  - the page `/app/timeline` (`?patient=`, `?type=`, `?cursor=`, `?month=`). Every control is a plain link, so the page reads and pages without JavaScript.
  - `DueCard({ item, lang })` (server): the doctor's words as written beside their crop, `overdue` as shape + `due.overdue`, and the date from Java through `due.date`, under the heading `due.title`.

**Board** (master W8): empty, one month, busy month, 45 papers, the filter, follow-ups due and overdue, beside `phone.html` scene `papers`.

- [ ] **Step 1: Write the failing tests.**
  - `DueCard.test.tsx`: `theInstructionIsAsWrittenBesideItsCrop` (the `textContent` strictly equals `instruction`); `overdueIsShapeAndWord`.
  - `MonthList.test.tsx`:
    - `entriesGroupUnderJavasMonth`: it groups by the `month` field, never by parsing `date`. This is a **unit-level contract probe**: it renders a copy of `timeline/first-page` with one entry mutated so that `month` differs from `date.slice(0, 7)` (`date` `2026-10-01`, `month` `2026-09`), and asserts that the entry sits under 2026-09. On Java's real output the two always agree (`TimelineJson` derives `month` from `date`; D2 and B3 own the Dhaka-day rule), so only a mutated copy can tell the two groupings apart.
    - `anUploadDayIsSaidAsSuch`: `date_is_upload_day` gives `timeline.uploaddate`.
    - `anUncertainDateIsMarked`.
  - `e2e/papers.spec.ts`:
    - `fortyFivePapersAndTheLongestNameDoNotOverflow` (master Review Focus 1): `calendar/forty-five` and `me-long`, with `scrollWidth <= clientWidth` on every element and the calendar grid, at 390 and 1440. The long name must be present in the DOM.
    - `theFilterIsALink`: `?type=lab` shows only lab rows, and the request carried `type=lab`.
    - `moreIsALinkThatWorksWithoutJavaScript` (`javaScriptEnabled: false`): `timeline.more` follows `next_cursor`, and the next page renders.
    - `theMonthMovesByLink`.
    - `anEmptyPatientGetsTheEmptyState`: `timeline.empty.*`, and one add link, for the owner only.
    - `everyFollowUpCropIsItsOwn`: the `crops` check, applied to follow-up rows.
    - `oneOpenIsOneTimelineView`: the stub's request log shows one timeline GET and one calendar GET for one open. Java's `TimelineAuditTest` (W8-api) is the row-count half.
      *(Amended by W8-api review, for PO sight:)* it also follows a month link (`?month=` of the previous month) and asserts the log shows exactly one more timeline GET and one more calendar GET for it, so the views per month link (one, through the timeline) are known and tested, not assumed to be zero.
    - `screenChecks("/app/timeline", "papers-busy", { crops: true, longest: true })`, where `LONGEST` uses `me-long` and `timeline/forty-five-first-page`.
- [ ] **Step 2:** Run. Expected: FAIL on missing modules and on Next's 404.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run. Expected: PASS. `gl :baymax:test --tests '*PatientTimelineApiContractTest'` PASS after copying the new fixtures, and `test:built` PASS.
- [ ] **Step 5: Replay** `entriesGroupUnderJavasMonth` with the grouping done by `date.slice(0, 7)`. Expected: FAIL naming the mutated entry's `document_id` and both months, `2026-09` expected and `2026-10` received. Restore and quote the line.
- [ ] **Step 6: Gates.** The W gates, plus `gl build`. **Board**, then **commit:**
  ```bash
  W=medioo-web; C=medioo-web/src/components; R=baymax/src/main/resources/baymax
  T=baymax/src/test/java/com/elioo/baymax/web/adapter/in/api; F=medioo-web/fixtures/api
  git add 'medioo-web/src/app/app/(signed-in)/timeline/page.tsx' \
    $C/MonthList/MonthList.tsx $C/MonthList/MonthList.css $C/MonthList/MonthList.test.tsx \
    $C/DueCard/DueCard.tsx $C/DueCard/DueCard.css $C/DueCard/DueCard.test.tsx $W/e2e/papers.spec.ts \
    $T/PatientTimelineApiContractTest.java $F/calendar/forty-five.json $F/timeline/forty-five-first-page.json \
    $F/timeline/lab-only.json $F/timeline/rx-only.json $W/src/api/types.ts $W/e2e/support/scenarios.mjs $W/src/routing/paths.ts \
    $R/ui-papers_bn.properties $R/ui-papers_en.properties $W/src/copy/generated/copy.bn.json $W/src/copy/generated/copy.en.json
  git status --short -- medioo-web baymax/src scripts docs .github   # nothing outside the list
  git commit -m "feat(medioo-web): কাগজপত্র — the wall calendar, the doctor's dates, the month list, readable without JavaScript (plan W8)" \
    -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
  ```
  PR body: the `due.*` drafts with both strings, and the replay.

---

## Task W9: স্বাস্থ্য — the lab list, the trend, the medicines as written

Branch `feat/medioo-web-health`. Lane C. After W2e-b. DR-44 item 3, DR-45 item 7.

**Files:**
- Create: `medioo-web/src/app/app/(signed-in)/health/page.tsx`, `src/components/LabList/LabList.tsx`, `LabList.css`, `LabList.test.tsx`, `src/components/MedicinesAsWritten/MedicinesAsWritten.tsx`, `MedicinesAsWritten.css`, `MedicinesAsWritten.test.tsx`, `medioo-web/e2e/health.spec.ts`
- Modify: `baymax/src/test/java/com/elioo/baymax/web/adapter/in/api/HealthApiContractTest.java` (case `labs-two-units`: two series sharing a name with different units), generated `medioo-web/fixtures/api/health/labs-two-units.json`
- Modify: `src/api/types.ts` (`MedicinesJson`; `LabsJson` exists, W2e-b), `src/api/types.test.ts` (add `labs-two-units` to `everyLabsFixtureIsALabsJson`'s list, which must hold every committed `labs-*` fixture; amended by W2e-b, for PO sight), `e2e/support/scenarios.mjs`, `src/routing/paths.ts`, `baymax/src/main/resources/baymax/ui-result_bn.properties`, `ui-result_en.properties` (drafts `health.labs` "ল্যাবের মান" / "Lab values", `health.one_reading` "একবারই মাপা হয়েছে: {0} {1}, {2}" / "Measured once: {0} {1}, {2}", `health.labs.none` "এখনো কোনো ল্যাবের মান নেই" / "No lab values yet", `health.medicines.none` "এখনো কোনো প্রেসক্রিপশন নেই" / "No prescription yet", `health.medicines.newer_unread` "নতুন একটা প্রেসক্রিপশন এখনো পড়া হয়নি" / "A newer prescription hasn't been read yet"; the page title is `tab.health`, so no `health.title`), regenerated `medioo-web/src/copy/generated/copy.bn.json`, `copy.en.json`
- Create (test helper): `medioo-web/src/components/MedicinesAsWritten/forbidden-words.ts`, holding the words the heading must never use, built from code points as `src/lint/bangla-literal.test.ts` builds its Bangla: `String.fromCodePoint(0x09ac, 0x09b0, 0x09cd, 0x09a4, 0x09ae, 0x09be, 0x09a8)` for বর্তমান, `String.fromCodePoint(0x099a, 0x09b2, 0x09ae, 0x09be, 0x09a8)` for চলমান, and `"current"`

**Interfaces:**
- Consumes:
  - `GET /app/api/patients/{id}/labs`: `{series:[{canonical_name, name, unit, points[]}], bp:{points[]}}`.
  - `GET /app/api/patients/{id}/medicines`: `{document_id, date, date_is_upload_day, date_uncertain, heading_key, medicines[], newer_prescription_not_read, newer_document_id}`.
  - Components: `TrendChart` (W2e-b), `StatusChip`, and the `Crop` component from W6.
- Produces:
  - the page `/app/health`;
  - `LabList({ labs, lang })`: a series of one point is the `health.one_reading` sentence, never a chart. **Amended by W2e-b, for PO sight:** for two or more points LabList asks TrendChart's exported `chartable(series.points)`, never `points.length`: when it is true it renders `<TrendChart series={series} lang={lang} />` (which says each reading's date, value, unit and chip word itself); when it is false (a reading printed as a bound, `>500` or `<0.5`) it lists every reading as written, with its date and `StatusChip`, and draws no chart. A series is never shown as nothing. Test: `aSeriesWithABoundIsListedNotCharted` over `fixtures/api/health/labs-non-numeric.json`, every reading's `value` in the DOM and no `.trend-chart`;
  - `MedicinesAsWritten({ medicines, lang })`: the heading from `heading_key` with the date, then each medicine's written fields beside its crop.

**Board:** labs some, labs none, two units, blood pressure, one reading, medicines latest, latest with none, a newer one unread, no prescription. Beside `phone.html` scene `health`.

- [ ] **Step 1: Write the failing tests.**
  - `HealthApiContractTest › labs-two-units` (red on the missing committed file).
  - `LabList.test.tsx`:
    - `oneReadingIsASentenceNotAChart`: no `svg` chart for a one-point series.
    - `aSeriesIsJavasNotJoinedHere`: with `labs-two-units`, two series sharing a name with different units stay two, as Java sent them.
    - `bloodPressureIsJavasPairs`.
  - `MedicinesAsWritten.test.tsx`:
    - `theHeadingIsJavasKeyWithTheDate`: exactly `t(lang, heading_key)` and the date, and no rendered element's text contains a word from `forbidden-words.ts`, matched per element.
    - `anEmptyLatestPrescriptionIsItsDateAndNothingElse`: render `medicines-latest`, then `rerender` the same instance with `medicines-empty-latest`. The date is shown, no medicine row remains, and none of `medicines-latest`'s names is in the DOM.
    - `aNewerUnreadPrescriptionIsSaidAndLinked`: `health.medicines.newer_unread`, linking `newer_document_id`.
    - `onlyTheWrittenFieldsShowVerbatim`: each field element's `textContent` strictly equals the JSON string.
  - `e2e/health.spec.ts`:
    - `everyMedicineCropIsItsOwn`.
    - `theHealthTabAppearsInPreviewOnly`: not preview gives no স্বাস্থ্য tab while `FLIPPED` lacks it; preview gives the tab.
    - `screenChecks("/app/health", "health-some", { crops: true, longest: true })`, where `LONGEST` uses `me-long` and `medicines-latest`.
- [ ] **Step 2:** Run. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run. Expected: PASS, `test:built` included.
- [ ] **Step 5: Replays.**
  - (a) A component that keeps the last non-empty list in state and falls back to it; run `anEmptyLatestPrescriptionIsItsDateAndNothingElse`. Expected: FAIL naming the medicine row.
  - (b) A digit-localising field formatter; run `onlyTheWrittenFieldsShowVerbatim`. Expected: FAIL naming the field.

  Restore each and quote both lines. Java's `HealthApiContractTest` keeps the server half of the first rule: an empty newest list is never replaced by an older one.
- [ ] **Step 6: Gates.** The W gates, plus `gl build`. **Board**, then **commit:**
  ```bash
  W=medioo-web; C=medioo-web/src/components; R=baymax/src/main/resources/baymax
  T=baymax/src/test/java/com/elioo/baymax/web/adapter/in/api
  git add 'medioo-web/src/app/app/(signed-in)/health/page.tsx' \
    $C/LabList/LabList.tsx $C/LabList/LabList.css $C/LabList/LabList.test.tsx \
    $C/MedicinesAsWritten/MedicinesAsWritten.tsx $C/MedicinesAsWritten/MedicinesAsWritten.css \
    $C/MedicinesAsWritten/MedicinesAsWritten.test.tsx $C/MedicinesAsWritten/forbidden-words.ts $W/e2e/health.spec.ts \
    $T/HealthApiContractTest.java medioo-web/fixtures/api/health/labs-two-units.json \
    $W/src/api/types.ts $W/e2e/support/scenarios.mjs $W/src/routing/paths.ts \
    $R/ui-result_bn.properties $R/ui-result_en.properties $W/src/copy/generated/copy.bn.json $W/src/copy/generated/copy.en.json
  git status --short -- medioo-web baymax/src scripts docs .github   # nothing outside the list
  git commit -m "feat(medioo-web): স্বাস্থ্য — lab values with their trend, medicines as written on the latest prescription (plan W9)" \
    -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
  ```
  PR body: the `health.*` drafts and the replays.

*Amended 2026-10-23 (PR #160), for PO sight:* what shipped departs from this task as follows.
- `MedicinesAsWritten({ medicines, lang, preview })`: every link to a paper goes through `href(path, preview)`, so a
  preview page stays inside the preview once this build serves `/app/documents/{id}` (W6); today that path is Java's,
  and `href` returns it unchanged. Tested with literal hrefs, and under a `vi.doMock` of `@/routing/paths` that adds
  the paper page to `NEXT_PAGES` (`aPreviewPageLinksThePaperInsideThePreviewOnceThisBuildServesIt`).
- Every lab reading is shown beside its own paper's crop (`landing.c.p`): a listed reading, the one-reading sentence,
  each blood-pressure half, and, for a charted series, each reading's paper under the chart (newest first, its date
  beside its crop). Each is a `[data-value-row]`, so `everyCropIsItsRowsOwnImage` judges the lab crops with the
  medicine lines (`crops:` lists both, in page order). Every crop on the tab carries one neutral alternative text
  (`health.crop.alt`), never the string read off it.
- A blood-pressure pair is two groups, `[data-half=systolic]` and `[data-half=diastolic]`, each its value, its own chip
  and its own crop, the slash between them read aloud. HealthApiContractTest case `bp-flagged` (systolic outside high
  against 90–120, diastolic inside 60–90) is the fixture that can see a chip drawn for the wrong half.
- Under the prescription's lines: `med.pharmacy` (approved), the medsSheet foot line `health.medicines.doctor_only` and
  the link `health.medicines.open` to the prescription. HealthApiContractTest case `medicines-none-newer-unread`
  (`LatestPrescription.none(Optional.of(id))`, a new family's first prescription still being read) says
  `health.medicines.first_unread`, linked, and never "a newer one".
- Draft keys beyond the five above: `health.one_reading.nounit`, `health.bp`, `health.medicines.first_unread`,
  `health.medicines.open`, `health.medicines.doctor_only`, `health.crop.alt`; and W6's `crop.retry`.
- W9 carries W6's `Crop` (`Crop.tsx`, `Crop.css`, `Crop.test.tsx`, blob-identical to PR #149's) and `crop.retry`. The
  browser proof of its retry is ported into `e2e/health.spec.ts › theCropRetryFetchesTheImageAgain` on a medicine crop,
  so W9 does not lean on `paper.spec.ts`; the two PRs may merge in either order (the add/add of identical blobs
  resolves cleanly on rebase).
- `TrendChart` gains an optional `labelledBy` (additive): the health tab names each list of readings by its heading,
  unit included, so one name in two units is never two lists named alike.
- The board has 13 states, not 9: also `readings-as-bounds`, `long-name`, `blood-pressure-flagged` and
  `first-prescription-being-read`.
- Extra files: `src/components/TabBar/TabBar.tsx` and `TabBar.test.tsx`, `src/routing/paths.test.ts`,
  `e2e/board.spec.ts`, `health/health.css`, `src/components/TrendChart/TrendChart.tsx`, `src/api/types.test.ts`, and
  the fixtures `bp-flagged.json`, `medicines-none-newer-unread.json`. Extra e2e cases:
  `eachBloodPressureHalfShowsItsOwnChip`, `theCropRetryFetchesTheImageAgain`, `theReadsAreTheChosenPatientsNotTheFirst`,
  `aFirstPrescriptionStillBeingReadIsSaidAndLinked`, `theLinesComeWithThePharmacyRuleAndTheirPrescription`,
  `theDisclaimerIsShownWheneverAValueOrAMedicineIs`, and screen checks for `health-bp-flagged`, `health-empty-latest`
  (now with crops) and `health-first-unread`.
- `gl build`: the Docker Postgres on 5433 was down while this was built, so the full build's evidence is CI's
  "Build and test" on the PR's head, quoted in the PR body; the PR merges only when that check is green and the PR is
  CLEAN. `HealthApiContractTest` and `WebCopyExportTest` (no database) ran locally.

---

## Task RANGE-api: Each labs point carries the range its own paper printed (PO ruling R3, DR-48)

*Added 2026-10-22, after the PO's ruling R3 (recorded as DR-48 in PR #144): "the TrendChart shows the lab's PRINTED
normal range as a pale band behind the line, using only what the paper printed; a paper that printed no range gets no
band."* Branch `feat/baymax-web-api-labs-printed-range` (PR #143). Lane B. Before RANGE-web.

**Files:**
- Create: `baymax/src/main/java/com/elioo/baymax/web/domain/PrintedBand.java` (pure: the stored `ref_low`/`ref_high` as numbers and text), `baymax/src/test/java/com/elioo/baymax/web/domain/PrintedBandTest.java`
- Modify: `web/domain/PaperDisplay.java` (`bandAgrees`, the pin's agreement rule moved out of `pin` and shared; `drawnBand`, the numbers a point may send), `web/domain/LabSeries.java` (`Point.range`), `web/application/service/HealthService.java` (each point's `drawnBand`), `web/adapter/in/api/HealthJson.java` (`ref_low`, `ref_high`, `ref_text` after every shipped field, A2)
- Modify (tests): `PaperDisplayTest.java`, `HealthServiceTest.java`, `HealthReadModelTest.java` (Testcontainers), `HealthApiContractTest.java` (case `labs-printed-range`)
- Generated: `medioo-web/fixtures/api/health/labs-printed-range.json`; regenerated (additions only against `main`) `labs-some.json`, `labs-with-unknown.json`, `labs-non-numeric.json`, `bp.json`
- Modify: `medioo-web/src/api/types.ts` (`TrendPointJson`), `src/api/types.test.ts`, `src/components/TrendChart/TrendChart.test.tsx` (the new fixture in "every generated labs body"), `docs/MEDIOO.md`

**Interfaces:**
- Produces, per `TrendPointJson` (series points and both blood-pressure halves): `ref_low: number | null`, `ref_high: number | null`, `ref_text: string | null`.
  - Only the point's own stored row, never a table, a default or a neighbouring point. Nothing printed: all three null.
  - Numbers only where the picture agrees with the point's chip (`PaperDisplay.drawnBand`, the paper page's pin rule): both numbers for a two-sided range that agrees with `kind`; one number for a one-sided bound beside an OUTSIDE/CRITICAL beyond it on `side`; none beside an UNKNOWN or when the band would contradict the chip.
  - A lone signed bound is placed by its sign whichever column holds it; a lone bare number has no number (its side would rest on the unchecked column).
  - `ref_text` is the stored sides, joined by Java with an en dash when both are stored: not a transcription of the paper.

**Tests:** `PrintedBandTest` (both directions: every placeable shape and every unplaceable one, a bare one-sided number in each column), `PaperDisplayTest` (a band that agrees is drawn, one that contradicts is text only, one-sided only beyond its bound on the chip's side, the pin is drawn exactly when the band agrees), `HealthServiceTest` and `HealthReadModelTest` (each point its own paper's range; a point with none, flagged OUTSIDE so a leaked range would agree and be drawn, carries none), `HealthApiContractTest` (by field, over `labs-printed-range`), `types.test.ts` (over every generated point, a sent number agrees with the chip).

**Replays:** `PrintedBand.of` stubbed to `NONE`; `PrintedBand.of` returning a fixed `59–104` when nothing is stored; `HealthService` carrying the previous point's range onto a point with none; the TS agreement check over the pre-agreement fixtures. Each red on the defect, quoted in the PR.

---

## Task RANGE-web: TrendChart draws the printed range as a pale band (PO ruling R3, DR-48)

*Added 2026-10-22.* Branch `feat/medioo-web-trend-band`. Lane C. After RANGE-api.

**Files:**
- Modify: `medioo-web/src/components/TrendChart/TrendChart.tsx`, `TrendChart.css`, `TrendChart.test.tsx` (replaces `noReadingStandsOnABand`), the TrendChart catalog states (a `printed-range` state over `labs-printed-range.json`)

**Rules (from `TrendPointJson`'s contract; React judges nothing):**
- A FILLED pale band only behind a point that carries both `ref_low` and `ref_high`, per point (two labs print two ranges).
- A point with one number: at most an UNFILLED edge line at that bound, labelled with `ref_text`, never a region that contains a point.
- A point with neither number: no band and no edge, whatever `ref_text` says.
- `ref_text` only as the band's accessible label beside the value's crop, never as quoted paper text, until the PO accepts its form (PR #143).
- The band is a pale neutral token, never the NOW red; colour is never the only signal.

**Tests:** `aFilledBandIsDrawnOnlyWhereBothNumbersAreSent` (over every generated labs body), `aOneSidedBoundIsAnEdgeNeverARegion`, `anUnknownPointStandsOnNoBand` (the `labs-printed-range` points 172, 173, 176, 177, 183), `aPointWithNoRangeHasNoBand` (point 179). Replay: a band drawn from the series' newest range behind every point; expected red naming point 179.

**Board:** TrendChart printed-range, two ranges in one series, one-sided edges, a band withheld beside a critical and an unknown; copied to `~/medioo-design/boards/trend-band/`.

*Amended 2026-10-23 (PR #151), for PO sight:* what shipped departs from this task as follows.
- Branch `feat/medioo-web-trend-range-band`; the board is copied to `~/medioo-design/boards/trend-range/`.
- Tests: `anUnknownPointStandsOnNoBand` became `aRangeTextWithNoNumbersDrawsNothing` (every point Java writes with a
  `ref_text` and no number, derived from the fixtures, at least 7) and `aReadingTheCodeCannotJudgeIsDrawnUnknown` (a
  number sent beside a reading drawn unknown is not drawn). Added: `differingRangesAreNeverOneBand` (both ends, only
  the high, only the low), `neighboursSharingARangeShareOneBand`, `eachRangeIsDrawnAtItsOwnPrintedBounds`,
  `everyRangeLiesInsideThePlot`, `aBandIsOutlinedAtItsOwnBounds`, `aLoneBoundThatDisagreesWithItsReadingIsNotDrawn`,
  `theBandNeverChangesAPointOrItsWord`, `noReaderIsGivenTheRangeTextUntilItsFormIsAccepted`,
  `theLegendStandsOnlyUnderADrawnRange`; `catalog.spec.ts` `theTrendRangeIsPaintedPaleAndNeutralAndLeavesTheMarkersAlone`.
- Five catalog states instead of one `printed-range`: `TrendChart/two-ranges`, `one-sided-high`, `one-sided-low`,
  `range-withheld`, `unknown-beside-range`.
- A two-sided band is a pale `--sep` fill outlined by two thin solid `--label3` lines at its bounds: the outline is
  what is seen (3:1), the fill decorates it. A one-sided bound is one such line, drawn only beside a reading beyond it
  on its side (fail closed, as `PaperDisplay.edgeAgrees`).
- The edge line carries no label, and `ref_text` is not used as an accessible label either: until the PO accepts its
  form (PR #143) it is neither said nor written, so a screen-reader user and a sighted reader are given the same. The
  legend names each kind drawn (`trend.band.legend.band`, `trend.band.legend.edge`).
- Extra files: `e2e/board.spec.ts` (shared board tooling: an `element` to shoot one catalog state, and `proto.act` to
  act on the prototype), `e2e/catalog.spec.ts`, `src/catalog/states.tsx`, `src/catalog/states.test.tsx`, both
  `ui-result` bundles, the generated `src/copy/generated/copy.{bn,en}.json`, and `docs/DECISIONS.md`.

---

## Task W10-api: The theme preference — `PUT /app/api/preferences`

Branch `feat/baymax-web-api-preferences`. Lane B. S12 applies (blocks the merge).

**Files:**
- Create: `baymax/src/main/java/com/elioo/baymax/web/adapter/in/api/PreferencesApiHandler.java`
- Modify: `AppApiRouter.java` (one route and one handler parameter)
- Modify (tests that build `mediooAppApiRoutes`): `PaperApiContractTest.java`, `UploadApiRealServerTest.java`, `HealthApiContractTest.java`, `SignInApiHandlerTest.java`, `UploadApiHandlerTest.java`, `AppApiGuardTest.java`, `B3Handlers.java`; and `AppApiLogSafetyTest.java`
- Create (test): `baymax/src/test/java/com/elioo/baymax/web/adapter/in/api/PreferencesApiTest.java`
- Generated: `docs/infra/app-routes.txt`

**Interfaces:**
- Produces: `PUT /app/api/preferences` with `{"theme": "auto"|"light"|"dark"}` → 204 + `Set-Cookie: baymax_theme=<value>; Path=/app; Max-Age=31536000; SameSite=Lax; HttpOnly`, plus `Secure` when `properties.getAuth().isCookieSecure()`. Any other value or field shape → 400 `INVALID`. Session and CSRF as for every mutation. Nothing is stored server-side.

- [ ] **Step 1: Write the failing tests** in `PreferencesApiTest`:
  - `eachThemeSetsItsCookieUnderApp`: parameterised; the cookie's `Path` is `/app`, never `/`.
  - `anUnknownThemeIsInvalidAndSetsNoCookie`.
  - `withoutXMedioItIsRefused`.
  - `withoutASessionItIs401`.
- [ ] **Step 2:** `gl :baymax:test --tests '*PreferencesApiTest'`. Expected: FAIL on the catch-all 404.
- [ ] **Step 3:** Implement, and add the handler to every router-building test listed.
- [ ] **Step 4:** Run the test, then the route and log-safety tests, and regenerate the snapshot. Expected: PASS.
- [ ] **Step 5: Replay** `eachThemeSetsItsCookieUnderApp` with `Path=/`. Expected: FAIL naming `Path`.
- [ ] **Step 6:** `gl build`, then commit:
  ```bash
  M=baymax/src/main/java/com/elioo/baymax/web/adapter/in/api; T=baymax/src/test/java/com/elioo/baymax/web/adapter/in/api
  git add $M/PreferencesApiHandler.java $M/AppApiRouter.java $T/PreferencesApiTest.java $T/AppApiLogSafetyTest.java \
    $T/PaperApiContractTest.java $T/UploadApiRealServerTest.java $T/HealthApiContractTest.java $T/SignInApiHandlerTest.java \
    $T/UploadApiHandlerTest.java $T/AppApiGuardTest.java $T/B3Handlers.java docs/infra/app-routes.txt
  git status --short -- medioo-web baymax/src scripts docs .github   # nothing outside the list
  git commit -m "feat(baymax-web): the theme preference as a cookie under /app (plan W10-api)" \
    -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
  ```
  PR body: S12 and the snapshot sign-off request.

---

## Task W10: পরিবার and settings — owner and member, flags, reminders, theme, language, sign-out

Branch `feat/medioo-web-family`. Lane C. S5, S6 and S7 apply, and each blocks the merge.

**Files:**
- Create: `medioo-web/src/app/app/(signed-in)/family/page.tsx`, `family/family-words.ts` (server: `FamilyWords`), `src/app/app/(signed-in)/patients/[id]/profile/page.tsx` (the patient's section on its own: the link already sent in WhatsApp profile prompts), `src/components/Family/FamilyAccount.tsx`, `PatientCard.tsx`, `ChronicFlags.tsx` (client), `ReminderSwitch.tsx` (client), `ThemeSwitch.tsx` (client), `LanguageSwitch.tsx` (client), `Family.css`, `Family.test.tsx`, `medioo-web/e2e/family.spec.ts`
- Modify: `src/app/layout.tsx` (reads `baymax_theme` and sets `data-theme` on `<html>` when `light` or `dark`), `e2e/tokens-cascade.spec.ts` (the cookie path), `src/api/types.ts` (`FamilyJson`), `e2e/support/scenarios.mjs`, `src/routing/paths.ts`, `baymax/src/main/resources/baymax/ui-family_bn.properties`, `ui-family_en.properties` (drafts `theme.title` "চেহারা" / "Appearance", `theme.auto` "ফোনের মতো" / "Like the phone", `theme.light` "আলো" / "Light", `theme.dark` "অন্ধকার" / "Dark"), regenerated `medioo-web/src/copy/generated/copy.bn.json`, `copy.en.json`

**Interfaces:**
- Consumes:
  - `GET /app/api/family`: `{account:{owner_name, number, language, reminders_stopped, terms_date}, flags_offered:[{id, label_key}], patients:[{id, name, name_possessive, age, sex_key, owner, chronic_flags[], consent_date, reminders}]}`.
  - `PUT /app/api/patients/{id}/flags`: refusals `VIEW_ONLY`, `INVALID`.
  - `PUT /app/api/patients/{id}/reminders` and `PUT /app/api/family/reminders`: `{"on": false}` only; `{"on": true}` is 400 until E2.
  - `PUT /app/api/language`, `PUT /app/api/preferences` (W10-api) and `POST /app/api/sign-out` (W3-api).
  - Components: `ToastRegion` (`profile.saved`) and `SheetDialog`.

  Every check box, the theme segmented track and the language control paint `--fill` and draw `--boundary`.
- Produces: the pages `/app/family` and `/app/patients/{id}/profile`. Each client switch takes its resolved words from `family-words.ts`: labels, the `profile.saved` toast text, and `app.err.forbidden` as `refused`.

**Board** (master W10): owner, share member, reminders off, a typed flag, theme light and dark, beside `phone.html` scenes `family` and `theme`.

- [ ] **Step 1: Write the failing tests.**
  - `Family.test.tsx`:
    - `theNumberIsShownOnlyHere`: `account.number` is rendered once, in `FamilyAccount`.
    - `aShareMemberSeesFlagsReadOnly`: `owner: false` gives no inputs, the flags as text, and a `retake.viewonly`-style note from `patients.shared`.
    - `aTypedFlagIsKeptAndShownAsTyped`: `label_key` null shows its own text, and saving sends it back unchanged (Q10 default).
    - `remindersCanOnlyBeStopped`: the switch sends `{"on": false}`; once stopped it says so and offers no "on".
    - `theFamilySwitchIsHiddenForAMemberWhoOwnsNoone` (S5 default).
    - `theFamilySwitchShowsForAnOwner` (S5, the other direction).
    - `aViewOnlyRefusalIsCopy`: a 403 `VIEW_ONLY` shows the `refused` prop's text (`app.err.forbidden`), with no crash.
    - `theThemePutsAndRepaints`: one `PUT /app/api/preferences`, then `data-theme` on `<html>` changes without a reload.
    - `languageReloadsInTheNewLanguage`.
    - `signOutIsHere`.
  - `e2e/family.spec.ts`:
    - `theThemeCookieIsReadOnTheServer`: with `baymax_theme=dark`, the first HTML response's `<html>` carries `data-theme="dark"`; unset, there is no attribute.
    - `theProfileLinkFromWhatsAppStillOpens`: `/app/patients/<id>/profile` renders that patient's card.
    - `noDeleteFamilyControlYet` (S7): no element names a family delete.
    - `screenChecks("/app/family", "family-owner", { longest: true })`, and the same for `family-share-member`. `LONGEST` uses `me-long` and `family/owner`.
      *(Amended by W10 review, approved by the PO 2026-10-23, ruling P2:)* `me-long` + `family/owner` measured only the shell's toolbar at the longest name: the cards are drawn from the family body, whose patients are short-named, so the overflow check could not fail for this screen's own long text. The twins are `family/long-name.json` (the long name, owned, with a 40-character typed flag) and `family/long-name-shared.json` (the same, seen by a share member), each beside the `me` FamilyApiContractTest reads from the same seeded family (`me/family-long-name.json`, `me/family-long-name-shared.json`), and the long name must be rendered inside `[data-patient-card]`. Every family scenario's `me` is read from its own seeded family the same way (`me/family-*.json`), so the toolbar never names a patient the tab does not list.
      *(Amended by W10 review, approved by the PO 2026-10-23, ruling P2:)* the theme and language tracks are `role="group"` and cannot be focused, so `everyFieldAndTrackDrawsTheBoundary` judges every non-focusable `[role=group]` that paints `--fill` on its edge at rest and hovered, with no `:focus-visible` demand, and the family page must yield at least one such track.
- [ ] **Step 2:** Run. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run. Expected: PASS, including `tokens-cascade.spec.ts` with the cookie and `test:built`.
- [ ] **Step 5: Replays.**
  - (a) `ReminderSwitch` offering "on" when stopped; run `remindersCanOnlyBeStopped`. Expected: FAIL naming the control.
  - (b) The layout ignoring the cookie; run `theThemeCookieIsReadOnTheServer`. Expected: FAIL naming `data-theme`.

  Restore each and quote both.
- [ ] **Step 6: Gates.** The W gates. **Board**, then **commit:**
  ```bash
  W=medioo-web; S='medioo-web/src/app/app/(signed-in)'; C=medioo-web/src/components/Family; R=baymax/src/main/resources/baymax
  git add "$S/family/page.tsx" "$S/family/family-words.ts" "$S/patients/[id]/profile/page.tsx" \
    $C/FamilyAccount.tsx $C/PatientCard.tsx $C/ChronicFlags.tsx $C/ReminderSwitch.tsx $C/ThemeSwitch.tsx \
    $C/LanguageSwitch.tsx $C/Family.css $C/Family.test.tsx $W/e2e/family.spec.ts \
    $W/src/app/layout.tsx $W/e2e/tokens-cascade.spec.ts $W/src/api/types.ts $W/e2e/support/scenarios.mjs $W/src/routing/paths.ts \
    $R/ui-family_bn.properties $R/ui-family_en.properties $W/src/copy/generated/copy.bn.json $W/src/copy/generated/copy.en.json
  git status --short -- medioo-web baymax/src scripts docs .github   # nothing outside the list
  git commit -m "feat(medioo-web): পরিবার and settings — flags, a reminder stop, theme and language, owner and member (plan W10)" \
    -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
  ```
  PR body: the `theme.*` drafts; S5, S6 and S7 as proposed cuts awaiting a yes; the replays.

---

## Self-review notes (writing-plans)

**Spec coverage.** Master Part D Wave 3 maps to these tasks:
- W3 (sign-in, the DR-42 fallback, the no-JS expired-session case) → W3-api, W3, F3. The proof is split, per S1.
- W4 (tabs, columns, switcher, chooser, 401 → `next=`) → W4.
- W5 (thread, banner with দেখেছি, honest composer) → W5-api, W5, F5.
- W6 (crops, zoom, gate states, NOW abroad, retake, failed, multi-page, delete, Review Focus 2 and 5, one view per click) → W6-api, W6, F6.
- W7 (sheet, tray, drag and drop, same-key retry, Review Focus 3; `POST /app/add` kept) → W7, F7.
- W8 (wall calendar, DueCard, month list, Review Focus 1) → W8-api, W8, F8.
- W9 → W9, F9.
- W10 (theme, language, owner vs member; sounds and delete-family proposed as cuts S6 and S7) → W10-api, W10, F10.
- `/_next/static/*` and `/fonts/*` with the first HTML route → F0.
- Master Review Focus 4 (expired session) → W3-api, W3, W4, W5-api (`anAckWithoutALiveSessionIs401AndWritesNoAck`) and W6 (`aCropAfterTheSessionEndedLeadsToSignIn`).

Part B decisions:
- 3 → W5 `theNowLineIsShippedVerbatimAndTheNameIsTheTitles`.
- 7 → W2a's tokens. On every routed page it is checked by `screenChecks › onlyNowComponentsPaintTheNowRed` (the rendered colour) and by the built-CSS NOW check in `test:built` (the stylesheet).
- 8 → B3's rule, sent back as received, in W5.
- 9 → W9.

DR-44's স্বাস্থ্য tab → W4's `reachable` and W9.

**Not covered here, by design:**
- W-del: the Java pages and the ledger, one PR per flipped route, after these. **The sign-in W-del keeps `SignInPages.routesUnder("/app/basic")` and `BasicSignInPagesTest` for as long as S1's fallback stands.**
- W11–W13, D3, D5, the E1/E2 screens beyond S7, and chat answers (D11).

**Consistency.**
- These names are defined once (Shared interfaces, W4, W6, W7) and used under those names: `javaGet`, `signedIn`, `requestContext`, `apiSend`, `apiGet`, `apiUpload`, `signInPath`, `basicSignInPath`, `previewPath`, `href`, `reachable`, `NEXT_PAGES`, `JAVA_PAGES`, `FLIPPED`, `signedInPage`, `paperRead`, `activePatient`, `useScenario`, `expectedWidth`, `screenChecks`, `PAGE_SCENARIOS`, `LONGEST`, `BOARD_SCENES`.
- Java additions: `GET /app/api/sign-in/state`, `GET /app/api/sign-in/next`, `POST /app/api/sign-out`, `/app/basic*`, `GET /app/api/patients/{id}/thread`, `ResultUseCase.card`, `GET /app/api/documents/{id}/values/{obs}`, the calendar's audit change, and `PUT /app/api/preferences`.
- No migration is added.
