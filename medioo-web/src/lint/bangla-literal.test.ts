import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { afterAll, describe, expect, it } from "vitest";
import { scanForBangla } from "./bangla-literal";

// Global Constraints: no Bangla literal (U+0980–U+09FF) in medioo-web outside generated copy. This file is itself
// scanned by the real-tree test, so the Bangla its fixtures need is built from code points, never typed.
const BN = String.fromCodePoint(0x0995, 0x09cb, 0x09a1);

const made: string[] = [];
afterAll(() => {
  for (const dir of made) rmSync(dir, { recursive: true, force: true });
});

/** A fixture workspace under the OS temp dir (never inside medioo-web), with `web/` as the root to scan. */
function workspace(files: Record<string, string>, include: string[] = ["src/**/*.ts"]): string {
  const ws = mkdtempSync(join(tmpdir(), "bangla-lint-"));
  made.push(ws);
  const tsconfig = {
    compilerOptions: {
      allowJs: false,
      strict: true,
      noEmit: true,
      module: "esnext",
      moduleResolution: "bundler",
      resolveJsonModule: true,
      esModuleInterop: true,
    },
    include,
  };
  const all: Record<string, string> = { "web/tsconfig.json": JSON.stringify(tsconfig, null, 2) + "\n", ...files };
  for (const [path, content] of Object.entries(all)) {
    mkdirSync(dirname(join(ws, path)), { recursive: true });
    writeFileSync(join(ws, path), content);
  }
  return ws;
}

const generatedCopy = (meta: object | undefined) =>
  JSON.stringify({ ...(meta ? { _meta: meta } : {}), "tab.chat": BN }, null, 2) + "\n";

