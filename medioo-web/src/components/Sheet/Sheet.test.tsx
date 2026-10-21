import { readFileSync } from "node:fs";
import { join } from "node:path";
import { cleanup, fireEvent, render } from "@testing-library/react";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { t } from "@/copy";
import { Sheet } from "./Sheet";
import { SheetDialog } from "./SheetDialog";

afterEach(cleanup);

/*
 * jsdom has an HTMLDialogElement with no showModal() or close(). The two are given here with the platform's
 * documented effect on the element (open set; open cleared and a `close` event), and nothing else: the Escape
 * handling, the name and the close button under test are the component's own. The modal top layer and focus trap are
 * the browser's, checked in a real browser by catalog.spec.ts (theSheetIsModalAndEscapeClosesIt).
 */
beforeAll(() => {
  const proto = HTMLDialogElement.prototype as HTMLDialogElement & { showModal?: () => void; close?: () => void };
  if (typeof proto.showModal !== "function") {
    proto.showModal = function (this: HTMLDialogElement) {
      this.setAttribute("open", "");
    };
  }
  if (typeof proto.close !== "function") {
    proto.close = function (this: HTMLDialogElement) {
      if (!this.hasAttribute("open")) return;
      this.removeAttribute("open");
      this.dispatchEvent(new Event("close"));
    };
  }
});

/** The names of a component's props whose value can be a function. */
type FunctionProps<C extends (props: never) => unknown> = {
  [K in keyof Parameters<C>[0]]-?: Extract<NonNullable<Parameters<C>[0][K]>, (...args: never[]) => unknown> extends never ? never : K;
}[keyof Parameters<C>[0]];

const dialogOf = (container: HTMLElement) => {
  const d = container.querySelector("dialog");
  if (!d) throw new Error("Sheet renders no <dialog>");
  return d;
};

