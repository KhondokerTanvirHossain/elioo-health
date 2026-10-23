import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";
import { CONTROL_CLASS, FIELD_SELECTOR, NOW_ALLOWED } from "../../src/styles/now-usage";
import { useScenario, type CropRow } from "./browser-api";
import { expectedWidth } from "./png.mjs";
import { LONGEST, longNameOf } from "./scenarios.mjs";

/*
 * The content checks every routed page and every catalog state meet (plan Wave 3, Shared interfaces → e2e support),
 * as properties of what the browser paints. One copy: catalog.spec.ts judges each `[data-catalog-state]` section with
 * these, and `screenChecks` judges a whole routed page (`scope` "body"). Each report returns what it saw as well as
 * what it found wrong, so a caller can guard against a check that measured nothing.
 */

export const BRAND = ["/brand/medioo-light.svg", "/brand/medioo-dark.svg", "/brand/mio.webp", "/brand/mio-head.webp"];
const SCHEMES = ["light", "dark"] as const;

/** Records every CSP violation from the first byte (call before the page loads). */
export async function installCspHook(page: Page) {
  await page.addInitScript(() => {
    const w = window as unknown as { __csp: string[] };
    w.__csp = [];
    document.addEventListener("securitypolicyviolation", (e) => {
      w.__csp.push(`${e.violatedDirective} blocked ${e.blockedURI || "inline"}`);
    });
  });
}

/** The CSP violations seen, or null when the hook was not installed. */
export function cspViolations(page: Page): Promise<string[] | null> {
  return page.evaluate(() => (window as unknown as { __csp?: string[] }).__csp ?? null);
}

/** Whether the emulated scheme is on, the active scheme's --bg, and what the body paints. */
export function schemePaint(page: Page) {
  return page.evaluate(() => {
    const swatch = document.createElement("div");
    swatch.style.setProperty("background-color", "var(--bg)");
    document.body.append(swatch);
    const token = getComputedStyle(swatch).backgroundColor;
    swatch.remove();
    return { dark: matchMedia("(prefers-color-scheme: dark)").matches, token, body: getComputedStyle(document.body).backgroundColor };
  });
}

/** The theme under test is the one painted, and the other scheme paints a different background (reading.spec.ts's guard). */
export async function expectSchemeIsPainted(page: Page, colorScheme: "light" | "dark") {
  const active = await schemePaint(page);
  expect(active.dark, "prefers-color-scheme is emulated").toBe(colorScheme === "dark");
  expect(active.body, "the body paints the active scheme's --bg").toBe(active.token);
  const other = colorScheme === "dark" ? "light" : "dark";
  await page.emulateMedia({ colorScheme: other });
  expect((await schemePaint(page)).body, `the ${other} theme paints a different background`).not.toBe(active.body);
  await page.emulateMedia({ colorScheme });
}

