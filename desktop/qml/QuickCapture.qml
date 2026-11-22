import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtQuick.Dialogs
import "."

// Quick create (Ctrl+N) matching docs/design/mockup/QuickCreate.dc.html:
// Sharp 680px #0A0A0A dialog, active type button in input row (Tab cycles types),
// type chips grid, live object preview box with the chosen type's properties,
// Enter creates & opens, Ctrl+Enter creates & keeps creating!
Popup {
    id: capture

    property var appWindow: null
    signal requestClose()
    signal objectCreated(string objectId)

    x: (parent.width - width) / 2
    y: Math.max(24, Math.min(84, parent.height * 0.10))
    width: Math.min(680, parent.width - 64)
    height: Math.min(contentBox.implicitHeight, parent.height - 2 * y)
    padding: 0
    modal: true
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside

    property string selectedTypeId: "note"
    readonly property bool mediaSelected: selectedTypeId === "image"
    property var kinds: []

    function refreshKinds() {
        const list = (typeof ws !== "undefined" && ws) ? ws.allTypes() : []
        kinds = list.map(t => ({
            type: t.id,
            label: String(t.name || t.id),
            color: (Theme.typeIcon(t.id) !== "shape") ? String(Theme.typeColor(t.id))
                 : (t.color && String(t.color).startsWith("#")) ? String(t.color)
                 : (t.color && Theme.paletteHex[t.color]) ? String(Theme.paletteColor(t.color))
                 : String(Theme.typeColor(t.id))
        }))
    }
    function cycleType() {
        if (kinds.length === 0) return
        let idx = 0
        for (let i = 0; i < kinds.length; i++) if (kinds[i].type === selectedTypeId) { idx = i; break }
        selectedTypeId = kinds[(idx + 1) % kinds.length].type
    }

    readonly property var selectedKindObj: {
        for (let i = 0; i < kinds.length; i++) if (kinds[i].type === selectedTypeId) return kinds[i]
        return { type: "note", label: qsTr("Note"), color: String(Theme.note) }
    }
    readonly property var previewProps: {
        if (!ws || !selectedTypeId) return []
        return ws.typeProperties(selectedTypeId).filter(p => !p.system).slice(0, 4)
    }

    Connections {
        target: typeof ws !== "undefined" ? ws : null
        function onTypesChanged() { capture.refreshKinds() }
    }

    onVisibleChanged: {
        if (visible) {
            refreshKinds()
            if (kinds.length > 0 && !kinds.some(k => k.type === selectedTypeId))
                selectedTypeId = kinds[0].type
            input.text = ""
            input.forceActiveFocus()
        }
    }

    Overlay.modal: Rectangle { color: Theme.scrim }

    background: Rectangle {
        radius: Theme.radiusLg
        color: Theme.sheet
        border.color: Theme.line2
        border.width: 1
    }

    ColumnLayout {
        id: contentBox
        width: capture.width
        spacing: 0

        // Top input bar (60px — QuickCreate.dc.html)
        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: 60
            color: "transparent"

            RowLayout {
                anchors.fill: parent
                anchors.leftMargin: 18
                anchors.rightMargin: 18
                spacing: 12

                // Active type pill (click or Tab cycles)
                Rectangle {
                    implicitWidth: ctRow.implicitWidth + 18; implicitHeight: 30
                    color: Theme.accent
                    Row {
                        id: ctRow; anchors.centerIn: parent; spacing: 7
                        TypeBadge {
                            anchors.verticalCenter: parent.verticalCenter
                            typeId: capture.selectedTypeId
                            size: 16
                            overrideColor: Theme.accentInk
                        }
                        Text {
                            anchors.verticalCenter: parent.verticalCenter
                            text: capture.selectedKindObj.label
                            color: Theme.accentInk; font.family: Theme.body; font.pixelSize: 13; font.weight: Font.Medium
                        }
                    }
                    HoverHandler { cursorShape: Qt.PointingHandCursor }
                    TapHandler { onTapped: capture.cycleType() }
                }

                TextInput {
                    id: input
                    Layout.fillWidth: true
                    verticalAlignment: TextInput.AlignVCenter
                    color: Theme.text
                    font.family: Theme.body
                    font.pixelSize: 19
                    clip: true

                    Text {
                        anchors.verticalCenter: parent.verticalCenter
                        visible: input.length === 0
                        text: capture.selectedTypeId === "web" ? qsTr("Paste a URL or title…") : qsTr("Title of new object…")
                        color: Theme.label; font: input.font
                    }

                    Keys.onPressed: function(event) {
                        if (event.key === Qt.Key_Escape) {
                            event.accepted = true
                            requestClose()
                        } else if (event.key === Qt.Key_Tab) {
                            event.accepted = true
                            capture.cycleType()
                        } else if (event.key === Qt.Key_Return || event.key === Qt.Key_Enter) {
                            event.accepted = true
                            submit(!!(event.modifiers & Qt.ControlModifier))
                        }
                    }
                }

                Rectangle {
                    implicitWidth: swSearchLbl.implicitWidth + 16; implicitHeight: 26
                    color: swSHov.hovered ? Theme.panel2 : "transparent"
                    border.width: 1; border.color: Theme.line2
                    Text { id: swSearchLbl; anchors.centerIn: parent; text: Theme.caps(qsTr("CTRL K · SEARCH")); color: Theme.dim; font.family: Theme.mono; font.pixelSize: 10 }
                    HoverHandler { id: swSHov; cursorShape: Qt.PointingHandCursor }
                    TapHandler { onTapped: { requestClose(); if (capture.appWindow) capture.appWindow.paletteOpen = true } }
                }
            }

            Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.line }
        }

        // Body: Type chips + Live object preview
        ColumnLayout {
            Layout.fillWidth: true
            Layout.margins: 18
            spacing: 14

            Text {
                text: Theme.caps(qsTr("TAB SWITCHES TYPE"))
                color: Theme.label; font.family: Theme.mono; font.pixelSize: 11
            }

            Flow {
                Layout.fillWidth: true
                spacing: 6
                Repeater {
                    model: capture.kinds
                    delegate: Rectangle {
                        required property var modelData
                        readonly property bool on: capture.selectedTypeId === modelData.type
                        implicitWidth: kRow.implicitWidth + 18; implicitHeight: 30
                        color: on ? Theme.accent : kHov.hovered ? Theme.panel2 : "transparent"
                        border.width: 1; border.color: on ? Theme.accent : Theme.line2
                        Row {
                            id: kRow; anchors.centerIn: parent; spacing: 7
                            TypeBadge {
                                anchors.verticalCenter: parent.verticalCenter
                                typeId: modelData.type
                                size: 15
                                overrideColor: on ? Theme.accentInk : "transparent"
                            }
                            Text {
                                anchors.verticalCenter: parent.verticalCenter
                                text: modelData.label
                                color: on ? Theme.accentInk : Theme.dim
                                font.family: Theme.body; font.pixelSize: 13
                            }
                        }
                        HoverHandler { id: kHov; cursorShape: Qt.PointingHandCursor }
                        TapHandler { onTapped: { capture.selectedTypeId = modelData.type; input.forceActiveFocus() } }
                    }
                }
            }

            // Live preview box (QuickCreate.dc.html)
            Rectangle {
                Layout.fillWidth: true
                implicitHeight: pvCol.implicitHeight + 32
                color: "transparent"
                border.width: 1; border.color: Theme.line

                ColumnLayout {
                    id: pvCol
                    anchors.left: parent.left; anchors.right: parent.right; anchors.top: parent.top
                    anchors.margins: 16; spacing: 0

                    Text {
                        text: Theme.caps(capture.selectedKindObj.label + " · " + qsTr("PREVIEW"))
                        color: capture.selectedKindObj.color
                        font.family: Theme.mono; font.pixelSize: 11
                    }
                    Text {
                        Layout.topMargin: 8; Layout.bottomMargin: 10
                        Layout.fillWidth: true
                        text: input.text.trim().length > 0 ? input.text.trim() : qsTr("Untitled")
                        color: input.text.trim().length > 0 ? Theme.text : Theme.label
                        font.family: Theme.display; font.pixelSize: 22; font.letterSpacing: -0.3
                        elide: Text.ElideRight
                    }
                    Repeater {
                        model: capture.previewProps
                        delegate: Rectangle {
                            required property var modelData
                            Layout.fillWidth: true; Layout.preferredHeight: 32
                            color: "transparent"
                            Rectangle { anchors.top: parent.top; width: parent.width; height: 1; color: Theme.lineSubtle }
                            RowLayout {
                                anchors.fill: parent; spacing: 10
                                Text { Layout.preferredWidth: 120; text: modelData.name || modelData.key; color: Theme.label; font.family: Theme.body; font.pixelSize: 13; elide: Text.ElideRight }
                                Text { Layout.fillWidth: true; text: modelData.kind === "checkbox" ? qsTr("No") : qsTr("Empty"); color: Theme.dim; font.family: Theme.body; font.pixelSize: 13 }
                            }
                        }
                    }
                }
            }
        }

        // Footer hints + Create button
        Rectangle {
            Layout.fillWidth: true; Layout.preferredHeight: 46
            color: "transparent"
            Rectangle { anchors.top: parent.top; width: parent.width; height: 1; color: Theme.line }
            RowLayout {
                anchors.fill: parent; anchors.leftMargin: 18; anchors.rightMargin: 18; spacing: 16
                Text { text: Theme.caps(qsTr("ENTER CREATE & OPEN")); color: Theme.label; font.family: Theme.mono; font.pixelSize: 10 }
                Text { text: Theme.caps(qsTr("CTRL ENTER KEEP CREATING")); color: Theme.label; font.family: Theme.mono; font.pixelSize: 10 }
                Item { Layout.fillWidth: true }
                Button {
                    small: true; primary: true
                    text: capture.mediaSelected ? qsTr("Pick file") : qsTr("Create")
                    enabled: capture.mediaSelected || input.text.trim().length > 0
                    onClicked: submit(false)
                }
            }
        }
    }

    FileDialog {
        id: mediaPicker
        title: qsTr("Pick a file")
        nameFilters: [qsTr("Images (*.png *.jpg *.jpeg *.webp *.gif *.bmp)")]
        onAccepted: {
            const assetId = ws.importFileAsAsset(selectedFile.toString().replace("file://", ""))
            const id = assetId.length > 0 ? ws.ensureMediaObject(assetId) : ""
            if (id.length === 0) {
                if (appWindow) appWindow.showToast(qsTr("Could not import file"))
                return
            }
            if (input.text.trim().length > 0)
                ws.renameObject(id, input.text.trim())
            requestClose()
            objectCreated(id)
            if (appWindow) appWindow.openObject(id)
        }
    }

    function submit(keepOpen) {
        if (mediaSelected) {
            mediaPicker.open()
            return
        }
        const title = input.text.trim()
        if (title.length === 0 || !ws) return
        let id = ""
        if (selectedTypeId === "journal") {
            const iso = Qt.formatDate(new Date(), "yyyy-MM-dd")
            id = ws.journalFor(iso)
            if (!id || id.length === 0) id = ws.createObject("journal", title)
        } else if (selectedTypeId === "tag") {
            id = ws.ensureTagObject(title)
        } else if (selectedTypeId === "web") {
            const looksUrl = /^https?:\/\//i.test(title) || /^[^\s]+\.[a-z]{2,}(\/.*)?$/i.test(title)
            if (looksUrl) {
                const fullUrl = /^https?:\/\//i.test(title) ? title : "https://" + title
                id = ws.createWebLinkFromUrl(fullUrl)
            } else {
                id = ws.createObject("web", title)
            }
        } else {
            id = ws.createObject(selectedTypeId, title)
        }
        if (id && id.length > 0) {
            objectCreated(id)
            if (keepOpen) {
                input.text = ""
                input.forceActiveFocus()
                if (appWindow) appWindow.showToast(qsTr("Created “%1”").arg(title))
            } else {
                requestClose()
                if (appWindow) appWindow.openObject(id)
            }
        }
    }
}
