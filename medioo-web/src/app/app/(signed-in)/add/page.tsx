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
 * patient the page was opened for (the shell answers 404 for one this family does not see, as Java does): it is handed
 * to Java's options read, which answers whom the sheet opens on (`chosen_patient_id`), and to the sheet as received.
 * `?replaces=` is the paper a retake replaces (D1), handed to the sheet as received: Java judges it on the press. Every press needs JavaScript (DR-42): without it the `<noscript>` says so (upload.nojs) and the sheet
 * the server drew has every control off and no `<form>`, so nothing posts anywhere. Java keeps its own `/app/add` until
 * F7 flips the path; until then this page is reached on the preview path.
 */
export default async function AddPage({ searchParams }: { searchParams: Promise<Params> }) {
  const { lang, preview, here } = await requestContext();
  const params = await searchParams;
  const asked = one(params.patient);
  return signedInPage({
    tab: null,
    asksItself: true,
    main: async () => {
      // Whose paper it opens on is Java's (chosen_patient_id, the HTML sheet's own rule): the page only hands Java the
      // patient it was opened for.
      const path = asked === null ? "/app/api/upload-options" : `/app/api/upload-options?patient=${encodeURIComponent(asked)}`;
      const options = signedIn(await javaGet<UploadOptionsJson>(path), here);
      return (
        <>
          <noscript>
            <p className="upload-sheet__nojs">{t(lang, "upload.nojs")}</p>
          </noscript>
          <UploadSheet
            options={options}
            words={uploadWords(lang, options)}
            preview={preview}
            asked={asked}
            replaces={one(params.replaces)}
          />
        </>
      );
    },
  });
}
