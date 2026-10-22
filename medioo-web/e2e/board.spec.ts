import { join } from "node:path";
import { test, type Page } from "@playwright/test";
import { t } from "../src/copy";
import { useScenario } from "./support/browser-api";

/*
 * The screenshot board (plan A7, Wave 3 Shared interfaces): each screen state at phone 390×844 and desktop 1280×800,
 * light and dark, beside the approved prototype scene it was built from. Runs only with BOARD=1 (the `board` project,
 * playwright.config.ts), into test-results/board/<screen>/, which git ignores; the folder is published privately and
 * linked from the PR. No screenshot goes into git.
 */

type Scene = {
  screen: string;
  state: string;
  route: string;
  scenario: string;
  proto: { file: "phone.html" | "desktop.html"; scene: string };
  /** What to do on the page before the picture (type a number, press a button). */
  act?: (page: Page) => Promise<void>;
  /** The widths to take it at, when not the phone and desktop pair (the shell: 390, 1024 and 1440, plan W4). */
  viewports?: Record<string, { width: number; height: number }>;
  /** "catalog": the page exists only in the catalog build (/app/shell-test), served on the catalog server. */
  build?: "catalog";
};

const number = async (page: Page) => {
  await page.locator('input[name="whatsapp_number"]').fill("01700000123");
  await page.getByRole("button", { name: t("bn", "login.next") }).click();
};
const code = async (page: Page) => {
  await page.locator('input[name="code"]').fill("123456");
  await page.getByRole("button", { name: t("bn", "login.enter") }).click();
};

export const BOARD_SCENES: Scene[] = [
  { screen: "signin", state: "welcome", route: "/app", scenario: "signed-out", proto: { file: "phone.html", scene: "welcome" } },
  {
    screen: "signin",
    state: "number",
    route: "/app",
    scenario: "signed-out",
    proto: { file: "desktop.html", scene: "signin" },
    act: async (page) => {
      await number(page);
      await page.locator("[data-number-back]").waitFor();
    },
  },
  {
    screen: "signin",
    state: "error",
    route: "/app",
    scenario: "signed-out-invalid",
    proto: { file: "phone.html", scene: "welcome" },
    act: async (page) => {
      await number(page);
      await page.locator('[aria-invalid="true"]').waitFor();
    },
  },
  { screen: "signin", state: "code-sent", route: "/app/verify", scenario: "state-pending-sent", proto: { file: "desktop.html", scene: "signin" } },
  {
    screen: "signin",
    state: "code-not-sent",
    route: "/app/verify",
    scenario: "state-pending-not-sent",
    proto: { file: "desktop.html", scene: "signin" },
  },
  {
    screen: "signin",
    state: "wrong-code",
    route: "/app/verify",
    scenario: "state-code-wrong",
    proto: { file: "desktop.html", scene: "signin" },
    act: async (page) => {
      await code(page);
      await page.locator('[aria-invalid="true"]').waitFor();
    },
  },
  {
    // Done: the right code pressed, the page leaving for the held place (held here, so the picture is of this page).
    screen: "signin",
    state: "done",
    route: "/app/verify",
    scenario: "state-pending-sent",
    proto: { file: "desktop.html", scene: "signin" },
    act: async (page) => {
      // A 204 to the navigation leaves this page where it is, the press done and the button still busy.
      await page.route("**/app/documents/**", (route) => route.fulfill({ status: 204 }));
      await code(page);
      await page.getByRole("button", { name: t("bn", "login.enter") }).and(page.locator(":disabled")).waitFor();
    },
  },
];

// The shell (plan W4): its layouts at the three widths that switch it, by patient count. 0, 1 and 2 patients; with two,
// the chooser (nobody remembered) and the switcher once one is asked for (the urgent mark in its sheet); the sidebar
// opened by the toolbar's button at 1024.
const SHELL_WIDTHS = {
  w390: { width: 390, height: 844 },
  w1024: { width: 1024, height: 800 },
  w1440: { width: 1440, height: 900 },
};
const TWO_SECOND = "5e9edf6d-0a2a-4913-8675-9d07a4100012";
const shell = (state: string, scenario: string, route = "/app/shell-test", act?: Scene["act"]): Scene => ({
  screen: "shell",
  state,
  route,
  scenario,
  build: "catalog",
  viewports: SHELL_WIDTHS,
  proto: { file: "desktop.html", scene: "app" },
  act,
});
BOARD_SCENES.push(
  { ...shell("one-patient", "me-one"), proto: { file: "phone.html", scene: "chat" } },
  shell("no-patients", "me-none"),
  shell("two-patients-chooser", "me-two"),
  shell("two-patients-chosen", "me-two", `/app/shell-test?patient=${TWO_SECOND}`),
  shell("two-patients-switcher-open", "me-two", `/app/shell-test?patient=${TWO_SECOND}`, async (page) => {
    await page.locator(".patient-switcher__button").click();
    await page.locator("dialog:modal").waitFor();
  }),
  shell("long-name", "me-long"),
  {
    ...shell("sidebar-open", "me-one", "/app/shell-test", async (page) => {
      await page.locator(".app-shell__menu").click();
      await page.locator(".app-shell__sidebar[data-open]").waitFor();
    }),
    viewports: { w1024: SHELL_WIDTHS.w1024 },
  },
);

const VIEWPORTS = { phone: { width: 390, height: 844 }, desktop: { width: 1280, height: 800 } } as const;
// The catalog build's server (playwright.config.ts starts it on the port after the production one).
const CATALOG_URL = process.env.E2E_CATALOG_URL ?? `http://127.0.0.1:${Number(process.env.E2E_PORT ?? 3217) + 1}`;
const PROTOTYPES = join(__dirname, "..", "..", "docs", "design", "2026-10-21-approved");
const OUT = join(__dirname, "..", "test-results", "board");

for (const scene of BOARD_SCENES) {
  for (const [layout, viewport] of Object.entries(scene.viewports ?? VIEWPORTS)) {
    for (const colorScheme of ["light", "dark"] as const) {
      test(`${scene.screen} ${scene.state} ${layout} ${colorScheme}`, async ({ page }) => {
        test.setTimeout(90_000);
        await page.setViewportSize(viewport);
        await page.emulateMedia({ colorScheme });
        await useScenario(page, scene.scenario);
        await page.goto(scene.build === "catalog" ? new URL(scene.route, CATALOG_URL).href : scene.route);
        await page.evaluate(() => document.fonts.ready.then(() => undefined));
        if (scene.act) await scene.act(page);
        await page.screenshot({ path: join(OUT, scene.screen, `${scene.state}-${layout}-${colorScheme}.png`), fullPage: true });

        const proto = await page.context().newPage();
        await proto.setViewportSize(viewport);
        await proto.emulateMedia({ colorScheme });
        await proto.goto(`file://${join(PROTOTYPES, scene.proto.file)}`);
        // The scene bar can be hidden at a width the prototype was not drawn for; the click is the prototype's own.
        await proto.evaluate((s) => document.querySelector<HTMLElement>(`[data-scene="${s}"]`)?.click(), scene.proto.scene);
        await proto.waitForTimeout(600);
        await proto.screenshot({ path: join(OUT, scene.screen, `${scene.state}-${layout}-${colorScheme}-prototype.png`) });
        await proto.close();
      });
    }
  }
}
