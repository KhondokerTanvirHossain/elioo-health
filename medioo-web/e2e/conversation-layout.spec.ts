import { readdirSync, readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { join } from "node:path";
import { expect, test, type Page } from "@playwright/test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import type { MeJson, MePatient } from "../src/api/types";
import { t, type Lang } from "../src/copy";

/*
 * The conversation's header laid out by a real browser (plan desktop-parity Task 7, review finding 1; Review Focus 4).
 * On the desktop the footer disclaimer is gone (X1) and the composer carries no standing line, so the header's
 * `desk.talk.sub` (whose papers this is about, then "not a doctor") is the only place the screen says it is not a
 * doctor, and the not-a-doctor clause comes last, after the patient's possessive name. A long Bangla name must never push that clause
 * out of sight: no ellipsis, no clipping, no spilling out of the header under the thread. jsdom checks text, not what
 * is visible, so only a browser can see it.
 *
 * The markup is the real DesktopShell with the real Conversation in its talk column (rendered to HTML here), styled by
 * globals.css, tokens.css and every component stylesheet, with the real Noto Sans Bengali; the column's width therefore
 * comes from DesktopShell.css at each window width. Nothing is served, so this needs no build and no stub Java.
 */

const ROOT = join(__dirname, "..");

// `server-only` throws outside a React Server Components build, and the conversation's words are read on the server
// (chat-words.ts). Here the markup is rendered in the test process, as vitest.config.mts aliases it for unit tests: the
// module is stood in for before the conversation and the shell are first loaded (in mount, below), never in the app.
const load = createRequire(__filename);
const serverOnly = load.resolve("server-only");
load.cache[serverOnly] = { id: serverOnly, filename: serverOnly, loaded: true, exports: {} } as NodeJS.Module;
// The conversation's islands and stylesheets come through next/dynamic loaders (desk-load.tsx, DR-42), which render
// nothing outside Next's build until their chunk arrives. Here, as under Vitest, next/dynamic is the stand-in that
// renders each loaded component itself (src/test/next-dynamic.ts), awaited before the markup is rendered (mount).
const dynamicStandIn = load("../src/test/next-dynamic") as typeof import("../src/test/next-dynamic");
const nextDynamic = load.resolve("next/dynamic");
load.cache[nextDynamic] = { id: nextDynamic, filename: nextDynamic, loaded: true, exports: dynamicStandIn } as NodeJS.Module;
const SRC = join(ROOT, "src");
const font = (file: string) => `url(data:font/woff2;base64,${readFileSync(join(ROOT, "public", "fonts", file)).toString("base64")})`;
function stylesheets(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((e) =>
    e.isDirectory() ? stylesheets(join(dir, e.name)) : e.name.endsWith(".css") ? [join(dir, e.name)] : [],
  );
}
const COMPONENT_CSS = stylesheets(join(SRC, "components"));
const CSS = [
  readFileSync(join(SRC, "styles", "tokens.css"), "utf8"),
  readFileSync(join(SRC, "app", "globals.css"), "utf8")
    .replace("url(/fonts/noto-sans-bengali.woff2)", font("noto-sans-bengali.woff2"))
    .replace("url(/fonts/noto-sans-latin.woff2)", font("noto-sans-latin.woff2")),
  ...COMPONENT_CSS.map((f) => readFileSync(f, "utf8")),
].join("\n");

/** The longest possessive name among the /me fixtures Java wrote: the name most likely to push the clause out. */
const LONGEST: MePatient = readdirSync(join(ROOT, "fixtures", "api", "me"))
  .filter((f) => f.endsWith(".json"))
  .flatMap((f) => (JSON.parse(readFileSync(join(ROOT, "fixtures", "api", "me", f), "utf8")) as { body: Partial<MeJson> }).body.patients ?? [])
  .reduce((a, b) => (b.name_possessive.length > a.name_possessive.length ? b : a));

/** The window widths of Review Focus 4: the narrow desktop (360 px column), the common laptop, and the wide window. */
const WIDTHS = [900, 1024, 1440] as const;
const LANGS: readonly Lang[] = ["bn", "en"];

async function mount(page: Page, lang: Lang, width: number) {
  await page.setViewportSize({ width, height: 800 });
  const { Conversation } = load("../src/components/Conversation/Conversation") as typeof import("../src/components/Conversation/Conversation");
  const { DesktopShell } = load("../src/components/DesktopShell/DesktopShell") as typeof import("../src/components/DesktopShell/DesktopShell");
  const talk = createElement(Conversation, {
    lang,
    preview: true,
    patient: LONGEST,
    items: [],
    selectedId: null,
    hrefFor: (id: string) => `/app/home?paper=${id}`,
  });
  // The parts' desk modules, loaded here first: Playwright maps the @/ paths of a module required while the test runs,
  // not of one a loader's import() reaches later, so the loaders then find them already loaded.
  load("../src/components/DesktopShell/DesktopShell.desk");
  load("../src/components/safety/NowBanner/NowBanner.desk");
  load("../src/components/Conversation/Conversation.desk");
  await dynamicStandIn.loadedDynamics();
  const shell = renderToStaticMarkup(createElement(DesktopShell, { lang, preview: true, sidebar: null, paper: null, talk }));
  await page.setContent(`<!doctype html><html lang="${lang}"><head><style>${CSS}</style></head><body>${shell}</body></html>`);
  await page.evaluate(() => document.fonts.ready);
}

/** The header's line as the browser laid it out. */
async function measure(page: Page) {
  return page.locator(".conversation__sub").evaluate((sub: HTMLElement) => {
    const head = sub.closest(".conversation__head")!.getBoundingClientRect();
    const talk = sub.closest(".desktop-shell__talk")!.getBoundingClientRect();
    const box = sub.getBoundingClientRect();
    // The width the line would need on one line, in the same font: the check is vacuous unless this exceeds the room.
    const probe = sub.cloneNode(true) as HTMLElement;
    probe.style.cssText = "position:absolute;visibility:hidden;white-space:nowrap;width:auto;max-width:none";
    sub.parentElement!.appendChild(probe);
    const oneLine = probe.getBoundingClientRect().width;
    probe.remove();
    return {
      text: sub.textContent,
      font: document.fonts.check(`12px "Noto Sans Bengali"`, String.fromCodePoint(0x0995)),
      talkWidth: talk.width,
      scrollWidth: sub.scrollWidth,
      clientWidth: sub.clientWidth,
      scrollHeight: sub.scrollHeight,
      clientHeight: sub.clientHeight,
      textOverflow: getComputedStyle(sub).textOverflow,
      oneLine,
      // How far the line runs past the header's box (positive = cut off or lying under the thread).
      pastRight: box.right - head.right,
      pastBottom: box.bottom - head.bottom,
    };
  });
}

test.describe("the conversation header", () => {
  test("theFixtureNameIsLong", () => {
    expect(LONGEST.name_possessive.length, "the longest fixture possessive is a long Bangla name").toBeGreaterThan(20);
  });

  for (const lang of LANGS) {
    for (const width of WIDTHS) {
      test(`theNotADoctorLineIsNeverClipped ${lang} ${width}px`, async ({ page }) => {
        await mount(page, lang, width);
        const m = await measure(page);
        expect(m.font, "Noto Sans Bengali is the font measured").toBe(true);
        expect(m.text, "the header's line is desk.talk.sub over the name, whole").toBe(t(lang, "desk.talk.sub", LONGEST.name_possessive));
        if (width === 900) {
          expect(m.talkWidth, "at 900 px the conversation column is the narrow one").toBeCloseTo(360, 0);
          // Not vacuous: the line does not fit on one line in the room the header leaves it.
          expect(m.oneLine, `the line needs ${m.oneLine} px on one line, more than the ${m.clientWidth} px it has`).toBeGreaterThan(m.clientWidth);
        }
        expect(
          m.scrollWidth,
          `the not-a-doctor clause is clipped at ${width} px (${lang}): the line is ${m.scrollWidth} px wide in ${m.clientWidth} px (text-overflow ${m.textOverflow})`,
        ).toBeLessThanOrEqual(m.clientWidth);
        // A pixel of slack: Noto's Bangla glyphs reach a rounding past a 1.3 line box, which is drawn, not cut off.
        expect(m.scrollHeight, `the line is cut off below at ${width} px (${lang})`).toBeLessThanOrEqual(m.clientHeight + 1);
        expect(m.pastRight, `the line runs ${m.pastRight} px past the header's right edge`).toBeLessThanOrEqual(0.5);
        expect(m.pastBottom, `the line hangs ${m.pastBottom} px below the header, under the thread`).toBeLessThanOrEqual(0.5);
      });
    }
  }
});
