import QtQuick
import "."

// Round icon button: transparent, surface-2 on hover/active.
Rectangle {
    id: root
    property string icon: "search"
    property bool active: false
    readonly property alias hovered: hover.hovered
    signal clicked()

    implicitWidth: 34
    implicitHeight: 34
    radius: Theme.slate ? Theme.radius : width / 2
    color: press.pressed ? Theme.panel3 : (active || hover.hovered) ? Theme.panel2 : "transparent"
    scale: press.pressed ? 0.94 : 1

    Behavior on color { ColorAnimation { duration: Theme.fast } }
    Behavior on scale { NumberAnimation { duration: 90 * Theme.motion } }

    TintedIcon {
        anchors.centerIn: parent
        name: root.icon
        size: 17
        color: root.active ? Theme.ice : (hover.hovered ? Theme.text : Theme.dim)
    }

    HoverHandler { id: hover; cursorShape: Qt.PointingHandCursor }
    TapHandler { id: press; onTapped: root.clicked() }
}
