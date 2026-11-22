# YAOP Web Clipper (Browser Extension)

Captures web links, web highlights, quick notes, and WebP-compressed images directly into your
local **YAOP Desktop** vault over `127.0.0.1` (product §22.5). Nothing leaves your machine
unencrypted; YAOP Desktop seals and syncs everything through your vault.

## Features

- **Save to Web links**: extracts canonical URL, title, OpenGraph description, site name,
  category, and favicon from the live tab (works behind logins).
- **WebP image conversion**:
  - Web link covers are converted in the browser to **WebP (q75, max 480 px)** with live KB
    savings preview (and YAOP Desktop also converts any fallback download to WebP).
  - Right-click any image → **Save image to YAOP (WebP)** or use the popup's **Images** tab
    to convert any page image to WebP and save it as a canonical `Image` object (or set it as
    the Web link cover).
- **Web highlights**: select text on any page → right-click **Save highlight to YAOP Web link**
  (or open the popup). Reuses an existing Web link for that URL and appends a quote block.
- **Quick notes**: save selected text or thoughts as a `Note` object with optional source URL.
- **Live vault tags & duplicate detection**: suggests your real YAOP tags (never auto-tagging
  `#hashtags` from web descriptions, product §4.5) and detects if the URL is already saved.
- **Offline queue**: if YAOP Desktop is closed, captures are queued locally in the browser and
  delivered automatically once YAOP Desktop opens.
- **Shortcuts**: `Alt+Shift+Y` opens the popup; `Alt+Shift+S` quick-saves the current page.

## Setup

1. Open **YAOP Desktop** → **Settings** → **Browser Extension** → click **Copy Token**.
2. Install the extension in your browser:
   - **Chrome / Brave / Edge / Vivaldi**:
     1. Open `chrome://extensions` and enable **Developer mode**.
     2. Click **Load unpacked** and select this `extension/` directory.
   - **Firefox**:
     1. Run `./extension/build.sh` (creates `extension/dist/firefox/` and `.xpi`).
     2. Open `about:debugging#/runtime/this-firefox` → **Load Temporary Add-on…** → select
        `extension/dist/firefox/manifest.json`.
3. Open the extension **Settings**, paste your **Bearer token**, click **Test connection**, and
   **Save**.

## Tests

```bash
node --test extension/test.mjs
```
