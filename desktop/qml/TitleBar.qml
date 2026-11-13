import QtQuick
import QtQuick.Layouts
import QtQuick.Controls
import "."

// Window title bar (spec §8.2): logo, search centred, sync state, primary action.
Item {
    id: bar

    signal searchRequested()
    signal captureRequested()
    signal syncRequested()
    signal settingsRequested()

    implicitHeight: Theme.topbarH

    Rectangle {
        anchors.bottom: parent.bottom
        width: parent.width
        height: 1
        color: Theme.line
    }

    RowLayout {
        anchors.fill: parent
        anchors.leftMargin: 18
        anchors.rightMargin: 14
        spacing: 14

        // brand
        RowLayout {
            Layout.preferredWidth: Theme.compact ? 28 : Theme.sidebarW - 18
            spacing: 10

            Image {
                Layout.preferredWidth: 30
                Layout.preferredHeight: 30
                source: "qrc:/assets/yaop.svg"
                width: 30; height: 30
                fillMode: Image.PreserveAspectFit
                sourceSize: Qt.size(60, 60)
                smooth: true
            }
            Text {
                visible: !Theme.compact
                text: "YAOP"
                color: Theme.text
                font.family: Theme.display
                font.pixelSize: 18
                font.weight: Font.DemiBold
                font.letterSpacing: -0.2
            }
        }

        Item { Layout.fillWidth: true }

        // sync state (click forces a sync; fixed text width prevents layout jitter)
        RowLayout {
            spacing: 8
            Rectangle {
                Layout.preferredWidth: 8
                Layout.preferredHeight: 8
                radius: 4
                color: !ws ? Theme.late : !ws.connected ? Theme.late
                     : (ws.syncing || ws.pendingCount > 0) ? Theme.violet : Theme.ok
                SequentialAnimation on scale {
                    running: ws && ws.syncing
                    loops: Animation.Infinite
                    NumberAnimation { from: 1; to: 1.6; duration: 420 }
                    NumberAnimation { from: 1.6; to: 1; duration: 420 }
                }
            }
            Text {
                visible: !Theme.compact
                Layout.preferredWidth: 112
                horizontalAlignment: Text.AlignRight
                elide: Text.ElideRight
                text: !ws ? qsTr("OFFLINE") : !ws.connected ? qsTr("OFFLINE")
                      : ws.syncing ? qsTr("SYNCING")
                      : ws.pendingCount > 0 ? qsTr("%1 QUEUED").arg(ws.pendingCount)
                      : qsTr("SYNCED · E2EE")
                color: Theme.dim
                font.family: Theme.mono
                font.pixelSize: 11
                font.letterSpacing: 1
            }
            HoverHandler { cursorShape: Qt.PointingHandCursor }
            TapHandler { onTapped: bar.syncRequested() }
        }

        // Image privacy toggle: click toggles Show / discreet mode, right-click picks a mode.
        IconButton {
            id: privacyButton
            icon: Privacy.active ? "eye-off" : "eye"
            active: Privacy.active
            onClicked: Privacy.toggle()
            TapHandler {
                acceptedButtons: Qt.RightButton
                onTapped: privacyMenu.popup()
            }
            Menu {
                id: privacyMenu
                MenuItem { text: qsTr("Show"); checkable: true; checked: Privacy.mode === 0; onTriggered: Privacy.setMode(0) }
                MenuItem { text: qsTr("Blur"); checkable: true; checked: Privacy.mode === 1; onTriggered: Privacy.setMode(1) }
                MenuItem { text: qsTr("Hide"); checkable: true; checked: Privacy.mode === 2; onTriggered: Privacy.setMode(2) }
                MenuSeparator {}
                MenuItem {
                    text: qsTr("Also in open notes")
                    checkable: true
                    checked: Privacy.store.scope === 0
                    onTriggered: Privacy.store.scope = Privacy.store.scope === 0 ? 1 : 0
                }
            }
        }

        IconButton {
            icon: "adjustments-horizontal"
            onClicked: bar.settingsRequested()
        }

        Button {
            text: Theme.compact ? "+" : qsTr("CAPTURE")
            primary: true
            onClicked: bar.captureRequested()
        }
    }

    // search pill anchored dead-center so sync status changes never shift it
    Rectangle {
        id: search
        anchors.centerIn: parent
        width: Math.min(440, Math.max(160, bar.width - 560))
        height: 38
        radius: 19
        color: searchHover.hovered ? Theme.panel2 : Theme.panel
        border.width: 1
        border.color: Theme.line
        Behavior on color { ColorAnimation { duration: Theme.fast } }

        RowLayout {
            anchors.fill: parent
            anchors.leftMargin: 14
            anchors.rightMargin: 14
            spacing: 10
            TintedIcon { name: "search"; size: 16; color: Theme.dim }
            Text {
                Layout.fillWidth: true
                text: qsTr("Search or open…")
                color: Theme.muted
                font.family: Theme.body
                font.pixelSize: 14
                elide: Text.ElideRight
            }
            Text {
                visible: !Theme.compact
                text: "CTRL K"
                color: Theme.muted
                font.family: Theme.mono
                font.pixelSize: 11
                font.letterSpacing: 1
            }
        }
        HoverHandler { id: searchHover; cursorShape: Qt.PointingHandCursor }
        TapHandler { onTapped: bar.searchRequested() }
    }
}
