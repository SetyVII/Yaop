import QtQuick
import "."

// Filter chip: surface pill; active = ice border and text.
Rectangle {
    id: root
    property string text: "ALL"
    property bool active: false
    property string typeId: ""   // set: the chip leads with that type's badge
    property string icon: ""     // else an optional plain icon
    signal clicked()

    implicitWidth: row.implicitWidth + (Theme.slate ? 22 : 28)
    implicitHeight: Theme.slate ? 32 : 30
    radius: Theme.slate ? Theme.radius : height / 2
    color: active && Theme.slate ? Theme.panel2 : hover.hovered ? Theme.cardHover : Theme.slate ? Theme.card : Theme.panel
    border.width: 1
    border.color: active ? (Theme.slate ? Theme.line3 : Theme.ice) : Theme.slate ? Theme.cardBorder : "transparent"
    Behavior on color { ColorAnimation { duration: Theme.fast } }

    Row {
        id: row
        anchors.centerIn: parent
        spacing: 7
        TypeBadge {
            visible: !!root.typeId
            anchors.verticalCenter: parent.verticalCenter
            typeId: root.typeId
            size: 20
        }
        TintedIcon {
            visible: !root.typeId && !!root.icon
            anchors.verticalCenter: parent.verticalCenter
            name: root.icon || "box"
            size: 15
            color: root.active ? Theme.text : Theme.dim
        }
        Text {
            id: label
            anchors.verticalCenter: parent.verticalCenter
            text: Theme.slate ? root.text : root.text.toUpperCase()
            color: root.active ? (Theme.slate ? Theme.text : Theme.ice) : Theme.dim
            font.family: Theme.slate ? Theme.body : Theme.mono
            font.pixelSize: Theme.slate ? 13 : 11
            font.letterSpacing: Theme.slate ? 0 : 1
            font.weight: Font.Medium
        }
    }

    HoverHandler { id: hover; cursorShape: Qt.PointingHandCursor }
    TapHandler { onTapped: root.clicked() }
}
