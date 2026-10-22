import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { expect, test, type Page } from "@playwright/test";
import type { UploadAnswerJson, UploadOptionsJson } from "../src/api/types";
import { banglaDigits, t } from "../src/copy";
import { reasonArgs, UPLOAD_REASON_COPY } from "../src/components/Upload/upload-reasons";
import { useScenario, type BrowserRequest } from "./support/browser-api";
import { FIXTURES } from "./support/scenarios.mjs";
import { screenChecks } from "./support/screen-checks";

/*
 * The add sheet (plan W7) on /app/add against stub Java, every answer a fixture Java's UploadApiContractTest wrote.
 * Owns master Review Focus 3 on the web side: a press the network cut is sent again with the key that press drew and
 * the very same bytes, so Java (UploadCutOffAcceptanceTest, the other half) keeps it one paper. Every request is read
 * by its fields (the multipart parts by name, each file by its SHA-256), never as a document searched for a string.
 */

const body = <T>(fixture: string): T => (JSON.parse(readFileSync(join(FIXTURES, fixture), "utf8")) as { body: T }).body;
const OWNER_ONE = body<UploadOptionsJson>("upload-options/owner-one.json");
const OWNER_TWO = body<UploadOptionsJson>("upload-options/owner-two.json");
const ONE_DOCUMENT = body<UploadAnswerJson>("uploads/one-document.json");
const SEPARATE = body<UploadAnswerJson>("uploads/separate.json");
const REFUSALS = body<Record<string, { status: number; body: { reason: string } }>>("uploads/refusals.json");
const MA = OWNER_ONE.can_send[0]!;
const RETAKE_OF = "5e9edf6d-0a2a-4913-8675-9d07a4100003";

/** Three pages, each its own bytes, so a swapped, dropped or re-encoded page changes the list of hashes. */
const PAGES = [1, 2, 3].map((n) => ({
  name: `page-${n}.jpg`,
  mimeType: "image/jpeg",
  buffer: Buffer.from([0xff, 0xd8, 0xff, 0xe0, ...Array.from({ length: 64 }, (_, i) => (i * n + n) % 256)]),
}));

