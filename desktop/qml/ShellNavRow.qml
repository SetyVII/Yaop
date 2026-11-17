import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "."

// Slate sidebar row: icon (bare, or in a rounded square tinted with the type colour), label,
// count. The open page's row is raised (surface + hairline ring) rather than tinted.
Rectangle {
    id: root
    property string icon: "home"
    property color tint: "transparent"   // type colour: draws the icon in a tinted square
    property string emoji: ""
    property string label: ""
    property string count: ""
    property bool active: false
    signal clicked()

    Layout.fillWidth: true
    implicitHeight: 34
    radius: Theme.radius
    color: active ? Theme.panel2 : hover.hovered ? Qt.rgba(1, 1, 1, 0.04) : "transparent"
    border.width: active ? 1 : 0
    border.color: Theme.line2

    Rectangle {
        id: iconBox
        x: 10
        anchors.verticalCenter: parent.verticalCenter
        width: 22
        height: 22
        radius: 6
        color: root.tint.a > 0 && !root.emoji ? Qt.rgba(root.tint.r, root.tint.g, root.tint.b, 0.16) : "transparent"

        TintedIcon {
            visible: !root.emoji
            anchors.centerIn: parent
            name: root.icon
            size: root.tint.a > 0 ? 14 : 17
            color: root.tint.a > 0 ? root.tint : root.active ? Theme.text : Theme.dim
        }
        Text {
            visible: !!root.emoji
            anchors.centerIn: parent
            text: root.emoji
            font.pixelSize: 14
        }
    }

    Text {
        anchors.left: iconBox.right
        anchors.leftMargin: 10
        anchors.right: countText.visible ? countText.left : parent.right
        anchors.rightMargin: 10
        anchors.verticalCenter: parent.verticalCenter
        text: root.label
        color: root.active ? Theme.text : Theme.dim
        font.family: Theme.body
        font.pixelSize: 14
        font.weight: root.active ? Font.Medium : Font.Normal
        elide: Text.ElideRight
    }

    Text {
        id: countText
        anchors.right: parent.right
        anchors.rightMargin: 12
        anchors.verticalCenter: parent.verticalCenter
        text: root.count
        visible: text.length > 0
        color: Theme.muted
        font.family: Theme.body
        font.pixelSize: 12
    }

    HoverHandler { id: hover; cursorShape: Qt.PointingHandCursor }
    TapHandler { onTapped: root.clicked() }
}
