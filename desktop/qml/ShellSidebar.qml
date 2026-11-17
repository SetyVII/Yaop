import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "."

// Slate sidebar: full height. Vault on top, New + Search, the daily places (Calendar, Inbox,
// Tasks), the other views, pinned objects, all types with counts; icon tools in the footer.
Rectangle {
    id: sidebar

    property string currentPage: "calendar"
    signal navigate(string page)
    signal typeSelected(string typeId)
    signal searchRequested()
    signal settingsRequested()
    signal newRequested()
    signal newTypeRequested()
    signal hideRequested()
    signal openObjectRequested(string objectId)

    readonly property int dataRev: ws ? ws.listRevision : 0

    color: Theme.bg2
    implicitWidth: Theme.sidebarW

    component SectionLabel: Text {
        Layout.topMargin: 18
        Layout.bottomMargin: 2
        Layout.leftMargin: 10
        color: Theme.muted
        font.family: Theme.body
        font.pixelSize: 12
        font.weight: Font.Medium
    }

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        // vault + hide
        RowLayout {
            Layout.fillWidth: true
            Layout.preferredHeight: Theme.paneBarH
            Layout.leftMargin: 14
            Layout.rightMargin: 8
            spacing: 10
            Image {
                Layout.preferredWidth: 20
                Layout.preferredHeight: 20
                source: "qrc:/assets/yaop.svg"
                sourceSize: Qt.size(40, 40)
                fillMode: Image.PreserveAspectFit
            }
            Text {
                Layout.fillWidth: true
                text: "YAOP"
                color: Theme.text
                font.family: Theme.body
                font.pixelSize: 15
                font.weight: Font.DemiBold
                elide: Text.ElideRight
            }
            IconButton {
                icon: "layout-sidebar-left-collapse"
                onClicked: sidebar.hideRequested()
                ToolTip.visible: hovered
                ToolTip.text: qsTr("Hide sidebar")
                ToolTip.delay: 500
            }
        }

        // New + Search
        RowLayout {
            Layout.fillWidth: true
            Layout.leftMargin: 8
            Layout.rightMargin: 8
            Layout.bottomMargin: 8
            spacing: 6
            Rectangle {
                Layout.fillWidth: true
                Layout.preferredHeight: 36
                radius: Theme.radius
                color: newHover.hovered ? Theme.panel2 : Theme.panel
                border.width: 1
                border.color: Theme.line
                RowLayout {
                    anchors.fill: parent
                    anchors.leftMargin: 12
                    spacing: 8
                    TintedIcon { name: "plus"; size: 16; color: Theme.text }
                    Text {
                        Layout.fillWidth: true
                        text: qsTr("New")
                        color: Theme.text
                        font.family: Theme.body
                        font.pixelSize: 14
                    }
                }
                HoverHandler { id: newHover; cursorShape: Qt.PointingHandCursor }
                TapHandler { onTapped: sidebar.newRequested() }
            }
            Rectangle {
                Layout.preferredWidth: 36
                Layout.preferredHeight: 36
                radius: Theme.radius
                color: searchHover.hovered ? Theme.panel2 : Theme.panel
                border.width: 1
                border.color: Theme.line
                TintedIcon { anchors.centerIn: parent; name: "search"; size: 16; color: Theme.dim }
                HoverHandler { id: searchHover; cursorShape: Qt.PointingHandCursor }
                TapHandler { onTapped: sidebar.searchRequested() }
                ToolTip.visible: searchHover.hovered
                ToolTip.text: qsTr("Search (Ctrl+K)")
                ToolTip.delay: 500
            }
        }

        Flickable {
            Layout.fillWidth: true
            Layout.fillHeight: true
            contentWidth: width
            contentHeight: col.implicitHeight + 16
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }

            ColumnLayout {
                id: col
                x: 8
                width: parent.width - 16
                spacing: 1

                ShellNavRow {
                    label: qsTr("Calendar"); icon: "calendar"
                    active: sidebar.currentPage === "calendar"
                    onClicked: sidebar.navigate("calendar")
                }
                ShellNavRow {
                    label: qsTr("Inbox"); icon: "inbox"
                    count: { sidebar.dataRev; const n = ws ? ws.inbox().length : 0; return n > 0 ? String(n) : "" }
                    active: sidebar.currentPage === "inbox"
                    onClicked: sidebar.navigate("inbox")
                }
                ShellNavRow {
                    label: qsTr("Tasks"); icon: "circle-check"
                    count: {
                        sidebar.dataRev
                        const n = ws ? ws.tasksFor("today").length + ws.tasksFor("upcoming").length : 0
                        return n > 0 ? String(n) : ""
                    }
                    active: sidebar.currentPage === "tasks"
                    onClicked: sidebar.navigate("tasks")
                }

                SectionLabel { text: qsTr("Views") }
                Repeater {
                    model: [
                        { key: "home", label: qsTr("Home"), icon: "home" },
                        { key: "habits", label: qsTr("Habits"), icon: "circle-check" },
                        { key: "library", label: qsTr("Spaces"), icon: "layout-grid" },
                        { key: "sets", label: qsTr("Sets"), icon: "filter" },
                        { key: "whiteboard", label: qsTr("Whiteboards"), icon: "layout-board-split" }
                    ]
                    delegate: ShellNavRow {
                        required property var modelData
                        label: modelData.label
                        icon: modelData.icon
                        active: sidebar.currentPage === modelData.key
                        onClicked: sidebar.navigate(modelData.key)
                    }
                }

                SectionLabel { visible: pinnedRep.count > 0; text: qsTr("Pinned") }
                Repeater {
                    id: pinnedRep
                    model: { sidebar.dataRev; return ws ? ws.pinnedObjects(12) : [] }
                    delegate: ShellNavRow {
                        required property var modelData
                        label: modelData.title
                        icon: Theme.typeIcon(modelData.typeId)
                        emoji: modelData.iconEmoji || ""
                        tint: Theme.typeColor(modelData.typeId)
                        onClicked: sidebar.openObjectRequested(modelData.id)
                    }
                }

                // All types: "+" shows on hover
                Item {
                    Layout.fillWidth: true
                    Layout.topMargin: 18
                    Layout.preferredHeight: 24
                    HoverHandler { id: typesHover }
                    RowLayout {
                        anchors.fill: parent
                        anchors.leftMargin: 10
                        anchors.rightMargin: 6
                        spacing: 6
                        TintedIcon { name: "shape"; size: 14; color: Theme.muted }
                        Text {
                            Layout.fillWidth: true
                            text: qsTr("All types")
                            color: Theme.muted
                            font.family: Theme.body
                            font.pixelSize: 12
                            font.weight: Font.Medium
                        }
                        IconButton {
                            opacity: typesHover.hovered ? 1 : 0
                            icon: "plus"
                            onClicked: sidebar.newTypeRequested()
                        }
                    }
                }
                Repeater {
                    model: { sidebar.dataRev; return ws ? ws.allTypes() : [] }
                    delegate: ShellNavRow {
                        required property var modelData
                        readonly property int n: { sidebar.dataRev; return ws ? ws.objectsByType(modelData.id, 0).length : 0 }
                        visible: n > 0 && modelData.id !== "task"
                        label: modelData.name
                        icon: Theme.typeIcon(modelData.id)
                        tint: Theme.typeColor(modelData.id)
                        count: String(n)
                        onClicked: sidebar.typeSelected(modelData.id)
                    }
                }
            }
        }

        // footer tools
        Rectangle { Layout.fillWidth: true; Layout.preferredHeight: 1; color: Theme.line }
        RowLayout {
            Layout.fillWidth: true
            Layout.preferredHeight: 48
            Layout.leftMargin: 8
            Layout.rightMargin: 12
            spacing: 2
            IconButton {
                icon: "settings"
                onClicked: sidebar.settingsRequested()
                ToolTip.visible: hovered; ToolTip.text: qsTr("Settings"); ToolTip.delay: 500
            }
            IconButton {
                icon: "sun"
                onClicked: Theme.setScheme("sky")
                ToolTip.visible: hovered; ToolTip.text: qsTr("Switch to the Sky look"); ToolTip.delay: 500
            }
            IconButton {
                icon: Privacy.active ? "eye-off" : "eye"
                active: Privacy.active
                onClicked: Privacy.toggle()
                ToolTip.visible: hovered; ToolTip.text: qsTr("Image privacy (Ctrl+Shift+H)"); ToolTip.delay: 500
            }
            Item { Layout.fillWidth: true }
            // sync state: click syncs now
            RowLayout {
                spacing: 6
                Rectangle {
                    Layout.preferredWidth: 7
                    Layout.preferredHeight: 7
                    radius: 4
                    color: !ws || !ws.connected ? Theme.late
                         : (ws.syncing || ws.pendingCount > 0) ? Theme.violet : Theme.ok
                }
                Text {
                    text: !ws || !ws.connected ? qsTr("Offline")
                          : ws.syncing ? qsTr("Syncing")
                          : ws.pendingCount > 0 ? qsTr("%1 queued").arg(ws.pendingCount)
                          : qsTr("Synced")
                    color: Theme.muted
                    font.family: Theme.body
                    font.pixelSize: 12
                }
                HoverHandler { cursorShape: Qt.PointingHandCursor }
                TapHandler { onTapped: if (ws) ws.syncNow() }
            }
        }
    }
}
