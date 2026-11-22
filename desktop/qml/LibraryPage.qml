import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtCore
import "."

// Objects / Type browser (docs/design/mockup/WebLinks.dc.html):
// Header (type icon, title, N of M count, 4 view switchers: Gallery/List/Table/Kanban,
// Filter popover with "Save as view", Sort toggle, accent "+ New"), secondary segmented
// property-filter bar (Category / Status when viewing a type, or All types strip), and
// 4 real views (Gallery, List, Table, Kanban).
Item {
    id: library

    property var appWindow: null
    property string filterType: (appWindow && appWindow.libraryType !== undefined)
                                ? appWindow.libraryType
                                : ((typeof app !== "undefined" && app.libraryType !== undefined) ? app.libraryType : "all")
    property string searchQuery: ""
    property string viewMode: "gallery"   // "gallery" | "list" | "table" | "kanban"
    property string sortMode: "updated"   // "updated" | "title"
    property string quickPropOption: "all"
    property string quickMultiOption: "all"
    property bool filterOpen: false
    property bool filterUnreviewedOnly: false
    property string filterTag: ""
    // Dynamic composite filter rules (WebLinks.dc.html: AND / OR rules)
    // Each rule: { join: "and"|"or", key: string, op: "eq"|"neq"|"contains", value: string }
    property var filterRules: []

    signal openObjectRequested(string objectId)

    // Named views of a type (spec/query.md "Type view"): "" = every object of the type.
    property string currentViewId: ""
    readonly property bool typeScoped: filterType !== "all" && filterType !== "trash"
    readonly property var typeViewList: { dataRev; return ws && typeScoped ? ws.typeViews(filterType) : [] }
    readonly property int templateCount: { dataRev; return ws && typeScoped ? ws.templatesFor(filterType).length : 0 }
    // A new template of this type, opened to edit (product §4.3).
    function newTemplate() {
        if (!ws || !typeScoped) return
        const info = ws.typeInfo(filterType)
        const id = ws.createObject(filterType, qsTr("%1 template").arg(info && info.name ? info.name : Theme.typeName(filterType)))
        ws.setIsTemplate(id, true)
        openObjectRequested(id)
    }
    Settings {
        id: libPrefs
        category: "library"
        property string lastViews: "{}"   // type id -> view id, per device
        property string allViewModes: "{}" // type id -> view mode of the "All" tab
    }
    function prefMap(name) { try { return JSON.parse(libPrefs[name] || "{}") } catch (e) { return {} } }
    function setPref(name, key, value) { const m = prefMap(name); m[key] = value; libPrefs[name] = JSON.stringify(m) }
    function selectView(id) {
        currentViewId = id
        if (typeScoped) setPref("lastViews", filterType, id)
        if (id === "@templates") {
            viewMode = "list"
        } else if (id.length > 0) {
            const v = typeViewList.find(x => x.id === id)
            viewMode = v && v.kind ? v.kind : "gallery"
        } else {
            viewMode = prefMap("allViewModes")[filterType] || "gallery"
        }
        recompute()
    }
    function pickViewMode(kind) {
        viewMode = kind
        if (currentViewId === "@templates") return
        if (currentViewId.length > 0) ws.setSetActiveView(currentViewId, kind)
        else if (typeScoped) setPref("allViewModes", filterType, kind)
    }
    // The current quick filters as a query (spec/query.md).
    function currentQuery() {
        const q = { version: 1 }
        if (typeScoped) q.types = [filterType]
        const filters = []
        if (filterUnreviewedOnly) filters.push({ prop: "reviewed", op: "is_false" })
        if (primarySelectProp && quickPropOption !== "all")
            filters.push({ prop: primarySelectProp.key, op: "eq", value: quickPropOption })
        if (primaryMultiProp && quickMultiOption !== "all")
            filters.push({ prop: primaryMultiProp.key, op: "has_any", value: [quickMultiOption] })
        for (let r = 0; r < filterRules.length; r++) {
            const rl = filterRules[r]
            if (!rl.value) continue
            filters.push({ prop: rl.key, op: rl.op === "contains" ? "contains" : rl.op, value: rl.value })
        }
        if (filterTag.trim().length > 0) filters.push({ prop: "tags", op: "has_any", value: [filterTag.trim()] })
        if (filters.length > 0) q.filters = filters
        q.sort = [sortMode === "title" ? { prop: "@title", dir: "asc" } : { prop: "@updated_at", dir: "desc" }]
        return q
    }
    function clearQuickFilters() {
        quickPropOption = "all"; quickMultiOption = "all"; filterUnreviewedOnly = false
        filterTag = ""; filterRules = []; searchQuery = ""; searchField.text = ""
    }
    // Saves what is on screen as a new named view of the type and opens it.
    function saveCurrentAsView(name) {
        if (!ws || !typeScoped) return ""
        const n = name && name.trim().length > 0 ? name.trim()
                : qsTr("View %1").arg(typeViewList.length + 1)
        const id = ws.createTypeView(filterType, n, currentQuery(), viewMode)
        clearQuickFilters()
        Qt.callLater(() => selectView(id))
        if (appWindow) appWindow.showToast(qsTr("Saved view “%1”").arg(n))
        return id
    }

    function update() { }
    function openTypeManager() {
        if (appWindow) appWindow.navigate("types")
    }

    readonly property int dataRev: ws ? ws.listRevision : 0
    property var allTypeItems: []
    property var items: []
    readonly property real gridGap: 14

    // Primary select property for the currently filtered type (e.g. web -> category, book/task -> status)
    readonly property var typeProps: {
        dataRev
        if (!ws || filterType === "all" || filterType === "trash") return []
        return ws.typeProperties(filterType)
    }
    readonly property var primarySelectProp: {
        for (let i = 0; i < typeProps.length; i++) {
            const p = typeProps[i]
            if (p.kind === "select" && p.options && p.options.length > 0) return p
        }
        return null
    }
    // Secondary multi_select property for quick filtering (web -> topics, book -> genre, image -> colors)
    readonly property var primaryMultiProp: {
        for (let i = 0; i < typeProps.length; i++) {
            const p = typeProps[i]
            if (p.kind === "multi_select" && p.key !== "tags" && p.options && p.options.length > 0) return p
        }
        return null
    }
    readonly property var filterableProps: {
        const out = []
        for (let i = 0; i < typeProps.length; i++) {
            const p = typeProps[i]
            if (p.key === "asset_id" || p.key === "mime" || p.key === "size") continue
            out.push(p)
        }
        if (out.length === 0) {
            out.push({ key: "tags", name: qsTr("Tags"), kind: "multi_select", options: [] })
            out.push({ key: "reviewed", name: qsTr("Reviewed"), kind: "checkbox", options: [] })
        }
        return out
    }
    function addFilterRule() {
        const fp = filterableProps.length > 0 ? filterableProps[0] : { key: "tags", kind: "text", options: [] }
        const defVal = (fp.options && fp.options.length > 0) ? fp.options[0].id
                     : fp.kind === "checkbox" ? "false" : ""
        const next = filterRules.slice()
        next.push({ join: "and", key: fp.key, op: fp.kind === "multi_select" ? "contains" : "eq", value: defVal })
        filterRules = next
    }
    function updateRule(idx, patch) {
        const next = filterRules.slice()
        next[idx] = Object.assign({}, next[idx], patch)
        filterRules = next
    }
    function removeRule(idx) {
        const next = filterRules.slice()
        next.splice(idx, 1)
        filterRules = next
    }
    function propDefFor(key) {
        for (let i = 0; i < filterableProps.length; i++)
            if (filterableProps[i].key === key) return filterableProps[i]
        return { key: key, name: key, kind: "text", options: [] }
    }
    function evalRule(it, rule) {
        const want = String(rule.value || "").trim().toLowerCase()
        if (want.length === 0) return true
        if (rule.key === "tags") {
            const has = (it.tags || []).some(t => String(t).toLowerCase().indexOf(want) >= 0)
            return rule.op === "neq" ? !has : has
        }
        const raw = String(ws.fieldText(it.id, rule.key) || "").toLowerCase()
        const pd = propDefFor(rule.key)
        let wantLabel = want
        if (pd.options) {
            const opt = pd.options.find(o => String(o.id).toLowerCase() === want)
            if (opt) wantLabel = String(opt.label || opt.id).toLowerCase()
        }
        if (rule.op === "eq")
            return raw === want || raw === wantLabel || raw.split(/,\s*/).indexOf(want) >= 0 || raw.split(/,\s*/).indexOf(wantLabel) >= 0
        if (rule.op === "neq")
            return raw !== want && raw !== wantLabel && raw.split(/,\s*/).indexOf(want) < 0
        return raw.indexOf(want) >= 0 || raw.indexOf(wantLabel) >= 0
    }
    readonly property var currentTypeInfo: {
        dataRev
        if (!ws || filterType === "all" || filterType === "trash") return null
        return ws.typeInfo(filterType)
    }

    function recompute() {
        if (!ws) { allTypeItems = []; items = []; return }
        const q = searchQuery.trim().toLowerCase()
        let base = []
        if (filterType === "trash") {
            base = ws.trashedObjects()
        } else if (filterType === "all") {
            const types = ws.allTypes().map(t => t.id)
            for (let i = 0; i < types.length; i++) {
                const list = ws.objectsByType(types[i], 0)
                for (let j = 0; j < list.length; j++) base.push(list[j])
            }
        } else if (currentViewId === "@templates") {
            base = ws.templatesFor(filterType).map(t => ws.objectSummary(t.id))
        } else if (currentViewId.length > 0) {
            const r = ws.runSet(currentViewId)
            base = r.items || ws.objectsByType(filterType, 0)
        } else {
            base = ws.objectsByType(filterType, 0)
        }
        allTypeItems = base

        let out = base.slice()
        if (q.length > 0) {
            out = out.filter(it => String(it.title || "").toLowerCase().indexOf(q) >= 0
                                || String(it.snippet || "").toLowerCase().indexOf(q) >= 0)
        }
        if (primarySelectProp && quickPropOption !== "all") {
            const key = primarySelectProp.key
            const opt = primarySelectProp.options.find(o => o.id === quickPropOption)
            const wantLabel = opt ? String(opt.label || opt.id).toLowerCase() : quickPropOption.toLowerCase()
            out = out.filter(it => {
                const raw = String(ws.fieldText(it.id, key) || "").toLowerCase()
                return raw === quickPropOption.toLowerCase() || raw === wantLabel
            })
        }
        if (primaryMultiProp && quickMultiOption !== "all") {
            const key = primaryMultiProp.key
            const opt = primaryMultiProp.options.find(o => o.id === quickMultiOption)
            const wantId = quickMultiOption.toLowerCase()
            const wantLabel = opt ? String(opt.label || opt.id).toLowerCase() : wantId
            out = out.filter(it => {
                const raw = String(ws.fieldText(it.id, key) || "").toLowerCase()
                const parts = raw.split(/,\s*/)
                return parts.indexOf(wantId) >= 0 || parts.indexOf(wantLabel) >= 0
            })
        }
        if (filterUnreviewedOnly) {
            out = out.filter(it => String(ws.fieldText(it.id, "reviewed") || "").toLowerCase() === "false")
        }
        if (filterTag.trim().length > 0) {
            const want = filterTag.trim().toLowerCase()
            out = out.filter(it => (it.tags || []).some(t => String(t).toLowerCase().indexOf(want) >= 0))
        }
        if (filterRules.length > 0) {
            out = out.filter(it => {
                let acc = evalRule(it, filterRules[0])
                for (let r = 1; r < filterRules.length; r++) {
                    const ok = evalRule(it, filterRules[r])
                    acc = (filterRules[r].join === "or") ? (acc || ok) : (acc && ok)
                }
                return acc
            })
        }
        if (currentViewId.length === 0) { // a saved view keeps its query's order
            if (sortMode === "title")
                out.sort((a, b) => String(a.title || "").localeCompare(String(b.title || "")))
            else
                out.sort((a, b) => String(b.updatedAt || "").localeCompare(String(a.updatedAt || "")))
        }
        items = out
    }

    onFilterTypeChanged: {
        quickPropOption = "all"; quickMultiOption = "all"; filterUnreviewedOnly = false; filterRules = []
        // reopen the view this type had last time on this device (if it still exists)
        const last = typeScoped ? (prefMap("lastViews")[filterType] || "") : ""
        const ok = last.length > 0 && ws && ws.typeViews(filterType).some(v => v.id === last)
        selectView(ok ? last : "")
    }
    onSearchQueryChanged: if (visible) recompute()
    onSortModeChanged: if (visible) recompute()
    onQuickPropOptionChanged: if (visible) recompute()
    onQuickMultiOptionChanged: if (visible) recompute()
    onFilterUnreviewedOnlyChanged: if (visible) recompute()
    onFilterTagChanged: if (visible) recompute()
    onFilterRulesChanged: if (visible) recompute()
    onDataRevChanged: if (visible) recompute()
    onVisibleChanged: if (visible) recompute()
    Component.onCompleted: recompute()

    Connections {
        target: library.appWindow
        function onLibraryTypeChanged() {
            if (library.appWindow && library.appWindow.libraryType !== undefined)
                library.filterType = library.appWindow.libraryType
        }
    }

    // Kanban columns computed from primarySelectProp (or by typeId when viewing all)
    readonly property var kanbanColumns: {
        if (viewMode !== "kanban" || !ws) return []
        if (primarySelectProp) {
            const key = primarySelectProp.key
            const cols = primarySelectProp.options.slice(0, 6).map(o => ({
                id: o.id, label: o.label || o.id, color: Theme.paletteColor(o.color || "gray"), cards: []
            }))
            cols.push({ id: "_none", label: qsTr("No value"), color: Theme.faint, cards: [] })
            for (let i = 0; i < items.length; i++) {
                const raw = String(ws.fieldText(items[i].id, key) || "").toLowerCase()
                let placed = false
                for (let c = 0; c < cols.length - 1; c++) {
                    if (raw === cols[c].id.toLowerCase() || raw === cols[c].label.toLowerCase()) {
                        cols[c].cards.push(items[i]); placed = true; break
                    }
                }
                if (!placed) cols[cols.length - 1].cards.push(items[i])
            }
            return cols
        }
        const byType = {}
        for (let i = 0; i < items.length; i++) {
            const tid = items[i].typeId || "note"
            if (!byType[tid]) byType[tid] = []
            byType[tid].push(items[i])
        }
        return Object.keys(byType).map(tid => {
            const info = ws.typeInfo(tid)
            return { id: tid, label: (info && info.name) ? info.name : Theme.typeName(tid), color: Theme.typeColor(tid), cards: byType[tid] }
        })
    }

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        // ---------------- Top bar (72px — WebLinks.dc.html) ----------------
        Item {
            Layout.fillWidth: true
            Layout.preferredHeight: 68

            Rectangle { anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom; height: 1; color: Theme.line }

            RowLayout {
                anchors.fill: parent
                anchors.leftMargin: 32
                anchors.rightMargin: 32
                spacing: 14

                TintedIcon {
                    name: library.filterType === "all" ? "layout-grid"
                        : library.filterType === "trash" ? "trash"
                        : Theme.typeIcon(library.filterType)
                    size: 20
                    color: library.filterType === "all" ? Theme.text
                         : library.filterType === "trash" ? Theme.danger
                         : Theme.typeColor(library.filterType)
                }

                Text {
                    text: library.filterType === "all" ? qsTr("Objects")
                        : library.filterType === "trash" ? qsTr("Trash")
                        : (library.currentTypeInfo && library.currentTypeInfo.name ? library.currentTypeInfo.name : Theme.typeName(library.filterType))
                    color: Theme.text
                    font.family: Theme.display
                    font.pixelSize: 22
                    font.letterSpacing: -0.2
                }

                Text {
                    text: Theme.caps(qsTr("%1 OF %2").arg(library.items.length).arg(library.allTypeItems.length))
                    color: Theme.label
                    font.family: Theme.mono
                    font.pixelSize: 11
                }

                Item { Layout.fillWidth: true }

                // Compact search input
                Rectangle {
                    Layout.preferredWidth: 200
                    Layout.preferredHeight: 36
                    color: "transparent"
                    border.width: 1
                    border.color: searchField.activeFocus ? Theme.text : Theme.line2
                    RowLayout {
                        anchors.fill: parent; anchors.leftMargin: 10; anchors.rightMargin: 10; spacing: 8
                        TintedIcon { name: "search"; size: 14; color: Theme.label }
                        TextInput {
                            id: searchField
                            Layout.fillWidth: true
                            verticalAlignment: TextInput.AlignVCenter
                            color: Theme.text; font.family: Theme.body; font.pixelSize: 13; clip: true
                            onTextEdited: library.searchQuery = text
                            Text { anchors.verticalCenter: parent.verticalCenter; visible: searchField.length === 0; text: qsTr("Filter…"); color: Theme.faint; font: searchField.font }
                        }
                    }
                }

                // 4 view mode icons (Galería | Lista | Tabla | Kanban)
                Row {
                    spacing: 2
                    Repeater {
                        model: [
                            { key: "gallery", icon: "layout-grid", label: qsTr("Gallery") },
                            { key: "list", icon: "list", label: qsTr("List") },
                            { key: "table", icon: "table", label: qsTr("Table") },
                            { key: "kanban", icon: "layout-kanban", label: qsTr("Kanban") }
                        ]
                        delegate: IconButton {
                            required property var modelData
                            icon: modelData.icon
                            active: library.viewMode === modelData.key
                            onClicked: library.pickViewMode(modelData.key)
                            ToolTip.visible: hovered; ToolTip.text: modelData.label; ToolTip.delay: 300
                        }
                    }
                }

                Rectangle { Layout.preferredWidth: 1; Layout.preferredHeight: 22; color: Theme.line2 }

                IconButton {
                    icon: "filter"
                    active: library.filterOpen || library.filterUnreviewedOnly || library.filterTag.length > 0
                    onClicked: library.filterOpen = !library.filterOpen
                    ToolTip.visible: hovered; ToolTip.text: qsTr("Filters"); ToolTip.delay: 300
                }
                IconButton {
                    icon: "arrows-sort"
                    active: library.sortMode === "title"
                    onClicked: library.sortMode = library.sortMode === "updated" ? "title" : "updated"
                    ToolTip.visible: hovered; ToolTip.text: library.sortMode === "updated" ? qsTr("Sorted by recent (click for A–Z)") : qsTr("Sorted A–Z (click for recent)"); ToolTip.delay: 300
                }

                Button {
                    primary: true
                    text: qsTr("+ New")
                    onClicked: {
                        if (library.appWindow) {
                            if (library.filterType !== "all" && library.filterType !== "trash")
                                library.appWindow.openCaptureFor(library.filterType)
                            else
                                library.appWindow.captureOpen = true
                        }
                    }
                }
            }
        }

        // ---------------- Named views of the type (Capacities: "Overview · All") ----------------
        Item {
            visible: library.typeScoped
            Layout.fillWidth: true
            Layout.preferredHeight: visible ? 44 : 0

            Rectangle { anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom; height: 1; color: Theme.lineSubtle }

            Flickable {
                anchors.fill: parent
                anchors.leftMargin: 28
                anchors.rightMargin: 28
                contentWidth: viewTabs.implicitWidth
                boundsBehavior: Flickable.StopAtBounds
                clip: true
                Row {
                    id: viewTabs
                    height: parent.height
                    spacing: 4
                    Repeater {
                        model: [{ id: "", title: qsTr("All"), fixed: true }].concat(library.typeViewList)
                        delegate: ViewTab {
                            required property var modelData
                            height: viewTabs.height
                            viewId: modelData.id
                            title: modelData.title
                            fixed: !!modelData.fixed
                            current: library.currentViewId === modelData.id
                            onPicked: library.selectView(modelData.id)
                            onRenamed: (name) => ws.renameObject(modelData.id, name)
                            onRemoved: {
                                if (library.currentViewId === modelData.id) library.selectView("")
                                ws.deleteObject(modelData.id)
                                if (library.appWindow) library.appWindow.showToast(qsTr("View moved to trash"))
                            }
                        }
                    }
                    ViewTab {
                        visible: library.templateCount > 0
                        height: viewTabs.height
                        title: qsTr("Templates · %1").arg(library.templateCount)
                        fixed: true
                        current: library.currentViewId === "@templates"
                        onPicked: library.selectView("@templates")
                    }
                    ViewTab {
                        height: viewTabs.height
                        title: "+ " + qsTr("New view")
                        fixed: true
                        muted: true
                        onPicked: library.saveCurrentAsView("")
                    }
                    ViewTab {
                        height: viewTabs.height
                        title: "+ " + qsTr("New template")
                        fixed: true
                        muted: true
                        onPicked: library.newTemplate()
                    }
                }
            }
        }

        // ---------------- Secondary segmented filter bar (WebLinks.dc.html) ----------------
        Item {
            Layout.fillWidth: true
            Layout.preferredHeight: 50
            clip: false

            Rectangle { anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom; height: 1; color: Theme.line }

            RowLayout {
                anchors.fill: parent
                anchors.leftMargin: 32
                anchors.rightMargin: 32
                spacing: 12

                Flickable {
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    clip: true
                    contentWidth: segRow.implicitWidth
                    contentHeight: height
                    boundsBehavior: Flickable.StopAtBounds

                    Row {
                        id: segRow
                        anchors.verticalCenter: parent.verticalCenter
                        spacing: 4

                        // If viewing a type with a primary select property -> show ALL options (scrollable, no 10-item cap)
                        Text {
                            visible: !!library.primarySelectProp
                            anchors.verticalCenter: parent.verticalCenter
                            rightPadding: 8
                            text: Theme.caps(library.primarySelectProp ? library.primarySelectProp.name : "")
                            color: Theme.label; font.family: Theme.mono; font.pixelSize: 11
                        }
                        Repeater {
                            model: library.primarySelectProp
                                   ? [{ id: "all", label: qsTr("All"), color: "" }].concat(library.primarySelectProp.options)
                                   : []
                            delegate: Rectangle {
                                required property var modelData
                                readonly property bool on: library.quickPropOption === modelData.id
                                implicitWidth: segTxt.implicitWidth + 24
                                implicitHeight: 32
                                color: on ? Theme.accent : segHov.hovered ? Theme.panel2 : "transparent"
                                Text {
                                    id: segTxt; anchors.centerIn: parent
                                    text: modelData.label || modelData.id
                                    color: on ? Theme.accentInk : segHov.hovered ? Theme.text : Theme.muted
                                    font.family: Theme.body; font.pixelSize: 13
                                }
                                HoverHandler { id: segHov; cursorShape: Qt.PointingHandCursor }
                                TapHandler { onTapped: library.quickPropOption = modelData.id }
                            }
                        }

                        // Otherwise -> show All / Types / Trash segments
                        Repeater {
                            model: !library.primarySelectProp
                                   ? [{ id: "all", name: qsTr("All") }]
                                     .concat((library.dataRev >= 0 && ws) ? ws.allTypes() : [])
                                     .concat([{ id: "trash", name: qsTr("Trash") }])
                                   : []
                            delegate: Chip {
                                required property var modelData
                                text: modelData.name
                                typeId: modelData.id !== "all" && modelData.id !== "trash" ? modelData.id : ""
                                icon: modelData.id === "all" ? "layout-grid" : modelData.id === "trash" ? "trash" : ""
                                active: library.filterType === modelData.id
                                onClicked: library.filterType = modelData.id
                            }
                        }
                    }
                }

                // Pinned Topic / Genre / Colors quick dropdown on the right (always visible even with 30 categories)
                Rectangle {
                    id: multiQuickBtn
                    visible: !!library.primaryMultiProp
                    Layout.alignment: Qt.AlignVCenter
                    implicitWidth: mqRow.implicitWidth + 20; implicitHeight: 32
                    color: library.quickMultiOption !== "all" ? Theme.accent : mqHov.hovered ? Theme.panel2 : "transparent"
                    border.width: 1; border.color: library.quickMultiOption !== "all" ? Theme.accent : Theme.line2
                    Row {
                        id: mqRow; anchors.centerIn: parent; spacing: 6
                        Text {
                            readonly property var curOpt: library.primaryMultiProp && library.quickMultiOption !== "all"
                                ? library.primaryMultiProp.options.find(o => o.id === library.quickMultiOption) : null
                            text: curOpt
                                ? ((library.primaryMultiProp.name || "") + ": " + (curOpt.label || curOpt.id))
                                : ((library.primaryMultiProp ? library.primaryMultiProp.name : qsTr("Topic")) + " ▾")
                            color: library.quickMultiOption !== "all" ? Theme.accentInk : Theme.dim
                            font.family: Theme.body; font.pixelSize: 13
                        }
                    }
                    HoverHandler { id: mqHov; cursorShape: Qt.PointingHandCursor }
                    TapHandler { onTapped: multiQuickPopup.open() }

                    Popup {
                        id: multiQuickPopup
                        x: parent.width - width
                        y: parent.height + 4
                        width: 240
                        height: Math.min(340, mqList.contentHeight + 12)
                        padding: 4
                        modal: false; closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
                        background: Rectangle { color: Theme.sheet; border.width: 1; border.color: Theme.line2 }
                        ListView {
                            id: mqList
                            anchors.fill: parent; clip: true
                            model: library.primaryMultiProp
                                   ? [{ id: "all", label: qsTr("All"), color: "" }].concat(library.primaryMultiProp.options)
                                   : []
                            delegate: Rectangle {
                                required property var modelData
                                readonly property bool sel: library.quickMultiOption === modelData.id
                                width: mqList.width; height: 32
                                color: sel ? Theme.accent : mqOptH.hovered ? Theme.panel2 : "transparent"
                                Row {
                                    anchors.verticalCenter: parent.verticalCenter; anchors.left: parent.left; anchors.leftMargin: 10; spacing: 8
                                    Rectangle {
                                        visible: !!modelData.color
                                        anchors.verticalCenter: parent.verticalCenter; width: 7; height: 7
                                        color: Theme.paletteColor(modelData.color || "gray")
                                        border.width: sel ? 1 : 0; border.color: "#000000"
                                    }
                                    Text {
                                        anchors.verticalCenter: parent.verticalCenter
                                        text: modelData.label || modelData.id
                                        color: sel ? Theme.accentInk : Theme.bodyText
                                        font.family: Theme.body; font.pixelSize: 13
                                    }
                                }
                                HoverHandler { id: mqOptH; cursorShape: Qt.PointingHandCursor }
                                TapHandler { onTapped: { library.quickMultiOption = modelData.id; multiQuickPopup.close() } }
                            }
                        }
                    }
                }
            }
        }

        // ---------------- Floating Filter Bar (WebLinks.dc.html) ----------------
        Rectangle {
            visible: library.filterOpen
            Layout.fillWidth: true
            implicitHeight: filterCol.implicitHeight + 28
            color: Theme.sheet

            Rectangle { anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom; height: 1; color: Theme.line2 }

            ColumnLayout {
                id: filterCol
                anchors.left: parent.left; anchors.right: parent.right; anchors.top: parent.top
                anchors.margins: 16; anchors.leftMargin: 32; anchors.rightMargin: 32
                spacing: 10

                Text {
                    text: Theme.caps(qsTr("SHOW %1 WHERE").arg(library.currentTypeInfo ? library.currentTypeInfo.name : qsTr("OBJECTS")))
                    color: Theme.label; font.family: Theme.mono; font.pixelSize: 11
                }

                // Dynamic AND / OR filter rules (WebLinks.dc.html)
                Repeater {
                    model: library.filterRules
                    delegate: RowLayout {
                        id: ruleRow
                        required property var modelData
                        required property int index
                        readonly property var pd: library.propDefFor(modelData.key)
                        Layout.fillWidth: true
                        spacing: 8

                        // AND / OR toggle for rules after the first
                        Rectangle {
                            Layout.preferredWidth: 42; Layout.preferredHeight: 30
                            color: "transparent"
                            border.width: ruleRow.index > 0 ? 1 : 0; border.color: Theme.line2
                            Text {
                                anchors.centerIn: parent
                                text: ruleRow.index === 0 ? "" : Theme.caps(ruleRow.modelData.join === "or" ? qsTr("OR") : qsTr("AND"))
                                color: Theme.dim; font.family: Theme.mono; font.pixelSize: 10
                            }
                            HoverHandler { cursorShape: ruleRow.index > 0 ? Qt.PointingHandCursor : Qt.ArrowCursor }
                            TapHandler {
                                enabled: ruleRow.index > 0
                                onTapped: library.updateRule(ruleRow.index, { join: ruleRow.modelData.join === "or" ? "and" : "or" })
                            }
                        }

                        // Property selector (cycles through type properties)
                        Rectangle {
                            implicitWidth: pFldTxt.implicitWidth + 24; Layout.preferredHeight: 30
                            color: pFldH.hovered ? Theme.panel2 : "transparent"
                            border.width: 1; border.color: Theme.line2
                            Text { id: pFldTxt; anchors.centerIn: parent; text: (ruleRow.pd.name || ruleRow.modelData.key) + " ▾"; color: Theme.bodyText; font.family: Theme.body; font.pixelSize: 13 }
                            HoverHandler { id: pFldH; cursorShape: Qt.PointingHandCursor }
                            TapHandler {
                                onTapped: {
                                    const list = library.filterableProps
                                    let idx = list.findIndex(x => x.key === ruleRow.modelData.key)
                                    const next = list[(idx + 1) % list.length]
                                    const defV = (next.options && next.options.length > 0) ? next.options[0].id
                                               : next.kind === "checkbox" ? "false" : ""
                                    library.updateRule(ruleRow.index, { key: next.key, op: next.kind === "multi_select" ? "contains" : "eq", value: defV })
                                }
                            }
                        }

                        // Operator selector
                        Rectangle {
                            implicitWidth: opTxt.implicitWidth + 20; Layout.preferredHeight: 30
                            color: opH.hovered ? Theme.panel2 : "transparent"
                            border.width: 1; border.color: Theme.line2
                            Text {
                                id: opTxt; anchors.centerIn: parent
                                text: ruleRow.modelData.op === "neq" ? qsTr("is not")
                                    : ruleRow.modelData.op === "contains" ? qsTr("contains") : qsTr("is")
                                color: Theme.muted; font.family: Theme.body; font.pixelSize: 13
                            }
                            HoverHandler { id: opH; cursorShape: Qt.PointingHandCursor }
                            TapHandler {
                                onTapped: {
                                    const ops = ["eq", "neq", "contains"]
                                    const n = ops[(ops.indexOf(ruleRow.modelData.op) + 1) % ops.length]
                                    library.updateRule(ruleRow.index, { op: n })
                                }
                            }
                        }

                        // Value selector: options dropdown if select/multi_select, Yes/No for checkbox, else text input
                        Rectangle {
                            visible: (ruleRow.pd.options && ruleRow.pd.options.length > 0) || ruleRow.pd.kind === "checkbox"
                            implicitWidth: valOptTxt.implicitWidth + 24; Layout.preferredHeight: 30
                            color: valOptH.hovered ? Theme.panel2 : "transparent"
                            border.width: 1; border.color: Theme.line2
                            Text {
                                id: valOptTxt; anchors.centerIn: parent
                                text: {
                                    if (ruleRow.pd.kind === "checkbox")
                                        return ruleRow.modelData.value === "true" ? qsTr("Yes") : qsTr("No")
                                    const o = (ruleRow.pd.options || []).find(x => x.id === ruleRow.modelData.value)
                                    return (o ? (o.label || o.id) : (ruleRow.modelData.value || qsTr("Choose…"))) + " ▾"
                                }
                                color: Theme.bodyText; font.family: Theme.body; font.pixelSize: 13
                            }
                            HoverHandler { id: valOptH; cursorShape: Qt.PointingHandCursor }
                            TapHandler {
                                onTapped: {
                                    if (ruleRow.pd.kind === "checkbox") {
                                        library.updateRule(ruleRow.index, { value: ruleRow.modelData.value === "true" ? "false" : "true" })
                                    } else {
                                        ruleOptPop.open()
                                    }
                                }
                            }
                            Popup {
                                id: ruleOptPop
                                y: parent.height + 4; width: 220; height: Math.min(280, ruleOptLv.contentHeight + 8)
                                padding: 4; modal: false; closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
                                background: Rectangle { color: Theme.sheet; border.width: 1; border.color: Theme.line2 }
                                ListView {
                                    id: ruleOptLv; anchors.fill: parent; clip: true
                                    model: ruleRow.pd.options || []
                                    delegate: Rectangle {
                                        required property var modelData
                                        readonly property bool on: ruleRow.modelData.value === modelData.id
                                        width: ruleOptLv.width; height: 30
                                        color: on ? Theme.accent : roH.hovered ? Theme.panel2 : "transparent"
                                        Row {
                                            anchors.verticalCenter: parent.verticalCenter; anchors.left: parent.left; anchors.leftMargin: 8; spacing: 7
                                            Rectangle { anchors.verticalCenter: parent.verticalCenter; width: 7; height: 7; color: Theme.paletteColor(modelData.color || "gray") }
                                            Text { anchors.verticalCenter: parent.verticalCenter; text: modelData.label || modelData.id; color: on ? Theme.accentInk : Theme.bodyText; font.family: Theme.body; font.pixelSize: 12 }
                                        }
                                        HoverHandler { id: roH; cursorShape: Qt.PointingHandCursor }
                                        TapHandler { onTapped: { library.updateRule(ruleRow.index, { value: modelData.id }); ruleOptPop.close() } }
                                    }
                                }
                            }
                        }
                        Rectangle {
                            visible: !(ruleRow.pd.options && ruleRow.pd.options.length > 0) && ruleRow.pd.kind !== "checkbox"
                            Layout.preferredWidth: 180; Layout.preferredHeight: 30
                            color: "transparent"; border.width: 1; border.color: rValIn.activeFocus ? Theme.text : Theme.line2
                            TextInput {
                                id: rValIn; anchors.fill: parent; anchors.leftMargin: 8; anchors.rightMargin: 8
                                verticalAlignment: TextInput.AlignVCenter
                                color: Theme.text; font.family: Theme.body; font.pixelSize: 13
                                text: ruleRow.modelData.value || ""
                                onTextEdited: library.updateRule(ruleRow.index, { value: text })
                                Text { anchors.verticalCenter: parent.verticalCenter; visible: rValIn.length === 0; text: qsTr("Value…"); color: Theme.faint; font: rValIn.font }
                            }
                        }

                        IconButton {
                            implicitWidth: 26; implicitHeight: 26; icon: "x"
                            onClicked: library.removeRule(ruleRow.index)
                        }
                        Item { Layout.fillWidth: true }
                    }
                }

                RowLayout {
                    spacing: 8
                    Button {
                        small: true; text: qsTr("+ Filter")
                        onClicked: library.addFilterRule()
                    }
                    Chip {
                        text: qsTr("Unreviewed only")
                        active: library.filterUnreviewedOnly
                        onClicked: library.filterUnreviewedOnly = !library.filterUnreviewedOnly
                    }
                    Rectangle {
                        Layout.preferredWidth: 180; Layout.preferredHeight: 28
                        color: "transparent"; border.width: 1; border.color: tagFInput.activeFocus ? Theme.text : Theme.line2
                        TextInput {
                            id: tagFInput
                            anchors.fill: parent; anchors.leftMargin: 8; anchors.rightMargin: 8
                            verticalAlignment: TextInput.AlignVCenter
                            color: Theme.text; font.family: Theme.body; font.pixelSize: 13
                            text: library.filterTag
                            onTextEdited: library.filterTag = text
                            Text { anchors.verticalCenter: parent.verticalCenter; visible: tagFInput.length === 0; text: qsTr("Tag contains…"); color: Theme.faint; font: tagFInput.font }
                        }
                    }
                    Item { Layout.fillWidth: true }
                    Button {
                        small: true; text: qsTr("Save as view")
                        onClicked: {
                            if (!ws) return
                            if (library.typeScoped) {
                                library.saveCurrentAsView("")
                                library.filterOpen = false
                                return
                            }
                            const label = qsTr("Filtered") + " · " + qsTr("View")
                            ws.createSet(label, library.currentQuery())
                            if (library.appWindow) library.appWindow.showToast(qsTr("Saved view “%1”").arg(label))
                        }
                    }
                    Button {
                        small: true; text: qsTr("Clear")
                        onClicked: library.clearQuickFilters()
                    }
                }
            }
        }

        // ---------------- Main Content Viewport ----------------
        Item {
            Layout.fillWidth: true
            Layout.fillHeight: true
            Layout.margins: library.viewMode === "table" ? 24 : 24
            Layout.leftMargin: 32
            Layout.rightMargin: 32
            clip: true

            // 1. GALLERY VIEW (4-column sharp cards)
            GridView {
                id: gridView
                anchors.fill: parent
                visible: library.viewMode === "gallery" && library.items.length > 0
                readonly property int cols: Math.max(1, Math.min(4, Math.floor((width + library.gridGap) / (260 + library.gridGap))))
                cellWidth: Math.floor((width + library.gridGap) / cols)
                cellHeight: 248 + library.gridGap
                cacheBuffer: 360
                boundsBehavior: Flickable.StopAtBounds
                ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }
                model: library.viewMode === "gallery" ? library.items : []

                delegate: Item {
                    required property var modelData
                    width: gridView.cellWidth
                    height: gridView.cellHeight
                    ObjectCard {
                        width: Math.max(120, gridView.cellWidth - library.gridGap)
                        height: 248
                        summary: modelData
                        onOpenRequested: modelData.trashed ? ws.restoreObject(modelData.id) : library.openObjectRequested(modelData.id)
                        onOpenInPaneRequested: if (library.appWindow && !modelData.trashed) library.appWindow.openInPane(modelData.id)
                        onRestoreRequested: ws.restoreObject(modelData.id)
                    }
                }
            }

            // 2. LIST VIEW
            ListView {
                id: listView
                anchors.fill: parent
                visible: library.viewMode === "list" && library.items.length > 0
                cacheBuffer: 260
                boundsBehavior: Flickable.StopAtBounds
                ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }
                model: library.viewMode === "list" ? library.items : []

                delegate: ObjectRow {
                    required property var modelData
                    width: listView.width
                    summary: modelData
                    onOpenRequested: modelData.trashed ? ws.restoreObject(modelData.id) : library.openObjectRequested(modelData.id)
                    onOpenInPaneRequested: if (library.appWindow && !modelData.trashed) library.appWindow.openInPane(modelData.id)
                    onRestoreRequested: ws.restoreObject(modelData.id)
                }
            }

            // 3. TABLE VIEW
            ColumnLayout {
                anchors.fill: parent
                visible: library.viewMode === "table" && library.items.length > 0
                spacing: 0
                readonly property var extraCols: library.typeProps.filter(p => !p.system).slice(0, 3)

                Rectangle {
                    Layout.fillWidth: true; Layout.preferredHeight: 38
                    color: Theme.panel; border.width: 1; border.color: Theme.line
                    RowLayout {
                        anchors.fill: parent; anchors.leftMargin: 12; anchors.rightMargin: 12; spacing: 12
                        Text { Layout.fillWidth: true; text: Theme.caps(qsTr("TITLE")); color: Theme.label; font.family: Theme.mono; font.pixelSize: 11 }
                        Repeater {
                            model: parent.parent.parent.extraCols
                            delegate: Text {
                                required property var modelData
                                Layout.preferredWidth: 150
                                text: Theme.caps(modelData.name || modelData.key)
                                color: Theme.label; font.family: Theme.mono; font.pixelSize: 11; elide: Text.ElideRight
                            }
                        }
                        Text { Layout.preferredWidth: 96; horizontalAlignment: Text.AlignRight; text: Theme.caps(qsTr("UPDATED")); color: Theme.label; font.family: Theme.mono; font.pixelSize: 11 }
                    }
                }
                ListView {
                    Layout.fillWidth: true; Layout.fillHeight: true
                    clip: true; boundsBehavior: Flickable.StopAtBounds
                    ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }
                    model: library.viewMode === "table" ? library.items : []
                    delegate: Rectangle {
                        id: tRow
                        required property var modelData
                        width: ListView.view.width; height: 42
                        color: trHov.hovered ? Theme.panel2 : "transparent"
                        Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.lineSubtle }
                        RowLayout {
                            anchors.fill: parent; anchors.leftMargin: 12; anchors.rightMargin: 12; spacing: 12
                            RowLayout {
                                Layout.fillWidth: true; spacing: 10
                                TypeBadge { typeId: tRow.modelData.typeId || ""; size: 15 }
                                Text { Layout.fillWidth: true; text: tRow.modelData.title || qsTr("Untitled"); color: Theme.text; font.family: Theme.body; font.pixelSize: 14; elide: Text.ElideRight }
                            }
                            Repeater {
                                model: library.typeProps.filter(p => !p.system).slice(0, 3)
                                delegate: Text {
                                    required property var modelData
                                    Layout.preferredWidth: 150
                                    text: ws ? (ws.fieldText(tRow.modelData.id, modelData.key) || "—") : "—"
                                    color: Theme.dim; font.family: Theme.body; font.pixelSize: 13; elide: Text.ElideRight
                                }
                            }
                            Text {
                                Layout.preferredWidth: 96; horizontalAlignment: Text.AlignRight
                                text: tRow.modelData.updatedAt ? Theme.caps(Qt.formatDateTime(new Date(tRow.modelData.updatedAt), "dd MMM")) : ""
                                color: Theme.label; font.family: Theme.mono; font.pixelSize: 11
                            }
                        }
                        HoverHandler { id: trHov; cursorShape: Qt.PointingHandCursor }
                        TapHandler { onTapped: tRow.modelData.trashed ? ws.restoreObject(tRow.modelData.id) : library.openObjectRequested(tRow.modelData.id) }
                    }
                }
            }

            // 4. KANBAN VIEW
            Flickable {
                anchors.fill: parent
                visible: library.viewMode === "kanban" && library.items.length > 0
                contentWidth: kbRow.implicitWidth
                contentHeight: height
                boundsBehavior: Flickable.StopAtBounds
                ScrollBar.horizontal: ScrollBar { policy: ScrollBar.AsNeeded }

                Row {
                    id: kbRow
                    height: parent.height
                    spacing: 14
                    Repeater {
                        model: library.kanbanColumns
                        delegate: Rectangle {
                            required property var modelData
                            width: 270
                            height: kbRow.height
                            color: Theme.ink
                            border.width: 1; border.color: Theme.line

                            ColumnLayout {
                                anchors.fill: parent; spacing: 0
                                RowLayout {
                                    Layout.fillWidth: true; Layout.margins: 12; spacing: 8
                                    Rectangle { width: 8; height: 8; color: modelData.color }
                                    Text { Layout.fillWidth: true; text: modelData.label; color: Theme.text; font.family: Theme.body; font.pixelSize: 14; elide: Text.ElideRight }
                                    Text { text: String(modelData.cards.length); color: Theme.label; font.family: Theme.mono; font.pixelSize: 11 }
                                }
                                Rectangle { Layout.fillWidth: true; height: 1; color: Theme.line }
                                ListView {
                                    Layout.fillWidth: true; Layout.fillHeight: true
                                    Layout.margins: 10
                                    spacing: 8; clip: true
                                    model: modelData.cards
                                    delegate: Rectangle {
                                        required property var modelData
                                        width: ListView.view.width
                                        implicitHeight: kbCardCol.implicitHeight + 20
                                        color: kbCHov.hovered ? Theme.panel2 : Theme.page
                                        border.width: 1; border.color: kbCHov.hovered ? Theme.line3 : Theme.line
                                        ColumnLayout {
                                            id: kbCardCol
                                            anchors.left: parent.left; anchors.right: parent.right; anchors.top: parent.top
                                            anchors.margins: 10; spacing: 4
                                            Text { Layout.fillWidth: true; text: modelData.title || qsTr("Untitled"); color: Theme.text; font.family: Theme.body; font.pixelSize: 14; wrapMode: Text.WordWrap; maximumLineCount: 2; elide: Text.ElideRight }
                                            Text { visible: (modelData.snippet || "").length > 0; Layout.fillWidth: true; text: modelData.snippet || ""; color: Theme.muted; font.family: Theme.body; font.pixelSize: 12; maximumLineCount: 2; elide: Text.ElideRight }
                                        }
                                        HoverHandler { id: kbCHov; cursorShape: Qt.PointingHandCursor }
                                        TapHandler { onTapped: library.openObjectRequested(modelData.id) }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Empty state
            Text {
                visible: library.items.length === 0
                anchors.centerIn: parent
                text: searchQuery.length > 0 ? qsTr("No matches for this filter.") : qsTr("No objects yet.")
                color: Theme.label; font.family: Theme.body; font.pixelSize: 14
            }
        }
    }
}
