import { spawn, type ChildProcess } from "node:child_process";
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { createServer } from "node:net";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { concretePath } from "../../../e2e/support/budget";
import { PAGE_PATHS, PAGE_SCENARIOS, signedOut, withNowName } from "../../../e2e/support/scenarios.mjs";
import { NEXT_PAGES, previewPath } from "../paths";

/*
 * Runs on the production build (pnpm build && pnpm run test:built). The desktop layout (DesktopShell, the sidebar, the
 * NOW bar, the paper viewer, the conversation and the details panel; plan desktop-parity) renders only where
 * desktopLayout(ctx) says so: on preview requests, until the flip (Task 10). Everything it alone needs must therefore
 * reach only a page that renders it, or every live page pays for it inside the DR-42 route ceiling, which is never
 * raised (owner decision 2026-11-07, option a): each desktop part's stylesheets and client islands sit behind its
 * loader (a `desk-load.tsx`, next/dynamic), and react-loadable-manifest links them only when the part renders.
 *
 * Two checks over the standalone server (what the image runs), behind stub Java, on every routed page as budget.spec.ts
 * measures it (NEXT_PAGES, under PAGE_SCENARIOS, and each page's NOW twin):
 * - desktopLoaderFilesReachOnlyThePreview: the files the build's react-loadable-manifest lists for the desktop loaders
 *   (derived from the build, never listed here) are referenced by no live page, and by the preview pages that render
 *   the parts, which shows the reference would be seen were it there;
 * - noLivePageStylesTheDesktopsMarkup: no stylesheet a live page links styles a class that only the desktop layout
 *   renders: a class in a preview page's markup that no live page's markup has (derived from the responses). This one
 *   does not depend on the loaders existing, so it names the defect on a build where the desktop's CSS is imported
 *   statically.
 * Each list is guarded against being empty: an empty list would pass both checks vacuously.
 */
const web = join(dirname(fileURLToPath(import.meta.url)), "..", "..", "..");
const src = join(web, "src");
const standalone = join(web, ".next", "standalone");

/** The HOSTNAME the image runs the server with, read from the Dockerfile (preview-gate.built.test.ts: bound otherwise, the preview fails closed). */
function imageHostname(): string {
  const value = /^\s*(?:ENV\s+)?HOSTNAME=(\S+?)\s*\\?$/m.exec(readFileSync(join(web, "Dockerfile"), "utf8"))?.[1];
  if (!value) throw new Error("medioo-web/Dockerfile sets no HOSTNAME=");
  return value;
}

function freePort(): Promise<number> {
  return new Promise((done, fail) => {
    const s = createServer();
    s.once("error", fail);
    s.listen(0, "127.0.0.1", () => {
      const { port } = s.address() as { port: number };
      s.close(() => done(port));
    });
  });
}

async function waitFor(url: string): Promise<void> {
  for (let i = 0; i < 100; i++) {
    try {
      if ((await fetch(url)).status < 500) return;
    } catch {
      // not listening yet
    }
    await new Promise((r) => setTimeout(r, 100));
  }
  throw new Error(`nothing answered ${url}`);
}

type Run = { base: string; children: ChildProcess[] };

/** The standalone server bound as the image binds it, and stub Java it reads (GET only: DR-40 A3). */
async function start(): Promise<Run> {
  if (!existsSync(join(standalone, "server.js"))) throw new Error("run pnpm build first: no .next/standalone/server.js");
  const [stubPort, port] = [await freePort(), await freePort()];
  const stub = `http://127.0.0.1:${stubPort}`;
  const base = `http://127.0.0.1:${port}`;
  const java = spawn(process.execPath, [join(web, "e2e", "support", "java-stub.mjs")], {
    env: { ...process.env, STUB_PORT: String(stubPort), E2E_ORIGIN: base, E2E_FIXTURES: join(web, "fixtures", "api") },
    stdio: "ignore",
  });
  const next = spawn(process.execPath, ["server.js"], {
    cwd: standalone,
    env: { ...process.env, NODE_ENV: "production", PORT: String(port), HOSTNAME: imageHostname(), JAVA_INTERNAL_URL: stub, GIT_SHA: "built-test" },
    stdio: "ignore",
  });
  const run = { base, children: [java, next] };
  await waitFor(`${stub}/__requests`);
  await waitFor(`${base}/app/healthz`);
  return run;
}

/** One page's HTML as a browser under `scenario` gets it (redirects not followed: a 307 is not the page). */
async function page(run: Run, path: string, scenario: string): Promise<string> {
  const cookie = signedOut(scenario) ? `e2e_scenario=${scenario}` : `e2e_scenario=${scenario}; baymax_session=e2e`;
  const response = await fetch(`${run.base}${path}`, { redirect: "manual", headers: { cookie } });
  const html = await response.text();
  if (response.status !== 200) throw new Error(`${path} under ${scenario}: answered ${response.status}, not the page`);
  return html;
}

