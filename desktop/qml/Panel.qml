import QtQuick
import QtQuick.Layouts
import "."

Rectangle {
    id: root
    property string label: ""
    property string title: ""
    property string action: ""
    signal actionClicked()

    // Inner padding of the body. Children go into a ColumnLayout (use Layout.* on them): the panel
    // height follows its content, so pages never hardcode a height or a header offset.
    property int padding: 14
    property int bodySpacing: 10
    default property alias content: body.data

    // Surface card: sky = plain fill, no border (spec §4); slate = lifted and outlined like cards.
    color: Theme.card
    border.width: Theme.slate ? 1 : 0
    border.color: Theme.cardBorder
    radius: Theme.radiusLg
    clip: true
    implicitHeight: panelCol.implicitHeight + 2

    ColumnLayout {
        id: panelCol
        anchors.fill: parent
        anchors.margins: 1
        spacing: 0

        Item {
            id: header
            Layout.fillWidth: true
            Layout.preferredHeight: (root.label.length > 0 || root.title.length > 0) ? Theme.panelHeaderH : 0
            visible: Layout.preferredHeight > 0

            RowLayout {
                anchors.fill: parent
                anchors.leftMargin: 16
                anchors.rightMargin: 16
                spacing: 10

                Text {
                    text: root.label
                    visible: text.length > 0
                    color: Theme.slate ? Theme.text : Theme.dim
                    font.family: Theme.mono
                    font.pixelSize: Theme.slate ? 14 : 11
                    font.letterSpacing: Theme.track(1.6)
                    font.weight: Theme.slate ? Font.DemiBold : Font.Medium
                }

                Text {
                    text: root.title
                    visible: text.length > 0
                    color: Theme.text
                    font.family: Theme.body
                    font.pixelSize: 14
                    font.weight: Font.Medium
                }

                Item { Layout.fillWidth: true }

                Text {
                    text: root.action
                    visible: text.length > 0
                    color: actionHover.hovered ? Theme.ice : Theme.dim
                    font.family: Theme.mono
                    font.pixelSize: Theme.slate ? 13 : 11
                    font.letterSpacing: Theme.track(1.2)

                    HoverHandler { id: actionHover; cursorShape: Qt.PointingHandCursor }
                    TapHandler { onTapped: root.actionClicked() }
                }
            }

        }

        ColumnLayout {
            id: body
            Layout.fillWidth: true
            Layout.margins: root.padding
            spacing: root.bodySpacing
        }
    }
}
