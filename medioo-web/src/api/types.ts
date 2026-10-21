import type { CopyKey } from "@/copy";

/*
 * The /app/api bodies React reads, written from the fixtures Java's contract tests generate (fixtures/api/<area>/),
 * never from a guess. types.test.ts assigns every committed fixture of an area to its type, so `pnpm typecheck` fails
 * when a field the type names is dropped or retyped. For the NOW banner it also compares every object's exact key set
 * against lists the compiler holds equal to the type, so a field Java adds fails the test; for the documents it checks
 * at run time the literal values a JSON import widens to `string` (a gate, a level, a kind) against the unions below.
 * Fields are snake_case, as Java sends them.
 *
 * The one exception is MePatient: there is no generated fixtures/api/me/ yet, so nothing pins it (see its comment).
 */

/** A JSON import's view of `T`: every string literal (a union, a CopyKey) widened to `string`, as resolveJsonModule types it. */
export type Json<T> = T extends string
  ? string
  : T extends number
    ? number
    : T extends boolean
      ? boolean
      : T extends null
        ? null
        : T extends readonly (infer U)[]
          ? Json<U>[]
          : T extends object
            ? { [K in keyof T]: Json<T[K]> }
            : T;

// ---- GET /app/api/documents/{id} (PaperJson.java, B2) ----------------------------------------------------------------

export const PAPER_STATUSES = ["RECEIVED", "PROCESSING", "DONE", "NEEDS_RETAKE", "FAILED"] as const;
export type PaperStatus = (typeof PAPER_STATUSES)[number];
/** The statuses PaperJson.reading sends: a paper still being read, with nothing about a result. */
export const READING_STATUSES = ["RECEIVED", "PROCESSING"] as const satisfies readonly PaperStatus[];

/** GateState: RELEASED covers APPROVED. The wording is sent only for RELEASED. */
export const GATES = ["NONE", "RELEASED", "PENDING", "REJECTED", "FAILED_SAFETY"] as const;
export type Gate = (typeof GATES)[number];

/** Urgency.dbValue(). */
export const URGENCIES = ["routine", "this_week", "now"] as const;
export type UrgencyLevel = (typeof URGENCIES)[number];
/** A banner is sent only above routine. */
export const BANNER_LEVELS = ["this_week", "now"] as const satisfies readonly UrgencyLevel[];
export type BannerLevel = (typeof BANNER_LEVELS)[number];

/** PaperDisplay.kindOf, lower-cased. */
export const DISPLAY_KINDS = ["inside", "unknown", "outside", "critical"] as const;
export type DisplayKind = (typeof DISPLAY_KINDS)[number];
export const SIDES = ["high", "low"] as const;
export type Side = (typeof SIDES)[number];

/** A crop's href and its stored size; width and height are null together when the ledger row has none (D4b). */
export type CropJson = { src: string; width: number | null; height: number | null };

export type PaperValueJson = {
  id: string | null;
  name: string | null;
  value: string | null;
  unit: string | null;
  ref_low: string | null;
  ref_high: string | null;
  display_kind: DisplayKind;
  side: Side | null;
  chip_key: CopyKey;
  pin: number | null;
  in_now_block: boolean;
  crop: CropJson;
};

/** Only the fields as written (PaperDisplay.MEDICINE_FIELDS); a field the paper does not carry is absent. */
export type MedicineJson = {
  name?: string;
  dose_text?: string;
  frequency_text?: string;
  timing_text?: string;
  duration_text?: string;
  crop: CropJson;
};

export type FollowUpJson = { instruction: string | null; due_date: string | null; crop: CropJson };

/** Sent only on a NOW paper. `call_999` is the one switch for a tel:999 link (F7: a Bangladeshi phone only). */
export type NowJson = {
  notdoctor_key: CopyKey;
  flags_go_key: CopyKey;
  bring_key: CopyKey;
  call_999: boolean;
  remote_share: boolean;
};

type PaperHead = {
  id: string;
  patient: { id: string; name: string; age: number | null; name_possessive: string; name_objective: string };
  owner: boolean;
  viewer: { bangladesh: boolean };
  received_at: string | null;
  page_count: number;
  pages: { n: number; src: string }[];
};

