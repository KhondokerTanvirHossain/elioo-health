"use client";

import { useEffect, useRef, useState } from "react";

/**
 * The toolbar's button that shows and hides the sidebar from 900 to 1199 px (plan W4; AppShell.css shows the button
 * only there, at the toolbar's far end, where the 256 px sidebar lying over the page never covers it). It names the
 * sidebar it controls (`aria-controls`), says whether it is shown (`aria-expanded`), and marks the sidebar
 * `data-open`, which is what the stylesheet reads. Escape closes it and puts the focus back on the button. `label` is
 * resolved by the server: no copy ships here.
 */
export function SidebarToggle({ label, controls }: { label: string; controls: string }) {
  const [open, setOpen] = useState(false);
  const button = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    document.getElementById(controls)?.toggleAttribute("data-open", open);
    if (!open) return;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key !== "Escape" || e.defaultPrevented || e.isComposing) return;
      setOpen(false);
      button.current?.focus();
    };
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [open, controls]);

  return (
    <button
      ref={button}
      type="button"
      className="app-shell__menu"
      aria-label={label}
      aria-controls={controls}
      aria-expanded={open}
      onClick={() => setOpen(!open)}
    >
      <svg className="app-shell__menu-icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
        <path d="M4 7h16M4 12h16M4 17h16" />
      </svg>
    </button>
  );
}
