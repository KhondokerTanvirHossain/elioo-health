import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import type { PaperJson, PaperResultJson, ThreadItemJson, ThreadJson } from "@/api/types";
import { t, type CopyKey, type Lang } from "@/copy";
import { VerdictCard } from "@/components/safety/VerdictCard/VerdictCard";
import { Thread } from "./Thread";

afterEach(cleanup);

/*
 * The thread (plan W5) over the bodies Java's ThreadApiContractTest wrote (fixtures/api/thread/) and the paper bodies
 * B2's PaperApiContractTest wrote (fixtures/api/documents/), so every case is what Java actually sends.
 */
const api = join(__dirname, "..", "..", "..", "fixtures", "api");
const LANGS: readonly Lang[] = ["bn", "en"];

function thread(name: string): ThreadJson {
  return (JSON.parse(readFileSync(join(api, "thread", `${name}.json`), "utf8")) as { body: ThreadJson }).body;
}

function document(name: string): PaperResultJson {
  return (JSON.parse(readFileSync(join(api, "documents", `${name}.json`), "utf8")) as { body: PaperResultJson }).body;
}

/** A thread of one paper card, as the thread JSON would carry it. */
const paperItem = (card: PaperJson): ThreadItemJson => ({ kind: "paper", at: card.received_at ?? "2026-10-20T04:00:00Z", card });

const draw = (items: ThreadItemJson[], lang: Lang = "bn") => render(<Thread items={items} lang={lang} preview={false} />).container;

/** The rendered items, in DOM order, each with the kind it says it is. */
const rendered = (root: HTMLElement) => [...root.querySelectorAll<HTMLElement>("[data-thread-item]")];

