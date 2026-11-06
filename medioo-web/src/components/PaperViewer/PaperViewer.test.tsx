import { act, cleanup, fireEvent, render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { PaperJson, PaperReadingJson, PaperResultJson, ThreadJson } from "@/api/types";
import { banglaDigits, formatDayMonth, t, type Lang } from "@/copy";
import { UrgencyTag } from "@/components/safety/UrgencyTag/UrgencyTag";
import { href } from "@/routing/paths";
import multipage from "../../../fixtures/api/documents/fifty-eight-values-multipage.json";
import processing from "../../../fixtures/api/documents/processing.json";
import protoAbbu from "../../../fixtures/api/thread/proto-abbu.json";
import protoAmma from "../../../fixtures/api/thread/proto-amma.json";
import { PaperViewer } from "./PaperViewer";

/*
 * The paper viewer (plan desktop-parity Task 6; desktop.html `chatView()`'s `.viewer`): the toolbar (prev / next, the
 * paper's type and date, whose paper and the lab, the stored urgency, zoom, delete), the page canvas, and the strip of
 * the patient's papers. Over the prototype's own family as Java wrote it (fixtures/api/thread/proto-*.json: each paper
 * item's `card` is the paper's PaperJson, newest first). No Bangla is typed here (the bangla-literal lint scans tests):
 * every expected word is the bundle's.
 */

// The reading check (ReadingProgress) asks Java and refreshes the page; neither may leave this file.
const apiGet = vi.fn();
vi.mock("@/api/browser", () => ({ apiGet: (...args: unknown[]) => apiGet(...args), apiSend: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: vi.fn(), push: vi.fn() }) }));

const cardsOf = (thread: { body: unknown }) =>
  (thread.body as ThreadJson).items.flatMap((item) => (item.kind === "paper" ? [item.card] : []));
/** Amma's five papers, newest first, as the thread sends them: blur, cbc (both 20 Oct), rx, creat, lipid. */
const AMMA = cardsOf(protoAmma);
/** Abbu's two: potas (NOW), sugar. */
const ABBU = cardsOf(protoAbbu);
const result = (papers: PaperJson[], type: string | null, date: string): PaperResultJson => {
  const found = papers.find((p): p is PaperResultJson => "date" in p && p.date === date && p.document_type === type);
  if (!found) throw new Error(`fixture: no ${type} paper on ${date}`);
  return found;
};
const CBC = result(AMMA, "lab_report", "2026-10-20");
const BLUR = AMMA[0]!;
const RX = result(AMMA, "prescription", "2026-10-15");
const CREAT = result(AMMA, "lab_report", "2026-10-14");
const LIPID = result(AMMA, "lab_report", "2026-10-13");
const POTAS = result(ABBU, "lab_report", "2026-10-16");
const READING = processing.body as PaperReadingJson;

const hrefFor = (id: string) => `/app/home?paper=${id}`;

