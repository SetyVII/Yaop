import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "."

// Daily: journal entries index. Opening/writing the note uses the shared editor.

Item {
    id: journalPage

    property var appWindow: null
    property int selectedOffset: 0
    signal openObjectRequested(string objectId)
    signal openObjectAtBlockRequested(string objectId, string blockId)

    function isoDay(offset) {
        const d = new Date()
        d.setDate(d.getDate() + offset)
        return Qt.formatDate(d, "yyyy-MM-dd")
    }

    function dateForOffset(offset) {
        const d = new Date()
        d.setDate(d.getDate() + offset)
        return d
    }

    function update() { }

    readonly property int dataRev: (visible && ws) ? ws.listRevision : 0
    readonly property string selectedIso: isoDay(-selectedOffset)
    readonly property var createdObjects: {
        dataRev
        return ws ? ws.objectsCreatedOn(selectedIso) : []
    }
    readonly property var referencedObjects: {
        dataRev
        return ws ? ws.backlinksFor("daily-" + selectedIso) : []
    }

    Flickable {
        anchors.fill: parent
        contentWidth: width
        contentHeight: content.implicitHeight + 2 * Theme.pagePad
        clip: true
        boundsBehavior: Flickable.StopAtBounds

        ColumnLayout {
            id: content
            x: Theme.pagePad
            y: Theme.pagePad
            width: parent.width - 2 * Theme.pagePad
            height: implicitHeight
            spacing: 18

            // strip: last 14 days
            Panel {
                Layout.fillWidth: true
                label: qsTr("DAILY")

                // Wraps onto more rows in a narrow window instead of overflowing sideways.
                Flow {
                    Layout.fillWidth: true
                    spacing: 8

                    Repeater {
                        model: 14

                        delegate: Item {
                            required property int index
                            width: 52
                            height: 68

                            readonly property string iso: journalPage.isoDay(-index)
                            readonly property string dayJournal: ws ? ws.journalLocalFor(iso) : ""

                            Rectangle {
                                anchors.fill: parent
                                radius: Theme.radius
                                color: index === journalPage.selectedOffset ? Theme.accent : (dayHover.hovered ? Theme.panel3 : Theme.panel2)
                                border.width: index === 0 && journalPage.selectedOffset !== 0 ? 1 : 0
                                border.color: Theme.accent

                                ColumnLayout {
                                    anchors.fill: parent
                                    anchors.margins: 8
                                    spacing: 2

                                    Text {
                                        Layout.alignment: Qt.AlignHCenter
                                        text: {
                                            const d = new Date()
                                            d.setDate(d.getDate() - index)
                                            return Qt.formatDate(d, "ddd")
                                        }
                                        color: index === journalPage.selectedOffset ? Theme.accentInk : Theme.muted
                                        font.family: Theme.mono
                                        font.pixelSize: 11
                                    }
                                    Text {
                                        Layout.alignment: Qt.AlignHCenter
                                        text: {
                                            const d = new Date()
                                            d.setDate(d.getDate() - index)
                                            return Qt.formatDate(d, "d")
                                        }
                                        color: index === journalPage.selectedOffset ? Theme.accentInk : Theme.text
                                        font.family: Theme.display
                                        font.pixelSize: 16
                                        font.weight: Font.DemiBold
                                    }
                                    Rectangle {
                                        Layout.alignment: Qt.AlignHCenter
                                        Layout.preferredWidth: 5
                                        Layout.preferredHeight: 5
                                        radius: 3
                                        color: dayJournal ? Theme.journal : "transparent"
                                    }
                                }
                            }

                            HoverHandler { id: dayHover; cursorShape: Qt.PointingHandCursor }
                            TapHandler {
                                onTapped: journalPage.selectedOffset = index
                                onDoubleTapped: {
                                    const existing = ws.journalFor(journalPage.isoDay(-index))
                                    if (existing && existing.length > 0)
                                        openObjectRequested(existing)
                                }
                            }
                        }
                    }
                }
            }

            // selected day's journal quick-open
            Panel {
                Layout.fillWidth: true
                label: journalPage.selectedOffset === 0 ? qsTr("TODAY") : journalPage.selectedIso

                RowLayout {
                    Layout.fillWidth: true
                    spacing: 14

                    ColumnLayout {
                        spacing: 4
                        Layout.fillWidth: true
                        Text {
                            text: Qt.formatDate(journalPage.dateForOffset(-journalPage.selectedOffset), "dddd d MMMM yyyy")
                            color: Theme.text
                            font.family: Theme.display
                            font.pixelSize: 17
                            font.weight: Font.Medium
                        }
                        Text {
                            text: qsTr("Daily entry, references, and objects created on this day.")
                            color: Theme.muted
                            font.family: Theme.body
                            font.pixelSize: 12
                        }
                    }

                    Button {
                        text: journalPage.selectedOffset === 0 ? qsTr("WRITE TODAY") : qsTr("OPEN DAILY")
                        onClicked: {
                            const existing = ws.journalFor(journalPage.selectedIso)
                            if (existing && existing.length > 0)
                                openObjectRequested(existing)
                        }
                    }
                }
            }

            // Habits due on the selected day: tap to log or correct that day
            property int habitRev: 0
            Connections { target: ws; function onObjectPropertiesChanged() { content.habitRev++ } }
            Panel {
                id: habitsPanel
                readonly property var day: {
                    const p = journalPage.selectedIso.split("-")
                    return new Date(Number(p[0]), Number(p[1]) - 1, Number(p[2]), 12)
                }
                readonly property var due: {
                    content.habitRev; journalPage.dataRev
                    return ws ? ws.habits().filter(h => HabitMath.isDue(h, habitsPanel.day)) : []
                }
                visible: due.length > 0
                Layout.fillWidth: true
                label: qsTr("HABITS")
                Flow {
                    Layout.fillWidth: true
                    spacing: 16
                    Repeater {
                        model: habitsPanel.due
                        delegate: Column {
                            id: hd
                            required property var modelData
                            spacing: 4
                            Rectangle {
                                width: 40; height: 40; radius: 20
                                readonly property real p: content.habitRev >= 0 ? HabitMath.progress(hd.modelData, habitsPanel.day) : 0
                                color: p >= 1 ? Theme.ok : Qt.rgba(1, 1, 1, 0.1)
                                border.width: p > 0 && p < 1 ? 3 : 0
                                border.color: Theme.ok
                                Text { anchors.centerIn: parent; text: hd.modelData.emoji || "🌱"; font.pixelSize: 17 }
                                MouseArea { anchors.fill: parent; onClicked: HabitMath.tap(hd.modelData, habitsPanel.day) }
                            }
                            Text { width: 56; text: hd.modelData.title; elide: Text.ElideRight; horizontalAlignment: Text.AlignHCenter; color: Theme.dim; font.pixelSize: 11 }
                        }
                    }
                }
            }

            // Created on this day (parity with mobile Daily)
            Panel {
                Layout.fillWidth: true
                label: qsTr("CREATED ON THIS DAY (%1)").arg(journalPage.createdObjects.length)

                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 0

                    Repeater {
                        model: journalPage.createdObjects
                        delegate: ObjectRow {
                            required property var modelData
                            Layout.fillWidth: true
                            summary: modelData
                            onOpenRequested: journalPage.openObjectRequested(modelData.id)
                        }
                    }

                    Text {
                        visible: journalPage.createdObjects.length === 0
                        text: qsTr("No objects created on this day.")
                        color: Theme.faint
                        font.family: Theme.mono
                        font.pixelSize: 11
                    }
                }
            }

            // Referenced on this day (backlinks to @YYYY-MM-DD / daily-<iso>)
            Panel {
                Layout.fillWidth: true
                label: qsTr("REFERENCED (%1)").arg(journalPage.referencedObjects.length)

                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 6

                    Repeater {
                        model: journalPage.referencedObjects
                        delegate: Rectangle {
                            required property var modelData
                            Layout.fillWidth: true
                            implicitHeight: 54
                            radius: Theme.radius
                            color: refHover.hovered ? Theme.panel2 : "transparent"

                            RowLayout {
                                anchors.fill: parent
                                anchors.leftMargin: 10
                                anchors.rightMargin: 10
                                spacing: 10

                                Rectangle {
                                    Layout.preferredWidth: 4
                                    Layout.preferredHeight: 28
                                    color: Theme.typeColor(modelData.typeId !== undefined ? modelData.typeId : "")
                                }
                                ColumnLayout {
                                    Layout.fillWidth: true
                                    spacing: 2
                                    Text {
                                        Layout.fillWidth: true
                                        text: modelData.title !== undefined ? modelData.title : ""
                                        color: Theme.text
                                        font.family: Theme.body
                                        font.pixelSize: 13
                                        font.weight: Font.Medium
                                        elide: Text.ElideRight
                                    }
                                    Text {
                                        Layout.fillWidth: true
                                        visible: text.length > 0
                                        text: modelData.snippet !== undefined ? modelData.snippet : ""
                                        color: Theme.muted
                                        font.family: Theme.mono
                                        font.pixelSize: 11
                                        elide: Text.ElideRight
                                    }
                                }
                            }
                            HoverHandler { id: refHover; cursorShape: Qt.PointingHandCursor }
                            TapHandler {
                                onTapped: {
                                    if (modelData.blockId && modelData.blockId.length > 0 && journalPage.appWindow)
                                        journalPage.appWindow.openObjectAtBlock(modelData.id, modelData.blockId)
                                    else
                                        journalPage.openObjectRequested(modelData.id)
                                }
                            }
                        }
                    }

                    Text {
                        visible: journalPage.referencedObjects.length === 0
                        text: qsTr("No references to this day yet.")
                        color: Theme.faint
                        font.family: Theme.mono
                        font.pixelSize: 11
                    }
                }
            }

            Connections {
                target: ws
                function onObjectListChanged() { journalPage.update() }
            }
        }
    }
}
