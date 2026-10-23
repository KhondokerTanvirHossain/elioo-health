import type { FamilyPatientJson } from "@/api/types";
import { ChronicFlags, type FlagWords } from "./ChronicFlags";
import { ReminderSwitch, type ReminderWords } from "./ReminderSwitch";
import "./Family.css";

/** One patient's words, resolved by the server (family-words.ts) from that patient's own fields. */
export type PatientWords = {
  /** `family.info.title` with the possessive name: the card's heading. */
  title: string;
  /** `patients.shared` for a patient shared with the viewer; null for one they own. */
  shared: string | null;
  /** `family.info.age` and `patients.years`; `age` null when Java has none. */
  ageLabel: string;
  age: string | null;
  /** `family.info.sex` and the `sex_key` Java sent. */
  sexLabel: string;
  sex: string;
  /** `family.consent.title` and `family.consent.at` with the date; `consentAt` null when none is recorded. */
  consentTitle: string;
  consentAt: string | null;
  /** `profile.nudges`: the reminders' heading. */
  remindersTitle: string;
  reminders: ReminderWords;
};

/**
 * One patient on the family tab (plan W10; phone.html scene `family`), and on its own at `/app/patients/{id}/profile`
 * (the link WhatsApp's profile prompts already sent): whose they are, their age and sex as recorded, the long-term
 * conditions (ChronicFlags), the consent date, and the reminders (ReminderSwitch). Only the owner may change anything
 * (D30): for a share member it is the same facts, marked shared, with no control at all. Nothing here derives a fact.
 *
 * `headingLevel`: where the card sits in its page's outline. On the family tab each patient is one of the group
 * `family.patients.title` (an h2), so the name is an h3 and its subtitles h4; on the profile page the patient is the
 * page's own subject, an h2 with h3 subtitles.
 */
export function PatientCard({
  patient,
  words,
  flags,
  headingLevel,
}: {
  patient: FamilyPatientJson;
  words: PatientWords;
  flags: FlagWords;
  headingLevel: 2 | 3;
}) {
  const titleId = `family-patient-${patient.id}`;
  const Title = headingLevel === 2 ? "h2" : "h3";
  const Subtitle = headingLevel === 2 ? "h3" : "h4";
  return (
    <section className="family-patient" aria-labelledby={titleId} data-patient-card={patient.id}>
      <Title className="family-patient__title" id={titleId}>
        {words.title}
      </Title>
      {words.shared !== null && <p className="family-patient__shared">{words.shared}</p>}
      <dl className="family-patient__facts">
        {words.age !== null && (
          <div className="family-patient__fact">
            <dt>{words.ageLabel}</dt>
            <dd>{words.age}</dd>
          </div>
        )}
        <div className="family-patient__fact">
          <dt>{words.sexLabel}</dt>
          <dd>{words.sex}</dd>
        </div>
      </dl>
      <ChronicFlags patientId={patient.id} owner={patient.owner} stored={patient.chronic_flags} words={flags} />
      {words.consentAt !== null && (
        <div className="family-patient__group">
          <Subtitle className="family-patient__subtitle">{words.consentTitle}</Subtitle>
          <p className="family-patient__note">{words.consentAt}</p>
        </div>
      )}
      <div className="family-patient__group">
        <Subtitle className="family-patient__subtitle">{words.remindersTitle}</Subtitle>
        <ReminderSwitch
          path={`/app/api/patients/${patient.id}/reminders`}
          stopped={patient.reminders !== "on"}
          canStop={patient.owner}
          words={words.reminders}
        />
      </div>
    </section>
  );
}
