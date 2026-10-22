// Types for java-stub.mjs (plain ESM: `node e2e/support/java-stub.mjs` runs stub Java with no build step).
type Headers = Record<string, string | string[] | undefined>;
export const OPEN: Set<string>;
export function cookieValue(header: string | undefined, name: string): string | null;
export function routeKey(scenario: Record<string, unknown>, method: string, path: string): string | null;
export function answer(request: {
  method: string;
  path: string;
  headers: Headers;
  origin: string;
  counters: Map<string, number>;
}): { status: number; body: unknown } | { abort: "connectionreset" | "connectionclosed" | "failed" };
export function record(request: { method: string; path: string; query: string; headers: Headers }): {
  method: string;
  path: string;
  query: string;
  run: string | null;
  headerNames: string[];
  "x-medioo": string | null;
  origin: string | null;
  "content-type": string | null;
};
