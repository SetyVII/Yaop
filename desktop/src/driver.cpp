#include "driver.h"
#include "onboarding.h"
#include "editormodel.h"
#include "model.h"
#include "workspace.h"

#include <QCoreApplication>
#include <QCryptographicHash>
#include <QElapsedTimer>
#include <QEventLoop>
#include <QFile>
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

QQuickItem *editorPageOf(QQuickWindow *window)
{
    return window->findChild<QQuickItem *>(QStringLiteral("editorPage"));
}

QQuickItem *blocksViewOf(QQuickWindow *window)
{
    return window->findChild<QQuickItem *>(QStringLiteral("blocks"));
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
                    // leave before the 1.2 s save debounce, like a quick click back
                    QTimer::singleShot(200, window, [window, ws, noteId, targetId]() {
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
            else if (scenario == QLatin1String("dnd"))
                dndScenario(window);
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

    // A real image asset for the media block and the library.
    QImage img(900, 1200, QImage::Format_RGB32);
    img.fill(QColor("#27406b"));
    for (int y = 0; y < img.height(); ++y)
        for (int x = 0; x < img.width(); x += 3)
            img.setPixelColor(x, y, QColor::fromHsv((x + y) % 360, 120, 200));
    const QString imgPath = QDir::tempPath() + QStringLiteral("/yaop-showcase.png");
    img.save(imgPath, "PNG");
    const QString assetId = ws->importFileAsAsset(imgPath);

    const QString sibling = ws->createObject(QStringLiteral("task"), QStringLiteral("Review the quarterly plan"));
    ws->createObject(QStringLiteral("note"), QStringLiteral("A note with a deliberately long title that must elide or wrap instead of breaking the layout of its card"));

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

    o.blocks = { para(QStringLiteral("Intro paragraph with **inline** text.")), h1, h2, bullet, num, todo, toggle, child,
                 quote, callout, code, math, table, divider, bookmark, embed, image,
                 para(QStringLiteral("Last paragraph after everything.")) };
    ws->saveObject(o);

    // Sets (spec/query.md) so the Sets page has something to show.
    for (const char *t : { "Read the plan", "Call the carpenter", "Renew passport" })
        ws->createObject(QStringLiteral("task"), QString::fromUtf8(t));
    ws->createObject(QStringLiteral("book"), QStringLiteral("The Name of the Wind"));
    ws->createObject(QStringLiteral("book"), QStringLiteral("Sapiens"));
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
    ws->createSet(QStringLiteral("Open tasks"),
                  QVariantMap{ { "types", QVariantList{ "task" } },
                               { "filters", QVariantList{ QVariantMap{ { "prop", "status" }, { "op", "neq" }, { "value", "done" } } } },
                               { "sort", QVariantList{ QVariantMap{ { "prop", "@title" }, { "dir", "asc" } } } } });
    ws->createSet(QStringLiteral("Books"), QVariantMap{ { "types", QVariantList{ "book" } } });
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
    std::printf("E2E FAIL: unknown --e2e step %s (pull-assert|edit|digest|backup-roundtrip|pair-host|pair-guest)\n", qPrintable(step));
    return 2;
}

} // namespace pkm
