import { headers } from "next/headers";
import { javaGet, signedIn } from "@/api/java";
import { requestContext } from "@/api/request";
import type { FamilyJson } from "@/api/types";
import { themeOf } from "@/app/theme";
import { DeskFamily } from "@/components/DeskFamily/DeskFamily";
import { FamilyScreen } from "@/components/Family/FamilyScreen";
import { href } from "@/routing/paths";
import { desktopFrame } from "../desktop-frame";
import { signedInPage } from "../shell";
import { familyWords } from "./family-words";

/**
 * family (plan W10; phone.html scenes `family` and `theme`): every patient this account sees, their long-term
 * conditions and reminders, and the account's settings (language, theme, the family's reminder stop for an owner,
 * sign out). It reads GET /app/api/family through javaGet (the preview gate, the cookie and nothing else) and renders
 * what Java sent; every write is the browser's own PUT to /app/api/**. The page is about everyone, so the shell never
 * puts the chooser in its place (`everyone`). The current theme is the `baymax_theme` cookie, as the root layout reads it.
 *
 * On the desktop layout (from 900 px, preview only until the flip) it is the sidebar's account row (desk.account,
 * plan desktop-parity F1): DeskFamily, desktop.html's settings column over the same read (javaGet's cache keeps it one)
 * and the same islands, the person in view the shell's (`?patient=`, else the one Java remembers).
 */
export default async function FamilyPage() {
  const read = async () => {
    const { here } = await requestContext();
    const family = signedIn(await javaGet<FamilyJson>("/app/api/family"), here);
    const theme = themeOf((await headers()).get("cookie"));
    return { family, theme };
  };
  return signedInPage({
    tab: "family",
    everyone: true,
    main: async ({ lang, preview }) => {
      const { family, theme } = await read();
      return (
        <FamilyScreen family={family} words={familyWords(lang, family)} theme={theme} lang={lang} signOutTo={href("/app/", preview)} />
      );
    },
    desktop: {
      frame: desktopFrame,
      paper: async ({ lang, preview, me, patient }) => {
        const { family, theme } = await read();
        return (
          <DeskFamily
            lang={lang}
            family={family}
            words={familyWords(lang, family)}
            activeId={patient?.id ?? null}
            documents={Object.fromEntries(me.patients.map((p) => [p.id, p.documents]))}
            theme={theme}
            signOutTo={href("/app/", preview)}
          />
        );
      },
    },
  });
}
