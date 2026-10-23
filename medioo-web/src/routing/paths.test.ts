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
    expect(NEXT_PAGES).not.toContain("/app/timeline");
    expect(href("/app/timeline", true)).toBe("/app/timeline");
    expect(href("/app/documents/5e9edf6d-0a2a-4913-8675-9d07a4100003", true)).toBe(
      "/app/documents/5e9edf6d-0a2a-4913-8675-9d07a4100003",
    );
  });

  it("reachableIsJavaOrAFlipOrNextUnderThePreview", () => {
    expect(FLIPPED).not.toContain("/app/health");
    expect(JAVA_PAGES).not.toContain("/app/health");
    expect(reachable("/app/health", false)).toBe(false);
    expect(reachable("/app/health", true)).toBe(false);
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
