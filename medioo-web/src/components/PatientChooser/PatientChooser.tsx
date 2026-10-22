import type { MePatient } from "@/api/types";
import { t, type Lang } from "@/copy";
import { UrgentMark } from "@/components/safety/UrgentMark/UrgentMark";
import { ChooseLink } from "./ChooseLink";
import "./PatientChooser.css";

/**
 * The chooser (plan W4): what a signed-in page shows in place of its own content when this family sees two or more
 * patients and Java remembers no choice. It asks (`switch.title`, `switch.hint`: "Medioo will not guess") and lists every
 * patient this family may see, in GET /app/api/me's order, none marked: a screen never decides a patient. An urgent
 * patient carries its red mark (shape and word). Each name is a link to `?patient=<id>` (ChooseLink).
 */
export function PatientChooser({ lang, patients }: { lang: Lang; patients: MePatient[] }) {
  const refused = t(lang, "app.err.notfound");
  const failed = t(lang, "app.err.generic");
  return (
    <section className="patient-chooser" aria-labelledby="patient-chooser-title">
      <h1 className="patient-chooser__title" id="patient-chooser-title">
        {t(lang, "switch.title")}
      </h1>
      <p className="patient-chooser__hint">{t(lang, "switch.hint")}</p>
      <ul className="patient-chooser__list">
        {patients.map((p) => (
          <li key={p.id} className="patient-chooser__item">
            <ChooseLink patientId={p.id} refused={refused} failed={failed}>
              <span className="patient-chooser__name">{p.name}</span>
              {p.urgent && <UrgentMark word={t(lang, "attn.strip")} />}
            </ChooseLink>
          </li>
        ))}
      </ul>
    </section>
  );
}
