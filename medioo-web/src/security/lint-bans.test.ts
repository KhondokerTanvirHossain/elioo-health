import { join } from "node:path";
import { ESLint } from "eslint";
import { describe, expect, it } from "vitest";

// The lint bans that keep a page inside style-src 'self' (eslint.config.mjs). Kept as a test, not a one-off canary,
// so a config change — a reordered extends, an eslint-config-next upgrade redefining a rule — cannot silently drop one.
// Both directions: each banned pattern reports its rule, and clean code reports nothing.
const ROOT = join(__dirname, "../..");
const eslint = new ESLint({ cwd: ROOT });

async function errorsIn(code: string): Promise<string[]> {
  const [result] = await eslint.lintText(code, { filePath: join(ROOT, "src/app/lint-fixture.tsx") });
  return (result?.messages ?? []).filter((m) => m.severity === 2).map((m) => m.ruleId ?? `fatal: ${m.message}`);
}

const BANNED: [string, string, string][] = [
  ["style={} on a DOM node", `export const A = () => <div style={{ color: "red" }} />;\n`, "react/forbid-dom-props"],
  [
    "style={} on a component",
    `const B = (p: { style?: object }) => <div className={String(p.style)} />;\nexport const C = () => <B style={{ color: "red" }} />;\n`,
    "react/forbid-component-props",
  ],
  ["next/image", `import Image from "next/image";\nexport const D = Image;\n`, "no-restricted-imports"],
  ["next/font/google", `import { Inter } from "next/font/google";\nexport const E = Inter;\n`, "no-restricted-imports"],
  ["next/font/local", `import localFont from "next/font/local";\nexport const F = localFont;\n`, "no-restricted-imports"],
];

describe("lint bans", () => {
  it("passes clean code with zero errors", async () => {
    expect(await errorsIn(`export const Ok = () => <div className="shell">ok</div>;\n`)).toEqual([]);
  }, 30_000);

  it.each(BANNED)("reports %s", async (_name, code, rule) => {
    expect(await errorsIn(code)).toContain(rule);
  }, 30_000);
});
