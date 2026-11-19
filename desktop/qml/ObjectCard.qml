import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "."

// Object card (grid mode): type, title, snippet, cover thumbnail, meta.

Rectangle {
    id: root

    property var summary: ({})
    signal openRequested()
    signal openInPaneRequested()
    signal restoreRequested()

    implicitWidth: 320
    implicitHeight: 176
    // Card surface: sky = plain fill (spec §4); slate = a step above the page, outlined, with a
    // soft shadow, so it reads as a card on the gray.
    radius: Theme.radiusLg
    color: hover.hovered ? Theme.cardHover : Theme.card
    border.width: Theme.slate ? 1 : 0
    border.color: hover.hovered ? Theme.line3 : Theme.cardBorder
    clip: true

    Rectangle {
        z: -1
        visible: Theme.slate
        x: 0
        y: 3
        width: parent.width
        height: parent.height
        radius: parent.radius
        color: Theme.cardShadow
    }

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
    readonly property var tagList: (summary.tags !== undefined && summary.tags) ? summary.tags : []
    readonly property bool hasTags: tagList.length > 0

    PrivateImage {
        visible: root.hasCover
        anchors.top: parent.top
        width: parent.width
        height: root.hasTags ? 68 : 96
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
        anchors.margins: 14
        anchors.topMargin: root.hasCover ? (root.hasTags ? 76 : 104) : 16
        spacing: root.hasCover ? 5 : 7

        RowLayout {
            Layout.fillWidth: true
            spacing: 8

            TypeBadge {
                typeId: summary.typeId !== undefined ? summary.typeId : ""
                emoji: summary.iconEmoji || ""
                size: 22
            }

            Text {
                readonly property string tid: summary.typeId !== undefined ? summary.typeId : ""
                readonly property var info: ws && tid ? ws.typeInfo(tid) : null
                text: Theme.slate ? (info && info.name ? info.name : Theme.typeName(tid)) : Theme.typeLabel(tid)
                color: Theme.dim
                font.family: Theme.slate ? Theme.body : Theme.mono
                font.pixelSize: Theme.slate ? 12 : 11
                font.letterSpacing: Theme.slate ? 0 : 1.1
            }

            Item { Layout.fillWidth: true }

            IconButton {
                visible: Theme.slate && hover.hovered && summary.trashed !== true
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

        Text {
            Layout.fillWidth: true
            text: summary.title !== undefined ? summary.title : ""
            color: Theme.text
            font.family: Theme.body
            font.pixelSize: 16
            font.weight: Font.DemiBold
            font.letterSpacing: -0.2
            elide: Text.ElideRight
            wrapMode: Text.WordWrap
            maximumLineCount: (root.hasCover && root.hasTags) ? 1 : 2
        }

        Text {
            visible: !(root.hasCover && root.hasTags) && (summary.snippet !== undefined && summary.snippet.length > 0)
            Layout.fillWidth: true
            text: summary.snippet !== undefined ? summary.snippet : ""
            color: Theme.muted
            font.family: Theme.body
            font.pixelSize: 13
            elide: Text.ElideRight
            wrapMode: Text.WordWrap
            maximumLineCount: root.hasCover ? 1 : 2
        }

        Row {
            visible: root.hasTags
            Layout.fillWidth: true
            spacing: 5
            clip: true

            Repeater {
                model: root.tagList.slice(0, 3)
                delegate: Rectangle {
                    required property string modelData
                    readonly property color tone: ws ? ws.tagColor(modelData) : Theme.tagGreen
                    height: 19
                    width: cardTagLbl.implicitWidth + 12
                    radius: Theme.radiusSm
                    color: Qt.rgba(tone.r, tone.g, tone.b, 0.18)
                    border.width: 1
                    border.color: Qt.rgba(tone.r, tone.g, tone.b, 0.34)
                    Text {
                        id: cardTagLbl
                        anchors.centerIn: parent
                        text: modelData
                        color: Theme.text
                        font.family: Theme.body
                        font.pixelSize: 11
                        font.weight: Font.Medium
                    }
                }
            }
            Text {
                visible: root.tagList.length > 3
                anchors.verticalCenter: parent.verticalCenter
                text: "+" + (root.tagList.length - 3)
                color: Theme.muted
                font.family: Theme.mono
                font.pixelSize: 10
            }
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
