import { notFound } from "next/navigation";
import { javaGet, signedIn } from "@/api/java";
import { requestContext } from "@/api/request";
import type { FamilyJson } from "@/api/types";
import { PatientCard } from "@/components/Family/PatientCard";
import { signedInPage } from "../../../shell";
import { familyWords } from "../../../family/family-words";

/**
 * One patient's section of family on its own (plan W10): the address WhatsApp's profile prompts and the opt-out page
 * already sent (`/app/patients/{id}/profile`). Java's ProfilePages.profilePage answers it by choosing that patient
 * (302 to `/app/family?patient={id}`), so the page passes the id as the shell's `asked` patient: the toolbar names this
 * patient whatever the family last chose, and one this account cannot see (or no such id) is the not-found page, by
 * the same rule as `?patient=`. It reads GET /app/api/family, the same body as the tab, and shows that patient's card,
 * the page's own subject (headingLevel 2); a patient the body does not list is the not-found page, as Java answers 404.
 */
export default async function PatientProfilePage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return signedInPage({
    tab: "family",
    asked: id,
    main: async ({ lang }) => {
      const { here } = await requestContext();
      const family = signedIn(await javaGet<FamilyJson>("/app/api/family"), here);
      const patient = family.patients.find((p) => p.id === id);
      if (patient === undefined) notFound();
      const words = familyWords(lang, family);
      return (
        <div className="family">
          <h1 className="family__heading">{words.heading}</h1>
          <PatientCard patient={patient} words={words.patients[patient.id]!} flags={words.flags} headingLevel={2} />
        </div>
      );
    },
  });
}
