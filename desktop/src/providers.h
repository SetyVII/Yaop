#pragma once

// QML image providers: "tex" (LaTeX renderer), "pdfpage" (asset-backed PDF
// pages via QPdfDocument), plus a tiny in-memory cache.

#include <QImage>
#include <QMutex>
#include <QPdfDocument>
#include <QQmlEngine>
#include <QQuickImageProvider>
#include <QHash>
#include <QString>

namespace pkm::tex {
struct Result;
}

namespace pkm {

class TeXImageProvider : public QQuickImageProvider {
public:
    TeXImageProvider();
    QImage requestImage(const QString &id, QSize *size, const QSize &requestedSize) override;
    static QString urlFor(const QString &latex, qreal px, bool display); // encodes id
    static QString decodeId(const QString &id, qreal *px, bool *display);

private:
    QMutex m_mutex;
    QHash<QString, QImage> m_cache;
};

// image://pdfpage/<assetId>            -> page count probe (returns page 1)
// image://pdfpage/<assetId>/<page>/<zoom> -> rendered page image
class PdfPageProvider : public QQuickImageProvider {
public:
    explicit PdfPageProvider(const QString &cacheDir);

    QImage requestImage(const QString &id, QSize *size, const QSize &requestedSize) override;

    // Returns the cached local path for an asset, downloading is handled by Workspace.
    void setAssetLookup(std::function<QString(const QString &)> fn) { m_lookup = std::move(fn); }

    int pageCount(const QString &assetId);
    QPdfDocument *documentFor(const QString &assetId);
    void invalidate(const QString &assetId);

private:
    QString m_cacheDir;
    std::function<QString(const QString &)> m_lookup;
    QMutex m_mutex;
    QHash<QString, QPdfDocument *> m_docs;
};

// image://icon/<name>/<hex> -> crisp 24x24 SVG icon rendered via QSvgRenderer
// Works identically on GPU and offscreen software backends (unlike MultiEffect).
class IconProvider : public QQuickImageProvider {
public:
    IconProvider();
    QImage requestImage(const QString &id, QSize *size, const QSize &requestedSize) override;

private:
    QMutex m_mutex;
    QHash<QString, QImage> m_cache;
};

} // namespace pkm
