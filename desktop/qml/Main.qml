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

    property string page: (typeof initialPage !== "undefined" && initialPage) ? initialPage
                          : Theme.slate ? "calendar" : "home"
    property bool paletteOpen: false
    property bool captureOpen: false
    property string editorObjectId: (typeof initialObjectId !== "undefined" && initialObjectId) ? initialObjectId : ""
    property string pendingScrollBlockId: ""
    property string libraryType: "all"
    property bool sidebarOpen: !Theme.compact

    // Tabs (slate top bar), like a browser: each tab is a place ("p:<page>" | "o:<objectId>") with
    // its own back / forward. Ctrl+click on anything that opens a place opens it in a new tab.
    readonly property string location: editorObjectId.length > 0 ? "o:" + editorObjectId : "p:" + page
    property string lastLocation: location
    property var backStack: []
    property var forwardStack: []
    property bool travelling: false
    property var tabs: [{ key: 0, loc: location, back: [], fwd: [] }]
    property int tabIndex: 0
    property int tabSeq: 1   // stable keys, so the strip animates only the tab that came or went
    onLocationChanged: {
        // "p:editor" only exists between clearing the object and setting the next page
        if (location === "p:editor") return
        if (!travelling && lastLocation !== location) {
            backStack = backStack.concat([lastLocation]).slice(-50)
            forwardStack = []
        }
        lastLocation = location
        storeTab()
    }
    function storeTab() {
        if (!tabs || !tabs[tabIndex]) return   // during start-up, before `tabs` exists
        const t = tabs.slice()
        t[tabIndex] = { key: tabs[tabIndex].key, loc: location, back: backStack, fwd: forwardStack }
        tabs = t
    }
    function goTo(loc) {
        travelling = true
        if (loc.startsWith("o:")) showObject(loc.substring(2))
        else { editorObjectId = ""; page = loc.substring(2) }
        travelling = false
        storeTab()
    }
    function goBack() {
        if (backStack.length === 0) return
        const loc = backStack[backStack.length - 1]
        backStack = backStack.slice(0, -1)
        forwardStack = forwardStack.concat([location])
        goTo(loc)
    }
    function goForward() {
        if (forwardStack.length === 0) return
        const loc = forwardStack[forwardStack.length - 1]
        forwardStack = forwardStack.slice(0, -1)
        backStack = backStack.concat([location])
        goTo(loc)
    }
    function loadTab(i) {
        tabIndex = i
        backStack = tabs[i].back
        forwardStack = tabs[i].fwd
        goTo(tabs[i].loc)
    }
    function switchTab(i) {
        if (i < 0 || i >= tabs.length || i === tabIndex) return
        storeTab()
        loadTab(i)
    }
    function newTab(loc) {
        storeTab()
        tabs = tabs.concat([{ key: tabSeq++, loc: loc, back: [], fwd: [] }])
        loadTab(tabs.length - 1)
    }
    function closeTab(i) {
        if (tabs.length <= 1 || i < 0 || i >= tabs.length) return
        if (i !== tabIndex) storeTab()
        const t = tabs.slice()
        t.splice(i, 1)
        const next = i < tabIndex ? tabIndex - 1 : i === tabIndex ? Math.min(i, t.length - 1) : tabIndex
        tabs = t
        loadTab(next)
    }
    // Ctrl held during the click that asked for a place: new tab (slate only, where tabs show).
    function wantsNewTab() { return Theme.slate && ws && ws.ctrlHeld() }
    function navigate(target) {
        if (wantsNewTab()) { newTab("p:" + target); return }
        editorObjectId = ""
        page = target
    }
    function openToday() {
        editorObjectId = ""
        page = "calendar"
        if (calendarLoader.item) calendarLoader.item.showToday()
    }

    // Where a place is, for the tabs.
    function placeIcon(loc) {
        if (loc.startsWith("o:")) {
            const o = ws ? ws.objectSummary(loc.substring(2)) : null
            return Theme.typeIcon(o && o.typeId ? o.typeId : "")
        }
        switch (loc.substring(2)) {
        case "calendar": return "calendar"
        case "inbox": return "inbox"
        case "tasks": return "circle-check"
        case "habits": return "circle-check"
        case "library": return "layout-grid"
        case "sets": return "filter"
        case "whiteboard": return "layout-board-split"
        case "journal": return "calendar"
        default: return "home"
        }
    }
    function placeTitle(loc) {
        if (loc.startsWith("o:")) {
            const o = ws ? ws.objectSummary(loc.substring(2)) : null
            return o && o.title ? o.title : qsTr("Untitled")
        }
        switch (loc.substring(2)) {
        case "calendar": return calendarLoader.item ? calendarLoader.item.placeTitle : qsTr("Today")
        case "inbox": return qsTr("Inbox")
        case "tasks": return qsTr("Tasks")
        case "habits": return qsTr("Habits")
        case "library": return qsTr("Spaces")
        case "sets": return qsTr("Sets")
        case "whiteboard": return qsTr("Whiteboards")
        case "journal": return qsTr("Daily")
        default: return qsTr("Home")
        }
    }
    readonly property var tabViews: {
        ws ? ws.listRevision : 0
        return tabs.map(t => ({ key: t.key, icon: placeIcon(t.loc), title: placeTitle(t.loc) }))
    }

    // journal/quick object open flow
    function showObject(objectId) {
        pendingScrollBlockId = ""
        editorObjectId = objectId
        page = "editor"
    }
    function openObject(objectId) {
        if (wantsNewTab()) newTab("o:" + objectId)
        else showObject(objectId)
    }

    function openObjectAtBlock(objectId, blockId) {
        if (wantsNewTab()) newTab("o:" + objectId)
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
    FontLoader { source: "../assets/fonts/Inter-Regular.otf" }
    FontLoader { source: "../assets/fonts/Inter-Medium.otf" }
    FontLoader { source: "../assets/fonts/Inter-SemiBold.otf" }
    FontLoader { source: "../assets/fonts/Inter-Bold.otf" }

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
    Shortcut { sequence: "Ctrl+D"; onActivated: app.openToday() }
    Shortcut { sequence: "Ctrl+T"; enabled: Theme.slate; onActivated: { app.newTab("p:calendar"); app.openToday() } }
    Shortcut { sequence: "Ctrl+W"; enabled: Theme.slate; onActivated: app.closeTab(app.tabIndex) }
    Shortcut { sequence: "Ctrl+Tab"; enabled: Theme.slate; onActivated: app.switchTab((app.tabIndex + 1) % app.tabs.length) }
    Shortcut { sequence: "Ctrl+Shift+Tab"; enabled: Theme.slate; onActivated: app.switchTab((app.tabIndex + app.tabs.length - 1) % app.tabs.length) }
    Shortcut { sequence: "Alt+Left"; onActivated: app.goBack() }
    Shortcut { sequence: "Alt+Right"; onActivated: app.goForward() }
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
        case "calendar": return 9
        default: return 8
        }
    }

    AppBackground { anchors.fill: parent; z: -10 }

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        TitleBar {
            visible: !Theme.slate
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

        ShellSidebar {
            visible: Theme.slate && app.sidebarOpen
            Layout.preferredWidth: Theme.sidebarW
            Layout.fillHeight: true
            currentPage: app.editorObjectId.length > 0 ? "editor" : app.page
            onNavigate: (target) => app.navigate(target)
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
            onNewRequested: app.captureOpen = true
            onSearchRequested: app.paletteOpen = true
            onSettingsRequested: settingsSheet.open()
            onHideRequested: app.sidebarOpen = false
            onOpenObjectRequested: (id) => app.openObject(id)
        }

        Sidebar {
            id: sidebar
            visible: !Theme.slate
            Layout.preferredWidth: Theme.sidebarW
            Layout.fillHeight: true
            currentPage: app.editorObjectId.length > 0 ? "editor" : app.page
            onNavigate: (target) => app.navigate(target)
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

            ShellTopBar {
                visible: Theme.slate
                Layout.fillWidth: true
                Layout.preferredHeight: Theme.paneBarH
                sidebarHidden: !app.sidebarOpen
                canBack: app.backStack.length > 0
                canForward: app.forwardStack.length > 0
                tabs: app.tabViews
                currentTab: app.tabIndex
                onTabClicked: (i) => app.switchTab(i)
                onTabCloseRequested: (i) => app.closeTab(i)
                onNewTabRequested: { app.newTab("p:calendar"); app.openToday() }
                onBackRequested: app.goBack()
                onForwardRequested: app.goForward()
                onShowSidebarRequested: app.sidebarOpen = true
            }

            TopBar {
                visible: !Theme.slate
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
                Layout.rightMargin: Theme.slate ? 10 : 0
                Layout.bottomMargin: Theme.slate ? 10 : 0
                Layout.leftMargin: Theme.slate && !app.sidebarOpen ? 10 : 0
                clip: true

                // Slate: the page is a rounded panel sitting on the canvas.
                Rectangle {
                    visible: Theme.slate
                    anchors.fill: parent
                    radius: Theme.radiusLg
                    color: Theme.panel
                    border.width: 1
                    border.color: Theme.line
                }

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
                        onPageRequested: (p) => app.navigate(p)
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
                    Loader {
                        id: calendarLoader
                        property bool everActive: false
                        active: everActive || pages.currentIndex === 9
                        onActiveChanged: if (active) everActive = true
                        sourceComponent: CalendarPage {
                            appWindow: app
                            shown: pages.currentIndex === 9
                            onOpenObjectRequested: (objectId) => app.openObject(objectId)
                        }
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
