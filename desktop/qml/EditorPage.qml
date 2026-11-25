import QtQuick
import QtQuick.Dialogs
import QtQuick.Controls
import QtQuick.Layouts
import QtCore
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
    property bool inSidePane: false

    Settings {
        id: layoutPrefs
        category: "layout"
        property string webViewMode: "embed"
        property bool webSidePanelOpen: true
        property real webSidePanelWidth: 420
        property bool notesRailVisible: true
        property bool webDetailsCollapsed: false
    }

    readonly property bool isWebObject: !!session && session.typeId === "web"
    readonly property bool hasWebUrl: isWebObject && session.url.length > 0
    readonly property bool isImageObject: !!session && session.typeId === "image" && session.assetId.length > 0 && !inSidePane && Theme.noir
    readonly property bool isBookLayout: !!session && session.typeId === "book" && !inSidePane && width >= 960 && Theme.noir
    readonly property bool isPersonObject: !!session && session.typeId === "person"
    readonly property bool isProjectObject: !!session && session.typeId === "project"
    // Daily note: header with weekday, long date and day navigation instead of the title field
    readonly property string journalIso: {
        if (!session || session.typeId !== "journal" || !ws) return ""
        const d = ws.objectSummary(session.objectId).journalDate || ""
        return /^\d{4}-\d{2}-\d{2}$/.test(d) ? d : ""
    }
    readonly property bool isJournalObject: journalIso.length > 0
    readonly property bool focusActive: !!(appWindow && appWindow.focusMode) && !inSidePane
    readonly property bool webSplitActive: hasWebUrl && !inSidePane && width >= 680

    // Books prev/next + collections + Project progress (Book.dc.html, TypeHeroes.dc.html)
    readonly property var allBooksList: {
        tagRev
        return (isBookLayout && ws) ? ws.objectsByType("book", 0) : []
    }
    readonly property int currentBookIndex: {
        if (!session) return -1
        for (let i = 0; i < allBooksList.length; i++)
            if (allBooksList[i].id === session.objectId) return i
        return -1
    }
    readonly property string bookStatusLabel: {
        tagRev
        if (!isBookLayout || !session || !ws) return ""
        const raw = ws.fieldText(session.objectId, "status")
        if (!raw) return ""
        const opt = ws.resolveOption("book", "status", raw)
        return opt && opt.label ? opt.label : raw
    }
    readonly property var objectCollections: {
        tagRev
        return (session && ws) ? ws.collectionsForObject(session.objectId) : []
    }
    readonly property var projProgress: {
        tagRev
        return (isProjectObject && session && ws) ? ws.projectProgress(session.objectId) : null
    }
    readonly property bool embedMode: webSplitActive && layoutPrefs.webViewMode === "embed"
    readonly property bool webSidePanelShown: embedMode && layoutPrefs.webSidePanelOpen
    readonly property bool webFullPanel: embedMode && !layoutPrefs.webSidePanelOpen
    readonly property real clampedWebSideW: Math.max(300, Math.min(Math.max(320, width - 360), layoutPrefs.webSidePanelWidth))
    property bool resizingWebSide: false

    // Image viewer state (Image.dc.html)
    property real imageZoom: 1.0
    property bool imageNotesOpen: true
    readonly property var allImagesList: {
        tagRev
        return (isImageObject && ws) ? ws.objectsByType("image", 0) : []
    }
    readonly property int currentImageIndex: {
        if (!session) return -1
        for (let i = 0; i < allImagesList.length; i++)
            if (allImagesList[i].id === session.objectId) return i
        return -1
    }

    // Person monogram initials (Person.dc.html)
    function personInitials(name) {
        const parts = String(name || "").trim().split(/\s+/).filter(p => p.length > 0)
        if (parts.length === 0) return "?"
        if (parts.length === 1) return parts[0].substring(0, 2).toUpperCase()
        return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase()
    }

    property int tagRev: 0
    property var tagBacklinks: []
    function refreshTagBacklinks() {
        if (ws && session && session.typeId === "tag")
            tagBacklinks = ws.backlinksFor(session.objectId)
        else
            tagBacklinks = []
    }
    Connections {
        target: ws
        function onObjectListChanged() { editPage.tagRev++; editPage.refreshTagBacklinks() }
        function onObjectPropertiesChanged(id) { editPage.tagRev++; if (session && session.typeId === "tag") editPage.refreshTagBacklinks() }
    }

    // Leaving the editor for another page or tab (objectId becomes "") keeps the last object's
    // session and its blocks alive while the page is hidden, so coming back to it is instant
    // instead of rebuilding every block. It is closed when another object replaces it, or
    // explicitly with release() (closing, trashing).
    function release() {
        if (!session) return
        const old = session.objectId
        session = null
        if (!appWindow || !appWindow.objectShownElsewhere(old, editPage))
            ws.closeObject(old)
    }
    onObjectIdChanged: {
        Privacy.noteRevealed = false
        if (objectId.length === 0)
            return
        if (session && session.objectId === objectId) {
            scrollToPendingBlock()
            return
        }
        release()
        session = ws.openObject(objectId)
        refreshTagBacklinks()
        scrollToPendingBlock()
    }
    onPendingBlockIdChanged: scrollToPendingBlock()

    // Where the caret goes when this page opens (docs/keyboard.md §4.1): an untitled object starts
    // in the title, any other one where you left it, else at the end of its first text block.
    function focusDefault() {
        if (!session) return
        if (session.title.length === 0) { titleInput.forceActiveFocus(); titleInput.end(); return }
        blocks.focusStart()
    }

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
    // Object menu (Capacities' "…"): everything you do to the object as a whole, keyboard-first.
    function openObjectMenu(anchor) {
        if (!session) return
        const a = anchor || (webSplitActive ? webMoreBtn : moreBtn)
        objectMenu.refresh() // read once on open, not on every save
        objectMenu.popup(a, a.width - objectMenu.width, a.height + 4)
    }
    function showVersion(i) {
        showRailTab(2)
        editorRail.openVersion(i)
    }
    function showRailTab(tab) {
        if (narrowRail) railOpen = true
        else layoutPrefs.notesRailVisible = true
        editorRail.activeTab = tab
    }
    Shortcut {
        sequence: "Ctrl+."
        enabled: editPage.visible && !!editPage.session
        onActivated: editPage.openObjectMenu()
    }

    function requestTrashCurrent() {
        if (!session)
            return
        pendingTrashBacklinks = ws.backlinksFor(session.objectId)
        trashPopup.open()
    }

    clip: true
    property bool railOpen: false
    readonly property bool showDockedRail: !editPage.isImageObject && !editPage.narrowRail && !editPage.embedMode && (!editPage.webSplitActive || layoutPrefs.notesRailVisible)
    // One text column for title, breadcrumb and blocks (680 px max, centred).
    readonly property real bookSideW: editPage.isBookLayout ? 300 : 0
    readonly property real mainW: editPage.webSidePanelShown ? editPage.clampedWebSideW
                                : (width - editPage.bookSideW - (editPage.showDockedRail ? Theme.railWidth : 0))
    readonly property real sidePad: editPage.webSidePanelShown ? 20 : Theme.pagePad
    readonly property real colW: editPage.webSidePanelShown
                                 ? Math.max(220, mainW - 2 * sidePad)
                                 : Math.min(Theme.editorWidth, mainW - 2 * sidePad)
    readonly property real colMargin: editPage.webSidePanelShown
                                      ? sidePad
                                      : Math.max(sidePad, (mainW - Theme.editorWidth) / 2)

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

    // ---------------- Dedicated Image Viewer + Notes split (Image.dc.html) ----------------
    RowLayout {
        visible: editPage.isImageObject
        anchors.fill: parent
        spacing: 0

        // Left #050505 viewer pane
        Rectangle {
            Layout.fillWidth: true
            Layout.fillHeight: true
            color: Theme.ink
            clip: true

            // Top viewer toolbar (56px)
            Rectangle {
                id: imgBar
                anchors.left: parent.left; anchors.right: parent.right; anchors.top: parent.top
                height: 56
                color: "transparent"
                Rectangle { anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom; height: 1; color: Theme.lineSubtle }

                RowLayout {
                    anchors.fill: parent; anchors.leftMargin: 16; anchors.rightMargin: 12; spacing: 6
                    IconButton { icon: "x"; onClicked: editPage.closeRequested(); ToolTip.visible: hovered; ToolTip.text: qsTr("Close viewer"); ToolTip.delay: 300 }
                    Text {
                        text: Theme.caps(qsTr("IMAGES · ESC CLOSES"))
                        color: Theme.label; font.family: Theme.mono; font.pixelSize: 11
                        Layout.leftMargin: 6
                    }
                    Item { Layout.fillWidth: true }
                    Text {
                        visible: editPage.allImagesList.length > 0 && editPage.currentImageIndex >= 0
                        text: Theme.caps((editPage.currentImageIndex + 1) + " / " + editPage.allImagesList.length)
                        color: Theme.muted; font.family: Theme.mono; font.pixelSize: 11
                        Layout.rightMargin: 6
                    }
                    IconButton {
                        icon: "chevron-left"
                        enabled: editPage.currentImageIndex > 0
                        opacity: enabled ? 1 : 0.35
                        onClicked: if (editPage.currentImageIndex > 0 && editPage.appWindow)
                            editPage.appWindow.openObject(editPage.allImagesList[editPage.currentImageIndex - 1].id)
                    }
                    IconButton {
                        icon: "chevron-right"
                        enabled: editPage.currentImageIndex >= 0 && editPage.currentImageIndex < editPage.allImagesList.length - 1
                        opacity: enabled ? 1 : 0.35
                        onClicked: if (editPage.currentImageIndex < editPage.allImagesList.length - 1 && editPage.appWindow)
                            editPage.appWindow.openObject(editPage.allImagesList[editPage.currentImageIndex + 1].id)
                    }
                    IconButton {
                        icon: "layout-sidebar-right"
                        active: editPage.imageNotesOpen
                        onClicked: editPage.imageNotesOpen = !editPage.imageNotesOpen
                        ToolTip.visible: hovered; ToolTip.text: qsTr("Toggle notes panel"); ToolTip.delay: 300
                    }
                }
            }

            // Zoomable image canvas
            Flickable {
                id: imgViewerFlick
                anchors.left: parent.left; anchors.right: parent.right
                anchors.top: imgBar.bottom; anchors.bottom: parent.bottom
                contentWidth: Math.max(width, viewerPic.width)
                contentHeight: Math.max(height, viewerPic.height)
                interactive: editPage.imageZoom > 1.0
                boundsBehavior: Flickable.StopAtBounds
                clip: true

                PrivateImage {
                    id: viewerPic
                    inObject: true
                    readonly property real natW: Math.max(1, implicitWidth)
                    readonly property real natH: Math.max(1, implicitHeight)
                    readonly property real fitScale: Math.min(1, (imgViewerFlick.width - 80) / natW, (imgViewerFlick.height - 120) / natH)
                    width: natW * fitScale * editPage.imageZoom
                    height: natH * fitScale * editPage.imageZoom
                    x: (imgViewerFlick.contentWidth - width) / 2
                    y: (imgViewerFlick.contentHeight - height) / 2
                    source: {
                        if (!editPage.isImageObject || !editPage.session || !ws) return ""
                        const p = ws.assetLocalPath(editPage.session.assetId)
                        return p.length > 0 ? "file://" + p : ""
                    }
                    sourceSize: Qt.size(2048, 2048)
                    fillMode: Image.PreserveAspectFit
                    smooth: true; mipmap: true; asynchronous: true; cache: true
                }
                WheelHandler {
                    onWheel: (ev) => {
                        const s = ev.angleDelta.y > 0 ? 1.15 : 1 / 1.15
                        editPage.imageZoom = Math.max(0.5, Math.min(3.0, editPage.imageZoom * s))
                    }
                }
            }

            // Floating bottom zoom control bar (Image.dc.html)
            Rectangle {
                anchors.horizontalCenter: parent.horizontalCenter
                anchors.bottom: parent.bottom; anchors.bottomMargin: 24
                implicitWidth: zoomRow.implicitWidth + 8; height: 44
                color: Theme.panel2; border.width: 1; border.color: Theme.line2
                Row {
                    id: zoomRow; anchors.centerIn: parent; spacing: 2
                    IconButton { icon: "zoom-out"; onClicked: editPage.imageZoom = Math.max(0.5, Math.round((editPage.imageZoom - 0.25) * 100) / 100) }
                    Text {
                        width: 54; height: 36
                        horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter
                        text: Math.round(editPage.imageZoom * 100) + "%"
                        color: Theme.bodyText; font.family: Theme.mono; font.pixelSize: 11
                    }
                    IconButton { icon: "zoom-in"; onClicked: editPage.imageZoom = Math.min(3.0, Math.round((editPage.imageZoom + 0.25) * 100) / 100) }
                    Rectangle { width: 1; height: 22; anchors.verticalCenter: parent.verticalCenter; color: Theme.line2 }
                    IconButton { icon: "arrows-maximize"; onClicked: editPage.imageZoom = 1.0; ToolTip.visible: hovered; ToolTip.text: qsTr("Fit (100%)"); ToolTip.delay: 300 }
                    IconButton {
                        icon: "external-link"
                        onClicked: if (editPage.session && ws) {
                            const p = ws.assetLocalPath(editPage.session.assetId)
                            if (p) Qt.openUrlExternally("file://" + p)
                        }
                        ToolTip.visible: hovered; ToolTip.text: qsTr("Open original"); ToolTip.delay: 300
                    }
                }
            }
        }

        // Right 440px Image Notes & Properties pane (Image.dc.html)
        Rectangle {
            visible: editPage.imageNotesOpen
            Layout.preferredWidth: 440
            Layout.fillHeight: true
            color: Theme.page
            Rectangle { anchors.top: parent.top; anchors.bottom: parent.bottom; anchors.left: parent.left; width: 1; color: Theme.line }

            Flickable {
                anchors.fill: parent
                contentWidth: width; contentHeight: imgNotesCol.implicitHeight + 80
                clip: true; boundsBehavior: Flickable.StopAtBounds
                ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }

                ColumnLayout {
                    id: imgNotesCol
                    x: 32; y: 32; width: parent.width - 64; spacing: 0

                    RowLayout {
                        Layout.fillWidth: true; spacing: 8
                        Rectangle {
                            implicitWidth: icRow.implicitWidth + 18; implicitHeight: 26
                            color: "transparent"; border.width: 1; border.color: Theme.line2
                            Row {
                                id: icRow; anchors.centerIn: parent; spacing: 7
                                TintedIcon { anchors.verticalCenter: parent.verticalCenter; name: "image"; size: 14; color: Theme.image }
                                Text { anchors.verticalCenter: parent.verticalCenter; text: qsTr("Image"); color: Theme.bodyText; font.family: Theme.body; font.pixelSize: 13 }
                            }
                        }
                        Item { Layout.fillWidth: true }
                        IconButton { icon: "trash"; onClicked: editPage.requestTrashCurrent() }
                    }

                    TextEdit {
                        Layout.fillWidth: true; Layout.topMargin: 16
                        wrapMode: TextEdit.WordWrap
                        text: editPage.session ? editPage.session.title : ""
                        color: Theme.text; font.family: Theme.display; font.pixelSize: 30; font.letterSpacing: -0.5
                        selectByMouse: true
                        onTextChanged: if (editPage.session && activeFocus && editPage.session.title !== text) editPage.session.title = text.replace(/[\r\n]+/g, " ")
                    }

                    TagRow {
                        Layout.fillWidth: true; Layout.topMargin: 14; Layout.bottomMargin: 14
                        objectId: editPage.isImageObject && editPage.session ? editPage.session.objectId : ""
                        onOpenTagRequested: (id) => { if (id && editPage.appWindow) editPage.appWindow.openObject(id) }
                    }

                    PropertyTable {
                        Layout.fillWidth: true
                        objectId: editPage.isImageObject && editPage.session ? editPage.session.objectId : ""
                        objectTypeId: "image"
                        labelWidth: 112
                        onOpenObjectRequested: (id) => { if (editPage.appWindow) editPage.appWindow.openObject(id) }
                    }

                    // File dimensions row
                    Rectangle {
                        readonly property size dims: (editPage.isImageObject && editPage.session && ws)
                                                     ? ws.imageFileSize(ws.assetLocalPath(editPage.session.assetId)) : Qt.size(0, 0)
                        visible: dims.width > 0
                        Layout.fillWidth: true; Layout.preferredHeight: 36
                        color: "transparent"
                        Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.lineSubtle }
                        RowLayout {
                            anchors.fill: parent; spacing: 12
                            Text { Layout.preferredWidth: 112; text: qsTr("File"); color: Theme.label; font.family: Theme.body; font.pixelSize: 13 }
                            Text { text: Theme.caps(parent.parent.dims.width + " × " + parent.parent.dims.height + " px"); color: Theme.dim; font.family: Theme.mono; font.pixelSize: 11 }
                        }
                    }

                    // Backlinks (shown right under properties so cover -> WebLink/Book/Note references are immediately clickable)
                    readonly property var imageBacklinks: {
                        editPage.tagRev
                        return (editPage.isImageObject && editPage.session && ws)
                               ? ws.backlinksFor(editPage.session.objectId) : []
                    }
                    Text {
                        visible: imgNotesCol.imageBacklinks.length > 0
                        Layout.topMargin: 16; Layout.bottomMargin: 6
                        text: Theme.caps(qsTr("REFERENCED BY · %1").arg(imgNotesCol.imageBacklinks.length))
                        color: Theme.label; font.family: Theme.mono; font.pixelSize: 11
                    }
                    Repeater {
                        model: imgNotesCol.imageBacklinks
                        delegate: Rectangle {
                            required property var modelData
                            Layout.fillWidth: true; implicitHeight: 40
                            color: iblHov.hovered ? Theme.panel2 : "transparent"
                            Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.lineSubtle }
                            RowLayout {
                                anchors.fill: parent; spacing: 8
                                TypeBadge { typeId: modelData.typeId || ""; size: 14 }
                                Text { Layout.fillWidth: true; text: modelData.title || qsTr("Untitled"); color: Theme.bodyText; font.family: Theme.body; font.pixelSize: 13; elide: Text.ElideRight }
                                Text { text: Theme.typeLabel(modelData.typeId || ""); color: Theme.label; font.family: Theme.mono; font.pixelSize: 10 }
                            }
                            HoverHandler { id: iblHov; cursorShape: Qt.PointingHandCursor }
                            TapHandler { onTapped: if (editPage.appWindow) editPage.appWindow.openObject(modelData.id) }
                        }
                    }

                    // Notes blocks
                    Text {
                        Layout.topMargin: 18; Layout.bottomMargin: 6
                        text: Theme.caps(qsTr("NOTES"))
                        color: Theme.label; font.family: Theme.mono; font.pixelSize: 11
                    }
                    Item {
                        Layout.fillWidth: true; Layout.preferredHeight: 220
                        BlocksView {
                            anchors.fill: parent
                            session: editPage.isImageObject ? editPage.session : null
                            onOpenObjectRequested: (id) => { if (editPage.appWindow) editPage.appWindow.openObject(id) }
                            onToastRequested: (msg) => { if (editPage.appWindow) editPage.appWindow.showToast(msg) }
                        }
                    }
                }
            }
        }
    }

    ColumnLayout {
        visible: !editPage.isImageObject
        anchors.fill: parent
        spacing: 0

        // ---------------- Web link top bar (Embed | Notes + side panel toggle) ----------------
        Rectangle {
            visible: editPage.webSplitActive
            Layout.fillWidth: true
            Layout.preferredHeight: 44
            color: Theme.bg2

            Item {
                anchors.fill: parent
                anchors.leftMargin: 12
                anchors.rightMargin: 10

                // Centered segmented switch: [ <> Embed | ≡ Notes ]
                Rectangle {
                    anchors.centerIn: parent
                    implicitWidth: segRow.implicitWidth + 6
                    implicitHeight: 30
                    radius: Theme.radius
                    color: Theme.panel
                    border.width: 1
                    border.color: Theme.line

                    Row {
                        id: segRow
                        anchors.centerIn: parent
                        spacing: 2

                        Rectangle {
                            id: embedBtn
                            implicitWidth: embedLblRow.implicitWidth + 18
                            implicitHeight: 24
                            radius: Theme.radiusSm
                            color: editPage.embedMode ? Theme.panel2 : (embedHover.hovered ? Theme.cardHover : "transparent")
                            border.width: editPage.embedMode ? 1 : 0
                            border.color: Theme.line2
                            Row {
                                id: embedLblRow
                                anchors.centerIn: parent
                                spacing: 6
                                TintedIcon {
                                    anchors.verticalCenter: parent.verticalCenter
                                    name: "code"
                                    size: 13
                                    color: editPage.embedMode ? Theme.text : Theme.muted
                                }
                                Text {
                                    anchors.verticalCenter: parent.verticalCenter
                                    text: qsTr("Embed")
                                    color: editPage.embedMode ? Theme.text : Theme.dim
                                    font.family: Theme.body
                                    font.pixelSize: 13
                                    font.weight: editPage.embedMode ? Font.Medium : Font.Normal
                                }
                            }
                            HoverHandler { id: embedHover; cursorShape: Qt.PointingHandCursor }
                            TapHandler { onTapped: layoutPrefs.webViewMode = "embed" }
                        }

                        Rectangle {
                            id: notesBtn
                            implicitWidth: notesLblRow.implicitWidth + 18
                            implicitHeight: 24
                            radius: Theme.radiusSm
                            color: !editPage.embedMode ? Theme.panel2 : (notesHover.hovered ? Theme.cardHover : "transparent")
                            border.width: !editPage.embedMode ? 1 : 0
                            border.color: Theme.line2
                            Row {
                                id: notesLblRow
                                anchors.centerIn: parent
                                spacing: 6
                                TintedIcon {
                                    anchors.verticalCenter: parent.verticalCenter
                                    name: "list"
                                    size: 13
                                    color: !editPage.embedMode ? Theme.text : Theme.muted
                                }
                                Text {
                                    anchors.verticalCenter: parent.verticalCenter
                                    text: qsTr("Notes")
                                    color: !editPage.embedMode ? Theme.text : Theme.dim
                                    font.family: Theme.body
                                    font.pixelSize: 13
                                    font.weight: !editPage.embedMode ? Font.Medium : Font.Normal
                                }
                            }
                            HoverHandler { id: notesHover; cursorShape: Qt.PointingHandCursor }
                            TapHandler { onTapped: layoutPrefs.webViewMode = "notes" }
                        }
                    }
                }

                // Right actions + new side panel toggle
                Row {
                    anchors.right: parent.right
                    anchors.verticalCenter: parent.verticalCenter
                    spacing: 2

                    IconButton {
                        anchors.verticalCenter: parent.verticalCenter
                        icon: "photo"
                        tip: qsTr("Cover")
                        onClicked: webCoverPopup.open()
                    }
                    IconButton {
                        anchors.verticalCenter: parent.verticalCenter
                        icon: "link"
                        tip: qsTr("Copy web address")
                        onClicked: if (session && session.url) {
                            ws.copyTextToClipboard(session.url)
                            if (editPage.appWindow) editPage.appWindow.showToast(qsTr("Link copied."))
                        }
                    }
                    IconButton {
                        id: webMoreBtn
                        anchors.verticalCenter: parent.verticalCenter
                        icon: "dots"
                        active: objectMenu.visible
                        tip: qsTr("More (Ctrl+.)")
                        onClicked: editPage.openObjectMenu(webMoreBtn)
                    }
                    IconButton {
                        anchors.verticalCenter: parent.verticalCenter
                        icon: "arrow-back"
                        onClicked: editPage.closeRequested()
                        ToolTip.visible: hovered; ToolTip.text: qsTr("Close"); ToolTip.delay: 500
                    }
                    IconButton {
                        anchors.verticalCenter: parent.verticalCenter
                        icon: "layout-sidebar-right"
                        active: editPage.embedMode
                                ? layoutPrefs.webSidePanelOpen
                                : (editPage.narrowRail ? editPage.railOpen : layoutPrefs.notesRailVisible)
                        onClicked: {
                            if (editPage.embedMode) {
                                layoutPrefs.webSidePanelOpen = !layoutPrefs.webSidePanelOpen
                            } else if (editPage.narrowRail) {
                                editPage.railOpen = !editPage.railOpen
                            } else {
                                layoutPrefs.notesRailVisible = !layoutPrefs.notesRailVisible
                            }
                        }
                        ToolTip.visible: hovered
                        ToolTip.text: editPage.embedMode
                                      ? (layoutPrefs.webSidePanelOpen ? qsTr("Hide notes side panel") : qsTr("Show notes side panel"))
                                      : qsTr("Toggle inspector rail")
                        ToolTip.delay: 500
                    }
                }
            }

            Rectangle {
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.bottom: parent.bottom
                height: 1
                color: Theme.line
            }
        }

    RowLayout {
        Layout.fillWidth: true
        Layout.fillHeight: true
        spacing: 0

        // ---------------- web view panel (Embed mode) ----------------
        WebEmbedView {
            id: webEmbedPane
            visible: editPage.embedMode
            Layout.fillWidth: editPage.embedMode
            Layout.preferredWidth: editPage.embedMode ? 1 : 0
            Layout.fillHeight: true
            url: editPage.embedMode && editPage.session ? editPage.session.url : ""
            title: editPage.session ? editPage.session.title : ""
            description: editPage.session ? editPage.session.description : ""
            domain: editPage.session ? editPage.session.domain : ""
            faviconUrl: editPage.session ? editPage.session.faviconUrl : ""
            thumbnailUrl: editPage.session ? editPage.session.thumbnailUrl : ""
            coverAssetId: editPage.session ? editPage.session.coverAssetId : ""
        }

        // Resizable divider between Web view and the new Notes side panel
        Item {
            visible: editPage.webSidePanelShown
            Layout.preferredWidth: 6
            Layout.fillHeight: true

            Rectangle {
                anchors.horizontalCenter: parent.horizontalCenter
                width: 1
                height: parent.height
                color: gripMouse.containsMouse || editPage.resizingWebSide ? Theme.line3 : Theme.line
            }
            MouseArea {
                id: gripMouse
                anchors.fill: parent
                hoverEnabled: true
                cursorShape: Qt.SplitHCursor
                onPressed: editPage.resizingWebSide = true
                onReleased: editPage.resizingWebSide = false
                onCanceled: editPage.resizingWebSide = false
                onPositionChanged: (mouse) => {
                    if (!pressed) return
                    const p = mapToItem(editPage, mouse.x, 0).x
                    layoutPrefs.webSidePanelWidth = Math.max(300, Math.min(editPage.width - 360, editPage.width - p))
                }
            }
        }

        // ---------------- Book left column (300px: Cover + stacked properties — Book.dc.html) ----------------
        Rectangle {
            visible: editPage.isBookLayout
            Layout.preferredWidth: 300
            Layout.fillHeight: true
            color: Theme.page

            Rectangle { anchors.top: parent.top; anchors.bottom: parent.bottom; anchors.right: parent.right; width: 1; color: Theme.line }

            Flickable {
                anchors.fill: parent
                contentWidth: width; contentHeight: bookCol.implicitHeight + 56
                clip: true; boundsBehavior: Flickable.StopAtBounds

                ColumnLayout {
                    id: bookCol
                    x: 24; y: 28; width: parent.width - 48; spacing: 0

                    // Book cover box (300px tall — Book.dc.html)
                    Rectangle {
                        Layout.fillWidth: true; Layout.preferredHeight: 300
                        color: Theme.ink; border.width: 1; border.color: Theme.line2; clip: true

                        readonly property string coverUrl: {
                            if (!editPage.session || !ws) return ""
                            if (editPage.session.coverAssetId.length > 0) {
                                const p = ws.assetLocalPath(editPage.session.coverAssetId)
                                if (p.length > 0) return "file://" + p
                            }
                            return editPage.session.thumbnailUrl || ""
                        }

                        PrivateImage {
                            visible: parent.coverUrl.length > 0
                            inObject: true; anchors.fill: parent
                            source: parent.coverUrl
                            fillMode: Image.PreserveAspectCrop; asynchronous: true; cache: true
                        }
                        // Minimal geometric book placeholder when no cover is set
                        Rectangle {
                            visible: parent.coverUrl.length === 0
                            anchors.centerIn: parent; width: 148; height: 214
                            color: "transparent"; border.width: 1; border.color: Theme.line3
                            Rectangle { x: 16; y: 30; width: 108; height: 1; color: Theme.line3 }
                            Rectangle { x: 16; y: 40; width: 72; height: 1; color: Theme.line3 }
                            Rectangle { anchors.centerIn: parent; width: 64; height: 64; color: Theme.accent }
                            Rectangle { x: 16; y: 184; width: 58; height: 1; color: Theme.line3 }
                        }
                        HoverHandler { id: bkCovHov; cursorShape: Qt.PointingHandCursor }
                        TapHandler { onTapped: webCoverPopup.open() }
                    }

                    Text {
                        Layout.topMargin: 10; Layout.bottomMargin: 16
                        text: Theme.caps(qsTr("COVER · CLICK TO SET"))
                        color: Theme.label; font.family: Theme.mono; font.pixelSize: 10
                    }

                    PropertyTable {
                        Layout.fillWidth: true
                        objectId: editPage.isBookLayout && editPage.session ? editPage.session.objectId : ""
                        objectTypeId: "book"
                        stacked: true
                        onOpenObjectRequested: (id) => { if (editPage.appWindow) editPage.appWindow.openObject(id) }
                    }
                }
            }
        }

        // ---------------- main column (also acts as the Web view's Notes side panel) ----------------
        Rectangle {
            id: mainCol
            visible: !editPage.webFullPanel
            Layout.fillWidth: !editPage.webSidePanelShown && !editPage.webFullPanel
            Layout.preferredWidth: editPage.webFullPanel ? 0
                                 : editPage.webSidePanelShown ? editPage.clampedWebSideW : 1
            Layout.fillHeight: true
            color: editPage.webSidePanelShown ? (Theme.slate ? Theme.bg2 : Theme.panel) : "transparent"
            clip: true

            Flickable {
                id: headFlick
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.top: parent.top
                height: 156
                contentWidth: width
                clip: true
                visible: !!editPage.session && editPage.session.coverAssetId.length > 0 && !editPage.hasWebUrl && !editPage.isBookLayout
                interactive: false

                // cover
                PrivateImage {
                    inObject: true
                    id: coverImage
                    anchors.fill: parent
                    source: headFlick.visible ? "file://" + ws.assetLocalPath(editPage.session.coverAssetId) : ""
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
                anchors.topMargin: headFlick.visible ? 0 : (editPage.webSidePanelShown ? 0 : 10)
                anchors.bottom: parent.bottom
                anchors.left: parent.left
                anchors.right: parent.right
                color: "transparent"

                ColumnLayout {
                    anchors.fill: parent
                    spacing: 0

                    // Book top navigation bar (Book.dc.html: "Books / Reading · 2 / 7 < >")
                    Rectangle {
                        visible: editPage.isBookLayout
                        Layout.fillWidth: true
                        Layout.preferredHeight: 46
                        color: "transparent"
                        Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.line }
                        RowLayout {
                            anchors.fill: parent
                            anchors.leftMargin: editPage.colMargin; anchors.rightMargin: 16
                            spacing: 8
                            Text {
                                text: Theme.caps(editPage.bookStatusLabel.length > 0
                                                 ? (qsTr("Books") + " / " + editPage.bookStatusLabel)
                                                 : qsTr("Books"))
                                color: Theme.label; font.family: Theme.mono; font.pixelSize: 11
                            }
                            Item { Layout.fillWidth: true }
                            Text {
                                visible: editPage.allBooksList.length > 0 && editPage.currentBookIndex >= 0
                                text: (editPage.currentBookIndex + 1) + " / " + editPage.allBooksList.length
                                color: Theme.label; font.family: Theme.mono; font.pixelSize: 11
                            }
                            IconButton {
                                visible: editPage.allBooksList.length > 1
                                implicitWidth: 28; implicitHeight: 28; icon: "chevron-left"
                                onClicked: {
                                    const n = editPage.allBooksList.length
                                    const prev = (editPage.currentBookIndex - 1 + n) % n
                                    if (editPage.appWindow) editPage.appWindow.openObject(editPage.allBooksList[prev].id)
                                }
                            }
                            IconButton {
                                visible: editPage.allBooksList.length > 1
                                implicitWidth: 28; implicitHeight: 28; icon: "chevron-right"
                                onClicked: {
                                    const n = editPage.allBooksList.length
                                    const next = (editPage.currentBookIndex + 1) % n
                                    if (editPage.appWindow) editPage.appWindow.openObject(editPage.allBooksList[next].id)
                                }
                            }
                        }
                    }

                    // modern (noir + slate): type chip + collection chips + change-type button
                    RowLayout {
                        visible: !!session && Theme.modern && !editPage.isPersonObject
                        Layout.leftMargin: editPage.colMargin
                        Layout.rightMargin: editPage.colMargin
                        Layout.topMargin: editPage.isWebObject ? 10 : (editPage.webSidePanelShown ? 14 : 20)
                        spacing: 8

                        Rectangle {
                            readonly property string tid: session ? session.typeId : ""
                            readonly property var info: ws && tid ? ws.typeInfo(tid) : null
                            implicitWidth: typeChipRow.implicitWidth + 18
                            implicitHeight: 26
                            radius: Theme.radiusSm
                            color: typeChipHover.hovered ? Theme.cardHover : Theme.card
                            border.width: 1
                            border.color: typeChipHover.hovered ? Theme.line3 : Theme.line2
                            Row {
                                id: typeChipRow
                                anchors.centerIn: parent
                                spacing: 7
                                TypeBadge { anchors.verticalCenter: parent.verticalCenter; typeId: parent.parent.tid; size: 15 }
                                Text {
                                    anchors.verticalCenter: parent.verticalCenter
                                    text: parent.parent.info && parent.parent.info.name ? parent.parent.info.name : Theme.typeName(parent.parent.tid)
                                    color: Theme.bodyText
                                    font.family: Theme.body
                                    font.pixelSize: 13
                                }
                            }
                            HoverHandler { id: typeChipHover; cursorShape: Qt.PointingHandCursor }
                            TapHandler {
                                onTapped: if (editPage.appWindow) {
                                    editPage.appWindow.libraryType = parent.tid
                                    editPage.appWindow.navigate("library")
                                }
                            }
                        }

                        // Collections containing this object (Book.dc.html: "Lecturas 2026")
                        Repeater {
                            model: editPage.objectCollections
                            delegate: Rectangle {
                                required property var modelData
                                implicitWidth: colChipTxt.implicitWidth + 16; implicitHeight: 26
                                color: colChipH.hovered ? Theme.panel2 : "transparent"
                                border.width: 1; border.color: colChipH.hovered ? Theme.line3 : Theme.line2
                                Text {
                                    id: colChipTxt; anchors.centerIn: parent
                                    text: modelData.title || qsTr("Collection")
                                    color: Theme.muted; font.family: Theme.body; font.pixelSize: 12
                                }
                                HoverHandler { id: colChipH; cursorShape: Qt.PointingHandCursor }
                                TapHandler { onTapped: if (editPage.appWindow) editPage.appWindow.openObject(modelData.id) }
                            }
                        }

                        Rectangle {
                            visible: !!session && ["image", "audio", "pdf", "video", "file", "journal", "tag"].indexOf(session.typeId) < 0
                            implicitWidth: chgLbl.implicitWidth + 14; implicitHeight: 26
                            radius: Theme.radiusSm
                            color: chgHov.hovered ? Theme.panel2 : "transparent"
                            border.width: 1; border.color: chgHov.hovered ? Theme.line3 : Theme.line
                            Text {
                                id: chgLbl; anchors.centerIn: parent
                                text: qsTr("Change type…")
                                color: Theme.muted; font.family: Theme.body; font.pixelSize: 12
                            }
                            HoverHandler { id: chgHov; cursorShape: Qt.PointingHandCursor }
                            TapHandler { onTapped: changeTypePopup.openForCurrent() }
                        }

                        Item { Layout.fillWidth: true; visible: editPage.isWebObject }

                        Rectangle {
                            visible: editPage.isWebObject
                            implicitWidth: detailsToggleLbl.implicitWidth + 14; implicitHeight: 26
                            radius: Theme.radiusSm
                            color: detailsToggleHov.hovered ? Theme.panel2 : "transparent"
                            border.width: 1; border.color: detailsToggleHov.hovered ? Theme.line3 : Theme.line
                            Text {
                                id: detailsToggleLbl; anchors.centerIn: parent
                                text: layoutPrefs.webDetailsCollapsed ? qsTr("Show details") : qsTr("Hide details")
                                color: Theme.muted; font.family: Theme.body; font.pixelSize: 12
                            }
                            HoverHandler { id: detailsToggleHov; cursorShape: Qt.PointingHandCursor }
                            TapHandler { onTapped: layoutPrefs.webDetailsCollapsed = !layoutPrefs.webDetailsCollapsed }
                        }
                    }

                    // Person avatar monogram header (Person.dc.html)
                    RowLayout {
                        visible: !!session && editPage.isPersonObject && Theme.noir
                        Layout.leftMargin: editPage.colMargin
                        Layout.rightMargin: editPage.colMargin
                        Layout.topMargin: 24
                        Layout.bottomMargin: 6
                        spacing: 18

                        Rectangle {
                            Layout.preferredWidth: 72; Layout.preferredHeight: 72
                            color: "transparent"
                            border.width: 1; border.color: Theme.person
                            Text {
                                anchors.centerIn: parent
                                text: editPage.personInitials(editPage.session ? editPage.session.title : "")
                                color: Theme.person
                                font.family: Theme.display; font.pixelSize: 24; font.letterSpacing: -0.4
                            }
                        }
                        ColumnLayout {
                            spacing: 6
                            Rectangle {
                                implicitWidth: pChipRow.implicitWidth + 18; implicitHeight: 26
                                color: "transparent"; border.width: 1; border.color: Theme.line2
                                Row {
                                    id: pChipRow; anchors.centerIn: parent; spacing: 7
                                    TintedIcon { anchors.verticalCenter: parent.verticalCenter; name: "person"; size: 14; color: Theme.person }
                                    Text { anchors.verticalCenter: parent.verticalCenter; text: qsTr("Person"); color: Theme.bodyText; font.family: Theme.body; font.pixelSize: 13 }
                                }
                            }
                            Text {
                                text: qsTr("Contact & incoming links")
                                color: Theme.label; font.family: Theme.mono; font.pixelSize: 11
                            }
                        }
                    }

                    // object type breadcrumb (sky)
                    Text {
                        id: typeIdLabel
                        Layout.leftMargin: editPage.colMargin
                        Layout.topMargin: 14
                        visible: !!session && !Theme.modern
                        text: {
                            const info = ws.typeInfo(session ? session.typeId : "")
                            const label = info.name !== undefined ? info.name.toUpperCase()
                                        : Theme.typeLabel(session ? session.typeId : "")
                            return "OBJECT / " + label
                        }
                        color: Theme.muted
                        font.family: Theme.mono
                        font.pixelSize: 11
                        font.letterSpacing: Theme.track(1.6)
                        font.weight: Font.Medium
                    }

                    // Web link preview card (compact horizontal strip above Title so the block editor keeps generous room)
                    Rectangle {
                        id: webHeaderCard
                        visible: editPage.hasWebUrl && !layoutPrefs.webDetailsCollapsed
                        Layout.fillWidth: true
                        Layout.leftMargin: editPage.colMargin
                        Layout.rightMargin: editPage.colMargin
                        Layout.topMargin: 8
                        Layout.preferredHeight: webThumbBox.visible ? (editPage.webSidePanelShown ? 74 : 84) : 34
                        Layout.minimumHeight: Layout.preferredHeight
                        radius: Theme.radiusLg
                        color: Theme.card
                        border.width: 1
                        border.color: Theme.cardBorder
                        clip: true

                        RowLayout {
                            anchors.fill: parent
                            spacing: 0

                            Rectangle {
                                id: webThumbBox
                                visible: !!editPage.session && (editPage.session.coverAssetId.length > 0 || editPage.session.thumbnailUrl.length > 0)
                                Layout.preferredWidth: editPage.webSidePanelShown ? 124 : 150
                                Layout.fillHeight: true
                                color: Theme.ink
                                clip: true

                                PrivateImage {
                                    id: webThumbMain
                                    inObject: true
                                    anchors.fill: parent
                                    fillMode: Image.PreserveAspectCrop
                                    sourceSize: Qt.size(480, 270)
                                    asynchronous: true
                                    cache: true
                                    smooth: true
                                    source: {
                                        if (!editPage.session) return ""
                                        if (editPage.session.coverAssetId.length > 0) {
                                            const p = ws.assetLocalPath(editPage.session.coverAssetId)
                                            if (p.length > 0) return "file://" + p
                                        }
                                        return editPage.session.thumbnailUrl
                                    }
                                }

                                HoverHandler { id: webCoverHover }
                                Rectangle {
                                    anchors.right: parent.right
                                    anchors.bottom: parent.bottom
                                    anchors.margins: 5
                                    width: 24
                                    height: 24
                                    radius: Theme.radiusSm
                                    color: Qt.rgba(0, 0, 0, 0.65)
                                    border.width: 1
                                    border.color: Theme.line2
                                    visible: webCoverHover.hovered
                                    TintedIcon { anchors.centerIn: parent; name: "pencil"; size: 12; color: Theme.text }
                                    HoverHandler { cursorShape: Qt.PointingHandCursor }
                                    TapHandler { onTapped: webCoverPopup.open() }
                                }
                            }

                            Rectangle {
                                visible: webThumbBox.visible
                                Layout.preferredWidth: 1
                                Layout.fillHeight: true
                                color: Theme.line
                            }

                            Rectangle {
                                Layout.fillWidth: true
                                Layout.fillHeight: true
                                color: Theme.panel2

                                RowLayout {
                                    anchors.fill: parent
                                    anchors.leftMargin: 10
                                    anchors.rightMargin: 8
                                    spacing: 8

                                    Item {
                                        Layout.preferredWidth: 16
                                        Layout.preferredHeight: 16
                                        Image {
                                            id: headerFav
                                            anchors.fill: parent
                                            visible: !!editPage.session && editPage.session.faviconUrl.length > 0 && status === Image.Ready
                                            source: editPage.session ? editPage.session.faviconUrl : ""
                                            sourceSize: Qt.size(32, 32)
                                            fillMode: Image.PreserveAspectFit
                                            asynchronous: true
                                            cache: true
                                        }
                                        TintedIcon {
                                            anchors.centerIn: parent
                                            visible: !headerFav.visible
                                            name: "world"
                                            size: 14
                                            color: Theme.muted
                                        }
                                    }

                                    Text {
                                        Layout.fillWidth: true
                                        text: editPage.session ? editPage.session.url : ""
                                        color: Theme.dim
                                        font.family: Theme.body
                                        font.pixelSize: 12
                                        elide: Text.ElideRight
                                        maximumLineCount: webThumbBox.visible ? 2 : 1
                                        wrapMode: webThumbBox.visible ? Text.WrapAnywhere : Text.NoWrap
                                    }

                                    IconButton {
                                        icon: "external-link"
                                        onClicked: if (editPage.session && editPage.session.url) Qt.openUrlExternally(editPage.session.url)
                                        ToolTip.visible: hovered; ToolTip.text: qsTr("Open in browser"); ToolTip.delay: 500
                                    }
                                }
                            }
                        }
                    }

                    JournalHeader {
                        visible: editPage.isJournalObject
                        Layout.fillWidth: true
                        Layout.topMargin: 10
                        Layout.leftMargin: editPage.colMargin
                        Layout.rightMargin: editPage.colMargin
                        iso: editPage.journalIso
                        onDayRequested: (iso) => {
                            const id = ws.journalFor(iso)
                            if (id && editPage.appWindow) editPage.appWindow.showObject(id)
                        }
                        onAudioRecorded: (path, seconds) => {
                            const id = ws.importFileAsAsset(path)
                            if (id && session) session.insertAudioBlockAfter(session.blockCount - 1, id, seconds)
                            else if (editPage.appWindow) editPage.appWindow.showToast(qsTr("Could not save the recording"))
                        }
                        onRecordFailed: (reason) => {
                            if (editPage.appWindow)
                                editPage.appWindow.showToast(reason === "ffmpeg" ? qsTr("Install ffmpeg to record audio.")
                                                                                 : qsTr("No microphone input."))
                        }
                    }

                    // Title (wraps across lines for long web link titles)
                    Item {
                        Layout.fillWidth: true
                        Layout.topMargin: editPage.isWebObject ? 6 : 10
                        Layout.bottomMargin: editPage.isWebObject ? 2 : 4
                        Layout.leftMargin: editPage.colMargin
                        Layout.rightMargin: editPage.colMargin
                        implicitHeight: Math.max(editPage.isWebObject ? 30 : 40, titleRow.implicitHeight)

                        RowLayout {
                            id: titleRow
                            anchors.fill: parent
                            spacing: 12

                            TextEdit {
                                id: titleInput
                                objectName: "titleInput"
                                visible: !editPage.isJournalObject
                                Layout.fillWidth: true
                                Layout.preferredWidth: 1
                                Layout.alignment: Qt.AlignVCenter
                                wrapMode: TextEdit.WordWrap
                                text: session ? session.title : ""
                                color: Theme.text
                                selectionColor: Qt.rgba(0.75, 0.89, 1, 0.38)
                                selectedTextColor: Theme.text
                                font.family: Theme.display
                                font.pixelSize: editPage.webSidePanelShown ? 18 : (editPage.isWebObject ? 22 : 28)
                                font.weight: Font.DemiBold
                                font.letterSpacing: -0.4
                                selectByMouse: true

                                function end() { cursorPosition = length }

                                onTextChanged: {
                                    if (!session) return
                                    if (text.indexOf("\n") >= 0 || text.indexOf("\r") >= 0) {
                                        const clean = text.replace(/[\r\n]+/g, " ")
                                        text = clean
                                        return
                                    }
                                    if (activeFocus && session.title !== text)
                                        session.title = text
                                }
                                // Enter / ↓ / Alt+↓: into the first block (a new one when empty).
                                Keys.onReturnPressed: (event) => { event.accepted = true; blocks.enterFromTop() }
                                Keys.onEnterPressed: (event) => { event.accepted = true; blocks.enterFromTop() }
                                Keys.onDownPressed: (event) => {
                                    const last = cursorRectangle.y >= positionToRectangle(length).y - 1
                                    if (last || (event.modifiers & Qt.AltModifier)) {
                                        event.accepted = true
                                        blocks.enterFromTop()
                                    } else {
                                        event.accepted = false
                                    }
                                }

                                Text {
                                    visible: titleInput.length === 0
                                    text: qsTr("Untitled")
                                    color: Theme.faint
                                    font: titleInput.font
                                }
                            }

                            Item { visible: editPage.isJournalObject; Layout.fillWidth: true }

                            // Object actions: compact icons so a long title keeps its width.
                            Row {
                                Layout.alignment: Qt.AlignTop
                                spacing: 2
                                IconButton {
                                    visible: !editPage.webSplitActive && !!session && session.typeId !== "image"
                                    icon: "photo"
                                    tip: qsTr("Cover")
                                    onClicked: webCoverPopup.open()
                                }
                                IconButton {
                                    visible: !editPage.webSplitActive && !!session
                                    icon: "link"
                                    tip: editPage.isWebObject ? qsTr("Copy web address") : qsTr("Copy link to this object")
                                    onClicked: {
                                        const url = editPage.isWebObject && session.url ? session.url
                                                                                       : "yaop://object/" + session.objectId
                                        ws.copyTextToClipboard(url)
                                        if (editPage.appWindow) editPage.appWindow.showToast(qsTr("Link copied."))
                                    }
                                }
                                IconButton {
                                    // outline / backlinks rail: docked when wide, on demand when narrow
                                    visible: !editPage.webSplitActive && editPage.narrowRail
                                    icon: "adjustments-horizontal"
                                    tip: qsTr("Outline and backlinks")
                                    onClicked: editPage.railOpen = !editPage.railOpen
                                }
                                IconButton {
                                    // "Show images in this note": only while privacy covers notes
                                    visible: Privacy.active && Privacy.store.scope === 0
                                    icon: Privacy.noteRevealed ? "eye" : "eye-off"
                                    active: !Privacy.noteRevealed
                                    tip: qsTr("Show images in this note")
                                    onClicked: Privacy.noteRevealed = !Privacy.noteRevealed
                                }
                                IconButton {
                                    visible: !editPage.webSplitActive && Theme.noir && !editPage.inSidePane
                                    icon: "arrows-maximize"
                                    active: editPage.focusActive
                                    tip: qsTr("Focus mode (Ctrl+Shift+F)")
                                    onClicked: if (editPage.appWindow) editPage.appWindow.focusMode = !editPage.appWindow.focusMode
                                }
                                IconButton {
                                    id: moreBtn
                                    visible: !editPage.webSplitActive
                                    icon: "dots"
                                    active: objectMenu.visible
                                    tip: qsTr("More (Ctrl+.)")
                                    onClicked: editPage.openObjectMenu(moreBtn)
                                }
                                IconButton {
                                    visible: !editPage.webSplitActive
                                    icon: "arrow-back"
                                    tip: qsTr("Close")
                                    onClicked: editPage.closeRequested()
                                }
                            }
                        }
                    }

                    // Web link OpenGraph description
                    Text {
                        visible: editPage.isWebObject && !layoutPrefs.webDetailsCollapsed && !!editPage.session && editPage.session.description.length > 0
                        Layout.fillWidth: true
                        Layout.leftMargin: editPage.colMargin
                        Layout.rightMargin: editPage.colMargin
                        Layout.bottomMargin: 6
                        text: editPage.session ? editPage.session.description : ""
                        color: Theme.dim
                        font.family: Theme.body
                        font.pixelSize: 12
                        wrapMode: Text.WordWrap
                        maximumLineCount: 2
                        elide: Text.ElideRight
                    }

                    // tags of the object (not on a tag itself)
                    TagRow {
                        visible: !!editPage.session && editPage.session.typeId !== "tag"
                                 && !(editPage.isWebObject && layoutPrefs.webDetailsCollapsed)
                        Layout.fillWidth: true
                        Layout.leftMargin: editPage.colMargin
                        Layout.rightMargin: editPage.colMargin
                        Layout.bottomMargin: editPage.isWebObject ? 4 : 10
                        objectId: visible ? editPage.session.objectId : ""
                        onOpenTagRequested: (id) => { if (id && editPage.appWindow) editPage.appWindow.openObject(id) }
                    }

                    // Tag color selector when viewing a Tag object
                    Row {
                        visible: !!editPage.session && editPage.session.typeId === "tag"
                        Layout.leftMargin: editPage.colMargin
                        Layout.bottomMargin: 12
                        spacing: 8
                        Text {
                            anchors.verticalCenter: parent.verticalCenter
                            text: qsTr("Tag color")
                            color: Theme.muted
                            font.family: Theme.body
                            font.pixelSize: 12
                        }
                        Repeater {
                            model: ["#A387FF", "#7EA6FF", "#7AF0D0", "#C9F2A6", "#FFB38A", "#FF8FB3", "#FFD166"]
                            delegate: Rectangle {
                                required property string modelData
                                readonly property bool current: {
                                    editPage.tagRev
                                    return editPage.session && ws && ws.tagColor(editPage.session.title) === modelData
                                }
                                anchors.verticalCenter: parent.verticalCenter
                                width: 18
                                height: 18
                                radius: Theme.noir ? 0 : 9
                                color: modelData
                                border.width: current ? 2 : 0
                                border.color: Theme.text
                                HoverHandler { cursorShape: Qt.PointingHandCursor }
                                TapHandler {
                                    onTapped: if (editPage.session && ws) ws.setTagColor(editPage.session.title, modelData)
                                }
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

                    // Project progress bar (TypeHeroes.dc.html: "7 of 12 tasks · 3 notes · 2 people")
                    ColumnLayout {
                        visible: editPage.isProjectObject && editPage.projProgress && editPage.projProgress.total > 0
                        Layout.fillWidth: true
                        Layout.leftMargin: editPage.colMargin
                        Layout.rightMargin: editPage.colMargin
                        Layout.bottomMargin: 12
                        spacing: 6
                        RowLayout {
                            Layout.fillWidth: true
                            Text {
                                text: qsTr("Progress")
                                color: Theme.dim; font.family: Theme.body; font.pixelSize: 13
                            }
                            Item { Layout.fillWidth: true }
                            Text {
                                text: editPage.projProgress ? qsTr("%1 of %2 tasks").arg(editPage.projProgress.done).arg(editPage.projProgress.total) : ""
                                color: Theme.bodyText; font.family: Theme.body; font.pixelSize: 13
                            }
                        }
                        Rectangle {
                            Layout.fillWidth: true; Layout.preferredHeight: 4; color: Theme.line2
                            Rectangle {
                                width: editPage.projProgress && editPage.projProgress.total > 0
                                       ? parent.width * (editPage.projProgress.done / editPage.projProgress.total) : 0
                                height: parent.height; color: Theme.project
                            }
                        }
                        Text {
                            visible: editPage.projProgress && (editPage.projProgress.notes > 0 || editPage.projProgress.people > 0)
                            text: Theme.caps(editPage.projProgress
                                             ? qsTr("%1 NOTES · %2 PEOPLE").arg(editPage.projProgress.notes).arg(editPage.projProgress.people) : "")
                            color: Theme.label; font.family: Theme.mono; font.pixelSize: 10
                        }
                    }

                    // Inline property table (hidden when Book left column already shows them, or Web details collapsed)
                    PropertyTable {
                        id: propTable
                        property bool allowed: !!editPage.session && !editPage.isBookLayout
                                               && !(editPage.isWebObject && layoutPrefs.webDetailsCollapsed)
                                               && (Theme.modern || editPage.webSidePanelShown)
                        visible: allowed && entries.length > 0
                        Layout.fillWidth: true
                        Layout.fillHeight: false
                        Layout.preferredHeight: visible ? Math.min(200, implicitHeight) : 0
                        Layout.minimumHeight: Layout.preferredHeight
                        Layout.leftMargin: editPage.colMargin
                        Layout.rightMargin: editPage.colMargin
                        Layout.bottomMargin: editPage.isWebObject ? 6 : 10
                        objectId: allowed ? editPage.session.objectId : ""
                        objectTypeId: editPage.session ? editPage.session.typeId : ""
                        hideWebHeaderProps: editPage.isWebObject
                        labelWidth: editPage.webSidePanelShown ? 104 : 136
                        onOpenObjectRequested: (id) => { if (editPage.appWindow) editPage.appWindow.openObject(id) }
                    }

                    Rectangle {
                        Layout.fillWidth: true
                        Layout.preferredHeight: 1
                        Layout.leftMargin: editPage.colMargin
                        Layout.rightMargin: editPage.colMargin
                        color: Theme.line
                    }

                    // Tagged objects list when viewing a Tag object
                    ColumnLayout {
                        visible: !!editPage.session && editPage.session.typeId === "tag" && editPage.tagBacklinks.length > 0
                        Layout.fillWidth: true
                        Layout.fillHeight: false
                        Layout.leftMargin: editPage.colMargin
                        Layout.rightMargin: editPage.colMargin
                        Layout.topMargin: 10
                        Layout.bottomMargin: 6
                        spacing: 6

                        Text {
                            text: Theme.caps(qsTr("TAGGED OBJECTS (%1)").arg(editPage.tagBacklinks.length))
                            color: Theme.muted
                            font.family: Theme.mono
                            font.pixelSize: 11
                            font.letterSpacing: Theme.track(1.0)
                        }
                        Flow {
                            Layout.fillWidth: true
                            spacing: 6
                            Repeater {
                                model: editPage.tagBacklinks
                                delegate: Rectangle {
                                    required property var modelData
                                    height: 28
                                    width: blRow.implicitWidth + 16
                                    radius: Theme.radiusSm
                                    color: blHover.hovered ? Theme.cardHover : Theme.card
                                    border.width: 1
                                    border.color: Theme.cardBorder
                                    Row {
                                        id: blRow
                                        anchors.centerIn: parent
                                        spacing: 6
                                        TypeBadge { anchors.verticalCenter: parent.verticalCenter; typeId: modelData.typeId || ""; emoji: modelData.iconEmoji || ""; size: 16 }
                                        Text {
                                            anchors.verticalCenter: parent.verticalCenter
                                            text: modelData.title || qsTr("Untitled")
                                            color: Theme.text
                                            font.family: Theme.body
                                            font.pixelSize: 13
                                        }
                                    }
                                    HoverHandler { id: blHover; cursorShape: Qt.PointingHandCursor }
                                    TapHandler { onTapped: if (editPage.appWindow) editPage.appWindow.openObject(modelData.id) }
                                }
                            }
                        }
                    }

                    // object cover banner (web cover URL or local cover asset, for non-image & non-web objects)
                    Rectangle {
                        visible: !!editPage.session
                                 && editPage.session.typeId !== "image"
                                 && !editPage.hasWebUrl
                                 && !editPage.isBookLayout
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
                        Layout.bottomMargin: editPage.session && editPage.session.typeId === "audio" ? 8 : 0
                        Layout.leftMargin: editPage.colMargin
                        Layout.rightMargin: editPage.colMargin

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

                        AudioPlayer {
                            id: objAudio
                            property int assetTick: 0
                            visible: !!editPage.session && editPage.session.typeId === "audio"
                            anchors.fill: parent
                            assetId: editPage.session ? editPage.session.assetId : ""
                            path: { assetTick; return editPage.session ? ws.assetLocalPath(editPage.session.assetId) : "" }
                            Connections {
                                target: ws
                                function onAssetReady(assetId, localPath) { if (assetId === objAudio.assetId) objAudio.assetTick++ }
                            }
                            ready: path.length > 0
                            durationSeconds: editPage.session && editPage.session.assetId.length > 0
                                             ? ws.assetDurationSeconds(editPage.session.assetId) : 0
                        }
                    }

                    TemplateStrip {
                        Layout.fillWidth: true
                        Layout.leftMargin: editPage.colMargin
                        Layout.rightMargin: editPage.colMargin
                        session: editPage.session
                        appWindow: editPage.appWindow
                    }

                    CollectionView {
                        visible: !!editPage.session && editPage.session.typeId === "collection"
                        Layout.fillWidth: true
                        Layout.leftMargin: editPage.colMargin
                        Layout.rightMargin: editPage.colMargin
                        Layout.bottomMargin: visible ? 16 : 0
                        Layout.preferredHeight: visible ? implicitHeight : 0
                        collectionId: visible ? editPage.session.objectId : ""
                        appWindow: editPage.appWindow
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
                        Layout.minimumHeight: 140
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
                                    focusMode: editPage.focusActive
                                    anchors.fill: parent
                                    onOpenObjectRequested: objectId => editPage.appWindow.openObject(objectId)
                                    onMediaPasteRequested: editPage.insertMediaItems(ws.importClipboardMedia())
                                    onToastRequested: message => { if (editPage.appWindow) editPage.appWindow.showToast(message) }
                                    onExitTop: { titleInput.forceActiveFocus(); titleInput.end() }
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
            visible: !!session && !editPage.focusActive && !editPage.embedMode && (!editPage.webSplitActive || layoutPrefs.notesRailVisible || editPage.narrowRail)
            // Wide windows dock the rail; narrow ones give it no width and float it over the page.
            Layout.preferredWidth: (editPage.showDockedRail && !editPage.focusActive) ? Theme.railWidth : 0
            Layout.fillHeight: true
            z: 20

            Rectangle {
                visible: editPage.showDockedRail || (editPage.narrowRail && editPage.railOpen)
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
                    id: editorRail
                    anchors.fill: parent
                    anchors.leftMargin: 1
                    session: editPage.session
                    workspace: ws
                    onScrollToBlockRequested: blockId => {
                        if (editPage.session)
                            editPage.session.forceFocus(blockId, 0)
                    }
                    onOpenObjectRequested: objectId => editPage.appWindow.openObject(objectId)
                    onOpenObjectAtBlockRequested: (objectId, blockId) => editPage.appWindow.openObjectAtBlock(objectId, blockId)
                    onChangeTypeRequested: changeTypePopup.openForCurrent()
                    onDuplicateRequested: if (editPage.session && ws) {
                        const copyId = ws.duplicateObject(editPage.session.objectId)
                        if (copyId) {
                            if (editPage.appWindow) {
                                editPage.appWindow.openObject(copyId)
                                editPage.appWindow.showToast(qsTr("Object duplicated"))
                            }
                        }
                    }
                    onTrashRequested: editPage.requestTrashCurrent()
                    onToastRequested: msg => { if (editPage.appWindow) editPage.appWindow.showToast(msg) }
                }
            }
        }
    }
    } // outer ColumnLayout

    Menu {
        id: objectMenu
        width: 270
        padding: 4
        background: Rectangle { color: Theme.sheet; border.width: 1; border.color: Theme.line2; radius: Theme.radiusSm }
        enter: Transition {
            NumberAnimation { property: "opacity"; from: 0; to: 1; duration: Theme.fast }
            NumberAnimation { property: "scale"; from: 0.97; to: 1; duration: Theme.fast; easing.type: Easing.OutCubic }
        }
        exit: Transition { NumberAnimation { property: "opacity"; from: 1; to: 0; duration: Theme.fast } }
        property bool pinned: false
        property bool isTpl: false
        property var tpls: []
        property int words: 0
        property int versions: 0
        function refresh() {
            if (!session || !ws) return
            const sum = ws.objectSummary(session.objectId)
            pinned = !!sum.pinned
            isTpl = sum.isTemplate === true
            tpls = ws.templatesFor(session.typeId).filter(t => t.id !== session.objectId)
            words = ws.objectStats(session.objectId).words || 0
            versions = ws.objectHistory(session.objectId).length
        }
        MenuRow {
            text: objectMenu.pinned ? qsTr("Unpin from the sidebar") : qsTr("Pin to the sidebar")
            onTriggered: if (session) ws.togglePinned(session.objectId)
        }
        MenuRow {
            text: qsTr("Copy link to this object")
            onTriggered: if (session) {
                ws.copyTextToClipboard("yaop://object/" + session.objectId)
                if (editPage.appWindow) editPage.appWindow.showToast(qsTr("Link copied."))
            }
        }
        MenuSeparator { contentItem: Rectangle { implicitHeight: 1; color: Theme.line } }
        MenuRow {
            enabled: !!session && ["image", "audio", "pdf", "video", "file", "journal", "tag"].indexOf(session.typeId) < 0
            text: qsTr("Change type…")
            onTriggered: changeTypePopup.openForCurrent()
        }
        MenuRow {
            text: qsTr("Duplicate")
            onTriggered: if (session) {
                const copyId = ws.duplicateObject(session.objectId)
                if (copyId && editPage.appWindow) {
                    editPage.appWindow.openObject(copyId)
                    editPage.appWindow.showToast(qsTr("Object duplicated"))
                }
            }
        }
        MenuRow {
            enabled: !!session && session.typeId !== "collection"
            text: qsTr("Add to collection…")
            onTriggered: collectionPicker.open()
        }
        MenuRow {
            readonly property bool isTpl: objectMenu.isTpl
            enabled: !!session && ["image", "audio", "pdf", "video", "file", "journal", "tag", "set"].indexOf(session.typeId) < 0
            text: isTpl ? qsTr("Stop being a template") : qsTr("Use as a template for this type")
            onTriggered: if (session) {
                ws.setIsTemplate(session.objectId, !isTpl)
                if (editPage.appWindow) editPage.appWindow.showToast(isTpl ? qsTr("No longer a template")
                                                                         : qsTr("New ones of this type can start from it"))
            }
        }
        MenuRow {
            readonly property var tpls: objectMenu.tpls
            enabled: tpls.length > 0
            text: tpls.length > 0 ? qsTr("Apply a template… · %1").arg(tpls.length) : qsTr("Apply a template…")
            onTriggered: templatePicker.open()
        }
        MenuSeparator { contentItem: Rectangle { implicitHeight: 1; color: Theme.line } }
        MenuRow {
            text: qsTr("Details · %1 words").arg(objectMenu.words)
            onTriggered: editPage.showRailTab(2)
        }
        MenuRow {
            readonly property int count: objectMenu.versions
            text: count > 0 ? qsTr("Version history · %1").arg(count) : qsTr("Version history")
            onTriggered: count > 0 ? editPage.showVersion(0) : editPage.showRailTab(2)
        }
        MenuRow { text: qsTr("Objects in this page"); onTriggered: editPage.showRailTab(3) }
        MenuRow { text: qsTr("Backlinks"); onTriggered: editPage.showRailTab(0) }
        MenuSeparator { contentItem: Rectangle { implicitHeight: 1; color: Theme.line } }
        MenuRow {
            text: qsTr("Move to trash")
            danger: true
            onTriggered: editPage.requestTrashCurrent()
        }
    }

    PickList {
        id: collectionPicker
        placeholder: qsTr("Add to a collection, or type a new name…")
        source: function (f) {
            if (!ws) return []
            const mine = session ? ws.collectionsForObject(session.objectId).map(c => c.id) : []
            const all = ws.collections().filter(c => c.id !== (session ? session.objectId : ""))
            const lf = f.toLowerCase()
            const out = all.filter(c => lf.length === 0 || c.title.toLowerCase().indexOf(lf) >= 0)
                           .map(c => ({ id: c.id, label: c.title, icon: "folder", color: Theme.label,
                                        hint: mine.indexOf(c.id) >= 0 ? qsTr("already in") : String(c.count) }))
            if (f.length > 0 && !all.some(c => c.title.toLowerCase() === lf))
                out.push({ id: "", label: qsTr("New collection “%1”").arg(f), name: f, icon: "plus", color: Theme.accent })
            return out
        }
        onPicked: (e) => {
            if (!session) return
            const colId = e.id.length > 0 ? e.id : ws.createCollection(e.name)
            const ok = ws.addToCollection(colId, session.objectId)
            if (editPage.appWindow)
                editPage.appWindow.showToast(ok ? qsTr("Added to “%1”").arg(e.name || e.label) : qsTr("Already in “%1”").arg(e.label))
        }
    }
    PickList {
        id: templatePicker
        placeholder: qsTr("Apply a template…")
        source: function (f) {
            if (!ws || !session) return []
            const lf = f.toLowerCase()
            return ws.templatesFor(session.typeId)
                     .filter(t => t.id !== session.objectId && (lf.length === 0 || t.title.toLowerCase().indexOf(lf) >= 0))
                     .map(t => ({ id: t.id, label: t.title, icon: Theme.typeIcon(session.typeId), color: Theme.typeColor(session.typeId),
                                  hint: qsTr("%1 blocks").arg(t.blockCount) }))
        }
        onPicked: (e) => {
            if (session && ws.applyTemplate(session.objectId, e.id) && editPage.appWindow)
                editPage.appWindow.showToast(qsTr("Applied “%1”").arg(e.label))
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
                text: Theme.caps(qsTr("SET WEB COVER"))
                color: Theme.text
                font.family: Theme.mono
                font.pixelSize: 12
                font.letterSpacing: Theme.track(1.4)
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
                Button { text: Theme.caps(qsTr("CANCEL")); small: true; onClicked: webCoverPopup.close() }
                Button {
                    id: setWebCoverBtn
                    text: Theme.caps(qsTr("SET COVER"))
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

    // ---------------- Change Type Dialog (Mentions.dc.html + spec/schema.md) ----------------
    Popup {
        id: changeTypePopup
        anchors.centerIn: Overlay.overlay
        width: Math.min(460, editPage.width - 40)
        padding: 20
        modal: true
        closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside

        property string targetTypeId: "note"
        property var previewData: ({ error: "", loose: [], properties: [] })
        readonly property var candidateTypes: {
            if (!ws) return []
            const fixed = ["image", "audio", "pdf", "video", "file", "journal", "tag"]
            return ws.allTypes().filter(t => fixed.indexOf(t.id) < 0 && (!editPage.session || t.id !== editPage.session.typeId))
        }

        function refreshPreview() {
            if (!ws || !editPage.session || !targetTypeId) return
            previewData = ws.previewTypeChange(editPage.session.objectId, targetTypeId)
        }
        function openForCurrent() {
            if (!editPage.session || candidateTypes.length === 0) return
            targetTypeId = candidateTypes[0].id
            refreshPreview()
            open()
        }
        onTargetTypeIdChanged: refreshPreview()

        Overlay.modal: Rectangle { color: Theme.scrim }
        background: Rectangle {
            radius: Theme.radiusLg
            color: Theme.sheet
            border.color: Theme.line2
            border.width: 1
        }

        contentItem: ColumnLayout {
            spacing: 14

            Text {
                text: Theme.caps(qsTr("CHANGE %1 TO").arg(editPage.session ? Theme.typeName(editPage.session.typeId).toUpperCase() : ""))
                color: Theme.label; font.family: Theme.mono; font.pixelSize: 11
            }

            Flow {
                Layout.fillWidth: true; spacing: 6
                Repeater {
                    model: changeTypePopup.candidateTypes
                    delegate: Chip {
                        required property var modelData
                        text: modelData.name || modelData.id
                        typeId: modelData.id
                        active: changeTypePopup.targetTypeId === modelData.id
                        onClicked: changeTypePopup.targetTypeId = modelData.id
                    }
                }
            }

            Text {
                visible: changeTypePopup.previewData.error && changeTypePopup.previewData.error.length > 0
                Layout.fillWidth: true
                text: changeTypePopup.previewData.error === "fixed_type"
                      ? qsTr("This type is fixed (media, journal, or tag) and cannot be converted.")
                      : String(changeTypePopup.previewData.error || "")
                color: Theme.danger; font.family: Theme.body; font.pixelSize: 13; wrapMode: Text.WordWrap
            }

            ColumnLayout {
                visible: !changeTypePopup.previewData.error
                Layout.fillWidth: true; spacing: 4
                Text {
                    text: Theme.caps(qsTr("PROPERTIES"))
                    color: Theme.label; font.family: Theme.mono; font.pixelSize: 11
                }
                Repeater {
                    model: changeTypePopup.previewData.loose || []
                    delegate: Rectangle {
                        required property var modelData
                        Layout.fillWidth: true; implicitHeight: 32; color: "transparent"
                        Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.lineSubtle }
                        RowLayout {
                            anchors.fill: parent; spacing: 10
                            Text { Layout.fillWidth: true; text: modelData.key; color: Theme.bodyText; font.family: Theme.body; font.pixelSize: 13 }
                            Text { text: "→"; color: Theme.label }
                            Text { text: qsTr("kept as loose property"); color: Theme.muted; font.family: Theme.body; font.pixelSize: 13 }
                        }
                    }
                }
                Text {
                    visible: !changeTypePopup.previewData.loose || changeTypePopup.previewData.loose.length === 0
                    text: qsTr("All existing properties fit the target type.")
                    color: Theme.dim; font.family: Theme.body; font.pixelSize: 13
                }
                Text {
                    Layout.fillWidth: true; Layout.topMargin: 6
                    text: qsTr("Nothing is deleted: properties that don't match stay saved and reappear if you switch back.")
                    color: Theme.muted; font.family: Theme.body; font.pixelSize: 12; wrapMode: Text.WordWrap
                }
            }

            RowLayout {
                Layout.fillWidth: true; spacing: 10
                Item { Layout.fillWidth: true }
                Button { small: true; text: qsTr("Cancel"); onClicked: changeTypePopup.close() }
                Button {
                    small: true; primary: true
                    text: qsTr("Change type")
                    enabled: !changeTypePopup.previewData.error && changeTypePopup.targetTypeId.length > 0
                    onClicked: if (editPage.session && ws) {
                        if (ws.changeObjectType(editPage.session.objectId, changeTypePopup.targetTypeId)) {
                            changeTypePopup.close()
                            if (editPage.appWindow) editPage.appWindow.showToast(qsTr("Object type changed"))
                        }
                    }
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
                text: Theme.caps(qsTr("MOVE TO TRASH"))
                color: Theme.text
                font.family: Theme.mono
                font.pixelSize: 12
                font.letterSpacing: Theme.track(1.4)
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
                    text: Theme.caps(qsTr("CANCEL"))
                    small: true
                    onClicked: trashPopup.close()
                }
                Button {
                    text: Theme.caps(qsTr("MOVE TO TRASH"))
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
