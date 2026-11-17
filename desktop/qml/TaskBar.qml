import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "."

// Task header under the title: status (To do · In progress · Done, the pill slides) and the due
// day. Writes the same properties as mobile: status = todo | in_progress | done, due_date = a day
// (yyyy-MM-dd, no time).
Flow {
    id: bar

    property string objectId: ""
    signal toast(string text)

    property int rev: 0
    Connections {
        target: ws
        function onObjectPropertiesChanged(id) { if (id === bar.objectId) bar.rev++ }
        function onObjectListChanged() { bar.rev++ }
    }
    readonly property string status: {
        rev
        const s = ws && objectId ? String(ws.fieldText(objectId, "status")).toLowerCase() : ""
        return s === "completed" ? "done" : s || "todo"
    }
    readonly property string due: {
        rev
        const o = ws && objectId ? ws.objectSummary(objectId) : null
        return o && o.dueDate ? String(o.dueDate).substring(0, 10) : ""
    }

    function iso(d) { return Qt.formatDate(d, "yyyy-MM-dd") }
    function dayFrom(n) { const d = new Date(); d.setHours(12); d.setDate(d.getDate() + n); return d }
    function setDue(value) {
        ws.setObjectProperty(objectId, "due_date", value, "date")
        rev++
    }
    function setStatus(s) {
        ws.setObjectProperty(objectId, "status", s, "select")
        rev++
    }
    // "Today", "Tomorrow", "Yesterday", else the short date; a past day of an open task is overdue.
    function dueLabel() {
        if (!due) return qsTr("No due date")
        if (due === iso(dayFrom(0))) return qsTr("Today")
        if (due === iso(dayFrom(1))) return qsTr("Tomorrow")
        if (due === iso(dayFrom(-1))) return qsTr("Yesterday")
        const p = due.split("-")
        const d = new Date(Number(p[0]), Number(p[1]) - 1, Number(p[2]), 12)
        const sameYear = d.getFullYear() === new Date().getFullYear()
        return d.toLocaleDateString(Qt.locale(), sameYear ? "ddd d MMM" : "d MMM yyyy")
    }
    readonly property bool overdue: !!due && status !== "done" && due < iso(dayFrom(0))

    spacing: 10

    // status
    Rectangle {
        height: 34
        width: statusRow.implicitWidth + 8
        radius: Theme.radius
        color: Theme.page
        border.width: 1
        border.color: Theme.line
        Rectangle {
            id: statusPill
            readonly property Item target: { statusRow.width; return statusRep.itemAt(statusRep.keys.indexOf(bar.status)) }
            x: statusRow.x + (target ? target.x : 0)
            y: statusRow.y
            width: target ? target.width : 0
            height: 26
            radius: Theme.radiusSm
            readonly property color tone: bar.status === "done" ? Theme.ok : bar.status === "in_progress" ? Theme.warm : Theme.panel2
            color: bar.status === "todo" ? Theme.panel2 : Qt.rgba(tone.r, tone.g, tone.b, 0.18)
            border.width: 1
            border.color: bar.status === "todo" ? Theme.line2 : Qt.rgba(tone.r, tone.g, tone.b, 0.45)
            Behavior on x { NumberAnimation { duration: 240; easing.type: Easing.OutCubic } }
            Behavior on width { NumberAnimation { duration: 240; easing.type: Easing.OutCubic } }
            Behavior on color { ColorAnimation { duration: 240 } }
        }
        Row {
            id: statusRow
            anchors.centerIn: parent
            spacing: 2
            Repeater {
                id: statusRep
                readonly property var keys: ["todo", "in_progress", "done"]
                model: [
                    { key: "todo", label: qsTr("To do"), icon: "circle" },
                    { key: "in_progress", label: qsTr("In progress"), icon: "clock" },
                    { key: "done", label: qsTr("Done"), icon: "circle-check" }
                ]
                delegate: Item {
                    required property var modelData
                    readonly property bool on: modelData.key === bar.status
                    width: sRow.implicitWidth + 20
                    height: 26
                    Row {
                        id: sRow
                        anchors.centerIn: parent
                        spacing: 6
                        TintedIcon {
                            anchors.verticalCenter: parent.verticalCenter
                            name: modelData.icon
                            size: 14
                            color: !parent.parent.on ? Theme.muted
                                 : modelData.key === "done" ? Theme.ok
                                 : modelData.key === "in_progress" ? Theme.warm : Theme.text
                        }
                        Text {
                            anchors.verticalCenter: parent.verticalCenter
                            text: modelData.label
                            color: parent.parent.on ? Theme.text : sHover.hovered ? Theme.dim : Theme.muted
                            font.family: Theme.body
                            font.pixelSize: 13
                            Behavior on color { ColorAnimation { duration: 240 } }
                        }
                    }
                    HoverHandler { id: sHover; cursorShape: Qt.PointingHandCursor }
                    TapHandler { onTapped: bar.setStatus(modelData.key) }
                }
            }
        }
    }

    // due day
    Rectangle {
        id: dueButton
        height: 34
        width: dueRow.implicitWidth + 24
        radius: Theme.radius
        readonly property color tone: bar.overdue ? Theme.late : Theme.ice
        color: dueHover.hovered ? Theme.panel2 : bar.due ? Qt.rgba(tone.r, tone.g, tone.b, 0.10) : "transparent"
        border.width: 1
        border.color: bar.due ? Qt.rgba(tone.r, tone.g, tone.b, 0.35) : Theme.line
        Row {
            id: dueRow
            anchors.centerIn: parent
            spacing: 7
            TintedIcon {
                anchors.verticalCenter: parent.verticalCenter
                name: "calendar"
                size: 15
                color: bar.due ? dueButton.tone : Theme.muted
            }
            Text {
                anchors.verticalCenter: parent.verticalCenter
                text: bar.due ? qsTr("Due %1").arg(bar.dueLabel()) : bar.dueLabel()
                color: bar.overdue ? Theme.late : bar.due ? Theme.text : Theme.muted
                font.family: Theme.body
                font.pixelSize: 13
                font.weight: bar.due ? Font.Medium : Font.Normal
            }
        }
        HoverHandler { id: dueHover; cursorShape: Qt.PointingHandCursor }
        TapHandler { onTapped: duePopup.open() }

        Popup {
            id: duePopup
            y: dueButton.height + 6
            width: 300
            padding: 14
            background: Rectangle {
                radius: Theme.radiusLg
                color: Theme.sheet
                border.width: 1
                border.color: Theme.line2
            }
            ColumnLayout {
                width: parent.width
                spacing: 4
                Repeater {
                    model: [
                        { label: qsTr("Today"), n: 0 },
                        { label: qsTr("Tomorrow"), n: 1 },
                        { label: qsTr("Next week"), n: 7 }
                    ]
                    delegate: Rectangle {
                        required property var modelData
                        readonly property date d: bar.dayFrom(modelData.n)
                        Layout.fillWidth: true
                        implicitHeight: 32
                        radius: Theme.radius
                        color: qHover.hovered ? Theme.panel2 : "transparent"
                        Text {
                            x: 10
                            anchors.verticalCenter: parent.verticalCenter
                            text: modelData.label
                            color: Theme.text
                            font.family: Theme.body
                            font.pixelSize: 13
                        }
                        Text {
                            anchors.right: parent.right
                            anchors.rightMargin: 10
                            anchors.verticalCenter: parent.verticalCenter
                            text: parent.d.toLocaleDateString(Qt.locale(), "ddd d MMM")
                            color: Theme.muted
                            font.family: Theme.body
                            font.pixelSize: 12
                        }
                        HoverHandler { id: qHover; cursorShape: Qt.PointingHandCursor }
                        TapHandler { onTapped: { bar.setDue(bar.iso(parent.d)); duePopup.close() } }
                    }
                }
                Rectangle { Layout.fillWidth: true; Layout.topMargin: 6; Layout.bottomMargin: 8; Layout.preferredHeight: 1; color: Theme.line }
                MiniMonth {
                    Layout.fillWidth: true
                    selected: {
                        if (!bar.due) return bar.dayFrom(0)
                        const p = bar.due.split("-")
                        return new Date(Number(p[0]), Number(p[1]) - 1, Number(p[2]), 12)
                    }
                    rev: bar.rev
                    filters: ({ note: false, tasks: true, created: false, refs: false })
                    onPicked: (d) => { bar.setDue(bar.iso(d)); duePopup.close() }
                }
                Rectangle {
                    visible: !!bar.due
                    Layout.fillWidth: true
                    Layout.topMargin: 6
                    implicitHeight: 32
                    radius: Theme.radius
                    color: clrHover.hovered ? Theme.panel2 : "transparent"
                    Text {
                        x: 10
                        anchors.verticalCenter: parent.verticalCenter
                        text: qsTr("Remove due date")
                        color: Theme.late
                        font.family: Theme.body
                        font.pixelSize: 13
                    }
                    HoverHandler { id: clrHover; cursorShape: Qt.PointingHandCursor }
                    TapHandler { onTapped: { bar.setDue(""); duePopup.close() } }
                }
            }
        }
    }
}
