"use client";

import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { holdFiles } from "@/components/Upload/held";

/**
 * A paper dropped anywhere on the desktop layout (desktop.html `#drop`, plan desktop-parity U1): while files are held
 * over the window a dashed frame says where to let go (`label`, desk.drop); a drop holds the files (held.ts) and opens
 * the add sheet's dialog (`to`, deskAddHref), whose tray takes them as its first pages. Only a drag that carries files is
 * taken (a dragged link or text stays the browser's). While the add sheet is on the page its own drop target takes them
 * (UploadSheet's DropZone), so this one stands aside. Drawn only for a patient the viewer owns. It rides in the desktop
 * shell's own desk-only chunk and stylesheet (DesktopShell.desk, DesktopShell.css `.desk-drop`): on every desktop page,
 * so no request of its own (DR-42's route ceiling, DR-51).
 */
export function DropAnywhere({ to, label }: { to: string; label: string }) {
  const [on, setOn] = useState(false);
  const depth = useRef(0);
  const router = useRouter();
  useEffect(() => {
    const sheetOpen = () => document.querySelector("[data-upload-sheet]") !== null;
    const carriesFiles = (e: DragEvent) => [...(e.dataTransfer?.types ?? [])].includes("Files");
    const enter = (e: DragEvent) => {
      if (!carriesFiles(e) || sheetOpen()) return;
      e.preventDefault();
      depth.current += 1;
      setOn(true);
    };
    const over = (e: DragEvent) => {
      if (carriesFiles(e) && !sheetOpen()) e.preventDefault();
    };
    const leave = () => {
      depth.current = Math.max(0, depth.current - 1);
      if (depth.current === 0) setOn(false);
    };
    const drop = (e: DragEvent) => {
      if (!carriesFiles(e) || sheetOpen()) return;
      e.preventDefault();
      depth.current = 0;
      setOn(false);
      const files = [...(e.dataTransfer?.files ?? [])];
      if (files.length === 0) return;
      holdFiles(files);
      router.push(to, { scroll: false });
    };
    window.addEventListener("dragenter", enter);
    window.addEventListener("dragover", over);
    window.addEventListener("dragleave", leave);
    window.addEventListener("drop", drop);
    return () => {
      window.removeEventListener("dragenter", enter);
      window.removeEventListener("dragover", over);
      window.removeEventListener("dragleave", leave);
      window.removeEventListener("drop", drop);
    };
  }, [to, router]);
  return (
    <div className="desk-drop" data-on={on ? "" : undefined} aria-hidden="true">
      {label}
    </div>
  );
}
