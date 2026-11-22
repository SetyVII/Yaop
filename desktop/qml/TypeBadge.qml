import QtQuick
import "."

// Type badge: in Estética Negra (noir), renders the crisp 24x24 1.5px-stroke SVG icon
// from Icons.dc.html tinted in the type's color (or a 7px colored square when `showIcon: false`).
Item {
    id: badge
    property string typeId: ""
    property string emoji: ""
    property int size: 22
    property bool showIcon: true
    property color overrideColor: "transparent"

    readonly property bool builtIn: Theme.typeIcon(typeId) !== "shape"
    readonly property var info: !builtIn && typeof ws !== "undefined" && ws && typeId ? ws.typeInfo(typeId) : null
    readonly property string cleanEmoji: (typeId === "tag" && (emoji === "🏷" || emoji === "🏷️")) ? "" : emoji
    readonly property string shownEmoji: cleanEmoji || (info && info.icon && /[^\x00-\x7F]/.test(info.icon) ? info.icon : "")
    readonly property string iconName: (info && info.icon && /^[a-z0-9-]+$/i.test(info.icon)) ? info.icon : Theme.typeIcon(typeId)
    readonly property color tone: {
        if (overrideColor.a > 0) return overrideColor
        if (!builtIn && info && info.color) {
            const c = String(info.color)
            if (c.startsWith("#")) return c
            if (Theme.paletteHex[c] !== undefined) return Theme.paletteColor(c)
        }
        return Theme.typeColor(typeId)
    }

    implicitWidth: Theme.noir && !showIcon && !shownEmoji ? Math.max(8, Math.round(size * 0.45)) : size
    implicitHeight: size
    width: implicitWidth
    height: size

    // Noir dot mode (when caller explicitly asks for showIcon: false)
    Rectangle {
        visible: Theme.noir && !badge.showIcon && !badge.shownEmoji
        anchors.centerIn: parent
        width: Math.max(7, Math.min(9, Math.round(badge.size * 0.42)))
        height: width
        radius: badge.typeId === "person" ? width / 2 : 0
        color: badge.tone
    }

    // Noir SVG icon mode (default — Icons.dc.html 24x24 stroke 1.5 square caps)
    TintedIcon {
        visible: Theme.noir && badge.showIcon && !badge.shownEmoji
        anchors.centerIn: parent
        name: badge.iconName
        size: Math.max(13, Math.round(badge.size * 0.88))
        color: badge.tone
    }

    // Slate / Sky mode: tinted rounded square with icon
    Rectangle {
        visible: !Theme.noir && !badge.shownEmoji
        anchors.fill: parent
        radius: Math.round(badge.size * 0.28)
        color: Qt.rgba(badge.tone.r, badge.tone.g, badge.tone.b, 0.16)
        TintedIcon {
            anchors.centerIn: parent
            name: badge.iconName
            size: Math.round(badge.size * 0.62)
            color: badge.tone
        }
    }

    Text {
        visible: !!badge.shownEmoji
        anchors.centerIn: parent
        text: badge.shownEmoji
        font.pixelSize: Math.round(badge.size * 0.66)
    }
}
