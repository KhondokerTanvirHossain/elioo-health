"use client";

import { useEffect, useRef, useState } from "react";
import { apiGet } from "@/api/browser";
import "./Crop.css";

/** What a crop says, resolved by its server parent: the retry line and the image's alternative text. */
export type CropWords = { retry: string; alt: string };

/**
 * One crop of the paper (plan W6, master Review Focus 2): the image Java serves for this row, laid out at its stored
 * size (D4b) so the page does not move when it loads. A crop that fails to load (an expired signature, a dropped
 * connection) is never a blank: it says `words.retry` with a button that loads the image again, from a fresh `<img>`
 * (a new element is a new request; resetting the same element's `src` is not). A retry that fails too asks Java
 * whether the session is still alive (`GET …/status`, which records nothing): a 401 there is the sign-in page holding
 * this place, through apiGet's rule. Without JavaScript the image is a plain `<img>`, and its error is the browser's.
 */
export function Crop({
  src,
  width,
  height,
  documentId,
  words,
  className,
}: {
  src: string;
  width: number | null;
  height: number | null;
  documentId: string;
  words: CropWords;
  className?: string;
}) {
  const [attempt, setAttempt] = useState(0);
  const [failed, setFailed] = useState(false);
  const ref = useRef<HTMLImageElement>(null);

  const fail = () => {
    setFailed(true);
    // The first failure offers the retry; a failed retry checks the session once, and a 401 leaves for sign-in.
    if (attempt > 0) void apiGet(`/app/api/documents/${documentId}/status`);
  };

  // An image that failed before React hydrated fired its error with no listener attached: judge it on mount.
  useEffect(() => {
    const img = ref.current;
    if (img && img.complete && img.naturalWidth === 0 && img.getAttribute("src")) fail();
    // Only on mount and on each new attempt's element.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [attempt]);

  if (failed) {
    return (
      <span className="crop crop--failed">
        <button
          type="button"
          className="crop__retry"
          onClick={() => {
            setFailed(false);
            setAttempt((n) => n + 1);
          }}
        >
          {words.retry}
        </button>
      </span>
    );
  }
  return (
    // eslint-disable-next-line @next/next/no-img-element -- next/image is banned (Global Constraints): Java serves the crop
    <img
      key={attempt}
      ref={ref}
      className={className ? `crop ${className}` : "crop"}
      src={src}
      alt={words.alt}
      loading="lazy"
      decoding="async"
      {...(width !== null && height !== null ? { width, height } : {})}
      onError={fail}
    />
  );
}
