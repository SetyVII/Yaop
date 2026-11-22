import QtQuick
import "../"
import QtQuick.Controls
import QtQuick.Layouts

// Per-block chrome: indent, group frame, block color surface, hover handle.
// Dispatches the body to the right sub-view by wire type.

Rectangle {
    id: blockRoot

    required property var view
    required property int index
    required property string blockId
    required property string blockType
    required property string plainText
    required property int indentLevel
    required property string blockColorName
    required property string blockColorHex
    required property bool firstInGroup
    required property bool lastInGroup
    required property bool isGrouped
    required property bool selected
    required property int hiddenChildren
    required property int headingLevel
    required property string numberLabel
    required property bool checked
    required property bool expanded
    required property string calloutType
    required property string calloutEmoji
    required property string richTextHtml
    required property string language
    required property string codeText
    required property string latex
    required property var rows
    required property var align
    required property string assetId
    required property string assetPath
    required property string assetKind
    required property string widthPercent
    required property real durationSeconds
    required property int pageCount
    required property int lastReadPage
    required property string fileName
    required property string url
    required property string bookmarkTitle
    required property string bookmarkDesc
    required property string domain
    required property string thumbnailUrl
    required property string faviconUrl
    required property string embedTarget
    required property string viewMode

    property bool hasBodyFocus: view && view.activeFocusedBlockId === blockId

    // Keyboard (block mode, docs/keyboard.md §4.5): Enter / Space run the body's main action.
    // Returns true when the body took the caret (code, table), so the block selection ends.
    function activateBody(viaSpace) {
        const b = bodyLoader.item
        return !!b && typeof b.activate === "function" && b.activate(viaSpace) === true
    }
    function openActions() {
        const overlay = Overlay.overlay
        if (overlay) {
            const margin = 12
            const gap = 4
            const estH = Math.min(460, Math.max(240, blockActions.implicitHeight > 0 ? blockActions.implicitHeight : 420))
            const topPt = blockRoot.mapToItem(overlay, Math.max(0, blockRoot.width - blockActions.width), 0)
            const botPt = blockRoot.mapToItem(overlay, Math.max(0, blockRoot.width - blockActions.width), 26)
            const spaceAbove = Math.max(0, topPt.y - margin - gap)
            const spaceBelow = Math.max(0, overlay.height - botPt.y - margin - gap)
            const openAbove = spaceBelow < estH && spaceAbove >= spaceBelow
            blockActions.x = Math.max(margin, Math.min(topPt.x, overlay.width - blockActions.width - margin))
            blockActions.y = openAbove
                ? Math.max(margin, topPt.y - estH - gap)
                : Math.min(overlay.height - estH - margin, botPt.y + gap)
        }
        blockActions.open()
    }

    readonly property real focusDim: (view && view.focusMode && view.activeFocusedBlockId.length > 0 && !hasBodyFocus) ? 0.35 : 1.0
    opacity: (view && view.dragging && view.dragFromRow === index ? 0.35 : 1) * appearT * focusDim
    Behavior on opacity { enabled: !appearAnim.running; NumberAnimation { duration: Theme.fast } }

    // Motion after a structural edit (BlocksView.playMotion): a block that moved slides from
    // where it was (200 ms), a new or converted block fades in 4 px up (140 ms).
    property real appearT: 1
    transform: Translate { id: motionShift }
    function slideFrom(dy) {
        motionShift.y = dy
        slideAnim.restart()
    }
    function appear() {
        appearT = 0
        motionShift.y = 4
        appearAnim.restart()
    }
    NumberAnimation {
        id: slideAnim
        target: motionShift; property: "y"; to: 0
        duration: Theme.normal; easing.type: Easing.Bezier; easing.bezierCurve: Theme.easeStandard
    }
    ParallelAnimation {
        id: appearAnim
        NumberAnimation { target: blockRoot; property: "appearT"; to: 1; duration: Theme.fast }
        NumberAnimation { target: motionShift; property: "y"; to: 0; duration: Theme.fast; easing.type: Easing.Bezier; easing.bezierCurve: Theme.easeEnter }
    }

    // Callout: box with the colour at 14 % and a 25 % border (spec §6). info = blue, warning = orange.
    readonly property string calloutColorName: calloutType === "WARNING" ? "orange"
        : calloutType === "IDEA" ? "yellow" : calloutType === "DANGER" ? "red" : "blue"
    property var calloutAccent: Theme.paletteColor(calloutColorName)

    radius: blockType === "callout" ? Theme.radius : ((selected || blockColorHex.length > 0) ? Theme.radiusSm : 0)
    // While the block-mode ring is drawn (BlocksView.selectionRing) it is the outline.
    border.width: selected && !(view && view.ringShown) ? 1 : (blockType === "callout" ? 1 : 0)
    border.color: selected ? Qt.rgba(0.75, 0.89, 1, 0.45) : Theme.paletteHighlight[calloutColorName]

    color: {
        if (selected)
            return Qt.rgba(0.75, 0.89, 1, 0.12)
        if (blockType === "callout")
            return Theme.paletteTint[calloutColorName]
        if (blockColorHex.length > 0)
            return Theme.paletteSurface(blockColorName)
        return hasBodyFocus ? Qt.rgba(1, 1, 1, 0.04) : "transparent"
    }
    Behavior on color { ColorAnimation { duration: Theme.fast } }

    implicitHeight: bodyLoader.implicitHeight + 20
    height: implicitHeight

    // Group frame: 1px box spanning the whole contiguous group run
    Rectangle {
        visible: blockRoot.isGrouped
        anchors.fill: parent
        radius: Theme.radius
        color: Qt.rgba(1, 1, 1, 0.03)
        border.width: 1
        border.color: Theme.line
        Rectangle { // crop top border when continuing a group
            visible: blockRoot.isGrouped && !blockRoot.firstInGroup
            width: parent.width
            height: 2
            y: -1
            color: blockRoot.color
        }
        Rectangle { // crop bottom border
            visible: blockRoot.isGrouped && !blockRoot.lastInGroup
            width: parent.width
            height: 2
            y: parent.height - 1
            color: blockRoot.color
        }
    }

    RowLayout {
        anchors.fill: parent
        anchors.leftMargin: 2
        anchors.rightMargin: 2
        spacing: 0

        Item { Layout.preferredWidth: 24 * indentLevel; Layout.fillHeight: true }

        // Quote: 1px #4A4A4A bar in noir (BlockTypes.dc.html), 3px ice bar in slate/sky
        Item {
            Layout.preferredWidth: blockType === "quote" ? (Theme.noir ? 16 : 11) : (blockType === "callout" ? 10 : 0)
            Layout.fillHeight: true

            Rectangle {
                visible: blockType === "quote"
                width: Theme.noir ? 1 : 3
                radius: Theme.noir ? 0 : 1.5
                height: parent.height - 8
                y: 4
                color: Theme.noir ? Theme.line3 : Theme.ice
            }
        }

        // List / todo / toggle markers
        Item {
            Layout.preferredWidth: markerRow.implicitWidth > 0 ? markerRow.implicitWidth + 8 : 0
            Layout.fillHeight: true

            RowLayout {
                id: markerRow
                anchors.left: parent.left
                anchors.top: parent.top
                // Block has 10px top/bottom padding around bodyLoader + 2px TextEdit.topPadding = 12px to line 1 top.
                anchors.topMargin: 12
                height: 21
                spacing: 4

                // Bullet: sharp 5x5 square in noir (filled lvl 0, hollow lvl 1, 7x1 dash lvl 2 — BlockTypes.dc.html)
                Item {
                    Layout.alignment: Qt.AlignVCenter
                    visible: blockType === "bullet_list"
                    implicitWidth: 14; implicitHeight: 14
                    Rectangle {
                        visible: Theme.noir
                        anchors.centerIn: parent
                        width: indentLevel >= 2 ? 7 : 5
                        height: indentLevel >= 2 ? 1 : 5
                        color: indentLevel === 1 ? "transparent" : Theme.muted
                        border.width: indentLevel === 1 ? 1 : 0
                        border.color: Theme.muted
                    }
                    Text {
                        visible: !Theme.noir
                        anchors.centerIn: parent
                        text: "•"
                        color: Theme.muted
                        font.family: Theme.body
                        font.pixelSize: 15
                    }
                }
                Text {
                    Layout.alignment: Qt.AlignVCenter
                    visible: blockType === "numbered_list" || blockType === "alphabetic_list"
                             || blockType === "roman_list"
                    text: numberLabel
                    color: Theme.muted
                    font.family: Theme.mono
                    font.pixelSize: 13
                }
                // Todo checkbox: sharp 17x17 square with 300ms scale(0->1) inner accent fill in noir (BlockTypes.dc.html)
                Rectangle {
                    Layout.alignment: Qt.AlignVCenter
                    visible: blockType === "todo"
                    width: Theme.noir ? 17 : 18
                    height: Theme.noir ? 17 : 18
                    radius: Theme.noir ? 0 : 9
                    color: Theme.noir ? "transparent" : (checked ? Theme.accent : "transparent")
                    border.width: Theme.noir ? 1 : 1.5
                    border.color: checked ? Theme.accent : (Theme.noir ? Theme.faint : Theme.dim)
                    Behavior on border.color { ColorAnimation { duration: 200 * Theme.motion } }
                    Rectangle {
                        visible: Theme.noir
                        anchors.fill: parent
                        anchors.margins: 3
                        color: Theme.accent
                        scale: checked ? 1 : 0
                        opacity: checked ? 1 : 0
                        Behavior on scale {
                            NumberAnimation {
                                duration: 300 * Theme.motion
                                easing.type: Easing.Bezier
                                easing.bezierCurve: [0.2, 0.8, 0.1, 1, 1, 1]
                            }
                        }
                        Behavior on opacity { NumberAnimation { duration: 200 * Theme.motion } }
                    }
                    Text {
                        visible: !Theme.noir && checked
                        anchors.centerIn: parent
                        text: "✓"
                        color: Theme.accentInk
                        font.pixelSize: 11
                        font.weight: Font.Bold
                    }
                    HoverHandler { cursorShape: Qt.PointingHandCursor }
                    TapHandler { onTapped: view.session.toggleTodo(blockRoot.index) }
                }
                // Toggle arrow
                Text {
                    Layout.alignment: Qt.AlignVCenter
                    visible: blockType === "toggle"
                    text: Theme.noir ? "▶" : "›"
                    rotation: expanded ? 90 : 0
                    Behavior on rotation { NumberAnimation { duration: Theme.fast } }
                    color: Theme.dim
                    font.family: Theme.body
                    font.pixelSize: Theme.noir ? 11 : 20
                    TapHandler { onTapped: view.session.toggleExpand(blockRoot.index) }
                }
                TintedIcon {
                    Layout.alignment: Qt.AlignVCenter
                    visible: blockType === "callout" && Theme.noir
                    name: calloutType === "IDEA" ? "bulb" : "callout"
                    size: 16
                    color: blockRoot.calloutAccent
                }
                Text {
                    Layout.alignment: Qt.AlignVCenter
                    visible: blockType === "callout" && !Theme.noir
                    text: calloutEmoji
                    font.family: Theme.body
                    font.pixelSize: 14
                }
                Text {
                    visible: blockType === "toggle" && hiddenChildren > 0 && !expanded
                    text: qsTr("(%1 hidden)").arg(hiddenChildren)
                    color: Theme.faint
                    font.family: Theme.mono
                    font.pixelSize: 11
                }
            }
        }

        Loader {
            id: bodyLoader
            Layout.fillWidth: true
            Layout.fillHeight: false
            sourceComponent: {
                switch (blockType) {
                case "code": return codeComp
                case "math": return mathComp
                case "table": return tableComp
                case "image": case "audio": case "pdf": return mediaComp
                case "embed": return embedComp
                case "bookmark": return bookmarkComp
                case "canvas": return canvasComp
                case "divider": return dividerComp
                default: return textComp
                }
            }
        }

        // Block reference count badge (spec/text.md — shown when other objects link to this block)
        Rectangle {
            id: refBadge
            readonly property int refCount: (view && view.blockRefCounts && view.blockRefCounts[blockId])
                ? view.blockRefCounts[blockId] : 0
            visible: refCount > 0
            Layout.alignment: Qt.AlignVCenter
            Layout.rightMargin: 4
            implicitWidth: refLabel.implicitWidth + 10
            implicitHeight: 18
            radius: Theme.radiusSm
            color: Theme.noir ? Theme.panel : Theme.panel2
            border.width: 1
            border.color: Theme.line2
            Text {
                id: refLabel
                anchors.centerIn: parent
                text: refBadge.refCount
                color: Theme.muted
                font.family: Theme.mono
                font.pixelSize: 10
            }
            ToolTip.visible: refHover.hovered
            ToolTip.text: qsTr("%1 reference(s) to this block").arg(refBadge.refCount)
            HoverHandler { id: refHover }
        }

        // Block handle (contextual actions)
        Item {
            Layout.preferredWidth: 26
            Layout.fillHeight: true

            Rectangle {
                width: 18
                height: 18
                anchors.centerIn: parent
                radius: Theme.radiusSm
                color: handleTap.pressed ? Theme.panel3 : handleArea.hovered ? Theme.panel2 : "transparent"
                opacity: handleArea.hovered || handleTap.pressed ? 1 : 0

                Text {
                    anchors.centerIn: parent
                    text: "⋮⋮"
                    color: Theme.muted
                    font.family: Theme.mono
                    font.pixelSize: 11
                }

                HoverHandler { id: handleArea; cursorShape: Qt.OpenHandCursor }
                TapHandler {
                    id: handleTap
                    onTapped: function(eventPoint, button) {
                        const mods = handleTap.point.modifiers
                        if (mods & Qt.ShiftModifier) {
                            const anchor = view.selectionAnchorRow >= 0 ? view.selectionAnchorRow
                                         : (view.activeRow >= 0 ? view.activeRow : blockRoot.index)
                            view.beginBlockSelection(anchor, blockRoot.index)
                            return
                        }
                        if (mods & (Qt.ControlModifier | Qt.MetaModifier)) {
                            view.toggleBlockSelection(blockRoot.index, blockRoot.blockId)
                            return
                        }
                        view.session.clearSelection()
                        blockRoot.openActions()
                    }
                }
                DragHandler {
                    id: blockDrag
                    acceptedButtons: Qt.LeftButton
                    onActiveChanged: {
                        if (active)
                            view.beginDrag(blockRoot.index)
                        else
                            view.endDrag(true)
                    }
                    onCentroidChanged: view.updateDrag(blockDrag.centroid.scenePosition)
                }
                Behavior on opacity { NumberAnimation { duration: Theme.fast } }
            }
        }
    }

    // ------------------------- components -------------------------
    Component {
        id: dividerComp
        Item {
            implicitHeight: 22
            Rectangle {
                anchors.verticalCenter: parent.verticalCenter
                anchors.left: parent.left
                anchors.right: parent.right
                height: 1
                color: Theme.line3
            }
        }
    }

    Component {
        id: textComp
        TextBlockView {
            view: blockRoot.view
            row: blockRoot.index
            blockType: blockRoot.blockType
            blockId: blockRoot.blockId
            headingLevel: blockRoot.headingLevel
            checked: blockRoot.checked
            content: blockRoot.richTextHtml
        }
    }
    Component {
        id: codeComp
        CodeBlockView {
            view: blockRoot.view
            row: blockRoot.index
            blockId: blockRoot.blockId
            language: blockRoot.language
            codeText: blockRoot.codeText
        }
    }
    Component {
        id: mathComp
        MathBlockView {
            view: blockRoot.view
            row: blockRoot.index
            blockId: blockRoot.blockId
            latexText: blockRoot.latex
        }
    }
    Component {
        id: tableComp
        TableBlockView {
            view: blockRoot.view
            row: blockRoot.index
            blockId: blockRoot.blockId
            rows: blockRoot.rows
            align: blockRoot.align
        }
    }
    Component {
        id: mediaComp
        MediaBlockView {
            view: blockRoot.view
            row: blockRoot.index
            blockType: blockRoot.blockType
            blockId: blockRoot.blockId
            assetId: blockRoot.assetId
            assetPath: blockRoot.assetPath
            widthPercent: parseFloat(blockRoot.widthPercent)
            durationSeconds: blockRoot.durationSeconds
            pageCount: blockRoot.pageCount
            lastReadPage: blockRoot.lastReadPage
            fileName: blockRoot.fileName
            captionHtml: blockRoot.richTextHtml
        }
    }
    Component {
        id: embedComp
        EmbedBlockView {
            view: blockRoot.view
            row: blockRoot.index
            targetObjectId: blockRoot.embedTarget
            viewMode: blockRoot.viewMode
        }
    }
    Component {
        id: bookmarkComp
        BookmarkBlockView {
            view: blockRoot.view
            row: blockRoot.index
            objectId: blockRoot.embedTarget
            url: blockRoot.url
            title: blockRoot.bookmarkTitle
            description: blockRoot.bookmarkDesc
            domain: blockRoot.domain
            thumbnailUrl: blockRoot.thumbnailUrl
            faviconUrl: blockRoot.faviconUrl
        }
    }
    Component {
        id: canvasComp
        CanvasBlockView {
            view: blockRoot.view
            row: blockRoot.index
            blockId: blockRoot.blockId
        }
    }

    // Rich Block Actions Popup (docs/design/mockup/BlockActions.dc.html):
    // Turn-into 8-button grid, 11 color swatches, Duplicate, Copy, Copy block reference,
    // Move up/down, Indent/Outdent, Move to another object…, Convert to new object…, Delete.
    Popup {
        id: blockActions
        parent: Overlay.overlay
        width: 340
        padding: 0
        closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside

        property bool pickingMoveTarget: false
        property string moveQuery: ""
        onOpened: { pickingMoveTarget = false; moveQuery = "" }

        background: Rectangle {
            color: Theme.sheet
            border.color: Theme.line2
            border.width: 1
            radius: Theme.radiusLg
        }

        component ActRow: Rectangle {
            id: ar
            property string icon: "shape"
            property string label: ""
            property string shortcut: ""
            property bool danger: false
            signal triggered()
            Layout.fillWidth: true
            implicitHeight: 34
            color: arHov.hovered ? Theme.accent : "transparent"
            RowLayout {
                anchors.fill: parent; anchors.leftMargin: 14; anchors.rightMargin: 14; spacing: 10
                TintedIcon { name: ar.icon; size: 15; color: arHov.hovered ? Theme.accentInk : (ar.danger ? Theme.danger : Theme.dim) }
                Text { Layout.fillWidth: true; text: ar.label; color: arHov.hovered ? Theme.accentInk : (ar.danger ? Theme.danger : Theme.bodyText); font.family: Theme.body; font.pixelSize: 13 }
                Text { visible: ar.shortcut.length > 0; text: Theme.caps(ar.shortcut); color: arHov.hovered ? Theme.accentInk : Theme.label; font.family: Theme.mono; font.pixelSize: 10 }
            }
            HoverHandler { id: arHov; cursorShape: Qt.PointingHandCursor }
            TapHandler { onTapped: ar.triggered() }
        }

        contentItem: ColumnLayout {
            spacing: 0

            // Header
            Item {
                Layout.fillWidth: true; Layout.preferredHeight: 36
                Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.line }
                RowLayout {
                    anchors.fill: parent; anchors.leftMargin: 14; anchors.rightMargin: 14
                    Text { text: blockActions.pickingMoveTarget ? qsTr("Move block to object") : qsTr("Block actions"); color: Theme.text; font.family: Theme.body; font.pixelSize: 13 }
                    Item { Layout.fillWidth: true }
                    Text { text: Theme.caps("CTRL ."); color: Theme.label; font.family: Theme.mono; font.pixelSize: 10 }
                }
            }

            // Move-to-object target picker (workspace.moveBlocksToObject)
            ColumnLayout {
                visible: blockActions.pickingMoveTarget
                Layout.fillWidth: true
                Layout.margins: 10
                spacing: 6

                Rectangle {
                    Layout.fillWidth: true; Layout.preferredHeight: 34
                    color: "transparent"; border.width: 1; border.color: Theme.line2
                    TextInput {
                        id: moveSearch
                        anchors.fill: parent; anchors.leftMargin: 10; anchors.rightMargin: 10
                        verticalAlignment: TextInput.AlignVCenter
                        color: Theme.text; font.family: Theme.body; font.pixelSize: 13
                        text: blockActions.moveQuery
                        onTextEdited: blockActions.moveQuery = text
                        Text { anchors.verticalCenter: parent.verticalCenter; visible: moveSearch.length === 0; text: qsTr("Search target object…"); color: Theme.faint; font: moveSearch.font }
                    }
                }
                Repeater {
                    model: {
                        if (!blockActions.pickingMoveTarget || typeof ws === "undefined" || !ws) return []
                        const list = blockActions.moveQuery.trim().length > 0
                                   ? ws.searchObjects(blockActions.moveQuery.trim(), 6)
                                   : ws.recentObjects(6)
                        return list.filter(o => !view.session || o.id !== view.session.objectId)
                    }
                    delegate: Rectangle {
                        required property var modelData
                        Layout.fillWidth: true; implicitHeight: 34
                        color: mtHov.hovered ? Theme.accent : "transparent"
                        RowLayout {
                            anchors.fill: parent; anchors.leftMargin: 10; anchors.rightMargin: 10; spacing: 8
                            TypeBadge { typeId: modelData.typeId || ""; size: 14 }
                            Text { Layout.fillWidth: true; text: modelData.title || qsTr("Untitled"); color: mtHov.hovered ? Theme.accentInk : Theme.bodyText; font.family: Theme.body; font.pixelSize: 13; elide: Text.ElideRight }
                        }
                        HoverHandler { id: mtHov; cursorShape: Qt.PointingHandCursor }
                        TapHandler {
                            onTapped: {
                                if (ws && view.session) {
                                    const n = ws.moveBlocksToObject(view.session.objectId, [blockRoot.blockId], modelData.id)
                                    if (n > 0) view.toastRequested(qsTr("Moved to “%1”").arg(modelData.title || qsTr("object")))
                                }
                                blockActions.close()
                            }
                        }
                    }
                }
                Button { small: true; text: qsTr("← Back"); onClicked: blockActions.pickingMoveTarget = false }
            }

            // Standard Block Actions view
            ColumnLayout {
                visible: !blockActions.pickingMoveTarget
                Layout.fillWidth: true
                spacing: 0

                // Turn into grid (BlockActions.dc.html)
                Text {
                    Layout.leftMargin: 14; Layout.topMargin: 10; Layout.bottomMargin: 6
                    text: Theme.caps(qsTr("TURN INTO · /"))
                    color: Theme.label; font.family: Theme.mono; font.pixelSize: 10
                }
                Flow {
                    Layout.fillWidth: true; Layout.leftMargin: 14; Layout.rightMargin: 14
                    spacing: 4
                    Repeater {
                        model: [
                            { k: "paragraph", l: qsTr("Text"), ic: "text" },
                            { k: "h1", l: qsTr("H1"), ic: "h-1" },
                            { k: "h2", l: qsTr("H2"), ic: "h-2" },
                            { k: "bullet", l: qsTr("List"), ic: "list" },
                            { k: "todo", l: qsTr("Todo"), ic: "task" },
                            { k: "quote", l: qsTr("Quote"), ic: "quote" },
                            { k: "callout", l: qsTr("Callout"), ic: "callout" },
                            { k: "code", l: qsTr("Code"), ic: "code" }
                        ]
                        delegate: Rectangle {
                            required property var modelData
                            readonly property bool cur: blockRoot.blockType === modelData.k
                                                     || (modelData.k === "h1" && blockRoot.blockType === "heading" && blockRoot.headingLevel === 1)
                                                     || (modelData.k === "h2" && blockRoot.blockType === "heading" && blockRoot.headingLevel === 2)
                                                     || (modelData.k === "bullet" && blockRoot.blockType === "bullet_list")
                            implicitWidth: tyR.implicitWidth + 14; height: 28
                            color: cur ? Theme.accent : tyHov.hovered ? Theme.panel2 : "transparent"
                            border.width: 1; border.color: cur ? Theme.accent : tyHov.hovered ? Theme.line3 : Theme.line
                            Row {
                                id: tyR; anchors.centerIn: parent; spacing: 5
                                TintedIcon { anchors.verticalCenter: parent.verticalCenter; name: modelData.ic; size: 13; color: cur ? Theme.accentInk : Theme.dim }
                                Text { anchors.verticalCenter: parent.verticalCenter; text: modelData.l; color: cur ? Theme.accentInk : Theme.dim; font.family: Theme.body; font.pixelSize: 12 }
                            }
                            HoverHandler { id: tyHov; cursorShape: Qt.PointingHandCursor }
                            TapHandler {
                                onTapped: {
                                    view.session.convertBlock(blockRoot.index, modelData.k)
                                    blockActions.close()
                                }
                            }
                        }
                    }
                }

                // Block color 11-swatch strip
                Text {
                    Layout.leftMargin: 14; Layout.topMargin: 12; Layout.bottomMargin: 6
                    text: Theme.caps(qsTr("BLOCK COLOR"))
                    color: Theme.label; font.family: Theme.mono; font.pixelSize: 10
                }
                Row {
                    Layout.leftMargin: 14; Layout.bottomMargin: 12
                    spacing: 3
                    Repeater {
                        model: [""].concat(Theme.paletteNames)
                        delegate: Rectangle {
                            required property string modelData
                            readonly property bool cur: blockRoot.blockColorName === modelData
                            width: 24; height: 24; color: "transparent"
                            border.width: 1; border.color: cur ? Theme.text : "transparent"
                            Rectangle {
                                anchors.fill: parent; anchors.margins: 3
                                color: modelData.length > 0 ? Theme.paletteColor(modelData) : "transparent"
                                border.width: modelData.length === 0 ? 1 : 0; border.color: Theme.faint
                                Text { visible: modelData.length === 0; anchors.centerIn: parent; text: "/"; color: Theme.faint; font.pixelSize: 10 }
                            }
                            HoverHandler { cursorShape: Qt.PointingHandCursor }
                            TapHandler {
                                onTapped: {
                                    view.session.setBlockColor(blockRoot.index, modelData)
                                    blockActions.close()
                                }
                            }
                        }
                    }
                }

                Rectangle { Layout.fillWidth: true; height: 1; color: Theme.line }

                Item { Layout.preferredHeight: 4 }
                ActRow {
                    icon: "copy"; label: qsTr("Duplicate"); shortcut: "Ctrl D"
                    onTriggered: { view.session.duplicateBlocks([blockRoot.blockId]); blockActions.close() }
                }
                ActRow {
                    icon: "copy"; label: qsTr("Copy"); shortcut: "Ctrl C"
                    onTriggered: {
                        if (typeof ws !== "undefined" && ws) ws.copyTextToClipboard(blockRoot.plainText)
                        view.toastRequested(qsTr("Block copied"))
                        blockActions.close()
                    }
                }
                ActRow {
                    icon: "web"; label: qsTr("Copy block reference")
                    onTriggered: {
                        if (typeof ws !== "undefined" && ws && view.session) {
                            ws.copyBlockReference(view.session.objectId, blockRoot.blockId)
                            view.toastRequested(qsTr("Block reference copied"))
                        }
                        blockActions.close()
                    }
                }
                ActRow {
                    icon: "arrow-up"; label: qsTr("Move up"); shortcut: "Ctrl Shift ↑"
                    onTriggered: { view.session.moveBlocks([blockRoot.blockId], -1); blockActions.close() }
                }
                ActRow {
                    icon: "arrow-down"; label: qsTr("Move down"); shortcut: "Ctrl Shift ↓"
                    onTriggered: { view.session.moveBlocks([blockRoot.blockId], 1); blockActions.close() }
                }
                ActRow {
                    icon: "arrow-forward"; label: blockRoot.indentLevel > 0 ? qsTr("Outdent") : qsTr("Indent"); shortcut: "Tab"
                    onTriggered: { view.session.indentBlocks(blockRoot.index, blockRoot.indentLevel > 0 ? -1 : 1); blockActions.close() }
                }
                ActRow {
                    icon: "arrow-forward"; label: qsTr("Move to another object…")
                    onTriggered: { blockActions.pickingMoveTarget = true; moveSearch.forceActiveFocus() }
                }
                ActRow {
                    icon: "shape"; label: qsTr("Turn into new object")
                    onTriggered: {
                        if (typeof ws !== "undefined" && ws && view.session) {
                            const title = blockRoot.plainText.trim().substring(0, 80) || qsTr("Untitled")
                            const newId = ws.createObject("note", title)
                            if (newId.length > 0) {
                                view.session.insertEmbedBlockAfter(blockRoot.index, newId)
                                view.session.removeBlockAt(blockRoot.index)
                                view.toastRequested(qsTr("Created “%1”").arg(title))
                            }
                        }
                        blockActions.close()
                    }
                }
                ActRow {
                    icon: "trash"; label: qsTr("Delete"); shortcut: "Del"; danger: true
                    onTriggered: {
                        view.session.setSelected([blockRoot.blockId])
                        view.session.deleteSelected()
                        blockActions.close()
                    }
                }
                Item { Layout.preferredHeight: 4 }
            }
        }
    }
}
