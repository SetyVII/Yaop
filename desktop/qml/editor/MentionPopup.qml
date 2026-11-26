import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../"

// Mention / tag / date picker ("@", "#" triggers). Like the slash menu it never takes keyboard
// focus: the editor keeps the caret and drives it through move()/accept(). Object rows show a
// thumbnail when the referenced object is an image (or has a cover), so references are recognisable.
// Object mode lists matching types first (picking one writes `@type-slug/` and narrows the list to
// that type), then up to 10 objects, then "+ Create "x" in <Type>" (first when nothing matches).
// Text that reads as a date (`@today`, `@mañana 9`, `@now`, `@friday`, `@+3`, `@15:30`, `@1/12`)
// adds date rows (pkmcore datequery): a whole word or a number first, a word cut short (`@ma`)
// after the objects.

Popup {
    id: mp

    property string mode: "object"   // "object" | "tag" | "date"
    property string query: ""
    property var workspace
    property real maxListHeight: 360
    property int currentIndex: 0
    signal pickedObject(string objectId, string label)
    signal pickedType(string slug)
    signal pickedCreateObject(string typeId, string title)
    signal pickedTag(string tagName)
    signal pickedDate(string isoDate)
    signal closeRequested()

    readonly property real headerHeight: 34
    readonly property real rowHeight: mode === "object" ? 46 : 34

    readonly property var entries: {
        if (mode === "object") {
            if (!workspace)
                return []
            const found = workspace.mentionSearch(query, 10)
            const dates = workspace.dateMentions(query)
            if (dates.length === 0)
                return found
            const exact = dates.filter(d => d.exact), loose = dates.filter(d => !d.exact)
            const types = found.filter(e => e.kind === "type")
            const objects = found.filter(e => e.kind !== "type" && !e.createNew)
            const create = found.filter(e => e.createNew)
            return types.concat(exact, objects, loose, create)
        }
        if (mode === "tag") {
            if (workspace)
                return workspace.tagSuggestions(query, 16)
            const q = query.trim().toLowerCase()
            const names = q.length > 0 ? [q] : [qsTr("work"), qsTr("study"), qsTr("ideas"), qsTr("personal"), qsTr("reading")]
            return names.map(n => ({ name: n, createNew: q.length > 0 }))
        }
        return [ { label: qsTr("Today"), offset: 0 }, { label: qsTr("Tomorrow"), offset: 1 },
                 { label: qsTr("Yesterday"), offset: -1 } ]
    }

    readonly property real naturalHeight: headerHeight + Math.max(1, entries.length) * rowHeight + 2

    onEntriesChanged: currentIndex = 0

    function move(delta) {
        if (entries.length === 0)
            return
        currentIndex = (currentIndex + delta + entries.length) % entries.length
        list.positionViewAtIndex(currentIndex, ListView.Contain)
    }

    function accept() {
        if (entries.length === 0 || currentIndex < 0 || currentIndex >= entries.length)
            return false
        activate(entries[currentIndex])
        return true
    }

    // "Tomorrow", "Friday", "In 3 days"… and, under it, the day it lands on.
    function dateLabel(e) {
        const d = new Date(Number(e.iso.substr(0, 4)), Number(e.iso.substr(5, 2)) - 1, Number(e.iso.substr(8, 2)))
        switch (e.key) {
        case "now": return qsTr("Now")
        case "today": return e.hasTime ? qsTr("Today at %1").arg(e.iso.substr(11, 5)) : qsTr("Today")
        case "tomorrow": return e.hasTime ? qsTr("Tomorrow at %1").arg(e.iso.substr(11, 5)) : qsTr("Tomorrow")
        case "yesterday": return qsTr("Yesterday")
        case "weekday": {
            const day = Qt.locale().dayName(d.getDay(), Locale.LongFormat)
            const name = day.charAt(0).toUpperCase() + day.slice(1)
            return e.hasTime ? qsTr("%1 at %2").arg(name).arg(e.iso.substr(11, 5)) : name
        }
        case "next-week": return qsTr("Next week")
        case "offset": return e.dayOffset >= 0 ? qsTr("In %n day(s)", "", e.dayOffset) : qsTr("%n day(s) ago", "", -e.dayOffset)
        case "time": return qsTr("Today at %1").arg(e.iso.substr(11, 5))
        default: return e.hasTime ? qsTr("%1 at %2").arg(Qt.formatDate(d, Qt.locale(), Locale.ShortFormat)).arg(e.iso.substr(11, 5))
                                  : Qt.formatDate(d, Qt.locale(), Locale.ShortFormat)
        }
    }
    function dateDetail(e) {
        const d = new Date(Number(e.iso.substr(0, 4)), Number(e.iso.substr(5, 2)) - 1, Number(e.iso.substr(8, 2)))
        const day = Qt.formatDate(d, Qt.locale(), "ddd d MMM yyyy")
        return Theme.caps(e.hasTime ? day + " · " + e.iso.substr(11, 5) : day)
    }

    function activate(e) {
        if (mode === "object" && e.kind === "date") {
            pickedDate(e.iso)
            closeRequested()
            return
        }
        if (mode === "object") {
            if (e.kind === "type") {
                pickedType(e.slug) // the popup stays open on the narrowed query
                return
            }
            if (e.createNew)
                pickedCreateObject(e.typeId, e.title)
            else
                pickedObject(e.id, e.title)
        } else if (mode === "tag") {
            pickedTag(e.name)
        } else {
            const d = new Date()
            d.setDate(d.getDate() + e.offset)
            pickedDate(Qt.formatDate(d, "yyyy-MM-dd"))
        }
        closeRequested()
    }

    padding: 1
    modal: false
    focus: false
    closePolicy: Popup.NoAutoClose

    background: Rectangle {
        radius: Theme.noir ? 0 : 18
        color: Theme.sheet
        border.color: Theme.line2
        border.width: 1
    }

    contentItem: ColumnLayout {
        spacing: 0

        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: mp.headerHeight
            color: Theme.panel

            Text {
                anchors.left: parent.left
                anchors.leftMargin: 12
                anchors.verticalCenter: parent.verticalCenter
                text: mp.mode === "object" ? (mp.entries.some(e => e.kind === "date") ? Theme.caps(qsTr("REFERENCE OR DATE"))
                                                                                      : Theme.caps(qsTr("REFERENCE AN OBJECT")))
                     : mp.mode === "tag" ? Theme.caps(qsTr("TAG")) : Theme.caps(qsTr("DATE"))
                color: mp.mode === "object" ? Theme.note : mp.mode === "tag" ? Theme.accent : Theme.journal
                font.family: Theme.mono
                font.pixelSize: 11
                font.letterSpacing: Theme.track(1.6)
            }
        }

        ListView {
            id: list
            Layout.fillWidth: true
            Layout.preferredHeight: Math.min(mp.maxListHeight, mp.entries.length * mp.rowHeight)
            spacing: 0
            clip: true
            interactive: contentHeight > height
            boundsBehavior: Flickable.StopAtBounds
            model: mp.entries

            ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }

            delegate: Rectangle {
                id: row
                required property var modelData
                required property int index
                width: list.width
                height: mp.rowHeight
                readonly property bool isCur: index === mp.currentIndex
                readonly property bool isDate: modelData.kind === "date"
                color: isCur ? (Theme.noir ? Theme.accent : Theme.panel3) : "transparent"

                readonly property string assetId: mp.mode === "object"
                    ? (modelData.assetId && modelData.assetId.length > 0 ? modelData.assetId
                       : (modelData.coverAssetId ? modelData.coverAssetId : "")) : ""

                // thumbnail (objects) or colour bar
                Rectangle {
                    id: lead
                    visible: mp.mode === "object"
                    anchors.left: parent.left
                    anchors.leftMargin: 10
                    anchors.verticalCenter: parent.verticalCenter
                    width: 34
                    height: 34
                    color: Theme.panel2
                    border.color: Theme.line
                    clip: true

                    Rectangle {
                        anchors.fill: parent
                        anchors.margins: 1
                        visible: thumb.status !== Image.Ready
                        color: row.isDate ? Theme.journal : Theme.typeColor(row.modelData.typeId !== undefined ? row.modelData.typeId : "")
                        opacity: 0.35
                    }
                    TintedIcon {
                        visible: row.isDate
                        anchors.centerIn: parent
                        name: row.modelData.hasTime ? "clock" : "calendar"
                        size: 16
                        color: Theme.text
                    }
                    PrivateImage {
                        id: thumb
                        anchors.fill: parent
                        anchors.margins: 1
                        asynchronous: true
                        cache: true
                        fillMode: Image.PreserveAspectCrop
                        sourceSize: Qt.size(68, 68)
                        source: row.assetId.length > 0 && mp.workspace
                                ? "file://" + mp.workspace.assetLocalPath(row.assetId) : ""
                    }
                }

                Text {
                    anchors.left: mp.mode === "object" ? lead.right : parent.left
                    anchors.leftMargin: mp.mode === "object" ? 10 : 14
                    anchors.right: parent.right
                    anchors.rightMargin: 12
                    anchors.verticalCenter: parent.verticalCenter
                    anchors.verticalCenterOffset: mp.mode === "object" ? -7 : 0
                    horizontalAlignment: Text.AlignLeft
                    text: mp.mode === "object"
                              ? (row.isDate ? mp.dateLabel(row.modelData)
                                 : row.modelData.createNew
                                 ? qsTr("+ Create \"%1\" in %2").arg(row.modelData.title !== undefined ? row.modelData.title : "")
                                       .arg(row.modelData.typeName !== undefined ? row.modelData.typeName : "")
                                 : row.modelData.kind === "type"
                                 ? "@" + row.modelData.slug + "/"
                                 : (row.modelData.title !== undefined ? row.modelData.title : ""))
                         : mp.mode === "tag"
                              ? (row.modelData.createNew
                                 ? qsTr("+ Create #%1").arg(row.modelData.name)
                                 : "#" + row.modelData.name)
                         : row.modelData.label
                    color: (row.isCur && Theme.noir) ? Theme.accentInk
                         : row.isDate ? Theme.journal
                         : (row.modelData.createNew === true || row.modelData.kind === "type")
                               ? (mp.mode === "tag" ? Theme.accent : Theme.ice)
                               : (mp.mode === "tag" ? Theme.accent : mp.mode === "date" ? Theme.journal : Theme.text)
                    font.family: mp.mode === "tag" ? Theme.mono : Theme.body
                    font.pixelSize: 12
                    elide: Text.ElideRight
                }

                Text {
                    visible: mp.mode === "object"
                    anchors.left: lead.right
                    anchors.leftMargin: 10
                    anchors.right: parent.right
                    anchors.rightMargin: 12
                    anchors.verticalCenter: parent.verticalCenter
                    anchors.verticalCenterOffset: 9
                    horizontalAlignment: Text.AlignLeft
                    text: row.isDate ? mp.dateDetail(row.modelData)
                          : row.modelData.createNew
                          ? Theme.caps(qsTr("NEW %1")).arg(Theme.typeLabel(row.modelData.typeId !== undefined ? row.modelData.typeId : ""))
                          : row.modelData.kind === "type"
                          ? Theme.caps(qsTr("TYPE · %1")).arg(row.modelData.title)
                          : Theme.typeLabel(row.modelData.typeId !== undefined ? row.modelData.typeId : "")
                    color: (row.isCur && Theme.noir) ? Theme.accentInk : Theme.faint
                    font.family: Theme.mono
                    font.pixelSize: 11
                    elide: Text.ElideRight
                }

                MouseArea {
                    anchors.fill: parent
                    hoverEnabled: true
                    onEntered: mp.currentIndex = row.index
                    onClicked: mp.activate(row.modelData)
                }
            }
        }

        Text {
            visible: mp.entries.length === 0
            Layout.fillWidth: true
            Layout.preferredHeight: mp.rowHeight
            verticalAlignment: Text.AlignVCenter
            leftPadding: 12
            text: mp.mode === "object" ? qsTr("No matching object") : ""
            color: Theme.faint
            font.family: Theme.body
            font.pixelSize: 11
        }
    }

    height: headerHeight + (entries.length === 0 ? rowHeight : Math.min(maxListHeight, entries.length * rowHeight)) + 2
}
