import type { PaperJson } from "@/api/types";
import { t, type Lang } from "@/copy";
import { paperWords } from "@/app/app/(signed-in)/documents/[id]/paper-words";
import { PaperSummary } from "@/components/Paper/PaperSummary";
import { PaperPanelStyles } from "./desk-load";

/**
 * The safety blocks the panel leaves to the card beside it (PaperPanel.test.tsx compares the rest with today's paper
 * page block for block): the urgency band, Java's sentence, which P19 puts in the NOW bar and on that paper's card and
 * nowhere else on the screen.
 */
export const CARD_CARRIES: readonly string[] = ["band"];

/**
 * A paper's details over the desktop conversation (plan desktop-parity Task 8; ruling P19: `desk.panel.open` on the
 * card opens it, holding everything today's paper page holds, with no safety block dropped). It is today's paper page
 * itself (PaperSummary, frame "panel"), so a block added to the page is in the panel with no change here: the card with
 * the not-a-doctor line and 999 (only on `call_999`), the NOW block, the outside, check-on-paper and inside groups with
 * every value beside its crop and range, the doctor's notes, the medicines, the next step, the pages, and delete for
 * the owner. Not its back link or its patient line, and not the band (CARD_CARRIES). Its close (`sheet.close`: the bn
 * "close" imperative is a forbidden phrase, so the panel says what every sheet says) goes to `closeHref`, the same
 * screen without the panel.
 *
 * A paper still being read shows today's reading card; the viewer beside it runs the check that moves it on.
 */
export function PaperPanel({ lang, preview, paper, closeHref }: { lang: Lang; preview: boolean; paper: PaperJson; closeHref: string }) {
  return (
    <div className="paper-panel" data-panel={paper.id}>
      <PaperPanelStyles />
      <div className="paper-panel__bar">
        <a className="paper-panel__close" href={closeHref}>
          {t(lang, "sheet.close")}
        </a>
      </div>
      <div className="paper-panel__body">
        <PaperSummary paper={paper} lang={lang} preview={preview} words={paperWords(lang)} frame="panel" />
      </div>
    </div>
  );
}
