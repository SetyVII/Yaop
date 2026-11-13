import QtQuick
import QtQuick.Layouts
import QtQuick.Controls
import "."

// Sidebar row: icon + label (+ count). Active = surface-2 pill with an ice icon.
Rectangle {
    id: root
    property string icon: "home"
    property string label: "Home"
    property string count: ""
    property bool active: false
    property bool compact: false   // icon-only (narrow window)
    property color dot: "transparent"   // type colour dot instead of an icon
    signal clicked()

    // The sidebar column does not stretch its children by itself: without this the item
    // collapses to its content and the icon and label end up on top of each other.
    Layout.fillWidth: true
    implicitWidth: 200
    height: 40
    radius: Theme.radius
    color: root.active ? Theme.panel2 : (press.pressed ? Theme.panel2 : hover.hovered ? Theme.panel : "transparent")

    Behavior on color { ColorAnimation { duration: Theme.fast } }

    Item {
        id: iconBox
        x: root.compact ? Math.round((root.width - width) / 2) : 14
        anchors.verticalCenter: parent.verticalCenter
        width: 18
        height: 18

        TintedIcon {
            visible: root.dot.a === 0
            anchors.fill: parent
            name: root.icon
            size: 18
            color: root.active ? Theme.ice : Theme.dim
        }
        Rectangle {
            visible: root.dot.a > 0
            anchors.centerIn: parent
            width: 8
            height: 8
            radius: 4
            color: root.dot
        }
    }

    Text {
        visible: !root.compact
        anchors.left: iconBox.right
        anchors.leftMargin: 12
        anchors.right: countText.visible ? countText.left : parent.right
        anchors.rightMargin: 12
        anchors.verticalCenter: parent.verticalCenter
        text: root.label
        color: root.active ? Theme.text : Theme.dim
        font.family: Theme.body
        font.pixelSize: 14
        font.weight: root.active ? Font.Medium : Font.Normal
        elide: Text.ElideRight
        Behavior on color { ColorAnimation { duration: Theme.fast } }
    }

    Text {
        id: countText
        anchors.right: parent.right
        anchors.rightMargin: 14
        anchors.verticalCenter: parent.verticalCenter
        text: root.count
        visible: text.length > 0 && !root.compact
        color: Theme.muted
        font.family: Theme.mono
        font.pixelSize: 11
    }

    ToolTip.visible: root.compact && hover.hovered
    ToolTip.text: root.label
    ToolTip.delay: 400
    HoverHandler { id: hover; cursorShape: Qt.PointingHandCursor }
    TapHandler { id: press; onTapped: root.clicked() }
}
