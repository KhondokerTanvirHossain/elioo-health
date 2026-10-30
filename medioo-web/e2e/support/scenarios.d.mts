// Types for scenarios.mjs (plain ESM, read by stub Java under `node` and by the specs).
export type Answer = { status: number; body: unknown };
/** The request dies on the wire, after the guard let it through (a phone losing its signal mid-press). */
export type Abort = { abort: "connectionreset" | "connectionclosed" | "failed" };
/** The network goes quiet: the request is never answered (no RST, no error), as a phone losing its signal usually is. */
export type Hold = { hold: true };
/** An answer derived from the request (the path and its parsed JSON body), as Java's would be. */
export type Derived = (request: { path: string; body: unknown }) => Answer;
export type Plain = string | Answer | Abort | Hold | Derived | Array<string | Answer | Abort | Hold | Derived>;
/** Answered for the request's query (java-stub.mjs forQuery): the first entry whose `when` the query carries, else `otherwise`. */
export type ByQuery = { byQuery: Array<{ when: Record<string, string>; answer: Plain }>; otherwise: Plain };
export type Value = Plain | ByQuery;
export function flagsSaved(fixture: string): Derived;
export const FIXTURES: string;
export const UNAUTHENTICATED: Answer;
export const SCENARIOS: Record<string, Record<string, Value>>;
export const PAGE_SCENARIOS: Record<string, string>;
export const PAGE_MARKERS: Record<string, string>;
export const PAGE_PATHS: Record<string, string>;
export function familyPatientId(fixture: string, index?: number): string;
export const LONGEST: Record<string, string>;
export function patientName(fixture: string): string;
export function numberShown(fixture: string): string;
export function cursorOf(fixture: string): string;
export function longNameOf(name: string, table?: Record<string, Value>): string;
export function signedOut(name: string, table?: Record<string, Value>): boolean;
export function bannerFor(fixture: string, meFixture: string, index?: number): Answer;
export function reRead(fixture: string, meFixture?: string): Answer;
export function messagesOnly(fixture: string): Answer;
export function withAsks(fixture: string): ByQuery;
export function withNow(name: string, table?: Record<string, Value>): Record<string, Value>;
export function withNowName(name: string): string;
export function withStaleNowName(name: string): string;
