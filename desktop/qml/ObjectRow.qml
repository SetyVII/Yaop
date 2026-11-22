import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "."

// Object row (list mode): one quiet line per object.

Rectangle {
    id: root

    property var summary: ({})
    signal openRequested()
    signal openInPaneRequested()
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

        TypeBadge {
            typeId: summary.typeId !== undefined ? summary.typeId : ""
            emoji: summary.iconEmoji || ""
            size: 24
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
            readonly property string tid: summary.typeId !== undefined ? summary.typeId : ""
            readonly property var info: ws && tid ? ws.typeInfo(tid) : null
            text: Theme.slate ? (info && info.name ? info.name : Theme.typeName(tid)) : Theme.typeLabel(tid)
            color: Theme.muted
            font.family: Theme.slate ? Theme.body : Theme.mono
            font.pixelSize: Theme.slate ? 12 : 11
            font.letterSpacing: Theme.slate ? 0 : 1.2
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

        Row {
            readonly property var tags: (summary.tags !== undefined && summary.tags) ? summary.tags : []
            visible: tags.length > 0
            spacing: 4
            Repeater {
                model: parent.tags.slice(0, 2)
                delegate: Rectangle {
                    required property string modelData
                    readonly property color tone: ws ? ws.tagColor(modelData) : Theme.tagGreen
                    height: 20
                    width: rowTagLbl.implicitWidth + 12
                    radius: Theme.radiusSm
                    color: Qt.rgba(tone.r, tone.g, tone.b, 0.18)
                    border.width: 1
                    border.color: Qt.rgba(tone.r, tone.g, tone.b, 0.34)
                    Text {
                        id: rowTagLbl
                        anchors.centerIn: parent
                        text: modelData
                        color: Theme.text
                        font.family: Theme.body
                        font.pixelSize: 11
                        font.weight: Font.Medium
                    }
                }
            }
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

        IconButton {
            visible: Theme.modern && hover.hovered && summary.trashed !== true
            icon: "layout-columns"
            onClicked: root.openInPaneRequested()
            ToolTip.visible: hovered; ToolTip.text: qsTr("Open in side pane (Shift+click)"); ToolTip.delay: 400
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
            text: Theme.caps(qsTr("RESTORE"))
            color: Theme.accent
            font.family: Theme.mono
            font.pixelSize: 10
            font.letterSpacing: Theme.track(1.0)
            TapHandler { onTapped: root.restoreRequested() }
        }
    }

    HoverHandler { id: hover; cursorShape: Qt.PointingHandCursor }
    TapHandler { onTapped: openRequested() }
}
