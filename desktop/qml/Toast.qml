import QtQuick
import "."

Item {
    id: root
    property string message: ""
    property bool open: false

    anchors.fill: parent
    z: 200
    enabled: false

    function show(text) {
        message = text
        open = true
        timer.restart()
    }

    Rectangle {
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.bottom: parent.bottom
        // y is anchored, so the rise animates the margin: 12 px up while fading in.
        anchors.bottomMargin: root.open ? 24 : 12
        width: label.implicitWidth + 30
        height: 38
        radius: 19
        color: Theme.sheet
        border.width: 1
        border.color: Theme.line
        opacity: root.open ? 1 : 0

        Behavior on opacity { NumberAnimation { duration: Theme.normal } }
        Behavior on anchors.bottomMargin {
            NumberAnimation {
                duration: Theme.normal
                easing.type: Easing.Bezier
                easing.bezierCurve: root.open ? Theme.easeEnter : Theme.easeExit
            }
        }

        Text {
            id: label
            anchors.centerIn: parent
            text: root.message
            color: Theme.text
            font.family: Theme.mono
            font.pixelSize: 11
            font.letterSpacing: Theme.track(0.8)
        }
    }

    Timer {
        id: timer
        interval: 2200
        onTriggered: root.open = false
    }
}
