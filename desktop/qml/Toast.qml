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
        anchors.bottomMargin: 24
        width: label.implicitWidth + 30
        height: 38
        radius: 19
        color: Theme.sheet
        border.width: 1
        border.color: Theme.line
        opacity: root.open ? 1 : 0
        y: root.open ? 0 : 8

        Behavior on opacity { NumberAnimation { duration: 160 } }
        Behavior on y { NumberAnimation { duration: 180; easing.type: Easing.OutCubic } }

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
