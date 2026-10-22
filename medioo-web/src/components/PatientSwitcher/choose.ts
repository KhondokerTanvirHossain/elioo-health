import { apiSend } from "@/api/browser";

/** How a choice ended: the page is leaving, Java refused it, it never reached Java, or the session had ended. */
export type ChoiceOutcome = "going" | "refused" | "failed" | "signed-out";

/**
 * Remembers whose papers to show (`PUT /app/api/active-patient`, the cookie Java's HTML pages use too) and, once Java
 * accepted it, opens `target`. A refusal (a patient this family no longer sees is Java's 404) or a send that failed goes
 * nowhere: the caller says so. A 401 is apiSend's: it has already gone to sign-in, holding this page. Shared by the
 * switcher and the chooser, so both remember a choice the same way. No copy: the callers hold their words.
 */
export async function choosePatient(patientId: string, target: string): Promise<ChoiceOutcome> {
  const result = await apiSend("PUT", "/app/api/active-patient", { patient_id: patientId });
  if (result.kind === "ok") {
    location.assign(target);
    return "going";
  }
  if (result.kind === "signed-out") return "signed-out";
  return result.kind === "refused" ? "refused" : "failed";
}

/** The same page with the choice in its address, inside the preview when this is a preview page (the path keeps it). */
export function samePageFor(patientId: string): string {
  return `${location.pathname}?patient=${encodeURIComponent(patientId)}`;
}
