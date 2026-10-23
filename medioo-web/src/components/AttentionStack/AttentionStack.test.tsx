import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import type { AttentionJson } from "@/api/types";
import { t, type CopyKey, type Lang } from "@/copy";
import { NOW_ALLOWED, WEEK_ALLOWED } from "@/styles/now-usage";
import { AttentionStack } from "./AttentionStack";

afterEach(cleanup);

/* Over the bodies Java's AttentionApiContractTest wrote (fixtures/api/attention/), in the order Java sent them. */
const dir = join(__dirname, "..", "..", "..", "fixtures", "api", "attention");
const stack = (name: string) => (JSON.parse(readFileSync(join(dir, `${name}.json`), "utf8")) as { body: AttentionJson }).body;
const LANGS: readonly Lang[] = ["bn", "en"];
// The possessive as GET /app/api/me writes it (NameGrammar), read from Java's fixture rather than typed here.
const POSSESSIVE = (JSON.parse(readFileSync(join(dir, "..", "me", "one-patient.json"), "utf8")) as { body: { patients: { name_possessive: string }[] } })
  .body.patients[0]!.name_possessive;

const draw = (body: AttentionJson, lang: Lang = "bn") =>
  render(<AttentionStack items={body.items} lang={lang} preview={false} possessive={POSSESSIVE} />).container;

describe("AttentionStack", () => {
  it("cardsComeInJavasOrderEachWithOneAction", () => {
    const body = stack("full-stack");
    expect(new Set(body.items.map((i) => i.kind)).size, "full-stack holds every kind").toBe(4);
    for (const lang of LANGS) {
      const root = draw(body, lang);
      const cards = [...root.querySelectorAll<HTMLElement>("[data-attention]")];
      expect(cards.map((c) => [c.dataset.attention, c.dataset.document])).toEqual(body.items.map((i) => [i.kind, i.document_id]));
      cards.forEach((card, k) => {
        const item = body.items[k]!;
        const links = [...card.querySelectorAll<HTMLAnchorElement>("a")];
        expect(links.length, `card ${k} (${item.kind}) has exactly one action`).toBe(1);
        expect(links[0]!.textContent).toBe(t(lang, item.action_key as CopyKey));
        expect(links[0]!.textContent).toBe(t(lang, "attn.open"));
        expect(links[0]!.getAttribute("href")).toBe(`/app/documents/${item.document_id}`);
      });
      expect(root.querySelector("h2")?.textContent).toBe(t(lang, "attn.heading", POSSESSIVE));
      cleanup();
    }
  });

  it("theUrgentCardIsItsLevelsBandAndLine", () => {
    const body = stack("full-stack");
    const urgent = body.items.filter((i) => i.kind === "urgent");
    expect(urgent.map((i) => (i.kind === "urgent" ? i.level : null))).toEqual(["now", "this_week"]);
    // The sentence for a level is the one Java sends as a paper's banner.line_key for that level (B2's fixtures), so
    // the attention card words NOW and THIS_WEEK as the paper and WhatsApp do; no key is typed here.
    const lineOf = (file: string) =>
      (JSON.parse(readFileSync(join(dir, "..", "documents", file), "utf8")) as { body: { banner: { line_key: CopyKey } } }).body.banner
        .line_key;
    for (const lang of LANGS) {
      const root = draw(body, lang);
      const cards = [...root.querySelectorAll<HTMLElement>('[data-attention="urgent"]')];
      expect(cards.map((c) => c.querySelector("p")?.textContent)).toEqual([
        t(lang, lineOf("now-bd.json")),
        t(lang, lineOf("this-week-pending.json")),
      ]);
      // The red band only for NOW, amber only for THIS_WEEK: each card's classes are its level's, nothing else.
      const classes = cards.map((c) => [...c.querySelectorAll("*")].flatMap((e) => [...e.classList]).map((cl) => `.${cl}`));
      expect(classes[0]!.some((c) => NOW_ALLOWED.test(c)), "the NOW card paints with a NOW class").toBe(true);
      expect(classes[1]!.some((c) => NOW_ALLOWED.test(c)), "the THIS_WEEK card paints no NOW class").toBe(false);
      expect(classes[1]!.some((c) => WEEK_ALLOWED.test(c)), "the THIS_WEEK card paints with its own class").toBe(true);
      expect(classes[0]!.some((c) => WEEK_ALLOWED.test(c)), "the NOW card paints no THIS_WEEK class").toBe(false);
      cleanup();
    }
  });

  it("aNowCardSaysItIsNotADoctor", () => {
    // The stack can show a NOW result for a paper the thread's first page does not reach, so its NOW card carries the
    // not-a-doctor line itself, as the paper's card does; the THIS_WEEK card and the plain cards do not.
    const body = stack("full-stack");
    const kinds = body.items.map((i) => (i.kind === "urgent" ? i.level : i.kind));
    expect(kinds, "full-stack holds a NOW card and cards that are not").toEqual(["now", "this_week", "retake", "follow_up", "reading"]);
    for (const lang of LANGS) {
      const root = draw(body, lang);
      const lines = [...root.querySelectorAll<HTMLElement>("[data-attention]")].map((c) =>
        [...c.querySelectorAll(".verdict-card__notdoctor")].map((p) => p.textContent),
      );
      expect(lines).toEqual(kinds.map((k) => (k === "now" ? [t(lang, "now.notdoctor")] : [])));
      cleanup();
    }
  });

  it("theFollowUpIsTheDoctorsWordsBesideTheirCrop", () => {
    const body = stack("full-stack");
    const item = body.items.find((i) => i.kind === "follow_up");
    expect(item?.kind).toBe("follow_up");
    const root = draw(body, "en");
    const card = root.querySelector<HTMLElement>('[data-attention="follow_up"]')!;
    if (item?.kind !== "follow_up") throw new Error("no follow-up");
    expect(card.textContent).toContain(item.instruction);
    expect([...card.querySelectorAll("img")].map((i) => i.getAttribute("src"))).toEqual([item.crop.src]);
  });

  it("anEmptyStackIsSilence", () => {
    const body = stack("empty");
    expect(body.items).toEqual([]);
    for (const lang of LANGS) {
      const root = draw(body, lang);
      expect(root.innerHTML).toBe("");
      cleanup();
    }
  });

  it("everyCommittedAttentionFixtureRenders", () => {
    const names = readdirSync(dir).filter((f) => f.endsWith(".json"));
    expect(names.length).toBeGreaterThanOrEqual(3);
    for (const file of names) {
      const body = stack(file.replace(/\.json$/, ""));
      expect(draw(body).querySelectorAll("[data-attention]").length, file).toBe(body.items.length);
      cleanup();
    }
  });
});
