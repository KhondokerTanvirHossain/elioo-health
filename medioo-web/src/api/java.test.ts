import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

/*
 * src/api/java.ts, the one module that reads Java from the Next server. The incoming request carries more than the
 * cookie (the preview mark, the path, an Authorization and an Origin), so forwarding any header but `cookie` shows up
 * in the stubbed fetch's init.
 */

const incoming = new Map<string, string>();
vi.mock("next/headers", () => ({ headers: async () => new Headers([...incoming]) }));
vi.mock("next/navigation", () => ({
  redirect: vi.fn((to: string) => {
    throw new Error(`NEXT_REDIRECT ${to}`);
  }),
  notFound: vi.fn(() => {
    throw new Error("NEXT_NOT_FOUND");
  }),
}));

const { javaGet, signedIn } = await import("./java");
const navigation = await import("next/navigation");

const PAPER = "/app/api/documents/5e9edf6d-0a2a-4913-8675-9d07a4100003";
type Call = { url: string; init: RequestInit };
let calls: Call[] = [];

/** A fetch that answers each path from `answers` (path without the origin) and records every call. */
function stubFetch(answers: Record<string, { status: number; body: unknown }>) {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init: RequestInit) => {
      calls.push({ url, init });
      const path = url.replace("http://java.test", "");
      const answer = answers[path];
      if (!answer) throw new Error(`unexpected fetch ${path}`);
      return new Response(typeof answer.body === "string" ? answer.body : JSON.stringify(answer.body), {
        status: answer.status,
        headers: { "content-type": "application/json" },
      });
    }),
  );
}

