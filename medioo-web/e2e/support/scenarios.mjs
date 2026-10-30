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

/**
 * A fixture a helper derives a literal answer from, parsed, once its `_meta.source` says Java wrote it for `key`: the
 * rule scenarios.test.ts's fixtureProblem applies to every fixture a scenario names, which never sees a literal. A
 * fixture regenerated for another route, or renamed, is refused here instead of being served under this one.
 */
function javaWrote(fixture, key) {
  const parsed = JSON.parse(readFileSync(join(FIXTURES, fixture), "utf8"));
  const source = parsed?._meta?.source;
  if (typeof source !== "string" || !source.startsWith(`${key} `)) {
    throw new Error(`${fixture} was written for "${source}", not "${key}"`);
  }
  return parsed;
}

/**
 * A NOW banner fixture whose papers belong to the `index`-th patient of a `me` fixture. The two bodies come from two
 * contract tests that each mint their own patient ids, so on their own the banner names a patient `me` does not list
 * (its title then falls back to the attention strip's words, as it must for a stale `me`). Java answers both from one
 * database, where the ids agree; only the ids are changed here, never a word or a flag.
 */
export function bannerFor(fixture, meFixture, index = 0) {
  const banner = javaWrote(fixture, "GET /app/api/now-banner");
  const me = javaWrote(meFixture, "GET /app/api/me");
  const patient = me.body.patients[index].id;
  return {
    status: banner.status,
    body: { ...banner.body, documents: banner.body.documents.map((d) => ({ ...d, patient_id: patient })) },
  };
}

/**
 * The answer to PUT /app/api/now-banner/ack: NowBannerApiHandler.ack answers 200 with the banner re-read, the body GET
 * /app/api/now-banner sends. No contract test writes a fixture for the PUT, so the GET's body is the literal answer:
 * the fixture's own, or, with `meFixture`, bannerFor's (its papers the `me` patient's).
 */
export function reRead(fixture, meFixture) {
  const body = meFixture === undefined ? javaWrote(fixture, "GET /app/api/now-banner").body : bannerFor(fixture, meFixture).body;
  return { status: 200, body };
}

/**
 * A thread fixture with its paper cards left out: a page of delivered messages only, as Java answers for a patient whose
 * papers are all older than the thread's first page. Only items are dropped, never a word changed.
 */
export function messagesOnly(fixture) {
  const parsed = javaWrote(fixture, "GET /app/api/patients/{id}/thread");
  return { status: parsed.status, body: { ...parsed.body, items: parsed.body.items.filter((i) => i.kind === "message") } };
}
/** The `next_cursor` a timeline fixture hands its "more" link, so the next page is served for exactly that cursor. */
export function cursorOf(fixture) {
  const cursor = JSON.parse(readFileSync(join(FIXTURES, fixture), "utf8")).body.next_cursor;
  if (typeof cursor !== "string") throw new Error(`${fixture} has no next_cursor`);
  return cursor;
}

/**
 * The papers page's four reads (plan W8): the timeline for its query (`?type=lab`, `?type=rx`, the first page's
 * cursor), the calendar for its month (September for the previous-month link; Java's own month otherwise), the
 * doctor's dates, and the attention stack; and, for a page with a paper being read, the status its quiet check polls
 * (PO ruling P6, `GET /app/api/documents/{id}/status`, answered to the browser from Java's own fixture).
 */
const papers = ({
  me = "me/one-patient.json",
  timeline,
  cursor,
  calendar,
  follows = "follow-ups/some.json",
  attention = "attention/now-only.json",
  rx = "timeline/rx-only.json",
  status,
}) => ({
  ...NO_NOW,
  "GET /app/api/me": me,
  "GET /app/api/patients/{id}/timeline": {
    byQuery: [
      { when: { type: "lab" }, answer: "timeline/lab-only.json" },
      { when: { type: "rx" }, answer: rx },
      ...(cursor ? [{ when: { cursor }, answer: "timeline/last-page.json" }] : []),
    ],
    otherwise: timeline,
  },
  "GET /app/api/patients/{id}/calendar": {
    byQuery: [{ when: { month: "2026-09" }, answer: "calendar/september.json" }],
    otherwise: calendar,
  },
  "GET /app/api/patients/{id}/follow-ups": follows,
  "GET /app/api/patients/{id}/attention": attention,
  ...(status ? { "GET /app/api/documents/{id}/status": status } : {}),
});

/**
 * The status poll answered per paper (`GET /app/api/documents/{id}/status`): the fixture `byId` names for the paper the
 * path asks about, `otherwise` for any other. Each is a file Java's contract test wrote for that route, never a body
 * written here.
 */
function statusById(byId, otherwise) {
  const route = "GET /app/api/documents/{id}/status";
  const read = (fixture) => {
    const parsed = javaWrote(fixture, route);
    return { status: parsed.status, body: parsed.body };
  };
  for (const fixture of [...Object.values(byId), otherwise]) read(fixture);
  return ({ path }) => read(byId[path.split("/")[4] ?? ""] ?? otherwise);
}

