// Browser-side WebP conversion using createImageBitmap + OffscreenCanvas.
// Works in both the popup window and the MV3 background service worker.

export function computeScaledDimensions(width, height, maxSide) {
  if (!width || !height || width <= 0 || height <= 0) {
    return { width: 0, height: 0, resized: false };
  }
  if (!maxSide || maxSide <= 0 || (width <= maxSide && height <= maxSide)) {
    return { width: Math.round(width), height: Math.round(height), resized: false };
  }
  const scale = maxSide / Math.max(width, height);
  return {
    width: Math.max(1, Math.round(width * scale)),
    height: Math.max(1, Math.round(height * scale)),
    resized: true,
  };
}

export function arrayBufferToBase64(buffer) {
  const bytes = new Uint8Array(buffer);
  const chunkSize = 0x8000;
  let binary = "";
  for (let i = 0; i < bytes.length; i += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunkSize));
  }
  return btoa(binary);
}

export function formatBytes(bytes) {
  if (!Number.isFinite(bytes) || bytes < 0) return "0 B";
  if (bytes < 1024) return `${bytes} B`;
  const kb = bytes / 1024;
  if (kb < 1024) return `${kb.toFixed(kb < 10 ? 1 : 0)} KB`;
  return `${(kb / 1024).toFixed(2)} MB`;
}

export async function convertBlobToWebP(
  blob,
  { maxSide = 0, quality = 90, rejectTiny = false } = {}
) {
  if (!blob || blob.size === 0) {
    throw new Error("empty_blob");
  }
  // Cap input at 20 MB to avoid hanging the worker on gigantic assets.
  if (blob.size > 20 * 1024 * 1024) {
    throw new Error("image_too_large");
  }

  const bitmap = await createImageBitmap(blob);
  try {
    const srcW = bitmap.width;
    const srcH = bitmap.height;
    if (rejectTiny && (srcW < 32 || srcH < 32)) {
      throw new Error("tiny_image");
    }

    const { width, height, resized } = computeScaledDimensions(srcW, srcH, maxSide);
    const originalBytes = blob.size;

    // Keep already-WebP blobs directly when no downscaling is needed.
    if (blob.type === "image/webp" && !resized) {
      const buf = await blob.arrayBuffer();
      const base64 = arrayBufferToBase64(buf);
      return {
        base64,
        dataUrl: `data:image/webp;base64,${base64}`,
        mime: "image/webp",
        width,
        height,
        originalBytes,
        webpBytes: buf.byteLength,
      };
    }

    const canvas = new OffscreenCanvas(width, height);
    const ctx = canvas.getContext("2d");
    if (!ctx) throw new Error("canvas_unavailable");
    ctx.drawImage(bitmap, 0, 0, width, height);

    const q = Math.max(0.1, Math.min(1, quality / 100));
    const webpBlob = await canvas.convertToBlob({ type: "image/webp", quality: q });
    const outBuf = await webpBlob.arrayBuffer();
    const base64 = arrayBufferToBase64(outBuf);
    const mime = webpBlob.type || "image/webp";

    return {
      base64,
      dataUrl: `data:${mime};base64,${base64}`,
      mime,
      width,
      height,
      originalBytes,
      webpBytes: outBuf.byteLength,
    };
  } finally {
    bitmap.close();
  }
}

export async function fetchImageBlob(imageUrl, timeoutMs = 8000) {
  const tryFetch = async (credentials) => {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), timeoutMs);
    try {
      const res = await fetch(imageUrl, {
        credentials,
        signal: ctrl.signal,
        cache: "force-cache",
      });
      if (!res.ok) throw new Error(`http_${res.status}`);
      const blob = await res.blob();
      if (!blob || blob.size === 0) throw new Error("empty_response");
      return blob;
    } finally {
      clearTimeout(timer);
    }
  };

  try {
    return await tryFetch("include");
  } catch {
    return await tryFetch("omit");
  }
}

export async function fetchAndConvertToWebP(imageUrl, opts = {}) {
  const blob = await fetchImageBlob(imageUrl);
  return await convertBlobToWebP(blob, opts);
}
