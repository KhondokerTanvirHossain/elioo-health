import "server-only";
import type { ReactNode } from "react";
import { cookies } from "next/headers";
import { BANNER_ONLY_COOKIE, readBannerOnly, type HeldPage } from "@/components/safety/NowBanner/banner-only";
import { KeptOnBannerRefresh } from "@/components/safety/NowBanner/KeptOnBannerRefresh";

/** The pathname of `here` (requestContext's path and query, preview prefix included). */
function pathOf(here: string): string {
  return new URL(here, "http://medioo.invalid").pathname;
}

/**
 * This request is a banner-only refresh of this very page (banner-only.ts): the page then reads nothing of its own and
 * renders `keptMain(held)` with the patient its toolbar named. Null for every other request, which reads as usual.
 */
export async function bannerOnlyRefresh(here: string): Promise<HeldPage | null> {
  return readBannerOnly((await cookies()).get(BANNER_ONLY_COOKIE)?.value, pathOf(here));
}

/** A paper page's main as it renders on every real read: held, so a banner-only refresh keeps it. */
export function heldMain(here: string, patient: string | null, main: ReactNode): ReactNode {
  return (
    <KeptOnBannerRefresh path={pathOf(here)} patient={patient}>
      {main}
    </KeptOnBannerRefresh>
  );
}

/** The main a banner-only refresh sends: the same holder with nothing in it, so the page keeps what it shows. */
export function keptMain(held: HeldPage): ReactNode {
  return <KeptOnBannerRefresh path={held.path} patient={held.patient} />;
}
