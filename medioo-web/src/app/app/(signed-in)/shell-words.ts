import "server-only";
import type { MeJson, MePatient } from "@/api/types";
import { t, type Lang } from "@/copy";
import type { ShellWords } from "@/components/AppShell/AppShell";

/*
 * The shell's words, resolved on the server (plan W4): the switcher, the sidebar's button and sign out are client
 * islands, which take their words as props and import no copy, so the browser receives only these strings, inside the
 * page's RSC payload. The switcher's label carries the shown patient's name as GET /app/api/me spelled it.
 */
export function shellWords(lang: Lang, me: MeJson, active: MePatient | null): ShellWords {
  return {
    menu: t(lang, "tabs.label"),
    signOut: t(lang, "app.logout"),
    failed: t(lang, "app.err.generic"),
    disclaimer: t(lang, "disclaimer"),
    switcher:
      active === null
        ? null
        : {
            label: t(lang, "switch.label", active.name),
            title: t(lang, "switch.title"),
            closeLabel: t(lang, "sheet.close"),
            refused: t(lang, "app.err.notfound"),
            failed: t(lang, "app.err.generic"),
            patients: me.patients.map((p) => ({
              id: p.id,
              name: p.name,
              ...(p.urgent ? { urgentWord: t(lang, "attn.strip") } : {}),
            })),
          },
  };
}
