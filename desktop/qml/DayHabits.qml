import QtQuick
import QtQuick.Layouts
import "."

// The habits due on a day, checked off right there. A ring fills toward a daily goal; the flame
// is the current streak.
ColumnLayout {
    id: dh

    property date day: new Date()
    property int rev: 0
    signal allRequested()
    signal newRequested()
    signal openRequested(string habitId)

    readonly property var due: { rev; return ws ? ws.habits().filter(h => HabitMath.isDue(h, dh.day)) : [] }
    readonly property int doneCount: { rev; return due.filter(h => HabitMath.met(h, dh.day)).length }
    readonly property bool future: Qt.formatDate(day, "yyyy-MM-dd") > Qt.formatDate(new Date(), "yyyy-MM-dd")

    spacing: 4

    RowLayout {
        Layout.fillWidth: true
        Layout.bottomMargin: 6
        spacing: 8
        TintedIcon { name: "circle-check"; size: 17; color: Theme.dim }
        Text {
            text: qsTr("Habits")
            color: Theme.text
            font.family: Theme.body
            font.pixelSize: 15
            font.weight: Font.DemiBold
        }
        Text {
            visible: dh.due.length > 0
            text: dh.doneCount + "/" + dh.due.length
            color: Theme.muted
            font.family: Theme.body
            font.pixelSize: 13
        }
        Item { Layout.fillWidth: true }
        Text {
            text: qsTr("All")
            color: allHover.hovered ? Theme.text : Theme.muted
            font.family: Theme.body
            font.pixelSize: 13
            HoverHandler { id: allHover; cursorShape: Qt.PointingHandCursor }
            TapHandler { onTapped: dh.allRequested() }
        }
    }

    Repeater {
        model: dh.due
        delegate: Rectangle {
            id: row
            required property var modelData
            readonly property real p: dh.rev >= 0 ? HabitMath.progress(modelData, dh.day) : 0
            readonly property int streak: dh.rev >= 0 ? HabitMath.streak(modelData) : 0
            Layout.fillWidth: true
            implicitHeight: 38
            radius: Theme.radius
            color: rowHover.hovered ? Theme.panel2 : "transparent"

            RowLayout {
                anchors.fill: parent
                anchors.leftMargin: 6
                anchors.rightMargin: 8
                spacing: 10

                // Sharp animated square in noir (AppDesktop.dc.html); progress ring in slate/sky
                Item {
                    Layout.preferredWidth: Theme.noir ? 18 : 24
                    Layout.preferredHeight: Theme.noir ? 18 : 24
                    opacity: dh.future ? 0.4 : 1

                    Rectangle {
                        visible: Theme.noir
                        anchors.fill: parent
                        color: "transparent"
                        border.width: 1
                        border.color: row.p >= 1 ? Theme.accent : (row.p > 0 ? Theme.ok : Theme.faint)
                        Behavior on border.color { ColorAnimation { duration: 200 * Theme.motion } }

                        // Counted partial progress bar from bottom
                        Rectangle {
                            visible: row.p > 0 && row.p < 1
                            anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom
                            anchors.margins: 3
                            height: Math.max(2, Math.round((parent.height - 6) * row.p))
                            color: Theme.ok
                            Behavior on height { NumberAnimation { duration: 250 * Theme.motion; easing.type: Easing.Bezier; easing.bezierCurve: [0.2, 0.8, 0.1, 1, 1, 1] } }
                        }
                        // Full completion inner square with mockup scale animation
                        Rectangle {
                            anchors.fill: parent; anchors.margins: 3
                            color: Theme.accent
                            scale: row.p >= 1 ? 1 : 0
                            opacity: row.p >= 1 ? 1 : 0
                            Behavior on scale { NumberAnimation { duration: 300 * Theme.motion; easing.type: Easing.Bezier; easing.bezierCurve: [0.2, 0.8, 0.1, 1, 1, 1] } }
                            Behavior on opacity { NumberAnimation { duration: 200 * Theme.motion } }
                        }
                    }

                    Canvas {
                        visible: !Theme.noir
                        anchors.fill: parent
                        property real p: row.p
                        property color track: Theme.line3
                        property color fill: Theme.ok
                        onPChanged: requestPaint()
                        onPaint: {
                            const ctx = getContext("2d")
                            ctx.reset()
                            ctx.lineWidth = 2
                            ctx.strokeStyle = track
                            ctx.beginPath(); ctx.arc(12, 12, 10, 0, 2 * Math.PI); ctx.stroke()
                            if (p >= 1) {
                                ctx.fillStyle = fill
                                ctx.beginPath(); ctx.arc(12, 12, 11, 0, 2 * Math.PI); ctx.fill()
                            } else if (p > 0) {
                                ctx.strokeStyle = fill
                                ctx.beginPath(); ctx.arc(12, 12, 10, -Math.PI / 2, -Math.PI / 2 + p * 2 * Math.PI); ctx.stroke()
                            }
                        }
                    }
                    TintedIcon {
                        visible: !Theme.noir && row.p >= 1
                        anchors.centerIn: parent
                        name: "check"
                        size: 14
                        color: Theme.accentInk
                    }
                    HoverHandler { cursorShape: dh.future ? Qt.ArrowCursor : Qt.PointingHandCursor }
                    TapHandler { enabled: !dh.future; onTapped: HabitMath.tap(row.modelData, dh.day) }
                }
                Text {
                    Layout.fillWidth: true
                    text: (row.modelData.emoji ? row.modelData.emoji + " " : "") + row.modelData.title
                    color: Theme.text
                    font.family: Theme.body
                    font.pixelSize: 14
                    elide: Text.ElideRight
                    TapHandler { onTapped: dh.openRequested(row.modelData.id) }
                }
                Row {
                    visible: row.streak > 0
                    spacing: 3
                    TintedIcon { anchors.verticalCenter: parent.verticalCenter; name: "flame"; size: 13; color: Theme.warm }
                    Text {
                        text: row.streak
                        color: Theme.warm
                        font.family: Theme.body
                        font.pixelSize: 12
                        font.weight: Font.Medium
                    }
                }
            }
            HoverHandler { id: rowHover }
        }
    }

    Text {
        visible: dh.due.length === 0
        text: qsTr("No habits due this day.")
        color: Theme.muted
        font.family: Theme.body
        font.pixelSize: 13
    }

    Text {
        Layout.topMargin: 4
        Layout.leftMargin: 6
        text: "+  " + qsTr("New habit")
        color: newHover.hovered ? Theme.text : Theme.muted
        font.family: Theme.body
        font.pixelSize: 13
        HoverHandler { id: newHover; cursorShape: Qt.PointingHandCursor }
        TapHandler { onTapped: dh.newRequested() }
    }
}
