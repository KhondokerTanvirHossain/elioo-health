import { mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, relative } from "node:path";
import { afterEach, describe, expect, it } from "vitest";

/*
 * The Wave 2 constraint "a generated file names an existing Java test", made executable over medioo-web/fixtures/
 * (plan W2c Step 3b): every JSON file there is something a Java contract test wrote, so a hand-made fixture that
 * guesses a contract (`"generated_by": "pending B3 …"`, or a test that was renamed away) cannot be committed.
 */

type Walk = { read: string[]; offenders: string[] };

// A class declared in a test source: the top-level class of its file, or a nested one (`@Nested class …`), whose
// simple name is what Snapshot.meta writes. B3's now-banner fixtures name NowBannerApiTest's nested
// NowBannerApiContractTest, which has no file of its own.
const CLASS_DECLARATION = /^\s*(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:public|protected|private|static|final|abstract|sealed)\s+)*(?:class|record)\s+([A-Za-z_$][\w$]*)/gm;

function javaTestNames(javaRoot: string): Set<string> {
  const names = new Set<string>();
  const walk = (dir: string) => {
    for (const entry of readdirSync(dir)) {
      const path = join(dir, entry);
      if (statSync(path).isDirectory()) walk(path);
      else if (entry.endsWith(".java")) {
        names.add(entry.slice(0, -".java".length));
        for (const m of readFileSync(path, "utf8").matchAll(CLASS_DECLARATION)) names.add(m[1]!);
      }
    }
  };
  walk(javaRoot);
  return names;
}

/** Every JSON file under `fixturesRoot`, and each one whose `_meta.generated_by` is not `baymax <a test class that exists>`. */
export function fixtureMetaOffenders(fixturesRoot: string, javaRoot: string): Walk {
  const tests = javaTestNames(javaRoot);
  if (tests.size === 0) throw new Error(`no Java tests under ${javaRoot}: every fixture would be judged against nothing`);
  const read: string[] = [];
  const offenders: string[] = [];
  const walk = (dir: string) => {
    for (const entry of readdirSync(dir).sort()) {
      const path = join(dir, entry);
      if (statSync(path).isDirectory()) {
        walk(path);
        continue;
      }
      const name = relative(fixturesRoot, path);
      if (!entry.endsWith(".json")) {
        offenders.push(`${name}: not JSON (a fixture is a generated JSON file)`);
        continue;
      }
      read.push(name);
      let by: unknown;
      try {
        by = (JSON.parse(readFileSync(path, "utf8")) as { _meta?: { generated_by?: unknown } } | null)?._meta?.generated_by;
      } catch {
        offenders.push(`${name}: not parseable JSON`);
        continue;
      }
      if (typeof by !== "string") offenders.push(`${name}: no _meta.generated_by`);
      else if (by.startsWith("pending ")) offenders.push(`${name}: generated_by "${by}" is a placeholder, not a test`);
      else {
        const m = /^baymax ([A-Za-z_$][\w$]*)$/.exec(by);
        if (!m) offenders.push(`${name}: generated_by "${by}" is not "baymax <TestClass>"`);
        else if (!tests.has(m[1]!)) offenders.push(`${name}: generated_by names ${m[1]}, and no test class of that name exists`);
      }
    }
  };
  walk(fixturesRoot);
  return { read, offenders };
}

const web = join(__dirname, "..", "..");
const fixtures = join(web, "fixtures");
const javaRoot = join(web, "..", "baymax", "src", "test", "java");

const made: string[] = [];
afterEach(() => {
  for (const dir of made.splice(0)) rmSync(dir, { recursive: true, force: true });
});

/** A copy of the real fixtures tree, plus `extra` files, in a temp directory. */
function copyWith(extra: Record<string, string>): string {
  const root = mkdtempSync(join(tmpdir(), "fixtures-meta-"));
  made.push(root);
  const copy = (from: string) => {
    for (const entry of readdirSync(from)) {
      const path = join(from, entry);
      if (statSync(path).isDirectory()) copy(path);
      else {
        const to = join(root, relative(fixtures, path));
        mkdirSync(dirname(to), { recursive: true });
        writeFileSync(to, readFileSync(path));
      }
    }
  };
  copy(fixtures);
  for (const [name, content] of Object.entries(extra)) {
    mkdirSync(dirname(join(root, name)), { recursive: true });
    writeFileSync(join(root, name), content);
  }
  return root;
}

describe("fixtures meta", () => {
  it("everyFixtureNamesAnExistingJavaTest", () => {
    const { read, offenders } = fixtureMetaOffenders(fixtures, javaRoot);
    expect(offenders).toEqual([]);
    // Guard: the walk read the paper fixtures and the catalog names, so the empty list above is not vacuous.
    expect(read.filter((f) => f.startsWith(join("api", "documents"))).length, "fixtures/api/documents read").toBeGreaterThan(0);
    expect(read).toContain(join("catalog", "names.json"));
  });

  it("aPendingFixtureFailsNamingItsFile", () => {
    const root = copyWith({
      "api/now-banner/planted.json": '{"_meta":{"generated_by":"pending B3 NowBannerApiContractTest"},"status":200,"body":{}}\n',
      "api/planted/renamed.json": '{"_meta":{"generated_by":"baymax NoSuchContractTest"},"body":{}}\n',
      "api/planted/hand.json": '{"body":{}}\n',
    });
    expect(fixtureMetaOffenders(root, javaRoot).offenders).toEqual([
      `${join("api", "now-banner", "planted.json")}: generated_by "pending B3 NowBannerApiContractTest" is a placeholder, not a test`,
      `${join("api", "planted", "hand.json")}: no _meta.generated_by`,
      `${join("api", "planted", "renamed.json")}: generated_by names NoSuchContractTest, and no test class of that name exists`,
    ]);
  });
});
