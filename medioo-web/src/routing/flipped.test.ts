import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { FLIPPED } from "./flipped";

type Block = { paths: string[]; methods: string[] | null; body: string };

/**
 * The `handle` blocks of a Caddyfile site, each with the paths it matches, the methods (null for any) and its body:
 * `handle /a /b {` takes inline paths; `handle @name {` takes the named matcher `@name { path …; method … }` or
 * `@name path …` defined in the same file.
 */
export function handleBlocks(text: string): Block[] {
  const lines = text.split("\n").map((l) => l.replace(/#.*$/, "").trim());
  const matchers = new Map<string, { paths: string[]; methods: string[] | null }>();
  for (let i = 0; i < lines.length; i++) {
    const m = /^@(\S+)\s*(.*)$/.exec(lines[i]!);
    if (!m) continue;
    const def = { paths: [] as string[], methods: null as string[] | null };
    const take = (directive: string) => {
      const [name, ...args] = directive.split(/\s+/);
      if (name === "path") def.paths.push(...args);
      if (name === "method") def.methods = args.map((a) => a.toUpperCase());
    };
    if (m[2] === "{") {
      for (i++; i < lines.length && lines[i] !== "}"; i++) if (lines[i]) take(lines[i]!);
    } else if (m[2]) take(m[2]);
    matchers.set(m[1]!, def);
  }
  const blocks: Block[] = [];
  for (let i = 0; i < lines.length; i++) {
    const m = /^handle(?:\s+(.*?))?\s*\{$/.exec(lines[i]!);
    if (!m) continue;
    const args = (m[1] ?? "").split(/\s+/).filter(Boolean);
    const body: string[] = [];
    let depth = 1;
    for (i++; i < lines.length; i++) {
      const line = lines[i]!;
      if (line.endsWith("{")) depth++;
      if (line === "}" && --depth === 0) break;
      if (line) body.push(line);
    }
    const named = args.length === 1 && args[0]!.startsWith("@") ? matchers.get(args[0]!.slice(1)) : undefined;
    blocks.push({
      paths: named ? named.paths : args,
      methods: named ? named.methods : null,
      body: body.join("\n"),
    });
  }
  return blocks;
}

const NOT_PAGES = new Set(["/app/healthz", "/app/preview", "/app/preview/*", "/_next/static/*", "/fonts/*", "/brand/*"]);

describe("flipped", () => {
  it("flippedIsTheCaddyfilesNextPaths", () => {
    const caddyfile = readFileSync(join(__dirname, "..", "..", "..", "docs", "infra", "Caddyfile.medioo"), "utf8");
    const toNext = handleBlocks(caddyfile).filter((b) => b.body === "reverse_proxy medioo-web:3000");
    // Guard: the parser found the one block every version of the file routes to Next, so it did not read nothing.
    expect(toNext.flatMap((b) => b.paths), "the /app/healthz block was parsed").toContain("/app/healthz");
    const pages = toNext
      .filter((b) => b.methods === null || b.methods.includes("GET"))
      .flatMap((b) => b.paths)
      .filter((p) => !NOT_PAGES.has(p));
    expect([...FLIPPED].sort()).toEqual([...new Set(pages)].sort());
  });

  it("theParserReadsANamedMatcherWithItsMethods", () => {
    // The shape a flip PR writes (Task F): a GET/HEAD-only matcher, so POST /app/verify stays on Java.
    const blocks = handleBlocks(
      "site {\n\t@signin {\n\t\tmethod GET HEAD\n\t\tpath /app /app/ /app/verify\n\t}\n\thandle @signin {\n\t\treverse_proxy medioo-web:3000\n\t}\n\thandle {\n\t\treverse_proxy medscribe-ai:8086\n\t}\n}\n",
    );
    expect(blocks).toEqual([
      { paths: ["/app", "/app/", "/app/verify"], methods: ["GET", "HEAD"], body: "reverse_proxy medioo-web:3000" },
      { paths: [], methods: null, body: "reverse_proxy medscribe-ai:8086" },
    ]);
  });
});
