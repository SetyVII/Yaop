import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../"

// Canvas (whiteboard) block: shows a quiet preview strip and opens the
// whiteboard surface. The canvas_data JSON is edited there.

Item {
    id: cb

    property var view
    property int row
    property string blockId

    signal openBoardRequested(string blockId)

    implicitHeight: 96

    Rectangle {
        anchors.fill: parent
        anchors.leftMargin: 2
        anchors.rightMargin: 2
        anchors.topMargin: 3
        anchors.bottomMargin: 3
        color: canvasHover.hovered ? "#0D0D0C" : "#090908"
        border.color: canvasHover.hovered ? Theme.line2 : Theme.line
        border.width: 1

        // dotted canvas texture
        Grid {
            anchors.fill: parent
            anchors.margins: 10
            columns: Math.max(6, Math.floor(parent.width / 22))
            rowSpacing: 16
            columnSpacing: 16
            Repeater {
                model: 60
                delegate: Rectangle {
                    width: 1
                    height: 1
                    color: Theme.faint
                }
            }
        }

        HoverHandler { id: canvasHover; cursorShape: Qt.PointingHandCursor }
        TapHandler { onTapped: cb.openBoardRequested(cb.blockId) }

        RowLayout {
            anchors.fill: parent
            anchors.margins: 14
            spacing: 10

            ColumnLayout {
                spacing: 3
                Layout.fillWidth: true

                Text {
                    text: qsTr("WHITEBOARD")
                    color: Theme.board
                    font.family: Theme.mono
                    font.pixelSize: 11
                    font.letterSpacing: 1.6
                }
                Text {
                    text: qsTr("Infinite canvas · open to draw nodes")
                    color: Theme.muted
                    font.family: Theme.body
                    font.pixelSize: 12
                }
            }

            Text {
                text: "↗"
                color: Theme.faint
                font.family: Theme.mono
                font.pixelSize: 13
            }
        }
    }
}
