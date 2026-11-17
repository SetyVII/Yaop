import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "."

// Slate top bar: back / forward and where you are (icon + title). The sidebar toggle shows here
// only while the sidebar is hidden.
Item {
    id: bar

    property bool sidebarHidden: false
    property bool canBack: false
    property bool canForward: false
    property string icon: "calendar"
    property string title: ""
    signal backRequested()
    signal forwardRequested()
    signal showSidebarRequested()

    implicitHeight: Theme.paneBarH

    RowLayout {
        anchors.fill: parent
        anchors.leftMargin: 8
        anchors.rightMargin: 12
        spacing: 2

        IconButton {
            visible: bar.sidebarHidden
            icon: "layout-sidebar-right-collapse"
            onClicked: bar.showSidebarRequested()
            ToolTip.visible: hovered; ToolTip.text: qsTr("Show sidebar"); ToolTip.delay: 500
        }
        IconButton {
            icon: "chevron-left"
            opacity: bar.canBack ? 1 : 0.35
            enabled: bar.canBack
            onClicked: bar.backRequested()
            ToolTip.visible: hovered && enabled; ToolTip.text: qsTr("Back"); ToolTip.delay: 500
        }
        IconButton {
            icon: "chevron-right"
            opacity: bar.canForward ? 1 : 0.35
            enabled: bar.canForward
            onClicked: bar.forwardRequested()
            ToolTip.visible: hovered && enabled; ToolTip.text: qsTr("Forward"); ToolTip.delay: 500
        }
        TintedIcon {
            Layout.leftMargin: 12
            name: bar.icon
            size: 17
            color: Theme.dim
        }
        Text {
            Layout.leftMargin: 6
            Layout.fillWidth: true
            text: bar.title
            color: Theme.text
            font.family: Theme.body
            font.pixelSize: 15
            font.weight: Font.Medium
            elide: Text.ElideRight
        }
    }
}
