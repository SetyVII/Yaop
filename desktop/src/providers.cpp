#include "providers.h"

#include "texrender_internal.h"

#include <QFile>
#include <QFileInfo>
#include <QPainter>
#include <QSvgRenderer>
#include <QUrl>

namespace pkm {

// id form: <percent-encoded latex>~<px>~<0|1 display>
QString TeXImageProvider::urlFor(const QString &latex, qreal px, bool display)
{
    QString id = QString::fromUtf8(QUrl::toPercentEncoding(latex));
    id += QStringLiteral("~%1~%2").arg(int(px), display ? 1 : 0);
    return QStringLiteral("image://tex/") + id;
}

QString TeXImageProvider::decodeId(const QString &id, qreal *px, bool *display)
{
    const QStringList parts = id.split(QLatin1Char('~'));
    bool ok = false;
    if (px)
        *px = parts.size() > 1 ? parts.at(1).toDouble(&ok) : 0.0;
    if (!ok && px)
        *px = 22.0;
    if (display)
        *display = parts.size() > 2 && parts.at(2).toInt() == 1;
    return QUrl::fromPercentEncoding(parts.isEmpty() ? QString().toUtf8() : parts.at(0).toUtf8());
}

TeXImageProvider::TeXImageProvider()
    : QQuickImageProvider(QQuickImageProvider::Image)
{
}

QImage TeXImageProvider::requestImage(const QString &id, QSize *size, const QSize &requestedSize)
{
    QMutexLocker lock(&m_mutex);
    const auto it = m_cache.constFind(id);
    if (it != m_cache.constEnd()) {
        QImage img = it.value();
        if (size)
            *size = img.size();
        return img;
    }
    qreal px = 22.0;
    bool display = false;
    const QString latex = decodeId(id, &px, &display);
    QByteArray colorHex = "#FFFFFF";
    tex::Result result = tex::render(latex, px, QColor(colorHex.constData()), display, 2.0);
    QImage img = result.ok ? result.image : QImage();
    if (!img.isNull() && !requestedSize.isNull() && requestedSize.width() > 0) {
        // keep aspect when QML constrains
        const qreal aspect = qreal(img.width()) / qreal(img.height() * 2);
        QImage scaled = img.scaled(requestedSize.width(), qMax(1, int(requestedSize.width() / aspect)),
                                   Qt::KeepAspectRatio, Qt::SmoothTransformation);
        if (!scaled.isNull())
            img = scaled;
    }
    if (m_cache.size() > 512)
        m_cache.clear();
    m_cache.insert(id, img);
    if (size)
        *size = img.size();
    return img;
}

// ---------------------------------------------------------------------------
// PDF pages
// ---------------------------------------------------------------------------
PdfPageProvider::PdfPageProvider(const QString &cacheDir)
    : QQuickImageProvider(QQuickImageProvider::Image)
    , m_cacheDir(cacheDir)
    , m_lookup([](const QString &id) { return id; })
{
}

QPdfDocument *PdfPageProvider::documentFor(const QString &assetId)
{
    QMutexLocker lock(&m_mutex);
    const auto it = m_docs.constFind(assetId);
    if (it != m_docs.constEnd())
        return it.value();
    const QString path = m_lookup(assetId);
    if (path.isEmpty() || !QFileInfo::exists(path))
        return nullptr;
    auto *doc = new QPdfDocument();
    if (doc->load(path) != QPdfDocument::Error::None) {
        delete doc;
        return nullptr;
    }
    m_docs.insert(assetId, doc);
    return doc;
}

int PdfPageProvider::pageCount(const QString &assetId)
{
    QPdfDocument *doc = documentFor(assetId);
    return doc ? doc->pageCount() : 0;
}

void PdfPageProvider::invalidate(const QString &assetId)
{
    QMutexLocker lock(&m_mutex);
    const auto it = m_docs.find(assetId);
    if (it != m_docs.end()) {
        delete it.value();
        m_docs.erase(it);
    }
}

QImage PdfPageProvider::requestImage(const QString &id, QSize *size, const QSize &requestedSize)
{
    const QStringList parts = id.split(QLatin1Char('/'));
    const QString assetId = parts.value(0);
    int page = parts.value(1, "1").toInt();        // 1-based
    const qreal zoom = qBound(0.25, parts.value(2, "1.5").toDouble(), 4.0);
    if (page < 1)
        page = 1;

    QPdfDocument *doc = documentFor(assetId);
    if (!doc) {
        if (size)
            *size = QSize(0, 0);
        return {};
    }
    const QSizeF base = doc->pagePointSize(page - 1);
    int w = int(base.width() * zoom);
    int h = int(base.height() * zoom);
    if (!requestedSize.isNull() && requestedSize.width() > 0 && requestedSize.height() > 0) {
        w = requestedSize.width();
        h = requestedSize.height();
    }
    QImage img = doc->render(page - 1, QSize(w, h));
    if (size)
        *size = img.size();
    return img;
}

// ---------------------------------------------------------------------------
// Crisp SVG icons (24x24, 1.5px stroke, square caps — docs/design/mockup/Icons.dc.html)
// ---------------------------------------------------------------------------
namespace {

QByteArray svgPathFor(const QString &name)
{
    static const QHash<QString, QByteArray> kPaths = {
        // 16 base types
        { "journal", "M4 6h16v15H4z M4 10h16 M8 3v5 M16 3v5 M14 14h3v3h-3z" },
        { "daily", "M4 6h16v15H4z M4 10h16 M8 3v5 M16 3v5 M14 14h3v3h-3z" },
        { "note", "M6 3h9l4 4v14H6z M15 3v4h4 M9 12h7 M9 16h5" },
        { "file-text", "M6 3h9l4 4v14H6z M15 3v4h4 M9 12h7 M9 16h5" },
        { "task", "M4 4h16v16H4z M8 12l3 3 5-6" },
        { "circle-check", "M4 4h16v16H4z M8 12l3 3 5-6" },
        { "project", "M3 7h7l2 2h9v11H3z" },
        { "folder", "M3 7h7l2 2h9v11H3z" },
        { "person", "M8 8a4 4 0 1 0 8 0a4 4 0 1 0 -8 0 M4 21c1-4 4-6 8-6s7 2 8 6" },
        { "user", "M8 8a4 4 0 1 0 8 0a4 4 0 1 0 -8 0 M4 21c1-4 4-6 8-6s7 2 8 6" },
        { "people", "M5.5 8a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0 -7 0 M2 20c.8-3.5 3.5-5 7-5s6.2 1.5 7 5 M16 4.5a3.5 3.5 0 0 1 0 7 M18 15c2 .6 3.4 2.2 4 5" },
        { "book", "M5 4h11a3 3 0 0 1 3 3v14H8a3 3 0 0 1-3-3z M5 18a3 3 0 0 1 3-3h11" },
        { "web", "M10 14l4-4 M8.5 11.5l-2 2a3.5 3.5 0 0 0 5 5l2-2 M15.5 12.5l2-2a3.5 3.5 0 0 0-5-5l-2 2" },
        { "link", "M10 14l4-4 M8.5 11.5l-2 2a3.5 3.5 0 0 0 5 5l2-2 M15.5 12.5l2-2a3.5 3.5 0 0 0-5-5l-2 2" },
        { "world", "M10 14l4-4 M8.5 11.5l-2 2a3.5 3.5 0 0 0 5 5l2-2 M15.5 12.5l2-2a3.5 3.5 0 0 0-5-5l-2 2" },
        { "image", "M3 5h18v14H3z M3 16l5-5 4 4 3-3 6 6 M14 9.5a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0" },
        { "photo", "M3 5h18v14H3z M3 16l5-5 4 4 3-3 6 6 M14 9.5a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0" },
        { "video", "M3 6h13v12H3z M16 10l5-3v10l-5-3" },
        { "audio", "M4 10v4 M8 7v10 M12 4v16 M16 8v8 M20 11v2" },
        { "music", "M4 10v4 M8 7v10 M12 4v16 M16 8v8 M20 11v2" },
        { "pdf", "M6 3h9l4 4v14H6z M15 3v4h4 M9 13h6 M9 17h4" },
        { "file-type-pdf", "M6 3h9l4 4v14H6z M15 3v4h4 M9 13h6 M9 17h4" },
        { "file", "M6 3h9l4 4v14H6z M15 3v4h4" },
        { "tag", "M3 12V3h9l9 9-9 9z M7.5 7.5h.01" },
        { "whiteboard", "M3 4h18v13H3z M8 21l4-4 4 4 M7 9h4 M7 12h7" },
        { "layout-board-split", "M3 4h18v13H3z M8 21l4-4 4 4 M7 9h4 M7 12h7" },
        { "table", "M3 5h18v14H3z M3 10h18 M3 15h18 M9 5v14" },
        { "habit", "M12 21a7 7 0 0 0 7-7c0-4-3-6-4-10-2 2-3 4-3 6-1-1-2-2-2-4-3 3-5 5-5 8a7 7 0 0 0 7 7z" },
        { "heart", "M12 21a7 7 0 0 0 7-7c0-4-3-6-4-10-2 2-3 4-3 6-1-1-2-2-2-4-3 3-5 5-5 8a7 7 0 0 0 7 7z" },
        { "flame", "M12 21a7 7 0 0 0 7-7c0-4-3-6-4-10-2 2-3 4-3 6-1-1-2-2-2-4-3 3-5 5-5 8a7 7 0 0 0 7 7z" },
        // 24 UI icons
        { "search", "M4 10.5a6.5 6.5 0 1 0 13 0a6.5 6.5 0 1 0 -13 0 M15.5 15.5L21 21" },
        { "plus", "M12 5v14 M5 12h14" },
        { "calendar", "M4 6h16v15H4z M4 10h16 M8 3v5 M16 3v5" },
        { "layout-grid", "M4 4h7v7H4z M13 4h7v7h-7z M4 13h7v7H4z M13 13h7v7h-7z" },
        { "graph", "M4 6a2 2 0 1 0 4 0a2 2 0 1 0 -4 0 M16 8a2 2 0 1 0 4 0a2 2 0 1 0 -4 0 M8 18a2 2 0 1 0 4 0a2 2 0 1 0 -4 0 M8 6.3l8 1.4 M6.7 8l2.6 8 M16.6 9.6l-5.2 6.8" },
        { "network", "M4 6a2 2 0 1 0 4 0a2 2 0 1 0 -4 0 M16 8a2 2 0 1 0 4 0a2 2 0 1 0 -4 0 M8 18a2 2 0 1 0 4 0a2 2 0 1 0 -4 0 M8 6.3l8 1.4 M6.7 8l2.6 8 M16.6 9.6l-5.2 6.8" },
        { "layout-columns", "M3 4h18v16H3z M15 4v16" },
        { "layout-sidebar-right", "M3 4h18v16H3z M15 4v16" },
        { "layout-sidebar-left-collapse", "M3 4h18v16H3z M9 4v16" },
        { "layout-sidebar-right-collapse", "M3 4h18v16H3z M9 4v16" },
        { "filter", "M4 5h16 M7 12h10 M10 19h4" },
        { "arrows-sort", "M7 4v16 M3 16l4 4 4-4 M17 20V4 M13 8l4-4 4 4" },
        { "list", "M9 6h11 M9 12h11 M9 18h11 M4 6h.01 M4 12h.01 M4 18h.01" },
        { "list-numbers", "M10 6h10 M10 12h10 M10 18h10 M4 5l1.5-1v5 M3.5 14h3l-3 4h3" },
        { "layout-kanban", "M4 4h4v16H4z M10 4h4v10h-4z M16 4h4v13h-4z" },
        { "grip-vertical", "M9 6h.01 M15 6h.01 M9 12h.01 M15 12h.01 M9 18h.01 M15 18h.01" },
        { "at", "M8 12a4 4 0 1 0 8 0a4 4 0 1 0 -8 0 M16 12v1.5a2.5 2.5 0 0 0 5 0V12a9 9 0 1 0-3.5 7.1" },
        { "text", "M5 6V4h14v2 M12 4v16 M9 20h6" },
        { "h-1", "M5 5v14 M13 5v14 M5 12h8 M17 12l3-2v9" },
        { "h-2", "M5 5v14 M13 5v14 M5 12h8 M16 11a2 2 0 0 1 4 0c0 2-4 4-4 8h4" },
        { "quote", "M5 18v-5c0-4 2-6 5-7 M14 18v-5c0-4 2-6 5-7" },
        { "code", "M8 7l-5 5 5 5 M16 7l5 5-5 5" },
        { "sigma", "M18 5H6l7 7-7 7h12" },
        { "callout", "M4 4h16v12H9l-5 4z" },
        { "toggle", "M8 5l8 7-8 7z" },
        { "history", "M3 12a9 9 0 1 0 3-6.7 M3 4v5h5 M12 7v5l3 2" },
        { "lock", "M5 11h14v10H5z M8 11V7a4 4 0 0 1 8 0v4" },
        { "sync", "M4 12a8 8 0 0 1 14-5.3 M18 3v4h-4 M20 12a8 8 0 0 1-14 5.3 M6 21v-4h4" },
        { "external-link", "M14 4h6v6 M20 4l-9 9 M18 14v6H4V6h6" },
        { "trash", "M4 7h16 M9 7V4h6v3 M6 7l1 14h10l1-14" },
        { "x", "M6 6l12 12 M18 6L6 18" },
        { "chevron-left", "M15 6l-6 6 6 6" },
        { "chevron-right", "M9 6l6 6-6 6" },
        { "chevron-down", "M6 9l6 6 6-6" },
        { "zoom-in", "M4 10.5a6.5 6.5 0 1 0 13 0a6.5 6.5 0 1 0 -13 0 M15.5 15.5L21 21 M8 10.5h5 M10.5 8v5" },
        { "zoom-out", "M4 10.5a6.5 6.5 0 1 0 13 0a6.5 6.5 0 1 0 -13 0 M15.5 15.5L21 21 M8 10.5h5" },
        { "arrows-maximize", "M4 9V4h5 M15 4h5v5 M20 15v5h-5 M9 20H4v-5" },
        { "inbox", "M3 13l3-8h12l3 8v7H3z M3 13h5l2 3h4l2-3h5" },
        { "shape", "M4 4h7v7H4z M13 13h7v7h-7z M11 7.5h5v5.5" },
        { "select", "M4 6h16v12H4z M9 11l3 3 3-3" },
        { "multi", "M4 7h10 M4 12h10 M4 17h10 M17 7l1.5 1.5L21 6" },
        { "number", "M9 4L7 20 M17 4l-2 16 M4 9h16 M3 15h16" },
        { "bulb", "M9 18h6 M10 21h4 M12 3a6 6 0 0 0-4 10.5c.8.8 1 1.5 1 2.5h6c0-1 .2-1.7 1-2.5A6 6 0 0 0 12 3z" },
        { "star", "M12 3l2.7 5.6 6.1.8-4.4 4.3 1 6.1L12 17l-5.4 2.8 1-6.1-4.4-4.3 6.1-.8z" },
        { "pin", "M9 3h6l-1 6 4 4H6l4-4z M12 13v8" },
        { "recipe", "M6 3v8a3 3 0 0 0 6 0V3 M9 3v18 M17 3c-2 2-2 6 0 8v10" },
        { "copy", "M8 8h12v12H8z M4 16V4h12" },
        { "dots", "M4 11h2v2H4z M11 11h2v2h-2z M18 11h2v2h-2z" },
        { "arrow-up", "M12 20V4 M5 11l7-7 7 7" },
        { "arrow-down", "M12 4v16 M5 13l7 7 7-7" },
        { "arrow-back", "M19 12H5 M12 19l-7-7 7-7" },
        { "arrow-forward", "M5 12h14 M13 6l6 6-6 6" },
        { "check", "M5 12l5 5 9-10" },
        { "minus", "M3 12h18" },
    };
    return kPaths.value(name);
}

} // namespace

IconProvider::IconProvider()
    : QQuickImageProvider(QQuickImageProvider::Image)
{
}

QImage IconProvider::requestImage(const QString &id, QSize *size, const QSize &requestedSize)
{
    const QStringList parts = id.split(QLatin1Char('/'));
    const QString name = parts.value(0);
    QString hex = parts.value(1, QStringLiteral("f2f2f2"));
    if (!hex.startsWith(QLatin1Char('#')))
        hex.prepend(QLatin1Char('#'));
    const int px = (requestedSize.width() > 0) ? requestedSize.width()
                 : (requestedSize.height() > 0) ? requestedSize.height() : 24;
    const int renderPx = qBound(16, px * 2, 128);
    const QString key = name + QLatin1Char('|') + hex + QLatin1Char('|') + QString::number(renderPx);

    {
        QMutexLocker lock(&m_mutex);
        auto it = m_cache.constFind(key);
        if (it != m_cache.constEnd()) {
            if (size)
                *size = it->size();
            return *it;
        }
    }

    QByteArray svg;
    const QByteArray d = svgPathFor(name);
    if (!d.isEmpty()) {
        svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" "
              "fill=\"none\" stroke=\"" + hex.toLatin1() + "\" stroke-width=\"1.5\" "
              "stroke-linecap=\"square\" stroke-linejoin=\"miter\"><path d=\"" + d + "\"/></svg>";
    } else {
        QFile f(QStringLiteral(":/assets/icons/") + name + QStringLiteral(".svg"));
        if (f.open(QIODevice::ReadOnly)) {
            svg = f.readAll();
            svg.replace("currentColor", hex.toLatin1());
            svg.replace("#000000", hex.toLatin1());
            svg.replace("#000", hex.toLatin1());
        }
    }

    QImage img(renderPx, renderPx, QImage::Format_ARGB32_Premultiplied);
    img.fill(Qt::transparent);
    if (!svg.isEmpty()) {
        QSvgRenderer r(svg);
        if (r.isValid()) {
            QPainter p(&img);
            p.setRenderHint(QPainter::Antialiasing, true);
            r.render(&p, QRectF(0, 0, renderPx, renderPx));
        }
    }

    {
        QMutexLocker lock(&m_mutex);
        if (m_cache.size() > 1024)
            m_cache.clear();
        m_cache.insert(key, img);
    }
    if (size)
        *size = img.size();
    return img;
}

} // namespace pkm
