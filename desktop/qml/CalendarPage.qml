import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtCore
import "."
import "editor"

// Calendar, Day view: what is due that day, the daily note edited in place, what was created
// on and refers to the day; the month and the day's habits on the right. Today's note is
// started on open; other days wait for "Start the daily note" so browsing leaves no empty notes.
Item {
    id: page

    property var appWindow: null
    property bool shown: false
    signal openObjectRequested(string objectId)

    property date day: today()
    function today() { const d = new Date(); return new Date(d.getFullYear(), d.getMonth(), d.getDate(), 12) }
    function shift(n) { day = new Date(day.getFullYear(), day.getMonth(), day.getDate() + n, 12) }
    function showToday() { day = today() }
    // one step of the current view: a day, three days, a week or a month
    function step(dir) {
        if (view === "month") day = new Date(day.getFullYear(), day.getMonth() + dir, 1, 12)
        else shift(dir * (view === "week" ? 7 : view === "three" ? 3 : 1))
    }
    function openDay(d) { day = d; prefs.view = "day" }

    readonly property string view: prefs.view
    readonly property date rangeStart: {
        if (view === "day") return day
        if (view === "three") return day
        const fd = Qt.locale().firstDayOfWeek % 7
        if (view === "week") return new Date(day.getFullYear(), day.getMonth(), day.getDate() - (day.getDay() - fd + 7) % 7, 12)
        const first = new Date(day.getFullYear(), day.getMonth(), 1, 12)
        return new Date(first.getFullYear(), first.getMonth(), 1 - (first.getDay() - fd + 7) % 7, 12)
    }
    readonly property int rangeDays: view === "month" ? 42 : view === "week" ? 7 : view === "three" ? 3 : 1
    readonly property var gridItems: {
        dataRev
        if (!ws || view === "day") return []
        const end = new Date(rangeStart.getFullYear(), rangeStart.getMonth(), rangeStart.getDate() + rangeDays - 1, 12)
        const want = { note: prefs.markNote, task: prefs.markTasks, created: prefs.markCreated }
        return ws.calendarItems(Qt.formatDate(rangeStart, "yyyy-MM-dd"), Qt.formatDate(end, "yyyy-MM-dd"))
                 .filter(it => want[it.kind])
    }

    readonly property string iso: Qt.formatDate(day, "yyyy-MM-dd")
    readonly property bool isToday: iso === Qt.formatDate(new Date(), "yyyy-MM-dd")
    readonly property string placeTitle: view !== "day" ? qsTr("Calendar")
                                       : isToday ? qsTr("Today") : day.toLocaleDateString(Qt.locale(), Locale.ShortFormat)
    function isoWeek(d) {
        const t = new Date(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()))
        t.setUTCDate(t.getUTCDate() + 4 - (t.getUTCDay() || 7))
        return Math.ceil(((t - Date.UTC(t.getUTCFullYear(), 0, 1)) / 864e5 + 1) / 7)
    }

    // data
    property int habitRev: 0
    Connections { target: ws; function onObjectPropertiesChanged() { page.habitRev++ } }
    readonly property int dataRev: (shown && ws) ? ws.listRevision : 0
    readonly property string noteId: { dataRev; return ws ? ws.journalLocalFor(iso) : "" }
    readonly property var dueTasks: {
        dataRev
        if (!ws) return []
        return ws.tasksFor("today").concat(ws.tasksFor("upcoming"), ws.tasksFor("done"))
                 .filter(t => String(t.dueDate).substring(0, 10) === page.iso)
    }
    readonly property var created: { dataRev; return ws ? ws.objectsCreatedOn(iso) : [] }
    readonly property var references: { dataRev; return ws ? ws.backlinksFor("daily-" + iso) : [] }

    // per device
    Settings {
        id: prefs
        category: "calendar"
        property bool showMonth: true
        property string view: "day"     // day | three | week | month
        property bool markNote: true
        property bool markTasks: true
        property bool markCreated: true
        property bool markRefs: true
    }

    // The daily note's editor session. Released while the page is hidden; never closed under
    // the editor page when that one shows the same note.
    property var session: null
    function syncSession() {
        if (shown && isToday && !noteId && ws) {
            ws.journalFor(iso)   // today's note is started on open
            return               // noteId changes and calls back
        }
        const want = shown ? noteId : ""
        if (session && session.objectId === want) return
        if (session) {
            const old = session.objectId
            session = null
            if (!appWindow || !appWindow.objectShownElsewhere(old, page)) ws.closeObject(old)
        }
        if (want) session = ws.openObject(want)
    }
    // Day view: the caret goes into the day's note (created for today) — docs/keyboard.md §3.
    function focusDefault() {
        if (view === "day") dayBlocks.focusStart()
        else page.forceActiveFocus()
    }
    onNoteIdChanged: Qt.callLater(syncSession)
    onShownChanged: Qt.callLater(syncSession)
    onIsoChanged: Qt.callLater(syncSession)

    component SectionHead: RowLayout {
        id: sh
        property string icon: ""
        property string title: ""
        default property alias extra: tail.data
        spacing: 10
        TintedIcon { name: sh.icon; size: 17; color: Theme.dim }
        Text {
            text: sh.title
            color: Theme.text
            font.family: Theme.body
            font.pixelSize: 15
            font.weight: Font.DemiBold
        }
        Item { Layout.fillWidth: true }
        Row { id: tail; spacing: 6 }
    }

    component GhostButton: Rectangle {
        id: gb
        property string icon: ""
        property string label: ""
        signal clicked()
        implicitWidth: gbRow.implicitWidth + 22
        implicitHeight: 32
        radius: Theme.radius
        color: gbHover.hovered ? Theme.panel2 : "transparent"
        border.width: 1
        border.color: Theme.line
        Row {
            id: gbRow
            anchors.centerIn: parent
            spacing: 6
            TintedIcon { visible: !!gb.icon; anchors.verticalCenter: parent.verticalCenter; name: gb.icon || "plus"; size: 15; color: Theme.dim }
            Text {
                anchors.verticalCenter: parent.verticalCenter
                text: gb.label
                color: Theme.dim
                font.family: Theme.body
                font.pixelSize: 13
            }
        }
        HoverHandler { id: gbHover; cursorShape: Qt.PointingHandCursor }
        TapHandler { onTapped: gb.clicked() }
    }

    component ObjectLine: Rectangle {
        id: ol
        property var obj: ({})
        property string lead: ""
        property string sub: ""
        signal open()
        Layout.fillWidth: true
        implicitHeight: sub ? 52 : 40
        radius: Theme.radius
        color: olHover.hovered ? Theme.panel2 : "transparent"
        RowLayout {
            anchors.fill: parent
            anchors.leftMargin: 8
            anchors.rightMargin: 10
            spacing: 12
            Text {
                visible: ol.lead.length > 0
                Layout.preferredWidth: 64
                text: ol.lead
                color: Theme.muted
                font.family: Theme.body
                font.pixelSize: 13
                horizontalAlignment: Text.AlignRight
            }
            Rectangle {
                Layout.preferredWidth: 22
                Layout.preferredHeight: 22
                radius: 6
                readonly property color c: Theme.typeColor(ol.obj.typeId || "")
                color: ol.obj.iconEmoji ? "transparent" : Qt.rgba(c.r, c.g, c.b, 0.16)
                TintedIcon { visible: !ol.obj.iconEmoji; anchors.centerIn: parent; name: Theme.typeIcon(ol.obj.typeId || ""); size: 14; color: parent.c }
                Text { visible: !!ol.obj.iconEmoji; anchors.centerIn: parent; text: ol.obj.iconEmoji || ""; font.pixelSize: 14 }
            }
            ColumnLayout {
                Layout.fillWidth: true
                spacing: 2
                Text {
                    Layout.fillWidth: true
                    text: ol.obj.title || qsTr("Untitled")
                    color: Theme.text
                    font.family: Theme.body
                    font.pixelSize: 14
                    elide: Text.ElideRight
                }
                Text {
                    Layout.fillWidth: true
                    visible: ol.sub.length > 0
                    text: ol.sub
                    color: Theme.muted
                    font.family: Theme.body
                    font.pixelSize: 12
                    elide: Text.ElideRight
                }
            }
        }
        HoverHandler { id: olHover; cursorShape: Qt.PointingHandCursor }
        TapHandler { onTapped: ol.open() }
    }

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        // header: day navigation, Show, views, month toggle
        RowLayout {
            Layout.fillWidth: true
            Layout.preferredHeight: 64
            Layout.leftMargin: 20
            Layout.rightMargin: 16
            spacing: 6

            Text {
                visible: page.view !== "day"
                Layout.rightMargin: 10
                text: Qt.locale().standaloneMonthName(page.day.getMonth()) + " " + page.day.getFullYear()
                color: Theme.text
                font.family: Theme.display
                font.pixelSize: 20
                font.weight: Font.Bold
                font.letterSpacing: -0.3
            }
            IconButton { icon: "chevron-left"; onClicked: page.step(-1) }
            GhostButton { label: qsTr("Today"); onClicked: page.showToday() }
            IconButton { icon: "chevron-right"; onClicked: page.step(1) }
            Item { Layout.fillWidth: true }

            GhostButton {
                icon: "eye"
                label: qsTr("Show")
                border.width: 0
                onClicked: showMenu.popup()
                Menu {
                    id: showMenu
                    MenuItem { text: qsTr("Daily note"); checkable: true; checked: prefs.markNote; onTriggered: prefs.markNote = !prefs.markNote }
                    MenuItem { text: qsTr("Tasks"); checkable: true; checked: prefs.markTasks; onTriggered: prefs.markTasks = !prefs.markTasks }
                    MenuItem { text: qsTr("Created"); checkable: true; checked: prefs.markCreated; onTriggered: prefs.markCreated = !prefs.markCreated }
                    MenuItem { text: qsTr("References"); checkable: true; checked: prefs.markRefs; onTriggered: prefs.markRefs = !prefs.markRefs }
                }
            }

            // views; the pill slides to the chosen one (accent fill in noir — Calendar.dc.html)
            Rectangle {
                Layout.preferredHeight: 36
                implicitWidth: viewsRow.implicitWidth + (Theme.noir ? 0 : 8)
                radius: Theme.radius
                color: Theme.page
                border.width: 1
                border.color: Theme.line2
                Rectangle {
                    id: viewPill
                    readonly property Item target: { viewsRow.width; return viewsRep.itemAt(viewsRep.keys.indexOf(page.view)) }
                    x: viewsRow.x + (target ? target.x : 0)
                    y: Theme.noir ? 0 : viewsRow.y
                    width: target ? target.width : 0
                    height: Theme.noir ? parent.height : 28
                    radius: Theme.radiusSm
                    color: Theme.noir ? Theme.accent : Theme.panel2
                    border.width: Theme.noir ? 0 : 1
                    border.color: Theme.line2
                    Behavior on x { NumberAnimation { duration: Theme.normal; easing.type: Easing.Bezier; easing.bezierCurve: Theme.easeStandard } }
                    Behavior on width { NumberAnimation { duration: Theme.normal; easing.type: Easing.Bezier; easing.bezierCurve: Theme.easeStandard } }
                }
                Row {
                    id: viewsRow
                    anchors.centerIn: parent
                    spacing: Theme.noir ? 0 : 2
                    Repeater {
                        id: viewsRep
                        readonly property var keys: ["month", "week", "three", "day"]
                        model: [
                            { key: "month", label: qsTr("Month") },
                            { key: "week", label: qsTr("Week") },
                            { key: "three", label: qsTr("Three days") },
                            { key: "day", label: qsTr("Day") }
                        ]
                        delegate: Item {
                            required property var modelData
                            readonly property bool on: modelData.key === page.view
                            width: vt.implicitWidth + 24
                            height: Theme.noir ? 36 : 28
                            Text {
                                id: vt
                                anchors.centerIn: parent
                                text: modelData.label
                                color: parent.on ? (Theme.noir ? Theme.accentInk : Theme.text) : vHover.hovered ? Theme.text : Theme.muted
                                font.family: Theme.body
                                font.pixelSize: 13
                                Behavior on color { ColorAnimation { duration: 240 * Theme.motion } }
                            }
                            HoverHandler { id: vHover; cursorShape: Qt.PointingHandCursor }
                            TapHandler { onTapped: prefs.view = modelData.key }
                        }
                    }
                }
            }

            IconButton {
                visible: page.view !== "month"
                icon: "calendar"
                active: prefs.showMonth
                onClicked: prefs.showMonth = !prefs.showMonth
                ToolTip.visible: hovered; ToolTip.text: prefs.showMonth ? qsTr("Hide the month") : qsTr("Show the month"); ToolTip.delay: 500
            }
        }
        Rectangle { Layout.fillWidth: true; Layout.preferredHeight: 1; color: Theme.line }

        RowLayout {
            id: viewBody
            Layout.fillWidth: true
            Layout.fillHeight: true
            spacing: 0

            // Switching Month / Week / Three days / Day: the new view fades in from 98.5 % (200 ms)
            // while the selector pill slides. Moving by a step (‹ ›) does not animate.
            Connections {
                target: page
                function onViewChanged() { if (Theme.motion > 0) viewIn.restart() }
            }
            ParallelAnimation {
                id: viewIn
                NumberAnimation { target: viewBody; property: "opacity"; from: 0; to: 1; duration: Theme.normal }
                NumberAnimation { target: viewBody; property: "scale"; from: 0.985; to: 1; duration: Theme.normal; easing.type: Easing.Bezier; easing.bezierCurve: Theme.easeEnter }
            }

            MonthGrid {
                visible: page.view === "month"
                Layout.fillWidth: true
                Layout.fillHeight: true
                month: page.day
                items: page.gridItems
                onDayRequested: (d) => page.openDay(d)
                onOpenRequested: (id) => page.openObjectRequested(id)
            }
            TimeGrid {
                visible: page.view === "week" || page.view === "three"
                Layout.fillWidth: true
                Layout.fillHeight: true
                start: page.rangeStart
                days: page.rangeDays
                items: page.gridItems
                onDayRequested: (d) => page.openDay(d)
                onOpenRequested: (id) => page.openObjectRequested(id)
            }

            // Ctrl+Left / Ctrl+Right day navigation (AppDesktop.dc.html)
            Shortcut {
                sequences: ["Ctrl+Left"]
                enabled: page.shown && page.view === "day"
                onActivated: page.shift(-1)
            }
            Shortcut {
                sequences: ["Ctrl+Right"]
                enabled: page.shown && page.view === "day"
                onActivated: page.shift(1)
            }

            // the day: header + daily note blocks + day's objects, one scroll
            Item {
                visible: page.view === "day"
                Layout.fillWidth: true
                Layout.fillHeight: true

                BlocksView {
                    id: dayBlocks
                    anchors.fill: undefined   // BlocksView fills its parent by default
                    anchors.top: parent.top
                    anchors.bottom: parent.bottom
                    anchors.horizontalCenter: parent.horizontalCenter
                    width: Math.min(parent.width - 2 * Theme.pagePad - 16, 880)
                    session: page.session
                    onOpenObjectRequested: objectId => page.openObjectRequested(objectId)
                    onToastRequested: message => { if (page.appWindow) page.appWindow.showToast(message) }

                    // header and trailer stop short of the scroll bar so nothing touches it
                    header: ColumnLayout {
                        width: dayBlocks.width - 20
                        spacing: 0

                        // Kicker + 44px date heading (AppDesktop.dc.html / Blocks.dc.html)
                        Text {
                            Layout.topMargin: 36
                            text: Theme.caps(page.isToday ? qsTr("TODAY") : Qt.locale().standaloneDayName(page.day.getDay()))
                            color: page.isToday ? Theme.accent : Theme.label
                            font.family: Theme.mono
                            font.pixelSize: 11
                            font.letterSpacing: Theme.track(0.6)
                        }
                        RowLayout {
                            Layout.topMargin: 8
                            spacing: 14
                            Text {
                                text: Qt.locale().standaloneDayName(page.day.getDay()) + ", " + page.day.toLocaleDateString(Qt.locale(), qsTr("d MMMM"))
                                color: Theme.text
                                font.family: Theme.display
                                font.pixelSize: Theme.noir ? 42 : 40
                                font.weight: Theme.noir ? Font.Normal : Font.Bold
                                font.letterSpacing: -0.9
                            }
                        }
                        Row {
                            Layout.topMargin: 12
                            spacing: 14
                            Rectangle {
                                implicitWidth: djRow.implicitWidth + 16; height: 24
                                color: "transparent"; border.width: 1; border.color: Theme.line2
                                Row {
                                    id: djRow; anchors.centerIn: parent; spacing: 7
                                    Rectangle { anchors.verticalCenter: parent.verticalCenter; width: 7; height: 7; color: Theme.journal }
                                    Text { anchors.verticalCenter: parent.verticalCenter; text: qsTr("Daily"); color: Theme.bodyText; font.family: Theme.body; font.pixelSize: 12 }
                                }
                            }
                            Text {
                                anchors.verticalCenter: parent.verticalCenter
                                text: Theme.caps(qsTr("WEEK %1 · %2").arg(page.isoWeek(page.day)).arg(page.day.getFullYear()))
                                color: Theme.label
                                font.family: Theme.mono
                                font.pixelSize: 11
                            }
                            Text {
                                visible: Theme.noir
                                anchors.verticalCenter: parent.verticalCenter
                                text: Theme.caps(qsTr("CTRL ← PREV · CTRL → NEXT"))
                                color: Theme.faint
                                font.family: Theme.mono
                                font.pixelSize: 10
                            }
                        }

                        // On this day: tasks due
                        SectionHead {
                            Layout.topMargin: 36
                            Layout.fillWidth: true
                            icon: "clock"
                            title: qsTr("On this day")
                            GhostButton {
                                icon: "plus"
                                label: qsTr("Add")
                                onClicked: if (page.appWindow) page.appWindow.openCaptureFor("task")
                            }
                        }
                        ColumnLayout {
                            Layout.fillWidth: true
                            Layout.topMargin: 10
                            spacing: 4
                            Repeater {
                                model: page.dueTasks
                                delegate: Rectangle {
                                    id: taskLine
                                    required property var modelData
                                    Layout.fillWidth: true
                                    implicitHeight: 40
                                    radius: Theme.radius
                                    color: Theme.noir ? (tlHover.hovered ? Theme.panel2 : "transparent")
                                                      : Qt.rgba(Theme.ok.r, Theme.ok.g, Theme.ok.b, tlHover.hovered ? 0.14 : 0.08)
                                    border.width: Theme.noir ? 0 : 1
                                    border.color: Qt.rgba(Theme.ok.r, Theme.ok.g, Theme.ok.b, 0.28)
                                    Rectangle { visible: Theme.noir; anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.lineSubtle }
                                    RowLayout {
                                        anchors.fill: parent
                                        anchors.leftMargin: Theme.noir ? 2 : 10
                                        anchors.rightMargin: Theme.noir ? 4 : 12
                                        spacing: 12
                                        // Sharp 18x18 square checkbox with 300ms scale(0->1) accent fill in noir (AppDesktop.dc.html)
                                        Rectangle {
                                            visible: Theme.noir
                                            Layout.preferredWidth: 18; Layout.preferredHeight: 18
                                            color: "transparent"
                                            border.width: 1
                                            border.color: taskLine.modelData.taskDone ? Theme.accent : Theme.faint
                                            Behavior on border.color { ColorAnimation { duration: 200 * Theme.motion } }
                                            Rectangle {
                                                anchors.fill: parent; anchors.margins: 3; color: Theme.accent
                                                scale: taskLine.modelData.taskDone ? 1 : 0
                                                opacity: taskLine.modelData.taskDone ? 1 : 0
                                                Behavior on scale {
                                                    NumberAnimation {
                                                        duration: 300 * Theme.motion
                                                        easing.type: Easing.Bezier
                                                        easing.bezierCurve: [0.2, 0.8, 0.1, 1, 1, 1]
                                                    }
                                                }
                                                Behavior on opacity { NumberAnimation { duration: 200 * Theme.motion } }
                                            }
                                            HoverHandler { cursorShape: Qt.PointingHandCursor }
                                            TapHandler { enabled: !taskLine.modelData.taskDone; onTapped: ws.completeTask(taskLine.modelData.id) }
                                        }
                                        TintedIcon {
                                            visible: !Theme.noir
                                            name: taskLine.modelData.taskDone ? "circle-check" : "circle"
                                            size: 17
                                            color: taskLine.modelData.taskDone ? Theme.ok : Theme.dim
                                            TapHandler {
                                                enabled: !taskLine.modelData.taskDone
                                                onTapped: ws.completeTask(taskLine.modelData.id)
                                            }
                                        }
                                        Text {
                                            Layout.fillWidth: true
                                            text: taskLine.modelData.title || qsTr("Untitled")
                                            color: taskLine.modelData.taskDone ? Theme.label : Theme.text
                                            font.family: Theme.body
                                            font.pixelSize: 15
                                            font.strikeout: taskLine.modelData.taskDone
                                            elide: Text.ElideRight
                                        }
                                        Text {
                                            text: Theme.caps(qsTr("TODAY"))
                                            color: Theme.label
                                            font.family: Theme.mono
                                            font.pixelSize: 10
                                        }
                                    }
                                    HoverHandler { id: tlHover; cursorShape: Qt.PointingHandCursor }
                                    TapHandler { onTapped: page.openObjectRequested(taskLine.modelData.id) }
                                }
                            }
                            Text {
                                visible: page.dueTasks.length === 0
                                Layout.leftMargin: 4
                                text: qsTr("Nothing due.")
                                color: Theme.muted
                                font.family: Theme.body
                                font.pixelSize: 13
                            }
                        }

                        // Daily note
                        SectionHead {
                            Layout.topMargin: 40
                            Layout.bottomMargin: 6
                            Layout.fillWidth: true
                            icon: "calendar"
                            title: qsTr("Daily note")
                            Text {
                                visible: !!page.noteId
                                text: qsTr("Open") + "  ↗"
                                color: openHover.hovered ? Theme.text : Theme.muted
                                font.family: Theme.body
                                font.pixelSize: 13
                                HoverHandler { id: openHover; cursorShape: Qt.PointingHandCursor }
                                TapHandler { onTapped: page.openObjectRequested(page.noteId) }
                            }
                        }
                        GhostButton {
                            visible: !page.noteId
                            Layout.topMargin: 6
                            icon: "plus"
                            label: qsTr("Start the daily note")
                            onClicked: ws.journalFor(page.iso)
                        }
                    }

                    trailer: ColumnLayout {
                        width: dayBlocks.width - 20
                        spacing: 4

                        SectionHead {
                            visible: page.created.length > 0
                            Layout.fillWidth: true
                            Layout.bottomMargin: 6
                            icon: "plus"
                            title: qsTr("Created this day")
                        }
                        Repeater {
                            model: page.created
                            delegate: ObjectLine {
                                required property var modelData
                                obj: modelData
                                onOpen: page.openObjectRequested(modelData.id)
                            }
                        }

                        SectionHead {
                            visible: page.references.length > 0
                            Layout.topMargin: page.created.length > 0 ? 28 : 0
                            Layout.bottomMargin: 6
                            Layout.fillWidth: true
                            icon: "link"
                            title: qsTr("References")
                        }
                        Repeater {
                            model: page.references
                            delegate: ObjectLine {
                                required property var modelData
                                obj: modelData
                                sub: modelData.snippet || ""
                                onOpen: {
                                    if (modelData.blockId && page.appWindow)
                                        page.appWindow.openObjectAtBlock(modelData.id, modelData.blockId)
                                    else
                                        page.openObjectRequested(modelData.id)
                                }
                            }
                        }
                        Item { Layout.preferredHeight: 40 }
                    }
                }
            }

            // Day inspector sidebar in Month view (360px — Calendar.dc.html)
            Rectangle {
                visible: Theme.noir && page.view === "month" && page.width > 920
                Layout.preferredWidth: 1
                Layout.fillHeight: true
                color: Theme.line
            }
            Flickable {
                visible: Theme.noir && page.view === "month" && page.width > 920
                Layout.preferredWidth: 360
                Layout.fillHeight: true
                contentWidth: width
                contentHeight: monthInspectCol.implicitHeight + 48
                clip: true
                boundsBehavior: Flickable.StopAtBounds

                ColumnLayout {
                    id: monthInspectCol
                    x: 24; y: 28; width: parent.width - 48; spacing: 0

                    Text {
                        text: Theme.caps(page.isToday ? qsTr("TODAY") : (page.iso < Qt.formatDate(new Date(), "yyyy-MM-dd") ? qsTr("PAST") : qsTr("UPCOMING")))
                        color: Theme.accent; font.family: Theme.mono; font.pixelSize: 11
                    }
                    Text {
                        Layout.topMargin: 6
                        text: Qt.locale().standaloneDayName(page.day.getDay()) + ", " + page.day.toLocaleDateString(Qt.locale(), qsTr("d MMMM"))
                        color: Theme.text; font.family: Theme.display; font.pixelSize: 24; font.letterSpacing: -0.4
                    }
                    Text {
                        Layout.topMargin: 4; Layout.bottomMargin: 22
                        text: Theme.caps(qsTr("%1 ITEMS").arg(page.dueTasks.length + page.created.length + (page.noteId ? 1 : 0)))
                        color: Theme.label; font.family: Theme.mono; font.pixelSize: 11
                    }
                    Text {
                        Layout.bottomMargin: 6
                        text: Theme.caps(qsTr("ON THIS DAY"))
                        color: Theme.label; font.family: Theme.mono; font.pixelSize: 11
                    }
                    // Daily note row
                    Rectangle {
                        visible: !!page.noteId
                        Layout.fillWidth: true; Layout.preferredHeight: 40; color: mnHov.hovered ? Theme.panel2 : "transparent"
                        Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.lineSubtle }
                        RowLayout {
                            anchors.fill: parent; spacing: 10
                            Rectangle { width: 7; height: 7; color: Theme.journal }
                            Text { Layout.fillWidth: true; text: qsTr("Daily note"); color: Theme.bodyText; font.family: Theme.body; font.pixelSize: 14 }
                            Text { text: Theme.caps(qsTr("DAILY")); color: Theme.label; font.family: Theme.mono; font.pixelSize: 10 }
                        }
                        HoverHandler { id: mnHov; cursorShape: Qt.PointingHandCursor }
                        TapHandler { onTapped: page.openDay(page.day) }
                    }
                    Repeater {
                        model: page.dueTasks.concat(page.created)
                        delegate: Rectangle {
                            required property var modelData
                            Layout.fillWidth: true; Layout.preferredHeight: 40; color: miHov.hovered ? Theme.panel2 : "transparent"
                            Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.lineSubtle }
                            RowLayout {
                                anchors.fill: parent; spacing: 10
                                TypeBadge { typeId: modelData.typeId || "task"; size: 14 }
                                Text { Layout.fillWidth: true; text: modelData.title || qsTr("Untitled"); color: Theme.bodyText; font.family: Theme.body; font.pixelSize: 14; elide: Text.ElideRight }
                                Text { text: Theme.typeLabel(modelData.typeId || "task"); color: Theme.label; font.family: Theme.mono; font.pixelSize: 10 }
                            }
                            HoverHandler { id: miHov; cursorShape: Qt.PointingHandCursor }
                            TapHandler { onTapped: page.openObjectRequested(modelData.id) }
                        }
                    }
                    Button {
                        visible: !page.noteId
                        Layout.topMargin: 12
                        small: true; text: qsTr("Start daily note")
                        onClicked: { ws.journalFor(page.iso); page.openDay(page.day) }
                    }
                    Text {
                        Layout.topMargin: 28
                        text: Theme.caps(qsTr("EXTERNAL CALENDAR (CALDAV) · COMING SOON"))
                        color: Theme.label; font.family: Theme.mono; font.pixelSize: 10
                    }
                }
            }

            // the month and the day's habits
            Rectangle {
                visible: prefs.showMonth && page.view !== "month" && page.width > 820
                Layout.preferredWidth: 1
                Layout.fillHeight: true
                color: Theme.line
            }
            Flickable {
                visible: prefs.showMonth && page.view !== "month" && page.width > 820
                Layout.preferredWidth: 300
                Layout.fillHeight: true
                contentWidth: width
                contentHeight: side.implicitHeight + 48
                clip: true
                boundsBehavior: Flickable.StopAtBounds

                ColumnLayout {
                    id: side
                    x: 20
                    y: 24
                    width: parent.width - 40
                    spacing: 0

                    MiniMonth {
                        Layout.fillWidth: true
                        selected: page.day
                        rev: page.dataRev
                        filters: ({ note: prefs.markNote, tasks: prefs.markTasks, created: prefs.markCreated, refs: prefs.markRefs })
                        onPicked: (d) => page.day = d
                    }
                    Rectangle {
                        Layout.fillWidth: true
                        Layout.topMargin: 22
                        Layout.bottomMargin: 22
                        Layout.preferredHeight: 1
                        color: Theme.line
                    }
                    DayHabits {
                        Layout.fillWidth: true
                        day: page.day
                        rev: page.habitRev + page.dataRev
                        onAllRequested: if (page.appWindow) { page.appWindow.editorObjectId = ""; page.appWindow.page = "habits" }
                        onNewRequested: if (page.appWindow) page.appWindow.openCaptureFor("habit")
                        onOpenRequested: (id) => page.openObjectRequested(id)
                    }
                }
            }
        }
    }
}
