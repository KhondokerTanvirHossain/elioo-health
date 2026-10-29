import { javaGet, signedIn } from "@/api/java";
import { requestContext } from "@/api/request";
import type { LabsJson, MedicinesJson } from "@/api/types";
import { t } from "@/copy";
import { LabList } from "@/components/LabList/LabList";
import { MedicinesAsWritten } from "@/components/MedicinesAsWritten/MedicinesAsWritten";
import { signedInPage } from "../shell";
import "./health.css";

/**
 * The health tab (plan W9; phone.html scene `health`, its labs list, trend and medicines sheets): the patient's lab
 * values, each series as Java sent it (a trend, one reading's sentence, or the readings as written), the blood-pressure
 * pairs, and the medicines as written on the latest prescription, under Java's heading with its date. Two reads,
 * `GET /app/api/patients/{id}/labs` and `/medicines`, in parallel through javaGet (the preview gate, the cookie and
 * nothing else); nothing is joined, judged or derived here. The patient is the shell's (`?patient=` honoured; the
 * chooser when two or more and nobody chosen). The disclaimer is the shell's (AppShell, plan W8 review): under `main` on
 * every signed-in page, so this page does not say it a second time.
 */
export default async function HealthPage() {
  return signedInPage({
    tab: "health",
    main: async ({ lang, preview, patient }) => {
      const title = <h1 className="health__title">{t(lang, "tab.health")}</h1>;
      if (patient === null) {
        return (
          <div className="health" data-page="health">
            {title}
            <p className="health__none">{t(lang, "health.labs.none")}</p>
          </div>
        );
      }
      const { here } = await requestContext();
      const id = encodeURIComponent(patient.id);
      const [labsAnswer, medicinesAnswer] = await Promise.all([
        javaGet<LabsJson>(`/app/api/patients/${id}/labs`),
        javaGet<MedicinesJson>(`/app/api/patients/${id}/medicines`),
      ]);
      const labs = signedIn(labsAnswer, here);
      const medicines = signedIn(medicinesAnswer, here);
      return (
        <div className="health" data-page="health">
          {title}
          <section className="health__section" aria-labelledby="health-labs">
            <h2 className="health__heading" id="health-labs">
              {t(lang, "health.labs")}
            </h2>
            <LabList labs={labs} lang={lang} />
          </section>
          <section className="health__section" aria-labelledby="health-medicines">
            <h2 className="health__heading" id="health-medicines">
              {t(lang, "med.title")}
            </h2>
            <MedicinesAsWritten medicines={medicines} lang={lang} preview={preview} />
          </section>
        </div>
      );
    },
  });
}
