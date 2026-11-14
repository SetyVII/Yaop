import QtQuick
import "../"

// Text-bearing block editor: paragraph, headings, lists, todo, toggle, quote,
// callout. The TextEdit is RichText; C++ SpanCodec keeps runs in sync.

Item {
    id: tb

    property var view
    property int row
    property string blockId
    property string blockType: "paragraph"
    property int headingLevel: 1
    property bool checked: false
    property string content: ""
    property bool loading: true

    readonly property int basePx: {
        switch (blockType) {
        case "heading": return headingLevel === 1 ? 24 : headingLevel === 2 ? 19 : 16   // 4 paints as 3
        case "quote": return 16
        default: return 15
        }
    }
    readonly property int weight: blockType === "heading" ? Font.DemiBold : Font.Normal
    readonly property bool struck: blockType === "todo" && checked

    implicitHeight: edit.implicitHeight + 2

    // Object mentions get a pill behind them so they read as references, not text.
    property var pills: []
    function refreshPills() {
        pills = view.session ? view.session.mentionRanges(edit.textDocument) : []
    }
    Timer { id: pillTimer; interval: 60; onTriggered: tb.refreshPills() }

    Repeater {
        model: tb.pills
        delegate: Rectangle {
            required property var modelData
            // depends on width/height so pills follow re-wraps
            readonly property rect a: (edit.width, edit.contentHeight, edit.positionToRectangle(modelData.start))
            readonly property rect b: (edit.width, edit.contentHeight,
                                       edit.positionToRectangle(modelData.start + modelData.length))
            visible: a.y === b.y && b.x > a.x // a chip wrapped over two lines gets no pill
            x: a.x - 4
            y: a.y
            width: b.x - a.x + 8
            height: a.height
            radius: height / 2
            color: Theme.mentionPill
            border.width: 1
            border.color: Theme.mentionPillLine
        }
    }

    onContentChanged: {
        if (loading)
            return
        // External content refresh: only apply when block is not focused.
        if (!edit.activeFocus)
            edit.text = content
        pillTimer.restart()
    }

    Component.onCompleted: {
        edit.text = content
        loading = false
        pillTimer.restart()
        if (!!view.session && view.session.focusBlockId === tb.blockId
                && view.session.focusBlockId.length > 0) {
            edit.forceActiveFocus()
            edit.cursorPosition = Math.min(view.session.focusPosition, edit.length)
            view.session.clearFocusTarget(tb.blockId)
        }
    }

    // Session-driven focus (after split/merge/convert operations)
    Connections {
        target: view.session
        function onFocusChanged() {
            if (view.session && view.session.focusBlockId === tb.blockId) {
                if (!edit.activeFocus)
                    edit.forceActiveFocus()
                const targetPos = Math.min(view.session.focusPosition, edit.length)
                if (edit.cursorPosition !== targetPos)
                    edit.cursorPosition = targetPos
                view.session.clearFocusTarget(tb.blockId)
            }
        }
        function onBlockHtmlReplaced(blockId) {
            if (blockId === tb.blockId) {
                tb.loading = true
                edit.text = tb.content
                tb.loading = false
                pillTimer.restart()
            }
        }
    }

    TextEdit {
        id: edit
        objectName: "blockEdit"

        anchors.fill: parent
        textFormat: TextEdit.RichText
        wrapMode: TextEdit.Wrap
        padding: 0
        topPadding: 2
        bottomPadding: 2
        leftPadding: 4
        rightPadding: 8
        clip: true
        selectByMouse: true
        selectByKeyboard: true
        color: tb.struck ? Theme.muted : Theme.text
        selectionColor: Qt.rgba(0.75, 0.89, 1, 0.38)
        selectedTextColor: Theme.text
        font.family: {
            if (blockType === "quote")
                return Theme.body
            return Theme.body
        }
        font.pixelSize: tb.basePx
        font.weight: tb.weight
        font.italic: blockType === "quote"
        font.strikeout: tb.struck
        font.letterSpacing: blockType === "heading" ? -0.3 : 0
        readOnly: false

        onActiveFocusChanged: {
            if (activeFocus) {
                view.refreshSelection(edit, tb.row)
                view.setFocusedBlock(tb.blockId, true)
                view.session.documentFocused(tb.row, edit.textDocument, edit.cursorPosition)
            } else {
                view.setFocusedBlock(tb.blockId, false)
                if (view.session)
                    view.session.documentUnfocusedBlock(tb.blockId, edit.textDocument, edit.cursorPosition)
            }
        }

        onSelectionStartChanged: view.refreshSelection(edit, tb.row)
        onSelectionEndChanged: view.refreshSelection(edit, tb.row)

        onCursorPositionChanged: {
            view.refreshSelection(edit, tb.row)
        }

        onLinkActivated: function(url) {
            view.linkRequested(url, tb.row, edit.textDocument, edit.cursorPosition, false, Qt.point(0, 0))
        }

        // In editable mode TextEdit swallows clicks (caret move) and never
        // emits linkActivated, so taps landing on a hovered anchor are
        // intercepted here and routed to the reference handler. Presses that
        // are not on a link fall through to the TextEdit (caret/selection).
        MouseArea {
            id: linkClickArea
            anchors.fill: parent
            acceptedButtons: Qt.LeftButton
            hoverEnabled: false // let TextEdit keep tracking hoveredLink
            property bool pressedCtrl: false
            property point pressedViewPt: Qt.point(0, 0)
            onPressed: function(mouse) {
                if ((mouse.modifiers & Qt.ShiftModifier) && view.session
                        && ((view.activeRow >= 0 && view.activeRow !== tb.row) || view.session.selectedCount > 0)) {
                    const anchor = view.selectionAnchorRow >= 0 ? view.selectionAnchorRow
                                 : (view.activeRow >= 0 ? view.activeRow : tb.row)
                    view.beginBlockSelection(anchor, tb.row)
                    mouse.accepted = true
                    return
                }
                if ((mouse.modifiers & (Qt.ControlModifier | Qt.MetaModifier)) && edit.hoveredLink.length === 0) {
                    view.toggleBlockSelection(tb.row, tb.blockId)
                    mouse.accepted = true
                    return
                }
                if (view.session && view.session.selectedCount > 0)
                    view.session.clearSelection()
                pressedCtrl = Boolean(mouse.modifiers & (Qt.ControlModifier | Qt.MetaModifier))
                pressedViewPt = linkClickArea.mapToItem(view, mouse.x, mouse.y)
                mouse.accepted = edit.hoveredLink.length > 0
            }
            onClicked: function(mouse) {
                if (edit.hoveredLink.length > 0) {
                    view.closePopups()
                    const ctrl = pressedCtrl || Boolean(mouse.modifiers & (Qt.ControlModifier | Qt.MetaModifier))
                    view.linkRequested(edit.hoveredLink, tb.row, edit.textDocument, edit.cursorPosition, ctrl, pressedViewPt)
                }
            }
        }

        onTextChanged: {
            if (tb.loading)
                return
            // Debounce C++ commit; the doc is the source of truth while typing.
            commitTimer.restart()
            markdownProbe.restart()
            inlineProbe.restart()
            pillTimer.restart()
        }

        // ------------------- structure keys -------------------
        Keys.onPressed: function(event) {
            // A trigger popup ("/", "@", "#") is open: arrows, Enter/Tab and Esc drive it from the
            // keyboard while the caret stays here, so the typed query is always visible.
            if (view.popupActive()) {
                if (event.key === Qt.Key_Down) { event.accepted = true; view.popupMove(1); return }
                if (event.key === Qt.Key_Up) { event.accepted = true; view.popupMove(-1); return }
                if (event.key === Qt.Key_Return || event.key === Qt.Key_Enter || event.key === Qt.Key_Tab) {
                    if (view.popupAccept()) { event.accepted = true; return }
                }
                if (event.key === Qt.Key_Escape) { event.accepted = true; view.closePopups(); return }
            }
            // Copied files / images paste as media blocks instead of a file:// path;
            // multi-block/Markdown/HTML paste inserts structured blocks at the caret.
            if (event.matches(StandardKey.Paste)) {
                if (typeof ws !== "undefined" && ws && ws.clipboardHasMedia) {
                    event.accepted = true
                    view.mediaPasteRequested()
                    return
                }
                if (view.session && view.session.pasteFromClipboard(tb.row, edit.cursorPosition, edit.textDocument)) {
                    event.accepted = true
                    return
                }
            }
            if (event.modifiers & Qt.ControlModifier || event.modifiers & Qt.MetaModifier) {
                if (event.key === Qt.Key_A) {
                    if (edit.selectionStart === 0 && edit.selectionEnd === edit.length) {
                        event.accepted = true
                        view.selectAllBlocksFrom(tb.row)
                    }
                    return
                }
                // formatting shortcuts on selection
                if (event.key === Qt.Key_B) {
                    event.accepted = true
                    view.session.formatMark("bold", edit.textDocument, edit.selectionStart, edit.selectionEnd)
                    return
                }
                if (event.key === Qt.Key_I) {
                    event.accepted = true
                    view.session.formatMark("italic", edit.textDocument, edit.selectionStart, edit.selectionEnd)
                    return
                }
                if (event.key === Qt.Key_U) {
                    event.accepted = true
                    view.session.formatMark("underline", edit.textDocument, edit.selectionStart, edit.selectionEnd)
                    return
                }
                if (event.key === Qt.Key_E) {
                    event.accepted = true
                    view.session.formatMark("code", edit.textDocument, edit.selectionStart, edit.selectionEnd)
                    return
                }
                if (event.key === Qt.Key_H && event.modifiers & Qt.ShiftModifier) {
                    event.accepted = true
                    view.session.formatMark("highlight", edit.textDocument, edit.selectionStart, edit.selectionEnd)
                    return
                }
                if (event.key === Qt.Key_D) {
                    event.accepted = true
                    view.session.duplicateBlocks([model.blockId])
                    return
                }
                return
            }

            switch (event.key) {
            case Qt.Key_Return:
            case Qt.Key_Enter:
                event.accepted = true
                view.session.handleEnter(tb.row, edit.cursorPosition, edit.textDocument)
                break
            case Qt.Key_Backspace:
                if (edit.selectionStart === edit.selectionEnd) {
                    if (edit.cursorPosition === 0) {
                        event.accepted = true
                        view.session.handleBackspace(tb.row, edit.cursorPosition, edit.textDocument)
                    } else if (view.session && view.session.deleteAnchorOnKey(tb.row, edit.cursorPosition, edit.textDocument, false)) {
                        event.accepted = true
                    }
                }
                break
            case Qt.Key_Delete:
                if (edit.selectionStart === edit.selectionEnd
                        && view.session
                        && view.session.deleteAnchorOnKey(tb.row, edit.cursorPosition, edit.textDocument, true)) {
                    event.accepted = true
                }
                break
            case Qt.Key_Tab:
                event.accepted = true
                view.session.indentBlocks(tb.row, 1, edit.cursorPosition)
                break
            case Qt.Key_Backtab:
                event.accepted = true
                view.session.indentBlocks(tb.row, -1, edit.cursorPosition)
                break
            case Qt.Key_Up:
                if ((event.modifiers & Qt.ShiftModifier) && edit.cursorRectangle.y <= 0 && tb.row > 0) {
                    event.accepted = true
                    view.beginBlockSelection(tb.row, tb.row - 1)
                    return
                }
                if (!(event.modifiers & Qt.ShiftModifier) && edit.cursorRectangle.y <= 0) {
                    event.accepted = true
                    view.focusNeighbor(tb.row - 1, 2147483647)
                }
                break
            case Qt.Key_Down:
                if ((event.modifiers & Qt.ShiftModifier)
                        && edit.cursorRectangle.y + edit.cursorRectangle.height >= edit.contentHeight - 2
                        && tb.row + 1 < view.count) {
                    event.accepted = true
                    view.beginBlockSelection(tb.row, tb.row + 1)
                    return
                }
                if (!(event.modifiers & Qt.ShiftModifier)
                        && edit.cursorRectangle.y + edit.cursorRectangle.height >= edit.contentHeight - 2) {
                    event.accepted = true
                    view.focusNeighbor(tb.row + 1, 0)
                }
                break
            case Qt.Key_Escape:
                view.closePopups()
                break
            default:
                break
            }
        }

        Timer {
            id: commitTimer
            interval: 400
            onTriggered: {
                if (loading)
                    return
                view.session.commitRichText(tb.row, edit.textDocument, edit.cursorPosition)
                view.refreshSelection(edit, tb.row)
            }
        }

        // Silent markdown prefix detection (#, -, 1., [], >, $$, ```, ---, >>, ▶)
        Timer {
            id: markdownProbe
            interval: 60
            onTriggered: {
                if (loading)
                    return
                const changed = view.session.handleMarkdownPrefix(tb.row, edit.textDocument)
                if (changed)
                    view.closePopups()
            }
        }

        // Inline "@" / "#" / "/" triggers
        Timer {
            id: inlineProbe
            interval: 40
            onTriggered: {
                if (loading)
                    return
                view.probeInlineTrigger(tb.row, edit)
            }
        }
    }

    // caret placeholder for empty blocks (quiet hint)
    Text {
        visible: edit.length === 0 && !edit.activeFocus
        anchors.left: parent.left
        anchors.leftMargin: 5
        anchors.verticalCenter: parent.verticalCenter
        text: blockType === "paragraph" ? qsTr("Type '/' for blocks…") : qsTr("")
        color: Theme.faint
        font.family: Theme.body
        font.pixelSize: 13
        font.italic: false
    }
}
