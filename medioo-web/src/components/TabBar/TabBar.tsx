import Link from "next/link";
import type { ReactNode } from "react";
import { t, type CopyKey, type Lang } from "@/copy";
import { href, reachable, servedByNext } from "@/routing/paths";
import "./TabBar.css";

export type Tab = "chat" | "papers" | "health" | "family";

// The four tabs, in the prototype's order (phone.html scene `chat`): chat, papers, health, family.
const TABS: { tab: Tab; path: string; key: CopyKey; icon: ReactNode }[] = [
  {
    tab: "chat",
    path: "/app/home",
    key: "tab.chat",
    icon: <path d="M4 5.5h16v10.5H9.5L5 19.5V16H4z" />,
  },
  {
    tab: "papers",
    path: "/app/timeline",
    key: "tab.papers",
    icon: <path d="M4.5 6.5h15v13h-15zM4.5 10.5h15M8.5 4v4M15.5 4v4" />,
  },
  {
    tab: "health",
    path: "/app/health",
    key: "tab.health",
    icon: <path d="M12 19.5s-7.5-4.6-7.5-10A4.2 4.2 0 0 1 12 7a4.2 4.2 0 0 1 7.5 2.5c0 5.4-7.5 10-7.5 10z" />,
  },
  {
    tab: "family",
    path: "/app/family",
    key: "tab.family",
    icon: <path d="M9 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM3.5 19.5a5.5 5.5 0 0 1 11 0M16 11a2.5 2.5 0 1 0 0-5M17.5 14.2a4.8 4.8 0 0 1 3 5.3" />,
  },
];

/**
 * The tabs (plan W4): chat `/app/home`, papers `/app/timeline`, health `/app/health`, family `/app/family`. A tab is
 * rendered only when its page answers for this viewer (`reachable`: Java serves it, Caddy sends it to Next, or Next
 * serves it under the preview), so the health tab shows under the preview (W9's page) and outside it only once F9
 * sends /app/health to Next; never a link to Java's 404. A preview
 * page links inside the preview (`href`). `current` is the page's own tab (null on a page that is none, a paper),
 * marked `aria-current="page"`.
 *
 * A tab whose page is Java's is a plain `<a>`, a real navigation. A tab whose page Next answers for this viewer (under
 * the preview, or once flipped: `servedByNext`) is a soft navigation (next/link, never prefetched), so the shell and
 * the NOW banner are rendered again from Java on the way there (plan W5). Nothing here may prefetch a read.
 * The same nav is the phone's bottom bar and, from 900 px, the sidebar's list (`rail`); AppShell shows one at a time.
 */
export function TabBar({ lang, preview, current, rail }: { lang: Lang; preview: boolean; current: Tab | null; rail?: boolean }) {
  return (
    <nav className={rail ? "tab-bar tab-bar--rail" : "tab-bar"} aria-label={t(lang, "tabs.label")}>
      <ul className="tab-bar__list">
        {TABS.filter(({ path }) => reachable(path, preview)).map(({ tab, path, key, icon }) => {
          const inside = (
            <>
              <svg className="tab-bar__icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
                {icon}
              </svg>
              <span className="tab-bar__label">{t(lang, key)}</span>
            </>
          );
          const at = tab === current ? "page" : undefined;
          return (
            <li key={tab} className="tab-bar__item">
              {servedByNext(path, preview) ? (
                <Link className="tab-bar__link" href={href(path, preview)} aria-current={at} prefetch={false}>
                  {inside}
                </Link>
              ) : (
                <a className="tab-bar__link" href={href(path, preview)} aria-current={at}>
                  {inside}
                </a>
              )}
            </li>
          );
        })}
      </ul>
    </nav>
  );
}
