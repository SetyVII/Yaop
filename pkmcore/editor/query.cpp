#include "query.h"

#include <QCryptographicHash>
#include <QDate>
#include <QHash>
#include <QJsonDocument>
#include <QLocale>
#include <QSet>
#include <algorithm>
#include <cmath>

namespace pkm::query {
namespace {

QString str(const QJsonValue &v, const QString &def)
{
    const QString s = v.isString() ? v.toString() : QString();
    return s.isEmpty() ? def : s;
}

QByteArray canonOf(const QJsonValue &v)
{
    if (v.isObject())
        return QJsonDocument(v.toObject()).toJson(QJsonDocument::Compact);
    return QJsonDocument(QJsonArray{ v }).toJson(QJsonDocument::Compact);
}

bool isEmptyValue(const QJsonValue &v)
{
    if (v.isUndefined() || v.isNull())
        return true;
    if (v.isString())
        return v.toString().isEmpty();
    if (v.isArray())
        return v.toArray().isEmpty();
    if (v.isObject())
        return v.toObject().isEmpty();
    return false;
}

QVector<QJsonValue> asList(const QJsonValue &v)
{
    QVector<QJsonValue> out;
    if (isEmptyValue(v))
        return out;
    if (v.isArray()) {
        for (const QJsonValue &x : v.toArray())
            out << x;
    } else {
        out << v;
    }
    return out;
}

bool jsonEqual(const QJsonValue &a, const QJsonValue &b)
{
    if (a.isDouble())
        return b.isDouble() && a.toDouble() == b.toDouble();
    return canonOf(a) == canonOf(b);
}

QJsonValue fieldValue(const QJsonObject &o, const QString &prop)
{
    const QJsonObject props = o.value(QLatin1String("properties")).toObject();
    if (prop == QLatin1String("@id"))
        return o.value(QLatin1String("id"));
    if (prop == QLatin1String("@type"))
        return o.value(QLatin1String("type_id"));
    if (prop == QLatin1String("@title"))
        return o.value(QLatin1String("title"));
    if (prop == QLatin1String("@created_at"))
        return o.value(QLatin1String("created_at"));
    if (prop == QLatin1String("@updated_at"))
        return o.value(QLatin1String("updated_at"));
    if (prop == QLatin1String("@pinned"))
        return QJsonValue(props.value(QLatin1String("pinned")).toBool(false));
    if (prop == QLatin1String("@refs")) {
        QStringList refs;
        QSet<QString> seen;
        for (const char *k : { "links", "property_links" }) {
            for (const QJsonValue &v : o.value(QLatin1String(k)).toArray()) {
                if (v.isString() && !seen.contains(v.toString())) {
                    seen.insert(v.toString());
                    refs << v.toString();
                }
            }
        }
        std::sort(refs.begin(), refs.end(), [](const QString &a, const QString &b) { return comparePoints(a, b) < 0; });
        return QJsonArray::fromStringList(refs);
    }
    if (prop == QLatin1String("blocks"))
        return QJsonValue(QJsonValue::Undefined);
    return props.value(prop);
}

std::optional<QString> day(const QJsonValue &x)
{
    if (!x.isString())
        return std::nullopt;
    const QString s = x.toString();
    if (s.size() < 10)
        return std::nullopt;
    const QString d = s.left(10);
    if (!QDate::fromString(d, QStringLiteral("yyyy-MM-dd")).isValid())
        return std::nullopt;
    return d;
}

// [s, e] of a date value.
std::optional<QPair<QString, QString>> dateInterval(const QJsonValue &v)
{
    if (v.isObject()) {
        const QJsonObject m = v.toObject();
        const auto s = day(m.value(QLatin1String("start")));
        if (!s)
            return std::nullopt;
        const auto e = day(m.value(QLatin1String("end")));
        return QPair<QString, QString>{ *s, e ? *e : *s };
    }
    const auto s = day(v);
    if (!s)
        return std::nullopt;
    return QPair<QString, QString>{ *s, *s };
}

std::optional<QString> resolveDate(const QJsonValue &v, const QString &today)
{
    if (!v.isString())
        return std::nullopt;
    const QString s = v.toString();
    if (s.startsWith(QLatin1String("today"))) {
        QDate t = QDate::fromString(today, QStringLiteral("yyyy-MM-dd"));
        if (!t.isValid())
            return std::nullopt;
        const QString rest = s.mid(5);
        if (!rest.isEmpty()) {
            bool ok = false;
            const int n = rest.toInt(&ok);
            if (!ok)
                return std::nullopt;
            t = t.addDays(n);
        }
        return t.toString(QStringLiteral("yyyy-MM-dd"));
    }
    const auto iv = dateInterval(v);
    if (!iv)
        return std::nullopt;
    return iv->first;
}

const QSet<QString> &knownOps()
{
    static const QSet<QString> ops{
        "eq", "neq", "contains", "not_contains", "starts_with", "gt", "gte", "lt", "lte",
        "empty", "not_empty", "is_true", "is_false", "has_any", "has_all", "has_none",
        "before", "after", "on", "between",
    };
    return ops;
}

QString validateGroup(const QString &match, const QJsonArray &filters)
{
    if (match != QLatin1String("all") && match != QLatin1String("any"))
        return QStringLiteral("unknown_match");
    for (const QJsonValue &fv : filters) {
        const QJsonObject f = fv.toObject();
        if (f.value(QLatin1String("filters")).isArray()) {
            const QString err = validateGroup(str(f.value(QLatin1String("match")), QStringLiteral("all")),
                                              f.value(QLatin1String("filters")).toArray());
            if (!err.isEmpty())
                return err;
        } else if (!knownOps().contains(str(f.value(QLatin1String("op")), QString()))) {
            return QStringLiteral("unknown_op");
        }
    }
    return {};
}

bool evalCond(const QJsonObject &c, const QJsonObject &o, const QString &today)
{
    const QJsonValue v = fieldValue(o, str(c.value(QLatin1String("prop")), QString()));
    const QJsonValue want = c.value(QLatin1String("value"));
    const QString op = str(c.value(QLatin1String("op")), QString());
    if (op == QLatin1String("eq")) {
        if (v.isArray()) {
            for (const QJsonValue &x : v.toArray())
                if (jsonEqual(x, want))
                    return true;
            return false;
        }
        return !v.isUndefined() && !v.isNull() && jsonEqual(v, want);
    }
    if (op == QLatin1String("neq")) {
        QJsonObject eq = c;
        eq.insert(QStringLiteral("op"), QStringLiteral("eq"));
        return !evalCond(eq, o, today);
    }
    if (op == QLatin1String("contains") || op == QLatin1String("not_contains") || op == QLatin1String("starts_with")) {
        const QString w = lower(want.toString());
        bool hit = false;
        if (v.isString()) {
            const QString s = lower(v.toString());
            hit = op == QLatin1String("starts_with") ? s.startsWith(w) : s.contains(w);
        }
        return op == QLatin1String("not_contains") ? !hit : hit;
    }
    if (op == QLatin1String("gt") || op == QLatin1String("gte") || op == QLatin1String("lt") || op == QLatin1String("lte")) {
        if (!v.isDouble() || !want.isDouble())
            return false;
        const double x = v.toDouble(), y = want.toDouble();
        if (op == QLatin1String("gt"))
            return x > y;
        if (op == QLatin1String("gte"))
            return x >= y;
        if (op == QLatin1String("lt"))
            return x < y;
        return x <= y;
    }
    if (op == QLatin1String("empty"))
        return isEmptyValue(v);
    if (op == QLatin1String("not_empty"))
        return !isEmptyValue(v);
    if (op == QLatin1String("is_true"))
        return v.isBool() && v.toBool();
    if (op == QLatin1String("is_false"))
        return !(v.isBool() && v.toBool());
    if (op == QLatin1String("has_any") || op == QLatin1String("has_all") || op == QLatin1String("has_none")) {
        QSet<QString> have;
        for (const QJsonValue &x : asList(v))
            if (x.isString())
                have.insert(x.toString());
        const QJsonArray wants = want.toArray();
        int count = 0;
        for (const QJsonValue &w : wants)
            if (w.isString() && have.contains(w.toString()))
                ++count;
        if (op == QLatin1String("has_any"))
            return count > 0;
        if (op == QLatin1String("has_all"))
            return !wants.isEmpty() && count == wants.size();
        return count == 0;
    }
    if (op == QLatin1String("before") || op == QLatin1String("after") || op == QLatin1String("on")) {
        const auto iv = dateInterval(v);
        const auto d = resolveDate(want, today);
        if (!iv || !d)
            return false;
        if (op == QLatin1String("before"))
            return iv->second < *d;
        if (op == QLatin1String("after"))
            return iv->first > *d;
        return iv->first <= *d && *d <= iv->second;
    }
    if (op == QLatin1String("between")) {
        const auto iv = dateInterval(v);
        const QJsonArray pair = want.toArray();
        if (!iv || pair.size() != 2)
            return false;
        const auto a = resolveDate(pair.at(0), today);
        const auto b = resolveDate(pair.at(1), today);
        return a && b && iv->first <= *b && iv->second >= *a;
    }
    return false;
}

bool evalGroup(const QString &match, const QJsonArray &filters, const QJsonObject &o, const QString &today)
{
    if (filters.isEmpty())
        return true;
    const bool any = match == QLatin1String("any");
    for (const QJsonValue &fv : filters) {
        const QJsonObject f = fv.toObject();
        const bool ok = f.value(QLatin1String("filters")).isArray()
            ? evalGroup(str(f.value(QLatin1String("match")), QStringLiteral("all")),
                        f.value(QLatin1String("filters")).toArray(), o, today)
            : evalCond(f, o, today);
        if (any && ok)
            return true;
        if (!any && !ok)
            return false;
    }
    return !any;
}

// rank: 0 boolean, 1 number, 2 string; -1 empty.
struct SortKey {
    int rank = -1;
    bool b = false;
    double n = 0;
    QString s;
};

SortKey sortKey(const QJsonValue &v)
{
    SortKey k;
    if (isEmptyValue(v))
        return k;
    if (v.isArray())
        return sortKey(v.toArray().at(0));
    if (v.isObject()) {
        const QJsonValue start = v.toObject().value(QLatin1String("start"));
        k.rank = 2;
        k.s = lower(start.isString() ? start.toString() : QString::fromUtf8(canonOf(v)));
        return k;
    }
    if (v.isBool()) {
        k.rank = 0;
        k.b = v.toBool();
    } else if (v.isDouble()) {
        k.rank = 1;
        k.n = v.toDouble();
    } else {
        k.rank = 2;
        k.s = lower(v.isString() ? v.toString() : QString::fromUtf8(canonOf(v)));
    }
    return k;
}

int compareKeys(const SortKey &a, const SortKey &b)
{
    if (a.rank != b.rank)
        return a.rank < b.rank ? -1 : 1;
    if (a.rank == 0)
        return a.b == b.b ? 0 : (a.b ? 1 : -1);
    if (a.rank == 1)
        return a.n < b.n ? -1 : (a.n > b.n ? 1 : 0);
    return comparePoints(a.s, b.s);
}

QString numberText(double d)
{
    if (std::floor(d) == d && std::fabs(d) < 1e15)
        return QString::number(qint64(d));
    return QString::number(d, 'g', QLocale::FloatingPointShortest);
}

QString groupKeyOf(const QJsonValue &v)
{
    if (v.isString())
        return v.toString();
    if (v.isBool())
        return v.toBool() ? QStringLiteral("true") : QStringLiteral("false");
    if (v.isDouble())
        return numberText(v.toDouble());
    if (v.isObject()) {
        if (const auto iv = dateInterval(v))
            return iv->first;
    }
    return QString::fromUtf8(canonOf(v));
}

QString sha256Hex(const QString &s)
{
    return QString::fromLatin1(QCryptographicHash::hash(s.toUtf8(), QCryptographicHash::Sha256).toHex());
}

} // namespace

QString lower(const QString &s)
{
    bool ascii = true;
    for (QChar c : s)
        if (c.unicode() >= 0x80) {
            ascii = false;
            break;
        }
    if (ascii)
        return s.toLower(); // ASCII: identical to the per-code-point mapping

    QList<uint> cps = s.toUcs4();
    for (uint &cp : cps)
        cp = QChar::toLower(cp);
    return QString::fromUcs4(reinterpret_cast<const char32_t *>(cps.constData()), cps.size());
}

int comparePoints(const QString &a, const QString &b)
{
    // UTF-16 code-unit order equals code-point order except that surrogates (U+D800..DFFF) must
    // sort after U+E000..U+FFFF: remap both ranges at the first difference (no UCS-4 copy).
    const qsizetype n = std::min(a.size(), b.size());
    const QChar *x = a.constData(), *y = b.constData();
    for (qsizetype i = 0; i < n; ++i) {
        ushort u = x[i].unicode(), v = y[i].unicode();
        if (u == v)
            continue;
        auto fix = [](ushort c) -> uint { return c >= 0xE000 ? c - 0x800u : (c >= 0xD800 ? c + 0x2000u : c); };
        return fix(u) < fix(v) ? -1 : 1;
    }
    return a.size() == b.size() ? 0 : (a.size() < b.size() ? -1 : 1);
}

Result evaluate(const QJsonObject &query, const QVector<QJsonObject> &objects, const QString &today)
{
    Result r;
    if (!query.value(QLatin1String("version")).isDouble() || query.value(QLatin1String("version")).toDouble() != 1.0) {
        r.error = QStringLiteral("unsupported_version");
        return r;
    }
    const QString match = str(query.value(QLatin1String("match")), QStringLiteral("all"));
    const QJsonArray filters = query.value(QLatin1String("filters")).toArray();
    r.error = validateGroup(match, filters);
    if (!r.error.isEmpty())
        return r;
    QJsonArray sorts = query.value(QLatin1String("sort")).toArray();
    for (const QJsonValue &s : sorts) {
        const QString d = str(s.toObject().value(QLatin1String("dir")), QStringLiteral("asc"));
        if (d != QLatin1String("asc") && d != QLatin1String("desc")) {
            r.error = QStringLiteral("unknown_dir");
            return r;
        }
    }
    const QString order = str(query.value(QLatin1String("order")), QStringLiteral("sorted"));
    if (order != QLatin1String("sorted") && order != QLatin1String("random")) {
        r.error = QStringLiteral("unknown_order");
        return r;
    }

    QSet<QString> types;
    for (const QJsonValue &t : query.value(QLatin1String("types")).toArray())
        if (t.isString())
            types.insert(t.toString());
    QVector<const QJsonObject *> hits;
    for (const QJsonObject &o : objects) {
        if (!o.value(QLatin1String("trashed_at")).toString().isEmpty())
            continue;
        if (o.value(QLatin1String("properties")).toObject().value(QLatin1String("is_template")).toBool(false))
            continue;
        if (!types.isEmpty() && !types.contains(o.value(QLatin1String("type_id")).toString()))
            continue;
        if (evalGroup(match, filters, o, today))
            hits << &o;
    }

    auto idOf = [](const QJsonObject *o) { return o->value(QLatin1String("id")).toString(); };
    if (order == QLatin1String("random")) {
        const QString seed = query.value(QLatin1String("random_seed")).toString();
        QHash<const QJsonObject *, QString> keys;
        for (const QJsonObject *o : hits)
            keys.insert(o, sha256Hex(seed + QLatin1Char(':') + idOf(o)));
        std::stable_sort(hits.begin(), hits.end(), [&](const QJsonObject *a, const QJsonObject *b) {
            return keys.value(a) < keys.value(b);
        });
    } else {
        if (sorts.isEmpty())
            sorts.append(QJsonObject{ { "prop", "@updated_at" }, { "dir", "desc" } });
        // Decorate once: each object's keys are computed a single time, not per comparison.
        struct Crit { QString prop; bool desc; };
        QVector<Crit> crits;
        for (const QJsonValue &sv : sorts) {
            const QJsonObject so = sv.toObject();
            crits.append({ str(so.value(QLatin1String("prop")), QString()),
                           so.value(QLatin1String("dir")).toString() == QLatin1String("desc") });
        }
        struct Row { const QJsonObject *o; QString id; QVector<SortKey> keys; };
        QVector<Row> rows;
        rows.reserve(hits.size());
        for (const QJsonObject *o : hits) {
            Row row{ o, idOf(o), {} };
            row.keys.reserve(crits.size());
            for (const Crit &c : crits)
                row.keys.append(sortKey(fieldValue(*o, c.prop)));
            rows.append(std::move(row));
        }
        std::stable_sort(rows.begin(), rows.end(), [&](const Row &a, const Row &b) {
            for (int i = 0; i < crits.size(); ++i) {
                const SortKey &ka = a.keys.at(i), &kb = b.keys.at(i);
                int c;
                if (ka.rank < 0 || kb.rank < 0)
                    c = (ka.rank < 0 && kb.rank < 0) ? 0 : (ka.rank < 0 ? 1 : -1);
                else
                    c = crits.at(i).desc ? -compareKeys(ka, kb) : compareKeys(ka, kb);
                if (c != 0)
                    return c < 0;
            }
            return comparePoints(a.id, b.id) < 0;
        });
        for (int i = 0; i < rows.size(); ++i)
            hits[i] = rows.at(i).o;
    }
    const QJsonValue lim = query.value(QLatin1String("limit"));
    if (lim.isDouble() && lim.toDouble() > 0 && int(lim.toDouble()) < hits.size())
        hits.resize(int(lim.toDouble()));

    if (!query.value(QLatin1String("group")).isObject()) {
        for (const QJsonObject *o : hits)
            r.ids << idOf(o);
        return r;
    }

    r.grouped = true;
    const QJsonObject g = query.value(QLatin1String("group")).toObject();
    const QString prop = str(g.value(QLatin1String("prop")), QString());
    struct Bucket {
        QString key;
        SortKey sk;
        QStringList ids;
        QSet<QString> seen;
    };
    QHash<QString, Bucket> buckets;
    QStringList nullIds;
    for (const QJsonObject *o : hits) {
        const QString id = idOf(o);
        const QVector<QJsonValue> elems = asList(fieldValue(*o, prop));
        if (elems.isEmpty()) {
            nullIds << id;
            continue;
        }
        for (const QJsonValue &el : elems) {
            const QString k = groupKeyOf(el);
            const SortKey sk = sortKey(el);
            auto it = buckets.find(k);
            if (it == buckets.end())
                it = buckets.insert(k, Bucket{ k, sk, {}, {} });
            else if (compareKeys(sk, it->sk) < 0)
                it->sk = sk;
            if (!it->seen.contains(id)) {
                it->seen.insert(id);
                it->ids << id;
            }
        }
    }
    QSet<QString> used;
    for (const QJsonValue &kv : g.value(QLatin1String("order")).toArray()) {
        const QString k = kv.toString();
        if (!kv.isString() || used.contains(k) || !buckets.contains(k))
            continue;
        used.insert(k);
        r.groups.append(Group{ k, buckets.value(k).ids });
    }
    QVector<Bucket> rest;
    for (auto it = buckets.cbegin(); it != buckets.cend(); ++it)
        if (!used.contains(it.key()))
            rest << it.value();
    std::sort(rest.begin(), rest.end(), [](const Bucket &a, const Bucket &b) {
        const int c = compareKeys(a.sk, b.sk);
        return c != 0 ? c < 0 : comparePoints(a.key, b.key) < 0;
    });
    for (const Bucket &b : rest)
        r.groups.append(Group{ b.key, b.ids });
    if (!nullIds.isEmpty())
        r.groups.append(Group{ std::nullopt, nullIds });
    return r;
}

} // namespace pkm::query
