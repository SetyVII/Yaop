import {
  deliverOrQueue,
  fetchDesktopMeta,
  flushQueue,
  lookupWebLink,
  pingDesktop,
} from "./api.js";
import {
  clearQueue,
  getQueue,
  isCapturableUrl,
  loadConfig,
  removeQueueItem,
} from "./config.js";
import { extractPageData } from "./extractor.js";
import { t, translateDom } from "./i18n.js";
import { fetchAndConvertToWebP, formatBytes } from "./webp.js";

const FALLBACK_CATEGORIES = [
  { id: "article", label: "Article" },
  { id: "blog_post", label: "Blog post" },
  { id: "video", label: "Video" },
  { id: "documentation", label: "Documentation" },
  { id: "repository", label: "Repository" },
  { id: "tutorial", label: "Tutorial" },
  { id: "tool", label: "Tool" },
  { id: "podcast", label: "Podcast" },
  { id: "social_post", label: "Social post" },
  { id: "forum_thread", label: "Forum thread" },
  { id: "product", label: "Product" },
  { id: "course", label: "Course" },
  { id: "other", label: "Other" },
];

const state = {
  cfg: null,
  tab: null,
  page: null,
  connected: false,
  existing: false,
  coverWebP: null,      // { base64, dataUrl, width, height, originalBytes, webpBytes }
  coverUrl: "",
  wlTags: [],
  noteTags: [],
};

const $ = (id) => document.getElementById(id);

function showToast(msg, kind = "ok") {
  const el = $("toast");
  el.textContent = msg;
  el.className = `toast ${kind}`;
  el.classList.remove("hidden");
}

function setStatus(kind, text) {
  const pill = $("status-pill");
  pill.className = `status-pill status-${kind}`;
  $("status-text").textContent = text;
}

function switchTab(tabName) {
  for (const btn of document.querySelectorAll(".tab")) {
    const active = btn.dataset.tab === tabName;
    btn.classList.toggle("active", active);
    btn.setAttribute("aria-selected", active ? "true" : "false");
  }
  for (const name of ["weblink", "images", "note", "queue"]) {
    $(`panel-${name}`).classList.toggle("hidden", name !== tabName);
  }
}

function renderTagChips(containerId, list) {
  const container = $(containerId);
  container.innerHTML = "";
  list.forEach((tag, idx) => {
    const chip = document.createElement("span");
    chip.className = "chip";
    chip.textContent = tag;
    const btn = document.createElement("button");
    btn.type = "button";
    btn.textContent = "×";
    btn.addEventListener("click", () => {
      list.splice(idx, 1);
      renderTagChips(containerId, list);
    });
    chip.appendChild(btn);
    container.appendChild(chip);
  });
}

