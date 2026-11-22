pragma Singleton
import QtQuick
import QtCore

QtObject {
    // Three looks, chosen per device in Settings › Appearance (never synced):
    //  "noir":  Estética negra — pure #000000 canvas, 1px #1F1F1F hairlines, 0px sharp corners,
    //           single user-chosen accent (default #C8F04A), mono uppercase metadata (default).
    //  "slate": neutral dark gray, a rounded page panel beside a full-height sidebar.
    //  "sky":   deep blue fading to ink, translucent white surfaces, mono caps labels.
    readonly property Settings store: Settings {
        category: "appearance"
        property string scheme: "noir"
        property string accentHex: "#C8F04A"
        property bool reduceMotion: false
    }
    readonly property bool noir: store.scheme === "noir" || (store.scheme !== "slate" && store.scheme !== "sky")
    readonly property bool slate: store.scheme === "slate"
    readonly property bool modern: noir || slate   // uses ShellSidebar + ShellTopBar + tabs
    function setScheme(name) { store.scheme = name }
    function setAccent(hex) { store.accentHex = hex }

    readonly property var accentOptions: [
        { hex: "#C8F04A", label: qsTr("Lime") },
        { hex: "#7FD4FF", label: qsTr("Sky") },
        { hex: "#FF8A3D", label: qsTr("Orange") },
        { hex: "#F2F2F2", label: qsTr("White") }
    ]

    // Page / surfaces (docs/design/mockup/Colors.dc.html)
    readonly property color page: noir ? "#000000" : slate ? "#18191C" : "#050A26"
    readonly property color bg: page
    readonly property color bg2: noir ? "#000000" : slate ? "#18191C" : Qt.rgba(0.02, 0.04, 0.15, 0.42)
    readonly property color panel: noir ? "#050505" : slate ? "#202124" : Qt.rgba(1, 1, 1, 0.06)
    readonly property color panel2: noir ? "#0D0D0D" : slate ? "#28292D" : Qt.rgba(1, 1, 1, 0.14)
    readonly property color panel3: noir ? "#161616" : slate ? "#313237" : Qt.rgba(1, 1, 1, 0.20)
    readonly property color sheet: noir ? "#0A0A0A" : slate ? "#232427" : "#0B1A4A"
    readonly property color ink: noir ? "#050505" : slate ? "#141517" : "#03081E"
    readonly property color card: noir ? "#000000" : slate ? "#27282C" : panel
    readonly property color cardHover: noir ? "#090909" : slate ? "#2E2F34" : panel2
    readonly property color cardBorder: noir ? "#1F1F1F" : slate ? "#34353B" : "transparent"
    readonly property color cardShadow: slate ? Qt.rgba(0, 0, 0, 0.32) : "transparent"
    readonly property color scrim: noir ? Qt.rgba(0, 0, 0, 0.72) : slate ? Qt.rgba(0, 0, 0, 0.55) : Qt.rgba(0.02, 0.04, 0.15, 0.62)

    // Lines
    readonly property color line: noir ? "#1F1F1F" : slate ? "#2D2E33" : Qt.rgba(1, 1, 1, 0.16)
    readonly property color lineSubtle: noir ? "#1A1A1A" : line
    readonly property color line2: noir ? "#2A2A2A" : slate ? "#3A3B41" : Qt.rgba(1, 1, 1, 0.24)
    readonly property color line3: noir ? "#4A4A4A" : slate ? "#4A4B52" : Qt.rgba(1, 1, 1, 0.34)

    // Type
    readonly property color text: noir ? "#F2F2F2" : slate ? "#EDEDEF" : "#FFFFFF"
    readonly property color bodyText: noir ? "#E6E6E6" : text
    readonly property color dim: noir ? "#BDBDBD" : slate ? "#B5B6BC" : "#AEBFE4"
    readonly property color muted: noir ? "#8B8B8B" : slate ? "#8B8C94" : "#8A9CC8"
    readonly property color label: noir ? "#7A7A7A" : muted
    readonly property color faint: noir ? "#5A5A5A" : slate ? "#5E5F66" : "#4F6498"

    // Accent: single accent marks ONLY active state (selection, active nav/tab, cursor, focus)
    readonly property color accent: noir ? store.accentHex : slate ? "#EDEDEF" : "#FFFFFF"
    readonly property color accent2: noir ? Qt.lighter(accent, 1.08) : slate ? "#FFFFFF" : "#E8F1FF"
    readonly property color accentInk: noir ? "#000000" : slate ? "#18191C" : "#0B1F5C"
    readonly property color accentDim: noir ? Qt.rgba(accent.r, accent.g, accent.b, 0.18)
                                     : slate ? Qt.rgba(1, 1, 1, 0.10) : Qt.rgba(1, 1, 1, 0.14)
    readonly property color ice: noir ? accent : slate ? "#7EA6FF" : "#BFE3FF"
    readonly property color focusRing: noir ? "#F2F2F2" : ice
    readonly property color mentionPill: noir ? "#0A0A0A" : slate ? Qt.rgba(0.49, 0.65, 1, 0.12) : Qt.rgba(0.75, 0.89, 1, 0.12)
    readonly property color mentionPillLine: noir ? "#2E2E2E" : slate ? Qt.rgba(0.49, 0.65, 1, 0.36) : Qt.rgba(0.75, 0.89, 1, 0.38)
    readonly property color violet: noir ? "#C2B2FF" : slate ? "#A387FF" : "#8F7CFF"
    readonly property color ok: noir ? "#7FE0A6" : slate ? "#3DD68C" : "#86E8A8"
    readonly property color late: noir ? "#FF786E" : slate ? "#F58A7A" : "#FF8A7A"
    readonly property color danger: late
    readonly property color warm: noir ? "#FFB07A" : "#FF9F43"
    readonly property color tagGreen: noir ? "#DCEC4E" : "#C9F2A6"

    // Object type colours (docs/design/mockup/Icons.dc.html)
    readonly property color note: "#BFE3FF"
    readonly property color task: "#BFE3FF"
    readonly property color project: "#FFD166"
    readonly property color person: "#FFB38A"
    readonly property color journal: noir ? "#E8EEFF" : slate ? "#C9CAD1" : "#E8EEFF"
    readonly property color board: "#BFE3FF"
    readonly property color media: "#BFE3FF"
    readonly property color book: "#9B8CFF"
    readonly property color weblink: "#7AF0D0"
    readonly property color image: "#FF8FB3"
    readonly property color tag: "#DCEC4E"
    readonly property color habit: "#86E8A8"

    // Typography (bundled, OFL)
    readonly property string display: modern ? "Inter" : "Inter Tight"
    readonly property string body: modern ? "Inter" : "Inter Tight"
    readonly property string mono: (noir || !slate) ? "IBM Plex Mono" : "Inter"
    readonly property string code: "IBM Plex Mono"
    readonly property string pixel: modern ? "Inter" : "Doto"

    // Interface labels: uppercase mono in noir & sky; sentence case in slate.
    function caps(s) {
        s = String(s)
        if (!slate) return s.toUpperCase()
        return s.toLowerCase().replace(/[a-z]/, c => c.toUpperCase())
            .replace(/\bctrl[ +]([a-z0-9])\b/g, (m, k) => "Ctrl+" + k.toUpperCase())
            .replace(/\bctrl\b/g, "Ctrl").replace(/\bshift\b/g, "Shift").replace(/\bpin\b/g, "PIN")
            .replace(/\be2ee\b/g, "E2EE").replace(/\bpdf\b/g, "PDF").replace(/\burl\b/g, "URL")
            .replace(/\blatex\b/g, "LaTeX").replace(/\bqr\b/g, "QR").replace(/\bid\b/g, "ID")
    }
    function track(n) { return slate ? 0 : noir ? Math.min(n, 0.7) : n }

    // Metrics — 0px sharp square corners in noir!
    readonly property int radiusSm: noir ? 0 : slate ? 6 : 8
    readonly property int radius: noir ? 0 : slate ? 8 : 14
    readonly property int radiusLg: noir ? 0 : slate ? 12 : 22
    property real winW: 1440
    readonly property bool compact: winW < 1000
    readonly property bool narrow: winW < 1280
    readonly property int pagePad: compact ? 16 : narrow ? 24 : 32
    readonly property int sidebarW: compact ? 64 : noir ? 256 : slate ? 236 : 220
    readonly property int topbarH: 56
    readonly property int paneBarH: noir ? 48 : slate ? 48 : 40
    readonly property int panelHeaderH: 42
    readonly property int editorWidth: 680
    readonly property int railWidth: noir ? 340 : 264

    // Motion
    readonly property bool reduceMotion: store.reduceMotion
    function setReduceMotion(on) { store.reduceMotion = on }
    readonly property real motion: reduceMotion ? 0 : 1
    readonly property int fast: 140 * motion
    readonly property int normal: 200 * motion
    readonly property int slow: 280 * motion
    readonly property var easeStandard: [0.2, 0, 0, 1, 1, 1]
    readonly property var easeEnter: [0.05, 0.7, 0.1, 1, 1, 1]
    readonly property var easeExit: [0.3, 0, 0.8, 0.15, 1, 1]

    // Named block/inline palette (10 fixed names — Colors.dc.html)
    readonly property var paletteNames: ["yellow", "orange", "red", "pink", "purple", "blue", "ice", "teal", "green", "gray"]
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
        case "web": case "weblink": case "web_link": return weblink
        case "image": return image
        case "journal": case "daily": return journal
        case "tag": return tag
        case "project": return project
        case "habit": return habit
        default: return "#BFE3FF"
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
        case "web": case "weblink": return "WEB"
        case "pdf": return "PDF"
        case "audio": return "AUDIO"
        case "video": return "VIDEO"
        case "file": return "FILE"
        case "table": return "TABLE"
        case "image": return "IMAGE"
        case "tag": return "TAG"
        case "habit": return "HABIT"
        case "set": return "QUERY"
        default: return String(t).toUpperCase()
        }
    }

    function typeName(t) {
        const l = typeLabelCaps(t).toLowerCase()
        return l.charAt(0).toUpperCase() + l.slice(1)
    }

    function typeIcon(t) {
        switch (t) {
        case "tag": return "tag"
        case "set": return "filter"
        case "web": case "weblink": case "web_link": return "web"
        case "video": return "video"
        case "file": return "file"
        case "table": return "table"
        case "daily": case "journal": return "journal"
        case "note": return "note"
        case "task": return "task"
        case "project": return "project"
        case "person": return "person"
        case "whiteboard": return "whiteboard"
        case "collection": case "book": return "book"
        case "pdf": return "pdf"
        case "audio": return "audio"
        case "image": return "image"
        case "habit": return "habit"
        default: return "shape"
        }
    }
}
