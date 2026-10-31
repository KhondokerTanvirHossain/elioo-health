import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import {
  FIXTURES,
  PAGE_SCENARIOS,
  SCENARIOS,
  UNAUTHENTICATED,
  bannerFor,
  firstRunAsked,
  flagsSaved,
  longNameOf,
  messagesOnly,
  reRead,
  signedOut,
  withAsks,
  withNowName,
} from "./scenarios.mjs";

/*
 * The scenario table is held to Java, not to what its author remembered (CLAUDE.md: a stub that cannot fail the way
 * production fails is not a test). Every key is a route Java serves, as its generated snapshot spells it, and every
 * fixture a key answers with was written by Java's contract test for that same method and route. Every W4–W10 screen
 * extends this table, so a scenario serving a route Java lacks, or a fixture written for another route, fails here.
 */

const SNAPSHOT = join(__dirname, "..", "..", "..", "docs", "infra", "app-routes.txt");

/** `METHOD pattern` lines of the snapshot, every method (the `/app/api` lines included); comments left out. */
export function snapshotRoutes(text: string): Set<string> {
  return new Set(
    text
      .split("\n")
      .map((line) => line.trim())
      .filter((line) => /^(?:GET|POST|PUT|PATCH|DELETE|\*) \/\S+$/.test(line)),
  );
}

type Named = { scenario: string; key: string; file: string; when?: Record<string, string> };

/**
 * Each fixture path a scenario value names (a string, each string of a list, each `byQuery` answer with the query it
 * is served for, and `otherwise`), with the key it answers.
 */
export function fixturesOf(scenarios: Record<string, Record<string, unknown>>): Named[] {
  const out: Named[] = [];
  const plain = (scenario: string, key: string, value: unknown, when?: Record<string, string>) => {
    for (const v of Array.isArray(value) ? value : [value]) if (typeof v === "string") out.push({ scenario, key, file: v, ...(when ? { when } : {}) });
  };
  for (const [scenario, table] of Object.entries(scenarios)) {
    for (const [key, value] of Object.entries(table)) {
      if (value !== null && typeof value === "object" && "byQuery" in value) {
        const q = value as { byQuery: { when: Record<string, string>; answer: unknown }[]; otherwise: unknown };
        for (const { when, answer } of q.byQuery) plain(scenario, key, answer, when);
        plain(scenario, key, q.otherwise);
      } else plain(scenario, key, value);
    }
  }
  return out;
}

/**
 * What is wrong with a fixture for a key: missing, written by Java for another method or route, or, when it is served
 * for a query (`when`), written for another value of a parameter that query names. A source spells its route, then
 * optionally the query it was read with (`GET …/timeline?type=lab over the case lab-only`).
 */
export function fixtureProblem(key: string, file: string, fixtures: string = FIXTURES, when?: Record<string, string>): string | null {
  const path = join(fixtures, file);
  if (!existsSync(path)) return `${file} does not exist`;
  const source = (JSON.parse(readFileSync(path, "utf8")) as { _meta?: { source?: unknown } })._meta?.source;
  if (typeof source !== "string") return `${file} has no _meta.source`;
  if (!source.startsWith(`${key} `) && !source.startsWith(`${key}?`)) return `${file} was written for "${source}", not "${key}"`;
  const read = new URLSearchParams(source.startsWith(`${key}?`) ? source.slice(key.length + 1).split(" ", 1)[0] : "");
  for (const [k, v] of Object.entries(when ?? {})) {
    // A parameter the source names must have the value it is served for; one it does not name (a cursor Java minted at
    // run time) is not judged here.
    if (read.has(k) && read.get(k) !== v) return `${file} was written for ${k}=${read.get(k)}, served for ${k}=${v}`;
  }
  return null;
}

/** The body a scenario value answers first: a fixture file's, or a literal's. */
function firstBody(value: unknown): unknown {
  const v = Array.isArray(value) ? value[0] : value;
  if (typeof v === "string") return (JSON.parse(readFileSync(join(FIXTURES, v), "utf8")) as { body: unknown }).body;
  return (v as { body: unknown }).body;
}

/** A fixture's body, or a literal's. */
function bodyAt(value: unknown): unknown {
  const v = Array.isArray(value) ? value[0] : value;
  if (typeof v === "string") return (JSON.parse(readFileSync(join(FIXTURES, v), "utf8")) as { body: unknown }).body;
  return (v as { body?: unknown } | undefined)?.body;
}

