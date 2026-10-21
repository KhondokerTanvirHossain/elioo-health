import { t, type CopyKey, type Lang } from "@/copy";
import "./Toast.css";

/**
 * A short confirmation that something the family did has happened ("saved", "copied"): the copy key's words with a
 * check, announced politely (`role="status"`). Never red and never amber: a toast is never how Medioo says something is
 * urgent. Where it sits and when it goes are the caller's: it renders while the caller renders it.
 */
export function Toast({ messageKey, args = [], lang }: { messageKey: CopyKey; args?: Array<string | number>; lang: Lang }) {
  return (
    <div className="toast" role="status">
      <svg className="toast__shape" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
        <path d="M5 12.5l4.5 4.5L19 7.5" />
      </svg>
      <span className="toast__text">{t(lang, messageKey, ...args)}</span>
    </div>
  );
}
