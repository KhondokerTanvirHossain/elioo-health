import type { UploadOptionsJson } from "@/api/types";

/*
 * What a page is before it leaves the phone (plan W7 review), ported from Java's own sheet (upload.js `known`,
 * `plan`, `prepare`, `pageName`) so the two sheets send the same thing: only the kinds Java reads, under a name that is
 * never the file's own (it may name the patient), scaled down only when the server turned that on.
 */

/** The kinds Java reads; anything else is refused before it leaves the phone (upload.js `known`). An unnamed type goes. */
export const KNOWN_TYPE = /^(image\/(jpeg|png|webp|heic|heif)|application\/pdf)$/;

export const isKnown = (file: File) => file.type === "" || KNOWN_TYPE.test(file.type);

/** The only name a page is sent under: never the file's own, which may name the patient (upload.js `pageName`). */
export const pageName = (i: number, ext: string) => `page-${i + 1}.${ext}`;

/** A page's extension by its type, as upload.js `prepare` picks it. */
export const extOf = (type: string) => (type === "application/pdf" ? "pdf" : type === "image/png" ? "png" : "jpg");

type Downscale = { edge: number; quality: number; passBytes: number };

export const downscaleOf = (limits: UploadOptionsJson["limits"]): Downscale => ({
  edge: limits.downscale_max_edge,
  quality: limits.downscale_quality,
  passBytes: limits.pass_through_bytes,
});

/**
 * null = send the file as it is (upload.js `plan`). Off while `edge` is 0; only JPEG, PNG and WebP; only what is
 * bigger than the target is scaled, and a JPEG already within the edge and under `passBytes` is never re-compressed.
 */
export function plan(width: number, height: number, bytes: number, type: string, cfg: Downscale) {
  if (!cfg.edge || !/^image\/(jpeg|png|webp)$/.test(type)) return null;
  const longest = Math.max(width, height);
  if (type === "image/jpeg" && longest <= cfg.edge && bytes <= cfg.passBytes) return null;
  const scale = Math.min(1, cfg.edge / longest);
  return { width: Math.max(1, Math.round(width * scale)), height: Math.max(1, Math.round(height * scale)), quality: cfg.quality };
}

/** One page as it is sent: its bytes and the name it goes under. */
export type PreparedPage = { blob: Blob; name: string };

/**
 * One page, as upload.js `prepare` makes it: as it is when downscaling is off or the browser cannot decode it, else
 * drawn on a white canvas (a JPEG has no alpha) at the planned size, and kept only when that is smaller.
 */
export async function preparePage(file: File, i: number, cfg: Downscale): Promise<PreparedPage> {
  const ext = extOf(file.type);
  const asIs = { blob: file, name: pageName(i, ext) };
  if (!cfg.edge || typeof createImageBitmap !== "function" || !/^image\/(jpeg|png|webp)$/.test(file.type)) return asIs;
  try {
    const bmp = await createImageBitmap(file, { imageOrientation: "from-image" });
    const p = plan(bmp.width, bmp.height, file.size, file.type, cfg);
    if (!p) {
      bmp.close();
      return asIs;
    }
    const canvas = document.createElement("canvas");
    canvas.width = p.width;
    canvas.height = p.height;
    const g = canvas.getContext("2d");
    if (!g) {
      bmp.close();
      return asIs;
    }
    g.fillStyle = "white";
    g.fillRect(0, 0, p.width, p.height);
    g.imageSmoothingQuality = "high";
    g.drawImage(bmp, 0, 0, p.width, p.height);
    bmp.close();
    const blob = await new Promise<Blob | null>((ok) => canvas.toBlob(ok, "image/jpeg", p.quality));
    return blob && blob.size < file.size ? { blob, name: pageName(i, "jpg") } : asIs;
  } catch {
    // A format this browser cannot decode goes as it is; the server decides.
    return asIs;
  }
}
