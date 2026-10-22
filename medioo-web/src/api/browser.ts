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
  const text = await response.text().catch(() => "");
  let json: unknown = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    json = null;
  }
  if (response.ok) return { kind: "ok", status: response.status, body: json as T | null };
  const reason = typeof (json as { reason?: unknown } | null)?.reason === "string" ? (json as { reason: string }).reason : "";
  if (response.status === 401 && reason === "UNAUTHENTICATED") {
    location.assign(signInPath(location.pathname + location.search));
    return { kind: "signed-out" };
  }
  return { kind: "refused", status: response.status, reason };
}

/** A mutation: `X-Medioo: 1`, JSON unless the body is FormData, the session cookie, and the 401 rule. */
export function apiSend<T>(method: "POST" | "PUT" | "DELETE", path: string, body?: object | FormData): Promise<ApiResult<T>> {
  return call<T>(method, path, body);
}

/** A client read (a status poll, the crop retry's session check), under the same 401 rule. */
export function apiGet<T>(path: string): Promise<ApiResult<T>> {
  return call<T>("GET", path);
}
