import QtQuick
import "."

// One calendar item (from ws.calendarItems) as a pill: the daily note in gray, a task in green
// with its circle, anything created in its type colour.
Rectangle {
    id: chip
    property var item: ({})
    signal open()

    readonly property color tone: item.kind === "note" ? Theme.journal
                                : item.kind === "task" ? Theme.ok
                                : Theme.typeColor(item.typeId || "")
    implicitHeight: 22
    radius: Theme.radiusSm
    color: Theme.noir ? (hover.hovered ? Theme.panel2 : "transparent")
                      : Qt.rgba(tone.r, tone.g, tone.b, hover.hovered ? 0.22 : 0.13)
    border.width: Theme.noir ? 0 : 1
    border.color: Qt.rgba(tone.r, tone.g, tone.b, 0.25)

    Row {
        anchors.left: parent.left
        anchors.leftMargin: Theme.noir ? 2 : 6
        anchors.right: parent.right
        anchors.rightMargin: Theme.noir ? 2 : 6
        anchors.verticalCenter: parent.verticalCenter
        spacing: 6
        Rectangle {
            visible: Theme.noir
            anchors.verticalCenter: parent.verticalCenter
            width: 7; height: 7
            color: chip.tone
        }
        TintedIcon {
            id: glyph
            visible: !Theme.noir && !chip.item.iconEmoji
            anchors.verticalCenter: parent.verticalCenter
            name: chip.item.kind === "task" ? (chip.item.done ? "circle-check" : "circle")
                : chip.item.kind === "note" ? "calendar" : Theme.typeIcon(chip.item.typeId || "")
            size: 12
            color: chip.tone
        }
        Text {
            visible: !Theme.noir && !!chip.item.iconEmoji
            anchors.verticalCenter: parent.verticalCenter
            text: chip.item.iconEmoji || ""
            font.pixelSize: 11
        }
        Text {
            width: parent.width - (Theme.noir ? 13 : 17)
            anchors.verticalCenter: parent.verticalCenter
            text: chip.item.kind === "note" ? qsTr("Daily note") : (chip.item.title || qsTr("Untitled"))
            color: chip.item.done ? Theme.muted : (Theme.noir ? Theme.bodyText : Theme.text)
            font.family: Theme.body
            font.pixelSize: 12
            font.strikeout: !!chip.item.done
            elide: Text.ElideRight
        }
    }
    HoverHandler { id: hover; cursorShape: Qt.PointingHandCursor }
    TapHandler { onTapped: chip.open() }
}
