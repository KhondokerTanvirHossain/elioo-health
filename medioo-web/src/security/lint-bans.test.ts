import { join } from "node:path";
import { ESLint } from "eslint";
import { describe, expect, it } from "vitest";

// The lint bans that keep a page inside style-src 'self' (eslint.config.mjs). Kept as a test, not a one-off canary,
// so a config change — a reordered extends, an eslint-config-next upgrade redefining a rule — cannot silently drop one.
// Both directions: each banned pattern reports its rule, and clean code reports nothing.
const ROOT = join(__dirname, "../..");
const eslint = new ESLint({ cwd: ROOT });

async function errorsIn(code: string, file = "src/app/lint-fixture.tsx"): Promise<string[]> {
  const [result] = await eslint.lintText(code, { filePath: join(ROOT, file) });
  return (result?.messages ?? []).filter((m) => m.severity === 2).map((m) => m.ruleId ?? `fatal: ${m.message}`);
}

// [name, code, rule, file the code is linted as (default src/app/lint-fixture.tsx)]
const BANNED: [string, string, string, string?][] = [
  ["style={} on a DOM node", `export const A = () => <div style={{ color: "red" }} />;\n`, "react/forbid-dom-props"],
  [
    "style={} on a component",
    `const B = (p: { style?: object }) => <div className={String(p.style)} />;\nexport const C = () => <B style={{ color: "red" }} />;\n`,
    "react/forbid-component-props",
  ],
  ["next/image", `import Image from "next/image";\nexport const D = Image;\n`, "no-restricted-imports"],
  ["next/font/google", `import { Inter } from "next/font/google";\nexport const E = Inter;\n`, "no-restricted-imports"],
  ["next/font/local", `import localFont from "next/font/local";\nexport const F = localFont;\n`, "no-restricted-imports"],
  // Generated copy is read only through @/copy (W2b), by either spelling of the path.
  [
    "generated copy imported directly (relative)",
    'import c from "../copy/generated/copy.bn.json";\nexport const G = c;\n',
    "no-restricted-imports",
  ],
  [
    "generated copy imported directly (alias)",
    'import c from "@/copy/generated/copy.bn.json";\nexport const G = c;\n',
    "no-restricted-imports",
  ],
  // src/copy/** may import the generated copy, but its override must not lift the other bans there.
  ["next/image under src/copy", `import Image from "next/image";\nexport const H = Image;\n`, "no-restricted-imports", "src/copy/lint-fixture.ts"],
];

describe("lint bans", () => {
  it("passes clean code with zero errors", async () => {
    expect(await errorsIn(`export const Ok = () => <div className="shell">ok</div>;\n`)).toEqual([]);
    expect(
      await errorsIn(`import { t } from "@/copy";\nexport const Ok = () => <div className="shell">{t("bn", "tab.chat")}</div>;\n`),
    ).toEqual([]);
    // The one door itself reads the generated copy, by a spelling the pattern matches everywhere else.
    expect(
      await errorsIn('import c from "@/copy/generated/copy.bn.json";\nexport const G = c;\n', "src/copy/lint-fixture.ts"),
    ).toEqual([]);
  }, 30_000);

  it.each(BANNED)("reports %s", async (_name, code, rule, file) => {
    expect(await errorsIn(code, file)).toContain(rule);
  }, 30_000);
});
