// D4 (wave 2 plan, Task D4a): what a real browser does with every image on a read paper. Not a Playwright test — a
// plain Node script, so `playwright test` never collects it; ImageProbeAcceptanceTest (baymax, RUN_BROWSER_PROBE=true)
// starts the Java app on a real port, signs a family in and runs this against it.
//
//   PROBE_SESSION=<session token> node medioo-web/e2e/d4/image-probe.mjs --base http://127.0.0.1:<port> \
//        --doc <document id> --out build/d4/image-probe.json
//
// The session comes from PROBE_SESSION (or stdin with --session-stdin), never argv, so it stays out of `ps` and shell
// history; --cookie is kept only as a last-resort fallback. The probe refuses any --base that is not this machine
// (localhost, 127.0.0.1, *.localhost): production is P4, a manual desktop-Chrome step, never this script with a real
// family's cookie. It refuses any --out that git does not ignore, so a report (which holds key paths) never lands in a
// tracked file.
//
// For each page (the paper, then its /all): every <img> — src, currentSrc, complete, natural size, and,
// drawn to a canvas, a 5×5 grid of its pixels (or "tainted" when the browser refuses, which a cross-origin image is) —
// with the chain of responses its URL went through: each hop's status, origin and path, the origin of each Location,
// and the final status, content-type and body length. Also every CSP violation, console error and failed request.
// Signed query strings are never written: a hop is its origin and path, so the output holds no credential.
import { chromium } from "@playwright/test";
import { execFileSync } from "node:child_process";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, isAbsolute, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), "../../..");

const GRID = [0.1, 0.3, 0.5, 0.7, 0.9];

const FLAGS = new Set(["session-stdin"]);

function args(argv) {
  const out = {};
  for (let i = 2; i < argv.length; i++) {
    if (!argv[i].startsWith("--")) throw new Error(`unexpected argument ${argv[i]}`);
    const name = argv[i].slice(2);
    if (FLAGS.has(name)) {
      out[name] = true;
    } else {
      out[name] = argv[++i];
    }
  }
  for (const required of ["base", "doc", "out"]) {
    if (!out[required]) throw new Error(`--${required} is required`);
  }
  return out;
}

/** Only this machine: a probe aimed elsewhere would carry a real family's session. */
function localBase(raw) {
  let url;
  try {
    url = new URL(raw);
  } catch {
    throw new Error("refusing --base: not a URL");
  }
  const host = url.hostname;
  if (host === "localhost" || host === "127.0.0.1" || host.endsWith(".localhost")) return url.origin;
  throw new Error(`refusing --base ${url.origin}: the probe runs only against an app on this machine; production is P4, a manual desktop-Chrome step`);
}

/** Inside this repo and ignored by git, so a report holding key paths can never be committed. */
function ignoredOut(raw) {
  const out = resolve(raw);
  const rel = relative(REPO, out);
  if (rel === "" || rel.startsWith("..") || isAbsolute(rel)) throw new Error(`refusing --out ${out}: not inside the repository`);
  try {
    execFileSync("git", ["check-ignore", "-q", rel], { cwd: REPO, stdio: "ignore" });
  } catch {
    throw new Error(`refusing --out ${rel}: git does not ignore it; write under build/d4/ (repo root) or <module>/build/`);
  }
  return out;
}

/** PROBE_SESSION, then stdin when asked, then --cookie; never echoed. */
function sessionToken(a) {
  if (process.env.PROBE_SESSION) return process.env.PROBE_SESSION;
  if (a["session-stdin"]) {
    const token = readFileSync(0, "utf8").trim();
    if (token) return token;
  }
  if (a.cookie) return a.cookie;
  throw new Error("no session: set PROBE_SESSION (or pass --session-stdin)");
}

/** origin + path: never the query, which on a signed URL is a credential. */
function bare(url) {
  try {
    const u = new URL(url);
    return u.origin + u.pathname;
  } catch {
    return String(url);
  }
}

function originOf(url) {
  try {
    return new URL(url).origin;
  } catch {
    return null;
  }
}

