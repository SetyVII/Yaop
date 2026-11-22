// Thin wrapper around chrome.i18n with graceful fallback for unit tests.

export function t(key, fallback = "") {
  try {
    if (typeof chrome !== "undefined" && chrome.i18n && chrome.i18n.getMessage) {
      const msg = chrome.i18n.getMessage(key);
      if (msg) return msg;
    }
  } catch {
    // ignore outside extension runtime
  }
  return fallback || key;
}

export function translateDom(root = document) {
  for (const el of root.querySelectorAll("[data-i18n]")) {
    const key = el.getAttribute("data-i18n");
    if (key) el.textContent = t(key, el.textContent);
  }
  for (const el of root.querySelectorAll("[data-i18n-placeholder]")) {
    const key = el.getAttribute("data-i18n-placeholder");
    if (key) el.setAttribute("placeholder", t(key, el.getAttribute("placeholder") || ""));
  }
  for (const el of root.querySelectorAll("[data-i18n-title]")) {
    const key = el.getAttribute("data-i18n-title");
    if (key) el.setAttribute("title", t(key, el.getAttribute("title") || ""));
  }
}
