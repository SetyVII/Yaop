// LinkPreviewFetcher: async HTTP transport + per-platform extraction pipelines.
// Zero Workspace/EditorModel dependencies — takes a URL and delivers LinkPreviewData.
#include "linkpreview.h"

#include <QNetworkAccessManager>
#include <QNetworkCookieJar>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QPointer>
#include <QRegularExpression>

#include <memory>

namespace pkm::linkpreview {

namespace {

constexpr qint64 kMaxJsonBytes = 512 * 1024;
constexpr qint64 kMaxImageBytes = 8 * 1024 * 1024;

bool needsRedditRedirectResolution(const QUrl &url)
{
    const QString host = bareHost(url);
    return host == QLatin1String("redd.it")
        || host == QLatin1String("v.redd.it")
        || url.path().contains(QLatin1String("/s/"), Qt::CaseInsensitive);
}

bool needsInstagramRedirectResolution(const QUrl &url)
{
    const QString host = bareHost(url);
    return host == QLatin1String("instagr.am")
        || url.path().contains(QLatin1String("/share/"), Qt::CaseInsensitive);
}

bool needsFacebookRedirectResolution(const QUrl &url)
{
    const QString host = bareHost(url);
    return host == QLatin1String("fb.watch")
        || host == QLatin1String("fb.me")
        || host == QLatin1String("fb.com")
        || url.path().contains(QLatin1String("/share/"), Qt::CaseInsensitive);
}

QString firstNonBlockedTitle(std::initializer_list<QString> candidates)
{
    for (const QString &c : candidates) {
        if (!c.trimmed().isEmpty() && !isBlockedTitle(c))
            return c.trimmed();
    }
    return {};
}

QString firstNonEmpty(std::initializer_list<QString> candidates)
{
    for (const QString &c : candidates) {
        if (!c.trimmed().isEmpty())
            return c.trimmed();
    }
    return {};
}

} // namespace

LinkPreviewFetcher::LinkPreviewFetcher(QObject *parent)
    : QObject(parent)
{
    initDefaultTransport();
}

LinkPreviewFetcher::LinkPreviewFetcher(HttpGetFn transport, QObject *parent)
    : QObject(parent)
    , m_transport(std::move(transport))
{
    if (!m_transport)
        initDefaultTransport();
}

LinkPreviewFetcher::~LinkPreviewFetcher() = default;

void LinkPreviewFetcher::initDefaultTransport()
{
    if (!m_nam) {
        m_nam = new QNetworkAccessManager(this);
        m_nam->setCookieJar(new QNetworkCookieJar(m_nam));
    }

    QPointer<LinkPreviewFetcher> self(this);
    m_transport = [self](const HttpRequest &req, std::function<void(HttpResponse)> done) {
        if (!self || !self->m_nam || !req.url.isValid()) {
            done(HttpResponse{});
            return;
        }
        const QString scheme = req.url.scheme().toLower();
        if (scheme != QLatin1String("http") && scheme != QLatin1String("https")) {
            done(HttpResponse{});
            return;
        }

        QNetworkRequest netReq(req.url);
        for (auto it = req.headers.constBegin(); it != req.headers.constEnd(); ++it)
            netReq.setRawHeader(it.key().toUtf8(), it.value().toUtf8());
        netReq.setAttribute(QNetworkRequest::RedirectPolicyAttribute,
                            req.followRedirects ? QNetworkRequest::NoLessSafeRedirectPolicy
                                                : QNetworkRequest::ManualRedirectPolicy);
        if (req.followRedirects)
            netReq.setMaximumRedirectsAllowed(req.maxRedirects);
        netReq.setTransferTimeout(req.timeoutMs);

        QNetworkReply *reply = self->m_nam->get(netReq);
        struct ReadState {
            QByteArray buffer;
            bool truncated = false;
            bool delivered = false;
            qint64 maxBytes = 0;
        };
        auto state = std::make_shared<ReadState>();
        state->maxBytes = req.maxBytes;

        QObject::connect(reply, &QNetworkReply::readyRead, self, [reply, state]() {
            if (state->truncated)
                return;
            const qint64 remaining = state->maxBytes - state->buffer.size();
            if (remaining <= 0) {
                state->truncated = true;
                reply->abort();
                return;
            }
            state->buffer.append(reply->read(remaining));
            if (state->buffer.size() >= state->maxBytes) {
                state->truncated = true;
                reply->abort();
            }
        });

        QObject::connect(reply, &QNetworkReply::finished, self, [reply, state, req, done = std::move(done)]() {
            reply->deleteLater();
            if (state->delivered)
                return;
            state->delivered = true;

            if (!state->truncated && reply->bytesAvailable() > 0) {
                const qint64 remaining = state->maxBytes - state->buffer.size();
                if (remaining > 0)
                    state->buffer.append(reply->read(remaining));
            }

            HttpResponse res;
            res.statusCode = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
            res.finalUrl = reply->url().isValid() ? reply->url() : req.url;
            res.contentType = reply->header(QNetworkRequest::ContentTypeHeader).toString();
            const QUrl redir = reply->attribute(QNetworkRequest::RedirectionTargetAttribute).toUrl();
            if (redir.isValid())
                res.redirectLocation = res.finalUrl.resolved(redir).toString();
            res.body = std::move(state->buffer);

            const bool transportOk = (reply->error() == QNetworkReply::NoError) || state->truncated;
            if (!req.followRedirects && res.statusCode >= 300 && res.statusCode < 400)
                res.ok = transportOk;
            else
                res.ok = transportOk && (res.statusCode >= 200 && res.statusCode < 300);

            done(std::move(res));
        });
    };
}

void LinkPreviewFetcher::httpGet(const HttpRequest &req, std::function<void(HttpResponse)> done)
{
    if (!m_transport) {
        done(HttpResponse{});
        return;
    }
    m_transport(req, std::move(done));
}

void LinkPreviewFetcher::clearCache()
{
    m_cache.clear();
    m_cacheOrder.clear();
}

QString LinkPreviewFetcher::cacheKey(const QUrl &normalizedUrl, const LinkPreviewOptions &opts) const
{
    return (opts.allowThirdPartyProxies ? QStringLiteral("1:") : QStringLiteral("0:"))
        + normalizedUrl.toString();
}

std::optional<LinkPreviewData> LinkPreviewFetcher::cacheLookup(const QString &key)
{
    auto it = m_cache.constFind(key);
    if (it == m_cache.constEnd())
        return std::nullopt;
    m_cacheOrder.removeOne(key);
    m_cacheOrder.append(key);
    return it.value();
}

void LinkPreviewFetcher::cacheInsert(const QString &key, const LinkPreviewData &data)
{
    m_cache.insert(key, data);
    m_cacheOrder.removeOne(key);
    m_cacheOrder.append(key);
    while (m_cacheOrder.size() > kMaxCacheEntries) {
        const QString oldest = m_cacheOrder.takeFirst();
        m_cache.remove(oldest);
    }
}

void LinkPreviewFetcher::resolveRedirects(const QUrl &startUrl, int maxHops,
                                          std::function<void(QUrl)> done)
{
    if (maxHops <= 0 || !startUrl.isValid()) {
        done(startUrl);
        return;
    }

    HttpRequest req;
    req.url = startUrl;
    req.headers = botHeaders();
    req.timeoutMs = 5000;
    req.maxBytes = 4096;
    req.followRedirects = false;

    QPointer<LinkPreviewFetcher> self(this);
    httpGet(req, [self, startUrl, maxHops, done = std::move(done)](HttpResponse res) mutable {
        if (!self)
            return;
        if (res.statusCode >= 300 && res.statusCode < 400 && !res.redirectLocation.isEmpty()) {
            const QUrl next = startUrl.resolved(QUrl(res.redirectLocation));
            if (next.isValid() && next != startUrl
                && (next.scheme() == QLatin1String("http") || next.scheme() == QLatin1String("https"))) {
                self->resolveRedirects(next, maxHops - 1, std::move(done));
                return;
            }
        }
        done(res.finalUrl.isValid() ? res.finalUrl : startUrl);
    });
}

void LinkPreviewFetcher::fetch(const QString &rawUrl, const LinkPreviewOptions &opts,
                               PreviewCallback cb)
{
    const QUrl normalized = normalizeUrl(rawUrl);
    if (!normalized.isValid()) {
        cb(std::nullopt);
        return;
    }

    const QString key = cacheKey(normalized, opts);
    if (const auto cached = cacheLookup(key)) {
        cb(cached);
        return;
    }

    QPointer<LinkPreviewFetcher> self(this);
    auto finish = [self, key, cb = std::move(cb)](const std::optional<LinkPreviewData> &result) mutable {
        if (!self)
            return;
        if (result.has_value())
            self->cacheInsert(key, *result);
        cb(result);
    };

    // 1. YouTube (official public oEmbed + direct CDN thumbnail, fallback to full HTML)
    const QString ytVideoId = extractYouTubeVideoId(normalized);
    if (!ytVideoId.isEmpty()) {
        fetchYouTube(normalized, ytVideoId, std::move(finish));
        return;
    }

    // 2. Reddit (direct + optional vxreddit & Arctic Shift archive fallback)
    if (isReddit(normalized)) {
        fetchReddit(normalized, opts.allowThirdPartyProxies, std::move(finish));
        return;
    }

    // 3. Instagram / Reels (social crawler + official /embed/captioned/ fallback)
    if (isInstagram(normalized)) {
        fetchInstagram(normalized, std::move(finish));
        return;
    }

    // 4. Facebook & Facebook Video
    if (isFacebook(normalized)) {
        fetchFacebook(normalized, std::move(finish));
        return;
    }

    // 5. X / Twitter (optional vxtwitter API + fixupx fallback, then direct social crawler)
    if (isTwitterOrX(normalized)) {
        fetchTwitter(normalized, opts.allowThirdPartyProxies, std::move(finish));
        return;
    }

    // 6. TikTok (official public oEmbed, fallback to generic HTML)
    if (isTikTok(normalized)) {
        fetchTikTok(normalized, std::move(finish));
        return;
    }

    // 7. Generic web page
    fetchGeneric(normalized, std::move(finish));
}

void LinkPreviewFetcher::downloadImage(const QUrl &imageUrl, ImageCallback cb)
{
    const QUrl clean(cleanImageUrl(imageUrl.toString()));
    if (!clean.isValid() || (clean.scheme() != QLatin1String("http") && clean.scheme() != QLatin1String("https"))) {
        cb({});
        return;
    }

    auto fetchUrl = [this](const QUrl &u, ImageCallback done) {
        HttpRequest req;
        req.url = u;
        req.headers = imageDownloadHeaders(u);
        req.timeoutMs = 8000;
        req.maxBytes = kMaxImageBytes;
        req.followRedirects = true;

        QPointer<LinkPreviewFetcher> self(this);
        httpGet(req, [self, done = std::move(done)](HttpResponse res) mutable {
            if (!self)
                return;
            done(res.ok ? res.body : QByteArray());
        });
    };

    // Upgrade YouTube hqdefault.jpg (480x360 letterboxed) to maxresdefault.jpg (1280x720)
    // when available, falling back seamlessly to hqdefault.jpg if 404.
    const QString host = clean.host().toLower();
    if ((host == QLatin1String("i.ytimg.com") || host == QLatin1String("img.youtube.com"))
        && clean.path().endsWith(QLatin1String("/hqdefault.jpg"), Qt::CaseInsensitive)) {
        QUrl hiRes = clean;
        QString hiPath = clean.path();
        hiPath.replace(hiPath.size() - 14, 14, QStringLiteral("/maxresdefault.jpg"));
        hiRes.setPath(hiPath);

        QPointer<LinkPreviewFetcher> self(this);
        fetchUrl(hiRes, [self, clean, cb = std::move(cb), fetchUrl](const QByteArray &hiBytes) mutable {
            if (!self)
                return;
            if (!hiBytes.isEmpty()) {
                cb(hiBytes);
                return;
            }
            fetchUrl(clean, std::move(cb));
        });
        return;
    }

    fetchUrl(clean, std::move(cb));
}

void LinkPreviewFetcher::fetchYouTube(const QUrl &url, const QString &videoId, PreviewCallback done)
{
    HttpRequest req;
    req.url = QUrl(QStringLiteral("https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=%1&format=json")
                       .arg(videoId));
    req.headers = jsonBotHeaders();
    req.timeoutMs = 6000;
    req.maxBytes = kMaxJsonBytes;

    QPointer<LinkPreviewFetcher> self(this);
    httpGet(req, [self, url, videoId, done = std::move(done)](HttpResponse res) mutable {
        if (!self)
            return;
        if (res.ok) {
            if (auto parsed = parseYouTubeOEmbed(res.body, url, videoId)) {
                done(parsed);
                return;
            }
        }
        // Fallback: direct HTML fetch (2 MB cap handles YouTube's ~850 KB <head>),
        // then deterministic YouTube fallback with guaranteed hqdefault thumbnail.
        self->fetchGeneric(url, [url, videoId, done = std::move(done)](const std::optional<LinkPreviewData> &htmlData) mutable {
            if (htmlData.has_value() && !htmlData->title.isEmpty()) {
                LinkPreviewData merged = *htmlData;
                if (merged.imageUrl.isEmpty())
                    merged.imageUrl = QStringLiteral("https://img.youtube.com/vi/%1/hqdefault.jpg").arg(videoId);
                if (merged.siteName.isEmpty())
                    merged.siteName = QStringLiteral("YouTube");
                if (merged.faviconUrl.isEmpty())
                    merged.faviconUrl = QStringLiteral("https://www.youtube.com/favicon.ico");
                if (merged.ogType.isEmpty())
                    merged.ogType = QStringLiteral("video.other");
                merged.mediaType = LinkMediaType::YouTube;
                merged.hasVideo = true;
                done(merged);
                return;
            }
            const bool isShort = url.path().contains(QLatin1String("/shorts/"), Qt::CaseInsensitive);
            LinkPreviewData fallback;
            fallback.url = url;
            fallback.title = isShort ? QStringLiteral("YouTube Short") : QStringLiteral("YouTube Video");
            fallback.imageUrl = QStringLiteral("https://img.youtube.com/vi/%1/hqdefault.jpg").arg(videoId);
            fallback.siteName = QStringLiteral("YouTube");
            fallback.faviconUrl = QStringLiteral("https://www.youtube.com/favicon.ico");
            fallback.ogType = QStringLiteral("video.other");
            fallback.mediaType = LinkMediaType::YouTube;
            fallback.hasVideo = true;
            done(fallback);
        });
    });
}

void LinkPreviewFetcher::fetchReddit(const QUrl &url, bool allowThirdPartyProxies, PreviewCallback done)
{
    struct RedditState {
        QUrl canonicalUrl;
        bool allowProxies = true;
        bool isVideo = false;
        std::optional<LinkPreviewData> directData;
        std::optional<LinkPreviewData> vxData;
        RedditArchivePost archive;
        PreviewCallback done;
    };

    auto st = std::make_shared<RedditState>();
    st->canonicalUrl = url;
    st->allowProxies = allowThirdPartyProxies;
    st->done = std::move(done);

    auto finalize = [st]() {
        const QString bestImage = firstNonEmpty({
            st->vxData ? cleanImageUrl(st->vxData->imageUrl) : QString(),
            st->directData ? cleanImageUrl(st->directData->imageUrl) : QString(),
            cleanImageUrl(st->archive.imageUrl),
        });
        const QString rawTitle = firstNonBlockedTitle({
            st->vxData ? st->vxData->title : QString(),
            st->directData ? st->directData->title : QString(),
            st->archive.title,
        });
        QString title = cleanRedditTitle(rawTitle);
        if (title.isEmpty()) {
            const QString slug = titleFromUrlSlug(st->canonicalUrl);
            title = !slug.isEmpty() ? slug
                                    : (st->isVideo ? QStringLiteral("Reddit Video")
                                                   : QStringLiteral("Reddit Post"));
        }
        const QString desc = firstNonEmpty({
            st->vxData ? st->vxData->description : QString(),
            st->directData ? st->directData->description : QString(),
            st->archive.description,
        });

        LinkPreviewData out;
        out.url = st->canonicalUrl;
        out.title = title;
        out.description = desc;
        out.imageUrl = bestImage;
        out.siteName = QStringLiteral("Reddit");
        out.faviconUrl = QStringLiteral("https://www.redditstatic.com/shreddit/assets/favicon/192x192.png");
        out.ogType = st->isVideo ? QStringLiteral("video.other") : QStringLiteral("article");
        out.mediaType = st->isVideo ? LinkMediaType::RedditVideo : LinkMediaType::Reddit;
        out.hasVideo = st->isVideo;
        st->done(out);
    };

    QPointer<LinkPreviewFetcher> self(this);

    auto runAfterCanonical = [self, st, finalize](const QUrl &canonical) {
        if (!self)
            return;
        st->canonicalUrl = canonical;
        const QString canonStr = canonical.toString().toLower();
        if (canonStr.contains(QLatin1String("v.redd.it")) || canonStr.contains(QLatin1String("/video/")))
            st->isVideo = true;

        // Step 1: direct HTML fetch
        HttpRequest directReq;
        directReq.url = canonical;
        directReq.headers = ogScraperHeaders();
        self->httpGet(directReq, [self, st, finalize](HttpResponse directRes) {
            if (!self)
                return;
            if (directRes.ok) {
                if (directRes.finalUrl.isValid())
                    st->canonicalUrl = directRes.finalUrl;
                if (directRes.body.contains("og:video"))
                    st->isVideo = true;
                st->directData = parseOpenGraphHtml(directRes.body, st->canonicalUrl, directRes.contentType);
                if (st->directData && st->directData->hasVideo)
                    st->isVideo = true;
            }

            if (!st->allowProxies) {
                finalize();
                return;
            }

            // Step 2: vxreddit embed proxy (bypasses login/Cloudflare walls)
            HttpRequest vxReq;
            vxReq.url = toVxRedditUrl(st->canonicalUrl);
            vxReq.headers = botHeaders();
            vxReq.timeoutMs = 6000;
            self->httpGet(vxReq, [self, st, finalize](HttpResponse vxRes) {
                if (!self)
                    return;
                if (vxRes.ok) {
                    if (vxRes.body.contains("og:video"))
                        st->isVideo = true;
                    st->vxData = parseOpenGraphHtml(vxRes.body, st->canonicalUrl, vxRes.contentType);
                    if (st->vxData && st->vxData->hasVideo)
                        st->isVideo = true;
                }

                const QString candidateImg = firstNonEmpty({
                    st->vxData ? cleanImageUrl(st->vxData->imageUrl) : QString(),
                    st->directData ? cleanImageUrl(st->directData->imageUrl) : QString(),
                });
                const QString postId = extractRedditPostId(st->canonicalUrl);
                if (!candidateImg.isEmpty() || postId.isEmpty()) {
                    finalize();
                    return;
                }

                // Step 3: Arctic Shift archive JSON fallback for missing image/title
                HttpRequest archReq;
                archReq.url = QUrl(QStringLiteral("https://arctic-shift.photon-reddit.com/api/posts/ids?ids=%1").arg(postId));
                archReq.headers = jsonBotHeaders();
                archReq.timeoutMs = 6000;
                archReq.maxBytes = kMaxJsonBytes;
                self->httpGet(archReq, [self, st, finalize](HttpResponse archRes) {
                    if (!self)
                        return;
                    if (archRes.ok) {
                        st->archive = parseRedditArchiveJson(archRes.body);
                        if (st->archive.isVideo)
                            st->isVideo = true;
                    }
                    finalize();
                });
            });
        });
    };

    if (needsRedditRedirectResolution(url))
        resolveRedirects(url, 5, std::move(runAfterCanonical));
    else
        runAfterCanonical(url);
}

void LinkPreviewFetcher::fetchInstagram(const QUrl &url, PreviewCallback done)
{
    struct IgState {
        QUrl canonicalUrl;
        bool isReel = false;
        std::optional<LinkPreviewData> botData;
        QString embedImage;
        PreviewCallback done;
    };

    auto st = std::make_shared<IgState>();
    st->canonicalUrl = url;
    st->done = std::move(done);

    auto finalize = [st]() {
        const QString finalImg = firstNonEmpty({
            st->botData ? cleanImageUrl(st->botData->imageUrl) : QString(),
            cleanImageUrl(st->embedImage),
        });
        const QString username = extractInstagramUsername(st->canonicalUrl);
        QString fallbackTitle;
        if (!username.isEmpty())
            fallbackTitle = st->isReel ? QStringLiteral("Reel by @%1").arg(username)
                                       : QStringLiteral("Post by @%1").arg(username);
        else
            fallbackTitle = st->isReel ? QStringLiteral("Instagram Reel")
                                       : QStringLiteral("Instagram Post");

        const QString rawTitle = firstNonBlockedTitle({
            st->botData ? st->botData->title : QString(),
        });
        QString title = cleanInstagramTitle(!rawTitle.isEmpty() ? rawTitle : fallbackTitle);
        if (title.isEmpty())
            title = fallbackTitle;

        LinkPreviewData out;
        out.url = st->canonicalUrl;
        out.title = title;
        out.description = st->botData ? st->botData->description : QString();
        out.imageUrl = finalImg;
        out.siteName = QStringLiteral("Instagram");
        out.faviconUrl = QStringLiteral("https://www.instagram.com/favicon.ico");
        out.ogType = st->isReel ? QStringLiteral("video.other") : QStringLiteral("article");
        out.mediaType = st->isReel ? LinkMediaType::InstagramReel : LinkMediaType::InstagramPost;
        out.hasVideo = st->isReel;
        st->done(out);
    };

    QPointer<LinkPreviewFetcher> self(this);

    auto runAfterCanonical = [self, st, finalize](const QUrl &canonical) {
        if (!self)
            return;
        st->canonicalUrl = canonical;
        const QString p = canonical.path().toLower();
        st->isReel = p.contains(QLatin1String("/reel/")) || p.contains(QLatin1String("/reels/"));

        // Step 1: Meta social crawler UA on canonical URL
        HttpRequest botReq;
        botReq.url = canonical;
        botReq.headers = socialCrawlerHeaders();
        self->httpGet(botReq, [self, st, finalize](HttpResponse botRes) {
            if (!self)
                return;
            if (botRes.ok) {
                if (botRes.finalUrl.isValid()) {
                    st->canonicalUrl = botRes.finalUrl;
                    const QString p = st->canonicalUrl.path().toLower();
                    if (p.contains(QLatin1String("/reel/")) || p.contains(QLatin1String("/reels/")))
                        st->isReel = true;
                }
                st->botData = parseOpenGraphHtml(botRes.body, st->canonicalUrl, botRes.contentType);
            }

            const QString botImg = st->botData ? cleanImageUrl(st->botData->imageUrl) : QString();
            const QString shortcode = extractInstagramShortcode(st->canonicalUrl);
            if (!botImg.isEmpty() || shortcode.isEmpty()) {
                finalize();
                return;
            }

            // Step 2: Instagram official public embed endpoint /p/<shortcode>/embed/captioned/
            HttpRequest embedReq;
            embedReq.url = QUrl(QStringLiteral("https://www.instagram.com/p/%1/embed/captioned/").arg(shortcode));
            embedReq.headers = ogScraperHeaders();
            embedReq.timeoutMs = 6000;
            self->httpGet(embedReq, [self, st, embedUrl = embedReq.url, finalize](HttpResponse embedRes) {
                if (!self)
                    return;
                if (embedRes.ok)
                    st->embedImage = extractInstagramEmbedImage(embedRes.body, embedUrl);
                finalize();
            });
        });
    };

    if (needsInstagramRedirectResolution(url))
        resolveRedirects(url, 5, std::move(runAfterCanonical));
    else
        runAfterCanonical(url);
}

void LinkPreviewFetcher::fetchFacebook(const QUrl &url, PreviewCallback done)
{
    QPointer<LinkPreviewFetcher> self(this);

    auto runAfterCanonical = [self, done = std::move(done)](const QUrl &canonical) mutable {
        if (!self)
            return;
        HttpRequest req;
        req.url = canonical;
        req.headers = ogScraperHeaders();
        self->httpGet(req, [self, canonical, done = std::move(done)](HttpResponse res) mutable {
            if (!self)
                return;
            const QUrl finalUrl = (res.ok && res.finalUrl.isValid()) ? res.finalUrl : canonical;
            std::optional<LinkPreviewData> data;
            if (res.ok)
                data = parseOpenGraphHtml(res.body, finalUrl, res.contentType);

            const QString urlLower = finalUrl.toString().toLower();
            const bool isVideo = urlLower.contains(QLatin1String("/watch"))
                || urlLower.contains(QLatin1String("/reel"))
                || urlLower.contains(QLatin1String("fb.watch"))
                || (data && (data->mediaType == LinkMediaType::FacebookVideo || data->hasVideo))
                || (res.ok && res.body.contains("og:type\" content=\"video"));

            const QString rawTitle = firstNonBlockedTitle({ data ? data->title : QString() });
            QString title = cleanFacebookTitle(rawTitle);
            if (title.isEmpty()) {
                const QString slug = titleFromUrlSlug(finalUrl);
                title = !slug.isEmpty() ? slug
                                        : (isVideo ? QStringLiteral("Facebook Video")
                                                   : QStringLiteral("Facebook Post"));
            }

            LinkPreviewData out;
            out.url = finalUrl;
            out.title = title;
            out.description = data ? data->description : QString();
            out.imageUrl = data ? cleanImageUrl(data->imageUrl) : QString();
            out.siteName = QStringLiteral("Facebook");
            out.faviconUrl = QStringLiteral("https://static.xx.fbcdn.net/rsrc.php/y1/r/ay1hV6OlegS.ico");
            out.ogType = isVideo ? QStringLiteral("video.other") : QStringLiteral("article");
            out.mediaType = isVideo ? LinkMediaType::FacebookVideo : LinkMediaType::Facebook;
            out.hasVideo = isVideo;
            done(out);
        });
    };

    if (needsFacebookRedirectResolution(url))
        resolveRedirects(url, 5, std::move(runAfterCanonical));
    else
        runAfterCanonical(url);
}

void LinkPreviewFetcher::fetchTwitter(const QUrl &url, bool allowThirdPartyProxies, PreviewCallback done)
{
    QPointer<LinkPreviewFetcher> self(this);
    auto doneShared = std::make_shared<PreviewCallback>(std::move(done));

    auto runDirectFallback = [self, url, doneShared]() {
        if (!self)
            return;
        HttpRequest req;
        req.url = url;
        req.headers = socialCrawlerHeaders();
        self->httpGet(req, [self, url, doneShared](HttpResponse res) {
            if (!self)
                return;
            std::optional<LinkPreviewData> directData;
            if (res.ok)
                directData = parseOpenGraphHtml(res.body, url, res.contentType);

            QString fallbackTitle = QStringLiteral("Post on X");
            const QStringList segs = url.path().split(QLatin1Char('/'), Qt::SkipEmptyParts);
            static const QStringList reserved = {
                QStringLiteral("i"), QStringLiteral("home"),
                QStringLiteral("explore"), QStringLiteral("messages"),
            };
            if (!segs.isEmpty() && !reserved.contains(segs.first().toLower()))
                fallbackTitle = QStringLiteral("Post on X by @%1").arg(segs.first());

            const QString title = firstNonBlockedTitle({ directData ? directData->title : QString() });
            LinkPreviewData out;
            out.url = url;
            out.title = !title.isEmpty() ? title : fallbackTitle;
            out.description = directData ? directData->description : QString();
            out.imageUrl = directData ? cleanImageUrl(directData->imageUrl) : QString();
            out.siteName = QStringLiteral("X (Twitter)");
            out.faviconUrl = QStringLiteral("https://abs.twimg.com/favicons/twitter.3.ico");
            out.ogType = QStringLiteral("article");
            out.mediaType = LinkMediaType::Twitter;
            (*doneShared)(out);
        });
    };

    if (!allowThirdPartyProxies) {
        runDirectFallback();
        return;
    }

    auto runFxFallback = [self, url, doneShared, runDirectFallback]() {
        if (!self)
            return;
        HttpRequest fxReq;
        fxReq.url = toFxTwitterUrl(url);
        fxReq.headers = botHeaders();
        fxReq.timeoutMs = 6000;
        self->httpGet(fxReq, [self, url, doneShared, runDirectFallback](HttpResponse fxRes) {
            if (!self)
                return;
            if (fxRes.ok) {
                if (auto parsed = parseOpenGraphHtml(fxRes.body, url, fxRes.contentType)) {
                    if (!parsed->title.isEmpty() && !isBlockedTitle(parsed->title)) {
                        parsed->url = url;
                        parsed->siteName = QStringLiteral("X (Twitter)");
                        parsed->faviconUrl = QStringLiteral("https://abs.twimg.com/favicons/twitter.3.ico");
                        parsed->mediaType = LinkMediaType::Twitter;
                        (*doneShared)(parsed);
                        return;
                    }
                }
            }
            runDirectFallback();
        });
    };

    const TwitterStatusRef status = extractTwitterStatus(url);
    if (!status.isValid()) {
        runFxFallback();
        return;
    }

    HttpRequest apiReq;
    apiReq.url = QUrl(QStringLiteral("https://api.vxtwitter.com/%1/status/%2")
                          .arg(status.user, status.statusId));
    apiReq.headers = jsonBotHeaders();
    apiReq.timeoutMs = 6000;
    apiReq.maxBytes = kMaxJsonBytes;
    httpGet(apiReq, [self, url, status, doneShared, runFxFallback](HttpResponse apiRes) {
        if (!self)
            return;
        if (apiRes.ok) {
            if (auto parsed = parseVxTwitterJson(apiRes.body, url, status.user)) {
                (*doneShared)(parsed);
                return;
            }
        }
        runFxFallback();
    });
}

void LinkPreviewFetcher::fetchTikTok(const QUrl &url, PreviewCallback done)
{
    HttpRequest req;
    const QString encoded = QString::fromLatin1(QUrl::toPercentEncoding(url.toString()));
    req.url = QUrl(QStringLiteral("https://www.tiktok.com/oembed?url=%1").arg(encoded));
    req.headers = jsonBotHeaders();
    req.timeoutMs = 6000;
    req.maxBytes = kMaxJsonBytes;

    QPointer<LinkPreviewFetcher> self(this);
    httpGet(req, [self, url, done = std::move(done)](HttpResponse res) mutable {
        if (!self)
            return;
        if (res.ok) {
            if (auto parsed = parseTikTokOEmbed(res.body, url)) {
                done(parsed);
                return;
            }
        }
        self->fetchGeneric(url, std::move(done));
    });
}

void LinkPreviewFetcher::fetchGeneric(const QUrl &url, PreviewCallback done)
{
    HttpRequest req;
    req.url = url;
    req.headers = ogScraperHeaders();
    req.timeoutMs = 8000;
    req.maxBytes = 2 * 1024 * 1024;
    req.followRedirects = true;

    QPointer<LinkPreviewFetcher> self(this);
    httpGet(req, [self, url, done = std::move(done)](HttpResponse res) mutable {
        if (!self)
            return;
        if (!res.ok || res.body.isEmpty()) {
            done(std::nullopt);
            return;
        }
        const QUrl finalUrl = res.finalUrl.isValid() ? res.finalUrl : url;
        done(parseOpenGraphHtml(res.body, finalUrl, res.contentType));
    });
}

} // namespace pkm::linkpreview
