import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { t } from "@/copy";
import { FLIPPED } from "@/routing/flipped";
import { NEXT_PAGES } from "@/routing/paths";
import { TabBar } from "./TabBar";

afterEach(() => {
  cleanup();
  vi.doUnmock("@/routing/paths");
  vi.resetModules();
});

const links = (container: HTMLElement) => [...container.querySelectorAll("a")];

describe("TabBar", () => {
  it("theHealthTabIsAbsentUntilItsRouteIsReachable", async () => {
    // Today: Caddy sends /app/health nowhere and this build serves no such page, so the health tab is not a link to a 404.
    expect(FLIPPED).not.toContain("/app/health");
    expect(NEXT_PAGES).not.toContain("/app/health");
    const plain = render(<TabBar lang="bn" preview={false} current="chat" />);
    expect(links(plain.container).map((a) => a.getAttribute("href"))).toEqual(["/app/home", "/app/timeline", "/app/family"]);
    cleanup();
    const preview = render(<TabBar lang="bn" preview current="chat" />);
    // The chat page is this build's (W5): on a preview page its tab stays inside the preview.
    // W10: this build serves /app/family, so on a preview page the family tab stays inside the preview.
    expect(NEXT_PAGES).toContain("/app/family");
    expect(links(preview.container).map((a) => a.getAttribute("href")), "preview, before W9").toEqual([
      "/app/preview/home",
      "/app/timeline",
      "/app/preview/family",
    ]);
    cleanup();

    // Once this build serves /app/health (W9), a preview page shows the tab, inside the preview. The lists are the
    // real reachable() and href() over NEXT_PAGES plus /app/health (pathsFor), not a stand-in for them.
    vi.resetModules();
    vi.doMock("@/routing/paths", async (importOriginal) => {
      const real = await importOriginal<typeof import("@/routing/paths")>();
      const { FLIPPED: flipped } = await import("@/routing/flipped");
      const next = [...real.NEXT_PAGES, "/app/health"];
      return { ...real, NEXT_PAGES: next, ...real.pathsFor({ next, java: real.JAVA_PAGES, flipped }) };
    });
    const { TabBar: WithHealth } = await import("./TabBar");
    const later = render(<WithHealth lang="bn" preview current="chat" />);
    const hrefs = links(later.container).map((a) => a.getAttribute("href"));
    expect(hrefs).toHaveLength(4);
    expect(hrefs[2], "the health tab, third as in the prototype").toBe("/app/preview/health");
    cleanup();
    // ...and still not outside the preview, where Caddy would send /app/health to Java's 404.
    const outside = render(<WithHealth lang="bn" preview={false} current="chat" />);
    expect(links(outside.container).map((a) => a.getAttribute("href"))).toEqual(["/app/home", "/app/timeline", "/app/family"]);
  });

  it("everyTabSaysItsWord", () => {
    for (const lang of ["bn", "en"] as const) {
      const { getByRole } = render(<TabBar lang={lang} preview={false} current="papers" />);
      const nav = getByRole("navigation", { name: t(lang, "tabs.label") });
      const names = [...nav.querySelectorAll("a")].map((a) => a.textContent);
      expect(names).toEqual([t(lang, "tab.chat"), t(lang, "tab.papers"), t(lang, "tab.family")]);
      cleanup();
    }
  });

  it("theCurrentTabIsMarkedAndNoOther", () => {
    const { container } = render(<TabBar lang="bn" preview={false} current="family" />);
    const current = links(container).filter((a) => a.getAttribute("aria-current") === "page");
    expect(current.map((a) => a.getAttribute("href"))).toEqual(["/app/family"]);
    // A page with no tab of its own (a paper) marks none.
    cleanup();
    const none = render(<TabBar lang="bn" preview={false} current={null} />);
    expect(links(none.container).filter((a) => a.hasAttribute("aria-current"))).toEqual([]);
  });

  it("aTabIsALinkTheBrowserFollowsNotAPrefetch", () => {
    // Plain links: a tab whose page is Java's must be a real navigation, and nothing here prefetches a page read.
    const { container } = render(<TabBar lang="bn" preview={false} current="chat" />);
    for (const a of links(container)) {
      expect(a.getAttribute("href")!.startsWith("/app/")).toBe(true);
      expect(a.hasAttribute("data-prefetch")).toBe(false);
    }
  });
});
