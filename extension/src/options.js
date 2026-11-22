import { flushQueue, pingDesktop } from "./api.js";
import { loadConfig, sanitizeConfig, saveConfig } from "./config.js";
import { t, translateDom } from "./i18n.js";

const $ = (id) => document.getElementById(id);

function readForm() {
  return sanitizeConfig({
    port: $("opt-port").value,
    token: $("opt-token").value,
    coverQuality: $("opt-cover-q").value,
    imageQuality: $("opt-img-q").value,
    imageMaxWidth: $("opt-img-max").value,
    autoClose: $("opt-autoclose").checked,
    showNotifications: $("opt-notifs").checked,
  });
}

function updateEndpointPreview() {
  const port = $("opt-port").value || "47192";
  $("opt-endpoint").value = `http://127.0.0.1:${port}`;
}

async function init() {
  translateDom();
  const cfg = await loadConfig();
  $("opt-port").value = cfg.port;
  $("opt-token").value = cfg.token;
  $("opt-cover-q").value = cfg.coverQuality;
  $("opt-img-q").value = cfg.imageQuality;
  $("opt-img-max").value = cfg.imageMaxWidth;
  $("opt-autoclose").checked = cfg.autoClose;
  $("opt-notifs").checked = cfg.showNotifications;
  updateEndpointPreview();

  $("opt-port").addEventListener("input", updateEndpointPreview);

  $("btn-test").addEventListener("click", async () => {
    const resEl = $("test-result");
    const candidate = readForm();
    resEl.className = "status-msg";
    resEl.textContent = "...";
    if (!candidate.token) {
      resEl.className = "status-msg err";
      resEl.textContent = t("statusNoToken", "Configure token");
      return;
    }
    const ping = await pingDesktop(candidate);
    if (ping.reachable && ping.authenticated) {
      resEl.className = "status-msg ok";
      const ver = ping.data && ping.data.version ? ` v${ping.data.version}` : "";
      resEl.textContent = `${t("optConnected", "Connected to YAOP Desktop")}${ver}`;
    } else if (ping.reachable && !ping.authenticated) {
      resEl.className = "status-msg err";
      resEl.textContent = t("statusAuthError", "Invalid token");
    } else {
      resEl.className = "status-msg err";
      resEl.textContent = t("optUnreachable", "Cannot reach YAOP Desktop on 127.0.0.1");
    }
  });

  $("settings-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    const saved = await saveConfig(readForm());
    $("opt-port").value = saved.port;
    $("opt-cover-q").value = saved.coverQuality;
    $("opt-img-q").value = saved.imageQuality;
    $("opt-img-max").value = saved.imageMaxWidth;
    updateEndpointPreview();

    const msg = $("save-msg");
    msg.className = "status-msg ok";
    msg.textContent = t("optSaved", "Settings saved.");
    // Try flushing any pending offline captures right after saving credentials
    flushQueue();
  });
}

init();
