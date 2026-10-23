import { describe, expect, it } from "vitest";
import { buildCsp, newNonce } from "./csp";

/** Splits a policy into its directives, so assertions name a directive rather than a substring of the whole header. */
function directives(policy: string): Map<string, string> {
  const map = new Map<string, string>();
  for (const part of policy.split(";")) {
    const tokens = part.trim().split(/\s+/);
    const name = tokens.shift();
    if (name) map.set(name, tokens.join(" "));
  }
  return map;
}

describe("buildCsp", () => {
  it("allows scripts only from self and this request's nonce", () => {
    expect(directives(buildCsp("abc")).get("script-src")).toBe("'self' 'nonce-abc'");
  });

  it("allows styles only from self, never inline", () => {
    const policy = buildCsp("abc");
    expect(directives(policy).get("style-src")).toBe("'self'");
    expect(policy).not.toContain("unsafe-inline");
    expect(policy).not.toContain("unsafe-eval");
  });

  it("denies by default and refuses to be framed", () => {
    const d = directives(buildCsp("abc"));
    expect(d.get("default-src")).toBe("'none'");
    expect(d.get("frame-ancestors")).toBe("'none'");
  });

  it("is exactly the policy in the plan's Global Constraints", () => {
    expect(buildCsp("n0nce")).toBe(
      "default-src 'none'; script-src 'self' 'nonce-n0nce'; style-src 'self'; img-src 'self' https: http: blob:; " +
        "font-src 'self'; connect-src 'self'; frame-ancestors 'none'; form-action 'self'; base-uri 'none'",
    );
  });

  // img-src is copied verbatim from the Java page policy (BaymaxSecurityHeadersFilter.CSP): crops 302 to S3 and the
  // upload tray previews blob: URLs. If the two drift, one UI shows images the other blocks.
  it("uses the same img-src as the Java pages", () => {
    expect(directives(buildCsp("abc")).get("img-src")).toBe("'self' https: http: blob:");
  });

  it("differs between two nonces only in the nonce", () => {
    const a = buildCsp("first");
    const b = buildCsp("second");
    expect(a).not.toBe(b);
    expect(a.replace("'nonce-first'", "'nonce-X'")).toBe(b.replace("'nonce-second'", "'nonce-X'"));
  });

  it("mints a fresh 16-byte nonce that the policy accepts", () => {
    const a = newNonce();
    const b = newNonce();
    expect(a).not.toBe(b);
    expect(Buffer.from(a, "base64")).toHaveLength(16);
    expect(directives(buildCsp(a)).get("script-src")).toBe(`'self' 'nonce-${a}'`);
  });

  // A nonce that could carry a quote or a semicolon would let a caller append its own directive.
  it("refuses a nonce that is not base64", () => {
    expect(() => buildCsp("abc'; script-src *")).toThrow();
    expect(() => buildCsp("")).toThrow();
  });
});

describe("middleware", () => {
  /** The request headers the middleware hands the page: Next carries overrides as x-middleware-request-<name>. */
  function forwarded(response: Response): Map<string, string> {
    const names = (response.headers.get("x-middleware-override-headers") ?? "").split(",").filter(Boolean);
    return new Map(names.map((n) => [n, response.headers.get(`x-middleware-request-${n}`) ?? ""]));
  }
  const call = async (path: string, headers: Record<string, string> = {}) => {
    const { NextRequest } = await import("next/server");
    const { middleware } = await import("../middleware");
    return middleware(new NextRequest(new URL(path, "http://127.0.0.1:3000"), { headers }));
  };

  it("aPreviewPathIsRewrittenAndMarked", async () => {
    const response = await call("/app/preview/home?patient=p1");
    const target = new URL(response.headers.get("x-middleware-rewrite") ?? "http://unset/");
    expect(target.pathname).toBe("/app/home");
    expect(target.search).toBe("?patient=p1");
    const headers = forwarded(response);
    expect(headers.get("x-medioo-preview")).toBe("1");
    // The place the browser asked for, preview prefix included, so a sign-in hop returns to the preview.
    expect(headers.get("x-medioo-path")).toBe("/app/preview/home?patient=p1");
    expect(directives(response.headers.get("content-security-policy") ?? "").get("default-src")).toBe("'none'");
    expect(response.headers.get("x-frame-options")).toBe("DENY");
  });

  it("theBarePreviewPathIsRewrittenToo", async () => {
    const bare = await call("/app/preview");
    expect(new URL(bare.headers.get("x-middleware-rewrite") ?? "http://unset/").pathname).toBe("/app");
    expect(forwarded(bare).get("x-medioo-preview")).toBe("1");
    const slash = await call("/app/preview/");
    expect(new URL(slash.headers.get("x-middleware-rewrite") ?? "http://unset/").pathname).toBe("/app/");
    // A look-alike is not the preview.
    const lookalike = await call("/app/previewer");
    expect(lookalike.headers.get("x-middleware-rewrite")).toBeNull();
    expect(forwarded(lookalike).has("x-medioo-preview")).toBe(false);
  });

  it("anIncomingPreviewMarkIsDropped", async () => {
    const response = await call("/app/home", { "x-medioo-preview": "1", "x-medioo-path": "/app/elsewhere" });
    expect(response.headers.get("x-middleware-rewrite")).toBeNull();
    const headers = forwarded(response);
    expect(headers.has("x-medioo-preview"), "x-medioo-preview reaches the page").toBe(false);
    expect(headers.get("x-medioo-path")).toBe("/app/home");
    expect(response.headers.get("content-security-policy")).not.toBeNull();
  });

  // Next's own rewrite coming back: bound to a loopback literal (HOSTNAME=127.0.0.1), Next turns the preview rewrite
  // into a proxy to itself (its NextURL names the host `localhost`, its router compares with `127.0.0.1`), and the
  // middleware runs a second time on the rewritten path, carrying the first pass's mark and the preview path. Dropping
  // the mark there rendered /app/preview/<page> as the ungated /app/<page> (PR 155, measured on a standalone build).
  it("aPreviewRewriteThatComesBackIsRefusedNotServedUngated", async () => {
    const response = await call("/app/home", { "x-medioo-preview": "1", "x-medioo-path": "/app/preview/home" });
    expect(response.status, "the second pass of a preview rewrite").toBe(404);
    expect(response.headers.get("x-middleware-rewrite")).toBeNull();
    expect(response.headers.get("x-middleware-next"), "the page is not rendered").toBeNull();
    expect(directives(response.headers.get("content-security-policy") ?? "").get("default-src")).toBe("'none'");
    expect(response.headers.get("x-frame-options")).toBe("DENY");
    // the bare preview path's rewrite coming back to /app, too
    const bare = await call("/app", { "x-medioo-preview": "1", "x-medioo-path": "/app/preview" });
    expect(bare.status).toBe(404);
    expect(bare.headers.get("x-middleware-next")).toBeNull();
  });
});
