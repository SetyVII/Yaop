import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "."

// An object's tags under its title: one colored pill per tag (click opens the tag, × removes it)
// and "Add tags", which searches existing tags and creates a new one. Writes
// properties.tags = [name] and makes sure each tag object exists (spec/object.md).
Flow {
    id: row

    property string objectId: ""
    signal openTagRequested(string tagId)

    property int rev: 0
    Connections {
        target: ws
        function onObjectPropertiesChanged(id) {
            if (id === row.objectId || id.startsWith("tag_"))
                row.rev++
        }
        function onObjectUpdated(id) {
            if (id === row.objectId || id.startsWith("tag_"))
                row.rev++
        }
        function onObjectListChanged() { row.rev++ }
    }
    readonly property var tags: { rev; return ws && objectId ? ws.objectTags(objectId) : [] }

    function save(list) {
        ws.setObjectProperty(objectId, "tags", list.join(","), "multi_select")
        rev++
    }
    function addTag(name) {
        const clean = String(name).replace(/#/g, "").replace(/,/g, " ").trim()
        if (!clean) return
        if (tags.some(t => t.toLowerCase() === clean.toLowerCase())) return
        ws.ensureTagObject(clean)
        save(tags.concat([clean]))
    }
    function removeTag(name) { save(tags.filter(t => t !== name)) }

    spacing: 6

    Repeater {
        model: row.tags
        delegate: Rectangle {
            id: chip
            required property string modelData
            readonly property color tone: {
                row.rev
                return (ws && modelData) ? ws.tagColor(modelData) : Theme.tagGreen
            }
            height: 26
            width: chipRow.implicitWidth + 18
            radius: Theme.radiusSm
            color: Theme.noir ? "transparent" : Qt.rgba(tone.r, tone.g, tone.b, chipHover.hovered ? 0.28 : 0.18)
            border.width: 1
            border.color: Theme.noir ? (chipHover.hovered ? Theme.line3 : Theme.line2) : Qt.rgba(tone.r, tone.g, tone.b, 0.36)
            Row {
                id: chipRow
                anchors.centerIn: parent
                spacing: 7
                Rectangle {
                    visible: Theme.noir
                    anchors.verticalCenter: parent.verticalCenter
                    width: 7; height: 7
                    color: chip.tone
                }
                Text {
                    anchors.verticalCenter: parent.verticalCenter
                    text: chip.modelData
                    color: Theme.noir ? Theme.bodyText : Theme.text
                    font.family: Theme.body
                    font.pixelSize: Theme.noir ? 13 : 12
                    font.weight: Theme.noir ? Font.Normal : Font.Medium
                }
                // remove: shown on hover
                Rectangle {
                    anchors.verticalCenter: parent.verticalCenter
                    width: chipHover.hovered ? 16 : 0
                    height: 16
                    radius: 4
                    clip: true
                    color: xHover.hovered ? Qt.rgba(1, 1, 1, 0.14) : "transparent"
                    Behavior on width { NumberAnimation { duration: 120 * Theme.motion } }
                    TintedIcon { anchors.centerIn: parent; name: "x"; size: 11; color: Theme.dim }
                    HoverHandler { id: xHover; cursorShape: Qt.PointingHandCursor }
                    TapHandler { onTapped: row.removeTag(chip.modelData) }
                }
            }
            HoverHandler { id: chipHover; cursorShape: Qt.PointingHandCursor }
            TapHandler { onTapped: row.openTagRequested(ws.ensureTagObject(chip.modelData)) }
        }
    }

    // Add tags
    Rectangle {
        id: addButton
        height: 26
        width: addRow.implicitWidth + 14
        radius: Theme.radiusSm
        color: addHover.hovered ? Theme.panel2 : "transparent"
        Row {
            id: addRow
            anchors.centerIn: parent
            spacing: 6
            TintedIcon { anchors.verticalCenter: parent.verticalCenter; name: "tag"; size: 14; color: addHover.hovered ? Theme.dim : Theme.muted }
            Text {
                anchors.verticalCenter: parent.verticalCenter
                text: row.tags.length ? qsTr("Add") : qsTr("Add tags")
                color: addHover.hovered ? Theme.text : Theme.muted
                font.family: Theme.body
                font.pixelSize: 13
            }
        }
        HoverHandler { id: addHover; cursorShape: Qt.PointingHandCursor }
        TapHandler {
            onTapped: {
                query.text = ""
                const overlay = Overlay.overlay
                if (overlay) {
                    const margin = 12
                    const gap = 6
                    const topPt = addButton.mapToItem(overlay, 0, 0)
                    const botPt = addButton.mapToItem(overlay, 0, addButton.height)
                    const estH = Math.min(280, Math.max(84, picker.implicitHeight > 0 ? picker.implicitHeight : 220))
                    const spaceAbove = Math.max(0, topPt.y - margin - gap)
                    const spaceBelow = Math.max(0, overlay.height - botPt.y - margin - gap)
                    const openAbove = spaceAbove >= Math.min(estH, 160) || spaceAbove >= spaceBelow
                    picker.x = Math.max(margin, Math.min(topPt.x, overlay.width - picker.width - margin))
                    picker.y = openAbove
                        ? Math.max(margin, topPt.y - estH - gap)
                        : Math.min(overlay.height - estH - margin, botPt.y + gap)
                }
                picker.open()
                query.forceActiveFocus()
            }
        }

        Popup {
            id: picker
            parent: Overlay.overlay
            width: 280
            padding: 8
            background: Rectangle {
                radius: Theme.radiusLg
                color: Theme.sheet
                border.width: 1
                border.color: Theme.line2
            }
            onOpened: query.forceActiveFocus()

            readonly property var results: {
                row.rev
                if (!visible || !ws) return []
                const taken = row.tags.map(t => t.toLowerCase())
                return ws.tagSuggestions(query.text, 8).filter(r => r.createNew || taken.indexOf(String(r.name).toLowerCase()) < 0)
            }
            property int current: 0
            onResultsChanged: current = 0
            function pick(i) {
                const r = results[i]
                if (r) row.addTag(r.name)
                query.text = ""
            }

            ColumnLayout {
                width: parent.width
                spacing: 4
                TextField {
                    id: query
                    Layout.fillWidth: true
                    placeholderText: qsTr("Search or create a tag…")
                    color: Theme.text
                    placeholderTextColor: Theme.muted
                    font.family: Theme.body
                    font.pixelSize: 13
                    background: Rectangle { radius: Theme.radius; color: Theme.ink; border.width: 1; border.color: Theme.line }
                    Keys.onDownPressed: picker.current = Math.min(picker.current + 1, picker.results.length - 1)
                    Keys.onUpPressed: picker.current = Math.max(picker.current - 1, 0)
                    Keys.onReturnPressed: picker.pick(picker.current)
                    Keys.onEnterPressed: picker.pick(picker.current)
                    Keys.onEscapePressed: picker.close()
                }
                Repeater {
                    model: picker.results
                    delegate: Rectangle {
                        required property var modelData
                        required property int index
                        Layout.fillWidth: true
                        implicitHeight: 30
                        radius: Theme.radius
                        color: index === picker.current ? Theme.panel2 : "transparent"
                        RowLayout {
                            anchors.fill: parent
                            anchors.leftMargin: 10
                            anchors.rightMargin: 10
                            spacing: 8
                            TintedIcon {
                                name: modelData.createNew ? "plus" : "tag"
                                size: 14
                                color: modelData.createNew ? Theme.ice : (ws ? ws.tagColor(modelData.name) : Theme.tagGreen)
                            }
                            Text {
                                Layout.fillWidth: true
                                text: modelData.createNew ? qsTr("Create “%1”").arg(modelData.name) : modelData.name
                                color: Theme.text
                                font.family: Theme.body
                                font.pixelSize: 13
                                elide: Text.ElideRight
                            }
                            Text {
                                visible: !modelData.createNew && (modelData.count || 0) > 0
                                text: String(modelData.count)
                                color: Theme.muted
                                font.family: Theme.mono
                                font.pixelSize: 11
                            }
                        }
                        HoverHandler { onHoveredChanged: if (hovered) picker.current = index; cursorShape: Qt.PointingHandCursor }
                        TapHandler { onTapped: picker.pick(index) }
                    }
                }
                Text {
                    visible: picker.results.length === 0
                    Layout.leftMargin: 10
                    Layout.topMargin: 4
                    Layout.bottomMargin: 4
                    text: qsTr("Type to create a tag.")
                    color: Theme.muted
                    font.family: Theme.body
                    font.pixelSize: 12
                }
            }
        }
    }
}
