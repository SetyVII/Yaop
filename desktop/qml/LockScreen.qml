import QtQuick
import QtQuick.Layouts
import "."

// Full-window lock (spec §15.5): nothing of the vault is drawn while locked.
Item {
    id: root
    anchors.fill: parent
    visible: appLock.locked
    z: 1000
    focus: visible

    onVisibleChanged: if (visible) { pinInput.text = ""; error = false; pinInput.forceActiveFocus() }
    property bool error: false

    Timer { id: tick; interval: 1000; running: root.visible; repeat: true; onTriggered: root.retry = appLock.retryInSeconds }
    property int retry: 0

    AppBackground { anchors.fill: parent; glyphs: true }
    // swallow every pointer event: the app underneath is not reachable
    MouseArea { anchors.fill: parent; hoverEnabled: true; onPressed: pinInput.forceActiveFocus() }

    ColumnLayout {
        anchors.centerIn: parent
        width: Math.min(360, parent.width - 48)
        spacing: 18

        Rectangle {
            Layout.alignment: Qt.AlignHCenter
            Layout.preferredWidth: 56
            Layout.preferredHeight: 56
            radius: 28
            color: Theme.accent
            Text {
                anchors.centerIn: parent
                text: "F"
                color: Theme.accentInk
                font.family: Theme.pixel
                font.weight: Font.ExtraBold
                font.pixelSize: 36
            }
        }

        Row {
            Layout.alignment: Qt.AlignHCenter
            spacing: 10
            Text { text: qsTr("YAOP is"); color: Theme.text; font.family: Theme.display; font.pixelSize: 30; font.weight: Font.Medium }
            Text { text: qsTr("locked"); color: Theme.ice; font.family: Theme.pixel; font.weight: Font.ExtraBold; font.pixelSize: 32 }
        }

        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: 48
            radius: 24
            color: Theme.panel
            border.width: 1
            border.color: root.error ? Theme.late : (pinInput.activeFocus ? Theme.ice : "transparent")

            TextInput {
                id: pinInput
                anchors.fill: parent
                anchors.leftMargin: 20
                anchors.rightMargin: 20
                verticalAlignment: TextInput.AlignVCenter
                horizontalAlignment: TextInput.AlignHCenter
                echoMode: TextInput.Password
                passwordCharacter: "•"
                color: Theme.text
                font.family: Theme.mono
                font.pixelSize: 18
                font.letterSpacing: Theme.track(4)
                enabled: root.retry === 0
                onTextEdited: root.error = false
                onAccepted: {
                    if (appLock.unlock(text)) {
                        text = ""
                    } else {
                        root.error = true
                        root.retry = appLock.retryInSeconds
                        text = ""
                    }
                }
                Text {
                    anchors.centerIn: parent
                    visible: pinInput.length === 0
                    text: Theme.caps(qsTr("PIN"))
                    color: Theme.muted
                    font.family: Theme.mono
                    font.pixelSize: 12
                    font.letterSpacing: Theme.track(2)
                }
            }
        }

        Text {
            Layout.alignment: Qt.AlignHCenter
            visible: root.error || root.retry > 0
            text: root.retry > 0 ? qsTr("Too many attempts. Try again in %1 s.").arg(root.retry)
                                 : qsTr("Wrong PIN.")
            color: Theme.late
            font.family: Theme.body
            font.pixelSize: 13
        }

        Button {
            Layout.alignment: Qt.AlignHCenter
            text: Theme.caps(qsTr("UNLOCK"))
            primary: true
            enabled: pinInput.length > 0 && root.retry === 0
            onClicked: pinInput.accepted()
        }
    }
}
