import { describe, expect, it } from "vitest";
import { answer } from "./java-stub.mjs";

/*
 * Stub Java's guard, in AppApiGuard.filter's order: the session first (a route outside OPEN with no session is 401
 * UNAUTHENTICATED, whatever its headers), then CSRF for a mutation (csrf() runs only inside admitted()), then the
 * route. Every later screen's 401 test runs against this stub, so a signed-out mutation must get the answer Java gives.
 */

const ORIGIN = "http://127.0.0.1:3217";
const ask = (method: string, path: string, headers: Record<string, string>) =>
  answer({ method, path, headers, origin: ORIGIN, counters: new Map() });

describe("stub Java's guard order", () => {
  it("aSignedOutMutationOnASessionRouteIs401WhateverItsHeaders", () => {
    // No X-Medioo, no type: Java answers 401 before csrf() is reached.
    expect(ask("POST", "/app/api/sign-out", { cookie: "e2e_scenario=signed-out" })).toEqual({
      status: 401,
      body: { reason: "UNAUTHENTICATED" },
    });
    expect(
      ask("PUT", "/app/api/now-banner/ack", { cookie: "e2e_scenario=signed-out", "x-medioo": "1", origin: "https://evil.example" }),
    ).toEqual({ status: 401, body: { reason: "UNAUTHENTICATED" } });
  });

  it("theSameMutationOnAnOpenRouteMeetsCsrf", () => {
    expect(ask("POST", "/app/api/sign-in/request", { cookie: "e2e_scenario=signed-out" })).toEqual({
      status: 403,
      body: { reason: "MISSING_HEADER" },
    });
    expect(ask("POST", "/app/api/sign-in/request", { cookie: "e2e_scenario=signed-out", "x-medioo": "1" })).toEqual({
      status: 403,
      body: { reason: "FORBIDDEN_ORIGIN" },
    });
    expect(
      ask("POST", "/app/api/sign-in/request", { cookie: "e2e_scenario=signed-out", "x-medioo": "1", origin: ORIGIN }),
    ).toEqual({ status: 415, body: { reason: "UNSUPPORTED_MEDIA_TYPE" } });
  });

  it("aSignedInMutationStillMeetsCsrfBeforeTheRoute", () => {
    const cookie = "e2e_scenario=signed-in; baymax_session=e2e";
    expect(ask("POST", "/app/api/sign-out", { cookie })).toEqual({ status: 403, body: { reason: "MISSING_HEADER" } });
    // Past CSRF, an unmapped route is the scenario's 404.
    expect(
      ask("POST", "/app/api/sign-out", { cookie, "x-medioo": "1", origin: ORIGIN, "content-type": "application/json" }),
    ).toEqual({ status: 404, body: { reason: "NOT_FOUND" } });
  });
});

describe("stub Java answers for the query (plan W8)", () => {
  // A synthetic table, so the rule is judged on answers that cannot be confused with one another.
  const lit = (n: number) => ({ status: 200, body: { n } });
  const TABLE = {
    paged: {
      "GET /app/api/patients/{id}/timeline": {
        byQuery: [
          { when: { type: "lab" }, answer: lit(1) },
          { when: { cursor: "c2" }, answer: lit(2) },
          { when: { type: "rx", cursor: "c2" }, answer: lit(3) },
        ],
        otherwise: lit(0),
      },
    },
  };
  const get = (query: string) =>
    answer({
      method: "GET",
      path: "/app/api/patients/5e9edf6d-0a2a-4913-8675-9d07a4100002/timeline",
      query,
      headers: { cookie: "e2e_scenario=paged; baymax_session=e2e" },
      origin: ORIGIN,
      counters: new Map(),
      scenarios: TABLE,
    });

  it("theFirstEntryWhoseEveryParameterTheQueryCarriesAnswers", () => {
    expect(get("type=lab")).toEqual(lit(1));
    expect(get("cursor=c2")).toEqual(lit(2));
    // Both entries match `type=rx&cursor=c2` on cursor; the first listed wins, so order in the table is the rule.
    expect(get("cursor=c2&type=rx")).toEqual(lit(2));
    expect(get("type=lab&cursor=c2")).toEqual(lit(1));
  });

  it("aQueryNoEntryMatchesIsAnsweredByOtherwise", () => {
    // The dangerous direction: a parameter's value must match exactly, never as a prefix or by its presence alone.
    expect(get("")).toEqual(lit(0));
    expect(get("type=labx")).toEqual(lit(0));
    expect(get("type=")).toEqual(lit(0));
    expect(get("cursor=c")).toEqual(lit(0));
    expect(get("month=2026-09")).toEqual(lit(0));
  });
});
