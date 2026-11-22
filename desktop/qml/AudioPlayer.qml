import QtQuick
import QtQuick.Controls
import QtQuick.Shapes
import YAOP.Core 1.0
import "."

// Audio transport shared by audio blocks and audio objects (BlockTypes.dc.html#audio):
// play/pause, a waveform that fills with the accent as it plays, click or drag on it to seek,
// elapsed / total time. Keys when focused: Space/Enter play-pause, ←/→ 5 s, Home to start.
Rectangle {
    id: root
    property alias assetId: pb.assetId
    property alias path: pb.path
    property int durationSeconds: 0
    property bool ready: true
    property string caption: ""
    readonly property alias playback: pb
    readonly property bool playing: pb.playing
    property bool focusRing: true

    function toggle() { pb.toggle() }
    function stop() { pb.stop() }

    implicitHeight: 64
    color: Theme.panel
    border.width: 1
    border.color: activeFocus ? Theme.line3 : (hover.hovered ? Theme.line2 : Theme.line)
    Behavior on border.color { ColorAnimation { duration: Theme.fast } }

    activeFocusOnTab: true
    Accessible.role: Accessible.Button
    Accessible.name: pb.playing ? qsTr("Pause audio") : qsTr("Play audio")
    Keys.onSpacePressed: pb.toggle()
    Keys.onReturnPressed: pb.toggle()
    Keys.onLeftPressed: pb.seek(pb.position - 5)
    Keys.onRightPressed: pb.seek(pb.position + 5)
    Keys.onPressed: (event) => {
        if (event.key === Qt.Key_Home) { pb.seek(0); event.accepted = true }
    }

    AudioPlayback {
        id: pb
        durationSeconds: root.durationSeconds
        peakCount: 64
        onFailed: (reason) => {
            const w = root.Window.window
            if (w && w.showToast)
                w.showToast(reason === "ffplay" ? qsTr("Install ffmpeg (ffplay) to play audio.")
                                                : qsTr("The audio file is not on this device yet."))
        }
    }
    HoverHandler { id: hover }

    readonly property real progress: pb.durationSeconds > 0 ? Math.min(1, pb.position / pb.durationSeconds) : 0

    function clock(s) {
        s = Math.max(0, Math.floor(s))
        const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60
        const mm = h > 0 ? String(m).padStart(2, "0") : String(m)
        return (h > 0 ? h + ":" : "") + mm + ":" + String(sec).padStart(2, "0")
    }

    Row {
        anchors.fill: parent
        anchors.leftMargin: 12
        anchors.rightMargin: 14
        spacing: 14

        // play / pause
        Rectangle {
            id: btn
            anchors.verticalCenter: parent.verticalCenter
            width: 38; height: 38
            radius: Theme.noir ? 0 : Theme.radiusSm
            color: pb.playing ? Theme.accent : (btnHover.hovered ? Theme.panel3 : Theme.panel2)
            border.width: pb.playing ? 0 : 1
            border.color: Theme.line2
            scale: btnTap.pressed ? 0.94 : 1
            Behavior on color { ColorAnimation { duration: Theme.fast } }
            Behavior on scale { NumberAnimation { duration: Theme.fast; easing.type: Easing.OutCubic } }

            // triangle
            Shape {
                anchors.centerIn: parent
                width: 14; height: 16
                visible: !pb.playing
                preferredRendererType: Shape.CurveRenderer
                ShapePath {
                    strokeWidth: 0
                    fillColor: Theme.text
                    startX: 1; startY: 0
                    PathLine { x: 14; y: 8 }
                    PathLine { x: 1; y: 16 }
                    PathLine { x: 1; y: 0 }
                }
            }
            // pause bars
            Row {
                anchors.centerIn: parent
                visible: pb.playing
                spacing: 4
                Rectangle { width: 4; height: 15; color: Theme.accentInk }
                Rectangle { width: 4; height: 15; color: Theme.accentInk }
            }
            HoverHandler { id: btnHover; cursorShape: Qt.PointingHandCursor }
            TapHandler { id: btnTap; onTapped: { root.forceActiveFocus(); pb.toggle() } }
        }

        // waveform + caption
        Item {
            id: waveBox
            anchors.verticalCenter: parent.verticalCenter
            width: parent.width - btn.width - timeLbl.width - parent.spacing * 2
            height: root.caption.length > 0 ? 44 : 34

            Item {
                id: wave
                width: parent.width
                height: 28
                readonly property int bars: 64
                readonly property real gap: 2
                readonly property real barW: Math.max(1.5, (width - gap * (bars - 1)) / bars)

                Repeater {
                    model: wave.bars
                    delegate: Rectangle {
                        required property int index
                        readonly property real peak: {
                            const p = pb.peaks
                            if (p && p.length > index) return p[index]
                            // placeholder until the file is read: a calm, fixed shape
                            return 0.25 + 0.2 * Math.abs(Math.sin(index * 0.7)) + 0.1 * ((index * 7919) % 5) / 5
                        }
                        readonly property bool played: (index + 0.5) / wave.bars <= root.progress
                        x: index * (wave.barW + wave.gap)
                        width: wave.barW
                        height: Math.max(3, peak * wave.height)
                        anchors.verticalCenter: parent.verticalCenter
                        color: played ? Theme.accent : (hover.hovered ? Theme.line3 : Theme.line2)
                        opacity: pb.peaks.length > 0 ? 1 : 0.6
                        Behavior on color { ColorAnimation { duration: Theme.fast } }
                        Behavior on height { NumberAnimation { duration: Theme.normal; easing.type: Easing.OutCubic } }
                    }
                }

                // hover position marker
                Rectangle {
                    visible: seekHover.hovered && pb.durationSeconds > 0
                    x: Math.max(0, Math.min(wave.width - 1, seekHover.point.position.x))
                    width: 1; height: wave.height
                    color: Theme.text
                    opacity: 0.5
                }

                HoverHandler { id: seekHover; cursorShape: pb.durationSeconds > 0 ? Qt.PointingHandCursor : Qt.ArrowCursor }
                TapHandler {
                    onTapped: (ev) => { root.forceActiveFocus(); pb.seekFraction(ev.position.x / wave.width) }
                }
                DragHandler {
                    target: null
                    xAxis.enabled: true; yAxis.enabled: false
                    onActiveChanged: if (!active) pb.seekFraction(centroid.position.x / wave.width)
                }
            }

            Text {
                visible: root.caption.length > 0
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.bottom: parent.bottom
                text: root.caption
                color: Theme.faint
                font.family: Theme.mono
                font.pixelSize: 11
                elide: Text.ElideRight
            }
        }

        TextMetrics { id: timeMetrics; font: timeLbl.font; text: "00:00 / 00:00" }
        Text {
            id: timeLbl
            anchors.verticalCenter: parent.verticalCenter
            width: Math.max(implicitWidth, timeMetrics.width)
            horizontalAlignment: Text.AlignRight
            text: !root.ready ? qsTr("downloading…")
                 : (pb.playing || pb.position > 0 ? root.clock(pb.position) + " / " : "") + root.clock(pb.durationSeconds)
            color: pb.playing ? Theme.text : Theme.muted
            font.family: Theme.mono
            font.pixelSize: 12
            font.features: { "tnum": 1 }
        }
    }
}