/**
 * Every build file a page references: a stylesheet or script tag, a preload, or a chunk the RSC payload names (React
 * loads a client component's chunks from there), as `static/css/…` or `static/chunks/…`, the spelling of Next's
 * manifests.
 */
function referenced(html: string): Set<string> {
  const unescaped = html.replace(/\\\//g, "/");
  return new Set([...unescaped.matchAll(/static\/(?:css|chunks)\/[A-Za-z0-9_\-./()[\]%~@]+?\.(?:css|js)/g)].map((m) => decodeURI(m[0])));
}

/**
 * The files of a page's own route entry: its script tags and the stylesheets Next links for its segments
 * (`data-precedence="next"`). A loader's file may also be one of these when webpack split a module the page itself
 * needs (the chat page's own composer) into a chunk the loader's import shares: the page loads it for itself, not for
 * the desktop. Everything a rendered loader adds is a preload or a `data-precedence="dynamic"` stylesheet instead.
 */
function entryOf(html: string): Set<string> {
  const tags = [...html.matchAll(/<(?:script|link)\b[^>]*>/g)].map((m) => m[0]);
  const scripts = tags.filter((tag) => tag.startsWith("<script") && /\ssrc="/.test(tag));
  const sheets = tags.filter((tag) => /\srel="stylesheet"/.test(tag) && /\sdata-precedence="next"/.test(tag));
  return new Set([...scripts, ...sheets].flatMap((tag) => [...referenced(tag)]));
}

/** The class names a page's markup renders (its `class` attributes). */
function classesOf(html: string): Set<string> {
  return new Set([...html.matchAll(/\sclass="([^"]*)"/g)].flatMap((m) => m[1]!.split(/\s+/).filter(Boolean)));
}

/** A class's component, its BEM block: `paper-viewer` for `paper-viewer__toolbar` and `paper-viewer--wide`. */
function blockOf(name: string): string {
  return name.split(/__|--/)[0]!;
}

/** The desktop loaders: every `desk-load.tsx` under src/, each a "use client" module of next/dynamic loaders. */
function loaders(dir = src): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((e) =>
    e.isDirectory() ? loaders(join(dir, e.name)) : e.name === "desk-load.tsx" ? [join(dir, e.name)] : [],
  );
}

/**
 * What the build's react-loadable-manifest lists for each desktop loader: per loader, the files of every next/dynamic
 * import it makes. A key is "<the importing module, relative to src/> -> <the import's request>" (Next's
 * ReactLoadablePlugin, relative to the project's src directory).
 */
function loaderFiles(): Map<string, Set<string>> {
  const path = join(web, ".next", "react-loadable-manifest.json");
  if (!existsSync(path)) throw new Error("run pnpm build first: no .next/react-loadable-manifest.json");
  const manifest = JSON.parse(readFileSync(path, "utf8")) as Record<string, { files: string[] }>;
  const out = new Map<string, Set<string>>(loaders().map((l) => [relative(web, l), new Set<string>()]));
  for (const [key, { files }] of Object.entries(manifest)) {
    const origin = relative(web, resolve(src, key.split(" -> ")[0]!));
    for (const f of files) out.get(origin)?.add(f);
  }
  return out;
}

/** A routed page as budget.spec.ts measures it, and its NOW twin (the banner shown, which a family sees on every page). */
const LIVE = NEXT_PAGES.flatMap((pattern) => {
  const scenario = PAGE_SCENARIOS[pattern as keyof typeof PAGE_SCENARIOS];
  if (scenario === undefined) throw new Error(`PAGE_SCENARIOS has no ${pattern}`);
  const path = concretePath(pattern, PAGE_PATHS);
  return signedOut(scenario) ? [{ path, scenario }] : [{ path, scenario }, { path, scenario: withNowName(scenario) }];
});

/** The desktop layout's pages, each the preview twin of a live page: home, and a NOW paper opened from a link. */
const PREVIEW = [
  { path: previewPath("/app/home"), scenario: "chat-preview" },
  { path: previewPath(concretePath("/app/documents/{id}", PAGE_PATHS)), scenario: "paper-now-bd-preview" },
];

describe("the desktop layout's CSS and JavaScript reach only the pages that render it", () => {
  let run: Run;
  const live: { path: string; scenario: string; html: string }[] = [];
  const preview: { path: string; scenario: string; html: string }[] = [];
  beforeAll(async () => {
    run = await start();
    for (const p of LIVE) live.push({ ...p, html: await page(run, p.path, p.scenario) });
    for (const p of PREVIEW) preview.push({ ...p, html: await page(run, p.path, p.scenario) });
  }, 60_000);
  afterAll(() => {
    for (const child of run?.children ?? []) child.kill();
  });

  it("desktopLoaderFilesReachOnlyThePreview", () => {
    const perLoader = loaderFiles();
    expect(
      [...perLoader.keys()],
      "the desktop loaders (desk-load.tsx under src/): none, so every desktop part's CSS and client islands are imported statically and ride in the route entry of every page that imports the shell",
    ).not.toEqual([]);
    const empty = [...perLoader].filter(([, files]) => files.size === 0).map(([loader]) => loader);
    expect(empty, "desktop loaders the build's react-loadable-manifest lists no file for").toEqual([]);
    const desktop = new Set([...perLoader.values()].flatMap((files) => [...files]));
    console.log(`desktop files (${desktop.size}): ${[...desktop].sort().join(", ")}`);
    expect([...desktop].filter((f) => f.endsWith(".css")), "the desktop loaders' stylesheets").not.toEqual([]);

    const leaks = live.flatMap(({ path, scenario, html }) => {
      const refs = referenced(html);
      const own = entryOf(html);
      return [...desktop].filter((f) => refs.has(f) && !own.has(f)).map((f) => `${path} (${scenario}) references ${f}`);
    });
    expect(leaks, "live pages referencing a desktop loader's file (beyond their own route entry's)").toEqual([]);

    // Each loader keeps a file of its own, which no live page references at all: its part's chunk is really apart. A
    // part pulled back into a route entry would leave its loader nothing but files that entry shares.
    const liveRefs = new Set(live.flatMap(({ html }) => [...referenced(html)]));
    const apart = new Map([...perLoader].map(([loader, files]) => [loader, [...files].filter((f) => !liveRefs.has(f))]));
    expect(
      [...apart].filter(([, files]) => !files.some((f) => f.endsWith(".js"))).map(([loader]) => loader),
      "desktop loaders whose every script a live page also references",
    ).toEqual([]);

    // The reference would be seen were it there: the preview pages, which render every part, reference each loader's
    // stylesheets and its own files.
    const previewRefs = new Set(preview.flatMap(({ html }) => [...referenced(html)]));
    const unseen = [...perLoader].flatMap(([loader, files]) =>
      [...files]
        .filter((f) => (f.endsWith(".css") || apart.get(loader)!.includes(f)) && !previewRefs.has(f))
        .map((f) => `${loader}: ${f}`),
    );
    expect(unseen, "desktop loaders' files the preview pages, which render every part, do not reference").toEqual([]);
  });

  it("noLivePageStylesTheDesktopsMarkup", () => {
    // By component (BEM block: `paper-viewer` for `paper-viewer__toolbar`), not by class: a modifier a phone page also
    // renders under a state no measured scenario shows (`mio-avatar--hop`, a first-run patient) is not the desktop's.
    const liveBlocks = new Set(live.flatMap(({ html }) => [...classesOf(html)].map(blockOf)));
    const desktopBlocks = [...new Set(preview.flatMap(({ html }) => [...classesOf(html)].map(blockOf)))].filter((b) => !liveBlocks.has(b)).sort();
    console.log(`components only the desktop layout renders (${desktopBlocks.length}): ${desktopBlocks.join(" ")}`);
    expect(desktopBlocks, "components only the desktop layout renders (the preview pages' markup, minus every live page's)").not.toEqual([]);

    const sheets = new Map<string, string>();
    const styled = new Map<string, { sheets: Set<string>; pages: Set<string> }>();
    for (const { path, scenario, html } of live) {
      for (const css of [...referenced(html)].filter((f) => f.endsWith(".css"))) {
        // The build's own bytes (the standalone server serves .next/static only once the image copies it there).
        const file = join(web, ".next", css);
        if (!existsSync(file)) throw new Error(`${path} links ${css}, which the build does not hold`);
        if (!sheets.has(css)) sheets.set(css, readFileSync(file, "utf8"));
        for (const block of desktopBlocks) {
          if (!new RegExp(`\\.${block}(?=__|--|[^\\w-]|$)`).test(sheets.get(css)!)) continue;
          const seen = styled.get(block) ?? { sheets: new Set<string>(), pages: new Set<string>() };
          seen.sheets.add(css);
          seen.pages.add(`${path} (${scenario})`);
          styled.set(block, seen);
        }
      }
    }
    expect(sheets.size, "stylesheets the live pages link").toBeGreaterThan(0);
    expect(
      [...styled].map(([block, seen]) => `.${block}: styled by ${[...seen.sheets].join(", ")} on ${seen.pages.size} live pages, e.g. ${[...seen.pages][0]}`),
      "live pages linking a stylesheet that styles a component only the desktop layout renders",
    ).toEqual([]);
  });
});
