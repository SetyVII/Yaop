import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "."

// Quick capture (Ctrl+N): create note / task / book / journal entry.

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
    readonly property var kinds: {
        const list = (typeof ws !== "undefined" && ws) ? ws.allTypes() : []
        const out = []
        for (let i = 0; i < list.length; ++i) {
            const t = list[i]
            if (t.id === "image" || t.id === "audio" || t.id === "pdf")
                continue
            out.push({ type: t.id, label: String(t.name || t.id).toUpperCase() })
        }
        if (out.length > 0)
            return out
        return [
            { type: "note", label: qsTr("NOTE") },
            { type: "task", label: qsTr("TASK") },
            { type: "journal", label: qsTr("JOURNAL") },
            { type: "book", label: qsTr("BOOK") }
        ]
    }

    onVisibleChanged: {
        if (visible) {
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
                        width: 26 + modelData.label.length * 7.5
                        height: 28
                        radius: 14
                        color: Theme.panel
                        border.color: capture.selectedTypeId === modelData.type ? Theme.ice : "transparent"
                        border.width: 1

                        Text {
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
                text: qsTr("CREATE")
                small: true
                enabled: input.text.trim().length > 0
                onClicked: submit()
            }
        }
    }

    function submit() {
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