beforeEach(() => {
  apiGet.mockReset().mockResolvedValue({ kind: "ok", status: 200, body: { status: "PROCESSING" } });
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function viewer(extra: Partial<Parameters<typeof PaperViewer>[0]> = {}) {
  return render(<PaperViewer lang="bn" preview={false} paper={CBC} papers={AMMA} selectedId={CBC.id} hrefFor={hrefFor} {...extra} />);
}

const q = (root: ParentNode, selector: string) => root.querySelector<HTMLElement>(selector);
const step = (root: ParentNode, which: "prev" | "next") => q(root, `[data-step="${which}"]`)!;
const zoomLabel = (root: ParentNode) => q(root, ".paper-viewer__zoom-level")!.textContent;
const canvas = (root: ParentNode) => q(root, ".paper-viewer__canvas")!;
const press = (root: ParentNode, label: string) => fireEvent.click(q(root, `button[aria-label="${label}"]`)!);
/** "120%" in the page's digits. */
const percent = (lang: Lang, n: number) => (lang === "bn" ? banglaDigits(`${n}%`) : `${n}%`);

describe("PaperViewer", () => {
  it("toolbarShowsTitleDateLabAndTheUrgencyWord", () => {
    for (const lang of ["bn", "en"] as const) {
      const { container } = viewer({ lang });
      // The type Java read (type.lab_report) and the paper's day; whose paper, then the lab as printed.
      expect(q(container, ".paper-viewer__title")!.textContent).toBe(`${t(lang, "type.lab_report")} · ${formatDayMonth(lang, "2026-10-20")}`);
      expect(q(container, ".paper-viewer__sub")!.textContent).toBe(`${t(lang, "desk.viewer.sub", CBC.patient.name_possessive)} · Shapla Diagnostics`);
      // A routine paper says no urgency word (D27: ROUTINE gets no chip, no status word, never "inside the range").
      expect(q(container, ".paper-viewer__toolbar [data-urgency]"), "routine: no word").toBeNull();
      cleanup();
    }
    // A stored THIS_WEEK and NOW say the word UrgencyTag says for them, shape and word, never the colour alone.
    for (const [paper, papers, level] of [
      [CREAT, AMMA, "this_week"],
      [POTAS, ABBU, "now"],
    ] as const) {
      const { container } = viewer({ paper, papers, selectedId: paper.id });
      const tag = q(container, ".paper-viewer__toolbar [data-urgency]");
      expect(tag?.getAttribute("data-urgency"), `${level}: the tag`).toBe(level);
      const own = render(<UrgencyTag level={level} lang="bn" />).container.textContent;
      expect(tag!.textContent).toBe(own);
      expect(tag!.querySelector("svg"), `${level}: a shape`).not.toBeNull();
      cleanup();
    }
    // No type read: the default word, and no facility: whose paper alone.
    const { container } = viewer({ paper: BLUR, selectedId: BLUR.id });
    expect(q(container, ".paper-viewer__title")!.textContent).toBe(
      `${t("bn", "type.default")} · ${t("bn", "trend.date.upload", formatDayMonth("bn", "2026-10-20"))}`,
    );
    expect(q(container, ".paper-viewer__sub")!.textContent).toBe(t("bn", "desk.viewer.sub", BLUR.patient.name_possessive));
  });

  it("theTitleSaysWhenItsDayIsTheUploadDayOrUnsure", () => {
    // F7: a paper with no printed date is filed under its upload day and says so, never passing it off as printed.
    expect((BLUR as PaperResultJson).date_is_upload_day, "fixture: blur has no printed date").toBe(true);
    expect((BLUR as PaperResultJson).printed_date).toBeNull();
    expect(CBC.date_is_upload_day || CBC.date_uncertain, "fixture: cbc's date is printed and sure").toBe(false);
    for (const lang of ["bn", "en"] as const) {
      const day = formatDayMonth(lang, "2026-10-20");
      const upload = t(lang, "trend.date.upload", day);
      const unsure = t(lang, "trend.date.uncertain", day);
      const titleOf = (paper: PaperJson) => {
        const { container } = viewer({ lang, paper, selectedId: paper.id });
        const title = q(container, ".paper-viewer__title")!.textContent;
        expect(canvas(container).getAttribute("aria-label"), "the canvas is named as the title").toBe(title);
        cleanup();
        return title;
      };
      // The upload day, marked.
      expect(titleOf(BLUR), `${lang}: blur's title`).toBe(`${t(lang, "type.default")} · ${upload}`);
      // A printed date the model was unsure of, marked as unsure.
      expect(titleOf({ ...CBC, date_uncertain: true }), `${lang}: an unsure date`).toBe(`${t(lang, "type.lab_report")} · ${unsure}`);
      // A printed, sure date: the bare day, no marker either way.
      const cbc = titleOf(CBC);
      expect(cbc).toBe(`${t(lang, "type.lab_report")} · ${day}`);
      expect(cbc).not.toContain(t(lang, "timeline.uploaddate"));
    }
  });

  it("prevIsDisabledOnTheOldestAndNextOnTheNewest", () => {
    const enabled = (el: HTMLElement) => el.getAttribute("aria-disabled") !== "true" && el.hasAttribute("href");
    // The oldest (lipid): nothing before it; next is the one after it in time (creat).
    let root = viewer({ paper: LIPID, selectedId: LIPID.id }).container;
    expect(enabled(step(root, "prev")), "oldest: prev").toBe(false);
    expect(step(root, "prev").getAttribute("aria-disabled")).toBe("true");
    expect(step(root, "next").getAttribute("href")).toBe(hrefFor(CREAT.id));
    cleanup();
    // The newest (blur): nothing after it; prev is cbc.
    root = viewer({ paper: BLUR, selectedId: BLUR.id }).container;
    expect(enabled(step(root, "next")), "newest: next").toBe(false);
    expect(step(root, "next").getAttribute("aria-disabled")).toBe("true");
    expect(step(root, "prev").getAttribute("href")).toBe(hrefFor(CBC.id));
    cleanup();
    // In between (rx): both ways.
    root = viewer({ paper: RX, selectedId: RX.id }).container;
    expect(step(root, "prev").getAttribute("href")).toBe(hrefFor(CREAT.id));
    expect(step(root, "next").getAttribute("href")).toBe(hrefFor(CBC.id));
    // Each says what it does.
    expect(step(root, "prev").getAttribute("aria-label")).toBe(t("bn", "desk.viewer.prev"));
    expect(step(root, "next").getAttribute("aria-label")).toBe(t("bn", "desk.viewer.next"));
  });

  it("theStripListsEveryPaperWithItsDateAndUrgencyDot", () => {
    const { container } = viewer();
    const strip = q(container, ".paper-strip")!;
    expect(strip.getAttribute("aria-label")).toBe(t("bn", "desk.viewer.strip", CBC.patient.name_possessive));
    const items = [...strip.querySelectorAll<HTMLAnchorElement>("a.paper-strip__item")];
    expect(items.length, "every paper of Amma's").toBe(5);
    expect(items.map((a) => a.getAttribute("href"))).toEqual(AMMA.map((p) => hrefFor(p.id)));
    // The date under each, read off its own element: blur's is its upload day and says so (F7); the rest are printed.
    const dayOf = (p: PaperResultJson) =>
      p.date_is_upload_day ? t("bn", "trend.date.upload", formatDayMonth("bn", p.date)) : formatDayMonth("bn", p.date);
    expect(items.map((a) => q(a, ".paper-strip__date")!.textContent)).toEqual(AMMA.map((p) => dayOf(p as PaperResultJson)));
    expect(q(items[0]!, ".paper-strip__date")!.textContent, "blur's upload day, marked").toBe(
      t("bn", "trend.date.upload", formatDayMonth("bn", "2026-10-20")),
    );
    expect(items[0]!.getAttribute("title"), "blur's title names its upload day").toBe(
      `${t("bn", "type.default")} · ${t("bn", "trend.date.upload", formatDayMonth("bn", "2026-10-20"))}`,
    );
    expect(q(items[1]!, ".paper-strip__date")!.textContent, "cbc's printed day, bare").toBe(formatDayMonth("bn", "2026-10-20"));
    // The stored urgency: THIS_WEEK on creat alone (routine and a retake say nothing, D27).
    expect(items.map((a) => q(a, "[data-urgency]")?.getAttribute("data-urgency") ?? null)).toEqual(
      AMMA.map((p) => (p.id === CREAT.id ? "this_week" : null)),
    );
    // The selected one, and only it, is current.
    const current = strip.querySelectorAll("[aria-current]");
    expect(current.length).toBe(1);
    expect(current[0]!.getAttribute("aria-current")).toBe("true");
    expect(current[0]!.getAttribute("href")).toBe(hrefFor(CBC.id));
    cleanup();
    // Abbu's: two, NOW on potas.
    const abbu = viewer({ paper: POTAS, papers: ABBU, selectedId: POTAS.id }).container;
    const dots = [...abbu.querySelectorAll("a.paper-strip__item")].map((a) => q(a, "[data-urgency]")?.getAttribute("data-urgency") ?? null);
    expect(dots).toEqual(ABBU.map((p) => (p.id === POTAS.id ? "now" : null)));
  });

  it("aShareMemberHasNoDeleteButton", () => {
    // The owner: the delete is there, in the desktop's words.
    const owner = viewer().container;
    const button = q(owner, ".paper-viewer__toolbar .delete-paper__open");
    expect(button, "the owner's delete").not.toBeNull();
    expect(button!.textContent).toBe(t("bn", "desk.viewer.delete"));
    cleanup();
    // A share member (owner: false): no delete at all, while the paper and the strip stay.
    const member = viewer({ paper: { ...CBC, owner: false } }).container;
    expect(q(member, ".delete-paper__open"), "a share member's delete button").toBeNull();
    expect(member.textContent).not.toContain(t("bn", "desk.viewer.delete"));
    expect(q(member, ".paper-viewer__canvas img"), "the paper is still shown").not.toBeNull();
    expect(member.querySelectorAll("a.paper-strip__item").length).toBe(5);
  });

  it("aReadingPaperShowsItsPagesAndTheReadingLine", () => {
    const { container } = viewer({ paper: READING, papers: [READING, ...AMMA], selectedId: READING.id });
    const page = canvas(container);
    // Its pages, as Java sent them, never an empty canvas.
    expect([...page.querySelectorAll("img")].map((i) => i.getAttribute("src")), "the reading paper's pages").toEqual(
      READING.pages.map((p) => p.src),
    );
    // The reading line, and the quiet check that moves it on (ReadingProgress, PO ruling P6).
    expect(q(container, ".paper-viewer__reading")?.textContent, "the reading line").toBe(t("bn", "progress.title"));
    expect(q(container, ".paper__quiet [role='status']"), "ReadingProgress").not.toBeNull();
    // Its strip item says it is being read, with no date it does not have.
    expect(q(container, `a.paper-strip__item[href="${hrefFor(READING.id)}"] .paper-strip__date`)!.textContent).toBe(
      t("bn", "status.PROCESSING"),
    );
    cleanup();
    // No page stored yet: the reading line still fills the canvas.
    const bare = viewer({ paper: { ...READING, pages: [], page_count: 0 }, papers: [READING], selectedId: READING.id }).container;
    expect(q(bare, ".paper-viewer__canvas .paper-viewer__reading")?.textContent, "a reading paper with no page").toBe(
      t("bn", "progress.title"),
    );
  });

  it("aFinishedPaperHasNoReadingCheck", () => {
    const { container } = viewer();
    expect(q(container, ".paper__quiet"), "no quiet check on a finished paper").toBeNull();
    expect(q(container, ".paper-viewer__reading")).toBeNull();
  });

  it("noPaperShowsTheFirstVisitDropZone", () => {
    for (const preview of [false, true]) {
      const { container } = viewer({ paper: null, papers: [], selectedId: null, preview });
      const zone = q(container, ".paper-viewer__first")!;
      expect(zone, "the drop zone").not.toBeNull();
      expect(q(zone, "h2")!.textContent).toBe(t("bn", "desk.first.title"));
      expect(q(zone, "p")!.textContent).toBe(t("bn", "desk.first.body"));
      const pick = q(zone, "a.paper-viewer__pick")!;
      expect(pick.textContent).toBe(t("bn", "desk.first.pick"));
      expect(pick.getAttribute("href")).toBe(href("/app/add", preview));
      // No paper: no toolbar, no zoom, no strip.
      expect(q(container, ".paper-viewer__toolbar")).toBeNull();
      expect(q(container, ".paper-strip")).toBeNull();
      cleanup();
    }
    expect(href("/app/add", false)).toBe("/app/add");
  });

  it("zoomStepsAndClamps", () => {
    for (const lang of ["bn", "en"] as const) {
      const { container } = viewer({ lang });
      const [zin, zout, fit] = (["desk.viewer.zin", "desk.viewer.zout", "desk.viewer.fit"] as const).map((k) => t(lang, k));
      // It opens fitted to the canvas.
      expect(canvas(container).getAttribute("data-zoom")).toBe("fit");
      // From an unmeasured fit (no layout here) a step starts from 100%.
      press(container, zout!);
      expect(zoomLabel(container)).toBe(percent(lang, 75));
      press(container, zin!);
      expect(zoomLabel(container)).toBe(percent(lang, 100));
      expect(canvas(container).getAttribute("data-zoom")).toBe("1");
      for (let i = 0; i < 12; i++) press(container, zin!);
      expect(zoomLabel(container), "+ at 300% stays").toBe(percent(lang, 300));
      expect(canvas(container).getAttribute("data-zoom")).toBe("3");
      for (let i = 0; i < 14; i++) press(container, zout!);
      expect(zoomLabel(container), "- at 50% stays").toBe(percent(lang, 50));
      expect(canvas(container).getAttribute("data-zoom")).toBe("0.5");
      press(container, fit!);
      expect(canvas(container).getAttribute("data-zoom")).toBe("fit");
      cleanup();
    }
  });

  it("zoomKeysAndArrowsWorkButNotWhileTyping", () => {
    const followed: string[] = [];
    const follow = (e: MouseEvent) => {
      const a = (e.target as Element).closest("a");
      if (a) {
        followed.push(a.getAttribute("href")!);
        e.preventDefault();
      }
    };
    document.addEventListener("click", follow, true);
    try {
      const { container } = viewer({ paper: RX, selectedId: RX.id });
      const key = (k: string, target: Element = document.body) => act(() => void fireEvent.keyDown(target, { key: k }));
      key("+");
      expect(canvas(container).getAttribute("data-zoom")).toBe("1.25");
      key("-");
      key("-");
      expect(canvas(container).getAttribute("data-zoom")).toBe("0.75");
      key("0");
      expect(canvas(container).getAttribute("data-zoom")).toBe("fit");
      // ← the paper before, → the paper after (desktop.html's keys).
      key("ArrowLeft");
      key("ArrowRight");
      expect(followed).toEqual([hrefFor(CREAT.id), hrefFor(CBC.id)]);
      // Typing in a field (the composer) is never a zoom or a page turn.
      const field = document.createElement("input");
      document.body.append(field);
      key("-", field);
      key("ArrowLeft", field);
      expect(canvas(container).getAttribute("data-zoom"), "typing - in a field zoomed the paper").toBe("fit");
      expect(followed.length, "an arrow in a field turned the paper").toBe(2);
      field.remove();
      // A modifier is the browser's (Ctrl - zooms the window), never the viewer's.
      act(() => void fireEvent.keyDown(document.body, { key: "-", ctrlKey: true }));
      expect(canvas(container).getAttribute("data-zoom"), "Ctrl - zoomed the paper").toBe("fit");
    } finally {
      document.removeEventListener("click", follow, true);
    }
  });

  it("noKeyWorksWhileADialogIsOpenOrOnThePhoneWidth", () => {
    const followed: string[] = [];
    const follow = (e: MouseEvent) => {
      const a = (e.target as Element).closest("a");
      if (a) {
        followed.push(a.getAttribute("href")!);
        e.preventDefault();
      }
    };
    document.addEventListener("click", follow, true);
    const key = (k: string) => act(() => void fireEvent.keyDown(document.body, { key: k }));
    try {
      const { container } = viewer({ paper: RX, selectedId: RX.id });
      // An open dialog (DeletePaper's sheet, the upload dialog) owns the keys: no zoom, no page turn.
      const dialog = document.createElement("dialog");
      dialog.setAttribute("open", "");
      document.body.append(dialog);
      key("-");
      key("ArrowLeft");
      expect(canvas(container).getAttribute("data-zoom"), "- zoomed the paper under an open dialog").toBe("fit");
      expect(followed, "← turned the paper under an open dialog").toEqual([]);
      dialog.remove();
      // Below 900 px the phone page is showing: the desktop keys are off.
      vi.stubGlobal("matchMedia", (query: string) => ({ matches: query === "(max-width: 899px)", media: query }));
      key("+");
      key("ArrowRight");
      expect(canvas(container).getAttribute("data-zoom"), "+ zoomed the paper on the phone width").toBe("fit");
      expect(followed, "→ turned the paper on the phone width").toEqual([]);
      // The same keys on the desktop width still work (the guards above are not why nothing happened).
      vi.stubGlobal("matchMedia", (query: string) => ({ matches: false, media: query }));
      key("+");
      key("ArrowRight");
      expect(canvas(container).getAttribute("data-zoom")).toBe("1.25");
      expect(followed).toEqual([hrefFor(CBC.id)]);
    } finally {
      document.removeEventListener("click", follow, true);
    }
  });

  it("pageOneLoadsAtOnceTheRestLazily", () => {
    const paper = multipage.body as PaperResultJson;
    expect(paper.pages.length, "the fixture has more than one page").toBeGreaterThan(1);
    const { container } = viewer({ paper, papers: [paper, ...AMMA], selectedId: paper.id });
    const pages = [...canvas(container).querySelectorAll("img")];
    expect(pages.map((i) => i.getAttribute("src"))).toEqual(paper.pages.map((p) => p.src));
    expect(pages.map((i) => i.getAttribute("loading"))).toEqual(paper.pages.map((p) => (p.n === 1 ? null : "lazy")));
    expect(pages.map((i) => i.getAttribute("alt"))).toEqual(paper.pages.map((p) => t("bn", "detail.page", p.n)));
    // Every strip thumbnail waits until it is scrolled to.
    const thumbs = [...container.querySelectorAll(".paper-strip img")];
    expect(thumbs.length).toBe(6);
    expect(thumbs.every((i) => i.getAttribute("loading") === "lazy")).toBe(true);
  });
});
