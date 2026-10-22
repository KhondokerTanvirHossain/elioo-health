import { describe, expect, it } from "vitest";
import type { MeJson } from "@/api/types";
import longName from "../../../../fixtures/api/me/long-name.json";
import noPatients from "../../../../fixtures/api/me/no-patients.json";
import onePatient from "../../../../fixtures/api/me/one-patient.json";
import twoPatients from "../../../../fixtures/api/me/two-patients.json";
import { activePatient } from "./active-patient";

/*
 * Whose papers a signed-in page shows (plan W4), in ActivePatient.resolve's order and over the bodies Java's
 * MeApiContractTest wrote: `?patient=` if this family sees that patient, else not found (Java's 404); then the choice
 * Java remembered (`active_patient_id`) if it is still in the list; then the only patient; then the chooser for two or
 * more, and nothing for none. A screen never decides a patient.
 */

const ONE: MeJson = onePatient.body;
const TWO: MeJson = twoPatients.body;
const NONE: MeJson = noPatients.body;
const LONG: MeJson = longName.body;

const [ma, rokeya] = TWO.patients;

describe("activePatient", () => {
  it("anAskedPatientThisFamilySeesIsTheOne", () => {
    expect(rokeya, "the two-patients fixture holds two").toBeDefined();
    expect(activePatient(TWO, rokeya!.id)).toEqual({ kind: "patient", patient: rokeya });
    // The asked one wins over the remembered one.
    const remembered: MeJson = { ...TWO, active_patient_id: ma!.id };
    expect(activePatient(remembered, rokeya!.id)).toEqual({ kind: "patient", patient: rokeya });
  });

  it("anAskedPatientThisFamilyDoesNotSeeIsNotFound", () => {
    // Another family's patient, a malformed id and an empty one: Java's ActivePatient.resolve answers each with a 404.
    expect(activePatient(ONE, "5e9edf6d-0a2a-4913-8675-9d07a4100099")).toEqual({ kind: "not-found" });
    expect(activePatient(ONE, "not-a-uuid")).toEqual({ kind: "not-found" });
    expect(activePatient(ONE, "")).toEqual({ kind: "not-found" });
    expect(activePatient(NONE, ONE.patients[0]!.id)).toEqual({ kind: "not-found" });
  });

  it("theRememberedPatientIsTheOneWhenStillVisible", () => {
    expect(ONE.active_patient_id, "Java remembered the only patient").toBe(ONE.patients[0]!.id);
    expect(activePatient(ONE, null)).toEqual({ kind: "patient", patient: ONE.patients[0] });
    expect(activePatient({ ...TWO, active_patient_id: rokeya!.id }, null)).toEqual({ kind: "patient", patient: rokeya });
    expect(activePatient(LONG, null)).toEqual({ kind: "patient", patient: LONG.patients[0] });
  });

  it("theOnlyPatientIsTheOneWithNothingRemembered", () => {
    expect(activePatient({ ...ONE, active_patient_id: null }, null)).toEqual({ kind: "patient", patient: ONE.patients[0] });
  });

  it("twoPatientsAndNothingRememberedIsTheChooser", () => {
    expect(TWO.active_patient_id, "the fixture remembers no one").toBeNull();
    expect(TWO.patients.length).toBe(2);
    expect(activePatient(TWO, null)).toEqual({ kind: "choose" });
  });

  it("noPatientsIsNone", () => {
    expect(activePatient(NONE, null)).toEqual({ kind: "none" });
  });

  it("aRememberedPatientWhoIsGoneIsForgottenNotGuessed", () => {
    // The cookie names a patient this family no longer sees (deleted, or a share ended): with two left, neither is
    // picked for the family.
    const gone: MeJson = { ...TWO, active_patient_id: "5e9edf6d-0a2a-4913-8675-9d07a4100098" };
    expect(activePatient(gone, null)).toEqual({ kind: "choose" });
    // With one left, it is the only patient, as Java's rule says, not the remembered one.
    expect(activePatient({ ...ONE, active_patient_id: "5e9edf6d-0a2a-4913-8675-9d07a4100098" }, null)).toEqual({
      kind: "patient",
      patient: ONE.patients[0],
    });
  });
});
