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
    }

    readonly property bool isWebObject: !!session && session.typeId === "web"
    readonly property bool hasWebUrl: isWebObject && session.url.length > 0
    readonly property bool webSplitActive: hasWebUrl && !inSidePane && width >= 680
    readonly property bool embedMode: webSplitActive && layoutPrefs.webViewMode === "embed"
    readonly property bool webSidePanelShown: embedMode && layoutPrefs.webSidePanelOpen
    readonly property bool webFullPanel: embedMode && !layoutPrefs.webSidePanelOpen
    readonly property real clampedWebSideW: Math.max(300, Math.min(Math.max(320, width - 360), layoutPrefs.webSidePanelWidth))
    property bool resizingWebSide: false

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
    function requestTrashCurrent() {
        if (!session)
            return
        pendingTrashBacklinks = ws.backlinksFor(session.objectId)
        trashPopup.open()
    }

    clip: true
    property bool railOpen: false
    readonly property bool showDockedRail: !editPage.narrowRail && !editPage.embedMode && (!editPage.webSplitActive || layoutPrefs.notesRailVisible)
    // One text column for title, breadcrumb and blocks (spec §6: 640 px max, centred).
    readonly property real mainW: editPage.webSidePanelShown ? editPage.clampedWebSideW
                                : (width - (editPage.showDockedRail ? Theme.railWidth : 0))
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

    ColumnLayout {
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

                    Button {
                        visible: editPage.width >= 820
                        anchors.verticalCenter: parent.verticalCenter
                        text: Theme.caps(qsTr("COVER"))
                        small: true
                        onClicked: webCoverPopup.open()
                    }
                    Button {
                        visible: editPage.width >= 820
                        anchors.verticalCenter: parent.verticalCenter
                        text: Theme.caps(qsTr("TRASH"))
                        small: true
                        onClicked: editPage.requestTrashCurrent()
                    }
                    IconButton {
                        anchors.verticalCenter: parent.verticalCenter
                        icon: "asterisk"
                        onClicked: if (session) ws.togglePinned(session.objectId)
                        ToolTip.visible: hovered; ToolTip.text: qsTr("Pin object"); ToolTip.delay: 500
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
                visible: !!editPage.session && editPage.session.coverAssetId.length > 0 && !editPage.hasWebUrl
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

                    // slate: the type as a chip (icon + name); a click lists that type
                    Rectangle {
                        visible: !!session && Theme.slate
                        Layout.leftMargin: editPage.colMargin
                        Layout.topMargin: editPage.webSidePanelShown ? 14 : 18
                        readonly property string tid: session ? session.typeId : ""
                        readonly property var info: ws && tid ? ws.typeInfo(tid) : null
                        implicitWidth: typeChipRow.implicitWidth + 16
                        implicitHeight: 28
                        radius: Theme.radiusSm
                        color: typeChipHover.hovered ? Theme.cardHover : Theme.card
                        border.width: 1
                        border.color: Theme.cardBorder
                        Row {
                            id: typeChipRow
                            anchors.centerIn: parent
                            spacing: 6
                            TypeBadge { anchors.verticalCenter: parent.verticalCenter; typeId: parent.parent.tid; size: 18 }
                            Text {
                                anchors.verticalCenter: parent.verticalCenter
                                text: parent.parent.info && parent.parent.info.name ? parent.parent.info.name : Theme.typeName(parent.parent.tid)
                                color: Theme.dim
                                font.family: Theme.body
                                font.pixelSize: 12
                                font.weight: Font.Medium
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

                    // object type breadcrumb (sky)
                    Text {
                        id: typeIdLabel
                        Layout.leftMargin: editPage.colMargin
                        Layout.topMargin: 14
                        visible: !!session && !Theme.slate
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

                    // Web link preview card (shown above Title for Web link objects, matching design)
                    Rectangle {
                        id: webHeaderCard
                        visible: editPage.hasWebUrl
                        Layout.fillWidth: true
                        Layout.leftMargin: editPage.colMargin
                        Layout.rightMargin: editPage.colMargin
                        Layout.topMargin: 10
                        Layout.preferredHeight: webThumbBox.visible ? (editPage.webSidePanelShown ? 132 : 156) : 36
                        Layout.minimumHeight: Layout.preferredHeight
                        radius: Theme.radiusLg
                        color: Theme.card
                        border.width: 1
                        border.color: Theme.cardBorder
                        clip: true

                        ColumnLayout {
                            anchors.fill: parent
                            spacing: 0

                            Rectangle {
                                id: webThumbBox
                                visible: !!editPage.session && (editPage.session.coverAssetId.length > 0 || editPage.session.thumbnailUrl.length > 0)
                                Layout.fillWidth: true
                                Layout.fillHeight: true
                                color: Theme.ink
                                clip: true

                                // Soft ambient backdrop from the thumbnail + crisp contained preview
                                PrivateImage {
                                    inObject: true
                                    anchors.fill: parent
                                    fillMode: Image.PreserveAspectCrop
                                    sourceSize: Qt.size(240, 120)
                                    asynchronous: true
                                    cache: true
                                    opacity: 0.22
                                    source: webThumbMain.source
                                }
                                PrivateImage {
                                    id: webThumbMain
                                    inObject: true
                                    anchors.fill: parent
                                    anchors.margins: 4
                                    fillMode: Image.PreserveAspectFit
                                    sourceSize: Qt.size(960, 320)
                                    asynchronous: true
                                    cache: true
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
                                    anchors.margins: 8
                                    width: 28
                                    height: 28
                                    radius: Theme.radiusSm
                                    color: Qt.rgba(0, 0, 0, 0.55)
                                    border.width: 1
                                    border.color: Theme.line2
                                    visible: webCoverHover.hovered
                                    TintedIcon { anchors.centerIn: parent; name: "pencil"; size: 13; color: Theme.text }
                                    HoverHandler { cursorShape: Qt.PointingHandCursor }
                                    TapHandler { onTapped: webCoverPopup.open() }
                                }
                            }

                            Rectangle {
                                Layout.fillWidth: true
                                Layout.preferredHeight: 36
                                color: Theme.panel2

                                Rectangle {
                                    visible: webThumbBox.visible
                                    anchors.top: parent.top
                                    width: parent.width
                                    height: 1
                                    color: Theme.line
                                }

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

                    // Title (wraps across lines for long web link titles)
                    Item {
                        Layout.fillWidth: true
                        Layout.topMargin: 10
                        Layout.bottomMargin: 4
                        Layout.leftMargin: editPage.colMargin
                        Layout.rightMargin: editPage.colMargin
                        implicitHeight: Math.max(40, titleRow.implicitHeight)

                        RowLayout {
                            id: titleRow
                            anchors.fill: parent
                            spacing: 12

                            TextEdit {
                                id: titleInput
                                objectName: "titleInput"
                                Layout.fillWidth: true
                                Layout.preferredWidth: 1
                                Layout.alignment: Qt.AlignVCenter
                                wrapMode: TextEdit.WordWrap
                                text: session ? session.title : ""
                                color: Theme.text
                                selectionColor: Qt.rgba(0.75, 0.89, 1, 0.38)
                                selectedTextColor: Theme.text
                                font.family: Theme.display
                                font.pixelSize: editPage.webSidePanelShown ? 22 : 28
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

                            Button {
                                visible: !editPage.webSplitActive && !Theme.compact && editPage.width >= 620
                                text: Theme.caps(qsTr("COVER"))
                                small: true
                                onClicked: webCoverPopup.open()
                            }
                            Button {
                                visible: !editPage.webSplitActive && !Theme.compact && editPage.width >= 620
                                text: Theme.caps(qsTr("SHARE"))
                                small: true
                                onClicked: appWindow.showToast(qsTr("Link copied."))
                            }
                            Button {
                                visible: !editPage.webSplitActive
                                text: Theme.caps(qsTr("TRASH"))
                                small: true
                                onClicked: editPage.requestTrashCurrent()
                            }
                            IconButton {
                                // outline / backlinks rail: docked when wide, on demand when narrow
                                visible: !editPage.webSplitActive && editPage.narrowRail
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
                                visible: !editPage.webSplitActive
                                icon: "asterisk"
                                onClicked: if (session) ws.togglePinned(session.objectId)
                            }
                            IconButton {
                                visible: !editPage.webSplitActive
                                icon: "arrow-back"
                                onClicked: editPage.closeRequested()
                            }
                        }
                    }

                    // Web link OpenGraph description
                    Text {
                        visible: editPage.isWebObject && !!editPage.session && editPage.session.description.length > 0
                        Layout.fillWidth: true
                        Layout.leftMargin: editPage.colMargin
                        Layout.rightMargin: editPage.colMargin
                        Layout.bottomMargin: 10
                        text: editPage.session ? editPage.session.description : ""
                        color: Theme.dim
                        font.family: Theme.body
                        font.pixelSize: 13
                        wrapMode: Text.WordWrap
                        maximumLineCount: editPage.webSidePanelShown ? 3 : 4
                        elide: Text.ElideRight
                    }

                    // tags of the object (not on a tag itself)
                    TagRow {
                        visible: !!editPage.session && editPage.session.typeId !== "tag"
                        Layout.fillWidth: true
                        Layout.leftMargin: editPage.colMargin
                        Layout.rightMargin: editPage.colMargin
                        Layout.bottomMargin: 10
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
                                radius: 9
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

                    // Inline property table (Name | Value, "Empty" when blank)
                    PropertyTable {
                        id: propTable
                        property bool allowed: !!editPage.session && (Theme.slate || editPage.webSidePanelShown)
                        visible: allowed && entries.length > 0
                        Layout.fillWidth: true
                        Layout.fillHeight: false
                        Layout.preferredHeight: visible ? implicitHeight : 0
                        Layout.minimumHeight: Layout.preferredHeight
                        Layout.leftMargin: editPage.colMargin
                        Layout.rightMargin: editPage.colMargin
                        Layout.bottomMargin: 10
                        objectId: allowed ? editPage.session.objectId : ""
                        hideWebHeaderProps: editPage.isWebObject
                        labelWidth: editPage.webSidePanelShown ? 104 : 132
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
                                        text: Theme.caps(qsTr("AUDIO"))
                                        color: Theme.faint
                                        font.family: Theme.mono
                                        font.pixelSize: 11
                                        font.letterSpacing: Theme.track(1.2)
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
            visible: !!session && !editPage.embedMode && (!editPage.webSplitActive || layoutPrefs.notesRailVisible || editPage.narrowRail)
            // Wide windows dock the rail; narrow ones give it no width and float it over the page.
            Layout.preferredWidth: editPage.showDockedRail ? Theme.railWidth : 0
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
    } // outer ColumnLayout

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
