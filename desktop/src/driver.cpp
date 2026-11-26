#include "driver.h"
#include "audiorecorder.h"
#include "onboarding.h"
#include "editormodel.h"
#include "model.h"
#include "workspace.h"

#include <QCoreApplication>
#include <QCryptographicHash>
#include <QElapsedTimer>
#include <QEventLoop>
#include <QFile>
#include <QFileInfo>
#include <QProcess>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QKeyEvent>
#include <QImage>
#include <QDir>
#include <QQuickItem>
#include <QSettings>
#include <QQuickTextDocument>
#include <QQuickWindow>
#include <QTextBlock>
#include <QTextDocument>
#include <QTimer>
#include <QTest>
#include <QWindow>
#include <QDate>
#include <functional>
#include <memory>

#include <algorithm>
#include <cmath>
#include <cstdio>

namespace pkm {

namespace {

// --drive-out/YAOP_DRIVE_DIR redirects the drive logs and screenshots out of /tmp.
QString driveOutDir()
{
    const QString env = qEnvironmentVariable("YAOP_DRIVE_DIR");
    return env.isEmpty() ? QStringLiteral("/tmp") : env;
}

void dlog(const QString &line)
{
    QFile f(driveOutDir() + QStringLiteral("/drive.log"));
    if (f.open(QIODevice::WriteOnly | QIODevice::Append))
        f.write(line.toUtf8() + QByteArrayLiteral("\n"));
}

// The item with this objectName that is on screen: several editors (tabs, side pane) may exist,
// and the first one found is often a hidden one.
QQuickItem *visibleNamed(QQuickWindow *window, const QString &name)
{
    QQuickItem *first = nullptr;
    for (QQuickItem *it : window->findChildren<QQuickItem *>(name)) {
        if (it->isVisible())
            return it;
        if (!first)
            first = it;
    }
    return first;
}

QQuickItem *editorPageOf(QQuickWindow *window)
{
    return visibleNamed(window, QStringLiteral("editorPage"));
}

QQuickItem *blocksViewOf(QQuickWindow *window)
{
    return visibleNamed(window, QStringLiteral("blocks"));
}

EditorModel *sessionOf(QQuickWindow *window)
{
    QQuickItem *page = editorPageOf(window);
    if (!page)
        return nullptr;
    const QVariant v = page->property("session");
    if (!v.value<QObject *>())
        return nullptr;
    return qobject_cast<EditorModel *>(v.value<QObject *>());
}

QString shotPath(const QString &name)
{
    return driveOutDir() + QStringLiteral("/shots/") + name;
}

void shot(QQuickWindow *window, const QString &path)
{
    const QImage image = window->grabWindow();
    dlog(QStringLiteral("DRIVE shot %1 %2x%3 ok=%4")
             .arg(path)
             .arg(image.size().width())
             .arg(image.size().height())
             .arg(image.save(path) ? 1 : 0));
}

void sendWindowKey(QQuickWindow *window, int key, const QString &text,
                   Qt::KeyboardModifiers mods = Qt::NoModifier)
{
    QObject *target = window->focusObject();
    if (!target)
        target = window;
    QKeyEvent press(QEvent::KeyPress, key, mods, text);
    QKeyEvent release(QEvent::KeyRelease, key, mods, text);
    QCoreApplication::sendEvent(target, &press);
    QCoreApplication::sendEvent(target, &release);
}

void typeText(QQuickWindow *window, const QString &text)
{
    for (const QChar &ch : text) {
        int key = 0;
        switch (ch.unicode()) {
        case ' ': key = Qt::Key_Space; break;
        case '\n': key = Qt::Key_Return; break;
        case '@': key = Qt::Key_At; break;
        case '#': key = Qt::Key_NumberSign; break;
        case '-': key = Qt::Key_Minus; break;
        default: key = ch.unicode(); break;
        }
        sendWindowKey(window, key, QString(ch));
    }
}

QString firstBlockId(QQuickWindow *window)
{
    EditorModel *model = sessionOf(window);
    const QString snap = model ? model->debugSnapshot() : QString();
    const QJsonArray arr = QJsonDocument::fromJson(snap.toUtf8()).array();
    return arr.size() > 0 ? arr.at(0).toObject().value(QLatin1String("id")).toString() : QString();
}

void dumpState(QQuickWindow *window, const QString &tag)
{
    EditorModel *model = sessionOf(window);
    QQuickItem *blocks = blocksViewOf(window);
    const QString trigger = blocks ? blocks->property("triggerKind").toString()
                                   : QStringLiteral("?");
    const QString query = blocks ? blocks->property("triggerQuery").toString() : QStringLiteral("?");
    QString focusText = QStringLiteral("(no session)");
    if (model) {
        const QJsonArray arr = QJsonDocument::fromJson(model->debugSnapshot().toUtf8()).array();
        for (const QJsonValue &e : arr) {
            if (e.toObject().value(QLatin1String("id")).toString() == model->focusBlockId()) {
                focusText = QJsonDocument(e.toObject().value(QLatin1String("runs")).toArray())
                                .toJson(QJsonDocument::Compact);
            }
        }
    }
    const QList<QQuickItem *> edits = window->findChildren<QQuickItem *>(QStringLiteral("blockEdit"));
    for (QQuickItem *it : edits) {
        const QColor c = it->property("color").value<QColor>();
        const QString text = it->property("text").toString().left(160);
        dlog(QStringLiteral("DRIVE-edit %1 color=%2 len=%3 text=%4")
                 .arg(tag, c.name())
                 .arg(it->property("length").toInt())
                 .arg(text));
    }
    QObject *fo = window->focusObject();
    dlog(QStringLiteral("DRIVE-focusObj %1 cls=%2")
             .arg(tag, fo ? QString::fromLatin1(fo->metaObject()->className()) : QString()));
    const QVariant docV = fo ? fo->property("textDocument") : QVariant();
    if (docV.value<QQuickTextDocument *>()) {
        QTextDocument *td = docV.value<QQuickTextDocument *>()->textDocument();
        dlog(QStringLiteral("DRIVE-doc %1 defaultColor=%2 blockFmtFg=%3")
                 .arg(tag)
                 .arg(td->defaultFont().toString().left(40))
                 .arg(td->firstBlock().charFormat().foreground().color().name()));
        for (QTextBlock b = td->begin(); b.isValid(); b = b.next()) {
            int fragCount = 0;
            for (auto it2 = b.begin(); !it2.atEnd(); ++it2) {
                const QTextFragment fr = it2.fragment();
                if (!fr.isValid())
                    continue;
                ++fragCount;
                const QColor fg = fr.charFormat().foreground().color();
                const QColor bg = fr.charFormat().background().color();
                dlog(QStringLiteral("DRIVE-frag %1 pos=%2 len=%3 text=%4 fg=%5(%6) bg=%7(%8) anchor=%9")
                         .arg(tag)
                         .arg(fr.position())
                         .arg(fr.length())
                         .arg(QString(fr.text()).left(40))
                         .arg(fg.isValid() ? fg.name() : QString("valid_no"))
                         .arg(fg.isValid() ? 1 : 0)
                         .arg(bg.isValid() ? bg.name() : QString("valid_no"))
                         .arg(bg.isValid() ? 1 : 0)
                         .arg(fr.charFormat().isAnchor() ? fr.charFormat().anchorHref() : QString()));
            }
            dlog(QStringLiteral("DRIVE-block %1 fragments=%2 text=%3")
                     .arg(tag).arg(fragCount).arg(QString(b.text()).left(60)));
        }
    }
    dlog(QStringLiteral("DRIVE %1 focus=%2 trigger=%3 query=%4")
             .arg(tag, model ? model->focusBlockId() : focusText, trigger, query));
    dlog(QStringLiteral("DRIVE-snap %1 %2").arg(tag, model ? model->debugSnapshot() : QStringLiteral("(none)")));
}

void blackScenario(QQuickWindow *window)
{
    EditorModel *model = sessionOf(window);
    if (!model) {
        dlog(QStringLiteral("DRIVE FAIL: no editor session"));
        QCoreApplication::exit(4);
        return;
    }
    shot(window, shotPath(QStringLiteral("drive_black_0_open.png")));
    model->forceFocus(firstBlockId(window), 0);

    QTimer::singleShot(350, window, [window]() {
        typeText(window, QStringLiteral("hola"));
        QTimer::singleShot(250, window, [window]() {
            sendWindowKey(window, Qt::Key_Return, QStringLiteral("\r"));
            QTimer::singleShot(600, window, [window]() {
                typeText(window, QStringLiteral("segundo"));
                QTimer::singleShot(250, window, [window]() {
                    sendWindowKey(window, Qt::Key_Home, QString());
                    QTimer::singleShot(150, window, [window]() {
                        sendWindowKey(window, Qt::Key_Backspace, QString());
                        dumpState(window, QStringLiteral("after-backspace-merge"));
                        QTimer::singleShot(550, window, [window]() {
                            sendWindowKey(window, Qt::Key_Return, QStringLiteral("\r"));
                            dumpState(window, QStringLiteral("after-merge-enter"));
                            QTimer::singleShot(700, window, [window]() {
                                typeText(window, QStringLiteral("post"));
                                QTimer::singleShot(350, window, [window]() {
                                    dumpState(window, QStringLiteral("final"));
                                    shot(window, shotPath(QStringLiteral("drive_black_final.png")));
                                    QCoreApplication::exit(0);
                                });
                            });
                        });
                    });
                });
            });
        });
    });
}

void mentionScenario(QQuickWindow *window, Workspace *ws)
{
    EditorModel *model = sessionOf(window);
    if (!model) {
        dlog(QStringLiteral("DRIVE FAIL: no editor session"));
        QCoreApplication::exit(4);
        return;
    }
    model->forceFocus(firstBlockId(window), 0);

    QTimer::singleShot(350, window, [window, model, ws]() {
        typeText(window, QStringLiteral("@ge"));
        QTimer::singleShot(550, window, [window, model, ws]() {
            dumpState(window, QStringLiteral("mention-typed"));
            shot(window, shotPath(QStringLiteral("drive_mention_popup.png")));
            // exercise the real pick path (what the popup click handler calls)
            if (!model) {
                dlog(QStringLiteral("DRIVE FAIL: session vanished at pick"));
                QCoreApplication::exit(4);
                return;
            }
            const QString id = model->objectId();
            // mention a DIFFERENT object so navigation is observable
            QString target = id;
            const QVariantList recent = ws->recentObjects(10);
            for (const QVariant &v : recent) {
                const QVariantMap m = v.toMap();
                if (m.value(QStringLiteral("id")).toString() != id
                    && m.value(QStringLiteral("typeId")).toString() != QLatin1String("journal")) {
                    target = m.value(QStringLiteral("id")).toString();
                    break;
                }
            }
            dlog(QStringLiteral("DRIVE mention target=%1").arg(target));
            QQuickItem *blocks = blocksViewOf(window);
            QMetaObject::invokeMethod(blocks, "applyMentionSelection",
                                      Q_ARG(QVariant, target), Q_ARG(QVariant, QStringLiteral("drive target")));
            QTimer::singleShot(600, window, [window, ws, target]() {
                dumpState(window, QStringLiteral("mention-inserted"));
                shot(window, shotPath(QStringLiteral("drive_mention_inserted.png")));

                // click the chip: press inside the first anchor run viewport
                QObject *focusObj = window->focusObject();
                QQuickItem *edit = qobject_cast<QQuickItem *>(focusObj);
                if (!edit) {
                    dlog(QStringLiteral("DRIVE FAIL: focus object is not an item"));
                    QCoreApplication::exit(5);
                    return;
                }
                const QRectF caretRect = edit->property("cursorRectangle").toRectF();
                const QPointF local = caretRect.center() - QPointF(24, 0);
                const QPoint g = edit->mapToScene(local).toPoint();
                dlog(QStringLiteral("DRIVE-click local=%1,%2 global=%3,%4")
                         .arg(local.x()).arg(local.y()).arg(g.x()).arg(g.y()));
                QMouseEvent move(QEvent::MouseMove, local,
                                 edit->mapToGlobal(local), Qt::NoButton, Qt::NoButton,
                                 Qt::NoModifier);
                QCoreApplication::sendEvent(edit, &move);
                dlog(QStringLiteral("DRIVE-hoveredLink=%1").arg(edit->property("hoveredLink").toString()));
                QMouseEvent press(QEvent::MouseButtonPress, local,
                                  edit->mapToGlobal(local), Qt::LeftButton, Qt::LeftButton,
                                  Qt::NoModifier);
                QMouseEvent release(QEvent::MouseButtonRelease, local,
                                    edit->mapToGlobal(local), Qt::LeftButton, Qt::LeftButton,
                                    Qt::NoModifier);
                QCoreApplication::sendEvent(edit, &press);
                QCoreApplication::sendEvent(edit, &release);
                QTimer::singleShot(500, window, [window, target]() {
                    dumpState(window, QStringLiteral("after-chip-click"));
                    const QString openId = window->property("editorObjectId").toString();
                    dlog(QStringLiteral("DRIVE editorObjectId=%1 navigated=%2")
                             .arg(openId, openId == target ? 1 : 0));
                    shot(window, shotPath(QStringLiteral("drive_mention_click.png")));
                    QCoreApplication::exit(0);
                });
            });
        });
    });
}

// Bug repro: "@hydroge" → create → open it → rename to "hydrogen" → back to the note.
// The note's mention must read "hydrogen" and the object must keep the new title.
void renameScenario(QQuickWindow *window, Workspace *ws)
{
    EditorModel *model = sessionOf(window);
    if (!model) {
        dlog(QStringLiteral("DRIVE FAIL: no editor session"));
        QCoreApplication::exit(4);
        return;
    }
    const QString noteId = model->objectId();
    model->forceFocus(firstBlockId(window), 0);
    QTimer::singleShot(350, window, [window, ws, noteId]() {
        typeText(window, QStringLiteral("@hydroge"));
        QTimer::singleShot(550, window, [window, ws, noteId]() {
            dumpState(window, QStringLiteral("rename-typed"));
            shot(window, shotPath(QStringLiteral("drive_rename_popup.png")));
            QQuickItem *blocks = blocksViewOf(window);
            QMetaObject::invokeMethod(blocks, "applyMentionCreate", Q_ARG(QVariant, QStringLiteral("note")),
                                      Q_ARG(QVariant, QStringLiteral("hydroge")));
            QTimer::singleShot(600, window, [window, ws, noteId]() {
                QString targetId;
                for (const QVariant &v : ws->recentObjects(10)) {
                    const QVariantMap m = v.toMap();
                    if (m.value(QStringLiteral("title")).toString() == QLatin1String("hydroge"))
                        targetId = m.value(QStringLiteral("id")).toString();
                }
                dlog(QStringLiteral("DRIVE rename target=%1").arg(targetId));
                QMetaObject::invokeMethod(window, "openObject", Q_ARG(QVariant, targetId));
                QTimer::singleShot(800, window, [window, ws, noteId, targetId]() {
                    if (EditorModel *target = sessionOf(window))
                        target->setTitle(QStringLiteral("hydrogen")); // what the title field does
                    // A sync round now (with a server) brings back the echo of the create
                    // ("hydroge") while the new title is still unsaved: it must not wipe it.
                    ws->syncNow();
                    QTimer::singleShot(1000, window, [window, ws, noteId, targetId]() {
                        QMetaObject::invokeMethod(window, "openObject", Q_ARG(QVariant, noteId));
                        QTimer::singleShot(800, window, [window, ws, noteId, targetId]() {
                            const QString snap = sessionOf(window) ? sessionOf(window)->debugSnapshot() : QString();
                            const bool labelOk = snap.contains(QLatin1String("hydrogen\""));
                            const QString title = ws->objectSummary(targetId).value(QStringLiteral("title")).toString();
                            dlog(QStringLiteral("DRIVE rename label-updated=%1 target-title=%2")
                                     .arg(labelOk ? 1 : 0).arg(title));
                            shot(window, shotPath(QStringLiteral("drive_rename_back.png")));
                            QCoreApplication::exit(labelOk && title == QLatin1String("hydrogen") ? 0 : 6);
                        });
                    });
                });
            });
        });
    });
}

// Clicks the mention chip left of the caret in the focused text block (as a user would).
void clickChipBeforeCaret(QQuickWindow *window, Qt::KeyboardModifiers mods = Qt::NoModifier)
{
    QQuickItem *edit = qobject_cast<QQuickItem *>(window->focusObject());
    if (!edit)
        return;
    const QPointF local = edit->property("cursorRectangle").toRectF().center() - QPointF(24, 0);
    QMouseEvent move(QEvent::MouseMove, local, edit->mapToGlobal(local), Qt::NoButton, Qt::NoButton, mods);
    QCoreApplication::sendEvent(edit, &move);
    QMouseEvent press(QEvent::MouseButtonPress, local, edit->mapToGlobal(local), Qt::LeftButton, Qt::LeftButton, mods);
    QMouseEvent release(QEvent::MouseButtonRelease, local, edit->mapToGlobal(local), Qt::LeftButton, Qt::LeftButton, mods);
    QCoreApplication::sendEvent(edit, &press);
    QCoreApplication::sendEvent(edit, &release);
}

// The same bug report driven only through keys and clicks, with sync rounds in between (run it
// with --server/--token): a line of text, "@patch" + Enter (create from the popup), click the
// chip at once, type "es" in the title, sync, go back, sync, click the chip again.
// The note must keep its block with "@patches" and the object must be titled "patches".
void renameUiScenario(QQuickWindow *window, Workspace *ws);

// YAOP_DRIVE_DAILY=1: the same flow inside today's daily note instead of a scratch note.
void renameUiDailyScenario(QQuickWindow *window, Workspace *ws)
{
    const QString daily = ws->journalFor(QDate::currentDate().toString(Qt::ISODate));
    // Shape of the report: four todos, a divider, then an empty line for the mention.
    for (const Object &o : ws->e2eAllObjects()) {
        if (o.id != daily)
            continue;
        Object seeded = o;
        seeded.blocks.clear();
        for (const char *t : { "Genshin", "Wuwa", "Nikke", "Arknights" }) {
            Run r;
            r.text = QString::fromLatin1(t);
            Block b = Block::makeParagraph(newUuid(), { r });
            b.type = BlockType::Todo;
            seeded.blocks.append(b);
        }
        Block divider = Block::makeParagraph(newUuid());
        divider.type = BlockType::Divider;
        seeded.blocks.append(divider);
        seeded.blocks.append(Block::makeParagraph(newUuid()));
        ws->saveObject(seeded);
    }
    QMetaObject::invokeMethod(window, "openObject", Q_ARG(QVariant, daily));
    QTimer::singleShot(1200, window, [window, ws]() { renameUiScenario(window, ws); });
}

void renameUiScenario(QQuickWindow *window, Workspace *ws)
{
    EditorModel *model = sessionOf(window);
    if (!model) {
        QCoreApplication::exit(4);
        return;
    }
    const QString noteId = model->objectId();
    // YAOP_DRIVE_PREFIX: text before the mention ("" = a block holding only the chip).
    const QString prefix = qEnvironmentVariableIsSet("YAOP_DRIVE_PREFIX")
                               ? qEnvironmentVariable("YAOP_DRIVE_PREFIX") : QStringLiteral("ver ");
    QString startBlock = firstBlockId(window);
    if (qEnvironmentVariableIsSet("YAOP_DRIVE_DAILY")) { // the empty last line
        const QJsonArray arr = QJsonDocument::fromJson(model->debugSnapshot().toUtf8()).array();
        startBlock = arr.last().toObject().value(QLatin1String("id")).toString();
    }
    model->forceFocus(startBlock, 0);
    auto later = [window](int ms, std::function<void()> f) { QTimer::singleShot(ms, window, std::move(f)); };
    later(350, [=]() {
        typeText(window, prefix + QStringLiteral("@patch"));
        later(500, [=]() {
            sendWindowKey(window, Qt::Key_Return, QStringLiteral("\r")); // accept the popup entry
            later(150, [=]() {
                dumpState(window, QStringLiteral("ui-mentioned"));
                clickChipBeforeCaret(window);
                later(900, [=]() {
                    const QString targetId = window->property("editorObjectId").toString();
                    dlog(QStringLiteral("DRIVE ui target=%1 note=%2").arg(targetId, noteId));
                    if (auto *title = visibleNamed(window, QStringLiteral("titleInput"))) {
                        title->forceActiveFocus();
                        QMetaObject::invokeMethod(title, "end");
                    }
                    typeText(window, QStringLiteral("es"));
                    {
                        QObject *f = window->focusObject();
                        auto *title = visibleNamed(window, QStringLiteral("titleInput"));
                        dlog(QStringLiteral("DRIVE ui typed focus=%1 title-field=%2 model-title=%3")
                                 .arg(f ? QString::fromLatin1(f->metaObject()->className()) : QStringLiteral("none"),
                                      title ? title->property("text").toString() : QStringLiteral("?"),
                                      sessionOf(window) ? sessionOf(window)->title() : QStringLiteral("?")));
                        shot(window, shotPath(QStringLiteral("drive_rename_ui_title.png")));
                    }
                    ws->syncNow();
                    later(900, [=]() {
                        QMetaObject::invokeMethod(window, "openObject", Q_ARG(QVariant, noteId));
                        later(2500, [=]() {
                            ws->syncNow();
                            later(2500, [=]() {
                                const QString snap = sessionOf(window) ? sessionOf(window)->debugSnapshot() : QString();
                                const QString title = ws->objectSummary(targetId).value(QStringLiteral("title")).toString();
                                const bool blockOk = snap.contains(targetId) && snap.contains(prefix);
                                const bool labelOk = snap.contains(QLatin1String("patches\""));
                                dlog(QStringLiteral("DRIVE ui block-kept=%1 label-updated=%2 target-title=%3 snap=%4")
                                         .arg(blockOk ? 1 : 0).arg(labelOk ? 1 : 0).arg(title, snap));
                                shot(window, shotPath(QStringLiteral("drive_rename_ui.png")));
                                if (!(blockOk && labelOk && title == QLatin1String("patches"))) {
                                    QCoreApplication::exit(6);
                                    return;
                                }
                                // Round 2: put the caret after the chip, click it again, come back.
                                if (EditorModel *m = sessionOf(window))
                                    m->forceFocus(startBlock, int(prefix.size()) + 7);
                                later(400, [=]() {
                                    // YAOP_DRIVE_CTRL=1: the second visit is a Ctrl+click
                                    clickChipBeforeCaret(window, qEnvironmentVariableIsSet("YAOP_DRIVE_CTRL")
                                                                     ? Qt::ControlModifier : Qt::NoModifier);
                                    later(1200, [=]() {
                                        const bool went = window->property("editorObjectId").toString() == targetId;
                                        QMetaObject::invokeMethod(window, "openObject", Q_ARG(QVariant, noteId));
                                        later(1500, [=]() {
                                            ws->syncNow();
                                            later(2500, [=]() {
                                                const QString snap2 = sessionOf(window) ? sessionOf(window)->debugSnapshot() : QString();
                                                const bool kept = snap2.contains(prefix) && snap2.contains(targetId);
                                                dlog(QStringLiteral("DRIVE ui round2 navigated=%1 block-kept=%2 snap=%3")
                                                         .arg(went ? 1 : 0).arg(kept ? 1 : 0).arg(snap2));
                                                QCoreApplication::exit(went && kept ? 0 : 7);
                                            });
                                        });
                                    });
                                });
                            });
                        });
                    });
                });
            });
        });
    });
}

// Mentions are atomic for the caret. With "@patch" alone at the start of a line: "qw" typed right
// after the chip and "q" typed right before it (Home) stay plain text outside it; then one Left
// from after "qw" + "X" never lands inside the chip. The mention must stay "@patch".
void caretScenario(QQuickWindow *window, Workspace *ws)
{
    Q_UNUSED(ws);
    EditorModel *model = sessionOf(window);
    if (!model) {
        QCoreApplication::exit(4);
        return;
    }
    model->forceFocus(firstBlockId(window), 0);
    auto later = [window](int ms, std::function<void()> f) { QTimer::singleShot(ms, window, std::move(f)); };
    later(350, [=]() {
        typeText(window, QStringLiteral("@patch"));
        later(500, [=]() {
            sendWindowKey(window, Qt::Key_Return, QStringLiteral("\r")); // create from the popup
            later(400, [=]() {
                typeText(window, QStringLiteral("qw"));          // right after the chip
                sendWindowKey(window, Qt::Key_Home, QString());
                typeText(window, QStringLiteral("q"));           // right before it
                sendWindowKey(window, Qt::Key_End, QString());
                sendWindowKey(window, Qt::Key_Left, QString());
                sendWindowKey(window, Qt::Key_Left, QString());
                sendWindowKey(window, Qt::Key_Left, QString());  // from the chip's end: jumps over it
                typeText(window, QStringLiteral("X"));
                later(1500, [=]() {
                    const QString snap = sessionOf(window) ? sessionOf(window)->debugSnapshot() : QString();
                    const bool ok = snap.contains(QLatin1String("\"text\":\"qX\""))
                                    && snap.contains(QLatin1String("\"text\":\"@patch\""))
                                    && snap.contains(QLatin1String("\"text\":\"qw\""));
                    dlog(QStringLiteral("DRIVE caret ok=%1 snap=%2").arg(ok ? 1 : 0).arg(snap));
                    QCoreApplication::exit(ok ? 0 : 6);
                });
            });
        });
    });
}

// Keyboard-first F0 (docs/keyboard.md): keys go through the window like real input, so global
// Shortcuts and Keys.onShortcutOverride decide as they do in use (sendWindowKey skips both).
// Voice note from the daily-note header: 2 s through the real recorder, then an audio block.
void journalRecordScenario(QQuickWindow *window, Workspace *ws)
{
    auto later = [window](int ms, std::function<void()> f) { QTimer::singleShot(ms, window, std::move(f)); };
    const QString jid = ws->journalFor(QDate::currentDate().toString(Qt::ISODate));
    QMetaObject::invokeMethod(window, "showObject", Q_ARG(QVariant, jid));
    later(1500, [=]() {
        QObject *rec = nullptr; // the recorder of the header on screen (other editors may exist)
        for (QObject *r : window->findChildren<QObject *>(QStringLiteral("journalRecorder"))) {
            auto *header = qobject_cast<QQuickItem *>(r->parent());
            if (header && header->isVisible())
                rec = r;
        }
        EditorModel *model = sessionOf(window);
        if (!rec || !model) {
            dlog(QStringLiteral("DRIVE record FAIL recorder=%1 session=%2").arg(rec ? 1 : 0).arg(model ? 1 : 0));
            QCoreApplication::exit(1);
            return;
        }
        const int before = model->blockCount();
        QMetaObject::invokeMethod(rec, "start");
        later(1200, [=]() { shot(window, shotPath(QStringLiteral("record-on.png"))); });
        // a discarded take must not report an error
        auto failedCount = std::make_shared<int>(0);
        QObject::connect(qobject_cast<AudioRecorder *>(rec), &AudioRecorder::failed, window, [failedCount](const QString &r) {
            ++*failedCount;
            dlog(QStringLiteral("DRIVE record failed reason=%1").arg(r));
        });
        later(2200, [=]() { QMetaObject::invokeMethod(rec, "stop"); });
        later(3200, [=]() { QMetaObject::invokeMethod(rec, "start"); });
        later(4000, [=]() { QMetaObject::invokeMethod(rec, "cancel"); });
        later(5500, [=]() {
            const int after = model->blockCount();
            if (*failedCount > 0) { QCoreApplication::exit(2); return; }
            dlog(QStringLiteral("DRIVE record blocks %1 -> %2 %3").arg(before).arg(after)
                     .arg(model->debugSnapshot().right(200)));
            shot(window, shotPath(QStringLiteral("record-done.png")));
            QCoreApplication::exit(after == before + 1 ? 0 : 1); // the kept take only
        });
    });
}

// Built-in "Unreviewed" tab on daily notes: three days, one reviewed -> the tab lists two.
void journalUnreviewedScenario(QQuickWindow *window, Workspace *ws)
{
    auto later = [window](int ms, std::function<void()> f) { QTimer::singleShot(ms, window, std::move(f)); };
    const QDate today = QDate::currentDate();
    for (int i = 0; i < 3; ++i)
        ws->journalFor(today.addDays(-i).toString(Qt::ISODate));
    ws->setObjectProperty(ws->journalFor(today.addDays(-1).toString(Qt::ISODate)), QStringLiteral("reviewed"),
                          true, QStringLiteral("checkbox"), QString(), false);
    window->setProperty("libraryType", QStringLiteral("journal"));
    QMetaObject::invokeMethod(window, "navigate", Q_ARG(QVariant, QStringLiteral("library")));
    later(1200, [=]() {
        QObject *lib = nullptr;
        for (QObject *it : window->findChildren<QObject *>())
            if (it->property("unreviewedCount").isValid())
                lib = it;
        if (!lib) { dlog(QStringLiteral("DRIVE unreviewed FAIL no library")); QCoreApplication::exit(1); return; }
        QMetaObject::invokeMethod(lib, "selectView", Q_ARG(QVariant, QStringLiteral("@unreviewed")));
        later(800, [=]() {
            const int n = lib->property("unreviewedCount").toInt();
            const int shown = lib->property("items").toList().size();
            dlog(QStringLiteral("DRIVE unreviewed count=%1 shown=%2").arg(n).arg(shown));
            shot(window, shotPath(QStringLiteral("unreviewed.png")));
            QCoreApplication::exit(n == 2 && shown == 2 ? 0 : 1);
        });
    });
}

// Writing in a daily note (its title field is hidden): type, ↑ from the first line must keep the
// caret in the text, and the typed words must be in the note.
void journalKeysScenario(QQuickWindow *window, Workspace *ws)
{
    auto later = [window](int ms, std::function<void()> f) { QTimer::singleShot(ms, window, std::move(f)); };
    const QString jid = ws->journalFor(QDate::currentDate().toString(Qt::ISODate));
    QMetaObject::invokeMethod(window, "showObject", Q_ARG(QVariant, jid));
    later(1500, [=]() {
        EditorModel *model = sessionOf(window);
        if (!model) { QCoreApplication::exit(4); return; }
        window->requestActivate();
        model->forceFocus(firstBlockId(window), 0);
        later(300, [=]() {
            typeText(window, QStringLiteral("hola diario"));
            later(300, [=]() {
                sendWindowKey(window, Qt::Key_Up, QString());
                later(300, [=]() {
                    QQuickItem *f = window->activeFocusItem();
                    const QString name = f ? f->objectName() : QString();
                    const bool visible = f && f->isVisible();
                    typeText(window, QStringLiteral("!"));
                    later(600, [=]() { // after the editor's 400 ms commit
                        const QString snap = model->debugSnapshot();
                        const bool typed = snap.contains(QLatin1String("hola diario"));
                        const bool bang = snap.contains(QLatin1Char('!'));
                        dlog(QStringLiteral("DRIVE jkeys focus=%1 visible=%2 typed=%3 bang=%4 title=%5 snap=%6")
                                 .arg(name).arg(visible).arg(typed).arg(bang).arg(model->title(), snap.left(200)));
                        shot(window, shotPath(QStringLiteral("journal-keys.png")));
                        if (!(visible && typed && bang)) { QCoreApplication::exit(1); return; }
                        // ‹ to a day without a note: the calendar opens on it and nothing is created
                        const QString yesterday = QDate::currentDate().addDays(-1).toString(Qt::ISODate);
                        QQuickItem *header = visibleNamed(window, QStringLiteral("journalHeader"));
                        QMetaObject::invokeMethod(header, "dayRequested", Q_ARG(QString, yesterday));
                        later(1200, [=]() {
                            const QString page = window->property("page").toString();
                            const bool created = !ws->journalLocalFor(yesterday).isEmpty();
                            dlog(QStringLiteral("DRIVE jkeys prev-missing page=%1 created=%2").arg(page).arg(created));
                            shot(window, shotPath(QStringLiteral("journal-prev-missing.png")));
                            if (page != QLatin1String("calendar") || created) { QCoreApplication::exit(1); return; }
                            // ‹ to a day that has a note: it opens in the editor
                            const QString jy = ws->journalFor(yesterday);
                            QMetaObject::invokeMethod(window, "showObject", Q_ARG(QVariant, jid));
                            later(1200, [=]() {
                                QQuickItem *h = visibleNamed(window, QStringLiteral("journalHeader"));
                                QMetaObject::invokeMethod(h, "dayRequested", Q_ARG(QString, yesterday));
                                later(1200, [=]() {
                                    EditorModel *m = sessionOf(window);
                                    const QString id = m ? m->objectId() : QString();
                                    dlog(QStringLiteral("DRIVE jkeys prev-existing opened=%1").arg(id == jy));
                                    QCoreApplication::exit(id == jy ? 0 : 1);
                                });
                            });
                        });
                    });
                });
            });
        });
    });
}

// Plain writing: split with Enter, join with Backspace, lists, todos and accents. Keys after an
// Enter wait 30 ms (faster than a person) so the new line has the caret, as with a real keyboard.
// Each step logs ok/FAIL with the block order; exit code = number of failures.
void writingScenario(QQuickWindow *window)
{
    EditorModel *model = sessionOf(window);
    if (!model) { QCoreApplication::exit(4); return; }
    auto order = [model]() {
        QStringList out;
        for (const QJsonValue &v : QJsonDocument::fromJson(model->debugSnapshot().toUtf8()).array()) {
            const QJsonObject o = v.toObject();
            QString text;
            for (const QJsonValue &r : o.value(QLatin1String("runs")).toArray())
                text += r.toObject().value(QLatin1String("text")).toString();
            out << o.value(QLatin1String("type")).toString() + QLatin1Char(':') + text;
        }
        return out.join(QLatin1Char(','));
    };
    auto key = [window](int k, Qt::KeyboardModifiers m = Qt::NoModifier, const QString &t = QString()) {
        sendWindowKey(window, k, t, m);
    };
    struct Step { QString name; std::function<void()> act; std::function<bool(const QString &)> ok; };
    auto steps = std::make_shared<QVector<Step>>();
    auto fails = std::make_shared<int>(0);
    auto ends = [](const QString &tail) { return [tail](const QString &o) { return o.endsWith(tail); }; };
    *steps = {
        { "type a word", [=]() { typeText(window, QStringLiteral("holamundo")); }, ends("paragraph:holamundo") },
        { "enter splits the line", [=]() { for (int i = 0; i < 5; ++i) key(Qt::Key_Left); key(Qt::Key_Return, Qt::NoModifier, "\r"); },
          ends("paragraph:hola,paragraph:mundo") },
        { "backspace at start joins", [=]() { key(Qt::Key_Backspace, Qt::NoModifier, "\b"); }, ends("paragraph:holamundo") },
        { "end goes to line end", [=]() { key(Qt::Key_End); }, ends("paragraph:holamundo") },
        { "enter at the end adds a line", [=]() { key(Qt::Key_Return, Qt::NoModifier, "\r"); }, ends("paragraph:holamundo,paragraph:") },
        { "'- ' starts a bullet", [=]() { typeText(window, QStringLiteral("- uno")); }, ends("paragraph:holamundo,bullet_list:uno") },
        { "enter continues the bullet", [=]() { key(Qt::Key_Return, Qt::NoModifier, "\r");
              QTimer::singleShot(30, window, [=]() { typeText(window, QStringLiteral("dos")); }); },
          ends("bullet_list:uno,bullet_list:dos") },
        { "enter on empty bullet leaves the list", [=]() { key(Qt::Key_Return, Qt::NoModifier, "\r");
              QTimer::singleShot(30, window, [=]() { key(Qt::Key_Return, Qt::NoModifier, "\r"); }); },
          ends("bullet_list:dos,paragraph:") },
        { "accents and ñ", [=]() { typeText(window, QStringLiteral("canción ñandú ¿qué?")); }, ends("paragraph:canción ñandú ¿qué?") },
        { "'[] ' starts a todo", [=]() { key(Qt::Key_Return, Qt::NoModifier, "\r");
              QTimer::singleShot(30, window, [=]() { typeText(window, QStringLiteral("[] tarea")); }); },
          ends("todo:tarea") },
        { "ctrl+z gives '[] ' back as text", [=]() { key(Qt::Key_Z, Qt::ControlModifier); }, ends("paragraph:[] tarea") },
        { "ctrl+shift+z redoes the todo", [=]() { key(Qt::Key_Z, Qt::ControlModifier | Qt::ShiftModifier); }, ends("todo:tarea") },
        { "ctrl+z x3 undoes the line and its Enter", [=]() {
              key(Qt::Key_Z, Qt::ControlModifier);
              QTimer::singleShot(30, window, [=]() { key(Qt::Key_Z, Qt::ControlModifier); });
              QTimer::singleShot(60, window, [=]() { key(Qt::Key_Z, Qt::ControlModifier); }); },
          ends("bullet_list:dos,paragraph:canción ñandú ¿qué?") },
        { "typing goes on at the caret undo left", [=]() { typeText(window, QStringLiteral("!")); },
          ends("bullet_list:dos,paragraph:canción ñandú ¿qué?!") },
        { "ctrl+y after new typing changes nothing", [=]() { key(Qt::Key_Y, Qt::ControlModifier); },
          ends("bullet_list:dos,paragraph:canción ñandú ¿qué?!") },
    };
    auto run = std::make_shared<std::function<void(int)>>();
    *run = [=](int i) {
        if (i >= steps->size()) {
            dlog(QStringLiteral("DRIVE writing done failures=%1").arg(*fails));
            shot(window, shotPath(QStringLiteral("writing.png")));
            QCoreApplication::exit(*fails);
            return;
        }
        (*steps)[i].act();
        // 500 ms: typing reaches the model on the editor's 400 ms commit, as it does for a person
        QTimer::singleShot(500, window, [=]() {
            const QString o = order();
            const bool ok = (*steps)[i].ok(o);
            QObject *f = window->focusObject();
            const int caret = f ? f->property("cursorPosition").toInt() : -1;
            const QString fn = f ? f->objectName() : QString();
            if (!ok) ++*fails;
            dlog(QStringLiteral("DRIVE writing %1 %2 (%3) focus=%4 caret=%5").arg(ok ? QStringLiteral("ok  ") : QStringLiteral("FAIL"), (*steps)[i].name, o, fn).arg(caret));
            (*run)(i + 1);
        });
    };
    window->requestActivate();
    model->forceFocus(firstBlockId(window), 0);
    QTimer::singleShot(400, window, [=]() { (*run)(0); });
}

void keysScenario(QQuickWindow *window)
{
    EditorModel *model = sessionOf(window);
    if (!model) {
        QCoreApplication::exit(4);
        return;
    }
    auto key = [window](int k, Qt::KeyboardModifiers m = Qt::NoModifier) {
        QTest::keyClick(static_cast<QWindow *>(window), Qt::Key(k), m);
    };
    auto later = [window](int ms, std::function<void()> f) { QTimer::singleShot(ms, window, std::move(f)); };
    window->requestActivate();
    model->forceFocus(firstBlockId(window), 0);
    later(400, [=]() {
        const int before = model->blockCount();
        key(Qt::Key_D, Qt::ControlModifier);                        // editor owns Ctrl+D
        later(400, [=]() {
            const bool duplicated = model->blockCount() == before + 1
                                    && window->property("editorObjectId").toString().size() > 0;
            dlog(QStringLiteral("DRIVE keys blocks %1 -> %2").arg(before).arg(model->blockCount()));
            key(Qt::Key_Escape);                                      // no global Esc any more
            key(Qt::Key_K, Qt::ControlModifier);
            later(400, [=]() {
                const bool paletteOpened = window->property("paletteOpen").toBool();
                key(Qt::Key_Escape);                                  // the popup closes itself
                later(400, [=]() {
                    const bool paletteClosed = !window->property("paletteOpen").toBool();
                    key(Qt::Key_1, Qt::ControlModifier);              // leave the editor (Home)
                    later(600, [=]() {
                    QQuickItem *focused = window->activeFocusItem();      // the page took focus
                    key(Qt::Key_D, Qt::ControlModifier);              // outside the editor: Today
                    later(600, [=]() {
                        QQuickItem *after = window->activeFocusItem();
                        const bool today = window->property("editorObjectId").toString().isEmpty()
                                           && window->property("page").toString() != QLatin1String("home")
                                           && after && after->objectName() == QLatin1String("blockEdit");   // caret in the day note
                        const bool hasFocus = focused && focused != window->contentItem();
                        const bool ok = duplicated && paletteOpened && paletteClosed && today && hasFocus;
                        dlog(QStringLiteral("DRIVE keys ok=%1 duplicated=%2 paletteOpened=%3 paletteClosed=%4 today=%5 focus=%6 (%7)")
                                 .arg(ok).arg(duplicated).arg(paletteOpened).arg(paletteClosed).arg(today)
                                 .arg(hasFocus).arg(focused ? QString::fromLatin1(focused->metaObject()->className()) : QStringLiteral("-")));
                        QCoreApplication::exit(ok ? 0 : 6);
                    });
                    });
                });
            });
        });
    });
}

// Alt+Tab away and back (docs/keyboard.md §3): the caret must be where it was, in the same
// block, so typing goes on there. Runs in a note and in today's daily note (Ctrl+J).
// Exit code = number of failures.
void returnScenario(QQuickWindow *window)
{
    auto later = [window](int ms, std::function<void()> f) { QTimer::singleShot(ms, window, std::move(f)); };
    auto fails = std::make_shared<int>(0);
    auto leaveAndCome = std::make_shared<std::function<void(QString, std::function<void()>)>>();
    // `steal`: on the way back the focus lands on another visible item, as Qt sometimes does
    *leaveAndCome = [=](QString where, std::function<void()> done) {
        const bool steal = where.endsWith(QLatin1String("-lost"));
        QQuickItem *f = window->activeFocusItem();
        const int caret = f ? f->property("cursorPosition").toInt() : -1;
        const QString block = f && f->parentItem() ? f->parentItem()->property("blockId").toString() : QString();
        // another window takes the focus (the browser), then this one gets it back
        static QWindow *other = nullptr;
        if (!other) { other = new QWindow; other->resize(200, 200); other->show(); }
        other->requestActivate();
        later(700, [=]() {
            window->requestActivate();
            // `-lost`: right after coming back something else on screen takes the focus (the page)
            if (steal)
                later(30, [=]() { if (QQuickItem *p = visibleNamed(window, QStringLiteral("navPages"))) p->forceActiveFocus(); });
            later(700, [=]() {
                QQuickItem *g = window->activeFocusItem();
                const int caret2 = g ? g->property("cursorPosition").toInt() : -1;
                const QString block2 = g && g->parentItem() ? g->parentItem()->property("blockId").toString() : QString();
                const bool ok = g && g->objectName() == QLatin1String("blockEdit") && caret2 == caret && block2 == block;
                if (!ok) ++*fails;
                dlog(QStringLiteral("DRIVE return %1 %2 before=%3@%4 after=%5@%6 (%7)")
                         .arg(ok ? QStringLiteral("ok") : QStringLiteral("FAIL"), where, block.left(8)).arg(caret)
                         .arg(block2.left(8)).arg(caret2)
                         .arg(g ? QString::fromLatin1(g->metaObject()->className()) + QLatin1Char('/') + g->objectName() : QStringLiteral("-")));
                done();
            });
        });
    };
    EditorModel *model = sessionOf(window);
    if (!model) { QCoreApplication::exit(4); return; }
    window->requestActivate();
    model->forceFocus(firstBlockId(window), 0);
    later(400, [=]() {
        typeText(window, QStringLiteral("uno dos tres"));
        sendWindowKey(window, Qt::Key_Left, QString());
        sendWindowKey(window, Qt::Key_Left, QString());
        later(700, [=]() {   // after the editor's 400 ms commit
            (*leaveAndCome)(QStringLiteral("note-middle"), [=]() {
                typeText(window, QStringLiteral("X"));
                later(700, [=]() {
                    const QString snap = model->debugSnapshot();
                    const bool typed = snap.contains(QLatin1String("uno dos trXes"));
                    if (!typed) ++*fails;
                    dlog(QStringLiteral("DRIVE return %1 typing-goes-on %2").arg(typed ? QStringLiteral("ok") : QStringLiteral("FAIL"), snap.left(300)));
                    // leave right after typing, before the commit
                    typeText(window, QStringLiteral("Y"));
                    (*leaveAndCome)(QStringLiteral("note-just-typed"), [=]() {
                    (*leaveAndCome)(QStringLiteral("note-lost"), [=]() {
                        QTest::keyClick(static_cast<QWindow *>(window), Qt::Key_J, Qt::ControlModifier);
                        later(900, [=]() {
                            typeText(window, QStringLiteral("diario"));
                            later(700, [=]() {
                                (*leaveAndCome)(QStringLiteral("daily-note"), [=]() {
                                (*leaveAndCome)(QStringLiteral("daily-note-lost"), [=]() {
                                    shot(window, shotPath(QStringLiteral("return.png")));
                                    QCoreApplication::exit(*fails);
                                });
                                });
                            });
                        });
                    });
                    });
                });
            });
        });
    });
}

// Dates after `@` (pkmcore datequery): each query is typed, Enter picks the first row, and the
// block must hold a date span with that ISO value. Exit code = number of failures.
void dateMentionScenario(QQuickWindow *window)
{
    EditorModel *model = sessionOf(window);
    if (!model) { QCoreApplication::exit(4); return; }
    window->requestActivate();
    model->forceFocus(firstBlockId(window), 0);
    const QDate today = QDate::currentDate();
    const QTime now = QTime::currentTime();
    struct Case { QString typed; QString iso; };
    auto cases = std::make_shared<QList<Case>>(QList<Case> {
        { QStringLiteral("hoy"), today.toString(Qt::ISODate) },
        { QStringLiteral("mañana 9"), today.addDays(1).toString(Qt::ISODate) + QStringLiteral("T09:00") },
        { QStringLiteral("now"), today.toString(Qt::ISODate) + now.toString(QStringLiteral("'T'HH:mm")) },
        { QStringLiteral("+3"), today.addDays(3).toString(Qt::ISODate) },
        { QStringLiteral("15:30"), today.toString(Qt::ISODate) + QStringLiteral("T15:30") },
    });
    auto fails = std::make_shared<int>(0);
    auto step = std::make_shared<std::function<void(int)>>();
    *step = [=](int i) {
        if (i >= cases->size()) {
            QTimer::singleShot(600, window, [=]() {
                shot(window, shotPath(QStringLiteral("date-mentions.png")));
                const QString snap = model->debugSnapshot();
                for (const Case &c : *cases) {
                    const bool ok = snap.contains(QStringLiteral("\"date\":\"%1\"").arg(c.iso));
                    if (!ok) ++*fails;
                    dlog(QStringLiteral("DRIVE date %1 @%2 -> %3").arg(ok ? QStringLiteral("ok") : QStringLiteral("FAIL"), c.typed, c.iso));
                }
                if (*fails) dlog(QStringLiteral("DRIVE date snap=%1").arg(snap.left(1200)));
                QCoreApplication::exit(*fails);
            });
            return;
        }
        typeText(window, QStringLiteral("@") + cases->at(i).typed);
        QTimer::singleShot(500, window, [=]() {
            if (i == 1) shot(window, shotPath(QStringLiteral("date-popup.png")));
            sendWindowKey(window, Qt::Key_Return, QStringLiteral("\r"));
            QTimer::singleShot(300, window, [=]() {
                typeText(window, QStringLiteral(" "));
                QTimer::singleShot(150, window, [=]() { (*step)(i + 1); });
            });
        });
    };
    QTimer::singleShot(400, window, [=]() { (*step)(0); });
}

// Keyboard-first F2/F3 (docs/keyboard.md §5): pages, sidebar and the shortcut sheet by keys only.
// Each step logs ok/FAIL; exit code = number of failures.
void navScenario(QQuickWindow *window)
{
    auto key = [window](int k, Qt::KeyboardModifiers m = Qt::NoModifier) {
        QTest::keyClick(static_cast<QWindow *>(window), Qt::Key(k), m);
    };
    auto later = [window](int ms, std::function<void()> f) { QTimer::singleShot(ms, window, std::move(f)); };
    auto fails = std::make_shared<int>(0);
    auto step = [fails](const QString &name, bool ok, const QString &detail = QString()) {
        if (!ok) ++*fails;
        dlog(QStringLiteral("DRIVE nav %1 %2 %3").arg(ok ? QStringLiteral("ok") : QStringLiteral("FAIL"), name, detail));
    };
    auto focusName = [window]() {
        QQuickItem *f = window->activeFocusItem();
        return f ? QString::fromLatin1(f->metaObject()->className()) + QLatin1Char('/') + f->objectName() : QStringLiteral("-");
    };
    auto inside = [window](const char *id) {
        for (QQuickItem *p = window->activeFocusItem(); p; p = p->parentItem())
            if (p->objectName() == QLatin1String(id)) return true;
        return false;
    };
    window->requestActivate();
    key(Qt::Key_1, Qt::ControlModifier);                                 // Home
    later(600, [=]() {
        key(Qt::Key_Down);
        later(300, [=]() {
            QQuickItem *first = window->activeFocusItem();
            const bool isPage = first && QString::fromLatin1(first->metaObject()->className()).contains(QLatin1String("Page"));
            step(QStringLiteral("down-focuses-an-item"), inside("navPages") && first && !isPage, focusName());
            key(Qt::Key_Down);
            later(300, [=]() {
                step(QStringLiteral("down-moves"), window->activeFocusItem() != first && inside("navPages"), focusName());
                shot(window, shotPath(QStringLiteral("nav-home.png")));
                key(Qt::Key_F6, Qt::ShiftModifier);                      // page -> tabs -> sidebar
                key(Qt::Key_F6, Qt::ShiftModifier);
                later(300, [=]() {
                    step(QStringLiteral("shift-f6-sidebar"), inside("navSidebar"), focusName());
                    key(Qt::Key_Down);
                    key(Qt::Key_Down);
                    later(300, [=]() {
                        const QString before = window->property("page").toString();
                        step(QStringLiteral("sidebar-arrows"), inside("navSidebar"), focusName());
                        shot(window, shotPath(QStringLiteral("nav-sidebar.png")));
                        key(Qt::Key_Return);
                        later(700, [=]() {
                            const QString after = window->property("page").toString();
                            step(QStringLiteral("enter-opens-page"), after != before || !window->property("editorObjectId").toString().isEmpty(),
                                 before + QStringLiteral(" -> ") + after);
                            key(Qt::Key_Slash, Qt::ControlModifier);
                            later(500, [=]() {
                                QQuickItem *f = window->activeFocusItem();
                                step(QStringLiteral("ctrl-slash-sheet"), f && f->objectName() == QLatin1String("shortcutFilter"), focusName());
                                key(Qt::Key_Escape);
                                later(500, [=]() {
                                    QQuickItem *g = window->activeFocusItem();
                                    step(QStringLiteral("esc-closes-sheet"), !g || g->objectName() != QLatin1String("shortcutFilter"), focusName());
                                    key(Qt::Key_J, Qt::ControlModifier);
                                    later(900, [=]() {
                                        QQuickItem *h = window->activeFocusItem();
                                        step(QStringLiteral("ctrl-j-today"), h && h->objectName() == QLatin1String("blockEdit")
                                             && window->property("page").toString() == QLatin1String("calendar"), focusName());
                                        shot(window, shotPath(QStringLiteral("nav-today.png")));
                                        QCoreApplication::exit(*fails);
                                    });
                                });
                            });
                        });
                    });
                });
            });
        });
    });
}

// Keyboard-first F1 (docs/keyboard.md §4): the editor is driven by keys only.
void editorKeysScenario(QQuickWindow *window)
{
    EditorModel *model = sessionOf(window);
    if (!model) {
        QCoreApplication::exit(4);
        return;
    }
    auto key = [window](int k, Qt::KeyboardModifiers m = Qt::NoModifier) {
        QTest::keyClick(static_cast<QWindow *>(window), Qt::Key(k), m);
    };
    auto type = [window](const char *text) {
        for (const char *c = text; *c; ++c)
            QTest::keyClick(static_cast<QWindow *>(window), *c);
    };
    auto focused = [window]() {
        QQuickItem *f = window->activeFocusItem();
        return f ? (f->objectName().isEmpty() ? QString::fromLatin1(f->metaObject()->className()) : f->objectName())
                 : QStringLiteral("-");
    };
    auto order = [model]() {
        QStringList out;
        for (const QJsonValue &v : QJsonDocument::fromJson(model->debugSnapshot().toUtf8()).array()) {
            const QJsonObject o = v.toObject();
            QString text;
            for (const QJsonValue &r : o.value(QLatin1String("runs")).toArray())
                text += r.toObject().value(QLatin1String("text")).toString();
            out << o.value(QLatin1String("type")).toString() + QLatin1Char(':') + text;
        }
        return out.join(QLatin1Char(','));
    };
    auto later = [window](int ms, std::function<void()> f) { QTimer::singleShot(ms, window, std::move(f)); };
    auto checks = std::make_shared<QStringList>();
    auto check = [checks](bool ok, const QString &what) {
        dlog(QStringLiteral("DRIVE editor-keys %1 %2").arg(ok ? QStringLiteral("ok  ") : QStringLiteral("FAIL"), what));
        if (!ok) checks->append(what);
    };
    window->requestActivate();
    QObject::connect(window, &QQuickWindow::activeFocusItemChanged, window, [=]() {
        QQuickItem *f = window->activeFocusItem();
        QQuickItem *p = f ? f->parentItem() : nullptr;
        dlog(QStringLiteral("DRIVE focus -> %1 / %2 row=%3 sel=%4").arg(focused(), p ? QString::fromLatin1(p->metaObject()->className()) : QString())
                 .arg(p ? p->property("row").toString() : QString()).arg(model->selectedCount()));
    });
    later(300, [=]() {
        check(focused() == QLatin1String("blockEdit"), QStringLiteral("caret in the first block on open (focus=%1)").arg(focused()));
        type("alpha");
        key(Qt::Key_Return);
        type("beta");
        later(500, [=]() {
            model->insertBlockAfter(1, QStringLiteral("code"));
            later(400, [=]() {
                // the block right after the code block takes "delta"
                const QJsonArray arr = QJsonDocument::fromJson(model->debugSnapshot().toUtf8()).array();
                int codeRow = -1;
                for (int i = 0; i < arr.size(); ++i)
                    if (arr.at(i).toObject().value(QLatin1String("type")).toString() == QLatin1String("code")) codeRow = i;
                if (codeRow + 1 >= arr.size())
                    model->insertBlockAfter(codeRow, QStringLiteral("paragraph"));
                else
                    model->forceFocus(arr.at(codeRow + 1).toObject().value(QLatin1String("id")).toString(), 0);
            later(500, [=]() {
                dlog(QStringLiteral("DRIVE editor-keys setup %1 selected=%2 focus=%3").arg(order()).arg(model->selectedCount()).arg(focused()));
                type("delta");
                key(Qt::Key_Up);   // delta -> code block: block mode
                later(300, [=]() {
                    check(model->selectedCount() == 1 && focused() != QLatin1String("blockEdit"),
                          QStringLiteral("up into a code block selects it (selected=%1 focus=%2)").arg(model->selectedCount()).arg(focused()));
                    key(Qt::Key_Up);   // beta
                    key(Qt::Key_Return);
                    later(300, [=]() {
                        check(model->selectedCount() == 0 && focused() == QLatin1String("blockEdit"),
                              QStringLiteral("enter edits the text block (focus=%1)").arg(focused()));
                        key(Qt::Key_Escape);
                        later(200, [=]() {
                            check(model->selectedCount() == 1, QStringLiteral("esc selects the block"));
                            key(Qt::Key_Up, Qt::ControlModifier | Qt::ShiftModifier);
                            later(400, [=]() {
                                check(order().startsWith(QLatin1String("paragraph:beta,paragraph:alpha,code")),
                                      QStringLiteral("ctrl+shift+up moves it (%1)").arg(order()));
                                key(Qt::Key_Return);
                                later(300, [=]() {
                                    key(Qt::Key_1, Qt::ControlModifier | Qt::AltModifier);
                                    later(400, [=]() {
                                        check(order().startsWith(QLatin1String("heading:beta")),
                                              QStringLiteral("ctrl+alt+1 makes a heading (%1)").arg(order()));
                                        key(Qt::Key_Up, Qt::AltModifier);
                                        later(300, [=]() {
                                            check(focused() == QLatin1String("titleInput"), QStringLiteral("alt+up goes to the title (focus=%1)").arg(focused()));
                                            key(Qt::Key_Down);
                                            later(300, [=]() {
                                                check(focused() == QLatin1String("blockEdit"), QStringLiteral("down from the title enters the blocks (focus=%1)").arg(focused()));
                                                key(Qt::Key_End);
                                                key(Qt::Key_Right);   // end of beta -> start of alpha
                                                type("X");
                                                later(600, [=]() {
                                                    check(order().contains(QLatin1String("paragraph:Xalpha")),
                                                          QStringLiteral("right at the end goes to the next block (%1)").arg(order()));
                                                    const int rowsBefore = model->rowCount();
                                                    const qreal yBefore = window->activeFocusItem()
                                                        ? window->activeFocusItem()->property("cursorRectangle").toRectF().y() : -1;
                                                    key(Qt::Key_Return, Qt::ShiftModifier);
                                                    type("Y");
                                                    later(600, [=]() {
                                                        const qreal yAfter = window->activeFocusItem()
                                                            ? window->activeFocusItem()->property("cursorRectangle").toRectF().y() : -1;
                                                        check(model->rowCount() == rowsBefore
                                                                  && order().contains(QLatin1String("paragraph:X\nYalpha"))
                                                                  && yAfter > yBefore,
                                                              QStringLiteral("shift+enter stays in block & moves to next line (rows=%1->%2 y=%3->%4 order=%5)")
                                                                  .arg(rowsBefore).arg(model->rowCount())
                                                                  .arg(yBefore).arg(yAfter).arg(order()));
                                                        key(Qt::Key_Down);      // X\nYalpha -> code: block mode
                                                        key(Qt::Key_Return);    // edit the code
                                                        later(300, [=]() {
                                                        check(model->selectedCount() == 0 && focused() != QLatin1String("blockModeKeys"),
                                                              QStringLiteral("enter on a code block edits it (focus=%1)").arg(focused()));
                                                        key(Qt::Key_Escape);
                                                        later(300, [=]() {
                                                        check(model->selectedCount() == 1 && focused() == QLatin1String("blockModeKeys"),
                                                              QStringLiteral("esc in code goes back to block mode (focus=%1)").arg(focused()));
                                                        dlog(QStringLiteral("DRIVE editor-keys done failures=%1").arg(checks->size()));
                                                        QCoreApplication::exit(checks->isEmpty() ? 0 : 6);
                                                        });
                                                        });
                                                    });
                                                });
                                            });
                                            });
                                        });
                                    });
                                });
                            });
                        });
                    });
                });
            });
        });
    });
}

void dndScenario(QQuickWindow *window)
{
    EditorModel *model = sessionOf(window);
    if (!model) {
        dlog(QStringLiteral("DRIVE FAIL: no editor session"));
        QCoreApplication::exit(4);
        return;
    }
    model->forceFocus(firstBlockId(window), 0);
    QTimer::singleShot(350, window, [window]() {
        typeText(window, QStringLiteral("alpha"));
        QTimer::singleShot(200, window, [window]() {
            sendWindowKey(window, Qt::Key_Return, QStringLiteral("\r"));
            QTimer::singleShot(500, window, [window]() {
                typeText(window, QStringLiteral("beta"));
                QTimer::singleShot(200, window, [window]() {
                    sendWindowKey(window, Qt::Key_Return, QStringLiteral("\r"));
                    QTimer::singleShot(500, window, [window]() {
                        typeText(window, QStringLiteral("gamma"));
                        QTimer::singleShot(300, window, [window]() {
                            dumpState(window, QStringLiteral("dnd-before"));
                            shot(window, shotPath(QStringLiteral("drive_dnd_before.png")));
                            QQuickItem *blocks = blocksViewOf(window);
                            QQuickItem *content = blocks ? blocks->property("contentItem").value<QQuickItem *>() : nullptr;
                            QList<QQuickItem *> rows;
                            if (content)
                                for (QQuickItem *c : content->childItems())
                                    if (c && c->height() > 10)
                                        rows.append(c);
                            dlog(QStringLiteral("DRIVE rows=%1").arg(rows.size()));
                            if (rows.size() < 3) {
                                QCoreApplication::exit(6);
                                return;
                            }
                            QQuickItem *row0 = rows.at(0);
                            const QPointF start(row0->width() - 13, row0->height() / 2);
                            const QPointF startScene = row0->mapToScene(start);
                            const QPointF endScene = rows.at(2)->mapToScene(
                                QPointF(rows.at(2)->width() - 13, rows.at(2)->height() + 10));
                            QMouseEvent press(QEvent::MouseButtonPress, start, startScene,
                                              startScene.toPoint(), Qt::LeftButton, Qt::LeftButton,
                                              Qt::NoModifier);
                            QCoreApplication::sendEvent(window, &press);
                            for (int i = 1; i <= 8; ++i) {
                                const QPointF p = startScene + (endScene - startScene) * (i / 8.0);
                                const QPointF local = row0->mapFromScene(p);
                                QMouseEvent mv(QEvent::MouseMove, local, p, p.toPoint(),
                                               Qt::NoButton, Qt::LeftButton, Qt::NoModifier);
                                QCoreApplication::sendEvent(window, &mv);
                                QCoreApplication::processEvents();
                            }
                            {
                                const QPointF local = row0->mapFromScene(endScene);
                                QMouseEvent rel(QEvent::MouseButtonRelease, local, endScene,
                                                endScene.toPoint(), Qt::LeftButton, Qt::NoButton,
                                                Qt::NoModifier);
                                QCoreApplication::sendEvent(window, &rel);
                            }
                            QTimer::singleShot(700, window, [window]() {
                                dumpState(window, QStringLiteral("dnd-after"));
                                shot(window, shotPath(QStringLiteral("drive_dnd_after.png")));
                                QCoreApplication::exit(0);
                            });
                        });
                    });
                });
            });
        });
    });
}

} // namespace

int runDrive(QQuickWindow *window, Workspace *ws, const QString &scenario)
{
    QTimer::singleShot(1200, window, [window, ws, scenario]() {
        // Create a scratch note so user data is untouched, then open it.
        const QString id = ws->createObject(QStringLiteral("note"),
                                            QStringLiteral("DRIVE repro scratch"));
        QMetaObject::invokeMethod(window, "openObject", Q_ARG(QVariant, id));
        QTimer::singleShot(1200, window, [window, ws, scenario]() {
            if (!sessionOf(window)) {
                dlog(QStringLiteral("DRIVE FAIL: editor page/session not found"));
                QCoreApplication::exit(4);
                return;
            }
            dlog(QStringLiteral("DRIVE begin %1").arg(scenario));
            if (scenario == QLatin1String("mention"))
                mentionScenario(window, ws);
            else if (scenario == QLatin1String("rename"))
                renameScenario(window, ws);
            else if (scenario == QLatin1String("caret"))
                caretScenario(window, ws);
            else if (scenario == QLatin1String("rename-ui"))
                qEnvironmentVariableIsSet("YAOP_DRIVE_DAILY") ? renameUiDailyScenario(window, ws)
                                                              : renameUiScenario(window, ws);
            else if (scenario == QLatin1String("dnd"))
                dndScenario(window);
            else if (scenario == QLatin1String("keys"))
                keysScenario(window);
            else if (scenario == QLatin1String("journal-unreviewed"))
                journalUnreviewedScenario(window, ws);
            else if (scenario == QLatin1String("journal-keys"))
                journalKeysScenario(window, ws);
            else if (scenario == QLatin1String("writing"))
                writingScenario(window);
            else if (scenario == QLatin1String("journal-record"))
                journalRecordScenario(window, ws);
            else if (scenario == QLatin1String("return"))
                returnScenario(window);
            else if (scenario == QLatin1String("date-mention"))
                dateMentionScenario(window);
            else if (scenario == QLatin1String("nav"))
                navScenario(window);
            else if (scenario == QLatin1String("editor-keys"))
                editorKeysScenario(window);
            else
                blackScenario(window);
        });
    });
    return 0;
}

// ===========================================================================
// Headless e2e steps (docs/product.md §22.1)
// ===========================================================================
namespace {

constexpr int kE2eTimeoutMs = 120000;

// --e2e-args "key=value;key=value" — values may contain spaces, never ';'.
QHash<QString, QString> parseE2eArgs(const QString &args)
{
    QHash<QString, QString> out;
    for (const QString &pair : args.split(QLatin1Char(';'), Qt::SkipEmptyParts)) {
        const int eq = pair.indexOf(QLatin1Char('='));
        if (eq <= 0)
            continue;
        out.insert(pair.left(eq).trimmed(), pair.mid(eq + 1).trimmed());
    }
    return out;
}

// Runs the event loop while polling `pred`; returns false on timeout.
bool waitUntil(const std::function<bool()> &pred, int timeoutMs)
{
    QElapsedTimer clock;
    clock.start();
    while (!pred()) {
        if (clock.elapsed() > timeoutMs)
            return false;
        QTimer timer;
        QEventLoop loop;
        QObject::connect(&timer, &QTimer::timeout, &loop, &QEventLoop::quit);
        timer.setSingleShot(true);
        timer.start(100);
        loop.exec();
    }
    return true;
}

std::optional<Object> e2eObjectById(Workspace *ws, const QString &id)
{
    for (const Object &o : ws->e2eAllObjects()) {
        if (o.id == id)
            return o;
    }
    return std::nullopt;
}

// A full bi-directional sync round: push pending ops, pull, and settle idle.
bool e2eFullSyncRound(Workspace *ws)
{
    ws->syncNow(); // queued ops drain, then the pull applies
    if (!waitUntil([ws]() { return !ws->syncing(); }, kE2eTimeoutMs))
        return false;
    // A second round picks up anything merged server-side while we pushed.
    ws->syncNow();
    if (!waitUntil([ws]() { return !ws->syncing(); }, kE2eTimeoutMs))
        return false;
    return true;
}

// ---------------------------------------------------------------------------
// Merged-state digest — MUST match mobile SyncE2ETest.mergedStateDigest exactly.
//
//   wire   = canonical wire export sent to the server: `Object::toJson(true)`
//            (mobile `SyncContractCodec.toServerJson`)
//   merged = { block_hlc, deleted_blocks, field_hlc, id, properties, title, type_id }
//            — the fields the merge considers (spec/merge.md "Fixtures") minus the object-level
//            `hlc`, which does NOT converge between devices today (documented SUPUESTO of the
//            e2e digest): the mobile `SyncContractCodec.fromServerJson` stores
//            `HybridLogicalClock.receive(wire.hlc)` — counter+1 with its own node — as the
//            object's hlc (desktop/server keep the merged hlc verbatim). It is per-device clock
//            noise, not merged content; the real ordering metadata lives in field_hlc /
//            block_hlc / deleted_blocks, which ARE hashed verbatim. On purpose EXCLUDED as
//            well: revision, updated_at, sync_status, sync_error, incarnation, trashed_at,
//            created_at, content, title_runs, links, property_links — per-device/server fields
//            or derived shadows that would make two equivalent merged states hash differently.
//   digest = sha256(canonical_json(merged)) as lowercase hex
//
// canonical_json: object keys sorted lexicographically; arrays keep their order (blocks
// travel in merged order and no block renumbering happens: ordered-list `number` is part
// of the merged block content); strings escape only ", \\, \n, \r, \t and \u00xx for the
// remaining control chars; numbers print as a plain integer when integral and < 2^53,
// otherwise "%.17g" (Locale US).
// ---------------------------------------------------------------------------
void e2eCanonicalString(QString &out, const QString &s)
{
    out += QLatin1Char('"');
    for (const QChar &c : s) {
        if (c == QLatin1Char('"'))
            out += QStringLiteral("\\\"");
        else if (c == QLatin1Char('\\'))
            out += QStringLiteral("\\\\");
        else if (c == QLatin1Char('\n'))
            out += QStringLiteral("\\n");
        else if (c == QLatin1Char('\r'))
            out += QStringLiteral("\\r");
        else if (c == QLatin1Char('\t'))
            out += QStringLiteral("\\t");
        else if (c.unicode() < 0x20)
            out += QStringLiteral("\\u%1").arg(int(c.unicode()), 2, 16, QChar::fromLatin1('0'));
        else
            out += c;
    }
    out += QLatin1Char('"');
}

void e2eCanonicalNumber(QString &out, double d)
{
    const double kMaxSafeInteger = 9007199254740992.0; // 2^53
    if (!std::isfinite(d) || d != std::floor(d) || std::fabs(d) >= kMaxSafeInteger) {
        char buf[40];
        std::snprintf(buf, sizeof(buf), "%.17g", d);
        out += QString::fromLatin1(buf);
    } else {
        out += QString::number(static_cast<qint64>(d));
    }
}

void e2eCanonicalAppend(QString &out, const QJsonValue &v)
{
    if (v.isObject()) {
        out += QLatin1Char('{');
        QStringList keys = v.toObject().keys();
        std::sort(keys.begin(), keys.end());
        for (int i = 0; i < keys.size(); ++i) {
            if (i > 0)
                out += QLatin1Char(',');
            e2eCanonicalString(out, keys.at(i));
            out += QLatin1Char(':');
            e2eCanonicalAppend(out, v.toObject().value(keys.at(i)));
        }
        out += QLatin1Char('}');
    } else if (v.isArray()) {
        out += QLatin1Char('[');
        const QJsonArray arr = v.toArray();
        for (int i = 0; i < arr.size(); ++i) {
            if (i > 0)
                out += QLatin1Char(',');
            e2eCanonicalAppend(out, arr.at(i));
        }
        out += QLatin1Char(']');
    } else if (v.isString()) {
        e2eCanonicalString(out, v.toString());
    } else if (v.isDouble()) {
        e2eCanonicalNumber(out, v.toDouble());
    } else if (v.isBool()) {
        out += v.toBool() ? QStringLiteral("true") : QStringLiteral("false");
    } else {
        out += QStringLiteral("null"); // null and undefined
    }
}

// Canonical merged-equivalent subset of the wire export (no hlc: see the note above).
// Returns the canonical bytes AND the object's wire hlc for the audit trail.
QPair<QByteArray, QString> e2eMergedCanonical(const Object &object)
{
    const QJsonObject wire = object.toJson(true);
    QJsonObject merged;
    merged.insert(QStringLiteral("block_hlc"), wire.value(QLatin1String("block_hlc")).toObject());
    merged.insert(QStringLiteral("deleted_blocks"),
                  wire.value(QLatin1String("deleted_blocks")).toObject());
    merged.insert(QStringLiteral("field_hlc"), wire.value(QLatin1String("field_hlc")).toObject());
    merged.insert(QStringLiteral("id"), wire.value(QLatin1String("id")).toString());
    merged.insert(QStringLiteral("properties"), wire.value(QLatin1String("properties")).toObject());
    merged.insert(QStringLiteral("title"), wire.value(QLatin1String("title")).toString());
    merged.insert(QStringLiteral("type_id"), wire.value(QLatin1String("type_id")).toString());

    QString canonical;
    e2eCanonicalAppend(canonical, QJsonValue(merged));
    return { canonical.toUtf8(), wire.value(QLatin1String("hlc")).toString() };
}

QString e2eMergedStateDigest(const QByteArray &canonical)
{
    return QString::fromLatin1(QCryptographicHash::hash(canonical,
                                                        QCryptographicHash::Sha256).toHex());
}

int e2ePullAssert(Workspace *ws, const QHash<QString, QString> &args)
{
    if (!e2eFullSyncRound(ws)) {
        std::printf("E2E FAIL pull-assert: sync round did not settle in %d ms\n", kE2eTimeoutMs);
        return 4;
    }
    for (auto it = args.constBegin(); it != args.constEnd(); ++it) {
        const QString id = it.key();
        const QString wantTitle = it.value();
        const bool found = waitUntil([ws, id, wantTitle]() {
            const auto obj = e2eObjectById(ws, id);
            if (!obj)
                return false;
            return wantTitle.isEmpty() || plainTitleFor(*obj) == wantTitle;
        }, kE2eTimeoutMs);
        if (!found) {
            const auto obj = e2eObjectById(ws, id);
            std::printf("E2E FAIL pull-assert: %s not received (title %s)\n",
                        qPrintable(id), obj ? qPrintable(plainTitleFor(*obj)) : "<missing>");
            return 5;
        }
        std::printf("E2ESTEP pull-assert ok %s\n", qPrintable(id));
    }
    return 0;
}

int e2eEdit(Workspace *ws, const QHash<QString, QString> &args)
{
    const QString id = args.value(QStringLiteral("id"));
    if (id.isEmpty()) {
        std::printf("E2E FAIL edit: --e2e-args needs id=<objectId>\n");
        return 2;
    }
    if (!waitUntil([ws, id]() { return e2eObjectById(ws, id).has_value(); }, kE2eTimeoutMs)) {
        std::printf("E2E FAIL edit: object %s not present\n", qPrintable(id));
        return 4;
    }

    Object obj = *e2eObjectById(ws, id);
    const QString title = args.value(QStringLiteral("title"));
    if (!title.isEmpty())
        obj.title = title;
    const QString text = args.value(QStringLiteral("text"));
    const QString mention = args.value(QStringLiteral("mention"));
    if (!text.isEmpty()) {
        QVector<Run> runs;
        Run run;
        run.text = text;
        runs.append(run);
        const int at = mention.indexOf(QLatin1Char('='));
        if (at > 0) { // mention=<objectId>:<label> — the appended block carries an inline mention
            Run m;
            m.text = mention.mid(at + 1);
            m.objectId = mention.left(at);
            runs.append(m);
        }
        obj.blocks.append(Block::makeParagraph(newUuid(), runs));
    }
    ws->saveObject(obj);

    // Push the edit and pull the merged result back.
    if (!waitUntil([ws]() { return !ws->syncing(); }, kE2eTimeoutMs) || !e2eFullSyncRound(ws)) {
        std::printf("E2E FAIL edit: sync round did not settle in %d ms\n", kE2eTimeoutMs);
        return 4;
    }
    if (!waitUntil([ws]() { return ws->pendingCount() == 0; }, kE2eTimeoutMs)) {
        std::printf("E2E FAIL edit: %d ops never reached the server\n", ws->pendingCount());
        return 5;
    }
    // One more round so the merged object from the server is applied locally.
    if (!e2eFullSyncRound(ws)) {
        std::printf("E2E FAIL edit: final pull did not settle\n");
        return 6;
    }
    std::printf("E2ESTEP edit ok %s\n", qPrintable(id));
    return 0;
}

int e2eDigest(Workspace *ws, const QString &outPath)
{
    if (outPath.isEmpty()) {
        std::printf("E2E FAIL digest: --e2e-out <path> required\n");
        return 2;
    }
    QList<Object> objects;
    for (const Object &o : ws->e2eAllObjects()) {
        if (o.trashedAt.isEmpty())
            objects.append(o);
    }
    std::sort(objects.begin(), objects.end(),
              [](const Object &a, const Object &b) { return a.id < b.id; });
    QFile f(outPath);
    if (!f.open(QIODevice::WriteOnly | QIODevice::Truncate)) {
        std::printf("E2E FAIL digest: cannot write %s\n", qPrintable(outPath));
        return 3;
    }
    // Audit trail for diffs (tools/doc-diff.sh on the .wire files): "<id>\t<hlc>\t<canonical>".
    QFile wf(outPath + QStringLiteral(".wire"));
    const bool hasWire = wf.open(QIODevice::WriteOnly | QIODevice::Truncate);
    for (const Object &o : objects) {
        const auto canon = e2eMergedCanonical(o);
        f.write(QStringLiteral("E2EDIGEST %1 %2\n")
                    .arg(o.id, e2eMergedStateDigest(canon.first)).toUtf8());
        if (hasWire)
            wf.write(o.id.toUtf8() + QByteArrayLiteral("\t") + canon.second.toUtf8() +
                     QByteArrayLiteral("\t") + canon.first + QByteArrayLiteral("\n"));
    }
    f.write(QStringLiteral("E2EDIGEST_COUNT %1\n").arg(objects.size()).toUtf8());
    f.close();
    wf.close();
    std::printf("E2ESTEP digest %d objects %s\n", int(objects.size()), qPrintable(outPath));
    return 0;
}

} // namespace

// Pairing steps : the harness plays the human who compares the 6 digits.
//   pair-host   writes the QR JSON to <out>, waits for the guest, writes the code to <out>.code,
//               approves and prints the sha256 of the transferred VK.
//   pair-guest  qrfile=<path>: joins, writes its code to <out>, waits for the VK, prints sha256.
int e2ePairHost(Workspace *ws, const QString &outPath)
{
    PairingFlow flow;
    flow.setServerUrl(QSettings().value(QStringLiteral("server/url")).toString());
    flow.setWorkspaceObject(ws);
    flow.hostStart();
    if (!waitUntil([&flow]() { return flow.state() == QLatin1String("waiting") || flow.state() == QLatin1String("failed"); }, 15000)
        || flow.state() == QLatin1String("failed")) {
        std::printf("E2E FAIL pair-host: could not open the pairing (%s)\n", qPrintable(flow.error()));
        return 4;
    }
    QFile(outPath).open(QIODevice::WriteOnly);
    { QFile f(outPath); if (f.open(QIODevice::WriteOnly | QIODevice::Truncate)) f.write(flow.qrPayload().toUtf8()); }
    if (!waitUntil([&flow]() { return flow.state() == QLatin1String("compare") || flow.state() == QLatin1String("failed"); }, 120000)
        || flow.state() != QLatin1String("compare")) {
        std::printf("E2E FAIL pair-host: no guest answered\n");
        return 5;
    }
    { QFile f(outPath + QStringLiteral(".code")); if (f.open(QIODevice::WriteOnly | QIODevice::Truncate)) f.write(flow.code().toUtf8()); }
    flow.hostApprove();
    if (!waitUntil([&flow]() { return flow.state() != QLatin1String("sending"); }, 15000)
        || flow.state() != QLatin1String("done")) {
        std::printf("E2E FAIL pair-host: transfer failed (%s)\n", qPrintable(flow.error()));
        return 6;
    }
    std::printf("E2ESTEP pair-host ok code=%s vk_sha256=%s\n", qPrintable(flow.code()),
                qPrintable(QString::fromLatin1(QCryptographicHash::hash(ws->vaultKeyBytes(), QCryptographicHash::Sha256).toHex())));
    return 0;
}

int e2ePairGuest(const QHash<QString, QString> &args, const QString &outPath)
{
    QFile qrFile(args.value(QStringLiteral("qrfile")));
    if (!qrFile.open(QIODevice::ReadOnly)) {
        std::printf("E2E FAIL pair-guest: needs qrfile=<path>\n");
        return 2;
    }
    PairingFlow flow;
    flow.guestStart(QString::fromUtf8(qrFile.readAll()));
    if (!waitUntil([&flow]() { return flow.state() == QLatin1String("compare") || flow.state() == QLatin1String("failed"); }, 15000)
        || flow.state() != QLatin1String("compare")) {
        std::printf("E2E FAIL pair-guest: could not join (%s)\n", qPrintable(flow.error()));
        return 4;
    }
    { QFile f(outPath); if (f.open(QIODevice::WriteOnly | QIODevice::Truncate)) f.write(flow.code().toUtf8()); }
    if (!waitUntil([&flow]() { return flow.state() == QLatin1String("done") || flow.state() == QLatin1String("failed"); }, 120000)
        || flow.state() != QLatin1String("done")) {
        std::printf("E2E FAIL pair-guest: no VK received (%s)\n", qPrintable(flow.error()));
        return 5;
    }
    std::printf("E2ESTEP pair-guest ok code=%s epoch=%u vk_sha256=%s\n", qPrintable(flow.code()),
                unsigned(flow.receivedEpoch()),
                qPrintable(QString::fromLatin1(QCryptographicHash::hash(flow.receivedVk(), QCryptographicHash::Sha256).toHex())));
    return 0;
}

QString seedShowcase(Workspace *ws)
{
    auto run = [](const QString &t, bool bold = false, bool code = false) {
        Run r;
        r.text = t;
        r.bold = bold;
        r.code = code;
        return r;
    };
    auto para = [&](const QString &t) { return Block::makeParagraph(newUuid(), { run(t) }); };
    const QString id = ws->createObject(QStringLiteral("note"), QStringLiteral("Showcase of every block"));
    auto obj = e2eObjectById(ws, id);
    if (!obj)
        return id;
    Object o = *obj;

    // A real architectural-style image asset for the media block, book cover, and image viewer.
    QImage img(900, 1200, QImage::Format_RGB32);
    img.fill(QColor("#111318"));
    for (int y = 0; y < img.height(); ++y) {
        for (int x = 0; x < img.width(); ++x) {
            if (y < 420 && x > 140 && x < 760)
                img.setPixelColor(x, y, QColor("#27406b"));
            else if (y >= 460 && y < 780 && x > 140 && x < 460)
                img.setPixelColor(x, y, QColor("#C8F04A"));
            else if (y >= 460 && y < 780 && x >= 500 && x < 760)
                img.setPixelColor(x, y, QColor("#D97A36"));
            else if (y >= 840 && y < 1040 && x > 140 && x < 760)
                img.setPixelColor(x, y, QColor("#1F242D"));
        }
    }
    const QString imgPath = QDir::tempPath() + QStringLiteral("/yaop-showcase.png");
    img.save(imgPath, "PNG");
    const QString assetId = ws->importFileAsAsset(imgPath);

    // Rich specialized objects for Book, Person, Image Viewer, Inbox/Reviewed, Graph & Unlinked Mentions.
    const QString bookId = QStringLiteral("book-showcase");
    const QString personId = QStringLiteral("person-showcase");
    const QString imageId = QStringLiteral("image-showcase");
    const QString nowIso = QDateTime::currentDateTimeUtc().toString(Qt::ISODateWithMs);

    ws->ensureTagObject(QStringLiteral("PKM"));
    ws->ensureTagObject(QStringLiteral("Writing"));
    ws->ensureTagObject(QStringLiteral("Design"));
    ws->ensureTagObject(QStringLiteral("AI"));
    ws->ensureTagObject(QStringLiteral("Coding"));

    {
        Object person;
        person.id = personId;
        person.typeId = TypeId::Person;
        person.title = QStringLiteral("Ana López");
        person.createdAt = nowIso;
        person.updatedAt = nowIso;
        person.properties.insert(QStringLiteral("category"), QStringLiteral("friend"));
        person.properties.insert(QStringLiteral("email"), QStringLiteral("ana@estudionorte.es"));
        person.properties.insert(QStringLiteral("organization"), QStringLiteral("Estudio Norte"));
        person.properties.insert(QStringLiteral("phone"), QStringLiteral("+34 612 345 678"));
        person.properties.insert(QStringLiteral("tags"), QJsonArray{ QStringLiteral("Design"), QStringLiteral("PKM") });
        Block ph1 = Block::makeParagraph(newUuid(), { run(QStringLiteral("Context")) });
        ph1.type = BlockType::Heading; ph1.level = 2;
        person.blocks = {
            ph1,
            para(QStringLiteral("Product designer collaborating on Swiss grid typography and local-first tools.")),
            para(QStringLiteral("Recommended reading Cómo tomar notas inteligentes for our weekly review workflow.")),
        };
        ws->saveObject(person);
    }

    {
        Object authorPerson;
        authorPerson.id = QStringLiteral("person-author-showcase");
        authorPerson.typeId = TypeId::Person;
        authorPerson.title = QStringLiteral("Sönke Ahrens");
        authorPerson.createdAt = nowIso;
        authorPerson.updatedAt = nowIso;
        authorPerson.properties.insert(QStringLiteral("category"), QStringLiteral("author"));
        authorPerson.blocks = { para(QStringLiteral("Author of How to Take Smart Notes.")) };
        ws->saveObject(authorPerson);

        Object col;
        col.id = QStringLiteral("collection-showcase");
        col.typeId = TypeId::Collection;
        col.title = QStringLiteral("Lecturas 2026");
        col.createdAt = nowIso;
        col.updatedAt = nowIso;
        col.properties.insert(QStringLiteral("items"), QJsonArray{ bookId, personId, QStringLiteral("web-showcase-2") });
        ws->saveObject(col);
    }

    {
        Object book;
        book.id = bookId;
        book.typeId = TypeId::Book;
        book.title = QStringLiteral("Cómo tomar notas inteligentes");
        book.createdAt = nowIso;
        book.updatedAt = nowIso;
        book.properties.insert(QStringLiteral("author"), QStringLiteral("Sönke Ahrens"));
        book.properties.insert(QStringLiteral("status"), QStringLiteral("reading"));
        book.properties.insert(QStringLiteral("genre"), QJsonArray{ QStringLiteral("personal_growth"), QStringLiteral("productivity") });
        book.properties.insert(QStringLiteral("rating"), 4);
        book.properties.insert(QStringLiteral("cover"),
                               QJsonObject{ { "asset_id", assetId }, { "crop_offset_y", 0.5 }, { "is_fit", false } });
        book.properties.insert(QStringLiteral("tags"), QJsonArray{ QStringLiteral("PKM"), QStringLiteral("Writing") });
        Run mentionAna;
        mentionAna.text = QStringLiteral("Ana López");
        mentionAna.objectId = personId;
        Block bh1 = Block::makeParagraph(newUuid(), { run(QStringLiteral("Key ideas")) });
        bh1.type = BlockType::Heading; bh1.level = 2;
        Block bq = para(QStringLiteral("Writing is not what follows research, learning or studying, it is the medium of all this work."));
        bq.type = BlockType::Quote;
        Block bb1 = Block::makeParagraph(newUuid(), { run(QStringLiteral("Discussed chapter 4 with ")), mentionAna, run(QStringLiteral(" during the sprint review.")) });
        bb1.type = BlockType::BulletList;
        Block bb2 = para(QStringLiteral("Separate fleeting notes, literature notes, and permanent atomic notes."));
        bb2.type = BlockType::BulletList;
        book.blocks = { bh1, bq, bb1, bb2 };
        ws->saveObject(book);
        // one saved version, then an edit, so the history preview has something to compare
        ws->openObject(bookId);
        ws->closeObject(bookId);
        Object edited = *e2eObjectById(ws, bookId);
        edited.blocks.removeAt(3);
        edited.blocks[1].runs = { run(QStringLiteral("Writing is the medium of all this work.")) };
        edited.blocks[1].text = runsToPlainText(edited.blocks[1].runs);
        ws->saveObject(edited);
    }

    {
        Object imObj;
        imObj.id = imageId;
        imObj.typeId = TypeId::Image;
        imObj.title = QStringLiteral("Swiss grid study — composition 04.png");
        imObj.createdAt = nowIso;
        imObj.updatedAt = nowIso;
        imObj.properties.insert(QStringLiteral("asset_id"), assetId);
        imObj.properties.insert(QStringLiteral("file_name"), QStringLiteral("swiss-grid-04.png"));
        imObj.properties.insert(QStringLiteral("mime"), QStringLiteral("image/png"));
        imObj.properties.insert(QStringLiteral("caption"), QStringLiteral("High-contrast poster grid study with lime and amber blocks"));
        imObj.properties.insert(QStringLiteral("source_url"), QStringLiteral("https://yaop.app/studies/grid-04"));
        imObj.properties.insert(QStringLiteral("colors"), QJsonArray{ QStringLiteral("black"), QStringLiteral("blue"), QStringLiteral("green"), QStringLiteral("orange") });
        imObj.properties.insert(QStringLiteral("tags"), QJsonArray{ QStringLiteral("Design") });
        imObj.blocks = {
            para(QStringLiteral("Reference visual for 0px corners, 1px hairline separation and accent balance.")),
        };
        ws->saveObject(imObj);
    }

    const QString sibling = ws->createObject(QStringLiteral("task"), QStringLiteral("Review the quarterly plan"));
    ws->createObject(QStringLiteral("note"), QStringLiteral("A note with a deliberately long title that must elide or wrap instead of breaking the layout of its card"));

    Run mentionBook;
    mentionBook.text = QStringLiteral("Cómo tomar notas inteligentes");
    mentionBook.objectId = bookId;
    Run mentionPerson;
    mentionPerson.text = QStringLiteral("Ana López");
    mentionPerson.objectId = personId;
    Block linkedIntro = Block::makeParagraph(newUuid(), {
        run(QStringLiteral("Intro paragraph referencing ")), mentionBook,
        run(QStringLiteral(" with notes from ")), mentionPerson, run(QStringLiteral("."))
    });

    Block h1 = Block::makeParagraph(newUuid(), { run(QStringLiteral("Heading one")) });
    h1.type = BlockType::Heading; h1.level = 1;
    Block h2 = h1; h2.id = newUuid(); h2.level = 2; h2.runs = { run(QStringLiteral("Heading two")) };
    Block bullet = para(QStringLiteral("Bullet item with enough text to wrap onto a second line when the window is narrow, to check wrapping")); bullet.type = BlockType::BulletList;
    Block num = para(QStringLiteral("Numbered item")); num.type = BlockType::NumberedList;
    Block todo = para(QStringLiteral("A checked todo")); todo.type = BlockType::Todo; todo.checked = true;
    Block toggle = para(QStringLiteral("Toggle with a child")); toggle.type = BlockType::Toggle;
    Block child = para(QStringLiteral("Hidden child of the toggle")); child.indentLevel = 1;
    Block quote = para(QStringLiteral("A quote that is long enough to need wrapping in a narrow column, just to see it hold together")); quote.type = BlockType::Quote;
    Block callout = para(QStringLiteral("Callout body text")); callout.type = BlockType::Callout; callout.emoji = QStringLiteral("!");
    Block code; code.id = newUuid(); code.type = BlockType::Code; code.language = QStringLiteral("javascript");
    code.text = QStringLiteral("console.log(chi)\nconst veryLongLine = someFunction(argumentNumberOne, argumentNumberTwo, argumentNumberThree);");
    Block math; math.id = newUuid(); math.type = BlockType::Math; math.latex = QStringLiteral("\\int_0^1 x^2\\,dx = \\frac{1}{3}");
    Block table; table.id = newUuid(); table.type = BlockType::Table;
    table.align = { QStringLiteral("left"), QStringLiteral("left"), QStringLiteral("left") };
    table.rows = { { QStringLiteral("Name"), QStringLiteral("Type"), QStringLiteral("Notes") },
                   { QStringLiteral("Alpha"), QStringLiteral("Note"), QStringLiteral("First row") },
                   { QStringLiteral("Beta"), QStringLiteral("Task"), QStringLiteral("Second row, a bit longer") } };
    Block divider; divider.id = newUuid(); divider.type = BlockType::Divider;
    Block bookmark; bookmark.id = newUuid(); bookmark.type = BlockType::Bookmark;
    bookmark.url = QStringLiteral("https://example.com/a/very/long/path/that/keeps/going/and/going");
    bookmark.title = QStringLiteral("Example domain with a long title that has to elide gracefully");
    bookmark.domain = QStringLiteral("example.com");
    Block embed; embed.id = newUuid(); embed.type = BlockType::Embed; embed.objectId = sibling;
    Block image; image.id = newUuid(); image.type = BlockType::Image; image.assetId = assetId;
    image.runs = { run(QStringLiteral("A caption")) };

    o.properties.insert(QStringLiteral("tags"), QJsonArray{ QStringLiteral("PKM"), QStringLiteral("Design") });
    o.blocks = { linkedIntro, h1, h2, bullet, num, todo, toggle, child,
                 quote, callout, code, math, table, divider, bookmark, embed, image,
                 para(QStringLiteral("Last paragraph after everything.")) };
    ws->saveObject(o);

    // Sets (spec/query.md) so the Sets page has something to show.
    for (const char *t : { "Read the plan", "Call the carpenter", "Renew passport" })
        ws->createObject(QStringLiteral("task"), QString::fromUtf8(t));
    ws->createObject(QStringLiteral("book"), QStringLiteral("The Name of the Wind"));
    {
        // a book template (product §4.3): new books can start from it
        Object tpl;
        tpl.id = QStringLiteral("book-template-showcase");
        tpl.typeId = TypeId::Book;
        tpl.title = QStringLiteral("Reading notes");
        tpl.createdAt = nowIso;
        tpl.updatedAt = nowIso;
        tpl.properties.insert(QStringLiteral("is_template"), true);
        tpl.properties.insert(QStringLiteral("status"), QStringLiteral("to_read"));
        Block h1 = para(QStringLiteral("Why I picked it"));
        h1.type = BlockType::Heading; h1.level = 2;
        Block h2 = para(QStringLiteral("Key ideas"));
        h2.type = BlockType::Heading; h2.level = 2;
        Block b = para(QStringLiteral(""));
        b.type = BlockType::BulletList;
        Block h3 = para(QStringLiteral("Quotes"));
        h3.type = BlockType::Heading; h3.level = 2;
        tpl.blocks = { h1, para(QStringLiteral("")), h2, b, h3 };
        ws->saveObject(tpl);
    }
    {
        Object sapiens;
        sapiens.id = QStringLiteral("book-sapiens-showcase");
        sapiens.typeId = TypeId::Book;
        sapiens.title = QStringLiteral("Sapiens");
        sapiens.createdAt = nowIso;
        sapiens.updatedAt = nowIso;
        sapiens.blocks = { para(QString()) };
        ws->saveObject(sapiens);
    }
    {
        Object wl;
        wl.id = QStringLiteral("web-showcase");
        wl.typeId = TypeId::Web;
        wl.title = QStringLiteral("GLM Coding Plan — AI Coding Powered by GLM-5.1 & GLM-5-Turbo");
        wl.createdAt = nowIso;
        wl.updatedAt = nowIso;
        wl.properties.insert(QStringLiteral("url"), QStringLiteral("https://z.ai/subscribe"));
        wl.properties.insert(QStringLiteral("description"),
                             QStringLiteral("Use GLM models like GLM-5.1 & GLM-5-Turbo for AI coding in Claude Code, Kilo Code, Cline, OpenCode and more."));
        wl.properties.insert(QStringLiteral("cover"),
                             QJsonObject{ { "asset_id", assetId }, { "crop_offset_y", 0.5 }, { "is_fit", false } });
        wl.properties.insert(QStringLiteral("tags"), QJsonArray{ QStringLiteral("AI"), QStringLiteral("Coding") });
        wl.properties.insert(QStringLiteral("category"), QStringLiteral("tool"));
        wl.properties.insert(QStringLiteral("topics"), QJsonArray{ QStringLiteral("technology"), QStringLiteral("programming") });
        wl.properties.insert(QStringLiteral("reviewed"), false);
        wl.blocks.append(para(QStringLiteral("Notes about this web link and why it is useful.")));
        ws->saveObject(wl);

        Object wl2;
        wl2.id = QStringLiteral("web-showcase-2");
        wl2.typeId = TypeId::Web;
        wl2.title = QStringLiteral("Local-First Software: You Own Your Data, in spite of the Cloud");
        wl2.createdAt = nowIso;
        wl2.updatedAt = nowIso;
        wl2.properties.insert(QStringLiteral("url"), QStringLiteral("https://www.inkandswitch.com/local-first/"));
        wl2.properties.insert(QStringLiteral("description"),
                              QStringLiteral("Seven ideals for local-first software: fast, multi-device, offline, collaborative, long-term, private, and user-controlled."));
        wl2.properties.insert(QStringLiteral("tags"), QJsonArray{ QStringLiteral("PKM") });
        wl2.properties.insert(QStringLiteral("category"), QStringLiteral("article"));
        wl2.properties.insert(QStringLiteral("topics"), QJsonArray{ QStringLiteral("technology"), QStringLiteral("design") });
        wl2.properties.insert(QStringLiteral("reviewed"), false);
        wl2.blocks.append(para(QStringLiteral("Core inspiration for YAOP's sync and encrypted vault architecture.")));
        ws->saveObject(wl2);

        Object wl3;
        wl3.id = QStringLiteral("web-showcase-reviewed");
        wl3.typeId = TypeId::Web;
        wl3.title = QStringLiteral("Qt 6 QML Best Practices & Scene Graph Architecture");
        wl3.createdAt = nowIso;
        wl3.updatedAt = nowIso;
        wl3.properties.insert(QStringLiteral("url"), QStringLiteral("https://doc.qt.io/qt-6/qtquick-bestpractices.html"));
        wl3.properties.insert(QStringLiteral("description"), QStringLiteral("Performance and declarative structure guidelines for Qt Quick desktop apps."));
        wl3.properties.insert(QStringLiteral("tags"), QJsonArray{ QStringLiteral("Coding") });
        wl3.properties.insert(QStringLiteral("category"), QStringLiteral("docs"));
        wl3.properties.insert(QStringLiteral("topics"), QJsonArray{ QStringLiteral("programming") });
        wl3.properties.insert(QStringLiteral("reviewed"), true);
        wl3.blocks.append(para(QStringLiteral("Reviewed and filed for desktop reference.")));
        ws->saveObject(wl3);

        const struct { const char *id, *title, *cat; QStringList topics; QStringList tags; } more[] = {
            { "web-yt-1", "Building a QML editor from scratch", "video", { "programming", "design" }, { "Coding" } },
            { "web-yt-2", "Zettelkasten in 10 minutes", "video", { "productivity" }, { "PKM" } },
            { "web-tut-1", "Scene graph tutorial", "tutorial", { "programming" }, { "Coding" } },
            { "web-blog-1", "Why local-first wins", "blog_post", { "technology", "design" }, { "PKM" } },
            { "web-rec-1", "Pan de masa madre", "recipe", { "food" }, {} },
            { "web-info-1", "Color theory infographic", "infographic", { "design" }, { "Design" } },
        };
        for (const auto &m : more) {
            Object w;
            w.id = QString::fromUtf8(m.id);
            w.typeId = TypeId::Web;
            w.title = QString::fromUtf8(m.title);
            w.createdAt = nowIso;
            w.updatedAt = nowIso;
            w.properties.insert(QStringLiteral("url"), QStringLiteral("https://example.org/") + w.id);
            w.properties.insert(QStringLiteral("category"), QString::fromUtf8(m.cat));
            w.properties.insert(QStringLiteral("topics"), QJsonArray::fromStringList(m.topics));
            w.properties.insert(QStringLiteral("tags"), QJsonArray::fromStringList(m.tags));
            w.properties.insert(QStringLiteral("reviewed"), true);
            ws->saveObject(w);
        }
    }
    {
        // An audio object with a real file (when ffmpeg is installed) for the player.
        const QString wavPath = QDir::tempPath() + QStringLiteral("/yaop-showcase.wav");
        QProcess::execute(QStringLiteral("ffmpeg"), { QStringLiteral("-y"), QStringLiteral("-loglevel"), QStringLiteral("quiet"),
                                                      QStringLiteral("-f"), QStringLiteral("lavfi"), QStringLiteral("-i"),
                                                      QStringLiteral("aevalsrc=sin(330*2*PI*t)*(0.2+0.8*abs(sin(t*0.9))*abs(sin(t*2.3))):d=42"), wavPath });
        const QString audioAsset = QFileInfo::exists(wavPath) ? ws->importFileAsAsset(wavPath) : QString();
        Object au;
        au.id = QStringLiteral("audio-showcase");
        au.typeId = TypeId::Audio;
        au.title = QStringLiteral("Nota de voz · 08:10");
        au.createdAt = nowIso;
        au.updatedAt = nowIso;
        au.properties.insert(QStringLiteral("asset_id"), audioAsset);
        au.properties.insert(QStringLiteral("file_name"), QStringLiteral("nota-de-voz.wav"));
        au.properties.insert(QStringLiteral("mime"), QStringLiteral("audio/wav"));
        au.properties.insert(QStringLiteral("tags"), QJsonArray{ QStringLiteral("PKM") });
        au.blocks.append(para(QStringLiteral("Ideas for the weekly review.")));
        ws->saveObject(au);
    }
    {
        const QString h1 = ws->createObject(QStringLiteral("habit"), QStringLiteral("Leer 30 min"));
        const QString h2 = ws->createObject(QStringLiteral("habit"), QStringLiteral("Beber agua"));
        ws->setObjectProperty(h2, QStringLiteral("habit_kind"), QStringLiteral("count"));
        ws->setObjectProperty(h2, QStringLiteral("habit_goal"), QStringLiteral("8"), QStringLiteral("number"));
        for (int i = 0; i < 6; ++i) {
            const QString d = QDate::currentDate().addDays(-i).toString(Qt::ISODate);
            if (i != 2)
                ws->setHabitDay(h1, d, true);
            ws->setHabitDay(h2, d, 3 + i);
        }
    }
    const QString openTasksSet = ws->createSet(QStringLiteral("Open tasks"),
                  QVariantMap{ { "types", QVariantList{ "task" } },
                               { "filters", QVariantList{ QVariantMap{ { "prop", "status" }, { "op", "neq" }, { "value", "done" } } } },
                               { "sort", QVariantList{ QVariantMap{ { "prop", "@title" }, { "dir", "asc" } } } } });
    const QString booksSet = ws->createSet(QStringLiteral("Books"), QVariantMap{ { "types", QVariantList{ "book" } } });
    ws->setSetActiveView(booksSet, QStringLiteral("gallery"));
    ws->setSetActiveView(openTasksSet, QStringLiteral("list"));
    {
        // a page with two queries in it (spec/query.md "Query in a page")
        Object dash;
        dash.id = QStringLiteral("dashboard-showcase");
        dash.typeId = TypeId::Note;
        dash.title = QStringLiteral("Reading dashboard");
        dash.createdAt = nowIso;
        dash.updatedAt = nowIso;
        Block q1 = Block::makeParagraph(newUuid());
        q1.type = BlockType::Embed;
        q1.objectId = openTasksSet;
        Block q2 = Block::makeParagraph(newUuid());
        q2.type = BlockType::Embed;
        q2.objectId = booksSet;
        dash.blocks = { para(QStringLiteral("What is open this week:")), q1, para(QStringLiteral("Books on the shelf:")), q2 };
        ws->saveObject(dash);
    }
    ws->createTypeView(QStringLiteral("web"), QStringLiteral("Videos"),
                       QVariantMap{ { "filters", QVariantList{ QVariantMap{ { "prop", "category" }, { "op", "eq" }, { "value", "video" } } } } },
                       QStringLiteral("list"));
    ws->createTypeView(QStringLiteral("web"), QStringLiteral("To read"),
                       QVariantMap{ { "filters", QVariantList{ QVariantMap{ { "prop", "reviewed" }, { "op", "is_false" } } } } },
                       QStringLiteral("gallery"));
    return id;
}

// backup-roundtrip: seed a note + an image, export a .yaop, restore it over the same vault
// (merge must be idempotent: no object changes, nothing lost) and make sure a corrupted copy
// is refused. Needs no server. args: file=<path.yaop>
int e2eBackupRoundtrip(Workspace *ws, const QHash<QString, QString> &args)
{
    const QString file = args.value(QStringLiteral("file"),
                                    QDir::tempPath() + QStringLiteral("/yaop-e2e.yaop"));
    const QString id = ws->createObject(QStringLiteral("note"), QStringLiteral("Backup roundtrip"));
    QImage img(64, 64, QImage::Format_RGB32);
    img.fill(QColor("#335577"));
    const QString png = QDir::tempPath() + QStringLiteral("/yaop-e2e-backup.png");
    img.save(png, "PNG");
    const QString assetId = ws->importFileAsAsset(png);
    auto digest = [&]() {
        QStringList d;
        for (const Object &o : ws->e2eAllObjects()) {
            const auto canon = e2eMergedCanonical(o);
            d << o.id + QLatin1Char(' ') + e2eMergedStateDigest(canon.first);
        }
        d.sort();
        return d.join(QLatin1Char('\n'));
    };
    const QString before = digest();
    const QString out = ws->exportBackup(file);
    if (!out.startsWith(QLatin1String("ok"))) {
        std::printf("E2E FAIL backup-roundtrip: export: %s\n", qPrintable(out));
        return 3;
    }
    const QString back = ws->restoreBackup(file);
    if (!back.startsWith(QLatin1String("ok")) || digest() != before || assetId.isEmpty()
        || !ws->hasAssetFile(assetId) || id.isEmpty()) {
        std::printf("E2E FAIL backup-roundtrip: restore: %s (digest %s)\n", qPrintable(back),
                    digest() == before ? "same" : "CHANGED");
        return 3;
    }
    QFile f(file);
    f.open(QIODevice::ReadWrite);
    f.seek(f.size() / 2);
    char c = 0;
    f.getChar(&c);
    f.seek(f.size() / 2);
    f.putChar(char(c ^ 0xff));
    f.close();
    if (ws->restoreBackup(file).startsWith(QLatin1String("ok"))) {
        std::printf("E2E FAIL backup-roundtrip: corrupted backup was accepted\n");
        return 3;
    }
    std::printf("E2ESTEP backup-roundtrip ok export=[%s] restore=[%s]\n", qPrintable(out), qPrintable(back));
    return 0;
}

// restore-backup: restores a .yaop (or legacy .facultas) file into this vault and prints the
// result plus how many objects of each type the vault holds afterwards. args: file=<path>
int e2eRestoreBackup(Workspace *ws, const QHash<QString, QString> &args)
{
    const QString out = ws->restoreBackup(args.value(QStringLiteral("file")));
    QMap<QString, int> byType;
    for (const Object &o : ws->e2eAllObjects())
        ++byType[o.typeId];
    QStringList counts;
    for (auto it = byType.constBegin(); it != byType.constEnd(); ++it)
        counts << QStringLiteral("%1=%2").arg(it.key()).arg(it.value());
    std::printf("E2ESTEP restore-backup %s [%s] types=%d\n", qPrintable(out), qPrintable(counts.join(' ')),
                int(ws->allTypes().size()));
    return out.startsWith(QLatin1String("ok")) ? 0 : 3;
}

// media-wire-ids: prints the blind wire id of every media object (to ask the server about its bytes)
int e2eMediaWireIds(Workspace *ws)
{
    for (const Object &o : ws->e2eAllObjects())
        if (o.id.startsWith(QLatin1String("media_")))
            std::printf("MEDIAWIRE %s\n", qPrintable(ws->e2eBlindId(o.id)));
    return 0;
}

int runE2eStep(Workspace *ws, const QString &step, const QString &args, const QString &outPath)
{
    if (step == QLatin1String("pair-host"))
        return e2ePairHost(ws, outPath);
    if (step == QLatin1String("pair-guest"))
        return e2ePairGuest(parseE2eArgs(args), outPath);
    // initialize() already ran a first sync+live connect; steps take it from here.
    if (step == QLatin1String("pull-assert"))
        return e2ePullAssert(ws, parseE2eArgs(args));
    if (step == QLatin1String("edit"))
        return e2eEdit(ws, parseE2eArgs(args));
    if (step == QLatin1String("digest"))
        return e2eDigest(ws, outPath);
    if (step == QLatin1String("media-wire-ids"))
        return e2eMediaWireIds(ws);
    if (step == QLatin1String("backup-roundtrip"))
        return e2eBackupRoundtrip(ws, parseE2eArgs(args));
    if (step == QLatin1String("restore-backup"))
        return e2eRestoreBackup(ws, parseE2eArgs(args));
    std::printf("E2E FAIL: unknown --e2e step %s (pull-assert|edit|digest|backup-roundtrip|restore-backup|pair-host|pair-guest)\n", qPrintable(step));
    return 2;
}

} // namespace pkm
