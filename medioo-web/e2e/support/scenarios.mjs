import { readFileSync } from "node:fs";
import { join, resolve } from "node:path";

/*
 * What stub Java answers, per scenario (plan Wave 3, Shared interfaces → e2e support). A key is `METHOD pattern`
 * exactly as docs/infra/app-routes.txt spells the route. A value is:
 *   - a path under medioo-web/fixtures/api/ (a file Java's contract tests wrote: `{_meta, status, body}`);
 *   - a literal `{ status, body }`;
 *   - a list of those, answered in order per test (the last one repeats): the number step's first POST shows the
 *     number back, the confirmed one requests the code.
 * The same table answers Next's server-side reads (java-stub.mjs) and the browser's own /app/api calls
 * (browser-api.ts). A scenario whose name starts `signed-out` or `state-`, or whose `me` is 401, runs without a
 * session cookie. Each screen adds its own scenarios.
 */

// Set by playwright.config.ts for the workers and the stub (no `import.meta` here: Playwright loads this file into its
// CommonJS test workers, where `import.meta` does not parse); medioo-web/fixtures/api from medioo-web otherwise.
export const FIXTURES = resolve(process.env.E2E_FIXTURES ?? join("fixtures", "api"));

const UNAUTHENTICATED = { status: 401, body: { reason: "UNAUTHENTICATED" } };
/** A mutation Java answers 204 with no body (the active patient, sign-out); no contract fixture writes one. */
const NO_CONTENT = { status: 204, body: null };

/** A fixture with one field of its body removed (the state Java answers when no public WhatsApp number is set). */
function without(fixture, field) {
  const parsed = JSON.parse(readFileSync(join(FIXTURES, fixture), "utf8"));
  const body = { ...parsed.body };
  delete body[field];
  return { status: parsed.status, body };
}

/** The first patient's name in a `me` fixture, so a failing body can carry a name the log check then looks for. */
export function patientName(fixture) {
  return JSON.parse(readFileSync(join(FIXTURES, fixture), "utf8")).body.patients[0].name;
}

/** The number a `sign-in/request` confirm fixture shows back, for the same reason. */
export function numberShown(fixture) {
  return JSON.parse(readFileSync(join(FIXTURES, fixture), "utf8")).body.confirm.number_display;
}

/** The open sign-in routes every signed-out scenario answers (AppApiGuard.OPEN, W3-api). */
const SIGN_IN = {
  "POST /app/api/sign-in/request": ["sign-in/confirm.json", "sign-in/sent.json"],
  "POST /app/api/sign-in/verify": "sign-in/verified.json",
  "GET /app/api/sign-in/state": "sign-in/state-none.json",
};

/** A code page whose state is `state`, and whose verify answers `verify`. */
const codePage = (state, verify = "sign-in/verified.json") => ({
  ...SIGN_IN,
  "GET /app/api/me": "me/unauthenticated.json",
  "GET /app/api/sign-in/state": state,
  "POST /app/api/sign-in/verify": verify,
});

/** A fixture with one field of its body replaced (a share member's view of an owner's paper). */
function withField(fixture, field, value) {
  const parsed = JSON.parse(readFileSync(join(FIXTURES, fixture), "utf8"));
  return { status: parsed.status, body: { ...parsed.body, [field]: value } };
}

/** The paper every documents fixture is (PaperFixtures.DOC), and the value of the value-sheet fixtures (W6). */
export const PAPER_ID = "5e9edf6d-0a2a-4913-8675-9d07a4100003";
export const SHEET_OBS = "7af69ec6-676f-3bb9-b0bb-3e665c36ed57";

const PAPER = "GET /app/api/documents/{id}";
const STATUS = "GET /app/api/documents/{id}/status";
const SHEET = "GET /app/api/documents/{id}/values/{obs}";
const status = (s) => ({ status: 200, body: { status: s } });

/**
 * A paper page (W6) under `me`: the paper Java sent, the status the reading page polls, and the two delete steps as
 * Java answers them (B4c: a confirm token, then the report). No contract fixture writes a status, a token or a delete
 * report, so those are literals in Java's shape (PaperStatusApiHandler, PaperDeleteApiHandler).
 */
