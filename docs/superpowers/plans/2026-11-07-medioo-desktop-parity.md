# Medioo Desktop Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Medioo's desktop web app match the approved desktop design. Phase 1 builds the sidebar and **কথা ও কাগজ** (the paper and the conversation side by side). The PO tries Phase 1 in preview before it goes live.

**Architecture:**
- **Where the new layout runs.** Next.js pages stay where they are. Below 900 px nothing changes: the phone tabs and the phone pages stay until the mobile list is done.
- **The new desktop layout.** From 900 px it is a new `DesktopShell`: the sidebar, a paper column and a conversation column.
- **The preview gate.** The new layout renders only on preview requests (`/app/preview/...` for families in `BAYMAX_WEB_PREVIEW_FAMILIES`) until the PO approves it. One constant then makes it the default. Merging therefore changes nothing for live users.
- **What Java must add.** Two additive fields: a per-patient urgency level, and thread text without the WhatsApp links (the phone keeps reading `body` until the mobile list is marked). Plus "proto" fixture cases that reproduce the prototype's pretend family.

**Tech Stack:**
- medioo-web: Next.js 15, React 19, TypeScript, Vitest + Testing Library, Playwright.
- baymax: Spring WebFlux, Java 21; the contract tests write `medioo-web/fixtures/api`.

