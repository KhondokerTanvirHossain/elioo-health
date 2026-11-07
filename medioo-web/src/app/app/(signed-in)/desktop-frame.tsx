import "server-only";
import { javaGet, signedIn } from "@/api/java";
import type { FollowUpsJson } from "@/api/types";
import { DesktopShell } from "@/components/DesktopShell/DesktopShell";
import { DesktopSidebar, nextVisitOf, type DesktopPlace } from "@/components/DesktopSidebar/DesktopSidebar";
import type { Tab } from "@/components/TabBar/TabBar";
import type { DesktopFrame } from "./shell";

/** The sidebar's place for a page's tab: the chat is the talk-and-papers screen, the papers tab the calendar. */
const PLACE: Record<Tab, DesktopPlace> = { chat: "talk", papers: "calendar", health: "labs", family: "settings" };

/** Today on the patient's calendar (Dhaka), ISO: what the sidebar counts the doctor's next date from. */
const DHAKA_DAY = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Dhaka", year: "numeric", month: "2-digit", day: "2-digit" });

/**
 * The desktop layout's frame (plan desktop-parity Tasks 5 and 8): DesktopShell around the page's desktop columns, with
 * DesktopSidebar (the patient's next visit read from their follow-ups) and the NOW bar. signedInPage calls it when
 * desktopLayout(ctx) says so, for a page that gives `desktop` slots.
 *
 * It is its own module, imported by those pages alone (home, a paper) and only as a type by shell.tsx, so that a page
 * which never renders the desktop layout does not carry it at all: not DesktopShell, not the sidebar, not their loaders
 * (desk-load.tsx) nor next/dynamic's runtime (DR-42; owner decision 2026-11-07).
 */
export const desktopFrame: DesktopFrame = async ({ lang, preview, here, me, patient, tab, bar, paper, talk, panel }) => {
  const followUps =
    patient === null
      ? null
      : signedIn(await javaGet<FollowUpsJson>(`/app/api/patients/${encodeURIComponent(patient.id)}/follow-ups`), here);
  return (
    <DesktopShell
      lang={lang}
      preview={preview}
      sidebar={
        <DesktopSidebar
          lang={lang}
          preview={preview}
          me={me}
          active={patient}
          current={tab === null ? null : PLACE[tab]}
          nextVisit={followUps === null ? null : nextVisitOf(followUps.items)}
          today={DHAKA_DAY.format(new Date())}
        />
      }
      bar={bar}
      paper={paper}
      talk={talk}
      panel={panel}
    />
  );
};
