import "server-only";
import { t, type Lang } from "@/copy";
import type { DeleteWords } from "@/components/DeletePaper/DeletePaper";
import type { ZoomWords } from "@/components/PageImage/PageImage";
import type { ReadingWords } from "@/components/ReadingProgress/ReadingProgress";
import type { PaperWords } from "@/components/Paper/PaperShared";

/*
 * The paper's island words, resolved on the server (plan W6): the delete and the page zoom are client islands, which
 * take their words as props and import no copy, so the browser receives only these strings in the page's RSC payload.
 * A crop's words are per row (its own name as alternative text) and are resolved beside the row (Paper/PaperParts).
 */

export function deleteWords(lang: Lang): DeleteWords {
  return {
    open: t(lang, "detail.delete"),
    question: t(lang, "doc.delete.q"),
    warn: t(lang, "doc.delete.warn"),
    yes: t(lang, "doc.delete.yes"),
    no: t(lang, "doc.delete.no"),
    gone: t(lang, "doc.gone"),
    refused: t(lang, "app.err.forbidden"),
    failed: t(lang, "app.err.generic"),
  };
}

/** One page's link and sheet: "Page n" as its title, and `label` (a NOW block's or a retake's own line) as the link. */
export function zoomWords(lang: Lang, n: number, label?: string): ZoomWords {
  return { zoom: label ?? t(lang, "detail.page", n), title: t(lang, "detail.page", n), closeLabel: t(lang, "sheet.close") };
}

/** The reading check's words (PO ruling P6): each settled status's, as the papers page's QuietCheck says them, and its link's. */
export function readingWords(lang: Lang): ReadingWords {
  return {
    settled: { DONE: t(lang, "status.DONE"), NEEDS_RETAKE: t(lang, "status.NEEDS_RETAKE"), FAILED: t(lang, "status.FAILED") },
    again: t(lang, "attn.reading.action"),
  };
}

export function paperWords(lang: Lang): PaperWords {
  return { delete: deleteWords(lang), page: (n, label) => zoomWords(lang, n, label), reading: readingWords(lang) };
}
