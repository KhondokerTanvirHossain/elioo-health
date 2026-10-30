"use client";

import Link from "next/link";
import type { CropJson, PaperValueJson } from "@/api/types";
import { Crop } from "@/components/Crop/Crop";
import { StatusChipView, type ChipKind } from "@/components/StatusChip/StatusChipView";
import { ValueBar } from "./ValueBar";
import { sheetHref, type From } from "./sheet-href";

/** One value row as its server parent resolved it: Java's strings as sent, its range line and its chip kind. */
export type ValueRowFields = Pick<PaperValueJson, "id" | "display_kind" | "name" | "value" | "unit" | "pin" | "ref_low" | "ref_high"> & {
  crop: CropJson;
  range: string;
  chip: ChipKind;
};

/** What every row of one list shares, sent once: the paper, where a sheet goes back to, and the words in `lang`. */
export type ValueRowsShared = {
  documentId: string;
  from: From;
  preview: boolean;
  retry: string;
  /** The chip's word for every kind (CHIP_KEY, resolved on the server). */
  chipWords: Record<ChipKind, string>;
};

/**
 * Value rows' drawing (ValueRows.row), from the fields ValueRows resolved on the server. A client component only so the
 * page's RSC payload carries each row's fields, and what the rows share once, rather than every row's rendered tree: on
 * the 58-value /all page that tree was most of the document's compressed bytes (DR-42 route ceiling, PO ruling P3). The
 * HTML is the same either way, and nothing here reads, decides or words anything: every string is the server's.
 */
export function ValueRowsView({ rows, ...shared }: ValueRowsShared & { rows: ValueRowFields[] }) {
  return rows.map((row, i) => <ValueRowView key={row.id ?? `row-${i}`} row={row} shared={shared} />);
}

function ValueRowView({ row, shared }: { row: ValueRowFields; shared: ValueRowsShared }) {
  const reading = (
    <>
      <span className="value-row__name">{row.name}</span>
      <span className="value-row__reading">
        <span className="value-row__value">{row.value}</span>
        {row.unit !== null && <span className="value-row__unit">{row.unit}</span>}
      </span>
    </>
  );
  return (
    <div className={`value-row value-row--${row.display_kind}`} data-value-row id={row.id === null ? undefined : `v-${row.id}`}>
      {row.id === null ? (
        <p className="value-row__head">{reading}</p>
      ) : (
        <Link
          className="value-row__head value-row__open"
          href={sheetHref(shared.documentId, row.id, shared.from, shared.preview)}
          prefetch={false}
        >
          {reading}
        </Link>
      )}
      <Crop
        src={row.crop.src}
        width={row.crop.width}
        height={row.crop.height}
        documentId={shared.documentId}
        words={{ retry: shared.retry, alt: row.name ?? "" }}
      />
      <ValueBar value={row} />
      <p className="value-row__foot">
        <span className="value-row__range">{row.range}</span>
        <StatusChipView kind={row.chip} word={shared.chipWords[row.chip]} />
      </p>
    </div>
  );
}
