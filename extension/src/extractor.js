// On-demand DOM extractor injected via chrome.scripting.executeScript (zero idle overhead).
// Must stay self-contained inside extractPageData() because scripting serializes the function.

export function extractPageData() {
  const resolveUrl = (raw) => {
    if (!raw || typeof raw !== "string") return "";
    const trimmed = raw.trim();
    if (!trimmed) return "";
    try {
      const u = new URL(trimmed, document.baseURI);
      if (u.protocol === "http:" || u.protocol === "https:") return u.href;
    } catch {
      // ignore invalid URLs
    }
    return "";
  };

  const getMeta = (selectors) => {
    for (const sel of selectors) {
      const el = document.querySelector(sel);
      if (el) {
        const val = (el.getAttribute("content") || el.getAttribute("href") || "").trim();
        if (val) return val;
      }
    }
    return "";
  };

  const pageUrl = location.href;
  const canonicalRaw = getMeta(['link[rel="canonical"]']);
  let url = pageUrl;
  if (canonicalRaw) {
    const resolved = resolveUrl(canonicalRaw);
    if (resolved) {
      try {
        // Keep canonical URL when it shares the same host (avoids broken cross-domain canonicals).
        if (new URL(resolved).host === location.host) url = resolved;
      } catch {
        // keep pageUrl
      }
    }
  }

  const title =
    getMeta([
      'meta[property="og:title"]',
      'meta[name="twitter:title"]',
      'meta[name="title"]',
    ]) || (document.title || "").trim();

  const description = getMeta([
    'meta[property="og:description"]',
    'meta[name="twitter:description"]',
    'meta[name="description"]',
  ]);

  const siteName =
    getMeta(['meta[property="og:site_name"]', 'meta[name="application-name"]']) ||
    location.hostname.replace(/^www\./, "");

  const ogType = getMeta(['meta[property="og:type"]']);

  const rawOgImage = getMeta([
    'meta[property="og:image:secure_url"]',
    'meta[property="og:image"]',
    'meta[name="twitter:image:src"]',
    'meta[name="twitter:image"]',
  ]);
  const ogImageUrl = resolveUrl(rawOgImage);

  const rawFavicon = getMeta([
    'link[rel="apple-touch-icon"]',
    'link[rel="icon"]',
    'link[rel="shortcut icon"]',
  ]);
  const faviconUrl = resolveUrl(rawFavicon) || `${location.origin}/favicon.ico`;

  const selection = (window.getSelection ? String(window.getSelection()) : "").trim();

  // Collect notable images from the DOM (sorted largest first, deduped, skipping tiny icons).
  const seen = new Set();
  const images = [];
  const pushImg = (rawUrl, alt, width, height, priority = 0) => {
    const resolved = resolveUrl(rawUrl);
    if (!resolved || seen.has(resolved)) return;
    if (/\.svg(\?|$)/i.test(resolved)) return;
    seen.add(resolved);
    images.push({
      url: resolved,
      alt: (alt || "").trim().slice(0, 140),
      width: Math.round(width || 0),
      height: Math.round(height || 0),
      score: priority + (width || 0) * (height || 0),
    });
  };

  if (ogImageUrl) {
    pushImg(ogImageUrl, title, 1200, 630, 10_000_000);
  }

  for (const img of document.querySelectorAll("img")) {
    const src = img.currentSrc || img.getAttribute("src") || "";
    const w = img.naturalWidth || img.clientWidth || 0;
    const h = img.naturalHeight || img.clientHeight || 0;
    if (w >= 120 && h >= 80) {
      pushImg(src, img.getAttribute("alt") || img.getAttribute("title") || "", w, h, 0);
    }
  }

  images.sort((a, b) => b.score - a.score);
  const topImages = images.slice(0, 18).map(({ score, ...rest }) => rest);

  return {
    url,
    title,
    description,
    siteName,
    ogType,
    coverUrl: ogImageUrl || (topImages[0] ? topImages[0].url : ""),
    faviconUrl,
    selection,
    images: topImages,
  };
}
