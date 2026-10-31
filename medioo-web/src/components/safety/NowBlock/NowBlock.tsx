import type { ReactNode } from "react";
import { t, type CopyKey, type Lang } from "@/copy";
import type { FollowUpJson, NowJson, PaperResultJson, PaperValueJson } from "@/api/types";
import "./NowBlock.css";

/** What the block draws its proof with: the page's own value row, a line quoted from the paper, and the whole pages. */
export type NowProof = {
  /** One value row, exactly as the page's groups draw it. */
  row: (value: PaperValueJson) => ReactNode;
  /** One follow-up, quoted beside its crop with its date. */
  followUp: (line: FollowUpJson) => ReactNode;
  /** One of the doctor's lines, quoted beside its crop. */
  quote: (line: { text: string; crop: PaperResultJson["values"][number]["crop"] }) => ReactNode;
  /** Every page whole, each opening large: what stands in when a reason has no row of its own to show. */
  pages: ReactNode;
  /** Where the outside group sits on this page (`#outside`), or null when the page draws none. */
  outsideHref: string | null;
};

/** The lead of each reason Java sends (`reasons[]`, PaperDisplay.drawnOrder's prefixes; "other" for one it does not know). */
const LEAD: Record<string, CopyKey> = {
  value_critical: "now.why.critical",
  value_outside_range: "value.group.outside",
  unverified_value_critical: "now.why.unverified",
  unverified_value_outside_range: "now.why.unverified_outside",
  text_emergency_phrase: "now.why.phrase",
  follow_up_past_due: "now.why.followup",
  other: "now.why.other",
};

/**
 * The NOW part of a paper (plan W6; DocumentPages.nowBody, spec §4.11), rendered only for a paper Java sent `now` for:
 * one block per reason in `reasons[]`, in Java's order, each with its lead and what proves it on the paper (the rows
 * Java marked `in_now_block`, the follow-ups, the diagnosis lines), the whole pages standing in where there is none;
 * then, for a phone abroad (`now.remote_share`), the person-at-home lines and the WhatsApp share whose text names the
 * patient and carries no value; the red-flag questions with `now.flags_go_key`; `now.bring_key`; and the not-a-doctor
 * line, inside the block. Every line is a key Java sent or a fixed lead; nothing here is decided from the values. No
 * call link: the one tel:999 on the page is VerdictCard's, gated by `now.call_999` alone (F7).
 */
export function NowBlock({ paper, now, lang, proof }: { paper: PaperResultJson; now: NowJson; lang: Lang; proof: NowProof }) {
  const blocks = paper.reasons.map((reason, i) => {
    let lead: ReactNode = <p className="now-block__lead">{t(lang, LEAD[reason] ?? "now.why.other")}</p>;
    let shown: ReactNode = null;
    if (reason === "value_critical") {
      const rows = paper.values.filter((v) => v.in_now_block);
      shown = rows.length === 0 ? null : rows.map((v, n) => <div key={v.id ?? `row-${n}`}>{proof.row(v)}</div>);
    } else if (reason === "value_outside_range" && proof.outsideHref !== null) {
      lead = null;
      shown = (
        <a className="now-block__jump" href={proof.outsideHref}>
          {t(lang, "now.why.outside")}
        </a>
      );
    } else if (reason === "follow_up_past_due") {
      shown = paper.follow_up.length === 0 ? null : paper.follow_up.map((f, n) => <div key={n}>{proof.followUp(f)}</div>);
    } else if (reason === "diagnosis_present") {
      const lines = paper.doctor.diagnosis ?? [];
      lead = <p className="now-block__lead">{t(lang, "ctx.diagnosis")}</p>;
      shown = lines.length === 0 ? null : lines.map((l, n) => <div key={n}>{proof.quote(l)}</div>);
    }
    return (
      <div key={`${reason}-${i}`} className="now-block__reason" data-reason={reason}>
        {lead}
        {shown ?? proof.pages}
      </div>
    );
  });

  const share = now.remote_share
    ? `https://wa.me/?text=${encodeURIComponent(t(lang, "now.remote.share.text", paper.patient.name))}`
    : null;
  return (
    <section className="now-block" aria-label={t(lang, "result.banner.label")} data-now-block>
      {blocks}
      {share !== null && (
        <div className="now-block__remote">
          <p className="now-block__lead">{t(lang, "now.remote.lead", paper.patient.name)}</p>
          <a className="now-block__share" href={share} rel="noopener">
            {t(lang, "now.remote.share")}
          </a>
          <p className="now-block__small">{t(lang, "now.remote.share.note")}</p>
          <p className="now-block__small">{t(lang, "now.remote.999")}</p>
        </div>
      )}
      <div className="now-block__flags">
        <p className="now-block__lead">{t(lang, "now.flags.q")}</p>
        <p className="now-block__line">{t(lang, now.flags_go_key)}</p>
      </div>
      <p className="now-block__line">{t(lang, now.bring_key)}</p>
      <p className="now-block__notdoctor">{t(lang, now.notdoctor_key)}</p>
    </section>
  );
}
