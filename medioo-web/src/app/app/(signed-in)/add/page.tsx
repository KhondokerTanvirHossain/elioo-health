import { javaGet, signedIn } from "@/api/java";
import { requestContext } from "@/api/request";
import type { UploadOptionsJson } from "@/api/types";
import { t } from "@/copy";
import { UploadSheet } from "@/components/Upload/UploadSheet";
import { signedInPage } from "../shell";
import { uploadWords } from "./upload-words";

type Params = Record<string, string | string[] | undefined>;
const one = (v: string | string[] | undefined) => (typeof v === "string" && v !== "" ? v : null);

/**
 * `/app/add` (plan W7): the add sheet as a page, inside the shell, over GET /app/api/upload-options. `?patient=` is the
 * patient the page was opened for (the shell answers 404 for one this family does not see, as Java does), and
 * `?replaces=` the paper a retake replaces (D1), both handed to the sheet as the page received them: Java judges them
 * on the press. Every press needs JavaScript (DR-42): without it the `<noscript>` says so (upload.nojs) and the sheet
 * the server drew has every control off and no `<form>`, so nothing posts anywhere. Java keeps its own `/app/add` until
 * F7 flips the path; until then this page is reached on the preview path.
 */
export default async function AddPage({ searchParams }: { searchParams: Promise<Params> }) {
  const { lang, preview, here } = await requestContext();
  const params = await searchParams;
  return signedInPage({
    tab: null,
    asksItself: true,
    main: async () => {
      const options = signedIn(await javaGet<UploadOptionsJson>("/app/api/upload-options"), here);
      return (
        <>
          <noscript>
            <p className="upload-sheet__nojs">{t(lang, "upload.nojs")}</p>
          </noscript>
          <UploadSheet
            options={options}
            words={uploadWords(lang, options)}
            preview={preview}
            asked={one(params.patient)}
            replaces={one(params.replaces)}
          />
        </>
      );
    },
  });
}
