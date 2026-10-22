import Link from "next/link";
import { t, type Lang } from "@/copy";
import type { CropJson, FollowUpJson, MedicineJson, PaperValueJson } from "@/api/types";
import { Crop, type CropWords } from "@/components/Crop/Crop";
import { StatusChip, chipKindOf } from "@/components/StatusChip/StatusChip";
import { href } from "@/routing/paths";
import { dueText, rangeText } from "./paper-text";

/*
 * The rows a paper is drawn with (plan W6; ValueRows.java): one value beside its own crop, a medicine crop first and
 * verbatim, a line quoted from the paper beside its crop. Server components: the crop is the one island inside them,
 * and it takes its words as props. Every row is drawn from the fields Java sent and nothing else.
 */

/** The medicine's written fields in PaperDisplay.MEDICINE_FIELDS order; a field the paper does not carry is absent. */
export const MEDICINE_FIELDS = ["name", "dose_text", "frequency_text", "timing_text", "duration_text"] as const;

/** Where a value sheet was opened from, so its close goes back to the same row (ValueRows.FROM_SUMMARY / FROM_ALL). */
export type From = "summary" | "all";

/** The value sheet's address for one observation of this paper, inside the preview on a preview page. */
export function sheetHref(documentId: string, observationId: string, from: From, preview: boolean): string {
  return href(`/app/documents/${documentId}/values/${observationId}`, preview) + `?from=${from}`;
}

/** The crop's alternative text and retry line: the row's own name as the paper printed it, and `crop.retry`. */
export function cropWords(lang: Lang, alt: string | null): CropWords {
  return { retry: t(lang, "crop.retry"), alt: alt ?? "" };
}

/** The range bar, only where Java placed the pin (`pin`, PaperDisplay.pin): the band is the printed range. */
export function ValueBar({ value }: { value: PaperValueJson }) {
  if (value.pin === null) return null;
  return (
    <span className="value-bar" aria-hidden="true">
      <svg className="value-bar__drawing" viewBox="0 0 100 10" focusable="false" preserveAspectRatio="none">
        <rect className="value-bar__line" x="0" y="4" width="100" height="2" />
        <rect className="value-bar__band" x="25" y="2" width="50" height="6" />
        <circle className="value-bar__pin" cx={value.pin.toFixed(1)} cy="5" r="4" />
      </svg>
      <span className="value-bar__ends">
        <span>{value.ref_low}</span>
        <span>{value.ref_high}</span>
      </span>
    </span>
  );
}

/**
 * One value (ValueRows.row): the name and the value with its unit as printed, which open the value's sheet; its own
 * crop; the bar where Java placed the pin; the printed range; and the chip Java chose (`display_kind` and `side`,
 * through chipKindOf, the one place an API value becomes a chip). A legacy row with no observation id opens no sheet.
 */
export function ValueRow({
  value,
  documentId,
  lang,
  preview,
  from,
}: {
  value: PaperValueJson;
  documentId: string;
  lang: Lang;
  preview: boolean;
  from: From;
}) {
  const reading = (
    <>
      <span className="value-row__name">{value.name}</span>
      <span className="value-row__reading">
        <span className="value-row__value">{value.value}</span>
        {value.unit !== null && <span className="value-row__unit">{value.unit}</span>}
      </span>
    </>
  );
  return (
    <div className={`value-row value-row--${value.display_kind}`} data-value-row id={value.id === null ? undefined : `v-${value.id}`}>
      {value.id === null ? (
        <p className="value-row__head">{reading}</p>
      ) : (
        <Link className="value-row__head value-row__open" href={sheetHref(documentId, value.id, from, preview)} prefetch={false}>
          {reading}
        </Link>
      )}
      <Crop
        src={value.crop.src}
        width={value.crop.width}
        height={value.crop.height}
        documentId={documentId}
        words={cropWords(lang, value.name)}
      />
      <ValueBar value={value} />
      <p className="value-row__foot">
        <span className="value-row__range">{rangeText(lang, value)}</span>
        <StatusChip kind={chipKindOf(value.display_kind, value.side)} lang={lang} />
      </p>
    </div>
  );
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
