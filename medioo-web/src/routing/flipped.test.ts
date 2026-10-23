import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { FLIPPED } from "./flipped";
import { JAVA_PAGES } from "./paths";

type Block = { paths: string[]; regexps: string[]; methods: string[] | null; body: string };

/**
 * The `handle` blocks of a Caddyfile site, each with the paths it matches, the `path_regexp` patterns it matches, the
 * methods (null for any) and its body: `handle /a /b {` takes inline paths; `handle @name {` takes the named matcher
 * `@name { path …; path_regexp [name] …; method … }` or `@name path …` defined in the same file.
 */
export function handleBlocks(text: string): Block[] {
  const lines = text.split("\n").map((l) => l.replace(/#.*$/, "").trim());
  const matchers = new Map<string, { paths: string[]; regexps: string[]; methods: string[] | null }>();
  for (let i = 0; i < lines.length; i++) {
    const m = /^@(\S+)\s*(.*)$/.exec(lines[i]!);
    if (!m) continue;
    const def = { paths: [] as string[], regexps: [] as string[], methods: null as string[] | null };
    const take = (directive: string) => {
      const [name, ...args] = directive.split(/\s+/);
      if (name === "path") def.paths.push(...args);
      // `path_regexp [<name>] <regexp>`: the regexp is the last argument either way
      if (name === "path_regexp" && args.length > 0) def.regexps.push(args[args.length - 1]!);
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
      regexps: named ? named.regexps : [],
      methods: named ? named.methods : null,
      body: body.join("\n"),
    });
  }
  return blocks;
}

const NOT_PAGES = new Set(["/app/healthz", "/app/preview", "/app/preview/*", "/_next/static/*", "/fonts/*", "/brand/*"]);

/** A FLIPPED pattern with each `{name}` segment filled by a fictional uuid: an address its regexp route must take. */
const SAMPLE_ID = "5e9edf6d-0a2a-4913-8675-9d07a4100001";
const instance = (pattern: string) => pattern.replace(/\{\w+\}/g, SAMPLE_ID);

/**
 * The FLIPPED patterns a Caddyfile's Next page blocks stand for: every literal `path`, and for each `path_regexp` every
 * dynamic FLIPPED pattern whose instance it matches (F6's one regexp stands for three pages). A regexp that matches
 * none is returned as itself (`path_regexp <re>`). A regexp is also tried on an instance of each `java` pattern that
 * is not in FLIPPED, and each one it takes is returned as `path_regexp <re> takes <pattern>`: a regexp too wide for
 * its row sends a page that is still Java's to Next. Either way the equality in flippedIsTheCaddyfilesNextPaths names
 * it.
 */
export function nextPages(caddyfile: string, flipped: readonly string[], java: readonly string[]): string[] {
  const pages = handleBlocks(caddyfile)
    .filter((b) => b.body === "reverse_proxy medioo-web:3000")
    .filter((b) => b.methods === null || b.methods.includes("GET"));
  const literal = pages.flatMap((b) => b.paths).filter((p) => !NOT_PAGES.has(p));
  const dynamic = flipped.filter((p) => /\{\w+\}/.test(p));
  const javaOnly = java.filter((p) => !flipped.includes(p));
  const fromRegexps = pages
    .flatMap((b) => b.regexps)
    .flatMap((re) => {
      const takes = (p: string) => new RegExp(re).test(instance(p));
      const hits = dynamic.filter(takes);
      const tooWide = javaOnly.filter(takes).map((p) => `path_regexp ${re} takes ${p}`);
      return [...(hits.length > 0 ? hits : [`path_regexp ${re}`]), ...tooWide];
    });
  return [...new Set([...literal, ...fromRegexps])].sort();
}

describe("flipped", () => {
  it("flippedIsTheCaddyfilesNextPaths", () => {
    const caddyfile = readFileSync(join(__dirname, "..", "..", "..", "docs", "infra", "Caddyfile.medioo"), "utf8");
    const toNext = handleBlocks(caddyfile).filter((b) => b.body === "reverse_proxy medioo-web:3000");
    // Guard: the parser found the one block every version of the file routes to Next, so it did not read nothing.
    expect(toNext.flatMap((b) => b.paths), "the /app/healthz block was parsed").toContain("/app/healthz");
    expect([...FLIPPED].sort()).toEqual(nextPages(caddyfile, FLIPPED, JAVA_PAGES));
  });

  it("theParserReadsANamedMatcherWithItsMethods", () => {
    // The shape a flip PR writes (Task F): a GET/HEAD-only matcher, so POST /app/verify stays on Java.
    const blocks = handleBlocks(
      "site {\n\t@signin {\n\t\tmethod GET HEAD\n\t\tpath /app /app/ /app/verify\n\t}\n\thandle @signin {\n\t\treverse_proxy medioo-web:3000\n\t}\n\thandle {\n\t\treverse_proxy medscribe-ai:8086\n\t}\n}\n",
    );
    expect(blocks).toEqual([
      { paths: ["/app", "/app/", "/app/verify"], regexps: [], methods: ["GET", "HEAD"], body: "reverse_proxy medioo-web:3000" },
      { paths: [], regexps: [], methods: null, body: "reverse_proxy medscribe-ai:8086" },
    ]);
  });

  it("theParserReadsAPathRegexpMatcher", () => {
    // F10's shape: a page whose address carries an id is matched by a regexp, with or without a capture name.
    const blocks = handleBlocks(
      "site {\n\t@profile {\n\t\tmethod GET HEAD\n\t\tpath_regexp ^/app/patients/[0-9a-f-]{36}/profile$\n\t}\n\thandle @profile {\n\t\treverse_proxy medioo-web:3000\n\t}\n\t@named path_regexp id ^/x/[0-9]+$\n\thandle @named {\n\t\treverse_proxy medioo-web:3000\n\t}\n}\n",
    );
    expect(blocks).toEqual([
      { paths: [], regexps: ["^/app/patients/[0-9a-f-]{36}/profile$"], methods: ["GET", "HEAD"], body: "reverse_proxy medioo-web:3000" },
      { paths: [], regexps: ["^/x/[0-9]+$"], methods: null, body: "reverse_proxy medioo-web:3000" },
    ]);
  });

  it("aRegexpRouteIsTheFlippedPatternItTakes", () => {
    const profile =
      "\t@profile {\n\t\tmethod GET HEAD\n\t\tpath_regexp ^/app/patients/[0-9a-f-]{36}/profile$\n\t}\n\thandle @profile {\n\t\treverse_proxy medioo-web:3000\n\t}\n";
    const healthz = "\thandle /app/healthz {\n\t\treverse_proxy medioo-web:3000\n\t}\n";
    expect(nextPages(`site {\n${healthz}${profile}}\n`, ["/app/patients/{id}/profile"], JAVA_PAGES)).toEqual([
      "/app/patients/{id}/profile",
    ]);
    // The other direction, each way the two could disagree: a regexp that takes no FLIPPED pattern is named as itself,
    // and a FLIPPED pattern no regexp takes is absent, so the equality in flippedIsTheCaddyfilesNextPaths fails on either.
    expect(nextPages(`site {\n${healthz}${profile}}\n`, ["/app/patients/{id}"], [])).toEqual([
      "path_regexp ^/app/patients/[0-9a-f-]{36}/profile$",
    ]);
    expect(nextPages(`site {\n${healthz}}\n`, ["/app/patients/{id}/profile"], JAVA_PAGES)).toEqual([]);
  });

  it("aRegexpThatTakesSeveralFlippedPatternsStandsForEachOfThem", () => {
    // The plan's F6 row is one regexp for a paper, its /all page and a value page: it stands for all three.
    const papers =
      "\t@papers {\n\t\tmethod GET HEAD\n\t\tpath_regexp ^/app/documents/[0-9a-f-]{36}(/all|/values/[0-9a-f-]{36})?$\n\t}\n\thandle @papers {\n\t\treverse_proxy medioo-web:3000\n\t}\n";
    const flipped = ["/app/documents/{id}", "/app/documents/{id}/all", "/app/documents/{id}/values/{obs}"];
    expect(nextPages(`site {\n${papers}}\n`, flipped, JAVA_PAGES)).toEqual([...flipped].sort());
  });

  it("aRegexpThatAlsoTakesAJavaOnlyPageIsNamedWithThatPage", () => {
    // Too wide: the profile regexp widened to the patient page, which is still Java's. It takes the FLIPPED profile and
    // also /app/patients/{id}, a JAVA_PAGES pattern not in FLIPPED, so the equality must fail on that page by name.
    const wide =
      "\t@profile {\n\t\tmethod GET HEAD\n\t\tpath_regexp ^/app/patients/[0-9a-f-]{36}(/profile)?$\n\t}\n\thandle @profile {\n\t\treverse_proxy medioo-web:3000\n\t}\n";
    expect(nextPages(`site {\n${wide}}\n`, ["/app/patients/{id}/profile"], JAVA_PAGES)).toEqual([
      "/app/patients/{id}/profile",
      "path_regexp ^/app/patients/[0-9a-f-]{36}(/profile)?$ takes /app/patients/{id}",
    ]);
  });
});
