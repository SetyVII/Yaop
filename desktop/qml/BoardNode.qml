import QtQuick
import QtQuick.Layouts
import "."

Rectangle {
    id: root
    property var node: ({})
    property var linksCanvas: null

    readonly property bool lime: node.kind === "sticky" && node.tone === "lime"
    readonly property color fg: lime ? Theme.bg : Theme.text
    readonly property color sub: lime ? "#4A4A1E" : Theme.dim

    x: node.x || 0
    y: node.y || 0
    width: node.w || 220
    height: node.h || 140
    radius: Theme.radiusSm
    color: lime ? Theme.accent : Theme.panel2
    border.width: 1
    border.color: lime ? Theme.accent : Theme.line2
    clip: true

    Behavior on border.color { ColorAnimation { duration: Theme.fast } }

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 14
        spacing: 7

        RowLayout {
            visible: root.node.kind !== "sticky"
            Layout.fillWidth: true
            spacing: 7

            Rectangle {
                width: 5
                height: 5
                color: Theme.typeColor(root.node.type || "note")
            }

            Text {
                text: Theme.typeLabel(String(root.node.type || "note"))
                color: Theme.muted
                font.family: Theme.mono
                font.pixelSize: 11
                font.letterSpacing: Theme.track(1.1)
            }

            Item { Layout.fillWidth: true }

            Text {
                text: root.node.tag || ""
                visible: text.length > 0
                color: Theme.muted
                font.family: Theme.mono
                font.pixelSize: 7
            }
        }

        Text {
            Layout.fillWidth: true
            visible: root.node.kind !== "person"
            text: root.node.title || ""
            color: root.fg
            font.family: Theme.body
            font.pixelSize: root.node.kind === "sticky" ? 14 : 13
            font.weight: Font.Medium
            wrapMode: Text.WordWrap
        }

        Text {
            Layout.fillWidth: true
            visible: root.node.kind !== "person" && text.length > 0
            text: root.node.body || ""
            color: root.sub
            font.family: Theme.body
            font.pixelSize: 11
            wrapMode: Text.WordWrap
        }

        ColumnLayout {
            visible: root.node.kind === "task"
            Layout.fillWidth: true
            spacing: 4

            Repeater {
                model: root.node.items || []

                delegate: RowLayout {
                    Layout.fillWidth: true
                    spacing: 6

                    Rectangle {
                        width: 9
                        height: 9
                        color: modelData.done ? Theme.accent : "transparent"
                        border.width: 1
                        border.color: modelData.done ? Theme.accent : Theme.line3
                    }

                    Text {
                        Layout.fillWidth: true
                        text: modelData.title
                        color: modelData.done ? Theme.muted : Theme.dim
                        font.family: Theme.body
                        font.pixelSize: 11
                        font.strikeout: modelData.done
                        elide: Text.ElideRight
                    }
                }
            }
        }

        RowLayout {
            visible: root.node.kind === "person"
            Layout.fillWidth: true
            spacing: 8

            Rectangle {
                width: 24
                height: 24
                color: Theme.panel3
                border.width: 1
                border.color: Theme.line2

                Text {
                    anchors.centerIn: parent
                    text: "S"
                    color: Theme.text
                    font.family: Theme.mono
                    font.pixelSize: 11
                }
            }

            ColumnLayout {
                spacing: 0

                Text {
                    text: root.node.title || ""
                    color: Theme.text
                    font.family: Theme.body
                    font.pixelSize: 11
                    font.weight: Font.Medium
                }

                Text {
                    text: root.node.body || ""
                    color: Theme.muted
                    font.family: Theme.mono
                    font.pixelSize: 11
                }
            }
        }

        Rectangle {
            visible: root.node.kind === "media"
            Layout.fillWidth: true
            Layout.fillHeight: true
            color: Theme.panel3
            border.width: 1
            border.color: Theme.line

            Canvas {
                anchors.fill: parent
                onPaint: {
                    const ctx = getContext("2d")
                    ctx.clearRect(0, 0, width, height)

                    ctx.fillStyle = Theme.ice
                    for (let x = 0; x < width; x += 6) {
                        for (let y = height * 0.45; y < height; y += 6) {
                            ctx.globalAlpha = 0.16 + 0.22 * (y / height)
                            ctx.fillRect(x, y, 2, 2)
                        }
                    }
                    ctx.globalAlpha = 1

                    ctx.strokeStyle = Theme.ice
                    ctx.lineWidth = 1
                    ctx.beginPath()
                    ctx.moveTo(0, height * 0.7)
                    ctx.bezierCurveTo(width * 0.25, height * 0.3, width * 0.45, height * 0.85, width * 0.62, height * 0.55)
                    ctx.bezierCurveTo(width * 0.78, height * 0.3, width * 0.9, height * 0.7, width, height * 0.48)
                    ctx.stroke()
                }
                onWidthChanged: requestPaint()
                onHeightChanged: requestPaint()
            }
        }

        Item { Layout.fillHeight: true }

        RowLayout {
            Layout.fillWidth: true
            spacing: 4

            Text {
                Layout.fillWidth: true
                text: root.node.foot || ""
                color: root.sub
                font.family: Theme.mono
                font.pixelSize: 11
                elide: Text.ElideRight
            }

            Rectangle {
                visible: root.node.kind === "sticky"
                width: 18
                height: 18
                color: "transparent"
                border.width: 1
                border.color: root.lime ? "#6A6A20" : Theme.line2

                Text {
                    anchors.centerIn: parent
                    text: root.node.author || "S"
                    color: root.fg
                    font.family: Theme.mono
                    font.pixelSize: 11
                }
            }
        }
    }

    HoverHandler { id: hover; cursorShape: Qt.OpenHandCursor }

    DragHandler {
        target: root
        onActiveChanged: {
            if (!active) {
                if (root.node) {
                    root.node.x = root.x
                    root.node.y = root.y
                }
                if (root.linksCanvas)
                    root.linksCanvas.requestPaint()
            }
        }
    }

    onXChanged: if (linksCanvas) linksCanvas.requestPaint()
    onYChanged: if (linksCanvas) linksCanvas.requestPaint()
}
