import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "."

// Library: live objects browser — search, type filter, virtualized grid/list.

Item {
    id: library

    property var appWindow: null
    property string filterType: (appWindow && appWindow.libraryType !== undefined)
                                ? appWindow.libraryType
                                : ((typeof app !== "undefined" && app.libraryType !== undefined) ? app.libraryType : "all")
    property string searchQuery: ""
    property bool gridMode: true
    signal openObjectRequested(string objectId)

    function update() { }

    readonly property int dataRev: ws ? ws.listRevision : 0
    property var items: []
    readonly property real gridGap: 12
    onFilterTypeChanged: if (visible) items = filtered()
    onSearchQueryChanged: if (visible) items = filtered()
    onDataRevChanged: if (visible) items = filtered()
    onVisibleChanged: if (visible) items = filtered()

    Connections {
        target: library.appWindow
        function onLibraryTypeChanged() {
            if (library.appWindow && library.appWindow.libraryType !== undefined)
                library.filterType = library.appWindow.libraryType
        }
    }

    Component.onCompleted: items = filtered()

    function filtered() {
        if (!ws)
            return []
        const query = searchQuery.trim().toLowerCase()
        if (filterType === "trash") {
            const trashed = ws.trashedObjects()
            if (query.length === 0)
                return trashed
            return trashed.filter(it => String(it.title || "").toLowerCase().includes(query)
                                     || String(it.snippet || "").toLowerCase().includes(query))
        }
        if (query.length > 0)
            return ws.searchObjects(query, 200)
        const type = filterType === "all" ? "" : filterType
        const out = []
        const types = type.length > 0 ? [type] : ws.allTypes().map(t => t.id)
        for (let i = 0; i < types.length; i++) {
            const list = ws.objectsByType(types[i], 0)
            for (let j = 0; j < list.length; j++)
                out.push(list[j])
        }
        out.sort((a, b) => String(b.updatedAt).localeCompare(String(a.updatedAt)))
        return out
    }

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: Theme.pagePad
        spacing: 18

        // search + filters
        RowLayout {
            Layout.fillWidth: true
            spacing: 12

            Rectangle {
                Layout.fillWidth: true
                Layout.preferredHeight: 42
                radius: 21
                color: Theme.panel
                border.width: 1
                border.color: searchField.activeFocus ? Theme.ice : "transparent"

                RowLayout {
                    anchors.fill: parent
                    anchors.leftMargin: 12
                    anchors.rightMargin: 12
                    spacing: 8

                    TintedIcon { name: "search"; size: 16; color: Theme.dim }
                    TextInput {
                        id: searchField
                        Layout.fillWidth: true
                        verticalAlignment: TextInput.AlignVCenter
                        color: Theme.text
                        font.family: Theme.body
                        font.pixelSize: 15
                        clip: true
                        onTextEdited: library.searchQuery = text
                        Text { anchors.verticalCenter: parent.verticalCenter; visible: searchField.length === 0; text: qsTr("Search objects…"); color: Theme.faint; font: searchField.font }
                    }
                }
            }

            // Filtered by one type: create an object of that type (through quick capture, so
            // images still get their file picker).
            Button {
                readonly property var filteredType: (dataRev >= 0 && ws && library.filterType !== "all"
                                                     && library.filterType !== "trash")
                                                    ? ws.allTypes().find(t => t.id === library.filterType) : undefined
                visible: filteredType !== undefined
                text: filteredType ? Theme.caps(qsTr("+ NEW %1")).arg(Theme.slate ? String(filteredType.name) : String(filteredType.name).toUpperCase()) : ""
                primary: true
                onClicked: if (library.appWindow) library.appWindow.openCaptureFor(library.filterType)
            }

            Button {
                text: Theme.caps(qsTr("TYPES"))
                onClicked: typeManagerPopup.open()
            }

            Button {
                text: library.gridMode ? Theme.caps(qsTr("LIST")) : Theme.caps(qsTr("GRID"))
                onClicked: library.gridMode = !library.gridMode
            }
        }

        // type chips (wrap onto more lines instead of widening the page)
        Flow {
            Layout.fillWidth: true
            spacing: 8

            Repeater {
                model: [{ id: "all", name: qsTr("All") }]
                       .concat((dataRev >= 0 && ws) ? ws.allTypes() : [])
                       .concat([{ id: "trash", name: qsTr("Trash") }])

                delegate: Chip {
                    required property var modelData
                    text: modelData.name
                    typeId: modelData.id !== "all" && modelData.id !== "trash" ? modelData.id : ""
                    icon: modelData.id === "all" ? "layout-grid" : modelData.id === "trash" ? "x" : ""
                    active: library.filterType === modelData.id
                    onClicked: library.filterType = modelData.id
                }
            }
        }

        // virtualized content viewport
        Item {
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true

            GridView {
                id: gridView
                anchors.fill: parent
                visible: library.gridMode && library.items.length > 0
                readonly property int cols: Math.max(1, Math.floor((width + library.gridGap) / (260 + library.gridGap)))
                cellWidth: Math.floor((width + library.gridGap) / cols)
                cellHeight: 176 + library.gridGap
                cacheBuffer: 360
                boundsBehavior: Flickable.StopAtBounds
                ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }
                model: library.gridMode ? library.items : []

                delegate: Item {
                    required property var modelData
                    width: gridView.cellWidth
                    height: gridView.cellHeight

                    ObjectCard {
                        width: Math.max(120, gridView.cellWidth - library.gridGap)
                        height: 176
                        summary: modelData
                        onOpenRequested: {
                            if (modelData.trashed)
                                ws.restoreObject(modelData.id)
                            else
                                library.openObjectRequested(modelData.id)
                        }
                        onOpenInPaneRequested: if (library.appWindow && !modelData.trashed) library.appWindow.openInPane(modelData.id)
                        onRestoreRequested: ws.restoreObject(modelData.id)
                    }
                }
            }

            ListView {
                id: listView
                anchors.fill: parent
                visible: !library.gridMode && library.items.length > 0
                cacheBuffer: 260
                boundsBehavior: Flickable.StopAtBounds
                ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }
                model: !library.gridMode ? library.items : []

                delegate: ObjectRow {
                    required property var modelData
                    width: listView.width
                    summary: modelData
                    onOpenRequested: {
                        if (modelData.trashed)
                            ws.restoreObject(modelData.id)
                        else
                            library.openObjectRequested(modelData.id)
                    }
                    onOpenInPaneRequested: if (library.appWindow && !modelData.trashed) library.appWindow.openInPane(modelData.id)
                    onRestoreRequested: ws.restoreObject(modelData.id)
                }
            }

            Text {
                visible: library.items.length === 0
                text: searchQuery.length > 0 ? qsTr("no matches for the search") : qsTr("no objects yet")
                color: Theme.faint
                font.family: Theme.mono
                font.pixelSize: 11
            }
        }
    }

    function openTypeManager() { typeManagerPopup.open() }

    Popup {
        id: typeManagerPopup
        anchors.centerIn: Overlay.overlay
        width: Math.min(540, library.width - 40)
        height: Math.min(560, library.height - 40)
        padding: 18
        modal: true
        closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside

        property string selectedTypeId: "note"
        property string newTypeName: ""
        property string newTypeColor: "blue"
        property string propNameInput: ""
        property string propKindInput: "text"
        property int typesRev: 0

        Connections {
            target: ws
            function onTypesChanged() { ++typeManagerPopup.typesRev }
        }

        background: Rectangle {
            radius: Theme.radiusLg
            color: Theme.sheet
            border.color: Theme.line2
            border.width: 1
        }

        contentItem: ColumnLayout {
            spacing: 12

            RowLayout {
                Layout.fillWidth: true
                Text {
                    text: Theme.caps(qsTr("TYPES & CUSTOM PROPERTIES"))
                    color: Theme.text
                    font.family: Theme.mono
                    font.pixelSize: 12
                    font.letterSpacing: Theme.track(1.4)
                    Layout.fillWidth: true
                }
                IconButton { icon: "x"; onClicked: typeManagerPopup.close() }
            }

            // Create new custom type
            RowLayout {
                Layout.fillWidth: true
                spacing: 8

                Rectangle {
                    Layout.fillWidth: true
                    Layout.preferredHeight: 34
                    radius: Theme.radius
                    color: Theme.panel
                    border.color: newTypeField.activeFocus ? Theme.ice : Theme.line
                    border.width: 1
                    TextInput {
                        id: newTypeField
                        anchors.fill: parent
                        anchors.leftMargin: 10
                        anchors.rightMargin: 10
                        verticalAlignment: TextInput.AlignVCenter
                        color: Theme.text
                        font.family: Theme.body
                        font.pixelSize: 13
                        clip: true
                        onTextChanged: typeManagerPopup.newTypeName = text
                        Text { anchors.verticalCenter: parent.verticalCenter; visible: newTypeField.length === 0; text: qsTr("New custom type name (e.g. Recipe, Bug)…"); color: Theme.faint; font: newTypeField.font }
                    }
                }

                Rectangle {
                    Layout.preferredWidth: 74
                    Layout.preferredHeight: 34
                    radius: Theme.radius
                    color: Theme.panel2
                    border.color: Theme.line
                    border.width: 1
                    RowLayout {
                        anchors.centerIn: parent
                        spacing: 6
                        Rectangle { width: 8; height: 8; radius: 4; color: Theme.paletteColor(typeManagerPopup.newTypeColor) }
                        Text { text: typeManagerPopup.newTypeColor.toUpperCase(); color: Theme.dim; font.family: Theme.mono; font.pixelSize: 10 }
                    }
                    TapHandler {
                        onTapped: {
                            const colors = ["purple", "blue", "teal", "green", "yellow", "orange", "pink", "red"]
                            const i = colors.indexOf(typeManagerPopup.newTypeColor)
                            typeManagerPopup.newTypeColor = colors[(i + 1) % colors.length]
                        }
                    }
                }

                Button {
                    text: Theme.caps(qsTr("CREATE TYPE"))
                    small: true
                    enabled: typeManagerPopup.newTypeName.trim().length > 0
                    onClicked: {
                        const tid = ws.createCustomType(typeManagerPopup.newTypeName, typeManagerPopup.newTypeColor, [])
                        if (tid.length > 0) {
                            typeManagerPopup.selectedTypeId = tid
                            newTypeField.text = ""
                            typeManagerPopup.newTypeName = ""
                        }
                    }
                }
            }

            // Pick type to inspect / add properties
            Flow {
                Layout.fillWidth: true
                spacing: 6
                Repeater {
                    model: (typeManagerPopup.typesRev >= 0 && ws) ? ws.allTypes() : []
                    delegate: Chip {
                        required property var modelData
                        text: modelData.name
                        active: typeManagerPopup.selectedTypeId === modelData.id
                        onClicked: typeManagerPopup.selectedTypeId = modelData.id
                    }
                }
            }

            Rectangle { Layout.fillWidth: true; height: 1; color: Theme.line }

            Text {
                text: Theme.caps(qsTr("SCHEMA PROPERTIES FOR %1")).arg(typeManagerPopup.selectedTypeId.toUpperCase())
                color: Theme.muted
                font.family: Theme.mono
                font.pixelSize: 11
            }

            ListView {
                Layout.fillWidth: true
                Layout.fillHeight: true
                clip: true
                spacing: 6
                model: (typeManagerPopup.typesRev >= 0 && ws) ? ws.typeProperties(typeManagerPopup.selectedTypeId) : []
                delegate: Rectangle {
                    required property var modelData
                    width: ListView.view.width
                    height: 32
                    radius: Theme.radiusSm
                    color: Theme.panel
                    RowLayout {
                        anchors.fill: parent
                        anchors.leftMargin: 10
                        anchors.rightMargin: 10
                        Text { text: modelData.name; color: Theme.text; font.family: Theme.body; font.pixelSize: 13; Layout.fillWidth: true }
                        Text { text: Theme.caps(String(modelData.kind || "text").toUpperCase()); color: Theme.dim; font.family: Theme.mono; font.pixelSize: 10 }
                    }
                }
            }

            // Add property to selected type
            RowLayout {
                Layout.fillWidth: true
                spacing: 8
                Rectangle {
                    Layout.fillWidth: true
                    Layout.preferredHeight: 32
                    radius: Theme.radius
                    color: Theme.panel
                    border.color: typePropField.activeFocus ? Theme.ice : Theme.line
                    border.width: 1
                    TextInput {
                        id: typePropField
                        anchors.fill: parent
                        anchors.leftMargin: 10
                        anchors.rightMargin: 10
                        verticalAlignment: TextInput.AlignVCenter
                        color: Theme.text
                        font.family: Theme.body
                        font.pixelSize: 12
                        clip: true
                        onTextChanged: typeManagerPopup.propNameInput = text
                        Text { anchors.verticalCenter: parent.verticalCenter; visible: typePropField.length === 0; text: qsTr("Add property to type…"); color: Theme.faint; font: typePropField.font }
                    }
                }
                Rectangle {
                    Layout.preferredWidth: 84
                    Layout.preferredHeight: 32
                    radius: Theme.radius
                    color: Theme.panel2
                    border.color: Theme.line
                    border.width: 1
                    Text { anchors.centerIn: parent; text: Theme.caps(typeManagerPopup.propKindInput.toUpperCase()); color: Theme.dim; font.family: Theme.mono; font.pixelSize: 10 }
                    TapHandler {
                        onTapped: {
                            const order = ["text", "number", "checkbox", "date", "url"]
                            const i = order.indexOf(typeManagerPopup.propKindInput)
                            typeManagerPopup.propKindInput = order[(i + 1) % order.length]
                        }
                    }
                }
                Button {
                    text: Theme.caps(qsTr("ADD PROP"))
                    small: true
                    enabled: typeManagerPopup.propNameInput.trim().length > 0
                    onClicked: {
                        if (ws.addPropertyToType(typeManagerPopup.selectedTypeId, typeManagerPopup.propNameInput, typeManagerPopup.propKindInput)) {
                            typePropField.text = ""
                            typeManagerPopup.propNameInput = ""
                        }
                    }
                }
            }
        }
    }
}
