import "server-only";
import type { FamilyJson, FamilyPatientJson } from "@/api/types";
import { formatDate, t, type CopyKey, type Lang } from "@/copy";
import type { PatientWords } from "@/components/Family/PatientCard";
import type { FamilyWords } from "@/components/Family/FamilyScreen";

/*
 * The family tab's words, resolved on the server (plan W10) from GET /app/api/family: the flags, the reminder stops,
 * the theme, the language and sign out are client islands, which take their words as props and import no copy, so the
 * browser receives only these strings, inside the page's RSC payload. A key Java sent (`sex_key`, a flag's
 * `label_key`) is resolved here; one the copy does not hold falls back to what is true without it (the sex unsaid,
 * the flag's own text), never to a guess.
 */

/** The copy for a key Java sent, or `fallback` when the copy has no such key. */
function sent(lang: Lang, key: string, fallback: string): string {
  try {
    return t(lang, key as CopyKey);
  } catch {
    return fallback;
  }
}

function patientWords(lang: Lang, p: FamilyPatientJson): PatientWords {
  const poss = p.name_possessive;
  return {
    title: t(lang, "family.info.title", poss),
    shared: p.owner ? null : t(lang, "patients.shared"),
    ageLabel: t(lang, "family.info.age"),
    age: p.age === null ? null : t(lang, "patients.years", p.age),
    sexLabel: t(lang, "family.info.sex"),
    sex: sent(lang, p.sex_key, t(lang, "sex.UNKNOWN")),
    consentTitle: t(lang, "family.consent.title"),
    consentAt: p.consent_date === null ? null : t(lang, "family.consent.at", formatDate(lang, p.consent_date)),
    remindersTitle: t(lang, "profile.nudges"),
    reminders: {
      on: t(lang, "profile.nudges.on", poss),
      off: t(lang, "profile.nudges.off", poss),
      stop: t(lang, "profile.nudges.stop", poss),
      refused: t(lang, "app.err.forbidden"),
      failed: t(lang, "app.err.generic"),
      confirm: confirmWords(lang, "optout.patient"),
    },
  };
}

/**
 * The stop's confirm, as Java's OptOutPages asks it (W10 review: nothing is sent on the first press): `optout.q`, the
 * stop itself (`optout.patient` or `optout.family`), `optout.keep`, and the sheet's close.
 */
function confirmWords(lang: Lang, yes: "optout.patient" | "optout.family") {
  return { title: t(lang, "optout.q"), yes: t(lang, yes), keep: t(lang, "optout.keep"), close: t(lang, "sheet.close") };
}

export function familyWords(lang: Lang, family: FamilyJson): FamilyWords {
  const labels = Object.fromEntries(family.flags_offered.map((o) => [o.label_key, sent(lang, o.label_key, o.id)]));
  // S5 (PO 2026-10-22, the plan's default): the family's stop only for a viewer who owns a patient; for a share member
  // who owns none it changes nothing they receive (the nudge engine never reads share_member, Q11).
  const ownsSomeone = family.patients.some((p) => p.owner);
  return {
    heading: t(lang, "tab.family"),
    patientsTitle: t(lang, "family.patients.title"),
    none: t(lang, "patients.none"),
    flags: {
      title: t(lang, "profile.flags"),
      readonlyTitle: t(lang, "flags.readonly.title"),
      none: t(lang, "flags.none"),
      hint: t(lang, "family.flags.hint"),
      other: t(lang, "flag.other"),
      save: t(lang, "profile.save"),
      saved: t(lang, "profile.saved"),
      refused: t(lang, "app.err.forbidden"),
      failed: t(lang, "app.err.generic"),
      offered: family.flags_offered.map((o) => ({ id: o.id, label: labels[o.label_key]! })),
      labels,
    },
    patients: Object.fromEntries(family.patients.map((p) => [p.id, patientWords(lang, p)])),
    account: {
      title: t(lang, "family.account.title"),
      name: t(lang, "family.account.name"),
      number: t(lang, "family.account.number"),
      signOut: t(lang, "app.logout"),
      failed: t(lang, "app.err.generic"),
      theme: {
        title: t(lang, "theme.title"),
        auto: t(lang, "theme.auto"),
        light: t(lang, "theme.light"),
        dark: t(lang, "theme.dark"),
        failed: t(lang, "app.err.generic"),
      },
      language: {
        title: t(lang, "family.account.lang"),
        bn: t(lang, "lang.bn"),
        en: t(lang, "lang.en"),
        failed: t(lang, "app.err.generic"),
      },
      familyReminders: ownsSomeone
        ? {
            title: t(lang, "profile.nudges"),
            words: {
              on: null,
              off: t(lang, "optout.done.family"),
              stop: t(lang, "optout.family"),
              refused: t(lang, "app.err.forbidden"),
              failed: t(lang, "app.err.generic"),
              confirm: confirmWords(lang, "optout.family"),
            },
          }
        : null,
    },
  };
}
