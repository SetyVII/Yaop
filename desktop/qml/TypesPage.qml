import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "."

// Types manager & creator (docs/design/mockup/Types.dc.html + SpecialTypes.dc.html).
// Left 300px sidebar lists all types with icon + color + count; right pane either creates
// a new custom type (with live template preview) or inspects an existing type's properties
// and select/multi-select options from spec/types.md.
Item {
    id: page

    property var appWindow: null
    property bool shown: false
    signal openTypeObjectsRequested(string typeId)

    property bool creating: true
    property string selectedTypeId: "web"
    property int typesRev: 0

    // New type draft state
    property string draftName: ""
    property string draftIcon: "people"
    property string draftColor: "orange"
    property var draftProps: [
        { name: qsTr("Date"), kind: "date" },
        { name: qsTr("Status"), kind: "select" }
    ]
    property string newPropName: ""
    property string newPropKind: "text"
    property bool kindPickerOpen: false

    // Inspect-mode add property state
    property string addPropName: ""
    property string addPropKind: "text"

    readonly property int dataRev: (shown && ws) ? ws.listRevision : 0
    readonly property var allTypesList: { dataRev; typesRev; return ws ? ws.allTypes() : [] }

    Connections {
        target: ws
        function onTypesChanged() { page.typesRev++ }
    }

    readonly property var iconCatalog: [
        "people", "note", "quote", "bulb", "code", "star", "pin", "recipe",
        "book", "project", "calendar", "audio", "video", "web", "table", "habit"
    ]
    readonly property var kindCatalog: [
        { key: "text", label: qsTr("Text"), icon: "text", hint: "" },
        { key: "number", label: qsTr("Number"), icon: "number", hint: "" },
        { key: "date", label: qsTr("Date"), icon: "calendar", hint: "" },
        { key: "select", label: qsTr("Select"), icon: "select", hint: qsTr("1 option") },
        { key: "multi_select", label: qsTr("Multi-select"), icon: "multi", hint: qsTr("many") },
        { key: "checkbox", label: qsTr("Checkbox"), icon: "task", hint: "" },
        { key: "url", label: qsTr("URL"), icon: "web", hint: "" },
        { key: "object", label: qsTr("Object reference"), icon: "shape", hint: "@type" }
    ]

    function kindLabel(k) {
        for (let i = 0; i < kindCatalog.length; i++)
            if (kindCatalog[i].key === k) return kindCatalog[i].label
        return k
    }
    function kindIcon(k) {
        for (let i = 0; i < kindCatalog.length; i++)
            if (kindCatalog[i].key === k) return kindCatalog[i].icon
        return "text"
    }

    function createNow() {
        const clean = draftName.trim()
        if (!clean || !ws) return
        const tid = ws.createCustomType(clean, draftColor, draftProps, draftIcon)
        if (tid.length > 0) {
            selectedTypeId = tid
            creating = false
            draftName = ""
            if (appWindow) appWindow.showToast(qsTr("Type “%1” created").arg(clean))
        }
    }

    RowLayout {
        anchors.fill: parent
        spacing: 0

        // Left sidebar: All types list (300px — Types.dc.html)
        Rectangle {
            Layout.preferredWidth: 300
            Layout.fillHeight: true
            color: Theme.page

            Rectangle {
                anchors.top: parent.top; anchors.bottom: parent.bottom; anchors.right: parent.right
                width: 1; color: Theme.line
            }

            ColumnLayout {
                anchors.fill: parent
                anchors.margins: 16
                anchors.topMargin: 24
                spacing: 8

                RowLayout {
                    Layout.fillWidth: true
                    Layout.leftMargin: 8
                    Layout.rightMargin: 4
                    Text {
                        Layout.fillWidth: true
                        text: Theme.caps(qsTr("TYPES · %1").arg(page.allTypesList.length))
                        color: Theme.label
                        font.family: Theme.mono
                        font.pixelSize: 11
                    }
                    Button {
                        small: true
                        primary: page.creating
                        text: qsTr("+ New")
                        onClicked: page.creating = true
                    }
                }

                Flickable {
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    contentWidth: width
                    contentHeight: typesCol.implicitHeight + 16
                    clip: true
                    boundsBehavior: Flickable.StopAtBounds
                    ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }

                    ColumnLayout {
                        id: typesCol
                        width: parent.width
                        spacing: 1

                        Repeater {
                            model: page.allTypesList
                            delegate: Rectangle {
                                required property var modelData
                                readonly property bool active: !page.creating && page.selectedTypeId === modelData.id
                                readonly property int count: { page.dataRev; return ws ? (ws.typeCounts()[modelData.id] || 0) : 0 }
                                readonly property color tone: {
                                    if (Theme.typeIcon(modelData.id) !== "shape") return Theme.typeColor(modelData.id)
                                    const c = String(modelData.color || "")
                                    if (c.startsWith("#")) return c
                                    if (c && Theme.paletteHex[c] !== undefined) return Theme.paletteColor(c)
                                    return Theme.typeColor(modelData.id)
                                }
                                Layout.fillWidth: true
                                implicitHeight: 36
                                color: active ? Theme.accent : rowHover.hovered ? Theme.panel2 : "transparent"

                                RowLayout {
                                    anchors.fill: parent
                                    anchors.leftMargin: 10
                                    anchors.rightMargin: 10
                                    spacing: 10
                                    TintedIcon {
                                        name: (modelData.icon && /^[a-z0-9-]+$/i.test(modelData.icon)) ? modelData.icon : Theme.typeIcon(modelData.id)
                                        size: 16
                                        color: active ? Theme.accentInk : tone
                                    }
                                    Text {
                                        Layout.fillWidth: true
                                        text: modelData.name || modelData.id
                                        color: active ? Theme.accentInk : (rowHover.hovered ? Theme.text : Theme.dim)
                                        font.family: Theme.body
                                        font.pixelSize: 14
                                        elide: Text.ElideRight
                                    }
                                    Text {
                                        text: String(count)
                                        color: active ? Theme.accentInk : Theme.label
                                        font.family: Theme.mono
                                        font.pixelSize: 11
                                    }
                                }
                                HoverHandler { id: rowHover; cursorShape: Qt.PointingHandCursor }
                                TapHandler {
                                    onTapped: {
                                        page.selectedTypeId = modelData.id
                                        page.creating = false
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Right main content: Creator OR Inspector
        ColumnLayout {
            Layout.fillWidth: true
            Layout.fillHeight: true
            spacing: 0

            // Top header bar (64px)
            Item {
                Layout.fillWidth: true
                Layout.preferredHeight: 64

                Rectangle {
                    anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom
                    height: 1; color: Theme.line
                }

                RowLayout {
                    anchors.fill: parent
                    anchors.leftMargin: 36
                    anchors.rightMargin: 36
                    spacing: 10

                    Text {
                        text: Theme.caps(qsTr("SETTINGS / TYPES /"))
                        color: Theme.label
                        font.family: Theme.mono
                        font.pixelSize: 11
                    }
                    Text {
                        readonly property var info: ws ? ws.typeInfo(page.selectedTypeId) : null
                        text: page.creating ? qsTr("New type") : (info && info.name ? info.name : page.selectedTypeId)
                        color: Theme.text
                        font.family: Theme.body
                        font.pixelSize: 15
                    }
                    Item { Layout.fillWidth: true }

                    Button {
                        visible: !page.creating
                        text: qsTr("Open objects")
                        onClicked: page.openTypeObjectsRequested(page.selectedTypeId)
                    }
                    Button {
                        visible: page.creating
                        primary: true
                        text: qsTr("Create type")
                        enabled: page.draftName.trim().length > 0
                        onClicked: page.createNow()
                    }
                }
            }

            // -------- CREATE MODE (Types.dc.html) --------
            Flickable {
                visible: page.creating
                Layout.fillWidth: true
                Layout.fillHeight: true
                contentWidth: width
                contentHeight: createRow.implicitHeight + 72
                clip: true
                boundsBehavior: Flickable.StopAtBounds

                RowLayout {
                    id: createRow
                    x: 36; y: 32
                    width: parent.width - 72
                    spacing: 44

                    // Left form column
                    ColumnLayout {
                        Layout.fillWidth: true
                        Layout.alignment: Qt.AlignTop
                        spacing: 0

                        Text {
                            text: Theme.caps(qsTr("NAME"))
                            color: Theme.label; font.family: Theme.mono; font.pixelSize: 11
                            Layout.bottomMargin: 8
                        }
                        Rectangle {
                            Layout.fillWidth: true
                            Layout.preferredHeight: 40
                            color: "transparent"
                            border.width: 1
                            border.color: nameField.activeFocus ? Theme.text : Theme.line2
                            TextInput {
                                id: nameField
                                anchors.fill: parent
                                anchors.leftMargin: 12; anchors.rightMargin: 12
                                verticalAlignment: TextInput.AlignVCenter
                                color: Theme.text
                                font.family: Theme.body; font.pixelSize: 15
                                text: page.draftName
                                onTextEdited: page.draftName = text
                                Text {
                                    anchors.verticalCenter: parent.verticalCenter
                                    visible: nameField.length === 0
                                    text: qsTr("Meeting, Recipe, Experiment…")
                                    color: Theme.faint; font: nameField.font
                                }
                            }
                        }

                        // Icon grid (16 icons)
                        Text {
                            Layout.topMargin: 24; Layout.bottomMargin: 10
                            text: Theme.caps(qsTr("ICON"))
                            color: Theme.label; font.family: Theme.mono; font.pixelSize: 11
                        }
                        Flow {
                            Layout.fillWidth: true
                            spacing: 6
                            Repeater {
                                model: page.iconCatalog
                                delegate: Rectangle {
                                    required property string modelData
                                    readonly property bool on: page.draftIcon === modelData
                                    width: 40; height: 40
                                    color: on ? Theme.accent : "transparent"
                                    border.width: 1
                                    border.color: on ? Theme.accent : icHov.hovered ? Theme.line3 : Theme.line
                                    TintedIcon {
                                        anchors.centerIn: parent
                                        name: modelData; size: 18
                                        color: on ? Theme.accentInk : icHov.hovered ? Theme.text : Theme.muted
                                    }
                                    HoverHandler { id: icHov; cursorShape: Qt.PointingHandCursor }
                                    TapHandler { onTapped: page.draftIcon = modelData }
                                }
                            }
                        }

                        // Color palette (10 fixed colors)
                        Text {
                            Layout.topMargin: 24; Layout.bottomMargin: 10
                            text: Theme.caps(qsTr("COLOR · PALETTE OF 10"))
                            color: Theme.label; font.family: Theme.mono; font.pixelSize: 11
                        }
                        Row {
                            spacing: 4
                            Repeater {
                                model: Theme.paletteNames
                                delegate: Rectangle {
                                    required property string modelData
                                    readonly property bool on: page.draftColor === modelData
                                    width: 32; height: 32
                                    color: "transparent"
                                    border.width: 1
                                    border.color: on ? Theme.text : "transparent"
                                    Rectangle {
                                        anchors.fill: parent; anchors.margins: 4
                                        color: Theme.paletteColor(modelData)
                                    }
                                    HoverHandler { cursorShape: Qt.PointingHandCursor }
                                    TapHandler { onTapped: page.draftColor = modelData }
                                }
                            }
                        }

                        // Properties list
                        Text {
                            Layout.topMargin: 28; Layout.bottomMargin: 6
                            text: Theme.caps(qsTr("PROPERTIES"))
                            color: Theme.label; font.family: Theme.mono; font.pixelSize: 11
                        }
                        // Fixed Title row
                        Rectangle {
                            Layout.fillWidth: true; Layout.preferredHeight: 42
                            color: "transparent"
                            Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.lineSubtle }
                            RowLayout {
                                anchors.fill: parent; spacing: 10
                                TintedIcon { name: "text"; size: 15; color: Theme.label }
                                Text { Layout.fillWidth: true; text: qsTr("Title"); color: Theme.text; font.family: Theme.body; font.pixelSize: 14 }
                                Text { text: Theme.caps(qsTr("FIXED")); color: Theme.label; font.family: Theme.mono; font.pixelSize: 11 }
                            }
                        }
                        Repeater {
                            model: page.draftProps
                            delegate: Rectangle {
                                required property var modelData
                                required property int index
                                Layout.fillWidth: true; Layout.preferredHeight: 42
                                color: "transparent"
                                Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.lineSubtle }
                                RowLayout {
                                    anchors.fill: parent; spacing: 10
                                    TintedIcon { name: page.kindIcon(modelData.kind); size: 15; color: Theme.label }
                                    Text { Layout.fillWidth: true; text: modelData.name; color: Theme.text; font.family: Theme.body; font.pixelSize: 14 }
                                    Text { text: Theme.caps(page.kindLabel(modelData.kind)); color: Theme.muted; font.family: Theme.mono; font.pixelSize: 11 }
                                    IconButton {
                                        implicitWidth: 26; implicitHeight: 26; icon: "x"
                                        onClicked: {
                                            const copy = page.draftProps.slice()
                                            copy.splice(index, 1)
                                            page.draftProps = copy
                                        }
                                    }
                                }
                            }
                        }

                        // Add property row
                        RowLayout {
                            Layout.fillWidth: true
                            Layout.topMargin: 14
                            spacing: 8

                            Rectangle {
                                Layout.fillWidth: true
                                Layout.preferredHeight: 36
                                color: "transparent"
                                border.width: 1
                                border.color: dpInput.activeFocus ? Theme.text : Theme.line2
                                TextInput {
                                    id: dpInput
                                    anchors.fill: parent
                                    anchors.leftMargin: 10; anchors.rightMargin: 10
                                    verticalAlignment: TextInput.AlignVCenter
                                    color: Theme.text; font.family: Theme.body; font.pixelSize: 13
                                    text: page.newPropName
                                    onTextEdited: page.newPropName = text
                                    onAccepted: addDraftPropBtn.doAdd()
                                    Text {
                                        anchors.verticalCenter: parent.verticalCenter
                                        visible: dpInput.length === 0
                                        text: qsTr("Property name…")
                                        color: Theme.faint; font: dpInput.font
                                    }
                                }
                            }
                            Button {
                                text: page.kindLabel(page.newPropKind) + " ▾"
                                onClicked: kindMenu.open()
                                Popup {
                                    id: kindMenu
                                    y: parent.height + 4
                                    width: 240
                                    padding: 4
                                    background: Rectangle { color: Theme.sheet; border.width: 1; border.color: Theme.line2 }
                                    contentItem: ColumnLayout {
                                        spacing: 0
                                        Repeater {
                                            model: page.kindCatalog
                                            delegate: Rectangle {
                                                required property var modelData
                                                readonly property bool active: page.newPropKind === modelData.key
                                                Layout.fillWidth: true; implicitHeight: 34
                                                color: (active || kmHov.hovered) ? Theme.accent : "transparent"
                                                RowLayout {
                                                    anchors.fill: parent; anchors.leftMargin: 10; anchors.rightMargin: 10; spacing: 10
                                                    TintedIcon { name: modelData.icon; size: 15; color: (active || kmHov.hovered) ? Theme.accentInk : Theme.dim }
                                                    Text { Layout.fillWidth: true; text: modelData.label; color: (active || kmHov.hovered) ? Theme.accentInk : Theme.text; font.family: Theme.body; font.pixelSize: 13 }
                                                    Text { visible: modelData.hint.length > 0; text: Theme.caps(modelData.hint); color: (active || kmHov.hovered) ? Theme.accentInk : Theme.label; font.family: Theme.mono; font.pixelSize: 10 }
                                                }
                                                HoverHandler { id: kmHov; cursorShape: Qt.PointingHandCursor }
                                                TapHandler { onTapped: { page.newPropKind = modelData.key; kindMenu.close() } }
                                            }
                                        }
                                    }
                                }
                            }
                            Button {
                                id: addDraftPropBtn
                                text: qsTr("+ Add")
                                enabled: page.newPropName.trim().length > 0
                                function doAdd() {
                                    const n = page.newPropName.trim()
                                    if (!n) return
                                    page.draftProps = page.draftProps.concat([{ name: n, kind: page.newPropKind }])
                                    page.newPropName = ""
                                }
                                onClicked: doAdd()
                            }
                        }
                    }

                    // Right column: Live template preview (360px — Types.dc.html)
                    ColumnLayout {
                        Layout.preferredWidth: 360
                        Layout.alignment: Qt.AlignTop
                        spacing: 10

                        Text {
                            text: Theme.caps(qsTr("PREVIEW · HOW %1 STARTS").arg((page.draftName.trim() || qsTr("A NEW OBJECT")).toUpperCase()))
                            color: Theme.label; font.family: Theme.mono; font.pixelSize: 11
                        }
                        Rectangle {
                            Layout.fillWidth: true
                            implicitHeight: Math.max(460, previewCol.implicitHeight + 52)
                            color: Theme.page
                            border.width: 1
                            border.color: Theme.line

                            ColumnLayout {
                                id: previewCol
                                anchors.left: parent.left; anchors.right: parent.right; anchors.top: parent.top
                                anchors.margins: 24
                                spacing: 0

                                Rectangle {
                                    implicitWidth: prevChipRow.implicitWidth + 18
                                    implicitHeight: 26
                                    color: "transparent"
                                    border.width: 1; border.color: Theme.line2
                                    Row {
                                        id: prevChipRow
                                        anchors.centerIn: parent; spacing: 7
                                        TintedIcon { name: page.draftIcon; size: 14; color: Theme.paletteColor(page.draftColor) }
                                        Text { text: page.draftName.trim() || qsTr("Meeting"); color: Theme.text; font.family: Theme.body; font.pixelSize: 13 }
                                    }
                                }

                                Text {
                                    Layout.topMargin: 18; Layout.bottomMargin: 16
                                    text: qsTr("Untitled")
                                    color: Theme.label; font.family: Theme.display; font.pixelSize: 26; font.letterSpacing: -0.4
                                }

                                Repeater {
                                    model: page.draftProps
                                    delegate: Rectangle {
                                        required property var modelData
                                        Layout.fillWidth: true; Layout.preferredHeight: 40
                                        color: "transparent"
                                        Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.lineSubtle }
                                        RowLayout {
                                            anchors.fill: parent; spacing: 10
                                            Text { Layout.preferredWidth: 100; text: modelData.name; color: Theme.label; font.family: Theme.body; font.pixelSize: 13; elide: Text.ElideRight }
                                            Text {
                                                Layout.fillWidth: true
                                                text: modelData.kind === "date" ? qsTr("Today")
                                                    : modelData.kind === "checkbox" ? qsTr("No")
                                                    : qsTr("Empty")
                                                color: Theme.muted; font.family: Theme.body; font.pixelSize: 13
                                            }
                                        }
                                    }
                                }

                                Text {
                                    Layout.topMargin: 24
                                    text: qsTr("Notes")
                                    color: Theme.text; font.family: Theme.body; font.pixelSize: 15; font.weight: Font.Medium
                                }
                                Text {
                                    Layout.topMargin: 6
                                    text: qsTr("Write blocks below…")
                                    color: Theme.label; font.family: Theme.body; font.pixelSize: 14
                                }
                            }
                        }
                    }
                }
            }

            // -------- INSPECT MODE (SpecialTypes.dc.html) --------
            Flickable {
                visible: !page.creating
                Layout.fillWidth: true
                Layout.fillHeight: true
                contentWidth: width
                contentHeight: inspectCol.implicitHeight + 72
                clip: true
                boundsBehavior: Flickable.StopAtBounds

                ColumnLayout {
                    id: inspectCol
                    x: 36; y: 32
                    width: parent.width - 72
                    spacing: 0

                    readonly property var info: { page.typesRev; return ws ? ws.typeInfo(page.selectedTypeId) : ({}) }
                    readonly property var propsList: { page.typesRev; return ws ? ws.typeProperties(page.selectedTypeId) : [] }

                    RowLayout {
                        spacing: 14
                        Text {
                            text: inspectCol.info.name || page.selectedTypeId
                            color: Theme.text; font.family: Theme.display; font.pixelSize: 30; font.letterSpacing: -0.5
                        }
                        Rectangle {
                            implicitWidth: stdLbl.implicitWidth + 14; implicitHeight: 22
                            color: "transparent"; border.width: 1; border.color: Theme.line2
                            Text {
                                id: stdLbl; anchors.centerIn: parent
                                text: Theme.caps(inspectCol.info.standard ? qsTr("STANDARD TYPE") : qsTr("CUSTOM TYPE"))
                                color: Theme.muted; font.family: Theme.mono; font.pixelSize: 10
                            }
                        }
                    }

                    // Table header
                    Rectangle {
                        Layout.fillWidth: true; Layout.topMargin: 24; Layout.preferredHeight: 34
                        color: "transparent"
                        Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.line }
                        RowLayout {
                            anchors.fill: parent; spacing: 16
                            Text { Layout.preferredWidth: 180; text: Theme.caps(qsTr("PROPERTY")); color: Theme.label; font.family: Theme.mono; font.pixelSize: 11 }
                            Text { Layout.preferredWidth: 140; text: Theme.caps(qsTr("KIND")); color: Theme.label; font.family: Theme.mono; font.pixelSize: 11 }
                            Text { Layout.fillWidth: true; text: Theme.caps(qsTr("OPTIONS")); color: Theme.label; font.family: Theme.mono; font.pixelSize: 11 }
                        }
                    }

                    Repeater {
                        model: inspectCol.propsList
                        delegate: Rectangle {
                            id: propInspectRow
                            required property var modelData
                            readonly property string propKey: modelData.key || ""
                            readonly property bool hasOptionList: modelData.kind === "select" || modelData.kind === "multi_select"
                            property string newOptText: ""
                            property int newOptColorIdx: 0
                            readonly property var optPalette: ["blue", "purple", "red", "green", "yellow", "orange", "teal", "pink", "ice", "gray"]
                            Layout.fillWidth: true
                            implicitHeight: Math.max(46, propRowInner.implicitHeight + 22)
                            color: "transparent"
                            Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.lineSubtle }

                            RowLayout {
                                id: propRowInner
                                anchors.left: parent.left; anchors.right: parent.right
                                anchors.verticalCenter: parent.verticalCenter
                                spacing: 16

                                RowLayout {
                                    Layout.preferredWidth: 180
                                    Layout.alignment: Qt.AlignTop
                                    spacing: 8
                                    Text { text: propInspectRow.modelData.name || propInspectRow.propKey; color: Theme.bodyText; font.family: Theme.body; font.pixelSize: 14 }
                                    Rectangle {
                                        visible: !!propInspectRow.modelData.system
                                        implicitWidth: sysTxt.implicitWidth + 10; implicitHeight: 18
                                        color: "transparent"; border.width: 1; border.color: Theme.line2
                                        Text { id: sysTxt; anchors.centerIn: parent; text: Theme.caps(qsTr("SYSTEM")); color: Theme.label; font.family: Theme.mono; font.pixelSize: 9 }
                                    }
                                }
                                Text {
                                    Layout.preferredWidth: 140
                                    Layout.alignment: Qt.AlignTop
                                    text: Theme.caps(page.kindLabel(propInspectRow.modelData.kind || "text"))
                                    color: Theme.muted; font.family: Theme.mono; font.pixelSize: 11
                                }
                                Flow {
                                    Layout.fillWidth: true
                                    spacing: 6
                                    Repeater {
                                        model: (propInspectRow.modelData.options && propInspectRow.modelData.options.length > 0)
                                               ? propInspectRow.modelData.options : []
                                        delegate: Rectangle {
                                            id: optChip
                                            required property var modelData
                                            implicitWidth: optRow.implicitWidth + 16; implicitHeight: 26
                                            color: "transparent"
                                            border.width: 1; border.color: optChipHov.hovered ? Theme.line3 : Theme.line2
                                            Row {
                                                id: optRow; anchors.centerIn: parent; spacing: 7
                                                Rectangle {
                                                    anchors.verticalCenter: parent.verticalCenter
                                                    width: 7; height: 7
                                                    color: Theme.paletteColor(optChip.modelData.color || "gray")
                                                }
                                                Text {
                                                    anchors.verticalCenter: parent.verticalCenter
                                                    text: optChip.modelData.label || optChip.modelData.id
                                                    color: Theme.dim; font.family: Theme.body; font.pixelSize: 13
                                                }
                                                Text {
                                                    visible: optChipHov.hovered
                                                    anchors.verticalCenter: parent.verticalCenter
                                                    text: "×"
                                                    color: Theme.label; font.pixelSize: 13
                                                    TapHandler {
                                                        onTapped: if (ws) ws.removeOptionFromProperty(page.selectedTypeId, propInspectRow.propKey, optChip.modelData.id)
                                                    }
                                                }
                                            }
                                            HoverHandler { id: optChipHov; cursorShape: Qt.PointingHandCursor }
                                        }
                                    }
                                    // Inline "+ Add option" for select / multi_select properties (SpecialTypes.dc.html)
                                    Rectangle {
                                        visible: propInspectRow.hasOptionList
                                        implicitWidth: 158; implicitHeight: 26
                                        color: "transparent"
                                        border.width: 1
                                        border.color: addOptInput.activeFocus ? Theme.text : Theme.line2
                                        RowLayout {
                                            anchors.fill: parent; anchors.leftMargin: 6; anchors.rightMargin: 6; spacing: 6
                                            Rectangle {
                                                Layout.preferredWidth: 8; Layout.preferredHeight: 8
                                                color: Theme.paletteColor(propInspectRow.optPalette[propInspectRow.newOptColorIdx])
                                                HoverHandler { cursorShape: Qt.PointingHandCursor }
                                                TapHandler { onTapped: propInspectRow.newOptColorIdx = (propInspectRow.newOptColorIdx + 1) % propInspectRow.optPalette.length }
                                            }
                                            TextInput {
                                                id: addOptInput
                                                Layout.fillWidth: true
                                                verticalAlignment: TextInput.AlignVCenter
                                                color: Theme.text; font.family: Theme.body; font.pixelSize: 12; clip: true
                                                text: propInspectRow.newOptText
                                                onTextEdited: propInspectRow.newOptText = text
                                                onAccepted: {
                                                    const lbl = propInspectRow.newOptText.trim()
                                                    if (lbl.length > 0 && ws) {
                                                        if (ws.addOptionToProperty(page.selectedTypeId, propInspectRow.propKey, lbl,
                                                                                   propInspectRow.optPalette[propInspectRow.newOptColorIdx])) {
                                                            propInspectRow.newOptText = ""
                                                        }
                                                    }
                                                }
                                                Text {
                                                    anchors.verticalCenter: parent.verticalCenter
                                                    visible: addOptInput.length === 0
                                                    text: qsTr("+ Add option…")
                                                    color: Theme.faint; font: addOptInput.font
                                                }
                                            }
                                        }
                                    }
                                    Text {
                                        visible: !propInspectRow.hasOptionList && (!propInspectRow.modelData.options || propInspectRow.modelData.options.length === 0)
                                        text: qsTr("—")
                                        color: Theme.faint; font.family: Theme.body; font.pixelSize: 13
                                    }
                                }
                            }
                        }
                    }

                    // Add property to existing type
                    RowLayout {
                        Layout.fillWidth: true; Layout.topMargin: 24; spacing: 8
                        Rectangle {
                            Layout.fillWidth: true; Layout.maximumWidth: 320; Layout.preferredHeight: 36
                            color: "transparent"; border.width: 1; border.color: addPInput.activeFocus ? Theme.text : Theme.line2
                            TextInput {
                                id: addPInput
                                anchors.fill: parent; anchors.leftMargin: 10; anchors.rightMargin: 10
                                verticalAlignment: TextInput.AlignVCenter
                                color: Theme.text; font.family: Theme.body; font.pixelSize: 13
                                text: page.addPropName
                                onTextEdited: page.addPropName = text
                                Text { anchors.verticalCenter: parent.verticalCenter; visible: addPInput.length === 0; text: qsTr("Add property to this type…"); color: Theme.faint; font: addPInput.font }
                            }
                        }
                        Button {
                            text: page.kindLabel(page.addPropKind)
                            onClicked: {
                                const ks = ["text", "number", "date", "select", "multi_select", "checkbox", "url"]
                                page.addPropKind = ks[(ks.indexOf(page.addPropKind) + 1) % ks.length]
                            }
                        }
                        Button {
                            primary: true
                            text: qsTr("+ Add property")
                            enabled: page.addPropName.trim().length > 0
                            onClicked: {
                                if (ws && ws.addPropertyToType(page.selectedTypeId, page.addPropName, page.addPropKind)) {
                                    page.addPropName = ""
                                }
                            }
                        }
                        Item { Layout.fillWidth: true }
                    }
                }
            }
        }
    }
}
