import copy from "../../../../copy/probe.bn.json";

/**
 * The DR-42 budget probe: the smallest page that is still a real Medioo page — the phone shell (header, one paragraph,
 * the tab bar), both Noto fonts and Bangla body text, rendered by the App Router with its client runtime. The first-load
 * bytes measured here are the floor every routed page starts from (docs/superpowers/specs/2026-10-21-dr42-budget-measurement.md).
 *
 * The tabs are plain links on purpose: next/link would prefetch routes that do not exist yet and add RSC requests that
 * no real cold load makes.
 */
export default function ProbePage() {
  return (
    <div className="shell">
      <header className="shell-header">
        <span className="wordmark" lang="en">
          Medioo
        </span>
      </header>
      <main className="shell-main">
        <h1>{copy["landing.h1"]}</h1>
        <p>{copy["landing.sub"]}</p>
      </main>
      <nav className="tabs" aria-label={copy["tabs.label"]}>
        <a href="/app/probe" aria-current="page">
          {copy["tab.chat"]}
        </a>
        <a href="/app/probe">{copy["tab.papers"]}</a>
        <a href="/app/probe">{copy["tab.family"]}</a>
      </nav>
    </div>
  );
}
