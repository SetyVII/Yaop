import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtQuick.Dialogs
import "."

// Quick capture (Ctrl+N, CAPTURE): pick any type — everything is an object, user types included —
// and type a title. Image opens a file picker instead (an image object needs its file).

Popup {
    id: capture

    property var appWindow: null
    signal requestClose()
    signal objectCreated(string objectId)

    x: (parent.width - width) / 2
    readonly property real topGap: Math.max(24, Math.min(110, parent.height * 0.15))
    y: topGap
    width: Math.min(480, parent.width - 80)
    height: Math.min(contentBox.implicitHeight + 2 * padding, parent.height - 2 * topGap)
    padding: 12
    modal: true
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside

    property string selectedTypeId: "note"
    readonly property bool mediaSelected: selectedTypeId === "image"
    // Re-read on every open and whenever a type is created: a binding over ws.allTypes() is
    // evaluated once, which hid the types created after start-up.
    property var kinds: []
    function refreshKinds() {
        const list = (typeof ws !== "undefined" && ws) ? ws.allTypes() : []
        kinds = list.map(t => ({ type: t.id, label: String(t.name || t.id).toUpperCase() }))
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

    // dim: the page behind goes ink-blue, not neutral grey
    Overlay.modal: Rectangle { color: Qt.rgba(0.02, 0.04, 0.15, 0.62) }

    background: Rectangle {
        radius: Theme.radiusLg
        color: Theme.sheet
        border.color: Theme.line2
        border.width: 1
    }

    ColumnLayout {
        id: contentBox
        width: capture.availableWidth // without it the column shrinks to its content (narrow chips, title)
        spacing: 16

        RowLayout {
            Layout.fillWidth: true
            spacing: 8

            Flow {
                Layout.fillWidth: true
                spacing: 6

                Repeater {
                    model: capture.kinds

                    delegate: Rectangle {
                        required property var modelData
                        width: chipLabel.implicitWidth + 26
                        height: 28
                        radius: 14
                        color: Theme.panel
                        border.color: capture.selectedTypeId === modelData.type ? Theme.ice : "transparent"
                        border.width: 1

                        Text {
                            id: chipLabel
                            anchors.centerIn: parent
                            text: modelData.label
                            color: capture.selectedTypeId === modelData.type ? Theme.ice : Theme.dim
                            font.family: Theme.mono
                            font.pixelSize: 10
                            font.letterSpacing: 1.0
                        }
                        TapHandler { onTapped: capture.selectedTypeId = modelData.type }
                    }
                }
            }

            IconButton {
                Layout.alignment: Qt.AlignTop
                icon: "x"
                onClicked: capture.requestClose()
            }
        }

        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: 40
            radius: Theme.radius
            color: Theme.panel

            TextInput {
                id: input
                anchors.fill: parent
                anchors.leftMargin: 10
                anchors.rightMargin: 10
                verticalAlignment: TextInput.AlignVCenter
                color: Theme.text
                font.family: Theme.body
                font.pixelSize: 14
                clip: true

                Text { anchors.verticalCenter: parent.verticalCenter; visible: input.length === 0; text: qsTr("Title…"); color: Theme.faint; font: input.font }

                Keys.onPressed: function(event) {
                    if (event.key === Qt.Key_Escape) {
                        event.accepted = true
                        requestClose()
                    } else if (event.key === Qt.Key_Return || event.key === Qt.Key_Enter) {
                        event.accepted = true
                        submit()
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
                onClicked: requestClose()
            }
            Button {
                small: true
                text: capture.mediaSelected ? qsTr("PICK FILE") : qsTr("CREATE")
                enabled: capture.mediaSelected || input.text.trim().length > 0
                onClicked: submit()
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
                appWindow.showToast(qsTr("Could not import file"))
                return
            }
            if (input.text.trim().length > 0)
                ws.renameObject(id, input.text.trim())
            requestClose()
            objectCreated(id)
        }
    }

    function submit() {
        if (mediaSelected) {
            mediaPicker.open()
            return
        }
        const title = input.text.trim()
        if (title.length === 0)
            return
        let id = ""
        if (selectedTypeId === "journal") {
            const iso = Qt.formatDate(new Date(), "yyyy-MM-dd")
            id = ws.journalFor(iso)
            if (!id || id.length === 0) {
                id = ws.createObject("journal", title)
            }
        } else if (selectedTypeId === "tag") {
            id = ws.ensureTagObject(title)
        } else {
            id = ws.createObject(selectedTypeId, title)
        }
        requestClose()
        if (id && id.length > 0) {
            objectCreated(id)
            appWindow.showToast(qsTr("Created · syncing"))
        }
    }
}
