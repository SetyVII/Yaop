import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "."

// Search-and-pick popup: type to filter, ↑/↓, Enter, Esc. `source(filter)` returns the entries,
// each {id, label, icon?, color?, hint?}. Emits picked(entry).
Popup {
    id: pl
    property string placeholder: qsTr("Search…")
    property var source: function (f) { return [] }
    property string filter: ""
    property int cur: 0
    signal picked(var entry)

    readonly property var entries: visible ? source(filter.trim()) : []
    onEntriesChanged: cur = Math.min(cur, Math.max(0, entries.length - 1))
    onOpened: { filter = ""; search.text = ""; cur = 0; search.forceActiveFocus() }
    function pick(e) { if (!e) return; close(); picked(e) }

    parent: Overlay.overlay
    x: (parent.width - width) / 2
    y: Math.max(24, parent.height * 0.18)
    width: Math.min(440, parent.width - 64)
    height: Math.min(420, parent.height - y - 24)
    padding: 6
    modal: true
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
    Overlay.modal: Rectangle { color: Theme.scrim }
    background: Rectangle { radius: Theme.radiusLg; color: Theme.sheet; border.color: Theme.line2; border.width: 1 }
    enter: Transition { NumberAnimation { property: "opacity"; from: 0; to: 1; duration: Theme.fast } }

    contentItem: ColumnLayout {
        spacing: 6
        TextField {
            id: search
            Layout.fillWidth: true
            placeholderText: pl.placeholder
            color: Theme.text
            placeholderTextColor: Theme.faint
            font.family: Theme.body; font.pixelSize: 14
            background: Rectangle { color: Theme.panel; border.width: 1; border.color: search.activeFocus ? Theme.line3 : Theme.line2 }
            onTextChanged: pl.filter = text
            Keys.onDownPressed: pl.cur = Math.min(pl.entries.length - 1, pl.cur + 1)
            Keys.onUpPressed: pl.cur = Math.max(0, pl.cur - 1)
            Keys.onReturnPressed: pl.pick(pl.entries[pl.cur])
            Keys.onEnterPressed: pl.pick(pl.entries[pl.cur])
        }
        ListView {
            id: list
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            model: pl.entries
            currentIndex: pl.cur
            onCurrentIndexChanged: positionViewAtIndex(currentIndex, ListView.Contain)
            boundsBehavior: Flickable.StopAtBounds
            ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }
            delegate: Rectangle {
                id: row
                required property var modelData
                required property int index
                readonly property bool lit: pl.cur === index
                width: list.width
                height: 36
                color: lit ? Theme.accent : "transparent"
                Behavior on color { ColorAnimation { duration: Theme.fast } }
                RowLayout {
                    anchors.fill: parent
                    anchors.leftMargin: 12; anchors.rightMargin: 12
                    spacing: 10
                    TintedIcon {
                        name: row.modelData.icon || "file"
                        size: 14
                        color: row.lit ? Theme.accentInk : (row.modelData.color || Theme.label)
                    }
                    Text {
                        Layout.fillWidth: true
                        text: row.modelData.label
                        color: row.lit ? Theme.accentInk : Theme.bodyText
                        font.family: Theme.body; font.pixelSize: 13
                        elide: Text.ElideRight
                    }
                    Text {
                        visible: (row.modelData.hint || "").length > 0
                        text: row.modelData.hint || ""
                        color: row.lit ? Theme.accentInk : Theme.faint
                        font.family: Theme.mono; font.pixelSize: 11
                    }
                }
                HoverHandler { cursorShape: Qt.PointingHandCursor; onHoveredChanged: if (hovered) pl.cur = row.index }
                TapHandler { onTapped: pl.pick(row.modelData) }
            }
        }
        Text {
            visible: pl.entries.length === 0
            Layout.margins: 8
            text: qsTr("Nothing found.")
            color: Theme.faint; font.family: Theme.body; font.pixelSize: 12
        }
    }
}
