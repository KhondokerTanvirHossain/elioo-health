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
  // ...nor by a dynamic import or require, which no-restricted-imports does not see.
  [
    "generated copy imported dynamically",
    'export const G = async () => (await import("@/copy/generated/copy.bn.json")).default;\n',
    "no-restricted-syntax",
  ],
  [
    "generated copy imported dynamically (template literal)",
    "export const G = async (l: string) => (await import(`../copy/generated/copy.${l}.json`)).default;\n",
    "no-restricted-syntax",
  ],
  [
    "generated copy required",
    'declare const require: (p: string) => unknown;\nexport const G = require("@/copy/generated/copy.bn.json");\n',
    "no-restricted-syntax",
  ],
  // The safety lines (urgency wording, 999) are rendered only from what Java sent, never named as a literal outside
  // src/components/safety/** (W2b review: urgency only for a RELEASED explanation, NOW only for a stored NOW, 999 only
  // when viewer.bangladesh).
  [
    "an urgency line named as a literal",
    `import { t } from "@/copy";\nexport const U = () => <p>{t("bn", "urgency.now")}</p>;\n`,
    "no-restricted-syntax",
  ],
  [
    "the 999 line named as a literal",
    `import { t } from "@/copy";\nexport const N = () => <p>{t("en", "now.call999")}</p>;\n`,
    "no-restricted-syntax",
  ],
  [
    "the remote 999 line named as a template literal",
    "import { t } from \"@/copy\";\nexport const N = () => <p>{t(\"en\", `now.remote.999`)}</p>;\n",
    "no-restricted-syntax",
  ],
  [
    "an urgency line named as a literal under src/copy",
    `import { t } from "@/copy";\nexport const U = () => t("bn", "urgency.this_week.short");\n`,
    "no-restricted-syntax",
    "src/copy/lint-fixture.ts",
  ],
  [
    "generated copy imported dynamically under src/components/safety",
    'export const G = async () => (await import("@/copy/generated/copy.bn.json")).default;\n',
    "no-restricted-syntax",
    "src/components/safety/lint-fixture.tsx",
  ],
  // No screen text is logged (plan W3): src/api, the pages and the components call no console.* at all.
  ["console.log in src is refused", "export const L = (x: string) => { console.log(x); return null; };\n", "no-console", "src/api/lint-fixture.ts"],
  ["console.error in a page is refused", "export const L = (x: string) => { console.error(x); return null; };\n", "no-console"],
  // src/copy/** may import the generated copy, but its override must not lift the other bans there.
  ["next/image under src/copy", `import Image from "next/image";\nexport const H = Image;\n`, "no-restricted-imports", "src/copy/lint-fixture.ts"],
  [
    "next/image under src/components/safety",
    `import Image from "next/image";\nexport const H = Image;\n`,
    "no-restricted-imports",
    "src/components/safety/lint-fixture.tsx",
  ],
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
    // The one safety directory renders the safety lines; every other ban still holds there (BANNED, below).
    expect(
      await errorsIn(
        `import { t } from "@/copy";\nexport const S = () => <p>{t("bn", "urgency.now")} {t("bn", "now.call999")}</p>;\n`,
        "src/components/safety/lint-fixture.tsx",
      ),
    ).toEqual([]);
    // A key received from the API is not a literal, and an ordinary key literal is fine.
    expect(
      await errorsIn(
        `import { t, type CopyKey } from "@/copy";\nexport const K = (p: { k: CopyKey }) => <p>{t("bn", p.k)} {t("bn", "now.notdoctor")}</p>;\n`,
      ),
    ).toEqual([]);
  }, 30_000);

  it.each(BANNED)("reports %s", async (_name, code, rule, file) => {
    expect(await errorsIn(code, file)).toContain(rule);
  }, 30_000);
});
