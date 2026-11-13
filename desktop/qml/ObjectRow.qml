import QtQuick
import QtQuick.Layouts
import "."

// Object row (list mode): one quiet line per object.

Rectangle {
    id: root

    property var summary: ({})
    signal openRequested()
    signal restoreRequested()

    implicitWidth: 0
    implicitHeight: 52
    color: hover.hovered ? Theme.panel2 : "transparent"
    border.width: 0

    Behavior on color { ColorAnimation { duration: Theme.fast } }

    Rectangle {
        anchors.bottom: parent.bottom
        width: parent.width
        height: 1
        color: Theme.line
    }

    RowLayout {
        anchors.fill: parent
        anchors.leftMargin: 12
        anchors.rightMargin: 12
        spacing: 12

        Rectangle {
            Layout.preferredWidth: 8
            Layout.preferredHeight: 8
            radius: 4
            color: Theme.typeColor(summary.typeId !== undefined ? summary.typeId : "")
        }

        PrivateImage {
            visible: summary.thumbnailUrl !== undefined && String(summary.thumbnailUrl).length > 0
            Layout.preferredWidth: 42
            Layout.preferredHeight: 30
            source: visible ? String(summary.thumbnailUrl) : ""
            sourceSize: Qt.size(84, 60)
            fillMode: Image.PreserveAspectCrop
            asynchronous: true
            cache: true
        }

        Text {
            Layout.preferredWidth: 90
            text: Theme.typeLabel(summary.typeId !== undefined ? summary.typeId : "")
            color: Theme.muted
            font.family: Theme.mono
            font.pixelSize: 11
            font.letterSpacing: 1.2
            elide: Text.ElideRight
        }

        Text {
            Layout.fillWidth: true
            text: summary.title !== undefined ? summary.title : ""
            color: Theme.text
            font.family: Theme.body
            font.pixelSize: 15
            elide: Text.ElideRight
        }

        Text {
            visible: (summary.snippet !== undefined && summary.snippet.length > 0)
            Layout.preferredWidth: 200
            text: summary.snippet !== undefined ? summary.snippet : ""
            color: Theme.faint
            font.family: Theme.body
            font.pixelSize: 11
            elide: Text.ElideRight
        }

        Text {
            text: summary.updatedAt !== undefined
                  ? Qt.formatDateTime(new Date(summary.updatedAt), "dd MMM HH:mm")
                  : ""
            color: Theme.faint
            font.family: Theme.mono
            font.pixelSize: 11
        }

        Text {
            visible: summary.pinned === true
            text: "◆"
            color: Theme.ice
            font.family: Theme.mono
            font.pixelSize: 11
        }

        Text {
            visible: summary.trashed === true
            text: qsTr("RESTORE")
            color: Theme.accent
            font.family: Theme.mono
            font.pixelSize: 10
            font.letterSpacing: 1.0
            TapHandler { onTapped: root.restoreRequested() }
        }
    }

    HoverHandler { id: hover; cursorShape: Qt.PointingHandCursor }
    TapHandler { onTapped: openRequested() }
}
