import { t, type Lang } from "@/copy";
import type { PaperResultJson } from "@/api/types";
import type { DeleteWords } from "@/components/DeletePaper/DeletePaper";
import type { ZoomWords } from "@/components/PageImage/PageImage";
import type { ReadingWords } from "@/components/ReadingProgress/ReadingProgress";
import { href } from "@/routing/paths";
import { MedicineRow, PaperQuote } from "./PaperParts";
import { sectionText } from "./paper-text";

/*
 * What every paper page draws alike (plan W6): the paper's address, the doctor's lines, the medicines, and the page for
 * a paper that is gone. Kept apart from PaperSummary so that /all and the value sheet import none of the summary's own
 * parts (NowBlock, MessageBubble, ReadingProgress): a page's CSS and client chunks are what its imports reach, and the
 * DR-42 route ceiling counts every byte of them on /all.
 */

/** The island words a paper page resolves on the server (src/app/app/(signed-in)/documents/[id]/paper-words.ts). */
export type PaperWords = { delete: DeleteWords; page: (n: number, label?: string) => ZoomWords; reading: ReadingWords };

/** The paper's own address, inside the preview on a preview page. */
export function paperHref(id: string, preview: boolean, rest = ""): string {
  return href(`/app/documents/${id}${rest}`, preview);
}

/** How many of a kind were read and not stored (DR-28: the stored V6 counts). */
export function unverified(paper: PaperResultJson, kind: "values" | "medicines" | "follow_up"): number {
  return paper.unverified?.[kind] ?? 0;
}

/** The doctor's lines (every section Java sent, each quoted beside its crop), as DocumentPages.contextQuotes draws them. */
export function DoctorLines({ paper, ctx }: { paper: PaperResultJson; ctx: { lang: Lang } }) {
  return Object.entries(paper.doctor).map(([section, lines]) => (
    <div key={section} className="paper__section">
      <h3 className="paper__subheading">{sectionText(ctx.lang, section)}</h3>
      {lines.map((l, i) => (
        <PaperQuote key={i} text={l.text} crop={l.crop} documentId={paper.id} lang={ctx.lang} />
      ))}
    </div>
  ));
}

/** The medicines section (DocumentPages.medicinesSection): the pharmacy notice once, then each medicine crop first. */
export function Medicines({ paper, ctx }: { paper: PaperResultJson; ctx: { lang: Lang } }) {
  const missing = unverified(paper, "medicines");
  if (paper.medicines.length === 0 && missing === 0) return null;
  return (
    <section id="medicines" className="paper__group">
      <h2 className="paper__heading">{t(ctx.lang, "med.title")}</h2>
      {paper.medicines.length > 0 && <p className="paper__note">{t(ctx.lang, "med.pharmacy")}</p>}
      {missing > 0 && <p className="paper__note">{t(ctx.lang, "med.unverified", missing)}</p>}
      {paper.medicines.map((m, i) => (
        <MedicineRow key={i} medicine={m} documentId={paper.id} lang={ctx.lang} />
      ))}
    </section>
  );
}

/** A paper that is no longer here (deleted, or never this family's): `doc.gone`, and the way back to the papers. */
export function PaperGone({ lang, preview, deleted }: { lang: Lang; preview: boolean; deleted: boolean }) {
  return (
    <article className="paper" data-gone>
      <section className="paper__card">
        <h1 className="paper__title">{t(lang, "doc.gone")}</h1>
        {deleted && <p className="paper__line">{t(lang, "doc.deleted")}</p>}
        <a className="paper__action" href={href("/app/timeline", preview)}>
          {t(lang, "app.back.papers")}
        </a>
      </section>
    </article>
  );
}
