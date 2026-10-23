import { describe, expect, it } from "vitest";
import { pathsFor } from "@/routing/paths";
import { closeHrefFrom } from "./close-href";

/*
 * Where the add sheet's close (sheet.close) goes: the `back` the opening page carried, as Java's own sheet did
 * (UploadUiHandler over LocalPath), when it is a page of this app that answers; otherwise the chat tab. Both directions:
 * a Java-drawn back closes to that page, and every other origin, and a page nobody serves, closes to the chat tab.
 */
const PAPER = "/app/documents/5e9edf6d-0a2a-4913-8675-9d07a4100003";
const JAVA = ["/app/home", "/app/timeline", "/app/documents/{id}", "/app/add"];
const today = pathsFor({ next: ["/app", "/app/verify", "/app/add"], java: JAVA, flipped: ["/app/add"] });
const close = (back: string | null, preview = false) => closeHrefFrom(back, preview, today);

describe("closeHrefFrom", () => {
  it("closes to the paper a Java page sent it from (DocumentPages' anotherPaper, back=/app/documents/{id})", () => {
    expect(close(PAPER)).toBe(PAPER);
  });

  it("closes to the chat or papers tab a Java tab sent it from (ChatView.addHref, AppTabs.addHref)", () => {
    expect(close("/app/home")).toBe("/app/home");
    expect(close("/app/timeline?patient=5e9edf6d-0a2a-4913-8675-9d07a4100002")).toBe("/app/timeline?patient=5e9edf6d-0a2a-4913-8675-9d07a4100002");
  });

  it.each(["//evil.example", "https://evil.example/app/home", "/\\evil.example", "/%2F%2Fevil.example", "javascript:alert(1)", "/app/../api/v1/baymax/me"])(
    "another origin, or a path out of /app, closes to the chat tab: %s",
    (back) => {
      expect(close(back)).toBe("/app/home");
    },
  );

  it("no back closes to the chat tab", () => {
    expect(close(null)).toBe("/app/home");
  });

  it("a page of this app that nobody serves closes to the chat tab, never to a 404", () => {
    expect(close("/app/nowhere")).toBe("/app/home");
  });

  it("on the preview, a back Next serves stays inside the preview; a Java page is linked as it is", () => {
    const later = pathsFor({ next: ["/app", "/app/verify", "/app/add", "/app/home"], java: JAVA, flipped: [] });
    expect(closeHrefFrom("/app/home", true, later)).toBe("/app/preview/home");
    expect(closeHrefFrom(null, true, later)).toBe("/app/preview/home");
    expect(closeHrefFrom(PAPER, true, later)).toBe(PAPER);
  });
});