/** The one paper of `status` a timeline fixture lists: the paper a scenario answers its own status for. */
function readingIdOf(fixture, status) {
  const ids = bodyOf(fixture).entries.filter((e) => e.status === status).map((e) => e.document_id);
  if (ids.length !== 1) throw new Error(`${fixture} lists ${ids.length} papers ${status}, not one`);
  return ids[0];
}

/** A fixture's body, as Java's contract test wrote it. */
function bodyOf(fixture) {
  return JSON.parse(readFileSync(join(FIXTURES, fixture), "utf8")).body;
}

/** The id of patient `index` in a GET /app/api/family fixture: the profile page is loaded at it (PAGE_PATHS). */
export function familyPatientId(fixture, index = 0) {
  return bodyOf(fixture).patients[index].id;
}

/** The patient a `/app/api/patients/{id}/…` path names, from a family fixture, or undefined. */
function patientAt(fixture, path) {
  const id = path.split("/")[4];
  return bodyOf(fixture).patients.find((p) => p.id === id);
}

const NOT_FOUND = { status: 404, body: { reason: "NOT_FOUND" } };

/**
 * What Java's flags PUT answers (FamilyApiHandler: the saved patient, as GET /app/api/family lists it), derived from the
 * request, never typed here: the patient the path names in the family fixture (404 for one it does not list, as Java
 * answers), whose `chronic_flags` are the list sent, stored as FamilyAccountService.normalizeFlags stores it (trimmed,
 * lower-cased, blanks and repeats dropped) and each named by its offered key or by none (a typed flag).
 */
export function flagsSaved(fixture) {
  return ({ path, body }) => {
    const patient = patientAt(fixture, path);
    if (patient === undefined) return NOT_FOUND;
    const keys = new Map(bodyOf(fixture).flags_offered.map((o) => [o.id, o.label_key]));
    const sent = Array.isArray(body?.chronic_flags) ? body.chronic_flags : [];
    const values = [...new Set(sent.filter((v) => typeof v === "string" && v.trim() !== "").map((v) => v.trim().toLowerCase()))];
    return { status: 200, body: { ...patient, chronic_flags: values.map((value) => ({ value, label_key: keys.get(value) ?? null })) } };
  };
}

/** What Java's per-patient stop answers: the patient the path names, stopped (404 for one the fixture does not list). */
function patientStopped(fixture) {
  return ({ path }) => {
    const patient = patientAt(fixture, path);
    return patient === undefined ? NOT_FOUND : { status: 200, body: { ...patient, reminders: "stopped_for_patient" } };
  };
}

/** What Java's family stop answers: the whole family body, the stop set and every patient stopped for the family. */
function familyStopped(fixture) {
  const body = bodyOf(fixture);
  return {
    status: 200,
    body: {
      ...body,
      account: { ...body.account, reminders_stopped: true },
      patients: body.patients.map((p) => ({ ...p, reminders: "stopped_for_family" })),
    },
  };
}

const VIEW_ONLY = { status: 403, body: { reason: "VIEW_ONLY" } };

/** The family tab's writes (W10) as Java answers an owner: B5's routes, W10-api's preference, W3-api's sign-out. */
const familyWrites = (fixture) => ({
  "PUT /app/api/patients/{id}/flags": flagsSaved(fixture),
  "PUT /app/api/patients/{id}/reminders": patientStopped(fixture),
  "PUT /app/api/family/reminders": familyStopped(fixture),
  "PUT /app/api/language": NO_CONTENT,
  "PUT /app/api/preferences": NO_CONTENT,
  "POST /app/api/sign-out": NO_CONTENT,
  // After signing out, the sign-in page asks where to go and Java says there is no session.
  "GET /app/api/sign-in/next": UNAUTHENTICATED,
  "GET /app/api/sign-in/state": "sign-in/state-none.json",
});

/** The open sign-in routes every signed-out scenario answers (AppApiGuard.OPEN, W3-api). */
const SIGN_IN = {
  "POST /app/api/sign-in/request": ["sign-in/confirm.json", "sign-in/sent.json"],
  "POST /app/api/sign-in/verify": "sign-in/verified.json",
  "GET /app/api/sign-in/state": "sign-in/state-none.json",
};

/** The NOW banner every signed-in page reads (W5), when the family has nothing unseen: show false. */
const NO_NOW = { "GET /app/api/now-banner": "now-banner/acked.json" };

/**
 * The chat page (W5): one patient (me/one-patient.json), no unseen NOW, an empty attention stack and the default thread, with
 * `over` replacing any of them.
 */
