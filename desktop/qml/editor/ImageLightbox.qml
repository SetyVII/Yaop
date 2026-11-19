import QtQuick
import QtQuick.Controls
import "../"

// Full-window view of an image block: fits the window, wheel zooms (1x to 5x) and dragging pans
// once zoomed. Esc, the close button or a click outside the picture closes it.

Popup {
    id: lb

    property string source
    property string caption

    function show(src, cap) {
        lb.source = src
        // Caption HTML from QTextDocument always carries a <style> head: show it only if it has text.
        const plain = (cap || "").replace(/<style[\s\S]*?<\/style>/gi, "").replace(/<[^>]*>/g, "").trim()
        lb.caption = plain.length > 0 ? cap : ""
        lb.zoom = 1
        lb.open()
    }

    property real zoom: 1

    parent: Overlay.overlay
    x: 0
    y: 0
    width: parent ? parent.width : 0
    height: parent ? parent.height : 0
    padding: 0
    modal: true
    focus: true
    closePolicy: Popup.CloseOnEscape
    background: Item {}
    Overlay.modal: Rectangle { color: Qt.rgba(0, 0, 0, 0.82) }

    enter: Transition { NumberAnimation { property: "opacity"; from: 0; to: 1; duration: Theme.fast } }
    exit: Transition { NumberAnimation { property: "opacity"; from: 1; to: 0; duration: Theme.fast } }

    // Fit size at zoom 1: the whole picture inside the window margins, never above natural size.
    readonly property real availW: Math.max(1, width - 96)
    readonly property real availH: Math.max(1, height - (caption.length > 0 ? 120 : 96))
    readonly property real natW: Math.max(1, img.implicitWidth)
    readonly property real natH: Math.max(1, img.implicitHeight)
    readonly property real fit: Math.min(1, availW / natW, availH / natH)

    Flickable {
        id: flick
        anchors.fill: parent
        contentWidth: Math.max(width, img.width)
        contentHeight: Math.max(height, img.height)
        interactive: lb.zoom > 1
        boundsBehavior: Flickable.StopAtBounds
        clip: true

        // Click on the dark area closes; double click on the picture toggles fit / 2x.
        TapHandler {
            function onPicture(p) {
                return p.x >= img.x && p.x <= img.x + img.width && p.y >= img.y && p.y <= img.y + img.height
            }
            onTapped: (eventPoint) => {
                if (!onPicture(eventPoint.position))
                    lb.close()
            }
            onDoubleTapped: (eventPoint) => {
                if (onPicture(eventPoint.position))
                    lb.zoom = lb.zoom > 1 ? 1 : 2
            }
        }

        PrivateImage {
            id: img
            inObject: true
            width: lb.natW * lb.fit * lb.zoom
            height: lb.natH * lb.fit * lb.zoom
            x: (flick.contentWidth - width) / 2
            y: (flick.contentHeight - height) / 2
            source: lb.source
            fillMode: Image.PreserveAspectFit
            smooth: true
            mipmap: true
            asynchronous: true
            cache: true
        }

        WheelHandler {
            acceptedDevices: PointerDevice.Mouse | PointerDevice.TouchPad
            onWheel: (event) => {
                const step = event.angleDelta.y > 0 ? 1.15 : 1 / 1.15
                lb.zoom = Math.max(1, Math.min(5, lb.zoom * step))
            }
        }
    }

    Text {
        visible: lb.caption.length > 0
        anchors.bottom: parent.bottom
        anchors.bottomMargin: 24
        anchors.horizontalCenter: parent.horizontalCenter
        width: Math.min(parent.width - 96, 720)
        horizontalAlignment: Text.AlignHCenter
        text: lb.caption
        textFormat: Text.RichText
        wrapMode: Text.Wrap
        maximumLineCount: 3
        elide: Text.ElideRight
        color: "#e6e6ea"
        font.family: Theme.body
        font.pixelSize: 13
    }

    Rectangle {
        anchors.top: parent.top
        anchors.right: parent.right
        anchors.margins: 16
        width: 32
        height: 32
        radius: Theme.radius
        color: closeHover.hovered ? Qt.rgba(1, 1, 1, 0.18) : Qt.rgba(1, 1, 1, 0.08)
        TintedIcon {
            anchors.centerIn: parent
            name: "x"
            size: 16
            color: "#e6e6ea"
        }
        HoverHandler { id: closeHover; cursorShape: Qt.PointingHandCursor }
        TapHandler { onTapped: lb.close() }
    }
}
