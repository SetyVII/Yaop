import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "."

// Everything that shares a value (a topic, a category, a tag): the value in the centre, the objects
// that carry it around it, and on the outer ring the other values those objects also carry.
// Click an object to open it, click an outer value to move the centre there (Backspace goes back),
// "Save as query" keeps it as a saved set. Data: ws.optionNeighborhood (pkmcore/editor/optionview).
Popup {
    id: view

    property var appWindow: null
    property string typeId: ""
    property string propKey: ""
    property string value: ""
    property var data: ({})
    property var trail: []         // previous centres, for Backspace
    property int current: -1       // keyboard selection in the object list
    property int hoverObj: -1
    property int hoverRel: -1
    property real appear: 0        // 0 → 1 on every new centre

    readonly property var center: data.center || ({})
    readonly property var objects: data.objects || []
    readonly property var related: (data.related || []).slice(0, 14)

    function show(tid, prop, val) {
        trail = []
        load(tid, prop, val)
        open()
    }
    function load(tid, prop, val) {
        typeId = tid; propKey = prop; value = val
        data = ws ? ws.optionNeighborhood(tid, prop, val) : ({})
        current = objects.length > 0 ? 0 : -1
        hoverObj = -1; hoverRel = -1
        appear = 0
        appearAnim.restart()
        graph.requestPaint()
    }
    function recenter(r) {
        trail = trail.concat([{ t: typeId, p: propKey, v: value }])
        load(typeId, r.prop, r.id)
    }
    function back() {
        if (trail.length === 0) return
        const last = trail[trail.length - 1]
        trail = trail.slice(0, -1)
        load(last.t, last.p, last.v)
    }
    function openObj(id) {
        close()
        if (appWindow) appWindow.openObject(id)
    }
    function saveAsQuery() {
        if (!ws) return
        const q = { version: 1, filters: [{ prop: propKey, op: "has_any", value: [value] }] }
        if (typeId.length > 0) q.types = [typeId]
        const id = ws.createSet((center.label || value), q)
        close()
        if (appWindow && id.length > 0) {
            appWindow.navigate("sets")
            Qt.callLater(() => { const p = appWindow.setsPage(); if (p) p.currentSetId = id })
            appWindow.showToast(qsTr("Saved as a query."))
        }
    }
    function chipColor(c) { return c === "tag" ? Theme.tag : Theme.paletteColor(c || "blue") }
    // ids of objects linked to the hovered outer value (or the values of the hovered object)
    function relHas(r, objId) { return r && r.objectIds && r.objectIds.indexOf(objId) >= 0 }

    NumberAnimation {
        id: appearAnim
        target: view; property: "appear"
        from: 0; to: 1
        duration: Theme.slow
        easing.type: Easing.OutCubic
    }

    parent: Overlay.overlay
    x: (parent.width - width) / 2
    y: Math.max(24, (parent.height - height) / 2)
    width: Math.min(1040, parent.width - 64)
    height: Math.min(700, parent.height - 48)
    padding: 0
    modal: true
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
    Overlay.modal: Rectangle { color: Theme.scrim }

    enter: Transition {
        NumberAnimation { property: "opacity"; from: 0; to: 1; duration: Theme.fast }
        NumberAnimation { property: "scale"; from: 0.97; to: 1; duration: Theme.fast; easing.type: Easing.OutCubic }
    }
    exit: Transition {
        NumberAnimation { property: "opacity"; from: 1; to: 0; duration: Theme.fast }
    }

    background: Rectangle {
        radius: Theme.radiusLg
        color: Theme.sheet
        border.color: Theme.line2
        border.width: 1
    }

    onOpened: list.forceActiveFocus()

    contentItem: ColumnLayout {
        spacing: 0

        // header
        RowLayout {
            Layout.fillWidth: true
            Layout.margins: 18
            Layout.bottomMargin: 12
            spacing: 12

            IconButton {
                visible: view.trail.length > 0
                icon: "arrow-left"
                tip: qsTr("Back (Backspace)")
                onClicked: view.back()
            }
            Rectangle {
                width: 12; height: 12
                radius: Theme.noir ? 0 : 3
                color: view.chipColor(view.center.color)
            }
            ColumnLayout {
                spacing: 2
                Layout.fillWidth: true
                Text {
                    text: view.center.label || view.value
                    color: Theme.text
                    font.family: Theme.display
                    font.pixelSize: 24
                    font.weight: Font.DemiBold
                    elide: Text.ElideRight
                    Layout.fillWidth: true
                }
                Text {
                    text: {
                        const info = ws && view.typeId ? ws.typeInfo(view.typeId) : null
                        const type = info && info.name ? info.name : Theme.typeName(view.typeId)
                        return (view.center.propName || view.propKey) + " · " + type + " · "
                             + qsTr("%n object(s)", "", view.objects.length)
                    }
                    color: Theme.muted
                    font.family: Theme.mono
                    font.pixelSize: 11
                }
            }
            Button {
                text: qsTr("Save as query")
                small: true
                onClicked: view.saveAsQuery()
            }
            IconButton {
                icon: "x"
                tip: qsTr("Close (Esc)")
                onClicked: view.close()
            }
        }

        Rectangle { Layout.fillWidth: true; height: 1; color: Theme.line }

        RowLayout {
            Layout.fillWidth: true
            Layout.fillHeight: true
            spacing: 0

            // radial graph
            Item {
                id: graphBox
                Layout.fillWidth: true
                Layout.fillHeight: true
                clip: true

                readonly property real cx: width / 2
                readonly property real cy: height / 2
                // inner ellipse for objects, outer one for the values they share
                readonly property real rx1: Math.max(90, width * 0.21)
                readonly property real ry1: Math.max(70, height * 0.21)
                readonly property real rx2: Math.max(160, width * 0.40)
                readonly property real ry2: Math.max(120, height * 0.41)
                property var relAngles: []

                function objAngle(i) {
                    const n = Math.max(1, view.objects.length)
                    return -Math.PI / 2 + (i / n) * Math.PI * 2 + (n % 2 === 0 ? Math.PI / n : 0)
                }
                function objPos(i) {
                    const a = objAngle(i)
                    const k = 0.55 + 0.45 * view.appear
                    return { x: cx + Math.cos(a) * rx1 * k, y: cy + Math.sin(a) * ry1 * k, a: a }
                }
                function relPos(j) {
                    const a = relAngles.length > j ? relAngles[j] : 0
                    const k = 0.7 + 0.3 * view.appear
                    return { x: cx + Math.cos(a) * rx2 * k, y: cy + Math.sin(a) * ry2 * k }
                }
                // Outer values sit near the objects that carry them, then are pushed apart so no
                // two chips overlap (a few relaxation passes on the angles).
                function layoutRelated() {
                    const rel = view.related
                    const out = []
                    for (let j = 0; j < rel.length; j++) {
                        let sx = 0, sy = 0
                        for (let k = 0; k < view.objects.length; k++)
                            if (view.relHas(rel[j], view.objects[k].id)) {
                                const a = objAngle(k); sx += Math.cos(a); sy += Math.sin(a)
                            }
                        out.push({ j: j, a: (Math.abs(sx) < 1e-6 && Math.abs(sy) < 1e-6) ? (j / Math.max(1, rel.length)) * Math.PI * 2 : Math.atan2(sy, sx),
                                   w: ((rel[j].label || rel[j].id || "").length * 7 + 46) })
                    }
                    const pt = (a) => ({ x: Math.cos(a) * rx2, y: Math.sin(a) * ry2 })
                    for (let pass = 0; pass < 60; pass++) {
                        let moved = false
                        out.sort((p, q) => p.a - q.a)
                        for (let i = 0; i < out.length && out.length > 1; i++) {
                            const p = out[i], q = out[(i + 1) % out.length]
                            const A = pt(p.a), B = pt(q.a)
                            const needX = (p.w + q.w) / 2 + 10, needY = 32
                            if (Math.abs(A.x - B.x) < needX && Math.abs(A.y - B.y) < needY) {
                                p.a -= 0.035; q.a += 0.035; moved = true
                            }
                        }
                        if (!moved) break
                    }
                    const angles = []
                    for (let i = 0; i < out.length; i++) angles[out[i].j] = out[i].a
                    relAngles = angles
                }
                onRelAnglesChanged: graph.requestPaint()
                onWidthChanged: layoutRelated()
                onHeightChanged: layoutRelated()
                Connections { target: view; function onDataChanged() { graphBox.layoutRelated() } }

                Canvas {
                    id: graph
                    anchors.fill: parent
                    renderStrategy: Canvas.Cooperative
                    onPaint: {
                        const ctx = getContext("2d")
                        ctx.reset()
                        const lineC = String(Theme.line2)
                        const hiC = String(Theme.accent)
                        ctx.lineWidth = 1
                        // centre → objects
                        for (let i = 0; i < view.objects.length; i++) {
                            const p = graphBox.objPos(i)
                            const hi = view.hoverObj === i || view.current === i
                                       || (view.hoverRel >= 0 && view.relHas(view.related[view.hoverRel], view.objects[i].id))
                            ctx.strokeStyle = hi ? hiC : lineC
                            ctx.globalAlpha = (hi ? 0.9 : 0.55) * view.appear
                            ctx.beginPath(); ctx.moveTo(graphBox.cx, graphBox.cy); ctx.lineTo(p.x, p.y); ctx.stroke()
                        }
                        // objects → outer values
                        for (let j = 0; j < view.related.length; j++) {
                            const q = graphBox.relPos(j)
                            for (let i = 0; i < view.objects.length; i++) {
                                if (!view.relHas(view.related[j], view.objects[i].id)) continue
                                const p = graphBox.objPos(i)
                                const hi = view.hoverRel === j || view.hoverObj === i || view.current === i
                                ctx.strokeStyle = hi ? hiC : lineC
                                ctx.globalAlpha = (hi ? 0.75 : 0.22) * view.appear
                                ctx.beginPath(); ctx.moveTo(p.x, p.y); ctx.lineTo(q.x, q.y); ctx.stroke()
                            }
                        }
                    }
                    Connections {
                        target: view
                        function onHoverObjChanged() { graph.requestPaint() }
                        function onHoverRelChanged() { graph.requestPaint() }
                        function onCurrentChanged() { graph.requestPaint() }
                        function onAppearChanged() { graph.requestPaint() }
                    }
                    onWidthChanged: requestPaint()
                    onHeightChanged: requestPaint()
                }

                // centre
                Rectangle {
                    x: graphBox.cx - width / 2
                    y: graphBox.cy - height / 2
                    width: centreTxt.implicitWidth + 28
                    height: 34
                    radius: Theme.noir ? 0 : 17
                    color: Theme.card
                    border.width: 1.5
                    border.color: view.chipColor(view.center.color)
                    Text {
                        id: centreTxt
                        anchors.centerIn: parent
                        text: view.center.label || view.value
                        color: Theme.text
                        font.family: Theme.body
                        font.pixelSize: 14
                        font.weight: Font.DemiBold
                    }
                }

                // objects
                Repeater {
                    model: view.objects
                    delegate: Item {
                        id: on
                        required property var modelData
                        required property int index
                        readonly property var pos: { view.appear; return graphBox.objPos(index) }
                        readonly property bool hi: view.hoverObj === index || view.current === index
                        x: pos.x - 6; y: pos.y - 6
                        width: 12; height: 12
                        opacity: Math.min(1, view.appear * 1.4)
                        z: hi ? 5 : 2

                        Rectangle {
                            anchors.centerIn: parent
                            width: on.hi ? 14 : 10; height: width
                            radius: width / 2
                            color: Theme.typeColor(on.modelData.typeId)
                            border.width: on.hi ? 2 : 0
                            border.color: Theme.text
                            Behavior on width { NumberAnimation { duration: Theme.fast; easing.type: Easing.OutCubic } }
                        }
                        Text {
                            anchors.horizontalCenter: parent.horizontalCenter
                            y: 16
                            width: Math.min(implicitWidth, 150)
                            text: on.modelData.title || qsTr("Untitled")
                            elide: Text.ElideRight
                            horizontalAlignment: Text.AlignHCenter
                            color: on.hi ? Theme.text : Theme.dim
                            font.family: Theme.body
                            font.pixelSize: 12
                            visible: on.hi || view.objects.length <= 16
                        }
                        HoverHandler {
                            margin: 6
                            cursorShape: Qt.PointingHandCursor
                            onHoveredChanged: view.hoverObj = hovered ? on.index : (view.hoverObj === on.index ? -1 : view.hoverObj)
                        }
                        TapHandler { margin: 6; onTapped: view.openObj(on.modelData.id) }
                    }
                }

                // outer values
                Repeater {
                    model: view.related
                    delegate: Rectangle {
                        id: rn
                        required property var modelData
                        required property int index
                        readonly property var pos: { view.appear; return graphBox.relPos(index) }
                        readonly property bool hi: view.hoverRel === index
                                                   || (view.hoverObj >= 0 && view.relHas(modelData, view.objects[view.hoverObj].id))
                        x: pos.x - width / 2; y: pos.y - height / 2
                        width: relRow.implicitWidth + 16; height: 24
                        radius: Theme.noir ? 0 : 12
                        opacity: Math.min(1, view.appear * 1.2) * (view.hoverObj >= 0 && !hi ? 0.45 : 1)
                        color: hi ? Theme.panel2 : Theme.card
                        border.width: 1
                        border.color: hi ? view.chipColor(modelData.color) : Theme.line2
                        z: hi ? 6 : 3
                        Behavior on color { ColorAnimation { duration: Theme.fast } }
                        Behavior on opacity { NumberAnimation { duration: Theme.fast } }
                        Row {
                            id: relRow
                            anchors.centerIn: parent
                            spacing: 6
                            Rectangle {
                                anchors.verticalCenter: parent.verticalCenter
                                width: 7; height: 7
                                color: view.chipColor(rn.modelData.color)
                            }
                            Text {
                                anchors.verticalCenter: parent.verticalCenter
                                text: (rn.modelData.prop === "tags" ? "#" : "") + (rn.modelData.label || rn.modelData.id)
                                color: rn.hi ? Theme.text : Theme.bodyText
                                font.family: Theme.body
                                font.pixelSize: 12
                            }
                            Text {
                                anchors.verticalCenter: parent.verticalCenter
                                text: rn.modelData.objectIds.length
                                color: Theme.label
                                font.family: Theme.mono
                                font.pixelSize: 10
                            }
                        }
                        HoverHandler {
                            cursorShape: Qt.PointingHandCursor
                            onHoveredChanged: view.hoverRel = hovered ? rn.index : (view.hoverRel === rn.index ? -1 : view.hoverRel)
                        }
                        TapHandler { onTapped: view.recenter(rn.modelData) }
                        ToolTip.visible: view.hoverRel === index
                        ToolTip.delay: 600
                        ToolTip.text: qsTr("%1 · show everything with it").arg(modelData.propName)
                    }
                }

                Text {
                    visible: view.objects.length === 0
                    anchors.centerIn: parent
                    anchors.verticalCenterOffset: 44
                    text: qsTr("Nothing carries this value yet.")
                    color: Theme.label
                    font.family: Theme.body
                    font.pixelSize: 13
                }
            }

            Rectangle { Layout.fillHeight: true; width: 1; color: Theme.line }

            // object list (keyboard: ↑/↓, Enter opens, Backspace goes back)
            ListView {
                id: list
                Layout.preferredWidth: 300
                Layout.fillHeight: true
                clip: true
                model: view.objects
                currentIndex: view.current
                focus: true
                keyNavigationEnabled: false
                boundsBehavior: Flickable.StopAtBounds
                header: Text {
                    width: list.width
                    leftPadding: 16; topPadding: 14; bottomPadding: 8
                    text: Theme.caps(qsTr("Objects"))
                    color: Theme.label
                    font.family: Theme.mono
                    font.pixelSize: 11
                    font.letterSpacing: Theme.track(1.2)
                }
                Keys.onUpPressed: view.current = Math.max(0, view.current - 1)
                Keys.onDownPressed: view.current = Math.min(view.objects.length - 1, view.current + 1)
                Keys.onReturnPressed: if (view.current >= 0) view.openObj(view.objects[view.current].id)
                Keys.onEnterPressed: if (view.current >= 0) view.openObj(view.objects[view.current].id)
                Keys.onPressed: (event) => {
                    if (event.key === Qt.Key_Backspace || (event.key === Qt.Key_Left && (event.modifiers & Qt.AltModifier))) {
                        view.back(); event.accepted = true
                    }
                }
                delegate: Rectangle {
                    id: row
                    required property var modelData
                    required property int index
                    width: list.width
                    height: 36
                    color: view.current === index ? Theme.panel2 : (rowHov.hovered ? Theme.cardHover : "transparent")
                    Behavior on color { ColorAnimation { duration: Theme.fast } }
                    Rectangle {
                        visible: view.current === row.index
                        width: 2; height: parent.height
                        color: Theme.accent
                    }
                    Row {
                        anchors.verticalCenter: parent.verticalCenter
                        x: 16
                        width: parent.width - 32
                        spacing: 10
                        TypeBadge { anchors.verticalCenter: parent.verticalCenter; typeId: row.modelData.typeId; size: 14 }
                        Text {
                            anchors.verticalCenter: parent.verticalCenter
                            width: parent.width - 24
                            text: row.modelData.title || qsTr("Untitled")
                            elide: Text.ElideRight
                            color: Theme.bodyText
                            font.family: Theme.body
                            font.pixelSize: 13
                        }
                    }
                    HoverHandler {
                        id: rowHov
                        cursorShape: Qt.PointingHandCursor
                        onHoveredChanged: view.hoverObj = hovered ? row.index : (view.hoverObj === row.index ? -1 : view.hoverObj)
                    }
                    TapHandler { onTapped: view.openObj(row.modelData.id) }
                }
            }
        }

        Rectangle { Layout.fillWidth: true; height: 1; color: Theme.line }
        Text {
            Layout.fillWidth: true
            Layout.margins: 10
            Layout.leftMargin: 18
            text: qsTr("↑↓ select · Enter open · click a value on the outer ring to follow it · Backspace back · Esc close")
            color: Theme.label
            font.family: Theme.mono
            font.pixelSize: 11
            elide: Text.ElideRight
        }
    }
}
