import QtQuick
import "."

Item {
    id: root
    property int cellSize: 3
    property int gap: 1
    property color color: Theme.text
    property color accent: Theme.accent

    readonly property var pattern: [
        1, 1, 1, 1, 1,
        1, 0, 0, 0, 0,
        1, 1, 1, 1, 0,
        1, 0, 0, 0, 0,
        1, 0, 0, 0, 1
    ]

    implicitWidth: 5 * cellSize + 4 * gap
    implicitHeight: 5 * cellSize + 4 * gap

    Repeater {
        model: 25
        Rectangle {
            width: root.cellSize
            height: root.cellSize
            x: (index % 5) * (root.cellSize + root.gap)
            y: Math.floor(index / 5) * (root.cellSize + root.gap)
            color: root.pattern[index] ? (index === 24 ? root.accent : root.color) : "transparent"
        }
    }
}
