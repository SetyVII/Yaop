import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "."

// Tags manager (docs/design/mockup/Tags.dc.html):
// All tags sorted by usage count with 9x9 color squares, proportional horizontal usage bars,
// inline 10-color palette editor, and right detail pane listing tagged objects.
Item {
    id: page

    property var appWindow: null
    property bool shown: false
    signal openObjectRequested(string objectId)

    readonly property int dataRev: (shown && ws) ? ws.listRevision : 0
    property int rev: 0
    property string selectedTagName: ""
    property string newTagInput: ""

    Connections {
        target: ws
        function onObjectPropertiesChanged() { page.rev++ }
    }

    readonly property var tags: { dataRev; rev; return ws ? ws.allTagsWithCounts() : [] }
    readonly property int maxCount: {
        let m = 1
        for (let i = 0; i < tags.length; i++) m = Math.max(m, tags[i].count || 0)
        return m
    }
    readonly property var activeTag: {
        for (let i = 0; i < tags.length; i++)
            if (tags[i].name === selectedTagName) return tags[i]
        return tags.length > 0 ? tags[0] : null
    }
    readonly property var taggedObjects: {
        dataRev; rev
        if (!ws || !activeTag || !activeTag.name) return []
        return ws.objectsForTag(activeTag.name)
    }

    RowLayout {
        anchors.fill: parent
        spacing: 0

        // Left: All tags table (Tags.dc.html)
        ColumnLayout {
            Layout.fillWidth: true
            Layout.fillHeight: true
            spacing: 0

            // Top bar
            Item {
                Layout.fillWidth: true; Layout.preferredHeight: 72
                Rectangle { anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom; height: 1; color: Theme.line }
                RowLayout {
                    anchors.fill: parent; anchors.leftMargin: 40; anchors.rightMargin: 40; spacing: 14
                    TintedIcon { name: "tag"; size: 20; color: Theme.tag }
                    Text { text: qsTr("Tags"); color: Theme.text; font.family: Theme.display; font.pixelSize: 22 }
                    Text { text: Theme.caps(qsTr("%1 · MOST USED FIRST").arg(page.tags.length)); color: Theme.label; font.family: Theme.mono; font.pixelSize: 11 }
                    Item { Layout.fillWidth: true }

                    Rectangle {
                        Layout.preferredWidth: 220; Layout.preferredHeight: 36
                        color: "transparent"; border.width: 1; border.color: createTagField.activeFocus ? Theme.text : Theme.line2
                        TextInput {
                            id: createTagField
                            anchors.fill: parent; anchors.leftMargin: 10; anchors.rightMargin: 10
                            verticalAlignment: TextInput.AlignVCenter
                            color: Theme.text; font.family: Theme.body; font.pixelSize: 13
                            text: page.newTagInput
                            onTextEdited: page.newTagInput = text
                            onAccepted: addTagBtn.doCreate()
                            Text { anchors.verticalCenter: parent.verticalCenter; visible: createTagField.length === 0; text: qsTr("New tag…"); color: Theme.faint; font: createTagField.font }
                        }
                    }
                    Button {
                        id: addTagBtn
                        primary: true
                        text: qsTr("+ Create")
                        enabled: page.newTagInput.trim().length > 0
                        function doCreate() {
                            const n = page.newTagInput.trim().replace(/^#+/, "").toLowerCase()
                            if (!n || !ws) return
                            ws.ensureTagObject(n)
                            page.selectedTagName = n
                            page.newTagInput = ""
                            page.rev++
                        }
                        onClicked: doCreate()
                    }
                }
            }

            Flickable {
                Layout.fillWidth: true; Layout.fillHeight: true
                contentWidth: width; contentHeight: tagListCol.implicitHeight + 64
                clip: true; boundsBehavior: Flickable.StopAtBounds

                ColumnLayout {
                    id: tagListCol
                    x: 40; y: 24
                    width: parent.width - 80
                    spacing: 0

                    Repeater {
                        model: page.tags
                        delegate: Rectangle {
                            required property var modelData
                            readonly property bool sel: page.activeTag && page.activeTag.name === modelData.name
                            Layout.fillWidth: true; Layout.preferredHeight: 46
                            color: sel ? Theme.panel2 : tRowHov.hovered ? Theme.cardHover : "transparent"

                            Rectangle { visible: sel; anchors.left: parent.left; anchors.top: parent.top; anchors.bottom: parent.bottom; width: 2; color: Theme.accent }
                            Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.lineSubtle }

                            RowLayout {
                                anchors.fill: parent; anchors.leftMargin: 12; anchors.rightMargin: 12; spacing: 14
                                Rectangle {
                                    Layout.preferredWidth: 9; Layout.preferredHeight: 9
                                    color: modelData.color || Theme.tag
                                }
                                Text {
                                    Layout.fillWidth: true
                                    text: modelData.name
                                    color: sel ? Theme.text : Theme.bodyText
                                    font.family: Theme.body; font.pixelSize: 15
                                    elide: Text.ElideRight
                                }
                                // Proportional bar (Tags.dc.html)
                                Rectangle {
                                    Layout.preferredWidth: 140; Layout.preferredHeight: 4
                                    color: Theme.line2
                                    Rectangle {
                                        width: Math.max(4, parent.width * ((modelData.count || 0) / page.maxCount))
                                        height: parent.height
                                        color: sel ? Theme.accent : Theme.muted
                                    }
                                }
                                Text {
                                    Layout.preferredWidth: 44
                                    horizontalAlignment: Text.AlignRight
                                    text: String(modelData.count || 0)
                                    color: Theme.muted; font.family: Theme.mono; font.pixelSize: 12
                                }
                            }
                            HoverHandler { id: tRowHov; cursorShape: Qt.PointingHandCursor }
                            TapHandler { onTapped: page.selectedTagName = modelData.name }
                        }
                    }

                    Text {
                        visible: page.tags.length === 0
                        Layout.topMargin: 24
                        text: qsTr("No tags yet. Type # inside any note or create one above.")
                        color: Theme.label; font.family: Theme.body; font.pixelSize: 14
                    }

                    Text {
                        Layout.fillWidth: true; Layout.topMargin: 28
                        text: qsTr("Each tag is an object with its own page, palette color, and incoming links. Apply it to an object or a single block.")
                        color: Theme.label; font.family: Theme.body; font.pixelSize: 13; lineHeight: 1.5; wrapMode: Text.WordWrap
                    }
                }
            }
        }

        // Right detail pane for selected tag (420px)
        Rectangle {
            visible: !!page.activeTag
            Layout.preferredWidth: 420
            Layout.fillHeight: true
            color: Theme.page
            Rectangle { anchors.top: parent.top; anchors.bottom: parent.bottom; anchors.left: parent.left; width: 1; color: Theme.line }

            Flickable {
                anchors.fill: parent
                contentWidth: width; contentHeight: detailCol.implicitHeight + 64
                clip: true; boundsBehavior: Flickable.StopAtBounds

                ColumnLayout {
                    id: detailCol
                    x: 32; y: 32; width: parent.width - 64; spacing: 0

                    RowLayout {
                        Layout.fillWidth: true; spacing: 10
                        Rectangle {
                            width: 12; height: 12
                            color: page.activeTag ? (page.activeTag.color || Theme.tag) : Theme.tag
                        }
                        Text {
                            Layout.fillWidth: true
                            text: page.activeTag ? page.activeTag.name : ""
                            color: Theme.text; font.family: Theme.display; font.pixelSize: 28; font.letterSpacing: -0.4
                        }
                        Button {
                            small: true; text: qsTr("Open page")
                            onClicked: if (page.activeTag && ws) {
                                const tid = ws.ensureTagObject(page.activeTag.name)
                                if (tid) page.openObjectRequested(tid)
                            }
                        }
                    }

                    Text {
                        Layout.topMargin: 22; Layout.bottomMargin: 8
                        text: Theme.caps(qsTr("COLOR · PALETTE OF 10"))
                        color: Theme.label; font.family: Theme.mono; font.pixelSize: 11
                    }
                    Row {
                        spacing: 4
                        Repeater {
                            model: Theme.paletteNames
                            delegate: Rectangle {
                                required property string modelData
                                readonly property string hex: Theme.paletteColor(modelData)
                                readonly property bool on: page.activeTag && String(page.activeTag.color || "").toUpperCase() === hex.toUpperCase()
                                width: 28; height: 28; color: "transparent"
                                border.width: 1; border.color: on ? Theme.text : "transparent"
                                Rectangle { anchors.fill: parent; anchors.margins: 3; color: hex }
                                HoverHandler { cursorShape: Qt.PointingHandCursor }
                                TapHandler {
                                    onTapped: if (page.activeTag && ws) {
                                        ws.setTagColor(page.activeTag.name, hex)
                                        page.rev++
                                    }
                                }
                            }
                        }
                    }

                    Text {
                        Layout.topMargin: 28; Layout.bottomMargin: 6
                        text: Theme.caps(qsTr("TAGGED OBJECTS · %1").arg(page.taggedObjects.length))
                        color: Theme.label; font.family: Theme.mono; font.pixelSize: 11
                    }
                    Repeater {
                        model: page.taggedObjects
                        delegate: Rectangle {
                            required property var modelData
                            Layout.fillWidth: true; implicitHeight: 46
                            color: objHov.hovered ? Theme.panel2 : "transparent"
                            Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.lineSubtle }
                            RowLayout {
                                anchors.fill: parent; anchors.leftMargin: 6; anchors.rightMargin: 6; spacing: 10
                                TypeBadge { typeId: modelData.typeId || ""; size: 16 }
                                Text { Layout.fillWidth: true; text: modelData.title || qsTr("Untitled"); color: Theme.bodyText; font.family: Theme.body; font.pixelSize: 14; elide: Text.ElideRight }
                                Text { text: Theme.typeLabel(modelData.typeId || ""); color: Theme.label; font.family: Theme.mono; font.pixelSize: 10 }
                            }
                            HoverHandler { id: objHov; cursorShape: Qt.PointingHandCursor }
                            TapHandler { onTapped: page.openObjectRequested(modelData.id) }
                        }
                    }
                    Text {
                        visible: page.taggedObjects.length === 0
                        Layout.topMargin: 10
                        text: qsTr("No objects tagged with this yet.")
                        color: Theme.label; font.family: Theme.body; font.pixelSize: 13
                    }
                }
            }
        }
    }
}
