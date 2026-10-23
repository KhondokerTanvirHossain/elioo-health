import { readdirSync } from "node:fs";
import { join } from "node:path";
import { cleanup, fireEvent, render, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { MePatient, NowBannerJson } from "@/api/types";
import { STATES } from "@/catalog/states";
import { t, type Lang } from "@/copy";
// B3's generated bodies (baymax NowBannerApiContractTest), never a hand fixture; the names are the catalog's
// fictional ones (baymax CatalogFixturesExportTest), so no Bangla is typed here.
import abroad from "../../../../fixtures/api/now-banner/abroad.json";
import acked from "../../../../fixtures/api/now-banner/acked.json";
import one from "../../../../fixtures/api/now-banner/one.json";
import twoPatients from "../../../../fixtures/api/now-banner/two-patients.json";
import catalogNames from "../../../../fixtures/catalog/names.json";
import { signInPath } from "@/routing/paths";
import { NowBanner } from "./NowBanner";
import { NowBannerHold } from "./NowBannerHold";
import { NowBannerSeen } from "./NowBannerSeen";

// The hold re-reads the page through the App Router on a stale tap; here that is a spy.
const router = vi.hoisted(() => ({ refresh: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router }));
// The real island, observed: theServerBannerPassesNoFunction reads the props NowBanner handed it.
vi.mock("./NowBannerSeen", async (importOriginal) => {
  const real = await importOriginal<typeof import("./NowBannerSeen")>();
  return { ...real, NowBannerSeen: vi.fn(real.NowBannerSeen) };
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  router.refresh.mockClear();
});

const LANGS: readonly Lang[] = ["bn", "en"];
const names = catalogNames.names;
const fixtureDir = join(__dirname, "..", "..", "..", "..", "fixtures", "api", "now-banner");
const FIXTURES: Record<string, NowBannerJson> = {
  "abroad.json": abroad.body,
  "acked.json": acked.body,
  "one.json": one.body,
  "two-patients.json": twoPatients.body,
};

/** A patient as GET /app/api/me lists them, reduced to the two fields the banner reads. */
const patient = (id: string, name: string): Pick<MePatient, "id" | "name"> => ({ id, name });

/** One patient per distinct patient of the banner's documents, named in order from `pool`. */
function patientsOf(banner: NowBannerJson, pool: string[]): Pick<MePatient, "id" | "name">[] {
  const ids = [...new Set(banner.documents.map((d) => d.patient_id).filter((id): id is string => id !== null))];
  return ids.map((id, i) => patient(id, pool[i % pool.length]!));
}

const alertOf = (container: HTMLElement) => {
  const alert = container.querySelector('[role="alert"]');
  expect(alert, "the banner is an alert").not.toBeNull();
  return alert as HTMLElement;
};

const titleOf = (alert: HTMLElement) => {
  const id = alert.getAttribute("aria-labelledby");
  expect(id, "the alert is named by its title").toBeTruthy();
  const title = alert.ownerDocument.getElementById(id!);
  expect(title, "the title element exists").not.toBeNull();
  expect(alert.contains(title), "the title is inside the banner").toBe(true);
  return title as HTMLElement;
};

const count = (text: string, part: string) => text.split(part).length - 1;

/** A fetch answer: `ok` from the status, and the body Java would send. */
const answer = (status: number, body?: unknown) =>
  ({
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
    text: async () => (body === undefined ? "" : JSON.stringify(body)),
  }) as unknown as Response;

/** The banner as a signed-in page renders it (shell.tsx): live, inside the hold that renders Java's answer. */
const live = (banner: NowBannerJson, patients: Pick<MePatient, "id" | "name">[], lang: Lang) =>
  render(
    <NowBannerHold>
      <NowBanner banner={banner} patients={patients} lang={lang} live />
    </NowBannerHold>,
  );

/** A promise the test settles by hand, to hold a request in flight. */
function deferred<T>() {
  let resolve!: (v: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

/** Lets pending promise callbacks run, so "nothing was sent" is checked after the click had its chance. */
const settle = () => new Promise((r) => setTimeout(r, 0));

/**
 * The vertices of an SVG path made only of straight segments (M, L, H, V, absolute or relative, closed by Z), or null
 * for any curve or arc: a circle or a rounded blob is not a polygon at all.
 */
function polygonOf(d: string): { points: [number, number][]; closed: boolean } | null {
  const points: [number, number][] = [];
  let x = 0;
  let y = 0;
  let closed = false;
  for (const [, cmd, rest] of d.matchAll(/([A-Za-z])([^A-Za-z]*)/g)) {
    const n = (rest!.match(/-?\d*\.?\d+/g) ?? []).map(Number);
    if (cmd === "Z" || cmd === "z") {
      closed = true;
      continue;
    }
    if (cmd === "H" || cmd === "h" || cmd === "V" || cmd === "v") {
      for (const v of n) {
        if (cmd === "H") x = v;
        else if (cmd === "h") x += v;
        else if (cmd === "V") y = v;
        else y += v;
        points.push([x, y]);
      }
      continue;
    }
    if (cmd !== "M" && cmd !== "L" && cmd !== "m" && cmd !== "l") return null;
    for (let k = 0; k + 1 < n.length; k += 2) {
      const relative = cmd === "m" || cmd === "l";
      x = (relative ? x : 0) + n[k]!;
      y = (relative ? y : 0) + n[k + 1]!;
      points.push([x, y]);
    }
  }
  return { points, closed };
}

describe("NowBanner", () => {
  it("hiddenWhenShowIsFalse", () => {
    expect(acked.body.show, "acked.json is the hidden case").toBe(false);
    for (const lang of LANGS) {
      const { container } = render(<NowBanner banner={acked.body} patients={patientsOf(acked.body, [names.long])} lang={lang} />);
      expect(container.innerHTML, `acked.json (${lang})`).toBe("");
      cleanup();
    }
    // The other direction: every fixture whose show is true renders an alert.
    const shown = Object.entries(FIXTURES).filter(([, b]) => b.show);
    expect(shown.length, "fixtures with show: true").toBeGreaterThanOrEqual(3);
    for (const [file, banner] of shown) {
      const { container } = render(<NowBanner banner={banner} patients={patientsOf(banner, [names.long])} lang="bn" />);
      expect(container.querySelectorAll('[role="alert"]').length, file).toBe(1);
      cleanup();
    }
  });

  it("theSafetyLineIsReadVerbatimAndTheNameIsInTheTitle", () => {
    expect(one.body.line_key).toBe("urgency.now");
    for (const lang of LANGS) {
      const name = names.long;
      const { container } = render(<NowBanner banner={one.body} patients={patientsOf(one.body, [name])} lang={lang} />);
      const alert = alertOf(container);
      const line = alert.querySelector(".now-banner__line");
      expect(line, "the safety sentence").not.toBeNull();
      expect(line!.textContent).toBe(t(lang, "urgency.now"));
      const title = titleOf(alert);
      expect(title.textContent, "the title names the patient").toContain(name);
      // The name is in the banner exactly once, and that once is the title.
      expect(count(alert.textContent ?? "", name), `the name in the whole banner (${lang})`).toBe(1);
      expect(title.contains(line), "the sentence is not inside the title").toBe(false);
      cleanup();
    }
  });

  it("theLineIsTheKeyJavaSent", () => {
    // Read from banner.line_key, not typed in the component: another key Java sent is that key's sentence.
    const other: NowBannerJson = { ...one.body, line_key: "urgency.now.short" };
    const { container } = render(<NowBanner banner={other} patients={patientsOf(other, [names.short])} lang="bn" />);
    expect(alertOf(container).querySelector(".now-banner__line")!.textContent).toBe(t("bn", "urgency.now.short"));
    cleanup();
    // A key the copy does not hold never blanks a NOW banner: it falls back to the shipped urgency.now sentence.
    const unknown: NowBannerJson = { ...one.body, line_key: "urgency.not-a-key" };
    const second = render(<NowBanner banner={unknown} patients={patientsOf(unknown, [names.short])} lang="bn" />);
    expect(alertOf(second.container).querySelector(".now-banner__line")!.textContent).toBe(t("bn", "urgency.now"));
    cleanup();
    // A key the copy does hold but that is not a NOW sentence (another surface's words, or one with a `{0}` that would
    // render raw) is not read as the safety line either. types.test.ts holds every committed fixture to a key that
    // resolves without this fallback.
    for (const key of ["attn.open", "now.remote.lead"]) {
      const foreign: NowBannerJson = { ...one.body, line_key: key };
      const third = render(<NowBanner banner={foreign} patients={patientsOf(foreign, [names.short])} lang="bn" />);
      expect(alertOf(third.container).querySelector(".now-banner__line")!.textContent, key).toBe(t("bn", "urgency.now"));
      cleanup();
    }
  });

  it("theTitleNamesThePatientsOfTheUnseenPapers", () => {
    const banner = twoPatients.body;
    const pool = [names.short, names.long];
    const patients = patientsOf(banner, pool);
    expect(patients.length, "two-patients.json has two patients").toBe(2);
    const { container } = render(<NowBanner banner={banner} patients={patients} lang="bn" />);
    const title = titleOf(alertOf(container));
    expect([...title.querySelectorAll(".now-banner__name")].map((n) => n.textContent)).toEqual(pool);
    cleanup();

    // A patient whose papers are all acknowledged is not named; the one still unseen is.
    const firstSeen: NowBannerJson = {
      ...banner,
      documents: banner.documents.map((d, i) => (i === 0 ? { ...d, acked: true } : d)),
    };
    const again = render(<NowBanner banner={firstSeen} patients={patients} lang="bn" />);
    const names2 = [...titleOf(alertOf(again.container)).querySelectorAll(".now-banner__name")].map((n) => n.textContent);
    expect(names2).toEqual([pool[1]]);
    cleanup();

    // No patient of the banner is known to the page (a stale /app/api/me): the title still names the alert.
    const unknown = render(<NowBanner banner={banner} patients={[]} lang="en" />);
    expect(titleOf(alertOf(unknown.container)).textContent).toBe(t("en", "attn.strip"));
  });

  it("theLinkOpensTheFirstUnseenPaper", () => {
    const banner = twoPatients.body;
    const [first, second] = banner.documents;
    const { container } = render(<NowBanner banner={banner} patients={patientsOf(banner, [names.short])} lang="bn" />);
    const links = [...alertOf(container).querySelectorAll<HTMLAnchorElement>("a.now-banner__open")];
    expect(links.map((a) => a.getAttribute("href"))).toEqual([`/app/documents/${first!.document_id}`]);
    expect(links[0]!.textContent).toBe(t("bn", "attn.open"));
    cleanup();

    const firstSeen: NowBannerJson = { ...banner, documents: banner.documents.map((d, i) => (i === 0 ? { ...d, acked: true } : d)) };
    const again = render(<NowBanner banner={firstSeen} patients={patientsOf(banner, [names.short])} lang="bn" />);
    const href = alertOf(again.container).querySelector("a.now-banner__open")!.getAttribute("href");
    expect(href).toBe(`/app/documents/${second!.document_id}`);
  });

  it("the999LinkFollowsTheViewer", () => {
    expect(abroad.body.call_999, "abroad.json is the viewer outside Bangladesh").toBe(false);
    for (const lang of LANGS) {
      const { container } = render(<NowBanner banner={abroad.body} patients={patientsOf(abroad.body, [names.short])} lang={lang} />);
      const tel = alertOf(container).querySelectorAll('a[href^="tel:"]');
      expect(`${tel.length} tel: links`, `abroad.json (${lang})`).toBe("0 tel: links");
      cleanup();
    }
    // The other direction: in Bangladesh exactly one, to 999, in the shipped words.
    expect(one.body.call_999).toBe(true);
    for (const lang of LANGS) {
      const { container } = render(<NowBanner banner={one.body} patients={patientsOf(one.body, [names.short])} lang={lang} />);
      const tel = [...alertOf(container).querySelectorAll<HTMLAnchorElement>('a[href^="tel:"]')];
      expect(tel.map((a) => a.getAttribute("href")), `one.json (${lang})`).toEqual(["tel:999"]);
      expect(tel[0]!.textContent).toBe(t(lang, "now.call999"));
      cleanup();
    }
  });

  it("theServerBannerPassesNoFunction", () => {
    // NowBanner is a server component: what it hands the seen island crosses to the browser, where a function cannot
    // go. With `live` it passes words, the seen list and a flag, and nothing callable (plan W5).
    const seen = vi.mocked(NowBannerSeen);
    seen.mockClear();
    render(<NowBanner banner={one.body} patients={patientsOf(one.body, [names.short])} lang="bn" live />);
    // One call per render of the island (it renders again once mounted); every one carries the same plain props.
    expect(seen.mock.calls.length, "the island was rendered").toBeGreaterThan(0);
    for (const [props] of seen.mock.calls as unknown as [Record<string, unknown>][]) {
      expect(Object.entries(props).filter(([, v]) => typeof v === "function").map(([k]) => k), "function props").toEqual([]);
      expect(props.live).toBe(true);
      expect(props.seen).toEqual(one.body.seen);
    }
  });

  it("theSeenButtonSendsWhatWasReceived", async () => {
    const banner = twoPatients.body;
    const fetchMock = vi.fn(async () => answer(200, acked.body));
    vi.stubGlobal("fetch", fetchMock);
    const { container } = live(banner, patientsOf(banner, [names.short]), "bn");
    const buttons = [...alertOf(container).querySelectorAll<HTMLButtonElement>("button")];
    expect(buttons.map((b) => b.textContent)).toEqual([t("bn", "now.banner.seen")]);
    fireEvent.click(buttons[0]!);
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));

    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("/app/api/now-banner/ack");
    expect(init.method).toBe("PUT");
    // Through apiSend: the guard's header and type, and the session cookie.
    expect(init.headers).toEqual({ "Content-Type": "application/json", "X-Medioo": "1" });
    expect(init.credentials).toBe("same-origin");
    // Exactly the generated seen set, as received: document_id, urgency, reasons_digest, release_state.
    expect(JSON.parse(String(init.body))).toEqual({ seen: twoPatients.body.seen });
  });

  it("theSeenListGoesBackUnchangedWhateverTheClientCouldDeriveFromIt", async () => {
    // two-patients.json has nothing acked and its seen list happens to be in document_id order, so a client that sent
    // only the unseen entries, or sorted them, or rebuilt them from the documents, would pass the test above. Vary only
    // what such a client would read: Java's contract is that `seen` lists every item, acknowledged or not, unchanged.
    const base = twoPatients.body;
    const firstAcked: NowBannerJson = { ...base, documents: base.documents.map((d, i) => (i === 0 ? { ...d, acked: true } : d)) };
    const reversed: NowBannerJson = { ...base, seen: [...base.seen].reverse() };
    const ids = reversed.seen.map((s) => s.document_id);
    expect(ids, "the reversed list is not in id order").not.toEqual([...ids].sort());
    const cases: Array<[string, NowBannerJson, NowBannerJson["seen"]]> = [
      ["one paper already acked", firstAcked, base.seen],
      ["the seen list in reverse order", reversed, reversed.seen],
    ];
    for (const [label, banner, sent] of cases) {
      const fetchMock = vi.fn(async () => answer(200, acked.body));
      vi.stubGlobal("fetch", fetchMock);
      const { container } = live(banner, patientsOf(banner, [names.short]), "bn");
      fireEvent.click(alertOf(container).querySelector("button")!);
      await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
      const [, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
      expect(JSON.parse(String(init.body)), label).toEqual({ seen: sent });
      cleanup();
      vi.unstubAllGlobals();
    }
  });

  it("theHoldRendersWhatTheServerAnswered", async () => {
    // The server records only what the banner shows right now (NowBannerService.acknowledge's sameShownAs) and answers
    // 200 with the banner re-read. A tap with a stale entry (the reviewer released the paper in between, or a new NOW
    // paper arrived) is a 200 whose banner still shows: the page is re-read from Java (router.refresh), never taken as
    // hidden. Only `show: false` hides it.
    vi.stubGlobal("fetch", vi.fn(async () => answer(200, twoPatients.body)));
    const stale = live(one.body, patientsOf(one.body, [names.short]), "en");
    fireEvent.click(alertOf(stale.container).querySelector("button")!);
    await waitFor(() => expect(router.refresh).toHaveBeenCalledTimes(1));
    expect(stale.container.querySelectorAll('[role="alert"]').length, "a stale tap keeps the banner").toBe(1);
    cleanup();
    vi.unstubAllGlobals();

    vi.stubGlobal("fetch", vi.fn(async () => answer(200, acked.body)));
    const hidden = live(one.body, patientsOf(one.body, [names.short]), "en");
    fireEvent.click(alertOf(hidden.container).querySelector("button")!);
    await waitFor(() => expect(hidden.container.querySelectorAll('[role="alert"]').length).toBe(0));
    expect(router.refresh, "a hidden banner needs no re-read").toHaveBeenCalledTimes(1);
  });

  it("aRefusalKeepsTheBannerAndSaysSo", async () => {
    const fetchMock = vi.fn(async () => answer(400, { reason: "INVALID" }));
    vi.stubGlobal("fetch", fetchMock);
    const { container } = live(one.body, patientsOf(one.body, [names.short]), "en");
    const alert = alertOf(container);
    expect(alert.querySelector('[role="status"]'), "no failure line before a tap").toBeNull();
    const button = alert.querySelector("button")!;
    fireEvent.click(button);
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(button.disabled, "the button is usable again after a refusal").toBe(false));
    // The refusal is said in words, announced: the banner stays, and the family is told the tap did not go through.
    await waitFor(() => expect(alert.querySelector('[role="status"]')?.textContent).toBe(t("en", "app.err.generic")));
    expect(container.querySelectorAll('[role="alert"]').length).toBe(1);
    expect(router.refresh).not.toHaveBeenCalled();
  });

  it("aFailedSendLeavesTheBannerAndTheButtonUsable", async () => {
    // Offline: fetch itself rejects. Nothing is hidden, the button comes back, and the failure line is shown.
    const fetchMock = vi.fn(async (): Promise<Response> => {
      throw new TypeError("Failed to fetch");
    });
    vi.stubGlobal("fetch", fetchMock);
    const { container } = live(one.body, patientsOf(one.body, [names.short]), "bn");
    const alert = alertOf(container);
    const button = alert.querySelector("button")!;
    fireEvent.click(button);
    await waitFor(() => expect(alert.querySelector('[role="status"]')?.textContent).toBe(t("bn", "app.err.generic")));
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(button.disabled, "the button is usable again after a failed send").toBe(false);
    expect(container.querySelectorAll('[role="alert"]').length).toBe(1);
  });

  it("aSignedOutTapKeepsTheBannerAndGoesToSignIn", async () => {
    // Review Focus 3: the session ended while the banner was up. The 401 is not "seen": the banner stays rendered, the
    // button stays busy while the page leaves, and the browser goes to sign-in holding this page (apiSend's rule).
    const assign = vi.fn();
    vi.stubGlobal("location", { pathname: "/app/preview/home", search: "?patient=p1", assign });
    vi.stubGlobal("fetch", vi.fn(async () => answer(401, { reason: "UNAUTHENTICATED" })));
    const { container } = live(one.body, patientsOf(one.body, [names.short]), "bn");
    const button = alertOf(container).querySelector("button")!;
    fireEvent.click(button);
    await waitFor(() => expect(assign).toHaveBeenCalledTimes(1));
    expect(assign).toHaveBeenCalledWith(signInPath("/app/preview/home?patient=p1"));
    await settle();
    expect(container.querySelectorAll('[role="alert"]').length, "the banner is still rendered at the 401").toBe(1);
    expect(button.disabled, "no second tap while the page leaves").toBe(true);
    expect(router.refresh).not.toHaveBeenCalled();
  });

  it("theButtonIsDisabledWhileTheAckIsInFlight", async () => {
    // A double tap must not send two acknowledgements: between the click and the answer the button is disabled.
    const pending = deferred<Response>();
    const fetchMock = vi.fn(() => pending.promise);
    vi.stubGlobal("fetch", fetchMock);
    const { container } = live(one.body, patientsOf(one.body, [names.short]), "bn");
    const button = alertOf(container).querySelector("button")!;
    fireEvent.click(button);
    await waitFor(() => expect(button.disabled, "disabled while the PUT is in flight").toBe(true));
    fireEvent.click(button);
    await settle();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    // The banner is not hidden on the tap: it waits for Java's answer.
    expect(container.querySelectorAll('[role="alert"]').length, "still shown while the PUT is in flight").toBe(1);
    pending.resolve(answer(200, acked.body));
    await waitFor(() => expect(container.querySelectorAll('[role="alert"]').length).toBe(0));
  });

  it("aBannerThatIsNotLiveSendsNothing", async () => {
    // Without `live` (the catalog), or live with no hold to hand the answer to, an acknowledgement would be stored while
    // the banner stayed up, so the island is inert: a tap sends no request at all.
    const fetchMock = vi.fn(async () => answer(200, acked.body));
    vi.stubGlobal("fetch", fetchMock);
    const cases = [
      render(
        <NowBannerHold>
          <NowBanner banner={one.body} patients={patientsOf(one.body, [names.short])} lang="bn" />
        </NowBannerHold>,
      ),
      render(<NowBanner banner={one.body} patients={patientsOf(one.body, [names.short])} lang="bn" live />),
    ];
    for (const { container } of cases) fireEvent.click(alertOf(container).querySelector("button")!);
    await settle();
    expect(fetchMock.mock.calls.length, "requests sent without a live banner in a hold").toBe(0);
  });

  it("theCatalogButtonSendsNothing", async () => {
    // The catalog's own NowBanner states, as /app/catalog-test renders them: tapping the seen button there sends no request.
    const fetchMock = vi.fn(async () => answer(200, acked.body));
    vi.stubGlobal("fetch", fetchMock);
    const states = STATES.filter((s) => s.component === "NowBanner" && !s.empty);
    let tapped = 0;
    for (const { state, render: draw } of states) {
      for (const lang of LANGS) {
        const { container } = render(draw(lang));
        const button = container.querySelector<HTMLButtonElement>('[role="alert"] button');
        expect(button, `NowBanner/${state} (${lang}) has the seen button`).not.toBeNull();
        fireEvent.click(button!);
        tapped++;
        cleanup();
      }
    }
    await settle();
    // Guard: the shown states were tapped, so zero requests is about real buttons.
    expect(tapped, "catalog seen buttons tapped").toBe(states.length * LANGS.length);
    expect(tapped).toBeGreaterThanOrEqual(10);
    expect(fetchMock.mock.calls.length, "requests sent from the catalog").toBe(0);
  });

  it("mioIsStillOnNow", () => {
    const shown = Object.entries(FIXTURES).filter(([, b]) => b.show);
    let seen = 0;
    const moving: string[] = [];
    for (const [file, banner] of shown) {
      const { container } = render(<NowBanner banner={banner} patients={patientsOf(banner, [names.short])} lang="bn" />);
      for (const img of alertOf(container).querySelectorAll("img.mio-avatar")) {
        seen++;
        // The still set exactly: any class beyond it (a motion class under any name) is Mio moving on a NOW surface.
        if (img.className !== "mio-avatar mio-avatar--head mio-avatar--32") moving.push(`${file}: ${img.className}`);
      }
      cleanup();
    }
    // Guard: Mio is on every shown banner, so the empty list is about a real avatar.
    expect(seen, "Mio avatars inside the shown banners").toBe(shown.length);
    expect(moving).toEqual([]);
  });

  it("theOctagonIsTheShape", () => {
    const { container } = render(<NowBanner banner={one.body} patients={patientsOf(one.body, [names.short])} lang="bn" />);
    const svg = alertOf(container).querySelector('svg[aria-hidden="true"]');
    expect(svg, "the shape, decorative").not.toBeNull();
    // The outline itself, read from its path: a closed polygon of eight distinct vertices with sides of one length (to
    // 10%), so a circle, a triangle or a square drawn in its place fails here.
    const d = svg!.querySelector("path")?.getAttribute("d") ?? "";
    const outline = polygonOf(d);
    expect(outline, `the outline is straight segments: ${d}`).not.toBeNull();
    expect(outline!.closed, "the outline is closed").toBe(true);
    const p = outline!.points;
    const distinct = new Set(p.map(([x, y]) => `${x.toFixed(2)},${y.toFixed(2)}`));
    expect(distinct.size, `distinct vertices of ${d}`).toBe(8);
    expect(p.length, `vertices of ${d}`).toBe(8);
    const sides = p.map(([x, y], k) => Math.hypot(p[(k + 1) % p.length]![0] - x, p[(k + 1) % p.length]![1] - y));
    expect(Math.max(...sides) / Math.min(...sides), `side lengths ${sides.map((l) => l.toFixed(2)).join()}`).toBeLessThan(1.1);
  });

  it("everyCommittedFixtureIsReadHere", () => {
    expect(Object.keys(FIXTURES).sort()).toEqual(readdirSync(fixtureDir).filter((f) => f.endsWith(".json")).sort());
  });
});
