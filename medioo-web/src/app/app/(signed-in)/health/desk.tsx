import "server-only";
import type { ReactNode } from "react";
import { javaGet, signedIn } from "@/api/java";
import type { LabsJson, MedicinesJson } from "@/api/types";
import { t } from "@/copy";
import { DeskLabs } from "@/components/DeskLabs/DeskLabs";
import { DeskMedicines } from "@/components/DeskMedicines/DeskMedicines";
import { href, reachable } from "@/routing/paths";
import type { ShellContext } from "../shell";

/**
 * The health page on the desktop layout: the lab values (plan desktop-parity Phase 2, L1; desktop.html `labsView()`),
 * for the patient the shell shows. The sidebar's lab values item (desk.nav.labs) is this page (DesktopSidebar's `labs` place), so it needs
 * no route of its own. It reads what the phone's page reads, by the same path, so javaGet's cache() keeps it one read.
 * - `?lab=` chooses the value (DeskLabs's chosenLab: a reading's observation id, or `bp`);
 * - a reading's paper opens the talk-and-papers screen on that paper (`/app/home?patient=…&paper=…`, as the calendar's
 *   `open`), written here so this route does not import the conversation's modules;
 * - with nothing read yet, the way to send a paper is the add page, where one is reachable for this viewer.
 */
export async function labsScreen(ctx: ShellContext, here: string, { soft }: { soft: boolean }): Promise<ReactNode> {
  const { lang, preview, patient } = ctx;
  if (patient === null) {
    return (
      <div className="health" data-page="health">
        <h1 className="health__title">{t(lang, "desk.nav.labs")}</h1>
        <p className="health__none">{t(lang, "health.labs.none")}</p>
      </div>
    );
  }
  const id = encodeURIComponent(patient.id);
  const labs = signedIn(await javaGet<LabsJson>(`/app/api/patients/${id}/labs`), here);
  const lab = new URL(here, "http://medioo.invalid").searchParams.get("lab");
  const at = (key: string) => href(`/app/health?patient=${id}&lab=${encodeURIComponent(key)}`, preview);
  const open = (documentId: string) => href(`/app/home?patient=${id}&paper=${encodeURIComponent(documentId)}`, preview);
  return (
    <DeskLabs
      lang={lang}
      labs={labs}
      asked={lab}
      at={at}
      soft={soft}
      open={open}
      send={reachable("/app/add", preview) ? href("/app/add", preview) : null}
    />
  );
}

/** The health page's desktop screen the address asks for: the medicines on `?show=medicines`, else the lab values. */
export function shows(here: string): "labs" | "medicines" {
  return new URL(here, "http://medioo.invalid").searchParams.get("show") === "medicines" ? "medicines" : "labs";
}

/**
 * The health page on the desktop layout at `?show=medicines`: the medicines (plan desktop-parity Phase 2, M1;
 * desktop.html `medsView()`), the sidebar's medicines item. It reads what the phone's page reads, by the same path, so
 * javaGet's cache() keeps it one read. Every paper it names (the prescription, a newer one being read) opens on the
 * talk-and-papers screen (`/app/home?patient=…&paper=…`, as labsScreen's `open`).
 */
export async function medicinesScreen(ctx: ShellContext, here: string): Promise<ReactNode> {
  const { lang, preview, patient } = ctx;
  if (patient === null) {
    return (
      <div className="health" data-page="health">
        <h1 className="health__title">{t(lang, "desk.nav.meds")}</h1>
        <p className="health__none">{t(lang, "health.medicines.none")}</p>
      </div>
    );
  }
  const id = encodeURIComponent(patient.id);
  const medicines = signedIn(await javaGet<MedicinesJson>(`/app/api/patients/${id}/medicines`), here);
  const open = (documentId: string) => href(`/app/home?patient=${id}&paper=${encodeURIComponent(documentId)}`, preview);
  return <DeskMedicines lang={lang} possessive={patient.name_possessive} medicines={medicines} open={open} />;
}
