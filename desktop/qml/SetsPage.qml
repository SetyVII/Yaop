import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "."

// Sets (spec §7, spec/query.md): saved queries with gallery / table / list views.
// Left: the saved sets. Right: the active set — header, query editor and its view.
Item {
    id: setsPage

    property var appWindow: null
    signal openObjectRequested(string objectId)

    readonly property int dataRev: ws ? ws.listRevision : 0
    property string currentSetId: ""
    property var info: ({})
    property var query: ({ version: 1 })
    property var items: []
    property string error: ""
    property string viewKind: "gallery"
    property bool editingQuery: false
    property int propsRev: 0

    readonly property var sets: (dataRev >= 0 && ws) ? ws.objectsByType("set", 0) : []
    readonly property var allTypes: (dataRev >= 0 && ws) ? ws.allTypes().filter(t => t.id !== "set") : []

    readonly property var ops: [
        { op: "eq", label: qsTr("is"), needs: "text" },
        { op: "neq", label: qsTr("is not"), needs: "text" },
        { op: "contains", label: qsTr("contains"), needs: "text" },
        { op: "not_contains", label: qsTr("does not contain"), needs: "text" },
        { op: "starts_with", label: qsTr("starts with"), needs: "text" },
        { op: "has_any", label: qsTr("has any of"), needs: "list" },
        { op: "has_all", label: qsTr("has all of"), needs: "list" },
        { op: "has_none", label: qsTr("has none of"), needs: "list" },
        { op: "gt", label: ">", needs: "number" },
        { op: "gte", label: "≥", needs: "number" },
        { op: "lt", label: "<", needs: "number" },
        { op: "lte", label: "≤", needs: "number" },
        { op: "before", label: qsTr("before"), needs: "date" },
        { op: "after", label: qsTr("after"), needs: "date" },
        { op: "on", label: qsTr("on"), needs: "date" },
        { op: "between", label: qsTr("between"), needs: "range" },
        { op: "empty", label: qsTr("is empty"), needs: "" },
        { op: "not_empty", label: qsTr("is not empty"), needs: "" },
        { op: "is_true", label: qsTr("is checked"), needs: "" },
        { op: "is_false", label: qsTr("is not checked"), needs: "" }
    ]
    function opInfo(op) {
        for (let i = 0; i < ops.length; i++)
            if (ops[i].op === op)
                return ops[i]
        return ops[0]
    }

    // Text typed in a filter value → the JSON the contract expects for that operator.
    function parseValue(op, text) {
        const need = opInfo(op).needs
        const t = String(text || "").trim()
        if (need === "")
            return undefined
        if (need === "list")
            return t.split(",").map(s => s.trim()).filter(s => s.length > 0)
        if (need === "range")
            return t.split(",").map(s => s.trim()).slice(0, 2)
        if (need === "number")
            return Number(t)
        if (need === "text" && /^-?\d+(\.\d+)?$/.test(t))
            return Number(t)
        return t
    }
    function valueText(v) {
        if (v === undefined || v === null)
            return ""
        if (Array.isArray(v))
            return v.join(", ")
        return String(v)
    }

    function typeName(id) {
        for (let i = 0; i < allTypes.length; i++)
            if (allTypes[i].id === id)
                return allTypes[i].name
        return id
    }
    function summaryText() {
        const parts = []
        const types = query.types || []
        parts.push(types.length ? qsTr("type = %1").arg(types.map(typeName).join(", ")) : qsTr("all types"))
        const f = query.filters || []
        if (f.length)
            parts.push(f.length === 1 ? qsTr("1 filter") : qsTr("%1 filters").arg(f.length))
        const s = query.sort || []
        if (s.length)
            parts.push(qsTr("by %1").arg(s[0].prop) + (s[0].dir === "desc" ? " ↓" : " ↑"))
        return parts.join(" · ")
    }

    // Table columns: title and type, every property the query looks at, and the last edit.
    readonly property var columns: {
        const cols = ["@title", "@type"]
        const add = p => { if (p && cols.indexOf(p) < 0) cols.push(p) }
        ;(query.filters || []).forEach(f => add(f.prop))
        ;(query.sort || []).forEach(s => add(s.prop))
        add("@updated_at")
        return cols
    }
    function columnLabel(p) {
        switch (p) {
        case "@title": return qsTr("Name")
        case "@type": return qsTr("Type")
        case "@updated_at": return qsTr("Edited")
        case "@created_at": return qsTr("Created")
        default: return p
        }
    }

    function load() {
        if (!ws || currentSetId.length === 0) {
            info = ({}); items = []; error = ""
            return
        }
        info = ws.setInfo(currentSetId)
        if (!info.id) {
            currentSetId = ""
            return
        }
        query = info.query || ({ version: 1 })
        viewKind = (info.activeView && info.activeView.kind) ? info.activeView.kind : "gallery"
        refresh()
    }
    function refresh() {
        if (!ws || currentSetId.length === 0)
            return
        const r = ws.runSet(currentSetId)
        error = r.error ? String(r.error) : ""
        items = r.items || []
    }
    function commitQuery(q) {
        q.version = 1
        query = q
        ws.setSetQuery(currentSetId, q)
        refresh()
    }
    function cloneQuery() { return JSON.parse(JSON.stringify(query)) }

    onCurrentSetIdChanged: load()
    onDataRevChanged: if (visible) refresh()
    onVisibleChanged: if (visible) {
        if (currentSetId.length === 0 && sets.length > 0)
            currentSetId = sets[0].id
        else
            refresh()
    }
    Component.onCompleted: if (sets.length > 0) currentSetId = sets[0].id

    RowLayout {
        anchors.fill: parent
        anchors.margins: Theme.pagePad
        spacing: 22

        // ── saved sets
        ColumnLayout {
            Layout.preferredWidth: Theme.compact ? 160 : 210
            Layout.maximumWidth: Theme.compact ? 160 : 210
            Layout.fillWidth: false
            Layout.fillHeight: true
            spacing: 6

            Text {
                text: qsTr("SETS")
                color: Theme.muted
                font.family: Theme.mono
                font.pixelSize: 10
                font.letterSpacing: 1.4
                Layout.bottomMargin: 4
            }

            ListView {
                id: setList
                Layout.fillWidth: true
                Layout.fillHeight: true
                clip: true
                spacing: 2
                model: setsPage.sets
                delegate: Rectangle {
                    required property var modelData
                    width: setList.width
                    height: 34
                    radius: Theme.radiusSm
                    color: setsPage.currentSetId === modelData.id ? Theme.panel2 : (rowHover.hovered ? Theme.panel : "transparent")
                    RowLayout {
                        anchors.fill: parent
                        anchors.leftMargin: 10
                        anchors.rightMargin: 10
                        spacing: 8
                        TintedIcon {
                            name: "filter"
                            size: 14
                            color: setsPage.currentSetId === modelData.id ? Theme.ice : Theme.dim
                        }
                        Text {
                            Layout.fillWidth: true
                            text: modelData.title || qsTr("Untitled")
                            color: Theme.text
                            elide: Text.ElideRight
                            font.family: Theme.body
                            font.pixelSize: 14
                        }
                    }
                    HoverHandler { id: rowHover; cursorShape: Qt.PointingHandCursor }
                    TapHandler { onTapped: setsPage.currentSetId = modelData.id }
                }
            }

            Button {
                Layout.fillWidth: true
                text: qsTr("+ NEW SET")
                small: true
                onClicked: {
                    const id = ws.createSet(qsTr("New set"), { version: 1 })
                    setsPage.currentSetId = id
                    setsPage.editingQuery = true
                    titleField.forceActiveFocus()
                    titleField.selectAll()
                }
            }
        }

        // ── the active set
        ColumnLayout {
            Layout.fillWidth: true
            Layout.fillHeight: true
            spacing: 12
            visible: setsPage.currentSetId.length > 0

            RowLayout {
                spacing: 10
                Rectangle {
                    implicitWidth: setTag.implicitWidth + 16
                    implicitHeight: 22
                    radius: 11
                    color: Qt.rgba(0.86, 0.93, 0.31, 0.16)
                    border.width: 1
                    border.color: Qt.rgba(0.86, 0.93, 0.31, 0.45)
                    Text {
                        id: setTag
                        anchors.centerIn: parent
                        text: "SET"
                        color: Theme.tag
                        font.family: Theme.mono
                        font.pixelSize: 10
                        font.letterSpacing: 1.2
                    }
                }
                Text {
                    Layout.fillWidth: true
                    text: setsPage.summaryText() + "  ·  " + (setsPage.items.length === 1 ? qsTr("1 object") : qsTr("%1 objects").arg(setsPage.items.length))
                    color: Theme.dim
                    elide: Text.ElideRight
                    font.family: Theme.mono
                    font.pixelSize: 11
                }
                Button {
                    text: setsPage.editingQuery ? qsTr("DONE") : qsTr("EDIT QUERY")
                    small: true
                    primary: setsPage.editingQuery
                    onClicked: setsPage.editingQuery = !setsPage.editingQuery
                }
            }

            TextInput {
                id: titleField
                Layout.fillWidth: true
                text: setsPage.info.title || ""
                color: Theme.text
                font.family: Theme.display
                font.pixelSize: 28
                font.weight: Font.Medium
                selectByMouse: true
                clip: true
                onEditingFinished: if (setsPage.currentSetId.length > 0) ws.renameObject(setsPage.currentSetId, text)
            }

            // ── query editor
            Rectangle {
                Layout.fillWidth: true
                visible: setsPage.editingQuery
                implicitHeight: queryCol.implicitHeight + 24
                radius: Theme.radius
                color: Theme.panel
                border.width: 1
                border.color: Theme.line

                ColumnLayout {
                    id: queryCol
                    anchors.fill: parent
                    anchors.margins: 12
                    spacing: 10

                    Text { text: qsTr("TYPES"); color: Theme.muted; font.family: Theme.mono; font.pixelSize: 10; font.letterSpacing: 1.2 }
                    Flow {
                        Layout.fillWidth: true
                        spacing: 6
                        Repeater {
                            model: setsPage.allTypes
                            delegate: Chip {
                                required property var modelData
                                text: modelData.name
                                active: (setsPage.query.types || []).indexOf(modelData.id) >= 0
                                onClicked: {
                                    const q = setsPage.cloneQuery()
                                    const t = q.types || []
                                    const i = t.indexOf(modelData.id)
                                    if (i >= 0) t.splice(i, 1); else t.push(modelData.id)
                                    q.types = t
                                    setsPage.commitQuery(q)
                                }
                            }
                        }
                    }

                    RowLayout {
                        spacing: 8
                        Text { text: qsTr("FILTERS"); color: Theme.muted; font.family: Theme.mono; font.pixelSize: 10; font.letterSpacing: 1.2 }
                        Chip {
                            visible: (setsPage.query.filters || []).length > 1
                            text: (setsPage.query.match || "all") === "any" ? qsTr("match any") : qsTr("match all")
                            onClicked: {
                                const q = setsPage.cloneQuery()
                                q.match = (q.match || "all") === "any" ? "all" : "any"
                                setsPage.commitQuery(q)
                            }
                        }
                    }

                    Repeater {
                        model: setsPage.query.filters || []
                        delegate: RowLayout {
                            id: filterRow
                            required property var modelData
                            required property int index
                            Layout.fillWidth: true
                            spacing: 8

                            function update(field, val) {
                                const q = setsPage.cloneQuery()
                                const f = q.filters[index]
                                if (field === "prop") f.prop = val
                                if (field === "op") {
                                    f.op = val
                                    const v = setsPage.parseValue(val, setsPage.valueText(f.value))
                                    if (v === undefined) delete f.value; else f.value = v
                                }
                                if (field === "value") {
                                    const v = setsPage.parseValue(f.op, val)
                                    if (v === undefined) delete f.value; else f.value = v
                                }
                                setsPage.commitQuery(q)
                            }

                            QueryField {
                                Layout.preferredWidth: 150
                                text: filterRow.modelData.prop || ""
                                placeholder: qsTr("property or @title")
                                onCommitted: (t) => filterRow.update("prop", t)
                            }
                            Chip {
                                text: setsPage.opInfo(filterRow.modelData.op).label
                                active: opMenu.opened
                                onClicked: opMenu.open()
                                Popup {
                                    id: opMenu
                                    y: parent.height + 4
                                    width: 320
                                    padding: 10
                                    background: Rectangle { radius: Theme.radius; color: Theme.sheet; border.color: Theme.line2 }
                                    contentItem: Flow {
                                        spacing: 6
                                        Repeater {
                                            model: setsPage.ops
                                            delegate: Chip {
                                                required property var modelData
                                                text: modelData.label
                                                active: modelData.op === filterRow.modelData.op
                                                onClicked: { opMenu.close(); filterRow.update("op", modelData.op) }
                                            }
                                        }
                                    }
                                }
                            }
                            QueryField {
                                Layout.fillWidth: true
                                visible: setsPage.opInfo(filterRow.modelData.op).needs !== ""
                                text: setsPage.valueText(filterRow.modelData.value)
                                placeholder: {
                                    switch (setsPage.opInfo(filterRow.modelData.op).needs) {
                                    case "list": return qsTr("a, b, c")
                                    case "date": return qsTr("2026-11-12 or today-7")
                                    case "range": return qsTr("today-7, today")
                                    case "number": return "0"
                                    default: return qsTr("value")
                                    }
                                }
                                onCommitted: (t) => filterRow.update("value", t)
                            }
                            Item { Layout.fillWidth: true; visible: setsPage.opInfo(filterRow.modelData.op).needs === "" }
                            IconButton {
                                icon: "x"
                                onClicked: {
                                    const q = setsPage.cloneQuery()
                                    q.filters.splice(filterRow.index, 1)
                                    setsPage.commitQuery(q)
                                }
                            }
                        }
                    }

                    RowLayout {
                        spacing: 8
                        Button {
                            text: qsTr("+ FILTER")
                            small: true
                            onClicked: {
                                const q = setsPage.cloneQuery()
                                q.filters = (q.filters || []).concat([{ prop: "@title", op: "contains", value: "" }])
                                setsPage.commitQuery(q)
                            }
                        }
                        Item { Layout.fillWidth: true }
                        Text { text: qsTr("SORT"); color: Theme.muted; font.family: Theme.mono; font.pixelSize: 10; font.letterSpacing: 1.2 }
                        QueryField {
                            Layout.preferredWidth: 150
                            text: (setsPage.query.sort && setsPage.query.sort.length) ? setsPage.query.sort[0].prop : ""
                            placeholder: qsTr("@updated_at")
                            onCommitted: (t) => {
                                const q = setsPage.cloneQuery()
                                const dir = (q.sort && q.sort.length) ? (q.sort[0].dir || "asc") : "asc"
                                q.sort = t.trim().length ? [{ prop: t.trim(), dir: dir }] : []
                                setsPage.commitQuery(q)
                            }
                        }
                        Chip {
                            enabled: (setsPage.query.sort || []).length > 0
                            opacity: enabled ? 1 : 0.4
                            text: ((setsPage.query.sort || [])[0] || {}).dir === "desc" ? qsTr("desc ↓") : qsTr("asc ↑")
                            onClicked: {
                                const q = setsPage.cloneQuery()
                                if (!q.sort || !q.sort.length)
                                    return
                                q.sort[0].dir = q.sort[0].dir === "desc" ? "asc" : "desc"
                                setsPage.commitQuery(q)
                            }
                        }
                    }
                }
            }

            // ── view tabs
            RowLayout {
                spacing: 6
                Repeater {
                    model: [
                        { kind: "gallery", label: qsTr("Gallery") },
                        { kind: "table", label: qsTr("Table") },
                        { kind: "list", label: qsTr("List") }
                    ]
                    delegate: Chip {
                        required property var modelData
                        text: modelData.label
                        active: setsPage.viewKind === modelData.kind
                        onClicked: {
                            setsPage.viewKind = modelData.kind
                            ws.setSetActiveView(setsPage.currentSetId, modelData.kind)
                        }
                    }
                }
                Item { Layout.fillWidth: true }
                Button {
                    text: qsTr("DELETE SET")
                    small: true
                    onClicked: {
                        ws.deleteObject(setsPage.currentSetId)
                        setsPage.currentSetId = setsPage.sets.length > 0 ? setsPage.sets[0].id : ""
                    }
                }
            }

            // ── results
            Item {
                Layout.fillWidth: true
                Layout.fillHeight: true
                clip: true

                GridView {
                    id: galleryView
                    anchors.fill: parent
                    visible: setsPage.viewKind === "gallery"
                    readonly property int cols: Math.max(1, Math.floor((width + 12) / (240 + 12)))
                    cellWidth: Math.floor((width + 12) / cols)
                    cellHeight: 176 + 12
                    cacheBuffer: 360
                    boundsBehavior: Flickable.StopAtBounds
                    ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }
                    model: visible ? setsPage.items : []
                    delegate: Item {
                        required property var modelData
                        width: galleryView.cellWidth
                        height: galleryView.cellHeight
                        ObjectCard {
                            width: Math.max(120, galleryView.cellWidth - 12)
                            height: 176
                            summary: modelData
                            onOpenRequested: setsPage.openObjectRequested(modelData.id)
                        }
                    }
                }

                ListView {
                    id: listView
                    anchors.fill: parent
                    visible: setsPage.viewKind === "list"
                    cacheBuffer: 260
                    boundsBehavior: Flickable.StopAtBounds
                    ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }
                    model: visible ? setsPage.items : []
                    delegate: ObjectRow {
                        required property var modelData
                        width: listView.width
                        summary: modelData
                        onOpenRequested: setsPage.openObjectRequested(modelData.id)
                    }
                }

                ListView {
                    id: tableView
                    anchors.fill: parent
                    visible: setsPage.viewKind === "table"
                    clip: true
                    boundsBehavior: Flickable.StopAtBounds
                    ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }
                    readonly property real colW: Math.max(120, (width - 260) / Math.max(1, setsPage.columns.length - 1))
                    function widthOf(i) { return i === 0 ? 260 : colW }
                    headerPositioning: ListView.OverlayHeader
                    header: Rectangle {
                        z: 2
                        width: tableView.width
                        height: 34
                        color: Theme.sheet
                        Row {
                            anchors.fill: parent
                            Repeater {
                                model: setsPage.columns
                                delegate: Text {
                                    required property var modelData
                                    required property int index
                                    width: tableView.widthOf(index)
                                    height: 34
                                    leftPadding: 12
                                    verticalAlignment: Text.AlignVCenter
                                    text: setsPage.columnLabel(modelData).toUpperCase()
                                    color: Theme.muted
                                    elide: Text.ElideRight
                                    font.family: Theme.mono
                                    font.pixelSize: 10
                                    font.letterSpacing: 1.2
                                }
                            }
                        }
                        Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.line }
                    }
                    model: visible ? setsPage.items : []
                    delegate: Rectangle {
                        id: tableRow
                        required property var modelData
                        width: tableView.width
                        height: 38
                        color: rowHover2.hovered ? Theme.panel : "transparent"
                        Row {
                            anchors.fill: parent
                            Repeater {
                                model: setsPage.columns
                                delegate: Text {
                                    required property var modelData
                                    required property int index
                                    width: tableView.widthOf(index)
                                    height: 38
                                    leftPadding: 12
                                    rightPadding: 8
                                    verticalAlignment: Text.AlignVCenter
                                    text: (setsPage.dataRev >= 0 && ws) ? ws.fieldText(tableRow.modelData.id, modelData) : ""
                                    color: index === 0 ? Theme.text : Theme.dim
                                    elide: Text.ElideRight
                                    font.family: index === 0 ? Theme.body : Theme.mono
                                    font.pixelSize: index === 0 ? 14 : 12
                                }
                            }
                        }
                        Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.line; opacity: 0.5 }
                        HoverHandler { id: rowHover2; cursorShape: Qt.PointingHandCursor }
                        TapHandler { onTapped: setsPage.openObjectRequested(tableRow.modelData.id) }
                    }
                }

                Text {
                    visible: setsPage.items.length === 0
                    text: setsPage.error.length > 0 ? qsTr("query error: %1").arg(setsPage.error) : qsTr("no objects match this set")
                    color: setsPage.error.length > 0 ? Theme.danger : Theme.faint
                    font.family: Theme.mono
                    font.pixelSize: 11
                }
            }
        }

        // empty state: no set yet
        ColumnLayout {
            Layout.fillWidth: true
            Layout.fillHeight: true
            visible: setsPage.currentSetId.length === 0
            spacing: 10
            Item { Layout.fillHeight: true }
            Text {
                Layout.alignment: Qt.AlignHCenter
                text: qsTr("Sets are saved queries")
                color: Theme.text
                font.family: Theme.display
                font.pixelSize: 22
            }
            Text {
                Layout.alignment: Qt.AlignHCenter
                text: qsTr("Pick types, add filters and a sort; see the result as a gallery, a table or a list.")
                color: Theme.dim
                font.family: Theme.body
                font.pixelSize: 14
            }
            Item { Layout.fillHeight: true }
        }
    }
}
