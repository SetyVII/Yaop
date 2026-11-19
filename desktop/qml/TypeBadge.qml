import QtQuick
import "."

// An object's type at a glance: its icon in a rounded square tinted with the type colour (or the
// object's emoji). Used wherever a list or card names a type, instead of a bare colour dot.
Rectangle {
    id: badge
    property string typeId: ""
    property string emoji: ""
    property int size: 22

    // Built-in types have an icon and colour of their own; user types bring theirs (emoji icon,
    // palette name or hex colour) from the type itself.
    readonly property bool builtIn: Theme.typeIcon(typeId) !== "box"
    readonly property var info: !builtIn && typeof ws !== "undefined" && ws && typeId ? ws.typeInfo(typeId) : null
    readonly property string cleanEmoji: (typeId === "tag" && (emoji === "🏷" || emoji === "🏷️")) ? "" : emoji
    readonly property string shownEmoji: cleanEmoji || (info && info.icon && /[^\x00-\x7F]/.test(info.icon) ? info.icon : "")
    readonly property color tone: {
        const c = info && info.color ? String(info.color) : ""
        if (c.startsWith("#")) return c
        if (c && Theme.paletteHex[c] !== undefined) return Theme.paletteColor(c)
        return Theme.typeColor(typeId)
    }
    implicitWidth: size
    implicitHeight: size
    width: size
    height: size
    radius: Math.round(size * 0.28)
    color: shownEmoji ? "transparent" : Qt.rgba(tone.r, tone.g, tone.b, 0.16)

    TintedIcon {
        visible: !badge.shownEmoji
        anchors.centerIn: parent
        name: Theme.typeIcon(badge.typeId)
        size: Math.round(badge.size * 0.62)
        color: badge.tone
    }
    Text {
        visible: !!badge.shownEmoji
        anchors.centerIn: parent
        text: badge.shownEmoji
        font.pixelSize: Math.round(badge.size * 0.66)
    }
}
