import QtQuick
import QtQuick.Layouts
import "../"

// Above the blocks of an object: on a template, a note saying so (with "Stop being a template");
// on an empty object whose type has templates, "Start from" chips that fill it (product §4.3).
Item {
    id: strip
    property var session: null
    property var appWindow: null
    readonly property int rev: typeof ws !== "undefined" && ws ? ws.listRevision : 0
    readonly property string oid: session ? session.objectId : ""
    readonly property bool isTemplate: { rev; return !!oid && ws.objectSummary(oid).isTemplate === true }
    readonly property var templates: { rev; return session && !isTemplate ? ws.templatesFor(session.typeId) : [] }
    readonly property bool empty: { rev; return !!session && session.blockCount <= 1 && (ws.objectStats(oid).words || 0) === 0 }
    readonly property bool showPicker: templates.length > 0 && empty

    visible: isTemplate || showPicker
    implicitHeight: visible ? row.implicitHeight + 28 : 0

    Rectangle {
        anchors.fill: parent
        anchors.bottomMargin: 8
        color: strip.isTemplate ? Theme.accentDim : "transparent"
        border.width: 1
        border.color: strip.isTemplate ? Theme.accent : Theme.line2
        radius: Theme.radiusSm
    }

    RowLayout {
        id: row
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.margins: 10
        spacing: 8

        Text {
            text: strip.isTemplate
                  ? qsTr("Template of %1 · new ones can start from it").arg(session ? Theme.typeName(session.typeId) : "")
                  : qsTr("Start from")
            color: strip.isTemplate ? Theme.text : Theme.muted
            font.family: Theme.body; font.pixelSize: 13
        }
        Flow {
            visible: !strip.isTemplate
            Layout.fillWidth: true
            spacing: 6
            Repeater {
                model: strip.templates
                delegate: Button {
                    required property var modelData
                    small: true
                    text: modelData.title
                    onClicked: {
                        if (ws.applyTemplate(strip.oid, modelData.id) && strip.appWindow)
                            strip.appWindow.showToast(qsTr("Started from “%1”").arg(modelData.title))
                    }
                }
            }
        }
        Item { visible: strip.isTemplate; Layout.fillWidth: true }
        Button {
            visible: strip.isTemplate
            small: true
            text: qsTr("Stop being a template")
            onClicked: ws.setIsTemplate(strip.oid, false)
        }
    }
}
