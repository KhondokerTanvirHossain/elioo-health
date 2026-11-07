"use client";

import { useState, type MouseEvent, type ReactNode } from "react";
import { choosePatient, samePageFor } from "@/components/PatientSwitcher/choose";

/**
 * One name in the chooser (plan W4): a plain link to `?patient=<id>`, which opens that patient's papers without
 * JavaScript. With JavaScript the press first has Java remember the choice (PUT /app/api/active-patient), so the next
 * tab does not ask again, then goes. A refusal or a failed send is said under the name (`refused`, `failed`: resolved by
 * the server parent) and goes nowhere; a 401 goes to sign-in holding this page. `className` and `data` let another
 * list draw the same link as its own row (the desktop sidebar's family, plan desktop-parity Task 5); `current` marks
 * the patient in view (`aria-current="page"`).
 */
export function ChooseLink({
  patientId,
  refused,
  failed,
  children,
  className = "patient-chooser__link",
  statusClassName = "patient-chooser__status",
  current = false,
}: {
  patientId: string;
  refused: string;
  failed: string;
  children: ReactNode;
  className?: string;
  statusClassName?: string;
  current?: boolean;
}) {
  const [said, setSaid] = useState<string | null>(null);
  const [sending, setSending] = useState(false);

  async function onClick(e: MouseEvent<HTMLAnchorElement>) {
    // A press that opens a new tab or window is the browser's: the link does what it says.
    if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
    e.preventDefault();
    if (sending) return;
    setSending(true);
    setSaid(null);
    const outcome = await choosePatient(patientId, samePageFor(patientId));
    if (outcome === "going" || outcome === "signed-out") return;
    setSending(false);
    setSaid(outcome === "refused" ? refused : failed);
  }

  return (
    <>
      <a
        className={className}
        href={`?patient=${encodeURIComponent(patientId)}`}
        onClick={onClick}
        aria-busy={sending || undefined}
        aria-current={current ? "page" : undefined}
        data-patient={patientId}
      >
        {children}
      </a>
      {said !== null && (
        <p className={statusClassName} role="status">
          {said}
        </p>
      )}
    </>
  );
}
