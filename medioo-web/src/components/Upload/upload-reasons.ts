import type { UploadOptionsJson } from "@/api/types";
import { formatDate, type CopyKey, type Lang } from "@/copy";

/*
 * Every refusal of `POST /app/api/uploads`, by the name Java sends (ApiReason), and the copy key the sheet says it with
 * (plan W7). Read on the server only, by upload-words.ts, which resolves each into words with the numbers the options
 * carry, so the browser turns no key into words. The keys are the HTML sheet's own (UploadUiHandler.REFUSALS), so the
 * two sheets say a refusal alike. The list of names is Java's: upload-reasons.test.ts holds this map equal to
 * fixtures/api/uploads/refusals.json, which UploadApiContractTest writes from the upload path's own source.
 */
export const UPLOAD_REASON_COPY: Record<string, CopyKey> = {
  CHOOSE_PATIENT: "add.choose",
  EMPTY_FILE: "upload.err.empty_file",
  FREE_TIER_DOCUMENTS: "add.cap",
  NO_FILE: "upload.err.no_file",
  TOO_LARGE: "upload.err.too_large",
  TOO_MANY_FILES: "upload.err.too_many_files",
  TOO_MANY_PAGES: "upload.err.too_many_pages",
  UNREADABLE_PDF: "upload.err.pdf",
  UNSUPPORTED_FILE: "upload.err.unsupported",
  UPLOAD_KEY_REUSED: "upload.err.key_reused",
  VIEW_ONLY: "add.viewonly",
};

/**
 * The numbers a refusal's line takes, from the options (never from the error body, which is only a name): the page cap,
 * the file cap, and for the allowance its size and the day it resets, as UiCopy.dateText writes a date.
 */
export function reasonArgs(key: CopyKey, options: UploadOptionsJson, lang: Lang): Array<string | number> {
  switch (key) {
    case "upload.err.too_many_pages":
      return [options.limits.max_pages];
    case "upload.err.too_many_files":
      return [options.limits.max_files];
    case "add.cap":
      return [options.allowance.max ?? 0, options.allowance.resets_on === null ? "" : formatDate(lang, options.allowance.resets_on)];
    default:
      return [];
  }
}
