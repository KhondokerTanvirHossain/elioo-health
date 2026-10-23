import type { FamilyAccountJson } from "@/api/types";
import type { Theme } from "@/app/theme";
import type { Lang } from "@/copy";
import { SignOut } from "@/components/AppShell/SignOut";
import { LanguageSwitch, type LanguageWords } from "./LanguageSwitch";
import { ReminderSwitch, type ReminderWords } from "./ReminderSwitch";
import { ThemeSwitch, type ThemeWords } from "./ThemeSwitch";
import "./Family.css";

/** The account section's words, resolved by the server (family-words.ts). */
export type AccountWords = {
  /** `family.account.title`, `family.account.name`, `family.account.number`. */
  title: string;
  name: string;
  number: string;
  /** `app.logout`, and `app.err.generic` for a sign-out that did not go through. */
  signOut: string;
  failed: string;
  theme: ThemeWords;
  language: LanguageWords;
  /**
   * The family's own reminder stop (`profile.nudges`, `optout.family`, `optout.done.family`), or null when it is hidden:
   * for a viewer who owns no patient it changes nothing they receive (S5, Q11).
   */
  familyReminders: { title: string; words: ReminderWords } | null;
};

/**
 * The viewer's own account (plan W10; phone.html scene `family`, the account group): the name, the WhatsApp number
 * (shown here and on no other page: GET /app/api/family is the one body that carries it), the language and the theme,
 * the family's reminder stop for an owner (S5), and sign out (the shell's own SignOut, one behaviour). Settings has
 * theme and language only: sounds land with W12 (S6), and there is no family delete (S7, Q9).
 */
export function FamilyAccount({
  account,
  words,
  theme,
  lang,
  signOutTo,
}: {
  account: FamilyAccountJson;
  words: AccountWords;
  theme: Theme;
  lang: Lang;
  signOutTo: string;
}) {
  const name = account.owner_name?.trim() ? account.owner_name : null;
  return (
    <section className="family-account" aria-labelledby="family-account-title">
      <h2 className="family-account__title" id="family-account-title">
        {words.title}
      </h2>
      <dl className="family-account__facts">
        {name !== null && (
          <div className="family-account__fact">
            <dt>{words.name}</dt>
            <dd>{name}</dd>
          </div>
        )}
        <div className="family-account__fact">
          <dt>{words.number}</dt>
          <dd className="family-account__number" dir="ltr">
            {account.number}
          </dd>
        </div>
      </dl>
      <LanguageSwitch current={lang} words={words.language} />
      <ThemeSwitch current={theme} words={words.theme} />
      {words.familyReminders !== null && (
        <div className="family-account__group" data-family-reminders={account.reminders_stopped ? "stopped" : "on"}>
          <h3 className="family-account__subtitle">{words.familyReminders.title}</h3>
          <ReminderSwitch
            path="/app/api/family/reminders"
            stopped={account.reminders_stopped}
            canStop
            words={words.familyReminders.words}
            reloadAfter
          />
        </div>
      )}
      <div className="family-account__sign-out">
        <SignOut label={words.signOut} failed={words.failed} to={signOutTo} />
      </div>
    </section>
  );
}
