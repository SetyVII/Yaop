#include "graph.h"

#include <functional>

#include <algorithm>

namespace pkm {

Graph buildGraph(const QHash<QString, Object> &objects, const QSet<QString> &types,
                 const QHash<QString, QStringList> &linksOverride)
{
    auto included = [&](const Object &o) {
        return o.trashedAt.isEmpty() && !o.isTemplate() && (types.isEmpty() || types.contains(o.typeId));
    };
    QHash<QString, QSet<QString>> adj;
    for (const Object &o : objects)
        if (included(o))
            adj.insert(o.id, {});
    for (const Object &o : objects) {
        if (!adj.contains(o.id))
            continue;
        const auto ov = linksOverride.constFind(o.id);
        const QStringList &links = ov != linksOverride.constEnd() ? ov.value() : o.links;
        for (const QStringList *list : { &links, &o.propertyLinks })
            for (const QString &t : *list)
                if (t != o.id && adj.contains(t)) {
                    adj[o.id].insert(t);
                    adj[t].insert(o.id);
                }
    }
    Graph g;
    for (auto it = adj.constBegin(); it != adj.constEnd(); ++it) {
        const Object &o = objects.value(it.key());
        g.nodes.append({ o.id, o.typeId, plainTitleFor(o), int(it.value().size()) });
        for (const QString &n : it.value())
            if (it.key() < n)
                g.edges.append({ it.key(), n });
    }
    std::sort(g.nodes.begin(), g.nodes.end(), [](const GraphNode &a, const GraphNode &b) { return a.id < b.id; });
    std::sort(g.edges.begin(), g.edges.end(), [](const GraphEdge &a, const GraphEdge &b) {
        return a.from != b.from ? a.from < b.from : a.to < b.to;
    });
    return g;
}

Graph localGraph(const Graph &whole, const QString &focus, int depth)
{
    QHash<QString, QSet<QString>> adj;
    for (const GraphEdge &e : whole.edges) {
        adj[e.from].insert(e.to);
        adj[e.to].insert(e.from);
    }
    bool present = false;
    for (const GraphNode &n : whole.nodes)
        present = present || n.id == focus;
    if (!present)
        return {};
    QSet<QString> seen{ focus };
    QSet<QString> frontier{ focus };
    for (int d = 0; d < std::max(1, depth); ++d) {
        QSet<QString> next;
        for (const QString &id : std::as_const(frontier))
            for (const QString &n : adj.value(id))
                if (!seen.contains(n))
                    next.insert(n);
        seen.unite(next);
        frontier = next;
    }
    Graph g;
    for (const GraphNode &n : whole.nodes)
        if (seen.contains(n.id))
            g.nodes.append(n);
    for (const GraphEdge &e : whole.edges)
        if (seen.contains(e.from) && seen.contains(e.to))
            g.edges.append(e);
    return g;
}

Graph localGraphOf(const QHash<QString, Object> &objects, const QString &focus, int depth,
                   const QSet<QString> &types, const QHash<QString, QStringList> &linksOverride)
{
    auto included = [&](const Object &o) {
        return o.trashedAt.isEmpty() && !o.isTemplate() && (types.isEmpty() || types.contains(o.typeId));
    };
    const auto f = objects.constFind(focus);
    if (f == objects.constEnd() || !included(f.value()))
        return {};
    // calls fn(src, dst) for every edge src -> dst between included objects
    auto forEachEdge = [&](const std::function<void(const QString &, const QString &)> &fn) {
        for (const Object &o : objects) {
            if (!included(o))
                continue;
            const auto ov = linksOverride.constFind(o.id);
            const QStringList &links = ov != linksOverride.constEnd() ? ov.value() : o.links;
            for (const QStringList *list : { &links, &o.propertyLinks })
                for (const QString &t : *list) {
                    if (t == o.id)
                        continue;
                    const auto to = objects.constFind(t);
                    if (to != objects.constEnd() && included(to.value()))
                        fn(o.id, t);
                }
        }
    };
    QSet<QString> seen{ focus };
    QSet<QString> frontier{ focus };
    for (int d = 0; d < std::max(1, depth) && !frontier.isEmpty(); ++d) {
        QSet<QString> next;
        forEachEdge([&](const QString &a, const QString &b) {
            if (frontier.contains(a) && !seen.contains(b))
                next.insert(b);
            if (frontier.contains(b) && !seen.contains(a))
                next.insert(a);
        });
        seen.unite(next);
        frontier = next;
    }
    QHash<QString, QSet<QString>> neighbours; // whole-graph degree of the nodes we keep
    QSet<QPair<QString, QString>> edgeSet;
    forEachEdge([&](const QString &a, const QString &b) {
        const bool ia = seen.contains(a), ib = seen.contains(b);
        if (ia)
            neighbours[a].insert(b);
        if (ib)
            neighbours[b].insert(a);
        if (ia && ib)
            edgeSet.insert(a < b ? qMakePair(a, b) : qMakePair(b, a));
    });
    Graph g;
    for (const QString &id : std::as_const(seen)) {
        const Object &o = objects.value(id);
        g.nodes.append({ o.id, o.typeId, plainTitleFor(o), int(neighbours.value(id).size()) });
    }
    for (const auto &e : std::as_const(edgeSet))
        g.edges.append({ e.first, e.second });
    std::sort(g.nodes.begin(), g.nodes.end(), [](const GraphNode &a, const GraphNode &b) { return a.id < b.id; });
    std::sort(g.edges.begin(), g.edges.end(), [](const GraphEdge &a, const GraphEdge &b) {
        return a.from != b.from ? a.from < b.from : a.to < b.to;
    });
    return g;
}

} // namespace pkm
