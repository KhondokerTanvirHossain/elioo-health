import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { cleanup, fireEvent, render } from "@testing-library/react";
import postcss, { type AtRule, type Rule } from "postcss";
import { afterEach, describe, expect, it } from "vitest";
import { t } from "@/copy";
import { DesktopShell } from "./DesktopShell";

/*
 * The desktop shell (plan desktop-parity Task 5; desktop.html `.app`): the sidebar, then the content region holding the
 * NOW bar slot across the two content columns (the paper, the conversation) and the details panel over the
 * conversation. From 900 to 1199 px the sidebar hides behind the toolbar button (SidebarToggle), as AppShell's does;
 * from 1200 px it is always there. Widths are a browser's to measure (Task 9's Playwright); this holds the order, the
 * slots, the wiring, and what the stylesheet says at each width.
 */

afterEach(cleanup);

function shell(extra: Partial<Parameters<typeof DesktopShell>[0]> = {}) {
  return render(
    <DesktopShell
      lang="bn"
      preview={false}
      sidebar={<p data-slot="sidebar">sidebar</p>}
      bar={<p data-slot="bar">bar</p>}
      paper={<p data-slot="paper">paper</p>}
      talk={<p data-slot="talk">talk</p>}
      panel={<p data-slot="panel">panel</p>}
      {...extra}
    />,
  );
}

const css = readFileSync(join(dirname(fileURLToPath(import.meta.url)), "DesktopShell.css"), "utf8");

/** The declarations the stylesheet gives `selector` for `prop`, keyed by the media query they sit under ("" for none). */
function declared(selector: string, prop: string): Record<string, string> {
  const out: Record<string, string> = {};
  postcss.parse(css).walkRules((rule: Rule) => {
    if (!rule.selectors.map((s) => s.trim()).includes(selector)) return;
    const media = rule.parent?.type === "atrule" ? (rule.parent as AtRule).params : "";
    rule.walkDecls(prop, (d) => {
      out[media] = d.value;
    });
  });
  return out;
}

describe("DesktopShell", () => {
  it("theSlotsAreInOrder", () => {
    const { container } = shell();
    const slots = [...container.querySelectorAll("[data-slot]")].map((e) => e.getAttribute("data-slot"));
    expect(slots).toEqual(["sidebar", "bar", "paper", "talk", "panel"]);
    expect(container.querySelector("main [data-slot=paper]"), "the paper is in the document's main").not.toBeNull();
    expect(container.querySelector("main [data-slot=talk]"), "the conversation is in the document's main").not.toBeNull();
  });

  it("noNowBarOverTheSidebar", () => {
    // P19: one slim bar across the content columns, never over the sidebar.
    const { container } = shell();
    const bar = container.querySelector("[data-slot=bar]")!;
    const sidebar = container.querySelector(".desktop-shell__sidebar")!;
    const content = container.querySelector(".desktop-shell__content")!;
    expect(sidebar.contains(bar), "not inside the sidebar").toBe(false);
    expect(content.contains(bar), "inside the content region").toBe(true);
    expect(sidebar.compareDocumentPosition(bar) & Node.DOCUMENT_POSITION_FOLLOWING, "after the sidebar, never before").toBeTruthy();
    // The bar spans both content columns: it sits above them, outside either.
    for (const column of [".desktop-shell__paper", ".desktop-shell__talk"]) {
      expect(container.querySelector(column)!.contains(bar), column).toBe(false);
    }
    expect(content.contains(container.querySelector(".desktop-shell__paper"))).toBe(true);
    expect(content.contains(container.querySelector(".desktop-shell__talk"))).toBe(true);
    cleanup();
    // No bar given, no slot left empty.
    expect(shell({ bar: undefined }).container.querySelector(".desktop-shell__bar")).toBeNull();
  });

  it("thePanelLiesOverTheConversation", () => {
    const { container } = shell();
    const talk = container.querySelector(".desktop-shell__talk")!;
    expect(talk.contains(container.querySelector("[data-slot=panel]")), "the panel is in the conversation's column").toBe(true);
    expect(declared(".desktop-shell__panel", "position")[""]).toBe("absolute");
    expect(declared(".desktop-shell__talk", "position")[""]).toBe("relative");
    cleanup();
    expect(shell({ panel: undefined }).container.querySelector(".desktop-shell__panel")).toBeNull();
  });

  it("belowNineHundredTheSidebarIsBehindTheToolbarButton", () => {
    // From 900 to 1199 px the sidebar is behind the toolbar's button (SidebarToggle, reused), as AppShell's is today.
    const { container, getByRole } = shell();
    const button = getByRole("button", { name: t("bn", "tabs.label") });
    const sidebar = container.querySelector(`#${button.getAttribute("aria-controls")}`);
    expect(sidebar, "aria-controls names the desktop sidebar").not.toBeNull();
    expect(sidebar!.classList.contains("desktop-shell__sidebar")).toBe(true);
    expect(sidebar!.getAttribute("aria-label")).toBe(t("bn", "tabs.label"));
    expect(button.getAttribute("aria-expanded")).toBe("false");
    fireEvent.click(button);
    expect(sidebar!.hasAttribute("data-open")).toBe(true);
    fireEvent.click(button);
    expect(sidebar!.hasAttribute("data-open")).toBe(false);
    // The button and the shell's widths, as the stylesheet says them: the shell is nothing below 900 px (today's phone
    // shell stands there); the button shows from 900 px and goes at 1200, where the sidebar is a column of its own.
    expect(declared(".desktop-shell", "display")).toEqual({ "": "none", "(min-width: 900px)": "grid" });
    expect(declared(".desktop-shell__menu", "display")).toEqual({
      "": "none",
      "(min-width: 900px)": "inline-flex",
      "(min-width: 1200px)": "none",
    });
    expect(declared(".desktop-shell__sidebar[data-open]", "position")["(min-width: 900px)"]).toBe("fixed");
    expect(declared(".desktop-shell", "grid-template-columns")["(min-width: 1200px)"]).toBe("var(--sidebar-w) minmax(0, 1fr)");
    expect(declared(".desktop-shell__columns", "grid-template-columns")[""]).toBe("minmax(0, 1fr) var(--conversation-w)");
  });

  it("noFooterDisclaimerOnDesktop", () => {
    // X1: the footer disclaimer is removed on desktop; the conversation header says "not a doctor" (Task 7).
    for (const lang of ["bn", "en"] as const) {
      const { container } = shell({ lang });
      expect(container.querySelector("footer.app-shell__foot")).toBeNull();
      expect(container.querySelector("footer")).toBeNull();
      expect(container.textContent).not.toContain(t(lang, "disclaimer"));
      cleanup();
    }
  });
});
