import QtQuick
import "."

// Filter / tag chip: in noir, sharp 0px border 1px #2A2A2A; active = Theme.accent fill with #000 text.
Rectangle {
    id: root
    property string text: "ALL"
    property bool active: false
    property string typeId: ""
    property string icon: ""
    property color dotColor: "transparent"
    signal clicked()

    implicitWidth: row.implicitWidth + (Theme.noir ? 18 : Theme.slate ? 22 : 28)
    implicitHeight: Theme.noir ? 28 : Theme.slate ? 32 : 30
    radius: Theme.noir ? 0 : Theme.slate ? Theme.radius : height / 2
    color: active ? (Theme.noir ? Theme.accent : Theme.slate ? Theme.panel2 : Theme.panel)
         : hover.hovered ? Theme.cardHover
         : (Theme.noir ? "transparent" : Theme.slate ? Theme.card : Theme.panel)
    border.width: 1
    border.color: active ? (Theme.noir ? Theme.accent : Theme.slate ? Theme.line3 : Theme.ice)
                : hover.hovered ? Theme.line3
                : (Theme.noir ? Theme.line2 : Theme.slate ? Theme.cardBorder : "transparent")
    Behavior on color { ColorAnimation { duration: Theme.fast } }
    Behavior on border.color { ColorAnimation { duration: Theme.fast } }

    Row {
        id: row
        anchors.centerIn: parent
        spacing: 7
        Rectangle {
            visible: root.dotColor.a > 0 && !root.typeId
            anchors.verticalCenter: parent.verticalCenter
            width: 7; height: 7
            color: root.dotColor
            border.width: Theme.noir && root.active ? 1 : 0
            border.color: "#000000"
        }
        TypeBadge {
            visible: !!root.typeId
            anchors.verticalCenter: parent.verticalCenter
            typeId: root.typeId
            size: Theme.noir ? 16 : 20
            overrideColor: root.active && Theme.noir ? Theme.accentInk : "transparent"
        }
        TintedIcon {
            visible: !root.typeId && root.dotColor.a === 0 && !!root.icon
            anchors.verticalCenter: parent.verticalCenter
            name: root.icon || "shape"
            size: 14
            color: root.active ? (Theme.noir ? Theme.accentInk : Theme.text) : Theme.dim
        }
        Text {
            id: label
            anchors.verticalCenter: parent.verticalCenter
            text: Theme.modern ? root.text : root.text.toUpperCase()
            color: root.active ? (Theme.noir ? Theme.accentInk : Theme.slate ? Theme.text : Theme.ice) : Theme.dim
            font.family: Theme.modern ? Theme.body : Theme.mono
            font.pixelSize: Theme.modern ? 13 : 11
            font.letterSpacing: Theme.modern ? 0 : 1
            font.weight: root.active ? Font.Medium : Font.Normal
        }
    }

    HoverHandler { id: hover; cursorShape: Qt.PointingHandCursor }
    TapHandler { onTapped: root.clicked() }
}