type Listed = { patients?: { id: string; owner: boolean }[] };
const listed = (b: unknown) => ((b as Listed | undefined)?.patients ?? []).map((p) => `${p.id} owner=${p.owner}`);

/**
 * What is wrong with a scenario that serves both `me` and `family`: the two bodies list other patients, or own them
 * differently, which Java cannot produce (both are built from one timeline.me). Null when they agree, or when the
 * scenario serves only one of them.
 */
export function mePairProblem(table: Record<string, unknown>): string | null {
  const me = table["GET /app/api/me"];
  const family = table["GET /app/api/family"];
  if (me === undefined || family === undefined) return null;
  const a = listed(bodyAt(me));
  const b = listed(bodyAt(family));
  if (a.length === 0 && b.length === 0) return "me and family list no patient: nothing is compared";
  return JSON.stringify(a) === JSON.stringify(b) ? null : `me lists ${JSON.stringify(a)}, family lists ${JSON.stringify(b)}`;
}

describe("the scenario table is Java's", () => {
  const routes = snapshotRoutes(readFileSync(SNAPSHOT, "utf8"));

  it("theSnapshotParsed", () => {
    // Guard: an empty parse would make every key "missing", and a wrong parse would let anything through.
    expect(routes.size, "routes parsed from app-routes.txt").toBeGreaterThan(20);
    expect(routes.has("GET /app/api/me")).toBe(true);
    expect(routes.has("POST /app/api/sign-in/request")).toBe(true);
  });

  it("everyKeyIsARouteJavaServes", () => {
    const keys = Object.values(SCENARIOS).flatMap((table) => Object.keys(table));
    expect(keys.length, "scenario keys").toBeGreaterThan(0);
    expect([...new Set(keys.filter((k) => !routes.has(k)))], "keys that are not a line of app-routes.txt").toEqual([]);
  });

  it("everyFixtureWasWrittenForItsKey", () => {
    const named = fixturesOf(SCENARIOS);
    expect(named.length, "fixtures named by the scenarios").toBeGreaterThan(0);
    const problems = named
      .map(({ scenario, key, file, when }) => ({ scenario, problem: fixtureProblem(key, file, FIXTURES, when) }))
      .filter((p) => p.problem !== null)
      .map((p) => `${p.scenario}: ${p.problem}`);
    expect(problems).toEqual([]);
  });

  // The dangerous direction, on synthetic tables: each check refuses what it exists to refuse.
  it("aFixtureUnderAnotherRouteIsRefused", () => {
    expect(fixtureProblem("GET /app/api/sign-in/next", "sign-in/verified.json")).toBe(
      'sign-in/verified.json was written for "POST /app/api/sign-in/verify over the SignInApiContractTest case verified", not "GET /app/api/sign-in/next"',
    );
    expect(fixtureProblem("GET /app/api/sign-in/next", "sign-in/next-held.json")).toBeNull();
    expect(fixtureProblem("GET /app/api/me", "me/nope.json")).toBe("me/nope.json does not exist");
    // A prefix of another route is not that route: "GET /app/api/me" must not accept a "GET /app/api/meX" fixture.
    expect(fixtureProblem("GET /app/api/m", "me/one-patient.json")).not.toBeNull();
  });

  it("aFixtureReadWithAQueryIsItsRoutesAndOnlyForThatQuery", () => {
    // The calendar's source names its month after the route: the route still matches, and a prefix still does not.
    expect(fixtureProblem("GET /app/api/patients/{id}/calendar", "calendar/busy.json")).toBeNull();
    expect(fixtureProblem("GET /app/api/patients/{id}/calenda", "calendar/busy.json")).not.toBeNull();
    // Served for the query it was read with, it passes; for another value of the same parameter, it is refused.
    const timeline = "GET /app/api/patients/{id}/timeline";
    expect(fixtureProblem(timeline, "timeline/lab-only.json", FIXTURES, { type: "lab" })).toBeNull();
    expect(fixtureProblem(timeline, "timeline/lab-only.json", FIXTURES, { type: "rx" })).toBe(
      "timeline/lab-only.json was written for type=lab, served for type=rx",
    );
    expect(fixtureProblem("GET /app/api/patients/{id}/calendar", "calendar/busy.json", FIXTURES, { month: "2026-09" })).toBe(
      "calendar/busy.json was written for month=2026-10, served for month=2026-09",
    );
  });

  it("everyByQueryWalksEveryAnswerAndHasAnOtherwise", () => {
    const named = fixturesOf({ s: { "GET /x": { byQuery: [{ when: { a: "1" }, answer: "a.json" }], otherwise: "b.json" } } });
    expect(named).toEqual([
      { scenario: "s", key: "GET /x", file: "a.json", when: { a: "1" } },
      { scenario: "s", key: "GET /x", file: "b.json" },
    ]);
    // Every byQuery value in the real table answers something when no entry matches.
    const missing = Object.entries(SCENARIOS).flatMap(([name, table]) =>
      Object.entries(table)
        .filter(([, v]) => v !== null && typeof v === "object" && "byQuery" in v && (v as { otherwise?: unknown }).otherwise === undefined)
        .map(([key]) => `${name} ${key}`),
    );
    expect(missing).toEqual([]);
  });

  it("signedOutIsJudgedOnMesAnswerNotItsFileName", () => {
    expect(signedOut("x", { "GET /app/api/me": UNAUTHENTICATED })).toBe(true);
    expect(signedOut("x", { "GET /app/api/me": "me/unauthenticated.json" })).toBe(true);
    expect(signedOut("x", { "GET /app/api/me": { status: 502, body: "bad gateway" } })).toBe(false);
    expect(signedOut("x", { "GET /app/api/me": "me/one-patient.json" })).toBe(false);
    expect(signedOut("x", {})).toBe(false);
    expect(signedOut("signed-in")).toBe(false);
    expect(signedOut("signed-out")).toBe(true);
  });

  it("theLongNameIsTheTwinsOwnMeName", () => {
    const long = (JSON.parse(readFileSync(join(FIXTURES, "me", "long-name.json"), "utf8")) as {
      body: { patients: { name: string }[] };
    }).body.patients[0]!.name;
    expect(long.length, "the long-name fixture's name is long").toBeGreaterThan(20);
    expect(longNameOf("twin", { "GET /app/api/me": "me/long-name.json" })).toBe(long);
    // A twin without a named patient cannot carry a long name: it fails, never judged on short strings.
    expect(() => longNameOf("twin", { "GET /app/api/me": "me/no-patients.json" })).toThrow("names no patient");
    expect(() => longNameOf("twin", { "GET /app/api/me": UNAUTHENTICATED })).toThrow("names no patient");
    expect(() => longNameOf("twin", {})).toThrow("names no patient");
  });

  // The derived answers (a fixture read and changed by a helper, served as a literal) are held to the same rule: the
  // fixture they start from was written by Java for the route they answer.
  it("aDerivedAnswerRefusesAFixtureWrittenForAnotherRoute", () => {
    expect(() => bannerFor("me/one-patient.json", "me/one-patient.json")).toThrow(
      'me/one-patient.json was written for "GET /app/api/me over',
    );
    expect(() => reRead("me/one-patient.json")).toThrow('me/one-patient.json was written for "GET /app/api/me over');
    expect(() => reRead("now-banner/one.json", "me/one-patient.json")).not.toThrow();
    expect(() => messagesOnly("now-banner/one.json")).toThrow('now-banner/one.json was written for "GET /app/api/now-banner over');
    // The other direction: Java's own fixtures for each route pass.
    expect(bannerFor("now-banner/one.json", "me/one-patient.json").body).toMatchObject({ show: true });
    expect(reRead("now-banner/acked.json")).toMatchObject({ status: 200, body: { show: false } });
    const messages = messagesOnly("thread/default.json").body as { items: { kind: string }[] };
    expect(messages.items.length, "default.json holds messages").toBeGreaterThan(0);
    expect(messages.items.every((i) => i.kind === "message")).toBe(true);
    // withAsks (plan CHAT-web): only a fixture Java read with asks=1, the thread read without the query included.
    expect(() => withAsks("thread/default.json")).toThrow('thread/default.json was written for "GET /app/api/patients/{id}/thread over');
    const asked = withAsks("thread/with-asks.json");
    expect(asked.byQuery).toEqual([{ when: { asks: "1" }, answer: "thread/with-asks.json" }]);
    // Without asks=1, Java lists every item but the asks, words unchanged.
    const all = (firstBody("thread/with-asks.json") as { items: { kind: string }[] }).items;
    const without = (asked.otherwise as { body: { items: { kind: string }[] } }).body.items;
    expect(all.filter((i) => i.kind === "ask").length, "with-asks holds asks").toBeGreaterThan(0);
    expect(without, "the same page without its asks").toEqual(all.filter((i) => i.kind !== "ask"));
    // firstRunAsked (review of PR 166): only a first-run page, joined with one ask Java stored, and the page as written
    // without asks=1.
    expect(() => firstRunAsked("thread/default.json", "thread/with-asks.json")).toThrow("is not a first-run page");
    expect(() => firstRunAsked("thread/first-run.json", "thread/default.json")).toThrow("was written for");
    const first = firstRunAsked("thread/first-run.json", "thread/with-asks.json");
    const joined = (first.byQuery[0]!.answer as { body: { items: { kind: string }[] } }).body.items;
    expect(joined.map((i) => i.kind)).toEqual(["ask"]);
    expect(joined[0]).toEqual(all.filter((i) => i.kind === "ask").at(-1));
    expect(first.otherwise).toBe("thread/first-run.json");
  });

  it("everySignedInPageHasANowTwin", () => {
    const pages = Object.entries(PAGE_SCENARIOS).filter(([, name]) => !signedOut(name));
    expect(pages.length, "signed-in pages in PAGE_SCENARIOS").toBeGreaterThan(0);
    for (const [path, name] of pages) {
      const twin = SCENARIOS[withNowName(name)];
      expect(twin, `${path}: ${withNowName(name)}`).toBeDefined();
      const me = firstBody(twin!["GET /app/api/me"]!) as { patients: { id: string }[] };
      const banner = firstBody(twin!["GET /app/api/now-banner"]!) as { show: boolean; documents: { patient_id: string }[] };
      expect(banner.show, `${path}: the twin shows a NOW banner`).toBe(true);
      expect(banner.documents.map((d) => d.patient_id)).toEqual([me.patients[0]!.id]);
      // Everything else the page reads is answered as its own scenario answers it.
      const others = (table: Record<string, unknown>) => Object.entries(table).filter(([key]) => key !== "GET /app/api/now-banner");
      expect(others(twin!)).toEqual(others(SCENARIOS[name]!));
    }
  });

  it("everyFamilyScenariosMeListsItsFamilysPatients", () => {
    const paired = Object.entries(SCENARIOS).filter(([, table]) => table["GET /app/api/me"] && table["GET /app/api/family"]);
    // Guard: the family scenarios were found, or the sweep passes over nothing.
    expect(paired.map(([name]) => name), "scenarios serving me and family").toContain("family-owner");
    expect(paired.length).toBeGreaterThanOrEqual(6);
    expect(paired.map(([name, table]) => [name, mePairProblem(table)]).filter(([, p]) => p !== null)).toEqual([]);
  });

  it("aMeThatNamesSomeoneTheTabDoesNotListIsRefused", () => {
    // The pairs W10 first shipped: Ma beside two other patients, and an owned `me` beside a shared family patient.
    expect(mePairProblem({ "GET /app/api/me": "me/one-patient.json", "GET /app/api/family": "family/owner.json" })).toMatch(/^me lists/);
    expect(mePairProblem({ "GET /app/api/me": "me/one-patient.json", "GET /app/api/family": "family/share-member.json" })).toMatch(
      /^me lists/,
    );
    // The same ids owned differently are refused too.
    const family = bodyAt("family/share-member.json") as { patients: { id: string; owner: boolean }[] };
    const owned = { status: 200, body: { patients: family.patients.map((p) => ({ ...p, owner: true })) } };
    expect(mePairProblem({ "GET /app/api/me": owned, "GET /app/api/family": "family/share-member.json" })).toMatch(/owner=true/);
    expect(mePairProblem({ "GET /app/api/me": "me/family-share-member.json", "GET /app/api/family": "family/share-member.json" })).toBeNull();
  });

  it("theFlagsAnswerIsTheListSentAsJavaStoresIt", () => {
    const family = bodyAt("family/owner.json") as { patients: { id: string }[] };
    const second = family.patients[1]!.id;
    const saved = flagsSaved("family/owner.json")({ path: `/app/api/patients/${second}/flags`, body: { chronic_flags: [" Kidney ", "migraine", "kidney", " "] } });
    expect(saved.status).toBe(200);
    const body = saved.body as { id: string; chronic_flags: unknown };
    expect(body.id, "the patient the path names, not the first one").toBe(second);
    expect(body.chronic_flags).toEqual([
      { value: "kidney", label_key: "flag.kidney" },
      { value: "migraine", label_key: null },
    ]);
    expect(flagsSaved("family/owner.json")({ path: "/app/api/patients/5e9edf6d-0a2a-4913-8675-9d07a4100099/flags", body: {} }).status).toBe(404);
  });

  it("aMisspelledRouteIsRefused", () => {
    expect(routes.has("GET /app/api/sign-in/nxt")).toBe(false);
    expect(routes.has("GET /app/api/sign-in/next")).toBe(true);
  });
});
