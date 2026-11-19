import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "."

// Inline property table under the object's tags (Name | Value, "Empty" when blank).
// Excludes url/description/tags when displayed in the object header.
Item {
    id: table

    property string objectId: ""
    property bool hideWebHeaderProps: false
    property real labelWidth: 118

    property int rev: 0
    property var entries: []

    function refresh() {
        if (!ws || !objectId) {
            entries = []
            return
        }
        const raw = ws.objectPropertyEntries(objectId)
        const skip = hideWebHeaderProps
                   ? ["tags", "url", "description", "thumbnail_url", "favicon_url", "cover_image", "is_bookmark"]
                   : ["tags"]
        entries = raw.filter(e => skip.indexOf(e.key) < 0)
    }

    onObjectIdChanged: refresh()
    onHideWebHeaderPropsChanged: refresh()

    Connections {
        target: ws
        function onObjectPropertiesChanged(id) {
            if (id === table.objectId)
                table.refresh()
        }
        function onTypesChanged() { table.refresh() }
    }

    visible: entries.length > 0
    implicitHeight: entries.length > 0 ? Math.min(160, propCol.implicitHeight) : 0
    Layout.fillHeight: false
    clip: true

    Flickable {
        anchors.fill: parent
        contentWidth: width
        contentHeight: propCol.implicitHeight
        boundsBehavior: Flickable.StopAtBounds
        clip: true
        ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }

    ColumnLayout {
        id: propCol
        width: parent.width
        spacing: 0

    Repeater {
        model: table.entries
        delegate: Item {
            id: propRow
            required property var modelData
            required property int index
            Layout.fillWidth: true
            implicitHeight: 30

            Rectangle {
                anchors.top: parent.top
                width: parent.width
                height: 1
                visible: propRow.index > 0
                color: Theme.line
            }

            RowLayout {
                anchors.fill: parent
                spacing: 0

                // Property name
                RowLayout {
                    Layout.fillWidth: false
                    Layout.minimumWidth: table.labelWidth
                    Layout.maximumWidth: table.labelWidth
                    Layout.preferredWidth: table.labelWidth
                    Layout.fillHeight: true
                    spacing: 7

                    TintedIcon {
                        name: modelData.kind === "checkbox" ? "circle-check"
                            : modelData.kind === "date" ? "calendar"
                            : modelData.kind === "url" ? "link"
                            : modelData.kind === "number" ? "sigma"
                            : "text"
                        size: 13
                        color: Theme.muted
                    }
                    Text {
                        Layout.fillWidth: true
                        text: modelData.name || modelData.key
                        color: Theme.muted
                        font.family: Theme.body
                        font.pixelSize: 13
                        elide: Text.ElideRight
                    }
                }

                // Vertical column separator
                Rectangle {
                    Layout.preferredWidth: 1
                    Layout.fillHeight: true
                    Layout.topMargin: 4
                    Layout.bottomMargin: 4
                    color: Theme.line
                }

                // Value cell
                Item {
                    Layout.fillWidth: true
                    Layout.preferredWidth: 1
                    Layout.fillHeight: true
                    Layout.leftMargin: 12

                    // Checkbox property
                    Rectangle {
                        visible: modelData.kind === "checkbox"
                        anchors.verticalCenter: parent.verticalCenter
                        width: 16
                        height: 16
                        radius: 4
                        color: modelData.checked ? Theme.ice : Theme.panel2
                        border.color: modelData.checked ? Theme.ice : Theme.line2
                        border.width: 1
                        TintedIcon {
                            anchors.centerIn: parent
                            visible: modelData.checked
                            name: "check"
                            size: 11
                            color: Theme.accentInk
                        }
                        HoverHandler { cursorShape: Qt.PointingHandCursor }
                        TapHandler {
                            onTapped: if (ws && table.objectId)
                                ws.setObjectProperty(table.objectId, modelData.key, !modelData.checked, "checkbox")
                        }
                    }

                    // Inline text / number / date / url input
                    TextInput {
                        id: valInput
                        visible: modelData.kind !== "checkbox"
                        anchors.fill: parent
                        verticalAlignment: TextInput.AlignVCenter
                        color: Theme.text
                        selectionColor: Qt.rgba(0.49, 0.65, 1, 0.35)
                        selectedTextColor: Theme.text
                        font.family: Theme.body
                        font.pixelSize: 13
                        clip: true
                        selectByMouse: true
                        text: modelData.value !== undefined ? String(modelData.value) : ""
                        onEditingFinished: {
                            if (ws && table.objectId && text !== String(modelData.value || ""))
                                ws.setObjectProperty(table.objectId, modelData.key, text, modelData.kind)
                        }
                        Keys.onReturnPressed: focus = false
                        Keys.onEnterPressed: focus = false

                        Text {
                            anchors.verticalCenter: parent.verticalCenter
                            visible: valInput.length === 0 && !valInput.activeFocus
                            text: modelData.kind === "date" ? "YYYY-MM-DD" : qsTr("Empty")
                            color: Theme.faint
                            font: valInput.font
                        }
                    }
                }
            }
        }
    }
    }
    }
}
