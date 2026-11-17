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
    color: Qt.rgba(tone.r, tone.g, tone.b, hover.hovered ? 0.22 : 0.13)
    border.width: 1
    border.color: Qt.rgba(tone.r, tone.g, tone.b, 0.25)

    Row {
        anchors.left: parent.left
        anchors.leftMargin: 6
        anchors.right: parent.right
        anchors.rightMargin: 6
        anchors.verticalCenter: parent.verticalCenter
        spacing: 5
        TintedIcon {
            id: glyph
            visible: !chip.item.iconEmoji
            anchors.verticalCenter: parent.verticalCenter
            name: chip.item.kind === "task" ? (chip.item.done ? "circle-check" : "circle")
                : chip.item.kind === "note" ? "calendar" : Theme.typeIcon(chip.item.typeId || "")
            size: 12
            color: chip.tone
        }
        Text {
            visible: !!chip.item.iconEmoji
            anchors.verticalCenter: parent.verticalCenter
            text: chip.item.iconEmoji || ""
            font.pixelSize: 11
        }
        Text {
            width: parent.width - 17
            anchors.verticalCenter: parent.verticalCenter
            text: chip.item.kind === "note" ? qsTr("Daily note") : (chip.item.title || qsTr("Untitled"))
            color: chip.item.done ? Theme.muted : Theme.text
            font.family: Theme.body
            font.pixelSize: 12
            font.strikeout: !!chip.item.done
            elide: Text.ElideRight
        }
    }
    HoverHandler { id: hover; cursorShape: Qt.PointingHandCursor }
    TapHandler { onTapped: chip.open() }
}
