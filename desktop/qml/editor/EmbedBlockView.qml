import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../"

// Object embed: reference card / compact / inline. Click opens the object.

Item {
    id: eb

    property var view
    property int row
    property string targetObjectId
    property string viewMode: "card"

    readonly property var summary: (typeof ws !== "undefined" && targetObjectId.length > 0)
                            ? ws.objectSummary(targetObjectId) : ({})
    readonly property string embeddedType: summary.typeId !== undefined ? summary.typeId : ""
    readonly property string displayTitle: summary.title !== undefined ? summary.title : targetObjectId
    readonly property string snippet: summary.snippet !== undefined ? summary.snippet : ""

    signal openRequested(string objectId)

    implicitHeight: viewMode === "card" ? 86 : viewMode === "compact" ? 44 : 28

    function emitOpen() {
        openRequested(targetObjectId)
        if (view)
            view.openObjectRequested(targetObjectId)
    }

    Rectangle {
        visible: eb.viewMode !== "inline"
        anchors.fill: parent
        anchors.leftMargin: 2
        anchors.rightMargin: 2
        anchors.topMargin: 2
        anchors.bottomMargin: 2
        radius: Theme.radius
        color: embedHover.hovered ? Theme.panel : "transparent"
        Behavior on color { ColorAnimation { duration: Theme.fast } }

        DashedFrame { anchors.fill: parent; radius: Theme.radius; lineColor: embedHover.hovered ? Theme.line3 : Theme.line2 }

        HoverHandler { id: embedHover; cursorShape: Qt.PointingHandCursor }
        TapHandler { onTapped: eb.emitOpen() }

        RowLayout {
            anchors.fill: parent
            anchors.margins: 12
            spacing: 10

            Rectangle {
                Layout.preferredWidth: 42
                Layout.preferredHeight: 42
                radius: 12
                color: Theme.panel2
                TintedIcon {
                    anchors.centerIn: parent
                    name: Theme.typeIcon(eb.embeddedType)
                    size: 18
                    color: Theme.typeColor(eb.embeddedType)
                }
            }

            ColumnLayout {
                spacing: 2
                Layout.fillWidth: true

                Text {
                    text: (Theme.typeLabel(eb.embeddedType).length > 0 ? Theme.typeLabel(eb.embeddedType) : qsTr("OBJECT"))
                    color: Theme.muted
                    font.family: Theme.mono
                    font.pixelSize: 11
                    font.letterSpacing: 1.2
                }
                Text {
                    text: eb.displayTitle
                    color: Theme.text
                    font.family: Theme.body
                    font.pixelSize: 15
                    font.weight: Font.Medium
                    elide: Text.ElideRight
                    Layout.fillWidth: true
                }
                Text {
                    visible: eb.viewMode === "card" && eb.snippet.length > 0
                    text: eb.snippet
                    color: Theme.muted
                    font.family: Theme.body
                    font.pixelSize: 12
                    elide: Text.ElideRight
                    maximumLineCount: 2
                    wrapMode: Text.Wrap
                    Layout.fillWidth: true
                }
            }

        }
    }

    RowLayout {
        visible: eb.viewMode === "inline"
        anchors.left: parent.left
        anchors.leftMargin: 4
        spacing: 5

        Rectangle { Layout.preferredWidth: 6; Layout.preferredHeight: 6; color: Theme.typeColor(eb.embeddedType) }
        Text {
            text: eb.displayTitle
            color: Theme.note
            font.family: Theme.body
            font.pixelSize: 13
            TapHandler { onTapped: eb.emitOpen() }
        }
    }
}
