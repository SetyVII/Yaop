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
            width: Math.min(parent.width - 2 * Theme.pagePad, 880)
            x: Math.round((parent.width - width) / 2)
            y: Theme.pagePad
            height: implicitHeight
            spacing: 20

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
                        Layout.topMargin: 6
                        Layout.bottomMargin: 4
                        spacing: 10

                        Rectangle {
                            visible: !Theme.noir
                            Layout.preferredWidth: 6; Layout.preferredHeight: 6; radius: 3; color: Theme.ice
                        }
                        Text {
                            text: Theme.noir
                                ? sectionColumn.modelData.label + " · " + sectionColumn.rows.length
                                : sectionColumn.modelData.label
                            color: Theme.label
                            font.family: Theme.mono
                            font.pixelSize: 11
                            font.letterSpacing: Theme.track(1.4)
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
                            property bool pendingToggle: false
                            readonly property bool visualDone: pendingToggle ? !(taskRow.modelData.taskDone === true)
                                                                             : (taskRow.modelData.taskDone === true)
                            Layout.fillWidth: true
                            Layout.preferredHeight: 48
                            radius: Theme.radius
                            color: taskMouse.hovered ? Theme.panel : "transparent"

                            Rectangle {
                                visible: Theme.noir
                                anchors.bottom: parent.bottom
                                width: parent.width
                                height: 1
                                color: Theme.lineSubtle
                            }

                            Timer {
                                id: toggleTimer
                                interval: 260
                                repeat: false
                                onTriggered: {
                                    ws.completeTask(taskRow.modelData.id)
                                    taskRow.pendingToggle = false
                                }
                            }

                            RowLayout {
                                anchors.fill: parent
                                anchors.leftMargin: Theme.noir ? 4 : 18
                                anchors.rightMargin: Theme.noir ? 4 : 14
                                spacing: 14

                                // Sharp 18x18 square checkbox with 300ms scale(0->1) accent fill in noir (AppDesktop.dc.html)
                                Rectangle {
                                    Layout.preferredWidth: Theme.noir ? 18 : 20
                                    Layout.preferredHeight: Theme.noir ? 18 : 20
                                    radius: Theme.noir ? 0 : 10
                                    color: Theme.noir ? "transparent" : (taskRow.visualDone ? Theme.accent : "transparent")
                                    border.color: taskRow.visualDone ? Theme.accent : (Theme.noir ? Theme.faint : Theme.dim)
                                    border.width: Theme.noir ? 1 : 1.5
                                    Behavior on border.color { ColorAnimation { duration: 200 * Theme.motion } }

                                    Rectangle {
                                        visible: Theme.noir
                                        anchors.fill: parent
                                        anchors.margins: 3
                                        color: Theme.accent
                                        scale: taskRow.visualDone ? 1 : 0
                                        opacity: taskRow.visualDone ? 1 : 0
                                        Behavior on scale {
                                            NumberAnimation {
                                                duration: 300 * Theme.motion
                                                easing.type: Easing.Bezier
                                                easing.bezierCurve: [0.2, 0.8, 0.1, 1, 1, 1]
                                            }
                                        }
                                        Behavior on opacity { NumberAnimation { duration: 200 * Theme.motion } }
                                    }

                                    Text {
                                        anchors.centerIn: parent
                                        visible: !Theme.noir && taskRow.visualDone
                                        text: "✓"
                                        color: Theme.accentInk
                                        font.pixelSize: 12
                                        font.weight: Font.Bold
                                    }
                                    HoverHandler { cursorShape: Qt.PointingHandCursor }
                                    TapHandler {
                                        onTapped: {
                                            if (toggleTimer.running) return
                                            if (Theme.noir && Theme.motion > 0) {
                                                taskRow.pendingToggle = true
                                                toggleTimer.start()
                                            } else {
                                                ws.completeTask(taskRow.modelData.id)
                                            }
                                        }
                                    }
                                }

                                Text {
                                    Layout.fillWidth: true
                                    text: taskRow.modelData.title !== undefined ? taskRow.modelData.title : ""
                                    color: taskRow.visualDone ? Theme.label : Theme.text
                                    Behavior on color { ColorAnimation { duration: 300 * Theme.motion } }
                                    font.family: Theme.body
                                    font.pixelSize: 15
                                    font.strikeout: taskRow.visualDone
                                    elide: Text.ElideRight
                                    TapHandler { onTapped: tasks.openObjectRequested(taskRow.modelData.id) }
                                }

                                Text {
                                    visible: taskRow.modelData.dueDate !== undefined
                                             && String(taskRow.modelData.dueDate).length > 0
                                    text: taskRow.modelData.dueDate !== undefined ? taskRow.modelData.dueDate : ""
                                    color: String(taskRow.modelData.dueDate) < Qt.formatDate(new Date(), "yyyy-MM-dd")
                                           && !taskRow.visualDone ? Theme.late : Theme.label
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
                        Layout.leftMargin: Theme.noir ? 4 : 18
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
