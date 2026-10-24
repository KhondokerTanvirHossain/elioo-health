import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { expect, test, type Page } from "@playwright/test";
import type { UploadAnswerJson, UploadOptionsJson } from "../src/api/types";
import { banglaDigits, formatDate, t, type CopyKey } from "../src/copy";
import { UPLOAD_REASON_COPY } from "../src/components/Upload/upload-reasons";
import { useScenario, type BrowserRequest } from "./support/browser-api";
import { FIXTURES } from "./support/scenarios.mjs";
import { screenChecks } from "./support/screen-checks";

/*
 * The add sheet (plan W7) on /app/add against stub Java, every answer a fixture Java's UploadApiContractTest wrote.
 * Owns master Review Focus 3 on the web side: a press the network cut, or let go quiet, or one answered with no named
 * refusal, is sent again with the key that press drew and the very same bytes, so Java (UploadCutOffAcceptanceTest,
 * the other half) keeps it one paper. Every request is read by its fields (the multipart parts by name, each file by
 * its SHA-256 and its filename), never as a document searched for a string.
 */

const body = <T>(fixture: string): T => (JSON.parse(readFileSync(join(FIXTURES, fixture), "utf8")) as { body: T }).body;
const OWNER_ONE = body<UploadOptionsJson>("upload-options/owner-one.json");
const OWNER_TWO = body<UploadOptionsJson>("upload-options/owner-two.json");
const ONE_DOCUMENT = body<UploadAnswerJson>("uploads/one-document.json");
const SEPARATE = body<UploadAnswerJson>("uploads/separate.json");
const REFUSALS = body<Record<string, { status: number; body: { reason: string } }>>("uploads/refusals.json");
const MA = OWNER_ONE.can_send[0]!;
const RETAKE_OF = "5e9edf6d-0a2a-4913-8675-9d07a4100003";
/** apiUpload's watchdog: no byte moving for this long and the request is given up on (upload.js: 30 s). */
const STALL_MS = 30_000;

/**
 * Three pages, each its own bytes, so a swapped, dropped or re-encoded page changes the list of hashes; named as a
 * gallery names a family's photo (fictional), so a page sent under its own name is seen by field.
 */
const PAGES = [1, 2, 3].map((n) => ({
  name: `Rahima Begum CBC ${n}.jpg`,
  mimeType: "image/jpeg",
  buffer: Buffer.from([0xff, 0xd8, 0xff, 0xe0, ...Array.from({ length: 64 }, (_, i) => (i * n + n) % 256)]),
}));

async function openAdd(page: Page, scenario: string, path = "/app/add", opts: { clock?: boolean } = {}) {
  const recorder = await useScenario(page, scenario);
  // The page's own timers under the test's hand, so a quiet network's 30 s pass at once.
  if (opts.clock) await page.clock.install();
  // Where a press lands (a paper, the papers): a page of its own here, so the test sees the address it went to.
  await page.route(/\/app\/(documents|timeline)(\/|\?|$)/, (route) =>
    route.request().isNavigationRequest() ? route.fulfill({ status: 200, contentType: "text/html", body: "<p>landed</p>" }) : route.fallback(),
  );
  const response = await page.goto(path);
  expect(response?.status(), `${path} under ${scenario}`).toBe(200);
  // The island is live: its controls are on only after hydration.
  await page.locator("[data-upload-sheet][data-live]").waitFor();
  return recorder;
}

const addPages = (page: Page, files = PAGES) => page.locator('input[type=file][data-src="gallery"]').setInputFiles(files);
/** The sheet's own refusal line (role=alert; Next's route announcer is another alert on every page). */
const alertLine = (page: Page) => page.locator(".upload-sheet__alert");
const sendButton = (page: Page) => page.getByRole("button", { name: t("bn", "add.send"), exact: true });
const retryButton = (page: Page) => page.getByRole("button", { name: t("bn", "up.retry") });
const uploads = (requests: BrowserRequest[]) => requests.filter((r) => r.method === "POST" && r.path === "/app/api/uploads");
const field = (r: BrowserRequest, name: string) => r.parts?.filter((p) => p.name === name).map((p) => p.data.toString("utf8")) ?? [];
const fileHashes = (r: BrowserRequest) =>
  r.parts?.filter((p) => p.name === "file").map((p) => createHash("sha256").update(p.data).digest("hex")) ?? [];
