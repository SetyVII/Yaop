#pragma once
// Graph of objects (product §10): nodes are alive objects, edges are references — content
// links, property links (tags, relations) — never the object to itself. Pure, no store.

#include "model.h"

#include <QHash>
#include <QSet>

namespace pkm {

struct GraphNode {
    QString id;
    QString typeId;
    QString title;
    int degree = 0;   // number of distinct neighbours
};

struct GraphEdge {
    QString from;
    QString to;       // from < to: an edge is undirected and listed once
};

struct Graph {
    QVector<GraphNode> nodes; // sorted by id
    QVector<GraphEdge> edges; // sorted by (from, to)
};

// Whole graph. `types` (optional) keeps only objects of those types; trashed and template
// objects are always left out, and so are references to objects that are not in the graph.
Graph buildGraph(const QHash<QString, Object> &objects, const QSet<QString> &types = {});

// Local graph around `focus`: every node within `depth` hops (depth >= 1), with the edges among
// them. Empty when `focus` is not in the graph.
Graph localGraph(const Graph &whole, const QString &focus, int depth = 1);

} // namespace pkm
