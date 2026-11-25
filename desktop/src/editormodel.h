#pragma once

// EditorModel: live editing session for one open object. The model exposes the
// visible blocks (toggle-aware) to QML and owns every structural edit
// (enter/backspace/markdown/convert/indent/move/duplicate/group/color), the
// atomic mention/tag/date inserts into the live QTextDocuments, and the
// debounced dirty commits back to the Workspace.

#include "model.h"
#include "spancodec.h"
#include "undohistory.h"

#include <QAbstractListModel>
#include <QQuickTextDocument>
#include <QSet>
#include <QTimer>

namespace pkm {

class Workspace;

class EditorModel : public QAbstractListModel {
    Q_OBJECT
    Q_PROPERTY(QString objectId READ objectId CONSTANT)
    Q_PROPERTY(QString title READ title WRITE setTitle NOTIFY titleChanged)
    Q_PROPERTY(QString plainTitle READ plainTitle NOTIFY titleChanged)
    Q_PROPERTY(QString typeId READ typeId NOTIFY typeChanged)
    Q_PROPERTY(QString coverAssetId READ coverAssetId NOTIFY coverChanged)
    Q_PROPERTY(QString thumbnailUrl READ thumbnailUrl NOTIFY coverChanged)
    Q_PROPERTY(QString url READ url NOTIFY metaChanged)
    Q_PROPERTY(QString description READ description NOTIFY metaChanged)
    Q_PROPERTY(QString faviconUrl READ faviconUrl NOTIFY metaChanged)
    Q_PROPERTY(QString domain READ domain NOTIFY metaChanged)
    Q_PROPERTY(QString assetId READ assetId CONSTANT)
    Q_PROPERTY(double coverCropY READ coverCropY CONSTANT)
    Q_PROPERTY(bool coverFit READ coverFit CONSTANT)
    Q_PROPERTY(bool dirty READ dirty NOTIFY dirtyChanged)
    Q_PROPERTY(int blockCount READ blockCount NOTIFY structureChanged)
    Q_PROPERTY(QVariantList outline READ outline NOTIFY structureChanged)
    Q_PROPERTY(QString focusBlockId READ focusBlockId NOTIFY focusChanged)
    Q_PROPERTY(int focusPosition READ focusPosition NOTIFY focusChanged)
    Q_PROPERTY(int selectedCount READ selectedCount NOTIFY selectionChanged)
    Q_PROPERTY(QString activeEditingId READ objectId CONSTANT)
    Q_PROPERTY(bool canUndo READ canUndo NOTIFY historyChanged)
    Q_PROPERTY(bool canRedo READ canRedo NOTIFY historyChanged)

public:
    enum Roles {
        BlockIdRole = Qt::UserRole + 1,
        TypeRole,
        HtmlRole,           // rendered rich content for TextEdit delegates
        PlainTextRole,
        IndentRole,
        CheckedRole,
        ExpandedRole,
        LevelRole,          // heading level
        NumberRole,         // sequenced list ordinal
        NumberLabelRole,    // "3." / "c." / "IV."
        CalloutTypeRole,
        EmojiRole,
        LanguageRole,
        CodeRoleText,
        LatexRole,
        RowsRole,           // QVariantList of QVariantList
        AlignRole,
        AssetIdRole,
        AssetPathRole,      // local cached path
        AssetKindRole,      // image/audio/pdf
        WidthPercentRole,
        DurationRole,
        PageCountRole,
        LastReadPageRole,
        FileNameRole,
        UrlRole,
        BookmarkTitleRole,
        BookmarkDescRole,
        DomainRole,
        ThumbnailUrlRole,
        FaviconUrlRole,
        ObjectEmbedRole,
        EmbedViewModeRole,
        ColorRole,          // block color name
        BlockColorHexRole,
        FirstInGroupRole,
        LastInGroupRole,
        HiddenChildrenRole,
        SelectedRole,
        HasMentionRole,
        IsGroupedRole,
    };

