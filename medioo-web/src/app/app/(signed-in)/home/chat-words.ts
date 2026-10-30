import "server-only";
import { t, type Lang } from "@/copy";
import type { ComposerWords } from "@/components/Composer/Composer";
import { call999Words } from "@/components/safety/AskAnswer/call999";

/**
 * The composer's words, resolved on the server (plan W5, asking since plan CHAT-web): Composer is a client island and
 * ships no copy.
 *
 * `reply` is what Medioo says when no answer came (a refusal, a dead network, the timeout): `chat.ask.reply` (the
 * decline and "ask a doctor", as `wa.fallback` says them), then `safety.red_flag_line` on its own line, appended here
 * by code exactly as WhatsApp's intake appends it to `wa.fallback`, so the emergency pointer can never be left out by an
 * edit of the copy. Java's answers carry their own appended lines and are shown as sent. `standing` is the line the
 * server renders (how to ask without JavaScript), `hint` the line once the field is there; `pending` names whose papers
 * Medioo is reading (`possessive`, MePatient.name_possessive). Every string comes from its one source (`ui-chat_*`,
 * `ui-result_*` and `messages_*`, through WebCopyExportTest).
 */
export function composerWords(lang: Lang, possessive: string): ComposerWords {
  return {
    field: t(lang, "chat.field"),
    send: t(lang, "add.send"),
    standing: t(lang, "chat.composer.nojs"),
    hint: t(lang, "chat.composer.hint"),
    reply: t(lang, "chat.ask.reply") + "\n" + t(lang, "safety.red_flag_line"),
    pending: t(lang, "chat.composer.pending", possessive),
    call999: call999Words(lang),
    camera: t(lang, "chat.camera"),
  };
}
