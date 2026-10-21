import type { JSX } from "react";
import type { MePatient, NowBannerJson } from "@/api/types";
import { banglaDigits, t, type Lang } from "@/copy";
import { MessageBubble } from "@/components/MessageBubble/MessageBubble";
import { MioAvatar } from "@/components/MioAvatar/MioAvatar";
import { OtpInput } from "@/components/OtpInput/OtpInput";
import { PhoneField } from "@/components/PhoneField/PhoneField";
import { NowBanner } from "@/components/safety/NowBanner/NowBanner";
import { CHIP_KINDS, StatusChip } from "@/components/StatusChip/StatusChip";
import { Wordmark } from "@/components/Wordmark/Wordmark";
// B3's generated GET /app/api/now-banner bodies (baymax NowBannerApiContractTest): each state reads one as Java wrote it.
import nowBannerAbroad from "../../fixtures/api/now-banner/abroad.json";
import nowBannerAcked from "../../fixtures/api/now-banner/acked.json";
import nowBannerOne from "../../fixtures/api/now-banner/one.json";
import nowBannerTwoPatients from "../../fixtures/api/now-banner/two-patients.json";
// Fictional names, written by baymax CatalogFixturesExportTest so no Bangla is typed here (Global Constraints).
import catalogNames from "../../fixtures/catalog/names.json";

/**
 * One state of one component, as the catalog renders it in each language. `empty` marks a state whose point is that
 * the component renders nothing (NowBanner when Java says `show: false`): catalog.spec.ts holds it to painting no
 * element at all, and holds every other state to rendering something.
 */
export type CatalogState = { component: string; state: string; render: (lang: Lang) => JSX.Element; empty?: true };

const names = catalogNames.names;
const nameFor = (lang: Lang) => (lang === "bn" ? names.short : names.english);

/**
 * The patients GET /app/api/me would list beside a banner: one per distinct patient of its documents, in order, named
 * from `pool`. The name is display data the screen supplies, so it is the one thing a NowBanner state chooses; the
 * banner is the generated body, unchanged.
 */
function bannerPatients(banner: NowBannerJson, pool: string[]): MePatient[] {
  const ids = [...new Set(banner.documents.flatMap((d) => (d.patient_id === null ? [] : [d.patient_id])))];
  return ids.map((id, i) => {
    const name = pool[i % pool.length]!;
    return {
      id,
      name,
      name_possessive: name,
      name_objective: name,
      age: 64,
      sex: "female",
      owner: true,
      documents: 1,
      last_document_at: null,
      urgent: true,
    };
  });
}

const nowBanner = (state: string, banner: NowBannerJson, pool: (lang: Lang) => string[], empty?: true): CatalogState => ({
  component: "NowBanner",
  state,
  render: (lang) => <NowBanner banner={banner} patients={bannerPatients(banner, pool(lang))} lang={lang} />,
  ...(empty ? { empty } : {}),
});

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

  // The NOW banner over each of B3's generated bodies; acked.json is the one Java says not to show. The seen button is
  // inert here: the catalog has no session and passes no onSeen.
  nowBanner("one-patient", nowBannerOne.body, (lang) => [nameFor(lang)]),
  nowBanner("two-patients", nowBannerTwoPatients.body, (lang) => [nameFor(lang), names.long]),
  nowBanner("abroad", nowBannerAbroad.body, (lang) => [nameFor(lang)]),
  nowBanner("hidden", nowBannerAcked.body, (lang) => [nameFor(lang)], true),
  // A long name, spaced and unbroken: the title wraps inside the banner, never past it (plan Review Focus 3).
  nowBanner("long-name", nowBannerOne.body, () => [names.long]),
  nowBanner("long-unbroken", nowBannerOne.body, () => [names.long_unbroken]),
];
