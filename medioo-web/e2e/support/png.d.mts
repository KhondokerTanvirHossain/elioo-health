// Types for png.mjs (plain ESM, so `node` runs it as stub Java's helper without a build step).
type Row = { crop?: { src?: string; width?: number | null; height?: number | null } } | null | undefined;
export function cropKey(row: Row): string;
export function hashWidth(key: string): number;
export function expectedWidth(row: Row): number;
export function png(width: number, height: number): Buffer;
export function pngFor(row: Row): Buffer;
