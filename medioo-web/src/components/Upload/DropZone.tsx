"use client";

import { useEffect, useRef, useState } from "react";

/**
 * The desktop drop target (desktop.html `#drop`): while files are held over the window, a dashed frame over the page
 * says where to let go; a drop hands the files to the sheet's tray, exactly as the picker would. Only a drag that
 * carries files is taken (a dragged link or text is the browser's own).
 *
 * The window's listeners stay on for as long as the sheet is on the page, `enabled` or not: a drag that carries files
 * is always the sheet's (its default refused), so a photo dropped while a press is on its way, or stuck, never opens
 * in the tab and takes the family away from the pages kept on the phone. Only while `enabled` does the frame show and
 * a drop reach the tray.
 *
 * The frame has no line of its own yet: its words (`upload.drop`, a draft outside the plan) wait for the PO's yes.
 */
export function DropZone({ enabled, onFiles }: { enabled: boolean; onFiles: (files: File[]) => void }) {
  const [on, setOn] = useState(false);
  const depth = useRef(0);
  const take = useRef(onFiles);
  const open = useRef(enabled);
  useEffect(() => {
    take.current = onFiles;
    open.current = enabled;
    if (!enabled) {
      depth.current = 0;
      setOn(false);
    }
  }, [onFiles, enabled]);

  useEffect(() => {
    const carriesFiles = (e: DragEvent) => [...(e.dataTransfer?.types ?? [])].includes("Files");
    const enter = (e: DragEvent) => {
      if (!carriesFiles(e)) return;
      e.preventDefault();
      if (!open.current) return;
      depth.current += 1;
      setOn(true);
    };
    const over = (e: DragEvent) => {
      if (carriesFiles(e)) e.preventDefault();
    };
    const leave = () => {
      depth.current = Math.max(0, depth.current - 1);
      if (depth.current === 0) setOn(false);
    };
    const drop = (e: DragEvent) => {
      if (!carriesFiles(e)) return;
      e.preventDefault();
      depth.current = 0;
      setOn(false);
      if (open.current) take.current([...(e.dataTransfer?.files ?? [])]);
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
  }, []);

  return (
    <div id="drop" className="upload-drop" hidden={!on} aria-hidden="true">
      <svg className="upload-drop__icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
        <path d="M12 4v12M6 10l6 6 6-6M5 20h14" />
      </svg>
    </div>
  );
}
