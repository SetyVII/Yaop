import QtQuick
import QtQuick.Shapes
import "."

// Rounded frame with a dashed 1 px line (embed blocks, spec §6).
Shape {
    id: root
    property real radius: Theme.radius
    property color lineColor: Theme.line2
    property color fillColor: "transparent"
    preferredRendererType: Shape.CurveRenderer

    ShapePath {
        strokeColor: root.lineColor
        strokeWidth: 1
        fillColor: root.fillColor
        strokeStyle: ShapePath.DashLine
        dashPattern: [4, 3]
        PathRectangle {
            x: 0.5; y: 0.5
            width: root.width - 1
            height: root.height - 1
            radius: root.radius
        }
    }
}
