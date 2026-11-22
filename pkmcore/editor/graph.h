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
// `linksOverride` replaces the stored `links` of some objects (e.g. ones open in an editor with
// unsaved references) without copying the whole map.
Graph buildGraph(const QHash<QString, Object> &objects, const QSet<QString> &types = {},
                 const QHash<QString, QStringList> &linksOverride = {});

// Local graph around `focus`: every node within `depth` hops (depth >= 1), with the edges among
// them. Empty when `focus` is not in the graph.
Graph localGraph(const Graph &whole, const QString &focus, int depth = 1);

// Same result as localGraph(buildGraph(objects, types, linksOverride), focus, depth), without
// building the whole graph: a few passes over the link lists, no per-node sets for the vault.
Graph localGraphOf(const QHash<QString, Object> &objects, const QString &focus, int depth = 1,
                   const QSet<QString> &types = {}, const QHash<QString, QStringList> &linksOverride = {});

} // namespace pkm
