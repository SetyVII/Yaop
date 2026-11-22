import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "."

// Global interactive graph (docs/design/mockup/Graph.dc.html).
// Uses ws.graphData() (pkmcore/editor/graph): nodes colored by type, sized by degree,
// hover highlights node + neighbors in Theme.accent, click opens object (Shift+click side pane).
Item {
    id: page

    property var appWindow: null
    property bool shown: false
    signal openObjectRequested(string objectId)

    readonly property int dataRev: (shown && ws) ? ws.listRevision : 0
    property var selectedTypes: ({})
    property string hoverType: ""
    property int hoverNode: -1
    property string caption: qsTr("Color by type · size by link count · click a node to open")

    property var graphNodes: []
    property var graphEdges: []
    property var typeCounts: []
    property var neighbors: []
    property real zoom: 1.0
    property real panX: 0
    property real panY: 0

    function rebuild() {
        if (!ws) return
        const g = ws.graphData("", 1, [])
        const rawNodes = g.nodes || []
        const rawEdges = g.edges || []

        // Group counts by type
        const tc = {}
        for (let i = 0; i < rawNodes.length; i++) {
            const tid = rawNodes[i].typeId || "note"
            tc[tid] = (tc[tid] || 0) + 1
        }
        const typeOrder = Object.keys(tc).sort((a, b) => tc[b] - tc[a])
        const typesOut = []
        for (let i = 0; i < typeOrder.length; i++) {
            const tid = typeOrder[i]
            const info = ws.typeInfo(tid)
            typesOut.push({
                id: tid,
                label: (info && info.name) ? info.name : Theme.typeName(tid),
                color: String(Theme.typeColor(tid)),
                n: tc[tid]
            })
        }
        typeCounts = typesOut

        // Deterministic force-directed layout (matching Graph.dc.html)
        const idToIdx = {}
        let seed = 7
        const rnd = () => { seed = (seed * 16807) % 2147483647; return seed / 2147483647 }
        const typeAngle = {}
        for (let i = 0; i < typeOrder.length; i++)
            typeAngle[typeOrder[i]] = (i / Math.max(1, typeOrder.length)) * Math.PI * 2

        const nodes = []
        for (let i = 0; i < rawNodes.length; i++) {
            const n = rawNodes[i]
            idToIdx[n.id] = i
            const a = typeAngle[n.typeId] || 0
            nodes.push({
                id: n.id,
                typeId: n.typeId || "note",
                title: n.title || qsTr("Untitled"),
                deg: n.degree || 0,
                color: String(Theme.typeColor(n.typeId || "note")),
                x: Math.cos(a) * 180 + (rnd() - 0.5) * 160,
                y: Math.sin(a) * 140 + (rnd() - 0.5) * 140,
                vx: 0, vy: 0
            })
        }
        const edges = []
        const nb = nodes.map(() => [])
        for (let i = 0; i < rawEdges.length; i++) {
            const a = idToIdx[rawEdges[i].from]
            const b = idToIdx[rawEdges[i].to]
            if (a !== undefined && b !== undefined && a !== b) {
                edges.push([a, b])
                nb[a].push(b)
                nb[b].push(a)
            }
        }

        // Run layout iterations (capped for large vaults so UI stays snappy)
        const iters = nodes.length > 180 ? 80 : 180
        for (let it = 0; it < iters; it++) {
            for (let i = 0; i < nodes.length; i++) {
                const a = nodes[i]
                for (let j = i + 1; j < nodes.length; j++) {
                    const b = nodes[j]
                    const dx = a.x - b.x
                    const dy = a.y - b.y
                    const d2 = dx * dx + dy * dy
                    const f = Math.min(0.5, 900 / (d2 + 400))
                    a.vx += dx * f; a.vy += dy * f
                    b.vx -= dx * f; b.vy -= dy * f
                }
            }
            for (let k = 0; k < edges.length; k++) {
                const a = nodes[edges[k][0]], b = nodes[edges[k][1]]
                const dx = b.x - a.x, dy = b.y - a.y
                a.vx += dx * 0.005; a.vy += dy * 0.005
                b.vx -= dx * 0.005; b.vy -= dy * 0.005
            }
            for (let i = 0; i < nodes.length; i++) {
                const n = nodes[i]
                n.vx -= n.x * 0.0025; n.vy -= n.y * 0.0025
                n.x += n.vx * 0.5; n.y += n.vy * 0.5
                n.vx *= 0.6; n.vy *= 0.6
            }
        }

        if (nodes.length > 0) {
            let x0 = Infinity, x1 = -Infinity, y0 = Infinity, y1 = -Infinity
            for (let i = 0; i < nodes.length; i++) {
                x0 = Math.min(x0, nodes[i].x); x1 = Math.max(x1, nodes[i].x)
                y0 = Math.min(y0, nodes[i].y); y1 = Math.max(y1, nodes[i].y)
            }
            const fit = Math.min(640 / Math.max(1, x1 - x0), 460 / Math.max(1, y1 - y0))
            const cx = (x0 + x1) / 2, cy = (y0 + y1) / 2
            for (let i = 0; i < nodes.length; i++) {
                nodes[i].x = (nodes[i].x - cx) * fit
                nodes[i].y = (nodes[i].y - cy) * fit
            }
        }

        graphNodes = nodes
        graphEdges = edges
        neighbors = nb
        hoverNode = -1
        canvas.requestPaint()
    }

    onDataRevChanged: if (shown) rebuild()
    onShownChanged: if (shown) rebuild()
    Component.onCompleted: if (shown) rebuild()

    function toggleTypeFilter(tid) {
        const next = Object.assign({}, selectedTypes)
        next[tid] = !next[tid]
        selectedTypes = next
        canvas.requestPaint()
    }

    function hasActiveTypeFilter() {
        const ks = Object.keys(selectedTypes)
        for (let i = 0; i < ks.length; i++) if (selectedTypes[ks[i]]) return true
        return false
    }

    RowLayout {
        anchors.fill: parent
        spacing: 0

        // Left filter sidebar (300px — Graph.dc.html)
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
                anchors.topMargin: 28
                spacing: 0

                Text {
                    Layout.leftMargin: 12
                    text: qsTr("Graph")
                    color: Theme.text
                    font.family: Theme.display
                    font.pixelSize: 22
                    font.weight: Font.Normal
                }
                Text {
                    Layout.leftMargin: 12
                    Layout.topMargin: 6
                    Layout.bottomMargin: 24
                    text: Theme.caps(qsTr("%1 objects · %2 links").arg(page.graphNodes.length).arg(page.graphEdges.length))
                    color: Theme.label
                    font.family: Theme.mono
                    font.pixelSize: 11
                }
                Text {
                    Layout.leftMargin: 12
                    Layout.bottomMargin: 8
                    text: Theme.caps(qsTr("FILTER BY TYPE"))
                    color: Theme.label
                    font.family: Theme.mono
                    font.pixelSize: 11
                }

                Flickable {
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    contentWidth: width
                    contentHeight: typeCol.implicitHeight + 80
                    clip: true
                    boundsBehavior: Flickable.StopAtBounds

                    ColumnLayout {
                        id: typeCol
                        width: parent.width
                        spacing: 2

                        Repeater {
                            model: page.typeCounts
                            delegate: Rectangle {
                                required property var modelData
                                readonly property bool on: !!page.selectedTypes[modelData.id]
                                Layout.fillWidth: true
                                implicitHeight: 38
                                color: on ? Theme.accent : tHover.hovered ? Theme.panel2 : "transparent"
                                Behavior on color { ColorAnimation { duration: Theme.fast } }

                                RowLayout {
                                    anchors.fill: parent
                                    anchors.leftMargin: 12
                                    anchors.rightMargin: 12
                                    spacing: 10
                                    TypeBadge {
                                        typeId: modelData.id
                                        size: 16
                                        overrideColor: on ? Theme.accentInk : "transparent"
                                    }
                                    Text {
                                        Layout.fillWidth: true
                                        text: modelData.label
                                        color: on ? Theme.accentInk : (tHover.hovered ? Theme.text : Theme.dim)
                                        font.family: Theme.body
                                        font.pixelSize: 14
                                        elide: Text.ElideRight
                                    }
                                    Text {
                                        text: String(modelData.n)
                                        color: on ? Theme.accentInk : Theme.label
                                        font.family: Theme.mono
                                        font.pixelSize: 11
                                    }
                                }
                                HoverHandler {
                                    id: tHover
                                    cursorShape: Qt.PointingHandCursor
                                    onHoveredChanged: {
                                        page.hoverType = hovered ? modelData.id : ""
                                        canvas.requestPaint()
                                    }
                                }
                                TapHandler { onTapped: page.toggleTypeFilter(modelData.id) }
                            }
                        }

                        Text {
                            Layout.fillWidth: true
                            Layout.leftMargin: 12
                            Layout.rightMargin: 12
                            Layout.topMargin: 24
                            text: qsTr("Hover a node to inspect its connections. Mentions, tags, and placed whiteboard objects count as links.")
                            color: Theme.label
                            font.family: Theme.body
                            font.pixelSize: 13
                            lineHeight: 1.5
                            wrapMode: Text.WordWrap
                        }
                    }
                }
            }
        }

        // Right interactive graph canvas
        Item {
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true

            Canvas {
                id: canvas
                anchors.fill: parent

                function screenPos(n) {
                    const ox = width / 2 + page.panX
                    const oy = height / 2 + page.panY
                    const s = Math.min(width / 760, height / 560) * page.zoom
                    return [ox + n.x * s, oy + n.y * s]
                }

                onPaint: {
                    const ctx = getContext("2d")
                    ctx.reset()
                    ctx.fillStyle = String(Theme.page)
                    ctx.fillRect(0, 0, width, height)

                    const nodes = page.graphNodes
                    const edges = page.graphEdges
                    if (nodes.length === 0) return

                    const anyFilter = page.hasActiveTypeFilter()
                    const ht = page.hoverType
                    const hov = page.hoverNode
                    const nb = page.neighbors
                    const acc = String(Theme.accent)

                    const P = nodes.map(n => screenPos(n))
                    const alphaFor = (i) => {
                        let a = 1.0
                        if (ht.length > 0) a = nodes[i].typeId === ht ? 1.0 : 0.14
                        else if (anyFilter) a = page.selectedTypes[nodes[i].typeId] ? 1.0 : 0.12
                        if (hov >= 0) a = (i === hov || (nb[hov] && nb[hov].indexOf(i) >= 0)) ? 1.0 : 0.14
                        return a
                    }

                    // Draw edges
                    for (let k = 0; k < edges.length; k++) {
                        const a = edges[k][0], b = edges[k][1]
                        const hot = hov >= 0 && (a === hov || b === hov)
                        ctx.strokeStyle = hot ? acc : "rgba(255,255,255," + (0.14 * Math.min(alphaFor(a), alphaFor(b))) + ")"
                        ctx.lineWidth = hot ? 1.25 : 1.0
                        ctx.beginPath()
                        ctx.moveTo(P[a][0], P[a][1])
                        ctx.lineTo(P[b][0], P[b][1])
                        ctx.stroke()
                    }

                    // Draw square nodes
                    for (let i = 0; i < nodes.length; i++) {
                        const n = nodes[i]
                        const p = P[i]
                        const z = 5 + Math.min(9, n.deg * 0.85)
                        const a = alphaFor(i)
                        ctx.globalAlpha = a
                        ctx.fillStyle = (i === hov) ? acc : n.color
                        ctx.fillRect(p[0] - z / 2, p[1] - z / 2, z, z)
                        if (i === hov) {
                            ctx.strokeStyle = acc
                            ctx.lineWidth = 1.2
                            ctx.strokeRect(p[0] - z / 2 - 4, p[1] - z / 2 - 4, z + 8, z + 8)
                        }
                        // Labels for hovered node, direct neighbors, or high-degree hubs
                        if (i === hov || (hov >= 0 && nb[hov] && nb[hov].indexOf(i) >= 0) || (hov < 0 && n.deg >= 3 && a > 0.5)) {
                            ctx.fillStyle = (i === hov) ? "#F2F2F2" : "#BDBDBD"
                            ctx.font = (i === hov ? "500 12px Inter" : "400 11px Inter")
                            ctx.fillText(n.title.length > 28 ? n.title.substring(0, 27) + "…" : n.title, p[0] + z / 2 + 6, p[1] + 4)
                        }
                    }
                    ctx.globalAlpha = 1.0
                }
                onWidthChanged: requestPaint()
                onHeightChanged: requestPaint()
            }

            MouseArea {
                anchors.fill: parent
                hoverEnabled: true
                property real lastX: 0
                property real lastY: 0
                property bool dragged: false

                function pickNode(mx, my) {
                    const nodes = page.graphNodes
                    let best = 18 * 18, found = -1
                    for (let i = 0; i < nodes.length; i++) {
                        const p = canvas.screenPos(nodes[i])
                        const d = (p[0] - mx) * (p[0] - mx) + (p[1] - my) * (p[1] - my)
                        if (d < best) { best = d; found = i }
                    }
                    return found
                }

                onPressed: (mouse) => { lastX = mouse.x; lastY = mouse.y; dragged = false }
                onPositionChanged: (mouse) => {
                    if (pressed) {
                        const dx = mouse.x - lastX, dy = mouse.y - lastY
                        if (Math.abs(dx) > 2 || Math.abs(dy) > 2) dragged = true
                        page.panX += dx; page.panY += dy
                        lastX = mouse.x; lastY = mouse.y
                        canvas.requestPaint()
                        return
                    }
                    const found = pickNode(mouse.x, mouse.y)
                    if (found !== page.hoverNode) {
                        page.hoverNode = found
                        cursorShape = found >= 0 ? Qt.PointingHandCursor : Qt.ArrowCursor
                        if (found >= 0) {
                            const n = page.graphNodes[found]
                            const info = ws ? ws.typeInfo(n.typeId) : null
                            const tn = (info && info.name) ? info.name : Theme.typeName(n.typeId)
                            const deg = page.neighbors[found] ? page.neighbors[found].length : n.deg
                            page.caption = n.title + " · " + tn + " · " + qsTr("%1 links").arg(deg)
                        } else {
                            page.caption = qsTr("Color by type · size by link count · click a node to open")
                        }
                        canvas.requestPaint()
                    }
                }
                onExited: {
                    if (page.hoverNode !== -1) {
                        page.hoverNode = -1
                        page.caption = qsTr("Color by type · size by link count · click a node to open")
                        canvas.requestPaint()
                    }
                }
                onReleased: (mouse) => {
                    if (!dragged && page.hoverNode >= 0) {
                        const id = page.graphNodes[page.hoverNode].id
                        page.openObjectRequested(id)
                    }
                }
                onWheel: (wheel) => {
                    const step = wheel.angleDelta.y > 0 ? 1.12 : 1 / 1.12
                    page.zoom = Math.max(0.4, Math.min(3.0, page.zoom * step))
                    canvas.requestPaint()
                }
            }

            // Bottom-left caption + zoom reset
            RowLayout {
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.bottom: parent.bottom
                anchors.margins: 20

                Text {
                    Layout.fillWidth: true
                    text: Theme.caps(page.caption)
                    color: Theme.label
                    font.family: Theme.mono
                    font.pixelSize: 11
                    elide: Text.ElideRight
                }
                Button {
                    visible: page.zoom !== 1.0 || page.panX !== 0 || page.panY !== 0
                    small: true
                    text: qsTr("Reset view")
                    onClicked: { page.zoom = 1.0; page.panX = 0; page.panY = 0; canvas.requestPaint() }
                }
            }

            // Empty state
            Text {
                visible: page.graphNodes.length === 0
                anchors.centerIn: parent
                text: qsTr("No objects yet. Create notes and link them with @ to grow your graph.")
                color: Theme.label
                font.family: Theme.body
                font.pixelSize: 14
            }
        }
    }
}
