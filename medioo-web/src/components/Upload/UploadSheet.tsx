"use client";

import { useEffect, useId, useLayoutEffect, useRef, useState } from "react";
import { apiUpload } from "@/api/browser";
import type { UploadAnswerJson, UploadOptionsJson } from "@/api/types";
import type { UploadWords } from "@/app/app/(signed-in)/add/upload-words";
import { href } from "@/routing/paths";
import { DropZone } from "./DropZone";
import { PageTray, type TrayControl, type TrayPage } from "./PageTray";
import { downscaleOf, isKnown, preparePage, type PreparedPage } from "./prepare";
import { fillSlots } from "./slots";
import { UploadProgress, type UploadPhase } from "./UploadProgress";
import "./Upload.css";

/**
 * A press's key (V21, D3): drawn once when the family presses send (add.send), held in memory across that press's retries, and
 * dropped only when Java named a refusal (the press made nothing, and the next press is a new one). Java keys a press
 * by this AND every byte sent with it: the same key with the same pages is the same paper, so a retry after the network
 * died, or after an answer that names no refusal (a gateway's 504, a 500), can never make a second one.
 */
export function uploadKey(): string {
  return crypto.randomUUID();
}

/** No page has moved for this long: the slow line (up.slow) says no page is lost. */
const SLOW_MS = 8000;

type Source = "camera" | "gallery" | "pdf";
type Group = "one" | "separate";

/** One press as it went: everything a retry sends again, byte for byte, under the same key. */
type Press = { key: string; patient: string; group: Group; replaces: string | null; pages: PreparedPage[] };

let pageIds = 0;

/**
 * The add sheet (plan W7; phone.html scene `chat` with the sheet open, desktop.html scene `app` with `#drop`), over
 * GET /app/api/upload-options. It never decides whose paper it is: it opens on Java's `chosen_patient_id` (the HTML
 * sheet's own rule, over the page's `?patient=`), and otherwise on nobody until the family chooses. It never sends for
 * a patient the viewer may only see, never answers "one report?" for the family, holds the tray to Java's own cap and
 * takes only the kinds Java reads. One press is one multipart POST straight to Java (apiUpload), each page under
 * `page-N.ext`, never its own name; a press the network cut, or one answered with no named refusal, is sent again, by
 * the retry or when the network comes back, with the same key and the same bytes. Its words are resolved on the server
 * (upload-words.ts): this island imports no copy.
 *
 * Without JavaScript the server's markup stays as drawn: every control off, no `<form>` (the page's `<noscript>` says
 * a paper needs JavaScript, DR-42). `asked` is the page's `?patient=` (for the view-only line and the retake);
 * `replaces` its `?replaces=`, sent only while the patient chosen is the one the retake link named (DR-47: Java drops
 * any other anyway). `closeHref` is where the header's close (sheet.close) goes, resolved by the page (close-href.ts):
 * a plain link, so it closes with or without JavaScript.
 */
