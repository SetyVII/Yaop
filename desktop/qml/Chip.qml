import QtQuick
import "."

// Filter chip: surface pill; active = ice border and text.
Rectangle {
    id: root
    property string text: "ALL"
    property bool active: false
    signal clicked()

    implicitWidth: label.implicitWidth + 28
    implicitHeight: 30
    radius: height / 2
    color: hover.hovered ? Theme.panel2 : Theme.panel
    border.width: 1
    border.color: active ? Theme.ice : "transparent"
    Behavior on color { ColorAnimation { duration: Theme.fast } }

    Text {
        id: label
        anchors.centerIn: parent
        text: root.text.toUpperCase()
        color: root.active ? Theme.ice : Theme.dim
        font.family: Theme.mono
        font.pixelSize: 11
        font.letterSpacing: 1
        font.weight: Font.Medium
    }

    HoverHandler { id: hover; cursorShape: Qt.PointingHandCursor }
    TapHandler { onTapped: root.clicked() }
}