describe("Sheet", () => {
  it("escapeCloses", () => {
    const { container, getByText } = render(
      <Sheet titleKey="retake.tips" open lang="bn">
        <p>{t("bn", "retake.tip.1")}</p>
      </Sheet>,
    );
    const dialog = dialogOf(container);
    expect(dialog.open, "open after mount").toBe(true);
    fireEvent.keyDown(getByText(t("bn", "retake.tip.1")), { key: "Escape" });
    expect(dialog.open, "closed by Escape").toBe(false);
  });

  it("escapeTellsTheClientCaller", () => {
    // The client API (SheetDialog) is the one that takes onClose: its caller owns `open`.
    const onClose = vi.fn();
    const { container, getByText } = render(
      <SheetDialog title={t("bn", "retake.tips")} closeLabel={t("bn", "sheet.close")} open onClose={onClose}>
        <p>{t("bn", "retake.tip.1")}</p>
      </SheetDialog>,
    );
    fireEvent.keyDown(getByText(t("bn", "retake.tip.1")), { key: "Escape" });
    expect(dialogOf(container).open, "closed by Escape").toBe(false);
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("escapeClosesOnlyTheInnermostSheet", () => {
    // The platform closes only the topmost modal: an Escape inside a sheet opened over another sheet closes that one.
    const outerClose = vi.fn();
    const innerClose = vi.fn();
    const { container, getByText } = render(
      <SheetDialog title="outer" closeLabel="close outer" open onClose={outerClose}>
        <SheetDialog title="inner" closeLabel="close inner" open onClose={innerClose}>
          <p>inner body</p>
        </SheetDialog>
      </SheetDialog>,
    );
    const [outer, inner] = [...container.querySelectorAll("dialog")];
    expect(outer!.contains(inner!), "the inner sheet sits inside the outer one").toBe(true);
    fireEvent.keyDown(getByText("inner body"), { key: "Escape" });
    expect({ inner: inner!.open, outer: outer!.open }).toEqual({ inner: false, outer: true });
    expect(innerClose).toHaveBeenCalledTimes(1);
    expect(outerClose).not.toHaveBeenCalled();
  });

  it("anEscapeThatEndsACompositionDoesNotClose", () => {
    // Bangla is often typed through an IME (Avro, the phone keyboards), where Escape cancels the composition, not the sheet.
    const onClose = vi.fn();
    const { container, getByRole } = render(
      <SheetDialog title="tips" closeLabel="close" open onClose={onClose}>
        <input aria-label="name" />
      </SheetDialog>,
    );
    fireEvent.keyDown(getByRole("textbox", { hidden: true }), { key: "Escape", isComposing: true });
    expect(dialogOf(container).open, "still open after a composition's Escape").toBe(true);
    expect(onClose).not.toHaveBeenCalled();
  });

  it("anEscapeAChildHandledDoesNotClose", () => {
    // A control that handles Escape itself (a listbox closing its popup) says so with preventDefault; the sheet stays.
    const onClose = vi.fn();
    const { container, getByRole } = render(
      <SheetDialog title="tips" closeLabel="close" open onClose={onClose}>
        <input
          aria-label="name"
          onKeyDown={(e) => {
            if (e.key === "Escape") e.preventDefault();
          }}
        />
      </SheetDialog>,
    );
    fireEvent.keyDown(getByRole("textbox", { hidden: true }), { key: "Escape" });
    expect(dialogOf(container).open, "still open after an Escape the child handled").toBe(true);
    expect(onClose).not.toHaveBeenCalled();
  });

  it("anotherKeyDoesNotClose", () => {
    const onClose = vi.fn();
    const { container } = render(
      <SheetDialog title={t("bn", "retake.tips")} closeLabel={t("bn", "sheet.close")} open onClose={onClose}>
        <p>{t("bn", "retake.tip.1")}</p>
      </SheetDialog>,
    );
    const dialog = dialogOf(container);
    fireEvent.keyDown(dialog, { key: "Enter" });
    expect(dialog.open).toBe(true);
    expect(onClose).not.toHaveBeenCalled();
  });

  it("theCloseButtonCloses", () => {
    const onClose = vi.fn();
    const { container, getByRole } = render(
      <SheetDialog title={t("en", "retake.tips")} closeLabel={t("en", "sheet.close")} open onClose={onClose}>
        <p>{t("en", "retake.tip.1")}</p>
      </SheetDialog>,
    );
    fireEvent.click(getByRole("button", { name: t("en", "sheet.close"), hidden: true }));
    expect(dialogOf(container).open).toBe(false);
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("theTitleIsTheDialogsName", () => {
    for (const lang of ["bn", "en"] as const) {
      const { container } = render(
        <Sheet titleKey="retake.tips" open lang={lang}>
          <p>{t(lang, "retake.tip.1")}</p>
        </Sheet>,
      );
      const dialog = dialogOf(container);
      const id = dialog.getAttribute("aria-labelledby");
      expect(id, "aria-labelledby").toBeTruthy();
      const title = container.ownerDocument.getElementById(id!);
      expect(title, "the element aria-labelledby names").not.toBeNull();
      expect(dialog.contains(title)).toBe(true);
      expect(title!.textContent).toBe(t(lang, "retake.tips"));
      cleanup();
    }
  });

  it("closedUntilOpenAndClosedAgainWhenOpenTurnsFalse", () => {
    const { container, rerender } = render(
      <Sheet titleKey="retake.tips" open={false} lang="bn">
        <p>{t("bn", "retake.tip.1")}</p>
      </Sheet>,
    );
    const dialog = dialogOf(container);
    expect(dialog.open).toBe(false);
    rerender(
      <Sheet titleKey="retake.tips" open lang="bn">
        <p>{t("bn", "retake.tip.1")}</p>
      </Sheet>,
    );
    expect(dialog.open).toBe(true);
    rerender(
      <Sheet titleKey="retake.tips" open={false} lang="bn">
        <p>{t("bn", "retake.tip.1")}</p>
      </Sheet>,
    );
    expect(dialog.open).toBe(false);
  });

  it("theSplitIsAServerHalfAndAClientHalf", () => {
    // SheetDialog is the "use client" boundary; Sheet is a server component. What the browser receives is judged on
    // the build (built/client-copy.built.test.ts) and eslint refuses @/copy and Sheet.tsx in a "use client" file.
    expect(readFileSync(join(__dirname, "SheetDialog.tsx"), "utf8").startsWith('"use client";')).toBe(true);
    expect(readFileSync(join(__dirname, "Sheet.tsx"), "utf8")).not.toMatch(/^\s*["']use client["']/);
  });

  it("sheetTakesNoFunctionProp", () => {
    // Held by `pnpm typecheck`: a function prop on the server half could only be passed by a client caller, and a
    // client caller importing Sheet.tsx ships the copy. FunctionProps<typeof Sheet> must be empty.
    const none: [FunctionProps<typeof Sheet>] extends [never] ? true : false = true;
    expect(none).toBe(true);
    // Guard: the same type does find a function prop where there is one, so it is not empty by construction.
    const some: [FunctionProps<typeof SheetDialog>] extends [never] ? false : true = true;
    expect(some).toBe(true);
  });

  it("noInlineStyle", () => {
    const { container } = render(
      <Sheet titleKey="retake.tips" open lang="bn">
        <p>{t("bn", "retake.tip.1")}</p>
      </Sheet>,
    );
    expect(container.querySelectorAll("[style]").length).toBe(0);
  });
});