export function UploadSheet({
  options,
  words,
  preview,
  asked,
  replaces,
  closeHref,
}: {
  options: UploadOptionsJson;
  words: UploadWords;
  preview: boolean;
  asked: string | null;
  replaces: string | null;
  closeHref: string;
}) {
  const askedViewOnly = asked !== null && options.view_only.some((p) => p.id === asked);

  const [live, setLive] = useState(false);
  const [patient, setPatient] = useState<string | null>(options.chosen_patient_id);
  const [group, setGroup] = useState<Group | null>(null);
  const [pages, setPages] = useState<TrayPage[]>([]);
  const [alert, setAlert] = useState<string | null>(null);
  const [phase, setPhase] = useState<UploadPhase | null>(null);
  const press = useRef<Press | null>(null);
  const lastMove = useRef(0);
  const inputs = useRef<Partial<Record<Source, HTMLInputElement | null>>>({});
  const firstSource = useRef<HTMLButtonElement>(null);
  const trayRef = useRef<HTMLElement>(null);
  const alertRef = useRef<HTMLParagraphElement>(null);
  /** Where focus goes once the tray has drawn: the control on the page moved, a button still there, the tray, the alert. */
  const focusNext = useRef<{ id: string; control: TrayControl } | "tray" | "empty" | "alert" | null>(null);
  const headingId = useId();
  const groupId = useId();
  const progressTitle = useRef<HTMLDivElement>(null);

  useEffect(() => setLive(true), []);

  // The thumbnails' object URLs are the family's photos held in memory: given back when the sheet goes.
  const shown = useRef<TrayPage[]>([]);
  shown.current = pages;
  useEffect(() => () => shown.current.forEach((p) => p.url && URL.revokeObjectURL(p.url)), []);

  const a = options.allowance;
  const capReached = a.limited && a.remaining === 0;
  const effectiveGroup: Group = pages.length > 1 ? (group ?? "one") : "one";
  const capFor = (count: number) => (count > 1 && group === "separate" ? options.limits.max_files : options.limits.max_pages);
  const max = capFor(pages.length);
  const overAllowance = a.limited && a.remaining !== null && effectiveGroup === "separate" && pages.length > a.remaining;
  const busy = phase !== null;

  /** The tray changed: anything the sheet said about the last one no longer applies. */
  const changed = () => setAlert(null);

  const add = (files: File[], src: Source | null) => {
    if (files.length === 0 || busy) return;
    // A kind Java does not read (a DOCX) never leaves the phone; said as Java would say it (upload.js `known`).
    const known = files.filter(isKnown);
    const cap = capFor(pages.length + known.length);
    const room = Math.max(0, cap - pages.length);
    const taken = known.slice(0, room).map((file) => ({
      id: `p${++pageIds}`,
      file,
      url: file.type === "application/pdf" ? null : URL.createObjectURL(file),
      src: src ?? (file.type === "application/pdf" ? ("pdf" as const) : null),
    }));
    // Pages past the cap are said, at the point the sheet stops taking them, never dropped without a word.
    if (known.length > room) setAlert(fillSlots(words.tray.limit, { max: cap, n: cap }, words.digits));
    else if (known.length < files.length) setAlert(words.reasons.UNSUPPORTED_FILE ?? words.generic);
    else changed();
    if (taken.length === 0) return;
    if (pages.length === 0) focusNext.current = "tray";
    setPages([...pages, ...taken]);
  };

  const move = (index: number, by: -1 | 1) => {
    changed();
    const next = [...pages];
    const [page] = next.splice(index, 1);
    next.splice(index + by, 0, page!);
    focusNext.current = { id: page!.id, control: by === -1 ? "up" : "down" };
    setPages(next);
  };

  const remove = (index: number) => {
    changed();
    const gone = pages[index];
    if (gone?.url) URL.revokeObjectURL(gone.url);
    const next = pages.filter((_, i) => i !== index);
    // Under two pages there is nothing to ask; when the tray grows again it is asked again, neither answer chosen.
    if (next.length < 2) setGroup(null);
    const stays = next[Math.min(index, next.length - 1)];
    focusNext.current = stays === undefined ? "empty" : { id: stays.id, control: "remove" };
    setPages(next);
  };

  // Focus stays with the page the family moved; at an end, or once removed, on a button that is still there (upload.js
  // `focus`); the tray when its first page arrives; the alert when Java refused the press.
  useLayoutEffect(() => {
    const want = focusNext.current;
    focusNext.current = null;
    if (want === null) return;
    if (want === "alert") return alertRef.current?.focus();
    if (want === "empty") return firstSource.current?.focus();
    const tray = trayRef.current;
    if (!tray) return;
    if (want === "tray") return tray.querySelector<HTMLElement>("[data-tray-focus]")?.focus();
    const row = tray.querySelector(`[data-page-id="${want.id}"]`);
    const control = row?.querySelector<HTMLButtonElement>(`[data-control="${want.control}"]`);
    (control && !control.disabled ? control : row?.querySelector<HTMLButtonElement>('[data-control="remove"]'))?.focus();
  }, [pages, alert]);

  const onChoose = (src: Source) => (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = [...(e.target.files ?? [])];
    // Cleared, so choosing the same photo again is a change the input reports.
    e.target.value = "";
    add(files, src);
  };

  /** Java named a refusal: the press made nothing, its key is dropped, and the sheet says it in Java's words. */
  const refused = (reason: string) => {
    press.current = null;
    setPhase(null);
    const separateShort = reason === "FREE_TIER_DOCUMENTS" && effectiveGroup === "separate" && a.limited && (a.remaining ?? 0) > 0;
    focusNext.current = "alert";
    setAlert(separateShort && words.capSeparate !== null ? words.capSeparate : words.reasons[reason]!);
  };

  /** One attempt of a press: its key, patient, grouping, retake and pages exactly as they first went. */
  const transmit = async (p: Press) => {
    const form = new FormData();
    form.append("patient", p.patient);
    form.append("upload_key", p.key);
    form.append("group", p.group);
    if (p.replaces !== null) form.append("replaces", p.replaces);
    for (const page of p.pages) form.append("file", page.blob, page.name);
    lastMove.current = Date.now();
    setPhase({ kind: "sending", sent: 0, total: 0, slow: false });
    const result = await apiUpload<UploadAnswerJson>(form, (sent, total) => {
      lastMove.current = Date.now();
      setPhase({ kind: "sending", sent, total, slow: false });
    });
    if (result.kind === "ok" && result.body !== null) {
      setPhase({ kind: "sent" });
      location.assign(href(result.body.next, preview));
      return;
    }
    if (result.kind === "signed-out") return;
    // Only a refusal Java named proves the press stored nothing. A cut network, a gateway's 504, a 500, a reason the
    // sheet has no words for: the press may have reached Java, so it is kept on the phone, key and bytes, and the retry
    // (or the network coming back) sends the same press, which Java answers with the same paper (V21).
    if (result.kind === "refused" && result.reason !== "" && result.reason in words.reasons) return refused(result.reason);
    setPhase({ kind: "stuck" });
  };

  const ready =
    live && !busy && patient !== null && pages.length > 0 && (pages.length === 1 || group !== null) && !overAllowance;

  const send = async () => {
    if (!ready) return;
    setAlert(null);
    lastMove.current = Date.now();
    setPhase({ kind: "sending", sent: 0, total: 0, slow: false });
    // Prepared once per press (upload.js `prepareAll`): every retry sends these very bytes.
    const cfg = downscaleOf(options.limits);
    const prepared = await Promise.all(pages.map((p, i) => preparePage(p.file, i, cfg)));
    const tooLarge =
      prepared.some((p) => p.blob.size > options.limits.max_file_bytes) ||
      prepared.reduce((n, p) => n + p.blob.size, 0) > options.limits.max_request_bytes;
    if (tooLarge) {
      // Before a byte leaves the phone, in Java's words for the same refusal.
      return refused("TOO_LARGE");
    }
    press.current = {
      key: uploadKey(),
      patient: patient!,
      group: effectiveGroup,
      replaces: replaces !== null && patient === asked ? replaces : null,
      pages: prepared,
    };
    void transmit(press.current);
  };

  /** The same press again: the key it drew, the bytes it holds. */
  const retry = () => {
    if (press.current === null) return;
    void transmit(press.current);
  };

  // Stuck: the network coming back sends the press again by itself (up.slow promises it).
  const retryRef = useRef(retry);
  useEffect(() => {
    retryRef.current = retry;
  });
  useEffect(() => {
    if (phase?.kind !== "stuck") return;
    const back = () => retryRef.current();
    window.addEventListener("online", back);
    return () => window.removeEventListener("online", back);
  }, [phase?.kind]);

  // Sending or stuck, the pages are on the phone only: leaving the page (closing the tab, Back, a link) asks first, as
  // upload.js does, so "kept on the phone" (up.stuck) stays true.
  const onTheWay = phase?.kind === "sending" || phase?.kind === "stuck";
  useEffect(() => {
    if (!onTheWay) return;
    const hold = (e: BeforeUnloadEvent) => {
      e.preventDefault();
      e.returnValue = "";
    };
    window.addEventListener("beforeunload", hold);
    return () => window.removeEventListener("beforeunload", hold);
  }, [onTheWay]);

  // Sending and nothing has moved for a while: say so, and that no page is lost.
  useEffect(() => {
    if (phase?.kind !== "sending" || phase.slow) return;
    const timer = setInterval(() => {
      if (Date.now() - lastMove.current > SLOW_MS) setPhase((p) => (p?.kind === "sending" ? { ...p, slow: true } : p));
    }, 1000);
    return () => clearInterval(timer);
  }, [phase]);

  useEffect(() => {
    if (phase?.kind === "stuck" || (phase?.kind === "sending" && phase.sent === 0)) progressTitle.current?.querySelector("h2")?.focus();
  }, [phase?.kind, phase]);

  const sheet = (body: React.ReactNode) => (
    <section className="upload-sheet" aria-labelledby={headingId} data-upload-sheet="" data-live={live ? "" : undefined}>
      <div className="upload-sheet__head">
        <h1 className="upload-sheet__title" id={headingId}>
          {words.title}
        </h1>
        <a className="upload-sheet__close" href={closeHref}>
          {words.close}
        </a>
      </div>
      {body}
    </section>
  );

  // Nobody to send for, or this month's free papers read: the line, and no control at all.
  if (options.can_send.length === 0) {
    return sheet(<p className="upload-sheet__notice">{options.view_only.length > 0 ? words.viewOnly : words.noPatient}</p>);
  }
  if (capReached) return sheet(<p className="upload-sheet__notice">{words.cap}</p>);

  const sendingFor = words.patients.find((p) => p.id === (press.current?.patient ?? patient))?.sending ?? words.up.title;
  // The HTML sheet's hint: "only one, so chosen" exactly when Java preselected, else "choose" (UploadUiHandler).
  const hint = words.onlyOne ?? words.choose;

  return sheet(
    <>
      {askedViewOnly && <p className="upload-sheet__notice">{words.viewOnly}</p>}
      <DropZone enabled={live && !busy} onFiles={(files) => add(files, null)} />
      {/*
       * Always in the page and in the accessibility tree (visually hidden while empty, never display:none), so a
       * refusal written into it is announced; it takes focus when Java refuses a press, so focus never falls to body.
       */}
      <p className="upload-sheet__alert" role="alert" tabIndex={-1} ref={alertRef}>
        {alert}
      </p>
      {phase !== null ? (
        <div ref={progressTitle}>
          <UploadProgress
            phase={phase}
            title={sendingFor}
            words={words.up}
            digits={words.digits}
            sizes={(press.current?.pages.map((p) => p.blob.size) ?? pages.map((p) => p.file.size))}
            onRetry={retry}
          />
        </div>
      ) : (
        <>
          <fieldset className="upload-sheet__who" disabled={!live}>
            <legend className="upload-sheet__legend">{words.who}</legend>
            <div className="upload-sheet__chips">
              {words.patients.map((p) => (
                <label className="upload-sheet__chip" key={p.id}>
                  <input
                    className="upload-sheet__pick"
                    type="radio"
                    name="patient"
                    value={p.id}
                    checked={patient === p.id}
                    onChange={() => {
                      changed();
                      setPatient(p.id);
                    }}
                  />
                  <span>{p.label}</span>
                </label>
              ))}
            </div>
            <p className="upload-sheet__hint">{hint}</p>
          </fieldset>

          <div className="upload-sheet__sources">
            {(["camera", "gallery", "pdf"] as const).map((src) => (
              <button
                type="button"
                className="upload-sheet__source"
                key={src}
                ref={src === "camera" ? firstSource : undefined}
                disabled={!live}
                onClick={() => inputs.current[src]?.click()}
              >
                <span className="upload-sheet__source-label">{words.sources[src].label}</span>
                <span className="upload-sheet__source-sub">{words.sources[src].sub}</span>
              </button>
            ))}
            <input
              ref={(el) => {
                inputs.current.camera = el;
              }}
              type="file"
              hidden
              data-src="camera"
              accept="image/*"
              capture="environment"
              disabled={!live}
              onChange={onChoose("camera")}
            />
            <input
              ref={(el) => {
                inputs.current.gallery = el;
              }}
              type="file"
              hidden
              data-src="gallery"
              accept="image/*"
              multiple
              disabled={!live}
              onChange={onChoose("gallery")}
            />
            <input
              ref={(el) => {
                inputs.current.pdf = el;
              }}
              type="file"
              hidden
              data-src="pdf"
              accept="application/pdf,image/*"
              disabled={!live}
              onChange={onChoose("pdf")}
            />
          </div>

          {pages.length > 0 && (
            <PageTray
              ref={trayRef}
              pages={pages}
              words={words.tray}
              digits={words.digits}
              max={max}
              disabled={!live}
              onMove={move}
              onRemove={remove}
              onMore={() => inputs.current.gallery?.click()}
            />
          )}

          {pages.length > 1 && (
            <div className="upload-sheet__group">
              <p className="upload-sheet__legend" id={groupId}>
                {words.group.question}
              </p>
              <div className="upload-sheet__track" role="radiogroup" aria-labelledby={groupId}>
                {(["one", "separate"] as const).map((g) => (
                  <label className="upload-sheet__option" key={g}>
                    <input
                      className="upload-sheet__pick"
                      type="radio"
                      name="group"
                      value={g}
                      checked={group === g}
                      onChange={() => {
                        changed();
                        setGroup(g);
                      }}
                    />
                    <span>{words.group[g]}</span>
                  </label>
                ))}
              </div>
              <p className="upload-sheet__hint">{overAllowance && words.capSeparate !== null ? words.capSeparate : words.group.hint}</p>
            </div>
          )}

          <button type="button" className="upload-sheet__send" data-primary="" disabled={!ready} onClick={() => void send()}>
            {words.send}
          </button>
          {words.quota !== null && <p className="upload-sheet__quota">{words.quota}</p>}
          <section className="upload-sheet__tips" aria-labelledby={`${headingId}-tips`}>
            <h2 className="upload-sheet__tips-title" id={`${headingId}-tips`}>
              {words.tips.title}
            </h2>
            <ul className="upload-sheet__tips-list">
              {words.tips.items.map((tip) => (
                <li key={tip}>{tip}</li>
              ))}
            </ul>
          </section>
        </>
      )}
    </>,
  );
}
