import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "."

ApplicationWindow {
    id: app
    width: 1440
    height: 900
    minimumWidth: 720
    minimumHeight: 520
    visible: true
    color: Theme.page
    title: "YAOP"

    property string page: (typeof initialPage !== "undefined" && initialPage) ? initialPage : "home"
    property bool paletteOpen: false
    property bool captureOpen: false
    property string editorObjectId: (typeof initialObjectId !== "undefined" && initialObjectId) ? initialObjectId : ""
    property string pendingScrollBlockId: ""
    property string libraryType: "all"

    // journal/quick object open flow
    function openObject(objectId) {
        pendingScrollBlockId = ""
        editorObjectId = objectId
        page = "editor"
    }

    function openObjectAtBlock(objectId, blockId) {
        pendingScrollBlockId = blockId !== undefined && blockId ? blockId : ""
        if (editorObjectId === objectId && page === "editor" && pendingScrollBlockId.length > 0) {
            editorPage.scrollToPendingBlock()
            return
        }
        editorObjectId = objectId
        page = "editor"
    }

    function closeEditor() {
        if (ws.hasEditorFor(editorObjectId))
            ws.closeObject(editorObjectId)
        editorObjectId = ""
        pendingScrollBlockId = ""
        page = "library"
    }

    // Quick capture with a type already chosen (e.g. "+ NEW BOOK" in the library).
    function openCaptureFor(typeId) {
        capture.selectedTypeId = typeId
        captureOpen = true
    }

    function showToast(text) {
        toast.show(text)
    }

    Binding { target: Theme; property: "winW"; value: app.width }

    FontLoader { source: "../assets/fonts/InterTight-Variable.ttf" }
    FontLoader { source: "../assets/fonts/IBMPlexMono-Regular.ttf" }
    FontLoader { source: "../assets/fonts/IBMPlexMono-Medium.ttf" }
    FontLoader { source: "../assets/fonts/IBMPlexMono-SemiBold.ttf" }
    FontLoader { source: "../assets/fonts/Doto-Variable.ttf" }

    Shortcut {
        sequence: "Ctrl+K"
        onActivated: {
            app.captureOpen = false
            app.paletteOpen = !app.paletteOpen
        }
    }

    Shortcut {
        sequence: "Ctrl+N"
        onActivated: {
            app.paletteOpen = false
            app.captureOpen = true
        }
    }

    Shortcut { sequence: "Ctrl+Shift+L"; onActivated: appLock.lockNow() }
    Shortcut { sequence: "Ctrl+Shift+H"; context: Qt.ApplicationShortcut; onActivated: Privacy.toggle() }
    Shortcut { sequence: "Ctrl+1"; onActivated: { app.editorObjectId = ""; app.page = "home" } }
    Shortcut { sequence: "Ctrl+2"; onActivated: { app.editorObjectId = ""; app.page = "journal" } }
    Shortcut { sequence: "Ctrl+3"; onActivated: { app.editorObjectId = ""; app.page = "library" } }
    Shortcut { sequence: "Ctrl+4"; onActivated: { app.editorObjectId = ""; app.page = "whiteboard" } }
    Shortcut { sequence: "Ctrl+5"; onActivated: { app.editorObjectId = ""; app.page = "tasks" } }
    Shortcut { sequence: "Ctrl+J"; onActivated: { app.editorObjectId = ""; app.page = "journal" } }
    Shortcut { sequence: "Ctrl+L"; onActivated: { app.editorObjectId = ""; app.page = "library" } }
    Shortcut {
        sequence: "Escape"
        onActivated: {
            app.paletteOpen = false
            app.captureOpen = false
            settingsSheet.close()
        }
    }

    onPaletteOpenChanged: {
        if (paletteOpen) palette.open()
        else palette.close()
    }
    onCaptureOpenChanged: {
        if (captureOpen) capture.open()
        else capture.close()
    }

    function pageIndex(p) {
        switch (p) {
        case "home": return 0
        case "journal": return 1
        case "library": return 2
        case "whiteboard": return 3
        case "tasks": return 4
        case "sets": return 5
        case "habits": return 6
        case "inbox": return 7
        default: return 8
        }
    }

    AppBackground { anchors.fill: parent; z: -10 }

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        TitleBar {
            Layout.fillWidth: true
            Layout.preferredHeight: Theme.topbarH
            onSearchRequested: app.paletteOpen = true
            onCaptureRequested: app.captureOpen = true
            onSyncRequested: ws.syncNow()
            onSettingsRequested: settingsSheet.open()
        }

    RowLayout {
        Layout.fillWidth: true
        Layout.fillHeight: true
        spacing: 0

        Sidebar {
            id: sidebar
            Layout.preferredWidth: Theme.sidebarW
            Layout.fillHeight: true
            currentPage: app.editorObjectId.length > 0 ? "editor" : app.page
            onNavigate: (target) => {
                app.editorObjectId = ""
                app.page = target
            }
            onTypeSelected: (typeId) => {
                app.libraryType = typeId
                app.editorObjectId = ""
                app.page = "library"
            }
            onNewTypeRequested: {
                app.editorObjectId = ""
                app.page = "library"
                Qt.callLater(() => Qt.callLater(() => { if (libraryLoader.item) libraryLoader.item.openTypeManager() }))
            }
            onSearchRequested: app.paletteOpen = true
            onSettingsRequested: settingsSheet.open()
            onOpenObjectRequested: (id) => app.openObject(id)
        }

        ColumnLayout {
            Layout.fillWidth: true
            Layout.fillHeight: true
            spacing: 0

            TopBar {
                Layout.fillWidth: true
                Layout.preferredHeight: Theme.paneBarH
                page: app.editorObjectId.length > 0 ? "editor" : app.page
                onSearchRequested: app.paletteOpen = true
                onNewRequested: app.captureOpen = true
                onSyncRequested: ws.syncNow()
                onSettingsRequested: settingsSheet.open()
            }

            Item {
                Layout.fillWidth: true
                Layout.fillHeight: true
                clip: true

                GridBackdrop { anchors.fill: parent }

                StackLayout {
                    id: pages
                    anchors.fill: parent
                    onCurrentIndexChanged: pageEnter.restart()
                    ParallelAnimation {
                        id: pageEnter
                        NumberAnimation { target: pages; property: "opacity"; from: 0; to: 1; duration: 160 }
                        NumberAnimation { target: pages; property: "anchors.topMargin"; from: 10; to: 0; duration: 160; easing.type: Easing.OutCubic }
                    }
                    currentIndex: app.pageIndex(app.editorObjectId.length > 0 ? "editor" : app.page)

                    HomePage {
                        appWindow: app
                        onPageRequested: (p) => { app.editorObjectId = ""; app.page = p }
                        onOpenObjectRequested: (objectId) => app.openObject(objectId)
                        onTypeSelected: (typeId) => {
                            app.libraryType = typeId
                            app.editorObjectId = ""
                            app.page = "library"
                        }
                    }
                    Loader {
                        id: journalLoader
                        property bool everActive: false
                        active: everActive || pages.currentIndex === 1
                        onActiveChanged: if (active) everActive = true
                        sourceComponent: JournalPage {
                            appWindow: app
                            onOpenObjectRequested: (objectId) => app.openObject(objectId)
                        }
                    }
                    Loader {
                        id: libraryLoader
                        property bool everActive: false
                        active: everActive || pages.currentIndex === 2
                        onActiveChanged: if (active) everActive = true
                        sourceComponent: LibraryPage {
                            appWindow: app
                            onOpenObjectRequested: (objectId) => app.openObject(objectId)
                        }
                    }
                    Loader {
                        id: whiteboardLoader
                        property bool everActive: false
                        active: everActive || pages.currentIndex === 3
                        onActiveChanged: if (active) everActive = true
                        sourceComponent: WhiteboardPage {
                            appWindow: app
                            onOpenObjectRequested: (objectId) => app.openObject(objectId)
                        }
                    }
                    Loader {
                        id: tasksLoader
                        property bool everActive: false
                        active: everActive || pages.currentIndex === 4
                        onActiveChanged: if (active) everActive = true
                        sourceComponent: TasksPage {
                            appWindow: app
                            onOpenObjectRequested: (objectId) => app.openObject(objectId)
                        }
                    }
                    Loader {
                        id: setsLoader
                        property bool everActive: false
                        active: everActive || pages.currentIndex === 5
                        onActiveChanged: if (active) everActive = true
                        sourceComponent: SetsPage {
                            appWindow: app
                            onOpenObjectRequested: (objectId) => app.openObject(objectId)
                        }
                    }
                    Loader {
                        id: habitsLoader
                        property bool everActive: false
                        active: everActive || pages.currentIndex === 6
                        onActiveChanged: if (active) everActive = true
                        sourceComponent: HabitsPage {
                            appWindow: app
                            onOpenObjectRequested: (objectId) => app.openObject(objectId)
                        }
                    }
                    Loader {
                        id: inboxLoader
                        property bool everActive: false
                        active: everActive || pages.currentIndex === 7
                        onActiveChanged: if (active) everActive = true
                        sourceComponent: InboxPage {
                            appWindow: app
                            onOpenObjectRequested: (objectId) => app.openObject(objectId)
                        }
                    }
                    EditorPage {
                        id: editorPage
                        objectName: "editorPage"
                        appWindow: app
                        objectId: app.editorObjectId
                        pendingBlockId: app.pendingScrollBlockId
                        onScrollHandled: app.pendingScrollBlockId = ""
                        onCloseRequested: app.closeEditor()
                    }
                }
            }
        }
    }

    } // window column

    CommandPalette {
        id: palette
        appWindow: app
        onOpenObjectRequested: app.openObject(objectId)
        onRequestClose: app.paletteOpen = false
    }

    QuickCapture {
        id: capture
        appWindow: app
        onRequestClose: app.captureOpen = false
    }

    SettingsSheet {
        id: settingsSheet
    }

    Toast { id: toast }

    LockScreen { }

    Component.onCompleted: {
        if (editorObjectId.length > 0)
            page = "editor"
        if (typeof initialOverlay !== "undefined" && initialOverlay === "palette")
            app.paletteOpen = true
        else if (typeof initialOverlay !== "undefined" && initialOverlay === "capture")
            app.captureOpen = true
    }

    Connections {
        target: ws
        function onJournalReady(objectId, isoDate) {
            if (app.page === "journal" && app.editorObjectId.length === 0 && objectId)
                app.openObject(objectId)
        }
        function onTaskCompleted(taskId) {
            app.showToast(qsTr("Task completed"))
        }
    }
}
