import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../"

// Floating format toolbar shown while a selection exists in a text block.
// Marks apply through EditorModel -> span::applyMark.

Popup {
    id: fb

    property var view
    property int bottomPaddingRow: -1

    function openBar() {
        open()
    }
    function visibleOnly() {
        return visible
    }

    width: fmtRow.implicitWidth + 16
    height: 40
    padding: 0
    closePolicy: Popup.NoAutoClose
    background: Rectangle {
        radius: 20
        color: Theme.panel2
        border.color: Theme.line2
        border.width: 1
    }

    contentItem: RowLayout {
        id: fmtRow
        spacing: 4

        component FmtTool: Item {
            id: tool
            property string glyph
            property string markKey
            property bool bold: false
            property bool italic: false
 property bool underline: false
            readonly property bool active: view && view.session && view.activeDoc
                ? view.session.markActive(markKey, view.activeDoc, view.selStart, view.selEnd)
                : false

            Layout.preferredWidth: 30
            Layout.preferredHeight: 28

            Rectangle {
                anchors.fill: parent
                anchors.margins: 2
                color: tool.active ? Theme.accentDim : (hoverHandler.hovered ? Theme.panel3 : "transparent")
            }

            Text {
                anchors.centerIn: parent
                text: tool.glyph
                color: tool.active ? Theme.accent : Theme.dim
                font.family: Theme.body
                font.pixelSize: 13
                font.bold: tool.bold
                font.italic: tool.italic
                font.underline: tool.underline
            }

            HoverHandler { id: hoverHandler; cursorShape: Qt.PointingHandCursor }
            TapHandler {
                onTapped: {
                    if (view.session && view.activeDoc)
                        view.session.formatMark(tool.markKey, view.activeDoc, view.selStart, view.selEnd)
                }
            }
        }

        FmtTool { glyph: "B"; markKey: "bold"; bold: true }
        FmtTool { glyph: "I"; markKey: "italic"; italic: true }
        FmtTool { glyph: "U"; markKey: "underline"; underline: true }
        FmtTool { glyph: "S"; markKey: "strike" }
        FmtTool { glyph: "{}"; markKey: "code" }
        FmtTool { glyph: "◧"; markKey: "highlight" }

        Rectangle { Layout.preferredWidth: 1; Layout.fillHeight: true; color: Theme.line2 }

        // Text colors
        Repeater {
            model: Theme.paletteNames.slice(0, 6)
            delegate: Item {
                Layout.preferredWidth: 20
                Layout.preferredHeight: 28

                Rectangle {
                    anchors.centerIn: parent
                    width: 13
                    height: 13
                    color: Theme.paletteColor(modelData)
                    opacity: colorHover.hovered ? 1 : 0.75
                    Rectangle { anchors.fill: parent; anchors.margins: -4; color: "transparent"; border.width: 1; border.color: colorHover.hovered ? Theme.line3 : "transparent" }
                }

                HoverHandler { id: colorHover; cursorShape: Qt.PointingHandCursor }
                TapHandler {
                    onTapped: {
                        if (view.session && view.activeDoc)
                            view.session.formatColor("text", modelData, view.activeDoc, view.selStart, view.selEnd)
                    }
                }
            }
        }

        Rectangle { Layout.preferredWidth: 1; Layout.fillHeight: true; color: Theme.line2 }

        Item {
            Layout.preferredWidth: 30
            Layout.preferredHeight: 28

            Text {
                anchors.centerIn: parent
                text: "🔗"
                color: linkHover.hovered ? Theme.accent : Theme.dim
                font.family: Theme.body
                font.pixelSize: 12
            }
            HoverHandler { id: linkHover; cursorShape: Qt.PointingHandCursor }
            TapHandler {
                onTapped: {
                    if (view.session && view.activeDoc) {
                        linkPrompt.text = ""
                        linkPrompt.open()
                    }
                }
            }
        }
    }

    Popup {
        id: linkPrompt
        x: (parent.width - width) / 2
        y: parent.height + 8
        width: 360
        height: 96
        padding: 10
        closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
        background: Rectangle { color: Theme.panel2; border.color: Theme.line; border.width: 1 }

        contentItem: ColumnLayout {
            spacing: 8
            Text {
                text: qsTr("LINK URL")
                color: Theme.muted
                font.family: Theme.mono
                font.pixelSize: 11
                font.letterSpacing: 1
            }
            Rectangle {
                Layout.fillWidth: true
                Layout.preferredHeight: 34
                color: Theme.panel
                border.color: Theme.line
                TextInput {
                    id: linkInput
                    anchors.fill: parent
                    anchors.margins: 8
                    verticalAlignment: TextInput.AlignVCenter
                    color: Theme.text
                    font.family: Theme.mono
                    font.pixelSize: 12
                    clip: true
                    focus: true
                        text: "https://"
                    }
            }
            RowLayout {
                spacing: 8
                Item { Layout.fillWidth: true }
                Button {
                    text: qsTr("Apply")
                    small: true
                    onClicked: {
                        if (fb.view && fb.view.session && fb.view.activeDoc && linkInput.text.length > 0)
                            fb.view.session.formatLink(linkInput.text, fb.view.activeDoc, fb.view.selStart, fb.view.selEnd)
                        linkPrompt.close()
                    }
                }
            }
        }
    }
}
