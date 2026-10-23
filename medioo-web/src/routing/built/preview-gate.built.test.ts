import { spawn, type ChildProcess } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { createServer } from "node:net";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { afterAll, beforeAll, describe, expect, it } from "vitest";

/*
 * Runs on the production build (pnpm build && pnpm run test:built): the standalone server the image runs
 * (.next/standalone/server.js), behind stub Java (e2e/support/java-stub.mjs), asked the preview path as a browser asks
 * it. The preview gate lives in the reads (src/api/java.ts) and depends on the middleware's mark reaching them, which
 * depends on how the server is bound: bound to a loopback literal, Next turns the preview rewrite into a proxy to
 * itself and the middleware runs twice (src/middleware.ts). PR 155 measured /app/preview rendering 200 without reading
 * `me` on a standalone run bound to 127.0.0.1, while production (the image, 0.0.0.0) answered 307.
 *
 * - bound as the image binds it (HOSTNAME from the Dockerfile): signed out, the preview is the sign-in page holding it
 *   (307) after one read, `me`; a family outside the preview reads `me` and gets the 404;
 * - bound to 127.0.0.1: the preview fails closed, a 404 with no read at all, never the ungated page.
 * Each case asserts what Java was asked, not only the status: the 404 and the 307 are also what an ungated page gives.
 */
const web = join(dirname(fileURLToPath(import.meta.url)), "..", "..", "..");
const standalone = join(web, ".next", "standalone");

/** The HOSTNAME the image runs the server with, read from the Dockerfile, never restated. */
function imageHostname(): string {
  const value = /^\s*(?:ENV\s+)?HOSTNAME=(\S+?)\s*\\?$/m.exec(readFileSync(join(web, "Dockerfile"), "utf8"))?.[1];
  if (!value) throw new Error("medioo-web/Dockerfile sets no HOSTNAME=");
  return value;
}

function freePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const s = createServer();
    s.once("error", reject);
    s.listen(0, "127.0.0.1", () => {
      const { port } = s.address() as { port: number };
      s.close(() => resolve(port));
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

type Run = { base: string; stub: string; children: ChildProcess[] };

async function start(hostname: string): Promise<Run> {
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
    env: { ...process.env, NODE_ENV: "production", PORT: String(port), HOSTNAME: hostname, JAVA_INTERNAL_URL: stub, GIT_SHA: "built-test" },
    stdio: "ignore",
  });
  const run = { base, stub, children: [java, next] };
  await waitFor(`${stub}/__requests`);
  await waitFor(`${base}/app/healthz`);
  return run;
}

/** GET <path> as a browser with the given cookies, redirects not followed; and every path stub Java was asked meanwhile. */
async function ask(run: Run, path: string, cookie: string): Promise<{ status: number; location: string | null; java: string[] }> {
  await fetch(`${run.stub}/__requests`, { method: "DELETE" });
  const response = await fetch(`${run.base}${path}`, { redirect: "manual", headers: { cookie } });
  await response.arrayBuffer();
  const log = (await (await fetch(`${run.stub}/__requests`)).json()) as { method: string; path: string }[];
  return { status: response.status, location: response.headers.get("location"), java: log.map((r) => `${r.method} ${r.path}`) };
}

const SIGNED_OUT = "e2e_scenario=signed-out";
const OUTSIDE = "e2e_scenario=preview-off; baymax_session=e2e";

describe.each([
  ["as the image binds it", imageHostname(), "open"],
  ["bound to 127.0.0.1", "127.0.0.1", "closed"],
])("the preview gate on the standalone server, %s", (_label, hostname, mode) => {
  let run: Run;
  beforeAll(async () => {
    run = await start(hostname);
  }, 30_000);
  afterAll(() => {
    for (const child of run?.children ?? []) child.kill();
  });

  it("signed out, the preview is the sign-in page holding it, after reading me alone", async () => {
    for (const [path, next] of [
      ["/app/preview", "%2Fapp%2Fpreview"],
      ["/app/preview/home", "%2Fapp%2Fpreview%2Fhome"],
    ] as const) {
      const got = await ask(run, path, SIGNED_OUT);
      if (mode === "open") {
        expect([got.status, got.location], path).toEqual([307, `/app/?next=${next}`]);
        expect(got.java, `${path}: what Java was asked`).toEqual(["GET /app/api/me"]);
      } else {
        expect([got.status, got.java], `${path}: fails closed, asking Java nothing`).toEqual([404, []]);
      }
    }
  });

  it("a family outside the preview gets the 404 after reading me alone", async () => {
    const got = await ask(run, "/app/preview/home", OUTSIDE);
    expect(got.status).toBe(404);
    expect(got.java, "what Java was asked").toEqual(mode === "open" ? ["GET /app/api/me"] : []);
  });
});
