import { describe, expect, it } from "vitest";
import { localPathOr } from "./local-path";

/*
 * The same rule as Java's LocalPath (baymax web.adapter.in.ui.LocalPath), case for case from its LocalPathTest: a
 * `back` is a page of this app or the landing, never another origin. Both directions per row: a page Java draws as a
 * `back` passes as written, and every spelling of another origin falls back.
 */
const FALLBACK = "/app/home";

describe("localPathOr", () => {
  it.each([
    ["/app/timeline?patient=5e9edf6d-0a2a-4913-8675-9d07a4100002", "/app/timeline?patient=5e9edf6d-0a2a-4913-8675-9d07a4100002"],
    ["/app/documents/5e9edf6d-0a2a-4913-8675-9d07a4100003", "/app/documents/5e9edf6d-0a2a-4913-8675-9d07a4100003"],
    ["/app/home", "/app/home"],
    ["/app/", "/app/"],
    ["/", "/"],
    ["//evil.example/x", FALLBACK],
    ["//evil.example", FALLBACK],
    ["/\\evil.example", FALLBACK],
    ["/\\\\evil.example", FALLBACK],
    ["/%5Cevil.example", FALLBACK],
    ["/%5cevil.example", FALLBACK],
    ["/%2F%2Fevil.example", FALLBACK],
    ["https://evil.example", FALLBACK],
    ["https://evil.example/app/home", FALLBACK],
    ["javascript:alert(1)", FALLBACK],
    ["/api/v1/baymax/me", FALLBACK],
    ["/application", FALLBACK],
    ["app/home", FALLBACK],
  ])("a page of this app passes, another origin falls back: %s -> %s", (raw, expected) => {
    expect(localPathOr(raw, FALLBACK)).toBe(expected);
  });

  it.each([
    ["/app/../api/v1/baymax/me", FALLBACK],
    ["/app/..", FALLBACK],
    ["/app/./timeline", FALLBACK],
    ["/app/timeline/..", FALLBACK],
    ["/app/%2e%2e/api/v1/baymax/me", FALLBACK],
    ["/app/%2E%2E/api/v1/baymax/me", FALLBACK],
    ["/app/.%2e/api", FALLBACK],
    ["/app/%2e/timeline", FALLBACK],
    ["/app/timeline.old", "/app/timeline.old"],
    ["/app/..timeline", "/app/..timeline"],
    ["/app/timeline?patient=x..y", "/app/timeline?patient=x..y"],
    ["/app/timeline?back=../x", "/app/timeline?back=../x"],
  ])("a dot segment the browser resolves out of /app falls back, a dot inside a name does not: %s -> %s", (raw, expected) => {
    expect(localPathOr(raw, FALLBACK)).toBe(expected);
  });

  it.each([
    ["/app/x%ZZ", FALLBACK],
    ["/app/x%", FALLBACK],
    ["/app/%5Cx", FALLBACK],
    ["/app/%5c%5cevil.example", FALLBACK],
    ["/app/timeline?patient=%5C", FALLBACK],
    ["/app/timeline?patient=%35e9", "/app/timeline?patient=%35e9"],
  ])("a malformed escape or an escaped backslash falls back, never throws: %s -> %s", (raw, expected) => {
    expect(localPathOr(raw, FALLBACK)).toBe(expected);
  });

  it("nothing given is the fallback", () => {
    expect(localPathOr(null, "/")).toBe("/");
    expect(localPathOr("", "/")).toBe("/");
  });
});