const chat = (over) => ({
  ...NO_NOW,
  "GET /app/api/me": "me/one-patient.json",
  "PUT /app/api/active-patient": NO_CONTENT,
  "GET /app/api/patients/{id}/attention": "attention/empty.json",
  "GET /app/api/patients/{id}/thread": "thread/default.json",
  ...over,
});

/** A code page whose state is `state`, and whose verify answers `verify`. */
const codePage = (state, verify = "sign-in/verified.json") => ({
  ...SIGN_IN,
  "GET /app/api/me": "me/unauthenticated.json",
  "GET /app/api/sign-in/state": state,
  "POST /app/api/sign-in/verify": verify,
});

/** The add sheet (W7): `me`, whom the viewer may send for, and what a press is answered. */
const addSheet = (options, uploads = "uploads/one-document.json", me = "me/one-patient.json") => ({
  ...NO_NOW,
  "GET /app/api/me": me,
  "GET /app/api/upload-options": `upload-options/${options}.json`,
  "POST /app/api/uploads": uploads,
});

/**
 * Every refusal of a press, as Java answers it (fixtures/api/uploads/refusals.json, which UploadApiContractTest writes
 * from the upload path's own source): one `upload-refused-<NAME>` scenario each, so the sheet's copy is checked for
 * every name Java can send and for no name it cannot.
 */
const REFUSALS = JSON.parse(readFileSync(join(FIXTURES, "uploads", "refusals.json"), "utf8")).body;
export const UPLOAD_REFUSED = Object.fromEntries(
  Object.entries(REFUSALS).map(([name, answer]) => [`upload-refused-${name}`, addSheet("owner-one", answer)]),
);

/**
 * The health tab (W9) on /app/health: one patient, no unseen NOW, and the labs and medicines bodies HealthApiContractTest
 * wrote (fixtures/api/health/), by case name.
 */
const health = (labs, medicines = "medicines-latest", me = "me/one-patient.json") => ({
  ...NO_NOW,
  "GET /app/api/me": me,
  "PUT /app/api/active-patient": NO_CONTENT,
  "GET /app/api/patients/{id}/labs": `health/${labs}.json`,
  "GET /app/api/patients/{id}/medicines": `health/${medicines}.json`,
});

/** The network dies under the request (browser-api.ts aborts it after the guard, java-stub.mjs drops the socket). */
const CUT = { abort: "connectionreset" };
/** The network goes quiet under the request: never answered, never reset (browser-api.ts leaves the route open). */
const HOLD = { hold: true };
/** Caddy's answer when Java is too slow: a 504 page, no Java reason (Java may still have stored the press). */
const GATEWAY_504 = { status: 504, body: "<html><body><h1>504 Gateway Timeout</h1></body></html>" };

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
  ...NO_NOW,
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

/**
 * A paper fixture about another patient (W6's `patient`, for PO sight): the same body with its `patient` naming `id`,
 * and that patient's names as the `me` fixture lists them, or the fixture's own names for a patient `me` does not list.
 */
function paperAbout(fixture, id, meFixture) {
  const parsed = JSON.parse(readFileSync(join(FIXTURES, fixture), "utf8"));
  const listed = meFixture === undefined ? undefined : bodyOf(meFixture).patients.find((p) => p.id === id);
  const own = parsed.body.patient;
  const name = listed?.name ?? own.name;
  // Java's own suffixes on the fixture's names, kept, with the name swapped in.
  const patient = { ...own, id, name, name_objective: own.name_objective.replace(own.name, name), name_possessive: own.name_possessive.replace(own.name, name) };
  return { status: parsed.status, body: { ...parsed.body, patient } };
}

/** A patient no `me` fixture lists: a paper about them is one this family does not see. */
export const UNLISTED_PATIENT = "5e9edf6d-0a2a-4913-8675-9d07a4100999";