const fileNames = (r: BrowserRequest) => r.parts?.filter((p) => p.name === "file").map((p) => p.filename) ?? [];
const sha = (b: Buffer) => createHash("sha256").update(b).digest("hex");
const keysOf = (requests: BrowserRequest[]) => uploads(requests).map((r) => field(r, "upload_key"));

/** One report of several pages: the family answers the question the sheet asks. */
async function oneReport(page: Page) {
  await page.getByRole("radio", { name: t("bn", "add.group.one") }).check();
}

/** Three pages as one report, pressed. */
async function pressThree(page: Page) {
  await addPages(page);
  await oneReport(page);
  await sendButton(page).click();
}

test.describe("one press, one key", () => {
  test("aRetryResendsTheSameKeyAndTheSameBytes", async ({ page }) => {
    // The first POST dies on the wire (connectionreset); the retry is answered.
    const recorder = await openAdd(page, "upload-retry");
    await pressThree(page);
    // The first press passed the guard (no refusal by name) and died on the wire.
    await expect
      .poll(() => uploads(recorder.requests()).map((r) => ({ answered: r.answered, reason: r.reason })))
      .toEqual([{ answered: "aborted", reason: null }]);
    await retryButton(page).waitFor();
    await expect(page.getByText(t("bn", "up.stuck"))).toBeVisible();
    await retryButton(page).click();
    await page.waitForURL((url) => url.pathname === ONE_DOCUMENT.next);

    const sent = uploads(recorder.requests());
    expect(sent.map((r) => r.answered), "the first died on the wire, the retry was answered").toEqual(["aborted", 200]);
    const [first, second] = sent;
    // The guard accepted both (it answers before the route): X-Medioo, the page's own Origin, and the browser's own
    // multipart type with its boundary.
    for (const r of sent) {
      expect(r.headers["x-medioo"]).toBe("1");
      expect(r.headers["content-type"]).toMatch(/^multipart\/form-data; boundary=/);
    }
    expect(field(first!, "upload_key"), "one key per request").toHaveLength(1);
    expect({ upload_key: field(second!, "upload_key") }).toEqual({ upload_key: field(first!, "upload_key") });
    expect(fileHashes(first!), "the files the family chose, in order").toEqual(PAGES.map((p) => sha(p.buffer)));
    expect(fileHashes(second!), "the retry's files").toEqual(fileHashes(first!));
    // Never a file's own name (it may name the patient): each page goes under its number, as upload.js sends it.
    for (const r of sent) expect(fileNames(r), "each file part's filename").toEqual(["page-1.jpg", "page-2.jpg", "page-3.jpg"]);
    expect(field(second!, "patient")).toEqual([MA.id]);
    expect(field(second!, "group")).toEqual(["one"]);
  });

  test("aQuietNetworkReachesStuckAndTheRetryIsTheSamePress", async ({ page }) => {
    // The first POST is never answered and never reset: the phone's signal went, as it usually does.
    const recorder = await openAdd(page, "upload-silent-then-sent", "/app/add", { clock: true });
    await pressThree(page);
    await expect.poll(() => uploads(recorder.requests()).map((r) => r.answered)).toEqual(["held"]);
    await expect(retryButton(page), "no retry while the watchdog has not run").toHaveCount(0);
    await page.clock.fastForward(STALL_MS + 1000);
    await expect(page.getByText(t("bn", "up.stuck")), "a silent network reaches stuck").toBeVisible();
    await retryButton(page).click();
    await page.waitForURL((url) => url.pathname === ONE_DOCUMENT.next);
    const [first, second] = uploads(recorder.requests());
    expect({ upload_key: field(second!, "upload_key") }).toEqual({ upload_key: field(first!, "upload_key") });
    expect(fileHashes(second!)).toEqual(fileHashes(first!));
  });

  test("anAnswerWithNoJavaReasonKeepsThePressKey", async ({ page }) => {
    // A gateway's 504 page (no {"reason"}): Java may have stored the press, so the retry must be that same press.
    const recorder = await openAdd(page, "upload-504-then-sent");
    await pressThree(page);
    // The family's next press, whichever the sheet offers: its retry, or send (add.send) again on the same tray.
    const next = retryButton(page).or(sendButton(page));
    await expect.poll(() => uploads(recorder.requests()).map((r) => r.answered)).toEqual([504]);
    // The sheet has taken the answer in: no longer on its way.
    await expect(page.locator('.upload-progress[data-phase="sending"]')).toHaveCount(0);
    await next.first().waitFor();
    const offeredRetry = await retryButton(page).isVisible();
    await next.first().click();
    await page.waitForURL((url) => url.pathname === ONE_DOCUMENT.next);
    expect(uploads(recorder.requests()).map((r) => r.answered)).toEqual([504, 200]);
    const [first, second] = keysOf(recorder.requests());
    expect({ upload_key: second }, "the 504 does not prove nothing was stored").toEqual({ upload_key: first });
    expect(offeredRetry, "the sheet showed stuck with its retry, not a refusal").toBe(true);
  });

  test("aRefusedPressPressedAgainUnchangedDrawsANewKey", async ({ page }) => {
    // Java named the refusal (the key was used before): the press made nothing, and the same pages pressed again are a
    // new press ("choose the paper again and send"), with nothing about the tray changed.
    const recorder = await openAdd(page, "upload-reused-then-sent");
    await pressThree(page);
    await expect(alertLine(page)).toHaveText(t("bn", "upload.err.key_reused"));
    await sendButton(page).click();
    await page.waitForURL((url) => url.pathname === ONE_DOCUMENT.next);
    const sent = uploads(recorder.requests());
    expect(fileHashes(sent[1]!), "the same pages, unchanged").toEqual(fileHashes(sent[0]!));
    const [first, second] = keysOf(recorder.requests());
    expect(first).toHaveLength(1);
    expect(second).toHaveLength(1);
    expect(second![0], "a named refusal drops the press's key").not.toBe(first![0]);
  });

  test("theNetworkComingBackSendsThePressAgain", async ({ page }) => {
    // Stuck after a cut, then the browser says it is online: the press goes again by itself (up.slow promises it).
    const recorder = await openAdd(page, "upload-retry");
    await pressThree(page);
    await expect(page.getByText(t("bn", "up.stuck"))).toBeVisible();
    await page.evaluate(() => window.dispatchEvent(new Event("online")));
    await page.waitForURL((url) => url.pathname === ONE_DOCUMENT.next);
    const [first, second] = uploads(recorder.requests());
    expect({ upload_key: field(second!, "upload_key") }).toEqual({ upload_key: field(first!, "upload_key") });
    expect(fileHashes(second!), "the same bytes").toEqual(PAGES.map((p) => sha(p.buffer)));
  });
});

