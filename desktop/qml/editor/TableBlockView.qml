import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../"

// Table block: editable grid of plain cells (wire rows + align).
// Add/remove rows & columns; header row rendered slightly emphasized.

Item {
    id: tb

    function focusCell(i) {
        const c = i >= 0 && i < cellRepeater.count ? cellRepeater.itemAt(i) : null
        if (c) c.focusInput()
    }
    function activate() { focusCell(0); return true }

    property var view
    property int row
    property string blockId
    property var rows: []
    property var align: []

    readonly property int cols: rows.length > 0 ? rows[0].length : 0
    readonly property real cellH: 30

    implicitHeight: tableCol.implicitHeight + 20

    ColumnLayout {
        id: tableCol
        anchors.left: parent.left
        anchors.leftMargin: 2
        anchors.right: parent.right
        anchors.rightMargin: 26
        spacing: 6

        // The grid frame sizes itself from its rows; columns share the available width equally,
        // so a narrow window squeezes cells (text elides) instead of overflowing.
        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: Math.max(46, tableGrid.implicitHeight + 2)
            radius: Theme.radius
            color: "transparent"
            border.color: Theme.line
            border.width: 1
            clip: true

            GridLayout {
                id: tableGrid
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.top: parent.top
                anchors.margins: 1
                columns: Math.max(1, tb.cols)
                columnSpacing: 0
                rowSpacing: 0

                Repeater {
                    id: cellRepeater
                    model: {
                        const flat = []
                        for (let r = 0; r < tb.rows.length; r++)
                            for (let c = 0; c < tb.rows[r].length; c++)
                                flat.push({ r: r, c: c, text: tb.rows[r][c] })
                        return flat
                    }
                    delegate: Rectangle {
                        id: cell
                        required property var modelData
                        required property int index
                        function focusInput() { cellInput.forceActiveFocus(); cellInput.selectAll() }
                        Layout.fillWidth: true
                        Layout.preferredWidth: 1
                        Layout.preferredHeight: tb.cellH
                        color: modelData.r === 0 ? Theme.panel : "transparent"
                        Rectangle { anchors.right: parent.right; width: 1; height: parent.height; color: Theme.line }
                        Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.line }

                        TextInput {
                            id: cellInput
                            anchors.fill: parent
                            anchors.leftMargin: 9
                            anchors.rightMargin: 9
                            verticalAlignment: TextInput.AlignVCenter
                            text: cell.modelData.text !== undefined ? cell.modelData.text : ""
                            color: cell.modelData.r === 0 ? Theme.text : Theme.dim
                            selectionColor: Qt.rgba(0.75, 0.89, 1, 0.38)
                            selectedTextColor: Theme.text
                            font.family: Theme.body
                            font.pixelSize: 12
                            font.weight: cell.modelData.r === 0 ? Font.Medium : Font.Normal
                            clip: true
                            onEditingFinished: {
                                if (text !== cell.modelData.text)
                                    view.session.setCell(tb.row, cell.modelData.r, cell.modelData.c, text)
                            }
                            onActiveFocusChanged: view.setFocusedBlock(tb.blockId, activeFocus)
                            // Tab / Shift+Tab: next / previous cell, ↑/↓: row, Ctrl+Enter: new row,
                            // Esc: back to block mode on the table.
                            Keys.onPressed: function(event) {
                                let to = -1
                                if (event.key === Qt.Key_Tab) to = cell.index + 1
                                else if (event.key === Qt.Key_Backtab) to = cell.index - 1
                                else if (event.key === Qt.Key_Down) to = cell.index + tb.cols
                                else if (event.key === Qt.Key_Up) to = cell.index - tb.cols
                                if (to >= 0 || event.key === Qt.Key_Tab || event.key === Qt.Key_Backtab
                                        || event.key === Qt.Key_Up || event.key === Qt.Key_Down) {
                                    event.accepted = true
                                    editingFinished()
                                    tb.focusCell(to)
                                    return
                                }
                                if ((event.key === Qt.Key_Return || event.key === Qt.Key_Enter)
                                        && (event.modifiers & Qt.ControlModifier)) {
                                    event.accepted = true
                                    editingFinished()
                                    view.session.addTableRows(tb.row, 1)
                                    return
                                }
                                if (event.key === Qt.Key_Escape) {
                                    event.accepted = true
                                    editingFinished()
                                    view.selectRow(tb.row)
                                }
                            }
                        }
                    }
                }
            }
        }

        RowLayout {
            spacing: 10
            Text {
                text: Theme.caps(qsTr("TABLE"))
                color: Theme.faint
                font.family: Theme.mono
                font.pixelSize: 11
                font.letterSpacing: Theme.track(1)
            }
            Button {
                text: qsTr("+ row")
                small: true
                onClicked: view.session.addTableRows(tb.row, 1)
            }
            Button {
                text: qsTr("+ column")
                small: true
                onClicked: view.session.addTableColumns(tb.row, 1)
            }
            Item { Layout.fillWidth: true }
        }
    }
}
