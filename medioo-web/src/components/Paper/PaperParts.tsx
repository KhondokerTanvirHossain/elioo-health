import { t, type Lang } from "@/copy";
import type { CropJson, FollowUpJson, MedicineJson, PaperValueJson } from "@/api/types";
import { Crop, type CropWords } from "@/components/Crop/Crop";
import { CHIP_KEY, CHIP_KINDS, chipKindOf, type ChipKind } from "@/components/StatusChip/StatusChip";
import { dueText, rangeText } from "./paper-text";
import { ValueRowsView, type ValueRowFields } from "./ValueRowView";
import type { From } from "./sheet-href";

export { ValueBar } from "./ValueBar";
export { sheetHref, type From } from "./sheet-href";

/*
 * The rows a paper is drawn with (plan W6; ValueRows.java): one value beside its own crop, a medicine crop first and
 * verbatim, a line quoted from the paper beside its crop. Server components that resolve every word: the crop is the
 * one island inside a medicine or a quote, and a value row is drawn by its client view (ValueRowView) from the fields
 * resolved here, so the RSC payload carries the row's fields and not its tree. Every row is drawn from the fields Java
 * sent and nothing else.
 */

/** The medicine's written fields in PaperDisplay.MEDICINE_FIELDS order; a field the paper does not carry is absent. */
export const MEDICINE_FIELDS = ["name", "dose_text", "frequency_text", "timing_text", "duration_text"] as const;

/** The crop's alternative text and retry line: the row's own name as the paper printed it, and `crop.retry`. */
export function cropWords(lang: Lang, alt: string | null): CropWords {
  return { retry: t(lang, "crop.retry"), alt: alt ?? "" };
}

/** The fields one value row is drawn from (ValueRowsView), each resolved here from what Java sent. */
function rowFields(lang: Lang, value: PaperValueJson): ValueRowFields {
  return {
    id: value.id,
    display_kind: value.display_kind,
    name: value.name,
    value: value.value,
    unit: value.unit,
    pin: value.pin,
    ref_low: value.ref_low,
    ref_high: value.ref_high,
    crop: value.crop,
    range: rangeText(lang, value),
    chip: chipKindOf(value.display_kind, value.side),
  };
}

/**
 * Value rows (ValueRows.row), each: the name and the value with its unit as printed, which open the value's sheet; its
 * own crop; the bar where Java placed the pin; the printed range; and the chip Java chose (`display_kind` and `side`,
 * through chipKindOf, the one place an API value becomes a chip). A legacy row with no observation id opens no sheet.
 * Drawn by ValueRowsView from the fields resolved here, with what the rows share (the paper, the words) sent once.
 */
export function ValueRows({
  values,
  documentId,
  lang,
  preview,
  from,
}: {
  values: PaperValueJson[];
  documentId: string;
  lang: Lang;
  preview: boolean;
  from: From;
}) {
  if (values.length === 0) return null;
  return (
    <ValueRowsView
      rows={values.map((v) => rowFields(lang, v))}
      documentId={documentId}
      from={from}
      preview={preview}
      retry={t(lang, "crop.retry")}
      chipWords={Object.fromEntries(CHIP_KINDS.map((k) => [k, t(lang, CHIP_KEY[k])])) as Record<ChipKind, string>}
    />
  );
}

/** One value row (a NOW block's row, a sheet's): ValueRows of one. */
export function ValueRow({ value, ...rest }: { value: PaperValueJson; documentId: string; lang: Lang; preview: boolean; from: From }) {
  return <ValueRows values={[value]} {...rest} />;
}

/** A line from the paper, verbatim, under the paper's label and beside its crop (ValueRows.paperQuote). */
export function PaperQuote({
  text,
  crop,
  documentId,
  lang,
  due,
}: {
  text: string | null;
  crop: CropJson;
  documentId: string;
  lang: Lang;
  due?: string | null;
}) {
  return (
    <figure className="paper-quote" data-crop-row>
      <figcaption className="paper-quote__label">{t(lang, "paper.label")}</figcaption>
      <Crop src={crop.src} width={crop.width} height={crop.height} documentId={documentId} words={cropWords(lang, text)} />
      {text !== null && <blockquote className="paper-quote__text">{text}</blockquote>}
      {due !== undefined && due !== null && <p className="paper-quote__due">{t(lang, "next.followup", dueText(lang, due))}</p>}
    </figure>
  );
}

/** A follow-up as the paper wrote it, with its date on the patient's calendar. */
export function FollowUpQuote({ line, documentId, lang }: { line: FollowUpJson; documentId: string; lang: Lang }) {
  return <PaperQuote text={line.instruction} crop={line.crop} documentId={documentId} lang={lang} due={line.due_date} />;
}

/**
 * A medicine (ValueRows.medicine): the crop first and large, what a pharmacy is shown, then exactly the fields the
 * paper wrote, each its own element holding Java's string as sent: no trim, no digit change, no translation. No colour,
 * no chip, no status: Medioo says nothing about a medicine.
 */
export function MedicineRow({ medicine, documentId, lang }: { medicine: MedicineJson; documentId: string; lang: Lang }) {
  const fields = MEDICINE_FIELDS.filter((f) => medicine[f] !== undefined);
  return (
    <div className="medicine-row" data-medicine-row data-crop-row>
      <Crop
        src={medicine.crop.src}
        width={medicine.crop.width}
        height={medicine.crop.height}
        documentId={documentId}
        words={cropWords(lang, medicine.name ?? null)}
        className="crop--big"
      />
      <p className="medicine-row__writing">
        {fields.map((f, i) => (
          <span key={f}>
            {i > 0 && <span aria-hidden="true"> · </span>}
            <span className="medicine-row__written" data-field={f}>
              {medicine[f]}
            </span>
          </span>
        ))}
      </p>
    </div>
  );
}
