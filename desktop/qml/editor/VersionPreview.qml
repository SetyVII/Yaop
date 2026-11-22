import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../"

// One saved version of an object (workspace_history.cpp): its blocks as they were, each marked
// changed or removed against today, with "Restore block" on those and "Restore this version" for
// the whole object. Restoring keeps the current state as a version first, so it can be undone.
Popup {
    id: pop

    property var workspace: null
    property var session: null
    property var version: ({})
    property var blocks: []
    property bool confirmWhole: false
    signal restored(bool whole)

    readonly property int changedCount: blocks.filter(b => b.state !== "same").length

    function show(v) {
        version = v
        confirmWhole = false
        blocks = workspace && session ? workspace.versionBlocks(session.objectId, v.id) : []
        open()
    }
    function restore(blockId) {
        if (!workspace || !session) return
        if (workspace.restoreVersion(session.objectId, version.id, blockId || "")) {
            restored(!blockId)
            if (!blockId) close()
            else blocks = workspace.versionBlocks(session.objectId, version.id)
        }
    }

    parent: Overlay.overlay
    x: (parent.width - width) / 2
    y: Math.max(24, (parent.height - height) / 2)
    width: Math.min(760, parent.width - 64)
    height: Math.min(680, parent.height - 48)
    padding: 0
    modal: true
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
    Overlay.modal: Rectangle { color: Theme.scrim }
    enter: Transition {
        NumberAnimation { property: "opacity"; from: 0; to: 1; duration: Theme.fast }
        NumberAnimation { property: "scale"; from: 0.97; to: 1; duration: Theme.fast; easing.type: Easing.OutCubic }
    }
    exit: Transition { NumberAnimation { property: "opacity"; from: 1; to: 0; duration: Theme.fast } }
    background: Rectangle { radius: Theme.radiusLg; color: Theme.sheet; border.color: Theme.line2; border.width: 1 }

    onOpened: {
        const first = blocks.findIndex(b => b.state !== "same")
        list.currentIndex = first
        list.forceActiveFocus()
    }

    contentItem: ColumnLayout {
        spacing: 0

        RowLayout {
            Layout.fillWidth: true
            Layout.margins: 18
            Layout.bottomMargin: 12
            spacing: 12
            ColumnLayout {
                Layout.fillWidth: true
                spacing: 3
                Text {
                    text: pop.version.createdAt ? Qt.formatDateTime(new Date(pop.version.createdAt), "dddd d MMMM yyyy, HH:mm") : ""
                    color: Theme.text
                    font.family: Theme.display; font.pixelSize: 20; font.weight: Font.DemiBold
                }
                Text {
                    text: (pop.version.title || "") + " · "
                          + (pop.changedCount === 0 ? qsTr("same as now")
                             : pop.changedCount === 1 ? qsTr("1 block differs from now")
                             : qsTr("%1 blocks differ from now").arg(pop.changedCount))
                    color: Theme.muted
                    font.family: Theme.mono; font.pixelSize: 11
                    elide: Text.ElideRight
                    Layout.fillWidth: true
                }
            }
            Button {
                id: restoreBtn
                primary: pop.confirmWhole
                small: true
                text: pop.confirmWhole ? qsTr("Replace the whole object") : qsTr("Restore this version")
                onClicked: pop.confirmWhole ? pop.restore("") : (pop.confirmWhole = true)
            }
            IconButton { icon: "x"; tip: qsTr("Close (Esc)"); onClicked: pop.close() }
        }
        Text {
            visible: pop.confirmWhole
            Layout.fillWidth: true
            Layout.leftMargin: 18
            Layout.rightMargin: 18
            Layout.bottomMargin: 10
            text: qsTr("The object as it is now is saved as a version first, so you can come back to it.")
            color: Theme.dim
            font.family: Theme.body; font.pixelSize: 12
            wrapMode: Text.WordWrap
        }

        Rectangle { Layout.fillWidth: true; height: 1; color: Theme.line }

        ListView {
            id: list
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            model: pop.blocks
            spacing: 2
            topMargin: 12
            bottomMargin: 12
            boundsBehavior: Flickable.StopAtBounds
            keyNavigationEnabled: false
            ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }
            // ↑/↓ jump between the blocks that differ, Enter restores the selected one
            function step(dir) {
                for (let i = currentIndex + dir; i >= 0 && i < pop.blocks.length; i += dir)
                    if (pop.blocks[i].state !== "same") { currentIndex = i; positionViewAtIndex(i, ListView.Contain); return }
            }
            Keys.onUpPressed: step(-1)
            Keys.onDownPressed: step(1)
            Keys.onReturnPressed: if (currentIndex >= 0 && pop.blocks[currentIndex].state !== "same") pop.restore(pop.blocks[currentIndex].id)
            Keys.onEnterPressed: if (currentIndex >= 0 && pop.blocks[currentIndex].state !== "same") pop.restore(pop.blocks[currentIndex].id)
            delegate: Item {
                id: blk
                required property var modelData
                readonly property bool differs: modelData.state !== "same"
                width: list.width
                height: Math.max(30, body.implicitHeight + 12)

                Rectangle {
                    anchors.fill: parent
                    anchors.leftMargin: 12; anchors.rightMargin: 12
                    color: (blkHov.hovered || blk.ListView.isCurrentItem) && blk.differs ? Theme.panel2 : "transparent"
                    Behavior on color { ColorAnimation { duration: Theme.fast } }
                }
                // left bar: changed = accent, removed = danger
                Rectangle {
                    visible: blk.differs
                    x: 12; width: 2; height: parent.height
                    color: blk.modelData.state === "removed" ? Theme.danger : Theme.accent
                }
                Text {
                    id: body
                    x: 26
                    width: parent.width - 26 - (restoreBlk.visible ? restoreBlk.width + 30 : 24)
                    anchors.verticalCenter: parent.verticalCenter
                    text: blk.modelData.text.length > 0 ? blk.modelData.text : " "
                    wrapMode: Text.Wrap
                    color: blk.differs ? Theme.text : Theme.muted
                    font.family: blk.modelData.type === "code" ? Theme.mono : Theme.body
                    font.pixelSize: blk.modelData.type === "heading" ? (blk.modelData.level <= 1 ? 20 : 16) : 14
                    font.weight: blk.modelData.type === "heading" ? Font.DemiBold : Font.Normal
                }
                Button {
                    id: restoreBlk
                    visible: blk.differs && (blkHov.hovered || blk.ListView.isCurrentItem)
                    anchors.right: parent.right
                    anchors.rightMargin: 18
                    anchors.verticalCenter: parent.verticalCenter
                    small: true
                    text: blk.modelData.state === "removed" ? qsTr("Bring back") : qsTr("Restore block")
                    onClicked: pop.restore(blk.modelData.id)
                }
                HoverHandler { id: blkHov }
            }
        }

        Rectangle { Layout.fillWidth: true; height: 1; color: Theme.line }
        RowLayout {
            Layout.fillWidth: true
            Layout.margins: 10
            Layout.leftMargin: 18
            spacing: 14
            Row {
                spacing: 6
                Rectangle { anchors.verticalCenter: parent.verticalCenter; width: 8; height: 8; color: Theme.accent }
                Text { text: qsTr("changed since"); color: Theme.faint; font.family: Theme.mono; font.pixelSize: 11 }
            }
            Row {
                spacing: 6
                Rectangle { anchors.verticalCenter: parent.verticalCenter; width: 8; height: 8; color: Theme.danger }
                Text { text: qsTr("removed since"); color: Theme.faint; font.family: Theme.mono; font.pixelSize: 11 }
            }
            Item { Layout.fillWidth: true }
            Text { text: qsTr("↑↓ changed blocks · Enter restores one · Esc close"); color: Theme.faint; font.family: Theme.mono; font.pixelSize: 11 }
        }
    }
}
