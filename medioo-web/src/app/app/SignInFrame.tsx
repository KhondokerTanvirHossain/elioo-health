import type { ReactNode } from "react";
import { t, type Lang } from "@/copy";
import { MessageBubble } from "@/components/MessageBubble/MessageBubble";
import { MioAvatar } from "@/components/MioAvatar/MioAvatar";
import { Wordmark } from "@/components/Wordmark/Wordmark";
import "./sign-in.css";

/**
 * What both sign-in pages share (phone.html scene `welcome`, desktop.html scene `signin`): the wordmark, Mio as drawn
 * with his one line, the title, then the step. Mio's line is `chat.intro` ("I am Medioo — not a doctor. …"): both
 * approved scenes say on the first screen that Medioo is not a doctor, and this approved key says it in his voice.
 * Below 900 px one column; from 900 px the welcome sits beside the form.
 *
 * `noscript` is the link to Java's no-JavaScript sign-in (S1), with the held place, for a browser that runs no script.
 * It sits above the step and is drawn as large as the primary press, so that browser reaches the way in first; the
 * step's own controls stay disabled without JavaScript (SignInFlow, CodeFlow), never a press that does nothing.
 */
export function SignInFrame({ lang, noscript, children }: { lang: Lang; noscript: string; children: ReactNode }) {
  return (
    <main className="sign-in">
      <section className="sign-in__welcome">
        <Wordmark lang={lang} />
        <div className="sign-in__mio">
          <MioAvatar size={96} />
          <MessageBubble surface="recv">{t(lang, "chat.intro")}</MessageBubble>
        </div>
      </section>
      <section className="sign-in__side">
        <h1 className="sign-in__title">{t(lang, "login.title")}</h1>
        <noscript>
          <a className="sign-in__primary sign-in__nojs" href={noscript}>
            {t(lang, "login.nojs")}
          </a>
        </noscript>
        {children}
      </section>
    </main>
  );
}
