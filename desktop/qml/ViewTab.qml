import QtQuick
import QtQuick.Controls
import "."

// One tab of a type's named views. Click or Enter opens it; double-click or F2 renames it in
// place; × or Delete moves it to the trash. `fixed` tabs ("All", "+ New view") do neither.
Item {
    id: tab
    property string viewId: ""
    property string title: ""
    property bool current: false
    property bool fixed: false
    property bool muted: false
    property bool editing: false
    property bool focusRing: true
    signal picked()
    signal renamed(string name)
    signal removed()

    implicitWidth: row.implicitWidth + 20
    activeFocusOnTab: true
    Accessible.role: Accessible.PageTab
    Accessible.name: title
    Keys.onReturnPressed: picked()
    Keys.onSpacePressed: picked()
    Keys.onPressed: (event) => {
        if (fixed) return
        if (event.key === Qt.Key_F2) { startEdit(); event.accepted = true }
        else if (event.key === Qt.Key_Delete) { removed(); event.accepted = true }
    }

    function startEdit() {
        if (fixed) return
        editing = true
        nameEdit.text = title
        nameEdit.selectAll()
        nameEdit.forceActiveFocus()
    }
    function commitEdit() {
        if (!editing) return
        editing = false
        const t = nameEdit.text.trim()
        if (t.length > 0 && t !== title) renamed(t)
    }

    Row {
        id: row
        anchors.centerIn: parent
        spacing: 6
        Text {
            visible: !tab.editing
            anchors.verticalCenter: parent.verticalCenter
            text: tab.title
            color: tab.current ? Theme.text : (hov.hovered || tab.activeFocus ? Theme.bodyText : (tab.muted ? Theme.faint : Theme.label))
            font.family: Theme.body
            font.pixelSize: 13
            font.weight: tab.current ? Font.Medium : Font.Normal
            Behavior on color { ColorAnimation { duration: Theme.fast } }
        }
        TextInput {
            id: nameEdit
            visible: tab.editing
            anchors.verticalCenter: parent.verticalCenter
            width: Math.max(80, contentWidth + 4)
            color: Theme.text
            selectionColor: Theme.accentDim
            font.family: Theme.body
            font.pixelSize: 13
            onAccepted: tab.commitEdit()
            onActiveFocusChanged: if (!activeFocus) tab.commitEdit()
            Keys.onEscapePressed: { tab.editing = false; tab.forceActiveFocus() }
        }
        Text {
            visible: !tab.fixed && !tab.editing && (hov.hovered || tab.activeFocus)
            anchors.verticalCenter: parent.verticalCenter
            text: "×"
            color: xHov.hovered ? Theme.danger : Theme.faint
            font.pixelSize: 14
            HoverHandler { id: xHov; cursorShape: Qt.PointingHandCursor }
            TapHandler { onTapped: tab.removed() }
        }
    }
    // underline slides in for the current tab
    Rectangle {
        anchors.bottom: parent.bottom
        anchors.horizontalCenter: parent.horizontalCenter
        height: 2
        width: tab.current ? parent.width - 12 : 0
        color: Theme.accent
        Behavior on width { NumberAnimation { duration: Theme.normal; easing.type: Easing.OutCubic } }
    }
    HoverHandler { id: hov; cursorShape: Qt.PointingHandCursor }
    TapHandler {
        enabled: !tab.editing
        onTapped: tab.picked()
        onDoubleTapped: tab.startEdit()
    }
    ToolTip.visible: hov.hovered && !fixed && !editing
    ToolTip.delay: 900
    ToolTip.text: qsTr("Double-click or F2 to rename · Delete to remove")
}
