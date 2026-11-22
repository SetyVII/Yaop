import QtQuick
import "../"

// Picks any object by title (recent ones when the search is empty).
PickList {
    id: osp
    property var exclude: []
    property string preferType: "" // listed first (a collection's suggested item type)
    signal pickedId(string objectId)
    source: function (f) {
        if (!ws) return []
        let raw = f.length === 0 ? ws.recentObjects(30) : ws.searchObjects(f, 30)
        if (osp.preferType.length > 0)
            raw = raw.filter(o => o.typeId === osp.preferType).concat(raw.filter(o => o.typeId !== osp.preferType))
        return raw.filter(o => osp.exclude.indexOf(o.id) < 0 && o.typeId !== "set")
                  .map(o => ({ id: o.id, label: o.title || qsTr("Untitled"), icon: Theme.typeIcon(o.typeId || ""),
                               color: Theme.typeColor(o.typeId || ""), hint: Theme.typeName(o.typeId || "") }))
    }
    onPicked: (e) => osp.pickedId(e.id)
}
