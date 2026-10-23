"use client";

import { useState } from "react";
import { apiSend } from "@/api/browser";
import type { FamilyPatientJson, StoredFlagJson } from "@/api/types";
import { ToastRegion } from "@/components/Toast/ToastRegion";
// ToastRegion's look lives with the server half; a client caller imports the stylesheet itself (no copy in it).
import "@/components/Toast/Toast.css";

/** Every word the flags say, resolved by the server (family-words.ts): this island ships no copy. */
export type FlagWords = {
  /** `profile.flags`: the question, the group's name (the owner's form). */
  title: string;
  /** `flags.readonly.title`: a statement over a share member's read-only flags, who has nothing to tick (D30). */
  readonlyTitle: string;
  /** `flags.none`: a share member's view of a patient with no flag recorded. */
  none: string;
  /** `family.flags.hint`: tick only what a doctor said. */
  hint: string;
  /** `flag.other`: the free-text field's label. */
  other: string;
  /** `profile.save`. */
  save: string;
  /** `profile.saved`: the toast after Java saved them. */
  saved: string;
  /** `app.err.forbidden`: Java refused the save as VIEW_ONLY. */
  refused: string;
  /** `app.err.generic`: the save never reached Java, or Java refused it otherwise (too many, too long). */
  failed: string;
  /** The offered flags (`flags_offered`), each with its label resolved, in Java's order. */
  offered: { id: string; label: string }[];
  /** Each offered `label_key` resolved, for a stored flag Java names by key. */
  labels: Record<string, string>;
};

/** The longest flag Java stores (FamilyAccountService.normalizeFlags refuses one over 40 characters). */
const MAX_FLAG = 40;

/** A stored flag's words: its key's copy when Java named one, else the text the family typed, as written. */
const labelOf = (flag: StoredFlagJson, labels: Record<string, string>) =>
  flag.label_key === null ? flag.value : (labels[flag.label_key] ?? flag.value);

/**
 * A patient's long-term conditions (plan W10, B5): asked, never inferred. The owner ticks the offered ones, keeps or
 * unticks any the family typed (shown as typed, `label_key` null: Java says which is which), may type one more, and
 * saves the whole list with `PUT /app/api/patients/{id}/flags` (`{"chronic_flags": [...]}`, Q10: a typed flag is sent
 * back unchanged). Nothing stored is ever left off the form: a stored value that is not an offered id is a row of its
 * own, so a save cannot drop it unseen. A share member (`owner` false) gets the flags as text under a statement
 * (`flags.readonly.title`, never the owner's tick-box question), or `flags.none`, and no control (D30); the card
 * around it carries the one `patients.shared` note (PatientCard).
 * Java's answer is the saved patient, whose flags replace these; its refusal is said in words, never a crash. While a
 * save is under way nothing is disabled (a disabled Save would drop the focus to the page): Save is aria-disabled and
 * a second press or a tick does nothing, and the status region is mounted empty so its words are announced.
 */
export function ChronicFlags({
  patientId,
  owner,
  stored,
  words,
}: {
  patientId: string;
  owner: boolean;
  stored: StoredFlagJson[];
  words: FlagWords;
}) {
  const offeredIds = new Set(words.offered.map((o) => o.id));
  const [flags, setFlags] = useState<StoredFlagJson[]>(stored);
  const [values, setValues] = useState<string[]>(stored.map((f) => f.value));
  const [other, setOther] = useState("");
  const [sending, setSending] = useState(false);
  const [toast, setToast] = useState<string | null>(null);
  const [said, setSaid] = useState<string | null>(null);

  if (!owner) {
    return (
      <div className="family-flags">
        <p className="family-flags__title">{words.readonlyTitle}</p>
        {stored.length > 0 ? (
          <ul className="family-flags__list">
            {stored.map((f) => (
              <li key={f.value} className="family-flags__item" data-flag={f.value}>
                {labelOf(f, words.labels)}
              </li>
            ))}
          </ul>
        ) : (
          <p className="family-flags__none">{words.none}</p>
        )}
      </div>
    );
  }

  const toggle = (value: string, on: boolean) =>
    setValues((now) => (on ? (now.includes(value) ? now : [...now, value]) : now.filter((v) => v !== value)));

  async function save() {
    if (sending) return;
    const typed = other.trim();
    const chronic = typed !== "" && !values.includes(typed) ? [...values, typed] : values;
    setSending(true);
    setToast(null);
    setSaid(null);
    const result = await apiSend<FamilyPatientJson>("PUT", `/app/api/patients/${patientId}/flags`, { chronic_flags: chronic });
    if (result.kind === "signed-out") return;
    setSending(false);
    if (result.kind === "ok") {
      const saved = Array.isArray(result.body?.chronic_flags) ? result.body.chronic_flags : null;
      if (saved !== null) {
        setFlags(saved);
        setValues(saved.map((f) => f.value));
      } else {
        setValues(chronic);
      }
      setOther("");
      setToast(words.saved);
      return;
    }
    setSaid(result.kind === "refused" && result.reason === "VIEW_ONLY" ? words.refused : words.failed);
  }

  const extra = flags.filter((f) => !offeredIds.has(f.value));
  // The check box is the whole row (the 44 px target), drawn as the prototype's box and tick beside the words.
  const box = (value: string, label: string) => (
    <label key={value} className="family-flags__option">
      <input
        type="checkbox"
        className="family-flags__check"
        name="flag"
        value={value}
        checked={values.includes(value)}
        onChange={(e) => {
          if (!sending) toggle(value, e.currentTarget.checked);
        }}
      />
      <span className="family-flags__box" aria-hidden="true">
        <svg className="family-flags__tick" viewBox="0 0 24 24" focusable="false">
          <path d="M5 12.5l4.5 4.5L19 7.5" />
        </svg>
      </span>
      <span className="family-flags__label">{label}</span>
    </label>
  );
  const otherId = `family-flags-other-${patientId}`;

  return (
    <fieldset className="family-flags" aria-busy={sending}>
      <legend className="family-flags__title">{words.title}</legend>
      <p className="family-flags__hint">{words.hint}</p>
      <div className="family-flags__options">
        {words.offered.map((o) => box(o.id, o.label))}
        {extra.map((f) => box(f.value, labelOf(f, words.labels)))}
      </div>
      <label className="family-flags__other-label" htmlFor={otherId}>
        {words.other}
      </label>
      <input
        id={otherId}
        type="text"
        className="family-flags__input"
        maxLength={MAX_FLAG}
        value={other}
        readOnly={sending}
        onChange={(e) => setOther(e.currentTarget.value)}
      />
      <button type="button" className="family-flags__save" aria-disabled={sending} onClick={save}>
        {words.save}
      </button>
      <p className="family-flags__status" role="status" aria-live="polite" aria-atomic="true">
        {said}
      </p>
      <ToastRegion message={toast} />
    </fieldset>
  );
}
