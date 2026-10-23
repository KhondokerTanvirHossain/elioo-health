import { javaGet, signedIn } from "@/api/java";
import { requestContext } from "@/api/request";
import type { AttentionJson, MeJson, MePatient, ThreadJson } from "@/api/types";
import { t, type Lang } from "@/copy";
import { AttentionStack } from "@/components/AttentionStack/AttentionStack";
import { Composer } from "@/components/Composer/Composer";
import { MessageBubble } from "@/components/MessageBubble/MessageBubble";
import { MioAvatar } from "@/components/MioAvatar/MioAvatar";
import { Thread } from "@/components/Thread/Thread";
import { href } from "@/routing/paths";
import { signedInPage, type ShellContext } from "../shell";
import { composerWords } from "./chat-words";
import { OpenAtNewest } from "./OpenAtNewest";
import "./home.css";

type Reads = { attention: AttentionJson; thread: ThreadJson };

/** The patient's attention stack and thread, read in parallel through javaGet (GET only, the cookie only). */
async function reads(patient: MePatient, here: string): Promise<Reads> {
  const id = encodeURIComponent(patient.id);
  const [attention, thread] = await Promise.all([
    javaGet<AttentionJson>(`/app/api/patients/${id}/attention`),
    javaGet<ThreadJson>(`/app/api/patients/${id}/thread`),
  ]);
  return { attention: signedIn(attention, here), thread: signedIn(thread, here) };
}

/** Medioo's greeting: a template, no model call and no time of day (ChatView.greeting). */
function greeting(lang: Lang, me: MeJson) {
  const name = me.family.owner_name.trim();
  return name === "" ? t(lang, "chat.hello.plain") : t(lang, "chat.hello", name);
}

/**
 * The chat page (plan W5; phone.html scenes `chat`, `now`, `retake`, `first`; desktop.html `app`, `first`): the greeting, the
 * attention stack, the thread as WhatsApp delivered it, and the composer, for the patient the shell shows (`?patient=`
 * honoured; the chooser when nobody is chosen). The NOW banner is the shell's, on every signed-in page.
 *
 * Below 900 px the stack is in the one column, above the thread; where the shell shows the conversation column, the
 * stack is there and the main column holds the thread. A patient with no paper yet gets Medioo's introduction and,
 * for the owner, the one camera. The disclaimer is on the page whenever a result is: a paper card in the thread, or any
 * card in the attention stack (which can hold a paper older than the thread's first page).
 *
 * The composer is docked at the foot of the window (home.css) and the page opens on the newest exchange (OpenAtNewest),
 * except while a NOW result is on the page (the banner, or a NOW card in the stack): then it opens at the top, so the
 * NOW result is read first and never scrolled away by the page itself. Mio does not move while the banner is shown.
 */
export default async function HomePage() {
  const { here } = await requestContext();
  // main and aside read the same two paths; javaGet's cache() makes that one read each.
  const load = (patient: MePatient) => reads(patient, here);

  const stack = (ctx: ShellContext, where: "inline" | "aside", attention: AttentionJson, patient: MePatient) => (
    <div className={`chat__stack chat__stack--${where}`}>
      <AttentionStack items={attention.items} lang={ctx.lang} preview={ctx.preview} possessive={patient.name_possessive} />
    </div>
  );

  return signedInPage({
    tab: "chat",
    main: async (ctx) => {
      const { lang, preview, me, patient } = ctx;
      if (patient === null) {
        return (
          <div className="chat">
            <h1 className="chat__title">{t(lang, "tab.chat")}</h1>
            <MessageBubble surface="recv">{greeting(lang, me)}</MessageBubble>
          </div>
        );
      }
      const { attention, thread } = await load(patient);
      // Java's ChatView: a patient with no paper and nothing to look at gets the introduction. The thread lists every
      // paper and every delivered message, so its first page empty is a patient with none.
      const first = thread.items.length === 0 && attention.items.length === 0;
      const results = thread.items.some((i) => i.kind === "paper") || attention.items.length > 0;
      const nowOnPage = ctx.nowShown || attention.items.some((i) => i.kind === "urgent" && i.level === "now");
      return (
        <div className="chat" data-page="chat">
          <h1 className="chat__title">{t(lang, "tab.chat")}</h1>
          {first && (
            <div className="chat__intro">
              <MioAvatar size={96} still={ctx.nowShown} />
            </div>
          )}
          <MessageBubble surface="recv">{greeting(lang, me)}</MessageBubble>
          {first && <MessageBubble surface="recv">{t(lang, "chat.intro")}</MessageBubble>}
          {stack(ctx, "inline", attention, patient)}
          <Thread items={thread.items} lang={lang} preview={preview} />
          {!first && (
            <p className="chat__papers">
              <a className="chat__papers-link" href={href(`/app/timeline?patient=${encodeURIComponent(patient.id)}`, preview)}>
                {t(lang, "chat.papers", patient.name_possessive)}
              </a>
            </p>
          )}
          {results && <p className="chat__disclaimer">{t(lang, "disclaimer")}</p>}
          <Composer words={composerWords(lang)} owner={patient.owner} patientId={patient.id} preview={preview} />
          <OpenAtNewest hold={nowOnPage} />
        </div>
      );
    },
    aside: async (ctx) => {
      if (ctx.patient === null) return undefined;
      const { attention } = await load(ctx.patient);
      // No stack, no conversation column: the thread keeps the width.
      return attention.items.length === 0 ? undefined : stack(ctx, "aside", attention, ctx.patient);
    },
  });
}
