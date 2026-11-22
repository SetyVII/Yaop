import QtQuick
import QtQuick.Layouts
import "."

// Month view: weeks × weekdays filling the page. Each day lists its items (as many as fit, then
// "N more"); the day number opens the Day view.
Item {
    id: grid

    property date month: new Date()          // any day in the month
    property var items: []                   // ws.calendarItems for the 42 cells
    signal dayRequested(date day)
    signal openRequested(string objectId)

    readonly property int firstDow: Qt.locale().firstDayOfWeek % 7
    readonly property date first: new Date(month.getFullYear(), month.getMonth(), 1, 12)
    readonly property date gridStart: {
        const lead = (first.getDay() - firstDow + 7) % 7
        return new Date(first.getFullYear(), first.getMonth(), 1 - lead, 12)
    }
    readonly property int rows: {
        const lead = (first.getDay() - firstDow + 7) % 7
        const days = new Date(first.getFullYear(), first.getMonth() + 1, 0).getDate()
        return Math.ceil((lead + days) / 7)
    }
    function cell(i) { return new Date(gridStart.getFullYear(), gridStart.getMonth(), gridStart.getDate() + i, 12) }
    function iso(d) { return Qt.formatDate(d, "yyyy-MM-dd") }
    readonly property var byDay: {
        const m = {}
        for (const it of items) (m[it.day] = m[it.day] || []).push(it)
        return m
    }

    readonly property int headH: 30
    readonly property real cellW: width / 7
    readonly property real cellH: (height - headH) / rows

    Repeater {
        model: 7
        delegate: Text {
            required property int index
            x: index * grid.cellW + 10
            width: grid.cellW - 20
            height: grid.headH
            verticalAlignment: Text.AlignVCenter
            text: Qt.locale().dayName((grid.firstDow + index) % 7, Locale.ShortFormat)
            color: Theme.muted
            font.family: Theme.body
            font.pixelSize: 12
        }
    }
    Rectangle { y: grid.headH - 1; width: grid.width; height: 1; color: Theme.line }

    Repeater {
        model: grid.rows * 7
        delegate: Item {
            id: dayCell
            required property int index
            readonly property date d: grid.cell(index)
            readonly property string key: grid.iso(d)
            readonly property bool inMonth: d.getMonth() === grid.first.getMonth()
            readonly property bool isToday: key === grid.iso(new Date())
            readonly property var dayItems: grid.byDay[key] || []
            readonly property int fits: Math.max(0, Math.floor((height - 34) / 25))
            readonly property int shown: dayItems.length > fits ? Math.max(0, fits - 1) : dayItems.length
            x: (index % 7) * grid.cellW
            y: grid.headH + Math.floor(index / 7) * grid.cellH
            width: grid.cellW
            height: grid.cellH

            Rectangle {
                anchors.fill: parent
                color: dayCell.inMonth ? "transparent" : Qt.rgba(0, 0, 0, 0.12)
            }
            Rectangle { anchors.right: parent.right; width: 1; height: parent.height; color: Theme.line; visible: index % 7 !== 6 }
            Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.line }

            // day number: today in an accent square (noir) or circle (slate/sky)
            Rectangle {
                x: 6
                y: 6
                width: 26
                height: 22
                radius: Theme.noir ? 0 : 12
                color: dayCell.isToday ? Theme.accent : numHover.hovered ? Theme.panel2 : "transparent"
                Text {
                    anchors.centerIn: parent
                    text: dayCell.d.getDate()
                    color: dayCell.isToday ? Theme.accentInk : dayCell.inMonth ? Theme.text : Theme.faint
                    font.family: Theme.body
                    font.pixelSize: 13
                    font.weight: dayCell.isToday ? Font.Medium : Font.Normal
                }
                HoverHandler { id: numHover; cursorShape: Qt.PointingHandCursor }
                TapHandler { onTapped: grid.dayRequested(dayCell.d) }
            }

            Column {
                x: 6
                y: 34
                width: parent.width - 12
                spacing: 3
                Repeater {
                    model: dayCell.dayItems.slice(0, dayCell.shown)
                    delegate: CalendarChip {
                        required property var modelData
                        width: parent.width
                        item: modelData
                        opacity: dayCell.inMonth ? 1 : 0.55
                        onOpen: grid.openRequested(modelData.id)
                    }
                }
                Text {
                    visible: dayCell.dayItems.length > dayCell.shown
                    leftPadding: 6
                    text: qsTr("%n more", "", dayCell.dayItems.length - dayCell.shown)
                    color: moreHover.hovered ? Theme.text : Theme.muted
                    font.family: Theme.body
                    font.pixelSize: 12
                    HoverHandler { id: moreHover; cursorShape: Qt.PointingHandCursor }
                    TapHandler { onTapped: grid.dayRequested(dayCell.d) }
                }
            }
            TapHandler {
                // a double click on empty space opens the day too
                onDoubleTapped: grid.dayRequested(dayCell.d)
            }
        }
    }
}
