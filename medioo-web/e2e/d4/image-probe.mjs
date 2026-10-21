// D4 (wave 2 plan, Task D4a): what a real browser does with every image on a read paper. Not a Playwright test — a
// plain Node script, so `playwright test` never collects it; ImageProbeAcceptanceTest (baymax, RUN_BROWSER_PROBE=true)
// starts the Java app on a real port, signs a family in and runs this against it.
//
//   node medioo-web/e2e/d4/image-probe.mjs --base http://127.0.0.1:<port> --cookie <session token> --doc <document id> \
//        --out build/d4/image-probe.json [--paths /app/home,/app/add]
//
// For each page (the paper, its /all, then any --paths): every <img> — src, currentSrc, complete, natural size, and,
// drawn to a canvas, a 5×5 grid of its pixels (or "tainted" when the browser refuses, which a cross-origin image is) —
// with the chain of responses its URL went through: each hop's status, origin and path, the origin of each Location,
// and the final status, content-type and body length. Also every CSP violation, console error and failed request.
// Signed query strings are never written: a hop is its origin and path, so the output holds no credential.
import { chromium } from "@playwright/test";
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";

const GRID = [0.1, 0.3, 0.5, 0.7, 0.9];

function args(argv) {
  const out = {};
  for (let i = 2; i < argv.length; i += 2) {
    if (!argv[i].startsWith("--")) throw new Error(`unexpected argument ${argv[i]}`);
    out[argv[i].slice(2)] = argv[i + 1];
  }
  for (const required of ["base", "cookie", "doc", "out"]) {
    if (!out[required]) throw new Error(`--${required} is required`);
  }
  return out;
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
  const base = a.base.replace(/\/$/, "");
  const paths = [`/app/documents/${a.doc}`, `/app/documents/${a.doc}/all`, ...(a.paths ? a.paths.split(",").filter(Boolean) : [])];
  const browser = await chromium.launch();
  try {
    const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
    // Path=/app, as the app sets it: the cookie reaches /app/** and nothing else
    await context.addCookies([{ name: "baymax_session", value: a.cookie, domain: new URL(base).hostname, path: "/app", httpOnly: true }]);
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
    mkdirSync(dirname(a.out), { recursive: true });
    writeFileSync(a.out, JSON.stringify(report, null, 2) + "\n");
  } finally {
    await browser.close();
  }
}

main().catch((e) => {
  console.error(`[image-probe] ${e && e.stack ? e.stack : e}`);
  process.exit(1);
});
