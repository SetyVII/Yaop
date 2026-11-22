// Extension settings and persistent offline capture queue (product §22.5).

export const DEFAULT_CONFIG = Object.freeze({
  port: 47192,
  token: "",
  coverQuality: 90,     // WebP quality for web link covers (product §8.1: original resolution, WebP q90)
  imageQuality: 82,     // WebP quality for standalone saved images
  imageMaxWidth: 1920,  // Max side in px for standalone saved images
  autoClose: true,
  showNotifications: true,
});

const CONFIG_KEY = "yaop_config";
const QUEUE_KEY = "yaop_capture_queue";
const MAX_QUEUE_ITEMS = 50;

export function clampInt(val, min, max, fallback) {
  const n = Number.parseInt(String(val), 10);
  if (!Number.isFinite(n)) return fallback;
  return Math.max(min, Math.min(max, n));
}

export function sanitizeConfig(raw = {}) {
  return {
    port: clampInt(raw.port, 1024, 65535, DEFAULT_CONFIG.port),
    token: typeof raw.token === "string" ? raw.token.trim() : "",
    coverQuality: clampInt(raw.coverQuality, 40, 95, DEFAULT_CONFIG.coverQuality),
    imageQuality: clampInt(raw.imageQuality, 40, 98, DEFAULT_CONFIG.imageQuality),
    imageMaxWidth: clampInt(raw.imageMaxWidth, 480, 4096, DEFAULT_CONFIG.imageMaxWidth),
    autoClose: raw.autoClose !== undefined ? Boolean(raw.autoClose) : DEFAULT_CONFIG.autoClose,
    showNotifications:
      raw.showNotifications !== undefined
        ? Boolean(raw.showNotifications)
        : DEFAULT_CONFIG.showNotifications,
  };
}

export function isCapturableUrl(rawUrl) {
  if (typeof rawUrl !== "string" || !rawUrl.trim()) return false;
  try {
    const u = new URL(rawUrl.trim());
    return u.protocol === "http:" || u.protocol === "https:";
  } catch {
    return false;
  }
}

export async function loadConfig() {
  const data = await chrome.storage.local.get(CONFIG_KEY);
  return sanitizeConfig(data[CONFIG_KEY] || {});
}

export async function saveConfig(partial) {
  const current = await loadConfig();
  const merged = sanitizeConfig({ ...current, ...partial });
  await chrome.storage.local.set({ [CONFIG_KEY]: merged });
  return merged;
}

export async function getQueue() {
  const data = await chrome.storage.local.get(QUEUE_KEY);
  return Array.isArray(data[QUEUE_KEY]) ? data[QUEUE_KEY] : [];
}

export async function setQueue(items) {
  const trimmed = Array.isArray(items) ? items.slice(-MAX_QUEUE_ITEMS) : [];
  await chrome.storage.local.set({ [QUEUE_KEY]: trimmed });
  await updateBadge(trimmed.length);
  return trimmed;
}

export async function enqueueCapture(kind, payload, label = "") {
  const queue = await getQueue();
  const item = {
    id: `${Date.now()}-${Math.random().toString(16).slice(2, 10)}`,
    kind,
    label: label || payload.title || payload.url || kind,
    url: payload.url || payload.source_url || "",
    createdAt: new Date().toISOString(),
    payload,
  };
  queue.push(item);
  await setQueue(queue);
  return item;
}

export async function removeQueueItem(id) {
  const queue = await getQueue();
  const next = queue.filter((it) => it.id !== id);
  await setQueue(next);
  return next;
}

export async function clearQueue() {
  await setQueue([]);
  return [];
}

export async function updateBadge(count) {
  try {
    if (typeof chrome === "undefined" || !chrome.action) return;
    if (count > 0) {
      await chrome.action.setBadgeText({ text: String(count) });
      await chrome.action.setBadgeBackgroundColor({ color: "#6EAAFF" });
    } else {
      await chrome.action.setBadgeText({ text: "" });
    }
  } catch {
    // ignore in non-extension test environments
  }
}
