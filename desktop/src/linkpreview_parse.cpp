// LinkPreview pure parsing, classification, cleaning, and WebLink update heuristics.
// Zero network or Workspace dependencies — fully deterministic and unit-testable.
#include "linkpreview.h"

#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QRegularExpression>
#include <QUrlQuery>

namespace pkm::linkpreview {

namespace {

constexpr qint64 kMaxHtmlParseBytes = 2 * 1024 * 1024;

bool hostMatches(const QString &hostLower, QLatin1String domain)
{
    if (hostLower == domain)
        return true;
    return hostLower.size() > domain.size()
        && hostLower.endsWith(domain)
        && hostLower.at(hostLower.size() - domain.size() - 1) == QLatin1Char('.');
}

QString decodeHtmlBytes(const QByteArray &rawBytes, const QString &contentType)
{
    const QByteArray slice = rawBytes.left(kMaxHtmlParseBytes);
    QString charset;
    static const QRegularExpression charsetRe(
        QStringLiteral("charset\\s*=\\s*\"?([A-Za-z0-9_\\-]+)\"?"),
        QRegularExpression::CaseInsensitiveOption);
    if (!contentType.isEmpty()) {
        const auto m = charsetRe.match(contentType);
        if (m.hasMatch())
            charset = m.captured(1).trimmed().toLower();
    }
    if (charset.isEmpty()) {
        const QString probe = QString::fromLatin1(slice.left(4096));
        const auto m = charsetRe.match(probe);
        if (m.hasMatch())
            charset = m.captured(1).trimmed().toLower();
    }
    if (charset == QLatin1String("iso-8859-1")
        || charset == QLatin1String("latin1")
        || charset == QLatin1String("latin-1")
        || charset == QLatin1String("windows-1252")
        || charset == QLatin1String("cp1252")) {
        return QString::fromLatin1(slice);
    }
    return QString::fromUtf8(slice);
}

QHash<QString, QString> parseTagAttributes(const QString &tagBody)
{
    static const QRegularExpression attrRe(
        QStringLiteral("([a-zA-Z0-9_:-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s/>]+))"));
    QHash<QString, QString> attrs;
    auto it = attrRe.globalMatch(tagBody);
    while (it.hasNext()) {
        const auto m = it.next();
        const QString k = m.captured(1).toLower();
        QString v = m.captured(2);
        if (v.isEmpty())
            v = m.captured(3);
        if (v.isEmpty())
            v = m.captured(4);
        attrs.insert(k, v);
    }
    return attrs;
}

QString resolveRelativeUrl(const QUrl &baseUrl, const QString &target)
{
    const QString cleaned = cleanImageUrl(target);
    if (cleaned.isEmpty())
        return {};
    const QUrl resolved = baseUrl.resolved(QUrl(cleaned));
    if (!resolved.isValid())
        return {};
    if (resolved.scheme() != QLatin1String("http") && resolved.scheme() != QLatin1String("https"))
        return {};
    return resolved.toString();
}

bool isRedditImageUrl(const QString &u)
{
    const QString l = u.toLower();
    return l.contains(QLatin1String("i.redd.it"))
        || l.contains(QLatin1String("preview.redd.it"))
        || l.contains(QLatin1String("external-preview.redd.it"))
        || l.endsWith(QLatin1String(".jpg"))
        || l.endsWith(QLatin1String(".jpeg"))
        || l.endsWith(QLatin1String(".png"))
        || l.endsWith(QLatin1String(".webp"));
}

QString extractRedditArchiveImage(const QJsonObject &post)
{
    const QString direct = cleanImageUrl(post.value(QLatin1String("url_overridden_by_dest")).toString());
    if (!direct.isEmpty() && isRedditImageUrl(direct))
        return direct;

    const QString url = cleanImageUrl(post.value(QLatin1String("url")).toString());
    if (!url.isEmpty() && isRedditImageUrl(url))
        return url;

    const QJsonArray images = post.value(QLatin1String("preview"))
                                  .toObject()
                                  .value(QLatin1String("images"))
                                  .toArray();
    if (!images.isEmpty()) {
        const QString previewUrl = cleanImageUrl(
            images.first().toObject().value(QLatin1String("source")).toObject().value(QLatin1String("url")).toString());
        if (!previewUrl.isEmpty())
            return previewUrl;
    }

    const QString thumb = cleanImageUrl(post.value(QLatin1String("thumbnail")).toString());
    if (thumb.startsWith(QLatin1String("http://"), Qt::CaseInsensitive)
        || thumb.startsWith(QLatin1String("https://"), Qt::CaseInsensitive)) {
        return thumb;
    }
    return {};
}

} // namespace

QUrl normalizeUrl(const QString &raw)
{
    QString trimmed = raw.trimmed();
    if (trimmed.isEmpty())
        return {};
    if (!trimmed.startsWith(QLatin1String("http://"), Qt::CaseInsensitive)
        && !trimmed.startsWith(QLatin1String("https://"), Qt::CaseInsensitive)) {
        if (trimmed.contains(QLatin1String("://")))
            return {};
        trimmed.prepend(QStringLiteral("https://"));
    }
    const QUrl u(trimmed);
    if (!u.isValid() || u.host().trimmed().isEmpty())
        return {};
    const QString scheme = u.scheme().toLower();
    if (scheme != QLatin1String("http") && scheme != QLatin1String("https"))
        return {};
    return u;
}

QString bareHost(const QUrl &url)
{
    QString h = url.host().toLower();
    if (h.startsWith(QLatin1String("www.")))
        h = h.mid(4);
    return h;
}

QString cleanImageUrl(const QString &url)
{
    QString s = url.trimmed();
    if (s.isEmpty())
        return {};
    s.replace(QLatin1String("&amp;"), QLatin1String("&"));
    s.replace(QLatin1String("\\/"), QLatin1String("/"));
    s.replace(QLatin1String("&quot;"), QString());
    return s.trimmed();
}

QString decodeHtmlEntities(const QString &text)
{
    if (text.isEmpty())
        return {};

    static const QRegularExpression entityRe(
        QStringLiteral("&(?:#x([0-9a-fA-F]+)|#(\\d+)|([a-zA-Z]+));"));

    QString out;
    out.reserve(text.size());
    qsizetype lastPos = 0;
    auto it = entityRe.globalMatch(text);
    while (it.hasNext()) {
        const auto m = it.next();
        out.append(text.mid(lastPos, m.capturedStart() - lastPos));
        lastPos = m.capturedEnd();

        QString replacement;
        if (!m.captured(1).isEmpty()) {
            bool ok = false;
            const uint cp = m.captured(1).toUInt(&ok, 16);
            if (ok && cp > 0 && cp <= 0x10FFFF) {
                if (cp == 0xA0)
                    replacement = QStringLiteral(" ");
                else
                    replacement = QString::fromUcs4(reinterpret_cast<const char32_t *>(&cp), 1);
            }
        } else if (!m.captured(2).isEmpty()) {
            bool ok = false;
            const uint cp = m.captured(2).toUInt(&ok, 10);
            if (ok && cp > 0 && cp <= 0x10FFFF) {
                if (cp == 160)
                    replacement = QStringLiteral(" ");
                else
                    replacement = QString::fromUcs4(reinterpret_cast<const char32_t *>(&cp), 1);
            }
        } else {
            const QString name = m.captured(3).toLower();
            if (name == QLatin1String("amp")) replacement = QStringLiteral("&");
            else if (name == QLatin1String("lt")) replacement = QStringLiteral("<");
            else if (name == QLatin1String("gt")) replacement = QStringLiteral(">");
            else if (name == QLatin1String("quot")) replacement = QStringLiteral("\"");
            else if (name == QLatin1String("apos")) replacement = QStringLiteral("'");
            else if (name == QLatin1String("nbsp")) replacement = QStringLiteral(" ");
            else if (name == QLatin1String("mdash")) replacement = QStringLiteral("—");
            else if (name == QLatin1String("ndash")) replacement = QStringLiteral("–");
            else if (name == QLatin1String("hellip")) replacement = QStringLiteral("…");
            else if (name == QLatin1String("bull")) replacement = QStringLiteral("•");
            else if (name == QLatin1String("copy")) replacement = QStringLiteral("©");
            else if (name == QLatin1String("reg")) replacement = QStringLiteral("®");
            else if (name == QLatin1String("trade")) replacement = QStringLiteral("™");
            else if (name == QLatin1String("euro")) replacement = QStringLiteral("€");
            else if (name == QLatin1String("laquo")) replacement = QStringLiteral("«");
            else if (name == QLatin1String("raquo")) replacement = QStringLiteral("»");
            else if (name == QLatin1String("lsquo")) replacement = QStringLiteral("‘");
            else if (name == QLatin1String("rsquo")) replacement = QStringLiteral("’");
            else if (name == QLatin1String("ldquo")) replacement = QStringLiteral("“");
            else if (name == QLatin1String("rdquo")) replacement = QStringLiteral("”");
        }

        if (!replacement.isEmpty())
            out.append(replacement);
        else
            out.append(m.captured(0));
    }
    out.append(text.mid(lastPos));
    return out.simplified();
}

QString extractYouTubeVideoId(const QUrl &url)
{
    if (!url.isValid())
        return {};
    const QString host = url.host().toLower();
    static const QRegularExpression idRe(QStringLiteral("^[A-Za-z0-9_-]{11}$"));

    const QStringList segs = url.path().split(QLatin1Char('/'), Qt::SkipEmptyParts);
    if (hostMatches(host, QLatin1String("youtu.be"))) {
        if (!segs.isEmpty() && idRe.match(segs.first()).hasMatch())
            return segs.first();
        return {};
    }
    if (hostMatches(host, QLatin1String("youtube.com"))
        || hostMatches(host, QLatin1String("youtube-nocookie.com"))) {
        if (!segs.isEmpty() && segs.first().compare(QLatin1String("watch"), Qt::CaseInsensitive) == 0) {
            const QString v = QUrlQuery(url).queryItemValue(QStringLiteral("v"));
            if (idRe.match(v).hasMatch())
                return v;
            return {};
        }
        if (segs.size() >= 2) {
            const QString head = segs.at(0).toLower();
            if ((head == QLatin1String("shorts") || head == QLatin1String("embed")
                 || head == QLatin1String("live") || head == QLatin1String("v"))
                && idRe.match(segs.at(1)).hasMatch()) {
                return segs.at(1);
            }
        }
    }
    return {};
}

bool isReddit(const QUrl &url)
{
    const QString h = url.host().toLower();
    return hostMatches(h, QLatin1String("reddit.com")) || hostMatches(h, QLatin1String("redd.it"));
}

QUrl toVxRedditUrl(const QUrl &url)
{
    QUrl out = url;
    out.setScheme(QStringLiteral("https"));
    const QString host = url.host().toLower();
    if (host == QLatin1String("reddit.com") || host == QLatin1String("www.reddit.com")
        || host == QLatin1String("redd.it") || host == QLatin1String("v.redd.it")) {
        out.setHost(QStringLiteral("www.vxreddit.com"));
    } else if (host.endsWith(QLatin1String(".reddit.com"))) {
        QString sub = host;
        sub.replace(QLatin1String("reddit.com"), QLatin1String("vxreddit.com"));
        out.setHost(sub);
    }
    return out;
}

QString extractRedditPostId(const QUrl &url)
{
    const QStringList segs = url.path().split(QLatin1Char('/'), Qt::SkipEmptyParts);
    static const QRegularExpression idRe(QStringLiteral("^[A-Za-z0-9_]+$"));
    static const QRegularExpression shortIdRe(QStringLiteral("^[A-Za-z0-9_]{5,10}$"));
    const int idx = segs.indexOf(QStringLiteral("comments"));
    if (idx >= 0 && segs.size() > idx + 1 && idRe.match(segs.at(idx + 1)).hasMatch())
        return segs.at(idx + 1);
    if (!segs.isEmpty() && shortIdRe.match(segs.last()).hasMatch())
        return segs.last();
    return {};
}

bool isInstagram(const QUrl &url)
{
    const QString h = url.host().toLower();
    return hostMatches(h, QLatin1String("instagram.com")) || hostMatches(h, QLatin1String("instagr.am"));
}

QString extractInstagramShortcode(const QUrl &url)
{
    static const QRegularExpression re(
        QStringLiteral("/(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)"),
        QRegularExpression::CaseInsensitiveOption);
    const auto m = re.match(url.path());
    return m.hasMatch() ? m.captured(1) : QString();
}

QString extractInstagramUsername(const QUrl &url)
{
    const QStringList segs = url.path().split(QLatin1Char('/'), Qt::SkipEmptyParts);
    static const QStringList reserved = {
        QStringLiteral("p"), QStringLiteral("reel"), QStringLiteral("reels"),
        QStringLiteral("tv"), QStringLiteral("explore"), QStringLiteral("stories"),
        QStringLiteral("accounts"), QStringLiteral("share"),
    };
    for (const QString &seg : segs) {
        if (!reserved.contains(seg.toLower()))
            return seg;
    }
    return {};
}

bool isFacebook(const QUrl &url)
{
    const QString h = url.host().toLower();
    return hostMatches(h, QLatin1String("facebook.com"))
        || hostMatches(h, QLatin1String("fb.watch"))
        || hostMatches(h, QLatin1String("fb.me"))
        || hostMatches(h, QLatin1String("fb.com"));
}

bool isTwitterOrX(const QUrl &url)
{
    const QString h = url.host().toLower();
    return hostMatches(h, QLatin1String("twitter.com")) || hostMatches(h, QLatin1String("x.com"));
}

TwitterStatusRef extractTwitterStatus(const QUrl &url)
{
    if (!isTwitterOrX(url))
        return {};
    static const QRegularExpression re(
        QStringLiteral("^/([^/]+)/status/([0-9]+)"),
        QRegularExpression::CaseInsensitiveOption);
    const auto m = re.match(url.path());
    if (!m.hasMatch())
        return {};
    return { m.captured(1), m.captured(2) };
}

QUrl toFxTwitterUrl(const QUrl &url)
{
    QUrl out = url;
    out.setScheme(QStringLiteral("https"));
    const QString h = url.host().toLower();
    if (hostMatches(h, QLatin1String("x.com")))
        out.setHost(QStringLiteral("fixupx.com"));
    else if (hostMatches(h, QLatin1String("twitter.com")))
        out.setHost(QStringLiteral("fxtwitter.com"));
    return out;
}

bool isTikTok(const QUrl &url)
{
    return hostMatches(url.host().toLower(), QLatin1String("tiktok.com"));
}

bool isBlockedTitle(const QString &title)
{
    const QString lower = title.trimmed().toLower();
    if (lower.isEmpty())
        return true;
    static const QStringList exactBlocked = {
        QStringLiteral("shared link"),
        QStringLiteral("untitled"),
        QStringLiteral("sin título"),
        QStringLiteral("reddit"),
        QStringLiteral("reddit - dive into anything"),
        QStringLiteral("reddit - the heart of the internet"),
        QStringLiteral("instagram"),
        QStringLiteral("facebook"),
        QStringLiteral("x"),
        QStringLiteral("twitter"),
        QStringLiteral("youtube"),
        QStringLiteral("- youtube"),
        QStringLiteral("tiktok"),
        QStringLiteral("login"),
        QStringLiteral("log in"),
    };
    if (exactBlocked.contains(lower))
        return true;

    return lower.startsWith(QStringLiteral("login •"))
        || lower.startsWith(QStringLiteral("log in •"))
        || lower.startsWith(QLatin1String("log in to "))
        || lower.startsWith(QLatin1String("login to "))
        || lower.contains(QLatin1String("please wait for verification"))
        || lower.contains(QLatin1String("cloudflare"))
        || lower.contains(QLatin1String("attention required"))
        || lower.contains(QLatin1String("just a moment"))
        || lower.contains(QLatin1String("internal server error"))
        || lower.contains(QLatin1String("500 internal"))
        || lower.contains(QLatin1String("404 not found"))
        || lower.contains(QLatin1String("access denied"))
        || lower.contains(QLatin1String("security check"));
}

QString cleanRedditTitle(const QString &raw)
{
    QString result = raw.trimmed();
    static const QRegularExpression prefixRe(
        QStringLiteral("^Check out this post on Reddit:\\s*"),
        QRegularExpression::CaseInsensitiveOption);
    static const QRegularExpression suffixRedditRe(
        QStringLiteral("\\s*[-–|]\\s*Reddit\\s*$"),
        QRegularExpression::CaseInsensitiveOption);
    static const QRegularExpression suffixColonSubRe(
        QStringLiteral("\\s*:\\s*r/[a-zA-Z0-9_]+\\s*$"),
        QRegularExpression::CaseInsensitiveOption);
    static const QRegularExpression suffixSubRe(
        QStringLiteral("\\s*r/[a-zA-Z0-9_]+\\s*$"),
        QRegularExpression::CaseInsensitiveOption);
    static const QRegularExpression trailingDashRe(QStringLiteral("\\s*-\\s*$"));

    result.remove(prefixRe);
    for (int i = 0; i < 3; ++i) {
        result.remove(suffixRedditRe);
        result.remove(suffixColonSubRe);
        result.remove(suffixSubRe);
        result.remove(trailingDashRe);
        result = result.trimmed();
    }
    return result;
}

QString cleanInstagramTitle(const QString &raw)
{
    QString result = raw.trimmed();
    static const QRegularExpression photosRe(
        QStringLiteral("\\s*•\\s*Instagram photos and videos\\s*$"),
        QRegularExpression::CaseInsensitiveOption);
    static const QRegularExpression onIgRe(
        QStringLiteral("\\s*on Instagram:.*$"),
        QRegularExpression::CaseInsensitiveOption);
    static const QRegularExpression trailingDashRe(QStringLiteral("\\s*-\\s*$"));

    result.remove(photosRe);
    result.remove(onIgRe);
    result.remove(trailingDashRe);
    return result.trimmed();
}

QString cleanFacebookTitle(const QString &raw)
{
    QString result = raw.trimmed();
    static const QRegularExpression checkOutRe(
        QStringLiteral("^Check out this post on Facebook:\\s*"),
        QRegularExpression::CaseInsensitiveOption);
    static const QRegularExpression sharedRe(
        QStringLiteral("^Shared with Facebook:\\s*"),
        QRegularExpression::CaseInsensitiveOption);
    static const QRegularExpression dashFbRe(
        QStringLiteral("\\s*-\\s*Facebook\\s*$"),
        QRegularExpression::CaseInsensitiveOption);
    static const QRegularExpression pipeFbRe(
        QStringLiteral("\\s*\\|\\s*Facebook\\s*$"),
        QRegularExpression::CaseInsensitiveOption);
    static const QRegularExpression trailingDashRe(QStringLiteral("\\s*-\\s*$"));

    result.remove(checkOutRe);
    result.remove(sharedRe);
    result.remove(dashFbRe);
    result.remove(pipeFbRe);
    result.remove(trailingDashRe);
    return result.trimmed();
}

QString titleFromUrlSlug(const QUrl &url)
{
    const QStringList segs = url.path().split(QLatin1Char('/'), Qt::SkipEmptyParts);
    if (segs.isEmpty())
        return {};

    QString slug;
    const int commentsIdx = segs.indexOf(QStringLiteral("comments"));
    if (commentsIdx >= 0 && segs.size() > commentsIdx + 2) {
        slug = segs.at(commentsIdx + 2);
    } else {
        for (int i = segs.size() - 1; i >= 0; --i) {
            const QString &seg = segs.at(i);
            bool hasLetter = false;
            for (const QChar c : seg) {
                if (c.isLetter()) {
                    hasLetter = true;
                    break;
                }
            }
            if (seg.contains(QLatin1Char('-')) || seg.contains(QLatin1Char('_'))
                || (seg.size() > 8 && hasLetter)) {
                slug = seg;
                break;
            }
        }
        if (slug.isEmpty())
            slug = segs.last();
    }

    QString decoded = QUrl::fromPercentEncoding(slug.toUtf8());
    static const QRegularExpression sepRe(QStringLiteral("[-_]+"));
    decoded.replace(sepRe, QStringLiteral(" "));
    const QStringList words = decoded.split(QLatin1Char(' '), Qt::SkipEmptyParts);
    QStringList titled;
    titled.reserve(words.size());
    for (QString w : words) {
        if (!w.isEmpty() && w.at(0).isLower())
            w[0] = w.at(0).toUpper();
        titled.append(w);
    }
    return titled.join(QLatin1Char(' '));
}

std::optional<LinkPreviewData> parseOpenGraphHtml(const QByteArray &rawBytes,
                                                  const QUrl &baseUrl,
                                                  const QString &contentType)
{
    if (rawBytes.isEmpty())
        return std::nullopt;

    const QString html = decodeHtmlBytes(rawBytes, contentType);
    QString ogTitle;
    QString ogDesc;
    QString ogImage;
    QString ogSiteName;
    QString ogType;
    QString metaDesc;
    QString htmlTitle;
    QString faviconUrl;
    QString maskIconFallback;
    bool hasOgVideo = false;

    static const QRegularExpression metaTagRe(
        QStringLiteral("<meta\\b([^>]*)>"),
        QRegularExpression::CaseInsensitiveOption | QRegularExpression::DotMatchesEverythingOption);

    auto mIt = metaTagRe.globalMatch(html);
    while (mIt.hasNext()) {
        const auto attrs = parseTagAttributes(mIt.next().captured(1));
        QString key = attrs.value(QStringLiteral("property")).toLower();
        if (key.isEmpty())
            key = attrs.value(QStringLiteral("name")).toLower();
        const QString rawContent = attrs.value(QStringLiteral("content")).trimmed();
        if (key.isEmpty() || rawContent.isEmpty())
            continue;

        if ((key == QLatin1String("og:title") || key == QLatin1String("twitter:title")) && ogTitle.isEmpty()) {
            ogTitle = decodeHtmlEntities(rawContent);
        } else if ((key == QLatin1String("og:description") || key == QLatin1String("twitter:description")) && ogDesc.isEmpty()) {
            ogDesc = decodeHtmlEntities(rawContent);
        } else if (key == QLatin1String("description") && metaDesc.isEmpty()) {
            metaDesc = decodeHtmlEntities(rawContent);
        } else if ((key == QLatin1String("og:image") || key == QLatin1String("og:image:url")
                    || key == QLatin1String("og:image:secure_url") || key == QLatin1String("twitter:image")
                    || key == QLatin1String("twitter:image:src"))
                   && ogImage.isEmpty()) {
            ogImage = cleanImageUrl(rawContent);
        } else if (key == QLatin1String("og:type") && ogType.isEmpty()) {
            ogType = rawContent.toLower();
        } else if (key == QLatin1String("og:site_name") && ogSiteName.isEmpty()) {
            ogSiteName = decodeHtmlEntities(rawContent);
        } else if (key == QLatin1String("og:video") || key == QLatin1String("og:video:url")
                   || key == QLatin1String("og:video:secure_url")) {
            hasOgVideo = true;
        }
    }

    static const QRegularExpression linkTagRe(
        QStringLiteral("<link\\b([^>]*)>"),
        QRegularExpression::CaseInsensitiveOption | QRegularExpression::DotMatchesEverythingOption);
    auto lIt = linkTagRe.globalMatch(html);
    while (lIt.hasNext()) {
        const auto attrs = parseTagAttributes(lIt.next().captured(1));
        const QString rel = attrs.value(QStringLiteral("rel")).toLower();
        const QString href = attrs.value(QStringLiteral("href")).trimmed();
        if (href.isEmpty())
            continue;
        if (ogImage.isEmpty() && rel == QLatin1String("image_src"))
            ogImage = cleanImageUrl(href);
        if (rel.contains(QLatin1String("icon"))) {
            const QString resolved = resolveRelativeUrl(baseUrl, decodeHtmlEntities(href));
            if (!resolved.isEmpty()) {
                if (rel.contains(QLatin1String("mask-icon"))) {
                    if (maskIconFallback.isEmpty())
                        maskIconFallback = resolved;
                } else if (faviconUrl.isEmpty()) {
                    faviconUrl = resolved;
                }
            }
        }
    }
    if (faviconUrl.isEmpty())
        faviconUrl = maskIconFallback;
    if (faviconUrl.isEmpty() && baseUrl.isValid() && !baseUrl.host().isEmpty())
        faviconUrl = QStringLiteral("%1://%2/favicon.ico").arg(baseUrl.scheme(), baseUrl.host());

    if (ogImage.isEmpty()) {
        static const QRegularExpression videoPosterRe(
            QStringLiteral("<video\\b[^>]*\\bposter=[\"']([^\"']+)[\"']"),
            QRegularExpression::CaseInsensitiveOption);
        const auto pm = videoPosterRe.match(html);
        if (pm.hasMatch())
            ogImage = cleanImageUrl(pm.captured(1));
    }

    static const QRegularExpression titleTagRe(
        QStringLiteral("<title\\b[^>]*>(.*?)</title>"),
        QRegularExpression::CaseInsensitiveOption | QRegularExpression::DotMatchesEverythingOption);
    const auto tm = titleTagRe.match(html);
    if (tm.hasMatch())
        htmlTitle = decodeHtmlEntities(tm.captured(1));

    QString rawTitle = !ogTitle.isEmpty() ? ogTitle : (!htmlTitle.isEmpty() ? htmlTitle : ogSiteName);
    QString candidateTitle;
    if (!isBlockedTitle(rawTitle)) {
        candidateTitle = rawTitle;
    } else {
        const QString slugTitle = titleFromUrlSlug(baseUrl);
        if (!slugTitle.isEmpty() && !isBlockedTitle(slugTitle))
            candidateTitle = slugTitle;
        else if (!isBlockedTitle(ogSiteName))
            candidateTitle = ogSiteName;
    }

    QString finalTitle = candidateTitle;
    if (!finalTitle.isEmpty()) {
        if (isReddit(baseUrl))
            finalTitle = cleanRedditTitle(finalTitle);
        else if (isFacebook(baseUrl))
            finalTitle = cleanFacebookTitle(finalTitle);
        else if (isInstagram(baseUrl))
            finalTitle = cleanInstagramTitle(finalTitle);
    }

    const QString description = !ogDesc.isEmpty() ? ogDesc : metaDesc;
    const QString finalImage = resolveRelativeUrl(baseUrl, ogImage);

    if (finalTitle.isEmpty() && description.isEmpty() && finalImage.isEmpty())
        return std::nullopt;

    const bool isVideo = ogType.contains(QLatin1String("video")) || hasOgVideo;
    const QString pathLower = baseUrl.path().toLower();
    LinkMediaType mediaType = LinkMediaType::Generic;
    if (!extractYouTubeVideoId(baseUrl).isEmpty())
        mediaType = LinkMediaType::YouTube;
    else if (isFacebook(baseUrl))
        mediaType = isVideo ? LinkMediaType::FacebookVideo : LinkMediaType::Facebook;
    else if (isReddit(baseUrl))
        mediaType = isVideo ? LinkMediaType::RedditVideo : LinkMediaType::Reddit;
    else if (isInstagram(baseUrl))
        mediaType = (pathLower.contains(QLatin1String("/reel"))) ? LinkMediaType::InstagramReel : LinkMediaType::InstagramPost;
    else if (isTwitterOrX(baseUrl))
        mediaType = LinkMediaType::Twitter;
    else if (isTikTok(baseUrl))
        mediaType = LinkMediaType::TikTok;

    LinkPreviewData out;
    out.url = baseUrl;
    out.title = finalTitle;
    out.description = description;
    out.imageUrl = finalImage;
    out.siteName = ogSiteName;
    out.faviconUrl = faviconUrl;
    out.ogType = ogType;
    out.mediaType = mediaType;
    out.hasVideo = isVideo;
    return out;
}

std::optional<LinkPreviewData> parseYouTubeOEmbed(const QByteArray &jsonBytes,
                                                  const QUrl &originalUrl,
                                                  const QString &videoId)
{
    const QJsonDocument doc = QJsonDocument::fromJson(jsonBytes);
    if (!doc.isObject())
        return std::nullopt;
    const QJsonObject obj = doc.object();
    const QString rawTitle = decodeHtmlEntities(obj.value(QLatin1String("title")).toString());
    const QString author = decodeHtmlEntities(obj.value(QLatin1String("author_name")).toString());
    if (rawTitle.isEmpty() && author.isEmpty())
        return std::nullopt;

    const bool isShort = originalUrl.path().contains(QLatin1String("/shorts/"), Qt::CaseInsensitive);
    QString thumb = cleanImageUrl(obj.value(QLatin1String("thumbnail_url")).toString());
    if (thumb.isEmpty() && !videoId.isEmpty())
        thumb = QStringLiteral("https://img.youtube.com/vi/%1/hqdefault.jpg").arg(videoId);

    LinkPreviewData out;
    out.url = originalUrl;
    out.title = !rawTitle.isEmpty()
        ? rawTitle
        : (isShort ? QStringLiteral("YouTube Short") : QStringLiteral("YouTube Video"));
    if (!author.isEmpty())
        out.description = QStringLiteral("Video by %1").arg(author);
    out.imageUrl = thumb;
    out.siteName = QStringLiteral("YouTube");
    out.faviconUrl = QStringLiteral("https://www.youtube.com/favicon.ico");
    out.ogType = QStringLiteral("video.other");
    out.mediaType = LinkMediaType::YouTube;
    out.hasVideo = true;
    return out;
}

RedditArchivePost parseRedditArchiveJson(const QByteArray &jsonBytes)
{
    RedditArchivePost out;
    const QJsonDocument doc = QJsonDocument::fromJson(jsonBytes);
    if (!doc.isObject())
        return out;
    const QJsonArray data = doc.object().value(QLatin1String("data")).toArray();
    if (data.isEmpty() || !data.first().isObject())
        return out;

    const QJsonObject post = data.first().toObject();
    out.title = cleanRedditTitle(decodeHtmlEntities(post.value(QLatin1String("title")).toString()));
    const QString selfText = decodeHtmlEntities(post.value(QLatin1String("selftext")).toString()).left(300).trimmed();
    out.description = selfText;
    out.imageUrl = extractRedditArchiveImage(post);
    out.isVideo = post.value(QLatin1String("is_video")).toBool(false)
        || post.value(QLatin1String("domain")).toString() == QLatin1String("v.redd.it")
        || post.value(QLatin1String("post_hint")).toString() == QLatin1String("hosted:video");
    out.ok = true;
    return out;
}

std::optional<LinkPreviewData> parseVxTwitterJson(const QByteArray &jsonBytes,
                                                  const QUrl &originalUrl,
                                                  const QString &fallbackUser)
{
    const QJsonDocument doc = QJsonDocument::fromJson(jsonBytes);
    if (!doc.isObject())
        return std::nullopt;
    const QJsonObject obj = doc.object();
    const QString text = decodeHtmlEntities(obj.value(QLatin1String("text")).toString());
    const QString userName = decodeHtmlEntities(obj.value(QLatin1String("user_name")).toString());
    const QString userScreen = obj.value(QLatin1String("user_screen_name")).toString().trimmed();
    if (text.isEmpty() && userName.isEmpty() && userScreen.isEmpty())
        return std::nullopt;

    QString mediaUrl;
    const QJsonArray mediaArr = obj.value(QLatin1String("mediaURLs")).toArray();
    const QJsonArray mediaArrSnake = obj.value(QLatin1String("media_urls")).toArray();
    const QJsonArray &chosen = !mediaArrSnake.isEmpty() ? mediaArrSnake : mediaArr;
    if (!chosen.isEmpty())
        mediaUrl = cleanImageUrl(chosen.first().toString());

    const QString title = !userName.isEmpty()
        ? (!userScreen.isEmpty() ? QStringLiteral("%1 (@%2)").arg(userName, userScreen) : userName)
        : (!fallbackUser.isEmpty() ? QStringLiteral("Tweet by @%1").arg(fallbackUser) : QStringLiteral("Post on X"));

    LinkPreviewData out;
    out.url = originalUrl;
    out.title = title;
    out.description = text;
    out.imageUrl = mediaUrl;
    out.siteName = QStringLiteral("X (Twitter)");
    out.faviconUrl = QStringLiteral("https://abs.twimg.com/favicons/twitter.3.ico");
    out.ogType = QStringLiteral("article");
    out.mediaType = LinkMediaType::Twitter;
    return out;
}

std::optional<LinkPreviewData> parseTikTokOEmbed(const QByteArray &jsonBytes,
                                                 const QUrl &originalUrl)
{
    const QJsonDocument doc = QJsonDocument::fromJson(jsonBytes);
    if (!doc.isObject())
        return std::nullopt;
    const QJsonObject obj = doc.object();
    const QString title = decodeHtmlEntities(obj.value(QLatin1String("title")).toString());
    const QString author = decodeHtmlEntities(obj.value(QLatin1String("author_name")).toString());
    const QString thumb = cleanImageUrl(obj.value(QLatin1String("thumbnail_url")).toString());
    if (title.isEmpty() && author.isEmpty() && thumb.isEmpty())
        return std::nullopt;

    LinkPreviewData out;
    out.url = originalUrl;
    out.title = !title.isEmpty()
        ? title
        : (!author.isEmpty() ? QStringLiteral("TikTok by %1").arg(author) : QStringLiteral("TikTok Video"));
    if (!author.isEmpty())
        out.description = QStringLiteral("@%1 on TikTok").arg(author);
    out.imageUrl = thumb;
    out.siteName = QStringLiteral("TikTok");
    out.faviconUrl = QStringLiteral("https://www.tiktok.com/favicon.ico");
    out.ogType = QStringLiteral("video.other");
    out.mediaType = LinkMediaType::TikTok;
    out.hasVideo = true;
    return out;
}

QString extractInstagramEmbedImage(const QByteArray &rawBytes, const QUrl &embedUrl)
{
    if (rawBytes.isEmpty())
        return {};
    if (const auto parsed = parseOpenGraphHtml(rawBytes, embedUrl)) {
        if (!parsed->imageUrl.isEmpty())
            return parsed->imageUrl;
    }
    const QString body = QString::fromUtf8(rawBytes.left(kMaxHtmlParseBytes));
    static const QRegularExpression escapedImgRe(
        QStringLiteral("https:\\\\/\\\\/[^\"&\\s]+\\.(?:jpg|jpeg|png|webp)[^\"\\s]*"),
        QRegularExpression::CaseInsensitiveOption);
    const auto m = escapedImgRe.match(body);
    if (m.hasMatch())
        return cleanImageUrl(m.captured(0));
    return {};
}

// Pure URL + OpenGraph heuristics -> canonical web.category option id (spec/fixtures/types/standard.json).
QString inferWebCategory(const QUrl &u, const QString &ogType, LinkMediaType mediaType)
{
    if (mediaType == LinkMediaType::YouTube
        || mediaType == LinkMediaType::TikTok
        || mediaType == LinkMediaType::RedditVideo
        || mediaType == LinkMediaType::InstagramReel
        || mediaType == LinkMediaType::FacebookVideo) {
        return QStringLiteral("video");
    }

    const QString host = bareHost(u);
    const QString path = u.path().toLower();
    const QString t = ogType.toLower();

    if (host == QLatin1String("youtube.com") || host == QLatin1String("youtu.be")
        || host == QLatin1String("vimeo.com") || host == QLatin1String("twitch.tv")
        || host == QLatin1String("tiktok.com") || host == QLatin1String("fb.watch")
        || host.endsWith(QLatin1String(".youtube.com"))
        || ((host == QLatin1String("facebook.com") || host.endsWith(QLatin1String(".facebook.com")))
            && (path.contains(QLatin1String("/watch")) || path.contains(QLatin1String("/reel"))
                || path.contains(QLatin1String("/videos"))))
        || ((host == QLatin1String("instagram.com") || host == QLatin1String("instagr.am"))
            && (path.contains(QLatin1String("/reel/")) || path.contains(QLatin1String("/reels/"))))
        || t.startsWith(QLatin1String("video")))
        return QStringLiteral("video");
    if (host == QLatin1String("github.com") || host == QLatin1String("gitlab.com")
        || host == QLatin1String("codeberg.org") || host == QLatin1String("bitbucket.org"))
        return QStringLiteral("repository");
    if (host.startsWith(QLatin1String("docs.")) || host.startsWith(QLatin1String("doc."))
        || host.startsWith(QLatin1String("developer.")) || host == QLatin1String("devdocs.io")
        || path.contains(QLatin1String("/docs/")) || path.contains(QLatin1String("/reference/")))
        return QStringLiteral("documentation");
    if (host == QLatin1String("stackoverflow.com") || host.endsWith(QLatin1String(".stackexchange.com"))
        || host == QLatin1String("quora.com"))
        return QStringLiteral("q_a");
    if (host == QLatin1String("reddit.com") || host == QLatin1String("redd.it")
        || host.endsWith(QLatin1String(".reddit.com"))
        || host == QLatin1String("news.ycombinator.com")
        || host.contains(QLatin1String("discourse")) || host.startsWith(QLatin1String("forum.")))
        return QStringLiteral("forum_thread");
    if (host == QLatin1String("x.com") || host == QLatin1String("twitter.com")
        || host == QLatin1String("instagram.com") || host == QLatin1String("instagr.am")
        || host == QLatin1String("facebook.com") || host == QLatin1String("fb.com")
        || host == QLatin1String("fb.me")
        || host == QLatin1String("threads.net")
        || host == QLatin1String("bsky.app") || host == QLatin1String("mastodon.social")
        || host == QLatin1String("linkedin.com"))
        return QStringLiteral("social_post");
    if (host == QLatin1String("open.spotify.com") || host == QLatin1String("podcasts.apple.com")
        || host == QLatin1String("changelog.com") || path.contains(QLatin1String("/podcast")))
        return QStringLiteral("podcast");
    if (host.startsWith(QLatin1String("amazon.")) || host.startsWith(QLatin1String("ebay."))
        || host == QLatin1String("aliexpress.com") || t == QLatin1String("product"))
        return QStringLiteral("product");
    if (host == QLatin1String("udemy.com") || host == QLatin1String("coursera.org")
        || host == QLatin1String("edx.org") || path.contains(QLatin1String("/course")))
        return QStringLiteral("course");
    if (host == QLatin1String("medium.com") || host.endsWith(QLatin1String(".substack.com"))
        || host.endsWith(QLatin1String(".blogspot.com")) || path.contains(QLatin1String("/blog/")))
        return QStringLiteral("blog_post");
    if (host == QLatin1String("arxiv.org") || host == QLatin1String("en.wikipedia.org")
        || host.endsWith(QLatin1String(".wikipedia.org")) || t == QLatin1String("article"))
        return QStringLiteral("article");
    return {};
}

QStringList inferWebTopics(const QUrl &u, const QString &title, const QString &desc)
{
    const QString hay = (u.host() + QLatin1Char(' ') + title + QLatin1Char(' ') + desc).toLower();
    QStringList topics;
    auto addIf = [&](const QString &id, std::initializer_list<const char *> needles) {
        if (topics.size() >= 2 || topics.contains(id))
            return;
        for (const char *n : needles) {
            if (hay.contains(QLatin1String(n))) {
                topics.append(id);
                return;
            }
        }
    };
    addIf(QStringLiteral("ai_ml"), { "llm", "ai ", "machine learning", "neural", "claude", "openai", "gpt", "glm", "deepseek", "transformer" });
    addIf(QStringLiteral("programming"), { "github.com", "gitlab.com", "c++", "kotlin", "golang", "rust", "python", "javascript", "typescript", "sqlite", "qt ", "qml", "api", "compiler" });
    addIf(QStringLiteral("design"), { "figma", "typography", "ui/ux", "design system", "css", "svg", "grid", "dribbble", "behance" });
    addIf(QStringLiteral("productivity"), { "zettelkasten", "pkm", "note-taking", "notes", "local-first", "obsidian", "capacities", "workflow" });
    addIf(QStringLiteral("science"), { "arxiv.org", "physics", "biology", "quantum", "paper", "research" });
    addIf(QStringLiteral("gaming"), { "genshin", "steam", "nintendo", "playstation", "xbox", "game " });
    addIf(QStringLiteral("technology"), { "software", "hardware", "linux", "android", "server", "cloud", "sync" });
    return topics;
}

bool shouldReplacePlaceholderTitle(const QString &currentTitle,
                                   const QUrl &originalUrl,
                                   const QUrl &finalUrl)
{
    const QString cur = currentTitle.trimmed();
    if (cur.isEmpty() || isBlockedTitle(cur))
        return true;
    const QString curLower = cur.toLower();
    if (curLower.startsWith(QLatin1String("http://")) || curLower.startsWith(QLatin1String("https://")))
        return true;
    if (cur == originalUrl.toString() || cur == finalUrl.toString())
        return true;
    const QString origHost = originalUrl.host().toLower();
    const QString origBare = bareHost(originalUrl);
    const QString finalHost = finalUrl.host().toLower();
    const QString finalBare = bareHost(finalUrl);
    return (!origHost.isEmpty() && (curLower == origHost || curLower == origBare))
        || (!finalHost.isEmpty() && (curLower == finalHost || curLower == finalBare));
}

bool shouldReplacePlaceholderSiteName(const QString &currentSiteName,
                                      const QUrl &originalUrl,
                                      const QUrl &finalUrl)
{
    const QString cur = currentSiteName.trimmed();
    if (cur.isEmpty())
        return true;
    const QString curLower = cur.toLower();
    const QString origHost = originalUrl.host().toLower();
    const QString origBare = bareHost(originalUrl);
    const QString finalHost = finalUrl.host().toLower();
    const QString finalBare = bareHost(finalUrl);
    return (!origHost.isEmpty() && (curLower == origHost || curLower == origBare))
        || (!finalHost.isEmpty() && (curLower == finalHost || curLower == finalBare));
}

QMap<QString, QString> ogScraperHeaders()
{
    return {
        { QStringLiteral("User-Agent"),
          QStringLiteral("Mozilla/5.0 (compatible; open-graph-scraper/6.0.0; +https://www.npmjs.com/package/open-graph-scraper)") },
        { QStringLiteral("Accept"),
          QStringLiteral("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8") },
        { QStringLiteral("Accept-Language"), QStringLiteral("en-US,en;q=0.9,es;q=0.8") },
        { QStringLiteral("Cache-Control"), QStringLiteral("max-age=0") },
        { QStringLiteral("Sec-Fetch-Dest"), QStringLiteral("document") },
        { QStringLiteral("Sec-Fetch-Mode"), QStringLiteral("navigate") },
        { QStringLiteral("Sec-Fetch-Site"), QStringLiteral("none") },
        { QStringLiteral("Sec-Fetch-User"), QStringLiteral("?1") },
        { QStringLiteral("Upgrade-Insecure-Requests"), QStringLiteral("1") },
    };
}

QMap<QString, QString> botHeaders()
{
    return {
        { QStringLiteral("User-Agent"), QStringLiteral("Discordbot/2.0") },
        { QStringLiteral("Accept"),
          QStringLiteral("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8") },
        { QStringLiteral("Accept-Language"), QStringLiteral("en-US") },
        { QStringLiteral("Cache-Control"), QStringLiteral("max-age=0") },
    };
}

QMap<QString, QString> jsonBotHeaders()
{
    return {
        { QStringLiteral("User-Agent"), QStringLiteral("Discordbot/2.0") },
        { QStringLiteral("Accept"), QStringLiteral("application/json") },
    };
}

QMap<QString, QString> socialCrawlerHeaders()
{
    return {
        { QStringLiteral("User-Agent"),
          QStringLiteral("facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)") },
        { QStringLiteral("Accept"),
          QStringLiteral("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8") },
        { QStringLiteral("Accept-Language"), QStringLiteral("en-US") },
        { QStringLiteral("Cache-Control"), QStringLiteral("max-age=0") },
    };
}

QMap<QString, QString> imageDownloadHeaders(const QUrl &imageUrl)
{
    QMap<QString, QString> h = {
        { QStringLiteral("User-Agent"),
          QStringLiteral("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36") },
        { QStringLiteral("Accept"),
          QStringLiteral("image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8") },
    };
    if (imageUrl.isValid())
        h.insert(QStringLiteral("Referer"), imageUrl.toString());
    return h;
}

} // namespace pkm::linkpreview
