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

    opacity: view && view.dragging && view.dragFromRow === index ? 0.35 : 1
    Behavior on opacity { NumberAnimation { duration: Theme.fast } }

    // Callout: box with the colour at 14 % and a 25 % border (spec §6). info = blue, warning = orange.
    readonly property string calloutColorName: calloutType === "WARNING" ? "orange"
        : calloutType === "IDEA" ? "yellow" : calloutType === "DANGER" ? "red" : "blue"
    property var calloutAccent: Theme.paletteColor(calloutColorName)

    radius: blockType === "callout" ? Theme.radius : ((selected || blockColorHex.length > 0) ? Theme.radiusSm : 0)
    border.width: selected ? 1 : (blockType === "callout" ? 1 : 0)
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

        // Quote: 3 px ice bar on the left (callouts are boxes instead)
        Item {
            Layout.preferredWidth: blockType === "quote" ? 11 : (blockType === "callout" ? 10 : 0)
            Layout.fillHeight: true

            Rectangle {
                visible: blockType === "quote"
                width: 3
                radius: 1.5
                height: parent.height - 4
                y: 2
                color: Theme.ice
            }
        }

        // List / todo / toggle markers
        Item {
            Layout.preferredWidth: markerRow.implicitWidth > 0 ? markerRow.implicitWidth + 8 : 0
            Layout.fillHeight: true

            // Markers sit on the FIRST line of the block (fixed one-line box at the top), not in the
            // vertical middle: a wrapped item used to leave its bullet floating between lines.
            RowLayout {
                id: markerRow
                anchors.left: parent.left
                anchors.top: parent.top
                anchors.topMargin: 6
                height: 22
                spacing: 4

                Text {
                    Layout.alignment: Qt.AlignVCenter
                    visible: blockType === "bullet_list"
                    text: "•"
                    color: Theme.muted
                    font.family: Theme.body
                    font.pixelSize: 15
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
                // Todo checkbox: circle 18 px; marked = white fill with an ink tick (spec §6)
                Rectangle {
                    Layout.alignment: Qt.AlignVCenter
                    visible: blockType === "todo"
                    width: 18
                    height: 18
                    radius: 9
                    color: checked ? Theme.accent : "transparent"
                    border.width: 1.5
                    border.color: checked ? Theme.accent : Theme.dim
                    Behavior on color { ColorAnimation { duration: Theme.fast } }
                    Text {
                        visible: checked
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
                    text: "›"
                    rotation: expanded ? 90 : 0
                    Behavior on rotation { NumberAnimation { duration: Theme.fast } }
                    color: Theme.dim
                    font.family: Theme.body
                    font.pixelSize: 20
                    TapHandler { onTapped: view.session.toggleExpand(blockRoot.index) }
                }
                Text {
                    Layout.alignment: Qt.AlignVCenter
                    visible: blockType === "callout"
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
                        blockActions.popup()
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

    // Block contextual actions ("more" menu)
    Menu {
        id: blockActions
        background: Rectangle { color: Theme.panel2; border.color: Theme.line; border.width: 1 }

        MenuItem {
            text: qsTr("Turn into…")
            onTriggered: {
                view.triggerKind = "slash"
                view.triggerRow = blockRoot.index
                view.triggerQuery = ""
                view.triggerEditor = null
                view.triggerCaret = 0
            }
        }
        MenuItem {
            text: qsTr("Duplicate")
            onTriggered: view.session.duplicateBlocks([blockRoot.blockId])
        }
        MenuItem {
            text: qsTr("Move up")
            onTriggered: view.session.moveBlocks([blockRoot.blockId], -1)
        }
        MenuItem {
            text: qsTr("Move down")
            onTriggered: view.session.moveBlocks([blockRoot.blockId], 1)
        }
        MenuItem {
            text: blockRoot.isGrouped ? qsTr("Remove from group") : qsTr("Create group")
            onTriggered: view.session.groupBlocks([blockRoot.blockId])
        }
        MenuItem {
            text: qsTr("Color")
            onTriggered: colorMenu.popup()
        }
        MenuSeparator { }
        MenuItem {
            text: qsTr("Delete")
            onTriggered: {
                view.session.setSelected([blockRoot.blockId])
                view.session.deleteSelected()
            }
        }
    }

    Menu {
        id: colorMenu

        Instantiator {
            model: Theme.paletteNames.concat([qsTr("No color")])
            onObjectAdded: (index, object) => colorMenu.insertItem(index, object)
            onObjectRemoved: (index, object) => colorMenu.removeItem(object)

            delegate: MenuItem {
                required property string modelData
                required property int index
                onTriggered: {
                    const isLast = index >= Theme.paletteNames.length
                    view.session.setBlockColor(blockRoot.index, isLast ? "" : modelData)
                }
            }
        }
    }
}
