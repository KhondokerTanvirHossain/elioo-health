import type { FamilyJson } from "@/api/types";
import type { Theme } from "@/app/theme";
import type { Lang } from "@/copy";
import type { FlagWords } from "./ChronicFlags";
import { FamilyAccount, type AccountWords } from "./FamilyAccount";
import { PatientCard, type PatientWords } from "./PatientCard";
import "./Family.css";

/**
 * Every word the family tab says, resolved on the server (src/app/app/(signed-in)/family/family-words.ts) from
 * GET /app/api/family: the islands inside (the flags, the reminder stops, the theme, the language, sign out) take theirs
 * as props and ship no copy.
 */
export type FamilyWords = {
  /** `tab.family`: the page's heading. */
  heading: string;
  /** `family.patients.title`, and `patients.none` for a family with nobody yet. */
  patientsTitle: string;
  none: string;
  flags: FlagWords;
  /** Each patient's words, by id. */
  patients: Record<string, PatientWords>;
  account: AccountWords;
};

/**
 * The family tab (plan W10; phone.html scene `family`): every patient this account sees, in GET /app/api/family's order
 * (which is `me`'s), each as a PatientCard, then the account (FamilyAccount). The tab is about everyone, so no patient
 * is chosen for it. The number is rendered once, in FamilyAccount.
 */
export function FamilyScreen({
  family,
  words,
  theme,
  lang,
  signOutTo,
}: {
  family: FamilyJson;
  words: FamilyWords;
  theme: Theme;
  lang: Lang;
  signOutTo: string;
}) {
  return (
    <div className="family">
      <h1 className="family__heading">{words.heading}</h1>
      <section className="family__patients" aria-labelledby="family-patients-title">
        <h2 className="family__section-title" id="family-patients-title">
          {words.patientsTitle}
        </h2>
        {family.patients.length === 0 ? (
          <p className="family__none">{words.none}</p>
        ) : (
          family.patients.map((p) => (
            <PatientCard key={p.id} patient={p} words={words.patients[p.id]!} flags={words.flags} headingLevel={3} />
          ))
        )}
      </section>
      <FamilyAccount account={family.account} words={words.account} theme={theme} lang={lang} signOutTo={signOutTo} />
    </div>
  );
}
