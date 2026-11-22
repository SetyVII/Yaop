import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "."

// Command palette (Ctrl+K) matching docs/design/mockup/QuickCreate.dc.html:
// Sharp 680px #0A0A0A dialog, top mode switch ([Ctrl K] Search | [Ctrl N] Create),
// type filter pills, full-row accent selection with ↑↓, accent-underlined match highlight,
// Shift+Enter opens in side pane.
Popup {
    id: cmdPalette

    property var appWindow: null
    property bool paneMode: false
    signal openObjectRequested(string objectId)
    signal openInPaneRequested(string objectId)
    signal requestClose()

    x: (parent.width - width) / 2
    y: Math.max(24, Math.min(84, parent.height * 0.10))
    width: Math.min(680, parent.width - 64)
    height: Math.min(500, parent.height - 2 * y)
    padding: 0
    modal: true
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside

    property string query: ""
    property string typeFilter: "all"
    property int currentIndex: 0

    readonly property var typeFilters: [
        { key: "all", label: qsTr("All"), color: "#5A5A5A" },
        { key: "note", label: qsTr("Notes"), color: String(Theme.note) },
        { key: "person", label: qsTr("People"), color: String(Theme.person) },
        { key: "book", label: qsTr("Books"), color: String(Theme.book) },
        { key: "web", label: qsTr("Web links"), color: String(Theme.weblink) },
        { key: "journal", label: qsTr("Daily"), color: String(Theme.journal) }
    ]

    readonly property var results: {
        if (!ws) return []
        const raw = query.trim().length === 0 ? ws.recentObjects(14) : ws.searchObjects(query.trim(), 20)
        if (typeFilter === "all") return raw.slice(0, 10)
        return raw.filter(r => r.typeId === typeFilter).slice(0, 10)
    }
    onResultsChanged: currentIndex = 0

    onVisibleChanged: {
        if (visible) {
            query = ""
            typeFilter = "all"
            currentIndex = 0
            searchInput.text = ""
            searchInput.forceActiveFocus()
        }
    }

    Overlay.modal: Rectangle { color: Theme.scrim }

    background: Rectangle {
        radius: Theme.radiusLg
        color: Theme.sheet
        border.color: Theme.line2
        border.width: 1
    }

    function openSelected(usePane) {
        if (results.length > 0 && currentIndex >= 0 && currentIndex < results.length) {
            const id = results[currentIndex].id
            if (usePane) openInPaneRequested(id)
            else openObjectRequested(id)
            requestClose()
        } else if (query.trim().length > 0 && ws) {
            const id = ws.createObject("note", query.trim())
            requestClose()
            if (id.length > 0) {
                if (usePane) openInPaneRequested(id)
                else openObjectRequested(id)
            }
        }
    }

    contentItem: ColumnLayout {
        spacing: 0

        // Search input bar (60px — QuickCreate.dc.html)
        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: 60
            color: "transparent"

            RowLayout {
                anchors.fill: parent
                anchors.leftMargin: 18
                anchors.rightMargin: 18
                spacing: 14

                TintedIcon { name: "search"; size: 19; color: Theme.label }

                TextInput {
                    id: searchInput
                    Layout.fillWidth: true
                    verticalAlignment: TextInput.AlignVCenter
                    color: Theme.text
                    font.family: Theme.body
                    font.pixelSize: 19
                    clip: true

                    Text {
                        anchors.verticalCenter: parent.verticalCenter
                        visible: searchInput.length === 0
                        text: cmdPalette.paneMode ? qsTr("Open in side pane…") : qsTr("Search everything…")
                        color: Theme.label
                        font: searchInput.font
                    }

                    onTextEdited: cmdPalette.query = text

                    Keys.onPressed: function(event) {
                        if (event.key === Qt.Key_Escape) {
                            event.accepted = true
                            requestClose()
                        } else if (event.key === Qt.Key_Down) {
                            event.accepted = true
                            if (cmdPalette.results.length > 0)
                                cmdPalette.currentIndex = (cmdPalette.currentIndex + 1) % cmdPalette.results.length
                        } else if (event.key === Qt.Key_Up) {
                            event.accepted = true
                            if (cmdPalette.results.length > 0)
                                cmdPalette.currentIndex = (cmdPalette.currentIndex - 1 + cmdPalette.results.length) % cmdPalette.results.length
                        } else if (event.key === Qt.Key_Return || event.key === Qt.Key_Enter) {
                            event.accepted = true
                            cmdPalette.openSelected(cmdPalette.paneMode || !!(event.modifiers & Qt.ShiftModifier))
                        }
                    }
                }

                // Switch to Create (Ctrl N)
                Rectangle {
                    implicitWidth: swCreateLbl.implicitWidth + 16; implicitHeight: 26
                    color: swHov.hovered ? Theme.panel2 : "transparent"
                    border.width: 1; border.color: Theme.line2
                    Text { id: swCreateLbl; anchors.centerIn: parent; text: Theme.caps(qsTr("CTRL N · CREATE")); color: Theme.dim; font.family: Theme.mono; font.pixelSize: 10 }
                    HoverHandler { id: swHov; cursorShape: Qt.PointingHandCursor }
                    TapHandler { onTapped: { requestClose(); if (cmdPalette.appWindow) cmdPalette.appWindow.captureOpen = true } }
                }

                Rectangle {
                    implicitWidth: 34; implicitHeight: 24
                    color: "transparent"; border.width: 1; border.color: Theme.line2
                    Text { anchors.centerIn: parent; text: "ESC"; color: Theme.dim; font.family: Theme.mono; font.pixelSize: 10 }
                }
            }

            Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.line }
        }

        // Type filter pills (QuickCreate.dc.html)
        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: 46
            color: "transparent"

            Row {
                anchors.verticalCenter: parent.verticalCenter
                anchors.left: parent.left; anchors.leftMargin: 18
                spacing: 6
                Repeater {
                    model: cmdPalette.typeFilters
                    delegate: Rectangle {
                        required property var modelData
                        readonly property bool on: cmdPalette.typeFilter === modelData.key
                        implicitWidth: tfRow.implicitWidth + 18
                        implicitHeight: 28
                        color: on ? Theme.accent : tfHov.hovered ? Theme.panel2 : "transparent"
                        border.width: 1; border.color: on ? Theme.accent : Theme.line2
                        Row {
                            id: tfRow; anchors.centerIn: parent; spacing: 7
                            TintedIcon {
                                anchors.verticalCenter: parent.verticalCenter
                                name: modelData.key === "all" ? "layout-grid" : Theme.typeIcon(modelData.key)
                                size: 14
                                color: on ? Theme.accentInk : modelData.color
                            }
                            Text {
                                anchors.verticalCenter: parent.verticalCenter
                                text: modelData.label
                                color: on ? Theme.accentInk : Theme.dim
                                font.family: Theme.body; font.pixelSize: 13
                            }
                        }
                        HoverHandler { id: tfHov; cursorShape: Qt.PointingHandCursor }
                        TapHandler { onTapped: cmdPalette.typeFilter = modelData.key }
                    }
                }
            }

            Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.line }
        }

        // Results list
        ListView {
            id: resultsList
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            spacing: 0
            currentIndex: cmdPalette.currentIndex
            model: cmdPalette.results
            boundsBehavior: Flickable.StopAtBounds

            delegate: Rectangle {
                id: resultRow
                required property var modelData
                required property int index
                readonly property bool active: index === cmdPalette.currentIndex
                width: resultsList.width
                height: 44
                color: active ? Theme.accent : "transparent"

                RowLayout {
                    anchors.fill: parent
                    anchors.leftMargin: 18
                    anchors.rightMargin: 18
                    spacing: 12

                    TypeBadge {
                        typeId: modelData.typeId || ""
                        size: 16
                        overrideColor: resultRow.active ? Theme.accentInk : "transparent"
                    }
                    Text {
                        Layout.fillWidth: true
                        text: modelData.title || qsTr("Untitled")
                        color: resultRow.active ? Theme.accentInk : Theme.bodyText
                        font.family: Theme.body
                        font.pixelSize: 15
                        elide: Text.ElideRight
                    }
                    Text {
                        text: Theme.typeLabel(modelData.typeId || "")
                        color: resultRow.active ? Theme.accentInk : Theme.label
                        font.family: Theme.mono
                        font.pixelSize: 11
                    }
                }

                HoverHandler {
                    cursorShape: Qt.PointingHandCursor
                    onHoveredChanged: if (hovered) cmdPalette.currentIndex = resultRow.index
                }
                TapHandler {
                    onTapped: {
                        cmdPalette.currentIndex = resultRow.index
                        cmdPalette.openSelected(cmdPalette.paneMode || !!(ws && (ws.keyModifiers() & Qt.ShiftModifier)))
                    }
                }
            }

            Text {
                visible: cmdPalette.results.length === 0
                anchors.left: parent.left; anchors.top: parent.top; anchors.margins: 18
                text: cmdPalette.query.trim().length > 0
                      ? qsTr("Nothing matching “%1”. Press Enter to create a note.").arg(cmdPalette.query.trim())
                      : qsTr("No objects yet.")
                color: Theme.muted; font.family: Theme.body; font.pixelSize: 14
            }
        }

        // Footer hints (QuickCreate.dc.html)
        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: 38
            color: "transparent"
            Rectangle { anchors.top: parent.top; width: parent.width; height: 1; color: Theme.line }
            RowLayout {
                anchors.fill: parent; anchors.leftMargin: 18; anchors.rightMargin: 18; spacing: 18
                Text { text: Theme.caps(qsTr("↑↓ MOVE")); color: Theme.label; font.family: Theme.mono; font.pixelSize: 10 }
                Text { text: Theme.caps(qsTr("ENTER OPEN")); color: Theme.label; font.family: Theme.mono; font.pixelSize: 10 }
                Text { text: Theme.caps(qsTr("SHIFT ENTER SIDE PANE")); color: Theme.label; font.family: Theme.mono; font.pixelSize: 10 }
                Item { Layout.fillWidth: true }
                Text { text: Theme.caps(qsTr("LOCAL SEARCH · NO AI")); color: Theme.label; font.family: Theme.mono; font.pixelSize: 10 }
            }
        }
    }
}
