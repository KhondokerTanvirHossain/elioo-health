import "server-only";
import type { UploadOptionsJson } from "@/api/types";
import { banglaDigits, t, type Lang } from "@/copy";
import { reasonArgs, UPLOAD_REASON_COPY } from "@/components/Upload/upload-reasons";

/*
 * The add sheet's words, resolved on the server (plan W7): UploadSheet and its parts are client islands, which take
 * their words as props and import no copy, so the browser receives only the strings this sheet says, inside the page's
 * RSC payload. The page tray's and the progress's lines keep their named slots ({n}, {total}, {max}) exactly as the
 * bundle writes them, as the HTML sheet hands them to upload.js; the island fills them with `digits`, the page's own
 * digit set, so a count reads in the language around it. Every number a refusal needs comes from the options.
 */

/** One patient the sheet may send for: the chip's words, and the progress title naming them (up.title.for). */
export type UploadPatientWords = { id: string; label: string; sending: string };

export type UploadWords = {
  /** add.title, the sheet's heading. */
  title: string;
  /** add.who, the chips' legend. */
  who: string;
  patients: UploadPatientWords[];
  /** add.onlyone, when the options preselect the only one; null when the family chooses. */
  onlyOne: string | null;
  /** add.choose: two or more, and nobody chosen for them. */
  choose: string;
  /** add.viewonly: the patient the page was opened for may only be seen, or the viewer may send for nobody. */
  viewOnly: string;
  /** add.nopatient: nobody's file is open yet. */
  noPatient: string;
  /** add.cap with the allowance's size and reset day; null for a family that pays. */
  cap: string | null;
  /** add.quota with what is left; null for a family that pays. */
  quota: string | null;
  /** add.cap.separate: separate papers that would take more than is left; null for a family that pays. */
  capSeparate: string | null;
  sources: Record<"camera" | "gallery" | "pdf", { label: string; sub: string }>;
  tips: { title: string; items: string[] };
  send: string;
  group: { question: string; one: string; separate: string; hint: string };
  /** The tray's lines, their slots ({n}, {max}) unfilled. */
  tray: {
    title: string;
    page: string;
    src: Record<"camera" | "gallery" | "pdf", string>;
    up: string;
    down: string;
    remove: string;
    limit: string;
    order: string;
    more: string;
  };
  /** The sending lines (s09), their slots ({n}, {total}) unfilled. */
  up: { title: string; count: string; sent: string; stuck: string; slow: string; retry: string; after: string; keep: string };
  /** upload.drop: the desktop drop target. */
  drop: string;
  /** Every refusal Java names, in words (UPLOAD_REASON_COPY, with the options' numbers). */
  reasons: Record<string, string>;
  /** app.err.generic: an answer the sheet has no words for. */
  generic: string;
  /** The page's digits, 0 to 9, for the slots above. */
  digits: string;
};

export function uploadWords(lang: Lang, options: UploadOptionsJson): UploadWords {
  const a = options.allowance;
  const only = options.can_send.find((p) => p.id === options.preselected_patient_id) ?? null;
  const reasons: Record<string, string> = {};
  for (const [name, key] of Object.entries(UPLOAD_REASON_COPY)) {
    reasons[name] = key === "add.cap" && !a.limited ? t(lang, "app.err.freetier") : t(lang, key, ...reasonArgs(key, options, lang));
  }
  return {
    title: t(lang, "add.title"),
    who: t(lang, "add.who"),
    patients: options.can_send.map((p) => ({
      id: p.id,
      label: p.age === null ? p.name : t(lang, "add.patient", p.name, p.age),
      sending: t(lang, "up.title.for", p.name_possessive),
    })),
    onlyOne: only === null ? null : t(lang, "add.onlyone", only.name_possessive, only.name_objective),
    choose: t(lang, "add.choose"),
    viewOnly: t(lang, "add.viewonly"),
    noPatient: t(lang, "add.nopatient"),
    cap: a.limited ? t(lang, "add.cap", ...reasonArgs("add.cap", options, lang)) : null,
    quota: a.limited && a.remaining !== null ? t(lang, "add.quota", a.remaining) : null,
    capSeparate: a.limited && a.remaining !== null ? t(lang, "add.cap.separate", a.remaining) : null,
    sources: {
      camera: { label: t(lang, "add.camera"), sub: t(lang, "add.camera.sub") },
      gallery: { label: t(lang, "add.gallery"), sub: t(lang, "add.gallery.sub") },
      pdf: { label: t(lang, "add.pdf"), sub: t(lang, "add.pdf.sub") },
    },
    tips: { title: t(lang, "add.tips"), items: [t(lang, "add.tip.flat"), t(lang, "add.tip.light"), t(lang, "add.tip.frame")] },
    send: t(lang, "add.send"),
    group: {
      question: t(lang, "add.group.q"),
      one: t(lang, "add.group.one"),
      separate: t(lang, "add.group.separate"),
      hint: t(lang, "add.group.hint"),
    },
    tray: {
      title: t(lang, "tray.title"),
      page: t(lang, "tray.page"),
      src: { camera: t(lang, "tray.src.camera"), gallery: t(lang, "tray.src.gallery"), pdf: t(lang, "tray.src.pdf") },
      up: t(lang, "tray.up"),
      down: t(lang, "tray.down"),
      remove: t(lang, "tray.remove"),
      limit: t(lang, "tray.limit"),
      order: t(lang, "tray.order"),
      more: t(lang, "tray.more"),
    },
    up: {
      title: t(lang, "up.title"),
      count: t(lang, "up.count"),
      sent: t(lang, "up.sent"),
      stuck: t(lang, "up.stuck"),
      slow: t(lang, "up.slow"),
      retry: t(lang, "up.retry"),
      after: t(lang, "up.after"),
      keep: t(lang, "up.keep"),
    },
    drop: t(lang, "upload.drop"),
    reasons,
    generic: t(lang, "app.err.generic"),
    digits: lang === "bn" ? banglaDigits("0123456789") : "0123456789",
  };
}
