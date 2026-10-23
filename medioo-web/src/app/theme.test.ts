import { describe, expect, it } from "vitest";
import { dataThemeOf, themeOf } from "./theme";

describe("themeOf (the baymax_theme cookie, plan W10)", () => {
  it("readsLightAndDarkExactly", () => {
    expect(themeOf("baymax_theme=dark")).toBe("dark");
    expect(themeOf("baymax_lang=en; baymax_theme=light")).toBe("light");
    expect(themeOf('baymax_theme="dark"')).toBe("dark");
  });

  it("isAutoForNoneAndForAnythingJavaWouldNeverSet", () => {
    expect(themeOf(undefined)).toBe("auto");
    expect(themeOf("")).toBe("auto");
    expect(themeOf("baymax_theme=auto")).toBe("auto");
    expect(themeOf("baymax_theme=DARK")).toBe("auto");
    expect(themeOf("baymax_theme=blue")).toBe("auto");
    // Another cookie whose name ends the same is not this one.
    expect(themeOf("x_baymax_theme=dark")).toBe("auto");
  });

  it("theFirstCookieOfThatNameWins", () => {
    expect(themeOf("baymax_theme=light; baymax_theme=dark")).toBe("light");
  });

  it("autoWritesNoAttribute", () => {
    expect(dataThemeOf("auto")).toBeUndefined();
    expect(dataThemeOf("light")).toBe("light");
    expect(dataThemeOf("dark")).toBe("dark");
  });
});
