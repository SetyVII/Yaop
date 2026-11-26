import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "."

// Keyboard sheet (Ctrl+/ or F1, docs/keyboard.md §6): first a short "how to move" guide, then
// every shortcut from the one keymap table (src/keymap.cpp), filtered as you type.
Popup {
    id: sheet

    x: (parent.width - width) / 2
    y: Math.max(16, Math.min(64, parent.height * 0.06))
    width: Math.min(760, parent.width - 48)
    height: parent.height - 2 * y
    padding: 0
    modal: true
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside

    property string query: ""

    // The six things to learn first, in the order you would use them.
    readonly property var guide: [
        { keys: ["today"], title: qsTr("Open your day"),
          text: qsTr("Opens today's daily note with the caret in it, ready to write.") },
        { keys: ["palette"], title: qsTr("Find anything"),
          text: qsTr("Type to search, ↑ ↓ to choose, Enter opens, Shift+Enter opens beside.") },
        { keys: [], labels: ["↑ ↓ ← →", "Enter"], title: qsTr("Move around"),
          text: qsTr("Arrows go to the nearest card, row, day or button that way; Enter opens it. Ctrl+Enter opens in a new tab.") },
        { keys: ["region.next"], title: qsTr("Change area"),
          text: qsTr("Jumps between the sidebar, the tabs, the page and the side pane. ← → at an edge does it too.") },
        { keys: ["back"], labels: ["Esc"], title: qsTr("Come back"),
          text: qsTr("Esc closes the innermost thing (a menu, a selection). Alt+← and Alt+→ go back and forward.") },
        { keys: [], labels: ["Esc", "↑ ↓", "Enter"], title: qsTr("Inside a note"),
          text: qsTr("Esc selects the whole block, arrows move between blocks, Enter edits again. Ctrl+. shows the block's actions.") }
    ]

    readonly property var sections: [
        { scope: "global", title: qsTr("Everywhere") },
        { scope: "list", title: qsTr("Pages, lists and the sidebar") },
        { scope: "editor", title: qsTr("Writing in a note") },
        { scope: "blocks", title: qsTr("Whole blocks selected (after Esc)") }
    ]

    function labelsOf(id) {
        const all = keymap.bindings("global")
        const b = all.find(x => x.id === id)
        return b ? b.labels : []
    }

    // Rows of one scope; Alt+1…9 collapse into one row.
    function rowsOf(scope) {
        const q = query.trim().toLowerCase()
        const out = []
        for (const b of keymap.bindings(scope)) {
            if (b.slateOnly && !Theme.modern) continue
            if (/^tab\.[2-9]$/.test(b.id)) continue
            const row = b.id === "tab.1"
                ? { title: qsTr("Go to tab 1 … 9 (9 = last)"), labels: [b.labels[0] + " … 9"] }
                : { title: b.title, labels: b.labels }
            if (q.length > 0 && row.title.toLowerCase().indexOf(q) < 0
                    && !row.labels.some(l => l.toLowerCase().indexOf(q) >= 0)) continue
            out.push(row)
        }
        return out
    }

    onAboutToShow: {
        query = ""
        filter.text = ""
        flick.contentY = 0
    }
    onOpened: filter.forceActiveFocus()

    Overlay.modal: Rectangle { color: Theme.scrim }

    background: Rectangle {
        radius: Theme.radiusLg
        color: Theme.sheet
        border.color: Theme.line2
        border.width: 1
    }

    component KeyCap: Rectangle {
        property string text: ""
        implicitWidth: capText.implicitWidth + 12
        implicitHeight: 22
        radius: Theme.radiusSm
        color: Theme.panel2
        border.width: 1
        border.color: Theme.line2
        Text {
            id: capText
            anchors.centerIn: parent
            text: parent.text
            color: Theme.text
            font.family: Theme.mono
            font.pixelSize: 12
        }
    }

    contentItem: ColumnLayout {
        spacing: 0

        // Title and filter
        ColumnLayout {
            Layout.fillWidth: true
            Layout.margins: 22
            Layout.bottomMargin: 14
            spacing: 6
            RowLayout {
                Layout.fillWidth: true
                Text {
                    Layout.fillWidth: true
                    text: qsTr("Keyboard")
                    color: Theme.text
                    font.family: Theme.body
                    font.pixelSize: 22
                    font.weight: Font.DemiBold
                }
                KeyCap { text: sheet.labelsOf("shortcuts").join("  ·  ") }
            }
            Text {
                Layout.fillWidth: true
                text: qsTr("Everything works without the mouse. Open this sheet any time.")
                color: Theme.muted
                font.family: Theme.body
                font.pixelSize: 13
                wrapMode: Text.WordWrap
            }
            Rectangle {
                Layout.fillWidth: true
                Layout.topMargin: 8
                implicitHeight: 38
                radius: Theme.radius
                color: Theme.panel
                border.width: 1
                border.color: filter.activeFocus ? Theme.line3 : Theme.line
                TextInput {
                    id: filter
                    objectName: "shortcutFilter"
                    anchors.fill: parent
                    anchors.leftMargin: 12
                    anchors.rightMargin: 12
                    verticalAlignment: TextInput.AlignVCenter
                    color: Theme.text
                    font.family: Theme.body
                    font.pixelSize: 14
                    clip: true
                    onTextEdited: sheet.query = text
                    Text {
                        anchors.verticalCenter: parent.verticalCenter
                        visible: filter.length === 0
                        text: qsTr("Filter: a word (tab, block, today) or a key (Ctrl+K)…")
                        color: Theme.label
                        font: filter.font
                    }
                    Keys.onPressed: (event) => {
                        const step = event.key === Qt.Key_Down ? 48 : event.key === Qt.Key_Up ? -48
                                   : event.key === Qt.Key_PageDown ? flick.height * 0.85
                                   : event.key === Qt.Key_PageUp ? -flick.height * 0.85 : 0
                        if (step === 0) return
                        event.accepted = true
                        const maxY = Math.max(0, flick.contentHeight - flick.height)
                        flick.contentY = Math.max(0, Math.min(maxY, flick.contentY + step))
                    }
                }
            }
        }

        Rectangle { Layout.fillWidth: true; Layout.preferredHeight: 1; color: Theme.line }

        Flickable {
            id: flick
            Layout.fillWidth: true
            Layout.fillHeight: true
            contentWidth: width
            contentHeight: body.implicitHeight + 40
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }

            ColumnLayout {
                id: body
                x: 22
                y: 18
                width: flick.width - 44
                spacing: 22

                // How to move: six steps
                ColumnLayout {
                    visible: sheet.query.trim().length === 0
                    Layout.fillWidth: true
                    spacing: 10
                    Text {
                        text: Theme.caps(qsTr("HOW TO MOVE"))
                        color: Theme.label
                        font.family: Theme.mono
                        font.pixelSize: 11
                        font.letterSpacing: Theme.track(0.5)
                    }
                    GridLayout {
                        Layout.fillWidth: true
                        columns: body.width > 560 ? 2 : 1
                        columnSpacing: 10
                        rowSpacing: 10
                        Repeater {
                            model: sheet.guide
                            delegate: Rectangle {
                                required property var modelData
                                required property int index
                                Layout.fillWidth: true
                                Layout.fillHeight: true
                                Layout.preferredHeight: stepCol.implicitHeight + 24
                                radius: Theme.radius
                                color: Theme.panel
                                border.width: 1
                                border.color: Theme.line
                                ColumnLayout {
                                    id: stepCol
                                    x: 12; y: 12
                                    width: parent.width - 24
                                    spacing: 6
                                    RowLayout {
                                        Layout.fillWidth: true
                                        spacing: 8
                                        Text {
                                            text: String(index + 1)
                                            color: Theme.ice
                                            font.family: Theme.mono
                                            font.pixelSize: 13
                                        }
                                        Text {
                                            Layout.fillWidth: true
                                            text: modelData.title
                                            color: Theme.text
                                            font.family: Theme.body
                                            font.pixelSize: 14
                                            font.weight: Font.Medium
                                            elide: Text.ElideRight
                                        }
                                    }
                                    Flow {
                                        Layout.fillWidth: true
                                        spacing: 6
                                        Repeater {
                                            model: {
                                                let caps = []
                                                for (const id of modelData.keys) caps = caps.concat(sheet.labelsOf(id))
                                                return caps.concat(modelData.labels || [])
                                            }
                                            delegate: KeyCap { required property string modelData; text: modelData }
                                        }
                                    }
                                    Text {
                                        Layout.fillWidth: true
                                        text: modelData.text
                                        color: Theme.dim
                                        font.family: Theme.body
                                        font.pixelSize: 13
                                        wrapMode: Text.WordWrap
                                    }
                                }
                            }
                        }
                    }
                }

                // Every shortcut, by where it works
                Repeater {
                    model: sheet.sections
                    delegate: ColumnLayout {
                        id: section
                        required property var modelData
                        readonly property var rows: { sheet.query; return sheet.rowsOf(modelData.scope) }
                        visible: rows.length > 0
                        Layout.fillWidth: true
                        spacing: 0
                        Text {
                            Layout.bottomMargin: 6
                            text: Theme.caps(section.modelData.title)
                            color: Theme.label
                            font.family: Theme.mono
                            font.pixelSize: 11
                            font.letterSpacing: Theme.track(0.5)
                        }
                        Repeater {
                            model: section.rows
                            delegate: Item {
                                required property var modelData
                                Layout.fillWidth: true
                                implicitHeight: Math.max(34, rowTitle.implicitHeight + 12)
                                Rectangle {
                                    anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom
                                    height: 1; color: Theme.line
                                }
                                Text {
                                    id: rowTitle
                                    anchors.left: parent.left
                                    anchors.right: caps.left
                                    anchors.rightMargin: 12
                                    anchors.verticalCenter: parent.verticalCenter
                                    text: modelData.title
                                    color: Theme.bodyText
                                    font.family: Theme.body
                                    font.pixelSize: 13
                                    wrapMode: Text.WordWrap
                                }
                                Row {
                                    id: caps
                                    anchors.right: parent.right
                                    anchors.verticalCenter: parent.verticalCenter
                                    spacing: 6
                                    Repeater {
                                        model: modelData.labels
                                        delegate: KeyCap { required property string modelData; text: modelData }
                                    }
                                }
                            }
                        }
                    }
                }

                Text {
                    visible: sheet.query.trim().length > 0
                             && !sheet.sections.some(s => sheet.rowsOf(s.scope).length > 0)
                    text: qsTr("No shortcut matches “%1”.").arg(sheet.query.trim())
                    color: Theme.muted
                    font.family: Theme.body
                    font.pixelSize: 13
                }
            }
        }
    }
}
