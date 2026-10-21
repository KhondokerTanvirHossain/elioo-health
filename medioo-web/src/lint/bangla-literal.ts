import { existsSync, readdirSync, readFileSync } from "node:fs";
import { extname, join, relative, resolve, sep } from "node:path";
import ts from "typescript";

/**
 * The Bangla-literal lint (master plan Global Constraints): no Bangla code point (U+0980–U+09FF) anywhere medioo-web's
 * build reads, outside copy that a Java test generated. All copy comes from the .properties bundles; a Bangla string
 * typed into the web app is copy that the English page and the copy owner never see.
 *
 * What is scanned is the union of two lists, because each misses what the other finds:
 * - the TypeScript program's own source files (tsconfig, every import followed, JSON modules included), which reaches a
 *   file imported from outside the root that no walk of the root would see;
 * - a walk of the root, which reaches every file the program never includes (eslint.config.mjs, a stray lib file, a
 *   JSON nothing imports yet).
 *
 * A file in a generated directory is exempt only when it is JSON whose `_meta.generated_by` names a Java test that
 * exists (`baymax <Name>` → `<Name>.java` under `javaTestRoot`). Where a file sits never makes it generated.
 */
export type BanglaScan = {
  scanned: string[];
  exempt: string[];
  offenders: string[];
  /** What the TypeScript program alone contributed, so a test can see that half of the scan is live. */
  program: string[];
};

export type BanglaScanOptions = {
  /** Directories, relative to the root and ending in `/`, whose files may be exempt when generated. */
  generatedDirs?: string[];
  /** Where the generating Java tests live, absolute or relative to the root. */
  javaTestRoot?: string;
};

// Written with escapes so the lint holds no literal of its own.
const BANGLA = /[\u0980-\u09FF]/u;

/**
 * Build outputs, skipped only directly under the root, as .gitignore and eslint.config.mjs anchor them: a source
 * directory of the same name deeper down (a route segment `src/app/out/`) is source and is walked. node_modules is
 * skipped at any depth.
 */
const ROOT_OUTPUT_DIRS = new Set([".next", "out", "test-results", "playwright-report"]);
const SKIPPED_EXTENSIONS = new Set([".woff2", ".webp", ".png", ".jpg", ".jpeg", ".ico"]);

/**
 * Files the walk never opens: `.env*` may hold secrets (repo rule: never read one), and .DS_Store is Finder metadata.
 * Neither is read by the build.
 */
function skippedFile(name: string): boolean {
  return name.startsWith(".env") || name === ".DS_Store";
}

const posix = (path: string) => path.split(sep).join("/");

function walk(dir: string, base: string = dir): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) {
      const skipped = entry.name === "node_modules" || (dir === base && ROOT_OUTPUT_DIRS.has(entry.name));
      return skipped ? [] : walk(path, base);
    }
    if (!entry.isFile() || skippedFile(entry.name) || SKIPPED_EXTENSIONS.has(extname(entry.name).toLowerCase())) return [];
    return [path];
  });
}

/**
 * The TypeScript program's source files, without node_modules; none when the root has no tsconfig.json. A tsconfig whose
 * program is empty throws: the scan would otherwise fall back to walk-only without a word and stop following imports.
 */
function programFiles(root: string): string[] {
  const configPath = join(root, "tsconfig.json");
  if (!existsSync(configPath)) return [];
  const read = ts.readConfigFile(configPath, ts.sys.readFile);
  if (read.error) throw new Error(`bangla-literal lint: cannot read ${configPath}`);
  const parsed = ts.parseJsonConfigFileContent(read.config, ts.sys, root);
  const program = ts.createProgram({ rootNames: parsed.fileNames, options: { ...parsed.options, noEmit: true } });
  const files = program
    .getSourceFiles()
    .map((f) => resolve(f.fileName))
    .filter((f) => !f.split(sep).includes("node_modules"));
  if (files.length === 0) throw new Error(`bangla-literal lint: the TypeScript program contributed zero files (${configPath})`);
  return files;
}

/** The simple names of every .java file under `dir`. */
function javaNames(dir: string | undefined): Set<string> {
  const names = new Set<string>();
  if (!dir || !existsSync(dir)) return names;
  const visit = (d: string) => {
    for (const entry of readdirSync(d, { withFileTypes: true })) {
      if (entry.isDirectory()) visit(join(d, entry.name));
      else if (entry.name.endsWith(".java")) names.add(entry.name.slice(0, -".java".length));
    }
  };
  visit(dir);
  return names;
}

/** True only for JSON whose `_meta.generated_by` is `baymax <Name>` with `<Name>.java` among the Java tests. */
function generatedByAnExistingTest(text: string, tests: Set<string>): boolean {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    return false;
  }
  const by = (parsed as { _meta?: { generated_by?: unknown } } | null)?._meta?.generated_by;
  const name = typeof by === "string" ? /^baymax ([A-Za-z_$][\w$]*)$/.exec(by)?.[1] : undefined;
  return name !== undefined && tests.has(name);
}

export function scanForBangla(root: string, options: BanglaScanOptions = {}): BanglaScan {
  const base = resolve(root);
  const generatedDirs = options.generatedDirs ?? [];
  const tests = javaNames(options.javaTestRoot === undefined ? undefined : resolve(base, options.javaTestRoot));

  const program = programFiles(base);
  const files = [...new Set([...program, ...walk(base)])];
  const scanned: string[] = [];
  const exempt: string[] = [];
  const offenders: string[] = [];

  for (const file of files.sort()) {
    const rel = posix(relative(base, file));
    const text = readFileSync(file, "utf8");
    scanned.push(rel);
    if (generatedDirs.some((dir) => rel.startsWith(dir)) && generatedByAnExistingTest(text, tests)) {
      exempt.push(rel);
      continue;
    }
    text.split("\n").forEach((line, i) => {
      if (BANGLA.test(line)) offenders.push(`${rel}:${i + 1}`);
    });
  }

  if (scanned.length === 0) throw new Error("bangla-literal lint scanned zero files");
  return {
    scanned: scanned.sort(),
    exempt: exempt.sort(),
    offenders,
    program: program.map((f) => posix(relative(base, f))).sort(),
  };
}
