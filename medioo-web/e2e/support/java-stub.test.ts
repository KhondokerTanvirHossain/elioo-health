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
