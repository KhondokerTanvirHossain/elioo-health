"use client";

import { useState } from "react";
import { SheetDialog } from "@/components/Sheet/SheetDialog";
// SheetDialog's look lives with the server half; a client caller imports the stylesheet itself (no copy in it).
import "@/components/Sheet/Sheet.css";
import { UrgentMark } from "@/components/safety/UrgentMark/UrgentMark";
import { choosePatient, samePageFor } from "./choose";
import "./PatientSwitcher.css";

/** Every word the switcher says, resolved by the server (shell-words.ts): this island ships no copy. */
export type SwitcherWords = {
  /** `switch.label` with the current patient's name: the button's text. */
  label: string;
  /** `switch.title`: the sheet's title and accessible name. */
  title: string;
  /** `sheet.close`. */
  closeLabel: string;
  /** `app.err.notfound`: Java refused the choice (a patient this family no longer sees). */
  refused: string;
  /** `app.err.generic`: the choice never reached Java. */
  failed: string;
  /** Whose papers this family may see, in GET /app/api/me's order; `urgentWord` (`attn.strip`) only for an urgent one. */
  patients: { id: string; name: string; urgentWord?: string }[];
};

/**
 * The patient switcher (plan W4): the current patient's name in a button that opens a sheet (SheetDialog, the client
 * half: the words arrive resolved) listing every patient this family may see, each with its red mark when Java says it
 * is urgent. A choice is remembered by Java first (PUT /app/api/active-patient) and then the same tab opens with
 * `?patient=`, inside the preview when this is a preview page; Java's refusal is said in a status line and nothing
 * moves. Choosing the patient already shown just closes the sheet. A 401 goes to sign-in holding the page (apiSend).
 *
 * `preview` is carried for the record of which page this is; the path the browser is on already holds the prefix.
 */
export function PatientSwitcher({ words, current }: { words: SwitcherWords; current: string; preview: boolean }) {
  const [open, setOpen] = useState(false);
  const [sending, setSending] = useState(false);
  const [said, setSaid] = useState<string | null>(null);

  async function choose(id: string) {
    if (sending) return;
    if (id === current) {
      setOpen(false);
      return;
    }
    setSending(true);
    setSaid(null);
    const outcome = await choosePatient(id, samePageFor(id));
    if (outcome === "going" || outcome === "signed-out") return;
    setSending(false);
    setSaid(outcome === "refused" ? words.refused : words.failed);
  }

  return (
    <div className="patient-switcher">
      <button type="button" className="patient-switcher__button" aria-haspopup="dialog" onClick={() => setOpen(true)}>
        <span className="patient-switcher__label">{words.label}</span>
        <svg className="patient-switcher__chevron" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
          <path d="M7 10l5 5 5-5" />
        </svg>
      </button>
      <SheetDialog title={words.title} closeLabel={words.closeLabel} open={open} onClose={() => setOpen(false)}>
        <ul className="patient-switcher__list">
          {words.patients.map((p) => (
            <li key={p.id}>
              <button
                type="button"
                className="patient-switcher__option"
                data-patient={p.id}
                aria-current={p.id === current ? "true" : undefined}
                disabled={sending}
                onClick={() => choose(p.id)}
              >
                <span className="patient-switcher__name">{p.name}</span>
                {p.urgentWord !== undefined && <UrgentMark word={p.urgentWord} />}
              </button>
            </li>
          ))}
        </ul>
        {said !== null && (
          <p className="patient-switcher__status" role="status">
            {said}
          </p>
        )}
      </SheetDialog>
    </div>
  );
}
