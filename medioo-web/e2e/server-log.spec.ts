import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { expect, test } from "@playwright/test";
import { STUB_URL } from "./support/browser-api";
import { FIXTURES } from "./support/scenarios.mjs";

/*
 * The `logs` project, after every phone and desktop test (playwright.config.ts): the Next server's own output for the
 * whole run, happy paths and Java failing (`java-502`, `next-500`) included, carries no screen text. What it must not
 * hold is derived from the fixtures the run served, not typed here: every number and its local forms, every name,
 * every number tail beside its mask, the codes the tests typed, and any query string.
 */

const LOG = join(__dirname, "..", "test-results", "next-server.log");

/** Every fixture body under fixtures/api, as parsed JSON. */
function bodies(): unknown[] {
  const out: unknown[] = [];
  const walk = (dir: string) => {
    for (const entry of readdirSync(dir)) {
      const path = join(dir, entry);
      if (statSync(path).isDirectory()) walk(path);
      else if (entry.endsWith(".json")) out.push((JSON.parse(readFileSync(path, "utf8")) as { body: unknown }).body);
    }
  };
  walk(FIXTURES);
  return out;
}

/** The values of every `key` field anywhere in `value`. */
function fields(value: unknown, key: string, out: string[] = []): string[] {
  if (Array.isArray(value)) value.forEach((v) => fields(v, key, out));
  else if (value && typeof value === "object") {
    for (const [k, v] of Object.entries(value)) {
      if (k === key && typeof v === "string" && v.trim()) out.push(v);
      else fields(v, key, out);
    }
  }
  return out;
}

test("theNextServerLogCarriesNoPatientText", async () => {
  const log = readFileSync(LOG, "utf8");
  // Guards: the log is the server's and saw Java fail, and the run reached the sign-in state read.
  expect(log.length, "the server log is not empty").toBeGreaterThan(0);
  expect(log, "the 502 from me behind the preview gate was logged by name").toContain("java 502 /app/api/me");
  expect(log).toContain("java 500 /app/api/sign-in/next");
  const requests = (await (await fetch(`${STUB_URL}/__requests`)).json()) as { method: string; path: string }[];
  expect(requests.some((r) => r.method === "GET" && r.path === "/app/api/sign-in/state"), "sign-in/state was read").toBe(true);

  const all = bodies();
  const text = JSON.stringify(all);
  const numbers = new Set<string>();
  for (const m of text.matchAll(/\+?880 ?1\d{3}-?\d{6}/g)) {
    const raw = m[0].replace(/[ -]/g, "");
    const national = raw.replace(/^\+?880/, "0");
    for (const form of [m[0], raw, raw.replace(/^\+/, ""), national, national.slice(1)]) numbers.add(form);
  }
  // The numbers the tests typed.
  numbers.add("01700000123");
  const names = [...fields(all, "name"), ...fields(all, "owner_name")];
  const tails = fields(all, "number_tail").map((tail) => `••${tail}`);
  const forbidden = [...numbers, ...names, ...tails, "123456"];
  // Guard: the lists were read from the fixtures, so "nothing found" is about real values.
  expect(numbers.size, "numbers read from the fixtures").toBeGreaterThan(1);
  expect(names.length, "names read from the fixtures").toBeGreaterThan(0);
  expect(tails.length, "number tails read from the fixtures").toBeGreaterThan(0);
  // A value counts as a whole word (a name such as "Ma" is not found inside "Math" in a stack trace), never as any
  // substring: the check is about the value, not about letters it shares with Node's own words.
  const escape = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const found = (value: string) => new RegExp(`(^|[^\\p{L}\\p{N}])${escape(value)}($|[^\\p{L}\\p{N}])`, "u").test(log);
  expect(forbidden.filter(found)).toEqual([]);
  // No query string at all: a held place or an id in a URL is the family's.
  expect(["?next=", "next=%2F", "?patient=", "?key="].filter((q) => log.includes(q))).toEqual([]);
});