/** A value's sheet (W6-api) under `me`. */
const sheet = (fixture, me = "me/one-patient.json") => ({ ...NO_NOW, "GET /app/api/me": me, [SHEET]: fixture });

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
  // Every signed-in page reads the NOW banner too (W5); here it is the one the family already saw (show: false).
  "me-one": { ...NO_NOW, "GET /app/api/me": "me/one-patient.json", "PUT /app/api/active-patient": NO_CONTENT },
  "me-two": { ...NO_NOW, "GET /app/api/me": "me/two-patients.json", "PUT /app/api/active-patient": NO_CONTENT },
  "me-none": { ...NO_NOW, "GET /app/api/me": "me/no-patients.json" },
  "me-long": { ...NO_NOW, "GET /app/api/me": "me/long-name.json", "PUT /app/api/active-patient": NO_CONTENT },
  "me-preview-off": { ...NO_NOW, "GET /app/api/me": "me/preview-off.json" },
  // The session ended while the page was open: the switcher's PUT is a 401, and so is the sign-in page's held-place
  // read when the browser lands there.
  "me-two-put-401": {
    ...SIGN_IN,
    ...NO_NOW,
    "GET /app/api/me": "me/two-patients.json",
    "PUT /app/api/active-patient": UNAUTHENTICATED,
    "GET /app/api/sign-in/next": UNAUTHENTICATED,
  },
  // Signing out: Java's 204 (SignInApiHandler.signOut), then the sign-in page, where the session is gone.
  "me-one-sign-out": {
    ...SIGN_IN,
    ...NO_NOW,
    "GET /app/api/me": "me/one-patient.json",
    "POST /app/api/sign-out": NO_CONTENT,
    "GET /app/api/sign-in/next": UNAUTHENTICATED,
  },
  // The chat page (W5), /app/home: the thread, the attention stack and the NOW banner, each a body Java's contract tests wrote.
  "chat-default": chat({}),
  "chat-stack": chat({ "GET /app/api/patients/{id}/attention": "attention/full-stack.json" }),
  "chat-now": chat({
    "GET /app/api/now-banner": bannerFor("now-banner/one.json", "me/one-patient.json"),
    "GET /app/api/patients/{id}/attention": "attention/now-only.json",
    "GET /app/api/patients/{id}/thread": "thread/now.json",
  }),
  "chat-now-abroad": chat({
    "GET /app/api/now-banner": bannerFor("now-banner/abroad.json", "me/one-patient.json"),
    "GET /app/api/patients/{id}/attention": "attention/now-only.json",
    "GET /app/api/patients/{id}/thread": "thread/now-abroad.json",
  }),
  // The thread's own NOW card for a viewer abroad (S13), with no banner on the page to carry a tel: link of its own.
  "chat-thread-abroad": chat({ "GET /app/api/patients/{id}/thread": "thread/now-abroad.json" }),
  "chat-retake": chat({ "GET /app/api/patients/{id}/thread": "thread/retake.json" }),
  "chat-failed": chat({ "GET /app/api/patients/{id}/thread": "thread/failed.json" }),
  "chat-first": chat({ "GET /app/api/patients/{id}/thread": "thread/first-run.json" }),
  // A first-run patient while the family has a NOW paper for another patient: the banner is global, so Mio's
  // introduction is on a NOW surface too (opened with ?patient= the second patient; the banner names Ma).
  "chat-first-now": chat({
    "GET /app/api/me": "me/two-patients.json",
    "GET /app/api/now-banner": bannerFor("now-banner/one.json", "me/two-patients.json"),
    "GET /app/api/patients/{id}/thread": "thread/first-run.json",
  }),
  // A NOW result the thread's first page does not reach: only messages there, the NOW paper in the attention stack.
  "chat-now-past-the-page": chat({
    "GET /app/api/patients/{id}/attention": "attention/now-only.json",
    "GET /app/api/patients/{id}/thread": messagesOnly("thread/default.json"),
  }),
  // A share member: Ma is the owner's, the second patient is shared with this account (owner: false).
  "chat-share": chat({
    "GET /app/api/me": "me/two-patients.json",
    "GET /app/api/patients/{id}/thread": "thread/share-member.json",
  }),
  "chat-share-first": chat({
    "GET /app/api/me": "me/two-patients.json",
    "GET /app/api/patients/{id}/thread": "thread/first-run.json",
  }),
  // The longest twins (LONGEST): the longest name, and the thread's longest real page.
  "chat-long": chat({ "GET /app/api/me": "me/long-name.json", "GET /app/api/patients/{id}/thread": "thread/second-page.json" }),
  // chat-now's twin keeps chat-now's thread: its crop check counts that thread's value rows on the twin too.
  "chat-now-long": chat({
    "GET /app/api/me": "me/long-name.json",
    "GET /app/api/now-banner": bannerFor("now-banner/one.json", "me/long-name.json"),
    "GET /app/api/patients/{id}/attention": "attention/now-only.json",
    "GET /app/api/patients/{id}/thread": "thread/now.json",
  }),
  // the seen button: Java's answer to the acknowledgement is the banner re-read (hidden, still shown, or a 401). Ma is urgent in
  // me/two-patients, so her red mark is in the switcher.
  "chat-now-ack": chat({
    "GET /app/api/me": "me/two-patients.json",
    "GET /app/api/now-banner": bannerFor("now-banner/one.json", "me/two-patients.json"),
    "PUT /app/api/now-banner/ack": reRead("now-banner/acked.json"),
    "GET /app/api/patients/{id}/thread": "thread/now.json",
  }),
  "chat-now-ack-stale": chat({
    "GET /app/api/me": "me/two-patients.json",
    "GET /app/api/now-banner": bannerFor("now-banner/one.json", "me/two-patients.json"),
    "PUT /app/api/now-banner/ack": reRead("now-banner/one.json", "me/two-patients.json"),
    "GET /app/api/patients/{id}/thread": "thread/now.json",
  }),
  "chat-now-ack-401": chat({
    ...SIGN_IN,
    "GET /app/api/now-banner": bannerFor("now-banner/one.json", "me/one-patient.json"),
    "PUT /app/api/now-banner/ack": UNAUTHENTICATED,
    "GET /app/api/sign-in/next": UNAUTHENTICATED,
    "GET /app/api/patients/{id}/thread": "thread/now.json",
  }),
  // A preview family (BAYMAX_WEB_PREVIEW_FAMILIES) on /app/preview/home, before and after a NOW paper arrived.
  "chat-preview": chat({ "GET /app/api/me": "me/preview-on.json" }),
  "chat-preview-now": chat({
    "GET /app/api/me": "me/preview-on.json",
    "GET /app/api/now-banner": bannerFor("now-banner/one.json", "me/preview-on.json"),
  }),
  // family (W10): each scenario's `me` is the one FamilyApiContractTest read from the same seeded family as its family
  // body (me/family-*.json), so the toolbar never names a patient the tab does not list (FAMILY_PAIRS, checked in
  // scenarios.test.ts). The owner (Ma remembered), the share member (every write refused VIEW_ONLY, as Java refuses a
  // forced one), an owner whose save Java refuses VIEW_ONLY (the share withdrawn while the page was open), a family
  // whose reminders are stopped, two patients and no choice (the tab is about everyone: no chooser), and the longest
  // twins (the longest name on the tab's own cards, owned and shared, with the longest typed flag).
  "family-owner": {
    ...NO_NOW,
    "GET /app/api/me": "me/family-owner.json",
    "GET /app/api/family": "family/owner.json",
    ...familyWrites("family/owner.json"),
  },
  "family-owner-view-only": {
    ...NO_NOW,
    "GET /app/api/me": "me/family-owner.json",
    "GET /app/api/family": "family/owner.json",
    ...familyWrites("family/owner.json"),
    "PUT /app/api/patients/{id}/flags": VIEW_ONLY,
  },
  "family-owner-long": {
    ...NO_NOW,
    "GET /app/api/me": "me/family-long-name.json",
    "GET /app/api/family": "family/long-name.json",
    ...familyWrites("family/long-name.json"),
  },
  "family-share-member": {
    ...NO_NOW,
    "GET /app/api/me": "me/family-share-member.json",
    "GET /app/api/family": "family/share-member.json",
    ...familyWrites("family/share-member.json"),
    "PUT /app/api/patients/{id}/flags": VIEW_ONLY,
    "PUT /app/api/patients/{id}/reminders": VIEW_ONLY,
  },
  "family-share-member-long": {
    ...NO_NOW,
    "GET /app/api/me": "me/family-long-name-shared.json",
    "GET /app/api/family": "family/long-name-shared.json",
    ...familyWrites("family/long-name-shared.json"),
    "PUT /app/api/patients/{id}/flags": VIEW_ONLY,
    "PUT /app/api/patients/{id}/reminders": VIEW_ONLY,
  },
  "family-reminders-off": {
    ...NO_NOW,
    "GET /app/api/me": "me/family-reminders-off.json",
    "GET /app/api/family": "family/reminders-off.json",
    ...familyWrites("family/reminders-off.json"),
  },
  "family-two-no-choice": {
    ...NO_NOW,
    "GET /app/api/me": "me/family-owner-no-choice.json",
    "GET /app/api/family": "family/owner.json",
    ...familyWrites("family/owner.json"),
  },
  "next-500": {
    "GET /app/api/me": "me/one-patient.json",
    "GET /app/api/sign-in/next": {
      status: 500,
      body: { error: `failed for ${patientName("me/one-patient.json")} at ${numberShown("sign-in/confirm.json")}` },
    },
  },
  // The add sheet (W7) on /app/add. Each `me` is the family the options are of (UploadApiContractTest uses
  // MeApiContractTest's patients): one patient, two the family owns, Ma's plus a shared one only to see, the cap
  // reached, and the longest name.
  "upload-owner-one": addSheet("owner-one"),
  "upload-owner-two": addSheet("owner-two", "uploads/one-document.json", "me/two-owned.json"),
  // owner-two with only Ma's name lengthened, in `me` and in the options: the long name inside the two-chip chooser.
  "upload-owner-two-long": addSheet("long-name", "uploads/one-document.json", "me/two-owned-long.json"),
  "upload-view-only": addSheet("view-only", "uploads/one-document.json", "me/two-patients.json"),
  "upload-cap-reached": addSheet("cap-reached"),
  "upload-separate": addSheet("owner-one", "uploads/separate.json"),
  // The server turned downscaling on (limits.downscale_max_edge 800): the sheet scales a big photo before it goes.
  "upload-downscale": addSheet("downscale-on"),
  // The first press dies on the wire and its retry is answered; one that never gets through (the stuck state).
  "upload-retry": addSheet("owner-one", [CUT, "uploads/one-document.json"]),
  "upload-stuck": addSheet("owner-one", CUT),
  // The network goes quiet under the first press (no RST, no error, no answer); the retry is answered.
  "upload-silent-then-sent": addSheet("owner-one", [HOLD, "uploads/one-document.json"]),
  "upload-silent": addSheet("owner-one", HOLD),
  // A gateway's 504 with no Java reason (Java may have stored the press), then the retry is answered.
  "upload-504-then-sent": addSheet("owner-one", [GATEWAY_504, "uploads/one-document.json"]),
  // A press Java refused by name (the key was used before), then the same pages pressed again.
  "upload-reused-then-sent": addSheet("owner-one", [REFUSALS.UPLOAD_KEY_REUSED, "uploads/one-document.json"]),
  // A sheet whose every press is refused, opened for Ma from a retake link (Java chose her: owner-two-asked).
  "upload-owner-two-asked-refused": addSheet("owner-two-asked", REFUSALS.UNSUPPORTED_FILE, "me/two-owned.json"),
  ...UPLOAD_REFUSED,
  // The health tab (W9) on /app/health. Lab values (a trend, two one-reading sentences) with the latest prescription; no
  // lab value and no prescription yet; one name in two units; blood pressure's pairs; readings printed as bounds (listed,
  // never drawn); the newest prescription read with nothing on it; a newer one still being read; and a preview family
  // (on /app/preview/health). The longest twin: the longest name (me/long-name.json) over labs-some and medicines-latest.
  "health-some": health("labs-some"),
  "health-none": health("labs-none", "medicines-none"),
  "health-two-units": health("labs-two-units"),
  "health-bp": health("bp"),
  "health-bounds": health("labs-non-numeric"),
  "health-empty-latest": health("labs-some", "medicines-empty-latest"),
  "health-newer-unread": health("labs-some", "medicines-newer-unread"),
  "health-two-no-choice": health("labs-some", "medicines-latest", "me/two-patients.json"),
  "health-preview": health("labs-some", "medicines-latest", "me/preview-on.json"),
  "health-long": health("labs-some", "medicines-latest", "me/long-name.json"),
  // Review of PR #160: a pair whose halves are judged apart; lab values with no prescription, and a prescription with no
  // lab value (the disclaimer each way); and a family's first prescription still being read, nothing read before it.
  "health-bp-flagged": health("bp-flagged"),
  "health-labs-only": health("labs-some", "medicines-none"),
  "health-medicines-only": health("labs-none", "medicines-latest"),
  "health-first-unread": health("labs-none", "medicines-none-newer-unread"),
  // The papers page (W8). Busy: a NOW in the strip, three doctor's dates (one overdue), October's calendar, the first of two
  // pages (its cursor serves the last page), and September behind the previous-month link.
  "papers-busy": papers({ timeline: "timeline/first-page.json", cursor: cursorOf("timeline/first-page.json"), calendar: "calendar/busy.json" }),
  // Forty-five papers in October (master Review Focus 1), with the whole attention stack; and its longest twin, the
  // longest name (me/long-name.json) over the same month.
  "papers-forty-five": papers({ timeline: "timeline/forty-five-first-page.json", calendar: "calendar/forty-five.json", attention: "attention/full-stack.json" }),
  "papers-forty-five-long": papers({
    me: "me/long-name.json",
    timeline: "timeline/forty-five-first-page.json",
    calendar: "calendar/forty-five.json",
    attention: "attention/full-stack.json",
  }),
  // One month: five papers, all in September, and no doctor's date.
  "papers-one-month": papers({
    timeline: "timeline/last-page.json",
    calendar: "calendar/september.json",
    follows: "follow-ups/none.json",
    attention: "attention/empty.json",
  }),
  // No paper at all, for the owner; and for a share member (two-patients' second, whom the family does not own).
  "papers-empty": papers({ timeline: "timeline/empty.json", calendar: "calendar/empty.json", follows: "follow-ups/none.json", attention: "attention/empty.json" }),
  "papers-empty-member": papers({
    me: "me/two-patients.json",
    timeline: "timeline/empty.json",
    calendar: "calendar/empty.json",
    follows: "follow-ups/none.json",
    attention: "attention/empty.json",
  }),
  // A share member on a page with papers (two-patients' second, opened by ?patient=): the busy page, and no send link.
  "papers-member": papers({
    me: "me/two-patients.json",
    timeline: "timeline/first-page.json",
    cursor: cursorOf("timeline/first-page.json"),
    calendar: "calendar/busy.json",
  }),
  // Papers still being read (one PROCESSING, one RECEIVED) beside read ones: the page reloads itself while they are.
  // Its read rows carry the counts line's cases (values outside, medicines, an upload day) and a paper under review.
  // With JavaScript the page checks quietly instead (PO ruling P6): both papers stay PROCESSING, so it never settles here.
  "papers-in-flight": papers({
    timeline: "timeline/in-flight.json",
    calendar: "calendar/busy.json",
    attention: "attention/empty.json",
    status: "document-status/processing.json",
  }),
  // The same page once both have been read (timeline/in-flight-settled.json), its quiet check answered DONE: a test
  // opens papers-in-flight and moves its scenario cookie here, as Java's own state moves when the reading ends.
  "papers-settled": papers({
    timeline: "timeline/in-flight-settled.json",
    calendar: "calendar/busy.json",
    attention: "attention/empty.json",
    status: "document-status/done.json",
  }),
  // Between the two (timeline/in-flight-half.json): the PROCESSING lab report read first, the RECEIVED paper still being
  // read. Its quiet check answers DONE for the paper read and PROCESSING for every other, so a test that passes through
  // here sees two papers read at different times (PO ruling P6, each said once).
  "papers-half": papers({
    timeline: "timeline/in-flight-half.json",
    calendar: "calendar/busy.json",
    attention: "attention/empty.json",
    status: statusById({ [readingIdOf("timeline/in-flight.json", "PROCESSING")]: "document-status/done.json" }, "document-status/processing.json"),
  }),
  // The settled page with a NOW paper the family has not seen (the banner, call_999 true, and the strip's NOW item): the
  // in-place re-render brings the alert in as a reload did. Abroad: the same with call_999 false, so no 999 link.
  "papers-settled-now": {
    ...papers({ timeline: "timeline/in-flight-settled.json", calendar: "calendar/busy.json", status: "document-status/done.json" }),
    "GET /app/api/now-banner": bannerFor("now-banner/one.json", "me/one-patient.json"),
  },
  "papers-settled-now-abroad": {
    ...papers({ timeline: "timeline/in-flight-settled.json", calendar: "calendar/busy.json", status: "document-status/done.json" }),
    "GET /app/api/now-banner": bannerFor("now-banner/abroad.json", "me/one-patient.json"),
  },
  // The prescription filter over a patient with lab reports only: Java's empty filtered page (timeline/rx-none.json).
  "papers-filter-none": papers({
    timeline: "timeline/first-page.json",
    cursor: cursorOf("timeline/first-page.json"),
    calendar: "calendar/busy.json",
    rx: "timeline/rx-none.json",
  }),
  // The busy page for a patient whose chronic flags are set (me/flags-set.json): no BMX-8 ask.
  "papers-flags-set": papers({
    me: "me/flags-set.json",
    timeline: "timeline/first-page.json",
    cursor: cursorOf("timeline/first-page.json"),
    calendar: "calendar/busy.json",
  }),

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
  // A delete whose confirm token Java refused (expired, or not this session's): the sheet stays and says so.
  "paper-delete-refused": paper("documents/routine-released.json", {
    "DELETE /app/api/documents/{id}": { status: 400, body: { reason: "CONFIRM_REQUIRED" } },
  }),
  // The moment after this family's own delete: the paper Java no longer shows (switched to by the delete test).
  "paper-deleted": paper("documents/not-found.json"),
  // A crop whose session ended: the image route sends the browser to sign-in (303), and the status read is a 401.
  "paper-crop-signed-out": paper("documents/routine-released.json", {
    [STATUS]: UNAUTHENTICATED,
    "GET /app/api/sign-in/next": UNAUTHENTICATED,
    ...SIGN_IN,
  }),
  // The NOW paper whose value rows open a sheet Java serves: a stale seen tap, a sheet, then back (banner-only.ts).
  "paper-now-bd-sheet": paper("documents/now-bd.json", { [SHEET]: "value-sheet/earlier-three.json" }),
  // Whose paper (W6's `patient`, for PO sight): two patients and no choice, the paper about the second, so a page that
  // named the first, or showed the chooser, is told apart; and a paper about a patient this family's `me` does not list.
  "paper-two-no-choice": paper(
    paperAbout("documents/now-bd.json", "5e9edf6d-0a2a-4913-8675-9d07a4100012", "me/two-patients.json"),
    {},
    "me/two-patients.json",
  ),
  "paper-not-this-familys": paper(paperAbout("documents/now-bd.json", UNLISTED_PATIENT)),
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
  "/app/add": "upload-owner-one",
  "/app/home": "chat-default",
  "/app/family": "family-owner",
  "/app/patients/{id}/profile": "family-owner",
  "/app/health": "health-some",
  "/app/timeline": "papers-busy",
  // W6: the NOW paper (the summary's heaviest state), the longest paper's /all, and a sheet with three earlier readings.
  "/app/documents/{id}": "paper-now-bd",
  "/app/documents/{id}/all": "paper-58",
  "/app/documents/{id}/values/{obs}": "sheet-earlier-three",
};

