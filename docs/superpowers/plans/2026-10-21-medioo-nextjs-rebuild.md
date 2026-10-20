# Medioo Web Rebuild (Next.js) — Master Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Execution in this repo runs as Workflow pipelines (ultracode); see Part E.

**Goal:** Replace Medioo's server-rendered Java web UI with a Next.js/React frontend that implements the approved phone and desktop designs, without moving any safety rule out of Java and without missing the Graph v21 deadline.

**Architecture:** A separate `medioo-web` Next.js standalone container sits beside the Java app on the box. Caddy routes per path, and Java stays the default until each screen is flipped. Browser JSON goes to a new Java namespace, `/app/api/**`, which inherits today's `Path=/app` session cookie. Java returns a display model (urgency, display kind, ordered NOW reasons, released wording only) that React renders but never derives. Screens move one route at a time (strangler). The Java page for a route is deleted only after its rules are re-asserted on the API and in the browser.

**Tech Stack:**
- **Frontend:** Next.js 15 (App Router, `output: "standalone"`), React 19, TypeScript (strict), pnpm, Node 22. Tests: Vitest + Testing Library, Playwright + `@axe-core/playwright`.
- **Backend:** Spring Boot 3.4 / WebFlux / R2DBC; Flyway for migrations.
- **Infra:** Docker, GHCR, Caddy, GitHub Actions.

**Spec:**
- [`docs/design/2026-10-21-approved/`](../../design/2026-10-21-approved/README.md): phone and desktop prototypes, the direction contract with the 2026-10-21 amendment, Mio and the wordmark.
- [`docs/superpowers/specs/2026-10-17-medioo-companion-experience-design.md`](../specs/2026-10-17-medioo-companion-experience-design.md): product principles.
- `docs/DECISIONS.md`: DR-12, DR-13, DR-22, DR-36 to DR-39.
- The research dossier behind this plan: six repository maps, an architecture draft and an adversarial critique (planning workflow, 2026-10-21). Its findings are folded into Parts A–D.

## Global Constraints

**Copy, safety and display**
- Every safety rule stays in Java and reaches React as a field. React renders `display_kind`, `urgency`, `reasons[]`, `wording` (present only when the gate is `RELEASED`), `viewer.bangladesh` and NameGrammar strings. It never derives them.
- Red is reserved for NOW urgency, CRITICAL values and the emergency call. Amber is reserved for THIS_WEEK. Destructive actions are not red.
- A value whose printed range is one-sided, or whose stored INSIDE the code cannot prove, renders as UNKNOWN with "কাগজে মিলিয়ে দেখুন". It is never "সীমার মধ্যে".
- Medicines are shown verbatim and crop-first. Nothing is derived: no "current", no purpose, no dose advice.
- "আমি ডাক্তার নই" appears on every page that shows a result. The NOW page carries `now.notdoctor` itself, not only the footer.
- The NOW banner is global. "দেখেছি" (PO-approved 2026-10-21) hides the banner only. Stored urgency, the paper's NOW block and WhatsApp delivery are unchanged. The patient keeps a red mark.
  - The acknowledgement is keyed to the exact set of NOW documents the viewer saw at the tap: `(document_id, urgency_assessed_at, release state)`. The banner shows again whenever a visible NOW document is not in that set: a new upload, a `routine`/`this_week` → `now` raise or NULL first assessment on an existing paper (V18), an A11 sweep assessment, or a later gate release (DR-41 item 5).
  - It is per account holder (member id), stored on the server so it holds across that person's devices, never per family: one member's tap must not hide the banner from another member who never saw it (Part B decision 8).
- The prototypes win on look and layout only. Their NOW line with the name (Part B decision 3), unconditional `tel:999` (A5), canned answers, scene bar, receipts and toasts do not ship (DR-41).
- All copy comes from `ui-*_{bn,en}.properties` and `messages_{bn,en}.properties`. No Bangla literal (U+0980–U+09FF) and no English UI prose may appear in `medioo-web/src` outside generated copy. New keys go through the copy owner and the PO.

**Accessibility and design**
- Legibility floor:
  - axe reports zero violations on every routed page, both themes.
  - Every status is colour + shape + word.
  - No text under 12 px.
  - Secondary text is at least 4.5:1 in both themes.
  - Touch targets are at least 44 px; primary actions at least 48 px.
- Brand:
  - Mio is used as drawn, from `mio.webp` and `mio-head.webp`, and moves only as a whole figure.
  - Mio never animates on a NOW result.
  - Any looping Mio motion plays at most twice.
  - The wordmark comes from `medioo-light.svg` and `medioo-dark.svg`, chosen by theme.
- Layout: below 900 px use the phone layout (tabs চ্যাট · কাগজপত্র · স্বাস্থ্য · পরিবার). From 900 to 1199 px the sidebar hides behind a toolbar button. At 1200 px and wider use three columns: sidebar 256, paper, conversation 400.

**Web platform**
- CSP on Next pages:
  ```
  default-src 'none'; script-src 'self' 'nonce-<per-request>'; style-src 'self'; img-src 'self' https: http: blob:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'; form-action 'self'; base-uri 'none'
  ```
  - `img-src` is copied verbatim from `BaymaxSecurityHeadersFilter.CSP`: crops 302 to S3 and the upload tray uses `blob:`. Copying it means the two policies cannot drift.
  - No `style={}` in TSX and no `next/image`.
  - Fonts are the existing self-hosted Noto subsets. No Google Fonts, no Caveat or Galada.
  - Next also sets `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff` and `Referrer-Policy: strict-origin-when-cross-origin`. Caddy's header block is deferred (it deletes `Server`) and overwrites `nosniff` and `Referrer-Policy` on every path, so Next uses Caddy's and Java's value; a different one would be tested locally and never reach a browser. `scripts/smoke-https.sh` asserts the production headers per path class.
