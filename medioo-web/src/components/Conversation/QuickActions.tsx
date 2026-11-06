import type { PaperResultJson } from "@/api/types";
import { t, type Lang } from "@/copy";
import { addTips } from "@/app/app/(signed-in)/add/upload-words";
import { href } from "@/routing/paths";
import { TipsSheet } from "./TipsSheet";

/**
 * The actions under a retake card (plan desktop-parity Task 7; desktop.html's `.suggest` under a blurry photo; P19:
 * actions only, no chip sends a question to the model):
 * - `desk.quick.resend`, the add page for this patient with `?replaces=` this paper, the query today's retake link uses
 *   (D1: Java judges the replacement on the press). Left out once Java says the paper was sent again (`replaced`);
 * - `desk.quick.tips`, the add sheet's own tips in a sheet (TipsSheet).
 * Only for a NEEDS_RETAKE paper the viewer owns: a share member sends nothing (the paper page says so, retake.viewonly),
 * and every other card offers no action at all. Returns null when there is nothing to offer.
 */
export function QuickActions({ lang, preview, paper }: { lang: Lang; preview: boolean; paper: PaperResultJson }) {
  if (paper.status !== "NEEDS_RETAKE" || !paper.retake || !paper.owner) return null;
  const add = `${href("/app/add", preview)}?patient=${encodeURIComponent(paper.patient.id)}`;
  const resend = `${add}&replaces=${encodeURIComponent(paper.id)}`;
  const tips = addTips(lang);
  return (
    <div className="quick-actions">
      {!paper.retake.replaced && (
        <a className="quick-actions__chip" href={resend}>
          {t(lang, "desk.quick.resend")}
        </a>
      )}
      <TipsSheet
        words={{ label: t(lang, "desk.quick.tips"), title: tips.title, close: t(lang, "sheet.close"), items: tips.items }}
        fallback={paper.retake.replaced ? add : resend}
      />
    </div>
  );
}
