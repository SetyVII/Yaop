import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../"

// Right side panel matching docs/design/mockup/Book.dc.html & Person.dc.html:
// 3 tabs with 2px Theme.accent underline:
//   0. Backlinks (+ type filters + Unlinked mentions with "Link" action)
//   1. Local Graph (ws.graphData(id, depth) rendered on Canvas, clickable nodes)
//   2. Details (Outline, Metadata, Version History with Restore, Object actions incl. Change Type & Duplicate)
//   3. Contains (objects this one mentions or embeds, like Capacities' "Contained objects")
Item {
    id: rail

    property var session: null
    property var workspace: null
    property int activeTab: 0         // 0=backlinks, 1=graph, 2=details, 3=contains
    property string backlinkFilter: "all"
    property int graphDepth: 1
    property int rev: 0

    property var backlinks: []
    property var unlinked: []
    property var historyList: []
    property var contained: []
    property var stats: ({})
    property var localGraph: ({ nodes: [], edges: [] })

    signal scrollToBlockRequested(string blockId)
    signal openObjectRequested(string objectId)
    signal openObjectAtBlockRequested(string objectId, string blockId)
    signal changeTypeRequested()
    signal duplicateRequested()
    signal trashRequested()
    signal toastRequested(string message)

    // Opens the preview of version `i` (newest = 0) once the history has loaded.
    property int pendingVersion: -1
    function openVersion(i) {
        activeTab = 2
        if (historyList.length > i) versionPreview.show(historyList[i])
        else pendingVersion = i
    }

    function refreshAll() {
        if (!workspace || !session) {
            backlinks = []
            unlinked = []
            historyList = []
            contained = []
            stats = ({})
            localGraph = { nodes: [], edges: [] }
            return
        }
        const oid = session.objectId
        backlinks = workspace.backlinksFor(oid) || []
        unlinked = workspace.unlinkedMentions(oid) || []
        historyList = workspace.objectHistory(oid) || []
        contained = workspace.containedObjects(oid) || []
        stats = workspace.objectStats(oid) || ({})
        if (pendingVersion >= 0 && historyList.length > pendingVersion) {
            versionPreview.show(historyList[pendingVersion])
            pendingVersion = -1
        }
        localGraph = workspace.graphData(oid, graphDepth) || { nodes: [], edges: [] }
        localGraphCanvas.requestPaint()
    }

    onSessionChanged: {
        backlinkFilter = "all"
        refreshTimer.restart()
    }
    onGraphDepthChanged: {
        if (workspace && session) {
            localGraph = workspace.graphData(session.objectId, graphDepth) || { nodes: [], edges: [] }
            localGraphCanvas.requestPaint()
        }
    }
    onActiveTabChanged: if (activeTab === 1) localGraphCanvas.requestPaint()

    Timer {
        id: refreshTimer
        interval: 60
        repeat: false
        onTriggered: rail.refreshAll()
    }

    Connections {
        target: workspace
        function onBacklinksReady(objectId, objects) {
            if (session && objectId === session.objectId)
                rail.backlinks = objects
        }
        function onObjectListChanged() { rail.rev++; refreshTimer.restart() }
        function onObjectUpdated(id) { if (session && id === session.objectId) refreshTimer.restart() }
        function onObjectPropertiesChanged(id) { if (session && id === session.objectId) refreshTimer.restart() }
    }

    // Backlink type breakdown for filter chips (Person.dc.html)
    readonly property var backlinkTypes: {
        const counts = {}
        for (let i = 0; i < backlinks.length; i++) {
            const tid = backlinks[i].typeId || "note"
            counts[tid] = (counts[tid] || 0) + 1
        }
        const keys = Object.keys(counts)
        if (keys.length <= 1) return []
        const out = [{ id: "all", label: qsTr("All"), color: "#5A5A5A", n: backlinks.length }]
        for (let i = 0; i < keys.length; i++) {
            const k = keys[i]
            const info = workspace ? workspace.typeInfo(k) : null
            out.push({ id: k, label: (info && info.name) ? info.name : Theme.typeName(k), color: String(Theme.typeColor(k)), n: counts[k] })
        }
        return out
    }
    readonly property var filteredBacklinks: {
        if (backlinkFilter === "all") return backlinks
        return backlinks.filter(b => (b.typeId || "note") === backlinkFilter)
    }

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        // ---------------- 3-tab header (Book.dc.html) ----------------
        Item {
            Layout.fillWidth: true
            Layout.preferredHeight: 48

            Rectangle { anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom; height: 1; color: Theme.line }

            Row {
                anchors.left: parent.left; anchors.leftMargin: 20
                anchors.bottom: parent.bottom
                spacing: 20

                Repeater {
                    model: [
                        { idx: 0, label: qsTr("Backlinks") },
                        { idx: 3, label: qsTr("Contains") },
                        { idx: 1, label: qsTr("Graph") },
                        { idx: 2, label: qsTr("Details") }
                    ]
                    delegate: Item {
                        required property var modelData
                        readonly property bool on: rail.activeTab === modelData.idx
                        width: tabLbl.implicitWidth + 4
                        height: 48

                        Text {
                            id: tabLbl
                            anchors.centerIn: parent
                            text: modelData.label
                            color: on || tabHov.hovered ? Theme.text : Theme.label
                            font.family: Theme.body
                            font.pixelSize: 13
                            font.weight: on ? Font.Medium : Font.Normal
                        }
                        Rectangle {
                            visible: on
                            anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom
                            height: 2
                            color: Theme.accent
                        }
                        HoverHandler { id: tabHov; cursorShape: Qt.PointingHandCursor }
                        TapHandler { onTapped: rail.activeTab = modelData.idx }
                    }
                }
            }
        }

        // ---------------- Tab bodies ----------------
        Flickable {
            Layout.fillWidth: true
            Layout.fillHeight: true
            contentWidth: width
            contentHeight: tabBodyCol.implicitHeight + 36
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }

            ColumnLayout {
                id: tabBodyCol
                x: 20; y: 18
                width: parent.width - 40
                spacing: 0

                // ===== TAB 0: BACKLINKS + UNLINKED MENTIONS =====
                ColumnLayout {
                    visible: rail.activeTab === 0
                    Layout.fillWidth: true
                    spacing: 0

                    Text {
                        text: Theme.caps(qsTr("%1 OBJECTS LINK HERE").arg(rail.backlinks.length))
                        color: Theme.label; font.family: Theme.mono; font.pixelSize: 11
                        Layout.bottomMargin: 8
                    }

                    // Filter by source type (when multiple types link here — Person.dc.html)
                    Flow {
                        visible: rail.backlinkTypes.length > 0
                        Layout.fillWidth: true
                        Layout.bottomMargin: 10
                        spacing: 4
                        Repeater {
                            model: rail.backlinkTypes
                            delegate: Rectangle {
                                required property var modelData
                                readonly property bool on: rail.backlinkFilter === modelData.id
                                implicitWidth: bfRow.implicitWidth + 14; implicitHeight: 26
                                color: on ? Theme.accent : bfHov.hovered ? Theme.panel2 : "transparent"
                                border.width: 1; border.color: on ? Theme.accent : Theme.line
                                Row {
                                    id: bfRow; anchors.centerIn: parent; spacing: 5
                                    Rectangle { anchors.verticalCenter: parent.verticalCenter; width: 6; height: 6; color: modelData.color }
                                    Text { anchors.verticalCenter: parent.verticalCenter; text: modelData.label; color: on ? Theme.accentInk : Theme.dim; font.family: Theme.body; font.pixelSize: 12 }
                                    Text { anchors.verticalCenter: parent.verticalCenter; text: String(modelData.n); color: on ? Theme.accentInk : Theme.label; font.family: Theme.mono; font.pixelSize: 10 }
                                }
                                HoverHandler { id: bfHov; cursorShape: Qt.PointingHandCursor }
                                TapHandler { onTapped: rail.backlinkFilter = modelData.id }
                            }
                        }
                    }

                    Repeater {
                        model: rail.filteredBacklinks
                        delegate: Rectangle {
                            id: blItem
                            required property var modelData
                            Layout.fillWidth: true
                            implicitHeight: blCol.implicitHeight + 24
                            color: blHov.hovered ? Theme.panel2 : "transparent"

                            Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.lineSubtle }

                            ColumnLayout {
                                id: blCol
                                anchors.left: parent.left; anchors.right: parent.right
                                anchors.verticalCenter: parent.verticalCenter
                                anchors.leftMargin: blHov.hovered ? 6 : 0
                                spacing: 5
                                Behavior on anchors.leftMargin { NumberAnimation { duration: Theme.fast } }

                                RowLayout {
                                    Layout.fillWidth: true; spacing: 8
                                    TypeBadge { typeId: blItem.modelData.typeId || ""; size: 14 }
                                    Text {
                                        Layout.fillWidth: true
                                        text: blItem.modelData.title || qsTr("Untitled")
                                        color: blHov.hovered ? Theme.text : Theme.bodyText
                                        font.family: Theme.body; font.pixelSize: 14
                                        elide: Text.ElideRight
                                    }
                                    Text {
                                        text: Theme.typeLabel(blItem.modelData.typeId || "")
                                        color: Theme.label; font.family: Theme.mono; font.pixelSize: 10
                                    }
                                }
                                Text {
                                    visible: (blItem.modelData.snippet || "").length > 0
                                    Layout.fillWidth: true
                                    text: blItem.modelData.snippet || ""
                                    color: Theme.muted; font.family: Theme.body; font.pixelSize: 12; lineHeight: 1.4
                                    wrapMode: Text.WordWrap; maximumLineCount: 3; elide: Text.ElideRight
                                }
                            }
                            HoverHandler { id: blHov; cursorShape: Qt.PointingHandCursor }
                            TapHandler {
                                onTapped: {
                                    if (blItem.modelData.blockId && blItem.modelData.blockId.length > 0)
                                        rail.openObjectAtBlockRequested(blItem.modelData.id, blItem.modelData.blockId)
                                    else
                                        rail.openObjectRequested(blItem.modelData.id)
                                }
                            }
                        }
                    }

                    Text {
                        visible: rail.filteredBacklinks.length === 0
                        Layout.topMargin: 6
                        text: qsTr("Nothing links here yet.")
                        color: Theme.faint; font.family: Theme.body; font.pixelSize: 13
                    }

                    // Unlinked mentions (Book.dc.html + pkmcore/editor/unlinked)
                    Text {
                        Layout.topMargin: 28; Layout.bottomMargin: 6
                        text: Theme.caps(qsTr("UNLINKED MENTIONS · %1").arg(rail.unlinked.length))
                        color: Theme.label; font.family: Theme.mono; font.pixelSize: 11
                    }
                    Repeater {
                        model: rail.unlinked
                        delegate: Rectangle {
                            id: ulItem
                            required property var modelData
                            Layout.fillWidth: true
                            implicitHeight: ulCol.implicitHeight + 20
                            color: "transparent"
                            Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.lineSubtle }

                            ColumnLayout {
                                id: ulCol
                                anchors.left: parent.left; anchors.right: parent.right
                                anchors.verticalCenter: parent.verticalCenter
                                spacing: 6

                                RowLayout {
                                    Layout.fillWidth: true; spacing: 8
                                    TypeBadge { typeId: ulItem.modelData.typeId || ""; size: 14 }
                                    Text {
                                        Layout.fillWidth: true
                                        text: ulItem.modelData.title || qsTr("Untitled")
                                        color: Theme.bodyText; font.family: Theme.body; font.pixelSize: 13
                                        elide: Text.ElideRight
                                        HoverHandler { cursorShape: Qt.PointingHandCursor }
                                        TapHandler { onTapped: rail.openObjectRequested(ulItem.modelData.objectId) }
                                    }
                                    Rectangle {
                                        implicitWidth: linkBtnLbl.implicitWidth + 14; implicitHeight: 22
                                        color: lnkHov.hovered ? Theme.accent : "transparent"
                                        border.width: 1; border.color: lnkHov.hovered ? Theme.accent : Theme.line2
                                        Text {
                                            id: linkBtnLbl; anchors.centerIn: parent
                                            text: qsTr("Link")
                                            color: lnkHov.hovered ? Theme.accentInk : Theme.bodyText
                                            font.family: Theme.body; font.pixelSize: 11
                                        }
                                        HoverHandler { id: lnkHov; cursorShape: Qt.PointingHandCursor }
                                        TapHandler {
                                            onTapped: if (rail.workspace && rail.session) {
                                                if (rail.workspace.linkUnlinkedMention(rail.session.objectId, ulItem.modelData)) {
                                                    rail.refreshAll()
                                                    rail.toastRequested(qsTr("Mention linked"))
                                                }
                                            }
                                        }
                                    }
                                }
                                Text {
                                    visible: (ulItem.modelData.snippet || "").length > 0
                                    Layout.fillWidth: true
                                    text: ulItem.modelData.snippet || ""
                                    color: Theme.muted; font.family: Theme.body; font.pixelSize: 12
                                    wrapMode: Text.WordWrap; maximumLineCount: 2; elide: Text.ElideRight
                                }
                            }
                        }
                    }
                    Text {
                        visible: rail.unlinked.length === 0
                        text: qsTr("None found.")
                        color: Theme.faint; font.family: Theme.body; font.pixelSize: 12
                    }
                }

                // ===== TAB 3: CONTAINED OBJECTS =====
                ColumnLayout {
                    visible: rail.activeTab === 3
                    Layout.fillWidth: true
                    spacing: 0

                    Text {
                        text: Theme.caps(qsTr("%1 OBJECTS IN THIS PAGE").arg(rail.contained.length))
                        color: Theme.label; font.family: Theme.mono; font.pixelSize: 11
                        Layout.bottomMargin: 8
                    }
                    Repeater {
                        model: rail.contained
                        delegate: Rectangle {
                            id: cItem
                            required property var modelData
                            Layout.fillWidth: true
                            implicitHeight: 36
                            color: cHov.hovered ? Theme.panel2 : "transparent"
                            Behavior on color { ColorAnimation { duration: Theme.fast } }
                            Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.lineSubtle }
                            RowLayout {
                                anchors.fill: parent
                                anchors.leftMargin: 4
                                anchors.rightMargin: 4
                                spacing: 10
                                TypeBadge { typeId: cItem.modelData.typeId; size: 14 }
                                Text {
                                    Layout.fillWidth: true
                                    text: cItem.modelData.title || qsTr("Untitled")
                                    color: cHov.hovered ? Theme.text : Theme.bodyText
                                    font.family: Theme.body; font.pixelSize: 13
                                    elide: Text.ElideRight
                                }
                            }
                            HoverHandler { id: cHov; cursorShape: Qt.PointingHandCursor }
                            TapHandler { onTapped: rail.openObjectRequested(cItem.modelData.id) }
                        }
                    }
                    Text {
                        visible: rail.contained.length === 0
                        Layout.fillWidth: true
                        text: qsTr("Mention an object with @ or embed it, and it shows here.")
                        color: Theme.faint; font.family: Theme.body; font.pixelSize: 12
                        wrapMode: Text.WordWrap
                    }
                }

                // ===== TAB 1: LOCAL GRAPH (Book.dc.html) =====
                ColumnLayout {
                    visible: rail.activeTab === 1
                    Layout.fillWidth: true
                    spacing: 12

                    Item {
                        Layout.fillWidth: true
                        Layout.preferredHeight: 280

                        Canvas {
                            id: localGraphCanvas
                            anchors.fill: parent
                            property var positions: []

                            onPaint: {
                                const ctx = getContext("2d")
                                ctx.reset()
                                ctx.fillStyle = String(Theme.page)
                                ctx.fillRect(0, 0, width, height)

                                const nodes = (rail.localGraph && rail.localGraph.nodes) ? rail.localGraph.nodes : []
                                const edges = (rail.localGraph && rail.localGraph.edges) ? rail.localGraph.edges : []
                                if (nodes.length === 0) { positions = []; return }

                                const centerId = rail.session ? rail.session.objectId : ""
                                const cx = width / 2, cy = height / 2
                                const rad = Math.min(width, height) * 0.36
                                const posById = {}
                                const posList = []
                                const others = []
                                for (let i = 0; i < nodes.length; i++) {
                                    if (nodes[i].id === centerId) {
                                        posById[nodes[i].id] = { x: cx, y: cy, node: nodes[i], center: true }
                                        posList.push(posById[nodes[i].id])
                                    } else {
                                        others.push(nodes[i])
                                    }
                                }
                                for (let i = 0; i < others.length; i++) {
                                    const a = (i / Math.max(1, others.length)) * Math.PI * 2 - Math.PI / 2
                                    const r = (i % 2 === 0 ? rad : rad * 0.78)
                                    const pt = { x: cx + Math.cos(a) * r, y: cy + Math.sin(a) * r, node: others[i], center: false }
                                    posById[others[i].id] = pt
                                    posList.push(pt)
                                }
                                positions = posList

                                const acc = String(Theme.accent)
                                for (let i = 0; i < edges.length; i++) {
                                    const a = posById[edges[i].from], b = posById[edges[i].to]
                                    if (!a || !b) continue
                                    const direct = a.center || b.center
                                    ctx.strokeStyle = direct ? acc : "#3A3A3A"
                                    ctx.lineWidth = 1
                                    ctx.beginPath(); ctx.moveTo(a.x, a.y); ctx.lineTo(b.x, b.y); ctx.stroke()
                                }
                                for (let i = 0; i < posList.length; i++) {
                                    const p = posList[i]
                                    const sz = p.center ? 18 : 11
                                    ctx.fillStyle = p.center ? acc : String(Theme.typeColor(p.node.typeId || ""))
                                    ctx.fillRect(p.x - sz / 2, p.y - sz / 2, sz, sz)
                                    if (!p.center) {
                                        ctx.fillStyle = "#BDBDBD"
                                        ctx.font = "400 11px Inter"
                                        const lbl = String(p.node.title || "")
                                        ctx.fillText(lbl.length > 18 ? lbl.substring(0, 17) + "…" : lbl, p.x + sz / 2 + 5, p.y + 4)
                                    }
                                }
                            }
                            onWidthChanged: requestPaint()
                            onHeightChanged: requestPaint()
                        }

                        MouseArea {
                            anchors.fill: parent
                            hoverEnabled: true
                            onClicked: (mouse) => {
                                const pts = localGraphCanvas.positions || []
                                for (let i = 0; i < pts.length; i++) {
                                    if (pts[i].center) continue
                                    const dx = pts[i].x - mouse.x, dy = pts[i].y - mouse.y
                                    if (dx * dx + dy * dy <= 16 * 16) {
                                        rail.openObjectRequested(pts[i].node.id)
                                        return
                                    }
                                }
                            }
                        }
                    }

                    RowLayout {
                        Layout.fillWidth: true
                        Text {
                            Layout.fillWidth: true
                            text: Theme.caps(qsTr("DEPTH %1 · COLOR BY TYPE").arg(rail.graphDepth))
                            color: Theme.label; font.family: Theme.mono; font.pixelSize: 11
                        }
                        Button {
                            small: true
                            text: rail.graphDepth === 1 ? qsTr("Depth 2") : qsTr("Depth 1")
                            onClicked: rail.graphDepth = rail.graphDepth === 1 ? 2 : 1
                        }
                    }
                }

                // ===== TAB 2: DETAILS + OUTLINE + VERSION HISTORY + ACTIONS =====
                ColumnLayout {
                    visible: rail.activeTab === 2
                    Layout.fillWidth: true
                    spacing: 0

                    // Outline
                    Text {
                        text: Theme.caps(qsTr("OUTLINE"))
                        color: Theme.label; font.family: Theme.mono; font.pixelSize: 11
                        Layout.bottomMargin: 8
                    }
                    Repeater {
                        model: rail.session ? rail.session.outline : []
                        delegate: Rectangle {
                            required property var modelData
                            Layout.fillWidth: true; implicitHeight: 28
                            color: outHov.hovered ? Theme.panel2 : "transparent"
                            Text {
                                anchors.left: parent.left; anchors.leftMargin: (modelData.level - 1) * 10 + 4
                                anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter
                                text: modelData.text
                                color: outHov.hovered ? Theme.text : Theme.dim
                                font.family: Theme.body; font.pixelSize: modelData.level <= 2 ? 13 : 12
                                elide: Text.ElideRight
                            }
                            HoverHandler { id: outHov; cursorShape: Qt.PointingHandCursor }
                            TapHandler { onTapped: rail.scrollToBlockRequested(modelData.id) }
                        }
                    }
                    Text {
                        visible: !rail.session || !rail.session.outline || rail.session.outline.length === 0
                        text: qsTr("No headings yet.")
                        color: Theme.faint; font.family: Theme.body; font.pixelSize: 12
                    }

                    // Metadata rows (Book.dc.html)
                    Text {
                        Layout.topMargin: 24; Layout.bottomMargin: 6
                        text: Theme.caps(qsTr("INFO"))
                        color: Theme.label; font.family: Theme.mono; font.pixelSize: 11
                    }
                    component MetaRow: Rectangle {
                        property string k: ""
                        property string v: ""
                        Layout.fillWidth: true; implicitHeight: 38
                        color: "transparent"
                        Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.lineSubtle }
                        RowLayout {
                            anchors.fill: parent
                            Text { text: k; color: Theme.label; font.family: Theme.body; font.pixelSize: 13 }
                            Item { Layout.fillWidth: true }
                            Text { text: v; color: Theme.bodyText; font.family: Theme.body; font.pixelSize: 13 }
                        }
                    }
                    MetaRow {
                        k: qsTr("Type")
                        v: {
                            if (!rail.session) return ""
                            const info = rail.workspace ? rail.workspace.typeInfo(rail.session.typeId) : null
                            return (info && info.name) ? info.name : Theme.typeName(rail.session.typeId)
                        }
                    }
                    MetaRow {
                        k: qsTr("Words")
                        v: String(rail.stats.words || 0)
                    }
                    MetaRow {
                        k: qsTr("Sentences · paragraphs")
                        v: (rail.stats.sentences || 0) + " · " + (rail.stats.paragraphs || 0)
                    }
                    MetaRow {
                        k: qsTr("Characters")
                        v: String(rail.stats.characters || 0)
                    }
                    MetaRow {
                        k: qsTr("Created")
                        v: rail.stats.createdAt ? Qt.formatDateTime(new Date(rail.stats.createdAt), "d MMM yyyy, HH:mm") : "—"
                    }
                    MetaRow {
                        k: qsTr("Updated")
                        v: rail.stats.updatedAt ? Qt.formatDateTime(new Date(rail.stats.updatedAt), "d MMM yyyy, HH:mm") : "—"
                    }
                    MetaRow {
                        k: qsTr("History")
                        v: (rail.historyList.length === 1 ? qsTr("1 version") : qsTr("%1 versions").arg(rail.historyList.length)) + qsTr(" · 30 days")
                    }
                    MetaRow {
                        k: qsTr("Encryption")
                        v: qsTr("Vault key (E2EE)")
                    }

                    // Object actions
                    Text {
                        Layout.topMargin: 24; Layout.bottomMargin: 8
                        text: Theme.caps(qsTr("ACTIONS"))
                        color: Theme.label; font.family: Theme.mono; font.pixelSize: 11
                    }
                    Flow {
                        Layout.fillWidth: true
                        spacing: 6
                        Button { small: true; text: qsTr("Change type…"); onClicked: rail.changeTypeRequested() }
                        Button { small: true; text: qsTr("Duplicate"); onClicked: rail.duplicateRequested() }
                        Button {
                            small: true; text: qsTr("Copy link")
                            onClicked: if (rail.workspace && rail.session) {
                                rail.workspace.copyTextToClipboard("yaop://object/" + rail.session.objectId)
                                rail.toastRequested(qsTr("Object link copied"))
                            }
                        }
                        Button { small: true; danger: true; text: qsTr("Trash"); onClicked: rail.trashRequested() }
                    }

                    // Version history list (product §16.3, workspace_history.cpp)
                    Text {
                        Layout.topMargin: 24; Layout.bottomMargin: 6
                        text: Theme.caps(qsTr("VERSION HISTORY · %1").arg(rail.historyList.length))
                        color: Theme.label; font.family: Theme.mono; font.pixelSize: 11
                    }
                    Repeater {
                        model: rail.historyList.slice(0, 20)
                        delegate: Rectangle {
                            id: hRow
                            required property var modelData
                            Layout.fillWidth: true
                            implicitHeight: hCol.implicitHeight + 18
                            color: "transparent"
                            Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.lineSubtle }
                            RowLayout {
                                id: hCol
                                anchors.left: parent.left; anchors.right: parent.right
                                anchors.verticalCenter: parent.verticalCenter
                                spacing: 8
                                ColumnLayout {
                                    Layout.fillWidth: true; spacing: 2
                                    Text {
                                        text: Qt.formatDateTime(new Date(hRow.modelData.createdAt), "dd MMM, HH:mm")
                                        color: Theme.bodyText; font.family: Theme.mono; font.pixelSize: 11
                                    }
                                    Text {
                                        Layout.fillWidth: true
                                        text: qsTr("%1 blocks").arg(hRow.modelData.blockCount || 0)
                                              + (hRow.modelData.preview && hRow.modelData.preview.length > 0 ? (" · " + hRow.modelData.preview[0]) : "")
                                        color: Theme.muted; font.family: Theme.body; font.pixelSize: 12
                                        elide: Text.ElideRight
                                    }
                                }
                                Button {
                                    small: true; text: qsTr("View")
                                    onClicked: versionPreview.show(hRow.modelData)
                                }
                            }
                        }
                    }
                    Text {
                        visible: rail.historyList.length === 0
                        text: qsTr("Snapshots are saved automatically when closing an edited object.")
                        color: Theme.faint; font.family: Theme.body; font.pixelSize: 12; wrapMode: Text.WordWrap
                        Layout.fillWidth: true
                    }
                }
            }
        }
    }

    VersionPreview {
        id: versionPreview
        workspace: rail.workspace
        session: rail.session
        onRestored: (whole) => {
            rail.refreshAll()
            rail.toastRequested(whole ? qsTr("Version restored") : qsTr("Block restored"))
        }
    }
}
