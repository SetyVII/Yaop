import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "."

// Small month beside the day: dots under a day say what it holds (one colour each, see `kinds`),
// a legend underneath. Clicking a day selects it.
ColumnLayout {
    id: mm

    property date selected: new Date()
    property int rev: 0
    property var filters: ({ note: true, tasks: true, created: true, refs: true })
    signal picked(date day)

    // shown month (first day, noon to stay clear of DST edges)
    property date month: new Date(selected.getFullYear(), selected.getMonth(), 1, 12)
    onSelectedChanged: {
        if (selected.getMonth() !== month.getMonth() || selected.getFullYear() !== month.getFullYear())
            month = new Date(selected.getFullYear(), selected.getMonth(), 1, 12)
    }

    readonly property var kinds: [
        { key: "note", label: qsTr("Daily note"), color: Theme.journal },
        { key: "tasks", label: qsTr("Tasks"), color: Theme.ok },
        { key: "created", label: qsTr("Created"), color: Theme.violet },
        { key: "refs", label: qsTr("References"), color: Theme.warm }
    ]
    readonly property int firstDow: Qt.locale().firstDayOfWeek % 7
    readonly property date gridStart: {
        const lead = (month.getDay() - firstDow + 7) % 7
        return new Date(month.getFullYear(), month.getMonth(), 1 - lead, 12)
    }
    function cell(i) { return new Date(gridStart.getFullYear(), gridStart.getMonth(), gridStart.getDate() + i, 12) }
    function iso(d) { return Qt.formatDate(d, "yyyy-MM-dd") }
    readonly property var marks: { rev; return ws ? ws.dayMarks(iso(cell(0)), iso(cell(41))) : ({}) }

    spacing: 10

    RowLayout {
        Layout.fillWidth: true
        Text {
            Layout.fillWidth: true
            text: Qt.locale().standaloneMonthName(mm.month.getMonth()) + " " + mm.month.getFullYear()
            color: Theme.text
            font.family: Theme.body
            font.pixelSize: 15
            font.weight: Font.DemiBold
        }
        IconButton {
            implicitWidth: 28; implicitHeight: 28
            icon: "chevron-left"
            onClicked: mm.month = new Date(mm.month.getFullYear(), mm.month.getMonth() - 1, 1, 12)
        }
        IconButton {
            implicitWidth: 28; implicitHeight: 28
            icon: "chevron-right"
            onClicked: mm.month = new Date(mm.month.getFullYear(), mm.month.getMonth() + 1, 1, 12)
        }
    }

    GridLayout {
        Layout.fillWidth: true
        columns: 7
        columnSpacing: 0
        rowSpacing: 2

        Repeater {
            model: 7
            delegate: Text {
                required property int index
                Layout.fillWidth: true
                Layout.preferredWidth: 1
                horizontalAlignment: Text.AlignHCenter
                text: Qt.locale().dayName((mm.firstDow + index) % 7, Locale.ShortFormat).substring(0, 2)
                color: Theme.muted
                font.family: Theme.body
                font.pixelSize: 11
            }
        }

        Repeater {
            model: 42
            delegate: Item {
                id: dayCell
                required property int index
                readonly property date d: mm.cell(index)
                readonly property string key: mm.iso(d)
                readonly property bool inMonth: d.getMonth() === mm.month.getMonth()
                readonly property bool isSelected: key === mm.iso(mm.selected)
                readonly property bool isToday: key === mm.iso(new Date())
                readonly property var m: mm.marks[key] || ({})
                visible: index < 35 || mm.cell(35).getMonth() === mm.month.getMonth()
                Layout.fillWidth: true
                Layout.preferredWidth: 1
                Layout.preferredHeight: 38

                Rectangle {
                    anchors.centerIn: parent
                    width: 32
                    height: 36
                    radius: Theme.radius
                    color: dayCell.isSelected ? Theme.accent : cellHover.hovered ? Theme.panel2 : "transparent"
                }
                Text {
                    anchors.horizontalCenter: parent.horizontalCenter
                    y: 6
                    text: dayCell.d.getDate()
                    color: dayCell.isSelected ? Theme.accentInk
                         : dayCell.isToday ? Theme.ice
                         : dayCell.inMonth ? Theme.text : Theme.faint
                    font.family: Theme.body
                    font.pixelSize: 13
                    font.weight: dayCell.isSelected || dayCell.isToday ? Font.DemiBold : Font.Normal
                }
                Row {
                    anchors.horizontalCenter: parent.horizontalCenter
                    y: 26
                    spacing: 2
                    Repeater {
                        model: mm.kinds.filter(k => mm.filters[k.key] && dayCell.m[k.key])
                        delegate: Rectangle {
                            required property var modelData
                            width: 4; height: 4; radius: 2
                            color: dayCell.isSelected ? Theme.accentInk : modelData.color
                            opacity: dayCell.inMonth ? 1 : 0.5
                        }
                    }
                }
                HoverHandler { id: cellHover; cursorShape: Qt.PointingHandCursor }
                TapHandler { onTapped: mm.picked(dayCell.d) }
                ToolTip.visible: cellHover.hovered && Object.keys(dayCell.m).length > 0
                ToolTip.delay: 300
                ToolTip.text: mm.kinds.filter(k => dayCell.m[k.key])
                                  .map(k => k.key === "note" ? k.label : k.label + " · " + dayCell.m[k.key]).join("\n")
            }
        }
    }

    // legend
    Flow {
        Layout.fillWidth: true
        spacing: 12
        Repeater {
            model: mm.kinds.filter(k => mm.filters[k.key])
            delegate: Row {
                required property var modelData
                spacing: 5
                Rectangle {
                    anchors.verticalCenter: parent.verticalCenter
                    width: 6; height: 6; radius: 3
                    color: modelData.color
                }
                Text {
                    text: modelData.label
                    color: Theme.muted
                    font.family: Theme.body
                    font.pixelSize: 12
                }
            }
        }
    }
}
