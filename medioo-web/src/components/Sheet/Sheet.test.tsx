import { readFileSync } from "node:fs";
import { join } from "node:path";
import { cleanup, fireEvent, render } from "@testing-library/react";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { t } from "@/copy";
import { Sheet } from "./Sheet";

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

const dialogOf = (container: HTMLElement) => {
  const d = container.querySelector("dialog");
  if (!d) throw new Error("Sheet renders no <dialog>");
  return d;
};

describe("Sheet", () => {
  it("escapeCloses", () => {
    const onClose = vi.fn();
    const { container, getByText } = render(
      <Sheet titleKey="retake.tips" open lang="bn" onClose={onClose}>
        <p>{t("bn", "retake.tip.1")}</p>
      </Sheet>,
    );
    const dialog = dialogOf(container);
    expect(dialog.open, "open after mount").toBe(true);
    fireEvent.keyDown(getByText(t("bn", "retake.tip.1")), { key: "Escape" });
    expect(dialog.open, "closed by Escape").toBe(false);
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("anotherKeyDoesNotClose", () => {
    const onClose = vi.fn();
    const { container } = render(
      <Sheet titleKey="retake.tips" open lang="bn" onClose={onClose}>
        <p>{t("bn", "retake.tip.1")}</p>
      </Sheet>,
    );
    const dialog = dialogOf(container);
    fireEvent.keyDown(dialog, { key: "Enter" });
    expect(dialog.open).toBe(true);
    expect(onClose).not.toHaveBeenCalled();
  });

  it("theCloseButtonCloses", () => {
    const onClose = vi.fn();
    const { container, getByRole } = render(
      <Sheet titleKey="retake.tips" open lang="en" onClose={onClose}>
        <p>{t("en", "retake.tip.1")}</p>
      </Sheet>,
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

  it("theClientHalfShipsNoCopy", () => {
    // The browser gets SheetDialog (its "use client" boundary), never the copy: Sheet resolves the words on the server.
    const source = readFileSync(join(__dirname, "SheetDialog.tsx"), "utf8");
    expect(source.startsWith('"use client";')).toBe(true);
    expect(source).not.toMatch(/from\s+["']@\/copy|copy\/generated/);
    expect(readFileSync(join(__dirname, "Sheet.tsx"), "utf8")).not.toMatch(/["']use client["']/);
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