/** axe over the page (or `include`), as `id: targets` lines, and how many rules passed. */
export async function axeReport(page: Page, include?: string) {
  const builder = new AxeBuilder({ page });
  const results = await (include ? builder.include(include) : builder).analyze();
  return {
    violations: results.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(" ")).join(", ")}`),
    passes: results.passes.length,
  };
}

/** Every element under each `scope` whose content is wider than its box, the sections measured, and the page's own width. */
export function overflowReport(page: Page, scope: string) {
  return page.evaluate((scope) => {
    const over: string[] = [];
    const measured: string[] = [];
    const empty: string[] = [];
    for (const section of document.querySelectorAll<HTMLElement>(scope)) {
      const label = section.dataset.catalogState ?? scope;
      measured.push(label);
      // The section and at least one element of its own, or the inner loop measured nothing there. A state marked empty
      // renders nothing by design.
      if (!section.hasAttribute("data-catalog-empty") && section.querySelectorAll("*").length < 2) empty.push(label);
      for (const el of [section, ...section.querySelectorAll("*")]) {
        if (el.scrollWidth > el.clientWidth + 1) {
          over.push(`${label} <${el.tagName.toLowerCase()} class="${el.getAttribute("class") ?? ""}"> ${el.scrollWidth} > ${el.clientWidth}`);
        }
      }
    }
    const root = document.documentElement;
    return { over, measured, empty, page: [root.scrollWidth, root.clientWidth] as [number, number] };
  }, scope);
}

/** Where the active theme's NOW red is painted under each `scope`, and where it is painted outside a NOW component. */
export function nowRedReport(page: Page, scope: string) {
  return page.evaluate(
    ({ allowSource, scope }) => {
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
        "text-decoration-color",
        "box-shadow",
      ];
      // A box-shadow reads "rgb(...) 0px 0px 0px 2px inset" (a ring), so it is matched by containment; every other
      // property by its exact value.
      const isNow = (prop: string, value: string) =>
        prop === "box-shadow" ? [...now].some((c) => c !== "" && value.includes(c)) : now.has(value);
      const allowed = (el: Element | null): boolean => {
        for (let e = el; e; e = e.parentElement) if ([...e.classList].some((c) => allow.test(`.${c}`))) return true;
        return false;
      };
      const bad: string[] = [];
      const painted: string[] = [];
      let elements = 0;
      for (const section of document.querySelectorAll<HTMLElement>(scope)) {
        const label = section.dataset.catalogState ?? scope;
        for (const el of [section, ...section.querySelectorAll("*")]) {
          elements++;
          const style = getComputedStyle(el);
          for (const prop of PROPS) {
            const value = style.getPropertyValue(prop);
            if (!isNow(prop, value)) continue;
            painted.push(label);
            if (!allowed(el)) bad.push(`${label} <${el.tagName.toLowerCase()} class="${el.getAttribute("class") ?? ""}"> ${prop}: ${value}`);
          }
        }
      }
      return { bad: [...new Set(bad)], painted: [...new Set(painted)], now: [...now], elements };
    },
    { allowSource: NOW_ALLOWED.source, scope },
  );
}

/**
 * Every field and track under each `scope` (FIELD_SELECTOR, or a CONTROL_CLASS class) is washed with --fill and draws
 * --boundary as an edge, at rest, hovered and focused, with no data-theme and under data-theme light and dark (Q1 (A),
 * PR #120). Returns the controls found (and whether the built-CSS scan can see each) and every problem.
 */
export async function boundaryReport(page: Page, scope: string) {
  const controls = await page.evaluate(
    ({ fields, controlClass, scope }) => {
      const re = new RegExp(controlClass);
      const found = [...document.querySelectorAll<HTMLElement>(`${scope} *`)].filter(
        (el) => el.matches(fields) || [...el.classList].some((c) => re.test(`.${c}`)),
      );
      return found.map((el, i) => {
        el.setAttribute("data-boundary-check", String(i));
        return {
          state: el.closest<HTMLElement>(scope)?.dataset.catalogState ?? scope,
          tag: el.tagName.toLowerCase(),
          classes: [...el.classList],
          // Whether the built-CSS scan can see this element at all: it judges a component's field only by a class on
          // the element itself that CONTROL_CLASS matches.
          scanned: [...el.classList].some((c) => re.test(`.${c}`)),
        };
      });
    },
    { fields: FIELD_SELECTOR, controlClass: CONTROL_CLASS.source, scope },
  );

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
            // Each pass is measured in the state it claims, or it silently re-measures rest.
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
  await page.evaluate(() => document.documentElement.removeAttribute("data-theme"));
  return { controls, bad };
}

/**
 * Every text node under each `scope` painted under 12 px, and how many text nodes were measured. getComputedStyle
 * reports an SVG <text>'s font-size in user units, before the viewBox is scaled to the box it is drawn in, so for text
 * inside an SVG the size is multiplied by the element's screen transform: a 12 px label in a 320-unit viewBox drawn
 * 296 px wide is painted at 11.1 px (W2e-b).
 */
export function smallTextReport(page: Page, scope: string) {
  return page.evaluate((scope) => {
    const small: string[] = [];
    let texts = 0;
    let svgTexts = 0;
    for (const section of document.querySelectorAll<HTMLElement>(scope)) {
      const walker = document.createTreeWalker(section, NodeFilter.SHOW_TEXT);
      for (let node = walker.nextNode(); node; node = walker.nextNode()) {
        if (!node.textContent?.trim()) continue;
        if (node.parentElement?.closest("script, style, noscript")) continue;
        texts++;
        const parent = node.parentElement!;
        let size = parseFloat(getComputedStyle(parent).fontSize);
        if (parent instanceof SVGGraphicsElement) {
          svgTexts++;
          const ctm = parent.getScreenCTM();
          if (ctm) size *= Math.hypot(ctm.a, ctm.b);
        }
        // 1e-3 absorbs float error in the transform only; a 12 px floor is not loosened by it.
        if (size < 12 - 1e-3) small.push(`${section.dataset.catalogState ?? scope}: "${node.textContent.trim().slice(0, 20)}" ${size.toFixed(2)}px`);
      }
    }
    return { small, texts, svgTexts };
  }, scope);
}

/** Every visible image under `scope`, once each has loaded: its path, whether it decoded, and its natural width. */
export async function imagesReport(page: Page, scope: string) {
  await page.$$eval(`${scope} img`, (imgs) => imgs.forEach((i) => i.scrollIntoView()));
  await page.waitForFunction(
    (scope) =>
      [...document.querySelectorAll<HTMLImageElement>(`${scope} img`)].filter((i) => i.checkVisibility()).every((i) => i.complete),
    scope,
  );
  await page.evaluate(() => window.scrollTo(0, 0));
  return page.$$eval(
    `${scope} img`,
    (imgs, scope) =>
      (imgs as HTMLImageElement[])
        .filter((i) => i.checkVisibility())
        .map((i) => ({
          state: i.closest<HTMLElement>(scope)?.dataset.catalogState ?? scope,
          path: i.currentSrc ? new URL(i.currentSrc).pathname : "",
          src: i.getAttribute("src") ?? "",
          complete: i.complete,
          natural: i.naturalWidth,
        })),
    scope,
  );
}

/** Every link, button and input smaller than the 44 px floor, and every `[data-primary]` action under 48 px. */
export function targetReport(page: Page) {
  return page.evaluate(() => {
    const small: string[] = [];
    let measured = 0;
    const describe = (el: Element, r: DOMRect) =>
      `<${el.tagName.toLowerCase()} class="${el.getAttribute("class") ?? ""}"> ${r.width.toFixed(1)}×${r.height.toFixed(1)}`;
    for (const el of document.querySelectorAll("a[href], button, input:not([type=hidden]), select, textarea, summary")) {
      if (!(el as HTMLElement).checkVisibility()) continue;
      measured++;
      const r = el.getBoundingClientRect();
      const min = el.matches("[data-primary], .now-banner__call, .now-banner__open") ? 48 : 44;
      if (r.width < min - 0.5 || r.height < min - 0.5) small.push(`${describe(el, r)} under ${min} px`);
    }
    const primaries = [...document.querySelectorAll("[data-primary]")].filter((el) => (el as HTMLElement).checkVisibility()).length;
    return { small, measured, primaries };
  });
}

/**
 * Whether every value row shows its own crop: the rendered `[data-value-row]` count is the fixture's `rows.length`
 * (so a missing row cannot be made up by an extra image elsewhere), each row holds exactly one visible image, and that
 * image is the row's crop (`src`) decoded at the row's own width. Rows are matched by DOM order, since
 * `PaperValueJson.id` is nullable. Returns every problem; empty means each row was checked.
 */
export async function cropRowsReport(page: Page, rows: readonly CropRow[]): Promise<string[]> {
  const problems: string[] = [];
  const widths = rows.map((r) => expectedWidth(r));
  if (rows.length === 0) problems.push("no rows in the fixture: nothing would be checked");
  if (new Set(widths).size !== widths.length) problems.push("this fixture cannot detect a swap: two rows share a width");
  const rendered = await page.locator("[data-value-row]").count();
  if (rendered !== rows.length) problems.push(`${rendered} value rows rendered, the fixture has ${rows.length}`);
  // Wait for every row image to load before reading natural widths.
  await imagesReport(page, "[data-value-row]");
  const perRow = await page.$$eval("[data-value-row]", (els) =>
    els.map((el) =>
      [...el.querySelectorAll("img")]
        .filter((i) => i.checkVisibility())
        .map((i) => ({ src: i.getAttribute("src") ?? "", natural: i.naturalWidth })),
    ),
  );
  perRow.forEach((images, i) => {
    const row = rows[i];
    if (images.length !== 1) problems.push(`row ${i + 1} shows ${images.length} images, not one`);
    if (!row || images.length !== 1) return;
    const [image] = images;
    if (image!.src !== row.crop.src) problems.push(`row ${i + 1} shows ${image!.src}, not its crop ${row.crop.src}`);
    if (image!.natural !== expectedWidth(row)) {
      problems.push(`row ${i + 1} decodes at ${image!.natural} px, its crop is ${expectedWidth(row)} px`);
    }
  });
  return problems;
}

/** Whether some element's own text (a text child, not a descendant's) contains `text`: a long name split across
 * elements, or present only in an attribute, is not rendered as one string. */
export function rendersOwnText(page: Page, text: string): Promise<boolean> {
  return page.evaluate(
    (n) =>
      [...document.querySelectorAll("body *")].some((el) =>
        [...el.childNodes].some((c) => c.nodeType === 3 && (c.textContent ?? "").includes(n)),
      ),
    text,
  );
}

/** A routed page opened under a scenario, with the CSP hook installed and every eager image and font loaded. */
export async function openRoute(page: Page, route: string, scenario: string) {
  await useScenario(page, scenario);
  await installCspHook(page);
  const response = await page.goto(route);
  expect(response?.status(), `${route} under ${scenario}`).toBe(200);
  await page.waitForLoadState("load");
  await page.evaluate(() => document.fonts.ready.then(() => undefined));
}

/**
 * Registers the content checks for one routed page under one scenario, in both colour schemes. `crops` adds the check
 * that every value row shows its own crop (`rows`: the fixture's values, in DOM order); `longest` registers the route a
 * second time under its longest twin (LONGEST), whose long name (read from the twin's own `me`, longNameOf) must be on
 * the page before overflow is judged. `onePrimary` requires exactly one `[data-primary]` (not at most one), so a page
 * that lost its marker cannot quietly fall from the 48 px rule to 44 px. `act` drives the page to a state a press
 * reaches (a confirm step, a refusal) before every check runs, so those states are judged too. `images` names the
 * other crops a page shows outside a value row (an attention card's follow-up beside the doctor's words, W5): each must
 * decode at its own width, as a row's crop does, and nothing else beside the brand files may be on the page.
 */
export function screenChecks(
  route: string,
  scenario: string,
  opts: {
    crops?: CropRow[];
    longest?: boolean;
    fields?: string[];
    onePrimary?: boolean;
    act?: { state: string; run: (page: Page) => Promise<void> };
    images?: CropRow[];
  } = {},
) {
  const scenarios = [scenario];
  if (opts.longest) {
    const twin = LONGEST[scenario];
    test(`${route} has a longest twin`, () => expect(twin, `LONGEST[${scenario}]`).toBeDefined());
    if (twin) scenarios.push(twin);
  }
  const open = async (page: Page, name: string) => {
    await openRoute(page, route, name);
    if (opts.act) await opts.act.run(page);
  };
  const state = opts.act ? ` after ${opts.act.state}` : "";
  for (const name of scenarios) {
    for (const colorScheme of SCHEMES) {
      test.describe(`${route} under ${name}${state}, ${colorScheme}`, () => {
        test.use({ colorScheme });

        test("axeReportsNoViolation", async ({ page }) => {
          await open(page, name);
          await expectSchemeIsPainted(page, colorScheme);
          const report = await axeReport(page);
          expect(report.violations).toEqual([]);
          expect(report.passes, "axe checked something").toBeGreaterThan(0);
        });

        test("noCspViolation", async ({ page }) => {
          await open(page, name);
          expect(await cspViolations(page)).toEqual([]);
        });

        test("noElementOverflowsItsBox", async ({ page }) => {
          await open(page, name);
          if (name !== scenario) {
            // The longest twin's name (from its own `me`) is on the page, or overflow was judged on short strings.
            const longName = longNameOf(name);
            expect(await rendersOwnText(page, longName), `the long name "${longName}" is rendered`).toBe(true);
          }
          const report = await overflowReport(page, "body");
          expect(report.over).toEqual([]);
          expect(report.page[0], "the page itself scrolls sideways").toBeLessThanOrEqual(report.page[1] + 1);
          expect(report.empty, "a page with nothing to measure").toEqual([]);
        });

        test("everyFieldAndTrackDrawsTheBoundary", async ({ page }) => {
          await open(page, name);
          const report = await boundaryReport(page, "body");
          expect(
            report.controls.filter((c) => !c.scanned).map((c) => `${c.tag}.${c.classes.join(".")} carries no CONTROL_CLASS class`),
            "fields the built-CSS scan cannot see",
          ).toEqual([]);
          expect(report.bad).toEqual([]);
          const checked = new Set(report.controls.flatMap((c) => c.classes));
          for (const want of opts.fields ?? []) expect(checked, `.${want} was checked`).toContain(want);
        });

        test("noTextUnderTwelvePixels", async ({ page }) => {
          await open(page, name);
          const report = await smallTextReport(page, "body");
          expect(report.small).toEqual([]);
          expect(report.texts, "text nodes measured").toBeGreaterThan(0);
        });

        test("onlyNowComponentsPaintTheNowRed", async ({ page }) => {
          await open(page, name);
          const report = await nowRedReport(page, "body");
          expect(report.now.length, "the NOW tokens resolved").toBeGreaterThanOrEqual(3);
          expect(report.elements, "elements judged").toBeGreaterThan(0);
          expect(report.bad).toEqual([]);
        });

        test("everyTargetMeetsTheFloor", async ({ page }) => {
          await open(page, name);
          const report = await targetReport(page);
          expect(report.small).toEqual([]);
          expect(report.measured, "targets measured").toBeGreaterThan(0);
          if (opts.onePrimary) expect(report.primaries, "exactly one primary action").toBe(1);
          else expect(report.primaries, "at most one primary action").toBeLessThanOrEqual(1);
        });

        test("everyVisibleImageIsADecodedBrandFileOrItsOwnCrop", async ({ page }) => {
          await open(page, name);
          const crops = new Map([...(opts.crops ?? []), ...(opts.images ?? [])].map((row) => [row.crop.src, expectedWidth(row)]));
          const images = await imagesReport(page, "body");
          const bad = images
            .filter((i) => {
              if (!i.complete || i.natural === 0) return true;
              if (BRAND.includes(i.path)) return false;
              const width = crops.get(i.src);
              return width === undefined || i.natural !== width;
            })
            .map((i) => `${i.path || "no currentSrc"} complete=${i.complete} naturalWidth=${i.natural}`);
          expect(bad).toEqual([]);
        });

        if (opts.crops) {
          test("everyCropIsItsRowsOwnImage", async ({ page }) => {
            await open(page, name);
            expect(await cropRowsReport(page, opts.crops!)).toEqual([]);
          });
        }
      });
    }
  }
}
