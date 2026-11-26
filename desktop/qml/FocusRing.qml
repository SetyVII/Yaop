import QtQuick
import QtQuick.Controls
import "."

// One keyboard focus ring for the window (docs/keyboard.md §2.6). It follows the focused item
// and slides to the next one; only items that declare `property bool focusRing: true` or that
// arrows stop on (keyNav.isStop: anything clickable) get it, and only while you navigate with the keyboard (a click hides it until the next key).
Rectangle {
    id: ring
    // In the overlay layer, so it also draws over popups and sheets.
    parent: Overlay.overlay
    readonly property Item host: parent
    readonly property Item target: {
        const it = host.Window.activeFocusItem
        return it && (it.focusRing === true || keyNav.isStop(it)) && it.visible ? it : null
    }
    readonly property bool shown: target !== null && keyInput.keyboardMode

    property bool sliding: false
    onTargetChanged: { sliding = shown; slideOff.restart(); place() }
    Timer { id: slideOff; interval: Theme.fast + 40; onTriggered: ring.sliding = false }

    function place() {
        if (!target) return
        const p = target.mapToItem(host, 0, 0)
        x = p.x - 3
        y = p.y - 3
        width = target.width + 6
        height = target.height + 6
        radius = (target.radius !== undefined ? target.radius : 6) + 3
    }
    // Follow scrolling and layout changes while shown.
    FrameAnimation { running: ring.shown; onTriggered: ring.place() }

    z: 1000
    visible: opacity > 0
    opacity: shown ? 0.85 : 0
    color: "transparent"
    border.width: 2
    border.color: Theme.focusRing

    Behavior on opacity { NumberAnimation { duration: Theme.fast } }
    Behavior on x { enabled: ring.sliding; NumberAnimation { duration: Theme.fast; easing.type: Easing.OutCubic } }
    Behavior on y { enabled: ring.sliding; NumberAnimation { duration: Theme.fast; easing.type: Easing.OutCubic } }
    Behavior on width { enabled: ring.sliding; NumberAnimation { duration: Theme.fast; easing.type: Easing.OutCubic } }
    Behavior on height { enabled: ring.sliding; NumberAnimation { duration: Theme.fast; easing.type: Easing.OutCubic } }
}
