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
  "next-500": {
    "GET /app/api/me": "me/one-patient.json",
    "GET /app/api/sign-in/next": {
      status: 500,
      body: { error: `failed for ${patientName("me/one-patient.json")} at ${numberShown("sign-in/confirm.json")}` },
    },
  },
};

export { UNAUTHENTICATED };

/** The scenario each page of NEXT_PAGES is measured under by budget.spec.ts (it fails for a page missing here). */
export const PAGE_SCENARIOS = {
  "/app": "signed-out",
  "/app/verify": "state-pending-not-sent",
};

/**
 * What only the page itself renders, per NEXT_PAGES entry: budget.spec.ts requires it in the measured document, so a
 * 200 error page, or a page that rendered something else, cannot be measured in its place and pass the ceiling.
 */
export const PAGE_MARKERS = {
  "/app": 'name="whatsapp_number"',
  "/app/verify": 'name="code"',
};

/** Each routed scenario's longest twin (`me/long-name.json` and the area's longest fixture), per screen. */
export const LONGEST = {};

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
