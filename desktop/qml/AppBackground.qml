import QtQuick
import "."

// Window background (spec §2.1): the sky comes in from the top-right corner and fades to ink.
// Painted ONCE for the whole window (not per card). A few "@" / "#" glyphs sit in the empty
// lower part of the sidebar column, outside any scrolling area (spec §4).
Item {
    id: root
    // Decorative glyphs only on EMPTY backgrounds (onboarding, empty states), never over content.
    property bool glyphs: false

    Rectangle { anchors.fill: parent; color: Theme.page }

    Canvas {
        id: sky
        anchors.fill: parent
        visible: !Theme.modern
        renderTarget: Canvas.Image
        onPaint: {
            const ctx = getContext("2d")
            ctx.reset()
            ctx.fillStyle = "#050A26"
            ctx.fillRect(0, 0, width, height)
            const g = ctx.createLinearGradient(0, 0, 0, height)
            g.addColorStop(0.0, "#0B1A4F")
            g.addColorStop(0.5, "#071240")
            g.addColorStop(1.0, "#050A26")
            ctx.fillStyle = g
            ctx.fillRect(0, 0, width, height)
        }
        onWidthChanged: requestPaint()
        onHeightChanged: requestPaint()
    }

    // decorative glyphs, static
    Repeater {
        model: [
            { g: "@", x: 0.012, y: 0.62, a: 0.40 }, { g: "#", x: 0.060, y: 0.71, a: 0.30 },
            { g: "@", x: 0.140, y: 0.80, a: 0.55 }, { g: "@", x: 0.030, y: 0.88, a: 0.25 },
            { g: "#", x: 0.110, y: 0.93, a: 0.45 }, { g: "@", x: 0.170, y: 0.67, a: 0.30 }
        ]
        delegate: Text {
            required property var modelData
            visible: root.glyphs
            x: modelData.x * root.width * 6
            y: modelData.y * root.height
            text: modelData.g
            color: Qt.rgba(1, 1, 1, modelData.a)
            font.family: Theme.mono
            font.pixelSize: 11
        }
    }
}
