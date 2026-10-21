import { redirect } from "next/navigation";
import { javaGet } from "@/api/java";
import { requestContext } from "@/api/request";
import { basicSignInPath, href } from "@/routing/paths";
import { CodeFlow } from "../CodeFlow";
import { SignInFrame } from "../SignInFrame";
import { codeWords, type SignInState } from "../sign-in-words";

/**
 * `/app/verify`: the code step (plan W3). What it says comes from Java's own login cookie, read by
 * `GET /app/api/sign-in/state` (W3-api), so a family who left for WhatsApp and came back gets the same page: sent says
 * it was sent; anything else never does, and asks them to write "code" to Medioo. No pending login is the number step.
 */
export default async function VerifyPage() {
  const { lang, preview } = await requestContext();
  const state = await javaGet<SignInState>("/app/api/sign-in/state");
  if (state.status !== 200) throw new Error(`java ${state.status} ${state.reason}`);
  if (!state.body.pending) redirect(href("/app/", preview));
  const words = codeWords(lang, state.body);
  const numberHref = href("/app/", preview);
  return (
    <SignInFrame lang={lang} noscript={basicSignInPath("")}>
      {words.sent && (
        <p className="sign-in__lede" role="status">
          {words.sent.line}
        </p>
      )}
      {words.notSent && (
        <>
          <p className="sign-in__lede">
            {words.notSent.say} <strong>{words.notSent.word}</strong>
          </p>
          {words.notSent.open && (
            <>
              <a className="sign-in__wa" href={words.notSent.open.href}>
                {words.notSent.open.text}
              </a>
              <p className="sign-in__hint">{words.notSent.open.hint}</p>
            </>
          )}
        </>
      )}
      <p className="sign-in__mask">{words.number}</p>
      <CodeFlow words={words} numberHref={numberHref} preview={preview} />
      {words.sent?.resend && (
        <a className="sign-in__quiet" href={words.sent.resend.href}>
          {words.sent.resend.text}
        </a>
      )}
      <a className="sign-in__quiet" href={numberHref}>
        {words.other}
      </a>
    </SignInFrame>
  );
}