function wireTagInput(inputId, containerId, list) {
  const input = $(inputId);
  const addFromInput = () => {
    const raw = input.value.replace(/^#+/, "").trim();
    if (!raw) return;
    if (!list.some((x) => x.toLowerCase() === raw.toLowerCase())) {
      list.push(raw);
      renderTagChips(containerId, list);
    }
    input.value = "";
  };
  input.addEventListener("keydown", (e) => {
    if (e.key === "Enter" || e.key === ",") {
      e.preventDefault();
      addFromInput();
    } else if (e.key === "Backspace" && input.value === "" && list.length > 0) {
      list.pop();
      renderTagChips(containerId, list);
    }
  });
  input.addEventListener("blur", addFromInput);
}

function populateCategories(categories, selectedId = "") {
  const select = $("wl-category");
  select.innerHTML = "";
  const autoOpt = document.createElement("option");
  autoOpt.value = "";
  autoOpt.textContent = t("categoryAuto", "Auto-detect");
  select.appendChild(autoOpt);

  const list = categories && categories.length > 0 ? categories : FALLBACK_CATEGORIES;
  for (const c of list) {
    const opt = document.createElement("option");
    opt.value = c.id;
    opt.textContent = c.label || c.id;
    if (c.id === selectedId) opt.selected = true;
    select.appendChild(opt);
  }
}

function populateTagSuggestions(tags) {
  const dl = $("vault-tags-list");
  dl.innerHTML = "";
  for (const item of tags || []) {
    const opt = document.createElement("option");
    opt.value = item.name;
    dl.appendChild(opt);
  }
}

async function prepareCoverWebP(imageUrl) {
  state.coverUrl = imageUrl || "";
  state.coverWebP = null;

  const preview = $("cover-preview");
  const empty = $("cover-empty");
  const stats = $("cover-stats");

  if (!imageUrl) {
    preview.classList.add("hidden");
    empty.classList.remove("hidden");
    stats.textContent = "";
    return;
  }

  empty.classList.add("hidden");
  preview.src = imageUrl;
  preview.classList.remove("hidden");
  stats.textContent = t("coverConverting", "Converting to WebP...");

  try {
    const converted = await fetchAndConvertToWebP(imageUrl, {
      maxSide: 0,
      quality: state.cfg.coverQuality,
      rejectTiny: true,
    });
    state.coverWebP = converted;
    preview.src = converted.dataUrl;
    const savedPct =
      converted.originalBytes > 0
        ? Math.round((1 - converted.webpBytes / converted.originalBytes) * 100)
        : 0;
    const delta = savedPct > 0 ? ` (-${savedPct}%)` : "";
    stats.textContent = `${converted.width}×${converted.height} · WebP ${formatBytes(converted.webpBytes)}${delta}`;
  } catch {
    state.coverWebP = null;
    stats.textContent = t("coverFallback", "Desktop will fetch & convert to WebP");
  }
}

function renderImagesTab(images) {
  const grid = $("images-grid");
  const empty = $("images-empty");
  const badge = $("images-count");
  grid.innerHTML = "";

  if (!images || images.length === 0) {
    empty.classList.remove("hidden");
    badge.classList.add("hidden");
    return;
  }

  empty.classList.add("hidden");
  badge.textContent = String(images.length);
  badge.classList.remove("hidden");

  for (const img of images) {
    const card = document.createElement("div");
    card.className = "img-card";

    const thumb = document.createElement("img");
    thumb.src = img.url;
    thumb.alt = img.alt || "";
    thumb.loading = "lazy";
    card.appendChild(thumb);

    const body = document.createElement("div");
    body.className = "img-card-body";

    const dim = document.createElement("div");
    dim.className = "img-card-dim";
    dim.textContent = img.width && img.height ? `${img.width}×${img.height}` : "image";
    body.appendChild(dim);

    const btns = document.createElement("div");
    btns.className = "img-card-btns";

    const btnSave = document.createElement("button");
    btnSave.type = "button";
    btnSave.className = "primary";
    btnSave.textContent = t("btnSaveImage", "Save WebP");
    btnSave.addEventListener("click", async () => {
      btnSave.disabled = true;
      try {
        const converted = await fetchAndConvertToWebP(img.url, {
          maxSide: state.cfg.imageMaxWidth,
          quality: state.cfg.imageQuality,
          rejectTiny: false,
        });
        const title = img.alt || (state.page && state.page.title) || "Web image";
        const res = await deliverOrQueue(
          "image",
          {
            image_base64: converted.base64,
            title,
            source_url: (state.page && state.page.url) || "",
            image_url: img.url,
            tags: state.wlTags,
            max_side: state.cfg.imageMaxWidth,
            quality: state.cfg.imageQuality,
            reviewed: false,
          },
          title
        );
        if (res.ok && res.queued) {
          showToast(t("queuedOk", "Desktop offline — added to queue"), "warn");
          await refreshQueueView();
        } else if (res.ok) {
          showToast(
            `${t("savedOk", "Saved to YAOP")} · WebP ${formatBytes(converted.webpBytes)}`,
            "ok"
          );
        } else {
          showToast(`${t("saveError", "Could not save")}: ${res.error}`, "err");
        }
      } catch (err) {
        showToast(`${t("saveError", "Could not save")}: ${err.message || err}`, "err");
      } finally {
        btnSave.disabled = false;
      }
    });

    const btnCover = document.createElement("button");
    btnCover.type = "button";
    btnCover.textContent = t("btnUseAsCover", "Use as cover");
    btnCover.addEventListener("click", async () => {
      $("wl-include-cover").checked = true;
      switchTab("weblink");
      await prepareCoverWebP(img.url);
    });

    btns.appendChild(btnSave);
    btns.appendChild(btnCover);
    body.appendChild(btns);
    card.appendChild(body);
    grid.appendChild(card);
  }
}

async function refreshQueueView() {
  const queue = await getQueue();
  const badge = $("queue-count");
  const list = $("queue-list");
  const empty = $("queue-empty");

  if (queue.length > 0) {
    badge.textContent = String(queue.length);
    badge.classList.remove("hidden");
    empty.classList.add("hidden");
  } else {
    badge.classList.add("hidden");
    empty.classList.remove("hidden");
  }

  list.innerHTML = "";
  for (const item of queue) {
    const li = document.createElement("li");
    li.className = "queue-item";

    const kind = document.createElement("span");
    kind.className = "queue-kind";
    kind.textContent = item.kind;
    li.appendChild(kind);

    const info = document.createElement("div");
    info.className = "queue-info";
    const title = document.createElement("div");
    title.className = "queue-title";
    title.textContent = item.label || item.url || item.kind;
    const sub = document.createElement("div");
    sub.className = "queue-sub";
    sub.textContent = item.lastError ? `error: ${item.lastError}` : item.url || item.createdAt;
    info.appendChild(title);
    info.appendChild(sub);
    li.appendChild(info);

    const rm = document.createElement("button");
    rm.type = "button";
    rm.className = "btn-ghost";
    rm.textContent = t("btnRemove", "Remove");
    rm.addEventListener("click", async () => {
      await removeQueueItem(item.id);
      await refreshQueueView();
    });
    li.appendChild(rm);
    list.appendChild(li);
  }
}

async function init() {
  translateDom();
  state.cfg = await loadConfig();

  // Wire navigation & inputs
  for (const btn of document.querySelectorAll(".tab")) {
    btn.addEventListener("click", () => switchTab(btn.dataset.tab));
  }
  $("btn-options").addEventListener("click", () => chrome.runtime.openOptionsPage());
  $("status-pill").addEventListener("click", () => chrome.runtime.openOptionsPage());
  wireTagInput("wl-tag-input", "wl-tag-chips", state.wlTags);
  wireTagInput("note-tag-input", "note-tag-chips", state.noteTags);
  populateCategories(FALLBACK_CATEGORIES);

  // Extract current tab metadata
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  state.tab = tab || null;

  const capturable = tab && isCapturableUrl(tab.url);
  if (!capturable) {
    $("unsupported-banner").classList.remove("hidden");
    $("btn-save-weblink").disabled = true;
    $("cover-card").classList.add("hidden");
  } else {
    let extracted = {
      url: tab.url,
      title: tab.title || "",
      description: "",
      siteName: "",
      ogType: "",
      coverUrl: "",
      faviconUrl: tab.favIconUrl || "",
      selection: "",
      images: [],
    };
    try {
      const res = await chrome.scripting.executeScript({
        target: { tabId: tab.id },
        func: extractPageData,
      });
      if (res && res[0] && res[0].result) {
        extracted = { ...extracted, ...res[0].result };
      }
    } catch {
      // keep fallback
    }
    state.page = extracted;

    $("wl-title").value = extracted.title || "";
    $("wl-url-display").textContent = extracted.url;
    $("wl-url-display").title = extracted.url;
    $("wl-desc").value = extracted.description || "";
    if (extracted.selection) {
      $("wl-highlight-wrap").classList.remove("hidden");
      $("wl-highlight").value = extracted.selection;
      $("note-body").value = extracted.selection;
    }
    $("note-title").value = extracted.title || "";

    renderImagesTab(extracted.images);
    prepareCoverWebP(extracted.coverUrl);
  }

  await refreshQueueView();

  // Check local YAOP Desktop connection + load live tags/categories + lookup URL
  if (!state.cfg.token) {
    setStatus("err", t("statusNoToken", "Configure token in Settings"));
  } else {
    const ping = await pingDesktop(state.cfg);
    if (ping.reachable && ping.authenticated) {
      state.connected = true;
      setStatus("ok", t("statusConnected", "Connected"));

      // Drain any queued captures silently now that desktop is online
      flushQueue().then((r) => {
        if (r && r.sent > 0) refreshQueueView();
      });

      const [metaRes, lookupRes] = await Promise.all([
        fetchDesktopMeta(state.cfg),
        capturable && state.page ? lookupWebLink(state.cfg, state.page.url) : Promise.resolve(null),
      ]);
      if (metaRes && metaRes.data && metaRes.data.ok) {
        populateTagSuggestions(metaRes.data.tags);
        populateCategories(metaRes.data.categories, $("wl-category").value);
      }
      if (lookupRes && lookupRes.data && lookupRes.data.found) {
        state.existing = true;
        $("existing-banner").classList.remove("hidden");
        $("btn-save-weblink").textContent = t("btnUpdateWebLink", "Update Web link");
        if (lookupRes.data.category) $("wl-category").value = lookupRes.data.category;
        if (Array.isArray(lookupRes.data.tags)) {
          for (const tg of lookupRes.data.tags) {
            if (!state.wlTags.includes(tg)) state.wlTags.push(tg);
          }
          renderTagChips("wl-tag-chips", state.wlTags);
        }
        $("wl-reviewed").checked = Boolean(lookupRes.data.reviewed);
      }
    } else if (ping.reachable && !ping.authenticated) {
      setStatus("err", t("statusAuthError", "Invalid token · check Settings"));
    } else {
      setStatus("warn", t("statusOffline", "Desktop offline · will queue"));
    }
  }

  // Save WebLink handler
  $("btn-save-weblink").addEventListener("click", async () => {
    if (!state.page || !isCapturableUrl(state.page.url)) return;
    if (!state.cfg.token) {
      chrome.runtime.openOptionsPage();
      return;
    }
    const btn = $("btn-save-weblink");
    btn.disabled = true;

    const includeCover = $("wl-include-cover").checked;
    const payload = {
      url: state.page.url,
      title: $("wl-title").value.trim() || state.page.title,
      description: $("wl-desc").value.trim(),
      site_name: state.page.siteName || "",
      og_type: state.page.ogType || "",
      category: $("wl-category").value,
      tags: [...state.wlTags],
      highlight: $("wl-highlight").value.trim(),
      note: $("wl-note").value.trim(),
      reviewed: $("wl-reviewed").checked,
      thumbnail_url: includeCover ? state.coverUrl : "",
      favicon_url: state.page.faviconUrl || "",
      cover_base64: includeCover && state.coverWebP ? state.coverWebP.base64 : "",
    };

    const res = await deliverOrQueue("web", payload, payload.title || payload.url);
    btn.disabled = false;
    if (res.ok && res.queued) {
      showToast(t("queuedOk", "Desktop offline — added to queue"), "warn");
      await refreshQueueView();
    } else if (res.ok) {
      const msg =
        res.data && res.data.existing
          ? t("updatedOk", "Updated in YAOP")
          : t("savedOk", "Saved to YAOP");
      showToast(msg, "ok");
      if (state.cfg.autoClose) {
        setTimeout(() => window.close(), 700);
      }
    } else {
      showToast(`${t("saveError", "Could not save")}: ${res.error}`, "err");
    }
  });

  // Save Note handler
  $("btn-save-note").addEventListener("click", async () => {
    if (!state.cfg.token) {
      chrome.runtime.openOptionsPage();
      return;
    }
    const title = $("note-title").value.trim();
    const text = $("note-body").value.trim();
    if (!title && !text) return;

    const btn = $("btn-save-note");
    btn.disabled = true;
    const payload = {
      title,
      text,
      source_url:
        $("note-include-url").checked && state.page && isCapturableUrl(state.page.url)
          ? state.page.url
          : "",
      tags: [...state.noteTags],
      reviewed: false,
    };
    const res = await deliverOrQueue("note", payload, title || text.slice(0, 60));
    btn.disabled = false;
    if (res.ok && res.queued) {
      showToast(t("queuedOk", "Desktop offline — added to queue"), "warn");
      await refreshQueueView();
    } else if (res.ok) {
      showToast(t("savedOk", "Saved to YAOP"), "ok");
      if (state.cfg.autoClose) {
        setTimeout(() => window.close(), 700);
      }
    } else {
      showToast(`${t("saveError", "Could not save")}: ${res.error}`, "err");
    }
  });

  // Queue buttons
  $("btn-flush-queue").addEventListener("click", async () => {
    const r = await flushQueue();
    await refreshQueueView();
    if (r.sent > 0) showToast(`${t("savedOk", "Saved to YAOP")} (${r.sent})`, "ok");
    else if (r.error) showToast(r.error, "warn");
  });

  $("btn-clear-queue").addEventListener("click", async () => {
    await clearQueue();
    await refreshQueueView();
  });
}

init();
