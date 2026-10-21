import { readdirSync } from "node:fs";
import { join } from "node:path";
import { cleanup, fireEvent, render, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { MePatient, NowBannerJson } from "@/api/types";
import { t, type Lang } from "@/copy";
// B3's generated bodies (baymax NowBannerApiContractTest), never a hand fixture; the names are the catalog's
// fictional ones (baymax CatalogFixturesExportTest), so no Bangla is typed here.
import abroad from "../../../../fixtures/api/now-banner/abroad.json";
import acked from "../../../../fixtures/api/now-banner/acked.json";
import one from "../../../../fixtures/api/now-banner/one.json";
import twoPatients from "../../../../fixtures/api/now-banner/two-patients.json";
import catalogNames from "../../../../fixtures/catalog/names.json";
import { NowBanner } from "./NowBanner";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
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

/** A patient as GET /app/api/me lists them; only the id and the name matter to the banner. */
function patient(id: string, name: string): MePatient {
  return {
    id,
    name,
    name_possessive: name,
    name_objective: name,
    age: 64,
    sex: "female",
    owner: true,
    documents: 1,
    last_document_at: "2026-10-19T04:00:00Z",
    urgent: true,
  };
}

/** One patient per distinct patient of the banner's documents, named in order from `pool`. */
function patientsOf(banner: NowBannerJson, pool: string[]): MePatient[] {
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

  it("theSeenButtonSendsWhatWasReceived", async () => {
    const banner = twoPatients.body;
    const fetchMock = vi.fn(async () => ({ ok: true, status: 200 }) as Response);
    vi.stubGlobal("fetch", fetchMock);
    const onSeen = vi.fn();
    const { container } = render(<NowBanner banner={banner} patients={patientsOf(banner, [names.short])} lang="bn" onSeen={onSeen} />);
    const buttons = [...alertOf(container).querySelectorAll<HTMLButtonElement>("button")];
    expect(buttons.map((b) => b.textContent)).toEqual([t("bn", "now.banner.seen")]);
    fireEvent.click(buttons[0]!);
    await waitFor(() => expect(onSeen).toHaveBeenCalledTimes(1));

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("/app/api/now-banner/ack");
    expect(init.method).toBe("PUT");
    expect(init.headers).toEqual({ "Content-Type": "application/json", "X-Medioo": "1" });
    // Exactly the generated seen set, as received: document_id, urgency, reasons_digest, release_state.
    expect(JSON.parse(String(init.body))).toEqual({ seen: twoPatients.body.seen });
  });

  it("onSeenOnlyAfterTheServerAccepted", async () => {
    const banner = one.body;
    const fetchMock = vi.fn(async () => ({ ok: false, status: 400 }) as Response);
    vi.stubGlobal("fetch", fetchMock);
    const onSeen = vi.fn();
    const { container } = render(<NowBanner banner={banner} patients={patientsOf(banner, [names.short])} lang="en" onSeen={onSeen} />);
    const button = alertOf(container).querySelector("button")!;
    fireEvent.click(button);
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(button.disabled, "the button is usable again after a refusal").toBe(false));
    expect(onSeen).not.toHaveBeenCalled();
  });

  it("mioIsStillOnNow", () => {
    const shown = Object.entries(FIXTURES).filter(([, b]) => b.show);
    let seen = 0;
    const moving: string[] = [];
    for (const [file, banner] of shown) {
      const { container } = render(<NowBanner banner={banner} patients={patientsOf(banner, [names.short])} lang="bn" />);
      for (const img of alertOf(container).querySelectorAll("img.mio-avatar")) {
        seen++;
        if (img.classList.contains("mio-avatar--hop") || img.classList.contains("mio-avatar--wave")) moving.push(`${file}: ${img.className}`);
      }
      cleanup();
    }
    // Guard: Mio is on every shown banner, so the empty list is about a real avatar.
    expect(seen, "Mio avatars inside the shown banners").toBe(shown.length);
    expect(moving).toEqual([]);
  });

  it("theOctagonIsTheShape", () => {
    const { container } = render(<NowBanner banner={one.body} patients={patientsOf(one.body, [names.short])} lang="bn" />);
    const svg = alertOf(container).querySelector('svg[data-shape="octagon"][aria-hidden="true"]');
    expect(svg, "the octagon, decorative").not.toBeNull();
    expect(svg!.children.length, "the octagon draws something").toBeGreaterThan(0);
  });

  it("everyCommittedFixtureIsReadHere", () => {
    expect(Object.keys(FIXTURES).sort()).toEqual(readdirSync(fixtureDir).filter((f) => f.endsWith(".json")).sort());
  });
});
