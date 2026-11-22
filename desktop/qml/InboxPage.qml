import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "."
import "editor"

// "Por revisar" split workspace (docs/design/mockup/Reviewed.dc.html):
// Left 420px list of pending (reviewed == false) + reviewed (reviewed == true) items with
// sharp accent checkboxes; right pane shows the selected object's cover, description,
// editable PropertyTable (Revisado, Enlace, Categoría, Tema), and live BlocksView notes.
Item {
    id: page

    property var appWindow: null
    signal openObjectRequested(string objectId)

    readonly property int dataRev: (visible && ws) ? ws.listRevision : 0
    property int rev: 0
    property string selectedId: ""
    property var session: null

    Connections {
        target: ws
        function onObjectPropertiesChanged() { page.rev++ }
    }

    readonly property var pending: { dataRev; rev; return ws ? ws.inbox() : [] }
    readonly property var done: { dataRev; rev; return ws ? ws.reviewedItems(25) : [] }
    readonly property var currentItem: {
        const all = pending.concat(done)
        for (let i = 0; i < all.length; i++)
            if (all[i].id === selectedId) return all[i]
        return all.length > 0 ? all[0] : null
    }
    readonly property string activeId: currentItem ? currentItem.id : ""

    function syncSession() {
        const want = (visible && activeId.length > 0) ? activeId : ""
        if (session && session.objectId === want) return
        if (session) {
            const old = session.objectId
            session = null
            if (!appWindow || !appWindow.objectShownElsewhere(old, page))
                ws.closeObject(old)
        }
        if (want && ws) session = ws.openObject(want)
    }
    onActiveIdChanged: Qt.callLater(syncSession)
    onVisibleChanged: Qt.callLater(syncSession)

    function toggleReviewed(item) {
        if (!ws || !item) return
        ws.setObjectProperty(item.id, "reviewed", !item.reviewed, "checkbox")
        page.rev++
    }

    RowLayout {
        anchors.fill: parent
        spacing: 0

        // Left pane: Por revisar + Revisados (420px — Reviewed.dc.html)
        Rectangle {
            Layout.preferredWidth: 420
            Layout.fillHeight: true
            color: Theme.page

            Rectangle { anchors.top: parent.top; anchors.bottom: parent.bottom; anchors.right: parent.right; width: 1; color: Theme.line }

            ColumnLayout {
                anchors.fill: parent
                spacing: 0

                // Header
                RowLayout {
                    Layout.fillWidth: true
                    Layout.margins: 20
                    Layout.topMargin: 28
                    Layout.bottomMargin: 16
                    Text { text: qsTr("To review"); color: Theme.text; font.family: Theme.display; font.pixelSize: 22 }
                    Item { Layout.fillWidth: true }
                    Text {
                        text: Theme.caps(qsTr("%1 · SAVED VIEW").arg(page.pending.length))
                        color: Theme.label; font.family: Theme.mono; font.pixelSize: 11
                    }
                }
                Rectangle { Layout.fillWidth: true; height: 1; color: Theme.lineSubtle }

                Flickable {
                    Layout.fillWidth: true; Layout.fillHeight: true
                    contentWidth: width; contentHeight: leftCol.implicitHeight + 32
                    clip: true; boundsBehavior: Flickable.StopAtBounds
                    ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }

                    ColumnLayout {
                        id: leftCol
                        width: parent.width
                        spacing: 0

                        Repeater {
                            model: page.pending
                            delegate: reviewRowComp
                        }

                        Text {
                            visible: page.pending.length === 0
                            Layout.margins: 20
                            text: qsTr("All reviewed.")
                            color: Theme.muted; font.family: Theme.body; font.pixelSize: 14
                        }

                        // Reviewed section
                        Text {
                            visible: page.done.length > 0
                            Layout.leftMargin: 18; Layout.topMargin: 24; Layout.bottomMargin: 8
                            text: Theme.caps(qsTr("REVIEWED · %1").arg(page.done.length))
                            color: Theme.label; font.family: Theme.mono; font.pixelSize: 11
                        }
                        Repeater {
                            model: page.done
                            delegate: reviewRowComp
                        }
                    }
                }
            }
        }

        // Right pane: Selected object review details + editable properties + notes
        Item {
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true

            Text {
                visible: !page.currentItem
                anchors.centerIn: parent
                text: qsTr("Nothing waiting for review. Shared web links and captures appear here.")
                color: Theme.label; font.family: Theme.body; font.pixelSize: 14
            }

            Flickable {
                visible: !!page.currentItem
                anchors.fill: parent
                contentWidth: width
                contentHeight: rightCol.implicitHeight + 80
                clip: true
                boundsBehavior: Flickable.StopAtBounds
                ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }

                ColumnLayout {
                    id: rightCol
                    x: 48; y: 36
                    width: Math.min(parent.width - 96, 820)
                    spacing: 0

                    // Top split: Type chip + Title + Description | Cover preview (220x140)
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 28

                        ColumnLayout {
                            Layout.fillWidth: true
                            Layout.alignment: Qt.AlignTop
                            spacing: 12

                            RowLayout {
                                spacing: 8
                                Rectangle {
                                    implicitWidth: tChipRow.implicitWidth + 18; implicitHeight: 26
                                    color: "transparent"; border.width: 1; border.color: Theme.line2
                                    Row {
                                        id: tChipRow; anchors.centerIn: parent; spacing: 7
                                        TypeBadge { typeId: page.currentItem ? page.currentItem.typeId : ""; size: 14 }
                                        Text {
                                            readonly property var ti: (ws && page.currentItem) ? ws.typeInfo(page.currentItem.typeId) : null
                                            text: ti && ti.name ? ti.name : (page.currentItem ? Theme.typeName(page.currentItem.typeId) : "")
                                            color: Theme.bodyText; font.family: Theme.body; font.pixelSize: 13
                                        }
                                    }
                                }
                                Item { Layout.fillWidth: true }
                                Button {
                                    small: true
                                    text: qsTr("Open in editor ↗")
                                    onClicked: if (page.currentItem) page.openObjectRequested(page.currentItem.id)
                                }
                            }

                            Text {
                                Layout.fillWidth: true
                                text: page.currentItem ? (page.currentItem.title || qsTr("Untitled")) : ""
                                color: Theme.text
                                font.family: Theme.display; font.pixelSize: 32; font.letterSpacing: -0.6
                                wrapMode: Text.WordWrap
                            }

                            Text {
                                visible: !!page.currentItem && String(page.currentItem.description || "").length > 0
                                Layout.fillWidth: true
                                text: page.currentItem ? String(page.currentItem.description || "") : ""
                                color: Theme.muted
                                font.family: Theme.body; font.pixelSize: 15; lineHeight: 1.5
                                wrapMode: Text.WordWrap
                                maximumLineCount: 4
                                elide: Text.ElideRight
                            }
                        }

                        // Cover thumbnail (220x140 — Reviewed.dc.html)
                        Rectangle {
                            readonly property string src: {
                                if (!page.currentItem || !ws) return ""
                                if (page.currentItem.coverAssetId) {
                                    const p = ws.assetLocalPath(page.currentItem.coverAssetId)
                                    if (p) return "file://" + p
                                }
                                return page.currentItem.thumbnailUrl || ""
                            }
                            visible: src.length > 0
                            Layout.preferredWidth: 220; Layout.preferredHeight: 140
                            Layout.alignment: Qt.AlignTop
                            color: Theme.ink; border.width: 1; border.color: Theme.line2; clip: true
                            PrivateImage {
                                inObject: true
                                anchors.fill: parent
                                source: parent.src
                                fillMode: Image.PreserveAspectCrop
                                asynchronous: true; cache: true
                            }
                        }
                    }

                    // URL bar for web links
                    Rectangle {
                        visible: !!page.currentItem && String(page.currentItem.url || "").length > 0
                        Layout.fillWidth: true; Layout.topMargin: 24; Layout.preferredHeight: 44
                        color: "transparent"
                        Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.lineSubtle }
                        RowLayout {
                            anchors.fill: parent; spacing: 12
                            Text { Layout.preferredWidth: 150; text: qsTr("Link"); color: Theme.label; font.family: Theme.body; font.pixelSize: 14 }
                            Text {
                                Layout.fillWidth: true
                                text: page.currentItem ? String(page.currentItem.url || "") : ""
                                color: Theme.dim; font.family: Theme.body; font.pixelSize: 14; font.underline: true
                                elide: Text.ElideRight
                                HoverHandler { cursorShape: Qt.PointingHandCursor }
                                TapHandler { onTapped: if (page.currentItem && page.currentItem.url) Qt.openUrlExternally(page.currentItem.url) }
                            }
                        }
                    }

                    // Editable PropertyTable (Revisado, Categoría, Tema…)
                    PropertyTable {
                        Layout.fillWidth: true
                        Layout.topMargin: 4
                        objectId: page.activeId
                        objectTypeId: page.currentItem ? page.currentItem.typeId : ""
                        hideWebHeaderProps: true
                        labelWidth: 150
                    }

                    // Inline notes blocks
                    Text {
                        Layout.topMargin: 28; Layout.bottomMargin: 8
                        text: Theme.caps(qsTr("NOTES"))
                        color: Theme.label; font.family: Theme.mono; font.pixelSize: 11
                    }
                    Item {
                        Layout.fillWidth: true
                        Layout.preferredHeight: 320
                        BlocksView {
                            anchors.fill: parent
                            session: page.session
                            onOpenObjectRequested: (id) => page.openObjectRequested(id)
                            onToastRequested: (msg) => { if (page.appWindow) page.appWindow.showToast(msg) }
                        }
                    }
                }
            }
        }
    }

    Component {
        id: reviewRowComp
        Rectangle {
            id: rRow
            required property var modelData
            readonly property bool sel: page.activeId === modelData.id
            readonly property bool isDone: !!modelData.reviewed
            Layout.fillWidth: true
            implicitHeight: isDone ? 48 : 64
            color: sel ? Theme.panel2 : rHov.hovered ? Theme.cardHover : "transparent"

            Rectangle { visible: sel; anchors.left: parent.left; anchors.top: parent.top; anchors.bottom: parent.bottom; width: 2; color: Theme.accent }
            Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.lineSubtle }

            RowLayout {
                anchors.fill: parent; anchors.leftMargin: 16; anchors.rightMargin: 16; spacing: 12

                // Sharp 18x18 checkbox with 300ms scale(0->1) accent fill (Reviewed.dc.html)
                Rectangle {
                    Layout.preferredWidth: 18; Layout.preferredHeight: 18
                    color: "transparent"
                    border.width: 1
                    border.color: rRow.isDone ? Theme.accent : Theme.faint
                    Behavior on border.color { ColorAnimation { duration: 200 * Theme.motion } }
                    Rectangle {
                        anchors.fill: parent; anchors.margins: 3; color: Theme.accent
                        scale: rRow.isDone ? 1 : 0
                        opacity: rRow.isDone ? 1 : 0
                        Behavior on scale {
                            NumberAnimation {
                                duration: 300 * Theme.motion
                                easing.type: Easing.Bezier
                                easing.bezierCurve: [0.2, 0.8, 0.1, 1, 1, 1]
                            }
                        }
                        Behavior on opacity { NumberAnimation { duration: 200 * Theme.motion } }
                    }
                    HoverHandler { cursorShape: Qt.PointingHandCursor }
                    TapHandler { onTapped: page.toggleReviewed(rRow.modelData) }
                }

                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 3
                    Text {
                        Layout.fillWidth: true
                        text: rRow.modelData.title || qsTr("Untitled")
                        color: rRow.isDone ? Theme.label : Theme.bodyText
                        font.family: Theme.body; font.pixelSize: 14
                        elide: Text.ElideRight
                    }
                    Text {
                        visible: !rRow.isDone
                        Layout.fillWidth: true
                        readonly property string dom: String(rRow.modelData.domain || "")
                        readonly property string cat: String(rRow.modelData.category || Theme.typeName(rRow.modelData.typeId || ""))
                        text: Theme.caps(dom.length > 0 ? (dom + " · " + cat) : cat)
                        color: Theme.label; font.family: Theme.mono; font.pixelSize: 10
                        elide: Text.ElideRight
                    }
                }
            }
            HoverHandler { id: rHov; cursorShape: Qt.PointingHandCursor }
            TapHandler { onTapped: page.selectedId = rRow.modelData.id }
        }
    }
}
