// Types for encode-proxy.mjs (plain ESM: `node e2e/support/encode-proxy.mjs` runs the measuring proxy with no build step).
import type { Server } from "node:http";
export function encodesType(contentType: string | undefined): boolean;
export function chooseEncoding(acceptEncoding: string | undefined): "zstd" | "gzip" | null;
export function startEncodeProxy(options: { upstream: string }): Server;
