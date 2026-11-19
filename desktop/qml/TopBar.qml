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
                case "home": return Theme.caps(qsTr("HOME"))
                case "journal": return Theme.caps(qsTr("DAILY"))
                case "library": return Theme.caps(qsTr("SPACES"))
                case "whiteboard": return Theme.caps(qsTr("WHITEBOARD"))
                case "tasks": return Theme.caps(qsTr("TASKS"))
                case "sets": return Theme.caps(qsTr("SETS"))
                case "editor": return Theme.caps(qsTr("OBJECT"))
                default: return ""
                }
            }
            color: Theme.dim
            font.family: Theme.mono
            font.pixelSize: 11
            font.letterSpacing: Theme.track(1.6)
            font.weight: Font.Medium
        }

        Item { Layout.fillWidth: true }
    }
}
