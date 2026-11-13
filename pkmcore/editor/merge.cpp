#include "merge.h"

#include "model.h"

#include <QByteArray>
#include <QJsonArray>
#include <QJsonValue>
#include <QSet>

#include <algorithm>
#include <functional>

namespace pkm {
namespace {

constexpr auto kPropBlocks = "blocks";
constexpr auto kPropPrefix = "p:";

// Canonical JSON of a wire value: keys sorted (QJsonObject keeps them sorted),
// numbers through the double pipeline — the same shape Go's canon() produces.
// All comparisons happen on these UTF-8 bytes, never on QString ordering.
QByteArray canon(const QJsonValue &v)
{
    QJsonDocument doc;
    if (v.isObject())
        doc = QJsonDocument(v.toObject());
    else
        doc = QJsonDocument(QJsonArray{ v });
    return doc.toJson(QJsonDocument::Compact);
}

QString stampOf(const QJsonObject &m, const QString &key, const QString &fallback)
{
    const QJsonValue v = m.value(key);
    if (v.isString() && !v.toString().isEmpty())
        return v.toString();
    return fallback;
}

bool pick(const QString &ha, const QString &hb, const QJsonValue &va, const QJsonValue &vb)
{
    const int c = compareHlc(ha, hb);
    if (c != 0)
        return c > 0;
    return canon(va) >= canon(vb);
}

QJsonArray wireBlocks(const QJsonObject &props)
{
    const QJsonValue raw = props.value(QLatin1String(kPropBlocks));
    return raw.isArray() ? raw.toArray() : QJsonArray();
}

bool isGroupObject(const QJsonObject &b)
{
    return b.value(QLatin1String("type")).toString() == QLatin1String("group");
}

// Legacy nested groups (doc/1) flatten to group_id members (doc/3) so the merge
// always works block by block (mirrors merge.go flattenGroups).
QJsonArray flattenGroups(const QJsonArray &list)
{
    bool nested = false;
    for (const QJsonValue &v : list) {
        if (isGroupObject(v.toObject())) {
            nested = true;
            break;
        }
    }
    if (!nested)
        return list;
    QJsonArray out;
    for (const QJsonValue &v : list) {
        QJsonObject b = v.toObject();
        if (!isGroupObject(b)) {
            out.append(b);
            continue;
        }
        const QString gid = b.value(QLatin1String("id")).toString();
        const QJsonArray kids = b.value(QLatin1String("children")).toArray();
        if (kids.isEmpty()) {
            b.remove(QLatin1String("children"));
            b.insert(QStringLiteral("type"), QStringLiteral("paragraph"));
            b.insert(QStringLiteral("group_id"), gid);
            out.append(b);
            continue;
        }
        for (const QJsonValue &k : kids) {
            QJsonObject c = k.toObject();
            c.insert(QStringLiteral("group_id"), gid);
            out.append(c);
        }
    }
    return out;
}

QString blockIdOf(const QJsonObject &b)
{
    return b.value(QLatin1String("id")).toString();
}

QStringList blockIdList(const QJsonArray &blocks)
{
    QStringList ids;
    for (const QJsonValue &b : blocks)
        ids.append(blockIdOf(b.toObject()));
    return ids;
}

QString maxHlc(const QString &a, const QString &b)
{
    return compareHlc(a, b) >= 0 ? a : b;
}

// ----- block anchors (yaop-sync/1) -------------------------------------

struct Anchor {
    QString after;
    QString hlc;
};

bool anchorLess(const Anchor &x, const Anchor &y)
{
    const int c = compareHlc(x.hlc, y.hlc);
    if (c != 0)
        return c < 0;
    return x.after.toUtf8() < y.after.toUtf8();
}

QHash<QString, Anchor> anchorMap(const QJsonObject &wire) // wire-level meta object
{
    QHash<QString, Anchor> out;
    const QJsonObject m = wire.value(QLatin1String("block_anchor")).toObject();
    for (auto it = m.begin(); it != m.end(); ++it) {
        if (!it.value().isObject()) // Go nil pointer -> skip
            continue;
        Anchor a;
        const QJsonObject o = it.value().toObject();
        a.after = o.value(QLatin1String("after")).toString();
        a.hlc = o.value(QLatin1String("hlc")).toString();
        out.insert(it.key(), a);
    }
    return out;
}

// Union of two closed anchor maps: per id the greatest anchoring event wins
// (tie -> canonically greater "after"). The per-key max never depends on how
// states were grouped — that is what makes repeated merges associative.
QHash<QString, Anchor> mergeBlockAnchors(const QHash<QString, Anchor> &a,
                                         const QHash<QString, Anchor> &b)
{
    QHash<QString, Anchor> out = a;
    for (auto it = b.begin(); it != b.end(); ++it) {
        const auto prev = out.constFind(it.key());
        if (prev == out.constEnd() || anchorLess(prev.value(), it.value()))
            out.insert(it.key(), it.value());
    }
    return out;
}

bool anchorComplete(const QHash<QString, Anchor> &anchors, const QSet<QString> &kept)
{
    for (const QString &id : kept)
        if (!anchors.contains(id))
            return false;
    return true;
}

bool anchorEventLess(const QString &a, const QString &b, const QHash<QString, Anchor> &anchors)
{
    const int c = compareHlc(anchors.value(a).hlc, anchors.value(b).hlc);
    if (c != 0)
        return c < 0;
    return a.toUtf8() < b.toUtf8();
}

// RGA forest render of the merged anchor map (spec/merge.md §Merge 4):
// parent = the block each one was anchored after; siblings and roots sorted by
// newest anchoring event first; tombstones keep their node (children of a dead
// anchor surface where the dead one was); cycles re-root at their oldest event.
QStringList renderAnchorOrder(const QHash<QString, Anchor> &anchors, const QSet<QString> &alive)
{
    if (alive.isEmpty())
        return {};
    QSet<QString> nodes = QSet<QString>(anchors.keyBegin(), anchors.keyEnd());
    nodes.unite(alive);
    QHash<QString, QString> parent;
    QHash<QString, QList<QString>> children;
    QStringList roots;
    for (const QString &id : std::as_const(nodes)) {
        const Anchor a = anchors.value(id);
        QString p;
        if (!a.after.isEmpty() && nodes.contains(a.after))
            p = a.after;
        parent.insert(id, p);
        if (p.isEmpty())
            roots.append(id);
        else
            children[p].append(id);
    }
    // Break anchor cycles deterministically (concurrent moves pointing at each
    // other): re-root the smallest-stamped member; tie by canonically smallest
    // id. Cycles are disjoint, but the scan restarts after every break exactly
    // like the reference so the whole step stays deterministic.
    for (;;) {
        QStringList keys = parent.keys();
        std::sort(keys.begin(), keys.end());
        bool broke = false;
        for (const QString &start : keys) {
            if (parent.value(start).isEmpty())
                continue;
            QStringList path;
            QHash<QString, int> idx;
            QString cur = start;
            QStringList cycle;
            while (!parent.value(cur).isEmpty()) {
                const auto seen = idx.constFind(cur);
                if (seen != idx.constEnd()) {
                    cycle = path.mid(seen.value());
                    break;
                }
                idx.insert(cur, path.size());
                path.append(cur);
                cur = parent.value(cur);
            }
            if (cycle.isEmpty())
                continue;
            QString worst = cycle.first();
            for (const QString &id : cycle) {
                if (anchorEventLess(id, worst, anchors))
                    worst = id;
            }
            QList<QString> &siblings = children[parent.value(worst)];
            siblings.removeOne(worst);
            parent.insert(worst, QString());
            roots.append(worst);
            broke = true;
            break;
        }
        if (!broke)
            break;
    }
    const auto byAnchorDesc = [&](const QList<QString> &ids) {
        QList<QString> out = ids;
        std::sort(out.begin(), out.end(), [&](const QString &x, const QString &y) {
            const int c = compareHlc(anchors.value(x).hlc, anchors.value(y).hlc);
            if (c != 0)
                return c > 0; // newest anchoring event first (RGA)
            return x.toUtf8() > y.toUtf8();
        });
        return out;
    };
    QStringList out;
    std::function<void(const QString &)> walk = [&](const QString &id) {
        if (alive.contains(id))
            out.append(id);
        for (const QString &child : byAnchorDesc(children.value(id)))
            walk(child);
    };
    for (const QString &id : byAnchorDesc(roots))
        walk(id);
    return out;
}

// Whole-sequence order of yaop-sync/1 — only for data that has no anchors
// yet: the greater "order" stamp provides the base sequence and blocks only
// the other side knows are inserted after their closest placed predecessor.
QStringList orderLegacy(const QStringList &primary, const QStringList &secondary,
                         const QSet<QString> &kept, const QHash<QString, QString> &keptHlc)
{
    QStringList order;
    QSet<QString> inOrder;
    for (const QString &id : primary) {
        if (kept.contains(id) && !inOrder.contains(id)) {
            order.append(id);
            inOrder.insert(id);
        }
    }
    const QSet<QString> inSecondary(secondary.begin(), secondary.end());
    for (int i = 0; i < secondary.size(); ++i) {
        const QString id = secondary.at(i);
        if (!kept.contains(id) || inOrder.contains(id))
            continue;
        int pos = 0;
        for (int j = i - 1; j >= 0; --j) {
            const int idx = order.indexOf(secondary.at(j));
            if (idx >= 0) {
                pos = idx + 1;
                break;
            }
        }
        // Concurrent inserts at the same anchor: the newer stays closer to it.
        while (pos < order.size() && !inSecondary.contains(order.at(pos))
               && compareHlc(keptHlc.value(order.at(pos)), keptHlc.value(id)) > 0)
            ++pos;
        order.insert(pos, id);
        inOrder.insert(id);
    }
    return order;
}

// "absent" must lose to any present value in tiebreaks.
QJsonValue presence(bool has, const QJsonValue &v)
{
    return has ? QJsonValue(QJsonArray{ v }) : QJsonValue(QJsonValue::Null);
}

} // namespace

bool stampWire(const QJsonObject *prev, const QJsonObject &next, const QJsonObject &prevMeta,
               const QString &now, QJsonObject &metaOut)
{
    QJsonObject field = prevMeta.value(QLatin1String("field_hlc")).toObject();
    QJsonObject blockH = prevMeta.value(QLatin1String("block_hlc")).toObject();
    QJsonObject deleted = prevMeta.value(QLatin1String("deleted_blocks")).toObject();
    bool changed = false;
    auto mark = [&](QJsonObject &m, const QString &k) {
        m.insert(k, now);
        changed = true;
    };

    // Block anchors (yaop-sync/1, spec/merge.md §Stamp 5): every block whose
    // predecessor changed (or that is new) re-anchors to the block it now
    // follows; dead blocks keep their entries (tombstone ghosts pin followers).
    QHash<QString, Anchor> anchor = anchorMap(prevMeta);
    const QJsonArray prevBlocks =
        prev ? flattenGroups(wireBlocks(prev->value(QLatin1String("properties")).toObject()))
             : QJsonArray();
    const QJsonArray nextBlocks = flattenGroups(wireBlocks(next.value(QLatin1String("properties")).toObject()));
    QHash<QString, QString> prevPred;
    for (int i = 0; i < prevBlocks.size(); ++i) {
        prevPred.insert(blockIdOf(prevBlocks.at(i).toObject()),
                        i == 0 ? QString() : blockIdOf(prevBlocks.at(i - 1).toObject()));
    }
    for (int i = 0; i < nextBlocks.size(); ++i) {
        const QString id = blockIdOf(nextBlocks.at(i).toObject());
        const QString pred = i > 0 ? blockIdOf(nextBlocks.at(i - 1).toObject()) : QString();
        if (!prevPred.contains(id) || prevPred.value(id) != pred)
            anchor.insert(id, Anchor{ pred, now });
    }

    // 1. title (together with title_runs) / type_id
    const QJsonArray nextTitlePair{ next.value(QStringLiteral("title")),
                                    next.value(QStringLiteral("title_runs")) };
    if (!prev
        || canon(QJsonArray{ prev->value(QStringLiteral("title")),
                             prev->value(QStringLiteral("title_runs")) })
               != canon(nextTitlePair)) {
        mark(field, QStringLiteral("title"));
    }
    if (!prev
        || canon(prev->value(QStringLiteral("type_id"))) != canon(next.value(QStringLiteral("type_id")))) {
        mark(field, QStringLiteral("type_id"));
    }
    // trashed_at (object tombstone): trashing or restoring stamps it (spec/merge.md §Stamp 2).
    {
        const QString pt = prev ? prev->value(QLatin1String("trashed_at")).toString() : QString();
        if (pt != next.value(QLatin1String("trashed_at")).toString())
            mark(field, QStringLiteral("trashed_at"));
    }

    // 2. properties (everything except blocks)
    const QJsonObject pp = prev ? prev->value(QLatin1String("properties")).toObject() : QJsonObject();
    const QJsonObject np = next.value(QLatin1String("properties")).toObject();
    QSet<QString> keys;
    for (auto it = pp.begin(); it != pp.end(); ++it)
        keys.insert(it.key());
    for (auto it = np.begin(); it != np.end(); ++it)
        keys.insert(it.key());
    keys.remove(QLatin1String(kPropBlocks));
    for (const QString &k : std::as_const(keys)) {
        if (pp.contains(k) != np.contains(k) || canon(pp.value(k)) != canon(np.value(k)))
            mark(field, QLatin1String(kPropPrefix) + k);
    }

    // 3. blocks: new or changed -> stamp (and resurrect); missing -> tombstone.
    QHash<QString, QJsonObject> prevById;
    for (const QJsonValue &v : std::as_const(prevBlocks))
        prevById.insert(blockIdOf(v.toObject()), v.toObject());
    QSet<QString> nextIds;
    for (const QJsonValue &v : std::as_const(nextBlocks)) {
        const QJsonObject b = v.toObject();
        const QString id = blockIdOf(b);
        nextIds.insert(id);
        const auto old = prevById.constFind(id);
        if (old == prevById.constEnd() || canon(old.value()) != canon(b)) {
            mark(blockH, id);
            deleted.remove(id);
        }
    }
    for (const QJsonValue &v : std::as_const(prevBlocks)) {
        const QString id = blockIdOf(v.toObject());
        if (!nextIds.contains(id)) {
            mark(deleted, id);
            blockH.remove(id);
        }
    }
    // 6. order
    if (canon(QJsonArray::fromStringList(blockIdList(prevBlocks)))
            != canon(QJsonArray::fromStringList(blockIdList(nextBlocks)))
        && (prev || !nextBlocks.isEmpty())) {
        mark(field, QStringLiteral("order"));
    }

    metaOut = QJsonObject();
    metaOut.insert(QStringLiteral("field_hlc"), field);
    metaOut.insert(QStringLiteral("block_hlc"), blockH);
    metaOut.insert(QStringLiteral("deleted_blocks"), deleted);
    QJsonObject anchors;
    for (auto it = anchor.begin(); it != anchor.end(); ++it)
        anchors.insert(it.key(),
                       QJsonObject{ { "after", it.value().after }, { "hlc", it.value().hlc } });
    if (!anchors.isEmpty())
        metaOut.insert(QStringLiteral("block_anchor"), anchors);
    return changed;
}

QJsonObject mergeWire(const QJsonObject &a, const QJsonObject &b)
{
    QJsonObject out = a;
    const QString aHlc = a.value(QLatin1String("hlc")).toString();
    const QString bHlc = b.value(QLatin1String("hlc")).toString();
    const QJsonObject fa = a.value(QLatin1String("field_hlc")).toObject();
    const QJsonObject fb = b.value(QLatin1String("field_hlc")).toObject();

    // 1. title / type_id: the greatest stamp wins; equal stamps -> canonically
    // greater value (merge.md). title and title_runs travel as one field.
    const QJsonArray titlePairA{ a.value(QStringLiteral("title")), a.value(QStringLiteral("title_runs")) };
    const QJsonArray titlePairB{ b.value(QStringLiteral("title")), b.value(QStringLiteral("title_runs")) };
    if (!pick(stampOf(fa, QStringLiteral("title"), aHlc), stampOf(fb, QStringLiteral("title"), bHlc),
              titlePairA, titlePairB)) {
        out.insert(QStringLiteral("title"), b.value(QStringLiteral("title")));
        if (b.contains(QLatin1String("title_runs")))
            out.insert(QStringLiteral("title_runs"), b.value(QStringLiteral("title_runs")));
        else
            out.remove(QStringLiteral("title_runs"));
    }
    if (!pick(stampOf(fa, QStringLiteral("type_id"), aHlc), stampOf(fb, QStringLiteral("type_id"), bHlc),
              a.value(QStringLiteral("type_id")), b.value(QStringLiteral("type_id"))))
        out.insert(QStringLiteral("type_id"), b.value(QStringLiteral("type_id")));

    // 2. properties: every key known by value or stamp on either side; a side
    // that never knew the key does not participate.
    const QJsonObject pa = a.value(QLatin1String("properties")).toObject();
    const QJsonObject pb = b.value(QLatin1String("properties")).toObject();
    QSet<QString> keys;
    for (auto it = pa.begin(); it != pa.end(); ++it)
        keys.insert(it.key());
    for (auto it = pb.begin(); it != pb.end(); ++it)
        keys.insert(it.key());
    for (auto it = fa.begin(); it != fa.end(); ++it)
        if (it.key().startsWith(QLatin1String(kPropPrefix)))
            keys.insert(it.key().mid(QString::fromLatin1(kPropPrefix).size()));
    for (auto it = fb.begin(); it != fb.end(); ++it)
        if (it.key().startsWith(QLatin1String(kPropPrefix)))
            keys.insert(it.key().mid(QString::fromLatin1(kPropPrefix).size()));
    keys.remove(QLatin1String(kPropBlocks));
    QJsonObject props;
    for (const QString &k : std::as_const(keys)) {
        const bool inA = pa.contains(k);
        const bool inB = pb.contains(k);
        const QJsonValue va = pa.value(k);
        const QJsonValue vb = pb.value(k);
        const QString sa = stampOf(fa, QLatin1String(kPropPrefix) + k, aHlc);
        const QString sb = stampOf(fb, QLatin1String(kPropPrefix) + k, bHlc);
        const bool aKnows = inA || fa.contains(QLatin1String(kPropPrefix) + k);
        const bool bKnows = inB || fb.contains(QLatin1String(kPropPrefix) + k);
        bool winnerHas = false;
        QJsonValue winnerVal;
        if (aKnows && !bKnows) {
            winnerHas = inA;
            winnerVal = va;
        } else if (bKnows && !aKnows) {
            winnerHas = inB;
            winnerVal = vb;
        } else if (pick(sa, sb, presence(inA, va), presence(inB, vb))) {
            winnerHas = inA;
            winnerVal = va;
        } else {
            winnerHas = inB;
            winnerVal = vb;
        }
        if (winnerHas)
            props.insert(k, winnerVal);
    }

    // 2b. trashed_at: LWW by field_hlc.trashed_at with the property participation
    // rule: a later restore beats an earlier trash, and a side that never saw the
    // trash (no value, no stamp) does not resurrect the object (spec/merge.md).
    {
        const QString key = QStringLiteral("trashed_at");
        const QString va = a.value(key).toString();
        const QString vb = b.value(key).toString();
        const bool hasA = !va.isEmpty(), hasB = !vb.isEmpty();
        const bool aStamped = fa.contains(key), bStamped = fb.contains(key);
        const bool aKnows = hasA || aStamped, bKnows = hasB || bStamped;
        QString winner;
        if (aKnows && !bKnows)
            winner = va;
        else if (bKnows && !aKnows)
            winner = vb;
        else if (aKnows && bKnows)
            winner = pick(stampOf(fa, key, aHlc), stampOf(fb, key, bHlc),
                          presence(hasA, va), presence(hasB, vb)) ? va : vb;
        if (!winner.isEmpty())
            out.insert(key, winner);
        else
            out.remove(key);
    }

    // 3. blocks: per id the candidate of the side with the greater block_hlc
    // (tie -> canonically greater JSON); tombstone >= candidate kills the block
    // (a later edit can resurrect it).
    const QJsonArray blocksA = flattenGroups(wireBlocks(pa));
    const QJsonArray blocksB = flattenGroups(wireBlocks(pb));
    const QJsonObject bha = a.value(QLatin1String("block_hlc")).toObject();
    const QJsonObject bhb = b.value(QLatin1String("block_hlc")).toObject();
    const QJsonObject da = a.value(QLatin1String("deleted_blocks")).toObject();
    const QJsonObject db = b.value(QLatin1String("deleted_blocks")).toObject();
    QHash<QString, QString> tomb;
    for (const QJsonObject *m : { &da, &db })
        for (auto it = m->begin(); it != m->end(); ++it)
            tomb[it.key()] = maxHlc(tomb.value(it.key()), it.value().toString());
    QHash<QString, QJsonObject> byA2, byB2;
    for (const QJsonValue &v : blocksA)
        byA2.insert(blockIdOf(v.toObject()), v.toObject());
    for (const QJsonValue &v : blocksB)
        byB2.insert(blockIdOf(v.toObject()), v.toObject());
    QSet<QString> unionIds;
    for (auto it = byA2.begin(); it != byA2.end(); ++it)
        unionIds.insert(it.key());
    for (auto it = byB2.begin(); it != byB2.end(); ++it)
        unionIds.insert(it.key());
    QHash<QString, QJsonObject> kept;
    QHash<QString, QString> keptHlc;
    for (const QString &id : std::as_const(unionIds)) {
        QJsonObject cand;
        QString ch;
        const bool inA = byA2.contains(id);
        const bool inB = byB2.contains(id);
        if (inA && !inB) {
            cand = byA2.value(id);
            ch = stampOf(bha, id, aHlc);
        } else if (inB && !inA) {
            cand = byB2.value(id);
            ch = stampOf(bhb, id, bHlc);
        } else {
            const QString ha = stampOf(bha, id, aHlc);
            const QString hb = stampOf(bhb, id, bHlc);
            if (pick(ha, hb, byA2.value(id), byB2.value(id))) {
                cand = byA2.value(id);
                ch = ha;
            } else {
                cand = byB2.value(id);
                ch = hb;
            }
        }
        const auto t = tomb.constFind(id);
        if (t != tomb.constEnd() && compareHlc(t.value(), ch) >= 0)
            continue; // dead
        kept.insert(id, cand);
        keptHlc.insert(id, ch);
    }

    // 4. order: anchor-complete states render from the merged anchor maps
    // (associative); anything older falls back to the whole-sequence rule.
    const QHash<QString, Anchor> anchors =
        mergeBlockAnchors(anchorMap(a), anchorMap(b));
    QStringList order;
    if (anchorComplete(anchors, QSet<QString>(kept.keyBegin(), kept.keyEnd()))) {
        order = renderAnchorOrder(anchors, QSet<QString>(kept.keyBegin(), kept.keyEnd()));
    } else {
        const bool primaryIsA = pick(stampOf(fa, QStringLiteral("order"), aHlc),
                                     stampOf(fb, QStringLiteral("order"), bHlc),
                                     QJsonArray::fromStringList(blockIdList(blocksA)),
                                     QJsonArray::fromStringList(blockIdList(blocksB)));
        const QStringList primary = primaryIsA ? blockIdList(blocksA) : blockIdList(blocksB);
        const QStringList secondary = primaryIsA ? blockIdList(blocksB) : blockIdList(blocksA);
        order = orderLegacy(primary, secondary,
                            QSet<QString>(kept.keyBegin(), kept.keyEnd()), keptHlc);
    }
    QJsonArray mergedBlocks;
    for (const QString &id : order)
        mergedBlocks.append(kept.value(id));
    const bool hadBlocksKey = pa.contains(QLatin1String(kPropBlocks))
                               || pb.contains(QLatin1String(kPropBlocks));
    if (!blocksA.isEmpty() || !blocksB.isEmpty() || hadBlocksKey)
        props.insert(QLatin1String(kPropBlocks), mergedBlocks);
    out.insert(QStringLiteral("properties"), props);

    // 5. meta result
    QJsonObject fieldOut;
    {
        QSet<QString> fieldKeys;
        for (auto it = fa.begin(); it != fa.end(); ++it)
            fieldKeys.insert(it.key());
        for (auto it = fb.begin(); it != fb.end(); ++it)
            fieldKeys.insert(it.key());
        for (const QString &k : std::as_const(fieldKeys))
            fieldOut.insert(k, maxHlc(fa.value(k).toString(), fb.value(k).toString()));
    }
    QJsonObject blockHlcOut;
    for (auto it = keptHlc.begin(); it != keptHlc.end(); ++it)
        blockHlcOut.insert(it.key(), it.value());
    QJsonObject deletedOut;
    for (auto it = tomb.begin(); it != tomb.end(); ++it)
        if (!kept.contains(it.key()))
            deletedOut.insert(it.key(), it.value());
    if (!fieldOut.isEmpty())
        out.insert(QStringLiteral("field_hlc"), fieldOut);
    else
        out.remove(QStringLiteral("field_hlc"));
    if (!blockHlcOut.isEmpty())
        out.insert(QStringLiteral("block_hlc"), blockHlcOut);
    else
        out.remove(QStringLiteral("block_hlc"));
    if (!deletedOut.isEmpty())
        out.insert(QStringLiteral("deleted_blocks"), deletedOut);
    else
        out.remove(QStringLiteral("deleted_blocks"));
    QJsonObject anchorsOut;
    for (auto it = anchors.begin(); it != anchors.end(); ++it) {
        anchorsOut.insert(it.key(),
                          QJsonObject{ { "after", it.value().after }, { "hlc", it.value().hlc } });
    }
    if (!anchorsOut.isEmpty())
        out.insert(QStringLiteral("block_anchor"), anchorsOut);
    else
        out.remove(QStringLiteral("block_anchor"));

    // 6. rest: hlc max, created_at min, content/updated_at from the primary
    // side (presentation passthrough, not part of the convergence guarantee),
    // schema_version by max (spec/object.md).
    out.insert(QStringLiteral("hlc"), maxHlc(aHlc, bHlc));
    QString createdAt;
    {
        const QString ca = a.value(QStringLiteral("created_at")).toString();
        const QString cb = b.value(QStringLiteral("created_at")).toString();
        if (ca.isEmpty())
            createdAt = cb;
        else if (cb.isEmpty())
            createdAt = ca;
        else
            createdAt = ca < cb ? ca : cb;
    }
    out.insert(QStringLiteral("created_at"), createdAt);
    // primary side (same winner as the order, spec/merge.md §Merge 6)
    const bool primaryIsA2 = pick(stampOf(fa, QStringLiteral("order"), aHlc),
                                  stampOf(fb, QStringLiteral("order"), bHlc),
                                  QJsonArray::fromStringList(blockIdList(blocksA)),
                                  QJsonArray::fromStringList(blockIdList(blocksB)));
    const QJsonObject &prim = primaryIsA2 ? a : b;
    if (prim.contains(QLatin1String("content")))
        out.insert(QStringLiteral("content"), prim.value(QStringLiteral("content")));
    else
        out.remove(QStringLiteral("content"));
    if (prim.contains(QLatin1String("updated_at")))
        out.insert(QStringLiteral("updated_at"), prim.value(QStringLiteral("updated_at")));
    else
        out.remove(QStringLiteral("updated_at"));
    const int schemaMax = std::max(a.value(QLatin1String("schema_version")).toInt(0),
                                   b.value(QLatin1String("schema_version")).toInt(0));
    if (schemaMax > 0)
        out.insert(QStringLiteral("schema_version"), schemaMax);
    else
        out.remove(QStringLiteral("schema_version"));

    // References are derived from the merged content, never unioned.
    out.insert(QStringLiteral("links"), QJsonArray::fromStringList(deriveLinks(out)));
    QJsonObject mergedProps = props;
    QStringList propLinks;
    for (const QJsonValue &v : a.value(QLatin1String("property_links")).toArray())
        propLinks.append(v.toString());
    for (const QJsonValue &v : b.value(QLatin1String("property_links")).toArray())
        propLinks.append(v.toString());
    QSet<QString> propSet;
    for (const QString &s : propLinks)
        propSet.insert(s);
    // filterPresent: keep only ids still present in the merged properties.
    QSet<QString> present;
    for (auto it = mergedProps.begin(); it != mergedProps.end(); ++it) {
        if (it.key() == QLatin1String(kPropBlocks))
            continue;
        const QJsonValue v = it.value();
        if (v.isString()) {
            const QString s = v.toString();
            present.insert(s);
            present.insert(tagKey(s));
            if (s.size() >= 10 && s.at(4) == QLatin1Char('-') && s.at(7) == QLatin1Char('-'))
                present.insert(QStringLiteral("daily-") + s.left(10));
        } else if (v.isArray()) {
            for (const QJsonValue &e : v.toArray()) {
                if (e.isString()) {
                    const QString s = e.toString();
                    present.insert(s);
                    present.insert(tagKey(s));
                    if (s.size() >= 10 && s.at(4) == QLatin1Char('-') && s.at(7) == QLatin1Char('-'))
                        present.insert(QStringLiteral("daily-") + s.left(10));
                }
            }
        }
    }
    QStringList filtered;
    for (const QString &id : std::as_const(propSet)) {
        const QString trimmed = id.startsWith(QLatin1String("tag_"))
            ? QString(id).mid(4) : id;
        if (present.contains(id) || present.contains(trimmed))
            filtered.append(id);
    }
    std::sort(filtered.begin(), filtered.end());
    out.insert(QStringLiteral("property_links"), QJsonArray::fromStringList(filtered));
    return out;
}

} // namespace pkm
