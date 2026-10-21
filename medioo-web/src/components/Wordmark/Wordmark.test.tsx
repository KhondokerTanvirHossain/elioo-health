import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { Wordmark } from "./Wordmark";

afterEach(cleanup);

describe("Wordmark", () => {
  it("theWordmarkIsTheVectorMasterForEachTheme", () => {
    const { container } = render(<Wordmark lang="bn" />);
    // The picture follows prefers-color-scheme: the light master as the <img>, the dark master as the dark <source>.
    const picture = container.querySelector("picture.wordmark__auto");
    expect(picture, "the <picture> that follows the system scheme").not.toBeNull();
    const auto = picture!.querySelector("img")!;
    expect(auto.getAttribute("src")).toBe("/brand/medioo-light.svg");
    expect(auto.getAttribute("alt")).toBe("Medioo");
    const sources = [...picture!.querySelectorAll("source")];
    expect(sources.map((s) => [s.getAttribute("srcset"), s.getAttribute("media")])).toEqual([
      ["/brand/medioo-dark.svg", "(prefers-color-scheme: dark)"],
    ]);

    // [data-theme] overrides the system scheme with one <img> per theme, chosen in CSS (no script).
    const light = container.querySelector("img.wordmark--light")!;
    const dark = container.querySelector("img.wordmark--dark")!;
    expect(light.getAttribute("src")).toBe("/brand/medioo-light.svg");
    expect(dark.getAttribute("src")).toBe("/brand/medioo-dark.svg");
    // Lazy, so the override an unset data-theme hides (display: none) is never fetched: one wordmark file per load.
    expect([light.getAttribute("loading"), dark.getAttribute("loading")]).toEqual(["lazy", "lazy"]);

    const imgs = [...container.querySelectorAll("img")];
    expect(imgs).toHaveLength(3);
    for (const img of imgs) {
      expect(img.getAttribute("alt")).toBe("Medioo");
      expect(Number(img.getAttribute("width"))).toBeGreaterThan(0);
      expect(Number(img.getAttribute("height"))).toBeGreaterThan(0);
    }
  });

  it("theLatinNameIsMarkedEnglishOnlyOnABanglaPage", () => {
    const bn = render(<Wordmark lang="bn" />).container.querySelector(".wordmark")!;
    expect(bn.getAttribute("lang")).toBe("en");
    cleanup();
    const en = render(<Wordmark lang="en" />).container.querySelector(".wordmark")!;
    expect(en.hasAttribute("lang")).toBe(false);
  });
});
