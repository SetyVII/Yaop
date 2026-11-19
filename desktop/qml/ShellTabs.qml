import QtQuick
import QtQuick.Controls
import "."

// Browser-like tab strip. Tabs share one width (they shrink as more open). Two pills sit behind
// them: the active one and the hover one, each sliding between tabs with the same easing, so
// switching or moving the pointer along the strip reads as one continuous motion.
Item {
    id: strip

    property var tabs: []          // [{key, icon, title}]
    property int current: 0
    signal tabClicked(int index)
    signal tabCloseRequested(int index)
    signal newTabRequested()

    readonly property int gap: 4
    readonly property int tabH: 34
    readonly property int plusW: 32
    readonly property real tabW: Math.max(44, Math.min(220, (width - plusW - gap) / Math.max(1, tabs.length) - gap))
    function slotX(i) { return i * (tabW + gap) }

    property int hoverIndex: -1
    readonly property int slide: 240
    readonly property int easing: Easing.OutCubic

    clip: true
    implicitHeight: tabH

    // Mirror of `tabs` keyed by tab: a tab keeps its delegate while others come and go, so its
    // move is animated instead of the whole strip being rebuilt.
    ListModel { id: tabModel }
    function sync() {
        const keys = tabs.map(t => t.key)
        for (let i = tabModel.count - 1; i >= 0; i--)
            if (keys.indexOf(tabModel.get(i).key) < 0) tabModel.remove(i)
        for (let i = 0; i < tabs.length; i++) {
            const t = tabs[i]
            if (i < tabModel.count && tabModel.get(i).key === t.key) {
                tabModel.setProperty(i, "title", t.title)
                tabModel.setProperty(i, "icon", t.icon)
            } else {
                tabModel.insert(i, { key: t.key, title: t.title, icon: t.icon })
            }
        }
    }
    onTabsChanged: sync()
    Component.onCompleted: sync()

    // hover pill: follows the pointer; fades in place, slides while visible
    Rectangle {
        id: hoverPill
        property int at: 0
        y: (strip.height - height) / 2
        x: strip.slotX(at)
        width: strip.tabW
        height: strip.tabH
        radius: Theme.radius
        color: Qt.rgba(1, 1, 1, 0.05)
        opacity: strip.hoverIndex >= 0 ? 1 : 0
        Behavior on x { enabled: hoverPill.opacity > 0; NumberAnimation { duration: strip.slide; easing.type: strip.easing } }
        Behavior on width { NumberAnimation { duration: strip.slide; easing.type: strip.easing } }
        Behavior on opacity { NumberAnimation { duration: 140 * Theme.motion } }
        Connections {
            target: strip
            function onHoverIndexChanged() { if (strip.hoverIndex >= 0) hoverPill.at = strip.hoverIndex }
        }
    }

    // active pill: raised like the sidebar's open row
    Rectangle {
        id: activePill
        y: (strip.height - height) / 2
        x: strip.slotX(strip.current)
        width: strip.tabW
        height: strip.tabH
        radius: Theme.radius
        color: Theme.panel2
        border.width: 1
        border.color: Theme.line2
        Behavior on x { NumberAnimation { duration: strip.slide; easing.type: strip.easing } }
        Behavior on width { NumberAnimation { duration: strip.slide; easing.type: strip.easing } }
    }

    Repeater {
        model: tabModel
        delegate: Item {
            id: tab
            required property int index
            required property string title
            required property string icon
            readonly property bool active: index === strip.current
            readonly property bool roomy: strip.tabW >= 96
            x: strip.slotX(index)
            y: (strip.height - height) / 2
            width: strip.tabW
            height: strip.tabH
            opacity: 0
            Component.onCompleted: opacity = 1
            Behavior on x { NumberAnimation { duration: strip.slide; easing.type: strip.easing } }
            Behavior on width { NumberAnimation { duration: strip.slide; easing.type: strip.easing } }
            Behavior on opacity { NumberAnimation { duration: 180 * Theme.motion } }

            TintedIcon {
                id: tabIcon
                x: tab.roomy ? 10 : (tab.width - width) / 2
                anchors.verticalCenter: parent.verticalCenter
                name: tab.icon
                size: 15
                color: tab.active ? Theme.text : Theme.muted
            }
            Text {
                visible: tab.roomy
                anchors.left: tabIcon.right
                anchors.leftMargin: 8
                anchors.right: closeBtn.visible ? closeBtn.left : parent.right
                anchors.rightMargin: 6
                anchors.verticalCenter: parent.verticalCenter
                text: tab.title
                color: tab.active ? Theme.text : Theme.dim
                font.family: Theme.body
                font.pixelSize: 13
                font.weight: tab.active ? Font.Medium : Font.Normal
                elide: Text.ElideRight
                Behavior on color { ColorAnimation { duration: strip.slide } }
            }
            Rectangle {
                id: closeBtn
                visible: strip.tabs.length > 1 && tab.roomy && (tab.active || tabHover.hovered)
                anchors.right: parent.right
                anchors.rightMargin: 6
                anchors.verticalCenter: parent.verticalCenter
                width: 22
                height: 22
                radius: Theme.radiusSm
                color: closeHover.hovered ? Theme.panel3 : "transparent"
                TintedIcon { anchors.centerIn: parent; name: "x"; size: 13; color: closeHover.hovered ? Theme.text : Theme.muted }
                HoverHandler { id: closeHover; cursorShape: Qt.PointingHandCursor }
                TapHandler { onTapped: strip.tabCloseRequested(tab.index) }
            }

            HoverHandler {
                id: tabHover
                onHoveredChanged: {
                    if (hovered) strip.hoverIndex = tab.index
                    else if (strip.hoverIndex === tab.index) strip.hoverIndex = -1
                }
            }
            TapHandler { onTapped: strip.tabClicked(tab.index) }
            TapHandler { acceptedButtons: Qt.MiddleButton; onTapped: strip.tabCloseRequested(tab.index) }
            ToolTip.visible: tabHover.hovered && !tab.roomy
            ToolTip.text: tab.title
            ToolTip.delay: 500
        }
    }

    IconButton {
        x: strip.slotX(strip.tabs.length)
        anchors.verticalCenter: parent.verticalCenter
        implicitWidth: strip.plusW
        implicitHeight: strip.plusW
        icon: "plus"
        onClicked: strip.newTabRequested()
        Behavior on x { NumberAnimation { duration: strip.slide; easing.type: strip.easing } }
        ToolTip.visible: hovered; ToolTip.text: qsTr("New tab (Ctrl+T)"); ToolTip.delay: 500
    }
}
