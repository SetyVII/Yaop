import QtQuick
import QtQuick.Dialogs
import QtQuick.Controls
import QtQuick.Layouts
import YAOP.Core 1.0
import "."
import "editor"

// EditorPage: the open object. Title + cover header, live block list, rail.
// All structure goes through ws.openObject() (EditorModel, C++).

Item {
    id: editPage

    property string objectId: ""
    property string pendingBlockId: ""
    property var appWindow: null
    property var session: null

    signal closeRequested()
    signal scrollHandled()

    function scrollToPendingBlock() {
        if (pendingBlockId.length > 0 && blocks) {
            const target = pendingBlockId
            Qt.callLater(function() {
                blocks.scrollToBlock(target)
                editPage.scrollHandled()
            })
        }
    }

    // Dock the rail only when there is room for it (the side pane never has).
    property bool narrowRail: Theme.narrow

    onObjectIdChanged: {
        Privacy.noteRevealed = false
        if (session) {
            const old = session.objectId
            session = null
            if (!appWindow || !appWindow.objectShownElsewhere(old, editPage))
                ws.closeObject(old)
        }
        if (objectId.length > 0) {
            session = ws.openObject(objectId)
            scrollToPendingBlock()
        }
    }
    onPendingBlockIdChanged: scrollToPendingBlock()

    // Inserts imported media ({assetId, name, kind}) as blocks at the end of the object.
    function insertMediaItems(items) {
        if (!session)
            return
        for (let i = 0; i < items.length; ++i) {
            const it = items[i]
            const row = session.blockCount - 1
            if (it.kind === "pdf")
                session.insertPdfBlockAfter(row, it.assetId, it.name)
            else if (it.kind === "audio")
                session.insertAudioBlockAfter(row, it.assetId, 0)
            else
                session.insertImageBlockAfter(row, it.assetId, "")
        }
        if (items.length === 0)
            appWindow.showToast(qsTr("Nothing to paste"))
    }

    function pickMedia(kind) {
        const filter = kind === "image" ? "Images (*.png *.jpg *.jpeg *.webp *.gif)"
                    : kind === "audio" ? "Audio (*.mp3 *.m4a *.wav *.ogg *.flac *.aac)"
                    : "PDF (*.pdf)"
        mediaPicker.nameFilters = [filter]
        mediaPicker.open()
    }

    property var pendingTrashBacklinks: []
    function requestTrashCurrent() {
        if (!session)
            return
        pendingTrashBacklinks = ws.backlinksFor(session.objectId)
        trashPopup.open()
    }

    clip: true
    property bool railOpen: false
    // One text column for title, breadcrumb and blocks (spec §6: 640 px max, centred).
    readonly property real mainW: width - (editPage.narrowRail ? 0 : Theme.railWidth)
    readonly property real colW: Math.min(Theme.editorWidth, mainW - 2 * Theme.pagePad)
    readonly property real colMargin: Math.max(Theme.pagePad, (mainW - Theme.editorWidth) / 2)

    // file picker (media insert)
    FileDialog {
        id: mediaPicker
        title: qsTr("Pick a file")
        nameFilters: ["*.*"]
        onAccepted: {
            const path = selectedFile.toString().replace("file://", "")
            if (session) {
                const row = editPage.session.blockCount - 1
                const assetId = ws.importFileAsAsset(path)
                if (assetId.length === 0) {
                    appWindow.showToast(qsTr("Could not import file"))
                    return
                }
                const name = decodeURIComponent(selectedFile.toString().split("/").pop())
                const kind = name.toLowerCase().endsWith(".pdf") ? "pdf"
                           : /\.(mp3|m4a|wav|ogg|flac|aac)$/.test(name.toLowerCase()) ? "audio"
                           : "image"
                if (kind === "pdf")
                    session.insertPdfBlockAfter(row, assetId, name)
                else if (kind === "audio")
                    session.insertAudioBlockAfter(row, assetId, 0)
                else
                    session.insertImageBlockAfter(row, assetId, "")
            }
        }
    }

    RowLayout {
        anchors.fill: parent
        spacing: 0

        // ---------------- main column ----------------
        Item {
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true

            Flickable {
                id: headFlick
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.top: parent.top
                height: 156
                contentWidth: width
                clip: true
                visible: coverImage.visible
                interactive: false

                // cover
                PrivateImage {
                    inObject: true
                    id: coverImage
                    anchors.fill: parent
                    visible: session && session.coverAssetId.length > 0
                    source: visible ? "file://" + ws.assetLocalPath(session.coverAssetId) : ""
                    sourceSize: Qt.size(1600, 320)
                    fillMode: Image.PreserveAspectCrop
                    asynchronous: true
                    cache: true
                    opacity: 0.5

                    Rectangle {
                        anchors.fill: parent
                        gradient: Gradient {
                            GradientStop { position: 0.0; color: "#00000000" }
                            GradientStop { position: 1.0; color: Theme.bg }
                        }
                    }
                }
            }

            Rectangle {
                anchors.top: headFlick.visible ? headFlick.bottom : parent.top
                anchors.topMargin: headFlick.visible ? 0 : 10
                anchors.bottom: parent.bottom
                anchors.left: parent.left
                anchors.right: parent.right
                color: "transparent"

                ColumnLayout {
                    anchors.fill: parent
                    spacing: 0

                    // object type breadcrumb
                    Text {
                        id: typeIdLabel
                        Layout.leftMargin: editPage.colMargin
                        Layout.topMargin: 14
                        visible: !!session
                        text: {
                            const info = ws.typeInfo(session ? session.typeId : "")
                            const label = info.name !== undefined ? info.name.toUpperCase()
                                        : Theme.typeLabel(session ? session.typeId : "")
                            return "OBJECT / " + label
                        }
                        color: Theme.muted
                        font.family: Theme.mono
                        font.pixelSize: 11
                        font.letterSpacing: 1.6
                        font.weight: Font.Medium
                    }

                    // Title
                    Rectangle {
                        Layout.fillWidth: true
                        Layout.preferredHeight: 64
                        color: "transparent"

                        RowLayout {
                            id: titleRow
                            anchors.fill: parent
                            anchors.leftMargin: editPage.colMargin
                            anchors.rightMargin: editPage.colMargin
                            spacing: 12

                            TextInput {
                                id: titleInput
                                objectName: "titleInput"
                                Layout.fillWidth: true
                                Layout.preferredWidth: 1   // shares the row instead of claiming the whole title
                                Layout.preferredHeight: 40
                                autoScroll: activeFocus    // unfocused, a long title shows its start
                                text: session ? session.title : ""
                                color: Theme.text
                                selectionColor: Qt.rgba(0.75, 0.89, 1, 0.38)
                                selectedTextColor: Theme.text
                                font.family: Theme.display
                                font.pixelSize: 28
                                font.weight: Font.DemiBold
                                font.letterSpacing: -0.4
                                selectByMouse: true
                                clip: true
                                verticalAlignment: TextInput.AlignVCenter

                                onTextEdited: if (session) session.title = text // the Q_PROPERTY setter (setTitle is not invokable)
                            }

                            Button {
                                visible: !Theme.compact && editPage.width >= 620
                                text: qsTr("COVER")
                                small: true
                                onClicked: webCoverPopup.open()
                            }
                            Button {
                                visible: !Theme.compact && editPage.width >= 620
                                text: qsTr("SHARE")
                                small: true
                                onClicked: appWindow.showToast(qsTr("Link copied."))
                            }
                            Button {
                                text: qsTr("TRASH")
                                small: true
                                onClicked: editPage.requestTrashCurrent()
                            }
                            IconButton {
                                // outline / backlinks rail: docked when wide, on demand when narrow
                                visible: editPage.narrowRail
                                icon: "adjustments-horizontal"
                                onClicked: editPage.railOpen = !editPage.railOpen
                            }
                            IconButton {
                                // "Show images in this note": only while privacy covers notes
                                visible: Privacy.active && Privacy.store.scope === 0
                                icon: Privacy.noteRevealed ? "eye" : "eye-off"
                                active: !Privacy.noteRevealed
                                onClicked: Privacy.noteRevealed = !Privacy.noteRevealed
                            }
                            IconButton {
                                icon: "asterisk"
                                onClicked: if (session) ws.togglePinned(session.objectId)
                            }
                            IconButton {
                                icon: "arrow-back"
                                onClicked: editPage.closeRequested()
                            }
                        }
                    }

                    // tasks: status and due day right under the title
                    TaskBar {
                        visible: !!editPage.session && editPage.session.typeId === "task"
                        Layout.fillWidth: true
                        Layout.leftMargin: editPage.colMargin
                        Layout.rightMargin: editPage.colMargin
                        Layout.bottomMargin: 12
                        objectId: visible ? editPage.session.objectId : ""
                    }

                    Rectangle {
                        Layout.fillWidth: true
                        Layout.preferredHeight: 1
                        Layout.leftMargin: editPage.colMargin
                        Layout.rightMargin: editPage.colMargin
                        color: Theme.line
                    }

                    // object cover banner (web cover URL or local cover asset, for non-image objects)
                    Rectangle {
                        visible: !!editPage.session
                                 && editPage.session.typeId !== "image"
                                 && (editPage.session.coverAssetId.length > 0 || editPage.session.thumbnailUrl.length > 0)
                        Layout.fillWidth: true
                        Layout.preferredHeight: 168
                        Layout.leftMargin: editPage.colMargin
                        Layout.rightMargin: editPage.colMargin
                        Layout.topMargin: 10
                        radius: Theme.radius
                        color: Theme.panel
                        clip: true

                        PrivateImage {
                            inObject: true
                            anchors.fill: parent
                            fillMode: Image.PreserveAspectCrop
                            sourceSize: Qt.size(1280, 400)
                            asynchronous: true
                            cache: true
                            source: {
                                if (!editPage.session)
                                    return ""
                                if (editPage.session.coverAssetId.length > 0) {
                                    const p = ws.assetLocalPath(editPage.session.coverAssetId)
                                    if (p.length > 0)
                                        return "file://" + p
                                }
                                return editPage.session.thumbnailUrl
                            }
                        }
                    }

                    // media object strip: big preview for image objects, player for audio
                    Item {
                        visible: !!editPage.session && editPage.session.assetId.length > 0
                                 && (editPage.session.typeId === "image" || editPage.session.typeId === "audio")
                        Layout.fillWidth: true
                        Layout.preferredHeight: editPage.session && editPage.session.typeId === "image" ? 240 : 64
                        Layout.leftMargin: editPage.colMargin
                        Layout.rightMargin: editPage.colMargin

                        AudioPlayback {
                            id: objPlayback
                            assetId: editPage.session ? editPage.session.assetId : ""
                            path: editPage.session ? ws.assetLocalPath(editPage.session.assetId) : ""
                            durationSeconds: editPage.session && editPage.session.assetId.length > 0
                                             ? ws.assetDurationSeconds(editPage.session.assetId) : 0
                        }

                        Rectangle {
                            visible: editPage.session && editPage.session.typeId === "image"
                            anchors.fill: parent
                            color: Theme.panel
                            border.color: Theme.line
                            border.width: 1
                            clip: true
                            PrivateImage {
                                inObject: true
                                id: objCoverImg
                                anchors.centerIn: parent
                                width: Math.min(parent.width - 8, implicitWidth)
                                height: Math.min(parent.height - 8, implicitHeight)
                                visible: status === Image.Ready
                                source: {
                                    if (!editPage.session || editPage.session.assetId.length === 0)
                                        return ""
                                    const p = ws.assetLocalPath(editPage.session.assetId)
                                    return p.length > 0 ? "file://" + p : ""
                                }
                                sourceSize: Qt.size(1600, 1600)
                                fillMode: Image.PreserveAspectFit
                                asynchronous: true
                                cache: true
                                smooth: true
                            }
                            Text {
                                anchors.centerIn: parent
                                visible: editPage.session && ws.assetLocalPath(editPage.session.assetId).length === 0
                                text: qsTr("downloading…")
                                color: Theme.faint
                                font.family: Theme.mono
                                font.pixelSize: 11
                            }
                        }

                        Rectangle {
                            visible: editPage.session && editPage.session.typeId === "audio"
                            anchors.fill: parent
                            color: Theme.panel
                            border.color: Theme.line
                            border.width: 1

                            RowLayout {
                                anchors.fill: parent
                                anchors.margins: 10
                                spacing: 12

                                Rectangle {
                                    Layout.preferredWidth: 34
                                    Layout.preferredHeight: 34
                                    color: playObjMouse.hovered ? Theme.panel3 : Theme.panel2
                                    border.color: Theme.line2
                                    Text {
                                        anchors.centerIn: parent
                                        text: objPlayback.playing ? "◼" : "▶"
                                        color: Theme.ice
                                        font.family: Theme.mono
                                        font.pixelSize: 13
                                    }
                                    HoverHandler { id: playObjMouse; cursorShape: Qt.PointingHandCursor }
                                    TapHandler {
                                        onTapped: {
                                            if (objPlayback.playing) objPlayback.stop()
                                            else objPlayback.play()
                                        }
                                    }
                                }
                                ColumnLayout {
                                    spacing: 3
                                    Layout.fillWidth: true
                                    Text {
                                        text: qsTr("AUDIO")
                                        color: Theme.faint
                                        font.family: Theme.mono
                                        font.pixelSize: 11
                                        font.letterSpacing: 1.2
                                    }
                                    Text {
                                        text: {
                                            const s = Math.max(0, Math.round(objPlayback.durationSeconds))
                                            return Math.floor(s / 60) + ":" + String(s % 60).padStart(2, "0")
                                        }
                                        color: Theme.muted
                                        font.family: Theme.mono
                                        font.pixelSize: 11
                                    }
                                }
                            }
                        }
                    }

                    HabitPanel {
                        Layout.fillWidth: true
                        Layout.leftMargin: editPage.colMargin
                        Layout.rightMargin: editPage.colMargin
                        habitId: (editPage.session && ws.objectSummary(editPage.session.objectId).typeId === "habit") ? editPage.session.objectId : ""
                    }

                    // Blocks
                    Item {
                        Layout.fillWidth: true
                        Layout.fillHeight: true
                        Layout.leftMargin: 2
                        Layout.rightMargin: 2

                        Item {
                            id: blocksHolder
                            anchors.fill: parent
                            anchors.leftMargin: 0

                            // centered editorial column
                            property real contentW: editPage.colW

                            Item {
                                id: blockColumn
                                x: editPage.colMargin - 2
                                width: parent.contentW
                                height: parent.height

                                BlocksView {
                                    id: blocks
                                    objectName: "blocks"
                                    session: editPage.session
                                    anchors.fill: parent
                                    onOpenObjectRequested: objectId => editPage.appWindow.openObject(objectId)
                                    onMediaPasteRequested: editPage.insertMediaItems(ws.importClipboardMedia())
                                    onToastRequested: message => { if (editPage.appWindow) editPage.appWindow.showToast(message) }
                                }

                                // Drag files in from the file manager or another app.
                                DropArea {
                                    anchors.fill: parent
                                    keys: ["text/uri-list"]
                                    onDropped: drop => {
                                        if (drop.hasUrls)
                                            editPage.insertMediaItems(ws.importMediaUrls(drop.urls))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---------------- rail ----------------
        Item {
            id: railHost
            visible: !!session
            // Wide windows dock the rail; narrow ones give it no width and float it over the page.
            Layout.preferredWidth: editPage.narrowRail ? 0 : Theme.railWidth
            Layout.fillHeight: true
            z: 20

            Rectangle {
                visible: !editPage.narrowRail || editPage.railOpen
                width: Theme.railWidth
                height: parent.height
                x: editPage.narrowRail ? -Theme.railWidth : 0
                color: Theme.bg2

                Rectangle {
                    width: 1
                    height: parent.height
                    color: Theme.line
                }

                EditorRail {
                    anchors.fill: parent
                    anchors.leftMargin: 1
                    anchors.rightMargin: 6
                    anchors.topMargin: 8
                    anchors.bottomMargin: 8
                    session: editPage.session
                    workspace: ws
                    onScrollToBlockRequested: blockId => {
                        // focus the target block; the delegate will scroll into view
                        if (editPage.session)
                            editPage.session.forceFocus(blockId, 0)
                    }
                    onOpenObjectRequested: objectId => editPage.appWindow.openObject(objectId)
                    onOpenObjectAtBlockRequested: (objectId, blockId) => editPage.appWindow.openObjectAtBlock(objectId, blockId)
                }
            }
        }
    }

    Popup {
        id: webCoverPopup
        anchors.centerIn: Overlay.overlay
        width: Math.min(440, editPage.width - 40)
        padding: 18
        modal: true
        closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
        onOpened: {
            webCoverInput.text = ""
            webCoverInput.forceActiveFocus()
        }

        background: Rectangle {
            radius: Theme.radiusLg
            color: Theme.sheet
            border.color: Theme.line2
            border.width: 1
        }

        contentItem: ColumnLayout {
            spacing: 12
            Text {
                text: qsTr("SET WEB COVER")
                color: Theme.text
                font.family: Theme.mono
                font.pixelSize: 12
                font.letterSpacing: 1.4
            }
            Rectangle {
                Layout.fillWidth: true
                Layout.preferredHeight: 36
                radius: Theme.radius
                color: Theme.panel
                border.color: webCoverInput.activeFocus ? Theme.ice : Theme.line
                border.width: 1
                TextInput {
                    id: webCoverInput
                    anchors.fill: parent
                    anchors.leftMargin: 10
                    anchors.rightMargin: 10
                    verticalAlignment: TextInput.AlignVCenter
                    color: Theme.text
                    font.family: Theme.body
                    font.pixelSize: 13
                    clip: true
                    onAccepted: setWebCoverBtn.applyCover()
                    Text {
                        anchors.verticalCenter: parent.verticalCenter
                        visible: webCoverInput.length === 0
                        text: qsTr("https://… image URL")
                        color: Theme.faint
                        font: webCoverInput.font
                    }
                }
            }
            RowLayout {
                Layout.fillWidth: true
                spacing: 8
                Item { Layout.fillWidth: true }
                Button { text: qsTr("CANCEL"); small: true; onClicked: webCoverPopup.close() }
                Button {
                    id: setWebCoverBtn
                    text: qsTr("SET COVER")
                    small: true
                    enabled: webCoverInput.text.trim().length > 0
                    function applyCover() {
                        const u = webCoverInput.text.trim()
                        if (u.length === 0 || !editPage.session)
                            return
                        ws.setWebCoverUrl(editPage.session.objectId, u)
                        webCoverPopup.close()
                        if (editPage.appWindow)
                            editPage.appWindow.showToast(qsTr("Fetching web cover…"))
                    }
                    onClicked: applyCover()
                }
            }
        }
    }

    Popup {
        id: trashPopup
        anchors.centerIn: Overlay.overlay
        width: Math.min(440, editPage.width - 40)
        padding: 20
        modal: true
        closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside

        background: Rectangle {
            radius: Theme.radiusLg
            color: Theme.sheet
            border.color: Theme.line2
            border.width: 1
        }

        contentItem: ColumnLayout {
            spacing: 12

            Text {
                text: qsTr("MOVE TO TRASH")
                color: Theme.text
                font.family: Theme.mono
                font.pixelSize: 12
                font.letterSpacing: 1.4
            }

            Text {
                Layout.fillWidth: true
                wrapMode: Text.Wrap
                text: editPage.pendingTrashBacklinks.length > 0
                      ? qsTr("This object is referenced in %1 place(s). Moving it to trash will hide those links until restored:").arg(editPage.pendingTrashBacklinks.length)
                      : qsTr("Move \"%1\" to trash? It can be restored from Trash within 30 days.").arg(editPage.session ? editPage.session.plainTitle : "")
                color: Theme.dim
                font.family: Theme.body
                font.pixelSize: 13
            }

            Repeater {
                model: editPage.pendingTrashBacklinks.slice(0, 6)
                delegate: Rectangle {
                    required property var modelData
                    Layout.fillWidth: true
                    implicitHeight: 34
                    radius: Theme.radius
                    color: Theme.panel
                    border.color: Theme.line
                    border.width: 1

                    RowLayout {
                        anchors.fill: parent
                        anchors.leftMargin: 10
                        anchors.rightMargin: 10
                        spacing: 8
                        Text {
                            text: modelData.title !== undefined ? modelData.title : ""
                            color: Theme.text
                            font.family: Theme.body
                            font.pixelSize: 12
                            font.weight: Font.Medium
                            elide: Text.ElideRight
                            Layout.fillWidth: true
                        }
                        Text {
                            text: modelData.snippet !== undefined ? modelData.snippet : ""
                            color: Theme.muted
                            font.family: Theme.body
                            font.pixelSize: 11
                            elide: Text.ElideRight
                            Layout.maximumWidth: 180
                        }
                    }
                }
            }

            RowLayout {
                Layout.fillWidth: true
                spacing: 10
                Item { Layout.fillWidth: true }
                Button {
                    text: qsTr("CANCEL")
                    small: true
                    onClicked: trashPopup.close()
                }
                Button {
                    text: qsTr("MOVE TO TRASH")
                    small: true
                    onClicked: {
                        trashPopup.close()
                        if (editPage.session) {
                            const oid = editPage.session.objectId
                            ws.deleteObject(oid)
                            editPage.closeRequested()
                            if (editPage.appWindow)
                                editPage.appWindow.showToast(qsTr("Moved to trash"))
                        }
                    }
                }
            }
        }
    }
}
