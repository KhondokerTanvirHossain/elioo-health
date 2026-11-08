import type { ReactNode } from "react";
import type { FamilyJson } from "@/api/types";
import type { Theme } from "@/app/theme";
import { t, type Lang } from "@/copy";
import { initialOf } from "@/components/DesktopSidebar/DesktopSidebar";
import { ChronicFlags } from "@/components/Family/ChronicFlags";
import { FamilyAccount } from "@/components/Family/FamilyAccount";
import type { FamilyWords } from "@/components/Family/FamilyScreen";
import { ReminderSwitch } from "@/components/Family/ReminderSwitch";
import { ChooseLink } from "@/components/PatientChooser/ChooseLink";
import { DeskFamilyStyles } from "./desk-load";

export type DeskFamilyProps = {
  lang: Lang;
  /** `GET /app/api/family` as Java sent it. */
  family: FamilyJson;
  /** The family tab's words (family-words.ts), which its islands take as props. */
  words: FamilyWords;
  /** The person in view (the shell's patient), or null when nobody is. */
  activeId: string | null;
  /** Each person's paper count, from `me` (`documents`), by id. */
  documents: Record<string, number>;
  theme: Theme;
  signOutTo: string;
};

/** A group of desktop.html's settings column: its small heading over one rounded group. */
function Group({ name, title, children }: { name: string; title: string; children: ReactNode }) {
  const id = `desk-fam-${name}`;
  return (
    <section className="desk-fam__section" data-section={name} aria-labelledby={id}>
      <h2 className="desk-fam__gh" id={id}>
        {title}
      </h2>
      <div className="desk-fam__group">{children}</div>
    </section>
  );
}

function Tick() {
  return (
    <svg className="desk-fam__tick" viewBox="0 0 24 24" aria-hidden="true" focusable="false" data-tick>
      <path d="M5 12.5l4.5 4.5L19 7.5" />
    </svg>
  );
}

/**
 * The family and account page on the desktop layout (plan desktop-parity Phase 2, row F1; desktop.html
 * `settingsView()`): one column of grouped rows, under the account item's own name (`desk.account`).
 * - the people whose papers are kept, in Java's order, each a row with their initial, name, age and paper count (and
 *   `patients.shared` when shared with the viewer), a way to them that keeps this page (ChooseLink); the one in view is
 *   ticked and current;
 * - for the person in view: their long-term conditions, the family tab's own tick list and save (ChronicFlags: asked,
 *   never inferred; a share member's read only), and their reminders, desktop.html's line over the family tab's own
 *   stop (ReminderSwitch: stopped only after a confirm, never a switch that looks as if it could be turned back on);
 * - the account, the family tab's own (FamilyAccount: the number, language, theme, the family's reminder stop for an
 *   owner, sign out).
 * Not drawn, though desktop.html shows them: adding a person, the people who share the family and the invite (plan
 * Phase 3), and deleting every paper (no family delete, S7). Its stylesheet comes through its loader (DR-42).
 */
export function DeskFamily({ lang, family, words, activeId, documents, theme, signOutTo }: DeskFamilyProps) {
  const active = family.patients.find((p) => p.id === activeId) ?? null;
  const refused = t(lang, "app.err.notfound");
  const failed = t(lang, "app.err.generic");
  return (
    <div className="desk-fam">
      <DeskFamilyStyles />
      <h1 className="desk-fam__bar">{t(lang, "desk.account")}</h1>
      <div className="desk-fam__col">
        <Group name="people" title={words.patientsTitle}>
          {family.patients.length === 0 ? (
            <p className="desk-fam__none">{words.none}</p>
          ) : (
            <ul className="desk-fam__people">
              {family.patients.map((p) => (
                <li key={p.id}>
                  <ChooseLink
                    patientId={p.id}
                    refused={refused}
                    failed={failed}
                    className="desk-fam__cell desk-fam__person"
                    statusClassName="desk-fam__status"
                    current={p.id === activeId}
                  >
                    <span className="desk-fam__avatar" aria-hidden="true">
                      {initialOf(p.name)}
                    </span>
                    <span className="desk-fam__grow">
                      <span className="desk-fam__name">{p.name}</span>
                      <small className="desk-fam__sub">
                        {[
                          p.age === null ? null : t(lang, "desk.fam.age", p.age),
                          documents[p.id] === undefined ? null : t(lang, "desk.fam.papers", documents[p.id]!),
                          p.owner ? null : t(lang, "patients.shared"),
                        ]
                          .filter((x) => x !== null)
                          .join(" · ")}
                      </small>
                    </span>
                    {p.id === activeId && <Tick />}
                  </ChooseLink>
                </li>
              ))}
            </ul>
          )}
        </Group>
        {active !== null && (
          <>
            <Group name="flags" title={t(lang, "desk.fam.flags", active.name_possessive)}>
              <div className="desk-fam__pad">
                <ChronicFlags patientId={active.id} owner={active.owner} stored={active.chronic_flags} words={words.flags} />
              </div>
            </Group>
            <Group name="reminders" title={t(lang, "desk.fam.reminders")}>
              <div className="desk-fam__pad">
                <p className="desk-fam__row-title">
                  {t(lang, "desk.fam.reminders.wa")}
                  <small className="desk-fam__sub">{t(lang, "desk.fam.reminders.sub")}</small>
                </p>
                <ReminderSwitch
                  key={active.id}
                  path={`/app/api/patients/${active.id}/reminders`}
                  stopped={active.reminders !== "on"}
                  canStop={active.owner}
                  words={words.patients[active.id]!.reminders}
                />
              </div>
            </Group>
          </>
        )}
        <section className="desk-fam__section desk-fam__account" data-section="account">
          <FamilyAccount account={family.account} words={words.account} theme={theme} lang={lang} signOutTo={signOutTo} />
        </section>
      </div>
    </div>
  );
}
