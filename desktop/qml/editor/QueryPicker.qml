import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../"

// Picks the query to place in a page (/query): an existing saved query, or a new one with every
// object of a type. Type to filter, ↑/↓, Enter, Esc.
Popup {
    id: qp
    signal chosen(string setId)

    property string filter: ""
    property int cur: 0
    readonly property var entries: {
        if (!visible || !ws) return []
        const f = filter.trim().toLowerCase()
        const out = []
        const sets = ws.objectsByType("set", 0)
        for (let i = 0; i < sets.length; i++)
            out.push({ kind: "set", id: sets[i].id, label: sets[i].title || qsTr("Untitled query") })
        const types = ws.allTypes().filter(t => t.id !== "set" && t.id !== "tag")
        for (let i = 0; i < types.length; i++)
            out.push({ kind: "type", id: types[i].id, label: qsTr("New query: all %1").arg(types[i].name || Theme.typeName(types[i].id)) })
        return f.length === 0 ? out : out.filter(e => e.label.toLowerCase().indexOf(f) >= 0)
    }
    onEntriesChanged: cur = Math.min(cur, Math.max(0, entries.length - 1))
    onOpened: { filter = ""; search.text = ""; cur = 0; search.forceActiveFocus() }

    function pick(e) {
        if (!e || !ws) return
        let id = e.id
        if (e.kind === "type") {
            const info = ws.typeInfo(e.id)
            id = ws.createSet(info && info.name ? info.name : Theme.typeName(e.id), { version: 1, types: [e.id] })
            ws.setSetActiveView(id, "list")
        }
        close()
        if (id.length > 0) chosen(id)
    }

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
            placeholderText: qsTr("Place a query in this page…")
            color: Theme.text
            placeholderTextColor: Theme.label
            font.family: Theme.body; font.pixelSize: 14
            background: Rectangle { color: Theme.panel; border.width: 1; border.color: search.activeFocus ? Theme.line3 : Theme.line2 }
            onTextChanged: qp.filter = text
            Keys.onDownPressed: qp.cur = Math.min(qp.entries.length - 1, qp.cur + 1)
            Keys.onUpPressed: qp.cur = Math.max(0, qp.cur - 1)
            Keys.onReturnPressed: qp.pick(qp.entries[qp.cur])
            Keys.onEnterPressed: qp.pick(qp.entries[qp.cur])
        }
        ListView {
            id: list
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            model: qp.entries
            currentIndex: qp.cur
            onCurrentIndexChanged: positionViewAtIndex(currentIndex, ListView.Contain)
            boundsBehavior: Flickable.StopAtBounds
            ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }
            delegate: Rectangle {
                id: row
                required property var modelData
                required property int index
                readonly property bool lit: qp.cur === index
                width: list.width
                height: 36
                color: lit ? Theme.accent : "transparent"
                Behavior on color { ColorAnimation { duration: Theme.fast } }
                RowLayout {
                    anchors.fill: parent
                    anchors.leftMargin: 12; anchors.rightMargin: 12
                    spacing: 10
                    TintedIcon {
                        name: row.modelData.kind === "set" ? "filter" : Theme.typeIcon(row.modelData.id)
                        size: 14
                        color: row.lit ? Theme.accentInk : (row.modelData.kind === "set" ? Theme.label : Theme.typeColor(row.modelData.id))
                    }
                    Text {
                        Layout.fillWidth: true
                        text: row.modelData.label
                        color: row.lit ? Theme.accentInk : (row.modelData.kind === "set" ? Theme.bodyText : Theme.muted)
                        font.family: Theme.body; font.pixelSize: 13
                        elide: Text.ElideRight
                    }
                }
                HoverHandler { cursorShape: Qt.PointingHandCursor; onHoveredChanged: if (hovered) qp.cur = row.index }
                TapHandler { onTapped: qp.pick(row.modelData) }
            }
        }
    }
}