async function openAdd(page: Page, scenario: string, path = "/app/add") {
  const recorder = await useScenario(page, scenario);
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
const uploads = (requests: BrowserRequest[]) => requests.filter((r) => r.method === "POST" && r.path === "/app/api/uploads");
const field = (r: BrowserRequest, name: string) => r.parts?.filter((p) => p.name === name).map((p) => p.data.toString("utf8")) ?? [];
const fileHashes = (r: BrowserRequest) =>
  r.parts?.filter((p) => p.name === "file").map((p) => createHash("sha256").update(p.data).digest("hex")) ?? [];
const sha = (b: Buffer) => createHash("sha256").update(b).digest("hex");

/** One report of several pages: the family answers the question the sheet asks. */
async function oneReport(page: Page) {
  await page.getByRole("radio", { name: t("bn", "add.group.one") }).check();
}

test.describe("one press, one key", () => {
  test("aRetryResendsTheSameKeyAndTheSameBytes", async ({ page }) => {
    // The first POST dies on the wire (connectionreset); the retry is answered.
    const recorder = await openAdd(page, "upload-retry");
    await addPages(page);
    await oneReport(page);
    await sendButton(page).click();
    // The first press passed the guard (no refusal by name) and died on the wire.
    await expect
      .poll(() => uploads(recorder.requests()).map((r) => ({ answered: r.answered, reason: r.reason })))
      .toEqual([{ answered: "aborted", reason: null }]);
    const retry = page.getByRole("button", { name: t("bn", "up.retry") });
    await retry.waitFor();
    await expect(page.getByText(t("bn", "up.stuck"))).toBeVisible();
    await retry.click();
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
    expect(field(second!, "patient")).toEqual([MA.id]);
    expect(field(second!, "group")).toEqual(["one"]);
  });

  test("aNewPressDrawsANewKey", async ({ page }) => {
    // The first press is refused; the family takes a page out and presses again: another press, another key.
    const recorder = await openAdd(page, "upload-refused-then-sent");
    await addPages(page);
    await oneReport(page);
    await sendButton(page).click();
    await expect(alertLine(page)).toHaveText(t("bn", UPLOAD_REASON_COPY.UNSUPPORTED_FILE!));
    await page.getByRole("button", { name: t("bn", "tray.remove").replace("{n}", banglaDigits("3")) }).click();
    await sendButton(page).click();
    await page.waitForURL((url) => url.pathname === ONE_DOCUMENT.next);
    const [first, second] = uploads(recorder.requests());
    expect(fileHashes(second!), "the second press sent two pages").toHaveLength(2);
    expect(field(first!, "upload_key")).toHaveLength(1);
    expect(field(second!, "upload_key")).toHaveLength(1);
    expect(field(second!, "upload_key")[0], "a changed press draws its own key").not.toBe(field(first!, "upload_key")[0]);
  });
});

test.describe("every refusal is its copy", () => {
  const names = Object.keys(REFUSALS).sort();
  test("theRefusalsWereRead", () => expect(names.length, "refusal names in uploads/refusals.json").toBeGreaterThanOrEqual(10));
  for (const name of names) {
    test(`eachRefusalIsItsCopy ${name}`, async ({ page }) => {
      const recorder = await openAdd(page, `upload-refused-${name}`);
      await addPages(page, PAGES.slice(0, 1));
      await sendButton(page).click();
      const key = UPLOAD_REASON_COPY[name]!;
      await expect(alertLine(page)).toHaveText(t("bn", key, ...reasonArgs(key, OWNER_ONE, "bn")));
      expect(uploads(recorder.requests()).map((r) => r.answered)).toEqual([REFUSALS[name]!.status]);
      // A refusal is said, never a crash: the sheet is still there, its press back on for another try.
      await expect(sendButton(page)).toBeEnabled();
    });
  }
});

test.describe("where a press lands", () => {
  test("oneDocumentOpensItsPaperSeveralOpenThePapers", async ({ page }) => {
    await openAdd(page, "upload-owner-one");
    await addPages(page);
    await oneReport(page);
    await sendButton(page).click();
    await page.waitForURL((url) => url.pathname === ONE_DOCUMENT.next);

    await openAdd(page, "upload-separate");
    await addPages(page);
    await page.getByRole("radio", { name: t("bn", "add.group.separate") }).check();
    await sendButton(page).click();
    const [path, query] = SEPARATE.next.split("?");
    await page.waitForURL((url) => url.pathname === path && url.search === `?${query}`);
  });
});

test.describe("drag and drop", () => {
  test("dragAndDropAddsPages", async ({ page }, info) => {
    test.skip(info.project.name !== "desktop", "a desktop gesture");
    await openAdd(page, "upload-owner-one");
    const transfer = await page.evaluateHandle(() => {
      const dt = new DataTransfer();
      for (const n of [1, 2]) dt.items.add(new File([new Uint8Array([0xff, 0xd8, 0xff, n])], `drop-${n}.jpg`, { type: "image/jpeg" }));
      return dt;
    });
    await page.dispatchEvent("body", "dragenter", { dataTransfer: transfer });
    await expect(page.locator("#drop"), "the drop target shows while files are held over the page").toBeVisible();
    await expect(page.locator("#drop")).toHaveText(t("bn", "upload.drop"));
    await page.dispatchEvent("body", "dragover", { dataTransfer: transfer });
    await page.dispatchEvent("body", "drop", { dataTransfer: transfer });
    await expect(page.locator(".page-tray__item")).toHaveCount(2);
    await expect(page.locator("#drop")).toBeHidden();
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
    const recorder = await openAdd(page, "upload-owner-two-refused", `/app/add?patient=${ma!.id}&replaces=${RETAKE_OF}`);
    await expect(chip(ma), "the patient the retake link named").toBeChecked();
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

test.describe("without JavaScript", () => {
  test.use({ javaScriptEnabled: false });

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
