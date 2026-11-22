// YAOP Web Clipper background service worker (MV3).
// Handles context menus, keyboard shortcut quick-save, WebP image conversion, and offline queue flush.

import { deliverOrQueue, flushQueue } from "./api.js";
import { getQueue, isCapturableUrl, loadConfig, updateBadge } from "./config.js";
import { extractPageData } from "./extractor.js";
import { t } from "./i18n.js";
import { fetchAndConvertToWebP } from "./webp.js";

const ALARM_FLUSH = "yaop_flush_queue";

const MENU_IDS = {
  SAVE_PAGE: "yaop_save_page",
  SAVE_LINK: "yaop_save_link",
  SAVE_HIGHLIGHT: "yaop_save_highlight",
  SAVE_NOTE: "yaop_save_note",
  SAVE_IMAGE: "yaop_save_image",
};

function setupContextMenus() {
  chrome.contextMenus.removeAll(() => {
    chrome.contextMenus.create({
      id: MENU_IDS.SAVE_PAGE,
      title: t("menuSavePage", "Save page to YAOP Web links"),
      contexts: ["page"],
    });
    chrome.contextMenus.create({
      id: MENU_IDS.SAVE_LINK,
      title: t("menuSaveLink", "Save link to YAOP Web links"),
      contexts: ["link"],
    });
    chrome.contextMenus.create({
      id: MENU_IDS.SAVE_HIGHLIGHT,
      title: t("menuSaveHighlight", "Save highlight to YAOP Web link"),
      contexts: ["selection"],
    });
    chrome.contextMenus.create({
      id: MENU_IDS.SAVE_NOTE,
      title: t("menuSaveNote", "Save selection as YAOP Note"),
      contexts: ["selection"],
    });
    chrome.contextMenus.create({
      id: MENU_IDS.SAVE_IMAGE,
      title: t("menuSaveImage", "Save image to YAOP (WebP)"),
      contexts: ["image"],
    });
  });
}

async function notify(message) {
  try {
    const cfg = await loadConfig();
    if (!cfg.showNotifications) return;
    chrome.notifications.create({
      type: "basic",
      iconUrl: chrome.runtime.getURL("icons/icon128.png"),
      title: "YAOP",
      message,
    });
  } catch {
    // ignore if notifications unavailable
  }
}

async function extractFromTab(tab) {
  const fallback = {
    url: (tab && tab.url) || "",
    title: (tab && tab.title) || "",
    description: "",
    siteName: "",
    ogType: "",
    coverUrl: "",
    faviconUrl: (tab && tab.favIconUrl) || "",
    selection: "",
    images: [],
  };
  if (!tab || !tab.id || !isCapturableUrl(tab.url)) return fallback;
  try {
    const results = await chrome.scripting.executeScript({
      target: { tabId: tab.id },
      func: extractPageData,
    });
    if (results && results[0] && results[0].result) {
      return { ...fallback, ...results[0].result };
    }
  } catch {
    // Restricted pages or unloaded tabs fall back to tab title + URL.
  }
  return fallback;
}

async function quickSaveWebLink(tab, { urlOverride = "", highlight = "" } = {}) {
  const cfg = await loadConfig();
  if (!cfg.token) {
    await notify(t("notifNeedToken", "Configure your YAOP token in settings"));
    chrome.runtime.openOptionsPage();
    return;
  }

  let payload;
  if (urlOverride) {
    if (!isCapturableUrl(urlOverride)) return;
    payload = {
      url: urlOverride,
      highlight,
      fetch_metadata: true,
    };
  } else {
    const page = await extractFromTab(tab);
    if (!isCapturableUrl(page.url)) return;
    let coverBase64 = "";
    if (page.coverUrl) {
      try {
        const converted = await fetchAndConvertToWebP(page.coverUrl, {
          maxSide: 0,
          quality: cfg.coverQuality,
          rejectTiny: true,
        });
        coverBase64 = converted.base64;
      } catch {
        // Desktop will fall back to downloading thumbnail_url and converting to WebP.
      }
    }
    payload = {
      url: page.url,
      title: page.title,
      description: page.description,
      site_name: page.siteName,
      og_type: page.ogType,
      thumbnail_url: page.coverUrl,
      favicon_url: page.faviconUrl,
      cover_base64: coverBase64,
      highlight: highlight || "",
      reviewed: false,
    };
  }

  const res = await deliverOrQueue("web", payload, payload.title || payload.url);
  if (res.ok && res.queued) {
    await notify(t("notifQueued", "YAOP Desktop closed — capture queued"));
  } else if (res.ok) {
    await notify(t("notifSavedWeb", "Web link saved to YAOP"));
  } else {
    await notify(`${t("saveError", "Could not save")}: ${res.error}`);
  }
}

