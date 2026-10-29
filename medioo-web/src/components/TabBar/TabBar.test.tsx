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
  it("theHealthTabIsDrawnOnlyWhereItsRouteIsReachable", async () => {
    // F9 sends /app/health to Next (DR-49), so outside the preview the tab is a link to this build's page, third as in
    // the prototype, and marked when it is this page.
    expect(NEXT_PAGES).toContain("/app/health");
    expect(FLIPPED).toContain("/app/health");
    const plain = render(<TabBar lang="bn" preview={false} current="health" />);
    const after = links(plain.container);
    expect(after.map((a) => a.getAttribute("href"))).toEqual(["/app/home", "/app/timeline", "/app/health", "/app/family"]);
    expect(after[2]!.getAttribute("aria-current"), "the health tab, third as in the prototype, is this page").toBe("page");
    cleanup();
    // A preview page: the chat (W5), papers (W8), health (W9) and family (W10) pages are this build's, so their tabs stay
    // inside the preview.
    const preview = render(<TabBar lang="bn" preview current="chat" />);
    const hrefs = links(preview.container).map((a) => a.getAttribute("href"));
    expect(hrefs, "preview, after W9").toEqual(["/app/preview/home", "/app/preview/timeline", "/app/preview/health", "/app/preview/family"]);
    cleanup();

    // The other direction: where Caddy does not send /app/health to Next (a box without F9's block, as before F9), the
    // tab would be a link to Java's 404, so it is not drawn. The lists are the real reachable() and href() over FLIPPED
    // less /app/health (pathsFor), not a stand-in for them.
    vi.resetModules();
    vi.doMock("@/routing/paths", async (importOriginal) => {
      const real = await importOriginal<typeof import("@/routing/paths")>();
      const { FLIPPED: flipped } = await import("@/routing/flipped");
      const before = flipped.filter((p) => p !== "/app/health");
      return { ...real, ...real.pathsFor({ next: real.NEXT_PAGES, java: real.JAVA_PAGES, flipped: before }) };
    });
    const { TabBar: Unflipped } = await import("./TabBar");
    const earlier = render(<Unflipped lang="bn" preview={false} current="chat" />);
    expect(links(earlier.container).map((a) => a.getAttribute("href")), "before F9").toEqual([
      "/app/home",
      "/app/timeline",
      "/app/family",
    ]);
  });

  it("everyTabSaysItsWord", () => {
    for (const lang of ["bn", "en"] as const) {
      const { getByRole } = render(<TabBar lang={lang} preview={false} current="papers" />);
      const nav = getByRole("navigation", { name: t(lang, "tabs.label") });
      const names = [...nav.querySelectorAll("a")].map((a) => a.textContent);
      expect(names).toEqual([t(lang, "tab.chat"), t(lang, "tab.papers"), t(lang, "tab.health"), t(lang, "tab.family")]);
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
