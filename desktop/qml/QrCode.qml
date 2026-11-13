import QtQuick
import "."

// Renders a QR from PairingFlow.qrModules (rows of "0"/"1"), black on white with a quiet zone.
Rectangle {
    id: root
    property var modules: []
    property int cell: 6
    readonly property int n: modules.length
    readonly property int quiet: 4

    color: "white"
    implicitWidth: (n + 2 * quiet) * cell
    implicitHeight: implicitWidth

    Canvas {
        anchors.fill: parent
        onPaint: {
            var ctx = getContext("2d")
            ctx.fillStyle = "white"
            ctx.fillRect(0, 0, width, height)
            ctx.fillStyle = "black"
            for (var y = 0; y < root.n; ++y) {
                var row = root.modules[y]
                for (var x = 0; x < root.n; ++x)
                    if (row.charAt(x) === "1")
                        ctx.fillRect((x + root.quiet) * root.cell, (y + root.quiet) * root.cell, root.cell, root.cell)
            }
        }
        Connections { target: root; function onModulesChanged() { requestPaint() } }
        Component.onCompleted: requestPaint()
    }
}