async function quickSaveImage(srcUrl, tab) {
  const cfg = await loadConfig();
  if (!cfg.token) {
    await notify(t("notifNeedToken", "Configure your YAOP token in settings"));
    chrome.runtime.openOptionsPage();
    return;
  }
  if (!srcUrl || (!isCapturableUrl(srcUrl) && !srcUrl.startsWith("data:image/"))) return;

  try {
    const converted = await fetchAndConvertToWebP(srcUrl, {
      maxSide: cfg.imageMaxWidth,
      quality: cfg.imageQuality,
      rejectTiny: false,
    });
    let fallbackName = (tab && tab.title) || "Web image";
    try {
      const pathPart = new URL(srcUrl).pathname.split("/").pop() || "";
      const stem = decodeURIComponent(pathPart).replace(/\.[a-z0-9]{2,5}$/i, "").trim();
      if (stem && stem.length <= 80) fallbackName = stem;
    } catch {
      // keep fallbackName
    }
    const payload = {
      image_base64: converted.base64,
      title: fallbackName,
      source_url: (tab && isCapturableUrl(tab.url)) ? tab.url : "",
      image_url: isCapturableUrl(srcUrl) ? srcUrl : "",
      max_side: cfg.imageMaxWidth,
      quality: cfg.imageQuality,
      reviewed: false,
    };
    const res = await deliverOrQueue("image", payload, fallbackName);
    if (res.ok && res.queued) {
      await notify(t("notifQueued", "YAOP Desktop closed — capture queued"));
    } else if (res.ok) {
      await notify(t("notifSavedImage", "WebP image saved to YAOP"));
    } else {
      await notify(`${t("saveError", "Could not save")}: ${res.error}`);
    }
  } catch (err) {
    await notify(`${t("saveError", "Could not save")}: ${err.message || err}`);
  }
}

async function quickSaveNote(selectionText, tab) {
  const cfg = await loadConfig();
  if (!cfg.token) {
    await notify(t("notifNeedToken", "Configure your YAOP token in settings"));
    chrome.runtime.openOptionsPage();
    return;
  }
  const clean = (selectionText || "").trim();
  if (!clean) return;
  const payload = {
    title: (tab && tab.title) || clean.slice(0, 72),
    text: clean,
    as_quote: true,
    source_url: (tab && isCapturableUrl(tab.url)) ? tab.url : "",
    reviewed: false,
  };
  const res = await deliverOrQueue("note", payload, payload.title);
  if (res.ok && res.queued) {
    await notify(t("notifQueued", "YAOP Desktop closed — capture queued"));
  } else if (res.ok) {
    await notify(t("notifSavedNote", "Note saved to YAOP"));
  } else {
    await notify(`${t("saveError", "Could not save")}: ${res.error}`);
  }
}

chrome.runtime.onInstalled.addListener(async () => {
  setupContextMenus();
  chrome.alarms.create(ALARM_FLUSH, { periodInMinutes: 1 });
  const queue = await getQueue();
  await updateBadge(queue.length);
});

chrome.runtime.onStartup.addListener(async () => {
  chrome.alarms.create(ALARM_FLUSH, { periodInMinutes: 1 });
  const queue = await getQueue();
  await updateBadge(queue.length);
  await flushQueue();
});

chrome.alarms.onAlarm.addListener(async (alarm) => {
  if (alarm.name === ALARM_FLUSH) {
    await flushQueue();
  }
});

chrome.contextMenus.onClicked.addListener(async (info, tab) => {
  switch (info.menuItemId) {
    case MENU_IDS.SAVE_PAGE:
      await quickSaveWebLink(tab);
      break;
    case MENU_IDS.SAVE_LINK:
      await quickSaveWebLink(tab, { urlOverride: info.linkUrl || "" });
      break;
    case MENU_IDS.SAVE_HIGHLIGHT:
      await quickSaveWebLink(tab, { highlight: info.selectionText || "" });
      break;
    case MENU_IDS.SAVE_NOTE:
      await quickSaveNote(info.selectionText || "", tab);
      break;
    case MENU_IDS.SAVE_IMAGE:
      await quickSaveImage(info.srcUrl || "", tab);
      break;
  }
});

chrome.commands.onCommand.addListener(async (command) => {
  if (command === "quick-save-weblink") {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    if (tab) await quickSaveWebLink(tab);
  }
});

// Popup / options messages (e.g. force flush).
chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (msg && msg.type === "FLUSH_QUEUE") {
    flushQueue().then(sendResponse);
    return true;
  }
  return false;
});
