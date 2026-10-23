"use client";

import { useEffect } from "react";

/**
 * Opens the chat page on the newest exchange (the direction contract: "the thread ends on the newest exchange"): once,
 * on mount, the window is scrolled to the end of the page, where the docked composer sits at its own place just after
 * the newest item, so nothing is under it. The page lays out oldest first, so this is all it takes; images that load
 * later can push the end down, so it scrolls once more at `load` unless the family has already scrolled, pressed a key
 * or touched the page. Without JavaScript it does nothing and the page reads from the top.
 *
 * `hold`: a NOW result is on the page (the banner, or a NOW card in the attention stack). The page is then left at the
 * top, where that result is, so it is read first and never scrolled away by the page itself.
 */
export function OpenAtNewest({ hold }: { hold: boolean }) {
  useEffect(() => {
    if (hold) return;
    const end = () => window.scrollTo({ top: document.documentElement.scrollHeight, behavior: "instant" });
    end();
    if (document.readyState === "complete") return;
    let moved = false;
    const mark = () => {
      moved = true;
    };
    const onLoad = () => {
      if (!moved) end();
    };
    const signals = ["wheel", "touchstart", "keydown", "pointerdown"] as const;
    for (const s of signals) window.addEventListener(s, mark, { passive: true });
    window.addEventListener("load", onLoad, { once: true });
    return () => {
      for (const s of signals) window.removeEventListener(s, mark);
      window.removeEventListener("load", onLoad);
    };
  }, [hold]);
  return null;
}