/** The path a dynamic NEXT_PAGES entry is measured at, its id read from the fixture its scenario serves (budget.ts). */
export const PAGE_PATHS = {
  "/app/patients/{id}/profile": `/app/patients/${familyPatientId("family/owner.json")}/profile`,
  "/app/documents/{id}": `/app/documents/${PAPER_ID}`,
  "/app/documents/{id}/all": `/app/documents/${PAPER_ID}/all`,
  "/app/documents/{id}/values/{obs}": `/app/documents/${PAPER_ID}/values/${SHEET_OBS}`,
};

/** The name of a scenario's NOW twin (withNow), registered below for every signed-in PAGE_SCENARIOS entry. */
export function withNowName(name) {
  return `${name}--now`;
}

/** The name of a scenario's stale-acknowledgement twin: the NOW twin whose seen tap Java answers `show: true`. */
export function withStaleNowName(name) {
  return `${name}--now-stale`;
}

/**
 * A page's own scenario with an unseen NOW paper for its `me` patient: `GET /app/api/now-banner` answered as
 * bannerFor("now-banner/one.json", <the scenario's me fixture>), everything else as the page's scenario answers it, so
 * the banner check opens each page with the reads that page makes (a scenario of another page answers 404 to them).
 * Every W screen PR spreads NO_NOW into its scenarios; this replaces exactly that one key.
 */
