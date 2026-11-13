import QtQuick
import QtQuick.Layouts
import "."

// Object card (grid mode): type, title, snippet, cover thumbnail, meta.

Rectangle {
    id: root

    property var summary: ({})
    signal openRequested()
    signal restoreRequested()

    implicitWidth: 320
    implicitHeight: 176
    // Card surface: plain fill, no border (spec §4)
    radius: Theme.radiusLg
    color: hover.hovered ? Theme.panel2 : Theme.panel
    clip: true

    Behavior on color { ColorAnimation { duration: Theme.fast } }

    // cover thumbnail (cover/image assets, or the web link's OG thumbnail)
    readonly property string coverSrc: {
        const asset = summary.coverAssetId !== undefined && summary.coverAssetId.length > 0
                    ? summary.coverAssetId
                    : ((summary.assetId !== undefined && summary.typeId === "image") ? summary.assetId : "")
        if (asset.length > 0) {
            const path = ws.assetLocalPath(asset)
            if (path.length > 0)
                return "file://" + path
        }
        if (summary.thumbnailUrl !== undefined && String(summary.thumbnailUrl).length > 0)
            return String(summary.thumbnailUrl)
        return ""
    }
    readonly property bool hasCover: coverSrc.length > 0

    PrivateImage {
        visible: root.hasCover
        anchors.top: parent.top
        width: parent.width
        height: 96
        source: root.coverSrc
        sourceSize: Qt.size(480, 192)
        fillMode: Image.PreserveAspectCrop
        asynchronous: true
        cache: true
        opacity: 0.9
    }

    ColumnLayout {
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        anchors.top: parent.top
        anchors.margins: 16
        anchors.topMargin: root.hasCover ? 106 : 16
        spacing: 7

        RowLayout {
            Layout.fillWidth: true
            spacing: 8

            Rectangle {
                width: 7
                height: 7
                radius: 3.5
                color: Theme.typeColor(summary.typeId !== undefined ? summary.typeId : "")
            }

            Text {
                text: Theme.typeLabel(summary.typeId !== undefined ? summary.typeId : "")
                color: Theme.dim
                font.family: Theme.mono
                font.pixelSize: 11
                font.letterSpacing: 1.1
            }

            Item { Layout.fillWidth: true }

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

        Text {
            Layout.fillWidth: true
            text: summary.title !== undefined ? summary.title : ""
            color: Theme.text
            font.family: Theme.body
            font.pixelSize: 17
            font.weight: Font.DemiBold
            font.letterSpacing: -0.2
            elide: Text.ElideRight
            wrapMode: Text.WordWrap
            maximumLineCount: 2
        }

        Text {
            visible: (summary.snippet !== undefined && summary.snippet.length > 0)
            Layout.fillWidth: true
            text: summary.snippet !== undefined ? summary.snippet : ""
            color: Theme.muted
            font.family: Theme.body
            font.pixelSize: 13
            elide: Text.ElideRight
            wrapMode: Text.WordWrap
            maximumLineCount: 2
        }

        Item { Layout.fillHeight: true }

        RowLayout {
            Layout.fillWidth: true
            spacing: 6

            Text {
                text: summary.blockCount !== undefined ? qsTr("%1 blocks").arg(summary.blockCount) : ""
                color: Theme.muted
                font.family: Theme.mono
                font.pixelSize: 11
            }
            Item { Layout.fillWidth: true }
            Text {
                text: summary.updatedAt !== undefined
                      ? Qt.formatDateTime(new Date(summary.updatedAt), "dd MMM HH:mm")
                      : ""
                color: Theme.muted
                font.family: Theme.mono
                font.pixelSize: 11
            }
        }
    }

    HoverHandler { id: hover; cursorShape: Qt.PointingHandCursor }
    TapHandler { onTapped: openRequested() }
}
