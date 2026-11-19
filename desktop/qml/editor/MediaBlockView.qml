import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import YAOP.Core 1.0
import "../"

// Media block: image (async asset + caption + width), audio (ffplay transport),
// pdf (page renderer + persisted reading position).

Item {
    id: mb

    property var view
    property int row
    property string blockId
    property string blockType: "image"
    property string assetId
    property string assetPath
    property real widthPercent: 1.0
    property real durationSeconds: 0
    property int pageCount: 1
    property int lastReadPage: 1
    property string fileName
    property string captionHtml: ""

    readonly property bool assetReady: assetPath.length > 0

    // Enter / Space in block mode: audio plays or stops; image and PDF open their object.
    function activate() {
        if (blockType === "audio") {
            if (playback.playing) playback.stop()
            else playback.play()
        } else {
            openImageObject()
        }
        return false
    }

    function showLarge() {
        if (!mb.assetReady)
            return
        lightboxLoader.active = true
        lightboxLoader.item.show("file://" + mb.assetPath, mb.captionHtml)
    }

    Loader {
        id: lightboxLoader
        active: false
        sourceComponent: ImageLightbox {
            onClosed: Qt.callLater(() => { lightboxLoader.active = false })
        }
    }

    function openImageObject() {
        if (!mb.view || mb.assetId.length === 0)
            return
        const id = (typeof ws !== "undefined" && ws)
                   ? ws.ensureMediaObject(mb.assetId)
                   : ("media_" + mb.assetId)
        if (id && id.length > 0)
            mb.view.openObjectRequested(id)
    }

    // Height of the image split row: the picture's own aspect at the block width, capped at
    // 520 so a tall image does not take the whole page. The size comes from the file header
    // (synchronous), never from the async-decoded image: a height that changes when the decode
    // lands makes the ListView keep stale delegate positions and blocks overlap. A fixed pane
    // left empty bands above and below wide images that clicked as the image.
    readonly property size imageFileSize: (blockType === "image" && assetReady && typeof ws !== "undefined" && ws)
                                          ? ws.imageFileSize(assetPath) : Qt.size(0, 0)
    readonly property real imagePaneH: {
        if (blockType !== "image")
            return 0
        if (imageFileSize.width <= 0 || imageFileSize.height <= 0)
            return 160
        const fitW = Math.max(1, contentCol.width * Math.min(1, mb.widthPercent) - 8)
        const natural = Math.min(imageFileSize.height, fitW * imageFileSize.height / imageFileSize.width)
        // The side pane needs room for its controls when the picture is a thin strip.
        return Math.round(Math.max(mb.panelOpen ? 240 : 48, Math.min(520, natural + 8)))
    }

    // Image side pane: closed by default so the picture gets the full width.
    property bool panelOpen: false
    property real panelWidth: 240
    function rememberPanel() {
        if (mb.view && mb.view.mediaPanels !== undefined && mb.blockId.length > 0)
            mb.view.mediaPanels[mb.blockId] = { open: mb.panelOpen, width: mb.panelWidth }
    }
    function setPanelOpen(open) {
        mb.panelOpen = open
        mb.rememberPanel()
    }
    Component.onCompleted: {
        const st = (mb.view && mb.view.mediaPanels) ? mb.view.mediaPanels[mb.blockId] : undefined
        if (st) {
            mb.panelWidth = st.width
            mb.panelOpen = st.open
        }
    }

    implicitHeight: contentCol.implicitHeight + 12

    AudioPlayback {
        id: playback
        assetId: mb.assetId
        durationSeconds: mb.durationSeconds > 0 ? mb.durationSeconds : 0
        path: mb.assetPath
        onPlayingChanged: {
            if (playing)
                mb.view.session.setFocusedBlock(mb.blockId, true)
        }
    }

    // Assets finish downloading asynchronously; refresh assetPath when ready.
    Connections {
        target: typeof ws !== "undefined" ? ws : null
        function onAssetReady(assetId, localPath) {
            if (assetId === mb.assetId && mb.view) {
                // nudge the cached role through the model data path
                mb.view.session.refreshAssetsFor(mb.assetId)
            }
        }
    }

    ColumnLayout {
        id: contentCol
        anchors.left: parent.left
        anchors.leftMargin: 2
        anchors.right: parent.right
        anchors.rightMargin: 26
        spacing: 6

        // ---------------- image: big preview; click slides the editor pane in ----------------
        Item {
            id: imageSplit
            visible: blockType === "image"
            Layout.fillWidth: true
            Layout.preferredHeight: mb.imagePaneH

            // Animated width actually taken by the pane (0 when closed).
            property real shownW: mb.panelOpen ? Math.min(mb.panelWidth, width * 0.6) : 0
            Behavior on shownW {
                enabled: !splitDrag.active
                NumberAnimation { duration: 220 * Theme.motion; easing.type: Easing.OutCubic }
            }
            readonly property real gap: Math.min(8, shownW)

            // big preview pane
            Rectangle {
                id: imgPane
                anchors.left: parent.left
                anchors.top: parent.top
                anchors.bottom: parent.bottom
                width: parent.width - imageSplit.shownW - imageSplit.gap
                radius: Theme.radius
                color: Theme.panel
                clip: true

                PrivateImage {
                    inObject: true
                    id: imageItem
                    anchors.centerIn: parent
                    // Fit INSIDE the fixed pane on both axes: a tall image used to overflow it and
                    // paint over the blocks below.
                    width: Math.max(1, Math.min(parent.width * mb.widthPercent - 8, parent.width - 8))
                    height: Math.max(1, parent.height - 8)
                    visible: mb.assetReady && status === Image.Ready
                    fillMode: Image.PreserveAspectFit
                    source: mb.assetReady ? "file://" + mb.assetPath : ""
                    sourceSize.width: 2400
                    smooth: true
                    // Cached so a block rebuilt by a structural edit shows the picture at once
                    // instead of flashing empty while it decodes again.
                    cache: true
                    asynchronous: true
                }

                Text {
                    visible: !imageItem.visible
                    anchors.centerIn: parent
                    text: mb.assetId.length > 0 ? qsTr("downloading image…") : qsTr("missing asset")
                    color: Theme.faint
                    font.family: Theme.mono
                    font.pixelSize: 11
                }

                // Pane toggle hint: shown on hover or while the pane is open.
                Rectangle {
                    id: panelToggle
                    anchors.top: parent.top
                    anchors.right: parent.right
                    anchors.margins: 8
                    width: 26
                    height: 26
                    radius: Theme.radius
                    color: toggleHover.hovered ? Theme.panel3 : Theme.panel2
                    opacity: (imgHover.hovered || mb.panelOpen) ? 0.95 : 0
                    Behavior on opacity { NumberAnimation { duration: Theme.fast } }
                    TintedIcon {
                        anchors.centerIn: parent
                        name: "layout-sidebar-right"
                        size: 14
                        color: mb.panelOpen ? Theme.accent : Theme.muted
                    }
                    HoverHandler { id: toggleHover; cursorShape: Qt.PointingHandCursor }
                    TapHandler { onTapped: mb.setPanelOpen(!mb.panelOpen) }
                }

                // Click: view it large. Ctrl/Cmd+click: open the image object. The corner button
                // toggles the caption pane.
                HoverHandler { id: imgHover; cursorShape: Qt.PointingHandCursor }
                TapHandler {
                    id: imgTap
                    onTapped: (eventPoint) => {
                        const p = eventPoint.position
                        if (p.x >= panelToggle.x && p.x <= panelToggle.x + panelToggle.width
                                && p.y >= panelToggle.y && p.y <= panelToggle.y + panelToggle.height)
                            return
                        if (imgTap.point.modifiers & (Qt.ControlModifier | Qt.MetaModifier))
                            mb.openImageObject()
                        else
                            mb.showLarge()
                    }
                }
            }

            // Drag handle between preview and pane: resizes the pane.
            Item {
                id: splitHandle
                visible: imageSplit.shownW > 0
                x: imgPane.width
                width: imageSplit.gap
                height: parent.height
                Rectangle {
                    anchors.centerIn: parent
                    width: 2
                    height: 36
                    radius: 1
                    color: (splitHover.hovered || splitDrag.active) ? Theme.accent : Theme.line2
                }
                HoverHandler { id: splitHover; cursorShape: Qt.SplitHCursor }
                DragHandler {
                    id: splitDrag
                    target: null
                    xAxis.enabled: true
                    yAxis.enabled: false
                    property real startW: 0
                    onActiveChanged: {
                        if (active)
                            startW = imageSplit.shownW
                        else
                            mb.rememberPanel()
                    }
                    onTranslationChanged: {
                        if (active)
                            mb.panelWidth = Math.max(180, Math.min(imageSplit.width * 0.6,
                                                                   startW - translation.x))
                    }
                }
            }

            // editor pane (slides in from the right)
            Item {
                anchors.right: parent.right
                anchors.top: parent.top
                anchors.bottom: parent.bottom
                width: imageSplit.shownW
                visible: width > 0.5
                clip: true

            Rectangle {
                anchors.left: parent.left
                anchors.top: parent.top
                anchors.bottom: parent.bottom
                width: Math.min(mb.panelWidth, imageSplit.width * 0.6)
                radius: Theme.radius
                color: Theme.panel

                ColumnLayout {
                    anchors.fill: parent
                    anchors.margins: 10
                    spacing: 8

                    Text {
                        text: Theme.caps(qsTr("IMAGE"))
                        color: Theme.faint
                        font.family: Theme.mono
                        font.pixelSize: 11
                        font.letterSpacing: Theme.track(1.2)
                    }
                    Text {
                        visible: mb.fileName.length > 0
                        text: mb.fileName
                        color: Theme.dim
                        font.family: Theme.mono
                        font.pixelSize: 11
                        elide: Text.ElideMiddle
                        Layout.fillWidth: true
                    }

                    RowLayout {
                        spacing: 6
                        Text {
                            text: Theme.caps(qsTr("WIDTH"))
                            color: Theme.faint
                            font.family: Theme.mono
                            font.pixelSize: 11
                            font.letterSpacing: Theme.track(1)
                        }
                        Repeater {
                            model: [0.75, 1.0]
                            delegate: Rectangle {
                                Layout.preferredWidth: 30
                                Layout.preferredHeight: 18
                                radius: 9
                                color: wArea.hovered ? Theme.panel3 : Theme.panel2
                                Text {
                                    anchors.centerIn: parent
                                    text: Math.round(modelData * 100) + "%"
                                    color: Theme.muted
                                    font.family: Theme.mono
                                    font.pixelSize: 11
                                }
                                HoverHandler { id: wArea }
                                TapHandler { onTapped: view.session.setComponentSize(mb.row, modelData) }
                            }
                        }
                    }

                    RowLayout {
                        spacing: 6
                        Button {
                            text: Theme.caps(qsTr("OPEN"))
                            small: true
                            onClicked: mb.openImageObject()
                        }
                        Button {
                            text: Theme.caps(qsTr("REMOVE"))
                            small: true
                            onClicked: {
                                if (mb.view && mb.view.session)
                                    mb.view.session.removeBlockAt(mb.row)
                            }
                        }
                    }

                    Text {
                        text: Theme.caps(qsTr("CAPTION"))
                        color: Theme.faint
                        font.family: Theme.mono
                        font.pixelSize: 11
                        font.letterSpacing: Theme.track(1)
                    }
                    TextEdit {
                        id: imgCaptionEdit
                        Layout.fillWidth: true
                        Layout.fillHeight: true
                        visible: mb.assetReady
                        textFormat: TextEdit.RichText
                        wrapMode: TextEdit.Wrap
                        padding: 0
                        leftPadding: 4
                        color: Theme.dim
                        selectionColor: Qt.rgba(0.75, 0.89, 1, 0.38)
                        selectedTextColor: Theme.text
                        font.family: Theme.body
                        font.pixelSize: 12
                        clip: true
                        selectByMouse: true

                        property bool captionLoading: true
                        Component.onCompleted: {
                            imgCaptionEdit.text = mb.captionHtml
                            captionLoading = false
                        }
                        onTextChanged: imgCapTimer.restart()
                        onActiveFocusChanged: {
                            view.setFocusedBlock(mb.blockId, activeFocus)
                            if (activeFocus) {
                                // Tell the workspace this object is being edited: a pull that merges
                                // remote changes must not reload the block under the caret.
                                view.session.documentFocused(mb.row, imgCaptionEdit.textDocument, 0)
                            } else if (!captionLoading) {
                                imgCapTimer.stop()
                                view.session.setCaption(mb.row, imgCaptionEdit.textDocument, 0)
                                view.session.documentUnfocused(mb.row, imgCaptionEdit.textDocument, 0)
                            }
                        }
                        // A structural edit elsewhere rebuilds this block: never lose pending text.
                        Component.onDestruction: {
                            if (!captionLoading && imgCapTimer.running && mb.view && mb.view.session)
                                mb.view.session.setCaption(mb.row, imgCaptionEdit.textDocument, 0)
                        }

                        Timer {
                            id: imgCapTimer
                            interval: 200
                            onTriggered: {
                                if (!imgCaptionEdit.captionLoading)
                                    view.session.setCaption(mb.row, imgCaptionEdit.textDocument, 0)
                            }
                        }
                        Connections {
                            target: mb
                            function onCaptionHtmlChanged() {
                                if (!imgCaptionEdit.captionLoading && !imgCaptionEdit.activeFocus)
                                    imgCaptionEdit.text = mb.captionHtml
                            }
                        }
                    }
                }
                }
            }
        }

        // caption below the player (audio only; image caption lives in the editor pane)
        TextEdit {
            id: captionEdit
            visible: blockType === "audio" && mb.assetReady
            Layout.fillWidth: true
            Layout.preferredHeight: visible ? Math.max(22, contentHeight + 4) : 0
            textFormat: TextEdit.RichText
            wrapMode: TextEdit.Wrap
            padding: 0
            leftPadding: 4
            color: Theme.dim
            selectionColor: Qt.rgba(0.75, 0.89, 1, 0.38)
            selectedTextColor: Theme.text
            font.family: Theme.body
            font.pixelSize: 12
            clip: true
            selectByMouse: true

            property bool captionLoading: true
            Component.onCompleted: {
                captionEdit.text = mb.captionHtml
                captionLoading = false
            }
            onTextChanged: capTimer.restart()
            onActiveFocusChanged: view.setFocusedBlock(mb.blockId, activeFocus)

            Timer {
                id: capTimer
                interval: 500
                onTriggered: {
                    if (!captionEdit.captionLoading)
                        view.session.setCaption(mb.row, captionEdit.textDocument, 0)
                }
            }

            Connections {
                target: mb
                function onCaptionHtmlChanged() {
                    if (!captionEdit.captionLoading && !captionEdit.activeFocus)
                        captionEdit.text = mb.captionHtml
                }
            }
        }

        // ---------------- audio ----------------
        Rectangle {
            visible: blockType === "audio"
            Layout.fillWidth: true
            Layout.preferredHeight: visible ? 76 : 0
            color: Theme.panel
            border.color: Theme.line
            border.width: 1

            RowLayout {
                anchors.fill: parent
                anchors.margins: 12
                spacing: 14

                Rectangle {
                    Layout.preferredWidth: 34
                    Layout.preferredHeight: 34
                    color: playMouse.hovered ? Theme.panel3 : Theme.panel2
                    border.color: Theme.line2
                    border.width: 1

                    Text {
                        anchors.centerIn: parent
                        text: playback.playing ? "◼" : "▶"
                        color: Theme.accent
                        font.family: Theme.mono
                        font.pixelSize: 13
                    }
                    HoverHandler { id: playMouse; cursorShape: Qt.PointingHandCursor }
                    TapHandler {
                        onTapped: {
                            if (playback.playing)
                                playback.stop()
                            else
                                playback.play()
                        }
                    }
                }

                ColumnLayout {
                    spacing: 4
                    Layout.fillWidth: true

                    Row {
                        spacing: 2
                        Repeater {
                            model: 44
                            delegate: Rectangle {
                                width: 2
                                color: (index % 9 === 0) ? Theme.accent : "#222226"
                                height: 8 + ((index * 7919) % 13)
                            }
                        }
                    }

                    RowLayout {
                        spacing: 8
                        Text {
                            text: Theme.caps(qsTr("AUDIO"))
                            color: Theme.faint
                            font.family: Theme.mono
                            font.pixelSize: 11
                            font.letterSpacing: Theme.track(1.2)
                        }
                        Text {
                            text: {
                                const s = Math.max(0, Math.round(playback.durationSeconds))
                                const m = Math.floor(s / 60)
                                return m + ":" + String(s % 60).padStart(2, "0")
                            }
                            color: Theme.muted
                            font.family: Theme.mono
                            font.pixelSize: 11
                        }
                        Item { Layout.fillWidth: true }
                        Text {
                            visible: !mb.assetReady
                            text: qsTr("downloading…")
                            color: Theme.faint
                            font.family: Theme.mono
                            font.pixelSize: 11
                        }
                    }
                }
            }
        }

        // ---------------- pdf ----------------
        Rectangle {
            visible: blockType === "pdf"
            Layout.fillWidth: true
            Layout.preferredHeight: visible ? 470 : 0
            color: "#060606"
            border.color: Theme.line
            border.width: 1

            ColumnLayout {
                anchors.fill: parent
                spacing: 0

                RowLayout {
                    Layout.fillWidth: true
                    Layout.preferredHeight: 30
                    Layout.leftMargin: 10
                    Layout.rightMargin: 8
                    spacing: 10

                    Text {
                        text: fileName.length > 0 ? fileName : qsTr("document.pdf")
                        color: Theme.media
                        font.family: Theme.mono
                        font.pixelSize: 11
                        elide: Text.ElideMiddle
                        Layout.fillWidth: true
                    }
                    Text {
                        text: qsTr("%1 / %2").arg(pdfRow.pageLabel).arg(mb.pageCount > 0 ? mb.pageCount : "?")
                        color: Theme.faint
                        font.family: Theme.mono
                        font.pixelSize: 11
                    }
                }

                Flickable {
                    id: pdfScrollArea
                    property int pageIdx: Math.max(0, (mb.lastReadPage || 1) - 1)
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    contentWidth: width
                    clip: true
                    boundsBehavior: Flickable.StopAtBounds
                    onPageIdxChanged: pdfRow.pageLabel = (pageIdx + 1)

                    PrivateImage {
                        inObject: true
                        id: pageImage
                        x: Math.max(0, (pdfScrollArea.width - width) / 2)
                        y: 8
                        readonly property real zoom: 2.0
                        width: Math.min(pdfScrollArea.width - 20, 620)
                        fillMode: Image.PreserveAspectFit
                        smooth: true
                        cache: false
                        source: (mb.blockType === "pdf" && mb.assetReady)
                                ? "image://pdfpage/" + encodeURIComponent(mb.assetId) + "/"
                                  + (pdfScrollArea.pageIdx + 1) + "/" + zoom
                                : ""
                        onStatusChanged: {
                            if (status === Image.Error)
                                pdfRow.pageLabel = "—"
                        }
                        onSourceSizeChanged: {
                            pdfScrollArea.contentHeight = implicitHeight + 20
                        }
                    }

                    Text {
                        visible: !mb.assetReady
                        anchors.centerIn: parent
                        text: qsTr("downloading pdf…")
                        color: Theme.faint
                        font.family: Theme.mono
                        font.pixelSize: 11
                    }

                    Item {
                        id: pdfRow
                        property string pageLabel: String(pdfScrollArea.pageIdx + 1)
                    }
                }

                RowLayout {
                    Layout.fillWidth: true
                    Layout.preferredHeight: 32
                    spacing: 8

                    Button {
                        Layout.leftMargin: 8
                        text: "<"
                        small: true
                        onClicked: {
                            const p = Math.max(0, pdfScrollArea.pageIdx - 1)
                            pdfScrollArea.pageIdx = p
                            view.session.setPdfPage(mb.row, p + 1)
                        }
                    }
                    Button {
                        text: ">"
                        small: true
                        onClicked: {
                            const max = Math.max(1, mb.pageCount) - 1
                            const p = Math.min(max, pdfScrollArea.pageIdx + 1)
                            pdfScrollArea.pageIdx = p
                            view.session.setPdfPage(mb.row, p + 1)
                        }
                    }
                    Text {
                        Layout.fillWidth: true
                        Layout.leftMargin: 6
                        text: qsTr("reading position syncs across devices")
                        color: Theme.faint
                        font.family: Theme.mono
                        font.pixelSize: 11
                    }
                    Item { Layout.preferredWidth: 8 }
                }
            }
        }
    }
}
