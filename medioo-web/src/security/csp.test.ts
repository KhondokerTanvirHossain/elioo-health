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
