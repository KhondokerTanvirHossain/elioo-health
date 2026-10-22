import { redirect } from "next/navigation";
import { javaGet } from "@/api/java";
import { requestContext } from "@/api/request";
import { basicSignInPath, href } from "@/routing/paths";
import { SignInFlow } from "./SignInFlow";
import { SignInFrame } from "./SignInFrame";
import { signInWords } from "./sign-in-words";

/**
 * `/app` and `/app/`: the welcome and the number step (plan W3). A signed-in visitor is sent on to the place Java
 * checked (`GET /app/api/sign-in/next`, W3-api), so Next never judges `next` and the hop never goes through the no-JS
 * fallback; anyone else gets the number step, with `next` handed to Java as the page received it.
 */
export default async function SignInPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const { lang, preview } = await requestContext();
  const given = (await searchParams).next;
  const next = typeof given === "string" ? given : null;
  const held = await javaGet<{ next: string }>("/app/api/sign-in/next?next=" + encodeURIComponent(next ?? ""));
  if (held.status === 200) redirect(href(held.body.next, preview));
  if (held.status !== 401) throw new Error(`java ${held.status} ${held.reason}`);
  return (
    <SignInFrame lang={lang} noscript={basicSignInPath(next ?? "")}>
      <SignInFlow words={signInWords(lang)} next={next} verifyHref={href("/app/verify", preview)} />
    </SignInFrame>
  );
}
