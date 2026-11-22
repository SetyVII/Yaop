// Local HTTP client for YAOP Desktop's CaptureBridge (127.0.0.1:<port>).

import { enqueueCapture, getQueue, loadConfig, setQueue } from "./config.js";

export function baseUrlFor(cfg) {
  return `http://127.0.0.1:${cfg.port}`;
}

async function requestJson(cfg, method, path, body = null, timeoutMs = 6000) {
  if (!cfg.token) {
    return { reachable: false, authenticated: false, status: 0, error: "no_token", data: null };
  }
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const headers = { Authorization: `Bearer ${cfg.token}` };
    const init = { method, headers, signal: ctrl.signal };
    if (body !== null) {
      headers["Content-Type"] = "application/json";
      init.body = JSON.stringify(body);
    }
    const res = await fetch(baseUrlFor(cfg) + path, init);
    let data = null;
    try {
      data = await res.json();
    } catch {
      data = null;
    }
    if (res.status === 401) {
      return { reachable: true, authenticated: false, status: 401, error: "unauthorized", data };
    }
    if (!res.ok) {
      return {
        reachable: true,
        authenticated: true,
        status: res.status,
        error: (data && data.error) || `http_${res.status}`,
        data,
      };
    }
    return { reachable: true, authenticated: true, status: res.status, error: null, data };
  } catch (err) {
    return {
      reachable: false,
      authenticated: false,
      status: 0,
      error: err && err.name === "AbortError" ? "timeout" : "offline",
      data: null,
    };
  } finally {
    clearTimeout(timer);
  }
}

export async function pingDesktop(cfg) {
  return requestJson(cfg, "GET", "/v1/ping", null, 2500);
}

export async function fetchDesktopMeta(cfg) {
  return requestJson(cfg, "GET", "/v1/meta", null, 3500);
}

export async function lookupWebLink(cfg, url) {
  return requestJson(cfg, "GET", `/v1/lookup?url=${encodeURIComponent(url)}`, null, 3000);
}

export function endpointForKind(kind) {
  if (kind === "image") return "/v1/capture/image";
  if (kind === "note") return "/v1/capture/note";
  return "/v1/capture/weblink";
}

export async function sendCapture(cfg, kind, payload) {
  const timeout = kind === "image" ? 15000 : 10000;
  return requestJson(cfg, "POST", endpointForKind(kind), payload, timeout);
}

// Delivers immediately if YAOP Desktop is reachable; queues locally when closed (product §22.5).
export async function deliverOrQueue(kind, payload, label = "") {
  const cfg = await loadConfig();
  if (!cfg.token) {
    return { ok: false, queued: false, error: "no_token", data: null };
  }
  const res = await sendCapture(cfg, kind, payload);
  if (res.reachable && res.authenticated && !res.error) {
    return { ok: true, queued: false, error: null, data: res.data };
  }
  if (!res.reachable) {
    await enqueueCapture(kind, payload, label);
    return { ok: true, queued: true, error: null, data: null };
  }
  return { ok: false, queued: false, error: res.error || "failed", data: res.data };
}

export async function flushQueue() {
  const queue = await getQueue();
  if (queue.length === 0) return { sent: 0, remaining: 0, error: null };

  const cfg = await loadConfig();
  if (!cfg.token) return { sent: 0, remaining: queue.length, error: "no_token" };

  const ping = await pingDesktop(cfg);
  if (!ping.reachable) return { sent: 0, remaining: queue.length, error: "offline" };
  if (!ping.authenticated) return { sent: 0, remaining: queue.length, error: "unauthorized" };

  let sent = 0;
  const remaining = [];
  let stopEarly = false;

  for (const item of queue) {
    if (stopEarly) {
      remaining.push(item);
      continue;
    }
    const res = await sendCapture(cfg, item.kind, item.payload);
    if (res.reachable && res.authenticated && !res.error) {
      sent += 1;
    } else if (!res.reachable) {
      remaining.push(item);
      stopEarly = true;
    } else {
      // Keep item if server returned a transient error; drop malformed 400s only if user clears.
      remaining.push({ ...item, lastError: res.error || "error" });
    }
  }

  await setQueue(remaining);
  return { sent, remaining: remaining.length, error: null };
}
