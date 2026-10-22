"use client";

import { useEffect, useRef, useState } from "react";

/**
 * The desktop drop target (desktop.html `#drop`): while files are held over the window, a dashed frame over the page
 * says where to let go; a drop hands the files to the sheet's tray, exactly as the picker would. Only a drag that
 * carries files is taken (a dragged link or text is the browser's own). The line is the sheet's (upload.drop), resolved
 * on the server. Off (`enabled` false) while nothing may be added, so a drop never lands on a sheet that cannot send.
 */
export function DropZone({ label, enabled, onFiles }: { label: string; enabled: boolean; onFiles: (files: File[]) => void }) {
  const [on, setOn] = useState(false);
  const depth = useRef(0);
  const take = useRef(onFiles);
  useEffect(() => {
    take.current = onFiles;
  }, [onFiles]);

  useEffect(() => {
    if (!enabled) return;
    const carriesFiles = (e: DragEvent) => [...(e.dataTransfer?.types ?? [])].includes("Files");
    const enter = (e: DragEvent) => {
      if (!carriesFiles(e)) return;
      e.preventDefault();
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
      take.current([...(e.dataTransfer?.files ?? [])]);
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
  }, [enabled]);

  return (
    <div id="drop" className="drop-zone" hidden={!on} aria-hidden="true">
      <span className="drop-zone__line">{label}</span>
    </div>
  );
}
