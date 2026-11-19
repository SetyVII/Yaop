import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "."

// Embedded web view panel for Web link objects. Lazily instantiates QtWebEngine.WebEngineView
// when available at runtime, and gracefully falls back to a native preview card otherwise.
Item {
    id: root

    property string url: ""
    property string title: ""
    property string description: ""
    property string domain: ""
    property string faviconUrl: ""
    property string thumbnailUrl: ""
    property string coverAssetId: ""

    property var webView: null
    property bool engineReady: false
    property bool engineFailed: false
    property bool loading: false
    property int loadProgress: 0
    property string currentUrl: url
    property bool canGoBack: false
    property bool canGoForward: false

    function syncUrl() {
        if (webView && url && String(webView.url) !== url)
            webView.url = url
        currentUrl = url
    }

    onUrlChanged: syncUrl()

    Component.onCompleted: {
        try {
            const qml = 'import QtQuick\n'
                      + 'import QtWebEngine\n'
                      + 'WebEngineView {\n'
                      + '    anchors.fill: parent\n'
                      + '    backgroundColor: "' + Theme.ink + '"\n'
                      + '}\n'
            const obj = Qt.createQmlObject(qml, webHost, "DynamicWebEngineView")
            if (obj) {
                webView = obj
                engineReady = true
                obj.loadingChanged.connect(function(req) {
                    root.loading = obj.loading
                    root.canGoBack = obj.canGoBack
                    root.canGoForward = obj.canGoForward
                    if (obj.url)
                        root.currentUrl = String(obj.url)
                })
                obj.loadProgressChanged.connect(function() {
                    root.loadProgress = obj.loadProgress
                })
                obj.urlChanged.connect(function() {
                    root.currentUrl = String(obj.url)
                    root.canGoBack = obj.canGoBack
                    root.canGoForward = obj.canGoForward
                })
                if (obj.newWindowRequested !== undefined) {
                    obj.newWindowRequested.connect(function(req) {
                        if (req && req.requestedUrl)
                            obj.url = req.requestedUrl
                    })
                }
                syncUrl()
            } else {
                engineFailed = true
            }
        } catch (e) {
            engineFailed = true
        }
    }

    Component.onDestruction: {
        if (webView) {
            webView.destroy()
            webView = null
        }
    }

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        // Slim browser bar
        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: 38
            color: Theme.bg2

            RowLayout {
                anchors.fill: parent
                anchors.leftMargin: 10
                anchors.rightMargin: 10
                spacing: 4

                IconButton {
                    visible: root.engineReady
                    icon: "chevron-left"
                    enabled: root.canGoBack
                    opacity: enabled ? 1 : 0.35
                    onClicked: if (root.webView) root.webView.goBack()
                    ToolTip.visible: hovered && enabled; ToolTip.text: qsTr("Back"); ToolTip.delay: 500
                }
                IconButton {
                    visible: root.engineReady
                    icon: "chevron-right"
                    enabled: root.canGoForward
                    opacity: enabled ? 1 : 0.35
                    onClicked: if (root.webView) root.webView.goForward()
                    ToolTip.visible: hovered && enabled; ToolTip.text: qsTr("Forward"); ToolTip.delay: 500
                }

                // Domain + URL pill
                Rectangle {
                    Layout.fillWidth: true
                    Layout.preferredHeight: 26
                    radius: Theme.radiusSm
                    color: Theme.panel
                    border.width: 1
                    border.color: Theme.line

                    RowLayout {
                        anchors.fill: parent
                        anchors.leftMargin: 8
                        anchors.rightMargin: 8
                        spacing: 6

                        Item {
                            Layout.preferredWidth: 14
                            Layout.preferredHeight: 14
                            Image {
                                id: favImg
                                anchors.fill: parent
                                visible: root.faviconUrl.length > 0 && status === Image.Ready
                                source: root.faviconUrl
                                sourceSize: Qt.size(28, 28)
                                fillMode: Image.PreserveAspectFit
                                asynchronous: true
                                cache: true
                            }
                            TintedIcon {
                                anchors.centerIn: parent
                                visible: !favImg.visible
                                name: "world"
                                size: 13
                                color: Theme.muted
                            }
                        }

                        Text {
                            Layout.fillWidth: true
                            text: root.currentUrl || root.url
                            color: Theme.dim
                            font.family: Theme.body
                            font.pixelSize: 12
                            elide: Text.ElideMiddle
                        }
                    }
                }

                IconButton {
                    visible: root.engineReady
                    icon: root.loading ? "x" : "arrow-forward"
                    onClicked: {
                        if (!root.webView) return
                        if (root.loading) root.webView.stop()
                        else root.webView.reload()
                    }
                    ToolTip.visible: hovered; ToolTip.text: root.loading ? qsTr("Stop") : qsTr("Reload"); ToolTip.delay: 500
                }

                IconButton {
                    icon: "external-link"
                    onClicked: if (root.currentUrl || root.url) Qt.openUrlExternally(root.currentUrl || root.url)
                    ToolTip.visible: hovered; ToolTip.text: qsTr("Open in browser"); ToolTip.delay: 500
                }
            }

            // Loading indicator line
            Rectangle {
                anchors.left: parent.left
                anchors.bottom: parent.bottom
                height: 2
                width: root.loading ? parent.width * Math.max(0.08, root.loadProgress / 100) : 0
                visible: root.loading
                color: Theme.ice
                Behavior on width { NumberAnimation { duration: 120 * Theme.motion } }
            }
            Rectangle {
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.bottom: parent.bottom
                height: 1
                color: Theme.line
            }
        }

        // WebEngine container + graceful fallback
        Item {
            id: webHost
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true

            Rectangle {
                anchors.fill: parent
                visible: root.engineFailed || (!root.engineReady && !root.url)
                color: Theme.ink

                ColumnLayout {
                    anchors.centerIn: parent
                    width: Math.min(460, parent.width - 48)
                    spacing: 14

                    Rectangle {
                        visible: root.coverAssetId.length > 0 || root.thumbnailUrl.length > 0
                        Layout.fillWidth: true
                        Layout.preferredHeight: 190
                        radius: Theme.radiusLg
                        color: Theme.panel
                        clip: true
                        PrivateImage {
                            inObject: true
                            anchors.fill: parent
                            fillMode: Image.PreserveAspectCrop
                            source: root.coverAssetId.length > 0 && ws.assetLocalPath(root.coverAssetId).length > 0
                                  ? "file://" + ws.assetLocalPath(root.coverAssetId)
                                  : root.thumbnailUrl
                        }
                    }

                    Text {
                        Layout.fillWidth: true
                        text: root.title || root.domain || root.url
                        color: Theme.text
                        font.family: Theme.display
                        font.pixelSize: 20
                        font.weight: Font.DemiBold
                        wrapMode: Text.WordWrap
                    }

                    Text {
                        visible: root.description.length > 0
                        Layout.fillWidth: true
                        text: root.description
                        color: Theme.dim
                        font.family: Theme.body
                        font.pixelSize: 13
                        wrapMode: Text.WordWrap
                        maximumLineCount: 4
                        elide: Text.ElideRight
                    }

                    Button {
                        text: Theme.caps(qsTr("OPEN IN BROWSER"))
                        primary: true
                        onClicked: if (root.url) Qt.openUrlExternally(root.url)
                    }
                }
            }
        }
    }
}
