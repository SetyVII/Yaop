#pragma once
// Whole-object operations that are pure functions of an Object (no store, no sync).

#include "model.h"

#include <QHash>
#include <functional>

namespace pkm {

// One canonical object per asset (media), per day (journal) or per name (tag): these types are
// never duplicated and never change type (spec/schema.md rule 1).
bool isFixedType(const QString &typeId);
inline bool canDuplicateType(const QString &typeId) { return !isFixedType(typeId); }

// A copy of `src` as a brand-new local object: new id, new block and group ids, no merge
// stamps, not pinned, alive. Content, properties and references are kept. Returns an object
// with an empty id when the type cannot be duplicated.
Object duplicateObject(const Object &src, const QString &newId, const QString &now,
                       const std::function<QString()> &newBlockId);

// Block references (spec/text.md, product §14): for each block of `objectId`, how many runs in
// alive objects point at it (mention runs with that object_id and block_id).
QHash<QString, int> blockReferenceCounts(const QHash<QString, Object> &objects, const QString &objectId);

// spec/schema.md "Changing an object's type". `toKeys` are the target type's property keys.
struct TypeChange {
    Object object;      // the object with its new type_id (unchanged otherwise)
    QStringList loose;  // property keys the target type does not define, sorted
    QString error;      // "fixed_type", or empty
};
TypeChange changeObjectType(const Object &src, const QString &to, const QStringList &toKeys);

// Moves the blocks `ids` (each with its indent subtree, in document order) from `from` to the end
// of `to`. Indents are shifted so the shallowest moved block lands at level 0. Ids are kept, so
// the move merges as a delete in one object and an insert in the other. `moved` = blocks moved.
struct BlockMove {
    Object from;
    Object to;
    int moved = 0;
};
BlockMove moveBlocksToObject(const Object &from, const Object &to, const QStringList &ids,
                             const std::function<QString()> &newBlockId);

} // namespace pkm
