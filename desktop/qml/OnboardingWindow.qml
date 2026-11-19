import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import YAOP.Core 1.0
import "."

// First run (spec §21.1): create or restore the vault, show the phrase once, confirm 3 words,
// connect a server (optional). `onb` is the VaultOnboarding context object.
ApplicationWindow {
    id: win
    width: 640
    height: 760
    visible: true
    color: Theme.page
    title: qsTr("YAOP · Welcome")

    // welcome | phrase | confirm | restore | join | server | saved
    property string step: (typeof initialOnboardingStep !== "undefined" && initialOnboardingStep) ? initialOnboardingStep : "welcome"
    property int restoreError: 0
    property bool confirmWrong: false
    property bool commitFailed: false

    FontLoader { source: "../assets/fonts/InterTight-Variable.ttf" }
    FontLoader { source: "../assets/fonts/IBMPlexMono-Regular.ttf" }
    FontLoader { source: "../assets/fonts/IBMPlexMono-Medium.ttf" }
    FontLoader { source: "../assets/fonts/Doto-Variable.ttf" }

    AppBackground { anchors.fill: parent; z: -10; glyphs: true }

    PairingFlow {
        id: pairing
        onGuestJoined: {
            onb.adoptVk(pairing.receivedVk, pairing.receivedEpoch)
            serverUrlField.text = pairing.guestServerUrl
            win.step = "server"
        }
    }

    function heading(t) { return t }

    ScrollView {
        anchors.fill: parent
        anchors.margins: 32
        contentWidth: availableWidth
        clip: true

        ColumnLayout {
            width: parent.width
            spacing: 16

            Text {
                text: Theme.caps(qsTr("YAOP"))
                color: Theme.ice
                font.family: Theme.mono
                font.pixelSize: 11
                font.letterSpacing: Theme.track(2)
            }

            // ---- welcome
            ColumnLayout {
                visible: win.step === "welcome"
                Layout.fillWidth: true
                spacing: 14
                Text {
                    Layout.fillWidth: true
                    text: qsTr("Your notes live encrypted on your devices. The server never reads them.")
                    color: Theme.text
                    font.family: Theme.body
                    font.pixelSize: 18
                    wrapMode: Text.WordWrap
                }
                Button { text: Theme.caps(qsTr("CREATE A NEW VAULT")); primary: true; onClicked: { onb.startCreate(); win.step = "phrase" } }
                Button { text: Theme.caps(qsTr("RESTORE WITH MY PHRASE")); onClicked: { win.restoreError = 0; win.step = "restore" } }
                Button { text: Theme.caps(qsTr("ADD THIS DEVICE FROM ANOTHER ONE")); onClicked: { pairing.cancel(); win.step = "join" } }
            }

            // ---- phrase (shown once)
            ColumnLayout {
                visible: win.step === "phrase"
                Layout.fillWidth: true
                spacing: 14
                Text { text: qsTr("Your recovery phrase"); color: Theme.text; font.family: Theme.body; font.pixelSize: 18 }
                Text {
                    Layout.fillWidth: true
                    text: qsTr("Write these 24 words down and keep them safe. They are shown only once here. " +
                               "Without the phrase and without another device, your data cannot be recovered.")
                    color: Theme.dim
                    font.family: Theme.body
                    font.pixelSize: 12
                    wrapMode: Text.WordWrap
                }
                GridLayout {
                    columns: 3
                    columnSpacing: 18
                    rowSpacing: 6
                    Repeater {
                        model: onb.phrase.length > 0 ? onb.phrase.split(" ") : []
                        delegate: Text {
                            required property string modelData
                            required property int index
                            text: (index + 1) + ". " + modelData
                            color: Theme.text
                            font.family: Theme.mono
                            font.pixelSize: 13
                        }
                    }
                }
                RowLayout {
                    spacing: 10
                    Button { text: Theme.caps(qsTr("I WROTE IT DOWN")); primary: true; onClicked: { win.confirmWrong = false; win.step = "confirm" } }
                    Button { text: Theme.caps(qsTr("BACK")); onClicked: win.step = "welcome" }
                }
            }

            // ---- confirm 3 words
            ColumnLayout {
                visible: win.step === "confirm"
                Layout.fillWidth: true
                spacing: 12
                Text { text: qsTr("Confirm your phrase"); color: Theme.text; font.family: Theme.body; font.pixelSize: 18 }
                Text { text: qsTr("Type the requested words to prove you saved it."); color: Theme.dim; font.family: Theme.body; font.pixelSize: 12 }
                Repeater {
                    id: confirmRep
                    model: onb.confirmIndices
                    delegate: OnbField {
                        required property int modelData
                        objectName: "confirmField"
                        Layout.fillWidth: true
                        label: Theme.caps(qsTr("WORD #%1")).arg(modelData + 1)
                    }
                }
                Text {
                    visible: win.confirmWrong
                    text: qsTr("Those words do not match. Check your notes and try again.")
                    color: Theme.danger
                    font.family: Theme.body
                    font.pixelSize: 12
                }
                RowLayout {
                    spacing: 10
                    Button {
                        text: Theme.caps(qsTr("CONTINUE"))
                        primary: true
                        onClicked: {
                            var answers = []
                            for (var i = 0; i < confirmRep.count; ++i)
                                answers.push(confirmRep.itemAt(i).text)
                            if (onb.checkWords(answers)) { win.confirmWrong = false; win.step = "server" }
                            else win.confirmWrong = true
                        }
                    }
                    Button { text: Theme.caps(qsTr("SHOW THE PHRASE AGAIN")); onClicked: win.step = "phrase" }
                }
            }

            // ---- restore
            ColumnLayout {
                visible: win.step === "restore"
                Layout.fillWidth: true
                spacing: 12
                Text { text: qsTr("Restore your vault"); color: Theme.text; font.family: Theme.body; font.pixelSize: 18 }
                Text { text: qsTr("Type your 24 words, separated by spaces."); color: Theme.dim; font.family: Theme.body; font.pixelSize: 12 }
                OnbField { id: restoreField; Layout.fillWidth: true; label: Theme.caps(qsTr("RECOVERY PHRASE")); multiline: true }
                Text {
                    visible: win.restoreError !== 0
                    Layout.fillWidth: true
                    wrapMode: Text.WordWrap
                    color: Theme.danger
                    font.family: Theme.body
                    font.pixelSize: 12
                    text: win.restoreError === VaultOnboarding.WordCount ? qsTr("The phrase must have exactly 24 words.")
                        : win.restoreError === VaultOnboarding.UnknownWord ? qsTr("One of the words is not in the list. Check the spelling.")
                        : qsTr("The phrase is not valid (checksum mismatch). Check the order and the words.")
                }
                RowLayout {
                    spacing: 10
                    Button {
                        text: Theme.caps(qsTr("CONTINUE"))
                        primary: true
                        onClicked: {
                            win.restoreError = onb.restore(restoreField.text)
                            if (win.restoreError === VaultOnboarding.RestoreOk) win.step = "server"
                        }
                    }
                    Button { text: Theme.caps(qsTr("BACK")); onClicked: win.step = "welcome" }
                }
            }

            // ---- join from another device
            ColumnLayout {
                visible: win.step === "join"
                Layout.fillWidth: true
                spacing: 12
                Text { text: qsTr("Add this device"); color: Theme.text; font.family: Theme.body; font.pixelSize: 18 }
                ColumnLayout {
                    visible: pairing.state === "idle" || pairing.state === "failed"
                    Layout.fillWidth: true
                    spacing: 12
                    Text {
                        Layout.fillWidth: true
                        text: qsTr("On an enrolled device open Settings, Add device, then paste its code here.")
                        color: Theme.dim; font.family: Theme.body; font.pixelSize: 12; wrapMode: Text.WordWrap
                    }
                    OnbField { id: pasteField; Layout.fillWidth: true; label: Theme.caps(qsTr("PAIRING CODE")); multiline: true }
                    Text { visible: pairing.state === "failed"; text: pairing.error; color: Theme.danger; font.family: Theme.body; font.pixelSize: 12 }
                    Button { text: Theme.caps(qsTr("JOIN")); primary: true; onClicked: pairing.guestStart(pasteField.text) }
                }
                ColumnLayout {
                    visible: pairing.state === "compare" || pairing.state === "opening"
                    Layout.fillWidth: true
                    spacing: 12
                    Text {
                        Layout.fillWidth: true
                        text: qsTr("Check that the other device shows this code, then approve there:")
                        color: Theme.dim; font.family: Theme.body; font.pixelSize: 12; wrapMode: Text.WordWrap
                    }
                    Text { text: pairing.code; color: Theme.ice; font.family: Theme.code; font.pixelSize: 40 }
                    Text { text: qsTr("Waiting for approval…"); color: Theme.faint; font.family: Theme.body; font.pixelSize: 12 }
                }
                Button { text: Theme.caps(qsTr("BACK")); onClicked: { pairing.cancel(); win.step = "welcome" } }
            }

            // ---- server (optional)
            ColumnLayout {
                visible: win.step === "server"
                Layout.fillWidth: true
                spacing: 12
                Text { text: qsTr("Connect a server"); color: Theme.text; font.family: Theme.body; font.pixelSize: 18 }
                Text {
                    Layout.fillWidth: true
                    text: qsTr("Optional. You can use YAOP without a server and connect one later.")
                    color: Theme.dim; font.family: Theme.body; font.pixelSize: 12; wrapMode: Text.WordWrap
                }
                OnbField { id: serverUrlField; Layout.fillWidth: true; label: Theme.caps(qsTr("SERVER URL")) }
                OnbField { id: tokenField; Layout.fillWidth: true; label: Theme.caps(qsTr("BEARER TOKEN")) }
                Text {
                    visible: win.commitFailed
                    Layout.fillWidth: true
                    wrapMode: Text.WordWrap
                    color: Theme.danger
                    font.family: Theme.body
                    font.pixelSize: 12
                    text: qsTr("No secure storage is available to keep the vault key (no system keychain). " +
                               "Start YAOP with YAOP_VAULT_PASSPHRASE set, or install a keychain service.")
                }
                RowLayout {
                    spacing: 10
                    Button {
                        text: Theme.caps(qsTr("CONNECT AND START"))
                        primary: true
                        onClicked: win.commitFailed = !onb.commit(serverUrlField.text, tokenField.text)
                    }
                    Button { text: Theme.caps(qsTr("SKIP FOR NOW")); onClicked: win.commitFailed = !onb.commit("", "") }
                }
            }
        }
    }
}
