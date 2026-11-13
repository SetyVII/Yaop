import QtQuick
import QtQuick.Effects
import "."

Item {
    id: root
    property string name: "search"
    property color color: Theme.text
    property int size: 16
    implicitWidth: size
    implicitHeight: size

    Behavior on color { ColorAnimation { duration: Theme.fast } }

    Image {
        anchors.fill: parent
        source: "../assets/icons/" + root.name + ".svg"
        sourceSize: Qt.size(root.size, root.size)
        fillMode: Image.PreserveAspectFit
        smooth: true
        layer.enabled: true
        layer.effect: MultiEffect {
            colorizationColor: root.color
            colorization: 1.0
        }
    }
}
