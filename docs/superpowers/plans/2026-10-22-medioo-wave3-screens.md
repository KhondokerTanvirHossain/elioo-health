# Medioo Web Rebuild — Wave 3 screens Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. In this repo each PR below runs as its own Workflow pipeline (master plan Part E): one worktree, one branch, one PR, no merge inside the pipeline.

**Goal:** Put the approved screens in front of families one route at a time — sign-in, the shell, চ্যাট with the global NOW banner, the paper, upload, কাগজপত্র, স্বাস্থ্য, পরিবার and settings — each first behind a preview path that only allowlisted families can open, then flipped in Caddy after Tanvir has tried it on his phone.

**Architecture:**
- **Screens** are Next.js App Router pages under `medioo-web/src/app/app/`. They render what `/app/api/**` sends and derive nothing. Server components read Java with `GET` only, through one module (`src/api/java.ts`) that forwards the incoming `Cookie` header and nothing else. Every mutation is a browser `fetch` to `/app/api/**` through `src/api/browser.ts`, which Caddy sends straight to Java.
- **Preview before flip.** `src/middleware.ts` rewrites `/app/preview/<rest>` to `/app/<rest>` and marks the request. A marked request renders only for a family whose `GET /app/api/me` says `preview: true` (`BAYMAX_WEB_PREVIEW_FAMILIES`, B1). Links inside a preview page stay inside the preview. Caddy routes `/app/preview/*`, `/_next/static/*`, `/fonts/*` and `/brand/*` once (PR F0, the first HTML route), and each screen's real paths in its own flip PR.
- **Tests run against a stub Java.** Playwright starts `next start` with `JAVA_INTERNAL_URL` pointing at `e2e/support/java-stub.mjs`, which serves the fixtures Java's contract tests write (`medioo-web/fixtures/api/**`). The browser's own `/app/api` calls and image requests are answered from the same scenario table. One shared check suite (`e2e/support/screen-checks.ts`) runs the content checks on every routed page.
- **Java gains four small route groups** where a screen needs a read or a write that does not exist yet: the sign-in state, sign-out and the no-JS fallback (W3-api), the chat thread (W5-api), the value sheet (W6-api) and the theme preference (W10-api).

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
- On main: B1 `/app/api` with `AppApiGuard` and `/me`; B2 paper JSON; B3 now-banner, attention, timeline, calendar, follow-ups; B4a sign-in JSON; B4b upload JSON; B4c status and delete; B5 family JSON; B6 labs and medicines; D1, D2, D4b; W1a–W1c; W2a tokens with the `boundary` token (PO ruling on PR #120); W2b copy; W2c Wordmark, MioAvatar, MessageBubble, StatusChip; W2d-a VerdictCard, Toast, Sheet; W2d-b NowBanner; W2e-a OtpInput, PhoneField.
- **Not on main, and each blocks only what is named:** W1d (`medioo-web/e2e/budget-ceilings.json` and the enforcing run): **W3 does not merge before it** (DR-42 item 4). W2e-b (MonthCalendar, TrendChart): W8 and W9 start after it. E1/E2-API and the PO's Q9: W10's delete-family step. D11: chat answers (W5's composer stays honest).

**Order and lanes:**

| PR | Branch | Lane | Starts after | Flips |
|---|---|---|---|---|
| W3-api | `feat/baymax-web-signin-state-fallback` | B | now | nothing |
| W3 | `feat/medioo-web-signin` | C | now; **merges after W1d and W3-api** | nothing (pages unreachable until F0) |
| F0 | `feat/medioo-route-preview` | X | W3 merged and deployed | `/app/preview/*`, `/_next/static/*`, `/fonts/*`, `/brand/*` |
| F3 | `feat/medioo-flip-signin` | X | F0 merged; W3's on-device step done | `GET`/`HEAD` `/app`, `/app/`, `/app/verify` |
| W4 | `feat/medioo-web-shell` | C | W3 merged | nothing (no page of its own) |
| W5-api | `feat/baymax-web-api-thread` | B | now | nothing |
| W5 | `feat/medioo-web-chat` | C | W4 and W5-api merged | nothing |
| F5 | `feat/medioo-flip-chat` | X | W5 merged; on-device done | `GET`/`HEAD` `/app/home` |
| W6-api | `feat/baymax-web-api-value-sheet` | B | now | nothing |
| W6 | `feat/medioo-web-paper` | C | W4 and W6-api merged (W5 for the thread aside) | nothing |
| F6 | `feat/medioo-flip-paper` | X | W6 merged; on-device done | `GET`/`HEAD` paper, `/all`, `/values/{obs}` |
| W7 | `feat/medioo-web-upload` | C | W4 merged | nothing |
| F7 | `feat/medioo-flip-upload` | X | W7 merged; on-device done | `GET`/`HEAD` `/app/add` |
| W8 | `feat/medioo-web-papers` | C | W4 and **W2e-b** merged | nothing |
| F8 | `feat/medioo-flip-papers` | X | W8 merged; on-device done | `GET`/`HEAD` `/app/timeline` |
| W9 | `feat/medioo-web-health` | C | W4 and **W2e-b** merged | nothing |
| F9 | `feat/medioo-flip-health` | X | W9 merged; on-device done | `GET`/`HEAD` `/app/health` |
| W10-api | `feat/baymax-web-api-preferences` | B | now | nothing |
| W10 | `feat/medioo-web-family` | C | W4 and W10-api merged | nothing |
| F10 | `feat/medioo-flip-family` | X | W10 merged; on-device done | `GET`/`HEAD` `/app/family`, `/app/patients/{id}/profile` |

Flip PRs serialise with every other merge (RUNBOOK step 1: hold other merges from the box edit until the flip's deploy is green). The master plan's "one PR per screen; each flips its routes after the on-device step" becomes two PRs per screen here, because the RUNBOOK applies a Caddy edit to the box **before** its PR merges: a flip in the screen PR would route families to a page the box is not yet running. *(For PO sight.)*

## Global Constraints

The master plan's [Global Constraints](2026-10-21-medioo-nextjs-rebuild.md#global-constraints), the Wave 2 plan's and the Wave 2b/3 back end plan's [Global Constraints](2026-10-22-medioo-wave2b-3-backend.md#global-constraints) apply **verbatim** to every task here (`gl`, web commands, additive API, the regenerated `app-routes.txt`, `AppApiLogSafetyTest`'s derived list, `{"reason"}` errors, no Bangla literal outside generated files, the colour and NOW-name bans, component styles, new copy keys are drafts, commit commands in full, the ignore check over every touched path, red replays, boards). `CLAUDE.md` applies too. New here, one line each:

- **The DR-42 ceilings are read, never restated.** Every routed page is measured by W1d's `medioo-web/e2e/budget.spec.ts` against `medioo-web/e2e/budget-ceilings.json` (`probe` 214,167 B, `route` 265,409 B). A W PR adds its pages to that file's page list and never touches either number; its PR body shows `git diff origin/main -- medioo-web/e2e/budget-ceilings.json` with only page-list lines.
- **Reading pages work without JavaScript** (DR-42 item 1): the timeline (`/app/timeline`), a paper (`/app/documents/{id}`, `/all`, `/values/{obs}`), a NOW result. Each has a `javaScriptEnabled: false` Playwright case. Every mutation needs JavaScript and goes through `apiSend`; its control without JavaScript is absent or says so in copy, never a form that posts somewhere.
- **Next reads Java with GET only, in one file.** Only `src/api/java.ts` names `JAVA_INTERNAL_URL` (`scripts/check-no-server-mutation.sh` rule 3). It forwards the incoming `cookie` header and no other header, uses `cache: "no-store"`, and is wrapped in React `cache()` so one request reads one path once.
- **No `next/link` prefetch.** Every `<Link>` carries `prefetch={false}`: a prefetched paper is a `GET /app/api/documents/{id}`, which records a DOCUMENT_VIEW nobody made (master W6). An eslint rule refuses a `Link` without it (W4).
- **A screen never decides a patient.** With two or more visible patients and no remembered choice, the page shows the chooser (`switch.title`, `switch.hint`). `?patient=<id>` that is not in `me.patients` is the not-found page, as Java's `ActivePatient.resolve` answers 404.
- **No screen text is logged.** `src/api/*`, pages and components call no `console.*` (eslint `no-console` over `medioo-web/src`, W3). The Next server log of a full e2e run carries none of the fixtures' names, numbers or crop keys (`e2e/server-log.spec.ts`, W3).
- **Inputs carry the boundary token** (PO, PR #120, Q1 option A): every selector a screen adds that paints `--fill` (the composer field, a segmented control's track, a check box, the theme switch) also draws `1px solid var(--boundary)`. W2a's built-CSS `fillBoundaryViolations` and the routed-page `everyFieldAndTrackDrawsTheBoundary` check both.
- **Copy keys a screen adds are drafts** listed in its PR body under "Pending (PO / copy owner)" with both strings. This plan's drafts are named in each task; none ships before the PO's yes.
- **Box steps belong to the operator.** The executing agent reads the box only (`ssh -i ~/Documents/000_office/umr/aws/bdemr-text-analysis.pem ec2-user@<host>`), never edits `/home/ec2-user/medscribe.env` or the box Caddyfile, and lists each box step under `box_steps_pending` with the RUNBOOK step it is.

## PO decisions this plan needs

Each blocks only the step named. A Q that blocks a merge means the PR is built and tested on the default and does not merge until ruled, with the ruling quoted in the PR body.

| # | Decision | Blocks | Default until ruled |
|---|---|---|---|
| S1 | **The no-JS sign-in fallback** (DR-42 Consequences, pending). | F3 merge | **Keep it.** Java serves it at `/app/basic` (W3-api), Caddy leaves it on Java, and the Next sign-in page links to it from `<noscript>`. If the PO accepts the gap instead, W3's no-JS case asserts the page says in copy that JavaScript is needed, and W3-api's `/app/basic` routes are not merged. |
| S2 | **New HTML routes under `/app`.** W3-api adds `/app/basic` and `/app/basic/verify` (GET and POST). The Wave 2 constraint lets a PR add `app-routes.txt` lines under `/app/api/` only. | W3-api merge | Added, and the PR body asks for the snapshot sign-off. |
| S3 | **`/app/login`.** DR-42 item 1 says Caddy moves `/app/`, `/app/login` and `/app/verify`. `/app/login` has no GET today; it is only the number form's POST target, and Java keeps every POST. | nothing | F3 routes `GET`/`HEAD` `/app`, `/app/`, `/app/verify` only; `POST /app/login` and `POST /app/verify` stay on Java (smoke rows assert it). |
| S4 | **Whose messages a share member sees in the chat thread.** `outbound_message.family_id` is the account WhatsApp delivered to. | W5-api merge | Only messages delivered to the viewer's own `family_account` (`family_id = WebSession.familyId()`). A share member sees the papers and their cards, and no message they never received. |
| S5 | **A share member's family reminder switch** (wave 2b–3 plan Q11). | W10 merge | Hidden for a viewer who owns no patient (every `patients[].owner` false): the switch changes nothing they receive. |
| S6 | **Sounds in settings.** The sound bank is W12. | nothing | W10 ships theme and language only; the sounds row lands with W12, which has something to switch. |
| S7 | **Delete-family in পরিবার** (Q9). | the W10 step that adds it | Not built in W10. It lands in a follow-up on E1/E2-API once the PO's written yes exists. |
| S8 | **Copy drafts**, each listed in its task. | the PR that adds each key, at merge | Drafts as written. |

## Review Focus

Inputs no task's main tests exercise, most likely to reach a family first. Each has a test in the task that owns the code.

1. **A Caddy matcher that captures a Java sub-path.** Flipping the paper must not route `/app/documents/{id}/image`, `/page/{n}`, `/status` or `/delete`, nor any `/app/api/**`, to Next: every crop would vanish at once. Owner: F6 (negative smoke rows for each sub-path, replayed red against a `/app/documents/*` matcher) and F0 (the `/app/api/me` row).
2. **A WhatsApp paper link opened, signed out, in a browser without JavaScript** (Opera Mini's extreme mode). The paper must redirect to sign-in with its place held, and the sign-in page must offer the Java fallback. Owner: W3 `theNoScriptLinkCarriesTheHeldPlace` and W6 `anExpiredSessionOnAPaperWithoutJavaScriptReachesTheFallback`.
3. **দেখেছি tapped after the session expired.** The ack must not look done: the client goes to sign-in with the page held, and the banner is still there after sign-in. Owner: W5 `aSeenTapOnAnExpiredSessionGoesToSignInAndKeepsTheBanner`.
4. **One open of a paper recording two views.** A layout and a page each reading the paper, a prefetch, or a React re-render would each write a DOCUMENT_VIEW. Owner: W6 `oneOpenIsOneDocumentRead` (the stub's request log) and the W4 lint ban on prefetching links.
5. **A share member on an owner's screen.** No delete, upload, flag or reminder control renders for them, and a forced write's 403 `VIEW_ONLY` becomes copy, never a crash. Owner: W6 `aShareMemberSeesNoDeleteAndNoRetakeUpload`, W10 `aShareMemberSeesFlagsReadOnly`.

---

## Shared interfaces (read before any task)

**Server reads** (`medioo-web/src/api/java.ts`, W3; server-only, imports `server-only`):
- `type JavaAnswer<T> = { status: 200; body: T } | { status: 400 | 401 | 403 | 404 | 409 | 413 | 415; reason: string }`.
- `javaGet<T>(path: string): Promise<JavaAnswer<T>>`: `GET ${JAVA_INTERNAL_URL}${path}`, header `cookie` copied from the incoming request when present, nothing else, `cache: "no-store"`; wrapped in React `cache()`. A 5xx or a network failure throws (Next's error page); it is never mapped to a reason.
- `signedIn<T>(answer: JavaAnswer<T>, here: string): T`: the body for a 200; `redirect(signInPath(here))` for 401 `UNAUTHENTICATED` (`here` is `requestContext().here`, so a preview page returns to its preview path); `notFound()` for 404; throws otherwise.

**Request context** (`medioo-web/src/api/request.ts`, W3): `requestContext(): Promise<{ lang: Lang; preview: boolean; here: string }>` from the request headers `cookie` (through `langOf`), `x-medioo-preview` (`"1"` only) and `x-medioo-path` (the path and query the browser asked for, preview prefix included).

**Browser writes** (`medioo-web/src/api/browser.ts`, W3):
- `type ApiResult<T> = { kind: "ok"; status: number; body: T | null } | { kind: "refused"; status: number; reason: string } | { kind: "signed-out" } | { kind: "offline" }`.
- `apiSend<T>(method: "POST" | "PUT" | "DELETE", path: string, body?: object | FormData): Promise<ApiResult<T>>`: always `X-Medioo: 1`; `Content-Type: application/json` and `JSON.stringify(body)` unless `body` is `FormData`; `credentials: "same-origin"`. A 401 with `reason: "UNAUTHENTICATED"` calls `window.location.assign(signInPath(location.pathname + location.search))` and resolves `signed-out`. A thrown fetch resolves `offline`.
- `apiGet<T>(path: string): Promise<ApiResult<T>>`: the same 401 rule, for the client polls (paper status).

**Paths** (`medioo-web/src/routing/paths.ts`, W3; F-PRs edit `flipped.ts` only):
- `signInPath(next: string): string` → `"/app/?next=" + encodeURIComponent(next)`. Java's `LocalPath` checks the value when the sign-in JSON receives it; Next never judges it.
- `PREVIEW_PREFIX = "/app/preview"`; `previewPath(path: string): string` → `"/app/preview" + path.slice("/app".length)`.
- `NEXT_PAGES: readonly string[]`: the page patterns this build serves (`"/app"`, `"/app/verify"`, then each screen's), held equal to the page entries of the build's `.next/app-path-routes-manifest.json`, minus `/app/healthz`, `/app/probe`, `/app/catalog-test`, `/app/shell-test` and `/_not-found`, by `routes.built.test.ts` (which fails too if the manifest yields no page).
- `JAVA_PAGES: readonly string[]`: the `GET /app/...` patterns of `docs/infra/app-routes.txt`, held equal to that file by `routes.test.ts`.
- `FLIPPED: readonly string[]` (`src/routing/flipped.ts`): the page patterns Caddy sends to Next, held equal to the `GET` paths parsed from `docs/infra/Caddyfile.medioo` by `flipped.test.ts`.
- `href(path: string, preview: boolean): string`: `previewPath(path)` when `preview` and the path matches `NEXT_PAGES`, else `path`.
- `reachable(path: string, preview: boolean): boolean`: true when the path matches `JAVA_PAGES`, or `NEXT_PAGES` under `preview`, or `FLIPPED`. A tab or link to an unreachable page is not rendered (the স্বাস্থ্য tab before F9).

**Middleware** (`medioo-web/src/middleware.ts`, W3): deletes any incoming `x-medioo-preview` and `x-medioo-path`, sets `x-medioo-path` to the request's path and query, and for `/app/preview` or `/app/preview/<rest>` rewrites to `/app` or `/app/<rest>` with `x-medioo-preview: 1`. The CSP and headers are set exactly as today on both the rewritten and the plain response.

**Preview gate** (`medioo-web/src/app/app/(signed-in)/layout.tsx`, W4): a `preview` request whose `me.preview` is false gets `notFound()`. The sign-in pages (W3) are outside the group and ungated: they show nothing but a form, and the preview path is linked from nowhere.

**e2e support** (W3):
- `medioo-web/e2e/support/scenarios.mjs`: `export const SCENARIOS = { "<name>": { "GET /app/api/me": "me/one-patient.json", … } }`. Keys are `METHOD route-pattern` exactly as `docs/infra/app-routes.txt` spells them; values are paths under `medioo-web/fixtures/api/`. Each screen adds its scenarios.
- `medioo-web/e2e/support/java-stub.mjs`: a Node `http` server. It reads the `baymax_session` cookie `e2e-<scenario>`, matches the request to a pattern, and answers the fixture's `status` and `body`; no session or an unknown scenario is 401 `{"reason":"UNAUTHENTICATED"}` (the sign-in routes excepted); an unmapped route is 404 `{"reason":"NOT_FOUND"}`. It records every request (method, path, query, header names, the `x-medioo` and `origin` values) and serves them at `GET /__requests` (`DELETE /__requests` clears). It logs nothing.
- `medioo-web/e2e/support/browser-api.ts`: `useScenario(page: Page, name: string): Promise<Recorder>` sets `baymax_session=e2e-<name>` for path `/app`, routes the browser's `**/app/api/**` from the same table, and routes `**/app/documents/*/image?key=*` and `**/app/documents/*/page/*` to PNGs from `e2e/support/png.mjs` sized by the fixture's `crop.width` × `crop.height` (or a size derived from the key's hash when null), so each row's image is distinguishable by its pixels. `Recorder.requests()` returns the browser-side requests, parsed (multipart parts by field name).
- `medioo-web/e2e/support/screen-checks.ts`: `screenChecks(route: string, scenario: string, opts?: { crops?: boolean; longest?: boolean })` registers, for both colour schemes: `axeReportsNoViolation`, `noCspViolation` (the `securitypolicyviolation` init script), `noElementOverflowsItsBox` (`scrollWidth <= clientWidth + 1` on every element and on the page), `everyFieldAndTrackDrawsTheBoundary`, `noTextUnderTwelvePixels`, and with `crops` `everyCropIsItsRowsOwnImage`. The implementations move here from `e2e/catalog.spec.ts`, which then imports them (one copy).
- `medioo-web/e2e/board.spec.ts` (runs only with `BOARD=1`): `BOARD_SCENES: { screen: string; state: string; route: string; scenario: string; proto: { file: "phone.html" | "desktop.html"; scene: string } }[]`. For each, a screenshot at phone 390×844 and desktop 1280×800, light and dark, into `test-results/board/<screen>/` (git-ignored), and the prototype at `file://…/docs/design/2026-10-21-approved/<file>` after clicking `[data-scene="<scene>"]`. The executing agent publishes the folder as a private board artifact and links it under "Pending (PO / box)". No screenshot goes into git.

**Flip mechanics** (Task F): `scripts/smoke-https.sh` rows gain an optional method, `POST /app/verify|303|-|location:/app/` (F3 adds it, with its test). Every new row's expected values are measured, not guessed, as W1c did.

**Copy keys this plan drafts** (bn first, en second; each in its task): `login.nojs` "JavaScript ছাড়া ঢুকতে এখানে চাপুন" / "Sign in without JavaScript"; `tab.health` "স্বাস্থ্য" / "Health"; `chat.ask.notyet` "এখনো উত্তর দিতে পারি না" / "I can't answer questions yet"; `crop.retry` "ছবিটা আনা যাচ্ছে না — আবার চেষ্টা করুন" / "The picture won't load — try again"; `doc.gone` "এই কাগজটা আর নেই" / "This paper is no longer here"; `upload.nojs` "কাগজ পাঠাতে JavaScript চালু করুন" / "Turn on JavaScript to send a paper"; `health.*` and `theme.*` in W9 and W10.

---

## Task W3-api: The sign-in state, sign-out, and the no-JS fallback at `/app/basic`

Branch `feat/baymax-web-signin-state-fallback`. Lane B. S1 and S2 apply.

**Files:**
- Create: `baymax/src/main/java/com/elioo/baymax/web/adapter/in/api/SignInStateApiHandler.java`
- Modify: `baymax/src/main/java/com/elioo/baymax/web/adapter/in/api/AppApiRouter.java` (two routes), `AppApiGuard.java` (`OPEN` gains `GET /app/api/sign-in/state`; `POST /app/api/sign-out` needs a session), `baymax/src/main/java/com/elioo/baymax/web/adapter/in/ui/SignInPages.java` (the handlers take the base they serve under)
- Test: `baymax/src/test/java/com/elioo/baymax/web/adapter/in/api/SignInStateApiTest.java`, `SignOutApiTest.java`, `MeApiContractTest.java`, `SignInApiContractTest.java`; `baymax/src/test/java/com/elioo/baymax/web/adapter/in/ui/BasicSignInPagesTest.java`; `AppApiLogSafetyTest.java` (`exercise` entries)
- Generated: `docs/infra/app-routes.txt`; `medioo-web/fixtures/api/me/{one-patient,two-patients,no-patients,preview-on,long-name}.json`, `medioo-web/fixtures/api/sign-in/{confirm,sent,not-sent,not-sent-no-link,invalid-number,code-wrong,code-expired,code-burned,code-format,restart,verified,state-pending-sent,state-pending-not-sent,state-none}.json`

**Interfaces:**
- Consumes: `LoginCookie.read`, `LoginCookie.nextPage`, `WaLink.of`, `WebAuthUseCase.logout`, `SessionAuthFilter.clearedCookie`, `SignInPages` handlers, `Snapshot`.
- Produces:
  - `GET /app/api/sign-in/state` (open, no session): `{"pending": false}` without a valid `baymax_login` cookie; otherwise `{"pending": true, "sent": boolean, "number_tail": "123", "code_ttl_minutes": int, "max_attempts": int, "wa_link"?: string}`, the same fields and values `SignInApiHandler.requested` sends. It reads the cookie Java wrote; Next never parses it.
  - `POST /app/api/sign-out` (session, CSRF): 204 with `SessionAuthFilter.clearedCookie()`, after `WebAuthUseCase.logout(token)`. With no session, 401 as every session route.
  - `GET /app/basic` and `GET /app/basic/` (the number page), `POST /app/basic/login`, `GET /app/basic/verify`, `POST /app/basic/verify`: `SignInPages`' existing pages and handlers, served with base `/app/basic`, so every form `action`, every redirect and the "অন্য নম্বর দিন" link stay under `/app/basic`. Cookies, `next=`, `LocalPath` checks and copy are the same code. The old `/app`, `/app/login`, `/app/verify` routes are unchanged.

- [ ] **Step 1: Write the failing tests.**
  - `SignInStateApiTest`: `withoutTheLoginCookieNothingIsPending` (body exactly `{"pending":false}`); `aSentLoginIsPendingAndSent` (a cookie from `LoginCookie.value(hash, "123", true)` → `pending` true, `sent` true, `number_tail` "123", `code_ttl_minutes` equal to `properties.getAuth().getOtpTtl().toMinutes()`); `aNotSentLoginIsPendingAndNotSent`; `theStateIsTheRequestsOwnFields` (the key set of the state's pending body equals the key set `POST /app/api/sign-in/request` answers for the same outcome, parsed by field); `aTamperedCookieIsNotPending`; `theStateNeedsNoSessionButStaysUnderTheGuard` (no session → 200; `AppApiGuard` access line present).
  - `SignOutApiTest`: `signOutEndsTheSessionAndClearsTheCookie` (204; `Set-Cookie` `baymax_session=` with `Max-Age=0` and `Path=/app`; the next `GET /app/api/me` with the old token is 401); `signOutWithoutXMedioIsRefused` (403 `MISSING_HEADER`); `signOutWithoutASessionIs401`.
  - `BasicSignInPagesTest` (WebTestClient over the real routers): `theBasicNumberPagePostsToBasic` (body contains `action="/app/basic/login"` and `name="whatsapp_number"`, and no `<script`); `aConfirmedNumberRedirectsToTheBasicCodePage` (303 `Location: /app/basic/verify`); `theBasicCodePagePostsToBasic` (`action="/app/basic/verify"`, the other-number link `href="/app/basic/"`); `aNoJsSignInThroughBasicOpensTheHeldPage` (`GET /app/basic?next=/app/documents/<uuid>` → number → confirm → code → `POST /app/basic/verify` with the right code → 303 `Location: /app/documents/<uuid>` and a `baymax_session` cookie; no response in the chain carries `<script`); `aHostileNextThroughBasicOpensHome` (parameterised like `SignInApiHandlerTest.aHostileHeldPlaceOpensHomeAndIsNeverHeld`, same values); `theOldPathsAreUnchanged` (`POST /app/login` confirmed still 303 to `/app/verify`).
  - `MeApiContractTest` and `SignInApiContractTest`: one case per fixture file above, each `eachCaseIsItsCommittedMswFixture`, plus `everyCommittedFixtureIsACurrentCase` per area, as `PaperApiContractTest`. `long-name` names the patient `CatalogFixturesExportTest`'s long name; numbers are `+8801700000xxx`.
- [ ] **Step 2:** Run `gl :baymax:test --tests '*SignInStateApiTest' --tests '*SignOutApiTest' --tests '*BasicSignInPagesTest' --tests '*MeApiContractTest' --tests '*SignInApiContractTest'`. Expected: FAIL — the state and sign-out routes answer 404 `NOT_FOUND` (the catch-all), `/app/basic` answers Java's 404 page, and the fixture tests fail on missing committed files.
- [ ] **Step 3: Implement.** `SignInStateApiHandler.state(ServerRequest)` builds its pending body with the same method `SignInApiHandler` uses (extract `requested` to a package-level static both call). `SignInPages` gets `routesUnder(String base)`; `routes` composes `routesUnder(BASE)` and `routesUnder(BASE + "/basic")` with the `/basic` set ordered before any `{variable}` route. `POST /app/api/sign-out` lives on `SignInApiHandler` as `signOut`.
- [ ] **Step 4:** Run Step 2's command. Expected: the fixture tests print their `cp` commands; run them, then PASS. Run `gl :baymax:test --tests '*AppApiRouteContractTest' --tests '*AppApiLogSafetyTest'`. Expected: FAIL on the snapshot and on two missing `exercise` entries; add the entries, regenerate `docs/infra/app-routes.txt` with the printed command, run again. Expected: PASS, and `git diff origin/main -- docs/infra/app-routes.txt | grep '^-[A-Z*]'` prints nothing.
- [ ] **Step 5: Replay** `aNoJsSignInThroughBasicOpensTheHeldPage` against `SignInPages.login` redirecting to `BASE + "/verify"` regardless of base. Expected: FAIL with `expected: "/app/basic/verify" but was: "/app/verify"`. Restore; quote the line.
- [ ] **Step 6:** `gl build`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 7: Commit.** Explicit paths: the handler, router, guard, `SignInPages.java`, the five tests, `AppApiLogSafetyTest.java`, `docs/infra/app-routes.txt`, and each fixture file by name. Message: `feat(baymax-web): the sign-in state and sign-out as JSON; a no-JS sign-in fallback at /app/basic (plan W3-api)`. PR body: S1, S2 under "Pending (PO / box)".

---

## Task W3: The screen foundation and sign-in

Branch `feat/medioo-web-signin`. Lane C. Merges after W1d and W3-api.

**Files:**
- Create: `medioo-web/src/api/java.ts`, `src/api/request.ts`, `src/api/browser.ts`, `src/api/java.test.ts`, `src/api/browser.test.ts`
- Create: `medioo-web/src/routing/paths.ts`, `src/routing/flipped.ts`, `src/routing/paths.test.ts`, `src/routing/routes.test.ts`, `src/routing/flipped.test.ts`, `src/routing/built/routes.built.test.ts`
- Modify: `medioo-web/src/middleware.ts`, `src/security/csp.test.ts` (rewrite cases), `vitest.built.config.mts` (include `src/**/built/**/*.built.test.ts`), `eslint.config.mjs` (`no-console: error` over `src`), `src/security/lint-bans.test.ts` (the `no-console` case), `next.config.ts` (`skipTrailingSlashRedirect: true`)
- Create: `medioo-web/src/app/app/page.tsx` (welcome and number), `src/app/app/verify/page.tsx` (code), `src/app/app/SignInFlow.tsx` (client island), `src/app/app/sign-in.css`
- Create: `medioo-web/e2e/support/{scenarios.mjs,java-stub.mjs,browser-api.ts,png.mjs,screen-checks.ts}`, `e2e/signin.spec.ts`, `e2e/server-log.spec.ts`, `e2e/board.spec.ts`
- Modify: `medioo-web/e2e/playwright.config.ts` (the stub web server; `JAVA_INTERNAL_URL` for `next start`; the server log teed to `test-results/next-server.log`; a `logs` project depending on `phone` and `desktop`), `e2e/catalog.spec.ts` (imports the moved checks), `e2e/budget.spec.ts` (each page entry's scenario through `useScenario`), `e2e/budget-ceilings.json` (pages `/app`, `/app/verify`)
- Modify: `baymax/src/main/resources/baymax/ui-*_{bn,en}.properties` (`login.nojs`), regenerated `medioo-web/src/copy/generated/copy.{bn,en}.json`

**Interfaces:**
- Consumes: `POST /app/api/sign-in/request` (`{whatsapp_number, confirmed?, next?}` → `{confirm:{number_display}}` or `{sent, number_tail, code_ttl_minutes, max_attempts, wa_link?}`; refusals `INVALID_NUMBER`), `POST /app/api/sign-in/verify` (`{code}` → `{next}`; refusals `CODE_WRONG`, `CODE_EXPIRED`, `CODE_BURNED`, `CODE_FORMAT`, `SIGN_IN_RESTART`), `GET /app/api/sign-in/state` and `GET /app/api/me` (W3-api). Components: `Wordmark`, `MioAvatar` (96, the welcome figure), `PhoneField` (`name="whatsapp_number"`, `error="login.number.invalid"`), `OtpInput` (`length={6}`, `name="code"`, `ttlMinutes`, `error` from `CodeRefusal`), `MessageBubble` (Mio's lines on the welcome), `Toast`. Both inputs already draw `--boundary`.
- Produces: everything under Shared interfaces; the pages `/app` (and `/app/`) and `/app/verify`.

**Board** (master W3): welcome, number, error, code sent, code not sent (wa.me), wrong code, done — beside `phone.html` scene `welcome` and `desktop.html` scene `signin`.

- [ ] **Step 1: Write the failing unit tests.**
  - `paths.test.ts`: `signInPath("/app/documents/x?all=1")` is `"/app/?next=%2Fapp%2Fdocuments%2Fx%3Fall%3D1"`; `previewPath("/app/home")` is `"/app/preview/home"` and `previewPath("/app")` is `"/app/preview"`; `href("/app/home", true)` is the preview path only when `/app/home` is in `NEXT_PAGES` (assert with a page not in it: unchanged); `reachable("/app/health", false)` is false while `FLIPPED` lacks it.
  - `routes.test.ts › javaPagesAreTheSnapshotsGetRoutes`: `JAVA_PAGES` equals the `GET /app…` lines of `docs/infra/app-routes.txt` (no `/app/api`), and the parsed list is non-empty.
  - `flipped.test.ts › flippedIsTheCaddyfilesNextPaths`: the `GET` paths of every `handle` block in `docs/infra/Caddyfile.medioo` whose body is `reverse_proxy medioo-web:3000`, minus `/app/healthz`, `/app/preview/*` and the asset paths, equal `FLIPPED`; and the parser found the `/app/healthz` block (guard against reading nothing).
  - `java.test.ts`: `javaGet` sends `GET` with only the `cookie` header (assert the stubbed `fetch`'s init: `method` "GET", header names exactly `["cookie"]`, `cache` "no-store"); it maps 401 `{"reason":"UNAUTHENTICATED"}` to `{status:401, reason:"UNAUTHENTICATED"}`; it throws on 502; `signedIn` on a 401 calls `redirect` with `signInPath(here)`.
  - `browser.test.ts`: `apiSend("PUT", "/app/api/x", {a:1})` sends `X-Medioo: 1`, `Content-Type: application/json`, body `{"a":1}`; with `FormData` it sets no `Content-Type`; a 401 `UNAUTHENTICATED` assigns `signInPath(location.pathname + location.search)` and resolves `signed-out`; a 403 `{"reason":"VIEW_ONLY"}` resolves `refused` with that reason; a rejected fetch resolves `offline`.
  - `csp.test.ts` gains `aPreviewPathIsRewrittenAndMarked` (`/app/preview/home` → rewrite target `/app/home`, request header `x-medioo-preview: 1`, the CSP set on the response) and `anIncomingPreviewMarkIsDropped` (`/app/home` with `x-medioo-preview: 1` from the client reaches the page without it).
  - `lint-bans.test.ts` gains `console.log in src is refused` (`no-console`).
- [ ] **Step 2:** Run `pnpm -C medioo-web exec vitest run src/routing src/api src/security`. Expected: FAIL with module-not-found for `@/routing/paths` and `@/api/java`, and the two middleware cases failing on `x-medioo-preview` undefined.
- [ ] **Step 3: Implement** the Shared interfaces modules and the middleware change, and set `skipTrailingSlashRedirect: true`.
- [ ] **Step 4:** Run Step 2's command. Expected: PASS. Run `bash scripts/check-no-server-mutation.sh`. Expected: PASS, having scanned `src/api/java.ts`.
- [ ] **Step 5: Write the failing e2e** `e2e/signin.spec.ts` (scenarios `signed-out` and `signed-in`; each sign-in answer from `fixtures/api/sign-in/*`):
  - `bothSpellingsOfTheSignInPageAnswer200`: `GET /app` and `GET /app/` are 200 with `name="whatsapp_number"`, neither a 308.
  - `aSignedInVisitorIsSentOnThroughJavasCheck`: with `signed-in`, `/app/?next=/app/documents/x` answers 307 to `/app/basic?next=%2Fapp%2Fdocuments%2Fx` (Java's `loginPage` redirects a session to the checked place; Next never judges `next`).
  - `theNumberIsShownBackBeforeAnythingIsSent`: typing and pressing `login.next` sends one `POST /app/api/sign-in/request` without `confirmed`; the page shows `confirm.number_display` verbatim and `login.number.yes`; only that press sends `confirmed: true`.
  - `anInvalidNumberIsSaidUnderTheField`: `INVALID_NUMBER` → PhoneField's alert holds `login.number.invalid`, `aria-invalid="true"`.
  - `sentSaysSentAndNotSentNeverDoes`: `sent` → `login.code.sent`; `not-sent` → `login.code.say` + `login.code.word` + the `wa_link` as `href` and no `login.code.sent` anywhere in the DOM; `not-sent-no-link` → no WhatsApp link.
  - `eachCodeRefusalIsItsOwnLine`: one case per `CODE_*` fixture, each asserting OtpInput's alert text is the key's copy (`login.bad.burned` with `max_attempts`).
  - `restartGoesBackToTheNumber`: `SIGN_IN_RESTART` → the number step.
  - `verifiedOpensTheHeldPlace`: `next` from the verify body is the page `location` after the press.
  - `theCodePageSurvivesALeaveToWhatsApp`: a fresh load of `/app/verify` with `state-pending-not-sent` shows the not-sent step with `number_tail`; with `state-none` it answers 307 to `/app/` (`/app/preview/` under the preview path).
  - `theNextIsSentToJavaUnjudged`: `/app/?next=%2F%2Fevil.example` sends `next: "//evil.example"` in the request body (Java refuses it; Next passes it on).
  - `theNoScriptLinkCarriesTheHeldPlace` (`javaScriptEnabled: false`): `/app/?next=/app/documents/x` renders a link to `/app/basic?next=%2Fapp%2Fdocuments%2Fx` with the text `login.nojs`, inside `<noscript>`.
  - `screenChecks("/app", "signed-out")` and `screenChecks("/app/verify", "state-pending-sent")`.
  - `e2e/server-log.spec.ts › theNextServerLogCarriesNoPatientText` (project `logs`): `test-results/next-server.log` holds none of the fixtures' `+8801700000…` numbers, their local forms, their `number_tail` values beside "••", the fixtures' names or any `code` the tests typed; guards: the log is non-empty and the stub's `/__requests` shows at least one `GET /app/api/sign-in/state`.
- [ ] **Step 6:** `pnpm -C medioo-web build && pnpm -C medioo-web e2e e2e/signin.spec.ts`. Expected: FAIL — `/app` is Next's 404 under the policy.
- [ ] **Step 7: Implement the pages.** `page.tsx` reads `requestContext()` and `javaGet("/app/api/me")`; a 200 redirects to `/app/basic?next=<the raw next>`; otherwise it renders the Wordmark, Mio and `SignInFlow`. `SignInFlow` holds the number → confirm → code steps in client state, calls `apiSend` for both POSTs, passes `next` from `useSearchParams` unchanged, and `location.assign`s the verify body's `next`. `verify/page.tsx` reads `GET /app/api/sign-in/state` and renders the code step, or redirects to `href("/app/", preview)`. The `<noscript>` link is in both pages.
- [ ] **Step 8:** Run Step 6's command, then the whole suite: `pnpm -C medioo-web e2e`. Expected: PASS, including `catalog.spec.ts` on the moved checks and `budget.spec.ts` with `/app` and `/app/verify` under 265,409 B.
- [ ] **Step 9: Replays.** (a) In `java.ts`, forward every incoming header; run `java.test.ts`. Expected: `expected [ 'cookie' ] … received [ 'cookie', 'x-medioo-preview', … ]`. (b) In `SignInFlow`, send `confirmed: true` on the first press; run `theNumberIsShownBackBeforeAnythingIsSent`. Expected: FAIL naming the `confirmed` field. (c) Remove the `x-medioo-preview` delete in the middleware; run `anIncomingPreviewMarkIsDropped`. Expected: FAIL naming the header. Restore each; quote the three lines.
- [ ] **Step 10: Gates.** `pnpm -C medioo-web lint && pnpm -C medioo-web lint:css && pnpm -C medioo-web typecheck && pnpm -C medioo-web test && pnpm -C medioo-web build && pnpm -C medioo-web test:built && pnpm -C medioo-web e2e`. Expected: all PASS. Run the ignore check over `medioo-web/src medioo-web/e2e medioo-web/fixtures baymax/src` and `git check-ignore -v medioo-web/test-results/next-server.log` (must print the rule).
- [ ] **Step 11: Board.** `BOARD=1 pnpm -C medioo-web e2e e2e/board.spec.ts`; publish `test-results/board/signin/` privately.
- [ ] **Step 12: Commit** every created and modified file by name. Message: `feat(medioo-web): sign-in screens over the sign-in JSON, a preview path, and the stub-Java e2e harness (plan W3)`. PR body: the replay lines, the budget diff, `login.nojs` as a draft, S1, the board link.

---

## Task F: Flipping a route (one PR per row of the table below)

Branch per the table at the top. Lane X. Runs after its screen PR is merged and deployed and, except F0, after the PO's on-device step.

**Files:**
- Modify: `docs/infra/Caddyfile.medioo` (the row's block, before the bare `handle`), `scripts/smoke-https.sh` (rows, and the header comment's "Routing stops at" line), `scripts/test_smoke_https.sh` (a fake-curl case per new row, both directions), `medioo-web/src/routing/flipped.ts` (the row's patterns; not F0), `docs/RUNBOOK.md` ("Routed today")
- Create (F0 only): `scripts/caddy-route-probe.sh <Caddyfile.medioo> <smoke-https.sh>` and `scripts/test_caddy_route_probe.sh`; `.github/workflows/ci-cd.yml` runs the test in "Deploy script tests". The probe copies the site block with `reverse_proxy medscribe-ai:8086` replaced by `respond "java" 299` and `reverse_proxy medioo-web:3000` by `respond "next" 298`, runs it in a throwaway Caddy container of the box's version on a local port, and sends every row of the smoke table (its method and path; `{old}` rows skipped): a row of class `next` or `static` must get 298, every other row 299. It fails if it read no rows. The routing is thereby derived from the same table the box run checks, not from a second list.

| PR | Caddy block | Smoke rows (expected values re-measured at Step 2) |
|---|---|---|
| F0 | four blocks: `handle /_next/static/* {…}`, `handle /fonts/* {…}`, `handle /brand/* {…}`, `handle /app/preview/* {…}` | `/_next/static/x.js\|404\|static\|-` (Next's 404, no longer Java's JSON one; F0 adds the class `static` to `smoke-https.sh`: the four Caddy/Next headers checked once each, no CSP required, as `next.config.ts` sends `SECURITY_HEADERS` there without a nonce); `/fonts/noto-sans-latin.woff2\|200\|next\|-`; `/brand/mio-head.webp\|200\|next\|-`; `/app/preview/\|200\|next\|html:name="whatsapp_number"`; `/app/api/me\|401\|api\|json:reason=UNAUTHENTICATED` (Java still owns the API) |
| F3 | `@medioo_signin { method GET HEAD; path /app /app/ /app/verify }` + `handle @medioo_signin {…}` | `/app/` class `page` → `next`; `/app/verify\|307\|next\|location:/app/`; `/app/basic\|200\|page\|html:action="/app/basic/login"`; `POST /app/verify\|303\|-\|location:/app/`; `POST /app/login\|200\|page\|html:name="whatsapp_number"` |
| F5 | `@medioo_chat { method GET HEAD; path /app/home }` | `/app/home\|307\|next\|location:/app/?next=%2Fapp%2Fhome` |
| F6 | `@medioo_paper { method GET HEAD; path_regexp ^/app/documents/[0-9a-f-]{36}(/all\|/values/[0-9a-f-]{36})?$ }` | the paper, `/all` and `/values/<uuid>` each `307\|next\|location:/app/?next=…`; and Java still answers, class `page` or `-` as measured: `/app/documents/<uuid>/image?key=x`, `/page/1`, `/status`, `/delete`, and `POST /app/documents/<uuid>/delete` |
| F7 | `@medioo_add { method GET HEAD; path /app/add }` | `/app/add\|307\|next\|location:/app/?next=%2Fapp%2Fadd`; `POST /app/add` still Java's |
| F8 | `@medioo_papers { method GET HEAD; path /app/timeline }` | `/app/timeline\|307\|next\|…`; `/app/patients/<uuid>` still Java's 303 |
| F9 | `@medioo_health { method GET HEAD; path /app/health }` | `/app/health\|307\|next\|…` (Java's 404 before) |
| F10 | `@medioo_family { method GET HEAD; path /app/family }` and `@medioo_profile { method GET HEAD; path_regexp ^/app/patients/[0-9a-f-]{36}/profile$ }` | `/app/family\|307\|next\|…`; `/app/patients/<uuid>/profile\|307\|next\|…`; `POST /app/patients/<uuid>/profile` still Java's |

- [ ] **Step 1: Preconditions,** each pasted into the PR body:
  - the screen's squash sha is an ancestor of the running image: `curl -s https://medioo.eliooo.org/app/healthz | jq -r .sha` → `git merge-base --is-ancestor <screen sha> <that sha> && echo deployed`. Expected: `deployed`.
  - (not F0) the PO's on-device result, quoted: Tanvir opened the screen's preview path on his phone with his family in `BAYMAX_WEB_PREVIEW_FAMILIES` (the box step below), in both themes, and said yes. Until then the PR stays open with the step under `box_steps_pending`.
- [ ] **Step 2: Measure.** Against production, `curl -s -o /dev/null -D - -X <method> https://medioo.eliooo.org<path>` for every row's path, before the change. Record each status and `Location`. For F0 the Next rows are measured through W1d's local Caddy replica against the deployed sha's image.
- [ ] **Step 3: Write the rows first** in `smoke-https.sh` (for F3, add method support: a path column of `METHOD /path`, defaulting to GET, passed as `curl -X`) and their cases in `test_smoke_https.sh`, each case in both directions (the expected answer passes; the pre-flip Java answer fails, naming the row). Run `bash scripts/test_smoke_https.sh`. Expected: PASS. Run `bash scripts/smoke-https.sh https://medioo.eliooo.org`. Expected: FAIL on exactly the new Next rows, every other row PASS.
- [ ] **Step 4: Edit** `Caddyfile.medioo` and (not F0) `flipped.ts`. Run `pnpm -C medioo-web exec vitest run src/routing/flipped.test.ts`. Expected: PASS. Validate the file in a throwaway Caddy of the box's version: `docker run --rm -v "$PWD/docs/infra/Caddyfile.medioo:/etc/caddy/Caddyfile:ro" caddy:<box version> caddy validate --config /etc/caddy/Caddyfile`. Expected: `Valid configuration`. Run `bash scripts/caddy-route-probe.sh docs/infra/Caddyfile.medioo scripts/smoke-https.sh`. Expected: every row PASS (F0 writes the probe first, with its test in both directions: a correct file passes, a file routing `/app/api/*` to Next fails on the `/app/api/me` row).
- [ ] **Step 5 (F6 only): Replay Review Focus 1.** Replace the `path_regexp` with `path /app/documents/*` and run the route probe. Expected: `FAIL /app/documents/<uuid>/image?key=x got 298 (next), expected 299 (java)`. Restore; quote the line.
- [ ] **Step 6: Box (operator, `box_steps_pending`):** RUNBOOK "Routing a path to medioo-web" steps 2–6 with this PR's file: drift check, tenants' codes, backup, in-place write, validate, reload, tenants again, the full table PASS, drift check with the PR's file. Paste the result lines.
- [ ] **Step 7: Merge** (by the PO's merge rule; this plan's pipeline does not merge), then RUNBOOK step 7: record the routes floor with the squash sha and the row's path label, and post `cat ~/medioo-routes-floor` on the PR.
- [ ] **Step 8: Commit** (before Step 6) the listed files by name (F0: with the probe, its test and the workflow). Message: `feat(infra): Caddy sends <paths> to medioo-web (plan <F n>)`.

**The preview family (once, before W3's on-device step; operator):** add Tanvir's family id to `BAYMAX_WEB_PREVIEW_FAMILIES` in `/home/ec2-user/medscribe.env` (back it up first, bare comma-separated UUIDs, `cat -A` check, RUNBOOK's env table), redeploy the running sha, and confirm `GET /app/api/me` answers `preview: true` for him and `false` for a second, fictional test family. Listed under `box_steps_pending` by W3 and F0.

---

## Task W4: The shell — tabs, three columns, the patient switcher, never guessing, and the 401 flow

Branch `feat/medioo-web-shell`. Lane C. No route of its own; its board renders inside a placeholder page that W5 replaces.

**Files:**
- Create: `medioo-web/src/app/app/(signed-in)/layout.tsx` (me, preview gate, shell), `src/app/app/(signed-in)/shell-test/page.tsx` (a page that exists only in the catalog build, `MEDIOO_CATALOG=1`, holding the three slots' outlines for the board and the overflow checks)
- Create: `medioo-web/src/components/AppShell/{AppShell.tsx,AppShell.css,AppShell.test.tsx}`, `src/components/TabBar/{TabBar.tsx,TabBar.css,TabBar.test.tsx}`, `src/components/PatientSwitcher/{PatientSwitcher.tsx,PatientSwitcher.css,PatientSwitcher.test.tsx}`, `src/components/PatientChooser/{PatientChooser.tsx,PatientChooser.css,PatientChooser.test.tsx}`, `src/app/app/(signed-in)/active-patient.ts` (+ `.test.ts`)
- Modify: `medioo-web/eslint.config.mjs` and `src/security/lint-bans.test.ts` (a `Link` without `prefetch={false}` is refused), `src/api/types.ts` (`MeJson`, pinned by `fixtures/api/me/*` in `types.test.ts`), `e2e/support/scenarios.mjs` (`me-one`, `me-two`, `me-none`, `me-long`, `me-preview-off`), `baymax/.../ui-*_{bn,en}.properties` (`tab.health`)
- Create: `medioo-web/e2e/shell.spec.ts`; modify `e2e/playwright.config.ts` so the `catalog` project also runs `shell.spec.ts` (`/app/shell-test` exists only in the catalog build; the production projects keep asserting it is a 404 through `catalog-off.spec.ts`, which gains the path)

**Interfaces:**
- Consumes: `GET /app/api/me` (`{family:{id, owner_name}, patients:[MePatient…], active_patient_id, preview}`), `PUT /app/api/active-patient` (`{patient_id}` → 204 + `baymax_patient`), `POST /app/api/sign-out` (W3-api). Components: `Wordmark`, `MioAvatar` (32 in the toolbar), `Sheet` (the switcher on phone), `StatusChip`-free (the switcher's red mark is `me.patients[].urgent`, drawn as the NOW token's dot + shape + the word from `attn.strip`).
- Produces:
  - `activePatient(me: MeJson, asked: string | null): { kind: "patient"; patient: MePatient } | { kind: "choose" } | { kind: "none" } | { kind: "not-found" }`: `asked` (`?patient=`) if in `me.patients`, else `not-found`; else `active_patient_id` if still in the list; else the only patient; else `choose` (two or more) or `none` (zero). The same order as `ActivePatient.resolve`.
  - `AppShell({ lang, preview, me, tab, main, aside?, banner? })`: below 900 px, main then the bottom `TabBar`; 900–1199 px, a toolbar button (`aria-expanded`, `aria-controls`) that shows the sidebar; at 1200 px and wider, sidebar 256 · main · aside 400. `banner` renders above everything (W5 mounts the NowBanner here).
  - `TabBar({ lang, preview, current })`: চ্যাট `/app/home`, কাগজপত্র `/app/timeline`, স্বাস্থ্য `/app/health`, পরিবার `/app/family`, each rendered only when `reachable(path, preview)`, each link `href(path, preview)`, `aria-current="page"` on the current one, each 48 px tall.

**Board** (master W4): layouts at 390, 1024 and 1440 px wide × 0, 1 and 2 patients, light and dark, beside `phone.html` scene `chat` (the tab bar) and `desktop.html` scene `app` (the columns).

- [ ] **Step 1: Write the failing tests.**
  - `active-patient.test.ts`: each branch once with the `me/*` fixtures; and `aRememberedPatientWhoIsGoneIsForgottenNotGuessed` (`active_patient_id` not in the list, two patients → `choose`).
  - `TabBar.test.tsx`: `theHealthTabIsAbsentUntilItsRouteIsReachable` (not preview, `FLIPPED` without `/app/health` → three links; preview with `/app/health` in `NEXT_PAGES` → four, the fourth `/app/preview/health`); `everyTabSaysItsWord` (each link's name is its `tab.*` copy).
  - `PatientChooser.test.tsx`: `theChooserListsEveryPatientAndPicksNone` (one link per patient, `href` `?patient=<id>`, none marked current); `anUrgentPatientIsMarkedByShapeAndWord` (the NOW dot plus `attn.strip` text, never colour alone).
  - `PatientSwitcher.test.tsx`: `choosingPutsTheChoiceAndNavigates` (one `apiSend("PUT", "/app/api/active-patient", {patient_id})`, then a navigation to the same tab with `?patient=`); `aRefusedChoiceSaysSo` (404 → `app.err.notfound` in a status line, nothing navigates).
  - `lint-bans.test.ts`: `a Link without prefetch={false} is refused` and `a Link with prefetch={false} is clean`.
  - `e2e/shell.spec.ts`: `theLayoutIsPhoneBelow900` (at 899 px the TabBar is visible and no sidebar); `theSidebarHidesBehindTheToolbarFrom900To1199` (at 1024 px the sidebar is hidden until the toolbar button is pressed, `aria-expanded` follows); `threeColumnsFrom1200` (at 1440 px the sidebar is 256 px and the aside 400 px wide, measured by `getBoundingClientRect`); `twoPatientsAndNoChoiceShowTheChooser`; `anUnknownPatientParamIsNotFound` (`?patient=<other uuid>` → 404 page); `aNonPreviewFamilyGets404OnThePreviewPath` (scenario `me-preview-off`, `/app/preview/shell-test` → 404); `noSessionRedirectsWithTheHeldPlace` (`/app/preview/shell-test?x=1` with no session → 307 `Location: /app/?next=%2Fapp%2Fpreview%2Fshell-test%3Fx%3D1`); `aMidPage401GoesToSignInWithThePlace` (Review Focus 4, client side: the stub answers the switcher's PUT with 401 → the page lands on `signInPath` of the page it was on); `signOutEndsAtTheSignInPage` (one `POST /app/api/sign-out` with `X-Medioo`, then `/app/`); `screenChecks("/app/shell-test", "me-long", { longest: true })` at 390, 1024 and 1440.
- [ ] **Step 2:** `pnpm -C medioo-web exec vitest run src/components/AppShell src/components/TabBar src/components/PatientChooser src/components/PatientSwitcher src/app src/security/lint-bans.test.ts`. Expected: FAIL on missing modules and on the `Link` lint case reporting nothing.
- [ ] **Step 3: Implement** the layout, `activePatient` and the four components. CSS uses only tokens; the switcher's urgent mark is the only NOW use and lives in a class the NOW-name ban allows only under `src/components/safety/**`, so it is a small `src/components/safety/UrgentMark/UrgentMark.tsx` (+ `.css`, + test `anUrgentMarkIsDotShapeAndWord`).
- [ ] **Step 4:** Run Step 2's command, then `pnpm -C medioo-web build:catalog && pnpm -C medioo-web e2e e2e/shell.spec.ts`. Expected: PASS.
- [ ] **Step 5: Replays.** (a) `activePatient` returning the first patient instead of `choose`; run `twoPatientsAndNoChoiceShowTheChooser`. Expected: FAIL naming the chooser heading `switch.title` absent. (b) `apiSend` ignoring 401; run `aMidPage401GoesToSignInWithThePlace`. Expected: FAIL naming the URL. Restore; quote both.
- [ ] **Step 6: Gates** as W3 Step 10. **Board**: `BOARD=1 …` and publish `test-results/board/shell/`.
- [ ] **Step 7: Commit** each file by name. Message: `feat(medioo-web): the shell — tabs, three columns, a switcher that never guesses, sign-in on a 401 (plan W4)`. PR body: `tab.health` draft, replays, board.

---

## Task W5-api: The chat thread — `GET /app/api/patients/{id}/thread`

Branch `feat/baymax-web-api-thread`. Lane B. S4 applies.

**Files:**
- Create: `baymax/src/main/java/com/elioo/baymax/web/adapter/in/api/ThreadApiHandler.java`, `ThreadJson.java`; `baymax/src/main/java/com/elioo/baymax/web/application/port/in/ThreadUseCase.java`; `web/application/service/ThreadService.java`; `web/application/port/out/ThreadReadPort.java`; `web/adapter/out/persistence/PostgresThreadReadAdapter.java`; `web/domain/ThreadItem.java`
- Modify: `ResultUseCase.java` and `ResultService.java` (`card`), `AppApiRouter.java` (one route), `AppApiLogSafetyTest.java`
- Test: `baymax/src/test/java/com/elioo/baymax/web/adapter/in/api/ThreadApiContractTest.java`, `ThreadApiAuditTest.java`; `web/adapter/out/persistence/PostgresThreadReadAdapterTest.java`
- Generated: `docs/infra/app-routes.txt`; `medioo-web/fixtures/api/thread/{default,now,retake,first-run,share-member,second-page}.json`

**Interfaces:**
- Consumes: `PaperJson.of`, `PaperJson.reading`, `PaperJson.withNames`, `TimelineUseCase.me` (visibility), `outbound_message` (V9, V13, V14).
- Produces:
  - `ResultUseCase.card(UUID familyId, UUID documentId): Mono<Object>` — a `ResultView` or `ReadingView` read through the same visibility as `result`, **recording no audit row**.
  - `GET /app/api/patients/{id}/thread?cursor=` → `{"items": [...], "next_cursor": string|null}`, newest first, 20 items a page. An item is `{"kind": "paper", "at": <received_at>, "card": <PaperJson body with names>}` or `{"kind": "message", "at": <sent_at>, "message_kind": "explanation"|"detail"|"retake"|"nudge", "document_id": string|null, "body": string}`. A message is listed only when `sent_at IS NOT NULL` (V14: the provider accepted it), `patient_id` is this patient, and `family_id` is the viewer's own account (S4); `body` is the stored text as sent. A patient this family cannot see, or a malformed id or cursor, is 404 `NOT_FOUND` / 400 `INVALID`.

- [ ] **Step 1: Write the failing tests.**
  - `PostgresThreadReadAdapterTest` (Testcontainers): `onlyDeliveredMessagesAreListed` (rows with `sent_at` NULL — pending, rejected, failed_safety, a failed delivery — are absent; the delivered one present, matched by id); `anotherAccountsMessagesAreNotListed` (the share member's read of the owner's patient lists the papers and none of the owner's messages); `papersAndMessagesInterleaveByTime` (three of each with interleaved instants come back in descending `at`, by id); `theCursorPagesWithoutRepeatsOrGaps` (45 items → pages of 20, 20, 5, every id once).
  - `ThreadApiContractTest`: one case per fixture, `eachCaseIsItsCommittedMswFixture`, `everyCommittedFixtureIsACurrentCase`; `noDeliveryOrGateFieldReachesTheBrowser` (no key `wamid`, `delivery_status`, `reviewer`, `reject_reason`, `gate_status`, `confidence`, `model`, `status_reason` anywhere in the parsed tree, walked by field); `aPaperCardIsThePaperJsonsOwnBody` (each `card` equals `GET /app/api/documents/{id}`'s body for the same paper, parsed, field by field); `aPatientOfAnotherFamilyIsNotFound`.
  - `ThreadApiAuditTest` (Testcontainers): `readingTheThreadRecordsNoView` — the count of `audit_event` rows with `kind = 'document_view'` (V8, queried by column) is unchanged across a thread read of a patient with five papers; guard: one `GET /app/api/documents/{id}` in the same test adds exactly one.
- [ ] **Step 2:** `gl :baymax:test --tests '*Thread*'`. Expected: FAIL — the route answers the catch-all 404 and the adapter class does not exist.
- [ ] **Step 3: Implement** the port, adapter (one SQL over `document` and `outbound_message`, visibility through `PostgresHealthRecordAdapter.VISIBLE_SQL`), service, `card` and the handler; register the route before the catch-all.
- [ ] **Step 4:** Run Step 2's command, copy the fixtures with the printed `cp` commands, run again. Expected: PASS. Then `gl :baymax:test --tests '*AppApiRouteContractTest' --tests '*AppApiLogSafetyTest'`, regenerate the snapshot, again. Expected: PASS.
- [ ] **Step 5: Replays.** (a) Drop `sent_at IS NOT NULL` from the SQL; run `onlyDeliveredMessagesAreListed`. Expected: FAIL naming the pending row's id. (b) Make `card` call `result`; run `readingTheThreadRecordsNoView`. Expected: `expected: 0 but was: 5`. Restore; quote both.
- [ ] **Step 6:** `gl build`. **Commit** each file by name. Message: `feat(baymax-web): the chat thread JSON — delivered messages and paper cards, no view recorded (plan W5-api)`. PR body: S4.

---

## Task W5: চ্যাট — the thread, the global NOW banner with দেখেছি, the honest composer

Branch `feat/medioo-web-chat`. Lane C. After W4 and W5-api.

**Files:**
- Create: `medioo-web/src/app/app/(signed-in)/home/page.tsx`, `src/components/Thread/{Thread.tsx,Thread.css,Thread.test.tsx}`, `src/components/Composer/{Composer.tsx,Composer.css,Composer.test.tsx}`, `src/components/AttentionStack/{AttentionStack.tsx,AttentionStack.css,AttentionStack.test.tsx}`, `src/components/safety/NowBanner/NowBannerLive.tsx` (client wrapper holding the banner state, passing `onSeen`)
- Modify: `src/app/app/(signed-in)/layout.tsx` (the banner on every signed-in page), `src/components/safety/NowBanner/NowBannerSeen.tsx` (send through `apiSend`, so a 401 goes to sign-in), `src/components/safety/VerdictCard/VerdictCard.tsx` (the thread's additions from PR #127's amendment: `values[]` chips through `StatusChip` with Java's `chip_key`, `MioAvatar`, the card as a link to the paper), `src/api/types.ts` (`ThreadJson`, `AttentionJson`), `e2e/support/scenarios.mjs`, `e2e/budget-ceilings.json` (`/app/home`), `src/routing/paths.ts` (`NEXT_PAGES` gains `/app/home`), `baymax/.../ui-*_{bn,en}.properties` (`chat.ask.notyet`)
- Create: `medioo-web/e2e/chat.spec.ts`

**Interfaces:**
- Consumes: `GET /app/api/me`, `GET /app/api/now-banner` (`{show, line_key, call_999, documents[], seen[]}`), `PUT /app/api/now-banner/ack` (`{seen}` → the banner re-read), `GET /app/api/patients/{id}/attention` (`{items:[{kind: urgent|retake|follow_up|reading, …, action_key}]}`), `GET /app/api/patients/{id}/thread` (W5-api). Components: `NowBanner` + `NowBannerSeen` (W2d-b), `VerdictCard` (W2d-a), `MessageBubble` (`recv` for Medioo, `sent` for the family's own typed line), `MioAvatar` (still on every NOW surface), `StatusChip`, `Toast`. The composer field paints `--fill` and draws `--boundary`.
- Produces: the page `/app/home` (`?patient=` honoured); `Thread({ items, lang, preview })`; `Composer({ lang, owner, patientId, preview })`.

**Board** (master W5): default, NOW, retake, first run — beside `phone.html` scenes `chat`, `now`, `retake`, `first` and `desktop.html` scenes `app`, `first`.

- [ ] **Step 1: Write the failing tests.**
  - `Thread.test.tsx`: `aMessageBodyIsShownAsSentAndNothingElse` (the `body` text verbatim in a `recv` bubble, no other text in it); `aPaperCardIsVerdictCardOverItsCard`; `aRoutinePaperCarriesNoChip` (the `routine-released` card renders no `.status-chip`); `everyValueChipIsTheOneJavaNamed` (for each `card.values[]`, the chip's text is `t(lang, chip_key)`, by row); `thePendingWordingIsNeverShown` (a `this-week-pending` card renders no `wording` text and the `result.gate.pending` line).
  - `Composer.test.tsx`: `askingSendsNothingAndSaysNotYet` (type a fictional question, press send: a `sent` bubble with the typed text, then a `recv` bubble `chat.ask.notyet`; the `fetch` spy was called zero times); `theTypedTextIsNotStored` (`localStorage`, `sessionStorage` and `document.cookie` unchanged after a send); `onlyTheOwnerHasTheCamera`.
  - `AttentionStack.test.tsx`: `cardsComeInJavasOrderEachWithOneAction` (the `full-stack` fixture's order kept; each card exactly one link, named `attn.open`); `anEmptyStackIsSilence` (no heading, no reassurance text).
  - `e2e/chat.spec.ts`: `theNowLineIsShippedVerbatimAndTheNameIsTheTitles` (the alert's sentence is exactly `t("bn","urgency.now")`; the patient's name appears in the title element and not inside that sentence); `999OnlyWhenJavaSaysBangladesh` (`now-banner/one` has a `tel:999` link; `now-banner/abroad` has none anywhere on the page); `seenSendsTheSeenSetAsReceived` (the PUT body deep-equals the fixture's `seen`, parsed); `seenHidesOnlyWhatTheServerSays` (the stub answers `acked.json` → banner gone; the patient's red mark in the switcher stays); `aStaleSeenKeepsTheBanner` (the stub answers `show: true` → banner still shown); `aSeenTapOnAnExpiredSessionGoesToSignInAndKeepsTheBanner` (Review Focus 3: 401 → `signInPath("/app/home")`; after `signed-in` again the banner is shown); `theBannerIsOnEveryPage` (with the NOW scenario, the alert is present on `/app/home`, and on `/app/shell-test` in the `catalog` project); `mioDoesNotMoveOnNow` (computed `animation-name` of every Mio under the banner is `none`); `aFirstRunPatientGetsTheIntroAndOneCamera` (`chat.intro` bubble; one camera link, owner only); `aNowResultIsReadableWithoutJavaScript` (`javaScriptEnabled: false`: the banner's sentence, the NOW card and `now.notdoctor` render; the seen button is absent); `theDisclaimerIsOnThePage` (`disclaimer` copy present whenever a result card is); `screenChecks("/app/home", "chat-default", { longest: true, crops: true })` and for `chat-now`.
- [ ] **Step 2:** `pnpm -C medioo-web exec vitest run src/components/Thread src/components/Composer src/components/AttentionStack src/components/safety` then `pnpm -C medioo-web build && pnpm -C medioo-web e2e e2e/chat.spec.ts`. Expected: FAIL — missing modules, then `/app/home` is Next's 404.
- [ ] **Step 3: Implement.** The page reads `me`, `now-banner`, `attention` and `thread` with `javaGet` in parallel; main is the greeting (`chat.hello` with `owner_name`, `chat.hello.plain` without), the attention stack, the thread and the composer; at ≥1200 px the aside holds the attention stack and main the thread. The camera opens `/app/add?patient=<id>` until W7, `href`'d.
- [ ] **Step 4:** Run Step 2's commands. Expected: PASS. `pnpm -C medioo-web test:built` (the built-CSS NOW check sees the new classes only under `safety/`). Expected: PASS.
- [ ] **Step 5: Replays.** (a) `NowBannerSeen` sending `seen` rebuilt from `documents`; run `seenSendsTheSeenSetAsReceived`. Expected: FAIL naming `reasons_digest`. (b) The banner rendered from `call_999: true` regardless; run `999OnlyWhenJavaSaysBangladesh`. Expected: FAIL naming `tel:999`. (c) The composer `fetch`ing `/app/api/chat`; run `askingSendsNothingAndSaysNotYet`. Expected: `expected 0 calls, received 1`. Restore; quote all three.
- [ ] **Step 6: Gates** as W3 Step 10. **Board** `test-results/board/chat/`. **Commit** each file by name. Message: `feat(medioo-web): চ্যাট — the thread as delivered, the global NOW banner with দেখেছি, a composer that says it cannot answer yet (plan W5)`. PR body: `chat.ask.notyet` draft, replays, board, the on-device step for F5.

---

## Task W6-api: One value's sheet — `GET /app/api/documents/{id}/values/{obs}`

Branch `feat/baymax-web-api-value-sheet`. Lane B.

**Files:**
- Create: `baymax/src/main/java/com/elioo/baymax/web/adapter/in/api/ValueSheetApiHandler.java`
- Modify: `PaperJson.java` (a public `valueRow` used by `of` and the sheet), `AppApiRouter.java` (the route, before `/documents/{id}`), `AppApiLogSafetyTest.java`
- Test: `baymax/src/test/java/com/elioo/baymax/web/adapter/in/api/ValueSheetApiContractTest.java`
- Generated: `docs/infra/app-routes.txt`; `medioo-web/fixtures/api/value-sheet/{inside,outside-high,unknown-one-sided,earlier-three,earlier-none,not-found}.json`

**Interfaces:**
- Consumes: `ResultUseCase.earlierReadings(familyId, documentId, observationId)` (404 for another family and for an observation not on this paper, before anything is read), then `ResultUseCase.result` (one DOCUMENT_VIEW, as the HTML sheet records).
- Produces: `{"paper": {id, document_type, date, date_is_upload_day, date_uncertain, patient:{id, name, name_possessive}}, "value": <the PaperJson value row: id, name, value, unit, ref_low, ref_high, display_kind, side, chip_key, pin, in_now_block, crop>, "earlier": [{document_id, observation_id, value, unit, crop:{src, width, height}, date, date_is_upload_day}]}`.

- [ ] **Step 1: Write the failing tests** in `ValueSheetApiContractTest`: one case per fixture; `theValueIsThePapersOwnRow` (equal, field by field, to the row with that id in `GET /app/api/documents/{id}`); `anObservationOfAnotherPaperIsNotFoundAndRecordsNoView` (404 `NOT_FOUND`; the `audit_event` count with `kind = 'document_view'` unchanged); `oneSheetReadIsOneView` (that count rises by exactly 1); `everyEarlierReadingCarriesItsOwnCrop` (each `crop.src` names that reading's document id); `aOneSidedInsideIsUnknownOnTheSheetToo`.
- [ ] **Step 2:** `gl :baymax:test --tests '*ValueSheetApiContractTest'`. Expected: FAIL — `GET /app/api/documents/{id}/values/{obs}` answers the catch-all 404.
- [ ] **Step 3: Implement**; **Step 4:** run, copy fixtures, run. Expected: PASS; then the route and log-safety tests, the snapshot. **Step 5: Replay** `anObservationOfAnotherPaperIsNotFoundAndRecordsNoView` with `result` read before `earlierReadings`. Expected: `expected: 0 but was: 1`. **Step 6:** `gl build`; **commit** by name. Message: `feat(baymax-web): one value's sheet as JSON, the earlier readings each with its crop (plan W6-api)`.

---

## Task W6: The paper — summary, `/all`, one value, every gate state, delete

Branch `feat/medioo-web-paper`. Lane C. Owns master Review Focus 2 and 5.

**Files:**
- Create: `medioo-web/src/app/app/(signed-in)/documents/[id]/page.tsx`, `documents/[id]/all/page.tsx`, `documents/[id]/values/[obs]/page.tsx`, `documents/[id]/paper-read.ts` (the one cached read)
- Create: `medioo-web/src/components/Paper/{PaperSummary.tsx,PaperAll.tsx,ValueSheet.tsx,Paper.css,Paper.test.tsx}`, `src/components/Crop/{Crop.tsx,Crop.css,Crop.test.tsx}` (client: the retry state), `src/components/PageImage/{PageImage.tsx,PageImage.css}` (zoom in a `Sheet`), `src/components/ReadingProgress/{ReadingProgress.tsx,ReadingProgress.css,ReadingProgress.test.tsx}` (polls status), `src/components/DeletePaper/{DeletePaper.tsx,DeletePaper.css,DeletePaper.test.tsx}`, `src/components/safety/NowBlock/{NowBlock.tsx,NowBlock.css,NowBlock.test.tsx}`
- Modify: `src/api/types.ts` (`ValueSheetJson`), `e2e/support/scenarios.mjs`, `e2e/budget-ceilings.json` (three pages), `src/routing/paths.ts`, `baymax/.../ui-*_{bn,en}.properties` (`crop.retry`, `doc.gone`)
- Create: `medioo-web/e2e/paper.spec.ts`

**Interfaces:**
- Consumes: `GET /app/api/documents/{id}` (PaperJson: `status`, `gate`, `wording` only for RELEASED, `review_hours` only PENDING, `banner`, `now.{notdoctor_key, flags_go_key, bring_key, call_999, remote_share}`, `reasons`, `counts`, `values[]`, `medicines[]`, `follow_up[]`, `doctor`, `retake.{reason_key, ordinal}`, `owner`, `viewer.bangladesh`, `pages[]`), `GET /app/api/documents/{id}/status` (polled, records nothing), `GET /app/api/documents/{id}/values/{obs}` (W6-api), `GET /app/api/documents/{id}/delete-token` → `DELETE /app/api/documents/{id}` `{confirm_token}`. Images go to Java's `/app/documents/{id}/image?key=` and `/page/{n}` (never flipped). Components: `VerdictCard`, `StatusChip` (Java's `chip_key`, `chipKindOf(display_kind, side)`), `MioAvatar` (still on NOW), `Sheet` (zoom, delete confirm), `Toast` (deleted), `MessageBubble` (the released wording, `result.says.label` + `result.says.note`).
- Produces: `paperRead(id: string)` — `javaGet` of the paper, cached for the request, so layout, page and metadata share one read.

**Board** (master W6): RELEASED, PENDING, withheld, NOW, NOW abroad, retake (third), failed, reading, multi-page `/all`, a value's sheet, delete confirm, gone — beside `phone.html` scenes `now`, `retake` and `desktop.html` scene `app` (the paper column).

- [ ] **Step 1: Write the failing tests.**
  - `Paper.test.tsx`, one per fixture of `fixtures/api/documents/`: `wordingOnlyWhenReleased` (the wording text present for `routine-released`, absent in every other fixture's render); `pendingSaysWhoAndHowLong` (`result.gate.pending` with `review_hours`); `aRoutinePaperIsFiledAndUnchipped`; `theCountsLineIsJavas` (`counts.line_key` with `values` and `outside`, never "০টি বাইরে"); `aSidelessOrOneSidedValueIsUnknown` (`display_kind` unknown → the `unk` chip and `sheet.unknown.why`, never `value.chip.inside`); `medicinesAreOnlyTheirWrittenFields` (each rendered medicine shows exactly the fields present in its JSON object, in `PaperDisplay.MEDICINE_FIELDS` order, and its crop).
  - `NowBlock.test.tsx`: `theNowBlockSaysNotDoctorItself` (`now.notdoctor` inside the block, not only in the footer); `abroadGetsTheRemoteLinesAndNo999` (`now-abroad`: `now.remote.flags.go`, `now.bring.remote`, the share action, no `tel:`); `theBlockRowsAreThoseJavaFlagged` (only `in_now_block` rows, in Java's order); `reasonsAreInJavasOrder`.
  - `Crop.test.tsx`: `aCropThatFailsShowsRetryNeverABlank` (an `error` event → `crop.retry` with a retry button; pressing it sets the same `src` again with a cache-busting fragment never sent to the server); `aCropIsLaidOutAtItsStoredSize` (`width`/`height` attributes from the JSON; null → none).
  - `DeletePaper.test.tsx`: `deleteAsksThenDeletesWithTheToken` (button → Sheet `doc.delete.q` / `doc.delete.warn` → `doc.delete.yes` → GET token then DELETE with `{confirm_token}`); `confirmRequiredStartsAgain` (400 `CONFIRM_REQUIRED` → a fresh token on the next yes).
  - `ReadingProgress.test.tsx`: `pollsStatusAndRefreshesWhenDone` (fake timers: every 5 s one `GET …/status`; `DONE` → `router.refresh()` once; polling stops).
  - `e2e/paper.spec.ts`: `everyValuesImageIsItsOwnRowsCrop` (for each `values[]` row, the `<img>` inside that row has `src === row.crop.src` and, once loaded, `naturalWidth === row.crop.width` from the stub's sized PNG; guard: at least one row checked); `anExpiredCropShowsRetry` (master Review Focus 2: the stub answers one crop key 403 → that row shows `crop.retry`, every other row's image `naturalWidth > 0`); `aDeletedPaperSaysItIsGone` (master Review Focus 5: the paper GET answers 404 `NOT_FOUND` → `doc.gone` and a link back, no error page); `aDeleteRacedByAnotherMemberSaysGone` (the DELETE answers 404 → `doc.gone`); `oneOpenIsOneDocumentRead` (Review Focus 4: open the paper from the timeline link, then go to `/all` and back; the stub's `/__requests` holds exactly one `GET /app/api/documents/{id}` per navigation that opened the paper, none from hovering or scrolling past paper links); `aShareMemberSeesNoDeleteAndNoRetakeUpload` (`owner: false`: no `detail.delete`, no `retake.again`, `retake.viewonly` shown); `aPaperIsReadableWithoutJavaScript` (`javaScriptEnabled: false`: values, chips, crops and the NOW block render; the delete button is absent); `anExpiredSessionOnAPaperWithoutJavaScriptReachesTheFallback` (Review Focus 2: no JS, no session → 307 to `/app/?next=%2Fapp%2Fdocuments%2F<id>`, whose `<noscript>` link is `/app/basic?next=%2Fapp%2Fdocuments%2F<id>`); `zoomOpensThePageImage`; `aValueSheetListsEarlierReadingsEachWithItsCrop`; `screenChecks` for the paper (`now-bd`, `routine-released`, `retake-third`), `/all` (the 58-value case: `KNOWN_OVER` on Java, here within 265,409 B or the PR stops and says so) and a value sheet, with `crops: true, longest: true`.
- [ ] **Step 2:** the Vitest and Playwright commands for these files. Expected: FAIL on missing modules, then Next's 404.
- [ ] **Step 3: Implement.** Pages read `paperRead(id)` only. Paper links everywhere use `prefetch={false}` (the lint enforces it).
- [ ] **Step 4:** Run. Expected: PASS; `test:built` PASS.
- [ ] **Step 5: Replays.** (a) `Crop` rendering the first crop of the paper for every row; run `everyValuesImageIsItsOwnRowsCrop`. Expected: FAIL naming the row id and both `src` values. (b) The `/all` page calling `javaGet` itself besides `paperRead`; run `oneOpenIsOneDocumentRead`. Expected: `expected 1 … received 2`. (c) `wording` rendered whenever present in a mutated fixture with gate `PENDING`; run `wordingOnlyWhenReleased`. Expected: FAIL naming the gate. Restore; quote all three.
- [ ] **Step 6: Gates**, **board** `test-results/board/paper/`, **commit** by name. Message: `feat(medioo-web): the paper — every gate state, NOW and abroad, each value beside its own crop, delete with a token (plan W6)`. PR body: `crop.retry`, `doc.gone` drafts; replays; board; the `/all` budget number.

---

## Task W7: Upload — the sheet, the page tray, drag and drop, retry with the same key

Branch `feat/medioo-web-upload`. Lane C. Owns master Review Focus 3. Java keeps `POST /app/add` until W-del.

**Files:**
- Create: `medioo-web/src/app/app/(signed-in)/add/page.tsx` (the sheet as a page; `<noscript>` says `upload.nojs`), `src/components/Upload/{UploadSheet.tsx,PageTray.tsx,DropZone.tsx,UploadProgress.tsx,Upload.css,upload-reasons.ts,Upload.test.tsx,upload-reasons.test.ts}`
- Modify: `src/components/Composer/Composer.tsx` and W8's add buttons when they exist (open the sheet in place, falling back to `/app/add`), `src/api/types.ts` (`UploadOptionsJson`, `UploadAnswerJson`), `e2e/support/scenarios.mjs`, `e2e/budget-ceilings.json` (`/app/add`), `src/routing/paths.ts`, `baymax/.../ui-*_{bn,en}.properties` (`upload.nojs`)
- Create: `baymax/src/test/java/com/elioo/baymax/web/adapter/in/api/UploadApiContractTest.java` (fixtures `medioo-web/fixtures/api/upload-options/{owner-one,owner-two,view-only,cap-reached}.json`, `medioo-web/fixtures/api/uploads/{one-document,separate,refusals}.json`); modify `UploadApiRealServerTest.java` (one test)
- Create: `medioo-web/e2e/upload.spec.ts`

**Interfaces:**
- Consumes: `GET /app/api/upload-options` (who the viewer may send for, view-only patients, the one preselect, allowance, `limits.max_pages`, `limits.max_files`, `max_file_bytes`, `max_request_bytes`), `POST /app/api/uploads` (multipart `patient`, `upload_key`, `group` `one`|`separate`, `replaces` (D1), `file` parts → document ids; refusals `CHOOSE_PATIENT`, `NO_FILE`, `EMPTY_FILE`, `UNSUPPORTED_FILE`, `UNREADABLE_PDF`, `TOO_MANY_PAGES`, `TOO_MANY_FILES`, `TOO_LARGE`, `VIEW_ONLY`, `UPLOAD_KEY_REUSED`, `FREE_TIER_DOCUMENTS`, `NOT_A_RETAKE`), `GET /app/api/documents/{id}/status`. Components: `Sheet`, `Toast`, `MessageBubble` (progress lines `up.*`). The group control's track and the tray's controls paint `--fill` and draw `--boundary`.
- Produces: `UPLOAD_REASON_COPY: Record<string, CopyKey>` (`upload-reasons.ts`), every upload refusal name → its `upload.err.*` or `app.err.*` key, numbers from the options (`max_files`, `max_pages`); `uploadKey(): string` (`crypto.randomUUID()`), drawn once per press and held in memory across that press's retries; a new press (a changed file set or patient) draws a new one.

**Board** (master W7): the sheet, tray with three pages, the drop zone (desktop), sending, slow, stuck with retry, each refusal, view-only, cap reached — beside `phone.html` scene `chat` with its sheet open and `desktop.html` scene `app` (`#drop`).

- [ ] **Step 1: Write the failing tests.**
  - `UploadApiContractTest`: one case per fixture; `refusals.json` lists every reason name `UploadApiReasonsTest` sweeps, so the web map is derived from it.
  - `UploadApiRealServerTest › aPressCutOffMidBodyThenRetriedWithItsKeyIsOneDocument` (master Review Focus 3, Java side, real port and Testcontainers): send half the bytes of a three-page press and close the socket; send the whole press with the same `upload_key`; exactly one `document` row for that key (queried by column) and the answer's ids equal it.
  - `upload-reasons.test.ts › everyUploadRefusalHasCopy`: every name in `fixtures/api/uploads/refusals.json` maps to a key that resolves in both languages; guard: the list is non-empty.
  - `Upload.test.tsx`: `theSheetNeverGuessesThePatient` (two patients, no preselect → `add.choose`, send disabled; `owner-one` → `add.onlyone` and preselected); `aViewOnlyPatientCannotBeSentFor` (`add.viewonly`); `theTrayKeepsOrderAndCap` (`tray.up`/`tray.down` reorder; the `max_pages`th page disables `tray.more` with `tray.limit`); `groupIsAskedNotGuessed` (two or more pages → `add.group.q`, neither preselected).
  - `e2e/upload.spec.ts`: `aRetryResendsTheSameKeyAndTheSameBytes` (the stub aborts the first `POST /app/api/uploads` with `connectionreset`, answers the second; both requests' `upload_key` parts are equal, and their `file` parts' SHA-256 lists equal, parsed by field); `aNewPressDrawsANewKey`; `eachRefusalIsItsCopy` (parameterised over `refusals.json`); `oneDocumentOpensItsPaperSeveralOpenThePapers`; `dragAndDropAddsPages` (desktop: a `DataTransfer` drop of two files fills the tray); `aRetakeNamesThePaperItReplaces` (`/app/add?patient=<p>&replaces=<d>` sends `replaces=<d>`); `withoutJavaScriptTheSheetSaysSo` (`upload.nojs`, no form); `screenChecks("/app/add", "upload-owner-two")`.
- [ ] **Step 2:** `gl :baymax:test --tests '*UploadApiContractTest' --tests '*UploadApiRealServerTest'` and the web commands. Expected: FAIL — missing fixtures; the real-server test FAILS if the cut-off press left a row (if it passes at once, it is a test written after the code: give it its own replay in Step 5).
- [ ] **Step 3: Implement.** The multipart is built in the browser and posted by `apiSend` straight to Java; Next never sees the body. Progress uses `XMLHttpRequest.upload.onprogress` inside `apiSend`'s rules (`X-Medioo`, the 401 path) through a sibling `apiUpload(form: FormData, onProgress: (sent: number, total: number) => void): Promise<ApiResult<UploadAnswerJson>>` in `src/api/browser.ts`.
- [ ] **Step 4:** Run. Expected: PASS.
- [ ] **Step 5: Replays.** (a) `uploadKey()` called inside the retry; run `aRetryResendsTheSameKeyAndTheSameBytes`. Expected: FAIL naming `upload_key` with both values. (b) Java side: the press's key ignored on a cut-off press in a deliberately broken `UploadService` lookup; run the real-server test. Expected: `expected: 1 but was: 2`. Restore; quote both.
- [ ] **Step 6: Gates** (`gl build` too), **board**, **commit** by name. Message: `feat(medioo-web): upload — the sheet, the page tray, drag and drop, a retry that is the same press (plan W7)`.

---

## Task W8: কাগজপত্র — the wall calendar, DueCard, the month list

Branch `feat/medioo-web-papers`. Lane C. After W2e-b. Owns master Review Focus 1.

**Files:**
- Create: `medioo-web/src/app/app/(signed-in)/timeline/page.tsx`, `src/components/MonthList/{MonthList.tsx,MonthList.css,MonthList.test.tsx}`, `src/components/DueCard/{DueCard.tsx,DueCard.css,DueCard.test.tsx}`
- Modify: `baymax/src/test/java/com/elioo/baymax/web/adapter/in/api/PatientTimelineApiContractTest.java` (cases `calendar/forty-five`, `timeline/forty-five-first-page`, `timeline/lab-only`, `timeline/rx-only`), `src/api/types.ts`, `e2e/support/scenarios.mjs`, `e2e/budget-ceilings.json` (`/app/timeline`), `src/routing/paths.ts`
- Create: `medioo-web/e2e/papers.spec.ts`

**Interfaces:**
- Consumes: `GET /app/api/patients/{id}/timeline?cursor=&type=lab|rx` (`{entries:[{document_id, document_type, date, date_is_upload_day, date_uncertain, month, status, chip, counts, facility, page_one, retake_reason_key, review_pending}], next_cursor}`), `GET /app/api/patients/{id}/calendar?month=YYYY-MM` (`{month, days:[{date, papers[], follow_ups[]}]}`), `GET /app/api/patients/{id}/follow-ups` (`{items:[{document_id, instruction, due_date, overdue, crop}]}`), `GET /app/api/patients/{id}/attention` (the strip, `attn.strip`). Components: `MonthCalendar` (W2e-b, `caption` = the patient's name and the month), `StatusChip`, `VerdictCard`-free (entries are rows).
- Produces: the page `/app/timeline` (`?patient=`, `?type=`, `?cursor=`, `?month=`), every control a plain link so the page reads and pages without JavaScript; `DueCard({ item, lang })`: the doctor's words as written beside their crop, `overdue` as shape + word, the date from Java.

**Board** (master W8): empty, one month, busy month, 45 papers, the filter, follow-ups due and overdue — beside `phone.html` scene `papers`.

- [ ] **Step 1: Write the failing tests.** `DueCard.test.tsx`: `theInstructionIsAsWrittenBesideItsCrop`; `overdueIsShapeAndWord`. `MonthList.test.tsx`: `entriesGroupUnderJavasMonth` (groups by the `month` field, never by parsing `date`); `anUploadDayIsSaidAsSuch` (`date_is_upload_day` → `timeline.uploaddate`); `anUncertainDateIsMarked`. `e2e/papers.spec.ts`: `fortyFivePapersAndTheLongestNameDoNotOverflow` (master Review Focus 1: `calendar/forty-five` and `me-long`, `scrollWidth <= clientWidth` on every element and the calendar grid, at 390 and 1440); `theFilterIsALink` (`?type=lab` shows only lab rows; the request carried `type=lab`); `moreIsALinkThatWorksWithoutJavaScript` (`javaScriptEnabled: false`: `timeline.more` follows `next_cursor`, the next page renders); `theMonthMovesByLink`; `anEmptyPatientGetsTheEmptyState` (`timeline.empty.*`, one add link, owner only); `everyFollowUpCropIsItsOwn` (as W6's crop check); `screenChecks("/app/timeline", "papers-busy", { crops: true, longest: true })`.
- [ ] **Step 2–4:** run (FAIL on missing modules and Next's 404), implement, run (PASS; `gl :baymax:test --tests '*PatientTimelineApiContractTest'` PASS after copying the new fixtures).
- [ ] **Step 5: Replay** `entriesGroupUnderJavasMonth` with grouping by `date.slice(0, 7)` against a fixture entry filed across a Dhaka midnight (D2's case). Expected: FAIL naming the entry and both months. Restore; quote.
- [ ] **Step 6: Gates** (`gl build`), **board**, **commit** by name. Message: `feat(medioo-web): কাগজপত্র — the wall calendar, the doctor's dates, the month list, readable without JavaScript (plan W8)`.

---

## Task W9: স্বাস্থ্য — the lab list, the trend, the medicines as written

Branch `feat/medioo-web-health`. Lane C. After W2e-b. DR-44 item 3, DR-45 item 7.

**Files:**
- Create: `medioo-web/src/app/app/(signed-in)/health/page.tsx`, `src/components/LabList/{LabList.tsx,LabList.css,LabList.test.tsx}`, `src/components/MedicinesAsWritten/{MedicinesAsWritten.tsx,MedicinesAsWritten.css,MedicinesAsWritten.test.tsx}`
- Modify: `src/api/types.ts` (`LabsJson`, `MedicinesJson`), `e2e/support/scenarios.mjs`, `e2e/budget-ceilings.json` (`/app/health`), `src/routing/paths.ts`, `baymax/.../ui-*_{bn,en}.properties` (`health.title` "স্বাস্থ্য" / "Health", `health.labs` "ল্যাবের মান" / "Lab values", `health.one_reading` "একবারই মাপা হয়েছে: {0} {1}, {2}" / "Measured once: {0} {1}, {2}", `health.labs.none` "এখনো কোনো ল্যাবের মান নেই" / "No lab values yet", `health.medicines.none` "এখনো কোনো প্রেসক্রিপশন নেই" / "No prescription yet", `health.medicines.newer_unread` "নতুন একটা প্রেসক্রিপশন এখনো পড়া হয়নি" / "A newer prescription hasn't been read yet")
- Create: `medioo-web/e2e/health.spec.ts`

**Interfaces:**
- Consumes: `GET /app/api/patients/{id}/labs` (`{series:[{canonical_name, name, unit, points[]}], bp:{points[]}}`), `GET /app/api/patients/{id}/medicines` (`{document_id, date, date_is_upload_day, date_uncertain, heading_key, medicines[], newer_prescription_not_read, newer_document_id}`). Components: `TrendChart` (W2e-b), `StatusChip`, the crop component from W6.
- Produces: the page `/app/health`; `LabList({ labs, lang })`: a series of one point is the `health.one_reading` sentence, never a chart; `MedicinesAsWritten({ medicines, lang })`: the heading from `heading_key` with the date, each medicine's written fields beside its crop.

**Board:** labs some, labs none, blood pressure, one reading, medicines latest, latest with none, a newer one unread, no prescription — beside `phone.html` scene `health`.

- [ ] **Step 1: Write the failing tests.** `LabList.test.tsx`: `oneReadingIsASentenceNotAChart` (no `svg` chart for a one-point series); `aSeriesIsJavasNotJoinedHere` (two series sharing a name with different units stay two, as Java sent them); `bloodPressureIsJavasPairs`. `MedicinesAsWritten.test.tsx`: `theHeadingIsJavasKeyWithTheDate` (exactly `t(lang, heading_key)` and the date; no rendered text matches `/current|বর্তমান|চলমান/i`, matched per element); `anEmptyLatestPrescriptionIsItsDateAndNothingElse` (`medicines-empty-latest`: the date, no medicine rows, no older paper's medicines); `aNewerUnreadPrescriptionIsSaidAndLinked` (`health.medicines.newer_unread` linking `newer_document_id`); `onlyTheWrittenFieldsShow`. `e2e/health.spec.ts`: `everyMedicineCropIsItsOwn`; `theHealthTabAppearsInPreviewOnly` (not preview → no স্বাস্থ্য tab while `FLIPPED` lacks it; preview → tab present); `screenChecks("/app/health", "health-some", { crops: true, longest: true })`.
- [ ] **Step 2–4:** run (FAIL), implement, run (PASS).
- [ ] **Step 5: Replay** `anEmptyLatestPrescriptionIsItsDateAndNothingElse` against a component that falls back to a prior list kept in state. Expected: FAIL naming the medicine row. Restore; quote.
- [ ] **Step 6: Gates**, **board**, **commit** by name. Message: `feat(medioo-web): স্বাস্থ্য — lab values with their trend, medicines as written on the latest prescription (plan W9)`. PR body: the `health.*` drafts.

---

## Task W10-api: The theme preference — `PUT /app/api/preferences`

Branch `feat/baymax-web-api-preferences`. Lane B.

**Files:**
- Create: `baymax/src/main/java/com/elioo/baymax/web/adapter/in/api/PreferencesApiHandler.java`
- Modify: `AppApiRouter.java`, `AppApiLogSafetyTest.java`
- Test: `baymax/src/test/java/com/elioo/baymax/web/adapter/in/api/PreferencesApiTest.java`
- Generated: `docs/infra/app-routes.txt`

**Interfaces:**
- Produces: `PUT /app/api/preferences` with `{"theme": "auto"|"light"|"dark"}` → 204 + `Set-Cookie: baymax_theme=<value>; Path=/app; Max-Age=31536000; SameSite=Lax; HttpOnly`, `Secure` when `properties.getAuth().isCookieSecure()`. Any other value or field shape → 400 `INVALID`. Session and CSRF as every mutation. Nothing stored server-side.

- [ ] **Step 1: Write the failing tests** in `PreferencesApiTest`: `eachThemeSetsItsCookieUnderApp` (parameterised; the cookie's `Path` is `/app`, never `/`); `anUnknownThemeIsInvalidAndSetsNoCookie`; `withoutXMedioItIsRefused`; `withoutASessionItIs401`.
- [ ] **Step 2:** `gl :baymax:test --tests '*PreferencesApiTest'`. Expected: FAIL — the catch-all 404. **Step 3:** implement. **Step 4:** run; route and log-safety tests; the snapshot. Expected: PASS. **Step 5: Replay** `eachThemeSetsItsCookieUnderApp` with `Path=/`. Expected: FAIL naming `Path`. **Step 6:** `gl build`; **commit** by name. Message: `feat(baymax-web): the theme preference as a cookie under /app (plan W10-api)`.

---

## Task W10: পরিবার and settings — owner and member, flags, reminders, theme, language, sign-out

Branch `feat/medioo-web-family`. Lane C. S5, S6 and S7 apply.

**Files:**
- Create: `medioo-web/src/app/app/(signed-in)/family/page.tsx`, `src/app/app/(signed-in)/patients/[id]/profile/page.tsx` (the patient's section on its own, the link already sent in WhatsApp profile prompts), `src/components/Family/{FamilyAccount.tsx,PatientCard.tsx,ChronicFlags.tsx,ReminderSwitch.tsx,ThemeSwitch.tsx,LanguageSwitch.tsx,Family.css,Family.test.tsx}`
- Modify: `src/app/layout.tsx` (reads `baymax_theme` and sets `data-theme` on `<html>` when `light` or `dark`), `e2e/tokens-cascade.spec.ts` (the cookie path), `src/api/types.ts` (`FamilyJson`), `e2e/support/scenarios.mjs`, `e2e/budget-ceilings.json` (two pages), `src/routing/paths.ts`, `baymax/.../ui-*_{bn,en}.properties` (`theme.title` "চেহারা" / "Appearance", `theme.auto` "ফোনের মতো" / "Like the phone", `theme.light` "আলো" / "Light", `theme.dark` "অন্ধকার" / "Dark")
- Create: `medioo-web/e2e/family.spec.ts`

**Interfaces:**
- Consumes: `GET /app/api/family` (`{account:{owner_name, number, language, reminders_stopped, terms_date}, flags_offered:[{id, label_key}], patients:[{id, name, name_possessive, age, sex_key, owner, chronic_flags[], consent_date, reminders}]}`), `PUT /app/api/patients/{id}/flags` (refusals `VIEW_ONLY`, `INVALID`), `PUT /app/api/patients/{id}/reminders` and `PUT /app/api/family/reminders` (`{"on": false}` only; `{"on": true}` is 400 until E2), `PUT /app/api/language`, `PUT /app/api/preferences` (W10-api), `POST /app/api/sign-out` (W3-api). Components: `Toast` (`profile.saved`), `Sheet`, `StatusChip`-free. Every check box, the theme segmented track and the language control paint `--fill` and draw `--boundary`.
- Produces: the pages `/app/family` and `/app/patients/{id}/profile`.

**Board** (master W10): owner, share member, reminders off, a typed flag, theme light and dark — beside `phone.html` scenes `family` and `theme`.

- [ ] **Step 1: Write the failing tests.** `Family.test.tsx`: `theNumberIsShownOnlyHere` (`account.number` rendered once, in `FamilyAccount`); `aShareMemberSeesFlagsReadOnly` (`owner: false` → no inputs, the flags as text, `retake.viewonly`-style note from `patients.shared`); `aTypedFlagIsKeptAndShownAsTyped` (`label_key` null → its own text; saving sends it back unchanged, Q10 default); `remindersCanOnlyBeStopped` (the switch sends `{"on": false}`; once stopped it says so and offers no "on"); `theFamilySwitchIsHiddenForAMemberWhoOwnsNoone` (S5 default); `aViewOnlyRefusalIsCopy` (403 `VIEW_ONLY` → `app.err.forbidden`, no crash); `theThemePutsAndRepaints` (one `PUT /app/api/preferences`, then `data-theme` on `<html>` changes without a reload); `languageReloadsInTheNewLanguage`; `signOutIsHere`. `e2e/family.spec.ts`: `theThemeCookieIsReadOnTheServer` (with `baymax_theme=dark` the first HTML response's `<html>` carries `data-theme="dark"`; unset → no attribute); `theProfileLinkFromWhatsAppStillOpens` (`/app/patients/<id>/profile` renders that patient's card); `noDeleteFamilyControlYet` (S7: no element names a family delete); `screenChecks("/app/family", "family-owner", { longest: true })` and for `family-share-member`.
- [ ] **Step 2–4:** run (FAIL), implement, run (PASS; `tokens-cascade.spec.ts` PASS with the cookie).
- [ ] **Step 5: Replays.** (a) `ReminderSwitch` offering "on" when stopped; run `remindersCanOnlyBeStopped`. Expected: FAIL naming the control. (b) The layout ignoring the cookie; run `theThemeCookieIsReadOnTheServer`. Expected: FAIL naming `data-theme`. Restore; quote both.
- [ ] **Step 6: Gates**, **board**, **commit** by name. Message: `feat(medioo-web): পরিবার and settings — flags, a reminder stop, theme and language, owner and member (plan W10)`. PR body: the `theme.*` drafts; S5, S6, S7.

---

## Self-review notes (writing-plans)

**Spec coverage.** Master Part D Wave 3: W3 (sign-in, DR-42 fallback, the no-JS expired-session case) → W3-api, W3, F3; W4 (tabs, columns, switcher, chooser, 401 → `next=`) → W4; W5 (thread, banner with দেখেছি, honest composer) → W5-api, W5, F5; W6 (crops, zoom, gate states, NOW abroad, retake, failed, multi-page, delete, Review Focus 2 and 5, one view per click) → W6-api, W6, F6; W7 (sheet, tray, drag and drop, same-key retry, Review Focus 3; `POST /app/add` kept) → W7, F7; W8 (wall calendar, DueCard, month list, Review Focus 1) → W8, F8; W9 → W9, F9; W10 (theme, language, owner vs member; sounds and delete-family held as S6, S7) → W10-api, W10, F10. `/_next/static/*` and `/fonts/*` with the first HTML route → F0. Master Review Focus 4 (expired session) → W3-api, W3, W4. Part B decisions 3 (W5 `theNowLineIsShippedVerbatimAndTheNameIsTheTitles`), 7 (W2a tokens, the NOW checks in `screenChecks` and `test:built`), 8 (B3's rule, sent back as received: W5), 9 (W9). DR-44's স্বাস্থ্য tab → W4's `reachable` and W9.

**Not covered here, by design:** W-del (the Java pages and the ledger; one PR per flipped route, after these), W11–W13, D3, D5, the E1/E2 screens beyond S7, chat answers (D11).

**Consistency.** `javaGet`, `signedIn`, `requestContext`, `apiSend`, `apiGet`, `apiUpload`, `signInPath`, `previewPath`, `href`, `reachable`, `NEXT_PAGES`, `JAVA_PAGES`, `FLIPPED`, `paperRead`, `activePatient`, `useScenario`, `screenChecks`, `BOARD_SCENES` are defined once (Shared interfaces, W4, W6) and used under those names. Java additions: `GET /app/api/sign-in/state`, `POST /app/api/sign-out`, `/app/basic*`, `GET /app/api/patients/{id}/thread`, `ResultUseCase.card`, `GET /app/api/documents/{id}/values/{obs}`, `PUT /app/api/preferences`. No migration is added.
