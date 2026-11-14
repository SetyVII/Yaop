#include "mentionquery.h"

#include <QRegularExpression>
#include <algorithm>

namespace pkm::mention {

static QString foldAccents(const QString &s)
{
    const QString nfd = s.normalized(QString::NormalizationForm_D);
    QString out;
    out.reserve(nfd.size());
    for (const QChar c : nfd)
        if (c.category() != QChar::Mark_NonSpacing)
            out.append(c);
    return out;
}

QString typeSlug(const QString &name)
{
    static const QRegularExpression separators(QStringLiteral("[\\s_-]+"));
    QString s = foldAccents(name.trimmed().toLower());
    s.replace(separators, QStringLiteral("-"));
    while (s.startsWith(QLatin1Char('-')))
        s.remove(0, 1);
    while (s.endsWith(QLatin1Char('-')))
        s.chop(1);
    return s;
}

QString typeKey(const QString &name)
{
    return typeSlug(name).remove(QLatin1Char('-'));
}

static bool aliasMatches(const QHash<QString, QString> &aliases, const QString &typeId,
                         const QString &key, bool prefix)
{
    for (auto it = aliases.constBegin(); it != aliases.constEnd(); ++it) {
        if (it.value() != typeId)
            continue;
        const QString a = typeKey(it.key());
        if (prefix ? a.startsWith(key) : a == key)
            return true;
    }
    return false;
}

QString resolveTypeToken(const QString &token, const QList<ObjectType> &types,
                         const QHash<QString, QString> &aliases)
{
    const QString key = typeKey(token);
    if (key.isEmpty())
        return {};
    for (const ObjectType &t : types)
        if (typeKey(t.id) == key || typeKey(t.name) == key || aliasMatches(aliases, t.id, key, false))
            return t.id;
    if (key.size() < 2)
        return {};
    for (const ObjectType &t : types)
        if (typeKey(t.id).startsWith(key) || typeKey(t.name).startsWith(key)
            || aliasMatches(aliases, t.id, key, true))
            return t.id;
    return {};
}

QList<ObjectType> matchingTypes(const QString &prefix, const QList<ObjectType> &types,
                                const QHash<QString, QString> &aliases,
                                const QStringList &recentTypeIds)
{
    const QString key = typeKey(prefix);
    QList<ObjectType> out;
    for (const ObjectType &t : types) {
        bool hit = key.isEmpty() || typeKey(t.id).startsWith(key) || typeKey(t.name).startsWith(key)
                   || aliasMatches(aliases, t.id, key, true);
        if (!hit && key.size() >= 2) {
            const QStringList words = typeSlug(t.name).split(QLatin1Char('-'), Qt::SkipEmptyParts);
            hit = std::any_of(words.begin(), words.end(), [&](const QString &w) { return w.startsWith(key); });
        }
        if (hit)
            out.append(t);
    }
    auto rank = [&](const QString &id) {
        const int i = recentTypeIds.indexOf(id);
        return i >= 0 ? i : int(recentTypeIds.size());
    };
    std::stable_sort(out.begin(), out.end(), [&](const ObjectType &a, const ObjectType &b) {
        const int ra = rank(a.id), rb = rank(b.id);
        if (ra != rb)
            return ra < rb;
        return a.name.localeAwareCompare(b.name) < 0;
    });
    return out;
}

} // namespace pkm::mention
