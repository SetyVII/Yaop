import QtQuick
import QtQuick.Layouts
import "."

Item {
    id: root
    property string label: "OBJECTS"
    property string value: "1,284"
    property string note: ""

    implicitHeight: 64

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 16
        spacing: 3

        Text {
            text: root.label
            color: Theme.muted
            font.family: Theme.mono
            font.pixelSize: 11
            font.letterSpacing: Theme.track(1.5)
            font.weight: Font.Medium
        }

        Text {
            text: root.value
            color: Theme.text
            font.family: Theme.display
            font.pixelSize: 22
            font.weight: Font.DemiBold
            font.letterSpacing: -0.6
        }

        Text {
            text: root.note
            visible: text.length > 0
            color: Theme.dim
            font.family: Theme.mono
            font.pixelSize: 11
        }
    }
}
