import type { ReactNode } from "react";
import type { MeJson, MePatient } from "@/api/types";
import type { Lang } from "@/copy";
import { MioAvatar } from "@/components/MioAvatar/MioAvatar";
import { PatientSwitcher, type SwitcherWords } from "@/components/PatientSwitcher/PatientSwitcher";
import { TabBar, type Tab } from "@/components/TabBar/TabBar";
import { Wordmark } from "@/components/Wordmark/Wordmark";
import { href } from "@/routing/paths";
import { SidebarToggle } from "./SidebarToggle";
import { SignOut } from "./SignOut";
import "./AppShell.css";

/** One shell per page, so one sidebar: the id the toolbar's button names in aria-controls. */
const SIDEBAR_ID = "app-shell-sidebar";

/**
 * Every word the shell says, resolved on the server (src/app/app/(signed-in)/shell-words.ts): the islands inside it (the
 * switcher, the sidebar's button, sign out) take theirs as props and ship no copy.
 */
export type ShellWords = {
  /** `tabs.label`: the sidebar's name, and the toolbar button that shows it. */
  menu: string;
  /** `app.logout`. */
  signOut: string;
  /** `app.err.generic`: a sign-out that did not go through. */
  failed: string;
  /** `disclaimer`: the not-a-doctor line every signed-in page carries under its main (Java's AppShell footer). */
  disclaimer: string;
  /** The switcher's words; null when no patient is shown (the chooser, or a family with none). */
  switcher: SwitcherWords | null;
};

/**
 * The shell every signed-in page renders (plan W4; desktop.html scene `app`, phone.html scene `chat`), from what
 * GET /app/api/me said and nothing else:
 * - `banner` (the global NOW banner, W5) above everything, at every width;
 * - below 900 px: the toolbar (Mio and the switcher), the page's `main` with the disclaimer under it, then the bottom
 *   tab bar (the disclaimer is under `main` at every width: the not-a-doctor line on every page that shows a result);
 * - from 900 to 1199 px: the sidebar (the wordmark, the tabs as a rail, whose account this is, sign out) hides behind
 *   the toolbar's button (SidebarToggle), and `aside` sits beside main;
 * - from 1200 px: three columns, sidebar 256 · main · aside 400 (the tokens --sidebar-w, --conversation-w).
 * `active` is the patient whose papers are shown (active-patient.ts decided it, never this file): with none, the
 * toolbar names nobody and the page's main is the chooser. `words` are shell-words.ts's. Every link stays inside the preview on a preview page.
 */
export function AppShell({
  lang,
  preview,
  me,
  active,
  words,
  tab,
  main,
  aside,
  banner,
}: {
  lang: Lang;
  preview: boolean;
  me: MeJson;
  active: MePatient | null;
  words: ShellWords;
  tab: Tab | null;
  main: ReactNode;
  aside?: ReactNode;
  banner?: ReactNode;
}) {
  const classes = ["app-shell", aside !== undefined ? "app-shell--aside" : null].filter(Boolean).join(" ");
  return (
    <div className={classes}>
      {banner !== undefined && <div className="app-shell__banner">{banner}</div>}
      <div className="app-shell__frame">
        <aside className="app-shell__sidebar" id={SIDEBAR_ID} aria-label={words.menu}>
          <div className="app-shell__brand">
            <Wordmark lang={lang} />
          </div>
          <TabBar lang={lang} preview={preview} current={tab} rail />
          <div className="app-shell__account">
            <p className="app-shell__owner">{me.family.owner_name}</p>
            <SignOut label={words.signOut} failed={words.failed} to={href("/app/", preview)} />
          </div>
        </aside>
        <div className="app-shell__column">
          <header className="app-shell__toolbar">
            <MioAvatar size={32} still />
            {active !== null && words.switcher !== null && (
              <PatientSwitcher words={words.switcher} current={active.id} preview={preview} />
            )}
            <SidebarToggle label={words.menu} controls={SIDEBAR_ID} />
          </header>
          <main className="app-shell__main">
            {main}
            <footer className="app-shell__foot">{words.disclaimer}</footer>
          </main>
        </div>
        {aside !== undefined && <aside className="app-shell__aside">{aside}</aside>}
      </div>
      <div className="app-shell__tabs">
        <TabBar lang={lang} preview={preview} current={tab} />
      </div>
    </div>
  );
}
