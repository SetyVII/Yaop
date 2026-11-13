import QtQuick
import QtQuick.Layouts
import "."

// Pane header bar (spec §5): 40 px, mono caps title on the left, pane actions on the right.
// Search, capture and sync live in the window TitleBar.
Item {
    id: bar

    property string page: "home"
    signal searchRequested()
    signal newRequested()
    signal syncRequested()
    signal settingsRequested()

    implicitHeight: Theme.paneBarH

    Rectangle {
        anchors.bottom: parent.bottom
        width: parent.width
        height: 1
        color: Theme.line
    }

    RowLayout {
        anchors.fill: parent
        anchors.leftMargin: Theme.pagePad
        anchors.rightMargin: Theme.pagePad
        spacing: 8

        Text {
            text: {
                switch (bar.page) {
                case "home": return qsTr("HOME")
                case "journal": return qsTr("DAILY")
                case "library": return qsTr("SPACES")
                case "whiteboard": return qsTr("WHITEBOARD")
                case "tasks": return qsTr("TASKS")
                case "sets": return qsTr("SETS")
                case "editor": return qsTr("OBJECT")
                default: return ""
                }
            }
            color: Theme.dim
            font.family: Theme.mono
            font.pixelSize: 11
            font.letterSpacing: 1.6
            font.weight: Font.Medium
        }

        Item { Layout.fillWidth: true }
    }
}
