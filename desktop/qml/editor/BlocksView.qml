import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../"

// Block list: the live editing surface. One delegate per visible block;
// all structure flows through the C++ EditorModel (pkm::blockops parity).

ListView {
    id: blocksView

    property var session: null
    property int activeRow: -1
    property var activeDoc: null
    property var activeTextEdit: null
    property int selStart: 0
    property int selEnd: 0
    property string activeFocusedBlockId: ""
    property var linkAnchorTip: null
    property int selectionAnchorRow: -1
    property int selectionCursorRow: -1
    property int dragSelectStartRow: -1
    property bool multiSelectingDrag: false

    // Places a trigger popup next to the caret: below it when the list fits, else above, else on
    // the side with more room — and the list scrolls inside whatever space is left, so the popup
    // is never taller than the view. Without a known editor it falls back to the old spot.
    function placePopup(p) {
        const margin = 12
        const gap = 6
        let x = 40
        let belowY = 140
        let aboveBottom = 140
        if (triggerEditor) {
            const r = triggerEditor.cursorRectangle
            const below = triggerEditor.mapToItem(blocksView, r.x, r.y + r.height + gap)
            const top = triggerEditor.mapToItem(blocksView, r.x, r.y)
            x = below.x
            belowY = below.y
            aboveBottom = top.y - gap
        }
        const chrome = p.headerHeight + 2   // header + border
        const spaceBelow = height - belowY - margin
        const spaceAbove = aboveBottom - margin
        const wanted = p.naturalHeight
        let placeBelow = wanted <= spaceBelow || (wanted > spaceAbove && spaceBelow >= spaceAbove)
        const room = Math.max(p.rowHeight * 3, (placeBelow ? spaceBelow : spaceAbove))
        p.maxListHeight = Math.max(p.rowHeight * 3, room - chrome)
        const vx = Math.max(margin, Math.min(x, width - p.width - margin))
        const vy = placeBelow ? belowY : Math.max(margin, aboveBottom - p.height)
        // Declared inside a ListView the popup lives in the scrolling content item: convert from
        // view coordinates to its real parent so it lands exactly at the caret.
        const host = p.parent ? blocksView.mapToItem(p.parent, vx, vy) : Qt.point(vx, vy)
        p.x = host.x
        p.y = host.y
    }

    // Keyboard API for the trigger popups: the editor keeps focus and forwards these keys.
    function activePopup() {
        if (triggerKind === "slash")
            return slashMenu
        if (triggerKind === "object" || triggerKind === "tag" || triggerKind === "date")
            return mentionPopup
        return null
    }
    function popupActive() { return activePopup() !== null }
    function popupMove(delta) {
        const p = activePopup()
        if (p)
            p.move(delta)
    }
    function popupAccept() {
        const p = activePopup()
        return p ? p.accept() : false
    }

    // inline trigger state
    property string triggerKind: ""     // "", "slash", "object", "tag", "date"
    property string triggerQuery: ""
    property int triggerRow: -1
    property var triggerEditor: null
    property int triggerCaret: 0

    // drag & drop reorder state
    property int dragFromRow: -1
    property int dropRow: -1
    property real dropIndicatorY: 0
    readonly property bool dragging: dragFromRow >= 0

    signal openObjectRequested(string objectId)
    signal mediaPickRequested(string kind)
    signal mediaPasteRequested()
    signal toastRequested(string message)

    function rowAtViewY(viewY) {
        if (count <= 0)
            return -1
        const cy = Math.max(0, Math.min(contentHeight - 1, viewY + contentY))
        const probeX = Math.min(48, Math.max(4, width * 0.25))
        let r = indexAt(probeX, cy)
        if (r < 0) r = indexAt(probeX, cy + 4)
        if (r < 0) r = indexAt(probeX, Math.max(0, cy - 4))
        return r
    }

    function beginBlockSelection(anchorRow, cursorRow) {
        if (!session || count <= 0)
            return
        const a = Math.max(0, Math.min(count - 1, anchorRow))
        const c = Math.max(0, Math.min(count - 1, cursorRow))
        selectionAnchorRow = a
        selectionCursorRow = c
        session.selectRange(a, c)
        formatBar.close()
        if (activeTextEdit && activeTextEdit.selectionStart !== activeTextEdit.selectionEnd)
            activeTextEdit.deselect()
        blocksView.forceActiveFocus()
    }

    function toggleBlockSelection(row, blockId) {
        if (!session)
            return
        selectionAnchorRow = row
        selectionCursorRow = row
        session.toggleSelected(blockId)
        formatBar.close()
        if (session.selectedCount > 0)
            blocksView.forceActiveFocus()
    }

    function extendBlockSelection(delta) {
        if (!session || count <= 0)
            return
        const anchor = selectionAnchorRow >= 0 ? selectionAnchorRow : Math.max(0, activeRow)
        const base = selectionCursorRow >= 0 ? selectionCursorRow : anchor
        const next = Math.max(0, Math.min(count - 1, base + delta))
        beginBlockSelection(anchor, next)
        positionViewAtIndex(next, ListView.Contain)
    }

    function selectAllBlocksFrom(row) {
        if (!session || count <= 0)
            return
        selectionAnchorRow = Math.max(0, Math.min(count - 1, row))
        selectionCursorRow = count - 1
        session.selectAllBlocks()
        formatBar.close()
        if (activeTextEdit)
            activeTextEdit.deselect()
        blocksView.forceActiveFocus()
    }

    function copyMultiSelection(cut) {
        if (!session || session.selectedCount <= 0)
            return false
        const n = session.selectedCount
        if (session.copySelected(cut)) {
            toastRequested(cut ? qsTr("Cut %1 blocks").arg(n) : qsTr("Copied %1 blocks").arg(n))
            return true
        }
        return false
    }

    function probeEmbedFlow() {
        if (!triggerEditor)
            return
        triggerKind = "object"
        triggerQuery = ""
        triggerRow = activeRow
    }

    function refreshSelection(editor, row) {
        activeDoc = editor.textDocument
        activeTextEdit = editor
        activeRow = row
        selStart = editor.selectionStart
        selEnd = editor.selectionEnd
        if (editor.selectionStart !== editor.selectionEnd && (!session || session.selectedCount === 0)) {
            formatBar.openBar()
            placeFormatBar(editor)
        } else {
            formatBar.close()
        }
    }

    // The toolbar floats just above the start of the selection (below it when the selection is at
    // the very top of the view), clamped inside the view.
    function placeFormatBar(editor) {
        const r = editor.positionToRectangle(editor.selectionStart)
        const above = editor.mapToItem(blocksView, r.x, r.y - formatBar.height - 6)
        const below = editor.mapToItem(blocksView, r.x, r.y + r.height + 6)
        const vy = above.y >= 4 ? above.y : below.y
        const vx = Math.max(8, Math.min(above.x, width - formatBar.width - 8))
        const host = formatBar.parent ? blocksView.mapToItem(formatBar.parent, vx, vy) : Qt.point(vx, vy)
        formatBar.x = host.x
        formatBar.y = host.y
    }

    function setFocusedBlock(blockId, focused) {
        if (focused)
            activeFocusedBlockId = blockId
        else if (activeFocusedBlockId === blockId)
            activeFocusedBlockId = ""
    }

    function focusNeighbor(row, cursorPos) {
        if (row < 0 || row >= count || !session)
            return
        currentIndex = row
        const wanted = session.data(session.index(row, 0), 257) // BlockIdRole
        if (wanted === session.focusBlockId)
            return
        // Ask the session to focus; the delegate will pick it up.
        session.forceFocus(wanted, cursorPos)
    }

    function closePopups() {
        triggerKind = ""
        triggerQuery = ""
        formatBar.close()
    }

    // Scan the token just before the caret for "/" "@" "#" triggers.
    // "@" accepts "@tipo/nombre" (and "@nombre/tipo") without switching "/" to SlashMenu.
    function probeInlineTrigger(row, editor) {
        if (!session || !editor)
            return
        const caret = editor.cursorPosition
        const text = editor.getText(0, editor.length)
        const minStop = Math.max(0, caret - 48)
        let i = caret - 1
        let firstSlash = -1
        let firstSpaceAfterCaret = -1
        while (i >= minStop) {
            const ch = text[i]
            if (ch === "\n" || ch === "\u2028")
                break
            if (ch === " ") {
                if (firstSpaceAfterCaret < 0)
                    firstSpaceAfterCaret = i
                // Only continue scanning across spaces if we haven't seen a slash yet? Wait: if we see '@' earlier AND '/' between '@' and caret, spaces in the title are valid!
                --i
                continue
            }
            if (ch === "@" || ch === "#") {
                const rawToken = text.substring(i + 1, caret)
                const hasSlash = rawToken.indexOf("/") >= 0
                if (!hasSlash && firstSpaceAfterCaret > i)
                    return closePopups()
                if (ch === "#" && firstSpaceAfterCaret > i)
                    return closePopups()
                if (i >= 1 && (text[i - 1] === "@" || text[i - 1] === "#" || text[i - 1] === "/"))
                    return closePopups()
                if (i >= 1 && text[i - 1] !== " " && text[i - 1] !== "\n" && text[i - 1] !== "\u2028" && text[i - 1] !== "(" && text[i - 1] !== "[")
                    return closePopups()
                // A "@" or "#" that is already a reference chip (or tag/date link) is content, not a trigger.
                const rect = editor.positionToRectangle(i + 1)
                if (editor.linkAt(Math.max(0, rect.x - 3), rect.y + rect.height / 2).length > 0)
                    return closePopups()
                triggerKind = ch === "@" ? "object" : "tag"
                triggerQuery = rawToken
                triggerRow = row
                triggerEditor = editor
                triggerCaret = i
                Qt.callLater(function() { const p = activePopup(); if (p) placePopup(p) })
                if (!formatBar.visibleOnly())
                    formatBar.close()
                return
            }
            if (ch === "/") {
                if (firstSlash < 0)
                    firstSlash = i
            }
            --i
        }
        if (firstSlash >= 0 && firstSpaceAfterCaret < firstSlash) {
            const atLineOrSpace = firstSlash === 0
                || text[firstSlash - 1] === " "
                || text[firstSlash - 1] === "\n"
                || text[firstSlash - 1] === "\u2028"
            if (atLineOrSpace) {
                const query = text.substring(firstSlash + 1, caret).toLowerCase()
                const rect = editor.positionToRectangle(firstSlash + 1)
                if (editor.linkAt(Math.max(0, rect.x - 3), rect.y + rect.height / 2).length > 0)
                    return closePopups()
                triggerKind = "slash"
                triggerQuery = query
                triggerRow = row
                triggerEditor = editor
                triggerCaret = firstSlash
                Qt.callLater(function() { const p = activePopup(); if (p) placePopup(p) })
                if (!formatBar.visibleOnly())
                    formatBar.close()
                return
            }
        }
        closePopups()
    }

    // Insert a block selected from the slash catalog.
    function applySlashSelection(key) {
        if (!session || triggerKind !== "slash")
            return
        const row = triggerRow >= 0 ? triggerRow : activeRow
        if (row < 0)
            return
        if (triggerEditor) {
            // Erase the "/query" token the user typed.
            const plain = triggerEditor.getText(0, triggerEditor.length)
            const caret = triggerEditor.cursorPosition
            let start = caret - 1
            while (start >= 0 && plain[start] !== "/")
                --start
            if (start >= 0) {
                triggerEditor.remove(start, caret)
                session.commitRichText(row, triggerEditor.textDocument, 0)
            }
        }
        session.convertBlock(row, key)
        triggerKind = ""
        triggerQuery = ""
    }

    function applyMentionSelection(objectId, label) {
        if (!session || triggerKind !== "object" || !triggerEditor)
            return
        const row = triggerRow
        const plain = triggerEditor.getText(0, triggerEditor.length)
        const caret = triggerEditor.cursorPosition
        let start = caret - 1
        while (start >= 0 && plain[start] !== "@")
            --start
        if (start < 0)
            return
        triggerEditor.remove(start, caret)
        // Referencing an image puts the image itself in the note (a media block over the same
        // asset); anything else becomes an inline chip.
        const info = (typeof ws !== "undefined" && ws) ? ws.objectSummary(objectId) : ({})
        if (info && info.typeId === "image" && info.assetId && info.assetId.length > 0) {
            session.commitRichText(row, triggerEditor.textDocument, 0)
            session.insertImageBlockAfter(row, info.assetId, "")
            triggerKind = ""
            triggerQuery = ""
            return
        }
        session.insertMentionAt(row, start, triggerEditor.textDocument, objectId, label)
        triggerKind = ""
        triggerQuery = ""
    }

    function applyMentionCreate(typeId, title) {
        if (!session || triggerKind !== "object" || !triggerEditor || typeof ws === "undefined" || !ws)
            return
        const cleanTitle = String(title || "").trim()
        if (cleanTitle.length === 0)
            return
        const newId = typeId === "tag" ? ws.ensureTagObject(cleanTitle) : ws.createObject(typeId, cleanTitle)
        if (!newId || newId.length === 0)
            return
        applyMentionSelection(newId, cleanTitle)
    }

    function applyTagSelection(tagName) {
        if (!session || triggerKind !== "tag" || !triggerEditor)
            return
        const row = triggerRow
        const plain = triggerEditor.getText(0, triggerEditor.length)
        const caret = triggerEditor.cursorPosition
        let start = caret - 1
        while (start >= 0 && plain[start] !== "#")
            --start
        if (start < 0)
            return
        triggerEditor.remove(start, caret)
        session.insertTagAt(row, start, triggerEditor.textDocument, tagName)
        triggerKind = ""
        triggerQuery = ""
    }

    function linkRequested(url, row, doc, caret, ctrlHeld, viewPt) {
        if (url.startsWith("yaop://object/")) {
            // Clicking a mention navigates straight to the referenced object.
            const id = url.substring("yaop://object/".length)
            if (id.length > 0)
                openObjectRequested(id)
        } else if (url.startsWith("yaop-date://")) {
            const iso = url.substring("yaop-date://".length).split("T")[0]
            if (typeof ws !== "undefined" && ws) {
                const jid = ctrlHeld ? ws.journalFor(iso) : ws.journalLocalFor(iso)
                if (jid.length > 0 && (ctrlHeld || jid.length > 0)) {
                    openObjectRequested(jid)
                    return
                }
            }
            linkAnchorTip = { type: "date", url: url, iso: iso, row: row, caret: caret, doc: doc }
            placeMentionTip(viewPt)
            mentionTip.showTip(linkAnchorTip)
        } else if (url.startsWith("yaop-tag://")) {
            const rest = url.substring("yaop-tag://".length)
            const parts = rest.split("/")
            const tid = parts[0] || ""
            const tname = parts.length > 1 ? decodeURIComponent(parts.slice(1).join("/")) : tid
            if (ctrlHeld && typeof ws !== "undefined" && ws) {
                const ensured = tname.length > 0 ? ws.ensureTagObject(tname) : tid
                if (ensured.length > 0) {
                    openObjectRequested(ensured)
                    return
                }
            }
            linkAnchorTip = { type: "tag", url: url, tagId: tid, tagName: tname, row: row, caret: caret, doc: doc }
            placeMentionTip(viewPt)
            mentionTip.showTip(linkAnchorTip)
        } else if (url.length > 0) {
            Qt.openUrlExternally(url)
        }
    }

    function placeMentionTip(viewPt) {
        if (!viewPt || (viewPt.x === 0 && viewPt.y === 0)) {
            mentionTip.x = Math.max(20, width - mentionTip.width - 40)
            mentionTip.y = 90
            return
        }
        mentionTip.x = Math.max(12, Math.min(width - mentionTip.width - 12, viewPt.x - 24))
        mentionTip.y = Math.max(12, Math.min(height - mentionTip.height - 12, viewPt.y + 18))
    }

    function scrollToBlock(blockId) {
        if (!session || !blockId || blockId.length === 0)
            return
        for (let i = 0; i < count; ++i) {
            if (session.data(session.index(i, 0), 257) === blockId) {
                positionViewAtIndex(i, ListView.Center)
                currentIndex = i
                return
            }
        }
    }

    // ---- drag & drop ----
    function beginDrag(row) {
        dragFromRow = row
        dropRow = row
        dropIndicatorY = 0
    }
    function updateDrag(scenePos) {
        const p = mapFromScene(scenePos)
        const cy = p.y + contentY
        let row = indexAt(24, cy)
        if (row < 0)
            row = cy <= 0 ? 0 : count - 1
        dropRow = row
        dropIndicatorY = Math.max(0, Math.min(height - 2, p.y))
    }
    function endDrag(commit) {
        if (commit && dragFromRow >= 0 && dropRow >= 0 && dragFromRow !== dropRow && session)
            session.moveBlockTo(dragFromRow, dropRow)
        dragFromRow = -1
        dropRow = -1
    }

    anchors.fill: parent
    clip: true
    boundsBehavior: Flickable.StopAtBounds
    spacing: 2
    currentIndex: -1
    keyNavigationEnabled: false
    focus: session && session.selectedCount > 0

    Keys.onPressed: function(event) {
        if (!session || session.selectedCount <= 0)
            return
        const ctrl = (event.modifiers & Qt.ControlModifier) || (event.modifiers & Qt.MetaModifier)
        if (ctrl && event.key === Qt.Key_C) {
            event.accepted = true
            copyMultiSelection(false)
            return
        }
        if (ctrl && event.key === Qt.Key_X) {
            event.accepted = true
            copyMultiSelection(true)
            return
        }
        if (ctrl && event.key === Qt.Key_V) {
            event.accepted = true
            const targetRow = selectionCursorRow >= 0 ? selectionCursorRow : 0
            session.pasteFromClipboard(targetRow, 0, null)
            return
        }
        if (ctrl && event.key === Qt.Key_A) {
            event.accepted = true
            session.selectAllBlocks()
            return
        }
        if (ctrl && event.key === Qt.Key_D) {
            event.accepted = true
            session.duplicateBlocks(session.selectedIds())
            return
        }
        if ((event.modifiers & Qt.ShiftModifier) && event.key === Qt.Key_Up) {
            event.accepted = true
            extendBlockSelection(-1)
            return
        }
        if ((event.modifiers & Qt.ShiftModifier) && event.key === Qt.Key_Down) {
            event.accepted = true
            extendBlockSelection(1)
            return
        }
        if (event.key === Qt.Key_Tab) {
            event.accepted = true
            session.indentBlocks(selectionAnchorRow >= 0 ? selectionAnchorRow : 0, 1)
            return
        }
        if (event.key === Qt.Key_Backtab) {
            event.accepted = true
            session.indentBlocks(selectionAnchorRow >= 0 ? selectionAnchorRow : 0, -1)
            return
        }
        if (event.key === Qt.Key_Delete || event.key === Qt.Key_Backspace) {
            event.accepted = true
            session.deleteSelected()
            return
        }
        if (event.key === Qt.Key_Escape) {
            event.accepted = true
            session.clearSelection()
            return
        }
    }

    // Drag across block boundaries to select multiple blocks like text.
    PointHandler {
        id: dragSelectHandler
        acceptedButtons: Qt.LeftButton
        onActiveChanged: {
            if (active && !blocksView.dragging) {
                blocksView.dragSelectStartRow = blocksView.rowAtViewY(point.position.y)
                blocksView.multiSelectingDrag = false
            } else if (!active) {
                blocksView.multiSelectingDrag = false
                blocksView.dragSelectStartRow = -1
                dragScrollTimer.stop()
            }
        }
        onPointChanged: {
            if (!active || blocksView.dragging || blocksView.dragSelectStartRow < 0)
                return
            const py = point.position.y
            const hoverRow = blocksView.rowAtViewY(Math.max(1, Math.min(blocksView.height - 1, py)))
            if (hoverRow >= 0 && (blocksView.multiSelectingDrag || hoverRow !== blocksView.dragSelectStartRow)) {
                blocksView.multiSelectingDrag = true
                blocksView.beginBlockSelection(blocksView.dragSelectStartRow, hoverRow)
            }
            if (blocksView.multiSelectingDrag && (py < 36 || py > blocksView.height - 36))
                dragScrollTimer.start()
            else
                dragScrollTimer.stop()
        }
    }

    Timer {
        id: dragScrollTimer
        interval: 30
        repeat: true
        onTriggered: {
            if (!blocksView.multiSelectingDrag || !dragSelectHandler.active) {
                stop()
                return
            }
            const py = dragSelectHandler.point.position.y
            const step = py < 36 ? -18 : (py > blocksView.height - 36 ? 18 : 0)
            if (step === 0)
                return
            const maxY = Math.max(0, blocksView.contentHeight - blocksView.height)
            blocksView.contentY = Math.max(0, Math.min(maxY, blocksView.contentY + step))
            const hoverRow = blocksView.rowAtViewY(Math.max(1, Math.min(blocksView.height - 1, py)))
            if (hoverRow >= 0 && blocksView.dragSelectStartRow >= 0)
                blocksView.beginBlockSelection(blocksView.dragSelectStartRow, hoverRow)
        }
    }

    model: session

    // Every structural edit (Enter, split, merge...) resets the whole model, and a ListView snaps
    // back to the top on reset. Keep the scroll position and then make sure the block holding the
    // caret is still on screen — long notes with images must not throw the view away.
    property real savedContentY: 0
    Connections {
        target: blocksView.session
        ignoreUnknownSignals: true
        function onModelAboutToBeReset() { blocksView.savedContentY = blocksView.contentY }
        function onModelReset() {
            Qt.callLater(function() {
                blocksView.contentY = Math.max(0, Math.min(blocksView.savedContentY,
                                                            blocksView.contentHeight - blocksView.height))
                blocksView.ensureFocusedVisible()
            })
        }
        function onFocusChanged() { Qt.callLater(blocksView.ensureFocusedVisible) }
    }

    function ensureFocusedVisible() {
        if (!session || !session.focusBlockId || session.focusBlockId.length === 0)
            return
        for (let i = 0; i < count; ++i) {
            if (session.data(session.index(i, 0), 257) === session.focusBlockId) {
                positionViewAtIndex(i, ListView.Contain)
                return
            }
        }
    }

    delegate: BlockDelegate {
        view: blocksView
        width: blocksView.width
    }

    // drop indicator while dragging a block
    Rectangle {
        visible: blocksView.dragging
        x: 8
        width: Math.max(0, blocksView.width - 16)
        height: 2
        color: Theme.accent
        y: blocksView.dropIndicatorY
        z: 50
        opacity: 0.9
    }

    ScrollBar.vertical: ScrollBar {
        policy: ScrollBar.AsNeeded
        contentItem: Rectangle {
            implicitWidth: 3
            radius: Theme.radiusSm
            color: Theme.faint
        }
    }

    footerPositioning: ListView.InlineFooter
    footer: Item {
        width: blocksView.width
        height: 92
        visible: !!session

        RowLayout {
            x: 32
            y: 20
            spacing: 12

            Rectangle {
                Layout.preferredWidth: 22
                Layout.preferredHeight: 22
                radius: 11
                color: plusArea.hovered ? Theme.panel3 : Theme.panel2

                Text {
                    anchors.centerIn: parent
                    text: qsTr("+")
                    color: plusArea.hovered ? Theme.text : Theme.dim
                    font.family: Theme.mono
                    font.pixelSize: 13
                }

                HoverHandler { id: plusArea; cursorShape: Qt.PointingHandCursor }
                TapHandler {
                    onTapped: {
                        const last = session.blockCount - 1
                        session.insertBlockAfter(last, "paragraph")
                    }
                }
            }

            Text {
                text: qsTr("/ blocks · @ reference · # tag · **bold** toolbar on selection")
                color: Theme.faint
                font.family: Theme.mono
                font.pixelSize: 11
                font.letterSpacing: 0.4
            }
        }
    }

    // ------------------- overlays hosted above the list -------------------
    FormatBar {
        id: formatBar
        view: blocksView
    }

    SelectionBar {
        view: blocksView
    }

    SlashMenu {
        id: slashMenu
        parentView: blocksView
        visible: blocksView.triggerKind === "slash"
        query: blocksView.triggerQuery
        width: Math.min(320, blocksView.width - 80)
        onVisibleChanged: if (visible) blocksView.placePopup(slashMenu)
        onQueryChanged: if (visible) blocksView.placePopup(slashMenu)
        onHeightChanged: if (visible) blocksView.placePopup(slashMenu)
        onSelected: function(key) {
            blocksView.applySlashSelection(key)
        }
        onMediaPickRequested: function(kind) {
            blocksView.mediaPickRequested(kind)
        }
        onCloseRequested: blocksView.closePopups()
    }

    MentionPopup {
        id: mentionPopup
        visible: blocksView.triggerKind === "object" || blocksView.triggerKind === "tag" || blocksView.triggerKind === "date"
        mode: blocksView.triggerKind === "object" ? "object" : blocksView.triggerKind === "tag" ? "tag" : "date"
        query: blocksView.triggerQuery
        workspace: typeof ws !== "undefined" ? ws : null
        width: Math.min(340, blocksView.width - 80)
        onVisibleChanged: if (visible) blocksView.placePopup(mentionPopup)
        onQueryChanged: if (visible) blocksView.placePopup(mentionPopup)
        onHeightChanged: if (visible) blocksView.placePopup(mentionPopup)
        onPickedObject: function(objectId, label) {
            blocksView.applyMentionSelection(objectId, label)
        }
        onPickedCreateObject: function(typeId, title) {
            blocksView.applyMentionCreate(typeId, title)
        }
        onPickedTag: function(tagName) {
            blocksView.applyTagSelection(tagName)
        }
        onPickedDate: function(isoDate) {
            if (!blocksView.session || !blocksView.triggerEditor)
                return
            const row = blocksView.triggerRow
            const plain = blocksView.triggerEditor.getText(0, blocksView.triggerEditor.length)
            const caret = blocksView.triggerEditor.cursorPosition
            let start = caret - 1
            while (start >= 0 && plain[start] !== "@")
                --start
            if (start >= 0) {
                blocksView.triggerEditor.remove(start, caret)
                blocksView.session.insertDateAt(row, start, blocksView.triggerEditor.textDocument, isoDate)
            }
            blocksView.triggerKind = ""
        }
        onCloseRequested: blocksView.closePopups()
    }

    MentionTip {
        id: mentionTip
        z: 60
        x: Math.max(20, parent.width - width - 40)
        y: 90
        workspace: typeof ws !== "undefined" ? ws : null
        onOpenRequested: function(objectId) {
            blocksView.openObjectRequested(objectId)
        }
        onRemoveRequested: function(tip) {
            if (blocksView.session && tip.row !== undefined)
                blocksView.session.removeAnchorAt(tip.row, tip.caret, tip.doc || blocksView.activeDoc)
            hideTip()
        }
    }
}
