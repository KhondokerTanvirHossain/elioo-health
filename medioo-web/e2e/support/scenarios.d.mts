// Types for scenarios.mjs (plain ESM, read by stub Java under `node` and by the specs).
export type Answer = { status: number; body: unknown };
export type Value = string | Answer | Array<string | Answer>;
export const FIXTURES: string;
export const UNAUTHENTICATED: Answer;
export const SCENARIOS: Record<string, Record<string, Value>>;
export const PAGE_SCENARIOS: Record<string, string>;
export const PAGE_MARKERS: Record<string, string>;
export const LONGEST: Record<string, string>;
export function patientName(fixture: string): string;
export function signedOut(name: string): boolean;
