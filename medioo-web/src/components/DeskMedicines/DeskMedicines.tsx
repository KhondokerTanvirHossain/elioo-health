import type { MedicinesJson, PrescribedMedicineJson } from "@/api/types";
import { t, type CopyKey, type Lang } from "@/copy";
import { Crop } from "@/components/Crop/Crop";
import { paperDate } from "@/components/LabList/LabList";
import { DeskMedicinesStyles } from "./desk-load";

export type DeskMedicinesProps = {
  lang: Lang;
  /** The patient's name as Java makes it possessive (`name_possessive`): the mother's, "Ma's". */
  possessive: string;
  /** `GET /app/api/patients/{id}/medicines` as Java sent it. */
  medicines: MedicinesJson;
  /** The talk-and-papers screen showing a paper. */
  open: (documentId: string) => string;
};

/** The fields a line may carry, in PaperDisplay.MEDICINE_FIELDS order; a field the paper does not carry is absent. */
const AFTER_NAME = ["dose_text", "frequency_text", "timing_text", "duration_text"] as const;

/** A written field: Java's string as sent, in its own element (no trim, no digit change, no translation). */
const Written = ({ field, text }: { field: string; text: string }) => (
  <span className="desk-meds__written" data-field={field}>
    {text}
  </span>
);

/**
 * One line of the prescription (desktop.html's `.medcard`): its crop first, what a pharmacy is shown; then the line as
 * written, the name and, after a dash, the rest of what was written with it; then "as written on the prescription" and
 * the way to the paper. No colour, no chip, no status: Medioo says nothing about a medicine. The crop's alternative text
 * says what the picture is (`health.crop.alt`), never the name read off it, which is the very claim the crop lets the
 * family check.
 */
function Line({ lang, line, documentId, open }: { lang: Lang; line: PrescribedMedicineJson; documentId: string; open: string }) {
  const rest = AFTER_NAME.filter((f) => line[f] !== undefined);
  return (
    <li className="desk-meds__card" data-medicine={line.crop.src}>
      <Crop
        src={line.crop.src}
        width={null}
        height={null}
        documentId={documentId}
        words={{ retry: t(lang, "crop.retry"), alt: t(lang, "health.crop.alt") }}
        className="desk-meds__crop"
      />
      <p className="desk-meds__line">
        {line.name !== undefined && <Written field="name" text={line.name} />}
        {line.name !== undefined && rest.length > 0 && <span aria-hidden="true">{" — "}</span>}
        {rest.map((f, i) => (
          <span key={f}>
            {i > 0 && <span aria-hidden="true"> · </span>}
            <Written field={f} text={line[f]!} />
          </span>
        ))}
      </p>
      <div className="desk-meds__src">
        <span>{t(lang, "desk.meds.as_written")}</span>
        <a className="desk-meds__paper" href={open}>
          {t(lang, "desk.meds.paper")}
        </a>
      </div>
    </li>
  );
}

/**
 * The medicines on the desktop layout (plan desktop-parity Phase 2, row M1; desktop.html `medsView()`): one column of
 * the shell, a readable measure centred in it. The heading names the patient (`desk.meds.title`) and says what the
 * screen holds; then `GET …/medicines` as Java sent it, the phone's rules (MedicinesAsWritten) with desktop.html's look:
 * - the newest prescription only, under Java's heading (`heading_key`: as written, the latest, with its date; never
 *   "current") and its date; each line its own crop, the line as written, and the way to the paper on the
 *   talk-and-papers screen; under them the pharmacy and doctor lines (`med.pharmacy`, `health.medicines.doctor_only`);
 * - an empty newest prescription is its date and nothing else, never an older list in its place;
 * - a newer prescription still being read is said and linked; none read yet: desktop.html's line, or, when the first
 *   is being read, that one, linked.
 * Its stylesheet comes through its loader (desk-load.tsx), so only a page that renders it links it (DR-42).
 */
export function DeskMedicines({ lang, possessive, medicines, open }: DeskMedicinesProps) {
  const reading = medicines.newer_prescription_not_read ? medicines.newer_document_id : null;
  const { document_id: documentId, date } = medicines;
  let body;
  if (documentId === null || date === undefined) {
    body =
      reading !== null ? (
        <p className="desk-meds__note">
          <a className="desk-meds__link" href={open(reading)}>
            {t(lang, "health.medicines.first_unread")}
          </a>
        </p>
      ) : (
        <div className="desk-meds__group">
          <p className="desk-meds__empty">{t(lang, "desk.meds.none")}</p>
        </div>
      );
  } else {
    const when = paperDate(lang, {
      date,
      date_is_upload_day: medicines.date_is_upload_day === true,
      date_uncertain: medicines.date_uncertain === true,
    });
    body = (
      <>
        <p className="desk-meds__head">
          <span>{t(lang, medicines.heading_key as CopyKey)}</span>
          <span aria-hidden="true"> · </span>
          <span>{when}</span>
        </p>
        {medicines.medicines.length > 0 && (
          <>
            <ul className="desk-meds__group">
              {medicines.medicines.map((line) => (
                <Line key={line.crop.src} lang={lang} line={line} documentId={documentId} open={open(documentId)} />
              ))}
            </ul>
            <div className="desk-meds__foot">
              <p>{t(lang, "med.pharmacy")}</p>
              <p>{t(lang, "health.medicines.doctor_only")}</p>
            </div>
          </>
        )}
        {reading !== null && (
          <p className="desk-meds__note">
            <a className="desk-meds__link" href={open(reading)}>
              {t(lang, "health.medicines.newer_unread")}
            </a>
          </p>
        )}
      </>
    );
  }
  return (
    <div className="desk-meds">
      <DeskMedicinesStyles />
      <h1 className="desk-meds__bar">{t(lang, "desk.nav.meds")}</h1>
      <div className="desk-meds__col">
        <h2 className="desk-meds__title">{t(lang, "desk.meds.title", possessive)}</h2>
        <p className="desk-meds__lede">{t(lang, "desk.meds.lede")}</p>
        {body}
      </div>
    </div>
  );
}
