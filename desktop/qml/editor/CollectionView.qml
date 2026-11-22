import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../"

// A collection's items in the user's order (product §19): open with a click or Enter, reorder
// with Alt+↑/↓ (or the arrows on hover), remove with × or Delete, add with "+ Add" (search).
FocusScope {
    id: cv
    property string collectionId: ""
    property var appWindow: null
    property int current: -1

    readonly property int rev: typeof ws !== "undefined" && ws ? ws.listRevision : 0
    readonly property var items: { rev; return ws && collectionId ? ws.collectionItems(collectionId) : [] }
    implicitHeight: col.implicitHeight

    function move(from, to) {
        if (to < 0 || to >= items.length) return
        if (ws.moveInCollection(collectionId, from, to)) current = to
    }
    function removeAt(i) {
        if (i < 0 || i >= items.length) return
        const it = items[i]
        ws.removeFromCollection(collectionId, it.id)
        current = Math.min(i, items.length - 2)
        if (appWindow) appWindow.showToast(qsTr("Removed “%1” from the collection").arg(it.title || qsTr("Untitled")))
    }
    function openAt(i) { if (i >= 0 && i < items.length && appWindow) appWindow.openObject(items[i].id) }

    Keys.onUpPressed: (e) => { if (e.modifiers & Qt.AltModifier) move(current, current - 1); else current = Math.max(0, current - 1) }
    Keys.onDownPressed: (e) => { if (e.modifiers & Qt.AltModifier) move(current, current + 1); else current = Math.min(items.length - 1, current + 1) }
    Keys.onReturnPressed: openAt(current)
    Keys.onDeletePressed: removeAt(current)

    ColumnLayout {
        id: col
        width: cv.width
        spacing: 0

        RowLayout {
            Layout.fillWidth: true
            Layout.bottomMargin: 6
            Text {
                text: Theme.caps(qsTr("%1 IN THIS COLLECTION").arg(cv.items.length))
                color: Theme.label; font.family: Theme.mono; font.pixelSize: 11
                Layout.fillWidth: true
            }
            Button { small: true; text: "+ " + qsTr("Add"); onClicked: adder.open() }
        }

        Repeater {
            model: cv.items
            delegate: Rectangle {
                id: row
                required property var modelData
                required property int index
                readonly property bool lit: cv.current === index && cv.activeFocus
                Layout.fillWidth: true
                implicitHeight: 40
                color: lit ? Theme.panel2 : (rHov.hovered ? Theme.cardHover : "transparent")
                Behavior on color { ColorAnimation { duration: Theme.fast } }
                // the moved row slides into place
                Behavior on y { NumberAnimation { duration: Theme.normal; easing.type: Easing.OutCubic } }
                Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.lineSubtle }
                Rectangle { visible: row.lit; width: 2; height: parent.height; color: Theme.accent }
                RowLayout {
                    anchors.fill: parent
                    anchors.leftMargin: 10
                    anchors.rightMargin: 6
                    spacing: 10
                    Text { text: String(row.index + 1); color: Theme.label; font.family: Theme.mono; font.pixelSize: 11; Layout.preferredWidth: 18 }
                    TypeBadge { typeId: row.modelData.typeId || ""; size: 14 }
                    Text {
                        Layout.fillWidth: true
                        text: row.modelData.title || qsTr("Untitled")
                        color: Theme.bodyText; font.family: Theme.body; font.pixelSize: 14
                        elide: Text.ElideRight
                    }
                    Row {
                        visible: rHov.hovered || row.lit
                        spacing: 0
                        IconButton { implicitWidth: 28; implicitHeight: 28; icon: "arrow-up"; tip: qsTr("Move up (Alt+↑)"); onClicked: cv.move(row.index, row.index - 1) }
                        IconButton { implicitWidth: 28; implicitHeight: 28; icon: "arrow-down"; tip: qsTr("Move down (Alt+↓)"); onClicked: cv.move(row.index, row.index + 1) }
                        IconButton { implicitWidth: 28; implicitHeight: 28; icon: "x"; tip: qsTr("Remove from the collection (Delete)"); onClicked: cv.removeAt(row.index) }
                    }
                }
                HoverHandler { id: rHov; cursorShape: Qt.PointingHandCursor }
                TapHandler {
                    onTapped: { cv.current = row.index; cv.forceActiveFocus() }
                    onDoubleTapped: cv.openAt(row.index)
                }
            }
        }
        Text {
            visible: cv.items.length === 0
            Layout.topMargin: 6
            text: qsTr("Empty. Use “+ Add”, or “Add to collection…” in any object's ⋯ menu.")
            color: Theme.label; font.family: Theme.body; font.pixelSize: 12
        }
        Text {
            visible: cv.items.length > 1
            Layout.topMargin: 6
            text: qsTr("Click to select · double-click or Enter opens · Alt+↑/↓ reorders · Delete removes")
            color: Theme.label; font.family: Theme.mono; font.pixelSize: 11
        }
    }

    ObjectSearchPicker {
        id: adder
        placeholder: qsTr("Add an object to this collection…")
        exclude: [cv.collectionId].concat(cv.items.map(i => i.id))
        preferType: cv.collectionId && ws ? (ws.fieldText(cv.collectionId, "item_type_id") || "") : ""
        onPickedId: (id) => ws.addToCollection(cv.collectionId, id)
    }
}
