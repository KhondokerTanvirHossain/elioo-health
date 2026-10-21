import { readFileSync } from "node:fs";
import { join } from "node:path";
import { cleanup, render } from "@testing-library/react";
import postcss from "postcss";
import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, describe, expect, it } from "vitest";
import { t } from "@/copy";
import { NOW_ALLOWED, WEEK_ALLOWED } from "@/styles/now-usage";
import { Toast } from "./Toast";
import { ToastRegion } from "./ToastRegion";

afterEach(cleanup);

/** A class list is red (or amber) when one of its classes, as a selector, is an allowed NOW (or THIS_WEEK) class. */
const urgent = (el: Element) => [...el.classList].filter((c) => NOW_ALLOWED.test(`.${c}`) || WEEK_ALLOWED.test(`.${c}`));

const regionOf = (container: HTMLElement) => {
  const regions = container.querySelectorAll('[role="status"]');
  expect(regions.length, "exactly one status region").toBe(1);
  return regions[0] as HTMLElement;
};

describe("Toast", () => {
  it("aToastIsNeverRed", () => {
    for (const lang of ["bn", "en"] as const) {
      const { container } = render(<Toast messageKey="profile.saved" lang={lang} />);
      const region = regionOf(container);
      expect(region.textContent).toBe(t(lang, "profile.saved"));
      // No element of it carries a NOW or THIS_WEEK class (read through the allow-lists, never as literals here).
      const all = [region, ...region.querySelectorAll("*")];
      expect(all.flatMap(urgent)).toEqual([]);
      // Guard: the toast itself was among the elements judged.
      expect(region.querySelector(".toast"), "the toast inside the region").not.toBeNull();
      cleanup();
    }
    // Nor does its stylesheet paint with the NOW or THIS_WEEK tokens.
    const css = readFileSync(join(__dirname, "Toast.css"), "utf8");
    const used: string[] = [];
    postcss.parse(css).walkDecls((decl) => {
      if (/var\(\s*--(on-)?(now|week)(?![\w])|var\(\s*--(now|week)-/.test(decl.value)) used.push(`${decl.prop}: ${decl.value}`);
    });
    expect(used).toEqual([]);
    // Guard: the stylesheet was read and has declarations.
    expect(css).toMatch(/\.toast\s*\{/);
  });

  it("theRegionIsThereBeforeItsMessage", () => {
    // A live region inserted with its text already inside is often not announced (VoiceOver iOS, NVDA on status): the
    // region must be in the DOM first and the text arrive as a change to it. The first render, what the server sends
    // and what the browser inserts on mount, is the region with nothing in it.
    for (const lang of ["bn", "en"] as const) {
      const html = renderToStaticMarkup(<Toast messageKey="profile.saved" lang={lang} />);
      const doc = new DOMParser().parseFromString(html, "text/html");
      const region = doc.querySelectorAll('[role="status"]');
      expect(region.length, `${lang}: the region is in the first render`).toBe(1);
      expect(region[0]!.getAttribute("aria-live")).toBe("polite");
      expect(region[0]!.textContent, `${lang}: and holds no text yet`).toBe("");
      // Guard: the message the region will get is not empty, so "no text" is not true of the toast anyway.
      expect(t(lang, "profile.saved").length).toBeGreaterThan(0);
    }
  });

  it("aMessageIsWrittenIntoTheRegionAlreadyMounted", () => {
    // The caller keeps the region mounted and swaps the message: the same element receives the text.
    const { container, rerender } = render(<ToastRegion message={null} />);
    const region = regionOf(container);
    expect(region.textContent).toBe("");
    rerender(<ToastRegion message={t("bn", "profile.saved")} />);
    expect(regionOf(container), "the same region element").toBe(region);
    expect(region.textContent).toBe(t("bn", "profile.saved"));
    rerender(<ToastRegion message={t("en", "progress.sent", 3)} />);
    expect(regionOf(container)).toBe(region);
    expect(region.textContent).toBe(t("en", "progress.sent", 3));
    rerender(<ToastRegion message={null} />);
    expect(regionOf(container)).toBe(region);
    expect(region.textContent).toBe("");
  });

  it("theMessageTakesItsArguments", () => {
    const { container } = render(<Toast messageKey="progress.sent" args={[3]} lang="bn" />);
    expect(regionOf(container).textContent).toBe(t("bn", "progress.sent", 3));
  });
});
