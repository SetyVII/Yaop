import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "."

// Sidebar matching docs/design/mockup/AppDesktop.dc.html:
// Brand mark (square outline + accent inner square + "yaop"), bordered Search bar (Ctrl K),
// primary nav sections (Hoy, Calendario, Por revisar, Objetos, Tipos, Etiquetas, Consultas, Grafo,
// Tareas, Hábitos, Pizarras, Papelera), Fijados, Tipos con contador, and Sync footer.
Rectangle {
    id: sidebar

    property string currentPage: "calendar"
    signal navigate(string page)
    signal typeSelected(string typeId)
    signal searchRequested()
    signal settingsRequested()
    signal shortcutsRequested()
    signal newRequested()
    signal newTypeRequested()
    signal hideRequested()
    signal openObjectRequested(string objectId)

    readonly property int dataRev: ws ? ws.listRevision : 0
    readonly property var typeCounts: { dataRev; return ws ? ws.typeCounts() : ({}) }
    // the type list changes rarely: rebuilding its rows on every save made them flicker
    property int typesRev: 0
    Connections { target: ws; function onTypesChanged() { sidebar.typesRev++ } }

    color: Theme.bg2
    implicitWidth: Theme.sidebarW

    // Right hairline border in noir
    Rectangle {
        visible: Theme.noir
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        anchors.right: parent.right
        width: 1
        color: Theme.line
    }

    component SectionLabel: Text {
        Layout.topMargin: 20
        Layout.bottomMargin: 6
        Layout.leftMargin: 12
        color: Theme.label
        font.family: Theme.mono
        font.pixelSize: 11
        font.letterSpacing: Theme.track(0.5)
        font.weight: Font.Normal
    }

    ColumnLayout {
        anchors.fill: parent
        anchors.rightMargin: Theme.noir ? 1 : 0
        spacing: 0

        // Brand mark + hide
        RowLayout {
            Layout.fillWidth: true
            Layout.preferredHeight: Theme.noir ? 56 : Theme.paneBarH
            Layout.leftMargin: 16
            Layout.rightMargin: 10
            spacing: 10

            // Signature square brand mark (AppDesktop.dc.html)
            Rectangle {
                visible: Theme.noir
                Layout.preferredWidth: 18
                Layout.preferredHeight: 18
                color: "transparent"
                border.width: 1.6
                border.color: Theme.text
                Rectangle {
                    anchors.centerIn: parent
                    width: 8
                    height: 8
                    color: Theme.accent
                }
            }
            Image {
                visible: !Theme.noir
                Layout.preferredWidth: 20
                Layout.preferredHeight: 20
                source: "qrc:/assets/yaop.svg"
                sourceSize: Qt.size(40, 40)
                fillMode: Image.PreserveAspectFit
            }
            Text {
                Layout.fillWidth: true
                text: Theme.noir ? "yaop" : "YAOP"
                color: Theme.text
                font.family: Theme.body
                font.pixelSize: 16
                font.weight: Font.Medium
                elide: Text.ElideRight
            }
            IconButton {
                implicitWidth: 30; implicitHeight: 30
                icon: "plus"
                onClicked: sidebar.newRequested()
                ToolTip.visible: hovered; ToolTip.text: qsTr("New (Ctrl+N)"); ToolTip.delay: 400
            }
            IconButton {
                implicitWidth: 30; implicitHeight: 30
                icon: "layout-sidebar-left-collapse"
                onClicked: sidebar.hideRequested()
                ToolTip.visible: hovered; ToolTip.text: qsTr("Hide sidebar (Ctrl+B)"); ToolTip.delay: 400
            }
        }

        // Bordered Search trigger (AppDesktop.dc.html: Buscar · Ctrl K)
        Rectangle {
            Layout.fillWidth: true
            Layout.leftMargin: 12
            Layout.rightMargin: 12
            Layout.topMargin: 4
            Layout.bottomMargin: 14
            Layout.preferredHeight: 38
            radius: Theme.radius
            color: searchHover.hovered ? Theme.panel2 : "transparent"
            border.width: 1
            border.color: searchHover.hovered ? Theme.line3 : Theme.line2

            RowLayout {
                anchors.fill: parent
                anchors.leftMargin: 12
                anchors.rightMargin: 12
                spacing: 10
                TintedIcon { name: "search"; size: 15; color: Theme.label }
                Text {
                    Layout.fillWidth: true
                    text: qsTr("Search")
                    color: Theme.label
                    font.family: Theme.body
                    font.pixelSize: 14
                }
                Text {
                    text: Theme.caps("CTRL K")
                    color: Theme.label
                    font.family: Theme.mono
                    font.pixelSize: 10
                }
            }
            HoverHandler { id: searchHover; cursorShape: Qt.PointingHandCursor }
            TapHandler { onTapped: sidebar.searchRequested() }
        }

        Flickable {
            Layout.fillWidth: true
            Layout.fillHeight: true
            contentWidth: width
            contentHeight: col.implicitHeight + 20
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }

            ColumnLayout {
                id: col
                x: 10
                width: parent.width - 20
                spacing: 2

                ShellNavRow {
                    label: qsTr("Today"); icon: "journal"; shortcutHint: "D"
                    active: sidebar.currentPage === "calendar"
                    onClicked: sidebar.navigate("calendar")
                }
                ShellNavRow {
                    label: qsTr("Objects"); icon: "layout-grid"; shortcutHint: "O"
                    active: sidebar.currentPage === "library"
                    onClicked: sidebar.navigate("library")
                }
                ShellNavRow {
                    label: qsTr("To review"); icon: "inbox"
                    count: { sidebar.dataRev; const n = ws ? ws.inbox().length : 0; return n > 0 ? String(n) : "" }
                    active: sidebar.currentPage === "inbox"
                    onClicked: sidebar.navigate("inbox")
                }
                ShellNavRow {
                    label: qsTr("Types"); icon: "shape"; shortcutHint: "T"
                    active: sidebar.currentPage === "types"
                    onClicked: sidebar.navigate("types")
                }
                ShellNavRow {
                    label: qsTr("Queries"); icon: "filter"; shortcutHint: "Q"
                    active: sidebar.currentPage === "sets"
                    onClicked: sidebar.navigate("sets")
                }
                ShellNavRow {
                    label: qsTr("Graph"); icon: "graph"; shortcutHint: "G"
                    active: sidebar.currentPage === "graph"
                    onClicked: sidebar.navigate("graph")
                }
                ShellNavRow {
                    label: qsTr("Tasks"); icon: "task"
                    count: {
                        sidebar.dataRev
                        const n = ws ? ws.tasksFor("today").length + ws.tasksFor("upcoming").length : 0
                        return n > 0 ? String(n) : ""
                    }
                    active: sidebar.currentPage === "tasks"
                    onClicked: sidebar.navigate("tasks")
                }
                ShellNavRow {
                    label: qsTr("Habits"); icon: "habit"
                    active: sidebar.currentPage === "habits"
                    onClicked: sidebar.navigate("habits")
                }
                ShellNavRow {
                    label: qsTr("Whiteboards"); icon: "whiteboard"
                    active: sidebar.currentPage === "whiteboard"
                    onClicked: sidebar.navigate("whiteboard")
                }
                ShellNavRow {
                    label: qsTr("Trash"); icon: "trash"
                    active: false
                    onClicked: sidebar.typeSelected("trash")
                }

                SectionLabel { visible: pinnedRep.count > 0; text: Theme.caps(qsTr("PINNED")) }
                Repeater {
                    id: pinnedRep
                    model: { sidebar.dataRev; return ws ? ws.pinnedObjects(12) : [] }
                    delegate: ShellNavRow {
                        required property var modelData
                        label: modelData.title || qsTr("Untitled")
                        icon: Theme.typeIcon(modelData.typeId)
                        emoji: modelData.iconEmoji || ""
                        tint: Theme.typeColor(modelData.typeId)
                        roundDot: modelData.typeId === "person"
                        onClicked: sidebar.openObjectRequested(modelData.id)
                    }
                }

                // Types with active objects
                Item {
                    Layout.fillWidth: true
                    Layout.topMargin: 20
                    Layout.bottomMargin: 4
                    Layout.preferredHeight: 22
                    HoverHandler { id: typesHover }
                    RowLayout {
                        anchors.fill: parent
                        anchors.leftMargin: 12
                        anchors.rightMargin: 4
                        spacing: 6
                        Text {
                            Layout.fillWidth: true
                            text: Theme.caps(qsTr("ALL TYPES"))
                            color: Theme.label
                            font.family: Theme.mono
                            font.pixelSize: 11
                            font.letterSpacing: Theme.track(0.5)
                        }
                        IconButton {
                            implicitWidth: 24; implicitHeight: 24
                            opacity: typesHover.hovered ? 1 : 0.45
                            icon: "plus"
                            onClicked: sidebar.newTypeRequested()
                        }
                    }
                }
                Repeater {
                    model: { sidebar.typesRev; return ws ? ws.allTypes() : [] }
                    delegate: ShellNavRow {
                        required property var modelData
                        readonly property int n: sidebar.typeCounts[modelData.id] || 0
                        readonly property bool builtIn: Theme.typeIcon(modelData.id) !== "shape"
                        readonly property string rawIcon: modelData.icon ? String(modelData.icon) : ""
                        visible: (n > 0 || modelData.id === "tag") && modelData.id !== "task"
                        active: modelData.id === "tag" && sidebar.currentPage === "tags"
                        label: modelData.name
                        icon: (!builtIn && rawIcon && /^[a-z0-9-]+$/i.test(rawIcon)) ? rawIcon : Theme.typeIcon(modelData.id)
                        emoji: (!builtIn && rawIcon && /[^\x00-\x7F]/.test(rawIcon)) ? rawIcon : ""
                        tint: {
                            if (builtIn) return Theme.typeColor(modelData.id)
                            const c = modelData.color ? String(modelData.color) : ""
                            if (c.startsWith("#")) return c
                            if (Theme.paletteHex[c] !== undefined) return Theme.paletteColor(c)
                            return Theme.typeColor(modelData.id)
                        }
                        count: String(n)
                        onClicked: sidebar.typeSelected(modelData.id)
                    }
                }
            }
        }

        // Footer: sync status + settings/privacy tools
        Rectangle { Layout.fillWidth: true; Layout.preferredHeight: 1; color: Theme.line }
        RowLayout {
            Layout.fillWidth: true
            Layout.preferredHeight: 46
            Layout.leftMargin: 14
            Layout.rightMargin: 8
            spacing: 8

            // Sync state (AppDesktop.dc.html: 6px accent square + mono status)
            RowLayout {
                Layout.fillWidth: true
                spacing: 8
                Rectangle {
                    Layout.preferredWidth: 6
                    Layout.preferredHeight: 6
                    radius: Theme.noir ? 0 : 3
                    color: !ws || !ws.connected ? Theme.late
                         : (ws.syncing || ws.pendingCount > 0) ? Theme.violet
                         : (Theme.noir ? Theme.accent : Theme.ok)
                }
                Text {
                    Layout.fillWidth: true
                    text: Theme.caps(!ws || !ws.connected ? qsTr("LOCAL ONLY")
                          : ws.syncing ? qsTr("SYNCING")
                          : ws.pendingCount > 0 ? qsTr("%1 QUEUED").arg(ws.pendingCount)
                          : qsTr("SYNCED"))
                    color: Theme.label
                    font.family: Theme.mono
                    font.pixelSize: 11
                    elide: Text.ElideRight
                }
                HoverHandler { cursorShape: Qt.PointingHandCursor }
                TapHandler { onTapped: if (ws) ws.syncNow() }
            }

            IconButton {
                implicitWidth: 30; implicitHeight: 30
                icon: Privacy.active ? "eye-off" : "eye"
                active: Privacy.active
                onClicked: Privacy.toggle()
                ToolTip.visible: hovered; ToolTip.text: qsTr("Image privacy (Ctrl+Shift+H)"); ToolTip.delay: 400
            }
            IconButton {
                implicitWidth: 30; implicitHeight: 30
                icon: "keyboard"
                onClicked: sidebar.shortcutsRequested()
                ToolTip.visible: hovered; ToolTip.text: qsTr("Keyboard shortcuts (%1)").arg(keymap.keysText("shortcuts")); ToolTip.delay: 400
            }
            IconButton {
                implicitWidth: 30; implicitHeight: 30
                icon: "settings"
                onClicked: sidebar.settingsRequested()
                ToolTip.visible: hovered; ToolTip.text: qsTr("Settings (Ctrl+,)"); ToolTip.delay: 400
            }
        }
    }
}
