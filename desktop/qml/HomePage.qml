import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "."

// Home (spec §8.2): translucent hero card, three metrics, today's tasks and "continue".

Item {
    id: home

    property var appWindow: null
    signal openObjectRequested(string objectId)
    signal typeSelected(string typeId)
    signal pageRequested(string page)

    function update() { }

    property int habitRev: 0
    Connections { target: ws; function onObjectPropertiesChanged() { home.habitRev++ } }
    readonly property var todayHabits: {
        dataRev; habitRev
        if (!ws) return []
        const k = home.todayIso, d = new Date()
        const code = ["sun","mon","tue","wed","thu","fri","sat"][d.getDay()]
        return ws.habits().filter(h => (h.state || "active") === "active"
            && (!h.start || k >= String(h.start).substring(0, 10))
            && ((h.freq || "daily") !== "weekdays" || (h.days || []).indexOf(code) >= 0))
    }
    function habitDone(h) {
        const v = h.rec[todayIso]
        if (v === undefined || v === null || v === false) return 0
        if (v === "skip" || (h.kind || "check") === "check") return 1
        return Math.min(1, Number(v) / Math.max(1, Number(h.goal || 1)))
    }
    function habitTap(h) {
        const p = habitDone(h)
        if ((h.kind || "check") === "check")
            ws.setHabitDay(h.id, todayIso, p >= 1 ? null : true)
        else {
            const cur = Number(h.rec[todayIso] || 0)
            ws.setHabitDay(h.id, todayIso, cur >= Math.max(1, Number(h.goal || 1)) ? null : cur + 1)
        }
    }

    readonly property int dataRev: (visible && ws) ? ws.listRevision : 0
    readonly property bool wide: width > 900
    readonly property string todayIso: Qt.formatDate(new Date(), "yyyy-MM-dd")

    readonly property var todayTasks: { dataRev; return ws ? ws.tasksFor("today") : [] }
    readonly property var recent: { dataRev; return ws ? ws.recentObjects(8) : [] }
    readonly property int objectCount: {
        dataRev
        let n = 0
        if (ws) {
            const types = ws.allTypes()
            for (let i = 0; i < types.length; ++i)
                n += ws.objectsByType(types[i].id, 0).length
        }
        return n
    }
    readonly property int updatedToday: {
        dataRev
        let n = 0
        const all = ws ? ws.recentObjects(500) : []
        for (let i = 0; i < all.length; ++i)
            if (String(all[i].updatedAt).indexOf(todayIso) === 0)
                ++n
        return n
    }
    readonly property int openTasks: {
        dataRev
        return ws ? ws.tasksFor("today").length + ws.tasksFor("upcoming").length : 0
    }

    function relTime(iso) {
        const t = Date.parse(iso)
        if (isNaN(t))
            return ""
        const m = Math.max(0, Math.round((Date.now() - t) / 60000))
        if (m < 1) return Theme.caps(qsTr("NOW"))
        if (m < 60) return Theme.caps(qsTr("%1 MIN AGO")).arg(m)
        const h = Math.round(m / 60)
        if (h < 24) return Theme.caps(qsTr("%1 H AGO")).arg(h)
        return Theme.caps(qsTr("%1 D AGO")).arg(Math.round(h / 24))
    }

    Flickable {
        anchors.fill: parent
        contentWidth: width
        contentHeight: content.implicitHeight + 2 * Theme.pagePad
        clip: true
        boundsBehavior: Flickable.StopAtBounds
        ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }

        ColumnLayout {
            id: content
            x: Theme.pagePad
            y: Theme.pagePad
            width: parent.width - 2 * Theme.pagePad
            spacing: 16

            // ---------------- hero: one sentence, two buttons ----------------
            Rectangle {
                Layout.fillWidth: true
                Layout.preferredHeight: heroCol.implicitHeight + 48
                radius: Theme.radius
                color: Theme.panel

                ColumnLayout {
                    id: heroCol
                    anchors.left: parent.left
                    anchors.right: parent.right
                    anchors.margins: 24
                    anchors.verticalCenter: parent.verticalCenter
                    spacing: 12

                    Flow {
                        Layout.fillWidth: true
                        spacing: 10
                        readonly property string greeting: {
                            const h = new Date().getHours()
                            return h < 6 ? qsTr("Good night") : h < 13 ? qsTr("Good morning")
                                 : h < 20 ? qsTr("Good afternoon") : qsTr("Good evening")
                        }
                        Text {
                            text: parent.greeting.substring(0, parent.greeting.lastIndexOf(" "))
                            color: Theme.text
                            font.family: Theme.display
                            font.pixelSize: 34
                            font.weight: Font.Medium
                        }
                        Text {
                            text: parent.greeting.substring(parent.greeting.lastIndexOf(" ") + 1)
                            color: Theme.ice
                            font.family: Theme.pixel
                            font.pixelSize: 36
                            font.weight: Font.ExtraBold
                        }
                        Text {
                            text: qsTr("— %1 tasks for today.").arg(home.todayTasks.length)
                            color: Theme.dim
                            font.family: Theme.body
                            font.pixelSize: 18
                            anchors.baseline: undefined
                        }
                    }

                    Flow {
                        Layout.fillWidth: true
                        spacing: 10
                        Button {
                            text: qsTr("Open daily")
                            primary: true
                            onClicked: {
                                const existing = ws.journalFor(home.todayIso)
                                if (existing && existing.length > 0)
                                    home.openObjectRequested(existing)
                            }
                        }
                        Button {
                            text: qsTr("To review (%1)").arg(ws ? (home.dataRev, ws.inbox().length) : 0)
                            onClicked: home.pageRequested("inbox")
                        }
                        Button {
                            text: qsTr("Quick note · Ctrl N")
                            onClicked: home.appWindow.captureOpen = true
                        }
                    }
                }
            }

            // ---------------- habits today ----------------
            Rectangle {
                visible: home.todayHabits.length > 0
                Layout.fillWidth: true
                Layout.preferredHeight: habitsCol.implicitHeight + 28
                radius: Theme.radius
                color: Theme.panel
                ColumnLayout {
                    id: habitsCol
                    anchors.fill: parent
                    anchors.margins: 14
                    spacing: 10
                    RowLayout {
                        Layout.fillWidth: true
                        Text { text: qsTr("Today's habits"); color: Theme.dim; font.pixelSize: 13; Layout.fillWidth: true }
                        Text {
                            text: qsTr("See all"); color: Theme.ice; font.pixelSize: 13
                            MouseArea { anchors.fill: parent; onClicked: home.pageRequested("habits") }
                        }
                    }
                    Flow {
                        Layout.fillWidth: true
                        spacing: 16
                        Repeater {
                            model: home.todayHabits
                            delegate: Column {
                                id: hc
                                required property var modelData
                                spacing: 4
                                readonly property real p: home.habitRev >= 0 ? home.habitDone(modelData) : 0
                                Rectangle {
                                    width: 44; height: 44; radius: 22
                                    color: hc.p >= 1 ? Theme.ok : Qt.rgba(1, 1, 1, 0.1)
                                    border.width: hc.p > 0 && hc.p < 1 ? 3 : 0
                                    border.color: Theme.ok
                                    Text { anchors.centerIn: parent; text: hc.modelData.emoji || "🌱"; font.pixelSize: 18 }
                                    MouseArea { anchors.fill: parent; onClicked: home.habitTap(hc.modelData) }
                                }
                                Text {
                                    width: 56; text: hc.modelData.title; elide: Text.ElideRight
                                    horizontalAlignment: Text.AlignHCenter
                                    color: Theme.dim; font.pixelSize: 11
                                }
                            }
                        }
                    }
                }
            }

            // ---------------- tasks today + continue ----------------
            GridLayout {
                Layout.fillWidth: true
                columns: home.wide ? 2 : 1
                columnSpacing: 16
                rowSpacing: 16

                Panel {
                    Layout.fillWidth: true
                    Layout.alignment: Qt.AlignTop
                    Layout.preferredWidth: 1
                    label: Theme.caps(qsTr("TASKS TODAY"))
                    action: Theme.caps(qsTr("SEE ALL"))
                    onActionClicked: home.pageRequested("tasks")
                    padding: 8

                    Text {
                        visible: home.todayTasks.length === 0
                        Layout.margins: 10
                        text: qsTr("nothing due today")
                        color: Theme.muted
                        font.family: Theme.body
                        font.pixelSize: 13
                    }
                    Repeater {
                        model: home.todayTasks.slice(0, 6)
                        delegate: Item {
                            required property var modelData
                            required property int index
                            Layout.fillWidth: true
                            Layout.preferredHeight: 48

                            Rectangle {
                                visible: index > 0
                                width: parent.width - 20
                                x: 10
                                height: 1
                                color: Theme.line
                            }
                            RowLayout {
                                anchors.fill: parent
                                anchors.leftMargin: 10
                                anchors.rightMargin: 10
                                spacing: 12

                                // circular check: marked = white fill + ink tick
                                Rectangle {
                                    Layout.preferredWidth: 20
                                    Layout.preferredHeight: 20
                                    radius: 10
                                    color: modelData.taskDone === true ? Theme.accent : "transparent"
                                    border.width: 1.5
                                    border.color: modelData.taskDone === true ? Theme.accent : Theme.dim
                                    Text {
                                        visible: modelData.taskDone === true
                                        anchors.centerIn: parent
                                        text: "✓"
                                        color: Theme.accentInk
                                        font.pixelSize: 12
                                        font.weight: Font.Bold
                                    }
                                    TapHandler { onTapped: ws.completeTask(modelData.id) }
                                    HoverHandler { cursorShape: Qt.PointingHandCursor }
                                }
                                Text {
                                    Layout.fillWidth: true
                                    text: modelData.title
                                    color: modelData.taskDone === true ? Theme.muted : Theme.text
                                    font.family: Theme.body
                                    font.pixelSize: 14
                                    font.strikeout: modelData.taskDone === true
                                    elide: Text.ElideRight
                                    TapHandler { onTapped: home.openObjectRequested(modelData.id) }
                                }
                                Text {
                                    visible: text.length > 0
                                    text: modelData.dueDate ? String(modelData.dueDate).toUpperCase() : ""
                                    color: modelData.dueDate && String(modelData.dueDate) < home.todayIso ? Theme.late : Theme.muted
                                    font.family: Theme.mono
                                    font.pixelSize: 11
                                }
                            }
                        }
                    }
                }

                Panel {
                    Layout.fillWidth: true
                    Layout.alignment: Qt.AlignTop
                    Layout.preferredWidth: 1
                    label: Theme.caps(qsTr("CONTINUE"))
                    padding: 8

                    Text {
                        visible: home.recent.length === 0
                        Layout.margins: 10
                        text: qsTr("nothing yet — create something with CAPTURE")
                        color: Theme.muted
                        font.family: Theme.body
                        font.pixelSize: 13
                    }
                    Repeater {
                        model: home.recent.slice(0, 5)
                        delegate: Item {
                            id: rowItem
                            required property var modelData
                            required property int index
                            Layout.fillWidth: true
                            Layout.preferredHeight: 64

                            Rectangle {
                                anchors.fill: parent
                                radius: Theme.radius
                                color: rowHover.hovered ? Theme.panel : "transparent"
                                Behavior on color { ColorAnimation { duration: Theme.fast } }
                            }
                            RowLayout {
                                anchors.fill: parent
                                anchors.leftMargin: 10
                                anchors.rightMargin: 10
                                spacing: 14

                                Rectangle {
                                    Layout.preferredWidth: 42
                                    Layout.preferredHeight: 42
                                    radius: 12
                                    color: Theme.panel2
                                    TintedIcon {
                                        anchors.centerIn: parent
                                        name: Theme.typeIcon(rowItem.modelData.typeId)
                                        size: 18
                                        color: Theme.typeColor(rowItem.modelData.typeId)
                                    }
                                }
                                ColumnLayout {
                                    Layout.fillWidth: true
                                    spacing: 2
                                    Text {
                                        Layout.fillWidth: true
                                        text: rowItem.modelData.title
                                        color: Theme.text
                                        font.family: Theme.body
                                        font.pixelSize: 15
                                        font.weight: Font.Medium
                                        elide: Text.ElideRight
                                    }
                                    Text {
                                        text: Theme.typeLabel(rowItem.modelData.typeId) + " · " + home.relTime(rowItem.modelData.updatedAt)
                                        color: Theme.muted
                                        font.family: Theme.mono
                                        font.pixelSize: 11
                                        font.letterSpacing: Theme.track(1)
                                    }
                                }
                            }
                            HoverHandler { id: rowHover; cursorShape: Qt.PointingHandCursor }
                            TapHandler { onTapped: home.openObjectRequested(rowItem.modelData.id) }
                        }
                    }
                }
            }
        }
    }
}
