import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "."

// Por revisar: shared links/images and quick captures waiting to be sorted (reviewed == false).
Item {
    id: page
    property var appWindow: null
    signal openObjectRequested(string objectId)
    readonly property int dataRev: (visible && ws) ? ws.listRevision : 0
    property int rev: 0
    Connections { target: ws; function onObjectPropertiesChanged() { page.rev++ } }
    readonly property var items: { dataRev; rev; return ws ? ws.inbox() : [] }

    Flickable {
        anchors.fill: parent
        contentWidth: width
        contentHeight: col.implicitHeight + 2 * Theme.pagePad
        clip: true
        ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }
        ColumnLayout {
            id: col
            x: Theme.pagePad; y: Theme.pagePad
            width: parent.width - 2 * Theme.pagePad
            spacing: 8
            Text { text: qsTr("To review"); color: Theme.text; font.family: Theme.display; font.pixelSize: 30; font.weight: Font.Medium }
            Text { visible: page.items.length === 0; text: qsTr("Nothing to review"); color: Theme.dim; font.pixelSize: 14 }
            Repeater {
                model: page.items
                delegate: Rectangle {
                    id: row
                    required property var modelData
                    Layout.fillWidth: true
                    Layout.preferredHeight: 52
                    radius: Theme.radius
                    color: Theme.panel
                    Text {
                        anchors.left: parent.left; anchors.leftMargin: 14
                        anchors.right: markBtn.left; anchors.rightMargin: 10
                        anchors.verticalCenter: parent.verticalCenter
                        text: row.modelData.title || qsTr("Untitled")
                        color: Theme.text; font.pixelSize: 15; elide: Text.ElideRight
                        MouseArea { anchors.fill: parent; onClicked: page.openObjectRequested(row.modelData.id) }
                    }
                    Text {
                        id: markBtn
                        anchors.right: parent.right; anchors.rightMargin: 14
                        anchors.verticalCenter: parent.verticalCenter
                        text: qsTr("Mark reviewed"); color: Theme.ice; font.pixelSize: 13
                        MouseArea { anchors.fill: parent; anchors.margins: -8; onClicked: ws.markReviewed(row.modelData.id) }
                    }
                }
            }
        }
    }
}