/** The arguments each refusal's line takes, written from the fixture (never through production's reasonArgs). */
function expectedArgs(name: string): Array<string | number> {
  switch (name) {
    case "TOO_MANY_PAGES":
    case "TOO_MANY_FILES":
      // Java's own line takes the page cap for both (UploadUiHandler.refuse: copy.plain(lang, key, maxPages)).
      return [OWNER_ONE.limits.max_pages];
    case "FREE_TIER_DOCUMENTS":
      return [OWNER_ONE.allowance.max!, formatDate("bn", OWNER_ONE.allowance.resets_on!)];
    default:
      return [];
  }
}

test.describe("every refusal is its copy", () => {
  const names = Object.keys(REFUSALS).sort();
  test("theRefusalsWereRead", () => expect(names.length, "refusal names in uploads/refusals.json").toBeGreaterThanOrEqual(10));
  test("theNumbersAreTheFixtures", () => {
    // By value: the page cap is ten, said in Bangla digits; the allowance is thirty, until the first of November.
    expect(t("bn", UPLOAD_REASON_COPY.TOO_MANY_PAGES!, ...expectedArgs("TOO_MANY_PAGES"))).toContain(banglaDigits("10"));
    expect(t("bn", UPLOAD_REASON_COPY.FREE_TIER_DOCUMENTS!, ...expectedArgs("FREE_TIER_DOCUMENTS"))).toContain(banglaDigits("30"));
  });
  for (const name of names) {
    test(`eachRefusalIsItsCopy ${name}`, async ({ page }) => {
      const recorder = await openAdd(page, `upload-refused-${name}`);
      // Before the refusal the line is there, empty, in the accessibility tree: a refusal written into it is announced.
      const announced = page.locator("[data-upload-sheet]").getByRole("alert");
      await expect(announced, "the sheet's alert is in the accessibility tree before a refusal").toHaveCount(1);
      await addPages(page, PAGES.slice(0, 1));
      await sendButton(page).click();
      const key = UPLOAD_REASON_COPY[name] as CopyKey;
      await expect(alertLine(page)).toHaveText(t("bn", key, ...expectedArgs(name)));
      await expect(announced, "and after it, with its words").toHaveText(t("bn", key, ...expectedArgs(name)));
      // Focus is on the refusal, never fallen to the page's body.
      await expect(alertLine(page)).toBeFocused();
      expect(uploads(recorder.requests()).map((r) => r.answered)).toEqual([REFUSALS[name]!.status]);
      // A refusal is said, never a crash: the sheet is still there, its press back on for another try.
      await expect(sendButton(page)).toBeEnabled();
    });
  }
});

