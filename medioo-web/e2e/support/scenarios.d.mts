// Types for scenarios.mjs (plain ESM, read by stub Java under `node` and by the specs).
export type Answer = { status: number; body: unknown };
/** The request dies on the wire, after the guard let it through (a phone losing its signal mid-press). */
export type Abort = { abort: "connectionreset" | "connectionclosed" | "failed" };
export type Value = string | Answer | Abort | Array<string | Answer | Abort>;
export const FIXTURES: string;
export const UNAUTHENTICATED: Answer;
export const SCENARIOS: Record<string, Record<string, Value>>;
export const PAGE_SCENARIOS: Record<string, string>;
export const PAGE_MARKERS: Record<string, string>;
export const LONGEST: Record<string, string>;
export function patientName(fixture: string): string;
export function numberShown(fixture: string): string;
export function longNameOf(name: string, table?: Record<string, Value>): string;
export function signedOut(name: string, table?: Record<string, Value>): boolean;
