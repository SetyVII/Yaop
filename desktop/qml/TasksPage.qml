import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "."

// Tasks: today / upcoming / done with in-place completion.
// Sections are plain column rows (no nested panels): proven pattern.

Item {
    id: tasks

    property var appWindow: null
    signal openObjectRequested(string objectId)

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
            spacing: 14

            // quick add
            Panel {
                Layout.fillWidth: true
                label: Theme.caps(qsTr("QUICK ADD"))

                RowLayout {
                    Layout.fillWidth: true
                    spacing: 12

                    TextInput {
                        id: taskInput
                        Layout.fillWidth: true
                        Layout.minimumHeight: 32
                        verticalAlignment: TextInput.AlignVCenter
                        color: Theme.text
                        font.family: Theme.body
                        font.pixelSize: 14
                        clip: true

                        Text { anchors.verticalCenter: parent.verticalCenter; visible: taskInput.length === 0; text: qsTr("New task…"); color: Theme.faint; font: taskInput.font }

                        onAccepted: {
                            if (text.trim().length > 0) {
                                ws.createObject("task", text.trim())
                                text = ""
                            }
                        }
                    }
                    Button {
                        text: Theme.caps(qsTr("ADD"))
                        small: true
                        enabled: taskInput.text.trim().length > 0
                        onClicked: {
                            ws.createObject("task", taskInput.text.trim())
                            taskInput.text = ""
                        }
                    }
                }
            }

            // sections: header + flow of rows (the Library-proven pattern)
            Repeater {
                id: sectionsRepeater
                model: [
                    { key: "today", label: Theme.caps(qsTr("TODAY")), emptyText: qsTr("nothing due today") },
                    { key: "upcoming", label: Theme.caps(qsTr("UPCOMING")), emptyText: qsTr("nothing upcoming") },
                    { key: "done", label: Theme.caps(qsTr("DONE")), emptyText: qsTr("nothing completed") }
                ]

                delegate: ColumnLayout {
                    id: sectionColumn
                    required property var modelData
                    Layout.fillWidth: true
                    spacing: 4
                    property var rows: []

                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 10

                        Rectangle { Layout.preferredWidth: 6; Layout.preferredHeight: 6; radius: 3; color: Theme.ice }
                        Text {
                            text: sectionColumn.modelData.label
                            color: Theme.muted
                            font.family: Theme.mono
                            font.pixelSize: 11
                            font.letterSpacing: Theme.track(1.6)
                            font.weight: Font.Medium
                        }
                        Rectangle {
                            Layout.fillWidth: true
                            Layout.preferredHeight: 1
                            color: Theme.line
                        }
                    }

                    Repeater {
                        model: sectionColumn.rows

                        delegate: Rectangle {
                            id: taskRow
                            required property var modelData
                            Layout.fillWidth: true
                            Layout.preferredHeight: 52
                            radius: Theme.radius
                            color: taskMouse.hovered ? Theme.panel : "transparent"

                            RowLayout {
                                anchors.fill: parent
                                anchors.leftMargin: 18
                                anchors.rightMargin: 14
                                spacing: 10

                                // circular check: marked = white fill with an ink tick (spec §5)
                                Rectangle {
                                    Layout.preferredWidth: 20
                                    Layout.preferredHeight: 20
                                    radius: 10
                                    color: taskRow.modelData.taskDone === true ? Theme.accent : "transparent"
                                    border.color: taskRow.modelData.taskDone === true ? Theme.accent : Theme.dim
                                    border.width: 1.5

                                    Text {
                                        anchors.centerIn: parent
                                        visible: taskRow.modelData.taskDone === true
                                        text: "✓"
                                        color: Theme.accentInk
                                        font.pixelSize: 12
                                        font.weight: Font.Bold
                                    }
                                    HoverHandler { cursorShape: Qt.PointingHandCursor }
                                    TapHandler { onTapped: ws.completeTask(taskRow.modelData.id) }
                                }

                                Text {
                                    Layout.fillWidth: true
                                    text: taskRow.modelData.title !== undefined ? taskRow.modelData.title : ""
                                    color: taskRow.modelData.taskDone === true ? Theme.muted : Theme.text
                                    font.family: Theme.body
                                    font.pixelSize: 15
                                    font.strikeout: taskRow.modelData.taskDone === true
                                    elide: Text.ElideRight
                                    TapHandler { onTapped: tasks.openObjectRequested(taskRow.modelData.id) }
                                }

                                Text {
                                    visible: taskRow.modelData.dueDate !== undefined
                                             && String(taskRow.modelData.dueDate).length > 0
                                    text: taskRow.modelData.dueDate !== undefined ? taskRow.modelData.dueDate : ""
                                    color: String(taskRow.modelData.dueDate) < Qt.formatDate(new Date(), "yyyy-MM-dd")
                                           && taskRow.modelData.taskDone !== true ? Theme.late : Theme.muted
                                    font.family: Theme.mono
                                    font.pixelSize: 11
                                }
                            }

                            HoverHandler { id: taskMouse }
                        }
                    }

                    Text {
                        visible: sectionColumn.rows.length === 0
                        text: sectionColumn.modelData.emptyText
                        color: Theme.faint
                        font.family: Theme.mono
                        font.pixelSize: 11
                        Layout.leftMargin: 18
                    }
                }
            }

            Item { Layout.fillHeight: true }
        }
    }

    // Imperative, signal-driven refresh (deterministic after async pulls).
    function refreshSections() {
        const sections = sectionsRepeater
        for (let i = 0; i < sections.count; i++) {
            const item = sections.itemAt(i)
            if (!item)
                continue
            const key = item.modelData.key
            item.rows = ws ? ws.tasksFor(key) : []
        }
    }

    Component.onCompleted: refreshSections()

    Connections {
        target: ws
        function onObjectListChanged() {
            tasks.refreshSections()
        }
    }
}
