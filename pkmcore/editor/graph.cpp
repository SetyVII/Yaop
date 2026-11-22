#include "graph.h"

#include <algorithm>

namespace pkm {

Graph buildGraph(const QHash<QString, Object> &objects, const QSet<QString> &types)
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
        for (const QStringList *list : { &o.links, &o.propertyLinks })
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

} // namespace pkm
