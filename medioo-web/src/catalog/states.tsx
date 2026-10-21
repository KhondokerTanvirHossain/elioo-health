import type { JSX } from "react";
import { t, type Lang } from "@/copy";
import { MessageBubble } from "@/components/MessageBubble/MessageBubble";
import { MioAvatar } from "@/components/MioAvatar/MioAvatar";
import { CHIP_KINDS, StatusChip } from "@/components/StatusChip/StatusChip";
import { Wordmark } from "@/components/Wordmark/Wordmark";
// Fictional names, written by baymax CatalogFixturesExportTest so no Bangla is typed here (Global Constraints).
import catalogNames from "../../fixtures/catalog/names.json";

/** One state of one component, as the catalog renders it in each language. */
export type CatalogState = { component: string; state: string; render: (lang: Lang) => JSX.Element };

const names = catalogNames.names;
const nameFor = (lang: Lang) => (lang === "bn" ? names.short : names.english);

/**
 * Every catalog state, registered per component. /app/catalog-test renders each one in both languages, and
 * catalog.spec.ts runs its checks (overflow, the rendered NOW red, axe, 12 px, CSP) over every one of them, so a state
 * added here is covered without a test edit. Later components (W2d, W2e) add theirs here.
 */
export const STATES: CatalogState[] = [
  { component: "Wordmark", state: "default", render: (lang) => <Wordmark lang={lang} /> },

  { component: "MioAvatar", state: "32", render: () => <MioAvatar size={32} /> },
  { component: "MioAvatar", state: "48-still", render: () => <MioAvatar size={48} still /> },
  { component: "MioAvatar", state: "96", render: () => <MioAvatar size={96} /> },

  {
    component: "MessageBubble",
    state: "recv",
    render: (lang) => (
      <MessageBubble surface="recv" tail>
        {t(lang, "chat.hello", nameFor(lang))}
      </MessageBubble>
    ),
  },
  {
    component: "MessageBubble",
    state: "sent",
    render: (lang) => (
      <MessageBubble surface="sent" tail>
        {t(lang, "chat.camera")}
      </MessageBubble>
    ),
  },
  {
    // A token that cannot wrap at a space (a long name typed without one) must wrap inside the bubble, never push the
    // row wider than the screen (plan Review Focus 3).
    component: "MessageBubble",
    state: "long-unbroken",
    render: (lang) => (
      <MessageBubble surface="recv" tail>
        {t(lang, "chat.hello", names.long_unbroken)}
      </MessageBubble>
    ),
  },

  ...CHIP_KINDS.map(
    (kind): CatalogState => ({ component: "StatusChip", state: kind, render: (lang) => <StatusChip kind={kind} lang={lang} /> }),
  ),
];
