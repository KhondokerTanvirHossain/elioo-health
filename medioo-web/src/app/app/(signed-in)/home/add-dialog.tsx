import "server-only";
import type { ReactNode } from "react";
import { javaGet, signedIn } from "@/api/java";
import type { UploadOptionsJson } from "@/api/types";
import { DeskUploadDialog } from "@/components/DeskUpload/desk-load";
import { uploadWords } from "../add/upload-words";
import type { ShellContext } from "../shell";

/*
 * The add sheet's dialog on the talk-and-papers screen (plan desktop-parity U1), in a module of its own: only the home
 * page imports it, so a paper link's page (which draws the same screen through desk.tsx) carries none of its loader
 * (DR-42's route ceiling, DR-51).
 */
/** This screen's address without `add`: where the dialog's close and Escape go (the same paper, the same panel). */
export function withoutAdd(here: string): string {
  const url = new URL(here, "http://medioo.invalid");
  url.searchParams.delete("add");
  const q = url.searchParams.toString();
  return q === "" ? url.pathname : `${url.pathname}?${q}`;
}

/**
 * The add sheet as a dialog over the talk-and-papers screen (plan desktop-parity U1; desktop.html `uploadDlg`), when the
 * address asks for it (`?add=1`, deskAddHref: the paperclip, the first visit's pick, a drop anywhere). It is the add
 * page's own read and words (GET /app/api/upload-options, `?patient=` the patient in view, Java deciding whom it opens
 * on; upload-words.ts) and today's UploadSheet, so the same API, refusals and rules; only the frame is the design's.
 * Not asked: nothing, and nothing is read.
 */
export async function addDialog(ctx: ShellContext, here: string, open: boolean): Promise<ReactNode> {
  if (!open) return undefined;
  const { lang, preview, patient } = ctx;
  const asked = patient?.id ?? null;
  const path = asked === null ? "/app/api/upload-options" : `/app/api/upload-options?patient=${encodeURIComponent(asked)}`;
  const options = signedIn(await javaGet<UploadOptionsJson>(path), here);
  const words = uploadWords(lang, options);
  return (
    <DeskUploadDialog label={words.title} options={options} words={words} preview={preview} asked={asked} replaces={null} closeHref={withoutAdd(here)} />
  );
}