async function probePage(context, base, path) {
  const page = await context.newPage();
  const responses = [];
  const failed = [];
  const consoleErrors = [];
  page.on("response", async (response) => {
    const request = response.request();
    if (request.resourceType() !== "image" && request.resourceType() !== "document") return;
    const status = response.status();
    const headers = response.headers();
    const entry = {
      url: response.url(),
      type: request.resourceType(),
      status,
      location: headers["location"] ? new URL(headers["location"], response.url()).href : null,
      contentType: headers["content-type"] ?? null,
      bodyLength: null,
    };
    responses.push(entry);
    if (status < 300 || status >= 400) {
      try {
        entry.bodyLength = (await response.body()).length;
      } catch {
        entry.bodyLength = null;
      }
    }
  });
  page.on("requestfailed", (request) => {
    failed.push({ url: bare(request.url()), type: request.resourceType(), error: request.failure()?.errorText ?? null });
  });
  page.on("console", (message) => {
    if (message.type() === "error") consoleErrors.push(message.text().replace(/\?[^\s"']*/g, "?…"));
  });

  const main = await page.goto(base + path, { waitUntil: "load" });
  // scroll in steps so every loading="lazy" image enters the viewport and is requested
  await page.evaluate(async () => {
    const step = Math.max(200, Math.floor(window.innerHeight / 2));
    for (let y = 0; y <= document.documentElement.scrollHeight; y += step) {
      window.scrollTo(0, y);
      await new Promise((r) => setTimeout(r, 120));
    }
    window.scrollTo(0, document.documentElement.scrollHeight);
  });
  // a request the CSP blocks can stay pending for the page's network tracking: never idle is a finding, not a crash
  const networkIdle = await page.waitForLoadState("networkidle", { timeout: 15_000 }).then(() => true, () => false);
  await page.evaluate(() => Promise.all([...document.images].map((img) => (img.complete ? null : img.decode().catch(() => null)))));

  const imgs = await page.evaluate((grid) => {
    return [...document.images].map((img) => {
      const out = {
        src: img.getAttribute("src"),
        currentSrc: img.currentSrc,
        cls: img.className,
        complete: img.complete,
        naturalWidth: img.naturalWidth,
        naturalHeight: img.naturalHeight,
        samples: null,
        tainted: false,
      };
      if (img.naturalWidth > 0) {
        try {
          const canvas = document.createElement("canvas");
          canvas.width = img.naturalWidth;
          canvas.height = img.naturalHeight;
          const g = canvas.getContext("2d");
          g.drawImage(img, 0, 0);
          out.samples = [];
          for (const fx of grid) {
            for (const fy of grid) {
              const d = g.getImageData(Math.floor(img.naturalWidth * fx), Math.floor(img.naturalHeight * fy), 1, 1).data;
              out.samples.push({ fx, fy, r: d[0], g: d[1], b: d[2] });
            }
          }
        } catch (e) {
          out.tainted = true;
          out.samples = null;
          out.taintError = String(e && e.name);
        }
      }
      return out;
    });
  }, GRID);
  const violations = await page.evaluate(() => window.__cspViolations ?? []);

  // each <img>'s chain: from its absolute URL, follow Location through the responses this page saw
  for (const img of imgs) {
    const hops = [];
    // currentSrc is absolute once the image is fetched, but can be the bare attribute when the fetch was blocked
    let url = img.currentSrc || img.src ? new URL(img.currentSrc || img.src, base + path).href : null;
    img.currentSrc = url;
    const seen = new Set();
    while (url && !seen.has(url)) {
      seen.add(url);
      const r = responses.find((x) => x.url === url);
      if (!r) {
        hops.push({ url: bare(url), origin: originOf(url), status: null });
        break;
      }
      hops.push({
        url: bare(r.url),
        origin: originOf(r.url),
        status: r.status,
        locationOrigin: r.location ? originOf(r.location) : null,
        contentType: r.contentType,
        bodyLength: r.bodyLength,
      });
      url = r.status >= 300 && r.status < 400 ? r.location : null;
    }
    img.hops = hops;
    const last = hops[hops.length - 1] ?? null;
    img.final = last ? { url: last.url, origin: last.origin, status: last.status, contentType: last.contentType ?? null, bodyLength: last.bodyLength ?? null } : null;
    img.src = img.src ? img.src.split("?")[0] + (img.src.includes("?") ? "?…" : "") : null;
    img.currentSrc = img.currentSrc ? bare(img.currentSrc) : null;
  }
  await page.close();
  return {
    path,
    status: main ? main.status() : null,
    networkIdle,
    imgs,
    cspViolations: violations,
    consoleErrors,
    failedRequests: failed,
  };
}

async function main() {
  const a = args(process.argv);
  const base = localBase(a.base);
  const outFile = ignoredOut(a.out);
  const token = sessionToken(a);
  const paths = [`/app/documents/${a.doc}`, `/app/documents/${a.doc}/all`];
  const browser = await chromium.launch();
  try {
    const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
    // Path=/app, as the app sets it: the cookie reaches /app/** and nothing else
    await context.addCookies([{ name: "baymax_session", value: token, domain: new URL(base).hostname, path: "/app", httpOnly: true }]);
    await context.addInitScript(() => {
      window.__cspViolations = [];
      document.addEventListener("securitypolicyviolation", (e) => {
        window.__cspViolations.push({
          directive: e.effectiveDirective || e.violatedDirective,
          blocked: (() => {
            try {
              const u = new URL(e.blockedURI);
              return u.origin + u.pathname;
            } catch {
              return e.blockedURI;
            }
          })(),
        });
      });
    });
    const pages = [];
    for (const path of paths) pages.push(await probePage(context, base, path));
    const report = { base, doc: a.doc, pages };
    mkdirSync(dirname(outFile), { recursive: true });
    writeFileSync(outFile, JSON.stringify(report, null, 2) + "\n");
  } finally {
    await browser.close();
  }
}

main().catch((e) => {
  console.error(`[image-probe] ${e && e.stack ? e.stack : e}`);
  process.exit(1);
});