test.describe("whose paper", () => {
  test("twoPatientsAndNoChoiceGetTheSheetNotTheShellsChooser", async ({ page }) => {
    // No ?patient= and two the family may send for: the sheet asks in its own words; the shell's chooser is not drawn.
    await useScenario(page, "upload-owner-two");
    expect((await page.goto("/app/add"))?.status()).toBe(200);
    await page.waitForLoadState("load");
    await expect(page.locator(".patient-chooser"), "the shell's chooser in place of the sheet").toHaveCount(0);
    const sheet = page.locator("[data-upload-sheet][data-live]");
    await expect(sheet.getByText(t("bn", "add.choose"))).toBeVisible();
    await expect(sheet.getByRole("radio")).toHaveCount(OWNER_TWO.can_send.length);
  });

  test("thePageAsksJavaWhoseItIs", async ({ page }) => {
    // The page hands Java its ?patient= and renders Java's chosen_patient_id (owner-two-asked: Ma).
    const recorder = await openAdd(page, "upload-owner-two-asked-refused", `/app/add?patient=${MA.id}`);
    const reads = (await recorder.java()).filter((r) => r.method === "GET" && r.path === "/app/api/upload-options");
    expect(reads.map((r) => r.query), "the options read carries the page's ?patient=").toEqual([`patient=${MA.id}`]);
    await expect(page.getByRole("radio", { name: t("bn", "add.patient", MA.name, MA.age!), exact: true })).toBeChecked();
  });
});

test.describe("where a press lands", () => {
  test("oneDocumentOpensItsPaperSeveralOpenThePapers", async ({ page }) => {
    await openAdd(page, "upload-owner-one");
    await pressThree(page);
    await page.waitForURL((url) => url.pathname === ONE_DOCUMENT.next);

    await openAdd(page, "upload-separate");
    await addPages(page);
    await page.getByRole("radio", { name: t("bn", "add.group.separate") }).check();
    await sendButton(page).click();
    const [path, query] = SEPARATE.next.split("?");
    await page.waitForURL((url) => url.pathname === path && url.search === `?${query}`);
  });
});

/** A JPEG's pixel size, read from its start-of-frame segment (SOF0 or SOF2), never from its file name or size. */
function jpegSize(b: Buffer): { width: number; height: number } | null {
  for (let i = 2; i + 9 < b.length; ) {
    if (b[i] !== 0xff) return null;
    const marker = b[i + 1]!;
    const length = b.readUInt16BE(i + 2);
    if (marker === 0xc0 || marker === 0xc2) return { height: b.readUInt16BE(i + 5), width: b.readUInt16BE(i + 7) };
    i += 2 + length;
  }
  return null;
}

test.describe("before a page leaves the phone", () => {
  test("theServersDownscaleIsAppliedInTheBrowser", async ({ page }) => {
    const options = body<UploadOptionsJson>("upload-options/downscale-on.json");
    expect(options.limits.downscale_max_edge, "the fixture turns it on").toBeGreaterThan(0);
    const recorder = await openAdd(page, "upload-downscale");
    // A 1600 × 1200 photo of noise (so it is heavy), drawn and encoded by this browser.
    const photo = Buffer.from(
      await page.evaluate(async () => {
        const c = document.createElement("canvas");
        c.width = 1600;
        c.height = 1200;
        const g = c.getContext("2d")!;
        const img = g.createImageData(1600, 1200);
        for (let i = 0; i < img.data.length; i++) img.data[i] = (i * 2654435761) % 251;
        g.putImageData(img, 0, 0);
        const blob = await new Promise<Blob>((ok) => c.toBlob((b) => ok(b!), "image/jpeg", 0.95));
        return [...new Uint8Array(await blob.arrayBuffer())];
      }),
    );
    expect(jpegSize(photo)).toEqual({ width: 1600, height: 1200 });
    await addPages(page, [{ name: "Rahima Begum prescription.jpg", mimeType: "image/jpeg", buffer: photo }]);
    await sendButton(page).click();
    await page.waitForURL((url) => url.pathname === ONE_DOCUMENT.next);
    const [press] = uploads(recorder.requests());
    const sent = press!.parts!.filter((p) => p.name === "file");
    expect(sent.map((p) => p.filename)).toEqual(["page-1.jpg"]);
    const edge = options.limits.downscale_max_edge;
    expect(jpegSize(sent[0]!.data), "scaled to the server's edge, in proportion").toEqual({ width: edge, height: (edge * 3) / 4 });
  });
});

