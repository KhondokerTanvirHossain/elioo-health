import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { FLIPPED } from "./flipped";
import { basicSignInPath, href, JAVA_PAGES, NEXT_PAGES, pathsFor, previewPath, reachable, servedByNext, signInPath } from "./paths";

const web = join(__dirname, "..", "..");

describe("paths", () => {
  it("signInPathEncodesTheWholeHeldPlace", () => {
    expect(signInPath("/app/documents/x?all=1")).toBe("/app/?next=%2Fapp%2Fdocuments%2Fx%3Fall%3D1");
  });

  it("basicSignInPathIsTheSeamJavasChainStartsFrom", () => {
    // W3-api's BasicSignInPagesTest starts its no-JS chain from this same href; the file is the seam between the two.
    const seam = JSON.parse(readFileSync(join(web, "fixtures", "seams", "noscript-href.json"), "utf8")) as {
      held: string;
      href: string;
    };
    expect(seam.href.startsWith("/app/basic?next=%2F"), "the seam is a held place under /app/basic").toBe(true);
    expect(basicSignInPath(seam.held)).toBe(seam.href);
  });

  it("previewPathKeepsTheRestUnderThePreviewPrefix", () => {
    expect(previewPath("/app/home")).toBe("/app/preview/home");
    expect(previewPath("/app")).toBe("/app/preview");
    expect(previewPath("/app/")).toBe("/app/preview/");
  });

  it("hrefStaysInsideThePreviewOnlyForANextPage", () => {
    expect(NEXT_PAGES).toContain("/app/verify");
    expect(href("/app/verify", true)).toBe("/app/preview/verify");
    expect(href("/app/verify", false)).toBe("/app/verify");
    expect(href("/app/", true)).toBe("/app/preview/");
    // A page this build does not serve is Java's: the preview prefix would land on Next's 404.
    expect(NEXT_PAGES).not.toContain("/app/nudges/opt-out");
    expect(href("/app/nudges/opt-out", true)).toBe("/app/nudges/opt-out");
    // The papers page (W8) is a Next page: a preview page's link to it, query and all, stays inside the preview.
    expect(NEXT_PAGES).toContain("/app/timeline");
    expect(href("/app/timeline?patient=x&type=lab", true)).toBe("/app/preview/timeline?patient=x&type=lab");
    expect(href("/app/timeline?patient=x", false)).toBe("/app/timeline?patient=x");
    expect(href("/app/documents/5e9edf6d-0a2a-4913-8675-9d07a4100003", true)).toBe(
      "/app/documents/5e9edf6d-0a2a-4913-8675-9d07a4100003",
    );
  });

  it("aDynamicNextPageMatchesARealIdInsideThePreview", () => {
    // W10's profile page is the first NEXT_PAGES entry with a `{id}` segment: a real id matches it, an empty one does not.
    const id = "b1813d1a-cb12-36de-9c8c-a15d9a6e3768";
    expect(NEXT_PAGES).toContain("/app/patients/{id}/profile");
    expect(href(`/app/patients/${id}/profile`, true)).toBe(`/app/preview/patients/${id}/profile`);
    expect(href(`/app/patients/${id}/profile`, false)).toBe(`/app/patients/${id}/profile`);
    expect(href("/app/patients//profile", true)).toBe("/app/patients//profile");
    expect(href("/app/family", true)).toBe("/app/preview/family");
  });

  it("reachableIsJavaOrAFlipOrNextUnderThePreview", () => {
    expect(JAVA_PAGES).not.toContain("/app/health");
    // W9: this build serves /app/health; Java never did. F9 sends it to Next, so it is reachable with or without the
    // preview. Before F9 it was reachable under the preview only (TabBar.test.tsx keeps that direction, over the same
    // rule with /app/health left out of FLIPPED).
    expect(NEXT_PAGES).toContain("/app/health");
    expect(FLIPPED).toContain("/app/health");
    expect(reachable("/app/health", false)).toBe(true);
    expect(reachable("/app/health", true)).toBe(true);
    // A page neither server answers is reachable nowhere, flip or no flip.
    expect(reachable("/app/healthy", false), "a page no server answers").toBe(false);
    expect(reachable("/app/healthy", true), "a page this build does not serve").toBe(false);
    // Java serves the paper, so it is reachable whatever the preview says; a pattern matches a real id.
    expect(reachable("/app/documents/5e9edf6d-0a2a-4913-8675-9d07a4100003", false)).toBe(true);
    expect(reachable("/app/documents/5e9edf6d-0a2a-4913-8675-9d07a4100003/all", false)).toBe(true);
    expect(reachable("/app/documents/5e9edf6d-0a2a-4913-8675-9d07a4100003/nope", false)).toBe(false);
  });

  it("onlyAPageNextAnswersForThisViewerNavigatesSoftly", () => {
    // A soft navigation asks the server that answers the link for the next page's payload: a Java page must be a real
    // navigation, and a Next page is Next's only under the preview or once Caddy sends it there (plan W5).
    const lists = { next: ["/app/home"], java: ["/app/home", "/app/timeline"], flipped: [] as string[] };
    const before = pathsFor(lists);
    expect(before.servedByNext("/app/home", true)).toBe(true);
    expect(before.servedByNext("/app/home?patient=x", true)).toBe(true);
    expect(before.servedByNext("/app/home", false), "not flipped: Caddy still sends it to Java").toBe(false);
    expect(before.servedByNext("/app/timeline", true), "a page this build does not serve").toBe(false);
    const after = pathsFor({ ...lists, flipped: ["/app/home"] });
    expect(after.servedByNext("/app/home", false), "flipped: Next answers it for everyone").toBe(true);
    expect(after.servedByNext("/app/timeline", false)).toBe(false);
    // This build: the chat page is Next's under the preview and, until F5, Java's otherwise.
    expect(NEXT_PAGES).toContain("/app/home");
    expect(servedByNext("/app/home", true)).toBe(true);
    expect(servedByNext("/app/home", false)).toBe(FLIPPED.includes("/app/home"));
  });
});
