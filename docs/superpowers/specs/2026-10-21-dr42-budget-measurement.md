# DR-42 input: what a Next.js page costs over the wire (W1a measurement)

**Status:** measurement and proposal only. **The PO decides DR-42** (plan `2026-10-21-medioo-nextjs-rebuild.md`,
Part B, decision 1). This file does not decide it. Until the PO decides, W3 (sign-in) stays held and every W budget
test records numbers without enforcing a limit.

**Measured:** 2026-10-21, on branch `feat/medioo-web-skeleton` (PR W1a).

## The question

Spec principle 14 says "JS ≤ 40 KB, works without JS". A React app cannot meet that: the framework alone costs more.
Part B asks for a real measurement of a Next page, taken over the wire the way production serves it, so that the
budget is set from a measured number.

## What was measured

`/app/probe` is the smallest page that is still a real Medioo page:
- the phone shell: header, one heading and one paragraph of Bangla body text, and the tab bar;
- both self-hosted Noto subsets (`noto-sans-bengali.woff2` 69,784 B, `noto-sans-latin.woff2` 27,864 B);
- the App Router client runtime, rendered per request with a fresh CSP nonce.

The text is shipped copy (`landing.h1`, `landing.sub`, `tab.*`), copied verbatim and pinned to the `.properties`
by `probe-copy.test.ts`.

The fonts are declared with `url()` only, without production's `local()` first. That makes the probe measure the worst
case, a phone without Noto, and `budget.spec.ts` fails if either font is not fetched. A measurement that did not
download the fonts would be smaller than the truth, not a smaller page.

**Setup.** The numbers were taken in four places. The table uses the first.

| | Server | In front | Client |
|---|---|---|---|
| Primary | the `medioo-web` Docker image (`node:22-alpine`, Node 22.23.3, `node server.js` from `output: "standalone"`) | local Caddy v2.11.7 with the production `medioo.eliooo.org` block | Playwright 1.64.1 Chromium, Pixel 7 profile at 390×844, fresh context |
| Same image, curl | as above | as above | `curl` with `Accept-Encoding: zstd, gzip`, then `identity` |
| `next start` | `next start` (Node 24.6.0) | the same Caddy block | the same Chromium; agrees with the primary within 0.05% |
| Today's Java pages | production, `https://medioo.eliooo.org` (HTTP/2) | production Caddy | the same Chromium, for comparison |

The production site block was read from `/home/ec2-user/n8n/Caddyfile` on the box, read-only. Only the address
(plain http on a local port) and the upstream changed:

```
encode zstd gzip
header {
	Strict-Transport-Security "max-age=31536000"
	X-Content-Type-Options "nosniff"
	Referrer-Policy "strict-origin-when-cross-origin"
	-Server
}
reverse_proxy <upstream>
```

**Byte columns:**
- **wire** is the encoded body that crossed the network, from Chromium's network stack (`request.sizes().responseBodySize`). It is not a `content-length` header: the streamed HTML has none.
- **decoded** is what the browser parsed.

Headers are not counted.

## Results: cold first load of `/app/probe`, phone, behind Caddy

Three runs gave the same bytes, within 6 B of HTML chunk framing.

| Class | Requests | Encoding on the wire | Wire (B) | Decoded (B) | Share of wire |
|---|---:|---|---:|---:|---:|
| document (HTML, inline RSC payload) | 1 | gzip (by Next) | 3,343 | 7,896 | 1.6% |
| stylesheet | 1 | gzip (by Next) | 805 | 1,739 | 0.4% |
| script (Next + React runtime) | 4 | gzip by Next; one small chunk zstd by Caddy | **102,171** | 351,031 | 50.1% |
| font (two woff2 subsets) | 2 | none (already compressed) | **97,648** | 97,648 | 47.9% |
| RSC (separate flight requests) | 0 | | 0 | 0 | 0% |
| **Total** | **8** | | **203,968** | **458,314** | |

Script detail:

| Chunk | Wire (B) | Decoded (B) |
|---|---:|---:|
| `36602703-*.js` (React DOM) | 54,416 | 173,019 |
| `55-*.js` (Next app-router runtime) | 45,847 | 174,128 |
| `webpack-*.js` | 1,686 | 3,329 |
| `main-app-*.js` | 222 | 555 |

`polyfills-*.js` is `noModule`, so a modern browser never fetches it. `next build` reports "First Load JS shared by
all: 102 kB", which agrees with the wire column.

### For comparison: today's Java pages in production (same client, 2026-10-21)

| Page | JS (B) | CSS (B) | Fonts (B) | Images (B) | HTML (B) | Total wire (B) |
|---|---:|---:|---:|---:|---:|---:|
| `/app/` (sign-in) | 0 | 13,035 | 97,927 | 0 | 1,438 | **112,400** |
| `/` (landing) | 0 | 13,017 | 97,936 | 31,531 | 4,051 | **146,535** |

The Next probe costs about **92 KB more on the wire** than today's sign-in page, and nearly all of it is the
framework's JavaScript. The fonts are the same bytes in both stacks.

