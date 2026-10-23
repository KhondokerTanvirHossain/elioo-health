import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { dirname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

/*
 * Runs on the builds (pnpm build && pnpm build:catalog && pnpm run test:built). The invariant is about what the browser
 * receives: no client chunk carries the copy. A component takes its words already resolved, as props from a server
 * parent (Sheet → SheetDialog, Toast → ToastRegion), and those props travel in the page's RSC payload, never in a
 * static chunk; a `t()` or a copy import that reached a "use client" module, directly, by a relative path, through a
 * dynamic import() or transitively through a helper, puts the whole bn+en bundle into a chunk. A source scan (eslint's
 * client-file ban) sees only the direct forms; this judges the bytes. Both builds are read: the production one (every
 * route the image serves) and the catalog one (every component state, the only build where Sheet and Toast render
 * today).
 */
const web = join(dirname(fileURLToPath(import.meta.url)), "..", "..", "..");
const BUILDS = [
  { dir: ".next", hint: "pnpm build" },
  { dir: ".next-catalog", hint: "pnpm build:catalog" },
] as const;

function chunks(build: (typeof BUILDS)[number]): { file: string; text: string }[] {
  const root = join(web, build.dir, "static", "chunks");
  const files: string[] = [];
  const walk = (dir: string) => {
    for (const entry of readdirSync(dir)) {
      const path = join(dir, entry);
      if (statSync(path).isDirectory()) walk(path);
      else if (entry.endsWith(".js")) files.push(path);
    }
  };
  if (existsSync(root)) walk(root);
  if (files.length === 0) throw new Error(`run ${build.hint} first: no .js under ${build.dir}/static/chunks`);
  // A minifier may write non-ASCII as \uXXXX (once, or twice inside a JSON.parse string): read the chunk as text.
  const unescape = (s: string) => s.replace(/\\+u([0-9a-fA-F]{4})/g, (_, hex: string) => String.fromCharCode(parseInt(hex, 16)));
  return files.map((f) => ({ file: relative(web, f), text: unescape(readFileSync(f, "utf8")) }));
}

/**
 * Every copy value long enough to be the copy's and not a coincidence (React's own strings are English), in both
 * languages, without the characters a bundler escapes inside a string literal (quotes, a backslash, a line break).
 */
function copyValues(): { key: string; lang: string; value: string }[] {
  const out: { key: string; lang: string; value: string }[] = [];
  for (const lang of ["bn", "en"] as const) {
    // Read as bytes for this check, not imported: the copy is rendered only through @/copy (eslint).
    const copy = JSON.parse(readFileSync(join(web, "src", "copy", "generated", `copy.${lang}.json`), "utf8")) as Record<string, unknown>;
    for (const [key, value] of Object.entries(copy)) {
      if (key === "_meta" || typeof value !== "string") continue;
      if (value.length < 12 || /["'`\\\n]/.test(value)) continue;
      out.push({ key, lang, value });
    }
  }
  return out;
}

describe("the built client chunks", () => {
  it("noClientChunkCarriesCopy", () => {
    const values = copyValues();
    // Guard: the list judged is the copy, in both languages, and includes the strings a sheet and a toast show.
    expect(values.length, "copy values judged").toBeGreaterThan(100);
    for (const [lang, key] of [
      ["bn", "retake.tip.3"],
      ["en", "urgency.now"],
      ["bn", "retake.title"],
    ] as const) {
      expect(values.some((v) => v.lang === lang && v.key === key), `${lang} ${key} is among the values judged`).toBe(true);
    }

    const found: string[] = [];
    const markers: Record<string, string[]> = {};
    for (const build of BUILDS) {
      const all = chunks(build);
      for (const { file, text } of all) {
        for (const { key, lang, value } of values) if (text.includes(value)) found.push(`${file}: ${lang} ${key} "${value}"`);
      }
      markers[build.dir] = all.filter(({ text }) => text.includes("sheet__close") || text.includes("toast-region")).map((c) => c.file);
    }
    expect(found, "client chunks that carry a copy value").toEqual([]);
    // Guard: the catalog build's client chunks hold SheetDialog and ToastRegion, so the scan read the chunks where a
    // copy regression in those components would land, not only the framework's.
    const catalog = chunks(BUILDS[1]);
    expect(catalog.some(({ text }) => text.includes("sheet__close")), "a .next-catalog chunk holds SheetDialog").toBe(true);
    expect(catalog.some(({ text }) => text.includes("toast-region")), "a .next-catalog chunk holds ToastRegion").toBe(true);
    // Guard (W7): the production build's client chunks hold the add sheet, the largest island that takes words as
    // props, so a copy import that reached it would land in a chunk this scan read.
    const production = chunks(BUILDS[0]);
    expect(production.some(({ text }) => text.includes("upload-sheet__send")), "a .next chunk holds UploadSheet").toBe(true);
    console.log("client chunks holding SheetDialog / ToastRegion:", markers);
  });
});
