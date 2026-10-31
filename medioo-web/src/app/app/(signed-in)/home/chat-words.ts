import "server-only";
import { banglaDigits, t, type Lang } from "@/copy";
import type { ComposerWords } from "@/components/Composer/Composer";
import { QUESTION_MAX_CHARS } from "@/components/Composer/ask-limits";
import { call999Words } from "@/components/safety/AskAnswer/call999";

/**
 * The composer's words, resolved on the server (plan W5, asking since plan CHAT-web): Composer is a client island and
 * ships no copy.
 *
 * `reply` is what Medioo says when no answer came (a refusal, a dead network, the timeout, a session that ended):
 * `chat.ask.reply` (Java's declined body, DR-50), then `safety.red_flag_line` on its own line, appended here by code
 * exactly as WhatsApp's intake appends it to `wa.fallback`, so the emergency pointer can never be left out by an edit of
 * the copy. `tooLong` is Java's refusal of an over-long question, the limit in it, with the same red-flag line. Java's
 * answers carry their own appended lines and are shown as sent. `hint` is the line under the field; `pending` names
 * whose papers Medioo is reading (`possessive`, MePatient.name_possessive). Every string comes from its one source
 * (`ui-chat_*`, `ui-result_*`, `ui-login_*` and `messages_*`, through WebCopyExportTest).
 */
export function composerWords(lang: Lang, possessive: string): ComposerWords {
  const red = t(lang, "safety.red_flag_line");
  const limit = lang === "bn" ? banglaDigits(String(QUESTION_MAX_CHARS)) : String(QUESTION_MAX_CHARS);
  return {
    field: t(lang, "chat.field"),
    send: t(lang, "add.send"),
    hint: t(lang, "chat.composer.hint"),
    reply: t(lang, "chat.ask.reply") + "\n" + red,
    tooLong: t(lang, "chat.composer.too_long", limit) + "\n" + red,
    signIn: t(lang, "login.enter"),
    pending: t(lang, "chat.composer.pending", possessive),
    call999: call999Words(lang),
    crop: { retry: t(lang, "crop.retry"), alt: t(lang, "health.crop.alt") },
    camera: t(lang, "chat.camera"),
  };
}

/** The line that says how to ask without JavaScript (`chat.composer.nojs`), for the page's `<noscript>` only. */
export function composerNoJs(lang: Lang): string {
  return t(lang, "chat.composer.nojs");
}
