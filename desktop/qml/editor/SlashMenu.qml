import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../"

// Slash catalog: block creation menu ("/" trigger), filtered by what is typed after the slash.
// The popup NEVER takes keyboard focus: the editor keeps typing and forwards Up/Down/Enter/Esc
// through move()/accept(), so the whole flow works without touching the mouse.

Popup {
    id: sm

    property string query: ""
    property var parentView: null
    // Space the host can give to the list (set by BlocksView.placePopup); the list scrolls past it.
    property real maxListHeight: 360
    property int currentIndex: 0
    signal selected(string key)
    signal closeRequested()
    signal mediaPickRequested(string kind)

    readonly property real rowHeight: 34
    readonly property real headerHeight: 34
    // Height the popup would like with no space limit (host uses it to pick above/below).
    readonly property real naturalHeight: headerHeight + Math.max(1, items.length) * rowHeight + 2

    padding: 1
    modal: false
    focus: false
    closePolicy: Popup.NoAutoClose

    // Opens in 140 ms (fade, 0.96 → 1 from its corner); the first rows arrive 20 ms apart.
    // Rows rebuilt while filtering do not animate.
    property bool justOpened: false
    onAboutToShow: { justOpened = Theme.motion > 0; staggerEnd.restart() }
    Timer { id: staggerEnd; interval: 60; onTriggered: sm.justOpened = false }
    transformOrigin: Popup.TopLeft
    enter: Transition {
        NumberAnimation { property: "opacity"; from: 0; to: 1; duration: Theme.fast }
        NumberAnimation { property: "scale"; from: 0.96; to: 1; duration: Theme.fast; easing.type: Easing.Bezier; easing.bezierCurve: Theme.easeEnter }
    }
    exit: Transition {
        NumberAnimation { property: "opacity"; to: 0; duration: 100 * Theme.motion }
    }

    readonly property var catalogItems: [
        { key: "h1", name: qsTr("Heading 1"), hint: "# ", glyph: "H1" },
        { key: "h2", name: qsTr("Heading 2"), hint: "## ", glyph: "H2" },
        { key: "h3", name: qsTr("Heading 3"), hint: "### ", glyph: "H3" },
        { key: "h4", name: qsTr("Heading 4"), hint: "#### ", glyph: "H4" },
        { key: "paragraph", name: qsTr("Text"), hint: "", glyph: "¶" },
        { key: "todo", name: qsTr("Checkbox"), hint: "[] ", glyph: "☑" },
        { key: "bullet", name: qsTr("Bullet list"), hint: "- ", glyph: "•" },
        { key: "numbered", name: qsTr("Numbered list"), hint: "1. ", glyph: "1." },
        { key: "alphabetic", name: qsTr("Alphabetic list"), hint: "a. ", glyph: "a." },
        { key: "roman", name: qsTr("Roman list"), hint: "i. ", glyph: "I." },
        { key: "toggle", name: qsTr("Toggle"), hint: "▶ ", glyph: "▾" },
        { key: "quote", name: qsTr("Quote"), hint: "> ", glyph: "❝" },
        { key: "callout", name: qsTr("Callout"), hint: "! ", glyph: "!" },
        { key: "group", name: qsTr("Group"), hint: ">> ", glyph: "❐" },
        { key: "code", name: qsTr("Code"), hint: "```", glyph: "{}" },
        { key: "math", name: qsTr("Math / LaTeX"), hint: "$$", glyph: "∑" },
        { key: "table", name: qsTr("Table"), hint: "| ", glyph: "▦" },
        { key: "divider", name: qsTr("Divider"), hint: "---", glyph: "—" },
        { key: "bookmark", name: qsTr("Bookmark / url"), hint: "url", glyph: "↗" },
        { key: "canvas", name: qsTr("Whiteboard"), hint: "", glyph: "⬚" },
        { key: "image", name: qsTr("Image…"), hint: "", glyph: "▣" },
        { key: "audio", name: qsTr("Audio…"), hint: "", glyph: "♪" },
        { key: "pdf", name: Theme.caps(qsTr("PDF…")), hint: "", glyph: "▤" },
        { key: "embed", name: qsTr("Reference…"), hint: "@", glyph: "@" }
    ]

    readonly property var items: {
        const q = query.trim().toLowerCase()
        if (q.length === 0)
            return catalogItems
        return catalogItems.filter(it => it.name.toLowerCase().indexOf(q) >= 0 || it.key.indexOf(q) >= 0)
    }

    onItemsChanged: currentIndex = 0

    // ---- keyboard API (called by the editor) ----
    function move(delta) {
        if (items.length === 0)
            return
        currentIndex = (currentIndex + delta + items.length) % items.length
        catalog.positionViewAtIndex(currentIndex, ListView.Contain)
    }

    // Returns false when there is nothing to pick, so Enter keeps its normal meaning.
    function accept() {
        if (items.length === 0 || currentIndex < 0 || currentIndex >= items.length)
            return false
        activate(items[currentIndex].key)
        return true
    }

    function activate(key) {
        if (key === "image" || key === "audio" || key === "pdf") {
            sm.selected("paragraph") // collapse the "/query" token
            sm.mediaPickRequested(key)
        } else if (key === "embed") {
            sm.selected("paragraph")
            if (sm.parentView && sm.parentView.probeEmbedFlow)
                sm.parentView.probeEmbedFlow()
        } else {
            sm.selected(key)
        }
        sm.closeRequested()
    }

    background: Rectangle {
        radius: Theme.noir ? 0 : 18
        color: Theme.sheet
        border.color: Theme.line2
        border.width: 1
    }

    contentItem: ColumnLayout {
        spacing: 0

        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: sm.headerHeight
            color: Theme.panel

            Text {
                anchors.left: parent.left
                anchors.leftMargin: 12
                anchors.verticalCenter: parent.verticalCenter
                text: sm.query.length > 0 ? qsTr("/ %1").arg(sm.query.toUpperCase()) : Theme.caps(qsTr("BLOCKS"))
                color: Theme.ice
                font.family: Theme.mono
                font.pixelSize: 11
                font.letterSpacing: Theme.track(1.6)
            }
        }

        ListView {
            id: catalog
            Layout.fillWidth: true
            Layout.preferredHeight: Math.min(sm.maxListHeight, sm.items.length * sm.rowHeight)
            spacing: 0
            clip: true
            interactive: contentHeight > height
            boundsBehavior: Flickable.StopAtBounds
            currentIndex: sm.currentIndex
            model: sm.items

            ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }

            delegate: Rectangle {
                id: row
                required property var modelData
                required property int index
                width: catalog.width
                height: sm.rowHeight
                readonly property bool isCur: index === sm.currentIndex
                color: isCur ? (Theme.noir ? Theme.accent : Theme.panel3) : "transparent"
                Component.onCompleted: if (sm.justOpened && index < 8) rowIn.start()
                SequentialAnimation {
                    id: rowIn
                    PropertyAction { target: row; property: "opacity"; value: 0 }
                    PauseAnimation { duration: row.index * 20 * Theme.motion }
                    NumberAnimation { target: row; property: "opacity"; to: 1; duration: Theme.fast; easing.type: Easing.Bezier; easing.bezierCurve: Theme.easeEnter }
                }

                Rectangle {
                    id: glyphBox
                    anchors.left: parent.left
                    anchors.leftMargin: 10
                    anchors.verticalCenter: parent.verticalCenter
                    width: 26
                    height: 22
                    color: row.isCur && Theme.noir ? "transparent" : Theme.panel2
                    border.color: row.isCur && Theme.noir ? "#000000" : Theme.line
                    Text {
                        anchors.centerIn: parent
                        text: row.modelData.glyph
                        color: row.isCur && Theme.noir ? Theme.accentInk : Theme.dim
                        font.family: Theme.mono
                        font.pixelSize: 11
                    }
                }

                Text {
                    anchors.left: glyphBox.right
                    anchors.leftMargin: 10
                    anchors.right: hintText.visible ? hintText.left : parent.right
                    anchors.rightMargin: 10
                    anchors.verticalCenter: parent.verticalCenter
                    horizontalAlignment: Text.AlignLeft
                    text: row.modelData.name
                    color: row.isCur && Theme.noir ? Theme.accentInk : Theme.text
                    font.family: Theme.body
                    font.pixelSize: 13
                    elide: Text.ElideRight
                }

                Text {
                    id: hintText
                    anchors.right: parent.right
                    anchors.rightMargin: 12
                    anchors.verticalCenter: parent.verticalCenter
                    visible: row.modelData.hint.length > 0
                    text: row.modelData.hint.trim()
                    color: row.isCur && Theme.noir ? Theme.accentInk : Theme.faint
                    font.family: Theme.mono
                    font.pixelSize: 11
                }

                MouseArea {
                    anchors.fill: parent
                    hoverEnabled: true
                    onEntered: sm.currentIndex = row.index
                    onClicked: sm.activate(row.modelData.key)
                }
            }
        }

        Text {
            visible: sm.items.length === 0
            Layout.fillWidth: true
            Layout.preferredHeight: sm.rowHeight
            verticalAlignment: Text.AlignVCenter
            leftPadding: 12
            text: qsTr("No matching block")
            color: Theme.faint
            font.family: Theme.body
            font.pixelSize: 11
        }
    }

    height: headerHeight + (items.length === 0 ? rowHeight : Math.min(maxListHeight, items.length * rowHeight)) + 2
}
