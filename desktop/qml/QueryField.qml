import QtQuick
import "."

// Small text field of the set query editor: commits on Enter or when focus leaves.
Rectangle {
    id: root
    property alias text: input.text
    property string placeholder: ""
    signal committed(string text)

    implicitHeight: 30
    implicitWidth: 120
    radius: Theme.radiusSm
    color: Theme.panel
    border.width: 1
    border.color: input.activeFocus ? Theme.ice : Theme.line

    TextInput {
        id: input
        anchors.fill: parent
        anchors.leftMargin: 10
        anchors.rightMargin: 10
        verticalAlignment: TextInput.AlignVCenter
        color: Theme.text
        font.family: Theme.mono
        font.pixelSize: 12
        selectByMouse: true
        clip: true
        onEditingFinished: root.committed(text)
        Text {
            anchors.verticalCenter: parent.verticalCenter
            visible: input.length === 0
            text: root.placeholder
            color: Theme.faint
            font: input.font
        }
    }
}
