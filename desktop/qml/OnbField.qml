import QtQuick
import QtQuick.Layouts
import "."

// Labeled single-line (or multi-line) input used by onboarding and settings.
ColumnLayout {
    id: root
    property string label: ""
    property alias text: input.text
    property bool multiline: false
    property bool secret: false
    spacing: 6

    Text {
        text: root.label
        color: Theme.muted
        font.family: Theme.mono
        font.pixelSize: 11
        font.letterSpacing: 1.2
    }
    Rectangle {
        Layout.fillWidth: true
        Layout.preferredHeight: root.multiline ? 96 : 36
        radius: Theme.radius
        color: Theme.panel
        border.width: 1
        border.color: input.activeFocus ? Theme.ice : "transparent"

        TextEdit {
            id: input
            anchors.fill: parent
            anchors.margins: 10
            color: Theme.text
            font.family: Theme.mono
            font.pixelSize: 13
            wrapMode: root.multiline ? TextEdit.Wrap : TextEdit.NoWrap
            clip: true
            selectByMouse: true
            // single-line: Enter does not insert a newline
            Keys.onPressed: (e) => { if (!root.multiline && (e.key === Qt.Key_Return || e.key === Qt.Key_Enter)) e.accepted = true }
        }
    }
}
