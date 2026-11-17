import QtQuick
import QtQuick.Controls
import "."

// Week and Three days: one column per day. On top, the all-day lane (daily note, tasks due);
// below, 24 hours where everything created that day sits at the time it was created. A red line
// marks now in today's column. Clicking a day's header opens it in the Day view.
Item {
    id: tg

    property date start: new Date()
    property int days: 7
    property var items: []
    signal dayRequested(date day)
    signal openRequested(string objectId)

    readonly property int gutter: 64
    readonly property int headH: 52
    readonly property int hourH: 48
    readonly property real colW: (width - gutter) / days
    function day(i) { return new Date(start.getFullYear(), start.getMonth(), start.getDate() + i, 12) }
    function iso(d) { return Qt.formatDate(d, "yyyy-MM-dd") }
    readonly property bool twelveHour: /a/i.test(Qt.locale().timeFormat(Locale.ShortFormat))
    function hourLabel(h) {
        return new Date(2000, 0, 1, h).toLocaleTimeString(Qt.locale(), twelveHour ? "h AP" : "HH:mm")
    }

    readonly property var byDay: {
        const m = {}
        for (const it of items) {
            const slot = m[it.day] = m[it.day] || { allDay: [], timed: [] }
            if (it.kind === "created") slot.timed.push(it)
            else slot.allDay.push(it)
        }
        return m
    }
    readonly property int allDayRows: {
        let n = 0
        for (let i = 0; i < days; i++) {
            const s = byDay[iso(day(i))]
            if (s) n = Math.max(n, s.allDay.length)
        }
        return n
    }
    readonly property int allDayH: Math.max(34, allDayRows * 25 + 10)

    // Side by side when they overlap: greedy lanes per cluster of overlapping chips, at most as many
    // lanes as fit (about 90 px each); the rest of a crowded cluster folds into one "+N" chip.
    readonly property int maxLanes: Math.max(1, Math.floor((colW - 10) / 90))
    function layout(timed) {
        const chipH = 24, out = []
        let cluster = [], laneEnds = [], clusterEnd = -1
        function flush() {
            if (laneEnds.length > maxLanes) {
                const keep = maxLanes - 1, folded = cluster.filter(c => c.lane >= keep)
                for (const c of folded) out.splice(out.indexOf(c), 1)
                for (const c of cluster) c.lanes = maxLanes
                out.push({ it: null, more: folded.length, y: Math.min(...folded.map(c => c.y)), lane: keep, lanes: maxLanes })
            } else {
                for (const c of cluster) c.lanes = laneEnds.length
            }
            cluster = []; laneEnds = []
        }
        for (const it of timed) {
            const y = it.minutes / 60 * hourH
            if (y >= clusterEnd) flush()
            let lane = laneEnds.findIndex(e => e <= y)
            if (lane < 0) { lane = laneEnds.length; laneEnds.push(0) }
            laneEnds[lane] = y + chipH
            const c = { it: it, y: y, lane: lane, lanes: 1 }
            cluster.push(c); out.push(c)
            clusterEnd = Math.max(clusterEnd, y + chipH)
        }
        flush()
        return out
    }

    property real nowMinutes: 0
    function tick() { const n = new Date(); nowMinutes = n.getHours() * 60 + n.getMinutes() }
    Timer { interval: 30000; running: tg.visible; repeat: true; triggeredOnStart: true; onTriggered: tg.tick() }

    // header: weekday + number per column
    Repeater {
        model: tg.days
        delegate: Item {
            id: head
            required property int index
            readonly property date d: tg.day(index)
            readonly property bool isToday: tg.iso(d) === tg.iso(new Date())
            x: tg.gutter + index * tg.colW
            width: tg.colW
            height: tg.headH
            Row {
                x: 10
                anchors.verticalCenter: parent.verticalCenter
                spacing: 8
                Text {
                    anchors.verticalCenter: parent.verticalCenter
                    text: Qt.locale().dayName(head.d.getDay(), Locale.ShortFormat).toUpperCase()
                    color: head.isToday ? Theme.ice : Theme.muted
                    font.family: Theme.body
                    font.pixelSize: 11
                    font.weight: Font.Medium
                }
                Rectangle {
                    width: 28; height: 28; radius: 14
                    color: head.isToday ? Theme.ice : headHover.hovered ? Theme.panel2 : "transparent"
                    Text {
                        anchors.centerIn: parent
                        text: head.d.getDate()
                        color: head.isToday ? Theme.accentInk : Theme.text
                        font.family: Theme.body
                        font.pixelSize: 16
                        font.weight: Font.DemiBold
                    }
                }
            }
            HoverHandler { id: headHover; cursorShape: Qt.PointingHandCursor }
            TapHandler { onTapped: tg.dayRequested(head.d) }
            Rectangle { anchors.left: parent.left; width: 1; height: parent.height; color: Theme.line }
        }
    }

    // all-day lane
    Item {
        id: allDay
        y: tg.headH
        width: tg.width
        height: tg.allDayH
        Text {
            width: tg.gutter - 10
            y: 10
            horizontalAlignment: Text.AlignRight
            text: qsTr("All day")
            color: Theme.muted
            font.family: Theme.body
            font.pixelSize: 11
        }
        Repeater {
            model: tg.days
            delegate: Item {
                id: adCol
                required property int index
                readonly property var slot: tg.byDay[tg.iso(tg.day(index))]
                x: tg.gutter + index * tg.colW
                width: tg.colW
                height: parent.height
                Rectangle { width: 1; height: parent.height; color: Theme.line }
                Column {
                    x: 5; y: 5
                    width: parent.width - 10
                    spacing: 3
                    Repeater {
                        model: adCol.slot ? adCol.slot.allDay : []
                        delegate: CalendarChip {
                            required property var modelData
                            width: parent.width
                            item: modelData
                            onOpen: tg.openRequested(modelData.id)
                        }
                    }
                }
            }
        }
        Rectangle { anchors.top: parent.top; width: parent.width; height: 1; color: Theme.line }
        Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.line }
    }

    // hours
    Flickable {
        id: hours
        y: tg.headH + tg.allDayH
        width: tg.width
        height: tg.height - y
        contentWidth: width
        contentHeight: 24 * tg.hourH + 12
        clip: true
        boundsBehavior: Flickable.StopAtBounds
        ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }
        Component.onCompleted: contentY = Math.max(0, Math.min(contentHeight - height, (new Date().getHours() - 2) * tg.hourH))

        Repeater {
            model: 24
            delegate: Item {
                required property int index
                y: index * tg.hourH
                width: tg.width
                height: tg.hourH
                Text {
                    visible: index > 0
                    width: tg.gutter - 10
                    y: -7
                    horizontalAlignment: Text.AlignRight
                    text: tg.hourLabel(index)
                    color: Theme.muted
                    font.family: Theme.body
                    font.pixelSize: 11
                }
                Rectangle { x: tg.gutter; width: tg.width - tg.gutter; height: 1; color: Theme.line; opacity: index > 0 ? 0.7 : 0 }
            }
        }

        Repeater {
            model: tg.days
            delegate: Item {
                id: col
                required property int index
                readonly property string key: tg.iso(tg.day(index))
                readonly property bool isToday: key === tg.iso(new Date())
                readonly property var placed: tg.layout(tg.byDay[key] ? tg.byDay[key].timed : [])
                x: tg.gutter + index * tg.colW
                width: tg.colW
                height: 24 * tg.hourH
                Rectangle { width: 1; height: parent.height; color: Theme.line }

                Repeater {
                    model: col.placed
                    delegate: Loader {
                        required property var modelData
                        readonly property real laneW: (col.width - 10) / modelData.lanes
                        x: 5 + modelData.lane * laneW
                        y: modelData.y + 1
                        width: laneW - 2
                        sourceComponent: modelData.it ? chipC : moreC
                        Component {
                            id: chipC
                            CalendarChip { item: modelData.it; onOpen: tg.openRequested(modelData.it.id) }
                        }
                        Component {
                            id: moreC
                            Rectangle {
                                implicitHeight: 22
                                radius: Theme.radiusSm
                                color: moreHover.hovered ? Theme.panel3 : Theme.panel2
                                border.width: 1
                                border.color: Theme.line2
                                Text {
                                    anchors.centerIn: parent
                                    text: "+" + modelData.more
                                    color: Theme.dim
                                    font.family: Theme.body
                                    font.pixelSize: 12
                                    font.weight: Font.Medium
                                }
                                HoverHandler { id: moreHover; cursorShape: Qt.PointingHandCursor }
                                TapHandler { onTapped: tg.dayRequested(tg.day(col.index)) }
                            }
                        }
                    }
                }

                // now
                Item {
                    visible: col.isToday
                    y: tg.nowMinutes / 60 * tg.hourH
                    width: parent.width
                    z: 5
                    Rectangle { x: -4; y: -4; width: 8; height: 8; radius: 4; color: Theme.late }
                    Rectangle { y: -1; width: parent.width; height: 2; color: Theme.late }
                }
            }
        }
    }
}
