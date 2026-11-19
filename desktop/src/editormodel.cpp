#include "editormodel.h"
#include "blockops.h"
#include "workspace.h"

#include <QClipboard>
#include <QGuiApplication>
#include <QJsonArray>
#include <QJsonDocument>
#include <QMimeData>
#include <QQuickTextDocument>
#include <QRegularExpression>
#include <QTextBlock>
#include <QTextCursor>
#include <QTextDocument>
#include <QUrl>

namespace pkm {

namespace {

QString numberLabelFor(const Block &b)
{
    if (b.type == BlockType::NumberedList)
        return QStringLiteral("%1.").arg(b.number);
    if (b.type == BlockType::AlphabeticList)
        return QStringLiteral("%1.").arg(alphabeticFromInt(b.number));
    if (b.type == BlockType::RomanList)
        return QStringLiteral("%1.").arg(romanFromInt(b.number).toLower());
    return {};
}

QString blockCssColor(const QString &name)
{
    if (name.isEmpty())
        return {};
    return span::paletteHex(name).name();
}

} // namespace

EditorModel::EditorModel(Workspace *ws, QObject *parent)
    : QAbstractListModel(parent)
    , m_ws(ws)
{
}

EditorModel::~EditorModel()
{
    flushNow();
}

void EditorModel::load(const Object &object)
{
    beginResetModel();
    m_object = object;
    m_blocks = object.blocks;
    // Mentions read their target's current title, so a rename shows here too; the refreshed
    // labels are saved with the next flush of this object.
    if (m_ws) {
        blockops::refreshMentionLabels(m_blocks, [this](const QString &id) {
            const Object *target = m_ws->objectPtr(id);
            return target ? target->title : QString();
        });
    }
    m_title = object.title;
    m_collapsed.clear();
    m_selected.clear();
    if (m_blocks.isEmpty())
        m_blocks.append(Block::makeParagraph(newUuid()));
    m_dirty = false;
    rebuildVisible();
    endResetModel();
    emit titleChanged();
    emit coverChanged();
    emit metaChanged();
    emit dirtyChanged();
    emit structureChanged();
    emit selectionChanged();
}

// ---------------------------------------------------------------------------
// Model plumbing
// ---------------------------------------------------------------------------
int EditorModel::rowCount(const QModelIndex &parent) const
{
    return parent.isValid() ? 0 : int(m_visible.size());
}

int EditorModel::visibleRowToBlockIndex(int row) const
{
    if (row < 0 || row >= m_visible.size())
        return -1;
    return m_visible[row].blockIndex;
}

int EditorModel::blockIdToVisibleRow(const QString &id) const
{
    for (int i = 0; i < m_visible.size(); ++i)
        if (m_visible[i].block->id == id)
            return i;
    return -1;
}

QHash<int, QByteArray> EditorModel::roleNames() const
{
    static const QHash<int, QByteArray> roles{
        { BlockIdRole, "blockId" },         { TypeRole, "blockType" },
        { HtmlRole, "richTextHtml" },       { PlainTextRole, "plainText" },
        { IndentRole, "indentLevel" },      { CheckedRole, "checked" },
        { ExpandedRole, "expanded" },       { LevelRole, "headingLevel" },
        { NumberRole, "number" },          { NumberLabelRole, "numberLabel" },
        { CalloutTypeRole, "calloutType" }, { EmojiRole, "calloutEmoji" },
        { LanguageRole, "language" },      { CodeRoleText, "codeText" },
        { LatexRole, "latex" },            { RowsRole, "rows" },
        { AlignRole, "align" },            { AssetIdRole, "assetId" },
        { AssetPathRole, "assetPath" },     { AssetKindRole, "assetKind" },
        { WidthPercentRole, "widthPercent" }, { DurationRole, "durationSeconds" },
        { PageCountRole, "pageCount" },    { LastReadPageRole, "lastReadPage" },
        { FileNameRole, "fileName" },      { UrlRole, "url" },
        { BookmarkTitleRole, "bookmarkTitle" }, { BookmarkDescRole, "bookmarkDesc" },
        { DomainRole, "domain" },          { ThumbnailUrlRole, "thumbnailUrl" },
        { FaviconUrlRole, "faviconUrl" },  { ColorRole, "blockColorName" },
        { ObjectEmbedRole, "embedTarget" }, { EmbedViewModeRole, "viewMode" },
        { BlockColorHexRole, "blockColorHex" }, { FirstInGroupRole, "firstInGroup" },
        { LastInGroupRole, "lastInGroup" }, { HiddenChildrenRole, "hiddenChildren" },
        { SelectedRole, "selected" },      { HasMentionRole, "hasMention" },
        { IsGroupedRole, "isGrouped" },
    };
    return roles;
}

QVariant EditorModel::data(const QModelIndex &index, int role) const
{
    const int row = index.row();
    if (row < 0 || row >= m_visible.size())
        return {};
    const Block &b = *m_visible[row].block;
    switch (role) {
    case BlockIdRole:
        return b.id;
    case TypeRole:
        return b.type;
    case HtmlRole: {
        if (!b.isTextual() && b.type != BlockType::Image && b.type != BlockType::Audio)
            return {};
        return span::runsToHtml(b.runs);
    }
    case PlainTextRole:
        return b.plainText();
    case IndentRole:
        return b.indentLevel;
    case CheckedRole:
        return b.checked;
    case ExpandedRole:
        return b.expanded;
    case LevelRole:
        return b.level;
    case NumberRole:
        return b.isSequencedList() ? b.number : 0;
    case NumberLabelRole:
        return numberLabelFor(b);
    case CalloutTypeRole:
        return b.calloutType;
    case EmojiRole:
        return b.emoji;
    case LanguageRole:
        return b.language;
    case CodeRoleText:
        return b.type == BlockType::Code ? b.text : QString();
    case LatexRole:
        return b.type == BlockType::Math ? b.latex : QString();
    case RowsRole: {
        if (b.type != BlockType::Table)
            return {};
        QVariantList rows;
        for (const QStringList &r : b.rows) {
            QVariantList cells;
            for (const QString &c : r)
                cells.append(c);
            rows.append(QVariant(cells));
        }
        return rows;
    }
    case AlignRole: {
        if (b.type != BlockType::Table)
            return {};
        QStringList out;
        for (const QString &alt : b.align)
            out.append(alt.isEmpty() ? QStringLiteral("left") : alt);
        return out;
    }
    case AssetIdRole:
        return b.assetId;
    case AssetPathRole: {
        if (b.assetId.isEmpty())
            return {};
        Workspace *ws = const_cast<Workspace *>(m_ws);
        ws->ensureAsset(b.assetId);
        return ws->assetLocalPath(b.assetId);
    }
    case AssetKindRole:
        if (b.type == BlockType::Image)
            return QStringLiteral("image");
        else if (b.type == BlockType::Audio)
            return QStringLiteral("audio");
        else if (b.type == BlockType::Pdf)
            return QStringLiteral("pdf");
        return {};
    case WidthPercentRole:
        return b.widthPercent > 0 ? b.widthPercent : 1.0;
    case DurationRole: {
        if (b.durationSeconds > 0)
            return b.durationSeconds;
        // Synced blocks may lack the duration; probe the cached asset once.
        if (m_ws && !b.assetId.isEmpty())
            return m_ws->assetDurationSeconds(b.assetId);
        return 0.0;
    }
    case PageCountRole:
        return b.pageCount;
    case LastReadPageRole:
        return b.lastReadPage;
    case FileNameRole:
        return b.fileName;
    case UrlRole:
        return b.url;
    case BookmarkTitleRole:
        return b.title;
    case BookmarkDescRole:
        return b.description;
    case DomainRole:
        return b.domain;
    case ThumbnailUrlRole:
        return b.thumbnailUrl;
    case FaviconUrlRole:
        return b.faviconUrl;
    case ObjectEmbedRole:
        return b.objectId;
    case EmbedViewModeRole:
        return b.viewMode;
    case ColorRole:
        return b.blockColor;
    case BlockColorHexRole:
        return blockCssColor(b.blockColor);
    case FirstInGroupRole:
        return m_visible[row].firstInGroup;
    case LastInGroupRole:
        return m_visible[row].lastInGroup;
    case HiddenChildrenRole:
        return m_visible[row].hiddenChildren;
    case SelectedRole:
        return m_selected.contains(b.id);
    case HasMentionRole: {
        for (const Run &r : b.runs)
            if (!r.objectId.isEmpty() || !r.date.isEmpty() || !r.tagId.isEmpty())
                return true;
        return false;
    }
    case IsGroupedRole:
        return !b.groupId.isEmpty();
    }
    return {};
}

void EditorModel::rebuildVisible()
{
    m_visible.clear();
    const auto entries = blockops::visibleEntries(m_blocks, m_collapsed, m_selected);
    m_visible.reserve(entries.size());
    for (const auto &e : entries) {
        VisibleRow row;
        row.blockIndex = e.index;
        row.block = &m_blocks[e.index];
        row.firstInGroup = e.firstInGroup;
        row.lastInGroup = e.lastInGroup;
        row.hiddenChildren = e.hiddenChildren;
        m_visible.append(row);
    }
}

void EditorModel::applyCollapseAwareReset()
{
    beginResetModel();
    rebuildVisible();
    endResetModel();
    emit structureChanged();
}

void EditorModel::applyIncrementalBlocks(const Blocks &nextBlocks, const QVector<int> &hintRoles)
{
    const auto nextEntries = blockops::visibleEntries(nextBlocks, m_collapsed, m_selected);
    const int oldCount = int(m_visible.size());
    const int newCount = int(nextEntries.size());

    auto rolesOrAll = hintRoles;

    if (oldCount == newCount) {
        bool sameIds = true;
        for (int i = 0; i < oldCount; ++i) {
            if (m_visible[i].block->id != nextBlocks[nextEntries[i].index].id) {
                sameIds = false;
                break;
            }
        }
        if (sameIds) {
            m_blocks = nextBlocks;
            rebuildVisible();
            if (oldCount > 0)
                emit dataChanged(index(0), index(oldCount - 1), rolesOrAll);
            emit structureChanged();
            return;
        }
    }

    if (newCount == oldCount + 1) {
        int insertAt = oldCount;
        for (int i = 0; i < oldCount; ++i) {
            if (m_visible[i].block->id != nextBlocks[nextEntries[i].index].id) {
                insertAt = i;
                break;
            }
        }
        bool tailMatches = true;
        for (int i = insertAt; i < oldCount; ++i) {
            if (m_visible[i].block->id != nextBlocks[nextEntries[i + 1].index].id) {
                tailMatches = false;
                break;
            }
        }
        if (tailMatches) {
            beginInsertRows(QModelIndex(), insertAt, insertAt);
            m_blocks = nextBlocks;
            rebuildVisible();
            endInsertRows();
            if (insertAt > 0)
                emit dataChanged(index(insertAt - 1), index(insertAt - 1), rolesOrAll);
            if (insertAt + 1 < newCount)
                emit dataChanged(index(insertAt + 1), index(newCount - 1),
                                 { NumberRole, NumberLabelRole, FirstInGroupRole, LastInGroupRole });
            emit structureChanged();
            return;
        }
    }

    if (newCount == oldCount - 1 && oldCount > 0) {
        int removeAt = oldCount - 1;
        for (int i = 0; i < newCount; ++i) {
            if (m_visible[i].block->id != nextBlocks[nextEntries[i].index].id) {
                removeAt = i;
                break;
            }
        }
        bool tailMatches = true;
        for (int i = removeAt; i < newCount; ++i) {
            if (m_visible[i + 1].block->id != nextBlocks[nextEntries[i].index].id) {
                tailMatches = false;
                break;
            }
        }
        if (tailMatches) {
            beginRemoveRows(QModelIndex(), removeAt, removeAt);
            m_blocks = nextBlocks;
            rebuildVisible();
            endRemoveRows();
            if (removeAt > 0 && removeAt - 1 < newCount)
                emit dataChanged(index(removeAt - 1), index(removeAt - 1), rolesOrAll);
            if (removeAt < newCount)
                emit dataChanged(index(removeAt), index(newCount - 1),
                                 { NumberRole, NumberLabelRole, FirstInGroupRole, LastInGroupRole });
            emit structureChanged();
            return;
        }
    }

    m_blocks = nextBlocks;
    applyCollapseAwareReset();
}

void EditorModel::refreshRowsFrom(int start)
{
    // Full re-sync is simple and robust for typical note sizes; QML flicker is
    // avoided because delegates are keyed by blockId and content unchanged.
    applyCollapseAwareReset();
}

QVariantList EditorModel::outline() const
{
    QVariantList out;
    for (const Block &b : m_blocks) {
        if (b.type != BlockType::Heading)
            continue;
        QVariantMap item;
        item.insert("id", b.id);
        item.insert("level", b.level);
        item.insert("text", runsToPlainText(b.runs));
        int row = blockIdToVisibleRow(b.id);
        item.insert("visible", row >= 0);
        out.append(item);
    }
    return out;
}

void EditorModel::setFocus(const QString &blockId, int pos)
{
    m_focusBlockId = blockId;
    m_focusPosition = pos;
    emit focusChanged();
}

void EditorModel::clearFocusTarget(const QString &blockId)
{
    if (m_focusBlockId == blockId) {
        m_focusBlockId.clear();
        m_focusPosition = 0;
    }
}

void EditorModel::markDirty()
{
    if (!m_dirty) {
        m_dirty = true;
        emit dirtyChanged();
    }
    scheduleSave();
}

void EditorModel::scheduleSave(int ms)
{
    if (m_saveTimerId)
        killTimer(m_saveTimerId);
    m_saveTimerId = startTimer(ms);
}

void EditorModel::timerEvent(QTimerEvent *event)
{
    if (event->timerId() == m_saveTimerId) {
        killTimer(m_saveTimerId);
        m_saveTimerId = 0;
        flushNow();
    }
    QAbstractListModel::timerEvent(event);
}

void EditorModel::flushNow()
{
    if (m_saveTimerId) {
        killTimer(m_saveTimerId);
        m_saveTimerId = 0;
    }
    if (m_ws && m_ws->shuttingDown())
        return;
    commitObjectToWorkspace();
}

void EditorModel::commitObjectToWorkspace()
{
    if (!m_ws)
        return;
    // Start from the workspace's current state (it may hold a merged remote change made while
    // this editor had unsaved edits) and lay the editor's own fields over it.
    const Object *current = m_ws->objectPtr(m_object.id);
    Object obj = current ? *current : m_object;
    obj.blocks = blockops::renumberLists(m_blocks);
    obj.title = m_title;
    obj.updatedAt = QDateTime::currentDateTimeUtc().toString(Qt::ISODateWithMs);
    obj.hlc = newHlc();
    m_ws->saveObject(obj, this);
    if (m_dirty) {
        m_dirty = false;
        emit dirtyChanged();
    }
    emit objectSaved(obj.id);
}

double EditorModel::coverCropY() const
{
    const QJsonValue cover = m_object.properties.value(QLatin1String("cover"));
    if (cover.isObject())
        return cover.toObject().value(QLatin1String("crop_offset_y")).toDouble(0.5);
    return 0.5;
}

QString EditorModel::thumbnailUrl() const
{
    const QString t = m_object.propertyString(QStringLiteral("thumbnail_url"));
    if (!t.isEmpty())
        return t;
    for (const Block &b : m_blocks) {
        if (b.type == BlockType::Bookmark && !b.thumbnailUrl.isEmpty())
            return b.thumbnailUrl;
    }
    return {};
}

QString EditorModel::url() const
{
    const QString u = m_object.propertyString(QStringLiteral("url"));
    if (!u.isEmpty())
        return u;
    for (const Block &b : m_blocks) {
        if (b.type == BlockType::Bookmark && !b.url.isEmpty())
            return b.url;
    }
    return {};
}

QString EditorModel::description() const
{
    const QString d = m_object.propertyString(QStringLiteral("description"));
    if (!d.isEmpty())
        return d;
    for (const Block &b : m_blocks) {
        if (b.type == BlockType::Bookmark && !b.description.isEmpty())
            return b.description;
    }
    return {};
}

QString EditorModel::faviconUrl() const
{
    const QString f = m_object.propertyString(QStringLiteral("favicon_url"));
    if (!f.isEmpty())
        return f;
    for (const Block &b : m_blocks) {
        if (b.type == BlockType::Bookmark && !b.faviconUrl.isEmpty())
            return b.faviconUrl;
    }
    return {};
}

QString EditorModel::domain() const
{
    const QString u = url();
    if (u.isEmpty())
        return {};
    QString host = QUrl(u).host();
    if (host.startsWith(QLatin1String("www.")))
        host = host.mid(4);
    return host;
}

void EditorModel::notifyCoverUpdated()
{
    emit coverChanged();
    emit metaChanged();
}

bool EditorModel::coverFit() const
{
    const QJsonValue cover = m_object.properties.value(QLatin1String("cover"));
    return cover.isObject() && cover.toObject().value(QLatin1String("is_fit")).toBool();
}

void EditorModel::setTitle(const QString &t)
{
    if (m_title == t)
        return;
    m_title = t;
    emit titleChanged();
    markDirty();
}

// ---------------------------------------------------------------------------
// Live QTextDocument access
// ---------------------------------------------------------------------------
QVector<Run> EditorModel::runsFromDoc(QQuickTextDocument *doc) const
{
    if (!doc)
        return {};
    QTextDocument *td = doc->textDocument();
    return span::documentToRuns(td, QColor("#FFFFFF"));
}

namespace {
bool runsEquivalent(const QVector<Run> &a, const QVector<Run> &b)
{
    if (a.size() != b.size())
        return false;
    for (int i = 0; i < a.size(); ++i) {
        const Run &x = a[i], &y = b[i];
        if (x.text != y.text || !x.sameStyleAs(y)
            || x.objectId != y.objectId || x.tagId != y.tagId || x.tagName != y.tagName
            || x.date != y.date || x.hasTime != y.hasTime
            || x.isMath != y.isMath || x.latex != y.latex || x.display != y.display
            || x.imageName != y.imageName)
            return false;
    }
    return true;
}
} // namespace

void EditorModel::commitRichText(int row, QQuickTextDocument *doc, int cursorPos)
{
    Q_UNUSED(cursorPos);
    const int idx = visibleRowToBlockIndex(row);
    if (idx < 0 || !doc)
        return;
    Block &b = m_blocks[idx];
    if (!b.isTextual())
        return;
    const QVector<Run> runs = runsFromDoc(doc);
    if (runsEquivalent(b.runs, runs))
        return;
    b.runs = runs;
    markDirty();
}

void EditorModel::documentEdited(int row, QQuickTextDocument *doc, int cursorPos)
{
    Q_UNUSED(row);
    Q_UNUSED(doc);
    Q_UNUSED(cursorPos);
}

void EditorModel::documentFocused(int row, QQuickTextDocument *doc, int cursorPos)
{
    Q_UNUSED(doc);
    Q_UNUSED(cursorPos);
    const int idx = visibleRowToBlockIndex(row);
    if (idx >= 0 && m_focusBlockId != m_blocks[idx].id) {
        m_focusBlockId.clear();
        m_focusPosition = 0;
    }
    if (m_ws)
        m_ws->setActiveEditing(m_object.id);
}

void EditorModel::documentUnfocused(int row, QQuickTextDocument *doc, int cursorPos)
{
    commitRichText(row, doc, cursorPos);
    if (m_ws)
        m_ws->clearActiveEditing(m_object.id);
}

void EditorModel::documentUnfocusedBlock(const QString &blockId, QQuickTextDocument *doc, int cursorPos)
{
    const int row = blockIdToVisibleRow(blockId);
    if (row >= 0)
        commitRichText(row, doc, cursorPos);
    if (m_ws)
        m_ws->clearActiveEditing(m_object.id);
}

int EditorModel::handleEnter(int row, int caretInDoc, QQuickTextDocument *doc)
{
    // Pull fresh content from the live doc before mutating the structure.
    const int idx = visibleRowToBlockIndex(row);
    if (idx < 0)
        return row;
    Block &b = m_blocks[idx];
    if (!b.isTextual()) {
        setFocus(b.id, 0);
        return row;
    }
    if (doc)
        b.runs = runsFromDoc(doc);

    // Caret position -> span offset (single-block docs: identical numbering).
    const int caret = qBound(0, caretInDoc, runsToPlainText(b.runs).length());
    const auto result = blockops::splitOnEnterAtOffset(b, caret);

    if (result.kind == blockops::SplitResult::ExitList) {
        Blocks nextBlocks = m_blocks;
        nextBlocks[idx] = result.updatedCurrent;
        applyIncrementalBlocks(nextBlocks, { TypeRole, IndentRole, NumberRole, NumberLabelRole,
                                             FirstInGroupRole, LastInGroupRole, IsGroupedRole });
        markDirty();
        setFocus(result.updatedCurrent.id, 0);
        return row;
    }

    Blocks nextBlocks = m_blocks;
    nextBlocks[idx] = result.updatedCurrent;
    nextBlocks.insert(idx + 1, result.newNext);
    nextBlocks = blockops::renumberLists(nextBlocks);
    applyIncrementalBlocks(nextBlocks, { HtmlRole, PlainTextRole, HasMentionRole,
                                         NumberRole, NumberLabelRole, FirstInGroupRole, LastInGroupRole });
    emit blockHtmlReplaced(result.updatedCurrent.id);
    markDirty();
    emit blockCreated(result.newNext.id);
    setFocus(result.newNext.id, 0);
    return row + 1;
}

void EditorModel::handleBackspace(int row, int caretInDoc, QQuickTextDocument *doc)
{
    const int idx = visibleRowToBlockIndex(row);
    if (idx < 0)
        return;
    Block &b = m_blocks[idx];
    if (doc && b.isTextual())
        b.runs = runsFromDoc(doc);
    if (caretInDoc > 0)
        return; // normal in-text deletion handled by TextEdit

    const auto result = blockops::handleBackspaceAtStart(m_blocks, idx);
    switch (result.kind) {
    case blockops::BackspaceResult::Outdented:
    case blockops::BackspaceResult::DemotedToParagraph:
    case blockops::BackspaceResult::LeftGroup: {
        Blocks nextBlocks = m_blocks;
        nextBlocks[idx] = result.demotedBlock;
        nextBlocks = blockops::renumberLists(nextBlocks);
        applyIncrementalBlocks(nextBlocks, { TypeRole, IndentRole, LevelRole, NumberRole, NumberLabelRole,
                                             CalloutTypeRole, EmojiRole, CheckedRole, ExpandedRole,
                                             FirstInGroupRole, LastInGroupRole, IsGroupedRole });
        markDirty();
        setFocus(result.demotedBlock.id, 0);
        break;
    }
    case blockops::BackspaceResult::DeleteEmpty: {
        const QString prevId = result.previousBlockId;
        applyIncrementalBlocks(blockops::renumberLists(result.updatedBlocks),
                               { NumberRole, NumberLabelRole, FirstInGroupRole, LastInGroupRole });
        markDirty();
        setFocus(prevId, result.cursorOffset);
        break;
    }
    case blockops::BackspaceResult::MergeWithPrevious: {
        const QString prevId = result.previousBlockId;
        applyIncrementalBlocks(blockops::renumberLists(result.updatedBlocks),
                               { HtmlRole, PlainTextRole, HasMentionRole,
                                 NumberRole, NumberLabelRole, FirstInGroupRole, LastInGroupRole });
        emit blockHtmlReplaced(prevId);
        markDirty();
        setFocus(prevId, result.cursorOffset);
        break;
    }
    case blockops::BackspaceResult::NoOp:
    default:
        break;
    }
}

bool EditorModel::handleMarkdownPrefix(int row, QQuickTextDocument *doc)
{
    const int idx = visibleRowToBlockIndex(row);
    if (idx < 0 || !doc)
        return false;
    Block &b = m_blocks[idx];
    if (doc && b.isTextual())
        b.runs = runsFromDoc(doc);
    const QString text = runsToPlainText(b.runs);

    const auto result = blockops::processMarkdownPrefix(b, text);
    if (result.kind == blockops::MarkdownResult::None)
        return false;

    if (result.kind == blockops::MarkdownResult::Divider) {
        Blocks nextBlocks = m_blocks;
        nextBlocks[idx] = result.block;
        nextBlocks.insert(idx + 1, result.next);
        applyIncrementalBlocks(nextBlocks, { TypeRole, HtmlRole, PlainTextRole });
        markDirty();
        setFocus(result.next.id, 0);
        return true;
    }
    Blocks nextBlocks = m_blocks;
    nextBlocks[idx] = result.block;
    nextBlocks = blockops::renumberLists(nextBlocks);
    applyIncrementalBlocks(nextBlocks, { TypeRole, LevelRole, CheckedRole, CalloutTypeRole, EmojiRole,
                                         HtmlRole, PlainTextRole, NumberRole, NumberLabelRole });
    emit blockHtmlReplaced(result.block.id);
    markDirty();
    setFocus(result.block.id, runsToPlainText(result.block.runs).length());
    return true;
}

// ---------------------------------------------------------------------------
// Block mutations
// ---------------------------------------------------------------------------
void EditorModel::convertBlock(int row, const QString &targetKey)
{
    const int idx = visibleRowToBlockIndex(row);
    if (idx < 0)
        return;
    const Block converted = blockops::convertBlockType(m_blocks[idx], targetKey);
    Blocks nextBlocks = m_blocks;
    nextBlocks[idx] = converted;
    nextBlocks = blockops::renumberLists(nextBlocks);
    applyIncrementalBlocks(nextBlocks, { TypeRole, LevelRole, CheckedRole, CalloutTypeRole, EmojiRole,
                                         ExpandedRole, NumberRole, NumberLabelRole, HtmlRole, PlainTextRole });
    markDirty();
    setFocus(converted.id, runsToPlainText(converted.runs).length());
}

void EditorModel::toggleTodo(int row)
{
    const int idx = visibleRowToBlockIndex(row);
    if (idx < 0)
        return;
    Blocks nextBlocks = m_blocks;
    Block &b = nextBlocks[idx];
    if (b.type == BlockType::Todo) {
        b.checked = !b.checked;
    } else {
        b = blockops::convertBlockType(b, BlockKey::Todo);
    }
    applyIncrementalBlocks(nextBlocks, { TypeRole, CheckedRole });
    markDirty();
}

void EditorModel::toggleExpand(int row)
{
    const int idx = visibleRowToBlockIndex(row);
    if (idx < 0)
        return;
    const Block &b = m_blocks[idx];
    if (b.type != BlockType::Toggle)
        return;
    if (m_collapsed.contains(b.id))
        m_collapsed.remove(b.id);
    else
        m_collapsed.insert(b.id);
    applyCollapseAwareReset();
    markDirty();
}

void EditorModel::indentBlocks(int row, int delta, int caret)
{
    const int idx = visibleRowToBlockIndex(row);
    if (idx < 0)
        return;
    const QString targetId = m_blocks[idx].id;
    QSet<QString> ids;
    if (m_selected.isEmpty())
        ids.insert(targetId);
    else
        ids = m_selected;
    const Blocks nextBlocks = blockops::indentSelected(m_blocks, ids, delta);
    const bool didReset = (blockops::visibleEntries(nextBlocks, m_collapsed, m_selected).size()
                           != m_visible.size());
    applyIncrementalBlocks(nextBlocks, { IndentRole, NumberRole, NumberLabelRole,
                                         FirstInGroupRole, LastInGroupRole });
    markDirty();
    if (didReset || !m_selected.isEmpty()) {
        const int pos = caret >= 0 ? caret : runsToPlainText(m_blocks[idx].runs).length();
        setFocus(targetId, pos);
    } else if (caret >= 0) {
        setFocus(targetId, caret);
    }
}

void EditorModel::moveBlocks(const QStringList &ids, int delta)
{
    if (ids.isEmpty())
        return;
    QSet<QString> set(ids.begin(), ids.end());
    const Block &first = m_blocks.first();
    m_blocks = blockops::moveSelected(m_blocks, set, delta);
    applyCollapseAwareReset();
    markDirty();
    setFocus(first.id, 0);
}

void EditorModel::moveBlockTo(int fromRow, int toRow)
{
    const int fromIdx = visibleRowToBlockIndex(fromRow);
    const int toIdx = toRow >= rowCount() ? int(m_blocks.size())
                                          : visibleRowToBlockIndex(toRow);
    if (fromIdx < 0 || toIdx < 0 || fromIdx == toIdx)
        return;
    const QString focusId = m_blocks[fromIdx].id;
    m_blocks = blockops::reorderSubtree(m_blocks, fromIdx, toIdx);
    applyCollapseAwareReset();
    markDirty();
    setFocus(focusId, 0);
}

void EditorModel::duplicateBlocks(const QStringList &ids)
{
    if (ids.isEmpty())
        return;
    QSet<QString> set(ids.begin(), ids.end());
    m_blocks = blockops::duplicateSelected(m_blocks, set);
    applyCollapseAwareReset();
    markDirty();
}

void EditorModel::groupBlocks(const QStringList &ids)
{
    if (ids.isEmpty())
        return;
    QSet<QString> set(ids.begin(), ids.end());
    m_blocks = blockops::toggleGroupSelected(m_blocks, set);
    applyCollapseAwareReset();
    markDirty();
}

void EditorModel::setBlockColor(int row, const QString &colorName)
{
    const int idx = visibleRowToBlockIndex(row);
    if (idx < 0)
        return;
    m_blocks[idx].blockColor = colorName;
    applyCollapseAwareReset();
    markDirty();
}

namespace {
constexpr const char *kClipboardBlocksMime = "application/x-yaop-blocks";

QPair<QVector<Run>, QVector<Run>> splitRunsAtOffset(const QVector<Run> &runs, int offset)
{
    QVector<Run> before, after;
    int pos = 0;
    for (const Run &r : runs) {
        const QString vis = runVisibleText(r);
        const int len = vis.length();
        const int end = pos + len;
        if (end <= offset) {
            before.append(r);
        } else if (pos >= offset) {
            after.append(r);
        } else {
            // Atomic tokens (mentions, tags, dates, math, inline images) stay intact.
            if (!r.objectId.isEmpty() || !r.tagId.isEmpty() || !r.tagName.isEmpty()
                || !r.date.isEmpty() || r.isMath || !r.imageName.isEmpty()) {
                before.append(r);
            } else {
                const int cut = offset - pos;
                Run left = r;
                left.text = r.text.left(cut);
                if (!left.text.isEmpty())
                    before.append(left);
                Run right = r;
                right.text = r.text.mid(cut);
                if (!right.text.isEmpty())
                    after.append(right);
            }
        }
        pos = end;
    }
    return { normalizeRuns(before), normalizeRuns(after) };
}

Blocks preparePastedBlocks(Blocks incoming)
{
    QHash<QString, QString> groupMap;
    for (Block &b : incoming) {
        b.id = newUuid();
        if (!b.groupId.isEmpty()) {
            auto it = groupMap.constFind(b.groupId);
            if (it == groupMap.constEnd())
                it = groupMap.insert(b.groupId, newUuid());
            b.groupId = it.value();
        }
    }
    return incoming;
}

bool looksLikeBlockMarkdown(const QString &txt)
{
    if (txt.contains(QLatin1Char('\n')))
        return true;
    const QString t = txt.trimmed();
    static const QRegularExpression prefixRe(
        QStringLiteral("^(#{1,4}\\s|[-*]\\s|[-*]\\s\\[[ xX]\\]\\s|>\\s|\\d+[.)]\\s|---|\\*\\*\\*|___|\\$\\$|```)"));
    return prefixRe.match(t).hasMatch();
}

bool htmlHasBlockStructure(const QString &html)
{
    static const QRegularExpression blockTagRe(
        QStringLiteral("<(h[1-6]|ul|ol|li|blockquote|pre|table|hr)\\b"), QRegularExpression::CaseInsensitiveOption);
    if (blockTagRe.match(html).hasMatch())
        return true;
    // Multiple paragraphs (<p ...> appearing more than once)
    const int firstP = html.indexOf(QStringLiteral("<p"), 0, Qt::CaseInsensitive);
    return firstP >= 0 && html.indexOf(QStringLiteral("<p"), firstP + 2, Qt::CaseInsensitive) > firstP;
}
} // namespace

void EditorModel::emitSelectionChanged()
{
    if (!m_visible.isEmpty())
        emit dataChanged(index(0), index(int(m_visible.size()) - 1), { SelectedRole });
    emit selectionChanged();
}

void EditorModel::setSelected(const QStringList &ids)
{
    const QSet<QString> next(ids.begin(), ids.end());
    if (next == m_selected)
        return;
    m_selected = next;
    emitSelectionChanged();
}

void EditorModel::selectRange(int fromRow, int toRow)
{
    if (m_visible.isEmpty())
        return;
    const int lo = qBound(0, qMin(fromRow, toRow), int(m_visible.size()) - 1);
    const int hi = qBound(0, qMax(fromRow, toRow), int(m_visible.size()) - 1);
    QSet<QString> next;
    for (int r = lo; r <= hi; ++r)
        next.insert(m_visible[r].block->id);
    if (next == m_selected)
        return;
    m_selected = next;
    emitSelectionChanged();
}

void EditorModel::toggleSelected(const QString &id)
{
    if (id.isEmpty())
        return;
    if (m_selected.contains(id))
        m_selected.remove(id);
    else
        m_selected.insert(id);
    emitSelectionChanged();
}

void EditorModel::selectAllBlocks()
{
    if (m_visible.isEmpty())
        return;
    QSet<QString> next;
    for (const VisibleRow &vr : m_visible)
        next.insert(vr.block->id);
    if (next == m_selected)
        return;
    m_selected = next;
    emitSelectionChanged();
}

void EditorModel::clearSelection()
{
    if (m_selected.isEmpty())
        return;
    m_selected.clear();
    emitSelectionChanged();
}

QStringList EditorModel::selectedIds() const
{
    QStringList out;
    for (const Block &b : m_blocks)
        if (m_selected.contains(b.id))
            out.append(b.id);
    return out;
}

void EditorModel::deleteSelected()
{
    if (m_selected.isEmpty())
        return;
    int firstIdx = 0;
    for (int i = 0; i < m_blocks.size(); ++i) {
        if (m_selected.contains(m_blocks[i].id)) {
            firstIdx = i;
            break;
        }
    }
    for (int i = m_blocks.size() - 1; i >= 0; --i) {
        if (m_selected.contains(m_blocks[i].id))
            m_blocks.removeAt(i);
    }
    m_selected.clear();
    if (m_blocks.isEmpty())
        m_blocks.append(Block::makeParagraph(newUuid()));
    m_blocks = blockops::renumberLists(m_blocks);
    applyCollapseAwareReset();
    markDirty();
    emit selectionChanged();
    const int targetIdx = qBound(0, firstIdx, int(m_blocks.size()) - 1);
    setFocus(m_blocks[targetIdx].id, 0);
}

void EditorModel::removeBlockAt(int row)
{
    const int idx = visibleRowToBlockIndex(row);
    if (idx < 0 || idx >= m_blocks.size())
        return;
    Blocks next = m_blocks;
    next.removeAt(idx);
    if (next.isEmpty())
        next.append(Block::makeParagraph(newUuid()));
    next = blockops::renumberLists(next);
    applyIncrementalBlocks(next, { NumberRole, NumberLabelRole, FirstInGroupRole, LastInGroupRole });
    markDirty();
    const int targetIdx = qBound(0, idx - 1, int(m_blocks.size()) - 1);
    setFocus(m_blocks[targetIdx].id, 0);
}

bool EditorModel::copySelected(bool cut)
{
    return copySelectedAs(QStringLiteral("all")) && (!cut || (deleteSelected(), true));
}

bool EditorModel::copySelectedAs(const QString &format)
{
    Blocks chosen;
    for (const Block &b : m_blocks)
        if (m_selected.contains(b.id))
            chosen.append(b);
    if (chosen.isEmpty())
        return false;

    auto *mime = new QMimeData();
    if (format == QLatin1String("html")) {
        const QString html = blocksToHtml(chosen);
        mime->setHtml(html);
        mime->setText(html);
    } else if (format == QLatin1String("plain")) {
        QStringList lines;
        for (const Block &b : chosen)
            lines.append(b.plainText());
        mime->setText(lines.join(QLatin1Char('\n')));
    } else {
        mime->setText(blocksToMarkdown(chosen));
        mime->setHtml(blocksToHtml(chosen));
        mime->setData(QString::fromLatin1(kClipboardBlocksMime), blocksToWireJsonBytes(chosen));
    }
    QGuiApplication::clipboard()->setMimeData(mime);
    return true;
}

bool EditorModel::pasteBlocksAt(int row, int caretInDoc, QQuickTextDocument *doc, const Blocks &rawIncoming)
{
    Blocks incoming = preparePastedBlocks(rawIncoming);
    if (incoming.isEmpty())
        return false;

    if (!m_selected.isEmpty()) {
        int insertAt = int(m_blocks.size());
        for (int i = 0; i < m_blocks.size(); ++i) {
            if (m_selected.contains(m_blocks[i].id)) {
                insertAt = i;
                break;
            }
        }
        for (int i = m_blocks.size() - 1; i >= 0; --i) {
            if (m_selected.contains(m_blocks[i].id))
                m_blocks.removeAt(i);
        }
        m_selected.clear();
        insertAt = qBound(0, insertAt, int(m_blocks.size()));
        for (int i = 0; i < incoming.size(); ++i)
            m_blocks.insert(insertAt + i, incoming[i]);
        m_blocks = blockops::renumberLists(m_blocks);
        applyCollapseAwareReset();
        markDirty();
        emit selectionChanged();
        const Block &last = incoming.last();
        setFocus(last.id, last.isTextual() ? runsToPlainText(last.runs).length() : 0);
        return true;
    }

    const int idx = visibleRowToBlockIndex(row);
    if (idx < 0) {
        for (const Block &b : incoming)
            m_blocks.append(b);
        m_blocks = blockops::renumberLists(m_blocks);
        applyCollapseAwareReset();
        markDirty();
        const Block &last = incoming.last();
        setFocus(last.id, last.isTextual() ? runsToPlainText(last.runs).length() : 0);
        return true;
    }

    Block &cur = m_blocks[idx];
    if (doc && cur.isTextual())
        cur.runs = runsFromDoc(doc);

    if (cur.isTextual()) {
        const QString curPlain = runsToPlainText(cur.runs);
        if (curPlain.isEmpty()) {
            const int baseIndent = cur.indentLevel;
            m_blocks.removeAt(idx);
            for (int i = 0; i < incoming.size(); ++i) {
                Block b = incoming[i];
                if (b.indentLevel == 0)
                    b.indentLevel = baseIndent;
                m_blocks.insert(idx + i, b);
            }
            m_blocks = blockops::renumberLists(m_blocks);
            applyCollapseAwareReset();
            markDirty();
            const Block &last = incoming.last();
            setFocus(last.id, last.isTextual() ? runsToPlainText(last.runs).length() : 0);
            return true;
        }

        const int caret = qBound(0, caretInDoc, curPlain.length());
        const auto [before, after] = splitRunsAtOffset(cur.runs, caret);
        if (incoming.size() == 1 && incoming[0].type == BlockType::Paragraph) {
            QVector<Run> merged = before;
            merged.append(incoming[0].runs);
            merged.append(after);
            cur.runs = normalizeRuns(merged);
            applyCollapseAwareReset();
            markDirty();
            setFocus(cur.id, runsToPlainText(before).length() + runsToPlainText(incoming[0].runs).length());
            return true;
        }

        if (caret == 0) {
            for (int i = 0; i < incoming.size(); ++i)
                m_blocks.insert(idx + i, incoming[i]);
            m_blocks = blockops::renumberLists(m_blocks);
            applyCollapseAwareReset();
            markDirty();
            const Block &last = incoming.last();
            setFocus(last.id, last.isTextual() ? runsToPlainText(last.runs).length() : 0);
            return true;
        }

        cur.runs = before;
        int insertAt = idx + 1;
        for (int i = 0; i < incoming.size(); ++i)
            m_blocks.insert(insertAt + i, incoming[i]);
        QString focusId = incoming.last().id;
        int focusPos = incoming.last().isTextual() ? runsToPlainText(incoming.last().runs).length() : 0;
        if (!after.isEmpty()) {
            Block &tail = m_blocks[insertAt + incoming.size() - 1];
            if (tail.isTextual()) {
                focusPos = runsToPlainText(tail.runs).length();
                QVector<Run> combined = tail.runs;
                combined.append(after);
                tail.runs = normalizeRuns(combined);
            } else {
                Block trail = Block::makeParagraph(newUuid(), after, cur.indentLevel);
                m_blocks.insert(insertAt + incoming.size(), trail);
                focusId = trail.id;
                focusPos = 0;
            }
        }
        m_blocks = blockops::renumberLists(m_blocks);
        applyCollapseAwareReset();
        markDirty();
        setFocus(focusId, focusPos);
        return true;
    }

    for (int i = 0; i < incoming.size(); ++i)
        m_blocks.insert(idx + 1 + i, incoming[i]);
    m_blocks = blockops::renumberLists(m_blocks);
    applyCollapseAwareReset();
    markDirty();
    const Block &last = incoming.last();
    setFocus(last.id, last.isTextual() ? runsToPlainText(last.runs).length() : 0);
    return true;
}

bool EditorModel::pasteFromClipboard(int row, int caret, QQuickTextDocument *doc)
{
    const QMimeData *md = QGuiApplication::clipboard()->mimeData();
    if (!md)
        return false;

    if (md->hasFormat(QString::fromLatin1(kClipboardBlocksMime))) {
        const Blocks parsed = blocksFromWireJson(QString::fromUtf8(md->data(QString::fromLatin1(kClipboardBlocksMime))));
        return pasteBlocksAt(row, caret, doc, parsed);
    }

    const QString txt = md->hasText() ? md->text() : QString();
    const QString html = md->hasHtml() ? md->html() : QString();

    if (!m_selected.isEmpty()) {
        if (!html.isEmpty() && !looksLikeBlockMarkdown(txt))
            return pasteBlocksAt(row, caret, doc, htmlToBlocks(html));
        if (!txt.isEmpty())
            return pasteBlocksAt(row, caret, doc, markdownToBlocks(txt));
        return false;
    }

    if (!txt.isEmpty() && looksLikeBlockMarkdown(txt))
        return pasteBlocksAt(row, caret, doc, markdownToBlocks(txt));

    if (!html.isEmpty() && htmlHasBlockStructure(html))
        return pasteBlocksAt(row, caret, doc, htmlToBlocks(html));

    const QString trimmed = txt.trimmed();
    if (!trimmed.isEmpty() && !trimmed.contains(QRegularExpression(QStringLiteral("\\s")))) {
        const QUrl u(trimmed, QUrl::StrictMode);
        if (u.isValid() && (u.scheme() == QLatin1String("http") || u.scheme() == QLatin1String("https"))
            && !u.host().isEmpty() && m_ws) {
            const int idx = visibleRowToBlockIndex(row);
            const QString curText = idx >= 0 ? runsToPlainText(m_blocks[idx].runs).trimmed() : QString();
            const QString blockId = idx >= 0 ? m_blocks[idx].id : QString();
            const QString weblinkId = m_ws->createWebLinkFromUrl(trimmed, m_object.id, blockId);
            if (idx >= 0 && curText.isEmpty() && m_blocks[idx].type == BlockType::Paragraph) {
                Blocks next = m_blocks;
                next[idx].type = BlockType::Bookmark;
                next[idx].url = trimmed;
                next[idx].title = trimmed;
                QString host = u.host();
                if (host.startsWith(QLatin1String("www.")))
                    host = host.mid(4);
                next[idx].domain = host;
                next[idx].objectId = weblinkId;
                applyIncrementalBlocks(next, { TypeRole, UrlRole, BookmarkTitleRole, DomainRole, ObjectEmbedRole });
                markDirty();
                return true;
            }
            insertBookmarkAfter(row, trimmed, weblinkId);
            return true;
        }
    }

    return false;
}

static Block blockForKey(const QString &key, const QVector<Run> &spans, int indent)
{
    if (key == BlockKey::H1 || key == BlockKey::H2 || key == BlockKey::H3 || key == BlockKey::H4) {
        Block b;
        b.type = BlockType::Heading;
        b.level = key == BlockKey::H1 ? 1 : key == BlockKey::H2 ? 2 : key == BlockKey::H3 ? 3 : 4;
        b.runs = spans;
        b.indentLevel = indent;
        return b;
    }
    if (key == BlockKey::Todo || key == BlockKey::Bullet || key == BlockKey::Numbered
        || key == BlockKey::Alphabetic || key == BlockKey::Roman || key == BlockKey::Quote
        || key == BlockKey::Callout || key == BlockKey::Toggle)
        return blockops::convertBlockType(Block::makeParagraph(newUuid(), spans, indent), key);
    return blockops::convertBlockType(Block::makeParagraph(newUuid(), spans, indent), key);
}

void EditorModel::insertBlockAfter(int row, const QString &typeKey)
{
    const int idx = visibleRowToBlockIndex(row);
    const int insertAt = idx < 0 ? int(m_blocks.size()) : idx + 1;
    const int indent = idx >= 0 ? m_blocks[idx].indentLevel : 0;
    Block b = blockForKey(typeKey, {}, indent);
    b.id = newUuid();
    m_blocks.insert(insertAt, b);
    applyCollapseAwareReset();
    markDirty();
    emit blockCreated(b.id);
    setFocus(b.id, 0);
}

void EditorModel::insertDividerAfter(int row)
{
    const int idx = visibleRowToBlockIndex(row);
    const int insertAt = idx < 0 ? int(m_blocks.size()) : idx + 1;
    Block d;
    d.id = newUuid();
    d.type = BlockType::Divider;
    m_blocks.insert(insertAt, d);
    Block next = Block::makeParagraph(newUuid());
    m_blocks.insert(insertAt + 1, next);
    applyCollapseAwareReset();
    markDirty();
    setFocus(next.id, 0);
}

void EditorModel::insertTableAfter(int row, int rows, int cols)
{
    const int idx = visibleRowToBlockIndex(row);
    const int insertAt = idx < 0 ? int(m_blocks.size()) : idx + 1;
    Block t;
    t.id = newUuid();
    t.type = BlockType::Table;
    for (int r = 0; r < qMax(1, rows); ++r)
        t.rows.append(QStringList(qMax(1, cols)));
    m_blocks.insert(insertAt, t);
    applyCollapseAwareReset();
    markDirty();
    for (const Run &probe : QVector<Run>())
        Q_UNUSED(probe);
    setFocus(t.id, 0);
}

void EditorModel::addTableRows(int row, int count)
{
    const int idx = visibleRowToBlockIndex(row);
    if (idx < 0 || m_blocks[idx].type != BlockType::Table)
        return;
    Block &t = m_blocks[idx];
    const int cols = t.rows.isEmpty() ? 2 : t.rows.first().size();
    for (int i = 0; i < qMax(1, count); ++i)
        t.rows.append(QStringList(cols));
    applyCollapseAwareReset();
    markDirty();
}

void EditorModel::addTableColumns(int row, int count)
{
    const int idx = visibleRowToBlockIndex(row);
    if (idx < 0 || m_blocks[idx].type != BlockType::Table)
        return;
    Block &t = m_blocks[idx];
    for (QStringList &r : t.rows)
        for (int i = 0; i < qMax(1, count); ++i)
            r.append(QString());
    if (!t.align.isEmpty())
        for (int i = 0; i < qMax(1, count); ++i)
            t.align.append(QStringLiteral("left"));
    applyCollapseAwareReset();
    markDirty();
}

void EditorModel::setCell(int row, int r, int c, const QString &text)
{
    const int idx = visibleRowToBlockIndex(row);
    if (idx < 0 || m_blocks[idx].type != BlockType::Table)
        return;
    Block &t = m_blocks[idx];
    if (r < 0 || r >= t.rows.size())
        return;
    QStringList &rowCells = t.rows[r];
    while (rowCells.size() <= c)
        rowCells.append(QString());
    rowCells[c] = text;
    emit dataChanged(index(row), index(row), { RowsRole });
    markDirty();
}

void EditorModel::setHeadingLevel(int row, int level)
{
    const int idx = visibleRowToBlockIndex(row);
    if (idx < 0)
        return;
    Block &b = m_blocks[idx];
    b.type = BlockType::Heading;
    b.level = qBound(1, level, 4);
    applyCollapseAwareReset();
    markDirty();
}

void EditorModel::setCodeLanguage(int row, const QString &language)
{
    const int idx = visibleRowToBlockIndex(row);
    if (idx < 0)
        return;
    m_blocks[idx].language = language;
    emit dataChanged(index(row), index(row), { LanguageRole });
    markDirty();
}

void EditorModel::commitCodeText(int row, const QString &text)
{
    const int idx = visibleRowToBlockIndex(row);
    if (idx < 0 || m_blocks[idx].type != BlockType::Code || m_blocks[idx].text == text)
        return;
    m_blocks[idx].text = text;
    markDirty();
}

void EditorModel::setFocusedBlock(const QString &blockId, bool focused)
{
    Q_UNUSED(blockId);
    if (!m_ws)
        return;
    if (focused)
        m_ws->setActiveEditing(m_object.id);
    else
        m_ws->clearActiveEditing(m_object.id);
}

void EditorModel::setLatex(int row, const QString &latex)
{
    const int idx = visibleRowToBlockIndex(row);
    if (idx < 0)
        return;
    m_blocks[idx].latex = latex;
    emit dataChanged(index(row), index(row), { LatexRole });
    markDirty();
}

void EditorModel::setPdfPage(int row, int page)
{
    const int idx = visibleRowToBlockIndex(row);
    if (idx < 0)
        return;
    m_blocks[idx].lastReadPage = qMax(1, page);
    emit dataChanged(index(row), index(row), { LastReadPageRole });
    markDirty();
}

static span::Mark markFromKey(const QString &key)
{
    if (key == QLatin1String("bold"))
        return span::Mark::Bold;
    if (key == QLatin1String("italic"))
        return span::Mark::Italic;
    if (key == QLatin1String("underline"))
        return span::Mark::Underline;
    if (key == QLatin1String("strike"))
        return span::Mark::Strike;
    if (key == QLatin1String("code"))
        return span::Mark::Code;
    return span::Mark::Highlight;
}

void EditorModel::formatMark(const QString &mark, QQuickTextDocument *doc, int selStart, int selEnd)
{
    if (!doc)
        return;
    QTextDocument *td = doc->textDocument();
    const span::Mark m = markFromKey(mark);
    const bool on = !span::markActiveInRange(td, m, selStart, selEnd);
    span::applyMark(td, m, on, selStart, selEnd);
}

bool EditorModel::markActive(const QString &mark, QQuickTextDocument *doc, int selStart, int selEnd)
{
    if (!doc)
        return false;
    return span::markActiveInRange(doc->textDocument(), markFromKey(mark), selStart, selEnd);
}

void EditorModel::formatColor(const QString &role, const QString &colorName,
                             QQuickTextDocument *doc, int selStart, int selEnd)
{
    if (!doc)
        return;
    span::applyColor(doc->textDocument(), role, colorName, selStart, selEnd);
}

void EditorModel::formatLink(const QString &url, QQuickTextDocument *doc, int selStart, int selEnd)
{
    if (!doc)
        return;
    span::applyLink(doc->textDocument(), url, selStart, selEnd);
}

void EditorModel::setCaption(int rowStart, QQuickTextDocument *doc, int endIndex)
{
    Q_UNUSED(endIndex);
    const int idx = visibleRowToBlockIndex(rowStart);
    if (idx < 0 || !doc)
        return;
    Block &b = m_blocks[idx];
    if (b.type != BlockType::Image && b.type != BlockType::Audio)
        return;
    b.runs = runsFromDoc(doc);
    markDirty();
}

void EditorModel::setComponentSize(int row, double widthPercent)
{
    const int idx = visibleRowToBlockIndex(row);
    if (idx < 0)
        return;
    m_blocks[idx].widthPercent = qBound(0.25, widthPercent, 1.0);
    emit dataChanged(index(row), index(row), { WidthPercentRole });
    markDirty();
}

void EditorModel::insertImageBlockAfter(int row, const QString &assetId, const QString &caption)
{
    const int idx = visibleRowToBlockIndex(row);
    const int insertAt = idx < 0 ? int(m_blocks.size()) : idx + 1;
    Block b;
    b.id = newUuid();
    b.type = BlockType::Image;
    b.assetId = assetId;
    b.widthPercent = 1.0;
    if (!caption.isEmpty()) {
        Run r;
        r.text = caption;
        b.runs.append(r);
    }
    m_blocks.insert(insertAt, b);
    applyCollapseAwareReset();
    markDirty();
}

void EditorModel::insertPdfBlockAfter(int row, const QString &assetId, const QString &fileName)
{
    const int idx = visibleRowToBlockIndex(row);
    const int insertAt = idx < 0 ? int(m_blocks.size()) : idx + 1;
    Block b;
    b.id = newUuid();
    b.type = BlockType::Pdf;
    b.assetId = assetId;
    b.fileName = fileName;
    b.pageCount = 1;
    b.lastReadPage = 1;
    m_blocks.insert(insertAt, b);
    applyCollapseAwareReset();
    markDirty();
}

void EditorModel::insertAudioBlockAfter(int row, const QString &assetId, int durationSeconds)
{
    const int idx = visibleRowToBlockIndex(row);
    const int insertAt = idx < 0 ? int(m_blocks.size()) : idx + 1;
    Block b;
    b.id = newUuid();
    b.type = BlockType::Audio;
    b.assetId = assetId;
    b.durationSeconds = durationSeconds;
    m_blocks.insert(insertAt, b);
    applyCollapseAwareReset();
    markDirty();
}

void EditorModel::insertEmbedBlockAfter(int row, const QString &targetObjectId)
{
    const int idx = visibleRowToBlockIndex(row);
    const int insertAt = idx < 0 ? int(m_blocks.size()) : idx + 1;
    Block b;
    b.id = newUuid();
    b.type = BlockType::Embed;
    b.objectId = targetObjectId;
    b.viewMode = QStringLiteral("card");
    m_blocks.insert(insertAt, b);
    applyCollapseAwareReset();
    markDirty();
}

void EditorModel::insertBookmarkAfter(int row, const QString &url, const QString &weblinkId)
{
    const int idx = visibleRowToBlockIndex(row);
    const int insertAt = idx < 0 ? int(m_blocks.size()) : idx + 1;
    const QString cleanUrl = url.trimmed();
    QUrl u(cleanUrl);
    Block b;
    b.id = newUuid();
    b.type = BlockType::Bookmark;
    b.url = cleanUrl;
    b.title = cleanUrl;
    b.domain = u.host();
    if (b.domain.startsWith(QStringLiteral("www.")))
        b.domain = b.domain.mid(4);
    b.objectId = weblinkId;
    if (b.objectId.isEmpty() && m_ws && !cleanUrl.isEmpty())
        b.objectId = m_ws->createWebLinkFromUrl(cleanUrl, m_object.id, b.id);
    Blocks next = m_blocks;
    next.insert(insertAt, b);
    applyIncrementalBlocks(next);
    markDirty();
}

void EditorModel::applyBookmarkPreview(const QString &weblinkId, const QString &url, const QString &title,
                                      const QString &description, const QString &thumbnailUrl,
                                      const QString &faviconUrl)
{
    bool anyChanged = false;
    for (int row = 0; row < m_visible.size(); ++row) {
        const int idx = m_visible[row].blockIndex;
        if (idx < 0 || idx >= m_blocks.size())
            continue;
        Block &b = m_blocks[idx];
        if (b.type != BlockType::Bookmark)
            continue;
        const bool matchId = !weblinkId.isEmpty() && b.objectId == weblinkId;
        const bool matchUrl = !url.isEmpty() && b.url == url;
        if (!matchId && !matchUrl)
            continue;
        bool rowChanged = false;
        if (!weblinkId.isEmpty() && b.objectId.isEmpty()) { b.objectId = weblinkId; rowChanged = true; }
        if (!title.isEmpty() && b.title != title) { b.title = title; rowChanged = true; }
        if (!description.isEmpty() && b.description != description) { b.description = description; rowChanged = true; }
        if (!thumbnailUrl.isEmpty() && b.thumbnailUrl != thumbnailUrl) { b.thumbnailUrl = thumbnailUrl; rowChanged = true; }
        if (!faviconUrl.isEmpty() && b.faviconUrl != faviconUrl) { b.faviconUrl = faviconUrl; rowChanged = true; }
        if (rowChanged) {
            anyChanged = true;
            emit dataChanged(index(row), index(row),
                             { BookmarkTitleRole, BookmarkDescRole, DomainRole,
                               ThumbnailUrlRole, FaviconUrlRole, ObjectEmbedRole });
        }
    }
    if (anyChanged) {
        markDirty();
        emit metaChanged();
    }
}

void EditorModel::forceFocus(const QString &blockId, int position)
{
    setFocus(blockId, position);
}

void EditorModel::refreshAssetsFor(const QString &assetId)
{
    for (int row = 0; row < m_visible.size(); ++row) {
        if (m_visible[row].block->assetId == assetId) {
            const QModelIndex i = index(row);
            emit dataChanged(i, i, { AssetPathRole, PageCountRole, DurationRole });
        }
    }
}

void EditorModel::insertCanvasBlockAfter(int row)
{
    const int idx = visibleRowToBlockIndex(row);
    const int insertAt = idx < 0 ? int(m_blocks.size()) : idx + 1;
    Block b;
    b.id = newUuid();
    b.type = BlockType::Canvas;
    b.canvasData = QJsonObject{ { "id", newUuid() } };
    m_blocks.insert(insertAt, b);
    applyCollapseAwareReset();
    markDirty();
}

// ---------------------------------------------------------------------------
// Inline inserts into the live document
// ---------------------------------------------------------------------------
void EditorModel::insertAnchorText(QQuickTextDocument *doc, int caretEnd, const QString &visible,
                                   const QString &anchorUrl, const QString &fg, const QString &bg)
{
    QTextDocument *td = doc ? doc->textDocument() : nullptr;
    if (!td)
        return;
    QTextCursor c(td);
    c.setPosition(qBound(0, caretEnd, td->characterCount() - 1));
    QTextCharFormat f;
    f.setAnchor(true);
    f.setAnchorHref(anchorUrl);
    f.setForeground(QColor(fg));
    if (!bg.isEmpty())
        f.setBackground(QColor(bg));
    c.insertText(visible, f);
}

int EditorModel::insertPlainAtChipEdge(QQuickTextDocument *doc, int pos, const QString &text)
{
    QTextDocument *td = doc ? doc->textDocument() : nullptr;
    if (!td || text.isEmpty() || pos < 0 || pos >= td->characterCount())
        return -1;
    auto chipAt = [td](int p) {
        if (p < 0 || p >= td->characterCount() - 1)
            return false;
        QTextCursor c(td);
        c.setPosition(p);
        c.setPosition(p + 1, QTextCursor::KeepAnchor);
        return c.charFormat().anchorHref().startsWith(QLatin1String("yaop"));
    };
    if (!chipAt(pos - 1) && !chipAt(pos))
        return -1;
    QTextCursor c(td);
    c.setPosition(pos);
    QTextCharFormat plain = c.charFormat();
    plain.setAnchor(false);
    plain.setAnchorHref(QString());
    plain.clearForeground();
    plain.clearBackground();
    c.insertText(text, plain);
    return pos + int(text.size());
}

QVariantList EditorModel::mentionRanges(QQuickTextDocument *doc) const
{
    QVariantList out;
    if (!doc || !doc->textDocument())
        return out;
    int start = -1, end = -1;
    QString href;
    auto flush = [&]() {
        if (start >= 0)
            out.append(QVariantMap{ { QStringLiteral("start"), start }, { QStringLiteral("length"), end - start } });
        start = -1;
    };
    for (QTextBlock b = doc->textDocument()->begin(); b.isValid(); b = b.next()) {
        for (auto it = b.begin(); !it.atEnd(); ++it) {
            const QTextFragment f = it.fragment();
            const QString h = f.charFormat().anchorHref();
            if (!h.startsWith(QLatin1String("yaop://object/"))) {
                flush();
                continue;
            }
            if (start >= 0 && h == href && f.position() == end) { // one mention split in fragments
                end = f.position() + f.length();
                continue;
            }
            flush();
            start = f.position();
            end = f.position() + f.length();
            href = h;
        }
        flush();
    }
    return out;
}

void EditorModel::insertMentionAt(int row, int caret, QQuickTextDocument *doc,
                                  const QString &objectIdIn, const QString &labelIn)
{
    const int idx = visibleRowToBlockIndex(row);
    if (idx < 0 || !doc)
        return;
    const QString objectId = objectIdIn;
    QString label = labelIn;
    while (label.startsWith(QLatin1Char('@')))
        label.remove(0, 1);
    if (label.isEmpty())
        label = objectId;
    insertAnchorText(doc, caret, QLatin1Char('@') + label,
                     QStringLiteral("yaop://object/") + objectId, "#BFE3FF", QString());
    m_blocks[idx].runs = runsFromDoc(doc);
    emit dataChanged(index(row), index(row), { HtmlRole, PlainTextRole });
    markDirty();
    setFocus(m_blocks[idx].id, caret + label.length() + 1);
}

void EditorModel::insertTagAt(int row, int caret, QQuickTextDocument *doc, const QString &tagName)
{
    const int idx = visibleRowToBlockIndex(row);
    if (idx < 0 || !doc)
        return;
    const QString name = QString(tagName).remove(QLatin1Char('#')).trimmed();
    if (name.isEmpty())
        return;
    if (m_ws)
        m_ws->ensureTagObject(name);
    const QString id = tagKey(name);
    insertAnchorText(doc, caret, QLatin1Char('#') + name,
                     QStringLiteral("yaop-tag://") + id + QLatin1Char('/') + name,
                     "#C9F2A6", QString());
    m_blocks[idx].runs = runsFromDoc(doc);
    emit dataChanged(index(row), index(row), { HtmlRole, PlainTextRole, HasMentionRole });
    markDirty();
    setFocus(m_blocks[idx].id, caret + name.length() + 1);
}

void EditorModel::insertDateAt(int row, int caret, QQuickTextDocument *doc, const QString &isoDate)
{
    const int idx = visibleRowToBlockIndex(row);
    if (idx < 0 || !doc)
        return;
    insertAnchorText(doc, caret, QLatin1Char('@') + isoDate,
                     QStringLiteral("yaop-date://") + isoDate, "#E8EEFF", "#2B3D7C");
    m_blocks[idx].runs = runsFromDoc(doc);
    emit dataChanged(index(row), index(row), { HtmlRole, PlainTextRole, HasMentionRole });
    markDirty();
    setFocus(m_blocks[idx].id, caret + isoDate.length() + 1);
}

void EditorModel::insertLinkAt(int row, int caretStart, int selectionEnd, QQuickTextDocument *doc,
                               const QString &url)
{
    const int idx = visibleRowToBlockIndex(row);
    if (idx < 0 || !doc)
        return;
    const auto from = qMin(caretStart, selectionEnd);
    const auto to = qMax(caretStart, selectionEnd);
    QTextDocument *td = doc->textDocument();
    span::applyLink(td, url, from, to);
    m_blocks[idx].runs = runsFromDoc(doc);
    emit dataChanged(index(row), index(row), { HtmlRole, PlainTextRole });
    markDirty();
}

void EditorModel::removeAnchorAt(int row, int caret, QQuickTextDocument *doc)
{
    const int idx = visibleRowToBlockIndex(row);
    if (idx < 0 || !doc)
        return;
    QTextDocument *td = doc->textDocument();
    const int docLen = qMax(0, td->characterCount() - 1);
    if (docLen <= 0)
        return;

    // Inspect the char at caret (or caret - 1 when caret sits at the end of an anchor).
    int probePos = qBound(1, caret > 0 ? caret : 1, docLen);
    QTextCursor pc(td);
    pc.setPosition(probePos);
    if (!pc.charFormat().isAnchor() && caret < docLen) {
        probePos = qBound(1, caret + 1, docLen);
        pc.setPosition(probePos);
    }
    const QTextCharFormat f = pc.charFormat();
    const QString href = f.anchorHref();
    if (!f.isAnchor() || href.isEmpty())
        return;

    int start = probePos - 1;
    int end = probePos;
    while (start > 0) {
        QTextCursor qc(td);
        qc.setPosition(start);
        if (!qc.charFormat().isAnchor() || qc.charFormat().anchorHref() != href)
            break;
        --start;
    }
    while (end < docLen) {
        QTextCursor qc(td);
        qc.setPosition(end + 1);
        if (!qc.charFormat().isAnchor() || qc.charFormat().anchorHref() != href)
            break;
        ++end;
    }

    QTextCursor c(td);
    c.setPosition(start);
    c.setPosition(end, QTextCursor::KeepAnchor);
    c.removeSelectedText();
    c.setCharFormat(QTextCharFormat());

    m_blocks[idx].runs = runsFromDoc(doc);
    emit dataChanged(index(row), index(row), { HtmlRole, PlainTextRole, HasMentionRole });
    emit blockHtmlReplaced(m_blocks[idx].id);
    markDirty();
    scheduleSave(250);
    setFocus(m_blocks[idx].id, start);
}

bool EditorModel::deleteAnchorOnKey(int row, int caret, QQuickTextDocument *doc, bool isDeleteKey)
{
    const int idx = visibleRowToBlockIndex(row);
    if (idx < 0 || !doc)
        return false;
    QTextDocument *td = doc->textDocument();
    const int docLen = qMax(0, td->characterCount() - 1);
    if (docLen <= 0)
        return false;

    const int probePos = isDeleteKey ? (caret + 1) : caret;
    if (probePos < 1 || probePos > docLen)
        return false;

    QTextCursor pc(td);
    pc.setPosition(probePos);
    const QTextCharFormat f = pc.charFormat();
    const QString href = f.anchorHref();
    if (!f.isAnchor() || href.isEmpty())
        return false;

    int start = probePos - 1;
    int end = probePos;
    while (start > 0) {
        QTextCursor qc(td);
        qc.setPosition(start);
        if (!qc.charFormat().isAnchor() || qc.charFormat().anchorHref() != href)
            break;
        --start;
    }
    while (end < docLen) {
        QTextCursor qc(td);
        qc.setPosition(end + 1);
        if (!qc.charFormat().isAnchor() || qc.charFormat().anchorHref() != href)
            break;
        ++end;
    }

    QTextCursor c(td);
    c.setPosition(start);
    c.setPosition(end, QTextCursor::KeepAnchor);
    c.removeSelectedText();
    c.setCharFormat(QTextCharFormat());

    m_blocks[idx].runs = runsFromDoc(doc);
    emit dataChanged(index(row), index(row), { PlainTextRole, HasMentionRole });
    markDirty();
    scheduleSave(250);
    return true;
}

QVariantList EditorModel::findMatches(const QString &needle) const
{
    QVariantList out;
    if (needle.trimmed().isEmpty())
        return out;
    for (const Block &b : m_blocks) {
        const QString text = b.plainText();
        int from = 0;
        while (true) {
            const int at = text.indexOf(needle, from, Qt::CaseInsensitive);
            if (at < 0)
                break;
            QVariantMap m;
            m.insert("blockId", b.id);
            m.insert("offset", at);
            m.insert("length", needle.length());
            out.append(m);
            from = at + needle.length();
        }
    }
    return out;
}

QString EditorModel::debugSnapshot() const
{
    QJsonArray arr;
    for (const Block &b : m_blocks) {
        QJsonObject o;
        o.insert("id", b.id);
        o.insert("type", b.type);
        QJsonArray runs;
        for (const Run &r : b.runs) {
            QJsonObject ro;
            ro.insert("text", runVisibleText(r));
            if (!r.objectId.isEmpty())
                ro.insert("object_id", r.objectId);
            if (!r.textColor.isEmpty())
                ro.insert("text_color", r.textColor);
            if (!r.bgColor.isEmpty())
                ro.insert("bg_color", r.bgColor);
            if (r.bold)
                ro.insert("bold", true);
            runs.append(ro);
        }
        o.insert("runs", runs);
        arr.append(o);
    }
    return QString::fromUtf8(QJsonDocument(arr).toJson(QJsonDocument::Compact));
}

} // namespace pkm
