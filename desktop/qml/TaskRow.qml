import QtQuick
import QtQuick.Layouts
import "."

Rectangle {
    id: root
    property var task: ({})
    signal toggled()

    height: 40
    color: hover.hovered ? Theme.panel2 : "transparent"

    Behavior on color { ColorAnimation { duration: Theme.fast } }

    RowLayout {
        anchors.fill: parent
        anchors.leftMargin: 16
        anchors.rightMargin: 16
        spacing: 10

        Rectangle {
            width: 14
            height: 14
            radius: Theme.radiusSm
            color: root.task.done ? Theme.accent : "transparent"
            border.width: 1
            border.color: root.task.done ? Theme.accent : Theme.line3

            Behavior on color { ColorAnimation { duration: Theme.fast } }

            TintedIcon {
                anchors.centerIn: parent
                name: "check"
                size: 10
                color: Theme.bg
                visible: root.task.done
                scale: root.task.done ? 1 : 0
                Behavior on scale {
                    NumberAnimation { duration: 140 * Theme.motion; easing.type: Easing.OutBack }
                }
            }
        }

        Text {
            Layout.fillWidth: true
            text: root.task.title || ""
            color: root.task.done ? Theme.muted : Theme.text
            font.family: Theme.body
            font.pixelSize: 12
            font.strikeout: root.task.done === true
            elide: Text.ElideRight
        }

        Text {
            text: root.task.tag ? (Theme.slate ? String(root.task.tag) : String(root.task.tag).toUpperCase()) : ""
            color: Theme.muted
            font.family: Theme.mono
            font.pixelSize: 11
            font.letterSpacing: Theme.track(0.8)
        }

        Text {
            text: root.task.due || ""
            color: Theme.muted
            font.family: Theme.mono
            font.pixelSize: 11
            width: 52
            horizontalAlignment: Text.AlignRight
        }
    }

    HoverHandler { id: hover; cursorShape: Qt.PointingHandCursor }
    TapHandler { onTapped: root.toggled() }
}
