import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiGet, apiSend } from "./browser";

/*
 * src/api/browser.ts: every mutation the screens make, browser → Caddy → Java. Java's AppApiGuard.csrf refuses a
 * mutation without `X-Medioo: 1`, with no Origin, or with no JSON/multipart type (a body-less POST included), so each of
 * those is asserted on what the fetch was handed.
 */

type Call = { url: string; init: RequestInit };
let calls: Call[] = [];
const assign = vi.fn();

function answer(status: number, body?: unknown) {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init: RequestInit) => {
      calls.push({ url, init });
      return new Response(body === undefined ? null : JSON.stringify(body), { status });
    }),
  );
}

const headersOf = (c: Call) => new Headers(c.init.headers);

beforeEach(() => {
  calls = [];
  assign.mockClear();
  vi.stubGlobal("location", { pathname: "/app/preview/documents/d1", search: "?all=1", assign });
});

afterEach(() => vi.unstubAllGlobals());

describe("apiSend", () => {
  it("sendsJsonWithTheCsrfHeader", async () => {
    answer(204);
    expect(await apiSend("PUT", "/app/api/x", { a: 1 })).toEqual({ kind: "ok", status: 204, body: null });
    expect(calls[0]!.url).toBe("/app/api/x");
    expect(calls[0]!.init.method).toBe("PUT");
    expect(headersOf(calls[0]!).get("x-medioo")).toBe("1");
    expect(headersOf(calls[0]!).get("content-type")).toBe("application/json");
    expect(calls[0]!.init.body).toBe('{"a":1}');
    expect(calls[0]!.init.credentials).toBe("same-origin");
  });

  it("aBodylessPostStillNamesItsType", async () => {
    answer(204);
    await apiSend("POST", "/app/api/sign-out");
    expect(headersOf(calls[0]!).get("x-medioo")).toBe("1");
    expect(headersOf(calls[0]!).get("content-type")).toBe("application/json");
    expect(calls[0]!.init.body).toBeUndefined();
  });

  it("formDataSetsNoContentTypeOfItsOwn", async () => {
    answer(200, { ok: true });
    const form = new FormData();
    form.append("file", new Blob(["x"]), "a.jpg");
    expect(await apiSend("POST", "/app/api/uploads", form)).toEqual({ kind: "ok", status: 200, body: { ok: true } });
    expect(headersOf(calls[0]!).has("content-type")).toBe(false);
    expect(headersOf(calls[0]!).get("x-medioo")).toBe("1");
    expect(calls[0]!.init.body).toBe(form);
  });

  it("aSignedOutAnswerGoesToSignInWithThePageHeld", async () => {
    answer(401, { reason: "UNAUTHENTICATED" });
    expect(await apiSend("PUT", "/app/api/now-banner/ack", { seen: [] })).toEqual({ kind: "signed-out" });
    expect(assign).toHaveBeenCalledWith("/app/?next=%2Fapp%2Fpreview%2Fdocuments%2Fd1%3Fall%3D1");
  });

  it("aRefusalIsItsReason", async () => {
    answer(403, { reason: "VIEW_ONLY" });
    expect(await apiSend("DELETE", "/app/api/documents/d1")).toEqual({ kind: "refused", status: 403, reason: "VIEW_ONLY" });
    expect(assign).not.toHaveBeenCalled();
  });

  it("aFailedFetchIsOffline", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => Promise.reject(new TypeError("Failed to fetch"))));
    expect(await apiSend("PUT", "/app/api/x", {})).toEqual({ kind: "offline" });
  });
});

describe("apiGet", () => {
  it("readsWithTheSessionRule", async () => {
    answer(200, { status: "DONE" });
    expect(await apiGet("/app/api/documents/d1/status")).toEqual({ kind: "ok", status: 200, body: { status: "DONE" } });
    expect(calls[0]!.init.method).toBe("GET");
    answer(401, { reason: "UNAUTHENTICATED" });
    expect(await apiGet("/app/api/documents/d1/status")).toEqual({ kind: "signed-out" });
    expect(assign).toHaveBeenCalledTimes(1);
  });
});
