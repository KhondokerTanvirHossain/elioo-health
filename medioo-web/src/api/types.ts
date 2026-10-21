/*
 * The /app/api bodies React reads, written from the fixtures Java's contract tests generate (fixtures/api/<area>/),
 * never from a guess. types.test.ts assigns every committed fixture of an area to its type, so `pnpm typecheck` fails
 * when a field the type names is dropped or retyped, and compares every object's exact key set against lists the
 * compiler holds equal to the type, so a field Java adds fails the test. Fields are snake_case, as Java sends them.
 *
 * The one exception is MePatient: there is no generated fixtures/api/me/ yet, so nothing pins it (see its comment).
 */

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
