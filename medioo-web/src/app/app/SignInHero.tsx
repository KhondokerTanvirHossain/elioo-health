import { t, type Lang } from "@/copy";
import { MessageBubble } from "@/components/MessageBubble/MessageBubble";
import { MioAvatar } from "@/components/MioAvatar/MioAvatar";
import { StatusChip } from "@/components/StatusChip/StatusChip";
import { UrgencyTag } from "@/components/safety/UrgencyTag/UrgencyTag";
import { Wordmark } from "@/components/Wordmark/Wordmark";

/** The sample card's two rows (desktop.html `demoCard`): a printed name, its value, and the chip the value would get. */
const ROWS = [
  { name: "Serum Creatinine", value: "1.6", chip: "out-high" as const },
  { name: "Blood Urea", value: "32", chip: "in" as const },
];

/**
 * The sign-in pages' welcome on the desktop layout (plan desktop-parity Phase 2, row I1; desktop.html `signinView()`
 * `.hero`), from 900 px, in place of the phone's welcome: the wordmark, Mio waving, the headline and the promise, a
 * sample of what Medioo answers (a paper sent, a card with its level, its verdict and two rows, and the reply), and the
 * trust line. Below 900 px it is not drawn (SignInFrame keeps today's welcome there).
 *
 * The sample is decoration (`aria-hidden`, as desktop.html draws it): a screen reader is told the headline, the promise
 * and the trust line. Its level is the app's own THIS_WEEK tag (UrgencyTag: shape and word), its chips the app's
 * StatusChip: no colour is painted here outside the components that own it. desktop.html also writes the urgency sentence
 * on the sample's band; a safety sentence is rendered only from what Java sent, inside the safety components (the lint
 * rule), so a made-up card on the sign-in page carries the tag alone.
 *
 * The trust line is desktop.html's, but for one clause: its "you may delete everything at any time" is a self-service the
 * app does not offer, so the landing page's approved promise stands in its place (`landing.promise.3`, everything deleted
 * within 24 hours on request).
 */
export function SignInHero({ lang }: { lang: Lang }) {
  return (
    <section className="sign-in__hero" aria-labelledby="sign-in-hero-title">
      <Wordmark lang={lang} />
      <MioAvatar size={96} />
      <h2 className="sign-in__hero-title" id="sign-in-hero-title">
        {t(lang, "desk.signin.h1")}
      </h2>
      <p className="sign-in__hero-lede">{t(lang, "desk.signin.lede")}</p>
      <div className="sign-in__demo" aria-hidden="true">
        <div className="sign-in__demo-me">
          <MessageBubble surface="sent">{t(lang, "desk.signin.me")}</MessageBubble>
        </div>
        <div className="sign-in__demo-card">
          <p className="sign-in__demo-band">
            <UrgencyTag level="this_week" lang={lang} />
          </p>
          <p className="sign-in__demo-verdict">
            <b>{t(lang, "desk.signin.verdict")}</b>
            <small>{t(lang, "desk.signin.meta")}</small>
          </p>
          <ul className="sign-in__demo-rows">
            {ROWS.map((r) => (
              <li key={r.name} className="sign-in__demo-row">
                <span>{r.name}</span>
                <span className="sign-in__demo-value">
                  <b>{r.value}</b>
                  <StatusChip kind={r.chip} lang={lang} />
                </span>
              </li>
            ))}
          </ul>
        </div>
        <MessageBubble surface="recv">{t(lang, "desk.signin.reply")}</MessageBubble>
      </div>
      <p className="sign-in__trust">
        {[t(lang, "desk.signin.trust.family"), t(lang, "landing.promise.3"), t(lang, "desk.signin.trust.doctor")].join(" · ")}
      </p>
    </section>
  );
}
