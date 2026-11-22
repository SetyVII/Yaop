import QtQuick
import "."

// Button: in noir, sharp 0px corners, 1px #2A2A2A border; primary = Theme.accent fill with #000 text.
Rectangle {
    id: root
    property string text: "BUTTON"
    property bool primary: false
    property bool small: false
    property bool danger: false
    signal clicked()

    implicitWidth: label.implicitWidth + (small ? 22 : 28)
    implicitHeight: small ? 30 : 36
    radius: Theme.noir ? 0 : Theme.slate ? Theme.radius : small ? 11 : Theme.radius
    opacity: enabled ? 1 : 0.4
    color: primary
           ? (press.pressed ? Theme.accent2 : hover.hovered ? Theme.accent2 : Theme.accent)
           : (press.pressed ? Theme.panel3 : hover.hovered ? Theme.panel2 : "transparent")
    border.width: primary ? 0 : 1
    border.color: danger ? Theme.danger : (hover.hovered ? Theme.line3 : Theme.line2)
    scale: (!Theme.noir && press.pressed) ? 0.98 : 1

    Behavior on color { ColorAnimation { duration: Theme.fast } }
    Behavior on border.color { ColorAnimation { duration: Theme.fast } }

    property bool focusRing: true

    Text {
        id: label
        anchors.centerIn: parent
        text: Theme.noir ? root.text : (Theme.slate ? Theme.caps(root.text) : root.text.toUpperCase())
        color: root.primary ? Theme.accentInk : (root.danger ? Theme.danger : Theme.text)
        font.family: Theme.noir ? Theme.body : Theme.mono
        font.pixelSize: Theme.noir ? (root.small ? 12 : 13) : (Theme.slate ? (root.small ? 12 : 13) : (root.small ? 10.5 : 12))
        font.letterSpacing: Theme.noir ? 0 : (root.small ? 0.8 : 1)
        font.weight: root.primary ? Font.Medium : Font.Normal
    }

    HoverHandler { id: hover; cursorShape: Qt.PointingHandCursor }
    TapHandler { id: press; onTapped: if (root.enabled) root.clicked() }
    Keys.onSpacePressed: if (enabled) clicked()
    Keys.onReturnPressed: if (enabled) clicked()
    activeFocusOnTab: true
}