    explicit EditorModel(Workspace *ws, QObject *parent = nullptr);
    ~EditorModel() override;

    void load(const Object &object);

    // model interface
    int rowCount(const QModelIndex &parent = {}) const override;
    QVariant data(const QModelIndex &index, int role) const override;
    QHash<int, QByteArray> roleNames() const override;

    QString objectId() const { return m_object.id; }
    QString title() const { return m_title; }
    void setTitle(const QString &t);
    QString plainTitle() const { return m_title.isEmpty() ? tr("Untitled") : m_title; }
    QString typeId() const { return m_object.typeId; }
    QString coverAssetId() const { return m_object.coverAssetId(); }
    QString thumbnailUrl() const;
    QString url() const;
    QString description() const;
    QString faviconUrl() const;
    QString domain() const;
    QString assetId() const { return m_object.propertyString(QStringLiteral("asset_id")); }
    double coverCropY() const;
    bool coverFit() const;
    bool dirty() const { return m_dirty; }
    int blockCount() const { return int(m_blocks.size()); }
    QVariantList outline() const;
    QString focusBlockId() const { return m_focusBlockId; }
    int focusPosition() const { return m_focusPosition; }
    bool canUndo() const { return m_history.canUndo(); }
    bool canRedo() const { return m_history.canRedo(); }

    // Document undo/redo (Ctrl+Z / Ctrl+Shift+Z). The caller commits pending typing first.
    Q_INVOKABLE bool undo();
    Q_INVOKABLE bool redo();

    // ---------------- editing operations (from QML) ----------------
    Q_INVOKABLE void commitRichText(int row, QQuickTextDocument *doc, int cursorPos);
    Q_INVOKABLE int handleEnter(int row, int caret, QQuickTextDocument *doc);
    // Returns true when a markdown prefix transformed the block.
    Q_INVOKABLE bool handleMarkdownPrefix(int row, QQuickTextDocument *doc);
    Q_INVOKABLE void handleBackspace(int row, int caret, QQuickTextDocument *doc);

    Q_INVOKABLE void convertBlock(int row, const QString &targetKey);
    Q_INVOKABLE void toggleTodo(int row);
    Q_INVOKABLE void toggleExpand(int row);
    Q_INVOKABLE void indentBlocks(int row, int delta, int caret = -1);
    Q_INVOKABLE void moveBlocks(const QStringList &ids, int delta);
    Q_INVOKABLE void moveBlockTo(int fromRow, int toRow); // drag & drop reorder
    Q_INVOKABLE void duplicateBlocks(const QStringList &ids);
    Q_INVOKABLE void groupBlocks(const QStringList &ids);
    Q_INVOKABLE void setBlockColor(int row, const QString &colorName);
    Q_INVOKABLE void setSelected(const QStringList &ids);
    Q_INVOKABLE void selectRange(int fromRow, int toRow);
    Q_INVOKABLE void toggleSelected(const QString &id);
    Q_INVOKABLE void selectAllBlocks();
    Q_INVOKABLE void clearSelection();
    Q_INVOKABLE void deleteSelected();
    Q_INVOKABLE void removeBlockAt(int row);
    Q_INVOKABLE int selectedCount() const { return int(m_selected.size()); }
    Q_INVOKABLE QStringList selectedIds() const;
    Q_INVOKABLE bool copySelected(bool cut = false);
    Q_INVOKABLE bool copySelectedAs(const QString &format);
    Q_INVOKABLE bool pasteFromClipboard(int row, int caret, QQuickTextDocument *doc);
    bool pasteBlocksAt(int row, int caret, QQuickTextDocument *doc, const Blocks &rawIncoming);

