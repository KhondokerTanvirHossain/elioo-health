import type { FollowUpItemJson } from "@/api/types";
import { formatDate, t, type Lang } from "@/copy";
import "./DueCard.css";

/**
 * One date a doctor gave (plan W8; phone.html scene `papers`, the due card), from GET /app/api/patients/{id}/follow-ups:
 * the doctor's words exactly as Java sent them, beside their own crop (DR-12: the words are checked against the paper,
 * so the picture sits next to them), the day Java sent through `due.date`, and Java's `overdue` as a shape and a word
 * (`due.overdue`), never a colour alone and never red or amber (those are NOW's and THIS_WEEK's). Nothing here compares
 * a date with today: `overdue` is Java's, against the patient's Dhaka day.
 */
export function DueCard({ item, lang }: { item: FollowUpItemJson; lang: Lang }) {
  return (
    <div className="due-card" data-value-row="" data-overdue={item.overdue ? "true" : "false"}>
      {/* eslint-disable-next-line @next/next/no-img-element -- next/image is banned (Global Constraints); Java serves the crop */}
      <img className="due-card__crop" src={item.crop.src} alt="" decoding="async" />
      <div className="due-card__text">
        <p className="due-card__instruction">{item.instruction}</p>
        <p className="due-card__when">
          <span className="due-card__date">{t(lang, "due.date", formatDate(lang, item.due_date))}</span>
          {item.overdue && (
            <span className="due-card__overdue">
              <svg className="due-card__shape" viewBox="0 0 24 24" aria-hidden="true" focusable="false" data-shape="overdue">
                <circle cx="12" cy="12" r="8.5" />
                <path d="M12 7.5V12l3 2M4 4l3 3" />
              </svg>
              <span className="due-card__word">{t(lang, "due.overdue")}</span>
            </span>
          )}
        </p>
      </div>
    </div>
  );
}

/** The doctor's dates under their heading (`due.title`), soonest first as Java sent them; none is nothing at all. */
export function DueCards({ items, lang }: { items: FollowUpItemJson[]; lang: Lang }) {
  if (items.length === 0) return null;
  return (
    <section className="due-card__list" aria-labelledby="due-card-title">
      <h2 className="due-card__title" id="due-card-title">
        {t(lang, "due.title")}
      </h2>
      <ul className="due-card__items">
        {items.map((item) => (
          <li key={`${item.document_id}-${item.crop.src}`} className="due-card__item">
            <DueCard item={item} lang={lang} />
          </li>
        ))}
      </ul>
    </section>
  );
}