test.describe("drag and drop", () => {
  const fileDrag = (page: Page) =>
    page.evaluateHandle(() => {
      const dt = new DataTransfer();
      for (const n of [1, 2]) dt.items.add(new File([new Uint8Array([0xff, 0xd8, 0xff, n])], `drop-${n}.jpg`, { type: "image/jpeg" }));
      return dt;
    });

  test("dragAndDropAddsPages", async ({ page }, info) => {
    test.skip(info.project.name !== "desktop", "a desktop gesture");
    await openAdd(page, "upload-owner-one");
    const transfer = await fileDrag(page);
    await page.dispatchEvent("body", "dragenter", { dataTransfer: transfer });
    await expect(page.locator("#drop"), "the drop target shows while files are held over the page").toBeVisible();
    await page.dispatchEvent("body", "dragover", { dataTransfer: transfer });
    await page.dispatchEvent("body", "drop", { dataTransfer: transfer });
    await expect(page.locator(".upload-tray__item")).toHaveCount(2);
    await expect(page.locator("#drop")).toBeHidden();
  });

  test("aPressOnItsWayIsNeverLeftByADropOrAnUnload", async ({ page }, info) => {
    test.skip(info.project.name !== "desktop", "a desktop gesture");
    const recorder = await openAdd(page, "upload-silent");
    /** Whether the page refused the browser's own action for each event (a drop opening the file, an unload). */
    const refused = () =>
      page.evaluate(() => {
        const dt = new DataTransfer();
        dt.items.add(new File([new Uint8Array([0xff, 0xd8, 0xff, 9])], "stray.jpg", { type: "image/jpeg" }));
        const fire = (type: string) => {
          const e = new DragEvent(type, { dataTransfer: dt, bubbles: true, cancelable: true });
          document.body.dispatchEvent(e);
          return e.defaultPrevented;
        };
        const unload = new Event("beforeunload", { cancelable: true });
        window.dispatchEvent(unload);
        return { dragover: fire("dragover"), drop: fire("drop"), unload: unload.defaultPrevented };
      });
    // Idle, leaving is free, and a drop is the sheet's: its page joins the tray (then is taken out again).
    expect(await refused(), "idle").toEqual({ dragover: true, drop: true, unload: false });
    await expect(page.locator(".upload-tray__item")).toHaveCount(1);
    await page.getByRole("button", { name: t("bn", "tray.remove").replace("{n}", banglaDigits("1")) }).click();

    await pressThree(page);
    await page.locator('.upload-progress[data-phase="sending"]').waitFor();
    // On its way: a stray drop opens nothing in the tab and joins nothing; leaving the page asks first.
    expect(await refused(), "sending").toEqual({ dragover: true, drop: true, unload: true });
    expect(new URL(page.url()).pathname).toBe("/app/add");
    await expect(page.locator(".upload-tray__item"), "nothing joined the press on its way").toHaveCount(0);
    expect(uploads(recorder.requests()), "still exactly one request").toHaveLength(1);
  });
});