    Q_INVOKABLE void insertBlockAfter(int row, const QString &typeKey);
    Q_INVOKABLE void insertDividerAfter(int row);
    Q_INVOKABLE void insertTableAfter(int row, int rows, int cols);
    Q_INVOKABLE void addTableRows(int row, int count);
    Q_INVOKABLE void addTableColumns(int row, int count);
    Q_INVOKABLE void setCell(int row, int r, int c, const QString &text);
    Q_INVOKABLE void setHeadingLevel(int row, int level);
    Q_INVOKABLE void setCodeLanguage(int row, const QString &language);
    // Code block body (plain text). The QML editor calls this on every pause; without it nothing
    // typed in a code block was ever persisted.
    Q_INVOKABLE void commitCodeText(int row, const QString &text);
    // Non-text blocks (audio player, table cell...) tell the workspace the object is being edited.
    Q_INVOKABLE void setFocusedBlock(const QString &blockId, bool focused);
    Q_INVOKABLE void setLatex(int row, const QString &latex);
    Q_INVOKABLE void setCaption(int rowStart, QQuickTextDocument *doc, int endIndex); // image/audio caption edit
    Q_INVOKABLE void setPdfPage(int row, int page);
    Q_INVOKABLE void setComponentSize(int row, double widthPercent);

    Q_INVOKABLE void insertDivider(int row) { insertDividerAfter(row); }

    // Mention / tag / date / link inserts into the live document.
    // Where the object mentions sit in a live block document: [{start, length}] in characters,
    // for the pill drawn behind each one (a QTextDocument cannot draw rounded backgrounds).
    Q_INVOKABLE QVariantList mentionRanges(QQuickTextDocument *doc) const;
    // Typing right next to a chip (mention, tag, date) would inherit its link format and grow the
    // chip. When `pos` touches one, inserts `text` there as plain text and returns the new caret;
    // returns -1 (nothing done) otherwise, so the editor types normally.
    Q_INVOKABLE int insertPlainAtChipEdge(QQuickTextDocument *doc, int pos, const QString &text);
    Q_INVOKABLE void insertMentionAt(int row, int caret, QQuickTextDocument *doc,
                                     const QString &objectId, const QString &label);
    Q_INVOKABLE void insertTagAt(int row, int caret, QQuickTextDocument *doc,
                                const QString &tagName);
    Q_INVOKABLE void insertDateAt(int row, int caret, QQuickTextDocument *doc,
                                 const QString &isoDate);
    Q_INVOKABLE void insertLinkAt(int row, int caret, int selectionEnd, QQuickTextDocument *doc,
                                 const QString &url);
    Q_INVOKABLE void removeAnchorAt(int row, int caret, QQuickTextDocument *doc);
    Q_INVOKABLE bool deleteAnchorOnKey(int row, int caret, QQuickTextDocument *doc, bool isDeleteKey);
    void applyBookmarkPreview(const QString &weblinkId, const QString &url, const QString &title,
                              const QString &description, const QString &thumbnailUrl,
                              const QString &faviconUrl);
    void notifyCoverUpdated();

    // Inline formatting on the live document driven by the selection toolbar.
    Q_INVOKABLE void formatMark(const QString &mark, QQuickTextDocument *doc, int selStart, int selEnd);
    Q_INVOKABLE bool markActive(const QString &mark, QQuickTextDocument *doc, int selStart, int selEnd);
    Q_INVOKABLE void formatColor(const QString &role, const QString &colorName,
                                 QQuickTextDocument *doc, int selStart, int selEnd);
    Q_INVOKABLE void formatLink(const QString &url, QQuickTextDocument *doc, int selStart, int selEnd);
    Q_INVOKABLE QString selectionText(QQuickTextDocument *doc, int selStart, int selEnd) const;
    Q_INVOKABLE void replaceSelectionWithMention(int row, QQuickTextDocument *doc, int selStart, int selEnd,
                                                 const QString &objectId, const QString &label);
    // Soft line break inside the current text block (Shift+Enter): inserts U+2028 via QTextCursor
    // so RichText TextEdit creates a real visual line break that serializes as '\n'.
    Q_INVOKABLE int insertLineBreak(QQuickTextDocument *doc, int selStart, int selEnd);
    Q_INVOKABLE void setEmbedViewMode(int row, const QString &mode);

