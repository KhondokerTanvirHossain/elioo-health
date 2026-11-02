import { signInPath } from "@/routing/paths";

/*
 * Every request a screen makes from the browser (plan Wave 3, Shared interfaces → Browser writes): mutations go
 * browser → Caddy → Java, never through Next (DR-40 A3). Each call carries what Java's AppApiGuard.csrf requires:
 * `X-Medioo: 1`, a JSON type on every call without FormData (a body-less POST included, which the guard would answer
 * 415), and the browser's own Origin (a same-origin fetch sends it). No copy and no console: a client island turns a
 * result into words its server parent already resolved.
 */

export type ApiResult<T> =
  | { kind: "ok"; status: number; body: T | null }
  | { kind: "refused"; status: number; reason: string }
  | { kind: "signed-out" }
  | { kind: "offline" };

/**
 * What a call may be given beyond its body: `timeoutMs`, after which it is given up on (see apiSend); and `stayOnSignOut`,
 * which answers a 401 with `signed-out` and leaves the page where it is, for a caller that must keep something on screen
 * and offers the sign-in link itself (the composer's ask, whose reply carries the red-flag line: review of PR 166,
 * finding 14). Without it a 401 goes to sign-in at once, as before.
 */
export type CallOptions = { timeoutMs?: number; stayOnSignOut?: boolean };

async function call<T>(method: string, path: string, body?: object | FormData, options: CallOptions = {}): Promise<ApiResult<T>> {
  const headers: Record<string, string> = { "X-Medioo": "1" };
  const form = typeof FormData !== "undefined" && body instanceof FormData;
  if (!form) headers["Content-Type"] = "application/json";
  const init: RequestInit = {
    method,
    headers,
    body: form ? (body as FormData) : body === undefined ? undefined : JSON.stringify(body),
    credentials: "same-origin",
  };
  const exchange = async (): Promise<ApiResult<T>> => {
    let response: Response;
    try {
      response = await fetch(path, init);
    } catch {
      return { kind: "offline" };
    }
    return judged<T>(response.status, await response.text().catch(() => ""), options.stayOnSignOut === true);
  };
  if (options.timeoutMs === undefined) return exchange();
  // The timeout ends the call whether or not the fetch honours its signal, and whether the headers or only the body are
  // late: the race resolves `offline` at the timeout first, then the abort frees the connection, so a late answer (or a
  // body the abort cut short) can never follow the fallback.
  const controller = new AbortController();
  init.signal = controller.signal;
  let timer: ReturnType<typeof setTimeout> | undefined;
  const timedOut = new Promise<ApiResult<T>>((resolve) => {
    timer = setTimeout(() => {
      resolve({ kind: "offline" });
      controller.abort();
    }, options.timeoutMs);
  });
  try {
    return await Promise.race([exchange(), timedOut]);
  } finally {
    clearTimeout(timer);
  }
}

/**
 * A mutation: `X-Medioo: 1`, JSON unless the body is FormData, the session cookie, and the 401 rule. With `timeoutMs`
 * (the composer's ask, plan CHAT-web), a call not answered in that time is aborted and resolves `offline`; without it the
 * call is exactly what it was before the option existed (no signal, no clock).
 */
export function apiSend<T>(
  method: "POST" | "PUT" | "DELETE",
  path: string,
  body?: object | FormData,
  options?: CallOptions,
): Promise<ApiResult<T>> {
  return call<T>(method, path, body, options);
}

/** A client read (a status poll, the crop retry's session check), under the same 401 rule. */
export function apiGet<T>(path: string): Promise<ApiResult<T>> {
  return call<T>("GET", path);
}

/** An answer's status and text, as `call` judges them: a body, a refusal by its reason, or the sign-in page. */
function judged<T>(status: number, text: string, stay = false): ApiResult<T> {
  let json: unknown = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    json = null;
  }
  if (status >= 200 && status < 300) return { kind: "ok", status, body: json as T | null };
  const reason = typeof (json as { reason?: unknown } | null)?.reason === "string" ? (json as { reason: string }).reason : "";
  if (status === 401 && reason === "UNAUTHENTICATED") {
    if (!stay) location.assign(signInPath(location.pathname + location.search));
    return { kind: "signed-out" };
  }
  return { kind: "refused", status, reason };
}

/**
 * One press of the add sheet (plan W7): `POST /app/api/uploads` with the sheet's multipart form, straight to Java
 * (Next never sees the body). An XMLHttpRequest, not a fetch, because only it reports how much of the body has gone
 * (`onProgress(sent, total)`). The rules are apiSend's: `X-Medioo: 1`; no Content-Type of its own (the browser writes
 * multipart/form-data with its boundary); a relative, same-origin URL, so the session cookie goes and no cross-origin
 * credential mode is asked for; the 401 rule; and `offline` when the request dies (`error` or `abort`), which the sheet
 * answers with a retry of the same press under the same key.
 *
 * A request that only goes quiet is dead too: a phone losing its signal usually gets no RST and no `error`. upload.js's
 * watchdog (`post()`/`wait()`), ported: no byte moving for `STALL_MS`, or `SERVER_MS` once every byte has gone (the
 * server is storing the pages), and the request is aborted, which resolves `offline`. No `xhr.timeout` is set: the
 * watchdog is the only clock.
 */
export function apiUpload<T = unknown>(form: FormData, onProgress: (sent: number, total: number) => void): Promise<ApiResult<T>> {
  return new Promise((resolve) => {
    const xhr = new XMLHttpRequest();
    let quiet: ReturnType<typeof setTimeout> | undefined;
    const wait = (ms: number) => {
      clearTimeout(quiet);
      quiet = setTimeout(() => xhr.abort(), ms);
    };
    xhr.open("POST", "/app/api/uploads");
    xhr.setRequestHeader("X-Medioo", "1");
    xhr.upload.onprogress = (e) => {
      wait(e.lengthComputable && e.loaded >= e.total ? SERVER_MS : STALL_MS);
      if (e.lengthComputable) onProgress(e.loaded, e.total);
    };
    xhr.upload.onload = () => wait(SERVER_MS);
    xhr.onload = () => {
      clearTimeout(quiet);
      resolve(judged<T>(xhr.status, xhr.responseText));
    };
    const offline = () => {
      clearTimeout(quiet);
      resolve({ kind: "offline" });
    };
    xhr.onerror = offline;
    xhr.onabort = offline;
    wait(STALL_MS);
    xhr.send(form);
  });
}

/** No byte has moved for this long: the network is gone (upload.js: 30 s). */
export const STALL_MS = 30_000;
/** Every byte has gone and the server has not answered for this long: given up on (upload.js: 120 s). */
export const SERVER_MS = 120_000;
