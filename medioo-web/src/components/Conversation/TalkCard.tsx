import { isReadingPaper, type PaperJson } from "@/api/types";
import { t, type Lang } from "@/copy";
import { typeText } from "@/components/Paper/paper-text";
import { viewerDay } from "@/components/PaperViewer/viewer-day";
import { VerdictCard } from "@/components/safety/VerdictCard/VerdictCard";
import { webText } from "./web-text";

/** `link` with `panel=1` added, the details panel over the conversation (plan desktop-parity Task 8's route). */
export function panelHref(link: string): string {
  return `${link}${link.includes("?") ? "&" : "?"}panel=1`;
}

/**
 * Medioo's card for one paper in the desktop conversation (plan desktop-parity Task 7; desktop.html `card()`): today's
 * VerdictCard over the card Java sent, so the band, Java's sentence, the not-a-doctor line, the 999 link (only on `call_999`),
 * the retake reason, the gate's wording and up to three value rows (threadRows, Java's order, the NOW block's first)
 * are exactly the phone's. Added around them, never in their place:
 * - under the title, the paper's type, its day (marked when it is the upload day or unsure, as the viewer writes it)
 *   and the lab as printed; nothing for a paper still being read, which has none of them yet;
 * - the outline when its paper is the one the viewer shows (`selected`);
 * - `desk.panel.open`, every detail of a finished paper in the panel over the conversation (P19).
 * The released wording is Java's WhatsApp text: it is shown without its link lines (webText, Java's web_body rule), so
 * no raw link is ever shown; every other line, the urgency and emergency lines among them, as written. The gate still
 * decides whether it is shown at all (VerdictCard).
 * The title opens the paper in the viewer (`link`).
 */
export function TalkCard({ lang, paper, link, selected }: { lang: Lang; paper: PaperJson; link: string; selected: boolean }) {
  if (isReadingPaper(paper)) return <VerdictCard paper={paper} lang={lang} link={link} selected={selected} />;
  const facility = paper.facility?.trim() ? paper.facility : null;
  const sub = [typeText(lang, paper.document_type), viewerDay(lang, paper), facility].filter(Boolean).join(" · ");
  const shown = typeof paper.wording === "string" ? { ...paper, wording: webText(paper.wording) } : paper;
  return (
    <VerdictCard
      paper={shown}
      lang={lang}
      link={link}
      sub={sub}
      selected={selected}
      more={{ href: panelHref(link), label: t(lang, "desk.panel.open") }}
    />
  );
}
