import QtQuick
import QtQuick.Controls
import "."

// Menu item in the app's look (dark sheet, accent highlight) with an optional shortcut hint.
MenuItem {
    id: item
    property string hint: ""
    property bool danger: false
    implicitHeight: 34
    leftPadding: 12
    rightPadding: 12
    contentItem: Item {
        implicitWidth: lbl.implicitWidth + (hintLbl.visible ? hintLbl.implicitWidth + 24 : 0)
        Text {
            id: lbl
            anchors.left: parent.left
            anchors.verticalCenter: parent.verticalCenter
            width: parent.width - (hintLbl.visible ? hintLbl.implicitWidth + 16 : 0)
            text: item.text
            elide: Text.ElideRight
            color: !item.enabled ? Theme.faint
                 : item.highlighted ? Theme.accentInk
                 : item.danger ? Theme.danger : Theme.bodyText
            font.family: Theme.body
            font.pixelSize: 13
        }
        Text {
            id: hintLbl
            visible: item.hint.length > 0
            anchors.right: parent.right
            anchors.verticalCenter: parent.verticalCenter
            text: item.hint
            color: item.highlighted ? Theme.accentInk : Theme.label
            font.family: Theme.mono
            font.pixelSize: 11
        }
    }
    background: Rectangle {
        color: item.highlighted && item.enabled ? (item.danger ? Theme.danger : Theme.accent) : "transparent"
        Behavior on color { ColorAnimation { duration: Theme.fast } }
    }
}
