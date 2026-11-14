pragma Singleton
import QtQuick

QtObject {
    // ---- Visual palette: deep blue fading to ink, translucent white surfaces on top, colour reserved for object types.
    // Page / translucent surfaces
    readonly property color page: "#050A26"
    readonly property color bg: "#050A26"
    readonly property color bg2: Qt.rgba(0.02, 0.04, 0.15, 0.42)   // sidebar: ink over the gradient
    readonly property color panel: Qt.rgba(1, 1, 1, 0.06)           // surface
    readonly property color panel2: Qt.rgba(1, 1, 1, 0.14)         // surface-2 (inside a panel)
    readonly property color panel3: Qt.rgba(1, 1, 1, 0.20)         // hover / pressed
    readonly property color sheet: "#0B1A4A"                         // popups / sheets (no blur in Qt)
    readonly property color ink: "#03081E"                           // code boxes (at 55%)

    // Lines (1 px white at 16 %)
    readonly property color line: Qt.rgba(1, 1, 1, 0.16)
    readonly property color line2: Qt.rgba(1, 1, 1, 0.24)
    readonly property color line3: Qt.rgba(1, 1, 1, 0.34)

    // Type
    readonly property color text: "#FFFFFF"
    readonly property color dim: "#AEBFE4"      // spec "muted": secondary text
    readonly property color muted: "#8A9CC8"    // spec "faint": metadata, placeholders
    readonly property color faint: "#4F6498"    // below the spec: disabled / ghost glyphs

    // Accent: white buttons (ink text) and ice for mentions, links, active indicators
    readonly property color accent: "#FFFFFF"
    readonly property color accent2: "#E8F1FF"
    readonly property color accentInk: "#0B1F5C"
    readonly property color accentDim: Qt.rgba(1, 1, 1, 0.14)
    readonly property color ice: "#BFE3FF"
    readonly property color mentionPill: Qt.rgba(0.75, 0.89, 1, 0.12)     // @mention pill fill
    readonly property color mentionPillLine: Qt.rgba(0.75, 0.89, 1, 0.38) // @mention pill border
    readonly property color violet: "#8F7CFF"
    readonly property color ok: "#86E8A8"
    readonly property color late: "#FF8A7A"
    readonly property color danger: "#FF8A7A"
    readonly property color tagGreen: "#C9F2A6"   // #tags

    // Object type colours (one per base type; unlisted types use ice)
    readonly property color note: "#BFE3FF"
    readonly property color task: "#BFE3FF"
    readonly property color project: "#FFD166"
    readonly property color person: "#FFB38A"
    readonly property color journal: "#E8EEFF"
    readonly property color board: "#BFE3FF"
    readonly property color media: "#BFE3FF"
    readonly property color book: "#9B8CFF"
    readonly property color weblink: "#7AF0D0"
    readonly property color image: "#FF8FB3"
    readonly property color tag: "#DCEC4E"

    // Typography (bundled, OFL): Inter Tight for content, Plex Mono for the interface, Doto as
    // the single accent word of a screen title.
    readonly property string display: "Inter Tight"
    readonly property string body: "Inter Tight"
    readonly property string mono: "IBM Plex Mono"
    readonly property string pixel: "Doto"

    // Metrics
    readonly property int radiusSm: 8      // block colour backgrounds, date chips
    readonly property int radius: 14       // controls and fields
    readonly property int radiusLg: 22     // cards and sheets
    // Window width, fed by Main.qml. Everything adaptive derives from it, so a layout never has
    // to hardcode "this fits on a 1440 screen".
    property real winW: 1440
    readonly property bool compact: winW < 1000   // sidebar becomes an icon rail
    readonly property bool narrow: winW < 1280    // the editor rail becomes an on-demand overlay
    readonly property int pagePad: compact ? 16 : narrow ? 22 : 28
    readonly property int sidebarW: compact ? 60 : 220
    readonly property int topbarH: 56      // window title bar
    readonly property int paneBarH: 40     // pane header bar
    readonly property int panelHeaderH: 42
    readonly property int editorWidth: 640
    readonly property int railWidth: 264

    // Motion
    readonly property int fast: 140
    readonly property int normal: 200
    readonly property int slow: 280

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

    function typeLabel(t) {
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

    function typeIcon(t) {
        switch (t) {
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
        case "habit": return "circle-check"
        default: return "box"
        }
    }
}
