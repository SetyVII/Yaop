import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../"

// Compact preview card (mobile pattern F): taps on a mention/tag/date anchor
// reveal this tip with Open / Remove actions.

Popup {
    id: mt

    property var workspace
    property var tip: null
    property string resolvedObjectId: ""

    signal openRequested(string objectId)
    signal removeRequested(var tip)

    function showTip(tipData) {
        tip = tipData
        resolvedObjectId = tipData.type === "mention" ? tipData.objectId : ""
        open()
    }

    function hideTip() {
        close()
    }

    width: 300
    height: contentCol.implicitHeight + 24
    padding: 12
    modal: false
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside

    background: Rectangle {
        radius: 18
        color: Theme.sheet
        border.color: Theme.line2
        border.width: 1
    }

    readonly property var summary: workspace && resolvedObjectId.length > 0
            ? workspace.objectSummary(resolvedObjectId) : ({})

    contentItem: ColumnLayout {
        id: contentCol
        spacing: 8

        // mention preview
        ColumnLayout {
            visible: mt.tip && mt.tip.type === "mention"
            spacing: 4
            Layout.fillWidth: true

            RowLayout {
                spacing: 8
                Rectangle {
                    Layout.preferredWidth: 3
                    Layout.preferredHeight: 16
                    color: Theme.typeColor(mt.summary.typeId !== undefined ? mt.summary.typeId : "")
                }
                Text {
                    text: mt.summary.title !== undefined ? mt.summary.title : qsTr("Unknown object")
                    color: Theme.text
                    font.family: Theme.body
                    font.pixelSize: 14
                    font.weight: Font.Medium
                    elide: Text.ElideRight
                    Layout.fillWidth: true
                }
            }
            Text {
                visible: mt.summary.snippet !== undefined && String(mt.summary.snippet).length > 0
                text: mt.summary.snippet !== undefined ? mt.summary.snippet : ""
                color: Theme.muted
                font.family: Theme.body
                font.pixelSize: 12
                wrapMode: Text.Wrap
                maximumLineCount: 3
                elide: Text.ElideRight
                Layout.fillWidth: true
            }
            Text {
                text: Theme.typeLabel(mt.summary.typeId !== undefined ? mt.summary.typeId : "")
                color: Theme.faint
                font.family: Theme.mono
                font.pixelSize: 11
                font.letterSpacing: 1.2
            }
        }

        RowLayout {
            visible: mt.tip && mt.tip.type === "tag"
            spacing: 8
            Text {
                text: qsTr("Tag")
                color: Theme.ice
                font.family: Theme.mono
                font.pixelSize: 11
            }
            Text {
                text: (mt.tip && mt.tip.type === "tag" && mt.tip.url) ? String(mt.tip.url).replace("yaop-tag://", "") : ""
                color: Theme.dim
                font.family: Theme.mono
                font.pixelSize: 11
            }
        }

        RowLayout {
            visible: mt.tip && mt.tip.type === "date"
            spacing: 8
            Text {
                text: qsTr("Date")
                color: Theme.journal
                font.family: Theme.mono
                font.pixelSize: 11
            }
            Text {
                text: (mt.tip && mt.tip.type === "date" && mt.tip.url) ? String(mt.tip.url).replace("yaop-date://", "") : ""
                color: Theme.dim
                font.family: Theme.mono
                font.pixelSize: 11
            }
        }

        // actions
        RowLayout {
            spacing: 8
            Layout.alignment: Qt.AlignRight

            Button {
                text: qsTr("Open")
                small: true
                visible: mt.tip && (mt.tip.type === "mention" || mt.tip.type === "tag" || mt.tip.type === "date")
                onClicked: {
                    if (mt.tip.type === "mention" && resolvedObjectId.length > 0) {
                        openRequested(resolvedObjectId)
                    } else if (mt.tip.type === "tag") {
                        const tid = (mt.tip.tagName && mt.workspace) ? mt.workspace.ensureTagObject(mt.tip.tagName) : (mt.tip.tagId || "")
                        if (tid.length > 0) openRequested(tid)
                    } else if (mt.tip.type === "date" && mt.workspace) {
                        const iso = mt.tip.iso || String(mt.tip.url || "").replace("yaop-date://", "").split("T")[0]
                        const jid = mt.workspace.journalFor(iso)
                        if (jid.length > 0) openRequested(jid)
                    }
                    mt.hideTip()
                }
            }
            Button {
                text: qsTr("Remove")
                small: true
                onClicked: {
                    removeRequested(mt.tip)
                }
            }
        }
    }
}
