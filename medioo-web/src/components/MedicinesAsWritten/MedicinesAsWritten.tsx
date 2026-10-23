import { t, type CopyKey, type Lang } from "@/copy";
import type { MedicinesJson, PrescribedMedicineJson } from "@/api/types";
import { Crop } from "@/components/Crop/Crop";
import { paperDate } from "@/components/LabList/LabList";
import { href } from "@/routing/paths";
import "./MedicinesAsWritten.css";

/** The fields a line may carry, in PaperDisplay.MEDICINE_FIELDS order; a field the paper does not carry is absent. */
const FIELDS = ["name", "dose_text", "frequency_text", "timing_text", "duration_text"] as const;

/**
 * One line of the prescription: its crop first and large, what a pharmacy is shown, then exactly the fields the line
 * was written with, each its own element holding Java's string as sent: no trim, no digit change, no translation. No
 * colour, no chip, no status: Medioo says nothing about a medicine. The crop's alternative text says what the picture
 * is (`health.crop.alt`), never the name read off it, which is the very claim the crop lets the family check.
 */
function Line({ line, documentId, lang }: { line: PrescribedMedicineJson; documentId: string; lang: Lang }) {
  const fields = FIELDS.filter((f) => line[f] !== undefined);
  return (
    <li className="medicines__line" data-value-row>
      <Crop
        src={line.crop.src}
        width={null}
        height={null}
        documentId={documentId}
        words={{ retry: t(lang, "crop.retry"), alt: t(lang, "health.crop.alt") }}
        className="medicines__crop"
      />
      <p className="medicines__writing">
        {fields.map((f, i) => (
          <span key={f}>
            {i > 0 && <span aria-hidden="true"> · </span>}
            <span className="medicines__written" data-field={f}>
              {line[f]}
            </span>
          </span>
        ))}
      </p>
    </li>
  );
}

/**
 * The medicines of the health screen (plan W9; Q3, decision 9, DR-45 item 7): `GET /app/api/patients/{id}/medicines`
 * as Java sent it. Under Java's heading (`heading_key`, "as written on the prescription — the latest, with its date",
 * never "current") and the prescription's date, each line beside its own crop. The newest prescription with nothing on
 * it is its date and nothing else: what is drawn is only this answer, never an older list kept from before. A newer
 * prescription still being read is said, and links to that paper. Under the lines, the pharmacy rule (`med.pharmacy`:
 * the original, never typed text), the medicines sheet's foot line (only the doctor changes or stops a medicine) and
 * the prescription itself, linked (phone.html medsSheet). No prescription read yet: `health.medicines.none`; nothing
 * read yet with one being read (a new family's first): `health.medicines.first_unread`, linked, never "a newer one".
 * Every link to a paper goes through `href`, so a preview page stays inside the preview once this build serves it.
 */
export function MedicinesAsWritten({ medicines, lang, preview }: { medicines: MedicinesJson; lang: Lang; preview: boolean }) {
  const reading = medicines.newer_prescription_not_read ? medicines.newer_document_id : null;
  const paper = (id: string) => href(`/app/documents/${id}`, preview);
  const { document_id: documentId, date } = medicines;
  if (documentId === null || date === undefined) {
    return (
      <div className="medicines">
        {reading !== null ? (
          <p className="medicines__none medicines__first">
            <a className="medicines__newer-link" href={paper(reading)}>
              {t(lang, "health.medicines.first_unread")}
            </a>
          </p>
        ) : (
          <p className="medicines__none">{t(lang, "health.medicines.none")}</p>
        )}
      </div>
    );
  }
  const newer =
    reading !== null ? (
      <p className="medicines__newer">
        <a className="medicines__newer-link" href={paper(reading)}>
          {t(lang, "health.medicines.newer_unread")}
        </a>
      </p>
    ) : null;
  return (
    <div className="medicines">
      <p className="medicines__head">
        <span className="medicines__heading">{t(lang, medicines.heading_key as CopyKey)}</span>
        <span className="medicines__date">
          {paperDate(lang, {
            date,
            date_is_upload_day: medicines.date_is_upload_day === true,
            date_uncertain: medicines.date_uncertain === true,
          })}
        </span>
      </p>
      {medicines.medicines.length > 0 && (
        <>
          <ul className="medicines__lines">
            {medicines.medicines.map((line) => (
              <Line key={line.crop.src} line={line} documentId={documentId} lang={lang} />
            ))}
          </ul>
          <div className="medicines__foot">
            <p className="medicines__pharmacy">{t(lang, "med.pharmacy")}</p>
            <p className="medicines__doctor">{t(lang, "health.medicines.doctor_only")}</p>
            <p className="medicines__open">
              <a className="medicines__newer-link" href={paper(documentId)}>
                {t(lang, "health.medicines.open")}
              </a>
            </p>
          </div>
        </>
      )}
      {newer}
    </div>
  );
}
