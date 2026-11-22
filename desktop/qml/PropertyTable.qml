import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "."

// Inline property table (docs/design/mockup/Book.dc.html, Reviewed.dc.html, Image.dc.html, Person.dc.html).
// Renders select dropdowns, multi_select removable chips + picker, sharp accent checkboxes,
// 5-bar rating meter (Book), 22x22 dominant color swatches (Image), clickable URLs,
// and a separate "Loose properties" section (spec/schema.md rule 4) with manual remove.
Item {
    id: table

    property string objectId: ""
    property string objectTypeId: ""
    property bool hideWebHeaderProps: false
    property real labelWidth: 136
    property bool stacked: false   // true in Book left column (Book.dc.html)
    signal openObjectRequested(string objectId)
    function openOption(key, value) {
        const w = table.Window.window
        if (w && w.openOption) w.openOption(table.objectTypeId, key, value)
    }

    property int rev: 0
    property var entries: []
    property var definedEntries: []
    property var looseEntries: []

    function refresh() {
        if (!ws || !objectId) {
            entries = []
            definedEntries = []
            looseEntries = []
            return
        }
        const raw = ws.objectPropertyEntries(objectId)
        const skip = hideWebHeaderProps
                   ? ["tags", "url", "description", "thumbnail_url", "favicon_url", "cover_image", "is_bookmark"]
                   : (objectTypeId === "collection" ? ["tags", "items"] : ["tags"]) // items: CollectionView
        const filtered = raw.filter(e => skip.indexOf(e.key) < 0)
        entries = filtered
        definedEntries = filtered.filter(e => !e.loose)
        looseEntries = filtered.filter(e => !!e.loose)
    }

    onObjectIdChanged: refresh()
    onHideWebHeaderPropsChanged: refresh()

    Connections {
        target: ws
        function onObjectPropertiesChanged(id) {
            if (id === table.objectId)
                table.refresh()
        }
        function onTypesChanged() { table.refresh() }
    }

    // Map image dominant color ids to display hex swatches
    function colorSwatchHex(id, fallbackColor) {
        const m = {
            "black": "#101010", "white": "#F2F2F2", "gray": "#7A7A7A", "grey": "#7A7A7A",
            "red": "#FF786E", "orange": "#FFB07A", "yellow": "#FFD666", "green": "#7FE0A6",
            "teal": "#7AF0D0", "cyan": "#7FD4FF", "blue": "#6EAAFF", "indigo": "#6366F1",
            "purple": "#C2B2FF", "violet": "#A387FF", "pink": "#FF8FB3", "magenta": "#E879F9",
            "fuchsia": "#F472B6", "brown": "#92400E", "beige": "#D6C5A7", "light_beige": "#E8DCC8",
            "gold": "#EAB308", "blue_green": "#2DD4BF"
        }
        if (m[id]) return m[id]
        if (fallbackColor && Theme.paletteHex[fallbackColor]) return Theme.paletteColor(fallbackColor)
        return "#8B8B8B"
    }

    function placeOverlayPopup(popup, anchorItem, xOffset, wantedHeight) {
        const overlay = Overlay.overlay
        if (!overlay || !anchorItem) {
            popup.x = xOffset
            popup.y = -wantedHeight - 4
            popup.height = wantedHeight
            popup.open()
            return
        }
        const margin = 12
        const gap = 4
        const topPt = anchorItem.mapToItem(overlay, xOffset, 0)
        const botPt = anchorItem.mapToItem(overlay, xOffset, anchorItem.height)
        const spaceAbove = Math.max(0, topPt.y - margin - gap)
        const spaceBelow = Math.max(0, overlay.height - botPt.y - margin - gap)
        const openAbove = spaceAbove >= Math.min(wantedHeight, 160) || spaceAbove >= spaceBelow
        const avail = openAbove ? spaceAbove : spaceBelow
        const h = Math.max(72, Math.min(wantedHeight, avail))
        popup.height = h
        popup.x = Math.max(margin, Math.min(topPt.x, overlay.width - popup.width - margin))
        popup.y = openAbove
            ? Math.max(margin, topPt.y - h - gap)
            : Math.min(overlay.height - h - margin, botPt.y + gap)
        popup.open()
    }

    function propIcon(kind) {
        switch (kind) {
        case "checkbox": return "task"
        case "date": return "calendar"
        case "url": return "web"
        case "number": return "number"
        case "select": return "select"
        case "multi_select": return "multi"
        case "object": return "person"
        default: return "text"
        }
    }

    visible: entries.length > 0
    implicitHeight: entries.length > 0 ? propCol.implicitHeight : 0
    Layout.fillHeight: false

    ColumnLayout {
        id: propCol
        width: parent.width
        spacing: 0

        Repeater {
            model: table.definedEntries
            delegate: propRowComp
        }

        // Loose properties section (spec/schema.md rule 4): keys not on the current type
        Text {
            visible: table.looseEntries.length > 0
            Layout.topMargin: 12
            Layout.bottomMargin: 4
            text: Theme.caps(qsTr("LOOSE PROPERTIES · KEPT FROM PREVIOUS TYPE"))
            color: Theme.label
            font.family: Theme.mono
            font.pixelSize: 10
        }
        Repeater {
            model: table.looseEntries
            delegate: propRowComp
        }
    }

    Component {
        id: propRowComp
        Item {
            id: propRow
            required property var modelData
            required property int index
            readonly property bool isSelect: modelData.kind === "select"
            readonly property bool isMulti: modelData.kind === "multi_select"
            readonly property bool isRating: modelData.key === "rating" && modelData.kind === "number"
            readonly property bool isColorSwatches: modelData.key === "colors" && isMulti
            readonly property bool isPersonField: (table.objectTypeId === "book" && (modelData.key === "author" || modelData.key === "recommended_by"))
                                               || modelData.kind === "object"
            readonly property var linkedPerson: (isPersonField && ws && modelData.value)
                                                ? ws.resolvePersonByValue(String(modelData.value)) : ({})
            readonly property bool hasLinkedPerson: !!(linkedPerson && linkedPerson.id && linkedPerson.id.length > 0)
            readonly property bool isEmail: modelData.key === "email"
            readonly property bool isPhone: modelData.key === "phone" || modelData.key === "telefono"
            readonly property var selList: modelData.selected || []
            readonly property var optList: modelData.options || []
            readonly property bool isLoose: !!modelData.loose

            Layout.fillWidth: true
            implicitHeight: Math.max(table.stacked ? 54 : 32, rowInner.implicitHeight + (table.stacked ? 14 : 6))

            Rectangle {
                anchors.bottom: parent.bottom
                width: parent.width
                height: 1
                color: Theme.lineSubtle
            }

            GridLayout {
                id: rowInner
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.verticalCenter: parent.verticalCenter
                columns: table.stacked ? 1 : 2
                columnSpacing: 12
                rowSpacing: 6

                // Key label
                RowLayout {
                    Layout.preferredWidth: table.stacked ? -1 : table.labelWidth
                    Layout.fillWidth: table.stacked
                    spacing: 8

                    TintedIcon {
                        name: propRow.isPersonField ? "person" : table.propIcon(propRow.modelData.kind)
                        size: 14
                        color: Theme.label
                    }
                    Text {
                        Layout.fillWidth: true
                        text: propRow.modelData.name || propRow.modelData.key
                        color: Theme.label
                        font.family: Theme.body
                        font.pixelSize: 13
                        elide: Text.ElideRight
                    }
                }

                // Value cell
                RowLayout {
                    Layout.fillWidth: true
                    spacing: 6

                    // 1) Checkbox (sharp 18x18 accent box — Reviewed.dc.html)
                    Item {
                        visible: propRow.modelData.kind === "checkbox"
                        Layout.fillWidth: true
                        implicitHeight: 28
                        Row {
                            anchors.verticalCenter: parent.verticalCenter
                            spacing: 10
                            Rectangle {
                                anchors.verticalCenter: parent.verticalCenter
                                width: 18; height: 18
                                radius: Theme.noir ? 0 : 4
                                color: "transparent"
                                border.width: 1
                                border.color: propRow.modelData.checked ? Theme.accent : Theme.faint
                                Behavior on border.color { ColorAnimation { duration: 200 * Theme.motion } }
                                Rectangle {
                                    anchors.fill: parent; anchors.margins: 3
                                    color: Theme.accent
                                    scale: propRow.modelData.checked ? 1 : 0
                                    opacity: propRow.modelData.checked ? 1 : 0
                                    Behavior on scale {
                                        NumberAnimation {
                                            duration: 300 * Theme.motion
                                            easing.type: Easing.Bezier
                                            easing.bezierCurve: [0.2, 0.8, 0.1, 1, 1, 1]
                                        }
                                    }
                                    Behavior on opacity { NumberAnimation { duration: 200 * Theme.motion } }
                                }
                                HoverHandler { cursorShape: Qt.PointingHandCursor }
                                TapHandler {
                                    onTapped: if (ws && table.objectId)
                                        ws.setObjectProperty(table.objectId, propRow.modelData.key, !propRow.modelData.checked, "checkbox")
                                }
                            }
                            Text {
                                anchors.verticalCenter: parent.verticalCenter
                                text: propRow.modelData.checked ? qsTr("Yes") : qsTr("No")
                                color: Theme.dim; font.family: Theme.body; font.pixelSize: 13
                            }
                        }
                    }

                    // 2) 5-bar rating meter for Book.rating (Book.dc.html)
                    Item {
                        visible: propRow.isRating
                        Layout.fillWidth: true
                        implicitHeight: 28
                        Row {
                            anchors.verticalCenter: parent.verticalCenter
                            spacing: 4
                            readonly property int curVal: Math.max(0, Math.min(5, Math.round(Number(propRow.modelData.value) || 0)))
                            Repeater {
                                model: 5
                                delegate: Rectangle {
                                    required property int index
                                    width: 22; height: 8
                                    color: (index + 1) <= parent.curVal ? Theme.accent : Theme.line2
                                    HoverHandler { cursorShape: Qt.PointingHandCursor }
                                    TapHandler {
                                        onTapped: if (ws && table.objectId) {
                                            const next = (index + 1) === parent.curVal ? "" : String(index + 1)
                                            ws.setObjectProperty(table.objectId, propRow.modelData.key, next, "number")
                                        }
                                    }
                                }
                            }
                            Text {
                                visible: parent.curVal > 0
                                anchors.verticalCenter: parent.verticalCenter
                                leftPadding: 6
                                text: parent.curVal + "/5"
                                color: Theme.label; font.family: Theme.mono; font.pixelSize: 11
                            }
                        }
                    }

                    // 3) Single select chip + dropdown popup (Reviewed.dc.html, Book.dc.html)
                    Item {
                        visible: propRow.isSelect
                        Layout.fillWidth: true
                        implicitHeight: 28

                        Rectangle {
                            id: selChip
                            implicitWidth: selChipRow.implicitWidth + 18
                            implicitHeight: 26
                            anchors.verticalCenter: parent.verticalCenter
                            radius: Theme.radiusSm
                            color: "transparent"
                            border.width: 1
                            border.color: selChipHov.hovered ? Theme.line3 : Theme.line2
                            Row {
                                id: selChipRow
                                anchors.centerIn: parent; spacing: 7
                                Rectangle {
                                    visible: propRow.selList.length > 0
                                    anchors.verticalCenter: parent.verticalCenter
                                    width: 7; height: 7
                                    color: propRow.selList.length > 0 ? Theme.paletteColor(propRow.selList[0].color || "blue") : Theme.faint
                                }
                                Text {
                                    anchors.verticalCenter: parent.verticalCenter
                                    text: propRow.selList.length > 0 ? propRow.selList[0].label : qsTr("Empty")
                                    color: propRow.selList.length > 0 ? Theme.bodyText : Theme.faint
                                    font.family: Theme.body; font.pixelSize: 13
                                }
                                Text {
                                    anchors.verticalCenter: parent.verticalCenter
                                    text: "▾"; color: Theme.label; font.pixelSize: 11
                                }
                            }
                            HoverHandler { id: selChipHov; cursorShape: Qt.PointingHandCursor }
                            TapHandler {
                                onTapped: table.placeOverlayPopup(
                                    optPopup, propRow, table.stacked ? 0 : table.labelWidth + 12,
                                    Math.min(364, Math.max(1, propRow.optList.length) * 34 + 56))
                            }
                        }
                        IconButton {
                            visible: propRow.selList.length > 0
                            anchors.left: selChip.right
                            anchors.leftMargin: 4
                            anchors.verticalCenter: parent.verticalCenter
                            implicitWidth: 28; implicitHeight: 28
                            icon: "graph"
                            tip: propRow.selList.length > 0 ? qsTr("Everything with “%1”").arg(propRow.selList[0].label) : ""
                            onClicked: table.openOption(propRow.modelData.key, propRow.selList[0].id)
                        }
                    }

                    // 4) Multi-select removable chips (or 22x22 swatches for image.colors) + "+ Add" button
                    Flow {
                        visible: propRow.isMulti
                        Layout.fillWidth: true
                        spacing: 6

                        // Image dominant color swatches (Image.dc.html)
                        Repeater {
                            model: propRow.isColorSwatches ? propRow.selList : []
                            delegate: Rectangle {
                                required property var modelData
                                width: 22; height: 22
                                color: table.colorSwatchHex(modelData.id, modelData.color)
                                border.width: 1; border.color: "#333333"
                                HoverHandler { id: swHov; cursorShape: Qt.PointingHandCursor }
                                TapHandler { onTapped: if (ws && table.objectId) ws.toggleOption(table.objectId, propRow.modelData.key, modelData.id, true) }
                                ToolTip.visible: swHov.hovered; ToolTip.text: (modelData.label || modelData.id) + " (click to remove)"; ToolTip.delay: 250
                            }
                        }

                        // Normal multi_select removable chips
                        Repeater {
                            model: propRow.isColorSwatches ? [] : propRow.selList
                            delegate: Rectangle {
                                required property var modelData
                                implicitWidth: msRow.implicitWidth + 16; implicitHeight: 26
                                radius: Theme.radiusSm; color: "transparent"
                                border.width: 1; border.color: msHov.hovered ? Theme.line3 : Theme.line2
                                Row {
                                    id: msRow; anchors.centerIn: parent; spacing: 7
                                    Rectangle { anchors.verticalCenter: parent.verticalCenter; width: 7; height: 7; color: Theme.paletteColor(modelData.color || "blue") }
                                    Text { anchors.verticalCenter: parent.verticalCenter; text: modelData.label || modelData.id; color: Theme.bodyText; font.family: Theme.body; font.pixelSize: 13 }
                                    Text { id: msX; anchors.verticalCenter: parent.verticalCenter; text: "×"; color: msHov.hovered ? Theme.text : Theme.label; font.pixelSize: 13 }
                                }
                                HoverHandler { id: msHov; cursorShape: Qt.PointingHandCursor }
                                // the chip shows everything with this value; only the × removes it
                                TapHandler {
                                    onTapped: (ev) => {
                                        if (ev.position.x >= msRow.x + msRow.width - msX.width - 4) {
                                            if (ws && table.objectId) ws.toggleOption(table.objectId, propRow.modelData.key, modelData.id, true)
                                        } else {
                                            table.openOption(propRow.modelData.key, modelData.id)
                                        }
                                    }
                                }
                                ToolTip.visible: msHov.hovered
                                ToolTip.delay: 600
                                ToolTip.text: qsTr("Show everything with “%1” · × removes it").arg(modelData.label || modelData.id)
                            }
                        }

                        // "+ Add" button
                        Rectangle {
                            visible: propRow.optList.length > 0
                            implicitWidth: addMsTxt.implicitWidth + 16; implicitHeight: 26
                            radius: Theme.radiusSm; color: "transparent"
                            border.width: 1; border.color: addMsHov.hovered ? Theme.line3 : Theme.line2
                            Text {
                                id: addMsTxt; anchors.centerIn: parent
                                text: "+ " + (propRow.modelData.name || qsTr("Add"))
                                color: Theme.muted; font.family: Theme.body; font.pixelSize: 12
                            }
                            HoverHandler { id: addMsHov; cursorShape: Qt.PointingHandCursor }
                            TapHandler {
                                onTapped: table.placeOverlayPopup(
                                    optPopup, propRow, table.stacked ? 0 : table.labelWidth + 12,
                                    Math.min(364, Math.max(1, propRow.optList.length) * 34 + 56))
                            }
                        }
                    }

                    // 4b) Person link chip for Book.author / Book.recommended_by (Book.dc.html)
                    Row {
                        visible: propRow.hasLinkedPerson
                        Layout.fillWidth: true
                        spacing: 6
                        Rectangle {
                            implicitWidth: pChipRow.implicitWidth + 16; implicitHeight: 26
                            color: pChipH.hovered ? Theme.panel2 : "transparent"
                            border.width: 1; border.color: pChipH.hovered ? Theme.line3 : Theme.line2
                            Row {
                                id: pChipRow; anchors.centerIn: parent; spacing: 6
                                TintedIcon { anchors.verticalCenter: parent.verticalCenter; name: "person"; size: 13; color: Theme.person }
                                Text { anchors.verticalCenter: parent.verticalCenter; text: propRow.linkedPerson.title || ""; color: Theme.bodyText; font.family: Theme.body; font.pixelSize: 13 }
                            }
                            HoverHandler { id: pChipH; cursorShape: Qt.PointingHandCursor }
                            TapHandler { onTapped: table.openObjectRequested(propRow.linkedPerson.id) }
                        }
                        Rectangle {
                            implicitWidth: 26; implicitHeight: 26
                            color: pPickH1.hovered ? Theme.panel2 : "transparent"
                            border.width: 1; border.color: Theme.line2
                            Text { anchors.centerIn: parent; text: "▾"; color: Theme.label; font.pixelSize: 11 }
                            HoverHandler { id: pPickH1; cursorShape: Qt.PointingHandCursor }
                            TapHandler {
                                onTapped: table.placeOverlayPopup(
                                    personPickerPopup, propRow, table.stacked ? 0 : table.labelWidth + 12,
                                    Math.min(300, Math.max(2, Math.min(8, personPickerCol.people.length) + 2) * 34 + 16))
                            }
                        }
                    }

                    // 5) Plain text / number / date / url inline input
                    TextInput {
                        id: valInput
                        visible: propRow.modelData.kind !== "checkbox" && !propRow.isSelect && !propRow.isMulti && !propRow.isRating && !propRow.hasLinkedPerson
                        Layout.fillWidth: true
                        Layout.preferredHeight: 28
                        verticalAlignment: TextInput.AlignVCenter
                        color: (propRow.modelData.kind === "url" || propRow.isEmail || propRow.isPhone) ? Theme.dim : Theme.bodyText
                        font.underline: (propRow.modelData.kind === "url" || propRow.isEmail) && text.length > 0
                        selectionColor: Theme.accentDim
                        selectedTextColor: Theme.text
                        font.family: Theme.body
                        font.pixelSize: 14
                        clip: true
                        selectByMouse: true
                        text: propRow.modelData.value !== undefined ? String(propRow.modelData.value) : ""
                        Component.onCompleted: cursorPosition = 0
                        onTextChanged: if (!activeFocus) cursorPosition = 0
                        onActiveFocusChanged: if (!activeFocus) cursorPosition = 0
                        onEditingFinished: {
                            if (ws && table.objectId && text !== String(propRow.modelData.value || ""))
                                ws.setObjectProperty(table.objectId, propRow.modelData.key, text, propRow.modelData.kind)
                        }
                        Keys.onReturnPressed: focus = false
                        Keys.onEnterPressed: focus = false

                        Text {
                            anchors.verticalCenter: parent.verticalCenter
                            visible: valInput.length === 0 && !valInput.activeFocus
                            text: propRow.modelData.kind === "date" ? "YYYY-MM-DD" : qsTr("Empty")
                            color: Theme.faint
                            font: valInput.font
                        }
                    }

                    // Pick/link Person button for unlinked Book.author / Book.recommended_by
                    Rectangle {
                        visible: propRow.isPersonField && !propRow.hasLinkedPerson
                        implicitWidth: 26; implicitHeight: 26
                        color: pPickH2.hovered ? Theme.panel2 : "transparent"
                        border.width: 1; border.color: Theme.line2
                        TintedIcon { anchors.centerIn: parent; name: "person"; size: 13; color: Theme.person }
                        HoverHandler { id: pPickH2; cursorShape: Qt.PointingHandCursor }
                        TapHandler {
                            onTapped: table.placeOverlayPopup(
                                personPickerPopup, propRow, table.stacked ? 0 : table.labelWidth + 12,
                                Math.min(300, Math.max(2, Math.min(8, personPickerCol.people.length) + 2) * 34 + 16))
                        }
                        ToolTip.visible: pPickH2.hovered; ToolTip.text: qsTr("Link a Person object"); ToolTip.delay: 300
                    }

                    // Clickable open action for Email / Phone / URL (SpecialTypes.dc.html)
                    IconButton {
                        visible: (propRow.isEmail || propRow.isPhone || propRow.modelData.kind === "url")
                                 && String(propRow.modelData.value || "").trim().length > 0
                        implicitWidth: 24; implicitHeight: 24
                        icon: "external-link"
                        onClicked: {
                            const v = String(propRow.modelData.value || "").trim()
                            if (propRow.isEmail) Qt.openUrlExternally("mailto:" + v)
                            else if (propRow.isPhone) Qt.openUrlExternally("tel:" + v.replace(/\s+/g, ""))
                            else Qt.openUrlExternally(v)
                        }
                        ToolTip.visible: hovered
                        ToolTip.text: propRow.isEmail ? qsTr("Send email") : propRow.isPhone ? qsTr("Call") : qsTr("Open URL")
                        ToolTip.delay: 300
                    }

                    // Remove button for loose properties (spec/schema.md rule 4)
                    IconButton {
                        visible: propRow.isLoose
                        implicitWidth: 24; implicitHeight: 24
                        icon: "x"
                        onClicked: if (ws && table.objectId) ws.removeObjectProperty(table.objectId, propRow.modelData.key)
                        ToolTip.visible: hovered; ToolTip.text: qsTr("Remove loose property"); ToolTip.delay: 300
                    }
                }
            }

            // Person object picker popup for Book.author / Book.recommended_by
            Popup {
                id: personPickerPopup
                parent: Overlay.overlay
                width: 260
                height: 220
                padding: 6
                closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
                background: Rectangle { color: Theme.sheet; border.width: 1; border.color: Theme.line2 }
                ColumnLayout {
                    id: personPickerCol
                    width: parent.width; spacing: 4
                    property string query: ""
                    readonly property var people: {
                        const all = ws ? ws.objectsByType("person", 0) : []
                        const q = query.trim().toLowerCase()
                        return q.length === 0 ? all : all.filter(p => String(p.title || "").toLowerCase().indexOf(q) >= 0)
                    }
                    Rectangle {
                        Layout.fillWidth: true; Layout.preferredHeight: 30
                        color: "transparent"; border.width: 1; border.color: Theme.line2
                        TextInput {
                            id: ppInput; anchors.fill: parent; anchors.leftMargin: 8; anchors.rightMargin: 8
                            verticalAlignment: TextInput.AlignVCenter
                            color: Theme.text; font.family: Theme.body; font.pixelSize: 12
                            onTextEdited: personPickerCol.query = text
                            Text { anchors.verticalCenter: parent.verticalCenter; visible: ppInput.length === 0; text: qsTr("Search or create person…"); color: Theme.faint; font: ppInput.font }
                        }
                    }
                    Repeater {
                        model: personPickerCol.people.slice(0, 8)
                        delegate: Rectangle {
                            required property var modelData
                            Layout.fillWidth: true; Layout.preferredHeight: 30
                            color: ppRowH.hovered ? Theme.panel2 : "transparent"
                            RowLayout {
                                anchors.fill: parent; anchors.leftMargin: 8; anchors.rightMargin: 8; spacing: 8
                                TintedIcon { name: "person"; size: 13; color: Theme.person }
                                Text { Layout.fillWidth: true; text: modelData.title || qsTr("Untitled"); color: Theme.bodyText; font.family: Theme.body; font.pixelSize: 13; elide: Text.ElideRight }
                            }
                            HoverHandler { id: ppRowH; cursorShape: Qt.PointingHandCursor }
                            TapHandler {
                                onTapped: {
                                    if (ws && table.objectId)
                                        ws.setObjectProperty(table.objectId, propRow.modelData.key, modelData.title, propRow.modelData.kind)
                                    personPickerPopup.close()
                                }
                            }
                        }
                    }
                    Rectangle {
                        visible: personPickerCol.query.trim().length > 0 || (String(propRow.modelData.value || "").trim().length > 0 && !propRow.hasLinkedPerson)
                        Layout.fillWidth: true; Layout.preferredHeight: 30
                        color: ppNewH.hovered ? Theme.accent : "transparent"
                        border.width: 1; border.color: Theme.line2
                        readonly property string targetName: personPickerCol.query.trim().length > 0
                                                             ? personPickerCol.query.trim()
                                                             : String(propRow.modelData.value || "").trim()
                        Text {
                            anchors.verticalCenter: parent.verticalCenter; anchors.left: parent.left; anchors.leftMargin: 8
                            text: qsTr("+ Create Person “%1”").arg(parent.targetName)
                            color: ppNewH.hovered ? Theme.accentInk : Theme.bodyText
                            font.family: Theme.body; font.pixelSize: 12
                        }
                        HoverHandler { id: ppNewH; cursorShape: Qt.PointingHandCursor }
                        TapHandler {
                            onTapped: {
                                const name = parent.targetName
                                if (ws && name.length > 0) {
                                    ws.createObject("person", name)
                                    ws.setObjectProperty(table.objectId, propRow.modelData.key, name, propRow.modelData.kind)
                                }
                                personPickerPopup.close()
                            }
                        }
                    }
                }
            }

            // Shared option picker popup for select & multi_select
            Popup {
                id: optPopup
                parent: Overlay.overlay
                width: 280
                height: 300
                padding: 4
                closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
                background: Rectangle {
                    color: Theme.sheet
                    border.width: 1
                    border.color: Theme.line2
                }
                property string filter: ""
                property int cur: 0
                readonly property var shown: {
                    const f = filter.trim().toLowerCase()
                    const all = propRow.optList || []
                    return f.length === 0 ? all : all.filter(o => String(o.label || o.id).toLowerCase().indexOf(f) >= 0)
                }
                onShownChanged: cur = Math.min(cur, Math.max(0, shown.length - 1))
                onOpened: { filter = ""; optSearch.text = ""; cur = 0; optSearch.forceActiveFocus() }
                function pick(o) {
                    if (!o) return
                    if (ws && table.objectId)
                        ws.toggleOption(table.objectId, propRow.modelData.key, o.id, propRow.isMulti)
                    if (!propRow.isMulti) optPopup.close()
                }
                contentItem: ColumnLayout {
                    spacing: 4
                    TextField {
                        id: optSearch
                        Layout.fillWidth: true
                        placeholderText: qsTr("Search…")
                        color: Theme.text
                        placeholderTextColor: Theme.faint
                        font.family: Theme.body; font.pixelSize: 13
                        background: Rectangle { color: Theme.panel; border.width: 1; border.color: optSearch.activeFocus ? Theme.line3 : Theme.line2 }
                        onTextChanged: optPopup.filter = text
                        Keys.onDownPressed: optPopup.cur = Math.min(optPopup.shown.length - 1, optPopup.cur + 1)
                        Keys.onUpPressed: optPopup.cur = Math.max(0, optPopup.cur - 1)
                        Keys.onReturnPressed: optPopup.pick(optPopup.shown[optPopup.cur])
                        Keys.onEnterPressed: optPopup.pick(optPopup.shown[optPopup.cur])
                    }
                    ListView {
                        id: optListView
                        Layout.fillWidth: true
                        Layout.fillHeight: true
                        clip: true
                        boundsBehavior: Flickable.StopAtBounds
                        model: optPopup.shown
                        currentIndex: optPopup.cur
                        onCurrentIndexChanged: positionViewAtIndex(currentIndex, ListView.Contain)
                        ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }
                        delegate: Rectangle {
                            required property var modelData
                            required property int index
                            readonly property bool chosen: propRow.selList.some(s => s.id === modelData.id)
                            readonly property bool lit: optHov.hovered || optPopup.cur === index
                            width: optListView.width
                            height: 34
                            color: lit ? Theme.accent : "transparent"
                            Behavior on color { ColorAnimation { duration: Theme.fast } }
                            RowLayout {
                                anchors.fill: parent; anchors.leftMargin: 12; anchors.rightMargin: 12; spacing: 10
                                Rectangle {
                                    width: 7; height: 7
                                    color: propRow.isColorSwatches ? table.colorSwatchHex(modelData.id, modelData.color)
                                                                   : Theme.paletteColor(modelData.color || "blue")
                                    border.width: lit ? 1 : 0; border.color: "#000000"
                                }
                                Text {
                                    Layout.fillWidth: true
                                    text: modelData.label || modelData.id
                                    color: lit ? Theme.accentInk : Theme.bodyText
                                    font.family: Theme.body; font.pixelSize: 13
                                    elide: Text.ElideRight
                                }
                                Text {
                                    visible: chosen
                                    text: "✓"
                                    color: lit ? Theme.accentInk : Theme.accent
                                    font.family: Theme.mono; font.pixelSize: 12
                                }
                            }
                            HoverHandler { id: optHov; cursorShape: Qt.PointingHandCursor; onHoveredChanged: if (hovered) optPopup.cur = index }
                            TapHandler { onTapped: optPopup.pick(modelData) }
                        }
                    }
                    Text {
                        visible: optPopup.shown.length === 0
                        Layout.margins: 8
                        text: qsTr("No option matches.")
                        color: Theme.faint
                        font.family: Theme.body; font.pixelSize: 12
                    }
                }
            }
        }
    }
}