At 1.6 Mbit/s, the throughput Lighthouse uses for "slow 4G", the probe's 203,968 B take about 1.0 s to transfer. The
Java sign-in page takes about 0.56 s. That is transfer time only, before round trips and parse.

## Findings that bear on W1b and W1c

These came up while measuring. None is changed in this PR.

1. **Production Caddy overwrites `Referrer-Policy`.**
   - The `header` block sets `strict-origin-when-cross-origin`. Because the block also deletes a header (`-Server`), it runs when the response is written, so it replaces the `same-origin` that Next sends.
   - The plan's Global Constraints require `same-origin`, and Java pages already receive Caddy's value today.
   - Behind the replica, `csp.spec.ts` fails on exactly this: `Expected: "same-origin"` / `Received: "strict-origin-when-cross-origin"`. Every other assertion passes there.
   - **W1c needs a choice:** drop that line from the medioo block, or accept Caddy's value and change the constraint. Either way it is a PO-visible header change on the live site.
2. **Next compresses its own responses, and Caddy passes them through.**
   - Next gzips by default. Caddy does not re-encode a body that already has a `Content-Encoding`, so the browser gets gzip, not zstd.
   - With Next's compression off (`compress: false`), Caddy's zstd at its default level came out **2.5% larger** in total: 206,450 B against 203,968 B. Scripts grew from 102,188 to 105,472 B.
   - **Keep Next's default.** This PR does.
3. **Static paths must move with the first route.**
   - A Next page loads `/_next/static/*` and `/fonts/*`. If W1c routes a page to `medioo-web` but leaves those paths on Java's default, the HTML arrives and its scripts and fonts 404.
   - The per-path table in `Caddyfile.medioo` needs `/_next/*` and `/fonts/*` routed to `medioo-web` the first time any HTML route flips. `/app/healthz` alone does not need them.
4. **Font caching.**
   - Next serves `public/` with `Cache-Control: public, max-age=0`, so a repeat visit revalidates both fonts (a 304 each).
   - Today's Java assets carry no `Cache-Control` at all.
   - `/_next/static/*` is `max-age=31536000, immutable`.
   - W2a, which owns the fonts, should serve them under hashed names with the immutable header. Repeat visits then cost the HTML only.
5. **Image and memory.**
   - The image is 242 MB uncompressed.
   - It reports `healthy` through the Dockerfile `HEALTHCHECK` within 25 s.
   - Idle, it uses 49–60 MiB under `--memory=320m`, in line with `bddrug-web`'s ~75 MiB on the box.
   - The 242 MB matters for X0's disk work: the box had 232 MB free when the plan was written.
6. **No RSC requests on a cold load.**
   - The flight payload is inlined in the HTML: 7.9 KB decoded, mostly the payload.
   - RSC requests appear only on client-side navigation (`next/link`). A navigation budget is a separate number that this probe does not measure.

## Proposal for the PO (DR-42 is the PO's to write)

1. **No-JS scope (plan A4).**
   - Reading pages are server-rendered and readable with JavaScript off: the timeline, a paper, a NOW result, and the opt-out confirmation.
   - Every mutation needs JavaScript: sign-in, upload, delete, settings and দেখেছি.
   - The probe already passes a no-JS reading test (`reading.spec.ts`), so every later page inherits the check.
2. **First-load wire budget per routed page: ≤ measured probe + 60 KB.**
   - 203,968 B + 61,440 B (60 × 1024) = **265,408 B**.
   - It is measured exactly as `budget.spec.ts` measures: cold, phone profile, both fonts counted, behind `encode zstd gzip`.
   - The route's own code, CSS, copy and data get 60 KB on the wire above the framework floor.
3. **The floor gets its own ceiling.**
   - Probe ≤ 203,968 B + 5% = **214,166 B**.
   - Without it, a Next or React upgrade that grows the runtime would silently eat every route's 60 KB. With it, that growth fails the probe and becomes a PO decision.
4. **Where the numbers live.**
   - Both ceilings go in one committed file, read by the budget tests.
   - The tests assert against that file, never against a probe measured in the same run, which would move with the framework.
   - The file changes only with PO sign-off.
5. **What "JS ≤ 40 KB" becomes.**
   - The framework's JavaScript alone is 102,171 B on the wire, 2.5× the old limit, before any Medioo code.
   - The proposal replaces the JS-only limit with the total first-load limit in item 2. The total includes fonts, which are half the bytes and the same cost the Java pages pay today.

**Decisions for the PO:**
- Accept item 2's 60 KB margin, or name another.
- Accept item 3's 5% floor tolerance.
- Accept the no-JS scope in item 1. That releases W3.
- Separately, for W1c: choose a Referrer-Policy (finding 1).

## How to reproduce

```bash
pnpm -C medioo-web install --frozen-lockfile
pnpm -C medioo-web build
pnpm -C medioo-web exec playwright test -c e2e/playwright.config.ts   # writes medioo-web/test-results/budget.json
# behind Caddy: run the production block above in front of the server, then
E2E_BASE_URL=http://127.0.0.1:<caddy-port> pnpm -C medioo-web exec playwright test -c e2e/playwright.config.ts e2e/budget.spec.ts
```
