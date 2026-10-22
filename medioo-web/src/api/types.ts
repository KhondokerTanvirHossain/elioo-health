import type { CopyKey } from "@/copy";

/*
 * The /app/api bodies React reads, written from the fixtures Java's contract tests generate (fixtures/api/<area>/),
 * never from a guess. types.test.ts assigns every committed fixture of an area to its type, so `pnpm typecheck` fails
 * when a field the type names is dropped or retyped. For the NOW banner it also compares every object's exact key set
 * against lists the compiler holds equal to the type, so a field Java adds fails the test; for the documents it checks
 * at run time the literal values a JSON import widens to `string` (a gate, a level, a kind) against the unions below.
 * Fields are snake_case, as Java sends them.
 *
 * GET /app/api/me is pinned the same way (MeJson, plan W4).
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
  /**
   * Only for NEEDS_RETAKE. `replaced`: the family already sent this paper again (another paper names it as the one it
   * replaces and did not fail), so the card asks for nothing more. Java decides it; nothing here derives it.
   */
  retake?: { reason_key: CopyKey; ordinal: number; replaced: boolean };
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
 * Pinned by the bodies MeApiContractTest writes (fixtures/api/me/*.json; types.test.ts › everyMeFixtureIsAMeJson
 * compares every patient key for key). `urgent` is Java's: the patient's attention stack holds a NOW paper.
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

/**
 * GET /app/api/me (plan B1, W4; fixtures/api/me/*.json): who is signed in, the patients this account may see, whose
 * papers Java remembers in view (`active_patient_id`: the `baymax_patient` cookie if still visible, else the only
 * patient, else null — ActivePatient.remembered), and whether this family previews the Next screens.
 */
export type MeJson = {
  family: { id: string; owner_name: string };
  patients: MePatient[];
  active_patient_id: string | null;
  preview: boolean;
};

// ---- GET /app/api/patients/{id}/calendar (B3) and /labs (B6), plan W2e-b ----------------------------------------------
//
// Strings Java owns the vocabulary of here (a chip, a kind, a side) stay `string`: the components map them through one
// function each and fail closed on a value they do not know. types.test.ts compares every object of these fixtures key
// for key with the lists there, so a field Java adds, drops or renames fails at test time too.

/** An image the API names: a crop or a page, served by Java (ImageRefs). */
export type ImageJson = { src: string };

/** A paper filed on a calendar day (B3, TimelineJson.calendar): `chip` is the stored urgency ("now", …) or null. */
export type CalendarPaperJson = { document_id: string; document_type: string; chip: string | null };

/** A doctor's date due on a calendar day, the instruction as written on the prescription and its crop. */
export type CalendarFollowUpJson = { document_id: string; instruction: string; crop: ImageJson };

/** One day of `GET /app/api/patients/{id}/calendar`: only the days that hold a paper or a follow-up are sent. */
export type CalendarDayJson = { date: string; papers: CalendarPaperJson[]; follow_ups: CalendarFollowUpJson[] };

/** `GET /app/api/patients/{id}/calendar?month=YYYY-MM` (B3). */
export type CalendarJson = { month: string; days: CalendarDayJson[] };

// ---- GET /app/api/patients/{id}/timeline, /follow-ups and /attention (B3), plan W8 ------------------------------------

/** What a timeline row counts (C2's read model): the paper's values, how many are outside, its medicines, its dates. */
export type TimelineCountsJson = { values: number; outside: number; medicines: number; follow_ups: number; unverified: number };

/**
 * One paper of the timeline (B3, TimelineJson.entry), in the read model's order. `month` is the key the page groups by,
 * Java's `YearMonth.from(date)` (D2's filing day), never parsed out of `date` here; `chip` is the stored urgency
 * ("now", "this_week") or null; `retake_reason_key` names the retake's line for a NEEDS_RETAKE paper, else null.
 */
export type TimelineEntryJson = {
  document_id: string;
  document_type: string | null;
  date: string;
  date_is_upload_day: boolean;
  date_uncertain: boolean;
  month: string;
  status: string;
  chip: string | null;
  counts: TimelineCountsJson;
  facility: string | null;
  page_one: ImageJson;
  retake_reason_key: string | null;
  review_pending: boolean;
};

/** `GET /app/api/patients/{id}/timeline?cursor=&type=lab|rx` (B3): one page, and the cursor of the next (null at the end). */
export type TimelineJson = { entries: TimelineEntryJson[]; next_cursor: string | null };

/** One doctor's date (B3, TimelineJson.followUps): the instruction as written, its day, Java's `overdue`, its crop. */
export type FollowUpItemJson = { document_id: string; instruction: string; due_date: string; overdue: boolean; crop: ImageJson };

/** `GET /app/api/patients/{id}/follow-ups` (B3): soonest first, inside Java's window. */
export type FollowUpsJson = { items: FollowUpItemJson[] };

/** An urgent paper of the attention stack (B3): a stored NOW or THIS_WEEK, with the day it is filed under. */
export type AttentionUrgentJson = {
  kind: "urgent";
  document_id: string;
  document_type: string | null;
  level: string;
  date: string;
  date_is_upload_day: boolean;
  date_uncertain: boolean;
  received_at: string;
  action_key: string;
};

/** A paper to send again (B3): the day it came, and the line Java names. */
export type AttentionRetakeJson = {
  kind: "retake";
  document_id: string;
  date: string;
  date_is_upload_day: boolean;
  received_at: string;
  title_key: string;
  action_key: string;
};

/** A doctor's date due soon (B3): the instruction as written and its crop. */
export type AttentionFollowUpJson = {
  kind: "follow_up";
  document_id: string;
  instruction: string;
  due_date: string;
  crop: ImageJson;
  title_key: string;
  action_key: string;
};

/** A paper still being read (B3). */
export type AttentionReadingJson = {
  kind: "reading";
  document_id: string;
  date: string;
  date_is_upload_day: boolean;
  received_at: string;
  title_key: string;
  action_key: string;
};

export type AttentionItemJson = AttentionUrgentJson | AttentionRetakeJson | AttentionFollowUpJson | AttentionReadingJson;

/** `GET /app/api/patients/{id}/attention` (B3): the stack in Java's order; the papers page shows its urgent items. */
export type AttentionJson = { items: AttentionItemJson[] };

/**
 * One reading of a lab series (B6, HealthJson): the value as printed, the paper's filing day, the kind and side
 * PaperDisplay.kindOf drew it with (`chipKindOf` turns them into a chip), the chip key, and the value's own crop.
 *
 * `ref_low`, `ref_high` and `ref_text` (PO ruling R3, DR-48; Java's PrintedBand and PaperDisplay.drawnBand) are the
 * range stored for THIS paper's reading, read only off its own stored extraction, never a reference table's and never a
 * neighbouring point's. All three are null when the paper printed none — never a default. Ranges differ from point to
 * point: two labs print two ranges for one marker.
 *
 * - `ref_low` / `ref_high` are PARSED NUMBERS, unlike `PaperJson` values' `ref_low` / `ref_high` of the same name, which
 *   are the stored strings. Java sends a number only where the picture agrees with this point's chip, by the paper
 *   page's pin rule, so React never judges it:
 *   - both set: a two-sided range that agrees with `kind` (an `inside` value in it; an `outside`/`critical` value
 *     beyond it on `side`). Only then may the chart draw a FILLED pale band.
 *   - one set: a one-sided bound (`<200` is `ref_high` 200), sent only beside an `outside`/`critical` value beyond it
 *     on `side`. It may be drawn only as an UNFILLED edge line labelled with `ref_text`, never as a region that
 *     contains a point. A one-sided range is never INSIDE (D35).
 *   - both null with `ref_text` set: draw nothing. The stored text cannot be placed (`3.9-6.1` in one column,
 *     `Up to 200`, a lone bare number), or the band would contradict the chip (`unknown` is never banded; a `critical`
 *     by threshold inside its printed range is not).
 * - `ref_text` is NOT a transcription of the paper. One stored side, or a whole range stored in one column, is the
 *   stored string unchanged; two stored sides are joined BY JAVA with an en dash (`59–104`), whatever the paper's own
 *   characters were (`59 - 104`). Use it only as the band's accessible label beside the value's crop, never as quoted
 *   paper text and never in place of the crop. Showing it to families as text waits for the PO's acceptance of that
 *   form (PR 143).
 */
export type TrendPointJson = {
  document_id: string;
  observation_id: string;
  value: string;
  date: string;
  date_is_upload_day: boolean;
  date_uncertain: boolean;
  kind: string;
  side: string | null;
  chip_key: string;
  crop: ImageJson;
  ref_low: number | null;
  ref_high: number | null;
  ref_text: string | null;
};

/** One marker's readings across papers, newest first, under the newest point's name and unit. */
export type LabSeriesJson = { canonical_name: string; name: string; unit: string | null; points: TrendPointJson[] };

/** A blood-pressure reading: the two halves read off one OCR line, each its own point and crop. */
export type BpPointJson = {
  document_id: string;
  date: string;
  date_is_upload_day: boolean;
  date_uncertain: boolean;
  systolic: TrendPointJson;
  diastolic: TrendPointJson;
};

/** `GET /app/api/patients/{id}/labs` (B6). */
export type LabsJson = { series: LabSeriesJson[]; bp: { points: BpPointJson[] } };

// ---- GET /app/api/upload-options, POST /app/api/uploads (UploadApiHandler, B4b; plan W7) -----------------------------

/** A patient on the add sheet: PatientJson's fields, then the name forms in the viewer's language (UiCopy.poss/obj). */
export type UploadPatientJson = {
  id: string;
  name: string;
  age: number | null;
  sex: string | null;
  owner: boolean;
  documents: number;
  last_document_at: string | null;
  name_possessive: string;
  name_objective: string;
};

/**
 * `GET /app/api/upload-options` (fixtures/api/upload-options/*.json): whom the viewer may send for, whom they may only
 * see, the one patient to preselect when there is nobody else it could be (never a guess), whose paper the page opens
 * on (`?patient=` passed through, judged by Java), this month's allowance (a
 * paying family: `limited` false and every number null), and every limit Java refuses a press by. A refusal's copy
 * reads its numbers from here, never from the error body.
 */
export type UploadOptionsJson = {
  can_send: UploadPatientJson[];
  view_only: UploadPatientJson[];
  preselected_patient_id: string | null;
  /**
   * Whose paper the sheet opens on (plan W7 review, additive): Java's one rule, `UploadOptions.chosen`, the one the HTML
   * sheet checks its chip by, over the page's own `?patient=`. The sheet renders it and derives nothing.
   */
  chosen_patient_id: string | null;
  allowance: { limited: boolean; max: number | null; remaining: number | null; resets_on: string | null };
  limits: {
    max_pages: number;
    max_files: number;
    max_file_bytes: number;
    max_request_bytes: number;
    downscale_max_edge: number;
    downscale_quality: number;
    pass_through_bytes: number;
  };
};

/** `POST /app/api/uploads` answered (fixtures/api/uploads/*.json): the press's papers, and where the sheet goes next. */
export type UploadAnswerJson = { documents: { id: string }[]; next: string };

// ---- GET /app/api/patients/{id}/thread (W5-api, ThreadJson.java) -------------------------------------------------------

/**
 * The outbound kinds V23's `outbound_message_kind_check` allows, `failure` included (ThreadApiContractTest derives the
 * same set from the constraint). A message is listed only once the provider accepted it, and only for the viewer's own
 * account (S4, S10, S11).
 */
export const MESSAGE_KINDS = ["explanation", "detail", "retake", "nudge", "failure"] as const;
export type MessageKind = (typeof MESSAGE_KINDS)[number];

/** A paper in the thread: `at` is its `received_at`, `card` the body GET /app/api/documents/{id} sends for it. */
export type ThreadPaperJson = { kind: "paper"; at: string; card: PaperJson };

/** A message WhatsApp delivered: `body` is the text as sent, `document_id` the paper it is about (null for a nudge). */
export type ThreadMessageJson = {
  kind: "message";
  at: string;
  message_kind: MessageKind;
  document_id: string | null;
  body: string;
};

export type ThreadItemJson = ThreadPaperJson | ThreadMessageJson;

/** `GET /app/api/patients/{id}/thread?cursor=` (W5-api): newest first, 20 a page. */
export type ThreadJson = { items: ThreadItemJson[]; next_cursor: string | null };

// ---- GET /app/api/patients/{id}/attention (B3, AttentionApiHandler.card) -----------------------------------------------

/** AttentionItem's four kinds. */
export const ATTENTION_KINDS = ["urgent", "retake", "follow_up", "reading"] as const;
export type AttentionKind = (typeof ATTENTION_KINDS)[number];

/** A NOW or THIS_WEEK paper: `level` is the stored urgency, the one source of its band and its line. */
export type AttentionUrgentJson = {
  kind: "urgent";
  document_id: string;
  level: BannerLevel;
  document_type: string | null;
  date: string;
  date_is_upload_day: boolean;
  date_uncertain: boolean;
  received_at: string;
  action_key: CopyKey;
};

/** A paper to photograph again. */
export type AttentionRetakeJson = {
  kind: "retake";
  document_id: string;
  date: string;
  date_is_upload_day: boolean;
  received_at: string;
  title_key: CopyKey;
  action_key: CopyKey;
};

/** The doctor's date, the instruction as written on the prescription, and its crop (principle 6). */
export type AttentionFollowUpJson = {
  kind: "follow_up";
  document_id: string;
  instruction: string;
  due_date: string;
  crop: ImageJson;
  title_key: CopyKey;
  action_key: CopyKey;
};

/** A paper still being read. */
export type AttentionReadingJson = {
  kind: "reading";
  document_id: string;
  date: string;
  date_is_upload_day: boolean;
  received_at: string;
  title_key: CopyKey;
  action_key: CopyKey;
};

export type AttentionItemJson = AttentionUrgentJson | AttentionRetakeJson | AttentionFollowUpJson | AttentionReadingJson;

/** `GET /app/api/patients/{id}/attention` (B3): the stack in Java's order; an empty stack is silence. */
export type AttentionJson = { items: AttentionItemJson[] };
