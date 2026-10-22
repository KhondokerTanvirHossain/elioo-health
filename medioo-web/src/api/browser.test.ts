import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiGet, apiSend, apiUpload } from "./browser";

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

  // The other direction of the 401 rule: only UNAUTHENTICATED is "go and sign in". A sign-in route's 401 with another
  // reason, or a bodiless 401, is a refusal the island says in copy, never a redirect.
  it("aFourOhOneWithAnotherReasonIsARefusalNotARedirect", async () => {
    answer(401, { reason: "SIGN_IN_RESTART" });
    expect(await apiSend("POST", "/app/api/sign-in/verify", { code: "123456" })).toEqual({
      kind: "refused",
      status: 401,
      reason: "SIGN_IN_RESTART",
    });
    expect(assign).not.toHaveBeenCalled();
  });

  it("aBodylessFourOhOneIsARefusalNotARedirect", async () => {
    answer(401);
    expect(await apiSend("POST", "/app/api/sign-in/verify", { code: "123456" })).toEqual({
      kind: "refused",
      status: 401,
      reason: "",
    });
    expect(assign).not.toHaveBeenCalled();
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

/**
 * A fake XMLHttpRequest that records what apiUpload did to it and answers when the test says. apiUpload is an XHR, not
 * a fetch, because only an XHR reports upload progress (`xhr.upload.onprogress`), which the sheet's progress needs.
 */
class FakeXhr {
  static made: FakeXhr[] = [];
  method = "";
  url = "";
  headers: Record<string, string> = {};
  withCredentials = false;
  status = 0;
  responseText = "";
  body: unknown = undefined;
  upload: {
    onprogress: ((e: { loaded: number; total: number; lengthComputable: boolean }) => void) | null;
    onload: (() => void) | null;
  } = { onprogress: null, onload: null };
  aborted = 0;
  onload: (() => void) | null = null;
  onerror: (() => void) | null = null;
  onabort: (() => void) | null = null;
  ontimeout: (() => void) | null = null;
  constructor() {
    FakeXhr.made.push(this);
  }
  open(method: string, url: string) {
    this.method = method;
    this.url = url;
  }
  setRequestHeader(name: string, value: string) {
    this.headers[name.toLowerCase()] = value;
  }
  send(body: unknown) {
    this.body = body;
  }
  /** As a browser does: abort() fires `abort` on a request still open. */
  abort() {
    this.aborted++;
    this.onabort?.();
  }
  answer(status: number, body?: unknown) {
    this.status = status;
    this.responseText = body === undefined ? "" : JSON.stringify(body);
    this.onload?.();
  }
}

describe("apiUpload", () => {
  beforeEach(() => {
    FakeXhr.made = [];
    vi.stubGlobal("XMLHttpRequest", FakeXhr);
  });

  const form = () => {
    const f = new FormData();
    f.append("upload_key", "k-1");
    f.append("file", new Blob(["page"]), "page-1.jpg");
    return f;
  };

  it("apiUploadSendsXMedioAndNoManualContentType", async () => {
    const sent: Array<[number, number]> = [];
    const body = form();
    const pending = apiUpload(body, (s, total) => sent.push([s, total]));
    const xhr = FakeXhr.made[0]!;
    expect(xhr.method).toBe("POST");
    // A relative path: same origin, so the session cookie goes with it and no cross-origin credential mode is asked for.
    expect(xhr.url).toBe("/app/api/uploads");
    expect(xhr.withCredentials).toBe(false);
    expect(xhr.headers["x-medioo"]).toBe("1");
    // The browser writes multipart/form-data with its boundary; a manual Content-Type would drop the boundary.
    expect(Object.keys(xhr.headers)).not.toContain("content-type");
    expect(xhr.body).toBe(body);
    xhr.upload.onprogress?.({ loaded: 50, total: 100, lengthComputable: true });
    xhr.answer(200, { documents: [{ id: "d1" }], next: "/app/documents/d1" });
    expect(await pending).toEqual({ kind: "ok", status: 200, body: { documents: [{ id: "d1" }], next: "/app/documents/d1" } });
    expect(sent).toEqual([[50, 100]]);
  });

  it("apiUploadSendsSignInOn401", async () => {
    const pending = apiUpload(form(), () => {});
    FakeXhr.made[0]!.answer(401, { reason: "UNAUTHENTICATED" });
    expect(await pending).toEqual({ kind: "signed-out" });
    expect(assign).toHaveBeenCalledWith("/app/?next=%2Fapp%2Fpreview%2Fdocuments%2Fd1%3Fall%3D1");
  });

  it("aRefusedUploadIsItsReason", async () => {
    const pending = apiUpload(form(), () => {});
    FakeXhr.made[0]!.answer(413, { reason: "TOO_LARGE" });
    expect(await pending).toEqual({ kind: "refused", status: 413, reason: "TOO_LARGE" });
    expect(assign).not.toHaveBeenCalled();
  });

  it("apiUploadIsOfflineOnError", async () => {
    const failed = apiUpload(form(), () => {});
    FakeXhr.made[0]!.onerror?.();
    expect(await failed).toEqual({ kind: "offline" });
    const aborted = apiUpload(form(), () => {});
    FakeXhr.made[1]!.onabort?.();
    expect(await aborted).toEqual({ kind: "offline" });
    expect(assign).not.toHaveBeenCalled();
  });

  /*
   * A phone that loses its signal usually gets no RST and no `error`: the request just goes quiet (W7 review). This is
   * upload.js's watchdog, ported: 30 s with no byte moving, or 120 s once every byte has gone (the server is storing the
   * pages), and the request is aborted, so the sheet reaches stuck and its same-key retry. One test per clock, and one
   * for an answer stopping it.
   */
  describe("the quiet watchdog", () => {
    beforeEach(() => vi.useFakeTimers());
    afterEach(() => vi.useRealTimers());

    it("aSilentUploadIsOfflineAfterThirtySeconds", async () => {
      let settled: unknown = "pending";
      void apiUpload(form(), () => {}).then((r) => (settled = r));
      const xhr = FakeXhr.made[0]!;
      await vi.advanceTimersByTimeAsync(29_999);
      expect({ aborted: xhr.aborted, settled }).toEqual({ aborted: 0, settled: "pending" });
      await vi.advanceTimersByTimeAsync(1);
      expect({ aborted: xhr.aborted, settled }).toEqual({ aborted: 1, settled: { kind: "offline" } });
    });

    it("aByteMovingReArmsTheThirtySeconds", async () => {
      let settled: unknown = "pending";
      void apiUpload(form(), () => {}).then((r) => (settled = r));
      const xhr = FakeXhr.made[0]!;
      await vi.advanceTimersByTimeAsync(20_000);
      xhr.upload.onprogress?.({ loaded: 50, total: 100, lengthComputable: true });
      await vi.advanceTimersByTimeAsync(29_999);
      expect({ aborted: xhr.aborted, settled }, "30 s from the last byte, not from the start").toEqual({ aborted: 0, settled: "pending" });
      await vi.advanceTimersByTimeAsync(1);
      expect({ aborted: xhr.aborted, settled }).toEqual({ aborted: 1, settled: { kind: "offline" } });
    });

    it("onceEveryByteHasGoneTheServerHasTwoMinutes", async () => {
      let settled: unknown = "pending";
      void apiUpload(form(), () => {}).then((r) => (settled = r));
      const xhr = FakeXhr.made[0]!;
      xhr.upload.onprogress?.({ loaded: 100, total: 100, lengthComputable: true });
      xhr.upload.onload?.();
      await vi.advanceTimersByTimeAsync(119_999);
      expect({ aborted: xhr.aborted, settled }, "the server storing the pages is not a stall").toEqual({ aborted: 0, settled: "pending" });
      await vi.advanceTimersByTimeAsync(1);
      expect({ aborted: xhr.aborted, settled }).toEqual({ aborted: 1, settled: { kind: "offline" } });
    });

    it("anAnswerStopsTheWatchdog", async () => {
      const pending = apiUpload(form(), () => {});
      const xhr = FakeXhr.made[0]!;
      xhr.answer(200, { documents: [{ id: "d1" }], next: "/app/documents/d1" });
      await vi.advanceTimersByTimeAsync(300_000);
      expect(xhr.aborted, "an answered request is never aborted").toBe(0);
      expect((await pending).kind).toBe("ok");
    });
  });
});
