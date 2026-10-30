import { redirect } from "next/navigation";
import { signedIn } from "@/api/java";
import { requestContext } from "@/api/request";
import { isReadingPaper } from "@/api/types";
import { PaperAll } from "@/components/Paper/PaperAll";
import { PaperGone, paperHref } from "@/components/Paper/PaperShared";
import { signedInPage } from "../../../shell";
import { paperRead } from "../paper-read";
import { paperWords } from "../paper-words";
import { bannerOnlyRefresh, heldMain, keptMain } from "../kept";

/**
 * `/app/documents/{id}/all`: everything on a read paper (plan W6, s17), from the paper's one read. Only a DONE paper has
 * this page, as Java's detailPage decides: any other status goes to the paper itself, which says what is happening.
 * A banner-only refresh reads no paper and keeps what the page shows (banner-only.ts).
 */
export default async function PaperAllPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const { lang, preview, here } = await requestContext();
  const held = await bannerOnlyRefresh(here);
  if (held) return signedInPage({ tab: null, patient: held.patient, main: keptMain(held) });
  const answer = await paperRead(id);
  if (answer.status === 404) {
    return signedInPage({ tab: null, patient: null, main: heldMain(here, null, <PaperGone lang={lang} preview={preview} deleted={false} />) });
  }
  const paper = signedIn(answer, here);
  if (isReadingPaper(paper) || paper.status !== "DONE") redirect(paperHref(paper.id, preview));
  return signedInPage({
    tab: null,
    patient: paper.patient.id,
    main: heldMain(here, paper.patient.id, <PaperAll paper={paper} lang={lang} preview={preview} words={paperWords(lang)} />),
  });
}
