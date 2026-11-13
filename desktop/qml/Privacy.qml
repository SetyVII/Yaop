pragma Singleton
import QtQuick
import QtCore

// Image privacy: device-local, never synced.
// mode 0 = Show, 1 = Blur, 2 = Hide. scope 0 = Everywhere (default), 1 = Lists only.
QtObject {
    id: privacy

    readonly property Settings store: Settings {
        category: "imagePrivacy"
        property int mode: 0
        property int discrete: 1
        property int scope: 0
        property bool resetOnExit: false
    }

    readonly property int mode: store.mode
    readonly property bool active: store.mode !== 0
    // "Show images in this note": temporary, EditorPage clears it when the object changes.
    property bool noteRevealed: false

    function toggle() { store.mode = store.mode === 0 ? store.discrete : 0 }
    function setMode(m) { if (m !== 0) store.discrete = m; store.mode = m }

    // Mode for an image; inObject = drawn inside the open object (editor, cover, blocks).
    function effective(inObject) {
        if (store.mode === 0) return 0
        if (inObject && (noteRevealed || store.scope === 1)) return 0
        return store.mode
    }

    Component.onCompleted: if (store.resetOnExit) store.mode = 0
}