const paper = (fixture, extra = {}, me = "me/one-patient.json") => ({
  "GET /app/api/me": me,
  [PAPER]: fixture,
  [STATUS]: status("DONE"),
  "GET /app/api/documents/{id}/delete-token": {
    status: 200,
    body: { confirm_token: "e2e-confirm-token", expires_at: "2026-10-20T04:10:00Z" },
  },
  "DELETE /app/api/documents/{id}": { status: 200, body: { objects_deleted: 3, rows_deleted: 9 } },
  ...extra,
});

/** A value's sheet (W6-api) under `me`. */
const sheet = (fixture, me = "me/one-patient.json") => ({ "GET /app/api/me": me, [SHEET]: fixture });

export const SCENARIOS = {
  // The sign-in page (W3): nobody signed in.
  "signed-out": { ...SIGN_IN, "GET /app/api/me": "me/unauthenticated.json" },
  "signed-out-not-sent": {
    ...SIGN_IN,
    "GET /app/api/me": "me/unauthenticated.json",
    "POST /app/api/sign-in/request": ["sign-in/confirm.json", "sign-in/not-sent.json"],
  },
  "signed-out-invalid-after-confirm": {
    ...SIGN_IN,
    "GET /app/api/me": "me/unauthenticated.json",
    "POST /app/api/sign-in/request": ["sign-in/confirm.json", "sign-in/invalid-number.json"],
  },
  "signed-out-invalid": {
    ...SIGN_IN,
    "GET /app/api/me": "me/unauthenticated.json",
    "POST /app/api/sign-in/request": "sign-in/invalid-number.json",
  },
  // A signed-in visitor on the sign-in page: sent on to the place Java checked.
  "signed-in": { "GET /app/api/me": "me/one-patient.json", "GET /app/api/sign-in/next": "sign-in/next-held.json" },
  "signed-in-hostile": { "GET /app/api/me": "me/one-patient.json", "GET /app/api/sign-in/next": "sign-in/next-hostile.json" },
  // The code page, by the state Java read from its own login cookie.
  "state-pending-sent": codePage("sign-in/state-pending-sent.json"),
  "state-pending-not-sent": codePage("sign-in/state-pending-not-sent.json"),
  "state-pending-not-sent-no-link": codePage(without("sign-in/state-pending-not-sent.json", "wa_link")),
  "state-none": codePage("sign-in/state-none.json"),
  "state-code-wrong": codePage("sign-in/state-pending-sent.json", "sign-in/code-wrong.json"),
  "state-code-expired": codePage("sign-in/state-pending-sent.json", "sign-in/code-expired.json"),
  "state-code-burned": codePage("sign-in/state-pending-sent.json", "sign-in/code-burned.json"),
  "state-code-format": codePage("sign-in/state-pending-sent.json", "sign-in/code-format.json"),
  "state-restart": codePage("sign-in/state-pending-sent.json", "sign-in/restart.json"),
  // A preview family signed in (BAYMAX_WEB_PREVIEW_FAMILIES), and one who is not: the preview gate reads `me` first.
  "preview-signed-in": {
    ...SIGN_IN,
    "GET /app/api/me": "me/preview-on.json",
    "GET /app/api/sign-in/next": "sign-in/next-held.json",
  },
  "preview-state-none": {
    ...SIGN_IN,
    "GET /app/api/me": "me/preview-on.json",
    "GET /app/api/sign-in/state": "sign-in/state-none.json",
  },
  // A preview family on the preview code page whose verify answers a place Next serves (a literal: Java's fixture
  // holds a paper, which Next does not serve yet, so it could not show the preview kept).
  "preview-verify-to-sign-in": {
    ...SIGN_IN,
    "GET /app/api/me": "me/preview-on.json",
    "GET /app/api/sign-in/state": "sign-in/state-pending-sent.json",
    "POST /app/api/sign-in/verify": { status: 200, body: { next: "/app/" } },
    "GET /app/api/sign-in/next": "sign-in/next-held.json",
  },
  "preview-off": { ...SIGN_IN, "GET /app/api/me": "me/preview-off.json", "GET /app/api/sign-in/next": "sign-in/next-held.json" },
  // Java failing, for the server-log check: `me` is a 502 (read by the preview gate), and the held-place read is a 500
  // whose body carries a fixture's name and number, which must never reach the Next server log.
  "java-502": { ...SIGN_IN, "GET /app/api/me": { status: 502, body: "bad gateway" } },
  // The shell (W4), on its catalog-only page /app/shell-test: one patient, two and no choice, none, the longest name,
  // and a family outside the preview. Choosing a patient is Java's 204 (MeApiHandler.setActivePatient sets the cookie).
  "me-one": { "GET /app/api/me": "me/one-patient.json", "PUT /app/api/active-patient": NO_CONTENT },
  "me-two": { "GET /app/api/me": "me/two-patients.json", "PUT /app/api/active-patient": NO_CONTENT },
  "me-none": { "GET /app/api/me": "me/no-patients.json" },
  "me-long": { "GET /app/api/me": "me/long-name.json", "PUT /app/api/active-patient": NO_CONTENT },
  "me-preview-off": { "GET /app/api/me": "me/preview-off.json" },
  // The session ended while the page was open: the switcher's PUT is a 401, and so is the sign-in page's held-place
  // read when the browser lands there.
  "me-two-put-401": {
    ...SIGN_IN,
    "GET /app/api/me": "me/two-patients.json",
    "PUT /app/api/active-patient": UNAUTHENTICATED,
    "GET /app/api/sign-in/next": UNAUTHENTICATED,
  },
  // Signing out: Java's 204 (SignInApiHandler.signOut), then the sign-in page, where the session is gone.
  "me-one-sign-out": {
    ...SIGN_IN,
    "GET /app/api/me": "me/one-patient.json",
    "POST /app/api/sign-out": NO_CONTENT,
    "GET /app/api/sign-in/next": UNAUTHENTICATED,
  },
  "next-500": {
    "GET /app/api/me": "me/one-patient.json",
    "GET /app/api/sign-in/next": {
      status: 500,
      body: { error: `failed for ${patientName("me/one-patient.json")} at ${numberShown("sign-in/confirm.json")}` },
    },
  },

  // The paper (W6), one scenario per state the page draws, each over the fixture Java's contract test wrote.
  "paper-now-bd": paper("documents/now-bd.json"),
  "paper-now-abroad": paper("documents/now-abroad.json"),
  "paper-routine": paper("documents/routine-released.json"),
  "paper-pending": paper("documents/this-week-pending.json"),
  "paper-withheld-rejected": paper("documents/withheld-rejected.json"),
  "paper-failed-safety": paper("documents/failed-safety.json"),
  "paper-retake-third": paper("documents/retake-third.json"),
  "paper-failed": paper("documents/failed.json"),
  "paper-58": paper("documents/fifty-eight-values-multipage.json"),
  "paper-unassessed": paper("documents/unassessed.json"),
  // A paper still being read, whose status Java still says is being read; then the same paper once read (the test
  // switches the scenario cookie between them, as the paper finishing between two polls does).
  "paper-processing": paper("documents/processing.json", { [STATUS]: status("PROCESSING") }),
  "paper-processing-done": paper("documents/routine-released.json", { [STATUS]: status("DONE") }),
  // The longest twins (LONGEST): the longest name in the shell, over the longest paper.
  "paper-58-long": paper("documents/fifty-eight-values-multipage.json", {}, "me/long-name.json"),
  // A share member on the owner's paper: Java's `owner: false`, everything else the same body.
  "paper-retake-share": paper(withField("documents/retake-third.json", "owner", false)),
  "paper-routine-share": paper(withField("documents/routine-released.json", "owner", false)),
  // Gone: deleted (by this family or another member) before the page was opened, or raced by another member's delete.
  "paper-gone": paper("documents/not-found.json"),
  "paper-delete-raced": paper("documents/routine-released.json", {
    "DELETE /app/api/documents/{id}": { status: 404, body: { reason: "NOT_FOUND" } },
  }),
  // The moment after this family's own delete: the paper Java no longer shows (switched to by the delete test).
  "paper-deleted": paper("documents/not-found.json"),
  // A crop whose session ended: the image route sends the browser to sign-in (303), and the status read is a 401.
  "paper-crop-signed-out": paper("documents/routine-released.json", {
    [STATUS]: UNAUTHENTICATED,
    "GET /app/api/sign-in/next": UNAUTHENTICATED,
    ...SIGN_IN,
  }),
  // A family outside the preview on a preview paper: the gate reads `me` and stops before the paper.
  "paper-preview-off": paper("documents/now-bd.json", {}, "me/preview-off.json"),
  // Nobody signed in on a paper link, without JavaScript (Review Focus 2): the paper's 307, then the sign-in page.
  "signed-out-paper": { ...SIGN_IN, "GET /app/api/me": "me/unauthenticated.json", [PAPER]: "documents/now-bd.json" },
  // One value's sheet, by what Java says about its history (R1).
  "sheet-earlier-three": sheet("value-sheet/earlier-three.json"),
  "sheet-earlier-none": sheet("value-sheet/earlier-none.json"),
  "sheet-no-history": sheet("value-sheet/no-history.json"),
  "sheet-earlier-other-unit": sheet("value-sheet/earlier-other-unit.json"),
  "sheet-unknown": sheet("value-sheet/unknown-one-sided.json"),
  "sheet-not-found": sheet("value-sheet/not-found.json"),
  "sheet-earlier-three-long": sheet("value-sheet/earlier-three.json", "me/long-name.json"),
};

