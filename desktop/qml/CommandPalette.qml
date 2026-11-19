import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "."

// Command palette (Ctrl+K): search objects, jump, create.

Popup {
    id: palette

    property var appWindow: null
    property bool paneMode: false
    signal openObjectRequested(string objectId)
    signal openInPaneRequested(string objectId)
    signal requestClose()

    x: (parent.width - width) / 2
    y: Math.max(16, Math.min(96, parent.height * 0.12))
    width: Math.min(560, parent.width - 80)
    height: Math.min(420, parent.height - 2 * y)
    padding: 1
    modal: true
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside

    property string query: ""
    readonly property var results: {
        if (!ws)
            return []
        if (query.trim().length === 0)
            return ws.recentObjects(10)
        return ws.searchObjects(query.trim(), 12)
    }

    onVisibleChanged: {
        if (visible) {
            query = ""
            searchInput.text = ""
            searchInput.forceActiveFocus()
        }
    }

    // dim: the page behind goes ink-blue, not neutral grey
    Overlay.modal: Rectangle { color: Theme.scrim }

    background: Rectangle {
        radius: Theme.radiusLg
        color: Theme.sheet
        border.color: Theme.line2
        border.width: 1
    }

    contentItem: ColumnLayout {
        spacing: 0

        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: 52
            radius: Theme.radiusLg - 1
            color: Theme.panel2

            RowLayout {
                anchors.fill: parent
                anchors.leftMargin: 14
                anchors.rightMargin: 14
                spacing: 10

                Text { text: "⌘K"; color: Theme.faint; font.family: Theme.mono; font.pixelSize: 9 }

                TextInput {
                    id: searchInput
                    Layout.fillWidth: true
                    verticalAlignment: TextInput.AlignVCenter
                    color: Theme.text
                    font.family: Theme.body
                    font.pixelSize: 16
                    clip: true

                    Text { anchors.verticalCenter: parent.verticalCenter; visible: searchInput.length === 0; text: palette.paneMode ? qsTr("Open a second object in side pane…") : qsTr("Search or create…"); color: Theme.muted; font: searchInput.font }

                    onTextEdited: palette.query = text

                    Keys.onPressed: function(event) {
                        if (event.key === Qt.Key_Escape) {
                            event.accepted = true
                            requestClose()
                        } else if (event.key === Qt.Key_Return || event.key === Qt.Key_Enter) {
                            event.accepted = true
                            const usePane = palette.paneMode || (event.modifiers & Qt.ShiftModifier)
                            if (palette.results.length > 0) {
                                if (usePane) palette.openInPaneRequested(palette.results[0].id)
                                else palette.openObjectRequested(palette.results[0].id)
                                requestClose()
                            } else if (text.trim().length > 0) {
                                // create on the fly
                                const id = ws.createObject("note", text.trim())
                                requestClose()
                                if (id.length > 0) {
                                    if (usePane) palette.openInPaneRequested(id)
                                    else palette.openObjectRequested(id)
                                }
                            }
                        }
                    }
                }
            }

            Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.line }
        }

        ListView {
            id: resultsList
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            spacing: 2
            model: palette.results

            delegate: ItemDelegate {
                id: resultRow
                required property var modelData
                width: resultsList.width
                height: 44

                background: Rectangle {
                    color: parent.hovered ? Theme.panel3 : "transparent"
                }

                contentItem: RowLayout {
                    anchors.fill: parent
                    anchors.leftMargin: 16
                    anchors.rightMargin: 16
                    spacing: 10

                    TypeBadge {
                        typeId: modelData.typeId !== undefined ? modelData.typeId : ""
                        emoji: modelData.iconEmoji || ""
                        size: 22
                    }
                    Text {
                        Layout.fillWidth: true
                        text: modelData.title
                        color: Theme.text
                        font.family: Theme.body
                        font.pixelSize: 13
                        elide: Text.ElideRight
                    }
                    Text {
                        text: Theme.typeLabel(modelData.typeId !== undefined ? modelData.typeId : "")
                        color: Theme.faint
                        font.family: Theme.mono
                        font.pixelSize: 11
                    }
                    IconButton {
                        visible: Boolean(Theme.slate && (resultRow.hovered || palette.paneMode))
                        icon: "layout-columns"
                        onClicked: {
                            palette.openInPaneRequested(modelData.id)
                            requestClose()
                        }
                        ToolTip.visible: hovered; ToolTip.text: qsTr("Open in side pane"); ToolTip.delay: 400
                    }
                }

                onClicked: {
                    if (palette.paneMode || (ws && (ws.keyModifiers() & Qt.ShiftModifier)))
                        palette.openInPaneRequested(modelData.id)
                    else
                        palette.openObjectRequested(modelData.id)
                    requestClose()
                }
            }
        }

        // create row when no results
        Rectangle {
            visible: palette.query.trim().length > 0 && palette.results.length === 0
            Layout.fillWidth: true
            Layout.preferredHeight: 48
            color: Theme.panel2

            RowLayout {
                anchors.fill: parent
                anchors.leftMargin: 16
                spacing: 10

                Text { text: "＋"; color: Theme.ice; font.family: Theme.mono; font.pixelSize: 12 }
                Text {
                    Layout.fillWidth: true
                    text: qsTr("Create note “%1”").arg(palette.query.trim())
                    color: Theme.dim
                    font.family: Theme.body
                    font.pixelSize: 12
                    elide: Text.ElideRight
                }
                Text {
                    text: "↵"
                    color: Theme.faint
                    font.family: Theme.mono
                    font.pixelSize: 11
                }
            }

            HoverHandler { cursorShape: Qt.PointingHandCursor }
            TapHandler {
                onTapped: {
                    const id = ws.createObject("note", palette.query.trim())
                    requestClose()
                    if (id.length > 0)
                        palette.openObjectRequested(id)
                }
            }
        }
    }
}
