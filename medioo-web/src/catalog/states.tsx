import type { JSX } from "react";
import { banglaDigits, t, type Lang } from "@/copy";
import { MessageBubble } from "@/components/MessageBubble/MessageBubble";
import { MioAvatar } from "@/components/MioAvatar/MioAvatar";
import { OtpInput } from "@/components/OtpInput/OtpInput";
import { PhoneField } from "@/components/PhoneField/PhoneField";
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
 * catalog.spec.ts runs its checks (overflow, the rendered NOW red, axe, 12 px, CSP, the drawn --boundary on every field
 * and track) over every one of them, so a state added here is covered without a test edit. Each component PR adds its
 * states here and to states.test.tsx's SHIPPED list.
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

  // The sign-in fields (W2e-a). A fictional code, in the digits a family would type in each language. The lifetime
  // line is the verify page's, at Java's default otpTtl of 10 minutes (BaymaxProperties).
  { component: "OtpInput", state: "empty", render: (lang) => <OtpInput length={6} lang={lang} name="code" ttlMinutes={10} /> },
  {
    component: "OtpInput",
    state: "filled",
    render: (lang) => (
      <OtpInput length={6} lang={lang} name="code" ttlMinutes={10} defaultValue={lang === "bn" ? banglaDigits("042917") : "042917"} />
    ),
  },
  {
    // A code Java refused (VerifyFailure.WRONG, the copy key login.bad.wrong), shown back as typed.
    component: "OtpInput",
    state: "error",
    render: (lang) => (
      <OtpInput
        length={6}
        lang={lang}
        name="code"
        ttlMinutes={10}
        error={{ key: "login.bad.wrong" }}
        defaultValue={lang === "bn" ? banglaDigits("042918") : "042918"}
      />
    ),
  },
  { component: "PhoneField", state: "empty", render: (lang) => <PhoneField lang={lang} name="whatsapp_number" /> },
  {
    // A number Java refused (INVALID_NUMBER, the copy key login.number.invalid), shown back as typed: too short.
    component: "PhoneField",
    state: "error",
    render: (lang) => <PhoneField lang={lang} name="whatsapp_number" error="login.number.invalid" defaultValue="01700 0000" />,
  },
];
