import { join } from "node:path";
import { test } from "@playwright/test";
import { BOARD_SCENES, VIEWPORTS, protoFor } from "./support/board-scenes";
import { useScenario } from "./support/browser-api";

export { BOARD_SCENES, VIEWPORTS };

/*
 * The screenshot board (plan A7, Wave 3 Shared interfaces): each screen state at phone 390×844 and desktop 1280×800,
 * light and dark, beside the approved prototype scene it was built from. Runs only with BOARD=1 (the `board` project,
 * playwright.config.ts), into test-results/board/<screen>/, which git ignores; the folder is published privately and
 * linked from the PR. No screenshot goes into git.
 */

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
        if (scene.clock) await page.clock.install();
        await page.goto(scene.build === "catalog" ? new URL(scene.route, CATALOG_URL).href : scene.route);
        await page.evaluate(() => document.fonts.ready.then(() => undefined));
        if (scene.act) await scene.act(page);
        if (scene.fold) {
          await page.waitForLoadState("load");
          await page.screenshot({ path: join(OUT, scene.screen, `${scene.state}-${layout}-${colorScheme}-fold.png`) });
        }
        const shot = join(OUT, scene.screen, `${scene.state}-${layout}-${colorScheme}.png`);
        if (scene.element) await page.locator(scene.element).screenshot({ path: shot });
        else await page.screenshot({ path: shot, fullPage: true });

        // From 900 px the desktop layout, beside desktop.html; below it the phone's (board-scenes.test.ts guards it).
        const design = protoFor(scene, viewport.width);
        const proto = await page.context().newPage();
        await proto.setViewportSize(viewport);
        await proto.emulateMedia({ colorScheme });
        await proto.goto(`file://${join(PROTOTYPES, design.file)}`);
        // The scene bar can be hidden at a width the prototype was not drawn for; the click is the prototype's own.
        await proto.evaluate((s) => document.querySelector<HTMLElement>(`[data-scene="${s}"]`)?.click(), design.scene);
        await proto.waitForTimeout(600);
        if (design.click) {
          await proto.evaluate((s) => document.querySelector<HTMLElement>(s)?.click(), design.click);
          await proto.waitForTimeout(600);
        }
        if (design.show) await proto.evaluate((s) => document.querySelector(s)?.classList.add("on"), design.show);
        if (design.act) {
          await design.act(proto);
          await proto.waitForTimeout(600);
        }
        await proto.screenshot({ path: join(OUT, scene.screen, `${scene.state}-${layout}-${colorScheme}-prototype.png`) });
        await proto.close();
      });
    }
  }
}
