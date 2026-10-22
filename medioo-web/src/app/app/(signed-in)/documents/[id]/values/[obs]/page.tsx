import { signedIn } from "@/api/java";
import { requestContext } from "@/api/request";
import { ValueSheet } from "@/components/Paper/ValueSheet";
import { signedInPage } from "../../../../shell";
import { valueSheetRead } from "../../paper-read";

/**
 * `/app/documents/{id}/values/{obs}?from=summary|all`: one value's sheet (plan W6, s13) as a page of its own, from the
 * sheet's one read. A 404 from Java (another family's paper, an observation not on this paper, a paper that is not
 * DONE, R1) is the not-found page; `from` says only where the close goes back to.
 */
export default async function ValueSheetPage({
  params,
  searchParams,
}: {
  params: Promise<{ id: string; obs: string }>;
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const { id, obs } = await params;
  const { lang, preview, here } = await requestContext();
  const sheet = signedIn(await valueSheetRead(id, obs), here);
  const from = (await searchParams).from === "all" ? "all" : "summary";
  return signedInPage({
    tab: null,
    patient: sheet.paper.patient.id,
    main: <ValueSheet sheet={sheet} lang={lang} preview={preview} from={from} />,
  });
}
