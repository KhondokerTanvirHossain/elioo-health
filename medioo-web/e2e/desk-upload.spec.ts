import { readFileSync } from "node:fs";
import { join } from "node:path";
import { expect, test, type Page } from "@playwright/test";
import type { MeJson, ThreadJson, UploadAnswerJson } from "../src/api/types";
import { t } from "../src/copy";
import { useScenario, type BrowserRequest } from "./support/browser-api";
import { SCHEMES, WIDTHS, hydrated } from "./support/desk-checks";
import { axeReport, cspViolations, expectSchemeIsPainted, installCspHook } from "./support/screen-checks";

/*
 * Sending a paper on the desktop layout (plan desktop-parity Phase 2, row U1; desktop.html `uploadDlg`, `#drop`): the
 * paperclip opens today's add sheet as a dialog over the talk-and-papers screen (`?add=1`), modal, its close and Escape
 * coming back to the same paper; a paper dropped anywhere opens it with the dropped pages in its tray; a press from the
 * dialog is the add sheet's own press to Java, which then says where to go. Against the production build and stub Java.
 */

/** A fixture's body, as the stub serves it under the chat-upload scenario (e2e/support/scenarios.mjs). */
const body = <T>(file: string): T => JSON.parse(readFileSync(join(__dirname, "..", "fixtures", "api", file), "utf8")).body as T;
const PATIENT = body<MeJson>("me/one-patient.json").patients[0]!.id;
const PAPER = body<ThreadJson>("thread/default.json").items.flatMap((i) => (i.kind === "paper" ? [i.card.id] : []))[0]!;
const ONE_DOCUMENT = body<UploadAnswerJson>("uploads/one-document.json");
const HOME = `/app/home?patient=${PATIENT}&paper=${PAPER}`;
const JPEG = { name: "page-from-the-desk.jpg", mimeType: "image/jpeg", buffer: Buffer.from([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3, 4]) };

async function open(page: Page, path = HOME) {
  const recorder = await useScenario(page, "chat-upload");
  await installCspHook(page);
  // Where a press lands: a page of its own, so the test sees the address Java sent it to.
  await page.route(/\/app\/(documents|timeline)(\/|\?|$)/, (route) =>
    route.request().isNavigationRequest() ? route.fulfill({ status: 200, contentType: "text/html", body: "<p>landed</p>" }) : route.fallback(),
  );
  const response = await page.goto(path);
  expect(response?.status(), path).toBe(200);
  await expect(page.locator(".desktop-shell"), "the desktop layout").toBeVisible();
  await hydrated(page);
  return recorder;
}

const dialog = (page: Page) => page.locator("dialog.desk-upload");
const uploads = (requests: BrowserRequest[]) => requests.filter((r) => r.method === "POST" && r.path === "/app/api/uploads");

test.describe("sending a paper on the desktop layout", () => {
  test.skip(({ isMobile }) => isMobile, "the desktop layout's");
  test.use({ viewport: WIDTHS.w1440 });

  test("thePaperclipOpensTheSheetOverTheScreenAndEscapeComesBackToThePaper", async ({ page }) => {
    await open(page);
    await page.locator(".desktop-shell .composer__attach").click();
    await expect(page).toHaveURL(new RegExp(`[?&]add=1(&|$)`));
    await expect(dialog(page)).toBeVisible();
    await expect(dialog(page)).toHaveAttribute("aria-label", t("bn", "add.title"));
    await expect(dialog(page).locator("[data-upload-sheet][data-live]")).toBeVisible();
    // Modal: the screen behind takes no focus or click while it is open.
    expect(await page.evaluate(() => document.querySelector<HTMLDialogElement>("dialog.desk-upload")!.matches(":modal"))).toBe(true);
    // The paper stays in view behind it.
    await expect(page.locator(".desktop-shell .paper-viewer")).toHaveAttribute("data-paper", PAPER);
    await page.keyboard.press("Escape");
    await expect(page).toHaveURL(new RegExp(`/app/home\\?patient=${PATIENT}&paper=${PAPER}$`));
    await expect(dialog(page)).toHaveCount(0);
  });

  test("aPaperDroppedAnywhereOpensTheSheetWithItInTheTray", async ({ page }) => {
    await open(page);
    // The drop frame's listeners are on (they attach a moment after hydration; a slow CI runner dropped before, PR 183).
    await page.locator(".desk-drop[data-live]").waitFor({ state: "attached" });
    // A file dragged over the window and let go on the conversation, as a browser hands it to the page.
    await page.evaluate(
      async ({ name, bytes }) => {
        const file = new File([new Uint8Array(bytes)], name, { type: "image/jpeg" });
        const data = new DataTransfer();
        data.items.add(file);
        const target = document.querySelector(".desktop-shell__talk")!;
        target.dispatchEvent(new DragEvent("dragenter", { bubbles: true, cancelable: true, dataTransfer: data }));
        target.dispatchEvent(new DragEvent("dragover", { bubbles: true, cancelable: true, dataTransfer: data }));
        target.dispatchEvent(new DragEvent("drop", { bubbles: true, cancelable: true, dataTransfer: data }));
      },
      { name: JPEG.name, bytes: [...JPEG.buffer] },
    );
    await expect(page).toHaveURL(/[?&]add=1(&|$)/);
    await expect(dialog(page).locator("[data-upload-sheet][data-live]")).toBeVisible();
    await expect(dialog(page).locator(".upload-tray__thumb, [data-page-id]"), "the dropped page is the tray's").not.toHaveCount(0);
  });

  test("aPressFromTheDialogIsTheSheetsOwnPressAndGoesWhereJavaSays", async ({ page }) => {
    const recorder = await open(page, `${HOME}&add=1`);
    await dialog(page).locator('input[type=file][data-src="gallery"]').setInputFiles([JPEG]);
    await dialog(page).getByRole("button", { name: t("bn", "add.send"), exact: true }).click();
    await page.waitForURL((url) => url.pathname === new URL(ONE_DOCUMENT.next, "http://x").pathname);
    expect(uploads(recorder.requests()), "one press to Java").toHaveLength(1);
  });

  for (const colorScheme of SCHEMES) {
    for (const [name, viewport] of [
      ["w1440", WIDTHS.w1440],
      ["w1024", WIDTHS.w1024],
    ] as const) {
      test(`axeAndCspWithTheDialogOpen (${colorScheme}, ${name})`, async ({ page }) => {
        await page.setViewportSize(viewport);
        await page.emulateMedia({ colorScheme });
        await open(page, `${HOME}&add=1`);
        await expect(dialog(page).locator("[data-upload-sheet][data-live]")).toBeVisible();
        await expectSchemeIsPainted(page, colorScheme);
        const report = await axeReport(page);
        expect(report.violations).toEqual([]);
        expect(await cspViolations(page)).toEqual([]);
      });
    }
  }
});
