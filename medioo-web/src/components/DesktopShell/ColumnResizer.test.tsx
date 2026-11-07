import { act, cleanup, fireEvent, render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ColumnResizer, PAPER_MIN, STORE_KEY, TALK_MIN, talkWidth } from "./ColumnResizer";

/*
 * The divider between the paper and the conversation (PO 2026-11-07: "the chat window vs paper windows should be
 * resizable"). Dragged, or moved with the keys, it sets the conversation's width as `--talk-w` on the columns, held
 * between TALK_MIN and the columns' width less PAPER_MIN, remembered on this browser, and put back by Enter or a
 * double click. jsdom lays nothing out, so the columns' and the conversation's widths are given here; the browser's
 * own check is e2e/talk-and-papers.spec.ts.
 */

const COLUMNS = 1184; // 1440 px window less the 256 px sidebar
const TALK = 400;

function mount() {
  const view = render(
    <div className="desktop-shell__columns" data-testid="columns">
      <section className="desktop-shell__paper" />
      <ColumnResizer label="resize" />
      <section className="desktop-shell__talk" />
    </div>,
  );
  const columns = view.getByTestId("columns");
  const handle = view.getByRole("separator", { name: "resize" });
  return { ...view, columns, handle, width: () => columns.style.getPropertyValue("--talk-w") };
}

let store: Map<string, string>;
beforeEach(() => {
  store = new Map();
  vi.stubGlobal("localStorage", {
    getItem: (k: string) => store.get(k) ?? null,
    setItem: (k: string, v: string) => void store.set(k, v),
    removeItem: (k: string) => void store.delete(k),
  });
  vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockImplementation(function (this: HTMLElement) {
    const w = this.classList.contains("desktop-shell__columns") ? COLUMNS : this.classList.contains("desktop-shell__talk") ? TALK : 0;
    return { width: w, height: 800, left: 0, top: 0, right: w, bottom: 800, x: 0, y: 0, toJSON: () => ({}) } as DOMRect;
  });
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("ColumnResizer", () => {
  it("talkWidthIsHeldBetweenItsBounds", () => {
    expect(talkWidth(400, COLUMNS), "inside the bounds").toBe(400);
    expect(talkWidth(100, COLUMNS), "never narrower than TALK_MIN").toBe(TALK_MIN);
    expect(talkWidth(2000, COLUMNS), "the paper keeps PAPER_MIN").toBe(COLUMNS - PAPER_MIN);
    // A window too narrow for both minimums: the conversation keeps its minimum, the paper gives way (CSS clamp's rule).
    expect(talkWidth(500, TALK_MIN + 10)).toBe(TALK_MIN);
  });

  it("isAKeyboardSeparatorNamedForWhatItDoes", () => {
    const { handle } = mount();
    expect(handle.getAttribute("aria-orientation")).toBe("vertical");
    expect(handle.tabIndex).toBe(0);
    expect(handle.getAttribute("aria-valuenow")).toBe(String(TALK));
    expect(handle.getAttribute("aria-valuemin")).toBe(String(TALK_MIN));
    expect(handle.getAttribute("aria-valuemax")).toBe(String(COLUMNS - PAPER_MIN));
  });

  it("itHasAValueBeforeAnythingIsMeasured", async () => {
    // A focusable separator must say its value (axe aria-required-attr). Before layout (the server's HTML, a page axe
    // reads before the island measures) it says the design's width, never nothing.
    vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockImplementation(() => ({ width: 0, height: 0 }) as DOMRect);
    const { handle } = mount();
    await act(async () => undefined);
    expect(handle.getAttribute("aria-valuenow"), "a value, unmeasured").toBe("400");
  });

  it("theKeysMoveItAndEnterPutsItBack", () => {
    const { handle, width } = mount();
    // The divider moves the way the arrow points: left widens the conversation, right narrows it.
    fireEvent.keyDown(handle, { key: "ArrowLeft" });
    expect(width()).toBe(`${TALK + 16}px`);
    expect(handle.getAttribute("aria-valuenow")).toBe(String(TALK + 16));
    fireEvent.keyDown(handle, { key: "ArrowRight", shiftKey: true });
    expect(width(), "Shift moves it four steps").toBe(`${TALK + 16 - 64}px`);
    fireEvent.keyDown(handle, { key: "End" });
    expect(width(), "End: the widest the conversation goes").toBe(`${COLUMNS - PAPER_MIN}px`);
    fireEvent.keyDown(handle, { key: "Home" });
    expect(width(), "Home: the narrowest").toBe(`${TALK_MIN}px`);
    expect(store.get(STORE_KEY), "remembered").toBe(String(TALK_MIN));
    fireEvent.keyDown(handle, { key: "Enter" });
    expect(width(), "Enter: the design's width again").toBe("");
    expect(store.has(STORE_KEY), "and forgotten").toBe(false);
  });

  it("draggingItFollowsThePointerAndADoubleClickPutsItBack", () => {
    const { handle, width } = mount();
    fireEvent.pointerDown(handle, { button: 0, clientX: 800, pointerId: 1 });
    fireEvent.pointerMove(handle, { clientX: 700, pointerId: 1 });
    expect(width(), "100 px to the left: the conversation is 100 px wider").toBe(`${TALK + 100}px`);
    fireEvent.pointerMove(handle, { clientX: -2000, pointerId: 1 });
    expect(width(), "held at its widest").toBe(`${COLUMNS - PAPER_MIN}px`);
    fireEvent.pointerUp(handle, { clientX: -2000, pointerId: 1 });
    expect(store.get(STORE_KEY), "remembered when let go").toBe(String(COLUMNS - PAPER_MIN));
    // After letting go, the pointer moving does nothing.
    fireEvent.pointerMove(handle, { clientX: 900, pointerId: 1 });
    expect(width()).toBe(`${COLUMNS - PAPER_MIN}px`);
    fireEvent.doubleClick(handle);
    expect(width()).toBe("");
    expect(store.has(STORE_KEY)).toBe(false);
  });

  it("aRememberedWidthComesBackAndAStoreThatThrowsIsNoMatter", async () => {
    store.set(STORE_KEY, "520");
    const first = mount();
    await act(async () => undefined);
    expect(first.width(), "the width this browser kept").toBe("520px");
    cleanup();
    // Junk in the store: the design's width.
    store.set(STORE_KEY, "wide");
    const junk = mount();
    await act(async () => undefined);
    expect(junk.width()).toBe("");
    cleanup();
    // A private window whose storage throws: the divider still works, it just forgets.
    vi.stubGlobal("localStorage", {
      getItem: () => {
        throw new Error("denied");
      },
      setItem: () => {
        throw new Error("denied");
      },
      removeItem: () => {
        throw new Error("denied");
      },
    });
    const denied = mount();
    await act(async () => undefined);
    fireEvent.keyDown(denied.handle, { key: "ArrowLeft" });
    expect(denied.width()).toBe(`${TALK + 16}px`);
  });
});