- Session cookie: `baymax_session` stays `Path=/app`. It is never widened (MedScribe's same-origin PoC script must not see it).
- CSRF: every mutating `/app/api` route requires all three of:
  - `Content-Type: application/json` or `multipart/form-data`;
  - the header `X-Medioo: 1`;
  - an `Origin` header that is present and equal to `baymax.web.public-origin`; a missing `Origin` is 403 `FORBIDDEN_ORIGIN` (browsers always send it on a fetch `POST`/`PUT`/`PATCH`/`DELETE`).
  - There is no CORS configuration, ever.
- Next talks to Java server-side with `GET` and `HEAD` only. No server actions (`'use server'`), no mutating route handlers: every mutation goes browser → Caddy → Java (DR-40 A3). A `medioo-web` CI check enforces it.
- Uploads, page and crop images, and every POST go to Java directly through Caddy. Next never proxies a request body.
- Language: `bn` by default. `Accept-Language` never changes the language; only the `baymax_lang` cookie does.

**Links, data and logging**
- URLs already sent over WhatsApp keep working forever: `/app`, `/app/documents/{id}`, `/app/documents/{id}/all`, `/app/documents/{id}/values/{obs}`, `/app/patients/{id}`, `/app/nudges/opt-out?p=&t=`, and the `/baymax/**` 301s.
- Migrations are numbered at merge time, as the next free `V<n>` on `origin/main` after rebase. Never pre-assign numbers. V22 and V23 belong to Phase 0 PRs 8 and 9.
- Logs never carry patient text, phone numbers, OTP codes or wamids above DEBUG. This holds in Java, in Next server logs and in browser `console` output.
- No real patient data in the repo. Fixtures, Playwright baselines and screenshot boards are fictional. Production-preview screenshots are private artifacts only, never git.

## Review Focus

1. **A family with a real-length name and many papers.** Long Bangla names (for example "মোছাম্মৎ রোকেয়া বেগম চৌধুরী"), 40+ papers and multi-page documents must lay out without clipping, and the calendar month must stay readable. Owned by W4/W8: a Playwright test with the longest-name fixture and a 45-paper fixture asserts that no element overflows its box (`scrollWidth <= clientWidth`) on the routed page.
2. **A crop that 404s or is expired.** A crop past the 15-minute presigned window, or a missing key, must show a "ছবিটা আনা যাচ্ছে না — আবার চেষ্টা করুন" state with a retry. It must never be a blank box or the wrong crop. Owned by W6: a test serves an expired-URL fixture and asserts the retry state, and that every rendered value's `<img>` has `naturalWidth > 0` or the retry state.
3. **Network dies mid-upload.** Retry must resend the same `upload_key` so the press stays one document (V21). Owned by W7: a test aborts the request after 50% of the bytes, then retries, and asserts that one document row exists, through Testcontainers on the Java side.
4. **A session that expires while a page is open.** The next `/app/api` call answers 401 with `{reason:"UNAUTHENTICATED"}`. The client must go to sign-in and keep the place: `next=` holds the current path, guarded by Java's `LocalPath`. Owned by B1 (Java) and W4 (client), with a test for each side.
5. **Two family members acting at once.** One deletes a paper while the other has it open. The second gets `{reason:"NOT_FOUND"}` and a "এই কাগজটা আর নেই" state, not a crash or stale data. Owned by W6.

---

## Part A — Architecture decisions (locked by this plan; recorded as DR-40/DR-41 in X1)

**A1. Topology.** `medioo-web` is a Next standalone container (`node:22-alpine`).
- **Network:** it joins `n8n_edge` with no published port, `--memory=320m`. The template is `bddrug-web`, already running on the box at about 75 MiB.
- **Image:** it is built and tagged with the same git sha as the Java image by the same deploy job.
- **Caddy routes per path, and Java is the default.** A path goes to `medioo-web:3000` only once it is listed in the versioned site block `docs/infra/Caddyfile.medioo` and applied on the box. There is no Next fallback proxy: that would buffer 60 MB uploads and add a second hop.
- **Java off the public port (W1c).** Java binds `127.0.0.1:8086` and the security group closes 8086. Until W1c lands this is a known gap: `:8086` is reachable over plain HTTP from outside, bypassing Caddy.
- **Flipping a route back** is a one-line Caddy edit plus a reload.

**A2. Deploy order and rollback.** `deploy.sh` swaps Java first and waits for its health, then swaps Node.
- **Missing web image:** if a `medioo-web:<sha>` image does not exist (any sha before W1b), the web step logs and is skipped, and the deploy stays green.
- **One `IMAGE_TAG` rolls back both, down to the routes floor.** Caddy routing is box state, not tied to `IMAGE_TAG`. Rolling back to a sha older than the most recent route flip first reverts that route on the box (backup, in-place write, validate, reload), then sets `IMAGE_TAG`, then runs `scripts/smoke-https.sh`. From W1c, each flip records the routes-floor sha and `deploy.sh` refuses an `IMAGE_TAG` that is not a descendant of it unless `FORCE=1`.
- **API compatibility:** API changes are additive only, so old Java plus new UI and new Java plus old UI never break.

**A3. Auth across the boundary.**
- **The browser** calls `/app/api/*`. Caddy sends these to Java, and the `Path=/app` cookie travels on its own.
- **Server components** call `http://medscribe-ai:8086/app/api/*` with `GET` or `HEAD` only and forward the incoming `Cookie` header, nothing else. Next never mints or reads a session, and has no server actions or mutating route handlers: a fetch from Node sends no `Origin`, so it would pass an Origin check by construction.
- **Cookies are set only in the browser.** Server components cannot set cookies, so the active patient and the theme or sound preferences are set by browser calls (for example `PUT /app/api/active-patient`), and Java answers with `Set-Cookie`.
- **Preview:** Java decides whether a family sees Next twins before a route flips, through the `BAYMAX_WEB_PREVIEW_FAMILIES` allowlist exposed as `me.preview`. It is never a cookie anyone can set.

**A4. No-JS scope.** Reading pages are server-rendered and readable with JavaScript off:
- the timeline;
- a paper;
- a NOW result;
- the opt-out confirmation.

Every mutation needs JavaScript: sign-in, upload, delete, settings and দেখেছি. This changes spec principle 14 and needs the PO to accept it as part of DR-42 (Part B, decision 1). Until the PO accepts it, sign-in stays on the Java pages: W3 is held, and Caddy keeps `/app/`, `/app/login` and `/app/verify` on Java.

**A5. Display model.** Java owns `DisplayKind`. `ValueRows.kindOf` moves from `web/adapter/in/ui` into the application layer, and the following move with it:
- the order of NOW reasons;
- the counts line (never "০টি বাইরে");
- the gate state;
- wording only when `RELEASED`;
- `viewer.bangladesh` (`tel:999` only for +880 viewers);
- the retake ordinal and the owner flag;
- NameGrammar possessive and objective forms.

The JSON never contains `confidence`, `model` or developer `status_reason` prose.

**A6. Test migration.** No Java UI test is deleted until its rule exists elsewhere. Each rule moves to two places:
- a Java contract test on `/app/api`;
- a Playwright assertion on the routed page, by role or by field.

The ledger is generated, not hand-written. `scripts/ui-test-ledger.sh` lists every `@Test` and `@ParameterizedTest` under `baymax/src/test/java/**/web/adapter/in/ui` at the sha before deletion, and CI fails if any of them lacks a `// successor: <TestClass#method>` that resolves to an existing test, or if the list is empty. Java pages are deleted per route as each one flips (W-del PRs), never in one PR.

**A7. Visual gate is a review, not a correctness check.**
- **The review:** every W PR publishes a private screenshot board (phone 390×844 and desktop 1280×800, light and dark) beside the matching prototype scene. The PO looks before merge.
- **Correctness comes from content checks:**
  - axe;
  - the CSP-violation report hook;
  - overflow checks with the longest real-length strings;
  - every value and medicine `<img>` resolves to that row's own crop, with `naturalWidth > 0`;
  - the network-log budget.
- **The on-device step:** before a route flips, Tanvir tries it on his phone with preview on.

**A8. Regression guards are replayed on both sides.**
- Java contract guards are replayed against a deliberately broken build.
- React safety tests are replayed against a mutated component; `scripts/replay-web-guard.sh <test> <mutation>` runs one replay.
- Either way, the failure message must name the defect.
- MSW fixtures are the exact files the Java contract tests write in the same CI run. A byte comparison fails on drift.

---

## Part B — Decisions the PO must make (each blocks only the PR named)

| # | Decision | Recommendation | Blocks |
|---|---|---|---|
| 1 | **Budget and no-JS (DR-42).** "JS ≤ 40 KB, works without JS" cannot hold for React. The fonts alone are 98 KB on the wire. | W1a measures a real Next page over the wire. Then: SSR and no-JS reading for timeline, paper and NOW; mutations need JS; first-load wire budget = the measured hello page + 60 KB, per route. | W3 (sign-in); every W budget test |
| 2 | **Voice privacy (DR-43).** Chrome's Web Speech sends audio to Google. | Typed commands ship first (W11a). Voice uses server speech-to-text through our GCP bn-BD key, with no logging and an explicit first-use consent, in W11b. | W11b |
| 3 | **NOW line.** The prototype adds the name ("এখনই আম্মাকে…"); the shipped `urgency.now` has no name and is read verbatim. | Keep the shipped line. Show the name in the band's title, not inside the safety sentence. | W5, W6 |
| 4 | **Old stale THIS_WEEK rows.** V18 forbids lowering urgency; rows already raised by past follow-ups exist. | Fix the cause going forward (D3) and leave existing rows raised, with a one-time "আগের কাগজ" tag. | D3 |
| 5 | **The same paper sent twice** (image hash). | Warn ("এই কাগজটা আগেও পাঠিয়েছেন") and let the family choose. Never merge silently. | D5 |
| 6 | **Landing page.** | Keep it in Java until W-del; port last (W13). | W13 |
| 7 | **The one NOW red.** | `#d12c24` light and `#d93229` dark (both meet ≥4.5:1 with white). | W2a |
| 8 | **দেখেছি scope.** | Per account holder (member id), stored on the server so it holds across that person's devices. Not per family: one member's tap would hide the NOW banner from every other member, including ones who never saw it. If per family is chosen, record that trade-off in DR-41 before B3 merges. | B3 |
| 9 | **"Current medicines".** | Show "প্রেসক্রিপশনে যেমন লেখা — সর্বশেষ, তারিখসহ" only. No active or stopped state. | B6, W9 |
| 10 | **New scope (DR-44):** voice, sounds, the স্বাস্থ্য tab, keepmd-parity later. | Record as approved MVP scope. Each keepmd feature gets its own BMX ticket later. | W9, W11, W12 |

---

## Part C — Integration with prior work

| Item | Verdict | Sequencing |
|---|---|---|
| **Phase 0 PR 10 (C3).** Graph v25. v21.0 expires **2027-01-21**. | Parallel. Hard date: **merge by 2026-11-15**. | Lane A starts now, not frozen by the rebuild. Add to its checklist: set the Meta app dashboard webhook version to v25, and after deploy confirm one real inbound message in the logs. |
| Phase 0 PR 8 (C1, V22 inbound idempotency) | Parallel | Rewrite its task snippets against current main, then merge. It takes V22. |
| Phase 0 PR 9 (B2, V23: every document gets a reply) | Parallel | After PR 8 (V23). W6's FAILED state must show what WhatsApp is told. |
| Phase 0 PR 7 (B1b, greetings restate urgency) | Parallel | Any time. It reads `DocumentUrgencyUseCase.current`. |
| PR 12 sign-in (DR-39 free mix) | UI superseded by W3; backend kept | `WebAuthService`, `CompositeOtpDelivery`, V17 and the "sent only when Graph accepted" rule are re-exposed through B4a. |
| Web-portal A1–D4 (shipped) | Read models kept; Java pages retired per route | A10/A11/B1/B4 (F1/F2/F5 urgency foundations), ResultService, AttentionService, TimelineService, UploadService and `upload_key` all stay. |
| D5 (downscale eval, paid, about $2) | Parallel | Needs Tanvir's yes. The edge it picks lands in W7, not `upload.js`. |
| E1 and E2 (proposals) | Absorbed into B5 + W10 if the PO says yes | Otherwise they stay out. |
| D11 (clinician sign-off on chat answers) | Parallel, blocked | W5's composer stays honest: asking produces a "এখনো উত্তর দিতে পারি না" bubble. The answer API is its own plan after D11. |
| DR-23 token rotation, 1b.9 Meta templates, DR-25 purge, testset PRs #51/#53 | Independent | Templates treat `/app/documents/{{1}}` as a fixed URL contract. |
| **Box disk at 99% (232 MB free)** | **Urgent, X0** | It can fail the next deploy of anything. |

---

## Part D — PR sequence

Each line is one PR. The **Waves** order work; within a wave, lanes run in parallel (Part E). "Proof" names the tests that must exist and pass. A W PR also names the routes it flips and the screens on its board.

**Wave 0 — now (all parallel)**
- **X0** Disk hygiene and an https smoke test. *Detailed below.*
- **X1** Decisions and design into the repo: DR-40/41, the approved design folder, the versioned Caddy site block. *Detailed below.*
- **P10** Phase 0 PR 10, as written in `2026-10-17-medioo-phase0-1a.md` §PR 10, plus the Meta dashboard and real-inbound steps above.

**Wave 1 — foundations**
- **W1a** `medioo-web` skeleton: nonce CSP, `/app/healthz`, the budget probe, CI steps. Not deployed. Produces the DR-42 measurement. *Detailed below.*
- **W1b** Second container deployed with no traffic: Java-first order, the missing-image skip, pruning both repos. *Detailed below.*
- **W1c** Caddy per-path routing (`/app/healthz` only), the https upstream smoke table, Java bound to 127.0.0.1. *Detailed below.*
- **B1** The `/app/api` router: CSRF guard, `{reason}` codes, `/me`, active patient, the preview allowlist, and a route-contract snapshot derived from the Java routers. *Detailed below.*
- P8, then P9, then P7 (lane A, after P10 or alongside it).

**Wave 2 — vocabulary and contracts**
- **W2a** The token file `medioo.tokens.json` → `tokens.css`, reconciling phone and desktop and absorbing the loose hexes. Fonts. A stylelint ban on colour literals. A built-CSS test that `--now*` is used only by now, crit and emergency components. Proof: contrast pairs computed from the token file in both themes.
- **W2b** Copy codegen (`.properties` → typed JSON), the Bangla-literal lint (it fails if it scans zero files), and Bangla digit and date golden vectors written by a Java test and read by Vitest.
- **W2c–W2e** Components, in groups of 3–4, each with catalog states at `/app/catalog-test` (build flag; the smoke test asserts it returns 404 in production):
  - c: Wordmark, MioAvatar, MessageBubble (`surface` prop), StatusChip (in, out-high, out-low, crit-high, crit-low, unk);
  - d: VerdictCard, NowBanner, Toast, Sheet;
  - e: OtpInput, PhoneField, MonthCalendar (with legend and screen-reader day status), TrendChart.
- **B2** Paper view-model `GET /app/api/documents/{id}`. Proof:
  - contract rows for F3 (RELEASED only), no 999 abroad, ROUTINE never chipped, PENDING body never shown, sideless OUTSIDE shown as UNKNOWN, one-sided INSIDE shown as UNKNOWN, medicine rows carrying only verbatim fields plus a crop;
  - `now.notdoctor` present;
  - no `confidence` or `model`;
  - each row replayed red against a broken build.
- **B4a** Sign-in JSON: `request` returns `{sent: boolean, wa_link?}` (sent only when Graph accepted), plus typed verify refusals. Proof: a log-capture test with a fictional number and code asserting both are absent, by field.
- **B4b** Upload JSON (multipart, `upload_key`, grouping, 60 MB cap, document ids, refusal codes) and upload options. Proof: a replay-key test on Postgres; a 59 MB multipart test through a real server.
- **B4c** Status (no audit write), delete paper (owner only, confirm token). Proof: Testcontainers delete of a document with `now_ack` and crop rows.
- **D4** Images root cause: a real-browser network check of the 302 to S3 first, then stored width and height (migration) and a stable same-origin image route. Proof: a content check that a decoded real crop is non-empty and its size matches the stored size.

**Wave 3 — screens (one PR per screen; each flips its routes after the on-device step)**
- **W3** Sign-in (held until decision 1). Flips `/app/`, `/app/login`, `/app/verify`. Board: welcome, number, error, code sent, code not sent (wa.me), wrong code, done.
- **W4** Shell: tabs and three columns, the patient switcher, never guessing the patient (the chooser), the 401 → sign-in `next=` flow. Board: layouts at 390/1024/1440 × 0/1/2 patients.
- **B3** Attention stack, timeline by month, follow-ups, NOW ack (migration with `family_id` and `patient_id` NOT NULL, FK, in the delete order). Proof:
  - two-direction ack tests, written first: it hides the banner for exactly the documents acked; it never changes stored urgency, the NOW block or WhatsApp; a new NOW upload, a `this_week` → `now` raise, a sweep assessment and a later gate release on an existing paper after the ack each show the banner again; after member A acks, member B in the same family still gets the banner;
  - Testcontainers deletion with a real ack row.
- **W5** Chat thread (read-only: documents, outbound messages, nudges), the NOW banner with দেখেছি, the honest composer. Flips `/app/home`. Board: default, NOW, retake, first run.
- **W6** Paper detail: the stored page image with DR-12 crops, zoom, every gate state, NOW and NOW-abroad, retake, failed, multi-page, delete; Review Focus 2 and 5. Flips `/app/documents/{id}`, `/all`, `/values/{obs}`. Proof:
  - each value's `<img>` src resolves to that row's own `crop_key`;
  - audit DOCUMENT_VIEW rows equal the number of clicks (derived from `audit_event`; links use `prefetch={false}`).
- **W7** Upload: sheet, page tray, drag and drop, retry with the same key, reading progress, every refusal as copy; Review Focus 3. Java keeps `POST /app/add` until W-del.
- **D1** Chat cards dated by the paper's date, and retake linked to the paper it replaces (migration). Proof, both directions written first:
  - a stale duplicate disappears;
  - a NOW paper uploaded today with an old printed date still shows;
  - the guard is replayed against the pre-fix query.
- **D2** Retakes filed under the upload day; uncertain dates stored (migration). Proof: a month-grouping test across a Dhaka midnight.
- **W8** কাগজপত্র wall calendar, DueCard, month list; Review Focus 1. Flips `/app/timeline`.
- **B6** Lab series by `(patient_id, canonical_name, folded unit)`, with blood-pressure pairing via the shared `crop_key`, and medicines as written on the latest prescription (decision 9). Proof:
  - a join test in the dangerous direction (two different rows sharing a key are not merged);
  - the MICRO SIGN fold;
  - one-sided points are never INSIDE.
- **W9** স্বাস্থ্য hub, the lab list, the trend chart (one reading gives a sentence, not a chart), medicines.
- **B5** Family JSON: flags, consent, reminders, opt-out, language; E1/E2 if approved. Proof: a deletion test derived from the schema; opt-back-in.
- **W10** পরিবার and settings: theme, sounds, language, owner vs member, delete-family confirm. Flips `/app/family`.

**Wave 4 — voice, motion, cut-over**
- **W11a** Typed command palette (⌥/Alt tap, ⌘K) with the patient lexicon built from `/me` names and NameGrammar forms. Navigation-only intents; "ask" intents answer "এখনো উত্তর দিতে পারি না" until D11. Proof: grammar tests with names not in the prototype.
- **W11b** Voice: hold Alt, hold the mic, GCP bn-BD speech-to-text (after DR-43). Proof: no transcript in any log, in either runtime.
- **W12** The sound bank (synthesized, PO's pick, default off until the first tap unlocks WebAudio), Mio states, the sheet spring. Proof: with reduced motion there are no animations and the computed `animation-name` is `none`.
- **W13** Landing page and opt-out page port (decision 6). Proof: an opt-out link from a real nudge fixture works end to end.
- **W-del-n** One PR per flipped route: delete its Java pages and tests, with ledger successors resolving, and keep image signing, `LocalPath` and `Lang`, and the classes `/app/api` shares from `web.adapter.in` (`ActivePatient`, `PatientJson`; moved out of `ui` in B1 for this reason). The last one removes the HTML test harness and updates `MEDIOO.md` and the RUNBOOK.
- **D3** Stop past follow-ups raising prescriptions to THIS_WEEK (after decision 4 and a clinical ruling). Proof: both directions first; an urgency replay script, read-only, with its own two-direction guard, run as an explicit Supabase step reported in the build report.
- **D5** Duplicate paper warning (after decision 5). Proof: both directions (the same bytes warn; a re-photographed different paper with the same date does not).

---

## Part E — Execution with Workflow / ultracode

**Lanes (parallel worktrees; merges serialised because every merge deploys):**
- **A (WhatsApp):** P10 → P8 → P9, with P7 alongside.
- **B (API):** B1 → {B2, B4a, B4b, B4c} → {B3, B5, B6}.
- **C (web):** W1a → W1b → W1c → W2a → {W2b, W2c} → {W2d, W2e} → W3…W10, each against MSW fixtures, rebased once its B PR merges.
- **D (data):** D4 now; D1 and D2 after P9; D3 and D5 after their decisions.
- **X:** X0 and X1 immediately.

**Per-PR pipeline (one Workflow per PR, or per wave with a pipeline over PRs):**
1. **Plan.** For Wave 2 onwards, write the PR's task plan from this document with superpowers:writing-plans, saved under `docs/superpowers/plans/` (one file per wave).
2. **Tests first.** Write the tests first. Regression guards are replayed red on the defect (A8), and the failure message is recorded in the PR body.
3. **Implement.**
4. **Local gates:** `./gradlew build`, `pnpm -C medioo-web lint typecheck test build`, and Playwright: phone and desktop, both themes, axe, CSP hook, overflow, crops, budget.
5. **Ignore check.** `git status --ignored --short -- medioo-web/src medioo-web/e2e baymax/src | grep '^!!'` prints nothing. Check each env file with `git check-ignore <file>`.
6. **Board.** Publish the screenshot board as a private artifact; the PO reviews it.
7. **Review.** An independent code-review agent with the CLAUDE.md flattering-failure checklist.
8. **Stage and push.** Stage explicit paths only (never `git add -A`). Push. Open the PR with `env -u GITHUB_TOKEN gh pr create`.
9. **Merge.** Merge only when "Build and test" is green, `mergeStateStatus` is `CLEAN` and the board is approved. Then verify the commit on `origin/main` before deleting the branch.
10. **Flip.** Post-deploy https self-test table. Tanvir tries the route with preview on, then the route flips in `Caddyfile.medioo`, applied on the box with a backup and reported in the build report.

**Where the PO looks:**
- X1 (DR-40/41);
- the W1a measurement for DR-42;
- the decisions in Part B;
- every W board;
- each on-device step;
- the D3 ruling;
- the ledger before each W-del.

---

## Part F — Wave 0 and Wave 1, task by task

> Wave 2+ tasks are planned at the start of each wave (Part E step 1) against the code as it is then. Writing them now would describe code that W1/B1 have not shaped yet.

### Task X0: Disk hygiene, scoped pruning before pull, https smoke test

**Files:**
- Modify: `deploy.sh` (add `prune_repo` and call it before `docker pull`; remove the global `docker image prune -f` at the old line 62)
- Create: `scripts/test_deploy_prune.sh`
- Modify: `.github/workflows/ci-cd.yml` (run the new test in "Build and test"; the smoke test also goes over https)
- Modify: `docs/RUNBOOK.md` (disk check and the pruning rule)

**Interfaces:**
- Produces: `prune_repo <image-repo> <keep-count>`, a bash function in `deploy.sh`. It removes local tags of exactly `<image-repo>`, keeps the newest `<keep-count>` by creation time, and never removes the image of a running container. It never touches other repositories (n8n, bddrug).

- [ ] **Step 0 (box, manual, PO approval needed — report it):** On EC2, run `df -h /`. Then run `docker images ghcr.io/khondokertanvirhossain/elioo-health/medscribe-ai --format '{{.Tag}} {{.CreatedAt}} {{.ID}}'`. Remove every tag except the running sha and the newest 2. Run `df -h /` again and record both numbers in the build report. Touch no other repository.
- [ ] **Step 1: Write the failing test** `scripts/test_deploy_prune.sh`. It puts a fake `docker` on `PATH` that records calls and serves a fixture image list: 6 medscribe-ai tags, 2 bddrug tags, 1 running. It sources `deploy.sh` in function-only mode (`DEPLOY_FUNCTIONS_ONLY=1`). It asserts:
  - `prune_repo ghcr.io/.../medscribe-ai 3` removes exactly the 3 oldest non-running medscribe-ai tags;
  - it removes 0 bddrug tags;
  - it keeps the running tag even when that tag is the oldest;
  - with an empty list it removes nothing and exits 0.
- [ ] **Step 2:** Run `bash scripts/test_deploy_prune.sh`. Expected: FAIL, `prune_repo: command not found`.
- [ ] **Step 3:** Implement `prune_repo` in `deploy.sh`. Guard the main body with `[ "${DEPLOY_FUNCTIONS_ONLY:-}" = 1 ] && return 0`. Call `prune_repo "$IMAGE" 3` before `docker pull`. Delete `docker image prune -f`.
- [ ] **Step 4:** Run `bash scripts/test_deploy_prune.sh`. Expected: PASS. Also run `bash scripts/test_deploy_is_tip.sh`. Expected: PASS (unchanged).
- [ ] **Step 5: CI and smoke.** Add `bash scripts/test_deploy_prune.sh` to the "Deploy tip check tests" step. In "Smoke test from runner", keep the `:8086` check and add `curl -fsS https://medioo.eliooo.org/actuator/health`. Both must pass.
- [ ] **Step 6: Commit.**
  ```bash
  git add deploy.sh scripts/test_deploy_prune.sh .github/workflows/ci-cd.yml docs/RUNBOOK.md
  git commit -m "fix(deploy): prune only this repo's old images before the pull; smoke over https"
  ```

### Task X1: Decisions and the approved design into the repo

**Files:**
- Create: `docs/design/2026-10-21-approved/` (done in the plan PR: prototypes, contract, Mio, wordmarks, README)
- Modify: `docs/DECISIONS.md` (append DR-40, DR-41)
- Create: `docs/infra/Caddyfile.medioo` (the medioo site block as it is on the box today, copied read-only)
- Modify: `CLAUDE.md` (Layout gains `medioo-web/` and `docs/design/`; Environment facts gain the Caddy snippet's path)
- Modify: `docs/MEDIOO.md` (link to this plan and the approved design)

- [ ] **Step 1:** Copy the live site block. Over SSH, read `/home/ec2-user/n8n/Caddyfile` (read-only), extract the `medioo.eliooo.org` and `baymax.eliooo.org` blocks, and save them to `docs/infra/Caddyfile.medioo` with a header comment saying the box is the source until W1c, after which this file is. Confirm it contains no secrets with `grep -nE 'token|key|password'`. Expected: nothing.
- [ ] **Step 2:** Append **DR-40** ("Next.js frontend beside Java", recording Part A1–A8 as decided) and **DR-41** ("Messages grammar, Mio, wordmark, legibility floor, the দেখেছি acknowledgement: the 2026-10-21 amendment") to `docs/DECISIONS.md`, in the existing DR format with Decision / Why / Alternatives.
- [ ] **Step 3:** Update `CLAUDE.md` and `docs/MEDIOO.md` as listed. Keep CLAUDE.md's "keep it short" rule: at most 4 new lines.
- [ ] **Step 4:** Check every text file in the design folder for real numbers, in all three forms (international, local with hyphens or spaces, Bangla digits): `grep -nE '(\+?880[- ]?|(^|[^0-9])0)1[3-9][0-9]{2}[- ]?[0-9]{6}' docs/design/2026-10-21-approved/*.{html,md}` and `grep -nE '(\+?৮৮০[- ]?)?০১[৩-৯][০-৯]{2}[- ]?[০-৯]{6}' docs/design/2026-10-21-approved/*.{html,md}`. Expected: the first matches the `01700-000000` placeholder (proof that it can fire) and every hit is a fictional placeholder; record each hit.
- [ ] **Step 5: Commit.**
  ```bash
  git add docs/DECISIONS.md docs/infra/Caddyfile.medioo CLAUDE.md docs/MEDIOO.md
  git commit -m "docs(medioo): DR-40/41 — Next.js beside Java, the approved design, the live Caddy block"
  ```

### Task W1a: `medioo-web` skeleton (not deployed) and the budget measurement

**Files:**
- Create: `medioo-web/package.json`, `medioo-web/pnpm-lock.yaml`, `medioo-web/tsconfig.json`, `medioo-web/next.config.ts` (`output: "standalone"`, `poweredByHeader: false`, `reactStrictMode: true`)
- Create: `medioo-web/src/middleware.ts`, `medioo-web/src/security/csp.ts`, `medioo-web/src/security/csp.test.ts`
- Create: `medioo-web/src/app/layout.tsx`, `medioo-web/src/app/app/healthz/route.ts`, `medioo-web/src/app/app/probe/page.tsx` (budget probe: the shell layout, both Noto fonts, one paragraph of Bangla)
- Create: `medioo-web/public/fonts/` (copy `noto-sans-bengali.woff2` and `noto-sans-latin.woff2` from `baymax/src/main/resources/baymax/ui/`)
- Create: `medioo-web/e2e/playwright.config.ts`, `medioo-web/e2e/csp.spec.ts`, `medioo-web/e2e/budget.spec.ts`
- Create: `medioo-web/Dockerfile` (multi-stage `node:22-alpine`; healthcheck `node -e "fetch('http://127.0.0.1:3000/app/healthz').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"`), `medioo-web/.dockerignore`
- Create: `medioo-web/.gitignore` (`/node_modules/`, `/.next/`, `/playwright-report/`, `/test-results/`, `/.env*`)
- Modify: root `.gitignore` (add `.env*` as an anchored rule plus `!medscribe.env.example`)
- Modify: `.dockerignore` (add `medioo-web/` so the Java build context stays the same)
- Modify: `.github/workflows/ci-cd.yml` ("Build and test" gains the pnpm and Playwright steps; Node 22 for every node step)
- Create: `docs/superpowers/specs/2026-10-21-dr42-budget-measurement.md` (numbers from Step 7)

**Interfaces:**
- Produces:
  - `buildCsp(nonce: string): string`, in `src/security/csp.ts`. Exactly the policy in Global Constraints, with `nonce` substituted.
  - `GET /app/healthz` returns `200 {"service":"medioo-web","sha":"<GIT_SHA env>"}` with `cache-control: no-store`.
  - Middleware sets `content-security-policy`, `x-frame-options: DENY`, `x-content-type-options: nosniff`, `referrer-policy: strict-origin-when-cross-origin` and passes the nonce to the layout via the `x-nonce` request header.

- [ ] **Step 1: Write the failing unit test** `csp.test.ts`:
  - `buildCsp("abc")` contains `script-src 'self' 'nonce-abc'`;
  - it contains `style-src 'self'` and not `unsafe-inline`;
  - it contains `default-src 'none'` and `frame-ancestors 'none'`;
  - two calls with different nonces differ only in the nonce.
- [ ] **Step 2:** Run `pnpm -C medioo-web vitest run src/security/csp.test.ts`. Expected: FAIL (module not found).
- [ ] **Step 3:** Implement `buildCsp`, the middleware (nonce = 16 random bytes, base64), the layout reading `headers().get("x-nonce")`, `healthz` and the probe page. Set the `lang` attribute to `bn`.
- [ ] **Step 4:** Run `pnpm -C medioo-web vitest run`. Expected: PASS.
- [ ] **Step 5: Write the e2e tests.**
  - `csp.spec.ts`: on `/app/probe` and `/app/healthz`, collect `securitypolicyviolation` events through an init script. Assert:
    - zero violations;
    - the CSP header is present on both routes;
    - every `<script>` carries a nonce attribute equal to the header's nonce;
    - no element has a `style` attribute.
  - `budget.spec.ts`: on a cold `/app/probe`, sum `response.body()` length (decoded) and the `content-length` or transfer size (wire) per resource type: document, script, font, stylesheet, RSC. Write the numbers to `test-results/budget.json`. Fail only if any script is not same-origin.
- [ ] **Step 6:** Run `pnpm -C medioo-web build && pnpm -C medioo-web exec playwright test`. Expected: PASS, and `test-results/budget.json` exists.
- [ ] **Step 7: Measure for DR-42.** Run `next start` behind a local Caddy with `encode zstd gzip`, the same as production. Record the wire and decoded bytes per class in `docs/superpowers/specs/2026-10-21-dr42-budget-measurement.md`, with the proposal "first-load wire ≤ measured probe + 60 KB per route". The PO decides (Part B, decision 1).
- [ ] **Step 8: CI.** In "Build and test", add:
  - `pnpm/action-setup` and `actions/setup-node` with `node-version: "22"`;
  - `pnpm -C medioo-web install --frozen-lockfile`;
  - `lint`, `typecheck`, `vitest run`, `build`;
  - the server-mutation check (DR-40 A3): fail if `medioo-web/src` contains `'use server'` or `"use server"`, or any fetch to `JAVA_INTERNAL_URL` with a method other than `GET`/`HEAD`; it fails too if it scanned zero files. Replay it red once against a planted `'use server'` and quote the failure line in the PR body;
  - `exec playwright install --with-deps chromium`;
  - `exec playwright test`;
  - upload `medioo-web/playwright-report` on failure.
  
  The existing `node --test baymax/src/test/js/*.test.mjs` step runs on Node 22.
- [ ] **Step 9: Ignore checks.**
  - Run `git status --ignored --short -- medioo-web/src medioo-web/e2e | grep '^!!'`. Expected: no output.
  - Run `git check-ignore medioo-web/.env.local medioo-web/.env.production .env.test`. Expected: all three printed.
  - Run `git check-ignore medscribe.env.example`. Expected: nothing printed.
- [ ] **Step 10: Commit.**
  ```bash
  git add medioo-web/package.json medioo-web/pnpm-lock.yaml medioo-web/tsconfig.json medioo-web/next.config.ts medioo-web/src medioo-web/public/fonts medioo-web/e2e medioo-web/Dockerfile medioo-web/.dockerignore medioo-web/.gitignore .gitignore .dockerignore .github/workflows/ci-cd.yml docs/superpowers/specs/2026-10-21-dr42-budget-measurement.md
  git commit -m "feat(medioo-web): Next.js skeleton with nonce CSP, healthz and a measured budget probe (not deployed)"
  ```

### Task W1b: Deploy `medioo-web` beside Java, with no traffic

**Files:**
- Modify: `deploy.sh` (add `deploy_web`; Java first; prune both repositories)
- Create: `scripts/test_deploy_sh.sh`
- Modify: `.github/workflows/ci-cd.yml` (the deploy job builds and pushes `ghcr.io/khondokertanvirhossain/elioo-health/medioo-web:<sha>` from `medioo-web/Dockerfile`; the test runs in "Build and test")
- Modify: `docs/RUNBOOK.md` (two images, rollback by one `IMAGE_TAG` down to the routes floor, pre-W1b shas deploy Java only and leave a newer `medioo-web` running)

**Interfaces:**
- Consumes: `prune_repo` (X0), `GET /app/healthz` (W1a).
- Produces: `deploy_web <tag>`, a bash function that:
  - prunes the web repository to 3 tags;
  - if `docker pull medioo-web:<tag>` fails, logs `web image <tag> not found; Java-only deploy` and returns 0;
  - otherwise runs `medioo-web` with `--network n8n_edge`, no `-p`, `--memory=320m`, `--restart unless-stopped`, `-e JAVA_INTERNAL_URL=http://medscribe-ai:8086 -e GIT_SHA=<tag>` and no `--env-file`, so Node never gets Java's secrets;
  - waits up to 60 s for `docker exec medioo-web node -e <healthz fetch>`;
  - on timeout prints the last 50 log lines and returns 1.

- [ ] **Step 1: Write the failing test** `scripts/test_deploy_sh.sh`, with the fake `docker` and `curl` from X0 extended with recorded call order. Assert:
  - (a) the Java `rm` and `run` happen before any `medioo-web` `run`;
  - (b) with no web image the script exits 0, Java is deployed and the log line is present;
  - (c) when web health fails the script exits 1 and Java stays running (no Java `rm` after its run);
  - (d) the `docker run` for `medioo-web` contains neither `-p` nor `--env-file`.
- [ ] **Step 2:** Run `bash scripts/test_deploy_sh.sh`. Expected: FAIL (`deploy_web: command not found`).
- [ ] **Step 3:** Implement `deploy_web` and call it after the Java health wait succeeds.
- [ ] **Step 4:** Run `bash scripts/test_deploy_sh.sh && bash scripts/test_deploy_prune.sh && bash scripts/test_deploy_is_tip.sh`. Expected: all PASS.
- [ ] **Step 5: CI.** Add a `docker/build-push-action` step (context `medioo-web`, tags `:<sha>` and `:latest`, gha cache scope `web`). Add `scripts/test_deploy_sh.sh` to the "Deploy script tests (tip check, prune)" step (named "Deploy tip check tests" before X0).
- [ ] **Step 6: Commit.**
  ```bash
  git add deploy.sh scripts/test_deploy_sh.sh .github/workflows/ci-cd.yml docs/RUNBOOK.md
  git commit -m "feat(deploy): medioo-web runs beside Java, Java first, no traffic yet"
  ```
- [ ] **Step 7 (after merge and deploy):** On the box, check:
  - `docker ps` shows both containers;
  - `docker stats --no-stream medioo-web` is under 320 MiB;
  - `free -m` still shows at least 1 GB available.
  
  Record all three in the build report.

### Task W1c: Caddy per-path routing, the https upstream table, Java off the public port

**Files:**
- Modify: `docs/infra/Caddyfile.medioo` (add `handle /app/healthz { reverse_proxy medioo-web:3000 }` before the default `reverse_proxy medscribe-ai:8086`)
- Create: `scripts/smoke-https.sh` (the path-class table)
- Modify: `.github/workflows/ci-cd.yml` (run `scripts/smoke-https.sh` after deploy; then drop the `:8086` smoke test)
- Modify: `deploy.sh` (`-p 127.0.0.1:${PORT}:${PORT}` instead of `-p ${PORT}:${PORT}`), `scripts/test_deploy_sh.sh` (assert the Java port binds to 127.0.0.1)
- Modify: `docs/RUNBOOK.md` (how to flip a route: edit `Caddyfile.medioo`, diff the box's medioo/baymax blocks against it first, back up the box Caddyfile, write it in place only — single-file bind mount, never `sed -i`/`mv`/`rsync` — confirm the container sees the new file, `caddy validate`, reload, `curl -sI` medioo, n8n and drugdb, run `scripts/smoke-https.sh`; and the rollback floor: revert the route before rolling back past it)
- Modify: `deploy.sh` (record and enforce the routes floor: refuse an `IMAGE_TAG` that is not a descendant of the recorded routes-floor sha unless `FORCE=1`), `scripts/test_deploy_sh.sh` (both directions: a descendant deploys, an ancestor is refused, `FORCE=1` overrides)

**Interfaces:**
- Produces: `scripts/smoke-https.sh <base-url>`. It exits 0 only when every row passes:
  - `/app/healthz` → 200 and body `service == "medioo-web"`;
  - `/actuator/health` → 200 and body `status == "UP"`;
  - `/app/` → 200, HTML from Java (contains `name="whatsapp_number"`);
  - `/medscribeai` and `/medscribeai/` → not 404;
  - `/baymax` → 301 to `/`;
  - `/api/v1/baymax/wa/webhook` (GET, no params) → Java's verification refusal status (the same as today, measured in Step 1);
  - the actual response headers per path class (Java page, Next page, API): `content-security-policy` present (the nonce CSP on Next paths), `x-frame-options: DENY`, `x-content-type-options: nosniff`, `referrer-policy: strict-origin-when-cross-origin`, `strict-transport-security` present;
- Produces: a drift step in the deploy job, after the smoke, over the job's existing SSH: extract the box's live medioo/baymax blocks read-only and diff them against `docs/infra/Caddyfile.medioo`. A difference fails the step with "box Caddyfile drifted from the repo — fold the box edit in first". Test it both ways against fixture files: identical blocks pass, one changed header line fails.

- [ ] **Step 1:** Run each table URL against production now. Record today's status codes as the expected values (the webhook refusal code in particular). Write `scripts/smoke-https.sh` with those values and run it. Expected: everything passes except `/app/healthz`, which fails because Next is not routed yet.
- [ ] **Step 2:** Apply on the box, following the `Caddyfile.medioo` header. First diff the live medioo/baymax blocks against the repo file; any difference is a box edit to fold into the repo file first, never overwrite. Record `curl -sI` status codes for medioo, n8n and drugdb. Back up the Caddyfile (`cp Caddyfile Caddyfile.bak-$(date +%Y%m%d-%H%M)`). Write it in place only (`cat new > Caddyfile`; never `sed -i`, `mv` or `rsync`: the file is a single-file bind mount). Confirm `docker exec n8n-caddy-1 cat /etc/caddy/Caddyfile | diff - /home/ec2-user/n8n/Caddyfile` prints nothing. Run `docker exec n8n-caddy-1 caddy validate --config /etc/caddy/Caddyfile`. Expected: `Valid configuration`. Then reload, and record the routes-floor sha.
- [ ] **Step 3:** Run `scripts/smoke-https.sh https://medioo.eliooo.org`. Expected: every row PASS. Also check `bddrug` and `n8n` still answer (`curl -sI` each host; the status codes are unchanged). Report the box change.
- [ ] **Step 4:** Update the `-p` binding test, then implement and run the deploy tests. Expected: PASS.
- [ ] **Step 5: Commit.**
  ```bash
  git add docs/infra/Caddyfile.medioo scripts/smoke-https.sh deploy.sh scripts/test_deploy_sh.sh .github/workflows/ci-cd.yml docs/RUNBOOK.md
  git commit -m "feat(infra): Caddy sends /app/healthz to medioo-web; https upstream smoke; Java off the public port"
  ```

### Task B1: The `/app/api` router — session, CSRF, reasons, `/me`, active patient, preview

**Files:**
- Create: `baymax/src/main/java/com/elioo/baymax/web/adapter/in/api/AppApiRouter.java`
- Create: `baymax/src/main/java/com/elioo/baymax/web/adapter/in/api/AppApiGuard.java`
- Create: `baymax/src/main/java/com/elioo/baymax/web/adapter/in/api/ApiReason.java`
- Create: `baymax/src/main/java/com/elioo/baymax/web/adapter/in/api/MeApiHandler.java`
- Modify: `baymax/src/main/java/com/elioo/baymax/config/BaymaxProperties.java` (`web.public-origin`, `web.preview-family-ids`)
- Modify: `medscribe.env.example` (document `BAYMAX_WEB_PUBLIC_ORIGIN=https://medioo.eliooo.org`, `BAYMAX_WEB_PREVIEW_FAMILIES=`)
- Test: `baymax/src/test/java/com/elioo/baymax/web/adapter/in/api/AppApiGuardTest.java`, `AppApiRouteContractTest.java`, `MeApiHandlerTest.java`, `AppApiLogSafetyTest.java`

**Interfaces:**
- Consumes: the existing `SessionAuthFilter` session resolution, `ActivePatient` (cookie semantics unchanged), `WebAuthService`, `HealthRecordPort` (family and patients).
- Produces:
  - `enum ApiReason { UNAUTHENTICATED, MISSING_HEADER, FORBIDDEN_ORIGIN, UNSUPPORTED_MEDIA_TYPE, NOT_FOUND, INVALID }`. The error body is always `{"reason":"<NAME>"}`; status codes 401 / 403 / 403 / 415 / 404 / 400.
  - `GET /app/api/me` → `{family:{id, owner_name}, patients:[{id, name, name_possessive, name_objective, age, sex, owner, documents, last_document_at, urgent:boolean}], active_patient_id: string|null, preview: boolean}`.
  - `PUT /app/api/active-patient` with body `{patient_id}` → 204 plus the same `baymax_patient` `Set-Cookie` that `ActivePatient` writes today. 404 `NOT_FOUND` for a patient outside the family.
  - `AppApiRouteContractTest` writes `build/route-contract/app-routes.txt` (every `/app/**` route of every Java router, method and pattern, sorted). The committed snapshot is `docs/infra/app-routes.txt`.

- [ ] **Step 1: Write the failing guard tests** `AppApiGuardTest` (WebTestClient over the router with a stub session):
  - `GET /app/api/me` with no session → 401 `{"reason":"UNAUTHENTICATED"}`;
  - `PUT /app/api/active-patient` with a session and JSON but no `X-Medioo` → 403 `MISSING_HEADER`;
  - the same with `X-Medioo: 1` and `Origin: https://evil.example` → 403 `FORBIDDEN_ORIGIN`;
  - the same with `X-Medioo: 1` and no `Origin` at all (a server-side fetch) → 403 `FORBIDDEN_ORIGIN`;
  - the same with `X-Medioo: 1`, the correct `Origin` and `Content-Type: text/plain` → 415 `UNSUPPORTED_MEDIA_TYPE`;
  - the same with `X-Medioo: 1`, the correct `Origin` and JSON → 204;
  - a `GET` with a session needs no `X-Medioo` → 200.
- [ ] **Step 2:** Run `./gradlew :baymax:test --tests '*AppApiGuardTest'`. Expected: FAIL (the classes do not exist).
- [ ] **Step 3:** Implement `ApiReason`, `AppApiGuard` (a `HandlerFilterFunction`; the session check reuses `SessionAuthFilter`'s resolver; mutating = POST/PUT/PATCH/DELETE) and `AppApiRouter`, mounted under `/app/api` and composed before the HTML `requireSession` filter, so that `/app/api` never 303s.
- [ ] **Step 4:** Run the same command. Expected: PASS.
- [ ] **Step 5: Write the derived route test** `AppApiRouteContractTest`. It walks every `RouterFunction` bean with `RouterFunctions.Visitor` and collects the routes under `/app/api`. It asserts:
  - the set is non-empty;
  - each route answers 401 with no session (the 401 assertion is derived from the route list, not a hand list);
  - it writes `app-routes.txt` for all `/app/**` routes and compares it with `docs/infra/app-routes.txt`. A diff fails with the message "a legacy /app route changed — WhatsApp links depend on it; update the snapshot only with PO sign-off".
- [ ] **Step 6:** Run it. Expected: FAIL on the missing snapshot. Generate the snapshot, review that every legacy path from Global Constraints is present, commit it, and run again. Expected: PASS.
- [ ] **Step 7: Write the handler tests** `MeApiHandlerTest` (Testcontainers Postgres, two patients):
  - `name_possessive` and `name_objective` equal what `NameGrammar` returns for each name;
  - `urgent` is true only for the patient with a stored NOW paper;
  - `active_patient_id` is null without the cookie and equals the patient after `PUT`;
  - `preview` is true only for an allowlisted family. The negative direction: a non-listed family sends a forged `medioo_preview=1` cookie and still gets `false`;
  - the JSON contains no key named `confidence`, `model` or `whatsapp_number` (checked by field over the parsed tree, not by substring).
- [ ] **Step 8:** Run `./gradlew :baymax:test --tests '*MeApiHandlerTest'`. Expected: FAIL. Implement `MeApiHandler`. Run again. Expected: PASS.
- [ ] **Step 9: Log safety** `AppApiLogSafetyTest`, with a `ListAppender` on the root logger at INFO. Exercise every `/app/api` route with the fictional owner number `+8801700000123` and patient name "রোকেয়া পরীক্ষা". Assert that no log event's formatted message or MDC value contains `8801700000123`, `01700000123` or `রোকেয়া পরীক্ষা`, field by field. Also assert that at least one event was captured, so the check cannot pass vacuously.
- [ ] **Step 10: Replay.** Temporarily remove the `X-Medioo` check. Run `AppApiGuardTest`. Expected: FAIL with `expected: 403 MISSING_HEADER but was: 204`. Record that line in the PR body, then restore the check.
- [ ] **Step 11:** Run `./gradlew build`. Expected: PASS.
- [ ] **Step 12: Commit.**
  ```bash
  git add baymax/src/main/java/com/elioo/baymax/web/adapter/in/api baymax/src/main/java/com/elioo/baymax/config/BaymaxProperties.java baymax/src/test/java/com/elioo/baymax/web/adapter/in/api docs/infra/app-routes.txt medscribe.env.example
  git commit -m "feat(baymax-web): /app/api — session JSON under the /app cookie, CSRF guard, reasons, /me and the active patient"
  ```

---

## Self-review notes (writing-plans)

**Spec coverage.** All the items below have a PR:
- The approved screens: W3–W10.
- Voice: W11a and W11b.
- Sounds and Mio: W12.
- Brand: W2c, using the X1 assets.
- The legibility floor: Global Constraints, W2a and every W test.
- দেখেছি: B3 and W5.
- keepmd parity: deferred by decision 10.
- The prior work items: Part C.
- The Graph deadline: P10.
- The audit data bugs: D1–D4.
- Missing JSON: B1–B6.

**Wave 2+ tasks are deliberately not expanded here.** The reason is in the Part F note. Each wave's plan is written and reviewed before that wave starts.

**Consistency.** The names used across tasks agree:
- `prune_repo` is used by X0, W1b and W1c.
- `/app/healthz` is used by W1a, W1b and W1c.
- `ApiReason` names are reused by Review Focus 4 and 5.
- No migration number is fixed in advance, apart from V22 and V23, which already belong to Phase 0.