export { UNAUTHENTICATED };

/** The scenario each page of NEXT_PAGES is measured under by budget.spec.ts (it fails for a page missing here). */
export const PAGE_SCENARIOS = {
  "/app": "signed-out",
  "/app/verify": "state-pending-not-sent",
  // W6: the NOW paper (the summary's heaviest state), the longest paper's /all, and a sheet with three earlier readings.
  "/app/documents/{id}": "paper-now-bd",
  "/app/documents/{id}/all": "paper-58",
  "/app/documents/{id}/values/{obs}": "sheet-earlier-three",
};

/** The address each dynamic NEXT_PAGES entry is measured at: the ids its scenario's fixtures are about. */
export const PAGE_PATHS = {
  "/app/documents/{id}": `/app/documents/${PAPER_ID}`,
  "/app/documents/{id}/all": `/app/documents/${PAPER_ID}/all`,
  "/app/documents/{id}/values/{obs}": `/app/documents/${PAPER_ID}/values/${SHEET_OBS}`,
};

/**
 * What only the page itself renders, per NEXT_PAGES entry: budget.spec.ts requires it in the measured document, so a
 * 200 error page, or a page that rendered something else, cannot be measured in its place and pass the ceiling.
 */
export const PAGE_MARKERS = {
  "/app": 'name="whatsapp_number"',
  "/app/verify": 'name="code"',
  "/app/documents/{id}": "data-now-block",
  "/app/documents/{id}/all": 'id="values"',
  "/app/documents/{id}/values/{obs}": 'id="value-sheet-title"',
};

