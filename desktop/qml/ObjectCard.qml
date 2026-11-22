import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "."

// Object card (grid mode — docs/design/mockup/WebLinks.dc.html):
// Pure black surface, 1px #1F1F1F border (#4A4A4A on hover), sharp 0px corners in noir,
// 112px cover band, title, mono meta, 7px color-square chips.
Rectangle {
    id: root

    property var summary: ({})
    signal openRequested()
    signal openInPaneRequested()
    signal restoreRequested()

    implicitWidth: 280
    implicitHeight: 248
    radius: Theme.radiusLg
    color: hover.hovered ? Theme.cardHover : Theme.card
    border.width: Theme.modern ? 1 : 0
    border.color: hover.hovered ? Theme.line3 : Theme.cardBorder
    clip: true

    Behavior on color { ColorAnimation { duration: Theme.fast } }
    Behavior on border.color { ColorAnimation { duration: Theme.fast } }

    readonly property string coverSrc: {
        const asset = summary.coverAssetId !== undefined && summary.coverAssetId.length > 0
                    ? summary.coverAssetId
                    : ((summary.assetId !== undefined && summary.typeId === "image") ? summary.assetId : "")
        if (asset.length > 0 && ws) {
            const path = ws.assetLocalPath(asset)
            if (path.length > 0) return "file://" + path
        }
        if (summary.thumbnailUrl !== undefined && String(summary.thumbnailUrl).length > 0)
            return String(summary.thumbnailUrl)
        return ""
    }
    readonly property bool hasCover: coverSrc.length > 0
    readonly property bool isWeb: (summary.typeId || "") === "web"
    readonly property bool isBook: (summary.typeId || "") === "book"
    readonly property bool isPerson: (summary.typeId || "") === "person"
    readonly property bool isTag: (summary.typeId || "") === "tag"
    readonly property bool isHabit: (summary.typeId || "") === "habit"
    readonly property bool isProject: (summary.typeId || "") === "project"
    readonly property bool isPdf: (summary.typeId || "") === "pdf"
    readonly property bool isAudio: (summary.typeId || "") === "audio"
    // Minimal #050505 geometric hero band (TypeHeroes.dc.html / WebLinks.dc.html)
    readonly property bool hasHeroBand: Theme.noir && (hasCover || isWeb || isBook || isPerson || isTag)
    readonly property var webCatOpt: (isWeb && ws && summary.category) ? ws.resolveOption("web", "category", summary.category) : null
    readonly property var webTopicOpt: (isWeb && ws && summary.topics && summary.topics.length > 0) ? ws.resolveOption("web", "topics", summary.topics[0]) : null
    readonly property var bookStatusOpt: (isBook && ws && summary.status) ? ws.resolveOption("book", "status", summary.status) : null
    readonly property var projProg: (isProject && ws && summary.id) ? ws.projectProgress(summary.id) : null
    readonly property var tagList: (summary.tags !== undefined && summary.tags) ? summary.tags : []
    readonly property bool hasTags: tagList.length > 0

    function formatDur(sec) {
        const s = Math.max(0, Math.round(Number(sec) || 0))
        const m = Math.floor(s / 60), r = s % 60
        return m + ":" + (r < 10 ? "0" : "") + r
    }
    function initials(name) {
        const parts = String(name || "").trim().split(/\s+/).filter(p => p.length > 0)
        if (parts.length === 0) return "?"
        if (parts.length === 1) return parts[0].substring(0, 2).toUpperCase()
        return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase()
    }

    // Cover image OR TypeHero geometric preview band (104px — WebLinks.dc.html / TypeHeroes.dc.html)
    Rectangle {
        visible: root.hasHeroBand || (!Theme.noir && root.hasCover)
        anchors.top: parent.top
        anchors.left: parent.left
        anchors.right: parent.right
        height: 104
        color: Theme.ink
        clip: true

        PrivateImage {
            visible: root.hasCover
            inObject: true
            anchors.fill: parent
            source: root.coverSrc
            sourceSize: Qt.size(480, 208)
            fillMode: Image.PreserveAspectCrop
            asynchronous: true
            cache: true
        }

        // Web link wireframe hero when no cover image (WebLinks.dc.html)
        Item {
            visible: !root.hasCover && root.isWeb
            anchors.fill: parent
            Rectangle { x: 20; y: 22; width: parent.width * 0.52; height: 1; color: Theme.line2 }
            Rectangle { x: 20; y: 34; width: parent.width * 0.72; height: 1; color: Theme.line2 }
            Rectangle { x: 20; y: 46; width: parent.width * 0.38; height: 1; color: Theme.line2 }
            Rectangle { x: 20; y: 74; width: parent.width * 0.80; height: 1; color: Theme.lineSubtle }
            Rectangle {
                anchors.right: parent.right; anchors.rightMargin: 24; anchors.verticalCenter: parent.verticalCenter
                width: 34; height: 34
                color: hover.hovered ? Theme.accent : Theme.line2
                Behavior on color { ColorAnimation { duration: 250 * Theme.motion } }
            }
        }

        // Book upright cover frame when no cover image (TypeHeroes.dc.html)
        Rectangle {
            visible: !root.hasCover && root.isBook
            anchors.centerIn: parent; width: 62; height: 82
            color: "transparent"; border.width: 1; border.color: Theme.line3
            Rectangle { x: 8; y: 12; width: 44; height: 1; color: Theme.line3 }
            Rectangle { x: 8; y: 18; width: 30; height: 1; color: Theme.line3 }
            Rectangle {
                anchors.centerIn: parent; anchors.verticalCenterOffset: 8
                width: 26; height: 26
                color: hover.hovered ? Theme.accent : Theme.book
                Behavior on color { ColorAnimation { duration: 250 * Theme.motion } }
            }
        }

        // Person monogram hero (TypeHeroes.dc.html)
        Rectangle {
            visible: !root.hasCover && root.isPerson
            anchors.centerIn: parent; width: 52; height: 52
            color: "transparent"; border.width: 1; border.color: Theme.person
            Text {
                anchors.centerIn: parent
                text: root.initials(summary.title)
                color: Theme.person
                font.family: Theme.display; font.pixelSize: 18
            }
        }

        // Tag hero badge (TypeHeroes.dc.html)
        Rectangle {
            visible: !root.hasCover && root.isTag
            readonly property color tc: ws ? ws.tagColor(summary.title || "") : Theme.tag
            anchors.centerIn: parent
            implicitWidth: tgHeroRow.implicitWidth + 24; implicitHeight: 34
            color: "transparent"; border.width: 1; border.color: tc
            Row {
                id: tgHeroRow; anchors.centerIn: parent; spacing: 8
                Rectangle { anchors.verticalCenter: parent.verticalCenter; width: 8; height: 8; color: parent.parent.tc }
                Text { anchors.verticalCenter: parent.verticalCenter; text: summary.title || ""; color: Theme.text; font.family: Theme.body; font.pixelSize: 14 }
            }
        }

        Rectangle {
            anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom
            height: 1; color: Theme.line
        }
    }

    ColumnLayout {
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        anchors.top: parent.top
        anchors.margins: 14
        anchors.topMargin: (root.hasHeroBand || (!Theme.noir && root.hasCover)) ? 116 : 16
        spacing: 6

        RowLayout {
            Layout.fillWidth: true
            spacing: 7

            TypeBadge {
                typeId: summary.typeId !== undefined ? summary.typeId : ""
                emoji: summary.iconEmoji || ""
                size: 16
            }

            Text {
                readonly property string tid: summary.typeId !== undefined ? summary.typeId : ""
                readonly property var info: ws && tid ? ws.typeInfo(tid) : null
                text: Theme.caps(info && info.name ? info.name : Theme.typeName(tid))
                color: Theme.label
                font.family: Theme.mono
                font.pixelSize: 10
            }

            Item { Layout.fillWidth: true }

            IconButton {
                visible: Theme.modern && hover.hovered && summary.trashed !== true
                implicitWidth: 24; implicitHeight: 24
                icon: "layout-columns"
                onClicked: root.openInPaneRequested()
                ToolTip.visible: hovered; ToolTip.text: qsTr("Open in side pane (Shift+click)"); ToolTip.delay: 400
            }

            Text {
                visible: summary.pinned === true
                text: "◆"
                color: Theme.accent
                font.family: Theme.mono
                font.pixelSize: 10
            }
            Text {
                visible: summary.trashed === true
                text: Theme.caps(qsTr("RESTORE"))
                color: Theme.accent
                font.family: Theme.mono
                font.pixelSize: 10
                TapHandler { onTapped: root.restoreRequested() }
            }
        }

        Text {
            Layout.fillWidth: true
            text: summary.title !== undefined && summary.title.length > 0 ? summary.title : qsTr("Untitled")
            color: Theme.text
            font.family: Theme.body
            font.pixelSize: 15
            font.weight: Font.Normal
            elide: Text.ElideRight
            wrapMode: Text.WordWrap
            maximumLineCount: root.hasCover ? 2 : 2
        }

        // Web link domain (WebLinks.dc.html)
        Text {
            visible: root.isWeb && (summary.domain || "").length > 0
            Layout.fillWidth: true
            text: Theme.caps(summary.domain || "")
            color: Theme.label
            font.family: Theme.mono
            font.pixelSize: 10
            elide: Text.ElideRight
        }

        // Book author + mini 5-bar rating (TypeHeroes.dc.html / Book.dc.html)
        RowLayout {
            visible: root.isBook && ((summary.author || "").length > 0 || (summary.rating || 0) > 0)
            Layout.fillWidth: true
            spacing: 8
            Text {
                Layout.fillWidth: true
                visible: (summary.author || "").length > 0
                text: summary.author || ""
                color: Theme.dim; font.family: Theme.body; font.pixelSize: 12
                elide: Text.ElideRight
            }
            Row {
                visible: (summary.rating || 0) > 0
                spacing: 2
                Repeater {
                    model: 5
                    delegate: Rectangle {
                        required property int index
                        width: 10; height: 4
                        color: (index + 1) <= (summary.rating || 0) ? Theme.accent : Theme.line2
                    }
                }
            }
        }

        // Project task progress bar (TypeHeroes.dc.html)
        ColumnLayout {
            visible: root.isProject && root.projProg && root.projProg.total > 0
            Layout.fillWidth: true
            spacing: 4
            RowLayout {
                Layout.fillWidth: true
                Text {
                    text: root.projProg ? qsTr("%1 of %2 tasks").arg(root.projProg.done).arg(root.projProg.total) : ""
                    color: Theme.muted; font.family: Theme.mono; font.pixelSize: 10
                }
                Item { Layout.fillWidth: true }
                Text {
                    visible: root.projProg && (root.projProg.notes > 0 || root.projProg.people > 0)
                    text: root.projProg ? qsTr("%1n · %2p").arg(root.projProg.notes).arg(root.projProg.people) : ""
                    color: Theme.label; font.family: Theme.mono; font.pixelSize: 10
                }
            }
            Rectangle {
                Layout.fillWidth: true; Layout.preferredHeight: 3
                color: Theme.line2
                Rectangle {
                    width: root.projProg && root.projProg.total > 0 ? parent.width * (root.projProg.done / root.projProg.total) : 0
                    height: parent.height
                    color: Theme.project
                }
            }
        }

        // PDF reading progress / Audio duration (TypeHeroes.dc.html)
        Text {
            visible: (root.isPdf && (summary.pageCount || 0) > 0) || (root.isAudio && (summary.duration || 0) > 0)
            text: root.isPdf
                ? Theme.caps(qsTr("PAGE %1 / %2").arg(summary.lastReadPage || 0).arg(summary.pageCount || 0))
                : Theme.caps(root.formatDur(summary.duration))
            color: Theme.label; font.family: Theme.mono; font.pixelSize: 10
        }

        Text {
            visible: !root.hasCover && !root.isWeb && !root.isBook && !root.isProject && (summary.snippet !== undefined && summary.snippet.length > 0)
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

            // Web link: Category chip + Topic chip (WebLinks.dc.html)
            Row {
                visible: root.isWeb && (!!root.webCatOpt || !!root.webTopicOpt)
                spacing: 5
                Rectangle {
                    visible: !!root.webCatOpt
                    height: 22; width: wCatRow.implicitWidth + 12
                    color: "transparent"; border.width: 1; border.color: Theme.line2
                    Row {
                        id: wCatRow; anchors.centerIn: parent; spacing: 5
                        Rectangle { anchors.verticalCenter: parent.verticalCenter; width: 6; height: 6; color: root.webCatOpt ? Theme.paletteColor(root.webCatOpt.color || "blue") : Theme.dim }
                        Text { anchors.verticalCenter: parent.verticalCenter; text: root.webCatOpt ? root.webCatOpt.label : ""; color: Theme.bodyText; font.family: Theme.body; font.pixelSize: 11 }
                    }
                }
                Rectangle {
                    visible: !!root.webTopicOpt
                    height: 22; width: wTopTxt.implicitWidth + 12
                    color: "transparent"; border.width: 1; border.color: Theme.line2
                    Text { id: wTopTxt; anchors.centerIn: parent; text: root.webTopicOpt ? root.webTopicOpt.label : ""; color: Theme.muted; font.family: Theme.body; font.pixelSize: 11 }
                }
            }

            // Book status chip
            Rectangle {
                visible: root.isBook && !!root.bookStatusOpt
                height: 22; width: bkStRow.implicitWidth + 12
                color: "transparent"; border.width: 1; border.color: Theme.line2
                Row {
                    id: bkStRow; anchors.centerIn: parent; spacing: 5
                    Rectangle { anchors.verticalCenter: parent.verticalCenter; width: 6; height: 6; color: root.bookStatusOpt ? Theme.paletteColor(root.bookStatusOpt.color || "purple") : Theme.book }
                    Text { anchors.verticalCenter: parent.verticalCenter; text: root.bookStatusOpt ? root.bookStatusOpt.label : ""; color: Theme.dim; font.family: Theme.body; font.pixelSize: 11 }
                }
            }

            Row {
                visible: root.hasTags && !(root.isWeb && (!!root.webCatOpt || !!root.webTopicOpt)) && !(root.isBook && !!root.bookStatusOpt)
                spacing: 5
                Repeater {
                    model: root.tagList.slice(0, 2)
                    delegate: Rectangle {
                        required property string modelData
                        readonly property color tone: ws ? ws.tagColor(modelData) : Theme.tag
                        height: 22
                        width: cardTagRow.implicitWidth + 12
                        radius: Theme.radiusSm
                        color: "transparent"
                        border.width: 1
                        border.color: Theme.line2
                        Row {
                            id: cardTagRow
                            anchors.centerIn: parent; spacing: 5
                            Rectangle { anchors.verticalCenter: parent.verticalCenter; width: 6; height: 6; color: tone }
                            Text {
                                anchors.verticalCenter: parent.verticalCenter
                                text: modelData
                                color: Theme.dim; font.family: Theme.body; font.pixelSize: 11
                            }
                        }
                    }
                }
            }
            Item { Layout.fillWidth: true }
            Text {
                text: summary.updatedAt !== undefined
                      ? Theme.caps(Qt.formatDateTime(new Date(summary.updatedAt), "dd MMM"))
                      : ""
                color: Theme.label
                font.family: Theme.mono
                font.pixelSize: 10
            }
        }
    }

    HoverHandler { id: hover; cursorShape: Qt.PointingHandCursor }
    TapHandler { onTapped: openRequested() }
}
