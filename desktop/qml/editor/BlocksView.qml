import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../"

// Block list: the live editing surface. One delegate per visible block;
// all structure flows through the C++ EditorModel (pkm::blockops parity).

ListView {
    id: blocksView

    property var session: null
    property bool focusMode: false
    property var blockRefCounts: (ws && session && session.objectId && ws.revision >= 0)
        ? ws.blockReferenceCounts(session.objectId) : ({})

    // Paste URL menu state (HowTo.dc.html flow 6)
    property string pasteUrlValue: ""
    property int pasteUrlRow: -1
    property int pasteUrlCaret: 0
    property var pasteUrlDoc: null
    function openUrlPasteMenu(row, caret, doc, url) {
        pasteUrlRow = row
        pasteUrlCaret = caret
        pasteUrlDoc = doc
        pasteUrlValue = url
        urlPasteMenu.currentIndex = 2   // default: Bookmark card (Enter)
        urlPasteMenu.open()
    }
    // Image blocks' side panel (open + width) by blockId: survives delegate rebuilds, not synced.
    property var mediaPanels: ({})
    // Optional content drawn under the "+" row (the calendar's day page lists the day's objects there).
    property Component trailer: null
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
        } else if (triggerRow >= 0 && itemAtIndex(triggerRow)) {   // block mode: under the block
            const d = itemAtIndex(triggerRow)
            const below = d.mapToItem(blocksView, 48, d.height + gap)
            x = below.x
            belowY = below.y
            aboveBottom = d.mapToItem(blocksView, 0, 0).y - gap
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
    signal exitTop()   // ↑ on the first line of the first block, Alt+↑: the page moves focus to its title

    // ---------------- keyboard navigation (docs/keyboard.md §4) ----------------
    readonly property int caretEnd: 2147483647
    readonly property var nonTextTypes: ["code", "math", "table", "image", "audio", "pdf",
                                         "embed", "bookmark", "canvas", "divider"]
    function idAt(row) { return session ? session.data(session.index(row, 0), 257) : "" }      // BlockIdRole
    function typeAt(row) { return session ? session.data(session.index(row, 0), 258) : "" }    // TypeRole
    function isTextRow(row) { return row >= 0 && row < count && nonTextTypes.indexOf(typeAt(row)) < 0 }
    function rowOf(blockId) {
        for (let i = 0; i < count; ++i)
            if (idAt(i) === blockId) return i
        return -1
    }

    // Goal column for ↑/↓ across blocks, in view coordinates; -1 = none (reset by other keys).
    property real goalX: -1
    property string goalEdge: ""    // "top" | "bottom": which line of the next block to land on
    function focusNeighborAt(row, viewX, edge) {
        goalX = viewX
        goalEdge = isTextRow(row) ? edge : ""
        focusNeighbor(row, edge === "bottom" ? caretEnd : 0)
    }
    // A text block's TextEdit takes the pending goal column (call right after it got focus).
    function applyGoal(edit) {
        if (goalEdge === "" || goalX < 0) return
        const local = edit.mapFromItem(blocksView, goalX, 0).x
        const y = goalEdge === "bottom" ? edit.contentHeight - 4 : 4
        edit.cursorPosition = edit.positionAt(local, y)
        goalEdge = ""
    }

    // Block mode on one row: whole block selected, no caret; BlocksView holds the keys.
    function selectRow(row) {
        if (!session || count <= 0) return
        const r = Math.max(0, Math.min(count - 1, row))
        beginBlockSelection(r, r)
        positionViewAtIndex(r, ListView.Contain)
    }
    // Enter in block mode: text blocks get the caret at the end; other blocks run their main action.
    function activateRow(row) {
        if (!session || row < 0 || row >= count) return
        if (isTextRow(row)) {
            session.clearSelection()
            session.forceFocus(idAt(row), caretEnd)
            return
        }
        const d = itemAtIndex(row)
        if (d && d.activateBody(false)) session.clearSelection()
    }
    function spaceOnRow(row) {
        const t = typeAt(row)
        if (t === "todo") session.toggleTodo(row)
        else if (t === "toggle") session.toggleExpand(row)
        else if (t === "image" || t === "pdf" || t === "audio") { const d = itemAtIndex(row); if (d) d.activateBody(true) }
    }
    function openBlockMenu(row) {
        const d = itemAtIndex(row)
        if (d) d.openActions()
    }
    function openTurnInto(row) {
        triggerRow = row
        triggerEditor = null
        triggerCaret = 0
        triggerQuery = ""
        triggerKind = "slash"
    }
    // Moves the selected blocks (block mode) and keeps them selected and in view.
    function moveSelection(delta) {
        const lo = Math.min(selectionAnchorRow, selectionCursorRow)
        const hi = Math.max(selectionAnchorRow, selectionCursorRow)
        if (lo < 0 || (delta < 0 && lo === 0) || (delta > 0 && hi >= count - 1)) return
        session.moveBlocks(session.selectedIds(), delta)
        selectionAnchorRow += delta
        selectionCursorRow += delta
        positionViewAtIndex(selectionCursorRow, ListView.Contain)
    }

    // Where the caret was in each object, so reopening it puts you back there (§4.1).
    property var caretMemo: ({})
    function parkCaret(blockId, pos) {
        if (session && blockId) caretMemo[session.objectId] = { blockId: blockId, pos: pos }
    }
    // Focus when the page opens: last caret in this object, else the end of the first text block,
    // else a new empty paragraph. Waits for the session and its rows when they are not there yet.
    property bool startPending: false
    function focusStart() { startPending = true; Qt.callLater(tryStart) }
    function tryStart() {
        if (!startPending || !session) return
        startPending = false
        if (count === 0) { session.insertBlockAfter(-1, "paragraph"); return }
        const memo = caretMemo[session.objectId]
        const r = memo ? rowOf(memo.blockId) : -1
        if (r >= 0) {
            if (isTextRow(r)) session.forceFocus(memo.blockId, memo.pos)
            else selectRow(r)
            return
        }
        for (let i = 0; i < count; ++i)
            if (isTextRow(i)) { session.forceFocus(idAt(i), caretEnd); return }
        selectRow(0)
    }
    onSessionChanged: {
        if (startPending) Qt.callLater(tryStart)
        knownIds = null
        Qt.callLater(rememberIds)
    }
    onCountChanged: if (startPending) Qt.callLater(tryStart)
    // From the title (Enter / ↓): start of the first block.
    function enterFromTop() {
        if (!session) return
        if (count === 0) session.insertBlockAfter(-1, "paragraph")
        else if (isTextRow(0)) session.forceFocus(idAt(0), 0)
        else selectRow(0)
    }

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
        holdKeys()
    }

    function toggleBlockSelection(row, blockId) {
        if (!session)
            return
        selectionAnchorRow = row
        selectionCursorRow = row
        session.toggleSelected(blockId)
        formatBar.close()
        if (session.selectedCount > 0)
            holdKeys()
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
        holdKeys()
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

    // /query: pick a saved query (or make one for a type) and place it after the current block.
    property int queryRow: -1
    function openQueryPicker() {
        queryRow = activeRow
        queryPicker.open()
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
        if (!session)
            return
        if (row < 0) { goalEdge = ""; exitTop(); return }
        if (row >= count)
            return
        if (!isTextRow(row)) { goalEdge = ""; selectRow(row); return }   // non-text blocks: block mode
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

    // Picking a type in the "@" popup rewrites the token to `@type-slug/` so the list narrows to it.
    function applyMentionTypePrefix(slug) {
        if (!triggerEditor || triggerKind !== "object")
            return
        const plain = triggerEditor.getText(0, triggerEditor.length)
        const caret = triggerEditor.cursorPosition
        let start = caret - 1
        while (start >= 0 && plain[start] !== "@")
            --start
        if (start < 0)
            return
        const token = "@" + slug + "/"
        triggerEditor.remove(start, caret)
        triggerEditor.insert(start, token)
        triggerEditor.cursorPosition = start + token.length
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
            const id = url.substring("yaop://object/".length).split("#")[0] // "#block" = block reference
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
    // Block mode: the keys go to this sink (they bubble up to Keys below). Focusing the ListView
    // itself is a no-op while a TextEdit inside it has the focus, and a ListView would hand its
    // focus back to the current delegate.
    Item { id: keySink; objectName: "blockModeKeys"; focus: false }
    function holdKeys() {
        currentIndex = -1
        keySink.forceActiveFocus()
    }

    // Block mode keeps focus here; Esc clears the selection but not the focus, so ↑/↓/Enter
    // pick the block again (see Keys below). A selection made elsewhere pulls the focus in.
    Connections {
        target: blocksView.session
        ignoreUnknownSignals: true
        function onSelectionChanged() {
            if (blocksView.session.selectedCount > 0 && !keySink.activeFocus)
                blocksView.holdKeys()
        }
    }

    Keys.onShortcutOverride: (event) => {
        if (session && session.selectedCount > 0 && typeof keymap !== "undefined"
                && keymap.owns("blocks", event.key, event.modifiers))
            event.accepted = true
    }
    Keys.onPressed: function(event) {
        if (!session)
            return
        const ctrl = (event.modifiers & Qt.ControlModifier) || (event.modifiers & Qt.MetaModifier)
        const shift = event.modifiers & Qt.ShiftModifier
        const plain = !(event.modifiers & (Qt.ControlModifier | Qt.MetaModifier | Qt.AltModifier))
        // "Turn into…" from block mode: the slash menu filters with what you type here.
        if (popupActive()) {
            event.accepted = true
            if (event.key === Qt.Key_Down) popupMove(1)
            else if (event.key === Qt.Key_Up) popupMove(-1)
            else if (event.key === Qt.Key_Return || event.key === Qt.Key_Enter || event.key === Qt.Key_Tab) popupAccept()
            else if (event.key === Qt.Key_Escape) closePopups()
            else if (event.key === Qt.Key_Backspace) triggerQuery = triggerQuery.slice(0, -1)
            else if (plain && event.text.length > 0 && event.text.charCodeAt(0) > 32) triggerQuery += event.text
            return
        }
        if (session.selectedCount <= 0) {
            // Focus here with nothing selected (after Esc): ↑/↓/Enter take the last block again.
            if (count <= 0 || !plain) return
            const last = Math.max(0, Math.min(count - 1, selectionCursorRow >= 0 ? selectionCursorRow
                                                         : (activeRow >= 0 ? activeRow : 0)))
            if (event.key === Qt.Key_Up || event.key === Qt.Key_Down || event.key === Qt.Key_Home
                    || event.key === Qt.Key_End) {
                event.accepted = true
                selectRow(event.key === Qt.Key_Home ? 0 : event.key === Qt.Key_End ? count - 1 : last)
            } else if (event.key === Qt.Key_Return || event.key === Qt.Key_Enter) {
                event.accepted = true
                activateRow(last)
            }
            return
        }
        const cur = selectionCursorRow >= 0 ? selectionCursorRow : 0
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
            session.pasteFromClipboard(cur, 0, null)
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
        if (ctrl && shift && (event.key === Qt.Key_Up || event.key === Qt.Key_Down)) {
            event.accepted = true
            moveSelection(event.key === Qt.Key_Up ? -1 : 1)
            return
        }
        if ((ctrl && event.key === Qt.Key_Period) || event.key === Qt.Key_Menu
                || (shift && event.key === Qt.Key_F10)) {
            event.accepted = true
            openBlockMenu(cur)
            return
        }
        if (shift && event.key === Qt.Key_Up) {
            event.accepted = true
            extendBlockSelection(-1)
            return
        }
        if (shift && event.key === Qt.Key_Down) {
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
            // Block mode stays on the block above what was deleted.
            event.accepted = true
            const top = Math.min(selectionAnchorRow, selectionCursorRow)
            session.deleteSelected()
            Qt.callLater(selectRow, Math.max(0, top - 1))
            return
        }
        if (event.key === Qt.Key_Escape) {
            event.accepted = true
            session.clearSelection()
            return
        }
        if (!plain)
            return
        switch (event.key) {
        case Qt.Key_Up: event.accepted = true; selectRow(cur - 1); break
        case Qt.Key_Down: event.accepted = true; selectRow(cur + 1); break
        case Qt.Key_Home: event.accepted = true; selectRow(0); break
        case Qt.Key_End: event.accepted = true; selectRow(count - 1); break
        case Qt.Key_Return:
        case Qt.Key_Enter: event.accepted = true; activateRow(cur); break
        case Qt.Key_Space: event.accepted = true; spaceOnRow(cur); break
        case Qt.Key_Slash: event.accepted = true; openTurnInto(cur); break
        default: break
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
        function onModelAboutToBeReset() {
            blocksView.savedContentY = blocksView.contentY
            blocksView.noteMotion()
        }
        function onModelReset() {
            Qt.callLater(function() {
                blocksView.contentY = Math.max(0, Math.min(blocksView.savedContentY,
                                                            blocksView.contentHeight - blocksView.height))
                blocksView.ensureFocusedVisible()
                blocksView.playMotion()
            })
        }
        function onSelectionChanged() { Qt.callLater(blocksView.updateRing) }
        function onFocusChanged() { Qt.callLater(blocksView.ensureFocusedVisible) }
    }

    // Motion across a model reset (FLIP). Before the reset: where the visible blocks sit and what
    // type they are (read from the delegates: the model is already mid-change and must not be
    // queried). After it, the new delegates slide from their old place, or fade in when they are
    // new or changed type. `knownIds` (every block after the previous reset) tells new from
    // merely scrolled into view. Opening another object animates nothing.
    property var motionBefore: null
    property var knownIds: null
    property string knownObject: ""
    function noteMotion() {
        motionBefore = null
        if (Theme.motion === 0 || !session || !knownIds || knownObject !== session.objectId)
            return
        const ys = {}, types = {}
        for (let i = 0; i < count; ++i) {
            const d = itemAtIndex(i)
            if (d) { ys[d.blockId] = d.y; types[d.blockId] = d.blockType }
        }
        motionBefore = { objectId: session.objectId, ids: knownIds, ys: ys, types: types }
    }
    function rememberIds() {
        const ids = {}
        if (session)
            for (let i = 0; i < count; ++i)
                ids[session.data(session.index(i, 0), 257)] = true
        knownIds = ids
        knownObject = session ? session.objectId : ""
    }
    function playMotion() {
        const m = motionBefore
        motionBefore = null
        let moved = false
        if (m && session && m.objectId === session.objectId) {
            const fresh = [], slides = []
            let kept = 0
            for (let i = 0; i < count; ++i) {
                const d = itemAtIndex(i)
                if (!d) continue
                const id = d.blockId
                if (m.ids[id]) ++kept
                if (!m.ids[id] || (m.types[id] !== undefined && m.types[id] !== d.blockType))
                    fresh.push(d)
                else if (m.ys[id] !== undefined && Math.abs(m.ys[id] - d.y) >= 1)
                    slides.push({ d: d, dy: m.ys[id] - d.y })
            }
            // An edit touches a few blocks; a reload, a paste or another object does not animate.
            if (kept > 0 && fresh.length <= 3) {
                fresh.forEach(d => d.appear())
                slides.forEach(s => s.d.slideFrom(s.dy))
                moved = slides.length > 0
            }
        }
        rememberIds()
        updateRing(moved)
    }

    // Block mode: one outline around the selected run that glides between blocks (140 ms, or
    // 200 ms with the blocks when they move) instead of each block lighting its own border.
    readonly property bool ringShown: selectionRing.shown
    function updateRing(withMove) {
        let first = null, last = null, lastRow = -1, gap = false
        if (session && session.selectedCount > 0) {
            for (let i = 0; i < count; ++i) {
                const d = itemAtIndex(i)
                if (!d || !d.selected) continue
                if (first && i !== lastRow + 1) gap = true
                if (!first) first = d
                last = d
                lastRow = i
            }
        }
        if (!first || gap) {
            selectionRing.shown = false
            return
        }
        selectionRing.glide = selectionRing.shown
        selectionRing.dur = withMove === true ? Theme.normal : Theme.fast
        selectionRing.y = first.y
        selectionRing.height = last.y + last.height - first.y
        selectionRing.shown = true
    }
    Rectangle {
        id: selectionRing
        parent: blocksView.contentItem
        z: 40
        x: 0
        width: blocksView.width
        property bool shown: false
        property bool glide: false
        property int dur: Theme.fast
        visible: opacity > 0
        opacity: shown ? 1 : 0
        radius: Theme.radiusSm
        color: "transparent"
        border.width: 1
        border.color: Qt.rgba(0.75, 0.89, 1, 0.45)
        Behavior on opacity { NumberAnimation { duration: Theme.fast } }
        Behavior on y { enabled: selectionRing.glide; NumberAnimation { duration: selectionRing.dur; easing.type: Easing.Bezier; easing.bezierCurve: Theme.easeStandard } }
        Behavior on height { enabled: selectionRing.glide; NumberAnimation { duration: selectionRing.dur; easing.type: Easing.Bezier; easing.bezierCurve: Theme.easeStandard } }
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
        id: vbar
        policy: ScrollBar.AsNeeded
        contentItem: Rectangle {
            implicitWidth: 3
            radius: Theme.radiusSm
            color: Theme.faint
            // only while scrolling or hovered, and never when everything fits
            opacity: vbar.size < 1 && (vbar.active || vbar.hovered) ? 0.9 : 0
            Behavior on opacity { NumberAnimation { duration: Theme.normal } }
        }
    }

    footerPositioning: ListView.InlineFooter
    footer: Item {
        width: blocksView.width
        height: 92 + trailerLoader.height
        visible: !!session || !!blocksView.trailer

        Loader {
            id: trailerLoader
            y: 92
            width: parent.width
            sourceComponent: blocksView.trailer
        }

        RowLayout {
            visible: !!session
            x: 32
            y: 20
            spacing: 12

            Rectangle {
                Layout.preferredWidth: 22
                Layout.preferredHeight: 22
                radius: Theme.noir ? 0 : 11
                color: plusArea.hovered ? Theme.panel3 : (Theme.noir ? "transparent" : Theme.panel2)
                border.width: Theme.noir ? 1 : 0
                border.color: plusArea.hovered ? Theme.line3 : Theme.line2

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
                font.letterSpacing: Theme.track(0.4)
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

    // Paste URL as… menu (HowTo.dc.html flow 6: Inline link | Chip | Bookmark card | Wide card)
    Popup {
        id: urlPasteMenu
        property int currentIndex: 2
        readonly property var choices: [
            { mode: "link", icon: "text", label: qsTr("Inline link"), hint: "" },
            { mode: "chip", icon: "shape", label: qsTr("Chip"), hint: "" },
            { mode: "card", icon: "web", label: qsTr("Bookmark card"), hint: "ENTER" },
            { mode: "wide", icon: "web", label: qsTr("Wide card"), hint: "" }
        ]
        function accept(mode) {
            close()
            if (blocksView.session && blocksView.pasteUrlValue.length > 0)
                blocksView.session.pasteUrlAs(blocksView.pasteUrlRow, blocksView.pasteUrlCaret,
                                              blocksView.pasteUrlDoc, blocksView.pasteUrlValue, mode)
        }
        x: Math.max(24, Math.min(blocksView.width - width - 24, 96))
        y: Math.max(24, Math.min(blocksView.height - height - 24, 140))
        width: 310
        padding: 0
        modal: false
        focus: true
        closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
        background: Rectangle {
            color: Theme.sheet
            border.width: 1; border.color: Theme.line2
        }
        contentItem: ColumnLayout {
            spacing: 0
            Text {
                Layout.margins: 12; Layout.bottomMargin: 6
                text: Theme.caps(qsTr("PASTE AS"))
                color: Theme.label; font.family: Theme.mono; font.pixelSize: 10
            }
            Repeater {
                model: urlPasteMenu.choices
                delegate: Rectangle {
                    required property var modelData
                    required property int index
                    readonly property bool on: urlPasteMenu.currentIndex === index
                    Layout.fillWidth: true; implicitHeight: 36
                    color: on ? Theme.accent : upH.hovered ? Theme.panel2 : "transparent"
                    RowLayout {
                        anchors.fill: parent; anchors.leftMargin: 12; anchors.rightMargin: 12; spacing: 10
                        TintedIcon {
                            name: modelData.icon; size: 15
                            color: on ? Theme.accentInk : (modelData.mode === "card" || modelData.mode === "wide" ? Theme.weblink : Theme.dim)
                        }
                        Text {
                            Layout.fillWidth: true
                            text: modelData.label
                            color: on ? Theme.accentInk : Theme.bodyText
                            font.family: Theme.body; font.pixelSize: 14
                        }
                        Text {
                            visible: modelData.hint.length > 0
                            text: modelData.hint
                            color: on ? Theme.accentInk : Theme.label
                            font.family: Theme.mono; font.pixelSize: 10
                        }
                    }
                    HoverHandler { id: upH; cursorShape: Qt.PointingHandCursor; onHoveredChanged: if (hovered) urlPasteMenu.currentIndex = index }
                    TapHandler { onTapped: urlPasteMenu.accept(modelData.mode) }
                }
            }
            Rectangle { Layout.fillWidth: true; Layout.preferredHeight: 1; Layout.topMargin: 4; color: Theme.line }
            Text {
                Layout.fillWidth: true; Layout.margins: 10
                text: Theme.caps(qsTr("Creates a Web link object (except Inline link)"))
                color: Theme.label; font.family: Theme.mono; font.pixelSize: 9
                wrapMode: Text.WordWrap
            }
        }
        Keys.onPressed: (ev) => {
            if (ev.key === Qt.Key_Down) { ev.accepted = true; currentIndex = (currentIndex + 1) % choices.length }
            else if (ev.key === Qt.Key_Up) { ev.accepted = true; currentIndex = (currentIndex - 1 + choices.length) % choices.length }
            else if (ev.key === Qt.Key_Return || ev.key === Qt.Key_Enter) { ev.accepted = true; accept(choices[currentIndex].mode) }
            else if (ev.key === Qt.Key_Escape) { ev.accepted = true; close() }
        }
    }

    QueryPicker {
        id: queryPicker
        onChosen: (setId) => {
            if (!blocksView.session) return
            blocksView.session.insertEmbedBlockAfter(Math.max(0, blocksView.queryRow), setId)
            blocksView.toastRequested(qsTr("Query placed. Its view and filters live in the query itself."))
        }
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
        onPickedType: function(slug) {
            blocksView.applyMentionTypePrefix(slug)
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
