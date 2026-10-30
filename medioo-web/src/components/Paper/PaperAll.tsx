import Link from "next/link";
import { t, type Lang } from "@/copy";
import type { PaperResultJson, PaperValueJson } from "@/api/types";
import { DeletePaper } from "@/components/DeletePaper/DeletePaper";
import { PageImage } from "@/components/PageImage/PageImage";
import { VerdictCard } from "@/components/safety/VerdictCard/VerdictCard";
import { FollowUpQuote, ValueRows } from "./PaperParts";
import { DoctorLines, Medicines, paperHref, type PaperWords } from "./PaperShared";
import { valueGroups } from "./rows";
import { metaLine, patientLine, typeText } from "./paper-text";
import "./Paper.css";

/**
 * Everything on a read paper (plan W6; DocumentPages.detail, s17): back to the result, whose paper it is, the title and
 * meta lines, the band Java sent, anchors to the sections that exist (plain links, no script), every value grouped by
 * the kind Java drew it as (every inside row, none collapsed), every medicine crop first, every line the doctor wrote
 * and the follow-ups beside their crops, and every page whole. Delete only for the owner. Only a DONE paper has this
 * page; the route sends any other to the paper itself.
 */
export function PaperAll({ paper, lang, preview, words }: { paper: PaperResultJson; lang: Lang; preview: boolean; words: PaperWords }) {
  const g = valueGroups(paper.values);
  const missingValues = paper.unverified?.values ?? 0;
  const missingFollow = paper.unverified?.follow_up ?? 0;
  const hasValues = paper.values.length > 0 || missingValues > 0;
  const hasMedicines = paper.medicines.length > 0 || (paper.unverified?.medicines ?? 0) > 0;
  const hasDoctor = Object.keys(paper.doctor).length > 0 || paper.follow_up.length > 0 || missingFollow > 0;
  const segments = [
    ...(hasValues ? [["values", t(lang, "detail.seg.values")]] : []),
    ...(hasMedicines ? [["medicines", t(lang, "detail.seg.medicines")]] : []),
    ...(hasDoctor ? [["doctor", t(lang, "detail.seg.doctor")]] : []),
    ["pages", t(lang, "detail.seg.pages")],
  ] as [string, string][];
  const group = (title: string, rows: PaperValueJson[], why?: string) =>
    rows.length === 0 ? null : (
      <div className="paper__section">
        <h3 className="paper__subheading">{t(lang, "detail.group.count", title, rows.length)}</h3>
        {why !== undefined && <p className="paper__why">{why}</p>}
        <ValueRows values={rows} documentId={paper.id} lang={lang} preview={preview} from="all" />
      </div>
    );
  return (
    <article className="paper paper--all" data-status={paper.status}>
      <header className="paper__head">
        <Link className="paper__back" href={paperHref(paper.id, preview)} prefetch={false}>
          {`‹ ${t(lang, "detail.back")}`}
        </Link>
        <p className="paper__patient">{patientLine(lang, paper.patient)}</p>
        <h1 className="paper__title">{t(lang, "detail.title", typeText(lang, paper.document_type))}</h1>
        <p className="paper__meta">{metaLine(lang, paper.date, paper.date_is_upload_day, paper.facility)}</p>
        <p className="paper__meta">
          {paper.values.length === 0
            ? t(lang, "detail.pagecount", paper.page_count)
            : t(lang, "detail.counts", paper.values.length, paper.page_count)}
        </p>
      </header>
      <VerdictCard paper={paper} lang={lang} wording={false} />
      <nav className="paper__seg" aria-label={t(lang, "detail.seg.label")}>
        {segments.map(([id, label]) => (
          <a key={id} className="paper__seg-link" href={`#${id}`}>
            {label}
          </a>
        ))}
      </nav>
      {hasValues && (
        <section id="values" className="paper__group">
          <h2 className="paper__heading">{t(lang, "detail.seg.values")}</h2>
          {paper.values.length > 0 && <p className="paper__meta">{t(lang, "detail.values.note", paper.values.length)}</p>}
          {missingValues > 0 && <p className="paper__note">{t(lang, "value.unverified")}</p>}
          {group(t(lang, "value.group.outside"), g.outside)}
          {group(t(lang, "value.group.unknown"), g.unknown, t(lang, "value.group.unknown.why"))}
          {group(t(lang, "value.group.inside"), g.inside)}
        </section>
      )}
      <Medicines paper={paper} ctx={{ lang }} />
      {hasDoctor && (
        <section id="doctor" className="paper__group">
          <h2 className="paper__heading">{t(lang, "detail.seg.doctor")}</h2>
          <DoctorLines paper={paper} ctx={{ lang }} />
          {(paper.follow_up.length > 0 || missingFollow > 0) && (
            <div className="paper__section">
              <h3 className="paper__subheading">{t(lang, "next.title")}</h3>
              {paper.follow_up.map((f, i) => (
                <FollowUpQuote key={i} line={f} documentId={paper.id} lang={lang} />
              ))}
              {missingFollow > 0 && <p className="paper__note">{t(lang, "next.unverified", missingFollow)}</p>}
            </div>
          )}
        </section>
      )}
      <section id="pages" className="paper__group">
        <h2 className="paper__heading">{t(lang, "detail.seg.pages")}</h2>
        <p className="paper__meta">{t(lang, paper.document_type === "prescription" ? "detail.pages.rx" : "detail.pages.note")}</p>
        {paper.pages.map((p) => (
          <figure key={p.n} id={`page-${p.n}`} className="paper__page">
            {/* eslint-disable-next-line @next/next/no-img-element -- next/image is banned (Global Constraints) */}
            <img className="paper__page-img" src={p.src} alt={t(lang, "detail.page", p.n)} loading="lazy" decoding="async" data-page-image />
            <figcaption className="paper__page-cap">
              <PageImage src={p.src} words={{ ...words.page(p.n), zoom: t(lang, "crop.zoom") }} />
            </figcaption>
          </figure>
        ))}
      </section>
      {paper.owner && <DeletePaper documentId={paper.id} words={words.delete} />}
    </article>
  );
}
