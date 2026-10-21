import { describe, expect, it } from "vitest";
import { langOf } from "./lang";

// The language is bn by default; only the baymax_lang cookie changes it, exactly as Java's Lang.of reads it.
// Accept-Language is never an input.
describe("langOf", () => {
  it("is bn with no cookie header", () => {
    expect(langOf(undefined)).toBe("bn");
    expect(langOf("")).toBe("bn");
  });

  it("is en when the header holds baymax_lang=en", () => {
    expect(langOf("baymax_lang=en")).toBe("en");
    expect(langOf("x=1; baymax_lang=en; y=2")).toBe("en");
  });

  it("is bn for any other value, case included, as Lang.of", () => {
    expect(langOf("baymax_lang=EN")).toBe("bn");
    expect(langOf("baymax_lang=fr")).toBe("bn");
    expect(langOf("not_baymax_lang=en")).toBe("bn");
  });

  it("reads the first baymax_lang cookie, as Lang.of's getFirst does", () => {
    expect(langOf("baymax_lang=fr; baymax_lang=en")).toBe("bn");
    expect(langOf("baymax_lang=en; baymax_lang=fr")).toBe("en");
  });

  it("unwraps one pair of double quotes, as Netty's cookie decoder does before Lang.of sees the value", () => {
    expect(langOf('baymax_lang="en"')).toBe("en");
    expect(langOf('baymax_lang=""en""')).toBe("bn");
    expect(langOf('baymax_lang="en')).toBe("bn");
    expect(langOf('baymax_lang="fr"')).toBe("bn");
  });
});
