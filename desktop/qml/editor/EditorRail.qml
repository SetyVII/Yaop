import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../"

// Right rail: outline, backlinks, properties. Quiet mono labels.

Item {
    id: rail

    property var session: null
    property var workspace: null
    property var propEntries: []
    property string newPropName: ""
    property string newPropKind: "text"
    property bool addPropToType: true
    signal scrollToBlockRequested(string blockId)
    signal openObjectRequested(string objectId)
    signal openObjectAtBlockRequested(string objectId, string blockId)

    anchors.fill: undefined

    function refreshProps() {
        if (workspace && session)
            propEntries = workspace.objectPropertyEntries(session.objectId)
        else
            propEntries = []
    }

    onSessionChanged: {
        refreshBacklinks.restart()
        refreshProps()
    }

    Flickable {
        anchors.fill: parent
        contentWidth: width
        contentHeight: railCol.implicitHeight + 16
        clip: true
        boundsBehavior: Flickable.StopAtBounds

        ColumnLayout {
            id: railCol
            width: parent.width
            spacing: 0

        // Outline
        Panel {
            Layout.fillWidth: true
            Layout.alignment: Qt.AlignTop
            label: qsTr("OUTLINE")
            padding: 10

            ListView {
                id: outlineList
                Layout.fillWidth: true
                Layout.preferredHeight: Math.min(240, Math.max(26, contentHeight))
                interactive: contentHeight > height
                clip: true
                spacing: 6
                model: session ? session.outline : []

                delegate: Item {
                    required property var modelData
                    width: outlineList.width
                    height: 22

                    Text {
                        anchors.left: parent.left
                        anchors.leftMargin: (modelData.level - 1) * 10
                        anchors.verticalCenter: parent.verticalCenter
                        text: modelData.text
                        color: modelData.visible ? Theme.dim : Theme.faint
                        font.family: Theme.body
                        font.pixelSize: modelData.level <= 2 ? 12.5 : 11.5
                        font.weight: modelData.level <= 2 ? Font.Medium : Font.Normal
                        elide: Text.ElideRight
                        width: parent.width - (modelData.level - 1) * 10

                        TapHandler {
                            onTapped: scrollToBlockRequested(modelData.id)
                        }
                    }
                }

                Text {
                    visible: outlineList.count === 0
                    anchors.left: parent.left
                    anchors.leftMargin: 2
                    anchors.verticalCenter: parent.verticalCenter
                    text: qsTr("no headings yet")
                    color: Theme.faint
                    font.family: Theme.mono
                    font.pixelSize: 11
                }
            }
        }

        // Backlinks
        Panel {
            id: backlinksPanel
            Layout.fillWidth: true
            Layout.alignment: Qt.AlignTop
            label: qsTr("BACKLINKS")
            padding: 10

            property var backlinks: []

            function refresh() {
                if (workspace && session)
                    workspace.backlinksFor(session.objectId)
            }

            ListView {
                id: backlinksList
                Layout.fillWidth: true
                Layout.preferredHeight: Math.min(320, Math.max(26, contentHeight))
                interactive: contentHeight > height
                clip: true
                spacing: 6
                model: backlinksPanel.backlinks

                delegate: Item {
                    required property var modelData
                    width: backlinksList.width
                    height: 42

                    ColumnLayout {
                        anchors.fill: parent
                        spacing: 1

                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 8

                            Rectangle {
                                Layout.preferredWidth: 3
                                Layout.preferredHeight: 14
                                color: Theme.typeColor(modelData.typeId !== undefined ? modelData.typeId : "")
                            }
                            Text {
                                Layout.fillWidth: true
                                text: modelData.title !== undefined ? modelData.title : ""
                                color: Theme.dim
                                font.family: Theme.body
                                font.pixelSize: 12
                                elide: Text.ElideRight
                                TapHandler {
                                    onTapped: {
                                        if (modelData.blockId && modelData.blockId.length > 0)
                                            rail.openObjectAtBlockRequested(modelData.id, modelData.blockId)
                                        else
                                            rail.openObjectRequested(modelData.id)
                                    }
                                }
                                HoverHandler { cursorShape: Qt.PointingHandCursor }
                            }
                        }

                        // spec §4.5: the reference view carries the snippet of
                        // the block that holds the link, resolved locally.
                        Text {
                            Layout.fillWidth: true
                            Layout.leftMargin: 11
                            text: modelData.snippet !== undefined ? modelData.snippet : ""
                            visible: text.length > 0
                            color: Theme.faint
                            font.family: Theme.mono
                            font.pixelSize: 11
                            elide: Text.ElideRight
                            maximumLineCount: 1
                            TapHandler {
                                onTapped: {
                                    if (modelData.blockId && modelData.blockId.length > 0)
                                        rail.openObjectAtBlockRequested(modelData.id, modelData.blockId)
                                    else
                                        rail.openObjectRequested(modelData.id)
                                }
                            }
                            HoverHandler { cursorShape: Qt.PointingHandCursor }
                        }
                    }
                }

                Text {
                    visible: backlinksList.count === 0
                    anchors.left: parent.left
                    anchors.leftMargin: 2
                    anchors.verticalCenter: parent.verticalCenter
                    text: qsTr("nothing links here")
                    color: Theme.faint
                    font.family: Theme.mono
                    font.pixelSize: 11
                }
            }
        }

        Timer {
            id: refreshBacklinks
            interval: 100
            repeat: false
            onTriggered: backlinksPanel.refresh()
        }

        Connections {
            target: workspace
            function onBacklinksReady(objectId, objects) {
                if (session && objectId === session.objectId)
                    backlinksPanel.backlinks = objects
            }
            function onObjectPropertiesChanged(objectId) {
                if (session && objectId === session.objectId)
                    rail.refreshProps()
            }
            function onTypesChanged() {
                rail.refreshProps()
            }
        }

        // Properties
        Panel {
            Layout.fillWidth: true
            Layout.alignment: Qt.AlignTop
            label: qsTr("PROPERTIES")
            padding: 10

            ColumnLayout {
                Layout.fillWidth: true
                spacing: 8

                Repeater {
                    model: rail.propEntries
                    delegate: RowLayout {
                        required property var modelData
                        Layout.fillWidth: true
                        spacing: 6

                        Text {
                            Layout.preferredWidth: 78
                            text: modelData.name || modelData.key
                            color: Theme.muted
                            font.family: Theme.mono
                            font.pixelSize: 11
                            elide: Text.ElideRight
                        }

                        Rectangle {
                            visible: modelData.kind === "checkbox"
                            Layout.preferredWidth: 16
                            Layout.preferredHeight: 16
                            radius: 3
                            color: modelData.checked ? Theme.accent : Theme.panel
                            border.color: Theme.line2
                            border.width: 1
                            Text {
                                anchors.centerIn: parent
                                visible: modelData.checked
                                text: "✓"
                                color: Theme.page
                                font.pixelSize: 10
                            }
                            TapHandler {
                                onTapped: if (rail.workspace && rail.session)
                                              rail.workspace.setObjectProperty(rail.session.objectId, modelData.key, !modelData.checked, "checkbox")
                            }
                        }

                        Rectangle {
                            visible: modelData.kind !== "checkbox"
                            Layout.fillWidth: true
                            Layout.preferredHeight: 26
                            radius: Theme.radiusSm
                            color: Theme.panel
                            border.color: propInput.activeFocus ? Theme.ice : Theme.line
                            border.width: 1

                            TextInput {
                                id: propInput
                                anchors.fill: parent
                                anchors.leftMargin: 6
                                anchors.rightMargin: 6
                                verticalAlignment: TextInput.AlignVCenter
                                color: Theme.text
                                font.family: Theme.body
                                font.pixelSize: 12
                                clip: true
                                selectByMouse: true
                                text: modelData.value !== undefined ? String(modelData.value) : ""
                                onEditingFinished: {
                                    if (rail.workspace && rail.session && text !== String(modelData.value || ""))
                                        rail.workspace.setObjectProperty(rail.session.objectId, modelData.key, text, modelData.kind)
                                }
                                Text {
                                    anchors.verticalCenter: parent.verticalCenter
                                    visible: propInput.length === 0
                                    text: modelData.kind === "date" ? "YYYY-MM-DD" : qsTr("—")
                                    color: Theme.faint
                                    font: propInput.font
                                }
                            }
                        }
                    }
                }

                // inline add property
                RowLayout {
                    Layout.fillWidth: true
                    spacing: 4

                    Rectangle {
                        Layout.fillWidth: true
                        Layout.preferredHeight: 26
                        radius: Theme.radiusSm
                        color: Theme.panel
                        border.color: newPropInput.activeFocus ? Theme.ice : Theme.line
                        border.width: 1

                        TextInput {
                            id: newPropInput
                            anchors.fill: parent
                            anchors.leftMargin: 6
                            anchors.rightMargin: 6
                            verticalAlignment: TextInput.AlignVCenter
                            color: Theme.text
                            font.family: Theme.body
                            font.pixelSize: 11
                            clip: true
                            onTextChanged: rail.newPropName = text
                            onAccepted: addPropBtn.triggerAdd()
                            Text {
                                anchors.verticalCenter: parent.verticalCenter
                                visible: newPropInput.length === 0
                                text: qsTr("+ Property…")
                                color: Theme.faint
                                font: newPropInput.font
                            }
                        }
                    }

                    Rectangle {
                        Layout.preferredWidth: 56
                        Layout.preferredHeight: 26
                        radius: Theme.radiusSm
                        color: Theme.panel2
                        border.color: Theme.line
                        border.width: 1
                        Text {
                            anchors.centerIn: parent
                            text: rail.newPropKind.toUpperCase()
                            color: Theme.dim
                            font.family: Theme.mono
                            font.pixelSize: 9
                        }
                        TapHandler {
                            onTapped: {
                                const order = ["text", "number", "checkbox", "date", "url"]
                                const i = order.indexOf(rail.newPropKind)
                                rail.newPropKind = order[(i + 1) % order.length]
                            }
                        }
                    }

                    Button {
                        id: addPropBtn
                        text: qsTr("ADD")
                        small: true
                        enabled: rail.newPropName.trim().length > 0
                        function triggerAdd() {
                            const n = rail.newPropName.trim()
                            if (!n || !rail.workspace || !rail.session)
                                return
                            const initVal = rail.newPropKind === "checkbox" ? false : ""
                            rail.workspace.setObjectProperty(rail.session.objectId, n, initVal, rail.newPropKind, n, rail.addPropToType)
                            newPropInput.text = ""
                            rail.newPropName = ""
                        }
                        onClicked: triggerAdd()
                    }
                }
            }
        }

        Item { Layout.fillHeight: true }
        }
    }

    }