describe("scanForBangla", () => {
  it("the walk reports a Bangla literal in any file under the root, imported or not", () => {
    // tsconfig includes only src/**, and nothing imports lib/x.ts: only the walk can reach it.
    const ws = workspace({ "web/src/ok.ts": "export const OK = 1;\n", "web/lib/x.ts": `export const X = "${BN}";\n` });
    const result = scanForBangla(join(ws, "web"));
    expect(result.offenders).toEqual(["lib/x.ts:1"]);
    expect(result.scanned).toContain("lib/x.ts");
  });

  it("the walk reports a file the TypeScript program never sees", () => {
    // allowJs is false and .mjs is not in include: the program cannot see this file at all.
    const ws = workspace({
      "web/src/ok.ts": "export const OK = 1;\n",
      "web/eslint.config.mjs": `export default [];\n// ${BN}\n`,
    });
    expect(scanForBangla(join(ws, "web")).offenders).toEqual(["eslint.config.mjs:2"]);
  });

  it("the program reports a JSON module imported from outside the root", () => {
    // ../shared is outside the walked root: only the TypeScript program, following the import, reaches it.
    const ws = workspace({
      "web/src/a.ts": 'import y from "../../shared/y.json";\nexport const Y = y;\n',
      "shared/y.json": JSON.stringify({ y: BN }) + "\n",
    });
    const result = scanForBangla(join(ws, "web"));
    expect(result.offenders).toEqual(["../shared/y.json:1"]);
    expect(result.scanned).toContain("../shared/y.json");
  });

  it("exempts a generated file whose generator exists", () => {
    const ws = workspace({
      "web/src/ok.ts": "export const OK = 1;\n",
      "web/src/copy/generated/copy.bn.json": generatedCopy({ generated_by: "baymax WebCopyExportTest" }),
      "java/com/example/WebCopyExportTest.java": "class WebCopyExportTest {}\n",
    });
    const result = scanForBangla(join(ws, "web"), {
      generatedDirs: ["src/copy/generated/"],
      javaTestRoot: join(ws, "java"),
    });
    expect(result.exempt).toEqual(["src/copy/generated/copy.bn.json"]);
    expect(result.offenders).toEqual([]);
  });

  it.each([
    ["no _meta", undefined],
    ["a generator that does not exist", { generated_by: "baymax NoSuchTest" }],
  ])("reports a file in a generated directory that names %s", (_name, meta) => {
    const ws = workspace({
      "web/src/ok.ts": "export const OK = 1;\n",
      "web/src/copy/generated/copy.bn.json": generatedCopy(meta),
      "java/com/example/WebCopyExportTest.java": "class WebCopyExportTest {}\n",
    });
    const result = scanForBangla(join(ws, "web"), {
      generatedDirs: ["src/copy/generated/"],
      javaTestRoot: join(ws, "java"),
    });
    expect(result.exempt).toEqual([]);
    expect(result.offenders.filter((o) => o.startsWith("src/copy/generated/copy.bn.json:"))).not.toEqual([]);
  });

  it("a valid _meta outside a generated directory never exempts the file", () => {
    // The two conditions are both required: a generator that exists, AND a generated directory. Here only the first holds.
    const ws = workspace({
      "web/src/ok.ts": "export const OK = 1;\n",
      "web/src/app/x.json": generatedCopy({ generated_by: "baymax WebCopyExportTest" }),
      "java/com/example/WebCopyExportTest.java": "class WebCopyExportTest {}\n",
    });
    const result = scanForBangla(join(ws, "web"), {
      generatedDirs: ["src/copy/generated/"],
      javaTestRoot: join(ws, "java"),
    });
    expect(result.offenders).toEqual(["src/app/x.json:5"]);
    expect(result.exempt).toEqual([]);
  });

  it.each(["src/app/out/note.css", "src/out/x.css", "src/features/test-results/x.mjs", "src/app/.next/x.css"])(
    "a source directory named like a build output below the root is still walked (%s)",
    (path) => {
      // Build outputs (out/, .next/, test-results/, playwright-report/) are skipped at the root only, as .gitignore
      // anchors them; a source directory of the same name deeper down is source.
      const ws = workspace({ "web/src/ok.ts": "export const OK = 1;\n", [`web/${path}`]: `/* ${BN} */\n` });
      expect(scanForBangla(join(ws, "web")).offenders).toEqual([`${path}:1`]);
    },
  );

  it("build outputs at the root and node_modules at any depth are not walked", () => {
    const ws = workspace({
      "web/src/ok.ts": "export const OK = 1;\n",
      "web/out/x.css": `/* ${BN} */\n`,
      "web/.next/x.js": `// ${BN}\n`,
      "web/test-results/x.txt": `${BN}\n`,
      "web/playwright-report/x.html": `${BN}\n`,
      "web/src/lib/node_modules/pkg/x.js": `// ${BN}\n`,
    });
    const result = scanForBangla(join(ws, "web"));
    expect(result.offenders).toEqual([]);
    expect(result.scanned).toEqual(["src/ok.ts", "tsconfig.json"]);
  });

  it("throws when the root has a tsconfig but the TypeScript program contributes zero files", () => {
    // include matches nothing: the lint would silently fall back to walk-only and stop following imports.
    const ws = workspace({ "web/lib/x.txt": "plain\n" }, ["nothing/**/*.ts"]);
    expect(() => scanForBangla(join(ws, "web"))).toThrow(/TypeScript program contributed zero files/);
  });

  it("throws when it scans zero files", () => {
    const empty = mkdtempSync(join(tmpdir(), "bangla-lint-empty-"));
    made.push(empty);
    expect(() => scanForBangla(empty)).toThrow(/scanned zero files/);
  });

  it("the real tree: no offenders, and the files that matter were read", () => {
    const result = scanForBangla(join(__dirname, "../.."), {
      generatedDirs: ["src/copy/generated/", "fixtures/api/"],
      javaTestRoot: "../baymax/src/test/java",
    });
    expect(result.offenders).toEqual([]);
    expect(result.scanned).toEqual(expect.arrayContaining(["src/middleware.ts", "src/app/layout.tsx"]));
    expect(result.exempt).toContain("src/copy/generated/copy.bn.json");
    // The program half is live: it reached the generated copy, a JSON module only an import brings in.
    expect(result.program.length).toBeGreaterThan(0);
    expect(result.program).toContain("src/copy/generated/copy.bn.json");
  }, 60_000);
});
