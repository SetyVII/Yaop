import QtQuick
import QtQuick.Layouts
import QtQuick.Controls
import "../"

// Math block: rendered TeX via image://tex (pkm::tex painter), inline editor
// popup for the latex source.

Item {
    id: mb

    function activate() { editPopup.open(); return false }

    property var view
    property int row
    property string blockId
    property string latexText: ""
    property bool loading: true
    property string texUrl: ""

    implicitHeight: contentCol.implicitHeight + 14

    function texUrlFor(latex, px, display) {
        return "image://tex/" + encodeURIComponent(latex) + "~" + px + "~" + (display ? 1 : 0)
    }

    onLatexTextChanged: {
        texUrl = texUrlFor(latexText, 22, true)
    }

    Component.onCompleted: {
        texUrl = texUrlFor(latexText, 22, true)
        loading = false
    }

    Column {
        id: contentCol
        anchors.left: parent.left
        anchors.leftMargin: 4
        anchors.right: parent.right
        spacing: 8

        Item {
            width: parent.width
            height: latexText.length === 0 ? 34 : Math.max(48, mathImage.implicitHeight + 16)

            Image {
                id: mathImage
                anchors.centerIn: parent
                source: mb.texUrl
                // Never wider than the block: a long formula scales down instead of overflowing.
                width: implicitWidth > 0 ? Math.min(implicitWidth, parent.width - 16) : 0
                height: implicitWidth > 0 ? implicitHeight * width / implicitWidth : 0
                visible: latexText.length > 0 && status === Image.Ready
                fillMode: Image.PreserveAspectFit
                smooth: true
            }

            Text {
                visible: latexText.length === 0
                anchors.left: parent.left
                anchors.leftMargin: 6
                anchors.verticalCenter: parent.verticalCenter
                text: qsTr("Empty formula — click to edit")
                color: Theme.faint
                font.family: Theme.mono
                font.pixelSize: 11
            }

            Text {
                visible: latexText.length > 0 && mathImage.status === Image.Error
                anchors.centerIn: parent
                text: qsTr("formula preview unavailable")
                color: Theme.faint
                font.family: Theme.mono
                font.pixelSize: 11
            }

            TapHandler {
                onTapped: editPopup.open()
            }
        }
    }

    // ---------------- latex source popup ----------------
    Popup {
        id: editPopup
        anchors.centerIn: parent.overlay === undefined ? undefined : null
        x: Math.max(8, (parent.width - width) / 2)
        y: 24
        width: Math.min(460, parent.width - 40)
        height: 158
        padding: 1
        modal: true
        focus: true
        closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside

        background: Rectangle {
            color: Theme.panel2
            border.color: Theme.line2
            border.width: 1
        }

        contentItem: ColumnLayout {
            spacing: 8

            Text {
                text: Theme.caps(qsTr("LATEX"))
                color: Theme.accent
                font.family: Theme.mono
                font.pixelSize: 11
                font.letterSpacing: Theme.track(1.4)
            }

            Rectangle {
                Layout.fillWidth: true
                Layout.preferredHeight: 68
                color: Theme.panel
                border.color: Theme.line
                border.width: 1

                TextEdit {
                    id: latexEdit
                    anchors.fill: parent
                    anchors.margins: 8
                    text: mb.latexText
                    textFormat: TextEdit.PlainText
                    wrapMode: TextEdit.WrapAnywhere
                    selectByMouse: true
                    color: Theme.text
                    font.family: Theme.code
                    font.pixelSize: 13
                    clip: true
                    Keys.onPressed: function(event) {
                        if ((event.key === Qt.Key_Return || event.key === Qt.Key_Enter)
                            && (event.modifiers & Qt.ControlModifier)) {
                            event.accepted = true
                            applyLatex()
                        }
                    }
                }
            }

            RowLayout {
                spacing: 8
                Item { Layout.fillWidth: true }
                Button {
                    text: qsTr("Apply")
                    small: true
                    onClicked: applyLatex()
                }
                Button {
                    text: qsTr("Cancel")
                    small: true
                    onClicked: editPopup.close()
                }
            }
        }

        function applyLatex() {
            view.session.setLatex(mb.row, latexEdit.text)
            mb.texUrl = mb.texUrlFor(latexEdit.text, 26, true)
            editPopup.close()
        }
    }
}
