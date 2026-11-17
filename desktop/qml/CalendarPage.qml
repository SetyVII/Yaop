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

    readonly property string iso: Qt.formatDate(day, "yyyy-MM-dd")
    readonly property bool isToday: iso === Qt.formatDate(new Date(), "yyyy-MM-dd")
    readonly property string placeTitle: isToday ? qsTr("Today") : day.toLocaleDateString(Qt.locale(), Locale.ShortFormat)
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
            if (!appWindow || appWindow.editorObjectId !== old) ws.closeObject(old)
        }
        if (want) session = ws.openObject(want)
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

            IconButton { icon: "chevron-left"; onClicked: page.shift(-1) }
            GhostButton { label: qsTr("Today"); onClicked: page.showToday() }
            IconButton { icon: "chevron-right"; onClicked: page.shift(1) }
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

            // views: only Day exists yet
            Rectangle {
                Layout.preferredHeight: 36
                implicitWidth: viewsRow.implicitWidth + 8
                radius: Theme.radius
                color: Theme.page
                border.width: 1
                border.color: Theme.line
                Row {
                    id: viewsRow
                    anchors.centerIn: parent
                    spacing: 2
                    Repeater {
                        model: [
                            { key: "month", label: qsTr("Month") },
                            { key: "week", label: qsTr("Week") },
                            { key: "three", label: qsTr("Three days") },
                            { key: "day", label: qsTr("Day") }
                        ]
                        delegate: Rectangle {
                            required property var modelData
                            readonly property bool on: modelData.key === "day"
                            width: vt.implicitWidth + 22
                            height: 28
                            radius: Theme.radiusSm
                            color: on ? Theme.panel2 : "transparent"
                            Text {
                                id: vt
                                anchors.centerIn: parent
                                text: modelData.label
                                color: parent.on ? Theme.text : Theme.faint
                                font.family: Theme.body
                                font.pixelSize: 14
                                font.weight: parent.on ? Font.Medium : Font.Normal
                            }
                            HoverHandler { id: vHover }
                            ToolTip.visible: vHover.hovered && !on
                            ToolTip.text: qsTr("Not built yet")
                            ToolTip.delay: 400
                        }
                    }
                }
            }

            IconButton {
                icon: "calendar"
                active: prefs.showMonth
                onClicked: prefs.showMonth = !prefs.showMonth
                ToolTip.visible: hovered; ToolTip.text: prefs.showMonth ? qsTr("Hide the month") : qsTr("Show the month"); ToolTip.delay: 500
            }
        }
        Rectangle { Layout.fillWidth: true; Layout.preferredHeight: 1; color: Theme.line }

        RowLayout {
            Layout.fillWidth: true
            Layout.fillHeight: true
            spacing: 0

            // the day: header + daily note blocks + day's objects, one scroll
            Item {
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

                    header: ColumnLayout {
                        width: dayBlocks.width
                        spacing: 0

                        RowLayout {
                            Layout.topMargin: 36
                            spacing: 8
                            Text {
                                text: Qt.locale().standaloneDayName(page.day.getDay())
                                color: Theme.ice
                                font.family: Theme.body
                                font.pixelSize: 17
                                font.weight: Font.Medium
                            }
                            Rectangle {
                                visible: page.isToday
                                implicitWidth: todayTxt.implicitWidth + 14
                                implicitHeight: 24
                                radius: Theme.radiusSm
                                color: Qt.rgba(Theme.ice.r, Theme.ice.g, Theme.ice.b, 0.14)
                                Text {
                                    id: todayTxt
                                    anchors.centerIn: parent
                                    text: qsTr("Today")
                                    color: Theme.ice
                                    font.family: Theme.body
                                    font.pixelSize: 13
                                    font.weight: Font.Medium
                                }
                            }
                        }
                        RowLayout {
                            Layout.topMargin: 4
                            spacing: 14
                            Text {
                                text: page.day.toLocaleDateString(Qt.locale(), qsTr("MMMM d, yyyy"))
                                color: Theme.text
                                font.family: Theme.display
                                font.pixelSize: 40
                                font.weight: Font.Bold
                                font.letterSpacing: -0.8
                            }
                            Text {
                                Layout.alignment: Qt.AlignBaseline
                                text: qsTr("Week %1").arg(page.isoWeek(page.day))
                                color: Theme.muted
                                font.family: Theme.body
                                font.pixelSize: 16
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
                                    implicitHeight: 38
                                    radius: Theme.radius
                                    color: Qt.rgba(Theme.ok.r, Theme.ok.g, Theme.ok.b, tlHover.hovered ? 0.14 : 0.08)
                                    border.width: 1
                                    border.color: Qt.rgba(Theme.ok.r, Theme.ok.g, Theme.ok.b, 0.28)
                                    RowLayout {
                                        anchors.fill: parent
                                        anchors.leftMargin: 10
                                        anchors.rightMargin: 12
                                        spacing: 10
                                        Text {
                                            text: qsTr("All day")
                                            color: Theme.muted
                                            font.family: Theme.body
                                            font.pixelSize: 13
                                        }
                                        TintedIcon {
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
                                            color: taskLine.modelData.taskDone ? Theme.muted : Theme.text
                                            font.family: Theme.body
                                            font.pixelSize: 14
                                            font.strikeout: taskLine.modelData.taskDone
                                            elide: Text.ElideRight
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
                        width: dayBlocks.width
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

            // the month and the day's habits
            Rectangle {
                visible: prefs.showMonth && page.width > 820
                Layout.preferredWidth: 1
                Layout.fillHeight: true
                color: Theme.line
            }
            Flickable {
                visible: prefs.showMonth && page.width > 820
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
