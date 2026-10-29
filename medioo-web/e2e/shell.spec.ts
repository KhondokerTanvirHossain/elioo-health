import { readFileSync } from "node:fs";
import { join } from "node:path";
import { expect, test, type Page } from "@playwright/test";
import { t } from "../src/copy";
import { useScenario } from "./support/browser-api";
import { FIXTURES } from "./support/scenarios.mjs";
import { screenChecks } from "./support/screen-checks";

/*
 * The shell (plan W4) on its catalog-only page /app/shell-test (the `catalog` project, against the catalog build and
 * stub Java): the three layouts, the chooser that never guesses, the preview gate, and the two ways a session ends (a
 * 401 in the middle of a page, and signing out). Every answer is a fixture Java's MeApiContractTest wrote.
 */

type Me = { patients: { id: string; name: string }[]; family: { owner_name: string } };
const me = (fixture: string): Me => (JSON.parse(readFileSync(join(FIXTURES, fixture), "utf8")) as { body: Me }).body;
const TWO = me("me/two-patients.json");
const OTHER_FAMILYS = "5e9edf6d-0a2a-4913-8675-9d07a4100099";

async function open(page: Page, scenario: string, path = "/app/shell-test") {
  const recorder = await useScenario(page, scenario);
  const response = await page.goto(path);
  expect(response?.status(), `${path} under ${scenario}`).toBe(200);
  await page.waitForLoadState("load");
  return recorder;
}

const sidebar = (page: Page) => page.locator(".app-shell__sidebar");
const tabBar = (page: Page) => page.locator(".app-shell__tabs .tab-bar");
const menu = (page: Page) => page.getByRole("button", { name: t("bn", "tabs.label") });

test.describe("the three layouts", () => {
  test("theLayoutIsPhoneBelow900", async ({ page }) => {
    await page.setViewportSize({ width: 899, height: 900 });
    await open(page, "me-one");
    await expect(tabBar(page)).toBeVisible();
    await expect(sidebar(page)).toBeHidden();
    await expect(menu(page), "no sidebar to open on a phone").toBeHidden();
    // Every tab is a 48 px target.
    for (const box of await tabBar(page).locator("a").evaluateAll((as) => as.map((a) => a.getBoundingClientRect().height))) {
      expect(box).toBeGreaterThanOrEqual(48);
    }
  });

  test("theSidebarHidesBehindTheToolbarFrom900To1199", async ({ page }) => {
    await page.setViewportSize({ width: 1024, height: 800 });
    await open(page, "me-one");
    await expect(sidebar(page)).toBeHidden();
    await expect(tabBar(page), "the phone's tab bar is gone from 900 px").toBeHidden();
    await expect(menu(page)).toHaveAttribute("aria-expanded", "false");
    await menu(page).click();
    await expect(sidebar(page)).toBeVisible();
    await expect(menu(page)).toHaveAttribute("aria-expanded", "true");
    await expect(sidebar(page).getByRole("link", { name: t("bn", "tab.papers") })).toBeVisible();
    await menu(page).click();
    await expect(sidebar(page)).toBeHidden();
    await expect(menu(page)).toHaveAttribute("aria-expanded", "false");
    // Escape closes it too, and the focus goes back to the button.
    await menu(page).click();
    await expect(sidebar(page)).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(sidebar(page)).toBeHidden();
    await expect(menu(page)).toHaveAttribute("aria-expanded", "false");
    await expect(menu(page)).toBeFocused();
  });

  test("threeColumnsFrom1200", async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await open(page, "me-one");
    await expect(menu(page), "nothing to open: the sidebar is always there").toBeHidden();
    await expect(tabBar(page)).toBeHidden();
    const box = (selector: string) => page.locator(selector).evaluate((e) => e.getBoundingClientRect().toJSON() as DOMRect);
    const [side, main, aside] = [await box(".app-shell__sidebar"), await box(".app-shell__main"), await box(".app-shell__aside")];
    expect(side.width).toBe(256);
    expect(aside.width).toBe(400);
    // Side by side, in that order, filling the width.
    expect(side.x).toBe(0);
    expect(main.x).toBeGreaterThanOrEqual(side.x + side.width);
    expect(aside.x).toBeGreaterThanOrEqual(main.x + main.width);
    expect(aside.x + aside.width).toBe(1440);
  });
});

