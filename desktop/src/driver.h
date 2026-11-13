#pragma once

#include <QString>

class QQuickWindow;

namespace pkm {

class Workspace;

// Hidden diagnostics: --drive <scenario> runs a scripted sequence against the
// live UI (synthetic key events), capturing per-step screenshots and model
// snapshots. Scenarios: "black" (block merge + enter, the invisible-text
// repro), "mention" ("@" inline trigger). Creates its own scratch object.
// Output dir defaults to /tmp; YAOP_DRIVE_DIR redirects it.
int runDrive(QQuickWindow *window, Workspace *ws, const QString &scenario);

// Headless e2e steps (--e2e): CLI steps without UI over a live
// Workspace. `args` is "key=value;key=value" (values may contain spaces).
//   pull-assert : sync and assert every "id=title" pair from args exists locally
//                 (empty title → existence only; titles are never hardcoded here)
//   edit        : edit one object (title + appended block + mention per args), push, pull
//   digest      : write "E2EDIGEST <objectId> <sha256>" per object (and
//                 "E2EDIGEST_COUNT n") to outPath using the merged-state digest
// Returns the process exit code (0 = ok).
int runE2eStep(Workspace *ws, const QString &step, const QString &args, const QString &outPath);

// Visual-QA fixture (--showcase): one object holding EVERY block type plus a few sibling
// objects (task, journal-like note, image) so each page has content to lay out. Returns the
// showcase object's id.
QString seedShowcase(Workspace *ws);

} // namespace pkm
