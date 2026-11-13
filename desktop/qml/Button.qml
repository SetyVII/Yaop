import QtQuick
import "."

// Button (spec §5): primary = white fill with ink text (one per view); secondary =
// transparent with a 1 px line. Mono caps, radius 14.
Rectangle {
    id: root
    property string text: "BUTTON"
    property bool primary: false
    property bool small: false
    signal clicked()

    implicitWidth: label.implicitWidth + (small ? 24 : 32)
    implicitHeight: small ? 30 : 38
    radius: small ? 11 : Theme.radius
    opacity: enabled ? 1 : 0.4
    color: primary
           ? (press.pressed ? Theme.accent2 : hover.hovered ? Qt.lighter(Theme.accent, 1.0) : Theme.accent)
           : (press.pressed ? Theme.panel3 : hover.hovered ? Theme.panel2 : "transparent")
    border.width: primary ? 0 : 1
    border.color: hover.hovered ? Theme.line3 : Theme.line
    scale: press.pressed ? 0.98 : 1

    Behavior on color { ColorAnimation { duration: Theme.fast } }
    Behavior on border.color { ColorAnimation { duration: Theme.fast } }
    Behavior on scale { NumberAnimation { duration: 90 } }

    // keyboard focus ring (ice)
    Rectangle {
        anchors.fill: parent
        anchors.margins: -3
        radius: parent.radius + 3
        color: "transparent"
        border.width: 2
        border.color: Theme.ice
        opacity: root.activeFocus ? 0.8 : 0
        visible: opacity > 0
    }

    Text {
        id: label
        anchors.centerIn: parent
        text: root.text.toUpperCase()
        color: root.primary ? Theme.accentInk : Theme.text
        font.family: Theme.mono
        font.pixelSize: root.small ? 10.5 : 12
        font.letterSpacing: root.small ? 0.8 : 1
        font.weight: Font.Medium
    }

    HoverHandler { id: hover; cursorShape: Qt.PointingHandCursor }
    TapHandler { id: press; onTapped: if (root.enabled) root.clicked() }
    Keys.onSpacePressed: if (enabled) clicked()
    Keys.onReturnPressed: if (enabled) clicked()
    activeFocusOnTab: true
}
