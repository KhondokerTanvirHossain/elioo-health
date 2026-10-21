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

// A name counts only when JUnit runs it as a test, whose simple name is what Snapshot.meta writes:
//  (a) the top-level class of a file (the file's name) whose source declares a test method, or
//  (b) a class declared with @Nested directly above it (other annotations may sit between), in such a file.
// Never a record, a support class with no test (testsupport/Snapshot), or an inner class JUnit does not run. B3's
// now-banner fixtures name NowBannerApiTest's @Nested NowBannerApiContractTest, which has no file of its own.
const TEST_METHOD = /^\s*@(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b/m;
const NESTED_CLASS =
  /^\s*@Nested\b(?:\s+@\w+(?:\([^)]*\))?)*\s+(?:(?:public|protected|private|static|final|abstract)\s+)*class\s+([A-Za-z_$][\w$]*)/gm;

function javaTestNames(javaRoot: string): Set<string> {
  const names = new Set<string>();
  const walk = (dir: string) => {
    for (const entry of readdirSync(dir)) {
      const path = join(dir, entry);
      if (statSync(path).isDirectory()) walk(path);
      else if (entry.endsWith(".java")) {
        const source = readFileSync(path, "utf8");
        if (!TEST_METHOD.test(source)) continue;
        names.add(entry.slice(0, -".java".length));
        for (const m of source.matchAll(NESTED_CLASS)) names.add(m[1]!);
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
      // Classes that exist in the test tree but are not tests: a support class with no @Test, a helper record.
      "api/planted/support.json": '{"_meta":{"generated_by":"baymax Snapshot"},"body":{}}\n',
      "api/planted/record.json": '{"_meta":{"generated_by":"baymax Fixture"},"body":{}}\n',
      // An inner class of a test file that is not @Nested: JUnit never runs it as a test.
      "api/planted/inner.json": '{"_meta":{"generated_by":"baymax Served"},"body":{}}\n',
    });
    expect(fixtureMetaOffenders(root, javaRoot).offenders).toEqual([
      `${join("api", "now-banner", "planted.json")}: generated_by "pending B3 NowBannerApiContractTest" is a placeholder, not a test`,
      `${join("api", "planted", "hand.json")}: no _meta.generated_by`,
      `${join("api", "planted", "inner.json")}: generated_by names Served, and no test class of that name exists`,
      `${join("api", "planted", "record.json")}: generated_by names Fixture, and no test class of that name exists`,
      `${join("api", "planted", "renamed.json")}: generated_by names NoSuchContractTest, and no test class of that name exists`,
      `${join("api", "planted", "support.json")}: generated_by names Snapshot, and no test class of that name exists`,
    ]);
  });

  it("aNestedTestClassAndATopLevelTestClassAreAccepted", () => {
    // The other direction: the rule still accepts what Java contract tests really are. NowBannerApiContractTest is
    // NowBannerApiTest's @Nested class with no file of its own; PaperApiContractTest is a top-level test file.
    const root = copyWith({
      "api/planted/nested.json": '{"_meta":{"generated_by":"baymax NowBannerApiContractTest"},"body":{}}\n',
      "api/planted/top.json": '{"_meta":{"generated_by":"baymax PaperApiContractTest"},"body":{}}\n',
    });
    const { read, offenders } = fixtureMetaOffenders(root, javaRoot);
    expect(read).toEqual(expect.arrayContaining([join("api", "planted", "nested.json"), join("api", "planted", "top.json")]));
    expect(offenders).toEqual([]);
  });
});
