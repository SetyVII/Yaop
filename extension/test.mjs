import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import http from "node:http";
import { fileURLToPath } from "node:url";

import {
  DEFAULT_CONFIG,
  clampInt,
  clearQueue,
  enqueueCapture,
  getQueue,
  isCapturableUrl,
  loadConfig,
  removeQueueItem,
  sanitizeConfig,
  saveConfig,
} from "./src/config.js";
import {
  arrayBufferToBase64,
  computeScaledDimensions,
  formatBytes,
} from "./src/webp.js";
import {
  deliverOrQueue,
  endpointForKind,
  flushQueue,
  pingDesktop,
} from "./src/api.js";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

function installChromeMock() {
  const store = new Map();
  let badgeText = "";
  globalThis.chrome = {
    storage: {
      local: {
        async get(key) {
          if (typeof key === "string") {
            return store.has(key) ? { [key]: structuredClone(store.get(key)) } : {};
          }
          const out = {};
          for (const k of key) {
            if (store.has(k)) out[k] = structuredClone(store.get(k));
          }
          return out;
        },
        async set(obj) {
          for (const [k, v] of Object.entries(obj)) {
            store.set(k, structuredClone(v));
          }
        },
      },
    },
    action: {
      async setBadgeText({ text }) {
        badgeText = text;
      },
      async setBadgeBackgroundColor() {},
    },
  };
  return { getBadge: () => badgeText, clearStore: () => store.clear() };
}

test("manifests and locales are valid and complete", () => {
  const chromeManifest = JSON.parse(fs.readFileSync(path.join(__dirname, "manifest.json"), "utf8"));
  const ffManifest = JSON.parse(fs.readFileSync(path.join(__dirname, "manifest.firefox.json"), "utf8"));

  assert.equal(chromeManifest.manifest_version, 3);
  assert.equal(ffManifest.manifest_version, 3);
  assert.equal(chromeManifest.background.service_worker, "src/background.js");
  assert.deepEqual(ffManifest.background.scripts, ["src/background.js"]);
  assert.ok(ffManifest.browser_specific_settings.gecko.id);

  for (const iconPath of Object.values(chromeManifest.icons)) {
    assert.ok(fs.existsSync(path.join(__dirname, iconPath)), `missing icon ${iconPath}`);
  }

  const en = JSON.parse(fs.readFileSync(path.join(__dirname, "_locales/en/messages.json"), "utf8"));
  const es = JSON.parse(fs.readFileSync(path.join(__dirname, "_locales/es/messages.json"), "utf8"));
  assert.deepEqual(Object.keys(es).sort(), Object.keys(en).sort(), "en/es locale keys match");
});

test("config helpers validate ports, bounds, and capturable URLs", () => {
  assert.equal(clampInt("75", 40, 95, 70), 75);
  assert.equal(clampInt("999", 40, 95, 70), 95);
  assert.equal(clampInt("bad", 40, 95, 70), 70);

  const cfg = sanitizeConfig({ port: 80, token: "  abc  ", coverQuality: 200, autoClose: false });
  assert.equal(cfg.port, 1024);
  assert.equal(cfg.token, "abc");
  assert.equal(cfg.coverQuality, 95);
  assert.equal(cfg.autoClose, false);
  assert.equal(cfg.imageQuality, DEFAULT_CONFIG.imageQuality);

  assert.equal(isCapturableUrl("https://example.com/a"), true);
  assert.equal(isCapturableUrl("http://localhost:8080"), true);
  assert.equal(isCapturableUrl("chrome://extensions"), false);
  assert.equal(isCapturableUrl("about:blank"), false);
  assert.equal(isCapturableUrl("file:///etc/passwd"), false);
  assert.equal(isCapturableUrl(""), false);
});

test("webp helpers compute aspect-preserving dimensions and base64", () => {
  assert.deepEqual(computeScaledDimensions(1600, 800, 480), {
    width: 480,
    height: 240,
    resized: true,
  });
  assert.deepEqual(computeScaledDimensions(320, 200, 480), {
    width: 320,
    height: 200,
    resized: false,
  });
  assert.deepEqual(computeScaledDimensions(600, 1200, 480), {
    width: 240,
    height: 480,
    resized: true,
  });

  const buf = new Uint8Array([72, 101, 108, 108, 111]).buffer;
  assert.equal(arrayBufferToBase64(buf), "SGVsbG8=");
  assert.equal(formatBytes(512), "512 B");
  assert.equal(formatBytes(15360), "15 KB");
  assert.equal(formatBytes(2 * 1024 * 1024), "2.00 MB");
});

test("endpoint mapping and offline queue deliver when desktop comes online", async () => {
  assert.equal(endpointForKind("web"), "/v1/capture/weblink");
  assert.equal(endpointForKind("image"), "/v1/capture/image");
  assert.equal(endpointForKind("note"), "/v1/capture/note");

  const mock = installChromeMock();
  const token = "0123456789abcdef0123456789abcdef";

  // 1. Desktop offline -> deliverOrQueue stores in local queue and updates badge
  await saveConfig({ port: 59991, token });
  const qRes = await deliverOrQueue("web", { url: "https://example.org", title: "Offline link" });
  assert.equal(qRes.ok, true);
  assert.equal(qRes.queued, true);
  assert.equal((await getQueue()).length, 1);
  assert.equal(mock.getBadge(), "1");

  // 2. Start a mock local YAOP Desktop CaptureBridge on 127.0.0.1 ephemeral port
  const received = [];
  const server = http.createServer((req, res) => {
    if (req.headers.authorization !== `Bearer ${token}`) {
      res.writeHead(401, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: false, error: "unauthorized" }));
      return;
    }
    if (req.method === "GET" && req.url === "/v1/ping") {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: true, app: "yaop-desktop", version: "0.1.0", protocol: 1 }));
      return;
    }
    let body = "";
    req.on("data", (c) => {
      body += c;
    });
    req.on("end", () => {
      received.push({ url: req.url, body: JSON.parse(body) });
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: true, id: "wl-1", existing: false }));
    });
  });

  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address();

  try {
    await saveConfig({ port, token });
    const cfg = await loadConfig();
    const ping = await pingDesktop(cfg);
    assert.equal(ping.reachable, true);
    assert.equal(ping.authenticated, true);

    // Flush offline queue -> sends the queued weblink and clears badge
    const flushed = await flushQueue();
    assert.equal(flushed.sent, 1);
    assert.equal(flushed.remaining, 0);
    assert.equal(mock.getBadge(), "");
    assert.equal(received.length, 1);
    assert.equal(received[0].url, "/v1/capture/weblink");
    assert.equal(received[0].body.url, "https://example.org");

    // Direct delivery while online
    const direct = await deliverOrQueue("image", { image_base64: "AAAA", title: "Shot" });
    assert.equal(direct.ok, true);
    assert.equal(direct.queued, false);
    assert.equal(received.length, 2);
    assert.equal(received[1].url, "/v1/capture/image");

    // Queue item removal & clear helpers
    const item = await enqueueCapture("note", { text: "hi" }, "hi");
    assert.equal((await getQueue()).length, 1);
    await removeQueueItem(item.id);
    assert.equal((await getQueue()).length, 0);
    await enqueueCapture("note", { text: "a" }, "a");
    await clearQueue();
    assert.equal((await getQueue()).length, 0);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});