beforeEach(() => {
  calls = [];
  vi.stubEnv("JAVA_INTERNAL_URL", "http://java.test");
  incoming.clear();
  incoming.set("cookie", "baymax_session=s1; baymax_lang=en");
  incoming.set("x-medioo-path", "/app/home?patient=p1");
  incoming.set("authorization", "Bearer not-for-java");
  incoming.set("origin", "https://medioo.example");
  vi.mocked(navigation.redirect).mockClear();
  vi.mocked(navigation.notFound).mockClear();
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

describe("javaGet", () => {
  it("sendsGetWithOnlyTheCookie", async () => {
    incoming.set("x-medioo-preview", "1");
    stubFetch({ "/app/api/me": { status: 200, body: { preview: true } } });
    await javaGet("/app/api/me");
    expect(calls.length).toBe(1);
    const init = calls[0]!.init;
    expect(init.method).toBe("GET");
    expect(Object.keys(init.headers as Record<string, string>)).toEqual(["cookie"]);
    expect((init.headers as Record<string, string>).cookie).toBe("baymax_session=s1; baymax_lang=en");
    expect(init.cache).toBe("no-store");
  });

  it("mapsARefusalToItsReason", async () => {
    stubFetch({ "/app/api/me": { status: 401, body: { reason: "UNAUTHENTICATED" } } });
    expect(await javaGet("/app/api/me")).toEqual({ status: 401, reason: "UNAUTHENTICATED" });
  });

  it("aJavaErrorNamesTheStatusAndPatternOnly", async () => {
    const path = `${PAPER}?x=+8801700000123`;
    stubFetch({ [path]: { status: 502, body: "upstream said Rokeya +8801700000123" } });
    const error = await javaGet(path).then(
      () => null,
      (e: Error) => e,
    );
    expect(error?.message).toBe("java 502 /app/api/documents/{id}");
    expect(error?.message).not.toContain("8801700000123");
    expect(error?.message).not.toContain("Rokeya");
  });

  it("aNonJsonOkNamesTheStatusAndPatternOnly", async () => {
    // undici's SyntaxError quotes the start of the body ("Unexpected token 'R', "Rokeya +88"..."), which would reach
    // the Next server log: the 200 that is not JSON is named like any other failure, with no cause attached.
    stubFetch({ [PAPER]: { status: 200, body: "Rokeya +8801700000123" } });
    const error = await javaGet(PAPER).then(
      () => null,
      (e: Error) => e,
    );
    expect(error?.message).toBe("java 200 /app/api/documents/{id}");
    expect(error?.cause).toBeUndefined();
  });

  it("anUnreachableJavaNamesThePatternOnly", async () => {
    const path = `${PAPER}?x=+8801700000123`;
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string) => {
        throw new TypeError(`fetch failed ${url}`);
      }),
    );
    const error = await javaGet(path).then(
      () => null,
      (e: Error) => e,
    );
    expect(error?.message).toBe("java unreachable /app/api/documents/{id}");
    expect(error?.message).not.toContain("?");
    expect(error?.message).not.toContain("8801700000123");
    expect(error?.cause).toBeUndefined();
  });

  it("aPreviewRequestReadsMeFirstAndStopsWhenNotPreview", async () => {
    incoming.set("x-medioo-preview", "1");
    stubFetch({ "/app/api/me": { status: 200, body: { preview: false } }, [PAPER]: { status: 200, body: {} } });
    const outcome = await javaGet(PAPER).then(
      () => "read",
      (e: Error) => e.message,
    );
    // What Java was asked first: a paper read here would be a DOCUMENT_VIEW nobody made.
    expect(calls.map((c) => c.url.replace("http://java.test", ""))).toEqual(["/app/api/me"]);
    expect(outcome).toBe("NEXT_NOT_FOUND");
  });

  it("aPreviewRequestForAPreviewFamilyReads", async () => {
    incoming.set("x-medioo-preview", "1");
    stubFetch({ "/app/api/me": { status: 200, body: { preview: true } }, [PAPER]: { status: 200, body: { id: "d" } } });
    expect(await javaGet(PAPER)).toEqual({ status: 200, body: { id: "d" } });
    expect(calls.map((c) => c.url.replace("http://java.test", ""))).toEqual(["/app/api/me", PAPER]);
  });

  it("aSignedOutPreviewRequestGoesToSignInWithItsPlace", async () => {
    incoming.set("x-medioo-preview", "1");
    incoming.set("x-medioo-path", "/app/preview/home");
    stubFetch({ "/app/api/me": { status: 401, body: { reason: "UNAUTHENTICATED" } } });
    await expect(javaGet(PAPER)).rejects.toThrow("NEXT_REDIRECT /app/?next=%2Fapp%2Fpreview%2Fhome");
    expect(calls.length).toBe(1);
  });

  it("aPlainRequestReadsOnlyThePath", async () => {
    stubFetch({ [PAPER]: { status: 200, body: { id: "d" } } });
    expect(await javaGet(PAPER)).toEqual({ status: 200, body: { id: "d" } });
    expect(calls.map((c) => c.url.replace("http://java.test", ""))).toEqual([PAPER]);
  });
});

describe("signedIn", () => {
  it("returnsTheBodyOfA200", () => {
    expect(signedIn({ status: 200, body: { a: 1 } }, "/app/home")).toEqual({ a: 1 });
  });

  it("aSignedOutAnswerRedirectsToSignInWithThePlace", () => {
    expect(() => signedIn({ status: 401, reason: "UNAUTHENTICATED" }, "/app/documents/x?all=1")).toThrow(
      "NEXT_REDIRECT /app/?next=%2Fapp%2Fdocuments%2Fx%3Fall%3D1",
    );
    expect(navigation.redirect).toHaveBeenCalledWith("/app/?next=%2Fapp%2Fdocuments%2Fx%3Fall%3D1");
  });

  it("a404IsTheNotFoundPage", () => {
    expect(() => signedIn({ status: 404, reason: "NOT_FOUND" }, "/app/home")).toThrow("NEXT_NOT_FOUND");
  });

  it("anyOtherRefusalThrowsByNameOnly", () => {
    expect(() => signedIn({ status: 403, reason: "VIEW_ONLY" }, "/app/home")).toThrow("java 403 VIEW_ONLY");
  });
});
