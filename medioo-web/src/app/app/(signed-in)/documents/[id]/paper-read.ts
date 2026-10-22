import "server-only";
import { notFound } from "next/navigation";
import { cache } from "react";
import { javaGet, type JavaAnswer } from "@/api/java";
import type { PaperJson, ValueSheetJson } from "@/api/types";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** An id from the address is a UUID or names no paper: never a path segment of its own (`..`, `a/b`) in Java's URL. */
function uuid(id: string): string {
  if (!UUID.test(id)) notFound();
  return id.toLowerCase();
}

/**
 * The paper, read once per request (plan W6, Review Focus 4): GET /app/api/documents/{id} through javaGet (the preview
 * gate, the cookie and nothing else; one read records one DOCUMENT_VIEW in Java), cached for the request so the page and
 * its metadata share the one read. An id that is not a UUID is the not-found page before anything is read.
 */
export const paperRead = cache((id: string): Promise<JavaAnswer<PaperJson>> => javaGet<PaperJson>(`/app/api/documents/${uuid(id)}`));

/**
 * One value's sheet (W6-api): GET /app/api/documents/{id}/values/{obs}, the sheet's one read (its own DOCUMENT_VIEW).
 * The page never reads the paper too: the sheet carries the paper's head.
 */
export const valueSheetRead = cache(
  (id: string, obs: string): Promise<JavaAnswer<ValueSheetJson>> =>
    javaGet<ValueSheetJson>(`/app/api/documents/${uuid(id)}/values/${uuid(obs)}`),
);
