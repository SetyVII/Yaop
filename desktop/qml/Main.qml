import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtCore
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

    readonly property string rawInitialPage: (typeof initialPage !== "undefined" && initialPage) ? String(initialPage) : ""
    property string page: rawInitialPage.length > 0 ? rawInitialPage.split(":")[0]
                          : Theme.modern ? "calendar" : "home"
    property bool paletteOpen: false
    property bool captureOpen: false
    property string editorObjectId: (typeof initialObjectId !== "undefined" && initialObjectId) ? initialObjectId : ""
    property string pendingScrollBlockId: ""
    property string libraryType: (rawInitialPage.startsWith("library:") && rawInitialPage.length > 8)
                                 ? rawInitialPage.substring(8) : "all"
    property bool sidebarOpen: !Theme.compact && !rawInitialPage.endsWith(":rail")
    property bool focusMode: false

    // Tabs (slate top bar), like a browser: each tab is a place ("p:<page>" | "o:<objectId>") with
    // its own back / forward. Ctrl+click on anything that opens a place opens it in a new tab.
    readonly property string location: editorObjectId.length > 0 ? "o:" + editorObjectId : "p:" + page
    property string lastLocation: location
    property var backStack: []
    property var forwardStack: []
    property bool travelling: false
    property var tabs: [{ key: 0, loc: location, back: [], fwd: [], pane: "" }]
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
        Qt.callLater(focusPage)
    }
    function storeTab() {
        if (!tabs || !tabs[tabIndex]) return   // during start-up, before `tabs` exists
        const t = tabs.slice()
        t[tabIndex] = { key: tabs[tabIndex].key, loc: location, back: backStack, fwd: forwardStack, pane: paneObjectId }
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
    // Switching tabs swaps the side pane at once: sliding it would re-lay the page out on every
    // frame of the switch.
    property bool switchingTab: false
    function loadTab(i) {
        switchingTab = true
        Qt.callLater(() => { switchingTab = false })
        tabIndex = i
        paneObjectId = tabs[i].pane || ""
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
        tabs = tabs.concat([{ key: tabSeq++, loc: loc, back: [], fwd: [], pane: "" }])
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
    // Ctrl held during the click that asked for a place: new tab (modern shell, where tabs show).
    function wantsNewTab() { return Theme.modern && ws && (ws.keyModifiers() & Qt.ControlModifier) }
    function wantsPane() { return Theme.modern && ws && (ws.keyModifiers() & Qt.ShiftModifier) }

    // Side pane: one object beside the page, keeping the page in view. Shift+click opens
    // there; links inside the pane open in the page. Each tab keeps its own pane.
    property string paneObjectId: ""
    readonly property bool paneOpen: Theme.modern && paneObjectId.length > 0
    function openInPane(id) {
        if (id === editorObjectId) { showToast(qsTr("Already open in the page")); return }
        paneObjectId = id
        storeTab()
    }
    function closePane() { paneObjectId = ""; storeTab() }
    function paneToPage() {
        const id = paneObjectId
        paneObjectId = ""
        showObject(id)
    }
    onEditorObjectIdChanged: if (editorObjectId.length > 0 && editorObjectId === paneObjectId) closePane()
    // Views that share one editor session per object ask before closing it.
    function objectShownElsewhere(id, who) {
        // a hidden editor or pane may still hold the object it showed last (EditorPage.release)
        if (who !== editorPage && (editorObjectId === id || (!!editorPage.session && editorPage.session.objectId === id))) return true
        if (who !== sidePane.editor && (paneObjectId === id || (!!sidePane.editor.session && sidePane.editor.session.objectId === id))) return true
        const cal = calendarLoader.item
        return !!cal && who !== cal && !!cal.session && cal.session.objectId === id
    }
    function navigate(target) {
        if (wantsNewTab()) { newTab("p:" + target); return }
        editorObjectId = ""
        page = target
    }
    // Today = the calendar's Day view of today, with the caret in its note.
    function openToday() {
        editorObjectId = ""
        page = "calendar"
        if (calendarLoader.item) calendarLoader.item.openDay(calendarLoader.item.today())
        Qt.callLater(focusPage)
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
        case "graph": return "graph"
        case "types": return "shape"
        case "tags": return "tag"
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
        case "inbox": return qsTr("To review")
        case "tasks": return qsTr("Tasks")
        case "habits": return qsTr("Habits")
        case "library": return qsTr("Objects")
        case "sets": return qsTr("Queries")
        case "graph": return qsTr("Graph")
        case "types": return qsTr("Types")
        case "tags": return qsTr("Tags")
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
        else if (wantsPane()) openInPane(objectId)
        else showObject(objectId)
    }

    function openObjectAtBlock(objectId, blockId) {
        if (wantsPane()) { openInPane(objectId); return }
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
        editorPage.release()
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

    // Global shortcuts come from the one keymap table (src/keymap.cpp, docs/keyboard.md).
    // Editor keys that clash (Ctrl+D) win there through Keys.onShortcutOverride.
    Instantiator {
        model: keymap.bindings("global")
        delegate: Shortcut {
            required property var modelData
            sequences: modelData.keys
            context: modelData.appWide ? Qt.ApplicationShortcut : Qt.WindowShortcut
            enabled: !modelData.slateOnly || Theme.modern
            onActivated: app.runAction(modelData.id)
        }
    }

    // There is always a focused thing (docs/keyboard.md §3): on start, when the window comes back
    // (Alt+Tab) and after a page change hid the focused item, focus goes to the page's default.
    function ensureFocus() {
        const it = app.activeFocusItem
        if (it && it.parent && it !== app.contentItem && it.visible) return   // no parent = window root
        const host = pages.children[pages.currentIndex]
        const item = host && host.item !== undefined ? host.item : host
        if (!item) return
        if (typeof item.focusDefault === "function") item.focusDefault()
        else item.forceActiveFocus()
    }
    onActiveChanged: if (active) Qt.callLater(ensureFocus)
    // Opening a place (Enter, Ctrl+K, Ctrl+D, back, a tab) puts the caret in it: the page's default.
    function focusPage() {
        const host = pages.children[pages.currentIndex]
        const item = host && host.item !== undefined ? host.item : host
        if (item && typeof item.focusDefault === "function") item.focusDefault()
        else ensureFocus()
    }

    function showPage(p) { editorObjectId = ""; page = p }
    function runAction(id) {
        if (id.startsWith("tab.") && Number(id.substring(4)) > 0) {
            const n = Number(id.substring(4))
            switchTab(n === 9 ? tabs.length - 1 : n - 1)   // 9 = last, like browsers
            return
        }
        if (id.startsWith("page.")) { showPage(id.substring(5)); return }
        switch (id) {
        case "palette":
            captureOpen = false
            palette.paneMode = false
            paletteOpen = !paletteOpen
            break
        case "capture":
            paletteOpen = false
            captureOpen = true
            break
        case "today": openToday(); break
        case "back": goBack(); break
        case "forward": goForward(); break
        case "tab.new": newTab("p:calendar"); openToday(); break
        case "tab.close": closeTab(tabIndex); break
        case "tab.next": switchTab((tabIndex + 1) % tabs.length); break
        case "tab.prev": switchTab((tabIndex + tabs.length - 1) % tabs.length); break
        case "sidebar": sidebarOpen = !sidebarOpen; break
        case "editor.focusMode": focusMode = !focusMode; break
        case "settings": settingsSheet.open(); break
        case "lock": appLock.lockNow(); break
        case "privacy": Privacy.toggle(); break
        default: console.warn("keymap: no action for", id)
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
        case "graph": return 10
        case "types": return 11
        case "tags": return 12
        default: return 8
        }
    }

    AppBackground { anchors.fill: parent; z: -10 }

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        TitleBar {
            visible: !Theme.modern
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

        // Collapsed 64px Icon Rail in noir when sidebar is collapsed (Book.dc.html / WebLinks.dc.html)
        Rectangle {
            id: iconRail
            visible: Theme.noir && !app.sidebarOpen && !app.focusMode
            Layout.preferredWidth: visible ? 64 : 0
            Layout.fillHeight: true
            color: Theme.bg2

            Rectangle { anchors.top: parent.top; anchors.bottom: parent.bottom; anchors.right: parent.right; width: 1; color: Theme.line }

            ColumnLayout {
                anchors.fill: parent
                anchors.topMargin: 16; anchors.bottomMargin: 14
                spacing: 4

                // Square yaop mark (click expands sidebar)
                Item {
                    Layout.alignment: Qt.AlignHCenter
                    Layout.preferredWidth: 44; Layout.preferredHeight: 40
                    Layout.bottomMargin: 10
                    Rectangle {
                        anchors.centerIn: parent; width: 20; height: 20
                        color: "transparent"; border.width: 1.6; border.color: Theme.text
                        Rectangle { anchors.centerIn: parent; width: 8; height: 8; color: Theme.accent }
                    }
                    HoverHandler { id: railLogoH; cursorShape: Qt.PointingHandCursor }
                    TapHandler { onTapped: app.sidebarOpen = true }
                    ToolTip.visible: railLogoH.hovered; ToolTip.text: qsTr("Expand sidebar (Ctrl+B)"); ToolTip.delay: 300
                }

                Repeater {
                    model: [
                        { key: "calendar", icon: "journal", label: qsTr("Today") },
                        { key: "search", icon: "search", label: qsTr("Search (Ctrl+K)") },
                        { key: "library", icon: "layout-grid", label: qsTr("Objects") },
                        { key: "inbox", icon: "inbox", label: qsTr("To review") },
                        { key: "types", icon: "shape", label: qsTr("Types") },
                        { key: "tags", icon: "tag", label: qsTr("Tags") },
                        { key: "graph", icon: "graph", label: qsTr("Graph") },
                        { key: "tasks", icon: "task", label: qsTr("Tasks") },
                        { key: "habits", icon: "habit", label: qsTr("Habits") },
                        { key: "whiteboard", icon: "whiteboard", label: qsTr("Whiteboards") }
                    ]
                    delegate: Rectangle {
                        required property var modelData
                        readonly property bool on: app.editorObjectId.length === 0 && app.page === modelData.key
                        Layout.alignment: Qt.AlignHCenter
                        Layout.preferredWidth: 44; Layout.preferredHeight: 42
                        color: on ? Theme.accent : railBtnH.hovered ? Theme.panel2 : "transparent"
                        Behavior on color { ColorAnimation { duration: Theme.fast } }
                        TintedIcon {
                            anchors.centerIn: parent
                            name: modelData.icon; size: 18
                            color: on ? Theme.accentInk : railBtnH.hovered ? Theme.text : Theme.label
                        }
                        HoverHandler { id: railBtnH; cursorShape: Qt.PointingHandCursor }
                        TapHandler {
                            onTapped: {
                                if (modelData.key === "search") app.paletteOpen = true
                                else app.navigate(modelData.key)
                            }
                        }
                        ToolTip.visible: railBtnH.hovered; ToolTip.text: modelData.label; ToolTip.delay: 300
                    }
                }

                Item { Layout.fillHeight: true }

                IconButton {
                    Layout.alignment: Qt.AlignHCenter
                    implicitWidth: 40; implicitHeight: 40
                    icon: "plus"
                    onClicked: app.captureOpen = true
                    ToolTip.visible: hovered; ToolTip.text: qsTr("New (Ctrl+N)"); ToolTip.delay: 300
                }
                IconButton {
                    Layout.alignment: Qt.AlignHCenter
                    implicitWidth: 40; implicitHeight: 40
                    icon: "settings"
                    onClicked: settingsSheet.open()
                    ToolTip.visible: hovered; ToolTip.text: qsTr("Settings"); ToolTip.delay: 300
                }
            }
        }

        // Ctrl+B: the page takes its new width at once (one layout pass) and the sidebar slides
        // over it (200 ms, a transform only).
        Item {
            id: sidebarSlot
            readonly property bool shown: Theme.modern && app.sidebarOpen && !app.focusMode
            z: 3
            visible: Theme.modern && (shown || sidebarShift.x > -Theme.sidebarW + 0.5)
            Layout.preferredWidth: shown ? Theme.sidebarW : 0
            Layout.fillHeight: true

            ShellSidebar {
                transform: Translate {
                    id: sidebarShift
                    x: sidebarSlot.shown ? 0 : -Theme.sidebarW
                    Behavior on x {
                        NumberAnimation { duration: Theme.normal; easing.type: Easing.Bezier; easing.bezierCurve: Theme.easeStandard }
                    }
                }
                width: Theme.sidebarW
                height: parent.height
                currentPage: app.editorObjectId.length > 0 ? "editor" : app.page
                onNavigate: (target) => app.navigate(target)
                onTypeSelected: (typeId) => {
                    if (typeId === "tag") {
                        app.navigate("tags")
                        return
                    }
                    app.libraryType = typeId
                    app.editorObjectId = ""
                    app.page = "library"
                }
                onNewTypeRequested: {
                    app.editorObjectId = ""
                    app.page = "types"
                    Qt.callLater(() => { if (typesLoader.item) typesLoader.item.creating = true })
                }
                onNewRequested: app.captureOpen = true
                onSearchRequested: app.paletteOpen = true
                onSettingsRequested: settingsSheet.open()
                onHideRequested: app.sidebarOpen = false
                onOpenObjectRequested: (id) => app.openObject(id)
            }
        }

        Sidebar {
            id: sidebar
            visible: !Theme.modern
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
                app.page = "types"
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
                visible: Theme.modern
                Layout.fillWidth: true
                Layout.preferredHeight: Theme.paneBarH
                sidebarHidden: !app.sidebarOpen
                canBack: app.backStack.length > 0
                canForward: app.forwardStack.length > 0
                paneOpen: app.paneOpen
                tabs: app.tabViews
                currentTab: app.tabIndex
                onTabClicked: (i) => app.switchTab(i)
                onTabCloseRequested: (i) => app.closeTab(i)
                onNewTabRequested: { app.newTab("p:calendar"); app.openToday() }
                onBackRequested: app.goBack()
                onForwardRequested: app.goForward()
                onShowSidebarRequested: app.sidebarOpen = true
                onSplitPaneRequested: {
                    if (app.paneOpen) {
                        app.closePane()
                    } else {
                        app.captureOpen = false
                        palette.paneMode = true
                        app.paletteOpen = true
                    }
                }
            }

            TopBar {
                visible: !Theme.modern
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

                // The page, and beside it the side pane when one is open. shownW eases from 0 to
                // the pane's width (plus the gap) so the page gives way smoothly.
                Item {
                    id: pageArea
                    anchors.left: parent.left
                    anchors.top: parent.top
                    anchors.bottom: parent.bottom
                    // the final width at once; the pane slides over the freed space
                    width: parent.width - sidePane.targetW
                }
                SidePane {
                    id: sidePane
                    z: 2
                    anchors.right: parent.right
                    anchors.top: parent.top
                    anchors.bottom: parent.bottom
                    hostWidth: parent.width
                    objectId: app.paneOpen ? app.paneObjectId : ""
                    snap: app.switchingTab
                    appWindow: app
                    onCloseRequested: app.closePane()
                    onExpandRequested: app.paneToPage()
                }

                // Slate: the page is a rounded panel sitting on the canvas.
                Rectangle {
                    visible: Theme.slate
                    anchors.fill: pageArea
                    radius: Theme.radiusLg
                    color: Theme.panel
                    border.width: 1
                    border.color: Theme.line
                }

                GridBackdrop { anchors.fill: pageArea }

                StackLayout {
                    id: pages
                    anchors.fill: pageArea
                    // Changing page or tab is instant, like a browser: the page is not faded or moved.
                    // A fade over the whole page made every frame of it two times slower, on top of
                    // building the page.
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
                        narrowRail: Theme.narrow || app.paneOpen
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
                    Loader {
                        id: graphLoader
                        property bool everActive: false
                        active: everActive || pages.currentIndex === 10
                        onActiveChanged: if (active) everActive = true
                        sourceComponent: GraphPage {
                            appWindow: app
                            shown: pages.currentIndex === 10
                            onOpenObjectRequested: (objectId) => app.openObject(objectId)
                        }
                    }
                    Loader {
                        id: typesLoader
                        property bool everActive: false
                        active: everActive || pages.currentIndex === 11
                        onActiveChanged: if (active) everActive = true
                        sourceComponent: TypesPage {
                            appWindow: app
                            shown: pages.currentIndex === 11
                            onOpenTypeObjectsRequested: (typeId) => {
                                app.libraryType = typeId
                                app.editorObjectId = ""
                                app.page = "library"
                            }
                        }
                    }
                    Loader {
                        id: tagsLoader
                        property bool everActive: false
                        active: everActive || pages.currentIndex === 12
                        onActiveChanged: if (active) everActive = true
                        sourceComponent: TagsPage {
                            appWindow: app
                            shown: pages.currentIndex === 12
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
        onOpenObjectRequested: (objectId) => app.openObject(objectId)
        onOpenInPaneRequested: (objectId) => app.openInPane(objectId)
        onRequestClose: {
            palette.paneMode = false
            app.paletteOpen = false
        }
        // Esc / click outside close the popup itself; keep the flag in step.
        onClosed: if (app.paletteOpen) { palette.paneMode = false; app.paletteOpen = false }
    }

    QuickCapture {
        id: capture
        appWindow: app
        onRequestClose: app.captureOpen = false
        onClosed: if (app.captureOpen) app.captureOpen = false
    }

    SettingsSheet {
        id: settingsSheet
    }

    Toast { id: toast }

    FocusRing { }

    LockScreen { }

    Component.onCompleted: {
        Qt.callLater(ensureFocus)
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
        function onBrowserCaptureReceived(kind, title, objectId) {
            const prefix = kind === "image" ? qsTr("Image saved: ")
                         : kind === "note" ? qsTr("Note saved: ")
                         : qsTr("Web link saved: ")
            app.showToast(prefix + (title.length > 42 ? title.substring(0, 42) + "…" : title))
        }
    }
}
