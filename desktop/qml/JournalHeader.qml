import QtQuick
import QtQuick.Layouts
import "."

// Header of an opened daily note: coloured weekday, Today badge, long local date, week number
// and ‹ Today › to move by day. The date comes from the note's `date` property, never the title.

ColumnLayout {
    id: jh

    property string iso: ""           // yyyy-MM-dd of this note
    signal dayRequested(string iso)   // open the daily note of another day

    readonly property date day: iso.length === 10
        ? new Date(Number(iso.substr(0, 4)), Number(iso.substr(5, 2)) - 1, Number(iso.substr(8, 2)))
        : new Date()
    readonly property string todayIso: Qt.formatDate(new Date(), "yyyy-MM-dd")
    readonly property bool isToday: iso === todayIso

    // One colour per weekday, Sunday first (Date.getDay order).
    readonly property var weekdayColors: [Theme.late, Theme.ice, Theme.violet, Theme.ok,
                                          Theme.warm, Theme.image, Theme.weblink]

    function shifted(days) {
        const d = new Date(day.getFullYear(), day.getMonth(), day.getDate() + days)
        return Qt.formatDate(d, "yyyy-MM-dd")
    }

    // ISO 8601 week: the week with the year's first Thursday is week 1.
    function isoWeek(d) {
        const t = new Date(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()))
        const dow = t.getUTCDay() || 7
        t.setUTCDate(t.getUTCDate() + 4 - dow)
        const yearStart = new Date(Date.UTC(t.getUTCFullYear(), 0, 1))
        return Math.ceil(((t - yearStart) / 86400000 + 1) / 7)
    }

    spacing: 2

    RowLayout {
        Layout.fillWidth: true
        spacing: 8

        Text {
            text: Qt.locale().standaloneDayName(jh.day.getDay(), Locale.LongFormat)
            color: jh.weekdayColors[jh.day.getDay()]
            font.family: Theme.body
            font.pixelSize: 14
            font.capitalization: Font.Capitalize
        }
        Rectangle {
            visible: jh.isToday
            implicitWidth: todayLbl.implicitWidth + 12; implicitHeight: 20
            radius: Theme.radiusSm
            color: Qt.rgba(Theme.late.r, Theme.late.g, Theme.late.b, 0.16)
            Text {
                id: todayLbl; anchors.centerIn: parent
                text: qsTr("Today")
                color: Theme.late; font.family: Theme.body; font.pixelSize: 12
            }
        }
        Item { Layout.fillWidth: true }

        IconButton { icon: "chevron-left"; tip: qsTr("Previous day"); onClicked: jh.dayRequested(jh.shifted(-1)) }
        Rectangle {
            implicitWidth: todayBtn.implicitWidth + 14; implicitHeight: 26
            radius: Theme.radiusSm
            color: todayHov.hovered && !jh.isToday ? Theme.panel2 : "transparent"
            border.width: 1; border.color: Theme.line2
            Text {
                id: todayBtn; anchors.centerIn: parent
                text: qsTr("Today")
                color: jh.isToday ? Theme.muted : Theme.bodyText
                font.family: Theme.body; font.pixelSize: 12
            }
            HoverHandler { id: todayHov; cursorShape: jh.isToday ? Qt.ArrowCursor : Qt.PointingHandCursor }
            TapHandler { enabled: !jh.isToday; onTapped: jh.dayRequested(jh.todayIso) }
        }
        IconButton { icon: "chevron-right"; tip: qsTr("Next day"); onClicked: jh.dayRequested(jh.shifted(1)) }
    }

    Flow {
        Layout.fillWidth: true
        spacing: 12
        Text {
            // the locale's long date without the weekday (it is the line above)
            text: jh.day.toLocaleDateString(Qt.locale(),
                      Qt.locale().dateFormat(Locale.LongFormat).replace(/d{4}[,.]?\s*/, ""))
            color: Theme.text
            font.family: Theme.display
            font.pixelSize: 28
            font.weight: Font.DemiBold
            font.letterSpacing: -0.4
            wrapMode: Text.WordWrap
        }
        Text {
            height: 38
            verticalAlignment: Text.AlignBottom
            bottomPadding: 6
            text: qsTr("Week %1").arg(jh.isoWeek(jh.day))
            color: Theme.muted
            font.family: Theme.body
            font.pixelSize: 13
        }
    }
}
