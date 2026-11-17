import QtQuick
import QtQuick.Dialogs
import QtQuick.Controls
import QtQuick.Layouts
import YAOP.Core 1.0
import "."

// Settings: server connection, sync status, cache management.

Popup {
    id: settingsSheet

    x: (parent.width - width) / 2
    y: Math.max(16, Math.min(80, parent.height * 0.08))
    width: Math.min(520, parent.width - 80)
    height: Math.min(contentCol.implicitHeight + 28, parent.height - 2 * y)
    padding: 14
    modal: true
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside

    // dim: the page behind goes ink-blue, not neutral grey
    Overlay.modal: Rectangle { color: Qt.rgba(0.02, 0.04, 0.15, 0.62) }

    background: Rectangle {
        radius: Theme.radiusLg
        color: Theme.sheet
        border.color: Theme.line2
        border.width: 1
    }

    // Scrolls when the window is shorter than the sheet (vault + devices grew it past 600 px).
    Flickable {
        id: sheetFlick
        anchors.fill: parent
        contentWidth: width
        contentHeight: contentCol.implicitHeight
        clip: true
        boundsBehavior: Flickable.StopAtBounds
        ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }

    ColumnLayout {
        id: contentCol
        width: sheetFlick.width
        spacing: 14

        Text {
            text: qsTr("APPEARANCE")
            color: Theme.ice
            font.family: Theme.mono
            font.pixelSize: 11
            font.letterSpacing: 1.6
        }
        // This device only; both looks share every screen.
        RowLayout {
            Layout.fillWidth: true
            spacing: 8
            Repeater {
                model: [
                    { key: "slate", label: qsTr("Slate"), hint: qsTr("Neutral gray, page panel") },
                    { key: "sky", label: qsTr("Sky"), hint: qsTr("Deep blue, mono labels") }
                ]
                delegate: Rectangle {
                    required property var modelData
                    readonly property bool on: (modelData.key === "slate") === Theme.slate
                    Layout.fillWidth: true
                    implicitHeight: 54
                    radius: Theme.radius
                    color: on ? Theme.panel2 : apHover.hovered ? Theme.panel : "transparent"
                    border.width: 1
                    border.color: on ? Theme.ice : Theme.line
                    Column {
                        anchors.verticalCenter: parent.verticalCenter
                        x: 12
                        spacing: 2
                        Text { text: modelData.label; color: Theme.text; font.family: Theme.body; font.pixelSize: 14; font.weight: Font.Medium }
                        Text { text: modelData.hint; color: Theme.muted; font.family: Theme.body; font.pixelSize: 12 }
                    }
                    HoverHandler { id: apHover; cursorShape: Qt.PointingHandCursor }
                    TapHandler { onTapped: Theme.setScheme(modelData.key) }
                }
            }
        }

        Text {
            text: qsTr("SERVER")
            color: Theme.ice
            font.family: Theme.mono
            font.pixelSize: 11
            font.letterSpacing: 1.6
        }

        // sync status row
        RowLayout {
            Layout.fillWidth: true
            spacing: 10

            Rectangle {
                Layout.preferredWidth: 8
                Layout.preferredHeight: 8
                color: ws ? (ws.connected ? Theme.accent : Theme.danger) : Theme.danger
            }
            Text {
                Layout.fillWidth: true
                text: ws ? (ws.connected
                            ? (ws.live ? qsTr("Connected · live stream active")
                                       : qsTr("Connected"))
                            : qsTr("Offline"))
                         : ""
                color: Theme.dim
                font.family: Theme.body
                font.pixelSize: 12
            }
            Text {
                text: ws ? qsTr("pending %1 · cursor %2").arg(ws.pendingCount).arg("") : ""
                color: Theme.faint
                font.family: Theme.mono
                font.pixelSize: 11
            }
        }

        // server url
        ColumnLayout {
            spacing: 6
            Layout.fillWidth: true

            Text {
                text: qsTr("SERVER URL")
                color: Theme.muted
                font.family: Theme.mono
                font.pixelSize: 11
                font.letterSpacing: 1.2
            }
            Rectangle {
                Layout.fillWidth: true
                Layout.preferredHeight: 36
                color: Theme.panel
                border.color: urlInput.activeFocus ? Theme.line3 : Theme.line

                TextInput {
                    id: urlInput
                    anchors.fill: parent
                    anchors.leftMargin: 10
                    anchors.rightMargin: 10
                    verticalAlignment: TextInput.AlignVCenter
                    text: ws ? ws.serverUrl : ""
                    color: Theme.text
                    font.family: Theme.mono
                    font.pixelSize: 12
                    clip: true
                }
            }
        }

        // token
        ColumnLayout {
            spacing: 6
            Layout.fillWidth: true

            Text {
                text: qsTr("BEARER TOKEN")
                color: Theme.muted
                font.family: Theme.mono
                font.pixelSize: 11
                font.letterSpacing: 1.2
            }
            Rectangle {
                Layout.fillWidth: true
                Layout.preferredHeight: 36
                color: Theme.panel
                border.color: tokenInput.activeFocus ? Theme.line3 : Theme.line

                TextInput {
                    id: tokenInput
                    anchors.fill: parent
                    anchors.leftMargin: 10
                    anchors.rightMargin: 10
                    verticalAlignment: TextInput.AlignVCenter
                    text: ws ? ws.bearerToken : ""
                    color: Theme.dim
                    font.family: Theme.mono
                    font.pixelSize: 11
                    echoMode: TextInput.Password
                    clip: true
                }
            }
        }


        // ---- vault: recovery phrase, add device (QR), devices (spec §15.2–15.3)
        PairingFlow {
            id: pairing
            serverUrl: ws ? ws.serverUrl : ""
            workspace: ws
        }

        Text {
            text: qsTr("VAULT")
            color: Theme.ice
            font.family: Theme.mono
            font.pixelSize: 11
            font.letterSpacing: 1.6
        }

        RowLayout {
            Layout.fillWidth: true
            spacing: 10
            Button {
                text: phraseText.visible ? qsTr("HIDE PHRASE") : qsTr("SHOW RECOVERY PHRASE")
                onClicked: { phraseText.visible = !phraseText.visible; phraseText.text = phraseText.visible && ws ? ws.recoveryPhrase() : "" }
            }
            Button {
                text: qsTr("ADD A DEVICE")
                onClicked: pairing.hostStart()
            }
            Button {
                text: qsTr("DEVICES")
                onClicked: { if (ws) ws.refreshDevices() }
            }
        }

        Text {
            id: phraseText
            visible: false
            Layout.fillWidth: true
            color: Theme.text
            font.family: Theme.mono
            font.pixelSize: 12
            wrapMode: Text.WordWrap
        }

        // ---- backup (.yaop, spec §15.6)
        Text {
            text: qsTr("BACKUP")
            color: Theme.ice
            font.family: Theme.mono
            font.pixelSize: 11
            font.letterSpacing: 1.6
        }
        Text {
            Layout.fillWidth: true
            text: qsTr("One encrypted file with the whole vault and its media. It opens with your 24 words, no server needed.")
            color: Theme.dim
            wrapMode: Text.WordWrap
            font.pixelSize: 12
        }
        RowLayout {
            Layout.fillWidth: true
            spacing: 10
            Button { text: qsTr("EXPORT BACKUP"); onClicked: exportDialog.open() }
            Button { text: qsTr("RESTORE FROM BACKUP"); onClicked: restoreDialog.open() }
        }
        Text {
            id: backupStatus
            visible: text.length > 0
            Layout.fillWidth: true
            color: text.indexOf("error") === 0 ? Theme.danger : Theme.text
            font.family: Theme.mono
            font.pixelSize: 12
            wrapMode: Text.WordWrap
        }
        FileDialog {
            id: exportDialog
            fileMode: FileDialog.SaveFile
            nameFilters: [qsTr("YAOP backup (*.yaop)")]
            defaultSuffix: "yaop"
            onAccepted: if (ws) backupStatus.text = ws.exportBackup(selectedFile.toString().replace(/^file:\/\//, ""))
        }
        FileDialog {
            id: restoreDialog
            fileMode: FileDialog.OpenFile
            nameFilters: [qsTr("YAOP or Facultas backup (*.yaop *.facultas)"), qsTr("All files (*)")]
            onAccepted: if (ws) backupStatus.text = ws.restoreBackup(selectedFile.toString().replace(/^file:\/\//, ""))
        }

        ColumnLayout {
            visible: pairing.state !== "idle"
            Layout.fillWidth: true
            spacing: 8
            QrCode {
                visible: pairing.state === "waiting"
                modules: pairing.qrModules
                cell: 5
                Layout.alignment: Qt.AlignHCenter
            }
            TextEdit {
                visible: pairing.state === "waiting"
                Layout.fillWidth: true
                readOnly: true
                selectByMouse: true
                text: pairing.qrPayload
                color: Theme.faint
                font.family: Theme.mono
                font.pixelSize: 11
                wrapMode: TextEdit.WrapAnywhere
            }
            Text {
                visible: pairing.state === "waiting"
                text: qsTr("Scan it from the new device, or copy this code into it.")
                color: Theme.dim; font.family: Theme.body; font.pixelSize: 11
            }
            Text {
                visible: pairing.state === "compare"
                text: qsTr("Does the new device show this code?")
                color: Theme.dim; font.family: Theme.body; font.pixelSize: 11
            }
            Text {
                visible: pairing.state === "compare"
                text: pairing.code
                color: Theme.ice; font.family: Theme.mono; font.pixelSize: 32
            }
            Text { visible: pairing.state === "done"; text: qsTr("Device added."); color: Theme.ice; font.family: Theme.body; font.pixelSize: 12 }
            Text { visible: pairing.state === "failed"; text: pairing.error; color: Theme.danger; font.family: Theme.body; font.pixelSize: 12 }
            RowLayout {
                spacing: 10
                Button { visible: pairing.state === "compare"; text: qsTr("THEY MATCH"); primary: true; onClicked: pairing.hostApprove() }
                Button { text: qsTr("CANCEL"); small: true; onClicked: pairing.cancel() }
            }
        }

        Text {
            visible: ws && ws.devicesNote.length > 0
            text: ws ? ws.devicesNote : ""
            color: Theme.danger; font.family: Theme.body; font.pixelSize: 11
        }
        Repeater {
            model: ws ? ws.devices : []
            delegate: RowLayout {
                required property var modelData
                Layout.fillWidth: true
                spacing: 8
                Text {
                    Layout.fillWidth: true
                    text: modelData.id.substring(0, 14) + (modelData.mine ? "  ·  " + qsTr("this device") : (modelData.revoked ? "  ·  " + qsTr("revoked") : ""))
                    color: Theme.dim; font.family: Theme.mono; font.pixelSize: 11
                }
                Button {
                    visible: !modelData.mine && !modelData.revoked
                    small: true
                    text: qsTr("REVOKE")
                    onClicked: ws.revokeDevice(modelData.id)
                }
            }
        }


        // ---- local lock (spec §15.5): PIN at start and after N idle minutes
        Text {
            text: qsTr("LOCK")
            color: Theme.ice
            font.family: Theme.mono
            font.pixelSize: 11
            font.letterSpacing: 1.6
        }
        RowLayout {
            Layout.fillWidth: true
            spacing: 10
            OnbField { id: pinCurrent; visible: appLock.hasPin; Layout.fillWidth: true; label: qsTr("CURRENT PIN") }
            OnbField { id: pinNew; Layout.fillWidth: true; label: appLock.hasPin ? qsTr("NEW PIN (4+)") : qsTr("SET A PIN (4+)") }
        }
        RowLayout {
            Layout.fillWidth: true
            spacing: 10
            Button {
                text: appLock.hasPin ? qsTr("CHANGE PIN") : qsTr("ENABLE LOCK")
                onClicked: pinMsg.text = appLock.setPin(pinNew.text, pinCurrent.text)
                                         ? qsTr("PIN saved.") : qsTr("Could not save: check the current PIN and use 4+ characters.")
            }
            Button {
                visible: appLock.hasPin
                text: qsTr("REMOVE")
                onClicked: pinMsg.text = appLock.clearPin(pinCurrent.text) ? qsTr("Lock removed.") : qsTr("Wrong current PIN.")
            }
            Button {
                visible: appLock.hasPin
                text: qsTr("LOCK NOW")
                onClicked: { settingsSheet.close(); appLock.lockNow() }
            }
        }
        RowLayout {
            visible: appLock.hasPin
            Layout.fillWidth: true
            spacing: 8
            Text { text: qsTr("AUTO-LOCK"); color: Theme.dim; font.family: Theme.mono; font.pixelSize: 11; font.letterSpacing: 1.2 }
            Repeater {
                model: [ { m: 1, l: "1 MIN" }, { m: 5, l: "5 MIN" }, { m: 15, l: "15 MIN" }, { m: 30, l: "30 MIN" }, { m: 0, l: qsTr("START ONLY") } ]
                delegate: Chip {
                    required property var modelData
                    text: modelData.l
                    active: appLock.timeoutMinutes === modelData.m
                    onClicked: appLock.timeoutMinutes = modelData.m
                }
            }
        }
        Text { id: pinMsg; visible: text.length > 0; Layout.fillWidth: true; wrapMode: Text.WordWrap; color: Theme.dim; font.family: Theme.body; font.pixelSize: 12 }

        RowLayout {
            Layout.fillWidth: true
            spacing: 10

            Button {
                text: qsTr("SAVE & RESYNC")
                onClicked: {
                    if (ws) {
                        if (urlInput.text.trim().length > 0 && urlInput.text !== ws.serverUrl)
                            ws.serverUrl = urlInput.text.trim()
                        if (tokenInput.text.trim().length > 0 && tokenInput.text !== ws.bearerToken)
                            ws.bearerToken = tokenInput.text.trim()
                        ws.syncNow()
                    }
                    settingsSheet.close()
                }
            }
            Button {
                text: qsTr("FULL RESYNC")
                onClicked: {
                    if (ws)
                        ws.triggerFullSync()
                    settingsSheet.close()
                }
            }
            Button {
                text: qsTr("PULLCHECK")
                onClicked: {
                    if (ws)
                        ws.debugPullCheck()
                }
            }
            Item { Layout.fillWidth: true }
            Button {
                text: qsTr("CLOSE")
                small: true
                onClicked: settingsSheet.close()
            }
        }

        Text {
            Layout.fillWidth: true
            text: qsTr("The desktop keeps an offline queue in a local SQLite cache. " +
                       "Edits push automatically and mirror to every device with live sync.")
            color: Theme.faint
            font.family: Theme.body
            font.pixelSize: 11
            wrapMode: Text.WrapAnywhere
        }
    }
    }
}
