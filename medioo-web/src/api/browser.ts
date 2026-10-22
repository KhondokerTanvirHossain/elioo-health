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

async function call<T>(method: string, path: string, body?: object | FormData): Promise<ApiResult<T>> {
  const headers: Record<string, string> = { "X-Medioo": "1" };
  const form = typeof FormData !== "undefined" && body instanceof FormData;
  if (!form) headers["Content-Type"] = "application/json";
  let response: Response;
  try {
    response = await fetch(path, {
      method,
      headers,
      body: form ? (body as FormData) : body === undefined ? undefined : JSON.stringify(body),
      credentials: "same-origin",
    });
  } catch {
    return { kind: "offline" };
  }
  return judged<T>(response.status, await response.text().catch(() => ""));
}

/** A mutation: `X-Medioo: 1`, JSON unless the body is FormData, the session cookie, and the 401 rule. */
export function apiSend<T>(method: "POST" | "PUT" | "DELETE", path: string, body?: object | FormData): Promise<ApiResult<T>> {
  return call<T>(method, path, body);
}

/** A client read (a status poll, the crop retry's session check), under the same 401 rule. */
export function apiGet<T>(path: string): Promise<ApiResult<T>> {
  return call<T>("GET", path);
}

/** An answer's status and text, as `call` judges them: a body, a refusal by its reason, or the sign-in page. */
function judged<T>(status: number, text: string): ApiResult<T> {
  let json: unknown = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    json = null;
  }
  if (status >= 200 && status < 300) return { kind: "ok", status, body: json as T | null };
  const reason = typeof (json as { reason?: unknown } | null)?.reason === "string" ? (json as { reason: string }).reason : "";
  if (status === 401 && reason === "UNAUTHENTICATED") {
    location.assign(signInPath(location.pathname + location.search));
    return { kind: "signed-out" };
  }
  return { kind: "refused", status, reason };
}

/**
 * One press of the add sheet (plan W7): `POST /app/api/uploads` with the sheet's multipart form, straight to Java
 * (Next never sees the body). An XMLHttpRequest, not a fetch, because only it reports how much of the body has gone
 * (`onProgress(sent, total)`). The rules are apiSend's: `X-Medioo: 1`; no Content-Type of its own (the browser writes
 * multipart/form-data with its boundary); a relative, same-origin URL, so the session cookie goes and no cross-origin
 * credential mode is asked for; the 401 rule; and `offline` when the request dies (`error`, `abort`, `timeout`), which
 * the sheet answers with a retry of the same press under the same key.
 */
export function apiUpload<T = unknown>(form: FormData, onProgress: (sent: number, total: number) => void): Promise<ApiResult<T>> {
  return new Promise((resolve) => {
    const xhr = new XMLHttpRequest();
    xhr.open("POST", "/app/api/uploads");
    xhr.setRequestHeader("X-Medioo", "1");
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable) onProgress(e.loaded, e.total);
    };
    xhr.onload = () => resolve(judged<T>(xhr.status, xhr.responseText));
    const offline = () => resolve({ kind: "offline" });
    xhr.onerror = offline;
    xhr.onabort = offline;
    xhr.ontimeout = offline;
    xhr.send(form);
  });
}
