import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { FLIPPED } from "./flipped";
import { basicSignInPath, href, JAVA_PAGES, NEXT_PAGES, previewPath, reachable, signInPath } from "./paths";

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
    // W6: a paper is a Next page, so a preview page links to it inside the preview; its Java sub-paths (the crop and
    // page images, the status, the delete) are not pages of this build and stay on Java.
    expect(href("/app/documents/5e9edf6d-0a2a-4913-8675-9d07a4100003", true)).toBe(
      "/app/preview/documents/5e9edf6d-0a2a-4913-8675-9d07a4100003",
    );
    expect(href("/app/documents/5e9edf6d-0a2a-4913-8675-9d07a4100003", false)).toBe(
      "/app/documents/5e9edf6d-0a2a-4913-8675-9d07a4100003",
    );
    for (const sub of ["/image?key=x", "/page/1", "/status", "/delete"]) {
      expect(href(`/app/documents/5e9edf6d-0a2a-4913-8675-9d07a4100003${sub}`, true)).toBe(
        `/app/documents/5e9edf6d-0a2a-4913-8675-9d07a4100003${sub}`,
      );
    }
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
});