describe("Thread", () => {
  it("aMessageBodyIsShownAsSentAndNothingElse", () => {
    const items = thread("default").items.filter((i) => i.kind === "message");
    expect(items.length, "the default thread holds messages").toBeGreaterThan(0);
    for (const lang of LANGS) {
      const root = draw(items, lang);
      const bubbles = rendered(root).map((el) => el.querySelector(".message-bubble--recv .message-bubble__body"));
      expect(bubbles.length).toBe(items.length);
      // Java sends newest first; the thread reads top to bottom, oldest to newest, as a conversation does.
      const bodies = [...items].reverse().map((i) => (i.kind === "message" ? i.body : ""));
      bubbles.forEach((bubble, k) => {
        expect(bubble, `item ${k} is a received bubble`).not.toBeNull();
        // The text exactly as sent, newlines included, and nothing beside it inside the bubble.
        expect(bubble!.textContent).toBe(bodies[k]);
        expect(bubble!.children.length, "no element added inside the bubble").toBe(0);
      });
      cleanup();
    }
  });

  it("theThreadReadsOldestToNewest", () => {
    const items = thread("default").items;
    const ats = items.map((i) => i.at);
    expect(ats, "Java sends newest first").toEqual([...ats].sort().reverse());
    const root = draw(items);
    expect(rendered(root).map((el) => el.dataset.at)).toEqual([...ats].reverse());
  });

  it("aFailureNoticeIsListedLikeAnyMessage", () => {
    const failed = thread("failed").items;
    const failure = failed.find((i) => i.kind === "message" && i.message_kind === "failure");
    expect(failure, "failed.json carries a failure notice").toBeDefined();
    const root = draw(failed);
    const bubbles = [...root.querySelectorAll(".message-bubble--recv .message-bubble__body")].map((b) => b.textContent);
    expect(bubbles).toContain(failure!.kind === "message" ? failure!.body : "");
  });

  it("aPaperCardIsVerdictCardOverItsCard", () => {
    for (const name of ["default", "now", "retake", "failed", "share-member"]) {
      const papers = thread(name).items.filter((i): i is Extract<ThreadItemJson, { kind: "paper" }> => i.kind === "paper");
      expect(papers.length, `${name} holds a paper`).toBeGreaterThan(0);
      for (const item of papers) {
        const root = draw([item]);
        const shown = root.querySelector("article.verdict-card");
        expect(shown, `${name}: the card`).not.toBeNull();
        const alone = render(<VerdictCard paper={item.card} lang="bn" link={`/app/documents/${item.card.id}`} mio />).container;
        expect(shown!.outerHTML, `${name}: the thread's card is VerdictCard over the card Java sent`).toBe(
          alone.querySelector("article.verdict-card")!.outerHTML,
        );
        cleanup();
      }
    }
  });

  it("aPaperCardOpensItsPaper", () => {
    const [item] = thread("now").items;
    expect(item?.kind).toBe("paper");
    const root = draw([item!]);
    const card = root.querySelector("article.verdict-card")!;
    const open = [...card.querySelectorAll<HTMLAnchorElement>("a.verdict-card__open")];
    expect(open.map((a) => a.getAttribute("href"))).toEqual([`/app/documents/${(item as { card: PaperJson }).card.id}`]);
  });

  it("aRoutinePaperCarriesNoChip", () => {
    const routine = document("routine-released");
    expect(routine.banner, "routine-released has no banner").toBeNull();
    expect(routine.values.length, "and it has a value a chip could be drawn for").toBeGreaterThan(0);
    const root = draw([paperItem(routine)]);
    expect(root.querySelectorAll(".status-chip").length).toBe(0);
    expect(root.querySelectorAll("[data-value-row]").length).toBe(0);
  });

  it("everyValueChipIsTheOneJavaNamed", () => {
    const nowBd = document("now-bd");
    const unknown = nowBd.values.find((v) => v.chip_key === "value.chip.unknown");
    expect(unknown, "now-bd carries a value Java could not judge").toBeDefined();
    const cards: Array<[string, PaperResultJson]> = [
      ["documents/now-bd", nowBd],
      ["documents/this-week-pending", document("this-week-pending")],
      ["thread/now", (thread("now").items[0] as { card: PaperResultJson }).card],
      // now-bd's own unknown row alone, so that chip is drawn too: the thread's card shows at most three rows.
      ["documents/now-bd, its unknown row alone", { ...nowBd, values: [unknown!] }],
    ];
    let checked = 0;
    const drawn = new Set<string>();
    for (const [name, card] of cards) {
      expect(card.banner, `${name} has a banner`).not.toBeNull();
      const bySrc = new Map(card.values.map((v) => [v.crop.src, v]));
      expect(bySrc.size, `${name}: each value has its own crop`).toBe(card.values.length);
      for (const lang of LANGS) {
        const root = draw([paperItem(card)], lang);
        const rows = [...root.querySelectorAll<HTMLElement>("[data-value-row]")];
        expect(rows.length, `${name}: one row per value, at most three`).toBe(Math.min(3, card.values.length));
        for (const row of rows) {
          // The row's own crop, as Java named it, and the chip Java named for that same value.
          const srcs = [...row.querySelectorAll("img")].map((i) => i.getAttribute("src"));
          expect(srcs.length, `${name}: one crop per row`).toBe(1);
          const value = bySrc.get(srcs[0]!);
          expect(value, `${name}: the row's crop ${srcs[0]} is one of the card's values`).toBeDefined();
          const chips = [...row.querySelectorAll(".status-chip__word")].map((c) => c.textContent);
          expect(chips, `${name} (${value!.name})`).toEqual([t(lang, value!.chip_key as CopyKey)]);
          drawn.add(value!.chip_key);
          checked++;
        }
        cleanup();
      }
    }
    // Guard: the rows drawn include every chip kind the fixtures carry, critical among them.
    expect(checked).toBeGreaterThanOrEqual(10);
    expect([...drawn].sort()).toEqual(
      expect.arrayContaining(["value.chip.critical.high", "value.chip.inside", "value.chip.outside.low", "value.chip.unknown"]),
    );
  });

  it("aLongNowCardShowsThreeRowsUnderItsSafetyLines", () => {
    // A real NOW lab prints twenty or thirty values: now-bd's rows repeated to 25, each with its own id and crop, and
    // Java's critical row (in_now_block) deep in the list, where paper order alone would never reach it.
    const nowBd = document("now-bd");
    const critical = nowBd.values.find((v) => v.in_now_block);
    expect(critical, "now-bd has a row in its NOW block").toBeDefined();
    const others = nowBd.values.filter((v) => !v.in_now_block);
    const values = Array.from({ length: 25 }, (_, k) => {
      const base = k === 20 ? critical! : others[k % others.length]!;
      return { ...base, id: `row-${k}`, crop: { ...base.crop, src: `${base.crop.src}?row=${k}` } };
    });
    const long: PaperResultJson = { ...nowBd, values };
    expect(long.now?.call_999, "a viewer in Bangladesh").toBe(true);
    const criticalCrop = `${critical!.crop.src}?row=20`;
    for (const lang of LANGS) {
      const root = draw([paperItem(long)], lang);
      const rows = [...root.querySelectorAll<HTMLElement>("[data-value-row]")];
      expect(rows.length, "at most three rows on the thread's card").toBe(3);
      const crops = rows.map((r) => r.querySelector("img")?.getAttribute("src"));
      expect(crops, "the NOW block's row is among them").toContain(criticalCrop);
      expect(crops[0], "and it comes first").toBe(criticalCrop);
      // The not-a-doctor line and the 999 link sit under the band and the title, before the first value row.
      for (const selector of [".verdict-card__notdoctor", ".verdict-card__call"]) {
        const el = root.querySelector(selector);
        expect(el, `${selector} (${lang})`).not.toBeNull();
        expect(
          el!.compareDocumentPosition(rows[0]!) & Node.DOCUMENT_POSITION_FOLLOWING,
          `${selector} comes before the first value row (${lang})`,
        ).toBeTruthy();
      }
      cleanup();
    }
  });

  it("thePendingWordingIsNeverShown", () => {
    const pending = document("this-week-pending");
    expect(pending.gate).toBe("PENDING");
    expect(pending.review_hours, "Java sent the review hours").toBeGreaterThan(0);
    // Even a body that drifted and carried a wording while PENDING shows none of it.
    const drifted = { ...pending, wording: "drifted wording that must not show" };
    for (const lang of LANGS) {
      const root = draw([paperItem(drifted)], lang);
      expect(root.textContent).not.toContain("drifted wording that must not show");
      expect(root.querySelector(".verdict-card__wording")).toBeNull();
      expect(root.querySelector(".verdict-card__pending")?.textContent).toBe(t(lang, "result.gate.pending", pending.review_hours!));
      cleanup();
    }
  });

  it("noTelLinkInTheThreadAbroad", () => {
    const abroad = thread("now-abroad").items;
    const card = (abroad[0] as { card: PaperResultJson }).card;
    expect(card.now?.call_999, "now-abroad is a viewer outside Bangladesh").toBe(false);
    expect(draw(abroad).querySelectorAll('a[href^="tel:"]').length).toBe(0);
    cleanup();
    // The other direction: in Bangladesh, exactly the 999 link.
    const bd = draw(thread("now").items);
    expect([...bd.querySelectorAll('a[href^="tel:"]')].map((a) => a.getAttribute("href"))).toEqual(["tel:999"]);
  });

  it("everyCommittedThreadFixtureRenders", () => {
    const names = readdirSync(join(api, "thread"))
      .filter((f) => f.endsWith(".json"))
      .map((f) => f.replace(/\.json$/, ""));
    expect(names.length).toBeGreaterThanOrEqual(8);
    for (const name of names) {
      const { items } = thread(name);
      const root = draw(items);
      expect(rendered(root).length, name).toBe(items.length);
      cleanup();
    }
  });
});
