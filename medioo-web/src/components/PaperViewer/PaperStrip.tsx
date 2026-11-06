import Link from "next/link";
import { isReadingPaper, type PaperJson } from "@/api/types";
import { t, type Lang } from "@/copy";
import { UrgencyTag } from "@/components/safety/UrgencyTag/UrgencyTag";
import { typeText } from "@/components/Paper/paper-text";
import { viewerDay } from "./viewer-day";

/** The strip's words for one paper: its day under the thumbnail, marked as the viewer marks it (or "being read", which has no day yet), and its type. */
function labelOf(lang: Lang, paper: PaperJson): { day: string; type: string } {
  if (isReadingPaper(paper)) return { day: t(lang, "status.PROCESSING"), type: t(lang, "type.default") };
  return { day: viewerDay(lang, paper), type: typeText(lang, paper.document_type) };
}

/**
 * The patient's papers along the foot of the viewer (plan desktop-parity Task 6; desktop.html's `.film`), in the order
 * given (newest first), each a link (`hrefFor`) with page 1 as its thumbnail, its day, and its stored urgency: UrgencyTag's
 * shape in its colour, its word read out but not drawn (the strip has no room for it). ROUTINE and an unassessed or
 * retake paper say nothing (D27). The paper in view is `aria-current`. Thumbnails load only when scrolled to: 45
 * papers are 45 page images. It scrolls sideways and never widens the column.
 */
export function PaperStrip({
  lang,
  papers,
  selectedId,
  hrefFor,
  label,
}: {
  lang: Lang;
  papers: PaperJson[];
  selectedId: string | null;
  hrefFor: (id: string) => string;
  label: string;
}) {
  return (
    <nav className="paper-strip" aria-label={label}>
      <ol className="paper-strip__list">
        {papers.map((paper) => {
          const { day, type } = labelOf(lang, paper);
          const first = paper.pages[0];
          const current = paper.id === selectedId;
          return (
            <li key={paper.id} className="paper-strip__entry">
              <Link
                className="paper-strip__item"
                href={hrefFor(paper.id)}
                aria-current={current ? "true" : undefined}
                title={`${type} · ${day}`}
                prefetch={false}
              >
                <span className="paper-strip__thumb">
                  {first !== undefined && (
                    // eslint-disable-next-line @next/next/no-img-element -- next/image is banned (Global Constraints)
                    <img className="paper-strip__img" src={first.src} alt="" loading="lazy" decoding="async" />
                  )}
                  {!isReadingPaper(paper) && (
                    <span className="paper-strip__urgency">
                      <UrgencyTag level={paper.urgency} lang={lang} />
                    </span>
                  )}
                </span>
                <span className="paper-strip__type">{type}</span>
                <span className="paper-strip__date">{day}</span>
              </Link>
            </li>
          );
        })}
      </ol>
    </nav>
  );
}