/** Each routed scenario's longest twin (`me/long-name.json` and the area's longest fixture), per screen. */
export const LONGEST = {
  // The shell (W4): its longest is the longest name (fixtures/api/me/long-name.json) in the toolbar and the sidebar.
  "me-one": "me-long",
  // The paper (W6): the longest name over the longest paper (fifty-eight values on three pages), for the summary and
  // for /all; and the longest name over the sheet with the most earlier readings.
  "paper-now-bd": "paper-58-long",
  "paper-58": "paper-58-long",
  "sheet-earlier-three": "sheet-earlier-three-long",
};

/** The `{status, body}` a scenario value answers first: a fixture file read, a literal as it is. */
function firstAnswer(value) {
  const v = Array.isArray(value) ? value[0] : value;
  if (typeof v !== "string") return v ?? null;
  const fixture = JSON.parse(readFileSync(join(FIXTURES, v), "utf8"));
  return { status: fixture.status ?? 200, body: fixture.body };
}

/**
 * The long name a longest twin must render, read from the twin's own `me` answer (`patients[0].name`), never passed by
 * the caller: the overflow check then judges the strings that scenario actually serves. Throws when the twin has no
 * `me` with a named patient, so a twin that cannot carry a long name fails instead of being judged on short strings.
 */
export function longNameOf(name, table = SCENARIOS[name]) {
  const me = table?.["GET /app/api/me"];
  const first = me === undefined ? undefined : firstAnswer(me)?.body?.patients?.[0]?.name;
  if (typeof first !== "string" || first.trim() === "") throw new Error(`${name}: its me answer names no patient`);
  return first;
}

/**
 * Whether a scenario runs without the session cookie: named `signed-out*` or `state-*`, or its `me` answers 401 —
 * judged on the answer (a fixture's status or a literal's), never on a file name.
 */
export function signedOut(name, table = SCENARIOS[name]) {
  if (name.startsWith("signed-out") || name.startsWith("state-")) return true;
  const me = table?.["GET /app/api/me"];
  return me !== undefined && firstAnswer(me)?.status === 401;
}
