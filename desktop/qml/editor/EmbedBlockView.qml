import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../"

// Object embed (docs/design/mockup/Mentions.dc.html + BlockTypes.dc.html):
// Four display modes: chip ("inline"), small card ("compact"), wide card ("card"), full read-only ("full").
// Hover shows a compact mode switcher to change viewMode live.
Item {
    id: eb

    property var view
    property int row
    property string targetObjectId
    property string viewMode: "card"

    readonly property var summary: (typeof ws !== "undefined" && targetObjectId.length > 0)
                            ? ws.objectSummary(targetObjectId) : ({})
    readonly property string embeddedType: summary.typeId !== undefined ? summary.typeId : ""
    readonly property string displayTitle: summary.title !== undefined && summary.title.length > 0 ? summary.title : targetObjectId
    readonly property string snippet: summary.snippet !== undefined ? summary.snippet : ""

    signal openRequested(string objectId)

    // a saved query (set) shows its live results instead of a card (spec/query.md "Query in a page")
    readonly property bool isQuery: embeddedType === "set"
    implicitHeight: isQuery ? queryLoader.height + 4
                  : viewMode === "full" ? 132 : viewMode === "card" ? 92 : viewMode === "compact" ? 56 : 32

    Loader {
        id: queryLoader
        active: eb.isQuery
        visible: active
        y: 2
        width: eb.width
        height: item ? item.implicitHeight : 0
        sourceComponent: QueryEmbed {
            view: eb.view
            setId: eb.targetObjectId
            onOpenRequested: (id) => { if (eb.view) eb.view.openObjectRequested(id) }
        }
    }

    function activate(viaSpace) { if (!viaSpace) emitOpen(); return false }
    function emitOpen() {
        openRequested(targetObjectId)
        if (view) view.openObjectRequested(targetObjectId)
    }

    HoverHandler { id: outerHover }

    // 1. CHIP ("inline")
    Rectangle {
        visible: !eb.isQuery && eb.viewMode === "inline"
        anchors.verticalCenter: parent.verticalCenter
        implicitWidth: chipRow.implicitWidth + 18
        height: 26
        radius: Theme.radiusSm
        color: "transparent"
        border.width: 1
        border.color: outerHover.hovered ? Theme.line3 : Theme.line2
        Row {
            id: chipRow; anchors.centerIn: parent; spacing: 7
            TypeBadge { anchors.verticalCenter: parent.verticalCenter; typeId: eb.embeddedType; size: 14 }
            Text { anchors.verticalCenter: parent.verticalCenter; text: eb.displayTitle; color: Theme.bodyText; font.family: Theme.body; font.pixelSize: 14 }
        }
        TapHandler { onTapped: eb.emitOpen() }
    }

    // 2. SMALL / WIDE / FULL CARD
    Rectangle {
        visible: !eb.isQuery && eb.viewMode !== "inline"
        anchors.fill: parent
        anchors.margins: 2
        radius: Theme.radius
        color: outerHover.hovered ? Theme.panel2 : Theme.page
        border.width: 1
        border.color: outerHover.hovered ? Theme.line3 : Theme.line2

        TapHandler { onTapped: eb.emitOpen() }

        RowLayout {
            anchors.fill: parent
            anchors.margins: eb.viewMode === "compact" ? 10 : 14
            spacing: 14

            // Compact / wide cover box (Mentions.dc.html)
            Rectangle {
                visible: eb.viewMode === "compact" || eb.viewMode === "card"
                Layout.preferredWidth: eb.viewMode === "compact" ? 30 : 54
                Layout.preferredHeight: eb.viewMode === "compact" ? 34 : 62
                color: Theme.ink
                border.width: 1; border.color: Theme.line2
                Rectangle {
                    anchors.centerIn: parent
                    width: eb.viewMode === "compact" ? 10 : 16
                    height: width
                    color: Theme.typeColor(eb.embeddedType)
                }
            }

            ColumnLayout {
                Layout.fillWidth: true
                spacing: 4

                Text {
                    text: Theme.caps(Theme.typeName(eb.embeddedType) + (eb.viewMode === "full" ? (" · " + qsTr("READ ONLY")) : ""))
                    color: Theme.typeColor(eb.embeddedType)
                    font.family: Theme.mono; font.pixelSize: 10
                }
                Text {
                    Layout.fillWidth: true
                    text: eb.displayTitle
                    color: Theme.text
                    font.family: Theme.body
                    font.pixelSize: eb.viewMode === "full" ? 18 : eb.viewMode === "card" ? 16 : 14
                    elide: Text.ElideRight
                }
                Text {
                    visible: (eb.viewMode === "card" || eb.viewMode === "full") && eb.snippet.length > 0
                    Layout.fillWidth: true
                    text: eb.snippet
                    color: Theme.muted
                    font.family: Theme.body; font.pixelSize: 13; lineHeight: 1.4
                    wrapMode: Text.WordWrap
                    maximumLineCount: eb.viewMode === "full" ? 3 : 1
                    elide: Text.ElideRight
                }
            }
        }
    }

    // Hover mode switcher (Chip | Small | Wide | Full — Mentions.dc.html)
    Row {
        visible: !eb.isQuery && outerHover.hovered && !!view && !!view.session
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.margins: 6
        spacing: 2
        z: 5
        Repeater {
            model: [
                { key: "inline", label: qsTr("Chip") },
                { key: "compact", label: qsTr("Small") },
                { key: "card", label: qsTr("Wide") },
                { key: "full", label: qsTr("Full") }
            ]
            delegate: Rectangle {
                required property var modelData
                readonly property bool on: eb.viewMode === modelData.key
                implicitWidth: ml.implicitWidth + 12; height: 22
                color: on ? Theme.accent : Theme.sheet
                border.width: 1; border.color: on ? Theme.accent : Theme.line2
                Text { id: ml; anchors.centerIn: parent; text: modelData.label; color: on ? Theme.accentInk : Theme.dim; font.family: Theme.body; font.pixelSize: 11 }
                HoverHandler { cursorShape: Qt.PointingHandCursor }
                TapHandler { onTapped: if (view && view.session) view.session.setEmbedViewMode(eb.row, modelData.key) }
            }
        }
    }
}
