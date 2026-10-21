import { describe, expect, it } from "vitest";
import bn from "./generated/copy.bn.json";
import en from "./generated/copy.en.json";
import vectors from "./generated/bangla-vectors.json";
import { t, type CopyKey, type Lang } from "./index";

// t() is held to what Java wrote (bangla-vectors.json, by BanglaGoldenVectorsTest calling UiCopy.raw), never to the JSON
// t() itself reads: comparing t() with its own source could not fail.
const counts = vectors.counts.map((v) => ({ lang: v.lang as Lang, args: v.args as Array<string | number>, out: v.out }));

function vector(lang: Lang, kind: "number" | "string") {
  const found = counts.find((v) => v.lang === lang && typeof v.args[0] === kind);
  if (!found) throw new Error(`no ${kind} counts vector for ${lang}`);
  return found;
}

describe("copy", () => {
  it("bn and en have the same keys", () => {
    const keys = (o: object) => Object.keys(o).filter((k) => k !== "_meta").sort();
    expect(keys(bn).length).toBeGreaterThan(300);
    expect(keys(en)).toEqual(keys(bn));
  });

  it("has counts vectors in both languages, with number and string arguments", () => {
    expect(counts.length).toBeGreaterThanOrEqual(4);
    for (const lang of ["bn", "en"] as const) {
      vector(lang, "number");
      vector(lang, "string");
    }
  });

  it.each(counts)("t fills placeholders as Java does ($lang, $args)", ({ lang, args, out }) => {
    expect(t(lang, "result.counts", ...args)).toBe(out);
  });

  it("a number argument is Bangla digits on bn and ASCII on en; a string argument is kept", () => {
    const bnNumber = vector("bn", "number");
    const bnString = vector("bn", "string");
    // The vectors themselves must tell the two apart, or this test could not see string arguments being converted.
    expect(bnNumber.out).not.toBe(bnString.out);
    expect(t("bn", "result.counts", 12, 3)).toBe(bnNumber.out);
    expect(t("bn", "result.counts", "12", "3")).toBe(bnString.out);
    expect(t("en", "result.counts", 12, 3)).toBe(vector("en", "number").out);
    expect(t("en", "result.counts", "12", "3")).toBe(vector("en", "string").out);
    expect(vector("en", "number").out).toBe(vector("en", "string").out);
  });

  it("an unknown key throws", () => {
    expect(() => t("bn", "no.such.key" as CopyKey)).toThrow("no copy for key no.such.key");
    expect(() => t("en", "_meta" as CopyKey)).toThrow("no copy for key _meta");
  });
});