    // Table block structural helpers (kept small: full grid editing in QML)
    Q_INVOKABLE void insertImageBlockAfter(int row, const QString &assetId, const QString &caption);
    Q_INVOKABLE void insertPdfBlockAfter(int row, const QString &assetId, const QString &fileName);
    Q_INVOKABLE void insertAudioBlockAfter(int row, const QString &assetId, int durationSeconds);
    Q_INVOKABLE void insertEmbedBlockAfter(int row, const QString &targetObjectId);
    Q_INVOKABLE void insertBookmarkAfter(int row, const QString &url, const QString &weblinkId = QString());
    // Paste URL menu (HowTo.dc.html flow 6): "link" | "chip" | "card" | "wide"
    Q_INVOKABLE void pasteUrlAs(int row, int caret, QQuickTextDocument *doc,
                                const QString &url, const QString &mode);
    Q_INVOKABLE void insertCanvasBlockAfter(int row);
    Q_INVOKABLE void forceFocus(const QString &blockId, int position = 0);
    Q_INVOKABLE void clearFocusTarget(const QString &blockId);
    Q_INVOKABLE void refreshAssetsFor(const QString &assetId);

    // Find inside this object: returns list of {row, offset, length} maps.
    Q_INVOKABLE QVariantList findMatches(const QString &needle) const;

    // Diagnostics (--drive): JSON dump of blocks with run styles/colors.
    Q_INVOKABLE QString debugSnapshot() const;

    // Live doc offset mapping between doc positions when text changes.
    Q_INVOKABLE void documentEdited(int row, QQuickTextDocument *doc, int cursorPos);
    Q_INVOKABLE void documentFocused(int row, QQuickTextDocument *doc, int cursorPos);
    Q_INVOKABLE void documentUnfocused(int row, QQuickTextDocument *doc, int cursorPos);
    Q_INVOKABLE void documentUnfocusedBlock(const QString &blockId, QQuickTextDocument *doc, int cursorPos);

signals:
    void titleChanged();
    void typeChanged();
    void coverChanged();
    void metaChanged();
    void dirtyChanged();
    void structureChanged();
    void focusChanged();
    void blockHtmlReplaced(const QString &blockId);
    void selectionChanged();
    void blockCreated(const QString &blockId);
    void scrollFocusRequest(QString blockId, int row);
    void objectSaved(QString objectId);
    void historyChanged();

public slots:
    void flushNow();

protected:
    void timerEvent(QTimerEvent *event) override;

private:
    struct VisibleRow {
        int blockIndex = 0;
        const Block *block = nullptr;
        int firstInGroup = 0;
        int lastInGroup = 0;
        int hiddenChildren = 0;
    };

    void rebuildVisible();
    void applyIncrementalBlocks(const Blocks &nextBlocks, const QVector<int> &hintRoles = {});
    void refreshRowsFrom(int start);
    void markDirty();
    // Takes the live text of a block before a structural edit, as its own undo step.
    void pullDoc(int idx, QQuickTextDocument *doc);
    void showEditState(const EditState &to);
    void scheduleSave(int ms = 1200);
    void commitObjectToWorkspace();
    void setFocus(const QString &blockId, int pos);
    int visibleRowToBlockIndex(int row) const;
    int blockIdToVisibleRow(const QString &id) const;
    void applyCollapseAwareReset();
    void emitSelectionChanged();
    QVector<Run> runsFromDoc(QQuickTextDocument *doc) const;
    void insertAnchorText(QQuickTextDocument *doc, int caretEnd, const QString &visible,
                          const QString &anchorUrl, const QString &fg, const QString &bg);

    Workspace *m_ws;
    Object m_object;
    Blocks m_blocks;
    QString m_title;
    bool m_dirty = false;
    int m_saveTimerId = 0;
    QSet<QString> m_collapsed;
    QSet<QString> m_selected;
    QVector<VisibleRow> m_visible;
    QString m_focusBlockId;
    int m_focusPosition = 0;
    UndoHistory m_history;
    bool m_restoring = false;
    friend class Workspace;
};

} // namespace pkm
