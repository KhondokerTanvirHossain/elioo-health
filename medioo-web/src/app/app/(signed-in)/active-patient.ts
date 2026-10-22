import type { MeJson, MePatient } from "@/api/types";

/** Whose papers a signed-in page shows: one patient, the chooser, nobody (a family with none), or Java's 404. */
export type ActiveView =
  | { kind: "patient"; patient: MePatient }
  | { kind: "choose" }
  | { kind: "none" }
  | { kind: "not-found" };

/**
 * Whose papers a signed-in page shows (plan W4), in the order of Java's ActivePatient.resolve, over what GET
 * /app/api/me said:
 * 1. `asked` (the page's `?patient=`): that patient if this family sees it, else not found, as Java answers 404 for a
 *    patient it cannot see, a malformed id or an empty one;
 * 2. `active_patient_id`, Java's remembered choice, if it is still in the list;
 * 3. the only patient;
 * 4. the chooser for two or more, nobody for none.
 * A screen never decides a patient: a remembered id that is no longer in the list is forgotten, never replaced by a
 * guess.
 */
export function activePatient(me: MeJson, asked: string | null): ActiveView {
  const byId = (id: string) => me.patients.find((p) => p.id === id);
  if (asked !== null) {
    const patient = byId(asked);
    return patient ? { kind: "patient", patient } : { kind: "not-found" };
  }
  const remembered = me.active_patient_id === null ? undefined : byId(me.active_patient_id);
  if (remembered) return { kind: "patient", patient: remembered };
  if (me.patients.length === 1) return { kind: "patient", patient: me.patients[0]! };
  return me.patients.length === 0 ? { kind: "none" } : { kind: "choose" };
}
