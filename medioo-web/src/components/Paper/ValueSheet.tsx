import Link from "next/link";
import { formatDate, t, type Lang } from "@/copy";
import type { EarlierReadingJson, ValueSheetJson } from "@/api/types";
import { Crop } from "@/components/Crop/Crop";
import { StatusChip, chipKindOf } from "@/components/StatusChip/StatusChip";
import { ValueBar, cropWords, sheetHref, type From } from "./PaperParts";
import { paperHref } from "./PaperSummary";
import { paperDateText, rangeText, typeText } from "./paper-text";
import "./Paper.css";

/** The heading over the earlier readings, exactly the key Java sent; null draws no line at all (R1). */
function EarlierLine({ sheet, lang }: { sheet: ValueSheetJson; lang: Lang }) {
  switch (sheet.earlier_line_key) {
    case null:
      return null;
    case "sheet.earlier.none":
      return <p className="paper__line" data-earlier-line>{t(lang, "sheet.earlier.none")}</p>;
    case "sheet.earlier":
      return <h2 className="paper__heading" data-earlier-line>{t(lang, "sheet.earlier", sheet.value.unit ?? "")}</h2>;
    case "sheet.earlier.nounit":
      return <h2 className="paper__heading" data-earlier-line>{t(lang, "sheet.earlier.nounit")}</h2>;
  }
}

/** One earlier reading on its own paper, beside its own crop, opening that paper's sheet from its `/all`. */
function Earlier({ e, lang, preview, name }: { e: EarlierReadingJson; lang: Lang; preview: boolean; name: string | null }) {
  return (
    <div className="value-row value-row--earlier" data-value-row>
      <Link className="value-row__head value-row__open" href={sheetHref(e.document_id, e.observation_id, "all", preview)} prefetch={false}>
        <span className="value-row__name">
          {e.date === null ? "" : formatDate(lang, e.date)}
          {e.date_is_upload_day && <span className="paper__meta"> {t(lang, "timeline.uploaddate")}</span>}
        </span>
        <span className="value-row__reading">
          <span className="value-row__value">{e.value}</span>
          {e.unit !== null && <span className="value-row__unit">{e.unit}</span>}
        </span>
      </Link>
      <Crop src={e.crop.src} width={e.crop.width} height={e.crop.height} documentId={e.document_id} words={cropWords(lang, name)} />
    </div>
  );
}

/**
 * One value's sheet (plan W6; DocumentPages.valueSheet, s13) as a page of its own, from GET
 * /app/api/documents/{id}/values/{obs} alone: the value's name, whose paper and when, a close that goes back to the
 * same row (`from`), the crop large, the value as printed with the bar where Java placed it, the printed range and the
 * chip; the "could not match" sentence for an unknown; then the earlier readings, each beside its own crop on its own
 * paper, under the line Java chose (`earlier_line_key`) and never one derived from how many there are.
 */
export function ValueSheet({ sheet, lang, preview, from }: { sheet: ValueSheetJson; lang: Lang; preview: boolean; from: From }) {
  const v = sheet.value;
  const close = paperHref(sheet.paper.id, preview, from === "all" ? "/all" : "") + (v.id === null ? "" : `#v-${v.id}`);
  const when = paperDateText(lang, sheet.paper.date, sheet.paper.date_is_upload_day);
  return (
    <article className="paper paper--sheet" aria-labelledby="value-sheet-title">
      <header className="paper__sheet-head">
        <div>
          <h1 className="paper__title" id="value-sheet-title">
            {v.name}
          </h1>
          <p className="paper__meta">{t(lang, "sheet.of", sheet.paper.patient.name, when, typeText(lang, sheet.paper.document_type))}</p>
        </div>
        <Link className="paper__close" href={close} prefetch={false}>
          {t(lang, "sheet.close")}
        </Link>
      </header>
      <div className="value-row value-row--sheet" data-value-row>
        <Crop
          src={v.crop.src}
          width={v.crop.width}
          height={v.crop.height}
          documentId={sheet.paper.id}
          words={cropWords(lang, v.name)}
          className="crop--big"
        />
        <a className="paper__zoom" href={v.crop.src}>
          {t(lang, "crop.zoom")}
        </a>
        <p className="value-row__reading value-row__reading--big">
          <span className="value-row__value">{v.value}</span>
          {v.unit !== null && <span className="value-row__unit">{v.unit}</span>}
        </p>
        <ValueBar value={v} />
        <p className="value-row__foot">
          <span className="value-row__range">{rangeText(lang, v)}</span>
          <StatusChip kind={chipKindOf(v.display_kind, v.side)} lang={lang} />
        </p>
      </div>
      {v.display_kind === "unknown" && <p className="paper__why">{t(lang, "sheet.unknown.why")}</p>}
      {sheet.earlier_line_key !== null && (
        <section id="earlier" className="paper__group">
          <EarlierLine sheet={sheet} lang={lang} />
          {sheet.earlier.map((e) => (
            <Earlier key={e.observation_id} e={e} lang={lang} preview={preview} name={v.name} />
          ))}
        </section>
      )}
    </article>
  );
}
