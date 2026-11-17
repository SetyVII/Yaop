import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "."

// Slate top bar: back / forward of the current tab, then the tabs. The sidebar toggle shows here
// only while the sidebar is hidden.
Item {
    id: bar

    property bool sidebarHidden: false
    property bool canBack: false
    property bool canForward: false
    property var tabs: []
    property int currentTab: 0
    signal tabClicked(int index)
    signal tabCloseRequested(int index)
    signal newTabRequested()
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
        ShellTabs {
            Layout.leftMargin: 10
            Layout.fillWidth: true
            Layout.fillHeight: true
            tabs: bar.tabs
            current: bar.currentTab
            onTabClicked: (i) => bar.tabClicked(i)
            onTabCloseRequested: (i) => bar.tabCloseRequested(i)
            onNewTabRequested: bar.newTabRequested()
        }
    }
}