test.describe("whose papers", () => {
  test("twoPatientsAndNoChoiceShowTheChooser", async ({ page }) => {
    await open(page, "me-two");
    await expect(page.getByRole("heading", { name: t("bn", "switch.title") })).toBeVisible();
    await expect(page.getByText(t("bn", "switch.hint"))).toBeVisible();
    const links = page.locator("main a[href^='?patient=']");
    await expect(links).toHaveCount(TWO.patients.length);
    expect(await links.evaluateAll((as) => as.map((a) => a.getAttribute("href")))).toEqual(TWO.patients.map((p) => `?patient=${p.id}`));
    // The toolbar names nobody: the shell did not pick one for the family.
    await expect(page.locator(".patient-switcher")).toHaveCount(0);
  });

  test("anUnknownPatientParamIsNotFound", async ({ page }) => {
    await useScenario(page, "me-one");
    const response = await page.goto(`/app/shell-test?patient=${OTHER_FAMILYS}`);
    expect(response?.status()).toBe(404);
  });

  test("anAskedPatientIsShownAndNamed", async ({ page }) => {
    const second = TWO.patients[1]!;
    await open(page, "me-two", `/app/shell-test?patient=${second.id}`);
    await expect(page.getByRole("button", { name: t("bn", "switch.label", second.name) })).toBeVisible();
  });
});

test.describe("the preview and the session", () => {
  test("aNonPreviewFamilyGets404OnThePreviewPath", async ({ page }) => {
    const recorder = await useScenario(page, "me-preview-off");
    const response = await page.goto("/app/preview/shell-test");
    expect(response?.status(), "a family outside the preview on /app/preview/shell-test").toBe(404);
    expect((await recorder.java()).map((r) => `${r.method} ${r.path}`)).toEqual(["GET /app/api/me"]);
  });

  test("aPreviewFamilyGetsThePreviewPage", async ({ page }) => {
    // The other direction: the gate lets a preview family in, and the tabs stay inside the preview.
    await open(page, "me-one", "/app/preview/shell-test");
    await expect(page.locator(".app-shell")).toBeVisible();
  });

  test("noSessionRedirectsWithTheHeldPlace", async ({ page }) => {
    await useScenario(page, "signed-out");
    const response = await page.request.get("/app/preview/shell-test?x=1", { maxRedirects: 0 });
    expect(response.status()).toBe(307);
    expect(response.headers()["location"]).toBe("/app/?next=%2Fapp%2Fpreview%2Fshell-test%3Fx%3D1");
  });

  test("aMidPage401GoesToSignInWithThePlace", async ({ page }) => {
    // The session ended while the page was open: the switcher's PUT answers 401, and the page goes to sign-in holding
    // the place it was on (master Review Focus 4, the client side).
    await page.setViewportSize({ width: 1440, height: 900 });
    const [first, second] = TWO.patients;
    const place = `/app/shell-test?patient=${first!.id}`;
    const recorder = await open(page, "me-two-put-401", place);
    await page.getByRole("button", { name: t("bn", "switch.label", first!.name) }).click();
    await page.locator("dialog").getByRole("button", { name: second!.name }).click();
    // The address itself, so a page that stayed put fails naming where it stayed.
    const signIn = `/app/?next=${encodeURIComponent(place)}`;
    await expect(page).toHaveURL(new RegExp(`^https?://[^/]+${signIn.replace(/[?.*+^$()[\]{}|\\]/g, "\\$&")}$`));
    expect(recorder.requests().map((r) => `${r.method} ${r.path}`)).toContain("PUT /app/api/active-patient");
  });

  test("signOutEndsAtTheSignInPage", async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    const recorder = await open(page, "me-one-sign-out");
    await sidebar(page).getByRole("button", { name: t("bn", "app.logout") }).click();
    await page.waitForURL((url) => url.pathname === "/app/");
    const sent = recorder.requests().filter((r) => r.path === "/app/api/sign-out");
    expect(sent.map((r) => [r.method, r.headers["x-medioo"], r.headers["content-type"]])).toEqual([["POST", "1", "application/json"]]);
    // The sign-in page itself, not an error page: the number field is there.
    await expect(page.locator('input[name="whatsapp_number"]')).toBeVisible();
  });
});

for (const width of [390, 1024, 1440]) {
  test.describe(`at ${width} px`, () => {
    test.use({ viewport: { width, height: 900 } });
    screenChecks("/app/shell-test", "me-one", { longest: true, disclaimer: true });
  });
}
