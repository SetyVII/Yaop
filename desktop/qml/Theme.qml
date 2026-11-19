pragma Singleton
import QtQuick
import QtCore

QtObject {
    // Two looks, chosen per device in Settings › Appearance (never synced):
    //  "slate": neutral dark gray, a rounded page panel beside a full-height sidebar (default).
    //  "sky":   deep blue fading to ink, translucent white surfaces, mono caps labels.
    readonly property Settings store: Settings {
        category: "appearance"
        property string scheme: "slate"
        property bool reduceMotion: false
    }
    readonly property bool slate: store.scheme !== "sky"
    function setScheme(name) { store.scheme = name }

    // Page / surfaces
    readonly property color page: slate ? "#18191C" : "#050A26"
    readonly property color bg: page
    readonly property color bg2: slate ? "#18191C" : Qt.rgba(0.02, 0.04, 0.15, 0.42)   // sidebar
    readonly property color panel: slate ? "#202124" : Qt.rgba(1, 1, 1, 0.06)          // surface
    readonly property color panel2: slate ? "#28292D" : Qt.rgba(1, 1, 1, 0.14)         // raised / inside a panel
    readonly property color panel3: slate ? "#313237" : Qt.rgba(1, 1, 1, 0.20)         // hover / pressed
    readonly property color sheet: slate ? "#232427" : "#0B1A4A"                        // popups / sheets
    readonly property color ink: slate ? "#141517" : "#03081E"                          // code boxes
    // Cards on the page: slate lifts them one step above the page panel and outlines them, so a
    // card never melts into the gray around it.
    readonly property color card: slate ? "#27282C" : panel
    readonly property color cardHover: slate ? "#2E2F34" : panel2
    readonly property color cardBorder: slate ? "#34353B" : "transparent"
    readonly property color cardShadow: slate ? Qt.rgba(0, 0, 0, 0.32) : "transparent"
    // Behind modal sheets: neutral in slate, ink-blue in sky.
    readonly property color scrim: slate ? Qt.rgba(0, 0, 0, 0.55) : Qt.rgba(0.02, 0.04, 0.15, 0.62)

    // Lines
    readonly property color line: slate ? "#2D2E33" : Qt.rgba(1, 1, 1, 0.16)
    readonly property color line2: slate ? "#3A3B41" : Qt.rgba(1, 1, 1, 0.24)
    readonly property color line3: slate ? "#4A4B52" : Qt.rgba(1, 1, 1, 0.34)

    // Type
    readonly property color text: slate ? "#EDEDEF" : "#FFFFFF"
    readonly property color dim: slate ? "#B5B6BC" : "#AEBFE4"      // secondary text
    readonly property color muted: slate ? "#8B8C94" : "#8A9CC8"    // metadata, placeholders
    readonly property color faint: slate ? "#5E5F66" : "#4F6498"    // disabled / ghost glyphs

    // Accent: light buttons (ink text) and ice for mentions, links, active indicators
    readonly property color accent: slate ? "#EDEDEF" : "#FFFFFF"
    readonly property color accent2: slate ? "#FFFFFF" : "#E8F1FF"
    readonly property color accentInk: slate ? "#18191C" : "#0B1F5C"
    readonly property color accentDim: slate ? Qt.rgba(1, 1, 1, 0.10) : Qt.rgba(1, 1, 1, 0.14)
    readonly property color ice: slate ? "#7EA6FF" : "#BFE3FF"
    readonly property color focusRing: ice   // keyboard focus (FocusRing.qml)
    readonly property color mentionPill: slate ? Qt.rgba(0.49, 0.65, 1, 0.12) : Qt.rgba(0.75, 0.89, 1, 0.12)
    readonly property color mentionPillLine: slate ? Qt.rgba(0.49, 0.65, 1, 0.36) : Qt.rgba(0.75, 0.89, 1, 0.38)
    readonly property color violet: slate ? "#A387FF" : "#8F7CFF"
    readonly property color ok: slate ? "#3DD68C" : "#86E8A8"
    readonly property color late: slate ? "#F58A7A" : "#FF8A7A"
    readonly property color danger: late
    readonly property color warm: "#FF9F43"       // streak flame, "references" marks
    readonly property color tagGreen: "#C9F2A6"   // #tags

    // Object type colours (one per base type; unlisted types use ice)
    readonly property color note: ice
    readonly property color task: ice
    readonly property color project: "#FFD166"
    readonly property color person: "#FFB38A"
    readonly property color journal: slate ? "#C9CAD1" : "#E8EEFF"
    readonly property color board: ice
    readonly property color media: ice
    readonly property color book: "#9B8CFF"
    readonly property color weblink: "#7AF0D0"
    readonly property color image: "#FF8FB3"
    readonly property color tag: "#DCEC4E"

    // Typography (bundled, OFL). Sky: Inter Tight for content, Plex Mono for the interface, Doto
    // as the single accent word of a screen title. Slate: Inter everywhere but code.
    readonly property string display: slate ? "Inter" : "Inter Tight"
    readonly property string body: slate ? "Inter" : "Inter Tight"
    // `mono` is the interface-label face (Plex Mono in sky, Inter in slate); `code` is for code,
    // formulas and codes to compare, monospace in both.
    readonly property string mono: slate ? "Inter" : "IBM Plex Mono"
    readonly property string code: "IBM Plex Mono"
    readonly property string pixel: slate ? "Inter" : "Doto"

    // Interface labels are written in caps for sky; slate shows them in sentence case, untracked.
    function caps(s) {
        s = String(s)
        if (!slate) return s
        return s.toLowerCase().replace(/[a-z]/, c => c.toUpperCase())
            .replace(/\bctrl[ +]([a-z0-9])\b/g, (m, k) => "Ctrl+" + k.toUpperCase())
            .replace(/\bctrl\b/g, "Ctrl").replace(/\bshift\b/g, "Shift").replace(/\bpin\b/g, "PIN")
            .replace(/\be2ee\b/g, "E2EE").replace(/\bpdf\b/g, "PDF").replace(/\burl\b/g, "URL")
            .replace(/\blatex\b/g, "LaTeX").replace(/\bqr\b/g, "QR").replace(/\bid\b/g, "ID")
    }
    function track(n) { return slate ? 0 : n }

    // Metrics
    readonly property int radiusSm: slate ? 6 : 8      // block colour backgrounds, date chips
    readonly property int radius: slate ? 8 : 14       // controls and fields
    readonly property int radiusLg: slate ? 12 : 22    // cards and sheets
    // Window width, fed by Main.qml. Everything adaptive derives from it, so a layout never has
    // to hardcode "this fits on a 1440 screen".
    property real winW: 1440
    readonly property bool compact: winW < 1000   // sidebar becomes an icon rail
    readonly property bool narrow: winW < 1280    // the editor rail becomes an on-demand overlay
    readonly property int pagePad: compact ? 16 : narrow ? 22 : 28
    readonly property int sidebarW: compact ? 60 : slate ? 236 : 220
    readonly property int topbarH: 56      // window title bar
    readonly property int paneBarH: slate ? 48 : 40     // pane header bar
    readonly property int panelHeaderH: 42
    readonly property int editorWidth: 640
    readonly property int railWidth: 264

    // Motion. Every duration goes through `motion`, so "Reduce motion" (Settings › Appearance,
    // per device) makes the whole app instant. Only transform, opacity and colour animate;
    // opening a page, switching tabs or scrolling never starts an animation (product §2:
    // correctness > performance > motion). Never animate a width, height, margin or anchor of
    // something with a page inside: that re-lays the page out on every frame. Give the page its
    // final size at once and slide the moving part with a Translate. No opacity over a whole page
    // either (measured: frames twice as slow). Use with `easing.type: Easing.Bezier;
    // easing.bezierCurve: Theme.ease*`.
    readonly property bool reduceMotion: store.reduceMotion
    function setReduceMotion(on) { store.reduceMotion = on }
    readonly property real motion: reduceMotion ? 0 : 1
    readonly property int fast: 140 * motion     // colour, hover, focus, check
    readonly property int normal: 200 * motion   // day, toggle, tab, moving a block, leaving
    readonly property int slow: 280 * motion     // sheets and panes coming in
    readonly property var easeStandard: [0.2, 0, 0, 1, 1, 1]
    readonly property var easeEnter: [0.05, 0.7, 0.1, 1, 1, 1]
    readonly property var easeExit: [0.3, 0, 0.8, 0.15, 1, 1]

    // Named block/inline palette (pkm::Palette kAllNames)
    readonly property var paletteNames: ["yellow", "orange", "red", "pink", "purple", "blue", "ice", "teal", "green", "gray"]
    // The 10 semantic names keep their meaning; the visual design defines their values (spec §2.4).
    readonly property var paletteHex: {
        "yellow": "#FFD666",
        "orange": "#FFB07A",
        "red": "#FF786E",
        "pink": "#FF8FB3",
        "purple": "#C2B2FF",
        "blue": "#6EAAFF",
        "ice": "#BFE3FF",
        "teal": "#7AF0D0",
        "green": "#7FE0A6",
        "gray": "#FFFFFF"
    }
    // Block background: the colour at 12 % over the blue (gray 8 %). ARGB.
    readonly property var paletteTint: {
        "yellow": "#1FFFD666",
        "orange": "#1FFFB07A",
        "red": "#1FFF786E",
        "pink": "#1FFF8FB3",
        "purple": "#1FC2B2FF",
        "blue": "#1F6EAAFF",
        "ice": "#1FBFE3FF",
        "teal": "#1F7AF0D0",
        "green": "#1F7FE0A6",
        "gray": "#14FFFFFF"
    }
    // Text highlight: the colour at 25-28 %.
    readonly property var paletteHighlight: {
        "yellow": "#47FFD666",
        "orange": "#40FFB07A",
        "red": "#40FF786E",
        "pink": "#40FF8FB3",
        "purple": "#40C2B2FF",
        "blue": "#476EAAFF",
        "ice": "#40BFE3FF",
        "teal": "#407AF0D0",
        "green": "#407FE0A6",
        "gray": "#26FFFFFF"
    }

    function paletteColor(name) {
        return paletteHex[name] !== undefined ? paletteHex[name] : "#FFFFFF"
    }
    function paletteSurface(name) {
        return paletteTint[name] !== undefined ? paletteTint[name] : "#14FFFFFF"
    }

    function typeColor(t) {
        switch (t) {
        case "note": return note
        case "book": return book
        case "person": return person
        case "web": case "weblink": return weblink
        case "image": return image
        case "journal": return journal
        case "tag": return tag
        case "project": return project
        case "habit": return ok
        default: return ice   // Video, Audio, PDF, File, Task, Whiteboard, Table, user types
        }
    }

    function typeLabel(t) { return caps(typeLabelCaps(t)) }
    function typeLabelCaps(t) {
        switch (t) {
        case "note": return "NOTE"
        case "task": return "TASK"
        case "project": return "PROJECT"
        case "person": return "PERSON"
        case "journal": return "JOURNAL"
        case "whiteboard": return "BOARD"
        case "collection": return "COLLECTION"
        case "book": return "BOOK"
        case "web": return "WEB"
        case "pdf": return "PDF"
        case "audio": return "AUDIO"
        case "image": return "IMAGE"
        default: return String(t).toUpperCase()
        }
    }

    // "Journal", "Web link"…: the type label as words, for slate.
    function typeName(t) {
        const l = typeLabelCaps(t).toLowerCase()
        return l.charAt(0).toUpperCase() + l.slice(1)
    }

    function typeIcon(t) {
        switch (t) {
        case "tag": return "tag"
        case "set": return "filter"
        case "web": case "weblink": case "web_link": return "world"
        case "video": return "video"
        case "file": return "file"
        case "daily": return "calendar"
        case "note": return "file-text"
        case "task": return "circle-check"
        case "project": return "folder"
        case "person": return "user"
        case "journal": return "calendar"
        case "whiteboard": return "layout-board-split"
        case "collection": return "book"
        case "book": return "book"
        case "web": return "external-link"
        case "pdf": return "file-type-pdf"
        case "audio": return "music"
        case "image": return "photo"
        case "habit": return "sun"
        default: return "box"
        }
    }
}
