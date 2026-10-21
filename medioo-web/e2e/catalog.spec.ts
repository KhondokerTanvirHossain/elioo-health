import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";
import { CONTROL_CLASS, FIELD_SELECTOR, NOW_ALLOWED } from "../src/styles/now-usage";

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
        // W2c registers 13 states (Wordmark 1, MioAvatar 3, MessageBubble 3, StatusChip 6), W2d-b 6 (NowBanner);
        // later PRs raise it.
        expect(registered, "registered states").toBeGreaterThanOrEqual(19);
        const sections = await page.$$eval("[data-catalog-state]", (els) =>
          els.map((el) => {
            const h2 = el.querySelector(":scope > h2");
            const rendered = [...el.children].filter((c) => c !== h2);
            const box = rendered.reduce((w, c) => Math.max(w, c.getBoundingClientRect().width), 0);
            return {
              state: el.getAttribute("data-catalog-state")!,
              lang: el.getAttribute("lang"),
              box,
              elements: rendered.length,
              empty: el.hasAttribute("data-catalog-empty"),
            };
          }),
        );
        expect(sections.length, "one section per state per language").toBe(registered * 2);
        expect(sections.length).toBeGreaterThanOrEqual(38);
        const byState = new Map<string, string[]>();
        for (const s of sections) byState.set(s.state, [...(byState.get(s.state) ?? []), s.lang ?? "none"]);
        expect(byState.size, "distinct states").toBe(registered);
        expect([...byState].filter(([, langs]) => langs.join() !== "bn,en"), "states not shown once in bn, then en").toEqual([]);
        expect(
          sections.filter((s) => !s.empty && s.box === 0).map((s) => `${s.state} (${s.lang})`),
          "states that render nothing",
        ).toEqual([]);
        // A state marked empty (the banner Java says not to show) paints no element at all, in either language.
        expect(
          sections.filter((s) => s.empty && s.elements > 0).map((s) => `${s.state} (${s.lang}): ${s.elements} elements`),
          "empty states that render something",
        ).toEqual([]);
        expect(sections.filter((s) => s.empty).map((s) => s.state)).toEqual(["NowBanner/hidden", "NowBanner/hidden"]);
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
            // The heading and at least one element of the state itself, or the inner loop measured nothing there. A
            // state marked empty renders nothing by design, and everyRegisteredStateRenders holds it to that.
            if (!section.hasAttribute("data-catalog-empty") && section.querySelectorAll("*").length < 2) empty.push(state);
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
        // Guards: every section measured at least one element of its state, the floor of sections was walked
        // (19 states × 2 languages; states.test.tsx holds the exact list), and the long names among them: the unbroken
        // bubble (W2c) and the NOW banner's title with a long name, spaced and unbroken (W2d-b).
        expect(result.empty, "sections whose state has no element to measure").toEqual([]);
        expect(result.measured.length).toBeGreaterThanOrEqual(38);
        expect(result.measured).toContain("MessageBubble/long-unbroken");
        expect(result.measured).toContain("NowBanner/long-name");
        expect(result.measured).toContain("NowBanner/long-unbroken");
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
        // Guard: the red was found where it belongs (the critical chips and the NOW banner), so the empty list above is
        // not vacuous.
        expect(result.painted).toEqual(
          expect.arrayContaining(["StatusChip/crit-high", "StatusChip/crit-low", "NowBanner/one-patient", "NowBanner/abroad"]),
        );
        // And never on the banner Java said not to show.
        expect(result.painted).not.toContain("NowBanner/hidden");
      });

      test("everyFieldAndTrackDrawsTheBoundary", async ({ page }) => {
        // Q1 (A), PR #120, on what the browser paints: every field and track is washed with --fill and draws --boundary
        // as an edge, at rest, hovered and focused, with no data-theme (what a family gets: the tokens follow this
        // system scheme) and under data-theme light and dark over it. This sees what the CSS scan in
        // built/now-usage.built.test.ts cannot: source order, a later rule that recolours the edge
        // (`border-color: var(--focus)` on focus), and a class only the markup puts on the element.
        await open(page);
        const controls = await page.evaluate(
          ({ fields, controlClass }) => {
            const re = new RegExp(controlClass);
            const found = [...document.querySelectorAll<HTMLElement>("[data-catalog-state] *")].filter(
              (el) => el.matches(fields) || [...el.classList].some((c) => re.test(`.${c}`)),
            );
            return found.map((el, i) => {
              el.setAttribute("data-boundary-check", String(i));
              return {
                state: el.closest<HTMLElement>("[data-catalog-state]")!.dataset.catalogState!,
                tag: el.tagName.toLowerCase(),
                classes: [...el.classList],
                // Whether the built-CSS scan can see this element at all: it judges a component's field only by a
                // class on the element itself that CONTROL_CLASS matches.
                scanned: [...el.classList].some((c) => re.test(`.${c}`)),
              };
            });
          },
          { fields: FIELD_SELECTOR, controlClass: CONTROL_CLASS.source },
        );

        // The CSS scan's coverage, derived from what the DOM holds rather than from a list of the classes the author
        // remembered: a field whose own element carries no CONTROL_CLASS class is a field built/now-usage never judges.
        expect(
          controls
            .filter((c) => !c.scanned)
            .map((c) => `${c.state} ${c.tag}.${c.classes.join(".")} carries no CONTROL_CLASS class, so built/now-usage cannot judge it`),
          "fields the built-CSS scan cannot see",
        ).toEqual([]);

        const bad: string[] = [];
        for (const theme of [null, "light", "dark"] as const) {
          const themeName = theme ?? "(system)";
          await page.evaluate((t) => {
            if (t === null) document.documentElement.removeAttribute("data-theme");
            else document.documentElement.setAttribute("data-theme", t);
          }, theme);
          // The theme's --fill and --boundary as getComputedStyle spells a colour, read off a swatch.
          const tokens = await page.evaluate(() => {
            const swatch = document.createElement("div");
            document.body.append(swatch);
            const resolved = (token: string) => {
              const raw = getComputedStyle(document.documentElement).getPropertyValue(token).trim();
              swatch.style.setProperty("background-color", raw);
              const value = raw === "" ? "" : getComputedStyle(swatch).backgroundColor;
              swatch.style.removeProperty("background-color");
              return value;
            };
            const out = { fill: resolved("--fill"), boundary: resolved("--boundary") };
            swatch.remove();
            return out;
          });
          const themed = await page.evaluate(() => document.documentElement.getAttribute("data-theme"));
          expect(themed, `data-theme is ${themeName}`).toBe(theme);
          expect(tokens.fill, `--fill resolves under data-theme=${themeName}`).not.toBe("");
          expect(tokens.boundary, `--boundary resolves under data-theme=${themeName}`).not.toBe("");

          for (const mode of ["rest", "hover", "focus"] as const) {
            for (const [i, control] of controls.entries()) {
              const el = page.locator(`[data-boundary-check="${i}"]`);
              await page.mouse.move(0, 0);
              await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur());
              if (mode === "hover") await el.hover();
              if (mode === "focus") await el.focus();
              const problems = await el.evaluate(
                (node, { fill, boundary, mode }) => {
                  const cs = getComputedStyle(node);
                  const visible = (style: string) => style !== "none" && style !== "hidden";
                  const out: string[] = [];
                  // Each pass is measured in the state it claims, or it silently re-measures rest: an overlay, pointer
                  // emulation or a scroll race can leave el.hover() landing without :hover matching.
                  if (mode === "rest" && (node.matches(":hover") || node.matches(":focus"))) out.push("not at rest");
                  if (mode === "hover" && !node.matches(":hover")) out.push("not hovered");
                  if (mode === "focus" && !node.matches(":focus-visible")) out.push("not focus-visible");
                  if (cs.backgroundColor !== fill) out.push(`background-color ${cs.backgroundColor}, not --fill ${fill}`);
                  const sides = ["top", "right", "bottom", "left"];
                  const border = sides.some(
                    (side) =>
                      parseFloat(cs.getPropertyValue(`border-${side}-width`)) > 0 &&
                      visible(cs.getPropertyValue(`border-${side}-style`)) &&
                      cs.getPropertyValue(`border-${side}-color`) === boundary,
                  );
                  const outline = parseFloat(cs.outlineWidth) > 0 && visible(cs.outlineStyle) && cs.outlineColor === boundary;
                  // A ring: the boundary's colour, offsets and blur 0, a spread of at least 1px ("<colour> 0px 0px 0px 1px").
                  const shadows = cs.boxShadow === "none" ? [] : cs.boxShadow.split(/,(?![^(]*\))/);
                  const ring = shadows.some((shadow) => {
                    const m = /^\s*(rgba?\([^)]*\))\s+(.*)$/.exec(shadow);
                    if (!m || m[1] !== boundary) return false;
                    const px = m[2]!.split(/\s+/).filter((w) => w.endsWith("px")).map(parseFloat);
                    return px.length === 4 && px[0] === 0 && px[1] === 0 && px[2] === 0 && px[3]! >= 1;
                  });
                  if (!border && !outline && !ring) {
                    const edges = sides.map(
                      (side) =>
                        `${side} ${cs.getPropertyValue(`border-${side}-width`)} ${cs.getPropertyValue(`border-${side}-style`)} ${cs.getPropertyValue(`border-${side}-color`)}`,
                    );
                    out.push(`draws no --boundary ${boundary} (border ${edges.join(", ")}; outline ${cs.outlineWidth} ${cs.outlineStyle} ${cs.outlineColor})`);
                  }
                  return out;
                },
                { ...tokens, mode },
              );
              for (const p of problems) bad.push(`${control.state} .${control.classes.join(".")} ${mode} data-theme=${themeName}: ${p}`);
            }
          }
        }
        expect(bad).toEqual([]);
        // Guards: fields were checked at this layout and scheme, so the empty lists above are not vacuous, and the
        // W2e-a fields, invalid included, were among them. A later PR that ships a field or a segmented track adds its
        // class here.
        expect(controls.length, "fields and tracks checked").toBeGreaterThan(0);
        const checked = new Set(controls.map((c) => `${c.state} ${c.classes.join(".")}`));
        for (const expected of [
          "OtpInput/empty otp-input",
          "OtpInput/error otp-input",
          "PhoneField/empty phone-field__input",
          "PhoneField/error phone-field__input",
        ]) {
          expect(checked, `${expected} was checked`).toContain(expected);
        }
      });

      test("theCodeFieldValidatesInTheBrowser", async ({ page }) => {
        // The pattern as Chromium compiles it (the v flag), on the element: a pattern invalid under v is dropped and
        // then validates nothing, which a unit test compiling its own RegExp cannot see.
        await open(page);
        const input = page.locator('[data-catalog-state="OtpInput/empty"][lang="en"] input');
        // ASCII, Bangla (U+09E7 to U+09EC, built from code points so no Bangla literal is typed), too short, not a digit.
        const bangla = Array.from({ length: 6 }, (_, i) => String.fromCodePoint(0x09e7 + i)).join("");
        const values = ["123456", bangla, "12345", "12345a"];
        const verdicts: Array<{ value: string; patternMismatch: boolean }> = [];
        for (const value of values) {
          await input.fill(value);
          verdicts.push({ value, patternMismatch: await input.evaluate((n) => (n as HTMLInputElement).validity.patternMismatch) });
        }
        expect(verdicts).toEqual([
          { value: values[0], patternMismatch: false },
          { value: values[1], patternMismatch: false },
          { value: values[2], patternMismatch: true },
          { value: values[3], patternMismatch: true },
        ]);
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
