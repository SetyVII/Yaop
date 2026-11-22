import QtQuick
import QtQuick.Layouts
import "../"

// Floating action bar shown when one or more blocks are selected in BlocksView.
Rectangle {
    id: bar

    property var view
    readonly property int count: view && view.session ? view.session.selectedCount : 0

    visible: count > 0
    z: 70
    anchors.horizontalCenter: parent ? parent.horizontalCenter : undefined
    anchors.bottom: parent ? parent.bottom : undefined
    anchors.bottomMargin: 16
    width: row.implicitWidth + 20
    height: 38
    radius: Theme.noir ? 0 : 19
    color: Theme.noir ? Theme.sheet : Theme.panel2
    border.width: 1
    border.color: Theme.line2

    component BarBtn: Item {
        id: btn
        property string label
        property bool danger: false
        signal clicked()

        Layout.preferredWidth: txt.implicitWidth + 14
        Layout.preferredHeight: 28

        Rectangle {
            anchors.fill: parent
            radius: 14
            color: hover.hovered ? (btn.danger ? Qt.rgba(1, 0.47, 0.43, 0.16) : Theme.panel3) : "transparent"
        }

        Text {
            id: txt
            anchors.centerIn: parent
            text: btn.label
            color: btn.danger ? Theme.danger : Theme.text
            font.family: Theme.mono
            font.pixelSize: 11
            font.letterSpacing: Theme.track(0.4)
        }

        HoverHandler { id: hover; cursorShape: Qt.PointingHandCursor }
        TapHandler { onTapped: btn.clicked() }
    }

    RowLayout {
        id: row
        anchors.centerIn: parent
        spacing: 2

        Text {
            text: qsTr("%1 blocks").arg(bar.count)
            color: Theme.ice
            font.family: Theme.mono
            font.pixelSize: 11
            font.weight: Font.Medium
            Layout.leftMargin: 4
            Layout.rightMargin: 6
        }

        Rectangle { Layout.preferredWidth: 1; Layout.preferredHeight: 18; color: Theme.line2 }

        BarBtn {
            label: qsTr("Copy")
            onClicked: if (view) view.copyMultiSelection(false)
        }
        BarBtn {
            label: Theme.caps(qsTr("HTML"))
            onClicked: if (view && view.session && view.session.copySelectedAs("html")) view.toastRequested(qsTr("Copied as HTML"))
        }
        BarBtn {
            label: qsTr("Duplicate")
            onClicked: if (view && view.session) view.session.duplicateBlocks(view.session.selectedIds())
        }
        BarBtn {
            label: qsTr("Group")
            onClicked: if (view && view.session) view.session.groupBlocks(view.session.selectedIds())
        }
        BarBtn {
            label: qsTr("Delete")
            danger: true
            onClicked: if (view && view.session) view.session.deleteSelected()
        }

        Rectangle { Layout.preferredWidth: 1; Layout.preferredHeight: 18; color: Theme.line2 }

        BarBtn {
            label: "✕"
            onClicked: if (view && view.session) view.session.clearSelection()
        }
    }
}
