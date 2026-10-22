"use client";

import { useCallback, useEffect, useId, useRef, useState } from "react";
import { apiUpload } from "@/api/browser";
import type { UploadAnswerJson, UploadOptionsJson } from "@/api/types";
import type { UploadWords } from "@/app/app/(signed-in)/add/upload-words";
import { href } from "@/routing/paths";
import { DropZone } from "./DropZone";
import { PageTray, type TrayPage } from "./PageTray";
import { UploadProgress, type UploadPhase } from "./UploadProgress";
import "./Upload.css";

/**
 * A press's key (V21, D3): drawn once when the family presses send (add.send), held in memory across that press's retries, and
 * dropped the moment the press changes (a page added, moved or removed, another patient, another answer to "one
 * report?"), so the next press draws its own. Java keys a press by this AND every byte sent with it: the same key with
 * the same pages is the same paper, so a retry after the network died can never make a second one.
 */
export function uploadKey(): string {
  return crypto.randomUUID();
}

/** No page has moved for this long: the slow line (up.slow) says no page is lost. */
const SLOW_MS = 8000;

type Source = "camera" | "gallery" | "pdf";
type Group = "one" | "separate";

let pageIds = 0;

/**
 * The add sheet (plan W7; phone.html scene `chat` with the sheet open, desktop.html scene `app` with `#drop`), over
 * GET /app/api/upload-options. It never decides whose paper it is: the options' one preselected patient, or the
 * patient the page was opened for when the viewer may send for them, else nobody until the family chooses. It never
 * sends for a patient the viewer may only see, never answers "one report?" for the family, and holds the tray to
 * Java's own cap. One press is one multipart POST straight to Java (apiUpload); a press the network cut is sent again,
 * by the retry or when the network comes back, with the same key and the same bytes. Its words are resolved on the
 * server (upload-words.ts): this island imports no copy.
 *
 * Without JavaScript the server's markup stays as drawn: every control off, no `<form>` (the page's `<noscript>` says
 * a paper needs JavaScript, DR-42). `asked` is the page's `?patient=`; `replaces` its `?replaces=`, sent only while the
 * patient chosen is the one the retake link named (DR-47: Java drops any other anyway).
 */
