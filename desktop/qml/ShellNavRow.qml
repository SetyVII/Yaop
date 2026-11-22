import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "."

// Sidebar navigation row: in noir (AppDesktop.dc.html), full-row Theme.accent fill + #000 text
// when active, subtle 5% white hover, 7px square indicator for pinned/type items, mono right badge.
Rectangle {
    id: root
    property string icon: "home"
    property color tint: "transparent"
    property bool roundDot: false
    property string emoji: ""
    property string label: ""
    property string count: ""
    property string shortcutHint: ""
    property bool active: false
    signal clicked()

    Layout.fillWidth: true
    implicitHeight: Theme.noir ? 36 : 34
    radius: Theme.radius
    color: active ? (Theme.noir ? Theme.accent : Theme.panel2)
         : hover.hovered ? Qt.rgba(1, 1, 1, 0.05) : "transparent"
    border.width: (!Theme.noir && active) ? 1 : 0
    border.color: Theme.line2

    Behavior on color { ColorAnimation { duration: Theme.fast } }

    Item {
        id: iconBox
        x: Theme.noir ? 12 : 10
        anchors.verticalCenter: parent.verticalCenter
        width: Theme.noir ? 18 : 22
        height: 22

        // Slate/Sky: rounded tinted box
        Rectangle {
            visible: !Theme.noir && root.tint.a > 0 && !root.emoji
            anchors.fill: parent
            radius: 6
            color: Qt.rgba(root.tint.r, root.tint.g, root.tint.b, 0.16)
        }

        // Crisp 24x24 SVG icon (Icons.dc.html) for both main nav and object types
        TintedIcon {
            visible: !root.emoji
            anchors.centerIn: parent
            name: root.icon
            size: Theme.noir ? 16 : (root.tint.a > 0 ? 14 : 16)
            color: root.active ? (Theme.noir ? Theme.accentInk : Theme.text)
                 : root.tint.a > 0 ? root.tint
                 : hover.hovered ? Theme.text : Theme.muted
        }
        Text {
            visible: !!root.emoji
            anchors.centerIn: parent
            text: root.emoji
            font.pixelSize: 13
        }
    }

    Text {
        anchors.left: iconBox.right
        anchors.leftMargin: 10
        anchors.right: tailText.visible ? tailText.left : parent.right
        anchors.rightMargin: 10
        anchors.verticalCenter: parent.verticalCenter
        text: root.label
        color: root.active ? (Theme.noir ? Theme.accentInk : Theme.text)
             : hover.hovered ? Theme.text : Theme.dim
        font.family: Theme.body
        font.pixelSize: 14
        font.weight: root.active ? Font.Medium : Font.Normal
        elide: Text.ElideRight
    }

    Text {
        id: tailText
        anchors.right: parent.right
        anchors.rightMargin: 12
        anchors.verticalCenter: parent.verticalCenter
        text: root.count.length > 0 ? root.count : root.shortcutHint
        visible: text.length > 0
        color: root.active ? (Theme.noir ? Theme.accentInk : Theme.muted) : Theme.label
        opacity: root.count.length > 0 ? 1.0 : 0.65
        font.family: Theme.mono
        font.pixelSize: 11
        property int last: Number(root.count) || 0
        onTextChanged: {
            const n = Number(root.count) || 0
            if (n > last && Theme.motion > 0) pop.restart()
            last = n
        }
        SequentialAnimation {
            id: pop
            PauseAnimation { duration: Theme.normal }
            NumberAnimation { target: tailText; property: "scale"; to: 1.3; duration: 100 * Theme.motion; easing.type: Easing.OutCubic }
            NumberAnimation { target: tailText; property: "scale"; to: 1; duration: Theme.fast; easing.type: Easing.OutBack }
        }
    }

    HoverHandler { id: hover; cursorShape: Qt.PointingHandCursor }
    TapHandler { onTapped: root.clicked() }
}
