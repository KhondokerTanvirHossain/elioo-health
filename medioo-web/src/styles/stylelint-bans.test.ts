import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import stylelint from "stylelint";
import { describe, expect, it } from "vitest";

// Colour literals live only in the generated tokens.css (plan W2a, Global Constraints). These lint through the repo's
// own .stylelintrc.json, in both directions: what must be refused is refused, and what is allowed reports nothing.
const web = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const componentFile = join(web, "src", "components", "X", "X.css");

async function rulesFor(code: string, codeFilename = componentFile): Promise<string[]> {
  const result = await stylelint.lint({ code, codeFilename, configBasedir: web, cwd: web });
  return result.results.flatMap((r) => r.warnings.map((w) => w.rule));
}

describe("stylelint colour bans", () => {
  it("aHexColourOutsideTokensIsRefused", async () => {
    expect(await rulesFor(".x { color: #d12c24; }\n")).toEqual(["color-no-hex"]);
  });

  it("aNamedColourIsRefused", async () => {
    expect(await rulesFor(".x { color: red; }\n")).toEqual(["color-named"]);
  });

  it("rgbRgbaHslAndColorMixAreRefused", async () => {
    for (const value of [
      "rgb(209 44 36)",
      "rgba(209, 44, 36, 0.5)",
      "hsl(3 70% 48%)",
      "hsla(3, 70%, 48%, 0.5)",
      "oklch(0.6 0.2 30)",
      "color-mix(in srgb, var(--now) 50%, white)",
    ]) {
      const rules = await rulesFor(`.x { background: ${value}; }\n`);
      expect(rules, value).toContain("function-disallowed-list");
    }
  });

  it("everyFunctionTheConfigNamesIsRefused", async () => {
    // Read from the config, so a typo in any entry is a failure here rather than a function that slips through.
    const config = JSON.parse(readFileSync(join(web, ".stylelintrc.json"), "utf8")) as { rules: Record<string, unknown> };
    const listed = config.rules["function-disallowed-list"] as string[];
    const expected = ["rgb", "rgba", "hsl", "hsla", "hwb", "lab", "lch", "oklab", "oklch", "color", "color-mix"];
    expect([...listed].sort()).toEqual([...expected].sort());
    for (const fn of listed) {
      const rules = await rulesFor(`.x { background: ${fn}(0 0 0); }\n`);
      expect(rules, fn).toContain("function-disallowed-list");
    }
  });

  it("aSystemColourIsRefused", async () => {
    // color-named does not know CSS system colours; they paint whatever the platform says, outside every token.
    for (const decl of ["color: CanvasText", "background: Canvas", "border-color: LinkText", "outline: 2px solid Highlight", "fill: GrayText"]) {
      expect(await rulesFor(`.x { ${decl}; }\n`), decl).toContain("declaration-property-value-disallowed-list");
    }
    // A word that merely contains one is not one.
    expect(await rulesFor(".x { color: var(--label); grid-area: marker; }\n")).toEqual([]);
  });

  it("aFontSizeIsATokenSoTheTwelvePixelFloorHoldsOnShippedCss", async () => {
    for (const decl of ["font-size: 11px", "font-size: 0.6rem", "font-size: 12px", "font: 600 11px var(--font-bn)"]) {
      const rules = await rulesFor(`.x { ${decl}; }\n`);
      expect(rules.some((r) => r.startsWith("declaration-property-value-")), decl).toBe(true);
    }
    for (const decl of ["font-size: var(--text-xs)", "font-size: inherit", "font-size: 100%", "font: 600 var(--text-sm)/1.5 var(--font-bn)"]) {
      expect(await rulesFor(`.x { ${decl}; }\n`), decl).toEqual([]);
    }
  });

  it("aVarColourIsAccepted", async () => {
    expect(await rulesFor(".x { color: var(--label); background: var(--bg); }\n")).toEqual([]);
  });

  it("theGeneratedTokensFileIsTheOnlyException", async () => {
    const tokensCss = join(web, "src", "styles", "tokens.css");
    const text = readFileSync(tokensCss, "utf8");
    expect(await rulesFor(text, tokensCss)).toEqual([]);
    expect(await rulesFor(text, componentFile)).toContain("color-no-hex");
  });

  it("theRealTreeIsClean", async () => {
    const result = await stylelint.lint({ files: "src/**/*.css", cwd: web, configBasedir: web });
    const errors = result.results.flatMap((r) => r.warnings.map((w) => `${r.source}: ${w.rule}: ${w.text}`));
    expect(errors).toEqual([]);
    const linted = result.results.map((r) => r.source ?? "");
    expect(linted.length).toBeGreaterThanOrEqual(2);
    expect(linted.some((s) => s.endsWith("src/app/globals.css"))).toBe(true);
    expect(linted.some((s) => s.endsWith("src/styles/tokens.css"))).toBe(true);
  });
});
