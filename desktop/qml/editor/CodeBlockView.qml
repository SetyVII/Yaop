import QtQuick
import "../"
import QtQuick.Controls
import QtQuick.Layouts

// Code block (docs/design/mockup/BlockTypes.dc.html):
// #050505 box, 1px #1F1F1F border, 36px header with language selector on the left
// and Copy button with accent feedback on the right.
Item {
    id: cb

    function activate() {
        edit.forceActiveFocus()
        edit.cursorPosition = edit.length
        return true
    }

    property var view
    property int row
    property string blockId
    property string language: "kotlin"
    property string codeText: ""
    property bool loading: true
    property bool copied: false

    implicitHeight: Math.max(76, edit.implicitHeight + 56)

    onCodeTextChanged: {
        if (loading) return
        if (!edit.activeFocus) edit.text = codeText
    }
    Component.onCompleted: {
        edit.text = codeText
        loading = false
    }

    Timer { id: copiedTimer; interval: 1400; onTriggered: cb.copied = false }

    Rectangle {
        anchors.fill: parent
        anchors.margins: 2
        radius: Theme.radius
        color: Theme.noir ? Theme.ink : Qt.rgba(0.012, 0.031, 0.118, 0.55)
        border.color: Theme.line
        border.width: 1
        clip: true

        // 36px header bar (BlockTypes.dc.html)
        Rectangle {
            id: strip
            anchors.top: parent.top
            anchors.left: parent.left
            anchors.right: parent.right
            height: 36
            color: "transparent"

            Rectangle { anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom; height: 1; color: Theme.lineSubtle }

            RowLayout {
                anchors.fill: parent
                anchors.leftMargin: 14
                anchors.rightMargin: 8
                spacing: 10

                // Language selector (click cycles)
                Rectangle {
                    Layout.preferredHeight: 24
                    Layout.preferredWidth: langLabel.implicitWidth + 12
                    color: langHover.hovered ? Theme.panel2 : "transparent"
                    Text {
                        id: langLabel
                        anchors.centerIn: parent
                        text: Theme.caps(cb.language)
                        color: langHover.hovered ? Theme.text : Theme.muted
                        font.family: Theme.mono
                        font.pixelSize: 11
                    }
                    HoverHandler { id: langHover; cursorShape: Qt.PointingHandCursor }
                    TapHandler {
                        onTapped: {
                            const langs = ["kotlin", "cpp", "go", "python", "javascript", "typescript",
                                           "sql", "bash", "json", "yaml", "markdown"]
                            const at = Math.max(0, langs.indexOf(cb.language))
                            view.session.setCodeLanguage(cb.row, langs[(at + 1) % langs.length])
                        }
                    }
                }

                Item { Layout.fillWidth: true }

                // Copy button (BlockTypes.dc.html)
                Rectangle {
                    Layout.preferredHeight: 26
                    Layout.preferredWidth: copyLbl.implicitWidth + 18
                    radius: Theme.radiusSm
                    color: cb.copied ? Theme.accent : (copyHover.hovered ? Theme.panel2 : "transparent")
                    border.width: 1
                    border.color: cb.copied ? Theme.accent : (copyHover.hovered ? Theme.line3 : Theme.line2)
                    Text {
                        id: copyLbl
                        anchors.centerIn: parent
                        text: cb.copied ? qsTr("Copied") : qsTr("Copy")
                        color: cb.copied ? Theme.accentInk : (copyHover.hovered ? Theme.text : Theme.dim)
                        font.family: Theme.body
                        font.pixelSize: 12
                    }
                    HoverHandler { id: copyHover; cursorShape: Qt.PointingHandCursor }
                    TapHandler {
                        onTapped: {
                            if (typeof ws !== "undefined" && ws) ws.copyTextToClipboard(edit.text)
                            cb.copied = true
                            copiedTimer.restart()
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
            anchors.topMargin: 10
            anchors.leftMargin: 16
            anchors.rightMargin: 14

            textFormat: TextEdit.PlainText
            wrapMode: TextEdit.WrapAtWordBoundaryOrAnywhere
            selectByMouse: true
            selectByKeyboard: true
            color: Theme.bodyText
            selectionColor: Theme.accentDim
            selectedTextColor: Theme.text
            font.family: Theme.code
            font.pixelSize: 13
            clip: true

            onActiveFocusChanged: view.setFocusedBlock(cb.blockId, activeFocus)
            onTextChanged: commitTimer.restart()
            Keys.onPressed: function(event) {
                if (event.key === Qt.Key_Escape) {
                    event.accepted = true
                    view.closePopups()
                    view.selectRow(cb.row)
                    return
                }
                if ((event.key === Qt.Key_Return || event.key === Qt.Key_Enter)
                    && (event.modifiers & Qt.ControlModifier)) {
                    event.accepted = true
                    view.session.insertBlockAfter(cb.row, "paragraph")
                }
                if (event.key === Qt.Key_Tab) {
                    event.accepted = true
                    edit.insert(edit.cursorPosition, "    ")
                }
            }

            Timer {
                id: commitTimer
                interval: 420
                onTriggered: {
                    if (!loading) view.session.commitCodeText(cb.row, edit.text)
                }
            }
        }
    }
}
