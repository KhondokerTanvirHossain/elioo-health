import type { ReactNode } from "react";
import { t, type Lang } from "@/copy";
import { SidebarToggle } from "@/components/AppShell/SidebarToggle";
import { NowBarStyles } from "@/components/safety/NowBanner/desk-load";
import { ColumnResizer, DesktopShellStyles } from "./desk-load";

/** One desktop shell per page, so one sidebar: the id the toolbar's button names in aria-controls (AppShell's differs). */
const SIDEBAR_ID = "desktop-shell-sidebar";

/**
 * The desktop layout (plan desktop-parity Task 5; desktop.html `.app`, ruling P19), from 900 px and only where
 * desktopLayout(ctx) says so (shell.tsx; below 900 px today's phone shell stands in its place):
 * - `sidebar` (DesktopSidebar) in a column of its own from 1200 px, at --sidebar-w; from 900 to 1199 px behind the
 *   toolbar's button (SidebarToggle, reused from AppShell), lying over the page at its own width when shown;
 * - the content region beside it: `bar` (the NOW bar) across the two content columns and never over the sidebar, then
 *   `paper` (the paper column, filling the rest) and `talk` (the conversation, at --conversation-w), with the divider
 *   between them (ColumnResizer: the family drags it to give either more room, PO 2026-11-07);
 * - `panel`, a paper's details, lies over the conversation column (P19: `desk.panel.open` opens it over the conversation);
 * - no `talk` at all (undefined, never null): a screen of one column that lays itself out, the calendar (plan
 *   desktop-calendar; desktop.html `calView()`, its month and its day panel), so no conversation and no divider.
 * There is no footer disclaimer here (X1): the conversation's header says "not a doctor" (Task 7), and the NOW block
 * keeps its own line. `preview` is carried for the record of which page this is: the slots hold their own links.
 * Its stylesheet comes through its loader (desk-load.tsx), so only a page that renders this shell links it (DR-42); the
 * bar slot brings the NOW bar's (NowBanner's desk-load.tsx), since NowBanner itself is on every page as the block.
 */
export function DesktopShell({
  lang,
  sidebar,
  bar,
  paper,
  talk,
  panel,
}: {
  lang: Lang;
  preview: boolean;
  sidebar: ReactNode;
  bar?: ReactNode;
  paper: ReactNode;
  talk?: ReactNode;
  panel?: ReactNode;
}) {
  const menu = t(lang, "tabs.label");
  const one = talk === undefined;
  return (
    <div className="desktop-shell">
      <DesktopShellStyles />
      <aside className="desktop-shell__sidebar" id={SIDEBAR_ID} aria-label={menu}>
        {sidebar}
      </aside>
      <main className="desktop-shell__content">
        {bar !== undefined && (
          <div className="desktop-shell__bar">
            <NowBarStyles />
            {bar}
          </div>
        )}
        <div className={one ? "desktop-shell__columns desktop-shell__columns--one" : "desktop-shell__columns"}>
          <section className="desktop-shell__paper">
            <SidebarToggle label={menu} controls={SIDEBAR_ID} className="desktop-shell__menu" />
            {paper}
          </section>
          {!one && (
            <>
              <ColumnResizer label={t(lang, "desk.resize")} />
              <section className="desktop-shell__talk">
                {talk}
                {panel !== undefined && <div className="desktop-shell__panel">{panel}</div>}
              </section>
            </>
          )}
        </div>
      </main>
    </div>
  );
}
