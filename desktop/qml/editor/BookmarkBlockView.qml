import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../"

// Web bookmark block: OG thumbnail preview on the right, favicon + domain,
// title (OG title when present) and description. Click opens the URL.

Item {
    id: bb

    property var view
    property int row
    property string objectId: ""
    property string url
    property string title
    property string description
    property string domain
    property string thumbnailUrl: ""
    property string faviconUrl: ""

    readonly property bool hasUrl: url.length > 0

    implicitHeight: 104

    Rectangle {
        visible: bb.hasUrl
        anchors.fill: parent
        anchors.leftMargin: 2
        anchors.rightMargin: 2
        anchors.topMargin: 3
        anchors.bottomMargin: 3
        radius: Theme.radius
        color: bmHover.hovered ? Theme.panel2 : Theme.panel
        clip: true

        HoverHandler { id: bmHover; cursorShape: Qt.PointingHandCursor }
        TapHandler {
            id: bmTap
            onTapped: {
                if ((bmTap.point.modifiers & (Qt.ControlModifier | Qt.MetaModifier)) && bb.objectId.length > 0 && bb.view)
                    bb.view.openObjectRequested(bb.objectId)
                else if (bb.hasUrl)
                    Qt.openUrlExternally(bb.url)
            }
        }

        RowLayout {
            anchors.fill: parent
            anchors.margins: 12
            anchors.leftMargin: 14
            spacing: 12

            // favicon (remote) falling back to the domain letter tile
            Item {
                Layout.preferredWidth: 34
                Layout.preferredHeight: 34

                Rectangle {
                    anchors.fill: parent
                    color: Theme.panel2
                    border.color: Theme.line
                    border.width: 1
                    visible: !(bb.faviconUrl.length > 0 && favicon.status === Image.Ready)

                    Text {
                        anchors.centerIn: parent
                        text: bb.domain.length > 0 ? bb.domain.substring(0, 1).toUpperCase() : "•"
                        color: Theme.dim
                        font.family: Theme.mono
                        font.pixelSize: 15
                    }
                }
                Image {
                    id: favicon
                    anchors.centerIn: parent
                    width: 18
                    height: 18
                    visible: bb.faviconUrl.length > 0 && status === Image.Ready
                    source: bb.faviconUrl
                    sourceSize: Qt.size(36, 36)
                    fillMode: Image.PreserveAspectFit
                    asynchronous: true
                    cache: true
                }
            }

            ColumnLayout {
                spacing: 3
                Layout.fillWidth: true

                Text {
                    text: bb.title.length > 0 ? bb.title : bb.url
                    color: Theme.text
                    font.family: Theme.body
                    font.pixelSize: 13
                    font.weight: Font.Medium
                    elide: Text.ElideRight
                    Layout.fillWidth: true
                }
                Text {
                    visible: bb.description.length > 0
                    text: bb.description
                    color: Theme.muted
                    font.family: Theme.body
                    font.pixelSize: 12
                    elide: Text.ElideRight
                    maximumLineCount: 2
                    wrapMode: Text.Wrap
                    Layout.fillWidth: true
                }

                RowLayout {
                    spacing: 6
                    Text {
                        text: bb.domain.length > 0 ? bb.domain.toUpperCase() : ""
                        color: Theme.faint
                        font.family: Theme.mono
                        font.pixelSize: 11
                        font.letterSpacing: Theme.track(1)
                    }
                    Text {
                        visible: bb.url.length > 0
                        text: "↗"
                        color: Theme.faint
                        font.family: Theme.mono
                        font.pixelSize: 11
                    }
                    Rectangle {
                        visible: bb.objectId.length > 0
                        Layout.preferredHeight: 18
                        Layout.preferredWidth: wlLabel.implicitWidth + 12
                        radius: 9
                        color: wlHover.hovered ? Theme.panel3 : Theme.panel2
                        border.color: Theme.line
                        border.width: 1
                        Text {
                            id: wlLabel
                            anchors.centerIn: parent
                            text: Theme.caps(qsTr("WEBLINK"))
                            color: Theme.ice
                            font.family: Theme.mono
                            font.pixelSize: 9
                            font.letterSpacing: Theme.track(0.8)
                        }
                        HoverHandler { id: wlHover; cursorShape: Qt.PointingHandCursor }
                        TapHandler {
                            onTapped: if (bb.view && bb.objectId.length > 0) bb.view.openObjectRequested(bb.objectId)
                        }
                    }
                }
            }

            // OG / link preview image
            PrivateImage {
                inObject: true
                id: ogThumb
                visible: bb.thumbnailUrl.length > 0
                Layout.preferredWidth: 118
                Layout.fillHeight: true
                Layout.leftMargin: 4
                source: bb.thumbnailUrl
                sourceSize: Qt.size(240, 240)
                fillMode: Image.PreserveAspectCrop
                asynchronous: true
                cache: true
                smooth: true
                opacity: status === Image.Ready ? 1 : 0
            }
            Rectangle {
                visible: bb.thumbnailUrl.length > 0 && ogThumb.status !== Image.Ready
                Layout.preferredWidth: 118
                Layout.fillHeight: true
                Layout.leftMargin: 4
                color: Theme.panel2
                border.color: Theme.line
                Text {
                    anchors.centerIn: parent
                    text: ogThumb.status === Image.Error ? "✕" : "···"
                    color: Theme.faint
                    font.family: Theme.mono
                    font.pixelSize: 11
                }
            }
        }
    }

    Text {
        visible: !bb.hasUrl
        anchors.left: parent.left
        anchors.leftMargin: 6
        anchors.verticalCenter: parent.verticalCenter
        text: qsTr("empty bookmark")
        color: Theme.faint
        font.family: Theme.mono
        font.pixelSize: 11
    }
}