export function withNow(name, table = SCENARIOS[name]) {
  const me = table?.["GET /app/api/me"];
  if (typeof me !== "string") throw new Error(`${name}: its me answer is not a fixture file, so no NOW twin can name its patient`);
  return { ...table, "GET /app/api/now-banner": bannerFor("now-banner/one.json", me) };
}

// paper-now-bd-sheet is no page's measured scenario, but its stale twin is the back-after-a-sheet test's (paper.spec.ts).
for (const name of [...Object.values(PAGE_SCENARIOS), "paper-now-bd-sheet"]) {
  if (signedOut(name)) continue;
  const now = withNow(name);
  SCENARIOS[withNowName(name)] = now;
  SCENARIOS[withStaleNowName(name)] = { ...now, "PUT /app/api/now-banner/ack": reRead("now-banner/one.json", now["GET /app/api/me"]) };
}

/**
 * What only the page itself renders, per NEXT_PAGES entry: budget.spec.ts requires it in the measured document, so a
 * 200 error page, or a page that rendered something else, cannot be measured in its place and pass the ceiling.
 */
export const PAGE_MARKERS = {
  "/app": 'name="whatsapp_number"',
  "/app/verify": 'name="code"',
  "/app/add": "data-upload-sheet",
  "/app/home": 'data-page="chat"',
  "/app/family": 'class="family-account"',
  "/app/patients/{id}/profile": `data-patient-card="${familyPatientId("family/owner.json")}"`,
  "/app/health": 'data-page="health"',
  "/app/timeline": 'class="month-list"',
  "/app/documents/{id}": "data-now-block",
  "/app/documents/{id}/all": 'id="values"',
  "/app/documents/{id}/values/{obs}": 'id="value-sheet-title"',
};

