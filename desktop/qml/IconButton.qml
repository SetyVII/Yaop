import QtQuick
import QtQuick.Controls
import "."

// Icon button: in noir, sharp 0px square, accent fill with #000 icon when active.
Rectangle {
    id: root
    property string icon: "search"
    property bool active: false
    // Shown on hover after a short delay and read by screen readers.
    property string tip: ""
    property bool focusRing: true
    readonly property alias hovered: hover.hovered
    signal clicked()

    implicitWidth: Theme.noir ? 36 : 34
    implicitHeight: Theme.noir ? 36 : 34
    radius: Theme.noir ? 0 : Theme.slate ? Theme.radius : width / 2
    color: active ? (Theme.noir ? Theme.accent : Theme.panel2)
         : press.pressed ? Theme.panel3
         : hover.hovered ? Theme.panel2 : "transparent"

    Behavior on color { ColorAnimation { duration: Theme.fast } }

    TintedIcon {
        anchors.centerIn: parent
        name: root.icon
        size: 17
        color: root.active ? (Theme.noir ? Theme.accentInk : Theme.ice)
             : (hover.hovered ? Theme.text : Theme.muted)
    }

    HoverHandler { id: hover; cursorShape: Qt.PointingHandCursor }
    TapHandler { id: press; onTapped: root.clicked() }
    activeFocusOnTab: true
    Keys.onSpacePressed: root.clicked()
    Keys.onReturnPressed: root.clicked()
    Accessible.role: Accessible.Button
    Accessible.name: root.tip
    ToolTip.visible: root.tip.length > 0 && hover.hovered
    ToolTip.text: root.tip
    ToolTip.delay: 500
}