export function UploadSheet({
  options,
  words,
  preview,
  asked,
  replaces,
}: {
  options: UploadOptionsJson;
  words: UploadWords;
  preview: boolean;
  asked: string | null;
  replaces: string | null;
}) {
  const askedViewOnly = asked !== null && options.view_only.some((p) => p.id === asked);
  const askedCanSend = asked !== null && options.can_send.some((p) => p.id === asked);
  const initial = askedViewOnly ? null : (options.preselected_patient_id ?? (askedCanSend ? asked : null));

  const [live, setLive] = useState(false);
  const [patient, setPatient] = useState<string | null>(initial);
  const [group, setGroup] = useState<Group | null>(null);
  const [pages, setPages] = useState<TrayPage[]>([]);
  const [alert, setAlert] = useState<string | null>(null);
  const [phase, setPhase] = useState<UploadPhase | null>(null);
  const key = useRef<string | null>(null);
  const lastMove = useRef(0);
  const inputs = useRef<Partial<Record<Source, HTMLInputElement | null>>>({});
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
  const max = pages.length > 1 && group === "separate" ? options.limits.max_files : options.limits.max_pages;
  const overAllowance = a.limited && a.remaining !== null && effectiveGroup === "separate" && pages.length > a.remaining;
  const busy = phase !== null;

  /** The press changed: its key is no longer this press's. */
  const changed = () => {
    key.current = null;
    setAlert(null);
  };

  const add = useCallback(
    (files: File[], src: Source | null) => {
      if (files.length === 0) return;
      key.current = null;
      setAlert(null);
      setPages((now) => {
        const cap = now.length + files.length > 1 && group === "separate" ? options.limits.max_files : options.limits.max_pages;
        const room = Math.max(0, cap - now.length);
        const taken = files.slice(0, room).map((file) => ({
          id: `p${++pageIds}`,
          file,
          url: file.type === "application/pdf" ? null : URL.createObjectURL(file),
          src: src ?? (file.type === "application/pdf" ? "pdf" : null),
        }));
        return [...now, ...taken];
      });
    },
    [group, options.limits.max_files, options.limits.max_pages],
  );

  const move = (index: number, by: -1 | 1) => {
    changed();
    setPages((now) => {
      const next = [...now];
      const [page] = next.splice(index, 1);
      next.splice(index + by, 0, page!);
      return next;
    });
  };

  const remove = (index: number) => {
    changed();
    setPages((now) => {
      const gone = now[index];
      if (gone?.url) URL.revokeObjectURL(gone.url);
      return now.filter((_, i) => i !== index);
    });
  };

  const onChoose = (src: Source) => (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = [...(e.target.files ?? [])];
    // Cleared, so choosing the same photo again is a change the input reports.
    e.target.value = "";
    add(files, src);
  };

  /** One attempt of this press, under `pressKey`: the patient, the key, the grouping, the retake it replaces, the pages. */
  const transmit = async (pressKey: string) => {
    const form = new FormData();
    form.append("patient", patient!);
    form.append("upload_key", pressKey);
    form.append("group", effectiveGroup);
    if (replaces !== null && patient === asked) form.append("replaces", replaces);
    for (const p of pages) form.append("file", p.file, p.file.name);
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
    if (result.kind === "offline") {
      // Kept on the phone, key and all: the retry (or the network coming back) sends this same press again.
      setPhase({ kind: "stuck" });
      return;
    }
    // Refused, or an answer with no body: the press made nothing, and the next press is a new one.
    key.current = null;
    setPhase(null);
    if (result.kind === "refused") {
      const separateShort = result.reason === "FREE_TIER_DOCUMENTS" && effectiveGroup === "separate" && a.limited && (a.remaining ?? 0) > 0;
      setAlert(separateShort ? words.capSeparate : (words.reasons[result.reason] ?? words.generic));
    } else {
      setAlert(words.generic);
    }
  };

  const ready =
    live && !busy && patient !== null && pages.length > 0 && (pages.length === 1 || group !== null) && !overAllowance;

  const send = () => {
    if (!ready) return;
    const tooLarge =
      pages.some((p) => p.file.size > options.limits.max_file_bytes) ||
      pages.reduce((n, p) => n + p.file.size, 0) > options.limits.max_request_bytes;
    if (tooLarge) {
      setAlert(words.reasons.TOO_LARGE ?? words.generic);
      return;
    }
    key.current ??= uploadKey();
    void transmit(key.current);
  };

  /** The same press again: the key it drew, the pages it holds. */
  const retry = () => {
    if (key.current === null) return;
    void transmit(key.current);
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
      <h1 className="upload-sheet__title" id={headingId}>
        {words.title}
      </h1>
      {body}
    </section>
  );

  // Nobody to send for, or this month's free papers read: the line, and no control at all.
  if (options.can_send.length === 0) {
    return sheet(<p className="upload-sheet__notice">{options.view_only.length > 0 ? words.viewOnly : words.noPatient}</p>);
  }
  if (capReached) return sheet(<p className="upload-sheet__notice">{words.cap}</p>);

  const sendingFor = words.patients.find((p) => p.id === patient)?.sending ?? words.up.title;
  const hint = words.onlyOne !== null && !askedViewOnly ? words.onlyOne : words.choose;

  return sheet(
    <>
      {askedViewOnly && <p className="upload-sheet__notice">{words.viewOnly}</p>}
      <DropZone label={words.drop} enabled={live && !busy} onFiles={(files) => add(files, null)} />
      {/* Always in the page, so a refusal written into it is announced (a region inserted with its text often is not). */}
      <p className="upload-sheet__alert" role="alert">
        {alert}
      </p>
      {phase !== null ? (
        <div ref={progressTitle}>
          <UploadProgress
            phase={phase}
            title={sendingFor}
            words={words.up}
            digits={words.digits}
            sizes={pages.map((p) => p.file.size)}
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
              <button type="button" className="upload-sheet__source" key={src} disabled={!live} onClick={() => inputs.current[src]?.click()}>
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

          <button type="button" className="upload-sheet__send" data-primary="" disabled={!ready} onClick={send}>
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