**Spec:**
- The approved desktop design, `docs/design/2026-10-21-approved/desktop.html` (artifact https://claude.ai/artifact/58dhF1LYuiTsNmeXoH5hpS), and the direction contract beside it.
- The PO's marked gap list, https://claude.ai/artifact/8A81rYbSaQDw3nKVdX7uTF: 31 rows. 27 match, C2 and F2 keep, V1 later.
- Ruling P19 (2026-11-07), below. This plan amends the layout sections of `2026-10-21-medioo-nextjs-rebuild.md` for widths ≥ 900 px. Everything else in that plan still applies.

## Global Constraints

**Layout and wording**
- Desktop (≥ 900 px) follows `desktop.html`, not `phone.html`. Below 900 px is unchanged until the mobile gap list is marked.
- The desktop menu: a family list, then "{possessive} খাতা" with কথা ও কাগজ · ক্যালেন্ডার · ল্যাবের মান · ওষুধ · আসছে. The phone tabs চ্যাট · কাগজপত্র · স্বাস্থ্য · পরিবার stay for < 900 px only.
- Every new word comes from the Java bundles: `ui-shell_bn.properties` / `ui-shell_en.properties`, exported by `WebCopyExportTest`. The Bangla is the prototype's own text, character for character. Nothing is typed into a `.tsx` (the `bangla-literal` lint already enforces this).
- Kept as built (PO marks): the papers page's filter chips and chronic-illness card (C2), and the theme switch (F2). Voice and sounds (V1) are not in this plan.

**Safety (P19 and earlier rulings)**
- **NOW bar.** One slim bar across the content columns, never over the sidebar. It shows the patient's name first, then Java's `line_key` sentence verbatim (DR-45 unchanged), then the paper's type and date, with কাগজটা দেখুন · ৯৯৯ (only when `call_999`) · দেখেছি. The warning appears in the bar and on that paper's card, and nowhere else on the screen.
- **A paper's details.** The card in the conversation keeps the red header and the 999 button verbatim. "সব দেখুন" opens a side panel over the conversation holding everything today's paper page holds: every value with its crop and range, the NOW block, doctor's notes, medicines, next step and page links. No safety block is dropped.
- **Disclaimer (X1).** The footer disclaimer is removed on desktop. "আমি ডাক্তার নই" sits in the conversation header, and the NOW block keeps its own not-a-doctor line.
- **Quick replies.** Actions only: আবার পাঠাই (a retake card only) and ভালো ছবির উপায়. No chip sends a question to the model.

**Process**
- Live users see no change until the PO approves in preview. The gate is `desktopLayout(ctx)` (Task 2). The flip is Task 10.
- Every board compares a ≥ 900 px capture only with a `desktop.html` scene. The guard is Task 1.
- DR-42 byte ceilings are never raised.
- The CLAUDE.md rules all apply: stage explicit paths only, run the ignore check, replay every regression guard red on the defect, and use `env -u GITHUB_TOKEN gh`.

## Review Focus

1. **An urgent paper opened from a WhatsApp link** (`/app/documents/{id}`, NOW, `call_999` true) on desktop must show the NOW bar, the red card with ৯৯৯, and the details panel already open. It must never show just the image. *Pinned in Task 8, test `aNowPaperFromALinkOpensWithItsPanelAndItsWarning`.*
2. **A share member** (`owner: false`) must not see the delete button or the paperclip. They still see the paper and the conversation. *Task 6 and Task 7 tests.*
3. **A paper still being read** (`PaperReadingJson`) selected in the viewer shows its pages, if any, with the reading line. It is never a blank canvas. *Task 6, `aReadingPaperShowsItsPagesAndTheReadingLine`.*
4. **A long Bangla name and 45 papers.** The sidebar wraps the name and the strip scrolls sideways, with no overflow (`scrollWidth <= clientWidth`) at 900, 1024 and 1440. *Task 9 Playwright.*
5. **The page's weight.** `/app/home` at desktop width stays inside the DR-42 ceilings, and the paper images load lazily. *Task 9 runs `budget.spec.ts` with the desktop layout on.*

## P19 — the PO's answers (2026-11-07), for the record

| Question | Answer |
|---|---|
| NOW bar wording | The name first, then Java's sentence unchanged (DR-45 kept). |
| A paper's details in কথা ও কাগজ | The card keeps the warning and ৯৯৯ verbatim; "সব দেখুন" opens a side panel with everything; nothing safety-related is dropped. |
| Quick replies | Actions only for now; question chips later. |
| Order | Rewrite the plan; build the sidebar and কথা ও কাগজ; the PO tries it in preview beside the design before it goes live; then one screen at a time. |

---

## Phase 1 — sidebar and কথা ও কাগজ (what the PO tries first)

### File map

| File | Responsibility |
|---|---|
| `medioo-web/e2e/board.spec.ts`, `medioo-web/e2e/support/board-scenes.test.ts` (new) | Desktop scenes compare with `desktop.html`; the guard. |
| `medioo-web/src/routing/desktop.ts` (new) | `DESKTOP_DEFAULT`, `desktopLayout(ctx)`. |
| `baymax/.../web/adapter/in/api/MeJson.java` and its handler | `urgency` per patient. |
| `baymax/.../web/adapter/in/api/ThreadJson.java` and its handler | `web_body` (additive). |
| `baymax/src/test/java/.../web/adapter/in/api/*ContractTest.java` | `proto-*` cases (the prototype's family). |
| `baymax/src/main/resources/baymax/ui-shell_{bn,en}.properties` | Every new word, `desk.*` keys. |
| `medioo-web/src/components/DesktopSidebar/` (new) | Family list, the ledger menu, আসছে, next-visit card, account row. |
| `medioo-web/src/components/DesktopShell/` (new) | Three columns, the NOW bar slot, 900–1199 behaviour. |
| `medioo-web/src/components/PaperViewer/` (new) | Toolbar, the page canvas with zoom, the paper strip. |
| `medioo-web/src/components/Conversation/` (new) | Header, the desktop thread items, quick actions, composer. |
| `medioo-web/src/components/PaperPanel/` (new) | The side panel; reuses `PaperSummary`'s parts. |
| `medioo-web/src/components/safety/NowBanner/` | `variant="bar"`. |
| `medioo-web/src/app/app/(signed-in)/home/page.tsx`, `documents/[id]/page.tsx`, `shell.tsx` | Render `DesktopShell` when `desktopLayout(ctx)`. |

### Task 1: The board compares desktop with the desktop design (the guard first)

**Files:**
- Create: `medioo-web/e2e/support/board-scenes.test.ts`
- Modify: `medioo-web/e2e/board.spec.ts` (export `BOARD_SCENES` and `VIEWPORTS` unchanged in shape; add `proto.desktop?: {scene, act?}`)

**Interfaces:**
- Produces: `protoFor(scene: Scene, width: number): { file: "phone.html" | "desktop.html"; scene: string; act?: (page) => Promise<void> }`. It returns `scene.proto.desktop` (on `desktop.html`) whenever `width >= 900`, else `scene.proto`.

- [ ] **Step 1: Write the failing guard** (Vitest, runs in `pnpm test`):

```ts
test("every capture at 900 px or wider is compared with desktop.html", () => {
  const wrong = BOARD_SCENES.flatMap((s) =>
    Object.entries(s.viewports ?? VIEWPORTS)
      .filter(([, v]) => v.width >= 900 && protoFor(s, v.width).file !== "desktop.html")
      .map(([name]) => `${s.screen}/${s.state}@${name}`));
  expect(wrong).toEqual([]);
});
test("the guard is not vacuous", () => {
  expect(BOARD_SCENES.some((s) => Object.values(s.viewports ?? VIEWPORTS).some((v) => v.width >= 900))).toBe(true);
});
```

- [ ] **Step 2: Replay red on today's board.** Before `protoFor` exists, inline today's rule (`scene.proto.file`). Run `pnpm -C medioo-web test board-scenes`. Expected: FAIL, listing the desktop captures that point at `phone.html` (15 scenes today; chat/default@desktop among them). Paste the list into the PR body.
- [ ] **Step 3:** Add `protoFor` and a `proto.desktop` to every scene, using the scenes from the PO's gap list: chat states → `app` with the patient and paper named; papers → `app` and then clicking `.si[data-view="papers"]`; health → the labs view; family → settings; upload → `app` and then clicking `[data-action="upload"]`. `board.spec.ts` captures through `protoFor`.
- [ ] **Step 4:** Run `pnpm -C medioo-web test board-scenes`. Expected: PASS.
- [ ] **Step 5:** Commit `test(medioo-web): the board compares desktop captures with desktop.html only`.

### Task 2: The preview gate

**Files:**
- Create: `medioo-web/src/routing/desktop.ts`, `medioo-web/src/routing/desktop.test.ts`

**Interfaces:**
- Produces: `export const DESKTOP_DEFAULT = false` and `export function desktopLayout(ctx: { preview: boolean }): boolean`, which returns `ctx.preview || DESKTOP_DEFAULT`.

- [ ] **Step 1:** Tests: `desktopLayout({preview:true})` is `true`; `desktopLayout({preview:false})` equals `DESKTOP_DEFAULT`; and `DESKTOP_DEFAULT` is `false` (this test changes in Task 10, which is the point).
- [ ] **Step 2:** Run. Expected: FAIL (module missing).
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run. Expected: PASS.
- [ ] **Step 5:** Commit `feat(medioo-web): desktop layout behind the preview gate`.

### Task 3: Java — the patient's urgency, the thread's web text, the prototype's family

**Files:**
- Modify: `MeJson.java` (patients gain `urgency`), `ThreadJson.java` (messages gain `web_body`), and their handlers. Both are additive; the phone pages keep reading what they read today.
- Modify: `medioo-web/src/api/types.ts` (`MePatient.urgency: UrgencyLevel | null`; `ThreadMessageJson.web_body: string`)
- Test: `MeApiContractTest`, `ThreadApiContractTest`, `PaperApiContractTest`, `AttentionApiContractTest`, the NOW-banner contract test, and the follow-ups contract test (`PatientTimelineApiContractTest` writes `follow-ups/`).

**Interfaces:**
- Produces:
  - `MePatient.urgency`: the level the attention stack's urgent item would carry for this patient (`AttentionUrgentJson.level`, the same Java rule, called once), else `null`. Values: `"now" | "this_week" | null`. `urgent` stays as it is.
  - `ThreadMessageJson.web_body`: `body` with every line that contains `http://` or `https://` removed, and nothing else changed.
  - Fixtures: `fixtures/api/{me,thread,documents,now-banner,attention,follow-ups}/proto-*.json`.

- [ ] **Step 1: Write the failing contract tests, both directions:**
  - `aPatientWithAnUnseenNowPaperIsNow`, `aPatientWithOnlyThisWeekIsThisWeek` and `aPatientWithNothingUrgentIsNull`.
  - `webBodyDropsOnlyTheLinkLines`: the body `"Ma-এর ফলো-আপ…\nএই ধরনের বার্তা আর চান না? এখানে ক্লিক করুন: https://…/opt-out?p=…"` gives `"Ma-এর ফলো-আপ…"`, and a body with no link is unchanged character for character.
- [ ] **Step 2:** Run `./gradlew :baymax:test --tests '*MeApiContractTest' --tests '*ThreadApiContractTest'`. Expected: FAIL on the missing fields.
- [ ] **Step 3:** Implement. Then add the `proto-*` cases. They hold the prototype's family exactly as `desktop.html` lines 606–639 define it:
  - আম্মা: রোকেয়া বেগম, 68. Papers: lipid 13 Oct, creat 14 Oct (THIS_WEEK), rx 15 Oct with the follow-up on 22 Oct, cbc 20 Oct, blur 20 Oct (NEEDS_RETAKE).
  - আব্বু: আব্দুল করিম, 74. Papers: sugar 10 Oct, potas 16 Oct (NOW, Potassium 11.0 against 3.5–5.1).

  Each case goes through the real handler, so the fixtures are Java's own output.
- [ ] **Step 4:** Run the same command and `cp` each written fixture as the test prints. Expected: PASS. `pnpm -C medioo-web test types` passes with the new fields.
- [ ] **Step 5:** Commit `feat(baymax-web): patient urgency, web thread text without links, the prototype family's fixtures`.

### Task 4: The words

**Files:**
- Modify: `baymax/src/main/resources/baymax/ui-shell_bn.properties`, `ui-shell_en.properties`, then regenerate `medioo-web/src/copy/generated/copy.{bn,en}.json` with `WebCopyExportTest`.

**Interfaces:**
- Produces the keys below. `{0}` is a possessive or a number.

| key | bn (from desktop.html) | en |
|---|---|---|
| desk.family | পরিবার | Family |
| desk.ledger | {0} খাতা | {0} records |
| desk.nav.talk | কথা ও কাগজ | Talk and papers |
| desk.nav.calendar | ক্যালেন্ডার | Calendar |
| desk.nav.labs | ল্যাবের মান | Lab values |
| desk.nav.meds | ওষুধ | Medicines |
| desk.nav.soon | আসছে | Coming |
| desk.soon.badge | শিগগিরই | Soon |
| desk.soon.summary / card / conditions / vaccines / scans / devices | ডাক্তারের জন্য সারাংশ / জরুরি কার্ড / অসুখ ও অ্যালার্জি / টিকা / এক্স-রে ও স্ক্যান / ডিভাইস | Summary for the doctor / Emergency card / Conditions and allergies / Vaccines / X-rays and scans / Devices |
| desk.visit.title | ডাক্তারের কাছে আবার | Back to the doctor |
| desk.visit.in | আর {0} দিন | in {0} days |
| desk.account | পরিবার ও অ্যাকাউন্ট | Family and account |
| desk.talk.sub | {0} কাগজ নিয়ে কথা · আমি ডাক্তার নই | About {0} papers · I'm not a doctor |
| desk.viewer.prev / next | আগের কাগজ / পরের কাগজ | Previous paper / Next paper |
| desk.viewer.zout / zin / fit | ছোট করুন / বড় করুন / মাপমতো | Smaller / Larger / Fit |
| desk.viewer.delete | কাগজটা মুছুন | Delete this paper |
| desk.viewer.sub | {0} কাগজ | {0} paper |
| desk.viewer.strip | {0} কাগজগুলো | {0} papers |
| desk.attach | কাগজ পাঠান | Send a paper |
| desk.quick.resend | আবার পাঠাই | Send it again |
| desk.quick.tips | ভালো ছবির উপায় | Tips for a good photo |
| desk.first.title | প্রথম কাগজটা এখানে টেনে আনুন | Drag the first paper here |
| desk.first.body | রিপোর্ট বা প্রেসক্রিপশনের ছবি, স্ক্যান বা PDF। Medioo পড়ে ডান দিকে উত্তর দেবে। | A photo, scan or PDF of a report or prescription. Medioo reads it and answers on the right. |
| desk.first.pick | ফাইল বেছে নিন | Choose a file |
| desk.panel.open | সব দেখুন | See everything |
| desk.panel.close | বন্ধ করুন | Close |

- [ ] **Step 1:** Add the keys. `WebCopyExportTest` fails, because the committed JSON is stale.
- [ ] **Step 2:** Regenerate and copy, as the test prints. `PaperApiContractTest.everyEmittedKeyResolvesInBothLanguages` and `pnpm -C medioo-web test copy` pass.
- [ ] **Step 3:** Commit `feat(baymax-copy): the desktop sidebar and কথা ও কাগজ words`.

### Task 5: DesktopSidebar and DesktopShell

**Files:**
- Create: `medioo-web/src/components/DesktopSidebar/{DesktopSidebar.tsx,DesktopSidebar.css,DesktopSidebar.test.tsx}`, `medioo-web/src/components/DesktopShell/{DesktopShell.tsx,DesktopShell.css,DesktopShell.test.tsx}`
- Modify: `medioo-web/src/app/app/(signed-in)/shell.tsx`. `signedInPage` gains `desktop?: { paper: Slot; talk: Slot }`. When `desktopLayout(ctx)` is true and `desktop` is given, it renders `DesktopShell`; otherwise it renders today's `AppShell`, unchanged.

**Interfaces:**
- Consumes: `MeJson` (with `urgency`), `FollowUpsJson` from `GET /app/api/patients/{id}/follow-ups`, `desktopLayout`, and the Task 4 keys.
- Produces:
  - `DesktopSidebar({ lang, preview, me, active: MePatient | null, current: "talk" | "calendar" | "labs" | "meds" | "settings", nextVisit: FollowUpItemJson | null })`.
  - `DesktopShell({ lang, preview, sidebar: ReactNode, bar?: ReactNode, paper: ReactNode, talk: ReactNode, panel?: ReactNode })`.

- [ ] **Step 1: Write the failing tests** (Testing Library):
  - `listsEveryPatientWithTheirUrgencyWord`: আব্বু shows "এখনই", আম্মা shows "এ সপ্তাহে", from `proto` me. The word comes from the existing urgency chip keys, never the colour alone.
  - `theActivePatientIsMarkedCurrent` (`aria-current="page"`).
  - `theLedgerNamesTheActivePatient` ("আম্মার খাতা").
  - `menuLinksGoWhereTheyShouldInPhase1`: কথা ও কাগজ → `/app/home`, ক্যালেন্ডার → `/app/timeline`, ল্যাবের মান → `/app/health`, ওষুধ → `/app/health#medicines`. All of them keep `/app/preview` on a preview page.
  - `soonGroupIsCollapsedAndItsItemsAreNotLinks`: six items, each with "শিগগিরই", none focusable as a link.
  - `nextVisitShowsDaysLeftAndHidesWhenNone`: proto rx gives "আর ২ দিন" on 20 Oct; with no follow-up, no card.
  - `accountRowOpensFamily` (`/app/family`, text "পরিবার ও অ্যাকাউন্ট").
  - `noNowBarOverTheSidebar`: the shell renders `bar` inside the content region, not before the sidebar.
  - `belowNineHundredTheSidebarIsBehindTheToolbarButton`: reuse `SidebarToggle`, at 900–1199 as today.
  - `noFooterDisclaimerOnDesktop` (X1): the desktop shell renders no `footer.app-shell__foot` (today's disclaimer footer); the conversation header carries "আমি ডাক্তার নই" (Task 7).
- [ ] **Step 2:** Run `pnpm -C medioo-web test DesktopSidebar DesktopShell`. Expected: FAIL.
- [ ] **Step 3:** Implement. Take the measurements from `desktop.html`'s CSS: sidebar 256 px (the token `--sidebar-w`); conversation 400 px (`--conversation-w`); the paper column fills the rest. A patient row is the avatar initial and name, with the full name below and the urgency word on the right. A patient link sets the active patient with today's `PUT /app/api/active-patient` (`choose.ts`).
- [ ] **Step 4:** Run the tests. Expected: PASS. Also run `pnpm -C medioo-web lint typecheck`.
- [ ] **Step 5:** Commit `feat(medioo-web): desktop sidebar and three-column shell (preview only)`.

### Task 6: PaperViewer

**Files:**
- Create: `medioo-web/src/components/PaperViewer/{PaperViewer.tsx,Zoom.tsx,PaperStrip.tsx,PaperViewer.css,PaperViewer.test.tsx}`

**Interfaces:**
- Consumes: `PaperJson` (`pages`, `document_type`, `date`, `facility`, `urgency`, `owner`), `PageImage`, `UrgencyTag`, `DeletePaper`.
- Produces: `PaperViewer({ lang, preview, paper: PaperJson | null, papers: PaperJson[], selectedId: string | null, hrefFor: (id: string) => string })`.
  - `papers` is the patient's papers, newest first.
  - `hrefFor` gives each strip item's and each prev/next's link (`/app/home?paper={id}` in Task 8).
  - `Zoom` is a client island: − / percentage / + / fit, stepping 0.25 between 0.5 and 3, and **fit** sets the width to the canvas. Keys `-`, `+` and `0` work, and ← → move between papers.

- [ ] **Step 1: Write the failing tests:**
  - `toolbarShowsTitleDateLabAndTheUrgencyWord`: proto cbc gives "রক্ত পরীক্ষা · ২০ অক্টোবর", "আম্মার কাগজ · Shapla Diagnostics" and "সীমার মধ্যে".
  - `prevIsDisabledOnTheOldestAndNextOnTheNewest`.
  - `theStripListsEveryPaperWithItsDateAndUrgencyDot`: 5 for আম্মা; the selected item is `aria-current`.
  - `aShareMemberHasNoDeleteButton` (Review Focus 2).
  - `aReadingPaperShowsItsPagesAndTheReadingLine` (Review Focus 3): a `PaperReadingJson` fixture renders `ReadingProgress` and its pages, never an empty canvas.
  - `noPaperShowsTheFirstVisitDropZone`: Task 4's first-visit words, with the button linking to `/app/add`.
  - `zoomStepsAndClamps`: clicking + at 3 stays at 3, − at 0.5 stays at 0.5, and the label reads "১০০%" at 1.
- [ ] **Step 2:** Run `pnpm -C medioo-web test PaperViewer`. Expected: FAIL.
- [ ] **Step 3:** Implement. Page images use `loading="lazy"` except page 1, which is the only image the canvas shows above the fold.
- [ ] **Step 4:** Run. Expected: PASS.
- [ ] **Step 5:** Commit `feat(medioo-web): the paper viewer — toolbar, zoom, strip`.

### Task 7: Conversation (the right-hand column)

**Files:**
- Create: `medioo-web/src/components/Conversation/{Conversation.tsx,TalkCard.tsx,QuickActions.tsx,DaySeparator.tsx,Conversation.css,Conversation.test.tsx}`
- Modify: `medioo-web/src/components/Composer/Composer.tsx`. Add a `variant: "phone" | "desk"`. `desk` is the paperclip, the field and send, with no camera circle and no disclaimer line under it. The phone variant is unchanged.

**Interfaces:**
- Consumes:
  - `ThreadJson` (with `web_body`) and `mergeAsks` (merge.ts);
  - `VerdictCard`, `NowBlock`, `AskAnswer` and `MioAvatar`;
  - the existing `Composer` ask flow.
- Produces: `Conversation({ lang, preview, patient: MePatient, items: ThreadItemJson[], selectedId: string | null, hrefFor })`.
  - Each `paper` item renders as a sent-photo bubble on the right (its page 1, linking to `hrefFor(id)`), then `TalkCard` on the left.
  - `TalkCard` shows the verdict headline, "type · date · facility", and up to three value rows with chips (the `pin` order Java sends). It is outlined when `card.id === selectedId`, and has "সব দেখুন" linking to `hrefFor(id) + "&panel=1"`.
  - A NOW card keeps the red header line and ৯৯৯, exactly as `VerdictCard` renders them today.
  - `message` items render `web_body` in a received bubble; `detail` messages (the WhatsApp "বিস্তারিত" reply, whose content the card and panel already show) are not rendered. `ask` items render as today.
  - A `DaySeparator` comes before the first item of each day: weekday, day, month and time, through `formatDate` / `formatTime`.

- [ ] **Step 1: Write the failing tests:**
  - `noRawLinkIsEverShown`: for every item in `fixtures/api/thread/*.json`, the rendered text contains no `http://` and no `https://`. Replay it on today's `Thread` to see it fail on the opt-out link.
  - `aPaperIsASentPhotoThenACard`.
  - `theSelectedCardIsOutlined`.
  - `aNowCardKeepsItsWarningAndCall999Verbatim`: the text equals Java's `line_key` copy, and the tel link is present only when `call_999`.
  - `aRetakeCardOffersResendAndTips`: আবার পাঠাই → `/app/add?patient={id}&replaces={docId}`, using the query today's retake link uses. ভালো ছবির উপায় opens the tips sheet the add page already has.
  - `aRoutineCardOffersNoQuickReplies`.
  - `headerSaysWhoseAndNotADoctor` ("আম্মার কাগজ নিয়ে কথা · আমি ডাক্তার নই").
  - `aShareMemberHasNoPaperclip` (Review Focus 2).
  - `daySeparatorsAppearOncePerDay`.
- [ ] **Step 2:** Run `pnpm -C medioo-web test Conversation Composer`. Expected: FAIL.
- [ ] **Step 3:** Implement. The thread scrolls to the selected card when one is selected, otherwise to the newest. Reuse `OpenAtNewest`, holding still while the NOW bar is shown.
- [ ] **Step 4:** Run. Expected: PASS.
- [ ] **Step 5:** Commit `feat(medioo-web): the conversation column — cards, sent photos, day separators, action chips`.

### Task 8: কথা ও কাগজ — home and the paper link, the panel, the NOW bar

**Files:**
- Create: `medioo-web/src/components/PaperPanel/{PaperPanel.tsx,PaperPanel.css,PaperPanel.test.tsx}`
- Modify: `medioo-web/src/components/safety/NowBanner/NowBanner.tsx` (`variant: "block" | "bar"`), `medioo-web/src/app/app/(signed-in)/home/page.tsx`, `medioo-web/src/app/app/(signed-in)/documents/[id]/page.tsx`

**Interfaces:**
- Consumes: Tasks 5–7, `PaperSummary`'s parts (`PaperParts.tsx`), and `NowBanner`.
- Produces:
  - **The routes.**
    - `/app/home?patient=&paper=&panel=1`: the viewer shows the paper named by `paper`, or the newest when there is none, and the conversation outlines its card. `panel=1` opens `PaperPanel` over the conversation; its close link drops `panel`.
    - `/app/documents/{id}` on the desktop layout renders the same screen, with that paper selected and the panel open. A WhatsApp link therefore lands on the paper with everything showing.
    - Below 900 px, both routes render today's pages, unchanged.
  - **The panel.** `PaperPanel({ lang, preview, paper: PaperResultJson, closeHref })` renders, in order: the NOW block (when `now`), the outside / check-on-paper / inside groups with crops and range bars, doctor's notes, medicines, next step, page links, and delete (owner only). It is today's paper page content minus its back link and header.
  - **The NOW bar.** `NowBanner variant="bar"` shows the patient's name (`patients[].name`), then `t(lang, line_key)` verbatim, then "{type} · {date}" of the newest NOW document, then the buttons. On the desktop layout the attention stack's urgent item is not rendered in any column (the card and the bar carry it).

- [ ] **Step 1: Write the failing tests:**
  - `aNowPaperFromALinkOpensWithItsPanelAndItsWarning` (Review Focus 1): `/app/documents/{proto-potas}` with the desktop layout shows the bar with "আব্বু" before Java's sentence, the red card with ৯৯৯, and the panel's NOW block. The warning sentence appears exactly twice on the page (the bar and the card; the panel's NOW block uses its own keys). Replay this on today's page: it fails on the missing bar and panel.
  - `theBarNamesThePatientFirstAndQuotesJavaVerbatim`.
  - `theBarHasNo999WhenCall999IsFalse` (abroad).
  - `panelCarriesEverySafetyBlockThePaperPageCarries`: derive the list of `data-safety` attributes from rendering today's `PaperSummary` on the same fixture, and assert the panel renders the same set. **Derived, not hand-listed**, and guarded against an empty set.
  - `homeWithoutPaperSelectsTheNewest`.
  - `belowNineHundredHomeIsTodaysChat` (the layout-off branch renders today's markup; snapshot by `data-page`).
- [ ] **Step 2:** Run `pnpm -C medioo-web test PaperPanel NowBanner home documents`. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run. Expected: PASS. Then run the full `pnpm -C medioo-web lint typecheck test build`.
- [ ] **Step 5:** Commit `feat(medioo-web): কথা ও কাগজ on desktop — paper beside the conversation, the details panel, the NOW bar (preview only)`.

### Task 9: End to end, the board, and the PO's try

**Files:**
- Modify: `medioo-web/e2e/support/scenarios.mjs` (the `proto-*` scenarios over Task 3's fixtures, `preview: true`), `medioo-web/e2e/chat.spec.ts`, `medioo-web/e2e/paper.spec.ts` (desktop-layout cases), `medioo-web/e2e/board.spec.ts` (Phase 1 scenes)

- [ ] **Step 1: Playwright at 1440×900, 1280×800, 1024×768 and 900×800,** light and dark, on the `proto` scenarios. Test:
  - axe clean;
  - no element overflows (Review Focus 4), including with `me/long-name.json` and the 45-paper timeline;
  - clicking a strip item changes the viewer and outlines the card;
  - ← → move between papers;
  - "সব দেখুন" opens and closes the panel;
  - the NOW bar's দেখেছি hides the bar only (the card stays red);
  - `budget.spec.ts` with the desktop layout on stays inside DR-42 (Review Focus 5).
- [ ] **Step 2:** Run `BOARD=1 pnpm -C medioo-web exec playwright test -c e2e/playwright.config.ts`. Publish the board privately, every Phase 1 capture beside its `desktop.html` scene (same family, same paper). Compare each pair by eye, and list every remaining difference in the PR body with its reason.
- [ ] **Step 3:** Run the per-PR pipeline of the rebuild plan (local gates, ignore check, independent review, PR). Merge when green. The gate keeps live users on today's layout.
- [ ] **Step 4: The PO's try.** On the deployed site, the PO opens `https://medioo.eliooo.org/app/preview/home` on a desktop browser, with the design open beside it. The PO's verdict is recorded in the build report. A "not yet" goes back to Task 5–8 as listed fixes; nothing flips.

### Task 10: The flip (only after the PO approves Phase 1)

- [ ] **Step 1:** Change the test `DESKTOP_DEFAULT is false` to `true` first, and watch it fail.
- [ ] **Step 2:** Set `DESKTOP_DEFAULT = true`. Run all gates.
- [ ] **Step 3:** Open a PR and merge when green. After deploy, run the https self-test table, then a desktop smoke of `/app/home` and one `/app/documents/{id}` link on production with the PO watching.
- [ ] **Step 4:** Record P19 and the flip in `docs/DECISIONS.md` as DR-46 ("desktop follows desktop.html; the board guard; the preview-first flip").

---

## Phase 2 — one screen at a time (each gets its own short plan when it starts)

Each screen follows the same path:
1. Words in the bundles.
2. Tests first.
3. The board against its `desktop.html` scene.
4. Merge behind the gate (`desktopLayout`).
5. The PO tries it in preview.
6. The flip.

New routes (`/app/labs`, `/app/medicines`) also need `FLIPPED`, `docs/infra/Caddyfile.medioo` and the box's Caddyfile (backup and validate first).

| Row | Screen | Done when |
|---|---|---|
| C1 (+C2 kept) | ক্যালেন্ডার | A full-width month grid with each day naming its papers by urgency, today filled, the follow-up day ringed, the chosen day's papers on the right with "কাগজ ও কথা খুলুন"; মাস / তালিকা; ‹ আজ ›. The filter chips and chronic-illness card stay, in the list view. |
| L1 | ল্যাবের মান (`/app/labs`) | Every value listed on the left; the chosen one on the right: big number, chip, last date and printed range, "সময়ের সাথে", "কাগজে যেমন লেখা" crop row linking to the paper. |
| M1 | ওষুধ (`/app/medicines`) | The latest prescription's header, each medicine as crop + typed line + "প্রেসক্রিপশনে যেমন লেখা, তেমনই" + "কাগজে দেখুন", the pharmacy line. Content from today's `MedicinesAsWritten`. |
| F1 (+F2 kept) | পরিবার ও অ্যাকাউন্ট | The settings page from the account row: people, illnesses as a tick list, reminders switch, account, theme (kept). Sharing and invite wait for Phase 3. |
| U1 | Sending a paper | The paperclip or a drop anywhere opens the small dialog: thumbnails, + more, একটাই রিপোর্ট / আলাদা আলাদা, the monthly count, বাদ দিন / পাঠান. The same upload API and reasons as today. |
| I1 | Sign-in | The design's two-pane page: headline, promise, sample card, waving Mio, trust line; the WhatsApp number with +880; the code flow per the 2026-10-18 login ruling. |
| S4 | Search (⌘K) | Searches the active patient's papers, values and medicines from data already loaded; no new Java search. The mic arrives with V1. |
| W1 | 1024 px | Covered by Phase 1's 900–1199 rule; checked again with each new screen. |

## Phase 3 — needs Java work and the PO's wording first

| Row | What | Why it waits |
|---|---|---|
| S3 | "কাউকে যোগ করুন" | The web has no add-a-person API; adding a person needs consent wording from the PO. |
| F1 (part) | "Who else sees" and invite a brother or sister | `FamilyJson` carries no share members; the invite flow needs its own small spec. |
| K8 (part) | "{name}কে জানাই" | Depends on the share members above. |
| V1 | Voice and sounds | Marked "later" by the PO. |

After Phase 2: the **mobile gap list**, marked by the PO the same way, then its plan.

## Amendment 2026-11-07 — the PO's first look at the preview

Tried on a 1710 px window; three changes, built on `feat/desktop-viewer-details`, still behind preview:

- **Fit stops at 100%.** Task 6 said fit sets the page to the canvas width; desktop.html's `fitZoom()` is
  `min(1, canvas / 640)`, so a wide window blew the page up to 149%. Fit is now the canvas width up to 640 px,
  centred beyond it (`paper-viewer-layout.spec.ts` checks both directions). The steps (25%, 50–300%) are unchanged.
- **The conversation follows the viewer** (desktop.html's `select()`): a change of paper (strip, arrows, keys, card)
  scrolls its card into view (`nearest`, smooth unless reduced motion) and pulses it once (`FollowPaper`). The NOW bar
  no longer holds the desktop column still: it lies across both columns and stays in sight; only a NOW card pinned in
  the column (PinnedUrgent) holds it, and Mio stays still while any NOW result shows. This closes board finding 10.
- **A divider between the paper and the conversation** (new; desktop.html has a fixed `--cw`): drag, arrow keys
  (Shift ×4), Home/End, Enter or double-click to reset; remembered per browser (`medioo.desk.talk-w`); the CSS clamps
  the conversation to ≥ 320 px and the paper to ≥ 420 px whatever was remembered. Copy key `desk.resize`;
  its Bangla is the PO's own wording (asked 2026-11-07; desktop.html has no divider): "কাগজ আর কথার মাপ বদলান".
- Detail: the strip's label is two fixed lines, the day then its F7 mark, never wrapped (full text kept).
- Detail: a card taller than the column (a NOW card with its crops) comes in at its top, below the sticky header, so
  its band and Java's sentence are in sight; centred, they sat under the header (`bringIntoView`).
- Detail: delete in the viewer's toolbar is desktop.html's trash icon, named `desk.viewer.delete`, so the toolbar
  keeps one row at 1024 px (board finding 9, in part: on a NOW or this-week paper the longer urgency word can still wrap a narrow toolbar).

## Phase 2, C1 — the calendar (2026-11-07)

Built on `feat/desktop-calendar`, behind preview (`/app/preview/timeline`), as desktop.html's `calView()`:

- **Data.** A paper on `GET …/calendar` is now the timeline's own entry for it (`TimelineJson.entry`: page one,
  facility, counts, status, chip, review, retake reason), so the day panel needs no other read and the calendar and the
  list can never describe a paper two ways. `PatientTimelineApiContractTest` asserts each calendar paper equals the
  timeline's entry field for field (derived, not a key list). The prototype family's October is now a fixture
  (`calendar/proto-*`, `timeline/proto-*`, `ProtoFamilyContractTest`).
- **Screen** (`DeskCalendar`, one column of the shell: no conversation, no divider). The toolbar: the month, মাস /
  তালিকা, ‹ আজ ›. The month: Saturday first, each day a link to itself (`?day=`) naming its papers by urgency (NOW,
  THIS_WEEK, the doctor's dates, the rest; three, then `+n`), the chosen day marked, a doctor's day ringed, the weeks
  sharing the window's height (≥ 96 px). The panel (340 px at every width, as desktop.html): each paper with its page,
  facility, stored level, counts line and "কাগজ ও কথা খুলুন" (the talk-and-papers screen on that paper); each doctor's
  date with the doctor's words quoted, their crop, and "কাগজটা দেখুন". তালিকা: the phone page's filters, list, "more" and
  the chronic-flags ask. The sidebar's next-visit card opens the calendar on its day.
- **Words.** Every `desk.cal.*` Bangla is desktop.html's own (`DesktopWordsTest`); Thursday is desktop.html's
  "বৃহস্পতি" in full (`desk.cal.thu`), the phone keeps its short form.
- **Not as desktop.html, on purpose:**
  - The doctor's-date card does not say "আগের দিন সন্ধ্যায় WhatsApp-এ মনে করিয়ে দেব।": the nudge engine reminds two
    days before, gated and capped (`NudgeRules.follow_up_due`), so the screen promises nothing it cannot keep. If the
    PO wants a reminder line, it needs wording that matches the engine.
  - Today's numeral is filled in the tint with its ink, not desktop.html's label-on-background inversion, which the
    token roles refuse (text in a surface token).
  - A day's paper says its type ("ল্যাব রিপোর্ট"), not the prototype's test name ("রক্ত পরীক্ষা"): the calendar has
    the stored type, not a title.
  - Every grey fill (the views, আজ, the quiet buttons, a day's event) carries the app's drawn boundary (Q1 (A)).
- **Safety colours.** The day events' NOW and THIS_WEEK marks join R2's calendar marks in `NOW_ALLOWED` /
  `WEEK_ALLOWED` and the TS class guard (`desk-cal__ev--now|week`, only in `src/components/DeskCalendar/`); each is
  drawn with its shape (octagon, calendar) and its paper's name, never the colour alone; the NOW name is in `--now-ink`
  (axe contrast at 12 px), the octagon in `--now`.
