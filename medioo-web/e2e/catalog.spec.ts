import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";
import { NOW_ALLOWED } from "../src/styles/now-usage";

/*
 * The component catalog (/app/catalog-test, catalog build only), in the `catalog` project: every registered state, in
 * both languages, under the checks no unit test can make because they are properties of what the browser paints. Each
 * runs at the phone and the desktop width, in both colour schemes. A state registered in src/catalog/states.tsx is
 * covered here without an edit.
 */

const VIEWPORTS = {
  phone: { width: 390, height: 844 },
  desktop: { width: 1280, height: 800 },
} as const;
const SCHEMES = ["light", "dark"] as const;

/** Loads the catalog with the CSP-violation hook installed from the first byte, every eager image loaded. */
async function open(page: Page) {
  await page.addInitScript(() => {
    const w = window as unknown as { __csp: string[] };
    w.__csp = [];
    document.addEventListener("securitypolicyviolation", (e) => {
      w.__csp.push(`${e.violatedDirective} blocked ${e.blockedURI || "inline"}`);
    });
  });
  const response = await page.goto("/app/catalog-test");
  expect(response?.status(), "the catalog build serves /app/catalog-test").toBe(200);
  await page.waitForLoadState("load");
}

for (const [layout, viewport] of Object.entries(VIEWPORTS)) {
  for (const colorScheme of SCHEMES) {
    test.describe(`${layout} ${colorScheme}`, () => {
      test.use({ viewport, colorScheme });

      test("everyRegisteredStateRenders", async ({ page }) => {
        await open(page);
        const registered = Number(await page.locator("main").getAttribute("data-catalog-states"));
        // W2c registers 13 states (Wordmark 1, MioAvatar 3, MessageBubble 3, StatusChip 6); later PRs raise it.
        expect(registered, "registered states").toBeGreaterThanOrEqual(13);
        const sections = await page.$$eval("[data-catalog-state]", (els) =>
          els.map((el) => {
            const h2 = el.querySelector(":scope > h2");
            const rendered = [...el.children].filter((c) => c !== h2);
            const box = rendered.reduce((w, c) => Math.max(w, c.getBoundingClientRect().width), 0);
            return { state: el.getAttribute("data-catalog-state")!, lang: el.getAttribute("lang"), box };
          }),
        );
        expect(sections.length, "one section per state per language").toBe(registered * 2);
        expect(sections.length).toBeGreaterThanOrEqual(26);
        const byState = new Map<string, string[]>();
        for (const s of sections) byState.set(s.state, [...(byState.get(s.state) ?? []), s.lang ?? "none"]);
        expect(byState.size, "distinct states").toBe(registered);
        expect([...byState].filter(([, langs]) => langs.join() !== "bn,en"), "states not shown once in bn, then en").toEqual([]);
        expect(sections.filter((s) => s.box === 0).map((s) => `${s.state} (${s.lang})`), "states that render nothing").toEqual([]);
        // The registry's exact list is src/catalog/states.test.tsx's: this page's own count above cannot see a state
        // dropped from the registry, only one the page failed to render.
      });

      test("everyVisibleImageIsADecodedBrandFile", async ({ page }) => {
        // A box with a width says nothing about an image: an <img width=48> whose file 404s or fails to decode still
        // has one. So every visible image is checked by its content: loaded, decoded, and one of the approved files.
        await open(page);
        const BRAND = ["/brand/medioo-light.svg", "/brand/medioo-dark.svg", "/brand/mio.webp", "/brand/mio-head.webp"];
        // Mio's eager images can sit below the fold; scroll each into view so the check waits on every one of them.
        await page.$$eval("[data-catalog-state] img", (imgs) => imgs.forEach((i) => i.scrollIntoView()));
        await page.waitForFunction(() =>
          [...document.querySelectorAll<HTMLImageElement>("[data-catalog-state] img")]
            .filter((i) => i.checkVisibility())
            .every((i) => i.complete),
        );
        const images = await page.$$eval("[data-catalog-state] img", (imgs) =>
          (imgs as HTMLImageElement[])
            .filter((i) => i.checkVisibility())
            .map((i) => ({
              state: i.closest<HTMLElement>("[data-catalog-state]")!.dataset.catalogState!,
              path: i.currentSrc ? new URL(i.currentSrc).pathname : "",
              complete: i.complete,
              natural: i.naturalWidth,
            })),
        );
        const bad = images
          .filter((i) => !i.complete || i.natural === 0 || !BRAND.includes(i.path))
          .map((i) => `${i.state}: ${i.path || "no currentSrc"} complete=${i.complete} naturalWidth=${i.natural}`);
        expect(bad).toEqual([]);
        // Guard: the images the W2c states draw were all seen, so the empty list above is not vacuous. Two per
        // language for Wordmark is one: the lazy theme overrides are hidden without data-theme.
        const seen = new Set(images.map((i) => `${i.state} ${i.path}`));
        for (const want of [
          "MioAvatar/32 /brand/mio-head.webp",
          "MioAvatar/48-still /brand/mio-head.webp",
          "MioAvatar/96 /brand/mio.webp",
          `Wordmark/default /brand/medioo-${colorScheme}.svg`,
        ]) {
          expect(seen, "visible, decoded images").toContain(want);
        }
      });

      test("axeReportsNoViolation", async ({ page }) => {
        await open(page);
        // The theme under test is the one painted (reading.spec.ts's guard): the body paints the active scheme's --bg,
        // and the other scheme paints a different one.
        const paint = () =>
          page.evaluate(() => {
            const swatch = document.createElement("div");
            swatch.style.setProperty("background-color", "var(--bg)");
            document.body.append(swatch);
            const token = getComputedStyle(swatch).backgroundColor;
            swatch.remove();
            return { dark: matchMedia("(prefers-color-scheme: dark)").matches, token, body: getComputedStyle(document.body).backgroundColor };
          });
        const active = await paint();
        expect(active.dark, "prefers-color-scheme is emulated").toBe(colorScheme === "dark");
        expect(active.body, "the body paints the active scheme's --bg").toBe(active.token);
        const other = colorScheme === "dark" ? "light" : "dark";
        await page.emulateMedia({ colorScheme: other });
        expect((await paint()).body, `the ${other} theme paints a different background`).not.toBe(active.body);
        await page.emulateMedia({ colorScheme });

        const results = await new AxeBuilder({ page }).analyze();
        expect(results.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(" ")).join(", ")}`)).toEqual([]);
        // An analysis that checked nothing would also report nothing.
        expect(results.passes.length).toBeGreaterThan(0);
      });

      test("noElementOverflowsItsBox", async ({ page }) => {
        await open(page);
        const result = await page.evaluate(() => {
          const sections = [...document.querySelectorAll<HTMLElement>("[data-catalog-state]")];
          const over: string[] = [];
          const measured: string[] = [];
          const empty: string[] = [];
          for (const section of sections) {
            const state = section.dataset.catalogState!;
            measured.push(state);
            // The heading and at least one element of the state itself, or the inner loop measured nothing there.
            if (section.querySelectorAll("*").length < 2) empty.push(state);
            for (const el of [section, ...section.querySelectorAll("*")]) {
              if (el.scrollWidth > el.clientWidth + 1) {
                over.push(`${state} <${el.tagName.toLowerCase()} class="${el.getAttribute("class") ?? ""}"> ${el.scrollWidth} > ${el.clientWidth}`);
              }
            }
          }
          const root = document.documentElement;
          return { over, measured, empty, page: [root.scrollWidth, root.clientWidth] };
        });
        expect(result.over).toEqual([]);
        expect(result.page[0], "the page itself scrolls sideways").toBeLessThanOrEqual(result.page[1]! + 1);
        // Guards: every section measured at least one element of its state, the W2c floor of sections was walked
        // (13 states × 2 languages; states.test.tsx holds the exact list), and the long unbroken bubble among them.
        expect(result.empty, "sections whose state has no element to measure").toEqual([]);
        expect(result.measured.length).toBeGreaterThanOrEqual(26);
        expect(result.measured).toContain("MessageBubble/long-unbroken");
      });

      test("onlyNowComponentsPaintTheNowRed", async ({ page }) => {
        await open(page);
        const result = await page.evaluate((allowSource) => {
          const allow = new RegExp(allowSource);
          // The active theme's NOW tokens, resolved by the browser to the spelling getComputedStyle reports.
          const swatch = document.createElement("div");
          document.body.append(swatch);
          const resolved = (prop: string, token: string) => {
            swatch.style.setProperty(prop, `var(${token})`);
            const value = getComputedStyle(swatch).getPropertyValue(prop);
            swatch.style.removeProperty(prop);
            return value;
          };
          const tokens = ["--now", "--now-ink", "--now-soft"];
          const now = new Set(tokens.flatMap((t) => [resolved("color", t), resolved("background-color", t)]));
          swatch.remove();
          const PROPS = [
            "color",
            "background-color",
            "fill",
            "stroke",
            "border-top-color",
            "border-right-color",
            "border-bottom-color",
            "border-left-color",
            "outline-color",
          ];
          const allowed = (el: Element | null): boolean => {
            for (let e = el; e; e = e.parentElement) if ([...e.classList].some((c) => allow.test(`.${c}`))) return true;
            return false;
          };
          const bad: string[] = [];
          const painted: string[] = [];
          for (const section of document.querySelectorAll<HTMLElement>("[data-catalog-state]")) {
            const state = section.dataset.catalogState!;
            for (const el of [section, ...section.querySelectorAll("*")]) {
              const style = getComputedStyle(el);
              for (const prop of PROPS) {
                const value = style.getPropertyValue(prop);
                if (!now.has(value)) continue;
                painted.push(state);
                if (!allowed(el)) bad.push(`${state} <${el.tagName.toLowerCase()} class="${el.getAttribute("class") ?? ""}"> ${prop}: ${value}`);
              }
            }
          }
          return { bad: [...new Set(bad)], painted: [...new Set(painted)], now: [...now] };
        }, NOW_ALLOWED.source);
        expect(result.now.length, "the NOW tokens resolved").toBeGreaterThanOrEqual(3);
        expect(result.bad).toEqual([]);
        // Guard: the red was found where it belongs (the critical chips), so the empty list above is not vacuous.
        expect(result.painted).toEqual(expect.arrayContaining(["StatusChip/crit-high", "StatusChip/crit-low"]));
      });

      test("noTextUnderTwelvePixels", async ({ page }) => {
        await open(page);
        const result = await page.evaluate(() => {
          const small: string[] = [];
          let texts = 0;
          for (const section of document.querySelectorAll<HTMLElement>("[data-catalog-state]")) {
            const walker = document.createTreeWalker(section, NodeFilter.SHOW_TEXT);
            for (let node = walker.nextNode(); node; node = walker.nextNode()) {
              if (!node.textContent?.trim()) continue;
              texts++;
              const size = parseFloat(getComputedStyle(node.parentElement!).fontSize);
              if (size < 12) small.push(`${section.dataset.catalogState}: "${node.textContent.trim().slice(0, 20)}" ${size}px`);
            }
          }
          return { small, texts };
        });
        expect(result.small).toEqual([]);
        expect(result.texts, "text nodes measured").toBeGreaterThan(0);
      });

      test("theWordmarkPaintsTheThemesFile", async ({ page }) => {
        const requested: string[] = [];
        page.on("request", (r) => {
          const path = new URL(r.url()).pathname;
          if (path.startsWith("/brand/medioo-")) requested.push(path);
        });
        await open(page);
        const visible = () =>
          page.$$eval('[data-catalog-state="Wordmark/default"] img', (imgs) =>
            (imgs as HTMLImageElement[])
              .filter((i) => i.getBoundingClientRect().width > 0)
              .map((i) => ({ src: i.currentSrc, natural: i.naturalWidth, complete: i.complete })),
          );
        const file = (scheme: string) => `medioo-${scheme}.svg`;

        // No data-theme: the system scheme picks the file, and only that file crosses the wire.
        const shown = await visible();
        expect(shown.length, "one visible wordmark per language section").toBe(2);
        for (const img of shown) {
          expect(img.src.endsWith(file(colorScheme)), `${img.src} in ${colorScheme}`).toBe(true);
          expect(img.natural, "the file decoded").toBeGreaterThan(0);
        }
        expect([...new Set(requested)], "wordmark files fetched").toEqual([`/brand/${file(colorScheme)}`]);

        // data-theme overrides the system scheme, in CSS: the other theme's file is shown and decodes.
        const other = colorScheme === "dark" ? "light" : "dark";
        await page.evaluate((theme) => document.documentElement.setAttribute("data-theme", theme), other);
        await expect
          .poll(async () => (await visible()).map((i) => i.complete && i.natural > 0 && i.src.endsWith(file(other))))
          .toEqual([true, true]);
      });

      test("noCspViolation", async ({ page }) => {
        await open(page);
        const hooked = await page.evaluate(() => Array.isArray((window as unknown as { __csp?: string[] }).__csp));
        expect(hooked, "the violation hook is installed").toBe(true);
        expect(await page.evaluate(() => (window as unknown as { __csp: string[] }).__csp)).toEqual([]);
      });
    });
  }
}