test.describe("a retake", () => {
  test("aRetakeNamesThePaperItReplaces", async ({ page }) => {
    const recorder = await openAdd(page, "upload-owner-one", `/app/add?patient=${MA.id}&replaces=${RETAKE_OF}`);
    await addPages(page, PAGES.slice(0, 1));
    await sendButton(page).click();
    await page.waitForURL((url) => url.pathname === ONE_DOCUMENT.next);
    const [press] = uploads(recorder.requests());
    expect(field(press!, "replaces")).toEqual([RETAKE_OF]);
    expect(field(press!, "patient")).toEqual([MA.id]);
  });

  test("aRetakeDropsReplacesWhenThePatientChanges", async ({ page }) => {
    const [ma, baba] = OWNER_TWO.can_send;
    const chip = (p: typeof ma) => page.getByRole("radio", { name: t("bn", "add.patient", p!.name, p!.age!), exact: true });
    const recorder = await openAdd(page, "upload-owner-two-asked-refused", `/app/add?patient=${ma!.id}&replaces=${RETAKE_OF}`);
    await expect(chip(ma), "the patient Java chose for the retake link").toBeChecked();
    await addPages(page, PAGES.slice(0, 1));
    await chip(baba).check();
    await sendButton(page).click();
    await expect(alertLine(page)).not.toBeEmpty();
    await chip(ma).check();
    await sendButton(page).click();
    await expect.poll(() => uploads(recorder.requests()).length).toBe(2);
    const [forBaba, forMa] = uploads(recorder.requests());
    expect({ patient: field(forBaba!, "patient"), replaces: field(forBaba!, "replaces") }).toEqual({ patient: [baba!.id], replaces: [] });
    expect({ patient: field(forMa!, "patient"), replaces: field(forMa!, "replaces") }).toEqual({ patient: [ma!.id], replaces: [RETAKE_OF] });
  });
});

/** The sheet's close (sheet.close), a link in its header. */
const closeLink = (page: Page) => page.locator("[data-upload-sheet]").getByRole("link", { name: t("bn", "sheet.close"), exact: true });

test.describe("the sheet's close", () => {
  const PAPER = `/app/documents/${RETAKE_OF}`;

  test("theCloseGoesBackToThePaperItWasOpenedFrom", async ({ page }) => {
    // The link Java's paper page draws (DocumentPages.anotherPaper): back=/app/documents/{id}.
    await openAdd(page, "upload-owner-one", `/app/add?patient=${MA.id}&back=${encodeURIComponent(PAPER)}`);
    await expect(closeLink(page)).toHaveAttribute("href", PAPER);
    const box = (await closeLink(page).boundingBox())!;
    expect({ width: box.width >= 44, height: box.height >= 44 }, "a 44 px target").toEqual({ width: true, height: true });
    await closeLink(page).click();
    await page.waitForURL((url) => url.pathname === PAPER);
  });

  for (const back of ["//evil.example", "https://evil.example/app/home", "/\\evil.example", "/app/../api/v1/baymax/me"]) {
    test(`theCloseNeverLeavesTheApp ${back}`, async ({ page }) => {
      await openAdd(page, "upload-owner-one", `/app/add?patient=${MA.id}&back=${encodeURIComponent(back)}`);
      await expect(closeLink(page)).toHaveAttribute("href", "/app/home");
    });
  }

  test("withNoBackTheCloseGoesToTheChatTab", async ({ page }) => {
    await openAdd(page, "upload-owner-one");
    await expect(closeLink(page)).toHaveAttribute("href", "/app/home");
  });
});

test.describe("without JavaScript", () => {
  test.use({ javaScriptEnabled: false });

  test("withoutJavaScriptTheCloseStillCloses", async ({ page }) => {
    await useScenario(page, "upload-owner-one");
    const paper = `/app/documents/${RETAKE_OF}`;
    expect((await page.goto(`/app/add?patient=${MA.id}&back=${encodeURIComponent(paper)}`))?.status()).toBe(200);
    await expect(closeLink(page), "a plain link the server drew").toHaveAttribute("href", paper);
  });

  test("withoutJavaScriptTheSheetSaysSo", async ({ page }) => {
    await useScenario(page, "upload-owner-one");
    const response = await page.goto("/app/add");
    expect(response?.status()).toBe(200);
    // Inside <noscript> (Playwright's text engine skips noscript, so the element is read itself), and laid out.
    const nojs = page.locator("noscript .upload-sheet__nojs");
    await expect(nojs).toHaveText(t("bn", "upload.nojs"));
    expect(await nojs.evaluate((el) => (el as HTMLElement).offsetHeight), "the line is drawn").toBeGreaterThan(0);
    await expect(page.locator("form"), "no form that would post somewhere").toHaveCount(0);
    // What the server drew is the sheet, its press off: nothing here sends without the script.
    await expect(sendButton(page)).toBeDisabled();
    await expect(page.locator("input[type=file]:enabled")).toHaveCount(0);
  });
});

screenChecks("/app/add", "upload-owner-two", { longest: true, onePrimary: true });
