import QtQuick
import QtQuick.Layouts
import "."

// Habit object header: streak, best figures and a 26-week heat calendar (click a day to log it).
Rectangle {
    id: panel
    property string habitId: ""
    property int rev: 0
    Connections { target: ws; function onObjectPropertiesChanged() { panel.rev++ } }
    readonly property var h: { rev; return habitId ? HabitMath.find(habitId) : null }
    visible: h !== null
    implicitHeight: visible ? col.implicitHeight + 28 : 0
    radius: Theme.radius
    color: Theme.panel

    ColumnLayout {
        id: col
        anchors.fill: parent
        anchors.margins: 14
        spacing: 12
        RowLayout {
            spacing: 24
            Repeater {
                model: panel.h ? [
                    { v: HabitMath.streak(panel.h), l: qsTr("day streak") },
                    { v: HabitMath.rate30(panel.h) + "%", l: qsTr("30 days") }
                ] : []
                delegate: Column {
                    required property var modelData
                    Text { text: modelData.v; color: Theme.text; font.family: Theme.display; font.pixelSize: 30; font.weight: Font.Medium }
                    Text { text: modelData.l; color: Theme.dim; font.pixelSize: 12 }
                }
            }
        }
        Row {
            spacing: 3
            Repeater {
                model: 26
                delegate: Column {
                    id: wk
                    required property int index
                    spacing: 3
                    Repeater {
                        model: 7
                        delegate: Rectangle {
                            required property int index
                            readonly property date day: {
                                const t = new Date(); t.setHours(12)
                                const mon = new Date(t.getTime() - ((t.getDay() + 6) % 7) * 864e5)
                                return new Date(mon.getTime() + ((wk.index - 25) * 7 + index) * 864e5)
                            }
                            readonly property bool future: day.getTime() > Date.now()
                            width: 12; height: 12; radius: 3
                            color: future ? "transparent"
                                 : HabitMath.progress(panel.h, day) >= 1 ? Theme.ok
                                 : HabitMath.progress(panel.h, day) > 0 ? Qt.rgba(0.53, 0.91, 0.66, 0.35)
                                 : Qt.rgba(1, 1, 1, 0.07)
                            MouseArea { anchors.fill: parent; enabled: !parent.future; onClicked: HabitMath.tap(panel.h, parent.day) }
                        }
                    }
                }
            }
        }
    }
}
