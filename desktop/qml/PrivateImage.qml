import QtQuick
import QtQuick.Effects
import "."

// Image that honours Privacy. Blur = strong blur + ink veil;
// hide = flat panel box with an image glyph. Alt + hover peeks at one blurred image while held.
Image {
    id: img
    property bool inObject: false
    readonly property int privacyMode: Privacy.effective(inObject)
    readonly property bool concealed: privacyMode !== 0 && !(privacyMode === 1 && peek.hovered)

    HoverHandler {
        id: peek
        enabled: img.privacyMode === 1
        acceptedModifiers: Qt.AltModifier
    }

    layer.enabled: concealed
    layer.effect: privacyMode === 2 ? hideFx : blurFx

    Component {
        id: blurFx
        MultiEffect {
            autoPaddingEnabled: false
            blurEnabled: true
            blur: 1.0
            blurMax: 64
            colorization: 0.45
            colorizationColor: Theme.sheet
        }
    }

    // Layer effects receive `source`; this one ignores it so nothing of the image shows through.
    Component {
        id: hideFx
        Rectangle {
            property var source
            color: Theme.sheet
            TintedIcon {
                anchors.centerIn: parent
                visible: parent.width >= 28 && parent.height >= 28
                name: "photo"
                size: Math.min(22, parent.height * 0.4)
                color: Theme.faint
            }
        }
    }
}
