import { headers } from "next/headers";
import { javaGet, signedIn } from "@/api/java";
import { requestContext } from "@/api/request";
import type { FamilyJson } from "@/api/types";
import { themeOf } from "@/app/theme";
import { FamilyScreen } from "@/components/Family/FamilyScreen";
import { href } from "@/routing/paths";
import { signedInPage } from "../shell";
import { familyWords } from "./family-words";

/**
 * family (plan W10; phone.html scenes `family` and `theme`): every patient this account sees, their long-term
 * conditions and reminders, and the account's settings (language, theme, the family's reminder stop for an owner,
 * sign out). It reads GET /app/api/family through javaGet (the preview gate, the cookie and nothing else) and renders
 * what Java sent; every write is the browser's own PUT to /app/api/**. The page is about everyone, so the shell never
 * puts the chooser in its place (`everyone`). The current theme is the `baymax_theme` cookie, as the root layout reads it.
 */
export default async function FamilyPage() {
  return signedInPage({
    tab: "family",
    everyone: true,
    main: async ({ lang, preview }) => {
      const { here } = await requestContext();
      const family = signedIn(await javaGet<FamilyJson>("/app/api/family"), here);
      const theme = themeOf((await headers()).get("cookie"));
      return (
        <FamilyScreen family={family} words={familyWords(lang, family)} theme={theme} lang={lang} signOutTo={href("/app/", preview)} />
      );
    },
  });
}
