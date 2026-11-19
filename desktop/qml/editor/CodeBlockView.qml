import QtQuick
import "../"
import QtQuick.Controls
import QtQuick.Layouts

// Code block: plain mono TextEdit + language selector. Line numbers quietly on
// the gutter. Content commits to model.codeText (wire "text").

Item {
    id: cb

    property var view
    property int row
    property string blockId
    property string language: "kotlin"
    property string codeText: ""
    property bool loading: true

    implicitHeight: Math.max(60, edit.implicitHeight + 26 + 6 + 10 + 4)

    onCodeTextChanged: {
        if (loading)
            return
        if (!edit.activeFocus)
            edit.text = codeText
    }

    Component.onCompleted: {
        edit.text = codeText
        loading = false
    }

    Rectangle {
        anchors.fill: parent
        anchors.margins: 2
        radius: Theme.radius
        color: Qt.rgba(0.012, 0.031, 0.118, 0.55)     // ink box (spec §6)
        border.color: Theme.line
        border.width: 1
        clip: true

        // language strip, pinned to the top: the text below starts under it and can never overlap it
        Rectangle {
            id: strip
            anchors.top: parent.top
            anchors.left: parent.left
            anchors.right: parent.right
            height: 32
            color: "transparent"

            RowLayout {
                anchors.fill: parent
                anchors.leftMargin: 10
                anchors.rightMargin: 8
                spacing: 10

                Item { Layout.fillWidth: true }
                // language tag (pill): click cycles the language
                Rectangle {
                    Layout.preferredHeight: 22
                    Layout.preferredWidth: langLabel.implicitWidth + 20
                    radius: 11
                    color: langHover.hovered ? Theme.panel3 : Theme.panel2
                    Text {
                        id: langLabel
                        anchors.centerIn: parent
                        text: language.toUpperCase()
                        color: Theme.dim
                        font.family: Theme.mono
                        font.pixelSize: 11
                        font.letterSpacing: Theme.track(1)
                    }

                    HoverHandler { id: langHover; cursorShape: Qt.PointingHandCursor }
                    TapHandler {
                        onTapped: {
                            const langs = ["kotlin", "python", "javascript", "typescript", "cpp",
                                           "java", "go", "bash", "sql", "json", "yaml", "markdown"]
                            const at = Math.max(0, langs.indexOf(cb.language))
                            view.session.setCodeLanguage(cb.row, langs[(at + 1) % langs.length])
                        }
                    }
                }
            }
        }

        TextEdit {
            id: edit
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: strip.bottom
            anchors.topMargin: 2
            anchors.leftMargin: 12
            anchors.rightMargin: 10

            textFormat: TextEdit.PlainText
            wrapMode: TextEdit.WrapAtWordBoundaryOrAnywhere
            selectByMouse: true
            selectByKeyboard: true
            color: Theme.text
            selectionColor: Qt.rgba(0.75, 0.89, 1, 0.38)
            selectedTextColor: Theme.text
            font.family: Theme.code
            font.pixelSize: 13
            clip: true

            onActiveFocusChanged: {
                view.setFocusedBlock(cb.blockId, activeFocus)
            }
            onTextChanged: commitTimer.restart()
            Keys.onPressed: function(event) {
                if (event.key === Qt.Key_Escape) {
                    event.accepted = true
                    view.closePopups()
                }
                // Enter inside code: insert a real newline (only exit on esc/ctrl+enter)
                if ((event.key === Qt.Key_Return || event.key === Qt.Key_Enter)
                    && (event.modifiers & Qt.ControlModifier)) {
                    event.accepted = true
                    view.session.insertBlockAfter(cb.row, "paragraph")
                }
                if (event.key === Qt.Key_Tab) {
                    event.accepted = true
                    const c = edit.cursorPosition
                    edit.insert(c, "    ")
                }
            }

            Timer {
                id: commitTimer
                interval: 420
                onTriggered: {
                    if (loading)
                        return
                    // Commit code text through the model path set for code blocks.
                    view.session.commitCodeText(cb.row, edit.text)
                }
            }

            // Gutter numbers
            Column {
                id: gutter
                x: 8
                y: 4
                spacing: 2
                Text {
                    text: "1"
                    visible: edit.length > 0
                    color: Theme.faint
                    font.family: Theme.code
                    font.pixelSize: 11
                }
                Text {
                    text: qsTr("—")
                    visible: edit.length === 0
                    color: Theme.faint
                    font.family: Theme.code
                    font.pixelSize: 11
                }
            }
        }
    }
}
