import "server-only";
import { t, type Lang } from "@/copy";
import { otpWords } from "@/components/OtpInput/OtpInput";
import type { OtpWords } from "@/components/OtpInput/OtpInputView";
import { phoneFieldWords } from "@/components/PhoneField/PhoneField";
import type { PhoneFieldWords } from "@/components/PhoneField/PhoneFieldView";

/*
 * The words of the two sign-in islands, resolved on the server (plan W3): a "use client" file takes its words as props
 * and imports no copy, so the browser receives only the strings these screens say, inside the page's RSC payload, and
 * never the bundle. Every number a line needs (the code's lifetime, the tries BURNED took, the number's tail) comes
 * from the GET the page already made (`sign-in/state`), never from an error body.
 */

/** The number step: the field, the press, the number shown back for a yes, and the two ways it can be refused. */
export type SignInWords = {
  field: PhoneFieldWords;
  next: string;
  confirm: string;
  yes: string;
  change: string;
  invalid: string;
  generic: string;
};

export function signInWords(lang: Lang): SignInWords {
  return {
    field: phoneFieldWords(lang),
    next: t(lang, "login.next"),
    confirm: t(lang, "login.number.confirm"),
    yes: t(lang, "login.number.yes"),
    change: t(lang, "login.number.change"),
    invalid: t(lang, "login.number.invalid"),
    generic: t(lang, "app.err.generic"),
  };
}

/** `GET /app/api/sign-in/state` (W3-api): the fields `POST /app/api/sign-in/request` answered, while a login is pending. */
export type SignInState =
  | { pending: false }
  | { pending: true; sent: boolean; number_tail: string; code_ttl_minutes: number; max_attempts: number; wa_link?: string };

/** The code refusals Java names (ApiReason), each said in the verify page's own line. */
export const CODE_REASONS = ["CODE_WRONG", "CODE_EXPIRED", "CODE_BURNED", "CODE_FORMAT"] as const;
export type CodeReason = (typeof CODE_REASONS)[number];

/**
 * The code step, as SignInPages.verifyPage says it: sent says it was sent (and offers another through WhatsApp when a
 * link exists); not sent never says it was sent, and asks the family to write "code" to Medioo, with the one-tap
 * WhatsApp link when Java has one. Two states and no third.
 */
export type CodeWords = {
  sent: { line: string; resend: { text: string; href: string } | null } | null;
  notSent: { say: string; word: string; open: { text: string; href: string; hint: string } | null } | null;
  number: string;
  field: OtpWords;
  enter: string;
  other: string;
  refusals: Record<CodeReason, string>;
  generic: string;
};

export function codeWords(lang: Lang, state: Extract<SignInState, { pending: true }>): CodeWords {
  const link = state.wa_link ?? null;
  return {
    sent: state.sent
      ? { line: t(lang, "login.code.sent"), resend: link === null ? null : { text: t(lang, "login.code.resend"), href: link } }
      : null,
    notSent: state.sent
      ? null
      : {
          say: t(lang, "login.code.say"),
          word: t(lang, "login.code.word"),
          open: link === null ? null : { text: t(lang, "login.wa.open"), href: link, hint: t(lang, "login.wa.hint") },
        },
    // The tail Java kept in its login cookie, masked as the HTML code page masks it.
    number: t(lang, "login.code.number", "••••••••" + state.number_tail),
    field: otpWords(lang, state.code_ttl_minutes),
    enter: t(lang, "login.enter"),
    other: t(lang, "login.other"),
    refusals: {
      CODE_WRONG: t(lang, "login.bad.wrong"),
      CODE_EXPIRED: t(lang, "login.bad.expired"),
      CODE_BURNED: t(lang, "login.bad.burned", state.max_attempts),
      CODE_FORMAT: t(lang, "login.bad.format"),
    },
    generic: t(lang, "app.err.generic"),
  };
}