/** Each routed scenario's longest twin (`me/long-name.json` and the area's longest fixture), per screen. */
export const LONGEST = {
  // The shell (W4): its longest is the longest name (fixtures/api/me/long-name.json) in the toolbar and the sidebar.
  "me-one": "me-long",
  // The add sheet (W7): owner-two with only Ma's name lengthened (me/two-owned-long.json in the shell,
  // upload-options/long-name.json's two chips on the sheet), so the twin is the routed state, the chooser included.
  "upload-owner-two": "upload-owner-two-long",
  // The chat page (W5): the longest name, and the thread's longest real page (thread/second-page.json, twenty items).
  "chat-default": "chat-long",
  "chat-now": "chat-now-long",
  // family (W10): the longest name on the tab's own cards (family/long-name.json, owned; long-name-shared.json, seen by a
  // share member), each with its own `me`, so the card headings are measured, not only the shell's toolbar.
  "family-owner": "family-owner-long",
  "family-share-member": "family-share-member-long",
  // The health tab (W9): the longest name in the shell over the lab values and the latest prescription's lines.
  "health-some": "health-long",
  // The papers page (W8): the longest name over forty-five papers in one month (timeline/forty-five-first-page.json).
  "papers-busy": "papers-forty-five-long",
  // The paper (W6): the longest name over the longest paper (fifty-eight values on three pages), for the summary and
  // for /all; and the longest name over the sheet with the most earlier readings.
  "paper-now-bd": "paper-58-long",
  "paper-58": "paper-58-long",
  "sheet-earlier-three": "sheet-earlier-three-long",
};

/** The `{status, body}` a scenario value answers first: a fixture file read, a literal as it is. */
function firstAnswer(value) {
  const plain = value !== null && typeof value === "object" && "byQuery" in value ? value.otherwise : value;
  const v = Array.isArray(plain) ? plain[0] : plain;
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
