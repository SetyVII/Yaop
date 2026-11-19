import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "."

// Sidebar (spec §8.2): views, TYPES with live counts, PINNED. Active row = surface-2 + ice icon.

Rectangle {
    id: sidebar

    property string currentPage: "home"
    signal navigate(string page)
    signal typeSelected(string typeId)
    signal searchRequested()
    signal settingsRequested()
    signal newTypeRequested()
    signal openObjectRequested(string objectId)

    readonly property int dataRev: ws ? ws.listRevision : 0
    readonly property bool compact: Theme.compact

    color: Theme.bg2
    implicitWidth: Theme.sidebarW

    Rectangle {
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        anchors.right: parent.right
        width: 1
        color: Theme.line
    }

    Flickable {
        anchors.fill: parent
        anchors.rightMargin: 1
        contentWidth: width
        contentHeight: col.implicitHeight + 24
        clip: true
        boundsBehavior: Flickable.StopAtBounds
        ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }

        ColumnLayout {
            id: col
            x: 10
            y: 14
            width: parent.width - 20
            spacing: 2

            Repeater {
                model: [
                    { key: "home", label: qsTr("Home"), icon: "home" },
                    { key: "journal", label: qsTr("Daily"), icon: "calendar" },
                    { key: "inbox", label: qsTr("To review"), icon: "circle" },
                    { key: "tasks", label: qsTr("Tasks"), icon: "circle-check" },
                    { key: "habits", label: qsTr("Habits"), icon: "circle-check" },
                    { key: "library", label: qsTr("Spaces"), icon: "layout-grid" },
                    { key: "sets", label: qsTr("Sets"), icon: "filter" },
                    { key: "whiteboard", label: qsTr("Whiteboards"), icon: "layout-board-split" }
                ]
                delegate: NavItem {
                    required property var modelData
                    compact: sidebar.compact
                    label: modelData.label
                    icon: modelData.icon
                    active: sidebar.currentPage === modelData.key
                    onClicked: sidebar.navigate(modelData.key)
                }
            }

            // Types: collapsed by default; once open only show types that have objects
            property bool typesOpen: false
            Text {
                visible: !sidebar.compact
                Layout.topMargin: 18
                Layout.leftMargin: 14
                text: (col.typesOpen ? "▾ " : "▸ ") + qsTr("Types")
                color: Theme.muted
                font.family: Theme.mono
                font.pixelSize: 11
                font.letterSpacing: Theme.track(1.6)
                font.weight: Font.Medium
                MouseArea { anchors.fill: parent; onClicked: col.typesOpen = !col.typesOpen }
            }
            NavItem {
                visible: !sidebar.compact
                label: qsTr("New type")
                icon: "plus"
                onClicked: sidebar.newTypeRequested()
            }
            Rectangle {
                visible: sidebar.compact
                Layout.topMargin: 10
                Layout.bottomMargin: 6
                Layout.fillWidth: true
                Layout.preferredHeight: 1
                color: Theme.line
            }

            Repeater {
                model: {
                    sidebar.dataRev
                    return ws ? ws.allTypes() : []
                }
                delegate: NavItem {
                    required property var modelData
                    compact: sidebar.compact
                    visible: (col.typesOpen || sidebar.compact) && count !== ""
                    label: modelData.name
                    icon: Theme.typeIcon(modelData.id)
                    dot: sidebar.compact ? "transparent" : "transparent"
                    count: {
                        sidebar.dataRev
                        const n = ws ? ws.objectsByType(modelData.id, 0).length : 0
                        return n > 0 ? String(n) : ""
                    }
                    active: false
                    onClicked: sidebar.typeSelected(modelData.id)
                }
            }

            // PINNED
            Text {
                visible: !sidebar.compact && pinnedRep.count > 0
                Layout.topMargin: 18
                Layout.leftMargin: 14
                text: qsTr("Pinned")
                color: Theme.muted
                font.family: Theme.mono
                font.pixelSize: 11
                font.letterSpacing: Theme.track(1.6)
                font.weight: Font.Medium
            }
            Repeater {
                id: pinnedRep
                model: {
                    sidebar.dataRev
                    return ws ? ws.pinnedObjects(8) : []
                }
                delegate: NavItem {
                    required property var modelData
                    visible: !sidebar.compact
                    compact: false
                    label: modelData.title
                    icon: Theme.typeIcon(modelData.typeId)
                    onClicked: sidebar.openObjectRequested(modelData.id)
                }
            }
        }
    }
}
