import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "."

// Habits (spec §24, docs/habits.md): weekly grid, rows = habits, columns = 7 days + streak.
// Day records are properties "d:YYYY-MM-DD" on the habit; streak and rate are computed here.
Item {
    id: page
    property var appWindow: null
    signal openObjectRequested(string objectId)

    readonly property int dataRev: (visible && ws) ? ws.listRevision : 0
    property int weekOffset: 0
    property int propsRev: 0
    readonly property var habits: { dataRev; propsRev; return ws ? ws.habits() : [] }
    readonly property var weekDays: {
        const t = new Date(); t.setHours(12)
        const dow = (t.getDay() + 6) % 7
        const mon = new Date(t.getTime() - dow * 864e5 + weekOffset * 7 * 864e5)
        const out = []
        for (let i = 0; i < 7; i++) out.push(new Date(mon.getTime() + i * 864e5))
        return out
    }
    Connections { target: ws; function onObjectPropertiesChanged() { page.propsRev++ } }

    // ---- maths (mirror of mobile HabitMath) ----
    function iso(d) { return Qt.formatDate(d, "yyyy-MM-dd") }
    function code(d) { return ["sun","mon","tue","wed","thu","fri","sat"][d.getDay()] }
    function kind(h) { return h.kind || "check" }
    function goal(h) { return Math.max(1, Number(h.goal || 1)) }
    function isDue(h, d) {
        if ((h.state || "active") !== "active") return false
        if (h.start && iso(d) < String(h.start).substring(0, 10)) return false
        if ((h.freq || "daily") === "weekdays") return (h.days || []).indexOf(code(d)) >= 0
        return true
    }
    function isSkip(h, d) { return h.rec[iso(d)] === "skip" }
    function progress(h, d) {
        const v = h.rec[iso(d)]
        if (v === undefined || v === null || v === false) return 0
        if (v === "skip") return 1
        if (kind(h) === "check") return 1
        return Math.min(1, Number(v) / goal(h))
    }
    function met(h, d) { return progress(h, d) >= 1 && !isSkip(h, d) }
    function streak(h) {
        const today = new Date(); today.setHours(12)
        let n = met(h, today) ? 1 : 0
        for (let i = 1; i < 3650; i++) {
            const d = new Date(today.getTime() - i * 864e5)
            if (h.start && iso(d) < String(h.start).substring(0, 10)) break
            if (!isDue(h, d) || isSkip(h, d)) continue
            if (!met(h, d)) break
            n++
        }
        return n
    }
    function rate30(h) {
        const today = new Date(); today.setHours(12)
        let due = 0, ok = 0
        for (let i = 0; i < 30; i++) {
            const d = new Date(today.getTime() - i * 864e5)
            if (!isDue(h, d) || isSkip(h, d)) continue
            due++; if (met(h, d)) ok++
        }
        return due === 0 ? 0 : Math.round(100 * ok / due)
    }
    function tap(h, d) {
        const key = iso(d)
        if (kind(h) === "check") {
            ws.setHabitDay(h.id, key, met(h, d) ? null : true)
        } else {
            const cur = Number(h.rec[key] || 0)
            ws.setHabitDay(h.id, key, cur >= goal(h) ? null : cur + 1)
        }
    }

    Flickable {
        anchors.fill: parent
        contentWidth: width
        contentHeight: col.implicitHeight + 2 * Theme.pagePad
        clip: true
        boundsBehavior: Flickable.StopAtBounds
        ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }

        ColumnLayout {
            id: col
            x: Theme.pagePad
            y: Theme.pagePad
            width: parent.width - 2 * Theme.pagePad
            spacing: 16

            RowLayout {
                Layout.fillWidth: true
                Text {
                    text: qsTr("Habits")
                    color: Theme.text
                    font.family: Theme.display
                    font.pixelSize: 30
                    font.weight: Font.Medium
                    Layout.fillWidth: true
                }
                Button { text: "‹"; onClicked: page.weekOffset-- }
                Button { text: qsTr("Today"); onClicked: page.weekOffset = 0 }
                Button { text: "›"; onClicked: page.weekOffset++ }
                Button {
                    text: qsTr("New habit")
                    primary: true
                    onClicked: {
                        const id = ws.createObject("habit", qsTr("New habit"))
                        if (id) page.openObjectRequested(id)
                    }
                }
            }

            Rectangle {
                Layout.fillWidth: true
                implicitHeight: grid.implicitHeight + 24
                radius: Theme.radius
                color: Theme.panel

                ColumnLayout {
                    id: grid
                    anchors.fill: parent
                    anchors.margins: 12
                    spacing: 4

                    RowLayout {
                        Layout.fillWidth: true
                        Item { Layout.fillWidth: true; Layout.minimumWidth: 160 }
                        Repeater {
                            model: page.weekDays
                            delegate: Text {
                                required property var modelData
                                Layout.preferredWidth: 44
                                horizontalAlignment: Text.AlignHCenter
                                text: Qt.formatDate(modelData, "ddd d")
                                color: page.iso(modelData) === page.iso(new Date()) ? Theme.ice : Theme.dim
                                font.family: Theme.body
                                font.pixelSize: 12
                            }
                        }
                        Text { Layout.preferredWidth: 56; text: qsTr("Streak"); color: Theme.dim; font.pixelSize: 12; horizontalAlignment: Text.AlignRight }
                        Text { Layout.preferredWidth: 48; text: "30 d"; color: Theme.dim; font.pixelSize: 12; horizontalAlignment: Text.AlignRight }
                    }

                    Text {
                        visible: page.habits.length === 0
                        text: qsTr("No habits yet. Create one with “New habit”.")
                        color: Theme.dim
                        font.pixelSize: 14
                        Layout.topMargin: 8
                    }

                    Repeater {
                        model: page.habits
                        delegate: RowLayout {
                            id: row
                            required property var modelData
                            Layout.fillWidth: true
                            Layout.preferredHeight: 44
                            Text {
                                Layout.fillWidth: true
                                Layout.minimumWidth: 160
                                text: (row.modelData.emoji || "🌱") + "  " + row.modelData.title
                                color: Theme.text
                                font.family: Theme.body
                                font.pixelSize: 15
                                elide: Text.ElideRight
                                MouseArea { anchors.fill: parent; onClicked: page.openObjectRequested(row.modelData.id) }
                            }
                            Repeater {
                                model: page.weekDays
                                delegate: Item {
                                    id: cell
                                    required property var modelData
                                    Layout.preferredWidth: 44
                                    Layout.preferredHeight: 36
                                    readonly property real p: page.propsRev >= 0 ? page.progress(row.modelData, modelData) : 0
                                    readonly property bool due: page.isDue(row.modelData, modelData)
                                    Rectangle {
                                        anchors.centerIn: parent
                                        width: 26; height: 26; radius: 13
                                        opacity: cell.due ? 1 : 0.4
                                        color: cell.p >= 1 ? Theme.ok : cell.p > 0 ? Qt.rgba(0.53, 0.91, 0.66, 0.35) : Qt.rgba(1, 1, 1, 0.1)
                                        Text {
                                            anchors.centerIn: parent
                                            visible: row.modelData.rec[page.iso(cell.modelData)] === "skip"
                                            text: "–"; color: Theme.dim
                                        }
                                    }
                                    MouseArea { anchors.fill: parent; onClicked: page.tap(row.modelData, cell.modelData) }
                                }
                            }
                            Text {
                                Layout.preferredWidth: 56
                                horizontalAlignment: Text.AlignRight
                                text: (page.propsRev >= 0 ? page.streak(row.modelData) : 0) + " 🔥"
                                color: Theme.dim
                                font.pixelSize: 13
                            }
                            Text {
                                Layout.preferredWidth: 48
                                horizontalAlignment: Text.AlignRight
                                text: (page.propsRev >= 0 ? page.rate30(row.modelData) : 0) + "%"
                                color: Theme.dim
                                font.pixelSize: 13
                            }
                        }
                    }
                }
            }
        }
    }
}
