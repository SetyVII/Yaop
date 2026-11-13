import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "."

Item {
    id: page
    property var appWindow
    signal openObjectRequested(string objectId)
    property real zoom: 1.0
    clip: true

    // Real whiteboard objects synced from the server
    property var realBoards: []

    function refreshBoards() {
        realBoards = ws ? ws.objectsByType("whiteboard", 0) : []
    }

    Component.onCompleted: refreshBoards()

    Connections {
        target: typeof ws !== "undefined" ? ws : null
        function onObjectListChanged() {
            page.refreshBoards()
        }
    }

    // Board index strip (live data)
    Panel {
        id: boardsPanel
        visible: realBoards.length > 0
        anchors.topMargin: Theme.pagePad
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.margins: Theme.pagePad
        label: qsTr("BOARDS FROM SERVER")

        Flow {
            Layout.fillWidth: true
            spacing: 10

            Repeater {
                model: page.realBoards

                delegate: Item {
                    required property var modelData
                    width: 220
                    height: 56

                    Rectangle {
                        anchors.fill: parent
                        color: boardHover.hovered ? Theme.panel2 : Theme.panel
                        border.color: boardHover.hovered ? Theme.line3 : Theme.line
                        border.width: 1

                        ColumnLayout {
                            anchors.fill: parent
                            anchors.margins: 10
                            spacing: 3

                            Row {
                                spacing: 3
                                Repeater {
                                    model: 8
                                    Rectangle { width: 3; height: 3; color: Theme.faint }
                                }
                            }
                            Text {
                                text: modelData.title
                                color: Theme.dim
                                font.family: Theme.body
                                font.pixelSize: 12
                                elide: Text.ElideRight
                                Layout.fillWidth: true
                            }
                            Text {
                                text: qsTr("%1 blocks").arg(modelData.blockCount !== undefined ? modelData.blockCount : "?")
                                color: Theme.faint
                                font.family: Theme.mono
                                font.pixelSize: 11
                            }
                        }

                        HoverHandler { id: boardHover; cursorShape: Qt.PointingHandCursor }
                        TapHandler { onTapped: openObjectRequested(modelData.id) }
                    }
                }
            }
        }
    }

    property var nodes: [
        {
            x: 90, y: 90, w: 230, h: 150,
            kind: "sticky", tone: "lime", type: "note",
            title: "What if capture was effortless?",
            body: "No folders. Just a thought and a link.",
            author: "S", foot: "STICKY / 09:12"
        },
        {
            x: 390, y: 70, w: 220, h: 130,
            kind: "sticky", tone: "dark", type: "note",
            title: "Every object is a link.",
            body: "Notes, tasks, people, projects.",
            author: "M", foot: "STICKY / 09:18"
        },
        {
            x: 680, y: 150, w: 270, h: 160,
            kind: "note", type: "note", tag: "NOTE",
            title: "Onboarding flow",
            body: "1. Capture → 2. Connect → 3. Sync",
            foot: "3 STEPS / 2 OPEN"
        },
        {
            x: 120, y: 360, w: 280, h: 190,
            kind: "task", type: "task", tag: "TASK",
            title: "Open questions",
            items: [
                { title: "Can sync be invisible?", done: true },
                { title: "What does offline feel like?", done: true },
                { title: "Where does search live?", done: false }
            ],
            foot: "2 / 3 DONE"
        },
        {
            x: 720, y: 380, w: 190, h: 90,
            kind: "person", type: "person",
            title: "Ada",
            body: "facilitator",
            foot: "PERSON"
        },
        {
            x: 450, y: 400, w: 240, h: 170,
            kind: "media", type: "image", tag: "IMAGE",
            title: "Whiteboard sketch",
            body: "Nov 8 · 2048 × 1365",
            foot: "FROM PRODUCT THINKING"
        }
    ]

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: Theme.pagePad
        anchors.topMargin: realBoards.length > 0 ? Theme.pagePad + 96 : Theme.pagePad
        spacing: 16

        // Header
        RowLayout {
            Layout.fillWidth: true
            spacing: 12

            ColumnLayout {
                spacing: 5

                RowLayout {
                    spacing: 8

                    Rectangle { width: 6; height: 6; color: Theme.ice }

                    Text {
                        text: "WHITEBOARD / WORK"
                        color: Theme.muted
                        font.family: Theme.mono
                        font.pixelSize: 11
                        font.letterSpacing: 1.6
                        font.weight: Font.Medium
                    }
                }

                RowLayout {
                    spacing: 12

                    Text {
                        text: "Product thinking"
                        color: Theme.text
                        font.family: Theme.display
                        font.pixelSize: 30
                        font.weight: Font.DemiBold
                        font.letterSpacing: -0.8
                    }

                    Text {
                        text: page.nodes.length + " NODES"
                        color: Theme.muted
                        font.family: Theme.mono
                        font.pixelSize: 11
                        font.letterSpacing: 0.9
                        Layout.alignment: Qt.AlignBottom
                        Layout.bottomMargin: 5
                    }
                }
            }

            Item { Layout.fillWidth: true }

            Button {
                text: "SHARE"
                small: true
                onClicked: appWindow.showToast("Board link copied.")
            }

            IconButton {
                icon: "dots"
                onClicked: appWindow.showToast("Board options.")
            }
        }

        // Board
        Rectangle {
            Layout.fillWidth: true
            Layout.fillHeight: true
            color: Theme.panel
            border.width: 1
            border.color: Theme.line
            clip: true

            Item {
                id: viewport
                anchors.fill: parent
                clip: true

                MouseArea {
                    anchors.fill: parent
                    drag.target: boardContent
                    drag.axis: Drag.XAndYAxis
                    drag.minimumX: -900
                    drag.maximumX: 300
                    drag.minimumY: -700
                    drag.maximumY: 200
                    cursorShape: pressed ? Qt.ClosedHandCursor : Qt.OpenHandCursor
                }

                Item {
                    id: boardContent
                    x: 0
                    y: 0
                    width: 1600
                    height: 1000
                    scale: page.zoom
                    transformOrigin: Item.TopLeft

                    Canvas {
                        anchors.fill: parent
                        onPaint: {
                            const ctx = getContext("2d")
                            ctx.clearRect(0, 0, width, height)

                            ctx.fillStyle = "#151515"
                            for (let x = 0; x < width; x += 24) {
                                for (let y = 0; y < height; y += 24) {
                                    ctx.fillRect(x, y, 1, 1)
                                }
                            }

                            ctx.fillStyle = Theme.line
                            for (let x = 0; x < width; x += 96) {
                                for (let y = 0; y < height; y += 96) {
                                    ctx.fillRect(x - 2, y, 5, 1)
                                    ctx.fillRect(x, y - 2, 1, 5)
                                }
                            }
                        }
                        onWidthChanged: requestPaint()
                        onHeightChanged: requestPaint()
                    }

                    Canvas {
                        id: links
                        anchors.fill: parent

                        function center(n) {
                            return { x: n.x + n.w / 2, y: n.y + n.h / 2 }
                        }

                        function line(a, b, color) {
                            const ctx = getContext("2d")
                            const ca = center(a)
                            const cb = center(b)
                            ctx.strokeStyle = color
                            ctx.lineWidth = 1
                            ctx.beginPath()
                            ctx.moveTo(ca.x, ca.y)
                            ctx.lineTo(cb.x, cb.y)
                            ctx.stroke()
                            ctx.fillStyle = color
                            ctx.fillRect(ca.x - 1, ca.y - 1, 3, 3)
                            ctx.fillRect(cb.x - 1, cb.y - 1, 3, 3)
                        }

                        onPaint: {
                            const ctx = getContext("2d")
                            ctx.clearRect(0, 0, width, height)
                            const n = page.nodes
                            if (n.length < 6)
                                return

                            line(n[0], n[2], Theme.line2)
                            line(n[1], n[2], Theme.line2)
                            line(n[2], n[3], Theme.line2)
                            line(n[0], n[4], Theme.line2)
                            line(n[3], n[4], Theme.line2)
                            line(n[2], n[5], Theme.ice)
                        }

                        Component.onCompleted: requestPaint()
                    }

                    Repeater {
                        model: page.nodes

                        delegate: BoardNode {
                            node: modelData
                            linksCanvas: links
                        }
                    }
                }

                // Toolbar
                Rectangle {
                    x: 16
                    y: 16
                    height: 36
                    width: toolbar.implicitWidth + 12
                    color: Theme.panel
                    border.width: 1
                    border.color: Theme.line2

                    RowLayout {
                        id: toolbar
                        anchors.centerIn: parent
                        spacing: 2

                        IconButton { icon: "cursor"; active: true }
                        IconButton { icon: "hand" }

                        Rectangle { width: 1; height: 18; color: Theme.line; Layout.leftMargin: 4; Layout.rightMargin: 4 }

                        IconButton { icon: "sticky" }
                        IconButton { icon: "text" }
                        IconButton { icon: "shape" }
                        IconButton { icon: "connect" }
                        IconButton { icon: "comment" }
                    }
                }

                // Hint
                Text {
                    anchors.left: parent.left
                    anchors.bottom: parent.bottom
                    anchors.margins: 16
                    text: "DRAG TO PAN / SCROLL TO ZOOM"
                    color: Theme.muted
                    font.family: Theme.mono
                    font.pixelSize: 11
                    font.letterSpacing: 1.0
                }

                // Minimap
                Rectangle {
                    anchors.right: parent.right
                    anchors.bottom: parent.bottom
                    anchors.rightMargin: 16
                    anchors.bottomMargin: 58
                    width: 132
                    height: 86
                    color: Theme.panel
                    border.width: 1
                    border.color: Theme.line2

                    Canvas {
                        anchors.fill: parent
                        onPaint: {
                            const ctx = getContext("2d")
                            ctx.clearRect(0, 0, width, height)

                            ctx.fillStyle = Theme.line
                            for (let x = 0; x < width; x += 12) {
                                for (let y = 0; y < height; y += 12) {
                                    ctx.fillRect(x, y, 1, 1)
                                }
                            }

                            for (let i = 0; i < page.nodes.length; ++i) {
                                const n = page.nodes[i]
                                ctx.fillStyle = i === 2 ? Theme.ice : "#5A5A54"
                                ctx.fillRect(4 + (n.x / 1600) * (width - 8), 4 + (n.y / 1000) * (height - 8), 4, 4)
                            }

                            ctx.strokeStyle = Theme.ice
                            ctx.lineWidth = 1
                            ctx.strokeRect(4, 4, width * 0.62, height * 0.52)
                        }
                        onWidthChanged: requestPaint()
                        onHeightChanged: requestPaint()
                    }
                }

                // Zoom
                Rectangle {
                    anchors.right: parent.right
                    anchors.bottom: parent.bottom
                    anchors.margins: 16
                    width: 128
                    height: 32
                    color: Theme.panel
                    border.width: 1
                    border.color: Theme.line2

                    RowLayout {
                        anchors.fill: parent
                        anchors.leftMargin: 2
                        anchors.rightMargin: 2
                        spacing: 0

                        IconButton {
                            icon: "minus"
                            onClicked: page.zoom = Math.max(0.55, page.zoom - 0.1)
                        }

                        Text {
                            Layout.fillWidth: true
                            text: Math.round(page.zoom * 100) + "%"
                            color: Theme.dim
                            font.family: Theme.mono
                            font.pixelSize: 11
                            horizontalAlignment: Text.AlignHCenter
                        }

                        IconButton {
                            icon: "plus"
                            onClicked: page.zoom = Math.min(1.8, page.zoom + 0.1)
                        }
                    }
                }

                WheelHandler {
                    onWheel: (event) => {
                        const factor = Math.exp(-event.angleDelta.y / 600)
                        page.zoom = Math.max(0.55, Math.min(1.8, page.zoom * factor))
                        event.accepted = true
                    }
                }
            }
        }
    }
}
