import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../"

// A saved query placed in a page (spec/query.md "Query in a page"): an embed block whose target is
// a set. Shows the set's live results in its active view (list, gallery or table; others as a list),
// eight at first with "Show all". The view switcher saves into the set, so every page that holds
// the same query shows it the same way.
Rectangle {
    id: qe

    property var view
    property string setId: ""
    property bool expanded: false
    signal openRequested(string objectId)

    // Results are fetched on load, then at most once per pause after changes (a query over a big
    // vault costs tens of ms; saves arrive every second while typing). Only what is shown is built.
    property var info: ({})
    property var result: ({})
    readonly property var items: result.items || []
    readonly property int total: result.total !== undefined ? result.total : items.length
    function reload() {
        if (!ws || !setId) { info = ({}); result = ({}); return }
        info = ws.setInfo(setId)
        result = ws.runSet(setId, expanded ? 200 : (kind === "gallery" ? 6 : 8))
    }
    Component.onCompleted: reload()
    onSetIdChanged: reload()
    onExpandedChanged: reload()
    Timer { id: reloadTimer; interval: 600; onTriggered: if (qe.visible) qe.reload() }
    Connections {
        target: typeof ws !== "undefined" ? ws : null
        function onObjectListChanged() { reloadTimer.restart() }
    }
    readonly property string kind: {
        const k = info.activeView ? info.activeView.kind : "list"
        return k === "gallery" || k === "table" ? k : "list"
    }
    readonly property var shown: items

    implicitHeight: col.implicitHeight + 2
    color: Theme.page
    border.width: 1
    border.color: hov.hovered ? Theme.line3 : Theme.line2
    radius: Theme.radius
    Behavior on border.color { ColorAnimation { duration: Theme.fast } }
    HoverHandler { id: hov }

    ColumnLayout {
        id: col
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.margins: 1
        spacing: 0

        // header: query name, count, view switch, open
        RowLayout {
            Layout.fillWidth: true
            Layout.preferredHeight: 42
            Layout.leftMargin: 12
            Layout.rightMargin: 6
            spacing: 8
            TintedIcon { name: "filter"; size: 14; color: Theme.label }
            Text {
                text: qe.info.title || qsTr("Query")
                color: Theme.text
                font.family: Theme.body; font.pixelSize: 14; font.weight: Font.Medium
                elide: Text.ElideRight
                Layout.maximumWidth: qe.width * 0.5
            }
            Text {
                text: qe.result.error ? qsTr("error: %1").arg(qe.result.error) : String(qe.total)
                color: qe.result.error ? Theme.danger : Theme.label
                font.family: Theme.mono; font.pixelSize: 11
            }
            Item { Layout.fillWidth: true }
            Row {
                visible: hov.hovered || qe.activeFocus
                spacing: 0
                Repeater {
                    model: [
                        { key: "list", icon: "list", tip: qsTr("List") },
                        { key: "gallery", icon: "layout-grid", tip: qsTr("Gallery") },
                        { key: "table", icon: "table", tip: qsTr("Table") }
                    ]
                    delegate: IconButton {
                        required property var modelData
                        implicitWidth: 28; implicitHeight: 28
                        icon: modelData.icon
                        tip: modelData.tip
                        active: qe.kind === modelData.key
                        onClicked: { ws.setSetActiveView(qe.setId, modelData.key); qe.reload() }
                    }
                }
            }
            IconButton {
                implicitWidth: 28; implicitHeight: 28
                icon: "arrow-up-right"
                tip: qsTr("Open the query")
                onClicked: qe.openRequested(qe.setId)
            }
        }
        Rectangle { Layout.fillWidth: true; height: 1; color: Theme.line }

        // list / table rows
        Repeater {
            model: qe.kind !== "gallery" ? qe.shown : []
            delegate: Rectangle {
                id: r
                required property var modelData
                required property int index
                Layout.fillWidth: true
                implicitHeight: 34
                color: rHov.hovered ? Theme.panel2 : "transparent"
                Behavior on color { ColorAnimation { duration: Theme.fast } }
                RowLayout {
                    anchors.fill: parent
                    anchors.leftMargin: 12
                    anchors.rightMargin: 12
                    spacing: 10
                    TypeBadge { typeId: r.modelData.typeId || ""; size: 14 }
                    Text {
                        Layout.fillWidth: true
                        text: r.modelData.title || qsTr("Untitled")
                        color: rHov.hovered ? Theme.text : Theme.bodyText
                        font.family: Theme.body; font.pixelSize: 13
                        elide: Text.ElideRight
                    }
                    Text {
                        visible: qe.kind === "table"
                        Layout.preferredWidth: 110
                        text: Theme.typeName(r.modelData.typeId || "")
                        color: Theme.muted; font.family: Theme.body; font.pixelSize: 12
                        elide: Text.ElideRight
                    }
                    Text {
                        visible: qe.kind === "table"
                        Layout.preferredWidth: 70
                        horizontalAlignment: Text.AlignRight
                        text: r.modelData.updatedAt ? Qt.formatDateTime(new Date(r.modelData.updatedAt), "d MMM") : ""
                        color: Theme.label; font.family: Theme.mono; font.pixelSize: 11
                    }
                }
                HoverHandler { id: rHov; cursorShape: Qt.PointingHandCursor }
                TapHandler { onTapped: qe.openRequested(r.modelData.id) }
            }
        }

        // gallery
        GridLayout {
            visible: qe.kind === "gallery" && qe.shown.length > 0
            Layout.fillWidth: true
            Layout.margins: 10
            columns: Math.max(1, Math.floor((qe.width - 20) / 190))
            columnSpacing: 8
            rowSpacing: 8
            Repeater {
                model: qe.kind === "gallery" ? qe.shown : []
                delegate: Rectangle {
                    id: g
                    required property var modelData
                    Layout.fillWidth: true
                    implicitHeight: 64
                    color: gHov.hovered ? Theme.panel2 : Theme.card
                    border.width: 1
                    border.color: gHov.hovered ? Theme.line3 : Theme.line
                    Behavior on color { ColorAnimation { duration: Theme.fast } }
                    ColumnLayout {
                        anchors.fill: parent
                        anchors.margins: 10
                        spacing: 4
                        Row {
                            spacing: 6
                            TypeBadge { typeId: g.modelData.typeId || ""; size: 12 }
                            Text {
                                text: Theme.caps(Theme.typeName(g.modelData.typeId || ""))
                                color: Theme.typeColor(g.modelData.typeId || "")
                                font.family: Theme.mono; font.pixelSize: 10
                            }
                        }
                        Text {
                            Layout.fillWidth: true
                            text: g.modelData.title || qsTr("Untitled")
                            color: Theme.text; font.family: Theme.body; font.pixelSize: 13
                            elide: Text.ElideRight
                        }
                    }
                    HoverHandler { id: gHov; cursorShape: Qt.PointingHandCursor }
                    TapHandler { onTapped: qe.openRequested(g.modelData.id) }
                }
            }
        }

        Text {
            visible: qe.total === 0 && !qe.result.error
            Layout.margins: 12
            text: qsTr("Nothing matches this query yet.")
            color: Theme.label; font.family: Theme.body; font.pixelSize: 12
        }

        // show all / fewer
        Rectangle {
            visible: qe.total > (qe.kind === "gallery" ? 6 : 8)
            Layout.fillWidth: true
            implicitHeight: 32
            color: moreHov.hovered ? Theme.panel2 : "transparent"
            Rectangle { width: parent.width; height: 1; color: Theme.lineSubtle }
            Text {
                anchors.centerIn: parent
                text: qe.expanded ? qsTr("Show fewer") : qsTr("Show all %1").arg(qe.total)
                color: moreHov.hovered ? Theme.text : Theme.muted
                font.family: Theme.body; font.pixelSize: 12
            }
            HoverHandler { id: moreHov; cursorShape: Qt.PointingHandCursor }
            TapHandler { onTapped: qe.expanded = !qe.expanded }
        }
    }
}
