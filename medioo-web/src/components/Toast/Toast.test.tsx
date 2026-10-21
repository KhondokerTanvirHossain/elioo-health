import { readFileSync } from "node:fs";
import { join } from "node:path";
import { cleanup, render } from "@testing-library/react";
import postcss from "postcss";
import { afterEach, describe, expect, it } from "vitest";
import { t } from "@/copy";
import { NOW_ALLOWED, WEEK_ALLOWED } from "@/styles/now-usage";
import { Toast } from "./Toast";

afterEach(cleanup);

/** A class list is red (or amber) when one of its classes, as a selector, is an allowed NOW (or THIS_WEEK) class. */
const urgent = (el: Element) => [...el.classList].filter((c) => NOW_ALLOWED.test(`.${c}`) || WEEK_ALLOWED.test(`.${c}`));

describe("Toast", () => {
  it("aToastIsNeverRed", () => {
    for (const lang of ["bn", "en"] as const) {
      const { container } = render(<Toast messageKey="profile.saved" lang={lang} />);
      const root = container.firstElementChild;
      expect(root, "Toast renders").not.toBeNull();
      expect(root!.getAttribute("role")).toBe("status");
      expect(root!.textContent).toBe(t(lang, "profile.saved"));
      // No element of it carries a NOW or THIS_WEEK class (read through the allow-lists, never as literals here).
      const all = [root!, ...root!.querySelectorAll("*")];
      expect(all.flatMap(urgent)).toEqual([]);
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

  it("theMessageTakesItsArguments", () => {
    const { container } = render(<Toast messageKey="progress.sent" args={[3]} lang="bn" />);
    expect(container.textContent).toBe(t("bn", "progress.sent", 3));
  });
});
