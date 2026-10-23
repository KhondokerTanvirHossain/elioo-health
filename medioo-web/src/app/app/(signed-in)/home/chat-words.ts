import "server-only";
import { t, type Lang } from "@/copy";
import type { ComposerWords } from "@/components/Composer/Composer";

/**
 * The composer's words, resolved on the server (plan W5): Composer is a client island and ships no copy.
 *
 * `reply` is what Medioo answers to a typed line until D11: `chat.ask.reply` (the decline and "ask a doctor", as
 * `wa.fallback` says them), then `safety.red_flag_line` on its own line, appended here by code exactly as WhatsApp's
 * intake appends it to `wa.fallback`, so the emergency pointer can never be left out by an edit of the copy. Both come
 * from their one source (`ui-chat_*` and `messages_*`, through WebCopyExportTest).
 */
export function composerWords(lang: Lang): ComposerWords {
  return {
    field: t(lang, "chat.field"),
    send: t(lang, "add.send"),
    standing: t(lang, "chat.soon"),
    reply: t(lang, "chat.ask.reply") + "\n" + t(lang, "safety.red_flag_line"),
    camera: t(lang, "chat.camera"),
  };
}
