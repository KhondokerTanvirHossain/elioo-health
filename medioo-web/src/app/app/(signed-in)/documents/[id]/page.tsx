import { signedIn } from "@/api/java";
import { requestContext } from "@/api/request";
import { PaperGone, PaperSummary } from "@/components/Paper/PaperSummary";
import { signedInPage } from "../../shell";
import { paperRead } from "./paper-read";
import { paperWords } from "./paper-words";

/**
 * `/app/documents/{id}`: one paper (plan W6), from its one read (paperRead) and nothing else, inside the shell, the
 * paper's own patient shown. No session is the sign-in page holding this place (a 307, so a no-JavaScript browser gets
 * there too, Review Focus 2); a preview page for a family outside the preview is the not-found page before the paper is
 * read (javaGet's gate). A paper Java no longer shows this family (deleted, by them or another member) says so, with
 * the way back: `doc.gone`, and `doc.deleted` when this browser's own delete brought it here.
 */
export default async function PaperPage({
  params,
  searchParams,
}: {
  params: Promise<{ id: string }>;
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const { id } = await params;
  const { lang, preview, here } = await requestContext();
  const answer = await paperRead(id);
  if (answer.status === 404) {
    const deleted = (await searchParams).deleted === "1";
    return signedInPage({ tab: null, patient: null, main: <PaperGone lang={lang} preview={preview} deleted={deleted} /> });
  }
  const paper = signedIn(answer, here);
  return signedInPage({
    tab: null,
    patient: paper.patient.id,
    main: <PaperSummary paper={paper} lang={lang} preview={preview} words={paperWords(lang)} />,
  });
}