/** A finished paper: DONE (the result), NEEDS_RETAKE (the retake) or FAILED. A key that does not apply is absent. */
export interface PaperResultJson extends PaperHead {
  status: Exclude<PaperStatus, (typeof READING_STATUSES)[number]>;
  document_type: string | null;
  printed_date: string | null;
  date: string;
  date_is_upload_day: boolean;
  date_uncertain: boolean;
  facility: string | null;
  urgency: UrgencyLevel | null;
  banner: { level: BannerLevel; line_key: CopyKey } | null;
  filed: boolean;
  reasons: string[];
  now?: NowJson;
  gate: Gate;
  /** Only when the gate is RELEASED. */
  wording?: string | null;
  /** Only when the gate is PENDING. */
  review_hours?: number;
  counts: { values: number; outside: number; line_key: CopyKey } | null;
  values: PaperValueJson[];
  medicines: MedicineJson[];
  follow_up: FollowUpJson[];
  doctor: Record<string, { text: string; crop: CropJson }[]>;
  unverified: { total: number; values: number; medicines: number; follow_up: number } | null;
  /** Only for NEEDS_RETAKE. */
  retake?: { reason_key: CopyKey; ordinal: number };
}

/**
 * A paper still being read (RECEIVED, PROCESSING): whose it is, when it came, its pages, nothing about a result. The
 * result's keys are `never` here, so a result body that drifted cannot pass as a reading one.
 */
export interface PaperReadingJson extends PaperHead {
  status: (typeof READING_STATUSES)[number];
  banner?: never;
  counts?: never;
  gate?: never;
  wording?: never;
  now?: never;
  values?: never;
  retake?: never;
}

export type PaperJson = PaperResultJson | PaperReadingJson;

/**
 * A paper still being read. A guard rather than `status === …` comparisons: each variant's status is a union of
 * literals, and TypeScript narrows a union only on a comparison that rules out a whole constituent.
 */
export function isReadingPaper(paper: PaperJson): paper is PaperReadingJson {
  return (READING_STATUSES as readonly string[]).includes(paper.status);
}

// ---- GET /app/api/now-banner (NowBannerApiHandler, B3) -------------------------------------------------------------

/**
 * One entry of the banner's `seen` list: what the viewer was shown, as the server keys an acknowledgement (DR-41
 * item 5). The client never builds or edits one; it sends the list back exactly as received.
 */
export type NowSeenJson = {
  document_id: string;
  urgency: string;
  reasons_digest: string;
  release_state: string;
};

/** One NOW paper the banner is about. `acked` is true when this viewer already acknowledged it (the "seen" button, DR-41 item 5). */
export type NowBannerDocumentJson = {
  document_id: string;
  patient_id: string | null;
  document_type: string;
  date: string;
  date_is_upload_day: boolean;
  date_uncertain: boolean;
  acked: boolean;
};

/**
 * GET /app/api/now-banner (plan B3, baymax NowBannerApiHandler; fixtures/api/now-banner/*.json). `show` is Java's
 * decision; `line_key` names the shipped urgency sentence (Part B decision 3); `call_999` is true only for a viewer in
 * Bangladesh; `seen` is the body of PUT /app/api/now-banner/ack, `{"seen": seen}`, as received.
 */
export type NowBannerJson = {
  show: boolean;
  line_key: string;
  call_999: boolean;
  documents: NowBannerDocumentJson[];
  seen: NowSeenJson[];
};

/**
 * One patient of GET /app/api/me (baymax MeApiHandler: PatientJson's fields, then the name forms and the red mark).
 * NOT pinned by a fixture: there is no generated fixtures/api/me/ yet (a MeApiContractTest is pending for W4/W5), so
 * these fields are read off MeApiHandler's source, in its order, and can drift unseen. Depend on the fields actually
 * read (NowBanner takes `Pick<MePatient, "id" | "name">`) until a fixture pins the rest.
 */
export type MePatient = {
  id: string;
  name: string;
  age: number | null;
  sex: string | null;
  owner: boolean;
  documents: number;
  last_document_at: string | null;
  name_possessive: string;
  name_objective: string;
  urgent: boolean;
};
