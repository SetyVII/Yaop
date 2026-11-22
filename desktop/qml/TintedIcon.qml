import QtQuick
import "."

// Crisp SVG icon rendered via IconProvider (image://icon/<name>/<hex>).
// Works identically on GPU and offscreen software backends with 1.5px square strokes.
Item {
    id: root
    property string name: "search"
    property color color: Theme.text
    property int size: 16
    implicitWidth: size
    implicitHeight: size

    readonly property string hex: {
        const r = Math.round(color.r * 255).toString(16).padStart(2, "0")
        const g = Math.round(color.g * 255).toString(16).padStart(2, "0")
        const b = Math.round(color.b * 255).toString(16).padStart(2, "0")
        return r + g + b
    }

    Image {
        anchors.fill: parent
        source: root.name.length > 0 ? ("image://icon/" + root.name + "/" + root.hex) : ""
        sourceSize: Qt.size(Math.max(16, root.size * 2), Math.max(16, root.size * 2))
        fillMode: Image.PreserveAspectFit
        smooth: true
        mipmap: true
        opacity: root.color.a
    }
}
