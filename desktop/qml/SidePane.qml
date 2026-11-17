import QtQuick
import QtQuick.Controls
import QtCore
import "."

// Side pane (slate): an object in a second rounded panel to the right of the page. It slides in
// from the right edge while the page gives way, can be resized by dragging the gap between them
// (width remembered per device), opened as the page (↗) or closed (×).
Item {
    id: pane

    property real hostWidth: 0
    property string objectId: ""
    property var appWindow: null
    readonly property alias editor: paneEditor
    signal closeRequested()
    signal expandRequested()

    Settings {
        id: prefs
        category: "layout"
        property real paneWidth: 520
    }
    readonly property int gap: 8
    readonly property real paneW: Math.max(340, Math.min(hostWidth - 380, prefs.paneWidth))
    readonly property bool open: objectId.length > 0
    property bool dragging: false

    // What the host gives up: eased on open/close, immediate while dragging.
    property real shownW: open ? paneW + gap : 0
    Behavior on shownW {
        enabled: !pane.dragging
        NumberAnimation { duration: 260; easing.type: Easing.OutCubic }
    }

    // Keeps the last object on screen while the pane slides out.
    property string shownId: ""
    onObjectIdChanged: if (objectId.length > 0) shownId = objectId
    onShownWChanged: if (shownW <= 0 && !open) shownId = ""

    width: shownW
    visible: shownW > 0.5
    clip: true

    // resize grip: the gap between page and pane
    Item {
        x: 0
        width: pane.gap
        height: parent.height
        z: 2
        Rectangle {
            anchors.horizontalCenter: parent.horizontalCenter
            anchors.verticalCenter: parent.verticalCenter
            width: 2
            height: Math.min(48, parent.height / 4)
            radius: 1
            color: Theme.line3
            opacity: grip.containsMouse || pane.dragging ? 1 : 0
            Behavior on opacity { NumberAnimation { duration: 140 } }
        }
        MouseArea {
            id: grip
            anchors.fill: parent
            hoverEnabled: true
            cursorShape: Qt.SplitHCursor
            onPressed: pane.dragging = true
            onReleased: pane.dragging = false
            onCanceled: pane.dragging = false
            onPositionChanged: (mouse) => {
                if (!pressed) return
                const x = mapToItem(pane.parent, mouse.x, 0).x
                prefs.paneWidth = Math.max(340, Math.min(pane.hostWidth - 380, pane.hostWidth - x - pane.gap / 2))
            }
        }
    }

    // The panel keeps its full width and is anchored to the right edge, so it slides in rather
    // than being revealed from the left.
    Rectangle {
        id: panel
        x: pane.width - width
        width: pane.paneW
        height: parent.height
        radius: Theme.radiusLg
        color: Theme.panel
        border.width: 1
        border.color: Theme.line
        clip: true

        // accent on the left edge: this panel is the pane, not the page
        Rectangle {
            x: 1
            y: Theme.radiusLg
            width: 3
            height: parent.height - 2 * Theme.radiusLg
            radius: 1.5
            color: Theme.ice
            opacity: 0.55
        }

        Row {
            id: paneBar
            anchors.right: parent.right
            anchors.rightMargin: 8
            y: 6
            spacing: 2
            z: 30
            IconButton {
                icon: "arrows-maximize"
                onClicked: pane.expandRequested()
                ToolTip.visible: hovered; ToolTip.text: qsTr("Open as page"); ToolTip.delay: 500
            }
            IconButton {
                icon: "x"
                onClicked: pane.closeRequested()
                ToolTip.visible: hovered; ToolTip.text: qsTr("Close the pane"); ToolTip.delay: 500
            }
        }

        EditorPage {
            id: paneEditor
            objectName: "paneEditor"
            anchors.fill: parent
            anchors.topMargin: 40
            anchors.leftMargin: 4
            appWindow: pane.appWindow
            objectId: pane.shownId
            narrowRail: true
            onCloseRequested: pane.closeRequested()
        }
    }
}
