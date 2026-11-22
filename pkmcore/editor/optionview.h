#pragma once
// Who shares a property value (a topic, a category, a tag): the objects that carry it and the
// other values those objects also carry, most shared first. Feeds the option graph on desktop.
// Pure functions, no store.

#include "model.h"

#include <QHash>

namespace pkm {

struct RelatedValue {
    QString prop;          // property key of the related value ("topics", "tags"…)
    QString value;         // stored value (option id or tag name)
    QStringList objectIds; // objects (of the centre) that also carry it, sorted
};

struct OptionNeighborhood {
    QStringList objectIds;         // alive objects of `typeId` whose `prop` holds `value`, sorted
    QVector<RelatedValue> related; // by object count desc, then prop, then value
};

// The values of `key` on `o`: a select string becomes a one-element list; case is kept.
QStringList propertyValues(const Object &o, const QString &key);

// Matching is case-insensitive. `otherProps` are the keys to look for related values in
// (the value itself is skipped on its own prop). Trashed objects and other types are ignored;
// an empty `typeId` matches every type.
OptionNeighborhood optionNeighborhood(const QHash<QString, Object> &objects, const QString &typeId,
                                      const QString &prop, const QString &value,
                                      const QStringList &otherProps);

} // namespace pkm
